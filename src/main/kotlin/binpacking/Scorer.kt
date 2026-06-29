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

        return Solution(placements.toList(), score, priorityCost, mismatchCount)
    }

    private fun countMismatches(placements: List<Placement>, maxGap: Int): Int {
        var count = 0
        for (i in placements.indices) {
            for (j in i + 1 until placements.size) {
                val a = placements[i]
                val b = placements[j]

                // Determine which is lower and which is upper
                val (lower, upper) = when {
                    a.top == b.y -> Pair(a, b)
                    b.top == a.y -> Pair(b, a)
                    else -> continue
                }

                // Check x-range overlap (bounding boxes)
                val lowerLeft  = lower.x;                      val lowerRight = lower.x + lower.shape.width - 1
                val upperLeft  = upper.x;                      val upperRight = upper.x + upper.shape.width - 1
                if (lowerRight < upperLeft || upperRight < lowerLeft) continue

                val gap = kotlin.math.abs(lower.ladderGlobalX - upper.ladderGlobalX)
                if (gap > maxGap) count++
            }
        }
        return count
    }
}
