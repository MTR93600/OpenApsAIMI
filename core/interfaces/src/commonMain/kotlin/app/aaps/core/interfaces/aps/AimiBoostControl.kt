package app.aaps.core.interfaces.aps

import kotlinx.coroutines.flow.StateFlow

/**
 * The manual AIMI Boost: a short TPO session the user starts by hand after eating, for 60 minutes. It
 * raises max SMB in hyper, max basal and the Autodrive max basal by the strength the user set (30 % by
 * default, 100 % at most), keeps the loop target at 80 mg/dL at most, and ends early under 65 mg/dL.
 *
 * The screen only asks; the AIMI plugin decides. Every guard is checked again in [start], so a screen
 * that shows a stale state can never start a Boost the plugin would refuse.
 */
interface AimiBoostControl {

    /** Whether a Boost can start now, and whether one is running. */
    val state: StateFlow<AimiBoostState>

    /** Start a Boost, or say why not. */
    suspend fun start(): AimiBoostStartResult

    /** Cancel a running Boost and give the user's values back. False when no Boost was running. */
    fun cancel(): Boolean
}

/**
 * @property available a Boost can start now
 * @property unavailableReason why not, when [available] is false and no Boost is running
 * @property active a Boost is running
 * @property remainingMinutes minutes left of the running Boost
 */
data class AimiBoostState(
    val available: Boolean = false,
    val unavailableReason: AimiBoostUnavailableReason? = null,
    val active: Boolean = false,
    val remainingMinutes: Int = 0,
)

/** Why a Boost cannot start. */
enum class AimiBoostUnavailableReason {

    /** AIMI is not the active algorithm. */
    NOT_AIMI,

    /** The loop is not in closed loop mode. */
    LOOP_NOT_CLOSED,

    /** AIMI has not run in the last 10 minutes. */
    NO_RECENT_TICK,

    /** Glucose is under the target (or under 80 mg/dL). */
    BELOW_TARGET,

    /** Glucose is falling. */
    FALLING,

    /** There was a low in the last 75 minutes. */
    RECENT_LOW,

    /** AIMI is protecting a rebound after a low. */
    REBOUND_GUARD,

    /** A Boost is already running. */
    ALREADY_ACTIVE,

    /** All three ceilings are already at their maximum: a Boost would change nothing. */
    AT_LIMIT,
}

/** The answer to [AimiBoostControl.start]. */
sealed interface AimiBoostStartResult {

    data class Started(val remainingMinutes: Int) : AimiBoostStartResult

    data class Refused(val reason: AimiBoostUnavailableReason) : AimiBoostStartResult
}
