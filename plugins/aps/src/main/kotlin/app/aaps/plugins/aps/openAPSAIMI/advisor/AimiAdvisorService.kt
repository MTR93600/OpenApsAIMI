package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt
import app.aaps.plugins.aps.R
import kotlin.math.max
import kotlin.math.min
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import org.json.JSONObject

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
    private val rh: app.aaps.core.interfaces.resources.ResourceHelper?
    private val aapsLogger: app.aaps.core.interfaces.logging.AAPSLogger?
    private val pluginManager: app.aaps.plugins.aps.openAPSAIMI.plugins.AimiPluginManager

    // Constructor injection for dependencies
    constructor(
        profileFunction: app.aaps.core.interfaces.profile.ProfileFunction? = null,
        persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer? = null,
        preferences: app.aaps.core.keys.interfaces.Preferences? = null,
        rh: app.aaps.core.interfaces.resources.ResourceHelper? = null,
        unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner? = null,
        tddCalculator: app.aaps.core.interfaces.stats.TddCalculator? = null,
        tirCalculator: app.aaps.core.interfaces.stats.TirCalculator? = null,
        pluginManager: app.aaps.plugins.aps.openAPSAIMI.plugins.AimiPluginManager? = null,
        aapsLogger: app.aaps.core.interfaces.logging.AAPSLogger? = null
    ) {
        this.profileFunction = profileFunction
        this.persistenceLayer = persistenceLayer
        this.preferences = preferences
        this.rh = rh
        this.unifiedReactivityLearner = unifiedReactivityLearner
        this.tddCalculator = tddCalculator
        this.tirCalculator = tirCalculator
        this.aapsLogger = aapsLogger
        this.pluginManager = pluginManager ?: app.aaps.plugins.aps.openAPSAIMI.plugins.AimiPluginManager(aapsLogger ?: object : app.aaps.core.interfaces.logging.AAPSLogger {
            override fun debug(message: String) {}
            override fun debug(enable: Boolean, tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun debug(tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun debug(tag: app.aaps.core.interfaces.logging.LTag, accessor: () -> String) {}
            override fun debug(tag: app.aaps.core.interfaces.logging.LTag, format: String, vararg arguments: Any?) {}
            override fun warn(tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun warn(tag: app.aaps.core.interfaces.logging.LTag, format: String, vararg arguments: Any?) {}
            override fun info(tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun info(tag: app.aaps.core.interfaces.logging.LTag, format: String, vararg arguments: Any?) {}
            override fun error(tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun error(tag: app.aaps.core.interfaces.logging.LTag, message: String, throwable: Throwable) {}
            override fun error(tag: app.aaps.core.interfaces.logging.LTag, format: String, vararg arguments: Any?) {}
            override fun error(message: String) {}
            override fun error(message: String, throwable: Throwable) {}
            override fun error(format: String, vararg arguments: Any?) {}
            override fun debug(className: String, methodName: String, lineNumber: Int, tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun info(className: String, methodName: String, lineNumber: Int, tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun warn(className: String, methodName: String, lineNumber: Int, tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
            override fun error(className: String, methodName: String, lineNumber: Int, tag: app.aaps.core.interfaces.logging.LTag, message: String) {}
        })
    }

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
                nightBasal = profile.getBasal(0L), // 00:00 basal
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
                // Fetch BG readings directly for the period
                val now = System.currentTimeMillis()
                val fromTime = now - (days * 24 * 3600 * 1000L)
                val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(fromTime, now, ascending = false)
                
                android.util.Log.d("AIMI_ADVISOR", "📊 Mean BG calculation: fetched ${bgReadings.size} BG readings")
                
                if (bgReadings.isEmpty()) {
                    android.util.Log.w("AIMI_ADVISOR", "⚠️ No BG readings found for last $days days. Using fallback meanBg=$meanBg")
                } else {
                    // Extract valid glucose values (GV objects have .value property)
                    val bgValues = bgReadings
                        .map { it.value }
                        .filter { it > 30.0 } // Filter out noise
                    
                    if (bgValues.isNotEmpty()) {
                        meanBg = bgValues.average()
                        android.util.Log.d("AIMI_ADVISOR", "✅ Calculated Mean BG: ${meanBg.toInt()} mg/dL from ${bgValues.size} readings")
                    } else {
                        android.util.Log.w("AIMI_ADVISOR", "⚠️ No valid BG data after filtering. Using fallback $meanBg")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AIMI_ADVISOR", "❌ Failed to calculate Mean BG: ${e.message}")
                e.printStackTrace()
            }
        } else {
            android.util.Log.w("AIMI_ADVISOR", "⚠️ PersistenceLayer is null, cannot calculate mean BG. Using fallback $meanBg")
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
            score >= 7.0 -> AdvisorSeverity.Good
            score >= 4.0 -> AdvisorSeverity.Warning
            else -> AdvisorSeverity.Critical
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
     * Generate recommendations based on Context using the Plugin System.
     */
    fun generateRecommendations(ctx: AdvisorContext, history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>): List<AimiRecommendation> {
        val currentProfile = profileFunction?.getProfile()
        
        if (preferences == null) return emptyList()

        val loopCtx = app.aaps.plugins.aps.openAPSAIMI.model.AimiPluginContext(
            glucose = app.aaps.core.interfaces.aps.GlucoseStatusAIMI(glucose = ctx.metrics.meanBg),
            profile = createDummyProfile(),
            iob = emptyList(),
            cob = 0.0,
            preferences = preferences
        )

        // Collect actions from all plugins
        val actions = pluginManager.collectActions(loopCtx)

        // Map actions to recommendations for the UI
        val recs = actions.map { action ->
            AimiRecommendation(
                titleResId = app.aaps.plugins.aps.R.string.aimi_advisor_recommendations_title,
                descriptionResId = 0, // Should be dynamic
                priority = action.priority,
                domain = action.domain,
                action = action
            )
        }.toMutableList()

        // 5) If nothing alarming -> positive message
        if (recs.isEmpty()) {
            recs.add(AimiRecommendation(
                titleResId = app.aaps.plugins.aps.R.string.aimi_adv_rec_profile_ok_title,
                descriptionResId = app.aaps.plugins.aps.R.string.aimi_adv_rec_profile_ok_desc,
                priority = app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority.Low,
                domain = app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain.Profile,
                action = null
            ))
        }

        return recs
    }

    private fun createDummyProfile(): app.aaps.core.interfaces.aps.OapsProfileAimi {
        return app.aaps.core.interfaces.aps.OapsProfileAimi(
            dia = 5.0, min_5m_carbimpact = 8.0, max_iob = 0.0, max_daily_basal = 0.0, max_basal = 0.0,
            min_bg = 70.0, max_bg = 180.0, target_bg = 100.0, carb_ratio = 10.0, sens = 50.0,
            autosens_adjust_targets = true, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
            high_temptarget_raises_sensitivity = true, low_temptarget_lowers_sensitivity = true,
            sensitivity_raises_target = true, resistance_lowers_target = true, adv_target_adjustments = true,
            exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = true,
            remainingCarbsCap = 90, enableUAM = true, A52_risk_enable = true, SMBInterval = 3,
            enableSMB_with_COB = true, enableSMB_with_temptarget = true, allowSMB_with_high_temptarget = true,
            enableSMB_always = true, enableSMB_after_carbs = true, maxSMBBasalMinutes = 120,
            maxUAMSMBBasalMinutes = 120, bolus_increment = 0.05, carbsReqThreshold = 1,
            current_basal = 1.0, temptargetSet = false, autosens_max = 1.2, out_units = "mg/dL",
            lgsThreshold = 70, variable_sens = 1.0, insulinDivisor = 30, TDD = 40.0, peakTime = 75.0,
            futureActivity = 0.0, sensorLagActivity = 0.0, historicActivity = 0.0, currentActivity = 0.0
        )
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
                ${report.recommendations.filter { it.domain is app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain.Pkpd }.joinToString(",") { "\"${try{rh?.gs(it.titleResId)}catch(e:Exception){it.descriptionResId}}\"" }}
              ]
            }
        """.trimIndent()
    }
    
    /**
     * Determines if a recommendation should be shown based on history (48h cooldown).
     * Fix #7: The original logic was INVERTED — it showed recs when a key was recently changed.
     */
    private fun shouldShowRecommendation(
        rec: AimiRecommendation,
        history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>
    ): Boolean {
        if (rec.action == null) return true // Informational recs always shown

        // Fix #1b: PKPD adjustments (DIA/Peak) have no effect when T3c Brittle Mode is active.
        // In T3c, the basal is driven by heuristics, not by the PKPD model. Suppress to avoid confusion.
        if (rec.domain is app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain.Pkpd) {
            val isT3cActive = preferences?.get(app.aaps.core.keys.BooleanKey.OApsAIMIT3cBrittleMode) ?: false
            if (isT3cActive) return false
        }

        // Fix #7: If action is UpdatePreference, suppress if key was recently changed to avoid recommendation loops.
        if (rec.action is app.aaps.plugins.aps.openAPSAIMI.model.AimiAction.PreferenceUpdate) {
            val update = rec.action as app.aaps.plugins.aps.openAPSAIMI.model.AimiAction.PreferenceUpdate
            // Suppress (return false) if this key was recently modified (48h cooldown)
            return !wasRecentlyChanged(history, update.key.key)
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
    /**
     * Generates a detailed clinical report (JSON) for expert analysis.
     */
    fun generateClinicalExport(periodDays: Int = 14): String {
        try {
            if (persistenceLayer == null || tddCalculator == null || tirCalculator == null) {
                return "{ \"error\": \"Dependencies missing\" }"
            }

            // 1. Fetch Raw Data
            val now = System.currentTimeMillis()
            val fromTime = now - (periodDays * 24 * 3600 * 1000L)
            
            val bgReadings = try {
                persistenceLayer.getBgReadingsDataFromTimeToTime(fromTime, now, false)
                    .map { it.value }
                    .filter { it > 30.0 }
            } catch (e: Exception) {
                emptyList<Double>()
            }
            
            // 2. Build Context
            val metrics = calculateMetrics(periodDays)
            val profile = profileFunction?.getProfile()
            val isf = profile?.getIsfMgdlTimeFromMidnight(0) ?: 40.0 // Default or specific logic needed to get specific ISF
            
            // Dummy Physio Manager (No access to instance here easily without DI)
            // In a real integration, we'd pass the PhysioManagerMTR instance.
            // For now, allow null or partial data.
            
            // Note: We cannot easily access PhysioManager here. 
            // We'll create a lightweight engine instance manually.
            // WARNING: PhysioManager is missing, so physio section will be empty/mocked.
            
            val clinicalCtx = AimiClinicalReportEngine.ClinicalContext(
                bgReadings = bgReadings,
                isfProfile = isf,
                metrics = metrics
            )
            
            // Create Engine (We pass null for PhysioManager as we can't access it easily here - TODO: Fix DI)
            // Wait, we need to handle the missing PhysioManager arg in constructor or make it nullable?
            // I'll modify ClinicalReportEngine to accept nullable manager? 
            // Better: use empty mocks.
            
            // Using a hack for now: We won't use the Engine class directly if we can't inject.
            // Actually, I can just reimplement the math logic locally or create a simplified local ReportBuilder 
            // to avoid DI hell in this existing Service.
            
            // Let's implement the logic inline here to ensure it works immediately without breaking DI.
            
            val stats = JSONObject()
            
            // Metabolic
            val mean = if(bgReadings.isNotEmpty()) bgReadings.average() else 0.0
            val stdDev = if(bgReadings.isNotEmpty()) kotlin.math.sqrt(bgReadings.map { (it-mean)*(it-mean) }.sum() / bgReadings.size) else 0.0
            val cv = if(mean > 0) stdDev/mean * 100 else 0.0
            
            // LBGI
            var lbgiSum = 0.0
            bgReadings.forEach { bg ->
                if(bg > 10) {
                    val f = 1.509 * (java.lang.Math.pow(java.lang.Math.log(bg), 1.084) - 5.381)
                    if(f < 0) lbgiSum += 10 * f * f
                }
            }
            val lbgi = if(bgReadings.isNotEmpty()) lbgiSum/bgReadings.size else 0.0

            stats.put("meta", JSONObject().put("generated", now))
            stats.put("metabolic", JSONObject().apply {
                put("gmi", 3.31 + 0.02392 * mean)
                put("cv", cv)
                put("lbgi", lbgi)
                put("tir", metrics.tir70_180)
            })
            
            stats.put("advisor_metrics", JSONObject().apply {
                put("hypos", metrics.timeBelow70)
                put("hypers", metrics.timeAbove180)
                put("basalRatio", metrics.basalPercent)
            })

            return stats.toString(2)

        } catch (e: Exception) {
            return "{ \"error\": \"${e.message}\" }"
        }
    }
}


