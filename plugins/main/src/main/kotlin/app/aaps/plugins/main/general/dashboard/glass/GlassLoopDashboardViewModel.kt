package app.aaps.plugins.main.general.dashboard.glass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbModelStore
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.main.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class GlassLoopDashboardViewModel @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val resourceHelper: ResourceHelper,
    private val dateUtil: DateUtil,
    private val config: Config,
    private val basalNeuralLearner: BasalNeuralLearner,
    private val aimiStorageHelper: AimiStorageHelper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlassLoopDashboardState())
    val uiState: StateFlow<GlassLoopDashboardState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newState = withContext(Dispatchers.IO) {
                val result = activePlugin.activeAPS?.lastAPSResult
                val hasResult = result != null

                val requestedSmbText = if (hasResult)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, result?.smb ?: 0.0)
                else
                    "--"

                val targetBgText = if (hasResult)
                    profileUtil.fromMgdlToStringInUnits(result?.targetBG ?: 0.0)
                else
                    "--"

                val glucoseStatus = glucoseStatusProvider.glucoseStatusData
                val hasGlucose = glucoseStatus != null
                val delta5mMgdl = glucoseStatus?.delta ?: 0.0
                val shortAvgDeltaMgdl = glucoseStatus?.shortAvgDelta ?: 0.0
                val longAvgDeltaMgdl = glucoseStatus?.longAvgDelta ?: 0.0

                val iob = result?.iob
                val iobText = if (iob != null)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, iob.iob + iob.basaliob)
                else
                    "--"

                val oapsProfile = result?.oapsProfileAimi
                val maxIobProfileText = if (oapsProfile != null)
                    resourceHelper.gs(CoreUiR.string.format_insulin_units, oapsProfile.max_iob)
                else
                    "--"

                val maxSmbProfile = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                val maxSmbProfileText = resourceHelper.gs(CoreUiR.string.format_insulin_units, maxSmbProfile)

                val variableSens = result?.variableSens
                val isfText = if (variableSens != null) {
                    val isfDisplay = profileUtil.fromMgdlToUnits(variableSens)
                    val isfUnitRes = if (profileFunction.getUnits() == GlucoseUnit.MGDL)
                        CoreUiR.string.profile_isf_units_mgdl
                    else
                        CoreUiR.string.profile_isf_units_mmol
                    resourceHelper.gs(R.string.dashboard_glass_loop_isf_value, isfDisplay, resourceHelper.gs(isfUnitRes))
                } else {
                    "--"
                }

                val stableMinutes = glucoseStatusCalculatorAimi.getAimiFeatures(allowOldData = true)?.stable5pctMinutes ?: 0.0

                // calculateDaily(-168, 0) would recompute a raw, UNCACHED 2016-bucket (7 days at 5-minute
                // steps) loop every time this screen opens — each bucket does a profile lookup, a basal-data
                // computation, and (unless the pump fakes temps via extended boluses) its own suspend DB
                // round-trip for an extended-bolus lookup. calculate(7, ...) instead serves past days from
                // TddCalculator's per-midnight cache (see TddCalculatorImpl.calculate) and only runs that
                // expensive loop once, for today's still-in-progress partial day — the same pattern every
                // other 7-day TDD consumer in the app already uses (StatsViewModel, KalmanFilter, etc.).
                // averageTDD's totalAmount is already a PER-DAY average, so divide by 24 (not 7*24) for U/h.
                val tdd7 = tddCalculator.averageTDD(tddCalculator.calculate(7L, allowMissingDays = true))
                val tdd7PerHour = (tdd7?.data?.totalAmount ?: 0.0) / 24.0

                val tirLow1h = tirCalculator.averageTIR(tirCalculator.calculateHour(70.0, 180.0)).belowPct() ?: 0.0
                val tirLow24h = tirCalculator.averageTIR(tirCalculator.calculateDaily(70.0, 180.0)).belowPct() ?: 0.0

                val nowEpochMs = dateUtil.now()
                val steps5m = persistenceLayer.getLastStepsCountFromTimeToTime(
                    nowEpochMs - 24 * 3_600_000L,
                    nowEpochMs
                )?.steps5min ?: 0

                val calendar = Calendar.getInstance()
                val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

                val lastRunTime = (result?.date ?: 0L).let { date -> if (date > 0L) dateUtil.timeString(date) else "" }

                val trajectoryCurves = TrajectoryRuntimeRepository.getLatest()
                val hasTrajectory = trajectoryCurves != null
                val trajectoryHybridText = trajectoryCurves?.hybridTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryIobText = trajectoryCurves?.iob?.lastOrNull()?.takeIf { it.isFinite() }?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryCobText = trajectoryCurves?.cobTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"

                val physioSnapshot = PatientStateRuntimeRepository.getLatest()
                val physioPresentation = physioSnapshot?.let {
                    PatientStatePresentationBuilder.build(it, dateUtil.now())
                }
                val hasPhysio = physioPresentation != null
                val physioModeText = physioPresentation?.modeHeadline ?: "--"
                val physioIntentText = physioPresentation?.intentSummary ?: "--"
                val physioThermalText = physioPresentation?.thermalSummary ?: "--"
                val physioUpdatedText = physioPresentation?.updatedSummary ?: ""

                val trainingCoordinator = BasalMlTrainingCoordinator.instance
                val hasMlTraining = trainingCoordinator != null
                val lastTrainedMs = trainingCoordinator?.lastTrainedAtMs() ?: 0L
                // minAgoShort is a compact countdown badge format ("(+15)"/"(-5)"), not an "ago" phrase, and
                // returns "" past ~7 days old — wrapped in the "%1$s ago" template that produced a bare
                // " ago". minAgoLong already returns a complete "N minutes ago" phrase; use it directly.
                val mlLastTrainedText = if (lastTrainedMs > 0L)
                    dateUtil.minAgoLong(resourceHelper, lastTrainedMs)
                else
                    resourceHelper.gs(R.string.dashboard_glass_loop_ml_never_trained)
                val mlSampleCountText = basalNeuralLearner.getGovernanceSnapshot().sampleCount.toString()
                val mlCircuitOpen = trainingCoordinator?.isCircuitOpenNow() ?: false

                val basalModelFile = trainingCoordinator?.basalWeightsFile()
                val basalModelFileText = basalModelFile?.takeIf { it.exists() }?.let { dateUtil.dateAndTimeString(it.lastModified()) }
                    ?: resourceHelper.gs(R.string.dashboard_glass_loop_ml_file_missing)

                val smbModelFile = AimiSmbModelStore.modelFile(aimiStorageHelper.getAimiDirectory())
                val smbModelFileText = smbModelFile.takeIf { it.exists() }?.let { dateUtil.dateAndTimeString(it.lastModified()) }
                    ?: resourceHelper.gs(R.string.dashboard_glass_loop_ml_file_missing)

                GlassLoopDashboardState(
                    isLoading = false,
                    lastRunTime = lastRunTime,
                    requestedSmbText = requestedSmbText,
                    glucoseText = if (hasGlucose) profileUtil.fromMgdlToStringInUnits(glucoseStatus?.glucose ?: 0.0) else "--",
                    delta5mText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(delta5mMgdl) else "--",
                    delta5mIsPositive = delta5mMgdl >= 0.0,
                    shortAvgDeltaText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(shortAvgDeltaMgdl) else "--",
                    shortAvgDeltaIsPositive = shortAvgDeltaMgdl >= 0.0,
                    longAvgDeltaText = if (hasGlucose) profileUtil.fromMgdlToSignedStringInUnits(longAvgDeltaMgdl) else "--",
                    longAvgDeltaIsPositive = longAvgDeltaMgdl >= 0.0,
                    iobText = iobText,
                    targetBgText = targetBgText,
                    maxIobProfileText = maxIobProfileText,
                    maxSmbProfileText = maxSmbProfileText,
                    isfText = isfText,
                    stableMinutesText = resourceHelper.gs(CoreUiR.string.format_mins, stableMinutes.roundToInt()),
                    tdd7DaysPerHourText = resourceHelper.gs(CoreUiR.string.pump_base_basal_rate, tdd7PerHour),
                    tirLow1hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow1h.roundToInt()),
                    tirLow24hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow24h.roundToInt()),
                    steps5mText = resourceHelper.gs(R.string.dashboard_glass_loop_steps_5m, steps5m),
                    hourOfDay = hourOfDay,
                    isWeekend = isWeekend,
                    buildVersionText = config.VERSION_NAME,
                    hasTrajectory = hasTrajectory,
                    trajectoryHybridText = trajectoryHybridText,
                    trajectoryIobText = trajectoryIobText,
                    trajectoryCobText = trajectoryCobText,
                    hasPhysio = hasPhysio,
                    physioModeText = physioModeText,
                    physioIntentText = physioIntentText,
                    physioThermalText = physioThermalText,
                    physioUpdatedText = physioUpdatedText,
                    hasMlTraining = hasMlTraining,
                    mlLastTrainedText = mlLastTrainedText,
                    mlSampleCountText = mlSampleCountText,
                    mlCircuitOpen = mlCircuitOpen,
                    basalModelFileText = basalModelFileText,
                    smbModelFileText = smbModelFileText,
                )
            }

            _uiState.update { newState }
        }
    }
}
