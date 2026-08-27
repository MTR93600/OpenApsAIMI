package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator // 🧠 PSE
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController // 🧮 MPC
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield // 🛡️ CBF
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner // 🎓 Learner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake // 🗂️ Data Lake
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller // 🧹 Backfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate // 🚪 Attention Gate
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveAuditor // 👨‍🏫 Auditor
import app.aaps.plugins.aps.openAPSAIMI.model.*
import app.aaps.core.keys.interfaces.PreferenceKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * 🧠 Autodrive Engine (iLet-like Architecture)
 * 
 * Moteur unifié de contrôle continu remplissant les fonctions cumulées de TrajectoryGuard,
 * DynamicBasalController, et SMB.
 * Actuellement en mode SHADOW (Fantôme) : il calcule et logge ses décisions sans ordonner à la pompe.
 */
@SingleIn(AppScope::class)
class AutodriveEngine @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val stateEstimator: ContinuousStateEstimator,
    private val mpcController: MpcController,
    private val safetyShield: ControlBarrierShield,
    private val onlineLearner: OnlineLearner,
    private val autodriveAuditor: AutodriveAuditor,
    private val dataLake: AutodriveDataLake,
    private val dataBackfiller: AutodriveDataBackfiller,
    private val attentionGate: MechanismAttentionGate
) {

    private val systemState = AtomicReference<AimiState>(AimiState.Manual)
    private var aggressiveWindowUntilEpochMs: Long = 0L

    /**
     * Sensitivity the safety barrier actually read on the last engaged tick, in ISF/10000 units.
     *
     * The dataset row carries `estimatedSI`, which is what the *controller* optimised against. As long
     * as the profile anchor stays unreachable the two are equal, but a safety component whose input is
     * not recorded anywhere is an audit hole, so it is exposed here for the exports to pick up.
     */
    var lastSafetySiUsed: Double = 0.0
        private set

    /** Coefficient the barrier actually used, i.e. floored. Diagnostic only. */
    var lastControlCoefficientUsed: Double = 0.0
        private set

    /** What that coefficient would be without the floor — see [recordCoefficientShadow]. */
    var lastControlCoefficientUnfloored: Double = 0.0
        private set

    /** Total insulin the barrier permitted this tick, in U over 5 minutes. */
    var lastCbfPermittedU: Double = 0.0
        private set

    /** What it would have permitted with the floor removed. Never enacted. */
    var lastCbfPermittedUnflooredU: Double = 0.0
        private set

    /** Profile ISF the barrier was handed, in mg/dL/U, so the ratio above is interpretable. */
    var lastProfileIsfSeen: Double = 0.0
        private set

    /**
     * What the solver asked for, **before** [ControlBarrierShield.enforce] saw it.
     *
     * The export's `model_output_u` is taken after the barrier, so a zero there means either "the
     * solver wanted nothing" or "the solver wanted a dose and the barrier suspended it". Those have
     * opposite fixes. On the 2026-08-14 lunch it was the second on every tick of the plateau, and
     * nothing exported could show it.
     */
    var lastMpcRawSmbU: Double = 0.0
        private set
    var lastMpcRawTbrUph: Double = 0.0
        private set

    /** The barrier's own terms for the production call of this tick. See [ControlBarrierShield.Diagnostics]. */
    var lastBarrierDiagnostics: ControlBarrierShield.Diagnostics? = null
        private set

    /**
     * Training row waiting to be written, for the tick currently running.
     *
     * `tick()` no longer writes to the dataset itself. It is reached from three places — the engaged
     * Autodrive branch, the T3C shadow tick, and `proposeBasalOnlyTbr` which delegates to `tick()` —
     * so a write inside it produced two or three rows carrying the same timestamp, in a count that
     * depended on which preferences were on. Every one of them was labelled `engaged = 1`, including
     * T3C proposals whose SMB the caller strips and which never reach the pump.
     *
     * Staging instead of writing gives one row per tick, labelled by the path that actually owned the
     * decision, and moves the file write off the middle of the dosing sequence.
     */
    private val pendingRow = AtomicReference<PendingTrainingRow?>(null)

    private data class PendingTrainingRow(
        val tickId: Long,
        val state: AutoDriveState,
        val rawCommand: AutoDriveCommand?,
        val safeCommand: AutoDriveCommand?,
        val engaged: Boolean,
        val timestampMs: Long,
    )

    private companion object {
        const val AGGRESSIVE_HOLD_MS: Long = 12 * 60 * 1000L

        /** Bounds on the profile-anchored sensitivity the safety barrier reads, in ISF/10000 units. */
        const val SAFETY_SI_MIN: Double = 1.0 / 10000.0
        const val SAFETY_SI_MAX: Double = 400.0 / 10000.0
    }

    fun setIsActive(enabled: Boolean) {
        updateState(isActive = enabled)
    }

    fun setShadowMode(enabled: Boolean) {
        updateState(isShadowMode = enabled)
    }

    private fun updateState(isActive: Boolean? = null, isShadowMode: Boolean? = null) {
        val current = systemState.get()
        if (current is AimiState.AutoDrive) {
            systemState.set(current.copy(
                isActive = isActive ?: current.isActive,
                isShadowMode = isShadowMode ?: current.isShadowMode
            ))
        } else {
            systemState.set(AimiState.AutoDrive(
                isActive = isActive ?: false,
                isShadowMode = isShadowMode ?: true,
                controllerType = AimiState.AutoDrive.ControllerType.Hybrid
            ))
        }
    }

    fun getAttentionMultiplier(): Double = attentionGate.lastAttentionMultiplier
    
    fun getHealthScore(): Double = autodriveAuditor.lastHealthScore

    fun onlineLearnerStatus(): OnlineLearner.StatusSnapshot = onlineLearner.statusSnapshot()

    /**
     * Stages a training row for a tick where the gate did not engage.
     *
     * Only the dataset is touched — no estimator update, no learner step, no command. The decision
     * columns stay neutral and `Engaged` is 0, so the classifier can condition on engagement rather
     * than the dataset being silently filtered by it.
     *
     * Nothing is written until [flushTickRow]. If the engaged branch also runs this tick, its row
     * wins, because that is the one whose decision reached the pump.
     */
    fun stageDisengagedSnapshot(state: AutoDriveState, tickId: Long, currentEpochMs: Long) {
        stageTickRow(
            tickId = tickId,
            state = state,
            rawCommand = null,
            safeCommand = null,
            engaged = false,
            timestampMs = currentEpochMs,
        )
    }

    /**
     * Stages the row for [tickId], keeping the engaged one when several paths run in the same tick.
     *
     * A T3C proposal and the engaged branch describe the same 5 minutes of physiology. Only one of
     * them decided the dose, and that is the one the classifier must see.
     */
    private fun stageTickRow(
        tickId: Long,
        state: AutoDriveState,
        rawCommand: AutoDriveCommand?,
        safeCommand: AutoDriveCommand?,
        engaged: Boolean,
        timestampMs: Long,
    ) {
        val candidate = PendingTrainingRow(tickId, state, rawCommand, safeCommand, engaged, timestampMs)
        pendingRow.getAndUpdate { existing ->
            when {
                existing == null || existing.tickId != tickId -> candidate
                // Same tick: an engaged row must never be replaced by a shadow one.
                existing.engaged && !engaged                  -> existing
                else                                          -> candidate
            }
        }
    }

    /**
     * Writes the row staged for [tickId], if any, and clears the slot.
     *
     * Must be called once per tick, on every exit path, otherwise the tick's row is silently dropped
     * and the next tick starts with a stale slot. A tick that staged nothing writes nothing.
     */
    fun flushTickRow(tickId: Long) {
        val row = pendingRow.getAndSet(null) ?: return
        if (row.tickId != tickId) {
            aapsLogger.debug(
                LTag.APS,
                "🗂️ [DATA_LAKE] Ligne en attente du tick ${row.tickId} abandonnée au tick $tickId."
            )
            return
        }
        runCatching {
            dataLake.recordSnapshot(
                state = row.state,
                rawCommand = row.rawCommand,
                safeCommand = row.safeCommand,
                engaged = row.engaged,
                currentTimestamp = row.timestampMs,
            )
        }.onFailure { aapsLogger.error(LTag.APS, "🗂️ [DATA_LAKE] Écriture de la ligne échouée: ${it.message}") }
    }

    /**
     * T3C basal-only proposal: runs the full Autodrive pipeline and returns TBR demand.
     * Restores prior engine mode afterward. Caller must strip/ignore SMB (never enact bolus).
     *
     * ⚠️ ASYNC IMPACT: temporarily activates Autodrive for one tick compute (learner + DataLake update).
     */
    fun proposeBasalOnlyTbr(
        currentState: AutoDriveState,
        profileBasal: Double,
        profileIsf: Double,
        lgsThreshold: Double,
        hour: Int,
        steps: Int,
        hr: Int,
        rhr: Int,
        currentEpochMs: Long = System.currentTimeMillis(),
        tickId: Long = 0L,
        observationId: Long = 0L,
        /** Relayed to `tick`; without it this path silently took the 0.0 default. */
        mpcRaFloorMgdlPerMin: Double = 0.0,
    ): BasalOnlyTbrProposal? {
        val previous = systemState.get()
        return try {
            setShadowMode(false)
            setIsActive(true)
            val cmd = tick(
                currentState = currentState,
                profileBasal = profileBasal,
                profileIsf = profileIsf,
                lgsThreshold = lgsThreshold,
                hour = hour,
                steps = steps,
                hr = hr,
                rhr = rhr,
                currentEpochMs = currentEpochMs,
                mpcRaFloorMgdlPerMin = mpcRaFloorMgdlPerMin,
                tickId = tickId,
                observationId = observationId,
                // A proposal is not a decision: the caller strips the SMB and may ignore the TBR, so
                // this row must not teach the classifier that Autodrive drove the tick.
                engaged = false,
            ) ?: return null
            BasalOnlyTbrProposal(
                tbrUph = cmd.temporaryBasalRate,
                strippedSmbU = cmd.scheduledMicroBolus.coerceAtLeast(0.0),
                reason = cmd.reason,
            )
        } finally {
            systemState.set(previous)
        }
    }

    data class BasalOnlyTbrProposal(
        val tbrUph: Double,
        val strippedSmbU: Double,
        val reason: String,
    )

    /**
     * Point d'entrée principal à chaque Tique (5 min) depuis DetermineBasalAIMI2.
     */
    fun tick(
        currentState: AutoDriveState,
        profileBasal: Double,
        profileIsf: Double,
        lgsThreshold: Double,
        hour: Int,
        steps: Int,
        hr: Int,
        rhr: Int,
        currentEpochMs: Long = System.currentTimeMillis(),
        /**
         * Hyper-trajectory Ra floor, in mg/dL/min, or 0.0 for none.
         *
         * Applied to the **controller's** view only. The barrier shield keeps the estimator's honest
         * value: it reads `estimatedRa` to size its own margins
         * (`ControlBarrierShield.kt:67, 101-103, 115`), and a safety barrier fed an optimistic input
         * is no longer a barrier.
         *
         * Until now this floor was computed, passed in as `AutoDriveState.estimatedRa`, and silently
         * discarded — line 165 overwrote the field before the estimator ran. It reached the recursive
         * belief tree but never the MPC its producer is named after.
         */
        mpcRaFloorMgdlPerMin: Double = 0.0,
        /**
         * Identifier of the loop tick, used to stage at most one training row per tick.
         *
         * This is the *invocation* identity, and it must be the same value `flushTickRow` is later
         * called with. Do not confuse it with [observationId]: a row describes one decision, an
         * observation describes one CGM sample, and a sample can be seen by several invocations.
         */
        tickId: Long = 0L,
        /**
         * Identifier of the CGM sample, so the estimator consumes each observation only once.
         *
         * `0` means "unknown" and restores the old behaviour of advancing on every call. See
         * `ContinuousStateEstimator.updateAndPredict`.
         */
        observationId: Long = 0L,
        /**
         * Whether this call owns the dose for the tick.
         *
         * Written to the `Engaged` column. It must come from the caller: `tick()` cannot tell a real
         * decision from a T3C proposal whose SMB the caller throws away, and it used to label both
         * as engaged.
         */
        engaged: Boolean = true,
    ): AutoDriveCommand? {
        val state = systemState.get() as? AimiState.AutoDrive ?: return null
        if (!state.isActive && !state.isShadowMode) return null

        // On injecte les données physiologiques temps réel dans l'état avant traitement
        val stateWithContext = currentState.copy(
            hour = hour,
            steps = steps,
            hr = hr,
            rhr = rhr
        )

        // 0. Le Processus d'apprentissage en ligne s'exécute pour affiner les paramètres
        onlineLearner.learnAndUpdate(stateWithContext, currentEpochMs)

        // 🎓 `learnedSensitivityFactor` is observed, not applied.
        //
        // Its training target is self-described in OnlineLearner as "un mock simple pour valider
        // l'architecture": `bg + velocity * 30`, a linear extrapolation, not the MPC trajectory. The
        // error of a linear extrapolation measures how curved the glucose trace is, not how strongly
        // insulin acts — so accumulating it into a sensitivity is not meaningful.
        //
        // It is also structurally pinned at 1.0 today: the step is `error * 0.001 * 0.005` (~1e-4 per
        // evaluation), it only advances on engaged ticks, and the field is never persisted, so every
        // restart resets it. Persisting it, as the obvious "fix", would let a meaningless quantity
        // accumulate to the ±50 % clamp and multiply `estimatedSI` — which reaches both MpcController
        // and ControlBarrierShield.
        //
        // The learner keeps running and reporting through `onlineLearnerStatus()`, so the signal stays
        // observable. Apply it only once its target is a real MPC trajectory.
        val learningAdjustedState = stateWithContext

        // 1. Attention Gate (Phase 9 - ML On-Device)
        // L'intelligence artificielle vient potentiellement biaiser la sensibilité perçue 
        // pour corriger de manière prédictive le comportement du MPC face à des menaces physio.
        val attentionState = attentionGate.applyAttention(learningAdjustedState)

        // 2. PSE (Physiological State Estimator) Update
        // [FIX CRITIQUE]: On réinjecte le Ra précédemment appris pour garder le momentum de la courbe
        val stateWithMomentum = attentionState.copy(estimatedRa = stateEstimator.getLastRa())
        val estimatedState = stateEstimator.updateAndPredict(stateWithMomentum, tickId = observationId)

        // 2. MPC (Model Predictive Controller) Calculation
        // The floor lifts what the controller anticipates, never what the estimator believes: it is a
        // feed-forward, not an observation, so it must not ratchet into the next tick's prediction.
        val mpcState = if (
            mpcRaFloorMgdlPerMin.isFinite() && mpcRaFloorMgdlPerMin > estimatedState.estimatedRa
        ) {
            aapsLogger.debug(
                LTag.APS,
                "🍽️ HTR_RA_FLOOR: MPC sees Ra=${"%.2f".format(mpcRaFloorMgdlPerMin)} " +
                    "instead of estimated ${"%.2f".format(estimatedState.estimatedRa)}",
            )
            estimatedState.copy(estimatedRa = mpcRaFloorMgdlPerMin)
        } else {
            estimatedState
        }
        val rawCommand = mpcController.calculateOptimalDose(mpcState, profileBasal, lgsThreshold)
        lastMpcRawSmbU = rawCommand.scheduledMicroBolus
        lastMpcRawTbrUph = rawCommand.temporaryBasalRate

        // 3. CBF (Control Barrier Shield) Safety Check.
        //
        // Honest Ra on purpose (see the `mpcRaFloorMgdlPerMin` doc), and now an honest sensitivity
        // too. The shield computes `lgh = -siMetabolic * bg` from the sensitivity it is given, so a
        // *lower* sensitivity shrinks the coefficient on the control action and the barrier permits a
        // *larger* dose. Anything able to lower that number is able to loosen the barrier.
        //
        // `estimatedSI` is exactly such a number: it descends from `pkpdRuntime.fusedIsf`, which
        // `PkPdIntegration` multiplies by an `aggressionMultiplier` bounded [0.55, 1.08] driven by
        // delta, UAM confidence and the behaviour family. On an undeclared meal that policy can lower
        // the sensitivity by 45 %, which loosens the barrier and pushes the controller the same way at
        // the same moment. A barrier that moves with the controller is not a barrier.
        //
        // This is the same failure the attention gate was clamped for — except that arm had never run,
        // and this one runs on every tick.
        //
        // The barrier therefore anchors on the profile ISF, and takes whichever of the two is *more*
        // restrictive, so this can only ever tighten the barrier relative to today: a defensive
        // learner raising `estimatedSI` is kept, a policy lowering it is ignored.
        //
        // Passed as an argument, not carried on the state: a `copy()` here would re-run
        // `AutoDriveState.init` on the dosing path, turning a non-finite estimate into a throw where
        // it used to be tolerated.
        val safetySi = profileAnchoredSafetySi(profileIsf, estimatedState.estimatedSI)
        lastSafetySiUsed = safetySi
        val safeCommand = safetyShield.enforce(rawCommand, estimatedState, profileBasal, safetySi, observationId)
        // Captured here on purpose: recordCoefficientShadow below runs `enforce` a second time for the
        // unfloored shadow, which would overwrite the shield's own record of the production call.
        lastBarrierDiagnostics = safetyShield.lastDiagnostics

        recordCoefficientShadow(profileIsf, safetySi, rawCommand, estimatedState, profileBasal, observationId, safeCommand)

        // 5. Explicabilité de l'IA (Auditor Traducteur)
        val auditedReason = autodriveAuditor.generateHumanReadableReason(
            state = estimatedState,
            baseProfileIsf = profileIsf, // Utilisation de l'ISF réel du profil (Phase 7)
            rawCommand = rawCommand,
            safeCommand = safeCommand
        )
        val auditedCommand = safeCommand.copy(reason = auditedReason)

        // 6. Data Lake CSV persistency (for V3 training).
        // Staged, not written: the file write happens once per tick in `flushTickRow`, after the dose
        // is decided. See `pendingRow`.
        stageTickRow(
            tickId = tickId,
            state = estimatedState,
            rawCommand = rawCommand,
            safeCommand = auditedCommand,
            engaged = engaged,
            timestampMs = currentEpochMs,
        )

        // 7. Logging & Shadow metrics
        if (state.isShadowMode) {
            logShadowDecision(currentState, auditedCommand, profileBasal)
        }

        if (!state.isActive) return null

        // 8. Quiet Mode Handover (Rollback to AIMI V2 PI Controller)
        // Autodrive V3 (MPC) is mathematically aggressive by nature. For calm waters and slight upstream drifts, 
        // the legacy proportional controller is superior. We yield control (return null) unless V3 is actively fighting.
        // 🚀 T9: Aggression boost based on UAM and CombinedDelta with hysteresis window.
        val aggressiveSignal = estimatedState.estimatedRa > 0.6 ||
            estimatedState.bgVelocity > 1.1 ||
            estimatedState.uamConfidence > 0.65 ||
            estimatedState.combinedDelta > 3.2
        if (aggressiveSignal) {
            aggressiveWindowUntilEpochMs = currentEpochMs + AGGRESSIVE_HOLD_MS
        }
        val inAggressiveWindow = currentEpochMs < aggressiveWindowUntilEpochMs

        val isHigh = estimatedState.bg > 145.0
        val needsSmb = auditedCommand.scheduledMicroBolus > 0.0
        val needsSafetyBrake = auditedCommand.temporaryBasalRate == 0.0
        
        // 🚀 HYBRID SMOOTHING: If the requested correction is minor (< 0.1 U/h delta),
        // let V2 handle the fine-tuning fluidity.
        // Fix #6: Lowered from 0.3 to 0.1 to allow V3 to engage more often and collect ML data.
        val tbrDelta = Math.abs(auditedCommand.temporaryBasalRate - profileBasal)
        val isStrongCorrection = tbrDelta > 0.15

        return if (inAggressiveWindow || isHigh || needsSmb || needsSafetyBrake || isStrongCorrection) {
            auditedCommand
        } else {
            aapsLogger.debug(
                LTag.APS,
                "💤 [AUTODRIVE_V3] Quiet Mode: Delegating prophylactic TBR adjustments to legacy V2 PI Controller."
            )
            null
        }
    }

    /**
     * Sensitivity the safety barrier reads, anchored on the profile and never looser than today.
     *
     * [profileIsf] is the profile ISF in mg/dL/U; the state carries sensitivity as ISF/10000, so it is
     * rescaled. The result is `max(profile-anchored, commanded)` because a **higher** sensitivity
     * makes `lgh = -siMetabolic * bg` larger in magnitude and the permitted dose smaller. Taking the
     * maximum means:
     *
     * - a policy multiplier that lowers the commanded sensitivity no longer loosens the barrier;
     * - a defensive learner that raises it is still honoured;
     * - the barrier can never end up looser than it is today, whatever the inputs.
     *
     * On a non-finite or non-positive profile ISF there is no anchor to trust, so the commanded value
     * is used unchanged and the event is logged rather than silently substituted.
     */
    /**
     * Measures what removing the coefficient floor would cost, without letting it reach the pump.
     *
     * `InsulinActionModel.controlCoefficient` floors at the pre-unification value so this refactor can
     * only tighten the barrier. Removing that floor is the next step and it is the one that moves the
     * dose: on this deployment the median profile ISF is 30 against a reference of 45, so the
     * unfloored coefficient is 0.67x and the barrier would be about a third more permissive.
     *
     * A third more permissive **on the median tick** is not something to ship on arithmetic. So the
     * unfloored barrier is run here on the same state, and only its result is exported. `enforce` is
     * pure apart from its acceleration memory, and that memory is now keyed on [observationId], so the
     * second call reads the same acceleration as the first instead of resetting it.
     */
    private fun recordCoefficientShadow(
        profileIsf: Double,
        safetySi: Double,
        rawCommand: AutoDriveCommand,
        estimatedState: AutoDriveState,
        profileBasal: Double,
        observationId: Long,
        safeCommand: AutoDriveCommand,
    ) {
        runCatching {
            val isf = InsulinActionModel.isfFromStateSi(safetySi)
            lastControlCoefficientUsed =
                InsulinActionModel.controlCoefficient(isf, InsulinActionModel.MPC_TAU_MIN)
            lastControlCoefficientUnfloored =
                InsulinActionModel.metabolicCoefficient(isf, InsulinActionModel.MPC_TAU_MIN)
            lastCbfPermittedU = safeCommand.scheduledMicroBolus + safeCommand.temporaryBasalRate / 12.0

            // Same barrier, same state, only the floor removed. Expressed as the sensitivity that
            // yields the unfloored coefficient, so `enforce` needs no new parameter.
            val unflooredEquivalentSi = lastControlCoefficientUnfloored * InsulinActionModel.REFERENCE_BG_MGDL *
                InsulinActionModel.MPC_TAU_MIN / 10000.0
            val unflooredCommand = safetyShield.enforce(
                rawCommand, estimatedState, profileBasal, unflooredEquivalentSi, observationId,
            )
            lastCbfPermittedUnflooredU =
                unflooredCommand.scheduledMicroBolus + unflooredCommand.temporaryBasalRate / 12.0
            lastProfileIsfSeen = profileIsf
        }
    }

    private fun profileAnchoredSafetySi(profileIsf: Double, commandedSi: Double): Double {
        if (!profileIsf.isFinite() || profileIsf <= 0.0) {
            aapsLogger.warn(
                LTag.APS,
                "🛡️ [CBF] ISF profil inutilisable ($profileIsf) — la barrière retombe sur la sensibilité commandée."
            )
            return commandedSi
        }
        val anchored = (profileIsf / 10000.0).coerceIn(SAFETY_SI_MIN, SAFETY_SI_MAX)
        if (anchored > commandedSi) {
            // This branch can fire. The old note here said it never could, because
            // `AutoDriveState.createSafe` floored `estimatedSI` at 0.1 against a `SAFETY_SI_MAX` of
            // 0.04. That floor is gone: `createSafe` now bounds the value in the caller's units,
            // between `InsulinActionModel.MIN_ISF_MGDL_PER_U` and `MAX_ISF_MGDL_PER_U` over 10000.
            // So both sides are now real sensitivities in the same units and the larger one wins.
            //
            // Note this is only the *input* to the coefficient. `InsulinActionModel.controlCoefficient`
            // then floors at `LEGACY_CONTROL_COEFFICIENT`, so on most ticks neither sensitivity
            // reaches the barrier at all: on the 2026-08-15 package the exported `si_metabolic` sat
            // exactly on that floor on 61 of 72 ticks.
            aapsLogger.info(
                LTag.APS,
                "🛡️ [CBF] Ancre profil retenue: ${anchored.format(5)} au lieu de ${commandedSi.format(5)}."
            )
        }
        return max(anchored, commandedSi)
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    private fun logShadowDecision(state: AutoDriveState, autodriveCommand: AutoDriveCommand, profileBasal: Double) {
        aapsLogger.debug(
            LTag.APS,
            "👽 [AUTODRIVE_SHADOW] BG: ${state.bg} (v: ${String.format("%.1f", state.bgVelocity)}) | " +
            "Est_SI: ${String.format("%.2f", state.estimatedSI)} | Est_Ra: ${String.format("%.2f", state.estimatedRa)} || " +
            "Autodrive dictates: TBR=${autodriveCommand.temporaryBasalRate} U/h, " +
            "SMB=${autodriveCommand.scheduledMicroBolus} U | Reason: ${autodriveCommand.reason}"
        )
    }

    /**
     * Advanced Decoupled Execution: Applies verified verdicts to the system.
     */
    fun applyVerdicts(verdicts: List<AimiVerdict>): List<AimiState.SafetyIntervention> {
        val interventions = mutableListOf<AimiState.SafetyIntervention>()
        
        verdicts.forEach { verdict ->
            when (verdict) {
                is AimiVerdict.Confirmed -> enactAction(verdict.action)
                is AimiVerdict.Modified -> enactAction(verdict.modifiedAction)
                is AimiVerdict.Rejected -> {
                    aapsLogger.warn(LTag.APS, "🚫 Action rejected by Auditor: ${verdict.auditorReason}")
                }
            }
        }
        
        return interventions
    }

    private fun enactAction(action: AimiAction) {
        when (action) {
            is AimiAction.TemporaryBasal -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting TBR: ${action.rate} U/h for ${action.durationMinutes}m")
            }
            is AimiAction.SMB -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting SMB: ${action.amount} U")
            }
            is AimiAction.Bolus -> {
                aapsLogger.info(LTag.APS, "🚀 Enacting Bolus: ${action.amount} U")
            }
            is AimiAction.PreferenceUpdate -> {
                aapsLogger.info(LTag.APS, "🚀 Updating Preference: ${action.key.key} -> ${action.newValue}")
            }
            is AimiAction.Notification -> {
                aapsLogger.info(LTag.APS, "🚀 Sending Notification: ${action.title}")
            }
        }
    }
}
