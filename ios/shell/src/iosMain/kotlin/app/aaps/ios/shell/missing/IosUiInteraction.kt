package app.aaps.ios.shell.missing

import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.extensions.asAnnouncement
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * The alarm entry point on iOS.
 *
 * [runAlarm] is an alarm the person has to hear: a bolus that failed to reach the pump, for
 * example. Like Android it first stores the alarm as an announcement, so it is in the history with
 * its real time even if nobody opens it. Then it posts an [NotificationLevel.URGENT] notification
 * through the shared [NotificationManager] with the alarm sound.
 *
 * Android shows a full screen activity instead, whose sound belongs to `OWNER_FULLSCREEN`. iOS has
 * no full screen alarm, and the iOS `cancelAll()` cannot stop that owner (see
 * `_docs/ios_blockers.md`), so a sound started that way could not be silenced. Going through the
 * registry gives the sound to `OWNER_INTERNAL` instead, and [stopAlarm] - `muteAllAlarms` - stops it
 * with the same code that stops every other alarm.
 *
 * [mainActivity] and [errorHelperActivity] have no iOS meaning at all. They exist so Android code
 * can build an `Intent`, and every reader of them is androidMain - checked, not assumed. They
 * answer with this class so the type is satisfied; reading either on iOS is a bug in the caller,
 * not a value to rely on.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosUiInteraction(
    private val aapsLogger: AAPSLogger,
    // Providers: the registry builds the notification platform, which would be a cycle back to here,
    // and the database is only needed when an alarm fires.
    private val notificationManager: () -> NotificationManager,
    private val persistenceLayer: () -> PersistenceLayer,
    private val config: Config,
    private val rh: TextResolver,
    private val appScope: CoroutineScope
) : UiInteraction {

    override val mainActivity: KClass<*> get() = IosUiInteraction::class
    override val errorHelperActivity: KClass<*> get() = IosUiInteraction::class

    override fun runAlarm(status: String, title: String, sound: AlarmSound?) {
        if (config.APS)
            appScope.launch {
                persistenceLayer().insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = TE.asAnnouncement(status),
                    action = Action.CAREPORTAL,
                    source = Sources.Aaps,
                    note = status,
                    listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                )
            }
        aapsLogger.debug(LTag.CORE, "runAlarm: $title - $status (sound=$sound)")
        notificationManager().post(
            id = NotificationId.TOAST_ALARM,
            text = rh.gs(InterfacesStrings.confirmation_line, title, status),
            level = NotificationLevel.URGENT,
            validMinutes = 0,
            // The same default as Android's AlarmNotificationManager when no sound is given.
            sound = sound ?: AlarmSound.ERROR
        )
    }

    /** Clears the registry's alarms, stops the audio it owns, and cancels the notifications. */
    override fun stopAlarm(reason: String) {
        notificationManager().muteAllAlarms()
    }
}
