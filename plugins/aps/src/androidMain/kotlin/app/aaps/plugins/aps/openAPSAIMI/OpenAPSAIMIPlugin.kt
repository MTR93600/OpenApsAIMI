package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.ApsStrings
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.LongSparseArray
import androidx.core.util.forEach
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.R as CoreKeysR
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiPreferenceInfoScreen
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.data.model.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.ui.compose.icons.IcPluginOpenAPS
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import app.aaps.plugins.aps.openAPSAIMI.ISF.DynIsfTrajectoryTuning
import app.aaps.plugins.aps.openAPSAIMI.ISF.DynamicSensitivityPolicy
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfAdjustmentEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.EndogenousPhaseHysteresis
import app.aaps.plugins.aps.openAPSAIMI.scenario.InsulinSlopePreserveHysteresis
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.IntKey as MetroIntKey
import dev.zacsweers.metro.binding
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import app.aaps.plugins.aps.openAPSAIMI.ISF.IsfBlender
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusion
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfFusionBounds
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinKineticsAuthority
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapPeakGovernor
import app.aaps.plugins.aps.openAPSAIMI.compose.PkpdTailPrudence
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapSitePeakShift
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryPeakBias
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryPeakMismatchScorer
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiAdaptationStatusBuilder
import app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryHistoryProvider
import androidx.core.util.isEmpty
import androidx.core.util.size
import androidx.core.net.toUri
import kotlin.math.abs
import kotlin.math.exp
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiControlCenterScreen
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer
import kotlinx.coroutines.withContext
import app.aaps.plugins.aps.openAPSAIMI.learning.AimiMlTrainingScheduler
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorViewerScreen
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiBackupManager
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

