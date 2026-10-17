package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Records one real AIMI tick so it can be replayed later and compared (the parity check).
 *
 * This is a developer tool and it is off by default on both phones. The switch is the preference
 * `BooleanKey.AimiParityCaptureEnable`, read again on every tick, so turning it on or off takes
 * effect at the next tick with no restart.
 *
 * Android records through `AimiParityCaptureHook`, iOS through `IosAimiParityRecorder`. Both build
 * the capture with the same shared `AimiParityCaptureBuilder` and write the same file names into
 * the same `parity/` folder, which is the point: a trace from one phone is meant to be compared
 * with a trace from the other. Only the background writer differs, because a daemon thread and a
 * blocking queue have no Kotlin/Native equivalent.
 *
 * The parameter is required, not optional. It was nullable with a default at first, and Metro then
 * took the default even on Android where a binding existed, so the recorder quietly stopped. A
 * platform that cannot record keeps the whole plugin out of its graph instead, which fails loudly.
 */
interface AimiTickCaptureHook {

    /**
     * Records the tick if recording is switched on and under its limit. All parameters are the
     * exact values that were passed to `determine_basal`, and [rt] is its result.
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
    )
}
