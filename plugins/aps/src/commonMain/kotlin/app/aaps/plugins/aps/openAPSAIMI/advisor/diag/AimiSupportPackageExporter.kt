package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDiagPlatform
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Builds and shares the AIMI Advisor support ZIP, on Android and on iOS.
 *
 * Observation / tools only. Call [build] on a background dispatcher. Not on the dose path.
 *
 * The package content is shared ([AimiSupportPackageContent]). The platform parts come in from
 * outside:
 * - [platform]: app version, device and preferences for the report;
 * - [zipFactory]: where the file goes and how the ZIP bytes are written;
 * - [sharing]: the share sheet;
 * - [subjectTime]: the date text in the mail subject. Android keeps `java.util.Date().toString()`,
 *   so its subject line is unchanged.
 */
class AimiSupportPackageExporter(
    private val platform: AimiDiagPlatform,
    private val preferences: Preferences,
    private val logger: AAPSLogger,
    private val profileFunction: ProfileFunction,
    private val textResolver: TextResolver,
    private val storage: AimiStorage,
    private val sharing: AimiSharing,
    private val zipFactory: AimiSupportZipFactory,
    private val subjectTime: () -> String,
) {

    suspend fun build(issue: String): AimiSupportPackageResult {
        val reportContent = try {
            val diagManager = AimiDiagnosticsManager(platform, preferences, logger, storage)
            val runningProfile = runCatching { profileFunction.getProfile() }.getOrNull()
            val runningProfileName = runCatching { profileFunction.getProfileName() }.getOrNull()
            diagManager.generateReport(
                userMessage = issue,
                activeProfile = runningProfile,
                activeProfileName = runningProfileName,
            )
        } catch (e: Exception) {
            logger.error(LTag.APS, "AIMI_DIAG: Failed to generate/share report", e)
            return AimiSupportPackageResult.Failed(e.message)
        }
        return writePackage(zipFactory, reportContent, storage, logger, aimiWallClockMs())
    }

    /**
     * Hands the finished package to the platform's share sheet, with the same subject, covering
     * message and chooser title on both phones.
     */
    fun share(zip: AimiPath, issue: String) {
        sharing.shareFile(
            path = zip,
            mimeType = MIME_ZIP,
            subject = textResolver.gs(ApsStrings.aimi_diag_subject, subjectTime()),
            text = "AIMI Support Package attached (ZIP).\n\nDetails: $issue",
            chooserTitle = textResolver.gs(ApsStrings.aimi_diag_chooser),
        )
    }

    internal companion object {

        private const val MIME_ZIP = "application/zip"

        /**
         * Writes the package file and says what came of it. Split out of
         * [AimiSupportPackageExporter.build] so the ZIP part can be tested without a profile or a
         * report.
         *
         * - Any failure answers [AimiSupportPackageResult.Failed], and the partial file is removed
         *   ([AimiSupportZipOutput.abort]): it holds health data and must not be left behind.
         * - No entry at all answers [AimiSupportPackageResult.Empty] and leaves no file, on both
         *   phones. An archive with nothing in it is not worth sharing.
         */
        internal fun writePackage(
            zipFactory: AimiSupportZipFactory,
            report: String,
            storage: AimiStorage,
            logger: AAPSLogger,
            nowMs: Long,
        ): AimiSupportPackageResult {
            var out: AimiSupportZipOutput? = null
            return try {
                val zip = zipFactory.create("AIMI_Support_Package_$nowMs.zip")
                out = zip
                val entries = AimiSupportPackageContent.write(zip, report, storage, logger, nowMs)
                if (entries == 0) {
                    out = null
                    zip.abort()
                    logger.info(LTag.APS, "AIMI_DIAG: nothing to put in the support package")
                    return AimiSupportPackageResult.Empty
                }
                val size = zip.finish()
                if (size > 0) AimiSupportPackageResult.Ready(zip.path) else AimiSupportPackageResult.Empty
            } catch (e: Exception) {
                out?.abort()
                logger.error(LTag.APS, "AIMI_DIAG: Failed to generate/share report", e)
                AimiSupportPackageResult.Failed(e.message)
            }
        }
    }
}