// Key 250: the APS-algorithm range is 200 (Loop), 210/220/230 (AMA/SMB/AutoISF) and 240
// (Autotune), all unchanged per ApsPluginRegistrations.kt's own note. 250 is the next free slot in
// that range - grepped every @IntKey/@MetroIntKey in the tree before picking it.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(250)
@SingleIn(AppScope::class)
open class OpenAPSAIMIPlugin  @Inject constructor(
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    override val rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    private val sp: SP,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalaimiSMB2: DetermineBasalaimiSMB2,
    private val profiler: Profiler,
    private val context: Context,
    private val apsResultProvider: () -> APSResult,
    private val unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner, // ?? Brain Injection
    private val stepsManager: app.aaps.plugins.aps.openAPSAIMI.steps.AIMIStepsManagerMTR, // ?? Steps Manager MTR
    private val physioManager: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioManagerMTR, // ?? Physiological Manager MTR
    // ?? Physiological Decision Adapter (The Safety Gate)
    private val physioAdapter: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR,
    private val auditorOrchestrator: app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorOrchestrator, // ?? AI Auditor MTR
    private val contextManager: app.aaps.plugins.aps.openAPSAIMI.context.ContextManager, // ?? Context Manager
    private val aimiBackupManager: AimiBackupManager, // ?? Cloud Backup Manager (Force Init)
    private val aimiMlTrainingScheduler: AimiMlTrainingScheduler,
    private val storageHelper: AimiStorageHelper,
    private val storage: AimiStorage,
    private val behaviorProfileSource: AimiBehaviorProfileSource,
    private val ch: ConcentrationHelper,
    private val trajectoryHistoryProvider: TrajectoryHistoryProvider,
    private val trajectoryGuard: TrajectoryGuard,
    private val dynIsfTrajectoryTuning: DynIsfTrajectoryTuning,
    private val tpoOrchestrator: TpoOrchestrator,
    private val fabricPrivacy: FabricPrivacy,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .composeContent { plugin ->
            app.aaps.plugins.aps.compose.OpenAPSComposeContent(
                apsPlugin = plugin as APS,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil
            )
        }
        .icon(IcPluginOpenAPS)
        .pluginName(ApsStrings.openapsaimi)
        .shortName(ApsStrings.oaps_aimi_shortname)
        .preferencesVisibleInSimpleMode(false)
        .showInList({ config.APS })
        .description(ApsStrings.description_openapsaimi)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

    /** Background work for plugin startup (avoids blocking the thread that calls [onStart]). */
    private val aimiPluginIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One-time migration after the classic (V1/V2) Autodrive engine was removed in favour of V3.
     * A user who had classic enabled but V3 disabled would otherwise silently lose all autodrive.
     * If the legacy key was stored and was on while V3 is off, enable V3 to preserve that intent,
     * then drop the legacy key so this never re-runs (and a later manual V3-off is respected).
     */
    private fun migrateClassicAutodriveToV3() {
        val legacyClassicKey = "key_use_Aimi_autoDrive"
        if (!sp.contains(legacyClassicKey)) return
        if (sp.getBoolean(legacyClassicKey, true) && !preferences.get(BooleanKey.OApsAIMIautoDriveActive)) {
            preferences.put(BooleanKey.OApsAIMIautoDriveActive, true)
            aapsLogger.info(LTag.APS, "Autodrive V1?V3 migration: enabled V3 (classic was on, V3 was off)")
        }
        sp.remove(legacyClassicKey)
    }

    override suspend fun onStart() {
        super.onStart()
        // Fresh cross-tick hysteresis for a new plugin lifetime (not per APS tick).
        EndogenousPhaseHysteresis.reset()
        InsulinSlopePreserveHysteresis.reset()
        migrateClassicAutodriveToV3()
        preferences.registerPreferences(app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey.entries)
        preferences.registerPreferences(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.entries)

        // Diagnostic (2026-07-22): trace the simplified PK/PD "tail power" slider key to pin the
        // reported startup reset. The startup snapshot answers "did the value survive the restart?";
        // each runtime change is fingerprinted by its landing value (Stability ladder 0.76/0.81/0.86;
        // migrate / applyLevel-centre 0.85; raw key default 0.5). Marker: PKPD_TAIL_TRACE.
        logTailDampingSnapshot("startup")
        tailDampingTraceJob?.cancel()
        tailDampingTraceJob = rxBus.toFlow(EventPreferenceChange::class)
            .onEach { event ->
                if (event.isChanged(DoubleKey.OApsAIMISmbTailDamping.key)) logTailDampingSnapshot("change")
            }
            .catch { t -> aapsLogger.error(LTag.APS, "PKPD_TAIL_TRACE subscription error", t) }
            .launchIn(aimiPluginIoScope)
        // Prewarm Therapy snapshot cache at plugin start to avoid first-loop default flags.
        aimiPluginIoScope.launch {
            runCatching { Therapy(persistenceLayer).updateStatesBasedOnTherapyEvents() }
                .onFailure { t -> aapsLogger.error(LTag.APS, "? Failed to prewarm Therapy snapshot", t) }
        }

        // ?? Start AIMI Steps Manager (Health Connect + Phone Sensor sync)
        try {
            stepsManager.start()
            aapsLogger.info(LTag.APS, "? AIMI Steps Manager started successfully")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "? Failed to start AIMI Steps Manager", e)
        }
        
        // ?? Start AIMI Physiological Manager
        try {
            physioManager.start()
            aapsLogger.info(LTag.APS, "? AIMI Physiological Manager started successfully")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "? Failed to start AIMI Physiological Manager", e)
        }

        physioPreferenceJob?.cancel()
        physioPreferenceJob = rxBus.toFlow(EventPreferenceChange::class)
            .onEach { event ->
                if (!event.isChanged(BooleanKey.AimiPhysioAssistantEnable.key)) return@onEach
                try {
                    if (preferences.get(BooleanKey.AimiPhysioAssistantEnable)) {
                        physioManager.start()
                    } else {
                        physioManager.stop()
                    }
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "Physio preference toggle handling failed", e)
                }
            }
            .catch { t -> aapsLogger.error(LTag.APS, "Physio preference Rx error", t) }
            .launchIn(aimiPluginIoScope)
        
        // ?? Basal / T3C ML trainer (6h); Autodrive attention stays on 24h schedule in AutodriveNeuralTrainer
        try {
            aimiMlTrainingScheduler.schedule()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "? Failed to schedule AIMI basal/T3C ML trainer", e)
        }
        
        AimiUamHandler.clearCache(context)
        AimiUamHandler.installConfidenceSupplier {
            // retourne null si tu veux "laisser la main" au runtime
            preferences.get(DoubleKey.AimiUamConfidence)
        }
        aimiPluginIoScope.launch {
            try {
                var count = 0
                val apsResults =
                    persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
                synchronized(dynIsfCacheLock) {
                    apsResults.forEach {
                        val glucose = it.glucoseStatus?.glucose ?: return@forEach
                        val variableSens = it.variableSens ?: return@forEach
                        val timestamp = it.date
                        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
                        if (variableSens > 0) dynIsfCache.put(key, variableSens)
                        count++
                    }
                }
                aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Dyn ISF cache warm-up failed", e)
            }
        }

        // ?? Pre-load ML model into memory for O(1) SMB inference on hot path
        try {
            val aimiDir = storageHelper.getAimiDirectory()
            val (status, path, error) = storageHelper.getStorageStatus()
            PkPdCsvLogger.configureStorage(storage, aapsLogger)
            AimiSmbTrainer.loadModel(aimiDir)
            aapsLogger.info(LTag.APS, "AIMI storage status=$status path=${path ?: "n/a"} error=${error ?: "none"}")
            aapsLogger.info(LTag.APS, "? AimiSmbTrainer: model load requested (async)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "? AimiSmbTrainer: failed to request model load", e)
        }
    }
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorAimi.getGlucoseStatusData(allowOldData)
    /** Diagnostic snapshot of the simplified PK/PD tail-power slider key ([phase] = startup|change). */
    private fun logTailDampingSnapshot(phase: String) {
        val raw = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        aapsLogger.info(
            LTag.APS,
            "PKPD_TAIL_TRACE $phase raw=$raw effective=${PkpdSmbTailDamping.effectiveStoredValue(raw)} " +
                "uiLevel=${PkpdTailPrudence.readUiLevel(preferences)}",
        )
    }

    override suspend fun onStop() {
        super.onStop()

        physioPreferenceJob?.cancel()
        physioPreferenceJob = null
        tailDampingTraceJob?.cancel()
        tailDampingTraceJob = null
        
        // ?? Stop AIMI Steps Manager
        try {
            stepsManager.stop()
            aapsLogger.info(LTag.APS, "?? AIMI Steps Manager stopped")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI Steps Manager", e)
        }
        
        // ?? Stop AIMI Physiological Manager
        try {
            physioManager.stop()
            aapsLogger.info(LTag.APS, "?? AIMI Physiological Manager stopped")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI Physiological Manager", e)
        }

        try {
            aimiMlTrainingScheduler.cancel()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error stopping AIMI basal/T3C ML trainer", e)
        }

        AimiUamHandler.close(context)
    }
    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.AIMI
    override var lastAPSResult: APSResult? = null
    override fun usingDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
    override fun offersDynamicSensitivity(): Boolean = true
    private val pkpdIntegration = PkPdIntegration(preferences, behaviorProfileSource)
    private var lastPkpdScale: Double = 1.0
    // Dans votre classe principale (ou plugin), vous pouvez d?clarer :
    private val kalmanISFCalculator = KalmanISFCalculator(tddCalculator, preferences, aapsLogger)
    // Fusion lente (TDD/profile) + rate-limit de blend
    private val isfBlender = IsfBlender()
    // top-level (? c?t? de isfBlender / pkpdIntegration)
    private val isfAdjEngine = IsfAdjustmentEngine()

    /** R?agit au switch Physio sans red?marrer l?app (planifie / annule WorkManager). */
    private var physioPreferenceJob: Job? = null

    /** Diagnostic: rx subscription tracing writes to `OApsAIMISmbTailDamping` (PKPD_TAIL_TRACE). */
    private var tailDampingTraceJob: Job? = null

    // ?tat EMA persistant (cl? Prefs ? cr?er si tu veux le garder entre runs)
    private var tddEma: Double? = null
    private val TDD_EMA_ALPHA = 0.2 // ou pref
    @Volatile private var cachedCannulaSiteAgeDays: Float = 0f
    private val cannulaSiteRefreshInFlight = AtomicBoolean(false)


    // Recr?e les bornes de la fusion ISF depuis les pr?f?rences (m?mes cl?s que PkPdIntegration)
    private fun isfFusion(): IsfFusion {
        val bounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
        )
        return IsfFusion(bounds)
    }

    /**
     * Age of current infusion site in days (0 if no cannula change in the look-back window).
     * Same window as main AIMI loop site logic (7 days of therapy events).
     */
    private fun computeCannulaSiteAgeDays(): Float {
        refreshCannulaSiteAgeAsync()
        return cachedCannulaSiteAgeDays
    }

    private fun refreshCannulaSiteAgeAsync() {
        if (!cannulaSiteRefreshInFlight.compareAndSet(false, true)) return
        aimiPluginIoScope.launch {
            try {
                val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
                val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)
                cachedCannulaSiteAgeDays = if (siteChanges.isNotEmpty()) {
                    val latestChangeTimestamp = siteChanges.last().timestamp
                    ((System.currentTimeMillis() - latestChangeTimestamp).toFloat() / (1000f * 60f * 60f * 24f))
                } else {
                    0f
                }
            } catch (_: Exception) {
                cachedCannulaSiteAgeDays = 0f
            } finally {
                cannulaSiteRefreshInFlight.set(false)
            }
        }
    }

    /**
     * Rebuild the forward insulin-activity array using the LEARNED PK/PD kinetics (adaptive DIA/peak) instead
     * of the fixed insulin profile, so the AIMI prediction curves (eventual / minPred / pkpd graph) reflect how
     * this patient's insulin actually acts. Reuses the production [IobCobCalculator.calculateIobArrayInDia] with
     * a profile whose [ICfg] carries the learned kinetics ? no parallel IOB math, same treatment iteration.
     *
     * Fail-safe: returns null (? caller falls back to the static-profile array) when the pref is off, the learner
     * exposes no valid DIA/peak, the exponential model would be numerically invalid (peak must stay < DIA/2), or
     * anything throws. Only the prediction curves consume this array; SMB IOB accounting is untouched.
     */
    private suspend fun buildLearnedKineticsIobArray(
        effectiveProfile: EffectiveProfile,
        learnedDiaHrs: Double?,
        learnedPeakMin: Double?,
    ): Array<IobTotal>? {
        if (!preferences.get(BooleanKey.OApsAIMIPkpdPredictionKinetics)) return null
        val diaHrs = learnedDiaHrs?.takeIf { it.isFinite() && it in 3.0..12.0 } ?: return null
        val peakMin = learnedPeakMin?.takeIf { it.isFinite() && it in 20.0..240.0 } ?: return null
        return try {
            val learnedICfg: ICfg = effectiveProfile.iCfg.copy(
                insulinEndTime = (diaHrs * 3_600_000.0).toLong(),
                insulinPeakTime = (peakMin * 60_000.0).toLong(),
            )
            // Exponential insulin model requires peak strictly below DIA/2 (else tau ? 0 ? NaN). The learner
            // clamps DIA and peak independently, so a high-peak / short-DIA combo can violate this ? fall back.
            if (learnedICfg.insulinPeakTime * 2 >= learnedICfg.insulinEndTime) return null
            val learnedProfile = object : EffectiveProfile by effectiveProfile { override val iCfg: ICfg = learnedICfg }
            iobCobCalculator.calculateIobArrayInDia(learnedProfile).also {
                aapsLogger.debug(LTag.APS, "PK/PD prediction kinetics: curves on learned DIA=${"%.1f".format(diaHrs)}h peak=${peakMin.toInt()}m")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "PK/PD learned-kinetics prediction array failed; using profile kinetics", e)
            null
        }
    }

    private fun mapInsulinActivityStageToHistoryStage(stage: InsulinActivityStage): ActivityStage =
        when (stage) {
            InsulinActivityStage.PRE_ONSET, InsulinActivityStage.RISING -> ActivityStage.RISING
            InsulinActivityStage.PEAK -> ActivityStage.PEAK
            InsulinActivityStage.TAIL, InsulinActivityStage.EXHAUSTED -> ActivityStage.TAIL
        }

    /**
     * Trajectory geometry ? small bounded peak nudge for [TapPeakGovernor] (TAP-G Phase D, same APS tick).
     */
    private suspend fun computeTrajectoryPeakNudgeForGovernor(
        nowMsForPkpd: Long,
        profile: EffectiveProfile,
        glucoseStatus: GlucoseStatus,
        pkpdRuntimeForActivity: PkPdRuntime?,
        mealCobForPkpd: Double,
        pkpdWindowSinceDoseMinForPkpd: Int,
        currentBasalUph: Double,
        targetBgMgdl: Double,
    ): Double {
        if (!preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)) return 0.0
        if (!preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled)) return 0.0
        return try {
            val stage = pkpdRuntimeForActivity?.activity?.stage?.let(::mapInsulinActivityStageToHistoryStage)
                ?: ActivityStage.PEAK
            val activityNow = pkpdRuntimeForActivity?.activity?.relativeActivity ?: 0.0
            val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMsForPkpd, profile).iob
            val accel = glucoseStatus.delta - glucoseStatus.shortAvgDelta
            val history = trajectoryHistoryProvider.buildHistory(
                nowMillis = nowMsForPkpd,
                historyMinutes = 90,
                currentBg = glucoseStatus.glucose,
                currentDelta = glucoseStatus.delta,
                currentAccel = accel,
                insulinActivityNow = activityNow,
                iobNow = iobNow,
                pkpdStage = stage,
                timeSinceLastBolus = pkpdWindowSinceDoseMinForPkpd,
                cobNow = mealCobForPkpd,
                effectiveProfile = profile,
                historicalInsulinPeakMinutes = profile.iCfg.peak.coerceAtLeast(35),
            )
            val orbit = StableOrbit.fromProfile(
                targetBg = targetBgMgdl,
                basalRate = currentBasalUph.coerceAtLeast(0.05),
            )
            val analysis = trajectoryGuard.analyzeTrajectory(history, orbit) ?: return 0.0
            val geometryNudge = TrajectoryPeakBias.minutesNudge(
                analysis = analysis,
                lastBolusAgeMinutes = pkpdWindowSinceDoseMinForPkpd,
                cobGrams = mealCobForPkpd,
            )
            val mismatchNudge = if (geometryNudge == 0.0) {
                TrajectoryPeakMismatchScorer.minutesNudgeFromHistoryOrZero(
                    history = history,
                    insulinPeakMinutes = profile.iCfg.peak.coerceAtLeast(35),
                    lastBolusAgeMinutes = pkpdWindowSinceDoseMinForPkpd,
                    cobGrams = mealCobForPkpd,
                )
            } else {
                0.0
            }
            geometryNudge + mismatchNudge
        } catch (_: Exception) {
            0.0
        }
    }

    @SuppressLint("DefaultLocale")
    /**
     * Reads the newest cache entry and records **which source was used** in [IsfSourceTelemetry].
     *
     * Diagnostic only — it does not change which value is returned. The cache key is
     * `bucketStart + glucose` (see the warm-up loop), so `valueAt(size - 1)` returns the entry of
     * the highest glucose in the newest 30-minute bucket, not the most recent one. The bucket start
     * is recovered from the key to report the age of the value actually used.
     *
     * See `docs/adr/0003-dynisf-cache-read-path.md`.
     */
    private fun readNewestDynIsfAndRecordSource(now: Long): Double? {
        val entry = synchronized(dynIsfCacheLock) {
            val size = dynIsfCache.size()
            if (size == 0) null else dynIsfCache.keyAt(size - 1) to dynIsfCache.valueAt(size - 1)
        }
        if (entry == null) {
            IsfSourceTelemetry.record(IsfSourceTelemetry.SOURCE_PROFILE_FALLBACK, null, null)
            return null
        }
        val (key, value) = entry
        val bucketMs = T.mins(30).msecs()
        val bucketStart = key - key % bucketMs
        // The key is `bucketStart + glucose`, so the remainder identifies the reading this value was
        // computed for. Two entries of the same bucket share an age but not a key.
        val cacheGlucose = key % bucketMs
        val ageMs = (now - bucketStart).coerceAtLeast(0L)
        val source =
            if (ageMs > IsfSourceTelemetry.STALE_AFTER_MS) IsfSourceTelemetry.SOURCE_DYNAMIC_STALE
            else IsfSourceTelemetry.SOURCE_DYNAMIC_FRESH
        IsfSourceTelemetry.record(source, value, ageMs, cacheKey = key, cacheGlucoseMgdl = cacheGlucose)
        return value
    }

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as? ProfileSealed.EPS)?.value?.originalPercentage?.div(100.0)
            ?: run {
                IsfSourceTelemetry.record(IsfSourceTelemetry.SOURCE_NO_EPS, null, null)
                return null
            }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Keep UI path non-blocking; use latest cache and refresh in background.
            val cached = readNewestDynIsfAndRecordSource(start)
            aimiPluginIoScope.launch { runCatching { calculateVariableIsf(start) } }
            return cached?.let { it * multiplier }
        }

        val cached = readNewestDynIsfAndRecordSource(start)
        aimiPluginIoScope.launch { runCatching { calculateVariableIsf(start) } }
        profiler.log(
            LTag.APS,
            "getIsfMgdl() CACHE $cached src=${IsfSourceTelemetry.lastSource} " +
                "age=${IsfSourceTelemetry.lastAgeMs} ${dateUtil.dateAndTimeAndSecondsString(start)} $caller",
            start
        )
        return cached?.let { it * multiplier }
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        val (count, sum) = synchronized(dynIsfCacheLock) {
            if (dynIsfCache.isEmpty()) {
                return@synchronized -1 to 0.0
            }
            var c = 0
            var s = 0.0
            val start = timestamp - T.hours(24).msecs()
            dynIsfCache.forEach { key, value ->
                if (key in start..timestamp) {
                    c++
                    s += value
                }
            }
            c to s
        }
        if (count < 0) {
            maybeLogDynIsfCacheEmptyWarning(caller)
            return null
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    @Volatile
    private var lastDynIsfCacheEmptyWarnMs: Long = 0L

    /**
     * Throttle noisy warning to avoid log storms when cache is cold.
     * In this branch we return null, and upstream profile logic applies the profile ISF fallback.
     */
    private fun maybeLogDynIsfCacheEmptyWarning(caller: String) {
        val nowMs = dateUtil.now()
        if (nowMs - lastDynIsfCacheEmptyWarnMs < TimeUnit.MINUTES.toMillis(5)) return
        lastDynIsfCacheEmptyWarnMs = nowMs
        aapsLogger.warn(
            LTag.APS,
            "dynIsfCache is empty; returning null average ISF (profile fallback upstream). caller=$caller",
        )
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    private val dynIsfCache = LongSparseArray<Double>()
    private val dynIsfCacheLock = Any()

    // Exemple de fonction pour pr?dire le delta futur ? partir d'un historique r?cent
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pond?r?e avec des poids croissants pour donner plus d'importance aux valeurs r?centes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }
    private fun estimateKalmanTrustFromDelta(delta: Double?): Double {
        val d = kotlin.math.abs(delta ?: 0.0)
        // 0..10 mg/dL/5min -> 0.1..0.9
        return (d / 10.0).coerceIn(0.1, 0.9)
    }

    // ISF bas? TDD (ancre 1800/TDD 24h) avec garde-fous
    private suspend fun tddIsf24hOr(profileIsf: Double): Double {
        val tdd24 = tddCalculator
            .averageTDD(tddCalculator.calculate(1, allowMissingDays = false))
            ?.data?.totalAmount
            ?: preferences.get(DoubleKey.OApsAIMITDD7) // fallback 7j
        val anchored = if (tdd24 > 0.1) 1800.0 / tdd24 else profileIsf
        return anchored.coerceIn(5.0, 400.0)
    }
    /**
     * Situational multiplier on the estimated sensitivity.
     *
     * ## What this used to do, and why it was wrong
     *
     * The hyperglycaemia arm carried two terms. The linear one reduced the sensitivity by up to half
     * between BG 110 and 200 and kept going, with no lower bound: it reaches 0.0 at BG 290 and turns
     * **negative** above it. The second term was an exponential in the rise rate,
     * `exp(-0.3 * (combinedDelta - 10))`, which took over whenever the rise exceeded 10 mg/dL per
     * 5 min — so the steeper the rise, the harder the sensitivity was crushed. At a combined delta of
     * +26 it returns **0.0073**. Measured in production on 2026-08-14 at BG 186.6 rising +26.4:
     * `isf_dynamic_factor` was 0.01, and the commanded sensitivity fell to **4.54 mg/dL/U** against a
     * static profile of 30 — a factor of 6.6 — held only by the `coerceIn(5.0, 300.0)` at the exit of
     * `calculateVariableIsf`. On 2026-08-12 at BG 290 the factor was measured at **-0.00**, and at
     * BG 297 at **-0.04**. That absolute clamp, which ADR 0008 records as never binding, is the only
     * thing standing between the pump and a negative sensitivity.
     *
     * Both terms are dose policy — "correct harder" — written inside the number every prediction, the
     * MPC and `ControlBarrierShield` read. ADR 0008 says they belong in an urgency term applied at the
     * dose terminal, not in `S`. This is step 3 of that migration, done for the rise arm only.
     *
     * ## What the corpus says the BG dependence actually is
     *
     * Insulin sensitivity was estimated from outcomes over **96 clean descents** (>= 30 min, >= 25
     * mg/dL fall, no carbs on board, appearance rate below 0.30, at least 0.8 U absorbed), as
     * `-dBG / insulin absorbed`. Endogenous glucose production is ignored, so every figure is a lower
     * bound on the true sensitivity. Median by starting glucose:
     *
     * | starting BG | n  | median estimate | relative |
     * |---|---|---|---|
     * | 70-140      | 27 | 24.3 mg/dL/U | 1.00 |
     * | 140-200     | 45 | 22.6 mg/dL/U | 0.93 |
     * | 200-400     | 24 | 18.7 mg/dL/U | 0.77 |
     *
     * So this patient really is less sensitive when high — by about **x1.3 across the whole range**.
     * The old code applied up to x137 between a fall and a steep rise. The linear coefficient is now
     * 0.15 instead of 0.5, which reproduces the measured 0.77 at BG 240 (0.783), and it is bounded
     * below at 0.75 so it cannot leave the range the measurement covers.
     *
     * The rise-rate term is **removed**, not re-tuned: there is no support in the corpus for the
     * sensitivity depending on how fast glucose is climbing. The aggressiveness that term was
     * expressing is legitimate, and it belongs downstream, where it is bounded and audited.
     *
     * ## What is deliberately not changed
     *
     * The falling arm (`exp(0.15 * |combinedDelta|)`, capped at 1.4) is left exactly as it was. It
     * raises the sensitivity on a fall, which makes the loop predict a larger effect from the insulin
     * already on board and dose less. Lowering that cap would permit **more** insulin on a fall. That is
     * a hypoglycaemia path and it is not touched here, even though the same 96 descents give no support
     * for it either.
     *
     * @param delta the current 5-minute glucose delta, mg/dL.
     * @param predicted the weighted mean of the recent deltas, mg/dL per 5 min.
     * @param bg current glucose, mg/dL.
     */
    private fun dynamicDeltaCorrectionFactor(delta: Double?, predicted: Double?, bg: Double?): Double =
        DynamicSensitivityPolicy.factorFor(delta = delta, predicted = predicted, bgMgdl = bg)

    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(true) ?: return emptyList()
        val bg = smb.glucose
        val deltaNow = smb.delta
        if (data.isEmpty()) return emptyList()

        val standardWindow = if (bg < 130) 30f else 15f
        val rapidRiseWindow = 10f
        val intervalMinutes = if (deltaNow > 15) rapidRiseWindow else standardWindow

        val nowTs = data.first().timestamp
        val recent = mutableListOf<Double>()
        for (i in 1 until data.size) {
            val r = data[i]
            if (r.value > 39 && !r.filledGap) {
                val minAgo = ((nowTs - r.timestamp) / 60000.0).toFloat()
                if (minAgo in 0.0f..intervalMinutes) {
                    val d = (data.first().recalculated - r.recalculated) / minAgo * 5f
                    recent.add(d)
                }
            }
        }
        return recent
    }
    @SuppressLint("DefaultLocale")
    private suspend fun calculateVariableIsf(
        timestamp: Long,
        pkpdScaleForTick: Double = lastPkpdScale,
        fusedSlowIsfOverride: Double? = null,
    ): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return "OFF" to null

        // 0) cache DB existant
        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null) return "DB" to result.variableSens

        // 1) BG & deltas actuels
        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return "GLUC" to null
        val currentDelta = glucoseStatusProvider.glucoseStatusData?.delta
        val recentDeltas = getRecentDeltas()
        val predictedDelta = predictedDelta(recentDeltas)

        // 2) facteur historique (comme avant)
        val dynamicFactor = dynamicDeltaCorrectionFactor(currentDelta, predictedDelta, glucose)

        // 3) ISF rapide #1 : Kalman existant
        val kalmanFastIsf = kalmanISFCalculator.calculateISF(glucose, currentDelta, predictedDelta)
        aapsLogger.debug(LTag.APS, "Adaptive ISF via Kalman: $kalmanFastIsf for BG: $glucose")

        // 4) ISF lent (socle) : profil/TDD fusionn? + pkpdScale (inchang?)
        val profileIsf = profileFunction.getProfile()?.getProfileIsfMgdl() ?: 20.0
        val tddIsf = tddIsf24hOr(profileIsf)
        val fusedSlowIsf = fusedSlowIsfOverride?.takeIf { it.isFinite() && it > 0.0 }
            ?: isfFusion().fused(profileIsf, tddIsf, pkpdScaleForTick)
        aapsLogger.debug(LTag.APS, "Fused slow ISF: $fusedSlowIsf (profile=$profileIsf, tddIsf=$tddIsf, pkpdScale=$pkpdScaleForTick)")

        // 5) EMA TDD (stabilise l?ajustement AF)
        val tdd24 = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: tddIsf /* fallback */
        tddEma = when (val prev = tddEma) {
            null -> tdd24
            else -> prev + TDD_EMA_ALPHA * (tdd24 - prev)
        }

        // 6) proxys de confiance (si variance non expos?e ici)
        val kalmanTrustProxy = estimateKalmanTrustFromDelta(currentDelta)             // 0..1
        val kalmanVarProxy = (1.0 - kalmanTrustProxy).coerceIn(0.0, 1.0)             // 1-trust
        val sippConfidence = AimiUamHandler.confidenceOrZero().coerceIn(0.0, 1.0)

        // 7) ISF rapide #2 : IsfAdjustmentEngine (AF ln(BG/55) + TDD-EMA + rate-limit)
        val isfAdj = isfAdjEngine.compute(
            bgKalman = glucose,
            tddEma   = (tddEma ?: tdd24),
            profileIsf = profileIsf,
            sippConfidence = sippConfidence,
            kalmanVar = kalmanVarProxy,
            nowMs = System.currentTimeMillis()
        )
        aapsLogger.debug(LTag.APS, "Adaptive ISF via IsfAdjustmentEngine: $isfAdj (tddEma=$tddEma, sipp=$sippConfidence, var=$kalmanVarProxy)")

        // 8) Combine the two fast estimators.
        //
        // This was written as `listOf(kalmanFastIsf, isfAdj).sorted()[1]` and described as a "robust
        // median". Two values have no median: `sorted()[1]` is the maximum. The behaviour is kept
        // because the maximum is the **conservative** choice here — a higher ISF means the loop
        // assumes insulin acts more strongly, so it doses less — but it is now named for what it is.
        //
        // Whether max, min or mean is right is a calibration question, not a naming one. It needs the
        // replay corpus and a shadow period, so it is deliberately not changed here.
        val fastConservative = max(kalmanFastIsf, isfAdj)

        // 9) Blend final (socle lent vs rapide), avec rate-limit temporel de IsfBlender
        var blended = isfBlender.blend(
            fusedIsf = fusedSlowIsf,
            kalmanIsf = fastConservative,
            trustFast = kalmanTrustProxy,
            nowMs = System.currentTimeMillis()
        )

        // 10) facteur dynamique + bornes globales
        blended *= dynamicFactor

        // 10b) ajustement trajectoire (g?om?trie CGM type AutoISF), born? ? avant physio pour limiter le cumul
        val profileForPhysio = profileFunction.getProfile()
        val physioIsfFactor: Double
        val physioMultsNullable: PhysioMultipliersMTR?
        if (profileForPhysio != null) {
            val iobForPhysio = iobCobCalculator.calculateFromTreatmentsAndTemps(timestamp, profileForPhysio)
            val mealForPhysio = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            val physioMults = physioAdapter.getMultipliers(
                currentBG = glucose,
                currentDelta = currentDelta ?: 0.0,
                iob = iobForPhysio.iob,
                cob = mealForPhysio.carbs,
            )
            physioIsfFactor = physioMults.isfFactor
            physioMultsNullable = physioMults
        } else {
            physioIsfFactor = 1.0
            physioMultsNullable = null
        }
        val trajectoryTuning = dynIsfTrajectoryTuning.computeAdjustedIsf(
            blendedMgdlPerU = blended,
            profileIsfMgdlPerU = profileIsf,
            bgMgdl = glucose,
            bgQualityState = bgQualityCheck.state,
            physioIsfFactor = physioIsfFactor,
        )
        blended = trajectoryTuning.isfMgdlPerU

        // 🏥 The physiological factor is NOT applied here.
        //
        // It used to be, and the callers applied it again on their own value, so `profile.sens`
        // carried it twice (0.72–1.32 instead of 0.85–1.15) while `profile.variable_sens` carried it
        // once — two fields meant to hold the same quantity, differing by one factor.
        //
        // What this function returns is the **estimated** sensitivity. The physiological factor is a
        // situational modulation, applied once by each caller on a fresh value; keeping it out of the
        // cached estimate also stops a 30-minute-old factor from being frozen into it.
        // See `docs/adr/0002-sensitivity-three-levels.md`.

        blended = blended.coerceIn(5.0, 300.0)

        // Diagnostic only: keep the intermediate terms so a support package can attribute the
        // movement of the commanded sensitivity. See `docs/adr/0002-sensitivity-three-levels.md`.
        IsfSourceTelemetry.recordComponents(
            kalmanFastIsf = kalmanFastIsf,
            isfAdjEngine = isfAdj,
            fusedSlowIsf = fusedSlowIsf,
            trustFast = kalmanTrustProxy,
            dynamicFactor = dynamicFactor,
            trajectoryMultiplier = trajectoryTuning.trajectoryMultiplier,
        )

        aapsLogger.debug(LTag.APS, "Final DynISF: $blended")
        aapsLogger.debug(
            LTag.APS,
            "DynISF inputs: fusedSlowIsf=$fusedSlowIsf, kalmanFastIsf=$kalmanFastIsf, isfAdj=$isfAdj, trustFast=$kalmanTrustProxy, pkpdScale=$pkpdScaleForTick"
        )

        // 11) cache
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        synchronized(dynIsfCacheLock) {
            if (dynIsfCache.size > 1000) dynIsfCache.clear()
            dynIsfCache.put(key, blended)
        }

        return "CALC" to blended
    }


    override suspend fun invoke(initiator: String, tempBasalFallback: Boolean): Unit = withContext(Dispatchers.Default) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = getGlucoseStatusData(false)
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
            return@withContext
        }
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump

        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(CoreUiStrings.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(CoreUiStrings.no_profile_set))
            return@withContext
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_disabled))
            return@withContext
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        val eff = profile as EffectiveProfile
        if (!hardLimits.checkHardLimits(eff.iCfg.dia, InterfacesStrings.profile_dia, hardLimits.diaRange())) return@withContext
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                InterfacesStrings.profile_carbs_ratio_value,
                hardLimits.icRange()
            )
        ) return@withContext
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSAIMIPlugin"), InterfacesStrings.profile_sensitivity_value, HardLimits.LIMIT_ISF)) return@withContext
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return@withContext
        if (!hardLimits.checkHardLimits(ch.fromPump(pump.baseBasalRate), app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return@withContext

        // End of check, start gathering data
        
        // ?? PHYSIO INTEGRATION: Retrieve Context & Multipliers
        val glucoseForPhysio = glucoseStatusProvider.glucoseStatusData?.glucose ?: 100.0
        val deltaForPhysio = glucoseStatusProvider.glucoseStatusData?.delta ?: 0.0
        
        // Calculate IOB/COB early for Physio Adapter
        val nowMs = dateUtil.now()
        val iobCalc = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMs, profile) // Uses 'profile' from enabled check above
        val mealDataForPhysio = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
        
        val physioMults = physioAdapter.getMultipliers(
            currentBG = glucoseForPhysio, 
            currentDelta = deltaForPhysio,
            iob = iobCalc.iob,
            cob = mealDataForPhysio.carbs
        )
        
        if (!physioMults.isNeutral()) {
            aapsLogger.info(LTag.APS, "?? LOOP: Applying Physio Factors: ISF x${physioMults.isfFactor}, Basal x${physioMults.basalFactor}, SMB x${physioMults.smbFactor}")
        }

        val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity)
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), InterfacesStrings.profile_low_target, HardLimits.LIMIT_MIN_BG)
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), InterfacesStrings.profile_high_target, HardLimits.LIMIT_MAX_BG)
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG)
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG)
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG)
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG)
        }
        val insulinDivisor = when {
            profile.iCfg.peak > 65 -> 55 // rapid peak: 75
            profile.iCfg.peak > 50 -> 65 // ultra rapid peak: 55
            else                  -> 45 // lyumjev peak: 45
        }

        var autosensResult = AutosensResult()
        val tddStatus: TddStatus?
        val variableSensitivity = 0.0
        val tdd = 0.0
        if (true) { // FIX: Always run, DetermineBasalAIMI2 handles dynIsfMode internally
            val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
//
// // Plancher pour ?viter des TDD trop faibles au d?marrage
            val minTDD = 10.0
//
// R?cup?ration et ajustement du TDD sur 7 jours
            val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))
            if (tdd7D != null && tdd7D.data.totalAmount > tdd7P && tdd7D.data.totalAmount > 1.3 * tdd7P) {
                tdd7D.data.totalAmount = 1.2 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 7 days limited to 10% increase. New TDD7D: ${tdd7D.data.totalAmount}")
            }
            if (tdd7D != null && tdd7D.data.totalAmount < tdd7P * 0.9) {
                tdd7D.data.totalAmount = tdd7P * 0.9
                aapsLogger.info(LTag.APS, "TDD for 7 days was too low. Adjusted to 90% of TDD7P: ${tdd7D.data.totalAmount}")
            }

            // Calcul du TDD sur 2 jours
            var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            // `!isFinite()` first: NaN and ±Infinity slip through `== 0.0` and `< tdd7P` (every
            // comparison with NaN is false), so without it a broken value would pass the floor
            // untouched and spread to the weighted tdd below. No change for any finite value.
            if (!tdd2Days.isFinite() || tdd2Days == 0.0 || tdd2Days < tdd7P) tdd2Days = tdd7P
