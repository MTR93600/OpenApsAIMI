package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shared "train a candidate -> validate -> atomically publish" core for the AIMI on-device trainers, so the SMB and
 * basal/T3C heads run one training/validation/publish protocol. They differ only in feature schema, label, and
 * publish strictness — expressed as parameters here — not in the mechanics. Persistence goes through
 * [AimiNeuralModelStore].
 *
 * **Publishing nothing is a valid and safe outcome.** Every gate below can end with no file on disk. The caller must
 * then fall back to its neutral / heuristic path. That is always safer than serving a model we could not prove is
 * alive.
 */
internal object NeuralModelTrainer {

    /** Lowest standard deviation we accept per feature. A constant column would otherwise divide by zero. */
    private const val MIN_INPUT_STD = 1e-6

    data class Split(
        val trainInputs: List<FloatArray>,
        val trainTargets: List<DoubleArray>,
        val valInputs: List<FloatArray>,
        val valTargets: List<DoubleArray>,
    )

    /** Chronology-preserving 80/20 split; if either side is empty it falls back to the train set (never empty). */
    fun split80_20(inputs: List<FloatArray>, targets: List<DoubleArray>): Split {
        val splitIdx = (inputs.size * 0.8).toInt().coerceAtLeast(1)
        val trainInputs = inputs.subList(0, splitIdx)
        val trainTargets = targets.subList(0, splitIdx)
        val valInputs = inputs.subList(splitIdx, inputs.size).takeIf { it.isNotEmpty() } ?: trainInputs
        val valTargets = targets.subList(splitIdx, targets.size).takeIf { it.isNotEmpty() } ?: trainTargets
        return Split(trainInputs, trainTargets, valInputs, valTargets)
    }

    /**
     * Per-feature mean and standard deviation of [rows], used to scale the network inputs.
     *
     * Raw AIMI features live on very different scales (bg in mg/dL, iob in units, flags in 0..1). Without scaling the
     * big columns dominate the gradient and the small ones are never learned, which is one way a head ends up
     * answering the same number for every input. Rows of the wrong length are skipped. An empty or degenerate set
     * gives mean 0 and std 1, which is the identity transform.
     */
    fun inputNormalizationStats(rows: List<FloatArray>, inputSize: Int): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(inputSize)
        val std = DoubleArray(inputSize) { 1.0 }
        val usable = rows.filter { it.size == inputSize }
        if (usable.isEmpty()) return mean to std

        for (row in usable) for (i in 0 until inputSize) mean[i] += row[i].toDouble()
        for (i in 0 until inputSize) mean[i] /= usable.size

