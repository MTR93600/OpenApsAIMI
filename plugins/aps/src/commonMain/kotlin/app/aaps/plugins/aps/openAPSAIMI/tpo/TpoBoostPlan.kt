package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The plan of the manual Boost: three ceilings raised by at most [IntKey.OApsAIMIManualBoostPercent] of
 * the user's own values (30 % by default, 10 to 100 %).
 *
 * - max SMB in hyper ([DoubleKey.OApsAIMIHighBGMaxSMB])
 * - max basal ([DoubleKey.ApsMaxBasal])
 * - the Autodrive max basal ([DoubleKey.autodriveMaxBasal])
 *
 * Both basal ceilings also stay under the age hard limit. The result is rounded **down** to 0.01, so
 * rounding can never pass the chosen percent. Max SMB and max IOB are not touched: those hard safety limits stay where
 * the user set them. The target is not a preference here: during a Boost AIMI keeps its own target at
 * 80 mg/dL at most (`decideBasalSchedule`).
 *
 * The plan reads the values in force now. `TpoOrchestrator.startManualBoost` first gives back the
 * values of a protective session that is running, so these are the user's own values, and two Boosts
 * can never stack.
 */
internal object TpoBoostPlan {

    const val TTL_MS = 60L * 60L * 1000L

    /** The factor for [percent], kept inside the key's own range. */
    fun factor(percent: Int): Double =
        1.0 + percent.coerceIn(IntKey.OApsAIMIManualBoostPercent.min, IntKey.OApsAIMIManualBoostPercent.max) / 100.0

    /** [value] raised by [factor], capped by [keyMax] and [hardMax], rounded down to 0.01, never lowered. */
    fun boosted(value: Double, keyMax: Double, factor: Double, hardMax: Double = Double.MAX_VALUE): Double {
        if (!value.isFinite() || value <= 0.0) return value
        val raised = min(value * factor, min(keyMax, hardMax))
        val rounded = floor(raised * 100.0 + 1e-9) / 100.0
        return max(value, rounded)
    }

    /** The Boost changes for the values in [preferences]. A key already at its cap is left out. */
    fun build(preferences: Preferences, hardMaxBasal: Double): TpoApplyPlan {
        val factor = factor(preferences.get(IntKey.OApsAIMIManualBoostPercent))
        val changes = buildList {
            add(DoubleKey.OApsAIMIHighBGMaxSMB, preferences, factor, Double.MAX_VALUE)
            add(DoubleKey.ApsMaxBasal, preferences, factor, hardMaxBasal)
            add(DoubleKey.autodriveMaxBasal, preferences, factor, hardMaxBasal)
        }
        val proposal = TpoProposal(
            packId = TpoPackId.MANUAL_BOOST,
            tier = TuningStepTier.MICRO,
            algoConfidence = 1.0,
            reasonCodes = listOf(REASON_MANUAL),
        )
        return TpoApplyPlan(proposal = proposal, changes = changes)
    }

    private fun MutableList<TuningChange>.add(key: DoubleKey, preferences: Preferences, factor: Double, hardMax: Double) {
        val current = preferences.get(key)
        val target = boosted(current, key.max, factor, hardMax)
        if (target > current) add(TuningChange(key, key.key, current, target, "TPO manual boost", TuningStepTier.MICRO))
    }

    const val REASON_MANUAL = "MANUAL_BOOST"
}
