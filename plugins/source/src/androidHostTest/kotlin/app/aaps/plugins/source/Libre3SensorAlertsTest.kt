package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmSensorAlertState
import app.aaps.core.interfaces.source.CgmSensorFailure
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.libre3.Libre3WarmupState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How the Libre 3 driver events map to the generic sensor alarm state. */
class Libre3SensorAlertsTest {

    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    private fun Libre3SensorAlerts.at(nowMs: Long, expiresAtMs: Long? = now + 100 * hour, running: Boolean = true) =
        state(running = running, sensorStored = true, expiresAtMs = expiresAtMs, nowMs = nowMs, expiringLeadMs = 12 * hour)

    @Test
    fun failedIsAFailure() {
        val alerts = Libre3SensorAlerts()
        alerts.onWarmup(Libre3WarmupState(Libre3WarmupState.Phase.FAILED), now)
        assertEquals(CgmSensorAlertState.Failed(CgmSensorFailure.OTHER), alerts.at(now))
        assertEquals(CgmSensorAlertState.Ok, alerts.at(now, running = false))
    }

    @Test
    fun reconnectingIsASignalLossSinceItsFirstEvent() {
        val alerts = Libre3SensorAlerts()
        alerts.onReading(now)
        alerts.onWarmup(Libre3WarmupState(Libre3WarmupState.Phase.RECONNECTING), now + 1_000L)
        alerts.onWarmup(Libre3WarmupState(Libre3WarmupState.Phase.RECONNECTING), now + 2_000L)
        assertEquals(CgmSensorAlertState.SignalLost(now + 1_000L), alerts.at(now + 3_000L))
    }

    @Test
    fun readyAfterWarmingIsWarmupDone() {
        val alerts = Libre3SensorAlerts()
        alerts.onWarmup(Libre3WarmupState(Libre3WarmupState.Phase.WARMING), now)
        alerts.onWarmup(Libre3WarmupState(Libre3WarmupState.Phase.READY), now + hour)
        alerts.onReading(now + hour)
        assertEquals(CgmSensorAlertState.WarmupDone(now + hour), alerts.at(now + hour + 1_000L))
    }

    @Test
    fun theEndOfLifeIsEnded() {
        val alerts = Libre3SensorAlerts()
        val end = now - 1_000L
        alerts.onReading(now)
        assertEquals(CgmSensorAlertState.Expired(end), alerts.at(now, expiresAtMs = end))
    }

    @Test
    fun aReadySecondSensorIsReported() {
        val alerts = Libre3SensorAlerts()
        alerts.onReading(now)
        alerts.onStaging(StagingState.READY, now)
        assertEquals(CgmSensorAlertState.SecondSensorReady(now), alerts.at(now + 1_000L))
    }
}
