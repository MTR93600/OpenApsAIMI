package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

/**
 * Short hysteresis for the physiological pattern SMB cap ([PhysiologicalPatternSnapshot.smbCapU]).
 *
 * Pattern classification can flap between consecutive loop ticks (e.g. SEDENTARY_DAY toggling on
 * `activityReduced` / steps churn). Without a hold, the cap drops to null mid rise and the HTR
 * min-cap branch in DetermineBasalAIMI2 can be skipped entirely for that tick. This holder keeps
 * the last known **HARD** cap alive for [holdTicks] ticks while BG is still rising, so hard-cap
 * coverage stays continuous within the same rise episode.
 *
 * Soft meal proposals are never re-materialized as binding hard caps during the hold — they pass
 * through live when present, and are not held as terminal mins when the pattern flaps off.
 */
class PatternCapHold(private val holdTicks: Int = DEFAULT_HOLD_TICKS) {

    private var heldHardCapU: Double? = null
    private var ticksLeft: Int = 0

    /** True when the last [resolve] returned a held (carried-over) HARD value rather than a live one. */
    var holding: Boolean = false
        private set

    /**
     * @param rawCapU live cap from the current pattern snapshot (null when no active pattern defines one)
     * @param rawKind soft vs hard semantics of [rawCapU]
     * @param rising true while the current rise episode continues (delta > 0)
     * @return binding HARD cap for this tick, or null when nothing hard should bind
     */
    fun resolve(
        rawCapU: Double?,
        rising: Boolean,
        rawKind: PatternCapKind? = PatternCapKind.HARD,
    ): Double? {
        if (rawCapU != null && rawKind == PatternCapKind.HARD) {
            heldHardCapU = rawCapU
            ticksLeft = holdTicks
            holding = false
            return rawCapU
        }
        if (rawCapU != null && rawKind == PatternCapKind.SOFT) {
            // Soft proposals are never held as binding hard mins.
            holding = false
            return null
        }
        if (!rising || ticksLeft <= 0) {
            clear()
            return null
        }
        ticksLeft--
        holding = true
        return heldHardCapU
    }

    private fun clear() {
        heldHardCapU = null
        ticksLeft = 0
        holding = false
    }

    companion object {
        const val DEFAULT_HOLD_TICKS = 3
    }
}
