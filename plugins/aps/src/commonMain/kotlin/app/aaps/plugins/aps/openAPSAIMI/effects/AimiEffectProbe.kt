package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Test-only trace of the shell. Production leaves [lines] unset, and then
 * [captureSetTbr] returns false so [setTempBasal] runs exactly as before.
 *
 * While a test is capturing, the requested temporary basal is recorded and the pump
 * body is skipped. The golden line is the decision's request, which is what the
 * shell will keep passing into `setTempBasal` after the decision moves to common code.
 *
 * Portable note: the Android version held [lines] in a `ThreadLocal`. Common code
 * has no thread-local storage, so this uses an atomic holder instead. Capture is a
 * test-only, single-threaded activity; production never sets [lines].
 */
@OptIn(ExperimentalAtomicApi::class)
internal object AimiEffectProbe {

    /** Minimal thread-local-like holder backed by an atomic reference. */
    class Box<T> {
        private val ref = AtomicReference<T?>(null)
        fun get(): T? = ref.load()
        fun set(value: T?) = ref.store(value)
        fun remove() = ref.store(null)
    }

    val lines = Box<MutableList<String>?>()

    fun add(line: String) {
        lines.get()?.add(line)
    }

    fun isCapturing(): Boolean = lines.get() != null

    fun captureSetTbr(
        rateUph: Double,
        durationMin: Int,
        overrideSafetyLimits: Boolean,
        forceExact: Boolean,
        adaptiveMultiplier: Double,
    ): Boolean {
        if (!isCapturing()) return false
        add(
            AimiTickEffect.SetTbr(
                rateUph = rateUph,
                durationMin = durationMin,
                overrideSafetyLimits = overrideSafetyLimits,
                forceExact = forceExact,
                adaptiveMultiplier = adaptiveMultiplier,
            ).encode(),
        )
        return true
    }

    fun noteSmb(units: Double, owner: String) {
        if (!isCapturing()) return
        add(AimiTickEffect.Smb(units = units, owner = owner).encode())
    }
}
