package app.aaps.ui.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.RawRes
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationHolder
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/*
    This code replaces  following
    val alarm = Intent(context, AlarmSoundService::class.java)
    alarm.putExtra("soundId", n.soundId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(alarm) else context.startService(alarm)

    it fails randomly with error
    Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{e317f7e u0 info.nightscout.nsclient/info.nightscout.androidaps.services.AlarmSoundService}

 */
@Singleton
class AlarmSoundServiceHelper @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val notificationHolder: Lazy<NotificationHolder>,
    private val context: Context
) {

    fun startAlarm(@RawRes sound: Int, reason: String) {
        aapsLogger.debug(LTag.CORE, "Starting alarm from $reason")
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                // The binder of the service that returns the instance that is created.
                val binder: AlarmSoundService.LocalBinder = service as AlarmSoundService.LocalBinder

                val alarmSoundService: AlarmSoundService = binder.getService()

                try {
                    context.startForegroundService(getServiceIntent(context, sound))
                } catch (e: Exception) {
                    // CHANGED: Android 14+ Protection
                    // If we are bound but the app is in background, startForegroundService might still throw
                    // ForegroundServiceStartNotAllowedException on API 34+.
                    aapsLogger.error(LTag.CORE, "Failed to promote AlarmSoundService to FgService inside connection: ${e.message}")
                    // We continue, as we are bound, but we know the service might be demoted or killed if restricted.
                }

                // This is the key: Without waiting Android Framework to call this method
                // inside Service.onCreate(), immediately call here to post the notification.
                alarmSoundService.startForeground(notificationHolder.get().notificationID, notificationHolder.get().notification)

                // Release the connection to prevent leaks.
                context.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }

        try {
            context.bindService(getServiceIntent(context, sound), connection, Context.BIND_AUTO_CREATE)
        } catch (ignored: RuntimeException) {
            // This is probably a broadcast receiver context even though we are calling getApplicationContext().
            // Just call startForegroundService instead since we cannot bind a service to a
            // broadcast receiver context. The service also have to call startForeground in
            // this case.
            try {
                context.startForegroundService(getServiceIntent(context, sound))
            } catch (e: Exception) {
                // CHANGED: Android 14+ Background Start Restriction fix
                // If app is in background, startForegroundService() throws ForegroundServiceStartNotAllowedException
                // We catch it to prevent crash. The visual notification will still appear via NotificationPlugin.
                aapsLogger.error(LTag.CORE, "Failed to start AlarmSoundService (Background restriction?): ${e.message}")
            }
        }
    }

    fun stopAlarm(reason: String) {
        aapsLogger.debug(LTag.CORE, "Stopping alarm from $reason")
        val alarm = Intent(context, AlarmSoundService::class.java)
        context.stopService(alarm)
    }

    private fun getServiceIntent(context: Context, @RawRes sound: Int): Intent {
        val alarm = Intent(context, AlarmSoundService::class.java)
        alarm.putExtra(AlarmSoundService.SOUND_ID, sound)
        return alarm
    }
}