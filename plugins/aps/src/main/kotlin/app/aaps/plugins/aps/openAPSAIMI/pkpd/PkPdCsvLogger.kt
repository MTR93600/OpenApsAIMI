package app.aaps.plugins.aps.openAPSAIMI.pkpd

import android.os.Environment
import android.util.Log
import java.io.File

data class PkPdLogRow(
    val dateStr: String,
    val epochMin: Long,
    val bg: Double,
    val delta5: Double,
    val iobU: Double,
    val carbsActiveG: Double,
    val windowMin: Int,
    val diaH: Double,
    val peakMin: Double,
    val fusedIsf: Double,
    val tddIsf: Double,
    val profileIsf: Double,
    val tailFrac: Double,
    val smbProposedU: Double,
    val smbFinalU: Double
)

object PkPdCsvLogger {
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val PATH = File(externalDir, "oapsaimi_pkpd_records.csv")
    private const val TAG = "PkPdCsvLogger"

    fun append(row: PkPdLogRow) {
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
                row.smbFinalU
            ).joinToString(",")

            //val file = File(PATH)
            PATH.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    error("Unable to create directory ${parent.absolutePath}")
                }
            }
            PATH.appendText(line + "\n")
        }

        appendResult.exceptionOrNull()?.let { throwable ->
            Log.w(TAG, "Unable to append PK/PD log row to $PATH", throwable)
        }
    }
}