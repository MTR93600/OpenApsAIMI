package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min

/**
 * AimiSmbTrainer — Singleton managing the ML model lifecycle for SMB refinement.
 *
 * Safety contracts:
 *  - refine() is always O(1): fallback to predictedSmb on any error
 *  - training runs on aapsIoDispatcher, never on the hot-path thread
 *  - circuit breaker disables ML for 6h after 3 consecutive failures
 *  - ML correction is clamped to ±min(0.05U, 25% of predictedSmb)
 */
@OptIn(ExperimentalAtomicApi::class)
object AimiSmbTrainer {

    private const val TAG = "AimiSmbTrainer"

    // Input dimension: 10 base features + 4 latent physio features + 3 patient-mode features +
    // 3 causal-context features + 1 trendIndicator
    const val INPUT_SIZE = SmbRefinementFeatureSchema.INPUT_SIZE

    // Training rate limit and the 24h stale-attempt gate live in [AimiSmbTrainingSchedule].
    // The numbers are the reference trainer's (`6a6561caab`); this object only applies them.

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
    private val scope      = CoroutineScope(aapsIoDispatcher + SupervisorJob())

    // Circuit breaker (shared component)
    private val circuitBreaker = TrainingCircuitBreaker()

    // Training rate limit / bootstrap-and-staleness state, persisted across app restarts (see
    // [loadPersistedState] / [persistState]). [lastTrainMs] is the last SUCCESSFUL publish, unchanged
    // from before; [lastAttemptMs] is the last time an attempt actually RAN past the gates, whether or
    // not it published a model. The rate limit and the 24h staleness trigger key on the attempt, not
    // the success, so a run of rejected candidates does not freeze the clock.
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

    /**
     * Guards [loadPersistedState] to run exactly once, whichever of [loadModel] or [trainNow] reaches it
     * first.
     */
    private val stateLoaded = AtomicBoolean(false)

    /** Set once the weights trained on an unreadable corpus have been thrown away. */
    private val staleModelDiscarded = AtomicBoolean(false)

    // ---- Public API ----------------------------------------------------------

    /**
     * Load previously saved model from disk, and the persisted training state. Call once on plugin start.
     *
     * The state load is guarded by [ensureStateLoadedLocked] under [trainMutex] — same guard [trainNow]
     * uses — so whichever of the two runs first on a given app start performs the actual disk read, and
     * the other is a no-op.
     *
     * ⚠️ ASYNC IMPACT: runs on the existing `aapsIoDispatcher` scope. The mutex is held only for the state
     * read, then released before the weight load, so [trainNow] (already inside [trainMutex]) cannot
     * decide on zeroed counters, and this function does not re-enter the mutex.
     */
    fun loadModel(storage: AimiStorage, dir: AimiPath) {
        scope.launch {
            trainMutex.withLock { ensureStateLoadedLocked(storage, dir) }
            val net = AimiSmbModelStore.load(storage, dir, INPUT_SIZE)
            modelRef.store(net)
            if (net != null) {
                println("$TAG: Model loaded from disk (${INPUT_SIZE} inputs)")
            } else {
                println("$TAG: No pre-trained model found — ML refinement inactive until first training")
            }
        }
    }

