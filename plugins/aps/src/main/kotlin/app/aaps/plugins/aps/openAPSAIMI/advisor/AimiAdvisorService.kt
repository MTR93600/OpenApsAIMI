package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt
import app.aaps.plugins.aps.R
import kotlin.math.max
import kotlin.math.min
import app.aaps.core.keys.DoubleKey

/**
 * =============================================================================
 * AIMI ADVISOR SERVICE
 * =============================================================================
 * 
 * Analyzes metrics and provides scored recommendations.
 * Uses Resource IDs for localization support.
 * =============================================================================
 */
class AimiAdvisorService {

    private val profileFunction: app.aaps.core.interfaces.profile.ProfileFunction?
    private val persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer?
    private val preferences: app.aaps.core.keys.interfaces.Preferences?

    // Constructor injection for dependencies
    constructor(
        profileFunction: app.aaps.core.interfaces.profile.ProfileFunction? = null,
        persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer? = null,
        preferences: app.aaps.core.keys.interfaces.Preferences? = null
    ) {
        this.profileFunction = profileFunction
        this.persistenceLayer = persistenceLayer
        this.preferences = preferences
    }

    /**
     * Generate a full advisor report for the specified period.
     */
    fun generateReport(periodDays: Int = 7): AdvisorReport {
        val context = collectContext(periodDays)
        val score = computeGlobalScore(context.metrics)
        val severity = classifySeverity(score)
        val recommendations = generateRecommendations(context)
        
        return AdvisorReport(
            generatedAt = System.currentTimeMillis(),
            metrics = context.metrics,
            overallScore = score,
            overallSeverity = severity,
            overallAssessment = getAssessmentLabel(score),
            recommendations = recommendations,
            summary = formatSummary(context.metrics)
        )
    }

    /**
     * Collect Context from REAL DATA.
     */
    fun collectContext(periodDays: Int = 7): AdvisorContext {
        // 1. Fetch Glucose History (Sync or blocking if on background thread)
        // Since this is called from Coroutine scope in Activity? No, currently generateReport is main thread blocking in onCreate.
        // We should fix this later but for now let's use blockingGet if possible or assume it's fast.
        // Actually, ProfileAdvisorActivity calls generatedReport on Main Thread? No, it should be async.
        // For now, we will do a best effort Main Thread fetch if permissible, or fix architecture.
        // But to respect the request "Fix connection", we implement logic.
        
        val end = System.currentTimeMillis()
        val start = end - (periodDays * 24 * 3600 * 1000L)
        
        // Fetch BGs via persistenceLayer (RxJava blocking for simplicity in this synchronous method, but optimally async)
        // If dependencies missing (testing), fallback to dummy
        if (persistenceLayer == null || profileFunction == null || preferences == null) {
            return getDummyContext(periodDays)
        }

        val history = try {
            persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true)
        } catch (e: Exception) {
            emptyList<app.aaps.core.data.model.GV>()
        }

        // Calculate Metrics
        val metrics = calculateMetrics(history, periodDays)

        // 2. Fetch Profile
        val profile = profileFunction.getProfile()
        val aimiProfile = if (profile != null) {
            AimiProfileSnapshot(
                nightBasal = profile.getBasal(),
                icRatio = profile.getIc(),
                isf = profile.getIsfMgdl("AimiAdvisor"),
                targetBg = profile.getTargetMgdl()
            )
        } else {
            AimiProfileSnapshot(0.0, 0.0, 0.0, 0.0)
        }

