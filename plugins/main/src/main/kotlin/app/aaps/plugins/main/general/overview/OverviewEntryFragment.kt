package app.aaps.plugins.main.general.overview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.commit
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.plugins.main.databinding.FragmentOverviewEntryBinding
import app.aaps.plugins.main.general.dashboard.DashboardFragment
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class OverviewEntryFragment : DaggerFragment() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private var _binding: FragmentOverviewEntryBinding? = null
    private val binding get() = _binding!!
    private val disposable = CompositeDisposable()
    private var currentTag: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOverviewEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentTag = childFragmentManager.findFragmentById(binding.overviewEntryContainer.id)?.tag
        showSelectedOverview()
    }

    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           if (it.isChanged(BooleanKey.OverviewUseDashboardLayout.key)) showSelectedOverview()
                       }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showSelectedOverview() {
        val binding = _binding ?: return
        val useDashboard = preferences.get(BooleanKey.OverviewUseDashboardLayout)
        val newTag = if (useDashboard) DASHBOARD_TAG else OVERVIEW_TAG
        if (newTag == currentTag && childFragmentManager.findFragmentByTag(newTag) != null) return

        val fragment = if (useDashboard) DashboardFragment() else OverviewFragment()
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(binding.overviewEntryContainer.id, fragment, newTag)
        }
        currentTag = newTag
    }

    companion object {
        private const val DASHBOARD_TAG = "overview_dashboard"
        private const val OVERVIEW_TAG = "overview_legacy"
    }
}
