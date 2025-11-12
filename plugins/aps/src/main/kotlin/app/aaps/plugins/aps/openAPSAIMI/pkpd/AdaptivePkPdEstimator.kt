package app.aaps.plugins.aps.openAPSAIMI.pkpd

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
    val tailWeight: Double = 1.5
)

class AdaptivePkPdEstimator(
    private val kernel: Kernel = LogNormalKernel(),
    private val cfg: PkPdLearningConfig = PkPdLearningConfig(),
    initial: PkPdParams = PkPdParams(diaHrs = 20.0, peakMin = 180.0)
) {
    private val state = AtomicReference(initial)
    private var lastUpdateEpochMin: Long = 0

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
        if (windowMin < cfg.minWindowMin || windowMin > cfg.maxWindowMin) return
        if (iobU < cfg.minIOBForLearningU) return
        if (carbsActiveG > 5.0) return
        if (exerciseFlag) return
        if (deltaMgDlPer5 > 3.0) return

        val p0 = state.get()
        val isfTddMgDlPerU = IsfTddProvider.isfTdd()
        //val dIoBdt = approximateAction(iobU, p0)
        //val expectedDropPer5 = dIoBdt * isfTddMgDlPerU * (5.0 / 60.0)
        val action = kernel.actionAt(windowMin.toDouble(), p0).coerceAtLeast(1e-6)
        val expectedDropPer5 = action * iobU * isfTddMgDlPerU * (5.0 / 60.0)
        val err = (-deltaMgDlPer5) - expectedDropPer5
        val tailFactor = 1.0 + cfg.tailWeight * max(0.0, (windowMin - p0.peakMin) / max(1.0, p0.peakMin))
        val diaAdj = cfg.lr * tailFactor * sign(err) * min(1.0, abs(err) / 10.0)
        val tpAdj = cfg.lr * 0.5 * sign(err) * min(1.0, abs(err) / 10.0)
        val now = epochMin
        val dtDays = if (lastUpdateEpochMin == 0L) 1.0 else max(1.0, (now - lastUpdateEpochMin) / (60.0 * 24.0))
        lastUpdateEpochMin = now
        val bounds = cfg.bounds
        val maxDiaStep = bounds.maxDiaChangePerDayH * cfg.maxRateChangeScale * dtDays
        val maxTpStep = bounds.maxPeakChangePerDayMin * cfg.maxRateChangeScale * dtDays
        val newDia = (p0.diaHrs + diaAdj)
            .coerceIn(p0.diaHrs - maxDiaStep, p0.diaHrs + maxDiaStep)
            .coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val newTp = (p0.peakMin + tpAdj)
            .coerceIn(p0.peakMin - maxTpStep, p0.peakMin + maxTpStep)
            .coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        state.set(PkPdParams(newDia, newTp))
    }

    private fun approximateAction(iobU: Double, p: PkPdParams): Double {
        val diaMin = p.diaHrs * 60.0
        return iobU / max(60.0, diaMin)
    }

    fun iobResidualAt(minFromDose: Double): Double =
        kernel.iobResidual(minFromDose, state.get()).coerceIn(0.0, 1.0)

    fun actionAt(minFromDose: Double): Double =
        kernel.actionAt(minFromDose, state.get()).coerceAtLeast(0.0)
}

object IsfTddProvider {
    @Volatile
    private var isf: Double = 45.0

    fun isfTdd(): Double = isf

    fun set(valueMgDlPerU: Double) {
        isf = valueMgDlPerU
    }
}