package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * Four dayparts for segment-aware [UnifiedReactivityLearner] reactivity.
 * Pure math lives here so unit tests can validate combine/shrinkage/caps without DI.
 */
enum class ReactivityDaypart(
    val startHourInclusive: Int,
    val endHourExclusive: Int,
) {
    NIGHT_0_6(0, 6),
    MORNING_6_11(6, 11),
    MIDDAY_11_16(11, 16),
    EVENING_16_24(16, 24),
    ;

    fun jsonKey(): String = name

    companion object {
        const val GLOBAL_WEIGHT = 0.40
        const val SHORT_WEIGHT = 0.30
        const val SEGMENT_WEIGHT = 0.30
        const val SHRINKAGE_K = 50.0
        const val EXERCISE_HYPO_AMPLIFIER = 1.5
        const val FACTOR_MIN = 0.5
        const val FACTOR_MAX = 1.5

        fun fromHour(hourOfDay: Int): ReactivityDaypart {
            val hour = hourOfDay.coerceIn(0, 23)
            return entries.first { hour >= it.startHourInclusive && hour < it.endHourExclusive }
        }

        fun combineFactors(global: Double, short: Double, segment: Double): Double {
            return (global * GLOBAL_WEIGHT + short * SHORT_WEIGHT + segment * SEGMENT_WEIGHT)
                .coerceIn(FACTOR_MIN, FACTOR_MAX)
        }

        /** Bayesian shrinkage of segment evidence toward the day-global factor. */
        fun shrinkTowardGlobal(rawSegment: Double, global: Double, sampleCount: Int): Double {
            if (sampleCount <= 0) return global.coerceIn(FACTOR_MIN, FACTOR_MAX)
            return (rawSegment * sampleCount + global * SHRINKAGE_K) / (sampleCount + SHRINKAGE_K)
        }

        /**
         * Segment may exceed [global] only when locally hypo-free.
         * When hypos occurred in the segment, never be more aggressive than global.
         */
        fun capSegmentAgainstGlobal(segment: Double, global: Double, hypoCount: Int): Double {
            val clamped = segment.coerceIn(FACTOR_MIN, FACTOR_MAX)
            return if (hypoCount > 0) minOf(clamped, global) else clamped
        }

        /** Sport/exercise notes amplify hypo burden (not exclusion). */
        fun exerciseAmplifiedHypoCount(hypoCount: Int, exerciseInSegment: Boolean): Double {
            if (hypoCount <= 0) return 0.0
            return if (exerciseInSegment) hypoCount * EXERCISE_HYPO_AMPLIFIER else hypoCount.toDouble()
        }

        fun segmentAdjustmentFromHypoBurden(effectiveHypoCount: Double): Double {
            return when {
                effectiveHypoCount >= 3.0 -> 0.80
                effectiveHypoCount >= 2.0 -> 0.85
                effectiveHypoCount >= 1.0 -> 0.92
                else -> 1.0
            }
        }

        fun computeRawSegmentFactor(
            currentSegment: Double,
            hypoCount: Int,
            tirAbove180: Double,
            exerciseInSegment: Boolean,
        ): Double {
            val effectiveHypo = exerciseAmplifiedHypoCount(hypoCount, exerciseInSegment)
            var adjustment = segmentAdjustmentFromHypoBurden(effectiveHypo)
            if (hypoCount == 0 && tirAbove180 > 40.0) {
                adjustment *= 1.10
            }
            return (currentSegment * adjustment).coerceIn(FACTOR_MIN, FACTOR_MAX)
        }

        fun countHypoEpisodes(bgValues: List<Double>): Int {
            var hypoCount = 0
            var inHypo = false
            for (bg in bgValues) {
                if (bg < 70.0 && !inHypo) {
                    hypoCount++
                    inHypo = true
                } else if (bg >= 70.0) {
                    inHypo = false
                }
            }
            return hypoCount
        }

        fun hasExerciseInDaypart(
            exerciseTimestamps: List<Long>,
            daypart: ReactivityDaypart,
            windowStart: Long,
            windowEnd: Long,
            hourFromTimestamp: (Long) -> Int,
        ): Boolean {
            return exerciseTimestamps.any { timestamp ->
                timestamp in windowStart..windowEnd &&
                    fromHour(hourFromTimestamp(timestamp)) == daypart
            }
        }
    }
}
