package app.aaps.plugins.main.general.dashboard.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupProvider
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioDataRepositoryMTR
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard // 🌀 Trajectory
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine // 🧠 Engine
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualitySnapshot
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.data.time.T
import app.aaps.core.objects.extensions.directionToLegacyDrawable
import app.aaps.core.objects.extensions.displayText
import java.io.Serializable
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.objects.extensions.toStringFull
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.plugins.main.R
import app.aaps.core.objects.overview.DashboardCoherentGlucose
import androidx.core.text.HtmlCompat
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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
    private val preferences: Preferences,
    private val overviewData: OverviewData,
    private val trajectoryGuard: TrajectoryGuard, // 🌀 Trajectory Injection
    private val autodriveEngine: AutodriveEngine, // 🧠 Engine Injection
    private val aimiPhysioDataRepository: AIMIPhysioDataRepositoryMTR
) : ViewModel() {

    private val disposables = CompositeDisposable()
    private var started = false
    /** Cancelled in [stop]; Rx + DB observation for dashboard updates run here (same pattern as OverviewFragment flows). */
    private var updateScope: CoroutineScope? = null

    /**
     * [updateStatus] runs suspend DB reads on [Dispatchers.IO]. Without serialization, overlapping runs can
     * finish out of order and the last [MutableLiveData.postValue] may apply an older snapshot (stale reservoir,
     * activity %, TBR, steps, HR, etc.).
     */
    private val updateMutex = Mutex()

    /**
     * Coalesces high-frequency overview signals (RM/EPS/TE, bucketed BG, pump, loop GUI, …) into a single
     * [updateStatus] after a short quiet window. Cancelling the previous job avoids piling N full dashboard
     * recomputes (TIR-of-day, steps, HR, …) under [updateMutex] when the bus chatters — the main cause of
     * persistent “frozen” hybrid UI while work catches up.
     *
     * Use [launchUpdate] (no debounce) for [refreshAll] and any path that must run immediately with other work
     * in the same mutex block.
     */
    private var debouncedStatusJob: Job? = null

    private fun scheduleDebouncedStatusRefresh(debounceMs: Long = 120L) {
        val scope = updateScope ?: return
        debouncedStatusJob?.cancel()
        debouncedStatusJob = scope.launch {
            delay(debounceMs)
            updateMutex.withLock {
                updateStatus()
            }
        }
    }

    private fun launchUpdate(block: suspend () -> Unit) {
        updateScope?.launch {
            updateMutex.withLock {
                block()
            }
        }
    }

    private val _statusCardState = MutableLiveData<StatusCardState>()
    val statusCardState: LiveData<StatusCardState> = _statusCardState
    @Volatile private var latestStatusCardState: StatusCardState? = null

    private val _adjustmentState = MutableLiveData<AdjustmentCardState>()
    val adjustmentState: LiveData<AdjustmentCardState> = _adjustmentState

    private val _graphMessage = MutableLiveData<String>()
    val graphMessage: LiveData<String> = _graphMessage

    /**
     * One-shot user feedback for a staging-sensor promotion (result message). Hoisted to the Compose
     * layer (a background ViewModel has no Compose tree) — the dashboard collects and shows a toast.
     */
    private val _promotionEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val promotionEvents = _promotionEvents.asSharedFlow()

    // Adaptive smoothing quality (informational, used only for UI badge in phase 1)
    private var adaptiveSmoothingQualityTier: AdaptiveSmoothingQualityTier? = null
    private var adaptiveSmoothingQualityBadgeText: String = ""
    private var adaptiveSmoothingQualityDialogMessage: String = ""

    private fun buildAdaptiveSmoothingBadgeText(tier: AdaptiveSmoothingQualityTier): String = when (tier) {
        AdaptiveSmoothingQualityTier.OK -> resourceHelper.gs(R.string.adaptive_smoothing_quality_badge_ok)
        AdaptiveSmoothingQualityTier.UNCERTAIN -> resourceHelper.gs(R.string.adaptive_smoothing_quality_badge_uncertain)
        AdaptiveSmoothingQualityTier.BAD -> resourceHelper.gs(R.string.adaptive_smoothing_quality_badge_bad)
    }

    private fun buildAdaptiveSmoothingDialogMessage(snap: AdaptiveSmoothingQualitySnapshot): String {
        val learnedR = snap.learnedR
        val outlierPct = (snap.outlierRate * 100.0).toInt()
        val compressionPct = (snap.compressionRate * 100.0).toInt()
        return when (snap.tier) {
            AdaptiveSmoothingQualityTier.OK -> context.getString(
                R.string.adaptive_smoothing_quality_dialog_ok,
                learnedR,
                outlierPct,
                compressionPct
            )
            AdaptiveSmoothingQualityTier.UNCERTAIN -> context.getString(
                R.string.adaptive_smoothing_quality_dialog_uncertain,
                learnedR,
                outlierPct,
                compressionPct
            )
            AdaptiveSmoothingQualityTier.BAD -> context.getString(
                R.string.adaptive_smoothing_quality_dialog_bad,
                learnedR,
                outlierPct,
                compressionPct
            )
        }
    }

    /** Sync badge fields from smoothing plugin (avoids missed Rx events / scheduler ordering). */
    private fun refreshAdaptiveSmoothingQualityFromPlugin() {
        val snap = activePlugin.activeSmoothing.lastAdaptiveSmoothingQualitySnapshot()
        if (snap != null) {
            adaptiveSmoothingQualityTier = snap.tier
            adaptiveSmoothingQualityBadgeText = buildAdaptiveSmoothingBadgeText(snap.tier)
            adaptiveSmoothingQualityDialogMessage = buildAdaptiveSmoothingDialogMessage(snap)
        } else {
            adaptiveSmoothingQualityTier = null
            adaptiveSmoothingQualityBadgeText = ""
            adaptiveSmoothingQualityDialogMessage = ""
        }
    }

    fun start() {
        if (!started) {
            updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            started = true
            subscribeToUpdates()
            updateScope?.launch {
                while (true) {
                    delay(ADAPTATION_FRESHNESS_TICK_MS)
                    updateMutex.withLock {
                        refreshAimiAdaptationSummary()
                    }
                }
            }
        }
        // Always pull latest pump/IOB/loop state on resume — [started] can already be true after a shallow
        // pause, and loop completion often emits [EventLoopUpdateGui] without [EventRefreshOverview].
        refreshAll()
    }

    fun stop() {
        started = false
        disposables.clear()
        updateScope?.cancel()
        updateScope = null
    }

    override fun onCleared() {
        disposables.clear()
        updateScope?.cancel()
        updateScope = null
        super.onCleared()
    }

    private fun subscribeToUpdates() {
        val scope = updateScope ?: return

        disposables += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ refreshAll() }, fabricPrivacy::logException)

        // Match [OverviewFragment.onResume]: debounce BG bucket storms before refresh.
        disposables += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        // End of loop.invoke() — keeps hybrid dashboard in sync with classic overview when APS enacts changes.
        disposables += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateGraphMessage() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        // Same bus as OverviewFragment.updateSensitivity() — keeps activity % / AIMI metrics in sync after autosens refresh.
        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                if (it.isChanged(StringKey.OApsAIMIContextStorage.key) ||
                    it.isChanged(BooleanKey.OverviewShowWizardButton.key) ||
                    it.isChanged(UnitDoubleKey.OverviewLowMark.key) ||
                    it.isChanged(UnitDoubleKey.OverviewHighMark.key) ||
                    it.isChanged(BooleanNonKey.AutosensUsedOnMainPhone.key) ||
                    it.isChanged(DoubleKey.AutosensMax.key) ||
                    it.isChanged(DoubleKey.AutosensMin.key)
                ) {
                    refreshAll()
                }
            }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ refreshAll() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleDebouncedStatusRefresh() }, fabricPrivacy::logException)

        fun <T : Any> observePersistenceChanges(clazz: Class<T>, onEmit: suspend () -> Unit) {
            scope.launch {
                try {
                    persistenceLayer.observeChanges(clazz).collect {
                        try {
                            onEmit()
                        } catch (e: Exception) {
                            fabricPrivacy.logException(e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fabricPrivacy.logException(e)
                }
            }
        }
        observePersistenceChanges(TB::class.java) { updateAdjustments() }
        observePersistenceChanges(TT::class.java) { updateAdjustments() }
        observePersistenceChanges(EB::class.java) { updateAdjustments() }
        // Same persistence streams as [OverviewFragment.onResume] (EPS / RM → scheduleUpdateGUI / processAps).
        observePersistenceChanges(EPS::class.java) { scheduleDebouncedStatusRefresh() }
        observePersistenceChanges(RM::class.java) { scheduleDebouncedStatusRefresh() }
        // Cannula / sensor ages on the hybrid card
        observePersistenceChanges(TE::class.java) { scheduleDebouncedStatusRefresh() }

        // Generic CGM warm-up: refresh the hero when the active source starts/updates/ends a warm-up
        // (phase change, (re)connection, or first glucose → handoff). No-op for sources that don't
        // implement [CgmWarmupProvider]. Per-second countdown ticking is driven from the Compose layer.
        scope.launch {
            try {
                (activePlugin.activeBgSource as? CgmWarmupProvider)?.warmupStatus?.collect {
                    scheduleDebouncedStatusRefresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }

        // Generic dual-sensor lifecycle + staging: refresh the hero when the active source reports a
        // production lifecycle change (early/end of life) or any staging-slot change (warm-up, settling,
        // ready). No-op for sources that don't implement [CgmSensorStatusProvider]; the staging slot is
        // collect-only and never feeds the loop until an explicit promote.
        scope.launch {
            try {
                (activePlugin.activeBgSource as? CgmSensorStatusProvider)?.let { sensor ->
                    merge(
                        sensor.lifecycle.map { },
                        sensor.stagingState.map { },
                        sensor.stagingLifecycle.map { },
                        sensor.stagingWarmupStatus.map { },
                        sensor.stagingEvidence.map { },
                    ).collect {
                        scheduleDebouncedStatusRefresh()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }

        // Same preference-driven refresh as overview [merge(...).onEach { scheduleUpdateGUI() }].
        scope.launch {
            try {
                merge(
                    preferences.observe(BooleanKey.OverviewShowWizardButton).drop(1).map { },
                    preferences.observe(UnitDoubleKey.OverviewLowMark).drop(1).map { },
                    preferences.observe(UnitDoubleKey.OverviewHighMark).drop(1).map { },
                    preferences.observe(BooleanNonKey.AutosensUsedOnMainPhone).drop(1).map { },
                    preferences.observe(DoubleKey.AutosensMax).drop(1).map { },
                    preferences.observe(DoubleKey.AutosensMin).drop(1).map { },
                ).collect {
                    refreshAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }
        // Steps / HR: coalesce bursts from sync (Health Connect, watch) then refresh hybrid metrics.
        scope.launch {
            try {
                persistenceLayer.observeChanges(HR::class.java)
                    .debounce(400L)
                    .collect {
                        try {
                            scheduleDebouncedStatusRefresh()
                        } catch (e: Exception) {
                            fabricPrivacy.logException(e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }
        scope.launch {
            try {
                persistenceLayer.observeChanges(SC::class.java)
                    .debounce(400L)
                    .collect {
                        try {
                            // New step buckets in DB → allow a fresh HC daily aggregate on next refresh.
                            aimiPhysioDataRepository.invalidateTodayStepsHcAggregateCache()
                            scheduleDebouncedStatusRefresh()
                        } catch (e: Exception) {
                            fabricPrivacy.logException(e)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }
    }

    private fun refreshAll() {
        launchUpdate {
            updateStatus()
            updateAdjustments()
            updateGraphMessage()
        }
    }

    private data class AimiAdaptationSummary(
        val text: String,
        val contentDescription: String,
        val hasAttention: Boolean,
    )

    private fun buildAimiAdaptationSummary(rt: RT?, now: Long): AimiAdaptationSummary {
        val adaptationState = AimiAdaptationStatusPresenter.present(
            status = rt?.aimiAdaptationStatus,
            now = now,
            staleAfterMs = T.mins(preferences.get(IntKey.AlertsStaleDataThreshold).toLong()).msecs(),
        )
        val summary = if (adaptationState.hasStatus) {
            resourceHelper.gs(
                R.string.aimi_adaptation_status_counts,
                adaptationState.activeCount,
                adaptationState.readyCount,
                adaptationState.waitingOrLearningCount,
                adaptationState.attentionCount,
            )
        } else {
            resourceHelper.gs(R.string.dashboard_aimi_adaptation_no_data)
        }
        return AimiAdaptationSummary(
            text = summary,
            contentDescription = resourceHelper.gs(R.string.dashboard_aimi_adaptation_a11y, summary),
            hasAttention = adaptationState.attentionCount > 0,
        )
    }

    private fun refreshAimiAdaptationSummary() {
        val currentState = latestStatusCardState ?: return
        val rt = loop.lastRun?.request?.rawData() as? RT
        val summary = buildAimiAdaptationSummary(rt, dateUtil.now())
        val refreshedState = currentState.copy(
            aimiAdaptationSummary = summary.text,
            aimiAdaptationContentDescription = summary.contentDescription,
            aimiAdaptationHasAttention = summary.hasAttention,
        )
        latestStatusCardState = refreshedState
        _statusCardState.postValue(refreshedState)
    }

    /**
     * Promote the active source's staging sensor to production (the ONLY action that swaps the loop's
     * glucose source — safety-critical). Runs on the update scope; the result message is emitted on
     * [promotionEvents] for the UI and the dashboard is refreshed so the staging card reflects the new
     * state. No-op when the active source has no staging capability.
     */
    fun promoteStaging() {
        val scope = updateScope ?: return
        val sensor = activePlugin.activeBgSource as? CgmSensorStatusProvider ?: return
        scope.launch {
            try {
                val message = when (val result = sensor.promoteStagingToProduction()) {
                    is PromotionResult.Ok       -> resourceHelper.gs(R.string.dashboard_staging_promote_ok)
                    is PromotionResult.Rejected -> resourceHelper.gs(promotionRejectReasonRes(result.reason))
                }
                _promotionEvents.emit(message)
                scheduleDebouncedStatusRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
            }
        }
    }

    private fun promotionRejectReasonRes(reason: PromotionRejectReason): Int = when (reason) {
        PromotionRejectReason.STAGING_ABSENT            -> R.string.dashboard_staging_promote_rejected_absent
        PromotionRejectReason.STAGING_NOT_SETTLED       -> R.string.dashboard_staging_promote_rejected_not_settled
        PromotionRejectReason.STAGING_NO_VALID_GLUCOSE  -> R.string.dashboard_staging_promote_rejected_no_glucose
        PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE -> R.string.dashboard_staging_promote_rejected_no_recent_glucose
        PromotionRejectReason.LOOP_BUSY                 -> R.string.dashboard_staging_promote_rejected_loop_busy
    }

    private suspend fun updateStatus() {
        refreshAdaptiveSmoothingQualityFromPlugin()
        val lastBg = lastBgData.lastBg()
        val now = dateUtil.now()
        val profile: EffectiveProfile? = profileFunction.getProfile()
        val gs = glucoseStatusProvider.glucoseStatusData
        val smoothing = activePlugin.activeSmoothing
        val displayMgdl = DashboardCoherentGlucose.displayMgdl(lastBg, gs, smoothing, now)
        val displayTs = DashboardCoherentGlucose.displayTimestamp(lastBg, gs, smoothing, now)
        val glucoseText = profileUtil.fromMgdlToStringInUnits(displayMgdl)
        val activeTemporaryTarget = persistenceLayer.getTemporaryTargetActiveAt(now)
        val targetRange = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = activeTemporaryTarget?.lowTarget,
            temporaryHigh = activeTemporaryTarget?.highTarget,
            profileLow = profile?.getTargetLowMgdl(now),
            profileHigh = profile?.getTargetHighMgdl(now),
            aimiTargetBg = loop.lastRun?.request?.targetBG,
        )
        val targetText = targetRange?.let { range ->
            profileUtil.toTargetRangeString(
                low = range.low,
                high = range.high,
                sourceUnits = GlucoseUnit.MGDL,
            )
        }
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)?.directionToLegacyDrawable()
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads) ?: ""
        val deltaMgdlForDisplay = when {
            gs == null -> null
            gs.shortAvgDelta.isFinite() -> gs.shortAvgDelta
            gs.delta.isFinite() -> gs.delta
            else -> null
        }
        val deltaText = deltaMgdlForDisplay?.let { v ->
            "Δ " + profileUtil.fromMgdlToSignedStringInUnits(v)
        } ?: ("Δ " + resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short))
        val bolusSnap = bolusIob()
        val basalSnap = basalIob()
        val iobText = formatDashboardIobHeader(bolusSnap, basalSnap)
        val cobText = iobCobCalculator.getCobInfo("Dashboard COB")
            .displayText(resourceHelper, decimalFormatter)
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val timeAgo = dateUtil.minAgoShort(displayTs)
        val timeAgoLong = dateUtil.minAgoLong(resourceHelper, displayTs)
        val displayBgDescription = DashboardCoherentGlucose.displayBgDescription(
            displayMgdl,
            profileFunction,
            preferences,
            resourceHelper
        )
        val contentDescription =
            resourceHelper.gs(R.string.a11y_blood_glucose) + " " +
                glucoseText + " " + displayBgDescription + " " + timeAgoLong


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
        val reservoirUnits = activePlugin.activePump.reservoirLevel.value.cU
        val reservoirText =
            if (reservoirUnits > 0) decimalFormatter.to2Decimal(reservoirUnits) + " IE"
            else null
        
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
        val scheduledBasalRateUh = profile?.getBasal(now)
        val basalText = scheduledBasalRateUh?.let { rate ->
            decimalFormatter.to2Decimal(rate) + " IE"
        }
        
        val activeTempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress }
        val effectiveBasalRateUh = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = activeTempBasal?.let { tbr ->
                DashboardV2StatusValueResolver.TemporaryBasal(
                    rate = tbr.rate,
                    isAbsolute = tbr.isAbsolute,
                )
            },
            scheduledBasalRateUh = scheduledBasalRateUh,
        )
        val effectiveBasalText = effectiveBasalRateUh?.let { rate ->
            resourceHelper.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, rate)
        }

        // 6. Activity % — delta vs scheduled basal during an active TBR (not the same framing as the "% of profile" on the TBR line).
        val activityPctText = activeTempBasal?.let { tbr ->
            profile?.let { p ->
                val currentBasal = p.getBasal(now)
                if (currentBasal > 0) {
                    if (tbr.rate <= 1e-4) {
                        resourceHelper.gs(R.string.dashboard_activity_tbr_basal_delivery_stopped)
                    } else {
                        val pct = ((tbr.rate / currentBasal) * 100 - 100).toInt()
                        val sign = if (pct >= 0) "+" else ""
                        "$sign$pct%"
                    }
                } else "0%"
            } ?: "0%"
        } ?: "0%"
        
        // 7. Pump Battery
        val pumpBatteryText = activePlugin.activePump.batteryLevel.value?.let { "$it%" }

        // 8. IOB (same formatting as the rest of AAPS — avoids mixing "IE" + locale-decimal vs treatment screens)
        val totalIobForSensorLine = bolusSnap.iob + basalSnap.basaliob
        val lastSensorValueText =
            if (totalIobForSensorLine >= 0) {
                resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIobForSensorLine)
            } else {
                resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units_signed, totalIobForSensorLine)
            }

        // 9. TBR: compact strip shows rate only (full line in étendu keeps % vs profil — long string was easy to clip in the horizontal chips row)
        val tbrRateCompactText = activeTempBasal?.let { tbr ->
            decimalFormatter.to2Decimal(tbr.rate) + " U/h"
        } ?: (decimalFormatter.to2Decimal(0.0) + " U/h")

        val tbrRateText = activeTempBasal?.let { tbr ->
            val rateUh = decimalFormatter.to2Decimal(tbr.rate) + " U/h"
            val pctStr = profile?.let { p ->
                val currentBasal = p.getBasal(now)
                if (currentBasal > 0) {
                    val pct = ((tbr.rate / currentBasal) * 100).toInt()
                    " ($pct%)"
                } else ""
            } ?: ""
            rateUh + pctStr
        } ?: (decimalFormatter.to2Decimal(0.0) + " U/h")

        // 10. Steps & HR
        var stepsText: String = "--"
        var hrText: String = "--"
        
        // 11. 24H Clinical Stats (TIR, CV, A1C)
        var cvText: String? = "CV --%"
        var tirVeryLow: Double? = null
        var tirLow: Double? = null
        var tirTarget: Double? = null
        var tirHigh: Double? = null
        var tirVeryHigh: Double? = null
        var avgBgMgdl: Double? = null
        var bgCv: Double? = null
        var a1c: Double? = null
        
        try {
            val from = dateUtil.beginOfDay(now) // Start of today (Midnight)
            
            // --- TODAY CLINICAL STATS COMPUTATION ---
            val bgsToday = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true)
            
            if (bgsToday.isNotEmpty()) {
                val values = bgsToday.map { it.value }

                val count = values.size.toDouble()
                
                tirVeryLow = (values.count { it < 54.0 } / count) * 100.0
                tirLow = (values.count { it in 54.0..69.99 } / count) * 100.0
                tirTarget = (values.count { it in 70.0..180.0 } / count) * 100.0
                tirHigh = (values.count { it in 180.01..250.0 } / count) * 100.0
                tirVeryHigh = (values.count { it > 250.0 } / count) * 100.0
                
                val mean = values.average()
                avgBgMgdl = mean
                
                val variance = values.map { (it - mean) * (it - mean) }.average()
                val stdDev = sqrt(variance)
                
                if (mean > 0) {
                    bgCv = (stdDev / mean) * 100.0
                    cvText = "CV ${decimalFormatter.to0Decimal(bgCv)}%"
                }
                
                // GMI / Estimated A1C Formula: (mean + 46.7) / 28.7
                a1c = (mean + 46.7) / 28.7
            }
            
            // --- Steps (Today): max(HC aggregate, persistence) when HC is in play ---
            // Persistence uses max per-device 5‑min buckets → cannot merge complementary phone+watch like
            // Health Connect / Santé, often ~1k low. HC [COUNT_TOTAL] can lag; taking the max of both
            // avoids sticking on whichever pipeline is behind (HC cache cleared on SC updates elsewhere).
            val persistenceSteps = computeDashboardPersistenceStepsToday(from, now)
            val hcTodaySteps = aimiPhysioDataRepository.fetchTodayStepsTotalAggregated(from, now)
            val chosenTotal: Double? = when {
                hcTodaySteps != null ->
                    max(
                        hcTodaySteps.toDouble().coerceAtLeast(0.0),
                        persistenceSteps.total.coerceAtLeast(0.0),
                    )
                persistenceSteps.hasRows -> persistenceSteps.total
                else -> null
            }
            stepsText = when {
                chosenTotal == null -> "--"
                chosenTotal > 1.0 -> decimalFormatter.to0Decimal(chosenTotal)
                else -> decimalFormatter.to0Decimal(0.0)
            }

            // Heart Rate (Average or Last)
            // Fix: Use 3h window + 15min buffer for overlapped records (Garmin), and ensure sorting
            val hrFrom = now - 3 * 60 * 60 * 1000
            val hrList = persistenceLayer.getHeartRatesFromTimeToTime(hrFrom - 15 * 60 * 1000, now)
                .sortedBy { it.timestamp }
            
            if (hrList.isNotEmpty()) {
                val lastHr = hrList.lastOrNull()?.beatsPerMinute
                if (lastHr != null && lastHr > 0) {
                    hrText = decimalFormatter.to0Decimal(lastHr)
                }
            }
        
        } catch (e: Exception) {
             e.printStackTrace()
        }

        val lastApsRequest = loop.lastRun?.request
        val aimiPulseTitle = buildAimiPulseTitle(loop.lastRun?.lastAPSRun)
        val aimiPulseSummary = buildAimiPulseSummary(lastApsRequest)
        val aimiPulseMeta = buildAimiPulseMeta(lastApsRequest)
        val aimiPulseHypoRisk = lastApsRequest?.isHypoRisk == true

        val tirStatsLine = if (avgBgMgdl != null && a1c != null) {
            val avgStr = profileUtil.fromMgdlToStringInUnits(avgBgMgdl)
            resourceHelper.gs(R.string.dashboard_tir_stats_format, avgStr, a1c)
        } else {
            resourceHelper.gs(R.string.dashboard_tir_stats_placeholder)
        }

        // Display-only: read RM from DB like [OverviewDataCacheImpl.updateRunningModeFromDatabase].
        // Avoid [Loop.runningMode] / [runningModePreCheck] here — they reconcile pump vs mode, may write RM,
        // emit [EventRefreshOverview], and amplify refresh work under [updateMutex] (dashboard can look "frozen").
        val loopRunningMode = persistenceLayer.getRunningModeActiveAt(now).mode

        // 12. AIMI Insights (Autodrive V3)
        val request = lastApsRequest
        val rt = request?.rawData() as? RT
        
        val t3cMinutes = rt?.trajectoryConvergenceETA ?: -1
        val insightT3c = if (t3cMinutes > 0) "🎯 ${t3cMinutes}m" else "🎯 --"
        
        val trajTypeName = rt?.trajectoryType
        val classification = TrajectoryType.entries.find { it.name == trajTypeName }
        val insightManoeuvre = classification?.let { "${it.emoji()} ${it.description()}" } ?: "🌀 --"
        
        val relevance = rt?.trajectoryRelevanceScore ?: 0.0
        val insightFactor = "⚡ x${decimalFormatter.to1Decimal(relevance)}"
        
        val healthScore = rt?.trajectoryHealth?.toDouble()?.div(100.0) ?: autodriveEngine.getHealthScore()
        val adaptationSummary = buildAimiAdaptationSummary(rt, now)

        // Generic CGM warm-up status of the active source (null for sources that don't implement the provider).
        val warmupStatus = (activePlugin.activeBgSource as? CgmWarmupProvider)?.warmupStatus?.value

        // Generic dual-sensor lifecycle + staging status (null / ABSENT for sources that don't implement it).
        val sensorStatus = activePlugin.activeBgSource as? CgmSensorStatusProvider
        val productionLifecycle = sensorStatus?.lifecycle?.value
        val stagingSlotState = sensorStatus?.stagingState?.value ?: StagingState.ABSENT
        val stagingLifecycleValue = sensorStatus?.stagingLifecycle?.value
        val stagingWarmupValue = sensorStatus?.stagingWarmupStatus?.value
        val stagingEvidenceValue = sensorStatus?.stagingEvidence?.value

        val state = StatusCardState(
            glucoseText = glucoseText,
            glucoseColor = DashboardCoherentGlucose.displayBgColor(
                context,
                displayMgdl,
                profileFunction,
                preferences,
                resourceHelper
            ),
            trendArrowRes = trendArrow,
            trendDescription = trendDescription,
            deltaText = deltaText,
            iobText = iobText,
            cobText = cobText,
            loopStatusText = loopStatusText(loopRunningMode),
            loopIsRunning = !loopRunningMode.pausesLoopExecution(),
            timeAgo = timeAgo,
            timeAgoDescription = timeAgoLong,
            isGlucoseActual = DashboardCoherentGlucose.isDisplayActual(
                lastBg,
                gs,
                smoothing,
                now,
                T.mins(9).msecs()
            ),
            contentDescription = contentDescription,
            pumpStatusText = buildPumpAdjustmentDisplay(dateUtil.now()).lineHtml,
            predictionText = buildPredictionLine(dateUtil.now()),
            unicornImageRes = selectUnicornImage(  // 🦄 Dynamic unicorn image
                bg = displayMgdl,
                delta = glucoseStatusProvider.glucoseStatusData?.delta
            ),
            isAimiContextActive = preferences.get(app.aaps.core.keys.StringKey.OApsAIMIContextStorage).length > 5,
            // For GlucoseCircleView
            glucoseValue = displayMgdl,
            targetLow = profile?.getTargetLowMgdl(),
            targetHigh = profile?.getTargetHighMgdl(),
            targetText = targetText,
            isTempTargetActive = activeTemporaryTarget != null,
            unitText = profileFunction.getUnits().displayLabel,

            // Circle-Top Hybrid Dashboard fields
            glucoseMgdl = displayMgdl?.toInt(),
            noseAngleDeg = noseAngleDeg,
            reservoirText = reservoirText,
            infusionAgeText = infusionAgeText,
            pumpBatteryText = pumpBatteryText,
            sensorAgeText = sensorAgeText,
            lastSensorValueText = lastSensorValueText,
            activityPctText = activityPctText,
            tbrRateText = tbrRateText,
            tbrRateCompactText = tbrRateCompactText,
            basalText = basalText,
            effectiveBasalText = effectiveBasalText,
            stepsText = stepsText,
            hrText = hrText,
            cvText = cvText,
            
            // 24H TIR Clinical Stats
            tirVeryLow = tirVeryLow,
            tirLow = tirLow,
            tirTarget = tirTarget,
            tirHigh = tirHigh,
            tirVeryHigh = tirVeryHigh,
            avgBgMgdl = avgBgMgdl,
            bgCv = bgCv,
            a1c = a1c,
            tirStatsLine = tirStatsLine,
            
            // AIMI Insights
            insightT3c = insightT3c,
            insightManoeuvre = insightManoeuvre,
            insightFactor = insightFactor,
            trajectoryRelevanceScore = relevance,
            aimiHealthScore = healthScore,
            aimiAdaptationSummary = adaptationSummary.text,
            aimiAdaptationContentDescription = adaptationSummary.contentDescription,
            aimiAdaptationHasAttention = adaptationSummary.hasAttention,

            aimiPulseTitle = aimiPulseTitle,
            aimiPulseSummary = aimiPulseSummary,
            aimiPulseMeta = aimiPulseMeta,
            aimiPulseHypoRisk = aimiPulseHypoRisk,

            adaptiveSmoothingQualityTier = adaptiveSmoothingQualityTier,
            adaptiveSmoothingQualityBadgeText = adaptiveSmoothingQualityBadgeText,
            adaptiveSmoothingQualityDialogMessage = adaptiveSmoothingQualityDialogMessage,
            warmup = warmupStatus,
            lifecycle = productionLifecycle,
            stagingState = stagingSlotState,
            stagingLifecycle = stagingLifecycleValue,
            stagingWarmup = stagingWarmupValue,
            stagingEvidence = stagingEvidenceValue,
        )
        latestStatusCardState = state
        _statusCardState.postValue(state)
    }

    /**
     * Calculates total IOB text for display.
     *
     * CRITICAL FIX: Removed abs() that was causing IOB to appear increasing
     * when basal IOB was negative (during low TBR).
     *
     * Total IOB can be negative (insulin debt from low TBR), which is valid
     * and important clinical information to display.
     */
    private suspend fun totalIobText(): String =
        formatDashboardIobHeader(bolusIob(), basalIob())

    /** Shared by [updateStatus] (single IOB fetch) and [totalIobText]. */
    private fun formatDashboardIobHeader(bolus: IobTotal, basal: IobTotal): String {
        val total = bolus.iob + basal.basaliob
        val formattedTotal = if (total >= 0) {
            resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, total)
        } else {
            "-" + resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, -total)
        }
        return "IOB: $formattedTotal"
    }

    private suspend fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()

    private suspend fun basalIob(): IobTotal =
        iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()

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

    private suspend fun updateAdjustments() {
        val now = dateUtil.now()
        val lastBg = lastBgData.lastBg()
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val displayMgdl = DashboardCoherentGlucose.displayMgdl(
            lastBg,
            glucoseStatus,
            activePlugin.activeSmoothing,
            now
        )
        val adjustments = buildActiveAdjustments(now)
        val pumpDisplay = buildPumpAdjustmentDisplay(now)
        val state = AdjustmentCardState(
            glycemiaLine = buildGlycemiaLine(displayMgdl, trendArrow, glucoseStatus),
            predictionLine = buildPredictionLine(now),
            iobActivityLine = buildIobActivityLine(),
            decisionLine = buildDecisionLine(),
            pumpLine = pumpDisplay.lineHtml,
            pumpReservoirPlain = pumpDisplay.reservoirPlain,
            pumpSitePlain = pumpDisplay.sitePlain,
            pumpSensorPlain = pumpDisplay.sensorPlain,
            safetyLine = buildSafetyLine(displayMgdl, glucoseStatus),
            modeLine = resolveModeLine(now),
            adjustments = adjustments,
            reason = buildDecisionLine(),
            // Populate new fields
            peakTime = loop.lastRun?.request?.oapsProfileAimi?.peakTime,
            dia = loop.lastRun?.request?.oapsProfileAimi?.dia,
            // The actual AIMI-adjusted target (RT.targetBG), not oapsProfileAimi.target_bg — that field is
            // the raw INPUT profile target AIMI started its computation from, not what it ended up
            // targeting after its own PKPD adjustments (same bug as the Glass dashboard's Target pill).
            // targetBG defaults to 0.0 when a loop ran but never set it — not a real target, show "--" instead.
            targetBg = loop.lastRun?.request?.targetBG?.takeIf { it > 0.0 },
            smb = loop.lastRun?.request?.smb,
            basal = loop.lastRun?.request?.rate,
            detailedReason = loop.lastRun?.request?.reason,
            isHypoRisk = loop.lastRun?.request?.isHypoRisk ?: false,
            // 🌀 Trajectory Visualization
            trajectoryTitle = (loop.lastRun?.request?.rawData() as? RT)?.trajectoryType?.let { name ->
                val type = TrajectoryType.entries.find { it.name == name }
                type?.let { "${it.emoji()} ${it.name}" } ?: name
            },
            trajectoryAscii = (loop.lastRun?.request?.rawData() as? RT)?.trajectoryType?.let { name ->
                TrajectoryType.entries.find { it.name == name }?.asciiArt()
            },
            trajectoryMetrics = (loop.lastRun?.request?.rawData() as? RT)?.let { r ->
                "κ=%.3f  E=%.1fU  Θ=%.2f  R=%.2f".format(r.trajectoryCurvature ?: 0.0, r.trajectoryEnergy ?: 0.0, r.trajectoryOpenness ?: 0.0, r.trajectoryRelevanceScore ?: 0.0)
            },
            trajectoryRelevance = (loop.lastRun?.request?.rawData() as? RT)?.trajectoryRelevanceScore
        )
        _adjustmentState.postValue(state)
    }

    private fun updateGraphMessage() {
        val message = resourceHelper.gs(R.string.dashboard_graph_updated, dateUtil.timeString(dateUtil.now()))
        _graphMessage.postValue(message)
    }

    private suspend fun buildActiveAdjustments(now: Long): List<String> {
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
        displayMgdl: Double?,
        trendArrow: TrendArrow?,
        glucoseStatus: GlucoseStatus?
    ): String {
        val glucoseText = profileUtil.fromMgdlToStringInUnits(displayMgdl)
        val deltaMgdl = when {
            glucoseStatus == null -> null
            glucoseStatus.shortAvgDelta.isFinite() -> glucoseStatus.shortAvgDelta
            glucoseStatus.delta.isFinite() -> glucoseStatus.delta
            else -> null
        }
        val deltaText = deltaMgdl?.let { "Δ " + profileUtil.fromMgdlToSignedStringInUnits(it) }
            ?: ("Δ " + resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short))
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
        val bestTerminal = predictions
            .filter { it.sourceSensor == SourceSensor.UAM_PREDICTION }
            .maxByOrNull { it.timestamp }
        val floorTerminal = predictions
            .filter { it.sourceSensor == SourceSensor.IOB_PREDICTION }
            .maxByOrNull { it.timestamp }
        if (bestTerminal == null && floorTerminal == null) {
            return resourceHelper.gs(R.string.dashboard_adjustment_prediction_unavailable)
        }
        val terminalTimestamp = maxOf(
            bestTerminal?.timestamp ?: 0L,
            floorTerminal?.timestamp ?: 0L,
        )
        val horizonMinutes = if (terminalTimestamp > now) {
            ((terminalTimestamp - now) / TimeUnit.MINUTES.toMillis(1)).coerceAtLeast(1L)
        } else {
            Constants.PREDICTION_GRAPH_HORIZON_HOURS * 60L
        }
        val displayHorizonMinutes = maxOf(Constants.PREDICTION_GRAPH_MIN_MINUTES.toLong(), horizonMinutes)
        val bestText = bestTerminal?.let { profileUtil.fromMgdlToStringInUnits(it.value) }
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val floorText = floorTerminal?.let { profileUtil.fromMgdlToStringInUnits(it.value) }
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        return resourceHelper.gs(
            R.string.dashboard_adjustment_scenario_projection,
            bestText,
            floorText,
            displayHorizonMinutes,
        )
    }

    private suspend fun buildIobActivityLine(): String {
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

    private fun buildAimiPulseTitle(lastApsRunMillis: Long?): String {
        val ts = lastApsRunMillis ?: return resourceHelper.gs(R.string.dashboard_aimi_pulse_title)
        if (ts <= 0L) return resourceHelper.gs(R.string.dashboard_aimi_pulse_title)
        val elapsed = (dateUtil.now() - ts).coerceAtLeast(0L)
        val age = dateUtil.age(elapsed, true, resourceHelper).trim()
        return resourceHelper.gs(R.string.dashboard_aimi_pulse_title_with_age, age)
    }

    private fun plainTextFromAimiReason(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun clipAimiReasonForPulse(plain: String, maxLen: Int = 220): String {
        val singleLine = plain.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (singleLine.length <= maxLen) return singleLine
        return singleLine.take(maxLen - 1).trimEnd() + "…"
    }

    private fun buildAimiPulseSummary(request: APSResult?): String {
        if (request == null) return resourceHelper.gs(R.string.dashboard_aimi_pulse_summary_none)
        val plain = plainTextFromAimiReason(request.reason)
        val core = when {
            plain.isNotBlank() -> clipAimiReasonForPulse(plain)
            else -> {
                val smbText = decimalFormatter.to2Decimal(request.smb)
                val basalText = if (request.rate == -1.0) "—" else decimalFormatter.to2Decimal(request.rate)
                resourceHelper.gs(R.string.dashboard_aimi_pulse_fallback, smbText, basalText)
            }
        }
        return if (request.isHypoRisk) {
            resourceHelper.gs(R.string.dashboard_aimi_pulse_hypo_prefix) + " " + core
        } else {
            core
        }
    }

    private fun buildAimiPulseMeta(request: APSResult?): String {
        if (request == null) return ""
        val smb = decimalFormatter.to2Decimal(request.smb)
        val basalDisplay = if (request.rate == -1.0) "—" else decimalFormatter.to2Decimal(request.rate) + " U/h"
        val sens = decimalFormatter.to0Decimal((request.autosensResult?.ratio ?: 1.0) * 100.0)
        return resourceHelper.gs(R.string.dashboard_aimi_pulse_meta, smb, basalDisplay, sens)
    }

    private data class PumpAdjustmentDisplay(
        val lineHtml: String,
        val reservoirPlain: String,
        val sitePlain: String,
        val sensorPlain: String,
    )

    private fun htmlSegmentToPlain(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

    private suspend fun buildPumpAdjustmentDisplay(now: Long): PumpAdjustmentDisplay {
        val reservoirUnits = activePlugin.activePump.reservoirLevel.value.cU
        val reservoirText =
            if (reservoirUnits > 0)
                resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, reservoirUnits)
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

        val lastConnection = activePlugin.activePump.lastDataTime.value
        val threshold = T.mins(preferences.get(IntKey.AlertsPumpUnreachableThreshold).toLong()).msecs()
        val isUnreachable =
            preferences.get(app.aaps.core.keys.BooleanKey.AlertPumpUnreachable) && (lastConnection + threshold < now)

        val statusText = if (isUnreachable) {
            val unreachableText = resourceHelper.gs(app.aaps.core.ui.R.string.pump_unreachable)
            "$reservoirText <font color='#FF0000'>$unreachableText</font>"
        } else {
            reservoirText
        }

        val lineHtml = resourceHelper.gs(R.string.dashboard_adjustment_pump, statusText, siteAge, sensorAge)
        return PumpAdjustmentDisplay(
            lineHtml = lineHtml,
            reservoirPlain = htmlSegmentToPlain(statusText),
            sitePlain = htmlSegmentToPlain(siteAge),
            sensorPlain = htmlSegmentToPlain(sensorAge),
        )
    }

    private fun buildSafetyLine(displayMgdl: Double?, glucoseStatus: GlucoseStatus?): String {
        val bgValue = displayMgdl
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

    private suspend fun resolveModeLine(now: Long): String? {
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

    private data class DashboardPersistenceStepsToday(
        val hasRows: Boolean,
        val total: Double,
    )

    /**
     * Same 5‑min bucket / device pick as pre–Health Connect dashboard (wear → Garmin → HC rows → phone).
     * Total is the maximum across devices only — it does not dedupe across devices like Health Connect / Santé;
     * the dashboard combines this with the HC aggregate by taking the maximum of the two when HC is available.
     */
    private suspend fun computeDashboardPersistenceStepsToday(from: Long, now: Long): DashboardPersistenceStepsToday {
        val stepsList = persistenceLayer.getStepsCountFromTimeToTime(from, now).sortedBy { it.timestamp }
        if (stepsList.isEmpty()) return DashboardPersistenceStepsToday(false, 0.0)

        fun isGarmin(device: String?): Boolean = device == "Garmin-Watchface"
        fun isHealthConnect(device: String?): Boolean = device == "HealthConnect"
        fun isPhone(device: String?): Boolean = device == "PhoneSensor"
        fun isWear(device: String?): Boolean =
            !device.isNullOrBlank() && !isGarmin(device) && !isHealthConnect(device) && !isPhone(device)

        val bestSource = stepsList
            .firstOrNull { isWear(it.device) }?.device
            ?: stepsList.firstOrNull { isGarmin(it.device) }?.device
            ?: stepsList.firstOrNull { isHealthConnect(it.device) }?.device
            ?: stepsList.firstOrNull { isPhone(it.device) }?.device

        fun dedupedTotalForDevice(device: String): Double =
            stepsList
                .asSequence()
                .filter { it.device == device }
                .groupBy { it.timestamp / (5 * 60 * 1000L) }
                .values
                .sumOf { bucket -> bucket.maxOfOrNull { it.steps5min.coerceAtLeast(0) } ?: 0 }
                .toDouble()

        val totalsByDevice = stepsList
            .asSequence()
            .mapNotNull { it.device.takeUnless(String::isNullOrBlank) }
            .distinct()
            .associateWith { dedupedTotalForDevice(it) }

        val totalSteps = totalsByDevice
            .values
            .maxOrNull()
            ?: if (bestSource != null) dedupedTotalForDevice(bestSource) else 0.0

        return DashboardPersistenceStepsToday(true, totalSteps)
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
        private val preferences: Preferences,
        private val overviewData: OverviewData,
        private val trajectoryGuard: TrajectoryGuard, // 🌀 Add to Factory
        private val autodriveEngine: AutodriveEngine, // 🧠 Add to Factory
        private val aimiPhysioDataRepository: AIMIPhysioDataRepositoryMTR
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
                    preferences,
                    overviewData,
                    trajectoryGuard,
                    autodriveEngine,
                    aimiPhysioDataRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }

    private data class ModeKeyword(val token: String, val labelRes: Int)

    companion object {
        private const val SAFETY_LIMITED_BG = 90.0
        private const val SAFETY_CRITICAL_BG = 70.0
        private val ADAPTATION_FRESHNESS_TICK_MS = TimeUnit.MINUTES.toMillis(1)
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
    /** Active temporary target when present, otherwise profile target, in the user's glucose units. */
    val targetText: String? = null,
    /** True when a temporary target is currently active (not just when [targetText] is non-blank). */
    val isTempTargetActive: Boolean = false,
    /** Display label of the user's glucose unit preference ("mg/dL" or "mmol/L"). */
    val unitText: String = "mg/dL",

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
    /** TBR rate only (no % of profile) for the horizontal compact chip row. */
    val tbrRateCompactText: String? = null,
    val basalText: String? = null,
    /** Active TBR rate when present, otherwise scheduled profile basal; always formatted in U/h. */
    val effectiveBasalText: String? = null,
    val stepsText: String? = null,
    val hrText: String? = null,
    val cvText: String? = null,
    
    // 24H TIR Clinical Stats
    val tirVeryLow: Double? = null,
    val tirLow: Double? = null,
    val tirTarget: Double? = null,
    val tirHigh: Double? = null,
    val tirVeryHigh: Double? = null,
    val avgBgMgdl: Double? = null,
    val bgCv: Double? = null,
    val a1c: Double? = null,
    /** Pre-formatted TIR summary line (average BG uses current unit preference). */
    val tirStatsLine: String = "",
    
    // AIMI Insights
    val insightT3c: String? = null,
    val insightManoeuvre: String? = null,
    val insightFactor: String? = null,
    val trajectoryRelevanceScore: Double? = null,
    val aimiHealthScore: Double? = null,
    val aimiAdaptationSummary: String = "",
    val aimiAdaptationContentDescription: String = "",
    val aimiAdaptationHasAttention: Boolean = false,

    /** Narrative layer: plain-text excerpt of last APS `reason` + timing (AIMI pulse card). */
    val aimiPulseTitle: String = "",
    val aimiPulseSummary: String = "",
    val aimiPulseMeta: String = "",
    val aimiPulseHypoRisk: Boolean = false,

    // Adaptive Smoothing Quality Badge (phase 1: informational only)
    val adaptiveSmoothingQualityTier: AdaptiveSmoothingQualityTier? = null,
    val adaptiveSmoothingQualityBadgeText: String = "",
    val adaptiveSmoothingQualityDialogMessage: String = "",

    /**
     * Generic warm-up / (re)connection status of the active BG source, or null when the source does
     * not report one (or is READY / IDLE / FAILED). Populated from
     * `(activeBgSource as? CgmWarmupProvider)?.warmupStatus`. Drives the hero warm-up ring only when
     * there is no fresh glucose to show — never references any specific CGM vendor.
     */
    val warmup: CgmWarmupStatus? = null,

    /**
     * Production sensor lifecycle (early/end of life) of the active source, or null when the source
     * does not report one. Drives a small, non-alarming "beginning of life" / "expires soon" subtext
     * under the hero — the glucose ring itself stays normal.
     */
    val lifecycle: CgmSensorLifecycle? = null,

    /** Coarse staging-slot state of the active source; [StagingState.ABSENT] hides the staging card. */
    val stagingState: StagingState = StagingState.ABSENT,

    /** Staging sensor lifecycle (age → settle progress), or null when there is no staging sensor. */
    val stagingLifecycle: CgmSensorLifecycle? = null,

    /** Staging sensor warm-up status (countdown), or null when nothing to show. */
    val stagingWarmup: CgmWarmupStatus? = null,

    /** Proof that the staging sensor really sends data (reading count), or null when unknown. */
    val stagingEvidence: CgmStagingEvidence? = null,
)

data class AdjustmentCardState(
    val glycemiaLine: String,
    val predictionLine: String,
    val iobActivityLine: String,
    val decisionLine: String,
    val pumpLine: String,
    /** Plain-text segments for compact pump badges (same data as [pumpLine], without HTML). */
    val pumpReservoirPlain: String = "",
    val pumpSitePlain: String = "",
    val pumpSensorPlain: String = "",
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
    val isHypoRisk: Boolean = false,
    // 🌀 Trajectory Data
    val trajectoryTitle: String? = null,
    val trajectoryAscii: String? = null,
    val trajectoryMetrics: String? = null,
    val trajectoryRelevance: Double? = null
) : Serializable
