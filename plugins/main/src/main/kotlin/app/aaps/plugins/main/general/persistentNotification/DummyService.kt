package app.aaps.plugins.main.general.persistentNotification

import android.app.Notification
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

/**
 * Keeps AndroidAPS in foreground state, so it won't be terminated by Android nor get restricted by the background execution limits
 */
class DummyService : DaggerService() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var notificationHolder: NotificationHolder

    private val disposable = CompositeDisposable()

    inner class LocalBinder : Binder() {

        fun getService(): DummyService = this@DummyService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundSafe()
        disposable.add(
            rxBus
                .toObservable(EventAppExit::class.java)
                .observeOn(aapsSchedulers.io)
                .subscribe({
                               aapsLogger.debug(LTag.CORE, "EventAppExit received")
                               stopSelf()
                           }, fabricPrivacy::logException)
        )
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.CORE, "onDestroy")
        disposable.clear()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundSafe()
        return START_STICKY
    }

    private fun startForegroundSafe() {
        try {
            aapsLogger.debug("Starting DummyService with ID ${notificationHolder.notificationID}")
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // On Android 14+, we MUST explicitly declare the type if strict rules apply
                // Ideally we check permissions here, but catching SecurityException is robust enough
                // and handles cases where permissions are revoked at runtime.
                // We use the 'location' type as checking permissions and passing 0 would also crash if manifest implies location.
                startForeground(
                    notificationHolder.notificationID, 
                    notificationHolder.notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(notificationHolder.notificationID, notificationHolder.notification)
            }
        } catch (se: SecurityException) {
            aapsLogger.error(LTag.CORE, "❌ CRITICAL: Failed to start Foreground Service due to missing permissions (Android 14+). Service will stop.", se)
            // Do NOT retry. Retrying causes the crash loop.
            // Just stop this service to let the App UI survive and ask for permissions.
            stopSelf()
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Error starting FGS, retrying with fallback", e)
            try {
                startForeground(4711, Notification())
            } catch (e2: Exception) {
                aapsLogger.error(LTag.CORE, "Fallback FGS failed too. Giving up.", e2)
                stopSelf()
            }
        }
    }
}
