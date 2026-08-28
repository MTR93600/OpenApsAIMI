package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState

/**
 * Anti-circular **v0** peak mismatch on a short window (RFC D.1 / D.2 sketch).
 *
 * IOB history is **not** re-simulated per candidate peak. Only the **shape** of the normalized
 * Weibull kernel (same as [InsulinWeibullCurve]) varies with [peakCandidate]; a single scale
 * is fit by least squares against observed CGM deltas.
 *
 * **Runtime (TAP-G)**: when [TrajectoryPeakBias] geometry nudge is zero, [minutesNudgeFromHistoryOrZero]
 * can add a small bounded nudge from phase-space **bgDelta** vs the template (same clean-window / COB gates).
 */
object TrajectoryPeakMismatchScorer {

    private const val FD_STEP_MIN = 0.5
    private const val MAX_MISMATCH_NUDGE_MIN = 2.0
    private const val GRID_HALF_SPAN_MIN = 12
    private const val GRID_STEP_MIN = 3
    /** Require RSS improvement vs profile peak by at least this relative margin. */
    private const val RSS_IMPROVE_RATIO = 0.92

    /** Central difference slope of normalized activity w.r.t. time (1/min scale). */
    fun normalizedCurveSlopePerMinute(
        minutesSinceBolus: Double,
        peakCandidateMinutes: Double,
    ): Double {
        val h = FD_STEP_MIN
        val t0 = (minutesSinceBolus - h).coerceAtLeast(0.0)
        val t1 = minutesSinceBolus + h
        val p = peakCandidateMinutes.coerceAtLeast(30.0)
        return (
            InsulinWeibullCurve.activityNormalized(t1, p) -
                InsulinWeibullCurve.activityNormalized(t0, p)
            ) / (2.0 * h)
    }

    /**
     * Residual sum of squares after optimal linear scale `observed ≈ scale * templateSlope`.
     */
    fun rssAfterScaleFit(
        observedDeltaMgDlPer5m: List<Double>,
        minutesSinceBolus: List<Double>,
        peakCandidateMinutes: Double,
    ): Double {
        require(observedDeltaMgDlPer5m.size == minutesSinceBolus.size)
        if (observedDeltaMgDlPer5m.isEmpty()) return 0.0
        val preds = minutesSinceBolus.map { normalizedCurveSlopePerMinute(it, peakCandidateMinutes) }
        var num = 0.0
        var den = 0.0
        for (i in observedDeltaMgDlPer5m.indices) {
            val o = observedDeltaMgDlPer5m[i]
            val p = preds[i]
            num += o * p
            den += p * p
        }
        val scale = if (den > 1e-18) num / den else 0.0
        var rss = 0.0
        for (i in observedDeltaMgDlPer5m.indices) {
            val e = observedDeltaMgDlPer5m[i] - scale * preds[i]
            rss += e * e
        }
        return rss
    }

    /**
     * Grid search in minutes (inclusive steps). Returns best peak (minutes).
     */
    fun bestPeakOnGrid(
        observedDeltaMgDlPer5m: List<Double>,
        minutesSinceBolus: List<Double>,
        peakGridMinutes: IntProgression,
    ): Int {
        var bestPeak = peakGridMinutes.first
        var bestRss = Double.POSITIVE_INFINITY
        for (p in peakGridMinutes) {
            val rss = rssAfterScaleFit(observedDeltaMgDlPer5m, minutesSinceBolus, p.toDouble())
            if (rss < bestRss - 1e-12) {
                bestRss = rss
                bestPeak = p
            }
        }
        return bestPeak
    }

    /**
     * Bounded peak-time nudge (minutes) from trajectory **history** deltas vs Weibull template.
     * Returns **0** when gates fail, too few samples, or RSS does not improve vs [insulinPeakMinutes].
     *
     * Intended as **secondary** signal when geometry-based [TrajectoryPeakBias] is 0 (avoid double count).
     */
    fun minutesNudgeFromHistoryOrZero(
        history: List<PhaseSpaceState>,
        insulinPeakMinutes: Int,
        lastBolusAgeMinutes: Int,
        cobGrams: Double,
    ): Double {
        if (!CleanPostBolusWindow.allowsTrajectoryPeakNudge(lastBolusAgeMinutes, cobGrams)) return 0.0
        if (history.size < 4) return 0.0
        val peakP = insulinPeakMinutes.coerceAtLeast(35)
        val interior = history
            .dropLast(1)
            .filter {
                it.timeSinceLastBolus in
                    CleanPostBolusWindow.MIN_MINUTES_SINCE_BOLUS..CleanPostBolusWindow.MAX_MINUTES_SINCE_BOLUS
            }
        if (interior.size < 6) return 0.0
        val minutes = interior.map { it.timeSinceLastBolus.toDouble() }
        val obs = interior.map { it.bgDelta }
        val lo = (peakP - GRID_HALF_SPAN_MIN).coerceAtLeast(35)
        val hi = (peakP + GRID_HALF_SPAN_MIN).coerceAtMost(120)
        if (lo > hi) return 0.0
        val grid = IntProgression.fromClosedRange(lo, hi, GRID_STEP_MIN)
        val best = bestPeakOnGrid(obs, minutes, grid)
        val rssPrior = rssAfterScaleFit(obs, minutes, peakP.toDouble())
        val rssBest = rssAfterScaleFit(obs, minutes, best.toDouble())
        if (rssBest >= rssPrior * RSS_IMPROVE_RATIO) return 0.0
        val raw = (best - peakP).toDouble()
        return raw.coerceIn(-MAX_MISMATCH_NUDGE_MIN, MAX_MISMATCH_NUDGE_MIN)
    }
}
