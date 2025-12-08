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
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import javax.inject.Inject

/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY - ROBUST VERSION
 * =============================================================================
 * 
 * Displays advisor recommendations based on glycemic performance analysis.
 * Uses programmatic UI to avoid additional layout XML.
 * 
 * IMPORTANT: advisorService is created manually, NOT injected via Dagger.
 * This eliminates any injection-related crash risk.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : TranslatedDaggerAppCompatActivity() {
    
    @Inject lateinit var rh: ResourceHelper
    
    // NOT injected - created manually to avoid Dagger issues
    private lateinit var advisorService: AimiAdvisorService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create service manually - zero injection risk
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
        
        // Generate report (safe - no external dependencies)
        val report = advisorService.generateReport(periodDays = 7)
        
        // Header with score
        rootLayout.addView(createScoreHeader(report))
        
        // Summary section
        rootLayout.addView(createSummaryCard(report))
        
        // Recommendations
        rootLayout.addView(createSectionTitle("Recommandations"))
        
        report.recommendations.forEach { rec ->
            rootLayout.addView(createRecommendationCard(rec))
        }
        
        // Footer
        rootLayout.addView(createFooter(report))
    }
    
    private fun createScoreHeader(report: AdvisorReport): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
            
            // Score circle (simulated with text)
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = "${"%.1f".format(report.overallScore)}/10"
                textSize = 48f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getScoreColor(report.overallScore))
                gravity = Gravity.CENTER
            })
            
            // Assessment label
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = report.overallAssessment
                textSize = 20f
                setTextColor(getScoreColor(report.overallScore))
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
        
        val content = TextView(this).apply {
            text = report.summary
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
    
    private fun createRecommendationCard(rec: AimiRecommendation): CardView {
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
            text = "${getPriorityEmoji(rec.priority)} ${rec.title}"
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
        
        // Description
        layout.addView(TextView(this).apply {
            text = rec.description
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(2f, 1.1f)
            setPadding(0, 0, 0, 12)
        })
        
        // Suggested changes
        if (rec.suggestedChanges.isNotEmpty()) {
            layout.addView(TextView(this).apply {
                text = "Actions suggérées :"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#90CAF9"))
                setPadding(0, 8, 0, 4)
            })
            
            rec.suggestedChanges.forEach { change ->
                layout.addView(TextView(this).apply {
                    text = "• $change"
                    textSize = 13f
                    setTextColor(Color.parseColor("#B0BEC5"))
                    setPadding(16, 2, 0, 2)
                })
            }
        }
        
        card.addView(layout)
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
    
    private fun getScoreColor(score: Double): Int = when {
        score >= 8.0 -> Color.parseColor("#4CAF50")  // Green
        score >= 6.5 -> Color.parseColor("#8BC34A")  // Light green
        score >= 5.0 -> Color.parseColor("#FFC107")  // Amber
        else -> Color.parseColor("#FF5722")          // Deep orange
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
