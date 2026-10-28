package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The Glass loop dashboard. Display only: it reads values, it never changes a dose or a setting.
 *
 * Port of `GlassLoopDashboardViewModel` on `dev_OAPSAIMI`. The values DEV read from AIMI singletons now
 * come from [AimiGlassDashboardData] (the AIMI snapshot feed, passed in by the screen through
 * [onAimiData]); the generic values (last loop run, glucose status, units, build version) are read
 * here through the core interfaces. The texts are built by [buildGlassLoopDashboardState].
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class GlassLoopDashboardViewModel(
    private val activePlugin: ActivePlugin,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val aapsLogger: AAPSLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlassLoopDashboardState(isLoading = true))
    val uiState: StateFlow<GlassLoopDashboardState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    private val format = object : GlassLoopDashboardFormat {
        override fun text(ref: TextRef, vararg args: Any?): String = rh.gs(ref, *args)
        override fun glucose(mgdl: Double): String = profileUtil.fromMgdlToStringInUnits(mgdl)
        override fun signedGlucose(mgdl: Double): String = profileUtil.fromMgdlToSignedStringInUnits(mgdl)
        override fun glucoseInUnits(mgdl: Double): Double = profileUtil.fromMgdlToUnits(mgdl)
        override fun time(epochMs: Long): String = dateUtil.timeString(epochMs)
        override fun dateTime(epochMs: Long): String = dateUtil.dateAndTimeString(epochMs)
        override fun minutesAgo(epochMs: Long): String = dateUtil.minAgoLong(rh, epochMs)
    }

    /** Rebuilds the screen from a new AIMI snapshot. The feed sends one at least every minute. */
    fun onAimiData(data: AimiGlassDashboardData) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val state = withContext(aapsIoDispatcher) { buildGlassLoopDashboardState(readInput(data), format) }
                _uiState.update { state }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Glass loop dashboard failed", e)
            }
        }
    }

    private fun readInput(data: AimiGlassDashboardData): GlassLoopDashboardInput {
        val now = dateUtil.now()
        val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault())
        val result = activePlugin.activeAPS?.lastAPSResult
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        return GlassLoopDashboardInput(
            hourOfDay = local.hour,
            isWeekend = local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY,
            buildVersion = config.VERSION_NAME,
            glucoseUnit = profileFunction.getUnits(),
            lastRun = result?.let {
                GlassLoopDashboardInput.LastRun(
                    dateMs = it.date,
                    smbUnits = it.smb,
                    // `iob` comes from IobTotal.combine(): its `iob` already holds the basal part.
                    iobUnits = it.iob?.iob,
                )
            },
            glucose = glucoseStatus?.let {
                GlassLoopDashboardInput.Glucose(
                    glucoseMgdl = it.glucose,
                    deltaMgdl = it.delta,
                    shortAvgDeltaMgdl = it.shortAvgDelta,
                    longAvgDeltaMgdl = it.longAvgDelta,
                )
            },
            aimi = data,
        )
    }
}

/** What [buildGlassLoopDashboardState] needs to format its texts. The VM uses the app formatters. */
internal interface GlassLoopDashboardFormat {

    fun text(ref: TextRef, vararg args: Any?): String
    fun glucose(mgdl: Double): String
    fun signedGlucose(mgdl: Double): String
    fun glucoseInUnits(mgdl: Double): Double
    fun time(epochMs: Long): String
    fun dateTime(epochMs: Long): String
    fun minutesAgo(epochMs: Long): String
}

/** The raw values of one loop dashboard refresh. Glucose values are in mg/dL. */
internal data class GlassLoopDashboardInput(
    val hourOfDay: Int,
    val isWeekend: Boolean,
    val buildVersion: String,
    val glucoseUnit: GlucoseUnit,
    /** The last APS result, null when the loop has not run yet. */
    val lastRun: LastRun?,
    /** The current glucose status, null without recent glucose. */
    val glucose: Glucose?,
    val aimi: AimiGlassDashboardData,
) {

    data class LastRun(val dateMs: Long, val smbUnits: Double, val iobUnits: Double?)
    data class Glucose(val glucoseMgdl: Double, val deltaMgdl: Double, val shortAvgDeltaMgdl: Double, val longAvgDeltaMgdl: Double)
}

private const val NO_VALUE = "--"

