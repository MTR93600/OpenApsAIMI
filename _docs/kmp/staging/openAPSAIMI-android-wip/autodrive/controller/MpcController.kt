package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryMpcFeedForward
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 🧮 Model Predictive Controller (MPC) - Autodrive Phase 3
 * 
 * Ce contrôleur remplace complètement les heuristiques classiques (PD, Sigmoïde).
 * Il prend l'état courant estimé (BG, SI, Ra) et simule le tur sur N étapes pour trouver
 * la séquence d'insuline optimale minimisant l'écart par rapport à la cible (100 mg/dL).
 * 
 * Il ne gère pas la sécurité absolue (Hypo). Cela sera géré par la Phase 4 (CBF).
 */
@SingleIn(AppScope::class)
class MpcController @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {
    // 🔬 Action de l'insuline : voir InsulinActionModel.
    // L'ancien `METABOLIC_SI_BASE = 0.05` vivait ici, dans la barrière et dans l'estimateur avec
    // trois valeurs différentes. La calibration est désormais énoncée en une constante de temps.

    // Paramètres MPC
    private val horizonMinutes = 180          // On vérifie sur 180 minutes (Weighted Horizon)
    private val stepMinutes = 5              // Pas de simulation
    private val steps = horizonMinutes / stepMinutes
    // Poids de base (Modifiés dynamiquement si Nuit/Jour)
    private val qBg = 1.0                    // Pénalité pour l'écart au Target BG

    /** Hard cap on grid search size — prevents CPU blow-ups when maxSMB is very high. */
    internal companion object {
        const val MAX_DOSE_CANDIDATES = 400
        const val FINE_SEARCH_STEP_U = 0.005
        const val COARSE_SEARCH_STEP_U = 0.015

        /**
         * The doses the solver is allowed to consider, over the whole safe domain `[0, maxSafeDoseU]`.
         *
         * The candidate count is capped at [MAX_DOSE_CANDIDATES] for CPU. That cap used to truncate the
         * **domain** instead of coarsening the **step**: the loop stopped after 400 entries, so at
         * [FINE_SEARCH_STEP_U] the largest dose the solver could ever score was
         * `399 * 0.005 = 1.995 U`, whatever `maxSafeDoseU` said.
         *
         * That was measurable in production. `isHyperPlateauQuiet` requires `|combinedDelta| < 1.2`, so
         * every rising tick took the fine step and the 1.995 U ceiling, while a quiet plateau took the
         * coarse step and a 5.985 U ceiling — the domain was widest exactly when the need was smallest.
         * On 172 rising ticks at BG >= 140 with no carbs on board, `model_output_u` had a maximum of
         * **1.87 U** and a p90 of 1.85, against `maxSafeDoseU` of about 2.5 U. The identity is exact:
         * `1.995 - profileBasal * 3 / 12 = 1.995 - 0.58 / 4 = 1.850`, since the TBR share is subtracted
         * from `bestDose` afterwards. The solver never saw the top fifth of its own authorised domain.
         *
         * The step is now widened so the last candidate reaches `maxSafeDoseU`. The CPU guard is
         * unchanged — never more than [MAX_DOSE_CANDIDATES] candidates — and the resolution only
         * coarsens above a domain of `MAX_DOSE_CANDIDATES * searchStep` (1.995 U at the fine step),
         * where it becomes `maxSafeDoseU / 399`: 0.0063 U at a 2.5 U domain, still an order of magnitude
         * finer than the pump's own bolus granularity.
         *
         * This widens the search domain only. It cannot exceed `maxSafeDoseU`, which is
         * `min(activeMaxSmb, weight * uPerKg)` and is not touched here, and every bound downstream —
         * `ControlBarrierShield`, the catalogue caps, `finalizeAndCapSMB`, `applySmbUnits` — still
         * applies. It cannot add insulin on a tick whose optimum is interior to the domain, which is the
         * case whenever the cost function is already asking for less than the ceiling.
         */
        fun buildDoseCandidates(maxSafeDoseU: Double, searchStep: Double): List<Double> {
            if (!maxSafeDoseU.isFinite() || maxSafeDoseU <= 0.0) return listOf(0.0)
            val requested = searchStep.takeIf { it.isFinite() && it > 0.0 } ?: FINE_SEARCH_STEP_U
            // Coarsen the step rather than cut the domain: the last candidate must reach maxSafeDoseU.
            val minStepForFullDomain = maxSafeDoseU / (MAX_DOSE_CANDIDATES - 1)
            val step = max(requested, minStepForFullDomain)
            val out = ArrayList<Double>(min(MAX_DOSE_CANDIDATES, (maxSafeDoseU / step).toInt() + 2))
            var dose = 0.0
            while (dose <= maxSafeDoseU + step * 0.5 && out.size < MAX_DOSE_CANDIDATES) {
                out.add(min(dose, maxSafeDoseU))
                dose += step
            }
            if (out.isEmpty()) out.add(0.0)
            return out
        }
    }

