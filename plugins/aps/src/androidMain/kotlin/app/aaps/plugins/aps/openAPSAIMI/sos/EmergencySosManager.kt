package app.aaps.plugins.aps.openAPSAIMI.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AIMI Emergency SOS Manager (SMS-only).
 *
 * Product contract:
 * - SMS after 30 minutes of BG below the monitoring threshold.
 * - Immediate SMS if BG &lt; immediate threshold OR delta ≤ −10 mg/dL.
 * - Follow-up every 15 minutes while still below recovery (threshold + 10).
 * - Missing-sensor SMS when no valid BG for the stale window.
 * - Up to two contacts; identical numbers are deduplicated (one SMS only).
 * - No automatic phone calls.
 *
 * ⚠️ ASYNC IMPACT: uses a process-scoped IO [CoroutineScope] for SMS + location.
 */
object EmergencySosManager {

    private const val SOS_PREFS = "aimi_sos_advanced_prefs"

    private const val KEY_FIRST_BELOW_THRESHOLD_TIME = "first_below_threshold_time"
    private const val KEY_LAST_ACTION_TIME = "last_action_time"
    private const val KEY_LAST_VALID_BG_TIME = "last_valid_bg_time"
    private const val KEY_STALE_ALERT_TRIGGERED = "stale_alert_triggered"

    private const val OBSERVATION_WINDOW_MS = 30 * 60 * 1000L
    private const val FOLLOWUP_INTERVAL_MS = 15 * 60 * 1000L
    private const val SENSOR_ERROR_BG = 10.0
    private const val RECOVERY_HYSTERESIS_MGDL = 10.0
    private const val IMMEDIATE_DELTA_MGDL = -10.0

    private val sosScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Normalize and deduplicate emergency phone numbers so the same contact never receives two SMS.
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

    @JvmStatic
    fun evaluateSosCondition(
        aapsLogger: AAPSLogger,
        bg: Double,
        delta: Double,
        iob: Double,
        context: Context,
        preferences: Preferences,
        nowMs: Long,
    ) {
        val appContext = context.applicationContext
        val isSosEnabled = preferences.get(BooleanKey.AimiEmergencySosEnable)
        val threshold = preferences.get(IntKey.AimiEmergencySosThreshold).toDouble()
        val immediateThreshold = preferences.get(IntKey.AimiEmergencySosImmediateThreshold).toDouble()
        val staleThresholdMs = preferences.get(IntKey.AimiEmergencySosStaleThreshold).toLong() * 60_000L
        val phones = uniquePhoneNumbers(
            preferences.get(StringKey.AimiEmergencySosPhone),
            preferences.get(StringKey.AimiEmergencySosPhone2),
        )
        val prefs = appContext.getSharedPreferences(SOS_PREFS, Context.MODE_PRIVATE)
        val canSms = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        aapsLogger.debug(
            LTag.APS,
            "SOS evaluate enabled=$isSosEnabled bg=${"%.1f".format(Locale.US, bg)} " +
                "monitor=$threshold immediate=$immediateThreshold staleMin=${staleThresholdMs / 60_000} " +
                "contacts=${phones.size} canSms=$canSms"
        )

        if (!isSosEnabled || phones.isEmpty() || !canSms) {
            resetSosState(prefs)
            return
        }

        val isBgRecovered = bg >= (threshold + RECOVERY_HYSTERESIS_MGDL)
        val isSensorError = bg <= SENSOR_ERROR_BG

        if (isBgRecovered) {
            prefs.edit { putLong(KEY_LAST_VALID_BG_TIME, nowMs) }
            if (prefs.getLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L) != 0L ||
                prefs.getBoolean(KEY_STALE_ALERT_TRIGGERED, false)
            ) {
                aapsLogger.info(
                    LTag.APS,
                    "SOS recovered bg=${"%.1f".format(Locale.US, bg)} above ${threshold + RECOVERY_HYSTERESIS_MGDL}"
                )
                resetSosState(prefs)
            }
            return
        }

        if (!isSensorError) {
            prefs.edit { putLong(KEY_LAST_VALID_BG_TIME, nowMs) }
        }

        val lastValidBgTime = prefs.getLong(KEY_LAST_VALID_BG_TIME, 0L)
        val lastActionTime = prefs.getLong(KEY_LAST_ACTION_TIME, 0L)
        var shouldTriggerNow = false
        var isStaleScenario = false

        if (lastActionTime != 0L && nowMs - lastActionTime >= FOLLOWUP_INTERVAL_MS) {
            shouldTriggerNow = true
        }

