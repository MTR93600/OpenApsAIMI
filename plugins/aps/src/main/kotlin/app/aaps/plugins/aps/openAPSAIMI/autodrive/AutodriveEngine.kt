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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🧠 Autodrive Engine (iLet-like Architecture)
 * 
 * Moteur unifié de contrôle continu remplissant les fonctions cumulées de TrajectoryGuard,
 * DynamicBasalController, et SMB.
 * Actuellement en mode SHADOW (Fantôme) : il calcule et logge ses décisions sans ordonner à la pompe.
 */
@Singleton
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

    private var isActive = false // Feature Toggle pour le monde réel
    private var isShadowMode = true // Tourne silencieusement

    fun setIsActive(enabled: Boolean) {
        isActive = enabled
    }

    fun setShadowMode(enabled: Boolean) {
        isShadowMode = enabled
    }

    /**
     * Point d'entrée principal à chaque Tique (5 min) depuis DetermineBasalAIMI2.
     */
    fun tick(currentState: AutoDriveState, profileBasal: Double, lgsThreshold: Double, currentEpochMs: Long = System.currentTimeMillis()): AutoDriveCommand? {
        if (!isActive && !isShadowMode) return null

        // 0. Le Processus d'apprentissage en ligne s'exécute pour affiner les paramètres
        onlineLearner.learnAndUpdate(currentState, currentEpochMs)

        // On injecte le facteur appris dans l'état (Phase 2 -> Phase 5)
        // Note: Ici c'est super simplifié, le SI global baisse ou monte selon le facteur.
        val learningAdjustedState = currentState.copy(
            estimatedSI = currentState.estimatedSI * onlineLearner.learnedResistanceFactor
        )

        // 1. Attention Gate (Phase 9 - ML On-Device)
        // L'intelligence artificielle vient potentiellement biaiser la sensibilité perçue 
        // pour corriger de manière prédictive le comportement du MPC face à des menaces physio.
        val attentionState = attentionGate.applyAttention(learningAdjustedState)

        // 2. PSE (Physiological State Estimator) Update
        val estimatedState = stateEstimator.updateAndPredict(attentionState)

        // 2. MPC (Model Predictive Controller) Calculation
        val rawCommand = mpcController.calculateOptimalDose(estimatedState, profileBasal, lgsThreshold)

        // 3. CBF (Control Barrier Shield) Safety Check
        val safeCommand = safetyShield.enforce(rawCommand, estimatedState, profileBasal)

        // 5. Explicabilité de l'IA (Auditor Traducteur)
        val auditedReason = autodriveAuditor.generateHumanReadableReason(
            state = estimatedState,
            baseProfileIsf = profileBasal * 10.0, // Approximation relative pour l'auditeur
            rawCommand = rawCommand,
            safeCommand = safeCommand
        )
        val auditedCommand = safeCommand.copy(reason = auditedReason)

        // 6. Data Lake CSV persistancy (Pour entraînement V3)
        // L'enregistrement est silencieux et asynchrone par rapport à la boucle de contrôle
        dataLake.recordSnapshot(
            state = estimatedState,
            rawCommand = rawCommand,
            safeCommand = auditedCommand,
            currentTimestamp = currentEpochMs
        )

        // 7. Logging & Shadow metrics
        if (isShadowMode) {
            logShadowDecision(currentState, auditedCommand, profileBasal)
        }

        if (!isActive) return null

        // 8. Quiet Mode Handover (Rollback to AIMI V2 PI Controller)
        // Autodrive V3 (MPC) is mathematically aggressive by nature. For calm waters and slight upstream drifts, 
        // the legacy proportional controller is superior. We yield control (return null) unless V3 is actively fighting.
        val isAggressiveRise = estimatedState.estimatedRa > 0.5 || estimatedState.bgVelocity > 1.0
        val isHigh = estimatedState.bg > 130.0
        val needsSmb = auditedCommand.scheduledMicroBolus > 0.0
        val needsSafetyBrake = auditedCommand.temporaryBasalRate == 0.0

        // 🚀 HYBRID SMOOTHING: Si la correction demandée est minime (ex: < 0.3 U/h d'écart), 
        // on laisse la V2 gérer la fluidité.
        val tbrDelta = kotlin.math.abs(auditedCommand.temporaryBasalRate - profileBasal)
        val isStrongCorrection = tbrDelta > 0.3

        return if (isAggressiveRise || isHigh || needsSmb || needsSafetyBrake || isStrongCorrection) {
            auditedCommand
        } else {
            aapsLogger.debug(
                LTag.APS,
                "💤 [AUTODRIVE_V3] Quiet Mode: Delegating prophylactic TBR adjustments to legacy V2 PI Controller."
            )
            null
        }
    }

    private fun logShadowDecision(state: AutoDriveState, autodriveCommand: AutoDriveCommand, profileBasal: Double) {
        aapsLogger.debug(
            LTag.APS,
            "👽 [AUTODRIVE_SHADOW] BG: ${state.bg} (v: ${String.format("%.1f", state.bgVelocity)}) | " +
            "Est_SI: ${String.format("%.2f", state.estimatedSI)} | Est_Ra: ${String.format("%.2f", state.estimatedRa)} || " +
            "Autodrive dictates: TBR=${autodriveCommand.temporaryBasalRate} U/h, " +
            "SMB=${autodriveCommand.scheduledMicroBolus} U | Reason: ${autodriveCommand.reason}"
        )
    }
}
