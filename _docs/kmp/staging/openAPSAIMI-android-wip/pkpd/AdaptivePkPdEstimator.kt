package app.aaps.plugins.aps.openAPSAIMI.pkpd

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Configuration values controlling the online learning loop. */
data class PkPdLearningConfig(
    val bounds: PkPdBounds = PkPdBounds(),
    val minWindowMin: Int = 20,
    val maxWindowMin: Int = 180,
    val minIOBForLearningU: Double = 0.3,
    val maxRateChangeScale: Double = 1.0,
    val lr: Double = 0.02,
    val tailWeight: Double = 1.5,
    /** Soft regularization target for learned DIA (hours). */
    val anchorDiaHrs: Double = 4.0,
    /** Soft regularization target for learned peak (minutes). */
    val anchorPeakMin: Double = 75.0,
)

enum class PkPdUpdateReason {
    NOT_UPDATED,
    ACCEPTED,
    WINDOW_OUT_OF_RANGE,
    IOB_TOO_LOW,
    CARBS_ACTIVE,
    EXERCISE,
    RISE_TOO_FAST,
    HYPO,
    NEAR_HYPO_FALLING,
    FAST_FALL,
}

data class AdaptivePkPdStatusSnapshot(
    val params: PkPdParams,
    val acceptedUpdateCount: Long,
    val latestReason: PkPdUpdateReason,
    val lastAcceptedUpdateAt: Long?,
    val updatedAt: Long?,
    /** Last raw DIA learning step, in hours, before the anchor pull and before any clamp. */
    val lastDiaLearnStepH: Double = 0.0,
    /** Last DIA pull back toward the anchor, in hours. Negative means "pull DIA down". */
    val lastDiaRegPullH: Double = 0.0,
)

