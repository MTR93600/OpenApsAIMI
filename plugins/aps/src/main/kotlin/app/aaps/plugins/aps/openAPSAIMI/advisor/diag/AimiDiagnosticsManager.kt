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
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
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

        // 6. What TPO has written to the preferences above
        sb.append("[TPO SESSION]\n")
        appendTpoState(sb, allPrefs)
        sb.append("\n")

        // 7. Statistics (Simulé ou récupéré si dispo)
        // Note: Accéder aux vraies stats TDD/TIR nécessite des injections complexes (OverviewData/StatsProvider).
        // Pour cette version V1, on met un placeholder ou on essaie de lire des prefs cachées si elles existent.
        sb.append("[VITAL STATS]\n")
        // Exemple : Lire "avg_tdd" si stocké
        // sb.append("Average TDD: ${preferences.get(DoubleKey.AvgTdd)}\n") 
        sb.append("(Stats deep analysis requires DB access - available in V2)\n")

        return sb.toString()
    }

    /**
     * Writes what the time-period override (TPO) has done to the preferences printed above.
     *
     * TPO writes preference values from inside a loop tick, with no user action, and puts them back
     * when the session ends. Until this section existed, a support package showed only the value in
     * force at export time, so a setting that TPO had changed looked exactly like a setting the user
     * had changed, and a value left behind by a session that never reverted could not be told from a
     * deliberate choice.
     *
     * Three things are printed for every key a session touched: the value the user had
     * (`baseline`), the value the session wrote (`overlay`), and the value live right now. A key
     * marked `USER-OWNED` will **never** be put back, because the session saw it change while it was
     * running — that is the one path by which a TPO value becomes permanent.
     *
     * The absence of a session is stated in words rather than left out: an empty section would read
     * as "TPO did nothing", which is exactly the conclusion this section exists to stop anyone
     * drawing for free.
     */
    private fun appendTpoState(sb: StringBuilder, allPrefs: Map<String, Any?>) {
        val enabled = preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMITpoEnabled)
        sb.append("Enabled: ").append(enabled).append(" (default is on)\n")
        val persistence = try {
            TpoPersistence(AimiStorageHelper(context, logger))
        } catch (e: Exception) {
            logger.error(LTag.CORE, "TPO diagnostics: storage not reachable", e)
            sb.append("Session store could not be read, so nothing here can be ruled out.\n")
            return
        }
        val reverts = try {
            persistence.loadLastRevertAtMsByPack()
        } catch (e: Exception) {
            logger.error(LTag.CORE, "TPO diagnostics: revert map not readable", e)
            emptyMap()
        }
        if (reverts.isEmpty()) {
            sb.append("Last revert per pack: none recorded\n")
        } else {
            reverts.forEach { (pack, atMs) ->
                sb.append("Last revert ").append(pack.name).append(": ").append(formatMs(atMs)).append('\n')
            }
        }
        val session = try {
            persistence.loadSession()
        } catch (e: Exception) {
            logger.error(LTag.CORE, "TPO diagnostics: session not readable", e)
            sb.append("Session file present but unreadable.\n")
            return
        }
        if (session == null) {
            sb.append("No session stored. Any value above is either the user's own or was left by a\n")
            sb.append("session whose file is gone - the revert times above are the only trace left.\n")
            return
        }
        sb.append("Session: ").append(session.sessionId).append('\n')
        sb.append("Pack: ").append(session.packId.name).append("  tier: ").append(session.tier.name).append('\n')
        sb.append("Status: ").append(session.status.name).append('\n')
        sb.append("Started: ").append(formatMs(session.startedAtMs)).append('\n')
        sb.append("Expires: ").append(formatMs(session.expiresAtMs))
        val live = session.status == TpoSessionStatus.ACTIVE || session.status == TpoSessionStatus.PENDING_LLM
        if (live && System.currentTimeMillis() > session.expiresAtMs) {
            sb.append("  PAST ITS EXPIRY AND STILL WRITTEN - the loop has not reverted it")
        }
        sb.append('\n')
        sb.append("Trigger: ").append(session.triggerReasonCodes.joinToString(","))
            .append("  confidence: ").append(String.format(Locale.US, "%.2f", session.triggerAlgoConfidence)).append('\n')
        if (session.baseline.isEmpty() && session.overlay.isEmpty()) {
            sb.append("No key recorded on this session.\n")
            return
        }
        sb.append("Keys (user value -> written value | live now):\n")
        val keys = (session.baseline.keys + session.overlay.keys).toSortedSet()
        keys.forEach { key ->
            val owned = key in session.userOwnedKeys
            sb.append("  ").append(key).append(": ")
                .append(session.baseline[key] ?: "?").append(" -> ")
                .append(session.overlay[key] ?: "?").append(" | ")
                .append(allPrefs[key] ?: "absent")
            if (owned) sb.append("  USER-OWNED, will never be put back")
            sb.append('\n')
        }
    }

    /** One date format for every timestamp in this report, so two lines can be compared by eye. */
    private fun formatMs(atMs: Long): String =
        if (atMs <= 0L) "not set" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMs))

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
