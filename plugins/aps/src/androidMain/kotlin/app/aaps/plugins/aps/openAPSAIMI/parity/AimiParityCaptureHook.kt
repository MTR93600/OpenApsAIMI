package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aimicontracts.AimiAutosensSnapshot
import app.aaps.plugins.aimicontracts.AimiBgQualitySnapshot
import app.aaps.plugins.aimicontracts.AimiCapabilitySnapshot
import app.aaps.plugins.aimicontracts.AimiConfigSnapshot
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiGlucoseSnapshot
import app.aaps.plugins.aimicontracts.AimiGlucoseWarmup
import app.aaps.plugins.aimicontracts.AimiInputSnapshot
import app.aaps.plugins.aimicontracts.AimiInsulinSnapshot
import app.aaps.plugins.aimicontracts.AimiKineticsSnapshot
import app.aaps.plugins.aimicontracts.AimiMealSnapshot
import app.aaps.plugins.aimicontracts.AimiModelBundle
import app.aaps.plugins.aimicontracts.AimiPhysiologySnapshot
import app.aaps.plugins.aimicontracts.AimiProfileSnapshot
import app.aaps.plugins.aimicontracts.AimiPumpSnapshot
import app.aaps.plugins.aimicontracts.AimiTickMeta
import app.aaps.plugins.aimicontracts.AimiTickTrigger
import app.aaps.plugins.aimicontracts.TimedValue
import app.aaps.plugins.aimitestkit.AimiTickCapture
import app.aaps.plugins.aps.openAPSAIMI.toAimiTickResult
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/**
 * Captures real Android ticks for byte-for-byte parity replay.
 *
 * When [enabled], each call to [maybeCapture] builds an [AimiInputSnapshot]
 * from the raw `determine_basal` inputs (the exact inverse of
 * `DetermineBasalAimiEngine.evaluate`), converts the [RT] result to the
 * expected [AimiTickResult], and writes the JSON capture to storage.
 *
 * Disabled by default. The [enabled] check is a single volatile read:
 * zero measurable impact on the loop when off. File I/O runs on a
 * background thread so the dosing tick never blocks on capture.
 *
 * Capture is bounded by [maxTicks]: after that many captures the hook
 * stops silently. Reset via [reset] to start a new batch.
 *
 * Thread-safety: [maybeCapture] may be called from the APS loop thread.
 * All mutable state is atomic or volatile. File writes are serialized
 * on a single background thread.
 */
object AimiParityCaptureHook {

    /** Master switch. Default false: capture is opt-in. */
    @Volatile
    var enabled: Boolean = false

    /** Maximum captures before the hook stops. Default 100 ticks. */
    @Volatile
    var maxTicks: Int = 100

    private val capturedCount = AtomicInteger(0)
    private val generation = AtomicLong(0)
    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
    private val writerThread: Thread by lazy {
        Thread({
            while (true) {
                val task = writeQueue.take()
                try {
                    task()
                } catch (_: Exception) {
                    // Capture must never crash the loop. Swallow and continue.
                }
            }
        }, "AimiParityCapture").apply { isDaemon = true; start() }
    }

    /**
     * Captures one tick if enabled and under the tick limit.
     *
     * All parameters are the exact values passed to `determine_basal`.
     * Returns immediately when disabled: a single volatile read.
     */
    fun maybeCapture(
        storage: AimiStorage,
        glucoseStatusAimi: GlucoseStatusAIMI,
        currentTemp: CurrentTemp,
        iobArray: Array<IobTotal>,
        profile: OapsProfileAimi,
        autosensResult: AutosensResult,
        mealData: MealData,
        microBolusAllowed: Boolean,
        currentTime: Long,
        flatBGsDetected: Boolean,
        dynIsfMode: Boolean,
        pkpdIobDataArray: Array<IobTotal>?,
        effectiveDiaHours: Double?,
        effectivePeakMinutes: Double?,
        rt: RT,
    ) {
        // Fast path: single volatile read, no allocation.
        if (!enabled) return
        if (capturedCount.get() >= maxTicks) return

        val tickIndex = capturedCount.getAndIncrement()
        if (tickIndex >= maxTicks) return

        val gen = generation.getAndIncrement()
        val state = AimiEngineState(schemaVersion = 1, generation = gen)

        // Build the snapshot on the calling thread (cheap, no I/O),
        // then hand the serialization + file write to the background thread.
        val snapshot = buildSnapshot(
            glucoseStatusAimi = glucoseStatusAimi,
            currentTemp = currentTemp,
            iobArray = iobArray,
            profile = profile,
            autosensResult = autosensResult,
            mealData = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = currentTime,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode,
            pkpdIobDataArray = pkpdIobDataArray,
            effectiveDiaHours = effectiveDiaHours,
            effectivePeakMinutes = effectivePeakMinutes,
            tickIndex = tickIndex,
        )
        val expected = rt.toAimiTickResult(state)
        val capture = AimiTickCapture(
            input = snapshot,
            state = state,
            models = AimiModelBundle(uamSchemaId = "uam-v1", uamSha256 = null),
            expected = expected,
        )

        // Touch the lazy writer thread so it starts on first capture.
        @Suppress("UNUSED_EXPRESSION")
        writerThread
        writeQueue.offer {
            val json = capture.encodeToString()
            val path = storage.file("parity", "tick-%06d.json".format(tickIndex))
            storage.createParentDirectories(path)
            storage.writeText(path, json)
        }
    }

    /** Resets the capture counter and generation. Starts a new batch. */
    fun reset() {
        capturedCount.set(0)
        generation.set(0)
    }

