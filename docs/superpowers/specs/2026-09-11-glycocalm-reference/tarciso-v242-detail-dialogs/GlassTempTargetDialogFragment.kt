package app.aaps.plugins.main.general.overview.glass

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.interfaces.configuration.Constants
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.DefaultValueHelper
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.ValueWithUnit
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.CancelCurrentTemporaryTargetIfAnyTransaction
import app.aaps.database.impl.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

class GlassTempTargetDialogFragment : DaggerDialogFragment() {

    @Inject lateinit var repository: AppRepository
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var defaultValueHelper: DefaultValueHelper
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var sp: SP

    private val disposable = CompositeDisposable()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread after a successful save (after auto-dismiss feedback). */
    var onSaved: (() -> Unit)? = null

    private val reasonOptions = listOf(
        "Exercise / Sports",
        "Pre-bolus / Eating Soon",
        "Hypo treatment & prevention",
        "Custom temporary adjustment"
    )
    private val reasonValues = listOf(
        TemporaryTarget.Reason.ACTIVITY,
        TemporaryTarget.Reason.EATING_SOON,
        TemporaryTarget.Reason.HYPOGLYCEMIA,
        TemporaryTarget.Reason.CUSTOM
    )

    private var isMmol by mutableStateOf(false)
    private var target by mutableStateOf(140.0)
    private var durationMin by mutableStateOf(60)
    private var reasonIndex by mutableStateOf(0)
    private var selectedPreset by mutableStateOf<TempTargetPresetKind?>(TempTargetPresetKind.ACTIVITY)
    private var eventTimestamp by mutableStateOf(System.currentTimeMillis())
    private var timeChanged by mutableStateOf(false)
    private var hasActiveTT by mutableStateOf(false)
    private var isSaving by mutableStateOf(false)
    private var isSaved by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    private val targetStep: Double get() = if (isMmol) 0.5 else 5.0
    private val targetMin: Double get() = if (isMmol) Constants.MIN_TT_MMOL else Constants.MIN_TT_MGDL
    private val targetMax: Double get() = if (isMmol) Constants.MAX_TT_MMOL else Constants.MAX_TT_MGDL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isMmol = try {
            profileFunction.getUnits() == GlucoseUnit.MMOL
        } catch (e: Exception) {
            false
        }
        target = try {
            defaultValueHelper.determineActivityTT()
        } catch (e: Exception) {
            if (isMmol) 7.8 else 140.0
        }
        durationMin = 6666
        hasActiveTT = try {
            repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet() is ValueWrapper.Existing
        } catch (e: Exception) {
            false
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val isDark = try {
                    sp.getString(app.aaps.core.utils.R.string.key_use_dark_mode, "dark") == "dark"
                } catch (e: Exception) {
                    true
                }
                GlassOverviewTheme(isDarkMode = isDark) {
                    GlassTempTargetDialogScreen(
                        isDark = isDark,
                        targetText = formatTarget(target),
                        rangeText = "Range: ${formatRange(targetMin)} - ${formatRange(targetMax)}",
                        unitLabel = if (isMmol) "mmol/L" else "mg/dL",
                        durationMin = durationMin,
                        presets = buildPresets(),
                        selectedPreset = selectedPreset,
                        reasonOptions = reasonOptions,
                        selectedReasonIndex = reasonIndex,
                        hasActiveTT = hasActiveTT,
                        formattedDate = formatDate(eventTimestamp),
                        formattedTime = formatTime(eventTimestamp),
                        isSaving = isSaving,
                        isSaved = isSaved,
                        errorMessage = errorMessage,
                        eventTimestamp = eventTimestamp,
                        onTargetMinus = {
                            target = max(targetMin, roundTarget(target - targetStep))
                            selectedPreset = null
                        },
                        onTargetPlus = {
                            target = min(targetMax, roundTarget(target + targetStep))
                            selectedPreset = null
                        },
                        onDurationMinus = {
                            durationMin = max(15, durationMin - 15)
                            selectedPreset = null
                        },
                        onDurationPlus = {
                            durationMin = min(9999, durationMin + 15)
                            selectedPreset = null
                        },
                        onPresetSelected = { kind -> applyPreset(kind) },
                        onReasonSelected = { index ->
                            reasonIndex = index
                            selectedPreset = null
                        },
                        onDateSet = { year, month, day ->
                            val cal = Calendar.getInstance().apply {
                                timeInMillis = eventTimestamp
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, day)
                            }
                            eventTimestamp = cal.timeInMillis
                            timeChanged = true
                        },
                        onTimeSet = { hour, minute ->
                            val cal = Calendar.getInstance().apply {
                                timeInMillis = eventTimestamp
                                set(Calendar.HOUR_OF_DAY, hour)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            eventTimestamp = cal.timeInMillis
                            timeChanged = true
                        },
                        onCancelActiveTT = { cancelActiveTT() },
                        onDismiss = { dismiss() },
                        onSave = { submitTempTarget() }
                    )
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.CENTER)
            window.attributes.windowAnimations = android.R.style.Animation_Dialog
        }
        return dialog
    }

    private fun buildPresets(): List<TempTargetPreset> {
        val eating = try {
            defaultValueHelper.determineEatingSoonTT()
        } catch (e: Exception) {
            if (isMmol) 4.4 else 80.0
        }
        val activity = try {
            defaultValueHelper.determineActivityTT()
        } catch (e: Exception) {
            if (isMmol) 7.8 else 140.0
        }
        val hypo = try {
            defaultValueHelper.determineHypoTT()
        } catch (e: Exception) {
            if (isMmol) 7.2 else 130.0
        }
        val unit = if (isMmol) "mmol/L" else "mg/dL"
        return listOf(
            TempTargetPreset(TempTargetPresetKind.EATING, "Eating Soon", "${formatTarget(eating)} $unit"),
            TempTargetPreset(TempTargetPresetKind.ACTIVITY, "Activity", "${formatTarget(activity)} $unit"),
            TempTargetPreset(TempTargetPresetKind.HYPO, "Hypo", "${formatTarget(hypo)} $unit")
        )
    }

    private fun applyPreset(kind: TempTargetPresetKind) {
        try {
            when (kind) {
                TempTargetPresetKind.EATING -> {
                    target = defaultValueHelper.determineEatingSoonTT()
                    durationMin = defaultValueHelper.determineEatingSoonTTDuration()
                    reasonIndex = 1
                }
                TempTargetPresetKind.ACTIVITY -> {
                    target = defaultValueHelper.determineActivityTT()
                    durationMin = defaultValueHelper.determineActivityTTDuration()
                    reasonIndex = 0
                }
                TempTargetPresetKind.HYPO -> {
                    target = defaultValueHelper.determineHypoTT()
                    durationMin = defaultValueHelper.determineHypoTTDuration()
                    reasonIndex = 2
                }
            }
            selectedPreset = kind
        } catch (e: Exception) {
            aapsLogger.error(LTag.UI, "Error applying temp target preset", e)
        }
    }

    private fun submitTempTarget() {
        if (isSaving || isSaved) return
        isSaving = true
        errorMessage = null

        try {
            val eventTime = eventTimestamp - eventTimestamp % 1000
            val units = profileFunction.getUnits()
            val reason = reasonValues[reasonIndex]
            val timeValue = ValueWithUnit.Timestamp(eventTime).takeIf { timeChanged }

            uel.log(
                UserEntry.Action.TT, UserEntry.Sources.TTDialog,
                timeValue,
                ValueWithUnit.TherapyEventTTReason(reason),
                ValueWithUnit.fromGlucoseUnit(target, units.asText),
                ValueWithUnit.Minute(durationMin)
            )

            disposable += repository.runTransactionForResult(
                InsertAndCancelCurrentTemporaryTargetTransaction(
                    timestamp = eventTime,
                    duration = TimeUnit.MINUTES.toMillis(durationMin.toLong()),
                    reason = reason,
                    lowTarget = profileUtil.convertToMgdl(target, units),
                    highTarget = profileUtil.convertToMgdl(target, units)
                )
            ).subscribeOn(Schedulers.io())
                .subscribe(
                    { result ->
                        result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                        result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                        if (durationMin == 10) {
                            sp.putBoolean(app.aaps.core.utils.R.string.key_objectiveusetemptarget, true)
                        }
                        mainHandler.post {
                            isSaving = false
                            isSaved = true
                            hasActiveTT = true
                            mainHandler.postDelayed({
                                try {
                                    dismiss()
                                } catch (e: Exception) {
                                    aapsLogger.error(LTag.UI, "Error dismissing temp target dialog", e)
                                }
                                try {
                                    onSaved?.invoke()
                                } catch (e: Exception) {
                                    aapsLogger.error(LTag.UI, "Error in temp target onSaved callback", e)
                                }
                            }, 900)
                        }
                    },
                    { e ->
                        aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", e)
                        mainHandler.post {
                            isSaving = false
                            errorMessage = "Error saving: ${e.message}"
                        }
                    }
                )
        } catch (e: Exception) {
            aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", e)
            isSaving = false
            errorMessage = "Error saving: ${e.message}"
        }
    }

    private fun cancelActiveTT() {
        if (isSaving || isSaved) return
        isSaving = true
        errorMessage = null
        try {
            val eventTime = dateUtil.now()
            uel.log(
                UserEntry.Action.CANCEL_TT,
                UserEntry.Sources.TTDialog,
                ValueWithUnit.Timestamp(eventTime).takeIf { timeChanged }
            )
            disposable += repository.runTransactionForResult(CancelCurrentTemporaryTargetIfAnyTransaction(eventTime))
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { result ->
                        result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                        mainHandler.post {
                            isSaving = false
                            isSaved = true
                            hasActiveTT = false
                            mainHandler.postDelayed({
                                try {
                                    dismiss()
                                } catch (e: Exception) {
                                    aapsLogger.error(LTag.UI, "Error dismissing temp target dialog", e)
                                }
                                try {
                                    onSaved?.invoke()
                                } catch (e: Exception) {
                                    aapsLogger.error(LTag.UI, "Error in temp target onSaved callback", e)
                                }
                            }, 900)
                        }
                    },
                    { e ->
                        aapsLogger.error(LTag.DATABASE, "Error while canceling temporary target", e)
                        mainHandler.post {
                            isSaving = false
                            errorMessage = "Error saving: ${e.message}"
                        }
                    }
                )
        } catch (e: Exception) {
            aapsLogger.error(LTag.DATABASE, "Error while canceling temporary target", e)
            isSaving = false
            errorMessage = "Error saving: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.let { activity ->
            protectionCheck.queryProtection(
                activity,
                ProtectionCheck.Protection.BOLUS,
                {},
                {
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    dismiss()
                },
                {
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    dismiss()
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        disposable.clear()
    }

    private fun roundTarget(value: Double): Double =
        if (isMmol) (value * 10).toInt() / 10.0
        else value.toInt().toDouble()

    private fun formatTarget(value: Double): String =
        if (isMmol) String.format(Locale.US, "%.1f", value)
        else value.toInt().toString()

    private fun formatRange(value: Double): String =
        if (isMmol) String.format(Locale.US, "%.1f", value)
        else value.toInt().toString()

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MM/dd/yyyy", Locale.US).format(timestamp)
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.US).format(timestamp)
    }
}
