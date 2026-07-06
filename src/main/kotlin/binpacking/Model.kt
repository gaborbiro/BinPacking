package binpacking

data class Cell(val x: Int, val y: Int)

data class Shape(
    val name: String,
    val width: Int,
    val height: Int,
    val cells: Set<Cell>,      // occupied cells, relative to bottom-left of bounding box
    val ladders: List<Int>,    // x offsets within bounding box (0-indexed); at least one required
    val priority: Int          // 1 = most important (keep low), higher = less important
)

data class Container(val width: Int, val height: Int)

data class Config(
    val container: Container,
    val maxLadderGap: Int,
    val maxSolutions: Int,
    val weightPriority: Double,
    val weightMismatch: Double,
    val shapes: List<Shape>
)

data class Placement(
    val shape: Shape,
    val x: Int,   // left edge of bounding box in container (0-indexed)
    val y: Int    // bottom edge of bounding box in container (0-indexed)
) {
    val ladderGlobalXs: List<Int> get() = shape.ladders.map { x + it }
    val top: Int get() = y + shape.height   // first row above this building

    private val globalCells: Set<Cell> by lazy {
        shape.cells.mapTo(HashSet()) { Cell(x + it.x, y + it.y) }
    }

    /** True if [upper] has at least one cell sitting directly on top of a cell of this placement. */
    fun isDirectlyBelow(upper: Placement): Boolean =
        upper.shape.cells.any { Cell(upper.x + it.x, upper.y + it.y - 1) in globalCells }

    /** Minimum ladder-column distance between this and [other]. 0 if either has no ladders. */
    fun minLadderGap(other: Placement): Int {
        if (ladderGlobalXs.isEmpty() || other.ladderGlobalXs.isEmpty()) return 0
        return ladderGlobalXs.minOf { la -> other.ladderGlobalXs.minOf { lb -> kotlin.math.abs(la - lb) } }
    }
}

data class Solution(
    val placements: List<Placement>,
    val score: Double,
    val priorityCost: Double,
    val mismatchCount: Int,
    val key: String
)
