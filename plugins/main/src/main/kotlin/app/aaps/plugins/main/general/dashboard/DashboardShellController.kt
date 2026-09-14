package app.aaps.plugins.main.general.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.ui.compose.ScreenMode
import app.aaps.core.data.time.T
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.graph.data.ScaledDataPoint
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.TimeRange
import app.aaps.core.interfaces.overview.OverviewMenus.CharType
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileAdvisorActivity
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorNotificationManager
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusIndicator
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusLiveData
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.FragmentDashboardBinding
import app.aaps.plugins.main.general.dashboard.compose.DashboardHeroCommands
import app.aaps.plugins.main.general.dashboard.glass.GlassHeroCommands
import app.aaps.plugins.main.general.dashboard.viewmodel.AdjustmentCardState
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.manual.UserManualActivity
import app.aaps.plugins.main.general.overview.OverviewDataImpl
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.graphData.viewportShouldFollowLiveRange
import app.aaps.plugins.main.general.overview.notifications.NotificationUiBinder
import app.aaps.ui.compose.careDialog.CareportalEventType
import com.jjoe64.graphview.series.LineGraphSeries
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import javax.inject.Provider

/**
 * Shared AIMI dashboard UI + data pipeline for [DashboardFragment] and [AimiDashboardComposeRootView].
 */
