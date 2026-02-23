package app.aaps.plugins.aps.openAPSAIMI.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
 * 🚨 AIMI Emergency SOS Manager
 *
 * Handles the logic for sending emergency SMS during severe hypoglycemia.
 * Validates permissions, coordinates location fetch, formats message,
 * and maintains a 30-minute cooldown to prevent spam.
 *
 * @author MTR & Lyra AI
 */
object EmergencySosManager {

    private const val TAG = "AIMI_SOS_Manager"
    private const val COOLDOWN_PREFS = "aimi_sos_cooldown_prefs"
    private const val LAST_SMS_TIMESTAMP_KEY = "last_sos_sms_timestamp"
    private const val COOLDOWN_DURATION_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Evaluates the current condition and triggers SOS if threshold is breached.
     * This function is non-blocking (fires Coroutine).
     */
    fun evaluateSosCondition(bg: Double, delta: Double, iob: Double, context: Context, preferences: Preferences) {
        val appContext = context.applicationContext

        // 1. Check if feature is globally enabled
        val isSosEnabled = preferences.get(BooleanKey.AimiEmergencySosEnable)
        if (!isSosEnabled) return

        // 2. Evaluate Threshold
        val threshold = preferences.get(IntKey.AimiEmergencySosThreshold)
        if (bg > threshold || bg <= 10.0) { // Ignore absurdly low values (sensor errors)
            return
        }

        // 3. Evaluate Cooldown
        val prefs = appContext.getSharedPreferences(COOLDOWN_PREFS, Context.MODE_PRIVATE)
        val lastSmsTime = prefs.getLong(LAST_SMS_TIMESTAMP_KEY, 0L)
        val now = System.currentTimeMillis()

        if (now - lastSmsTime < COOLDOWN_DURATION_MS) {
            Log.d(TAG, "SOS Triggered but blocked by Cooldown. Time remaining: ${(COOLDOWN_DURATION_MS - (now - lastSmsTime)) / 1000}s")
            return
        }

        // 4. Validate Setup & Permissions
        val phoneNumber = preferences.get(StringKey.AimiEmergencySosPhone).trim()
        if (phoneNumber.isEmpty()) {
            Log.e(TAG, "SOS Triggered but no phone number configured.")
            return
        }

        if (!hasRequiredPermissions(appContext)) {
            Log.e(TAG, "SOS Triggered but permissions (Location/SMS) are missing.")
            return
        }

        // 5. Fire Async Task to Fetch Location & Send SMS
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.w(TAG, "🚨 CRITICAL HYPO ($bg < $threshold) - Initiating SOS Protocol...")
                prefs.edit().putLong(LAST_SMS_TIMESTAMP_KEY, now).apply() // Early save to prevent race condition

                val location = fetchLocation(appContext) // Can be null
                sendSms(appContext, phoneNumber, bg, delta, iob, location)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute SOS sequence", e)
                // If it failed drastically, reset the cooldown so it can try again
                prefs.edit().putLong(LAST_SMS_TIMESTAMP_KEY, 0L).apply()
            }
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val locFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        var bgLoc = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bgLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        return sms && locFine && bgLoc
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation(context: Context): Location? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Try GPS first
            var location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            
            // Fallback to network
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            // Fallback to passive
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }
            
            location
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch GPS location", e)
            null
        }
    }

    private fun sendSms(context: Context, phone: String, bg: Double, delta: Double, iob: Double, location: Location?) {
        val locationString = if (location != null) {
            "https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            "Position indisponible (GPS introuvable)"
        }

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

            // If message is too long, it needs to be divided
            val parts = smsManager?.divideMessage(message)
            if (parts != null && parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager?.sendTextMessage(phone, null, message, null, null)
            }
            
            Log.w(TAG, "✅ SOS SMS Sent Successfully to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send SMS", e)
            throw e
        }
    }
}
