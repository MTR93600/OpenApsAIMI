package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds and shares the AIMI Advisor support ZIP.
 *
 * Observation / tools only. File I/O on a background dispatcher. Not on the dose path.
 *
 * Package contents (from parked Advisor Activity + `origin/dev_OAPSAIMI` @ `02c90656b1`):
 * 1. `Diagnostic_Report.txt` including `[ACTIVE PROFILE]` from [ProfileFunction]
 * 2. `AIMI_Decisions_Last24h.jsonl` (last 24 hours, when the log can be read)
 * 3. tail of `oapsaimiML2_records.csv` (header + last 3000 rows)
 */
class AimiSupportPackageExporter(
    private val context: Context,
    private val preferences: Preferences,
    private val logger: AAPSLogger,
    private val storageHelper: AimiStorageHelper,
    private val profileFunction: ProfileFunction,
    private val rh: ResourceHelper,
) {

    sealed class Result {
        data class Ready(val zipFile: File) : Result()
        data object Empty : Result()
        data class Failed(val message: String?) : Result()
    }

    suspend fun build(issue: String): Result {
        return try {
            val diagManager = AimiDiagnosticsManager(context, preferences, logger)
            val runningProfile = runCatching { profileFunction.getProfile() }.getOrNull()
            val runningProfileName = runCatching { profileFunction.getProfileName() }.getOrNull()
            val reportContent = diagManager.generateReport(
                userMessage = issue,
                activeProfile = runningProfile,
                activeProfileName = runningProfileName,
            )
            val zipFileName = "AIMI_Support_Package_${aimiWallClockMs()}.zip"
            val zipFile = File(context.cacheDir, zipFileName)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
                if (reportContent.isNotEmpty()) {
                    out.putNextEntry(ZipEntry("Diagnostic_Report.txt"))
                    out.write(reportContent.toByteArray())
                    out.closeEntry()
                }
                addDecisionLogLast24h(out)
                addCsvTail(out, "oapsaimiML2_records.csv")
            }

            if (zipFile.exists() && zipFile.length() > 0) {
                Result.Ready(zipFile)
            } else {
                Result.Empty
            }
        } catch (e: Exception) {
            logger.error(LTag.APS, "AIMI_DIAG: Failed to generate/share report", e)
            Result.Failed(e.message)
        }
    }

    fun share(zipFile: File, issue: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, rh.gs(R.string.aimi_diag_subject, Date().toString()))
            putExtra(Intent.EXTRA_TEXT, "AIMI Support Package attached (ZIP).\n\nDetails: $issue")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, rh.gs(R.string.aimi_diag_chooser))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Adds the tail of an AIMI CSV to the support package, header first.
     *
     * A missing or unreadable file is skipped in silence. The package is a best effort report, and
     * failing to build it would leave the user with nothing to send.
     */
    private fun addCsvTail(out: ZipOutputStream, fileName: String) {
        try {
            val source = storageHelper.getAimiFile(fileName)
            if (!source.exists() || !source.canRead()) {
                logger.info(LTag.APS, "AIMI_DIAG: $fileName not found, not added to the package")
                return
            }
            val lines = source.readLines(Charsets.UTF_8)
            val tail = AimiSupportCsvTail.select(lines) ?: return
            out.putNextEntry(ZipEntry(fileName))
            out.write(AimiSupportCsvTail.toText(tail).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            logger.info(
                LTag.APS,
                "AIMI_DIAG: added $fileName to the package (${tail.body.size} rows of ${lines.size - 1})"
            )
        } catch (e: Exception) {
            logger.warn(LTag.APS, "AIMI_DIAG: could not add $fileName: ${e.message}")
        }
    }

    private fun addDecisionLogLast24h(out: ZipOutputStream) {
        val jsonFile = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile()
        if (!jsonFile.exists() || !jsonFile.canRead()) return
        out.putNextEntry(ZipEntry("AIMI_Decisions_Last24h.jsonl"))
        val cutoffTime = aimiWallClockMs() - (24 * 60 * 60 * 1000L)
        val reader = BufferedReader(FileReader(jsonFile))
        val writer = BufferedWriter(OutputStreamWriter(out))
        try {
            var line = reader.readLine()
            while (line != null) {
                if (AimiSupportDecisionLogFilter.keep(line, cutoffTime)) {
                    writer.write(line)
                    writer.newLine()
                }
                line = reader.readLine()
            }
            writer.flush()
        } finally {
            reader.close()
        }
        out.closeEntry()
    }
}
