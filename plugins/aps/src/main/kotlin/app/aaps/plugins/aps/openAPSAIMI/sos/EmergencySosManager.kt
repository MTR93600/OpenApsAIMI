package app.aaps.plugins.aps.openAPSAIMI.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🚨 AIMI Emergency SOS Manager Advanced (Robuste / Stateless)
 *
 * - Premier SOS (SMS) envoyé au bout de 30 minutes de BG < seuil.
 * - Ré-évaluation toutes les 5 minutes (via la boucle principale d'AAPS).
 * - SMS et Appels alternés toutes les 15 minutes si le BG reste bas.
 * - État persisté dans SharedPreferences pour résister au "Doze Mode" d'Android.
 *
 * @author MTR & Lyra AI
 */
object EmergencySosManager {

    private const val TAG = "AIMI_SOS_Manager"
    private const val SOS_PREFS = "aimi_sos_advanced_prefs"
    
    private const val KEY_FIRST_BELOW_THRESHOLD_TIME = "first_below_threshold_time"
    private const val KEY_LAST_ACTION_TIME = "last_action_time"
    private const val KEY_LAST_ACTION_WAS_SMS = "last_action_was_sms"

    private const val OBSERVATION_WINDOW_MS = 30 * 60 * 1000L // 30 minutes for first SOS
    private const val FOLLOWUP_INTERVAL_MS = 15 * 60 * 1000L   // 15 minutes between SMS/call after first SOS

    fun evaluateSosCondition(
        bg: Double,
        delta: Double,
        iob: Double,
        context: Context,
        preferences: Preferences,
        nowMs: Long
    ) {
        val appContext = context.applicationContext

        // 1. Feature disabled or invalid settings
        val isSosEnabled = preferences.get(BooleanKey.AimiEmergencySosEnable)
        val threshold = preferences.get(IntKey.AimiEmergencySosThreshold)
        val phoneNumber = preferences.get(StringKey.AimiEmergencySosPhone).trim()

        val prefs = appContext.getSharedPreferences(SOS_PREFS, Context.MODE_PRIVATE)
        
        val canSms = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val canCall = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        // Require at least one action permission, else the feature is virtually disabled
        if (!isSosEnabled || phoneNumber.isEmpty() || (!canSms && !canCall)) {
            resetSosState(prefs)
            return
        }

        // 2. BG Recovered -> Reset everything
        if (bg > threshold || bg <= 10.0) {
            if (prefs.getLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L) != 0L) {
                Log.w(TAG, "🟢 BG ($bg) recovered above threshold ($threshold). Ending SOS tracking.")
                resetSosState(prefs)
            }
            return
        }

        // 3. BG is BELOW threshold. Track the time.
        var firstBelowTime = prefs.getLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L)
        if (firstBelowTime == 0L) {
            // This is the first time we drop below threshold
            Log.w(TAG, "⚠️ BG dropped below threshold ($bg < $threshold). Starting 30 min trend monitoring...")
            with(prefs.edit()) {
                putLong(KEY_FIRST_BELOW_THRESHOLD_TIME, nowMs)
                apply()
            }
            firstBelowTime = nowMs
        }

        // 4. Have we waited long enough for the first action? (30 mins)
        // Skip the 30-min window ONLY IF it's the very first action AND BG is falling.
        val lastActionTime = prefs.getLong(KEY_LAST_ACTION_TIME, 0L)
        val isFirstAction = lastActionTime == 0L
        val forceImmediateTrigger = isFirstAction && delta < 0.0
        
        // If it's the first action and we aren't forcing an immediate trigger, we MUST wait 30 minutes
        if (isFirstAction && !forceImmediateTrigger && nowMs - firstBelowTime < OBSERVATION_WINDOW_MS) {
            val remain = (OBSERVATION_WINDOW_MS - (nowMs - firstBelowTime)) / 60000
            Log.d(TAG, "SOS Monitoring: BG still low. $remain minutes remaining before first alert.")
            return
        }

        if (forceImmediateTrigger) {
             Log.w(TAG, "🚨 SOS IMMEDIATE TRIGGER: BG ($bg) crossed threshold ($threshold) while falling (Δ$delta). Bypassing 30-min window.")
        }

        // 5. Ready for Action! Check if we need to act based on 15 min interval
        // If it's the very first action (lastActionTime == 0) OR 15 mins have passed
        if (isFirstAction || nowMs - lastActionTime >= FOLLOWUP_INTERVAL_MS) {
            
            val lastActionWasSms = prefs.getBoolean(KEY_LAST_ACTION_WAS_SMS, false)
            
            // Intelligent Alternation Logic based on permissions
            val shouldSendSms = canSms && (isFirstAction || !lastActionWasSms || !canCall)
            // To be precise: We want SMS if (first time OR it's SMS turn OR we can't call). We want Call if (it's Call turn and we can Call) OR (we can't SMS and we can Call).
            val doSmsNow = canSms && (isFirstAction || !lastActionWasSms || !canCall)
            // If doSmsNow is false, we must do Call (because we know at least one permission exists). 
            val doCallNow = !doSmsNow // By elimination, since at least one of canSms/canCall is true.
            
            if (doSmsNow) {
                Log.w(TAG, "🚨 Sending SOS SMS (BG: $bg)")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val location = fetchLocation(appContext)
                        sendSms(appContext, phoneNumber, bg, delta, iob, location)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed SMS send in background", e)
                    }
                }
                
                with(prefs.edit()) {
                    putLong(KEY_LAST_ACTION_TIME, nowMs)
                    putBoolean(KEY_LAST_ACTION_WAS_SMS, true)
                    apply()
                }
            } else if (doCallNow) {
                // Last action was SMS -> Make a Call
                Log.w(TAG, "🚨 Initiating SOS Call (BG: $bg)")
                makeCall(appContext, phoneNumber)
                
                with(prefs.edit()) {
                    putLong(KEY_LAST_ACTION_TIME, nowMs)
                    putBoolean(KEY_LAST_ACTION_WAS_SMS, false)
                    apply()
                }
            }
        } else {
             val remain = (FOLLOWUP_INTERVAL_MS - (nowMs - lastActionTime)) / 60000
             Log.d(TAG, "SOS Active. Next escalation in $remain minutes.")
        }
    }

    private fun resetSosState(prefs: android.content.SharedPreferences) {
        with(prefs.edit()) {
            putLong(KEY_FIRST_BELOW_THRESHOLD_TIME, 0L)
            putLong(KEY_LAST_ACTION_TIME, 0L)
            putBoolean(KEY_LAST_ACTION_WAS_SMS, false)
            apply()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation(context: Context): Location? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location == null) location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location == null) location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            location
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GPS location", e)
            null
        }
    }

    private fun sendSms(context: Context, phone: String, bg: Double, delta: Double, iob: Double, location: Location?) {
        val locationString = location?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "Position indisponible"
        val message = """
            🚨 SOS HYPO SÉVÈRE 🚨
            BG: ${bg.toInt()} mg/dL
            Tendance: ${String.format("%.1f", delta)}
            IOB: ${String.format("%.2f", iob)}U
            Position: $locationString
        """.trimIndent()
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager?.divideMessage(message)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager?.sendTextMessage(phone, null, message, null, null)
            }
            Log.w(TAG, "✅ SMS sent successfully to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "❌ SMS sending failed", e)
        }
    }

    private fun makeCall(context: Context, phone: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("android.phone.extra.forceSpeaker", true) // attempt speakerphone
            }
            context.startActivity(intent)
            Log.w(TAG, "📞 Call attempted to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Call failed", e)
        }
    }
}
