package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.FragmentDashboardBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import dagger.android.support.DaggerFragment
import javax.inject.Inject

class DashboardFragment : DaggerFragment() {

    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var loop: Loop
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: OverviewViewModel by viewModels {
        OverviewViewModel.Factory(
            requireContext(),
            lastBgData,
            trendCalculator,
            iobCobCalculator,
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
                    ToastUtils.infoToast(requireContext(), resourceHelper.gs(R.string.dashboard_nav_history))
                    true
                }
                R.id.dashboard_nav_bolus -> {
                    ToastUtils.infoToast(requireContext(), resourceHelper.gs(R.string.dashboard_nav_bolus))
                    true
                }
                R.id.dashboard_nav_adjustments -> {
                    ToastUtils.infoToast(requireContext(), resourceHelper.gs(R.string.dashboard_nav_adjustments))
                    true
                }
                R.id.dashboard_nav_settings -> {
                    ToastUtils.infoToast(requireContext(), resourceHelper.gs(R.string.dashboard_nav_settings))
                    true
                }
                else -> true
            }
        }

        viewModel.statusCardState.observe(viewLifecycleOwner) { binding.statusCard.update(it) }
        viewModel.adjustmentState.observe(viewLifecycleOwner) { binding.adjustmentStatus.update(it) }
        viewModel.graphMessage.observe(viewLifecycleOwner) { binding.glucoseGraph.update(it) }
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
}
