package app.aaps.plugins.source.compose

import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState

/**
 * Warm-up countdown resolution for Dexcom ONE+ UI (agent A8 skeleton).
 *
 * Priority:
 * 1. Protocol [OnePlusWarmupState.remainingMs] when non-null (preferred).
 * 2. Else [OnePlusWarmupState.endsAtEpochMs] → remaining = endsAt − now.
 * 3. Else a **local fallback** timer ([localFallbackEndsAtEpochMs]) — used ONLY when the
 *    protocol does not expose remaining/end. Do not invent a second clock when remainingMs
 *    is already provided by the driver.
 */
object DexcomOnePlusWarmupCountdown {

    /** Nominal warm-up length used only for the local fallback path (~30 min). */
    const val LOCAL_FALLBACK_DURATION_MS: Long = 30L * 60L * 1000L

    /**
     * @param state current driver warm-up state
     * @param nowEpochMs wall clock now
     * @param localFallbackEndsAtEpochMs end time for the local-only timer; null if not started
     * @return remaining ms to show, or null when no countdown applies
     */
    fun resolveRemainingMs(
        state: OnePlusWarmupState,
        nowEpochMs: Long,
        localFallbackEndsAtEpochMs: Long?,
    ): Long? {
        state.remainingMs?.let { return it.coerceAtLeast(0L) }
        state.endsAtEpochMs?.let { return (it - nowEpochMs).coerceAtLeast(0L) }
        // Local fallback ONLY when protocol remaining/end are both null.
        localFallbackEndsAtEpochMs?.let { return (it - nowEpochMs).coerceAtLeast(0L) }
        return null
    }

    fun shouldStartLocalFallback(state: OnePlusWarmupState): Boolean =
        state.phase == OnePlusWarmupState.Phase.WARMING &&
            state.remainingMs == null &&
            state.endsAtEpochMs == null

    /**
     * The local fallback deadline belongs to the whole warm-up, not to a single phase: a duty-cycle
     * disconnect moves the phase to CONNECTING / RECONNECTING and must keep the clock. Dropping it
     * there blanked the countdown and restarted a fresh ~30 min timer on the next WARMING packet.
     * Only a finished / stopped / failed warm-up clears it.
     */
    fun shouldClearLocalFallback(phase: OnePlusWarmupState.Phase): Boolean = when (phase) {
        OnePlusWarmupState.Phase.READY,
        OnePlusWarmupState.Phase.IDLE,
        OnePlusWarmupState.Phase.FAILED       -> true

        OnePlusWarmupState.Phase.WARMING,
        OnePlusWarmupState.Phase.PAIRING,
        OnePlusWarmupState.Phase.CONNECTING,
        OnePlusWarmupState.Phase.RECONNECTING -> false
    }

    /** Phases that show the mm:ss countdown when one is known (the link may be re-establishing). */
    fun showsCountdown(phase: OnePlusWarmupState.Phase): Boolean = when (phase) {
        OnePlusWarmupState.Phase.WARMING,
        OnePlusWarmupState.Phase.IDLE,
        OnePlusWarmupState.Phase.PAIRING,
        OnePlusWarmupState.Phase.CONNECTING,
        OnePlusWarmupState.Phase.RECONNECTING -> true

        OnePlusWarmupState.Phase.READY,
        OnePlusWarmupState.Phase.FAILED       -> false
    }

    fun formatMmSs(remainingMs: Long): String {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        return "%d:%02d".format(minutes, seconds)
    }
}
