package binpacking

import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: binpacking <puzzle.bp>")
        return
    }

    val file = File(args[0])
    if (!file.exists()) {
        System.err.println("File not found: ${args[0]}")
        return
    }

    val config = try {
        Parser.parse(file.readText())
    } catch (e: Exception) {
        System.err.println("Parse error: ${e.message}")
        return
    }

    val containerArea = config.container.width * config.container.height
    val shapesArea    = config.shapes.sumOf { it.cells.size }
    println("Container: ${config.container.width}x${config.container.height}  (${containerArea} cells)")
    println("Buildings: ${config.shapes.size}  total surface: ${shapesArea} cells  (${containerArea - shapesArea} unused)")
    println("Solving...")
    println()

    print("  0%  (0 solutions found)   ")
    val startMs = System.currentTimeMillis()
    val solutions = Solver(config) { pct, covered, total, found, best ->
        val bestStr = if (best != null) "  best: ${String.format("%.2f", best)}" else ""
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
        val remaining = total - covered
        val etaStr = if (covered > 0) {
            val etaSec = remaining / (covered.toDouble() / elapsed * 1000)
            formatDuration(etaSec.toLong())
        } else "?"
        print("\r  $pct%  covered: ${"%,d".format(covered)} / ${"%,d".format(total)}  ETA: $etaStr  ($found solutions found$bestStr)   ")
    }.solve()
    println()

    if (solutions.isEmpty()) {
        println("No valid arrangement found — all buildings cannot fit simultaneously.")
        return
    }

    val capped = solutions.size == config.maxSolutions
    println("Found ${solutions.size} solution(s)${if (capped) " (capped at max_solutions=${config.maxSolutions})" else ""}.")
    println()

    val outputLines = StringBuilder()
    solutions.forEachIndexed { idx, solution ->
        outputLines.append(Renderer.renderSolution(solution, config, idx + 1, solutions.size))
        outputLines.appendLine()
    }

    val outputText = outputLines.toString()

    // Write to .out file alongside the input
    val outFile = file.resolveSibling(file.nameWithoutExtension + ".out")
    outFile.writeText(outputText)
    println("Results written to: ${outFile.absolutePath}")
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "0s"
    val years   = totalSeconds / (365L * 24 * 3600)
    val days    = (totalSeconds % (365L * 24 * 3600)) / (24 * 3600)
    val hours   = (totalSeconds % (24 * 3600)) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return listOf(
        years   to "year",
        days    to "day",
        hours   to "hr",
        minutes to "min",
        seconds to "sec"
    ).filter { it.first > 0 }
     .joinToString(", ") { (v, unit) -> "$v $unit${if (v != 1L) "s" else ""}" }
}

