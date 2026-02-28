package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator // 🧠 PSE
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController // 🧮 MPC
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield // 🛡️ CBF
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner // 🎓 Learner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.advisor.AutodriveAuditor // 👨‍🏫 Auditor
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
    private val autodriveAuditor: AutodriveAuditor
) {

    private var isActive = false // Feature Toggle pour le monde réel
    private var isShadowMode = true // Tourne silencieusement

    fun setShadowMode(enabled: Boolean) {
        isShadowMode = enabled
    }

    /**
     * Point d'entrée principal à chaque Tique (5 min) depuis DetermineBasalAIMI2.
     */
    fun tick(currentState: AutoDriveState, profileBasal: Double, currentEpochMs: Long = System.currentTimeMillis()): AutoDriveCommand? {
        if (!isActive && !isShadowMode) return null

        // 0. Le Processus d'apprentissage en ligne s'exécute pour affiner les paramètres
        onlineLearner.learnAndUpdate(currentState, currentEpochMs)

        // On injecte le facteur appris dans l'état (Phase 2 -> Phase 5)
        // Note: Ici c'est super simplifié, le SI global baisse ou monte selon le facteur.
        val learningAdjustedState = currentState.copy(
            estimatedSI = currentState.estimatedSI * onlineLearner.learnedResistanceFactor
        )

        // 1. PSE (Physiological State Estimator) Update
        val estimatedState = stateEstimator.updateAndPredict(learningAdjustedState)

        // 2. MPC (Model Predictive Controller) Calculation
        val rawCommand = mpcController.calculateOptimalDose(estimatedState, profileBasal)

        // 3. CBF (Control Barrier Shield) Safety Check
        val safeCommand = safetyShield.enforce(rawCommand, estimatedState, profileBasal)

        // 4. Explicabilité de l'IA (Auditor Traducteur)
        val auditedReason = autodriveAuditor.generateHumanReadableReason(
            state = estimatedState,
            baseProfileIsf = profileBasal * 10.0, // Approximation relative pour l'auditeur
            rawCommand = rawCommand,
            safeCommand = safeCommand
        )
        val auditedCommand = safeCommand.copy(reason = auditedReason)

        // 5. Logging & Shadow metrics
        if (isShadowMode) {
            logShadowDecision(currentState, auditedCommand, profileBasal)
        }

        return if (isActive) auditedCommand else null
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
