package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.core.ui.extensions.toStringFull
import app.aaps.core.ui.extensions.toStringShort
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.adaptiveSmoothingQualityTexts
import app.aaps.ui.compose.dashboard.glass.formatDeviceAge
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The values of the AIMI dashboard home and of its adjustment details screen that no shared overview
 * flow carries: the AIMI insights and adaptation summary, the "last AIMI run" card, today's five-band
 * time in range, the temp basal, the pump battery, the smoothing quality and the adjustment lines.
 *
 * On `dev_OAPSAIMI` these came from `OverviewViewModel.updateStatus` and `updateAdjustments`. The rules
 * are the same. The glucose, IOB, loop pill, steps, heart rate and pump line come from the shared view
 * models the Glass home also uses, so they are not computed twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class AimiDashboardViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val iobCobCalculator: IobCobCalculator,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val lastBgData: LastBgData,
    private val loop: Loop,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val config: Config,
) : ViewModel() {

    private val _status = MutableStateFlow(AimiDashboardStatus())
    val status: StateFlow<AimiDashboardStatus> = _status.asStateFlow()

    /** One-shot texts for a snackbar: staging promotion result, "loop run requested". */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Work only while a screen shows the status, like GlassHomeViewModel.
        _status.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { visible -> if (visible) refreshTriggers() else emptyFlow() }
            .onEach { refresh() }
            .launchIn(viewModelScope)
    }

    private fun refreshTriggers(): Flow<Unit> = merge(
        // Emits at once, then every minute: ages, "last AIMI run" and the adaptation freshness move with time.
        tickerFlow(60_000L),
        rxBus.toFlow(EventLoopUpdateGui::class).map { },
        persistenceLayer.observeChanges(GV::class).map { },
        persistenceLayer.observeChanges(TB::class).map { },
        persistenceLayer.observeChanges(TT::class).map { },
        persistenceLayer.observeChanges(TE::class).map { },
        persistenceLayer.observeChanges(RM::class).map { },
        activePlugin.activePump.batteryLevel.map { },
        persistenceLayer.databaseClearedFlow.map { },
        preferences.observe(StringKey.OApsAIMIContextStorage).map { },
    )

    private var refreshJob: Job? = null

    private fun refresh() {
        // Only the newest refresh matters; a slow one must not pile up behind the next.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                _status.value = withContext(aapsIoDispatcher) { buildStatus() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "AIMI dashboard status failed", e)
            }
        }
    }

    /**
     * Promotes the staging sensor of [sensor] to production: the only action that changes the glucose
     * source of the loop. The result is sent on [messages].
     */
    fun promoteStaging(sensor: CgmSensorStatusProvider) {
        viewModelScope.launch {
            try {
                _messages.emit(rh.gs(promotionMessage(sensor.promoteStagingToProduction())))
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Staging promotion failed", e)
            }
        }
    }

    /** Asks the loop to run now, as the "Run loop" button on `dev_OAPSAIMI`. Only a phone that loops can. */
    fun runLoop() {
        if (!config.APS) return
        viewModelScope.launch {
            _messages.emit(rh.gs(UiStrings.dashboard_loop_run_requested))
            try {
                withContext(aapsIoDispatcher) { loop.invoke("Dashboard", true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Error invoking loop from dashboard", e)
            }
        }
    }

    private suspend fun buildStatus(): AimiDashboardStatus {
        val now = dateUtil.now()
        val profile = profileFunction.getProfile()
        val request = loop.lastRun?.request
        val rt = request?.rawData() as? RT

        val activeTempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress(dateUtil) }
        val scheduledBasal = profile?.getBasal(now)
        val tbrRate = activeTempBasal?.rate ?: 0.0
        val tbrRateCompactText = rh.gs(UiStrings.dashboard_rate_uh, decimalFormatter.to2Decimal(tbrRate))
        val tbrRateText = if (activeTempBasal != null && scheduledBasal != null && scheduledBasal > 0) {
            rh.gs(UiStrings.dashboard_rate_uh_percent, decimalFormatter.to2Decimal(tbrRate), (tbrRate / scheduledBasal * 100).toInt())
        } else tbrRateCompactText

        val dayStart = dateUtil.beginOfDay(now)
        val tir = AimiDashboardRules.tir(persistenceLayer.getBgReadingsDataFromTimeToTime(dayStart, now, true).map { it.value })
        val tirStatsLine = tir?.let {
            rh.gs(UiStrings.dashboard_tir_stats_format, profileUtil.fromMgdlToStringInUnits(it.averageMgdl), decimalFormatter.to1Decimal(it.a1c))
        } ?: rh.gs(UiStrings.dashboard_tir_stats_placeholder)

        val t3cMinutes = rt?.trajectoryConvergenceETA ?: -1
        val trajectory = AimiDashboardRules.trajectoryLabel(rt?.trajectoryType)
        val relevance = rt?.trajectoryRelevanceScore
        val adaptation = AimiAdaptationStatusPresenter.present(
            status = rt?.aimiAdaptationStatus,
            now = now,
            staleAfterMs = preferences.get(IntKey.AlertsStaleDataThreshold) * 60_000L,
        )
        val adaptationSummary = if (adaptation.hasStatus) {
            rh.gs(UiStrings.aimi_adaptation_status_counts, adaptation.activeCount, adaptation.readyCount, adaptation.waitingOrLearningCount, adaptation.attentionCount)
        } else rh.gs(UiStrings.dashboard_aimi_adaptation_no_data)

        val smoothing = activePlugin.activeSmoothing.lastAdaptiveSmoothingQualitySnapshot()

        return AimiDashboardStatus(
            tbrRateText = tbrRateText,
            tbrRateCompactText = tbrRateCompactText,
            pumpBatteryText = activePlugin.activePump.batteryLevel.value?.let { rh.gs(UiStrings.dashboard_battery_percent, it) },
            tir = tir,
            tirStatsLine = tirStatsLine,
            insightT3c = if (t3cMinutes > 0) rh.gs(UiStrings.dashboard_aimi_insight_t3c, t3cMinutes) else rh.gs(UiStrings.dashboard_aimi_insight_t3c_none),
            insightTrajectory = trajectory?.let { rh.gs(it) } ?: rh.gs(UiStrings.dashboard_aimi_insight_trajectory_none),
            insightFactor = rh.gs(UiStrings.dashboard_aimi_insight_factor, decimalFormatter.to1Decimal(relevance ?: 0.0)),
            trajectoryRelevanceScore = relevance,
            aimiHealthScore = rt?.trajectoryHealth?.toDouble()?.div(100.0),
            adaptationSummary = adaptationSummary,
            adaptationContentDescription = rh.gs(UiStrings.dashboard_aimi_adaptation_a11y, adaptationSummary),
            adaptationHasAttention = adaptation.attentionCount > 0,
            pulseTitle = pulseTitle(loop.lastRun?.lastAPSRun, now),
            pulseSummary = pulseSummary(request),
            pulseMeta = pulseMeta(request),
            pulseHypoRisk = request?.isHypoRisk == true,
            contextIndicatorVisible = preferences.get(StringKey.OApsAIMIContextStorage).length > CONTEXT_MIN_LENGTH,
            smoothingTier = smoothing?.tier,
            smoothingBadgeText = smoothing?.let { rh.gs(adaptiveSmoothingQualityTexts(it.tier).badge) } ?: "",
            smoothingDialogMessage = smoothing?.let {
                rh.gs(adaptiveSmoothingQualityTexts(it.tier).dialog, it.learnedR, (it.outlierRate * 100.0).toInt(), (it.compressionRate * 100.0).toInt())
            } ?: "",
            adjustment = buildAdjustment(now, request, rt),
        )
    }

    private suspend fun buildAdjustment(now: Long, request: APSResult?, rt: RT?): AimiAdjustmentDetails {
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val lastBg = lastBgData.lastBg()
        val bgMgdl = glucoseStatus?.glucose ?: lastBg?.value
        val deltaMgdl = glucoseStatus?.let { if (it.shortAvgDelta.isFinite()) it.shortAvgDelta else it.delta.takeIf { d -> d.isFinite() } }
        val deltaText = rh.gs(
            UiStrings.dashboard_glass_delta,
            deltaMgdl?.let { profileUtil.fromMgdlToSignedStringInUnits(it) } ?: rh.gs(CoreUiStrings.value_unavailable_short),
        )
        val bolusIob = iobCobCalculator.calculateIobFromBolus()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()
        val totalIob = bolusIob.iob + basalIob.basaliob
        val iobText = if (totalIob >= 0) rh.gs(InterfacesStrings.format_insulin_units, totalIob)
        else rh.gs(CoreUiStrings.format_insulin_units_signed, totalIob)
        val autosensPercent = decimalFormatter.to0Decimal((request?.autosensResult?.ratio ?: 1.0) * 100.0)
        val dash = rh.gs(CoreUiStrings.value_unavailable_short)

        return AimiAdjustmentDetails(
            glycemiaLine = rh.gs(
                UiStrings.dashboard_adjustment_glycemia,
                profileUtil.fromMgdlToStringInUnits(bgMgdl),
                AimiDashboardRules.trendSymbol(lastBg?.trendArrow),
                deltaText,
            ),
            predictionLine = predictionLine(request, now),
            iobActivityLine = rh.gs(UiStrings.dashboard_adjustment_iob_activity, iobText, autosensPercent),
            decisionLine = decisionLine(request),
            modeLine = modeLine(now),
            safetyLine = safetyLine(bgMgdl, glucoseStatus?.shortAvgDelta, request?.isHypoRisk == true),
            pumpReservoirText = activePlugin.activePump.reservoirLevel.value.cU.takeIf { it > 0 }
                ?.let { rh.gs(InterfacesStrings.format_insulin_units, it) } ?: dash,
            pumpSiteText = formatDeviceAge(rh, persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.let { now - it.timestamp }),
            pumpSensorText = formatDeviceAge(rh, persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.let { now - it.timestamp }),
            adjustments = activeAdjustments(now),
            peakText = request?.oapsProfileAimi?.peakTime?.let { rh.gs(UiStrings.dashboard_adjustment_minutes, decimalFormatter.to0Decimal(it)) } ?: dash,
            diaText = request?.oapsProfileAimi?.dia?.let { rh.gs(UiStrings.dashboard_adjustment_hours, decimalFormatter.to1Decimal(it)) } ?: dash,
            // The target AIMI ended up using (RT.targetBG), not the profile target it started from. 0 means not set.
            targetText = request?.targetBG?.takeIf { it > 0.0 }?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: dash,
            smbText = request?.let { rh.gs(UiStrings.dashboard_adjustment_units, decimalFormatter.to2Decimal(it.smb)) } ?: dash,
            basalText = request?.rate?.takeIf { it >= 0.0 }?.let { rh.gs(UiStrings.dashboard_rate_uh, decimalFormatter.to2Decimal(it)) } ?: dash,
            reason = request?.reason?.let { AimiDashboardRules.plainText(it) }?.takeIf { it.isNotBlank() },
            trajectoryTitle = AimiDashboardRules.trajectoryLabel(rt?.trajectoryType)?.let { rh.gs(it) } ?: rt?.trajectoryType,
            trajectoryMetrics = rt?.takeIf { it.trajectoryType != null }?.let { r ->
                rh.gs(
                    UiStrings.dashboard_trajectory_metrics,
                    decimalFormatter.to3Decimal(r.trajectoryCurvature ?: 0.0),
                    decimalFormatter.to1Decimal(r.trajectoryEnergy ?: 0.0),
                    decimalFormatter.to2Decimal(r.trajectoryOpenness ?: 0.0),
                    decimalFormatter.to2Decimal(r.trajectoryRelevanceScore ?: 0.0),
                )
            },
            canRunLoop = config.APS,
        )
    }

    private suspend fun activeAdjustments(now: Long): List<String> = buildList {
        processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress(dateUtil) }?.let {
            add(rh.gs(UiStrings.dashboard_adjustment_temp_basal, it.toStringShort(rh)))
        }
        persistenceLayer.getTemporaryTargetActiveAt(now)?.let { target ->
            val range = profileUtil.toTargetRangeString(target.lowTarget, target.highTarget, GlucoseUnit.MGDL)
            add(rh.gs(UiStrings.dashboard_adjustment_temp_target, range, dateUtil.untilString(target.timestamp + target.duration, rh)))
        }
        persistenceLayer.getExtendedBolusActiveAt(now)?.takeIf { it.isInProgress(dateUtil) }?.let {
            add(rh.gs(UiStrings.dashboard_adjustment_extended_bolus, it.toStringFull(dateUtil, rh)))
        }
    }

    private fun predictionLine(request: APSResult?, now: Long): String {
        val predictions = request?.predictionsAsGv.orEmpty()
        val best = predictions.filter { it.sourceSensor == SourceSensor.UAM_PREDICTION }.maxByOrNull { it.timestamp }
        val floor = predictions.filter { it.sourceSensor == SourceSensor.IOB_PREDICTION }.maxByOrNull { it.timestamp }
        if (best == null && floor == null) return rh.gs(UiStrings.dashboard_adjustment_prediction_unavailable)
        val horizonMinutes = AimiDashboardRules.predictionHorizonMinutes(maxOf(best?.timestamp ?: 0L, floor?.timestamp ?: 0L), now)
        val dash = rh.gs(CoreUiStrings.value_unavailable_short)
        return rh.gs(
            UiStrings.dashboard_adjustment_scenario_projection,
            best?.let { profileUtil.fromMgdlToStringInUnits(it.value) } ?: dash,
            floor?.let { profileUtil.fromMgdlToStringInUnits(it.value) } ?: dash,
            horizonMinutes,
        )
    }

    private fun decisionLine(request: APSResult?): String {
        request ?: return rh.gs(UiStrings.dashboard_adjustment_decision_unavailable)
        val basal = if (request.rate == -1.0) NO_BASAL else decimalFormatter.to2Decimal(request.rate)
        return rh.gs(UiStrings.dashboard_adjustment_decision, decimalFormatter.to2Decimal(request.smb), basal)
    }

    private fun safetyLine(bgMgdl: Double?, shortAvgDelta: Double?, hypoRisk: Boolean): String {
        bgMgdl ?: return rh.gs(UiStrings.dashboard_adjustment_safety_unknown)
        val level = when (AimiDashboardRules.safetyLevel(bgMgdl)) {
            AimiDashboardRules.SafetyLevel.CRITICAL -> UiStrings.dashboard_adjustment_safety_level_critical
            AimiDashboardRules.SafetyLevel.LIMITED  -> UiStrings.dashboard_adjustment_safety_level_limited
            AimiDashboardRules.SafetyLevel.NORMAL   -> UiStrings.dashboard_adjustment_safety_level_normal
        }
        val bgReason = rh.gs(UiStrings.dashboard_adjustment_reason_bg, profileUtil.fromMgdlToStringInUnits(bgMgdl))
        val reasons = shortAvgDelta?.let { delta ->
            val trend = when {
                delta > TREND_THRESHOLD  -> UiStrings.dashboard_adjustment_trend_rising
                delta < -TREND_THRESHOLD -> UiStrings.dashboard_adjustment_trend_falling
                else                     -> UiStrings.dashboard_adjustment_trend_stable
            }
            rh.gs(UiStrings.dashboard_adjustment_reason_list, bgReason, rh.gs(UiStrings.dashboard_adjustment_reason_trend, rh.gs(trend)))
        } ?: bgReason
        val shown = if (hypoRisk) rh.gs(UiStrings.dashboard_adjustment_safety_hypo, reasons) else reasons
        return rh.gs(UiStrings.dashboard_adjustment_safety, rh.gs(level), shown)
    }

    /** The newest meal or activity mode note of the last 12 h that is still running, as on DEV. */
    private suspend fun modeLine(now: Long): String? {
        val events = persistenceLayer.getTherapyEventDataFromTime(now - MODE_LOOKBACK_MS, TE.Type.NOTE, false)
        val (event, mode) = events
            .mapNotNull { event ->
                val mode = AimiDashboardRules.modeForNote(event.note) ?: return@mapNotNull null
                if (event.timestamp + event.duration - now <= 0) null else event to mode
            }
            .maxByOrNull { it.first.timestamp } ?: return null
        val remaining = event.timestamp + event.duration - now
        return rh.gs(UiStrings.dashboard_adjustment_mode, rh.gs(mode), formatDeviceAge(rh, remaining))
    }

    private fun pulseTitle(lastApsRun: Long?, now: Long): String {
        val run = lastApsRun?.takeIf { it > 0L } ?: return rh.gs(UiStrings.dashboard_aimi_pulse_title)
        return rh.gs(UiStrings.dashboard_aimi_pulse_title_with_age, formatDeviceAge(rh, now - run))
    }

    private fun pulseSummary(request: APSResult?): String {
        request ?: return rh.gs(UiStrings.dashboard_aimi_pulse_summary_none)
        val plain = AimiDashboardRules.plainText(request.reason)
        val core = if (plain.isNotBlank()) AimiDashboardRules.clip(plain)
        else rh.gs(
            UiStrings.dashboard_aimi_pulse_fallback,
            decimalFormatter.to2Decimal(request.smb),
            if (request.rate == -1.0) NO_VALUE else decimalFormatter.to2Decimal(request.rate),
        )
        return if (request.isHypoRisk) rh.gs(UiStrings.dashboard_aimi_pulse_hypo, core) else core
    }

    private fun pulseMeta(request: APSResult?): String {
        request ?: return ""
        val basal = if (request.rate == -1.0) NO_VALUE else rh.gs(UiStrings.dashboard_rate_uh, decimalFormatter.to2Decimal(request.rate))
        val sens = decimalFormatter.to0Decimal((request.autosensResult?.ratio ?: 1.0) * 100.0)
        return rh.gs(UiStrings.dashboard_aimi_pulse_meta, decimalFormatter.to2Decimal(request.smb), basal, sens)
    }

    private fun promotionMessage(result: PromotionResult): TextRef = when (result) {
        PromotionResult.Ok                  -> UiStrings.dashboard_staging_promote_ok
        PromotionResult.OkBoundFailed       -> UiStrings.dashboard_staging_promote_ok_bound_failed
        is PromotionResult.OkFollowUpFailed -> UiStrings.dashboard_staging_promote_ok_follow_up_failed
        is PromotionResult.Rejected         -> when (result.reason) {
            PromotionRejectReason.STAGING_ABSENT            -> UiStrings.dashboard_staging_promote_rejected_absent
            PromotionRejectReason.STAGING_NOT_SETTLED       -> UiStrings.dashboard_staging_promote_rejected_not_settled
            PromotionRejectReason.STAGING_NO_VALID_GLUCOSE  -> UiStrings.dashboard_staging_promote_rejected_no_glucose
            PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE -> UiStrings.dashboard_staging_promote_rejected_no_recent_glucose
            PromotionRejectReason.LOOP_BUSY                 -> UiStrings.dashboard_staging_promote_rejected_loop_busy
        }
    }

    private companion object {

        /** DEV showed the context badge when the stored AIMI context is longer than this (not "" or "[]"). */
        const val CONTEXT_MIN_LENGTH = 5
        const val MODE_LOOKBACK_MS = 12 * 60 * 60 * 1000L
        const val TREND_THRESHOLD = 0.5
        const val NO_VALUE = "—"
        const val NO_BASAL = "---"
    }
}