class AdaptivePkPdEstimator(
    private val kernel: Kernel = ExponentialKernel(),
    private val cfg: PkPdLearningConfig = PkPdLearningConfig(),
    initial: PkPdParams = PkPdParams(diaHrs = 4.0, peakMin = 75.0)
) {

    companion object {
        /** Must match [PkPdLearningConfig.minWindowMin] / [PkPdIntegration] learning clamp. */
        const val LEARNING_WINDOW_MIN_MIN = 20
        /** Must match [PkPdLearningConfig.maxWindowMin]. */
        const val LEARNING_WINDOW_MAX_MIN = 180

        /** Skip learning below this BG — insulin/glucose dynamics are dominated by hypo recovery. */
        const val HYPO_LEARN_SKIP_BG_MGDL = 75.0

        /** Skip learning when BG is still near hypo and falling. */
        const val HYPO_CAUTION_BG_MGDL = 90.0

        /** Skip learning on fast falls — observed drop is not a reliable PK/PD signal. */
        const val FAST_FALL_DELTA_MGDL5 = -3.0
    }

    private val state = AtomicReference(initial)
    private var lastUpdateEpochMin: Long = 0
    private val acceptedUpdateCount = AtomicLong(0L)
    private val statusRef = AtomicReference(
        AdaptivePkPdStatusSnapshot(
            params = initial,
            acceptedUpdateCount = 0L,
            latestReason = PkPdUpdateReason.NOT_UPDATED,
            lastAcceptedUpdateAt = null,
            updatedAt = null,
        )
    )

    fun params(): PkPdParams = state.get()

    fun update(
        epochMin: Long,
        bg: Double,
        deltaMgDlPer5: Double,
        iobU: Double,
        carbsActiveG: Double,
        windowMin: Int,
        exerciseFlag: Boolean
    ) {
        if (windowMin < cfg.minWindowMin || windowMin > cfg.maxWindowMin) {
            recordSkipped(epochMin, PkPdUpdateReason.WINDOW_OUT_OF_RANGE)
            return
        }
        if (iobU < cfg.minIOBForLearningU) {
            recordSkipped(epochMin, PkPdUpdateReason.IOB_TOO_LOW)
            return
        }
        // RELAXED: Allow learning with moderate carbs (was 5g, now 15g)
        if (carbsActiveG > 15.0) {
            recordSkipped(epochMin, PkPdUpdateReason.CARBS_ACTIVE)
            return
        }
        if (exerciseFlag) {
            recordSkipped(epochMin, PkPdUpdateReason.EXERCISE)
            return
        }
        // RELAXED: Allow learning during slower rises (was 3.0, now 5.0)
        if (deltaMgDlPer5 > 5.0) {
            recordSkipped(epochMin, PkPdUpdateReason.RISE_TOO_FAST)
            return
        }
        if (bg < HYPO_LEARN_SKIP_BG_MGDL) {
            recordSkipped(epochMin, PkPdUpdateReason.HYPO)
            return
        }
        if (bg < HYPO_CAUTION_BG_MGDL && deltaMgDlPer5 < -1.0) {
            recordSkipped(epochMin, PkPdUpdateReason.NEAR_HYPO_FALLING)
            return
        }
        if (deltaMgDlPer5 <= FAST_FALL_DELTA_MGDL5) {
            recordSkipped(epochMin, PkPdUpdateReason.FAST_FALL)
            return
        }

        val p0 = state.get()
        val isfTddMgDlPerU = IsfTddProvider.isfTdd()
        //val dIoBdt = approximateAction(iobU, p0)
        //val expectedDropPer5 = dIoBdt * isfTddMgDlPerU * (5.0 / 60.0)
        val action = kernel.actionAt(windowMin.toDouble(), p0).coerceAtLeast(1e-6)
        val expectedDropPer5 = action * iobU * isfTddMgDlPerU * (5.0 / 60.0)
        val err = (-deltaMgDlPer5) - expectedDropPer5
        val tailFactor = 1.0 + cfg.tailWeight * max(0.0, (windowMin - p0.peakMin) / max(1.0, p0.peakMin))
        val diaAdj = cfg.lr * tailFactor * sign(err) * min(1.0, abs(err) / 10.0)
        // Wave3 F2: drop ×0.5 so NORMAL 5 min/day rate cap can bind on clean ticks.
        val tpAdj = cfg.lr * sign(err) * min(1.0, abs(err) / 10.0)
        val diaReg = 0.002
        // Peak soft-reg weaker so sustained |peak−anchor| can reach ~±20 (was ±5 at reg=0.002).
        val peakReg = 0.001

        val diaRegPull = -diaReg * (p0.diaHrs - cfg.anchorDiaHrs)
        val diaAdjReg = diaAdj + diaRegPull
        val tpAdjReg = tpAdj - peakReg * (p0.peakMin - cfg.anchorPeakMin)

        val now = epochMin
        val minutesPerDay = 60.0 * 24.0
        val dtDays = if (lastUpdateEpochMin == 0L) {
            min(1.0, 5.0 / minutesPerDay)
        } else {
            min(1.0, (now - lastUpdateEpochMin) / minutesPerDay)
        }
        lastUpdateEpochMin = now
        val bounds = cfg.bounds
        val maxDiaStep = bounds.maxDiaChangePerDayH * cfg.maxRateChangeScale * dtDays
        val maxTpStep = bounds.maxPeakChangePerDayMin * cfg.maxRateChangeScale * dtDays
        val newDia = (p0.diaHrs + diaAdjReg)
            .coerceIn(p0.diaHrs - maxDiaStep, p0.diaHrs + maxDiaStep)
            .coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val newTp = (p0.peakMin + tpAdjReg)
            .coerceIn(p0.peakMin - maxTpStep, p0.peakMin + maxTpStep)
            .coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        val newParams = PkPdParams(newDia, newTp)
        state.set(newParams)
        val acceptedCount = acceptedUpdateCount.incrementAndGet()
        val updateAt = epochMin * 60_000L
        statusRef.set(
            AdaptivePkPdStatusSnapshot(
                params = newParams,
                acceptedUpdateCount = acceptedCount,
                latestReason = PkPdUpdateReason.ACCEPTED,
                lastAcceptedUpdateAt = updateAt,
                updatedAt = updateAt,
                lastDiaLearnStepH = diaAdj,
                lastDiaRegPullH = diaRegPull,
            )
        )
    }

    fun statusSnapshot(): AdaptivePkPdStatusSnapshot = statusRef.get().copy()

    private fun recordSkipped(epochMin: Long, reason: PkPdUpdateReason) {
        val previous = statusRef.get()
        statusRef.set(
            AdaptivePkPdStatusSnapshot(
                params = state.get(),
                acceptedUpdateCount = acceptedUpdateCount.get(),
                latestReason = reason,
                lastAcceptedUpdateAt = previous.lastAcceptedUpdateAt,
                updatedAt = epochMin * 60_000L,
                lastDiaLearnStepH = previous.lastDiaLearnStepH,
                lastDiaRegPullH = previous.lastDiaRegPullH,
            )
        )
    }

    private fun approximateAction(iobU: Double, p: PkPdParams): Double {
        val diaMin = p.diaHrs * 60.0
        return iobU / max(60.0, diaMin)
    }

    fun iobResidualAt(minFromDose: Double): Double {
        val params = state.get()
        val cdfFrac = kernel.normalizedCdf(minFromDose, params)
        return (1.0 - cdfFrac).coerceIn(0.0, 1.0)
    }

    fun actionAt(minFromDose: Double): Double =
        kernel.actionAt(minFromDose, state.get()).coerceAtLeast(0.0)

    fun activityStateAt(minFromDose: Double): InsulinActivityState {
        val params = state.get()
        val window = kernel.activityWindow(params)
        val diaMin = window.diaMin
        val clampedMin = minFromDose.coerceIn(0.0, diaMin)
        val peakAction = kernel.actionAt(window.peakMin, params).coerceAtLeast(1e-6)
        val currentAction = if (clampedMin <= 0.0) 0.0 else kernel.actionAt(clampedMin, params) / peakAction
        val normalizedPosition = window.normalizedPosition(clampedMin)
        val postWindow = window.postWindowFraction(clampedMin)
        val minutesUntilOnset = (window.onsetMin - clampedMin).coerceAtLeast(0.0)
        val anticipation = if (minutesUntilOnset <= 0.0) 0.0 else exp(-minutesUntilOnset / 45.0)
        val stage = when {
            clampedMin < window.onsetMin - 1e-6 -> InsulinActivityStage.PRE_ONSET
            clampedMin < window.peakMin -> InsulinActivityStage.RISING
            clampedMin <= window.offsetMin -> InsulinActivityStage.PEAK
            clampedMin < diaMin - 1e-6 -> InsulinActivityStage.TAIL
            else -> InsulinActivityStage.EXHAUSTED
        }
        return InsulinActivityState(
            window = window,
            relativeActivity = currentAction.coerceIn(0.0, 1.0),
            normalizedPosition = normalizedPosition.coerceIn(0.0, 1.0),
            postWindowFraction = postWindow.coerceIn(0.0, 1.0),
            anticipationWeight = anticipation.coerceIn(0.0, 1.0),
            minutesUntilOnset = minutesUntilOnset,
            stage = stage
        )
    }
}

object IsfTddProvider {
    @Volatile
    private var isf: Double = 45.0

    @JvmStatic
    fun isfTdd(): Double = isf

    @JvmStatic
    fun set(valueMgDlPerU: Double) {
        isf = valueMgDlPerU
    }
}