package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.DeviceHandover
import app.aaps.core.interfaces.maintenance.DeviceHandovers
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.queue.CommandQueue
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [DeviceHandovers], the same on Android and iPhone.
 *
 * Only the active pump and the active BG source are collected: a plugin that is switched off holds
 * no device this phone is using. On import, the plugin that knows the id is switched on first, so the
 * pump or sensor is used straight away even when the file came from the other platform, where the
 * plugin has another name and the plugin choice in the settings does not match.
 *
 * [activePlugin], [commandQueue] and [configBuilder] are providers: the device plugins are part of
 * the plugin list, so asking for them directly would build a cycle.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DeviceHandoversImpl(
    private val aapsLogger: AAPSLogger,
    private val activePlugin: () -> ActivePlugin,
    private val commandQueue: () -> CommandQueue,
    private val configBuilder: () -> ConfigBuilder
) : DeviceHandovers {

    private companion object {

        /** Long enough for a pump command, maybe a bolus, to finish. */
        val COMMAND_WAIT = 60.seconds
        val POLL = 250.milliseconds
    }

    override suspend fun collect(): Map<String, String> {
        // A command that still runs changes the numbers the next phone needs (the pump's sequence).
        val idle = withTimeoutOrNull(COMMAND_WAIT) {
            while (commandQueue().performing() != null) delay(POLL)
        } != null
        if (!idle) aapsLogger.warn(LTag.CORE, "Device handover: a pump command still runs after $COMMAND_WAIT, collecting anyway")
        val plugins = activePlugin().let { listOf(it.activePumpInternal, it.activeBgSource) }
        val out = mutableMapOf<String, String>()
        plugins.filterIsInstance<DeviceHandover>().distinctBy { it.handoverId }.forEach { device ->
            val fields = guarded("export ${device.handoverId}") { device.exportHandover() }
            if (fields != null) {
                out[DeviceHandovers.PREFIX + device.handoverId] = DeviceHandovers.encode(fields)
                aapsLogger.info(LTag.CORE, "Device handover: ${device.handoverId} collected, ${fields.size} fields")
            } else aapsLogger.info(LTag.CORE, "Device handover: ${device.handoverId} has no device paired, left out")
        }
        return out
    }

    override fun idsIn(values: Map<String, String>): List<String> =
        values.keys.filter { it.startsWith(DeviceHandovers.PREFIX) }.map { it.removePrefix(DeviceHandovers.PREFIX) }.sorted()

    override suspend fun apply(values: Map<String, String>): List<String> {
        val taken = mutableListOf<String>()
        for (id in idsIn(values)) {
            val fields = DeviceHandovers.decode(values.getValue(DeviceHandovers.PREFIX + id))
            if (fields == null) {
                aapsLogger.error(LTag.CORE, "Device handover: $id cannot be read, left out")
                continue
            }
            val plugin = activePlugin().getPluginsList().firstOrNull { it is DeviceHandover && it.handoverId == id }
            if (plugin == null) {
                aapsLogger.warn(LTag.CORE, "Device handover: no plugin for $id in this build, left out")
                continue
            }
            if (!plugin.isEnabled()) guarded("select ${plugin::class.simpleName}") {
                configBuilder().performPluginSwitch(plugin, enabled = true, type = plugin.getType())
            }
            val ok = guarded("import $id") { (plugin as DeviceHandover).importHandover(fields) } == true
            aapsLogger.info(LTag.CORE, "Device handover: $id ${if (ok) "taken over" else "refused by the plugin"}")
            if (ok) taken += id
        }
        return taken
    }

    /** One device on its own: one that throws must not stop the others. A cancellation still goes up. */
    private suspend fun <T> guarded(name: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            aapsLogger.error(LTag.CORE, "Device handover: '$name' failed: ${t.message}", t)
            null
        }
}
