package app.aaps.implementation.androidNotification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.RawRes
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmAction
import app.aaps.core.interfaces.notifications.AlarmIntent
import app.aaps.core.interfaces.notifications.AlarmSoundPlayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.R
import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.androidNotification.AlarmNotificationManager.Companion.CHANNEL_FULL_SCREEN_SILENT
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Builds and posts Android notifications for AAPS alarms. Replaces the old `AlarmSoundService` /
 * `AlarmSoundServiceHelper` foreground-service-based path.
 *
 * Two alarm modalities:
 *  - [postFullScreenAlarm]: posts an FSI notification on a sound-bearing channel; Android auto-launches
 *    ErrorActivity when the device is idle AND the FSI permission is held, otherwise shows a heads-up.
 *    The channel always carries the sound so the alarm is audible even when the activity can't launch
 *    (e.g. USE_FULL_SCREEN_INTENT denied — the default on Android 14+). When the activity does launch it
 *    takes over with looping/ramped audio, deferring its loop past the channel one-shot to avoid overlap.
 *  - [postSoundOnlyAlarm]: posts on a sound-bearing channel chosen by the
 *    [BooleanKey.AlertOverrideDoNotDisturb] preference. No activity, system plays channel sound.
 *
 * Channels are created eagerly when this @Singleton is first injected so that the first post
 * doesn't race channel creation.
 */
