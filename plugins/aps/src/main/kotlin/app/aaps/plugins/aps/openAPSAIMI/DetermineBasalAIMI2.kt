package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.UE
import app.aaps.core.data.time.T
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
import app.aaps.plugins.aps.openAPSAIMI.model.BasalPlan
import app.aaps.plugins.aps.openAPSAIMI.extensions.asRounded
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.MealAggressionContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
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
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionProfiler
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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


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
    private val uiInteraction: app.aaps.core.interfaces.ui.UiInteraction,
    private val wCycleFacade: WCycleFacade,
    private val wCyclePreferences: WCyclePreferences,
    private val wCycleLearner: WCycleLearner,
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
    @Inject lateinit var glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi
    @Inject lateinit var comparator: AimiSmbComparator
    @Inject lateinit var basalLearner: app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
    @Inject lateinit var reactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.ReactivityLearner
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
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
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
    private var insulinPeakTime = 0.0
    private val nightGrowthResistanceMode = NightGrowthResistanceMode()
    private val ngrTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var zeroBasalAccumulatedMinutes: Int = 0
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal

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
            val dateStr  = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ctx.nowEpochMillis))
            val epochMin = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ctx.nowEpochMillis)
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


    private fun roundBasal(value: Double): Double = value


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

        // 1. Contrôle de la chute rapide
        if (dropPerHour >= maxAllowedDropPerHour && delta < 0 && currentBG < 110f) {
            // stopBasal = true // Désactivé pour éviter les coupures abusives
            isHypoRisk = true
            // factors.add(0.3f) // Désactivé pour ne pas impacter le calcul
            //reasonBuilder.append("BG drop élevé ($dropPerHour mg/dL/h), forte réduction; ")
            reasonBuilder.append(context.getString(R.string.bg_drop_high, dropPerHour))
        }

        // 2. Mode montée très rapide : override de toutes les réductions
        if (delta >= 20f && combinedDelta >= 15f && !honeymoon) {
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

            // 8. BG prédit proche de la cible
            if (predictedBG < targetBG + 10) {
                factors.add(0.5f)
                //reasonBuilder.append("BG prédit ($predictedBG) proche de la cible ($targetBG), réduction x0.5; ")
                reasonBuilder.append(context.getString(R.string.bg_near_target, predictedBG, targetBG))
            }
        }

        // Calcul du bolusFactor : 1.0 si aucune réduction, sinon moyenne des facteurs collectés
        var bolusFactor = if (factors.isNotEmpty()) {
            factors.average().toFloat().toDouble()
        } else {
            1.0
        }

        // 9. Zéro basal prolongé : on force le bolusFactor à 1 et on désactive l'arrêt basale
        if (zeroBasalDurationMinutes >= MAX_ZERO_BASAL_DURATION) {
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
        recentSteps5Minutes: Int,
        currentHR: Float,
        averageHR60: Float,
        pumpAgeDays: Float,
        iob: Double = 0.0 // Ajout du paramètre IOB
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

        // 3. Ajustement en fonction de l'activité physique
        if (recentSteps5Minutes > 200 && currentHR > averageHR60) {
            // Exercice : absorption accélérée, réduction du DIA de 30%
            diaMinutes *= 0.7f
            //reasonBuilder.append("Physical activity detected: reduced by 30%\n")
            reasonBuilder.append(context.getString(R.string.physical_activity_detected))
        } else if (recentSteps5Minutes == 0 && currentHR > averageHR60) {
            // Aucune activité mais HR élevée (stress) : absorption potentiellement plus lente, augmentation du DIA de 30%
            diaMinutes *= 1.3f
            //reasonBuilder.append("High HR without activity (stress): increased by 30%\n")
            reasonBuilder.append(context.getString(R.string.high_hr_no_activity))
        }

        // 4. Ajustement en fonction du niveau absolu de fréquence cardiaque
        if (currentHR > 130f) {
            // HR très élevée : circulation rapide, réduction du DIA de 30%
            diaMinutes *= 0.7f
            //reasonBuilder.append("High HR (>130bpm): reduced by 30%\n")
            reasonBuilder.append(context.getString(R.string.high_hr_over_130))
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
//  stepCount?.let { reason.append(" | Steps=$it") }
        stepCount?.let { reason.append(context.getString(R.string.steps, it)) }
//  heartRate?.let { reason.append(" | HR=$it bpm") }
        heartRate?.let { reason.append(context.getString(R.string.heart_rate, if (it.isNaN()) "--" else "%.0f".format(it))) }
        reason.append("\n")
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
    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        return if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
    }
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double =
        if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)

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

        // 4) Enfin, l’exception meal-rise si elle est vraie
        if (mealModeActive) {
            val safeFloor = max(100.0, targetbg - 5)
            if (currentBg > safeFloor && delta > 0.5 && eventualBg > safeFloor) {
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
            bgNow > hypoGuard && bgNow > 110 && detectMealOnset(delta, predDelta, bgacc.toFloat())

        // 3) Tendance & ajustement
        val bgTrend = calculateBgTrend(getRecentBGs(), StringBuilder())
        var rateAdjustment = adjustRateBasedOnBgTrend(_rate, bgTrend).coerceAtLeast(0.0)

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

        val headerRow = "dateStr, bg, iob, cob, delta, shortAvgDelta, longAvgDelta, tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour, predictedSMB, smbGiven\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$predictedSMB,$smbToGive"


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
            "predictedSMB,maxIob,maxSMB,smbGiven\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive"
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
        } else {
            //reasonBuilder.append("Aucune suppression nécessaire : tir1DAYIR est supérieur ou égal à 85%.")
            reasonBuilder.append(context.getString(R.string.reason_no_deletion_needed))
        }
    }

    private fun applySafetyPrecautions(
        mealData: MealData,
        smbToGiveParam: Float,
        hypoThreshold: Double,
        reason: StringBuilder? = null,
        pkpdRuntime: PkPdRuntime? = null,
        exerciseFlag: Boolean = false,
        suspectedLateFatMeal: Boolean = false
    ): Float {
        var smbToGive = smbToGiveParam
        val mealWeights = computeMealAggressionWeights(mealData, hypoThreshold)

        val (isCrit, critMsg) = isCriticalSafetyCondition(mealData, hypoThreshold,context)
        if (isCrit) {
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
        smbToGive = applySpecificAdjustments(smbToGive)
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
        // pkpdRuntime damping removed to avoid double application (handled in SmbInstructionExecutor)

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
            result = maxIob.toFloat() - iob
        }

        return result
    }
    private fun hasReceivedPbolusMInLastHour(pbolusA: Double): Boolean {
        val epsilon = 0.01
        val oneHourAgo = dateUtil.now() - T.hours(1).msecs()

        val bolusesLastHour = persistenceLayer
            .getBolusesFromTime(oneHourAgo, true)
            .blockingGet()

        return bolusesLastHour.any { Math.abs(it.amount - pbolusA) < epsilon }
    }

    private fun isAutodriveModeCondition(
        delta: Float,
        autodrive: Boolean,
        slopeFromMinDeviation: Double,
        bg: Float,
        predictedBg: Float,
        reason: StringBuilder // ← on utilise CE builder-là
    ): Boolean {
        // ⚙️ Prefs
        val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val autodriveDelta: Float = preferences.get(DoubleKey.OApsAIMIcombinedDelta).toFloat()
        val autodriveMinDeviation: Double = preferences.get(DoubleKey.OApsAIMIAutodriveDeviation)
        val autodriveBG: Int = preferences.get(IntKey.OApsAIMIAutodriveBG)

        // 📈 Deltas récents & delta combiné
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas).toFloat()
        val combinedDelta = (delta + predicted) / 2f

        // 🔍 Tendance BG
        val recentBGs = getRecentBGs()
        var autodriveCondition = true
        if (recentBGs.isNotEmpty()) {
            val bgTrend = calculateBgTrend(recentBGs, reason)
            reason.appendLine(
                "📈 BGTrend=${"%.2f".format(bgTrend)} | Δcomb=${"%.2f".format(combinedDelta)} | predBG=${"%.0f".format(predictedBg)}"
            )
            autodriveCondition = adjustAutodriveCondition(bgTrend, predictedBg, combinedDelta, reason)
        } else {
            //reason.appendLine("⚠️ Aucune BG récente — conditions par défaut conservées")
            reason.appendLine(context.getString(R.string.no_recent_bg))
        }

        // ⛔ Ne pas relancer si pbolus récent
        if (hasReceivedPbolusMInLastHour(pbolusA)) {
            reason.appendLine("⛔ Pbolus ${"%.2f".format(pbolusA)}U < 60 min → autodrive=OFF")
            return false
        }

        // ✅ Décision finale
        val ok =
            autodriveCondition &&
                combinedDelta >= autodriveDelta &&
                autodrive &&
                predictedBg > 140 &&
                slopeFromMinDeviation >= autodriveMinDeviation &&
                bg >= autodriveBG.toFloat()

        reason.appendLine(
            "🚗 Autodrive: ${if (ok) "✅ ON" else "❌ OFF"} | " +
                "cond=$autodriveCondition, Δc≥${"%.2f".format(autodriveDelta)}, " +
                "predBG>140, slope≥${"%.2f".format(autodriveMinDeviation)}, bg≥${autodriveBG}"
        )

        return ok
    }

    private fun adjustAutodriveCondition(
        bgTrend: Float,
        predictedBg: Float,
        combinedDelta: Float,
        reason: StringBuilder
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
        if ((bgTrend >= 0f && combinedDelta >= autodriveDelta) || (predictedBg > 140 && combinedDelta >= autodriveDelta)) {
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
        val pbolusHC2: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus2)
        return highCarbrunTime in 15..24 && lastBolusSMBUnit != pbolusHC2.toFloat() && highCarbTime
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

        // Vérification des conditions critiques avec des noms explicites
        //if (isHypoBlocked(context)) conditions.add("hypoGuard")
        if (isHypoBlocked(context)) conditions.add(ctx.getString(R.string.condition_hypoguard))
        //if (isNosmbHm(context)) conditions.add("nosmbHM")
        if (isNosmbHm(context)) conditions.add(ctx.getString(R.string.condition_nosmbhm))
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
        //if (isNewCalibration(context)) conditions.add("isNewCalibration")
        if (isNewCalibration(context)) conditions.add(ctx.getString(R.string.condition_newcalibration))
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
        if (isPrediction(context)) conditions.add(ctx.getString(R.string.condition_prediction))
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
        val recentBurst = recentSteps5Minutes >= 200 && recentSteps10Minutes >= 500
        val sustainedActivity =
            recentSteps30Minutes >= 800 || recentSteps60Minutes >= 1500 || recentSteps180Minutes >= 2500

        val baselineHr = if (averageBeatsPerMinute10 > 0.0) averageBeatsPerMinute10 else averageBeatsPerMinute
        val elevatedHeartRate = baselineHr > 0 && averageBeatsPerMinute > baselineHr * 1.1
        val shortActivityWithHr = (recentSteps5Minutes >= 200 || recentSteps10Minutes >= 400) && elevatedHeartRate

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
        return interval.coerceIn(1, 10)
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

    private fun isBelowHypoThreshold(
        bgNow: Double,
        predicted: Double,
        eventual: Double,
        hypo: Double,
        delta: Double
    ): Boolean {
        val tol = 5.0
        val floor = hypo - tol
        val strongNow = bgNow <= floor
        val strongFuture = (predicted <= floor && eventual <= floor)
        val fastFall = (delta <= -2.0 && predicted <= hypo)
        return strongNow || strongFuture || fastFall
    }
    // Hystérèse : on ne débloque qu’après avoir été > (seuil+margin) pendant X minutes
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

    private fun applySpecificAdjustments(smbAmount: Float): Float {

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

    private fun adjustFactorsBasedOnBgAndHypo(
        morningFactor: Float,
        afternoonFactor: Float,
        eveningFactor: Float
    ): Triple<Float, Float, Float> {
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        val hypoAdjustment = if (bg < 120 || (iob > 3 * maxSMB)) 0.3f else 0.9f
        // Récupération des deltas récents et calcul du delta prédit
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : combine le delta mesuré et le delta prédit
        val combinedDelta = (delta + predicted) / 2.0f
        // s'assurer que combinedDelta est positif pour le calcul logarithmique
        val safeCombinedDelta = if (combinedDelta <= 0) 0.0001f else combinedDelta
        val deltaAdjustment = ln(safeCombinedDelta.toDouble() + 1).coerceAtLeast(0.0)


        // Interpolation de base pour factorAdjustment selon la glycémie (bg)
        var factorAdjustment = when {
            bg < 110 -> interpolateFactor(bg.toFloat(), 70f, 110f, 0.1f, 0.3f)
            else -> interpolateFactor(bg.toFloat(), 110f, 280f, 0.75f, 2.5f)
        }
        if (honeymoon) factorAdjustment = when {
            bg < 160 -> interpolateFactor(bg.toFloat(), 70f, 160f, 0.2f, 0.4f)
            else -> interpolateFactor(bg.toFloat(), 160f, 250f, 0.4f, 0.65f)
        }
        var bgAdjustment = 1.0f + (deltaAdjustment - 1) * factorAdjustment
        bgAdjustment *= 1.2f

        val dynamicCorrection = when {
            //hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22 -> 0.7f
            combinedDelta > 11f  -> 2.5f   // Très forte montée, on augmente très agressivement
            combinedDelta > 8f  -> 2.0f   // Montée forte
            combinedDelta > 4f  -> 1.5f   // Montée modérée à forte
            combinedDelta > 2f  -> 1.0f   // Montée légère
            combinedDelta in -2f..2f -> 0.8f  // Stable
            combinedDelta < -2f && combinedDelta >= -4f -> 0.7f  // Baisse légère
            combinedDelta < -4f && combinedDelta >= -6f -> 0.5f  // Baisse modérée
            combinedDelta < -6f -> 0.4f   // Baisse forte, on diminue considérablement pour éviter l'hypo
            else -> 1.0f
        }
        // On applique ce facteur sur bgAdjustment pour intégrer l'anticipation
        bgAdjustment *= dynamicCorrection

        // // Interpolation pour scalingFactor basée sur la cible (targetBg)
        // val scalingFactor = interpolateFactor(bg.toFloat(), targetBg, 110f, 09f, 0.5f).coerceAtLeast(0.1f)

        val maxIncreaseFactor = 12.5f
        val maxDecreaseFactor = 0.2f

        val adjustFactor = { factor: Float ->
            val adjustedFactor = factor * bgAdjustment * hypoAdjustment //* scalingFactor
            adjustedFactor.coerceIn(((factor * maxDecreaseFactor).toDouble()), ((factor * maxIncreaseFactor).toDouble()))
        }

        return Triple(
            adjustFactor(morningFactor).takeIf { !it.isNaN() } ?: morningFactor,
            adjustFactor(afternoonFactor).takeIf { !it.isNaN() } ?: afternoonFactor,
            adjustFactor(eveningFactor).takeIf { !it.isNaN() } ?: eveningFactor
        ) as Triple<Float, Float, Float>
    }



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
        rT: RT
    ): PredictionResult {
        val advancedPredictions = AdvancedPredictionEngine.predict(
            currentBG = currentBg,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile
        )

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
        return info
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

        // 2️⃣ Basé sur currentActivity (IOB)
        if (currentActivity > 0.1) {
            val adjustment = currentActivity * 20 + 5
            dynamicPeakTime += adjustment
            //reasonBuilder.append("  • Ajout lié IOB: +$adjustment\n")
            reasonBuilder.append(context.getString(R.string.reason_iob_adjustment, adjustment))
        }

        // 3️⃣ Ratio d'activité
        val ratioFactor = when {
            activityRatio > 1.5 -> 0.5 + (activityRatio - 1.5) * 0.05
            activityRatio < 0.5 -> 1.5 + (0.5 - activityRatio) * 0.05
            else -> 1.0
        }
        dynamicPeakTime *= ratioFactor
//  reasonBuilder.append("  • Ratio activité: ${round(activityRatio,2)} ➝ facteur $ratioFactor\n")
        reasonBuilder.append(context.getString(R.string.reason_activity_ratio, round(activityRatio,2), ratioFactor))

        // 4️⃣ Nombre de pas
        stepCount?.let {
            when {
                it > 1000 -> {
                    val stepAdj = it * 0.015
                    dynamicPeakTime += stepAdj
//              reasonBuilder.append("  • Pas ($it) ➝ +$stepAdj\n")
                    reasonBuilder.append(context.getString(R.string.reason_steps_adjustment, it, stepAdj))
                }
                it < 100 -> {
                    dynamicPeakTime *= 0.9
//              reasonBuilder.append("  • Peu de pas ($it) ➝ x0.9\n")
                    reasonBuilder.append(context.getString(R.string.reason_few_steps, it))
                }
            }
        }

        // 5️⃣ Fréquence cardiaque
        heartRate?.let {
            when {
                it > 110 -> {
                    dynamicPeakTime *= 1.15
//              reasonBuilder.append("  • FC élevée ($it) ➝ x1.15\n")
                    reasonBuilder.append(context.getString(R.string.reason_high_hr, it))
                }
                it < 70 -> {
                    dynamicPeakTime *= 0.65
//              reasonBuilder.append("  • FC basse ($it) ➝ x0.85\n")
                    reasonBuilder.append(context.getString(R.string.reason_low_hr, it))
                }
            }
        }

        // 6️⃣ Corrélation FC + pas
        if (stepCount != null && heartRate != null) {
            if (stepCount > 1000 && heartRate > 110) {
                dynamicPeakTime *= 1.2
//          reasonBuilder.append("  • Activité intense ➝ x1.2\n")
                reasonBuilder.append(context.getString(R.string.reason_high_activity))
            } else if (stepCount < 200 && heartRate < 70) {
                dynamicPeakTime *= 0.75
//          reasonBuilder.append("  • Repos total ➝ x0.75\n")
                reasonBuilder.append(context.getString(R.string.reason_total_rest))
            }
        }

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

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float): Boolean {
        val combinedDelta = (delta + predictedDelta) / 2.0f
        return combinedDelta > 3.0f && acceleration > 1.2f
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

    @SuppressLint("NewApi", "DefaultLocale") fun determine_basal(
        glucose_status: GlucoseStatusAIMI, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean, uiInteraction: UiInteraction
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        wCycleInfoForRun = null
        wCycleReasonLogged = false
        lastProfile = profile
        // ✅ ETAPE 1: Calculer le Profil d'Action de l'IOB
        val iobActionProfile = InsulinActionProfiler.calculate(iob_data_array, profile)

// Stocker les résultats dans des variables locales pour plus de clarté
        val iobTotal = iobActionProfile.iobTotal
        val iobPeakMinutes = iobActionProfile.peakMinutes
        val iobActivityNow = iobActionProfile.activityNow
        val iobActivityIn30Min = iobActionProfile.activityIn30Min

// On met à jour la variable `iob` globale de la classe avec la valeur de notre profiler pour la cohérence
        this.iob = iobTotal.toFloat()

// On ajoute les nouvelles informations au log pour le débogage
        consoleLog.add(
            "PAI: Peak in ${"%.0f".format(iobPeakMinutes)}m | " +
                "Activity Now=${"%.0f".format(iobActivityNow * 100)}%, " +
                "in 30m=${"%.0f".format(iobActivityIn30Min * 100)}%"
        )
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
            return rT.also { it.reason.append("no GS") } // ou ton handling habituel
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
        val reasonAimi = StringBuilder()
        var pkpdRuntime: PkPdRuntime? = null
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
        var DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Calcul initial avec un ajustement dynamique basé sur bg et delta
        DinMaxIob = ((bg / 100.0) * (bg / 55.0) + (combinedDelta / 2.0)).toFloat()

// Sécurisation : imposer une borne minimale et une borne maximale
        DinMaxIob = DinMaxIob.coerceAtLeast(1.0f).coerceAtMost(maxIob.toFloat() * 1.3f)

// Réduction de l'augmentation si on est la nuit (0h-6h)
        if (hourOfDay in 0..5 && bg < 160) {
            DinMaxIob = DinMaxIob.coerceAtMost(maxIob.toFloat())
        }

        this.maxIob = if (autodrive) DinMaxIob.toDouble() else maxIob
        //rT.reason.append(", MaxIob: $maxIob")
        rT.reason.append(context.getString(R.string.reason_max_iob, maxIob))
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        // Calcul initial avec ajustement basé sur la glycémie et le delta
        var DynMaxSmb = ((bg / 200) * (bg / 100) + (combinedDelta / 2)).toFloat()

// ⚠ Sécurisation : bornes min/max pour éviter des valeurs extrêmes
        DynMaxSmb = DynMaxSmb.coerceAtLeast(0.1f).coerceAtMost(maxSMBHB.toFloat() * 2.5f)

// ⚠ Ajustement si delta est négatif (la glycémie baisse) pour éviter un SMB trop fort
        if (combinedDelta < 0) {
            DynMaxSmb *= 0.75f // Réduction de 25% si la glycémie baisse
        }

// ⚠ Réduction nocturne pour éviter une surcorrection pendant le sommeil (0h - 6h)
        //if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
        //    DynMaxSmb *= 0.6f
        //}

// ⚠ Alignement avec `maxSMB` et `profile.peakTime`
        DynMaxSmb = DynMaxSmb.coerceAtMost(maxSMBHB.toFloat() * (tp / 60.0).toFloat())

        //val DynMaxSmb = (bg / 200) * (bg / 100) + (delta / 2)
        val enableUAM = profile.enableUAM

        this.maxSMBHB = if (autodrive && !honeymoon) DynMaxSmb.toDouble() else preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        this.maxSMB = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0 || bg > 180 && honeymoon && mealData.slopeFromMinDeviation >= 1.4) maxSMBHB else maxSMB
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
        this.stable = if (delta > -3 && delta < 3 && shortAvgDelta > -3 && shortAvgDelta < 3 && longAvgDelta > -3 && longAvgDelta < 3 && bg < 180) 1 else 0
        val nightbis = hourOfDay <= 7
        val modesCondition = (!mealTime || mealruntime > 30) && (!lunchTime || lunchruntime > 30) && (!bfastTime || bfastruntime > 30) && (!dinnerTime || dinnerruntime > 30) && !sportTime && (!snackTime || snackrunTime > 30) && (!highCarbTime || highCarbrunTime > 30) && !sleepTime && !lowCarbTime
        val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val reason = StringBuilder()
        val recentBGs = getRecentBGs()
        val bgTrend = calculateBgTrend(recentBGs, reason)
        val autodriveCondition = adjustAutodriveCondition(bgTrend, predictedBg, combinedDelta.toFloat(),reason)
        if (bg > 100 && predictedBg > 140 && !nightbis && !hasReceivedPbolusMInLastHour(pbolusAS) && autodrive && detectMealOnset(delta, predicted.toFloat(), bgAcceleration.toFloat()) && modesCondition) {
            rT.units = pbolusAS
            //rT.reason.append("Autodrive early meal detection/snack: Microbolusing ${pbolusAS}U, CombinedDelta : ${combinedDelta}, Predicted : ${predicted}, Acceleration : ${bgAcceleration}.")
            rT.reason.append(context.getString(R.string.reason_autodrive_early_meal, pbolusAS, combinedDelta, predicted, bgAcceleration.toDouble()))
            return rT
        }
        if (isMealModeCondition()) {
            val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
            rT.units = pbolusM
            //rT.reason.append(" Microbolusing Meal Mode ${pbolusM}U.")
            rT.reason.append(context.getString(R.string.manual_meal_prebolus, pbolusM))
            return rT
        }
        if (!nightbis && isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(), predictedBg, reason) && modesCondition) {
            val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
            rT.units = pbolusA
            //reason.append("→ Microbolusing Autodrive Mode ${pbolusA}U\n")
            reason.append(context.getString(R.string.autodrive_meal_prebolus, pbolusA))
            //reason.append("  • Target BG: $targetBg\n")
            reason.append(context.getString(R.string.target_bg, targetBg))
            //reason.append("  • Slope from min deviation: ${mealData.slopeFromMinDeviation}\n")
            reason.append(context.getString(R.string.slope_from_min_deviation, mealData.slopeFromMinDeviation))
            //reason.append("  • BG acceleration: $bgAcceleration\n")
            reason.append(context.getString(R.string.bg_acceleration, bgAcceleration))
            rT.reason.append(reason.toString()) // une seule fois à la fin
            return rT
            // rT.reason.append("Microbolusing Autodrive Mode ${pbolusA}U. TargetBg : ${targetBg}, CombinedDelta : ${combinedDelta}, Slopemindeviation : ${mealData.slopeFromMinDeviation}, Acceleration : ${bgAcceleration}. ")
            // return rT
        }
        if (isbfastModeCondition()) {
            val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
            rT.units = pbolusbfast
            //rT.reason.append(" Microbolusing 1/2 Breakfast Mode ${pbolusbfast}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_bfast1, pbolusbfast))
            return rT
        }
        if (isbfast2ModeCondition()) {
            val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
            this.maxSMB = pbolusbfast2
            rT.units = pbolusbfast2
            //rT.reason.append(" Microbolusing 2/2 Breakfast Mode ${pbolusbfast2}U. ")
            rT.reason.append(context.getString(R.string.reason_prebolus_bfast2, pbolusbfast2))
            return rT
        }
        if (isLunchModeCondition()) {
            val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
            rT.units = pbolusLunch
            //rT.reason.append(" Microbolusing 1/2 Lunch Mode ${pbolusLunch}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_lunch1, pbolusLunch))
            return rT
        }
        if (isLunch2ModeCondition()) {
            val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
            this.maxSMB = pbolusLunch2
            rT.units = pbolusLunch2
            //rT.reason.append(" Microbolusing 2/2 Lunch Mode ${pbolusLunch2}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_lunch2, pbolusLunch2))
            return rT
        }
        if (isDinnerModeCondition()) {
            val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
            rT.units = pbolusDinner
            //rT.reason.append(" Microbolusing 1/2 Dinner Mode ${pbolusDinner}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_dinner1, pbolusDinner))
            return rT
        }
        if (isDinner2ModeCondition()) {
            val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
            this.maxSMB = pbolusDinner2
            rT.units = pbolusDinner2
            //rT.reason.append(" Microbolusing 2/2 Dinner Mode ${pbolusDinner2}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_dinner2, pbolusDinner2))
            return rT
        }
        if (isHighCarbModeCondition()) {
            val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
            rT.units = pbolusHC
            //rT.reason.append(" Microbolusing High Carb Mode ${pbolusHC}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_highcarb, pbolusHC))
            return rT
        }
        if (isHighCarb2ModeCondition()) {
            val pbolusHC2: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus2)
            rT.units = pbolusHC2
            //rT.reason.append(" Microbolusing High Carb Mode ${pbolusHC}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_highcarb2, pbolusHC2))
            return rT
        }
        if (issnackModeCondition()) {
            val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
            rT.units = pbolussnack
            //rT.reason.append(" Microbolusing snack Mode ${pbolussnack}U.")
            rT.reason.append(context.getString(R.string.reason_prebolus_snack, pbolussnack))
            return rT
        }
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

        // TODO eliminate
        val profile_current_basal = roundBasal(profile.current_basal)
        var basal: Double

        // TODO eliminate
        val systemTime = currentTime
        val iobArray = iob_data_array
        val iob_data = iobArray[0]
        val mealFlags = MealFlags(mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime)

