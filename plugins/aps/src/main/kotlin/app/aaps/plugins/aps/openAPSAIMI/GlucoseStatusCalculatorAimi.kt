package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSAIMI.extensions.asRounded
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/** Features additionnels pour AIMI, calculés en même temps que GlucoseStatusAIMI. */
data class AimiBgFeatures(
    val accel: Double,
    val delta5Prev: Double,
    val delta5Next: Double,
    val corrR2: Double,
    val parabolaMinutes: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
    val stable5pctMinutes: Double,
    val stable5pctAverage: Double,
    val combinedDelta: Double,
    val isNightGrowthCandidate: Boolean
)

@Reusable
class GlucoseStatusCalculatorAimi @Inject constructor(
    private val log: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val fmt: DecimalFormatter,
    private val deltaCalculator: DeltaCalculator,
    private val preferences: Preferences
) {
    private object Defaults {
        const val FRESHNESS_MIN = 7L
        const val SERIES_GAP_MIN = 13L
        const val FIT_GAP_MIN = 9L
        const val CUTOFF_MIN = 30.0
        const val BW_FRACTION = 0.05
        const val CACHE_TTL_MS = 30_000L
    }

    data class Result(
        val gs: GlucoseStatusAIMI?,
        val features: AimiBgFeatures?
    )

    private var last: Result? = null
    private var lastTs: Long = 0L

    /** API “standard” pour le plugin: renvoie un GlucoseStatusAIMI. */
    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAIMI? =
        (if (shouldRecompute(allowOldData)) compute(allowOldData) else last)?.gs

    /** API AIMI: renvoie les features (accélération, deltas paraboliques, etc.). */
    fun getAimiFeatures(allowOldData: Boolean): AimiBgFeatures? =
        (if (shouldRecompute(allowOldData)) compute(allowOldData) else last)?.features

    /** Recalcule et renvoie (GS AIMI + features). */
    fun compute(allowOldData: Boolean): Result {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
        if (data == null || data.isEmpty()) {
            log.debug(LTag.GLUCOSE, "AIMI GS: no data")
            return storeAndReturn(null, null)
        }

        val now = dateUtil.now()
        if (data[0].timestamp < now - Defaults.FRESHNESS_MIN * 60 * 1000L && !allowOldData) {
            log.debug(LTag.GLUCOSE, "AIMI GS: old data")
            return storeAndReturn(null, null)
        }

        // 1) Deltas standard
        val deltas = deltaCalculator.calculateDeltas(data)

        // 2) Bande ±5%
        val bw = Defaults.BW_FRACTION
        val head = data[0]
        val headTs = head.timestamp
        var sum = head.recalculated
        var avg = sum
        var minutesDur = 0.0
        var n = 1
        for (i in 1 until data.size) {
            val rec = data[i]
            if (rec.value <= 39 || rec.filledGap) continue
            val gapMin = (headTs - rec.timestamp) / 60000.0
            val within = rec.recalculated > avg * (1 - bw) && rec.recalculated < avg * (1 + bw)
            if (gapMin - minutesDur > Defaults.SERIES_GAP_MIN || !within) break
            n++
            sum += rec.recalculated
            avg = sum / n
            minutesDur = gapMin
        }

        // 3) Fit quadratique local
        val fit = QuadraticFit.fit5min(
            data = data,
            cutoffMin = Defaults.CUTOFF_MIN,
            fitGapMin = Defaults.FIT_GAP_MIN,
            time = { it.timestamp },
            recalc = { it.recalculated },
            filled = { it.filledGap }
        )

        // 4) Delta combiné (poids depuis prefs)
        val wDelta = 1.0
        val wShort = preferences.getOr(DoubleKey.OApsAIMIAutodriveAcceleration, 1.0)
        val wLong  = preferences.getOr(DoubleKey.OApsAIMIcombinedDelta, 1.0)
        val combinedDelta = combineDelta(
            d = deltas.delta,
            s = deltas.shortAvgDelta,
            l = deltas.longAvgDelta,
            wD = wDelta, wS = wShort, wL = wLong
        )

        // 5) Heuristique Night-Growth
        val pediatricAge = runCatching { preferences.get(IntKey.OApsAIMINightGrowthAgeYears) }.getOrNull() ?: 12
        val ngrSlopeMin = deriveNightSlopeThreshold(pediatricAge, minutesDur)
        val isNg = (fit?.deltaPl ?: 0.0) >= ngrSlopeMin || (fit?.accel ?: 0.0) > 0.0

        // 6) GS AIMI + features
        val features = AimiBgFeatures(
            accel = fit?.accel ?: 0.0,
            delta5Prev = fit?.deltaPl ?: 0.0,
            delta5Next = fit?.deltaPn ?: 0.0,
            corrR2 = fit?.r2 ?: 0.0,
            parabolaMinutes = fit?.duraMin ?: 0.0,
            a0 = fit?.a0 ?: 0.0,
            a1 = fit?.a1 ?: 0.0,
            a2 = fit?.a2 ?: 0.0,
            stable5pctMinutes = minutesDur,
            stable5pctAverage = avg,
            combinedDelta = combinedDelta,
            isNightGrowthCandidate = isNg
        )

        val gsAimi = GlucoseStatusAIMI(
            glucose = head.recalculated,
            noise = 0.0,
            delta = deltas.delta,
            shortAvgDelta = deltas.shortAvgDelta,
            longAvgDelta = deltas.longAvgDelta,
            date = head.timestamp,
            duraISFminutes = minutesDur,
            duraISFaverage = avg,
            parabolaMinutes = features.parabolaMinutes,
            deltaPl = features.delta5Prev,
            deltaPn = features.delta5Next,
            bgAcceleration = features.accel,
            a0 = features.a0,
            a1 = features.a1,
            a2 = features.a2,
            corrSqu = features.corrR2
        ).asRounded()

        log.debug(
            LTag.GLUCOSE,
            "AIMI GS: g=${head.recalculated} " +
                "Δ=${fmt.to1Decimal(deltas.delta)} " +
                "SA=${fmt.to1Decimal(deltas.shortAvgDelta)} " +
                "LA=${fmt.to1Decimal(deltas.longAvgDelta)} " +
                "acc=${fmt.to2Decimal(features.accel)} " +
                "combΔ=${fmt.to2Decimal(features.combinedDelta)} " +
                "R2=${fmt.to2Decimal(features.corrR2)} " +
                "fitMin=${fmt.to1Decimal(features.parabolaMinutes)} " +
                "NG=$isNg"
        )

        return storeAndReturn(gsAimi, features)
    }

    private fun storeAndReturn(gs: GlucoseStatusAIMI?, features: AimiBgFeatures?): Result {
        val res = Result(gs, features)
        last = res
        lastTs = dateUtil.now()
        return res
    }

    private fun shouldRecompute(allowOldData: Boolean): Boolean {
        if (last == null) return true
        val staleness = dateUtil.now() - lastTs
        return (!allowOldData) || staleness > Defaults.CACHE_TTL_MS
    }

    private fun Preferences.getOr(key: DoubleKey, default: Double): Double =
        runCatching { this.get(key) }.getOrNull() ?: default

    private fun deriveNightSlopeThreshold(age: Int, stabilityMinutes: Double): Double {
        val ageBase = when {
            age <= 7 -> 3.5
            age <= 12 -> 4.5
            else -> 5.0
        }
        val stabilityBonus = min(1.0, stabilityMinutes / 60.0)
        return max(2.5, ageBase - stabilityBonus)
    }

    private fun combineDelta(d: Double, s: Double, l: Double, wD: Double, wS: Double, wL: Double): Double {
        val sumW = max(1e-9, wD + wS + wL)
        return (wD * d + wS * s + wL * l) / sumW
    }
}