//
            val tdd2DaysPerHour = tdd2Days / 24
            val tddLast4H = tdd2DaysPerHour * 4
//
// Calcul du TDD sur 1 jour avec une limite minimale pour ?viter des instabilit?s
            var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: 0.0
            if (!tddDaily.isFinite() || tddDaily == 0.0 || tddDaily < tdd7P / 2) tddDaily = maxOf(tdd7P, minTDD)

            if (tddDaily > tdd7P && tddDaily > 1.1 * tdd7P) {
                tddDaily = 1.1 * tdd7P
                aapsLogger.info(LTag.APS, "TDD for 1 day limited to 10% increase. New TDDDaily: $tddDaily")
            }
// // Calcul du TDD sur 24 heures
            var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 0.0
            if (!tdd24Hrs.isFinite() || tdd24Hrs == 0.0) tdd24Hrs = tdd7P
            val tdd24HrsPerHour = tdd24Hrs / 24
            val tddLast8to4H = tdd24HrsPerHour * 4
// // Calcul pond?r? du TDD r?cent pour ?viter les fluctuations extr?mes
            val tddWeightedFromLast8H = ((1.2 * tdd2DaysPerHour) + (0.3 * tddLast4H) + (0.5 * tddLast8to4H)) * 3
            val tdd = (tddWeightedFromLast8H * 0.20) + (tdd2Days * 0.50) + (tddDaily * 0.30)

            // On r?cup?re la glyc?mie et le delta actuel
            val gsData = glucoseStatusProvider.glucoseStatusData
            val currentBG = gsData?.glucose
            if (currentBG == null) {
                aapsLogger.error(LTag.APS, "Donn?es de glyc?mie indisponibles, impossibilit? de calculer l'ISF adaptatif.")
                return@withContext
            }
            val currentDelta = gsData?.delta
            val recentDeltas = getRecentDeltas()
            val predictedDelta = predictedDelta(recentDeltas)

            // Calcul adaptatif de l'ISF via la fonction centralis?e encapsulant le tout (incluant l'alimentation du cache)
            val mealCobForEarlyPkpd = mealDataForPhysio.carbs.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val pkpdRuntimeForActivity = pkpdIntegration.computeRuntime(
                epochMillis = now,
                bg = currentBG,
                deltaMgDlPer5 = currentDelta ?: 0.0,
                iobU = iobCalc.iob,
                carbsActiveG = mealCobForEarlyPkpd,
                windowMin = 90,
                exerciseFlag = false,
                profileIsf = profile.getProfileIsfMgdl(),
                tdd24h = tdd24Hrs,
                combinedDelta = currentDelta ?: 0.0,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                allowLearning = !preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath),
            )
            lastPkpdScale = pkpdRuntimeForActivity?.pkpdScale ?: 1.0

            val (source, calcSensitivity) = calculateVariableIsf(
                timestamp = now,
                pkpdScaleForTick = lastPkpdScale,
                fusedSlowIsfOverride = pkpdRuntimeForActivity?.fusedIsf,
            )
            var variableSensitivity = calcSensitivity ?: profile.getProfileIsfMgdl()
            
            aapsLogger.debug(LTag.APS, "Adaptive ISF computed (source: $source): $variableSensitivity for BG: $currentBG, currentDelta: $currentDelta, predictedDelta: $predictedDelta")

            // 🏥 Physiological modulation, applied here on every path — `calculateVariableIsf` returns
            // the estimate without it (see the note at its physio step). Previously this branch only
            // ran on the fallback path, so the dynamic path ended up with the factor once in
            // `variable_sens` and twice in `sens`.
            if (physioMults.isfFactor != 1.0) {
                variableSensitivity *= physioMults.isfFactor
                aapsLogger.debug(LTag.APS, "🏥 LOOP: DynISF modulated: $variableSensitivity (x${physioMults.isfFactor})")
            }
            // Profile-relative lower bound, unconditional and after every multiplier — ADR 0008 step 1,
            // lower half only. It can only raise the sensitivity, which can only tighten the barrier and
            // make the predictions attribute more effect to the insulin already on board.
            variableSensitivity = DynamicSensitivityPolicy.floorAgainstProfile(
                commandedMgdlPerU = variableSensitivity,
                profileIsfMgdlPerU = runCatching { profile.getProfileIsfMgdl() }.getOrNull(),
            )
            variableSensitivity = variableSensitivity.coerceIn(5.0, 300.0)

            aapsLogger.debug(LTag.APS, "Final adaptive ISF after clamping: $variableSensitivity")

