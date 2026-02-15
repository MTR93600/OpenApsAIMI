package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * 🌀 AIMI Adaptive Trajectory - Vector Models
 *
 * Data structures for the Cosine Gate trajectory modulation system.
 * Defines kernels, modulation parameters, and input/output structures.
 */

enum class KernelType {
    REST,
    STRESS,
    ACTIVITY,
    SLEEP,
    POSTMEAL
}

/**
 * Reference kernel for physiological states.
 * Defines the target vector and the associated modulation parameters.
 *
 * @param name Type of the kernel (REST, STRESS, etc.)
 * @param referenceVector The ideal vector state for this kernel [Delta, Activity, Stress]
 * @param baseSensitivityMultiplier Multiplier for ISF/Sensitivity (1.0 = neutral)
 * @param basePeakShiftMinutes Shift in peak time in minutes (+ delayed, - accelerated)
 */
data class TrajectoryKernelRef(
    val name: KernelType,
    val referenceVector: DoubleArray,
    val baseSensitivityMultiplier: Double,
    val basePeakShiftMinutes: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrajectoryKernelRef
        if (name != other.name) return false
        if (!referenceVector.contentEquals(other.referenceVector)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + referenceVector.contentHashCode()
        return result
    }
}

/**
 * Result of the Cosine Gate calculation.
 * Contains the final modulation parameters to be applied to the trajectory.
 */
data class TrajectoryModulation(
    val effectiveSensitivityMultiplier: Double,
    val peakTimeShiftMinutes: Int,
    val weights: Map<KernelType, Double>,
    val dominantKernel: KernelType,
    val dataQuality: Double,
    val debug: String
) {
    companion object {
        val NEUTRAL = TrajectoryModulation(
            effectiveSensitivityMultiplier = 1.0,
            peakTimeShiftMinutes = 0,
            weights = mapOf(KernelType.REST to 1.0),
            dominantKernel = KernelType.REST,
            dataQuality = 1.0,
            debug = "NEUTRAL (Default)"
        )
    }
}

/**
 * Input data for the Cosine Trajectory Gate.
 * Aggregates all necessary physiological and metabolic context.
 */
data class GateInput(
    val bgCurrent: Double,
    val bgDelta: Double,
    val iob: Double,
    val cob: Double,
    val stepCount15m: Int,
    val hrCurrent: Int,
    val hrvCurrent: Double,
    val sleepState: Boolean, // True if sleep detected
    val physioState: PhysioStateMTR, // From PhysioEngine
    val dataQuality: Double, // 0.0 - 1.0
    val timestamp: Long = System.currentTimeMillis()
)
