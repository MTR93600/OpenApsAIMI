package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason

/**
 * What the last AIMI tick saw, for the Boost guards.
 *
 * @property minBgLookback75m the lowest glucose of the last 75 minutes, mg/dL
 * @property reboundGuardActive `CorrectionAggressionGate` is in `REBOUND_GUARD` (a rebound after a low)
 */
internal data class TpoBoostTick(
    val nowMs: Long,
    val bgMgdl: Double,
    val deltaMgdl5m: Double,
    val minBgLookback75m: Double,
    val reboundGuardActive: Boolean,
)

/**
 * May the manual Boost start? Pure: the plugin passes everything in, and checks it again at start.
 *
 * The Boost adds insulin, so every doubt says no. Null means it may start.
 */
internal object TpoBoostEligibility {

    const val MAX_TICK_AGE_MS = 10L * 60L * 1000L

    /** Under this glucose there is no Boost, whatever the target is. */
    const val MIN_BG_MGDL = 80.0

    /** The usual line of a low, for the last 75 minutes. */
    const val RECENT_LOW_MGDL = 70.0

    /** At this fall per 5 minutes or faster, glucose is going down. */
    const val FALLING_DELTA_MGDL = -2.0

    /**
     * A running Boost ends when glucose goes under this. The user chose 65 and not 70: just after a meal
     * a dip to the high 60s usually comes back up by itself, and ending the Boost there would waste it.
     */
    const val END_BELOW_MGDL = 65.0

    /** True when a running Boost must end now: glucose is known and under [END_BELOW_MGDL]. */
    fun shouldEndForLow(bgMgdl: Double): Boolean = bgMgdl.isFinite() && bgMgdl < END_BELOW_MGDL

    fun check(
        isAimi: Boolean,
        loopClosed: Boolean,
        tick: TpoBoostTick?,
        nowMs: Long,
        targetMgdl: Double?,
        activeSession: TpoSessionDocument?,
    ): AimiBoostUnavailableReason? {
        if (!isAimi) return AimiBoostUnavailableReason.NOT_AIMI
        if (!loopClosed) return AimiBoostUnavailableReason.LOOP_NOT_CLOSED
        // A running protective session does not block: the Boost replaces it
        if (activeSession != null && activeSession.isRunning() && activeSession.packId == TpoPackId.MANUAL_BOOST) {
            return AimiBoostUnavailableReason.ALREADY_ACTIVE
        }
        if (tick == null || nowMs - tick.nowMs !in 0..MAX_TICK_AGE_MS) return AimiBoostUnavailableReason.NO_RECENT_TICK
        if (!tick.bgMgdl.isFinite() || !tick.deltaMgdl5m.isFinite()) return AimiBoostUnavailableReason.NO_RECENT_TICK
        if (tick.minBgLookback75m < RECENT_LOW_MGDL) return AimiBoostUnavailableReason.RECENT_LOW
        if (tick.reboundGuardActive) return AimiBoostUnavailableReason.REBOUND_GUARD
        // No known target is a doubt too: then only the absolute floor counts
        if (tick.bgMgdl < MIN_BG_MGDL || (targetMgdl != null && tick.bgMgdl < targetMgdl)) return AimiBoostUnavailableReason.BELOW_TARGET
        if (tick.deltaMgdl5m <= FALLING_DELTA_MGDL) return AimiBoostUnavailableReason.FALLING
        return null
    }

    private fun TpoSessionDocument.isRunning(): Boolean =
        status == TpoSessionStatus.ACTIVE || status == TpoSessionStatus.PENDING_LLM
}