    /**
     * Fire-and-forget training trigger.
     * Respects the rate limit (6h since the last ATTEMPT) and the gates in [AimiSmbTrainingSchedule.shouldAttempt].
     * Never blocks the caller.
     */
    fun maybeTrainAsync(storage: AimiStorage, dir: AimiPath, csvFile: AimiPath) {
        val now = aimiWallClockMs()

        // Rate limit guard (fast path, no coroutine needed). This alone cannot tell bootstrap or staleness
        // apart from a plain "not due" skip — that needs the row count from the CSV — so it only ever
        // short-circuits the case every path in [AimiSmbTrainingSchedule.shouldAttempt] agrees on: too soon
        // since the last attempt. Clamped the same way `shouldAttempt` clamps it: a future value in memory
        // must not block this pre-check forever either.
        val safeLastAttempt = AimiSmbTrainingSchedule.sanitizeTimestamp(lastAttemptMs.load(), now)
        if (now - safeLastAttempt < AimiSmbTrainingSchedule.TRAIN_INTERVAL_MS) return

        // Circuit breaker guard. "Still cooling down" is a waiting state, not a real attempt: it goes to
        // [currentWaitingStatusRef] only, in memory, so it never overwrites the persisted reason a real
        // attempt left behind and never turns into a disk write on every loop tick.
        if (isCircuitOpen(now)) {
            currentWaitingStatusRef.store(TrainingResult(atMs = now, outcome = TrainingOutcome.CIRCUIT_OPEN))
            return
        }

        scope.launch {
            if (trainMutex.isLocked) return@launch  // Another training in progress
            trainMutex.withLock {
                try {
                    trainNow(storage, dir, csvFile)
                } catch (e: Exception) {
                    recordFailure()
                    recordResult(
                        storage,
                        dir,
                        TrainingResult(
                            atMs = aimiWallClockMs(),
                            outcome = TrainingOutcome.ERROR,
                            gateDetail = e.message ?: e.toString(),
                        ),
                    )
                    println("$TAG: Training failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Outcome of the most recent training ATTEMPT ([TrainingOutcome.TRAINED],
     * [TrainingOutcome.REJECTED_BY_GATES], [TrainingOutcome.TOO_FEW_SAMPLES],
     * [TrainingOutcome.REFUSED_HEADER], [TrainingOutcome.SKIPPED_NO_CSV] or [TrainingOutcome.ERROR]), or
     * null if none has run since the app started and none was persisted. Never
     * [TrainingOutcome.SKIPPED_NOT_DUE] or [TrainingOutcome.CIRCUIT_OPEN] — see [currentWaitingStatus].
     */
    fun lastResult(): TrainingResult? = lastAttemptResultRef.load()

    /**
     * Why the trainer is not attempting right now ([TrainingOutcome.SKIPPED_NOT_DUE] or
     * [TrainingOutcome.CIRCUIT_OPEN]), or null when the last thing that happened was a real attempt.
     * In-memory only: never persisted, and this is never shown IN PLACE of [lastResult] — only next to it.
     */
    fun currentWaitingStatus(): TrainingResult? = currentWaitingStatusRef.load()

    /** Epoch ms of the last completed training ATTEMPT (success or not), or 0 if none yet. Dashboard-facing. */
    fun lastAttemptAtMs(): Long = lastAttemptMs.load()

    /** Epoch ms of the last SUCCESSFUL training (model published), or 0 if none yet. Dashboard-facing. */
    fun lastTrainedAtMs(): Long = lastTrainMs.load()

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

        val now = aimiWallClockMs()
        if (isCircuitOpen(now)) return predictedSmb

        val model = modelRef.load() ?: return predictedSmb

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
            println("$TAG: refine() exception: ${e.message}")
            predictedSmb
        }
    }

    // ---- Internal training ---------------------------------------------------

    /**
     * `@VisibleForTesting`: this is the whole training pipeline, always run under [trainMutex] in
     * production via [maybeTrainAsync]. Widened to `internal` only so a test can call it directly and
     * await its result instead of racing the fire-and-forget coroutine [maybeTrainAsync] launches. No
     * production call site does this.
     */
    internal suspend fun trainNow(storage: AimiStorage, dir: AimiPath, csvFile: AimiPath) {
        // Caller already holds [trainMutex] (`maybeTrainAsync`). Do not take it again: Mutex is not reentrant.
        ensureStateLoadedLocked(storage, dir)

        val startedAtMs = aimiWallClockMs()

        if (!storage.exists(csvFile)) {
            println("$TAG: CSV not found — skip training")
            recordResult(storage, dir, TrainingResult(atMs = startedAtMs, outcome = TrainingOutcome.SKIPPED_NO_CSV))
            return
        }

        val headerLine = storage.readFirstLine(csvFile)
        if (headerLine == null) {
            println("$TAG: CSV not found — skip training")
            // The reference read the whole file first, so it could report the row count here. This port
            // reads the header before walking the rows, so the count is not known yet and stays 0. The
            // outcome is the same; only that display number is missing.
            recordResult(storage, dir, TrainingResult(atMs = startedAtMs, outcome = TrainingOutcome.SKIPPED_NO_CSV))
            return
        }

        // Walked with forEachLine, not readLines: this CSV gains a row every loop tick and is never
        // truncated, so loading the whole file as a List<String> is an unbounded-memory read on a
        // journal that keeps growing. See AimiStorage.forEachLine.
        val dataLines = ArrayList<String>()
        var lineIndex = 0
        val walkedOk = storage.forEachLine(csvFile) { line ->
            lineIndex++
            if (lineIndex == 1) return@forEachLine // header, already read via readFirstLine
            if (line.isNotBlank()) dataLines.add(line)
        }
        if (!walkedOk) {
            println("$TAG: CSV walk failed — skip training")
            // No reference equivalent: the reference read the file in one go and let an I/O failure throw.
            // This port answers `false` instead, so the outcome that fits is ERROR — a real attempt that
            // could not finish. It does NOT feed the circuit breaker, exactly as before this reading existed.
            recordResult(
                storage,
                dir,
                TrainingResult(atMs = startedAtMs, outcome = TrainingOutcome.ERROR, gateDetail = "CSV walk failed"),
            )
            return
        }
        val totalRows = dataLines.size.toLong()

        val now = aimiWallClockMs()
        // No model in memory AND no weight file yet: the first attempt must not wait on 200 new rows.
        val modelAvailable = modelRef.load() != null ||
            storage.exists(AimiSmbModelStore.modelFile(storage, dir))
        val decision = AimiSmbTrainingSchedule.shouldAttempt(
            nowMs = now,
            lastAttemptMs = lastAttemptMs.load(),
            rowsAtLastTrain = rowsAtLastTrain.load(),
            totalRows = totalRows,
            modelAvailable = modelAvailable,
        )
        // The row-counter correction (CSV shrank below rowsAtLastTrain) must be kept even when this
        // attempt is skipped, or the negative-newRows condition would recur on every call.
        if (decision.effectiveRowsAtLastTrain != rowsAtLastTrain.load()) {
            rowsAtLastTrain.store(decision.effectiveRowsAtLastTrain)
            persistState(storage, dir)
        }
        if (!decision.attempt) {
            println("$TAG: Skip training: ${decision.reason}")
            // In-memory only, not persisted: this path can run on every loop tick for hours while the
            // corpus is still growing toward MIN_NEW_ROWS_TO_RETRAIN or STALE_ATTEMPT_MS, and a "still not
            // due" result is not worth a disk write every few minutes. `decision.reason` (for example
            // "only 88 new rows (need 200), last attempt not stale") is kept as the detail so a dashboard
            // "Now: waiting" line can say why, not just that it is waiting.
            currentWaitingStatusRef.store(
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.SKIPPED_NOT_DUE,
                    totalRows = totalRows,
                    gateDetail = decision.reason,
                ),
            )
            return
        }

        // This attempt is really running past the gates: the rate limit and the 24h trigger both
        // measure from here, whatever the outcome below turns out to be. Clear the waiting status too —
        // it would otherwise show a stale "waiting" reason while this real attempt runs.
        lastAttemptMs.store(now)
        currentWaitingStatusRef.store(null)
        persistState(storage, dir)

        val headers = headerLine.split(",").map { it.trim() }
        val headerCheck = AimiSmbCorpus.checkCorpusHeader(headers)
        if (!headerCheck.valid) {
            println("$TAG: SMB corpus refused — no training. ${headerCheck.reason}")
            discardModelTrainedOnUnreadableCorpus(storage, dir)
            recordResult(
                storage,
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.REFUSED_HEADER,
                    totalRows = totalRows,
                    gateDetail = headerCheck.reason,
                ),
            )
            return
        }
        val corpus = AimiSmbCorpus.buildTrainingCorpus(headers, dataLines)
        if (corpus == null) {
            recordResult(
                storage,
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.REFUSED_HEADER,
                    totalRows = totalRows,
                    gateDetail = "corpus could not be built",
                ),
            )
            return
        }
        val inputs = corpus.inputs
        val targets = corpus.targets
        val rowsRejectedByFilter = totalRows - inputs.size

