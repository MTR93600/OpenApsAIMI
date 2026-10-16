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
 * This is a developer tool and it is off by default. It only exists on Android, because the
 * recorder is built on JVM classes. The plugin takes this port as an optional parameter: where no
 * platform provides one, the plugin does not record. That is the honest answer on iOS, where the
 * tool does not exist, so nothing pretends to record.
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