    /**
     * Calcule la dose optimale pour les 5 prochaines minutes (Receding Horizon).
     */
    fun calculateOptimalDose(state: AutoDriveState, profileBasal: Double, lgsThreshold: Double): AutoDriveCommand {
        // Optimisation Newton-Raphson 1D simplifiée ou Recherche Dichotomique pour trouver min(J)
        // La fonction J(u) est supposée convexe par rapport à la dose d'insuline injectée maintement.
        

        // 🌙 PROTECTION NOCTURNE ANTI-HYPO (Phase 3+)
        // La nuit, on dort : pas de repas en cours, la sensibilité est souvent stable.
        // On interdit au solver de chercher la perfection absolue et on sur-pénalise l'injection
        // d'insuline rapide (SMB) pour privilégier des basales douces.
        val activeTargetBg: Double
        val activeRInsulin: Double
        val activeMaxSmb: Double

        if (state.isNight) {
            activeTargetBg = 110.0 // On rehausse la cible de sécurité
            activeRInsulin = 150.0 // On multiplie par 3 le coût (plus proactif que 5x)
            activeMaxSmb = 0.5 // On refuse d'envoyer de fortes doses d'un coup
        } else {
            activeTargetBg = 100.0

            val htrTier = HyperSeverityTier.entries.getOrElse(state.htrTierOrdinal) { HyperSeverityTier.OFF }
            val htrMaxMult = HyperTrajectoryMpcFeedForward.aggressiveMaxSmbMultiplier(
                tier = htrTier,
                projectionLeadMgdl = state.htrProjectionLeadMgdl,
            )
            
            // 🚀 DAWN GUARD CONSERVATISM
            // Si on suspecte un pic de cortisol (Matériel + Heure + Pas de glucides + Pas de pas), 
            // on rend l'IA extrêmement prudente sur l'envoi de bolus (SMB).
            val isDawnWindow = state.hour in 4..10
            val isLowActivity = state.steps < 200
            val legacyDawnRise = isDawnWindow && isLowActivity && (state.hr > state.rhr + 5) && state.cob < 0.1
            val isDawnRiseSuspected = (state.physioExtendedDawnGuard && state.cob < 0.1) || legacyDawnRise

            if (isDawnRiseSuspected) {
                val dawnCostMult = if (state.physioExtendedDawnGuard) {
                    4.0
                } else {
                    1.0
                }
                activeRInsulin = 100.0 * dawnCostMult.coerceAtMost(4.0)
                activeMaxSmb = state.maxSMB * if (state.physioExtendedDawnGuard) 0.45 else 0.5
            } else if (state.estimatedRa > 3.0 || htrTier >= HyperSeverityTier.ANTICIPATORY) {
                // 🧨 DYNAMIC AGGRESSIVENESS (Phase 11 - Unannounced Meal Crushing) + HTR feed-forward
                activeRInsulin = if (htrTier >= HyperSeverityTier.ANTICIPATORY) 12.0 else 10.0
                activeMaxSmb = state.highBgMaxSMB * htrMaxMult
            } else if (state.bg > 120.0) {
                activeRInsulin = 20.0
                activeMaxSmb = state.highBgMaxSMB * htrMaxMult.coerceAtLeast(1.0)
            } else {
                activeRInsulin = 30.0
                activeMaxSmb = state.maxSMB
            }
        }

        // 🛡️ WEIGHT-AWARENESS SAFETY CAP (Phase 7)
        // Bridle le domaine de recherche MPC : min(plafond SMB actif, poids × coeff).
        // Coeff configurable (OApsAIMIMpcInsulinUPerKgPerStep), défaut 0.065 U/kg/5min (~+30% vs ancien 0.05 fixe).
        val uPerKg = preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
        val boundedMaxSmb = if (activeMaxSmb.isFinite() && activeMaxSmb > 0.0) activeMaxSmb else state.maxSMB.coerceAtLeast(0.0)
        val weightCap = state.patientWeightKg.takeIf { it.isFinite() && it > 0.0 }?.let { it * uPerKg } ?: boundedMaxSmb
        val maxSafeDoseU = min(boundedMaxSmb, weightCap).coerceAtLeast(0.0)

        val isHyperPlateauQuiet = !state.isNight &&
            state.bg > 145.0 &&
            abs(state.bgVelocity) < 0.35 &&
            abs(state.combinedDelta) < 1.2 &&
            state.estimatedRa < 2.5

        // Résolution via balayage fin sur le domaine praticable Sécurisé [0.0 , maxSafeDoseU]
        val searchStep = if (isHyperPlateauQuiet) COARSE_SEARCH_STEP_U else FINE_SEARCH_STEP_U
        val doseCandidates = Companion.buildDoseCandidates(maxSafeDoseU, searchStep)

        // Hyper plateau: act on 180m horizon only (still safe); skip extra alignment work.
        val horizons = if (isHyperPlateauQuiet) listOf(180) else listOf(60, 120, 180)
        val optimalDoses = mutableMapOf<Int, Double>()

        for (h in horizons) {
            var bestDoseForH = 0.0
            var minCostForH = Double.MAX_VALUE
            val hSteps = h / stepMinutes

            for (candidateDose in doseCandidates) {
                val cost = simulateAndCost(candidateDose, state, activeTargetBg, activeRInsulin, lgsThreshold, state.isNight, hSteps)
                if (cost < minCostForH) {
                    minCostForH = cost
                    bestDoseForH = candidateDose
                }
            }
            optimalDoses[h] = bestDoseForH
        }

        val bestDose = optimalDoses[180] ?: 0.0 // We act on the safest (180w) result

        if (isHyperPlateauQuiet) {
            aapsLogger.debug(
                LTag.APS,
                "🧮 [MPC] Hyper-plateau quiet mode: candidates=${doseCandidates.size} step=${searchStep} 180w=${bestDose.format(2)}U",
            )
        } else {
            aapsLogger.debug(
                LTag.APS,
                "🧮 [MPC] Alignment Comparison: 60=${horizonDoseLabel(optimalDoses, 60)}U, " +
                    "120=${horizonDoseLabel(optimalDoses, 120)}U, 180w=${bestDose.format(2)}U",
            )
        }

        // Traitement de la sortie :
        val maxTbrMultiplier = if (state.isNight) 5.0 else 3.0
        var tbrUph = min(bestDose * 12.0, profileBasal * maxTbrMultiplier)
        
        // 🛡️ DYNAMIC BASAL CUSHION (Amortisseur de Basale)
        // On remplace le blocage brut à 100 mg/dL par un palier dépendant de la vélocité.
        // Cela permet de couper la basale à 110-120 si on chute, tout en protégeant le profil si on est stable.
        val floorMultiplier = when {
            state.bg > 130.0 -> 1.0            // Zone haute : Basale profil maintenue
            state.bg > 100.0 -> {
                when {
                    state.bgVelocity > -0.5 -> 1.0 // Stable/Montée : Garder 100%
                    state.bgVelocity < -1.5 -> 0.0 // Chute rapide : Autoriser 0%
                    else -> 0.5                     // Descente douce : Amortisseur à 50%
                }
            }
            else -> 0.0                        // Zone < 100mg/dL : Liberté totale (0%)
        }
        
        val floorTbr = profileBasal * floorMultiplier
        if (tbrUph < floorTbr) {
            tbrUph = floorTbr
        }

        val smbU = max(0.0, bestDose - (tbrUph / 12.0))
        val comparisonStr = formatHorizonComparison(optimalDoses, bestDose)

        return AutoDriveCommand(
            temporaryBasalRate = tbrUph,
            scheduledMicroBolus = smbU,
            reason = "[MPC] $comparisonStr"
        )
    }

