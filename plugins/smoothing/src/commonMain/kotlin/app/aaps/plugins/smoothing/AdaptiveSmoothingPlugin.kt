package app.aaps.plugins.smoothing

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.awaitInitialized
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualitySnapshot
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.rx.events.EventAdaptiveSmoothingQuality
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.interfaces.smoothing.SmoothingContext
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcStats
import app.aaps.plugins.smoothing.keys.UkfDoubleNonKey
import app.aaps.plugins.smoothing.keys.UkfLongNonKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Adaptive UKF smoothing plugin.
 *
 * Combines:
 * 1. Unscented Kalman Filter (UKF) for signal processing and trend estimation.
 * 2. Rule-based safety logic for compression artifacts and low-glucose handling.
 * 3. Time-series segmentation for xDrip / notification listener style feeds: major gaps reset state;
 *    minor gaps damp rate before prediction; learned R is softly nudged after long silences.
 */
@OptIn(FlowPreview::class, ExperimentalAtomicApi::class)
@Inject
@SingleIn(AppScope::class)
// Bound as PluginBase, not implicitly: these classes have two supertypes (PluginBase and Smoothing),
// so Metro cannot pick one. The plugin list wants PluginBase.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(615)
class AdaptiveSmoothingPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    private val rxBus: RxBus,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val iobCobCalculator: IobCobCalculator,
    private val profileFunction: ProfileFunction
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.SMOOTHING)
        .icon(IcStats)
        .pluginName(SmoothingStrings.adaptive_smoothing_name)
        .shortName(SmoothingStrings.smoothing_shortname)
        .description(SmoothingStrings.description_adaptive_smoothing),
    aapsLogger, rh
), Smoothing {

    override fun preferDashboardGlucoseFromGlucoseStatus(): Boolean = true

    override fun lastAdaptiveSmoothingQualitySnapshot(): AdaptiveSmoothingQualitySnapshot? = lastQualitySnapshot

    // ============================================================
    // UKF CONFIGURATION & PARAMETERS
    // ============================================================

    private val n = 2 // State dimension [G, Ġ]
    private val alpha = 1.00
    private val beta = 0.0
    private val kappa = 3.0
    private val lambda = alpha * alpha * (n + kappa) - n
    private val gamma = sqrt(n + lambda)

    // Sigma point weights
    private val wm = DoubleArray(2 * n + 1)
    private val wc = DoubleArray(2 * n + 1)

    // FIXED process noise (Physiological Limits)
    private val qFixed = doubleArrayOf(
        1.0, 0.0,     // Glucose process noise: ~2.4 mg/dL std dev per 5 min
        0.0, 0.40     // Rate process noise: ~0.24 mg/dL/min std dev
    )

    // Adaptive Measurement Noise (R) Limits (learned default = [UkfDoubleNonKey.LearnedR])
    private val rMin = 16.0
    private val rMax = 196.0

    // Adaptation Logic
    private val innovationWindow = 48
    private val rateDamping = 0.98

    // Processing state
    private var learnedR = UkfDoubleNonKey.LearnedR.defaultValue
    private val innovations = ArrayDeque<Double>(innovationWindow + 1)
    private val rawInnovationVariance = ArrayDeque<Double>(innovationWindow + 1)
    private var lastProcessedTimestamp: Long = 0
    private var lastSensorChangeTimestamp: Long = 0

    /** Profile ISF (mg/dL per U) snapshot for the current [smooth] pass. Drives patient-relative compression
     *  feasibility; null when the profile is unavailable → compression detection is disabled (never blocks). */
    private var passIsfMgdlPerU: Double? = null

    private val smoothingSupervisor = SupervisorJob()

    // Dispatchers.IO does not exist on Kotlin/Native, so the shared code uses Dispatchers.Default. Both
    // run these jobs off the caller's thread; the work here is a database query and a flow collection.
    private val smoothingScope = CoroutineScope(smoothingSupervisor + Dispatchers.Default)

    private var lastAdaptiveSmoothingQualityTier: AdaptiveSmoothingQualityTier? = null
    private var lastAdaptiveSmoothingQualityEventAt: Long = 0L

    @Volatile
    private var lastQualitySnapshot: AdaptiveSmoothingQualitySnapshot? = null

    private val resetRequested = AtomicBoolean(false)
    private var sensorObservationJob: Job? = null
    private var sensorBackfillJob: Job? = null

    // Safety Context
    private data class GlycemicContext(
        val cv: Double,
        val zone: GlycemicZone,
        val currentBg: Double,
        val iob: Double,
        val isNight: Boolean,
        val rawDelta: Double, // Heuristic delta for rules
        val dtMin: Double, // minutes between this sample and the previous (older) one — normalizes irregular feeds
        val isfMgdlPerU: Double? // profile ISF snapshot; null → compression detection disabled (never blocks)
    )

    private enum class GlycemicZone { HYPO, LOW_NORMAL, TARGET, HYPER }

    /**
     * Contiguous index run in `data` where index 0 is newest and `lastIndex` oldest.
     * [newestIdx] ≤ [oldestIdx]; both inclusive.
     * [leadingGapMinutesBeforeSegment] is the time gap from the previous point toward present when this
     * segment follows a **major** break (> `MAJOR_GAP_MINUTES`); null for the chronologically oldest segment.
     */
    private data class SmoothingIndexSegment(
        val newestIdx: Int,
        val oldestIdx: Int,
        val leadingGapMinutesBeforeSegment: Double?,
    )

    /** Median-based spacing rules for xDrip / notification listener (recomputed each [smooth]). */
    private data class GapPolicy(
        val minorGapMinutes: Double,
        val invalidMinSpacingMinutes: Double,
    )

    /** One UKF step snapshot for RTS backward pass (newest-first deque order). */
    private data class FilterState(
        val x: DoubleArray,
        val P: DoubleArray,
        val xPred: DoubleArray,
        val PPred: DoubleArray,
        val dt: Double,
    ) {

        // Hand written only to satisfy the data-class-with-array warning. These snapshots are never
        // compared or hashed, so the content-based versions change nothing at runtime.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FilterState) return false
            if (!x.contentEquals(other.x)) return false
            if (!P.contentEquals(other.P)) return false
            if (!xPred.contentEquals(other.xPred)) return false
            if (!PPred.contentEquals(other.PPred)) return false
            return dt == other.dt
        }

        override fun hashCode(): Int {
            var result = x.contentHashCode()
            result = 31 * result + P.contentHashCode()
            result = 31 * result + xPred.contentHashCode()
            result = 31 * result + PPred.contentHashCode()
            result = 31 * result + dt.hashCode()
            return result
        }
    }

    // ============================================================
    // INITIALIZATION
    // ============================================================

    init {
        initSigmaWeights()
        loadPersistedParameters()
        sensorObservationJob = smoothingScope.launch {
            if (!config.awaitInitialized(30_000L)) {
                aapsLogger.warn(LTag.GLUCOSE, "AdaptiveSmoothing: config not initialized; sensor TE observation not started")
                return@launch
            }
            try {
                persistenceLayer.observeChanges(TE::class)
                    .debounce(SENSOR_CHANGE_DEBOUNCE_SECONDS * 1000)
                    .collect { checkForSensorChange() }
            } catch (t: Throwable) {
                aapsLogger.error(LTag.GLUCOSE, "AdaptiveSmoothing: sensor subscription error", t)
            }
        }
        sensorBackfillJob = smoothingScope.launch {
            if (!config.awaitInitialized(30_000L)) return@launch
            runCatching { loadInitialSensorChangeFromDb() }
                .onFailure { t -> aapsLogger.error(LTag.GLUCOSE, "AdaptiveSmoothing: loadLastSensorChange error", t) }
        }
    }

    private fun initSigmaWeights() {
        wm[0] = lambda / (n + lambda)
        wc[0] = lambda / (n + lambda) + (1 - alpha * alpha + beta)
        val w = 1.0 / (2.0 * (n + lambda))
        for (i in 1 until 2 * n + 1) {
            wm[i] = w
            wc[i] = w
        }
    }

    override suspend fun onStop() {
        sensorObservationJob?.cancel()
        sensorBackfillJob?.cancel()
        smoothingSupervisor.cancelChildren()
        super.onStop()
    }

    // ============================================================
    // MAIN SMOOTHING LOOP
    // ============================================================

    override suspend fun smooth(
        data: MutableList<InMemoryGlucoseValue>,
        context: SmoothingContext
    ): MutableList<InMemoryGlucoseValue> {
        if (data.size < 2) {
            copyRawToSmoothed(data)
            sanitizeOutput(data)
            refreshSnapshotAfterShortOrRawPass()
            return data
        }

        try {
            if (shouldResetLearning(data[0].timestamp)) {
                resetLearning()
            }

            val previousTimestamp = lastProcessedTimestamp
            lastProcessedTimestamp = data[0].timestamp

            val cachedIobTotalU = context.cachedTotalIobUnits ?: run {
                val bolusIob = iobCobCalculator.calculateIobFromBolus().iob
                val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().iob
                bolusIob + basalIob
            }
            // Profile ISF snapshot for patient-relative compression feasibility (stable proxy — using the profile
            // ISF rather than dynISF avoids a smoothed-BG → dynISF → smoothing feedback loop; the margin + metabolic
            // floor absorb the approximation). Null (profile unavailable) disables compression detection this pass.
            passIsfMgdlPerU = runCatching { profileFunction.getProfile()?.getProfileIsfMgdl() }
                .getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            processHybridSegments(data, cachedIobTotalU)

            val newDataProcessed = data.any { it.timestamp > previousTimestamp }
            if (newDataProcessed) {
                savePersistedParameters()
            }

            sanitizeOutput(data)
            return data

        } catch (e: Exception) {
            aapsLogger.error(LTag.GLUCOSE, "HybridSmoothing: Error, falling back to raw", e)
            copyRawToSmoothed(data)
            sanitizeOutput(data)
            val now = nowMillis()
            lastQualitySnapshot = AdaptiveSmoothingQualitySnapshot(
                tier = AdaptiveSmoothingQualityTier.BAD,
                learnedR = learnedR,
                outlierRate = 1.0,
                compressionRate = 0.0,
                updatedAtMillis = now
            )
            return data
        }
    }

    /**
     * Splits the CGM series into contiguous segments (xDrip / NL friendly), runs the hybrid UKF per segment,
     * then aggregates quality metrics. Indices: [0] = newest.
     */
    private fun processHybridSegments(
        data: MutableList<InMemoryGlucoseValue>,
        cachedIobTotalU: Double,
    ) {
        val gapPolicy = computeGapPolicy(data)
        aapsLogger.debug(
            LTag.GLUCOSE,
            "AdaptiveSmoothing: gapPolicy minor=${gapPolicy.minorGapMinutes.fmtLog(1)} min, invalid=${gapPolicy.invalidMinSpacingMinutes.fmtLog(2)} min"
        )
        val segments = findSmoothingSegments(data, gapPolicy)
        val covered = BooleanArray(data.size)
        var measurementNoiseR = learnedR
        var processedPoints = 0
        var compressionPoints = 0
        var outlierPoints = 0

        if (segments.isEmpty()) {
            aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: no multi-point segment; raw fallback")
            for (idx in data.indices) {
                data[idx].smoothed = max(data[idx].calibratedOrValue, MIN_VALID_BG)
                data[idx].trendArrow = TrendArrow.NONE
            }
            emitQualitySnapshot(0.0, 0.0)
            return
        }

        for (segment in segments) {
            if (segment.leadingGapMinutesBeforeSegment != null) {
                innovations.clear()
                rawInnovationVariance.clear()
                val gap = segment.leadingGapMinutesBeforeSegment
                measurementNoiseR = blendRAfterMajorGap(measurementNoiseR, gap)
                aapsLogger.debug(
                    LTag.GLUCOSE,
                    "AdaptiveSmoothing: new segment after ${gap.fmtLog(0)} min gap; R→${measurementNoiseR.fmtLog(1)}"
                )
            }
            val (p, c, o) = processHybridIndexSegment(data, segment, cachedIobTotalU, measurementNoiseR, covered, gapPolicy)
            processedPoints += p
            compressionPoints += c
            outlierPoints += o
            measurementNoiseR = learnedR
        }

        for (idx in data.indices) {
            if (!covered[idx]) {
                data[idx].smoothed = max(data[idx].calibratedOrValue, MIN_VALID_BG)
                data[idx].trendArrow = TrendArrow.NONE
            }
        }

        learnedR = measurementNoiseR

        val compressionRate = if (processedPoints > 0) compressionPoints.toDouble() / processedPoints.toDouble() else 0.0
        val outlierRate = if (processedPoints > 0) outlierPoints.toDouble() / processedPoints.toDouble() else 0.0
        emitQualitySnapshot(compressionRate, outlierRate)
    }

    private fun emitQualitySnapshot(compressionRate: Double, outlierRate: Double) {
        val tier = when {
            compressionRate >= 0.15 || outlierRate >= 0.25 || learnedR >= 70.0 -> AdaptiveSmoothingQualityTier.BAD
            learnedR >= 45.0 || outlierRate >= 0.10 || compressionRate >= 0.07 -> AdaptiveSmoothingQualityTier.UNCERTAIN
            else                                                               -> AdaptiveSmoothingQualityTier.OK
        }

        val now = nowMillis()
        lastQualitySnapshot = AdaptiveSmoothingQualitySnapshot(
            tier = tier,
            learnedR = learnedR,
            outlierRate = outlierRate,
            compressionRate = compressionRate,
            updatedAtMillis = now
        )

        val shouldSend = lastAdaptiveSmoothingQualityTier != tier ||
            (now - lastAdaptiveSmoothingQualityEventAt) >= QUALITY_EVENT_THROTTLE_MS

        if (shouldSend) {
            lastAdaptiveSmoothingQualityTier = tier
            lastAdaptiveSmoothingQualityEventAt = now
            rxBus.send(EventAdaptiveSmoothingQuality(tier, learnedR, outlierRate, compressionRate))
        }
    }

    /**
     * Builds segments along the time axis (oldest high index → newest 0). Splits on major gaps,
     * implausible tight spacing (duplicates / NL jitter), or LO error rows.
     */
    private fun computeGapPolicy(data: List<InMemoryGlucoseValue>): GapPolicy {
        val dts = ArrayList<Double>(MEDIAN_DT_MAX_PAIRS)
        val limit = min(data.size - 1, MEDIAN_DT_MAX_PAIRS)
        for (i in 0 until limit) {
            val dt = (data[i].timestamp - data[i + 1].timestamp) / MILLIS_PER_MINUTE
            if (dt > 0.0 && dt < MAJOR_GAP_MINUTES) {
                dts.add(dt)
            }
        }
        if (dts.isEmpty()) {
            return GapPolicy(minorGapMinutes = MINOR_GAP_DEFAULT_MINUTES, invalidMinSpacingMinutes = INVALID_SPACING_DEFAULT_MINUTES)
        }
        dts.sort()
        val median = dts[dts.size / 2]
        val invalid = (median * MEDIAN_TO_INVALID_FACTOR).coerceIn(INVALID_SPACING_FLOOR_MINUTES, INVALID_SPACING_CEILING_MINUTES)
        val minor = (median * MEDIAN_TO_MINOR_FACTOR).coerceIn(MINOR_GAP_FLOOR_MINUTES, MINOR_GAP_CEILING_MINUTES)
        return GapPolicy(minorGapMinutes = minor, invalidMinSpacingMinutes = invalid)
    }

    private fun findSmoothingSegments(data: List<InMemoryGlucoseValue>, gapPolicy: GapPolicy): List<SmoothingIndexSegment> {
        if (data.size < 2) return emptyList()
        val out = mutableListOf<SmoothingIndexSegment>()
        var oldestEnd = data.lastIndex

        for (newer in data.lastIndex - 1 downTo 0) {
            val older = newer + 1
            val dtMin = (data[newer].timestamp - data[older].timestamp) / MILLIS_PER_MINUTE
            val breakSeg = dtMin > MAJOR_GAP_MINUTES ||
                dtMin < gapPolicy.invalidMinSpacingMinutes ||
                dtMin < 0.0 ||
                data[newer].value <= LO_BG_VALUE_STEPS ||
                data[older].value <= LO_BG_VALUE_STEPS

            if (breakSeg) {
                if (oldestEnd > older) {
                    out.add(SmoothingIndexSegment(newestIdx = older, oldestIdx = oldestEnd, leadingGapMinutesBeforeSegment = null))
                }
                oldestEnd = newer
            }
        }
        if (oldestEnd >= 0) {
            out.add(SmoothingIndexSegment(newestIdx = 0, oldestIdx = oldestEnd, leadingGapMinutesBeforeSegment = null))
        }
        annotateInterSegmentGaps(out, data)
        return out
    }

    /** Fills [SmoothingIndexSegment.leadingGapMinutesBeforeSegment] for every segment except the chronologically oldest. */
    private fun annotateInterSegmentGaps(segments: MutableList<SmoothingIndexSegment>, data: List<InMemoryGlucoseValue>) {
        if (segments.size < 2) return
        for (i in 1 until segments.size) {
            val cur = segments[i]
            val boundaryOlderIdx = cur.oldestIdx + 1
            if (boundaryOlderIdx > data.lastIndex) continue
            val gapMin = (data[cur.oldestIdx].timestamp - data[boundaryOlderIdx].timestamp) / MILLIS_PER_MINUTE
            if (!gapMin.isFinite() || gapMin < 0) continue
            segments[i] = cur.copy(leadingGapMinutesBeforeSegment = gapMin)
        }
    }

    /** After a major silence, nudge R toward default while keeping most of the learned sensor noise. */
    private fun blendRAfterMajorGap(currentR: Double, gapMinutes: Double): Double {
        if (!gapMinutes.isFinite() || gapMinutes <= MAJOR_GAP_MINUTES) return currentR
        val lambda = min(1.0, (gapMinutes - MAJOR_GAP_MINUTES) / R_BLEND_SLOPE_MINUTES) * R_BLEND_MAX_WEIGHT
        val rInit = UkfDoubleNonKey.LearnedR.defaultValue
        return ((1.0 - lambda) * currentR + lambda * rInit).coerceIn(rMin, rMax)
    }

    private fun rateDecayMinorGap(dtMinutes: Double): Double =
        exp(-dtMinutes / RATE_DECAY_TIME_CONSTANT_MINUTES)

    /**
     * Forward UKF over one index-contiguous segment; marks `covered` for processed indices.
     * @return Triple(processed count, compression count, outlier count)
     */
    private fun processHybridIndexSegment(
        data: MutableList<InMemoryGlucoseValue>,
        segment: SmoothingIndexSegment,
        cachedIobTotalU: Double,
        initialR: Double,
        covered: BooleanArray,
        gapPolicy: GapPolicy,
    ): Triple<Int, Int, Int> {
        val oldestIdx = segment.oldestIdx
        val newestIdx = segment.newestIdx
        val x = doubleArrayOf(data[oldestIdx].calibratedOrValue, 0.0)
        val stateCovariance = doubleArrayOf(16.0, 0.0, 0.0, 1.0)
        var measurementNoiseR = initialR

        val segmentSize = oldestIdx - newestIdx + 1
        val gNewestFirst = DoubleArray(segmentSize)
        val ratesNewestFirst = DoubleArray(segmentSize)
        val forwardStates = ArrayDeque<FilterState>(segmentSize)

        var processedPoints = 0
        var compressionPoints = 0
        var outlierPoints = 0

        for (i in oldestIdx downTo newestIdx) {
            covered[i] = true
            val z = data[i].calibratedOrValue
            val timestamp = data[i].timestamp
            processedPoints++

            val dtRaw = if (i < oldestIdx) {
                (timestamp - data[i + 1].timestamp) / MILLIS_PER_MINUTE
            } else {
                DEFAULT_ASSUMED_SAMPLE_MINUTES
            }

            if (i < oldestIdx && dtRaw > gapPolicy.minorGapMinutes && dtRaw <= MAJOR_GAP_MINUTES) {
                x[1] *= rateDecayMinorGap(dtRaw)
                aapsLogger.debug(LTag.GLUCOSE, "AdaptiveSmoothing: minor gap ${dtRaw.fmtLog(1)} min → rate damp")
            }

            val dtPredictCap = min(PREDICT_DT_HARD_CAP_MINUTES, max(PREDICT_DT_SOFT_FLOOR_MINUTES, gapPolicy.minorGapMinutes * PREDICT_DT_SCALE_VS_MINOR))
            val dtPredict = when {
                i == oldestIdx             -> DEFAULT_ASSUMED_SAMPLE_MINUTES
                dtRaw > MAJOR_GAP_MINUTES  -> MAJOR_GAP_MINUTES
                else                       -> dtRaw.coerceIn(MIN_DT_MINUTES, dtPredictCap)
            }

            val ctx = calculateGlycemicContext(data, i, cachedIobTotalU)
            val isCompression = isCompressionArtifactCandidate(ctx)
            if (isCompression) compressionPoints++

            val isHypoCritical = ctx.currentBg < 70.0

            var (xPred, predictedCovariance) = predict(x, stateCovariance, qFixed, dtPredict)

            val preFitInnovation = z - xPred[0]
            val preFitSigma = sqrt(predictedCovariance[0] + measurementNoiseR)
            val normInnovation = preFitInnovation / preFitSigma
            val isRapidManeuver = normInnovation > 2.5 && preFitInnovation > 0

            if (isRapidManeuver) {
                aapsLogger.debug(LTag.GLUCOSE, "HybridSmoothing: RAPID RISE DETECTED (Innov=${preFitInnovation.toInt()}). Inflating Q for Zero-Lag.")
                val qAdaptive = qFixed.copyOf()
                qAdaptive[3] *= 50.0
                qAdaptive[0] *= 2.0
                val result = predict(x, stateCovariance, qAdaptive, dtPredict)
                xPred = result.first
                predictedCovariance = result.second
            }

            if (isCompression) {
                aapsLogger.warn(LTag.GLUCOSE, "HybridSmoothing: COMPRESSION BLOCKED at ${z.toInt()} mg/dL. Holding prediction.")
                forwardStates.addFirst(
                    FilterState(
                        x.copyOf(),
                        stateCovariance.copyOf(),
                        xPred.copyOf(),
                        predictedCovariance.copyOf(),
                        dtPredict,
                    )
                )
                x[0] = xPred[0]
                x[1] = xPred[1]
                stateCovariance[0] = predictedCovariance[0]
                stateCovariance[1] = predictedCovariance[1]
                stateCovariance[2] = predictedCovariance[2]
                stateCovariance[3] = predictedCovariance[3]
                data[i].smoothed = x[0]
            } else {
                forwardStates.addFirst(
                    FilterState(
                        x.copyOf(),
                        stateCovariance.copyOf(),
                        xPred.copyOf(),
                        predictedCovariance.copyOf(),
                        dtPredict,
                    )
                )
                val innovation = z - xPred[0]
                val innovationVariance = predictedCovariance[0] + measurementNoiseR
                val isStatisticalOutlier = isOutlier(innovation, innovationVariance)
                if (isStatisticalOutlier) outlierPoints++

                measurementNoiseR = adaptMeasurementNoise(measurementNoiseR, innovations, rawInnovationVariance)
                trackInnovation(innovation, innovationVariance)
                update(xPred, predictedCovariance, z, measurementNoiseR, x, stateCovariance)

                val velocity = x[1]
                val predictedBg20min = x[0] + (velocity * 20.0)
                val isKineticHypo = (predictedBg20min < 55.0) ||
                    (z < 80.0 && velocity < -1.5) ||
                    (velocity < -3.0)

                if (isKineticHypo) {
                    if (x[0] > z) {
                        x[0] = z
                    }
                    if (velocity < -2.0) {
                        x[0] += (velocity * 2.0)
                    }
                    aapsLogger.debug(LTag.GLUCOSE, "HybridSmoothing: KINETIC HYPO DETECTED! Vel=$velocity, Pred20=$predictedBg20min. Forcing low.")
                } else if (isHypoCritical && x[0] > z + 5.0) {
                    x[0] = (x[0] + z) / 2.0
                }

                data[i].smoothed = x[0]
            }

            val revIdx = i - newestIdx
            gNewestFirst[revIdx] = data[i].smoothed ?: x[0]
            ratesNewestFirst[revIdx] = x[1]

            data[i].trendArrow = computeTrendArrow(x[1])
        }

        if (segmentSize >= RTS_MIN_SEGMENT_POINTS && forwardStates.size == segmentSize) {
            val gRtsNewestFirst = runRtsGlucoseNewestFirst(gNewestFirst, ratesNewestFirst, forwardStates)
            for (step in gRtsNewestFirst.indices) {
                val idx = newestIdx + step
                data[idx].smoothed = gRtsNewestFirst[step].coerceAtLeast(MIN_VALID_BG)
            }
        }

        learnedR = measurementNoiseR
        return Triple(processedPoints, compressionPoints, outlierPoints)
    }

    /**
     * RTS backward pass on glucose only (Tsunami-style), [gNewestFirst]/[ratesNewestFirst] indexed newest→oldest step;
     * [forwardStates] same order ([0] = newest step's pre-update snapshot).
     */
    private fun runRtsGlucoseNewestFirst(
        gNewestFirst: DoubleArray,
        ratesNewestFirst: DoubleArray,
        forwardStates: ArrayDeque<FilterState>,
    ): DoubleArray {
        val n = gNewestFirst.size
        if (n < RTS_MIN_SEGMENT_POINTS || forwardStates.size < n) return gNewestFirst.copyOf()
        val smoothed = gNewestFirst.copyOf()
        val states = forwardStates.toList()
        val maxSmoothSteps = min(n - 1, states.size - 1)
        if (maxSmoothSteps < 1) return smoothed

        val xSmooth = doubleArrayOf(gNewestFirst[0], ratesNewestFirst[0])
        for (step in 1..maxSmoothSteps) {
            val state = states[step - 1]
            val c = computeRtsSmootherGain(state.P, state.PPred, state.dt)
            val dx0 = xSmooth[0] - state.xPred[0]
            val dx1 = xSmooth[1] - state.xPred[1]
            xSmooth[0] = gNewestFirst[step] + c[0] * dx0 + c[1] * dx1
            xSmooth[1] = state.x[1] + c[2] * dx0 + c[3] * dx1
            smoothed[step] = xSmooth[0]
        }
        return smoothed
    }

    /** C = P · Fᵀ · P_pred⁻¹ with F = [[1, dt],[0, exp(-dt/τ)]]. */
    private fun computeRtsSmootherGain(p: DoubleArray, pPred: DoubleArray, dt: Double): DoubleArray {
        val damp = rateDecayMinorGap(dt)
        val pFt00 = p[0] + p[1] * dt
        val pFt01 = p[1] * damp
        val pFt10 = p[2] + p[3] * dt
        val pFt11 = p[3] * damp
        val det = pPred[0] * pPred[3] - pPred[1] * pPred[2]
        if (abs(det) < 1e-10) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val inv00 = pPred[3] / det
        val inv01 = -pPred[1] / det
        val inv10 = -pPred[2] / det
        val inv11 = pPred[0] / det
        return doubleArrayOf(
            pFt00 * inv00 + pFt01 * inv10,
            pFt00 * inv01 + pFt01 * inv11,
            pFt10 * inv00 + pFt11 * inv10,
            pFt10 * inv01 + pFt11 * inv11,
        )
    }

    private fun refreshSnapshotAfterShortOrRawPass() {
        val now = nowMillis()
        val tier = lastAdaptiveSmoothingQualityTier ?: AdaptiveSmoothingQualityTier.OK
        lastQualitySnapshot = AdaptiveSmoothingQualitySnapshot(
            tier = tier,
            learnedR = learnedR,
            outlierRate = 0.0,
            compressionRate = 0.0,
            updatedAtMillis = now
        )
    }

    private fun isOutlier(innovation: Double, innovationVariance: Double): Boolean {
        val mahalanobisSq = (innovation * innovation) / innovationVariance
        return mahalanobisSq > CHI_SQUARED_THRESHOLD || abs(innovation) > OUTLIER_ABSOLUTE
    }

    // ============================================================
    // HEURISTIC SAFETY LOGIC
    // ============================================================

    private fun calculateGlycemicContext(data: List<InMemoryGlucoseValue>, index: Int, cachedIobTotalU: Double): GlycemicContext {
        // 'data' is Newest...Oldest, so relative to 'i' the older points are i+1, i+2.

        val valCur = data[index].calibratedOrValue
        val valOld1 = if (index + 1 < data.size) data[index + 1].calibratedOrValue else valCur

        // Heuristic Delta (Raw)
        val rawDelta = valCur - valOld1

        // Spacing to the previous (older) sample, used to normalize the fall to a 5-min equivalent (xDrip / NL safe).
        val dtMin = if (index + 1 < data.size)
            ((data[index].timestamp - data[index + 1].timestamp) / MILLIS_PER_MINUTE)
                .coerceIn(COMPRESSION_DT_MIN_MINUTES, COMPRESSION_DT_MAX_MINUTES)
        else DEFAULT_ASSUMED_SAMPLE_MINUTES

        // IOB Safety (current IOB; same for all points in this pass)
        val iob = cachedIobTotalU

        // Night
        val hour = Instant.fromEpochMilliseconds(data[index].timestamp)
            .toLocalDateTime(TimeZone.currentSystemDefault()).hour
        val isNight = hour !in 7..<23

        val zone = when {
            valCur < 70  -> GlycemicZone.HYPO
            valCur < 90  -> GlycemicZone.LOW_NORMAL
            valCur < 180 -> GlycemicZone.TARGET
            else         -> GlycemicZone.HYPER
        }

        return GlycemicContext(
            cv = 0.0, // Simplified for realtime check
            zone = zone,
            currentBg = valCur,
            iob = iob,
            isNight = isNight,
            rawDelta = rawDelta,
            dtMin = dtMin,
            isfMgdlPerU = passIsfMgdlPerU
        )
    }

    private fun isCompressionArtifactCandidate(ctx: GlycemicContext): Boolean {
        // Patient-relative compression detection. A pressure artifact is a fall STEEPER than the patient's insulin
        // plus metabolism can physiologically cause — which scales with the person (a high-ISF child can fall fast on
        // little insulin; a fixed "IOB < 3 U" gate would wrongly dismiss that real fall). Fail-safe by design: the
        // dangerous smoother error is masking a REAL fall (loop then sees BG too high → over-doses → deeper hypo), so
        // when in doubt we do NOT declare compression. With no reliable ISF we never block (respect the fall).
        val isf = ctx.isfMgdlPerU ?: return false
        if (isf <= 0.0) return false

        // Normalize the observed fall to a 5-minute equivalent so irregular feeds (xDrip / NL) are not mis-flagged.
        val dtMin = ctx.dtMin.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_ASSUMED_SAMPLE_MINUTES
        val fallPer5 = -ctx.rawDelta * (5.0 / dtMin) // positive when BG is falling
        if (fallPer5 <= 0.0) return false

        // 1. Steepness gate (kept): only steep falls are candidates.
        val steepFloor5 = if (ctx.isNight) COMPRESSION_STEEP_FLOOR_NIGHT_5MIN else COMPRESSION_STEEP_FLOOR_DAY_5MIN
        if (fallPer5 < steepFloor5) return false

        // 2. Feasibility gate (new): flag only when the fall exceeds what active insulin + metabolism can explain.
        //    insulin term = IOB × ISF × (fraction of IOB acting per 5 min); generous fraction → blocks LESS → safer.
        //    metabolic floor = non-insulin fall that is physiologically possible (exercise, GNG suppression) so a real
        //    effort-driven fall is never taken for compression.
        val insulinPlausibleDrop5 = ctx.iob.coerceAtLeast(0.0) * isf * COMPRESSION_INSULIN_ACTIVE_FRACTION_5MIN
        val plausibleDrop5 = COMPRESSION_METABOLIC_DROP_FLOOR_5MIN + insulinPlausibleDrop5
        return fallPer5 > COMPRESSION_FEASIBILITY_MARGIN * plausibleDrop5
    }

    // ============================================================
    // UKF MATHEMATICS (Unscented Transform)
    // ============================================================

    private fun predict(x: DoubleArray, covariance: DoubleArray, q: DoubleArray, dt: Double): Pair<DoubleArray, DoubleArray> {
        // Generate Sigma Points
        val sigmaPoints = generateSigmaPoints(x, covariance)
        val sigmaPointsPred = Array(2 * n + 1) { DoubleArray(n) }

        // Propagate (Model: G + G_dot*dt)
        for (i in 0 until 2 * n + 1) {
            sigmaPointsPred[i][0] = sigmaPoints[i][0] + sigmaPoints[i][1] * dt
            sigmaPointsPred[i][1] = sigmaPoints[i][1] * rateDamping
        }

        // Recombine Mean
        val xPred = DoubleArray(n)
        for (i in 0 until 2 * n + 1) {
            xPred[0] += wm[i] * sigmaPointsPred[i][0]
            xPred[1] += wm[i] * sigmaPointsPred[i][1]
        }

        // Recombine Covariance
        val predictedCovarianceMatrix = DoubleArray(4)
        for (i in 0 until 2 * n + 1) {
            val dx0 = sigmaPointsPred[i][0] - xPred[0]
            val dx1 = sigmaPointsPred[i][1] - xPred[1]
            predictedCovarianceMatrix[0] += wc[i] * dx0 * dx0
            predictedCovarianceMatrix[1] += wc[i] * dx0 * dx1
            predictedCovarianceMatrix[2] += wc[i] * dx1 * dx0
            predictedCovarianceMatrix[3] += wc[i] * dx1 * dx1
        }

        // Add Process Noise (Scaled by time)
        val qScale = dt / 5.0
        predictedCovarianceMatrix[0] += q[0] * qScale
        predictedCovarianceMatrix[3] += q[3] * qScale

        predictedCovarianceMatrix[0] = max(predictedCovarianceMatrix[0], 0.1)
        predictedCovarianceMatrix[3] = max(predictedCovarianceMatrix[3], 0.001)

        return Pair(xPred, predictedCovarianceMatrix)
    }

    private fun update(
        xPred: DoubleArray,
        predictedCovariance: DoubleArray,
        z: Double,
        measurementNoiseVariance: Double,
        x: DoubleArray,
        covariance: DoubleArray,
    ) {
        val sigmaPoints = generateSigmaPoints(xPred, predictedCovariance)
        val zSigma = DoubleArray(2 * n + 1)

        // Measurement Model h(x) = x[0] (Glucose)
        for (i in 0 until 2 * n + 1) zSigma[i] = sigmaPoints[i][0]

        // Predicted Measurement Mean
        var zPred = 0.0
        for (i in 0 until 2 * n + 1) zPred += wm[i] * zSigma[i]

        // Measurement Variance
        var innovationVariance = 0.0
        for (i in 0 until 2 * n + 1) {
            val dz = zSigma[i] - zPred
            innovationVariance += wc[i] * dz * dz
        }
        innovationVariance += measurementNoiseVariance
        val innovationVarianceSafe = max(innovationVariance, 1e-6)

        // Cross covariance (state × measurement)
        val crossCovariance = DoubleArray(n)
        for (i in 0 until 2 * n + 1) {
            val dx0 = sigmaPoints[i][0] - xPred[0]
            val dx1 = sigmaPoints[i][1] - xPred[1]
            val dz = zSigma[i] - zPred
            crossCovariance[0] += wc[i] * dx0 * dz
            crossCovariance[1] += wc[i] * dx1 * dz
        }

        // Kalman Gain
        val k = DoubleArray(n)
        k[0] = crossCovariance[0] / innovationVarianceSafe
        k[1] = crossCovariance[1] / innovationVarianceSafe

        // Update State
        val innovation = z - zPred
        x[0] = xPred[0] + k[0] * innovation
        x[1] = xPred[1] + k[1] * innovation

        x[1] = x[1].coerceIn(-5.0, 5.0) // Clamp rate physics

        // Update Covariance
        covariance[0] = predictedCovariance[0] - k[0] * innovationVarianceSafe * k[0]
        covariance[1] = predictedCovariance[1] - k[0] * innovationVarianceSafe * k[1]
        covariance[2] = predictedCovariance[2] - k[1] * innovationVarianceSafe * k[0]
        covariance[3] = predictedCovariance[3] - k[1] * innovationVarianceSafe * k[1]

        covariance[0] = max(covariance[0], 0.1)
        covariance[3] = max(covariance[3], 0.001)
    }

    private fun generateSigmaPoints(x: DoubleArray, covariance: DoubleArray): Array<DoubleArray> {
        val sigmaPoints = Array(2 * n + 1) { DoubleArray(n) }
        val sqrtP = matrixSqrt2x2(covariance)

        sigmaPoints[0][0] = x[0]; sigmaPoints[0][1] = x[1]

        for (i in 0 until n) {
            sigmaPoints[i + 1][0] = x[0] + gamma * sqrtP[i * 2 + 0]
            sigmaPoints[i + 1][1] = x[1] + gamma * sqrtP[i * 2 + 1]
            sigmaPoints[i + 1 + n][0] = x[0] - gamma * sqrtP[i * 2 + 0]
            sigmaPoints[i + 1 + n][1] = x[1] - gamma * sqrtP[i * 2 + 1]
        }
        return sigmaPoints
    }

    private fun matrixSqrt2x2(covariance: DoubleArray): DoubleArray {
        val a = covariance[0]
        val b = (covariance[1] + covariance[2]) / 2.0
        val d = covariance[3]

        val l11 = sqrt(max(a, 1e-9))
        val l21 = b / l11
        val discriminant = d - l21 * l21

        val l22 = if (discriminant < 0) sqrt(max(d, 1e-9)) else sqrt(discriminant)

        return doubleArrayOf(l11, l21, 0.0, l22)
    }

    // ============================================================
    // ADAPTATION AND UTILS
    // ============================================================

    private fun adaptMeasurementNoise(currentR: Double, innovations: ArrayDeque<Double>, rawInnovationsSquared: ArrayDeque<Double>): Double {
        if (innovations.size < 8) return currentR
        val avgInnovSq = med(innovations)

        // Stability Clamp
        if (innovations.any { it > 9.0 }) return currentR.coerceIn(rMin, rMax)

        var newR = currentR
        if (avgInnovSq >= 1.1 || avgInnovSq <= 0.9) {
            newR = currentR + 0.06 * (med(rawInnovationsSquared) - currentR)
        }
        return newR.coerceIn(rMin, rMax)
    }

    private fun med(list: Collection<Double>): Double {
        val sorted = list.sorted()
        return if (sorted.size % 2 == 0) (sorted[sorted.size / 2] + sorted[(sorted.size - 1) / 2]) / 2.0 else sorted[sorted.size / 2]
    }

    private fun trackInnovation(innovation: Double, innovationVariance: Double) {
        val normalizedSq = (innovation * innovation) / innovationVariance
        val rawSq = innovation * innovation
        innovations.addFirst(normalizedSq)
        rawInnovationVariance.addFirst(rawSq)
        if (innovations.size > innovationWindow) innovations.removeLast()
        if (rawInnovationVariance.size > innovationWindow) rawInnovationVariance.removeLast()
    }

    private fun computeTrendArrow(rate: Double): TrendArrow {
        return when {
            rate > 2.0  -> TrendArrow.DOUBLE_UP
            rate > 1.0  -> TrendArrow.SINGLE_UP
            rate > 0.5  -> TrendArrow.FORTY_FIVE_UP
            rate < -2.0 -> TrendArrow.DOUBLE_DOWN
            rate < -1.0 -> TrendArrow.SINGLE_DOWN
            rate < -0.5 -> TrendArrow.FORTY_FIVE_DOWN
            else        -> TrendArrow.FLAT
        }
    }

    private fun copyRawToSmoothed(data: MutableList<InMemoryGlucoseValue>) {
        data.forEach {
            it.smoothed = it.calibratedOrValue
            it.trendArrow = TrendArrow.NONE
        }
    }

    private fun sanitizeOutput(data: MutableList<InMemoryGlucoseValue>) {
        data.forEach { gv ->
            val smoothed = gv.smoothed
            if (smoothed == null || !smoothed.isFinite()) {
                gv.smoothed = gv.calibratedOrValue.coerceIn(MIN_VALID_BG, MAX_VALID_BG)
                gv.trendArrow = TrendArrow.FLAT
                return@forEach
            }
            gv.smoothed = smoothed.coerceIn(MIN_VALID_BG, MAX_VALID_BG)
        }
    }

    // ============================================================
    // PERSISTENCE & SENSOR MANAGEMENT
    // ============================================================
    // Simplified for robustness

    private fun loadPersistedParameters() {
        try {
            learnedR = preferences.get(UkfDoubleNonKey.LearnedR)
            lastProcessedTimestamp = preferences.get(UkfLongNonKey.LastProcessedTimestamp)
            lastSensorChangeTimestamp = preferences.get(UkfLongNonKey.LastSensorChangeTimestamp)
        } catch (_: Exception) {
            learnedR = UkfDoubleNonKey.LearnedR.defaultValue
        }
    }

    private fun savePersistedParameters() {
        try {
            preferences.put(UkfDoubleNonKey.LearnedR, learnedR)
            preferences.put(UkfLongNonKey.LastProcessedTimestamp, lastProcessedTimestamp)
            preferences.put(UkfLongNonKey.LastSensorChangeTimestamp, lastSensorChangeTimestamp)
        } catch (_: Exception) {
        }
    }

    private fun shouldResetLearning(currentTimestamp: Long): Boolean {
        if (resetRequested.exchange(false)) return true
        if (lastProcessedTimestamp == 0L) return true
        val diffMinutes = (currentTimestamp - lastProcessedTimestamp) / 60000.0
        if (diffMinutes < 0) return false
        if (diffMinutes > 1440) return true
        return false
    }

    private fun resetLearning() {
        learnedR = UkfDoubleNonKey.LearnedR.defaultValue
        innovations.clear()
        rawInnovationVariance.clear()
        lastAdaptiveSmoothingQualityTier = null
        lastAdaptiveSmoothingQualityEventAt = 0L
        lastQualitySnapshot = null
        aapsLogger.info(LTag.GLUCOSE, "HybridSmoothing: Learning Reset. R=$learnedR")
        savePersistedParameters()
    }

    private suspend fun loadInitialSensorChangeFromDb() {
        val events = persistenceLayer.getTherapyEventDataFromTime(nowMillis() - 30L * 24 * 3600 * 1000, false)
        val latest = events.asSequence().filter { it.type == TE.Type.SENSOR_CHANGE }.maxByOrNull { it.timestamp }
        if (latest != null && latest.timestamp > lastSensorChangeTimestamp) {
            lastSensorChangeTimestamp = latest.timestamp
            resetRequested.store(true)
        }
    }

    private fun checkForSensorChange() {
        sensorBackfillJob?.cancel()
        sensorBackfillJob = smoothingScope.launch {
            if (!config.awaitInitialized(30_000L)) return@launch
            runCatching { loadInitialSensorChangeFromDb() }
                .onFailure { t -> aapsLogger.error(LTag.GLUCOSE, "AdaptiveSmoothing: loadLastSensorChange error", t) }
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {

        const val SENSOR_CHANGE_DEBOUNCE_SECONDS: Long = 10L
        const val MIN_VALID_BG: Double = 39.0
        const val MAX_VALID_BG: Double = 500.0

        private const val MILLIS_PER_MINUTE = 60_000.0

        /** Gaps longer than this start a new UKF segment (xDrip / NL safe default, aligned with Tsunami-style splits). */
        private const val MAJOR_GAP_MINUTES = 60.0

        /** Default minor gap (minutes) when median spacing cannot be estimated. */
        private const val MINOR_GAP_DEFAULT_MINUTES = 12.0

        /** Default invalid spacing when no median (minutes). */
        private const val INVALID_SPACING_DEFAULT_MINUTES = 2.0

        private const val MEDIAN_DT_MAX_PAIRS = 48
        private const val MEDIAN_TO_MINOR_FACTOR = 2.0
        private const val MEDIAN_TO_INVALID_FACTOR = 0.35
        private const val MINOR_GAP_FLOOR_MINUTES = 10.0
        private const val MINOR_GAP_CEILING_MINUTES = 22.0
        private const val INVALID_SPACING_FLOOR_MINUTES = 1.2
        private const val INVALID_SPACING_CEILING_MINUTES = 3.0

        private const val PREDICT_DT_SCALE_VS_MINOR = 1.65
        private const val PREDICT_DT_SOFT_FLOOR_MINUTES = 12.0
        private const val PREDICT_DT_HARD_CAP_MINUTES = 24.0

        /** Minimum segment length to run RTS backward smoothing. */
        private const val RTS_MIN_SEGMENT_POINTS = 3

        /** LO / error row (mg/dL) — forces a segment split like Tsunami. */
        private const val LO_BG_VALUE_STEPS = 38.0

        private const val DEFAULT_ASSUMED_SAMPLE_MINUTES = 5.0
        private const val MIN_DT_MINUTES = 1.0

        private const val RATE_DECAY_TIME_CONSTANT_MINUTES = 30.0

        /** After a major gap, blend at most this weight toward default R over [R_BLEND_SLOPE_MINUTES] beyond major. */
        private const val R_BLEND_MAX_WEIGHT = 0.35
        private const val R_BLEND_SLOPE_MINUTES = 90.0

        /**
         * Patient-relative compression detection (see `isCompressionArtifactCandidate`). A pressure artifact is a
         * fall steeper than `COMPRESSION_STEEP_FLOOR_*` AND exceeding [COMPRESSION_FEASIBILITY_MARGIN] × the
         * physiologically plausible fall (metabolic floor + IOB × ISF × active fraction), all per 5 min.
         */
        private const val COMPRESSION_STEEP_FLOOR_NIGHT_5MIN = 15.0
        private const val COMPRESSION_STEEP_FLOOR_DAY_5MIN = 25.0
        private const val COMPRESSION_METABOLIC_DROP_FLOOR_5MIN = 18.0
        private const val COMPRESSION_INSULIN_ACTIVE_FRACTION_5MIN = 0.15
        private const val COMPRESSION_FEASIBILITY_MARGIN = 1.5
        private const val COMPRESSION_DT_MIN_MINUTES = 2.0
        private const val COMPRESSION_DT_MAX_MINUTES = 15.0

        /** 99.99% chi-square, 1 DoF — used only for informational outlier rate (badge / study). */
        const val CHI_SQUARED_THRESHOLD: Double = 15.13
        const val OUTLIER_ABSOLUTE: Double = 65.0
        const val QUALITY_EVENT_THROTTLE_MS: Long = 30_000L
    }
}

/**
 * Fixed decimals with a dot, for the debug log lines above.
 *
 * Replaces `String.format("%.Nf", x)`, which is JVM only. `withDecimalsHalfUp` matches what `%.Nf` did:
 * exactly N decimals, rounded half up. The separator is pinned to a dot because these strings are read
 * in logs, not shown to the user, so they must not follow the device locale.
 */
private fun Double.fmtLog(decimals: Int): String =
    NumberFormat.withDecimalsHalfUp(decimals).format(this, NumberFormatPlatform.SEPARATOR_DOT)