/**
 * Builds the loop dashboard texts with the same strings and decimals as DEV. A value that is missing
 * stays "--"; it is not replaced by 0. A forecast older than the feed's freshness window is not shown.
 */
internal fun buildGlassLoopDashboardState(input: GlassLoopDashboardInput, format: GlassLoopDashboardFormat): GlassLoopDashboardState {
    val aimi = input.aimi
    val lastRun = input.lastRun
    val keyFactors = aimi.keyFactors
    val safety = aimi.safety
    val glucose = input.glucose

    fun insulin(units: Double) = format.text(InterfacesStrings.format_insulin_units, units)
    fun percent(value: Double) = format.text(CoreUiStrings.units_format_percent, value.roundToInt())

    val requestedSmbText = lastRun?.let { insulin(it.smbUnits) } ?: NO_VALUE

    val isfText = keyFactors?.isfMgdlPerUnit?.let { isf ->
        val unitRef = if (input.glucoseUnit == GlucoseUnit.MGDL) InterfacesStrings.profile_isf_units_mgdl else InterfacesStrings.profile_isf_units_mmol
        format.text(UiStrings.dashboard_glass_loop_isf_value, format.glucoseInUnits(isf), format.text(unitRef))
    } ?: NO_VALUE

    val trajectory = aimi.trajectory?.takeIf { !it.isStale }
    val physio = aimi.physioPresentation

    val basal = aimi.training?.basal
    val smb = aimi.training?.smb
    val smbAttempt = smb?.lastAttempt

    return GlassLoopDashboardState(
        isLoading = false,
        lastRunTime = lastRun?.dateMs?.takeIf { it > 0L }?.let(format::time) ?: "",
        requestedSmbText = requestedSmbText,
        glucoseText = glucose?.let { format.glucose(it.glucoseMgdl) } ?: NO_VALUE,
        delta5mText = glucose?.let { format.signedGlucose(it.deltaMgdl) } ?: NO_VALUE,
        delta5mIsPositive = (glucose?.deltaMgdl ?: 0.0) >= 0.0,
        shortAvgDeltaText = glucose?.let { format.signedGlucose(it.shortAvgDeltaMgdl) } ?: NO_VALUE,
        shortAvgDeltaIsPositive = (glucose?.shortAvgDeltaMgdl ?: 0.0) >= 0.0,
        longAvgDeltaText = glucose?.let { format.signedGlucose(it.longAvgDeltaMgdl) } ?: NO_VALUE,
        longAvgDeltaIsPositive = (glucose?.longAvgDeltaMgdl ?: 0.0) >= 0.0,
        iobText = lastRun?.iobUnits?.let(::insulin) ?: NO_VALUE,
        targetBgText = keyFactors?.targetBgMgdl?.let(format::glucose) ?: NO_VALUE,
        maxIobProfileText = keyFactors?.maxIobUnits?.let(::insulin) ?: NO_VALUE,
        maxSmbProfileText = keyFactors?.maxSmbUnits?.let(::insulin) ?: NO_VALUE,
        isfText = isfText,
        stableMinutesText = keyFactors?.stableBgMinutes?.let { format.text(CoreUiStrings.format_mins, it.roundToInt()) } ?: NO_VALUE,
        tdd7DaysPerHourText = safety?.tdd7DaysPerHour?.let { format.text(CoreUiStrings.pump_base_basal_rate, it) } ?: NO_VALUE,
        tirLow1hText = safety?.lowTirLastHourPercent?.let(::percent) ?: NO_VALUE,
        tirLow24hText = safety?.lowTirTodayPercent?.let(::percent) ?: NO_VALUE,
        steps5mText = safety?.stepsLast5Minutes?.let { format.text(UiStrings.dashboard_glass_loop_steps_5m, it) } ?: NO_VALUE,
        hourOfDay = input.hourOfDay,
        isWeekend = input.isWeekend,
        buildVersionText = input.buildVersion,
        hasTrajectory = trajectory != null,
        trajectoryHybridText = trajectory?.hybridTerminalMgdl?.let(format::glucose) ?: NO_VALUE,
        trajectoryIobText = trajectory?.iobTerminalMgdl?.let(format::glucose) ?: NO_VALUE,
        trajectoryCobText = trajectory?.cobTerminalMgdl?.let(format::glucose) ?: NO_VALUE,
        hasPhysio = physio != null,
        physioModeText = physio?.modeHeadline ?: NO_VALUE,
        physioIntentText = physio?.intentSummary ?: NO_VALUE,
        physioThermalText = physio?.thermalSummary ?: NO_VALUE,
        physioUpdatedText = physio?.updatedSummary ?: "",
        hasMlTraining = basal != null,
        mlLastTrainedText = basal?.lastTrainedAtMs?.takeIf { it > 0L }?.let(format::minutesAgo)
            ?: format.text(UiStrings.dashboard_glass_loop_ml_never_trained),
        mlSampleCountText = basal?.sampleCount?.toString() ?: NO_VALUE,
        mlCircuitOpen = basal?.circuitOpen ?: false,
        basalModelFileText = modelFileText(basal?.modelFile, format),
        smbModelFileText = modelFileText(smb?.modelFile, format),
        smbTrainingStatusText = smbAttempt?.let {
            format.text(UiStrings.dashboard_glass_loop_ml_smb_status_value, format.text(smbOutcomeText(it.outcome)), format.minutesAgo(it.atMs))
        } ?: format.text(UiStrings.dashboard_glass_loop_ml_smb_never_attempted),
        smbTrainingDetailText = smbTrainingDetailText(smbAttempt, smb?.waiting, format),
    )
}

