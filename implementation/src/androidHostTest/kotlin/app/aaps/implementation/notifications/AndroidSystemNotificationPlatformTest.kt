package app.aaps.implementation.notifications

import android.app.NotificationManager as AndroidNotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmAction
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.AlarmSoundPlayer
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.androidNotification.AlarmNotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The Android half of the notification split: which of the three outcomes a notification gets.
 *
 * This is the logic that used to sit inside `NotificationManagerImpl` and could not be reached from
 * a test, because that class also owned the registry. It matters enough to pin: getting it wrong
 * either alerts the user twice for one alarm, or silently drops a notification they asked to see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSystemNotificationPlatformTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences: Preferences = mock()
    private val alarmNotificationManager: AlarmNotificationManager = mock()
    private val alarmSoundPlayer: AlarmSoundPlayer = mock()
    private val notificationHolder: NotificationHolder = mock()
    private val iconsProvider: IconsProvider = mock()

    private lateinit var sut: AndroidSystemNotificationPlatform

    @Before
    fun setUp() {
        whenever(iconsProvider.getIcon()).thenReturn(android.R.drawable.ic_dialog_alert)
        whenever(iconsProvider.getNotificationIcon()).thenReturn(android.R.drawable.ic_dialog_alert)
        sut = AndroidSystemNotificationPlatform(
            aapsLogger = mock<AAPSLogger>(),
            context = context,
            preferences = preferences,
            iconsProvider = iconsProvider,
            notificationHolder = { notificationHolder },
            alarmNotificationManager = { alarmNotificationManager },
            alarmSoundPlayer = { alarmSoundPlayer }
        )
    }

    private fun notification(
        level: NotificationLevel,
        sound: AlarmSound? = null,
        actions: List<NotificationAction> = emptyList(),
        id: NotificationId = NotificationId.NEW_VERSION_DETECTED
    ) = AapsNotification(
        id = id,
        instanceKey = 42,
        text = "text",
        level = level,
        sound = sound,
        actions = actions
    )

    @Test
    fun `an urgent alarm carrying a sound is posted silently`() {
        // Override DND off: the ramping audio belongs to AlarmSoundPlayer, so the tray entry must not
        // make noise of its own. It also must not consult the visual-notification preference: this
        // path is not the optional visual one.
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(false)

        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.URGENT_ALARM), "Urgent")

        verify(alarmNotificationManager).postSilentAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), urgent = eq(true), alarmAction = eq(null)
        )
        verify(preferences, never()).get(BooleanKey.AlertUrgentAsAndroidNotification)
        verify(alarmNotificationManager, never()).postSoundAlarmNotification(any(), any(), any(), any(), anyOrNull(), any())
    }

    @Test
    fun `the low glucose alarm carries the hypo treated button`() {
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(false)

        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.ALARM, id = NotificationId.BG_HYPO), "Urgent")

        verify(alarmNotificationManager).postSilentAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), urgent = eq(true), alarmAction = eq(AlarmAction.HYPO_TREATED)
        )
    }

    /** Override DND on, no ramp and no melody: the bypass channel plays the clip, and the player waits for it. */
    @Test
    fun `with the dnd override the channel rings and the player starts after the clip`() {
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(true)
        whenever(alarmNotificationManager.playerOwnsSound(any())).thenReturn(false)
        whenever(alarmNotificationManager.dndBlocksAlarms()).thenReturn(false)

        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.ALARM, id = NotificationId.BG_HYPO), "Urgent")
        sut.setAudibleAlarm(42, AlarmSound.ALARM, AlarmSoundCategory.LOW_GLUCOSE)

        verify(alarmNotificationManager).postSoundAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), sound = eq(AlarmSound.ALARM),
            alarmAction = eq(AlarmAction.HYPO_TREATED), rampMode = eq(false)
        )
        verify(alarmNotificationManager, never()).postSilentAlarmNotification(any(), any(), any(), any(), anyOrNull())
        // A clip start time, not 0: the player must not play over the channel clip.
        verify(alarmSoundPlayer).play(eq(AlarmSound.ALARM), eq(AlarmSoundPlayer.OWNER_INTERNAL), argThat<Long> { this > 0L }, eq(AlarmSoundCategory.LOW_GLUCOSE))
    }

    /** Override DND on and the player owns the sound (ramp or melody): the bypass channel only vibrates. */
    @Test
    fun `with the dnd override and a ramp the channel only vibrates and the player starts at once`() {
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(true)
        whenever(alarmNotificationManager.playerOwnsSound(any())).thenReturn(true)

        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.ALARM), "Urgent")
        sut.setAudibleAlarm(42, AlarmSound.ALARM)

        verify(alarmNotificationManager).postSoundAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), sound = eq(AlarmSound.ALARM),
            alarmAction = eq(null), rampMode = eq(true)
        )
        verify(alarmSoundPlayer).play(AlarmSound.ALARM, AlarmSoundPlayer.OWNER_INTERNAL)
    }

    /** The same alarm posted again while the player already plays it: no second sound on top. */
    @Test
    fun `a repost while the player sounds does not ring the channel again`() {
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(true)
        whenever(alarmNotificationManager.playerOwnsSound(any())).thenReturn(false)
        whenever(alarmNotificationManager.dndBlocksAlarms()).thenReturn(false)
        whenever(alarmSoundPlayer.isAudibleFor(AlarmSoundPlayer.OWNER_INTERNAL)).thenReturn(true)

        sut.setAudibleAlarm(42, AlarmSound.ALARM)
        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.ALARM), "Urgent")

        verify(alarmNotificationManager).postSoundAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), sound = eq(AlarmSound.ALARM),
            alarmAction = eq(null), rampMode = eq(true)
        )
    }

    /** A second alarm while the player loops the first: the player moves over, the channel adds no clip. */
    @Test
    fun `a new alarm while another one sounds does not add a channel clip`() {
        whenever(preferences.get(BooleanKey.AlertOverrideDoNotDisturb)).thenReturn(true)
        whenever(alarmNotificationManager.playerOwnsSound(any())).thenReturn(false)
        whenever(alarmNotificationManager.dndBlocksAlarms()).thenReturn(false)
        whenever(alarmSoundPlayer.isAudibleFor(AlarmSoundPlayer.OWNER_INTERNAL)).thenReturn(true)

        sut.setAudibleAlarm(7, AlarmSound.URGENT_ALARM)
        sut.show(notification(NotificationLevel.URGENT, sound = AlarmSound.ALARM), "Urgent")
        sut.setAudibleAlarm(42, AlarmSound.ALARM)

        verify(alarmNotificationManager).postSoundAlarmNotification(
            notificationKey = eq(42), title = eq("Urgent"), body = eq("text"), sound = eq(AlarmSound.ALARM),
            alarmAction = eq(null), rampMode = eq(true)
        )
        // Starts at once (0): there is no clip to wait for.
        verify(alarmSoundPlayer).play(AlarmSound.ALARM, AlarmSoundPlayer.OWNER_INTERNAL)
    }

    @Test
    fun `without the preference nothing is shown at all`() {
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)).thenReturn(false)

        sut.show(notification(NotificationLevel.NORMAL), "Info")

        verify(alarmNotificationManager, never()).postSilentAlarmNotification(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `a notification carrying actions is not shown in the tray`() {
        // Actions are answered in the app, so a tray copy would be a dead end.
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)).thenReturn(true)

        sut.show(notification(NotificationLevel.NORMAL, actions = listOf(mock())), "Info")

        verify(alarmNotificationManager, never()).postSilentAlarmNotification(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `an urgent notification without a sound takes the ordinary path when asked for`() {
        // URGENT alone is not the alarm tier - only URGENT *with a sound* is. This one is the plain
        // visual notification, so it is gated on the preference like any other.
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)).thenReturn(true)

        sut.show(notification(NotificationLevel.URGENT), "Urgent")

        verify(alarmNotificationManager, never()).postSilentAlarmNotification(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `the audible alarm starts, holds and stops`() {
        sut.setAudibleAlarm(1, AlarmSound.ALARM)
        verify(alarmSoundPlayer).play(AlarmSound.ALARM, AlarmSoundPlayer.OWNER_INTERNAL)

        // Called again with the same key means "keep playing" - a second play() would restart the ramp.
        sut.setAudibleAlarm(1, AlarmSound.ALARM)
        verify(alarmSoundPlayer).play(AlarmSound.ALARM, AlarmSoundPlayer.OWNER_INTERNAL)

        // A different alarm takes over.
        sut.setAudibleAlarm(2, AlarmSound.URGENT_ALARM)
        verify(alarmSoundPlayer).play(AlarmSound.URGENT_ALARM, AlarmSoundPlayer.OWNER_INTERNAL)

        sut.setAudibleAlarm(null, null)
        verify(alarmSoundPlayer).stop(AlarmSoundPlayer.OWNER_INTERNAL)
    }

    @Test
    fun `silence is not repeated when nothing is sounding`() {
        sut.setAudibleAlarm(null, null)

        verify(alarmSoundPlayer, never()).stop(any())
    }

    @Test
    fun `mute all clears the alarms but leaves the ongoing notification alone`() {
        // cancelAll() deliberately does not call NotificationManager.cancelAll(): that would also take
        // down the foreground service notification carrying the loop status.
        sut.cancelAll()

        verify(alarmNotificationManager).cancelAlarm()
    }

    @Test
    fun `cancelling one notification also clears any sound it owned`() {
        sut.cancel(7)

        verify(alarmNotificationManager).cancelSoundAlarm(7)
    }

    @Test
    fun `a dismissal from the tray is reported with its instance key`() {
        var dismissed: Int? = null
        sut.onDismissed { dismissed = it }

        // Showing is what registers the receiver, so do that first, then post the delete broadcast.
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)).thenReturn(true)
        sut.show(notification(NotificationLevel.NORMAL), "Info")
        context.sendBroadcast(
            Intent(NotificationManager.DISMISS_ACTION)
                .putExtra("instanceKey", 42)
        )
        ShadowLooper.idleMainLooper()

        assertThat(dismissed).isEqualTo(42)
    }

    /**
     * The test above sends the broadcast itself. The real delete intent had no package, and from Android 14 an
     * implicit broadcast from a PendingIntent never reaches a receiver registered as not exported: a swipe in the
     * tray, or on the watch, took the notification away, but the registry kept it, so the overview still showed it.
     * This takes the delete intent the notification really carries.
     */
    @Test
    fun `the delete intent names this package, so the dismiss receiver gets it`() {
        var dismissed: Int? = null
        sut.onDismissed { dismissed = it }
        whenever(preferences.get(BooleanKey.AlertUrgentAsAndroidNotification)).thenReturn(true)
        sut.show(notification(NotificationLevel.NORMAL), "Info")

        val posted = shadowOf(context.getSystemService(AndroidNotificationManager::class.java)).allNotifications.single()
        val deleteIntent = shadowOf(posted.deleteIntent).savedIntent

        assertThat(deleteIntent.`package`).isEqualTo(context.packageName)
        context.sendBroadcast(deleteIntent)
        ShadowLooper.idleMainLooper()
        assertThat(dismissed).isEqualTo(42)
    }
}
