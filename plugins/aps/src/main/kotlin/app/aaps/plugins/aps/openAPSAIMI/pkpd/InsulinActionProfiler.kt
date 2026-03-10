package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlin.math.pow// 1. Définir la structure de données pour notre nouvelle métrique
data class IobActionProfile(
    val iobTotal: Double,
    val peakMinutes: Double,      // Temps pondéré jusqu'au pic. Négatif si le pic est passé.
    val activityNow: Double,      // Activité relative actuelle (0..1)
    val activityIn30Min: Double   // Activité relative projetée dans 30 min (0..1)
)

object InsulinActionProfiler {

    // Modèle simple de courbe d'action de l'insuline (peut être affiné)
    // Retourne une activité relative (0 à 1) pour un temps donné après l'injection.
    private fun getInsulinActivity(minutesSinceBolus: Double, peakTime: Double): Double {
        if (minutesSinceBolus < 0 || peakTime <= 0) return 0.0
        // Utilise une fonction de Weibull, courante pour modéliser la PK/PD
        val shape = 3.5
        val scale = peakTime / ((shape - 1) / shape).pow(1 / shape)
        val activity = (shape / scale) * (minutesSinceBolus / scale).pow(shape - 1) *
            kotlin.math.exp(-(minutesSinceBolus / scale).pow(shape))
        return activity
    }

    fun calculate(iobArray: Array<IobTotal>, profile: OapsProfileAimi, snsDominance: Double = 0.3): IobActionProfile {
        // 🧬 PHYSIO-MODULATION: Stress (SNS=0.8) -> +20% Peak; Optimal (SNS=0.2) -> -4% Peak
        val peakModulation = 1.0 + (snsDominance - 0.3) * 0.4
        val insulinPeakTime = (profile.peakTime.toDouble() * peakModulation).coerceAtLeast(30.0) // ex: 75.0 pour Novorapid
        if (iobArray.isEmpty() || insulinPeakTime <= 0) {
            return IobActionProfile(0.0, 0.0, 0.0, 0.0)
        }

        var totalIob = 0.0
        var weightedPeakMinutes = 0.0
        var weightedActivityNow = 0.0
        var weightedActivityIn30Min = 0.0

        val now = System.currentTimeMillis()

        // Normalisation pour que le pic d'activité soit égal à 1
        val maxActivity = getInsulinActivity(insulinPeakTime, insulinPeakTime)
        if (maxActivity == 0.0) return IobActionProfile(0.0, 0.0, 0.0, 0.0)


        for (iobEntry in iobArray) {
            val iobValue = iobEntry.iob // Quantité d'IOB de cette entrée
            if (iobValue <= 0) continue

            totalIob += iobValue
            val minutesSinceBolus = (now - iobEntry.time) / (1000.0 * 60.0)

            // Calcul du temps restant jusqu'au pic pour ce bolus
            val timeToPeak = insulinPeakTime - minutesSinceBolus
            weightedPeakMinutes += timeToPeak * iobValue

            // Calcul de l'activité actuelle et future pour ce bolus
            val activityNow = getInsulinActivity(minutesSinceBolus, insulinPeakTime) / maxActivity
            val activityIn30Min = getInsulinActivity(minutesSinceBolus + 30, insulinPeakTime) / maxActivity

            weightedActivityNow += activityNow * iobValue
            weightedActivityIn30Min += activityIn30Min * iobValue
        }

        if (totalIob == 0.0) {
            return IobActionProfile(0.0, 0.0, 0.0, 0.0)
        }

        return IobActionProfile(
            iobTotal = totalIob,
            peakMinutes = weightedPeakMinutes / totalIob,
            activityNow = weightedActivityNow / totalIob,
            activityIn30Min = weightedActivityIn30Min / totalIob
        )
    }
}