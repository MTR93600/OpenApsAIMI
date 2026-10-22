package app.aaps.pump.medtrum.comm

import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Remembers what was posted and dismissed, with the level and the sound. */
class RecordingNotificationManager : NotificationManager {

    data class Posted(val id: NotificationId, val text: TextRef?, val level: NotificationLevel, val sound: AlarmSound?)

    val posted = mutableListOf<Posted>()
    val dismissed = mutableListOf<NotificationId>()

    override val notifications: StateFlow<List<AapsNotification>> = MutableStateFlow(emptyList())

    override fun cleanUp() = Unit

    override fun post(
        id: NotificationId, text: String, level: NotificationLevel, validMinutes: Int, sound: AlarmSound?,
        actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        posted += Posted(id, null, level, sound)
        return NotificationHandle(posted.size)
    }

    override fun post(
        id: NotificationId, text: String, level: NotificationLevel, date: Long, validTo: Long, sound: AlarmSound?,
        actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        posted += Posted(id, null, level, sound)
        return NotificationHandle(posted.size)
    }

    override fun post(
        id: NotificationId, textRef: TextRef, level: NotificationLevel, validMinutes: Int, date: Long, validTo: Long,
        sound: AlarmSound?, actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        posted += Posted(id, textRef, level, sound)
        return NotificationHandle(posted.size)
    }

    override fun dismiss(id: NotificationId) {
        dismissed += id
    }

    override fun dismiss(handle: NotificationHandle) = Unit

    override fun muteAllAlarms() = Unit
}