/** What [AimiDashboardViewModel] adds to the AIMI dashboard. Texts are ready to show. */
@Immutable
data class AimiDashboardStatus(
    val tbrRateText: String? = null,
    val tbrRateCompactText: String? = null,
    val pumpBatteryText: String? = null,
    val tir: AimiDashboardTir? = null,
    val tirStatsLine: String = "",
    val insightT3c: String = "",
    val insightTrajectory: String = "",
    val insightFactor: String = "",
    val trajectoryRelevanceScore: Double? = null,
    val aimiHealthScore: Double? = null,
    val adaptationSummary: String = "",
    val adaptationContentDescription: String = "",
    val adaptationHasAttention: Boolean = false,
    val pulseTitle: String = "",
    val pulseSummary: String = "",
    val pulseMeta: String = "",
    val pulseHypoRisk: Boolean = false,
    val contextIndicatorVisible: Boolean = false,
    val smoothingTier: AdaptiveSmoothingQualityTier? = null,
    val smoothingBadgeText: String = "",
    val smoothingDialogMessage: String = "",
    val adjustment: AimiAdjustmentDetails? = null,
)

/** The adjustment details screen (`AdjustmentDetailsActivity` on `dev_OAPSAIMI`). */
@Immutable
data class AimiAdjustmentDetails(
    val glycemiaLine: String,
    val predictionLine: String,
    val iobActivityLine: String,
    val decisionLine: String,
    val modeLine: String?,
    val safetyLine: String,
    val pumpReservoirText: String,
    val pumpSiteText: String,
    val pumpSensorText: String,
    val adjustments: List<String>,
    val peakText: String,
    val diaText: String,
    val targetText: String,
    val smbText: String,
    val basalText: String,
    val reason: String?,
    val trajectoryTitle: String?,
    val trajectoryMetrics: String?,
    /** False on a phone that does not loop (a client): the "Run loop" button is then not shown. */
    val canRunLoop: Boolean,
)

