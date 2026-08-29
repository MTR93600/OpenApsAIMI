package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.concurrent.Volatile

object PkPdCsvLogger {

    private const val FILE_NAME = "oapsaimi_pkpd_records.csv"

    @Volatile private var storage: AimiStorage? = null
    @Volatile private var log: AAPSLogger? = null

    /**
     * Wires the logger to the app's storage. Called once from `OpenAPSAIMIPlugin` at start-up.
     *
     * Before the port this took a `File` and fell back to a hard-coded
     * `Environment.getExternalStorageDirectory()/Documents/AAPS` when nothing had configured it.
     * That fallback cannot exist in shared code, so an unconfigured logger now drops the row and
     * says so, instead of writing to a path nobody asked for.
     */
    fun configureStorage(storage: AimiStorage?, log: AAPSLogger? = null) {
        this.storage = storage
        this.log = log
    }

    fun append(row: PkPdLogRow) {
        val storage = this.storage
        if (storage == null) {
            log?.warn(LTag.AIMI, "PkPdCsvLogger: no storage configured, dropping row")
            return
        }
        val target = storage.file(FILE_NAME)
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

        if (!storage.createParentDirectories(target)) {
            log?.warn(LTag.AIMI, "PkPdCsvLogger: cannot create directory for ${storage.displayPath(target)}")
            return
        }
        if (!storage.appendText(target, line + "\n")) {
            log?.warn(LTag.AIMI, "PkPdCsvLogger: unable to append PK/PD log row to ${storage.displayPath(target)}")
        }
    }
}
