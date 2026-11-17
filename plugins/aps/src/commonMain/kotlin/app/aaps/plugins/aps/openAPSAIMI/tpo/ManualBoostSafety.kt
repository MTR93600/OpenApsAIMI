package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SportGuardKind
import kotlin.math.max

/**
 * What a running manual Boost changes in the SMB guards. Pure, so it is tested without a tick.
 *
 * A Boost means "I have eaten and I want action" (maintainer, 2026-11-17). Activity that is going on now
 * still stops the SMB, as without a Boost. Activity that is only in the past step totals halves it instead
 * of stopping it, and the effort factor is then not applied on top: both read the same walk, and counting
 * it twice left about 1 U of the 4.5 U asked (maintainer, 2026-11-17). In any other case of a Boost the
 * effort factor cannot cut the SMB below half. The Boost itself ends under 65 mg/dL.
 *
 * Why not more: the sport guard was made a vital safety after 2026-08-18, when SMBs given back after a
 * three hour hike took glucose from 168 to 50 mg/dL. Half keeps part of that protection.
 */
internal object ManualBoostSafety {

    /** The SMB is multiplied by this when the sport guard fired on past activity only, during a Boost. */
    const val PAST_ACTIVITY_SMB_FACTOR = 0.5

    /** During a Boost the effort factor does not cut the SMB below this. */
    const val EFFORT_FACTOR_FLOOR = 0.5

    /**
     * The factor the sport guard applies to the SMB: 0 stops it, 1 leaves it.
     * Without a Boost, or when the activity goes on now, it is 0 as before.
     */
    fun sportSmbFactor(kind: SportGuardKind, boostActive: Boolean): Double = when {
        kind == SportGuardKind.NONE                                    -> 1.0
        boostActive && kind == SportGuardKind.PAST_ACTIVITY_ONLY       -> PAST_ACTIVITY_SMB_FACTOR
        else                                                           -> 0.0
    }

    /**
     * The effort factor to use. Unchanged without a Boost. With one, 1 (not applied) when the sport guard
     * already halved this SMB for past activity, else never below [EFFORT_FACTOR_FLOOR].
     */
    fun effortFactor(applied: Double, boostActive: Boolean, sportHalvedForBoost: Boolean): Double = when {
        !boostActive        -> applied
        sportHalvedForBoost -> 1.0
        else                -> max(applied, EFFORT_FACTOR_FLOOR)
    }
}
