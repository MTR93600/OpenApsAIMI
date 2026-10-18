package app.aaps.ios.shell.di

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.shared.clientbindings.ClientGraphBindings
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The second half is the safety rule. An iPhone cannot enter a Medtrum serial number and cannot
 * start a patch, so the plugin must never become the active pump. If someone lifts the enforcement
 * without building the pairing flow, this test is what says so.
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
    fun `the user cannot switch the Medtrum pump on`() {
        val plugin = graph().medtrumPump

        assertEquals(EnforcedState.Disabled, plugin.enforcedState())
        assertFalse(plugin.isEnabled(PluginType.PUMP))
        assertFalse(plugin.isEnabled())
    }

    @Test
    fun `the plugin answers as the driver it delegates to`() {
        val plugin = graph().medtrumPump

        assertEquals(PumpType.MEDTRUM_NANO, plugin.model())
        assertFalse(plugin.canHandleDST())
        assertFalse(plugin.isConfigured(), "no serial number can be entered on iOS yet")
    }
}