    /**
     * Simule la trajectoire pour une dose candidate U donnée et retourne le coût total J.
     */
    private fun simulateAndCost(doseU: Double, startState: AutoDriveState, activeTargetBg: Double, activeRInsulin: Double, lgsThreshold: Double, isNight: Boolean, customSteps: Int? = null): Double {
        var currentBg = startState.bg
        // 🛡️ IOB SAFETY CAP (Phase 17 revision):
        // Cap simulation at user's strict maxIOB to prevent LGS panic blockage.
        val effectiveMaxIob = startState.maxIOB
        val cappedIob = (startState.iob + doseU).coerceAtMost(effectiveMaxIob + doseU)
        var currentIob = cappedIob
        var totalCost = 0.0
        val targetSteps = customSteps ?: steps

        // Modèle Métabolique Simplifié EKF/UKF (Bergman Minimal Model paramétré par state)
        // p1 = efficacité de base du glucose à se résorber seul (GEZI)
        val p1 = 0.015 
        
        // La sensibilité est dynamique, estimée dans l'état, mise à l'échelle métabolique
        val si = InsulinActionModel.controlCoefficient(
            isfMgdlPerU = InsulinActionModel.isfFromStateSi(startState.estimatedSI),
            tauMin = InsulinActionModel.MPC_TAU_MIN,
        )

        for (k in 1..targetSteps) {
            // -- Dynamique du Glucose (Simulation Euler sur 5 min) --
            // Formulation classique : dBG/dt = - [p1 + SI * IOB]*(BG) + p1*BG_Target + Ra
            // IMPORTANT : Limite biologique stricte à 0.0 pour empêcher l'explosion mathématique avec une IOB très négative
            val glucoseClearanceRate = max(0.0, p1 + (si * currentIob))
            val deltaBgPerMin = - (glucoseClearanceRate) * currentBg + (p1 * activeTargetBg) + startState.estimatedRa
            val deltaBgPer5 = deltaBgPerMin * stepMinutes

            currentBg += deltaBgPer5
            
            // -- Dynamique de l'Insuline --
            // Décroissance de l'IOB selon un modèle à 1 compartiment (demi-vie ~ 50 min)
            currentIob *= 0.90 

            // -- Évaluation du Coût (Weighted Horizon) --
            // On applique une pondération temporelle (W_k) pour privilégier la réactivité court-terme
            // tout en gardant une surveillance critique sur le stacking long-terme.
            val currentTimeMin = k * stepMinutes
            val timeWeight = when {
                currentTimeMin <= 60 -> 1.0     // 0-60m : Pleine réactivité
                currentTimeMin <= 120 -> 0.5    // 60-120m : Convergence stratégique
                else -> 0.2                     // 120-180m : Sécurité anti-stacking
            }

            // Pénalité asymétrique : l'hypo (BG < Cible) est lourdement pénalisée par rapport à l'hyper
            val errorBg = currentBg - activeTargetBg
            val scaledErrorBg = errorBg / 10.0 // Divide par 10 pour que le carré (divisé par 100) soit comparable au coût de RInsulin
            
            // 🚀 REACTIVITY BOOST: On triple le poids de l'erreur en hyper (>0) pour écraser le pic
            val qPenalty = if (errorBg < 0) qBg * 5.0 else qBg * 3.0
            
            totalCost += timeWeight * (qPenalty * scaledErrorBg * scaledErrorBg)
            // 🛡️ LETHAL SAFETY FALLBACK (Check throughout the horizon)
            // La simulation ne doit JAMAIS franchir le seuil létal absolu. 
            // La nuit, on renforce la marge à +10 mg/dL pour forcer la coupure préventive.
            val lgsBuffer = if (isNight) 10.0 else 5.0
            if (currentBg < lgsThreshold + lgsBuffer) {
                totalCost += 1_000_000.0
            }
        }

        // Ajout du coût de régularisation R
        // On réintroduit un léger terme linéaire pour tuer les micro-bolus inutiles (bruit)
        totalCost += (activeRInsulin * doseU * doseU) + (activeRInsulin * 0.1 * doseU)

        return totalCost
    }

    /** Safe labels when [horizons] omits 60/120 (hyper-plateau quiet uses 180 only). */
    private fun horizonDoseLabel(optimalDoses: Map<Int, Double>, minutes: Int): String =
        optimalDoses[minutes]?.format(2) ?: "n/a"

    private fun formatHorizonComparison(optimalDoses: Map<Int, Double>, bestDose: Double): String =
        "H:[60:${horizonDoseLabel(optimalDoses, 60)}|120:${horizonDoseLabel(optimalDoses, 120)}|180:${bestDose.format(2)}]"

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
