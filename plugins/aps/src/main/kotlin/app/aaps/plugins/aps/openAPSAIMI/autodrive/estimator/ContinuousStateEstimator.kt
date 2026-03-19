package app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 🧠 Continuous State Estimator (PSE)
 * 
 * Utilise une variante simplifiée du modèle de Bergman (Minimal Model) couplée à un
 * filtre de Kalman Étendu (EKF) ou Unscented (UKF) pour déduire les états cachés :
 * - SI (Insulin Sensitivity)
 * - Ra (Rate of Appearance des glucides)
 * 
 * Ces variables ne sont pas directement mesurables mais sont déduites de l'erreur entre
 * la prédiction de glycémie et la vraie mesure CGM.
 */
@Singleton
class ContinuousStateEstimator @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    // Paramètres d'état internes persistants (Covariance, etc.)
    private var pSi = 0.1      // Incertitude sur la Sensibilité
    private var pRa = 1.0      // Incertitude sur l'Absorption
    private var lastSi = 1.0   // Dernier SI estimé
    private var lastRa = 0.0   // Dernier Ra estimé

    /**
     * Reçoit le nouvel état réel depuis la pompe/capteur et met à jour l'estimation
     * des variables physiologiques cachées (Principalement Ra = Rate of Appearance).
     * 
     * [FCL 11.0] Modification : state.estimatedSI contient DÉJÀ la super-sensibilité
     * calculée par AIMI V1 (WCycle + Heart Rate + Autosens). Le PSE va donc l'utiliser
     * comme "truth" robuste et se focaliser sur l'estimation du repas fantôme (Ra).
     */
    fun updateAndPredict(actualState: AutoDriveState): AutoDriveState {
        
        // 1. Modèle Interne Prédictif (Ce qu'on PENSE qui aurait dû se passer sans repas)
        // dBG/dt = - [p1 + SI * IOB]*(BG - BG_target) + Ra
        val p1 = 0.015 // Efficacité du glucose à retourner à la basale
        val bgTarget = 100.0
        
        // Dynamique naturelle attendue en mg/dL/min: G' = -p1(G-Gb) - (SI * multiplier)*I*G + Ra
        val expectedNaturalDelta = -p1 * (actualState.bg - bgTarget) - (actualState.estimatedSI * 0.0012 * actualState.iob * actualState.bg)
        
        // 🚀 LEAD COMPENSATOR (Phase 10 - Hardware-Awareness)
        // Le capteur Dexcom G6 possède un lag matériel (lissage natif) qui écrase et retarde la dérivée.
        // Si détecté, on booste l'accélération perçue pour réagir en temps réel comme le One+
        val isG6 = actualState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE
        val hardwareCompensatedVelocity = if (isG6 && actualState.bgVelocity > 0.5) {
            actualState.bgVelocity * 1.25 // +25% lead (Total lead with orchestrator = +50%)
        } else {
            actualState.bgVelocity // Transmission directe temps réel (One+ / G7 / Libre)
        }

        // 2. Erreur d'Innovation (L'écart avec la réalité : La vitesse BG réelle)
        // bgVelocity est en mg/dL/min. 
        val innovation = hardwareCompensatedVelocity - (expectedNaturalDelta + lastRa)
        
        // 🚀 DAWN GUARD DAMPENING (Prevent Cortisol Over-Correction)
        // Entre 5h et 9h, si peu d'activité physique (pas de marche), on suspecte le cortisol.
        // On augmente le scepticisme (rVariance) pour ne pas sauter sur chaque variation du capteur.
        val isDawnWindow = actualState.hour in 5..9
        val isLowActivity = actualState.steps < 200
        val isDawnGuardActive = isDawnWindow && isLowActivity && (actualState.cob < 0.1)

        // 3. Matrice de Bruit de Processus Q & Matrice de Mesure R
        val rVariance = if (isDawnGuardActive) 10.0 else 2.0 // Scepticisme CGM élevé au réveil
        
        // 🚀 DYNAMIC MANEUVER DETECTION (Phase 11 - Agile Tracking)
        val baseQ = when {
            innovation > 3.0 -> 5.0   // Repas lourd confirmé
            innovation > 1.0 -> 0.5 + (innovation - 1.0) * 0.75
            innovation < -1.0 -> 1.0  // Désamorçage
            else -> 0.2
        }
        
        // Sous Dawn Guard, on divise par 2.5 (x0.4) la vitesse à laquelle l'IA accepte que c'est un repas
        val qRa = if (isDawnGuardActive && innovation > 0) baseQ * 0.4 else baseQ 
        
        // 4. Prédiction de Covariance (P_k|k-1)
        pRa += qRa

        // 5. Calcul du Gain de Kalman (K) pour Ra
        val sRa = pRa + rVariance 
        val kRa = pRa / sRa 

        // 6. Mise à jour de l'état caché (Rate of Appearance)
        var estimatedRa = lastRa + (kRa * innovation)

        // 7. Règles physiologiques d'Écrêtage (Clipping)
        // On limite le maximum de Ra possible (Vitesse d'absorption) pendant le Dawn Guard
        val baseMaxRa = (actualState.patientWeightKg / 10.0) * 1.5
        val maxBiologicalRa = if (isDawnGuardActive) baseMaxRa * 0.5 else baseMaxRa // Cap Ra 50% plus bas
        estimatedRa = estimatedRa.coerceIn(0.0, maxBiologicalRa)

        // Désamorçage rapide (Decay)
        if (innovation < -0.5 && hardwareCompensatedVelocity <= 0.0) {
            estimatedRa *= 0.25 
        }

        // 8. Mise à jour de la Covariance (P_k|k)
        pRa = (1 - kRa) * pRa

        // Sauvegarde d'État Externe
        lastRa = estimatedRa

        aapsLogger.debug(
            LTag.APS,
            "👽 [PSE UKF] dBG_attendu=${expectedNaturalDelta.format(2)} | dBG_vrai=${actualState.bgVelocity.format(2)} (G6?=$isG6) | Innov=${innovation.format(2)} || 🍽️ Ra_estimé = ${estimatedRa.format(2)} mg/dL/min"
        )

        // Renvoie l'état enrichi avec le modèle de digestion fantôme calculé
        return actualState.copy(
            estimatedRa = estimatedRa
        )
    }

    fun getLastRa(): Double = lastRa

    /**
     * T9 — Compensation lag matériel G6 (Lead Compensator standalone)
     *
     * Expose la correction de délai Dexcom G6 (+25% sur la vélocité, +50% combiné avec l'orchestrateur)
     * dans un appel indépendant du pipeline Autodrive V3.
     * Utilisable depuis le chemin AIMI V2 pour que la compensation s'applique partout.
     *
     * @param velocity Vélocité BG brute du capteur (mg/dL/min)
     * @param isG6     Vrai si le capteur est un Dexcom G6 natif
     * @return Vélocité corrigée (ou identique si non-G6)
     */
    fun applyG6LeadCompensation(velocity: Double, isG6: Boolean): Double {
        return if (isG6 && velocity > 0.5) {
            val compensated = velocity * 1.25
            aapsLogger.debug(
                LTag.APS,
                "👽 [G6-Lead-V2] v_raw=${velocity.format(2)} → v_comp=${compensated.format(2)} (+25% lead corr)"
            )
            compensated
        } else {
            velocity
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
