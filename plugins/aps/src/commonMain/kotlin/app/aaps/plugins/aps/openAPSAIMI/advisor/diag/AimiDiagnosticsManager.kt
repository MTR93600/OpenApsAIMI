package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiCsvTimestamp
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDiagPlatform
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

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
    private val logger: AAPSLogger,
    /** Where the TPO session file lives, for the `[TPO SESSION]` section. */
    private val storage: AimiStorage,
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

        // What TPO has written to the preferences above.
        sb.append("[TPO SESSION]\n")
        appendTpoState(sb, allPrefs)
        sb.append("\n")

        // The SMB ML trainer's own account of its last attempt: without this, "training does not
        // work" support tickets could not tell "waiting for data" from "a gate keeps rejecting it"
        // from "never even tried since the last restart".
        sb.append("[SMB ML TRAINING]\n")
        appendSmbTrainingState(sb)
        sb.append("\n")

        sb.append("[VITAL STATS]\n")
        sb.append("(Stats deep analysis requires DB access - available in V2)\n")

        return sb.toString()
    }

    /**
     * Writes what the time-period override (TPO) has done to the preferences printed above.
     *
     * TPO writes preference values from inside a loop tick, with no user action, and puts them back
     * when the session ends. Without this section a support package showed only the value in force at
     * export time, so a setting that TPO had changed looked exactly like a setting the user had
     * changed, and a value left behind by a session that never reverted could not be told from a
     * deliberate choice.
     *
     * For every key a session touched three values are printed: the value the user had (`baseline`),
     * the value the session wrote (`overlay`), and the value live right now. At revert time the
     * baseline comes back only when the live value is still the one the session wrote (see
     * `TpoRevertPolicy`). A key marked `DIVERGED EARLIER` was seen with another value during the
     * session; that mark is kept for diagnostics only and does not block the revert any more.
     *
     * When there is no session this is said in words rather than left out: an empty section would
     * read as "TPO did nothing", and that is the conclusion this section must not let anyone draw
     * for free.
     */
    private fun appendTpoState(sb: StringBuilder, allPrefs: Map<String, Any?>) {
        val enabled = preferences.get(BooleanKey.OApsAIMITpoEnabled)
        sb.append("Enabled: ").append(enabled).append(" (default is on)\n")
        val persistence = TpoPersistence(storage)
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
        // loadSession() also answers null for a file it cannot parse, so look at the file itself.
        if (session == null && persistence.hasSessionFile()) {
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
        if (live && aimiWallClockMs() > session.expiresAtMs) {
            sb.append("  PAST ITS EXPIRY AND STILL WRITTEN - the loop has not reverted it")
        }
        sb.append('\n')
        sb.append("Trigger: ").append(session.triggerReasonCodes.joinToString(","))
            .append("  confidence: ").append(aimiFmt2(session.triggerAlgoConfidence)).append('\n')
        if (session.baseline.isEmpty() && session.overlay.isEmpty()) {
            sb.append("No key recorded on this session.\n")
            return
        }
        sb.append("Keys (user value -> written value | live now):\n")
        val keys = (session.baseline.keys + session.overlay.keys).sorted()
        keys.forEach { key ->
            sb.append("  ").append(key).append(": ")
                .append(session.baseline[key] ?: "?").append(" -> ")
                .append(session.overlay[key] ?: "?").append(" | ")
                .append(allPrefs[key] ?: "absent")
            if (key in session.userOwnedKeys) sb.append("  DIVERGED EARLIER (diagnostic only, revert decides on the live value)")
            sb.append('\n')
        }
    }

    /**
     * Writes the SMB trainer's own record of its last attempt ([AimiSmbTrainer.lastResult]).
     *
     * The trainer used to fail silently: a support package showed only whether a weight file existed,
     * never why it had not been refreshed. This prints the outcome, the row counts and, for a rejected
     * candidate or a refused header, the measured value against its threshold, so a support ticket can
     * be answered from the report alone.
     */
    private fun appendSmbTrainingState(sb: StringBuilder) {
        val result = AimiSmbTrainer.lastResult()
        if (result == null) {
            sb.append("No attempt recorded since the app was last started (and none was persisted).\n")
            return
        }
        sb.append("Last attempt: ").append(formatMs(result.atMs)).append('\n')
        sb.append("Outcome: ").append(result.outcome.name).append('\n')
        sb.append("CSV rows: ").append(result.totalRows).append('\n')
        sb.append("Samples after quality filter: ").append(result.samplesAfterFilter)
            .append("  rejected by filter: ").append(result.rowsRejectedByFilter).append('\n')
        if (result.gateDetail.isNotBlank()) {
            sb.append("Detail: ").append(result.gateDetail).append('\n')
        }
    }

    /** One date format for every timestamp in this report, so two lines can be compared by eye. */
    private fun formatMs(atMs: Long): String = if (atMs <= 0L) "not set" else aimiCsvTimestamp(atMs)
}
