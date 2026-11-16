package app.aaps.plugins.aps.openAPSAIMI.wcycle

/**
 * When a declared endometriosis pain flare ends.
 *
 * The person switches the flare on (`BooleanKey.AimiEndometriosisPainFlare`). Its start time is
 * stored as internal state (`AimiLongKey.EndometriosisFlareStartedAt`), and the flare stops changing
 * insulin once `IntKey.AimiEndometriosisFlareDuration` hours (default 4) have passed. The loop never
 * switches the setting off itself: no setting is written by the loop alone. The switch simply has no
 * effect any more, and the illness card says so. Switching it off and on again starts a new flare:
 * [EndometriosisFlareSwitchWatcher] stores the start when the switch is turned on.
 *
 * Step S3b of the AIMI settings plan (owner decision 2026-11-15). Before, nothing read the duration,
 * so a flare that was not switched off raised the basal and damped the SMBs for ever.
 *
 * Pure, so the rule is the same on both phones and can be tested on its own.
 */
object EndometriosisFlareClock {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** What the flare does now. */
    sealed interface Status {

        /** The flare switch is off. */
        data object Off : Status

        /** The flare acts, for [remainingMinutes] more minutes. */
        data class Active(val remainingMinutes: Long) : Status

        /** The flare switch is on, but its duration has passed: it no longer changes insulin. */
        data object Ended : Status
    }

    /**
     * The start time to store: cleared when the flare is off, now when it is on with no start yet
     * (just switched on, switched on before this build, or by an import), else the stored one.
     */
    fun startToStore(flareOn: Boolean, storedStartMs: Long, nowMs: Long): Long = when {
        !flareOn            -> 0L
        storedStartMs <= 0L -> nowMs
        else                -> storedStartMs
    }

    /**
     * What the flare does at [nowMs].
     *
     * A flare that is on with no start time yet has its whole duration left: the next loop run stores
     * the start. A start in the future (the phone clock went back) never gives more than the whole
     * duration.
     */
    fun status(flareOn: Boolean, startedAtMs: Long, durationHours: Int, nowMs: Long): Status {
        if (!flareOn) return Status.Off
        val durationMs = durationHours * HOUR_MS
        val elapsedMs = if (startedAtMs <= 0L) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)
        if (elapsedMs >= durationMs) return Status.Ended
        val remainingMs = durationMs - elapsedMs
        return Status.Active(remainingMinutes = (remainingMs + MINUTE_MS - 1) / MINUTE_MS)
    }
}
