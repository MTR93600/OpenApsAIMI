package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAutoISF.GlucoseStatusCalculatorAutoIsf
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.abs
import kotlin.math.max

/**
 * Optional bounded adjustment of DynISF using **short-horizon CGM geometry** (same inputs family as
 * AutoISF: [GlucoseStatusCalculatorAutoIsf]), without running the AutoISF APS or reading its decisions.
 *
 * Product rules: strict gating, max change per tick, no stack when physio already tightens ISF,
 * shadow mode for safe rollout.
 */
data class DynIsfTrajectoryTuningOutcome(
    val isfMgdlPerU: Double,
    val appliedMultiplierToBlended: Boolean,
    val shadowOnly: Boolean,
    val trajectoryMultiplier: Double,
    val riseScore: Double,
    val fallScore: Double,
)

@SingleIn(AppScope::class)
class DynIsfTrajectoryTuning @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val glucoseStatusCalculatorAutoIsf: GlucoseStatusCalculatorAutoIsf,
) {

    fun computeAdjustedIsf(
        blendedMgdlPerU: Double,
        profileIsfMgdlPerU: Double,
        bgMgdl: Double,
        bgQualityState: BgQualityCheck.State,
        physioIsfFactor: Double,
    ): DynIsfTrajectoryTuningOutcome {
        if (!preferences.get(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled)) {
            return DynIsfTrajectoryTuningOutcome(
                isfMgdlPerU = blendedMgdlPerU,
                appliedMultiplierToBlended = false,
                shadowOnly = false,
                trajectoryMultiplier = 1.0,
                riseScore = 0.0,
                fallScore = 0.0,
            )
        }
        val shadowOnly = preferences.get(BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly)
        val maxFraction = preferences.get(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction)

        if (bgQualityState == BgQualityCheck.State.FLAT) {
            return noop(blendedMgdlPerU, shadowOnly, "flat_bg_state")
        }
        if (physioIsfFactor < PHYSIO_ISF_STACK_SKIP_BELOW) {
            return noop(blendedMgdlPerU, shadowOnly, "physio_isf_already_tight factor=$physioIsfFactor")
        }
        if (bgMgdl !in BG_GATE_MIN..BG_GATE_MAX) {
            return noop(blendedMgdlPerU, shadowOnly, "bg_out_of_gate bg=$bgMgdl")
        }

        val gs = glucoseStatusCalculatorAutoIsf.getGlucoseStatusData(allowOldData = false)
            ?: return noop(blendedMgdlPerU, shadowOnly, "no_autoisf_glucose_status")

        val (riseScore, fallScore) = trajectoryRiseFallScores(gs)
        if (riseScore < ACTIVATION_THRESHOLD && fallScore < ACTIVATION_THRESHOLD) {
            return DynIsfTrajectoryTuningOutcome(
                isfMgdlPerU = blendedMgdlPerU,
                appliedMultiplierToBlended = false,
                shadowOnly = shadowOnly,
                trajectoryMultiplier = 1.0,
                riseScore = riseScore,
                fallScore = fallScore,
            )
        }

        val mult = when {
            riseScore >= fallScore && riseScore >= ACTIVATION_THRESHOLD ->
                1.0 - maxFraction * riseScore
            fallScore >= ACTIVATION_THRESHOLD ->
                1.0 + maxFraction * fallScore
            else -> 1.0
        }

        val adjustedRaw = blendedMgdlPerU * mult
        val profileLo = profileIsfMgdlPerU * PROFILE_REL_LOW
        val profileHi = profileIsfMgdlPerU * PROFILE_REL_HIGH
        val adjusted = adjustedRaw.coerceIn(profileLo, profileHi)

        val logDetail =
            "DYNISF_TRAJ rise=${"%.2f".format(riseScore)} fall=${"%.2f".format(fallScore)} " +
                "mult=${"%.4f".format(mult)} blended=${"%.1f".format(blendedMgdlPerU)} -> " +
                "${"%.1f".format(adjusted)} shadow=$shadowOnly corr=${"%.2f".format(gs.corrSqu)}"

        if (shadowOnly) {
            aapsLogger.debug(LTag.APS, "DYNISF_TRAJECTORY_SHADOW $logDetail")
            return DynIsfTrajectoryTuningOutcome(
                isfMgdlPerU = blendedMgdlPerU,
                appliedMultiplierToBlended = false,
                shadowOnly = true,
                trajectoryMultiplier = mult,
                riseScore = riseScore,
                fallScore = fallScore,
            )
        }

        if (abs(adjusted - blendedMgdlPerU) < 1e-6) {
            return DynIsfTrajectoryTuningOutcome(
                isfMgdlPerU = blendedMgdlPerU,
                appliedMultiplierToBlended = false,
                shadowOnly = false,
                trajectoryMultiplier = mult,
                riseScore = riseScore,
                fallScore = fallScore,
            )
        }

        aapsLogger.debug(LTag.APS, "DYNISF_TRAJECTORY_APPLY $logDetail")
        return DynIsfTrajectoryTuningOutcome(
            isfMgdlPerU = adjusted,
            appliedMultiplierToBlended = true,
            shadowOnly = false,
            trajectoryMultiplier = mult,
            riseScore = riseScore,
            fallScore = fallScore,
        )
    }

    private fun noop(
        blended: Double,
        shadowOnly: Boolean,
        reason: String,
    ): DynIsfTrajectoryTuningOutcome {
        aapsLogger.debug(LTag.APS, "DYNISF_TRAJECTORY_SKIP reason=$reason shadow=$shadowOnly")
        return DynIsfTrajectoryTuningOutcome(
            isfMgdlPerU = blended,
            appliedMultiplierToBlended = false,
            shadowOnly = shadowOnly,
            trajectoryMultiplier = 1.0,
            riseScore = 0.0,
            fallScore = 0.0,
        )
    }

    private companion object {
        const val PHYSIO_ISF_STACK_SKIP_BELOW: Double = 0.94
        const val BG_GATE_MIN: Double = 70.0
        const val BG_GATE_MAX: Double = 260.0
        const val ACTIVATION_THRESHOLD: Double = 0.32
        const val MIN_PARABOLA_CORR: Double = 0.56
        const val RISE_DELTA_SCALE: Double = 11.0
        const val RISE_PN_SCALE: Double = 9.0
        const val RISE_ACC_SCALE: Double = 6.0
        const val FALL_DELTA_SCALE: Double = 10.0
        const val PROFILE_REL_LOW: Double = 0.58
        const val PROFILE_REL_HIGH: Double = 1.42
    }
}

