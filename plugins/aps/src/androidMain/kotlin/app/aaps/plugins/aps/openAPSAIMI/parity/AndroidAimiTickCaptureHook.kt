package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTickCaptureHook
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android half of [AimiTickCaptureHook]: hands every call to [AimiParityCaptureHook].
 *
 * This class is the only place that writes `AimiParityCaptureHook.enabled`. At every tick, before it
 * hands the call on, it copies the expert preference `BooleanKey.AimiParityCaptureEnable` into that
 * switch. So a change in the settings works from the next tick, with no restart. It is read here
 * because this class already gets a call once per tick and can be injected with `Preferences`, which
 * the `AimiParityCaptureHook` object cannot. The preference decides: a value set by hand on the
 * object is overwritten at the next tick.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiTickCaptureHook @Inject constructor(
    private val preferences: Preferences,
) : AimiTickCaptureHook {

    /** Copies the preference into the recorder switch. Called at the start of every tick. */
    internal fun followPreference() {
        AimiParityCaptureHook.enabled = preferences.get(BooleanKey.AimiParityCaptureEnable)
    }

    override fun maybeCapture(
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
        followPreference()
        AimiParityCaptureHook.maybeCapture(
            storage = storage,
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
        )
    }
}
