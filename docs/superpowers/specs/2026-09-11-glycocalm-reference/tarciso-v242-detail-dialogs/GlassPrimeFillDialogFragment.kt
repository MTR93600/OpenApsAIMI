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
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.main.constraints.ConstraintObject
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.ValueWithUnit
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.InsertIfNewByTimestampTherapyEventTransaction
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

class GlassPrimeFillDialogFragment : DaggerDialogFragment() {

    @Inject lateinit var repository: AppRepository
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var sp: SP

    private val disposable = CompositeDisposable()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread after a successful save (after auto-dismiss feedback). */
    var onSaved: (() -> Unit)? = null

    private var siteChecked by mutableStateOf(true)
    private var cartridgeChecked by mutableStateOf(false)
    private var primeAmount by mutableStateOf(0.0)
    private var eventTimestamp by mutableStateOf(System.currentTimeMillis())
    private var timeChanged by mutableStateOf(false)
    private var notes by mutableStateOf("")
    private var isSaving by mutableStateOf(false)
    private var isSaved by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    private val step: Double
        get() {
            val pumpStep = try {
                activePlugin.activePump.pumpDescription.bolusStep
            } catch (e: Exception) {
                0.0
            }
            return if (pumpStep > 0) pumpStep else 0.1
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val isDark = try {
                    sp.getString(app.aaps.core.utils.R.string.key_use_dark_mode, "dark") == "dark"
                } catch (e: Exception) {
                    true
                }
                GlassOverviewTheme(isDarkMode = isDark) {
                    GlassPrimeFillDialogScreen(
                        isDark = isDark,
                        siteChecked = siteChecked,
                        cartridgeChecked = cartridgeChecked,
                        primeAmountText = formatAmount(primeAmount),
                        formattedDate = formatDate(eventTimestamp),
                        formattedTime = formatTime(eventTimestamp),
                        notes = notes,
                        isSaving = isSaving,
                        isSaved = isSaved,
                        errorMessage = errorMessage,
                        eventTimestamp = eventTimestamp,
                        onSiteToggle = { siteChecked = !siteChecked },
                        onCartridgeToggle = { cartridgeChecked = !cartridgeChecked },
                        onPrimeMinus = { primeAmount = max(0.0, round2(primeAmount - step)) },
                        onPrimePlus = { primeAmount = round2(primeAmount + step) },
                        onPrimePreset = { preset -> primeAmount = round2(primeAmount + preset) },
                        onNotesChange = { notes = it },
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
                        onSetToNow = {
                            eventTimestamp = System.currentTimeMillis()
                            timeChanged = false
                        },
                        onDismiss = { dismiss() },
                        onSave = { submitPrimeFill() }
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

    private fun submitPrimeFill() {
        if (isSaving || isSaved) return
        val primeAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(primeAmount, aapsLogger)).value()
        if (primeAfterConstraints <= 0 && !siteChecked && !cartridgeChecked) {
            errorMessage = "Select an action: prime dose, site or cartridge change"
            return
        }
        isSaving = true
        errorMessage = null

        try {
            val eventTime = eventTimestamp - eventTimestamp % 1000
            val notesStr = notes.trim()
            val timeValue = ValueWithUnit.Timestamp(eventTime).takeIf { timeChanged }

            if (primeAfterConstraints > 0) {
                uel.log(
                    UserEntry.Action.PRIME_BOLUS, UserEntry.Sources.FillDialog,
                    notesStr,
                    ValueWithUnit.Insulin(primeAfterConstraints)
                )
                requestPrimeBolus(primeAfterConstraints, notesStr)
            }
            if (siteChecked) {
                uel.log(
                    UserEntry.Action.SITE_CHANGE, UserEntry.Sources.FillDialog,
                    notesStr,
                    timeValue,
                    ValueWithUnit.TherapyEventType(TherapyEvent.Type.CANNULA_CHANGE)
                )
                disposable += repository.runTransactionForResult(
                    InsertIfNewByTimestampTherapyEventTransaction(
                        timestamp = eventTime,
                        type = TherapyEvent.Type.CANNULA_CHANGE,
                        note = notesStr,
                        glucoseUnit = TherapyEvent.GlucoseUnit.MGDL
                    )
                ).subscribeOn(Schedulers.io())
                    .subscribe(
                        { result -> result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted therapy event $it") } },
                        { aapsLogger.error(LTag.DATABASE, "Error while saving therapy event", it) }
                    )
            }
            if (cartridgeChecked) {
                uel.log(
                    UserEntry.Action.RESERVOIR_CHANGE, UserEntry.Sources.FillDialog,
                    notesStr,
                    timeValue,
                    ValueWithUnit.TherapyEventType(TherapyEvent.Type.INSULIN_CHANGE)
                )
                disposable += repository.runTransactionForResult(
                    InsertIfNewByTimestampTherapyEventTransaction(
                        timestamp = eventTime + 1000,
                        type = TherapyEvent.Type.INSULIN_CHANGE,
                        note = notesStr,
                        glucoseUnit = TherapyEvent.GlucoseUnit.MGDL
                    )
                ).subscribeOn(Schedulers.io())
                    .subscribe(
                        { result -> result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted therapy event $it") } },
                        { aapsLogger.error(LTag.DATABASE, "Error while saving therapy event", it) }
                    )
            }
            mainHandler.post {
                isSaving = false
                isSaved = true
                mainHandler.postDelayed({
                    try {
                        dismiss()
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.UI, "Error dismissing prime/fill dialog", e)
                    }
                    try {
                        onSaved?.invoke()
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.UI, "Error in prime/fill dialog onSaved callback", e)
                    }
                }, 900)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.DATABASE, "Error while saving prime/fill", e)
            isSaving = false
            errorMessage = "Error saving: ${e.message}"
        }
    }

    private fun requestPrimeBolus(insulin: Double, notes: String) {
        try {
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.insulin = insulin
            detailedBolusInfo.context = requireContext()
            detailedBolusInfo.bolusType = DetailedBolusInfo.BolusType.PRIMING
            detailedBolusInfo.notes = notes
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    if (!result.success) {
                        uiInteraction.runAlarm(
                            result.comment,
                            rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror),
                            app.aaps.core.ui.R.raw.boluserror
                        )
                    }
                }
            })
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Error requesting prime bolus", e)
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

    private fun round2(value: Double): Double =
        (value * 100).roundToInt() / 100.0

    private fun formatAmount(value: Double): String {
        val rounded = round2(value)
        return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString()
        else String.format(Locale.US, "%.2f", rounded).trimEnd('0')
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MM/dd/yy", Locale.US).format(timestamp)
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("hh:mm a", Locale.US).format(timestamp)
    }
}
