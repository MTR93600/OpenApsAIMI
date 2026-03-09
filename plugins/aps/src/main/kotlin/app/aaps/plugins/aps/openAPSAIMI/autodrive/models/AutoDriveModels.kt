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
    val estimatedSI: Double = 1.0,  // Sensibilité à l'insuline estimée à l'instant T (par défaut 1.0 = profile)
    val estimatedRa: Double = 0.0,  // Taux d'apparition des glucides estimé (Glucose Rate of Appearance)
    val patientWeightKg: Double = 70.0, // Poids du patient (Volume de Distribution Vd) - Phase 7
    val physiologicalStressMask: DoubleArray, // Vecteur optionnel d'attention (pour plus tard)
    val isNight: Boolean = false,      // Mode Nuit (Sommeil = réduction drastique de l'agressivité)
    val sourceSensor: app.aaps.core.data.model.SourceSensor? = null, // Type de capteur matériel (Phase 10)
    val maxIOB: Double = 3.0,          // Limite MaxIOB de sécurité (Phase 4+)
    val highBgMaxSMB: Double = 2.0     // Plafond SMB utilisateur pour BG élevé (Phase 11+)
)

/**
 * La commande brute calculée par le MPC de l'Autodrive, AVANT le passage dans le bouclier de sécurité.
 */
data class AutoDriveCommand(
    val scheduledMicroBolus: Double,
    val temporaryBasalRate: Double,
    val isSafe: Boolean = true,
    val reason: String = "Autodrive Init"
)
