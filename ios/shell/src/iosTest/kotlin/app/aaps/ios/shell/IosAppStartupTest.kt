package app.aaps.ios.shell

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Start up, which is two calls that have to happen in one order.
 *
 * Both halves were found by the app dying rather than by reading the code, and both failures looked
 * nothing like their cause:
 *
 * - without [PluginRegistry.register], `PluginStore.plugins` is an unset `lateinit`, and the first
 *   thing to read it is `ProfileEditorViewModel` - from a coroutine in its own constructor, where an
 *   exception is unhandled on Kotlin/Native and **aborts the process**. The app drew nothing.
 * - without [PluginRegistry.initializeConfig], the list is there but no plugin is active in any
 *   category, so asking for the pump throws "No pump selected". That call also *starts* every
 *   enabled plugin. While it only verified the categories, NSClientV3 was enabled but had never run
 *   `onStart`, so the client never synced and never opened a websocket until the Configuration
 *   screen was opened by hand.
 *
 * Neither shows up in a build. These tests are here so a reordering does not quietly bring them
 * back.
 */
class IosAppStartupTest {

    private object SilentText : TextResolver {

        override fun gs(ref: TextRef): String = ""
        override fun gs(ref: TextRef, vararg args: Any?): String = ""
        override fun gsNotLocalised(ref: TextRef): String = ""
        override fun shortTextMode(): Boolean = false
    }

    private object SilentLogger : AAPSLogger {

        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(tag: LTag, message: String) {}
        override fun error(tag: LTag, message: String, throwable: Throwable) {}
        override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun error(message: String) {}
        override fun error(message: String, throwable: Throwable) {}
        override fun error(format: String, vararg arguments: Any?) {}
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    }

    /** PluginBase needs one; nothing here posts a notification, so every call is a no-op. */
    private object NoNotifications : NotificationManager {

        override val notifications: StateFlow<List<AapsNotification>> = MutableStateFlow(emptyList())
        override fun cleanUp() {}
        override fun post(id: NotificationId, text: String, level: NotificationLevel, validMinutes: Int, sound: AlarmSound?, actions: List<NotificationAction>, validityCheck: (() -> Boolean)?) = NotificationHandle(0)
        override fun post(id: NotificationId, text: String, level: NotificationLevel, date: Long, validTo: Long, sound: AlarmSound?, actions: List<NotificationAction>, validityCheck: (() -> Boolean)?) = NotificationHandle(0)
        override fun post(id: NotificationId, textRef: TextRef, level: NotificationLevel, validMinutes: Int, date: Long, validTo: Long, sound: AlarmSound?, actions: List<NotificationAction>, validityCheck: (() -> Boolean)?) = NotificationHandle(0)
        override fun dismiss(id: NotificationId) {}
        override fun dismiss(handle: NotificationHandle) {}
        override fun muteAllAlarms() {}
    }

    private class NamedPlugin(val id: String) : PluginBase(
        PluginDescription().also { it.mainType = PluginType.GENERAL },
        SilentLogger, SilentText, NoNotifications
    )

    /** Records what happened and in which order, which is the whole contract under test. */
    private class RecordingRegistry : PluginRegistry {

        val calls = mutableListOf<String>()
        var registered: List<PluginBase> = emptyList()

        override fun register(plugins: List<PluginBase>) {
            calls.add("register")
            registered = plugins
        }

        override fun initializeConfig(): List<Job> {
            calls.add("initialize")
            return emptyList()
        }
    }

    private fun run(plugins: Map<Int, PluginBase>): RecordingRegistry {
        val registry = RecordingRegistry()
        IosAppStartup(SilentLogger, registry, plugins, startPeriodicWork = {}).run()
        return registry
    }

    @Test
    fun `plugins are registered before the config is initialized`() {
        val registry = run(mapOf(1 to NamedPlugin("a")))

        assertEquals(listOf("register", "initialize"), registry.calls)
    }

    /** The order key decides the plugin list order and which default is picked. */
    @Test
    fun `plugins arrive sorted by their order key`() {
        val registry = run(mapOf(800 to NamedPlugin("safety"), 10 to NamedPlugin("iob"), 1000 to NamedPlugin("pump")))

        assertEquals(listOf("iob", "safety", "pump"), registry.registered.map { (it as NamedPlugin).id })
    }

