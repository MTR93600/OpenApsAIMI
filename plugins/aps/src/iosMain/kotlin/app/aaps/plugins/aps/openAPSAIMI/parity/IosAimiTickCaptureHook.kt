package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTickCaptureHook
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * iOS half of [AimiTickCaptureHook]. The twin of `AndroidAimiTickCaptureHook`.
 *
 * At every tick, before it records, it copies the expert preference
 * `BooleanKey.AimiParityCaptureEnable` into the recorder switch. So the switch works from the next
 * tick, with no restart, and it is the same preference as on Android. Default is off.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosAimiTickCaptureHook @Inject constructor(
    private val preferences: Preferences,
) : AimiTickCaptureHook {

    private val recorder = IosAimiParityRecorder(CoroutineScope(SupervisorJob() + aapsIoDispatcher))

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
        recorder.enabled = preferences.get(BooleanKey.AimiParityCaptureEnable)
        recorder.maybeCapture(
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
