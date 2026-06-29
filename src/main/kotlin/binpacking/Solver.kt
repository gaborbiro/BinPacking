package binpacking

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong

class Solver(private val config: Config, private val onProgress: ((pct: Int, covered: Long, total: Long, found: Int, best: Double?) -> Unit)? = null) {

    private val solutions = CopyOnWriteArrayList<Solution>()
    private val seenKeys  = ConcurrentHashMap.newKeySet<String>()
    private val covered   = AtomicLong(0)

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

        // Build root-level tasks: one per (shape, x) combination.
        val tasks = mutableListOf<Runnable>()
        for (shapeIdx in tryOrder) {
            val shape = config.shapes[shapeIdx]
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

                    credit(1)  // this root call

                    val grid = Grid(config.container.width, config.container.height)
                    val py   = grid.settleY(shape, px)

                    if (py == -1) {
                        credit(subtreeSize[childMask])
                    } else {
                        val remaining = config.shapes.toMutableList().also { it.removeAt(shapeIdx) }
                        val placed    = mutableListOf(Placement(shape, px, py))
                        grid.place(shape, px, py)
                        backtrack(remaining, childMask, placed, grid)
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
        grid: Grid
    ) {
        credit(1)

        if (solutions.size >= config.maxSolutions) {
            credit(subtreeSize[remainingMask] - 1)
            return
        }

        if (remaining.isEmpty()) {
            val solution = Scorer.buildSolution(placed, config)
            val key = solutionKey(placed)
            if (seenKeys.add(key)) solutions.add(solution)
            return
        }

        val remainingArea = remaining.sumOf { it.cells.size }
        if (remainingArea > grid.availableArea()) {
            credit(subtreeSize[remainingMask] - 1)
            return
        }

        val tryOrder = remaining.indices.sortedBy { remaining[it].priority }
        for (i in tryOrder) {
            val shape    = remaining.removeAt(i)
            val shapeBit = 1 shl config.shapes.indexOf(shape)
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

                placed.add(Placement(shape, px, py))
                grid.place(shape, px, py)

                backtrack(remaining, childMask, placed, grid)

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

    private fun credit(n: Long) {
        val c = covered.addAndGet(n)
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
