package app.aaps.implementation.plugin

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.Sensitivity
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.constraints.Safety
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PermissionProvider
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which permissions an iOS master shows, and which it marks as missing.
 *
 * Bluetooth and notifications are always listed, because a master cannot read glucose, dose or alarm
 * without them. A permission that iOS cannot check must never be listed: as missing it would send the
 * user looking for a setting that does not exist, as granted it would claim a check nothing
 * performed. It has to reach the log instead.
 */
class IosPluginPermissionsTest {

    private class RecordingLogger : AAPSLogger {

        val errors = mutableListOf<String>()

        override fun error(tag: LTag, message: String) {
            errors.add(message)
        }

        override fun debug(message: String) {}
        override fun debug(enable: Boolean, tag: LTag, message: String) {}
        override fun debug(tag: LTag, message: String) {}
        override fun debug(tag: LTag, accessor: () -> String) {}
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun warn(tag: LTag, message: String) {}
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
        override fun info(tag: LTag, message: String) {}
        override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
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

    private object SilentText : TextResolver {

        override fun gs(ref: TextRef): String = ""
        override fun gs(ref: TextRef, vararg args: Any?): String = ""
        override fun gsNotLocalised(ref: TextRef): String = ""
        override fun shortTextMode(): Boolean = false
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

    private class FakePlugin(
        logger: AAPSLogger,
        private val enabled: Boolean,
        private val groups: List<PermissionGroup>
    ) : PluginBase(PluginDescription().also { it.mainType = PluginType.GENERAL }, logger, SilentText, NoNotifications) {

        override fun isEnabled(): Boolean = enabled
        override fun requiredPermissions(): List<PermissionGroup> = groups
    }

    /** Only `getPluginsList` is ever called. The rest would be a bug if it were. */
    private class FakeActivePlugin(private val plugins: List<PluginBase>) : ActivePlugin {

        override fun getPluginsList(): ArrayList<PluginBase> = ArrayList(plugins)

        override val activeBgSource: BgSource get() = unused()
        override val activeAPS: APS? get() = unused()
        override val activePump: PumpWithConcentration get() = unused()
        override val activePumpInternal: Pump get() = unused()
        override val activeSensitivity: Sensitivity get() = unused()
        override val activeSafety: Safety get() = unused()
        override val activeIobCobCalculator: IobCobCalculator get() = unused()
        override val activeObjectives: Objectives? get() = unused()
        override val activeSmoothing: Smoothing get() = unused()
        override val activeCalibration: Calibration get() = unused()
        override val firstActiveSync: Sync? get() = unused()
        override val activeSyncs: ArrayList<Sync> get() = unused()
        override fun getSpecificPluginsVisibleInList(type: PluginType): ArrayList<PluginBase> = unused()
        override fun getSpecificPluginsListByInterface(interfaceClass: KClass<*>): ArrayList<PluginBase> = unused()
        override fun verifySelectionInCategories(): List<Job> = unused()
        override fun getSpecificPluginsList(type: PluginType): ArrayList<PluginBase> = unused()
        override fun beforeImport() = unused()
        override fun afterImport() = unused()

        private fun unused(): Nothing = throw AssertionError("IosPluginPermissions must only read the plugin list")
    }

    private val logger = RecordingLogger()

    /** Every state is GRANTED unless a test sets it. */
    private class FakeStatus : IosPermissionStatus {

        val states = mutableMapOf<String, IosPermissionState>()
        val asked = mutableListOf<String>()

        override fun state(permission: String): IosPermissionState = states[permission] ?: IosPermissionState.GRANTED
        override fun refresh(onDone: () -> Unit) = onDone()
        override fun ask(permission: String, onDone: () -> Unit) {
            asked.add(permission)
            onDone()
        }
    }

    private val status = FakeStatus()

    private fun group(vararg permissions: String) = PermissionGroup(
        permissions = permissions.toList(),
        rationaleTitle = TextRef.Literal("why"),
        rationaleDescription = TextRef.Literal("because")
    )

