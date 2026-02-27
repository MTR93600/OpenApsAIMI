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
     * des variables physiologiques cachées (Ra, SI).
     */
    fun updateAndPredict(actualState: AutoDriveState): AutoDriveState {
        
        // 1. Modèle Interne Prédictif (Ce qu'on PENSE qui aurait dû se passer)
        // Bergman simplifié : dBG/dt = - (p1 + SI * IOB) * BG + p1 * Gb + Ra
        // Hypothèse de base (T=5min)
        val p1 = 0.01 // Efficacité du glucose propre (basale)
        val gb = 100.0 // Glucose basal théorique
        
        val predictedDelta = - (p1 + lastSi * actualState.iob) * actualState.bg + p1 * gb + lastRa
        
        // 2. Erreur d'Innovation (L'écart avec la réalité)
        // bgVelocity (mg/dL/min). Le delta attendu est par minute.
        val innovation = actualState.bgVelocity - predictedDelta
        
        // 3. Matrice Jacobienne (Gradients)
        // Comment le dBG dépend de SI et de Ra ?
        val hSi = - actualState.iob * actualState.bg // L'impact de SI est énorme si gros IOB et gros BG
        val hRa = 1.0 // L'impact de Ra est direct (+1)

        // 4. Bruit (Tuning Autodrive)
        val rVariance = 2.0 // Bruit de la mesure CGM
        val qSi = 0.001     // Bruit de process SI (on veut que ça varie lentement)
        val qRa = 0.5       // Bruit de process Ra (peut exploser d'un coup avec un repas)

        // Étape de Prédiction (Incertitude augmente)
        pSi += qSi
        pRa += qRa

        // 5. Gain de Kalman
        // S = H * P * H^t + R (Incertitude totale projetée sur la mesure)
        val s = (hSi * pSi * hSi) + (hRa * pRa * hRa) + rVariance
        val kSi = pSi * hSi / s
        val kRa = pRa * hRa / s

        // 6. Mise à jour de l'état
        var newSi = lastSi + kSi * innovation
        var newRa = lastRa + kRa * innovation

        // 7. Contraintes Physiologiques (Clipping)
        newSi = newSi.coerceIn(0.2, 5.0) // On ne peut pas avoir une sensibilité négative ou infinie
        newRa = max(0.0, newRa)          // On ne vomit pas le repas (Ra >= 0)

        // 8. Mise à jour des covariances
        pSi = (1 - kSi * hSi) * pSi
        pRa = (1 - kRa * hRa) * pRa

        // Sauvegarde pour le prochain cycle
        lastSi = newSi
        lastRa = newRa

        // MTR Autodrive Logger
        aapsLogger.debug(
            LTag.APS,
            "👽 [PSE] Innovation: ${innovation.format(2)} | SI_est=${newSi.format(2)} | Ra_est=${newRa.format(2)}"
        )

        // Renvoie l'état enrichi avec la vérité estimée
        return actualState.copy(
            estimatedSI = newSi,
            estimatedRa = newRa
        )
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
