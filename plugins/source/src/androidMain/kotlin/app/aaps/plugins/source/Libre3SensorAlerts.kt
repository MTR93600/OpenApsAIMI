package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmSensorAlertRules
import app.aaps.core.interfaces.source.CgmSensorAlertState
import app.aaps.core.interfaces.source.CgmSensorFailure
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.libre3.Libre3WarmupState
import app.aaps.plugins.libre3.parse.Libre3PatchStatus
import app.aaps.plugins.libre3.parse.Libre3SensorError

/**
 * What the Libre 3 driver says, turned into the generic sensor alarm state
 * ([app.aaps.core.interfaces.source.CgmSensorAlertProvider]).
 *
 * The plugin hands every driver event to it; the times of the changes are kept here, so each state
 * names its episode with a time that does not move. [state] then applies [CgmSensorAlertRules].
 *
 * - `FAILED`: a final failure of the driver.
 * - `RECONNECTING`: the link is lost, since the first event of that phase.
 * - `READY` after `WARMING`: warm-up done.
 * - The end is the end of the life read by the NFC scan. A Libre 3 has no grace period.
 *
 * - The health messages of the sensor ([onStatus]): a fault code is a sensor error, and an end the
 *   sensor reports by itself is "expired" when the life is over, else "ended early". A code this
 *   driver does not know counts only after [UNKNOWN_ERRORS_TO_FAIL] messages in a row, so one odd
 *   message does not sound the urgent alarm. A sending problem often passes by itself and is no fault.
 *
 * Not thread safe on its own: the plugin calls it under one lock.
 */
internal class Libre3SensorAlerts {

    private var phase: Libre3WarmupState.Phase = Libre3WarmupState.Phase.IDLE
    private var linkLostSinceMs: Long? = null
    private var warmupDoneAtMs: Long? = null
    private var lastReadingAtMs: Long? = null
    private var secondSensorReadyAtMs: Long? = null
    private var fault: Fault? = null
    private var unknownInARow = 0

    /** What a health message of the sensor says for the alarms. */
    private enum class Fault { ERROR, ENDED }

    fun onWarmup(state: Libre3WarmupState, nowMs: Long) {
        val previous = phase
        phase = state.phase
        linkLostSinceMs = if (state.phase == Libre3WarmupState.Phase.RECONNECTING) linkLostSinceMs ?: nowMs else null
        if (state.phase == Libre3WarmupState.Phase.READY && previous == Libre3WarmupState.Phase.WARMING) warmupDoneAtMs = nowMs
        if (state.phase == Libre3WarmupState.Phase.WARMING) warmupDoneAtMs = null
    }

    fun onReading(timestampMs: Long) {
        if (timestampMs > (lastReadingAtMs ?: Long.MIN_VALUE)) lastReadingAtMs = timestampMs
    }

    fun onStaging(state: StagingState, nowMs: Long) {
        secondSensorReadyAtMs = if (state == StagingState.READY) secondSensorReadyAtMs ?: nowMs else null
    }

    /** One health message of the sensor that feeds the loop. */
    fun onStatus(status: Libre3PatchStatus) {
        val raw = faultOf(status)
        if (raw == Fault.ERROR && status.sensorError == Libre3SensorError.UNKNOWN) {
            unknownInARow++
            if (unknownInARow >= UNKNOWN_ERRORS_TO_FAIL) fault = Fault.ERROR
        } else {
            unknownInARow = 0
            fault = raw
        }
    }

    /** A new sensor in the production slot: what was known of the old one is forgotten. */
    fun onNewSensor() {
        linkLostSinceMs = null
        warmupDoneAtMs = null
        lastReadingAtMs = null
        fault = null
        unknownInARow = 0
    }

    private fun faultOf(status: Libre3PatchStatus): Fault? = when {
        status.sensorError == Libre3SensorError.INSERTION_FAILURE -> Fault.ERROR

        status.sensorError == Libre3SensorError.EXPIRED ||
            status.sensorError == Libre3SensorError.TERMINATED ||
            status.patchState.isTerminated ||
            status.patchState.isExpiredOrError                    -> Fault.ENDED

        status.sensorError == Libre3SensorError.UNKNOWN           -> Fault.ERROR
        else                                                      -> null
    }

    fun state(running: Boolean, sensorStored: Boolean, expiresAtMs: Long?, nowMs: Long, expiringLeadMs: Long): CgmSensorAlertState =
        CgmSensorAlertRules.map(
            CgmSensorAlertRules.Input(
                stopped = !running,
                sensorStored = sensorStored,
                failure = when {
                    phase == Libre3WarmupState.Phase.FAILED -> CgmSensorFailure.OTHER
                    fault == Fault.ERROR                    -> CgmSensorFailure.SENSOR_ERROR
                    else                                    -> null
                },
                endedBySensor = fault == Fault.ENDED,
                expiresAtMs = expiresAtMs,
                linkLostSinceMs = linkLostSinceMs,
                lastReadingAtMs = lastReadingAtMs,
                readingExpected = phase != Libre3WarmupState.Phase.WARMING && phase != Libre3WarmupState.Phase.PAIRING,
                issueSinceMs = null,
                secondSensorReadyAtMs = secondSensorReadyAtMs,
                warmupDoneAtMs = warmupDoneAtMs,
                expiringLeadMs = expiringLeadMs,
                nowMs = nowMs,
            )
        )

    companion object {

        /** Health messages with an unknown error code in a row before they count as a fault. */
        const val UNKNOWN_ERRORS_TO_FAIL = 2
    }
}
