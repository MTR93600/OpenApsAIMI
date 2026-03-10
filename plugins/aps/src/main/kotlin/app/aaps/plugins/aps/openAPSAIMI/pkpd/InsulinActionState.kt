package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Stage de l'activité insulinique
 */
enum class ActivityStage {
    RISING,    // Onset → Peak (avant pic)
    PEAK,      // Near peak (±15min du pic)
    FALLING,   // Post-peak, décroissance active
    TAIL       // Queue longue, activité résiduelle faible
}

/**
 * État de l'action insulinique en temps réel
 */
data class InsulinActionState(
    val onsetConfirmed: Boolean,
    val onsetConfidenceScore: Double,
    val timeSinceOnsetMin: Double,
    val activityNow: Double,
    val activityStage: ActivityStage,
    val timeToPeakMin: Int,
    val timeToEndMin: Int,
    val residualEffect: Double,
    val effectiveIob: Double,
    val reason: String
) {
    companion object {
        fun default() = InsulinActionState(
            onsetConfirmed = false,
            onsetConfidenceScore = 0.0,
            timeSinceOnsetMin = 0.0,
            activityNow = 0.0,
            activityStage = ActivityStage.TAIL,
            timeToPeakMin = 0,
            timeToEndMin = 0,
            residualEffect = 0.0,
            effectiveIob = 0.0,
            reason = "No active insulin"
        )
    }
}

/**
 * Throttle SMB vs TBR
 */
data class SmbTbrThrottle(
    val smbFactor: Double,
    val intervalAddMin: Int,
    val preferTbr: Boolean,
    val reason: String
) {
    companion object {
        fun normal() = SmbTbrThrottle(
            smbFactor = 1.0,
            intervalAddMin = 0,
            preferTbr = false,
            reason = "Normal operation"
        )
    }
}
