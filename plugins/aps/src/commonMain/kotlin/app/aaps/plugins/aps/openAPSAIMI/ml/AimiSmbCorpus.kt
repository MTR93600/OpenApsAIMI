package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.math.exp

/**
 * File-free SMB corpus reading: header guard and row → training sample.
 *
 * Extracted from `AimiSmbTrainer` (`origin/dev_OAPSAIMI` @ `6c0c0285ff`) so the contract can live
 * in commonMain and be locked by commonTest (`kotlin.test`). The Android trainer still owns
 * persistence, coroutines, and `refine()`. Formulas are copied as they were; none are invented.
 */
internal object AimiSmbCorpus {

    /** The rows the trainer accepted, in the shape the training pipeline expects. */
    data class TrainingCorpus(
        val inputs: List<FloatArray>,
        val targets: List<DoubleArray>,
    )

    /** Verdict on a stored header: whether the trainer may read the file, and why not when it may not. */
    data class HeaderCheck(val valid: Boolean, val reason: String)

    /**
     * Checks a stored CSV header against [SmbRefinementFeatureSchema.trainingCsvColumnNames].
     *
     * The trainer looks its label up by name, so a header that lists fewer columns than the rows carry
     * silently returns the index of another column. That is what happened in production: a 13 name
     * header put `smbGiven` at index 12, where a real row holds `endogenousGlucoseDrive`, a score
     * bounded by 1. The model then learned a hormonal score while believing it learned insulin units.
     *
     * The header is accepted only when `smbGiven` sits exactly where the schema puts it, and when every
     * name up to and including it matches the schema. Columns after the label are not checked here:
     * they are read by name with a bounds-safe lookup, so an older, shorter header stays usable.
     */
    fun checkCorpusHeader(headers: List<String>): HeaderCheck {
        val expected = SmbRefinementFeatureSchema.trainingCsvColumnNames
        val expectedTargetIndex = SmbRefinementFeatureSchema.targetColumnIndex
        val foundTargetIndex = headers.indexOf(SmbRefinementFeatureSchema.TARGET_COLUMN_NAME)

        if (foundTargetIndex != expectedTargetIndex) {
            return HeaderCheck(
                valid = false,
                reason = "'${SmbRefinementFeatureSchema.TARGET_COLUMN_NAME}' expected at index " +
                    "$expectedTargetIndex, found at index $foundTargetIndex " +
                    "(stored header has ${headers.size} columns, schema has ${expected.size})",
            )
        }

        for (index in 0..expectedTargetIndex) {
            val stored = headers.getOrNull(index)
            if (stored != expected[index]) {
                return HeaderCheck(
                    valid = false,
                    reason = "column $index is '$stored', schema expects '${expected[index]}'",
                )
            }
        }

        return HeaderCheck(valid = true, reason = "")
    }

    /**
     * Turns the stored header and data lines into a training corpus, or returns `null` when the
     * corpus cannot be read safely.
     *
     * A row shorter than the header is kept: the schemas are nested, so an older row holds its cells
     * under the right names and the columns it lacks read as absent. A row longer than the header is
     * dropped, because it cannot be lined up with any column: the production file holds one such row,
     * 65 fields wide, made of two writes that got interleaved.
     */
    fun buildTrainingCorpus(
        headers: List<String>,
        dataLines: List<String>,
        minLearningQuality: Float = SmbRefinementFeatureSchema.MIN_TRAINING_LEARNING_QUALITY,
        relaxedFilter: Boolean = true,
    ): TrainingCorpus? {
        val headerCheck = checkCorpusHeader(headers)
        if (!headerCheck.valid) return null
        val targetIndex = SmbRefinementFeatureSchema.targetColumnIndex
        val originOwnerIndex = headers.indexOf(ORIGIN_OWNER_COLUMN)

        val inputs = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()

        for (line in dataLines) {
            val cols = line.split(",").map { it.trim() }
            if (cols.size <= targetIndex) continue
            if (cols.size > headers.size) continue

            val raw = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols) ?: continue
            if (!SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, cols, raw, minLearningQuality, relaxedFilter)) continue

            // Approximate trendIndicator for offline training (copied from AimiSmbTrainer).
            val trendIndicator = computeTrendIndicator(raw)
            val enhanced = raw.copyOf(raw.size + 1).also { it[raw.size] = trendIndicator }

            val label = cols[targetIndex].toDoubleOrNull() ?: continue
            if (isDoseFromOutsideSmb(cols.getOrNull(originOwnerIndex), label)) continue
            targets.add(doubleArrayOf(label))
            inputs.add(enhanced)
        }

        return TrainingCorpus(inputs = inputs, targets = targets)
    }

    /** Column that names which SMB channel owned the dose of the tick. */
    const val ORIGIN_OWNER_COLUMN = "smbOriginOwner"

    /** Value of [ORIGIN_OWNER_COLUMN] when no SMB channel owned the dose. */
    const val NO_SMB_OWNER = "NONE"

    /**
     * True when the row's dose did not come from an SMB decision, so it must not teach the SMB model.
     *
     * A dose with no SMB owner is a prebolus (FCL, meal mode): on the 2026-10-04 → 10-15 corpus every such
     * row is a 3.0 U shot, at bg 85-115 and often falling. Kept as a label it teaches "flat or falling bg,
     * give 3 U". A row with no owner and no dose is a real "no SMB" answer and stays. An empty owner is an
     * older row written before the column existed: it stays too, because nothing says it is not an SMB.
     */
    fun isDoseFromOutsideSmb(originOwner: String?, label: Double): Boolean =
        label > 0.0 && originOwner?.trim() == NO_SMB_OWNER

    private fun computeTrendIndicator(raw: FloatArray): Float {
        // raw: [bg, iob, cob, delta, shortAvgDelta, longAvgDelta, ...]
        val bg           = raw.getOrElse(0) { 120f }.toDouble()
        val iob          = raw.getOrElse(1) { 0f }.toDouble()
        val delta        = raw.getOrElse(3) { 0f }
        val shortAvgDelta = raw.getOrElse(4) { 0f }
        val longAvgDelta  = raw.getOrElse(5) { 0f }
        val combinedDelta = (delta + shortAvgDelta + longAvgDelta) / 3f
        val stressScore   = if (bg > 150) 40.0 else 0.0
        val metabolicLoad = iob * 5.0
        val baseTrend = (combinedDelta * 5.0f) + (stressScore * 0.1).toFloat() - (metabolicLoad * 0.5).toFloat()
        val sig = (1f / (1f + exp(-baseTrend.toDouble()))).toFloat()
        return 0.5f + sig * 0.7f
    }
}
