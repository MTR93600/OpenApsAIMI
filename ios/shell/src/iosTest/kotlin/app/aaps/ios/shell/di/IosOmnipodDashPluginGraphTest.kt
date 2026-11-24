package app.aaps.ios.shell.di

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.resources.TextRefValueRegistry
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcPluginOmnipod
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.pump.OmnipodDash
import app.aaps.ios.shell.prefs.TestStorage
import app.aaps.plugins.sync.nsclientV3.ws.NsSocket
import app.aaps.plugins.sync.nsclientV3.ws.NsSocketFactory
import app.aaps.pump.medtrum.ios.IosMedtrumPlugin
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosOmnipodDashPlugin
import app.aaps.pump.omnipod.dashctl.ios.plugin.OMNIPOD_DASH_IOS_PLUGIN_NAME
import app.aaps.pump.omnipod.dashctl.ios.plugin.omnipodDashIosPluginDescription
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.ui.DashOverviewContent
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

    private fun graph() = createGraphFactory<IosProbeGraph.Factory>().create(NoCrashReporter)

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
    fun `the app graph offers Dash in the pump driver bucket next to Medtrum`() {
        TestStorage.install()
        val app = createGraphFactory<IosAppGraph.Factory>().create(NoSockets, NoCrashReporter)

        val plugins = app.contributedPumpDriverPlugins
        val dash = assertIs<IosOmnipodDashPlugin>(plugins[1080], "Dash under the Android key 1080")
        assertIs<IosMedtrumPlugin>(plugins[1120])
        assertEquals(PluginType.PUMP, dash.getType())
        assertEquals(1, plugins.values.count { it is IosOmnipodDashPlugin }, "one Dash plugin only")
        // Only in the @PumpDriver bucket, so the follower app, which does not merge it, has no driver.
        assertTrue(app.contributedPlugins.values.none { it is IosOmnipodDashPlugin || it is IosMedtrumPlugin }, "no driver in the plain bucket")
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
    fun `the config builder shows the description and icon of Android Dash`() {
        val description = omnipodDashIosPluginDescription()

        assertEquals(DashStrings.omnipod_dash_pump_description, description.description)
        assertSame(IcPluginOmnipod, description.icon)
    }

    @Test
    fun `the plugin has a pump screen`() {
        val plugin = graph().omnipodDashPump

        // The plugin entry opens this screen (pod state, buttons, activation and deactivation wizards),
        // the way the Medtrum entry opens its own.
        assertIs<DashOverviewContent>(plugin.pluginDescription.composeContentProvider?.invoke(plugin))
    }

    @Test
    fun `the texts of the Dash screens resolve once the plugin is built`() {
        graph().omnipodDashPump

        // dashctl registers its own string owner (it is not in StringOwnerModules), so building the
        // plugin must be enough for its texts to show as text and not as names.
        assertEquals("Activate Pod", TextRefValueRegistry.textOf(DashStrings.dash_button_activate_pod as TextRef.Named))
    }

    @Test
    fun `the plugin has the settings screen of Android Dash`() {
        val plugin = graph().omnipodDashPump

        // Beeps, pod alerts and notification sounds, so a user can change them on the iPhone.
        assertTrue(plugin.hasPreferences(), "the config builder must show a settings button")
        val screen = assertIs<PreferenceSubScreenDef>(plugin.getPreferenceScreenContent())
        assertEquals(
            listOf("omnipod_dash_beeps", "omnipod_dash_alerts", "omnipod_dash_notifications"),
            screen.items.map { assertIs<PreferenceSubScreenDef>(it).key }
        )
        assertEquals("Bolus beeps enabled", TextRefValueRegistry.textOf(DashBeepKey.BolusBeepsEnabled.title as TextRef.Named))
    }
}
