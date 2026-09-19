package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Storage-port half of [TrainingCsvHeader]: rewrite the first line of a CSV on disk.
 *
 * Lives in androidMain because the SMB training writer still uses [AimiPath] directly on the tick
 * writer's own file handling (P0.7 left File I/O there). The rewrite rule itself is
 * [TrainingCsvHeader.apply] in commonMain.
 *
 * Any I/O problem is thrown to the caller, which is expected to log it and carry on: a header that
 * could not be fixed must never stop a row from being written.
 */
internal fun TrainingCsvHeader.ensureCurrent(storage: AimiStorage, path: AimiPath, headerLine: String): TrainingCsvHeader.Outcome {
    val applied = if (!storage.exists(path)) {
        storage.createParentDirectories(path)
        TrainingCsvHeader.apply(null, headerLine)
    } else {
        TrainingCsvHeader.apply(storage.readLines(path), headerLine)
    }
    if (applied.outcome != TrainingCsvHeader.Outcome.ALREADY_CURRENT) {
        storage.writeText(path, applied.lines.joinToString("\n") + "\n")
    }
    return applied.outcome
}
