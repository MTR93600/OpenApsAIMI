package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

/**
 * Models for the Basedow (Hyperthyroidism) integration physiological module.
 * Clinically models states of increased insulin clearance, increased EGP,
 * and faster carb absorption characteristics of hyperthyroidism safely.
 */

enum class ThyroidStatus {
    EUTHYROID, // Normal, base state
    HYPER_MILD, 
    HYPER_MODERATE,
    HYPER_SEVERE,
    NORMALIZING, // Critical risk state during treatment when sensitivity rapidly returns
    UNKNOWN // Not enough data to classify
}

enum class ThyroidTreatmentPhase {
    NONE, // No active treatment / unmanaged
    TITRATION, // Initial treatment phase (rapid normalizing risk)
    STABLE,
    DE_ESCALATION // Slowly withdrawing treatment
}

enum class ThyroidEstimationMode {
    MANUAL, // Explicit toggles
    AUTO // Future: lab + physio inference
}

enum class NormalizingGuardLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ThyroidInputs(
    val timestampMs: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = false,
    val userMode: ThyroidEstimationMode = ThyroidEstimationMode.MANUAL,
    val manualStatus: ThyroidStatus = ThyroidStatus.EUTHYROID,
    val treatmentPhase: ThyroidTreatmentPhase = ThyroidTreatmentPhase.NONE,
    val guardLevel: NormalizingGuardLevel = NormalizingGuardLevel.HIGH,
    // Future expansion for Physio/Labs
    val restingHr: Double? = null,
    val tshLab: Double? = null
)

data class ThyroidEffects(
    val status: ThyroidStatus = ThyroidStatus.EUTHYROID,
    val diaMultiplier: Double = 1.0,      // e.g. 0.8 to represent faster clearance (shorter action)
    val egpMultiplier: Double = 1.0,      // e.g. 1.2 to represent higher glucose output
    val carbRateMultiplier: Double = 1.0, // e.g. 1.15 to represent faster absorption
    val isfMultiplier: Double = 1.0,      // e.g. 0.9 to represent slightly more "resistance" overall
    val blockSmb: Boolean = false,        // Safety gate primarily during Normalizing
    val basalCapMultiplier: Double = 1.5, // Safety max cap on basal increments
    val smbCapUnits: Double? = null       // Strict max for SMB delivery
)
