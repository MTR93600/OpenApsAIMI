package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveAuditor
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🚀 Autodrive Engine - V3 (ML + CBF + MPC)
 * 
 * Noyau central de l'Autodrive. Coordonne les 6 modules majeurs :
 * 1. Auditor : Valide que les donnés d'entrée sont propres et fiables.
 * 2. Estimator (PSE) : Devine ce qui se passe sous la peau (Ra - Rate of Appearance).
 * 3. Controller (MPC) : Simule l'avenir et calcule l'insuline parfaite pour atterrir à 100mg/dL.
 * 4. Safety Shield (CBF) : Vérifie mathématiquement que la proposition du MPC ne tuera pas le patient.
 * 5. Learner (Online) : Apprend en continu du passé pour ajuster les calculs futurs.
 * 6. Big Data (DataLake) : Enregistre tout pour l'entraînement "Off-device" des réseaux de neurones.
 */
@Singleton
class AutodriveEngine @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val auditor: AutodriveAuditor,
    private val estimator: ContinuousStateEstimator,
    private val controller: MpcController,
    private val shield: ControlBarrierShield,
    private val learner: OnlineLearner,
    private val dataLake: AutodriveDataLake,
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
     * Point d'entrée à chaque cycle APS (Toutes les 5 minutes).
     * 
     * @param state L'état du patient tel que lu par le capteur Dexcom/Libre et la Pompe.
     * @param profileBasal La basal par défaut configurée pour cette heure de la journée.
     * @return La commande d'injection finale.
     */
    fun tick(state: AutoDriveState, profileBasal: Double): AutoDriveCommand? {
        if (!isActive && !isShadowMode) return null
        
        aapsLogger.debug(LTag.APS, "╭━━[ AUTODRIVE ENGINE V3 STARTED ]━━")
        
        // 1. Audit Check : Est-ce qu'on doit même envisager de contrôler la pompe ?
        if (!auditor.isSafeToRun(state)) {
            aapsLogger.debug(LTag.APS, "🛑 [REJECTED] Auditor Flagged system as unsafe or unreadable.")
            return AutoDriveCommand(
                temporaryBasalRate = profileBasal, // Retour à la normale
                scheduledMicroBolus = 0.0,
                isSafe = false,
                reason = "Auditor Reject"
            )
        }
        
        // --- MACHINE LEARNING PRE-FLIGHT ---
        // Exécute le réseau de neurones "Attention Gate" (Phase 8).
        // Si le réseau détecte un danger que les maths n'ont pas vu (Stress, Rebond d'hypo secret, etc.)
        // il peut modifier nos variables avant qu'elles n'entrent dans les maths de contrôle.
        val contextAwareState = attentionGate.processState(state)
        
        // 2. Continuous Learning : Apprentissage des erreurs passées (Phase 5)
        // Mets à jour la sensibilité (Si on s'est trompé il y a 30min).
        learner.learnAndUpdate(contextAwareState, System.currentTimeMillis())
        val learnerResistanceFactor = learner.learnedResistanceFactor

        // Modifie finement la sensibilité lue en l'ajustant avec l'expérience récente
        val adjustedState = contextAwareState.copy(
            estimatedSI = contextAwareState.estimatedSI / learnerResistanceFactor
        )

        // 3. State Estimation (Phase 2) : Deviner l'absorption des glucides (Ra)
        // Si la glycémie monte plus vite que ce que p1 et l'insuline active (IOB) prédisent,
        // c'est qu'on a un taux d'apparition de carb (Ra) fantôme.
        val enrichedState = estimator.updateAndPredict(adjustedState)

        // 4. Model Predictive Control (Phase 3) : Résolution Optimale
        // Calcule le couple (SMB / TBR) parfait pour atteindre l'objectif sans bruit heuristique.
        val rawCommand = controller.calculateOptimalDose(enrichedState, profileBasal)

        // 5. Control Barrier Shield (Phase 4) : Sécurité Absolue
        // Filtre la commande agressive contre une barrière mathématique pour empêcher
        // l'algorithme d'envoyer le patient en hypo (h(x) >= 0).
        val filteredSafeCommand = shield.enforce(rawCommand, enrichedState, profileBasal)

        aapsLogger.debug(
            LTag.APS, 
            "🚀 [AUTODRIVE OUT] -> TBR=${filteredSafeCommand.temporaryBasalRate.format(2)} U/h | SMB=${filteredSafeCommand.scheduledMicroBolus.format(2)} U"
        )
        
        // 6. Data Lake Intake (Phase 1)
        // Sauvegarde de l'état d'entrée pour la télémétrie locale et l'entraînement Cloud du futur
        dataLake.ingest(
            state = enrichedState,
            rawCommand = rawCommand,
            finalCommand = filteredSafeCommand,
            timestampMs = System.currentTimeMillis()
        )
        // Exécute le backup asynchrone pour ne pas ralentir le cycle courant
        dataLake.flushToDiskIfReady()

        aapsLogger.debug(LTag.APS, "╰━[ AUTODRIVE ENGINE V3 FINISHED ]━")

        return if (isActive) filteredSafeCommand else null
    }
    
    // Fonction utilitaire locale pour l'affichage des floats à X décimales
    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
