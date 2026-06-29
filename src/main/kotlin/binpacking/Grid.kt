package binpacking

class Grid(val width: Int, val height: Int) {

    private val occupied = mutableSetOf<Cell>()

    fun settleY(shape: Shape, px: Int): Int {
        // Compute lower bound: for each column of the shape, find the highest
        // occupied cell and the lowest shape cell in that column, then derive
        // the minimum py needed to clear it (ignoring holes in the shape column).
        var minPy = 0
        val colGroups = shape.cells.groupBy { it.x }
        for ((cx, cells) in colGroups) {
            val globalCol = px + cx
            val colHeight = occupied.filter { it.x == globalCol }.maxOfOrNull { it.y + 1 } ?: 0
            val minCy = cells.minOf { it.y }
            minPy = maxOf(minPy, colHeight - minCy)
        }

        // Linear scan from lower bound to handle shapes with holes in columns
        while (true) {
            if (minPy + shape.height > height) return -1
            if (canPlace(shape, px, minPy)) return minPy
            minPy++
        }
    }

    fun canPlace(shape: Shape, px: Int, py: Int): Boolean {
        for (cell in shape.cells) {
            val gx = px + cell.x
            val gy = py + cell.y
            if (gx < 0 || gx >= width || gy < 0 || gy >= height) return false
            if (Cell(gx, gy) in occupied) return false
        }
        return true
    }

    fun place(shape: Shape, px: Int, py: Int) {
        shape.cells.forEach { occupied.add(Cell(px + it.x, py + it.y)) }
    }

    fun unplace(shape: Shape, px: Int, py: Int) {
        shape.cells.forEach { occupied.remove(Cell(px + it.x, py + it.y)) }
    }

    fun availableArea(): Int = width * height - occupied.size
}