/** Today's readings in five bands, in percent, with the average and the estimated A1C (GMI). */
@Immutable
data class AimiDashboardTir(
    val veryLow: Double,
    val low: Double,
    val target: Double,
    val high: Double,
    val veryHigh: Double,
    val averageMgdl: Double,
    val a1c: Double,
)

/** Pure rules behind [AimiDashboardViewModel], kept apart so they can be tested without a database. */
internal object AimiDashboardRules {

    private const val SAFETY_CRITICAL_BG = 70.0
    private const val SAFETY_LIMITED_BG = 90.0
    private const val PREDICTION_HORIZON_MINUTES = 4 * 60L
    private const val PREDICTION_MIN_MINUTES = 2 * 60L
    private const val PULSE_MAX_LENGTH = 220

    enum class SafetyLevel { CRITICAL, LIMITED, NORMAL }

    fun safetyLevel(bgMgdl: Double): SafetyLevel = when {
        bgMgdl < SAFETY_CRITICAL_BG -> SafetyLevel.CRITICAL
        bgMgdl < SAFETY_LIMITED_BG  -> SafetyLevel.LIMITED
        else                        -> SafetyLevel.NORMAL
    }

    /**
     * Five bands as on DEV: very low < 54, low 54-70, in range 70-180, high 180-250, very high > 250
     * mg/dL. A1C is the GMI estimate (mean + 46.7) / 28.7. Null without readings.
     */
    fun tir(valuesMgdl: List<Double>): AimiDashboardTir? {
        if (valuesMgdl.isEmpty()) return null
        val count = valuesMgdl.size.toDouble()
        val mean = valuesMgdl.average()
        return AimiDashboardTir(
            veryLow = valuesMgdl.count { it < 54.0 } / count * 100.0,
            low = valuesMgdl.count { it >= 54.0 && it < 70.0 } / count * 100.0,
            target = valuesMgdl.count { it in 70.0..180.0 } / count * 100.0,
            high = valuesMgdl.count { it > 180.0 && it <= 250.0 } / count * 100.0,
            veryHigh = valuesMgdl.count { it > 250.0 } / count * 100.0,
            averageMgdl = mean,
            a1c = (mean + 46.7) / 28.7,
        )
    }

