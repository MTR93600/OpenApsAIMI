package app.aaps.ui.widget.state

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.IsfStep
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.objects.extensions.apsAdjustedTargetMgdl
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.leftToRightText
import app.aaps.core.ui.extensions.directionToDescription
import app.aaps.core.ui.extensions.displayText
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.Inject
import kotlin.math.abs

/**
 * Builds the [WidgetState] from the app state. The rule the Android widgets used, moved here from
 * the Android `WidgetStateLoader`, so that iOS shows the same thing. Two small differences, both
 * only when something changes while the state is built:
 * - The age comes from the very reading that is shown (one `lastBg()` read), not from a second read
 *   in `LastBgData.isActualBg()`. Before, a new reading landing in between could leave an old value
 *   not struck through.
 * - The profile is read once and used for the target, the "modified" mark and the temp basal state.
 *   Before, it was read three times.
 */
@Inject
class WidgetStateBuilder(
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val rh: TextResolver,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val dateUtil: DateUtil,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val persistenceLayer: PersistenceLayer,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val sensitivityOverview: SensitivityOverview
) {

    /**
     * @param withChart also fill [WidgetState.chartPoints]. The Android widgets do not draw them, so
     * they leave it off and do not pay for the copy of the reading table.
     */
    suspend fun build(withChart: Boolean = false): WidgetState {
        val unavailable = rh.gs(CoreUiStrings.value_unavailable_short)

        // Read the reading once: its value, its time and its age must belong together.
        val lastBg = lastBgData.lastBg()
        val bgText = lastBg?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: unavailable
        // The range still comes from LastBgData, which may already see a newer reading. That is only
        // a colour; the age below is what keeps an old value from looking fresh.
        val bgRange = WidgetStateRules.bgRange(lastBgData.isLow(), lastBgData.isHigh())
        val isStale = WidgetStateRules.isStale(lastBg?.timestamp, dateUtil.now())

        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val deltaText = glucoseStatus?.let { leftToRightText(profileUtil.fromMgdlToSignedStringInUnits(it.delta)) } ?: unavailable
        val timeAgoText = dateUtil.minAgo(rh, lastBg?.timestamp)

        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val iobTotal = bolusIob.iob + basalIob.basaliob

        val cobInfo = iobCobCalculator.getCobInfo("Overview COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter) ?: unavailable
        var cobActive = (cobInfo.displayCob ?: 0.0) > 0.0
        var carbsRequired = false
        var carbsRequiredGrams = 0
        val lastRun = loop.lastRun
        val constraintsProcessed = lastRun?.constraintsProcessed
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            val carbsReq = constraintsProcessed.carbsReq
            if (carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (WidgetStateRules.carbsRequired(carbsReq, lastCarbsTime, lastRun.lastAPSRun)) {
                    cobText += "\n" + rh.gs(CoreUiStrings.value_with_unit, carbsReq.toString(), rh.gs(CoreUiStrings.required))
                    cobActive = true
                    carbsRequired = true
                    carbsRequiredGrams = carbsReq
                }
            }
        }

        val units = profileFunction.getUnits()
        val profile = profileFunction.getProfile()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        var tempTargetText = ""
        var targetChip = WidgetTargetChip.NONE
        if (tempTarget != null) {
            // A target without an end (a scene without a duration) shows no remaining time, like the overview chip.
            val range = profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units)
            tempTargetText = if (tempTarget.hasEnd) rh.gs(InterfacesStrings.value_with_detail, range, dateUtil.untilString(tempTarget.end, rh, withParentheses = false))
            else range
            targetChip = WidgetTargetChip.TEMP
        } else if (profile != null) {
            val adjustedTarget = profile.apsAdjustedTargetMgdl(loop, config, processedDeviceStatusData)
            if (adjustedTarget != null) {
                tempTargetText = profileUtil.toTargetRangeString(adjustedTarget, adjustedTarget, GlucoseUnit.MGDL, units)
                targetChip = WidgetTargetChip.ADJUSTED
            } else {
                tempTargetText = profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                targetChip = WidgetTargetChip.PROFILE
            }
        }

        val profileText = profileFunction.getProfileNameWithRemainingTime()
        val profileModified = profile?.let {
            it is ProfileSealed.EPS && (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
        } ?: false

        // What the overview chip and the watch show. The widget had its own copy of the rule, which
        // knew only the autosens ratio: with DynISF it showed "100%" and not the ISF in use (#5340).
        val sensitivity = sensitivityOverview.build()
        // Spoken as what the step means, like the overview chip. Equal values need no word.
        val isfStepDescription = when (sensitivity.isfStep) {
            IsfStep.UP   -> rh.gs(CoreUiStrings.arrow_up)
            IsfStep.DOWN -> rh.gs(CoreUiStrings.arrow_down)
            IsfStep.SAME -> null
        }

        val tbrState = if (profile == null) WidgetTbrState.NONE
        else {
            val basalData = iobCobCalculator.getBasalData(profile, dateUtil.now())
            WidgetStateRules.tbrState(true, basalData.isTempBasalRunning, basalData.tempBasalAbsolute, basalData.basal)
        }

        val mode = loop.runningMode()

        return WidgetState(
            bgText = bgText,
            bgMgdl = lastBg?.recalculated,
            bgTimestamp = lastBg?.timestamp,
            units = units,
            bgRange = bgRange,
            isStale = isStale,
            trendArrow = lastBg?.let { trendArrow },
            arrowDescription = lastBg?.let { rh.gs(trendArrow.directionToDescription()) },
            bgSpoken = if (isStale) rh.gs(InterfacesStrings.values_with_comma, bgText, rh.gs(UiStrings.a11y_bg_old_reading)) else bgText,
            deltaText = deltaText,
            deltaMgdl = glucoseStatus?.delta,
            timeAgoText = timeAgoText,
            iobText = rh.gs(InterfacesStrings.format_insulin_units, iobTotal),
            iob = iobTotal,
            iobActive = abs(iobTotal) > 0.001,
            iobLabel = rh.gs(CoreUiStrings.iob),
            cobText = cobText,
            cob = cobInfo.displayCob,
            cobActive = cobActive,
            cobLabel = rh.gs(CoreUiStrings.cob),
            carbsRequired = carbsRequired,
            carbsRequiredGrams = carbsRequiredGrams,
            tempTargetText = tempTargetText,
            targetChip = targetChip,
            tempTargetReason = tempTarget?.reason,
            targetIcon = WidgetStateRules.targetIcon(tempTarget),
            profileText = profileText,
            profileModified = profileModified,
            runningMode = mode,
            runningModeText = rh.gs(WidgetStateRules.runningModeText(mode)),
            loopIcon = WidgetStateRules.loopIcon(mode),
            runningModeActive = WidgetStateRules.runningModeActive(mode),
            lastLoopTime = if (config.APS) lastRun?.lastAPSRun else null,
            sensitivityIcon = WidgetStateRules.sensitivityIcon(sensitivity.isEnabled, sensitivity.ratio),
            sensitivityText = sensitivity.asText,
            isfFromText = sensitivity.isfFrom,
            isfToText = sensitivity.isfTo,
            isfStep = sensitivity.isfStep,
            isfStepDescription = isfStepDescription,
            tbrState = tbrState,
            tbrDescription = rh.gs(WidgetStateRules.tbrDescription(tbrState)),
            chartPoints = if (withChart) WidgetStateRules.chartPoints(iobCobCalculator.ads.getBucketedDataTableCopy(), dateUtil.now()) else emptyList()
        )
    }
}
