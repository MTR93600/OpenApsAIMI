package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.math.round

internal data class WearableSnapshotWindows(
    val snapshot: HealthContextSnapshot,
    val hrAvg10: Int,
    val hrAvg60: Int,
)

/**
 * Builds the snapshot Android fills from the watch, plus the 10-minute and 60-minute heart-rate
 * windows. A sample counts when `timestamp + duration` reaches the window, the same overlap
 * [decideHeartRateIsf] uses. The current heart rate is the newest sample in the last 15 minutes,
 * and [HealthContextSnapshot.hrAvg15m] copies it, as the Android repository does.
 *
 * Resting heart rate missing or not positive becomes 60, the Android fallback.
 * Confidence follows the Android sum: heart rate alone is 0.3, and `isValid` needs more than 0.3,
 * so heart rate without HRV or sleep stays invalid and does not reduce a dose.
 */
internal fun snapshotFromWearableWindows(
    stepsLast5m: Int,
    stepsLast15m: Int,
    stepsLast60m: Int,
    heartRates: List<HR>,
    restingBpm: Int,
    nowMs: Long,
): WearableSnapshotWindows {
    val hrAvg10 = averageBpm(heartRates, nowMs, 10 * 60 * 1000L)
    val hrAvg60 = averageBpm(heartRates, nowMs, 60 * 60 * 1000L)
    val recent = heartRates
        .filter { it.timestamp + it.duration >= nowMs - 15 * 60 * 1000L }
        .maxByOrNull { it.timestamp }
    val hrNow = recent?.beatsPerMinute?.toInt() ?: 0
    val activity = when {
        stepsLast5m >= 200 || stepsLast15m >= 375 -> "ACTIVE"
        else -> "IDLE"
    }
    val confidence = if (hrNow > 0) 0.3 else 0.0
    return WearableSnapshotWindows(
        snapshot = HealthContextSnapshot(
            stepsLast5m = stepsLast5m,
            stepsLast15m = stepsLast15m,
            stepsLast60m = stepsLast60m,
            activityState = activity,
            hrNow = hrNow,
            hrAvg15m = hrNow,
            hrMeasuredAtMs = if (hrNow > 0) recent?.timestamp ?: 0L else 0L,
            rhrResting = if (restingBpm > 0) restingBpm else 60,
            timestamp = nowMs,
            confidence = confidence,
            source = "HealthKit",
            isValid = confidence > 0.3,
        ),
        hrAvg10 = hrAvg10,
        hrAvg60 = hrAvg60,
    )
}

/**
 * An [Exception] from the platform read logs the wearable line and returns an empty snapshot.
 * An [Error] propagates. The empty snapshot does not add insulin.
 */
internal fun wearableWindowsOrEmpty(
    log: MutableList<String>,
    read: () -> WearableSnapshotWindows,
): WearableSnapshotWindows {
    return try {
        read()
    } catch (e: Exception) {
        val errorType = e::class.simpleName ?: "Exception"
        log += "WEARABLE snapshot failed ($errorType): ${e.message.orEmpty()} — snapshot empty"
        WearableSnapshotWindows(snapshot = HealthContextSnapshot(), hrAvg10 = 0, hrAvg60 = 0)
    }
}

private fun averageBpm(samples: List<HR>, nowMs: Long, windowMs: Long): Int {
    val windowStart = nowMs - windowMs
    val inWindow = samples.filter { it.timestamp + it.duration >= windowStart }
    if (inWindow.isEmpty()) return 0
    return round(inWindow.map { it.beatsPerMinute.toInt() }.average()).toInt()
}
