package app.aaps.plugins.main.general.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import app.aaps.plugins.main.databinding.FragmentDashboardBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import dagger.android.support.DaggerFragment
import javax.inject.Inject

class DashboardFragment : DaggerFragment() {

    @Inject lateinit var dashboardShellDeps: DashboardShellDeps

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var shellHost: DashboardShellHost
    private lateinit var shellController: DashboardShellController

    private val viewModel: OverviewViewModel by viewModels { dashboardShellDeps.overviewViewModelFactory }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shellHost = DashboardFragmentShellHost(this)
        shellController = DashboardShellController(shellHost, dashboardShellDeps, viewModel, "DashboardFragment")
        shellController.attach(binding)
    }

    override fun onStart() {
        super.onStart()
        if (::shellController.isInitialized) {
            shellController.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::shellController.isInitialized) {
            shellController.resume()
        }
    }

    override fun onPause() {
        if (::shellController.isInitialized) {
            shellController.pause()
        }
        super.onPause()
    }

    override fun onStop() {
        if (::shellController.isInitialized) {
            shellController.stop()
        }
        super.onStop()
    }

    override fun onDestroyView() {
        if (::shellController.isInitialized) {
            shellController.destroyView()
        }
        super.onDestroyView()
        _binding = null
    }
}
