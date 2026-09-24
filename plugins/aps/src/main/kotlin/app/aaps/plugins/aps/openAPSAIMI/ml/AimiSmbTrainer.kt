package app.aaps.plugins.aps.openAPSAIMI.ml

import android.util.Log
import androidx.annotation.VisibleForTesting
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AimiSmbTrainer — Singleton managing the ML model lifecycle for SMB refinement.
 *
 * Safety contracts:
 *  - refine() is always O(1): fallback to predictedSmb on any error
 *  - training runs on Dispatchers.IO, never on the hot-path thread
 *  - circuit breaker disables ML for 6h after 3 consecutive failures
 *  - ML correction is clamped to ±min(0.05U, 25% of predictedSmb)
 */
object AimiSmbTrainer {

    private const val TAG = "AimiSmbTrainer"

    // Input dimension: 10 base features + 4 latent physio features + 3 patient-mode features +
    // 3 causal-context features + 1 trendIndicator
    const val INPUT_SIZE = SmbRefinementFeatureSchema.INPUT_SIZE

    // Training rate limit
    private const val TRAIN_INTERVAL_MS  = 6 * 60 * 60 * 1000L  // 6h
    private const val MIN_NEW_ROWS_TO_RETRAIN = 200

    /**
     * If the last training ATTEMPT (successful or not) is older than this, attempt again even when
     * [MIN_NEW_ROWS_TO_RETRAIN] has not been reached.
     *
     * A user whose CGM reads every 5 min (not every 1 min, like the maintainer's) writes about 288
     * rows/day, so reaching 200 new rows can take most of a day even on a healthy corpus — and the
     * in-memory attempt counter used to reset to 0 on every app restart, so a phone that restarts a
     * few times a day could go without ever reaching 200 again. 24h is long enough to never fire
     * during normal operation for a fast-CGM user, and short enough that a stalled trainer is never
     * silently stuck for more than a day. Mirrors `BasalMlTrainingCoordinator.STALE_TRAINING_MS`.
     */
    private const val STALE_ATTEMPT_MS = 24L * 60 * 60 * 1000L // 24h

    /** Smallest number of post-filter samples a training attempt needs to run at all. */
    private const val MIN_TRAINING_SAMPLES = 10

    /** File holding [lastAttemptMs] / [lastTrainMs] / [rowsAtLastTrain] / the last [TrainingResult], across restarts. */
    private const val STATE_FILE_NAME = "smb_ml_training_state.json"

    /**
     * A persisted or in-memory timestamp more than this far in the future is treated as invalid (clock
     * was wrong once, or a backup was restored from another device) and reset to 0, in both
     * [shouldAttempt] and [loadPersistedState].
     *
     * Without this, `now - lastAttemptMs` is negative and stays below [TRAIN_INTERVAL_MS] forever — no
     * attempt until the real clock catches up to the bad value, possibly months, and now that
     * [lastAttemptMs] is persisted, one bad clock reading becomes permanent across restarts. A small
     * positive tolerance (not exactly 0) allows for ordinary clock drift between the moment a value was
     * written and the moment it is compared.
     */
    private const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60 * 1000 // 5 min

    /** Index of the bg column in the SMB feature vector (`SmbRefinementFeatureSchema` lists it first). */
    private const val BG_FEATURE_INDEX = 0

    /**
     * Accepted output band for a published SMB model, in insulin units.
     *
     * The model predicts an SMB dose, not a multiplier, so the band is in units. The configured
     * maximum SMB sits well below the upper bound, which is there to drop an absurd or negative
     * answer rather than to shape therapy.
     */
    private val SMB_OUTPUT_RANGE = 0.0..5.0

    /**
     * Smallest bg response we accept from a published SMB model, in insulin units.
     *
     * It is set to the runtime correction clamp on purpose. `refine` only ever moves the dose by
     * `min(0.05 U, 25 % of the dose)`, so a model whose answer moves less than 0.05 U across the bg
     * anchors cannot change what the pump does — it can only add the same small offset to every dose.
     * That is the failure this gate exists to catch: the basal head shipped a constant model that ran
     * for 40 days on two devices because nothing checked whether the answer moved at all.
     */
    private const val SMB_MIN_OUTPUT_SPREAD = 0.05

    /**
     * A published model must beat the best constant predictor on the held-out rows by this factor.
     *
     * The spread probe alone cannot reject noise: over a wide bg sweep a model fitted to pure label
     * noise moves MORE than one that found the real function. Held-out error against the best constant
     * is what separates them.
     */
    private const val SMB_MAX_BASELINE_MAE_RATIO = 0.95

    // ---- State ---------------------------------------------------------------
    private val modelRef   = AtomicReference<AimiNeuralNetwork?>(null)
    private val trainMutex = Mutex()
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Circuit breaker (shared component)
    private val circuitBreaker = TrainingCircuitBreaker()