// ?? Cr?ation du r?sultat final (Convention: Ratio < 1 = R?sistant)
            autosensResult = AutosensResult(
                ratio = tdd2Days / tdd24Hrs,
                ratioFromTdd = tdd2Days / tdd24Hrs,
                ratioFromCarbs = 1.0 
            )

            // ?? AIMI BRAIN INTEGRATION (UnifiedReactivityLearner)
            // "The Cognitive Bridge": Adjusts BOTH Sensitivity (ISF) and Resistance (Autosens Ratio)
            //
            // ? Skipped in T3C brittle mode: T3C has its own aggressiveness pipeline
            // (rawAggressiveness / adaptiveMult / CFRD boost). Applying Brain Boost on top would
            // create a redundant, conflicting autosens manipulation ? and could amplify the
            // pre-bolus preserved from applyLegacyMealModes, violating T3C's TBR-only design intent.
            val t3cBrittleActive = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
            if (t3cBrittleActive) {
                aapsLogger.debug(LTag.APS, "?? Brain Boost: skipped (T3C brittle mode active ? TBR-only path)")
            }
            if (!t3cBrittleActive) try {
                // ?? TRIPLE-SIGNAL CONFIRMATION for Confirmed Rise
                val gsAimi = gsData as? GlucoseStatusAIMI
                val accel = gsAimi?.bgAcceleration ?: 0.0
                val combDelta = ((gsData?.delta ?: 0.0) + predictedDelta) / 2.0
                val isConfirmedHighRise = (gsData?.glucose ?: 0.0) > 150.0 && combDelta > 1.5 && accel > 0.4

                unifiedReactivityLearner.processIfNeeded(isConfirmedHighRise)
                var brainFactor = unifiedReactivityLearner.getCombinedFactor()
                val bgNow = gsData?.glucose ?: 0.0
                val targetLow = profile.getTargetLowMgdl()
                val targetHigh = profile.getTargetHighMgdl()
                val nearTargetMargin = 15.0
                val isNearTargetBand = bgNow in (targetLow - nearTargetMargin)..(targetHigh + nearTargetMargin)
                val evidenceScore = run {
                    var score = 0
                    val deltaNow = gsData?.delta ?: 0.0
                    val shortNow = gsData?.shortAvgDelta ?: 0.0
                    val longNow = gsData?.longAvgDelta ?: 0.0
                    if (deltaNow > 1.2) score += 25
                    if (shortNow > 1.0) score += 20
                    if (longNow > 0.6) score += 10
                    if (combDelta > 1.5) score += 15
                    if (accel > 0.4) score += 15
                    if (predictedDelta > 1.5) score += 10
                    if (bgNow > targetHigh + 20.0) score += 10
                    if (deltaNow > 2.5 && accel > 0.6) score += 10
                    score.coerceIn(0, 100)
                }
                val hasFormalMealEvidence = evidenceScore >= 60
                
                // ?? SAFETY OVERRIDE (FCL 10.3) - Refined for "Blind Spot" Removal:
                // If we are in Hyper (>150) AND Rising/Stable, we MUST NOT be protective (<1.0).
                // ANTI-LAG: Lower threshold to 110 if rising fast (delta > 3.0)
                val isFastRise = (gsData?.delta ?: 0.0) > 3.0
                val overrideThreshold = if (isFastRise) 110.0 else 150.0
                val isHyper = (gsData?.glucose ?: 0.0) > overrideThreshold
                val isRising = (gsData?.delta ?: 0.0) > -0.5
                
                if (isHyper && isRising && brainFactor < 1.0) {
                    aapsLogger.debug(LTag.APS, "?? Brain Override: IGNORING protective factor ${"%.2f".format(brainFactor)} because BG ${gsData?.glucose ?: 0.0} is > $overrideThreshold & stable/rising.")
                    brainFactor = 1.0
                }

                // ??? Evidence Gate: near target, do not over-correct without formal rise evidence.
                if (isNearTargetBand && !hasFormalMealEvidence && brainFactor > 1.02) {
                    aapsLogger.debug(
                        LTag.APS,
                        "??? Evidence Gate: Near-target BG=${"%.1f".format(bgNow)} score=$evidenceScore ? cap brainFactor ${"%.2f".format(brainFactor)}?1.02"
                    )
                    brainFactor = 1.02
                }

                // ?? EXPLOSIVE RISE BOOST: If delta is very high, force a slight aggressive factor
                if (glucoseStatus.delta > 6.0 && brainFactor < 1.1 && (!isNearTargetBand || hasFormalMealEvidence)) {
                    aapsLogger.debug(LTag.APS, "?? Brain Boost: Forcing factor 1.1 due to explosive rise (delta=${glucoseStatus.delta})")
                    brainFactor = 1.1
                }
                if (glucoseStatus.delta > 6.0 && isNearTargetBand && !hasFormalMealEvidence) {
                    aapsLogger.debug(LTag.APS, "??? Evidence Gate: Explosive boost blocked near target (score=$evidenceScore, BG=${"%.1f".format(bgNow)})")
                }

                if (brainFactor != 1.0) {
                    val originalRatio = autosensResult.ratio
                    val originalISF = variableSensitivity
                    
                    // 1. Modulate Autosens Ratio (Basal/Targets/ISF)
                    // High Brain Factor (Aggressive) -> Ratio decreases (e.g. 0.8 / 1.5 = 0.53) -> More Resistant
                    autosensResult.ratio = originalRatio / brainFactor
                    
                    // ?? IMPORTANT: variableSensitivity (Dynamic ISF) is NO LONGER modulated here.
                    // It will be modulated by autosensResult.ratio in DetermineBasalAIMI2.kt 
                    // to ensure a single, consistent point of application for the "Resistance" multiplier.
                    
                    aapsLogger.debug(LTag.APS, "?? AIMI Brain Override: " +
                        "Autosens ${"%.2f".format(originalRatio)}->${"%.2f".format(autosensResult.ratio)} " +
                        "(Factor ${"%.2f".format(brainFactor)})")
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Failed to apply AIMI Brain factor", e)
            }

            val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
            val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()
            val nowMsForPkpd = dateUtil.now()
            val bgNowForPkpd = glucoseStatus.glucose
            val deltaNowForPkpd = glucoseStatus.delta
            val iobNowForPkpd = iobCobCalculator.calculateFromTreatmentsAndTemps(nowMsForPkpd, profile).iob
            val profileIsfRawForPkpd = profile.getProfileIsfMgdl()
            val iobHeadForPkpd = iobArray.firstOrNull()
            val pkpdWindowSinceDoseMinForPkpd = if (iobHeadForPkpd != null && iobHeadForPkpd.lastBolusTime > 0L) {
                ((nowMsForPkpd - iobHeadForPkpd.lastBolusTime) / 60000.0).toInt().coerceAtLeast(0)
            } else {
                90
            }
            val mealCobForPkpd = mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            // R?utilise le runtime PKPD calcul? avant DynISF (m?me tick, pkpdScale/fusedIsf align?s snapshot)
            aapsLogger.debug(
                LTag.APS,
                "PK/PD: pkpdScale=$lastPkpdScale (bg=$bgNowForPkpd, delta=$deltaNowForPkpd, iob=$iobNowForPkpd, tdd24=$tdd24Hrs, isfRaw=$profileIsfRawForPkpd)",
            )
            // Prediction curves on learned/effective PK/PD kinetics via InsulinKineticsAuthority.
            val trajectoryPeakNudgeMinutes = computeTrajectoryPeakNudgeForGovernor(
                nowMsForPkpd = nowMsForPkpd,
                profile = profile as EffectiveProfile,
                glucoseStatus = glucoseStatus,
                pkpdRuntimeForActivity = pkpdRuntimeForActivity,
                mealCobForPkpd = mealCobForPkpd,
                pkpdWindowSinceDoseMinForPkpd = pkpdWindowSinceDoseMinForPkpd,
                currentBasalUph = profile.getBasal(),
                targetBgMgdl = targetBg,
            )
            val sitePeakShiftMinutes = TapSitePeakShift.minutesForSiteAge(computeCannulaSiteAgeDays())
            val peakGovernorForActivity = TapPeakGovernor.resolve(
                insulinPeakMinutes = profile.iCfg.peak,
                physioPeakShiftMinutes = physioMults.peakShiftMinutes,
                sitePeakShiftMinutes = sitePeakShiftMinutes,
                pkpdLearnedPeak = pkpdRuntimeForActivity?.params?.peakMin,
                pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
                governorEnabled = preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
                peakMinBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
                peakMaxBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
                learnedBlendWeight = preferences.get(DoubleKey.OApsAIMIPeakGovernorLearnedWeight),
                trajectoryMinutesNudge = trajectoryPeakNudgeMinutes,
            )
            val kineticsView = InsulinKineticsAuthority.resolve(
                InsulinKineticsAuthority.ResolveInput(
                    accountingIobArray = iobArray,
                    profileDiaHours = eff.iCfg.dia,
                    profilePeakMinutes = peakGovernorForActivity.effectivePeakMinutes,
                    effectiveProfile = profile as EffectiveProfile,
                    pkpdRuntime = pkpdRuntimeForActivity,
                    peakGovernor = peakGovernorForActivity,
                    causalPosterior = null,
                    physioPeakShiftMinutes = physioMults.peakShiftMinutes,
                    sitePeakShiftMinutes = sitePeakShiftMinutes,
                    trajectoryPeakNudgeMinutes = trajectoryPeakNudgeMinutes,
                    preferences = preferences,
                    learningDiagnostics = PkpdLearningDiagnostics.from(
                        causalStatePosterior = null,
                        allowLearning = false,
                        exerciseFlag = false,
                        iobU = iobNowForPkpd,
                        carbsActiveG = mealCobForPkpd,
                        bg = bgNowForPkpd,
                        deltaMgDlPer5 = deltaNowForPkpd,
                    ),
                ),
            )
            val pkpdIobDataArray: Array<IobTotal>? = buildLearnedKineticsIobArray(
                effectiveProfile = profile as EffectiveProfile,
                learnedDiaHrs = kineticsView.effective.diaHours,
                learnedPeakMin = kineticsView.effective.peakMinutes,
            )
            kineticsView.diaGovernor?.logLine?.let { line -> aapsLogger.debug(LTag.APS, line) }
            peakGovernorForActivity.logLine?.let { line -> aapsLogger.debug(LTag.APS, line) }
            preferences.put(
                AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine,
                peakGovernorForActivity.logLine.orEmpty(),
            )
            if (peakGovernorForActivity.logLine.isNullOrBlank()) {
                preferences.put(AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed, "")
            }

            // H.1: Save detailed peak branch data for UI / PKPD Overview
            preferences.put(DoubleKey.OApsAIMIPkpdStatePriorPeak, peakGovernorForActivity.peakPrior)
            preferences.put(DoubleKey.OApsAIMIPkpdStatePhysioPeak, peakGovernorForActivity.peakPhysio)
            preferences.put(DoubleKey.OApsAIMIPkpdStateSitePeak, peakGovernorForActivity.peakSite)
            preferences.put(DoubleKey.OApsAIMIPkpdStateTrajectoryPeak, peakGovernorForActivity.peakTrajectory)
            preferences.put(DoubleKey.OApsAIMIPkpdStateEffectivePeak, peakGovernorForActivity.effectivePeakMinutes)
            preferences.put(AimiStringKey.OApsAIMIPkpdStateDominantBranch, peakGovernorForActivity.dominantBranch)

            val peakTimeMinutesForProfile = kineticsView.effective.peakMinutes
            var currentActivity = 0.0
            for (i in -4..0) { //MP: -4 to 0 calculates all the insulin active during the last 5 minutes
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(i.toLong()), profile)
                currentActivity += iob.activity
            }
            var futureActivity = 0.0

            // Cosine / activity integration uses the same governed peak as OapsProfileAimi.peakTime
            val safepk = peakTimeMinutesForProfile.toInt().coerceAtLeast(35)
            
            for (i in -4..0) { //MP: calculate 5-minute-insulin activity centering around peakTime
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(safepk.toLong() - i), profile)
                futureActivity += iob.activity
            }
            val sensorLag = -10L //MP Assume that the glucose value measurement reflect the BG value from 'sensorlag' minutes ago & calculate the insulin activity then
            var sensorLagActivity = 0.0
            for (i in -4..0) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sensorLag - i), profile)
                sensorLagActivity += iob.activity
            }

            val activityHistoric = -20L //MP Activity at the time in minutes from now. Used to calculate activity in the past to use as target activity.
            var historicActivity = 0.0
            for (i in -2..2) {
                val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(activityHistoric - i), profile)
                historicActivity += iob.activity
            }
