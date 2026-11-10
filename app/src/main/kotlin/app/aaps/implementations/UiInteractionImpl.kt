package app.aaps.implementations

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.aaps.ComposeMainActivity
import app.aaps.compose.navigation.AppRoute
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.ui.compose.ScreenMode
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmIntent
import app.aaps.core.interfaces.notifications.AlarmSoundPlayer
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.implementation.androidNotification.AlarmNotificationManager
import app.aaps.ui.activities.ErrorActivity
import app.aaps.ui.dialogs.AlertDialogs
import dagger.Reusable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class UiInteractionImpl @Inject constructor(
    private val context: Context,
    rxBus: RxBus,
    private val preferences: Preferences,
    private val alarmNotificationManager: AlarmNotificationManager,
    private val alarmSoundPlayer: AlarmSoundPlayer,
    // Provider breaks a Dagger cycle: NotificationManagerImpl injects NotificationHolder, which
    // injects this UiInteraction. notificationManager is only needed lazily in stopAlarm().
    private val notificationManager: Provider<NotificationManager>,
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private val config: Config,
    @ApplicationScope private val appScope: CoroutineScope
) : UiInteraction {

    private val alertDialogs: AlertDialogs = AlertDialogs(preferences, rxBus)

    override val mainActivity: Class<*> = ComposeMainActivity::class.java
    override val errorHelperActivity: Class<*> = ErrorActivity::class.java

    override val unitsEntries = arrayOf<CharSequence>("mg/dL", "mmol/L")
    override val unitsValues = arrayOf<CharSequence>("mg/dl", "mmol")

    override fun runAlarm(status: String, title: String, @RawRes soundId: Int) {
        // Persist the error as an announcement at fire time — gated by the NS-announcement
        // preference + APS build. Done here (not in ErrorActivity) so the record is written for
        // every alarm with the true trigger time, regardless of whether/how it is later
        // acknowledged (phone activity, Wear mute, OS-trimmed notification, or never opened).
        if (config.APS)
            appScope.launch {
                persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = TE.asAnnouncement(status),
                    action = Action.CAREPORTAL,
                    source = Sources.Aaps,
                    note = status,
                    listValues = listOf(ValueWithUnit.TEType(TE.Type.ANNOUNCEMENT))
                )
            }

        // ProcessLifecycleOwner.currentState requires main-thread access (officially @MainThread).
        // BLE callbacks, RxJava workers, and coroutine non-main dispatchers all call runAlarm
        // from non-main threads. From those contexts we skip the foreground-direct optimization
        // entirely and use the FSI path, which is safe from any thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            aapsLogger.debug(LTag.CORE, "runAlarm (off-main → FSI): $title - $status (sound=$soundId)")
            alarmNotificationManager.postFullScreenAlarm(status = status, title = title, soundId = soundId)
            return
        }

        if (isAppInForeground()) {
            aapsLogger.debug(LTag.CORE, "runAlarm (foreground direct): $title - $status (sound=$soundId)")
            val intent = Intent(context, errorHelperActivity).apply {
                putExtra(AlarmIntent.EXTRA_SOUND_ID, soundId)
                putExtra(AlarmIntent.EXTRA_STATUS, status)
                putExtra(AlarmIntent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                context.startActivity(intent)
            } catch (ex: Exception) {
                aapsLogger.error(LTag.CORE, "runAlarm: direct startActivity failed, falling back to FSI", ex)
                postFsiFallback(status, title, soundId)
            }
        } else {
            aapsLogger.debug(LTag.CORE, "runAlarm (background via FSI): $title - $status (sound=$soundId)")
            alarmNotificationManager.postFullScreenAlarm(status = status, title = title, soundId = soundId)
        }
    }

    override fun postNotificationSoundAlarm(notificationKey: Int, @RawRes soundId: Int, title: String, body: String, urgent: Boolean) {
        alarmNotificationManager.postSilentAlarmNotification(
            notificationKey = notificationKey,
            title = title,
            body = body,
            urgent = urgent
        )
        if (soundId != 0) {
            alarmSoundPlayer.play(soundId, AlarmSoundPlayer.OWNER_INTERNAL)
        }
    }

    override fun cancelNotificationSoundAlarm(notificationKey: Int) {
        alarmNotificationManager.cancelSoundAlarm(notificationKey)
        alarmSoundPlayer.stop(AlarmSoundPlayer.OWNER_INTERNAL)
    }

    override fun stopAlarm(reason: String) {
        aapsLogger.debug(LTag.CORE, "stopAlarm: $reason")
        // Route through the registry owner so all audible alarms are actually silenced: clears the
        // internal AlarmSoundPlayer (Wear snooze used to only cancel the system notification, leaving
        // the ramping audio playing), stops the full-screen audio, and cancels the notifications.
        notificationManager.get().muteAllAlarms()
    }

    override fun showOkDialog(context: Context, title: String, message: String, onFinish: (() -> Unit)?) {
        alertDialogs.showOkDialog(context, title, message, onFinish)
    }

    override fun showOkDialog(context: Context, title: Int, message: Int, onFinish: (() -> Unit)?) {
        alertDialogs.showOkDialog(context, title, message, onFinish)
    }

    override fun showOkCancelDialog(context: Context, title: Int, message: Int, ok: (() -> Unit)?, cancel: (() -> Unit)?, icon: Int?) {
        alertDialogs.showOkCancelDialog(context, title, message, ok, cancel, icon)
    }

    override fun showOkCancelDialog(context: Context, title: String, message: String, ok: (() -> Unit)?, cancel: (() -> Unit)?, icon: Int?) {
        alertDialogs.showOkCancelDialog(context, title, message, ok, cancel, icon)
    }

    override fun showOkCancelDialog(context: Context, title: String, message: String, secondMessage: String, ok: (() -> Unit)?, cancel: (() -> Unit)?, icon: Int?) {
        alertDialogs.showOkCancelDialog(context, title, message, secondMessage, ok, cancel, icon)
    }

    override fun showYesNoCancel(context: Context, title: Int, message: Int, yes: (() -> Unit)?, no: (() -> Unit)?) {
        alertDialogs.showYesNoCancel(context, title, message, yes, no)
    }

    override fun showYesNoCancel(context: Context, title: String, message: String, yes: (() -> Unit)?, no: (() -> Unit)?) {
        alertDialogs.showYesNoCancel(context, title, message, yes, no)
    }

    override fun showError(context: Context, title: String, message: String, positiveButton: Int?, ok: (() -> Unit)?, cancel: (() -> Unit)?) {
        alertDialogs.showError(context, title, message, positiveButton, ok, cancel)
    }

    override fun openRunningModeScreen(activity: FragmentActivity) {
        activity.startActivity(Intent(activity, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, AppRoute.RunningMode.route)
        })
    }

    override fun openInsulinScreen(activity: FragmentActivity) {
        activity.startActivity(Intent(activity, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, AppRoute.InsulinDialog.route)
        })
    }

    override fun openTempTargetManagementScreen(activity: FragmentActivity) {
        activity.startActivity(Intent(activity, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, AppRoute.TempTargetManagement.createRoute(ScreenMode.EDIT))
        })
    }

    override fun openProfileManagementScreen(activity: FragmentActivity) {
        activity.startActivity(Intent(activity, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, AppRoute.Profile.createRoute(ScreenMode.EDIT))
        })
    }

    override fun openProfileActivationScreen(activity: FragmentActivity, profileIndex: Int) {
        activity.startActivity(Intent(activity, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, AppRoute.ProfileActivation.createRoute(profileIndex))
        })
    }

    override fun runPreferencesForPlugin(activity: FragmentActivity, pluginSimpleName: String) {
        openComposeMainAtRoute(activity, AppRoute.PluginPreferences.createRoute(pluginSimpleName))
    }

    override fun openComposeMainAtRoute(context: Context, navRoute: String) {
        context.startActivity(Intent(context, ComposeMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(ComposeMainActivity.EXTRA_NAVIGATE_ROUTE, navRoute)
        })
    }

    private fun postFsiFallback(status: String, title: String, @RawRes soundId: Int) {
        alarmNotificationManager.postFullScreenAlarm(status = status, title = title, soundId = soundId)
        runCatching {
            Toast.makeText(context, "ALARM: $title — $status", Toast.LENGTH_LONG).show()
        }.onFailure {
            aapsLogger.error(LTag.CORE, "runAlarm: Toast fallback also failed; alarm not user-visible", it)
        }
    }

    private fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
