package app.aaps.plugins.aps.openAPSAIMI.sos

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelCritical
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSettingEnabled
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.math.abs

/**
 * iOS half of [AimiEmergencySos].
 *
 * Trigger logic is a direct port of Android's `EmergencySosManager.evaluateSosCondition`
 * (same thresholds, same 30-minute observation window, same 15-minute follow-ups, same
 * stale-sensor alert, same state keys). Behavioural parity with `dev_OAPSAIMI` is in the
 * *condition*; only the delivery differs.
 *
 * Delivery is a local notification, not SMS: iOS offers no API for an app to send SMS,
 * so the Android behaviour (texting up to two emergency contacts) cannot be reproduced.
 * What iOS can do is raise a loud local alert on this device with the glucose data and,
 * when configured, the emergency numbers so the user or a bystander can call them.
 * Phone numbers are therefore informational on iOS, not required: unlike Android, an
 * empty contact list does not suppress the alert.
 *
 * Critical alerts:
 * - Needs the `com.apple.developer.usernotifications.critical-alerts` entitlement in the
 *   app's `.entitlements` file. Apple grants this entitlement only on request (developer
 *   portal); without it the option is silently not granted.
 * - At runtime the granted setting is checked before every post: critical level plus the
 *   critical sound when granted, otherwise a time-sensitive notification with the default
 *   sound. No crash, no silent path - the degradation is logged.
 *
 * Not ported (documented, not silent):
 * - Live location in the message (Android appends a Google Maps link). Needs CoreLocation
 *   permission and an async fetch; the notification carries glucose data only.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiEmergencySos @Inject constructor() : AimiEmergencySos {

    /**
     * Resolved on first use, not in the constructor.
     *
     * `currentNotificationCenter()` needs a real app bundle and throws
     * `bundleProxyForCurrentProcess is nil` without one, so touching it eagerly would break
     * any test binary that builds the graph. Same reason as in `IosSystemNotificationPlatform`.
     */
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }
    private var authorizationAsked = false

    override fun evaluate(
        aapsLogger: AAPSLogger,
        bg: Double,
        delta: Double,
        iob: Double,
        preferences: Preferences,
        nowMs: Long,
    ) {
        val isSosEnabled = preferences.get(BooleanKey.AimiEmergencySosEnable)
        val threshold = preferences.get(IntKey.AimiEmergencySosThreshold).toDouble()
        val immediateThreshold = preferences.get(IntKey.AimiEmergencySosImmediateThreshold).toDouble()
        val staleThresholdMs = preferences.get(IntKey.AimiEmergencySosStaleThreshold).toLong() * 60_000L
        val phones = uniquePhoneNumbers(
            preferences.get(StringKey.AimiEmergencySosPhone),
            preferences.get(StringKey.AimiEmergencySosPhone2),
        )

        aapsLogger.debug(
            LTag.APS,
            "SOS evaluate enabled=$isSosEnabled bg=${aimiFmt1(bg)} " +
                "monitor=${aimiFmt1(threshold)} immediate=${aimiFmt1(immediateThreshold)} " +
                "staleMin=${staleThresholdMs / 60_000} contacts=${phones.size}"
        )

        if (!isSosEnabled) {
            resetSosState()
            return
        }

        val isBgRecovered = bg >= (threshold + RECOVERY_HYSTERESIS_MGDL)
        val isSensorError = bg <= SENSOR_ERROR_BG

        if (isBgRecovered) {
            setLong(KEY_LAST_VALID_BG_TIME, nowMs)
            if (getLong(KEY_FIRST_BELOW_THRESHOLD_TIME) != 0L || getBool(KEY_STALE_ALERT_TRIGGERED)) {
                aapsLogger.info(
                    LTag.APS,
                    "SOS recovered bg=${aimiFmt1(bg)} above ${aimiFmt1(threshold + RECOVERY_HYSTERESIS_MGDL)}"
                )
                resetSosState()
            }
            return
        }

        if (!isSensorError) {
            setLong(KEY_LAST_VALID_BG_TIME, nowMs)
        }

        val lastValidBgTime = getLong(KEY_LAST_VALID_BG_TIME)
        val lastActionTime = getLong(KEY_LAST_ACTION_TIME)
        var shouldTriggerNow = false
        var isStaleScenario = false

        if (lastActionTime != 0L && nowMs - lastActionTime >= FOLLOWUP_INTERVAL_MS) {
            shouldTriggerNow = true
        }

        if (!shouldTriggerNow && lastValidBgTime != 0L && (nowMs - lastValidBgTime >= staleThresholdMs)) {
            isStaleScenario = true
            if (lastActionTime == 0L) {
                shouldTriggerNow = true
                setBool(KEY_STALE_ALERT_TRIGGERED, true)
            }
        }

        if (!shouldTriggerNow && !isStaleScenario && !isSensorError) {
            var firstBelowTime = getLong(KEY_FIRST_BELOW_THRESHOLD_TIME)
            if (firstBelowTime == 0L) {
                aapsLogger.debug(
                    LTag.APS,
                    "SOS monitoring start: BG ${aimiFmt1(bg)} below ${aimiFmt1(threshold)}, " +
                        "30 min observation window"
                )
                setLong(KEY_FIRST_BELOW_THRESHOLD_TIME, nowMs)
                firstBelowTime = nowMs
            }
            if (lastActionTime == 0L) {
                when {
                    bg < immediateThreshold -> shouldTriggerNow = true
                    delta <= IMMEDIATE_DELTA_MGDL -> shouldTriggerNow = true
                    nowMs - firstBelowTime >= OBSERVATION_WINDOW_MS -> shouldTriggerNow = true
                }
            }
        }

        if (!shouldTriggerNow) return

        val isCritical = bg < immediateThreshold
        val isRecovering = delta > 0.0
        val (title, footer) = when {
            isStaleScenario -> SOS_TITLE_STALE to SOS_FOOTER_STALE
            isCritical -> SOS_TITLE_CRITICAL to SOS_FOOTER_CRITICAL
            isRecovering -> SOS_TITLE_RECOVERY to SOS_FOOTER_RECOVERY
            else -> SOS_TITLE_LOW to SOS_FOOTER_LOW
        }
        val deltaString = (if (delta < 0) "-" else "+") + aimiFmt1(abs(delta))
        val timeLabel = formatTime(nowMs)
        val contactsLine = if (phones.isNotEmpty()) "\nCall: ${phones.joinToString(", ")}" else ""
        val body = if (isStaleScenario) {
            "$SOS_LABEL_LAST_BG: ${bg.toInt()}\n" +
                "$SOS_LABEL_TREND: $deltaString\n" +
                "$SOS_LABEL_TIME: $timeLabel$footer$contactsLine"
        } else {
            "$SOS_LABEL_BG: ${bg.toInt()}\n" +
                "$SOS_LABEL_TREND: $deltaString\n" +
                "$SOS_LABEL_IOB: ${aimiFmt2(iob)}U\n" +
                "$SOS_LABEL_TIME: $timeLabel$footer$contactsLine"
        }

        aapsLogger.info(LTag.APS, "SOS alerting (BG: ${aimiFmt1(bg)}, Delta: $deltaString)")

        // Persist action time before posting so a crash mid-post does not spam.
        setLong(KEY_LAST_ACTION_TIME, nowMs)

        postSosNotification(aapsLogger, title, body)
    }

    /**
     * Posts the alert, degrading gracefully when critical alerts are unavailable.
     *
     * The granted setting is read fresh on every post: the user can change notification
     * permissions in Settings at any time, and a cached answer would lie after that.
     */
    private fun postSosNotification(aapsLogger: AAPSLogger, title: String, body: String) {
        ensureAuthorization(aapsLogger)
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val criticalGranted = settings?.criticalAlertSetting == UNNotificationSettingEnabled
            if (!criticalGranted) {
                aapsLogger.debug(
                    LTag.APS,
                    "SOS critical alerts not granted (criticalAlertSetting=${settings?.criticalAlertSetting}); " +
                        "using time-sensitive notification"
                )
            }
            val content = UNMutableNotificationContent().apply {
                setTitle(title)
                setBody(body)
                if (criticalGranted) {
                    setSound(UNNotificationSound.defaultCriticalSound())
                    setInterruptionLevel(UNNotificationInterruptionLevelCritical)
                } else {
                    setSound(UNNotificationSound.defaultSound())
                    setInterruptionLevel(UNNotificationInterruptionLevelTimeSensitive)
                }
            }
            // Stable identifier: a follow-up replaces the previous alert instead of stacking.
            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = NOTIFICATION_ID,
                content = content,
                trigger = null,
            )
            center.addNotificationRequest(request) { error ->
                if (error != null) aapsLogger.error(LTag.APS, "SOS notification failed: $error")
                else aapsLogger.info(LTag.APS, "SOS notification posted (critical=$criticalGranted)")
            }
        }
    }

    /**
     * Asks for notification permission once, lazily, on the first SOS alert.
     *
     * Asking in the constructor would put the system prompt in front of the user during
     * start up, before anything has explained why the app wants it. `CriticalAlert` is
     * included in the options: without the entitlement it is simply not granted, which the
     * per-post settings check then handles.
     */
    private fun ensureAuthorization(aapsLogger: AAPSLogger) {
        if (authorizationAsked) return
        authorizationAsked = true
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge or UNAuthorizationOptionCriticalAlert
        center.requestAuthorizationWithOptions(options) { granted, error ->
            if (error != null) aapsLogger.error(LTag.APS, "SOS notification permission failed: $error")
            else aapsLogger.debug(LTag.APS, "SOS notification permission granted=$granted")
        }
    }

    private fun resetSosState() {
        setLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L)
        setLong(KEY_LAST_ACTION_TIME, 0L)
        setLong(KEY_LAST_VALID_BG_TIME, 0L)
        setBool(KEY_STALE_ALERT_TRIGGERED, false)
    }

    private fun getLong(key: String): Long = NSUserDefaults.standardUserDefaults.longForKey(key)

    private fun setLong(key: String, value: Long) =
        NSUserDefaults.standardUserDefaults.setLong(value, forKey = key)

    private fun getBool(key: String): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(key)

    private fun setBool(key: String, value: Boolean) =
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = key)

    private fun formatTime(ms: Long): String {
        // Per-call instance: NSDateFormatter is not thread-safe and this runs rarely.
        val formatter = NSDateFormatter()
        formatter.dateFormat = "dd/MM HH:mm"
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
    }

    companion object {
        // State keys mirror Android's "aimi_sos_advanced_prefs" file, as plain
        // NSUserDefaults keys. longForKey/boolForKey return 0/false when absent,
        // matching Android's getLong(key, 0L)/getBoolean(key, false).
        private const val KEY_FIRST_BELOW_THRESHOLD_TIME = "aimi_sos.first_below_threshold_time"
        private const val KEY_LAST_ACTION_TIME = "aimi_sos.last_action_time"
        private const val KEY_LAST_VALID_BG_TIME = "aimi_sos.last_valid_bg_time"
        private const val KEY_STALE_ALERT_TRIGGERED = "aimi_sos.stale_alert_triggered"

        // Trigger constants, identical to Android's EmergencySosManager.
        private const val OBSERVATION_WINDOW_MS = 30 * 60 * 1000L
        private const val FOLLOWUP_INTERVAL_MS = 15 * 60 * 1000L
        private const val SENSOR_ERROR_BG = 10.0
        private const val RECOVERY_HYSTERESIS_MGDL = 10.0
        private const val IMMEDIATE_DELTA_MGDL = -10.0

        private const val NOTIFICATION_ID = "aimi-sos-alert"

        // Message strings mirror plugins/aps/src/androidMain/res/values/aimi_strings.xml.
        private const val SOS_TITLE_CRITICAL = "SOS: CRITICAL HYPO"
        private const val SOS_TITLE_LOW = "SOS LOW BLOOD SUGAR ALERT"
        private const val SOS_TITLE_RECOVERY = "SOS HYPO: RECOVERY PHASE"
        private const val SOS_TITLE_STALE = "AAPS: MISSING SENSOR DATA"
        private const val SOS_FOOTER_CRITICAL = "\nTAKE ACTION NOW!"
        private const val SOS_FOOTER_LOW = "\nWAITING FOR RECOVERY OR USER ACTION"
        private const val SOS_FOOTER_RECOVERY = "\nBLOOD SUGAR IS RISING — WAIT FOR CONFIRMATION"
        private const val SOS_FOOTER_STALE = "\nCHECK SENSOR CONNECTION."
        private const val SOS_LABEL_BG = "BLOOD SUGAR"
        private const val SOS_LABEL_LAST_BG = "LAST BG"
        private const val SOS_LABEL_TREND = "TREND"
        private const val SOS_LABEL_IOB = "ACTIVE INSULIN"
        private const val SOS_LABEL_TIME = "TIME"

        /**
         * Normalize and deduplicate emergency phone numbers so the same contact is never
         * listed twice. Port of `EmergencySosManager.uniquePhoneNumbers`.
         */
        fun uniquePhoneNumbers(vararg phones: String): List<String> {
            val seen = linkedSetOf<String>()
            val result = mutableListOf<String>()
            for (raw in phones) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val fingerprint = phoneFingerprint(trimmed)
                if (fingerprint.isEmpty()) continue
                if (seen.add(fingerprint)) result.add(trimmed)
            }
            return result
        }

        /** Digits only; strips a leading international `00` so `+33…` and `0033…` match. */
        internal fun phoneFingerprint(phone: String): String {
            var digits = phone.filter { it.isDigit() }
            if (digits.startsWith("00")) digits = digits.removePrefix("00")
            return digits
        }
    }
}
