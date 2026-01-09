package app.aaps.plugins.main.general.dashboard.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.data.time.T
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import java.io.Serializable
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.objects.extensions.toStringFull
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class OverviewViewModel(
    private val context: Context,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val iobCobCalculator: IobCobCalculator,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val profileUtil: ProfileUtil,
    private val profileFunction: ProfileFunction,
    private val resourceHelper: ResourceHelper,
    private val dateUtil: DateUtil,
    private val loop: Loop,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val decimalFormatter: DecimalFormatter,
    private val activePlugin: ActivePlugin,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences
) : ViewModel() {

    private val disposables = CompositeDisposable()
    private var started = false

    private val _statusCardState = MutableLiveData<StatusCardState>()
    val statusCardState: LiveData<StatusCardState> = _statusCardState

    private val _adjustmentState = MutableLiveData<AdjustmentCardState>()
    val adjustmentState: LiveData<AdjustmentCardState> = _adjustmentState

    private val _graphMessage = MutableLiveData<String>()
    val graphMessage: LiveData<String> = _graphMessage

    fun start() {
        if (started) return
        started = true
        subscribeToUpdates()
        refreshAll()
    }

    fun stop() {
        started = false
        disposables.clear()
    }

    override fun onCleared() {
        disposables.clear()
        super.onCleared()
    }

    private fun subscribeToUpdates() {
        disposables += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ refreshAll() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateGraphMessage() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(app.aaps.core.interfaces.rx.events.EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ 
                if (it.isChanged(app.aaps.core.keys.StringKey.OApsAIMIContextStorage.key)) {
                    updateStatus() 
                }
            }, fabricPrivacy::logException)
    }

    private fun refreshAll() {
        updateStatus()
        updateAdjustments()
        updateGraphMessage()
    }

    private fun updateStatus() {
        val lastBg = lastBgData.lastBg()
        val glucoseText = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)?.directionToIcon()
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads) ?: ""
        val deltaText = glucoseStatusProvider.glucoseStatusData?.shortAvgDelta?.let {
            profileUtil.fromMgdlToSignedStringInUnits(it)
        } ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val iobText = totalIobText()
        val cobText = iobCobCalculator
            .getCobInfo("Dashboard COB")
            .displayText(resourceHelper, decimalFormatter)
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val timeAgo = dateUtil.minAgoShort(lastBg?.timestamp)
        val timeAgoLong = dateUtil.minAgoLong(resourceHelper, lastBg?.timestamp)
        val contentDescription =
            resourceHelper.gs(R.string.a11y_blood_glucose) + " " +
                glucoseText + " " + lastBgData.lastBgDescription() + " " + timeAgoLong


        // ═══════════════════════════════════════════════════════════════
        // Circle-Top Hybrid Dashboard - Calculate all new fields
        // ═══════════════════════════════════════════════════════════════
        
        // 1. Nose angle from delta (for GlucoseRingView pointer)
        val delta = glucoseStatusProvider.glucoseStatusData?.delta ?: 0.0
        val noseAngleDeg = when {
            delta > 10 -> 45f   // Rapidly rising →45°
            delta > 5 -> 20f    // Rising →20°
            delta > 2 -> 10f    // Slightly rising →10°
            delta < -10 -> -45f // Rapidly falling ↓-45°
            delta < -5 -> -20f  // Falling ↓-20°
            delta < -2 -> -10f  // Slightly falling ↓-10°
            else -> 0f          // Stable →0°
        }
        
        // 2. Reservoir
        val reservoirText = activePlugin.activePump.reservoirLevel?.let { level ->
            if (level > 0) decimalFormatter.to2Decimal(level) + " IE" 
            else null
        }
        
        // 3. Infusion Age (from CarePortal)
        val infusionAgeText = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.let { event ->
            val ageMillis = dateUtil.now() - event.timestamp
            val hours = (ageMillis / (1000 * 60 * 60)).toInt()
            val days = hours / 24
            val remainingHours = hours % 24
            if (days > 0) "${days}d ${remainingHours}h" else "${hours}h"
        }
        
        // 4. Sensor Age
        val sensorAgeText = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.let { event ->
            val ageMillis = dateUtil.now() - event.timestamp
            val hours = (ageMillis / (1000 * 60 * 60)).toInt()
            val days = hours / 24
            val remainingHours = hours % 24
            if (days > 0) "${days}d ${remainingHours}h" else "${hours}h"
        }
        
        // 5. Basal (current profile basal rate)
        val basalText = profileFunction.getProfile()?.let { profile ->
            val currentBasal = profile.getBasal(dateUtil.now())
            decimalFormatter.to2Decimal(currentBasal) + " IE"
        }
        
        // 6. Activity % - simplified (TBR percentage)
        val activityPctText = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.takeIf { it.isInProgress }?.let { tbr ->
            profileFunction.getProfile()?.let { profile ->
                val currentBasal = profile.getBasal(dateUtil.now())
                if (currentBasal > 0) {
                    val pct = ((tbr.rate / currentBasal) * 100 - 100).toInt()
                    "$pct%"
                } else "0%"
            } ?: "0%"
        } ?: "0%"
        
        // 7. Pump Battery
        val pumpBatteryText = activePlugin.activePump.batteryLevel?.let { "$it%" }
        
        // 8. Last Sensor Value (simplified)
        val lastSensorValueText = lastBg?.recalculated?.let { bg ->
            val units = profileFunction.getUnits() ?: GlucoseUnit.MGDL
            profileUtil.fromMgdlToStringInUnits(bg, units)
        }
        
        // 9. TBR Rate
        val tbrRateText = processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.takeIf { it.isInProgress }?.let { tbr ->
            decimalFormatter.to2Decimal(tbr.rate) + " U/h"
        } ?: "0.00 U/h"

        val state = StatusCardState(
            glucoseText = glucoseText,
            glucoseColor = lastBgData.lastBgColor(context),
            trendArrowRes = trendArrow,
            trendDescription = trendDescription,
            deltaText = deltaText,
            iobText = iobText,
            cobText = cobText,
            loopStatusText = loopStatusText(loop.runningMode),
            loopIsRunning = !loop.runningMode.isSuspended(),
            timeAgo = timeAgo,
            timeAgoDescription = timeAgoLong,
            isGlucoseActual = lastBgData.isActualBg(),
            contentDescription = contentDescription,
            pumpStatusText = buildPumpLine(dateUtil.now()),
            predictionText = buildPredictionLine(dateUtil.now()),
            unicornImageRes = selectUnicornImage(  // 🦄 Dynamic unicorn image
                bg = lastBg?.recalculated,
                delta = glucoseStatusProvider.glucoseStatusData?.delta
            ),
            isAimiContextActive = preferences.get(app.aaps.core.keys.StringKey.OApsAIMIContextStorage).length > 5,
            // For GlucoseCircleView
            glucoseValue = lastBg?.recalculated,
            targetLow = profileFunction.getProfile()?.getTargetLowMgdl(),
            targetHigh = profileFunction.getProfile()?.getTargetHighMgdl(),
            
            // Circle-Top Hybrid Dashboard fields
            glucoseMgdl = lastBg?.recalculated?.toInt(),
            noseAngleDeg = noseAngleDeg,
            reservoirText = reservoirText,
            infusionAgeText = infusionAgeText,
            pumpBatteryText = pumpBatteryText,
            sensorAgeText = sensorAgeText,
            lastSensorValueText = lastSensorValueText,
            activityPctText = activityPctText,
            tbrRateText = tbrRateText,
            basalText = basalText
        )
        _statusCardState.postValue(state)
    }

    /**
     * Calculates total IOB text for display.
     * 
     * CRITICAL FIX: Removed abs() that was causing IOB to appear increasing
     * when basal IOB was negative (during low TBR).
     * 
     * Scenario that was broken:
     * - T1: Bolus IOB = 1.0 U, Basal IOB = -1.0 U → total = abs(0.0) = 0.0 U ✓
     * - T2: Bolus IOB = 0.5 U, Basal IOB = -1.5 U → total = abs(-1.0) = 1.0 U ✗ (INCREASED!)
     * 
     * Total IOB can be negative (insulin debt from low TBR), which is valid
     * and important clinical information to display.
     */
    private fun totalIobText(): String {
        val bolus = bolusIob()
        val basal = basalIob()
        
        // FIXED: No abs() - total can be negative (insulin debt)
        val total = bolus.iob + basal.basaliob
        
        // Display with sign to show positive/negative IOB
        val formattedTotal = if (total >= 0) {
            resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, total)
        } else {
            // Negative IOB (insulin debt) - show with minus sign
            "-" + resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, -total)
        }
        
        return "IOB: $formattedTotal"
    }

    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()

    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()

    private fun loopStatusText(mode: RM.Mode): String =
        resourceHelper.gs(
            when (mode) {
                RM.Mode.SUPER_BOLUS -> app.aaps.core.ui.R.string.superbolus
                RM.Mode.DISCONNECTED_PUMP -> app.aaps.core.ui.R.string.disconnected
                RM.Mode.SUSPENDED_BY_PUMP -> app.aaps.core.ui.R.string.pumpsuspended
                RM.Mode.SUSPENDED_BY_USER -> app.aaps.core.ui.R.string.loopsuspended
                RM.Mode.SUSPENDED_BY_DST -> app.aaps.core.ui.R.string.loop_suspended_by_dst
                RM.Mode.CLOSED_LOOP_LGS -> app.aaps.core.ui.R.string.uel_lgs_loop_mode
                RM.Mode.CLOSED_LOOP -> app.aaps.core.ui.R.string.closedloop
                RM.Mode.OPEN_LOOP -> app.aaps.core.ui.R.string.openloop
                RM.Mode.DISABLED_LOOP -> app.aaps.core.ui.R.string.disabled_loop
                RM.Mode.RESUME -> app.aaps.core.ui.R.string.resumeloop
            }
        )

    private fun updateAdjustments() {
        val now = dateUtil.now()
        val lastBg = lastBgData.lastBg()
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val adjustments = buildActiveAdjustments(now)
        val state = AdjustmentCardState(
            glycemiaLine = buildGlycemiaLine(lastBg, trendArrow, glucoseStatus),
            predictionLine = buildPredictionLine(now),
            iobActivityLine = buildIobActivityLine(),
            decisionLine = buildDecisionLine(),
            pumpLine = buildPumpLine(now),
            safetyLine = buildSafetyLine(lastBg, glucoseStatus),
            modeLine = resolveModeLine(now),
            adjustments = adjustments,
            reason = buildDecisionLine(),
            // Populate new fields
            peakTime = loop.lastRun?.request?.oapsProfileAimi?.peakTime,
            dia = loop.lastRun?.request?.oapsProfileAimi?.dia,
            targetBg = loop.lastRun?.request?.oapsProfileAimi?.target_bg,
            smb = loop.lastRun?.request?.smb,
            basal = loop.lastRun?.request?.rate,
            detailedReason = loop.lastRun?.request?.reason,
            isHypoRisk = loop.lastRun?.request?.isHypoRisk ?: false
        )
        _adjustmentState.postValue(state)
    }

    private fun updateGraphMessage() {
        val message = resourceHelper.gs(R.string.dashboard_graph_updated, dateUtil.timeString(dateUtil.now()))
        _graphMessage.postValue(message)
    }

    private fun buildActiveAdjustments(now: Long): List<String> {
        val adjustments = mutableListOf<String>()
        processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress }?.let {
            adjustments += resourceHelper.gs(R.string.dashboard_adjustment_temp_basal, it.toStringShort(resourceHelper))
        }
        persistenceLayer.getTemporaryTargetActiveAt(now)?.let { target ->
            val units = profileFunction.getUnits() ?: GlucoseUnit.MGDL
            val range = profileUtil.toTargetRangeString(target.lowTarget, target.highTarget, GlucoseUnit.MGDL, units)
            adjustments += resourceHelper.gs(
                R.string.dashboard_adjustment_temp_target,
                range,
                dateUtil.untilString(target.end, resourceHelper)
            )
        }
        persistenceLayer.getExtendedBolusActiveAt(now)?.takeIf { it.isInProgress(dateUtil) }?.let {
            adjustments += resourceHelper.gs(
                R.string.dashboard_adjustment_extended_bolus,
                it.toStringFull(dateUtil, resourceHelper)
            )
        }
        return adjustments
    }

    private fun buildGlycemiaLine(
        lastBg: InMemoryGlucoseValue?,
        trendArrow: TrendArrow?,
        glucoseStatus: GlucoseStatus?
    ): String {
        val glucoseText = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
        val deltaText = glucoseStatus?.shortAvgDelta?.let { profileUtil.fromMgdlToSignedStringInUnits(it) }
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        return resourceHelper.gs(
            R.string.dashboard_adjustment_glycemia,
            glucoseText,
            trendSymbol(trendArrow),
            deltaText
        )
    }

    private fun buildPredictionLine(now: Long): String {
        val request = loop.lastRun?.request ?: return resourceHelper.gs(R.string.dashboard_adjustment_prediction_unavailable)
        val predictions = request.predictionsAsGv
        if (predictions.isEmpty()) {
            fabricPrivacy.logMessage("PRED_UNAVAILABLE: predictions empty")
            return resourceHelper.gs(R.string.dashboard_adjustment_prediction_unavailable)
        }
        val targetTime = now + TimeUnit.MINUTES.toMillis(PREDICTION_LOOKAHEAD_MINUTES)
        val closest = predictions.minByOrNull { abs(it.timestamp - targetTime) }
            ?: return resourceHelper.gs(R.string.dashboard_adjustment_prediction_unavailable)
        val valueText = profileUtil.fromMgdlToStringInUnits(closest.value)
        val minutes = max(1L, abs(closest.timestamp - now) / TimeUnit.MINUTES.toMillis(1))
        val minutesText = resourceHelper.gs(R.string.dashboard_adjustment_minutes, minutes)
        return resourceHelper.gs(R.string.dashboard_adjustment_prediction, "→", valueText, minutesText)
    }

    private fun buildIobActivityLine(): String {
        val autosensPercent = ((loop.lastRun?.request?.autosensResult?.ratio ?: 1.0) * 100.0)
        val activityText = decimalFormatter.to0Decimal(autosensPercent)
        return resourceHelper.gs(R.string.dashboard_adjustment_iob_activity, totalIobText(), activityText)
    }

    private fun buildDecisionLine(): String {
        val request = loop.lastRun?.request ?: return resourceHelper.gs(R.string.dashboard_adjustment_decision_unavailable)
        val smbText = decimalFormatter.to2Decimal(request.smb)
        val basalText = if (request.rate == -1.0) "---" else decimalFormatter.to2Decimal(request.rate)
        return resourceHelper.gs(R.string.dashboard_adjustment_decision, smbText, basalText)
    }

    private fun buildPumpLine(now: Long): String {
        val reservoirLevel = activePlugin.activePump.reservoirLevel
        val reservoirText =
            if (reservoirLevel > 0)
                resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, reservoirLevel)
            else resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        val siteEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
        var siteAge = formatTherapyAge(siteEvent, now)
        siteEvent?.let {
            val diff = now - it.timestamp
            // > 3 days (72 hours) -> Yellow
            if (diff > TimeUnit.DAYS.toMillis(3)) {
                siteAge = "<font color='#FFFF00'>$siteAge</font>"
            }
        }

        val sensorEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
        var sensorAge = formatTherapyAge(sensorEvent, now)
        sensorEvent?.let {
            val diff = now - it.timestamp
            // > 8 days (9th day start) -> Orange
            if (diff > TimeUnit.DAYS.toMillis(8)) {
                sensorAge = "<font color='#FFA500'>$sensorAge</font>"
            }
        }

        val lastConnection = activePlugin.activePump.lastDataTime
        val threshold = T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
        val isUnreachable = preferences.get(app.aaps.core.keys.BooleanKey.AlertPumpUnreachable) && (lastConnection + threshold < now)

        val statusText = if (isUnreachable) {
            val unreachableText = resourceHelper.gs(app.aaps.core.ui.R.string.pump_unreachable)
            "$reservoirText <font color='#FF0000'>$unreachableText</font>"
        } else {
            reservoirText
        }

        return resourceHelper.gs(R.string.dashboard_adjustment_pump, statusText, siteAge, sensorAge)
    }

    private fun buildSafetyLine(lastBg: InMemoryGlucoseValue?, glucoseStatus: GlucoseStatus?): String {
        val bgValue = lastBg?.recalculated
        val level = when {
            bgValue == null -> null
            bgValue < SAFETY_CRITICAL_BG -> resourceHelper.gs(R.string.dashboard_adjustment_safety_level_critical)
            bgValue < SAFETY_LIMITED_BG -> resourceHelper.gs(R.string.dashboard_adjustment_safety_level_limited)
            else -> resourceHelper.gs(R.string.dashboard_adjustment_safety_level_normal)
        }
        if (level == null) return resourceHelper.gs(R.string.dashboard_adjustment_safety_unknown)
        val reasons = mutableListOf<String>()
        bgValue?.let {
            val res = if (it < SAFETY_LIMITED_BG)
                R.string.dashboard_adjustment_reason_low
            else R.string.dashboard_adjustment_reason_high
            reasons += resourceHelper.gs(res, profileUtil.fromMgdlToStringInUnits(it))
        }
        glucoseStatus?.shortAvgDelta?.let {
            val trendRes = when {
                it > 0.5 -> R.string.dashboard_adjustment_trend_rising
                it < -0.5 -> R.string.dashboard_adjustment_trend_falling
                else -> R.string.dashboard_adjustment_trend_stable
            }
            reasons += resourceHelper.gs(R.string.dashboard_adjustment_reason_trend, resourceHelper.gs(trendRes))
        }
        if (reasons.isEmpty()) reasons += resourceHelper.gs(R.string.dashboard_adjustment_reason_unknown)
        if (reasons.isEmpty()) reasons += resourceHelper.gs(R.string.dashboard_adjustment_reason_unknown)
        
        val safetyText = reasons.joinToString(", ")
        val finalSafetyText = if (loop.lastRun?.request?.isHypoRisk == true) {
            "<font color='#FF0000'>Hypo Risk!</font> $safetyText"
        } else {
            safetyText
        }
        
        return resourceHelper.gs(R.string.dashboard_adjustment_safety, level, finalSafetyText)
    }

    private fun resolveModeLine(now: Long): String? {
        val events = persistenceLayer.getTherapyEventDataFromTime(now - MODE_LOOKBACK_MS, TE.Type.NOTE, false)
        var latest: Pair<TE, ModeKeyword>? = null
        events.forEach { event ->
            val keyword = modeKeywords.firstOrNull { event.note?.contains(it.token, ignoreCase = true) == true } ?: return@forEach
            val remaining = event.timestamp + event.duration - now
            if (remaining <= 0) return@forEach
            if (latest == null || event.timestamp > latest!!.first.timestamp) {
                latest = event to keyword
            }
        }
        val result = latest ?: return null
        val remaining = (result.first.timestamp + result.first.duration) - now
        if (remaining <= 0) return null
        val remainingText = dateUtil.age(remaining, true, resourceHelper).trim()
        val modeName = resourceHelper.gs(result.second.labelRes)
        return resourceHelper.gs(R.string.dashboard_adjustment_mode, modeName, remainingText)
    }

    private fun formatTherapyAge(event: TE?, now: Long): String {
        return event?.let {
            val diff = now - it.timestamp
            dateUtil.age(diff, true, resourceHelper).trim()
        } ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
    }

    private fun trendSymbol(arrow: TrendArrow?): String = when (arrow) {
        TrendArrow.DOUBLE_DOWN -> "⬇⬇"
        TrendArrow.SINGLE_DOWN -> "⬇️"
        TrendArrow.FORTY_FIVE_DOWN -> "↘️"
        TrendArrow.FLAT -> "→"
        TrendArrow.FORTY_FIVE_UP -> "↗️"
        TrendArrow.SINGLE_UP -> "⬆️"
        TrendArrow.DOUBLE_UP -> "⬆⬆"
        else -> "→"
    }

    /**
     * 🦄 Selects the appropriate AIMICO unicorn image based on BG and delta.
     * Matches AIMICO watchface behavior:
     * - Normal: Blue unicorn with sunglasses and "AIMI" logo
     * - Alert: Worried expression with stress notes  
     * - Hypo: Holding orange juice box
     */
    /**
     * 🦄 Selects the appropriate AIMICO unicorn image based on BG and delta.
     * Logic:
     * - Hypo (< 70): Down/Up trends or Stable
     * - Normal (70-180): Down/Up trends or Stable
     * - Hyper (180-250): Alert Up or Hyper Stable
     * - Alert (> 250): Alert Up or Alert Stable
     */
    private fun selectUnicornImage(bg: Double?, delta: Double?): Int {
        if (bg == null) return R.drawable.unicorn_normal_up
        val d = delta ?: 0.0

        return when {
            // Hypo State (< 70)
            bg < 70.0 -> when {
                d < -2.0 -> R.drawable.unicorn_hypo_juice
                d > 2.0 -> R.drawable.unicorn_hypo_up
                else -> R.drawable.unicorn_hypo_stable // Or unicorn_hypo_juice if preferred
            }

            // Normal State (70 - 180)
            bg < 180.0 -> when {
                d < -2.0 -> R.drawable.unicorn_normal_down
                d > 2.0 -> R.drawable.unicorn_normal_stable
                else -> R.drawable.unicorn_normal_up
            }

            // Hyper State (180 - 250)
            bg < 250.0 -> when {
                d > 2.0 -> R.drawable.unicorn_alert_up // Rising in hyper -> Alert
                else -> R.drawable.unicorn_hyper_stable2
            }

            // Alert State (> 250)
            else -> when {
                d > 2.0 -> R.drawable.unicorn_alert_up
                else -> R.drawable.unicorn_hyper_stable2
            }
        }
    }


    class Factory(
        private val context: Context,
        private val lastBgData: LastBgData,
        private val trendCalculator: TrendCalculator,
        private val iobCobCalculator: IobCobCalculator,
        private val glucoseStatusProvider: GlucoseStatusProvider,
        private val profileUtil: ProfileUtil,
        private val profileFunction: ProfileFunction,
        private val resourceHelper: ResourceHelper,
        private val dateUtil: DateUtil,
        private val loop: Loop,
        private val processedTbrEbData: ProcessedTbrEbData,
        private val persistenceLayer: PersistenceLayer,
        private val decimalFormatter: DecimalFormatter,
        private val activePlugin: ActivePlugin,
        private val rxBus: RxBus,
        private val aapsSchedulers: AapsSchedulers,
        private val fabricPrivacy: FabricPrivacy,
        private val preferences: Preferences
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OverviewViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return OverviewViewModel(
                    context.applicationContext,
                    lastBgData,
                    trendCalculator,
                    iobCobCalculator,
                    glucoseStatusProvider,
                    profileUtil,
                    profileFunction,
                    resourceHelper,
                    dateUtil,
                    loop,
                    processedTbrEbData,
                    persistenceLayer,
                    decimalFormatter,
                    activePlugin,
                    rxBus,
                    aapsSchedulers,
                    fabricPrivacy,
                    preferences
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }

    private data class ModeKeyword(val token: String, val labelRes: Int)

    companion object {
        private const val PREDICTION_LOOKAHEAD_MINUTES = 30L
        private const val SAFETY_LIMITED_BG = 90.0
        private const val SAFETY_CRITICAL_BG = 70.0
        private val MODE_LOOKBACK_MS = TimeUnit.HOURS.toMillis(12)
        private val modeKeywords = listOf(
            ModeKeyword("meal", R.string.dashboard_mode_meal),
            ModeKeyword("bfast", R.string.dashboard_mode_breakfast),
            ModeKeyword("lunch", R.string.dashboard_mode_lunch),
            ModeKeyword("dinner", R.string.dashboard_mode_dinner),
            ModeKeyword("highcarb", R.string.dashboard_mode_highcarb),
            ModeKeyword("lowcarb", R.string.dashboard_mode_lowcarb),
            ModeKeyword("snack", R.string.dashboard_mode_snack),
            ModeKeyword("sport", R.string.dashboard_mode_sport),
            ModeKeyword("sleep", R.string.dashboard_mode_sleep)
        )
    }
}