        if (inputs.size < AimiSmbTrainingSchedule.MIN_TRAINING_SAMPLES) {
            println("$TAG: Insufficient training samples (${inputs.size}) — skip")
            recordResult(
                storage,
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.TOO_FEW_SAMPLES,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rowsRejectedByFilter,
                    gateDetail = "${inputs.size} samples < ${AimiSmbTrainingSchedule.MIN_TRAINING_SAMPLES} required",
                ),
            )
            return
        }

        println("$TAG: Training on ${inputs.size} samples…")

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
        // The gate-rejection detail a dashboard / support report shows is read off the very last line
        // this callback receives: every rejection path in `NeuralModelTrainer.trainAndPublish` logs its
        // "spread x < y" / "mae ratio" reason immediately before returning null, so capturing the last
        // message is the same as capturing the reason, without changing `trainAndPublish`'s signature or
        // its behaviour for the basal caller.
        var lastGateMessage = ""
        val net = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = AimiSmbModelStore.modelFile(storage, dir),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.001, epochs = 300),
            inputSize = INPUT_SIZE,
            regularizationLambda = 0.01,
            outputRange = SMB_OUTPUT_RANGE,
            spreadFeatureIndex = BG_FEATURE_INDEX,
            spreadSweepValues = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL,
            minOutputSpread = SMB_MIN_OUTPUT_SPREAD,
            maxBaselineMaeRatio = SMB_MAX_BASELINE_MAE_RATIO,
            log = { message ->
                println("$TAG: $message)
                lastGateMessage = message
            },
        )
        if (net != null) {
            modelRef.store(net)
            lastTrainMs.store(aimiWallClockMs())
            rowsAtLastTrain.store(totalRows)
            persistState(storage, dir)
            circuitBreaker.reset()   // reset circuit breaker on success
            println("$TAG: Model trained and saved successfully (${inputs.size} rows)")
            recordResult(
                storage,
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.TRAINED,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rowsRejectedByFilter,
                ),
            )
        } else {
            // RULING R-CB (tip 6a6561caab): a rejected candidate must not disable the model already in
            // service. recordFailure feeds the breaker, which after 3 failures makes refine return the
            // raw dose for 6h. Only count this when modelRef is null — nothing is in service yet.
            // The 6h / 24h clocks stay on lastAttemptMs, set above, either way.
            if (AimiSmbTrainingSchedule.countGateRejectionAsBreakerFailure(modelRef.load() != null)) {
                recordFailure()
            }
            recordResult(
                storage,
                dir,
                TrainingResult(
                    atMs = now,
                    outcome = TrainingOutcome.REJECTED_BY_GATES,
                    totalRows = totalRows,
                    samplesAfterFilter = inputs.size,
                    rowsRejectedByFilter = rowsRejectedByFilter,
                    gateDetail = lastGateMessage,
                ),
            )
        }
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
     *
     * ⚠️ ASYNC IMPACT: runs on the existing `aapsIoDispatcher` training coroutine (same as `trainNow`).
     * `modelRef.store(null)` is visible to the hot-path `refine()`; the File delete is IO-only.
     * `loadModel` is also fire-and-forget on that dispatcher — same pre-existing race as a training
     * publish vs a plugin-start load.
     */
    private fun discardModelTrainedOnUnreadableCorpus(storage: AimiStorage, dir: AimiPath) {
        if (!staleModelDiscarded.compareAndSet(false, true)) return
        modelRef.store(null)
        val removed = AimiSmbModelStore.delete(storage, dir)
        println("$TAG: SMB weights discarded: they were trained on an unreadable corpus. " +
            "Weight file removed=$removed. refine() now returns the rule-based dose until a " +
            "training run on a readable corpus publishes new weights.")
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
            println("$TAG: Circuit breaker OPEN — ML disabled for 6h after ${TrainingCircuitBreaker.DEFAULT_MAX_FAILURES} consecutive failures")
        }
    }

    // ---- Persistence -----------------------------------------------------
    // Same three counters as the reference `smb_ml_training_state.json`. The bytes go through
    // [AimiStorage.replaceText] (tmp then rename), which is the study equivalent of the reference
    // `File.renameTo`. Not a preference key, so import/export is unchanged.

    private fun stateFile(storage: AimiStorage, dir: AimiPath): AimiPath =
        storage.resolve(dir, AimiSmbTrainingSchedule.STATE_FILE_NAME)

    /**
     * Loads the persisted state exactly once. The caller MUST already hold [trainMutex] (or be the
     * single-threaded path inside it): this function does not take the lock, because [trainNow] is
     * already called from inside `trainMutex.withLock` and [Mutex] is not reentrant.
     */
    private fun ensureStateLoadedLocked(storage: AimiStorage, dir: AimiPath) {
        if (stateLoaded.compareAndSet(false, true)) {
            loadPersistedState(storage, dir)
        }
    }

    /**
     * Missing or unreadable file leaves the in-memory defaults (all zero), which is the same as
     * "never trained" — safe, since it only makes the next attempt run a little sooner, never later.
     *
     * A loaded timestamp more than [AimiSmbTrainingSchedule.CLOCK_SKEW_TOLERANCE_MS] in the future is
     * reset to 0. Neither timestamp is ever moved backwards by a load: the larger of the current
     * in-memory value and the loaded one wins.
     *
     * `@VisibleForTesting`: normally reached only through [ensureStateLoadedLocked]; exposed `internal`
     * so a test can prove the round trip directly instead of racing [loadModel]'s coroutine.
     */
    internal fun loadPersistedState(storage: AimiStorage, dir: AimiPath) {
        val file = stateFile(storage, dir)
        if (!storage.exists(file)) return
        try {
            val text = storage.readText(file) ?: return
            val loaded = AimiSmbTrainingSchedule.decodeCounters(text, aimiWallClockMs()) ?: return
            lastAttemptMs.store(maxOf(lastAttemptMs.load(), loaded.lastAttemptMs))
            lastTrainMs.store(maxOf(lastTrainMs.load(), loaded.lastTrainMs))
            rowsAtLastTrain.store(loaded.rowsAtLastTrain)
            // Only when nothing has happened in this process yet: a result this run produced is newer
            // than anything on disk and must not be replaced by it.
            if (lastAttemptResultRef.load() == null) {
                loaded.lastResult?.let { lastAttemptResultRef.store(it) }
            }
        } catch (e: Exception) {
            println("$TAG: Could not load SMB training state: ${e.message}")
        }
    }

    /**
     * `@VisibleForTesting`: written only from the training paths above in production; exposed `internal`
     * so a test can write a known state and read it back.
     */
    internal fun persistState(storage: AimiStorage, dir: AimiPath) {
        try {
            val file = stateFile(storage, dir)
            storage.createParentDirectories(file)
            val text = AimiSmbTrainingSchedule.encodeCounters(
                AimiSmbTrainingSchedule.Counters(
                    lastAttemptMs = lastAttemptMs.load(),
                    lastTrainMs = lastTrainMs.load(),
                    rowsAtLastTrain = rowsAtLastTrain.load(),
                    lastResult = lastAttemptResultRef.load(),
                ),
            )
            if (!storage.replaceText(file, text)) {
                println("$TAG: Could not persist SMB training state")
            }
        } catch (e: Exception) {
            println("$TAG: Could not persist SMB training state: ${e.message}")
        }
    }

    /**
     * Records [result] as the last real training outcome (see [lastAttemptResultRef]), in memory and on
     * disk.
     *
     * Skips the disk write when [result] carries the same outcome, counters and detail as what is
     * already held (only the timestamp differs) — otherwise a "no CSV yet", or a candidate refused again
     * for the SAME reason, would rewrite the state file on every attempt forever, for a user for whom
     * nothing has actually changed.
     */
    private fun recordResult(storage: AimiStorage, dir: AimiPath, result: TrainingResult) {
        val previous = lastAttemptResultRef.load()
        lastAttemptResultRef.store(result)
        val unchanged = previous != null &&
            previous.outcome == result.outcome &&
            previous.totalRows == result.totalRows &&
            previous.samplesAfterFilter == result.samplesAfterFilter &&
            previous.rowsRejectedByFilter == result.rowsRejectedByFilter &&
            previous.gateDetail == result.gateDetail
        if (unchanged) return
        persistState(storage, dir)
    }

    // ---- Test-only access ----------------------------------------------------
    // [AimiSmbTrainer] is a singleton object, so its state otherwise leaks between test cases (and test
    // classes, within the same JVM). None of these is called from production code.

    /** Test-only: resets every piece of shared state back to "just started". */
    internal fun resetForTest() {
        lastAttemptMs.store(0L)
        lastTrainMs.store(0L)
        rowsAtLastTrain.store(0L)
        lastAttemptResultRef.store(null)
        currentWaitingStatusRef.store(null)
        modelRef.store(null)
        staleModelDiscarded.store(false)
        stateLoaded.store(false)
        circuitBreaker.reset()
    }

    /** Test-only: current `rowsAtLastTrain`, which otherwise has no getter. */
    internal fun rowsAtLastTrainForTest(): Long = rowsAtLastTrain.load()

    /**
     * Test-only: sets the three persisted counters directly, so their persistence and the shrunk-CSV
     * correction (through [trainNow], not just the pure `AimiSmbTrainingSchedule.shouldAttempt`) can be
     * tested without depending on a real — stochastic, unseeded — successful training run to produce
     * non-zero values.
     */
    internal fun setPersistedStateForTest(lastAttemptMs: Long = 0L, lastTrainMs: Long = 0L, rowsAtLastTrain: Long = 0L) {
        this.lastAttemptMs.store(lastAttemptMs)
        this.lastTrainMs.store(lastTrainMs)
        this.rowsAtLastTrain.store(rowsAtLastTrain)
    }

    /**
     * Test-only: sets (or clears) the in-memory model, so the rule that a rejected candidate must not
     * disable an incumbent already in service can be tested without depending on a real — stochastic,
     * unseeded — successful training run to populate `modelRef`.
     */
    internal fun setModelForTest(net: AimiNeuralNetwork?) {
        modelRef.store(net)
    }
}