    // Training rate limit / bootstrap-and-staleness state, persisted across app restarts (see
    // [loadPersistedState] / [persistState]). [lastTrainMs] is the last SUCCESSFUL publish, unchanged
    // from before; [lastAttemptMs] is new — the last time an attempt actually RAN past the gates below,
    // whether or not it published a model. Point 2 of the SMB training spec keys the rate limit and the
    // 24h staleness trigger on the attempt, not the success, so a run of rejected candidates does not
    // freeze the clock.
    private val lastAttemptMs   = AtomicLong(0L)
    private val lastTrainMs     = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)

    /**
     * Outcome of the most recent training ATTEMPT that really ran the gates: [TrainingOutcome.TRAINED],
     * [TrainingOutcome.REJECTED_BY_GATES], [TrainingOutcome.TOO_FEW_SAMPLES],
     * [TrainingOutcome.REFUSED_HEADER], [TrainingOutcome.SKIPPED_NO_CSV] or [TrainingOutcome.ERROR].
     * Persisted, and shown on the dashboard / support report as the answer to "why does training not
     * work". Never set to [TrainingOutcome.SKIPPED_NOT_DUE] or [TrainingOutcome.CIRCUIT_OPEN] — those
     * are "nothing happened, still waiting" states that would otherwise overwrite a real rejection
     * reason on every idle loop tick (up to ~18h/day) and erase it from both memory and disk. See
     * [currentWaitingStatusRef] for that case.
     */
    private val lastAttemptResultRef = AtomicReference<TrainingResult?>(null)

    /**
     * Why the trainer is not attempting right now: [TrainingOutcome.SKIPPED_NOT_DUE] or
     * [TrainingOutcome.CIRCUIT_OPEN]. In-memory only, NEVER persisted, and never a substitute for
     * [lastAttemptResultRef] — it supplements it (dashboard: "Last attempt: ... / Now: waiting, ...").
     */
    private val currentWaitingStatusRef = AtomicReference<TrainingResult?>(null)

    /** Set once the weights trained on an unreadable corpus have been thrown away. */
    private val staleModelDiscarded = AtomicBoolean(false)

    /**
     * Guards [loadPersistedState] to run exactly once, whichever of [loadModel] or [trainNow] reaches it
     * first — see [ensureStateLoadedLocked].
     */
    private val stateLoaded = AtomicBoolean(false)

    /** Why a training attempt ended, kept in [TrainingResult] for the dashboard and the support report. */
    enum class TrainingOutcome {
        /** A candidate passed every gate and was published. */
        TRAINED,

        /** Not due yet: rate limit, and neither enough new rows nor a stale-enough last attempt. */
        SKIPPED_NOT_DUE,

        /** No CSV file to read. */
        SKIPPED_NO_CSV,

        /** The stored CSV header does not match the schema — refused instead of training on the wrong column. */
        REFUSED_HEADER,

        /** Fewer than [MIN_TRAINING_SAMPLES] rows survived the quality filter. */
        TOO_FEW_SAMPLES,

        /** A candidate was trained but did not pass the liveness / accuracy gates, so nothing was published. */
        REJECTED_BY_GATES,

        /** The failure circuit breaker is open; no attempt was made. */
        CIRCUIT_OPEN,

        /** An exception was thrown while training. */
        ERROR,
    }

    /**
     * Snapshot of one training attempt: what happened, and — for a result the user or a developer needs to
     * explain — enough numbers to explain it without reading logcat.
     *
     * @param gateDetail for [TrainingOutcome.REJECTED_BY_GATES] and [TrainingOutcome.REFUSED_HEADER], the
     *   measured value against its threshold (for example "spread 0.021 < 0.050"), taken from the same
     *   message the shared training pipeline already logs. Empty when not applicable.
     */
    data class TrainingResult(
        val atMs: Long,
        val outcome: TrainingOutcome,
        val totalRows: Long = 0L,
        val samplesAfterFilter: Int = 0,
        val rowsRejectedByFilter: Long = 0L,
        val gateDetail: String = "",
    )

    /** Decision returned by [shouldAttempt]: whether to run, why, and the row counter corrected for a shrunk CSV. */
    internal data class TrainingDecision(
        val attempt: Boolean,
        val reason: String,
        val effectiveRowsAtLastTrain: Long,
    )

    /**
     * Whether a training attempt should run now, given only in-memory numbers — no file I/O, so it is
     * fully unit-testable.
     *
     * Order of the gates:
     * 1. Rate limit: less than [TRAIN_INTERVAL_MS] since the last ATTEMPT never runs, bootstrap or not —
     *    point 1 of the spec keeps this gate for the very first training too.
     * 2. Bootstrap: no model available yet (`!modelAvailable`) always attempts once the rate limit clears,
     *    regardless of new rows.
     * 3. Enough new rows ([MIN_NEW_ROWS_TO_RETRAIN]) always attempts — unchanged from before.
     * 4. Otherwise, a last attempt older than [STALE_ATTEMPT_MS] (24h) attempts anyway, so a slow-CGM user
     *    is never stuck waiting for 200 new rows that take a full day to accumulate.
     * 5. Otherwise, skip.
     *
     * [rowsAtLastTrain] is corrected to 0 first when [totalRows] fell below it (the CSV shrank — the
     * nightly `automateDeletionIfBadDay` trims it on a bad TIR day), so `totalRows - rowsAtLastTrain` can
     * never go negative. The caller must persist [TrainingDecision.effectiveRowsAtLastTrain] even when
     * the decision is to skip, so the correction is not lost.
     *
     * [lastAttemptMs] more than [CLOCK_SKEW_TOLERANCE_MS] in the future (a bad clock reading, or a
     * restored backup from another device) is treated as 0 ("never attempted"), so a single bad
     * timestamp cannot freeze training until the real clock catches up to it — see
     * [CLOCK_SKEW_TOLERANCE_MS].
     */
    internal fun shouldAttempt(
        nowMs: Long,
        lastAttemptMs: Long,
        rowsAtLastTrain: Long,
        totalRows: Long,
        modelAvailable: Boolean,
    ): TrainingDecision {
        val safeLastAttemptMs = if (lastAttemptMs > nowMs + CLOCK_SKEW_TOLERANCE_MS) 0L else lastAttemptMs
        val correctedRows = if (totalRows < rowsAtLastTrain) 0L else rowsAtLastTrain

        if (nowMs - safeLastAttemptMs < TRAIN_INTERVAL_MS) {
            return TrainingDecision(attempt = false, reason = "rate limit: last attempt too recent", effectiveRowsAtLastTrain = correctedRows)
        }
        if (!modelAvailable) {
            return TrainingDecision(attempt = true, reason = "bootstrap: no model available yet", effectiveRowsAtLastTrain = correctedRows)
        }

        val newRows = totalRows - correctedRows
        if (newRows >= MIN_NEW_ROWS_TO_RETRAIN) {
            return TrainingDecision(attempt = true, reason = "$newRows new rows since last train", effectiveRowsAtLastTrain = correctedRows)
        }
        if (nowMs - safeLastAttemptMs > STALE_ATTEMPT_MS) {
            return TrainingDecision(attempt = true, reason = "last attempt older than 24h ($newRows new rows)", effectiveRowsAtLastTrain = correctedRows)
        }
        return TrainingDecision(
            attempt = false,
            reason = "only $newRows new rows (need $MIN_NEW_ROWS_TO_RETRAIN), last attempt not stale",
            effectiveRowsAtLastTrain = correctedRows,
        )
    }

    // ---- Public API ----------------------------------------------------------

    /**
     * Load previously saved model from disk, and the persisted training state. Call once on plugin start.
     *
     * The state load is guarded by [ensureStateLoadedLocked] under [trainMutex] — same guard [trainNow]
     * uses — so whichever of the two runs first on a given app start performs the actual disk read, and
     * the other is a no-op. This closes the race where a training tick right after boot could decide on
     * default (zeroed) counters before this coroutine had a chance to load the real ones.
     */
    fun loadModel(dir: File) {
        scope.launch {
            trainMutex.withLock { ensureStateLoadedLocked(dir) }
            val net = AimiSmbModelStore.load(dir, INPUT_SIZE)
            modelRef.set(net)
            if (net != null) {
                Log.i(TAG, "Model loaded from disk (${INPUT_SIZE} inputs)")
            } else {
                Log.i(TAG, "No pre-trained model found — ML refinement inactive until first training")
            }
        }
    }

    /**
     * Fire-and-forget training trigger.
     * Respects the rate limit (6h since the last ATTEMPT) and the gates in [shouldAttempt].
     * Never blocks the caller.
     */
    fun maybeTrainAsync(dir: File, csvFile: File) {
        val now = System.currentTimeMillis()

        // Rate limit guard (fast path, no coroutine needed). This alone cannot tell bootstrap or staleness
        // apart from a plain "not due" skip — that needs the row count from the CSV — so it only ever
        // short-circuits the case every path in [shouldAttempt] agrees on: too soon since the last attempt.
        // Clamped the same way `shouldAttempt` clamps it (see [CLOCK_SKEW_TOLERANCE_MS]): a future value in
        // memory must not block this pre-check forever either.
        val rawLastAttempt = lastAttemptMs.get()
        val safeLastAttempt = if (rawLastAttempt > now + CLOCK_SKEW_TOLERANCE_MS) 0L else rawLastAttempt
        if (now - safeLastAttempt < TRAIN_INTERVAL_MS) return

        scope.launch {
            if (trainMutex.isLocked) return@launch  // Another training in progress
            trainMutex.withLock {
                try {
                    trainNow(dir, csvFile)
                } catch (e: Exception) {
                    recordFailure()
                    recordResult(dir, TrainingResult(atMs = System.currentTimeMillis(), outcome = TrainingOutcome.ERROR, gateDetail = e.message ?: e.toString()))
                    Log.e(TAG, "Training failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Outcome of the most recent training ATTEMPT (TRAINED / REJECTED_BY_GATES / TOO_FEW_SAMPLES /
     * REFUSED_HEADER / SKIPPED_NO_CSV / ERROR), or null if none has run since the app started (and none
     * was persisted). Never [TrainingOutcome.SKIPPED_NOT_DUE] or [TrainingOutcome.CIRCUIT_OPEN] — see
     * [currentWaitingStatus] for that.
     */
    fun lastResult(): TrainingResult? = lastAttemptResultRef.get()

    /**
     * Why the trainer is not attempting right now ([TrainingOutcome.SKIPPED_NOT_DUE] or
     * [TrainingOutcome.CIRCUIT_OPEN]), or null when the last thing that happened was a real attempt.
     * In-memory only: never persisted, and this is never shown IN PLACE of [lastResult] — only next to it.
     */
    fun currentWaitingStatus(): TrainingResult? = currentWaitingStatusRef.get()

    /** Epoch ms of the last completed training ATTEMPT (success or not), or 0 if none yet. Dashboard-facing. */
    fun lastAttemptAtMs(): Long = lastAttemptMs.get()

    /** Epoch ms of the last SUCCESSFUL training (model published), or 0 if none yet. Dashboard-facing. */
    fun lastTrainedAtMs(): Long = lastTrainMs.get()

    /** True while the training circuit breaker is currently open (recent failures cooling down). */
    fun isCircuitOpenNow(): Boolean = circuitBreaker.isOpen()

    /**
     * Refine [predictedSmb] using the in-memory model.
     *
     * - Returns [predictedSmb] unchanged if model is null, circuit is open,
     *   or any exception is thrown.
     * - Clamps the ML correction to ±min(0.05U, 25% of predictedSmb).
     */
    internal fun refine(
        predictedSmb: Float,
        features: FloatArray,
        behaviorProfile: AimiBehaviorRuntimeProfile? = null,
    ): Float {
        if (features.size != INPUT_SIZE) return predictedSmb

        val now = System.currentTimeMillis()
        if (isCircuitOpen(now)) return predictedSmb

        val model = modelRef.get() ?: return predictedSmb

        return try {
            val out = model.predict(features)

            val mlOut = out.firstOrNull()?.toFloat() ?: return predictedSmb
            if (!mlOut.isFinite()) return predictedSmb

            val maxDelta = correctionClamp(predictedSmb, behaviorProfile)
            val delta    = (mlOut - predictedSmb).coerceIn(-maxDelta, maxDelta)
            val refined  = predictedSmb + delta

            if (!refined.isFinite() || refined < 0f) predictedSmb else refined
        } catch (e: Exception) {
            recordFailure()
            Log.w(TAG, "refine() exception: ${e.message}")
            predictedSmb
        }
    }

    // ---- Internal training ---------------------------------------------------

    /**
     * `@VisibleForTesting`: this is the whole training pipeline, always run under [trainMutex] in
     * production via [maybeTrainAsync]. Widened to `internal` only so a test can call it directly and
     * await its result deterministically instead of racing the fire-and-forget coroutine
     * [maybeTrainAsync] launches. No production call site does this.
     */
    @VisibleForTesting
    internal suspend fun trainNow(dir: File, csvFile: File) {
        // Always the first thing this function does — see [ensureStateLoadedLocked]. Safe to call
        // unlocked here because every production call to `trainNow` already runs inside
        // `trainMutex.withLock` (its only caller is `maybeTrainAsync`); a test that calls it directly is
        // single-threaded and does not need the lock either.
        ensureStateLoadedLocked(dir)

        val now = System.currentTimeMillis()

        // CIRCUIT_OPEN and (below) SKIPPED_NOT_DUE are "nothing changed, still waiting" states that can
        // repeat on every loop tick for hours. They update `currentWaitingStatusRef` only — in memory,
        // never persisted, and never overwriting `lastAttemptResultRef` — so a busy waiting period does
        // not erase a real rejection reason, nor turn into a disk write every few minutes. Every outcome
        // that means an attempt actually ran the gates below (from [TrainingOutcome.SKIPPED_NO_CSV]
        // onward) is a REAL result: persisted via `recordResult`.
        if (isCircuitOpen(now)) {
            Log.d(TAG, "Circuit breaker open — skip training")
            currentWaitingStatusRef.set(TrainingResult(atMs = now, outcome = TrainingOutcome.CIRCUIT_OPEN))
            return
        }

        if (!csvFile.exists()) {
            Log.d(TAG, "CSV not found — skip training")
            recordResult(dir, TrainingResult(atMs = now, outcome = TrainingOutcome.SKIPPED_NO_CSV))
            return
        }

        val allLines = csvFile.readLines()
        val dataLines = allLines.drop(1).filter { it.isNotBlank() }
        val totalRows = dataLines.size.toLong()

        // Point 1: the 200-new-rows requirement does not apply while no model is loaded AND no model
        // file exists yet — the very first training attempt must not wait on a row count that can only
        // grow after training has already run once.
        val modelAvailable = modelRef.get() != null || AimiSmbModelStore.modelFile(dir).exists()

        val decision = shouldAttempt(
            nowMs = now,
            lastAttemptMs = lastAttemptMs.get(),
            rowsAtLastTrain = rowsAtLastTrain.get(),
            totalRows = totalRows,
            modelAvailable = modelAvailable,
        )
        // The row-counter correction (CSV shrank below rowsAtLastTrain — the nightly bad-day deletion
        // can do this) must be kept even when this attempt is skipped, or the negative-newRows condition
        // would just recur on every call until enough rows are re-accumulated.
        if (decision.effectiveRowsAtLastTrain != rowsAtLastTrain.get()) {
            rowsAtLastTrain.set(decision.effectiveRowsAtLastTrain)
            persistState(dir)
        }
        if (!decision.attempt) {
            Log.d(TAG, "Skip training: ${decision.reason}")
            // In-memory only, not persisted: this path can run on every loop tick for hours while the
            // corpus is still growing toward MIN_NEW_ROWS_TO_RETRAIN or STALE_ATTEMPT_MS, and a "still not
            // due" result is not worth a disk write every few minutes. `decision.reason` (e.g. "only 88 new
            // rows (need 200), last attempt not stale") is kept as the detail so the dashboard's "Now:
            // waiting" line can say why, not just that it is waiting.
            currentWaitingStatusRef.set(
                TrainingResult(atMs = now, outcome = TrainingOutcome.SKIPPED_NOT_DUE, totalRows = totalRows, gateDetail = decision.reason),
            )
            return
        }

        // This attempt is really running past the gates now: mark it, so the rate limit and the 24h
        // staleness trigger both measure from here, whatever the outcome below turns out to be. Clear the
        // waiting status too — it would otherwise show a stale "waiting" reason while this real attempt runs.
        lastAttemptMs.set(now)
        currentWaitingStatusRef.set(null)
        persistState(dir)

        val headers = allLines.firstOrNull()?.split(",")?.map { it.trim() }
        if (headers == null) {
            Log.d(TAG, "CSV has no header — skip training")
            recordResult(dir, TrainingResult(atMs = now, outcome = TrainingOutcome.SKIPPED_NO_CSV, totalRows = totalRows))
            return
        }
        val headerCheck = checkCorpusHeader(headers)
        if (!headerCheck.valid) {
            Log.e(TAG, "SMB corpus refused — no training. ${headerCheck.reason}")
            discardModelTrainedOnUnreadableCorpus(dir)
            recordResult(dir, TrainingResult(atMs = now, outcome = TrainingOutcome.REFUSED_HEADER, totalRows = totalRows, gateDetail = headerCheck.reason))
            return
        }
        val corpus = buildTrainingCorpus(headers, dataLines)
        if (corpus == null) {
            recordResult(dir, TrainingResult(atMs = now, outcome = TrainingOutcome.REFUSED_HEADER, totalRows = totalRows, gateDetail = "corpus could not be built"))
            return
        }
        val inputs = corpus.inputs
        val targets = corpus.targets
        val rejectedByFilter = totalRows - inputs.size

        if (inputs.size < MIN_TRAINING_SAMPLES) {
            Log.w(TAG, "Insufficient training samples (${inputs.size}) — skip")
            recordResult(
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.TOO_FEW_SAMPLES,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rejectedByFilter,
                    gateDetail = "${inputs.size} samples < $MIN_TRAINING_SAMPLES required",
                ),
            )
            return
        }

        Log.i(TAG, "Training on ${inputs.size} samples…")

        // Single-pass train → probe-validate → atomic publish via the shared pipeline.
        //
        // The liveness gates below used to be off for this head, so it could publish a model that
        // answers the same value for every input. Measured: trained on a single constant label it
        // published, with a spread of 0.0125 U over random inputs.
        //
        // `requireIncumbentBeat` stays off on purpose. Comparing against the model on disk is what
        // froze the basal head for 40 days: a dead incumbent anchored the comparison and every later
        // candidate was dropped. The liveness probes are the safe way to keep a bad model out; a
        // val-loss ratchet is not.
        //
        // The gate-rejection detail shown on the dashboard / support report (point 4 of the spec) is
        // read off the very last line this callback receives: every rejection path in
        // `NeuralModelTrainer.trainAndPublish` logs its "spread x < y" / "mae ratio" reason immediately
        // before returning null, so capturing the last message is equivalent to capturing the reason
        // without changing `trainAndPublish`'s signature or behaviour for the basal caller.
        var lastGateMessage = ""
        val net = NeuralModelTrainer.trainAndPublish(
            weightsFile = AimiSmbModelStore.modelFile(dir),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.001, epochs = 300),
            inputSize = INPUT_SIZE,
            regularizationLambda = 0.01,
            outputRange = SMB_OUTPUT_RANGE,
            spreadFeatureIndex = BG_FEATURE_INDEX,
            spreadSweepValues = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL,
            minOutputSpread = SMB_MIN_OUTPUT_SPREAD,
            maxBaselineMaeRatio = SMB_MAX_BASELINE_MAE_RATIO,
            log = { msg -> Log.i(TAG, msg); lastGateMessage = msg },
        )
        if (net != null) {
            modelRef.set(net)
            lastTrainMs.set(System.currentTimeMillis())
            rowsAtLastTrain.set(totalRows)
            persistState(dir)
            circuitBreaker.reset()   // reset circuit breaker on success
            Log.i(TAG, "Model trained and saved successfully (${inputs.size} rows)")
            recordResult(
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.TRAINED,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rejectedByFilter,
                ),
            )
        } else {
            // RULING R-CB: a candidate rejected by the gates must not disable the model already in
            // service. `recordFailure` feeds the circuit breaker, which (after 3 failures) makes `refine`
            // return the raw dose for 6h — i.e. switches a GOOD incumbent off because of a candidate that
            // never got near production. The incumbent already proved itself: it passed these same gates
            // when it was published. Only count this as a circuit-breaker failure when there is no model
            // in service — the pre-24h-trigger case this breaker was built for, where a rejection really
            // does mean "still nothing to serve". The 6h gap before the next retry (`TRAIN_INTERVAL_MS`,
            // now keyed on `lastAttemptMs` set just above) is unchanged from the rest of this change; the
            // old code retried on every tick after a rejection (rate limit keyed on last SUCCESS) — that
            // is not restored here, only the circuit-breaker coupling is fixed.
            if (modelRef.get() == null) {
                recordFailure()
            }
            recordResult(
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.REJECTED_BY_GATES,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rejectedByFilter,
                    gateDetail = lastGateMessage,
                ),
            )
        }
    }

    // ---- Corpus reading ------------------------------------------------------

    /** The rows the trainer accepted, in the shape the training pipeline expects. */
    internal data class TrainingCorpus(
        val inputs: List<FloatArray>,
        val targets: List<DoubleArray>,
    )

    /** Verdict on a stored header: whether the trainer may read the file, and why not when it may not. */
    internal data class HeaderCheck(val valid: Boolean, val reason: String)

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
    internal fun checkCorpusHeader(headers: List<String>): HeaderCheck {
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
    internal fun buildTrainingCorpus(headers: List<String>, dataLines: List<String>): TrainingCorpus? {
        val headerCheck = checkCorpusHeader(headers)
        if (!headerCheck.valid) {
            Log.e(TAG, "SMB corpus refused — no training. ${headerCheck.reason}")
            return null
        }
        val targetIndex = SmbRefinementFeatureSchema.targetColumnIndex

        val inputs = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()

        for (line in dataLines) {
            val cols = line.split(",").map { it.trim() }
            if (cols.size <= targetIndex) continue
            if (cols.size > headers.size) continue

            val raw = SmbRefinementFeatureSchema.parseTrainingFeatures(headers, cols) ?: continue
            if (!SmbRefinementFeatureSchema.shouldUseCsvRowForTraining(headers, cols, raw)) continue

            // Approximate trendIndicator for offline training
            val trendIndicator = computeTrendIndicator(raw)
            val enhanced = raw.copyOf(raw.size + 1).also { it[raw.size] = trendIndicator }

            targets.add(doubleArrayOf(cols[targetIndex].toDoubleOrNull() ?: continue))
            inputs.add(enhanced)
        }

        return TrainingCorpus(inputs = inputs, targets = targets)
    }

    // ---- Helpers -------------------------------------------------------------

    /**
     * Throws away the stored SMB weights, once, after the corpus guard refused the file.
     *
     * The weights on disk were fitted against whatever column the stale header pointed at, so they
     * answer in the wrong unit and `refine` keeps using them until a training run succeeds. Clearing
     * [modelRef] and deleting the weight file sends `refine` back to returning `predictedSmb`
     * unchanged, which is already what it does when no model is loaded.
     *
     * It runs at most once per app start: a corpus that stays unreadable must not turn into a delete
     * on every tick.
     */
    private fun discardModelTrainedOnUnreadableCorpus(dir: File) {
        if (!staleModelDiscarded.compareAndSet(false, true)) return
        modelRef.set(null)
        val removed = AimiSmbModelStore.delete(dir)
        Log.w(
            TAG,
            "SMB weights discarded: they were trained on an unreadable corpus. " +
                "Weight file removed=$removed. refine() now returns the rule-based dose until a " +
                "training run on a readable corpus publishes new weights.",
        )
    }

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

    internal fun correctionClamp(
        predictedSmb: Float,
        behaviorProfile: AimiBehaviorRuntimeProfile? = null,
    ): Float {
        val baseClamp = min(0.05f, predictedSmb * 0.25f).coerceAtLeast(0f)
        val multiplier = behaviorProfile?.mlCorrectionFractionMultiplier() ?: 1.0f
        return (baseClamp * multiplier).coerceAtLeast(0f)
    }


    private fun isCircuitOpen(now: Long): Boolean = circuitBreaker.isOpen(now)

    private fun recordFailure() {
        if (circuitBreaker.recordFailure()) {
            Log.w(TAG, "Circuit breaker OPEN — ML disabled for 6h after ${TrainingCircuitBreaker.DEFAULT_MAX_FAILURES} consecutive failures")
        }
    }

    // ---- Persistence -----------------------------------------------------

    private fun stateFile(dir: File): File = File(dir, STATE_FILE_NAME)

    /**
     * Loads the persisted state exactly once, whichever of [loadModel] or [trainNow] reaches this first
     * on a given app start — [stateLoaded] makes every later call a no-op.
     *
     * The caller MUST already hold [trainMutex] (or be a single-threaded test): this function does not
     * take the lock itself, because [trainNow] is already called from inside
     * `trainMutex.withLock { trainNow(...) }` in [maybeTrainAsync], and [Mutex] is not reentrant —
     * acquiring it again in here would deadlock that path. [loadModel] takes the lock explicitly around
     * its own call to this function instead.
     *
     * This closes the restart race I3: without it, `loadModel`'s coroutine and the first
     * `maybeTrainAsync` tick could interleave freely, so a training decision could run on the zeroed
     * in-memory defaults before the persisted state had a chance to load, and the persisted (older,
     * correct) values could then overwrite a fresh in-memory attempt right after it ran.
     */
    private fun ensureStateLoadedLocked(dir: File) {
        if (stateLoaded.compareAndSet(false, true)) {
            loadPersistedState(dir)
        }
    }

    /**
     * Loads [lastAttemptMs], [lastTrainMs], [rowsAtLastTrain] and the last [TrainingResult] from
     * [STATE_FILE_NAME] in [dir], same mechanism (JSON file next to the model) as
     * `BasalMlTrainingCoordinator.loadPersistedState`. Missing or unreadable file leaves the in-memory
     * defaults (all zero / null), which is the same as "never trained" — safe, since it only makes the
     * next attempt run a little sooner, never later.
     *
     * Two safety nets, per the I3/I4 review findings:
     * - A loaded [lastAttemptMs] or [lastTrainMs] more than [CLOCK_SKEW_TOLERANCE_MS] in the future is
     *   reset to 0 rather than trusted — a bad clock reading must not freeze training permanently once
     *   persisted.
     * - Neither timestamp is ever moved BACKWARDS by a load: the larger of the current in-memory value
     *   and the loaded one wins. In production this is exactly [ensureStateLoadedLocked]'s "exactly
     *   once, before any decision" guarantee, so the two should never actually differ — this is defense
     *   in depth for a caller that (unlike production) calls this function directly, such as a test.
     *
     * `@VisibleForTesting`: normally reached only through [ensureStateLoadedLocked]; exposed `internal`
     * so a test can prove the round trip directly instead of racing [loadModel]'s coroutine.
     */
    @VisibleForTesting
    internal fun loadPersistedState(dir: File) {
        val file = stateFile(dir)
        if (!file.exists()) return
        try {
            val now = System.currentTimeMillis()
            val json = JSONObject(file.readText())
            val loadedAttemptMs = sanitizeTimestamp(json.optLong("lastAttemptMs", 0L), now)
            val loadedTrainMs = sanitizeTimestamp(json.optLong("lastTrainMs", 0L), now)
            lastAttemptMs.set(maxOf(lastAttemptMs.get(), loadedAttemptMs))
            lastTrainMs.set(maxOf(lastTrainMs.get(), loadedTrainMs))
            rowsAtLastTrain.set(json.optLong("rowsAtLastTrain", 0L))
            if (lastAttemptResultRef.get() == null) {
                json.optJSONObject("lastResult")?.let { r ->
                    val outcome = runCatching { TrainingOutcome.valueOf(r.optString("outcome")) }.getOrNull()
                    if (outcome != null) {
                        lastAttemptResultRef.set(
                            TrainingResult(
                                atMs = r.optLong("atMs", 0L),
                                outcome = outcome,
                                totalRows = r.optLong("totalRows", 0L),
                                samplesAfterFilter = r.optInt("samplesAfterFilter", 0),
                                rowsRejectedByFilter = r.optLong("rowsRejectedByFilter", 0L),
                                gateDetail = r.optString("gateDetail", ""),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load SMB training state: ${e.message}")
        }
    }

    /** [ms] clamped to 0 when it lies more than [CLOCK_SKEW_TOLERANCE_MS] past [nowMs]. See I4. */
    private fun sanitizeTimestamp(ms: Long, nowMs: Long): Long = if (ms > nowMs + CLOCK_SKEW_TOLERANCE_MS) 0L else ms

    /**
     * Writes the current state to [STATE_FILE_NAME] in [dir]: write to a sibling `.tmp` file, then
     * `renameTo` the target. A same-directory rename is atomic on the filesystems this runs on, so a
     * kill mid-write leaves either the previous file intact or the new one — never a truncated,
     * unparseable one (which [loadPersistedState] would otherwise silently read as "never trained").
     * Same pattern `AimiNeuralModelStore.save` already uses for the weight files.
     */
    @VisibleForTesting
    internal fun persistState(dir: File) {
        try {
            val file = stateFile(dir)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("lastAttemptMs", lastAttemptMs.get())
                .put("lastTrainMs", lastTrainMs.get())
                .put("rowsAtLastTrain", rowsAtLastTrain.get())
            lastAttemptResultRef.get()?.let { result ->
                json.put(
                    "lastResult",
                    JSONObject()
                        .put("atMs", result.atMs)
                        .put("outcome", result.outcome.name)
                        .put("totalRows", result.totalRows)
                        .put("samplesAfterFilter", result.samplesAfterFilter)
                        .put("rowsRejectedByFilter", result.rowsRejectedByFilter)
                        .put("gateDetail", result.gateDetail)
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Could not rename SMB training state tmp file into place")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist SMB training state: ${e.message}")
        }
    }

    /**
     * Records [result] as the last real training outcome (see [lastAttemptResultRef]), in memory and on
     * disk. Skips the disk write when [result] carries the same outcome/counters/detail as what is
     * already persisted (only the timestamp differs) — otherwise a "no CSV yet" or a repeatedly-rejected
     * candidate with the SAME gate message would write the state file on every attempt forever, for a
     * user for whom nothing has actually changed.
     */
    private fun recordResult(dir: File, result: TrainingResult) {
        val previous = lastAttemptResultRef.get()
        lastAttemptResultRef.set(result)
        val unchanged = previous != null &&
            previous.outcome == result.outcome &&
            previous.totalRows == result.totalRows &&
            previous.samplesAfterFilter == result.samplesAfterFilter &&
            previous.rowsRejectedByFilter == result.rowsRejectedByFilter &&
            previous.gateDetail == result.gateDetail
        if (unchanged) return
        persistState(dir)
    }

    /**
     * Test-only: resets every piece of shared state back to "just started". [AimiSmbTrainer] is a
     * singleton object, so its state otherwise leaks between test cases (and test classes, within the
     * same JVM). Never called from production code.
     */
    @VisibleForTesting
    internal fun resetForTest() {
        lastAttemptMs.set(0L)
        lastTrainMs.set(0L)
        rowsAtLastTrain.set(0L)
        lastAttemptResultRef.set(null)
        currentWaitingStatusRef.set(null)
        modelRef.set(null)
        staleModelDiscarded.set(false)
        stateLoaded.set(false)
        circuitBreaker.reset()
    }

    /** Test-only: current [rowsAtLastTrain], which otherwise has no public getter. */
    @VisibleForTesting
    internal fun rowsAtLastTrainForTest(): Long = rowsAtLastTrain.get()

    /**
     * Test-only: sets the three persisted counters directly, so their persistence (I5) and the
     * shrunk-CSV correction (through [trainNow], not just the pure [shouldAttempt]) can be tested without
     * depending on a real — stochastic, unseeded — successful training run to produce non-zero values.
     * Never called from production code.
     */
    @VisibleForTesting
    internal fun setPersistedStateForTest(lastAttemptMs: Long = 0L, lastTrainMs: Long = 0L, rowsAtLastTrain: Long = 0L) {
        this.lastAttemptMs.set(lastAttemptMs)
        this.lastTrainMs.set(lastTrainMs)
        this.rowsAtLastTrain.set(rowsAtLastTrain)
    }

    /**
     * Test-only: sets (or clears) the in-memory model, so R-CB (a rejected candidate must not disable an
     * incumbent already in service) can be tested without depending on a real — stochastic, unseeded —
     * successful training run to populate [modelRef]. Never called from production code.
     */
    @VisibleForTesting
    internal fun setModelForTest(net: AimiNeuralNetwork?) {
        modelRef.set(net)
    }
}