    /** Map iteration order is not insertion order, so sorting has to be explicit. */
    @Test
    fun `a map given in the wrong order is still sorted`() {
        val registry = run(mapOf(1000 to NamedPlugin("last"), 1 to NamedPlugin("first")))

        assertEquals(listOf("first", "last"), registry.registered.map { (it as NamedPlugin).id })
    }

    /**
     * Empty is not a crash here, but it is a broken app: nothing would be active in any category.
     * Pinned so the emptiness stays visible as a real state rather than being assumed impossible.
     */
    @Test
    fun `no plugins at all still runs both steps`() {
        val registry = run(emptyMap())

        assertEquals(listOf("register", "initialize"), registry.calls)
        assertTrue(registry.registered.isEmpty())
    }

    /** The `@APS` bucket (Objectives among it) belongs in the list only when the loop runs here. */
    @Test
    fun `aps plugins are merged only when asked`() {
        val plugins = mapOf(10 to NamedPlugin("iob"))
        val aps = mapOf(840 to NamedPlugin("objectives"))

        val notMerged = RecordingRegistry()
        IosAppStartup(SilentLogger, notMerged, plugins, apsPlugins = aps, mergeApsPlugins = false, startPeriodicWork = {}).run()
        val merged = RecordingRegistry()
        IosAppStartup(SilentLogger, merged, plugins, apsPlugins = aps, mergeApsPlugins = true, startPeriodicWork = {}).run()

        assertEquals(listOf("iob"), notMerged.registered.map { (it as NamedPlugin).id })
        assertEquals(listOf("iob", "objectives"), merged.registered.map { (it as NamedPlugin).id })
    }

    /** The master services read plugin state, so they start after the plugins, as in `MainApp`. */
    @Test
    fun `master services start after the plugins`() {
        val registry = RecordingRegistry()
        IosAppStartup(
            SilentLogger, registry, mapOf(1 to NamedPlugin("a")),
            startPeriodicWork = { registry.calls.add("periodic") },
            startMasterServices = { registry.calls.add("master") }
        ).run()

        assertEquals(listOf("register", "initialize", "periodic", "master"), registry.calls)
    }

    /**
     * The Nightscout role change moves the upload marks. It must be done before any plugin starts,
     * or the first upload could already be on its way with the old marks.
     */
    @Test
    fun `the step before the plugins runs after register and before initialize`() {
        val registry = RecordingRegistry()
        IosAppStartup(
            SilentLogger, registry, mapOf(1 to NamedPlugin("a")),
            startPeriodicWork = {},
            beforePluginsStart = { registry.calls.add("before") }
        ).run()

        assertEquals(listOf("register", "before", "initialize"), registry.calls)
    }

    /** The version record, the start note and `EventAppInitialized` come last, as in `MainApp.doInit`. */
    @Test
    fun `the step after start runs after the master services`() {
        val registry = RecordingRegistry()
        IosAppStartup(
            SilentLogger, registry, mapOf(1 to NamedPlugin("a")),
            startPeriodicWork = {},
            startMasterServices = { registry.calls.add("master") },
            afterStart = { registry.calls.add("after") }
        ).run()

        assertEquals(listOf("register", "initialize", "master", "after"), registry.calls)
    }

    /** A failing last step is logged; it must not take the app down. */
    @Test
    fun `a failing step after start does not throw`() {
        val registry = RecordingRegistry()
        IosAppStartup(
            SilentLogger, registry, emptyMap(),
            startPeriodicWork = {},
            afterStart = { throw IllegalStateException("database not ready") }
        ).run()

        assertEquals(listOf("register", "initialize"), registry.calls)
    }

    /**
     * The master services wait for the plugins to finish starting, as `MainApp` waits on Android,
     * and `run()` itself does not wait - it is called on the main thread.
     */
    @Test
    fun `master services wait for the plugin start jobs`() = runTest {
        val pluginStart = Job()
        val registry = object : PluginRegistry {
            val calls = mutableListOf<String>()
            override fun register(plugins: List<PluginBase>) {
                calls.add("register")
            }

            override fun initializeConfig(): List<Job> {
                calls.add("initialize")
                return listOf(pluginStart)
            }
        }
        IosAppStartup(
            SilentLogger, registry, emptyMap(),
            startPeriodicWork = {},
            startMasterServices = { registry.calls.add("master") },
            startupScope = this
        ).run()

        // run() has returned, and the services have not started yet.
        runCurrent()
        assertEquals(listOf("register", "initialize"), registry.calls)

        pluginStart.complete()
        runCurrent()
        assertEquals(listOf("register", "initialize", "master"), registry.calls)
    }
}
