package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.min

/**
 * Composite chaos score from inter-scale tension, active paradoxes, uncertain geometry, and cap flapping.
 * Drives authority downgrade and episodic memory (branch F complement).
 */
object RbtChaosEvaluator {

    private const val ACTIVE_THRESHOLD = 0.72
    private const val CAUTION_THRESHOLD = 0.55

    data class Input(
        val snapshot: RecursiveBeliefSnapshot,
        val trajectoryUncertain: Boolean,
        val patternCapFlapping: Boolean,
        val unsuppressedParadoxCount: Int? = null,
    )

    data class Result(
        val score: Double,
        val active: Boolean,
        val caution: Boolean,
        val reasonCodes: List<String>,
    ) {
        fun summary(): String =
            "score=${aimiFmt2(score)} active=$active reasons=${reasonCodes.joinToString(",")}"
    }

    fun evaluate(input: Input): Result {
        val reasons = linkedSetOf<String>()
        val maxTension = input.snapshot.tensions.maxOfOrNull { it.magnitude } ?: 0.0
        val tensionNorm = (maxTension / 2.5).coerceIn(0.0, 1.0)
        if (tensionNorm >= 0.45) reasons += "TENSION"

        val paradoxCount = input.unsuppressedParadoxCount
            ?: input.snapshot.paradoxes.count { !it.suppressed }
        val paradoxNorm = min(1.0, paradoxCount / 4.0)
        if (paradoxCount >= 2) reasons += "PARADOX"

        val uncertainNorm = if (input.trajectoryUncertain) {
            reasons += "TRAJ_UNCERTAIN"
            0.85
        } else {
            0.0
        }

        val flapNorm = if (input.patternCapFlapping) {
            reasons += "CAP_FLAP"
            0.70
        } else {
            0.0
        }

        val scaleSpread = input.snapshot.scales.map { it.urgency }
        val spreadNorm = if (scaleSpread.size >= 2) {
            val spread = (scaleSpread.maxOrNull()!! - scaleSpread.minOrNull()!!).coerceAtLeast(0.0)
            val norm = (spread / 3.0).coerceIn(0.0, 1.0)
            if (norm >= 0.55) reasons += "SCALE_SPREAD"
            norm
        } else {
            0.0
        }

        val score = (
            tensionNorm * 0.35 +
                paradoxNorm * 0.25 +
                uncertainNorm * 0.20 +
                flapNorm * 0.10 +
                spreadNorm * 0.10
            ).coerceIn(0.0, 1.0)

        return Result(
            score = score,
            active = score >= ACTIVE_THRESHOLD,
            caution = score >= CAUTION_THRESHOLD,
            reasonCodes = reasons.toList(),
        )
    }
}
