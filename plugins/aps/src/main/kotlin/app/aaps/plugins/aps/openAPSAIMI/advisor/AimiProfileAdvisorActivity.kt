package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.lifecycleScope
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY
 * =============================================================================
 * Displays advisor recommendations using localized resources.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : TranslatedDaggerAppCompatActivity() {
    
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: app.aaps.core.interfaces.profile.ProfileFunction
    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer
    @Inject lateinit var preferences: app.aaps.core.keys.interfaces.Preferences
    @Inject lateinit var unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
    @Inject lateinit var tddCalculator: app.aaps.core.interfaces.stats.TddCalculator
    @Inject lateinit var tirCalculator: app.aaps.core.interfaces.stats.TirCalculator
    
    // NOT injected - created manually to avoid Dagger issues
    private lateinit var advisorService: AimiAdvisorService
    private lateinit var historyRepo: app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository

    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Pass dependencies to service
        advisorService = AimiAdvisorService(
            profileFunction = profileFunction, 
            persistenceLayer = persistenceLayer, 
            preferences = preferences, 
            rh = rh, 
            unifiedReactivityLearner = unifiedReactivityLearner,
            tddCalculator = tddCalculator,
            tirCalculator = tirCalculator
        )
        historyRepo = app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository(this)
        title = rh.gs(R.string.aimi_advisor_title)
        
        // Dark Navy Background
        val bgColor = Color.parseColor("#10141C") 
        val cardColor = Color.parseColor("#1E293B")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        
        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
            setBackgroundColor(bgColor)
        }
        setContentView(scrollView)

        // Loading Indicator
        val loadingText = TextView(this).apply {
            text = rh.gs(R.string.aimi_adv_loading)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        rootLayout.addView(loadingText)
        
        // CRITICAL FIX: Load data on IO thread to prevent crash
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val history = historyRepo.getRecentActions(7)
                val report = advisorService.generateReport(periodDays = 7, history = history)
                val context = advisorService.collectContext(7)

                withContext(Dispatchers.Main) {
                    rootLayout.removeView(loadingText)


                    // 1. Header (Title + Score Pill)
                    rootLayout.addView(createDashboardHeader(report))
            
                    // 2. Metrics Grid (2x2)
                    rootLayout.addView(createMetricsGrid(report.metrics, cardColor))
                    
                    // 3. Section: Observations & Recommendations
                    val standardRecs = report.recommendations.filter { it.domain != RecommendationDomain.PKPD }
                    val pkpdRecs = report.recommendations.filter { it.domain == RecommendationDomain.PKPD }

                    if (standardRecs.isNotEmpty()) {
                        rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_obs)))
                        standardRecs.forEach { rec ->
                            rootLayout.addView(createObservationCard(rec, report.metrics, cardColor))
                        }
                    }

                    // 3b. PKPD TUNING (Unified)
                    if (pkpdRecs.isNotEmpty()) {
                        rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_pkpd)))
                        pkpdRecs.forEach { rec ->
                            rootLayout.addView(createObservationCard(rec, report.metrics, cardColor))
                        }
                    }

                    // 4. Section: COGNITIVE BRIDGE (BRAIN)
                    rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_brain)))
                    rootLayout.addView(createCognitiveCard(context.prefs.unifiedReactivityFactor, cardColor))

                    // 5. Section: AI Coach (ChatGPT/Gemini)
                    rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_coach)))
                    rootLayout.addView(createCoachCard(context, report, cardColor))
            
                    // Footer
                    rootLayout.addView(createFooter(report))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingText.text = "${rh.gs(R.string.aimi_adv_error_prefix)}${e.localizedMessage}"
                    loadingText.setTextColor(Color.parseColor("#F87171")) // Red
                }
                e.printStackTrace()
            }
        }
    }

    private fun createDashboardHeader(report: AdvisorReport): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 48)
            
            val infoLayout = LinearLayout(this@AimiProfileAdvisorActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            infoLayout.addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(R.string.aimi_adv_report_weekly)
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            
            infoLayout.addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = report.metrics.periodLabel
                textSize = 14f
                setTextColor(Color.parseColor("#94A3B8")) // Slate 400
                setPadding(0, 4, 0, 0)
            })
            
            addView(infoLayout)
            
            // Score Pill
            val pill = CardView(this@AimiProfileAdvisorActivity).apply {
                radius = 50f
                setCardBackgroundColor(Color.parseColor("#0F392B")) // Dark Green bg
                cardElevation = 0f
            }
            
            val scoreText = TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(R.string.aimi_adv_score_label, report.overallScore)
                setTextColor(Color.parseColor("#4ADE80")) // Bright Green
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
                setPadding(32, 12, 32, 12)
            }
            pill.addView(scoreText)
            addView(pill)

            // Settings Button (Gear)
            val settingsBtn = TextView(this@AimiProfileAdvisorActivity).apply {
                text = "⚙️"
                textSize = 22f
                setPadding(24, 0, 0, 0)
                setOnClickListener {
                    showModelSelectorDialog()
                }
            }
            addView(settingsBtn)
        }
    }

    private fun showModelSelectorDialog() {
        val current = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        val idx = if (current == "GEMINI") 1 else 0
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_advisor_model_title)) // "Select Model"
            .setSingleChoiceItems(arrayOf("ChatGPT", "Gemini"), idx) { dialog, which ->
                val newValue = if (which == 1) "GEMINI" else "OPENAI"
                preferences.put(app.aaps.core.keys.StringKey.AimiAdvisorProvider, newValue)
                dialog.dismiss()
                recreate() // Reload activity to apply change
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }


    private fun createMetricsGrid(metrics: AdvisorMetrics, cardColor: Int): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        // Row 1
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 0, 0, 24)
        }
        row1.addView(createMetricCard("TIR (70-180)", "${(metrics.tir70_180 * 100).roundToInt()}%", Color.parseColor("#4ADE80"), cardColor), paramHalf())
        row1.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 0) })
        row1.addView(createMetricCard("TDD MOYEN", "${metrics.tdd.roundToInt()} U", Color.parseColor("#60A5FA"), cardColor), paramHalf())
        
        // Row 2
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        row2.addView(createMetricCard("GMI", "${metrics.gmi}%", Color.parseColor("#FACC15"), cardColor), paramHalf())
        row2.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(24, 0) })
        row2.addView(createMetricCard("HYPO < 54", "${(metrics.timeBelow54 * 100).roundToInt()}%", Color.parseColor("#F87171"), cardColor), paramHalf())

        grid.addView(row1)
        grid.addView(row2)

        // Row 3 (Today)
        if (metrics.todayTir != null || metrics.todayTdd != null) {
            val row3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, 24, 0, 0)
            }
            
            val tirVal = metrics.todayTir?.let { "${(it * 100).roundToInt()}%" } ?: "-"
            // Use slightly different color to distinguish? Or same green/blue scheme.
            row3.addView(createMetricCard("AUJ. TIR", tirVal, Color.parseColor("#4ADE80"), cardColor), paramHalf())
            
            row3.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(16, 0) })
            
            val tddVal = metrics.todayTdd?.let { "%.1f U".format(it) } ?: "-"
            row3.addView(createMetricCard("AUJ. TDD", tddVal, Color.parseColor("#60A5FA"), cardColor), paramHalf())
            
            grid.addView(row3)
        }

        return grid
    }

    private fun createMetricCard(label: String, value: String, valueColor: Int, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 24f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 48, 16, 48)
        }
        
        content.addView(TextView(this).apply {
            text = value
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(valueColor)
        })
        
        content.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8")) // Slate 400
            isAllCaps = true
            setPadding(0, 8, 0, 0)
        })
        
        card.addView(content)
        return card
    }

    private fun paramHalf(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B")) // Slate 500
            setPadding(4, 0, 0, 24)
            isAllCaps = true
        }
    }
    
    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }
    }
    
    private fun createObservationCard(rec: AimiRecommendation, metrics: AdvisorMetrics, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
        }
        
        // Icon Circle
        val iconBg = CardView(this).apply {
            radius = 50f
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#334155")) // Slate 700ish
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
        }
        val iconText = TextView(this).apply {
            text = getPriorityEmoji(rec.priority)
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconBg.addView(iconText)
        row.addView(iconBg)
        
        // Text Content
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
        }
        
        textLayout.addView(TextView(this).apply {
            text = rh.gs(rec.titleResId)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        
        var desc = when(rec.descriptionResId) {
             R.string.aimi_adv_rec_hypos_desc -> rh.gs(rec.descriptionResId, (metrics.timeBelow54 * 100).roundToInt(), metrics.severeHypoEvents)
             R.string.aimi_adv_rec_control_desc -> rh.gs(rec.descriptionResId, (metrics.tir70_180 * 100).roundToInt())
             R.string.aimi_adv_rec_hypers_desc -> rh.gs(rec.descriptionResId, (metrics.timeAbove180 * 100).roundToInt())
             R.string.aimi_adv_rec_basal_desc -> rh.gs(rec.descriptionResId, (metrics.basalPercent * 100).roundToInt())
             else -> {
                  if (rec.descriptionArgs.isNotEmpty()) {
                      try {
                          rh.gs(rec.descriptionResId, *rec.descriptionArgs.toTypedArray())
                      } catch(e: Exception) {
                          rh.gs(rec.descriptionResId) + " " + rec.descriptionArgs.joinToString(" ")
                      }
                  } else {
                      rh.gs(rec.descriptionResId)
                  }
              }
        }
        
        textLayout.addView(TextView(this).apply {
            text = desc
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8")) // Slate 400
            setLineSpacing(4f, 1.1f)
            setPadding(0, 4, 0, 0)
        })
        
        // Add dynamic actions overview if present
        if (rec.action != null && rec.action is AdvisorAction.UpdatePreference) {
            val actionBtn = TextView(this).apply {
                text = rh.gs(R.string.aimi_adv_apply_btn)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8")) // Sky Blue
                gravity = Gravity.END
                setPadding(0, 16, 0, 0)
                setOnClickListener {
                    showApplyActionDialog(rec.action as AdvisorAction.UpdatePreference)
                }
            }
            textLayout.addView(actionBtn)
        }
        
        row.addView(textLayout)
        card.addView(row)
        return card
    }

    private fun showApplyActionDialog(action: AdvisorAction.UpdatePreference) {
        val sb = StringBuilder()
        sb.append(rh.gs(R.string.aimi_adv_apply_dialog_prefix))
        
        action.changes.forEach { change ->
             sb.append("• ${change.keyName}: ${change.oldValue} ➔ ${change.newValue}\n")
             sb.append("  ${change.explanation}\n\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_adv_apply_dialog_title))
            .setMessage(sb.toString())
            .setPositiveButton(rh.gs(R.string.aimi_adv_apply_dialog_confirm)) { _, _ ->
                applyAction(action)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAction(action: AdvisorAction.UpdatePreference) {
        try {
            var appliedCount = 0
            
            action.changes.forEach { change ->
                var applied = false
                if (change.newValue is Double && change.key is DoublePreferenceKey) {
                     val key = change.key as DoublePreferenceKey
                     preferences.put(key, change.newValue as Double)
                     logAction(change)
                     applied = true
                } else if (change.newValue is Int && change.key is IntPreferenceKey) {
                     val key = change.key as IntPreferenceKey
                     preferences.put(key, change.newValue as Int)
                     logAction(change)
                     applied = true
                } else if (change.newValue is Boolean && change.key is BooleanPreferenceKey) {
                     val key = change.key as BooleanPreferenceKey
                     preferences.put(key, change.newValue as Boolean)
                     logAction(change)
                     applied = true
                }
                
                if (applied) appliedCount++
            }

            if (appliedCount > 0) {
                 android.widget.Toast.makeText(this, rh.gs(R.string.aimi_adv_success_msg, appliedCount), android.widget.Toast.LENGTH_SHORT).show()
                 recreate()
            } else {
                 android.widget.Toast.makeText(this, rh.gs(R.string.aimi_adv_no_change_msg), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "${rh.gs(R.string.aimi_adv_error_prefix)}${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun logAction(change: AdvisorAction.Prediction) {
        // Extract key string from Key object if possible
        val keyStr = (change.key as? app.aaps.core.keys.interfaces.PreferenceKey)?.key ?: change.keyName

         historyRepo.logAction(
                app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                keyStr, 
                change.explanation,
                change.oldValue.toString(),
                change.newValue.toString()
            )
    }

    private fun createCognitiveCard(factor: Double, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 24, 24, 24)
        }
        
        // Brain Icon
        val iconBg = CardView(this).apply {
            radius = 50f
            cardElevation = 0f
            setCardBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
        }
        val iconText = TextView(this).apply {
            text = "🧠"
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconBg.addView(iconText)
        row.addView(iconBg)
        
        // Text Content
        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 0, 0)
        }
        
        // Determine State
        val stateText: String
        val stateColor: Int
        val explanation: String
        
        when {
            factor < 0.95 -> {
                stateText = "PROTECTEUR (x${"%.2f".format(factor)})"
                stateColor = Color.parseColor("#F87171") // Red/Orange - Reducing aggression
                explanation = "Le système a détecté une instabilité/hypo récente et a réduit l'agressivité globale."
            }
            factor > 1.05 -> {
                stateText = "OFFENSIF (x${"%.2f".format(factor)})"
                stateColor = Color.parseColor("#EF4444") // Red - Increasing aggression
                explanation = "Le système combat une hyperglycémie persistante ou une résistance détectée."
            }
            else -> {
                stateText = "NEUTRE (x${"%.2f".format(factor)})"
                stateColor = Color.parseColor("#4ADE80") // Green
                explanation = "Le système fonctionne avec ses paramètres de base. Aucune anomalie détectée."
            }
        }

        textLayout.addView(TextView(this).apply {
            text = stateText
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(stateColor)
        })
        
        textLayout.addView(TextView(this).apply {
            text = explanation
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8")) // Slate 400
            setLineSpacing(4f, 1.1f)
            setPadding(0, 4, 0, 0)
        })
        
        row.addView(textLayout)
        card.addView(row)
        return card
    }

    private fun createCoachCard(context: AdvisorContext, report: AdvisorReport, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 48)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Header with sparkles
        val title = TextView(this).apply {
            text = "✨ ${rh.gs(R.string.aimi_coach_title)}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#C084FC")) // Purple
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        val contentText = TextView(this).apply {
            text = rh.gs(R.string.aimi_coach_loading)
            textSize = 14f
            setTextColor(Color.parseColor("#CBD5E1")) // Slate 300
            setLineSpacing(6f, 1.2f)
        }
        layout.addView(contentText)

        card.addView(layout)

        // Fetch keys using definitions from StringKey
        val providerStr = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        val openAiKey = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorOpenAIKey)
        val geminiKey = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorGeminiKey)

        val provider = if (providerStr == "GEMINI") AiCoachingService.Provider.GEMINI else AiCoachingService.Provider.OPENAI
        val activeKey = if (provider == AiCoachingService.Provider.GEMINI) geminiKey else openAiKey
        
        if (activeKey.isBlank()) {
            val basicAnalysis = advisorService.generatePlainTextAnalysis(context, report)
            val placeholder = rh.gs(R.string.aimi_coach_placeholder) + " (${provider.name})"
            contentText.text = "$basicAnalysis\n\n⚙️ $placeholder"
        } else {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val history = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        historyRepo.getRecentActions(7)
                    }
                    val advice = AiCoachingService().fetchAdvice(this@AimiProfileAdvisorActivity, context, report, activeKey, provider, history)
                    contentText.text = advice
                } catch (e: Exception) {
                    contentText.text = rh.gs(R.string.aimi_coach_error) + "\n" + e.localizedMessage
                }
            }
        }
        return card
    }

    private fun createFooter(report: AdvisorReport): TextView {
        val time = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(report.generatedAt))
        
        return TextView(this).apply {
            text = "Généré le $time • OpenAPS AIMI"
            textSize = 12f
            setTextColor(Color.parseColor("#475569")) // Slate 600
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 32)
        }
    }
    
    private fun getScoreColor(severity: AdvisorSeverity): Int = when (severity) {
        AdvisorSeverity.GOOD -> Color.parseColor("#4ADE80")  // Green
        AdvisorSeverity.WARNING -> Color.parseColor("#FACC15")  // Warning
        AdvisorSeverity.CRITICAL -> Color.parseColor("#F87171") // Red
    }
    
    private fun getPriorityEmoji(priority: RecommendationPriority): String = when (priority) {
        RecommendationPriority.CRITICAL -> "⚠️"
        RecommendationPriority.HIGH -> "📈"
        RecommendationPriority.MEDIUM -> "ℹ️"
        RecommendationPriority.LOW -> "✅"
    }
    
    // Extension for dp to px
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
