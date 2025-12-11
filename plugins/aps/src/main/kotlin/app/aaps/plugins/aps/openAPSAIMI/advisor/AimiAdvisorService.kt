package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt
import app.aaps.plugins.aps.R
import kotlin.math.max
import kotlin.math.min
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey

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
        preferences: app.aaps.core.keys.interfaces.Preferences? = null,
        rh: app.aaps.core.interfaces.resources.ResourceHelper? = null
    ) {
        this.profileFunction = profileFunction
        this.persistenceLayer = persistenceLayer
        this.preferences = preferences
        this.rh = rh
    }

    private val rh: app.aaps.core.interfaces.resources.ResourceHelper?

    /**
     * Generate a full advisor report for the specified period.
     */
    fun generateReport(periodDays: Int = 7): AdvisorReport {
        val context = collectContext(periodDays)
        val score = computeGlobalScore(context.metrics)
        val severity = classifySeverity(score)
        val recommendations = generateRecommendations(context)
        
        // PKPD Analysis
        val pkpdSuggestions = PkpdAdvisor().analysePkpd(context.metrics, context.pkpdPrefs, rh!!)
        
        return AdvisorReport(
            generatedAt = System.currentTimeMillis(),
            metrics = context.metrics,
            overallScore = score,
            overallSeverity = severity,
            overallAssessment = getAssessmentLabel(score),
            recommendations = recommendations,
            pkpdSuggestions = pkpdSuggestions,
            summary = formatSummary(context.metrics)
        )
    }

    /**
     * Collect Context from REAL DATA.
     */
    fun collectContext(periodDays: Int = 7): AdvisorContext {
        // 1. Fetch Glucose History
        val end = System.currentTimeMillis()
        val start = end - (periodDays * 24 * 3600 * 1000L)
        
        if (persistenceLayer == null || profileFunction == null || preferences == null) {
            return getEmptyContext(periodDays)
        }

        val history = try {
            persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true)
        } catch (e: Exception) {
            emptyList<app.aaps.core.data.model.GV>()
        }

        // 2. Fetch TDD & Basal% Real Data
        val tddList = try {
            persistenceLayer.getLastTotalDailyDoses(periodDays, false) // false = descending (newest first)
        } catch (e: Exception) {
            emptyList<app.aaps.core.data.model.TDD>()
        }

        var avgTdd = 0.0
        var avgBasalPct = 0.50

        if (tddList.isNotEmpty()) {
            // Filter out zero entries if any, to avoid skewing
            val validTdds = tddList.filter { it.totalAmount > 0.1 }
            if (validTdds.isNotEmpty()) {
                 avgTdd = validTdds.map { it.totalAmount }.average()
                 avgBasalPct = validTdds.map { it.basalAmount / it.totalAmount }.average()
            }
        }
        
        // Fallback to Prefs if TDD is missing (e.g. new user)
        if (avgTdd == 0.0) {
            // Try to guess from profile? No, just keep 0 or use MaxSMB * 20? 
            // Better to show 0/Unknown than fake data.
            // But user might want a stub? Let's stick to 0.0 if no data.
        }

        // Calculate Metrics
        val metrics = calculateMetrics(history, periodDays, avgTdd, avgBasalPct)

        // 3. Fetch Profile
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

        // 4. Fetch Prefs (General)
        val aimiPrefs = AimiPrefsSnapshot(
            maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB).toDouble(),
            lunchFactor = preferences.get(DoubleKey.OApsAIMILunchFactor).toDouble(),
            unifiedReactivityFactor = 1.0,
            autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal).toDouble()
        )

        // 5. Fetch PKPD Prefs
        val pkpdPrefs = PkpdPrefsSnapshot(
            pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
            initialDiaH = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH).toDouble(),
            initialPeakMin = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin).toDouble(),
            boundsDiaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH).toDouble(),
            boundsDiaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH).toDouble(),
            boundsPeakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin).toDouble(),
            boundsPeakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax).toDouble(),
            maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH).toDouble(),
            maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin).toDouble(),
            isfFusionMinFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor).toDouble(),
            isfFusionMaxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor).toDouble(),
            isfFusionMaxChangePerTick = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick).toDouble(),
            smbTailThreshold = preferences.get(DoubleKey.OApsAIMISmbTailThreshold).toDouble(),
            smbTailDamping = preferences.get(DoubleKey.OApsAIMISmbTailDamping).toDouble(),
            smbExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping).toDouble(),
            smbLateFatDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping).toDouble()
        )

        return AdvisorContext(metrics, aimiProfile, aimiPrefs, pkpdPrefs)
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

    private fun getEmptyContext(periodDays: Int): AdvisorContext {
        val metrics = AdvisorMetrics(
            periodLabel = "$periodDays derniers jours",
            tir70_180 = 0.0,
            tir70_140 = 0.0,
            timeBelow70 = 0.0,
            timeBelow54 = 0.0,
            timeAbove180 = 0.0,
            timeAbove250 = 0.0,
            meanBg = 0.0,
            gmi = 0.0,
            tdd = 0.0,
            basalPercent = 0.0,
            hypoEvents = 0,
            severeHypoEvents = 0,
            hyperEvents = 0
        )
        // Return zeros for profile/prefs to indicate no data
        val profile = AimiProfileSnapshot(0.0, 0.0, 0.0, 0.0)
        val prefs = AimiPrefsSnapshot(0.0, 0.0, 1.0, 0.0)
        val pkpdPrefs = PkpdPrefsSnapshot(false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        return AdvisorContext(metrics, profile, prefs, pkpdPrefs)
    }

    private fun calculateMetrics(
        history: List<app.aaps.core.data.model.GV>, 
        days: Int,
        avgTdd: Double, 
        avgBasalPct: Double
    ): AdvisorMetrics {
        if (history.isEmpty()) return getEmptyContext(days).metrics // Fallback

        val total = history.size.toDouble()
        val low70 = history.count { it.value < 70 }.toDouble()
        val low54 = history.count { it.value < 54 }.toDouble()
        val high180 = history.count { it.value > 180 }.toDouble()
        val high250 = history.count { it.value > 250 }.toDouble()
        val inRange = history.count { it.value >= 70 && it.value <= 180 }.toDouble()
        val inRangeTight = history.count { it.value >= 70 && it.value <= 140 }.toDouble()
        
        val meanBg = history.map { it.value }.average()
        val gmi = 3.31 + (0.02392 * meanBg)

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
            tdd = (avgTdd * 10.0).roundToInt() / 10.0, // Round for UI
            basalPercent = avgBasalPct,
            hypoEvents = 0, 
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
        
        // Use ResourceHelper if available, otherwise fallback to English or French default?
        // Ideally we should have resources for these strings.
        // For now, let's look up resources dynamically or allow fallback.
        // Since the user wants phone language, we really need the resources.
        
        if (rh != null) {
            // Introduction based on score
            if (report.overallScore >= 8.5) {
                sb.append(rh.gs(R.string.aimi_adv_analysis_intro_excellent) + "\n\n")
            } else if (report.overallScore >= 5.5) {
                sb.append(rh.gs(R.string.aimi_adv_analysis_intro_good) + "\n\n")
            } else {
                sb.append(rh.gs(R.string.aimi_adv_analysis_intro_poor) + "\n\n")
            }
            
            // Summary of Issues
            if (report.recommendations.isNotEmpty()) {
                sb.append(rh.gs(R.string.aimi_adv_analysis_issues_header) + "\n")
                report.recommendations.forEach { rec ->
                    when (rec.domain) {
                        RecommendationDomain.SAFETY -> sb.append("- ${rh.gs(R.string.aimi_adv_analysis_issue_safety)}\n")
                        RecommendationDomain.ISF, RecommendationDomain.TARGET -> sb.append("- ${rh.gs(R.string.aimi_adv_analysis_issue_hyper)}\n")
                        RecommendationDomain.MODES, RecommendationDomain.SMB -> sb.append("- ${rh.gs(R.string.aimi_adv_analysis_issue_smb)}\n")
                        RecommendationDomain.BASAL -> sb.append("- ${rh.gs(R.string.aimi_adv_analysis_issue_basal)}\n")
                        RecommendationDomain.PROFILE_QUALITY -> sb.append("- ${rh.gs(R.string.aimi_adv_analysis_issue_profile)}\n")
                    }
                }
            } else {
                sb.append(rh.gs(R.string.aimi_adv_analysis_all_good))
            }

            // PKPD Summary
            if (report.pkpdSuggestions.isNotEmpty()) {
                sb.append("\n\nSUGGESTIONS PKPD :\n")
                report.pkpdSuggestions.forEach { 
                    sb.append("- ${it.explanation}\n")
                }
            }
            
            sb.append("\n" + rh.gs(R.string.aimi_adv_analysis_footer))

        } else {
             // Fallback if RH missing (should not happen in real app)
             sb.append("Analysis available in app.")
        }

        return sb.toString()
    }

    /**
     * Payload generator for future AI (LLM).
     */
    fun generatePayloadForAI(report: AdvisorReport, context: AdvisorContext): String {
        return """
            {
              "score": ${report.overallScore},
              "metrics": {
                "tir": ${context.metrics.tir70_180},
                "hypos": ${context.metrics.timeBelow70},
                "hypers": ${context.metrics.timeAbove180},
                "meanBg": ${context.metrics.meanBg},
                "tdd": ${context.metrics.tdd}
              },
              "pkpd": {
                "enabled": ${context.pkpdPrefs.pkpdEnabled},
                "dia": ${context.pkpdPrefs.initialDiaH},
                "peak": ${context.pkpdPrefs.initialPeakMin},
                "isfFusionMax": ${context.pkpdPrefs.isfFusionMaxFactor},
                "smbTailDamping": ${context.pkpdPrefs.smbTailDamping}
              },
              "suggestions": [
                ${report.pkpdSuggestions.joinToString(",") { "\"${it.explanation}\"" }}
              ]
            }
        """.trimIndent()
    }
}

