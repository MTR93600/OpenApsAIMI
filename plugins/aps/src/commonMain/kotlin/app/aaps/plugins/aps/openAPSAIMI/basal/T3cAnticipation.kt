package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.Predictions

/**
 * Predictive anticipation layer for T3C brittle mode: fuses OpenAPS prediction curves (IOB/COB/UAM/ZT)
 * with local parabolic projection **without** replacing safety logic elsewhere.
 *
 * All functions are pure / side-effect free for deterministic tests.
 */
object T3cAnticipation {

    const val PREDICTION_STEP_MINUTES = 5

    /** mg/dL margin above LGS used for “soft hypo lead” timing (earlier basal relief). */
    const val DEFAULT_SOFT_HYPO_MARGIN_MGDL = 12.0

    data class Hints(
        val strength: Double = 0.0,
        /** User LGS-related threshold (mg/dL), same basis as [T3cTrajectoryContext.lgsThresholdMgdl]. */
        val lgsThresholdMgdl: Double = 70.0,
        val minutesToSoftHypo: Int? = null,
        /** Lowest BG across the defensive prediction envelope (if available). */
        val defensiveNadirBg: Double? = null,
        /** Minutes until any aggressive curve first reaches [activationThreshold] (hyper anticipation). */
        val minutesToHyperExcursion: Int? = null
    ) {
        companion object {
            val DISABLED = Hints()
        }
    }

    fun buildHints(
        predictions: Predictions?,
        bgNow: Double,
        lgsThresholdMgdl: Double,
        activationThreshold: Double,
        eventualBg: Double?,
        strengthRaw: Double,
        softHypoMarginMgdl: Double = DEFAULT_SOFT_HYPO_MARGIN_MGDL,
        /** CFRD: minimum LGS floor — impaired glucagon counter-regulation requires a higher safety threshold. */
        lgsFloorMgdl: Double = 70.0,
        /** CFRD: number of 5-min steps to shift the COB curve forward (malabsorption delay). */
        cobDelaySteps: Int = 0,
    ): Hints {
        val strength = strengthRaw.coerceIn(0.0, 1.0)
        if (strength <= 0.0) return Hints.DISABLED

        val defensive = defensiveEnvelope(predictions, bgNow)
        val aggressive = aggressiveEnvelope(predictions, bgNow, cobDelaySteps)

        val softLine = (lgsThresholdMgdl + softHypoMarginMgdl).coerceAtMost(120.0)
        val minutesSoft = if (defensive.isNotEmpty()) {
            minutesToFirstAtOrBelow(defensive, softLine)
        } else null

        val nadir = defensive.minOrNull()

        val minutesHyper = when {
            aggressive.isNotEmpty() ->
                minutesToFirstAtOrAbove(aggressive, activationThreshold)
            eventualBg != null && eventualBg.isFinite() &&
                eventualBg >= activationThreshold && bgNow < activationThreshold - 5.0 -> 25
            else -> null
        }

        return Hints(
            strength = strength,
            lgsThresholdMgdl = lgsThresholdMgdl.coerceIn(lgsFloorMgdl.coerceIn(65.0, 100.0), 100.0),
            minutesToSoftHypo = minutesSoft,
            defensiveNadirBg = nadir,
            minutesToHyperExcursion = minutesHyper
        )
    }

