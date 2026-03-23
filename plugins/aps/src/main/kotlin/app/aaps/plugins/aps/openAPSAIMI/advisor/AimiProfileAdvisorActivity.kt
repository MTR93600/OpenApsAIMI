package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.plugins.aps.openAPSAIMI.model.*
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
import android.content.Intent
import java.util.Locale


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
    @Inject lateinit var aapsLogger: app.aaps.core.interfaces.logging.AAPSLogger
    
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
            tirCalculator = tirCalculator,
            aapsLogger = aapsLogger
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
                    val standardRecs = report.recommendations.filter { it.domain != AimiDomain.Pkpd }
                    val pkpdRecs = report.recommendations.filter { it.domain == AimiDomain.Pkpd }

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

            val supportBtn = TextView(this@AimiProfileAdvisorActivity).apply {
                text = "🩺"
                textSize = 22f
                setPadding(24, 0, 0, 0)
                setOnClickListener {
                    showSupportDialog()
                }
            }
            addView(supportBtn)



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

            // Basal Proposal Button (Preview/Export only, no auto-apply)
            val basalProposalBtn = TextView(this@AimiProfileAdvisorActivity).apply {
                text = "🧪"
                textSize = 22f
                setPadding(24, 0, 0, 0)
                setOnClickListener {
                    showBasalProposalDialog()
                }
            }
            addView(basalProposalBtn)
        }
    }

    private fun showBasalProposalDialog() {
        android.widget.Toast.makeText(this, "Generating basal proposal...", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val proposal = advisorService.generateBasalProfileProposal(periodDays = 7)
                val preview = buildBasalProposalPreview(proposal)
                val exportText = advisorService.exportBasalProfileProposalText(proposal)

                withContext(Dispatchers.Main) {
                    androidx.appcompat.app.AlertDialog.Builder(this@AimiProfileAdvisorActivity)
                        .setTitle("Basal Proposal (Preview)")
                        .setMessage(preview)
                        .setPositiveButton("Export") { _, _ ->
                            shareBasalProposal(exportText)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@AimiProfileAdvisorActivity,
                        "Basal proposal failed: ${e.localizedMessage}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildBasalProposalPreview(proposal: AimiAdvisorService.BasalProfileProposal): String {
        if (proposal.rows.isEmpty()) {
            return "No profile data available.\nNo proposal generated."
        }
        val firstRows = proposal.rows.take(6).joinToString("\n") { row ->
            val deltaPct = if (row.current > 0.0) ((row.proposed / row.current) - 1.0) * 100.0 else 0.0
            String.format(
                Locale.US,
                "%02dh  %.2f -> %.2f U/h (%+.1f%%)",
                row.hour,
                row.current,
                row.proposed,
                deltaPct
            )
        }
        return buildString {
            appendLine("This is a proposal only. No automatic profile update.")
            appendLine("Strategy: ${proposal.strategy}")
            appendLine("Factor: ${"%.3f".format(Locale.US, proposal.scalingFactor)}")
            appendLine("Rationale: ${proposal.rationale}")
            appendLine()
            appendLine("Preview (first 6 hours):")
            appendLine(firstRows)
        }
    }

    private fun shareBasalProposal(content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AIMI Basal Proposal")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Export basal proposal"))
    }

    private fun showSupportDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter Expert Code"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = android.widget.FrameLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_adv_support_title))
            .setMessage(rh.gs(R.string.aimi_adv_support_msg))
            .setView(layout)
            .setPositiveButton(rh.gs(R.string.aimi_adv_support_verify)) { _, _ ->
                val code = input.text.toString()
                if (app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiDiagnosticsManager.verifyCode(code)) {
                    showIssueDialog()
                } else {
                    android.widget.Toast.makeText(this, rh.gs(R.string.aimi_adv_support_invalid), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIssueDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Describe your issue (optional)..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        val layout = android.widget.FrameLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_adv_issue_title))
            .setMessage(rh.gs(R.string.aimi_adv_issue_msg))
            .setView(layout)
            .setPositiveButton(rh.gs(R.string.aimi_adv_generate_btn)) { _, _ ->
                val issue = input.text.toString()
                generateAndShareReport(issue)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateAndShareReport(issue: String) {
        android.widget.Toast.makeText(this, "Generating diagnostic report...", android.widget.Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val diagManager = app.aaps.plugins.aps.openAPSAIMI.advisor.diag.AimiDiagnosticsManager(this@AimiProfileAdvisorActivity, preferences, aapsLogger)
                val reportContent = diagManager.generateReport(issue)
                val authority = "${packageName}.fileprovider"
                
                // Create a temporary ZIP file in cache
                val zipFileName = "AIMI_Support_Package_${System.currentTimeMillis()}.zip"
                val zipFile = java.io.File(cacheDir, zipFileName)
                
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(zipFile))).use { out ->
                    // 1. Add Diagnostic Report (Text)
                    if (reportContent.isNotEmpty()) {
                        val entry = java.util.zip.ZipEntry("Diagnostic_Report.txt")
                        out.putNextEntry(entry)
                        out.write(reportContent.toByteArray())
                        out.closeEntry()
                    }
                    
                    // 2. Add Decision Log (JSONL) - Last 24h ONLY
                    val externalDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
                    val jsonFile = java.io.File(externalDir, "AAPS/AIMI_Decisions.jsonl")
                    
                    if (jsonFile.exists() && jsonFile.canRead()) {
                        val entry = java.util.zip.ZipEntry("AIMI_Decisions_Last24h.jsonl")
                        out.putNextEntry(entry)
                        
                        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) // 24 hours ago
                        
                        // Buffer for reading/writing
                        val reader = java.io.BufferedReader(java.io.FileReader(jsonFile))
                        val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(out))
                        
                        try {
                            var line = reader.readLine()
                            while (line != null) {
                                // Fast heuristic check: assume timestamp is near the start or parse simple
                                // {"timestamp":1730000000000,...}
                                // We'll do a robust regex or substring find to avoid full JSON parsing (too heavy)
                                try {
                                    // Look for "timestamp":123456789
                                    val tsIdx = line.indexOf("\"timestamp\":")
                                    if (tsIdx != -1) {
                                        // Extract number after timestamp
                                        val start = tsIdx + 12
                                        var end = start
                                        while (end < line.length && line[end].isDigit()) {
                                            end++
                                        }
                                        if (end > start) {
                                            val tsStr = line.substring(start, end)
                                            val ts = tsStr.toLongOrNull()
                                            if (ts != null && ts >= cutoffTime) {
                                                writer.write(line)
                                                writer.newLine()
                                            }
                                        }
                                    } else {
                                        // If no timestamp found, maybe keep it or discard? Safe to discard for cleaner log.
                                    }
                                } catch (e: Exception) {
                                    // Ignore parse errors, skip line
                                }
                                line = reader.readLine()
                            }
                            writer.flush() // Flush BufferedWriter to ZipOutputStream
                        } finally {
                            reader.close()
                            // writer.close() -> Do NOT close writer here as it would close the ZipOutputStream!
                        }
                        out.closeEntry()
                    }
                }

                if (zipFile.exists() && zipFile.length() > 0) {
                     val uri = androidx.core.content.FileProvider.getUriForFile(this@AimiProfileAdvisorActivity, authority, zipFile)
                     
                     withContext(Dispatchers.Main) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                        intent.type = "application/zip"
                        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, rh.gs(R.string.aimi_diag_subject, java.util.Date().toString()))
                        intent.putExtra(android.content.Intent.EXTRA_TEXT, "AIMI Support Package attached (ZIP).\n\nDetails: $issue")
                        intent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        
                        startActivity(android.content.Intent.createChooser(intent, rh.gs(R.string.aimi_diag_chooser)))
                    }
                } else {
                     withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(this@AimiProfileAdvisorActivity, "Failed to create support package (Empty)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    aapsLogger.error("AIMI_DIAG", "Failed to generate/share report", e)
                    android.widget.Toast.makeText(this@AimiProfileAdvisorActivity, rh.gs(R.string.aimi_adv_error_gen) + ": " + e.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showModelSelectorDialog() {
        val current = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        val idx = when (current.uppercase()) {
            "OPENAI" -> 0
            "GEMINI" -> 1
            "DEEPSEEK" -> 2
            "CLAUDE" -> 3
            else -> 0
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_advisor_model_title)) // "Select Model"
            .setSingleChoiceItems(
                arrayOf("ChatGPT (GPT-5.2)", "Gemini (2.5 Flash)", "DeepSeek (Chat)", "Claude (3.5 Sonnet)"), 
                idx
            ) { dialog, which ->
                val newValue = when (which) {
                    0 -> "OPENAI"
                    1 -> "GEMINI"
                    2 -> "DEEPSEEK"
                    3 -> "CLAUDE"
                    else -> "OPENAI"
                }
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
        if (rec.action != null && rec.action is AimiAction.PreferenceUpdate) {
            val actionBtn = TextView(this).apply {
                text = rh.gs(R.string.aimi_adv_apply_btn)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8")) // Sky Blue
                gravity = Gravity.END
                setPadding(0, 16, 0, 0)
                setOnClickListener {
                    showApplyActionDialog(rec.action as AimiAction.PreferenceUpdate)
                }
            }
            textLayout.addView(actionBtn)
        }
        
        row.addView(textLayout)
        card.addView(row)
        return card
    }

    private fun showApplyActionDialog(action: AimiAction.PreferenceUpdate) {
        val sb = StringBuilder()
        sb.append(rh.gs(R.string.aimi_adv_apply_dialog_prefix))
        
        // Single preference update in the new model vs list in old
        sb.append("• ${action.key.key}: ➔ ${action.newValue}\n")
        sb.append("  ${action.reason}\n\n")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_adv_apply_dialog_title))
            .setMessage(sb.toString())
            .setPositiveButton(rh.gs(R.string.aimi_adv_apply_dialog_confirm)) { _, _ ->
                applyAction(action)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAction(action: AimiAction.PreferenceUpdate) {
        try {
            var applied = false
            val newValue = action.newValue
            val key = action.key

            when {
                newValue is Double && key is DoublePreferenceKey -> {
                    preferences.put(key, newValue)
                    applied = true
                }
                newValue is Int && key is IntPreferenceKey -> {
                    preferences.put(key, newValue)
                    applied = true
                }
                newValue is Boolean && key is BooleanPreferenceKey -> {
                    preferences.put(key, newValue)
                    applied = true
                }
                newValue is String && key is StringPreferenceKey -> {
                    preferences.put(key, newValue)
                    applied = true
                }
            }

            if (applied) {
                 logAction(action)
                 android.widget.Toast.makeText(this, rh.gs(R.string.aimi_adv_success_msg, 1), android.widget.Toast.LENGTH_SHORT).show()
                 recreate()
            } else {
                 android.widget.Toast.makeText(this, rh.gs(R.string.aimi_adv_no_change_msg), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "${rh.gs(R.string.aimi_adv_error_prefix)}${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun logAction(action: AimiAction.PreferenceUpdate) {
        val keyStr = action.key.key

         historyRepo.logAction(
                app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                keyStr, 
                action.reason,
                "OLD", // New model doesn't strictly store old value, but we could find it if needed
                action.newValue.toString()
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
        val deepSeekKey = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorDeepSeekKey)
        val claudeKey = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorClaudeKey)

        val provider = when (providerStr.uppercase()) {
            "GEMINI" -> AiCoachingService.Provider.GEMINI
            "DEEPSEEK" -> AiCoachingService.Provider.DEEPSEEK
            "CLAUDE" -> AiCoachingService.Provider.CLAUDE
            else -> AiCoachingService.Provider.OPENAI
        }
        
        val activeKey = when (provider) {
            AiCoachingService.Provider.GEMINI -> geminiKey
            AiCoachingService.Provider.DEEPSEEK -> deepSeekKey
            AiCoachingService.Provider.CLAUDE -> claudeKey
            else -> openAiKey
        }
        
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
        AdvisorSeverity.Good -> Color.parseColor("#4ADE80")  // Green
        AdvisorSeverity.Warning -> Color.parseColor("#FACC15")  // Warning
        AdvisorSeverity.Critical -> Color.parseColor("#F87171") // Red
    }
    
    private fun getPriorityEmoji(priority: AimiPriority): String = when (priority) {
        AimiPriority.Critical -> "⚠️"
        AimiPriority.High -> "📈"
        AimiPriority.Medium -> "ℹ️"
        AimiPriority.Low -> "✅"
        else -> "ℹ️"
    }
    
    // Extension for dp to px
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()
}