        val variance = DoubleArray(inputSize)
        for (row in usable) for (i in 0 until inputSize) {
            val d = row[i].toDouble() - mean[i]
            variance[i] += d * d
        }
        for (i in 0 until inputSize) {
            val v = variance[i] / usable.size
            val s = sqrt(v)
            std[i] = if (s.isFinite() && s > MIN_INPUT_STD) s else 1.0
            if (!mean[i].isFinite()) mean[i] = 0.0
        }
        return mean to std
    }

    /**
     * Probe vectors that differ on exactly ONE feature: [spreadFeatureIndex] takes each value of [sweepValues] in
     * turn, and every other feature is held at its training median over [rows].
     *
     * One axis at a time is the whole point. A joint p10/p50/p90 vector mixes states that never happen together
     * (p90 bg with p90 iob), so a model that is completely blind to bg can still look responsive. Varying one axis
     * asks the only question that matters: does the output move when this feature moves?
     *
     * **[sweepValues] must be fixed clinical values, never percentiles of [rows].** The gate used to sweep the p10,
     * p50 and p90 of the training column itself. On real field data (one device, 24 h, 260 ticks) that window was
     * bg 79.2 .. 157.6, so 78.4 mg/dL wide: only 0.44x of the clinical span 70 .. 250 mg/dL (180 mg/dL). The gate
     * therefore understated that model's real bg response by about 2.3x, while the threshold it was compared against
     * is written in full-range multiplier units. On 10 patient-shaped seeds the per-patient window rejected 5 of 10
     * models that genuinely learned and accepted 1 of 10 pure-noise models.
     *
     * Two things follow. The same threshold means a different thing on every patient, so it cannot be tuned once.
     * And the window is NARROWEST for a well-controlled patient, so the old gate was loosest exactly where a wrong
     * model is hardest to notice.
     *
     * The values must also be the ones the runtime health probe uses (`BasalNeuralLearner.answersToBg`, which
     * sweeps `BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL` and drops the model when it does not answer). If
     * the two probes sample different windows, training can publish a model that its own loader then refuses:
     * training reports success, the runtime silently falls back to the heuristic, and no log line links the two.
     * That is a worse failure than either probe alone.
     *
     * An empty [sweepValues] falls back to the old p10/p50/p90 window. It is kept only so a measurement test can
     * compare the two windows on one model; [trainAndPublish] refuses to run the spread gate on it.
     *
     * Returns an empty list when the probe cannot be built (no rows, or index out of range).
     */
    fun spreadProbeVectors(
        rows: List<FloatArray>,
        inputSize: Int,
        spreadFeatureIndex: Int?,
        sweepValues: List<Double> = emptyList(),
    ): List<FloatArray> {
        val index = spreadFeatureIndex ?: return emptyList()
        if (index < 0 || index >= inputSize) return emptyList()
        val usable = rows.filter { it.size == inputSize }
        if (usable.isEmpty()) return emptyList()

        val column = FloatArray(usable.size)
        val base = FloatArray(inputSize)
        for (i in 0 until inputSize) {
            for (r in usable.indices) column[r] = usable[r][i]
            column.sort()
            base[i] = percentile(column, 0.5)
        }
        if (sweepValues.isNotEmpty()) {
            return sweepValues.map { value -> base.copyOf().also { it[index] = value.toFloat() } }
        }

        for (r in usable.indices) column[r] = usable[r][index]
        column.sort()
        return listOf(0.1, 0.5, 0.9).map { p ->
            base.copyOf().also { it[index] = percentile(column, p) }
        }
    }

    /** Nearest-rank percentile of an already sorted array. */
    private fun percentile(sorted: FloatArray, p: Double): Float {
        if (sorted.isEmpty()) return 0f
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * Mean absolute error of [predict] over the held-out rows, and the same number for the best CONSTANT
     * predictor, which is the median of the TRAINING labels. Both are measured on the same held-out rows, so
     * neither of them has seen them.
     *
     * Why this pair matters more than it looks. The spread probe was believed to tell a model that learned from a
     * model that only fitted noise. Measured on patient-shaped data with a fixed training seed, it does the
     * opposite: over 10 seeds the models trained on real signal moved by 0.073 .. 0.143 across the clinical bg
     * anchors, while the models trained on pure label noise moved by 0.159 .. 0.208 — MORE. A model with nothing to
     * fit ends up with a bigger apparent bg slope than a model that found the real, mostly flat function, because
     * early stopping picks its best epoch out of validation noise and the anchors then read that wandering shape.
     * So no spread threshold can separate the two: the orders overlap the wrong way round.
     *
     * The held-out error against the median does separate them, and by a wide margin: 0.476 .. 0.705 of the
     * baseline for the models that learned, 0.999 .. 1.052 for the noise models. That is the whole point of the
     * gate — a model that cannot beat "always answer the median label" has learned nothing we should dose on.
     *
     * MAE, not the training loss, on purpose: the median is the best constant under MAE, so the comparison is
     * against the strongest constant there is. Returns a pair of (model, baseline). A baseline of 0 means every
     * label was the same number, so there was nothing to learn.
     */
    fun heldOutMaeAgainstConstant(
        predict: (FloatArray) -> DoubleArray,
        split: Split,
    ): Pair<Double, Double> {
        if (split.valInputs.isEmpty() || split.trainTargets.isEmpty()) return Double.NaN to Double.NaN
        val outputs = split.trainTargets.first().size
        if (outputs == 0) return Double.NaN to Double.NaN

        val median = DoubleArray(outputs) { o ->
            val column = split.trainTargets.mapNotNull { it.getOrNull(o) }.sorted()
            if (column.isEmpty()) 0.0 else column[column.size / 2]
        }

        var modelError = 0.0
        var baselineError = 0.0
        var counted = 0
        for (i in split.valInputs.indices) {
            val target = split.valTargets.getOrNull(i) ?: continue
            val out = try {
                predict(split.valInputs[i])
            } catch (_: Exception) {
                return Double.NaN to Double.NaN
            }
            for (o in 0 until outputs) {
                val t = target.getOrNull(o) ?: continue
                val p = out.getOrNull(o) ?: return Double.NaN to Double.NaN
                modelError += abs(p - t)
                baselineError += abs(median[o] - t)
                counted++
            }
        }
        if (counted == 0) return Double.NaN to Double.NaN
        return modelError / counted to baselineError / counted
    }

    /**
     * Runs every "is this model alive?" gate on one predictor and returns true only if all of them pass.
     *
     * 1. **Finite probe** — the output on [probeInput] must be a real number.
     * 2. **Range probe** — if [outputRange] is given, that output must fall inside it. This runs whenever a range is
     *    given, bootstrap or not: a first model is not automatically a good model.
     * 3. **Spread probe** — if [spreadProbes] is not empty, the outputs across those one-axis probes must differ by
     *    at least [minOutputSpread]. This is the gate that catches a constant model, which passes the range probe
     *    whenever its constant happens to land in range. [minOutputSpread] must be given in the real unit of the
     *    label (for basal, a fraction of a scale factor), because a spread of 0.001 beats a naive "not constant"
     *    test while being clinically the same as a constant. It also only means something once [spreadProbes] cover
     *    a FIXED window, see [spreadProbeVectors].
     *
     * [label] only names the model in the log lines ("candidate" / "incumbent").
     */
    fun passesPublishProbes(
        predict: (FloatArray) -> DoubleArray,
        label: String,
        inputSize: Int,
        probeInput: FloatArray?,
        outputRange: ClosedFloatingPointRange<Double>?,
        spreadProbes: List<FloatArray>,
        minOutputSpread: Double,
        log: (String) -> Unit = {},
    ): Boolean {
        val probe = probeInput?.takeIf { it.size == inputSize } ?: FloatArray(inputSize) { 0f }
        val probeOut = try {
            predict(probe)
        } catch (_: Exception) {
            log("$label probe failed to run — discard")
            return false
        }
        if (probeOut.isEmpty() || !probeOut.all { it.isFinite() }) {
            log("$label probe NaN/Inf — discard")
            return false
        }
        val probeVal = probeOut.first()
        if (outputRange != null && probeVal !in outputRange) {
            log("$label probe $probeVal outside $outputRange — discard")
            return false
        }

        if (spreadProbes.isEmpty() || minOutputSpread <= 0.0) return true

        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (vector in spreadProbes) {
            val out = try {
                predict(vector)
            } catch (_: Exception) {
                log("$label spread probe failed to run — discard")
                return false
            }
            if (out.isEmpty() || !out.first().isFinite()) {
                log("$label spread probe NaN/Inf — discard")
                return false
            }
            val v = out.first()
            if (v < min) min = v
            if (v > max) max = v
        }
        val spread = max - min
        if (spread < minOutputSpread) {
            log("$label answers almost the same for every input: spread $spread < $minOutputSpread — discard")
            return false
        }
        return true
    }

    /**
     * Train a fresh candidate on [split], validate it, and publish to [weightsFile] on success.
     *
     * Before training, the per-feature mean and standard deviation of `split.trainInputs` are installed on the
     * candidate, so training and later inference see the same scaling.
     *
     * A candidate publishes only if it passes the probes described in [passesPublishProbes] and, when
     * [maxBaselineMaeRatio] is set, only if it beats the best constant predictor on the held-out rows (see
     * [heldOutMaeAgainstConstant]). If
     * [requireIncumbentBeat] is set, the incumbent runs the very same probes first:
     * - an incumbent that fails them is **not** allowed to gate the candidate by validation loss, and its weight
     *   file (plus its `.bak`) is deleted, so a dead model cannot keep being served;
     * - the candidate still has to pass the probes on its own. Publishing nothing is the safe outcome.
     *
     * @param probeInput optional sanity-check input for the range probe. When it is not given the training centroid
     *   is used. It must never fall back to an all-zero vector: zero is not a state a patient can be in (it means
     *   bg 0), so a well trained model may legitimately answer a negative dose there and be thrown away for it.
     * @param outputRange if non-null, the probe output must fall inside it (rejects degenerate models). Enforced on
     *   the bootstrap run too — the caller must not pass null just because no model exists yet.
     * @param spreadFeatureIndex optional index of the feature to sweep for the spread probe (for basal, the bg column).
     * @param spreadSweepValues the fixed values that feature is set to for the spread probe (for basal, the clinical
     *   bg anchors). Required as soon as the spread gate is armed: an empty list is refused instead of falling back
     *   to the per-patient p10..p90 window, which measured a different window on every patient. See
     *   [spreadProbeVectors].
     * @param minOutputSpread minimum accepted output spread over [spreadSweepValues], in the label's own unit.
     * @param maxBaselineMaeRatio if greater than 0, the candidate's held-out mean absolute error must be at most
     *   this fraction of the best constant predictor's (0.95 = at least 5% better than always answering the median
     *   training label). 0 switches the check off, which is what the SMB head still does. This is the only gate
     *   measured to separate a model that learned from a model that fitted noise; the spread probe does not, see
     *   [heldOutMaeAgainstConstant].
     * @param requireIncumbentBeat if true, the candidate's best validation loss must not exceed the incumbent's by
     *   more than [valLossTolerance] (keep the better model); if false, any finite / in-range candidate publishes.
     * @param valLossTolerance a **tolerance**, not a hurdle: the candidate may be up to this factor WORSE than the
     *   incumbent and still publish (1.05 = up to 5% worse). It exists so a slightly noisier but fresher model can
     *   take over. It is not a "must beat by 5%" rule.
     * @param log optional diagnostic sink (reject reasons + publish); defaults to no-op.
     * @return the published network, or null if nothing was published.
     */
    fun trainAndPublish(
        weightsFile: File,
        split: Split,
        config: TrainingConfig,
        inputSize: Int,
        hiddenSize: Int = 8,
        regularizationLambda: Double = 0.01,
        outputRange: ClosedFloatingPointRange<Double>? = null,
        probeInput: FloatArray? = null,
        spreadFeatureIndex: Int? = null,
        spreadSweepValues: List<Double> = emptyList(),
        minOutputSpread: Double = 0.0,
        maxBaselineMaeRatio: Double = 0.0,
        requireIncumbentBeat: Boolean = false,
        valLossTolerance: Double = 1.0,
        log: (String) -> Unit = {},
    ): AimiNeuralNetwork? {
        if (split.trainInputs.isEmpty() || split.valInputs.isEmpty()) return null

        val spreadGateArmed = spreadFeatureIndex != null && minOutputSpread > 0.0
        if (spreadGateArmed && spreadSweepValues.isEmpty()) {
            log("no fixed sweep values for feature $spreadFeatureIndex — discard")
            return null
        }
        val spreadProbes = spreadProbeVectors(split.trainInputs, inputSize, spreadFeatureIndex, spreadSweepValues)
        if (spreadGateArmed && spreadProbes.isEmpty()) {
            log("cannot build the spread probe for feature $spreadFeatureIndex — discard")
            return null
        }

        // Feature scaling is needed by the candidate, and its mean doubles as the point the range probe reads.
        val (mean, std) = inputNormalizationStats(split.trainInputs, inputSize)

        // The range probe has to read the model somewhere a patient could actually be. An all-zero vector is not
        // such a place, so the centroid of the training rows stands in when the caller gives no probe.
        val effectiveProbe = probeInput?.takeIf { it.size == inputSize }
            ?: FloatArray(inputSize) { i -> mean[i].toFloat() }

        // The incumbent must prove it is alive before it is allowed to block anything.
        val incumbent = if (requireIncumbentBeat) AimiNeuralModelStore.load(weightsFile, inputSize) else null
        val incumbentUsable = incumbent != null && passesPublishProbes(
            predict = incumbent::predict,
            label = "incumbent ${weightsFile.name}",
            inputSize = inputSize,
            probeInput = effectiveProbe,
            outputRange = outputRange,
            spreadProbes = spreadProbes,
            minOutputSpread = minOutputSpread,
            log = log,
        )
        if (incumbent != null && !incumbentUsable) {
            log("incumbent ${weightsFile.name} is dead — remove it and train from scratch")
            AimiNeuralModelStore.delete(weightsFile)
        }
        val incumbentLoss = if (incumbentUsable) incumbent!!.validate(split.valInputs, split.valTargets) else Double.MAX_VALUE

        val candidate = AimiNeuralNetwork(inputSize, hiddenSize, 1, config, regularizationLambda)
        candidate.setInputNormalization(mean, std)
        candidate.trainWithValidation(split.trainInputs, split.trainTargets, split.valInputs, split.valTargets, log = log)

        val candidateOk = passesPublishProbes(
            predict = candidate::predict,
            label = "candidate",
            inputSize = inputSize,
            probeInput = effectiveProbe,
            outputRange = outputRange,
            spreadProbes = spreadProbes,
            minOutputSpread = minOutputSpread,
            log = log,
        )
        if (!candidateOk) return null

        // The candidate must be worth more than "always answer the median label". Only the candidate is checked:
        // an incumbent that cannot beat the constant cannot block a candidate that can, because the validation
        // loss comparison below already prefers the better fit on the very same held-out rows.
        if (maxBaselineMaeRatio > 0.0) {
            val (candidateMae, baselineMae) = heldOutMaeAgainstConstant(candidate::predict, split)
            if (!candidateMae.isFinite() || !baselineMae.isFinite()) {
                log("cannot measure the held-out error of ${weightsFile.name} — discard")
                return null
            }
            if (baselineMae <= 0.0) {
                log("every training label is the same number, there is nothing to learn — discard")
                return null
            }
            val ratio = candidateMae / baselineMae
            if (ratio > maxBaselineMaeRatio) {
                log("candidate does not beat the median label: mae=$candidateMae baseline=$baselineMae ratio=$ratio > $maxBaselineMaeRatio — discard")
                return null
            }
        }

        if (incumbentUsable) {
            val candidateLoss = candidate.lastBestValidationLoss()
            if (!candidateLoss.isFinite()) return null
            val maxAllowed = incumbentLoss * valLossTolerance + 1e-6
            if (candidateLoss > maxAllowed) {
                log("reject ${weightsFile.name} val=$candidateLoss > incumbent=$incumbentLoss (tol=$valLossTolerance)")
                return null
            }
        } else if (requireIncumbentBeat && !candidate.lastBestValidationLoss().isFinite()) {
            return null
        }

        if (!AimiNeuralModelStore.save(weightsFile, candidate)) {
            log("atomic save failed for ${weightsFile.name}")
            return null
        }
        log("published ${weightsFile.name}")
        return candidate
    }
}