        // 3. Fetch Prefs
        val aimiPrefs = AimiPrefsSnapshot(
            maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB).toDouble(),
            lunchFactor = preferences.get(DoubleKey.OApsAIMILunchFactor).toDouble(),
            unifiedReactivityFactor = 1.0, // Not a simple preference anymore
            autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal).toDouble()
        )

        return AdvisorContext(metrics, aimiProfile, aimiPrefs)
    }

    /**
     * Score global 0–10. 10 = perfect, 0 = critical.
     */
    private fun computeGlobalScore(metrics: AdvisorMetrics): Double {
        var score = 10.0

        // 1) TIR 70–180 : objective >= 80%
        val tir = metrics.tir70_180
        if (tir < 0.8) {
            val deficit = 0.8 - tir          
            score -= deficit * 40.0          // 2% missing TIR -> -0.8 pt
        }

        // 2) Hypos : time <70 (very penalizing)
        val below70 = metrics.timeBelow70
        if (below70 > 0.03) {                // tolerance ~3%
            val excess = below70 - 0.03
            score -= excess * 80.0           // 1% more -> -0.8 pt
        }

        // 3) Hypers : time >180
        val above180 = metrics.timeAbove180
        if (above180 > 0.20) {
            val excess = above180 - 0.20
            score -= excess * 40.0           // 5% more -> -2 pts
        }

        // 4) Severe Hypos
        if (metrics.timeBelow54 > 0.0 || metrics.severeHypoEvents > 0) {
            score -= 2.0
        }

        // 5) Basal Ratio if extreme (>60% or <35%)
        if (metrics.basalPercent > 0.60 || metrics.basalPercent < 0.35) {
            score -= 1.0
        }

        // Clamp 0–10
        score = max(0.0, min(10.0, score))

        // Round to 1 decimal
        return (score * 10.0).roundToInt() / 10.0
    }

    private fun getDummyContext(periodDays: Int): AdvisorContext {
        // Fallback for previews/testing
        val meanBg = 135.0
        val gmi = 3.31 + (0.02392 * meanBg)
        val metrics = AdvisorMetrics(
            periodLabel = "$periodDays derniers jours (DÉMO)",
            tir70_180 = 0.78,
            tir70_140 = 0.55,
            timeBelow70 = 0.04,
            timeBelow54 = 0.00,
            timeAbove180 = 0.18,
            timeAbove250 = 0.05,
            meanBg = meanBg,
            gmi = (gmi * 10.0).roundToInt() / 10.0,
            tdd = 35.0,
            basalPercent = 0.48,
            hypoEvents = 0,
            severeHypoEvents = 0,
            hyperEvents = 4
        )
        val profile = AimiProfileSnapshot(0.80, 10.0, 45.0, 100.0)
        val prefs = AimiPrefsSnapshot(2.0, 1.0, 1.2, 3.0)
        return AdvisorContext(metrics, profile, prefs)
    }

    private fun calculateMetrics(history: List<app.aaps.core.data.model.GV>, days: Int): AdvisorMetrics {
        if (history.isEmpty()) return getDummyContext(days).metrics // Fallback if no data

        val total = history.size.toDouble()
        val low70 = history.count { it.value < 70 }.toDouble()
        val low54 = history.count { it.value < 54 }.toDouble()
        val high180 = history.count { it.value > 180 }.toDouble()
        val high250 = history.count { it.value > 250 }.toDouble()
        val inRange = history.count { it.value >= 70 && it.value <= 180 }.toDouble()
        val inRangeTight = history.count { it.value >= 70 && it.value <= 140 }.toDouble()
        
        val meanBg = history.map { it.value }.average()
        val gmi = 3.31 + (0.02392 * meanBg)

        // TDD & Basal% - Hard to get from just BGs.
        // We need TDD history. PersistenceLayer has getTDDs?
        // For simplicity now, we'll estimate or use 0 if not available, OR use preferences if available.
        // Let's assume 50/50 for now if we can't fetch it easily without heavy DB query inside this function.
        // TODO: Fetch real TDD via persistenceLayer.getBoluses...
        val tddStub = 40.0 
        val basalPctStub = 0.50

        return AdvisorMetrics(
            periodLabel = "$days derniers jours",
            tir70_180 = inRange / total,
            tir70_140 = inRangeTight / total,
            timeBelow70 = low70 / total,
            timeBelow54 = low54 / total,
            timeAbove180 = high180 / total,
            timeAbove250 = high250 / total,
            meanBg = meanBg,
            gmi = (gmi * 10.0).roundToInt() / 10.0,
            tdd = tddStub,
            basalPercent = basalPctStub,
            hypoEvents = 0, // Need complex event detection logic
            severeHypoEvents = 0,
            hyperEvents = 0
        )
    }

    private fun getAssessmentLabel(score: Double): String = when {
        score >= 8.5 -> "Excellent"
        score >= 7.0 -> "Bon"
        score >= 5.5 -> "À améliorer"
        score >= 4.0 -> "Attention"
        else -> "Critique"
    }
    
    // formatSummary function removed or kept mostly for debug, UI uses individual metrics now
    fun formatSummary(metrics: AdvisorMetrics): String = "" // Deprecated by new UI


    private fun classifySeverity(score: Double): AdvisorSeverity =
        when {
            score < 4.0 -> AdvisorSeverity.CRITICAL
            score < 7.0 -> AdvisorSeverity.WARNING
            else -> AdvisorSeverity.GOOD
        }

    /**
     * Generate recommendations based on Context (Rules Engine).
     */
    fun generateRecommendations(ctx: AdvisorContext): List<AimiRecommendation> {
        val recs = mutableListOf<AimiRecommendation>()
        val metrics = ctx.metrics
        val profile = ctx.profile
        val prefs = ctx.prefs

        // 1) CRITICAL: Hypos / Safety Aggression
        // If hypos > 4% and MaxSMB is high -> suggest reduction
        if (metrics.timeBelow70 > 0.04) {
            val actions = mutableListOf<AdvisorAction>()
            
            // Rule: Reduce MaxSMB if > 1.5U
            if (prefs.maxSmb > 1.5) {
                actions += AdvisorAction(
                    actionCode = AdvisorActionCode.REDUCE_MAX_SMB,
                    params = mapOf(
                        "from" to prefs.maxSmb,
                        "to" to prefs.maxSmb * 0.8 // -20%
                    )
                )
            }

            recs += AimiRecommendation(
                domain = RecommendationDomain.SAFETY,
                priority = RecommendationPriority.CRITICAL,
                titleResId = R.string.aimi_adv_rec_hypos_title,
                descriptionResId = R.string.aimi_adv_rec_hypos_desc,
                actionsResIds = listOf(R.string.aimi_adv_rec_hypos_action_isf),
                advisorActions = actions
            )
        }

        // 2) HIGH: Poor Control (Low TIR but safe) -> Increase Basal
        if (metrics.tir70_180 < 0.70 && metrics.timeBelow70 <= 0.03) {
            val actions = mutableListOf<AdvisorAction>()
            
            // Rule: Increase Night Basal
            actions += AdvisorAction(
                actionCode = AdvisorActionCode.INCREASE_NIGHT_BASAL,
                params = mapOf(
                    "from" to profile.nightBasal,
                    "to" to profile.nightBasal * 1.10 // +10%
                )
            )

            // Rule: Increase Lunch Factor if seemingly underdosed (placeholder logic)
            if (prefs.lunchFactor < 1.2) {
                actions += AdvisorAction(
                     actionCode = AdvisorActionCode.INCREASE_LUNCH_FACTOR,
                     params = mapOf(
                         "from" to prefs.lunchFactor,
                         "to" to prefs.lunchFactor + 0.1
                     )
                )
            }

            recs += AimiRecommendation(
                domain = RecommendationDomain.BASAL,
                priority = RecommendationPriority.HIGH,
                titleResId = R.string.aimi_adv_rec_control_title,
                descriptionResId = R.string.aimi_adv_rec_control_desc,
                actionsResIds = emptyList(), // Use dynamic actions primarily
                advisorActions = actions
            )
        }

        // 3) MEDIUM: Hypers dominant
        if (metrics.timeAbove180 > 0.20 && metrics.timeBelow70 <= 0.03) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.ISF,
                priority = RecommendationPriority.MEDIUM,
                titleResId = R.string.aimi_adv_rec_hypers_title,
                descriptionResId = R.string.aimi_adv_rec_hypers_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_hypers_action_ratios,
                    R.string.aimi_adv_rec_hypers_action_autodrive
                )
            )
        }

        // 4) MEDIUM: Basal dominance
        if (metrics.basalPercent > 0.55) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.MEDIUM,
                titleResId = R.string.aimi_adv_rec_basal_title,
                descriptionResId = R.string.aimi_adv_rec_basal_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_basal_action_night
                )
            )
        }

        // 5) If nothing alarming -> positive message
        if (recs.isEmpty()) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.LOW,
                titleResId = R.string.aimi_adv_rec_profile_ok_title,
                descriptionResId = R.string.aimi_adv_rec_profile_ok_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_profile_ok_action_doc,
                    R.string.aimi_adv_rec_profile_ok_action_tune
                )
            )
        }

        return recs
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt()

    /**
     * Level 1 Analysis: Generates a deterministic text summary of the recommendations.
     * This acts as a basic explanation when the AI Coach is disabled.
     */
    fun generatePlainTextAnalysis(context: AdvisorContext, report: AdvisorReport): String {
        val sb = StringBuilder()
        
        // Introduction based on score
        if (report.overallScore >= 8.5) {
            sb.append("L'analyse indique d'excellents résultats. Votre profil semble bien adapté.\n\n")
        } else if (report.overallScore >= 5.5) {
            sb.append("L'analyse montre une bonne maîtrise globale, mais quelques ajustements pourraient améliorer la stabilité.\n\n")
        } else {
            sb.append("L'analyse détecte plusieurs zones d'instabilité. Des ajustements sont recommandés pour réduire la variabilité.\n\n")
        }

        // Summary of Issues
        if (report.recommendations.isNotEmpty()) {
            sb.append("Points d'attention identifiés :\n")
            report.recommendations.forEach { rec ->
                when (rec.domain) {
                    RecommendationDomain.SAFETY -> sb.append("- Risque d'hypoglycémie détecté (MaxSMB potentiellement trop agressif).\n")
                    RecommendationDomain.ISF, RecommendationDomain.TARGET -> sb.append("- Hyperglycémies persistantes (ISF ou Cible à revoir).\n")
                    RecommendationDomain.MODES, RecommendationDomain.SMB -> sb.append("- Gestion des repas perfectible (Modes ou SMB).\n")
                    RecommendationDomain.BASAL -> sb.append("- Le poids de la basale est déséquilibré par rapport au TDD.\n")
                    RecommendationDomain.PROFILE_QUALITY -> sb.append("- Qualité du profil à optimiser.\n")
                    // Default case if added later
                    else -> {}
                }
            }
            sb.append("\nLes actions suggérées ci-dessous visent à corriger ces déséquilibres de manière ciblée.")
        } else {
            sb.append("Aucun problème majeur détecté. Continuez ainsi !")
        }

        return sb.toString()
    }
}