    /** Minutes ahead of the last prediction point; 4 h when it is not in the future, at least 2 h. */
    fun predictionHorizonMinutes(terminalTimestamp: Long, now: Long): Long {
        val horizon = if (terminalTimestamp > now) ((terminalTimestamp - now) / 60_000L).coerceAtLeast(1L) else PREDICTION_HORIZON_MINUTES
        return maxOf(PREDICTION_MIN_MINUTES, horizon)
    }

    /** The AIMI reason is HTML; the card shows it as one plain line. */
    fun plainText(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun clip(plain: String, maxLength: Int = PULSE_MAX_LENGTH): String =
        if (plain.length <= maxLength) plain else plain.take(maxLength - 1).trimEnd() + "…"

    fun trendSymbol(arrow: TrendArrow?): String = when (arrow) {
        TrendArrow.DOUBLE_DOWN     -> "⬇⬇"
        TrendArrow.SINGLE_DOWN     -> "⬇️"
        TrendArrow.FORTY_FIVE_DOWN -> "↘️"
        TrendArrow.FORTY_FIVE_UP   -> "↗️"
        TrendArrow.SINGLE_UP       -> "⬆️"
        TrendArrow.DOUBLE_UP       -> "⬆⬆"
        else                       -> "→"
    }

    /** The trajectory types AIMI publishes in `RT.trajectoryType`; an unknown name has no label. */
    fun trajectoryLabel(type: String?): TextRef? = when (type) {
        "OPEN_DIVERGING"     -> UiStrings.dashboard_trajectory_open_diverging
        "SLOW_DRIFT"         -> UiStrings.dashboard_trajectory_slow_drift
        "CLOSING_CONVERGING" -> UiStrings.dashboard_trajectory_closing_converging
        "TIGHT_SPIRAL"       -> UiStrings.dashboard_trajectory_tight_spiral
        "STABLE_ORBIT"       -> UiStrings.dashboard_trajectory_stable_orbit
        "HOVERING"           -> UiStrings.dashboard_trajectory_hovering
        "UNCERTAIN"          -> UiStrings.dashboard_trajectory_uncertain
        else                 -> null
    }

    private val MODE_KEYWORDS = listOf(
        "meal" to UiStrings.dashboard_mode_meal,
        "bfast" to UiStrings.dashboard_mode_breakfast,
        "lunch" to UiStrings.dashboard_mode_lunch,
        "dinner" to UiStrings.dashboard_mode_dinner,
        "highcarb" to UiStrings.dashboard_mode_highcarb,
        "lowcarb" to UiStrings.dashboard_mode_lowcarb,
        "snack" to UiStrings.dashboard_mode_snack,
        "sport" to UiStrings.dashboard_mode_sport,
        "sleep" to UiStrings.dashboard_mode_sleep,
    )

    /** The mode a note starts, by the same keywords as DEV, first match wins. */
    fun modeForNote(note: String?): TextRef? =
        note?.let { text -> MODE_KEYWORDS.firstOrNull { text.contains(it.first, ignoreCase = true) }?.second }
}
