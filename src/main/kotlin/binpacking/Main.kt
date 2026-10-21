package binpacking

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class BestInfo(val score: Double, val runId: String)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: binpacking <puzzle.bp> [--resume | <checkpoint>]")
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

    val outFile  = file.resolveSibling(file.nameWithoutExtension + ".out")
    val ckptFile = file.resolveSibling(file.nameWithoutExtension + ".ckpt")

    // Resolve checkpoint: --resume reads .ckpt file; bare string is used directly.
    val encodedCheckpoint: String? = when {
        args.size >= 2 && args[1] == "--resume" -> {
            if (ckptFile.exists()) ckptFile.readText().trim()
            else { System.err.println("No checkpoint file found (${ckptFile.name})."); null }
        }
        args.size >= 2 && !args[1].startsWith("-") -> args[1]
        else -> null
    }

    val decoded = encodedCheckpoint?.let { decodeCheckpoint(it, config) }
    val isResume = decoded != null
    val resumeSkipTasks = decoded?.completedTasks ?: emptySet()
    val resumeBestScore = decoded?.bestScore      ?: Double.MAX_VALUE
    val resumeBestRunId = decoded?.bestRunId      ?: ""

    val resumeSeenKeys: Set<String> =
        if (isResume && outFile.exists()) parseSeenKeys(outFile) else emptySet()

    val runId     = generateRunId()
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val header    = "Run: $runId  |  $timestamp"

    if (isResume) println("Resuming (${resumeSkipTasks.size} tasks done, ${resumeSeenKeys.size} solutions known)")
    val containerArea = config.container.width * config.container.height
    val shapesArea    = config.shapes.sumOf { it.cells.size }
    println(header)
    println("Container: ${config.container.width}x${config.container.height}  ($containerArea cells)")
    println("Buildings: ${config.shapes.size}  total surface: $shapesArea cells  (${containerArea - shapesArea} unused)")

    val completedTasks = ConcurrentHashMap.newKeySet<String>()
    completedTasks.addAll(resumeSkipTasks)

    val lastBest = AtomicReference<BestInfo?>(
        if (resumeBestScore < Double.MAX_VALUE) BestInfo(resumeBestScore, resumeBestRunId) else null
    )

    fun buildCheckpoint(): String {
        val b = lastBest.get()
        return encodeCheckpoint(config, b?.score ?: Double.MAX_VALUE, b?.runId ?: "", completedTasks)
    }

    val writer = if (isResume) java.io.BufferedWriter(java.io.FileWriter(outFile, true))
                 else          outFile.bufferedWriter(Charsets.UTF_8)

    ckptFile.writeText(buildCheckpoint())

    val shutdownHook = Thread {
        synchronized(writer) { try { writer.close() } catch (_: Exception) {} }
        ckptFile.writeText(buildCheckpoint())
        println("\nCheckpoint saved to ${ckptFile.name}")
        println("Resume with: binpacking ${file.name} --resume")
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    val startMs = System.currentTimeMillis()
    var solutionSpacePrinted = false

    val solutions = Solver(
        config,
        onProgress = { pct, covered, total, found ->
            if (!solutionSpacePrinted) {
                println("Solution space: ${"%,.0f".format(total)} nodes")
                println()
                println("Solving...")
                solutionSpacePrinted = true
            }
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
            val etaStr = if (covered > 0) {
                val etaSec = (total - covered) / (covered / elapsed * 1000)
                formatDuration(etaSec.toLong())
            } else "?"
            val b = lastBest.get()
            val bestStr = if (b != null) "%.2f (%s)".format(b.score, b.runId) else "none"
            print("\r  ${"%.15f".format(pct)}%  $found found | best score: $bestStr | ETA: $etaStr   ")
            ckptFile.writeText(buildCheckpoint())
        },
        onSolutionFound = { solution, found ->
            lastBest.updateAndGet { prev ->
                if (prev == null || solution.score < prev.score) BestInfo(solution.score, runId) else prev
            }
            synchronized(writer) {
                writer.write(Renderer.renderSolution(solution, config, found, if (config.maxSolutions == -1) -1 else config.maxSolutions, runId))
                writer.newLine()
                writer.flush()
            }
        },
        onRootTaskDone = { taskId ->
            completedTasks.add(taskId)
            ckptFile.writeText(buildCheckpoint())
        },
        resumeSkipTasks = resumeSkipTasks,
        resumeBestScore = resumeBestScore,
        resumeSeenKeys  = resumeSeenKeys
    ).solve()

    println()
    synchronized(writer) { writer.close() }

    try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: Exception) {}

    val totalInFile = rewriteSortedOut(outFile, header)

    if (totalInFile == 0) {
        println("No valid arrangement found.")
        return
    }

    val capped = config.maxSolutions != -1 && solutions.size == config.maxSolutions
    println("Found ${solutions.size} new solution(s)${if (capped) " (capped at ${config.maxSolutions})" else ""}. $totalInFile total in file.")
    println()
    println("Results written to: ${outFile.absolutePath}")

    ckptFile.writeText(buildCheckpoint())
    println("Checkpoint: ${buildCheckpoint()}")
    println("Resume with: binpacking ${file.name} --resume")
}

// ── Checkpoint encode/decode ────────────────────────────────────────────────

