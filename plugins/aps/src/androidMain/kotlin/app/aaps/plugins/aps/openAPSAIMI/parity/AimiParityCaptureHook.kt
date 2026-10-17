package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/**
 * Captures real Android ticks for byte-for-byte parity replay.
 *
 * When [enabled], each call to [maybeCapture] builds an `AimiInputSnapshot`
 * from the raw `determine_basal` inputs (the exact inverse of
 * `DetermineBasalAimiEngine.evaluate`), converts the [RT] result to the
 * expected `AimiTickResult`, and writes the JSON capture to storage.
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

    /**
     * Master switch. Default false: capture is opt-in.
     *
     * Do not set it by hand. `AndroidAimiTickCaptureHook` writes it at every tick from the expert
     * preference `BooleanKey.AimiParityCaptureEnable`, so the preference decides.
     */
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

        // Build the capture on the calling thread (cheap, no I/O), then hand the
        // serialization + file write to the background thread. The shared builder is also
        // used by the iOS recorder, so both phones write the same shape.
        val capture = AimiParityCaptureBuilder.buildCapture(
            generation = gen,
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
            rt = rt,
            tickIndex = tickIndex,
        )

        // Touch the lazy writer thread so it starts on first capture.
        @Suppress("UNUSED_EXPRESSION")
        writerThread
        writeQueue.offer {
            val json = capture.encodeToString()
            val path = storage.file(AimiParityCaptureBuilder.DIRECTORY, AimiParityCaptureBuilder.fileName(tickIndex))
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
}
