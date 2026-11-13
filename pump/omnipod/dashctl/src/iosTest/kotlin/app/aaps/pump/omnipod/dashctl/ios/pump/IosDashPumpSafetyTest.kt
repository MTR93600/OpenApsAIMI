package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Safety guard: the pump must NEVER send bytes without a paired pod
 * AND an established session.
 *
 * The pump runs on the real `MessageIO` and `DashBleLink` against a simulated pod; any write to
 * the radio after the link was prepared is a failure. Runs on the iOS simulator.
 */
class IosDashPumpSafetyTest {

    private val fixture = DashPumpOnFakePod()
    private val pump get() = fixture.pump
    private val stateManager get() = fixture.stateManager

    @AfterTest
    fun closePod() {
        fixture.pod.close()
        fixture.pod.assertNoProblems()
    }

    private fun assertNothingSent() {
        assertTrue(fixture.writesFromPump().isEmpty(), "no bytes may go to the radio: ${fixture.writesFromPump()}")
    }

    private fun fakeBolus(insulin: Double): DetailedBolusInfo =
        DetailedBolusInfo().apply { this.insulin = insulin }

    @Test
    fun `unpaired pump refuses bolus and sends nothing`() = runTest {
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success, "bolus must fail when unpaired")
        assertNothingSent()
    }

    @Test
    fun `unpaired pump refuses temp basal and sends nothing`() = runTest {
        val result = pump.setTempBasalAbsolute(
            1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL
        )
        assertFalse(result.success)
        assertNothingSent()
    }

    @Test
    fun `unpaired pump refuses cancel temp basal`() = runTest {
        val result = pump.cancelTempBasal(false)
        assertFalse(result.success)
        assertNothingSent()
    }

    @Test
    fun `unpaired pump does not ask for a status`() = runTest {
        pump.getPumpStatus("test")
        assertNothingSent()
    }

    @Test
    fun `paired without session refuses commands`() = runTest {
        stateManager.updateFromPairing(12345L, ByteArray(16) { it.toByte() })
        pump.connect("test")
        // No establishSession(): guard must fail closed.
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success)
        assertNothingSent()
    }

    @Test
    fun `paired with session but disconnected refuses commands`() = runTest {
        stateManager.updateFromPairing(12345L, ByteArray(16) { it.toByte() })
        // Connected then disconnected; no session ever established.
        pump.connect("test")
        pump.disconnect("test")
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success)
        assertNothingSent()
    }

    @Test
    fun `stopDelivery refuses when unpaired`() {
        val result = pump.stopDelivery()
        assertFalse(result.success)
        assertNothingSent()
    }

    @Test
    fun `isConfigured reflects pairing state`() {
        assertFalse(pump.isConfigured())
        stateManager.updateFromPairing(12345L, ByteArray(16))
        assertTrue(pump.isConfigured())
        assertFalse(pump.isInitialized(), "no session yet")
    }

    @Test
    fun `a session that is set up and then disconnected refuses commands`() = runTest {
        fixture.pairAndConnect()
        pump.disconnect("test")
        val before = fixture.writesFromPump().size

        val result = pump.deliverTreatment(fakeBolus(1.0))

        assertFalse(result.success)
        assertTrue(fixture.writesFromPump().size == before, "nothing may be sent after a disconnect")
    }
}