private object QuadraticFit {
    data class Out(
        val accel: Double,
        val deltaPl: Double,
        val deltaPn: Double,
        val r2: Double,
        val duraMin: Double,
        val a0: Double,
        val a1: Double,
        val a2: Double
    )
    private var accelFiltered = 0.0
    fun <T> fit5min(
        data: List<T>,
        cutoffMin: Double,
        fitGapMin: Long,
        time: (T) -> Long,
        recalc: (T) -> Double,
        filled: (T) -> Boolean
    ): Out? {
        if (data.size < 4) return null
        val SCALE_TIME = 300.0
        val SCALE_BG = 50.0
        val t0 = time(data[0])
        var lastTi = 0.0
        var n = 0
        var sumY = 0.0
        var sumX = 0.0
        var sumX2 = 0.0
        var sumX3 = 0.0
        var sumX4 = 0.0
        var sumXY = 0.0
        var sumX2Y = 0.0
        var best: Out? = null
        var bestR2 = -1.0

        for (i in data.indices) {
            val r = data[i]
            if (recalc(r) <= 39 || filled(r)) continue
            n++
            val ti = (time(r) - t0) / 1000.0 / SCALE_TIME
            if (-ti * SCALE_TIME > cutoffMin * 60) break
            if (ti < lastTi - fitGapMin * 60 / SCALE_TIME) break
            lastTi = ti
            val x = ti
            val y = recalc(r) / SCALE_BG
            val x2 = x * x
            val x3 = x2 * x
            val x4 = x2 * x2
            sumX += x; sumX2 += x2; sumX3 += x3; sumX4 += x4
            sumY += y; sumXY += x * y; sumX2Y += x2 * y

            if (n > 3) {
                val detH = sumX4 * (sumX2 * n - sumX * sumX) -
                    sumX3 * (sumX3 * n - sumX * sumX2) +
                    sumX2 * (sumX3 * sumX - sumX2 * sumX2)
                if (detH == 0.0) continue

                val detA = sumX2Y * (sumX2 * n - sumX * sumX) -
                    sumXY  * (sumX3 * n - sumX * sumX2) +
                    sumY   * (sumX3 * sumX - sumX2 * sumX2)
                val detB = sumX4 * (sumXY * n - sumY * sumX) -
                    sumX3 * (sumX2Y * n - sumY * sumX2) +
                    sumX2 * (sumX2Y * sumX - sumXY * sumX2)
                val detC = sumX4 * (sumX2 * sumY - sumX * sumXY) -
                    sumX3 * (sumX3 * sumY - sumX * sumX2Y) +
                    sumX2 * (sumX3 * sumXY - sumX2 * sumX2Y)

                val a = detA / detH
                val b = detB / detH
                val c = detC / detH

                var ssTot = 0.0
                var ssRes = 0.0
                val yMean = sumY / n
                for (j in 0..i) {
                    val rj = data[j]
                    val xj = (time(rj) - t0) / 1000.0 / SCALE_TIME
                    val yj = recalc(rj) / SCALE_BG
                    val yHat = a * xj * xj + b * xj + c
                    val d = yj - yHat
                    ssTot += (yj - yMean) * (yj - yMean)
                    ssRes += d * d
                }
                val r2 = if (ssTot != 0.0) 1 - ssRes / ssTot else 0.0

                val dt = 5 * 60 / SCALE_TIME
                val accelCurrent = 2 * a * SCALE_BG / 60.0  // mg/dL/min²

// --- Nouveau : filtrage exponentiel léger pour stabiliser ---
                accelFiltered = 0.6 * accelFiltered + 0.4 * accelCurrent

// --- Affectation finale ---
                val accel = accelFiltered

                val deltaPl = -SCALE_BG * (a * (-dt) * (-dt) - b * dt)
                val deltaPn =  SCALE_BG * (a * ( dt) * ( dt) + b * dt)

                val a0 = c * SCALE_BG
                val a1 = b * SCALE_BG
                val a2 = a * SCALE_BG

                if (r2 >= bestR2) {
                    val duraMin = (-ti * (SCALE_TIME / 60.0)) * 5.0
                    bestR2 = r2
                    best = Out(accel, deltaPl, deltaPn, r2, max(0.0, duraMin), a0, a1, a2)
                }
            }
        }
        return best
    }
}
