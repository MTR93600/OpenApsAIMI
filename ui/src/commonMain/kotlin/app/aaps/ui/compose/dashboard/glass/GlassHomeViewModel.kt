package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.objects.extensions.isInProgress
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The values of the Glass home that no shared overview flow carries: target, effective basal, loop
 * mode, today's time in range, steps, heart rate and the pump line.
 *
 * On `dev_OAPSAIMI` these came from `OverviewViewModel.updateStatus`. The rules are the same; only the
 * values the overview flows already have (glucose, IOB, status lights, charts) are read elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class GlassHomeViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val loop: Loop,
    private val dateUtil: DateUtil,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
) : ViewModel() {

    private val _status = MutableStateFlow(GlassHomeStatus())
    val status: StateFlow<GlassHomeStatus> = _status.asStateFlow()

    /**
     * Today's step total from the platform health store, published by AIMI. Null when there is none
     * (no AIMI, no Health Connect, iOS, desktop): the database total is then shown alone.
     */
    private val healthStoreStepsToday = MutableStateFlow<AimiGlassDashboardData.StepsToday?>(null)

    /** Called by the home with the latest AIMI snapshot value. */
    fun onHealthStoreStepsToday(stepsToday: AimiGlassDashboardData.StepsToday?) {
        healthStoreStepsToday.value = stepsToday
    }

    /** Yesterday's in-range share does not change during the day: read once per day. */
    private var yesterdayDayStart: Long? = null
    private var yesterdayInRangePct: Double? = null

    init {
        // Work only while the home is shown, like StatusViewModel.
        _status.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .flatMapLatest { visible -> if (visible) refreshTriggers() else emptyFlow() }
            .onEach { refresh() }
            .launchIn(viewModelScope)
    }

    private fun refreshTriggers(): Flow<Unit> = merge(
        // Emits at once, then every minute: steps, heart rate and the loop target move with time.
        tickerFlow(60_000L),
        persistenceLayer.observeChanges(GV::class).map { },
        persistenceLayer.observeChanges(TT::class).map { },
        persistenceLayer.observeChanges(TB::class).map { },
        persistenceLayer.observeChanges(RM::class).map { },
        persistenceLayer.observeChanges(HR::class).map { },
        persistenceLayer.observeChanges(SC::class).map { },
        // Site and sensor age of the pump line.
        persistenceLayer.observeChanges(TE::class).map { },
        activePlugin.activePump.reservoirLevel.map { },
        persistenceLayer.databaseClearedFlow.map { },
        healthStoreStepsToday.map { },
    )

    private var refreshJob: Job? = null

    private fun refresh() {
        // Only the newest refresh matters; a slow one must not pile up behind the next.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                _status.value = withContext(aapsIoDispatcher) { buildStatus() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Glass home status failed", e)
            }
        }
    }

    private suspend fun buildStatus(): GlassHomeStatus {
        val now = dateUtil.now()
        val profile = profileFunction.getProfile()
        val activeTemporaryTarget = persistenceLayer.getTemporaryTargetActiveAt(now)
        val targetRange = DashboardV2StatusValueResolver.resolveTargetRangeMgdl(
            temporaryLow = activeTemporaryTarget?.lowTarget,
            temporaryHigh = activeTemporaryTarget?.highTarget,
            profileLow = profile?.getTargetLowMgdl(now),
            profileHigh = profile?.getTargetHighMgdl(now),
            aimiTargetBg = loop.lastRun?.request?.targetBG,
        )
        val targetText = targetRange?.let { range ->
            profileUtil.toTargetRangeString(low = range.low, high = range.high, sourceUnits = GlucoseUnit.MGDL)
        }

        val activeTempBasal = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)?.takeIf { it.isInProgress(dateUtil) }
        val effectiveBasalRateUh = DashboardV2StatusValueResolver.resolveEffectiveBasalRateUh(
            temporaryBasal = activeTempBasal?.let { DashboardV2StatusValueResolver.TemporaryBasal(rate = it.rate, isAbsolute = it.isAbsolute) },
            scheduledBasalRateUh = profile?.getBasal(now),
        )
        val effectiveBasalText = effectiveBasalRateUh?.let { rh.gs(CoreUiStrings.pump_base_basal_rate, it) }

        // Display only: read the running mode from the database, as DEV did.
        val loopMode = persistenceLayer.getRunningModeActiveAt(now).mode

        val dayStart = dateUtil.beginOfDay(now)
        val todayMgdl = persistenceLayer.getBgReadingsDataFromTimeToTime(dayStart, now, true).map { it.value }
        if (yesterdayDayStart != dayStart) {
            val yesterdayMgdl = persistenceLayer.getBgReadingsDataFromTimeToTime(dateUtil.beginOfDay(dayStart - 1), dayStart, true).map { it.value }
            yesterdayInRangePct = GlassHomeStats.inRangePct(yesterdayMgdl)
            yesterdayDayStart = dayStart
        }
        val tir = GlassHomeStats.tir(
            todayMgdl = todayMgdl,
            yesterdayInRangePct = yesterdayInRangePct,
            hoursSinceDayStart = (now - dayStart) / 3_600_000.0,
        )

        val steps = GlassHomeStats.chooseStepsToday(
            // A total of another day (read just before midnight) is not today's.
            healthStoreSteps = healthStoreStepsToday.value?.takeIf { it.dayStartMs == dayStart }?.steps,
            databaseSteps = GlassHomeStats.stepsToday(persistenceLayer.getStepsCountFromTimeToTime(dayStart, now)),
        )
        val stepsText = when {
            steps == null -> NO_VALUE
            steps > 1.0   -> decimalFormatter.to0Decimal(steps)
            else          -> decimalFormatter.to0Decimal(0.0)
        }
        // 3 h window plus 15 min: some watches (Garmin) write records that overlap.
        val lastHr = persistenceLayer.getHeartRatesFromTimeToTime(now - HR_WINDOW_MS - HR_OVERLAP_MS, now)
            .maxByOrNull { it.timestamp }?.beatsPerMinute?.takeIf { it > 0 }
        val hrText = lastHr?.let { decimalFormatter.to0Decimal(it) } ?: NO_VALUE

        return GlassHomeStatus(
            pumpStatus = buildPumpStatus(now),
            targetText = targetText,
            isTempTargetActive = activeTemporaryTarget != null,
            effectiveBasalText = effectiveBasalText,
            loopStatusText = rh.gs(loopModeLabel(loopMode)),
            loopIsRunning = !loopMode.pausesLoopExecution(),
            stepsText = stepsText,
            hrText = hrText,
            tir = tir,
        )
    }

    /** Same sources and rules as DEV `OverviewViewModel.buildPumpAdjustmentDisplay`. */
    private suspend fun buildPumpStatus(now: Long): GlassPumpStatusLine {
        val pump = activePlugin.activePump
        val reservoirUnits = pump.reservoirLevel.value.cU
        val reservoirText =
            if (reservoirUnits > 0) rh.gs(InterfacesStrings.format_insulin_units, reservoirUnits)
            else rh.gs(CoreUiStrings.value_unavailable_short)
        val siteAgeMs = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.let { now - it.timestamp }
        val sensorAgeMs = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.let { now - it.timestamp }
        val unreachable = GlassPumpStatusRules.isPumpUnreachable(
            alertEnabled = preferences.get(BooleanKey.AlertPumpUnreachable),
            lastConnectionMs = pump.lastDataTime.value,
            thresholdMinutes = preferences.get(IntKey.AlertsPumpUnreachableThreshold),
            nowMs = now,
        )
        return GlassPumpStatusLine(
            reservoirText = reservoirText,
            unreachableText = if (unreachable) rh.gs(CoreUiStrings.pump_unreachable) else null,
            siteAgeText = formatAge(siteAgeMs),
            siteAgeWarning = GlassPumpStatusRules.siteAgeWarning(siteAgeMs),
            sensorAgeText = formatAge(sensorAgeMs),
            sensorAgeWarning = GlassPumpStatusRules.sensorAgeWarning(sensorAgeMs),
        )
    }

    private fun formatAge(ageMs: Long?): String =
        when (val age = ageMs?.let { GlassPumpStatusRules.age(it) }) {
            null                                    -> rh.gs(CoreUiStrings.value_unavailable_short)
            is GlassPumpStatusRules.Age.DaysHours    -> rh.gs(UiStrings.status_age_short, age.days, age.hours)
            is GlassPumpStatusRules.Age.HoursMinutes -> rh.gs(CoreUiStrings.format_hour_minute, age.hours, age.minutes)
            is GlassPumpStatusRules.Age.Minutes      -> rh.gs(CoreUiStrings.format_mins, age.minutes)
        }

    private companion object {

        const val NO_VALUE = "--"
        const val HR_WINDOW_MS = 3 * 60 * 60 * 1000L
        const val HR_OVERLAP_MS = 15 * 60 * 1000L
    }
}

