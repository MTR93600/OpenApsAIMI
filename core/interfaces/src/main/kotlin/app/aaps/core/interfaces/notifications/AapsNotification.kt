package app.aaps.core.interfaces.notifications

import androidx.annotation.RawRes

data class AapsNotification(
    val id: NotificationId,
    val instanceKey: Int,
    val text: String,
    val level: NotificationLevel,
    val date: Long = System.currentTimeMillis(),
    val validTo: Long = 0L,
    @RawRes val soundRes: Int? = null,
    val actions: List<NotificationAction> = emptyList(),
    val validityCheck: (() -> Boolean)? = null,
    /**
     * [android.os.SystemClock.elapsedRealtime] at which an accompanying bypass-DND channel one-shot
     * of the same [soundRes] was posted (DND-override path), else 0. Passed to
     * [AlarmSoundPlayer.play] so the ramping MediaPlayer loop defers past the channel one-shot and
     * they don't overlap. 0 → no accompanying channel sound (play immediately).
     */
    val postedAtElapsedRealtime: Long = 0L
)
