package app.aaps.plugins.main.general.overview

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.graph.data.GraphViewWithCleanup
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.Overview
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.apsAdjustedTargetMgdl
import app.aaps.core.objects.extensions.directionToLegacyDrawable
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.overview.DashboardCoherentGlucose
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.elements.SingleClickButton
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.extensions.toVisibilityKeepSpace
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.OverviewFragmentBinding
import app.aaps.plugins.main.databinding.OverviewNotificationItemBinding
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.graphData.viewportShouldFollowLiveRange
import app.aaps.plugins.main.general.overview.notifications.NotificationUiBinder
import app.aaps.plugins.main.general.overview.ui.StatusLightHandler
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusIndicator
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusLiveData
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorNotificationManager
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import com.jjoe64.graphview.GraphView
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.min
import app.aaps.core.interfaces.notifications.NotificationManager as AapsNotificationManager

class OverviewFragment : DaggerFragment(), View.OnClickListener, View.OnLongClickListener {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var nsSettingsStatus: NSSettingsStatus
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var notificationManager: AapsNotificationManager
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overview: Overview
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var automation: Automation
    @Inject lateinit var bgQualityCheck: BgQualityCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var graphDataProvider: Provider<GraphData>
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var notificationUiBinder: NotificationUiBinder
    @Inject lateinit var auditorStatusLiveData: AuditorStatusLiveData
    @Inject lateinit var auditorNotificationManager: AuditorNotificationManager

    private val disposable = CompositeDisposable()
    private var scope: CoroutineScope? = null

    private var smallWidth = false
    private var smallHeight = false
    private var axisWidth: Int = 0
    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val secondaryGraphs = ArrayList<GraphView>()
    private val secondaryGraphsLabel = ArrayList<TextView>()
    private var forceGraphViewportReset = false
    private var lastGraphFormatRangeHours: Int? = null

    private var carbAnimation: AnimationDrawable? = null
    private var lastUserAction = ""
    private var auditorIndicator: AuditorStatusIndicator? = null

    private var _binding: OverviewFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    //@SuppressLint("NewApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        OverviewFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // pre-process landscape mode
        //check screen width
        val wm = requireActivity().windowManager.currentWindowMetrics
        val screenWidth = wm.bounds.width()
        val screenHeight = wm.bounds.height()
        smallWidth = screenWidth <= SMALL_WIDTH
        smallHeight = screenHeight <= SMALL_HEIGHT

        if (config.AAPSCLIENT1)
            binding.nsclientCard.setBackgroundColor(Color.argb(80, 0xE8, 0xC5, 0x0C))
        if (config.AAPSCLIENT2)
            binding.nsclientCard.setBackgroundColor(Color.argb(80, 0x0F, 0xBB, 0xE0))
        if (config.AAPSCLIENT3)
            binding.nsclientCard.setBackgroundColor(Color.argb(80, 0x4C, 0xAF, 0x50))

        overview.setVersionView(binding.infoLayout.version)

        binding.nsclientCard.visibility = config.AAPSCLIENT.toVisibility()

        binding.notifications.setHasFixedSize(false)
        binding.notifications.layoutManager = LinearLayoutManager(view.context)
        axisWidth = when {
            resources.displayMetrics.densityDpi <= 120 -> 3
            resources.displayMetrics.densityDpi <= 160 -> 10
            resources.displayMetrics.densityDpi <= 320 -> 35
            resources.displayMetrics.densityDpi <= 420 -> 50
            resources.displayMetrics.densityDpi <= 560 -> 70
            else                                       -> 80
        }
        binding.graphsLayout.bgGraph.gridLabelRenderer?.gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
        binding.graphsLayout.bgGraph.gridLabelRenderer?.reloadStyles()
        binding.graphsLayout.bgGraph.gridLabelRenderer?.labelVerticalWidth = axisWidth

        carbAnimation = binding.infoLayout.carbsIcon.background as AnimationDrawable?
        carbAnimation?.setEnterFadeDuration(1200)
        carbAnimation?.setExitFadeDuration(1200)