// R?cup?re GS standard + features AIMI
            val pack = glucoseStatusCalculatorAimi.compute(allowOldData = true)
            val gs = pack.gs ?: run {
                rxBus.send(EventResetOpenAPSGui(rh.gs(ApsStrings.openapsma_no_glucose_data)))
                aapsLogger.debug(LTag.APS, rh.gs(ApsStrings.openapsma_no_glucose_data))
                return@withContext
            }
            val f = pack.features

// Construit l?objet attendu par determine_basal
            val glucoseStatusAimi = GlucoseStatusAIMI(
                glucose         = gs.glucose,
                noise           = gs.noise,
                delta           = gs.delta,
                shortAvgDelta   = gs.shortAvgDelta,
                longAvgDelta    = gs.longAvgDelta,
                date            = gs.date,

                // Champs AIMI disponibles
                duraISFminutes  = f?.stable5pctMinutes ?: 0.0,
                deltaPl         = f?.delta5Prev ?: 0.0,
                deltaPn         = f?.delta5Next ?: 0.0,
                bgAcceleration  = f?.accel ?: 0.0,
                corrSqu         = f?.corrR2 ?: 0.0,

                // Champs non expos?s par AimiBgFeatures => valeurs neutres
                duraISFaverage  = 0.0,
                parabolaMinutes = 0.0,
                a0              = 0.0,
                a1              = 0.0,
                a2              = 0.0,
                // Propagate CGM source (G6/G7/One+/…) so AIMI lead stays G6-only
                sourceSensor    = gs.sourceSensor
            )
            futureActivity = Round.roundTo(futureActivity, 0.0001)
            sensorLagActivity = Round.roundTo(sensorLagActivity, 0.0001)
            historicActivity = Round.roundTo(historicActivity, 0.0001)
            currentActivity = Round.roundTo(currentActivity, 0.0001)
            val tdd4D = tddCalculator.averageTDD(tddCalculator.calculate(4, allowMissingDays = false))
            // Diagnostic only: capture the static profile ISF next to the command value written into
            // `sens` below, so a support package can show the gap between the two.
            // See `docs/adr/0002-sensitivity-three-levels.md`.
            IsfSourceTelemetry.recordProfileStatic(
                runCatching { profile.getProfileIsfMgdl() }.getOrNull()
            )
            IsfSourceTelemetry.recordPhysioFactor(physioMults.isfFactor)
            val oapsProfile = OapsProfileAimi(
                dia = eff.iCfg.dia,
                min_5m_carbimpact = 0.0, // not used
                max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
                max_daily_basal = profile.getMaxDailyBasal(),
                max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
                min_bg = minBg,
                max_bg = maxBg,
                target_bg = targetBg,
                carb_ratio = profile.getIc(),
                // The profile-relative lower bound is applied here too, because this is the value that
                // becomes `profile.sens` — the number read by the predictions, the tube advisor and the
                // hypoglycaemia guard, and exported as `command_isf_mgdl`. Without it the bound would sit
                // before the multipliers that undo it, which is the defect ADR 0008 keeps recording: the
                // physiological factor is applied after the `coerceIn(5.0, 300.0)` on this path, which is
                // how a commanded sensitivity of 4.54 mg/dL/U was reached on 2026-08-14 (5.00 x 0.908).
                sens = DynamicSensitivityPolicy.floorAgainstProfile(
                    commandedMgdlPerU = profile.getIsfMgdl("OpenAPSAIMIPlugin") * physioMults.isfFactor,
                    profileIsfMgdlPerU = runCatching { profile.getProfileIsfMgdl() }.getOrNull(),
                ).also { commanded ->
                    // Shadow only — nothing reads this. Records what an **unconditional exit clamp**
                    // relative to the profile would command.
                    //
                    // It was first placed inside `calculateVariableIsf`, before the effective-profile
                    // percentage and the physiological factor. Production then showed the commanded
                    // sensitivity reaching x0.46 and x2.11 of profile while the shadow reported a
                    // single hit in 282 ticks — the guard had been put before the multipliers that
                    // undo it, which is precisely the defect this ADR set keeps documenting. It now
                    // sits where the value is final.
                    // See `docs/adr/0008-isf-decision-architecture.md`.
                    IsfSourceTelemetry.recordProfileRelativeShadow(
                        blendedMgdl = commanded,
                        profileIsfMgdl = runCatching { profile.getProfileIsfMgdl() }.getOrNull() ?: 0.0,
                    )
                },
                autosens_adjust_targets = false, // not used
                max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier) * physioMults.smbFactor, // ?? SMB Cap modulation
                current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier) * physioMults.basalFactor, // ?? Basal Cap modulation
                lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
                high_temptarget_raises_sensitivity = false,
                low_temptarget_lowers_sensitivity = false,
                sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
                resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
                adv_target_adjustments = SMBDefaults.adv_target_adjustments,
                exercise_mode = SMBDefaults.exercise_mode,
                half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
                maxCOB = SMBDefaults.maxCOB,
                skip_neutral_temps = pump.setNeutralTempAtFullHour(),
                remainingCarbsCap = SMBDefaults.remainingCarbsCap,
                enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
                A52_risk_enable = SMBDefaults.A52_risk_enable,
                SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
                enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
                enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
                allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
                enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
                enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
                maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
                maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
                bolus_increment = pump.pumpDescription.bolusStep,
                carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
                current_basal = ch.fromPump(activePlugin.activePump.baseBasalRate) * physioMults.basalFactor, // ?? Basal Rate Modulation
                temptargetSet = isTempTarget,
                autosens_max = preferences.get(DoubleKey.AutosensMax),
                out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
                variable_sens = variableSensitivity,
                insulinDivisor = insulinDivisor,
                TDD = if (tdd4D == null) preferences.get(DoubleKey.OApsAIMITDD7) else tdd,
                peakTime = peakTimeMinutesForProfile,
                futureActivity = futureActivity,
                sensorLagActivity = sensorLagActivity,
                historicActivity = historicActivity,
                currentActivity = currentActivity
            )

            // Keep AIMI aligned with the upstream SMB/AutoISF crash guard: these values feed divisions in
            // determine_basal and a non-finite/non-positive input can otherwise cascade into NaN dosing fields.
            // TDD is checked for finiteness only, and outside the dynIsfMode gate on purpose.
            // Unlike SMB, AIMI always has a TDD: it falls back to the OApsAIMITDD7 preference, so
            // `TDD <= 0.0` cannot happen and adding it would be dead code that only creates a
            // dependency on that preference's lower bound. A NaN, on the other hand, is not caught
            // by the `== 0.0 || < tdd7P` floors that build it (every comparison with NaN is false),
            // and it would reach `basalaimi` and `ci` in DetermineBasalAIMI2. Those floors are now
            // NaN-proof too; this check stays as the backstop.
            val invalidInputs = !oapsProfile.sens.isFinite() || oapsProfile.sens <= 0.0 ||
                !oapsProfile.carb_ratio.isFinite() || oapsProfile.carb_ratio <= 0.0 ||
                !autosensResult.ratio.isFinite() || autosensResult.ratio <= 0.0 ||
                !oapsProfile.TDD.isFinite() ||
                (dynIsfMode && (!oapsProfile.variable_sens.isFinite() || oapsProfile.variable_sens <= 0.0))
            if (invalidInputs) {
                val msg = "OpenAPS AIMI aborting: invalid ISF inputs " +
                    "dynIsfMode=$dynIsfMode sens=${oapsProfile.sens} carb_ratio=${oapsProfile.carb_ratio} " +
                    "autosensRatio=${autosensResult.ratio} variable_sens=${oapsProfile.variable_sens} " +
                    "TDD=${oapsProfile.TDD}"
                aapsLogger.error(LTag.APS, msg)
                // Parity with OpenAPSSMBPlugin: without these two the abort is invisible - it never
                // reaches Crashlytics and the APS card keeps showing the previous run.
                fabricPrivacy.logException(IllegalStateException(msg))
                rxBus.send(EventResetOpenAPSGui(msg))
                rxBus.send(EventOpenAPSUpdateGui())
                return@withContext
            }

            val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
            val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

            aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal AIMI <<<")
            aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
            aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
            aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
            aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
            aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
            aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
            aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
            aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
            aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

            determineBasalaimiSMB2.determine_basal(
                glucose_status = glucoseStatusAimi,
                currenttemp = currentTemp,
                iob_data_array = iobArray,
                profile = oapsProfile,
                autosens_data = autosensResult,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = now,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode,
                uiInteraction = uiInteraction,
                pkpd_iob_data_array = pkpdIobDataArray,
                effective_dia_hours = kineticsView.effective.diaHours,
                effective_peak_minutes = kineticsView.effective.peakMinutes,
                extraDebug = physioMults.detailedReason
            ).also {
                it.aimiAdaptationStatus = it.aimiAdaptationStatus?.let { status ->
                    AimiAdaptationStatusBuilder.enrichGovernors(
                        status = status,
                        now = nowMsForPkpd,
                        pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
                        peakGovernorEnabled = preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
                        peakGovernor = peakGovernorForActivity,
                        diaGovernorEnabled = preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled),
                        diaGovernor = kineticsView.diaGovernor,
                    )
                }
                val determineBasalResult = apsResultProvider().with(it)
                
                if (it.predBGs != null) {
                    // FCL 11.1: Force Populate predictionsAsGv for UI (OverviewViewModel)
                    // If 'with(RT)' failed to populate the list, we do it manually here.
                    if (determineBasalResult.predictionsAsGv.isEmpty()) {
                        it.predBGs?.IOB?.forEachIndexed { index, value ->
                             val time = now + index * 300000L // 5 mins
                             val gv = GV(
                                 timestamp = time,
                                 value = value.toDouble(),
                                 raw = value.toDouble(),
                                 trendArrow = TrendArrow.NONE,
                                 noise = 0.0,
                                 sourceSensor = SourceSensor.IOB_PREDICTION
                             )
                             determineBasalResult.predictionsAsGv.add(gv)
                         }
                    }
                }

                // Preserve input data
                determineBasalResult.inputConstraints = inputConstraints
                determineBasalResult.autosensResult = autosensResult
                determineBasalResult.iobData = iobArray
                determineBasalResult.glucoseStatus = glucoseStatus
                determineBasalResult.currentTemp = currentTemp
                determineBasalResult.oapsProfileAimi = oapsProfile
                determineBasalResult.mealData = mealData
                lastAPSResult = determineBasalResult
                lastAPSRun = now
                aapsLogger.debug(LTag.APS, "Result: $it")
                rxBus.send(EventAPSCalculationFinished())
            }

            rxBus.send(EventOpenAPSUpdateGui())
        }
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(ApsStrings.limiting_iob, maxIobPref, rh.gs(ApsStrings.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(ApsStrings.limiting_iob, hardLimits.maxIobSMB(), rh.gs(ApsStrings.hardlimit)), this)
        }
        return maxIob
    }
    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
    }

    override fun applyBasalConstraints(
        absoluteRate: Constraint<Double>,
        profile: Profile
    ): Constraint<Double> {
        // ????????????????????????????????????????????????????
        // 1?? On d?tecte si l?on est en mode ?meal? ou ?early autodrive?
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        
        // ?? Context Integration (Remote/AI)
        val contextSnapshot = contextManager.getSnapshot(dateUtil.now())
        
        val isMealMode = therapy.snackTime
            || therapy.highCarbTime
            || therapy.mealTime
            || therapy.lunchTime
            || therapy.dinnerTime
            || therapy.bfastTime
            || contextSnapshot.hasMealRisk // ?? Remote "Lunch/Meal" triggers this

        val isSportMode = therapy.sportTime || contextSnapshot.hasActivity // ?? Remote "Sport" triggers this

        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7
        val isAutodriveEnabled = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
        val smb = glucoseStatusCalculatorAimi.getGlucoseStatusData(false) ?: return absoluteRate

        val isEarlyAutodrive = !night && !isMealMode && !isSportMode && isAutodriveEnabled &&
            determineBasalaimiSMB2.isAutodriveEngaged()

        val isSpecialMode = isMealMode || isEarlyAutodrive || preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)

        // ????????????????????????????????????????????????????
        // 2?? On choisit la bonne pref en fonction du mode
        var maxBasal = when {
            isMealMode       -> preferences.get(DoubleKey.meal_modes_MaxBasal)
            isEarlyAutodrive -> preferences.get(DoubleKey.autodriveMaxBasal)
            else             -> preferences.get(DoubleKey.ApsMaxBasal)
        }

        if (isEnabled()) {
            // 3?? On remonte au maxDailyBasal si besoin
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(
                    rh.gs(ApsStrings.increasing_max_basal),
                    this
                )
            }

            // 4?? On bride toujours sur maxBasal
            absoluteRate.setIfSmaller(
                maxBasal,
                rh.gs(
                    CoreUiStrings.limitingbasalratio,
                    maxBasal,
                    rh.gs(ApsStrings.maxvalueinpreferences)
                ),
                this
            )

            // ???> **Si on est dans un mode sp?cial, on s?arr?te l? :**
            if (isSpecialMode) {
                return absoluteRate
            }

            // ????????????????????????????????????????????????????
            // 5?? Sinon, on applique en plus le multiplicateur ?current basal?
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(
                    CoreUiStrings.limitingbasalratio,
                    maxFromBasalMultiplier,
                    rh.gs(ApsStrings.max_basal_multiplier)
                ),
                this
            )

            // 6?? Et le multiplicateur ?daily basal?
            val maxDailyMultiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxDailyMultiplier * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromDaily,
                rh.gs(
                    CoreUiStrings.limitingbasalratio,
                    maxFromDaily,
                    rh.gs(ApsStrings.max_daily_basal_multiplier)
                ),
                this
            )

            // TODO(kmp): AfrezzaMaxBasalConstraints not on kmp yet
        }

        return absoluteRate
    }

    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(ApsStrings.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(ApsStrings.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(ApsStrings.autosens_disabled_in_preferences), this)
        }
        return value
    }

    /**
     * Required for [app.aaps.ui.compose.preferences.AllPreferencesScreen]: only plugins that return
     * a [PreferenceSubScreenDef] appear in the Compose settings list. XML-only [addPreferenceScreen]
     * does not surface there.
     */
    override fun getPreferenceScreenContent(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "openapsaimi_settings",
            titleResId = R.string.openapsaimi,
            icon = pluginDescription.icon,
            items = buildAimiComposePreferenceItems(),
        )

    /**
     * OpenAPS/SMB core keys (same order as [app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin]) +
     * nested AIMI sections so Compose settings match most of the legacy tree.
     */
    private fun buildAimiComposePreferenceItems(): List<PreferenceItem> = buildList {
        add(DoubleKey.ApsMaxBasal)
        // TODO(kmp): AfrezzaMaxBasalConstraints not on kmp yet
        add(DoubleKey.ApsSmbMaxIob)
        add(BooleanKey.ApsUseDynamicSensitivity)
        add(BooleanKey.ApsUseAutosens)
        add(IntKey.ApsDynIsfAdjustmentFactor)
        add(UnitDoubleKey.ApsLgsThreshold)
        add(BooleanKey.ApsDynIsfAdjustSensitivity)
        add(BooleanKey.ApsSensitivityRaisesTarget)
        add(BooleanKey.ApsResistanceLowersTarget)
        add(BooleanKey.ApsUseSmb)
        add(BooleanKey.ApsUseSmbWithHighTt)
        add(BooleanKey.ApsUseSmbAlways)
        add(BooleanKey.ApsUseSmbWithCob)
        add(BooleanKey.ApsUseSmbWithLowTt)
        add(BooleanKey.ApsUseSmbAfterCarbs)
        add(IntKey.ApsMaxSmbFrequency)
        add(IntKey.ApsMaxMinutesOfBasalToLimitSmb)
        add(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb)
        add(BooleanKey.ApsUseUam)
        add(IntKey.ApsCarbsRequestThreshold)
        add(
            PreferenceSubScreenDef(
                key = "openapsaimi_absorption_advanced",
                titleResId = app.aaps.core.ui.R.string.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier,
                ),
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_user_preferences",
                titleResId = R.string.user_preferences,
                items = aimiComposeUserPreferenceItems(),
            )
        )
    }

    private fun aimiComposeEntries(vararg entries: Pair<String, String>): Map<String, TextRef> =
        entries.associate { (value, label) -> value to TextRef.Literal(label) }

    private fun aimiComposeUserPreferenceItems(): List<PreferenceItem> = buildList {
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_ai_keys",
                titleResId = R.string.aimi_prefs_ai_title,
                items = listOf(
                    StringKey.AimiAdvisorProvider.withEntries(
                        mapOf(
                            "OPENAI" to ApsStrings.aimi_prefs_provider_openai,
                            "GEMINI" to ApsStrings.aimi_prefs_provider_gemini,
                            "DEEPSEEK" to ApsStrings.aimi_prefs_provider_deepseek,
                            "CLAUDE" to ApsStrings.aimi_prefs_provider_claude,
                        )
                    ),
                    StringKey.AimiAdvisorOpenAIKey,
                    StringKey.AimiAdvisorGeminiKey,
                    StringKey.AimiAdvisorDeepSeekKey,
                    StringKey.AimiAdvisorClaudeKey,
                    BooleanKey.OApsAIMIAdvisorPersonalOrefMl,
                    BooleanKey.OApsAIMIAdvisorLlmRichOref,
                ),
            )
        )
        // Single string pref: inline avoids an extra nested sub-screen + full-screen drill-down
        // (same UX as other lone keys, e.g. pregnancy date).
        add(AimiStringKey.RemoteControlPin)
        add(aimiComposeTpoSubScreen())
        add(
            ApsIntentKey.AimiControlCenter.withCompose(
                ComposeScreenContent { onBack ->
                    AimiControlCenterScreen(
                        preferences = preferences,
                        tpoOrchestrator = tpoOrchestrator,
                        onBack = onBack,
                    )
                },
            ),
        )
        add(aimiComposePkpdGuidedSubScreen())
        add(aimiComposePatientContextSubScreen())
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_sos",
                titleResId = R.string.aimi_sos_title,
                items = buildList {
                    add(BooleanKey.AimiEmergencySosEnable)
                    add(StringKey.AimiEmergencySosPhone)
                    add(StringKey.AimiEmergencySosPhone2)
                    add(IntKey.AimiEmergencySosThreshold)
                    add(IntKey.AimiEmergencySosImmediateThreshold)
                    add(IntKey.AimiEmergencySosStaleThreshold)
                    add(ApsIntentKey.AimiSosPermissions)
                    add(
                        ApsIntentKey.AimiHypoRiskAlarmInfo.withCompose(
                            ComposeScreenContent { onBack ->
                                AimiPreferenceInfoScreen(
                                    titleResId = R.string.hypo_risk_notification_title,
                                    messageResId = R.string.aimi_hypo_risk_alarm_summary,
                                    onBack = onBack,
                                )
                            },
                        ),
                    )
                },
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_physio",
                titleResId = R.string.aimi_physio_title,
                items = buildList {
                    add(BooleanKey.AimiPhysioAssistantEnable)
                    add(
                        ApsIntentKey.AimiPhysioPatternCatalogInfo.withCompose(
                            ComposeScreenContent { onBack ->
                                AimiPreferenceInfoScreen(
                                    titleResId = R.string.aimi_physio_pattern_catalog_title,
                                    messageResId = R.string.aimi_physio_pattern_catalog_detail,
                                    onBack = onBack,
                                )
                            },
                        ),
                    )
                    add(ApsIntentKey.AimiHealthConnectPermissions)
                    add(AimiStringKey.ActivitySourceMode)
                    add(AimiStringKey.OuraPersonalAccessToken)
                    add(BooleanKey.AimiPhysioSleepDataEnable)
                    add(BooleanKey.AimiPhysioHRVDataEnable)
                    add(BooleanKey.AimiPhysioLLMAnalysisEnable)
                    add(BooleanKey.AimiPhysioDebugLogs)
                },
            )
        )
        add(aimiComposeLabSubScreen())
    }

    private fun aimiComposeHormonitorViewerItem(): PreferenceItem =
        ApsIntentKey.HormonitorViewer.withCompose(
            ComposeScreenContent { onBack ->
                HormonitorViewerScreen(onBack = onBack)
            }
        )

    private fun aimiComposePkpdSetupItem(): PreferenceItem =
        ApsIntentKey.PkpdSetup.withCompose(
            ComposeScreenContent { onBack ->
                AimiPkpdSettingsScreen(
                    preferences = preferences,
                    onBack = onBack,
                    loadProfileInsulin = {
                        val profile = profileFunction.getProfile() as? EffectiveProfile
                        profile?.iCfg?.dia to profile?.iCfg?.peak?.toDouble()
                    },
                    loadPkpdRecommendations = {
                        withContext(Dispatchers.IO) {
                            AimiAdvisorService(
                                profileFunction = profileFunction,
                                persistenceLayer = persistenceLayer,
                                preferences = preferences,
                                rh = rh,
                                unifiedReactivityLearner = unifiedReactivityLearner,
                                tddCalculator = tddCalculator,
                            ).pkpdRecommendationsForSettings(7)
                        }
                    },
                )
            }
        )

    private fun aimiComposeTpoSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_tpo",
            titleResId = R.string.aimi_tpo_prefs_subscreen_title,
            items = listOf(
                BooleanKey.OApsAIMITpoEnabled,
                BooleanKey.OApsAIMITpoLlmConfirmEnabled,
                BooleanKey.OApsAIMITpoNotifyOnApply,
            ),
        )

    private fun aimiComposePkpdGuidedSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_pkpd_guided",
            titleResId = R.string.aimi_pkpd_guided_title,
            items = listOf(aimiComposePkpdSetupItem()),
        )

    private fun aimiComposePatientContextSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_patient_context",
            titleResId = R.string.aimi_patient_context_title,
            items = buildList {
                add(DoubleKey.OApsAIMIweight)
                add(DoubleKey.OApsAIMICHO)
                add(DoubleKey.OApsAIMITDD7)
                add(BooleanKey.OApsAIMIpregnancy)
                add(AimiStringKey.PregnancyDueDateString)
                add(BooleanKey.OApsAIMIhoneymoon)
                add(BooleanKey.OApsAIMInight)
                add(aimiComposeWomenCycleSubScreen())
                add(aimiComposeInflammatorySubScreen())
                add(aimiComposeThyroidModuleSubScreen())
                add(aimiComposeEndometriosisSubScreen())
                add(aimiComposeNightGrowthSubScreen())
            },
        )

    private fun aimiComposeLabSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_lab",
            titleResId = R.string.aimi_lab_title,
            items = buildList {
                add(BooleanKey.OApsAIMIMLtraining)
                add(aimiComposeHormonitorViewerItem())
                add(DoubleKey.OApsAIMIMaxSMB)
                add(DoubleKey.OApsAIMIHighBGMaxSMB)
                add(aimiComposePkpdSubScreen())
                add(aimiComposeAdaptiveBasalSubScreen())
                add(aimiComposeT3cSubScreen())
                add(aimiComposeTrajectorySubScreen())
                add(aimiComposeManualModesSubScreen())
                add(aimiComposeAutodriveSubScreen())
                add(BooleanKey.OApsxdriponeminute)
                add(BooleanKey.OApsAIMIUnifiedReactivityEnabled)
                add(aimiComposeAiAuditorSubScreen())
            },
        )

    private fun aimiComposeAdaptiveBasalSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_adaptive_basal",
            titleResId = R.string.oaps_aimi_adaptive_basal_title,
            items = buildList {
                add(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
                add(BooleanKey.OApsAIMIBasalSlewLimitEnabled)
                add(BooleanKey.OApsAIMIBasalChannelSafetyGuards)
                add(BooleanKey.OApsAIMIBasalTerminalInvariants)
                add(BooleanKey.OApsAIMIBasalProjectedError)
                add(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling)
                add(DoubleKey.OApsAIMIGovernanceHypoRateEnter)
                add(DoubleKey.OApsAIMIGovernanceHypoRateExit)
                add(DoubleKey.OApsAIMIGovernanceHypoBgMgdl)
                add(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate)
                add(DoubleKey.OApsAIMIGovernanceHoldAggFloorRate)
                add(DoubleKey.OApsAIMIGovernanceHoldAggDecayRate)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere)
                add(DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere)
                add(DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere)
                add(DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere)
                add(DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples)
                add(DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl)
                add(DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp)
                add(DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax)
            },
        )

    private fun aimiComposeT3cSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_t3c",
            titleResId = R.string.aimi_t3c_settings_title,
            items = listOf(
                BooleanKey.OApsAIMIT3cBrittleMode,
                BooleanKey.OApsAIMIT3cAutodriveBasalAuthority,
                BooleanKey.OApsAIMIT3cHyperBasalFloor,
                BooleanKey.OApsAIMIT3cCfrdMode,
                BooleanKey.OApsAIMIT3cCfrdExacerbationMode,
                DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl,
                DoubleKey.OApsAIMIT3cCfrdCobDelayMin,
                DoubleKey.OApsAIMIT3cActivationThreshold,
                DoubleKey.OApsAIMIT3cAggressiveness,
                DoubleKey.OApsAIMIT3cAnticipationStrength,
                BooleanKey.OApsAIMIUndeclaredCobEnabled,
                DoubleKey.OApsAIMIUndeclaredCobMaxG,
            ),
        )

    private fun aimiComposeTrajectorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_trajectory",
            titleResId = R.string.aimi_trajectory_section_title,
            items = buildList {
                add(BooleanKey.OApsAIMITrajectoryGuardEnabled)
                add(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_tube_mpc",
                        titleResId = CoreKeysR.string.aimi_tube_advanced_title,
                        items = listOf(
                            DoubleKey.AimiTubeHypoFloorMgdl,
                            DoubleKey.AimiTubeHyperBandMgdl,
                            DoubleKey.AimiTubeAggressiveness,
                            DoubleKey.AimiTubeBasalTrimMax,
                            DoubleKey.AimiTubeKappaSafetyMargin,
                        ),
                    )
                )
            },
        )

    private fun aimiComposeWomenCycleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_wcycle",
            titleResId = R.string.wcycle_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIwcycle)
                add(
                    StringKey.OApsAIMIWCycleTrackingMode.withEntries(
                        aimiComposeEntries(
                            "FIXED_28" to "Fixe 28 jours",
                            "CALENDAR_VARIABLE" to "Calendrier variable",
                            "NO_MENSES_LARC" to "Sans règles (LARC)",
                            "PERIMENOPAUSE" to "Périménopause",
                            "MENOPAUSE" to "Ménopause",
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIWCycleContraceptive.withEntries(
                        aimiComposeEntries(
                            "NONE" to "Aucune",
                            "COC_PILL" to "Pilule combinée (COC)",
                            "POP_PILL" to "Pilule progestative (POP)",
                            "HORMONAL_IUD" to "DIU hormonal",
                            "COPPER_IUD" to "DIU cuivre",
                            "IMPLANT" to "Implant",
                            "INJECTION" to "Injection",
                            "RING" to "Anneau",
                            "PATCH" to "Patch",
                        )
                    )
                )
                add(DoubleKey.OApsAIMIwcycledateday)
                add(IntKey.OApsAIMIWCycleAvgLength)
                add(BooleanKey.OApsAIMIWCycleShadow)
                add(BooleanKey.OApsAIMIWCycleRequireConfirm)
                add(DoubleKey.OApsAIMIWCycleClampMin)
                add(DoubleKey.OApsAIMIWCycleClampMax)
            },
        )

    private fun aimiComposeInflammatorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_inflammatory",
            titleResId = R.string.aimi_inflammatory_diseases_title,
            items = listOf(
                StringKey.OApsAIMIWCycleThyroid.withEntries(
                    aimiComposeEntries(
                        "EUTHYROID" to "Euthyroïde",
                        "HYPOTHYROID_TREATED" to "Hypothyroïdie traitée",
                        "HASHIMOTO" to "Hashimoto",
                        "THYROIDECTOMY" to "Thyroïdectomie",
                    )
                ),
                StringKey.OApsAIMIWCycleVerneuil.withEntries(
                    aimiComposeEntries(
                        "NONE" to "Aucune",
                        "QUIESCENT" to "Calme",
                        "ACTIVE" to "Active",
                        "FLARE" to "Push",
                    )
                ),
            ),
        )

    private fun aimiComposeThyroidModuleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_thyroid_module",
            titleResId = R.string.oaps_aimi_thyroid_title,
            items = buildList {
                add(BooleanKey.OApsAIMIThyroidEnabled)
                add(
                    StringKey.OApsAIMIThyroidMode.withEntries(
                        aimiComposeEntries(
                            "MANUAL" to "Manual Selection",
                            "AUTO" to "Auto (Labs + Physio) - Experimental",
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidManualStatus.withEntries(
                        aimiComposeEntries(
                            "EUTHYROID" to "Euthyroid (Normal)",
                            "HYPER_MILD" to "Mild Hyper",
                            "HYPER_MODERATE" to "Moderate Hyper",
                            "HYPER_SEVERE" to "Severe Hyper",
                            "NORMALIZING" to "Normalizing (High Risk)",
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidTreatmentPhase.withEntries(
                        aimiComposeEntries(
                            "NONE" to "None (Stable Euthyroid)",
                            "TITRATION" to "Titration (Starting Meds)",
                            "STABLE" to "Stable Treatment",
                            "DE_ESCALATION" to "De-escalation (Stopping Meds)",
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidGuardLevel.withEntries(
                        aimiComposeEntries(
                            "LOW" to "Low (Minimal caps)",
                            "MEDIUM" to "Medium (Standard caps)",
                            "HIGH" to "High (Strict block on fast drops)",
                        )
                    )
                )
                add(BooleanKey.OApsAIMIThyroidLogVerbosity)
            },
        )

    private fun aimiComposeEndometriosisSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_endo",
            titleResId = R.string.endo_preferences_title,
            items = listOf(
                BooleanKey.AimiEndometriosisEnable,
                BooleanKey.AimiEndometriosisPainFlare,
                IntKey.AimiEndometriosisFlareDuration,
                DoubleKey.AimiEndometriosisBasalMult,
                DoubleKey.AimiEndometriosisSmbDampen,
            ),
        )

    private fun aimiComposeAiAuditorSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ai_auditor",
            titleResId = R.string.aimi_ai_auditor_section_title,
            items = buildList {
                add(BooleanKey.AimiAuditorEnabled)
                add(
                    StringKey.AimiAuditorMode.withEntries(
                        aimiComposeEntries(
                            "AUDIT_ONLY" to "Audit only (log verdicts)",
                            "SOFT_MODULATION" to "Soft modulation (apply if confident)",
                            "HIGH_RISK_ONLY" to "High risk only (apply with risk flags)",
                        )
                    )
                )
                add(IntKey.AimiAuditorMaxPerHour)
                add(IntKey.AimiAuditorTimeoutSeconds)
                add(IntKey.AimiAuditorMinConfidence)
            },
        )

    private fun aimiComposeNightGrowthSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ngr",
            titleResId = R.string.oaps_aimi_ngr_title,
            items = listOf(
                BooleanKey.OApsAIMINightGrowthEnabled,
                IntKey.OApsAIMINightGrowthAgeYears,
                StringKey.OApsAIMINightGrowthStart,
                StringKey.OApsAIMINightGrowthEnd,
                DoubleKey.OApsAIMINightGrowthMaxIobExtra,
            ),
        )

    private fun aimiComposeManualModesSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_training_ml_modes",
            titleResId = R.string.training_ml_modes_preferences,
            items = buildList {
                add(DoubleKey.meal_modes_MaxBasal)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_breakfast",
                        titleResId = R.string.training_ml_breakfast_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIBFPrebolus,
                            DoubleKey.OApsAIMIBFPrebolus2,
                            DoubleKey.OApsAIMIBFFactor,
                            IntKey.OApsAIMIBFinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_lunch",
                        titleResId = R.string.training_ml_lunch_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMILunchPrebolus,
                            DoubleKey.OApsAIMILunchPrebolus2,
                            DoubleKey.OApsAIMILunchFactor,
                            IntKey.OApsAIMILunchinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_dinner",
                        titleResId = R.string.training_ml_dinner_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIDinnerPrebolus,
                            DoubleKey.OApsAIMIDinnerPrebolus2,
                            DoubleKey.OApsAIMIDinnerFactor,
                            IntKey.OApsAIMIDinnerinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_highcarb",
                        titleResId = R.string.training_ml_high_carb_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIHighCarbPrebolus,
                            DoubleKey.OApsAIMIHighCarbPrebolus2,
                            DoubleKey.OApsAIMIHCFactor,
                            IntKey.OApsAIMIHCinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_snack",
                        titleResId = R.string.training_ml_snack_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMISnackPrebolus,
                            DoubleKey.OApsAIMISnackFactor,
                            IntKey.OApsAIMISnackinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_meal",
                        titleResId = R.string.training_ml_generic_meal_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIMealPrebolus,
                            DoubleKey.OApsAIMIMealFactor,
                            IntKey.OApsAIMImealinterval,
                        ),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_mode_sleep",
                        titleResId = R.string.training_ml_sleep_modes_preferences,
                        items = listOf(
                            DoubleKey.OApsAIMIsleepFactor,
                            IntKey.OApsAIMISleepinterval,
                        ),
                    )
                )
            },
        )

    private fun aimiComposeAutodriveSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_autodrive",
            titleResId = R.string.autodrive_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIautoDriveActive)
                add(BooleanKey.OApsAIMIautoDriveAuthoritative)
                add(BooleanKey.OApsAIMIHyperTrajectoryRelease)
                add(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive)
                add(BooleanKey.OApsAIMIRecursiveBeliefShadow)
                add(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
                add(BooleanKey.OApsAIMIRecursiveBeliefWavelet)
                add(BooleanKey.OApsAIMITreeMealRiseFrontLoad)
                add(BooleanKey.OApsAIMISensorConfidenceCgmFirst)
                add(BooleanKey.OApsAIMIMealConfirmedEarlyRelease)
                add(DoubleKey.OApsAIMIHyperEstablishedDevMgdl)
                add(DoubleKey.OApsAIMIHyperDeepDevMgdl)
                add(DoubleKey.autodriveMaxBasal)
                add(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
                add(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor)
                add(BooleanKey.OApsAIMIEffortActivityProtection)
                add(DoubleKey.OApsAIMIautodrivesmallPrebolus)
                add(DoubleKey.OApsAIMIautodrivePrebolus)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_autodrive_prebolus_vars",
                        titleResId = R.string.autodrive_prebolus_variables,
                        items = listOf(
                            IntKey.OApsAIMIAutodriveBG,
                            DoubleKey.OApsAIMIcombinedDelta,
                            DoubleKey.OApsAIMIAutodriveDeviation,
                        ),
                    )
                )
            },
        )

    private fun aimiComposePkpdSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_pkpd",
            titleResId = R.string.oaps_aimi_pkpd_section_title,
            // Wave1 G1: product surface is the guided Compose screen only — raw key dump removed.
            items = listOf(aimiComposePkpdSetupItem()),
        )

}
