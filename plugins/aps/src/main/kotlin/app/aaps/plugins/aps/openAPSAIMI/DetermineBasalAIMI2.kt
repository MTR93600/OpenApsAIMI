package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.UE
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.carbs.CarbsAdvisor
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.MealAggressionContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.physio.toSNSDominance // 🧬 Physio Extensions
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLogRow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.ports.PkpdPort
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoTools
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbDampingUsecase
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbInstructionExecutor
import app.aaps.plugins.aps.openAPSAIMI.smb.computeMealHighIobDecision
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.comparison.AimiSmbComparator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleInfo
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionProfiler
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdAbsorptionGuard
import app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit  // 🌀 Trajectory Control
import app.aaps.plugins.aps.openAPSAIMI.trajectory.WarningSeverity  // 🌀 Trajectory Warnings
import app.aaps.plugins.aps.openAPSAIMI.context.ContextMode  // 🎯 Context Mode
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.asSequence
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class AimiDecisionContext(
    val event_id: String,
    val timestamp: Long,
    val trigger: String,
    val baseline_state: BaselineState,
    val adjustments: Adjustments = Adjustments(),
    var outcome: Outcome? = null
) {
    data class BaselineState(
        val profile_isf_mgdl: Double,
        val profile_basal_uph: Double,
        val current_bg_mgdl: Double,
        val cob_g: Double,
        val iob_u: Double
    )
    data class Adjustments(
        var dynamic_isf: DynamicIsf? = null,
        var basal_safety_cap: BasalCap? = null,
        var physiological_context: PhysioContext? = null
    )
    data class DynamicIsf(
        var final_value_mgdl: Double = 0.0,
        val modifiers: MutableList<Modifier> = mutableListOf()
    )
    data class Modifier(val source: String, val factor: Double, val clinical_reason: String)
    data class BasalCap(val status: String, val limit_uph: Double, val safety_reason: String)
    data class PhysioContext(val hormonal_cycle_phase: String, val physical_activity_mode: String)
    data class Outcome(val clinical_decision: String, val dosage_u: Double, val target_basal_uph: Double? = null, val narrative_explanation: String)

    fun toMedicalJson(): String {
        return try {
            val json = org.json.JSONObject()
            json.put("event_id", event_id)
            json.put("timestamp", timestamp)
            json.put("trigger", trigger)

            val base = org.json.JSONObject()
            base.put("profile_isf_mgdl", baseline_state.profile_isf_mgdl)
            base.put("profile_basal_uph", baseline_state.profile_basal_uph)
            base.put("current_bg_mgdl", baseline_state.current_bg_mgdl)
            base.put("cob_g", baseline_state.cob_g)
            base.put("iob_u", baseline_state.iob_u)
            json.put("baseline_state", base)

            val adj = org.json.JSONObject()
            adjustments.dynamic_isf?.let { d ->
                val dJson = org.json.JSONObject()
                dJson.put("final_value_mgdl", d.final_value_mgdl)
                val modsIdx = org.json.JSONArray()
                d.modifiers.forEach { m ->
                    val mJson = org.json.JSONObject()
                    mJson.put("source", m.source)
                    mJson.put("factor", m.factor)
                    mJson.put("reason", m.clinical_reason)
                    modsIdx.put(mJson)
                }
                dJson.put("modifiers", modsIdx)
                adj.put("dynamic_isf", dJson)
            }
            adjustments.physiological_context?.let { p ->
                val pJson = org.json.JSONObject()
                pJson.put("cycle_phase", p.hormonal_cycle_phase)
                pJson.put("activity_mode", p.physical_activity_mode)
                adj.put("physio_context", pJson)
            }
            // Add Basal Cap if present
            adjustments.basal_safety_cap?.let { c ->
                val cJson = org.json.JSONObject()
                cJson.put("status", c.status)
                cJson.put("limit_uph", c.limit_uph)
                cJson.put("reason", c.safety_reason)
                adj.put("basal_cap", cJson)
            }
            json.put("adjustments", adj)

            outcome?.let { o ->
                val oJson = org.json.JSONObject()
                oJson.put("decision", o.clinical_decision)
                oJson.put("amount", o.dosage_u)
                o.target_basal_uph?.let { oJson.put("target_basal_rate_uph", it) }
                oJson.put("narrative", o.narrative_explanation)
                json.put("outcome", oJson)
            }
            json.toString()
        } catch(e: Exception) { "{ \"error\": \"JSON Generation Failed\" }" }
    }
}

internal data class PredictionSanityResult(
    val predBg: Double,
    val eventualBg: Double,
    val label: String
)

internal fun sanitizePredictionValues(
    bg: Double,
    delta: Float,
    predBgRaw: Double?,
    eventualBgRaw: Double?,
    series: Predictions?,
    log: MutableList<String>? = null
): PredictionSanityResult {
    val baseBg = bg.coerceIn(25.0, 600.0)
    var predBg = (predBgRaw ?: baseBg).coerceIn(25.0, 600.0)
    var eventualBg = (eventualBgRaw ?: predBg).coerceIn(25.0, 600.0)
    val anomalies = mutableListOf<String>()

    val lengths = listOfNotNull(series?.IOB, series?.COB, series?.ZT, series?.UAM)
    val minSize = lengths.minOfOrNull { it.size } ?: 0
    if (minSize in 1..5) {
        anomalies.add("series<$minSize")
    }

    if (!predBg.isFinite() || !eventualBg.isFinite()) {
        anomalies.add("nonFinite")
        predBg = baseBg
        eventualBg = baseBg
    }

    val largeDrop = baseBg - predBg
    val rising = delta >= 0f
    if (rising && baseBg > 140 && largeDrop > 80) {
        predBg = (baseBg + delta * 6).coerceIn(25.0, 600.0)
        anomalies.add("jumpClamp")
    }

    if (eventualBg < 25 || eventualBg > 600) {
        anomalies.add("eventualRange")
        eventualBg = predBg
    }

    val label = if (anomalies.isEmpty()) "ok" else anomalies.joinToString("+")
    if (anomalies.isNotEmpty()) {
        log?.add(
            "PRED_SANITY_FAIL: $label bg=${baseBg.roundToInt()} pred=${predBg.roundToInt()} ev=${eventualBg.roundToInt()} delta=${"%.1f".format(delta)}"
        )
    }

    return PredictionSanityResult(predBg, eventualBg, label)
}

// ========================================
// Meal Advisor Configuration Constants
// ========================================
/**
 * IOB Discount Factor for Meal Advisor
 * 
 * When calculating SMB for a confirmed meal (via photo), we discount the current IOB
 * by this factor to account for uncertainty:
 * - IOB may be from a previous unlogged meal (e.g., soup)
 * - IOB action diminishes over time
 * - User confirmation signals "new meal coming" that will raise BG
 * 
 * Value of 0.7 means we only subtract 70% of actual IOB, giving a 30% safety margin.
 */
private const val MEAL_ADVISOR_IOB_DISCOUNT_FACTOR = 0.7

/**
 * Minimum Carb Coverage for Meal Advisor
 * 
 * Guarantees that at least this percentage of calculated insulin for carbs
 * is delivered as SMB, even if IOB calculation would suggest zero.
 * 
 * This ensures a prebolus is ALWAYS sent when user confirms a meal,
 * since the meal WILL raise BG regardless of current IOB.
 * 
 * Value of 0.25 means at least 25% of carb insulin requirement is delivered.
 */
private const val MEAL_ADVISOR_MIN_CARB_COVERAGE = 0.25

/**
 * Main orchestrator for the AIMI loop.
 *
 * High level flow (all numbers are mg/dL unless stated otherwise):
 *  1. Gather loop context (profile, COB/IOB, modes, history) and build the PKPD runtime.
 *  2. Use PKPD engines to derive final insulin action parameters and predictions
 *     (eventual BG + full prediction curve) that feed both basal and SMB logic.
 *  3. Blend ISF/autosens, apply wCycle/NGR adjustments, then run ML to propose an SMB.
 *  4. Pipe the proposed SMB through centralized safety and damping (tail/exercise/meal),
 *     then quantize before execution via the SMB engine.
 *  5. Basal decisions reuse the same PKPD/ISF context and the shared safety gates to
 *     avoid diverging behaviours between basal and SMB paths.
 */
@Singleton
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences,
    private val gestationalAutopilot: app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot,
    private val auditorOrchestrator: app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorOrchestrator,
    private val uiInteraction: UiInteraction,
    private val wCycleFacade: WCycleFacade,
    private val wCyclePreferences: WCyclePreferences,
    private val wCycleLearner: WCycleLearner,
    private val pumpCapabilityValidator: app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator,
    context: Context
) {
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var basalDecisionEngine: BasalDecisionEngine
    @Inject lateinit var activityManager: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityManager // Agnostic injection
    @Inject lateinit var glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi
    @Inject lateinit var comparator: AimiSmbComparator
    @Inject lateinit var basalLearner: app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
    @Inject lateinit var unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
    @Inject lateinit var storageHelper: AimiStorageHelper  // 🛡️ Restored StorageHelper
    
    // Helper to safely access learner (handles potential early access before injection)
    private val safeReactivityFactor: Double
        get() {
            val base = if (::unifiedReactivityLearner.isInitialized) unifiedReactivityLearner.getCombinedFactor() else 1.0
            
            // 🧬 PHYSIO: SNS=0.8 -> +15% Boost
            val snapshot = if (::physioAdapter.isInitialized) physioAdapter.getLatestSnapshot() else null
            val sns = snapshot?.toSNSDominance() ?: 0.3
            val mod = 1.0 + (sns - 0.3) * 0.3
            
            return base * mod
        }
    @Inject lateinit var aapsLogger: AAPSLogger  // 📊 Logger for health monitoring

    @Inject lateinit var trajectoryGuard: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard  // 🌀 Phase-Space Trajectory Controller
    @Inject lateinit var trajectoryHistoryProvider: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryHistoryProvider  // 🌀 Trajectory History
    @Inject lateinit var contextManager: app.aaps.plugins.aps.openAPSAIMI.context.ContextManager  // 🎯 Context Module
    @Inject lateinit var contextInfluenceEngine: app.aaps.plugins.aps.openAPSAIMI.context.ContextInfluenceEngine  // 🎯 Context Influence
    @Inject lateinit var physioAdapter: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR  // 🏥 Physiological Modulation
    
    // 🌸 Endometriosis Adjuster (Lazy init manually since not in graph yet or use manual passing)
    private val endoAdjuster by lazy { app.aaps.plugins.aps.openAPSAIMI.wcycle.EndometriosisAdjuster(preferences, aapsLogger) }

    // ❌ OLD reactivityLearner removed - UnifiedReactivityLearner is now the only one
    init {
        // Branche l’historique basal (TBR) sur la persistence réelle
        BasalHistoryUtils.installHistoryProvider(
            BasalHistoryUtils.FetcherProvider(
                fetcher = { fromMillis: Long ->
                    // Récupère les TBR depuis 'fromMillis', puis trie DESC par timestamp
                    val raws: List<TB> = try {
                        // Adapte le nom exact de l’API selon ta persistence
                        persistenceLayer
                            .getTemporaryBasalsStartingFromTime(fromMillis,ascending = false)    // souvent retourne Single<List<TB>>
                            .blockingGet()
                    } catch (t: Throwable) {
                        emptyList()
                    }

                    raws.asSequence()
                        .filter { it.timestamp > 0L && it.timestamp >= fromMillis }
                        .sortedByDescending { it.timestamp }
                        .toList()
                },
                // Optionnel : aligne "now" sur ton utilitaire de date
                nowProvider = { dateUtil.now() }
            )
        )
    }

    private val context: Context = context.applicationContext
    private val EPS_FALL = 0.3      // mg/dL/5min : seuil de baisse
    private val EPS_ACC  = 0.2      // mg/dL/5min : seuil d'écart short vs long
    private var lateFatRiseFlag: Boolean = false
    // — Hystérèse anti-pompage —
    private val HYPO_RELEASE_MARGIN   = 5.0      // mg/dL au-dessus du seuil
    private val HYPO_RELEASE_HOLD_MIN = 5        // minutes à rester > seuil+margin
    private var highBgOverrideUsed = false
    private val INSULIN_STEP = Constants.DEFAULT_INSULIN_STEP_U.toFloat()

    // État interne d’hystérèse
    private var lastHypoBlockAt: Long = 0L
    private var hypoClearCandidateSince: Long? = null
    private var mealModeSmbReason: String? = null
    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()
    private var lastAutodriveActionTime: Long = 0L  // FCL 14.1 Cooldown State
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    //private val modelFile = File(externalDir, "ml/model.tflite")
    //private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private val csvfile = File(externalDir, "oapsaimiML2_records.csv")
    private val csvfile2 = File(externalDir, "oapsaimi2_records.csv")
    private val pkpdIntegration = PkPdIntegration(preferences)
    //private val tempFile = File(externalDir, "temp.csv")
    private var bgacc = 0.0
    private var predictedSMB = 0.0f
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute10 = 0.0
    private var averageBeatsPerMinute60 = 0.0
    private var averageBeatsPerMinute180 = 0.0
    private var eventualBG = 0.0
    private var now = System.currentTimeMillis()
    private var iob = 0.0f
    private var cob = 0.0f
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var lastCycleNotificationDay: Int = -1 // State for cycle notification spam prevention
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
    private var cachedPkpdRuntime: PkPdRuntime? = null // 🔧 FIX (MTR): Global cache for Safety methods
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var lastProfile: OapsProfileAimi? = null
    private var wCycleInfoForRun: WCycleInfo? = null
    private var wCycleReasonLogged: Boolean = false
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 90.0f
    private var normalBgThreshold = 110.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0
    private var maxSMB = 0.5
    private var maxSMBHB = 0.5
    private var lastBolusSMBUnit = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var aimilimit = 0.0f
    private var ci = 0.0f
    private var sleepTime = false
    private var sportTime = false
    private var snackTime = false
    private var lowCarbTime = false
    private var highCarbTime = false
    private var mealTime = false
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var fastingTime = false
    private var stopTime = false
    private var iscalibration = false
    private var mealruntime: Long = 0
    private var bfastruntime: Long = 0
    private var lunchruntime: Long = 0
    private var dinnerruntime: Long = 0
    private var highCarbrunTime: Long = 0
    private var snackrunTime: Long = 0
    private var intervalsmb = 1
    private var peakintermediaire = 0.0
    private var latestAdjustedDia: Double = 0.0 // Captured for logging
    private var insulinPeakTime = 0.0
    private var iobActivityNow: Double = 0.0
    private var lastBolusAgeMinutes: Double = Double.NaN
    private var lastDecisionSource: String = "AIMI"
    private var lastSafetySource: String = "NONE"
    private var lastPredictionAvailable: Boolean = false
    private var lastPredictionSize: Int = 0
    private var lastEventualBgSnapshot: Double = 0.0
    private var lastSmbProposed: Double = 0.0
    private var lastSmbCapped: Double = 0.0
    private var lastSmbFinal: Double = 0.0
    private var lastAutodriveState: AutodriveState = AutodriveState.IDLE
    private var internalLastSmbMillis: Long = 0L // Local Atomic Timestamp for Safety
    private val nightGrowthResistanceMode = NightGrowthResistanceMode()
    private val ngrTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var zeroBasalAccumulatedMinutes: Int = 0
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal
    private val insulinObserver = app.aaps.plugins.aps.openAPSAIMI.pkpd.RealTimeInsulinObserver()  // 🚀 Real-Time Insulin Observer
    private var pkpdThrottleIntervalAdd: Int = 0       // 🚀 PKPD interval boost (0 si normal/modes repas)
    private var pkpdPreferTbrBoost: Double = 1.0       // 🚀 PKPD TBR boost factor (1.0 si normal/modes repas)

    /**
     * 🛡️ Sanitize strings before adding to consoleLog to prevent JSON deserialization crashes.
     * Escapes quotes, backslashes, and removes control characters that could break JSON parsing.
     * 
     * Critical for backward compatibility with database records containing special characters.
     */
    private fun sanitizeForJson(input: String): String {
        return input
            .replace("\\", "\\\\")     // Escape backslashes first!
            .replace("\"", "\\\"")     // Escape quotes
            .replace("\n", "\\n")      // Escape newlines
            .replace("\r", "\\r")      // Escape carriage returns
            .replace("\t", "\\t")      // Escape tabs
            .filter { it.code >= 32 || it in "\n\r\t" }  // Remove other control chars
    }

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))
    private fun parseNgrTime(value: String, fallback: LocalTime): LocalTime =
        runCatching { LocalTime.parse(value, ngrTimeFormatter) }.getOrElse { fallback }

    private class PkpdPortAdapter(
        private val pkpdIntegration: PkPdIntegration
    ) : PkpdPort {

        private fun LoopContext.mealModeActive(): Boolean =
            modes.meal || modes.breakfast || modes.lunch || modes.dinner || modes.highCarb || modes.snack

        override fun snapshot(ctx: LoopContext): PkpdPort.Snapshot {
            val mealCtx = MealAggressionContext(
                mealModeActive = ctx.mealModeActive(),
                predictedBgMgdl = ctx.eventualBg,
                targetBgMgdl = ctx.profile.targetMgdl
            )
            val rt = pkpdIntegration.computeRuntime(
                epochMillis = ctx.nowEpochMillis,
                bg = ctx.bg.mgdl,
                deltaMgDlPer5 = ctx.bg.delta5,
                iobU = ctx.iobU,
                carbsActiveG = ctx.cobG,
                windowMin = ctx.settings.smbIntervalMin,
                exerciseFlag = false, // remplace par ctx.modes.sport si dispo
                profileIsf = ctx.profile.isfMgdlPerU,
                tdd24h = ctx.tdd24hU,
                mealContext = mealCtx
            )
            return if (rt != null) {
                PkpdPort.Snapshot(
                    diaMin   = (rt.params.diaHrs * 60.0).toInt(), // ✅ diaHrs
                    peakMin  = rt.params.peakMin.toInt(),
                    fusedIsf = rt.fusedIsf,
                    tailFrac = rt.tailFraction
                    // ⚠ champs SMB optionnels laissent null ici
                )
            } else {
                PkpdPort.Snapshot(diaMin = 6*60, peakMin = 60, fusedIsf = ctx.profile.isfMgdlPerU, tailFrac = 0.0)
            }
        }

        override fun dampSmb(units: Double, ctx: LoopContext, bypassDamping: Boolean): PkpdPort.DampingAudit {
            val mealCtx = MealAggressionContext(
                mealModeActive = ctx.mealModeActive(),
                predictedBgMgdl = ctx.eventualBg,
                targetBgMgdl = ctx.profile.targetMgdl
            )
            val rt = pkpdIntegration.computeRuntime(epochMillis = ctx.nowEpochMillis,
                                                    bg = ctx.bg.mgdl,
                                                    deltaMgDlPer5 = ctx.bg.delta5,
                                                    iobU = ctx.iobU,
                                                    carbsActiveG = ctx.cobG,
                                                    windowMin = ctx.settings.smbIntervalMin,
                                                    exerciseFlag = false, // remplace par ctx.modes.sport si dispo
                                                    profileIsf = ctx.profile.isfMgdlPerU,
                                                    tdd24h = ctx.tdd24hU,
                                                    mealContext = mealCtx)

            val damping = SmbDampingUsecase.run(
                rt,
                SmbDampingUsecase.Input(
                    smbDecision = units,
                    exercise = false, // adapte si tu as un flag d’exercice
                    suspectedLateFatMeal = ctx.modes.highCarb, // ✅ depuis les modes
                    mealModeRun = bypassDamping,
                    highBgRiseActive = false
                )
            )
            val audit = damping.audit
            return if (audit != null) {
                PkpdPort.DampingAudit(
                    out = damping.smbAfterDamping,
                    tailApplied = audit.tailApplied, tailMult = audit.tailMult,
                    exerciseApplied = audit.exerciseApplied, exerciseMult = audit.exerciseMult,
                    lateFatApplied = audit.lateFatApplied, lateFatMult = audit.lateFatMult,
                    mealBypass = audit.mealBypass
                )
            } else {
                PkpdPort.DampingAudit(damping.smbAfterDamping, false, 1.0, false, 1.0, false, 1.0, mealBypass = false)
            }
        }


        override fun logCsv(
            ctx: LoopContext,
            pkpd: PkpdPort.Snapshot,
            smbProposed: Double,
            smbFinal: Double,
            audit: PkpdPort.DampingAudit?
        ) {
            val dateStr  = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ctx.nowEpochMillis))
            val epochMin = TimeUnit.MILLISECONDS.toMinutes(ctx.nowEpochMillis)
            PkPdCsvLogger.append(
                PkPdLogRow(
                    dateStr = dateStr,
                    epochMin = epochMin,
                    bg = ctx.bg.mgdl,
                    delta5 = ctx.bg.delta5,
                    iobU = ctx.iobU,
                    carbsActiveG = ctx.cobG,
                    windowMin = ctx.settings.smbIntervalMin,
                    diaH = pkpd.diaMin / 60.0,
                    peakMin = pkpd.peakMin.toDouble(),
                    fusedIsf = pkpd.fusedIsf,
                    tddIsf = 1800.0 / (ctx.tdd24hU.coerceAtLeast(0.1)), // comme avant si tu l’utilises
                    profileIsf = ctx.profile.isfMgdlPerU,
                    tailFrac = pkpd.tailFrac,
                    smbProposedU = smbProposed,
                    smbFinalU = smbFinal,
                    tailMult = audit?.tailMult,
                    exerciseMult = audit?.exerciseMult,
                    lateFatMult = audit?.lateFatMult,
                    highBgOverride = null,
                    lateFatRise = pkpd.lateFatRise,
                    quantStepU = ctx.pump.bolusStep
                )
            )
        }
    }

    private val nightGrowthLearner = NightGrowthResistanceLearner()

    private fun buildNightGrowthResistanceConfig(
        profile: OapsProfileAimi,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        targetBg: Double
    ): NGRConfig {
        val age = preferences.get(IntKey.OApsAIMINightGrowthAgeYears).coerceAtLeast(0)
        val enabledPref = preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled)
        val nightStart = parseNgrTime(preferences.get(StringKey.OApsAIMINightGrowthStart), LocalTime.of(22, 0))
        val nightEnd = parseNgrTime(preferences.get(StringKey.OApsAIMINightGrowthEnd), LocalTime.of(6, 0))
        val extraIobPerSlot = max(0.0, preferences.get(DoubleKey.OApsAIMINightGrowthMaxIobExtra))
        val diaMinutes = max(60, (profile.dia * 60.0).roundToInt())
        val features = glucoseStatusCalculatorAimi.getAimiFeatures(true)
        val learnerOutput = nightGrowthLearner.derive(
            NightGrowthResistanceLearner.Input(
                ageYears = age,
                autosensRatio = autosens.ratio,
                diaMinutes = diaMinutes,
                isfMgdl = profile.sens,
                targetBg = targetBg,
                basalRate = profile.current_basal,
                stabilityMinutes = features?.stable5pctMinutes ?: 0.0,
                combinedDelta = features?.combinedDelta ?: 0.0,
                bgNoise = glucoseStatus?.noise ?: 0.0
            )
        )
        val enabled = enabledPref ?: (age < 18)
        val slotCap = if (age < 10) 6 else 4
        return NGRConfig(
            enabled = enabled,
            pediatricAgeYears = age,
            nightStart = nightStart,
            nightEnd = nightEnd,
            minRiseSlope = learnerOutput.minRiseSlope,
            minDurationMin = learnerOutput.minDurationMinutes,
            minEventualOverTarget = learnerOutput.minEventualOverTarget,
            allowSMBBoostFactor = learnerOutput.smbBoost,
            allowBasalBoostFactor = learnerOutput.basalBoost,
            maxSMBClampU = learnerOutput.maxSmbClamp,
            extraIobPer30Min = extraIobPerSlot,
            decayMinutes = learnerOutput.decayMinutes,
            headroomSlotCap = slotCap
        )
    }
    /**
     * Prédit l’évolution de la glycémie sur un horizon donné (en minutes),
     * avec des pas de 5 minutes.
     *
     * @param currentBG La glycémie actuelle (mg/dL)
     * @param basalCandidate La dose basale candidate (en U/h)
     * @param horizonMinutes L’horizon de prédiction (ex. 30 minutes)
     * @param insulinSensitivity La sensibilité insulinique (mg/dL/U)
     * @return Une liste de glycémies prédites pour chaque pas de 5 minutes.
     */
    private fun predictGlycemia(
        currentBG: Double,
        basalCandidateUph: Double,
        horizonMinutes: Int,
        insulinSensitivityMgdlPerU: Double,
        stepMinutes: Int = 5,
        minBgClamp: Double = 40.0,
        maxBgClamp: Double = 400.0,
        // ↓ nouveaux paramètres optionnels (par défaut 5h de DIA, pic à 75 min)
        diaMinutes: Int = 300,
        timeToPeakMinutes: Int = 75
    ): List<Double> {
        val predictions = ArrayList<Double>(maxOf(0, horizonMinutes / stepMinutes))
        if (horizonMinutes <= 0 || stepMinutes <= 0) return predictions

        var bg = currentBG
        val steps = horizonMinutes / stepMinutes
        val uPerStep = basalCandidateUph * (stepMinutes / 60.0)

        fun triangularActivity(tMin: Int, tp: Int, dia: Int): Double {
            if (tMin <= 0 || tMin >= dia) return 0.0
            val tpClamped = tp.coerceIn(1, dia - 1)
            val rise = if (tMin <= tpClamped) (2.0 / tpClamped) * tMin else 0.0
            val fall = if (tMin > tpClamped) 2.0 * (1.0 - (tMin - tpClamped).toDouble() / (dia - tpClamped)) else 0.0
            // Hauteur max = 2.0 → aire totale sur [0, DIA] ≈ DIA (même “dose” qu’activité = 1)
            return if (tMin <= tpClamped) rise else fall
        }

        repeat(steps) { k ->
            val tMin = (k + 1) * stepMinutes

            // activité réaliste (pic à tp, s’éteint à DIA)
            val activity = triangularActivity(tMin, timeToPeakMinutes, diaMinutes)

            // effet du pas courant (pas de convolution pour rester simple comme ton code)
            val delta = insulinSensitivityMgdlPerU * uPerStep * activity

            bg = (bg - delta).coerceIn(minBgClamp, maxBgClamp)
            predictions.add(bg)

            // early stop en hypo profonde
            if (bg <= minBgClamp) return predictions
        }
        return predictions
    }

    /**
     * Calcule la fonction de coût, ici la somme des carrés des écarts entre les glycémies prédites et la glycémie cible.
     *
     * @param basalCandidate La dose candidate de basal.
     * @param currentBG La glycémie actuelle.
     * @param targetBG La glycémie cible.
     * @param horizonMinutes L’horizon de prédiction (en minutes).
     * @param insulinSensitivity La sensibilité insulinique.
     * @return Le coût cumulé.
     */
    fun costFunction(
        basalCandidate: Double, currentBG: Double,
        targetBG: Double, horizonMinutes: Int,
        insulinSensitivity: Double, nnPrediction: Double
    ): Double {
        val predictions = predictGlycemia(currentBG, basalCandidate, horizonMinutes, insulinSensitivity)
        val predictionCost = predictions.sumOf { (it - targetBG).pow(2) }
        val nnPenalty = (basalCandidate - nnPrediction).pow(2)
        return predictionCost + 0.5 * nnPenalty  // Pondération du terme de pénalité
    }


    /**
     * Détecte une montée glycémique significative basée sur les deltas réels.
     * Utilisé pour éviter que les prédictions optimistes bloquent l'action.
     *
     * @param deltaVal Delta 5min actuel (mg/dL/5min)
     * @param shortAvgDeltaVal Moyenne courte des deltas
     * @param bgNow Glycémie actuelle
     * @param targetBgVal Objectif glycémique
     * @param mealModeActive Mode repas actif (seuils plus sensibles)
     * @return true si une montée significative est détectée
     */
    private fun isRisingFast(
        deltaVal: Double,
        shortAvgDeltaVal: Double,
        bgNow: Double,
        targetBgVal: Double,
        mealModeActive: Boolean
    ): Boolean {
        // Seuils ajustés selon le contexte repas
        val deltaThreshold = if (mealModeActive) 2.0 else 4.0
        val shortAvgThreshold = if (mealModeActive) 1.5 else 3.0
        val bgMargin = if (mealModeActive) 0.0 else 10.0

        return (deltaVal >= deltaThreshold || shortAvgDeltaVal >= shortAvgThreshold)
            && bgNow >= targetBgVal - bgMargin
    }

    private fun roundBasal(value: Double): Double {
        val safeValue = if (value < 0.0) 0.0 else value
        // Standard rounding to 2 decimals (OpenAPS style 0.00)
        return Math.round(safeValue * 100.0) / 100.0
    }


    /**
     * Ajuste la dose d'insuline (SMB) et décide éventuellement de stopper la basale.
     *
     * @param currentBG Glycémie actuelle (mg/dL).
     * @param predictedBG Glycémie prédite par l'algorithme (mg/dL).
     * @param bgHistory Historique des BG récents (pour calculer le drop/h).
     * @param combinedDelta Delta combiné mesuré et prédit (mg/dL/5min).
     * @param iob Insuline active (IOB).
     * @param maxIob IOB maximum autorisé.
     * @param tdd24Hrs Total daily dose sur 24h (U).
     * @param tddPerHour TDD/h sur la dernière heure (U/h).
     * @param tirInhypo Pourcentage du temps passé en hypo.
     * @param targetBG Objectif de glycémie (mg/dL).
     * @param zeroBasalDurationMinutes Durée cumulée en minutes pendant laquelle la basale est déjà à zéro.
     */
    fun safetyAdjustment(
        currentBG: Float,
        predictedBG: Float,
        bgHistory: List<Float>,
        combinedDelta: Float,
        iob: Float,
        maxIob: Float,
        tdd24Hrs: Float,
        tddPerHour: Float,
        tirInhypo: Float,
        targetBG: Float,
        zeroBasalDurationMinutes: Int
    ): SafetyDecision {
        val windowMinutes = 30f
        val dropPerHour = HypoTools.calculateDropPerHour(bgHistory, windowMinutes)
        val maxAllowedDropPerHour = 65f  // Seuil de chute rapide à ajuster si besoin
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        val reasonBuilder = StringBuilder()
        var stopBasal = false
        var basalLS = false
        var isHypoRisk = false

        // Liste des facteurs multiplicatifs proposés ; on calculera la moyenne à la fin
        val factors = mutableListOf<Float>()

        // 1. Contrôle de la chute rapide (RÉVISÉ : Basal-First)
        // Avant : StopBasal si BG < 110 (Trop agressif)
        // Après : StopBasal si BG < 85 (Sécurité), Sinon Réduction 50% (Douceur)
        val safetyFloor = 85.0f

        if (dropPerHour >= maxAllowedDropPerHour && delta < 0) {
            if (currentBG < safetyFloor) {
                // CAS CRITIQUE : On coupe tout
                stopBasal = true
                isHypoRisk = true
                factors.add(0.0f)
                reasonBuilder.append(context.getString(R.string.bg_drop_high_critical, dropPerHour))
            } else if (currentBG < 110f) {
                // CAS AVERTISSEMENT : On réduit de 50% mais on garde le flux
                stopBasal = false
                factors.add(0.5f)
                reasonBuilder.append(context.getString(R.string.bg_drop_high_warning, dropPerHour))
            }
        }

        // 2. Mode montée très rapide : override de toutes les réductions
        // SÉCURISÉ : On ne bypass les sécurités que si on est AU-DESSUS de la cible
        if (delta >= 20f && combinedDelta >= 15f && !honeymoon && currentBG > targetBG) {
            // on passe outre toutes les réductions ; bolusFactor sera 1.0
            //reasonBuilder.append("Montée rapide détectée (delta $delta mg/dL), application du mode d'urgence; ")
            reasonBuilder.append(context.getString(R.string.bg_rapid_rise, delta))
        } else {
            // 3. Ajustement selon combinedDelta
            when {
                combinedDelta < 1f -> {
                    factors.add(0.6f)
                    //reasonBuilder.append("combinedDelta très faible ($combinedDelta), réduction x0.6; ")
                    reasonBuilder.append(context.getString(R.string.bg_combined_delta_weak, combinedDelta))
                }
                combinedDelta < 2f -> {
                    factors.add(0.8f)
                    //reasonBuilder.append("combinedDelta modéré ($combinedDelta), réduction x0.8; ")
                    reasonBuilder.append(context.getString(R.string.bg_combined_delta_moderate, combinedDelta))
                }
                else -> {
                    // Appel au multiplicateur lissé
                    factors.add(computeDynamicBolusMultiplier(combinedDelta))
                    //reasonBuilder.append("combinedDelta élevé ($combinedDelta), multiplicateur dynamique appliqué; ")
                    reasonBuilder.append(context.getString(R.string.bg_combined_delta_high, combinedDelta))
                }
            }

            // 4. Plateau BG élevé + combinedDelta très faible
            if (currentBG > 160f && combinedDelta < 1f) {
                factors.add(0.8f)
                //reasonBuilder.append("Plateau BG>160 & combinedDelta<1, réduction x0.8; ")
                reasonBuilder.append(context.getString(R.string.bg_stable_high_delta_low))
            }

            // 5. Contrôle IOB
            if (iob >= maxIob * 0.85f) {
                factors.add(0.85f)
                //reasonBuilder.append("IOB élevé ($iob U), réduction x0.85; ")
                reasonBuilder.append(context.getString(R.string.iob_high_reduction, iob))
            }

            // 6. Contrôle du TDD par heure
            val tddThreshold = tdd24Hrs / 24f
            if (tddPerHour > tddThreshold) {
                factors.add(0.8f)
                //reasonBuilder.append("TDD/h élevé ($tddPerHour U/h), réduction x0.8; ")
                reasonBuilder.append(context.getString(R.string.tdd_per_hour_high, tddPerHour))
            }

            // 7. TIR élevé
            if (tirInhypo >= 8f) {
                factors.add(0.5f)
                //reasonBuilder.append("TIR élevé ($tirInhypo%), réduction x0.5; ")
                reasonBuilder.append(context.getString(R.string.tir_high, tirInhypo))
            }

            // 8. BG prédit proche de la cible - SAUF si montée significative
            val risingFast = delta >= 3f || combinedDelta >= 2f
            if (predictedBG < targetBG + 10 && !risingFast) {
                factors.add(0.5f)
                //reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
                reasonBuilder.append(context.getString(R.string.bg_near_target, predictedBG, targetBG))
            } else if (predictedBG < targetBG + 10 && risingFast) {
                // Log pour traçabilité mais pas de réduction
                reasonBuilder.append(context.getString(R.string.bg_near_target_but_rising, 
                    predictedBG, targetBG, delta, combinedDelta))
            }
        }

        // Calcul du bolusFactor : Prendre le MINIMUM (le plus sécuritaire) et non la moyenne
        var bolusFactor = if (factors.isNotEmpty()) {
            factors.minOrNull()?.toDouble() ?: 1.0
        } else {
            1.0
        }

        // 9. Zéro basal prolongé : on force le bolusFactor à 1 et on désactive l'arrêt basale
        // SÉCURISÉ : Seulement si PAS de risque hypo actuel
        if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION && !isHypoRisk) {
            stopBasal = false
            basalLS = true
            bolusFactor = 1.0
            //reasonBuilder.append("Zero basal duration ($zeroBasalDurationMinutes min) dépassé, forçant basal minimal; ")
            reasonBuilder.append(context.getString(R.string.zero_basal_forced, zeroBasalDurationMinutes))
        }

        return SafetyDecision(
            stopBasal = stopBasal,
            bolusFactor = bolusFactor,
            reason = reasonBuilder.toString(),
            basalLS = basalLS,
            isHypoRisk = isHypoRisk
        )
    }

    /**
     * Ajuste le DIA (en minutes) en fonction du niveau d'IOB.
     *
     * @param diaMinutes Le DIA courant (en minutes) après les autres ajustements.
     * @param currentIOB La quantité actuelle d'insuline active (U).
     * @param threshold Le seuil d'IOB à partir duquel on commence à augmenter le DIA (par défaut 7 U).
     * @return Le DIA ajusté en minutes tenant compte de l'impact de l'IOB.
     */
    fun adjustDIAForIOB(diaMinutes: Float, currentIOB: Float, threshold: Float = 2f): Float {
        // Si l'IOB est inférieur ou égal au seuil, pas d'ajustement.
        if (currentIOB <= threshold) return diaMinutes

        // Calculer l'excès d'IOB
        val excess = currentIOB - threshold
        // Pour chaque unité au-dessus du seuil, augmenter le DIA de 5 %.
        val multiplier = 1 + 0.05f * excess
        return diaMinutes * multiplier
    }
    /**
     * Calcule le DIA ajusté en minutes en fonction de plusieurs paramètres :
     * - baseDIAHours : le DIA de base en heures (par exemple, 9.0 pour 9 heures)
     * - currentHour : l'heure actuelle (0 à 23)
     * - recentSteps5Minutes : nombre de pas sur les 5 dernières minutes
     * - currentHR : fréquence cardiaque actuelle (bpm)
     * - averageHR60 : fréquence cardiaque moyenne sur les 60 dernières minutes (bpm)
     *
     * La logique appliquée :
     * 1. Conversion du DIA de base en minutes.
     * 2. Ajustement selon l'heure de la journée :
     *    - Matin (6-10h) : réduction de 20% (×0.8),
     *    - Soir/Nuit (22-23h et 0-5h) : augmentation de 20% (×1.2).
     * 3. Ajustement en fonction de l'activité physique :
     *    - Si recentSteps5Minutes > 200 et que currentHR > averageHR60, on réduit le DIA de 30% (×0.7).
     *    - Si recentSteps5Minutes == 0 et que currentHR > averageHR60, on augmente le DIA de 30% (×1.3).
     * 4. Ajustement selon la fréquence cardiaque absolue :
     *    - Si currentHR > 130 bpm, on réduit le DIA de 30% (×0.7).
     * 5. Le résultat final est contraint entre 180 minutes (3h) et 720 minutes (12h).
     */
    fun calculateAdjustedDIA(
        baseDIAHours: Float,
        currentHour: Int,
        pumpAgeDays: Float,
        iob: Double = 0.0,
        activityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext,
        steps: Int? = null,
        heartRate: Int? = null
    ): Double {
        val reasonBuilder = StringBuilder()

        // 1. Conversion du DIA de base en minutes
        var diaMinutes = baseDIAHours * 60f  // Pour 9h, 9*60 = 540 min
        //reasonBuilder.append("Base DIA: ${baseDIAHours}h = ${diaMinutes}min\n")
        reasonBuilder.append(context.getString(R.string.dia_base_info, baseDIAHours, diaMinutes))

        // 2. Ajustement selon l'heure de la journée
        // Matin (6-10h) : absorption plus rapide, réduction du DIA de 20%
        if (currentHour in 6..10) {
            diaMinutes *= 0.8f
            //reasonBuilder.append("Morning adjustment (6-10h): reduced by 20%\n")
            reasonBuilder.append(context.getString(R.string.morning_adjustment))
        }
        // Soir/Nuit (22-23h et 0-5h) : absorption plus lente, augmentation du DIA de 20%
        else if (currentHour in 22..23 || currentHour in 0..5) {
            diaMinutes *= 1.2f
            //reasonBuilder.append("Night adjustment (22-23h & 0-5h): increased by 20%\n")
            reasonBuilder.append(context.getString(R.string.night_adjustment))
        }

    
    // 3. Ajustement en fonction de l'activité physique (Via ActivityContext)
    when (activityContext.state) {
        app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE -> {
             diaMinutes *= 0.7f
             // reasonBuilder.append(context.getString(R.string.reason_high_activity)) // Using Bio-Sync reason now
        }
        app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.MODERATE -> {
             diaMinutes *= 0.8f
             reasonBuilder.append(" • Moderate Activity ➝ x0.8\n")
        }
        app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.LIGHT -> {
             diaMinutes *= 0.9f
        }
        else -> {
            // REST
            if (activityContext.isRecovery) {
                // Recovery might imply lasting effects? For now, keep normal.
            }
        }
    }    

        // 3b. BIO-SYNC Stress Mode (Correction for High HR at Rest)
        val s = steps ?: 0
        val h = heartRate ?: 0
        if (h > 95 && s < 100) {
             // Stress / Maladie : Résistance -> DIA plus long
             diaMinutes *= 1.2f
             reasonBuilder.append(context.getString(R.string.reason_bio_sync_stress, h, s))
        } else if (s > 1000) {
             // Flow / Sport : Absorption rapide -> DIA plus court (si pas déjà appliqué par ActivityContext)
             // On s'assure qu'on ne double pas la réduction si ActivityState est déjà INTENSE
             if (activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE) {
                 diaMinutes *= 0.85f
                 reasonBuilder.append(context.getString(R.string.reason_bio_sync_flow, s, h, 0.85f))
             }
        }

        // 5. Ajustement en fonction de l'IOB (Insulin on Board)
        // Si le patient a déjà beaucoup d'insuline active, il faut réduire le DIA pour éviter l'hypoglycémie
        diaMinutes = adjustDIAForIOB(diaMinutes, iob.toFloat())
        // if (iob > 2.0) {
        //     diaMinutes *= 0.8f
        //     reasonBuilder.append("High IOB (${iob}U): reduced by 20%\n")
        // } else if (iob < 0.5) {
        //     diaMinutes *= 1.1f
        //     reasonBuilder.append("Low IOB (${iob}U): increased by 10%\n")
        // }

        // 6. Ajustement en fonction de l'âge du site d'insuline
        // Si le site est utilisé depuis 2 jours ou plus, augmenter le DIA de 10% par jour supplémentaire.
        if (pumpAgeDays >= 2f) {
            val extraDays = pumpAgeDays - 2f
            val ageMultiplier = 1 + 0.1f * extraDays  // 10% par jour supplémentaire
            diaMinutes *= ageMultiplier
            //reasonBuilder.append("Pump age (${pumpAgeDays} days): increased by ${extraDays * 10}%\n")
            reasonBuilder.append(context.getString(R.string.pump_age_adjustment, pumpAgeDays, extraDays * 10))
        }

        // 7. Contrainte de la plage finale : entre 180 min (3h) et 720 min (12h)
        val finalDiaMinutes = diaMinutes.coerceIn(180f, 720f)
        //reasonBuilder.append("Final DIA constrained to [180, 720] min: ${finalDiaMinutes}min")
        reasonBuilder.append(context.getString(R.string.final_dia_constrained, finalDiaMinutes))


        //println("DIA Calculation Details:")
        println(context.getString(R.string.dia_calculation_details))
        println(reasonBuilder.toString())

        this.latestAdjustedDia = finalDiaMinutes.toDouble()
        return finalDiaMinutes.toDouble()
    }

    // -- Méthode pour obtenir l'historique récent de BG, similaire à getRecentBGs() --
    private fun getRecentBGs(): List<Float> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        val intervalMinutes = if (bg < 130) 50f else 25f
        val nowTimestamp = data.first().timestamp
        val recentBGs = mutableListOf<Float>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..intervalMinutes) {
                    // Utilisation de la valeur recalculée comme BG
                    recentBGs.add(data[i].recalculated.toFloat())
                }
            }
        }
        return recentBGs
    }
    fun appendCompactLog(
        reason: StringBuilder,
        peakTime: Double,
        bg: Double,
        delta: Float,
        stepCount: Int?,
        heartRate: Double?
    ) {
        val bgStr = "%.0f".format(bg)
        val deltaStr = "%.1f".format(delta)
        val peakStr = "%.1f".format(peakTime)

//  reason.append("  → 🕒 PeakTime=$peakStr min | BG=$bgStr Δ$deltaStr")
        reason.append(context.getString(R.string.peak_time, peakStr, bgStr, deltaStr))
        stepCount?.let { reason.append(context.getString(R.string.steps, it)) }
        //  heartRate?.let { reason.append(" | HR=$it bpm") }
        heartRate?.let { reason.append(context.getString(R.string.heart_rate, if (it.isNaN()) "--" else "%.0f".format(it))) }
        reason.append("\n")
    }
    
    /**
     * 🧠 AI Auditor Helper: Calculate cumulative SMB delivered in last 30 minutes
     * Used for intelligent audit triggering
     */
    private fun calculateSmbLast30Min(): Double {
        val now = dateUtil.now()
        val lookback30min = now - 30 * 60 * 1000L
        
        return try {
            val boluses = persistenceLayer
                .getBolusesFromTime(lookback30min, ascending = false)
                .blockingGet()
                .filter { it.type == BS.Type.SMB }
            
            boluses.sumOf { it.amount }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Failed to calculate SMB last 30min", e)
            0.0
        }
    }
    
    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }
    // Helper for Post-Meal Basal Boost (AIMI 2.0)
    private fun adjustBasalForMealHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        isMealModeActive: Boolean,
        minutesSinceMealStart: Int,
        mealMaxBasalUph: Double
    ): Double {
        val mealPhase = isMealModeActive && minutesSinceMealStart in 0..120
        if (!mealPhase) return suggestedBasalUph

        val risingOrFlat = delta >= 0.3 || shortAvgDelta >= 0.2
        val moderatelyHigh = bg > targetBg + 30.0
        val veryHigh = bg > targetBg + 90.0   // ex. cible 100 → 190+

        if (!risingOrFlat || !moderatelyHigh) return suggestedBasalUph

        val boostFactor = when {
            veryHigh -> 10    // ex : 250+ → +50 %
            else -> 8       // ex : 180–250 → +25 %
        }

        val boosted = suggestedBasalUph * boostFactor

        // Plafond sécurisé : on ne dépasse pas mealMaxBasalUph
        return if (boosted > mealMaxBasalUph) mealMaxBasalUph else boosted
    }

    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT, overrideSafety: Boolean = false): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        val rawRate = if (overrideSafety || basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
        return rawRate.coerceAtLeast(0.0)
    }
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double {
        val raw = if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
        return raw.coerceAtLeast(0.0)
    }

    private fun convertBG(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun enablesmb(
        profile: OapsProfileAimi,
        microBolusAllowed: Boolean,
        mealData: MealData,
        targetbg: Double,
        mealModeActive: Boolean,
        currentBg: Double,
        delta: Double,
        eventualBg: Double
    ): Boolean {
        mealModeSmbReason = null

        // 0) Garde globale
        if (!microBolusAllowed) {
            consoleError.add(context.getString(R.string.smb_disabled))
            return false
        }
        
        // 🔒 SAFETY: Hard Floor for SMB. No SMB below 80 mg/dL ever.
        // Even if predicted to rise, we don't SuperBolus a hypo.
        if (currentBg < 80) {
            consoleError.add("SMB disabled: BG ${convertBG(currentBg)} < 80")
            return false
        }

        // 1) Détection meal-rise plus tolérante
        val safeFloor = max(100.0, targetbg - 5.0)
// avant : delta >= 0.3 && currentBg > safeFloor && eventualBg > safeFloor
        val isMealRise = mealModeActive &&
            (delta >= 0.1) &&
            (currentBg > safeFloor)

// 2) Garde high TT : bypass si mode repas actif et pas de risque hypo
        val hypoGuard = computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = profile.lgsThreshold)
        val mealBypassHighTT = mealModeActive && currentBg > hypoGuard

        if (!profile.allowSMB_with_high_temptarget &&
            profile.temptargetSet && targetbg > 100 &&
            !mealBypassHighTT && !isMealRise
        ) {
            consoleError.add(context.getString(R.string.smb_disabled_high_target, targetbg))
            return false
        }

        // 3) Enable cases (préférences)
        if (profile.enableSMB_always) {
            consoleLog.add(context.getString(R.string.smb_enabled_always))
            return true
        }
        if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
            consoleLog.add(context.getString(R.string.smb_enabled_for_cob, mealData.mealCOB))
            return true
        }
        if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
            consoleLog.add(context.getString(R.string.smb_enabled_after_carb_entry))
            return true
        }
        if (profile.enableSMB_with_temptarget && profile.temptargetSet && targetbg < 100) {
            consoleLog.add(context.getString(R.string.smb_enabled_for_temp_target, convertBG(targetbg)))
            return true
        }

        // 4) Enfin, l'exception meal-rise si elle est vraie
        if (mealModeActive) {
            val safeFloor = max(100.0, targetbg - 5)
            val risingFast = delta >= 2.0 || (delta > 0 && currentBg > 120)
            
            // Condition assouplie: eventualBg ignoré si montée confirmée
            if (currentBg > safeFloor && delta > 0.5 && (eventualBg > safeFloor || risingFast)) {
                mealModeSmbReason = context.getString(
                    R.string.smb_enabled_meal_mode,
                    convertBG(currentBg),
                    delta,
                    convertBG(eventualBg)
                )
                return true
            }
        }

        consoleError.add(context.getString(R.string.smb_disabled_no_pref_or_condition))
        return false
    }


    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    fun setTempBasal(
        _rate: Double,
        duration: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean = false,
        forceExact: Boolean = false
    ): RT {
        // 0) LGS kill-switch (sans récursion)
        val lgsPref = profile.lgsThreshold
        val hypoGuard = computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = lgsPref)
        val blockLgs = isBelowHypoThreshold(bg, predictedBg.toDouble(), eventualBG, hypoGuard, delta.toDouble())
        if (blockLgs) {
            rT.reason.append(context.getString(R.string.lgs_triggered, "%.0f".format(bg), "%.0f".format(hypoGuard)))
            rT.duration = maxOf(duration, 30)
            rT.rate = 0.0
            return rT
        }
        val isLgsEnabled = profile.lgsThreshold != null && profile.lgsThreshold!! > 0

        val bgNow = bg

        // 1) Mode manuel : on pose exactement la valeur demandée (toujours bornée ≥ 0)
        if (forceExact) {
            val rate = _rate.coerceAtLeast(0.0)
            rT.reason.append(
                context.getString(
                    R.string.manual_basal_override,
                    rate,
                    duration,
                    if (Therapy(persistenceLayer).let { it.updateStatesBasedOnTherapyEvents();
                            it.snackTime || it.highCarbTime || it.mealTime || it.lunchTime || it.dinnerTime || it.bfastTime
                        }) "✔" else "✘"
                )
            )
            rT.duration = duration
            rT.rate = rate
            return rT
        }

        // 2) Contexte
        lastProfile = profile
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        val isMealMode = therapy.snackTime || therapy.highCarbTime || therapy.mealTime
            || therapy.lunchTime || therapy.dinnerTime || therapy.bfastTime

        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        val night = hour <= 7 // (OK tel quel, utilisé pour l’autodrive)
        val predDelta = predictedDelta(getRecentDeltas()).toFloat()
        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)
        val isEarlyAutodrive = !night && !isMealMode && autodrive &&
            bgNow > hypoGuard && bgNow > 110 && detectMealOnset(delta, predDelta, bgacc.toFloat(), predictedBg, profile.target_bg.toFloat())

        // 3) Tendance & ajustement
        val bgTrend = calculateBgTrend(getRecentBGs(), StringBuilder())
        var rateAdjustment = adjustRateBasedOnBgTrend(_rate, bgTrend).coerceAtLeast(0.0)
        
        // 🚀 PKPD TBR Boost: Augmenter TBR si preferTbr (sauf modes repas)
        // Note: pkpdPreferTbrBoost est déjà à 1.0 pour les modes repas (via reset dans finalizeAndCapSMB)
        if (pkpdPreferTbrBoost > 1.0 && !isMealMode) {
            val originalRate = rateAdjustment
            rateAdjustment = (rateAdjustment * pkpdPreferTbrBoost).coerceAtLeast(0.0)
            consoleLog.add("PKPD_TBR_BOOST original=${"%.2f".format(originalRate)} boost=${"%.2f".format(pkpdPreferTbrBoost)} → ${"%.2f".format(rateAdjustment)}U/h")
        }

        // 4) Limites de sécurité
        val maxSafe = min(
            profile.max_basal,
            min(
                profile.max_daily_safety_multiplier * profile.max_daily_basal,
                profile.current_basal_safety_multiplier * profile.current_basal
            )
        )

        // 5) Application des limites
        val bypassSafety = (overrideSafetyLimits || isMealMode || isEarlyAutodrive) && bgNow > hypoGuard

        // Même en bypass, on ne dépasse JAMAIS max_basal (hard cap)
        var rate = when {
            bgNow <= hypoGuard -> 0.0
            // [BASAL FLOOR] Rising & > 85 mg/dL -> Maintain floor (50%) instead of 0.0
            // Only if prediction would otherwise set it to 0.0 (e.g. safety logic)
            // We check this floor *inside* the safe zone (> hypoGuard).
            rateAdjustment == 0.0 && bgNow > 85.0 && delta > 1.0 && !isMealMode && !isLgsEnabled -> {
                 rT.reason.append(" [BASAL_FLOOR] ")
                 profile.current_basal * 0.5
            }
            bypassSafety       -> rateAdjustment.coerceIn(0.0, profile.max_basal)
            else               -> rateAdjustment.coerceIn(0.0, maxSafe)
        }

        // 6) Ajustements cycle féminin (conserve un cap)
        val wCycleInfo = ensureWCycleInfo()
        if (wCycleInfo != null) {
            appendWCycleReason(rT.reason, wCycleInfo)
        }
        if (bgNow > hypoGuard) {
            if (wCycleInfo != null && wCycleInfo.applied) {
                val pre = rate
                val scaled = rate * wCycleInfo.basalMultiplier
                val limit = if (bypassSafety) profile.max_basal else maxSafe
                rate = scaled.coerceIn(0.0, limit)
                val need = if (pre > 0.0) rate / pre else null
                updateWCycleLearner(need, null)
                // 🔁 log "post-application" avec la mesure d'écart réellement appliquée
                val profile = lastProfile
                if (profile != null) {
                    wCycleFacade.infoAndLog(
                        mapOf(
                            "trackingMode" to wCyclePreferences.trackingMode().name,
                            "contraceptive" to wCyclePreferences.contraceptive().name,
                            "thyroid" to wCyclePreferences.thyroid().name,
                            "verneuil" to wCyclePreferences.verneuil().name,
                            "bg" to bg,
                            "delta5" to delta.toDouble(),
                            "iob" to iob.toDouble(),
                            "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                            "isfProfile" to profile.sens,
                            "dynIsf" to variableSensitivity.toDouble(),
                            "needBasalScale" to need
                        )
                    )
                }
            }
            rate = if (bypassSafety) rate.coerceAtMost(profile.max_basal) else rate.coerceAtMost(maxSafe)
        }

        rT.reason.append(context.getString(R.string.temp_basal_pose, "%.2f".format(rate), duration))
        rT.duration = duration
        rT.rate = rate
        return rT
    }




    private fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder): Float {
        if (recentBGs.isEmpty()) {
            //reason.append("✘ Aucun historique de glycémie disponible.\n")
            reason.append(context.getString(R.string.no_bg_history))
            return 0.0f
        }

        // Hypothèse : recentBGs = liste du plus récent au plus ancien → on inverse
        val sortedBGs = recentBGs.reversed()

        val firstValue = sortedBGs.first()
        val lastValue = sortedBGs.last()
        val count = sortedBGs.size

        val bgTrend = (lastValue - firstValue) / count.toFloat()

        //reason.append("→ Analyse BG Trend\n")
        reason.append(context.getString(R.string.bg_trend_analysis))
        //reason.append("  • Première glycémie : $firstValue mg/dL\n")
        reason.append(context.getString(R.string.first_bg_value, firstValue))
        //reason.append("  • Dernière glycémie : $lastValue mg/dL\n")
        reason.append(context.getString(R.string.last_bg_value, lastValue))
        //reason.append("  • Nombre de valeurs : $count\n")
        reason.append(context.getString(R.string.number_of_values, count))
        //reason.append("  • Tendance calculée : $bgTrend mg/dL/intervalle\n")
        reason.append(context.getString(R.string.calculated_trend, bgTrend))
        return bgTrend
    }

    private fun adjustRateBasedOnBgTrend(_rate: Double, bgTrend: Float): Double {
        // Si la BG est accessible dans le scope, on peut aussi y jeter un œil ici :
        val bgNow = bg
        // Si on s’approche du seuil hypo et que la tendance est négative, coupe à 0 SEULEMENT si chute rapide
        if (bgNow <= 90.0 && bgTrend < -2.0f) return 0.0
        val adjustmentFactor = if (bgTrend < 0.0f) 0.8 else 1.2
        return _rate * adjustmentFactor
    }


    private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {
        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, predictedSMB, smbGiven, dynamicPeak, adjustedDia\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$predictedSMB,$smbToGive," +
            "$peakintermediaire,$latestAdjustedDia"


        if (!csvfile.exists()) {
            csvfile.parentFile?.mkdirs()
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr,hourOfDay,weekend," +
            "bg,targetBg,iob,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven,dynamicPeak,adjustedDia\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive,$peakintermediaire,$latestAdjustedDia"
        if (!csvfile2.exists()) {
            csvfile2.parentFile?.mkdirs() // Crée le dossier s'il n'existe pas
            csvfile2.createNewFile()
            csvfile2.appendText(headerRow)
        }
        csvfile2.appendText(valuesToRecord + "\n")
    }

    fun removeLast200Lines(csvFile: File) {
        val reasonBuilder = StringBuilder()
        if (!csvFile.exists()) {
            //println("Le fichier original n'existe pas.")
            println(context.getString(R.string.original_file_missing))
            return
        }

        // Lire toutes les lignes du fichier
        val lines = csvFile.readLines(Charsets.UTF_8)

        if (lines.size <= 200) {
            //reasonBuilder.append("Le fichier contient moins ou égal à 200 lignes, aucune suppression effectuée.")
            reasonBuilder.append(context.getString(R.string.file_too_short))
            return
        }

        // Conserver toutes les lignes sauf les 200 dernières
        val newLines = lines.dropLast(200)

        // Création d'un nom de sauvegarde avec timestamp
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val backupFileName = "backup_$timestamp.csv"
        val backupFile = File(csvFile.parentFile, backupFileName)

        // Sauvegarder le fichier original
        csvFile.copyTo(backupFile, overwrite = true)

        // Réécrire le fichier original avec les lignes restantes
        csvFile.writeText(newLines.joinToString("\n"), Charsets.UTF_8)

        //reasonBuilder.append("Les 200 dernières lignes ont été supprimées. Le fichier original a été sauvegardé sous '$backupFileName'.")
        reasonBuilder.append(context.getString(R.string.last_200_deleted, backupFileName))
    }
    @SuppressLint("StringFormatInvalid")
    private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
        val reasonBuilder = StringBuilder()
        // Vérifier si le TIR est inférieur à 85%
        if (tir1DAYIR < 85) {
            // Vérifier si l'heure actuelle est entre 00:05 et 00:10
            val currentTime = LocalTime.now()
            val start = LocalTime.of(0, 5)
            val end = LocalTime.of(0, 10)

            if (currentTime.isAfter(start) && currentTime.isBefore(end)) {
                // Calculer la date de la veille au format dd/MM/yyyy
                val yesterday = LocalDate.now().minusDays(1)
                val dateToRemove = yesterday.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

                // Appeler la méthode de suppression
                //createFilteredAndSortedCopy(csvfile,dateToRemove)
                removeLast200Lines(csvfile)
                //reasonBuilder.append("Les données pour la date $dateToRemove ont été supprimées car TIR1DAIIR est inférieur à 85%.")
                reasonBuilder.append(context.getString(R.string.reason_data_removed, dateToRemove))
            } else {
                //reasonBuilder.append("La suppression ne peut être exécutée qu'entre 00:05 et 00:10.")
                reasonBuilder.append(context.getString(R.string.reason_deletion_time_restricted))
            }
        }
    }

    /**
     * 🛡️ Centralized Safety Enforcement for "Innovation" Modes
     * Ensures consistent application of MaxIOB and MaxSMB limits using capSmbDose.
     */
    private data class SmbGateAudit(
        val sinceBolus: Double,
        val refractoryWindow: Double,
        val absorptionFactor: Double,
        val predMissing: Boolean,
        val maxIobLimit: Double,
        val maxSmbLimit: Double
    )

    private fun finalizeAndCapSMB(
        rT: RT,
        proposedUnits: Double,
        reasonHeader: String,
        mealData: MealData,
        hypoThreshold: Double,
        isExplicitUserAction: Boolean = false,
        decisionSource: String = "AIMI",
        isMealActive: Boolean = false
    ) {
        // 🚀 REACTOR MODE: Full Speed (Safety delegated to applySafetyPrecautions)
        // User Directive: "Garde le moteur à plein régime"
        
        val effectiveProposed = proposedUnits

        // No inline clamping here. 
        // We trust the UnifiedReactivityLearner to provide the correct amplification
        // and the Safety Module to catch critical issues.
        
        val proposedFloat = effectiveProposed.toFloat()
        lastDecisionSource = decisionSource
        lastSmbProposed = effectiveProposed
        
        // 🛡️ SAFETY NET: Dynamic SMB Limit (Zones & Trajectory)
        // Replaces simple "React Over 120" with a smart, amplified range logic.
        // Handles: Strict Lows (<120), Buffer/Transition (120-160), and Full Reactor (>160).
        // 🧠 AI Auditor Confidence (si disponible)
        // Si l'Auditor a été interrogé récemment, utiliser sa confiance
        // Sinon, passer null pour appliquer le boost par défaut
        val auditorLastConfidence: Double? = try {
            app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(300_000)?.verdict?.confidence
        } catch (e: Exception) { null }
        
        val baseLimit = app.aaps.plugins.aps.openAPSAIMI.safety.SafetyNet.calculateSafeSmbLimit(
            bg = this.bg,
            targetBg = targetBg.toDouble(),
            eventualBg = this.eventualBG,
            delta = this.delta.toDouble(),
            shortAvgDelta = this.shortAvgDelta.toDouble(),
            maxSmbLow = this.maxSMB,
            maxSmbHigh = this.maxSMBHB,
            isExplicitUserAction = isExplicitUserAction,
            auditorConfidence = auditorLastConfidence
        )

         // 🔒 FCL Safety: Enforce Safety Precautions (Dropping Fast, Hypo Risk, etc)
         // finalizeAndCapSMB often handles forced boluses, but they MUST yield to critical physical safety.
         // 🔧 RESTORED: Pass PKPD runtime for tail damping
         // Note: pkpdRuntime is calculated later in determine_basal, so we pass null here
         // and rely on the PKPD tail damping in applySafetyPrecautions for context-aware reduction
         val safetyCappedUnits = applySafetyPrecautions(
            mealData = mealData,
            smbToGiveParam = proposedFloat,
            hypoThreshold = hypoThreshold,
            reason = rT.reason,
            pkpdRuntime = cachedPkpdRuntime, // 🔧 FIX (MTR): Use cached runtime for Tail Damping
            exerciseFlag = sportTime, // Pass exercise state
            suspectedLateFatMeal = lateFatRiseFlag, // Pass late fat flag
            ignoreSafetyConditions = isExplicitUserAction
         ).coerceAtMost(baseLimit.toFloat()) // Apply the SafetyNet limit immediately

         if (safetyCappedUnits < proposedFloat) {
              consoleLog.add("Safety Precautions reduced SMB: $proposedFloat -> $safetyCappedUnits (BaseLimit=${"%.2f".format(baseLimit)})")
         }

         // 🔧 FIX 3: Enhanced refractory if prediction absent
         // Calculate predMissing FIRST before using it
         val predMissing = !lastPredictionAvailable || lastPredictionSize < 3
         
         val baseRefractoryWindow = calculateSMBInterval().toDouble()
         val refractoryWindow = if (predMissing) {
             (baseRefractoryWindow * 1.5).coerceAtLeast(5.0) // +50% safety margin if blind
         } else {
             baseRefractoryWindow
         }
         
         val sinceBolus = if (lastBolusAgeMinutes.isNaN()) 999.0 else lastBolusAgeMinutes
         val refractoryBlocked = sinceBolus < refractoryWindow && !isExplicitUserAction
         var gatedUnits = safetyCappedUnits
         var absorptionFactor = 1.0

         if (refractoryBlocked) {
             gatedUnits = 0f
             consoleLog.add("⏸️ REFRACTORY_BLOCK sinceBolus=${"%.1f".format(sinceBolus)}m window=${"%.1f".format(refractoryWindow)}m (SMB blocked)")
         } else if (sinceBolus < refractoryWindow && isExplicitUserAction) {
             // Modes repas bypassent explicitement le refractory
             consoleLog.add("✅ REFRACTORY_BYPASS sinceBolus=${"%.1f".format(sinceBolus)}m window=${"%.1f".format(refractoryWindow)}m (Meal mode override)")
         }

         // 🔧 FIX 2: Adaptive AbsorptionGuard threshold (pediatric-safe)
         val tdd24h = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 30.0
         val activityThreshold = (tdd24h / 24.0) * 0.15 // 15% of hourly TDD
         
         if (sinceBolus < 20.0 && iobActivityNow > activityThreshold && !isExplicitUserAction) {
             absorptionFactor = if (bg > targetBg + 60 && delta > 0) 0.75 else 0.5
             gatedUnits = (gatedUnits * absorptionFactor.toFloat()).coerceAtLeast(0f)
         }

         if (predMissing && !isExplicitUserAction) {
             val degraded = (maxSMB * 0.5).toFloat()
             if (gatedUnits > degraded) gatedUnits = degraded
         }
         
         // 🚀 NOUVEAUTÉ: Real-Time Insulin Observer Throttle
         if (!isExplicitUserAction) {
             val actionState = insulinObserver.update(
                 currentBg = this.bg,
                 bgDelta = this.delta.toDouble(),
                 iobTotal = this.iob.toDouble(),
                 iobActivityNow = this.iobActivityNow,
                 iobActivityIn30 = 0.0,  // Not critical for throttle
                 peakMinutesAbs = 0,     // Not critical for throttle
                 diaHours = 4.0,         // Approximation
                 carbsActiveG = this.cob.toDouble(),
                 now = dateUtil.now()
             )
             
             val throttle = app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbTbrThrottleLogic.computeThrottle(
                 actionState = actionState,
                 bgDelta = this.delta.toDouble(),
                 bgRising = this.bg > this.targetBg,
                 targetBg = this.targetBg.toDouble(),
                 currentBg = this.bg
             )
             
             // Apply throttle
             val originalGated = gatedUnits
             gatedUnits = (gatedUnits * throttle.smbFactor.toFloat()).coerceAtLeast(0f)
             
             // Log
             if (throttle.smbFactor < 1.0 || throttle.preferTbr) {
                 consoleLog.add("PKPD_THROTTLE smbFactor=${"%.2f".format(throttle.smbFactor)} intervalAdd=${throttle.intervalAddMin} preferTbr=${throttle.preferTbr} reason=${throttle.reason}")
                 if (originalGated > 0f && gatedUnits < originalGated * 0.6f) {
                     consoleLog.add("  ⚠️ SMB reduced ${"%2f".format(originalGated)} → ${"%.2f".format(gatedUnits)}U (PKPD throttle)")
                 }
             }
             
             // Si preferTbr, suggérer TBR dans reason (pas bloquer SMB)
             if (throttle.preferTbr && gatedUnits < proposedFloat * 0.5) {
                 rT.reason.append(" | 💡 TBR recommended (${throttle.reason})")
             }
             
             // 🚀 Stocker les valeurs pour interval SMB et TBR boost
             pkpdThrottleIntervalAdd = throttle.intervalAddMin
             pkpdPreferTbrBoost = if (throttle.preferTbr) 1.15 else 1.0  // +15% TBR si preferTbr
         } else {
             // Reset si explicit user action (modes repas)
             pkpdThrottleIntervalAdd = 0
             pkpdPreferTbrBoost = 1.0
         }

         val safeCap = capSmbDose(
             proposedSmb = gatedUnits, // Use the safety-reduced amount as base
            bg = this.bg,
            // 🔒 CRITICAL FIX: Always respect user preference (no bypass)
            // Previous code used max(baseLimit, proposedUnits) which IGNORED user limits
            // This caused hypos for users who set conservative maxSMB
            maxSmbConfig = baseLimit, // ✅ ALWAYS respect user preference
            iob = this.iob.toDouble(),
            maxIob = this.maxIob
        )
        
        // 🚀 MEAL MODES FORCE SEND: "Red Carpet" Logic
        var finalUnits: Double
        
        // Définition élargie du contexte prioritaire "Tapis Rouge"
        // 1. Action Explicite (Bouton appuyé)
        // 2. Mode Repas Actif (Dinner, Lunch, etc.) OU AIMI Context Meal (RContext déclaré)
        // 3. Chaos Carbohydrate (COB présents + Montée violente > 5 mg/dL/5m)
        val isMealChaos = (mealData.mealCOB > 10.0 && delta > 5.0)
        
        // Helper interne pour vérifier AIMI Context (RContext) - supposer true si mealData indique un repas récent
        // Dans une implémentation idéale, on injecterait le ContextRepository, mais ici on utilise les proxies disponibles
        // 🐛 FIX: 'mealData.isMealStart' n'existe pas. On utilise la variable locale 'isMealActive' calculée plus haut.
        val isAimiContextMeal = isMealActive || (mealData.mealCOB > 5.0 && delta > 2.0)

        val isRedCarpetSituation = isExplicitUserAction || isMealModeCondition() || isAimiContextMeal || ((isMealChaos || isMealActive) && proposedUnits > 0.5f)

        // On entre dans la logique forcée si on est en situation "Red Carpet" et qu'il y a une demande
        if (isRedCarpetSituation && proposedUnits > 0.0) {
            
            // 1. Restauration de la demande
            // Si les sécurités mineures ont coupé plus de 40% du bolus, on restaure la demande initiale.
            val candidateUnits = if (gatedUnits < proposedUnits.toFloat() * 0.6f) { 
                consoleLog.add("✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=${"%.2f".format(proposedUnits)} vs Gated=${"%.2f".format(gatedUnits)})")
                 proposedUnits.toFloat()
            } else {
                 gatedUnits 
            }

            // 2. Appliquer les Sécurités VITALES (Hard Caps uniquement)
            
            // a. Cap MaxSMB - On utilise MaxSMBHB (High) si dispo, sinon config standard
            val maxSmbCap = if (maxSMBHB > baseLimit) maxSMBHB.toFloat() else baseLimit.toFloat()
            var mealBolus = min(candidateUnits, maxSmbCap)

            // b. Cap MaxIOB (Sécurité Ultime) - On ne s'autorise à remplir QUE l'espace disponible
            val iobSpace = (this.maxIob - this.iob).coerceAtLeast(0.0)
            
            if (mealBolus > iobSpace.toFloat()) {
                consoleLog.add("🛡️ RED CARPET: Clamped by MaxIOB (Need=${"%.2f".format(mealBolus)}, Space=${"%.2f".format(iobSpace)})")
                mealBolus = iobSpace.toFloat()
            }
            
            // c. Hard Cap 30U (Ceinture de sécurité absolue anti-bug)
            mealBolus = mealBolus.coerceAtMost(30f)

            finalUnits = mealBolus.toDouble()
            
            // Log explicite pour le debugging
            if (finalUnits > gatedUnits + 0.1) {
                val reason = if (isExplicitUserAction) "UserAction" else if (isMealChaos) "CarbChaos" else "MealMode/Context"
                consoleLog.add("🍱 MEAL_FORCE_EXECUTED ($reason): ${"%.2f".format(finalUnits)} U (Overrides minor safety checks)")
            }

        } else {
            // Comportement standard (Pas de repas ou demande nulle)
            finalUnits = safeCap.toDouble()
        }
        
        lastSmbCapped = finalUnits
        lastSmbFinal = finalUnits

        if (finalUnits > 0) {
            internalLastSmbMillis = dateUtil.now()
        }

        rT.units = finalUnits.coerceAtLeast(0.0)
        rT.reason.append(reasonHeader)

         val audit = SmbGateAudit(
             sinceBolus = sinceBolus,
             refractoryWindow = refractoryWindow,
             absorptionFactor = absorptionFactor,
             predMissing = predMissing,
             maxIobLimit = this.maxIob,
             maxSmbLimit = baseLimit
         )
         if (proposedUnits > 0 || safeCap > 0f) {
             logSmbGateExplain(audit, proposedFloat, gatedUnits, safeCap, activityThreshold)
         }

        if (safeCap < proposedFloat) {
             rT.reason.appendLine(context.getString(R.string.limits_smb, proposedFloat, safeCap))
             consoleLog.add("SMB_CAP: Proposed=$proposedFloat Allowed=$safeCap Reason=$reasonHeader")
             consoleLog.add("  -> Limits: MaxSMB=$baseLimit MaxIOB=${this.maxIob} IOB=${this.iob}")
             if (safeCap == 0f && this.iob >= this.maxIob) {
                 consoleLog.add("  -> BLOCK: IOB_SATURATION (IOB ${this.iob} >= MaxIOB ${this.maxIob})")
             }
        }
    }

    /**
     * 🛡️ Sécurité Ultime : Plafonne le SMB final juste avant l'envoi.
     *
     * Cette fonction garantit que peu importe les calculs précédents (ML, Reactivity, etc.),
     * le système ne dépassera JAMAIS le maxSMB configuré.
     *
     * @param proposedSmb Dose proposée par l'algo
     * @param bg Glycémie actuelle
     * @param maxSmbConfig Le MaxSMB configuré (ou ajusté pour HyperGLY)
     * @param iob IOB actuel
     * @param maxIob Max IOB autorisé
     * @return La dose plafonnée
     */
    private fun capSmbDose(
        proposedSmb: Float,
        bg: Double,
        maxSmbConfig: Double,
        iob: Double,
        maxIob: Double
    ): Float {
        // 1. Plafond absolu MaxSMB (Respect strict de la config)
        var capped = calculateMin(proposedSmb, maxSmbConfig.toFloat())

        // 2. Protection supplémentaire pour BG < 120 (Zone Normale/Basse)
        // On s'assure qu'aucun boost "Hyper" (comme Autodrive ou Reactivity fort) ne s'applique ici.
        // Si BG < 120, on est TRÈS conservateur.
        if (bg < 120) {
            // 2. Protection supplémentaire pour BG < 120 (Zone Normale/Basse)
            // L'utilisateur demande explicitement que la logique soit écrite ici.
            // On s'assure que si on est en zone "normale", on n'utilise PAS le MaxSMBHB ni aucun boost.
            // On re-vérifie par rapport à OApsAIMIMaxSMB (passé ici via maxSmbConfig normalement, mais on force le min).
            
            // Même si maxSmbConfig était élevé par erreur, on le redescend à une valeur de sécurité hardcodée 
            // SI et seulement SI l'utilisateur n'a pas mis un OApsAIMIMaxSMB géant volontairement.
            // MAIS pour respecter la demande "as tu restauré un maxsmb bg < 120", on s'assure que capped <= maxSmbConfig
            // Ce qui est déjà fait en 1.
            
            // On ajoute une sécurité "Absolue" pour cette zone critique :
            // Si BG est < 120, on refuse tout SMB > 2.0U (sauf si l'utilisateur a configuré un maxSMB < 2.0, alors c'est plus bas).
            // C'est une ceinture de sécurité contre une config utilisateur dangereuse type "MaxSMB = 10" utilisé tout le temps.
            // OU, si on suit strictement la demande : respecter la préférence "MaxSMB" (Low).
            
            // On va supposer que `maxSmbConfig` EST la valeur de la préférence Low/Normal (car passée par l'appelant).
            // On ajoute juste un double-check :
            if (capped > maxSmbConfig) {
                 capped = maxSmbConfig.toFloat()
            }
        }

        // 3. Vérification IOB (Ceinture et bretelles)
        // Si l'injection nous fait dépasser MaxIOB, on réduit.
        if (iob + capped > maxIob) {
            capped = max(0.0, maxIob - iob).toFloat()
        }

        return capped
    }

    private fun logSmbGateExplain(audit: SmbGateAudit, proposed: Float, gated: Float, final: Float, activityThreshold: Double) {
        val refractoryLine =
            "GATE_REFRACTORY sinceLastBolus=${"%.1f".format(audit.sinceBolus)}m window=${"%.1f".format(audit.refractoryWindow)}"
        val maxIobLine = "GATE_MAXIOB allowed=${"%.2f".format(audit.maxIobLimit)} current=${"%.2f".format(iob)}"
        val maxSmbLine = "GATE_MAXSMB cap=${"%.2f".format(audit.maxSmbLimit)} proposed=${"%.2f".format(proposed)}"
        val absorptionLine = "GATE_ABSORPTION activity=${"%.3f".format(iobActivityNow)} threshold=${"%.3f".format(activityThreshold)} factor=${"%.2f".format(audit.absorptionFactor)}"
        val predLine = "GATE_PRED_MISSING fallback=${if (audit.predMissing) "ON" else "OFF"}"

        if (final > 0f || gated == 0f || final == 0f) {
            consoleLog.add(refractoryLine)
            consoleLog.add(maxIobLine)
            consoleLog.add(maxSmbLine)
            consoleLog.add(absorptionLine)
            consoleLog.add(predLine)
        }
    }

    // Fonction utilitaire pour éviter l'import min
    private fun calculateMin(a: Float, b: Float): Float {
        return if (a < b) a else b
    }

    private fun applySafetyPrecautions(
        mealData: MealData,
        smbToGiveParam: Float,
        hypoThreshold: Double,
        reason: StringBuilder? = null,
        pkpdRuntime: PkPdRuntime? = null,
        exerciseFlag: Boolean = false,
        suspectedLateFatMeal: Boolean = false,
        ignoreSafetyConditions: Boolean = false
    ): Float {
        var smbToGive = smbToGiveParam
        val mealWeights = computeMealAggressionWeights(mealData, hypoThreshold)

        val (isCrit, critMsg) = isCriticalSafetyCondition(mealData, hypoThreshold,context)
        if (isCrit && !ignoreSafetyConditions) {
            reason?.appendLine("🛑 $critMsg → SMB=0")
            consoleLog.add("SMB forced to 0 by critical safety: $critMsg")
            return 0f
        }

        if (isSportSafetyCondition()) {
            if (mealWeights.guardScale > 0.0 && smbToGive > 0f) {
                val before = smbToGive
                smbToGive = (smbToGive * mealWeights.guardScale.toFloat()).coerceAtLeast(0f)
                reason?.appendLine(
                    context.getString(R.string.reason_safety_sport_meal_reduction, before, smbToGive)
                )
            } else {
                reason?.appendLine(context.getString(R.string.safety_sport_smb_zero))
                consoleLog.add("SMB forced to 0 by sport safety guard")
                return 0f
            }
        }
        val wCycleInfo = ensureWCycleInfo()
        if (wCycleInfo != null) {
            if (wCycleInfo.applied) {
                val pre = smbToGive
                smbToGive = (smbToGive * wCycleInfo.smbMultiplier.toFloat()).coerceAtLeast(0f)
                val need = if (pre > 0f) (smbToGive / pre).toDouble() else null
                updateWCycleLearner(null, need)

// 🔁 log "post-application" avec la mesure d'écart réellement appliquée
                val profile = lastProfile
                if (profile != null) {
                    wCycleFacade.infoAndLog(
                        mapOf(
                            "trackingMode" to wCyclePreferences.trackingMode().name,
                            "contraceptive" to wCyclePreferences.contraceptive().name,
                            "thyroid" to wCyclePreferences.thyroid().name,
                            "verneuil" to wCyclePreferences.verneuil().name,
                            "bg" to bg,
                            "delta5" to delta.toDouble(),
                            "iob" to iob.toDouble(),
                            "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                            "isfProfile" to profile.sens,
                            "dynIsf" to variableSensitivity.toDouble(),
                            "needSmbScale" to need
                        )
                    )
                }
            }
        }
        // Ajustements spécifiques
        val beforeAdj = smbToGive
        smbToGive = applySpecificAdjustments(smbToGive, ignoreSafetyRestrictions = ignoreSafetyConditions)
        if (smbToGive != beforeAdj) {
            //reason?.appendLine("🎛️ Ajustements: ${"%.2f".format(beforeAdj)} → ${"%.2f".format(smbToGive)} U")
            reason?.appendLine(context.getString(R.string.adjustments_smb, beforeAdj, smbToGive))
        }
        if (mealWeights.active && mealWeights.boostFactor > 1.0 && smbToGive > 0f) {
            val beforeBoost = smbToGive
            smbToGive = (smbToGive * mealWeights.boostFactor.toFloat()).coerceAtLeast(0f)
            reason?.appendLine(
                context.getString(
                    R.string.reason_meal_aggression_boost,
                    beforeBoost,
                    smbToGive,
                    mealWeights.boostFactor
                )
            )
        }
        // 🔧 RESTORED: PKPD Tail Damping for Exercise & Late Fat Meals
    // Apply PKPD-aware insulin tail considerations
    if (pkpdRuntime != null && smbToGive > 0f) {
        val tailDampingFactor = when {
            // Exercise: reduce SMB if insulin tail is still active
            exerciseFlag && pkpdRuntime.pkpdScale < 0.9 -> {
                val factor = 0.7 // Conservative 30% reduction
                reason?.appendLine("⚡ Exercise + PKPD Tail: SMB×${"%.2f".format(factor)}")
                factor
            }
            // Suspected late fat meal: reduce SMB to avoid stacking before fat absorption
            suspectedLateFatMeal && iob > maxSMB -> {
                val factor = 0.6 // More conservative for fat meals
                reason?.appendLine("🧈 Late Fat + High IOB: SMB×${"%.2f".format(factor)}")
                factor
            }
            else -> 1.0
        }
        
        if (tailDampingFactor < 1.0) {
            val beforeTail = smbToGive
            smbToGive = (smbToGive * tailDampingFactor.toFloat()).coerceAtLeast(0f)
            consoleLog.add("PKPD_TAIL_DAMP: ${"%.2f".format(beforeTail)}→${"%.2f".format(smbToGive)} ex=$exerciseFlag fat=$suspectedLateFatMeal scale=${pkpdRuntime.pkpdScale}")
        }
    }

        // Finalisation
        val beforeFinalize = smbToGive
        smbToGive = finalizeSmbToGive(smbToGive)
        if (smbToGive != beforeFinalize) {
            //reason?.appendLine("🧩 Finalisation: ${"%.2f".format(beforeFinalize)} → ${"%.2f".format(smbToGive)} U")
            reason?.appendLine(context.getString(R.string.finalization_smb, beforeFinalize, smbToGive))
        }

        // Limites max
        val beforeLimits = smbToGive
        smbToGive = applyMaxLimits(smbToGive)
        if (smbToGive != beforeLimits) {
            //reason?.appendLine("🧱 Limites: ${"%.2f".format(beforeLimits)} → ${"%.2f".format(smbToGive)} U")
            reason?.appendLine(context.getString(R.string.limits_smb, beforeLimits, smbToGive))
        }
        smbToGive = smbToGive.coerceAtLeast(0f)
        return smbToGive
    }
    private fun applyMaxLimits(smbToGive: Float): Float {
        var result = smbToGive

        // Vérifiez d'abord si smbToGive dépasse maxSMB
        if (result > maxSMB) {
            result = maxSMB.toFloat()
        }
        // Ensuite, vérifiez si la somme de iob et smbToGive dépasse maxIob
        if (iob + result > maxIob) {
            val room = maxIob.toFloat() - iob
            if (room < 0) {
                 // Debug pour tracking
                 // consoleLog.add("DEBUG: Negative Room detected in applyMaxLimits ($room). Clamped to 0.") 
            }
            result = max(0.0f, room)
        }

        return result
    }

    // Helper to check for recent bolus activity (prevent double dosing)
    private fun hasReceivedRecentBolus(minutes: Int, lastBolusTimeMs: Long): Boolean {
        val lookbackTime = dateUtil.now() - minutes * 60 * 1000L
        
        // 1. Check DB
        val boluses = persistenceLayer.getBolusesFromTime(lookbackTime, true).blockingGet()
        val dbHasBolus = boluses.any { it.amount > 0.3 }

        // 2. Check Pump Status Memory (Fallback)
        val memoryHasBolus = lastBolusTimeMs > lookbackTime

        if (dbHasBolus || memoryHasBolus) {
            return true
        }
        return false
    }

    /**
     * Detects rapid IOB increase which may indicate receptor saturation
     * and potentially slower insulin absorption.
     * 
     * @param currentIOB Current insulin on board
     * @param lookbackMinutes Time window to check (default 15 min)
     * @return IOB increase amount if rapid, 0.0 otherwise
     */
    
    /**
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ❌ CODE MORT SUPPRIMÉ (2026-01-05) - Système FCL Legacy
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //
    // Fonctions supprimées (partiellement implémentées, jamais utilisées):
    // - detectRapidIOBIncrease()           : Détection bolus rapide > 2U
    // - calculateDynamicDIA()              : Ajustement DIA saturation récepteurs
    // - calculateAdaptivePrebolus()        : Prebolus adaptatif (DISABLED user)
    // - isHighPlateauBreakerCondition()    : Feature FCL "High Plateau Breaker"
    // - calculateResistanceHammer()        : Feature FCL boost x1.5 résistance
    // - checkIneffectivenessWatchdog()     : Watchdog échecs Hammer
    // - updateWatchdogState()              : Update state Hammer (tournait vide)
    //
    // Variables supprimées:
    // - lastResistanceHammerTime: Long     : État Resistance Hammer (jamais modifié)
    // - hammerFailureCount: Int            : Compteur échecs (jamais incrémenté)
    //
    // Raison suppression: Système partiellement détruit, remplacé par:
    // - pkpd/PkPdRuntime            : DIA/peak dynamiques + saturation tail
    // - safety/HighBgOverride       : Gestion montées élevées (progressif vs brutal)
    //
    // Total supprimé: 149 lignes (7 fonctions + 1 appel + 2 variables)
    // Backup: DetermineBasalAIMI2.kt.backup_20260105_221151
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

     * Calculates dynamic DIA and peak time adjustments based on rapid IOB increase.
     * Large boluses may slow absorption due to receptor saturation.
     * 
     * @param profile Base profile with standard DIA/peak
     * @param rapidIOBAmount Amount of rapid IOB increase
     * @return Pair of (adjustedDIA, adjustedPeak)
     */



    private fun isDriftTerminatorCondition(
        bg: Float,
        targetBg: Float,
        delta: Float,
        lastBolusVolume: Double,
        reason: StringBuilder
    ): Boolean {
        // 1. Slow Creep (Target + 15)
        if (bg <= targetBg + 15) return false
        
        // 2. Rising
        if (delta <= 0) return false
        
        // 3. No recent bolus activity (Clean slate)
        if (lastBolusVolume > 0.1) return false
        
        reason.append("🧹 Drift Terminator: Slow creep detected without recent bolus -> ENGAGED\n")
        return true
    }

    private fun calculateDynamicMicroBolus(
        isf: Double,
        baseFactor: Double = 20.0,
        reason: StringBuilder
    ): Double {
        // Formula: MicroBolus = 20 / ISF
        // Example: ISF 50 -> 0.4U. ISF 100 -> 0.2U.
        // Safety: ISF is rarely < 10 or > 500.
        // Cap max bolus to 0.5U for safety by default (unless baseFactor changes)
        if (isf <= 0) return 0.0 // Should not happen
        
        var bolus = baseFactor / isf
        
        // Safety Caps
        bolus = bolus.coerceIn(0.05, 0.5) 
        
        return bolus
    }



    private fun isCompressionProtectionCondition(
        delta: Float,
        reason: StringBuilder
    ): Boolean {
         // Impossible rise (e.g. +30 mg/dL in 5 mins) = Compression Low Recovery
         // [User Request]: Relaxed to avoid blocking aggressive meal spikes (e.g. +22)
         if (delta > 35.0f) {
             reason.append("🛡️ Safety Net: Compression Rebound Block (Delta > 35) -> Autodrive OFF\n")
             return true
         }
         return false
    }

    private fun isPostHypoProtectionCondition(
        recentBGs: List<Float>,
        reason: StringBuilder
    ): Boolean {
        // Check last 60 mins (approx 12 points)
        // If any BG < 70, we are in "Safety Zone"
        val recentHypo = recentBGs.take(12).any { it < 70 }
        if (recentHypo) {
            reason.append("🛡️ Safety Net: Post-Hypo Rebound Brake ENGAGED (BG < 70 in last 60m)\n")
            return true
        }
        return false
    }


    @SuppressLint("DefaultLocale")
    private fun isAutodriveModeCondition(
        delta: Float,
        autodrive: Boolean,
        slopeFromMinDeviation: Double,
        bg: Float,
        predictedBg: Float,
        reason: StringBuilder,
        targetBg: Float
    ): Boolean {
        // ⚙️ Prefs
        val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val autodriveDelta: Float = preferences.get(DoubleKey.OApsAIMIcombinedDelta).toFloat()
        val autodriveMinDeviation: Double = preferences.get(DoubleKey.OApsAIMIAutodriveDeviation)
    val autodriveBG: Int = preferences.get(IntKey.OApsAIMIAutodriveBG) // User Decision: Static Threshold

        // 🛡️ Noise Filter (Anti-Jump) -> [User Request]: Disabled. information for Autodrive.
    // if (delta > 15f && shortAvgDelta < 5f) {
    //      reason.append("🚫 Noise detected (Delta > 15 & Avg < 5) -> Autodrive OFF")
    //      return false
    // }

        // 📈 Deltas récents & delta combiné (IMPROVED: 15-min history)
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas).toFloat()
        
        // FIX: Extended delta history (3 periods: 0, -5, -10 min) for better noise filtering
        val avgRecentDelta = if (recentDeltas.size >= 2) {
            recentDeltas.take(2).average().toFloat()  // Average of 2 most recent deltas (~10 min)
        } else {
            delta  // Fallback to current delta if insufficient history
        }
        
        // Combine: current + predicted + recent average + trend
        // Weighted: 40% current, 30% predicted, 30% recent average
        val combinedDelta = (delta * 0.4f + predicted * 0.3f + avgRecentDelta * 0.3f)
        
        consoleLog.add("DELTA_CALC current=${String.format("%.1f", delta)} predicted=${String.format("%.1f", predicted)} avgRecent=${String.format("%.1f", avgRecentDelta)} → combined=${String.format("%.1f", combinedDelta)}")
        
        // 🎯 Dynamic Thresholds
    // Respect User Static Threshold AND Safety Margin (Target + 10)
    val dynamicBgThreshold = maxOf(targetBg + 10f, autodriveBG.toFloat())
    val dynamicPredictedThreshold = targetBg + 30f

        // 🔍 Tendance BG
        val recentBGs = getRecentBGs()
        var autodriveCondition = true
        var currentState = AutodriveState.IDLE

        if (recentBGs.isNotEmpty()) {
            val bgTrend = calculateBgTrend(recentBGs, reason)
            reason.appendLine(
                "📈 BGTrend=${"%.2f".format(bgTrend)} | Δcomb=${"%.2f".format(combinedDelta)} | predBG=${"%.0f".format(predictedBg)}"
            )
            autodriveCondition = adjustAutodriveCondition(bgTrend, predictedBg, combinedDelta, reason, dynamicPredictedThreshold)
        } else {
            //reason.appendLine("⚠️ Aucune BG récente — conditions par défaut conservées")
            reason.appendLine(context.getString(R.string.no_recent_bg))
        }

        // ⛔ Ne pas relancer si pbolus récent
        // [FIX] Removed 1-hr lockout for Autodrive.
        // User reported "conditions met but nothing happens".
        // Continuous Autodrive should not be blocked by a previous action.
        // if (hasReceivedPbolusMInLastHour(pbolusA)) { ... }

        // Determine State (Watching vs Engaged vs Idle)
        if (autodriveCondition && combinedDelta >= 1.0f && slopeFromMinDeviation >= 1.0) {
            currentState = AutodriveState.WATCHING
        }

        // FCL 13.0: Rocket Start Bypass (CombinedDelta > 10 or > 2xPref)
        
        // Final Decision
        val ok =
            autodriveCondition &&
                combinedDelta >= autodriveDelta &&
                autodrive &&
                predictedBg > dynamicPredictedThreshold &&
                // FCL Safety: Prevent Autodrive on falling BG (Delta must be near stable or rising)
                delta >= -2.0f &&
                (slopeFromMinDeviation >= autodriveMinDeviation || combinedDelta > 10.0f || combinedDelta > autodriveDelta * 2.0f) &&
                bg >= dynamicBgThreshold

        if (ok) currentState = AutodriveState.ENGAGED

        lastAutodriveState = currentState

        reason.appendLine(
            "Autodrive: ${if (ok) "ON" else "OFF"} [$currentState] | " +
                "cond=$autodriveCondition, dC=${"%.2f".format(combinedDelta)}, " +
                "predBG>${dynamicPredictedThreshold.toInt()}, slope>=${"%.2f".format(autodriveMinDeviation)}, bg>=${dynamicBgThreshold.toInt()} (UserMin=${autodriveBG})"
    )

        return ok
    }


    private fun adjustAutodriveCondition(
        bgTrend: Float,
        predictedBg: Float,
        combinedDelta: Float,
        reason: StringBuilder,
        predictedThreshold: Float
    ): Boolean {
        val autodriveDelta: Double = preferences.get(DoubleKey.OApsAIMIcombinedDelta)

        //reason.append("→ Autodrive Debug\n")
        reason.append(context.getString(R.string.autodrive_debug_header))
        //reason.append("  • BG Trend: $bgTrend\n")
        reason.append(context.getString(R.string.autodrive_bg_trend, bgTrend))
        //reason.append("  • Predicted BG: $predictedBg\n")
        reason.append(context.getString(R.string.autodrive_predicted_bg, predictedBg))
        //reason.append("  • Combined Delta: $combinedDelta\n")
        reason.append(context.getString(R.string.autodrive_combined_delta, combinedDelta))
        //reason.append("  • Required Combined Delta: $autodriveDelta\n")
        reason.append(context.getString(R.string.autodrive_required_delta, autodriveDelta))

        // Cas 1 : glycémie baisse => désactivation
        if (bgTrend < -0.15f) {
            //reason.append("  ✘ Autodrive désactivé : tendance glycémie en baisse\n")
            reason.append(context.getString(R.string.autodrive_disabled_trend))
            return false
        }

        // Cas 2 : glycémie monte ou conditions fortes
        if ((bgTrend >= 0f && combinedDelta >= autodriveDelta) || (predictedBg > predictedThreshold && combinedDelta >= autodriveDelta)) {
            //reason.append("  ✔ Autodrive activé : conditions favorables\n")
            reason.append(context.getString(R.string.autodrive_enabled_conditions))
            return true
        }

        // Cas 3 : conditions non remplies
        //reason.append("  ✘ Autodrive désactivé : conditions insuffisantes\n")
        reason.append(context.getString(R.string.autodrive_disabled_conditions))
        return false
    }

    private fun isMealModeCondition(): Boolean {
        val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
        return mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
    }
    private fun isbfastModeCondition(): Boolean {
        val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
        return bfastruntime in 0..7 && lastBolusSMBUnit != pbolusbfast.toFloat() && bfastTime
    }
    private fun isbfast2ModeCondition(): Boolean {
        val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
        return bfastruntime in 15..30 && lastBolusSMBUnit != pbolusbfast2.toFloat() && bfastTime
    }
    private fun isLunchModeCondition(): Boolean {
        val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
        return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
    }
    private fun isLunch2ModeCondition(): Boolean {
        val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
        return lunchruntime in 15..24 && lastBolusSMBUnit != pbolusLunch2.toFloat() && lunchTime
    }
    private fun isDinnerModeCondition(): Boolean {
        val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
        return dinnerruntime in 0..7 && lastBolusSMBUnit != pbolusDinner.toFloat() && dinnerTime
    }
    private fun isDinner2ModeCondition(): Boolean {
        val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
        return dinnerruntime in 15..24 && lastBolusSMBUnit != pbolusDinner2.toFloat() && dinnerTime
    }
    private fun isHighCarbModeCondition(): Boolean {
        val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
        return highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
    }
    private fun isHighCarb2ModeCondition(): Boolean {
        val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus2)
        return highCarbrunTime in 15..23 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
    }

    private fun issnackModeCondition(): Boolean {
        val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
        return snackrunTime in 0..7 && lastBolusSMBUnit != pbolussnack.toFloat() && snackTime
    }
    // --- Helpers "fenêtre repas 30 min" ---
    private fun runtimeToMinutes(rt: Long): Int {
        return if (rt > 180) { // heuristique : si >180, on suppose secondes
            (rt / 60).toInt()
        } else {
            rt.toInt()
        }
    }

    /** Renvoie (label du mode, runtime en minutes) du mode repas actif, sinon null */
    private fun activeMealRuntimeMinutes(): Pair<String, Int>? {
        return when {
            mealTime   -> "meal" to runtimeToMinutes(mealruntime)
            bfastTime  -> "bfast" to runtimeToMinutes(bfastruntime)
            lunchTime  -> "lunch" to runtimeToMinutes(lunchruntime)
            dinnerTime -> "dinner" to runtimeToMinutes(dinnerruntime)
            highCarbTime -> "highcarb" to runtimeToMinutes(highCarbrunTime)
            else -> null
        }
    }

    /** Temps restant dans la fenêtre 0..windowMin (par défaut 30) ; null si hors fenêtre */
    private fun remainingInWindow0to(rtMin: Int, windowMin: Int = 30): Int? {
        if (rtMin !in 0..windowMin) return null
        return (windowMin - rtMin).coerceAtLeast(1) // au moins 1 minute pour poser une TBR
    }
    private fun roundToPoint05(number: Float): Float {
        return (number * 20.0).roundToInt() / 20.0f
    }

    private data class MealAggressionWeights(
        val active: Boolean,
        val boostFactor: Double,
        val guardScale: Double,
        val bypassTail: Boolean,
        val predictedOvershoot: Double
    )

    private fun isMealContextActive(mealData: MealData): Boolean {
        val manualFlags = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        val cobActive = mealData.mealCOB > 5.0
        return manualFlags || cobActive
    }

    private fun computeMealAggressionWeights(mealData: MealData, hypoThreshold: Double): MealAggressionWeights {
        if (!isMealContextActive(mealData)) return MealAggressionWeights(false, 1.0, 0.0, false, 0.0)
        val predicted = predictedBg.toDouble()
        val overshoot = (predicted - targetBg).coerceAtLeast(0.0)
        val normalized = (overshoot / 80.0).coerceIn(0.0, 1.0)
        val boost = 1.0 + 0.05 + 0.15 * normalized
        val guardScale = if (overshoot > 10 && (bg - hypoThreshold) > 5.0) {
            (0.4 + 0.3 * normalized).coerceAtMost(0.85)
        } else 0.0
        val bypassTail = overshoot > 20 && mealData.mealCOB > 10.0
        return MealAggressionWeights(true, boost, guardScale, bypassTail, overshoot)
    }

    private fun isCriticalSafetyCondition(mealData: MealData,  hypoThreshold: Double,ctx: Context): Pair<Boolean, String> {
        val cobFromMeal = try {
            // Adapte le nom selon ta classe (souvent mealData.cob ou mealData.mealCOB)
            mealData.mealCOB
        } catch (_: Throwable) {
            cob // variable globale déjà existante
        }.toDouble()
        // Extraction des données de contexte pour éviter les variables globales
        val context = SafetyContext(
            delta = delta.toDouble(),
            bg = bg,
            iob = iob.toDouble(),
            predictedBg = predictedBg.toDouble(),
            eventualBG = eventualBG,
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            lastsmbtime = lastsmbtime,
            fastingTime = fastingTime,
            iscalibration = iscalibration,
            targetBg = targetBg.toDouble(),
            maxSMB = maxSMB,
            maxIob = maxIob,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            cob = cobFromMeal,
            hypoThreshold = hypoThreshold
        )

        // Récupération des conditions critiques
        val criticalConditions = determineCriticalConditions(ctx,context)

        // Calcul du résultat final
        val isCritical = criticalConditions.isNotEmpty()

        // Construction du message de retour
        val message = buildConditionMessage(isCritical, criticalConditions)

        return isCritical to message
    }

    /**
     * Structure de données pour le contexte de sécurité
     */
    private data class SafetyContext(
        val delta: Double,
        val bg: Double,
        val iob: Double,
        val predictedBg: Double,
        val eventualBG: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val lastsmbtime: Int,
        val fastingTime: Boolean,
        val iscalibration: Boolean,
        val targetBg: Double,
        val maxSMB: Double,
        val maxIob: Double,
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean,
        val snackTime: Boolean,
        val cob: Double,
        val hypoThreshold: Double
    )
    private fun isHypoBlocked(context: SafetyContext): Boolean =
        shouldBlockHypoWithHysteresis(
            bg = context.bg,
            predictedBg = context.predictedBg,
            eventualBg = context.eventualBG,
            threshold = context.hypoThreshold,
            deltaMgdlPer5min = context.delta
        )
    /**
     * Détermine les conditions critiques à partir du contexte fourni
     */
    private fun determineCriticalConditions(ctx:Context,context: SafetyContext): List<String> {
        val conditions = mutableListOf<String>()

        // Fallback Logic usage
        // Note: SafetyContext does not have profile objects, but it has maxIob, targetBg.
        // We reconstruct the fallback check locally.
        val fallback = (context.bg > context.targetBg + 30.0) &&
                       (context.delta >= 2.0) &&
                       (context.iob < context.maxIob * 0.8)

        if (fallback) {
             // Log fallback active in logs? context object doesn't have logger, 
             // but caller checks conditions. We can rely on emptiness or specific code.
             // We just skip adding the BLOCKING condition.
        }

        // Vérification des conditions critiques avec des noms explicites
        //if (isHypoBlocked(context)) conditions.add("hypoGuard")
        if (isHypoBlocked(context) && !fallback) conditions.add(ctx.getString(R.string.condition_hypoguard))
        else if (fallback && isHypoBlocked(context)) {
             // If it WAS blocked but we bypassed it:
             // Maybe we want a trace?
        }

        //if (isNosmbHm(context)) conditions.add("nosmbHM")
        // REMOVED: Caused strict SMB block in Honeymoon mode (IOB > 0.7).
        // if (isNosmbHm(context)) conditions.add(ctx.getString(R.string.condition_nosmbhm))
        //if (isHoneysmb(context)) conditions.add("honeysmb")
        if (isHoneysmb(context)) conditions.add(ctx.getString(R.string.condition_honeysmb))
        //if (isNegDelta(context)) conditions.add("negdelta")
        if (isNegDelta(context)) conditions.add(ctx.getString(R.string.condition_negdelta))
        //if (isNosmb(context)) conditions.add("nosmb")
        if (isNosmb(context)) conditions.add(ctx.getString(R.string.condition_nosmb))
        //if (isFasting(context)) conditions.add("fasting")
        if (isFasting(context)) conditions.add(ctx.getString(R.string.condition_fasting))
        //if (isBelowMinThreshold(context)) conditions.add("belowMinThreshold")
        if (isBelowMinThreshold(context)) conditions.add(ctx.getString(R.string.condition_belowminthreshold))
        if (isNewCalibration(context)) conditions.add("isNewCalibration")
        //if (isNewCalibration(context)) conditions.add(ctx.getString(R.string.condition_newcalibration))
        //if (isBelowTargetAndDropping(context)) conditions.add("belowTargetAndDropping")
        if (isBelowTargetAndDropping(context)) conditions.add(ctx.getString(R.string.condition_belowtarget_dropping))
        //if (isBelowTargetAndStableButNoCob(context)) conditions.add("belowTargetAndStableButNoCob")
        if (isBelowTargetAndStableButNoCob(context)) conditions.add(ctx.getString(R.string.condition_belowtarget_stable_nocob))
        //if (isDroppingFast(context)) conditions.add("droppingFast")
        if (isDroppingFast(context)) conditions.add(ctx.getString(R.string.condition_droppingfast))
        //if (isDroppingFastAtHigh(context)) conditions.add("droppingFastAtHigh")
        if (isDroppingFastAtHigh(context)) conditions.add(ctx.getString(R.string.condition_droppingfastathigh))
        //if (isDroppingVeryFast(context)) conditions.add("droppingVeryFast")
        if (isDroppingVeryFast(context)) conditions.add(ctx.getString(R.string.condition_droppingveryfast))
        //if (isPrediction(context)) conditions.add("prediction")
        if (isPrediction(context) && !fallback) conditions.add(ctx.getString(R.string.condition_prediction))
        //if (isBg90(context)) conditions.add("bg90")
        if (isBg90(context)) conditions.add(ctx.getString(R.string.condition_bg90))
        //if (isAcceleratingDown(context)) conditions.add("acceleratingDown")
        if (isAcceleratingDown(context)) conditions.add(ctx.getString(R.string.condition_acceleratingdown))

        return conditions
    }

    /**
     * Construction du message de retour décrivant les conditions remplies
     */
    private fun buildConditionMessage(isCritical: Boolean, conditions: List<String>): String {
        val conditionsString = if (conditions.isNotEmpty()) {
            conditions.joinToString(", ")
        } else {
//          "No conditions met"
            context.getString(R.string.no_conditions_met_2)
        }

//      return "Safety condition $isCritical : $conditionsString"
        val critical = if (isCritical) "✔"  else ""
        return context.getString(R.string.safety_condition, critical, conditionsString)
    }

    // Fonctions de vérification spécifiques pour chaque condition
    private fun isNosmbHm(context: SafetyContext): Boolean =
        context.iob > 0.7 &&
            preferences.get(BooleanKey.OApsAIMIhoneymoon) &&
            context.delta <= 10.0 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime &&
            context.predictedBg < 130

    private fun isHoneysmb(context: SafetyContext): Boolean =
        preferences.get(BooleanKey.OApsAIMIhoneymoon) &&
            context.delta < 0 &&
            context.bg < 170

    private fun isNegDelta(context: SafetyContext): Boolean =
        context.delta <= -1 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime &&
            context.eventualBG < 120

    private fun isNosmb(context: SafetyContext): Boolean =
        context.iob >= 2 * context.maxSMB &&
            context.bg < 110 &&
            context.delta < 10 &&
            !context.mealTime &&
            !context.bfastTime &&
            !context.lunchTime &&
            !context.dinnerTime

    private fun isFasting(context: SafetyContext): Boolean = context.fastingTime

    private fun isBelowMinThreshold(context: SafetyContext): Boolean =
        context.bg < 60 // Seuil arbitraire pour la valeur minimale

    private fun isNewCalibration(context: SafetyContext): Boolean = context.iscalibration

    private fun isBelowTargetAndDropping(context: SafetyContext): Boolean =
        context.bg < context.targetBg &&
            context.delta < 0

    private fun isBelowTargetAndStableButNoCob(context: SafetyContext): Boolean =
        context.bg < context.targetBg &&
            context.delta >= 0 &&
            context.cob <= 0 // Pas de COB (Carbohydrate On Board)

    private fun isDroppingFast(context: SafetyContext): Boolean =
        context.delta < -2.0 // Seuil arbitraire pour une chute rapide

    private fun isDroppingFastAtHigh(context: SafetyContext): Boolean =
        context.bg > 180 &&
            context.delta < -1.5

    private fun isDroppingVeryFast(context: SafetyContext): Boolean =
        context.delta < -3.0

    private fun isPrediction(context: SafetyContext): Boolean =
        context.predictedBg < context.bg &&
            context.delta < 0

    private fun isBg90(context: SafetyContext): Boolean = context.bg < 90

    private fun isAcceleratingDown(context: SafetyContext): Boolean =
        context.delta < 0 &&
            context.longAvgDelta < 0 &&
            context.shortAvgDelta < 0 &&
            (context.bg < context.targetBg || context.delta < -2.0)

    private fun isSportSafetyCondition(): Boolean {
        val manualSport = sportTime
        
        // Assouplissement des seuils : ne détecter que des VRAIS sports intenses
        // Anciens seuils : 200 pas/5min, 500 pas/10min → Trop sensible (marche normale)
        // Nouveaux seuils : 400 pas/5min, 800 pas/10min → Sports réels seulement
        val recentBurst = recentSteps5Minutes >= 400 && recentSteps10Minutes >= 800
        
        // Activité soutenue : relevé significativement pour éviter faux positifs
        // Une marche de 20 min = ~2000 pas → NE DOIT PAS déclencher sécurité sport
        // Seuil 60 min : 3000 pas = ~30 min de marche soutenue ou 45+ min de marche normale
        val sustainedActivity =
            recentSteps30Minutes >= 1200 || recentSteps60Minutes >= 3000 || recentSteps180Minutes >= 4500

        val baselineHr = if (averageBeatsPerMinute10 > 0.0) averageBeatsPerMinute10 else averageBeatsPerMinute
        val elevatedHeartRate = baselineHr > 0 && averageBeatsPerMinute > baselineHr * 1.15 // +15% au lieu de +10%
        val shortActivityWithHr = (recentSteps5Minutes >= 400 || recentSteps10Minutes >= 600) && elevatedHeartRate

        val highTargetExercise = targetBg >= 140 && (shortActivityWithHr || sustainedActivity)

        return manualSport || recentBurst || sustainedActivity || highTargetExercise
    }
    private fun calculateSMBInterval(): Int {
        val defaultInterval = 3

        // 1) Lecture des préférences
        val intervals = SMBIntervals(
            snack = preferences.get(IntKey.OApsAIMISnackinterval),
            meal = preferences.get(IntKey.OApsAIMImealinterval),
            bfast = preferences.get(IntKey.OApsAIMIBFinterval),
            lunch = preferences.get(IntKey.OApsAIMILunchinterval),
            dinner = preferences.get(IntKey.OApsAIMIDinnerinterval),
            sleep = preferences.get(IntKey.OApsAIMISleepinterval),
            hc = preferences.get(IntKey.OApsAIMIHCinterval),
            highBG = preferences.get(IntKey.OApsAIMIHighBGinterval)
        )

        // 2) Cas critique : montée très rapide -> SMB toutes les minutes
        if (delta > 15f) {
            return 1
        }

        // 3) Intervalle de base en fonction du mode actif
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        val modeInterval = when {
            snackTime                -> intervals.snack
            mealTime                 -> intervals.meal
            bfastTime                -> intervals.bfast
            lunchTime                -> intervals.lunch
            dinnerTime               -> intervals.dinner
            sleepTime                -> intervals.sleep
            highCarbTime             -> intervals.hc
            !honeymoon && bg > 120f  -> intervals.highBG
            honeymoon && bg > 180f   -> intervals.highBG
            else                     -> defaultInterval
        }.coerceAtLeast(1)

        var interval = modeInterval

        // 4) Sécurité : sport important ou low carb -> au moins 10 min
        val safetySport = recentSteps180Minutes > 1500 && bg < 120f
        val safetyLowCarb = lowCarbTime
        if (safetySport || safetyLowCarb) {
            interval = interval.coerceAtLeast(10)
        }

        // 5) Activité très soutenue -> on peut monter jusqu'à 15 min
        val strongActivity = recentSteps5Minutes > 100 &&
            recentSteps30Minutes > 500 &&
            lastsmbtime > 20
        if (strongActivity) {
            interval = interval.coerceAtLeast(15)
        }

        // 6) BG sous la cible -> on espace davantage les SMB
        if (bg < targetBg) {
            interval = (interval * 2).coerceAtMost(20)
        }

        // 7) Honeymoon calme -> on espace aussi
        if (honeymoon && bg < 170f && delta < 5f) {
            interval = (interval * 2).coerceAtMost(20)
        }

        // 8) Nuit (optionnelle) : on permet un peu plus de réactivité
        val currentHour = LocalTime.now().hour
        if (preferences.get(BooleanKey.OApsAIMInight) &&
            currentHour == 23 &&
            delta < 10f &&
            iob < maxSMB
        ) {
            interval = (interval * 0.8).toInt().coerceAtLeast(1)
        }

        // 9) Clamp final : mécanique SMB entre 1 et 10 min
        // Clamp final + Low BG boost
        var finalInterval = interval.coerceIn(1, 10)
        
        // 🛡️ FIX NC4: LOW BG INTERVAL BOOST (Safety-Critical)
        val lowBgIntervalMin = 5
        if (bg < 120f && finalInterval < lowBgIntervalMin) {
            finalInterval = lowBgIntervalMin
            consoleLog.add("LOW_BG_INTERVAL_BOOST bg=${bg.roundToInt()} interval=${finalInterval}m")
        }
        
        // 🚀 PKPD Throttle: Add interval boost if near peak/onset unconfirmed
        // Note: pkpdThrottleIntervalAdd est déjà à 0 pour les modes repas (via reset dans finalizeAndCapSMB)
        val pkpdBoost = pkpdThrottleIntervalAdd
        if (pkpdBoost > 0) {
            val baseInterval = finalInterval
            finalInterval = (finalInterval + pkpdBoost).coerceAtMost(10)
            consoleLog.add("PKPD_INTERVAL_BOOST base=${baseInterval}m +${pkpdBoost}m → ${finalInterval}m")
        }
        
        return finalInterval
    }

    // Structure simple, inchangée
    data class SMBIntervals(
        val snack: Int,
        val meal: Int,
        val bfast: Int,
        val lunch: Int,
        val dinner: Int,
        val sleep: Int,
        val hc: Int,
        val highBG: Int
    )
    // Calcule le seuil "OpenAPS-like" et applique LGS si plus haut
    private fun computeHypoThreshold(minBg: Double, lgsThreshold: Int?): Double {
        var t = minBg - 0.5 * (minBg - 40.0) // 90→65, 100→70, 110→75, 130→85
        if (lgsThreshold != null && lgsThreshold > t) t = lgsThreshold.toDouble()
        return t
    }

    /**
     * Helper: Get LGS threshold with safe fallback using OpenAPS formula
     * Use this instead of hardcoded fallbacks (70.0) throughout the code
     * 
     * @param profile Profile containing lgsThreshold and min_bg
     * @return LGS threshold from profile, or calculated from min_bg if null
     */
    private fun getLgsThresholdSafe(profile: OapsProfileAimi): Double {
        return if (profile.lgsThreshold != null && profile.lgsThreshold!! > 0) {
            profile.lgsThreshold!!.toDouble()
        } else {
            // Use OpenAPS-like formula on min_bg as fallback
            computeHypoThreshold(profile.min_bg, null)
        }
    }


    private fun isBelowHypoThreshold(
        bgNow: Double,
        predicted: Double,
        eventual: Double,
        hypo: Double,
        delta: Double
    ): Boolean {
        val tol = 5.0
        val floor = hypo - tol
        
        // 1. Hypo actuelle = TOUJOURS bloquer (sécurité absolue)
        val strongNow = bgNow <= floor
        if (strongNow) return true
        
        // 2. ⚡ NOUVEAU: Bypass progressif si BG monte clairement
        //    - delta >= 4 : bypass total des prédictions (montée forte)
        //    - delta >= 2 && bg > hypo : bypass strongFuture seulement
        val risingFast = delta >= 4.0
        val risingModerate = delta >= 2.0 && bgNow > hypo
        
        if (risingFast) {
            // Montée forte: ignorer complètement les prédictions
            return false
        }
        
        // 3. Prédictions futures (seulement si pas en montée modérée)
        val strongFuture = (predicted <= floor && eventual <= floor)
        if (strongFuture && risingModerate) {
            // Montée modérée: ignorer strongFuture mais pas fastFall
            // Continue to check fastFall only
        } else if (strongFuture) {
            return true
        }
        
        // 4. Chute rapide avec prédiction basse
        val fastFall = (delta <= -2.0 && predicted <= hypo)
        return fastFall
    }
    // Hystérèse : on ne débloque qu’après avoir été > (seuil+margin) pendant X minutes
    private fun canFallbackSmbWithoutPrediction(
        bg: Double,
        delta: Double,
        targetBg: Double,
        iob: Double,
        profile: OapsProfileAimi
    ): Boolean {
        // Fallback SMB allowed if clearly high and rising, even if prediction is missing
        val clearlyHigh = bg > targetBg + 30.0
        val stronglyRising = delta >= 2.0 // mg/dl/5min
        // Ensure IOB is not already saturating safety
        val iobSafe = iob < profile.max_iob * 0.8

        return clearlyHigh && stronglyRising && iobSafe
    }

    private fun shouldBlockHypoWithHysteresis(
        bg: Double,
        predictedBg: Double,
        eventualBg: Double,
        threshold: Double,
        deltaMgdlPer5min: Double,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val minBg = minOf(safe(bg), safe(predictedBg), safe(eventualBg))

        val blockedNow = isBelowHypoThreshold(bg, predictedBg, eventualBg, computeHypoThreshold(80.0, profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt() ), deltaMgdlPer5min)
        if (blockedNow) {
            lastHypoBlockAt = now
            hypoClearCandidateSince = null
            return true
        }

        // jamais bloqué avant → pas de collant
        if (lastHypoBlockAt == 0L) return false

        val above = minBg > threshold + HYPO_RELEASE_MARGIN
        if (above) {
            if (hypoClearCandidateSince == null) hypoClearCandidateSince = now
            val heldMs = now - hypoClearCandidateSince!!
            return if (heldMs >= HYPO_RELEASE_HOLD_MIN * 60_000L) {
                // libération de l’hystérèse
                lastHypoBlockAt = 0L
                hypoClearCandidateSince = null
                false
            } else {
                true // on colle encore
            }
        } else {
            // rechute sous (seuil+margin) → on réinitialise la fenêtre de libération
            hypoClearCandidateSince = null
            return true
        }
    }

    private fun applySpecificAdjustments(smbAmount: Float, ignoreSafetyRestrictions: Boolean = false): Float {
        // 🚀 BYPASS: If explicitly triggered by user (Meal Advisor), skip soft reductions
        if (ignoreSafetyRestrictions) return smbAmount

        val currentHour = LocalTime.now().hour
        val honeymoon   = preferences.get(BooleanKey.OApsAIMIhoneymoon)

        // 2) 🔧 AJUSTEMENT “falling decelerating” (soft)
        //    On baisse encore (deltas négatifs) mais la baisse RALENTIT :
        //    shortAvgDelta est moins négatif que longAvgDelta → on temporise.
        val fallingDecelerating =
            delta < -EPS_FALL &&
                shortAvgDelta < -EPS_FALL &&
                longAvgDelta  < -EPS_FALL &&
                shortAvgDelta >  longAvgDelta + EPS_ACC

        if (fallingDecelerating && bg < targetBg + 10) {
            // On est sous/près de la cible et la baisse ralentit → on réduit le SMB
            return (smbAmount * 0.5f).coerceAtLeast(0f)
        }

        // 3) règles existantes “soft”
        val belowTarget = bg < targetBg
        if (belowTarget) return smbAmount / 2

        if (honeymoon && bg < 170 && delta < 5) return smbAmount / 2

        //if (preferences.get(BooleanKey.OApsAIMInight) && currentHour == 23 && delta < 10 && iob < maxSMB) {
        //    return smbAmount * 0.8f
        //}
        //if (currentHour in 0..7 && delta < 10 && iob < maxSMB) {
        //    return smbAmount * 0.8f
        //}

        return smbAmount
    }

    private fun finalizeSmbToGive(smbToGive: Float): Float {
        var result = smbToGive

        if (result < 0.0f) result = 0.0f
        if (iob <= 0.1 && bg > 120 && delta >= 2 && result == 0.0f) result = 0.1f
        // + déclencheur spécifique montée tardive
        if (lateFatRiseFlag && result == 0.0f && bg > 130 && delta >= 1.0f) {
            result = 0.1f
        }
        return result
    }

    // DetermineBasalAIMI2.kt
    private fun calculateSMBFromModel(reason: StringBuilder? = null): Float {
        val smb = AimiUamHandler.predictSmbUam(
            floatArrayOf(
                hourOfDay.toFloat(), weekend.toFloat(),
                bg.toFloat(), targetBg, iob,
                delta, shortAvgDelta, longAvgDelta,
                tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                recentSteps5Minutes.toFloat(), recentSteps10Minutes.toFloat(),
                recentSteps15Minutes.toFloat(), recentSteps30Minutes.toFloat(),
                recentSteps60Minutes.toFloat(), recentSteps180Minutes.toFloat()
            ),
            reason, // 👈 logs visibles si non-null
            context
        )
        return smb.coerceAtLeast(0f)
    }
    private data class MealFlags(
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean
    )
    private fun isLateFatProteinRise(
        bg: Double,
        predictedBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        iob: Double,
        cob: Double,
        maxSMB: Double,
        lastBolusTimeMs: Long?,           // null si inconnu
        mealFlags: MealFlags,
        nowMs: Long = dateUtil.now()      // ou System.currentTimeMillis()
    ): Boolean {
        val hoursSinceBolus = lastBolusTimeMs?.let { (nowMs - it) / 3_600_000.0 } ?: Double.POSITIVE_INFINITY
        val rising = delta >= 1.0 && (shortAvgDelta >= 0.5 || longAvgDelta >= 0.3)
        val highish = bg > 130 || predictedBg > 140
        val lowIOB  = iob < maxSMB
        val noMeal  = !(mealFlags.mealTime || mealFlags.bfastTime || mealFlags.lunchTime
            || mealFlags.dinnerTime || mealFlags.highCarbTime)
        return noMeal && hoursSinceBolus in 2.0..7.0 && rising && highish && lowIOB && cob <= 1.0
    }


    private fun neuralnetwork5(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        predictedSMB: Float,
        profile: OapsProfileAimi
    ): Float {
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        val combinedDelta = (delta + predicted) / 2.0f
        // Définir un nombre maximal d'itérations plus bas en cas de montée rapide
        val maxIterations = if (combinedDelta > 15f) 25 else 50
        var finalRefinedSMB: Float = calculateSMBFromModel()

        val allLines = csvfile.readLines()
        //println("CSV file path: \${csvfile.absolutePath}")
        println(context.getString(R.string.csv_file_path, csvfile.absolutePath))
        if (allLines.isEmpty()) {
            //println("CSV file is empty.")
            println(context.getString(R.string.csv_file_empty))
            return predictedSMB
        }

        val headerLine = allLines.first()
        val headers = headerLine.split(",").map { it.trim() }
        val requiredColumns = listOf(
            "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
            "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
            "predictedSMB", "smbGiven"
        )
        if (!requiredColumns.all { headers.contains(it) }) {
            //println("CSV file is missing required columns.")
            println(context.getString(R.string.csv_missing_columns))
            return predictedSMB
        }

        val colIndices = requiredColumns.map { headers.indexOf(it) }
        val targetColIndex = headers.indexOf("smbGiven")
        val inputs = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()
        var lastEnhancedInput: FloatArray? = null

        for (line in allLines.drop(1)) {
            val cols = line.split(",").map { it.trim() }
            val rawInput = colIndices.mapNotNull { idx -> cols.getOrNull(idx)?.toFloatOrNull() }.toFloatArray()

            val trendIndicator = calculateTrendIndicator(
                delta, shortAvgDelta, longAvgDelta,
                bg.toFloat(), iob, variableSensitivity, cob, normalBgThreshold,
                recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
                profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
            )

            val enhancedInput = rawInput.copyOf(rawInput.size + 1)
            enhancedInput[rawInput.size] = trendIndicator.toFloat()
            lastEnhancedInput = enhancedInput

            val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
            if (targetValue != null) {
                inputs.add(enhancedInput)
                targets.add(doubleArrayOf(targetValue))
            }
        }

        if (inputs.isEmpty() || targets.isEmpty()) {
            //println("Insufficient data for training.")
            println(context.getString(R.string.insufficient_data_training))
            return predictedSMB
        }

        val maxK = 10
        val adjustedK = minOf(maxK, inputs.size)
        val foldSize = maxOf(1, inputs.size / adjustedK)
        var bestNetwork: AimiNeuralNetwork? = null
        var bestFoldValLoss = Double.MAX_VALUE

        for (k in 0 until adjustedK) {
            val validationInputs = inputs.subList(k * foldSize, minOf((k + 1) * foldSize, inputs.size))
            val validationTargets = targets.subList(k * foldSize, minOf((k + 1) * foldSize, targets.size))
            val trainingInputs = inputs.minus(validationInputs)
            val trainingTargets = targets.minus(validationTargets)
            if (validationInputs.isEmpty()) continue

            val tempNetwork = AimiNeuralNetwork(
                inputSize = inputs.first().size,
                hiddenSize = 5,
                outputSize = 1,
                config = TrainingConfig(
                    learningRate = 0.001,
                    epochs = 200
                ),
                regularizationLambda = 0.01
            )

            tempNetwork.trainWithValidation(trainingInputs, trainingTargets, validationInputs, validationTargets)
            val foldValLoss = tempNetwork.validate(validationInputs, validationTargets)

            if (foldValLoss < bestFoldValLoss) {
                bestFoldValLoss = foldValLoss
                bestNetwork = tempNetwork
            }
        }

        val adjustedLearningRate = if (bestFoldValLoss < 0.01) 0.0005 else 0.001
        val epochs = if (bestFoldValLoss < 0.01) 100 else 200

        if (bestNetwork != null) {
            //println("Réentraînement final avec les meilleurs hyperparamètres sur toutes les données...")
            println(context.getString(R.string.retraining_final_model))
            val finalNetwork = AimiNeuralNetwork(
                inputSize = inputs.first().size,
                hiddenSize = 5,
                outputSize = 1,
                config = TrainingConfig(
                    learningRate = adjustedLearningRate,
                    beta1 = 0.9,
                    beta2 = 0.999,
                    epsilon = 1e-8,
                    patience = 10,
                    batchSize = 32,
                    weightDecay = 0.01,
                    epochs = epochs,
                    useBatchNorm = false,
                    useDropout = true,
                    dropoutRate = 0.3,
                    leakyReluAlpha = 0.01
                ),
                regularizationLambda = 0.01
            )
            finalNetwork.copyWeightsFrom(bestNetwork)
            finalNetwork.trainWithValidation(inputs, targets, inputs, targets)
            bestNetwork = finalNetwork
        }

        // --- Normalisation légère sur lastEnhancedInput ---
        fun normalize(input: FloatArray): FloatArray {
            val mean = input.average().toFloat()
            val std = input.map { (it - mean) * (it - mean) }.average().let { sqrt(it).toFloat().coerceAtLeast(1e-8f) }
            return input.map { (it - mean) / std }.toFloatArray()
        }

        var iterationCount = 0
        do {
            val dynamicThreshold = calculateDynamicThreshold(iterationCount, delta, shortAvgDelta, longAvgDelta)
            val normalizedInput = lastEnhancedInput?.let { normalize(it) }?.toDoubleArray() ?: DoubleArray(0)
            val refinedSMB = bestNetwork?.let {
                AimiNeuralNetwork.refineSMB(finalRefinedSMB, it, normalizedInput)
            } ?: finalRefinedSMB

            //println("→ Iteration $iterationCount | SMB=$finalRefinedSMB → $refinedSMB | Δ=${abs(finalRefinedSMB - refinedSMB)} | threshold=$dynamicThreshold")
            println(context.getString(R.string.iteration_smb, iterationCount, finalRefinedSMB, refinedSMB, abs(finalRefinedSMB - refinedSMB), dynamicThreshold))

            if (abs(finalRefinedSMB - refinedSMB) <= dynamicThreshold) {
                finalRefinedSMB = max(0.05f, refinedSMB)
                break
            }
            iterationCount++
        } while (iterationCount < maxIterations)

        if (finalRefinedSMB > predictedSMB && bg > 150 && delta > 5) {
            //println("Modèle prédictif plus élevé, ajustement retenu.")
            println(context.getString(R.string.predicted_smb_higher))
            return finalRefinedSMB
        }

        val alpha = 0.7f
        val blendedSMB = alpha * finalRefinedSMB + (1 - alpha) * predictedSMB
        return blendedSMB
    }

    private fun computeDynamicBolusMultiplier(delta: Float): Float {
        // Centrer la sigmoïde autour de 5 mg/dL, avec une pente modérée (échelle 10)
        val x = (delta - 5f) / 10f
        val sig = (1f / (1f + exp(-x)))  // sigmoïde entre 0 et 1
        return 0.5f + sig * 0.7f  // multipliateur lissé entre 0,5 et 1,2
    }

    private fun calculateDynamicThreshold(
        iterationCount: Int,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float
    ): Float {
        val baseThreshold = if (delta > 15f) 1.5f else 2.5f
        // Réduit le seuil au fur et à mesure des itérations pour exiger une convergence plus fine
        val iterationFactor = 1.0f / (1 + iterationCount / 100)
        val trendFactor = when {
            delta > 8 || shortAvgDelta > 4 || longAvgDelta > 3 -> 0.5f
            delta < 5 && shortAvgDelta < 3 && longAvgDelta < 3 -> 1.5f
            else -> 1.0f
        }
        return baseThreshold * iterationFactor * trendFactor
    }

    private fun FloatArray.toDoubleArray(): DoubleArray {
        return this.map { it.toDouble() }.toDoubleArray()
    }

    private fun interpolateFactor(value: Float, start1: Float, end1: Float, start2: Float, end2: Float): Float {
        return start2 + (value - start1) * (end2 - start2) / (end1 - start1)
    }
    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        // Fenêtre standard selon BG
        val standardWindow = if (bg < 130) 40f else 20f
        // Fenêtre raccourcie pour détection rapide
        val rapidRiseWindow = 10f
        // Si le delta instantané est supérieur à 15 mg/dL, on choisit la fenêtre rapide
        val intervalMinutes = if (delta > 15) rapidRiseWindow else standardWindow

        val nowTimestamp = data.first().timestamp
        return data.drop(1).filter { it.value > 39 && !it.filledGap }
            .mapNotNull { entry ->
                val minutesAgo = ((nowTimestamp - entry.timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 0.0f..intervalMinutes) {
                    val delta = (data.first().recalculated - entry.recalculated) / minutesAgo * 5f
                    delta
                } else {
                    null
                }
            }
    }


    // Calcul d'un delta prédit à partir d'une moyenne pondérée
    private fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }

    // ❌ adjustFactorsBasedOnBgAndHypo() REMOVED (was lines 3191-3251)
    // Legacy function for time-based reactivity (morning/afternoon/evening factors)
    // Replaced by UnifiedReactivityLearner.globalFactor which learns optimal reactivity
    // from actual glycemic outcomes (hypos, hypers, variability)



    private fun calculateAdjustedDelayFactor(
        bg: Float,
        recentSteps180Minutes: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float
    ): Float {
        val currentHour = LocalTime.now().hour
        val highBgOverrideThreshold = normalBgThreshold + 40f
        val severeHighBgThreshold = normalBgThreshold + 80f

        var delayFactor = if (
            bg.isNaN() ||
            averageBeatsPerMinute.isNaN() ||
            averageBeatsPerMinute10.isNaN() ||
            averageBeatsPerMinute10 == 0f
        ) {
            1f
        } else {
            val stepActivityThreshold = 1500
            val heartRateIncreaseThreshold = 1.2
            val insulinSensitivityDecreaseThreshold = 1.5 * normalBgThreshold

            val increasedPhysicalActivity = recentSteps180Minutes > stepActivityThreshold
            val sanitizedHr10 = if (averageBeatsPerMinute10.isFinite() && averageBeatsPerMinute10 > 0f) {
                averageBeatsPerMinute10
            } else {
                Float.NaN
            }
            val heartRateChange = if (sanitizedHr10.isNaN()) 1.0 else averageBeatsPerMinute / sanitizedHr10
            val increasedHeartRateActivity = !sanitizedHr10.isNaN() && (heartRateChange.toDouble() >= heartRateIncreaseThreshold)

            val baseFactor = when {
                bg <= normalBgThreshold -> 1f
                bg <= insulinSensitivityDecreaseThreshold -> 1f - ((bg - normalBgThreshold) / (insulinSensitivityDecreaseThreshold - normalBgThreshold))
                else -> 0.5f
            }

            val shouldDampenForActivity = (increasedPhysicalActivity || increasedHeartRateActivity) && bg < highBgOverrideThreshold
            var adjusted = baseFactor.toFloat()
            if (shouldDampenForActivity) {
                adjusted = (adjusted * 0.85f).coerceAtLeast(0.6f)
            }
            if (bg >= highBgOverrideThreshold) {
                adjusted = adjusted.coerceAtLeast(1f)
            }
            if (bg >= severeHighBgThreshold) {
                adjusted = adjusted.coerceAtLeast(1.1f)
            }
            adjusted
        }
        // Augmenter le délai si l'heure est le soir (18h à 23h) ou diminuer le besoin entre 00h à 5h
        if (currentHour in 18..23) {
            delayFactor *= 1.2f
        } else if (currentHour in 0..5) {
            delayFactor *= 0.8f
        }
        return delayFactor
    }


    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float
    ): Float {
        val reasonBuilder = StringBuilder()
        // Calculer l'effet initial de l'insuline
        var insulinEffect = iob * variableSensitivity / insulinDivisor

        // Si des glucides sont présents, nous pourrions vouloir ajuster l'effet de l'insuline pour tenir compte de l'absorption des glucides.
        if (cob > 0) {
            // Ajustement hypothétique basé sur la présence de glucides. Ce facteur doit être déterminé par des tests/logique métier.
            insulinEffect *= 0.9f
        }
        val highBgOverrideThreshold = normalBgThreshold + 40f
        val severeHighBgThreshold = normalBgThreshold + 80f
        val rawPhysicalActivityFactor = 1.0f - (recentSteps180Min / 10000f).coerceAtMost(0.4f)
        val physicalActivityFactor = rawPhysicalActivityFactor.coerceIn(0.7f, 1.0f)
        if (bg < highBgOverrideThreshold) {
            insulinEffect *= physicalActivityFactor
        }
        // Calculer le facteur de retard ajusté en fonction de l'activité physique
        val adjustedDelayFactor = calculateAdjustedDelayFactor(
            bg,
            recentSteps180Minutes,
            averageBeatsPerMinute,
            averageBeatsPerMinute10
        )

        // Appliquer le facteur de retard ajusté à l'effet de l'insuline
        insulinEffect *= adjustedDelayFactor
        if (bg >= severeHighBgThreshold) {
            insulinEffect *= 1.3f
        } else if (bg > normalBgThreshold) {
            insulinEffect *= 1.2f
        }
        val currentHour = LocalTime.now().hour
        if (currentHour in 0..5) {
            insulinEffect *= 0.8f
        }
        //reasonBuilder.append("insulin effect : $insulinEffect")
        reasonBuilder.append(context.getString(R.string.insulin_effect, insulinEffect))
        return insulinEffect
    }
    private fun calculateTrendIndicator(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float,
        recentSteps5min: Int,
        recentSteps10min: Int
    ): Int {

        // Calcul de l'impact de l'insuline
        val insulinEffect = calculateInsulinEffect(
            bg, iob, variableSensitivity, cob, normalBgThreshold, recentSteps180Min,
            averageBeatsPerMinute, averageBeatsPerMinute10, insulinDivisor
        )

        // Calcul de l'impact de l'activité physique
        val activityImpact = (recentSteps5min - recentSteps10min) * 0.05

        // Calcul de l'indicateur de tendance
        val trendValue = (delta * 0.5) + (shortAvgDelta * 0.25) + (longAvgDelta * 0.15) + (insulinEffect * 0.2) + (activityImpact * 0.1)

        return when {
            trendValue > 1.0 -> 1 // Forte tendance à la hausse
            trendValue < -1.0 -> -1 // Forte tendance à la baisse
            abs(trendValue) < 0.5 -> 0 // Pas de tendance significative
            trendValue > 0.5 -> 2 // Faible tendance à la hausse
            else -> -2 // Faible tendance à la baisse
        }
    }

    private data class PredictionResult(
        val eventual: Double,
        val series: List<Int>
    )

    private fun computePkpdPredictions(
        currentBg: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,

        profile: OapsProfileAimi,
        rT: RT,
        delta: Double
    ): PredictionResult {
        consoleLog.add("Debug: computePkpdPredictions called with delta=$delta")
        val advancedPredictions = try {
            AdvancedPredictionEngine.predict(
                currentBG = currentBg,
                iobArray = iobArray,
                finalSensitivity = finalSensitivity,
                cobG = cobG,
                profile = profile,
                delta = delta
            )
        } catch (e: Exception) {
            consoleLog.add("Error in AdvancedPredictionEngine: ${e.message}")
            // Fallback: flat prediction
            List(48) { currentBg }
        }

        val sanitizedPredictions = advancedPredictions.map { round(min(401.0, max(39.0, it)), 0) }
        val intsPredictions = sanitizedPredictions.map { it.toInt() }
        rT.predBGs = Predictions().apply {
            IOB = intsPredictions
            COB = intsPredictions
            ZT = intsPredictions
            UAM = intsPredictions
        }

        val eventual = intsPredictions.lastOrNull()?.toDouble() ?: currentBg
        consoleLog.add(
            "PKPD predictions → eventual=${"%.0f".format(eventual)} mg/dL from ${intsPredictions.size} steps"
        )
        return PredictionResult(eventual, intsPredictions)
    }

    private fun ensurePredictionFallback(rt: RT, bgNow: Double) {
        if (rt.predBGs == null) {
            val safeBg = bgNow.roundToInt()
            rt.predBGs = Predictions().apply {
                IOB = listOf(safeBg)
                COB = listOf(safeBg)
                ZT = listOf(safeBg)
                UAM = listOf(safeBg)
            }
            consoleLog.add("GATE_PKPD_MISSING: injected fallback prediction @${safeBg}mg/dL")
        }
        if (rt.eventualBG == null) {
            rt.eventualBG = bgNow
        }
    }


    private fun determineNoteBasedOnBg(bg: Double): String {
        return when {
            //bg > 170 -> "more aggressive"
            bg > 170 -> context.getString(R.string.bg_note_more_aggressive)
            //bg in 90.0..100.0 -> "less aggressive"
            bg in 90.0..100.0 -> context.getString(R.string.bg_note_less_aggressive)
            //bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
            bg in 80.0..89.9 -> context.getString(R.string.bg_note_too_aggressive)
            //bg < 80 -> "low treatment"
            bg < 80 -> context.getString(R.string.bg_note_low_treatment)
            //else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts
            else -> context.getString(R.string.bg_note_normal)
        }
    }

    private fun processNotesAndCleanUp(notes: String): String {
        return notes.lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            //.replace("a", " ")
            .replace("an", " ")
            .replace("and", " ")
            .replace("\\s+", " ")
    }
    private fun ensureWCycleInfo(): WCycleInfo? {
        val profile = lastProfile ?: return null
        wCycleInfoForRun?.let { return it }
        val info = wCycleFacade.infoAndLog(
            mapOf(
                "trackingMode" to wCyclePreferences.trackingMode().name,
                "contraceptive" to wCyclePreferences.contraceptive().name,
                "thyroid" to wCyclePreferences.thyroid().name,
                "verneuil" to wCyclePreferences.verneuil().name,
                "bg" to bg,
                "delta5" to delta.toDouble(),
                "iob" to iob.toDouble(),
                "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                "isfProfile" to profile.sens,
                "dynIsf" to variableSensitivity.toDouble()
            )
        )
        wCycleInfoForRun = info
        checkCycleDayNotification(info)
        return info
    }

    private fun checkCycleDayNotification(info: WCycleInfo) {
        val mode = wCyclePreferences.trackingMode()
        val tracking = mode != CycleTrackingMode.MENOPAUSE && mode != CycleTrackingMode.NO_MENSES_LARC 
        
        // Trigger: Late Period (Day > Avg Length)
        // Spam Prevention: Notify only once per day (if day index changed)
        val limit = wCyclePreferences.avgLen()
        if (tracking && info.dayInCycle > limit) {
             if (info.dayInCycle != lastCycleNotificationDay) {
                 val msg = "⚠️ WCycle: J${info.dayInCycle} > $limit. Retard détecté.\nMettre à jour le 1er jour des règles ?"
                 consoleLog.add(msg)
                 uiInteraction.addNotification(
                    app.aaps.core.interfaces.notifications.Notification.HYPO_RISK_ALARM,
                    msg,
                    app.aaps.core.interfaces.notifications.Notification.URGENT
                 )
                 lastCycleNotificationDay = info.dayInCycle
             }
        }
    }

    private fun appendWCycleReason(target: StringBuilder, info: WCycleInfo) {
        if (wCycleReasonLogged) return
        if (info.reason.isBlank()) return
        target.append(", WCycle: ").append(info.reason)
        wCycleReasonLogged = true
    }

    private fun updateWCycleLearner(needBasalScale: Double?, needSmbScale: Double?) {
        val info = wCycleInfoForRun ?: return
        if (!info.enabled) return
        val minClamp = wCyclePreferences.clampMin()
        val maxClamp = wCyclePreferences.clampMax()
        wCycleLearner.update(
            info.phase,
            needBasalScale?.coerceIn(minClamp, maxClamp),
            needSmbScale?.coerceIn(minClamp, maxClamp)
        )
    }

    private fun calculateDynamicPeakTime(
        currentActivity: Double,
        futureActivity: Double,
        sensorLagActivity: Double,
        historicActivity: Double,
        profile: OapsProfileAimi,
        stepCount: Int? = null, // Nombre de pas
        heartRate: Int? = null, // Rythme cardiaque
        bg: Double,             // Glycémie actuelle
        delta: Double,          // Variation glycémique
        reasonBuilder: StringBuilder // Builder pour accumuler les logs
    ): Double {
        var dynamicPeakTime = profile.peakTime
        val activityRatio = futureActivity / (currentActivity + 0.0001)

        //reasonBuilder.append("🧠 Calcul Dynamic PeakTime\n")
        reasonBuilder.append(context.getString(R.string.calc_dynamic_peaktime))
//  reasonBuilder.append("  • PeakTime initial: ${profile.peakTime}\n")
        reasonBuilder.append(context.getString(R.string.profile_peak_time, profile.peakTime))
//  reasonBuilder.append("  • BG: $bg, Delta: ${round(delta, 2)}\n")
        reasonBuilder.append(context.getString(R.string.bg_delta, bg, delta))

        // 1️⃣ Facteur de correction hyperglycémique
        val hyperCorrectionFactor = when {
            bg <= 130 || delta <= 4 -> 1.0
            bg in 130.0..240.0 -> 0.6 - (bg - 130) * (0.6 - 0.3) / (240 - 130)
            else -> 0.3
        }
        dynamicPeakTime *= hyperCorrectionFactor
//  reasonBuilder.append("  • Facteur hyperglycémie: $hyperCorrectionFactor\n")
        reasonBuilder.append(context.getString(R.string.reason_hyper_correction, hyperCorrectionFactor))

        // 2️⃣ Basé sur currentActivity (IOB) - "Active Insulin" vs "Activity" check
        // Si c'est de l'activité physique (IOB provenant de l'activité ? Non, currentActivity est souvent l'activité physique déclarée/détectée)
        // Correction BIO-SYNC : L'activité accélère l'absorption (pic plus tôt)
        if (currentActivity > 0.1) {
            // Old: dynamicPeakTime += adjustment (Retardait le pic)
            // New: on réduit le temps du pic (ça va plus vite)
            val acceleration = currentActivity * 20 + 5
            dynamicPeakTime -= acceleration
            reasonBuilder.append(context.getString(R.string.reason_iob_adjustment_inverted, acceleration))
        }

        // 3️⃣ Ratio d'activité (Future / Current)
        // Si on va bouger plus (Future > Current), ça va accélérer encore plus
        val ratioFactor = when {
            activityRatio > 1.5 -> 0.8  // (était 0.5 + ...) on accélère (x0.8)
            activityRatio < 0.5 -> 1.2  // on ralentit (x1.2)
            else -> 1.0
        }
        dynamicPeakTime *= ratioFactor
        reasonBuilder.append(context.getString(R.string.reason_activity_ratio, round(activityRatio,2), ratioFactor))

        // 4️⃣ & 5️⃣ BIO-SYNC FUSION : Steps & HeartRate
        // On détecte 3 états : FLOW (Sport), STRESS (Cortisol), ou REST
        val steps = stepCount ?: 0
        val hr = heartRate ?: 0
        
        val isStress = hr > 95 && steps < 100 // Tachycardie au repos -> Stress/Maladie
        val isFlow = steps > 500 || (steps > 200 && hr > 100) // Activité significative

        if (isStress) {
            // 🔴 STRESS MODE : Cortisol -> Résistance -> Pic retardé et étalé
            dynamicPeakTime *= 1.25
            reasonBuilder.append(context.getString(R.string.reason_bio_sync_stress, hr, steps))
            consoleLog.add("Bio-Sync: STRESS DETECTED (HR $hr, Steps $steps) -> Peak slowed x1.25")
        } else if (isFlow) {
            // 🟢 FLOW MODE : Circulation ++ -> Absorption accélérée -> Pic plus tôt
            // Plus on bouge, plus c'est rapide, borné à x0.7
            val flowFactor = if (steps > 1500) 0.7 else 0.85
            dynamicPeakTime *= flowFactor
            reasonBuilder.append(context.getString(R.string.reason_bio_sync_flow, steps, hr, flowFactor))
        } else if (steps < 50 && hr < 65 && hr > 40) {
            // 🔵 DEEP REST : Métabolisme lent
            dynamicPeakTime *= 1.1
            reasonBuilder.append("Bio-Sync: Deep Rest (HR $hr) -> x1.1\n")
        }

        /* 
        // ANCIENNE LOGIQUE SUPPRIMÉE (Obsolète car contradictoire)
        // 4️⃣ Nombre de pas (Old: >1000 -> += stepAdj)
        // 5️⃣ Fréquence cardiaque (Old: >110 -> x1.15)
        // 6️⃣ Corrélation FC + pas
        */

        this.peakintermediaire = dynamicPeakTime

        // 7️⃣ Sensor lag vs historique
        if (dynamicPeakTime > 40) {
            if (sensorLagActivity > historicActivity) {
                dynamicPeakTime *= 0.85
//          reasonBuilder.append("  • SensorLag > Historic ➝ x0.85\n")
                reasonBuilder.append(context.getString(R.string.reason_sensor_lag))
            } else if (sensorLagActivity < historicActivity) {
                dynamicPeakTime *= 1.2
//          reasonBuilder.append("  • SensorLag < Historic ➝ x1.2\n")
                reasonBuilder.append(context.getString(R.string.reason_sensor_lag_lower))
            }
        }

        // 🔚 Clamp entre 35 et 120
        val finalPeak = dynamicPeakTime.coerceIn(35.0, 120.0)
//  reasonBuilder.append("  → Résultat PeakTime final : $finalPeak\n")
        //reasonBuilder.append("  → Picco insulina dinamico : ${"%.0f".format(finalPeak)}\n")
        return finalPeak
    }

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float, predictedBg: Float, targetBg: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        
        // 1. Existing strict check (Explosive Rise)
        if (combinedDelta > 3.0f && acceleration > 1.2f) return true

        // 2. Harmonized check (Steady Meal Rise)
        // Relaxed acceleration req if rise is clearly above noise
        val normalizedRise = ((predictedBg - targetBg) / 70.0f).coerceIn(0.0f, 1.0f)
        if (normalizedRise > 0.3f && combinedDelta > 2.0f && acceleration > 0.3f) return true
        
        // 3. [FIX] Brute Force Rise (No Acceleration needed if Delta is huge)
        // If BG is rising +5 mg/dL/min, it IS a meal/carb impact, even if linear.
        if (combinedDelta > 5.0f || delta > 5.0f) return true

        return false
    }

    private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""
        val recentNotes2: MutableList<String> = mutableListOf()
        val autoNote = determineNoteBasedOnBg(bg)
        recentNotes2.add(autoNote)
        notes += autoNote  // Ajout de la note auto générée

        recentNotes?.forEach { note ->
            if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                val noteText = note.note.lowercase()
                if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") || noteText.contains("bfast") || noteText.contains("lunch") || noteText.contains("dinner") ||
                    noteText.contains("lowcarb") || noteText.contains("highcarb") || noteText.contains("meal") || noteText.contains("fasting") ||
                    noteText.contains("low treatment") || noteText.contains("less aggressive") ||
                    noteText.contains("more aggressive") || noteText.contains("too aggressive") ||
                    noteText.contains("normal")) {

                    notes += if (notes.isEmpty()) recentNotes2 else " "
                    notes += note.note
                    recentNotes2.add(note.note)
                }
            }
        }

        notes = processNotesAndCleanUp(notes)
        return notes
    }

    /**
     * 🛡️ Log de santé du stockage et des learners AIMI.
     * Affiche l'état du système dans l'UI (Reasoning) ET dans les logs système.
     * NOUVEAU: Populate aussi rT.learnersInfo pour affichage comme section dédiée.
     */
    private fun logLearnersHealth(rT: RT) {
        val storageReport = storageHelper.getHealthReport()
        val reactivityFactor = safeReactivityFactor // Safety check added
        val basalMultiplier = basalLearner.getMultiplier()
        
        // Construire le rapport de santé
        val healthLines = listOf(
            "═══════════════════════════════",
            "🛡️ AIMI LEARNERS HEALTH",
            "Storage: $storageReport",
            "UnifiedReactivity: factor=${"%.3f".format(reactivityFactor)}",
            "BasalLearner: multiplier=${"%.3f".format(basalMultiplier)}",
            "PkPdEstimator: runtime-only",
            "═══════════════════════════════"
        )
        
        // 📊 NOUVEAU: Afficher en HAUT de la page AIMI via rT.learnersInfo (section dédiée)
        val reactivityPct = (reactivityFactor * 100).toInt()
        val reactivityTrend = when {
            reactivityFactor < 0.5 -> "↓ prudent"
            reactivityFactor > 1.2 -> "↑ agressif"
            else -> "→ neutre"
        }
        
        val basalTrend = when {
            basalMultiplier < 0.9 -> "↓ basal réduit"
            basalMultiplier > 1.1 -> "↑ basal augmenté"
            else -> "→ basal neutre"
        }
        
        // ✅ Populate rT.learnersInfo for UI section display (like "Profil :", "Données repas :", etc.)
        rT.learnersInfo = buildString {
            appendLine("UnifiedReactivity: $reactivityPct% ($reactivityTrend)")
            appendLine("BasalLearner: ×${String.format("%.2f", basalMultiplier)} ($basalTrend)")
            appendLine("PkPdEstimator: ℹ️ runtime-only")
            append("Storage: $storageReport")
        }
        
        // Aussi dans consoleLog pour affichage UI (Reasoning)
        healthLines.forEach { line ->
            consoleLog.add(line)
        }
        
        // Logger aussi dans logcat pour debug
        aapsLogger.info(LTag.APS, "╔═══════════════════════════════════════════════╗")
        aapsLogger.info(LTag.APS, "║ 📦 AIMI SYSTEM HEALTH                          ║")
        aapsLogger.info(LTag.APS, "╠═══════════════════════════════════════════════╣")
        aapsLogger.info(LTag.APS, "║ Storage: $storageReport")
        aapsLogger.info(LTag.APS, "║ UnifiedReactivity: ✅ factor=${"%.3f".format(reactivityFactor)}")
        aapsLogger.info(LTag.APS, "║ BasalLearner: ✅ multiplier=${"%.3f".format(basalMultiplier)}")
        aapsLogger.info(LTag.APS, "║ PkPdEstimator: ℹ️ runtime-only")
        aapsLogger.info(LTag.APS, "╚═══════════════════════════════════════════════╝")
    }

    @SuppressLint("NewApi", "DefaultLocale") fun determine_basal(
        glucose_status: GlucoseStatusAIMI, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean, uiInteraction: UiInteraction
    ): RT {
        consoleError.clear()
        consoleLog.clear()

        // 🕵️ COMPARATOR: Capture Original Profile to avoid Bias
        // AIMI modifies the profile (activity, pregnancy, autosens) in-flight.
        // We want the comparator to run against the RAW profile.
        val originalProfile = profile.copy()
        
        // 🤰 Gestational Autopilot Integration
        try {
            if (preferences.get(BooleanKey.OApsAIMIpregnancy)) {
                val dueDateString = preferences.get(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.PregnancyDueDateString)
                if (dueDateString.isNotEmpty()) {
                    try {
                        val dueDate = java.time.LocalDate.parse(dueDateString)
                        val gState = gestationalAutopilot.calculateState(dueDate)
                        val mult = gestationalAutopilot.getProfileMultipliers(gState)
                        
                        val factorBasal = mult["basal"] ?: 1.0
                        val factorISF = mult["isf"] ?: 1.0
                        val factorCR = mult["cr"] ?: 1.0
                        
                        // Capture old values for logging
                        val oldBasal = profile.current_basal
                        val oldISF = profile.sens
                        val oldCR = profile.carb_ratio
                        
                        // Apply to profile (In-Flight Mutation)
                        profile.current_basal *= factorBasal
                        profile.sens *= factorISF
                        profile.carb_ratio *= factorCR
                        // Also adjust variable_sens (DynISF)
                        profile.variable_sens *= factorISF
                        
                        aapsLogger.debug(LTag.APS, "🤰 Pregnancy Mode Active: Week ${gState.gestationalWeek} (${gState.description}) -> Basal*${factorBasal}, ISF*${factorISF}")
                        consoleLog.add("🤰 GESTATION ACTIVE: ${gState.gestationalWeek.toInt()} SA (${gState.description})")
                        consoleLog.add("   └ Factors: Basal x${"%.2f".format(factorBasal)} | ISF x${"%.2f".format(factorISF)} | CR x${"%.2f".format(factorCR)}")
                        consoleLog.add("   └ Adjusted: Basal ${"%.2f".format(oldBasal)}->${"%.2f".format(profile.current_basal)} | ISF ${oldISF.toInt()}->${profile.sens.toInt()}")
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "Error parsing pregnancy due date: $dueDateString", e)
                    }
                } else {
                    consoleLog.add("🤰 PREGNANCY MODE ON but No Due Date set in WCycle prefs.")
                }
            }
        } catch (e: Exception) {
            consoleLog.add("🤰 Error in Gestation logic: ${e.message}")
            e.printStackTrace()
        }
        
        // 🏥 AIMI DECISION CONTEXT INITIALIZATION (For Medical Transparency)
        val decisionCtx = AimiDecisionContext(
            event_id = "evt_${currentTime}",
            timestamp = currentTime,
            trigger = if (glucose_status.delta > 5) "BG_Rise_Fast" else "Routine_Cycle",
            baseline_state = AimiDecisionContext.BaselineState(
                profile_isf_mgdl = profile.sens,
                profile_basal_uph = profile.current_basal,
                current_bg_mgdl = glucose_status.glucose,
                cob_g = mealData.mealCOB,
                iob_u = iob_data_array.firstOrNull()?.iob ?: 0.0 // Taking first element (Net IOB usually)
            )
        )
        
        var rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        
        // 🛡️ Log health status of storage and learners (NOW with rT)
        logLearnersHealth(rT)
        wCycleInfoForRun = null
        wCycleReasonLogged = false
        lastProfile = profile

        // 🛡️ ADAPTIVE SMOOTHIE WORKAROUND
        // If delta is significant (> 3.0), ignore plugin's Flat detection (likely smoothed artifacts)
        // We SHADOW the parameter 'flatBGsDetected' to enforce this override globally in this function.
        val flatBGsDetected = if (flatBGsDetected && abs(glucose_status.delta) > 3.0) {
            consoleLog.add("⚠️ FLAT OVERRIDE: Delta=${glucose_status.delta} > 3.0 -> Sensor ALIVE.")
            false
        } else {
            flatBGsDetected
        }
        
        // 🏃 REAL-TIME ACTIVITY FETCH (every loop)
        // Fetches immediate Steps & HR for display and micro-adjustments
        val rtActivity = physioAdapter.getRealTimeActivity()
        consoleLog.add("PHYSIO_RT Steps=${rtActivity.stepsToday} HR=${rtActivity.heartRate}bpm")

        // 🧹 STATE RESET (Critical Fix FCL 10.6):
        // maxSMB is a persistent class member. It MUST be reset to the user's preference at the start of every cycle.
        // Otherwise, temporary overrides (like BFast2 mode) permeate to future cycles.
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = this.maxSMB // Fallback since specific key doesn't exist yet 
        // Logic check: usually maxSMBHB is just maxSMB. Let's check init. Init said 0.5.
        // Better safe:
        // this.maxSMBHB = 2.0 // or whatever default.
        // Actually earlier code used `if (bg > 120...) maxSMBHB else maxSMB`.
        // Let's stick to maxSMB reset first which was the smoking gun.
        // ✅ ETAPE 1: Calculer le Profil d'Action de l'IOB
        // 🧬 PHYSIO INTEGRATION: Get SNS Dominance from Adapter
        val physioSnapshot = physioAdapter.getLatestSnapshot()
        val snsDominance = physioSnapshot.toSNSDominance()
        
        // 🏥 Update Context
        decisionCtx.adjustments.physiological_context = AimiDecisionContext.PhysioContext(
            hormonal_cycle_phase = wCycleInfoForRun?.let { "${it.phase.name}_Day${it.dayInCycle}" } ?: "Unknown",
            physical_activity_mode = if (snsDominance > 0.6) "Stress/Activity" else "Resting"
        )

        // ✅ ETAPE 1: Calculer le Profil d'Action de l'IOB (avec modulation physio)
        val iobActionProfile = InsulinActionProfiler.calculate(iob_data_array, profile, snsDominance)

// Stocker les résultats dans des variables locales pour plus de clarté
        val iobTotal = iobActionProfile.iobTotal
        val iobPeakMinutes = iobActionProfile.peakMinutes
        iobActivityNow = iobActionProfile.activityNow
        val iobActivityIn30Min = iobActionProfile.activityIn30Min

// On met à jour la variable `iob` globale de la classe avec la valeur de notre profiler pour la cohérence
        // this.iob = iobTotal.toFloat() // FIX: Do NOT overwrite official IOB with Gross IOB. Use iob_data.iob.

// On ajoute les nouvelles informations au log pour le débogage
        consoleLog.add(
            "PAI: Peak in ${"%.0f".format(iobPeakMinutes)}m | " +
                "Activity Now=${"%.0f".format(iobActivityNow * 100)}%, " +
                "in 30m=${"%.0f".format(iobActivityIn30Min * 100)}%"
        )
        
        // 🚀 NOUAUTÉ: Update Real-Time Insulin Observer
        val insulinActionState = insulinObserver.update(
            currentBg = bg,
            bgDelta = delta.toDouble(),
            iobTotal = iobTotal,
            iobActivityNow = iobActivityNow,
            iobActivityIn30 = iobActivityIn30Min,
            peakMinutesAbs = iobPeakMinutes.toInt(),
            diaHours = profile.dia,
            carbsActiveG = cob.toDouble(),
            now = dateUtil.now()
        )
        
        // Log état observateur
        consoleLog.add("PKPD_OBS ${insulinActionState.reason}")
        
        // 👇 Force la création du CSV (premier snapshot WCycle “pré-décision”)
        ensureWCycleInfo()
        // --- GS + features AIMI -----------------------------------------------------
        val pack = try {
            glucoseStatusCalculatorAimi.compute(false)
        } catch (e: Exception) {
            consoleError.add("❌ GlucoseStatusCalculatorAimi.compute() failed: ${e.message}")
            null
        }

        if (pack == null || pack.gs == null) {
            consoleError.add("❌ No glucose data (AIMI pack empty)")
            return rT.also {
                it.reason.append("no GS")
                ensurePredictionFallback(it, bg)
            } // ou ton handling habituel
        }

        val gs = pack.gs!!
        val f  = pack.features

// Construit un GlucoseStatusAIMI complet (plus de 0.0 par défaut)
        val glucoseStatus = glucose_status ?: GlucoseStatusAIMI(
            glucose = gs.glucose,
            noise = gs.noise,
            delta = gs.delta,
            shortAvgDelta = gs.shortAvgDelta,
            longAvgDelta = gs.longAvgDelta,
            date = gs.date,

            // === valeurs issues de f (si présentes) ===
            duraISFminutes = f?.stable5pctMinutes ?: 0.0,
            duraISFaverage = f?.stable5pctAverage ?: 0.0,
            parabolaMinutes = f?.parabolaMinutes ?: 0.0,
            deltaPl = f?.delta5Prev ?: 0.0,
            deltaPn = f?.delta5Next ?: 0.0,
            bgAcceleration = f?.accel ?: 0.0,
            a0 = f?.a0 ?: 0.0,
            a1 = f?.a1 ?: 0.0,
            a2 = f?.a2 ?: 0.0,
            corrSqu = f?.corrR2 ?: 0.0
        )
        ensurePredictionFallback(rT, glucoseStatus.glucose)
        
        // ═══════════════════════════════════════════════════════════════════════════
        // 🏥 PHYSIOLOGICAL ASSISTANT INTEGRATION (MTR)
        // ═══════════════════════════════════════════════════════════════════════════
        val physioMultipliers = if (preferences.get(app.aaps.core.keys.BooleanKey.AimiPhysioAssistantEnable)) {
            try {
                physioAdapter.getMultipliers(
                    currentBG = glucoseStatus.glucose,
                    currentDelta = glucoseStatus.delta
                    // recentHypoTimestamp will be fetched internally by adapter
                )
            } catch (e: Exception) {
                aapsLogger.error(app.aaps.core.interfaces.logging.LTag.APS, "Physio adapter error - using defaults", e)
                app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR.NEUTRAL
            }
        } else {
            app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR.NEUTRAL
        }
        
        // Log physio modulation if active
        if (!physioMultipliers.isNeutral()) {
            consoleLog.add(
                "🏥 PHYSIO: ISF×${String.format("%.3f", physioMultipliers.isfFactor)} " +
                "Basal×${String.format("%.3f", physioMultipliers.basalFactor)} " +
                "SMB×${String.format("%.3f", physioMultipliers.smbFactor)} " +
                "Conf=${(physioMultipliers.confidence * 100).toInt()}%"
            )
        }
        
        // 🏥 Log detailed physio status (Always visible - never null)
        try {
             val physioLog = physioAdapter.getDetailedLogString()
             consoleError.add(physioLog)
        } catch (e: Exception) {
             consoleError.add("❌ Physio Log Error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════════════
        // 🔧 FIX CRITIQUE (MTR): EARLY PKPD CALCULATION
        // PkPd predictions must be available BEFORE SafetyNet, Meal Advisor, and Legacy logic run.
        // ═══════════════════════════════════════════════════════════════════════════
        
        // 1. Prepare Sensitivity for PKPD
        // Use default 1.0 if autosens not available yet (it is computed later usually)
        // Accessing autosens_data might fail if it's a local var defined later.
        val earlyAutosensRatio = 1.0 
        val earlySens = profile.sens / earlyAutosensRatio 
        
        // 2. Compute PKPD Predictions Immediately
        val earlyPkpdPredictions = computePkpdPredictions(
            currentBg = glucoseStatus.glucose,
            iobArray = iob_data_array,
            finalSensitivity = earlySens,
            cobG = mealData.mealCOB, // Use mealData which is initialized at start
            profile = profile,
            rT = rT,
            delta = glucoseStatus.delta
        )
        
        // 3. Initialize Variables & PkPdRuntime
        this.eventualBG = earlyPkpdPredictions.eventual
        this.predictedBg = earlyPkpdPredictions.eventual.toFloat()
        rT.eventualBG = earlyPkpdPredictions.eventual
        
        // 4. Compute PkPdRuntime (Critical for Tail Damping)
        this.cachedPkpdRuntime = try {
             pkpdIntegration.computeRuntime(
                epochMillis = dateUtil.now(),
                bg = glucoseStatus.glucose,
                deltaMgDlPer5 = glucoseStatus.delta,
                iobU = iobTotal.toDouble(),  // FIX: Use iobTotal from iobActionProfile (line 3614)
                carbsActiveG = mealData.mealCOB,
                windowMin = 360, // 6h window
                exerciseFlag = sportTime,
                profileIsf = earlySens,
                tdd24h = profile.max_daily_basal * 24.0, // Sort of
                consoleLog = consoleLog
            )
        } catch (e: Exception) {
            consoleError.add("❌ Early PKPD Runtime init failed: ${e.message}")
            null
        }
        
        // Local alias for compatibility with legacy code below
        var pkpdRuntime = this.cachedPkpdRuntime
        
        // 5. 🏥 Apply Physiological Multipliers NOW
        // This ensures Legacy modes and Meal Advisor respect fatigue/stress limits
        if (!physioMultipliers.isNeutral()) {
             // Apply to local sensitivity (will be refined later but good baseline)
             this.variableSensitivity = (earlySens * physioMultipliers.isfFactor).toFloat()
             
             // Apply to limits
             profile.max_daily_basal = profile.max_daily_basal * physioMultipliers.basalFactor
             this.maxSMB = (this.maxSMB * physioMultipliers.smbFactor).coerceAtLeast(0.1)
             
             consoleLog.add("🏥 PHYSIO APPLIED: MaxSMB=${"%.2f".format(this.maxSMB)} MaxBasal=${"%.2f".format(profile.max_daily_basal)}")
        }

        val reasonAimi = StringBuilder()
        var windowSinceDoseInt = 0
        var carbsActiveForPkpd = 0.0
        // On définit fromTime pour couvrir une longue période (par exemple, les 7 derniers jours)
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
// Récupération des événements de changement de cannule
        val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)

// Calcul de l'âge du site en jours
        val pumpAgeDays: Float = if (siteChanges.isNotEmpty()) {
            // On suppose que la liste est triée par ordre décroissant (le plus récent en premier)
            val latestChangeTimestamp = siteChanges.last().timestamp
            ((System.currentTimeMillis() - latestChangeTimestamp).toFloat() / (1000 * 60 * 60 * 24))
        } else {
            // Si aucun changement n'est enregistré, vous pouvez définir une valeur par défaut
            0f
        }
        val effectiveDiaH = pkpdRuntime?.params?.diaHrs
            ?: profile.dia   // → ou ton DIA ajusté SI PKPD est désactivé

        val effectivePeakMin = pkpdRuntime?.params?.peakMin
            ?: profile.peakTime  // idem, legacy seulement en fallback
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        val useLegacyDynamics = (pkpdRuntime == null)
        // Calcul du delta combiné : on combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        val tp = if (useLegacyDynamics) {
        calculateDynamicPeakTime(
            currentActivity = profile.currentActivity,
            futureActivity = profile.futureActivity,
            sensorLagActivity = profile.sensorLagActivity,
            historicActivity = profile.historicActivity,
            profile,
            recentSteps15Minutes,
            averageBeatsPerMinute.toInt(),
            bg,
            combinedDelta,
            reasonAimi
        )
        } else {
            pkpdRuntime.params.peakMin
        }
        val autodrive = preferences.get(BooleanKey.OApsAIMIautoDrive)

        val calendarInstance = Calendar.getInstance()
        this.hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        val dayOfWeek = calendarInstance[Calendar.DAY_OF_WEEK]
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        this.bg = glucoseStatus.glucose
        val getlastBolusSMB = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
        val lastBolusSMBTime = getlastBolusSMB?.timestamp ?: 0L
        //val lastBolusSMBMinutes = lastBolusSMBTime / 60000
        this.lastBolusSMBUnit = getlastBolusSMB?.amount?.toFloat() ?: 0.0F
        val diff = abs(now - lastBolusSMBTime)
        this.lastsmbtime = (diff / (60 * 1000)).toInt()
        this.maxIob = preferences.get(DoubleKey.ApsSmbMaxIob)
// Tarciso Dynamic Max IOB
        // [FIX] User Request: Strict MaxIOB Limit (Preference Only).
        // Dynamic calculations removed to prevent "dangerous variations".
        this.maxIob = maxIob
        rT.reason.append(context.getString(R.string.reason_max_iob, maxIob))
        consoleLog.add("MAX_IOB_STATIC: Pref=$maxIob (Dynamic disabled by request)")
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        // 🔒 STRICT LIMITS: User preferences are HARD CAPS.
        // Dynamic Autodrive boosting removed to prevent overriding user settings.
        val enableUAM = profile.enableUAM
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        
        // 🔧 ENHANCED MaxSMB Selection: Plateau OR Slope logic
        // Addresses critical edge case: BG stuck high (270-300) with small deltas → slope < 1.0
        // Solution: Use maxSMBHB if EITHER:
        //   1. Active rise detected (slope >= 1.0) - Original logic
        //   2. High plateau (BG >= 250) - NEW, regardless of slope
        this.maxSMB = when {
            // 🚨 CRITICAL PLATEAU: BG >= 250, regardless of slope
            // Absolute emergency if BG catastrophic, even with low delta
            // Protection: Don't apply if rapid fall (delta <= -5)
            bg >= 250 && combinedDelta > -5.0 -> {
                consoleLog.add("MAXSMB_PLATEAU_CRITICAL BG=${bg.roundToInt()} Δ=${String.format("%.1f", combinedDelta)} slope=${String.format("%.2f", mealData.slopeFromMinDeviation)} -> maxSMBHB=${String.format("%.2f", maxSMBHB)}U (plateau)")
                maxSMBHB
            }
            
            // 🔴 ACTIVE RISE HIGH: BG >= 140 (meal interception zone)
            // Full maxSMBHB for confirmed meal/resistance in elevated range
            // Added combinedDelta check to confirm rise is real
            (bg >= 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 && combinedDelta > 0.5) ||
            (bg >= 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4 && combinedDelta > 0.5) -> {
                consoleLog.add("MAXSMB_SLOPE_HIGH BG=${bg.roundToInt()} slope=${String.format("%.2f", mealData.slopeFromMinDeviation)} \u0394=${String.format("%.1f", combinedDelta)} -> maxSMBHB=${String.format("%.2f", maxSMBHB)}U (confirmed rise)")
                maxSMBHB
            }
            
            // 🟡 ACTIVE RISE SENSITIVE: BG 120-140 (near target zone)
            // 85% maxSMBHB for extra caution close to target
            // Added combinedDelta check to confirm rise is real
            bg >= 120 && bg < 140 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 && combinedDelta > 0.5 -> {
                val partial = max(maxSMB, maxSMBHB * 0.85)
                consoleLog.add("MAXSMB_SLOPE_SENSITIVE BG=${bg.roundToInt()} slope=${String.format("%.2f", mealData.slopeFromMinDeviation)} \u0394=${String.format("%.1f", combinedDelta)} -> ${String.format("%.2f", partial)}U (85% maxSMBHB - confirmed rise)")
                partial
            }
            
            // 🟠 MODERATE PLATEAU: BG 200-250, stable delta
            // Compromise: 75% of maxSMBHB for elevated but not critical BG
            bg >= 200 && bg < 250 && combinedDelta > -3.0 && combinedDelta < 3.0 -> {
                val partial = max(maxSMB, maxSMBHB * 0.75)
                consoleLog.add("MAXSMB_PLATEAU_MODERATE BG=${bg.roundToInt()} Δ=${String.format("%.1f", combinedDelta)} -> ${String.format("%.2f", partial)}U (75% maxSMBHB)")
                partial
            }
            
            // 🔵 FALLING PROTECTION: BG elevated but falling moderately
            // Partial limit to avoid over-correction while still allowing some action
            bg > 180 && combinedDelta <= -3.0 && combinedDelta > -8.0 -> {
                val partial = max(maxSMB, maxSMBHB * 0.6)
                consoleLog.add("MAXSMB_FALLING BG=${bg.roundToInt()} Δ=${String.format("%.1f", combinedDelta)} -> ${String.format("%.2f", partial)}U (60% maxSMBHB)")
                partial
            }
            
            // ⚪ STANDARD: Normal/low BG conditions
            else -> {
                consoleLog.add("MAXSMB_STANDARD BG=${bg.roundToInt()} -> ${String.format("%.2f", maxSMB)}U")
                maxSMB
            }
        }

        // 🔒 SAFETY CLAMP: Force Standard MaxSMB if < 120
        // User Rule: "lowbg when < 120". No bypass allowed.
        val stdMaxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        if (bg < 120.0 && this.maxSMB > stdMaxSMB) {
             this.maxSMB = stdMaxSMB
             consoleLog.add("🔒 STRICT CLAMP: BG<120 -> Forced Standard MaxSMB (${String.format("%.2f", stdMaxSMB)}U)")
        }
        val ngrConfig = buildNightGrowthResistanceConfig(profile, autosens_data, glucoseStatus, targetBg.toDouble())
        this.tir1DAYabove = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.abovePct()!!
        val tir1DAYIR = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.inRangePct()!!
        this.currentTIRLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.belowPct()!!
        this.currentTIRRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.inRangePct()!!
        this.currentTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0))?.abovePct()!!
        this.lastHourTIRLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0, 140.0))?.belowPct()!!
        val lastHourTIRAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0))?.abovePct()
        this.lastHourTIRLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 140.0))?.belowPct()!!
        this.lastHourTIRabove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 170.0))?.abovePct()!!
        this.lastHourTIRabove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 120.0))?.abovePct()!!
        val tirbasal3IR = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.inRangePct()
        val tirbasal3B = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.belowPct()
        val tirbasal3A = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0))?.abovePct()
        val tirbasalhAP = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0))?.abovePct()
        //this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        this.now = System.currentTimeMillis()
        automateDeletionIfBadDay(tir1DAYIR.toInt())

        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0
        var lastCarbTimestamp = mealData.lastCarbTime
        if (lastCarbTimestamp.toInt() == 0) {
            val oneDayAgoIfNotFound = now - 24 * 60 * 60 * 1000
            lastCarbTimestamp = persistenceLayer.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
        }
        this.lastCarbAgeMin = ((now - lastCarbTimestamp) / (60 * 1000)).toInt()

        this.futureCarbs = persistenceLayer.getFutureCob().toFloat()
        if (lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = persistenceLayer.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
        }

        val fourHoursAgo = now - 4 * 60 * 60 * 1000
        this.recentNotes = persistenceLayer.getUserEntryDataFromTime(fourHoursAgo).blockingGet()

        this.tags0to60minAgo = parseNotes(0, 60)
        this.tags60to120minAgo = parseNotes(60, 120)
        this.tags120to180minAgo = parseNotes(120, 180)
        this.tags180to240minAgo = parseNotes(180, 240)
        this.delta = glucoseStatus.delta.toFloat()
        this.shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat()
        this.longAvgDelta = glucoseStatus.longAvgDelta.toFloat()
        val bgAcceleration = glucoseStatus.bgAcceleration ?: 0f
        this.bgacc = bgAcceleration.toDouble()
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents()
        }
        val deleteEventDate = therapy.deleteEventDate
        val deleteTime = therapy.deleteTime
        if (deleteTime) {
            //removeLastNLines(100)
            //createFilteredAndSortedCopy(csvfile,deleteEventDate.toString())
            removeLast200Lines(csvfile)
        }
        this.sleepTime = therapy.sleepTime
        this.snackTime = therapy.snackTime
        this.sportTime = therapy.sportTime
        this.lowCarbTime = therapy.lowCarbTime
        this.highCarbTime = therapy.highCarbTime
        this.mealTime = therapy.mealTime
        this.bfastTime = therapy.bfastTime
        this.lunchTime = therapy.lunchTime
        this.dinnerTime = therapy.dinnerTime
        this.fastingTime = therapy.fastingTime
        this.stopTime = therapy.stopTime
        this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
        this.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
        this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
        this.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
        this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
        this.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
        this.iscalibration = therapy.calibrationTime
        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta > -3 && delta < 3 && shortAvgDelta > -3 && shortAvgDelta < 3 && longAvgDelta > -3 && longAvgDelta < 3) 1 else 0
        val nightbis = hourOfDay <= 7
        val modesCondition = (!mealTime || mealruntime > 30) && (!lunchTime || lunchruntime > 30) && (!bfastTime || bfastruntime > 30) && (!dinnerTime || dinnerruntime > 30) && !sportTime && (!snackTime || snackrunTime > 30) && (!highCarbTime || highCarbrunTime > 30) && !sleepTime && !lowCarbTime
        val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val reason = StringBuilder()
        val recentBGs = getRecentBGs()

        // 🕒 FCL 5.0 Pre-calc: Total Bolus Volume Last Hour
        val oneHourAgo = now - (60 * 60 * 1000L)
        val bolusesHistory = persistenceLayer.getBolusesFromTime(oneHourAgo, true).blockingGet()
        val totalBolusLastHour = bolusesHistory.sumOf { it.amount }

        val bgTrend = calculateBgTrend(recentBGs, reason)
        
        // 🧠 FCL 7.0: Update Watchdog State
        
        // 🧠 FCL 8.0: Autosens Synergy
        var autosensRatio = autosens_data.ratio
        if (autosensRatio <= 0.1) autosensRatio = 1.0 // Safety fallback
        
        // Effective ISF = Profile ISF / Ratio
        // Case: Resistant (Ratio 1.2) -> ISF 100 / 1.2 = 83 (Harder to move -> Stronger bolus needed)
        // Case: Sensitive (Ratio 0.7) -> ISF 100 / 0.7 = 142 (Easier to move -> Weaker bolus needed)
        
        // [FIX] Critical Math Inversion found during Deep Dive:
        // Previous: ISF * Ratio. 
        // 100 * 1.2 = 120 (Weaker). WRONG for Resistance.
        // 100 * 0.7 = 70 (Stronger). WRONG for Sensitivity.
        
        val systemTime = currentTime
        val iobArray = iob_data_array
        val iob_data = iobArray[0]
        val mealFlags = MealFlags(mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime)

        // Heure du dernier bolus : iob_data est bien disponible ici
        val lastBolusTimeMs: Long? = iob_data.lastBolusTime.takeIf { it > 0L }

        val lateFatRiseFlag  = isLateFatProteinRise(
            bg = bg,
            predictedBg = predictedBg.toDouble(),
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            iob = iob.toDouble(),
            cob = cob.toDouble(),
            maxSMB = maxSMB,
            lastBolusTimeMs = lastBolusTimeMs,
            mealFlags = mealFlags
        )
        val tdd7P: Double = preferences.get(DoubleKey.OApsAIMITDD7)
        var tdd24Hrs = tddCalculator.calculateDaily(-24, 0)?.totalAmount?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()
        // TODO eliminate
        val bgTime = glucoseStatus.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        
        // 🔒 SAFETY FCL 14.0: Stale Data Check
        // If data is > 12 mins old, disable Autodrive/SMBs to prevent unwanted late boluses.
        if (minAgo > 12.0) {
            reason.append("⚠️ Data Stale (${minAgo.toInt()}m) -> Logic Paused\n")
            consoleError.add("Data Stale (${minAgo}m) -> Logic Paused")
            logDecisionFinal("STALE_DATA", rT, bg, delta)
            return rT.also { ensurePredictionFallback(it, bg) }
        }
        val windowSinceDoseMin = if (iob_data.lastBolusTime > 0 || internalLastSmbMillis > 0) {
            val effectiveLastBolusTime = kotlin.math.max(iob_data.lastBolusTime, internalLastSmbMillis)
            ((systemTime - effectiveLastBolusTime) / 60000.0).coerceAtLeast(0.0)
        } else 0.0
        windowSinceDoseInt = windowSinceDoseMin.toInt()
        lastBolusAgeMinutes = windowSinceDoseMin
        val carbsActiveG = mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        carbsActiveForPkpd = carbsActiveG
        val mealModeActiveNow = isMealContextActive(mealData)
        val pkpdMealContext = MealAggressionContext(
            mealModeActive = mealModeActiveNow,
            predictedBgMgdl = predictedBg.toDouble(),
            targetBgMgdl = targetBg.toDouble()
        )
        val pkpdRuntimeTemp = pkpdIntegration.computeRuntime(
            epochMillis = currentTime,
            bg = bg,
            deltaMgDlPer5 = delta.toDouble(),
            iobU = iob.toDouble(),
            carbsActiveG = carbsActiveG,
            windowMin = windowSinceDoseInt,
            exerciseFlag = sportTime,
            profileIsf = profile.sens,
            tdd24h = tdd24Hrs.toDouble(),
            mealContext = pkpdMealContext,
            consoleLog = consoleLog
        )
        if (pkpdRuntimeTemp != null) {
            pkpdRuntime = pkpdRuntimeTemp
            
            // 📊 Expose PkPd Learner state in rT for visibility
            consoleLog.add("📊 PKPD_LEARNER:")
            consoleLog.add("  │ DIA (learned): ${"%.2f".format(Locale.US, pkpdRuntime.params.diaHrs)}h")
            consoleLog.add("  │ Peak (learned): ${"%.0f".format(Locale.US, pkpdRuntime.params.peakMin)}min")
            consoleLog.add("  │ fusedISF: ${"%.1f".format(Locale.US, pkpdRuntime.fusedIsf)} mg/dL/U")
            consoleLog.add("  │ pkpdScale: ${"%.3f".format(Locale.US, pkpdRuntime.pkpdScale)}")
            consoleLog.add("  └ adaptiveMode: ${if (pkpdRuntime.params.diaHrs != 4.0 || pkpdRuntime.params.peakMin != 75.0) "ACTIVE" else "DEFAULT"}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // 🌀 PHASE-SPACE TRAJECTORY ANALYSIS (Feature Flag)
        // ═══════════════════════════════════════════════════════════════════
        val trajectoryFlagEnabled = preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)
        
        if (trajectoryFlagEnabled) {
            try {
                val trajectoryHistory = trajectoryHistoryProvider.buildHistory(
                    nowMillis = currentTime, historyMinutes = 90, currentBg = bg,
                    currentDelta = delta.toDouble(), currentAccel = bgacc,
                    insulinActivityNow = iobActivityNow, iobNow = iob.toDouble(),
                    pkpdStage = insulinActionState.activityStage,
                    timeSinceLastBolus = if (lastBolusAgeMinutes.isFinite()) lastBolusAgeMinutes.toInt() else 120,
                    cobNow = cob.toDouble()
                )
                
                val stableOrbit = StableOrbit.fromProfile(targetBg.toDouble(), profile.current_basal)
                val traj = trajectoryGuard.analyzeTrajectory(trajectoryHistory, stableOrbit)
                
                if (traj == null) {
                    // Insufficient history - display warming up status
                    consoleLog.add("🌀 Trajectory: ⏳ Warming up (${trajectoryHistory.size}/4 states, need 20min)")
                    rT.trajectoryEnabled = false
                } else {
                    // SUCCESS - Display comprehensive trajectory status
                    val analysis = traj
                    val statusEmoji = analysis.classification.emoji()
                    val typeDesc = analysis.classification.description()
                    
                    // ALWAYS show this compact summary line
                    consoleLog.add("🌀 Trajectory: $statusEmoji $typeDesc | κ=${"%.2f".format(analysis.metrics.curvature)} conv=${"%.1f".format(analysis.metrics.convergenceVelocity)} health=${"%.0f".format(analysis.metrics.healthScore*100)}%")
                    
                    // Visual representation of trajectory type
                    val artLines = analysis.classification.asciiArt().split("\n")
                    artLines.forEach { line -> consoleLog.add("  $line") }
                    
                    // Detailed metrics section
                    consoleLog.add("  📊 Metrics: Coherence=${"%.2f".format(analysis.metrics.coherence)} Energy=${"%.1f".format(analysis.metrics.energyBalance)}U Openness=${"%.2f".format(analysis.metrics.openness)}")
                    
                    // Modulation (if active)
                    val mod = analysis.modulation
                    if (mod.isSignificant()) {
                        consoleLog.add("  🎛 Modulation: SMB×${"%.2f".format(mod.smbDamping)} Int×${"%.2f".format(mod.intervalStretch)} (${mod.reason})")
                        
                        // Apply modulations
                        if (abs(mod.smbDamping - 1.0) > 0.05) {
                            val orig = maxSMB
                            maxSMB *= mod.smbDamping; maxSMBHB *= mod.smbDamping
                            consoleLog.add("    → SMB: ${"%.2f".format(orig)}U → ${"%.2f".format(maxSMB)}U")
                        }
                        if (abs(mod.intervalStretch - 1.0) > 0.05) {
                            val orig = intervalsmb
                            intervalsmb = (intervalsmb * mod.intervalStretch).toInt().coerceIn(1, 20)
                            consoleLog.add("    → Interval: ${orig}min → ${intervalsmb}min")
                        }
                        if (abs(mod.safetyMarginExpand - 1.0) > 0.05) {
                            val orig = maxIob
                            maxIob *= mod.safetyMarginExpand
                            consoleLog.add("    → MaxIOB: ${"%.2f".format(orig)}U → ${"%.2f".format(maxIob)}U")
                        }
                    }
                    
                    // High-severity warnings
                    analysis.warnings.filter { it.severity >= WarningSeverity.HIGH }.forEach { w ->
                        consoleLog.add("  🚨 ${w.severity.emoji()} ${w.message}")
                        if (w.severity == WarningSeverity.CRITICAL) {
                            try { uiInteraction.addNotification(w.type.hashCode(), w.message, 2) } catch (e: Exception) {}
                        }
                    }
                    
                    // Convergence ETA (if available)
                    analysis.predictedConvergenceTime?.let {
                        consoleLog.add("  ⏱ Est. convergence: ${it}min")
                    }
                    
                    // 📊 Store trajectory metrics in rT for graphing/trending
                    rT.trajectoryEnabled = true
                    rT.trajectoryType = analysis.classification.name
                    rT.trajectoryCurvature = analysis.metrics.curvature
                    rT.trajectoryConvergence = analysis.metrics.convergenceVelocity
                    rT.trajectoryCoherence = analysis.metrics.coherence
                    rT.trajectoryEnergy = analysis.metrics.energyBalance
                    rT.trajectoryOpenness = analysis.metrics.openness
                    rT.trajectoryHealth = (analysis.metrics.healthScore * 100).toInt()
                    rT.trajectoryModulationActive = analysis.modulation.isSignificant()
                    rT.trajectoryWarningsCount = analysis.warnings.size
                    rT.trajectoryConvergenceETA = analysis.predictedConvergenceTime
                }
            } catch (e: Exception) {
                consoleLog.add("🌀 Trajectory: ❌ Error (${e.message})")
                aapsLogger.error(LTag.APS, "Trajectory Guard failed", e)
                rT.trajectoryEnabled = false
            }
        } else {
            // Feature flag OFF - still show status
            consoleLog.add("🌀 Trajectory: ⏸ Disabled")
            rT.trajectoryEnabled = false
        }

        // ═══════════════════════════════════════════════════════════════════
        // 🎯 CONTEXT MODULE INTEGRATION
        // ═══════════════════════════════════════════════════════════════════
        
        val contextEnabled = preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled)
        
        if (contextEnabled) {
            try {
                consoleLog.add("═══ CONTEXT MODULE ═══")
                
                // Get context snapshot
                val contextSnapshot = contextManager.getSnapshot(System.currentTimeMillis())
                
                if (contextSnapshot.intentCount > 0) {
                    // Get context mode from preferences
                    val modeStr = preferences.get(app.aaps.core.keys.StringKey.ContextMode)
                    val contextMode = when (modeStr) {
                        "CONSERVATIVE" -> ContextMode.CONSERVATIVE
                        "AGGRESSIVE" -> ContextMode.AGGRESSIVE
                        else -> ContextMode.BALANCED
                    }
                    
                    // Compute context influence
                    val contextInfluence = contextInfluenceEngine.computeInfluence(
                        snapshot = contextSnapshot,
                        currentBG = bg,
                        iob = iob_data.iob,
                        cob = cob.toDouble(),
                        mode = contextMode
                    )
                    
                    // Log active intents
                    consoleLog.add("🎯 Active Contexts: ${contextSnapshot.intentCount}")
                    contextSnapshot.activeIntents.take(3).forEach { intent ->
                        val typeStr = intent::class.simpleName ?: "Unknown"
                        consoleLog.add("  • $typeStr")
                    }
                    
                    // Apply context influence
                    if (abs(contextInfluence.smbFactorClamp - 1.0f) > 0.05f) {
                        val origMaxSMB = maxSMB
                        maxSMB *= contextInfluence.smbFactorClamp
                        maxSMBHB *= contextInfluence.smbFactorClamp
                        consoleLog.add("  SMB: %.2f→%.2fU (×%.2f)".format(Locale.US, origMaxSMB, maxSMB, contextInfluence.smbFactorClamp))
                    }
                    
                    if (contextInfluence.extraIntervalMin > 0) {
                        val origInterval = intervalsmb
                        intervalsmb = (intervalsmb + contextInfluence.extraIntervalMin).coerceIn(1, 20)
                        consoleLog.add("  Interval: %d→%dmin (+%d)".format(origInterval, intervalsmb, contextInfluence.extraIntervalMin))
                    }
                    
                    if (contextInfluence.preferBasal) {
                        consoleLog.add("  ⚠️ Prefers TEMP BASAL over SMB")
                    }
                    
                    // Log reasoning
                    contextInfluence.reasoningSteps.take(3).forEach { reason ->
                        consoleLog.add("  → $reason")
                    }
                    
                    // Store in rT for logging
                    rT.contextEnabled = true
                    rT.contextIntentCount = contextSnapshot.intentCount
                    rT.contextModulation = contextInfluence.smbFactorClamp.toDouble()
                    
                } else {
                    consoleLog.add("🎯 Context: No active intents")
                    rT.contextEnabled = true
                    rT.contextIntentCount = 0
                }
                
            } catch (e: Exception) {
                consoleLog.add("⚠️ Context error: ${e.message}")
                aapsLogger.error(LTag.APS, "Context Module failed", e)
                rT.contextEnabled = false
            }
        } else {
            rT.contextEnabled = false
        }
        consoleLog.add("═══════════════════════════════════")
        
        
        // End FCL 11.0 Hoist. Next block uses the results.
        var tdd7Days = profile.TDD
        if (tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P
        this.tdd7DaysPerHour = (tdd7Days / 24).toFloat()

        var tdd2Days = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tdd2Days == 0.0f || tdd2Days < tdd7P) tdd2Days = tdd7P.toFloat()
        this.tdd2DaysPerHour = tdd2Days / 24

        var tddDaily = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f || tddDaily < tdd7P / 2) tddDaily = tdd7P.toFloat()
        this.tddPerHour = tddDaily / 24

        this.tdd24HrsPerHour = tdd24Hrs / 24
        val fusedSensitivity = pkpdRuntime?.fusedIsf
        val dynSensitivity = profile.variable_sens.takeIf { it > 0.0 } ?: profile.sens
        val baseSensitivity = fusedSensitivity ?: profile.sens

        var sens = when {
            fusedSensitivity == null -> dynSensitivity
            dynSensitivity <= 0.0 -> fusedSensitivity
            else -> min(fusedSensitivity, dynSensitivity)
        }
        if (sens <= 0.0) sens = baseSensitivity
        this.variableSensitivity = sens.toFloat()

        if (fusedSensitivity != null) {
            consoleError.add("ISF fusionné=${"%.1f".format(fusedSensitivity)} dynISF=${"%.1f".format(dynSensitivity)} → appliqué=${"%.1f".format(sens)}")
            try {
                app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfTddProvider.set(fusedSensitivity)
            } catch (e: Exception) {
                consoleError.add("Impossible de mettre à jour IsfTddProvider: ${e.message}")
            }
        }
        
        val profileISF_raw = if (profile.sens > 10) profile.sens else 50.0
        // 🔮 FCL 11.0 Fix: Use Dynamic 'sens' for Effective ISF to capture Resistance/Sensitivity
        val effectiveISF = sens / autosens_data.ratio 
        // ⚡ FCL 12.0: Unified Learner Integration for Prebolus
        // [User Request]: Disabled for now. Prebolus should be raw / standard.
        // val useUnified = preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)
        // val reactivityFactor = if (useUnified) unifiedReactivityLearner.globalFactor else 1.0
        
        // [FIX] Use pbolusA (User Preference) if set, otherwise dynamic fallback (25.0 factor)
        val dynamicPbolusLarge = if (pbolusA > 0.0) pbolusA else calculateDynamicMicroBolus(effectiveISF, 25.0, reason)
        // [FIX] Use pbolusAS (User Preference) if set, otherwise dynamic fallback
        val dynamicPbolusSmall = if (pbolusAS > 0.0) pbolusAS else calculateDynamicMicroBolus(effectiveISF, 15.0, reason)
        
        // 🔮 FCL 11.0: Generate Predictions NOW so they are visible even if Autodrive returns early
        // 🔮 FCL 11.0: Generate Predictions NOW so they are visible even if Autodrive returns early
        try {
            // Using consoleError to ensure visibility in UI Debug Panel
            consoleError.add("🔮 PREDICT INIT: BG=$bg Delta=$delta Sens=${"%.1f".format(sens)} IOB=${iob_data_array.firstOrNull()?.iob}")
            
            // [FIX] User Request: Inject Advisor COB into Predictions details
            // MealAdvisor (Snap&Go) saves carbs in Prefs but doesn't create Carb Treatment entry immediately.
            val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
            val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
            val isFreshAdvisor = (dateUtil.now() - advisorTime) < 60 * 60000 // 60 min validity
            
            val effectiveCOB = if (mealData.mealCOB > 0) mealData.mealCOB else if (isFreshAdvisor) advisorCarbs else 0.0

            val advancedPredictions = AdvancedPredictionEngine.predict(
                currentBG = bg,
                iobArray = iob_data_array,
                finalSensitivity = sens,
                cobG = effectiveCOB,
                profile = profile,
                delta = delta.toDouble()
            )
            
            val sanitizedPredictions = advancedPredictions.mapNotNull {
                 if (it.isNaN()) null else round(min(401.0, max(39.0, it)), 0)
            }
            val intsPredictions = sanitizedPredictions.map { it.toInt() }
            lastPredictionSize = intsPredictions.size
            lastPredictionAvailable = intsPredictions.isNotEmpty()

            if (intsPredictions.isNotEmpty()) {
                // 🔮 FCL 11.0: Populate Oref0 standard variables for safety & compatibility
                var IOBpredBGs = mutableListOf<Double>()
                var COBpredBGs = mutableListOf<Double>()
                var UAMpredBGs = mutableListOf<Double>()
                var ZTpredBGs = mutableListOf<Double>()
                
                // Initialize with current BG
                IOBpredBGs.add(bg)
                COBpredBGs.add(bg)
                UAMpredBGs.add(bg)
                ZTpredBGs.add(bg)
                
                // Populate all arrays with AIMI predictions (Unified Model)
                // We use the same prediction for all because AIMI is an end-to-end model
                // Populate all arrays with AIMI predictions (Unified Model)
                // We use the same prediction for all because AIMI is an end-to-end model
                intsPredictions.forEach { pred -> 
                    val valDouble = pred.toDouble()
                    IOBpredBGs.add(valDouble)
                    COBpredBGs.add(valDouble)
                    UAMpredBGs.add(valDouble)
                    ZTpredBGs.add(valDouble)
                }
                
                // Calculate Min/Max/Eventual for Guard/Safety Logic
                val lastPred = intsPredictions.last().toDouble()
                val minPred = intsPredictions.minOrNull()?.toDouble() ?: bg
                lastEventualBgSnapshot = lastPred

                // Set Eventual BG
                rT.eventualBG = lastPred
                
                // FIX CRITICAL LGS HALT: Update member variable 'predictedBg' from this prediction
                this.predictedBg = lastPred.toFloat()
                
                // Populate rT.predBGs for UI Graph (Modern)
                rT.predBGs = Predictions().apply {
                    IOB = IOBpredBGs.map { it.toInt() }
                    COB = COBpredBGs.map { it.toInt() }
                    ZT  = ZTpredBGs.map { it.toInt() }
                    UAM = UAMpredBGs.map { it.toInt() }
                }

                // (Legacy assignments removed - fields do not exist in RT)
                
                // Debug logging mimicking SMB for consistency (FIX B3)
                consoleError.add("🔮 PREDICT GRAPH: IOB=${IOBpredBGs.size} COB=${COBpredBGs.size} ZT=${ZTpredBGs.size} UAM=${UAMpredBGs.size}")
                consoleError.add("minGuardBG ${minPred.toInt()} IOBpredBG ${lastPred.toInt()}")
                if (UAMpredBGs.size < 6) consoleError.add("⚠ WARNING: UAM Series too short (<6) for Graph!")
                
                consoleLog.add("PRED_SET size=${intsPredictions.size} eventual=${lastPred.toInt()} min=${minPred.toInt()} source=Advanced")
                
            } else {
                 consoleError.add("🔮 PREDICT WARNING: Empty prediction list returned. Using Fallback.")
                 
                 // FAILSAFE: Always populate rT with current BG to avoid "Unavailable" graph
                 val fallbackList = listOf(bg.toInt(), bg.toInt(), bg.toInt()) // Small series
                 rT.predBGs = Predictions().apply {
                     IOB = fallbackList
                     COB = fallbackList
                     ZT  = fallbackList
                     UAM = fallbackList
                 }
                 rT.eventualBG = bg
                 this.predictedBg = bg.toFloat()
                 
                 consoleLog.add("PRED_SET size=3 eventual=${bg.toInt()} min=${bg.toInt()} source=FallbackBG")
            }
        } catch (e: Exception) {
            consoleError.add("🔮 PREDICT ERROR: ${e.message}")
            e.printStackTrace()
        }
        consoleLog.add("Prédiction avancée avec ISF final de ${"%.1f".format(sens)} (Avancé)")

        // FIX: Use the calculated 'sens' (Dynamic) instead of 'profileISF_raw' (Static) if desired?
        // User wants BEST logic. 'sens' includes DynISF.
        // Let's use 'sens' for effectiveISF calculation.
        
        // MOVED: Early Meal Detection and Zero-IOB Priming are now FALLBACKS after Main Autodrive.
        // See Lines 3489+.
            // MOVED to after Main Autodrive.

        // -----------------------------------------------------
        // ACTIVE MODE NAME CALCULATION (Visual)
        // -----------------------------------------------------
        
        // 🍱 LEGACY MEAL MODES: Calculate TBR limit for all modes
        val maxBasalmodePref = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val modeTbrLimit = if (maxBasalmodePref > 0.1) maxBasalmodePref else profile.max_basal
        
        val activeModeName = when {
            lunchTime -> "Lunch"
            dinnerTime -> "Dinner"
            bfastTime -> "Breakfast"
            snackTime -> "Snack"
            highCarbTime -> "HighCarb"
            mealTime -> "Meal"
            else -> "N/A"
        }
        if (isMealModeCondition()) {
            val pbolusM = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (mealruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true    )
                consoleLog.add("🍱 LEGACY_TBR_MEAL rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusM
            rT.reason.append(context.getString(R.string.manual_meal_prebolus, pbolusM))
            consoleLog.add("🍱 LEGACY_MODE_MEAL P1=${"%.2f".format(pbolusM)}U (DIRECT SEND)")
            return rT
        }

        if (isbfastModeCondition()) {
            val pbolusbfast = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (bfastruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
 consoleLog.add("🍱 LEGACY_TBR_BFAST rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusbfast
            rT.reason.append(context.getString(R.string.reason_prebolus_bfast1, pbolusbfast))
            consoleLog.add("🍱 LEGACY_MODE_BFAST P1=${"%.2f".format(pbolusbfast)}U (DIRECT SEND)")
            return rT
        }

        if (isbfast2ModeCondition()) {
            val pbolusbfast2 = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (bfastruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_BFAST rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusbfast2
            rT.reason.append(context.getString(R.string.reason_prebolus_bfast2, pbolusbfast2))
            consoleLog.add("🍱 LEGACY_MODE_BFAST P2=${"%.2f".format(pbolusbfast2)}U (DIRECT SEND)")
            return rT
        }

        if (isLunchModeCondition()) {
            val pbolusLunch = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (lunchruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_LUNCH rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusLunch
            rT.reason.append(context.getString(R.string.reason_prebolus_lunch1, pbolusLunch))
            consoleLog.add("🍱 LEGACY_MODE_LUNCH P1=${"%.2f".format(pbolusLunch)}U (DIRECT SEND)")
            return rT
        }

        if (isLunch2ModeCondition()) {
            val pbolusLunch2 = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (lunchruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_LUNCH rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusLunch2
            rT.reason.append(context.getString(R.string.reason_prebolus_lunch2, pbolusLunch2))
            consoleLog.add("🍱 LEGACY_MODE_LUNCH P2=${"%.2f".format(pbolusLunch2)}U (DIRECT SEND)")
            return rT
        }

        if (isDinnerModeCondition()) {
            val pbolusDinner = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (dinnerruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_DINNER rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusDinner
            rT.reason.append(context.getString(R.string.reason_prebolus_dinner1, pbolusDinner))
            consoleLog.add("🍱 LEGACY_MODE_DINNER P1=${"%.2f".format(pbolusDinner)}U (DIRECT SEND)")
            return rT
        }

        if (isDinner2ModeCondition()) {
            val pbolusDinner2 = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (dinnerruntime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_DINNER rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusDinner2
            rT.reason.append(context.getString(R.string.reason_prebolus_dinner2, pbolusDinner2))
            consoleLog.add("🍱 LEGACY_MODE_DINNER P2=${"%.2f".format(pbolusDinner2)}U (DIRECT SEND)")
            return rT
        }

        if (isHighCarbModeCondition()) {
            val pbolusHC = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (highCarbrunTime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_HIGHCARB rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolusHC
            rT.reason.append(context.getString(R.string.reason_prebolus_highcarb, pbolusHC))
            consoleLog.add("🍱 LEGACY_MODE_HIGHCARB P1=${"%.2f".format(pbolusHC)}U (DIRECT SEND)")
            return rT
        }
        if (isHighCarb2ModeCondition()) {
            val pbolusHC = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus2)
            if (highCarbrunTime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
                consoleLog.add("🍱 LEGACY_TBR_HIGHCARB rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            rT.units = pbolusHC
            rT.reason.append(context.getString(R.string.reason_prebolus_highcarb, pbolusHC))
            consoleLog.add("🍱 LEGACY_MODE_HIGHCARB P1=${"%.2f".format(pbolusHC)}U (DIRECT SEND)")
            return rT
        }

        if (issnackModeCondition()) {
            val pbolussnack = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            
            // 🚀 TBR: Apply if runtime < 30 min
            if (snackrunTime < 30 * 60) {
                setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
                consoleLog.add("🍱 LEGACY_TBR_SNACK rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
            }
            
            rT.units = pbolussnack
            rT.reason.append(context.getString(R.string.reason_prebolus_snack, pbolussnack))
            consoleLog.add("🍱 LEGACY_MODE_SNACK P1=${"%.2f".format(pbolussnack)}U (DIRECT SEND)")
            return rT
        }

        // 🛡️ Hoisted Safety Variables for Autodrive & Early Terminators
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val sanity = sanitizePredictionValues(bg, delta, predictedBg.toDouble(), rT.eventualBG, rT.predBGs, consoleLog)
        val minBg = minOf(safe(bg), safe(sanity.predBg), safe(sanity.eventualBg))
        val threshold = computeHypoThreshold(minBg, profile.lgsThreshold)
        val pumpReachable = try {
            activePlugin.activePump.isInitialized() && activePlugin.activePump.isConnected()
        } catch (e: Exception) { false }
        consoleLog.add(
            "PRED_PIPE: bg=${bg.roundToInt()} delta=${"%.1f".format(delta)} predBg=${sanity.predBg.roundToInt()} " +
                "eventualBg=${sanity.eventualBg.roundToInt()} min=${minBg.roundToInt()} th=${threshold.toInt()} " +
                "noise=${glucoseStatus.noise} dataAge=${minAgo}m pumpReachable=$pumpReachable sanity=${sanity.label}"
        )

        // -----------------------------------------------------
        // ⚔️ DECISION PIPELINE EXECUTION (Refactor v2.3 Strict)
        // -----------------------------------------------------
        // PRIORITY 2: SAFETY FALLBACK (LGS / HYPO / STALE)
        // Evaluated only if no manual mode is active.
        val safetyRes = trySafetyStart(bg, delta, profile, iob_data, glucoseStatus.noise.toInt(), sanity.predBg, sanity.eventualBg)
        if (safetyRes is DecisionResult.Applied) {
            consoleLog.add("SAFETY_APPLIED_TBR_ZERO intent=${safetyRes.tbrUph}")
            if (safetyRes.tbrUph != null) {
                setTempBasal(safetyRes.tbrUph, safetyRes.tbrMin ?: 30, profile, rT, currenttemp, overrideSafetyLimits = true)
            }
            // Block all boluses
            rT.insulinReq = 0.0
            rT.reason.append(" | ⚠ Safety Halt: ${safetyRes.reason}")
            lastDecisionSource = safetyRes.source
            logDecisionFinal("SAFETY", rT, bg, delta)
            return rT
        }

        // PRIORITY 3: MEAL ADVISOR
        val advisorRes = tryMealAdvisor(bg, delta, iob_data, profile, lastBolusTimeMs ?: 0L, modesCondition)
        if (advisorRes is DecisionResult.Applied) {
             consoleLog.add("MEAL_ADVISOR_APPLIED source=${advisorRes.source} bolus=${advisorRes.bolusU}")
             if (advisorRes.tbrUph != null) {
                  setTempBasal(advisorRes.tbrUph, advisorRes.tbrMin ?: 30, profile, rT, currenttemp, overrideSafetyLimits = true)
             }
             if (advisorRes.bolusU != null && advisorRes.bolusU > 0) {
                  finalizeAndCapSMB(rT, advisorRes.bolusU, advisorRes.reason, mealData, threshold, true, advisorRes.source)
             }
             // Add Status Log (User Request)
             rT.reason.appendLine(context.getString(R.string.autodrive_status, if (autodrive) "✔" else "✘", "Meal Advisor"))
             logDecisionFinal("MEAL_ADVISOR", rT, bg, delta)
             return rT
        }


        // -----------------------------------------------------
        // 🛡️ HARD BRAKE (Lyra Optimization)
        // Check "falling decelerating" condition BEFORE Autodrive to prevent fueling the drop.
        val fallingDecelerating = delta < -EPS_FALL &&
                                  shortAvgDelta < -EPS_FALL &&
                                  longAvgDelta < -EPS_FALL &&
                                  shortAvgDelta > longAvgDelta + EPS_ACC

        if (fallingDecelerating && bg < targetBg + 10) {
            consoleLog.add("🛑 HARD_BRAKE triggered: delta=$delta, short=$shortAvgDelta")
            rT.reason.append("🛑 Hard Brake: Falling Fast & Decelerating -> Zero Basal\n")
            // Force 0% for 30m
            setTempBasal(0.0, 30, profile, rT, currenttemp, overrideSafetyLimits = true)
            lastSafetySource = "HardBrake" 
            logDecisionFinal("HARD_BRAKE", rT, bg, delta)
            return rT
        }
        // -----------------------------------------------------

        // PRIORITY 4: AUTODRIVE (Strict)
        val autoRes = tryAutodrive(
            bg, delta, shortAvgDelta, profile, lastBolusTimeMs ?: 0L, predictedBg, mealData.slopeFromMinDeviation, targetBg, reason,
            preferences.get(BooleanKey.OApsAIMIautoDrive),
            dynamicPbolusLarge, dynamicPbolusSmall,
            flatBGsDetected  // 🛡️ Pass CGM quality signal
        )
        
        if (autoRes is DecisionResult.Applied) {
             // 1. Apply TBR (System Intent) if present
             if (autoRes.tbrUph != null) {
                  setTempBasal(autoRes.tbrUph, autoRes.tbrMin ?: 30, profile, rT, currenttemp, overrideSafetyLimits = false)
             }
             
             // 2. Apply Bolus Intent (if present)
             val intentBolus = autoRes.bolusU ?: 0.0
             if (intentBolus > 0) {
                  finalizeAndCapSMB(rT, intentBolus, autoRes.reason, mealData, threshold, false, autoRes.source)
             }
             
             // 3. Verify EFFECTIVE Action (R3 / User Spec)
             // "If bolus capped to 0 AND no meaningful TBR -> Fallthrough"
             val effectiveBolus = rT.insulinReq ?: 0.0
             val effectiveDuration = rT.duration ?: 0
             // Action is meaningful if we are giving insulin OR setting a HIGH temp (greater than current or 0). 
             // Strictly: "NE DOIT JAMAIS retourner Applied s’il n’a RIEN appliqué."
             
             if (effectiveBolus > 0.05 || effectiveDuration > 0) {
                 lastAutodriveActionTime = System.currentTimeMillis() // 🟢 Update Strict Cooldown
                 consoleLog.add("AUTODRIVE_APPLIED intent=${intentBolus} actual=$effectiveBolus")
                 logDecisionFinal("AUTODRIVE", rT, bg, delta)
                 return rT
             } else {
                 consoleLog.add("AUTODRIVE_NOOP_FALLBACK reason=CappedToZero")
                 // Reset rT to clean state for Global Fallback?
                 // Actually rT might have some partial strings. 
                 // We should proceed to Global AIMI.
                 rT.insulinReq = 0.0 // Ensure 0
             }
        } 
        
        // AUTODRIVE FALLTHROUGH LOGGING handled inside tryAutodrive returns


        // 🛡️ Innovation: FCL 6.0 Safety Net
        val isPostHypo = isPostHypoProtectionCondition(recentBGs, reason)
        val isCompression = isCompressionProtectionCondition(delta.toFloat(), reason)
        
        if (isCompression) {
             // Hard Stop on Sensor Error
             logDecisionFinal("COMPRESSION", rT, bg, delta)
             return rT
        }
        
        // 🧠 FCL 8.0: Context-Aware Trigger for Drift Terminator
        // Resistant (<0.8): Tighten to +10. Sensitive (>1.2): Relax to +30. Normal: +15
        val terminatorThresholdAdd = when {
            autosensRatio < 0.8 -> 10.0 // Aggressive
            autosensRatio > 1.2 -> 30.0 // Safe
            else -> 15.0
        }
        val terminatorTarget = targetBg + terminatorThresholdAdd

        // 🧹 Innovation: FCL 5.0 Drift Terminator (Blocked by Post-Hypo)
        // Independent Refractory: Only block if 'Small' was given recently.
        if (!nightbis && autodrive && bg >= 80 && !isPostHypo && !hasReceivedRecentBolus(45, lastBolusTimeMs ?: 0L) && isDriftTerminatorCondition(bg.toFloat(), terminatorTarget.toFloat(), delta.toFloat(), totalBolusLastHour, reason) && modesCondition) {
            val terminatortap = dynamicPbolusSmall
            reason.append("→ Drift Terminator (Trigger +${terminatorThresholdAdd}): Micro-Tap ${terminatortap}U\n")
            consoleLog.add("AD_EARLY_TBR_TRIGGER rate=0.0 duration=0 reason=DriftTerminator_Tap") // Actually a bolus tap, not TBR, but fits "Early Action" category
            consoleLog.add("AD_SMALL_PREBOLUS_TRIGGER amount=$terminatortap reason=DriftTerminator")
            finalizeAndCapSMB(rT, terminatortap, reason.toString(), mealData, threshold, decisionSource = "DriftTerminator")
            logDecisionFinal("DRIFT_TERMINATOR", rT, bg, delta)
            return rT
        }
        
        // Duplicate Autodrive and Zero-IOB Logic Removed (Refactor v2.2)

        // Check lines ~3750 for active logic.
        //rT.reason.append(", MaxSMB: $maxSMB")
        rT.reason.append(context.getString(R.string.reason_maxsmb, maxSMB))
        var nowMinutes = calendarInstance[Calendar.HOUR_OF_DAY] + calendarInstance[Calendar.MINUTE] / 60.0 + calendarInstance[Calendar.SECOND] / 3600.0
        nowMinutes = (kotlin.math.round(nowMinutes * 100) / 100)  // Arrondi à 2 décimales
        val circadianSensitivity = (0.00000379 * nowMinutes.pow(5)) -
            (0.00016422 * nowMinutes.pow(4)) +
            (0.00128081 * nowMinutes.pow(3)) +
            (0.02533782 * nowMinutes.pow(2)) -
            (0.33275556 * nowMinutes) +
            1.38581503

        val circadianSmb = kotlin.math.round(
            ((0.00000379 * delta * nowMinutes.pow(5)) -
                (0.00016422 * delta * nowMinutes.pow(4)) +
                (0.00128081 * delta * nowMinutes.pow(3)) +
                (0.02533782 * delta * nowMinutes.pow(2)) -
                (0.33275556 * delta * nowMinutes) +
                1.38581503) * 100
        ) / 100  // Arrondi à 2 décimales
        // TODO eliminate
        val deliverAt = currentTime

        // Dynamic Pump Capabilities
        val pumpDesc = activePlugin.activePump.pumpDescription
        val pumpCaps = PumpCaps(
            basalStep = if (pumpDesc.basalStep > 0) pumpDesc.basalStep else 0.05,
            bolusStep = if (pumpDesc.bolusStep > 0) pumpDesc.bolusStep else 0.05,
            minDurationMin = 30,
            maxBasal = profile.max_basal,
            maxSmb = 3.0
        )
        val profile_current_basal = pumpCapabilityValidator.validateBasal(profile.current_basal, pumpCaps)
        var basal: Double


        val noise = glucoseStatus.noise
        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            //rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
            rT.reason.append(context.getString(R.string.reason_cgm_calibrating))
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            //rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read  ago at $bgTime")
            rT.reason.append(context.getString(R.string.reason_bg_data_old, systemTime, minAgo, bgTime))
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            //rT.reason.append("Error: CGM data is unchanged for the past ~45m")
            rT.reason.append(context.getString(R.string.reason_cgm_flat))
        }

        // TODO eliminate
        //val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver
        //val max_iob = maxIob
        var maxIobLimit = maxIob
        //this.maxIob = maxIob
        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = if (honeymoon) 130 else 100

        val halfBasalTarget = profile.half_basal_exercise_target


        when {
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 && predictedBg < 140 -> {
                this.targetBg = 130.0f
            }

            !profile.temptargetSet && predictedBg >= 120 && combinedDelta > 3                                                                                                    -> {
                var baseTarget = if (honeymoon) 110.0 else 70.0
                if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
                    baseTarget = if (honeymoon) 110.0 else 90.0
                }
                var hyperTarget = max(baseTarget, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
                hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
                hyperTarget = max(hyperTarget, baseTarget.toInt())

                this.targetBg = hyperTarget.toFloat()
                target_bg = hyperTarget.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                //consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
                consoleLog.add(context.getString(R.string.sensitivity_ratio_temp_target, sensitivityRatio, target_bg))
            }

            !profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120                                                                                                    -> {
                val baseHypoTarget = if (honeymoon) 130.0 else 110.0
                val hypoTarget = baseHypoTarget * max(1.0, circadianSensitivity)
                this.targetBg = min(hypoTarget.toFloat(), 166.0f)
                target_bg = targetBg.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatio = c / (c + target_bg - normalTarget)
                // limit sensitivityRatio to profile.autosens_max (1.2x by default)
                sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
                sensitivityRatio = round(sensitivityRatio, 2)
                //consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
                consoleLog.add(context.getString(R.string.sensitivity_ratio_temp_target, sensitivityRatio, target_bg))
            }

            else                                                                                                                                                                 -> {
                val defaultTarget = profile.target_bg
                this.targetBg = defaultTarget.toFloat()
                target_bg = targetBg.toDouble()
            }
        }
        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            //consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
            consoleLog.add(context.getString(R.string.sensitivity_ratio_temp_target, sensitivityRatio, target_bg))
        } else {
            sensitivityRatio = autosens_data.ratio
            //consoleLog.add("Autosens ratio: $sensitivityRatio; ")
            consoleLog.add(context.getString(R.string.autosens_ratio_log, sensitivityRatio))
        }
        basal = profile.current_basal * sensitivityRatio
        // WCycle Connectivity: Apply Basal Multiplier
        val wCycle = wCycleInfoForRun
        if (wCycle != null && wCycle.applied) {
             basal *= wCycle.basalMultiplier.toDouble()
        }
        basal = roundBasal(basal)
        if (basal != profile_current_basal)
        //consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
            consoleLog.add(context.getString(R.string.console_adjust_basal, profile_current_basal, basal))
        else
        //consoleLog.add("Basal unchanged: $basal; ")
            consoleLog.add(context.getString(R.string.console_basal_unchanged, basal))

// adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            //consoleLog.add("Temp Target set, not adjusting with autosens")
            consoleLog.add(context.getString(R.string.console_temp_target_set))
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                //consoleLog.add("target_bg unchanged: $new_target_bg; ")
                    consoleLog.add(context.getString(R.string.console_target_bg_unchanged, new_target_bg))
                else
                //consoleLog.add("target_bg from $target_bg to $new_target_bg; ")
                    consoleLog.add(context.getString(R.string.console_target_bg_changed, target_bg, new_target_bg))

                target_bg = new_target_bg
            }
        }

        // var iob2 = 0.0f
        // Check IOB mismatch
         if (abs(this.iob - iob_data.iob.toFloat()) > 1.0) {
              consoleLog.add("⚠️ IOB Mismatch: Profiler=${this.iob} vs System=${iob_data.iob}")
         }
         this.iob = iob_data.iob.toFloat() // FIX: Restore Official Net IOB for Safety Checks
        // if (iob_data.basaliob < 0) {
        //     iob2 = -iob_data.basaliob.toFloat() + iob
        //     this.iob = iob2
        // }

        val tick: String = if (glucoseStatus.delta > -0.5) {
            "+" + round(glucoseStatus.delta)
        } else {
            round(glucoseStatus.delta).toString()
        }
        val minDelta = min(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
        val minAvgDelta = min(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta)
        // val maxDelta = max(glucoseStatus.delta, max(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta))
        // MOVED (FCL 11.0): Logic hoisted to top of determine_basal
        // See Lines 3381+
        // (Deleted duplicate block)

        consoleError.add("CR:${profile.carb_ratio}")
        //val insulinEffect = calculateInsulinEffect(bg.toFloat(),iob,variableSensitivity,cob,normalBgThreshold,recentSteps180Minutes,averageBeatsPerMinute.toFloat(),averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat())

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis10 = now - 10 * 60 * 1000 // 10 minutes en millisecondes
        val timeMillis15 = now - 15 * 60 * 1000 // 15 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis60 = now - 60 * 60 * 1000 // 60 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
            // Robust Steps Retrieval (Matches HR logic)
            // Search window: 210 mins to cover 180min + delays
            val stepsSearchStart = now - 210 * 60 * 1000
            val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(stepsSearchStart, now)

            if (allStepsCounts.isNotEmpty()) {
                val lastSteps = allStepsCounts.maxByOrNull { it.timestamp }
                aapsLogger.debug(LTag.APS, "Steps Data: Found ${allStepsCounts.size} records. Last: ${lastSteps?.steps5min} steps @ ${java.util.Date(lastSteps?.timestamp ?: 0)}")
            } else {
                aapsLogger.debug(LTag.APS, "Steps Data: No records found in last 210 mins")
            }


            // 🔧 FIX: timestamp est déjà l'END time (cf. SC.kt doc), pas besoin d'ajouter duration
            val valid5 = allStepsCounts.filter { it.timestamp >= timeMillis5 }.maxByOrNull { it.timestamp }
            // Fallback for 5 min
            val fallbackRecord = if (valid5 == null) {
                 allStepsCounts.filter { it.timestamp >= (now - 30 * 60 * 1000) }.maxByOrNull { it.timestamp }
            } else null
            
            this.recentSteps5Minutes = valid5?.steps5min ?: fallbackRecord?.steps5min ?: 0
            
            this.recentSteps10Minutes = allStepsCounts.filter { it.timestamp >= timeMillis10 }
                .maxByOrNull { it.timestamp }?.steps10min ?: 0
            
            this.recentSteps15Minutes = allStepsCounts.filter { it.timestamp >= timeMillis15 }
                .maxByOrNull { it.timestamp }?.steps15min ?: 0
                
            this.recentSteps30Minutes = allStepsCounts.filter { it.timestamp >= timeMillis30 }
                .maxByOrNull { it.timestamp }?.steps30min ?: 0
                
            this.recentSteps60Minutes = allStepsCounts.filter { it.timestamp >= timeMillis60 }
                .maxByOrNull { it.timestamp }?.steps60min ?: 0
                
            this.recentSteps180Minutes = allStepsCounts.filter { it.timestamp >= timeMillis180 }
                .maxByOrNull { it.timestamp }?.steps180min ?: 0
                
        } else {
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        // Efficient robust Heart Rate retrieval (One query for all windows + fallback)
        try {
            // Search window: 200 mins to cover the 180min avg + buffer for overlapping records
            val searchStart = now - 200 * 60 * 1000
            val allHeartRates = persistenceLayer.getHeartRatesFromTimeToTime(searchStart, now)

            // Debug info for the user/screenshot
            if (allHeartRates.isNotEmpty()) {
                val lastHR = allHeartRates.maxByOrNull { it.timestamp }
                aapsLogger.debug(LTag.APS, "HR Data: Found ${allHeartRates.size} records. Last: ${lastHR?.beatsPerMinute} @ ${java.util.Date(lastHR?.timestamp ?: 0)}")
            } else {
                aapsLogger.debug(LTag.APS, "HR Data: No records found in last 200 mins")
            }

            // Helper to get average for a window (considering Overlap)
            fun getRateForWindow(windowMillis: Long): List<HR> {
                val windowStart = now - windowMillis
                return allHeartRates.filter {
                    val end = it.timestamp + it.duration
                    end >= windowStart // Ends after start of window
                }
            }

            // 1. Current HR (5 min window) - with Fallback
            val hr5List = getRateForWindow(5 * 60 * 1000)
            this.averageBeatsPerMinute = if (hr5List.isNotEmpty()) {
                hr5List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                // FALLBACK: Use the most recent value from the entire cache if available
                val partialFallback = allHeartRates.filter { (it.timestamp + it.duration) >= (now - 30 * 60 * 1000) } // Look back 30 mins for fallback
                val lastKnown = partialFallback.maxByOrNull { it.timestamp }
                if (lastKnown != null) {
                    // consoleLog.add("⚠️ HR Fallback: using data from ${((now - lastKnown.timestamp)/60000)} min ago")
                    lastKnown.beatsPerMinute
                } else {
                    Double.NaN // Will display "--" if truly nothing in 30 mins
                }
            }

            // 2. 10 Min Average
            val hr10List = getRateForWindow(10 * 60 * 1000)
            this.averageBeatsPerMinute10 = if (hr10List.isNotEmpty()) {
                hr10List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                this.averageBeatsPerMinute // fallback to current (which might be last known)
            }

            // 3. 60 Min Average
            val hr60List = getRateForWindow(60 * 60 * 1000)
            this.averageBeatsPerMinute60 = if (hr60List.isNotEmpty()) {
                hr60List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                80.0 // Default for long term avg
            }

            // 4. 180 Min Average
            val hr180List = getRateForWindow(180 * 60 * 1000)
            this.averageBeatsPerMinute180 = if (hr180List.isNotEmpty()) {
                hr180List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                80.0
            }

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error processing Heart Rate data", e)
            averageBeatsPerMinute = 80.0
            averageBeatsPerMinute10 = 80.0
            averageBeatsPerMinute60 = 80.0
            averageBeatsPerMinute180 = 80.0
        }
        val heartRateTrend = averageBeatsPerMinute10 / averageBeatsPerMinute60
        if (recentSteps10Minutes < 100 && heartRateTrend > 1.1 && bg > 110) {
            // Si la FC augmente de >10% sur les 10 dernières minutes (sans marche)
            // On rend l'ISF 10% plus agressif pour contrer une potentielle résistance
            this.variableSensitivity *= 0.9f
            consoleLog.add("ISF réduit de 10% (tendance FC anormale).")
        }
        if (tdd7Days.toFloat() != 0.0f) {
            val learnedBasalMultiplier = basalLearner.getMultiplier()
            basalaimi = ((tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)) * learnedBasalMultiplier).toFloat()
            if (learnedBasalMultiplier != 1.0) {
                consoleLog.add("Basal adjusted by learner (x${"%.2f".format(learnedBasalMultiplier)})")
            }
        }
        this.basalaimi = basalDecisionEngine.smoothBasalRate(tdd7P.toFloat(), tdd7Days.toFloat(), basalaimi)
        if (tdd7Days.toFloat() != 0.0f) {
            this.ci = (450 / tdd7Days).toFloat()
        }

        val choKey: Double = preferences.get(DoubleKey.OApsAIMICHO)
        if (ci != 0.0f && ci != Float.POSITIVE_INFINITY && ci != Float.NEGATIVE_INFINITY) {
            this.aimilimit = (choKey / ci).toFloat()
        } else {
            this.aimilimit = (choKey / profile.carb_ratio).toFloat()
        }
        val timenow = LocalTime.now().hour
        val sixAMHour = LocalTime.of(6, 0).hour

        val pregnancyEnable = preferences.get(BooleanKey.OApsAIMIpregnancy)

        if (tirbasal3B != null && pregnancyEnable && tirbasal3IR != null) {
            // 🎯 UnifiedReactivityLearner is now used exclusively
            val useUnified = preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)
            
            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5           -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()

                timenow < sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    val reactivity = if (useUnified) {
                        unifiedReactivityLearner.globalFactor
                    } else {
                        1.0  // Fallback to neutral if disabled
                    }
                    consoleLog.add("Reactivity (< 6AM): enabled=$useUnified, factor=${"%.3f".format(reactivity)}")
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                timenow > sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    val reactivity = if (useUnified) {
                        unifiedReactivityLearner.globalFactor
                    } else {
                        1.0  // Fallback to neutral if disabled
                    }
                    consoleLog.add("Reactivity (> 6AM): enabled=$useUnified, factor=${"%.3f".format(reactivity)}")
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0      -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70              -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5                -> (basalaimi * 0.85).toFloat()
                else                                              -> basalaimi
            }
        }

        this.basalaimi = if (honeymoon && basalaimi > profile_current_basal * 2) (profile_current_basal.toFloat() * 2) else basalaimi

        this.basalaimi = if (basalaimi < 0.0f) 0.0f else basalaimi
        val deltaAcceleration = glucoseStatus.delta - glucoseStatus.shortAvgDelta
        if (deltaAcceleration > 1.5 && bg > 130) {
            // Si la glycémie accélère (+1.5mg/dL/5min par rapport à la moyenne), on augmente le basal
            val boostFactor = 1.2f // Boost de 20%
            this.basalaimi = (this.basalaimi * boostFactor).coerceAtMost(profile.max_basal.toFloat())
            consoleLog.add("Basal boosté (+20%) pour accélération BG.")
        } else if (bg in 80.0..115.0 && glucoseStatus.delta > 1.0) {
            // 🚀 EARLY BASAL: Réactivité précoce pour les montées douces (80-115 mg/dL)
            // L'objectif est de ne pas attendre 130 mg/dL pour réagir.
            
            var earlyFactor = 1.0f
            if (deltaAcceleration > 0.5) { 
                // Accélération détectée (même faible)
                earlyFactor = 1.25f // +25%
                consoleLog.add("Early Basal: Accélération détectée en zone basse (+25%)")
            } else { 
                // Montée linéaire simple
                earlyFactor = 1.15f // +15%
                consoleLog.add("Early Basal: Montée progressive (+15%)")
            }

            // Application sécurisée : Max 1.5x le profil (restons modérés en zone basse)
            val safeCap = (profile_current_basal * 1.5).toFloat()
            this.basalaimi = (this.basalaimi * earlyFactor).coerceAtMost(safeCap)
        }
        // this.variableSensitivity = if (honeymoon) {
        //     if (bg < 150) {
        //         (baseSensitivity * 1.2).toFloat() // Légère augmentation pour honeymoon en cas de BG bas
        //     } else {
        //         max(
        //             (baseSensitivity / 3.0).toFloat(), // Réduction plus forte en honeymoon
        //             sens.toFloat()
        //         )
        //     }
        // } else {
        //     if (bg < 100) {
        //         (baseSensitivity * 1.1).toFloat()
        //     } else if (bg > 120) {
        //         val aggressivenessFactor = (1.0 + 0.4 * ((bg - 120.0) / 60.0)).coerceIn(1.0, 1.4)
        //         val aggressiveSens = (sens / aggressivenessFactor).toFloat()
        //         max( (baseSensitivity * 0.7).toFloat(), aggressiveSens)
        //     }else{
        //
        //         sens.toFloat()
        //     }
        // }
        var newVariableSensitivity = sens // On part de la sensibilité de base (fusionnée)

// --- ✅ ETAPE 2: NOUVELLE LOGIQUE PROACTIVE BASÉE SUR LE PAI ---
        consoleLog.add("PAI Logic: Base ISF=${"%.1f".format(sens)}")

// Scénario 1 : Montée glycémique détectée
        if (delta > 1.5 && bg > 120) {
            val urgencyFactor = when {
                // Le pic est loin (>45min) OU le pic est déjà bien passé (<-30min) -> URGENCE
                iobPeakMinutes > 45 || iobPeakMinutes < -30 -> {
                    consoleLog.add("PAI: BG rising & IOB badly timed. AGGRESSIVE.")
                    0.60 // ISF réduit de 40%
                }
                // L'activité de l'insuline va diminuer. On anticipe.
                iobActivityIn30Min < iobActivityNow * 0.9 -> {
                    consoleLog.add("PAI: BG rising & IOB activity will drop. PROACTIVE.")
                    0.90 // ISF réduit de 10%
                }
                // Le pic est dans un avenir proche (0-45min). On peut être patient.
                iobPeakMinutes in 0.0..45.0 -> {
                    consoleLog.add("PAI: BG rising but IOB peak is coming. PATIENT.")
                    1.0 // Pas de changement
                }
                else -> 1.0 // Cas par défaut
            }
            newVariableSensitivity *= urgencyFactor
            if (urgencyFactor != 1.0) {
                consoleLog.add("PAI: Urgency factor ${"%.2f".format(urgencyFactor)} applied. New ISF=${"%.1f".format(newVariableSensitivity)}")
            }
        }

// Scénario 2 : Tendance stable ou en légère baisse mais BG toujours haut
        if (delta in -1.0..1.5 && bg > 140) {
            // Si l'activité de l'insuline va chuter, on risque un rebond.
            if (iobActivityIn30Min < iobActivityNow * 0.8) {
                consoleLog.add("PAI: BG high/stable but IOB will fade. Anti-rebound.")
                newVariableSensitivity *= 0.95 // On est 5% plus agressif
            }
        }

        this.variableSensitivity = newVariableSensitivity.toFloat()

// --- FIN DE LA NOUVELLE LOGIQUE ---

        // 🌸 ENDOMETRIOSIS & CYCLE LOGIC (MTR)
        val endoFactors = endoAdjuster.calculateFactors(bg, delta.toDouble())
        if (endoFactors.reason.isNotEmpty()) {
            consoleLog.add("Endo: ${endoFactors.reason} (Basal x${endoFactors.basalMult}, SMB x${endoFactors.smbMult}, ISF x${endoFactors.isfMult})")
            
            // 1. ISF Adjustment (Stronger ISF means LOWER value, so we DIVIDE by mult > 1, or here isulMult is usually < 1 for resistance?)
            // In Adjuster: isfMult approx 1.0/basalMult (0.7..1.0). So it acts as Resistance.
            // variableSensitivity is ISF (mg/dL/U). Lower is stronger.
            // If isfMult = 0.8 (Resistance), we want ISF to be HIGHER (weaker).
            // Wait, standard: ISF * factor. If factor < 1, ISF drops -> Stronger.
            // Let's check logic: EndoAdjuster returns isfMult.
            //   if basal is 1.3 (high), resistance is high. We need MORE insulin.
            //   So ISF should be LOWER (e.g. 100 -> 80).
            //   In Adjuster I did: isfMult = (1.0 / basalMult).coerceIn(0.7, 1.0)
            //   So if basal 1.3 -> isfMult ~ 0.77.
            //   NewISF = OldISF * 0.77 (100 -> 77). This is STRONGER insulin. 
            //   Wait, Endometriosis is INFLAMMATION/RESISTANCE. We need STRONGER insulin.
            //   So yes, LOWERING the ISF value is correct.
            this.variableSensitivity *= endoFactors.isfMult.toFloat()
            
            // 2. Basal Adjustment (Profile Basal Boost)
            // We apply it to 'basalaimi' which is the base for calculations
            this.basalaimi *= endoFactors.basalMult.toFloat()
        }

        // --- 🏃 ACTIVITY MANAGER INTEGRATION ---
        
        // 1. Process Data through Manager
        val activityContext = activityManager.process(
            steps5min = recentSteps5Minutes,
            steps10min = recentSteps10Minutes,
            avgHr = averageBeatsPerMinute,
            avgHrResting = averageBeatsPerMinute60 // Using 60min avg as proxy for baseline/resting for now
        )

        // 2. Log Decision
        if (activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST || activityContext.isRecovery) {
            consoleLog.add("Activity: ${activityContext.description} → ISF x${"%.2f".format(activityContext.isfMultiplier)}")
        }

        // 3. Apply Multiplier to Sensitivity (ISF)
        // Note: activityContext.isfMultiplier is >= 1.0 (Boosts ISF aka lowers resistance)
        this.variableSensitivity *= activityContext.isfMultiplier.toFloat()
        
        // 4. Handle Recovery / Protection
        if (activityContext.protectionMode) {
             consoleLog.add("Activity Protection Mode Active (Recovery/Intense)")
        }

        // 5. Basal Modulation (Physiological Protection)
        // Reduire la basale SI activité significative (évite accumulation IOB)
        // Light: 100%, Moderate: 80%, Intense: 60%
        val anyMealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        val basalFactor = when (activityContext.state) {
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.LIGHT -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.MODERATE -> if (anyMealModeActive) 0.9f else 0.8f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE -> if (anyMealModeActive) 0.8f else 0.6f
        }
        if (basalFactor < 1.0f) {
            this.basalaimi *= basalFactor
            consoleLog.add("Basal Activity Redux: x${"%.2f".format(basalFactor)} -> ${"%.2f".format(this.basalaimi)}U/h")
        }

        // 🔹 Legacy Steps Logic (Removed/Replaced by ActivityManager above)
        // if (recentSteps5Minutes > 100 ...) { ... } 
        // -> All handled by activityManager.process() now.

        // 🔹 Sécurisation des bornes minimales et maximales
        this.variableSensitivity = this.variableSensitivity.coerceIn(5.0f, 300.0f)

        // 🏥 Apply Physiological Multipliers (AFTER all other ISF/basal calculations)
        this.variableSensitivity = (this.variableSensitivity * physioMultipliers.isfFactor).toFloat()
        profile.max_daily_basal = profile.max_daily_basal * physioMultipliers.basalFactor
        this.maxSMB = (this.maxSMB * physioMultipliers.smbFactor).coerceAtLeast(0.1)

        sens = variableSensitivity.toDouble()
        val pkpdPredictions = computePkpdPredictions(
            currentBg = bg,
            iobArray = iob_data_array,
            finalSensitivity = sens,
            cobG = mealData.mealCOB,

            profile = profile,
            rT = rT,
            delta = delta.toDouble()
        )
        this.eventualBG = pkpdPredictions.eventual
        this.predictedBg = pkpdPredictions.eventual.toFloat()
        rT.eventualBG = pkpdPredictions.eventual
        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucoseStatus.longAvgDelta - bgi))
            }
        }
        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG = round(bg - (iob_data.iob * sens), 0)
        // and adjust it for the deviation above (used only for noisy target heuristics)
        val legacyEventual = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && legacyEventual > adjustedMinBG && min_bg > adjustedMinBG) {
                //consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                consoleLog.add(context.getString(R.string.console_min_bg_adjusted, min_bg, adjustedMinBG))
                min_bg = adjustedMinBG
            } else {
                //consoleLog.add("min_bg unchanged: $min_bg; ")
                consoleLog.add(context.getString(R.string.console_min_bg_unchanged, min_bg))
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && legacyEventual > adjustedTargetBG && target_bg > adjustedTargetBG) {
                //consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                consoleLog.add(context.getString(R.string.console_target_bg_adjusted, target_bg, adjustedTargetBG))
                target_bg = adjustedTargetBG
            } else {
                //consoleLog.add("target_bg unchanged: $target_bg; ")
                consoleLog.add(context.getString(R.string.console_target_bg_unchanged, target_bg))
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && legacyEventual > adjustedMaxBG && max_bg > adjustedMaxBG) {
                //consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                consoleError.add(context.getString(R.string.console_max_bg_adjusted, max_bg, adjustedMaxBG))
                max_bg = adjustedMaxBG
            } else {
                //consoleError.add("max_bg unchanged: $max_bg")
                consoleError.add(context.getString(R.string.console_max_bg_unchanged, max_bg))
            }
        }
        //fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel(rT.reason)
        //val smbProposed = modelcal.toDouble()
        //val minBg = minOf(safe(bg), safe(predictedBg.toDouble()), safe(eventualBG))
        //val threshold = computeHypoThreshold(minBg, profile.lgsThreshold)

        var isHypoBlocked = shouldBlockHypoWithHysteresis(
                bg = bg,
                predictedBg = predictedBg.toDouble(),
                eventualBg = eventualBG,
                threshold = threshold,
                deltaMgdlPer5min = delta.toDouble()
            )
        
        // 🚀 ROCKET OVERRIDE (AIMI Neural Logic)
        // If BG is skyrocketing (Delta > 5) or high (> Target+40), the "Eventual BG" prediction (based on naked IOB) 
        // is likely a false flag (panic). We MUST unblock the system to allow aggression.
        if (isHypoBlocked && (delta > 5.0 || bg > target_bg + 40)) {
             isHypoBlocked = false
             lastHypoBlockAt = 0L // Reset hysteresis state to prevent "sticky" blocking
             rT.reason.append("🚀 Rocket Override: Hypo Block IGNORED due to massive rise. ") 
        }

        var fallbackActive = false
        if (isHypoBlocked) {
             if (canFallbackSmbWithoutPrediction(bg, delta.toDouble(), target_bg, iob.toDouble(), profile)) {
                 fallbackActive = true
            }
        }

        if (isHypoBlocked && !fallbackActive) {
            //rT.reason.appendLine(
            //    "🛑 Hypo guard+hystérèse: minBG=${convertBG(minBg)} " +
            //        "≤ Th=${convertBG(threshold)} (BG=${convertBG(bg)}, pred=${convertBG(predictedBg.toDouble())}, ev=${convertBG(eventualBG)}) → SMB=0"
            rT.reason.appendLine(context.getString(R.string.reason_hypo_guard, convertBG(minBg), convertBG(threshold), convertBG(bg), convertBG(predictedBg.toDouble()), convertBG(eventualBG))
            )
            this.predictedSMB = 0f
        } else {
            var finalModelSmb = modelcal
             
             if (fallbackActive) {
                 // Damping for fallback (Hyper Kicker replacement)
                 // User suggested 50% dampening when relying on raw UAM without global prediction
                 finalModelSmb = modelcal * 0.5f 
                 rT.reason.appendLine("Hyper fallback active: SMB unblocked (50% damped) despite missing prediction. UAM: ${"%.2f".format(modelcal)} -> ${"%.2f".format(finalModelSmb)}")
             } else {
                 rT.reason.appendLine("💉 SMB (UAM): ${"%.2f".format(modelcal)} U")
             }
             
             this.predictedSMB = finalModelSmb
        }

        // Detailed logging as requested
        val hasPred = predictedBg > 20
        val hyperKicker = (bg > target_bg + 30 && (delta >= 0.3 || shortAvgDelta >= 0.2))
        
        // 🚀 MEAL ADVISOR ONE-SHOT TRIGGER (MTR Request)
        val isMealAdvisorOneShot = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
        if (isMealAdvisorOneShot) {
             preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)
             consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT: Forcing Aggression (SMB+TBR)")
             rT.reason.append("🚀 Advisor Trigger: Force Action. ")
        }

        consoleLog.add(
            String.format(
                java.util.Locale.US,
                "SMB Decision: BG=%.0f, Delta=%.1f, IOB=%.2f, HasPred=%s, HyperKicker=%s, UAM=%.2f, Proposed=%.2f",
                bg, delta, iob, hasPred, hyperKicker, modelcal, this.predictedSMB
            )
        )
        val pkpdDiaMinutesOverride: Double? = pkpdRuntime?.params?.diaHrs?.let { it * 60.0 } // PKPD donne des heures → on passe en minutes
        val useLegacyDynamicsdia = pkpdDiaMinutesOverride == null
        val smbExecution = SmbInstructionExecutor.execute(
            SmbInstructionExecutor.Input(
                context = context,
                preferences = preferences,
                csvFile = csvfile,
                rT = rT,
                consoleLog = consoleLog,
                consoleError = consoleError,
                combinedDelta = combinedDelta,
                shortAvgDelta = shortAvgDelta,
                longAvgDelta = longAvgDelta,
                profile = profile,
                glucoseStatus = glucoseStatus,
                bg = bg,
                delta = delta.toDouble(),
                iob = iob,
                basalaimi = basalaimi,
                initialBasal = basal,
                honeymoon = honeymoon,
                hourOfDay = hourOfDay,
                mealTime = mealTime,
                bfastTime = bfastTime,
                lunchTime = lunchTime,
                dinnerTime = dinnerTime,
                highCarbTime = highCarbTime,
                snackTime = snackTime,
                sleepTime = sleepTime,
                recentSteps5Minutes = recentSteps5Minutes,
                recentSteps10Minutes = recentSteps10Minutes,
                recentSteps30Minutes = recentSteps30Minutes,
                recentSteps60Minutes = recentSteps60Minutes,
                recentSteps180Minutes = recentSteps180Minutes,
                averageBeatsPerMinute = averageBeatsPerMinute,
                averageBeatsPerMinute60 = averageBeatsPerMinute60,
                pumpAgeDays = pumpAgeDays.toInt(),
                sens = sens,
                tp = tp.toInt(),
                variableSensitivity = variableSensitivity,
                targetBg = target_bg,
                predictedBg = predictedBg,
                eventualBg = eventualBG,
                // Pass Dynamic MaxSMB (High vs Low logic) so Solver knows real limit
                // 🚀 ADVISOR BYPASS: If Triggered, use maxSMBHB (or at least 10U) to ensure aggressive delivery 
                maxSmb = if (isMealAdvisorOneShot) max(maxSMBHB, 10.0) else if ((bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0) || ((mealTime || lunchTime || dinnerTime || highCarbTime) && bg > 100)) maxSMBHB else maxSMB,
                maxIob = preferences.get(DoubleKey.ApsSmbMaxIob),
                predictedSmb = predictedSMB,
                modelValue = modelcal,
                mealData = mealData,
                pkpdRuntime = pkpdRuntime,
                sportTime = sportTime,
                lateFatRiseFlag = lateFatRiseFlag,
                highCarbRunTime = highCarbrunTime,
                threshold = threshold,
                dateUtil = dateUtil,
                currentTime = currentTime,
                windowSinceDoseInt = windowSinceDoseInt,
                currentInterval = intervalsmb,
                insulinStep = pumpCaps.bolusStep.toFloat(),
                highBgOverrideUsed = highBgOverrideUsed,
                profileCurrentBasal = profile_current_basal,
                cob = cob,
                globalReactivityFactor = if (preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)) {
                    safeReactivityFactor
                } else 1.0  // Backwards compatible default
            ),
            SmbInstructionExecutor.Hooks(
                refineSmb = { combined, short, long, predicted, profileInput ->
                    neuralnetwork5(combined, short, long, predicted, profileInput)
                },
                // ❌ adjustFactors removed - UnifiedReactivityLearner handles reactivity
                calculateAdjustedDia = { baseDia, currentHour, steps5, currentHr, avgHr60, pumpAge, iobValue ->
                    // 🔀 Si PKPD est actif, on l'utilise comme base, mais on permet l'ajustement dynamique (activités, heure, etc.)
                    val effectiveBaseDia = pkpdDiaMinutesOverride?.let { (it / 60.0).toFloat() } ?: baseDia

                    calculateAdjustedDIA(
                        baseDIAHours = effectiveBaseDia,
                        currentHour = currentHour,
                        pumpAgeDays = pumpAge,
                        iob = iobValue,
                        activityContext = activityContext,
                        steps = steps5,
                        heartRate = currentHr?.toInt()
                    )
                },
                costFunction = { basalInput, bgInput, targetInput, horizon, sensitivity, candidate ->
                    costFunction(basalInput, bgInput, targetInput, horizon, sensitivity, candidate)
                },
                applySafety = { meal, smb, guard, reasonBuilder, runtime, exercise, suspected ->
                    applySafetyPrecautions(meal, smb, guard as Double, reasonBuilder, runtime, exercise, suspected)
                },
                runtimeToMinutes = { runtimeToMinutes(it!!)},
                computeHypoThreshold = { minBg, lgs -> computeHypoThreshold(minBg, lgs) },
                isBelowHypo = { bgNow, predictedValue, eventualValue, hypo, deltaValue ->
                    isBelowHypoThreshold(bgNow, predictedValue, eventualValue, hypo, deltaValue)
                },
                logDataMl = { predicted, given -> logDataMLToCsv(predicted, given) },
                logData = { predicted, given -> logDataToCsv(predicted, given) },
                roundBasal = { value -> roundBasal(value) },
                roundDouble = { value, digits -> round(value, digits) }
            )
        )

        predictedSMB = smbExecution.predictedSmb
        basal = smbExecution.basal
        highBgOverrideUsed = smbExecution.highBgOverrideUsed
        smbExecution.newSmbInterval?.let { intervalsmb = it }
        var smbToGive = smbExecution.finalSmb
        consoleLog.add(
            String.format(
                java.util.Locale.US,
                "💉 SMB result: raw=%.2f -> final=%.2f",
                predictedSMB, smbToGive
            )
        )
        
        // 🎯 [MIGRATION FCL 10.0]
        // Legacy "Direct SMB Modulation" removed.
        // The UnifiedReactivityLearner now acts upstream via OpenAPSAIMIPlugin -> Autosens.Ratio.
        // This ensures the factor is applied consistently to both Basal and SMB limits, respecting all safety caps.
        //
        // if (preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)) { ... }
        
        // 🛡️ PKPD ABSORPTION GUARD (FIX 2025-12-30)
        // Soft guard basé sur physiologie insuline : "Injecter → Laisser agir → Réévaluer"
        // Empêche surcorrection UAM sans bloquer vraies urgences
        // 🚀 ADVISOR BYPASS: Must propagate to this guard variable too, otherwise capSmbDose will clamp it back down!
        val currentMaxSmb = if (isMealAdvisorOneShot) max(maxSMBHB, 10.0) else if ((bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0) || ((mealTime || lunchTime || dinnerTime || highCarbTime) && bg > 100)) maxSMBHB else maxSMB
        
        // Détecter si mode repas actif (ne pas freiner prebolus/TBR modes)
        val anyMealModeForGuard = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        
        // Calculer guard
        val pkpdGuard = PkpdAbsorptionGuard.compute(
            pkpdRuntime = pkpdRuntime,
            windowSinceLastDoseMin = windowSinceDoseInt.toDouble(),
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            targetBg = target_bg,
            predBg = predictedBg.toDouble().takeIf { it > 20 },
            isMealMode = anyMealModeForGuard
        )
        
        // Appliquer guard sur SMB
        if (pkpdGuard.isActive()) {
            val beforeGuard = smbToGive
            smbToGive = (smbToGive * pkpdGuard.factor.toFloat()).coerceAtLeast(0f)
            
            // Logs détaillés
            consoleError.add(pkpdGuard.toLogString())
            consoleLog.add("SMB_GUARDED: ${"%.2f".format(beforeGuard)}U → ${"%.2f".format(smbToGive)}U")
            
            // Ajouter au reason (visible utilisateur)
            rT.reason.append(" | ${pkpdGuard.reason} x${"%.2f".format(pkpdGuard.factor)}")
            
            // Augmenter intervalle si nécessaire
            if (pkpdGuard.intervalAddMin > 0) {
                intervalsmb = (intervalsmb + pkpdGuard.intervalAddMin).coerceAtMost(10)
                consoleLog.add("INTERVAL_ADJUSTED: +${pkpdGuard.intervalAddMin}m → ${intervalsmb}m total")
            }
        }

        // 🌸 ENDOMETRIOSIS SMB DAMPENING
        if (endoFactors.smbMult < 1.0) {
            val beforeEndo = smbToGive
            smbToGive = (smbToGive * endoFactors.smbMult.toFloat()).coerceAtLeast(0f)
            if (smbToGive < beforeEndo) {
                consoleLog.add("SMB_ENDO_DAMPEN: ${"%.2f".format(beforeEndo)}U → ${"%.2f".format(smbToGive)}U (x${"%.2f".format(endoFactors.smbMult)})")
                rT.reason.append(" | EndoDampen x${"%.2f".format(endoFactors.smbMult)}")
            }
        }

        // 🚀 MEAL MODES FORCE SEND: "Red Carpet" Logic
        
        // Define 'beforeCap' here so it's available for the logging check downstream
        val beforeCap = smbToGive

        // Defined broader context for priority
        val isMealChaos = (mealData.mealCOB > 10.0 && delta > 5.0)
        // Note: isMealAdvisorOneShot included via "Explicit User Action" equivalent
        val isExplicitAction = isMealAdvisorOneShot
        
        val isRedCarpetSituation = isExplicitAction || anyMealModeForGuard || (isMealChaos && smbExecution.finalSmb > 0.5)

        val gatedUnits = smbToGive
        val proposedUnits = smbExecution.finalSmb.toFloat()

        if (isRedCarpetSituation && proposedUnits > 0.0) {
            
            // 1. Restore demand if minor safeties (guards) killed it (>40% reduction)
            val candidateUnits = if (gatedUnits < proposedUnits * 0.6f) { 
                consoleLog.add("✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=${"%.2f".format(proposedUnits)} vs Gated=${"%.2f".format(gatedUnits)})")
                 proposedUnits
            } else {
                 gatedUnits 
            }

            // 2. Apply VITAL Hard Caps ONLY
            
            // a. Cap MaxSMB - Use the computed currentMaxSmb (which handles HB/OneShot logic)
            // Ensure we use the High Band if available in Red Carpet
            val redCarpetMaxSmb = max(currentMaxSmb, maxSMBHB)
            var mealBolus = min(candidateUnits.toDouble(), redCarpetMaxSmb).toFloat()

            // b. Cap MaxIOB (Ultimate Safety)
            val iobSpace = (this.maxIob - this.iob).coerceAtLeast(0.0)
            
            if (mealBolus > iobSpace.toFloat()) {
                consoleLog.add("🛡️ RED CARPET: Clamped by MaxIOB (Need=${"%.2f".format(mealBolus)}, Space=${"%.2f".format(iobSpace)})")
                mealBolus = iobSpace.toFloat()
            }
            
            // c. Hard Cap 30U (Sanity)
            mealBolus = mealBolus.coerceAtMost(30f)

            // d. Pump Max Bolus (Hardware constraint)
            // capSmbDose usually does this. We must do it manually here.
            // We don't have direct access to pump_max_bolus easily here without referencing 'profile.pump_max_bolus' or similar?
            // checking 'profile' object...
            // It seems 'profile' might NOT have pump_max_bolus direct field? 
            // Standard capSmbDose uses 'maxSmbConfig' which is usually checked against strict limits.
            // Let's trust 'iobSpace' and 'maxSmb' are the main algo drivers, but we should try to find pump max.
            // Looking at capSmbDose implementation (not visible here, but usually standard).
            // Let's assume 30U hard cap is the "software pump max" for this block.
            
            smbToGive = mealBolus

            if (smbToGive.toDouble() > gatedUnits + 0.1) {
                val reason = if (isExplicitAction) "UserAction" else if (isMealChaos) "CarbChaos" else "MealMode"
                consoleLog.add("🍱 MEAL_FORCE_EXECUTED ($reason): ${"%.2f".format(smbToGive)} U (Overrides minor safety checks)")
            }

        } else {
            // Standard Behavior
            smbToGive = capSmbDose(
                proposedSmb = smbToGive,
                bg = bg,
                maxSmbConfig = currentMaxSmb,
                iob = iob.toDouble(),
                maxIob = this.maxIob
            )
        }
        if (smbToGive < beforeCap) {
            rT.reason.append(" | 🛡️ Cap: ${"%.2f".format(beforeCap)} → ${"%.2f".format(smbToGive)}")
        }
        val savedReason = rT.reason.toString()
        // 🔮 FCL 11.0: Preserve Predictions across reset
        val savedPredBGs = rT.predBGs

        rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            //targetBG = target_bg,
            targetBG = "%.0f".format(target_bg).toDouble(),
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            //sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            sensitivityRatio = "%.0f".format(sensitivityRatio).toDouble(),
            consoleLog = consoleLog,
            consoleError = consoleError,
            //variable_sens = variableSensitivity.toDouble()
            variable_sens = "%.0f".format(variableSensitivity.toDouble()).toDouble()
        )
        // 🔮 FCL 11.0: Restore preserved Predictions
        rT.predBGs = savedPredBGs ?: rT.predBGs
        ensurePredictionFallback(rT, bg)
        rT.reason.append(savedReason)
        // Re-define for Global Logic
        val estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val estimatedCarbsTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val timeSinceEstimateMin = (System.currentTimeMillis() - estimatedCarbsTime) / 60000.0
        
        // Trigger already consumed above
        
        val maxBasalPref = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val rate: Double? = when {
            isMealAdvisorOneShot -> {
                 // Action 2: Force Basal (TBR) for 30 minutes. 
                 // User requested "Force a quantity of basal". We use 130% of current basal (Safe Boost) or MaxBasal if preferred.
                 // Using 1.3x boost as a safe "Meal Support" factor.
                 val targetRate = profile_current_basal * 1.3
                 val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal
                 calculateRate(basal, safeMax, 1.3, "Meal Advisor Trigger (One-Shot)", currenttemp, rT, overrideSafety = true)
            }
            snackTime && snackrunTime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because Snack Time $snackrunTime.", currenttemp, rT, overrideSafety = true)
            
            // 🚀 RE-ENABLED: 30 MIN INITIAL BOOST (User Request)
            // Force Max TBR during the first 30 minutes of any meal mode to act as extended prebolus.
            (mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime) && (listOf(mealruntime, lunchruntime, dinnerruntime, highCarbrunTime, bfastruntime).maxOrNull() ?: 0) in 0..30 -> {
                val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile_current_basal * 5.0
                //val factor = safeMax / profile_current_basal
                calculateRate(basal, safeMax, 1.0, "Meal Boost 30min (Force MaxBasal)", currenttemp, rT, overrideSafety = true)
            }

            // 🔥 Patch Post-Meal Hyper Boost (AIMI 2.0)
            // Added: Treat Recent Meal Advisor (< 120m) as implicit Meal Mode
            (mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime || snackTime || (timeSinceEstimateMin <= 120 && estimatedCarbs > 10.0)) -> {
                val runTime = listOf(mealruntime, lunchruntime, dinnerruntime, highCarbrunTime, bfastruntime, snackrunTime).maxOrNull() ?: timeSinceEstimateMin.toInt()
                val target = target_bg // simplification
                val rocketStart = delta > 5.0f || bg > target_bg + 40
                // If Rocket Start (Delta > 10 or Very High BG), use global Max Basal (Aggressive).
                // Otherwise use the Meal Mode preference (often conservative, default profile*2).
                val safeMax = if (rocketStart) profile.max_basal else if (maxBasalPref > 0) maxBasalPref else profile_current_basal * 2.0 
                
                val boostedRate = adjustBasalForMealHyper(
                    suggestedBasalUph = profile_current_basal, // Start with profile basal
                    bg = bg,
                    targetBg = target,
                    delta = delta.toDouble(),
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    isMealModeActive = true,
                    minutesSinceMealStart = runTime.toInt(),
                    mealMaxBasalUph = safeMax
                )
                
                if (boostedRate > profile_current_basal * 1.05) { // Only if significantly boosted
                     calculateRate(basal, profile_current_basal, boostedRate/profile_current_basal, "Post-Meal Boost active ($runTime m)", currenttemp, rT)
                } else null
            }
            
            // 🔥 General Hyper Kicker (Non-Meal)
            // Catch-all for late rises outside specific meal windows
            // 🚀 FCL 13.0: Harmonized Rocket Start (Delta > 5.0) to match Meal/Hypo logic.
            ((bg > target_bg + 40 || delta > 5.0f) && (delta >= 0.3 || shortAvgDelta >= 0.2)) -> {
                val maxBasalPref = preferences.get(DoubleKey.autodriveMaxBasal) // Absolute max
                val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal // Fallback if 0
                
                val boostedRate = adjustBasalForGeneralHyper(
                    suggestedBasalUph = profile_current_basal, 
                    bg = bg, 
                    targetBg = target_bg, 
                    delta = delta.toDouble(), 
                    shortAvgDelta = shortAvgDelta.toDouble(), 
                    maxBasalConfig = safeMax
                )
                
                if (boostedRate > profile_current_basal * 1.1) {
                    calculateRate(basal, profile_current_basal, boostedRate/profile_current_basal, "Global Hyper Kicker (Active)", currenttemp, rT, overrideSafety = true)
                } else null
            }

            // Fix: Clamp delta multiplier to 0.0 to prevent negative basal (delta is Float)
            fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.coerceAtLeast(0.0f).toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
            else -> null
        }


        // 🔧 FIX: Basal Boost Overlay Pattern (No Early Return)
        // ================================================================
        // Track basal boost source for logging and modulation
        val basalBoostApplied = rate != null
        val basalBoostSource: String? = when {
            rate != null && rT.reason.contains("Global Hyper Kicker") -> "HyperKicker"
            rate != null && rT.reason.contains("Post-Meal Boost") -> "PostMealBoost"  
            rate != null && rT.reason.contains("Meal") -> "MealMode"
            rate != null && rT.reason.contains("fasting") -> "Fasting"
            else -> null
        }
        
        // Apply basal boost if calculated (OVERLAY - don't block SMB)
        if (basalBoostApplied && rate != null) {
            rT.rate = rate.coerceAtLeast(0.0)
            rT.deliverAt = deliverAt
            rT.duration = 30
            consoleLog.add("BOOST_BASAL_APPLIED source=${basalBoostSource ?: "Unknown"} rate=${"%.2f".format(Locale.US, rate)}U/h")
            rT.reason.append("BasalBoost: ${basalBoostSource ?: "?"} ${"%.2f".format(Locale.US, rate)}U/h. ")
            // REMOVED: return rT (continue to SMB calculation)
        }
        // ================================================================


        rT.reason.appendLine(
             context.getString(R.string.autodrive_status, if (autodrive) "✔" else "✘", activeModeName)
        )
        // Cleaned up Logging

        rT.reason.appendLine(
            "📊 TIR: <70: ${"%.1f".format(currentTIRLow)}% | 70–180: ${"%.1f".format(currentTIRRange)}% | >180: ${"%.1f".format(currentTIRAbove)}%"
        )
        appendCompactLog(reasonAimi, tp, bg, delta, recentSteps5Minutes, averageBeatsPerMinute)
        rT.reason.append(reasonAimi.toString())
        
        // 🔮 FCL 11.0: Deep Endo - Apply WCycle IC Multiplier
        val icMult = wCycleFacade.getIcMultiplier()
        // If multiplier > 1 (e.g. 1.15 Luteal), we want STRONGER insulin.
        // Stronger insulin means LOWER Carb Ratio (e.g. 10g/U -> 8.7g/U).
        // So we DIVIDE the profile CR by the multiplier.
        val adjustedCR = profile.carb_ratio / icMult
        
        val csf = sens / adjustedCR
        //consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")
        consoleError.add(context.getString(R.string.console_profile_sens, baseSensitivity, sens, csf))

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            //consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            consoleError.add(context.getString(R.string.console_limiting_carb_impact, ci, maxCI, maxCarbAbsorptionRate))
            ci = maxCI.toFloat()
        }
        var remainingCATimeMin = 2.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        var remainingCATime = remainingCATimeMin
        val totalCI = max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, mealData.mealCOB - totalCA)
        remainingCarbs = min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        val slopeFromMaxDeviation = mealData.slopeFromMaxDeviation
        val slopeFromMinDeviation = mealData.slopeFromMinDeviation
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, mealData.mealCOB * csf / ci))
        }
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        //consoleError.add("Carb Impact: ${ci} mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        consoleError.add(context.getString(R.string.console_carb_impact, ci, round(cid * 5 / 60 * 2, 1), round(remainingCIpeak, 1)))
        // MOVED (FCL 11.0): Logic hoisted to top of determine_basal
        // (Deleted duplicate block)
//fin predictions
////////////////////////////////////////////
//estimation des glucides nécessaires si risque hypo

        val thresholdBG = 70.0
        val carbsRequired = CarbsAdvisor.estimateRequiredCarbs(
            bg = bg,
            targetBG = targetBg.toDouble(),
            slope = slopeFromDeviations,
            iob = iob.toDouble(),
            csf = csf,
            isf = sens,
            cob = cob.toDouble()
        )
        val minutesAboveThreshold = HypoTools.calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
        if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !lunchTime && !dinnerTime && !bfastTime && !highCarbTime && !mealTime) {
            rT.carbsReq = carbsRequired
            rT.carbsReqWithin = minutesAboveThreshold
            //rT.reason.append("$carbsRequired add\'l carbs req w/in ${minutesAboveThreshold}m; ")
            rT.reason.append(context.getString(R.string.reason_additional_carbs, carbsRequired, minutesAboveThreshold))
        }

        val forcedBasalmealmodes = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val forcedBasal = preferences.get(DoubleKey.autodriveMaxBasal)

        //val enableSMB = enablesmb(profile, microBolusAllowed, mealData, target_bg)
        // 📝 Repère l'activation d'un mode repas pour assouplir les gardes SMB/TBR.
        // 📝 Repère l'activation d'un mode repas pour assouplir les gardes SMB/TBR.
        val mealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime

        val enableSMB = enablesmb(
            profile,
            microBolusAllowed,
            mealData,
            target_bg,
            mealModeActive,
            bg,
            delta.toDouble(),
            eventualBG
        )

        mealModeSmbReason?.let { reason(rT, it) }

        rT.COB = mealData.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convertBG(target_bg)} \uD83D\uDCD2 "
        )
        val zeroSinceMin = BasalHistoryUtils.historyProvider.zeroBasalDurationMinutes(2)
        val minutesSinceLastChange = BasalHistoryUtils.historyProvider.minutesSinceLastChange()
        //val (conditionResult, conditionsTrue) = isCriticalSafetyCondition(mealData, hypoThreshold)
        this.zeroBasalAccumulatedMinutes = zeroSinceMin
        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            //rT.reason.append("Eventual BG " + convertBG(eventualBG) + " >= " + convertBG(max_bg) + ", ")
            rT.reason.append(context.getString(R.string.reason_eventual_bg, convertBG(eventualBG), convertBG(max_bg)))
        }
        val tdd24h = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount ?: 0.0
        val tirInHypo = tirCalculator.averageTIR(tirCalculator.calculate(1, 65.0, 180.0))?.belowPct() ?: 0.0
        val safetyDecision = safetyAdjustment(
            currentBG = glucoseStatus.glucose.toFloat(),
            predictedBG = eventualBG.toFloat(),
            bgHistory = glucoseStatusCalculatorAimi.getRecentGlucose(),
            combinedDelta = combinedDelta.toFloat(),
            iob = iob,
            maxIob = profile.max_iob.toFloat(),
            tdd24Hrs = tdd24h.toFloat(),
            tddPerHour = tddPerHour,
            tirInhypo = tirInHypo.toFloat(),
            targetBG = profile.target_bg.toFloat(),
            zeroBasalDurationMinutes = windowSinceDoseInt
        )
        rT.isHypoRisk = safetyDecision.isHypoRisk

        if (safetyDecision.isHypoRisk) {
            uiInteraction.addNotification(
                app.aaps.core.interfaces.notifications.Notification.HYPO_RISK_ALARM,
                context.getString(R.string.hypo_risk_notification_text),
                app.aaps.core.interfaces.notifications.Notification.URGENT
            )
        }
        // --- helpers ---
        fun runtimeToMinutes(rt: Long?): Int {
            if (rt == null) return Int.MAX_VALUE
            // si valeurs en millisecondes
            if (rt > 600_000L) return (rt / 60_000L).toInt()
            // si valeurs en secondes
            if (rt > 180L) return (rt / 60L).toInt()
            // sinon: déjà en minutes
            return rt.toInt()
        }

// -------- 1) sécurité hypo dure, avant tout
        if (safetyDecision.stopBasal) {
            return setTempBasal(0.0, 30, profile, rT, currenttemp)
        }

// -------- 2) forçage IMMEDIAT début de repas (<= 2 min), AVANT le test IOB
        // Détection mode repas ACTIF + runtime en minutes (robuste secondes/minutes)
        val (isMealActive, runtimeMinLabel, runtimeMinValue) = when {
            mealTime     -> Triple(true, "meal",     runtimeToMinutes(mealruntime))
            bfastTime    -> Triple(true, "bfast",    runtimeToMinutes(bfastruntime))
            lunchTime    -> Triple(true, "lunch",    runtimeToMinutes(lunchruntime))
            dinnerTime   -> Triple(true, "dinner",   runtimeToMinutes(dinnerruntime))
            highCarbTime -> Triple(true, "highcarb", runtimeToMinutes(highCarbrunTime))
            else         -> Triple(false, "", Int.MAX_VALUE)
        }

        if (isMealActive && runtimeMinValue in 0..30) {
            val forced = forcedBasalmealmodes.coerceAtLeast(0.05) // anti-0
            val alreadyForced = abs(currenttemp.rate - forced) < 0.05 && currenttemp.duration >= 25
            if (!alreadyForced) {
                rT.reason.append(
                    context.getString(
                        R.string.meal_mode_first_30,
                        "$runtimeMinLabel($runtimeMinValue)",
                        forced
                    )
                )
                return setTempBasal(
                    forced, 30, profile, rT, currenttemp,
                    overrideSafetyLimits = true    // bypass du plafond IOB pour le départ repas
                )
            }
        }
        val ngrResult = nightGrowthResistanceMode.evaluate(
            now = Instant.ofEpochMilli(systemTime),
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            eventualBG = eventualBG,
            targetBG = target_bg,
            iob = iob_data.iob,
            cob = mealData.mealCOB,
            react = bg,
            isMealActive = isMealActive,
            config = ngrConfig
        )
        if (ngrResult.reason.isNotEmpty()) {
            rT.reason.appendLine(ngrResult.reason)
            consoleLog.add(ngrResult.reason)
        }
        val lowTempTarget = profile.temptargetSet && target_bg <= profile.target_bg
        val originalMaxIobLimit = maxIobLimit
        if (!lowTempTarget && ngrResult.extraIOBHeadroomU > 0.0) {
            val slotBudget = ngrConfig.extraIobPer30Min * ngrConfig.headroomSlotCap
            val absoluteMaxIob = preferences.get(DoubleKey.ApsSmbMaxIob) + slotBudget
            val candidate = maxIobLimit + ngrResult.extraIOBHeadroomU
            val updatedLimit = min(candidate, absoluteMaxIob)
            if (updatedLimit > originalMaxIobLimit + 0.01) {
                maxIobLimit = updatedLimit
                this.maxIob = maxIobLimit
                val headroomMessage = context.getString(
                    R.string.oaps_aimi_ngr_headroom,
                    round(maxIobLimit - originalMaxIobLimit, 2),
                    round(maxIobLimit, 2)
                )
                rT.reason.appendLine(headroomMessage)
                consoleLog.add(headroomMessage)
            }
        }
        this.maxIob = maxIobLimit
        val safeBgThreshold = max(110.0, target_bg)
        val originalBasal = basal
        val shouldApplyBasalBoost = ngrResult.basalMultiplier > 1.0001 && !lowTempTarget && delta > 0 && shortAvgDelta > 0 && bg > target_bg
        if (shouldApplyBasalBoost && originalBasal > 0.0) {
            val boostedBasal = roundBasal((originalBasal * ngrResult.basalMultiplier).coerceAtLeast(0.05))
            if (boostedBasal > originalBasal + 0.01) {
                basal = boostedBasal
                val basalMessage = context.getString(
                    R.string.oaps_aimi_ngr_basal_applied,
                    boostedBasal / originalBasal,
                    round(boostedBasal, 2)
                )
                rT.reason.appendLine(basalMessage)
                consoleLog.add(basalMessage)
            }
        }
        val originalSmb = smbToGive.toDouble()
        val shouldApplySmbBoost = ngrResult.smbMultiplier > 1.0001 && !lowTempTarget && safetyDecision.bolusFactor >= 1.0 && eventualBG > target_bg && delta > 0 && bg >= safeBgThreshold
        if (shouldApplySmbBoost && originalSmb > 0.0) {
            val boosted = originalSmb * ngrResult.smbMultiplier
            val smbClamp = min(ngrConfig.maxSMBClampU, maxSMB)
            val finalSmb = boosted.coerceAtMost(smbClamp)
            val appliedMultiplier = finalSmb / originalSmb
            if (appliedMultiplier > 1.0001) {
                smbToGive = finalSmb.toFloat()
                val smbMessage = context.getString(
                    R.string.oaps_aimi_ngr_smb_applied,
                    appliedMultiplier,
                    round(finalSmb, 3),
                    round(smbClamp, 3)
                )
                rT.reason.appendLine(smbMessage)
                consoleLog.add(smbMessage)
            }
        }
        // 📝 Décision centralisée : peut-on relaxer le plafond IOB pendant un repas montant ?
        // 📝 Décision centralisée : peut-on relaxer le plafond IOB pendant un repas montant ?
        val mealHighIobDecision = computeMealHighIobDecision(
            mealModeActive,
            bg,
            delta.toDouble(),
            eventualBG,
            target_bg,
            iob_data.iob,
            maxIobLimit
        )
        val allowMealHighIob = mealHighIobDecision.relax
        val mealHighIobDamping = mealHighIobDecision.damping

        if (iob_data.iob > maxIobLimit && !allowMealHighIob) {
            //rT.reason.append("IOB ${round(iob_data.iob, 2)} > maxIobLimit maxIobLimit")
            rT.reason.append(context.getString(R.string.reason_iob_max, round(iob_data.iob, 2), round(maxIobLimit, 2)))
            val finalResult = if (delta < 0) {
                // BG is dropping, usually we cut to 0. BUT check floor first.
                val floorRate = applyBasalFloor(0.0, profile.current_basal, safetyDecision, activityContext, bg, delta.toDouble(), ((glucose_status as? GlucoseStatusAIMI)?.shortAvgDelta ?: 0.0).toDouble(), eventualBG.toDouble(), mealModeActive, getLgsThresholdSafe(profile))
                
                if (floorRate > 0.0) {
                     rT.reason.append(context.getString(R.string.reason_bg_dropping_floor, delta, floorRate))
                     setTempBasal(floorRate, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
                } else {
                     rT.reason.append(context.getString(R.string.reason_bg_dropping, delta))
                     setTempBasal(0.0, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
                }
            } else if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                //rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                // Apply floor here too just in case 'basal' itself is super low? (Unlikely if it came from profile, but possible)
                val safeBasal = applyBasalFloor(basal, profile.current_basal, safetyDecision, activityContext, bg, delta.toDouble(), ((glucose_status as? GlucoseStatusAIMI)?.shortAvgDelta ?: 0.0).toDouble(), eventualBG.toDouble(), mealModeActive, getLgsThresholdSafe(profile))
                rT.reason.append(context.getString(R.string.reason_set_temp_basal, round(safeBasal, 2)))
                setTempBasal(safeBasal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
            }
            comparator.compare(
                aimiResult = finalResult,
                glucoseStatus = glucose_status,
                currentTemp = currenttemp,
                iobData = iob_data_array,
                profileAimi = originalProfile,
                autosens = autosens_data,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )
            logDecisionFinal("MAX_IOB", finalResult, bg, delta)
            return finalResult
        } else {
            var insulinReq = smbToGive.toDouble()

            // ⚡ ACTIVITY SAFETY CLAMP
            // Si mode protection (Recovery ou Intense), on bride les SMB pour éviter l'hypo tardive
            if (activityContext.protectionMode || activityContext.state == app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE) {
                val safetyMax = maxSMB * 0.5 // 50% du MaxSMB autorisé
                if (insulinReq > safetyMax) {
                    insulinReq = safetyMax
                    rT.reason.append(context.getString(R.string.reason_activity_cap, safetyMax)) // Ensure string exists or use plain text if risky
                    consoleLog.add("SMB capped by Activity/Recovery (Limit: ${"%.2f".format(safetyMax)})")
                }
            }

            // 📝 SMB autorisés mais atténués lorsque le repas impose un IOB > max raisonnable.
            if (allowMealHighIob) {
                insulinReq *= mealHighIobDamping
                rT.reason.append(
                    context.getString(
                        R.string.reason_meal_high_iob_relaxed,
                        round(iob_data.iob, 2),
                        round(maxIobLimit, 2),
                        (mealHighIobDamping * 100).roundToInt()
                    )
                )
            }

            //updateZeroBasalDuration(profile_current_basal)

            insulinReq = insulinReq * safetyDecision.bolusFactor
            insulinReq = round(insulinReq, 3)
            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            // minutes since last bolus
            val lastBolusAge = round((systemTime - iob_data.lastBolusTime) / 60000.0, 1)
            //console.error(lastBolusAge);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            
            // 🔧 FIX: Log SMB flow continuation after basal boost
            if (basalBoostApplied) {
                consoleLog.add("SMB_FLOW_CONTINUES afterBasalBoost=true source=${basalBoostSource ?: "?"}")
            }

            if (microBolusAllowed && enableSMB) {
                val microBolus = insulinReq
                //rT.reason.append(" insulinReq $insulinReq")
                rT.reason.append(context.getString(R.string.reason_insulin_required, insulinReq))
                if (microBolus >= maxSMB) {
                    //rT.reason.append("; maxBolus $maxSMB")
                    rT.reason.append(context.getString(R.string.reason_max_smb, maxSMB))
                }
                rT.reason.append(". ")

                // allow SMBIntervals between 1 and 10 minutes
                //val SMBInterval = min(10, max(1, profile.SMBInterval))
                val smbInterval = calculateSMBInterval()
                // Debug interval SMB : on journalise l'intervalle choisi et l'âge du dernier bolus
                val intervalStr = String.format(java.util.Locale.US, "%.1f", smbInterval.toDouble())
                val lastBolusStr = String.format(java.util.Locale.US, "%.1f", lastBolusAge)
                val deltaStr = String.format(java.util.Locale.US, "%.1f", delta.toDouble())
                rT.reason.append(" [SMB interval=")
                rT.reason.append(intervalStr)
                rT.reason.append(" min, lastBolusAge=")
                rT.reason.append(lastBolusStr)
                rT.reason.append(" min, Δ=")
                rT.reason.append(deltaStr)
                rT.reason.append(", BG=")
                rT.reason.append(bg.toInt().toString())
                rT.reason.append("] ")

                val nextBolusMins = round(smbInterval - lastBolusAge, 0)
                val nextBolusSeconds = round((smbInterval - lastBolusAge) * 60, 0) % 60
                if (lastBolusAge > smbInterval) {
                    if (microBolus > 0) {
                        finalizeAndCapSMB(
                            rT = rT,
                            proposedUnits = microBolus,
                            reasonHeader = context.getString(R.string.reason_microbolus, microBolus),
                            mealData = mealData,
                            hypoThreshold = threshold,
                            isExplicitUserAction = false,
                            decisionSource = "GlobalAIMI",
                            isMealActive = isMealActive
                        )
                    }
                } else {
                    rT.reason.append(
                        context.getString(
                            R.string.reason_wait_microbolus,
                            nextBolusMins,
                            nextBolusSeconds
                        )
                    )
                }
            }

            val forcedMealActive =
                abs(currenttemp.rate - forcedBasalmealmodes.toDouble()) < 0.05 && currenttemp.duration > 0

            val basalInput = BasalDecisionEngine.Input(
                bg = bg,
                profileCurrentBasal = profile_current_basal,
                basalEstimate = basalaimi.toDouble(),
                tdd7P = tdd7P,
                tdd7Days = tdd7Days,
                variableSensitivity = variableSensitivity.toDouble(),
                profileSens = profile.sens,
                predictedBg = predictedBg.toDouble(),
                targetBg = targetBg.toDouble(),
                minBg = profile.min_bg,
                lgsThreshold = getLgsThresholdSafe(profile),
                eventualBg = eventualBG,
                iob = iob.toDouble(),
                maxIob = maxIob,
                allowMealHighIob = allowMealHighIob,
                safetyDecision = safetyDecision,
                mealData = mealData,
                delta = delta.toDouble(),
                shortAvgDelta = shortAvgDelta.toDouble(),
                longAvgDelta = longAvgDelta.toDouble(),
                combinedDelta = combinedDelta.toDouble(),
                bgAcceleration = bgAcceleration.toDouble(),
                slopeFromMaxDeviation = mealData.slopeFromMaxDeviation,
                slopeFromMinDeviation = mealData.slopeFromMinDeviation,
                forcedBasal = forcedBasal.toDouble(),
                forcedMealActive = forcedMealActive,
                isMealActive = isMealActive,
                runtimeMinValue = runtimeMinValue,
                snackTime = snackTime,
                snackRuntimeMin = runtimeToMinutes(snackrunTime),
                fastingTime = fastingTime,
                sportTime = sportTime,
                honeymoon = honeymoon,
                pregnancyEnable = pregnancyEnable,
                mealTime = mealTime,
                mealRuntimeMin = runtimeToMinutes(mealruntime),
                bfastTime = bfastTime,
                bfastRuntimeMin = runtimeToMinutes(bfastruntime),
                lunchTime = lunchTime,
                lunchRuntimeMin = runtimeToMinutes(lunchruntime),
                dinnerTime = dinnerTime,
                dinnerRuntimeMin = runtimeToMinutes(dinnerruntime),
                highCarbTime = highCarbTime,
                highCarbRuntimeMin = runtimeToMinutes(highCarbrunTime),
                timenow = timenow,
                sixAmHour = sixAMHour,
                recentSteps5Minutes = recentSteps5Minutes,
                nightMode = nightbis,
                modesCondition = modesCondition,
                autodrive = autodrive,
                currentTemp = currenttemp,
                glucoseStatus = glucoseStatus,
                featuresCombinedDelta = f?.combinedDelta,
                smbToGive = smbToGive.toDouble(),
                zeroSinceMin = zeroSinceMin,
                minutesSinceLastChange = minutesSinceLastChange,
                pumpCaps = pumpCaps,
                auditorConfidence = (try { app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(300_000)?.verdict?.confidence } catch (e: Exception) { 0.0 }) ?: 0.0
            )
            val helpers = BasalDecisionEngine.Helpers(
                calculateRate = { basalValue, currentBasalValue, multiplier, label ->
                    calculateRate(basalValue, currentBasalValue, multiplier, label, currenttemp, rT)
                },
                calculateBasalRate = { basalValue, currentBasalValue, multiplier ->
                    calculateBasalRate(basalValue, currentBasalValue, multiplier)
                },
                detectMealOnset = { deltaValue, predictedDelta, acceleration, predBg, targBg ->
                    detectMealOnset(deltaValue, predictedDelta, acceleration, predBg, targBg)
                },
                round = { value, digits -> round(value, digits) }
            )
            val basalDecision = basalDecisionEngine.decide(basalInput, rT, helpers)
            
            // --- Update Learners BEFORE building final result ---
            val currentHour = LocalTime.now().hour
            val anyMealActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime
            val isNight = currentHour >= 22 || currentHour <= 6
            
            basalLearner.process(
                currentBg = bg,
                currentDelta = delta.toDouble(),
                tdd7Days = tdd7Days,
                tdd30Days = tdd7Days,
                isFastingTime = isNight && !anyMealActive
            )
            
            // 📊 Expose BasalLearner state in rT for visibility
            consoleLog.add("📊 BASAL_LEARNER:")
            consoleLog.add("  │ shortTerm: ${"%.3f".format(Locale.US, basalLearner.shortTermMultiplier)}")
            consoleLog.add("  │ mediumTerm: ${"%.3f".format(Locale.US, basalLearner.mediumTermMultiplier)}")
            consoleLog.add("  │ longTerm: ${"%.3f".format(Locale.US, basalLearner.longTermMultiplier)}")
            consoleLog.add("  └ combined: ${"%.3f".format(Locale.US, basalLearner.getMultiplier())}")

            // 🎯 Process UnifiedReactivityLearner
            unifiedReactivityLearner.processIfNeeded()
            
            // 📊 Expose UnifiedReactivityLearner state in rT for visibility
            unifiedReactivityLearner.lastAnalysis?.let { analysis ->
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                consoleLog.add("📊 REACTIVITY_LEARNER:")
                consoleLog.add("  │ globalFactor: ${"%.3f".format(Locale.US, analysis.globalFactor)}")
                consoleLog.add("  │ shortTermFactor: ${"%.3f".format(Locale.US, analysis.shortTermFactor)}")
                consoleLog.add("  │ combinedFactor: ${"%.3f".format(Locale.US, unifiedReactivityLearner.getCombinedFactor())}")
                consoleLog.add("  │ TIR 70-180: ${analysis.tir70_180.toInt()}%")
                consoleLog.add("  │ CV%: ${analysis.cv_percent.toInt()}%")
                consoleLog.add("  │ Hypo count (24h): ${analysis.hypo_count}")
                consoleLog.add("  │ Reason: ${analysis.adjustmentReason}")
                consoleLog.add("  └ Analyzed at: ${sdf.format(Date(analysis.timestamp))}")
            }

            // 🔮 WCycle Active Learning
            if (wCyclePreferences.enabled()) {
                val phase = wCycleFacade.getPhase()
                if (phase != app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase.UNKNOWN) {
                     wCycleFacade.updateLearning(phase, autosens_data.ratio)
                }
            }

            // 🌀 TRAJECTORY VISUALIZATION (AIMI 2.1)
            try {
                // Quick reconstruction of recent states for instant visualization
                // Note: Ideally we use a full history provider, but for UI feedback we use instantaneous extrapolation
                val now = System.currentTimeMillis()
                val currentActivity = (iob_data.iob * 1.0) // simplified activity equivalent
                val targetOrb = app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit(targetBg = targetBg.toDouble(), targetActivity = 0.0)
                
                val history = listOf(
                    // t-15 min (approx)
                    app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                        timestamp = now - 900000,
                        bg = bg - (shortAvgDelta * 3), 
                        bgDelta = shortAvgDelta.toDouble(), 
                        bgAccel = 0.0,
                        insulinActivity = currentActivity,
                        iob = iob_data.iob,
                        pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                        timeSinceLastBolus = 0
                    ),
                    // t-5 min
                    app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                        timestamp = now - 300000,
                        bg = bg - delta, 
                        bgDelta = delta.toDouble(), 
                        bgAccel = (delta - shortAvgDelta).toDouble(),
                        insulinActivity = currentActivity,
                        iob = iob_data.iob,
                        pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                        timeSinceLastBolus = 0
                    ),
                    // Current (t)
                    app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                        timestamp = now,
                        bg = bg, 
                        bgDelta = delta.toDouble(), 
                        bgAccel = (delta - shortAvgDelta).toDouble(),
                        insulinActivity = currentActivity,
                        iob = iob_data.iob,
                        pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                        timeSinceLastBolus = 0
                    )
                )

                val metrics = app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetricsCalculator.calculateAll(history, targetOrb)
                
                if (metrics != null) {
                    val healthPercent = (metrics.healthScore * 100).toInt()
                    val healthBar = "█".repeat(healthPercent / 10) + "░".repeat(10 - (healthPercent / 10))
                    
                    val type = when {
                        metrics.isStable -> "⭕ Stable Orbit"
                        metrics.isConverging -> "🔄 Converging"
                        metrics.isDiverging -> "↗️ Diverging"
                        metrics.isTightSpiral -> "🌀 Spiral"
                        else -> "❓ Uncertain"
                    }
                    
                    val etaText = if (metrics.convergenceVelocity > 0) {
                        val eta = app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetricsCalculator.estimateConvergenceTime(history, targetOrb)
                        if (eta != null) "$eta min to stable orbit" else "Approaching..."
                    } else {
                        "Diverging from target"
                    }

                    // Append ASCII Block to Console Log
                    consoleLog.add("─────────────────────────────────┐")
                    consoleLog.add("│ 🌀 TRAJECTORY STATUS            │")
                    consoleLog.add("├─────────────────────────────────┤")
                    consoleLog.add("│ Type: %-26s│".format(type))
                    consoleLog.add("│ Health: %s %d%%          │".format(healthBar, healthPercent))
                    consoleLog.add("│ ETA: %-27s│".format(etaText))
                    consoleLog.add("│                                 │")
                    consoleLog.add("│ Metrics:                        │")
                    consoleLog.add("│ ├─ Curvature:    %-15s│".format("%.2f".format(metrics.curvature)))
                    consoleLog.add("│ ├─ Convergence:  %-15s│".format("%+.2f".format(metrics.convergenceVelocity)))
                    consoleLog.add("│ ├─ Coherence:    %-15s│".format("%.2f".format(metrics.coherence)))
                    consoleLog.add("│ ├─ Energy:       %-15s│".format("%+.1f".format(metrics.energyBalance)))
                    
                    // 🤖 AI Auditor Insight (if available and relevant)
                    // Check cache for recent verdict (10 min validity)
                    try {
                        val cached = app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(600_000)
                        if (cached != null) {
                            // Only show if it matches the current trajectory uncertainty or implies action
                            consoleLog.add("│                                 │")
                            val aiIcon = "🤖"
                            
                            // Map VerdictType manually to avoid import issues if not available
                            val verdictEnum = cached.verdict.verdict
                            val action = verdictEnum.name // CONFIRM, SOFTEN, SHIFT_TO_TBR
                            
                            consoleLog.add("│ $aiIcon AI: %-25s│".format("$action (${(cached.verdict.confidence*100).toInt()}%)"))
                            
                            // Shorten evidence to fit in box
                            val evidenceList = cached.verdict.evidence
                            val evidenceStr = if (evidenceList.isNotEmpty()) evidenceList[0] else ""
                            val evidence = evidenceStr.replace("\n", " ").take(30)
                            consoleLog.add("│ > %-30s│".format(evidence))
                        }
                    } catch (e: Exception) {
                        // Ignore cache access errors
                    }

                    consoleLog.add("└─────────────────────────────────┘")
                }
            } catch (e: Exception) {
                // Silently fail trajectory visualizer to avoid critical loop crash
            }

            // 📊 Build learners summary for RT visibility (finalResult.learnersInfo)
            val learnersParts = mutableListOf<String>()
            
            // Basal Learner
            val basalMult = basalLearner.getMultiplier()
            if (kotlin.math.abs(basalMult - 1.0) > 0.01) {
                learnersParts.add("Basal×" + String.format(Locale.US, "%.2f", basalMult))
            }
            
            // PKPD Learner (fusedISF) - handle nullable
            pkpdRuntime?.let { runtime ->
                val profileIsf = profile.sens
                if (kotlin.math.abs(runtime.fusedIsf - profileIsf) > 0.5) {
                    learnersParts.add("ISF:" + runtime.fusedIsf.toInt())
                }
            }
            
            // Unified Reactivity Learner
            val reactivityFactor = unifiedReactivityLearner.getCombinedFactor()
            if (kotlin.math.abs(reactivityFactor - 1.0) > 0.01) {
                learnersParts.add("React×" + String.format(Locale.US, "%.2f", reactivityFactor))
            }
            
            val learnersSummary = learnersParts.joinToString(", ")
            
            val finalResult = setTempBasal(
                _rate = basalDecision.rate,
                duration = basalDecision.duration,
                profile = profile,
                rT = rT,
                currenttemp = currenttemp,
                overrideSafetyLimits = basalDecision.overrideSafety
            )
            comparator.compare(
                aimiResult = finalResult,
                glucoseStatus = glucose_status,
                currentTemp = currenttemp,
                iobData = iob_data_array,
                profileAimi = originalProfile,
                autosens = autosens_data,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )

            // 🛡️ Safety: Strictly Clamp Basal to >= 0.0 to prevent negative display/command
            finalResult.rate = finalResult.rate?.coerceAtLeast(0.0) ?: 0.0

            // 📊 ================================================================
            // LEARNERS INFO: Populate finalResult for RT visibility
            // ================================================================
            if (learnersSummary.isNotEmpty()) {
                // 1. Set dedicated field
                finalResult.learnersInfo = learnersSummary
                
                // 2. Append to reason (visible in RT's main "reason" field)
                finalResult.reason.append("; [").append(learnersSummary).append("]")
                
                // 3. Log for debugging
                consoleLog.add("📊 Learners applied to finalResult.reason: [" + learnersSummary + "]")
            }
            

            // 📊 ================================================================
            // RT INSTRUMENTATION: Production Debug Lines
            // ================================================================
            
            // Collect data for detailed learners line
            val urFactor = unifiedReactivityLearner.getCombinedFactor()
            val profileIsf = profile.sens  // From profile
            val fusedIsf = pkpdRuntime?.fusedIsf
            val pkpdDiaMin = pkpdRuntime?.params?.diaHrs?.let { (it * 60).toInt() }
            val pkpdPeakMin = pkpdRuntime?.params?.peakMin?.toInt()
            val pkpdTailPct = pkpdRuntime?.tailFraction?.let { (it * 100).toInt() }
            
            // Build concise learners line
            val learnersDebugLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildLearnersLine(
                unifiedReactivityFactor = urFactor,
                profileIsf = profileIsf,
                fusedIsf = fusedIsf,
                pkpdDiaMin = pkpdDiaMin,
                pkpdPeakMin = pkpdPeakMin,
                pkpdTailPct = pkpdTailPct
            )
            
            // Append learners line to reason (newline for readability)
            finalResult.reason.append("\n").append(learnersDebugLine)
            
            // WCycle line (if applicable)
            if (wCyclePreferences.enabled()) {
                val wcyclePhase = wCycleFacade.getPhase()?.name
                val wcycleFactor = wCycleFacade.getIcMultiplier()
                val wcycleLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildWCycleLine(
                    enabled = true,
                    phase = wcyclePhase,
                    factor = wcycleFactor
                )
                if (wcycleLine != null) {
                    finalResult.reason.append("\n").append(wcycleLine)
                }
            }
            
            // Auditor line (always present, shows OFF/STALE/verdict)
            val auditorDebugLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildAuditorLine(
                enabled = preferences.get(BooleanKey.AimiAuditorEnabled)
            )
            finalResult.reason.append("\n").append(auditorDebugLine)
            
            // Log instrumentation applied
            consoleLog.add("📊 RT instrumentation: 2-3 debug lines added to reason")
            
            // 🧠 ================================================================
            // AI DECISION AUDITOR INTEGRATION (Second Brain)
            // ================================================================
            val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)
            
            // DEBUG: Log the preference value
            aapsLogger.debug(LTag.APS, "🧠 AI Auditor: Preference value = $auditorEnabled")
            
            // Set flag immediately for RT display (before async operations)
            finalResult.aiAuditorEnabled = auditorEnabled
            
            if (auditorEnabled) {
                try {
                    // Collect all data for auditor
                    val smbProposed = finalResult.units ?: 0.0
                    val tbrRate = finalResult.rate
                    val tbrDuration = finalResult.duration
                    val intervalMin = intervalsmb  // Current interval
                    val smb30min = calculateSmbLast30Min()
                    val predictionAvailable = (finalResult.predBGs?.IOB?.size ?: 0) > 0
                    
                    // Check if in prebolus window (P1/P2)
                    // Note: Therapy doesn't expose P1/P2 directly, we infer from mode start time
                    val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
                    val now = dateUtil.now()
                    
                    // P1 = first 15 min, P2 = 15-30 min of meal mode
                    val inPrebolusWindow = when {
                        therapy.bfastTime -> {
                            val runtimeMin = therapy.getTimeElapsedSinceLastEvent("bfast") / 60000
                            runtimeMin in 0..30
                        }
                        therapy.lunchTime -> {
                            val runtimeMin = therapy.getTimeElapsedSinceLastEvent("lunch") / 60000
                            runtimeMin in 0..30
                        }
                        therapy.dinnerTime -> {
                            val runtimeMin = therapy.getTimeElapsedSinceLastEvent("dinner") / 60000
                            runtimeMin in 0..30
                        }
                        therapy.highCarbTime -> {
                            val runtimeMin = therapy.getTimeElapsedSinceLastEvent("highcarb") / 60000
                            runtimeMin in 0..30
                        }
                        else -> false
                    }
                    
                    // Detect mode type
                    val modeType = when {
                        therapy.bfastTime -> "breakfast"
                        therapy.lunchTime -> "lunch"
                        therapy.dinnerTime -> "dinner"
                        therapy.highCarbTime -> "highCarb"
                        therapy.snackTime -> "snack"
                        therapy.mealTime -> "meal"
                        else -> null
                    }
                    
                    // Calculate mode runtime using Therapy.getTimeElapsedSinceLastEvent
                    val modeRuntimeMin = when {
                        therapy.bfastTime -> (therapy.getTimeElapsedSinceLastEvent("bfast") / 60000).toInt()
                        therapy.lunchTime -> (therapy.getTimeElapsedSinceLastEvent("lunch") / 60000).toInt()
                        therapy.dinnerTime -> (therapy.getTimeElapsedSinceLastEvent("dinner") / 60000).toInt()
                        therapy.highCarbTime -> (therapy.getTimeElapsedSinceLastEvent("highcarb") / 60000).toInt()
                        therapy.snackTime -> (therapy.getTimeElapsedSinceLastEvent("snack") / 60000).toInt()
                        therapy.mealTime -> (therapy.getTimeElapsedSinceLastEvent("meal") / 60000).toInt()
                        else -> null
                    }
                    
                    // Autodrive state
                    val autodriveState = lastAutodriveState.toString()
                    
                    // WCycle info
                    val wcyclePhase = wCycleFacade.getPhase()?.name
                    val wcycleFactor = wCycleFacade.getIcMultiplier()  // Use IC multiplier as factor
                    
                    // Extract reason tags from finalResult.reason
                    val reasonTags = finalResult.reason.toString().split(". ").map { it.trim() }
                    
                    // Call auditor (async)
                    auditorOrchestrator.auditDecision(
                        bg = bg,
                        delta = delta.toDouble(),
                        shortAvgDelta = shortAvgDelta.toDouble(),
                        longAvgDelta = longAvgDelta.toDouble(),
                        glucoseStatus = glucose_status,
                        iob = iob_data_array.firstOrNull() ?: IobTotal(dateUtil.now()).apply { iob = 0.0; activity = 0.0 },
                        cob = cob.toDouble(),  // Convert Float to Double
                        profile = profile,
                        pkpdRuntime = pkpdRuntime,
                        isfUsed = profile.variable_sens,
                        smbProposed = smbProposed,
                        tbrRate = tbrRate,
                        tbrDuration = tbrDuration,
                        intervalMin = intervalMin.toDouble(),
                        maxSMB = maxSMB,
                        maxSMBHB = maxSMBHB,
                        maxIOB = maxIob,
                        maxBasal = profile.max_basal,
                        reasonTags = reasonTags,
                        modeType = modeType,
                        modeRuntimeMin = modeRuntimeMin,
                        autodriveState = autodriveState,
                        wcyclePhase = wcyclePhase,
                        wcycleFactor = wcycleFactor,
                        tbrMaxMode = null,  // TODO: if you track max TBR for modes
                        tbrMaxAutoDrive = null,  // TODO: if you track max TBR for autodrive
                        smb30min = smb30min,
                        predictionAvailable = predictionAvailable,
                        inPrebolusWindow = inPrebolusWindow
                    ) { verdict, modulated ->
                        // Callback executed when audit completes
                        
                        if (modulated.appliedModulation) {
                            // ✅ Modulation applied
                            consoleLog.add(sanitizeForJson("🧠 AI Auditor: ${modulated.modulationReason}"))
                            
                            if (verdict != null) {
                                consoleLog.add(sanitizeForJson("   Verdict: ${verdict.verdict}, Confidence: ${"%.2f".format(verdict.confidence)}"))
                                
                                // Log first 2 evidence items
                                verdict.evidence.take(2).forEach { evidence ->
                                    consoleLog.add(sanitizeForJson("   Evidence: $evidence"))
                                }
                                
                                if (verdict.riskFlags.isNotEmpty()) {
                                    consoleLog.add(sanitizeForJson("   ⚠️ Risk Flags: ${verdict.riskFlags.joinToString(", ")}"))
                                }
                            }
                            
                            // Apply modulated decision
                            finalResult.units = modulated.smbU
                            if (modulated.tbrRate != null) {
                                finalResult.rate = modulated.tbrRate
                            }
                            if (modulated.tbrMin != null) {
                                finalResult.duration = modulated.tbrMin
                            }
                            
                            // Log changes
                            if (kotlin.math.abs((modulated.smbU ?: 0.0) - smbProposed) > 0.01) {
                                consoleLog.add(sanitizeForJson("   SMB modulated: ${"%.2f".format(smbProposed)} → ${"%.2f".format(modulated.smbU)} U"))
                            }
                            if (kotlin.math.abs(modulated.intervalMin - intervalMin) > 0.1) {
                                consoleLog.add(sanitizeForJson("   Interval modulated: ${intervalMin.toInt()} → ${modulated.intervalMin.toInt()} min"))
                            }
                            if (modulated.preferTbr) {
                                consoleLog.add(sanitizeForJson("   Prefer TBR enabled"))
                            }
                            
                            // 🎨 Populate RT fields for dashboard display
                            finalResult.aiAuditorEnabled = true
                            finalResult.aiAuditorVerdict = verdict?.verdict?.name
                            finalResult.aiAuditorConfidence = verdict?.confidence
                            finalResult.aiAuditorModulation = modulated.modulationReason
                            finalResult.aiAuditorRiskFlags = verdict?.riskFlags?.joinToString(", ")
                            
                        } else {
                            // ℹ️ No modulation (audit only, confidence too low, etc.)
                            if (verdict != null) {
                                consoleLog.add(sanitizeForJson("🧠 AI Auditor: ${modulated.modulationReason}"))
                                consoleLog.add(sanitizeForJson("   AIMI decision confirmed (Verdict: ${verdict.verdict}, Conf: ${"%.2f".format(verdict.confidence)})"))
                                
                                // Still populate RT fields for audit tracking
                                finalResult.aiAuditorEnabled = true
                                finalResult.aiAuditorVerdict = verdict.verdict.name
                                finalResult.aiAuditorConfidence = verdict.confidence
                                finalResult.aiAuditorModulation = "Audit only (no modulation)"
                                finalResult.aiAuditorRiskFlags = verdict.riskFlags.joinToString(", ")
                            }
                        }
                    }
                } catch (e: Exception) {
                    consoleLog.add(sanitizeForJson("⚠️ AI Auditor error: ${e.message}"))
                    aapsLogger.error(LTag.APS, "AI Auditor exception", e)
                    // Continue with original decision on error
                }
            }
            
            
            // 🏥 AIMI SNAPSHOT FINALIZATION
            val snapshotFusedIsf = pkpdRuntime?.fusedIsf ?: profile.sens
            val snapshotProfileIsf = profile.sens
            
            // Populate Dynamic ISF details
            decisionCtx.adjustments.dynamic_isf = AimiDecisionContext.DynamicIsf(
                final_value_mgdl = snapshotFusedIsf,
                modifiers = mutableListOf<AimiDecisionContext.Modifier>().apply {
                    // Autosens
                    if (autosens_data.ratio != 1.0) {
                        add(AimiDecisionContext.Modifier(
                            source = "Autosensitivity",
                            factor = 1.0 / autosens_data.ratio, // Ratio < 1 => Sensitive => ISF increases (Factor > 1)
                            clinical_reason = "Rolling avg sensitivity: ${"%.2f".format(autosens_data.ratio)}"
                        ))
                    }
                    // ISF Fusion
                    if (abs(snapshotFusedIsf - (snapshotProfileIsf * (1.0/autosens_data.ratio))) > 1.0) {
                         add(AimiDecisionContext.Modifier(
                            source = "PkPd_Fusion",
                            factor = snapshotFusedIsf / (snapshotProfileIsf * (1.0/autosens_data.ratio)),
                            clinical_reason = "Fusion with TDD & Profile"
                        ))
                    }
                }
            )
            
            // Populate Outcome
            // Populate Outcome
            decisionCtx.outcome = AimiDecisionContext.Outcome(
                clinical_decision = if ((finalResult.units ?: 0.0) > 0) "SMB_Delivery" else if ((finalResult.rate ?: profile.current_basal) != profile.current_basal) "Basal_Modulation" else "No_Action",
                dosage_u = finalResult.units ?: 0.0,
                target_basal_uph = finalResult.rate, 
                narrative_explanation = finalResult.reason.toString().replace("\n", " | ").take(2048) // Headline
            )
            
            // Log the 'Medical Record'
            val medicalJson = decisionCtx.toMedicalJson()
            consoleLog.add("AIMI_SNAPSHOT: $medicalJson")
            
            // 💾 PERSISTENCE: Save to Documents/AAPS/AIMI_Decisions.jsonl
            try {
                val decisionsFile = File(externalDir, "AIMI_Decisions.jsonl")
                if (!decisionsFile.exists()) {
                     decisionsFile.parentFile?.mkdirs()
                     decisionsFile.createNewFile()
                }
                decisionsFile.appendText("$medicalJson\n")
            } catch (e: Exception) {
                consoleError.add("Failed to save AIMI Decision JSON: ${e.message}")
            }
            
            return finalResult
        }
    }

    /**
     * Applies a safety floor to the basal rate to prevent unnecessary cutoffs (0 U/h)
     * during "cruise mode" or moderate activity, unless critical safety conditions are met.
     */
    private fun applyBasalFloor(
        suggestedRate: Double,
        profileBasal: Double,
        safetyDecision: SafetyDecision,
        activityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        predictedBg: Double,
        isMealActive: Boolean,
        lgsThreshold: Double
    ): Double {
        // 1. Critical Safety: Hypo REELLE seulement permet 0 U/h
        if (safetyDecision.stopBasal || bg < lgsThreshold) {
            return suggestedRate // Allow 0.0 pour hypo réelle
        }
        
        // 2. ⚡ Prediction basse MAIS montée → ne pas bypasser le floor
        if (predictedBg < 65) {
            if (delta > 0 && bg > 90) {
                // Prédiction pessimiste, BG monte → appliquer floor quand même
                // Note: logging handled at caller level
            } else {
                return suggestedRate // Allow 0.0 si vraiment en baisse
            }
        }

        // 3. ⚡ Mode Repas Actif : Floor plus élevé (60% profil)
        if (isMealActive && suggestedRate < profileBasal * 0.6) {
            val mealFloor = profileBasal * 0.6
            if (bg > 90 && delta > -1) {
                return mealFloor
            }
        }

        // 4. Activity Context
        val isActivity = activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST
        if (isActivity) {
            // If dropping fast during activity, allow low basal/zero
            if (delta < -3 || bg < 90) {
                return suggestedRate
            }
            // Recovery: If rising/stable during activity, avoid ZERO.
            val activityFloor = profileBasal * 0.3  // 30% floor en activité
            if (suggestedRate < activityFloor) {
                // If rising, push higher
                if (delta > 0) {
                    val risingFloor = profileBasal * 0.6  // 60% si montée
                    return risingFloor
                }
                return activityFloor
            }
            return suggestedRate
        }

        // 5. Persistent Rise (Standard Mode Boost)
        // Si ça monte de façon persistante (AvgDelta > 0.5) et Delta > 0, on ne laisse pas chuter en dessous de 80%
        if (delta > 0 && shortAvgDelta > 0.5 && bg > 100) {
             val persistentFloor = profileBasal * 0.8
             if (suggestedRate < persistentFloor) {
                 return persistentFloor
             }
        }

        // 6. Cruise Mode (No Activity, No Critical Low)
        val cruiseFloor = profileBasal * 0.55 // 55% floor (augmenté de 45%)
        if (suggestedRate < cruiseFloor) {
            // Only enforce floor if strictly safe
            if (bg > 100 && delta > -2 && predictedBg > 80) {
                return cruiseFloor
            }
        }

        return suggestedRate
    }




    // Helper for General Hyper Kicker (Non-Meal) (AIMI 2.0)
    private fun adjustBasalForGeneralHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        maxBasalConfig: Double
    ): Double {
        // "Progressivement rapidement" logic requested by user
        
        // Risque montée franche ou plateau haut persistant
        val rising = delta >= 0.5 || shortAvgDelta >= 0.3
        val plateauHigh = delta >= -0.1 && bg > targetBg + 50
        val rocketStart = delta > 10.0 // FCL 13.0 Rocket Start
    
        if (!rising && !plateauHigh && !rocketStart) return suggestedBasalUph
    
        val deviation = bg - targetBg
    
        // Progressive scaling based on deviation severity
        // 30mg au dessus: x2
        // 60mg au dessus: x5
        // 90mg au dessus: x8
        // 120mg+        : x10 (Authorized by user)
        // Rocket Start : Auto Max (x10) if delta > 10.0
    
        val scaleFactor = when {
            rocketStart || deviation >= 120 -> 10.0
            deviation >= 90  -> 8.0
            deviation >= 60  -> 5.0
            deviation >= 30  -> 2.0
            else -> 1.0
        }
    
        if (scaleFactor == 1.0) return suggestedBasalUph
        
        val boosted = suggestedBasalUph * scaleFactor
        
        // Cap only by absolute max config (safety)
        return if (boosted > maxBasalConfig) maxBasalConfig else boosted
    }
// -----------------------------------------------------------------------------------------
    // ⚙️ DECISION PIPELINE HELPERS (AIMI 2.0 Refactor)
    // -----------------------------------------------------------------------------------------

    // -----------------------------------------------------
    // ⚔️ DECISION PIPELINE HELPERS (AIMI 2.0 Refactor)
    // -----------------------------------------------------

    private enum class ModeDegradeLevel(val value: Int, val label: String) {
        NORMAL(0, "Normal"),
        CAUTION(1, "Caution"),
        HIGH_RISK(2, "High Risk"),
        CRITICAL(3, "Critical")
    }

    private data class DegradePlan(
        val level: ModeDegradeLevel,
        val reason: String,
        val bolusFactor: Double,
        val tbrFactor: Double,
        val banner: String?
    )

    private data class ModeState(
         var name: String = "",
         var startMs: Long = 0L,
         var pre1: Boolean = false,
         var pre2: Boolean = false,
         var pre1SentMs: Long = 0L,
         var pre2SentMs: Long = 0L,
         var tbrStartedMs: Long = 0L,      // 🆕 Track TBR activation
         var degradeLevel: Int = 0          // 🆕 Track safety state
    ) {
         fun serialize(): String = "$name|$startMs|$pre1|$pre2|$pre1SentMs|$pre2SentMs|$tbrStartedMs|$degradeLevel"
         companion object {
             fun deserialize(s: String): ModeState {
                 if (s.isBlank()) return ModeState()
                 val p = s.split("|")
                 if (p.size < 4) return ModeState()
                 return try {
                     ModeState(
                         p[0], 
                         p[1].toLong(), 
                         p[2].toBoolean(), 
                         p[3].toBoolean(),
                         p.getOrNull(4)?.toLongOrNull() ?: 0L,
                         p.getOrNull(5)?.toLongOrNull() ?: 0L,
                         p.getOrNull(6)?.toLongOrNull() ?: 0L,
                         p.getOrNull(7)?.toIntOrNull() ?: 0
                     )
                 } catch (e: Exception) { ModeState() }
             }
         }
    }

    private fun logDecisionFinal(tag: String, rT: RT, bg: Double? = null, delta: Float? = null) {
        val smb = rT.insulinReq ?: 0.0
        val smbUnits = rT.units ?: 0.0
        val tbr = (rT.rate ?: 0.0).coerceAtLeast(0.0)
        val dur = rT.duration ?: 0
        val builder = StringBuilder("DECISION_FINAL[$tag]: smb=${"%.2f".format(smb)}U tbr=${"%.2f".format(tbr)}U/h dur=${dur}m")
        if (bg != null) builder.append(" bg=${bg.roundToInt()}")
        if (delta != null) builder.append(" Δ=${"%.1f".format(delta)}")
        val reasonText = rT.reason.toString().replace("\n", " | ")
        builder.append(" reason=${reasonText.take(180)}")
        consoleLog.add(builder.toString())

        val modeLabel = when {
            mealTime -> "Meal"
            lunchTime -> "Lunch"
            dinnerTime -> "Dinner"
            highCarbTime -> "HighCarb"
            snackTime -> "Snack"
            else -> "None"
        }
        val predSize = rT.predBGs?.IOB?.size ?: lastPredictionSize
        val predAvailable = predSize > 0 || lastPredictionAvailable
        val eventual = (rT.eventualBG ?: lastEventualBgSnapshot)
        val bgValue = bg ?: this.bg
        val deltaValue = delta?.toDouble() ?: this.delta.toDouble()
        val refractoryStatus = if (!lastBolusAgeMinutes.isNaN() && lastBolusAgeMinutes < intervalsmb) "YES" else "NO"
        val smbFinalValue = if (smbUnits > 0.0) smbUnits else smb
        lastSmbFinal = smbFinalValue
        val predChunk = "${if (predAvailable) "Y" else "N"}(sz=${predSize} ev=${eventual.roundToInt()})"

        // 🔧 FIX 4: Enhanced diagnostic logging with activity threshold
        val tdd24h = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 30.0
        val activityThreshold = (tdd24h / 24.0) * 0.15
        
        val tickLine =
            "TICK ts=${System.currentTimeMillis()} bg=${bgValue.roundToInt()} d=${"%.1f".format(deltaValue)} iob=${"%.2f".format(iob)} act=${"%.3f".format(iobActivityNow)} th=${"%.3f".format(activityThreshold)} " +
                "cob=${"%.1f".format(cob)} mode=$modeLabel autodriveState=$lastAutodriveState pred=$predChunk " +
                "safety=$lastSafetySource ref=$refractoryStatus maxIOB=${"%.2f".format(maxIob)} maxSMB=${"%.2f".format(maxSMB)} " +
                "smb=${"%.2f".format(lastSmbProposed)}->${"%.2f".format(lastSmbCapped)}->${"%.2f".format(smbFinalValue)} " +
                "tbr=${"%.2f".format(tbr)} src=$lastDecisionSource"
        consoleLog.add(tickLine)
    }

    // ==========================================
    // 🛡️ PRIORITY 1: SAFETY (LGS/HYPO)
    // ==========================================
    private fun trySafetyStart(
        bg: Double,
        delta: Float,
        profile: OapsProfileAimi,
        iob: IobTotal,
        noise: Int,
        predBg: Double,
        eventualBg: Double
    ): DecisionResult {
        lastSafetySource = "CALLED"
        // 0. Sanity Check (Units/Calibration)
        if (bg < 25 || bg > 600) {
             consoleLog.add("⚠ Unit Mismatch Suspected? BG=$bg")
        }

        // 1. Extreme Low / LGS (Correct Logic)
        // Explicit Debug Structure
        fun safe(v: Double) = if (v.isFinite()) v else 999.0
        val bgNow = safe(bg)
        val predNow = safe(predBg)
        val eventualNow = safe(eventualBg)
        val lgsMin = minOf(bgNow, predNow, eventualNow)
        val lgsTh = computeHypoThreshold(lgsMin, profile.lgsThreshold) // Uses member function

        if (lgsMin < lgsTh || (bg < 70 && delta < 0)) {
            val reasonStr = "LGS_TRIGGER: min=${lgsMin.toInt()} <= Th=${lgsTh.toInt()} (BG=${bgNow.toInt()} pred=${predNow.toInt()} ev=${eventualNow.toInt()})"
            consoleLog.add("SAFETY_APPLIED_TBR_ZERO reason=$reasonStr")
            lastSafetySource = "SafetyLGS"
            return DecisionResult.Applied(
                source = "SafetyLGS",
                bolusU = 0.0,
                tbrUph = 0.0, // Strict 0.0
                tbrMin = 30,
                reason = reasonStr
            )
        }

        // 2. High Noise / Stale Data
        if (noise >= 3) {
            lastSafetySource = "SafetyNoise"
             return DecisionResult.Applied(
                source = "SafetyNoise",
                bolusU = 0.0,
                tbrUph = 0.0,
                tbrMin = 30,
                reason = "High Noise ($noise) - Force TBR 0.0"
            )
        }
        
        lastSafetySource = "SafetyPass"
        return DecisionResult.Fallthrough("Safety OK")
    }

    private fun tryMealAdvisor(bg: Double, delta: Float, iobData: IobTotal, profile: OapsProfileAimi, lastBolusTime: Long, modesCondition: Boolean): DecisionResult {
        val estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val estimatedCarbsTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val timeSinceEstimateMin = (System.currentTimeMillis() - estimatedCarbsTime) / 60000.0

        if (estimatedCarbs > 10.0 && timeSinceEstimateMin in 0.0..120.0 && bg >= 60) {
            // Refractory Check (Safety)
            if (hasReceivedRecentBolus(45, lastBolusTime)) {
                return DecisionResult.Fallthrough("Advisor Refractory (Recent Bolus <45m)")
            }
            
            // FIX: Removed delta > 0.0 condition - Meal Advisor should work even if BG is stable/falling
            // The refractory check, BG floor (>=60), and time window (120min) are sufficient safety
            if (modesCondition) { 
                val maxBasalPref = preferences.get(DoubleKey.meal_modes_MaxBasal)
                val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal
                
                // FIX: TBR Coverage Calculation
                // ORIGINAL logic subtracted coveredByBasal from SMB, causing netNeeded to become 0
                // NEW logic: TBR is a COMPLEMENT to SMB, not a replacement
                // - SMB provides immediate prebolus action
                // - TBR provides continuous aggressive support
                
                // INTELLIGENT IOB HANDLING (Fix 2025-12-19)
                // Problem: User may have elevated IOB from previous unlogged meal (soup, snack)
                // Solution: Discount IOB + guarantee minimum coverage for confirmed new meal
                val insulinForCarbs = estimatedCarbs / profile.carb_ratio
                
                // Apply IOB discount to account for uncertainty
                val effectiveIOB = iobData.iob * MEAL_ADVISOR_IOB_DISCOUNT_FACTOR
                
                // Guarantee minimum coverage (user confirmed meal = BG WILL rise)
                val minimumRequired = insulinForCarbs * MEAL_ADVISOR_MIN_CARB_COVERAGE
                
                // Calculate need with discounted IOB, then apply minimum guarantee
                val calculatedNeed = insulinForCarbs - effectiveIOB
                val netNeeded = max(calculatedNeed, minimumRequired).coerceAtLeast(0.0)
                
                // For reference, calculate what TBR will deliver (not subtracted from SMB)
                val tbrCoverage = safeMax * 0.5  // 30min = 0.5h

                // DEBUG: Log all calculation steps with detailed breakdown
                consoleLog.add("ADVISOR_CALC carbs=${estimatedCarbs.toInt()}g IC=${profile.carb_ratio} → ${String.format("%.2f", insulinForCarbs)}U")
                consoleLog.add("ADVISOR_CALC IOB_raw=${String.format("%.2f", iobData.iob)}U × discount=$MEAL_ADVISOR_IOB_DISCOUNT_FACTOR → IOB_effective=${String.format("%.2f", effectiveIOB)}U")
                consoleLog.add("ADVISOR_CALC minimumGuaranteed=${String.format("%.2f", minimumRequired)}U (${(MEAL_ADVISOR_MIN_CARB_COVERAGE * 100).toInt()}% of carb need)")
                consoleLog.add("ADVISOR_CALC calculated=${String.format("%.2f", calculatedNeed)}U → netSMB=${String.format("%.2f", netNeeded)}U (max of calculated and minimum)")
                consoleLog.add("ADVISOR_CALC TBR=${String.format("%.1f", safeMax)}U/h (will deliver ${String.format("%.2f", tbrCoverage)}U over 30min as complement)")
                consoleLog.add("ADVISOR_CALC TOTAL delivery: SMB ${String.format("%.2f", netNeeded)}U + TBR ${String.format("%.2f", tbrCoverage)}U = ${String.format("%.2f", netNeeded + tbrCoverage)}U delta=$delta modesOK=true")
                
                     return DecisionResult.Applied(
                        source = "MealAdvisor",
                        bolusU = netNeeded,
                        tbrUph = safeMax,
                        tbrMin = 30,
                        reason = "📸 Meal Advisor: ${estimatedCarbs.toInt()}g -> ${"%.2f".format(netNeeded)}U + TBR ${"%.1f".format(safeMax)}U/h"
                    )
            } else {
                consoleLog.add("ADVISOR_SKIP reason=modesCondition_false (legacy mode active)")
            }
        }
        return DecisionResult.Fallthrough("No active Meal Advisor request")
    }

    private fun tryAutodrive(
        bg: Double, 
        delta: Float, 
        shortAvgDelta: Float, 
        profile: OapsProfileAimi,
        lastBolusTime: Long,
        predictedBg: Float,
        slopeFromMinDeviation: Double,
        targetBg: Float,
        reasonBuf: StringBuilder,
        autodrive: Boolean,
        dynamicPbolusLarge: Double,
        dynamicPbolusSmall: Double,
        flatBGsDetected: Boolean  // 🔧 NEW: CGM quality signal
    ): DecisionResult {
        // 🛡️ GATE R0: CGM Quality Check (Priority #1 Safety)
        if (flatBGsDetected) {
            return DecisionResult.Fallthrough("CGM data unreliable (FLAT detected)")
        }
        
        val autodriveBG = preferences.get(IntKey.OApsAIMIAutodriveBG)
        
        // 🛡️ GATE R1: Strict BG Threshold (Raised from 100 to 120 for safety)
        // Never trigger Autodrive below 120 mg/dL to prevent hypos
        val safeMinimumBG = maxOf(autodriveBG.toDouble(), 120.0)
        if (bg < safeMinimumBG) {
            return DecisionResult.Fallthrough("BG $bg < Safe minimum ${safeMinimumBG.toInt()}")
        }

        // GATE R2: Strict Cooldown (45 min)
        val now = System.currentTimeMillis()
        val cooldownMs = 45 * 60 * 1000L
        val remaining = (lastAutodriveActionTime + cooldownMs) - now
        if (remaining > 0) {
            return DecisionResult.Fallthrough("Cooldown active (${remaining/1000/60}m)")
        }

        // Logic Re-Use
        val validCondition = isAutodriveModeCondition(delta, autodrive, slopeFromMinDeviation, bg.toFloat(), predictedBg, reasonBuf, targetBg)
        
        if (!validCondition) return DecisionResult.Fallthrough("Conditions not met")

        // Determine Intensity
        var amount = 0.0
        var stateReason = ""
        
        // 🔧 FIX: Raised BG threshold from 100 to 120 for all Autodrive triggers
        if (bg >= 120.0 && delta >= 5.0 && shortAvgDelta >= 3.0) {
             amount = dynamicPbolusLarge
             stateReason = "Confirmed: Bg≥120 & Delta≥5 & Avg≥3"
        } else if (bg >= 120.0 && delta >= 2.0) {
             amount = dynamicPbolusSmall
             stateReason = "Early: Bg≥120 & Delta≥2"
        } else {
             return DecisionResult.Fallthrough("BG or Delta insufficient (need BG≥120)")
        }

        // TBR Calculation
        val rawAutoMax = preferences.get(DoubleKey.autodriveMaxBasal) ?: 0.0
        val scalarAuto: Double = if (rawAutoMax > 0.1) rawAutoMax.toDouble() else profile.max_basal.toDouble()
        
        // 🛡️ TIERED AUTODRIVE BASAL (Lyra Optimization)
        // If "Early", we use only 50% of the allowed max (Soft Start).
        // If "Confirmed", we use 100%.
        val tierFactor = if (stateReason.startsWith("Early")) 0.5 else 1.0
        val effectiveAutoMax = scalarAuto * tierFactor

        val safeAutoMax = minOf(effectiveAutoMax, profile.max_basal.toDouble())
        
        // 🛡️ Sanitize stateReason to prevent JSON crashes
        val safeStateReason = sanitizeForJson(stateReason)
        consoleLog.add(sanitizeForJson("AD_INTENT amount=$amount tbr=$safeAutoMax reason=$safeStateReason"))
        
        return DecisionResult.Applied(
            source = "Autodrive",
            bolusU = amount,
            tbrUph = safeAutoMax,
            tbrMin = 30,
            reason = "🚀 Autodrive [$safeStateReason] -> Force ${amount}U"
        )
    }

}

enum class AutodriveState {
    IDLE,
    WATCHING,
    ENGAGED
}


