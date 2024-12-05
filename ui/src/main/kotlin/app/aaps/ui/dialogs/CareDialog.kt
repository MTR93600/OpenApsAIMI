package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.configuration.Constants.NO_SPORT_PERCENTAGE
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_LIGHT
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_MIDDLE
import app.aaps.core.data.configuration.Constants.SPORT_PERCENTAGE_HEAVY
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogCareBinding
import com.google.common.base.Joiner
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CareDialog : DialogFragmentWithDate() {

    private val TAG = "CareDialog"

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var translator: Translator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var profileUtil: ProfileUtil

    // sargius from ProfileSwitchDialog
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var config: Config
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck

    private var queryingProtection = false
    private var profileName: String? = null
    ////

    private val disposable = CompositeDisposable()
    private var options: UiInteraction.EventType = UiInteraction.EventType.BGCHECK
    //private var valuesWithUnit = mutableListOf<XXXValueWithUnit?>()
    private var valuesWithUnit = mutableListOf<ValueWithUnit?>()

    @StringRes
    private var event: Int = app.aaps.core.ui.R.string.none

    private var _binding: DialogCareBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!


    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putInt("event", event)
        savedInstanceState.putInt("options", options.ordinal)

        // from ProfileSwitchDialog
        savedInstanceState.putDouble("duration", binding.duration.value)
        savedInstanceState.putDouble("percentage", binding.percentage.value)
        savedInstanceState.putDouble("timeshift", binding.timeshift.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogCareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (savedInstanceState ?: arguments)?.let {
            event = it.getInt("event", app.aaps.core.ui.R.string.error)
            options = UiInteraction.EventType.entries.toTypedArray()[it.getInt("options", 0)]
        }

        binding.icon.setImageResource(
            when (options) {
                UiInteraction.EventType.BGCHECK        -> app.aaps.core.objects.R.drawable.ic_cp_bgcheck
                UiInteraction.EventType.SENSOR_INSERT  -> app.aaps.core.objects.R.drawable.ic_cp_cgm_insert
                UiInteraction.EventType.BATTERY_CHANGE -> app.aaps.core.objects.R.drawable.ic_cp_pump_battery
                UiInteraction.EventType.NOTE           -> app.aaps.core.objects.R.drawable.ic_cp_note
                UiInteraction.EventType.EXERCISE       -> app.aaps.core.objects.R.drawable.ic_cp_exercise
                UiInteraction.EventType.QUESTION       -> app.aaps.core.objects.R.drawable.ic_cp_question
                UiInteraction.EventType.ANNOUNCEMENT   -> app.aaps.core.objects.R.drawable.ic_cp_announcement
            }
        )

        binding.title.text = rh.gs(
            when (options) {
                UiInteraction.EventType.BGCHECK        -> app.aaps.core.ui.R.string.careportal_bgcheck
                UiInteraction.EventType.SENSOR_INSERT  -> app.aaps.core.ui.R.string.cgm_sensor_insert
                UiInteraction.EventType.BATTERY_CHANGE -> app.aaps.core.ui.R.string.pump_battery_change
                UiInteraction.EventType.NOTE           -> app.aaps.core.ui.R.string.careportal_note
                UiInteraction.EventType.EXERCISE       -> app.aaps.core.ui.R.string.careportal_exercise
                UiInteraction.EventType.QUESTION       -> app.aaps.core.ui.R.string.careportal_question
                UiInteraction.EventType.ANNOUNCEMENT   -> app.aaps.core.ui.R.string.careportal_announcement
            }
        )

        when (options) {
            UiInteraction.EventType.QUESTION,
            UiInteraction.EventType.ANNOUNCEMENT,
            UiInteraction.EventType.BGCHECK        -> {
                binding.durationLayout.visibility = View.GONE
            }

            UiInteraction.EventType.SENSOR_INSERT,
            UiInteraction.EventType.BATTERY_CHANGE -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
                binding.durationLayout.visibility = View.GONE
            }

            UiInteraction.EventType.NOTE      -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
            }

            UiInteraction.EventType.EXERCISE    -> {
                binding.bgLayout.visibility = View.GONE
                binding.bgsource.visibility = View.GONE
                binding.sportDutyLayout.visibility = View.VISIBLE
            }
        }

        // sargius added
        val lastTherapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.EXERCISE)
        val lastExerciseDuty = lastTherapyEvent?.exerciseDuty
        Log.d(TAG, "id = ${lastTherapyEvent?.id} lastTherapyEvent = $lastTherapyEvent, lastExerciseDuty = $lastExerciseDuty")
        if (lastExerciseDuty == null || lastExerciseDuty == TE.ExerciseDuty.NONE) {
            binding.switchDutyOptions.isChecked = false
            binding.tt.isChecked = false

            binding.dutyLight.isChecked = false
            binding.dutyMiddle.isChecked = false
            binding.dutyHeavy.isChecked = false

        } else {
            binding.switchDutyOptions.isChecked = true
            binding.tt.isChecked = true

            when (lastExerciseDuty) {
                TE.ExerciseDuty.LIGHT -> binding.dutyLight.isChecked = true
                TE.ExerciseDuty.MIDDLE -> binding.dutyMiddle.isChecked = true
                TE.ExerciseDuty.HEAVY -> binding.dutyHeavy.isChecked = true

                else -> {
                    binding.dutyLight.isChecked = false
                    binding.dutyMiddle.isChecked = false
                    binding.dutyHeavy.isChecked = false
                }
            }
        }

        binding.switchDutyOptions.setOnClickListener {
            if (binding.switchDutyOptions.isChecked) {
                Log.d(TAG, "Sport options checked")
                binding.dutyLight.setChecked(true)
                binding.percentage.value = SPORT_PERCENTAGE_LIGHT
                binding.tt.isChecked = true

            } else {
                Log.d(TAG, "Sport options NOT checked")
                binding.sportDuty.clearCheck()
                binding.percentage.value = NO_SPORT_PERCENTAGE
                binding.tt.isChecked = false
            }
        }

        // radio buttons
        binding.sportDuty.setOnClickListener {
            Log.d(TAG, "sportDuty clicked")
            if (binding.dutyLight.isSelected || binding.dutyMiddle.isSelected || binding.dutyHeavy.isSelected) {
                binding.switchDutyOptions.setChecked(true)
                binding.tt.isChecked = true
            }
        }

        binding.dutyLight.setOnClickListener {
            Log.d(TAG, "dutyLight clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_LIGHT
            binding.tt.isChecked = true

            if (binding.dutyLight.isChecked) {
                Log.d(TAG, "dutyLight isChecked")
            }
        }

        binding.dutyMiddle.setOnClickListener {
            Log.d(TAG, "dutyMiddle clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_MIDDLE
            binding.tt.isChecked = true
        }

        binding.dutyHeavy.setOnClickListener {
            Log.d(TAG, "dutyHeavy clicked")
            binding.switchDutyOptions.setChecked(true)
            binding.percentage.value = SPORT_PERCENTAGE_HEAVY
            binding.tt.isChecked = true
        }
        ////

        // from ProfileSwitchDialog
        binding.percentage.setParams(
            savedInstanceState?.getDouble("percentage") ?: SPORT_PERCENTAGE_LIGHT, // 100
            Constants.CPP_MIN_PERCENTAGE.toDouble(),
            Constants.CPP_MAX_PERCENTAGE.toDouble(),
            5.0,
            DecimalFormat("0"),
            false,
            binding.okcancel.ok,
            textWatcher
        )

        binding.timeshift.setParams(
            savedInstanceState?.getDouble("timeshift") ?: 0.0,
            Constants.CPP_MIN_TIMESHIFT.toDouble(),
            Constants.CPP_MAX_TIMESHIFT.toDouble(),
            1.0, DecimalFormat("0"),
            false,
            binding.okcancel.ok
        )
        ////


        val bg = profileUtil.fromMgdlToUnits(glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0)
        val bgTextWatcher: TextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (binding.sensor.isChecked) binding.meter.isChecked = true
            }
        }

        // from ProfileSwitchDialog
        // profile
        context?.let { context ->
            val profileStore = activePlugin.activeProfileSource.profile ?: return
            val profileListToCheck = profileStore.getProfileList()
            val profileList = ArrayList<CharSequence>()

            for (profileName in profileListToCheck) {
                val profileToCheck = activePlugin.activeProfileSource.profile?.getSpecificProfile(profileName.toString())

                if (profileToCheck != null && ProfileSealed.Pure(profileToCheck, activePlugin).isValid("ProfileSwitch", activePlugin.activePump, config, rh, rxBus, hardLimits, false).isValid) {
                    profileList.add(profileName)
                }
            }

            if (profileList.isEmpty()) {
                dismiss()
                return
            }

            binding.profileList.setAdapter(ArrayAdapter(context, app.aaps.core.ui.R.layout.spinner_centered, profileList))

            // set selected to actual profile
            if (profileName != null) {
                binding.profileList.setText(profileName, false)

            } else {
                binding.profileList.setText(profileList[0], false)

                for (p in profileList.indices) {
                    if (profileList[p] == profileFunction.getOriginalProfileName()) {
                        binding.profileList.setText(profileList[p], false)
                    }
                }
            }
        }

        profileFunction.getProfile()?.let { profile ->
            if (profile is ProfileSealed.EPS) {
                if (profile.value.originalPercentage != 100 || profile.value.originalTimeshift != 0L) {
                    binding.reuselayout.visibility = View.VISIBLE
                    binding.reusebutton.text = rh.gs(R.string.reuse_profile_pct_hours, profile.value.originalPercentage, T.msecs(profile.value.originalTimeshift).hours().toInt())
                    binding.reusebutton.setOnClickListener {
                        binding.percentage.value = profile.value.originalPercentage.toDouble()
                        binding.timeshift.value = T.msecs(profile.value.originalTimeshift).mins().toDouble() // hours, sargius changed
                    }
                }
            }
        }
        ////

        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            binding.bgUnits.text = rh.gs(app.aaps.core.ui.R.string.mmol)
            binding.bg.setParams(
                savedInstanceState?.getDouble("bg")
                    ?: bg, 2.0, 30.0, 0.1, DecimalFormat("0.0"), false, binding.okcancel.ok, bgTextWatcher
            )
        } else {
            binding.bgUnits.text = rh.gs(app.aaps.core.ui.R.string.mgdl)
            binding.bg.setParams(
                savedInstanceState?.getDouble("bg")
                    ?: bg, 36.0, 500.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, bgTextWatcher
            )
        }

        binding.duration.setParams(
            savedInstanceState?.getDouble("duration")
                ?: 0.0, 0.0, Constants.MAX_PROFILE_SWITCH_DURATION, 10.0, DecimalFormat("0"), false, binding.okcancel.ok
        )

        if (options == UiInteraction.EventType.NOTE || options == UiInteraction.EventType.QUESTION
                || options == UiInteraction.EventType.ANNOUNCEMENT || options == UiInteraction.EventType.EXERCISE) {

            binding.notesLayout.root.visibility = View.VISIBLE
        }

        // independent to preferences
        binding.bgLabel.labelFor = binding.bg.editTextId
        binding.durationLabel.labelFor = binding.duration.editTextId

        // from ProfileSwitchDialog
        // binding.ttLayout.visibility = View.GONE
        binding.percentageLabel.labelFor = binding.percentage.editTextId
        binding.timeshiftLabel.labelFor = binding.timeshift.editTextId
    }

    // from ProfileSwitchDialog
    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true

            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }

                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun submit(): Boolean {
        // from ProfileSwitchDialog
        if (_binding == null) return false
        Log.d(TAG, "submit")

        val profileStore = activePlugin.activeProfileSource.profile
            ?: return false

        val actions: LinkedList<String> = LinkedList()

        val duration = binding.duration.value.toInt()

        val profileName = binding.profileList.text.toString()
        actions.add(rh.gs(app.aaps.core.ui.R.string.profile) + ": " + profileName)

        val percent = binding.percentage.value.toInt()
        if (percent != 100) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.percent) + ": " + percent + "%")
        }

        val timeShift = binding.timeshift.value.toInt() // timeShift is given in Minutes here
        Log.d(TAG, "timeShift = $timeShift")
        if (timeShift != 0) {
            actions.add(rh.gs(R.string.timeshift_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_hours, timeShift.toDouble()))
        }

        val isTT = binding.duration.value > 0 && binding.percentage.value < 100 && binding.tt.isChecked
        val target = preferences.get(UnitDoubleKey.OverviewActivityTarget)
        val units = profileFunction.getUnits()

        if (isTT) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.temporary_target) + ": " + rh.gs(app.aaps.core.ui.R.string.activity))
        }
        ////

        val enteredBy = sp.getString("careportal_enteredby", "AndroidAPS")
        val unitResId = if (profileFunction.getUnits() == GlucoseUnit.MGDL) {
            app.aaps.core.ui.R.string.mgdl
        } else {
            app.aaps.core.ui.R.string.mmol
        }

        eventTime -= eventTime % 1000

        // sargius added
        val exerciseDutyOption = if (binding.switchDutyOptions.isChecked) {
            if (binding.dutyLight.isChecked) {
                TE.ExerciseDuty.LIGHT
            } else if (binding.dutyMiddle.isChecked) {
                TE.ExerciseDuty.MIDDLE
            } else if (binding.dutyHeavy.isChecked) {
                TE.ExerciseDuty.HEAVY
            } else {
                TE.ExerciseDuty.NONE
            }

        } else {
            TE.ExerciseDuty.NONE
        }
        Log.d(TAG, "exerciseDutyOption = $exerciseDutyOption")

        val therapyEvent = TE(
            timestamp = eventTime,
            type = when (options) {
                UiInteraction.EventType.BGCHECK        -> TE.Type.FINGER_STICK_BG_VALUE
                UiInteraction.EventType.SENSOR_INSERT  -> TE.Type.SENSOR_CHANGE
                UiInteraction.EventType.BATTERY_CHANGE -> TE.Type.PUMP_BATTERY_CHANGE
                UiInteraction.EventType.NOTE           -> TE.Type.NOTE
                UiInteraction.EventType.EXERCISE       -> TE.Type.EXERCISE
                UiInteraction.EventType.QUESTION       -> TE.Type.QUESTION
                UiInteraction.EventType.ANNOUNCEMENT   -> TE.Type.ANNOUNCEMENT
            },
            glucoseUnit = profileFunction.getUnits(),
            exerciseDuty = exerciseDutyOption
        )

        // val actions: LinkedList<String> = LinkedList(), // moved up
        actions.add(rh.gs(R.string.confirm_treatment))
        if (options == UiInteraction.EventType.BGCHECK || options == UiInteraction.EventType.QUESTION || options == UiInteraction.EventType.ANNOUNCEMENT) {
            val meterType =
                when {
                    binding.meter.isChecked  -> TE.MeterType.FINGER
                    binding.sensor.isChecked -> TE.MeterType.SENSOR
                    else                     -> TE.MeterType.MANUAL
                }

            actions.add(rh.gs(R.string.glucose_type) + ": " + translator.translate(meterType))
            actions.add(rh.gs(app.aaps.core.ui.R.string.bg_label) + ": " + profileUtil.stringInCurrentUnitsDetect(binding.bg.value) + " " + rh.gs(unitResId))
            therapyEvent.glucoseType = meterType
            therapyEvent.glucose = binding.bg.value
            valuesWithUnit.add(ValueWithUnit.fromGlucoseUnit(binding.bg.value, profileFunction.getUnits()))
            valuesWithUnit.add(ValueWithUnit.TEMeterType(meterType))
        }

        if (options == UiInteraction.EventType.NOTE || options == UiInteraction.EventType.EXERCISE) {
            if (duration > 0L) {
                actions.add(rh.gs(app.aaps.core.ui.R.string.duration_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, binding.duration.value.toInt()))
                therapyEvent.duration = T.mins(binding.duration.value.toLong()).msecs()
                valuesWithUnit.add(ValueWithUnit.Minute(binding.duration.value.toInt()).takeIf { !binding.duration.value.equals(0.0) })
            }
        }

        // sargius added
        if (options == UiInteraction.EventType.EXERCISE) {
            val exerciseDuty = when {
                binding.dutyLight.isChecked  -> TE.ExerciseDuty.LIGHT
                binding.dutyMiddle.isChecked  -> TE.ExerciseDuty.MIDDLE
                binding.dutyHeavy.isChecked  -> TE.ExerciseDuty.HEAVY

                else -> TE.ExerciseDuty.NONE
            }

            actions.add(rh.gs(R.string.sport_duty_options_label) + ": " + translator.translate(exerciseDuty))
            therapyEvent.exerciseDuty = exerciseDuty
            Log.d(TAG, "1. exerciseDuty = $exerciseDuty")
        }

        val notes = binding.notesLayout.notes.text.toString()
        if (notes.isNotEmpty()) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
            therapyEvent.note = notes
        }

        if (eventTimeChanged) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))
        }

        therapyEvent.enteredBy = enteredBy

        val source = when (options) {
            UiInteraction.EventType.BGCHECK        -> Sources.BgCheck
            UiInteraction.EventType.SENSOR_INSERT  -> Sources.SensorInsert
            UiInteraction.EventType.BATTERY_CHANGE -> Sources.BatteryChange
            UiInteraction.EventType.NOTE           -> Sources.Note
            UiInteraction.EventType.EXERCISE       -> Sources.Exercise
            UiInteraction.EventType.QUESTION       -> Sources.Question
            UiInteraction.EventType.ANNOUNCEMENT   -> Sources.Announcement
        }

        activity?.let { activity ->
            // from ProfileSwitchDialog, pack activity?.let {... into validity checking
            val ps = profileFunction.buildProfileSwitch2(profileStore, profileName, duration, percent, timeShift, eventTime) ?: return@let

            val validity = ProfileSealed.PS(ps, activePlugin).isValid(rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), activePlugin.activePump, config, rh, rxBus, hardLimits, false)
            if (validity.isValid) {
                OKDialog.showConfirmation(activity, rh.gs(event), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    // old method
                    valuesWithUnit.add(0, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged })
                    valuesWithUnit.add(1, ValueWithUnit.TEType(therapyEvent.type))
                    Log.d(TAG, "insert therapyEvent by insertPumpTherapyEventIfNewByTimestamp(), therapyEvent = $therapyEvent")
                    disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                        therapyEvent = therapyEvent,
                        action = Action.CAREPORTAL,
                        source = source,
                        note = notes,
                        listValues = valuesWithUnit
                    ).subscribe()

                    // transfer from ProfileSwitchDialog, converted for timeshift in Minutes
                    if (profileFunction.createProfileSwitch2(
                            profileStore = profileStore,
                            profileName = profileName,
                            durationInMinutes = duration,
                            percentage = percent,
                            timeShiftInMinutes = timeShift,
                            timestamp = eventTime,
                            action = Action.PROFILE_SWITCH,
                            source = Sources.ProfileSwitchDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                ValueWithUnit.SimpleString(profileName),
                                ValueWithUnit.Percent(percent),
                                ValueWithUnit.Minute(timeShift).takeIf { timeShift != 0 },
                                ValueWithUnit.Minute(duration).takeIf { duration != 0 }
                            )
                        )
                    ) {
                        if (percent == 90 && duration == 10) sp.putBoolean(app.aaps.core.utils.R.string.key_objectiveuseprofileswitch, true)
                        if (isTT) {
                            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                                TT(
                                    timestamp = eventTime,
                                    duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                                    reason = TT.Reason.ACTIVITY,
                                    lowTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits()),
                                    highTarget = profileUtil.convertToMgdl(target, profileFunction.getUnits())
                                ),
                                action = Action.TT,
                                source = Sources.TTDialog,
                                note = null,
                                listValues = listOf(
                                    ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                    ValueWithUnit.TETTReason(TT.Reason.ACTIVITY),
                                    ValueWithUnit.fromGlucoseUnit(target, units),
                                    ValueWithUnit.Minute(duration)
                                )
                            ).subscribe()

                        } else { // cancel temporary target if "tt" (Activity checkbox) not checked
                            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                                timestamp = eventTime,
                                action = Action.TT,
                                source = Sources.TTDialog,
                                note = null,
                                listValues = listOf()
                            ).subscribe()
                        }
                    }

                }, null)

            } else {
                OKDialog.show(
                    activity,
                    rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch),
                    HtmlHelper.fromHtml(Joiner.on("<br/>").join(validity.reasons))
                )

                return false
            }
        }

        return true
    }


    // from ProfileSwitchDialog
    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            _binding?.let { binding ->
                val isDuration = binding.duration.value > 0
                val isLowerPercentage = binding.percentage.value < 100
                binding.ttLayout.visibility = (isDuration && isLowerPercentage).toVisibility()
            }
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }
}