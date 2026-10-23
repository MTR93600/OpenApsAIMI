package app.aaps.plugins.constraints.dstHelper

import app.aaps.core.data.model.RM
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.dst.DstHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.constraints.ConstraintsStrings
import app.aaps.plugins.constraints.dstHelper.keys.DstHelperLongKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Warns before a daylight saving change and suspends the loop just after one, for pumps that cannot
 * follow a clock change by themselves.
 *
 * Shared by every platform. A change is found by comparing the daylight saving part of the offset
 * now with the same part some hours away - exactly what Android compared with `Calendar.DST_OFFSET`.
 * That one value comes from each platform's own time zone database ([daylightSavingPartMs]): the
 * same Java call as before on Android and the desktop, `NSTimeZone` on iOS.
 */
@ContributesBinding(AppScope::class, binding = binding<DstHelper>())
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(850)
@SingleIn(AppScope::class)
@Inject
class DstHelperPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    private val activePlugin: ActivePlugin,
    notificationManager: NotificationManager,
    private val loop: Loop,
    private val profileFunction: ProfileFunction
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .enforce(EnforcedState.Enabled)
        .showInList { false }
        .pluginName(ConstraintsStrings.dst_plugin_name),
    ownPreferences = DstHelperLongKey.entries,
    aapsLogger, rh, preferences, notificationManager
), DstHelper {

    companion object {

        private const val DISABLE_TIME_FRAME_HOURS = -3
        private const val WARN_PRIOR_TIME_FRAME_HOURS = 12
    }

    //Return false if time to DST change happened in the last 3 hours.
    override fun dstCheck() {
        val pump = activePlugin.activePump
        if (pump.canHandleDST()) return
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val nowMs = now.toEpochMilliseconds()
        if (willBeDST(now, zone)) {
            val snoozedTo: Long = preferences.get(DstHelperLongKey.SnoozeDstIn24h)
            if (snoozedTo == 0L || nowMs > snoozedTo) {
                notificationManager.post(
                    NotificationId.DST_IN_24H,
                    ConstraintsStrings.dst_in_24h_warning,
                    actions = listOf(NotificationAction(CoreUiStrings.snooze) {
                        preferences.put(DstHelperLongKey.SnoozeDstIn24h, Clock.System.now().toEpochMilliseconds() + T.hours(24).msecs())
                    }))
            }
        }
        if (wasDST(now, zone)) {
            // dstCheck() is invoked from KeepAliveWorker on a background scope; the file
            // already uses runBlocking for profileFunction. Keeping the same pattern here.
            val mode = runBlocking { loop.runningMode() }
            if (!mode.pausesLoopExecution()) {
                val profile = runBlocking { profileFunction.getProfile() } ?: return
                runBlocking {
                    loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_DST, durationInMinutes = T.hours((-DISABLE_TIME_FRAME_HOURS).toLong()).mins().toInt(), action = Action.SUSPEND, source = Sources.Aaps, profile = profile)
                }
                val snoozedTo: Long = preferences.get(DstHelperLongKey.SnoozeLoopDisabled)
                if (snoozedTo == 0L || nowMs > snoozedTo) {
                    notificationManager.post(
                        NotificationId.DST_LOOP_DISABLED,
                        ConstraintsStrings.dst_loop_disabled_warning,
                        actions = listOf(NotificationAction(CoreUiStrings.snooze) {
                            preferences.put(DstHelperLongKey.SnoozeLoopDisabled, Clock.System.now().toEpochMilliseconds() + T.hours(24).msecs())
                        }))
                }
            } else {
                aapsLogger.debug(LTag.CONSTRAINTS, "Loop already suspended")
            }
        }
    }

    /** True when the clock changed within the last three hours. Same comparison as Android always made. */
    internal fun wasDST(now: Instant, zone: TimeZone): Boolean =
        dstPart(zone, now) != dstPart(zone, now + DISABLE_TIME_FRAME_HOURS.hours)

    /** True when the clock will change within the next twelve hours. */
    internal fun willBeDST(now: Instant, zone: TimeZone): Boolean =
        dstPart(zone, now) != dstPart(zone, now + WARN_PRIOR_TIME_FRAME_HOURS.hours)

    private fun dstPart(zone: TimeZone, at: Instant): Long = daylightSavingPartMs(zone.id, at.toEpochMilliseconds())
}
