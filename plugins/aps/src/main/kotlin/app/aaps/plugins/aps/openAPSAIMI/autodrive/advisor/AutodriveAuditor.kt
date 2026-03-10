package app.aaps.plugins.aps.openAPSAIMI.autodrive.advisor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 👨‍🏫 Autodrive Auditor (Explicabilité I.A.)
 * 
 * Cette classe agit comme un traducteur entre les mathématiques pures du système
 * iLet-like (MPC, UKF, CBF) et le cerveau humain. 
 * Elle génère des phrases explicatives qui apparaîtront dans les logs et l'UI
 * "reason" de la pompe pour justifier pourquoi l'algorithme a pris telle ou telle décision.
 */
@Singleton
class AutodriveAuditor @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    /**
     * Analyse l'état physiologique estimé par l'UKF et la décision sécurisée finale,
     * puis construit une justification humaine.
     */
    fun generateHumanReadableReason(
        state: AutoDriveState,
        baseProfileIsf: Double,
        rawCommand: AutoDriveCommand,
        safeCommand: AutoDriveCommand
    ): String {
        val explanations = mutableListOf<String>()

        // 1. Analyse de la Sensibilité (SI)
        // L'ISF est l'inverse de la sensibilité. Un petit ISF = Très sensible. Un grand ISF = Résistant.
        val currentIsfMgDl = state.estimatedSI * 10000.0 // Reconversion approximative
        val isfRatio = baseProfileIsf / currentIsfMgDl

        if (isfRatio > 1.3) {
            explanations.add("🔥 Forte Résistance/Inflammation tractée")
        } else if (isfRatio < 0.7) {
            explanations.add("🏃 Forte Sensibilité/Exercice détectée")
        }

        // 2. Analyse de l'Absorption (Ra) détectée par l'UKF
        if (state.estimatedRa > 3.0) {
            explanations.add("🍽️ Repas Fantôme Massif (> 3mg/dL/min)")
        } else if (state.estimatedRa > 1.0) {
            explanations.add("🍏 Digestion en cours")
        }

        // 3. Justification de la Dose (MPC)
        val intention = if (rawCommand.scheduledMicroBolus > 0.0) {
            "Frappe SMB (${rawCommand.scheduledMicroBolus.format(1)}U)"
        } else if (rawCommand.temporaryBasalRate > 0.0) {
            "Soutien TBR (${rawCommand.temporaryBasalRate.format(1)}U/h)"
        } else {
            "Suspension"
        }
        explanations.add("🧮 MPC ordonne $intention")

        // 4. Interception du Bouclier (CBF)
        if (rawCommand.scheduledMicroBolus > safeCommand.scheduledMicroBolus || rawCommand.temporaryBasalRate > safeCommand.temporaryBasalRate) {
            val rejectedU = (rawCommand.scheduledMicroBolus + (rawCommand.temporaryBasalRate / 12.0)) - (safeCommand.scheduledMicroBolus + (safeCommand.temporaryBasalRate / 12.0))
            explanations.add("🛡️ CBF a bloqué ${rejectedU.format(1)}U (Risque Hypo à 80mg/dL)")
        }

        val finalReason = explanations.joinToString(" | ")

        aapsLogger.debug(
            LTag.APS,
            "👨‍🏫 [AUDITOR] $finalReason"
        )

        return finalReason
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