    /**
     * Step-wise minimum across non-empty series (aligned to shortest length) — conservative for **hypo** paths.
     */
    fun defensiveEnvelope(predictions: Predictions?, @Suppress("UNUSED_PARAMETER") bgNow: Double): List<Double> {
        val lists = listOfNotNull(
            predictions?.IOB,
            predictions?.COB,
            predictions?.UAM,
            predictions?.ZT
        ).filter { it.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        val n = lists.minOf { it.size }
        return List(n) { idx -> lists.minOf { it[idx].toDouble().coerceIn(39.0, 401.0) } }
    }

    /**
     * Step-wise maximum across non-empty series — optimistic for **hyper** excursion timing.
     *
     * @param cobDelaySteps CFRD malabsorption: shifts the COB curve forward by N steps (each = 5 min),
     *   delaying when carb-driven glucose arrives in the aggressive envelope. This prevents the
     *   algorithm from believing the post-prandial rise is already over when absorption is still pending.
     */
    fun aggressiveEnvelope(
        predictions: Predictions?,
        @Suppress("UNUSED_PARAMETER") bgNow: Double,
        cobDelaySteps: Int = 0,
    ): List<Double> {
        val rawCob = predictions?.COB
        val delayedCob = if (cobDelaySteps > 0 && rawCob != null && rawCob.size > 1) {
            // Prepend `cobDelaySteps` copies of the first entry (absorption hasn't started yet)
            // and drop the corresponding tail so the curve length is unchanged.
            val delay = cobDelaySteps.coerceAtMost(rawCob.size - 1)
            List(delay) { rawCob.first() } + rawCob.dropLast(delay)
        } else rawCob
        val lists = listOfNotNull(
            predictions?.IOB,
            delayedCob,
            predictions?.UAM,
            predictions?.ZT
        ).filter { it.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        val n = lists.minOf { it.size }
        return List(n) { idx -> lists.maxOf { it[idx].toDouble().coerceIn(39.0, 401.0) } }
    }

    fun minutesToFirstAtOrBelow(series: List<Double>, threshold: Double): Int? {
        series.forEachIndexed { i, v ->
            if (v <= threshold) return i * PREDICTION_STEP_MINUTES
        }
        return null
    }

    fun minutesToFirstAtOrAbove(series: List<Double>, threshold: Double): Int? {
        series.forEachIndexed { i, v ->
            if (v >= threshold) return i * PREDICTION_STEP_MINUTES
        }
        return null
    }

    /**
     * Pulls the local PI projection toward **eventual** prediction when that predicts **worse hyper**
     * (never pulls down — hypo is handled via [hypoLeadMultiplier]).
     */
    fun blendProjectedForHyper(projectedBg: Double, eventualBg: Double?, strength: Double): Double {
        if (strength <= 0.0 || eventualBg == null || !eventualBg.isFinite()) return projectedBg
        val ev = eventualBg.coerceIn(39.0, 401.0)
        val uplift = (ev - projectedBg).coerceAtLeast(0.0)
        return projectedBg + strength * uplift
    }

    /**
     * Shortens delivery horizon when the aggressive envelope says hyper will breach the activation band soon.
     */
    fun compressDeliveryHorizonHours(
        baseHorizonHours: Double,
        minutesToHyperExcursion: Int?,
        strength: Double
    ): Double {
        if (strength <= 0.0 || minutesToHyperExcursion == null) return baseHorizonHours
        val m = minutesToHyperExcursion.coerceAtLeast(0)
        val factor = when {
            m <= 10 -> 0.70
            m <= 20 -> 0.80
            m <= 35 -> 0.90
            else -> 1.0
        }
        val blended = 1.0 - strength * (1.0 - factor)
        return (baseHorizonHours * blended).coerceIn(0.08, baseHorizonHours)
    }

    /**
     * Scales **down** basal when a soft hypo crossing is imminent on the defensive envelope
     * or when the nadir sits clearly under target (IOB stacking / resistance release).
     */
    fun hypoLeadMultiplier(
        hints: Hints,
        targetBg: Double
    ): Double {
        if (hints.strength <= 0.0) return 1.0
        var mult = 1.0

        val m = hints.minutesToSoftHypo
        if (m != null) {
            val tier = when {
                m <= 10 -> 0.68
                m <= 20 -> 0.78
                m <= 30 -> 0.86
                m <= 45 -> 0.93
                else -> 1.0
            }
            mult *= 1.0 - hints.strength * (1.0 - tier)
        }

        val nadir = hints.defensiveNadirBg
        if (nadir != null && nadir.isFinite() && nadir < targetBg) {
            val span = (targetBg - hints.lgsThresholdMgdl).coerceAtLeast(12.0)
            val depth = ((targetBg - nadir) / span).coerceIn(0.0, 1.5)
            if (depth > 0.05) {
                val nadirTrim = 1.0 - hints.strength * (0.12 * depth).coerceIn(0.0, 0.18)
                mult *= nadirTrim
            }
        }

        return mult.coerceIn(0.55, 1.0)
    }
}
