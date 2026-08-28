package app.aaps.plugins.aps.openAPSAIMI.pkpd

import android.os.Environment
import android.util.Log
import java.io.File

object PkPdCsvLogger {
    private val defaultDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private const val TAG = "PkPdCsvLogger"
    @Volatile private var storageDirectoryOverride: File? = null

    fun configureStorageDirectory(directory: File?) {
        storageDirectoryOverride = directory
    }

    private fun currentPath(): File = File(storageDirectoryOverride ?: defaultDir, "oapsaimi_pkpd_records.csv")

    @JvmStatic
    fun append(row: PkPdLogRow) {
        val target = currentPath()
        val appendResult = runCatching {
            val line = listOf(
                row.dateStr,
                row.epochMin,
                row.bg,
                row.delta5,
                row.iobU,
                row.carbsActiveG,
                row.windowMin,
                row.diaH,
                row.peakMin,
                row.fusedIsf,
                row.tddIsf,
                row.profileIsf,
                row.tailFrac,
                row.smbProposedU,
                row.smbFinalU,
                row.tailMult,
                row.exerciseMult,
                row.lateFatMult,
                row.highBgOverride,
                row.lateFatRise,
                row.quantStepU,
                row.activityStage,
                row.activityRelief,
                row.activityFraction,
                row.anticipation
            ).joinToString(",")

            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    error("Unable to create directory ${parent.absolutePath}")
                }
            }
            target.appendText(line + "\n")
        }

        appendResult.exceptionOrNull()?.let { throwable ->
            Log.w(TAG, "Unable to append PK/PD log row to $target", throwable)
        }
    }
}
