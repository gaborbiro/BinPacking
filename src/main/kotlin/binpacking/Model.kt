package binpacking

data class Cell(val x: Int, val y: Int)

data class Shape(
    val name: String,
    val width: Int,
    val height: Int,
    val cells: Set<Cell>,   // occupied cells, relative to bottom-left of bounding box
    val ladder: Int,        // x offset within bounding box (0-indexed)
    val priority: Int       // 1 = most important (keep low), higher = less important
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
    val ladderGlobalX: Int get() = x + shape.ladder
    val top: Int get() = y + shape.height   // first row above this building
}

data class Solution(
    val placements: List<Placement>,
    val score: Double,
    val priorityCost: Double,
    val mismatchCount: Int
)
