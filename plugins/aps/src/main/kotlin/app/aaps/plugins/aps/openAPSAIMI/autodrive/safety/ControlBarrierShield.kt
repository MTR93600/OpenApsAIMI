package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
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
    private val METABOLIC_SI_BASE = 0.0012 // Calibration factor (Phase 12)
    
    // État persistant pour le calcul de l'accélération
    private var lastBgVelocity: Double? = null

    // Paramètres de Sécurité CBF
    private val bgDangerThreshold = 80.0 // Marge renforcée pour la limite absolue
    private val nominalGamma = 0.04       // Valeur de base (0.04 = ~1.0 mg/dL/min à h=25)

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
        
        // Lie Derivative L_f(h) : Évolution naturelle sans insuline actionnée (Dose = 0)
        val siMetabolic = state.estimatedSI * METABOLIC_SI_BASE
        val lfh = - (p1 + siMetabolic * state.iob) * state.bg + (p1 * 100.0) + state.estimatedRa

        // Lie Derivative L_g(h) : Impact de l'action de contrôle (Dose_u)
        val lgh = - siMetabolic * state.bg

        // --- 3. Filtre CBF : Inéquation de sécurité ---
        // L'accélération (a) détermine la rigidité de la barrière.
        // Si la chute s'accélère (a < 0), on réduit gamma pour durcir le bouclier.
        val currentVelocity = state.bgVelocity
        val accel = lastBgVelocity?.let { (currentVelocity - it) / 5.0 } ?: 0.0
        lastBgVelocity = currentVelocity
        
        val activeGamma = if (accel < -0.05 && currentVelocity < 0) {
            // Accélération vers le bas détectée : On divise gamma par 2 (Bouclier Rigide)
            nominalGamma * 0.5
        } else {
            nominalGamma
        }

        // On veut garantir : L_f(h) + L_g(h) * u >= - activeGamma * h
        val safetyBoundary = -activeGamma * h
        val systemEvolution = lfh + (lgh * totalProposedDose)

        var currentReason = rawCommand.reason
        if (activeGamma < nominalGamma) {
            currentReason += " | [🛡️ ACCEL_GUARD]"
        }
        
        var (finalTbr, finalSmb) = if (systemEvolution >= safetyBoundary) {
            // 🛡️ CBF SAFE
            Pair(rawCommand.temporaryBasalRate, rawCommand.scheduledMicroBolus)
        } else {
            // 🚨 CBF VIOLATION: Filtrage Actif
            val safeU = (-activeGamma * h - lfh) / lgh
            
            val (cbfTbr, cbfSmb) = if (safeU <= 0.0) {
                Pair(0.0, 0.0) // Suspension complète
            } else if (safeU <= 0.2) {
                Pair(min(safeU * 12.0, rawCommand.temporaryBasalRate), 0.0)
            } else {
                val tbr = min(profileBasal, rawCommand.temporaryBasalRate)
                val smb = min(safeU - (tbr / 12.0), rawCommand.scheduledMicroBolus)
                Pair(tbr, max(0.0, smb))
            }
            
            currentReason = "${rawCommand.reason} | [🛡️ CBF SATURATED]"
            if (safeU <= 0.0) currentReason += " ZERO BASAL (Hypo Bound)"
            
            aapsLogger.debug(
                LTag.APS,
                "🛡️ [CBF SHIELD] MPC Restricted. Required H($h) Boundary($safetyBoundary). Overridden: U_req=${totalProposedDose.format(2)} -> U_safe=${safeU.format(2)}"
            )
            Pair(cbfTbr, cbfSmb)
        }

        // --- 5. MaxIOB Enforcement (Phase 4+) ---
        val currentIob = state.iob
        val maxAllowedDose = max(0.0, state.maxIOB - currentIob)
        val currentProposedTotalU = (finalTbr / 12.0) + finalSmb
        
        if (currentProposedTotalU > maxAllowedDose) {
            // Surcharge détectée : On coupe le SMB d'abord, puis la TBR si nécessaire.
            val truncatedSmb = min(finalSmb, maxAllowedDose)
            val remainingForTbr = max(0.0, maxAllowedDose - truncatedSmb)
            val truncatedTbr = min(finalTbr, remainingForTbr * 12.0)
            
            finalSmb = truncatedSmb
            finalTbr = truncatedTbr
            currentReason = if (currentReason.contains("V3") || currentReason.contains("MPC")) currentReason else "V3: État stable"
            currentReason += " | [🛡️ CBF SATURATED] MAX_IOB Limit reached"
            
            aapsLogger.debug(
                LTag.APS,
                "🛡️ [CBF SHIELD] MAX_IOB Violation. IOB=${currentIob.format(2)} Max=${state.maxIOB.format(2)} | Truncating Total=${currentProposedTotalU.format(2)} -> ${maxAllowedDose.format(2)}"
            )
        }

        return AutoDriveCommand(
            temporaryBasalRate = finalTbr,
            scheduledMicroBolus = finalSmb,
            reason = currentReason
        )
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