internal class DashboardShellController(
    private val host: DashboardShellHost,
    private val deps: DashboardShellDeps,
    private val viewModel: OverviewViewModel,
    private val eventSourcePrefix: String,
) {

    private val preferences: Preferences get() = deps.preferences
    private val resourceHelper: ResourceHelper get() = deps.resourceHelper
    private val dateUtil: DateUtil get() = deps.dateUtil
    private val loop get() = deps.loop
    private val activePlugin get() = deps.activePlugin
    private val decimalFormatter get() = deps.decimalFormatter
    private val rxBus get() = deps.rxBus
    private val aapsSchedulers get() = deps.aapsSchedulers
    private val fabricPrivacy get() = deps.fabricPrivacy
    private val overviewData: OverviewDataImpl
        get() = deps.overviewData as? OverviewDataImpl
            ?: error("DashboardShellController requires OverviewDataImpl")
    private val overviewMenus get() = deps.overviewMenus
    private val graphDataProvider: Provider<GraphData> get() = deps.graphDataProvider
    private val config: Config get() = deps.config
    private val protectionCheck get() = deps.protectionCheck
    private val uiInteraction get() = deps.uiInteraction
    private val aapsLogger get() = deps.aapsLogger
    private val xDripSource get() = deps.xDripSource
    private val dexcomBoyda get() = deps.dexcomBoyda
    private val notificationUiBinder get() = deps.notificationUiBinder
    private val auditorStatusLiveData get() = deps.auditorStatusLiveData
    private val auditorNotificationManager get() = deps.auditorNotificationManager
    private val overviewDataCache get() = deps.overviewDataCache.get()
    private val persistenceLayer get() = deps.persistenceLayer

    private val disposables = CompositeDisposable()
    private var shellBinding: DashboardShellBinding? = null
    private var currentRange = 0
    private var auditorIndicator: AuditorStatusIndicator? = null
    private var graphViewportLayoutListener: View.OnLayoutChangeListener? = null
    private var forceGraphViewportReset = false
    private var lastGraphFormatRangeHours: Int? = null
    private var lastGraphBgLagMs: Long? = null
    private var graphRefreshJob: Job? = null
    private var overviewCacheMarkerJob: Job? = null
    private var markerDataRetryJob: Job? = null
    private var markerDataRetryAttempts = 0
    private var dbMarkerFallbackJob: Job? = null
    private var dbMarkerFallbackRange: Pair<Long, Long>? = null
    private var dbFallbackSmbMarkers: List<ChartSmbMarker> = emptyList()
    private var dbFallbackTbrMarkers: List<Long> = emptyList()
    private var dbFallbackTbrSegments: List<ChartTbrSegment> = emptyList()
    private var periodicOverviewRefreshJob: Job? = null
    private var embeddedLayoutSettleRefreshJob: Job? = null
    private var isHypoRiskDialogShowing = false
    private var uiPipelineAttachRetries = 0
    private var lastNonEmptyComposeGraphInput: DashboardEmbeddedComposeState.GraphRenderInput? = null

    private val heroCommands: DashboardHeroCommands by lazy { createHeroCommands() }
    private val glassHeroCommands: GlassHeroCommands by lazy { createGlassHeroCommands() }

    /**
     * Compose shell: [runDashboardUiAttachedSide] was invoked in bursts (activity lifecycle + replay),
     * cancelling the 60s periodic job before its first delay and flooding Rx — see user logs
     * (many `AimiDashboardComposeView.periodic` / `dataPipeline` in one frame).
     */
    private val coalescedUiPipelineRunnable: Runnable = object : Runnable {
        override fun run() {
            if (shellBinding == null || !config.appInitialized) return
            if (host.isBindingAttached()) {
                uiPipelineAttachRetries = 0
                runDashboardUiAttachedSide()
            } else if (host.embeddedInComposeMainShell() && uiPipelineAttachRetries++ < 60) {
                shellBinding?.root?.postDelayed(this, 16L)
            }
        }
    }

    private var pendingOverviewRefreshTag: String? = null
    private var pendingOverviewRefreshNow: Boolean = false
    private var pendingOverviewRefreshIncludeIob: Boolean = true

    private val overviewRefreshWhenSafeRunnable = Runnable {
        val tag = pendingOverviewRefreshTag ?: return@Runnable
        if (!host.isBindingAttached() || shellBinding == null || !config.appInitialized) return@Runnable
        pendingOverviewRefreshTag = null
        if (pendingOverviewRefreshIncludeIob) {
            activePlugin.activeOverview.overviewBus.send(EventUpdateOverviewIobCob(tag))
        }
        rxBus.send(EventRefreshOverview(tag, now = pendingOverviewRefreshNow))
    }

    fun attach(binding: FragmentDashboardBinding) {
        attachShell(DashboardShellBinding.fromFragmentDashboard(binding))
    }

    internal fun heroCommandsForCompose(): DashboardHeroCommands = heroCommands

    internal fun glassHeroCommandsForCompose(): GlassHeroCommands = glassHeroCommands

    fun attachShell(binding: DashboardShellBinding) {
        shellBinding = binding
        val attachLegacyGraphBackend = shouldAttachLegacyGraphBackend()
        binding.bottomNavigation?.let { bottomNav ->
            if (host.embeddedInComposeMainShell()) {
                bottomNav.visibility = View.GONE
            }
            bottomNav.selectedItemId = R.id.dashboard_nav_home
            bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.dashboard_nav_home -> true
                    R.id.dashboard_nav_history -> openHistory()
                    R.id.dashboard_nav_bolus -> openBolus()
                    R.id.dashboard_nav_adjustments -> openModes()
                    R.id.dashboard_nav_settings -> openSensorApp()
                    else -> true
                }
            }
        }

        binding.overviewNotifications?.layoutManager = LinearLayoutManager(host.context)

        syncGraphRange(preferences.get(IntNonKey.RangeToDisplay), false)
        host.embeddedComposeState?.updateGraphCommands(
            DashboardEmbeddedComposeState.GraphComposeCommands(
                onSelectRange = { hours -> syncGraphRange(hours) },
                onDoubleTap = {
                    val nextRange = when (overviewData.rangeToDisplay) {
                        6 -> 9
                        9 -> 12
                        12 -> 18
                        18 -> 24
                        else -> 6
                    }
                    syncGraphRange(nextRange)
                },
                onLongPress = { syncGraphRange(6) },
                onGraphPanFromDragFraction = graphPan@{ fraction ->
                    val embedded = host.embeddedComposeState ?: return@graphPan
                    val rangeMs = T.hours(overviewData.rangeToDisplay.toLong()).msecs()
                    embedded.accumulateGraphPanPastMs((fraction * rangeMs).toLong())
                    shellBinding?.root?.post { updateGraph() }
                },
            ),
        )
        host.embeddedComposeState?.updateGraphFreshnessConfig(
            graphFreshnessConfigFromPreferences(),
        )

        binding.statusCard?.let { sc ->
            sc.attachComposeHeroDependencies(preferences)
            viewModel.statusCardState.observe(host.liveDataOwner) { state ->
                sc.updateWithState(state)
            }
            sc.isClickable = true
            sc.isFocusable = true
            sc.setActionListener(heroCommands)
            sc.setOnClickListener { openLoopDialog() }
            sc.getLoopIndicator().setOnClickListener { openLoopDialog() }
            sc.getContextIndicator().setOnClickListener { launchContextActivity() }
        }
        viewModel.adjustmentState.observe(host.liveDataOwner) { state ->
            state?.let {
                binding.adjustmentStatus?.update(it)
                host.embeddedComposeState?.adjustmentCardState = it
                if (it.isHypoRisk) {
                    showHypoRiskDialog()
                }
            }
        }
        viewModel.graphMessage.observe(host.liveDataOwner) {
            binding.glucoseGraph?.setUpdateMessage(it)
            host.embeddedComposeState?.updateGraphMessage(it)
            scheduleGraphRefresh()
        }

        val runLoopAction: () -> Unit = {
            app.aaps.core.ui.toast.ToastUtils.infoToast(host.context, resourceHelper.gs(R.string.dashboard_loop_run_requested))
            host.liveDataOwner.lifecycleScope.launch {
                try {
                    loop.invoke("Dashboard", true)
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Error invoking loop from dashboard", e)
                }
            }
            Unit
        }
        binding.adjustmentStatus?.setOnClickListener { openAdjustmentDetails() }
        binding.adjustmentStatus?.setOnRunLoopClickListener { runLoopAction() }
        host.embeddedComposeState?.onOpenAdjustmentDetails = { openAdjustmentDetails() }
        host.embeddedComposeState?.onRunLoopRequested = runLoopAction

        if (attachLegacyGraphBackend) {
            setupDashboardGraphChrome()
            binding.glucoseGraph?.graph?.viewport?.isScrollable = true
            binding.glucoseGraph?.graph?.viewport?.isScalable = true
        }

        val isComposeShell = host.embeddedInComposeMainShell()
        val gestureDetector = if (isComposeShell) {
            null
        } else {
            android.view.GestureDetector(host.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val nextRange = when (overviewData.rangeToDisplay) {
                        6 -> 9
                        9 -> 12
                        12 -> 18
                        18 -> 24
                        else -> 6
                    }
                    syncGraphRange(nextRange)
                    return true
                }

                override fun onLongPress(e: android.view.MotionEvent) {
                    syncGraphRange(6)
                }
            })
        }

        if (attachLegacyGraphBackend) {
            val scrollParent = binding.nestedScrollView
            val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
            var panStartX = 0f
            var panStartY = 0f
            binding.glucoseGraph?.graph?.setOnTouchListener { _, event ->
                gestureDetector?.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        panStartX = event.x
                        panStartY = event.y
                        scrollParent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.x - panStartX)
                        val dy = abs(event.y - panStartY)
                        if (dx > dy + touchSlop) {
                            scrollParent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        scrollParent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        } else {
            binding.glucoseGraph?.graph?.setOnTouchListener(null)
        }

        if (attachLegacyGraphBackend) {
            bindDashboardGraphHeightToViewport()
        }

        if (host.embeddedInComposeMainShell() || !attachLegacyGraphBackend) {
            binding.glucoseGraph?.rangeButton?.visibility = View.GONE
        } else {
            binding.glucoseGraph?.rangeButton?.setOnClickListener { anchor ->
                val popup = PopupMenu(host.context, anchor)
                popup.menu.add(android.view.Menu.NONE, 6, android.view.Menu.NONE, host.context.getString(R.string.graph_long_scale_6h))
                popup.menu.add(android.view.Menu.NONE, 9, android.view.Menu.NONE, host.context.getString(R.string.graph_long_scale_9h))
                popup.menu.add(android.view.Menu.NONE, 12, android.view.Menu.NONE, host.context.getString(R.string.graph_long_scale_12h))
                popup.menu.add(android.view.Menu.NONE, 18, android.view.Menu.NONE, host.context.getString(R.string.graph_long_scale_18h))
                popup.menu.add(android.view.Menu.NONE, 24, android.view.Menu.NONE, host.context.getString(R.string.graph_long_scale_24h))
                popup.setOnMenuItemClickListener { item ->
                    syncGraphRange(item.itemId)
                    true
                }
                popup.show()
            }
        }

        setupAuditorIndicator()
    }

    fun start() {
        if (shellBinding == null || !config.appInitialized) return
        startDashboardDataPipeline()
    }

    fun resume() {
        val binding = shellBinding ?: return
        disposables.clear()
        updateContextBadge()
        binding.statusCard?.syncDashboardMetricsModeFromPreferences()
        host.embeddedComposeState?.requestMetricsPreferenceResync()
        if (config.appInitialized) {
            startDashboardDataPipeline()
        }
        val composeState = host.embeddedComposeState
        if (composeState != null) {
            notificationUiBinder.bindCompose(
                overviewBus = activePlugin.activeOverview.overviewBus,
                onSnapshot = { items -> composeState.notifications = items },
                disposable = disposables,
            )
            composeState.onDismissNotification = { id ->
                composeState.notifications = notificationUiBinder.dismissCompose(id)
            }
        } else {
            binding.overviewNotifications?.let { rv ->
                notificationUiBinder.bind(
                    overviewBus = activePlugin.activeOverview.overviewBus,
                    notificationsView = rv,
                    disposable = disposables,
                )
            }
        }
        disposables += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ event ->
                if (event.isChanged(IntNonKey.RangeToDisplay.key)) {
                    syncGraphRange(preferences.get(IntNonKey.RangeToDisplay), false)
                }
                if (event.isChanged(StringKey.OApsAIMIContextStorage.key)) {
                    updateContextBadge()
                }
                if (event.isChanged(BooleanKey.OverviewDashboardExtendedMetrics.key)) {
                    binding.statusCard?.syncDashboardMetricsModeFromPreferences()
                    host.embeddedComposeState?.requestMetricsPreferenceResync()
                }
                if (event.isChanged(IntKey.AlertsStaleDataThreshold.key)) {
                    host.embeddedComposeState?.updateGraphFreshnessConfig(graphFreshnessConfigFromPreferences())
                }
            }, fabricPrivacy::logException)
    }

    fun pause() {
        if (!host.embeddedInComposeMainShell()) {
            cancelDashboardRefreshJobs()
        }
    }

    fun stop() {
        cancelDashboardRefreshJobs()
        viewModel.stop()
        disposables.clear()
    }

    fun destroyView() {
        cancelDashboardRefreshJobs()
        shellBinding?.root?.let { root ->
            root.removeCallbacks(coalescedUiPipelineRunnable)
            root.removeCallbacks(overviewRefreshWhenSafeRunnable)
        }
        disposables.clear()
        graphViewportLayoutListener?.let { listener ->
            shellBinding?.let { b ->
                val target = b.nestedScrollView ?: b.root
                target.removeOnLayoutChangeListener(listener)
            }
        }
        graphViewportLayoutListener = null
        graphRefreshJob?.cancel()
        graphRefreshJob = null
        markerDataRetryJob?.cancel()
        markerDataRetryJob = null
        markerDataRetryAttempts = 0
        auditorIndicator?.stopAnimations()
        auditorIndicator = null
        host.embeddedComposeState?.adjustmentCardState = null
        host.embeddedComposeState?.notifications = emptyList()
        host.embeddedComposeState?.onOpenAdjustmentDetails = null
        host.embeddedComposeState?.onRunLoopRequested = null
        host.embeddedComposeState?.onDismissNotification = null
        host.embeddedComposeState?.resetGraphPan()
        host.embeddedComposeState?.resetVicoViewportFollowState()
        host.embeddedComposeState?.updateGraphCommands(DashboardEmbeddedComposeState.GraphComposeCommands())
        host.embeddedComposeState?.updateGraphMessage("")
        host.embeddedComposeState?.updateGraphRenderInput(
            GraphRenderInputFactory.build(
                rangeHours = 6,
                hasBgData = false,
                followLive = true,
                graphPanActive = false,
                lastRefreshEpochMs = 0L,
                fromTimeEpochMs = 0L,
                toTimeEpochMs = 0L,
                nowEpochMs = 0L,
                targetLowMgdl = null,
                targetHighMgdl = null,
                points = emptyList(),
                predictionPoints = emptyList(),
                smbMarkers = emptyList(),
                tbrMarkerEpochMs = emptyList(),
                tbrSegments = emptyList(),
            ),
        )
        shellBinding = null
    }

    /**
     * Mirrors the classic [app.aaps.plugins.main.general.overview.OverviewFragment] pattern: Rx/DB
     * subscriptions are re-armed on resume ([OverviewViewModel.start]); that must not be skipped
     * when [host.isBindingAttached] is still false for one frame (Compose [AndroidView] vs activity lifecycle).
     */
    private fun startDashboardDataPipeline() {
        if (shellBinding == null || !config.appInitialized) return
        viewModel.start()
        if (host.embeddedInComposeMainShell()) {
            scheduleRunDashboardUiAttachedSideCoalesced()
        } else if (host.isBindingAttached()) {
            runDashboardUiAttachedSide()
        } else {
            shellBinding?.root?.post { runDashboardUiAttachedSide() }
        }
    }

    private fun scheduleRunDashboardUiAttachedSideCoalesced() {
        val root = shellBinding?.root ?: return
        uiPipelineAttachRetries = 0
        root.removeCallbacks(coalescedUiPipelineRunnable)
        root.postDelayed(coalescedUiPipelineRunnable, 48L)
    }

    private fun runDashboardUiAttachedSide() {
        if (shellBinding == null || !config.appInitialized || !host.isBindingAttached()) return
        startDashboardPeriodicRefresh()
        subscribeOverviewCacheMarkerStreams()
        scheduleOverviewRefreshWhenSafe(
            tagSuffix = "dataPipeline",
            now = false,
            includeIobCob = true,
            extraDebounceMs = 0L,
        )
        if (host.embeddedInComposeMainShell()) {
            scheduleOverviewRefreshWhenSafe(
                tagSuffix = "embeddedResumeDeferred",
                now = false,
                includeIobCob = true,
                extraDebounceMs = EMBEDDED_RESUME_DEBOUNCE_MS,
            )
        }
    }

    private fun scheduleOverviewRefreshWhenSafe(
        tagSuffix: String,
        now: Boolean,
        includeIobCob: Boolean,
        extraDebounceMs: Long,
    ) {
        if (!config.appInitialized) return
        val root = shellBinding?.root
        pendingOverviewRefreshTag = "$eventSourcePrefix.$tagSuffix"
        pendingOverviewRefreshNow = now
        pendingOverviewRefreshIncludeIob = includeIobCob
        val deferMs = extraDebounceMs + DashboardOverviewRefreshGate.deferMsIfAimiTickActive()
        if (root == null) {
            if (deferMs == 0L) {
                overviewRefreshWhenSafeRunnable.run()
            }
            return
        }
        root.removeCallbacks(overviewRefreshWhenSafeRunnable)
        if (deferMs == 0L) {
            overviewRefreshWhenSafeRunnable.run()
        } else {
            root.postDelayed(overviewRefreshWhenSafeRunnable, deferMs)
        }
    }

    private fun cancelDashboardRefreshJobs() {
        periodicOverviewRefreshJob?.cancel()
        periodicOverviewRefreshJob = null
        embeddedLayoutSettleRefreshJob?.cancel()
        embeddedLayoutSettleRefreshJob = null
        overviewCacheMarkerJob?.cancel()
        overviewCacheMarkerJob = null
        shellBinding?.root?.removeCallbacks(overviewRefreshWhenSafeRunnable)
        pendingOverviewRefreshTag = null
    }

    /**
     * Overview fills [OverviewDataCache] treatment/basal flows after IOB calc (progress bar); the dashboard
     * must redraw when those arrive — Rx refresh alone is easy to miss on cold start.
     */
    private fun subscribeOverviewCacheMarkerStreams() {
        overviewCacheMarkerJob?.cancel()
        if (shouldAttachLegacyGraphBackend()) return
        overviewCacheMarkerJob = host.liveDataOwner.lifecycleScope.launch {
            launch {
                overviewDataCache.treatmentGraphFlow.collect {
                    scheduleGraphRefresh()
                }
            }
            launch {
                overviewDataCache.basalGraphFlow.collect {
                    scheduleGraphRefresh()
                }
            }
            launch {
                overviewDataCache.calcProgressFlow.collect {
                    scheduleGraphRefresh()
                }
            }
        }
    }

    private fun startDashboardPeriodicRefresh() {
        cancelDashboardRefreshJobs()
        periodicOverviewRefreshJob = host.liveDataOwner.lifecycleScope.launch {
            while (isActive) {
                if (host.isBindingAttached() && shellBinding != null && config.appInitialized) {
                    scheduleOverviewRefreshWhenSafe(
                        tagSuffix = "periodic",
                        now = false,
                        includeIobCob = false,
                        extraDebounceMs = 0L,
                    )
                }
                delay(DASHBOARD_PERIODIC_REFRESH_MS)
            }
        }
        if (host.embeddedInComposeMainShell()) {
            embeddedLayoutSettleRefreshJob = host.liveDataOwner.lifecycleScope.launch {
                delay(EMBEDDED_LAYOUT_SETTLE_REFRESH_MS)
                if (!host.isBindingAttached() || shellBinding == null) return@launch
                if (!config.appInitialized) return@launch
                scheduleOverviewRefreshWhenSafe(
                    tagSuffix = "embeddedLayoutSettle",
                    now = false,
                    includeIobCob = false,
                    extraDebounceMs = 0L,
                )
            }
        }
    }

    private fun updateContextBadge() {
        val binding = shellBinding ?: return
        try {
            val jsonStr = preferences.get(StringKey.OApsAIMIContextStorage)
            val hasContext = jsonStr.length > 5
            val embedded = host.embeddedComposeState
            if (embedded != null) {
                embedded.contextIndicatorVisible = hasContext
            } else {
                binding.statusCard?.getContextIndicator()?.visibility = if (hasContext) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to update context badge: ${e.message}")
        }
    }

    private fun graphAxisWidthPx(): Int = when {
        host.context.resources.displayMetrics.densityDpi <= 120 -> 3
        host.context.resources.displayMetrics.densityDpi <= 160 -> 10
        host.context.resources.displayMetrics.densityDpi <= 320 -> 35
        host.context.resources.displayMetrics.densityDpi <= 420 -> 50
        host.context.resources.displayMetrics.densityDpi <= 560 -> 70
        else -> 80
    }

    private fun setupDashboardGraphChrome() {
        val binding = shellBinding ?: return
        val graphView = binding.glucoseGraph ?: return
        val ctx = host.context
        val graph = graphView.graph
        graph.gridLabelRenderer?.labelVerticalWidth = graphAxisWidthPx()
        graph.gridLabelRenderer?.gridColor = resourceHelper.gac(ctx, app.aaps.core.ui.R.attr.graphGrid)
        graph.viewport.backgroundColor = resourceHelper.gac(ctx, app.aaps.core.ui.R.attr.viewPortBackgroundColor)
        graph.gridLabelRenderer?.reloadStyles()
    }

    private fun bindDashboardGraphHeightToViewport() {
        val binding = shellBinding ?: return
        val heightSource = binding.nestedScrollView ?: binding.root
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyDashboardGraphHeight(heightSource)
        }
        graphViewportLayoutListener = listener
        heightSource.addOnLayoutChangeListener(listener)
        heightSource.post { applyDashboardGraphHeight(heightSource) }
    }

    private fun applyDashboardGraphHeight(heightSource: View) {
        if (shellBinding == null) return
        val binding = shellBinding ?: return
        val graphView = binding.glucoseGraph ?: return
        val viewportH = heightSource.height
        if (viewportH <= 0) return
        val res = host.context.resources
        val minPx = res.getDimensionPixelSize(R.dimen.dashboard_graph_height_min)
        val maxPx = res.getDimensionPixelSize(R.dimen.dashboard_graph_height_max)
        val fraction = res.getFraction(R.fraction.dashboard_graph_viewport_fraction, 1, 1)
        val raw = (viewportH * fraction).toInt()
        val viewportCapPx = (viewportH * 0.50f).toInt()
        val effectiveMaxPx = minOf(maxPx, viewportCapPx)
        val effectiveMinPx = minOf(minPx, effectiveMaxPx)
        val target = raw.coerceIn(effectiveMinPx, effectiveMaxPx)
        val lp = graphView.layoutParams
        if (lp.height == target) return
        lp.height = target
        graphView.layoutParams = lp
        graphView.requestLayout()
        binding.root.post {
            if (shellBinding != null) {
                setupDashboardGraphChrome()
                scheduleGraphRefresh()
            }
        }
    }

    private fun scheduleGraphRefresh() {
        if (shellBinding == null || !host.isBindingAttached()) return
        graphRefreshJob?.cancel()
        graphRefreshJob = host.liveDataOwner.lifecycleScope.launch {
            delay(GRAPH_REFRESH_DEBOUNCE_MS)
            if (shellBinding != null && host.isBindingAttached()) {
                updateGraph()
            }
        }
    }

    private fun scheduleMarkerDataRetry() {
        if (markerDataRetryAttempts >= MARKER_DATA_RETRY_MAX_ATTEMPTS) return
        markerDataRetryJob?.cancel()
        markerDataRetryJob = host.liveDataOwner.lifecycleScope.launch {
            val delayMs = MARKER_DATA_RETRY_DELAY_MS * (markerDataRetryAttempts + 1)
            delay(delayMs)
            if (shellBinding == null || !host.isBindingAttached()) return@launch
            markerDataRetryAttempts += 1
            updateGraph()
        }
    }

    private fun handleAuditorClick() {
        val state = auditorIndicator?.getCurrentState() ?: return
        when (state.type) {
            AuditorUIState.StateType.READY,
            AuditorUIState.StateType.WARNING -> {
                auditorNotificationManager.openReport(host.context)
            }
            AuditorUIState.StateType.PROCESSING -> {
                uiInteraction.showOkDialog(
                    host.context,
                    host.context.getString(app.aaps.plugins.aps.R.string.aimi_auditor_report_dialog_title),
                    host.context.getString(app.aaps.plugins.aps.R.string.aimi_auditor_indicator_processing),
                )
            }
            AuditorUIState.StateType.ERROR -> {
                uiInteraction.showOkDialog(
                    host.context,
                    host.context.getString(app.aaps.core.ui.R.string.error),
                    state.statusMessage,
                )
            }
            else -> {
                uiInteraction.showOkDialog(
                    host.context,
                    host.context.getString(app.aaps.plugins.aps.R.string.aimi_auditor_report_dialog_title),
                    host.context.getString(app.aaps.plugins.aps.R.string.aimi_auditor_indicator_idle),
                )
            }
        }
    }

    private fun setupAuditorIndicator() {
        val binding = shellBinding ?: return
        try {
            aapsLogger.debug(LTag.CORE, "🔍 [Dashboard] Searching for Auditor badge...")
            val container = binding.auditorHost()
            aapsLogger.debug(LTag.CORE, "✅ [Dashboard] Badge container found!")
            auditorIndicator = AuditorStatusIndicator(host.context)
            container.removeAllViews()
            container.addView(auditorIndicator)
            auditorIndicator?.setOnClickListener {
                handleAuditorClick()
            }
            auditorStatusLiveData.uiState.observe(host.liveDataOwner) { uiState ->
                auditorIndicator?.setState(uiState)
                if (uiState.shouldNotify) {
                    auditorNotificationManager.showInsightAvailable(uiState)
                }
                container.visibility = View.VISIBLE
                aapsLogger.debug(LTag.CORE, "[Dashboard] Badge state: ${uiState.type}")
            }
            auditorStatusLiveData.forceUpdate()
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "[Dashboard] Badge setup error: ${e.message}", e)
        }
    }

    private fun openHistory(): Boolean {
        host.context.startActivity(Intent(host.context, UserManualActivity::class.java))
        return true
    }

    private fun openBolus(): Boolean {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED) uiInteraction.openInsulinScreen(activity)
            }
        }
        return true
    }

    private fun openModes(): Boolean {
        host.context.startActivity(Intent(host.context, DashboardModesActivity::class.java))
        return true
    }

    private fun openLoopDialog() {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED && host.isBindingAttached()) uiInteraction.openRunningModeScreen(activity)
            }
        }
    }

    /** Runs [action] only after a Protection.BOLUS check passes — same gate ElementType.CARBS /
     *  BOLUS_WIZARD / QUICK_WIZARD_MANAGEMENT / TEMP_TARGET_MANAGEMENT require everywhere else. */
    private fun withBolusProtection(action: (FragmentActivity) -> Unit) {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED) action(activity)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun openSensorApp(): Boolean {
        if (xDripSource.isEnabled()) return openCgmApp("com.eveningoutpost.dexdrip")
        if (dexcomBoyda.isEnabled()) {
            dexcomBoyda.dexcomPackages().forEach { if (openCgmApp(it)) return true }
        }
        return openModes()
    }

    @RequiresApi(Build.VERSION_CODES.CUPCAKE)
    private fun openCgmApp(packageName: String): Boolean {
        val ctx = host.context
        val packageManager = ctx.packageManager
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: throw ActivityNotFoundException()
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            aapsLogger.debug(LTag.CORE, "Error opening CGM app")
            false
        }
    }

    private fun openAdjustmentDetails(): Boolean {
        val state: AdjustmentCardState = viewModel.adjustmentState.value ?: return false
        val intent = Intent(host.context, AdjustmentDetailsActivity::class.java)
            .putExtra(AdjustmentDetailsActivity.EXTRA_ADJUSTMENT_STATE, state)
        host.context.startActivity(intent)
        return true
    }

    private fun syncGraphRange(hours: Int, userInitiated: Boolean = true) {
        val binding = shellBinding ?: return
        val clampedHours = when (hours) {
            6, 9, 12, 18, 24 -> hours
            else -> 6
        }
        if (!userInitiated && clampedHours == currentRange) {
            binding.glucoseGraph?.rangeButton?.text = overviewMenus.scaleString(clampedHours)
            return
        }

        currentRange = clampedHours
        host.embeddedComposeState?.resetGraphPan()
        host.embeddedComposeState?.bumpVicoViewportReset()
        host.embeddedComposeState?.resetVicoViewportFollowState()
        host.embeddedComposeState?.updateGraphRange(clampedHours)
        overviewData.rangeToDisplay = clampedHours
        overviewData.initRange()
        binding.glucoseGraph?.rangeButton?.text = overviewMenus.scaleString(clampedHours)
        preferences.put(IntNonKey.RangeToDisplay, clampedHours)
        preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
        forceGraphViewportReset = true
        rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
        if (userInitiated) {
            app.aaps.core.ui.toast.ToastUtils.infoToast(host.context, host.context.getString(R.string.graph_range_updated, clampedHours))
        }
    }

    private fun updateGraph() {
        val binding = shellBinding ?: return
        val menuChartSettings = overviewMenus.setting
        if (menuChartSettings.isEmpty()) return
        val composeState = host.embeddedComposeState
        val useComposeOnlyGraphPipeline = !shouldAttachLegacyGraphBackend()
        val now = dateUtil.now()
        val showPredictionsOnStrip = menuChartSettings[0][CharType.PRE.ordinal]

        val hasBgData = overviewData.bgReadingsArray.isNotEmpty()
        if (!useComposeOnlyGraphPipeline) {
            binding.glucoseGraph?.showPlaceholder(!hasBgData)
        }
        if (!hasBgData) {
            lastGraphBgLagMs = null
            val fallbackInput = lastNonEmptyComposeGraphInput
            val newestPointMs = fallbackInput?.points?.maxOfOrNull { it.timestampEpochMs } ?: 0L
            val snapshotHasPoints = newestPointMs > 0L
            val snapshotNotStale = !snapshotHasPoints ||
                (now - newestPointMs) <= BG_GRAPH_SNAPSHOT_MAX_POINT_AGE_MS
            val holdOk = fallbackInput != null &&
                (now - fallbackInput.lastRefreshEpochMs) <= BG_GRAPH_EMPTY_GAP_HOLD_MS
            val hasRecentFallback = fallbackInput != null && snapshotNotStale && (holdOk || snapshotHasPoints)
            if (hasRecentFallback) {
                val reused = fallbackInput!!.copy(
                    lastRefreshEpochMs = now,
                    nowEpochMs = now,
                )
                composeState?.updateGraphRenderInput(reused)
                lastNonEmptyComposeGraphInput = reused
                aapsLogger.debug(LTag.CORE, "Dashboard graph reused last BG snapshot during transient empty data gap")
            } else {
                composeState?.updateGraphRenderInput(
                    GraphRenderInputFactory.build(
                        rangeHours = overviewData.rangeToDisplay,
                        hasBgData = false,
                        followLive = true,
                        graphPanActive = false,
                        lastRefreshEpochMs = now,
                        fromTimeEpochMs = overviewData.fromTime,
                        toTimeEpochMs = overviewData.endTime,
                        nowEpochMs = now,
                        targetLowMgdl = preferences.get(UnitDoubleKey.OverviewLowMark),
                        targetHighMgdl = preferences.get(UnitDoubleKey.OverviewHighMark),
                        points = emptyList(),
                    ),
                )
                aapsLogger.debug(LTag.CORE, "Dashboard graph cleared: no BG data and no usable snapshot (hold=${holdOk}, stale=${!snapshotNotStale})")
            }
            return
        }

        val rangeChanged =
            lastGraphFormatRangeHours != null && lastGraphFormatRangeHours != overviewData.rangeToDisplay
        lastGraphFormatRangeHours = overviewData.rangeToDisplay

        val newestBgMs = overviewData.bgReadingsArray.maxOfOrNull { it.timestamp } ?: 0L
        val dataLagMs = (now - newestBgMs).coerceAtLeast(0L)
        val prevLag = lastGraphBgLagMs
        val staleGapRecovered =
            prevLag != null &&
                prevLag >= STALE_BG_LAG_MS &&
                dataLagMs <= prevLag - LAG_IMPROVEMENT_FOR_RECOVERY_MS
        lastGraphBgLagMs = dataLagMs

        val snapComposeViewportToLive = useComposeOnlyGraphPipeline &&
            (forceGraphViewportReset || rangeChanged || staleGapRecovered)

        val followLive = if (useComposeOnlyGraphPipeline) {
            forceGraphViewportReset || rangeChanged || staleGapRecovered
        } else {
            forceGraphViewportReset || rangeChanged || staleGapRecovered ||
                viewportShouldFollowLiveRange(binding.glucoseGraph?.graph ?: return, overviewData)
        }
        forceGraphViewportReset = false
        if (snapComposeViewportToLive) {
            composeState?.resetGraphPan()
            composeState?.bumpVicoViewportReset()
            composeState?.resetVicoViewportFollowState()
        }
        if (!useComposeOnlyGraphPipeline) {
            val graphView = binding.glucoseGraph ?: return
            val graphData = graphDataProvider.get().with(graphView.graph, overviewData)
            graphData.addInRangeArea(
                preferences.get(UnitDoubleKey.OverviewLowMark),
                preferences.get(UnitDoubleKey.OverviewHighMark),
            )
            graphData.addBgReadings(menuChartSettings[0][CharType.PRE.ordinal], host.context)
            graphData.addBucketedData()
            graphData.addTreatments(host.context)
            if ((config.AAPSCLIENT || activePlugin.activePump.pumpDescription.isTempBasalCapable) && menuChartSettings[0][CharType.BAS.ordinal]) {
                graphData.addBasals()
            }
            graphData.addTargetLine()
            graphData.addRunningModes()
            graphData.addNowLine(now)
            graphData.setNumVerticalLabels()
            graphData.formatAxis(overviewData.fromTime, overviewData.endTime, resetX = followLive)
            graphData.performUpdate(keepViewport = !followLive)
        }

        val rangeMs = T.hours(overviewData.rangeToDisplay.toLong()).msecs()
        // Pan limit must use the oldest *data* (and app graph horizon), not overviewData.fromTime —
        // fromTime is already the left edge of the current viewport, so min(fromTime, …) made maxPanPast ~0
        // and the compose graph could not scroll into history.
        val graphHorizonStartMs = overviewData.toTime - T.hours(Constants.GRAPH_TIME_RANGE_HOURS.toLong()).msecs()
        val oldestBgMs = overviewData.bgReadingsArray.minOfOrNull { it.timestamp }
        val dataEarliest =
            if (oldestBgMs != null) {
                min(oldestBgMs, graphHorizonStartMs)
            } else {
                graphHorizonStartMs
            }
        val maxPanPast = (overviewData.toTime - dataEarliest - rangeMs).coerceAtLeast(0L)
        if (useComposeOnlyGraphPipeline && composeState != null) {
            composeState.clampGraphPanPastMs(maxPanPast)
        }
        val panPastMs = if (useComposeOnlyGraphPipeline) composeState?.graphPanPastMs ?: 0L else 0L
        val visibleToEpoch = if (useComposeOnlyGraphPipeline) {
            overviewData.toTime - panPastMs
        } else {
            overviewData.endTime
        }
        val visibleFromEpoch = if (useComposeOnlyGraphPipeline) {
            visibleToEpoch - rangeMs
        } else {
            overviewData.fromTime
        }
        val predictionQueryFrom = min(visibleFromEpoch, overviewData.fromTime)
        val minFutureHorizonMs = T.hours(Constants.PREDICTION_GRAPH_MIN_HOURS.toLong()).msecs()
        val predictionFutureHorizonMs = max(
            minFutureHorizonMs,
            T.hours(Constants.PREDICTION_GRAPH_HORIZON_HOURS.toLong()).msecs(),
        )
        val predictionDisplayToEpoch = visibleToEpoch + predictionFutureHorizonMs
        val predictionQueryTo = max(overviewData.endTime, predictionDisplayToEpoch)
        val requestedCacheRange =
            if (useComposeOnlyGraphPipeline) {
                val warmupMarginMs = max(rangeMs, T.hours(6).msecs())
                TimeRange(
                    fromTime = visibleFromEpoch - warmupMarginMs,
                    toTime = visibleToEpoch,
                    endTime = predictionQueryTo,
                )
            } else {
                null
            }
        if (useComposeOnlyGraphPipeline && requestedCacheRange != null) {
            syncOverviewCacheRange(
                fromMs = requestedCacheRange.fromTime,
                toMs = requestedCacheRange.toTime,
                endMs = requestedCacheRange.endTime,
            )
        }

        val composePoints = overviewData.bgReadingsArray
            .asSequence()
            .filter { it.timestamp in visibleFromEpoch..visibleToEpoch }
            .map { DashboardEmbeddedComposeState.GraphPoint(timestampEpochMs = it.timestamp, value = it.value) }
            .toList()
        val predictionPoints = runCatching {
            val series = overviewData.predictionsGraphSeries as? PointsWithLabelGraphSeries<DataPointWithLabelInterface>
                ?: return@runCatching emptyList()
            series.getValues(predictionQueryFrom.toDouble(), predictionQueryTo.toDouble())
                .asSequence()
                .mapNotNull { point ->
                    point?.let {
                        DashboardEmbeddedComposeState.GraphPoint(
                            timestampEpochMs = it.x.toLong(),
                            value = it.y,
                        )
                    }
                }
                .filter {
                    it.timestampEpochMs >= visibleFromEpoch &&
                        it.timestampEpochMs <= predictionDisplayToEpoch
                }
                .toList()
        }.getOrElse { error ->
            aapsLogger.error(LTag.CORE, "Dashboard prediction extraction failed; using empty prediction series", error)
            emptyList()
        }
        val lastPredEpochMs = predictionPoints.maxOfOrNull { it.timestampEpochMs }
        // When predictions are enabled on the main chart, keep a fixed future band (see
        // predictionFutureHorizonMs) so a short prediction series does not collapse the X axis to ~1h.
        val graphDisplayToEpoch = if (showPredictionsOnStrip) {
            predictionDisplayToEpoch
        } else if (lastPredEpochMs != null) {
            max(visibleToEpoch, minOf(lastPredEpochMs, predictionDisplayToEpoch))
        } else {
            visibleToEpoch
        }
        // Compose dashboard: always read SMB/TBR from the same singleton [OverviewDataCache] flows as Overview.
        // (Previously gated on cache TimeRange alignment with the shell; that often failed and skipped cache reads.)
        var smbMarkers = emptyList<ChartSmbMarker>()
        var tbrMarkers = emptyList<Long>()
        var tbrSegments = emptyList<ChartTbrSegment>()
        if (useComposeOnlyGraphPipeline) {
            smbMarkers = extractSmbMarkersFromCache(visibleFromEpoch, visibleToEpoch)
            tbrMarkers = extractTbrChangeMarkerTimesFromBasalSteps(visibleFromEpoch, visibleToEpoch)
            tbrSegments = extractTbrSegmentsFromBasalSteps(visibleFromEpoch, visibleToEpoch)
            val needSmb = smbMarkers.isEmpty()
            val needTbr = tbrMarkers.isEmpty() && tbrSegments.isEmpty()
            if (needSmb || needTbr) {
                val fallbackRange = dbMarkerFallbackRange
                if (fallbackRange != null &&
                    fallbackRange.first == visibleFromEpoch &&
                    fallbackRange.second == visibleToEpoch
                ) {
                    if (needSmb && dbFallbackSmbMarkers.isNotEmpty()) {
                        smbMarkers = dbFallbackSmbMarkers
                    }
                    if (needTbr &&
                        (dbFallbackTbrMarkers.isNotEmpty() || dbFallbackTbrSegments.isNotEmpty())
                    ) {
                        tbrMarkers = dbFallbackTbrMarkers
                        tbrSegments = dbFallbackTbrSegments
                    }
                } else {
                    requestDbMarkerFallback(visibleFromEpoch, visibleToEpoch)
                }
            }
        } else {
            smbMarkers = extractSmbMarkers(visibleFromEpoch, visibleToEpoch)
            tbrMarkers = extractTbrChangeMarkerTimes(visibleFromEpoch, visibleToEpoch)
            tbrSegments = extractTbrSegments(visibleFromEpoch, visibleToEpoch)
        }
        val basalStepCount = overviewDataCache.basalGraphFlow.value.actualBasal.size
        aapsLogger.debug(
            LTag.CORE,
            "Dashboard strip markers smb=${smbMarkers.size} tbrSeg=${tbrSegments.size} tbrMark=${tbrMarkers.size} composeOnly=$useComposeOnlyGraphPipeline basalPts=$basalStepCount",
        )
        val markersFullyEmpty = smbMarkers.isEmpty() && tbrMarkers.isEmpty() && tbrSegments.isEmpty()
        if (useComposeOnlyGraphPipeline && markersFullyEmpty) {
            scheduleMarkerDataRetry()
        } else {
            markerDataRetryAttempts = 0
            markerDataRetryJob?.cancel()
            markerDataRetryJob = null
        }
        val graphPanActive = useComposeOnlyGraphPipeline && panPastMs > 0L
        val graphInput = GraphRenderInputFactory.build(
            rangeHours = overviewData.rangeToDisplay,
            hasBgData = true,
            followLive = followLive,
            graphPanActive = graphPanActive,
            lastRefreshEpochMs = now,
            fromTimeEpochMs = visibleFromEpoch,
            toTimeEpochMs = graphDisplayToEpoch,
            nowEpochMs = now,
            targetLowMgdl = preferences.get(UnitDoubleKey.OverviewLowMark),
            targetHighMgdl = preferences.get(UnitDoubleKey.OverviewHighMark),
            points = composePoints,
            predictionPoints = predictionPoints,
            smbMarkers = smbMarkers,
            tbrMarkerEpochMs = tbrMarkers,
            tbrSegments = tbrSegments,
        )
        composeState?.updateGraphRenderInput(graphInput)
        lastNonEmptyComposeGraphInput = graphInput
    }

    /**
     * Vico-only dashboard: legacy [overviewData.treatmentsSeries] is not fed by GraphView, but
     * [OverviewDataCache.treatmentGraphFlow] is kept in sync from the DB.
     */
    private fun extractSmbMarkersFromCache(fromMs: Long, toMs: Long): List<ChartSmbMarker> = runCatching {
        val boluses = overviewDataCache.treatmentGraphFlow.value.boluses
        if (boluses.isEmpty()) return@runCatching emptyList()
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val smbOnly = ArrayList<ChartSmbMarker>()
        for (b in boluses) {
            if (b.bolusType != BolusType.SMB) continue
            val t = b.timestamp
            if (t < fromMs || t > toMs) continue
            val label = decimalFormatter.toPumpSupportedBolusWithUnits(b.amount, bolusStep)
            smbOnly.add(ChartSmbMarker(timestampEpochMs = t, amountLabel = label))
        }
        if (smbOnly.isNotEmpty()) return@runCatching smbOnly
        // Some pumps/sync paths may persist SMB without explicit SMB type; keep visibility instead of blank strip.
        val fallback = ArrayList<ChartSmbMarker>()
        for (b in boluses) {
            val t = b.timestamp
            if (t < fromMs || t > toMs) continue
            if (!b.isValid || b.amount <= 0.0) continue
            val label = decimalFormatter.toPumpSupportedBolusWithUnits(b.amount, bolusStep)
            fallback.add(ChartSmbMarker(timestampEpochMs = t, amountLabel = label))
        }
        fallback
    }.getOrElse { emptyList() }

    private fun basalActualStepPointsUpTo(toMs: Long): List<GraphDataPoint> {
        val actual = overviewDataCache.basalGraphFlow.value.actualBasal
        if (actual.isEmpty()) return emptyList()
        return actual.filter { it.timestamp <= toMs }.sortedBy { it.timestamp }
    }

    private fun syncOverviewCacheRange(fromMs: Long, toMs: Long, endMs: Long) {
        val current = overviewDataCache.timeRangeFlow.value
        val needsUpdate =
            current == null ||
                abs(current.fromTime - fromMs) > 60_000L ||
                abs(current.toTime - toMs) > 60_000L ||
                abs(current.endTime - endMs) > 60_000L
        if (!needsUpdate) return
        overviewDataCache.updateTimeRange(
            TimeRange(
                fromTime = fromMs,
                toTime = toMs,
                endTime = endMs,
            ),
        )
    }

    private fun extractTbrChangeMarkerTimesFromBasalSteps(fromMs: Long, toMs: Long): List<Long> = runCatching {
        val pts = basalActualStepPointsUpTo(toMs)
        if (pts.size < 2) return@runCatching emptyList()
        val eps = 1e-6
        val raw = ArrayList<Long>()
        for (i in 1 until pts.size) {
            if (abs(pts[i].value - pts[i - 1].value) > eps) {
                val t = pts[i].timestamp
                if (t in fromMs..toMs) raw.add(t)
            }
        }
        decimateMarkerTimes(raw, minGapMs = 5 * 60_000L, maxMarkers = 36)
    }.getOrElse { emptyList() }

    private fun extractTbrSegmentsFromBasalSteps(fromMs: Long, toMs: Long): List<ChartTbrSegment> = runCatching {
        val pts = basalActualStepPointsUpTo(toMs)
        if (pts.size < 2) return@runCatching emptyList()
        val runs = ArrayList<Triple<Long, Long, Double>>()
        for (i in 0 until pts.size - 1) {
            runs.add(Triple(pts[i].timestamp, pts[i + 1].timestamp, pts[i].value))
        }
        val maxMag = runs.maxOf { abs(it.third) }.coerceAtLeast(1e-6)
        val out = ArrayList<ChartTbrSegment>()
        for ((s, e, y) in runs) {
            val duration = e - s
            if (duration < 30_000L) continue
            val overlapStart = max(s, fromMs)
            val overlapEnd = min(e, toMs)
            if (overlapEnd <= overlapStart) continue
            val intensity = (abs(y) / maxMag).toFloat().coerceIn(0.1f, 1f)
            out.add(
                ChartTbrSegment(
                    startEpochMs = overlapStart,
                    endEpochMs = overlapEnd,
                    intensity01 = intensity,
                ),
            )
        }
        out
    }.getOrElse { emptyList() }

    private fun requestDbMarkerFallback(fromMs: Long, toMs: Long) {
        if (dbMarkerFallbackRange?.first == fromMs && dbMarkerFallbackRange?.second == toMs && dbMarkerFallbackJob?.isActive == true) return
        dbMarkerFallbackJob?.cancel()
        dbMarkerFallbackJob = host.liveDataOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
            val dbFromMs = fromMs - T.hours(6).msecs()
            val boluses = runCatching {
                persistenceLayer.getBolusesFromTimeToTime(dbFromMs, toMs, true)
            }.getOrElse { emptyList() }
            val validBoluses = boluses.filter { it.isValid && it.amount > 0.0 }
            val smbExplicit = validBoluses
                .filter { it.type == BS.Type.SMB }
                .map {
                    ChartSmbMarker(
                        timestampEpochMs = it.timestamp,
                        amountLabel = decimalFormatter.toPumpSupportedBolusWithUnits(it.amount, bolusStep),
                    )
                }
            val smbMarkers = if (smbExplicit.isNotEmpty()) smbExplicit else {
                validBoluses.map {
                    ChartSmbMarker(
                        timestampEpochMs = it.timestamp,
                        amountLabel = decimalFormatter.toPumpSupportedBolusWithUnits(it.amount, bolusStep),
                    )
                }
            }
            val tbrStarting = runCatching {
                persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(dbFromMs, toMs, true)
            }.getOrElse { emptyList() }
            val tbrActiveAtStart = runCatching {
                persistenceLayer.getTemporaryBasalActiveAt(fromMs)
            }.getOrNull()
            val tempBasals = buildList {
                addAll(tbrStarting)
                if (tbrActiveAtStart != null) add(tbrActiveAtStart)
            }
            val validTempBasals = tempBasals
                .asSequence()
                .filter { it.isValid }
                .filter { it.end > fromMs && it.timestamp < toMs }
                .sortedBy { it.timestamp }
                .distinctBy { Triple(it.timestamp, it.duration, it.rate) }
                .toList()
            val tbrSegments = dbSegmentsFromTemporaryBasals(validTempBasals, fromMs, toMs)
            val tbrMarkers = tbrSegments.map { it.startEpochMs }
            withContext(Dispatchers.Main) {
                if (shellBinding == null || !host.isBindingAttached()) return@withContext
                dbMarkerFallbackRange = fromMs to toMs
                dbFallbackSmbMarkers = smbMarkers
                dbFallbackTbrMarkers = tbrMarkers
                dbFallbackTbrSegments = tbrSegments
                if (smbMarkers.isNotEmpty() || tbrMarkers.isNotEmpty() || tbrSegments.isNotEmpty()) {
                    updateGraph()
                }
            }
        }
    }

    private fun dbSegmentsFromTemporaryBasals(
        basals: List<TB>,
        fromMs: Long,
        toMs: Long,
    ): List<ChartTbrSegment> {
        if (basals.isEmpty()) return emptyList()
        val maxMagnitude = basals.maxOfOrNull { abs(it.rate) }?.coerceAtLeast(1e-6) ?: 1e-6
        val out = ArrayList<ChartTbrSegment>()
        for (tb in basals) {
            val start = max(tb.timestamp, fromMs)
            val end = min(tb.end, toMs)
            if (end <= start) continue
            val intensity = (abs(tb.rate) / maxMagnitude).toFloat().coerceIn(0.1f, 1f)
            out.add(
                ChartTbrSegment(
                    startEpochMs = start,
                    endEpochMs = end,
                    intensity01 = intensity,
                ),
            )
        }
        return out
    }

    private fun extractSmbMarkers(fromMs: Long, toMs: Long): List<ChartSmbMarker> = runCatching {
        val series =
            overviewData.treatmentsSeries as? PointsWithLabelGraphSeries<DataPointWithLabelInterface>
                ?: return@runCatching emptyList()
        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val out = ArrayList<ChartSmbMarker>()
        val iterator = series.getValues(fromMs.toDouble(), toMs.toDouble())
        while (iterator.hasNext()) {
            when (val dp = iterator.next()) {
                is BolusDataPoint ->
                    if (dp.data.type == BS.Type.SMB) {
                        val t = dp.x.toLong()
                        val label = decimalFormatter.toPumpSupportedBolusWithUnits(dp.data.amount, bolusStep)
                        out.add(ChartSmbMarker(timestampEpochMs = t, amountLabel = label))
                    }
                else -> Unit
            }
        }
        out
    }.getOrElse { emptyList() }

    private fun extractTbrChangeMarkerTimes(fromMs: Long, toMs: Long): List<Long> = runCatching {
        val series = overviewData.tempBasalGraphSeries as? LineGraphSeries<ScaledDataPoint>
            ?: return@runCatching emptyList()
        val points = ArrayList<ScaledDataPoint>()
        val iterator = series.getValues(fromMs.toDouble(), toMs.toDouble())
        while (iterator.hasNext()) {
            points.add(iterator.next())
        }
        if (points.isEmpty()) return@runCatching emptyList()
        val eps = 0.02
        val raw = ArrayList<Long>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            if (dx < 1.0 && dy > 1e-6) {
                raw.add(b.x.toLong())
            } else if (dy > eps) {
                raw.add(b.x.toLong())
            }
        }
        decimateMarkerTimes(raw, minGapMs = 5 * 60_000L, maxMarkers = 36)
    }.getOrElse { emptyList() }

    private fun extractTbrSegments(fromMs: Long, toMs: Long): List<ChartTbrSegment> = runCatching {
        val series = overviewData.tempBasalGraphSeries as? LineGraphSeries<ScaledDataPoint>
            ?: return@runCatching emptyList()
        val points = ArrayList<ScaledDataPoint>()
        val iterator = series.getValues(fromMs.toDouble(), toMs.toDouble())
        while (iterator.hasNext()) {
            points.add(iterator.next())
        }
        if (points.isEmpty()) return@runCatching emptyList()
        val eps = 1e-4
        val runs = ArrayList<Triple<Long, Long, Double>>()
        var runStart = points.first().x.toLong()
        var runEnd = runStart
        var runY = points.first().y
        for (i in 1 until points.size) {
            val p = points[i]
            if (abs(p.y - runY) < eps) {
                runEnd = p.x.toLong()
            } else {
                runs.add(Triple(runStart, runEnd, runY))
                runStart = p.x.toLong()
                runEnd = runStart
                runY = p.y
            }
        }
        runs.add(Triple(runStart, runEnd, runY))
        val maxMag = runs.maxOf { abs(it.third) }.coerceAtLeast(1e-6)
        val out = ArrayList<ChartTbrSegment>()
        for ((s, e, y) in runs) {
            val duration = e - s
            if (duration < 30_000L) continue
            val overlapStart = max(s, fromMs)
            val overlapEnd = min(e, toMs)
            if (overlapEnd <= overlapStart) continue
            val intensity = (abs(y) / maxMag).toFloat().coerceIn(0.1f, 1f)
            out.add(
                ChartTbrSegment(
                    startEpochMs = overlapStart,
                    endEpochMs = overlapEnd,
                    intensity01 = intensity,
                ),
            )
        }
        out
    }.getOrElse { emptyList() }

    private fun decimateMarkerTimes(sortedInput: List<Long>, minGapMs: Long, maxMarkers: Int): List<Long> {
        if (sortedInput.isEmpty()) return emptyList()
        val sorted = sortedInput.sorted()
        val merged = ArrayList<Long>()
        var last = Long.MIN_VALUE / 4
        for (t in sorted) {
            if (t - last >= minGapMs) {
                merged.add(t)
                last = t
            }
        }
        if (merged.size <= maxMarkers) return merged
        val step = (merged.size + maxMarkers - 1) / maxMarkers
        return merged.filterIndexed { index, _ -> index % step == 0 }
    }

    private fun shouldAttachLegacyGraphBackend(): Boolean {
        val composeState = host.embeddedComposeState
        if (host.embeddedInComposeMainShell()) return false
        return composeState?.graphUiState?.attachLegacyGraphBackend != false
    }

    private fun graphFreshnessConfigFromPreferences(): DashboardEmbeddedComposeState.GraphFreshnessConfig {
        val staleMinutes = preferences.get(IntKey.AlertsStaleDataThreshold)
        return GraphFreshnessConfigResolver.fromStaleThresholdMinutes(staleMinutes)
    }

    private fun createHeroCommands(): DashboardHeroCommands =
        object : DashboardHeroCommands {
            override fun openLoopDialogFromHero() = openLoopDialog()

            override fun openContextFromBadge() = launchContextActivity()

            override fun onAimiAdaptationClicked() = launchAimiAdaptationStatusActivity()

            override fun onAimiAdvisorClicked() = launchAimiAdvisorActivity()

            override fun onAdjustClicked() {
                openAdjustmentDetails()
            }

            override fun onAimiPreferencesClicked() = launchMealAdvisorActivity()

            override fun onStatsClicked() = launchContextActivity()

            override fun onAimiPulseClicked() {
                openAdjustmentDetails()
            }

            // Route strings must match AppRoute.Stats.route / AppRoute.Treatments.route in the
            // :app module (app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt) — plugins:main
            // cannot depend on :app, so these are inlined literals, not a shared constant.
            // Note: AppRoute.TreatmentDialog ("treatment_dialog") is a single bolus-entry dialog, NOT
            // this screen — the tabbed treatments history/log browser is AppRoute.Treatments ("treatments").
            override fun openStatsScreen() =
                uiInteraction.openComposeMainAtRoute(host.context, "stats")

            override fun openTreatmentsScreen() =
                uiInteraction.openComposeMainAtRoute(host.context, "treatments")

            // Same inlining rule as above. Defaults match each screen's own AppRoute.createRoute()
            // default (ScreenMode.EDIT), and the no-arg forms of CarbsDialog/WizardDialog.
            // ElementType.CARBS / BOLUS_WIZARD / QUICK_WIZARD_MANAGEMENT / TEMP_TARGET_MANAGEMENT
            // all require Protection.BOLUS everywhere else they're reached (QuickLaunchToolbar,
            // search, NavigationRequest.Element) — gate these the same way so a PIN/biometric lock
            // configured for bolus-level actions isn't silently skipped from the dashboard.
            override fun openCarbsEntry() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "carbs_dialog")
            }

            override fun openBolusWizard() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "wizard_dialog")
            }

            override fun openQuickWizardManagement() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "quick_wizard_management?mode=${ScreenMode.EDIT.name}")
            }

            override fun openTempTargetManagement() = withBolusProtection { activity ->
                uiInteraction.openTempTargetManagementScreen(activity)
            }
        }

    private fun createGlassHeroCommands(): GlassHeroCommands =
        object : GlassHeroCommands {
            override fun openLoop() {
                // Own inlined Protection.BOLUS check (same gate openLoopDialog() applies) rather than reusing
                // openLoopDialog(): that shared helper also routes the legacy uiInteraction.openRunningModeScreen()
                // for its other callers (the status card / loop indicator taps, and the legacy hero's
                // openLoopDialogFromHero()), which must keep that legacy behavior.
                host.activity?.let { activity ->
                    protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                        if (result == ProtectionResult.GRANTED && host.isBindingAttached()) {
                            uiInteraction.openComposeMainAtRoute(host.context, "glass_loop_detail")
                        }
                    }
                }
            }

            override fun openLoopDashboard() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_loop_dashboard")

            override fun openInsulin() {
                // Own inlined Protection.BOLUS check (same gate openBolus() applies) rather than reusing
                // openBolus(): that shared helper also routes the legacy uiInteraction.openInsulinScreen()
                // for other callers (the bottom-nav bolus command), which must keep that legacy behavior.
                host.activity?.let { activity ->
                    protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                        if (result == ProtectionResult.GRANTED) {
                            uiInteraction.openComposeMainAtRoute(host.context, "glass_insulin_detail")
                        }
                    }
                }
            }

            override fun openTarget() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_target_detail")
            }

            override fun openBasal() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_basal_detail")
            }

            override fun openCannula() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_cannula_detail")
            }

            override fun openPump() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_pump_detail")

            override fun openBattery() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_battery_detail")

            override fun openSensorInsert() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(
                    host.context,
                    "glass_sensor_insert_detail/${CareportalEventType.SENSOR_INSERT.ordinal}",
                )
            }

            override fun openSensorQuality() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_sensor_quality")

            override fun openStatsScreen() =
                uiInteraction.openComposeMainAtRoute(host.context, "stats")

            override fun openTreatmentsScreen() =
                uiInteraction.openComposeMainAtRoute(host.context, "treatments")
        }

    private fun launchAimiAdaptationStatusActivity() {
        try {
            host.context.startActivity(Intent(host.context, AimiAdaptationStatusActivity::class.java))
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to launch AIMI adaptation status: ${e.message}")
        }
    }

    private fun launchAimiAdvisorActivity() {
        try {
            val intent = Intent(host.context, AimiProfileAdvisorActivity::class.java)
            host.context.startActivity(intent)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to launch Advisor: ${e.message}")
        }
    }

    private fun launchMealAdvisorActivity() {
        try {
            val intent = Intent(host.context, app.aaps.plugins.aps.openAPSAIMI.advisor.meal.MealAdvisorActivity::class.java)
            host.context.startActivity(intent)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to launch Meal Advisor: ${e.message}")
        }
    }

    private fun launchContextActivity() {
        try {
            val intent = Intent().setClassName(host.context, "app.aaps.plugins.aps.openAPSAIMI.context.ui.ContextActivity")
            host.context.startActivity(intent)
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to launch ContextActivity: ${e.message}")
        }
    }

    private fun showHypoRiskDialog() {
        if (isHypoRiskDialogShowing) return
        isHypoRiskDialogShowing = true
        app.aaps.core.ui.dialogs.OKDialog.show(
            host.context,
            host.context.getString(R.string.hypo_risk_notification_title),
            host.context.getString(R.string.hypo_risk_notification_text),
            runOnDismiss = true
        ) {
            isHypoRiskDialogShowing = false
        }
    }

    companion object {
        /** Max time after last successful graph refresh to keep reusing a snapshot when [OverviewData.bgReadingsArray] is transiently empty. */
        private const val BG_GRAPH_EMPTY_GAP_HOLD_MS = 120_000L
        /** Do not show a cached graph if the newest BG point in that snapshot is older than this (real stale CGM). */
        private const val BG_GRAPH_SNAPSHOT_MAX_POINT_AGE_MS = 20 * 60 * 1000L
        private const val STALE_BG_LAG_MS = 10 * 60 * 1000L
        private const val LAG_IMPROVEMENT_FOR_RECOVERY_MS = 2 * 60 * 1000L
        private const val GRAPH_REFRESH_DEBOUNCE_MS = 120L
        private const val MARKER_DATA_RETRY_DELAY_MS = 700L
        private const val MARKER_DATA_RETRY_MAX_ATTEMPTS = 8
        private const val DASHBOARD_PERIODIC_REFRESH_MS = 60 * 1000L
        private const val EMBEDDED_RESUME_DEBOUNCE_MS = 200L
        private const val EMBEDDED_LAYOUT_SETTLE_REFRESH_MS = 2_000L
    }
}
