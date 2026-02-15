package app.aaps.plugins.aps.openAPSAIMI.physio.gate

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSAIMI.physio.GateInput
import app.aaps.plugins.aps.openAPSAIMI.physio.KernelType
import app.aaps.plugins.aps.openAPSAIMI.physio.TrajectoryKernelRef
import app.aaps.plugins.aps.openAPSAIMI.physio.TrajectoryModulation
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 🌀 Cosine Trajectory Gate
 *
 * Computes trajectory modulation (Sensitivity & Peak Shift) by comparing
 * current state vector against reference kernels using Cosine Similarity.
 *
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class CosineTrajectoryGate @Inject constructor(
    private val prefs: Preferences,
    private val aapsLogger: AAPSLogger
) {

    companion object {
        private const val TAG = "CosineGate"

        // Reference Kernels
        // Vector dimensions: [Delta, Activity, Stress]
        // 1. Delta: Normalized BG Trend (-1..1)
        // 2. Activity: Normalized Steps (-1..1)
        // 3. Stress: Normalized Stress Load (-1..1)

        private val KERNEL_REST = TrajectoryKernelRef(
            KernelType.REST,
            doubleArrayOf(0.0, 0.0, 0.0), // Baseline
            baseSensitivityMultiplier = 1.0,
            basePeakShiftMinutes = 0
        )

        private val KERNEL_ACTIVITY = TrajectoryKernelRef(
            KernelType.ACTIVITY,
            doubleArrayOf(-0.5, 1.0, -0.2), // Falling BG, High Activity, Low Stress
            baseSensitivityMultiplier = 1.3, // Weaker insulin action (higher factor) to prevent hypo
            basePeakShiftMinutes = 0
        )

        private val KERNEL_STRESS = TrajectoryKernelRef(
            KernelType.STRESS,
            doubleArrayOf(0.3, 0.0, 1.0), // Rising BG, No Activity, High Stress
            baseSensitivityMultiplier = 0.8, // Stronger insulin action (lower factor)
            basePeakShiftMinutes = 10 // Delayed peak (slower absorption)
        )
        
        // Sleep Kernel: Low activity, negative stress (Deep relaxation)
        private val KERNEL_SLEEP = TrajectoryKernelRef(
             KernelType.SLEEP,
             doubleArrayOf(0.0, -0.2, -0.8), // Low Activity, Very Low Stress
             baseSensitivityMultiplier = 1.1, // Mildly conservative
             basePeakShiftMinutes = 0
        )

        private val ALL_KERNELS = listOf(KERNEL_REST, KERNEL_ACTIVITY, KERNEL_STRESS, KERNEL_SLEEP)
    }

    /**
     * Main computation function
     */
    fun compute(input: GateInput): TrajectoryModulation {
        
        // 1. Gate Switch (Feature Flag)
        // Note: Key will be added to BooleanKey enum
        if (!prefs.get(BooleanKey.AimiCosineGateEnabled)) {
             return TrajectoryModulation.NEUTRAL.copy(debug = "Disabled")
        }

        // 2. Data Quality Check
        val minQuality = prefs.get(DoubleKey.AimiCosineGateMinDataQuality)
        if (input.dataQuality < minQuality) {
            return TrajectoryModulation.NEUTRAL.copy(
                dataQuality = input.dataQuality,
                debug = "Low Quality ${"%.2f".format(input.dataQuality)} < $minQuality"
            )
        }

        // 3. Build State Vector
        val vector = buildStateVector(input)

        // 4. Calculate Weights
        val alpha = prefs.get(DoubleKey.AimiCosineGateAlpha)
        val weights = calculateWeights(vector, ALL_KERNELS, alpha)

        // 5. Interpolate Parameters
        var effectiveSensMult = 0.0
        var weightedShift = 0.0
        var dominantKernel = KernelType.REST
        var maxWeight = -1.0

        ALL_KERNELS.forEach { kernel ->
            val w = weights[kernel.name] ?: 0.0
            effectiveSensMult += kernel.baseSensitivityMultiplier * w
            weightedShift += kernel.basePeakShiftMinutes * w

            if (w > maxWeight) {
                maxWeight = w
                dominantKernel = kernel.name
            }
        }

        // 6. Clamp Results
        val minSens = prefs.get(DoubleKey.AimiCosineGateMinSensitivity)
        val maxSens = prefs.get(DoubleKey.AimiCosineGateMaxSensitivity)
        val maxShift = prefs.get(IntKey.AimiCosineGateMaxPeakShift)

        val clampedSens = effectiveSensMult.coerceIn(minSens, maxSens)
        val clampedShift = weightedShift.toInt().coerceIn(-maxShift, maxShift)

        // 7. Debug String
        val debugStr = buildString {
            append("DQ=${"%.2f".format(input.dataQuality)} ")
            append("Vec=[${vector.joinToString(",") { "%.1f".format(it) }}] ")
            append("W=[${weights.entries.joinToString(" ") { 
                "${it.key.name.take(1)}:${"%.2f".format(it.value)}" 
            }}] ")
            append("-> M=${"%.2f".format(clampedSens)} S=${clampedShift}m")
        }
        
        // Log changes if significant
        if (kotlin.math.abs(clampedSens - 1.0) > 0.05 || kotlin.math.abs(clampedShift) > 5) {
             // Only log high impact changes to avoid spam, or rely on caller to log
        }

        return TrajectoryModulation(
            effectiveSensitivityMultiplier = clampedSens,
            peakTimeShiftMinutes = clampedShift,
            weights = weights,
            dominantKernel = dominantKernel,
            dataQuality = input.dataQuality,
            debug = debugStr
        )
    }

    private fun buildStateVector(input: GateInput): DoubleArray {
        // [Delta, Activity, Stress]
        
        // 1. Delta (BG Trend): -10..10 -> -1..1
        val normDelta = (input.bgDelta / 10.0).coerceIn(-1.0, 1.0)

        // 2. Activity: 15m steps. 0..1500 -> 0..1
        val normActivity = (input.stepCount15m / 1500.0).coerceIn(0.0, 1.0)

        // 3. Stress: Using PhysioState and HR
        // -1.0 (Sleep/Relax) .. 0.0 (Normal) .. 1.0 (Stress)
        var normStress = 0.0
        
        val stateName = input.physioState.name
        if (stateName == "STRESS_DETECTED" || stateName == "INFECTION_RISK") {
            normStress = 1.0
        } else if (input.sleepState) {
            normStress = -0.5 // Deep sleep / relaxation
            if (stateName == "RECOVERY_NEEDED") normStress = -0.2 // Poor sleep is less relaxing
        } else if (input.hrCurrent > 90 && input.hrCurrent < 150) {
            // Mild stress / arousal if not classified explicitly
             if (normActivity < 0.3) normStress = 0.5 // High HR but low activity = Stress
             else normStress = -0.2 // High HR + High Activity = Exercise (Good stress, handled by Activity dim)
        }

        return doubleArrayOf(normDelta, normActivity, normStress)
    }

    private fun calculateWeights(
        inputVec: DoubleArray,
        kernels: List<TrajectoryKernelRef>,
        alpha: Double
    ): Map<KernelType, Double> {
        // 1. Calculate Cosine Similarity
        val similarities = kernels.associate { kernel ->
            kernel.name to cosineSimilarity(inputVec, kernel.referenceVector)
        }

        // 2. Softmax
        // shift by max to prevent overflow
        val maxSim = similarities.values.maxOrNull() ?: 0.0
        val maxScore = maxSim * alpha
        
        val exps = similarities.mapValues { (_, sim) ->
            exp((sim * alpha) - maxScore)
        }
        
        val sumExps = exps.values.sum()
        
        return if (sumExps == 0.0) {
            kernels.associate { it.name to (if (it.name == KernelType.REST) 1.0 else 0.0) }
        } else {
             // Use EnumMap for efficiency if possible, or simple Map
             val result = EnumMap<KernelType, Double>(KernelType::class.java)
             exps.forEach { (k, v) -> result[k] = v / sumExps }
             result
        }
    }

    private fun cosineSimilarity(v1: DoubleArray, v2: DoubleArray): Double {
        if (v1.size != v2.size) return 0.0
        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }

        if (norm1 == 0.0 || norm2 == 0.0) {
            // If one vector is zero, similarity is undefined/0
            // But if BOTH are zero? (REST kernel is 0,0,0) -> 1.0 match?
            // Cosine similarity is undefined for zero vector.
            // Distance based?
            // For this implementation: if input is zero (baseline) and kernel is zero (REST), match!
            if (norm1 == 0.0 && norm2 == 0.0) return 1.0
            return 0.0 
        }

        return dot / (sqrt(norm1) * sqrt(norm2))
    }
}
