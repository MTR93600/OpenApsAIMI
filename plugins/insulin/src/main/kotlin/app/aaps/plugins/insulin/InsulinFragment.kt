package app.aaps.plugins.insulin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInsulinChange
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.plugins.insulin.databinding.InsulinFragmentBinding
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class InsulinFragment : DaggerFragment() {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var config: Config
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var aapsLogger: AAPSLogger

    private var _binding: InsulinFragmentBinding? = null
    private var disposable: CompositeDisposable = CompositeDisposable()
    private val concentrationConfirmed: Boolean
        get()= preferences.get(LongNonKey.LastInsulinChange) < preferences.get(LongNonKey.LastInsulinConfirmation) || (currentInsulin == 100 && targetInsulin == 100)
    private val recentUpdate: Boolean
        get()=preferences.get(LongNonKey.LastInsulinChange) > System.currentTimeMillis() - T.mins(15).msecs()
    private val currentConcentration
        get() = preferences.get(IntNonKey.InsulinConcentration)
    private val targetConcentration
        get() = preferences.get(IntKey.InsulinRequestedConcentration)
    private val currentInsulin: Int
        get()= preferences.get(IntNonKey.InsulinConcentration)
    private val targetInsulin: Int
        get()= preferences.get(IntKey.InsulinRequestedConcentration)
    private val concentrationEnabled: Boolean
        get() = config.isEngineeringMode() && config.isDev() || config.enableAutotune()

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InsulinFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.name.text = activePlugin.activeInsulin.friendlyName
        binding.comment.text = activePlugin.activeInsulin.comment
        binding.dia.text = rh.gs(app.aaps.core.ui.R.string.dia) + ":  " + rh.gs(app.aaps.core.ui.R.string.format_hours, activePlugin.activeInsulin.dia)
        binding.graph.show(activePlugin.activeInsulin)
        binding.insulinConfirmation.visibility = (!concentrationConfirmed || (currentInsulin!=targetInsulin && recentUpdate)).toVisibility()
        binding.insulinConfirmation.setOnClickListener {
            uiInteraction.runConcentrationDialog(childFragmentManager)
        }

    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInsulinChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        updateGui()
    }

    private fun updateGui() {
            _binding?.let {
                val insulinConcentrationVisibility = (currentConcentration != 100 || targetConcentration != 100 || concentrationEnabled)
                val buttonVisibility = (!concentrationConfirmed || (currentInsulin != targetInsulin && recentUpdate)) && insulinConcentrationVisibility
                binding.insulinConfirmation.visibility = buttonVisibility.toVisibility()
                binding.concentration.visibility = insulinConcentrationVisibility.toVisibility()
                binding.concentration.text = rh.gs(R.string.insulin_current_concentration, currentConcentration)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }
}