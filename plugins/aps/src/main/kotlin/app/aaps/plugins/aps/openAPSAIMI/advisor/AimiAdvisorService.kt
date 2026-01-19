package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt
import app.aaps.plugins.aps.R
import kotlin.math.max
import kotlin.math.min
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import com.google.gson.Gson

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
    private val unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner?
    private val tddCalculator: app.aaps.core.interfaces.stats.TddCalculator?
    private val tirCalculator: app.aaps.core.interfaces.stats.TirCalculator?

    // Constructor injection for dependencies
    constructor(
        profileFunction: app.aaps.core.interfaces.profile.ProfileFunction? = null,
        persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer? = null,
        preferences: app.aaps.core.keys.interfaces.Preferences? = null,
        rh: app.aaps.core.interfaces.resources.ResourceHelper? = null,
        unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner? = null,
        tddCalculator: app.aaps.core.interfaces.stats.TddCalculator? = null,
        tirCalculator: app.aaps.core.interfaces.stats.TirCalculator? = null
    ) {
        this.profileFunction = profileFunction
        this.persistenceLayer = persistenceLayer
        this.preferences = preferences
        this.rh = rh
        this.unifiedReactivityLearner = unifiedReactivityLearner
        this.tddCalculator = tddCalculator
        this.tirCalculator = tirCalculator
    }

    private val rh: app.aaps.core.interfaces.resources.ResourceHelper?

    /**
     * Generate a full advisor report for the specified period.
     */
    fun generateReport(periodDays: Int = 7, history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog> = emptyList()): AdvisorReport {
        val context = collectContext(periodDays)
        val score = computeGlobalScore(context.metrics)
        val severity = classifySeverity(score)
        val recommendations = generateRecommendations(context, history).toMutableList()
        
        // PKPD Analysis - now returns AimiRecommendation
        val pkpdSuggestions = PkpdAdvisor().analysePkpd(context.metrics, context.pkpdPrefs, rh!!)
        
        // Filter PKPD suggestions based on history (48h cooldown)
        pkpdSuggestions.forEach { rec ->
            if (shouldShowRecommendation(rec, history)) {
                recommendations.add(rec)
            }
        }
        
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
     * Gather all necessary data for analysis.
     */
    fun collectContext(periodDays: Int): AdvisorContext {
        if (persistenceLayer == null || profileFunction == null || preferences == null) {
            return getEmptyContext()
        }

        // 1. Calculate Metrics (from Persistence Layer)
        // We'll trust persistenceLayer to give us stats or we calculate from raw data
        val metrics = calculateMetrics(periodDays)

        // 2. Snapshot Profile
        val profile = profileFunction.getProfile()
        val profileSnapshot = if (profile != null) {
            val totalBasalCalc = (0 until 24).sumOf { h -> profile.getBasal((h * 3600).toLong()) }
            
            AimiProfileSnapshot(
                nightBasal = profile.getBasal(0), // 00:00 basal
                icRatio = calculateWeightedAverage(profile.getIcsValues()),
                isf = calculateWeightedAverage(profile.getIsfsMgdlValues()),
                targetBg = calculateWeightedAverage(profile.getSingleTargetsMgdl()),
                dia = profile.dia,
                totalBasal = totalBasalCalc
            )
        } else {
            AimiProfileSnapshot(0.5, 10.0, 40.0, 100.0, 5.0, 12.0) // Fallback
        }

        // 3. Snapshot Preferences
        val prefsSnapshot = AimiPrefsSnapshot(
            maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB),
            lunchFactor = preferences.get(DoubleKey.OApsAIMILunchFactor),
            unifiedReactivityFactor = 1.0, // Default for now
            autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
        )
        
        // 4. Snapshot PKPD Preferences
        val pkpdSnapshot = PkpdPrefsSnapshot(
            pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
            initialDiaH = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH),
            initialPeakMin = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin),
            boundsDiaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
            boundsDiaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
            boundsPeakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            boundsPeakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
            maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin),
            isfFusionMinFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            isfFusionMaxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            isfFusionMaxChangePerTick = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick),
            smbTailThreshold = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
            smbTailDamping = preferences.get(DoubleKey.OApsAIMISmbTailDamping),
            smbExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
            smbLateFatDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)
        )

        return AdvisorContext(metrics, profileSnapshot, prefsSnapshot, pkpdSnapshot)
    }

    private fun calculateWeightedAverage(values: Array<app.aaps.core.interfaces.profile.Profile.ProfileValue>): Double {
        if (values.isEmpty()) return 0.0
        
        var totalWeightedValue = 0.0
        var totalDuration = 0
        
        // Sort by time just in case
        val sorted = values.sortedBy { it.timeAsSeconds }
        
        for (i in sorted.indices) {
            val start = sorted[i].timeAsSeconds
            val end = if (i < sorted.size - 1) sorted[i + 1].timeAsSeconds else 24 * 3600
            val duration = end - start
            
            totalWeightedValue += sorted[i].value * duration
            totalDuration += duration
        }
        
        return if (totalDuration > 0) totalWeightedValue / totalDuration else 0.0
    }

    private fun calculateMetrics(days: Int): AdvisorMetrics {
        // Fallback defaults
        var tir70_180 = 0.65
        var tir70_140 = 0.40
        var timeBelow70 = 0.05
        var timeBelow54 = 0.01
        var timeAbove180 = 0.30
        var timeAbove250 = 0.05
        var meanBg = 160.0
        var tdd = 45.0
        var basalPercent = 0.50
        var todayTir: Double? = null
        var todayTdd: Double? = null

        // 1. Calculate Real TIR (70-180)
        if (tirCalculator != null) {
            try {
                // Main Range (70-180)
                val tirs = tirCalculator.calculate(days.toLong(), 70.0, 180.0)
                val avgTir = tirCalculator.averageTIR(tirs)
                
                if (avgTir != null) {
                    tir70_180 = (avgTir.inRangePct() ?: 0.0) / 100.0
                    timeBelow70 = (avgTir.belowPct() ?: 0.0) / 100.0
                    timeAbove180 = (avgTir.abovePct() ?: 0.0) / 100.0
                }

                // Very Low (<54) - Calculate with low=54
                val tirs54 = tirCalculator.calculate(days.toLong(), 54.0, 180.0)
                val avg54 = tirCalculator.averageTIR(tirs54)
                if (avg54 != null) {
                    timeBelow54 = (avg54.belowPct() ?: 0.0) / 100.0
                }
                
                // Very High (>250) - Calculate with high=250
                val tirs250 = tirCalculator.calculate(days.toLong(), 70.0, 250.0)
                val avg250 = tirCalculator.averageTIR(tirs250)
                if (avg250 != null) {
                    timeAbove250 = (avg250.abovePct() ?: 0.0) / 100.0
                }

                // Tight Range (70-140)
                val tirs140 = tirCalculator.calculate(days.toLong(), 70.0, 140.0)
                val avg140 = tirCalculator.averageTIR(tirs140)
                if (avg140 != null) {
                    tir70_140 = (avg140.inRangePct() ?: 0.0) / 100.0
                }
                
                // Today's TIR
                val dailyTirs = tirCalculator.calculateDaily(70.0, 180.0)
                if (dailyTirs != null && dailyTirs.size() > 0) {
                     // Get the entry with the largest timestamp (latest)
                     // LongSparseArray doesn't ensure order by key?
                     // Usually appended. Let's iterate or assume logic.
                     // Finding max key
                     var maxDate = 0L
                     var todayStat: app.aaps.core.interfaces.stats.TIR? = null
                     for(i in 0 until dailyTirs.size()) {
                         val key = dailyTirs.keyAt(i)
                         if (key > maxDate) {
                             maxDate = key
                             todayStat = dailyTirs.valueAt(i)
                         }
                     }
                     if (todayStat != null) {
                        todayTir = (todayStat.inRangePct() ?: 0.0) / 100.0
                     }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Calculate Real TDD
        if (tddCalculator != null) {
            try {
                val tdds = tddCalculator.calculate(days.toLong(), true)
                val avgTdd = tddCalculator.averageTDD(tdds)
                
                if (avgTdd != null) {
                    tdd = avgTdd.data.totalAmount
                    if (tdd > 0) {
                        basalPercent = avgTdd.data.basalAmount / tdd
                    }
                }
                
                val today = tddCalculator.calculateToday()
                if (today != null) {
                    todayTdd = today.totalAmount
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Calculate Mean BG & GMI
        if (persistenceLayer != null) {
            try {
                // Fetch APS results for the period (approx BG history)
                val now = System.currentTimeMillis()
                val fromTime = now - (days * 24 * 3600 * 1000L)
                val apsResults = persistenceLayer.getApsResults(fromTime, now)
                
                // Extract valid glucose values from JSON using Gson (same approach as DetermineBasalAIMI2)
                val gson = Gson()
                val bgValues = apsResults.mapNotNull { apsResult ->
                    try {
                        // Access glucoseStatusJson via reflection-free approach
                        val jsonField = apsResult.javaClass.getDeclaredField("glucoseStatusJson")
                        jsonField.isAccessible = true
                        val json = jsonField.get(apsResult) as? String
                        
                        if (json != null) {
                            val glucoseStatus = gson.fromJson(json, GlucoseStatusAIMI::class.java)
                            if (glucoseStatus.glucose > 30.0) glucoseStatus.glucose else null
                        } else null
                    } catch (e: Exception) {
                        null // Skip malformed JSON or reflection errors
                    }
                }
                
                if (bgValues.isNotEmpty()) {
                    meanBg = bgValues.average()
                    android.util.Log.d("AIMI_ADVISOR", "✅ Calculated Mean BG: ${meanBg.toInt()} mg/dL from ${bgValues.size} readings")
                } else {
                    android.util.Log.w("AIMI_ADVISOR", "⚠️ No valid BG data found for mean calculation over last $days days. Using fallback $meanBg.")
                }
            } catch (e: Exception) {
                android.util.Log.e("AIMI_ADVISOR", "❌ Failed to calculate Mean BG: ${e.message}")
                e.printStackTrace()
            }
        }

        return AdvisorMetrics(
            periodLabel = "Last $days days",
            tir70_180 = tir70_180,
            tir70_140 = tir70_140,
            timeBelow70 = timeBelow70, 
            timeBelow54 = timeBelow54,
            timeAbove180 = timeAbove180,
            timeAbove250 = timeAbove250,
            meanBg = meanBg,
            gmi = 3.31 + (0.02392 * meanBg), // GMI = 3.31 + 0.02392 * MeanBG (mg/dL)
            tdd = tdd,
            basalPercent = basalPercent,
            hypoEvents = 0, // Need Notification/Treatment analysis
            severeHypoEvents = 0,
            hyperEvents = 0,
            todayTir = todayTir,
            todayTdd = todayTdd
        )
    }

    private fun computeGlobalScore(m: AdvisorMetrics): Double {
        // Simple weighted score
        // TIR (50%), Hypo Avoidance (30%), Stability (20%)
        val tirScore = m.tir70_180 * 10.0
        val hypoScore = (1.0 - (m.timeBelow70 * 5).coerceAtMost(1.0)) * 10.0 // penalized heavily
        val hyperScore = (1.0 - m.timeAbove180) * 10.0
        
        return (tirScore * 0.5) + (hypoScore * 0.3) + (hyperScore * 0.2)
    }

    private fun classifySeverity(score: Double): AdvisorSeverity {
        return when {
            score >= 7.0 -> AdvisorSeverity.GOOD
            score >= 4.0 -> AdvisorSeverity.WARNING
            else -> AdvisorSeverity.CRITICAL
        }
    }

    private fun getAssessmentLabel(score: Double): String {
        return when {
            score >= 8.5 -> rh?.gs(R.string.aimi_advisor_score_label_excellent) ?: "Excellent"
            score >= 7.0 -> rh?.gs(R.string.aimi_advisor_score_label_good) ?: "Good"
            score >= 4.0 -> rh?.gs(R.string.aimi_advisor_score_label_warning) ?: "Warning"
            score >= 2.0 -> rh?.gs(R.string.aimi_advisor_score_label_attention) ?: "Attention"
            else -> rh?.gs(R.string.aimi_advisor_score_label_critical) ?: "Critical"
        }
    }

    private fun getEmptyContext(): AdvisorContext {
        return AdvisorContext(
            metrics = AdvisorMetrics("N/A",0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0,0,0, null, null),
            profile = AimiProfileSnapshot(0.0,0.0,0.0,0.0, 5.0, 12.0),
            prefs = AimiPrefsSnapshot(0.0,0.0,0.0,0.0),
            pkpdPrefs = PkpdPrefsSnapshot(false,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
        )
    }

    private fun formatSummary(m: AdvisorMetrics): String {
        return "TIR: ${(m.tir70_180*100).toInt()}% | Hypos: ${(m.timeBelow70*100).toInt()}% | Mean: ${m.meanBg.toInt()}"
    }

    /**
     * Generate recommendations based on Context (Rules Engine).
     */
    fun generateRecommendations(ctx: AdvisorContext, history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>): List<AimiRecommendation> {
        val recs = mutableListOf<AimiRecommendation>()
        val metrics = ctx.metrics
        val profile = ctx.profile
        val prefs = ctx.prefs


        // 1) CRITICAL: Hypos / Safety Aggression
        // If hypos > 4% and MaxSMB is high -> suggest reduction
        if (metrics.timeBelow70 > 0.04) {
            var action: AdvisorAction? = null
            
            // Rule: Reduce MaxSMB if > 1.5U
            if (prefs.maxSmb > 1.5) {
                val newValue = (prefs.maxSmb * 0.8 * 10.0).roundToInt() / 10.0 // -20%, rounded
                action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMIMaxSMB,
                            keyName = "Max SMB",
                            oldValue = prefs.maxSmb,
                            newValue = newValue,
                            explanation = rh!!.gs(R.string.aimi_adv_rec_hypos_desc, (metrics.timeBelow54 * 100).roundToInt(), metrics.severeHypoEvents) // Re-use desc for explanation? Or specific string.
                        )
                    )
                )
            }

            val rec = AimiRecommendation(
                titleResId = R.string.aimi_adv_rec_hypos_title,
                descriptionResId = R.string.aimi_adv_rec_hypos_desc,
                priority = RecommendationPriority.CRITICAL,
                domain = RecommendationDomain.SAFETY,
                action = action
            )
            
            if (shouldShowRecommendation(rec, history)) {
                recs += rec
            }
        }

        // 2) HIGH: Poor Control (Low TIR but safe) -> Increase Basal
        if (metrics.tir70_180 < 0.70 && metrics.timeBelow70 <= 0.03) {
            
            var action: AdvisorAction? = null

            // Rule: Increase Lunch Factor if seemingly underdosed
            if (prefs.lunchFactor < 1.2) {
                 val newValue = (prefs.lunchFactor + 0.1 * 10.0).roundToInt() / 10.0
                 action = AdvisorAction.UpdatePreference(
                    changes = listOf(
                        AdvisorAction.Prediction(
                            key = DoubleKey.OApsAIMILunchFactor,
                            keyName = "Lunch Factor",
                            oldValue = prefs.lunchFactor,
                            newValue = newValue,
                            explanation = "Augmenter l'agressivité au déjeuner (+0.1)"
                        )
                    )
                 )
            }

            val rec = AimiRecommendation(
                titleResId = R.string.aimi_adv_rec_control_title,
                descriptionResId = R.string.aimi_adv_rec_control_desc,
                priority = RecommendationPriority.HIGH,
                domain = RecommendationDomain.BASAL,
                action = action
            )
             if (shouldShowRecommendation(rec, history)) {
                recs += rec
            }
        }

        // 3) MEDIUM: Hypers dominant
        if (metrics.timeAbove180 > 0.20 && metrics.timeBelow70 <= 0.03) {
            // No automatic action defined yet for general hypers beyond PKPD
             val rec = AimiRecommendation(
                titleResId = R.string.aimi_adv_rec_hypers_title,
                descriptionResId = R.string.aimi_adv_rec_hypers_desc,
                priority = RecommendationPriority.MEDIUM,
                domain = RecommendationDomain.ISF,
                action = null 
            )
            if (shouldShowRecommendation(rec, history)) {
                recs += rec
            }
        }

        // 4) MEDIUM: Basal dominance
        if (metrics.basalPercent > 0.55) {
             val rec = AimiRecommendation(
                titleResId = R.string.aimi_adv_rec_basal_title,
                descriptionResId = R.string.aimi_adv_rec_basal_desc,
                priority = RecommendationPriority.MEDIUM,
                domain = RecommendationDomain.BASAL,
                action = null
            )
            recs += rec // Informational, always show? Or check history?
        }

        // 5) If nothing alarming -> positive message
        if (recs.isEmpty()) {
            recs += AimiRecommendation(
                titleResId = R.string.aimi_adv_rec_profile_ok_title,
                descriptionResId = R.string.aimi_adv_rec_profile_ok_desc,
                priority = RecommendationPriority.LOW,
                domain = RecommendationDomain.PROFILE_QUALITY,
                action = null
            )
        }

        return recs
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt()

    /**
     * Level 1 Analysis: Generates a deterministic text summary of the recommendations.
     */
    fun generatePlainTextAnalysis(context: AdvisorContext, report: AdvisorReport): String {
        val sb = StringBuilder()
        
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
                    // Just print the title of the recommendation
                    val title = try { rh.gs(rec.titleResId) } catch (e: Exception) { "-" }
                    sb.append("- $title\n")
                }
            } else {
                sb.append(rh.gs(R.string.aimi_adv_analysis_all_good))
            }
            
            sb.append("\n" + rh.gs(R.string.aimi_adv_generated_footer, formatTime(report.generatedAt)))

        } else {
             sb.append("Analysis available in app.")
        }

        return sb.toString()
    }
    
    private fun formatTime(time: Long): String {
         return java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
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
                ${report.recommendations.filter { it.domain == RecommendationDomain.PKPD }.joinToString(",") { "\"${try{rh?.gs(it.titleResId)}catch(e:Exception){it.descriptionResId}}\"" }}
              ]
            }
        """.trimIndent()
    }
    
    /**
     * Determines if a recommendation should be shown based on history (48h cooldown).
     */
    private fun shouldShowRecommendation(
        rec: AimiRecommendation,
        history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>
    ): Boolean {
        if (rec.action == null) return true // Informational recs always shown (unless we want to suppress noise)
        
        // If action is UpdatePreference, check if any of the keys were modified recently
        if (rec.action is AdvisorAction.UpdatePreference) {
            val update = rec.action as AdvisorAction.UpdatePreference
            // If ANY key in the proposal was modified in the last 48h, suppress it.
            return update.changes.none { change ->
                 wasRecentlyChanged(history, change.keyName) // We use keyName or we need the actual key string?
                 // AdvisorHistoryRepository stores "key: String". 
                 // change.key is 'Any' (PreferenceKey object). change.keyName is "Display Name".
                 // We need the preference key STRING.
                 // Let's assume prediction stores the technical key string implicitly or we need to extract it.
                 // AdvisorAction.Prediction has (val key: Any).
                 // We need to cast it to PreferenceKey to get .key string.
                 
                 val prefKey = change.key as? app.aaps.core.keys.interfaces.PreferenceKey
                 prefKey?.let { wasRecentlyChanged(history, it.key) } ?: false
            }
        }
        return true
    }

    private fun wasRecentlyChanged(
        history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>,
        keyString: String
    ): Boolean {
        // Check last 48h
        val threshold = System.currentTimeMillis() - (48 * 3600 * 1000L)
        return history.any { 
            it.timestamp > threshold && 
            it.key == keyString 
        }
    }
}

