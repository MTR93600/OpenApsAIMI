package app.aaps.ios.shell.di

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.shared.clientbindings.ClientGraphBindings
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * That the iOS graph can really build the Medtrum pump plugin, and that it stays switched off.
 *
 * Linking the framework proves the symbols resolve. It does not prove the graph can hand the
 * plugin over: a `@ContributesIntoMap` whose dependencies cannot be satisfied is a compile error,
 * but a plugin that throws while being built is only found by building it.
 *
 * The second half is the safety rule, and it moved. An iPhone can now be told which pump is
 * yours - the plugin offers a Bluetooth chooser that reads the serial out of the advertisement -
 * so nothing forces the plugin off any more. What it still cannot do is START a patch, and the
 * thing that must stay true is that a plugin with no pump chosen reports itself unconfigured, so
 * the loop never believes it has a pump it cannot reach.
 */
class IosMedtrumPluginGraphTest {

    private fun graph() = createGraphFactory<IosProbeGraph.Factory>().create(CoreObjectsGraph, ClientGraphBindings)

    @Test
    fun `the graph builds the Medtrum pump plugin`() {
        val plugin = graph().medtrumPump

        assertEquals(PluginType.PUMP, plugin.getType())
        assertTrue(plugin.name.isNotBlank())
    }

    @Test
    fun `the plugin is one object per graph`() {
        val g = graph()

        assertSame(g.medtrumPump, g.medtrumPump)
    }

    @Test
    fun `the user may switch the Medtrum pump on`() {
        val plugin = graph().medtrumPump

        assertNotEquals(EnforcedState.Disabled, plugin.enforcedState())
    }

    @Test
    fun `the plugin offers the pump chooser so a serial can be learned`() {
        // Without this screen there is no way to tell the driver which pump is yours, and the
        // plugin would be switchable but unable to reach anything.
        val content = graph().medtrumPump.getPreferenceScreenContent()

        assertNotNull(content, "the plugin must offer a settings screen")
    }

    @Test
    fun `the plugin answers as the driver it delegates to`() {
        val plugin = graph().medtrumPump

        // No pump chosen yet, so no device type: the same answer as Android `MedtrumPump.pumpType()`.
        assertEquals(PumpType.MEDTRUM_UNTESTED, plugin.model())
        assertTrue(plugin.canHandleDST())
        assertFalse(plugin.isConfigured(), "no pump has been chosen in this test, so it is not configured")
    }
}
