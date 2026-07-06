package binpacking

object Renderer {

    fun renderSolution(solution: Solution, config: Config, rank: Int, total: Int, runId: String = ""): String {
        val sb = StringBuilder()

        val runTag = if (runId.isNotEmpty()) "  |  $runId" else ""
        val totalStr = if (total == -1) "?" else total.toString()
        sb.appendLine("=== Rank $rank/$totalStr  score=${String.format("%.2f", solution.score)}$runTag ===")
        sb.appendLine("  placed: ${solution.placements.size}/${config.shapes.size}")

        val nameWidth = solution.placements.maxOfOrNull { it.shape.name.length } ?: 0
        for (p in solution.placements.sortedBy { it.shape.name }) {
            val ladderMark = solution.placements.any { other ->
                other !== p &&
                (p.isDirectlyBelow(other) || other.isDirectlyBelow(p)) &&
                p.minLadderGap(other) > config.maxLadderGap
            }

            val mark = if (ladderMark) " [LADDER MISMATCH]" else ""
            sb.appendLine(
                "  ${p.shape.name.padEnd(nameWidth)}  pos=(${p.x},${p.y})  " +
                "ladder_globals=${p.ladderGlobalXs.joinToString(",")}  priority=${p.shape.priority}$mark"
            )
        }

        sb.appendLine("  priority_cost=${String.format("%.2f", solution.priorityCost)}  " +
                      "ladder_mismatches=${solution.mismatchCount}  key: ${solution.key}")
        sb.appendLine()
        sb.append(renderGrid(solution, config))

        return sb.toString()
    }

    private fun rangesOverlap(l1: Int, r1: Int, l2: Int, r2: Int) = l1 <= r2 && l2 <= r1

    // ─────────────────────────────────────────────────────────────
    // Grid rendering — each container cell is 2 chars wide × 1 row tall.
    // y=0 (bottom) → last screen row.  y=H-1 (top) → first screen row.
    // Building borders follow the bounding box; carved cells show as '░░'.
    // ─────────────────────────────────────────────────────────────
    private fun renderGrid(solution: Solution, config: Config): String {
        val W  = config.container.width
        val H  = config.container.height
        val CW = 2 * W  // character width

        // charGrid[screenRow][charCol], filled with '.' initially
        val charGrid  = Array(H) { CharArray(CW) { '.' } }
        // solidGrid tracks which char positions have been written by a real (non-carved) cell.
        // Carved ░░ cells must not overwrite real cells from an adjacent building's bounding box.
        val solidGrid = Array(H) { BooleanArray(CW) }

        for (p in solution.placements) {
            drawBuilding(p, charGrid, solidGrid, H)
        }

        val sb = StringBuilder()
        sb.append('╔'); repeat(CW) { sb.append('═') }; sb.appendLine('╗')
        for (row in charGrid) {
            sb.append('║'); sb.append(String(row)); sb.appendLine('║')
        }
        sb.append('╚'); repeat(CW) { sb.append('═') }; sb.appendLine('╝')
        return sb.toString()
    }

    private fun drawBuilding(p: Placement, charGrid: Array<CharArray>, solidGrid: Array<BooleanArray>, containerH: Int) {
        val shape = p.shape
        val px = p.x
        val py = p.y
        val w  = shape.width
        val h  = shape.height

        for (cy in 0 until h) {
            val screenRow = containerH - 1 - (py + cy)
            for (cx in 0 until w) {
                val charCol = 2 * (px + cx)   // 2 chars per cell
                val cell = Cell(cx, cy)

                if (cell !in shape.cells) {
                    // Carved cell: only draw ░░ if no real cell from another building is here
                    if (!solidGrid[screenRow][charCol]) {
                        charGrid[screenRow][charCol]     = '.'
                        charGrid[screenRow][charCol + 1] = '.'
                    }
                } else {
                    val (c1, c2) = buildingChars(cx, cy, w, h, shape.name, shape.ladders.toSet())
                    charGrid[screenRow][charCol]     = c1
                    charGrid[screenRow][charCol + 1] = c2
                    solidGrid[screenRow][charCol]     = true
                    solidGrid[screenRow][charCol + 1] = true
                }
            }
        }
    }

    // Returns the two characters for cell (cx, cy) in a building of size w×h.
    //
    // Each cell is 2 chars wide. Layout:
    //   Top row:    ╔═  ══  ══  ═╗
    //   Interior:   ║   ╫(ladder)  ║   (╫ in ladder column)
    //   Name row:   ║   <name...>  ║   (2 name chars per interior cell)
    //   Bottom row: ╚═  ══  ╫═  ═╝   (╫ at ladder column)
    private fun buildingChars(cx: Int, cy: Int, w: Int, h: Int, name: String, ladders: Set<Int>): Pair<Char, Char> {
        val isTop       = cy == h - 1
        val isBottom    = cy == 0
        val isLeft      = cx == 0
        val isRight     = cx == w - 1
        val isNameRow   = cy == 1 && h >= 3
        val isLadderCol = cx in ladders

        return when {
            // Top border — ladder overrides corners
            isTop && isLeft && isLadderCol  -> '╓' to '─'
            isTop && isRight && isLadderCol -> '─' to '╖'
            isTop && isLeft                 -> '┌' to '─'
            isTop && isRight                -> '─' to '┐'
            isTop && isLadderCol            -> '╥' to '─'
            isTop                           -> '─' to '─'

            // Bottom border — ladder overrides corners
            isBottom && isLeft && isLadderCol  -> '╙' to '─'
            isBottom && isRight && isLadderCol -> '─' to '╜'
            isBottom && isLeft                 -> '└' to '─'
            isBottom && isRight                -> '─' to '┘'
            isBottom && isLadderCol            -> '╨' to '─'
            isBottom                           -> '─' to '─'

            // Interior walls — ladder overrides wall chars
            isLeft && isLadderCol  -> '╫' to ' '
            isRight && isLadderCol -> ' ' to '╫'
            isLeft                 -> '│' to ' '
            isRight                -> ' ' to '│'

            // Name row: 2 name chars per interior cell; ladder shows if name runs out
            isNameRow -> {
                val i  = 2 * (cx - 1)
                val c1 = name.getOrNull(i)     ?: if (isLadderCol) '╫' else ' '
                val c2 = name.getOrNull(i + 1) ?: ' '
                c1 to c2
            }

            // Ladder column in all other interior rows
            isLadderCol -> '╫' to ' '

            else -> ' ' to ' '
        }
    }
}