// Heure du dernier bolus : iob_data est bien disponible ici (voir initialisation iob_data plus haut).
// Tu as déjà iob_data.lastBolusTime et windowSinceDoseInt calculés dans ce bloc. :contentReference[oaicite:0]{index=0}
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
        val windowSinceDoseMin = if (iob_data.lastBolusTime > 0) {
            ((systemTime - iob_data.lastBolusTime) / 60000.0).coerceAtLeast(0.0)
        } else 0.0
        windowSinceDoseInt = windowSinceDoseMin.toInt()
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
            mealContext = pkpdMealContext
        )
        if (pkpdRuntimeTemp != null) {
            pkpdRuntime = pkpdRuntimeTemp
        }

        // TODO eliminate
        //bg = glucoseStatus.glucose.toFloat()
        //this.bg = bg.toFloat()
        // TODO eliminate
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
        // this.iob = iob_data.iob.toFloat()
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
            // --- LOG ---
            consoleError.add(
                "ISF fusionné=${"%.1f".format(fusedSensitivity)} dynISF=${"%.1f".format(dynSensitivity)} → appliqué=${"%.1f".format(sens)}"
            )

            // --- 🔥 NOUVEAU : synchroniser l’ISF dans le provider PKPD ---
            try {
                app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfTddProvider.set(fusedSensitivity)
            } catch (e: Exception) {
                consoleError.add("Impossible de mettre à jour IsfTddProvider: ${e.message}")
            }
        }

        consoleError.add("CR:${profile.carb_ratio}")
        //val insulinEffect = calculateInsulinEffect(bg.toFloat(),iob,variableSensitivity,cob,normalBgThreshold,recentSteps180Minutes,averageBeatsPerMinute.toFloat(),averageBeatsPerMinute10.toFloat(),profile.insulinDivisor.toFloat())

        val now = System.currentTimeMillis()
        val timeMillis5 = now - 5 * 60 * 1000 // 5 minutes en millisecondes
        val timeMillis10 = now - 10 * 60 * 1000 // 10 minutes en millisecondes
        val timeMillis15 = now - 15 * 60 * 1000 // 15 minutes en millisecondes
        val timeMillis30 = now - 30 * 60 * 1000 // 30 minutes en millisecondes
        val timeMillis60 = now - 60 * 60 * 1000 // 60 minutes en millisecondes
        val timeMillis180 = now - 180 * 60 * 1000 // 180 minutes en millisecondes

        val allStepsCounts = persistenceLayer.getStepsCountFromTimeToTime(timeMillis180, now)

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
            allStepsCounts.forEach { stepCount ->
                val timestamp = stepCount.timestamp
                if (timestamp >= timeMillis5) {
                    this.recentSteps5Minutes = stepCount.steps5min
                }
                if (timestamp >= timeMillis10) {
                    this.recentSteps10Minutes = stepCount.steps10min
                }
                if (timestamp >= timeMillis15) {
                    this.recentSteps15Minutes = stepCount.steps15min
                }
                if (timestamp >= timeMillis30) {
                    this.recentSteps30Minutes = stepCount.steps30min
                }
                if (timestamp >= timeMillis60) {
                    this.recentSteps60Minutes = stepCount.steps60min
                }
                if (timestamp >= timeMillis180) {
                    this.recentSteps180Minutes = stepCount.steps180min
                }
            }
        } else {
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val heartRates5 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis5, now)
            this.averageBeatsPerMinute = heartRates5.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute = 80.0
        }
        try {
            val heartRates10 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis10, now)
            this.averageBeatsPerMinute10 = heartRates10.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute10 = 80.0
        }
        try {
            val heartRates60 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis60, now)
            this.averageBeatsPerMinute60 = heartRates60.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

            averageBeatsPerMinute60 = 80.0
        }
        try {

            val heartRates180 = persistenceLayer.getHeartRatesFromTimeToTime(timeMillis180, now)
            this.averageBeatsPerMinute180 = heartRates180.map { it.beatsPerMinute.toInt() }.average()

        } catch (e: Exception) {

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
            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5           -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()

                timenow < sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    val reactivity = reactivityLearner.getFactor(LocalTime.now())
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                timenow > sixAMHour                               -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    val reactivity = reactivityLearner.getFactor(LocalTime.now())
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0      -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70              -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5                -> (basalaimi * 0.85).toFloat()
                else                                              -> basalaimi
            }
        }

        this.basalaimi = if (honeymoon && basalaimi > profile_current_basal * 2) (profile_current_basal.toFloat() * 2) else basalaimi

        //this.basalaimi = if (basalaimi < 0.0f) 0.0f else basalaimi
        val deltaAcceleration = glucoseStatus.delta - glucoseStatus.shortAvgDelta
        if (deltaAcceleration > 1.5 && bg > 130) {
            // Si la glycémie accélère (+1.5mg/dL/5min par rapport à la moyenne), on augmente le basal
            val boostFactor = 1.2f // Boost de 20%
            this.basalaimi = (this.basalaimi * boostFactor).coerceAtMost(profile.max_basal.toFloat())
            consoleLog.add("Basal boosté (+20%) pour accélération BG.")
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

// 🔹 On conserve les ajustements existants pour l'activité physique,
// mais ils s'appliquent à la sensibilité déjà modulée par le PAI.
        if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10
            || recentSteps180Minutes > 1500 && bg < 130 && delta < 10
        ) {
            this.variableSensitivity *= 1.3f
        }
        if (recentSteps30Minutes > 500 && recentSteps5Minutes in 1..99 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.2f
        }

