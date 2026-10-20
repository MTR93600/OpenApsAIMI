package app.aaps.pump.medtrum.ios

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** An [AAPSLogger] that keeps every line, so a test can show what the driver said. */
private class RecordingAapsLogger : AAPSLogger {

    val lines = mutableListOf<String>()

    private fun add(level: String, tag: LTag, message: String) {
        lines += "$level ${tag.tag} $message"
    }

    override fun debug(message: String) = add("D", LTag.CORE, message)
    override fun debug(enable: Boolean, tag: LTag, message: String) = add("D", tag, message)
    override fun debug(tag: LTag, message: String) = add("D", tag, message)
    override fun debug(tag: LTag, accessor: () -> String) = add("D", tag, accessor())
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = add("D", tag, format)
    override fun warn(tag: LTag, message: String) = add("W", tag, message)
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = add("W", tag, format)
    override fun info(tag: LTag, message: String) = add("I", tag, message)
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = add("I", tag, format)
    override fun error(tag: LTag, message: String) = add("E", tag, message)
    override fun error(tag: LTag, message: String, throwable: Throwable) = add("E", tag, message)
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = add("E", tag, format)
    override fun error(message: String) = add("E", LTag.CORE, message)
    override fun error(message: String, throwable: Throwable) = add("E", LTag.CORE, message)
    override fun error(format: String, vararg arguments: Any?) = add("E", LTag.CORE, format)
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = add("D", tag, message)
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = add("I", tag, message)
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = add("W", tag, message)
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = add("E", tag, message)
}

/**
 * What the Medtrum plugin wrapper promises on iOS.
 *
 * Two halves, and both of them are about honesty rather than therapy:
 *
 * - the plugin declares itself forced off, because an iPhone cannot start a patch yet;
 * - the driver the plugin delegates to refuses every therapeutic command while it is not paired,
 *   and says so in words, instead of answering "done".
 *
 * The driver's behaviour once it IS connected is covered by `IosMedtrumPumpTest`, which drives a
 * real session over a fake transport. Nothing here opens the radio: `IosMedtrumDriverHost` only
 * creates `CBCentralManager` in `openRadio`, which is never called below.
 */
class IosMedtrumPluginWiringTest {

    @BeforeTest
    fun clearStoredState() {
        // The host reads the real NSUserDefaults. A value left by another test would decide
        // whether the pump counts as configured, so start from nothing.
        IosMedtrumStateStore().clear()
    }

    private fun newHost(pumpSync: PumpSync = RecordingPumpSync()): IosMedtrumDriverHost = IosMedtrumDriverHost(
        aapsLogger = RecordingAapsLogger(),
        textResolver = TestTextResolver,
        pumpSync = pumpSync,
        detailedBolusInfoStorage = FakeDetailedBolusInfoStorage(),
        temporaryBasalStorage = FakeTemporaryBasalStorage()
    )

    // -- The plugin's own declaration -----------------------------------------------------------

    @Test
    fun `the plugin is a pump plugin`() {
        assertEquals(PluginType.PUMP, medtrumIosPluginDescription().mainType)
    }

    @Test
    fun `the plugin is forced off because it cannot pair`() {
        val applying = medtrumIosPluginDescription().enforcements.filter { it.applies() }
        assertEquals(1, applying.size, "exactly one enforcement should apply")
        assertEquals(EnforcedState.Disabled, applying.first().state)
    }

    @Test
    fun `the plugin says why it is off in words a user can read`() {
        val description = medtrumIosPluginDescription().description
        assertEquals(TextRef.Literal(MEDTRUM_IOS_NOT_READY_REASON), description)
        assertTrue(MEDTRUM_IOS_NOT_READY_REASON.contains("Not usable on iPhone yet"))
    }

    @Test
    fun `the reason given to the user and the reason given to the config builder are the same text`() {
        val enforcement = medtrumIosPluginDescription().enforcements.single()
        assertEquals(medtrumIosPluginDescription().description, enforcement.reason)
    }

    // -- The driver behind it --------------------------------------------------------------------

    @Test
    fun `a fresh host has no serial number so the pump is not configured`() {
        val host = newHost()
        assertEquals(0L, host.state.pumpSN)
        assertFalse(host.driver.isConfigured(), "no serial number means no pump")
        assertFalse(host.driver.isInitialized())
        assertFalse(host.driver.isReady())
    }

    @Test
    fun `a fresh host reports no patch so the pump counts as suspended`() {
        val host = newHost()
        assertEquals(MedtrumPumpState.NONE, host.state.pumpState)
        assertTrue(host.driver.isSuspended())
    }

    @Test
    fun `the pump is the Nano and does not handle daylight saving`() {
        val host = newHost()
        assertEquals(PumpType.MEDTRUM_NANO, host.driver.model())
        assertEquals(ManufacturerType.Medtrum, host.driver.manufacturer())
        assertFalse(host.driver.canHandleDST())
    }

    @Test
    fun `a bolus is refused with a reason while the pump is not paired`() = runTest {
        val host = newHost()
        val result = host.driver.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 })
        assertFalse(result.success)
        assertFalse(result.enacted)
        assertEquals(0.0, result.bolusDelivered)
        assertTrue(result.comment.isNotBlank(), "a refusal must say why")
    }

    @Test
    fun `a temp basal is refused with a reason while the pump is not paired`() = runTest {
        val host = newHost()
        val result = host.driver.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL)
        assertFalse(result.success)
        assertFalse(result.enacted)
        assertTrue(result.comment.isNotBlank())
    }

    @Test
    fun `cancelling a temp basal is refused with a reason while the pump is not paired`() = runTest {
        val host = newHost()
        val result = host.driver.cancelTempBasal(true)
        assertFalse(result.success)
        assertFalse(result.enacted)
        assertTrue(result.comment.isNotBlank())
    }

    @Test
    fun `a percent temp basal is always refused because Medtrum has none`() = runTest {
        val host = newHost()
        val result = host.driver.setTempBasalPercent(80, 30, false, PumpSync.TemporaryBasalType.NORMAL)
        assertFalse(result.success)
        assertFalse(result.enacted)
        assertTrue(result.comment.isNotBlank())
    }

    @Test
    fun `an extended bolus is always refused because Medtrum has none`() = runTest {
        val host = newHost()
        assertFalse(host.driver.setExtendedBolus(1.0, 30).success)
        assertFalse(host.driver.cancelExtendedBolus().success)
    }

    @Test
    fun `reading the pump status while not paired writes no history`() = runTest {
        val sync = RecordingPumpSync()
        val host = newHost(sync)
        host.driver.getPumpStatus("test")
        assertTrue(sync.calls.isEmpty(), "nothing should be written for a pump we never reached")
    }

    @Test
    fun `shutting a host down twice is harmless`() {
        val host = newHost()
        host.shutdown()
        host.shutdown()
        assertNotNull(host.driver)
    }
}
