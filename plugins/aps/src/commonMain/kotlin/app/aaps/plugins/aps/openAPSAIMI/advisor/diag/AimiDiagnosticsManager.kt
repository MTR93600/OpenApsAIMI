package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiCsvTimestamp
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDiagPlatform

/**
 * Secure diagnostic engine for AIMI.
 * Handles authentication (Premium Expert Code) and the "black box" support report.
 *
 * Unparked from `_docs/kmp/staging/openAPSAIMI-android-wip/advisor/diag/` and updated with
 * `origin/dev_OAPSAIMI` @ `02c90656b1` ([ACTIVE PROFILE] via [ProfileFunction][app.aaps.core.interfaces.profile.ProfileFunction]).
 *
 * Platform details (app version, device, raw preferences, SHA-256) come from [AimiDiagPlatform];
 * everything else is shared. Observation / tools only. Not on the dose path.
 */
class AimiDiagnosticsManager(
    private val platform: AimiDiagPlatform,
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {

    companion object {
        // SHA-256 of "MTR-X-742-NEBULA" (Premium Expert Code)
        private const val SUPPORT_HASH = "7bb66c320fbc2e1c0e851eec23a171dcbd07ece4854bec29535822b25839323d"

        fun verifyCode(input: String, platform: AimiDiagPlatform): Boolean {
            val inputClean = input.trim()
            val hash = platform.sha256Hex(inputClean)
            return constantTimeEquals(hash, SUPPORT_HASH)
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
        val now = aimiCsvTimestamp()

        sb.append("=========================================\n")
        sb.append("   AIMI DIAGNOSTIC REPORT - $now\n")
        sb.append("=========================================\n\n")

        if (userMessage.isNotBlank()) {
            sb.append("[USER TICKET]\n")
            sb.append(userMessage).append("\n\n")
        }

        sb.append("[SYSTEM]\n")
        sb.append("App Version: ${platform.appVersionName()} (${platform.appVersionCode()})\n")
        sb.append("${platform.osInfo()}\n")
        sb.append("Device: ${platform.deviceInfo()}\n\n")

        sb.append("[NIGHTSCOUT]\n")
        val nsUrl = preferences.get(StringKey.NsClientUrl)
        val safeUrl = if (nsUrl.contains("@")) {
            val parts = nsUrl.split("@")
            "***SECRET***@" + (if (parts.size > 1) parts[1] else "???")
        } else {
            nsUrl.ifBlank { "Not Set" }
        }
        sb.append("URL: $safeUrl\n")
        val nsEnabled = preferences.get(BooleanKey.NsClientUploadData)
        sb.append("Upload Enabled: $nsEnabled\n\n")

        AimiDiagnosticsActiveProfile.writeSection(sb, activeProfile, activeProfileName)
        AimiDiagnosticsActiveProfile.writePreferencesPreamble(sb)
        val allPrefs = try {
            platform.allPreferences()
        } catch (e: Exception) {
            logger.error(LTag.CORE, "Error reading preferences", e)
            emptyMap()
        }

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

        sb.append("[VITAL STATS]\n")
        sb.append("(Stats deep analysis requires DB access - available in V2)\n")

        return sb.toString()
    }
}
