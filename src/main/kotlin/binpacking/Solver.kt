package binpacking

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.AtomicLong

class Solver(
    private val config: Config,
    private val onProgress:       ((pct: Double, covered: Double, total: Double, found: Int) -> Unit)? = null,
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

    @Volatile private var saRunning = false
    private var greedyPerm: List<Shape> = emptyList()
    private var greedyXs:  IntArray     = IntArray(0)

    fun solve(): List<Solution> {
        seenKeys.addAll(resumeSeenKeys)
        bestScore = resumeBestScore

        greedySeed()

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

            // Symmetry: among equivalent shapes, only create root tasks for the lowest-index one.
            if ((0 until shapeIdx).any { j -> equivalentShapes(config.shapes[j], shape) }) {
                val numFitX = (0 until config.container.width).count { px -> px + shape.width <= config.container.width }
                credit(numFitX.toDouble() * (subtreeSize[childMask] + 1.0))
                continue
            }

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

        saRunning = true
        val saThread = Thread(::runSA, "sa-worker").also { it.isDaemon = true; it.start() }

        val pool = ForkJoinPool(Runtime.getRuntime().availableProcessors())
        try {
            pool.submit { tasks.forEach { pool.submit(it) } }.get()
            pool.awaitQuiescence(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
        } finally {
            pool.shutdown()
        }

        saRunning = false
        saThread.join(2000)

        return solutions.sortedBy { it.score }
    }

    // Fast greedy pass: for each shape (in priority order) pick the x that minimises
    // the immediate score increase. Seeds bestScore before B&B tasks start so that
    // pruning is effective from the very first root task.
    private fun greedySeed() {
        val grid   = Grid(config.container.width, config.container.height)
        val placed = mutableListOf<Placement>()

        for (shape in config.shapes.sortedBy { it.priority }) {
            var bestPlacement: Placement? = null
            var bestCost = Double.MAX_VALUE

            for (px in 0..config.container.width - shape.width) {
                val py = grid.settleY(shape, px)
                if (py == -1) continue
                val p    = Placement(shape, px, py)
                val cost = (py.toDouble() / shape.priority) * config.weightPriority +
                           countNewMismatches(p, placed) * config.weightMismatch
                if (cost < bestCost) { bestCost = cost; bestPlacement = p }
            }

            if (bestPlacement == null) return   // shape doesn't fit — abandon
            grid.place(shape, bestPlacement.x, bestPlacement.y)
            placed.add(bestPlacement)
        }

        if (placed.size < config.shapes.size) return

        greedyPerm = placed.map { it.shape }
        greedyXs   = IntArray(placed.size) { placed[it].x }

        val solution = Scorer.buildSolution(placed, config)
        if (seenKeys.add(solution.key)) {
            solutions.add(solution)
            if (solution.score < bestScore) bestScore = solution.score
            onSolutionFound?.invoke(solution, solutions.size)
        }
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

            // Symmetry: only try the first equivalent shape at this level (lowest index in remaining).
            // After removeAt(i), remaining[0..i-1] are the shapes that come before i — unchanged.
            if ((0 until i).any { j -> equivalentShapes(remaining[j], shape) }) {
                val numFitX = (0 until config.container.width).count { it + shape.width <= config.container.width }
                credit(numFitX.toDouble() * subtreeSize[childMask])
                remaining.add(i, shape)
                continue
            }

            // Symmetry: enforce non-decreasing x among placements of equivalent shapes.
            val minXSymmetry = placed.filter { equivalentShapes(it.shape, shape) }.maxOfOrNull { it.x } ?: 0
            val numSkippedX  = (0 until config.container.width).count { it + shape.width <= config.container.width && it < minXSymmetry }
            if (numSkippedX > 0) credit(numSkippedX.toDouble() * subtreeSize[childMask])

            val xPositions = (0 until config.container.width)
                .filter { px -> px + shape.width <= config.container.width && px >= minXSymmetry }
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

    // Evaluates a full placement sequence and returns the resulting Solution,
    // or null if any shape cannot be settled (no room).
    private fun evaluate(perm: List<Shape>, xs: IntArray): Solution? {
        val grid   = Grid(config.container.width, config.container.height)
        val placed = mutableListOf<Placement>()
        for ((i, shape) in perm.withIndex()) {
            val py = grid.settleY(shape, xs[i])
            if (py == -1) return null
            grid.place(shape, xs[i], py)
            placed.add(Placement(shape, xs[i], py))
        }
        return Scorer.buildSolution(placed, config)
    }

    // Simulated annealing worker: runs alongside B&B, sharing bestScore and seenKeys.
    // State = (permutation of shapes, x position per shape). Two move types:
    //   swap  — exchange two shapes' permutation slots (changes settling order)
    //   shift — move one shape's x by ±1
    // Restarts periodically by reheating with a shuffled permutation seeded from
    // the best solution found so far.
    private fun runSA() {
        val n = config.shapes.size
        if (n == 0) return
        val rng = java.util.Random()

        val perm: MutableList<Shape> =
            if (greedyPerm.isNotEmpty()) greedyPerm.toMutableList()
            else config.shapes.toMutableList().also { java.util.Collections.shuffle(it, rng) }
        val xs: IntArray =
            if (greedyXs.isNotEmpty()) greedyXs.copyOf()
            else IntArray(n) { i -> rng.nextInt((config.container.width - perm[i].width).coerceAtLeast(0) + 1) }

        var currentScore = evaluate(perm, xs)?.score ?: Double.MAX_VALUE

        val tInitial = if (currentScore < Double.MAX_VALUE) (currentScore * 0.05).coerceAtLeast(1.0) else 10.0
        var T        = tInitial
        val tMin     = tInitial * 1e-4

        while (saRunning) {
            if (config.maxSolutions != -1 && solutions.size >= config.maxSolutions) break

            // Apply move in-place, record undo info
            val doSwap = n > 1 && rng.nextBoolean()
            val idx1   = rng.nextInt(n)
            var idx2   = 0; var savedX = 0

            if (doSwap) {
                idx2 = rng.nextInt(n - 1).let { if (it >= idx1) it + 1 else it }
                val t  = perm[idx1]; perm[idx1] = perm[idx2]; perm[idx2] = t
                val tx = xs[idx1];   xs[idx1]   = xs[idx2];   xs[idx2]   = tx
            } else {
                savedX = xs[idx1]
                val maxX = (config.container.width - perm[idx1].width).coerceAtLeast(0)
                xs[idx1] = (xs[idx1] + if (rng.nextBoolean()) 1 else -1).coerceIn(0, maxX)
            }

            val newSol   = evaluate(perm, xs)
            val newScore = newSol?.score ?: Double.MAX_VALUE

            // Record global improvement regardless of SA acceptance
            if (newSol != null && newScore < bestScore && seenKeys.add(newSol.key)) {
                solutions.add(newSol)
                bestScore = newScore
                onSolutionFound?.invoke(newSol, solutions.size)
            }

            // SA acceptance criterion
            val delta = newScore - currentScore
            if (delta < 0 || (T > tMin && rng.nextDouble() < Math.exp(-delta / T))) {
                currentScore = newScore
            } else {
                if (doSwap) {
                    val t  = perm[idx1]; perm[idx1] = perm[idx2]; perm[idx2] = t
                    val tx = xs[idx1];   xs[idx1]   = xs[idx2];   xs[idx2]   = tx
                } else {
                    xs[idx1] = savedX
                }
            }

            T *= 0.99995
            if (T < tMin) {
                // Reheat: random permutation, seed x positions from best known solution
                T = tInitial
                val best = solutions.minByOrNull { it.score }
                java.util.Collections.shuffle(perm, rng)
                if (best != null) {
                    val xByName = best.placements.associate { it.shape.name to it.x }
                    for (i in 0 until n) xs[i] = xByName[perm[i].name] ?: xs[i]
                }
                currentScore = evaluate(perm, xs)?.score ?: Double.MAX_VALUE
            }
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
            val pct = ((c / theoreticalTotal) * 100).coerceIn(0.0, 100.0)
            onProgress?.invoke(pct, c, theoreticalTotal, solutions.size)
        }
    }

    private fun equivalentShapes(a: Shape, b: Shape) =
        a.width == b.width && a.height == b.height &&
        a.cells == b.cells && a.ladders == b.ladders &&
        a.priority == b.priority

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