// 🔹 Et on conserve la sécurisation finale des bornes
        this.variableSensitivity = this.variableSensitivity.coerceIn(5.0f, 300.0f)

// On met à jour la variable `sens` pour que le reste de la fonction l'utilise
        sens = variableSensitivity.toDouble()
        consoleError.add("Final ISF after PAI & activity: ${"%.1f".format(sens)}")

// 🔹 Ajustement basé sur l'activité physique : correction plus fine des valeurs
        if (recentSteps5Minutes > 100 && recentSteps10Minutes > 200 && bg < 130 && delta < 10
            || recentSteps180Minutes > 1500 && bg < 130 && delta < 10
        ) {

            this.variableSensitivity *= 1.3f // Réduction du facteur d’augmentation
        }

// 🔹 Réduction du boost si l’activité est modérée pour éviter une ISF excessive
        if (recentSteps30Minutes > 500 && recentSteps5Minutes in 1..99 && bg < 130 && delta < 10) {
            this.variableSensitivity *= 1.2f
        }

// 🔹 Sécurisation des bornes minimales et maximales
        this.variableSensitivity = this.variableSensitivity.coerceIn(5.0f, 300.0f)


        sens = variableSensitivity.toDouble()
        val pkpdPredictions = computePkpdPredictions(
            currentBg = bg,
            iobArray = iob_data_array,
            finalSensitivity = sens,
            cobG = mealData.mealCOB,
            profile = profile,
            rT = rT
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
        fun safe(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        //val expectedDelta = calculateExpectedDelta(target_bg, eventualBG, bgi)
        val modelcal = calculateSMBFromModel(rT.reason)
        //val smbProposed = modelcal.toDouble()
        val minBg = minOf(safe(bg), safe(predictedBg.toDouble()), safe(eventualBG))
        val threshold = computeHypoThreshold(minBg, profile.lgsThreshold)

        if (shouldBlockHypoWithHysteresis(
                bg = bg,
                predictedBg = predictedBg.toDouble(),
                eventualBg = eventualBG,
                threshold = threshold,
                deltaMgdlPer5min = delta.toDouble()
            )
        ) {
            //rT.reason.appendLine(
            //    "🛑 Hypo guard+hystérèse: minBG=${convertBG(minBg)} " +
            //        "≤ Th=${convertBG(threshold)} (BG=${convertBG(bg)}, pred=${convertBG(predictedBg.toDouble())}, ev=${convertBG(eventualBG)}) → SMB=0"
            rT.reason.appendLine(context.getString(R.string.reason_hypo_guard, convertBG(minBg), convertBG(threshold), convertBG(bg), convertBG(predictedBg.toDouble()), convertBG(eventualBG))
            )
            this.predictedSMB = 0f
        } else {
            rT.reason.appendLine("💉 SMB (UAM): ${"%.2f".format(modelcal)} U")
            this.predictedSMB = modelcal
        }
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
                maxSmb = maxSMB,
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
                insulinStep = INSULIN_STEP,
                highBgOverrideUsed = highBgOverrideUsed,
                profileCurrentBasal = profile_current_basal,
                cob = cob
            ),
            SmbInstructionExecutor.Hooks(
                refineSmb = { combined, short, long, predicted, profileInput ->
                    neuralnetwork5(combined, short, long, predicted, profileInput)
                },
                adjustFactors = { morning, afternoon, evening ->
                    adjustFactorsBasedOnBgAndHypo(morning, afternoon, evening)
                },
                calculateAdjustedDia = { baseDia, currentHour, steps5, currentHr, avgHr60, pumpAge, iobValue ->
                    // 🔀 Si PKPD est actif → on impose son DIA (en minutes)
                    if (!useLegacyDynamicsdia && pkpdDiaMinutesOverride != null) {
                        pkpdDiaMinutesOverride
                    } else {
                        // 🔙 Sinon on garde toute ta logique dynamique legacy
                        calculateAdjustedDIA(
                            baseDia,
                            currentHour,
                            steps5,
                            currentHr,
                            avgHr60,
                            pumpAge,
                            iobValue
                        )
                    }
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
        val savedReason = rT.reason.toString()
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
        rT.reason.append(savedReason)
        var rate = when {
            snackTime && snackrunTime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 4.0, "AI Force basal because mealTime $snackrunTime.", currenttemp, rT)
            mealTime && mealruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because mealTime $mealruntime.", currenttemp, rT)
            lunchTime && lunchruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because lunchTime $lunchruntime.", currenttemp, rT)
            dinnerTime && dinnerruntime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because dinnerTime $dinnerruntime.", currenttemp, rT)
            highCarbTime && highCarbrunTime in 0..30 && delta < 15 -> calculateRate(basal, profile_current_basal, 10.0, "AI Force basal because highcarb $highCarbrunTime.", currenttemp, rT)
            fastingTime -> calculateRate(profile_current_basal, profile_current_basal, delta.toDouble(), "AI Force basal because fastingTime", currenttemp, rT)
            else -> null
        }

        rate?.let {
            rT.rate = it
            rT.deliverAt = deliverAt
            rT.duration = 30
            return rT
        }

        rT.reason.appendLine( //"🚗 Autodrive: $autodrive | Mode actif: ${isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(), predictedBg, reason)} | " +
            context.getString(R.string.autodrive_status, if (autodrive) "✔" else "✘", if (isAutodriveModeCondition(delta, autodrive, mealData.slopeFromMinDeviation, bg.toFloat(), predictedBg, reason)) "✔" else "✘") +
//"AutodriveCondition: $autodriveCondition"
                context.getString(R.string.autodrive_condition, if (autodriveCondition) "✔" else "✘")
        )

        rT.reason.appendLine(
//    "🔍 BGTrend: ${"%.2f".format(bgTrend)} | ΔCombiné: ${"%.2f".format(combinedDelta)} | " +
            context.getString(R.string.reason_bg_trend, bgTrend, combinedDelta) +
//    "Predicted BG: ${"%.0f".format(predictedBg)} | Accélération: ${"%.2f".format(bgacc)} | " +
                context.getString(R.string.reason_predicted_bg, predictedBg, bgacc) +
//    "Slope Min Dev.: ${"%.2f".format(mealData.slopeFromMinDeviation)}"
                context.getString(R.string.reason_slope_min_dev, mealData.slopeFromMinDeviation)
        )

        rT.reason.appendLine(
            "📊 TIR: <70: ${"%.1f".format(currentTIRLow)}% | 70–180: ${"%.1f".format(currentTIRRange)}% | >180: ${"%.1f".format(currentTIRAbove)}%"
        )
        appendCompactLog(reasonAimi, tp, bg, delta, recentSteps5Minutes, averageBeatsPerMinute)
        rT.reason.append(reasonAimi.toString())
        val csf = sens / profile.carb_ratio
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
        val advancedPredictions = AdvancedPredictionEngine.predict(
            currentBG = bg,
            iobArray = iob_data_array,
            finalSensitivity = sens,
            cobG = mealData.mealCOB,
            profile = profile
        )
        val sanitizedPredictions = advancedPredictions.map { round(min(401.0, max(39.0, it)), 0) }
        val intsPredictions = sanitizedPredictions.map { it.toInt() }
        rT.predBGs = Predictions().apply {
            IOB = intsPredictions
            COB = intsPredictions
            ZT  = intsPredictions
            UAM = intsPredictions
        }
        consoleLog.add("Prédiction avancée avec ISF final de ${"%.1f".format(sens)} (PKPD cache reused)")
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
                //rT.reason.append(", BG is dropping (delta $delta), setting basal to 0. ")
                rT.reason.append(context.getString(R.string.reason_bg_dropping, delta))
                setTempBasal(0.0, 30, profile, rT, currenttemp, overrideSafetyLimits = false) // Basal à 0 pendant 30 minutes
            } else if (currenttemp.duration > 15 && (roundBasal(basal) == roundBasal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                //rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                rT.reason.append(context.getString(R.string.reason_set_temp_basal, round(basal, 2)))
                setTempBasal(basal, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
            }
            comparator.compare(
                aimiResult = finalResult,
                glucoseStatus = glucose_status,
                currentTemp = currenttemp,
                iobData = iob_data_array,
                profileAimi = profile,
                autosens = autosens_data,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )
            return finalResult
        } else {
            var insulinReq = smbToGive.toDouble()
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
                        rT.units = microBolus
                        rT.reason.append(context.getString(R.string.reason_microbolus, microBolus))
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
                minutesSinceLastChange = minutesSinceLastChange
            )
            val helpers = BasalDecisionEngine.Helpers(
                calculateRate = { basalValue, currentBasalValue, multiplier, label ->
                    calculateRate(basalValue, currentBasalValue, multiplier, label, currenttemp, rT)
                },
                calculateBasalRate = { basalValue, currentBasalValue, multiplier ->
                    calculateBasalRate(basalValue, currentBasalValue, multiplier)
                },
                detectMealOnset = { deltaValue, predictedDelta, acceleration ->
                    detectMealOnset(deltaValue, predictedDelta, acceleration)
                },
                round = { value, digits -> round(value, digits) }
            )
            val basalDecision = basalDecisionEngine.decide(basalInput, rT, helpers)
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
                profileAimi = profile,
                autosens = autosens_data,
                mealData = mealData,
                microBolusAllowed = microBolusAllowed,
                currentTime = currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = dynIsfMode
            )

            // --- Update Learners ---
            val currentHour = LocalTime.now().hour
            val anyMealActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime
            val isNight = currentHour >= 22 || currentHour <= 6
            
            basalLearner.process(
                currentBg = bg,
                currentDelta = delta.toDouble(),
                tdd7Days = tdd7Days,
                tdd30Days = tdd7Days, // Placeholder as tdd30Days is not readily available in this scope yet
                isFastingTime = isNight && !anyMealActive
            )

            reactivityLearner.process(
                currentBg = bg,
                isMealActive = anyMealActive,
                time = LocalTime.now()
            )

            return finalResult
        }
    }
}
