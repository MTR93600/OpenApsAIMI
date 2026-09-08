package app.aaps.plugins.aps.openAPSAIMI.ml

import java.io.File

/**
 * Keeps the first line of a training CSV equal to the header the writer builds today.
 *
 * The header used to be written only when the file was created, so a file that already existed kept
 * its first header for ever. That is how `oapsaimiML2_records.csv` ended up with a 13 name header
 * above rows of 33, 38 and then 39 fields: every reader that looks a column up by name then gets the
 * index of another column, and the SMB model was trained on `endogenousGlucoseDrive` instead of
 * `smbGiven`.
 *
 * The first line is replaced whenever it differs, with no condition on its old shape. This mirrors
 * `BasalNeuralLearner.ensureCsvSchema`, which has done the same on the basal corpus from the start;
 * the basal header never drifted, the SMB one did. It is safe because the SMB schemas are nested:
 * new columns are only ever appended at the end, so each older header is an exact prefix of the newer
 * one. An older, shorter data row therefore keeps every cell under the right name, and the columns it
 * does not have are read as absent, never as zero. Data rows are never rewritten, moved or deleted.
 */
internal object TrainingCsvHeader {

    /** What [ensureCurrent] did to the file. */
    enum class Outcome {
        /** The file did not exist, or was empty, and now holds only the header. */
        CREATED,

        /** The first line was already the wanted header. */
        ALREADY_CURRENT,

        /** The first line was replaced; every data row was kept as it was. */
        REPLACED,
    }

    /**
     * Makes the first line of [file] equal to [headerLine], keeping every data row untouched.
     *
     * [headerLine] is taken without its line break. Any I/O problem is thrown to the caller, which is
     * expected to log it and carry on: a header that could not be fixed must never stop a row from
     * being written.
     */
    fun ensureCurrent(file: File, headerLine: String): Outcome {
        val wanted = headerLine.trimEnd('\n')
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(wanted + "\n", Charsets.UTF_8)
            return Outcome.CREATED
        }
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) {
            file.writeText(wanted + "\n", Charsets.UTF_8)
            return Outcome.CREATED
        }
        if (lines.first().trimEnd('\r') == wanted) return Outcome.ALREADY_CURRENT
        file.writeText((listOf(wanted) + lines.drop(1)).joinToString("\n") + "\n", Charsets.UTF_8)
        return Outcome.REPLACED
    }
}