        binding.graphsLayout.bgGraph.setOnLongClickListener {
            forceGraphViewportReset = true
            overviewData.rangeToDisplay = when (overviewData.rangeToDisplay) {
                6    -> 9
                9    -> 12
                12   -> 18
                18   -> 24
                else -> 6
            }
            preferences.put(IntNonKey.RangeToDisplay, overviewData.rangeToDisplay)
            preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
            false
        }
        val graphTouchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var graphPanStartX = 0f
        var graphPanStartY = 0f
        binding.graphsLayout.bgGraph.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    graphPanStartX = event.x
                    graphPanStartY = event.y
                    binding.topPartScrollbar.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - graphPanStartX)
                    val dy = abs(event.y - graphPanStartY)
                    if (dx > dy + graphTouchSlop) {
                        binding.topPartScrollbar.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.topPartScrollbar.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        prepareGraphsIfNeeded(overviewMenus.setting.size)
        overviewMenus.setupChartMenu(binding.graphsLayout.chartMenuButton, binding.graphsLayout.scaleButton)
        binding.graphsLayout.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)

        binding.graphsLayout.chartMenuButton.visibility = preferences.simpleMode.not().toVisibility()

        binding.activeProfile.setOnClickListener(this)
        binding.activeProfile.setOnLongClickListener(this)
        binding.tempTarget.setOnClickListener(this)
        binding.tempTarget.setOnLongClickListener(this)
        binding.pumpStatusLayout.setOnClickListener(this)
        binding.buttonsLayout.acceptTempButton.setOnClickListener(this)
        binding.infoLayout.apsMode.setOnClickListener(this)
        binding.infoLayout.apsMode.setOnLongClickListener(this)

        binding.root.findViewById<View>(R.id.aimi_context_indicator).setOnClickListener {
            try {
                val intent = Intent().setClassName(requireContext(), "app.aaps.plugins.aps.openAPSAIMI.context.ui.ContextActivity")
                startActivity(intent)
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Failed to launch ContextActivity: ${e.message}")
            }
        }

        // AIMI Auditor Indicator Setup
        setupAuditorIndicator()

        // Wire up AIMI Dashboard Loop Indicator for clicks (Loop Mode)
        binding.root.findViewById<View>(R.id.loop_indicator)?.let { indicator ->
            indicator.setOnClickListener(this)
            indicator.setOnLongClickListener(this)
        }
        binding.root.findViewById<View>(R.id.loop_status)?.let { status ->
            status.setOnClickListener(this)
            status.setOnLongClickListener(this)
        }
    }

    override fun onPause() {
        super.onPause()
        scope?.cancel()
        scope = null
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        persistenceLayer.observeChanges(EPS::class.java)
            .onEach { scheduleUpdateGUI() }
            .launchIn(newScope)
        persistenceLayer.observeChanges(RM::class.java)
            .onEach { processAps() }
            .launchIn(newScope)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateCalcProgress() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateSensitivity() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGraph() }, fabricPrivacy::logException)
        viewLifecycleOwner.lifecycleScope.launch {
            notificationManager.notifications.collectLatest { updateNotification() }
        }
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           forceGraphViewportReset = true
                           overviewData.rangeToDisplay = it.hours
                           preferences.put(IntNonKey.RangeToDisplay, it.hours)
                           preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateBg() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        merge(
            preferences.observe(BooleanKey.OverviewShowWizardButton).drop(1).map {},
            preferences.observe(UnitDoubleKey.OverviewLowMark).drop(1).map {},
            preferences.observe(UnitDoubleKey.OverviewHighMark).drop(1).map {},
            preferences.observe(BooleanNonKey.AutosensUsedOnMainPhone).drop(1).map {},
            preferences.observe(DoubleKey.AutosensMax).drop(1).map {},
            preferences.observe(DoubleKey.AutosensMin).drop(1).map {},
        ).onEach { scheduleUpdateGUI() }.launchIn(newScope)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(requireContext())
                           updatePumpStatus()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ processButtonsVisibility() }, fabricPrivacy::logException)
        persistenceLayer.observeChanges(TT::class.java)
            .onEach { updateTemporaryTarget() }
            .launchIn(newScope)
        persistenceLayer.observeChanges(EB::class.java)
            .onEach { updateExtendedBolus() }
            .launchIn(newScope)
        persistenceLayer.observeChanges(TB::class.java)
            .onEach { updateTemporaryBasal() }
            .launchIn(newScope)
        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)

        handler.post { refreshAll() }
        updatePumpStatus()
        updateCalcProgress()
    }

    fun refreshAll() {
        if (!config.appInitialized) return
        if (_binding == null) return  // View destroyed, skip refresh
        runOnUiThread {
            _binding ?: return@runOnUiThread
            updateTime()
            updateSensitivity()
            updateGraph()
            updateNotification()
            updateAimiContextIndicator()
        }
        // refreshAll is posted from a background HandlerThread; anything touching the Fragment view,
        // viewLifecycleOwner, or loop.runningMode (DB via runBlocking) must run on the main thread.
        activity?.runOnUiThread {
            if (_binding == null || !isAdded) return@runOnUiThread
            updateBg()
            updateTemporaryBasal()
            updateExtendedBolus()
            updateIobCob()
            processButtonsVisibility()
            processAps()
            updateProfile()
            updateTemporaryTarget()
        }
    }

    private fun updateAimiContextIndicator() {
        try {
            val jsonStr = preferences.get(app.aaps.core.keys.StringKey.OApsAIMIContextStorage)
            val hasContext = jsonStr.length > 5 // "[]" length is 2

            // 1. Update Badge in Root/Classic Layout
            _binding?.root?.findViewById<View>(R.id.aimi_context_indicator)?.visibility = hasContext.toVisibility()

            // 2. Update Badge in Modern Dashboard (Critical Fix for duplication)
            val modernCard = _binding?.root?.findViewById<View>(R.id.modernCircleCard)
            if (modernCard != null) {
                modernCard.findViewById<View>(R.id.aimi_context_indicator)?.visibility = hasContext.toVisibility()
            }

            // Ne pas lier infoCard vs modernCircleCard au switch Physio : ce sont deux mises en page distinctes.
            // Les visibilités par défaut viennent du layout (overview_fragment.xml : info visible, modern gone).

        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to update context indicator/dashboard: ${e.message}")
        }
    }

    private fun setupAuditorIndicator() {
        try {
            // UNIVERSAL FIX: Support ALL dashboard layouts (overview_info_layout, component_status_card, etc.)
            // Strategy: Try multiple findViewById paths in fallback order

            // 1. Try finding container in Modern Dashboard (Priority!)
            val modernCard = binding.root.findViewById<View>(R.id.modernCircleCard)
            val modernContainer = modernCard?.findViewById<FrameLayout>(R.id.aimi_auditor_indicator_container)

            // 2. Fallback to Info Layout (Standard) - Only if modern not found
            val legacyContainer = binding.infoLayout?.root?.findViewById<FrameLayout>(
                R.id.aimi_auditor_indicator_container
            )

            // 3. Last resort: Global search
            val container = modernContainer ?: legacyContainer ?: binding.root.findViewById<FrameLayout>(
                R.id.aimi_auditor_indicator_container
            ) ?: run {
                aapsLogger.warn(LTag.CORE, "Auditor indicator container not found in any layout hierarchy")
                return
            }

            aapsLogger.debug(LTag.CORE, "Auditor indicator container found successfully")

            // Create and add custom indicator
            auditorIndicator = AuditorStatusIndicator(requireContext())
            container.removeAllViews()
            container.addView(auditorIndicator)

            // Setup click listener
            auditorIndicator?.setOnClickListener {
                handleAuditorClick()
            }

            // Observe LiveData for state changes
            auditorStatusLiveData.uiState.observe(viewLifecycleOwner) { uiState ->
                auditorIndicator?.setState(uiState)

                // Show notification if needed
                if (uiState.shouldNotify) {
                    auditorNotificationManager.showInsightAvailable(uiState)
                }

                // 🎨 LIVING BADGE: Always visible, visual state changes instead of hiding
                // - IDLE/OFF: Static gray icon (base state)
                // - ACTIVE: Pulsing colored icon (AI decision applied)
                // - ERROR: Static red icon (problem detected)
                container.visibility = View.VISIBLE  // Always visible!

                aapsLogger.debug(LTag.CORE, "Auditor indicator state updated: ${uiState.type}, visible=${container.visibility == View.VISIBLE}")
            }

            // Initial update
            auditorStatusLiveData.forceUpdate()

        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to setup Auditor indicator: ${e.message}", e)
        }
    }

    private fun handleAuditorClick() {
        val state = auditorIndicator?.getCurrentState() ?: return

        when (state.type) {
            AuditorUIState.StateType.READY,
            AuditorUIState.StateType.WARNING -> {
                auditorNotificationManager.openReport(requireContext())
            }

            AuditorUIState.StateType.PROCESSING -> {
                activity?.let { activity ->
                    uiInteraction.showOkDialog(
                        activity,
                        getString(app.aaps.plugins.aps.R.string.aimi_auditor_report_dialog_title),
                        getString(app.aaps.plugins.aps.R.string.aimi_auditor_indicator_processing),
                    )
                }
            }

            AuditorUIState.StateType.ERROR -> {
                activity?.let { activity ->
                    uiInteraction.showOkDialog(
                        activity,
                        rh.gs(app.aaps.core.ui.R.string.error),
                        state.statusMessage,
                    )
                }
            }

            else -> {
                activity?.let { activity ->
                    uiInteraction.showOkDialog(
                        activity,
                        getString(app.aaps.plugins.aps.R.string.aimi_auditor_report_dialog_title),
                        getString(app.aaps.plugins.aps.R.string.aimi_auditor_indicator_idle),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        // Remove handler callbacks before nulling view to prevent crashes
        handler.removeCallbacksAndMessages(null)
        // Remove listeners and detach series to prevent memory leaks
        _binding?.graphsLayout?.bgGraph?.let { graph ->
            graph.setOnLongClickListener(null)
            graph.setOnTouchListener(null)
            graph.removeAllSeries()
        }
        for (graph in secondaryGraphs) {
            graph.setOnLongClickListener(null)
            graph.removeAllSeries()
        }

        // Cleanup Auditor indicator
        auditorIndicator?.stopAnimations()
        auditorIndicator = null

        _binding = null
        carbAnimation?.stop()
        carbAnimation = null
        secondaryGraphs.clear()
        secondaryGraphsLabel.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.looper.quitSafely()
    }

    override fun onClick(v: View) {
        if (childFragmentManager.isStateSaved) return
        when (v.id) {
            R.id.accept_temp_button -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (childFragmentManager.isStateSaved) return@launch
                    profileFunction.getProfile() ?: return@launch
                    if ((loop as PluginBase).isEnabled()) {
                        val lastRun = loop.lastRun
                        loop.invoke("Accept temp button", false)
                        val changeRequested = lastRun?.constraintsProcessed?.isChangeRequested() == true
                        val resultHtml = lastRun?.constraintsProcessed?.resultAsHtmlString().orEmpty()
                        if (lastRun?.lastAPSRun != null && changeRequested) {
                            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                                if (result != ProtectionResult.GRANTED) return@requestProtection
                                if (!isAdded) return@requestProtection
                                uiInteraction.showOkCancelDialog(
                                    context = requireActivity(),
                                    title = rh.gs(app.aaps.core.ui.R.string.tempbasal_label),
                                    message = resultHtml,
                                    ok = {
                                        uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Overview)
                                        (context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(Constants.NOTIFICATION_ID)
                                        rxBus.send(EventMobileToWear(EventData.CancelNotification(dateUtil.now())))
                                        viewLifecycleOwner.lifecycleScope.launch { loop.acceptChangeRequest() }
                                        binding.buttonsLayout.acceptTempButton.visibility = View.GONE
                                    })
                            }
                        }
                    }
                }
            }

            R.id.temp_target          -> {
                val act = activity ?: return
                protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                    if (result == ProtectionResult.GRANTED && isAdded) uiInteraction.openTempTargetManagementScreen(act)
                }
            }

            R.id.pump_status_layout   -> {
                val act = activity ?: return
                uiInteraction.showOkDialog(
                    context = act,
                    title = rh.gs(app.aaps.core.ui.R.string.pump),
                    message = processedDeviceStatusData.extendedPumpStatusHtml
                )
            }

            R.id.aps_mode, R.id.loop_indicator, R.id.loop_status -> {
                val act = activity ?: return
                protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                    if (result == ProtectionResult.GRANTED && isAdded) uiInteraction.openRunningModeScreen(act)
                }
            }

            R.id.active_profile       -> {
                val act = activity ?: return
                uiInteraction.openProfileManagementScreen(act)
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (v.id) {
            R.id.aps_mode, R.id.loop_indicator, R.id.loop_status -> {
                activity?.let { act ->
                    protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                        if (result == ProtectionResult.GRANTED && isAdded) uiInteraction.openRunningModeScreen(act)
                    }
                }
                return true
            }

            R.id.temp_target          -> {
                v.performClick()
                return true
            }

            R.id.active_profile       -> {
                activity?.let { act ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (loop.runningMode() == RM.Mode.DISCONNECTED_PUMP) {
                            OKDialog.show(act, rh.gs(R.string.not_available_full), rh.gs(R.string.smscommunicator_pump_disconnected))
                        } else {
                            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                                if (result == ProtectionResult.GRANTED && isAdded) uiInteraction.openProfileActivationScreen(act, 0)
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    @SuppressLint("SetTextI18n")
    private fun processButtonsVisibility() {
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            iobCobCalculator.ads.lastBg()
            val pump = activePlugin.activePump
            val profile = profileFunction.getProfile()
            profileFunction.getProfileName()
            iobCobCalculator.ads.actualBg()
            var list = ""
            val runningMode = withContext(Dispatchers.IO) { loop.runningMode() }

            // **** Temp button ****
            val lastRun = loop.lastRun
            val changeRequested = lastRun?.constraintsProcessed?.isChangeRequested() == true
            val resultString = lastRun?.constraintsProcessed?.resultAsString().orEmpty()
            val resultAvailable =
                lastRun != null &&
                    (lastRun.lastOpenModeAccept == 0L || lastRun.lastOpenModeAccept < lastRun.lastAPSRun) &&// never accepted or before last result
                    changeRequested // change is requested

            val events = automation.events.value.filter { it.userAction }
            val runnableEvents = withContext(Dispatchers.IO) {
                events.filter { it.isEnabled && it.canRun() }
            }

            runOnUiThread {
                _binding ?: return@runOnUiThread
                if (resultAvailable && pump.isInitialized() && runningMode == RM.Mode.OPEN_LOOP && (loop as PluginBase).isEnabled()) {
                    binding.buttonsLayout.acceptTempButton.visibility = View.VISIBLE
                    binding.buttonsLayout.acceptTempButton.text = "${rh.gs(R.string.set_basal_question)}\n$resultString"
                } else {
                    binding.buttonsLayout.acceptTempButton.visibility = View.GONE
                }

                // Automation buttons
                binding.buttonsLayout.userButtonsLayout.removeAllViews()
                if (!runningMode.pausesLoopExecution() && pump.isInitialized() && profile != null && !config.isEnabled(ExternalOptions.SHOW_USER_ACTIONS_ON_WATCH_ONLY))
                    for (event in runnableEvents) {
                            context?.let { context ->
                                SingleClickButton(context, null, app.aaps.core.ui.R.attr.customBtnStyle).also {
                                    it.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.userOptionColor))
                                    it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                    it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { l ->
                                        l.setMargins(rh.dpToPx(1), 0, rh.dpToPx(1), 0)
                                    }
                                    it.setPadding(rh.dpToPx(1), it.paddingTop, rh.dpToPx(1), it.paddingBottom)
                                    it.compoundDrawablePadding = rh.dpToPx(-4)
                                    it.setCompoundDrawablesWithIntrinsicBounds(
                                        null,
                                        rh.gd(app.aaps.core.ui.R.drawable.ic_user_options_24dp).also { icon ->
                                            icon?.setBounds(rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20), rh.dpToPx(20))
                                        }, null, null
                                    )
                                    it.text = event.title
                                    it.setOnClickListener {
                                        uiInteraction.showOkCancelDialog(context = context, message = rh.gs(R.string.run_question, event.title), ok = { scope?.launch { automation.processEvent(event) } })
                                    }
                                    binding.buttonsLayout.userButtonsLayout.addView(it)
                                    for (drawable in it.compoundDrawables) {
                                        drawable?.mutate()
                                        drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, app.aaps.core.ui.R.attr.userOptionColor), PorterDuff.Mode.SRC_IN)
                                    }
                                }
                            }
                            list += event.hashCode()
                    }
                binding.buttonsLayout.userButtonsLayout.visibility = events.isNotEmpty().toVisibility()
            }
            if (list != lastUserAction) {
                // Synchronize Watch Tiles with overview
                lastUserAction = list
                rxBus.send(EventWearUpdateTiles())
            }
        }
    }

    private fun processAps() {
        val pump = activePlugin.activePump
        suspend fun readLoopUiInputs(): Pair<RM.Mode, Int> {
            val mode = loop.runningMode()
            val mins = loop.minutesToEndOfSuspend()
            return mode to mins
        }
        suspend fun applyLoopUiOnMain(loopMode: RM.Mode, minsToEndOfSuspend: Int) {
            withContext(Dispatchers.Main) {
                _binding ?: return@withContext
                // aps mode
                fun apsModeSetA11yLabel(stringRes: Int) {
                    binding.infoLayout.apsMode.stateDescription = rh.gs(stringRes)
                }

                if (pump.pumpDescription.isTempBasalCapable) {
                    binding.infoLayout.apsMode.visibility = View.VISIBLE
                    binding.infoLayout.apsModeText.visibility = View.VISIBLE
                    when (loopMode) {
                        RM.Mode.SUPER_BOLUS       -> {
                            binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_superbolus)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.superbolus)
                            binding.infoLayout.apsModeText.text = dateUtil.age(minsToEndOfSuspend * 60000L, true, rh)
                            binding.infoLayout.apsModeText.visibility = View.VISIBLE
                        }

                        RM.Mode.DISCONNECTED_PUMP -> {
                            binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_disconnected)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.disconnected)
                            binding.infoLayout.apsModeText.text = dateUtil.age(minsToEndOfSuspend * 60000L, true, rh)
                            binding.infoLayout.apsModeText.visibility = View.VISIBLE
                        }

                        RM.Mode.SUSPENDED_BY_PUMP -> {
                            binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.pumpsuspended)
                            binding.infoLayout.apsModeText.text = rh.gs(app.aaps.core.ui.R.string.pumpsuspended)
                            binding.infoLayout.apsModeText.visibility = View.GONE
                        }

                        RM.Mode.SUSPENDED_BY_USER -> {
                            binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.loopsuspended)
                            binding.infoLayout.apsModeText.text = dateUtil.age(minsToEndOfSuspend * 60000L, true, rh)
                            binding.infoLayout.apsModeText.visibility = View.VISIBLE
                        }

                        RM.Mode.SUSPENDED_BY_DST  -> {
                            binding.infoLayout.apsMode.setImageResource(app.aaps.core.ui.R.drawable.ic_loop_paused)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.loop_suspended_by_dst)
                            binding.infoLayout.apsModeText.text = dateUtil.age(minsToEndOfSuspend * 60000L, true, rh)
                            binding.infoLayout.apsModeText.visibility = View.VISIBLE
                        }

                        RM.Mode.CLOSED_LOOP_LGS   -> {
                            binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_lgs)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.uel_lgs_loop_mode)
                            binding.infoLayout.apsModeText.visibility = View.GONE
                        }

                        RM.Mode.CLOSED_LOOP       -> {
                            binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_closed)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.closedloop)
                            binding.infoLayout.apsModeText.visibility = View.GONE
                        }

                        RM.Mode.OPEN_LOOP         -> {
                            binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_open)
                            apsModeSetA11yLabel(app.aaps.core.ui.R.string.openloop)
                            binding.infoLayout.apsModeText.visibility = View.GONE
                        }

                        RM.Mode.DISABLED_LOOP     -> {
                            binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_disabled)
                            apsModeSetA11yLabel(R.string.disabled_loop)
                            binding.infoLayout.apsModeText.visibility = View.GONE
                        }

                        RM.Mode.RESUME            -> error("Invalid mode")
                    }
                } else {
                    binding.infoLayout.apsMode.visibility = View.GONE
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }

                binding.pump.text = processedDeviceStatusData.pumpStatus(nsSettingsStatus)
                binding.pump.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.pump), message = processedDeviceStatusData.extendedPumpStatusHtml) } }

                binding.openaps.text = processedDeviceStatusData.openApsStatus
                binding.openaps.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.openaps), message = processedDeviceStatusData.extendedOpenApsStatusHtml) } }

                binding.uploader.text = processedDeviceStatusData.uploaderStatusSpanned
                binding.uploader.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.uploader), message = processedDeviceStatusData.extendedUploaderStatusHtml) } }
            }
        }

        if (android.os.Looper.getMainLooper().thread == Thread.currentThread()) {
            if (!isAdded || view == null) return
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (loopMode, mins) = readLoopUiInputs()
                applyLoopUiOnMain(loopMode, mins)
            }
        } else {
            scope?.launch {
                val (loopMode, mins) = readLoopUiInputs()
                applyLoopUiOnMain(loopMode, mins)
            }
        }
    }

    private fun prepareGraphsIfNeeded(numOfGraphs: Int) {
        if (numOfGraphs != secondaryGraphs.size - 1) {
            //aapsLogger.debug("New secondary graph count ${numOfGraphs-1}")
            // rebuild needed
            secondaryGraphs.clear()
            secondaryGraphsLabel.clear()
            binding.graphsLayout.secondaryGraphs.removeAllViews()
            (1 until numOfGraphs).forEach { _ ->
                val relativeLayout = RelativeLayout(context)
                relativeLayout.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val graph = GraphViewWithCleanup(requireContext())
                graph.layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh.dpToPx(100)).also { it.setMargins(0, rh.dpToPx(15), 0, rh.dpToPx(10)) }
                graph.gridLabelRenderer?.gridColor = rh.gac(context, app.aaps.core.ui.R.attr.graphGrid)
                graph.gridLabelRenderer?.reloadStyles()
                graph.gridLabelRenderer?.isHorizontalLabelsVisible = false
                graph.gridLabelRenderer?.labelVerticalWidth = axisWidth
                graph.gridLabelRenderer?.numVerticalLabels = 3
                graph.viewport.backgroundColor = rh.gac(context, app.aaps.core.ui.R.attr.viewPortBackgroundColor)
                relativeLayout.addView(graph)

                val label = TextView(context)
                val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(rh.dpToPx(30), rh.dpToPx(25), 0, 0) }
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                label.layoutParams = layoutParams
                relativeLayout.addView(label)
                secondaryGraphsLabel.add(label)

                binding.graphsLayout.secondaryGraphs.addView(relativeLayout)
                secondaryGraphs.add(graph)
            }
        }
    }

    var task: Runnable? = null

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {

            override fun run() {
                refreshAll()
                task = null
            }
        }
        task?.let { handler.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler.postDelayed(it, 500) }
    }

    @SuppressLint("SetTextI18n")
    fun updateBg() {
        val lastBg = lastBgData.lastBg()
        val now = dateUtil.now()
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val displayMgdl = DashboardCoherentGlucose.displayMgdl(
            lastBg,
            glucoseStatus,
            activePlugin.activeSmoothing,
            now
        )
        val displayTs = DashboardCoherentGlucose.displayTimestamp(
            lastBg,
            glucoseStatus,
            activePlugin.activeSmoothing,
            now
        )
        val displayBgColor = DashboardCoherentGlucose.displayBgColor(
            context,
            displayMgdl,
            profileFunction,
            preferences,
            rh
        )
        val isActualBg = DashboardCoherentGlucose.isDisplayActual(
            lastBg,
            glucoseStatus,
            activePlugin.activeSmoothing,
            now,
            T.mins(9).msecs()
        )
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads)
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)
        val displayBgDescription = DashboardCoherentGlucose.displayBgDescription(
            displayMgdl,
            profileFunction,
            preferences,
            rh
        )
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.bg.text = profileUtil.fromMgdlToStringInUnits(displayMgdl)
            binding.infoLayout.bg.setTextColor(displayBgColor)
            trendArrow?.let { binding.infoLayout.arrow.setImageResource(it.directionToLegacyDrawable()) }
            binding.infoLayout.arrow.visibility = (trendArrow != null).toVisibilityKeepSpace()
            binding.infoLayout.arrow.setColorFilter(displayBgColor)
            binding.infoLayout.arrow.contentDescription = displayBgDescription + " " + rh.gs(app.aaps.core.ui.R.string.and) + " " + trendDescription

            if (glucoseStatus != null) {
                binding.infoLayout.deltaLarge.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                binding.infoLayout.deltaLarge.setTextColor(displayBgColor)
                binding.infoLayout.delta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                binding.infoLayout.avgDelta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.shortAvgDelta)
                binding.infoLayout.longAvgDelta.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.longAvgDelta)
            } else {
                binding.infoLayout.deltaLarge.text = ""
                binding.infoLayout.delta.text = "Δ " + rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
                binding.infoLayout.avgDelta.text = ""
                binding.infoLayout.longAvgDelta.text = ""
            }

            // strike through if BG is old
            binding.infoLayout.bg.paintFlags =
                if (!isActualBg) binding.infoLayout.bg.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else binding.infoLayout.bg.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            val outDate = (if (!isActualBg) rh.gs(R.string.a11y_bg_outdated) else "")
            binding.infoLayout.bg.contentDescription = rh.gs(R.string.a11y_blood_glucose) + " " + binding.infoLayout.bg.text.toString() + " " + displayBgDescription + " " + outDate

            binding.infoLayout.timeAgo.text = dateUtil.minOrSecAgo(rh, displayTs)
            binding.infoLayout.timeAgo.contentDescription = dateUtil.minAgoLong(rh, displayTs)
            binding.infoLayout.timeAgoShort.text = dateUtil.minAgoShort(displayTs)

            val qualityIcon = bgQualityCheck.icon()
            if (qualityIcon != 0) {
                binding.infoLayout.bgQuality.visibility = View.VISIBLE
                binding.infoLayout.bgQuality.setImageResource(qualityIcon)
                binding.infoLayout.bgQuality.contentDescription = rh.gs(R.string.a11y_bg_quality) + " " + bgQualityCheck.stateDescription()
                binding.infoLayout.bgQuality.setOnClickListener {
                    context?.let { context -> uiInteraction.showOkDialog(context = context, title = rh.gs(R.string.data_status), message = bgQualityCheck.message) }
                }
            } else {
                binding.infoLayout.bgQuality.visibility = View.GONE
            }
            binding.infoLayout.simpleMode.visibility = preferences.simpleMode.toVisibility()

            // ═══════════════════════════════════════════════════════════════════════════════
            // MODERN CIRCLE DASHBOARD - Dynamic Unicorn + Glucose Circle
            // ═══════════════════════════════════════════════════════════════════════════════
            updateModernCircleDashboard()
        }
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════════
     * MODERN CIRCLE DASHBOARD UPDATE
     * ═══════════════════════════════════════════════════════════════════════════════
     *
     * Updates Modern Circle dashboard components:
     * - Dynamic Unicorn color (based on BG range)
     * - Glucose Circle animation (arc progress)
     * - Centralized info (glucose + delta + time)
     * - Trend arrow
     */
    private fun updateModernCircleDashboard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = profileFunction.getProfile() ?: return@launch

            // Get current data from providers (same as updateBg)
            val lastBg = lastBgData.lastBg()
            val nowCircle = dateUtil.now()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            val displayTsForBasal = DashboardCoherentGlucose.displayTimestamp(
                lastBg,
                glucoseStatus,
                activePlugin.activeSmoothing,
                nowCircle
            )
            val basalAt = displayTsForBasal ?: lastBg?.timestamp ?: nowCircle
            val activityBasalData = iobCobCalculator.getBasalData(profile, basalAt)
            val profileBasalAtActivity = profile.getBasal(basalAt)
            val activityPercent = if (profileBasalAtActivity > 0) {
                ((activityBasalData.basal / profileBasalAtActivity) * 100).toInt()
            } else {
                100
            }
            val tbrFormattedRate = String.format(
                "%.2f",
                iobCobCalculator.getBasalData(profile, lastBg?.timestamp ?: dateUtil.now()).basal
            )

            runOnUiThread {
                _binding ?: return@runOnUiThread
            val displayMgdl = DashboardCoherentGlucose.displayMgdl(
                lastBg,
                glucoseStatus,
                activePlugin.activeSmoothing,
                nowCircle
            )
            val displayTs = DashboardCoherentGlucose.displayTimestamp(
                lastBg,
                glucoseStatus,
                activePlugin.activeSmoothing,
                nowCircle
            )
            val displayBgColor = DashboardCoherentGlucose.displayBgColor(
                context,
                displayMgdl,
                profileFunction,
                preferences,
                rh
            )
            val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)

            // Try to find Modern Circle components (component_status_card.xml)
            // Uses fallback strategy (binding.root) for direct layout access
            val glucoseCircle = binding.root.findViewById<app.aaps.core.ui.elements.GlucoseCircleView>(
                app.aaps.core.ui.R.id.glucose_circle
            )
            val unicornIcon = binding.root.findViewById<android.widget.ImageView>(
                app.aaps.core.ui.R.id.unicorn_icon
            )
            val glucoseValue = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.glucose_value
            )
            val timeAgo = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.time_ago
            )
            val deltaSmall = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.delta_small
            )
            val trendArrowView = binding.root.findViewById<android.widget.ImageView>(
                app.aaps.core.ui.R.id.trend_arrow
            )
            val deltaValue = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.delta_value
            )
            val activityText = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.activity_text
            )
            val tbrText = binding.root.findViewById<android.widget.TextView>(
                app.aaps.core.ui.R.id.tbr_text
            )

            // If Modern Circle components not found, silently return (legacy layout)
            if (glucoseCircle == null || unicornIcon == null) {
                return@runOnUiThread
            }

            // ───────────────────────────────────────────────────────────────────────
            // 1. UPDATE GLUCOSE CIRCLE (Custom View with animation)
            // ───────────────────────────────────────────────────────────────────────
            if (displayMgdl != null) {
                glucoseCircle.setGlucose(
                    glucoseMgDl = displayMgdl,
                    targetLow = profile.getTargetLowMgdl(),
                    targetHigh = profile.getTargetHighMgdl(),
                    animate = true
                )
            }

            // ───────────────────────────────────────────────────────────────────────
            // 2. UPDATE DYNAMIC UNICORN COLOR (Based on BG range)
            // ───────────────────────────────────────────────────────────────────────
            val unicornColor = when {
                displayMgdl == null -> android.graphics.Color.GRAY
                displayMgdl < 54.0 -> androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    app.aaps.core.ui.R.color.critical_low
                ) // Red - Severe hypo
                displayMgdl < profile.getTargetLowMgdl() -> androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    app.aaps.core.ui.R.color.low
                ) // Orange - Hypo
                displayMgdl <= profile.getTargetHighMgdl() -> androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    app.aaps.core.ui.R.color.inRange
                ) // Green - In range ✅
                displayMgdl <= 250.0 -> androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    app.aaps.core.ui.R.color.high
                ) // Yellow - High
                else -> androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    app.aaps.core.ui.R.color.critical_high
                ) // Orange-red - Severe high
            }

            unicornIcon.setColorFilter(unicornColor, android.graphics.PorterDuff.Mode.SRC_ATOP)

            // ───────────────────────────────────────────────────────────────────────
            // 3. UPDATE GLUCOSE VALUE (Inside circle)
            // ───────────────────────────────────────────────────────────────────────
            glucoseValue?.text = profileUtil.fromMgdlToStringInUnits(displayMgdl)
            glucoseValue?.setTextColor(displayBgColor)

            // ───────────────────────────────────────────────────────────────────────
            // 4. UPDATE TIME AGO (Inside circle)
            // ───────────────────────────────────────────────────────────────────────
            timeAgo?.text = dateUtil.minAgoShort(displayTs)

            // ───────────────────────────────────────────────────────────────────────
            // 5. UPDATE DELTA SMALL (Inside circle - compact format)
            // ───────────────────────────────────────────────────────────────────────
            if (glucoseStatus != null && deltaSmall != null) {
                val deltaStr = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                deltaSmall.text = "Δ $deltaStr"
                deltaSmall.setTextColor(displayBgColor)
            } else {
                deltaSmall?.text = "--"
            }

            // ───────────────────────────────────────────────────────────────────────
            // 6. UPDATE TREND ARROW (Right of circle)
            // ───────────────────────────────────────────────────────────────────────
            if (trendArrowView != null && trendArrow != null) {
                trendArrowView.setImageResource(trendArrow.directionToLegacyDrawable())
                trendArrowView.visibility = android.view.View.VISIBLE
                trendArrowView.setColorFilter(displayBgColor)
            } else if (trendArrowView != null) {
                trendArrowView.visibility = android.view.View.INVISIBLE
            }

            // ───────────────────────────────────────────────────────────────────────
            // 7. UPDATE DELTA LARGE (Right of arrow)
            // ───────────────────────────────────────────────────────────────────────
            deltaValue?.let { tv ->
                if (glucoseStatus != null) {
                    tv.text = profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                    tv.setTextColor(displayBgColor)
                } else {
                    tv.text = "--"
                }
            }

            // ───────────────────────────────────────────────────────────────────────
            // 8. UPDATE ACTIVITY TEXT (Bottom right - Activity % from loop data)
            // ───────────────────────────────────────────────────────────────────────
            activityText?.let { tv ->
                tv.text = "Activity: $activityPercent%"
            }

            // ───────────────────────────────────────────────────────────────────────
            // 9. UPDATE TBR TEXT (Bottom right - Current basal rate)
            // ───────────────────────────────────────────────────────────────────────
            tbrText?.let { tv ->
                tv.text = "TBR: $tbrFormattedRate U/h"
            }
            }
        }
    }

    private fun updateProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = profileFunction.getProfile()
            val profileNameWithTime = profileFunction.getProfileNameWithRemainingTime()
            runOnUiThread {
                _binding ?: return@runOnUiThread
                val profileBackgroundColor = profile?.let {
                    if (it is ProfileSealed.EPS) {
                        if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                            app.aaps.core.ui.R.attr.ribbonWarningColor
                        else app.aaps.core.ui.R.attr.ribbonDefaultColor
                    } else app.aaps.core.ui.R.attr.ribbonDefaultColor
                } ?: app.aaps.core.ui.R.attr.ribbonCriticalColor

                val profileTextColor = profile?.let {
                    if (it is ProfileSealed.EPS) {
                        if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                            app.aaps.core.ui.R.attr.ribbonTextWarningColor
                        else app.aaps.core.ui.R.attr.ribbonTextDefaultColor
                    } else app.aaps.core.ui.R.attr.ribbonTextDefaultColor
                } ?: app.aaps.core.ui.R.attr.ribbonTextDefaultColor
                setRibbon(binding.activeProfile, profileTextColor, profileBackgroundColor, profileNameWithTime)
            }
        }
    }

    private fun updateTemporaryBasal() {
        val temporaryBasalText = overviewData.temporaryBasalText()
        val temporaryBasalColor = overviewData.temporaryBasalColor(context)
        val temporaryBasalIcon = overviewData.temporaryBasalIcon()
        val temporaryBasalDialogText = overviewData.temporaryBasalDialogText()
        runOnUiThread {
            _binding ?: return@runOnUiThread
            binding.infoLayout.baseBasal.text = temporaryBasalText
            binding.infoLayout.baseBasal.setTextColor(temporaryBasalColor)
            binding.infoLayout.baseBasalIcon.setImageResource(temporaryBasalIcon)
            binding.infoLayout.basalLayout.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.basal), message = temporaryBasalDialogText) } }
        }
    }

    private fun updateExtendedBolus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pump = activePlugin.activePump
            val extendedBolus = withContext(Dispatchers.IO) { persistenceLayer.getExtendedBolusActiveAt(dateUtil.now()) }
            val extendedBolusText = overviewData.extendedBolusText()
            val extendedBolusDialogText = overviewData.extendedBolusDialogText()
            _binding ?: return@launch
            binding.infoLayout.extendedBolus.text = extendedBolusText
            binding.infoLayout.extendedLayout.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.extended_bolus), message = extendedBolusDialogText) } }
            binding.infoLayout.extendedLayout.visibility = (extendedBolus != null && !pump.isFakingTempsByExtendedBoluses).toVisibility()
        }
    }

    private fun updateTime() {
        _binding ?: return
        binding.graphsLayout.scaleButton.text = overviewMenus.scaleString(overviewData.rangeToDisplay)
        binding.infoLayout.time.text = dateUtil.timeString(dateUtil.now())
        // Status lights
        val pump = activePlugin.activePump
        val isPatchPump = pump.pumpDescription.isPatchPump
        binding.statusLightsLayout.apply {
            cannulaOrPatch.setImageResource(if (isPatchPump) R.drawable.ic_patch_pump_outline else R.drawable.ic_cp_age_cannula)
            cannulaOrPatch.contentDescription = rh.gs(if (isPatchPump) R.string.statuslights_patch_pump_age else R.string.statuslights_cannula_age)
            insulinAge.visibility = isPatchPump.not().toVisibility()
            batteryLayout.visibility = (!isPatchPump || pump.pumpDescription.useHardwareLink).toVisibility()
            pbAge.visibility = (pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()).toVisibility()
            val useBatteryLevel = (pump.model() == PumpType.OMNIPOD_EROS)
                || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)
            pbLevel.visibility = useBatteryLevel.toVisibility()
        }
        statusLightHandler.updateStatusLights(
            binding.statusLightsLayout.cannulaAge,
            null,
            binding.statusLightsLayout.insulinAge,
            binding.statusLightsLayout.reservoirLevel,
            binding.statusLightsLayout.sensorAge,
            null,
            binding.statusLightsLayout.pbAge,
            binding.statusLightsLayout.pbLevel
        )
    }

    private fun updateIobCob() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bolusIob = withContext(Dispatchers.IO) { iobCobCalculator.calculateIobFromBolus() }.round()
            val basalIob = withContext(Dispatchers.IO) { iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended() }.round()
            val totalIob = bolusIob.iob + basalIob.basaliob
            val iobText = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIob)
            val iobDialogText =
                rh.gs(app.aaps.core.ui.R.string.format_insulin_units, totalIob) + "\n" +
                    rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob.iob) + "\n" +
                    rh.gs(app.aaps.core.ui.R.string.basal) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob.basaliob)
            val displayText = withContext(Dispatchers.IO) { iobCobCalculator.getCobInfo("Overview COB") }.displayText(rh, decimalFormatter)
            val lastCarbsTime = withContext(Dispatchers.IO) { persistenceLayer.getNewestCarbs() }?.timestamp ?: 0L

            _binding ?: return@launch
            binding.infoLayout.iob.text = iobText
            binding.infoLayout.iobLayout.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.iob), message = iobDialogText) } }
            // cob
            var cobText = displayText ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

            val constraintsProcessed = loop.lastRun?.constraintsProcessed
            val lastRun = loop.lastRun
            if (config.APS && constraintsProcessed != null && lastRun != null) {
                if (constraintsProcessed.carbsReq > 0) {
                    //only display carbsreq when carbs have not been entered recently
                    if (lastCarbsTime < lastRun.lastAPSRun) {
                        cobText += "\n" + constraintsProcessed.carbsReq + " " + rh.gs(app.aaps.core.ui.R.string.required)
                    }
                    if (carbAnimation?.isRunning == false)
                        carbAnimation?.start()
                } else {
                    carbAnimation?.stop()
                    carbAnimation?.selectDrawable(0)
                }
            }
            binding.infoLayout.cob.text = cobText
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateTemporaryTarget() {
        if (_binding == null) return  // View destroyed, skip update
        viewLifecycleOwner.lifecycleScope.launch {
            val units = profileFunction.getUnits()
            val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
            _binding ?: return@launch
            if (tempTarget != null) {
                setRibbon(
                    binding.tempTarget,
                    app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                    app.aaps.core.ui.R.attr.ribbonWarningColor,
                    profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh)
                )
            } else {
                profileFunction.getProfile()?.let { profile ->
                    // If the target is not the same as set in the profile then oref has overridden it.
                    // Rounded comparison (apsAdjustedTargetMgdl) — parity with upstream f13d7e439a "Fix TT rounding":
                    // the unrounded compare falsely flagged every loop run as "adjusted" with mmol profiles.
                    val adjustedTarget = profile.apsAdjustedTargetMgdl(loop, config, processedDeviceStatusData)
                    if (adjustedTarget != null) {
                        aapsLogger.debug("Adjusted target. Profile: ${profile.getTargetMgdl()} APS: $adjustedTarget")
                        setRibbon(
                            binding.tempTarget,
                            app.aaps.core.ui.R.attr.ribbonTextWarningColor,
                            app.aaps.core.ui.R.attr.tempTargetBackgroundColor,
                            profileUtil.toTargetRangeString(adjustedTarget, adjustedTarget, GlucoseUnit.MGDL, units)
                        )
                    } else {
                        setRibbon(
                            binding.tempTarget,
                            app.aaps.core.ui.R.attr.ribbonTextDefaultColor,
                            app.aaps.core.ui.R.attr.ribbonDefaultColor,
                            profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                        )
                    }
                }
            }
        }
    }

    private fun setRibbon(view: TextView, attrResText: Int, attrResBack: Int, text: String) {
        with(view) {
            setText(text)
            setBackgroundColor(rh.gac(context, attrResBack))
            setTextColor(rh.gac(context, attrResText))
            compoundDrawables[0]?.setTint(rh.gac(context, attrResText))
        }
    }

    private fun updateGraph() {
        _binding ?: return
        val pump = activePlugin.activePump
        val graphData = graphDataProvider.get().with(binding.graphsLayout.bgGraph, overviewData)
        val menuChartSettings = overviewMenus.setting
        if (menuChartSettings.isEmpty()) return
        graphData.addInRangeArea(
            preferences.get(UnitDoubleKey.OverviewLowMark),
            preferences.get(UnitDoubleKey.OverviewHighMark)
        )
        graphData.addBgReadings(menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal], context)
        graphData.addBucketedData()
        graphData.addTreatments(context)
        graphData.addEps(context, 0.95)
        if (menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal])
            graphData.addTherapyEvents()
        if (menuChartSettings[0][OverviewMenus.CharType.ACT.ordinal])
            graphData.addActivity(0.8)
        if ((pump.pumpDescription.isTempBasalCapable || config.AAPSCLIENT) && menuChartSettings[0][OverviewMenus.CharType.BAS.ordinal])
            graphData.addBasals()
        graphData.addTargetLine()
        graphData.addRunningModes()
        graphData.addNowLine(dateUtil.now())

        // set manual x bounds to have nice steps
        graphData.setNumVerticalLabels()
        val rangeChanged =
            lastGraphFormatRangeHours != null && lastGraphFormatRangeHours != overviewData.rangeToDisplay
        lastGraphFormatRangeHours = overviewData.rangeToDisplay
        val followLive = forceGraphViewportReset || rangeChanged ||
            viewportShouldFollowLiveRange(binding.graphsLayout.bgGraph, overviewData)
        forceGraphViewportReset = false
        graphData.formatAxis(overviewData.fromTime, overviewData.endTime, resetX = followLive)

        graphData.performUpdate()

        // 2nd graphs
        prepareGraphsIfNeeded(menuChartSettings.size)
        val secondaryGraphsData: ArrayList<GraphData> = ArrayList()

        val now = System.currentTimeMillis()
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            val secondGraphData = graphDataProvider.get().with(secondaryGraphs[g], overviewData)
            var useABSForScale = false
            var useIobForScale = false
            var useCobForScale = false
            var useDevForScale = false
            var useRatioForScale = false
            var useVarSensForScale = false
            var useDSForScale = false
            var useBGIForScale = false
            var useHRForScale = false
            var useSTEPSForScale = false
            when {
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]      -> useABSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]      -> useIobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]      -> useCobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]      -> useDevForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]      -> useBGIForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]      -> useRatioForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal]  -> useVarSensForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] -> useDSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal]       -> useHRForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]    -> useSTEPSForScale = true
            }
            val alignDevBgiScale = menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] && menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]

            if (menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]) secondGraphData.addAbsIob(useABSForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]) secondGraphData.addIob(useIobForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]) secondGraphData.addCob(useCobForScale, if (useCobForScale) 1.0 else 0.5)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]) secondGraphData.addDeviations(useDevForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]) secondGraphData.addMinusBGI(useBGIForScale, if (alignDevBgiScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]) secondGraphData.addRatio(useRatioForScale, if (useRatioForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal]) secondGraphData.addVarSens(useVarSensForScale, if (useVarSensForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] && config.isDev()) secondGraphData.addDeviationSlope(
                useDSForScale,
                if (useDSForScale) 1.0 else 0.8,
                useRatioForScale
            )
            if (menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal]) secondGraphData.addHeartRate(useHRForScale, if (useHRForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]) secondGraphData.addSteps(useSTEPSForScale, if (useSTEPSForScale) 1.0 else 0.8)

            // set manual x bounds to have nice steps
            secondGraphData.formatAxis(overviewData.fromTime, overviewData.endTime, resetX = followLive)
            secondGraphData.addNowLine(now)
            secondaryGraphsData.add(secondGraphData)
        }
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size - 1)) {
            secondaryGraphsLabel[g].text = overviewMenus.enabledTypes(g + 1)
            secondaryGraphs[g].visibility = (
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.VAR_SEN.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.HR.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.STEPS.ordinal]
                ).toVisibility()
            secondaryGraphsData[g].performUpdate()
        }
    }

    private fun updateCalcProgress() {
        _binding ?: return
        binding.progressBar.visibility = (overviewData.calcProgressPct != 100).toVisibility()
        binding.progressBar.progress = overviewData.calcProgressPct
    }

    private fun updateSensitivity() {
        _binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val lastAutosensData = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)
            val lastAutosensRatio = lastAutosensData?.let { it.autosensResult.ratio * 100 }
            if (config.AAPSCLIENT && preferences.get(BooleanNonKey.AutosensUsedOnMainPhone) ||
                !config.AAPSCLIENT && constraintChecker.isAutosensModeEnabled().value()
            ) {
                binding.infoLayout.sensitivityIcon.setImageResource(
                    lastAutosensRatio?.let {
                        when {
                            it > 100.0 -> R.drawable.ic_as_above
                            it < 100.0 -> R.drawable.ic_as_below
                            else       -> R.drawable.ic_swap_vert_black_48dp_green
                        }
                    }
                        ?: R.drawable.ic_swap_vert_black_48dp_green
                )
            } else {
                binding.infoLayout.sensitivityIcon.setImageResource(
                    lastAutosensRatio?.let {
                        when {
                            it > 100.0 -> R.drawable.ic_x_as_above
                            it < 100.0 -> R.drawable.ic_x_as_below
                            else       -> R.drawable.ic_x_swap_vert
                        }
                    }
                        ?: R.drawable.ic_x_swap_vert
                )
            }

            // Show variable sensitivity
            val profile = profileFunction.getProfile()
            val request = loop.lastRun?.request
            val isfMgdl = profile?.getProfileIsfMgdl()
            val isfForCarbs = profile?.getIsfMgdlForCarbs(dateUtil.now(), "Overview", config, processedDeviceStatusData)
            val variableSens =
                if (config.APS) request?.variableSens ?: 0.0
                else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens ?: 0.0
                else 0.0
            val ratioUsed = request?.autosensResult?.ratio ?: 1.0

            if (variableSens != isfMgdl && variableSens != 0.0 && isfMgdl != null) {
                val okDialogText: ArrayList<String> = ArrayList()
                val overViewText: ArrayList<String> = ArrayList()
                val autoSensHiddenRange = 0.0             //Hide Autosens value if equals 100%
                val autoSensMax = 100.0 + (preferences.get(DoubleKey.AutosensMax) - 1.0) * autoSensHiddenRange * 100.0
                val autoSensMin = 100.0 + (preferences.get(DoubleKey.AutosensMin) - 1.0) * autoSensHiddenRange * 100.0
                lastAutosensRatio?.let {
                    if (it !in autoSensMin..autoSensMax)
                        overViewText.add(rh.gs(app.aaps.core.ui.R.string.autosens_short, it))
                    okDialogText.add(rh.gs(app.aaps.core.ui.R.string.autosens_long, it))
                }
                overViewText.add(
                    String.format(
                        Locale.getDefault(), "%1$.1f→%2$.1f",
                        profileUtil.fromMgdlToUnits(isfMgdl, profileFunction.getUnits()),
                        profileUtil.fromMgdlToUnits(variableSens, profileFunction.getUnits())
                    )
                )
                binding.infoLayout.sensitivity.text = overViewText.joinToString("\n")
                binding.infoLayout.sensitivity.visibility = View.VISIBLE
                binding.infoLayout.variableSensitivity.visibility = View.GONE
                if (ratioUsed != 1.0 && ratioUsed != lastAutosensData?.autosensResult?.ratio)
                    okDialogText.add(rh.gs(app.aaps.core.ui.R.string.algorithm_long, ratioUsed * 100))
                okDialogText.add(rh.gs(app.aaps.core.ui.R.string.isf_for_carbs, profileUtil.fromMgdlToUnits(isfForCarbs ?: 0.0, profileFunction.getUnits())))
                if (config.APS) {
                    val aps = activePlugin.activeAPS
                    aps?.getSensitivityOverviewString()?.let {
                        okDialogText.add(it)
                    }
                }
                binding.infoLayout.asLayout.setOnClickListener { activity?.let { uiInteraction.showOkDialog(context = it, title = rh.gs(app.aaps.core.ui.R.string.sensitivity), message = okDialogText.joinToString("\n")) } }

            } else {
                binding.infoLayout.sensitivity.text =
                    lastAutosensData?.let {
                        rh.gs(app.aaps.core.ui.R.string.autosens_short, it.autosensResult.ratio * 100)
                    } ?: ""
                binding.infoLayout.variableSensitivity.visibility = View.GONE
                binding.infoLayout.sensitivity.visibility = View.VISIBLE
            }
        }
    }

    private fun updatePumpStatus() {
        _binding ?: return
        val status = overviewData.pumpStatus
        binding.pumpStatus.text = status
        binding.pumpStatusLayout.visibility = (status != "").toVisibility()
    }

    private fun updateNotification() {
        _binding ?: return
        notificationManager.cleanUp()
        val notifications = notificationManager.notifications.value
        if (notifications.isNotEmpty()) {
            binding.notifications.adapter = NotificationRecyclerViewAdapter(notifications)
            binding.notifications.visibility = View.VISIBLE
        } else {
            binding.notifications.visibility = View.GONE
        }
    }

    private inner class NotificationRecyclerViewAdapter(
        private val notificationsList: List<AapsNotification>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<NotificationRecyclerViewAdapter.NotificationsViewHolder>() {

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): NotificationsViewHolder =
            NotificationsViewHolder(LayoutInflater.from(viewGroup.context).inflate(R.layout.overview_notification_item, viewGroup, false))

        override fun onBindViewHolder(holder: NotificationsViewHolder, position: Int) {
            val notification = notificationsList[position]
            holder.binding.dismiss.tag = notification
            val buttonTextRes = notification.actions.firstOrNull()?.buttonTextRes
            if (buttonTextRes != null && buttonTextRes != 0) holder.binding.dismiss.setText(buttonTextRes)
            else holder.binding.dismiss.setText(app.aaps.core.ui.R.string.snooze)
            @Suppress("SetTextI18n")
            holder.binding.text.text = dateUtil.timeString(notification.date) + " " + notification.text
            when (notification.level) {
                NotificationLevel.URGENT       -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationUrgent))
                NotificationLevel.IMPORTANT    -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationUrgent))
                NotificationLevel.NORMAL       -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationNormal))
                NotificationLevel.LOW          -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationLow))
                NotificationLevel.INFO         -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationInfo))
                NotificationLevel.ANNOUNCEMENT -> holder.binding.cv.setBackgroundColor(rh.gac(app.aaps.core.ui.R.attr.notificationAnnouncement))
            }
        }

        override fun getItemCount(): Int = notificationsList.size

        inner class NotificationsViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {

            val binding = OverviewNotificationItemBinding.bind(itemView)

            init {
                binding.dismiss.setOnClickListener {
                    val notification = it.tag as AapsNotification
                    notification.actions.firstOrNull()?.action?.invoke()
                    notificationManager.dismiss(notification.id)
                }
            }
        }
    }

    companion object {

        // Screen size thresholds for the small-width / small-height layout of this legacy overview.
        // Upstream dropped them from Constants together with the XML overview; this fork keeps the
        // classic skin, so they live here now.
        private const val SMALL_WIDTH = 320
        private const val SMALL_HEIGHT = 480
    }
}