/** The loop pill text for a running mode. Same labels as on `dev_OAPSAIMI`. */
internal fun loopModeLabel(mode: RM.Mode): TextRef = when (mode) {
    RM.Mode.SUPER_BOLUS       -> CoreUiStrings.superbolus
    RM.Mode.DISCONNECTED_PUMP -> CoreUiStrings.disconnected
    RM.Mode.SUSPENDED_BY_PUMP -> InterfacesStrings.pumpsuspended
    RM.Mode.SUSPENDED_BY_USER -> InterfacesStrings.loopsuspended
    RM.Mode.SUSPENDED_BY_DST  -> CoreUiStrings.loop_suspended_by_dst
    RM.Mode.CLOSED_LOOP_LGS   -> CoreUiStrings.uel_lgs_loop_mode
    RM.Mode.CLOSED_LOOP       -> CoreUiStrings.closedloop
    RM.Mode.OPEN_LOOP         -> CoreUiStrings.openloop
    RM.Mode.DISABLED_LOOP     -> CoreUiStrings.disabled_loop
    RM.Mode.RESUME            -> CoreUiStrings.resumeloop
}

/**
 * What [GlassHomeViewModel] adds to the Glass home. Texts are ready to show; null means "no value",
 * and the home then shows "--".
 */
data class GlassHomeStatus(
    /** Null until the first refresh: the pump line is then not shown. */
    val pumpStatus: GlassPumpStatusLine? = null,
    val targetText: String? = null,
    val isTempTargetActive: Boolean = false,
    val effectiveBasalText: String? = null,
    /** Null until the first refresh: the pill then shows its default "Loop" label. */
    val loopStatusText: String? = null,
    val loopIsRunning: Boolean = true,
    val stepsText: String = "--",
    val hrText: String = "--",
    val tir: GlassTir? = null,
)