@Singleton
class AlarmNotificationManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val iconsProvider: IconsProvider,
    // Provider breaks a Dagger cycle: UiInteractionImpl injects this class, but we need
    // UiInteraction.errorHelperActivity for the FSI target. Provider defers resolution.
    private val uiInteractionProvider: Provider<UiInteraction>,
    private val alarmSoundPlayer: AlarmSoundPlayer,
    private val rh: ResourceHelper
) {

    companion object {

        const val GROUP_ID = "aaps_alarm_group"

        /**
         * Sound-suppressed channel used by [postSilentAlarmNotification], where audio is owned
         * externally by [app.aaps.core.interfaces.notifications.AlarmSoundPlayer]. Heads-up popup still
         * shows (IMPORTANCE_HIGH) and vibration still fires, but the channel itself plays no sound.
         * (No longer used by [postFullScreenAlarm], which always posts on a sound-bearing channel so
         * the alarm is audible even when its full-screen activity can't launch.)
         */
        const val CHANNEL_FULL_SCREEN_SILENT = "aaps_alarm_fullscreen_silent"

        /**
         * Bypass-DND channel with NO sound, used when "Gradually increase volume" is on: it vibrates
         * and shows the alarm through Do Not Disturb, and the ramping [AlarmSoundPlayer] owns the audio
         * from silence. See [AlarmChannelPolicy].
         */
        const val CHANNEL_RAMP_BYPASS = "aaps_alarm_ramp_bypass"

        /** Vibration of [CHANNEL_RAMP_BYPASS]: three pulses, inside the player's silent start. */
        private val RAMP_VIBRATION_PATTERN = longArrayOf(0, 600, 400, 600, 400, 600)

        /** Stable id for runAlarm notification (single active full-screen alarm at a time). */
        const val NOTIFICATION_ID_FULL_SCREEN = 4712

        /** Request code for the screen-wake broadcast + its AlarmClockInfo show intent. */
        private const val WAKE_REQUEST_CODE = 4713

        /** Request code for the notification Mute action PendingIntent. */
        private const val MUTE_REQUEST_CODE = 4714

        /** Request code for the per-alarm extra action PendingIntent (see [AlarmAction]). */
        private const val ALARM_ACTION_REQUEST_CODE = 4715

        /**
         * Delay before the screen-wake / activity-launch alarms fire. Small buffer so both
         * setAlarmClock alarms register before firing; a ~1.5s wait to light the screen is
         * negligible for a medical alarm.
         */
        private const val SCREEN_WAKE_DELAY_MS = 1_500L

        /**
         * Base offset for per-AAPS-notification sound alarm IDs. The system notification ID is
         * `SOUND_ID_OFFSET + notificationKey`, where notificationKey is the `AapsNotification`'s
         * instanceKey (the `NotificationId.ordinal`, 0..~80, for single notifications; 10000+ for
         * `allowMultiple` ones). The 100_000 offset keeps both ranges well clear of
         * NotificationHolder=4711 and FULL_SCREEN=4712.
         */
        const val SOUND_ID_OFFSET = 100_000

        private val SOUND_NAMES: Map<Int, String> = mapOf(
            app.aaps.core.ui.R.raw.alarm to "alarm",
            app.aaps.core.ui.R.raw.boluserror to "boluserror",
            app.aaps.core.ui.R.raw.error to "error",
            app.aaps.core.ui.R.raw.urgentalarm to "urgentalarm"
        )

        private val DISPLAY_NAMES: Map<Int, String> = mapOf(
            app.aaps.core.ui.R.raw.alarm to "Standard alarm",
            app.aaps.core.ui.R.raw.boluserror to "Bolus error",
            app.aaps.core.ui.R.raw.error to "General error",
            app.aaps.core.ui.R.raw.urgentalarm to "Urgent alarm"
        )
    }

    private val mgr: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Tracks active sound notification keys so [cancelAlarm] can clear all of them.
     * Per-AAPS-notification cancel goes through [cancelSoundAlarm] and only touches one id —
     * avoids the "dismissing notification A stops notification B's sound" bug that comes from
     * a shared singleton notification id.
     *
     * IMPORTANT: every read/write of this field — AND the paired `mgr.notify`/`mgr.cancel`
     * call — must run inside `synchronized(activeSoundKeys)`. The post and cancel paths can be
     * called concurrently from unrelated threads (post comes from `NotificationManagerImpl`
     * under its own monitor; cancel comes from `UiInteractionImpl.stopAlarm` which has no
     * outer lock). Without pairing the notify/add (and remove/cancel) in one critical section
     * a global cancel can iterate-and-clear in between, leaving the just-posted system
     * notification orphaned.
     *
     * Note on OS-driven dismissal: notifications are `setOngoing(true)` so the user can't
     * swipe to dismiss, but the OS may still trim notifications under memory pressure. In
     * that rare case `activeSoundKeys` will hold stale entries until the next `cancelAlarm()`
     * (which is harmless — `mgr.cancel(id)` for a no-longer-active id is a no-op).
     */
    private val activeSoundKeys: MutableSet<Int> = mutableSetOf()

    /**
     * Whether the channels were last created while AAPS held Do Not Disturb access.
     *
     * Android honours `setBypassDnd(true)` only when the channel is created (or created again) while
     * the app holds that access. So the channels made before the user granted it ring in Do Not
     * Disturb only after the next app start. [ensureChannels] creates them again once access is there.
     * For an existing channel Android then applies the bypass only when the user has not edited the
     * channel, and keeps sound and vibration as they were.
     *
     * Declared before `init`, so its initial value is set before `init` writes it.
     */
    @Volatile private var channelsHaveDndAccess = false

    init {
        // Read before creating: if access comes at the same moment, the next post re-creates them.
        val hasDndAccess = hasDndAccess()
        createChannels()
        channelsHaveDndAccess = hasDndAccess
    }

    private fun hasDndAccess(): Boolean =
        try {
            mgr.isNotificationPolicyAccessGranted
        } catch (_: Exception) {
            false
        }

    /** Re-creates the channels once Do Not Disturb access is granted. Called before every alarm post. */
    @Synchronized
    private fun ensureChannels() {
        if (!channelsHaveDndAccess && hasDndAccess()) {
            createChannels()
            channelsHaveDndAccess = true
            aapsLogger.debug(LTag.NOTIFICATION, "Alarm channels created again after Do Not Disturb access was granted")
        }
    }

    private fun createChannels() {
        mgr.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, "AAPS Alarms"))

        // Clean up an even-older silent FSI channel id from before the rampEnabled split.
        // Harmless if absent.
        mgr.deleteNotificationChannel("aaps_alarm_fullscreen")

        // Delete the pre-bypassDnd override channels ("_alarm" suffix). Channel settings are locked
        // after creation, so the DND-override channels had to be re-created under a new id
        // ("_alarm_bypass") to carry setBypassDnd(true). Harmless if absent. See channelIdForSound().
        for (name in SOUND_NAMES.values) mgr.deleteNotificationChannel("aaps_alarm_${name}_alarm")

        // Silent FSI channel for the ramp-enabled case. IMPORTANCE_HIGH keeps the heads-up
        // popup visible; vibration enabled because the user opted into ramp, not into a
        // completely-silent alert.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FULL_SCREEN_SILENT,
                "Urgent alarm (volume-ramp mode)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(true)
                group = GROUP_ID
                description = "Used when 'Gradually increase notification volume' is on — the activity's MediaPlayer owns the audio experience."
            }
        )

        // Ramp mode with DND override: vibration first, then the player's rising sound. Bypasses DND
        // like the sound channels below (honoured only with notification-policy access).
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RAMP_BYPASS,
                "Urgent alarm (vibrate, then rising sound, override DND)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = RAMP_VIBRATION_PATTERN
                setBypassDnd(true)
                group = GROUP_ID
                description = "Used when 'Gradually increase notification volume' is on: the phone vibrates, then the alarm sound rises from silence."
            }
        )

        // Two sound-bearing channels per R.raw sound: one routed to STREAM_ALARM (overrides DND),
        // one routed to STREAM_NOTIFICATION (respects DND/silent). Picked at post time based on
        // the user's AlertOverrideDoNotDisturb preference.
        //
        // Both `runAlarm` (full-screen) and `startAlarm` (sound-only) use these channels. For the
        // FSI path, the channel's one-shot sound covers the "user is active, FSI downgrades to
        // heads-up" case so the user always hears an audio cue at post time, not only after they
        // tap the notification. These channels are used only when the volume ramp is OFF: a channel
        // clip plays at the full stream volume and nothing can lower it, so with the ramp ON the
        // silent channels are used instead (see AlarmChannelPolicy).
        for ((soundId, displayName) in DISPLAY_NAMES) {
            val uri: Uri = Uri.parse("android.resource://${context.packageName}/$soundId")

            val alarmAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mgr.createNotificationChannel(
                NotificationChannel(
                    channelIdForSound(soundId, overrideDnd = true),
                    "$displayName (override DND)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(uri, alarmAttrs)
                    // The only reliable way to ring through Do Not Disturb / bedtime modes. Honored
                    // by the OS ONLY while AAPS holds notification-policy access (ACCESS_NOTIFICATION_POLICY,
                    // user-granted); silently ignored otherwise, so safe to always set.
                    setBypassDnd(true)
                    group = GROUP_ID
                }
            )

            val notifyAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // IMPORTANCE_HIGH gives heads-up popup behavior regardless of DND choice — DND/silent
            // is enforced by USAGE_NOTIFICATION at the audio layer, not by channel importance.
            // For medical alarms we always want heads-up visibility.
            mgr.createNotificationChannel(
                NotificationChannel(
                    channelIdForSound(soundId, overrideDnd = false),
                    "$displayName (respects DND)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(uri, notifyAttrs)
                    group = GROUP_ID
                }
            )
        }
    }

    private fun openAppPendingIntent(): PendingIntent? {
        val mainActivity = uiInteractionProvider.get().mainActivity
        return TaskStackBuilder.create(context).run {
            addParentStack(mainActivity)
            addNextIntent(Intent(context, mainActivity))
            getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    /**
     * Adds the alarm's extra action button (e.g. "Hypo treated") to [builder] when it declares one.
     *
     * The button reaches a [android.content.BroadcastReceiver] rather than the in-app
     * [app.aaps.core.interfaces.notifications.NotificationAction] lambda, because during the event
     * that matters — a real hypo — the phone is locked and the app is not open.
     */
    private fun addAlarmAction(builder: NotificationCompat.Builder, action: AlarmAction?) {
        if (action == null) return
        val (labelRes, receiver) = when (action) {
            AlarmAction.HYPO_TREATED -> R.string.alert_hypo_treated to HypoTreatedReceiver::class.java
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_ACTION_REQUEST_CODE,
            Intent(context, receiver),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(0, rh.gs(labelRes), pendingIntent)
    }

    /**
     * True when Do Not Disturb would mute alarm audio now (total silence, or priority mode without
     * alarms). Unreadable policy counts as blocked: then the bypass-DND sound channel is used.
     */
    fun dndBlocksAlarms(): Boolean {
        val filter = mgr.currentInterruptionFilter
        val alarmsAllowed = try {
            (mgr.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
        } catch (_: Exception) {
            null
        }
        return AlarmAudioPolicy.dndBlocksAlarms(filter, alarmsAllowed)
    }

    /**
     * True when the looping player owns the alarm sound (volume ramp, or a chosen soft melody), so the
     * notification channel must stay silent. False when the OS must play it through the channel.
     */
    fun playerOwnsSound(category: AlarmSoundCategory): Boolean {
        val overrideDnd = preferences.get(BooleanKey.AlertOverrideDoNotDisturb)
        val soundKey = if (category == AlarmSoundCategory.LOW_GLUCOSE) StringKey.AlertSoundLowGlucose else StringKey.AlertSoundOtherAlarms
        val owns = AlarmAudioPolicy.playerOwnsSound(
            increaseVolume = preferences.get(BooleanKey.AlertIncreaseVolume),
            customMelody = AlarmMelody.fromChoice(preferences.get(soundKey)) != null,
            overrideDnd = overrideDnd,
            dndBlocksAlarms = overrideDnd && dndBlocksAlarms(),
        )
        if (!owns && overrideDnd && dndBlocksAlarms()) {
            aapsLogger.debug(LTag.NOTIFICATION, "Do Not Disturb blocks alarms: urgent alarm rings through the bypass-DND sound channel")
        }
        return owns
    }

    private fun channelIdForSound(@RawRes soundId: Int, overrideDnd: Boolean): String {
        val name = SOUND_NAMES[soundId] ?: "error"
        // "_bypass" (was "_alarm") is a version bump: channel settings are immutable after creation,
        // so the override channel must get a NEW id to pick up setBypassDnd(true). Old "_alarm"
        // channels are deleted in createChannels().
        val suffix = if (overrideDnd) "alarm_bypass" else "notify"
        return "aaps_alarm_${name}_$suffix"
    }

    /**
     * Post a full-screen-intent alarm notification.
     * The notification carries an FSI to [UiInteraction.errorHelperActivity]; Android either
     * launches it full-screen (device idle / lock screen) or shows a heads-up notification.
     * The activity is responsible for sound playback.
     */
    fun postFullScreenAlarm(status: String, title: String, @RawRes soundId: Int) {
        ensureChannels()
        // Reached only from the background / off-main branches of UiInteraction.runAlarm.
        //
        // Screen-wake + full-screen ErrorActivity launch no longer rely on USE_FULL_SCREEN_INTENT
        // (Google Play silently re-revokes it on every update for a sideloaded, non-alarm app, and it
        // only auto-launches on a locked screen anyway). Instead scheduleScreenWakeAndLaunch() below
        // fires two setAlarmClock alarms — one powers the display on (AlarmScreenWakeReceiver), one
        // launches ErrorActivity — both permission-free.
        //
        // The sound-bearing notification posted here rings via the system channel one-shot (instant,
        // reliable) and is the lock-screen fallback UI when the activity can't be brought to the
        // foreground (another app on top — a background app cannot interrupt the foreground without FSI).
        // We ALSO start the looping/ramping AlarmSoundPlayer here (OWNER_FULLSCREEN) so the alarm keeps
        // sounding continuously even when ErrorActivity never foregrounds — reusing AAPS's existing
        // DummyService foreground state, exactly as the internal-notification alarm path does. The
        // EXTRA_POSTED_AT_ELAPSED_REALTIME stamp defers the loop past the channel one-shot to avoid
        // double-audio; if ErrorActivity does launch, it re-requests the same owner+sound, which the
        // player treats as idempotent (no restart glitch).
        val overrideDnd = preferences.get(BooleanKey.AlertOverrideDoNotDisturb)
        val rampChoice = AlarmChannelPolicy.forPlayerOwnedSound(playerOwnsSound(AlarmSoundCategory.OTHER), overrideDnd)
        // With the ramp on the channel is silent, so the player starts at once (postedAt 0 = no wait).
        val postedAt = if (rampChoice == null) SystemClock.elapsedRealtime() else 0L
        val intent = Intent(context, uiInteractionProvider.get().errorHelperActivity).apply {
            putExtra(AlarmIntent.EXTRA_SOUND_ID, soundId)
            putExtra(AlarmIntent.EXTRA_STATUS, status)
            putExtra(AlarmIntent.EXTRA_TITLE, title)
            putExtra(AlarmIntent.EXTRA_POSTED_AT_ELAPSED_REALTIME, postedAt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_FULL_SCREEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Ramp off: a sound-bearing channel (matching the requested soundId + DND preference) so the alarm
        // is audible from the notification regardless of whether the FSI activity ever launches.
        // Ramp on: a silent channel, and the player below rises from silence at once.
        val channelId = rampChoice?.channelId ?: channelIdForSound(soundId, overrideDnd)

        // Mute action so the user can silence the looping alarm straight from the lock-screen
        // notification when ErrorActivity isn't in the foreground (see AlarmMuteReceiver).
        val mutePendingIntent = PendingIntent.getBroadcast(
            context, MUTE_REQUEST_CODE,
            Intent(context, AlarmMuteReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconsProvider.getNotificationIcon())
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .addAction(0, rh.gs(app.aaps.core.ui.R.string.mute), mutePendingIntent)
            .build()

        try {
            mgr.notify(NOTIFICATION_ID_FULL_SCREEN, notification)
            aapsLogger.debug(LTag.NOTIFICATION, "Posted full-screen alarm: $title - $status")
        } catch (ex: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime (Android 13+). The user's next return to
            // ComposeMainActivity refreshes the global PermissionsSheet (onResume → refresh),
            // which will surface the missing-permission state and offer to open Android settings.
            aapsLogger.error(
                LTag.NOTIFICATION,
                "Failed to post full-screen alarm \"$title\" — POST_NOTIFICATIONS likely revoked",
                ex
            )
        }

        // Continuous looping/ramping audio so the alarm keeps sounding even if ErrorActivity never
        // comes to the foreground (deferred past the channel one-shot via postedAt). Stopped by
        // muteAllAlarms() / ErrorActivity acknowledge, both of which stop OWNER_FULLSCREEN.
        alarmSoundPlayer.play(soundId, AlarmSoundPlayer.OWNER_FULLSCREEN, postedAt)

        // Permission-free screen-wake + activity launch (independent of the notification above, so it
        // still runs even if POST_NOTIFICATIONS was revoked and mgr.notify threw).
        scheduleScreenWakeAndLaunch(pendingIntent)
    }

    /**
     * Wakes the display and launches [app.aaps.core.interfaces.ui.UiInteraction.errorHelperActivity]
     * for a background alarm without `USE_FULL_SCREEN_INTENT`, by firing two simultaneous
     * [AlarmManager.setAlarmClock] alarms (alarm-clock alarms are Doze-exempt and get the
     * background-activity-launch exemption):
     *  1. a broadcast to [AlarmScreenWakeReceiver], whose `ACQUIRE_CAUSES_WAKEUP` wake lock powers
     *     the screen on;
     *  2. [activityPendingIntent] (a `getActivity` at ErrorActivity), which the OS holds until the
     *     screen is on and then presents — over the keyguard when AAPS is the foreground task.
     *
     * A small delay lets the alarms register before firing. `setAlarmClock` does not require
     * `SCHEDULE_EXACT_ALARM` (alarm clocks are exempt).
     */
    private fun scheduleScreenWakeAndLaunch(activityPendingIntent: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + SCREEN_WAKE_DELAY_MS
        val show = PendingIntent.getActivity(
            context, WAKE_REQUEST_CODE,
            Intent(context, uiInteractionProvider.get().mainActivity),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val wakeOp = PendingIntent.getBroadcast(
            context, WAKE_REQUEST_CODE,
            Intent(context, AlarmScreenWakeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), wakeOp)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), activityPendingIntent)
        aapsLogger.debug(LTag.NOTIFICATION, "Scheduled screen-wake + activity launch in ${SCREEN_WAKE_DELAY_MS}ms")
    }

    /**
     * Post a **silent** alarm notification on the [CHANNEL_FULL_SCREEN_SILENT] channel
     * (heads-up + vibration, no channel sound). The audio is owned by [AlarmSoundPlayer]; this
     * notification provides shade/lock-screen visibility and a tap target to open the app.
     *
     * Tracked in [activeSoundKeys] exactly like [postSoundAlarmNotification], so
     * [cancelSoundAlarm] / [cancelAlarm] clear it.
     *
     * @param notificationKey unique-per-AAPS-notification identifier (the `AapsNotification.instanceKey`)
     * @param urgent          true for URGENT-level alerts (stronger vibration)
     * @param alarmAction     optional extra action button declared by the alarm (see [addAlarmAction])
     */
    fun postSilentAlarmNotification(
        notificationKey: Int,
        title: String,
        body: String,
        urgent: Boolean,
        alarmAction: AlarmAction? = null
    ) {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, CHANNEL_FULL_SCREEN_SILENT)
            .setSmallIcon(iconsProvider.getNotificationIcon())
            .setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent())
        if (urgent) {
            builder.setVibrate(longArrayOf(1000, 1000, 1000, 1000))
        } else {
            builder.setVibrate(longArrayOf(0, 100, 50, 100, 50))
        }
        addAlarmAction(builder, alarmAction)

        val systemId = SOUND_ID_OFFSET + notificationKey
        try {
            // notify + add must be atomic relative to cancelAlarm/cancelSoundAlarm — see activeSoundKeys.
            synchronized(activeSoundKeys) {
                mgr.notify(systemId, builder.build())
                activeSoundKeys.add(notificationKey)
            }
            aapsLogger.debug(LTag.NOTIFICATION, "Posted silent alarm key=$notificationKey: $title - $body")
        } catch (ex: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime (Android 13+) — see postFullScreenAlarm.
            aapsLogger.error(
                LTag.NOTIFICATION,
                "Failed to post silent alarm \"$title\" key=$notificationKey — POST_NOTIFICATIONS likely revoked",
                ex
            )
        }
    }

    /**
     * Post an audible alarm notification on the bypass-DND **sound-bearing** channel
     * ([channelIdForSound] with `overrideDnd = true`). Unlike [postSilentAlarmNotification], the OS
     * itself emits the alarm sound here — the only path that survives Do Not Disturb / bedtime modes
     * (when notification-policy access is granted). Used by the internal URGENT path when
     * [BooleanKey.AlertOverrideDoNotDisturb] is on; the caller's ramping MediaPlayer loop is deferred
     * past this one-shot (via `AapsNotification.postedAtElapsedRealtime`) so they don't overlap.
     *
     * Tracked in [activeSoundKeys] exactly like [postSilentAlarmNotification].
     *
     * @param notificationKey unique-per-AAPS-notification identifier (the `AapsNotification.instanceKey`)
     * @param soundId         raw alarm sound resource (selects the channel)
     * @param urgent          true for URGENT-level alerts (stronger vibration)
     * @param alarmAction     optional extra action button declared by the alarm (see [addAlarmAction])
     */
    fun postSoundAlarmNotification(
        notificationKey: Int,
        title: String,
        body: String,
        @RawRes soundId: Int,
        urgent: Boolean,
        alarmAction: AlarmAction? = null,
        /** Player owns the sound (ramp or melody): post on the silent [CHANNEL_RAMP_BYPASS] (vibration only). */
        rampMode: Boolean = false,
    ) {
        ensureChannels()
        val channelId = if (rampMode) CHANNEL_RAMP_BYPASS else channelIdForSound(soundId, overrideDnd = true)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(iconsProvider.getNotificationIcon())
            .setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent())
        if (urgent) {
            builder.setVibrate(longArrayOf(1000, 1000, 1000, 1000))
        } else {
            builder.setVibrate(longArrayOf(0, 100, 50, 100, 50))
        }
        addAlarmAction(builder, alarmAction)

        val systemId = SOUND_ID_OFFSET + notificationKey
        try {
            // notify + add must be atomic relative to cancelAlarm/cancelSoundAlarm — see activeSoundKeys.
            synchronized(activeSoundKeys) {
                mgr.notify(systemId, builder.build())
                activeSoundKeys.add(notificationKey)
            }
            aapsLogger.debug(
                LTag.NOTIFICATION,
                "Posted bypass-DND ${if (rampMode) "ramp (vibrate)" else "sound"} alarm key=$notificationKey: $title - $body"
            )
        } catch (ex: SecurityException) {
            // POST_NOTIFICATIONS revoked at runtime (Android 13+) — see postFullScreenAlarm.
            aapsLogger.error(
                LTag.NOTIFICATION,
                "Failed to post sound alarm \"$title\" key=$notificationKey — POST_NOTIFICATIONS likely revoked",
                ex
            )
        }
    }

    /**
     * Cancel a specific sound alarm by its [notificationKey] (the `AapsNotification.instanceKey`).
     * No-op if that key has no active notification. Use this when an individual AAPS notification is
     * dismissed/replaced/expired so we only cancel its own sound, not any concurrently active
     * alarms.
     */
    fun cancelSoundAlarm(notificationKey: Int) {
        val cancelled = synchronized(activeSoundKeys) {
            if (activeSoundKeys.remove(notificationKey)) {
                mgr.cancel(SOUND_ID_OFFSET + notificationKey)
                true
            } else false
        }
        if (cancelled) aapsLogger.debug(LTag.NOTIFICATION, "Cancelled sound alarm key=$notificationKey")
    }

    /**
     * Cancel all active AAPS alarm notifications (full-screen + every tracked sound alarm).
     * Used by the global stop paths: user-acknowledge from ErrorActivity, app onTerminate,
     * Wear "mute all" gesture.
     */
    fun cancelAlarm() {
        mgr.cancel(NOTIFICATION_ID_FULL_SCREEN)
        synchronized(activeSoundKeys) {
            activeSoundKeys.forEach { mgr.cancel(SOUND_ID_OFFSET + it) }
            activeSoundKeys.clear()
        }
        aapsLogger.debug(LTag.NOTIFICATION, "Cancelled all AAPS alarm notifications")
    }
}
