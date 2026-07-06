package binpacking

object Parser {

    fun parse(text: String): Config {
        val rawLines = text.lines()

        var containerWidth = 0
        var containerHeight = 0
        var maxLadderGap = 3
        var maxSolutions = 200
        var weightPriority = 1.0
        var weightMismatch = 5.0
        val shapes = mutableListOf<Shape>()

        var i = 0
        while (i < rawLines.size) {
            val raw = rawLines[i]
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) { i++; continue }

            val tokens = line.split(Regex("\\s+"))
            when (tokens[0]) {
                "container" -> {
                    containerWidth = tokens[1].toInt()
                    containerHeight = tokens[2].toInt()
                    i++
                }
                "max_ladder_gap"  -> { maxLadderGap  = tokens[1].toInt();    i++ }
                "max_solutions"   -> { maxSolutions   = tokens[1].toInt();    i++ }
                "weight_priority" -> { weightPriority = tokens[1].toDouble(); i++ }
                "weight_mismatch" -> { weightMismatch = tokens[1].toDouble(); i++ }
                "shape" -> {
                    val name = tokens[1]
                    if (tokens.size >= 4 && tokens[2].toIntOrNull() != null) {
                        // One-liner: shape Name W H [ladder=X,Y] [priority=N]
                        val w = tokens[2].toInt()
                        val h = tokens[3].toInt()
                        var ladders = listOf(0)
                        var priority = 1
                        for (t in tokens.drop(4)) {
                            if (t.startsWith("ladder="))   ladders  = t.substringAfter('=').split(",").map { it.toInt() }
                            if (t.startsWith("priority=")) priority = t.substringAfter('=').toInt()
                        }
                        shapes.add(makeShape(name, w, h, emptyList(), ladders, priority))
                        i++
                    } else {
                        // Block form — collect indented lines
                        i++
                        var w = 0; var h = 0; var ladders = listOf(0); var priority = 1
                        val carves = mutableListOf<IntArray>()

                        while (i < rawLines.size) {
                            val innerRaw = rawLines[i]
                            val innerLine = innerRaw.substringBefore('#')
                            if (innerLine.isBlank()) { i++; continue }
                            if (!innerRaw.first().isWhitespace()) break

                            val t = innerLine.trim().split(Regex("\\s+"))
                            when (t[0]) {
                                "size"     -> { w = t[1].toInt(); h = t[2].toInt() }
                                "carve"    -> carves.add(intArrayOf(t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt()))
                                "ladder"   -> ladders  = t.drop(1).map { it.toInt() }
                                "priority" -> priority = t[1].toInt()
                            }
                            i++
                        }
                        shapes.add(makeShape(name, w, h, carves, ladders, priority))
                    }
                }
                else -> i++
            }
        }

        require(containerWidth > 0 && containerHeight > 0) { "Missing or invalid 'container' directive" }

        return Config(
            Container(containerWidth, containerHeight),
            maxLadderGap, maxSolutions, weightPriority, weightMismatch, shapes
        )
    }

    private fun makeShape(
        name: String, w: Int, h: Int,
        carves: List<IntArray>, ladders: List<Int>, priority: Int
    ): Shape {
        require(w > 0 && h > 0) { "Shape '$name': size ${w}x${h} must be positive" }
        require(priority >= 1)   { "Shape '$name': priority $priority must be >= 1" }

        for (l in ladders) {
            require(l in 0 until w) {
                "Shape '$name': ladder column $l is out of bounds (width=$w)"
            }
        }

        for ((i, c) in carves.withIndex()) {
            val (cx, cy, cw, ch) = c
            require(cw > 0 && ch > 0) {
                "Shape '$name': carve[$i] ($cx $cy $cw $ch) has non-positive dimensions"
            }
            require(cx >= 0 && cy >= 0 && cx + cw <= w && cy + ch <= h) {
                "Shape '$name': carve[$i] ($cx $cy $cw $ch) is outside bounds (${w}x${h})"
            }
        }

        for (i in carves.indices) {
            for (j in i + 1 until carves.size) {
                val (x1, y1, w1, h1) = carves[i]
                val (x2, y2, w2, h2) = carves[j]
                require(x1 >= x2 + w2 || x2 >= x1 + w1 || y1 >= y2 + h2 || y2 >= y1 + h1) {
                    "Shape '$name': carve[$i] ($x1 $y1 $w1 $h1) overlaps carve[$j] ($x2 $y2 $w2 $h2)"
                }
            }
        }

        val cells = mutableSetOf<Cell>()
        for (x in 0 until w) for (y in 0 until h) cells.add(Cell(x, y))
        for (c in carves) {
            val (cx, cy, cw, ch) = c
            for (x in cx until cx + cw) for (y in cy until cy + ch) cells.remove(Cell(x, y))
        }
        require(cells.isNotEmpty()) { "Shape '$name': all cells have been carved away" }

        return Shape(name, w, h, cells, ladders, priority)
    }

    // Destructuring helper for IntArray
    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]
}
