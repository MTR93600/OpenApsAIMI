package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionManager

/**
 * The SMB ceiling the tick installs after the session check.
 *
 * [maxSmb] is `OApsAIMIMaxSMB`. [maxSmbHb] is the high-glucose ceiling, and it is never below
 * [maxSmb], which is the assignment in `runRealtimePhysioIobProfilerAndInsulinObserver`.
 */
internal data class TpoTickSmbCeiling(
    val maxSmb: Double,
    val maxSmbHb: Double,
)

/**
 * The activity scene an active session is locked against.
 *
 * Protection is on, the request starts at 2 U, and meal-high IOB damps by one half. Half of the
 * ceiling, then that damping, is the delivered request. No session (ceiling 1.00 U) delivers
 * 0.25 U. A session that stepped the ceiling to 0.80 U delivers 0.20 U.
 */
internal object ActiveTpoActivityScene {
    const val SMB_TO_GIVE = 2f
    const val MEAL_HIGH_IOB_DAMPING = 0.50
}

/** The two preference reads the Android tick does after `onTickStart`. */
internal fun tpoTickSmbCeiling(preferences: Preferences): TpoTickSmbCeiling {
    val maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB)
    val maxSmbHb = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB).coerceAtLeast(maxSmb)
    return TpoTickSmbCeiling(maxSmb = maxSmb, maxSmbHb = maxSmbHb)
}

/**
 * Expire or revert, then read the ceiling.
 *
 * Android calls [TpoSessionManager.expireIfNeeded] from `TpoOrchestrator.onTickStart` and then
 * [tpoTickSmbCeiling]. iOS has no orchestrator, so this is that pair, on the clock the caller
 * passes. [evaluate][app.aaps.plugins.aimiengine.AimiEngine.evaluate] stays a normal function:
 * the session file is JSON, not a suspending DAO.
 */
internal fun decideTpoSessionAtTickStart(
    nowMs: Long,
    preferences: Preferences,
    persistence: TpoPersistence,
): TpoTickSmbCeiling {
    TpoSessionManager(persistence).expireIfNeeded(nowMs, preferences, historyRepo = null)
    return tpoTickSmbCeiling(preferences)
}
