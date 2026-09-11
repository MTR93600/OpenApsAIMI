package app.aaps.plugins.aps.openAPSAIMI.ml

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
 *
 * The rewrite rule itself is File-free so it can live in commonMain and be locked by commonTest.
 * The File wrapper is `TrainingCsvHeader.ensureCurrent` in androidMain, which is what the tick
 * writer calls.
 */
internal object TrainingCsvHeader {

    /** What [apply] / [ensureCurrent] did to the header. */
    enum class Outcome {
        /** The file did not exist, or was empty, and now holds only the header. */
        CREATED,

        /** The first line was already the wanted header. */
        ALREADY_CURRENT,

        /** The first line was replaced; every data row was kept as it was. */
        REPLACED,
    }

    /** Result of applying [headerLine] to the lines already stored (or to a missing file). */
    data class Applied(
        val outcome: Outcome,
        val lines: List<String>,
    )

    /**
     * Makes the first of [existingLines] equal to [headerLine], keeping every data row untouched.
     *
     * [existingLines] is `null` when the file does not exist. [headerLine] is taken without its
     * line break. This is the File-free half of the reference `TrainingCsvHeader.ensureCurrent`.
     */
    fun apply(existingLines: List<String>?, headerLine: String): Applied {
        val wanted = headerLine.trimEnd('\n')
        if (existingLines == null || existingLines.isEmpty()) {
            return Applied(outcome = Outcome.CREATED, lines = listOf(wanted))
        }
        if (existingLines.first().trimEnd('\r') == wanted) {
            return Applied(outcome = Outcome.ALREADY_CURRENT, lines = existingLines)
        }
        return Applied(
            outcome = Outcome.REPLACED,
            lines = listOf(wanted) + existingLines.drop(1),
        )
    }
}