data class StatusCardState(
    val glucoseText: String,
    val glucoseColor: Int,
    val trendArrowRes: Int?,
    val trendDescription: String,
    val deltaText: String,
    val iobText: String,
    val cobText: String,
    val loopStatusText: String,
    val loopIsRunning: Boolean,
    val timeAgo: String,
    val timeAgoDescription: String,
    val isGlucoseActual: Boolean,
    val contentDescription: String,
    val pumpStatusText: String = "",
    val predictionText: String = "",
    val unicornImageRes: Int = R.drawable.unicorn_normal_stable,  // 🦄 Dynamic unicorn image
    val isAimiContextActive: Boolean = false,
    // For GlucoseCircleView color update
    val glucoseValue: Double? = null,
    val targetLow: Double? = null,
    val targetHigh: Double? = null,
    
    // Circle-Top Hybrid Dashboard fields
    val glucoseMgdl: Int? = null,
    val noseAngleDeg: Float? = null,
    val reservoirText: String? = null,
    val infusionAgeText: String? = null,
    val pumpBatteryText: String? = null,
    val sensorAgeText: String? = null,
    val lastSensorValueText: String? = null,
    val activityPctText: String? = null,
    val tbrRateText: String? = null,
    val basalText: String? = null
)

data class AdjustmentCardState(
    val glycemiaLine: String,
    val predictionLine: String,
    val iobActivityLine: String,
    val decisionLine: String,
    val pumpLine: String,
    val safetyLine: String,
    val modeLine: String?,
    val adjustments: List<String>,
    val reason: String?,
    // New fields for detailed view
    val peakTime: Double? = null,
    val dia: Double? = null,
    val targetBg: Double? = null,
    val smb: Double? = null,
    val basal: Double? = null,
    val detailedReason: String? = null,
    val isHypoRisk: Boolean = false
) : Serializable
