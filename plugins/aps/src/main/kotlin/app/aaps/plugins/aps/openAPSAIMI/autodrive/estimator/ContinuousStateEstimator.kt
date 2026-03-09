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
        
        // Dynamique naturelle attendue en mg/dL/min
        val expectedNaturalDelta = - (p1 + (actualState.estimatedSI * actualState.iob)) * (actualState.bg - bgTarget)
        
        // 🚀 LEAD COMPENSATOR (Phase 10 - Hardware-Awareness)
        // Le capteur Dexcom G6 possède un lag matériel (lissage natif) qui écrase et retarde la dérivée.
        // Si détecté, on booste l'accélération perçue pour réagir en temps réel comme le One+
        val isG6 = actualState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE
        val hardwareCompensatedVelocity = if (isG6 && actualState.bgVelocity > 0.5) {
            actualState.bgVelocity * 1.5 // +50% de projection du signal dans le futur
        } else {
            actualState.bgVelocity // Transmission directe temps réel (One+ / G7 / Libre)
        }

        // 2. Erreur d'Innovation (L'écart avec la réalité : La vitesse BG réelle)
        // bgVelocity est en mg/dL/min. 
        val innovation = hardwareCompensatedVelocity - (expectedNaturalDelta + lastRa)
        
        // 3. Matrice de Bruit de Processus Q & Matrice de Mesure R
        val rVariance = 2.0 // Bruit de la mesure du capteur CGM (Incertitude Dexcom/Libre)
        
        // 🚀 DYNAMIC MANEUVER DETECTION (Phase 11 - Agile Tracking)
        // On remplace le switch binaire (0.5 ou 5.0) par un gain proportionnel à l'innovation.
        // Cela permet de ne pas sur-réagir aux petits grignotages (pomme).
        val qRa = when {
            innovation > 3.0 -> 5.0   // Repas lourd confirmé (McDo)
            innovation > 1.0 -> 0.5 + (innovation - 1.0) * 0.75 // Transition linéaire vers l'agressivité
            innovation < -1.0 -> 1.0  // Aide au désamorçage rapide
            else -> 0.2               // Tracking ultra-doux en croisière (stabilité basale)
        }
        
        // 4. Prédiction de Covariance (P_k|k-1)
        pRa += qRa

        // 5. Calcul du Gain de Kalman (K) pour Ra (H = 1 car Ra impacte directement dBG)
        val sRa = pRa + rVariance // Variance de l'innovation
        val kRa = pRa / sRa 

        // 6. Mise à jour de l'état caché (Rate of Appearance)
        // Si l'innovation est fortement positive (On monte + vite que prévu), Ra explose.
        var estimatedRa = lastRa + (kRa * innovation)

        // 7. Règles physiologiques d'Écrêtage (Clipping)
        // Ra ne peut pas être inférieur à 0 (On ne peut pas absorber "négativement" un repas)
        // L'excès de chute inexpliquée sera géré par la sensibilité dynamique d'AIMI.
        // [PHASE 7] Limite Supérieure : Un intestin ne peut pas absorber plus de glucides que le Volume Sanguin (Vd) ne peut en encaisser.
        // Cap théorique agressif : ~1.5 mg/dL/min par tranche de 10 kg de poids corporel.
        val maxBiologicalRa = (actualState.patientWeightKg / 10.0) * 1.5
        estimatedRa = estimatedRa.coerceIn(0.0, maxBiologicalRa)

        // Désamorçage rapide (Decay) si on a fini de manger
        // Si la glycémie chute vite ou est stable et que l'innovation est négative, on tue le Ra fantôme.
        if (innovation < -0.5 && hardwareCompensatedVelocity <= 0.0) {
            estimatedRa *= 0.25 // Écrase le repas fantôme 2x plus vite pour éviter le crash en fin de pic
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

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
