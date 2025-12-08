package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlinx.coroutines.GlobalScope

/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY
 * =============================================================================
 * Displays advisor recommendations using localized resources.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : TranslatedDaggerAppCompatActivity() {
    
    @Inject lateinit var rh: ResourceHelper
    
    // NOT injected - created manually to avoid Dagger issues
    private lateinit var advisorService: AimiAdvisorService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        advisorService = AimiAdvisorService()
        
        title = rh.gs(R.string.aimi_advisor_title)
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        val scrollView = ScrollView(this).apply {
            addView(rootLayout)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        setContentView(scrollView)
        
        val report = advisorService.generateReport(periodDays = 7)
        
        // Header with score
        rootLayout.addView(createScoreHeader(report))
        
        // Summary section
        rootLayout.addView(createSummaryCard(report))

        // AI Coach section
        val context = advisorService.collectContext(7) // TODO: refactor to collect once
        rootLayout.addView(createCoachCard(context, report))
        
        // Recommendations
        rootLayout.addView(createSectionTitle(rh.gs(R.string.aimi_advisor_recommendations_title)))
        
        report.recommendations.forEach { rec ->
            rootLayout.addView(createRecommendationCard(rec, report.metrics))
        }
        
        // Footer
        rootLayout.addView(createFooter(report))
    }
    
    private fun createScoreHeader(report: AdvisorReport): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
            
            // Score circle
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = "${"%.1f".format(report.overallScore)}/10"
                textSize = 48f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getScoreColor(report.overallSeverity))
                gravity = Gravity.CENTER
            })
            
            // Assessment label
            val labelRes = when (report.overallSeverity) {
                 AdvisorSeverity.GOOD -> if (report.overallScore >= 8.5) R.string.aimi_advisor_score_label_excellent else R.string.aimi_advisor_score_label_good
                 AdvisorSeverity.WARNING -> if (report.overallScore >= 5.5) R.string.aimi_advisor_score_label_warning else R.string.aimi_advisor_score_label_attention
                 AdvisorSeverity.CRITICAL -> R.string.aimi_advisor_score_label_critical
            }

            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(labelRes)
                textSize = 20f
                setTextColor(getScoreColor(report.overallSeverity))
                gravity = Gravity.CENTER
            })
        }
    }
    
    private fun createSummaryCard(report: AdvisorReport): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(Color.parseColor("#2D2D2D"))
            cardElevation = 8f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }
        
        val metrics = report.metrics
        val summaryText = buildString {
            append(rh.gs(R.string.aimi_advisor_period_label, metrics.periodLabel)).append("\n\n")
            append(rh.gs(R.string.aimi_advisor_metric_tir_70_180, (metrics.tir70_180 * 100).roundToInt())).append("\n")
            append(rh.gs(R.string.aimi_advisor_metric_tir_70_140, (metrics.tir70_140 * 100).roundToInt())).append("\n")
            append(rh.gs(R.string.aimi_advisor_metric_time_below_70, (metrics.timeBelow70 * 100).roundToInt())).append("\n")
            append(rh.gs(R.string.aimi_advisor_metric_time_above_180, (metrics.timeAbove180 * 100).roundToInt())).append("\n")
            append(rh.gs(R.string.aimi_advisor_metric_mean_bg, metrics.meanBg.roundToInt())).append("\n")
            append(rh.gs(R.string.aimi_advisor_metric_tdd, metrics.tdd, (metrics.basalPercent * 100).roundToInt()))
        }

        val content = TextView(this).apply {
            text = summaryText
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            setLineSpacing(4f, 1.2f)
        }
        
        card.addView(content)
        return card
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
    
    private fun createRecommendationCard(rec: AimiRecommendation, metrics: AdvisorMetrics): CardView {
        val card = CardView(this).apply {
            radius = 12f
            setCardBackgroundColor(getCardColor(rec.priority))
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        
        // Priority badge + Title
        layout.addView(TextView(this).apply {
            text = "${getPriorityEmoji(rec.priority)} ${rh.gs(rec.titleResId)}"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        
        // Domain tag
        layout.addView(TextView(this).apply {
            text = rec.domain.name
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 4, 0, 8)
        })
        
        // Description - Format with metrics if needed
        val description = when(rec.descriptionResId) {
             R.string.aimi_adv_rec_hypos_desc -> rh.gs(rec.descriptionResId, (metrics.timeBelow54 * 100).roundToInt(), metrics.severeHypoEvents)
             R.string.aimi_adv_rec_control_desc -> rh.gs(rec.descriptionResId, (metrics.tir70_180 * 100).roundToInt())
             R.string.aimi_adv_rec_hypers_desc -> rh.gs(rec.descriptionResId, (metrics.timeAbove180 * 100).roundToInt())
             R.string.aimi_adv_rec_basal_desc -> rh.gs(rec.descriptionResId, (metrics.basalPercent * 100).roundToInt())
             else -> rh.gs(rec.descriptionResId)
        }

        layout.addView(TextView(this).apply {
            text = description
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(2f, 1.1f)
            setPadding(0, 0, 0, 12)
        })
        
        // Suggested changes (Static)
        if (rec.actionsResIds.isNotEmpty()) {
            layout.addView(TextView(this).apply {
                text = "Actions suggérées :"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#90CAF9"))
                setPadding(0, 8, 0, 4)
            })
            
            rec.actionsResIds.forEach { actionId ->
                layout.addView(TextView(this).apply {
                    text = "• ${rh.gs(actionId)}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#B0BEC5"))
                    setPadding(16, 2, 0, 2)
                })
            }
        }

        // Suggested changes (Dynamic Rules Engine)
        if (rec.advisorActions.isNotEmpty()) {
            // Re-add header if not already added by static actions
            if (rec.actionsResIds.isEmpty()) {
                layout.addView(TextView(this).apply {
                    text = "Actions suggérées :"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#90CAF9"))
                    setPadding(0, 8, 0, 4)
                })
            }

            rec.advisorActions.forEach { action ->
                layout.addView(TextView(this).apply {
                    text = "• ${formatAction(action)}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#B0BEC5"))
                    setPadding(16, 2, 0, 2)
                })
            }
        }
        
        card.addView(layout)
        return card
    }

    private fun formatAction(action: AdvisorAction): String {
        return when (action.actionCode) {
            AdvisorActionCode.INCREASE_NIGHT_BASAL -> rh.gs(
                R.string.aimi_action_increase_night_basal,
                action.params["from"] as Double,
                action.params["to"] as Double
            )
            AdvisorActionCode.REDUCE_MAX_SMB -> rh.gs(
                R.string.aimi_action_reduce_max_smb,
                action.params["from"] as Double,
                action.params["to"] as Double
            )
            AdvisorActionCode.INCREASE_LUNCH_FACTOR -> rh.gs(
                R.string.aimi_action_increase_lunch_factor,
                action.params["from"] as Double,
                action.params["to"] as Double
            )
        }
    }
    private fun createCoachCard(context: AdvisorContext, report: AdvisorReport): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(Color.parseColor("#121212")) // Blackish
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        // Title
        layout.addView(TextView(this).apply {
            text = "✨ ${rh.gs(R.string.aimi_coach_title)}"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#BB86FC")) // Material Purple
            setPadding(0, 0, 0, 16)
        })

        val contentText = TextView(this).apply {
            text = rh.gs(R.string.aimi_coach_loading)
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setLineSpacing(4f, 1.2f)
        }
        layout.addView(contentText)

        card.addView(layout)

        // Trigger AI loading
        // In a real app, use ViewModel/LifecycleScope. Here we use GlobalScope for simplicity in this file-based context
        // or recreate a scope since we are in Activity.
        val apiKey = "" // TODO: Load from prefs
        
        if (apiKey.isBlank()) {
            contentText.text = rh.gs(R.string.aimi_coach_placeholder)
        } else {
             kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    val advice = AiCoachingService().fetchAdvice(context, report, apiKey)
                    contentText.text = advice
                } catch (e: Exception) {
                    contentText.text = rh.gs(R.string.aimi_coach_error)
                }
            }
        }

        return card
    }

    private fun createFooter(report: AdvisorReport): TextView {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(report.generatedAt))
        
        return TextView(this).apply {
            text = "Généré le $time"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
    }
    
    private fun getScoreColor(severity: AdvisorSeverity): Int = when (severity) {
        AdvisorSeverity.GOOD -> Color.parseColor("#4CAF50")  // Green
        AdvisorSeverity.WARNING -> Color.parseColor("#FFC107")  // Amber
        AdvisorSeverity.CRITICAL -> Color.parseColor("#FF5722") // Deep orange
    }
    
    private fun getCardColor(priority: RecommendationPriority): Int = when (priority) {
        RecommendationPriority.CRITICAL -> Color.parseColor("#4A1A1A")  // Dark red
        RecommendationPriority.HIGH -> Color.parseColor("#4A3A1A")      // Dark orange
        RecommendationPriority.MEDIUM -> Color.parseColor("#2D3A4A")    // Dark blue
        RecommendationPriority.LOW -> Color.parseColor("#2D2D2D")       // Dark gray
    }
    
    private fun getPriorityEmoji(priority: RecommendationPriority): String = when (priority) {
        RecommendationPriority.CRITICAL -> "🔴"
        RecommendationPriority.HIGH -> "🟠"
        RecommendationPriority.MEDIUM -> "🟡"
        RecommendationPriority.LOW -> "🟢"
    }
}
