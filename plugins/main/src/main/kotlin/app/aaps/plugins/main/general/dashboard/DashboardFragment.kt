package app.aaps.plugins.main.general.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.fragment.app.viewModels
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.overview.OverviewMenus.CharType
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.UIRunnable
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.FragmentDashboardBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.AdjustmentCardState
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.overview.graphData.GraphData
import dagger.android.support.DaggerFragment
import javax.inject.Inject
import javax.inject.Provider

class DashboardFragment : DaggerFragment() {

    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var loop: Loop
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var graphDataProvider: Provider<GraphData>
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var automation: Automation
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var dexcomBoyda: DexcomBoyda

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private fun sensor(): Boolean {
        val ctx = context ?: return false

        // BG source actuellement utilisée dans AAPS
        val bgSource = activePlugin.activeBgSource as? PluginBase

        // On force en String pour pouvoir utiliser contains(ignoreCase = true)
        val pluginName = bgSource
            ?.pluginDescription
            ?.pluginName
            ?.toString()
            .orEmpty()

        return when {
            pluginName.contains("dexcom", ignoreCase = true) -> {
                // Essaye d’ouvrir l’appli Dexcom
                launchPackageIfExists(ctx, "com.dexcom.g6") ||   // à adapter selon ta config
                    launchPackageIfExists(ctx, "com.dexcom.one") ||  // ex. Dexcom One
                    openSettings()                                   // fallback
            }
            pluginName.contains("xdrip", ignoreCase = true) -> {
                // Essaye d’ouvrir xDrip
                launchPackageIfExists(ctx, "com.eveningoutpost.dexdrip") || openSettings()
            }
            else -> {
                // Ni Dexcom ni xDrip détecté → on ouvre les prefs
                openSettings()
            }
        }
    }
    private fun launchPackageIfExists(ctx: android.content.Context, packageName: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            startActivity(intent)
            true
        } else {
            false
        }
    }

    private val viewModel: OverviewViewModel by viewModels {
        OverviewViewModel.Factory(
            requireContext(),
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
            fabricPrivacy
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.bottomNavigation.selectedItemId = R.id.dashboard_nav_home
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.dashboard_nav_home -> {
                    true
                }
                R.id.dashboard_nav_history -> {
                    openHistory()
                }
                R.id.dashboard_nav_bolus -> {
                    openBolus()
                }
                R.id.dashboard_nav_adjustments -> {
                    openModes()
                }
                R.id.dashboard_nav_settings -> {
                    openSensorApp()
                }
                else -> true
            }
        }

        viewModel.statusCardState.observe(viewLifecycleOwner) { binding.statusCard.update(it) }
        viewModel.adjustmentState.observe(viewLifecycleOwner) { state -> state?.let { binding.adjustmentStatus.update(it) } }
        viewModel.graphMessage.observe(viewLifecycleOwner) {
            binding.glucoseGraph.setUpdateMessage(it)
            updateGraph()
        }

        binding.adjustmentStatus.isClickable = true
        binding.adjustmentStatus.isFocusable = true
        binding.adjustmentStatus.setOnClickListener { openAdjustmentDetails() }

        binding.statusCard.isClickable = true
        binding.statusCard.isFocusable = true
        binding.statusCard.setOnClickListener { openLoopDialog() }
        binding.glucoseGraph.graph.gridLabelRenderer?.gridColor = resourceHelper.gac(requireContext(), app.aaps.core.ui.R.attr.graphGrid)
        binding.glucoseGraph.graph.gridLabelRenderer?.reloadStyles()
    }

    override fun onResume() {
        super.onResume()
        viewModel.start()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openHistory(): Boolean {
        startActivity(Intent(requireContext(), uiInteraction.historyBrowseActivity))
        return true
    }

    private fun openBolus(): Boolean {
        activity?.let { activity ->
            protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                uiInteraction.runInsulinDialog(childFragmentManager)
            })
        }
        return true
    }

    private fun openModes(): Boolean {
        val context = context ?: return false
        startActivity(Intent(context, DashboardModesActivity::class.java))
        return true
    }

    private fun openLoopDialog() {
        activity?.let { activity ->
            protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                if (isAdded) uiInteraction.runLoopDialog(childFragmentManager, 1)
            })
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
        val context = context ?: return false
        val packageManager = context.packageManager
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: throw ActivityNotFoundException()
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            aapsLogger.debug(LTag.CORE, "Error opening CGM app")
            false
        }
    }

    private fun openAdjustmentDetails(): Boolean {
        val context = context ?: return false
        val state: AdjustmentCardState = viewModel.adjustmentState.value ?: return false
        val intent = Intent(context, AdjustmentDetailsActivity::class.java)
            .putExtra(AdjustmentDetailsActivity.EXTRA_ADJUSTMENT_STATE, state)
        startActivity(intent)
        return true
    }

    private fun openSettings(): Boolean {
        val intent = Intent(requireContext(), uiInteraction.preferencesActivity)
            .putExtra(UiInteraction.PLUGIN_NAME, resourceHelper.gs(app.aaps.core.ui.R.string.nav_plugin_preferences))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        return true
    }

    private fun updateGraph() {
        if (_binding == null) return
        val menuChartSettings = overviewMenus.setting
        if (menuChartSettings.isEmpty()) return
        val graphData = graphDataProvider.get().with(binding.glucoseGraph.graph, overviewData)
        val now = dateUtil.now()

        val hasBgData = overviewData.bgReadingsArray.isNotEmpty()
        binding.glucoseGraph.showPlaceholder(!hasBgData)
        if (!hasBgData) {
            aapsLogger.debug(LTag.CORE, "Dashboard graph skipped: no BG data")
            return
        }

        graphData.addInRangeArea(
            overviewData.fromTime,
            overviewData.endTime,
            preferences.get(UnitDoubleKey.OverviewLowMark),
            preferences.get(UnitDoubleKey.OverviewHighMark)
        )
        graphData.addBgReadings(menuChartSettings[0][CharType.PRE.ordinal], context)
        graphData.addBucketedData()
        graphData.addTreatments(context)
        if ((config.AAPSCLIENT || activePlugin.activePump.pumpDescription.isTempBasalCapable) && menuChartSettings[0][CharType.BAS.ordinal]) {
            graphData.addBasals()
        }
        graphData.addTargetLine()
        graphData.addRunningModes()
        graphData.addNowLine(now)
        graphData.setNumVerticalLabels()
        graphData.formatAxis(overviewData.fromTime, overviewData.endTime)
        graphData.performUpdate()
    }
}
