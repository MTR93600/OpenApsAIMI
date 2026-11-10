package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max

/** Reduction-only terminal guard for an undeclared, automatically detected meal. */
object AutoMealPlateauBrake {
    data class Decision(
        val active: Boolean,
        val maxBasalUph: Double? = null,
        val blockSmb: Boolean = false,
        val reason: String,
    )

    fun evaluate(
        bgNewestFirst: List<Double>,
        requestedBasalUph: Double,
        profileBasalUph: Double,
        targetBgMgdl: Double,
        isfMgdlPerU: Double,
        iobU: Double,
        cobG: Double,
        explicitMealMode: Boolean,
    ): Decision {
        if (explicitMealMode) return off("explicit_meal")
        if (cobG > 0.1) return off("cob_active")
        if (bgNewestFirst.size < REQUIRED_BG_VALUES) return off("insufficient_history")
        if (!requestedBasalUph.isFinite() || !profileBasalUph.isFinite() || profileBasalUph < 0.0) return off("invalid_basal")
        if (!isfMgdlPerU.isFinite() || isfMgdlPerU <= 0.0) return off("invalid_isf")
        if (requestedBasalUph <= profileBasalUph * ELEVATED_BASAL_FACTOR) return off("basal_not_elevated")

        val values = bgNewestFirst.take(REQUIRED_BG_VALUES)
        if (values.any { !it.isFinite() || it <= 39.0 }) return off("invalid_glucose")
        val currentBg = values.first()
        if (currentBg > targetBgMgdl + MAX_ABOVE_TARGET_MGDL) return off("still_materially_high")

        val deltas = values.zipWithNext { newer, older -> newer - older }
        val recent = deltas.take(RECENT_INTERVALS)
        val older = deltas.drop(RECENT_INTERVALS)
        if (recent.first() >= RENEWED_RISE_MGDL || recent.take(2).average() >= RENEWED_RISE_AVG_MGDL) {
            return off("renewed_rise")
        }

        val plateau = recent.all { it <= MAX_FLAT_DELTA_MGDL } && recent.sum() <= MAX_RECENT_NET_RISE_MGDL
        val earlierRise = older.any { it >= EARLIER_RISE_DELTA_MGDL } || older.sum() >= EARLIER_NET_RISE_MGDL
        if (!plateau) return off("not_plateau")
        if (!earlierRise) return off("no_prior_rise")

        // Keep only enough excess basal to correct the remaining distance gradually. This cap
        // shrinks continuously as BG approaches target and never raises the engine's request.
        val correctionUnits = max(0.0, currentBg - targetBgMgdl) / isfMgdlPerU
        val uncoveredCorrectionUnits = max(0.0, correctionUnits - max(0.0, iobU))
        val gradualCorrectionUph = uncoveredCorrectionUnits / CORRECTION_HORIZON_HOURS
        return Decision(
            active = true,
            maxBasalUph = profileBasalUph + gradualCorrectionUph,
            blockSmb = true,
            reason = "prior_rise_then_15m_plateau",
        )
    }

    private fun off(reason: String) = Decision(active = false, reason = reason)

    private const val REQUIRED_BG_VALUES = 7
    private const val RECENT_INTERVALS = 3
    private const val ELEVATED_BASAL_FACTOR = 1.05
    private const val MAX_ABOVE_TARGET_MGDL = 35.0
    private const val MAX_FLAT_DELTA_MGDL = 0.8
    private const val MAX_RECENT_NET_RISE_MGDL = 1.5
    private const val EARLIER_RISE_DELTA_MGDL = 1.5
    private const val EARLIER_NET_RISE_MGDL = 3.0
    private const val RENEWED_RISE_MGDL = 1.5
    private const val RENEWED_RISE_AVG_MGDL = 1.0
    private const val CORRECTION_HORIZON_HOURS = 2.0
}