        if (!shouldTriggerNow && lastValidBgTime != 0L && (nowMs - lastValidBgTime >= staleThresholdMs)) {
            isStaleScenario = true
            if (lastActionTime == 0L) {
                shouldTriggerNow = true
                prefs.edit { putBoolean(KEY_STALE_ALERT_TRIGGERED, true) }
            }
        }

        if (!shouldTriggerNow && !isStaleScenario && !isSensorError) {
            var firstBelowTime = prefs.getLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L)
            if (firstBelowTime == 0L) {
                aapsLogger.debug(
                    LTag.APS,
                    appContext.getString(R.string.sos_log_monitoring_start, bg, threshold)
                )
                prefs.edit { putLong(KEY_FIRST_BELOW_THRESHOLD_TIME, nowMs) }
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

        val timeLabel = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(nowMs))
        val deltaString = String.format(Locale.US, "%+.1f", delta)
        aapsLogger.info(LTag.APS, appContext.getString(R.string.sos_log_sending, bg, deltaString))

        // Persist action time before async send so a crash mid-send does not spam.
        prefs.edit { putLong(KEY_LAST_ACTION_TIME, nowMs) }

        sosScope.launch {
            try {
                val location = fetchLocation(aapsLogger, appContext)
                val isCritical = bg < immediateThreshold
                val isRecovering = delta > 0.0
                val title: String
                val footer: String
                when {
                    isStaleScenario -> {
                        title = appContext.getString(R.string.sos_sms_title_stale)
                        footer = appContext.getString(R.string.sos_sms_footer_stale)
                    }
                    isCritical -> {
                        title = appContext.getString(R.string.sos_sms_title_critical)
                        footer = appContext.getString(R.string.sos_sms_footer_critical)
                    }
                    isRecovering -> {
                        title = appContext.getString(R.string.sos_sms_title_recovery)
                        footer = appContext.getString(R.string.sos_sms_footer_recovery)
                    }
                    else -> {
                        title = appContext.getString(R.string.sos_sms_title_low)
                        footer = appContext.getString(R.string.sos_sms_footer_low)
                    }
                }
                val body = if (isStaleScenario) {
                    "$title\n${appContext.getString(R.string.sos_sms_label_last_bg)}: ${bg.toInt()}\n" +
                        "${appContext.getString(R.string.sos_sms_label_trend)}: $deltaString\n" +
                        "${appContext.getString(R.string.sos_sms_label_time)}: $timeLabel$footer"
                } else {
                    "$title\n${appContext.getString(R.string.sos_sms_label_bg)}: ${bg.toInt()}\n" +
                        "${appContext.getString(R.string.sos_sms_label_trend)}: $deltaString\n" +
                        "${appContext.getString(R.string.sos_sms_label_iob)}: ${"%.2f".format(Locale.US, iob)}U\n" +
                        "${appContext.getString(R.string.sos_sms_label_time)}: $timeLabel$footer"
                }
                for (phone in phones) {
                    sendRawSms(aapsLogger, appContext, phone, body, location)
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, appContext.getString(R.string.sos_log_error_send), e)
            }
        }
    }

    private fun resetSosState(prefs: android.content.SharedPreferences) {
        prefs.edit {
            putLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L)
            putLong(KEY_LAST_ACTION_TIME, 0L)
            putLong(KEY_LAST_VALID_BG_TIME, 0L)
            putBoolean(KEY_STALE_ALERT_TRIGGERED, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation(aapsLogger: AAPSLogger, context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "SOS location unavailable: ${e.message}")
            null
        }
    }

    private fun sendRawSms(
        aapsLogger: AAPSLogger,
        context: Context,
        phone: String,
        message: String,
        location: Location?,
    ) {
        val posLabel = context.getString(R.string.sos_sms_label_pos)
        val loc = location?.let {
            "\n$posLabel: https://www.google.com/maps?q=${it.latitude},${it.longitude}"
        } ?: "\n$posLabel: ${context.getString(R.string.sos_sms_pos_not_available)}"
        val fullMsg = message + loc
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager?.divideMessage(fullMsg)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager?.sendTextMessage(phone, null, fullMsg, null, null)
            }
            aapsLogger.info(LTag.APS, "SOS SMS queued to ${maskPhone(phone)}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "SOS SMS error for ${maskPhone(phone)}", e)
        }
    }

    private fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length <= 4) "****" else "***${digits.takeLast(4)}"
    }
}
