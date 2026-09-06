package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.profile.Profile
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

    /**
     * Builds the support report.
     *
     * @param activeProfile the profile the loop is really running, from `ProfileFunction.getProfile()`.
     *   Pass it whenever it can be read. Without it the report only shows the `LocalProfile_*`
     *   preferences, which are the profile **editor's** content and can differ from what runs: on the
     *   2026-09-06 package they read 70 / 30 mg/dL per U while the loop was running 120 / 50.
     * @param activeProfileName name of that profile, when known.
     */
    fun generateReport(
        userMessage: String,
        activeProfile: Profile? = null,
        activeProfileName: String? = null,
    ): String {
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

        // 4. The profile the loop is really running
        sb.append("[ACTIVE PROFILE]\n")
        appendActiveProfile(sb, activeProfile, activeProfileName)
        sb.append("\n")

        // 5. AIMI Core Preferences
        sb.append("[AIMI PREFERENCES]\n")
        sb.append("Note: the LocalProfile_* keys below are the profile editor's content.\n")
        sb.append("They are not always what the loop runs. See [ACTIVE PROFILE] above.\n")
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
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
            if (AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey(key)) {
                isInteresting = false
            }

            if (isInteresting) {
                sb.append(key).append(": ").append(AimiDiagnosticsPrefExportPolicy.formatExportValue(key, value)).append('\n')
            }
        }
        sb.append("\n")

        // 6. Statistics (Simulé ou récupéré si dispo)
        // Note: Accéder aux vraies stats TDD/TIR nécessite des injections complexes (OverviewData/StatsProvider).
        // Pour cette version V1, on met un placeholder ou on essaie de lire des prefs cachées si elles existent.
        sb.append("[VITAL STATS]\n")
        // Exemple : Lire "avg_tdd" si stocké
        // sb.append("Average TDD: ${preferences.get(DoubleKey.AvgTdd)}\n") 
        sb.append("(Stats deep analysis requires DB access - available in V2)\n")

        return sb.toString()
    }

    /**
     * Writes the running profile, block by block, in mg/dL per U so no unit conversion can hide.
     *
     * Reads the same accessors the loop reads, so what is printed here is what the engine was
     * handed. A null profile is stated as such instead of being left out: an absent section would
     * read as "no profile problem", which is the mistake this whole section exists to stop.
     */
    private fun appendActiveProfile(sb: StringBuilder, profile: Profile?, name: String?) {
        if (profile == null) {
            sb.append("Not available when the report was built.\n")
            return
        }
        sb.append("Name: ").append(name ?: "unknown").append('\n')
        sb.append("Display units: ").append(profile.units).append('\n')
        sb.append("Percentage: ").append(profile.percentage).append("%\n")
        sb.append("Timeshift: ").append(profile.timeshift).append(" h\n")
        appendBlocks(sb, "ISF (mg/dL per U)", profile.getIsfsMgdlValues())
        appendBlocks(sb, "IC (g per U)", profile.getIcsValues())
        appendBlocks(sb, "Basal (U/h)", profile.getBasalValues())
        appendBlocks(sb, "Target (mg/dL)", profile.getSingleTargetsMgdl())
    }

    /** One line per quantity: every block as `hh:mm value`, in the profile's own order. */
    private fun appendBlocks(sb: StringBuilder, label: String, values: Array<Profile.ProfileValue>) {
        sb.append(label).append(": ")
        if (values.isEmpty()) {
            sb.append("none\n")
            return
        }
        values.forEachIndexed { index, block ->
            if (index > 0) sb.append(", ")
            val hours = block.timeAsSeconds / 3600
            val minutes = (block.timeAsSeconds % 3600) / 60
            sb.append(String.format(Locale.US, "%02d:%02d %.2f", hours, minutes, block.value))
        }
        sb.append('\n')
    }
}
