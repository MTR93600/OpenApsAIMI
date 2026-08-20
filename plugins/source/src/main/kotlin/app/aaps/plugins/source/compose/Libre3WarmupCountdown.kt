package app.aaps.plugins.source.compose

import app.aaps.plugins.libre3.Libre3WarmupState

/**
 * Works out what the countdown should show.
 *
 * Pure, so the rule is unit tested rather than eyeballed on a screen.
 */
object Libre3WarmupCountdown {

    /**
     * @param nowMs the phone clock.
     * @return milliseconds left, or null when nothing is known and the screen should show a dash.
     */
    fun remainingMs(state: Libre3WarmupState, nowMs: Long): Long? {
        // What the sensor said beats anything worked out here.
        state.remainingMs?.let { return maxOf(0L, it) }
        state.endsAtEpochMs?.let { return maxOf(0L, it - nowMs) }
        return null
    }

    /** Minutes and seconds, as `mm:ss`. */
    fun format(remainingMs: Long): String {
        val totalSeconds = remainingMs / 1000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    /** True once there is nothing left to wait for. */
    fun isFinished(state: Libre3WarmupState, nowMs: Long): Boolean {
        if (state.phase == Libre3WarmupState.Phase.READY) return true
        val remaining = remainingMs(state, nowMs) ?: return false
        return remaining <= 0L
    }
}