/** The model file date, or "not created yet" when there is no file. */
private fun modelFileText(file: AimiGlassDashboardData.ModelFile?, format: GlassLoopDashboardFormat): String =
    file?.takeIf { it.exists }?.modifiedAtMs?.let(format::dateTime) ?: format.text(UiStrings.dashboard_glass_loop_ml_file_missing)

/** One plain sentence per outcome, so the dashboard never shows a raw enum name. */
internal fun smbOutcomeText(outcome: AimiGlassDashboardData.SmbOutcome): TextRef = when (outcome) {
    AimiGlassDashboardData.SmbOutcome.TRAINED              -> UiStrings.dashboard_glass_loop_ml_smb_outcome_trained
    AimiGlassDashboardData.SmbOutcome.NO_CSV               -> UiStrings.dashboard_glass_loop_ml_smb_outcome_no_csv
    AimiGlassDashboardData.SmbOutcome.REFUSED_HEADER       -> UiStrings.dashboard_glass_loop_ml_smb_outcome_refused_header
    AimiGlassDashboardData.SmbOutcome.TOO_FEW_SAMPLES      -> UiStrings.dashboard_glass_loop_ml_smb_outcome_too_few_samples
    AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES    -> UiStrings.dashboard_glass_loop_ml_smb_outcome_rejected
    AimiGlassDashboardData.SmbOutcome.ERROR                -> UiStrings.dashboard_glass_loop_ml_smb_outcome_error
    // Waiting states never reach `lastAttempt`; they come from `waiting`.
    AimiGlassDashboardData.SmbOutcome.WAITING_NOT_DUE      -> UiStrings.dashboard_glass_loop_ml_smb_waiting_not_due
    AimiGlassDashboardData.SmbOutcome.WAITING_CIRCUIT_OPEN -> UiStrings.dashboard_glass_loop_ml_smb_waiting_circuit_open
}

/**
 * The second line: the gate message when a model was refused, otherwise how many rows the quality
 * filter kept, otherwise why the trainer is waiting. Empty when there is nothing to add.
 */
private fun smbTrainingDetailText(
    attempt: AimiGlassDashboardData.SmbAttempt?,
    waiting: AimiGlassDashboardData.SmbWaiting?,
    format: GlassLoopDashboardFormat,
): String {
    val gateDetail = attempt?.detail.orEmpty()
    if (gateDetail.isNotEmpty()) return gateDetail
    if (attempt != null && attempt.samplesAfterFilter > 0) {
        return format.text(UiStrings.dashboard_glass_loop_ml_smb_rows, attempt.samplesAfterFilter, attempt.rowsRejectedByFilter.toInt())
    }
    return when (waiting) {
        AimiGlassDashboardData.SmbWaiting.NOT_DUE      -> format.text(UiStrings.dashboard_glass_loop_ml_smb_waiting_not_due)
        AimiGlassDashboardData.SmbWaiting.CIRCUIT_OPEN -> format.text(UiStrings.dashboard_glass_loop_ml_smb_waiting_circuit_open)
        null                                           -> ""
    }
}