/**
 * Maps [GlucoseStatusAutoIsf] to rise/fall strength in [0,1] for bounded ISF multipliers.
 * Rise → lower ISF (more insulin); fall → higher ISF (more conservative).
 */
internal fun trajectoryRiseFallScores(gs: GlucoseStatusAutoIsf): Pair<Double, Double> {
    val riseFromDelta = (gs.delta / RISE_DELTA_SCALE).coerceIn(0.0, 1.0)
    val riseFromPn = (gs.deltaPn / RISE_PN_SCALE).coerceIn(0.0, 1.0)
    val accTerm =
        if (gs.corrSqu >= MIN_PARABOLA_CORR) {
            (gs.bgAcceleration.coerceAtLeast(0.0) / RISE_ACC_SCALE).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    val riseScore = max(riseFromDelta, max(riseFromPn * 0.92, accTerm * 0.88))

    val fallFromDelta = ((-gs.delta) / FALL_DELTA_SCALE).coerceIn(0.0, 1.0)
    val fallFromPn = ((-gs.deltaPn) / FALL_DELTA_SCALE).coerceIn(0.0, 1.0)
    val fallAcc =
        if (gs.corrSqu >= MIN_PARABOLA_CORR) {
            ((-gs.bgAcceleration) / RISE_ACC_SCALE).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    val fallScore = max(fallFromDelta, max(fallFromPn * 0.92, fallAcc * 0.88))
    return riseScore to fallScore
}

private const val RISE_DELTA_SCALE: Double = 11.0
private const val RISE_PN_SCALE: Double = 9.0
private const val RISE_ACC_SCALE: Double = 6.0
private const val FALL_DELTA_SCALE: Double = 10.0
private const val MIN_PARABOLA_CORR: Double = 0.56
