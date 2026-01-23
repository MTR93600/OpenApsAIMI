package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.overview.OverviewData
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moteur de diagnostic sécurisé pour AIMI.
 * Gère l'authentification (Code Premium) et la génération du rapport "Black Box".
 */
class AimiDiagnosticsManager(
    private val context: Context,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {

    companion object {
        // Hash SHA-256 de "MTR-X-742-NEBULA" (Premium Expert Code)
        private const val SUPPORT_HASH = "7bb66c320fbc2e1c0e851eec23a171dcbd07ece4854bec29535822b25839323d"
        
        fun verifyCode(input: String): Boolean {
            val inputClean = input.trim()
            val hash = hashString(inputClean)
            // Comparaison time-constant pour éviter timing attacks (soyons pro)
            return constantTimeEquals(hash, SUPPORT_HASH)
        }

        private fun hashString(input: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray())
                .fold("") { str, it -> str + "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }
    }

    fun generateReport(userMessage: String): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.append("=========================================\n")
        sb.append("   AIMI DIAGNOSTIC REPORT - $now\n")
        sb.append("=========================================\n\n")

        // 1. User Message
        if (userMessage.isNotBlank()) {
            sb.append("[USER TICKET]\n")
            sb.append(userMessage).append("\n\n")
        }

        // 2. System Info
        sb.append("[SYSTEM]\n")
        var versionName = "Unknown"
        var versionCode = 0L
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = pInfo.versionName ?: "Unknown"
            versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            logger.error(LTag.CORE, "Error getting version info", e)
        }

        sb.append("App Version: $versionName ($versionCode)\n")
        sb.append("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")

        // 3. Nightscout (Safe)
        sb.append("[NIGHTSCOUT]\n")
        val nsUrl = preferences.get(app.aaps.core.keys.StringKey.NsClientUrl)
        // Obfuscation partielle de l'URL pour sécurité (masquer le token s'il est dans l'URL)
        val safeUrl = if (nsUrl.contains("@")) {
            val parts = nsUrl.split("@")
            "***SECRET***@" + (if (parts.size > 1) parts[1] else "???")
        } else {
            nsUrl.ifBlank { "Not Set" }
        }
        sb.append("URL: $safeUrl\n")
        val nsEnabled = preferences.get(app.aaps.core.keys.BooleanKey.NsClientUploadData)
        sb.append("Upload Enabled: $nsEnabled\n\n")

        // 4. AIMI Core Preferences
        sb.append("[AIMI PREFERENCES]\n")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val allPrefs = prefs.all
        
        // Liste des clés intéressantes (AIMI, APS, Constraints)
        val interestKeys = listOf("aimi", "aps", "smb", "max", "basal", "target", "profile", "opt_")
        
        allPrefs.keys.sorted().forEach { key ->
            val value = allPrefs[key]
            var isInteresting = false
            for (pattern in interestKeys) {
                if (key.contains(pattern, ignoreCase = true)) {
                    isInteresting = true
                    break
                }
            }
            
            // Exclusions de sécurité (PWD, WiFi, Tokens)
            if (key.contains("password", true) || key.contains("token", true) || key.contains("secret", true)) {
                 isInteresting = false
            }

            if (isInteresting) {
                sb.append("$key: $value\n")
            }
        }
        sb.append("\n")

        // 5. Statistics (Simulé ou récupéré si dispo)
        // Note: Accéder aux vraies stats TDD/TIR nécessite des injections complexes (OverviewData/StatsProvider).
        // Pour cette version V1, on met un placeholder ou on essaie de lire des prefs cachées si elles existent.
        sb.append("[VITAL STATS]\n")
        // Exemple : Lire "avg_tdd" si stocké
        // sb.append("Average TDD: ${preferences.get(DoubleKey.AvgTdd)}\n") 
        sb.append("(Stats deep analysis requires DB access - available in V2)\n")

        return sb.toString()
    }
}
