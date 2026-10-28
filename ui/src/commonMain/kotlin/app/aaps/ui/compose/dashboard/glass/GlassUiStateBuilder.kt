package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.ui.compose.overview.statusLights.StatusUiState

private const val NO_VALUE = "--"

/**
 * Builds the state of the Glass status card from the shared overview data.
 *
 * On `dev_OAPSAIMI` everything came from one `StatusCardState`. Here each part has its own source:
 *
 * - [bgInfo] (glucose, range, trend, age) from `GraphViewModel.bgInfoState`
 * - [timeAgo], [deltaText], [iobText], [unitText] are already formatted by the caller
 * - [lights] (reservoir, cannula, battery, sensor ages) from `StatusViewModel.uiState`
 * - [homeStatus] (target, basal, loop, steps, heart rate, time in range) from [GlassHomeViewModel]
 * - [treatmentData] (last bolus, last carbs) from `GraphViewModel.treatmentGraphFlow`
 * - [warmup] from the active BG source when it is a `CgmWarmupProvider`
 *
 * A missing source leaves the default of that field ("--" or the default label).
 */
internal fun buildGlassUiState(
    bgInfo: BgInfoData?,
    timeAgo: String,
    deltaText: String,
    unitText: String,
    iobText: String?,
    lights: StatusUiState?,
    homeStatus: GlassHomeStatus?,
    treatmentData: TreatmentGraphData?,
    warmup: CgmWarmupStatus?,
    nowMs: Long,
): GlassUiState {
    val defaults = GlassUiState()
    return GlassUiState(
        currentBg = bgInfo?.bgText ?: defaults.currentBg,
        unit = unitText,
        glucoseRange = bgInfo?.bgRange,
        deltaText = deltaText,
        trendArrow = bgInfo?.trendArrow,
        timeAgo = timeAgo.ifEmpty { defaults.timeAgo },
        // The age is always the short form ("3d4h") since upstream 78c1707f665, so it fits the 100 dp pills.
        insulinAge = lights?.insulinStatus?.age ?: NO_VALUE,
        insulinAgeStatus = lights?.insulinStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        insulinLabel = lights?.insulinStatus?.label,
        cannulaAge = lights?.cannulaStatus?.age ?: NO_VALUE,
        cannulaAgeStatus = lights?.cannulaStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        cannulaLabel = lights?.cannulaStatus?.label,
        batteryAge = lights?.batteryStatus?.age ?: NO_VALUE,
        batteryAgeStatus = lights?.batteryStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        batteryLabel = lights?.batteryStatus?.label,
        sensorAge = lights?.sensorStatus?.age ?: NO_VALUE,
        sensorAgeStatus = lights?.sensorStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        sensorLabel = lights?.sensorStatus?.label,
        loopStatusText = homeStatus?.loopStatusText,
        loopIsRunning = homeStatus?.loopIsRunning ?: defaults.loopIsRunning,
        iobText = iobText ?: NO_VALUE,
        isTempTargetActive = homeStatus?.isTempTargetActive ?: false,
        targetText = homeStatus?.targetText ?: NO_VALUE,
        basalPercentText = homeStatus?.effectiveBasalText ?: NO_VALUE,
        stepsText = homeStatus?.stepsText ?: NO_VALUE,
        hrText = homeStatus?.hrText ?: NO_VALUE,
        lastBolusText = treatmentData?.boluses
            ?.filter { it.isValid && it.timestamp <= nowMs }
            ?.maxByOrNull { it.timestamp }?.label ?: NO_VALUE,
        lastCarbsText = treatmentData?.carbs
            ?.filter { it.isValid && it.timestamp <= nowMs }
            ?.maxByOrNull { it.timestamp }?.label ?: NO_VALUE,
        tir = homeStatus?.tir,
        warmup = warmup,
        isGlucoseActual = bgInfo?.isOutdated?.not() ?: defaults.isGlucoseActual,
        glucoseTimestampMs = bgInfo?.timestamp,
    )
}
