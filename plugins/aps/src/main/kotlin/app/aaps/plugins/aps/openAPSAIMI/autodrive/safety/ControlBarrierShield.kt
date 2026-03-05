package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 🛡️ Control Barrier Shield (CBF) - Autodrive Phase 4
 * 
 * Filtre de sécurité mathématique qui protège le patient contre les commandes
 * trop agressives du MPC (Phase 3). L'algorithme repose sur les Control Barrier Functions :
 * Il s'assure que la dérivée temporelle de la distance à l'état de vulnérabilité
 * (Hypoglycémie < 75) respecte une borne qui garantit l'invariance mathématique.
 * 
 * Si la commande MPC est sûre : elle passe.
 * Si elle brise la barrière : elle est écrétée exactement sur le bord du domaine sûr.
 */
@Singleton
class ControlBarrierShield @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    // Paramètres de Sécurité CBF
    private val bgDangerThreshold = 80.0 // Marge renforcée pour la limite absolue
    private val gamma = 0.04 // Autorise environ -1.0 mg/dL/min quand h(x) = 25 (BG=105)

    /**
     * Vérifie et modifie si besoin la commande brute proposée par le MPC.
     */
    fun enforce(rawCommand: AutoDriveCommand, state: AutoDriveState, profileBasal: Double): AutoDriveCommand {
        
        // --- 1. Définition de la distance à la zone de danger, h(x) ---
        // h(x) > 0 signifie "On est safe"
        val h = state.bg - bgDangerThreshold

        // --- 2. Dynamique Attendue (Dérivée de h) ---
        // dBG/dt = - (p1 + SI * IOB) * BG + p1 * Gb + Ra
        val p1 = 0.01 // Efficacité de la clairance du glucose basal
        
        // IOB courant. On inclut ici l'impact qu'aurait la dose microbolus proposée.
        // Remarque : Le microbolus s'ajoute à l'IOB net dans l'instant dt (simplification).
        val proposedIobIncrement = rawCommand.scheduledMicroBolus
        
        // Le taux d'insuline additionnel induit par le TBR proposé (sur 5 min)
        // TBR est en U/h, on le convertit en injection sur 5min :
        val tbrIncrement = (rawCommand.temporaryBasalRate / 12.0)
        
        val totalProposedDose = proposedIobIncrement + tbrIncrement
        
        // Dérivée Lie L_f(h) : Évolution naturelle sans insuline actionnée (Dose = 0)
        val lfh = - (p1 + state.estimatedSI * state.iob) * state.bg + (p1 * 100.0) + state.estimatedRa

        // Lie Derivative L_g(h) : Impact de l'action de contrôle (Dose_u)
        val lgh = - state.estimatedSI * state.bg

        // --- 3. Filtre CBF : Inéquation de sécurité ---
        // On veut garantir : L_f(h) + L_g(h) * u >= - gamma * h
        // Si cette inéquation est respectée, le MPC peut faire ce qu'il veut.
        val safetyBoundary = -gamma * h
        val systemEvolution = lfh + (lgh * totalProposedDose)

        if (systemEvolution >= safetyBoundary) {
            // SAFE: La trajectoire reste dans le domaine invariant
            return rawCommand
        }

        // --- 4. Violation : Filtrage Actif ---
        // La dose est trop agressive. On résout u pour être *exactement* sur la frontière
        // L_f(h) + L_g(h) * u_safe = - gamma * h
        // u_safe = (-gamma * h - L_f(h)) / L_g(h)
        val safeU = (-gamma * h - lfh) / lgh

        var safeTbr = profileBasal
        var safeSmb = 0.0
        var reason = "${rawCommand.reason} | [🛡️ CBF SATURATED]"

        // Si safeU est négatif, même une dose de 0 ne sauvera pas le patient (l'hypothèse est déjà franchie)
        // Il faut alors couper l'insuline complètement (Suspension).
        if (safeU <= 0.0) {
            safeTbr = 0.0
            safeSmb = 0.0
            reason += " ZERO BASAL (Hypo Bound)"
        } else {
            // Le safeU total autorisé doit être réparti en TBR et SMB.
            // On convertit safeU (insuline absolue sur 5 min) en commandes APS.
            if (safeU <= 0.2) {
                // Petite dose -> TBR
                safeTbr = min(safeU * 12.0, rawCommand.temporaryBasalRate)
                safeSmb = 0.0
            } else {
                // Grosse dose -> On garde un peu de TBR et on met le reste en SMB
                safeTbr = min(profileBasal, rawCommand.temporaryBasalRate)
                val remainingU = safeU - (safeTbr / 12.0)
                safeSmb = min(remainingU, rawCommand.scheduledMicroBolus)
            }
        }

        aapsLogger.debug(
            LTag.APS,
            "🛡️ [CBF SHIELD] MPC Restricted. Required H($h) Boundary($safetyBoundary). Overridden: U_req=${totalProposedDose.format(2)} -> U_safe=${safeU.format(2)}"
        )

        return AutoDriveCommand(
            temporaryBasalRate = safeTbr,
            scheduledMicroBolus = safeSmb,
            reason = reason
        )
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