/** Pure rules behind [GlassHomeViewModel], kept apart so they can be tested without a database. */
internal object GlassHomeStats {

    /** Today's ring shows only after this many hours: early in the day there are too few readings. */
    const val TIR_DAY_READY_HOURS = 6.0

    private const val LOW_MGDL = 70.0
    private const val HIGH_MGDL = 180.0

    /** Share of readings in 70-180 mg/dL, in percent. Null without readings. */
    fun inRangePct(valuesMgdl: List<Double>): Double? =
        valuesMgdl.takeIf { it.isNotEmpty() }?.let { list -> list.count { it in LOW_MGDL..HIGH_MGDL } * 100.0 / list.size }

    /**
     * Today's time in range for the ring, with the change against yesterday. Null when there are no
     * readings today or the day is younger than [TIR_DAY_READY_HOURS]. The bands are the ones DEV used:
     * below = under 70, in range = 70-180, above = over 180 mg/dL.
     */
    fun tir(todayMgdl: List<Double>, yesterdayInRangePct: Double?, hoursSinceDayStart: Double): GlassTir? {
        if (todayMgdl.isEmpty() || hoursSinceDayStart < TIR_DAY_READY_HOURS) return null
        val count = todayMgdl.size.toDouble()
        val inRange = todayMgdl.count { it in LOW_MGDL..HIGH_MGDL } / count * 100.0
        val below = todayMgdl.count { it < LOW_MGDL } / count * 100.0
        val above = todayMgdl.count { it > HIGH_MGDL } / count * 100.0
        return GlassTir(
            belowPct = below.toFloat(),
            inRangePct = inRange.toFloat(),
            abovePct = above.toFloat(),
            deltaVsYesterday = yesterdayInRangePct?.let { (inRange - it).roundToInt() },
        )
    }

    /**
     * Steps since midnight from the database, as DEV computed them without Health Connect: per device,
     * the largest 5 min count of each 5 min slot is added up, and the device with the highest total wins
     * (devices are not merged). Null when there are no rows.
     */
    fun stepsToday(rows: List<SC>): Double? {
        if (rows.isEmpty()) return null
        return rows
            .filter { it.device.isNotBlank() }
            .groupBy { it.device }
            .values
            .maxOfOrNull { deviceRows ->
                deviceRows
                    .groupBy { it.timestamp / FIVE_MIN_MS }
                    .values
                    .sumOf { slot -> slot.maxOf { it.steps5min.coerceAtLeast(0) } }
                    .toDouble()
            } ?: 0.0
    }

    /**
     * The steps shown for today, as on `dev_OAPSAIMI`: with a health store total, the larger of it and
     * the database total (the database cannot merge phone and watch, the store can lag, so the larger
     * one is the fresher); without it, the database total. Null when neither has a value.
     */
    fun chooseStepsToday(healthStoreSteps: Long?, databaseSteps: Double?): Double? = when {
        healthStoreSteps != null -> max(healthStoreSteps.toDouble().coerceAtLeast(0.0), (databaseSteps ?: 0.0).coerceAtLeast(0.0))
        else                     -> databaseSteps
    }

    private const val FIVE_MIN_MS = 5 * 60 * 1000L
}
