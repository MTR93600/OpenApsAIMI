package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The iPhone host must refuse out loud, never quietly.
 *
 * This is the contract that keeps a person from believing their iPhone is reading a sensor: the
 * plugin is listed, it carries a reason, and what it hands out by itself is the stub, which reports
 * FAILED and never claims a session.
 *
 * The real driver is shared code now and the host can reach it, so the tests below say which of
 * the two comes back and when. Nothing here builds `OnePlusIosPlatform`: that opens a
 * `CBCentralManager`, which a unit test on the simulator has no business doing.
 */
class DexcomOnePlusIosHostTest {

    private val host = DexcomOnePlusIosHost(SilentLogger)

    @Test
    fun `the host says why the plugin cannot work here`() {
        assertNotNull(host.notUsableReason)
    }

    @Test
    fun `the plugin is listed so the reason can be read`() {
        assertTrue(host.isAvailable())
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

    @Test
    fun `no screen button is offered because there are no screens`() {
        assertEquals(emptyList(), host.screenItems())
    }

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
