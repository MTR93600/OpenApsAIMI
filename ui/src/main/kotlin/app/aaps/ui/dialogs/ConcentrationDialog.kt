package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventInsulinChange
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.ui.R
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.databinding.DialogConcentrationBinding
import dagger.android.HasAndroidInjector
import java.text.DecimalFormat
import javax.inject.Inject

class ConcentrationDialog : DialogFragmentWithDate() {

    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var rxBus: RxBus

    var helperActivity: TranslatedDaggerAppCompatActivity? = null
    private var _binding: DialogConcentrationBinding? = null
    private val currentInsulin: Int
        get()= preferences.get(IntNonKey.InsulinConcentration)
    private val targetInsulin: Int
        get()= preferences.get(IntKey.InsulinRequestedConcentration)

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putInt("concentration", binding.concentration.value.toInt())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogConcentrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.concentration.setParams(
            0.0, 40.0, 200.0, 10.0, DecimalFormat("0"), false, binding.okcancel.ok
        )
        if (currentInsulin == targetInsulin)
            binding.message.text = rh.gs(R.string.concentration_title, currentInsulin)
        else
            binding.message.text = rh.gs(R.string.concentration_title2, currentInsulin, targetInsulin)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun dismiss() {
        super.dismissAllowingStateLoss()
        helperActivity?.finish()
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val concentration = binding.concentration.value.toInt()
        val now = System.currentTimeMillis()
        when(concentration) {
            targetInsulin -> {
                activity?.let { activity ->
                    OKDialog.showConfirmation(activity, rh.gs(R.string.concentration), rh.gs(R.string.ins_concentration_confirmed, targetInsulin), {
                        uel.log(action = Action.RESERVOIR_CHANGE, source = Sources.ConcentrationDialog, value = ValueWithUnit.InsulinConcentration(concentration))
                    })
                    preferences.put(IntNonKey.InsulinConcentration, targetInsulin)
                    preferences.put(LongNonKey.LastInsulinConfirmation, now)
                    rxBus.send(EventInsulinChange())
                }
            }
            currentInsulin -> {
                activity?.let { activity ->
                    OKDialog.showConfirmation(activity, rh.gs(R.string.concentration), rh.gs(R.string.ins_concentration_unchanged, currentInsulin, targetInsulin), {
                        uel.log(action = Action.RESERVOIR_CHANGE, source = Sources.ConcentrationDialog, value = ValueWithUnit.InsulinConcentration(concentration))
                    })
                    preferences.put(IntKey.InsulinRequestedConcentration, concentration)
                    preferences.put(LongNonKey.LastInsulinConfirmation, now)
                    rxBus.send(EventInsulinChange())
                }
            }
            else    -> {
                activity?.let { activity ->
                    OKDialog.show(activity, rh.gs(R.string.concentration), rh.gs(R.string.concentration_not_confirmed))}
            }
        }
        return true
    }
}