    private fun sut(
        plugins: List<PluginBase> = emptyList(),
        providers: Set<PermissionProvider> = emptySet()
    ) = IosPluginPermissions(logger, FakeActivePlugin(plugins), status) { providers }

    private fun List<PermissionGroup>.ids() = flatMap { it.permissions }

    @Test
    fun `bluetooth and notifications are always listed`() {
        assertEquals(listOf(IosPluginPermissions.BLUETOOTH, IosPluginPermissions.NOTIFICATIONS), sut().collectAllPermissions().ids())
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `nothing is missing when everything is granted`() {
        assertTrue(sut().collectMissingPermissions().isEmpty())
    }

    /** The point of the class: a refused Bluetooth means no pump and no sensor, and must show. */
    @Test
    fun `a refused permission is missing`() {
        status.states[IosPluginPermissions.BLUETOOTH] = IosPermissionState.DENIED

        assertEquals(listOf(IosPluginPermissions.BLUETOOTH), sut().collectMissingPermissions().ids())
    }

    @Test
    fun `a permission never asked for is missing`() {
        status.states[IosPluginPermissions.NOTIFICATIONS] = IosPermissionState.NOT_ASKED

        assertEquals(listOf(IosPluginPermissions.NOTIFICATIONS), sut().collectMissingPermissions().ids())
    }

    /** Before the first read, the sheet must not open on a guess. */
    @Test
    fun `a state not read yet is not missing`() {
        status.states[IosPluginPermissions.NOTIFICATIONS] = IosPermissionState.UNKNOWN

        assertTrue(sut().collectMissingPermissions().isEmpty())
    }

    @Test
    fun `location is listed only when something declares it`() {
        assertFalse(sut().collectAllPermissions().ids().contains(IosPluginPermissions.LOCATION))

        val provider = object : PermissionProvider {
            override fun requiredPermissions(): List<PermissionGroup> = listOf(group(IosPluginPermissions.LOCATION))
        }
        status.states[IosPluginPermissions.LOCATION] = IosPermissionState.DENIED

        assertEquals(listOf(IosPluginPermissions.LOCATION), sut(providers = setOf(provider)).collectMissingPermissions().ids())
    }

    /** The guard. A permission iOS cannot check must not pass quietly, and must not be listed. */
    @Test
    fun `a plugin that declares an unknown permission is reported as a problem`() {
        val sut = sut(plugins = listOf(FakePlugin(logger, enabled = true, groups = listOf(group("some.permission")))))

        assertFalse(sut.collectAllPermissions().ids().contains("some.permission"))
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors.single().contains("some.permission"))
    }

    @Test
    fun `a disabled plugin is not asked`() {
        val sut = sut(plugins = listOf(FakePlugin(logger, enabled = false, groups = listOf(group("some.permission")))))

        sut.collectAllPermissions()
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `a permission provider is asked as well as the plugins`() {
        val provider = object : PermissionProvider {
            override fun requiredPermissions(): List<PermissionGroup> = listOf(group("provider.permission"))
        }
        sut(providers = setOf(provider)).collectMissingPermissions()

        assertTrue(logger.errors.single().contains("provider.permission"))
    }

    /** A plugin that also asks for Bluetooth must not make it appear twice. */
    @Test
    fun `bluetooth declared by a plugin is listed once`() {
        val sut = sut(plugins = listOf(FakePlugin(logger, enabled = true, groups = listOf(group(IosPluginPermissions.BLUETOOTH)))))

        assertEquals(1, sut.collectAllPermissions().ids().count { it == IosPluginPermissions.BLUETOOTH })
    }

    @Test
    fun `a request asks for the permission of the group`() {
        var done = false
        sut().request(group(IosPluginPermissions.BLUETOOTH)) { done = true }

        assertEquals(listOf(IosPluginPermissions.BLUETOOTH), status.asked)
        assertTrue(done)
    }
}
