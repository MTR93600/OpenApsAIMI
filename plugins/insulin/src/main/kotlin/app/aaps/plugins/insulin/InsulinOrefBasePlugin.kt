package app.aaps.plugins.insulin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.iob.Iob
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventTherapyEventChange
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlin.math.exp
import kotlin.math.pow

/**
 * Created by adrian on 13.08.2017.
 *
 * parameters are injected from child class
 *
 */
abstract class InsulinOrefBasePlugin(
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
    private val persistenceLayer: PersistenceLayer,
    val profileFunction: ProfileFunction,
    val rxBus: RxBus,
    aapsLogger: AAPSLogger,
    val config: Config,
    val hardLimits: HardLimits,
    val uiInteraction: UiInteraction,
    val context: Context
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.INSULIN)
        .fragmentClass(InsulinFragment::class.java.name)
        .pluginIcon(R.drawable.ic_insulin)
        .shortName(R.string.insulin_shortname)
        .visibleByDefault(false)
        .neverVisible(config.AAPSCLIENT),
    aapsLogger, rh
), Insulin, PluginConstraints {
    private var lastWarned: Long = 0
    private var disposable: CompositeDisposable = CompositeDisposable()
    private val currentInsulin: Int
        get()= preferences.get(IntNonKey.InsulinConcentration)
    private val targetInsulin: Int
        get()= preferences.get(IntKey.InsulinRequestedConcentration)
    private val concentrationConfirmed: Boolean
        get()= preferences.get(LongNonKey.LastInsulinChange) < preferences.get(LongNonKey.LastInsulinConfirmation) || currentInsulin == 100
    val MAX_INSULIN = T.days(7).msecs()
    private val recentUpdate: Boolean
        get()=preferences.get(LongNonKey.LastInsulinChange) > System.currentTimeMillis() - T.mins(15).msecs()
    val concentrationEnabled: Boolean
        get() = config.isEngineeringMode() && config.isDev() || config.enableAutotune()

    override val dia
        get(): Double {
            val dia = userDefinedDia
            return if (dia >= hardLimits.minDia()) {
                dia
            } else {
                sendShortDiaNotification(dia)
                hardLimits.minDia()
            }
        }

    open fun sendShortDiaNotification(dia: Double) {
        if (System.currentTimeMillis() - lastWarned > 60 * 1000) {
            lastWarned = System.currentTimeMillis()
            uiInteraction.addNotification(Notification.SHORT_DIA, String.format(notificationPattern, dia, hardLimits.minDia()), Notification.URGENT)
        }
    }

    private val notificationPattern: String
        get() = rh.gs(R.string.dia_too_short)

    open val userDefinedDia: Double
        get() {
            val profile = profileFunction.getProfile()
            return profile?.dia ?: hardLimits.minDia()
        }

    override fun iobCalcForTreatment(bolus: BS, time: Long, dia: Double): Iob {
        assert(dia != 0.0)
        assert(peak != 0)
        val result = Iob()
        if (bolus.amount != 0.0) {
            val bolusTime = bolus.timestamp
            val t = (time - bolusTime) / 1000.0 / 60.0
            val td = dia * 60 //getDIA() always >= MIN_DIA
            val tp = peak.toDouble()
            // force the IOB to 0 if over DIA hours have passed
            if (t < td) {
                val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
                val a = 2 * tau / td
                val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
                result.activityContrib = bolus.amount * (s / tau.pow(2.0)) * t * (1 - t / td) * exp(-t / tau)
                result.iobContrib = bolus.amount * (1 - s * (1 - a) * ((t.pow(2.0) / (tau * td * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1))
            }
        }
        return result
    }

    override val iCfg: ICfg
        get() = ICfg(friendlyName, (dia * 1000.0 * 3600.0).toLong(), T.mins(peak.toLong()).msecs())

    override val comment
        get(): String {
            var comment = commentStandardText()
            val userDia = userDefinedDia
            if (userDia < hardLimits.minDia()) {
                comment += "\n" + rh.gs(R.string.dia_too_short, userDia, hardLimits.minDia())
            }
            return comment
        }

    abstract override val peak: Int
    abstract fun commentStandardText(): String

    override fun onStart() {
        super.onStart()
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        swapAdapter()
        if (!concentrationEnabled) { // Concentration not allowed without engineering mode
            preferences.put(IntKey.InsulinRequestedConcentration, 100)
        }
    }

    override fun onStop() {
        super.onStop()
        disposable.clear()
    }

    @Synchronized
    fun swapAdapter() { // Launch Popup to confirm Insulin concentration on Reservoir change
        val now = System.currentTimeMillis()
        disposable += persistenceLayer
            .getTherapyEventDataFromTime(now - MAX_INSULIN, false)
            .observeOn(aapsSchedulers.main)
            .subscribe { list ->
                list.filter { te -> te.type == TE.Type.INSULIN_CHANGE }.firstOrNull()?.let {
                    preferences.put(LongNonKey.LastInsulinChange, it.timestamp)
                }
                val showOnUpdateRequest = (currentInsulin != targetInsulin) && recentUpdate
                if (!concentrationConfirmed || showOnUpdateRequest) {
                    uiInteraction.runInsulinConfirmation()
                }
            }
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null && requiredKey != "insulin_concentration_advanced") return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "insulin_settings"
            title = rh.gs(R.string.insulin_settings)
            initialExpandedChildrenCount = 0
            addConcentrationPreference(preferenceManager, context, this)
        }
    }

    fun addConcentrationPreference(preferenceManager: PreferenceManager, context: Context, category: PreferenceCategory) {
        category.addPreference(preferenceManager.createPreferenceScreen(context).apply {
            key = "insulin_concentration_advanced"
            title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.insulin_concentration_doc)) },
                    summary = R.string.insulin_concentration_doc_txt
                )
            )
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.InsulinRequestedConcentration, title = R.string.insulin_requested_concentration_title, dialogMessage = R.string.insulin_change_concentration_summary))
        })
    }

    override fun isClosedLoopAllowed(value: Constraint<Boolean>): Constraint<Boolean> {
        if (!concentrationConfirmed) {
            if (value.value() && !recentUpdate) {
                uiInteraction.addNotification(Notification.TOAST_ALARM, rh.gs(app.aaps.core.ui.R.string.concentration_not_confirmed), Notification.NORMAL)
            }
            value.set(false, rh.gs(app.aaps.core.ui.R.string.concentration_not_confirmed), this)
        }
        return value
    }
}