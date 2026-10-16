package app.aaps.plugins.aps.openAPSAIMI.parity

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTickCaptureHook
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Android half of [AimiTickCaptureHook]: hands every call to [AimiParityCaptureHook]. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiTickCaptureHook @Inject constructor() : AimiTickCaptureHook {

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
    ) = AimiParityCaptureHook.maybeCapture(
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
