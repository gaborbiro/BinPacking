package binpacking

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong

class Solver(private val config: Config, private val onProgress: ((pct: Int, covered: Long, total: Long, found: Int, best: Double?) -> Unit)? = null) {

    private val solutions = CopyOnWriteArrayList<Solution>()
    private val seenKeys  = ConcurrentHashMap.newKeySet<String>()
    private val covered   = AtomicLong(0)

    // Best score seen so far — used for branch-and-bound pruning.
    // Only ever decreases; a stale read just means slightly less pruning, never wrong results.
    @Volatile private var bestScore = Double.MAX_VALUE

    private lateinit var subtreeSize: LongArray
    private var theoreticalTotal = 1L

    private val reportCounter = AtomicLong(0)

    fun solve(): List<Solution> {
        val n = config.shapes.size
        val xCounts = config.shapes.map { (config.container.width - it.width + 1).coerceAtLeast(0).toLong() }

        subtreeSize = LongArray(1 shl n)
        subtreeSize[0] = 1L
        for (mask in 1 until (1 shl n)) {
            var t = 1L
            for (i in 0 until n) {
                if (mask and (1 shl i) == 0) continue
                t += xCounts[i] * subtreeSize[mask xor (1 shl i)]
            }
            subtreeSize[mask] = t
        }
        theoreticalTotal = subtreeSize[(1 shl n) - 1]

        val fullMask = (1 shl n) - 1
        val tryOrder = config.shapes.indices.sortedBy { config.shapes[it].priority }

        val tasks = mutableListOf<Runnable>()
        for (shapeIdx in tryOrder) {
            val shape     = config.shapes[shapeIdx]
            val childMask = fullMask xor (1 shl shapeIdx)
            val xPositions = (0 until config.container.width)
                .filter { px -> px + shape.width <= config.container.width }
                .sortedBy { px -> ladderMismatchCost(shape, px, emptyList()) }

            for (px in xPositions) {
                tasks.add(Runnable {
                    if (solutions.size >= config.maxSolutions) {
                        credit(subtreeSize[childMask] + 1)
                        return@Runnable
                    }

                    credit(1)

                    val grid = Grid(config.container.width, config.container.height)
                    val py   = grid.settleY(shape, px)

                    if (py == -1) {
                        credit(subtreeSize[childMask])
                    } else {
                        val placement        = Placement(shape, px, py)
                        val initPriorityCost = py.toDouble() / shape.priority
                        val initMismatches   = 0  // no neighbours yet
                        val remaining        = config.shapes.toMutableList().also { it.removeAt(shapeIdx) }
                        val placed           = mutableListOf(placement)
                        grid.place(shape, px, py)
                        backtrack(remaining, childMask, placed, grid, initPriorityCost, initMismatches)
                    }
                })
            }
        }

        val pool = ForkJoinPool(Runtime.getRuntime().availableProcessors())
        try {
            pool.submit { tasks.forEach { pool.submit(it) } }.get()
            pool.awaitQuiescence(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
        } finally {
            pool.shutdown()
        }

        return solutions.sortedBy { it.score }
    }

    private fun backtrack(
        remaining: MutableList<Shape>,
        remainingMask: Int,
        placed: MutableList<Placement>,
        grid: Grid,
        partialPriorityCost: Double,
        partialMismatches: Int
    ) {
        credit(1)

        // Branch and bound: partial score is a valid lower bound (costs can only grow).
        val lowerBound = partialPriorityCost * config.weightPriority +
                         partialMismatches   * config.weightMismatch
        if (lowerBound >= bestScore) {
            credit(subtreeSize[remainingMask] - 1)
            return
        }

        if (solutions.size >= config.maxSolutions) {
            credit(subtreeSize[remainingMask] - 1)
            return
        }

        if (remaining.isEmpty()) {
            val solution = Scorer.buildSolution(placed, config)
            val key = solutionKey(placed)
            if (seenKeys.add(key)) {
                solutions.add(solution)
                if (solution.score < bestScore) bestScore = solution.score
            }
            return
        }

        val remainingArea = remaining.sumOf { it.cells.size }
        if (remainingArea > grid.availableArea()) {
            credit(subtreeSize[remainingMask] - 1)
            return
        }

        val tryOrder = remaining.indices.sortedBy { remaining[it].priority }
        for (i in tryOrder) {
            val shape     = remaining.removeAt(i)
            val shapeBit  = 1 shl config.shapes.indexOf(shape)
            val childMask = remainingMask xor shapeBit

            val xPositions = (0 until config.container.width)
                .filter { px -> px + shape.width <= config.container.width }
                .sortedBy { px -> ladderMismatchCost(shape, px, placed) }

            for (px in xPositions) {
                val py = grid.settleY(shape, px)
                if (py == -1) {
                    credit(subtreeSize[childMask])
                    continue
                }

                val placement    = Placement(shape, px, py)
                val newPriority  = partialPriorityCost + py.toDouble() / shape.priority
                val newMismatches = partialMismatches + countNewMismatches(placement, placed)

                // Prune before recursing if already provably worse.
                val childBound = newPriority * config.weightPriority +
                                 newMismatches * config.weightMismatch
                if (childBound >= bestScore) {
                    credit(subtreeSize[childMask])
                    continue
                }

                placed.add(placement)
                grid.place(shape, px, py)

                backtrack(remaining, childMask, placed, grid, newPriority, newMismatches)

                grid.unplace(shape, px, py)
                placed.removeLast()

                if (solutions.size >= config.maxSolutions) {
                    remaining.add(i, shape)
                    return
                }
            }

            remaining.add(i, shape)
        }
    }

    // Counts mismatches the new placement creates with already-placed shapes.
    // Each mismatch pair is counted once: when the second shape of the pair is placed.
    private fun countNewMismatches(p: Placement, placed: List<Placement>): Int {
        var count = 0
        for (other in placed) {
            val adjacent = other.top == p.y || p.top == other.y
            if (!adjacent) continue
            val overlap = p.x < other.x + other.shape.width && other.x < p.x + p.shape.width
            if (!overlap) continue
            if (kotlin.math.abs(p.ladderGlobalX - other.ladderGlobalX) > config.maxLadderGap) count++
        }
        return count
    }

    private fun credit(n: Long) {
        val c     = covered.addAndGet(n)
        val calls = reportCounter.incrementAndGet()
        if (calls and 0xFFFF == 0L) {
            val pct  = ((c.toDouble() / theoreticalTotal) * 100).toInt().coerceIn(0, 100)
            val best = solutions.minOfOrNull { it.score }
            onProgress?.invoke(pct, c, theoreticalTotal, solutions.size, best)
        }
    }

    private fun ladderMismatchCost(shape: Shape, px: Int, placed: List<Placement>): Int {
        val ladderGlobal = px + shape.ladder
        return placed.count { other ->
            val xOverlap = px < other.x + other.shape.width && other.x < px + shape.width
            xOverlap && kotlin.math.abs(ladderGlobal - other.ladderGlobalX) > config.maxLadderGap
        }
    }

    private fun solutionKey(placements: List<Placement>): String =
        placements
            .sortedWith(compareBy({ it.shape.name }, { it.x }, { it.y }))
            .joinToString("|") { "${it.shape.name}@${it.x},${it.y}" }
}
