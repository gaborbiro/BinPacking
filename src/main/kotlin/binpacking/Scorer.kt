package binpacking

object Scorer {

    fun buildSolution(placements: List<Placement>, config: Config): Solution {
        val priorityCost = placements.sumOf { p ->
            // Higher priority (lower number) buildings pay more for being high up.
            // Cost = y_bottom / priority, so priority-1 at y=10 costs 10, priority-3 costs 3.33.
            p.y.toDouble() / p.shape.priority
        }

        val mismatchCount = countMismatches(placements, config.maxLadderGap)
        val score = priorityCost * config.weightPriority + mismatchCount * config.weightMismatch

        val key = placements
            .sortedWith(compareBy({ it.shape.name }, { it.x }, { it.y }))
            .joinToString("|") { "${it.shape.name}@${it.x},${it.y}" }
        return Solution(placements.toList(), score, priorityCost, mismatchCount, key)
    }

    private fun countMismatches(placements: List<Placement>, maxGap: Int): Int {
        var count = 0
        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                val a = placements[i]
                val b = placements[j]
                if (!a.isDirectlyBelow(b) && !b.isDirectlyBelow(a)) continue
                if (a.minLadderGap(b) > maxGap) count++
            }
        }
        return count
    }
}
