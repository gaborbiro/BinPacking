package binpacking

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.AtomicLong

class Solver(
    private val config: Config,
    private val onProgress:       ((pct: Int, covered: Double, total: Double, found: Int) -> Unit)? = null,
    private val onSolutionFound:  ((solution: Solution, found: Int) -> Unit)? = null,
    private val onRootTaskDone:   ((taskId: String) -> Unit)? = null,
    private val resumeSkipTasks:  Set<String> = emptySet(),
    private val resumeBestScore:  Double = Double.MAX_VALUE,
    private val resumeSeenKeys:   Set<String> = emptySet()
) {
    private val solutions = CopyOnWriteArrayList<Solution>()
    private val seenKeys  = ConcurrentHashMap.newKeySet<String>()

    // Double to avoid Long overflow on large trees.
    private val covered  = DoubleAdder()
    private lateinit var subtreeSize: DoubleArray
    private var theoreticalTotal = 1.0

    @Volatile private var bestScore = Double.MAX_VALUE

    private val reportCounter = AtomicLong(0)

    fun solve(): List<Solution> {
        seenKeys.addAll(resumeSeenKeys)
        bestScore = resumeBestScore

        val n = config.shapes.size
        val xCounts = config.shapes.map { (config.container.width - it.width + 1).coerceAtLeast(0).toDouble() }

        subtreeSize = DoubleArray(1 shl n)
        subtreeSize[0] = 1.0
        for (mask in 1 until (1 shl n)) {
            var t = 1.0
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
                val taskId = "$shapeIdx:$px"
                tasks.add(Runnable {
                    if (taskId in resumeSkipTasks) {
                        credit(subtreeSize[childMask] + 1.0); return@Runnable
                    }

                    if (config.maxSolutions != -1 && solutions.size >= config.maxSolutions) {
                        credit(subtreeSize[childMask] + 1.0); return@Runnable
                    }

                    credit(1.0)

                    val grid = Grid(config.container.width, config.container.height)
                    val py   = grid.settleY(shape, px)

                    if (py == -1) {
                        credit(subtreeSize[childMask])
                    } else {
                        val placement        = Placement(shape, px, py)
                        val initPriorityCost = py.toDouble() / shape.priority
                        val remaining        = config.shapes.toMutableList().also { it.removeAt(shapeIdx) }
                        val placed           = mutableListOf(placement)
                        grid.place(shape, px, py)
                        backtrack(remaining, childMask, placed, grid, initPriorityCost, 0)
                    }

                    onRootTaskDone?.invoke(taskId)
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
        credit(1.0)

        val lowerBound = partialPriorityCost * config.weightPriority +
                         partialMismatches   * config.weightMismatch
        if (lowerBound >= bestScore) {
            credit(subtreeSize[remainingMask] - 1.0)
            return
        }

        if (config.maxSolutions != -1 && solutions.size >= config.maxSolutions) {
            credit(subtreeSize[remainingMask] - 1.0)
            return
        }

        if (remaining.isEmpty()) {
            val solution = Scorer.buildSolution(placed, config)
            if (seenKeys.add(solution.key)) {
                solutions.add(solution)
                if (solution.score < bestScore) bestScore = solution.score
                onSolutionFound?.invoke(solution, solutions.size)
            }
            return
        }

        val remainingArea = remaining.sumOf { it.cells.size }
        if (remainingArea > grid.availableArea()) {
            credit(subtreeSize[remainingMask] - 1.0)
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

                val placement     = Placement(shape, px, py)
                val newPriority   = partialPriorityCost + py.toDouble() / shape.priority
                val newMismatches = partialMismatches + countNewMismatches(placement, placed)

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

                if (config.maxSolutions != -1 && solutions.size >= config.maxSolutions) {
                    remaining.add(i, shape)
                    return
                }
            }

            remaining.add(i, shape)
        }
    }

    private fun countNewMismatches(p: Placement, placed: List<Placement>): Int =
        placed.count { other ->
            (p.isDirectlyBelow(other) || other.isDirectlyBelow(p)) &&
            p.minLadderGap(other) > config.maxLadderGap
        }

    private fun credit(n: Double) {
        covered.add(n)
        val calls = reportCounter.incrementAndGet()
        if (calls and 0xFFFF == 0L) {
            val c   = covered.sum()
            val pct = ((c / theoreticalTotal) * 100).toInt().coerceIn(0, 100)
            onProgress?.invoke(pct, c, theoreticalTotal, solutions.size)
        }
    }

    private fun ladderMismatchCost(shape: Shape, px: Int, placed: List<Placement>): Int {
        val ladderGlobals = shape.ladders.map { px + it }
        // Bounding-box x-overlap as a fast proxy (ordering hint only, not final score).
        return placed.count { other ->
            val xOverlap = px < other.x + other.shape.width && other.x < px + shape.width
            if (!xOverlap) return@count false
            val minGap = ladderGlobals.minOf { lg -> other.ladderGlobalXs.minOf { ol -> kotlin.math.abs(lg - ol) } }
            minGap > config.maxLadderGap
        }
    }

}
