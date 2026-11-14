package app.aaps.ios.shell.di

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.pump.OmnipodDash
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.ios.shell.prefs.TestStorage
import app.aaps.plugins.sync.nsclientV3.ws.NsSocket
import app.aaps.plugins.sync.nsclientV3.ws.NsSocketFactory
import app.aaps.pump.medtrum.ios.IosMedtrumPlugin
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosOmnipodDashPlugin
import app.aaps.pump.omnipod.dashctl.ios.plugin.OMNIPOD_DASH_IOS_PLUGIN_NAME
import app.aaps.pump.omnipod.dashctl.ios.plugin.omnipodDashIosPluginDescription
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.shared.clientbindings.ClientGraphBindings
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * That the iOS graph can really build the Omnipod Dash pump plugin, and that the user can choose it.
 *
 * The same reasons as `IosMedtrumPluginGraphTest`: linking only proves the symbols resolve; a plugin
 * that throws while it is built is only found by building it. The plugin is contributed to the plugin
 * map of the app graph with `@ContributesIntoMap`, which is the list the config builder offers, so
 * the app graph is built too and the plugin is looked up there under the Android key 1080.
 *
 * The safety half: with no pod running the plugin must say it is not initialized, so the loop never
 * believes it has a pump it cannot reach (Android `isInitialized` = `isPodRunning`).
 */
class IosOmnipodDashPluginGraphTest {

    /** A factory that makes no sockets, as `IosHistoryWindowScopingTest`: nothing here connects. */
    private object NoSockets : NsSocketFactory {

        override fun create(url: String): NsSocket? = null
    }

    @BeforeTest
    fun noPodStored() {
        // The host reads the real Keychain. A pod left by another run would make it look running.
        DashKeychainStateStore().clear()
    }

    private fun graph() = createGraphFactory<IosProbeGraph.Factory>().create(CoreObjectsGraph, ClientGraphBindings, NoCrashReporter)

    @Test
    fun `the graph builds the Omnipod Dash pump plugin`() {
        val plugin = graph().omnipodDashPump

        assertEquals(PluginType.PUMP, plugin.getType())
        assertEquals(OMNIPOD_DASH_IOS_PLUGIN_NAME, plugin.name)
    }

    @Test
    fun `the plugin is one object per graph`() {
        val g = graph()

        assertSame(g.omnipodDashPump, g.omnipodDashPump)
    }

    @Test
    fun `the user may switch the Omnipod Dash pump on`() {
        val plugin = graph().omnipodDashPump

        assertNotEquals(EnforcedState.Disabled, plugin.enforcedState())
    }

    @Test
    fun `the app graph offers Dash in the plugin list next to Medtrum`() {
        TestStorage.install()
        val app = createGraphFactory<IosAppGraph.Factory>().create(CoreObjectsGraph, ClientGraphBindings, NoSockets, NoCrashReporter)

        val plugins = app.contributedPlugins
        val dash = assertIs<IosOmnipodDashPlugin>(plugins[1080], "Dash under the Android key 1080")
        assertIs<IosMedtrumPlugin>(plugins[1120])
        assertEquals(PluginType.PUMP, dash.getType())
        assertEquals(1, plugins.values.count { it is IosOmnipodDashPlugin }, "one Dash plugin only")
    }

    @Test
    fun `with no pod the plugin is not initialized`() {
        val plugin = graph().omnipodDashPump

        assertFalse(plugin.isInitialized(), "no pod runs, the loop must not use this pump")
        assertTrue(plugin.isConnected(), "Android: no running pod reads as connected, so the queue does not wait")
        assertEquals("4241", plugin.serialNumber(), "Android serial without a pod")
    }

    @Test
    fun `the plugin answers as Android Dash`() {
        val plugin = graph().omnipodDashPump

        assertEquals(PumpType.OMNIPOD_DASH, plugin.model())
        assertEquals(ManufacturerType.Insulet, plugin.manufacturer())
        assertEquals(PumpType.OMNIPOD_DASH, plugin.pumpDescription.pumpType)
        assertEquals(0.05, plugin.pumpDescription.bolusStep)
        assertFalse(plugin.pumpDescription.isExtendedBolusCapable)
        assertFalse(plugin.canHandleDST())
        assertTrue(plugin.isConfigured(), "Android Dash does not override isConfigured")
    }

    @Test
    fun `the plugin is the OmnipodDash pump the setup wizard knows`() {
        val plugin = graph().omnipodDashPump

        // Android OmnipodDashPumpPlugin is OmnipodDash. The shared setup wizard lets the user pass
        // its pump screen with an OmnipodDash pump that has no pod yet, and hides "Read status".
        assertIs<OmnipodDash>(plugin, "the setup wizard must know this pump as Omnipod Dash")
    }

    @Test
    fun `the config builder says what works on iPhone`() {
        assertEquals(InterfacesStrings.omnipod_dash_ios_caution, omnipodDashIosPluginDescription().description)
    }
}
