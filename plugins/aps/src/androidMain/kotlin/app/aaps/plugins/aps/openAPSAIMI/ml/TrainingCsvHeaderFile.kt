package app.aaps.plugins.aps.openAPSAIMI.ml

import java.io.File

/**
 * File half of [TrainingCsvHeader]: rewrite the first line of a CSV on disk.
 *
 * Lives in androidMain because the SMB training writer still uses `java.io.File` (P0.7 left File
 * I/O on the tick). The rewrite rule itself is [TrainingCsvHeader.apply] in commonMain.
 *
 * Any I/O problem is thrown to the caller, which is expected to log it and carry on: a header that
 * could not be fixed must never stop a row from being written.
 */
internal fun TrainingCsvHeader.ensureCurrent(file: File, headerLine: String): TrainingCsvHeader.Outcome {
    val applied = if (!file.exists()) {
        file.parentFile?.mkdirs()
        TrainingCsvHeader.apply(null, headerLine)
    } else {
        TrainingCsvHeader.apply(file.readLines(Charsets.UTF_8), headerLine)
    }
    if (applied.outcome != TrainingCsvHeader.Outcome.ALREADY_CURRENT) {
        file.writeText(applied.lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }
    return applied.outcome
}
