package app.aaps.ios.shell.di

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.SystemNotificationPlatform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The system notifications of a test graph ([DashQueueTestGraph]): each one shown is kept, nothing
 * reaches `UNUserNotificationCenter` (a test binary has no app bundle, and iOS stops it there).
 * The shared notification manager above it is the real one.
 */
@SingleIn(AppScope::class)
@Inject
class RecordingSystemNotifications : SystemNotificationPlatform {

    private val lock = AapsLock()
    private val all = mutableListOf<AapsNotification>()

    /** Every notification shown, in order. */
    val shown: List<AapsNotification> get() = lock.withLock { all.toList() }

    override fun show(notification: AapsNotification, title: String) {
        lock.withLock { all += notification }
    }

    override fun cancel(instanceKey: Int) = Unit

    override fun cancelAll() = Unit

    override fun setAudibleAlarm(instanceKey: Int?, sound: AlarmSound?) = Unit

    override fun onDismissed(callback: (instanceKey: Int) -> Unit) = Unit
}