    /** Number of ticks captured so far in this batch. */
    fun capturedSoFar(): Int = capturedCount.get()

    /**
     * Builds the input snapshot from raw determine_basal parameters.
     *
     * This is the exact inverse of `DetermineBasalAimiEngine.evaluate`:
     * replaying the snapshot through the adapter must reconstruct
     * identical raw parameters.
     */
    private fun buildSnapshot(
        glucoseStatusAimi: GlucoseStatusAIMI,
        currentTemp: CurrentTemp,
        iobArray: Array<IobTotal>,
        profile: OapsProfileAimi,
        autosensResult: AutosensResult,
        mealData: MealData,
        microBolusAllowed: Boolean,
        currentTime: Long,
        flatBGsDetected: Boolean,
        dynIsfMode: Boolean,
        pkpdIobDataArray: Array<IobTotal>?,
        effectiveDiaHours: Double?,
        effectivePeakMinutes: Double?,
        tickIndex: Int,
    ): AimiInputSnapshot {
        // Adapter reads: input.glucose.glucoseMgdl, delta, shortAvgDelta,
        // longAvgDelta, noise, and meta.wallClockEpochMs for the date.
        val glucose = AimiGlucoseSnapshot(
            glucoseMgdl = TimedValue.Fresh(glucoseStatusAimi.glucose, currentTime, 0),
            sourceId = glucoseStatusAimi.sourceSensor?.text,
            warmup = AimiGlucoseWarmup.None,
            loopEligible = true,
            delta = TimedValue.Fresh(glucoseStatusAimi.delta, currentTime, 0),
            shortAvgDelta = TimedValue.Fresh(glucoseStatusAimi.shortAvgDelta, currentTime, 0),
            longAvgDelta = TimedValue.Fresh(glucoseStatusAimi.longAvgDelta, currentTime, 0),
            noise = TimedValue.Fresh(glucoseStatusAimi.noise, currentTime, 0),
        )
        // Adapter reads: tempBasalUPerHour, tempBasalRemainingMs, pumpCanSmb.
        // Duration is minutes in CurrentTemp; snapshot carries ms.
        val pump = AimiPumpSnapshot(
            profileBasalUPerHour = TimedValue.Missing("not captured"),
            tempBasalUPerHour = TimedValue.Fresh(currentTemp.rate, currentTime, 0),
            tempBasalRemainingMs = currentTemp.duration * 60_000L,
            maxBolusU = null,
            maxBasalUPerHour = null,
            pumpCanSmb = microBolusAllowed,
            pumpCanTempBasal = true,
        )
        // Adapter passes the full profile object through unchanged.
        val profileSnapshot = AimiProfileSnapshot(profile = profile)
        // Adapter reads iobHistory when non-empty, else builds a single
        // point from iobU/activity. We always have the array: pass it through.
        val lastIob = iobArray.lastOrNull()
        val insulin = AimiInsulinSnapshot(
            iobU = if (lastIob != null) TimedValue.Fresh(lastIob.iob, currentTime, 0)
            else TimedValue.Missing("empty iob array"),
            activityUPerHour = if (lastIob != null) TimedValue.Fresh(lastIob.activity, currentTime, 0)
            else TimedValue.Missing("empty iob array"),
            iobHistory = iobArray.toList(),
            pkpdIobHistory = pkpdIobDataArray?.toList() ?: emptyList(),
        )
        // Adapter reads mealCOB, slopes, lastCarbTime, lastBolusTime.
        val meal = AimiMealSnapshot(
            cobG = TimedValue.Fresh(mealData.mealCOB, currentTime, 0),
            lastCarbsG = TimedValue.Missing("not captured"),
            slopeFromMaxDeviation = TimedValue.Fresh(mealData.slopeFromMaxDeviation, currentTime, 0),
            slopeFromMinDeviation = TimedValue.Fresh(mealData.slopeFromMinDeviation, currentTime, 0),
            lastCarbTimeMs = mealData.lastCarbTime.takeIf { it != 0L },
            lastBolusTimeMs = mealData.lastBolusTime.takeIf { it != 0L },
        )
        val autosens = AimiAutosensSnapshot(
            ratio = TimedValue.Fresh(autosensResult.ratio, currentTime, 0),
        )
        val bgQuality = AimiBgQualitySnapshot(flatBGsDetected = flatBGsDetected)
        val kinetics = AimiKineticsSnapshot(
            effectiveDiaHours = effectiveDiaHours,
            effectivePeakMinutes = effectivePeakMinutes,
        )
        // Physiology, config and capabilities are not consumed by the
        // adapter's determine_basal mapping: leave them at defaults.
        return AimiInputSnapshot(
            meta = AimiTickMeta(
                schemaVersion = 1,
                tickId = tickIndex.toLong(),
                wallClockEpochMs = currentTime,
                monotonicMs = 0L,
                timezoneOffsetMinutes = 0,
                trigger = AimiTickTrigger.Cgm,
            ),
            glucose = glucose,
            pump = pump,
            profile = profileSnapshot,
            insulin = insulin,
            meal = meal,
            physiology = AimiPhysiologySnapshot(
                heartRateBpm = TimedValue.Missing("not captured"),
                steps = TimedValue.Missing("not captured"),
                hrvRmssdMs = TimedValue.Missing("not captured"),
                hrvSdnnMs = TimedValue.Missing("not captured"),
            ),
            config = AimiConfigSnapshot(schemaVersion = 1),
            capabilities = AimiCapabilitySnapshot(closedLoopAllowed = true),
            autosens = autosens,
            bgQuality = bgQuality,
            kinetics = kinetics,
            dynIsfMode = dynIsfMode,
        )
    }
}
