package app.aaps.plugins.main.general.dashboard.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.IobTotal
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
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.objects.extensions.toStringFull
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign

class OverviewViewModel(
    private val context: Context,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val iobCobCalculator: IobCobCalculator,
    private val profileUtil: ProfileUtil,
    private val profileFunction: ProfileFunction,
    private val resourceHelper: ResourceHelper,
    private val dateUtil: DateUtil,
    private val loop: Loop,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val decimalFormatter: DecimalFormatter,
    private val activePlugin: ActivePlugin,
    private val rxBus: RxBus,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy
) : ViewModel() {

    private val disposables = CompositeDisposable()
    private var started = false

    private val _statusCardState = MutableLiveData<StatusCardState>()
    val statusCardState: LiveData<StatusCardState> = _statusCardState

    private val _adjustmentState = MutableLiveData<List<String>>()
    val adjustmentState: LiveData<List<String>> = _adjustmentState

    private val _graphMessage = MutableLiveData<String>()
    val graphMessage: LiveData<String> = _graphMessage

    fun start() {
        if (started) return
        started = true
        subscribeToUpdates()
        refreshAll()
    }

    fun stop() {
        started = false
        disposables.clear()
    }

    override fun onCleared() {
        disposables.clear()
        super.onCleared()
    }

    private fun subscribeToUpdates() {
        disposables += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ refreshAll() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateAdjustments() }, fabricPrivacy::logException)

        disposables += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateGraphMessage() }, fabricPrivacy::logException)

        disposables += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateStatus() }, fabricPrivacy::logException)
    }

    private fun refreshAll() {
        updateStatus()
        updateAdjustments()
        updateGraphMessage()
    }

    private fun updateStatus() {
        val lastBg = lastBgData.lastBg()
        val glucoseText = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated)
        val trendArrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)?.directionToIcon()
        val trendDescription = trendCalculator.getTrendDescription(iobCobCalculator.ads) ?: ""
        val iobText = totalIobText()
        val cobText = iobCobCalculator
            .getCobInfo("Dashboard COB")
            .displayText(resourceHelper, decimalFormatter)
            ?: resourceHelper.gs(app.aaps.core.ui.R.string.value_unavailable_short)
        val timeAgo = dateUtil.minAgoShort(lastBg?.timestamp)
        val timeAgoLong = dateUtil.minAgoLong(resourceHelper, lastBg?.timestamp)
        val contentDescription =
            resourceHelper.gs(R.string.a11y_blood_glucose) + " " +
                glucoseText + " " + lastBgData.lastBgDescription() + " " + timeAgoLong

        val state = StatusCardState(
            glucoseText = glucoseText,
            glucoseColor = lastBgData.lastBgColor(context),
            trendArrowRes = trendArrow,
            trendDescription = trendDescription,
            iobText = iobText,
            cobText = cobText,
            loopStatusText = loopStatusText(loop.runningMode),
            loopIsRunning = !loop.runningMode.isSuspended(),
            timeAgo = timeAgo,
            timeAgoDescription = timeAgoLong,
            isGlucoseActual = lastBgData.isActualBg(),
            contentDescription = contentDescription
        )
        _statusCardState.postValue(state)
    }

    private fun totalIobText(): String {
        val bolus = bolusIob()
        val basal = basalIob()
        val total = bolus.iob + basal.basaliob
        return resourceHelper.gs(app.aaps.core.ui.R.string.format_insulin_units, total)
    }

    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()

    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()

    private fun loopStatusText(mode: RM.Mode): String =
        resourceHelper.gs(
            when (mode) {
                RM.Mode.SUPER_BOLUS -> app.aaps.core.ui.R.string.superbolus
                RM.Mode.DISCONNECTED_PUMP -> app.aaps.core.ui.R.string.disconnected
                RM.Mode.SUSPENDED_BY_PUMP -> app.aaps.core.ui.R.string.pumpsuspended
                RM.Mode.SUSPENDED_BY_USER -> app.aaps.core.ui.R.string.loopsuspended
                RM.Mode.SUSPENDED_BY_DST -> app.aaps.core.ui.R.string.loop_suspended_by_dst
                RM.Mode.CLOSED_LOOP_LGS -> app.aaps.core.ui.R.string.uel_lgs_loop_mode
                RM.Mode.CLOSED_LOOP -> app.aaps.core.ui.R.string.closedloop
                RM.Mode.OPEN_LOOP -> app.aaps.core.ui.R.string.openloop
                RM.Mode.DISABLED_LOOP -> app.aaps.core.ui.R.string.disabled_loop
                RM.Mode.RESUME -> app.aaps.core.ui.R.string.resumeloop
            }
        )

    private fun updateAdjustments() {
        val now = dateUtil.now()
        val adjustments = mutableListOf<String>()
        processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress }?.let {
            adjustments += resourceHelper.gs(R.string.dashboard_adjustment_temp_basal, it.toStringShort(resourceHelper))
        }
        persistenceLayer.getTemporaryTargetActiveAt(now)?.let { target ->
            val units = profileFunction.getUnits() ?: GlucoseUnit.MGDL
            val range = profileUtil.toTargetRangeString(target.lowTarget, target.highTarget, GlucoseUnit.MGDL, units)
            adjustments += resourceHelper.gs(R.string.dashboard_adjustment_temp_target, range, dateUtil.untilString(target.end, resourceHelper))
        }
        persistenceLayer.getExtendedBolusActiveAt(now)?.takeIf { it.isInProgress(dateUtil) }?.let {
            adjustments += resourceHelper.gs(R.string.dashboard_adjustment_extended_bolus, it.toStringFull(dateUtil, resourceHelper))
        }
        _adjustmentState.postValue(adjustments)
    }

    private fun updateGraphMessage() {
        val message = resourceHelper.gs(R.string.dashboard_graph_updated, dateUtil.timeString(dateUtil.now()))
        _graphMessage.postValue(message)
    }

    class Factory(
        private val context: Context,
        private val lastBgData: LastBgData,
        private val trendCalculator: TrendCalculator,
        private val iobCobCalculator: IobCobCalculator,
        private val profileUtil: ProfileUtil,
        private val profileFunction: ProfileFunction,
        private val resourceHelper: ResourceHelper,
        private val dateUtil: DateUtil,
        private val loop: Loop,
        private val processedTbrEbData: ProcessedTbrEbData,
        private val persistenceLayer: PersistenceLayer,
        private val decimalFormatter: DecimalFormatter,
        private val activePlugin: ActivePlugin,
        private val rxBus: RxBus,
        private val aapsSchedulers: AapsSchedulers,
        private val fabricPrivacy: FabricPrivacy
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OverviewViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return OverviewViewModel(
                    context.applicationContext,
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
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }
}

data class StatusCardState(
    val glucoseText: String,
    val glucoseColor: Int,
    val trendArrowRes: Int?,
    val trendDescription: String,
    val iobText: String,
    val cobText: String,
    val loopStatusText: String,
    val loopIsRunning: Boolean,
    val timeAgo: String,
    val timeAgoDescription: String,
    val isGlucoseActual: Boolean,
    val contentDescription: String
)
