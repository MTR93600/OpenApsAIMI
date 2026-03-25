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
    // --- ⚖️ METABOLIC_SI_BASE (Phase 3 Synchronization) ---
    private val METABOLIC_SI_BASE = 0.05

    
    // État persistant pour le calcul de l'accélération
    private var lastBgVelocity: Double? = null

    // Paramètres de Sécurité CBF
    private val bgDangerThreshold = 80.0 // Marge renforcée pour la limite absolue
    private val nominalGamma = 0.04       // Valeur de base (0.04 = ~1.0 mg/dL/min à h=25)
    private val mealRiseGammaBoost = 1.35
    private val mealRiseGammaBoostMax = 0.07
    // Higher cap used only when meal is strongly confirmed (high Ra) and BG is high/rising.
    private val mealRiseGammaBoostMaxHigh = 0.12

    /**
     * Vérifie et modifie si besoin la commande brute proposée par le MPC.
     */
    fun enforce(rawCommand: AutoDriveCommand, state: AutoDriveState, profileBasal: Double): AutoDriveCommand {
        
        // --- 1. Définition de la distance à la zone de danger, h(x) ---
        // h(x) > 0 signifie "On est safe"
        val h = state.bg - bgDangerThreshold

        // --- 2. Dynamique Attendue (Dérivée de h) ---
        // dBG/dt = - (p1 + SI * IOB) * BG + p1 * Gb + Ra
        val p1 = 0.015 // Efficacité de la clairance du glucose basal (Aligné sur PSE)
        
        // IOB courant. On inclut ici l'impact qu'aurait la dose microbolus proposée.
        // Remarque : Le microbolus s'ajoute à l'IOB net dans l'instant dt (simplification).
        val proposedIobIncrement = rawCommand.scheduledMicroBolus
        
        // Le taux d'insuline additionnel induit par le TBR proposé (sur 5 min)
        // TBR est en U/h, on le convertit en injection sur 5min :
        val tbrIncrement = (rawCommand.temporaryBasalRate / 12.0)
        
        val totalProposedDose = proposedIobIncrement + tbrIncrement
        
        // Lie Derivative L_f(h) : Évolution naturelle sans insuline actionnée (Dose = 0)
        val siMetabolic = state.estimatedSI * METABOLIC_SI_BASE
        val lfh = - p1 * (state.bg - 100.0) - (siMetabolic * state.iob * state.bg) + state.estimatedRa

        // Lie Derivative L_g(h) : Impact de l'action de contrôle (Dose_u)
        val lgh = - siMetabolic * state.bg

        // --- 3. Filtre CBF : Inéquation de sécurité ---
        // L'accélération (a) détermine la rigidité de la barrière.
        // Si la chute s'accélère (a < 0), on réduit gamma pour durcir le bouclier.
        val currentVelocity = state.bgVelocity
        val accel = lastBgVelocity?.let { (currentVelocity - it) / 5.0 } ?: 0.0
        lastBgVelocity = currentVelocity
        
        var activeGamma = if (accel < -0.05 && currentVelocity < 0) {
            // Accélération vers le bas détectée : On divise gamma par 2 (Bouclier Rigide)
            nominalGamma * 0.5
        } else {
            nominalGamma
        }

        // Meal-priority context relaxation:
        // confirmed rise can start before 180 mg/dL; avoid over-braking while keeping hard bounds.
        val strongMealRiseContext =
            state.bg >= 145.0 &&
                state.bgVelocity >= 0.2 &&
                (state.cob >= 6.0 || state.uamConfidence >= 0.45 || state.combinedDelta >= 2.0)

        // Extra confirmation: high Ra + rising fast.
        // We intentionally trigger BEFORE BG is very high by projecting ahead.
        //
        // Rationale:
        // - Glucose absorption (Ra) can outpace insulin action (especially in adipose tissue).
        // - When Ra is high, we must anticipate the rise earlier (longer "lead" time).
        // - We keep accel guard: if the system is accelerating downward, do NOT relax.
        val carbLeadMin = when {
            state.estimatedRa >= 4.0 -> 45.0
            state.estimatedRa >= 3.0 -> 40.0
            state.estimatedRa >= 2.0 -> 35.0
            else -> 28.0
        }
        val accelAdjMin = when {
            accel > 0.06 && currentVelocity > 0.5 -> 6.0   // rise is accelerating → anticipate more
            accel > 0.03 && currentVelocity > 0.3 -> 3.0
            else -> 0.0
        }
        val anticipationHorizonMin = (carbLeadMin + accelAdjMin).coerceIn(25.0, 55.0)
        val projectedBgAtHorizon = state.bg + state.bgVelocity * anticipationHorizonMin
        val isHighRiseMeal =
            strongMealRiseContext &&
                state.estimatedRa >= 2.0 &&
                state.bgVelocity >= 0.6 &&
                // Either already above a moderate threshold, or likely to cross into dangerous hyper range soon.
                (state.bg >= 150.0 || projectedBgAtHorizon >= 185.0) &&
                !(accel < -0.05 && currentVelocity < 0)

        if (strongMealRiseContext && activeGamma >= nominalGamma) {
            val maxCap = if (isHighRiseMeal) mealRiseGammaBoostMaxHigh else mealRiseGammaBoostMax
            val boosted = (activeGamma * mealRiseGammaBoost).coerceAtMost(maxCap)
            if (boosted > activeGamma) {
                activeGamma = boosted
                aapsLogger.debug(
                    LTag.APS,
                    "🛡️ [CBF SHIELD] Meal-priority relaxation active. " +
                        "Gamma=${activeGamma.format(3)} BG=${state.bg.format(1)} dBG=${state.bgVelocity.format(2)} " +
                        "Ra=${state.estimatedRa.format(2)} COB=${state.cob.format(1)} UAM=${state.uamConfidence.format(2)} cΔ=${state.combinedDelta.format(2)}" +
                        (if (isHighRiseMeal) " [HIGH_RISE_MEAL]" else "")
                )
            }
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
                // In strongly confirmed meal-rise (high BG, rising, high Ra), prioritize SMB over TBR
                // to avoid "CBF blocks almost everything" while still respecting the barrier constraint (safeU budget).
                val preferSmb = isHighRiseMeal && rawCommand.scheduledMicroBolus > 0.0
                val tbr = if (preferSmb) {
                    // keep some basal support if requested, but free most of the safeU budget for SMB
                    min(profileBasal * 0.2, rawCommand.temporaryBasalRate)
                } else {
                    min(profileBasal, rawCommand.temporaryBasalRate)
                }
                val smbBudget = safeU - (tbr / 12.0)
                val smb = min(smbBudget, rawCommand.scheduledMicroBolus)
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

        // --- 5. MaxIOB Enforcement (Phase 4 / Strict User Limit) ---
        val currentIob = state.iob
        
        // 🛡️ STRICT USER CAP: Follow exactly the preference set by the user.
        val effectiveMaxIob = state.maxIOB
        
        val maxAllowedDose = max(0.0, effectiveMaxIob - currentIob)
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
