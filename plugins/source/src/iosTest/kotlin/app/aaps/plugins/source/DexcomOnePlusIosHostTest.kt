package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The iPhone host may be switched on now, and must still say what is different here.
 *
 * Nothing locks the plugin any more, so the contract moved: the switch is free, the limits are in
 * the note that replaces the description, and what the host hands out by itself is still the stub
 * until the engineering switch is turned on.
 *
 * The real driver is shared code and the host can reach it, so the tests below say which of the
 * two comes back and when. Nothing here builds `OnePlusIosPlatform`: that opens a
 * `CBCentralManager`, which a unit test on the simulator has no business doing. The config
 * builder is never reached either - only the start screen asks for it.
 */
class DexcomOnePlusIosHostTest {

    private val host = DexcomOnePlusIosHost(SilentLogger, { error("the config builder is not used here") })

    @Test
    fun `nothing locks the plugin on iPhone any more`() {
        assertNull(host.notUsableReason)
    }

    @Test
    fun `the host still says what is different on iPhone`() {
        assertNotNull(host.platformNote)
    }

    @Test
    fun `the production slot hands out the stub until the engineering switch is turned on`() {
        assertSame(OnePlusCgmDriverStub.instance, host.productionDriver())
        assertTrue(!host.useRealSkeleton())
    }

    @Test
    fun `the pre-soak slot is the stub because a second slot cannot have its own scan here`() {
        assertSame(OnePlusCgmDriverStub.instance, host.stagingDriver())
    }

    @Test
    fun `the stub never claims a session`() {
        assertTrue(!host.productionDriver().isSessionUp())
    }

    @Test
    fun `there is no pre-soak driver to promote`() {
        assertNull(host.promoteStagingInstance())
    }

    // The screen entry itself is not asserted here. `screenItems` wires the shared start screen to
    // the plugin, and a unit test on the simulator cannot build a plugin. What that screen does is
    // covered where it can be run: `DexcomOnePlusStartRulesTest` and `DexcomOnePlusStartStringsTest`
    // run its rules and its strings on this simulator, and `DexcomOnePlusStartScreenTest` renders it
    // under Robolectric with a one slot host, which is the iPhone shape.

    @Test
    fun `each slot gets its own store`() {
        val production = host.slotStore(null)
        val staging = host.slotStore(host.stagingNamespace)
        assertTrue(production !== staging)
    }

    /**
     * Swallows the lines the host writes; this test is about what it answers, not what it logs.
     *
     * Written out by hand because an iOS test has no Mockito.
     */
    private object SilentLogger : AAPSLogger {

        override fun debug(message: String) = Unit
        override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, accessor: () -> String) = Unit
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun warn(tag: LTag, message: String) = Unit
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun info(tag: LTag, message: String) = Unit
        override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(tag: LTag, message: String) = Unit
        override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
        override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
        override fun error(format: String, vararg arguments: Any?) = Unit
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    }
}
