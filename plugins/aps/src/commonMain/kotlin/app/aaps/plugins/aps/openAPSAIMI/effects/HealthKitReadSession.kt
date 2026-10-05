package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot

/** Step windows, in minutes. The same three sums Android keeps on the snapshot. */
internal val HEALTHKIT_STEP_WINDOWS_MIN: List<Int> = listOf(5, 15, 60)

/** Heart-rate samples covering the 10-minute and 60-minute ISF windows. */
internal const val HEALTHKIT_HEART_RATE_WINDOW_MIN: Int = 60

/** Resting heart rate lookback. A missing value becomes 60 inside the snapshot. */
internal const val HEALTHKIT_RESTING_WINDOW_HOURS: Int = 24

/**
 * The HealthKit calls a read session makes. Tests supply a fake. `iosMain` talks to `HKHealthStore`.
 * [requestReadAuthorization] is the permission prompt. The session calls it only when both switches
 * are on.
 */
internal interface HealthKitWindowPort {
    fun requestReadAuthorization()
    fun stepsSum(nowMs: Long, minutes: Int): Int
    fun heartRateSamples(nowMs: Long, minutes: Int): List<HR>
    fun restingHeartRateBpm(nowMs: Long, hours: Int): Int
}

internal class HealthKitReadFailure(message: String) : IllegalStateException(message)

internal data class HealthKitSessionResult(
    val windows: WearableSnapshotWindows,
    val heartRates: List<HR>,
    val authorizationRequested: Boolean,
)

/**
 * `IosClientConfig.APS` and `AimiCommonEngineSwitch` both have to be on.
 * Either one off and the session does not ask for a grant.
 */
internal fun healthKitReadAuthorizationAllowed(apsEnabled: Boolean, engineEnabled: Boolean): Boolean =
    apsEnabled && engineEnabled

/**
 * One read session. Asks the port for steps over 5, 15 and 60 minutes, heart-rate samples over
 * 60 minutes, and resting heart rate over 24 hours, then writes [HealthContextSnapshot].
 * The 10-minute and 60-minute averages are the overlap [decideHeartRateIsf] uses.
 *
 * An [Exception] logs `WEARABLE snapshot failed … — snapshot empty` and returns an empty snapshot.
 * An [Error] propagates. The empty snapshot does not add insulin.
 */
internal fun openHealthKitReadSession(
    port: HealthKitWindowPort,
    nowMs: Long,
    apsEnabled: Boolean,
    engineEnabled: Boolean,
    log: MutableList<String>,
): HealthKitSessionResult {
    val requested = healthKitReadAuthorizationAllowed(apsEnabled, engineEnabled)
    return try {
        if (requested) port.requestReadAuthorization()
        val steps = HEALTHKIT_STEP_WINDOWS_MIN.map { minutes -> port.stepsSum(nowMs, minutes) }
        val heart = port.heartRateSamples(nowMs, HEALTHKIT_HEART_RATE_WINDOW_MIN)
        val resting = port.restingHeartRateBpm(nowMs, HEALTHKIT_RESTING_WINDOW_HOURS)
        HealthKitSessionResult(
            windows = snapshotFromWearableWindows(
                stepsLast5m = steps[0],
                stepsLast15m = steps[1],
                stepsLast60m = steps[2],
                heartRates = heart,
                restingBpm = resting,
                nowMs = nowMs,
            ),
            heartRates = heart,
            authorizationRequested = requested,
        )
    } catch (e: Exception) {
        val errorType = e::class.simpleName ?: "Exception"
        log += "WEARABLE snapshot failed ($errorType): ${e.message.orEmpty()} — snapshot empty"
        HealthKitSessionResult(
            windows = WearableSnapshotWindows(
                snapshot = HealthContextSnapshot(),
                hrAvg10 = 0,
                hrAvg60 = 0,
            ),
            heartRates = emptyList(),
            authorizationRequested = requested,
        )
    }
}
