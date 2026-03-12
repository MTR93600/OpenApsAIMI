package app.aaps.plugins.aps.openAPSAIMI.autodrive.models

/**
 * Représente l'état mathématique continu du patient à un instant T (généralement toutes les 5 min).
 * Contrairement à BgSnapshot, cet objet est agnostique des règles d'APS (pas de "meal", etc.).
 */
data class AutoDriveState(
    val bg: Double,
    val bgVelocity: Double,         // mg/dL/min
    val iob: Double,                // Unités actives
    val cob: Double = 0.0,          // Graphes optionnels si annoncés
    val estimatedSI: Double = 1.0,  // Sensibilité à l'insuline estimée à l'instant T
    val estimatedRa: Double = 0.0,  // Taux d'apparition des glucides estimé
    val patientWeightKg: Double = 70.0, // Poids du patient (Volume de Distribution Vd) - Phase 7
    val physiologicalStressMask: DoubleArray, // Vecteur optionnel d'attention (pour plus tard)
    val isNight: Boolean = false,      // Mode Nuit (Sommeil = réduction drastique de l'agressivité)
    val hour: Int = 12,                // Heure locale (0-23) pour Dawn Guard
    val steps: Int = 0,                // Pas cumulés sur 15 min
    val hr: Int = 70,                  // Rythme cardiaque actuel
    val rhr: Int = 60,                 // Rythme cardiaque au repos
    val sourceSensor: app.aaps.core.data.model.SourceSensor? = null, // Type de capteur matériel (Phase 10)
    val maxIOB: Double = 3.0,          // Limite MaxIOB de sécurité (Phase 4+)
    val maxSMB: Double = 1.0,          // Plafond SMB utilisateur standard (Phase 11+)
    val highBgMaxSMB: Double = 2.0     // Plafond SMB utilisateur pour BG élevé (Phase 11+)
) {
    init {
        require(bg in 30.0..600.0) { "BG out of safe bounds: $bg" }
        require(bgVelocity in -10.0..10.0) { "BG Velocity out of bounds: $bgVelocity" }
        require(iob >= 0.0) { "IOB cannot be negative: $iob" }
        require(cob >= 0.0) { "COB cannot be negative: $cob" }
        require(estimatedSI > 0.0) { "Estimated SI must be positive: $estimatedSI" }
        require(estimatedRa >= 0.0) { "Estimated Ra cannot be negative: $estimatedRa" }
        require(patientWeightKg in 40.0..250.0) { "Patient weight out of physiological bounds: $patientWeightKg" }
        require(hour in 0..23) { "Invalid hour: $hour" }
    }

    companion object {
        @JvmStatic
        fun createSafe(
            bg: Double,
            bgVelocity: Double,
            iob: Double,
            cob: Double = 0.0,
            estimatedSI: Double = 1.0,
            estimatedRa: Double = 0.0,
            patientWeightKg: Double = 70.0,
            physiologicalStressMask: DoubleArray = DoubleArray(0),
            isNight: Boolean = false,
            hour: Int = 12,
            steps: Int = 0,
            hr: Int = 70,
            rhr: Int = 60,
            sourceSensor: app.aaps.core.data.model.SourceSensor? = null,
            maxIOB: Double = 3.0,
            maxSMB: Double = 1.0,
            highBgMaxSMB: Double = 2.0
        ): AutoDriveState {
            return try {
                AutoDriveState(
                    bg = bg.coerceIn(30.0, 600.0),
                    bgVelocity = bgVelocity.coerceIn(-10.0, 10.0),
                    iob = iob.coerceAtLeast(0.0),
                    cob = cob.coerceAtLeast(0.0),
                    estimatedSI = estimatedSI.coerceAtLeast(0.1),
                    estimatedRa = estimatedRa.coerceAtLeast(0.0),
                    patientWeightKg = patientWeightKg.coerceIn(40.0, 250.0),
                    physiologicalStressMask = physiologicalStressMask,
                    isNight = isNight,
                    hour = hour.coerceIn(0, 23),
                    steps = steps.coerceAtLeast(0),
                    hr = hr.coerceAtLeast(0),
                    rhr = rhr.coerceAtLeast(0),
                    sourceSensor = sourceSensor,
                    maxIOB = maxIOB.coerceAtLeast(0.0),
                    maxSMB = maxSMB.coerceAtLeast(0.0),
                    highBgMaxSMB = highBgMaxSMB.coerceAtLeast(0.0)
                )
            } catch (e: Exception) {
                // Fallback to minimal safe state if even coercion fails abruptly (unlikely but safe)
                AutoDriveState(
                    bg = 100.0,
                    bgVelocity = 0.0,
                    iob = 0.0,
                    physiologicalStressMask = DoubleArray(0)
                )
            }
        }
    }
}

/**
 * La commande brute calculée par le MPC de l'Autodrive, AVANT le passage dans le bouclier de sécurité.
 */
data class AutoDriveCommand(
    val scheduledMicroBolus: Double,
    val temporaryBasalRate: Double,
    val isSafe: Boolean = true,
    val reason: String = "Autodrive Init"
)