// Bump this whenever the exploration order, scoring, or task decomposition changes.
const val CHECKPOINT_VERSION = 2

fun configHash(config: Config): String {
    val key = "${config.container.width}x${config.container.height}" +
              "|w${config.weightPriority},${config.weightMismatch},gap${config.maxLadderGap}" +
              "|" + config.shapes.sortedBy { it.name }.joinToString(";") { s ->
                  "${s.name}:${s.width}x${s.height}:p${s.priority}:" +
                  s.cells.sortedWith(compareBy({ it.x }, { it.y })).joinToString(",") { "(${it.x},${it.y})" } +
                  ":ls${s.ladders.sorted().joinToString(",")}"
              }
    return "%08x".format(key.hashCode())
}

fun encodeCheckpoint(config: Config, bestScore: Double, bestRunId: String, completedTasks: Collection<String>): String {
    val best = if (bestScore >= Double.MAX_VALUE / 2) "inf" else bestScore.toString()
    val raw  = "v$CHECKPOINT_VERSION|${configHash(config)}|$best|$bestRunId|${completedTasks.sorted().joinToString(",")}"
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
}

data class CheckpointData(val completedTasks: Set<String>, val bestScore: Double, val bestRunId: String)

/** Returns null (and prints a clear error) if the checkpoint is incompatible. */
fun decodeCheckpoint(encoded: String, config: Config): CheckpointData? = try {
    val raw   = String(java.util.Base64.getUrlDecoder().decode(encoded))
    val parts = raw.split("|", limit = 5)
    require(parts.size == 5) { "malformed checkpoint" }

    val version = parts[0].removePrefix("v").toIntOrNull() ?: error("unreadable version tag")
    require(version == CHECKPOINT_VERSION) {
        "checkpoint is version $version, solver is version $CHECKPOINT_VERSION — start fresh"
    }
    require(parts[1] == configHash(config)) {
        "puzzle config has changed since checkpoint was saved — start fresh"
    }
    val bestScore = parts[2].let { if (it == "inf") Double.MAX_VALUE else it.toDouble() }
    val bestRunId = parts[3]
    val tasks     = parts[4].let { if (it.isEmpty()) emptySet() else it.split(",").toHashSet() }
    CheckpointData(tasks, bestScore, bestRunId)
} catch (e: Exception) {
    System.err.println("Cannot resume: ${e.message}")
    null
}

// ── Resume helpers ──────────────────────────────────────────────────────────

fun parseSeenKeys(file: File): Set<String> =
    file.readLines()
        .filter { it.contains("  key: ") }
        .mapTo(HashSet()) { it.substringAfter("  key: ").trim() }

/** Re-reads the .out file, sorts all solution blocks by score, rewrites with updated ranks. */
fun rewriteSortedOut(outFile: File, header: String): Int {
    if (!outFile.exists()) return 0
    val text = outFile.readText()
    val rankRegex = Regex("""=== Rank \d+/\d+  score=([\d.]+)""")
    val found = rankRegex.findAll(text).toList()
    if (found.isEmpty()) return 0

    val blocks = found.mapIndexed { i, m ->
        val start = m.range.first
        val end   = if (i + 1 < found.size) found[i + 1].range.first else text.length
        m.groupValues[1].toDouble() to text.substring(start, end)
    }.sortedBy { it.first }

    outFile.bufferedWriter(Charsets.UTF_8).use { w ->
        w.write("$header\n\n")
        blocks.forEachIndexed { idx, (_, block) ->
            w.write(block.replaceFirst(
                Regex("""=== Rank \d+/\d+"""),
                "=== Rank ${idx + 1}/${blocks.size}"
            ))
        }
    }
    return blocks.size
}

// ── Utilities ───────────────────────────────────────────────────────────────

fun generateRunId(): String {
    val adj  = listOf("amber", "bold", "calm", "deep", "fierce", "golden", "iron", "jade", "keen", "lunar",
                      "misty", "noble", "old", "pale", "quiet", "rapid", "silver", "tall", "vast", "wild")
    val noun = listOf("bridge", "canyon", "dawn", "ember", "forge", "glade", "haven", "inlet", "jest", "knoll",
                      "ledge", "marsh", "notch", "orbit", "plain", "quartz", "ridge", "shore", "thorn", "vault")
    return "${adj.random().replaceFirstChar { it.uppercase() }}-${noun.random().replaceFirstChar { it.uppercase() }}-${(100..999).random()}"
}

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "0s"
    val years   = totalSeconds / (365L * 24 * 3600)
    val days    = (totalSeconds % (365L * 24 * 3600)) / (24 * 3600)
    val hours   = (totalSeconds % (24 * 3600)) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    fun fmt(v: Long, unit: String): String {
        val (n, suffix) = when {
            v >= 1_000_000_000L -> Pair(v / 1_000_000_000L, "B")
            v >= 1_000_000L     -> Pair(v / 1_000_000L,     "M")
            v >= 1_000L         -> Pair(v / 1_000L,         "K")
            else                -> Pair(v,                  "")
        }
        return "$n$suffix $unit"
    }
    return listOf(years to "y", days to "d", hours to "h", minutes to "m", seconds to "s")
        .filter { it.first > 0 }
        .joinToString(" ") { (v, unit) -> fmt(v, unit) }
}
