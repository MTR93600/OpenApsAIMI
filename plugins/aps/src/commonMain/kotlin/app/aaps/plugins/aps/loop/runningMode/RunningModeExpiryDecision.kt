package app.aaps.plugins.aps.loop.runningMode

import app.aaps.core.data.model.RM

/**
 * When the end of the active running mode has to be handled, shared by the Android and the iOS
 * scheduler. Only the trigger differs between the two: WorkManager on Android, a timer on iOS.
 */
internal object RunningModeExpiryDecision {

    /**
     * Milliseconds until [active] ends, or `null` when nothing must be scheduled and any pending
     * job should be cancelled: a permanent mode, an open-ended one, an end time that overflows, or
     * an end that is already in the past.
     */
    fun delayUntilExpiry(active: RM, now: Long): Long? {
        if (!active.mode.mustBeTemporary() || active.duration <= 0L) return null
        // Long.MAX_VALUE duration is the "open-ended" sentinel (e.g. SUSPENDED_BY_PUMP from
        // precheck, constraint-forced modes). No expiry to schedule.
        if (active.duration == Long.MAX_VALUE) return null
        // Overflow guard: timestamp + duration can wrap when duration is near Long.MAX_VALUE.
        val endTime = active.timestamp + active.duration
        if (endTime < active.timestamp) return null
        val delayMs = endTime - now
        return delayMs.takeIf { it > 0 }
    }
}
