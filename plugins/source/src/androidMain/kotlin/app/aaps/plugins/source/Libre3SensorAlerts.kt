package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmSensorAlertRules
import app.aaps.core.interfaces.source.CgmSensorAlertState
import app.aaps.core.interfaces.source.CgmSensorFailure
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.libre3.Libre3WarmupState

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
 * Not here yet: the health messages of the sensor (a sensor error, an end the sensor reports by
 * itself). The driver on this branch does not hand them to its watchers. Until it does, such an end
 * shows as "ended" when the life is over, and a sensor error only when the driver fails.
 *
 * Not thread safe on its own: the plugin calls it under one lock.
 */
internal class Libre3SensorAlerts {

    private var phase: Libre3WarmupState.Phase = Libre3WarmupState.Phase.IDLE
    private var linkLostSinceMs: Long? = null
    private var warmupDoneAtMs: Long? = null
    private var lastReadingAtMs: Long? = null
    private var secondSensorReadyAtMs: Long? = null

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

    /** A new sensor in the production slot: what was known of the old one is forgotten. */
    fun onNewSensor() {
        linkLostSinceMs = null
        warmupDoneAtMs = null
        lastReadingAtMs = null
    }

    fun state(running: Boolean, sensorStored: Boolean, expiresAtMs: Long?, nowMs: Long, expiringLeadMs: Long): CgmSensorAlertState =
        CgmSensorAlertRules.map(
            CgmSensorAlertRules.Input(
                stopped = !running,
                sensorStored = sensorStored,
                failure = if (phase == Libre3WarmupState.Phase.FAILED) CgmSensorFailure.OTHER else null,
                endedBySensor = false,
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
}
