package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The iOS twin of Android's `AimiParityCaptureHook`.
 *
 * Same rules: off unless [enabled], at most [maxTicks] captures per batch, the capture is built on
 * the calling thread and written on a background coroutine, so the dosing tick never waits for
 * file I/O. The capture itself comes from `AimiParityCaptureBuilder`, the same code Android uses,
 * and the file goes to `parity/tick-NNNNNN.json`, so an iPhone trace and an Android trace can be
 * compared file by file.
 *
 * On Kotlin/Native there is no daemon thread and no blocking queue. One coroutine on [scope]
 * reads write jobs from a channel, one at a time, so files are written in tick order.
 */
internal class IosAimiParityRecorder(
    private val scope: CoroutineScope,
    @Volatile var maxTicks: Int = DEFAULT_MAX_TICKS,
) {

    /** Master switch. The caller copies the preference into it at every tick. */
    @Volatile
    var enabled: Boolean = false

    private val capturedCount = AtomicInt(0)
    private val generation = AtomicLong(0)
    private val writeQueue = Channel<() -> Unit>(Channel.UNLIMITED)
    private val writerStarted = AtomicInt(0)

    private fun startWriterOnce() {
        if (!writerStarted.compareAndSet(0, 1)) return
        scope.launch {
            for (job in writeQueue) {
                try {
                    job()
                } catch (_: Exception) {
                    // Capture must never crash the loop. Skip this file and go on.
                }
            }
        }
    }

    /** Captures one tick if [enabled] and under [maxTicks]. Returns at once when off. */
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
        if (!enabled) return
        if (capturedCount.value >= maxTicks) return
        val tickIndex = capturedCount.getAndIncrement()
        if (tickIndex >= maxTicks) return

        val capture = AimiParityCaptureBuilder.buildCapture(
            generation = generation.getAndIncrement(),
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
        startWriterOnce()
        writeQueue.trySend {
            val json = capture.encodeToString()
            val path = storage.file(AimiParityCaptureBuilder.DIRECTORY, AimiParityCaptureBuilder.fileName(tickIndex))
            storage.createParentDirectories(path)
            storage.writeText(path, json)
        }
    }

    /** Resets the counter and generation. Starts a new batch. */
    fun reset() {
        capturedCount.value = 0
        generation.value = 0
    }

    /** Number of ticks captured so far in this batch. */
    fun capturedSoFar(): Int = capturedCount.value

    /** Suspends until every write queued before this call is done. Used by tests. */
    suspend fun flush() {
        startWriterOnce()
        val done = CompletableDeferred<Unit>()
        writeQueue.trySend { done.complete(Unit) }
        done.await()
    }

    companion object {

        /** Same default as Android: 100 ticks. */
        const val DEFAULT_MAX_TICKS: Int = 100
    }
}
