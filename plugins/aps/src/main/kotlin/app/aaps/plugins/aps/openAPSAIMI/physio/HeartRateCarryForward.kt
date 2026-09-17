package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Decides how long a heart rate may be carried forward when a refresh brings nothing back.
 *
 * ## The defect this replaces
 *
 * `HealthContextRepository` used to carry the previous heart rate into a new snapshot and stamp it
 * `timestamp = snapshot.timestamp`, then store that snapshot as the new previous one. The re-dating
 * therefore **compounded**: the same reading was presented as current on every tick, indefinitely,
 * and nothing anywhere recorded when it had actually been measured. No consumer could tell a reading
 * taken this minute from one taken hours ago, and the export carries no acquisition time either — the
 * only visible clue was the staircase shape of the series.
 *
 * ## The rule
 *
 * A reading keeps **its own** age. A fresh sample resets it; a carried one does not. Past
 * [MAX_AGE_MS] the reading is dropped to zero rather than carried, which is the value every consumer
 * already treats as "missing" — including `StressIsfFloor`, whose `hrNowBpm <= 0` check is the one
 * place in the engine that handles absence correctly.
 *
 * [MAX_AGE_MS] is not invented here: it is the provider's own lookback. `HealthContextRepository`
 * asks `getLatestHeartRate(15 * 60 * 1000)`, so carrying a reading past 15 minutes adds nothing the
 * provider would not have returned by itself.
 */
object HeartRateCarryForward {

    /** Longest a heart-rate reading may be carried forward, in milliseconds. */
    const val MAX_AGE_MS: Long = 15 * 60 * 1000L

    /**
     * What the snapshot should carry for the heart rate.
     *
     * @param hrNow the point value, 0 when missing.
     * @param hrAvg15m the mean over the last 15 minutes. In this deployment it is the same held
     *   sample as [hrNow] — see the repository — so it is carried and dropped together with it.
     * @param measuredAtMs when [hrNow] was actually measured, or 0 when there is no usable reading.
     */
    data class Result(
        val hrNow: Int,
        val hrAvg15m: Int,
        val measuredAtMs: Long,
    ) {
        companion object {
            /** Nothing usable: every consumer reads this as missing data. */
            val MISSING = Result(hrNow = 0, hrAvg15m = 0, measuredAtMs = 0L)
        }
    }

    /**
     * Pure: it reads no clock and keeps no state, so a test can replay tick after tick.
     *
     * @param freshHrNow the point value this refresh produced, 0 when it produced none.
     * @param freshHrAvg15m the mean this refresh produced, 0 when it produced none.
     * @param nowMs this tick's clock.
     * @param previousHrNow the point value carried from the last tick.
     * @param previousHrAvg15m the mean carried from the last tick.
     * @param previousMeasuredAtMs when that carried value was measured, 0 when there is none.
     */
    fun resolve(
        freshHrNow: Int,
        freshHrAvg15m: Int,
        nowMs: Long,
        previousHrNow: Int,
        previousHrAvg15m: Int,
        previousMeasuredAtMs: Long,
    ): Result {
        if (freshHrNow > 0) {
            // A real sample. The mean is the same held value in this deployment, so when the refresh
            // gives no mean the point stands in for it rather than an older one being kept.
            return Result(
                hrNow = freshHrNow,
                hrAvg15m = if (freshHrAvg15m > 0) freshHrAvg15m else freshHrNow,
                measuredAtMs = nowMs,
            )
        }
        if (previousHrNow <= 0 || previousMeasuredAtMs <= 0L) return Result.MISSING
        val age = nowMs - previousMeasuredAtMs
        // A clock that went backwards is not an age; drop the reading rather than guess.
        if (age < 0L || age > MAX_AGE_MS) return Result.MISSING
        return Result(
            hrNow = previousHrNow,
            hrAvg15m = if (previousHrAvg15m > 0) previousHrAvg15m else previousHrNow,
            measuredAtMs = previousMeasuredAtMs,
        )
    }
}
