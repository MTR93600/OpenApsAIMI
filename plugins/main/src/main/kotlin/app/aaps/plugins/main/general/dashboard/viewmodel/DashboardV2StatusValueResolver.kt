package app.aaps.plugins.main.general.dashboard.viewmodel

/** Pure numeric selection rules for values shared by the dashboard status cards (Glass and legacy). */
internal object DashboardV2StatusValueResolver {

    data class TargetRangeMgdl(
        val low: Double,
        val high: Double,
    )

    data class TemporaryBasal(
        val rate: Double,
        val isAbsolute: Boolean,
    )

    /**
     * Priority: an active temporary target (explicit user intent) always wins first. Otherwise, prefer the
     * single-point target AIMI actually used for its last loop decision (e.g. its PKPD hyperglycemia
     * tightening — see `DetermineBasalAIMI2.kt`'s `target_bg`, surfaced as `RT.targetBG`) over the raw,
     * unadjusted profile target — the profile target is only AIMI's starting point, not necessarily what
     * it ends up targeting after its own adjustments. Falls back to the plain profile range when no loop
     * run has produced a target yet (e.g. right after install).
     */
    fun resolveTargetRangeMgdl(
        temporaryLow: Double?,
        temporaryHigh: Double?,
        profileLow: Double?,
        profileHigh: Double?,
        aimiTargetBg: Double? = null,
    ): TargetRangeMgdl? =
        completeTargetRange(temporaryLow, temporaryHigh)
            ?: aimiTargetBg?.finiteOrNull()?.takeIf { it > 0.0 }?.let { TargetRangeMgdl(low = it, high = it) }
            ?: completeTargetRange(profileLow, profileHigh)

    fun resolveEffectiveBasalRateUh(
        temporaryBasal: TemporaryBasal?,
        scheduledBasalRateUh: Double?,
    ): Double? {
        if (temporaryBasal == null) return scheduledBasalRateUh.finiteOrNull()
        if (temporaryBasal.isAbsolute) return temporaryBasal.rate.finiteOrNull()
        val scheduled = scheduledBasalRateUh.finiteOrNull() ?: return null
        return (scheduled * temporaryBasal.rate / 100.0).finiteOrNull()
    }

    private fun completeTargetRange(low: Double?, high: Double?): TargetRangeMgdl? {
        val completeLow = low.finiteOrNull() ?: return null
        val completeHigh = high.finiteOrNull() ?: return null
        return TargetRangeMgdl(low = completeLow, high = completeHigh)
    }

    private fun Double?.finiteOrNull(): Double? = this?.takeIf(Double::isFinite)
}
