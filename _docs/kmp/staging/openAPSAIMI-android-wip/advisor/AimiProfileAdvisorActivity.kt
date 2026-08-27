package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.plugins.aps.openAPSAIMI.model.*
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.core.ui.locale.LocaleHelper
import app.aaps.plugins.aps.R
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefAnalysisReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefUserInsightFormatter
import dev.zacsweers.metro.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import android.content.Intent
import app.aaps.core.keys.StringKey
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextApplySupport
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningApplyResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningExportStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningPlan
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeNumericStats
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.JsonlTailReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservation
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationFamily
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationLevel
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationSignal
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cOwnershipTransition
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeNumericStats
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiControlCenterPendingChanges
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiFamilyWritebackPlan
import java.util.Locale


/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY
 * =============================================================================
 * Displays advisor recommendations using localized resources.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : MetroAppCompatActivity() {
    
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: app.aaps.core.interfaces.profile.ProfileFunction
    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer
    @Inject lateinit var preferences: app.aaps.core.keys.interfaces.Preferences
    @Inject lateinit var unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
    @Inject lateinit var tddCalculator: app.aaps.core.interfaces.stats.TddCalculator
    @Inject lateinit var tirCalculator: app.aaps.core.interfaces.stats.TirCalculator
    @Inject lateinit var aapsLogger: app.aaps.core.interfaces.logging.AAPSLogger
    @Inject lateinit var importExportPrefs: ImportExportPrefs
    @Inject lateinit var exportPasswordDataStore: ExportPasswordDataStore
    
    // NOT injected - created manually to avoid Dagger issues
    private lateinit var advisorService: AimiAdvisorService
    private lateinit var historyRepo: app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository

    /** Observation / PKPD recommendation cards; used to remove rows after apply without full recreate(). */
    private val recommendationRowViews = mutableListOf<Pair<View, AimiRecommendation>>()

    private var lastReport: AdvisorReport? = null
    private var selectedTuningContext: AimiTuningContext =
        AimiTuningContext.AUTO_BALANCE

    private val bgColor = Color.parseColor("#10141C")
    private val cardColor = Color.parseColor("#1E293B")
    private lateinit var rootLayout: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

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
        selectedTuningContext = TuningContextEngine.parseContext(
            preferences.get(StringKey.AimiTuningContextSelection),
        )
        title = rh.gs(R.string.aimi_advisor_title)

        rootLayout = LinearLayout(this).apply {
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

        val loadingText = TextView(this).apply {
            text = rh.gs(R.string.aimi_adv_loading)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        rootLayout.addView(loadingText)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val history = historyRepo.getRecentActions(10)
                val report = advisorService.generateReport(
                    periodDays = 10,
                    history = history,
                    assetContext = applicationContext,
                )
                var deferredInsertIndex = 0
                var historyLoadingText: TextView? = null
                withContext(Dispatchers.Main) {
                    if (isFinishing) return@withContext
                    rootLayout.removeView(loadingText)
                    recommendationRowViews.clear()
                    lastReport = report
                    deferredInsertIndex = populateCoreReportUi(report)
                    historyLoadingText = TextView(this@AimiProfileAdvisorActivity).apply {
                        text = rh.gs(R.string.aimi_adv_loading_details)
                        textSize = 13f
                        setTextColor(Color.parseColor("#94A3B8"))
                        gravity = Gravity.CENTER
                        setPadding(0, 24, 0, 24)
                    }
                    rootLayout.addView(historyLoadingText, deferredInsertIndex)
                }

                val lastRbtExport = loadLastRecursiveBeliefJson()
                val t3cHistorySummary = T3cRuntimeHistoryReader.summarizeLast24Hours()
                val harmoniaHistorySummary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours()

                withContext(Dispatchers.Main) {
                    if (isFinishing) return@withContext
                    historyLoadingText?.let { rootLayout.removeView(it) }
                    populateDeferredHistorySections(
                        insertIndex = deferredInsertIndex,
                        lastRbtExport = lastRbtExport,
                        t3cHistorySummary = t3cHistorySummary,
                        harmoniaHistorySummary = harmoniaHistorySummary,
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing) {
                        val msg = when (t) {
                            is OutOfMemoryError -> rh.gs(R.string.aimi_adv_error_oom)
                            else -> "${rh.gs(R.string.aimi_adv_error_prefix)}${t.localizedMessage ?: t.javaClass.simpleName}"
                        }
                        // loadingText may already be detached (removed before populateCoreReportUi);
                        // re-attach it so the error is actually visible instead of failing silently.
                        if (loadingText.parent == null) rootLayout.addView(loadingText)
                        loadingText.text = msg
                        loadingText.setTextColor(Color.parseColor("#F87171"))
                    }
                }
                t.printStackTrace()
            }
        }
    }

    /**
     * Main Advisor content (metrics, recommendations, coach). RBT/T3c JSONL sections load afterward.
     * @return child index where deferred history cards should be inserted.
     */
    private fun populateCoreReportUi(report: AdvisorReport): Int {
        val advisorCtx = report.advisorContext

        safeAddCard("header") { rootLayout.addView(createDashboardHeader(report)) }
        safeAddCard("tuning") { rootLayout.addView(createTuningContextCard(report, cardColor)) }

        val familyBridgeSuggestions =
            runCatching { buildAimiFamilyBridgeSuggestions(preferences, report.metrics) }.getOrDefault(emptyList())
        val causalInsights = runCatching {
            buildAimiBehaviorCausalInsights(
                preferences = preferences,
                metrics = report.metrics,
                familyBridgeSuggestions = familyBridgeSuggestions,
            )
        }.getOrDefault(emptyList())

        safeAddCard("causal map") { rootLayout.addView(createBehaviorCausalMapCard(causalInsights, familyBridgeSuggestions, cardColor)) }
        safeAddCard("family bridge") { rootLayout.addView(createBehaviorFamilyBridgeCard(familyBridgeSuggestions, cardColor)) }

        val deferredInsertIndex = rootLayout.childCount

        safeAddCard("metrics") { rootLayout.addView(createMetricsGrid(report.metrics, cardColor)) }

        val standardRecs = report.recommendations.filter { it.domain != AimiDomain.Pkpd }
        val pkpdRecs = report.recommendations.filter { it.domain == AimiDomain.Pkpd }

        if (standardRecs.isNotEmpty()) {
            rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_obs)))
            standardRecs.forEach { rec ->
                safeAddCard("observation") {
                    val card = createObservationCard(rec, report.metrics, cardColor)
                    rootLayout.addView(card)
                    recommendationRowViews.add(card to rec)
                }
            }
        }

        if (pkpdRecs.isNotEmpty()) {
            rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_pkpd)))
            pkpdRecs.forEach { rec ->
                safeAddCard("pkpd") {
                    val card = createObservationCard(rec, report.metrics, cardColor)
                    rootLayout.addView(card)
                    recommendationRowViews.add(card to rec)
                }
            }
        }

        rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_brain)))
        safeAddCard("brain") { rootLayout.addView(createCognitiveCard(advisorCtx.prefs.unifiedReactivityFactor, cardColor)) }

        report.orefAnalysis?.let { oref ->
            rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_oref)))
            safeAddCard("oref") { rootLayout.addView(createOrefAnalysisCard(oref, cardColor)) }
        }

        rootLayout.addView(createSectionHeader(rh.gs(R.string.aimi_adv_section_coach)))
        safeAddCard("coach") { rootLayout.addView(createCoachCard(advisorCtx, report, causalInsights, cardColor)) }
        safeAddCard("footer") { rootLayout.addView(createFooter(report)) }

        return deferredInsertIndex
    }

    /** Adds a card, isolating render failures so one broken card never blanks the rest of the advisor. */
    private inline fun safeAddCard(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            t.printStackTrace()
            rootLayout.addView(
                TextView(this).apply {
                    text = rh.gs(R.string.aimi_adv_card_render_error, label, t.javaClass.simpleName)
                    textSize = 12f
                    setTextColor(Color.parseColor("#F87171"))
                    setPadding(24, 12, 24, 12)
                }
            )
        }
    }

    private fun populateDeferredHistorySections(
        insertIndex: Int,
        lastRbtExport: org.json.JSONObject?,
        t3cHistorySummary: T3cRuntimeHistorySummary?,
        harmoniaHistorySummary: HarmoniaRuntimeHistorySummary?,
    ) {
        rootLayout.addView(createRecursiveBeliefUnfoldCard(cardColor, lastRbtExport), insertIndex)
        rootLayout.addView(createT3cRuntimeHistoryCard(cardColor, t3cHistorySummary), insertIndex + 1)
        rootLayout.addView(createHarmoniaRuntimeHistoryCard(cardColor, harmoniaHistorySummary), insertIndex + 2)
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
                    val jsonFile = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile()
                    
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
                arrayOf("ChatGPT (GPT-5.4 mini)", "Gemini (Flash)", "DeepSeek (Chat)", "Claude (Haiku 4.5)"),
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
        
        var desc = when {
            rec.descriptionResId == 0 -> {
                val act = rec.action
                when (act) {
                    is AimiAction.PreferenceUpdate -> act.reason
                    else -> rec.descriptionArgs.joinToString(" ").ifEmpty { "" }
                }
            }
            rec.descriptionResId == R.string.aimi_adv_rec_hypos_desc ->
                rh.gs(rec.descriptionResId, (metrics.timeBelow54 * 100).roundToInt(), metrics.severeHypoEvents)
            rec.descriptionResId == R.string.aimi_adv_rec_control_desc ->
                rh.gs(rec.descriptionResId, (metrics.tir70_180 * 100).roundToInt())
            rec.descriptionResId == R.string.aimi_adv_rec_hypers_desc ->
                rh.gs(rec.descriptionResId, (metrics.timeAbove180 * 100).roundToInt())
            rec.descriptionResId == R.string.aimi_adv_rec_basal_desc ->
                rh.gs(rec.descriptionResId, (metrics.basalPercent * 100).roundToInt())
            rec.descriptionArgs.isNotEmpty() -> {
                try {
                    rh.gs(rec.descriptionResId, *rec.descriptionArgs.toTypedArray())
                } catch (e: Exception) {
                    rh.gs(rec.descriptionResId) + " " + rec.descriptionArgs.joinToString(" ")
                }
            }
            else -> rh.gs(rec.descriptionResId)
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
            .setPositiveButton(rh.gs(R.string.aimi_adv_apply_btn)) { _, _ ->
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
                 refreshRecommendationRowsAfterApply()
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

    private fun refreshRecommendationRowsAfterApply() {
        val history = historyRepo.getRecentActions(10)
        val toRemove = recommendationRowViews.filter { (_, rec) ->
            !advisorService.isRecommendationVisible(rec, history)
        }
        toRemove.forEach { (view, _) ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        recommendationRowViews.removeAll { toRemove.contains(it) }
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

    private fun createOrefAnalysisCard(oref: OrefAnalysisReport, cardBg: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardBg)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 32)
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.aimi_adv_oref_user_insight_title)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, 8)
        })
        layout.addView(TextView(this).apply {
            text = OrefUserInsightFormatter.buildParagraph(this@AimiProfileAdvisorActivity, oref)
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setLineSpacing(6f, 1.2f)
            setPadding(0, 0, 0, 20)
        })
        layout.addView(TextView(this).apply {
            text = oref.toPromptSection().trim()
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setLineSpacing(5f, 1.15f)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        })

        val canShowCgmChart =
            oref.timeBelow70Pct != null || oref.timeInRange70180Pct != null || oref.timeAbove180Pct != null
        if (canShowCgmChart) {
            val chartBlock = createOrefCgmRangeChart(oref).apply {
                visibility = View.GONE
            }
            layout.addView(Button(this).apply {
                text = rh.gs(R.string.aimi_adv_oref_show_chart)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#334155"))
                setPadding(rh.dpToPx(12), rh.dpToPx(10), rh.dpToPx(12), rh.dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = rh.dpToPx(16)
                }
                setOnClickListener { buttonView ->
                    val show = chartBlock.visibility != View.VISIBLE
                    chartBlock.visibility = if (show) View.VISIBLE else View.GONE
                    (buttonView as Button).text = rh.gs(
                        if (show) R.string.aimi_adv_oref_hide_chart else R.string.aimi_adv_oref_show_chart
                    )
                }
            })
            layout.addView(chartBlock)
        }

        card.addView(layout)
        return card
    }

    /** Bar chart for OREF-window CGM distribution (same % as the monospace summary above). */
    private fun createOrefCgmRangeChart(oref: OrefAnalysisReport): LinearLayout {
        val maxBarPx = rh.dpToPx(120)
        val barWidthPx = rh.dpToPx(28)

        fun column(label: String, pct: Double?, barColor: Int): LinearLayout {
            val value = pct ?: 0.0
            val fillH = (value / 100.0 * maxBarPx).roundToInt().coerceIn(0, maxBarPx)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@AimiProfileAdvisorActivity).apply {
                    text = pct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_HORIZONTAL
                })

                val track = FrameLayout(this@AimiProfileAdvisorActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxBarPx).apply {
                        topMargin = rh.dpToPx(8)
                        bottomMargin = rh.dpToPx(8)
                    }
                    setBackgroundColor(Color.parseColor("#334155"))
                }
                track.addView(View(this@AimiProfileAdvisorActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(barWidthPx, fillH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    setBackgroundColor(barColor)
                })
                addView(track)

                addView(TextView(this@AimiProfileAdvisorActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.parseColor("#94A3B8"))
                    gravity = Gravity.CENTER_HORIZONTAL
                })
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = rh.dpToPx(8)
            }
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(R.string.aimi_adv_oref_chart_title)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, 0, 0, rh.dpToPx(8))
            })
            addView(LinearLayout(this@AimiProfileAdvisorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(column(rh.gs(R.string.aimi_adv_oref_bar_low), oref.timeBelow70Pct, Color.parseColor("#F87171")))
                addView(column(rh.gs(R.string.aimi_adv_oref_bar_in_range), oref.timeInRange70180Pct, Color.parseColor("#4ADE80")))
                addView(column(rh.gs(R.string.aimi_adv_oref_bar_high), oref.timeAbove180Pct, Color.parseColor("#FBBF24")))
            })
        }
    }

    private fun createCoachCard(
        context: AdvisorContext,
        report: AdvisorReport,
        causalInsights: List<AimiBehaviorCausalInsight>,
        cardBg: Int,
    ): CardView {
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
            val basicAnalysis = advisorService.generatePlainTextAnalysis(context, report, insightContext = this@AimiProfileAdvisorActivity)
            val placeholder = rh.gs(R.string.aimi_coach_placeholder) + " (${provider.name})"
            contentText.text = "$basicAnalysis\n\n⚙️ $placeholder"
        } else {
            lifecycleScope.launch {
                try {
                    val history = withContext(Dispatchers.IO) {
                        historyRepo.getRecentActions(7)
                    }
                    val richOref = preferences.get(BooleanKey.OApsAIMIAdvisorLlmRichOref)
                    val advice = AiCoachingService().fetchAdvice(
                        this@AimiProfileAdvisorActivity,
                        context,
                        report,
                        activeKey,
                        provider,
                        history,
                        includeRichOref = richOref,
                        causalInsights = causalInsights,
                    )
                    if (!isFinishing) {
                        contentText.text = advice
                    }
                } catch (t: Throwable) {
                    if (!isFinishing) {
                        val detail = when (t) {
                            is OutOfMemoryError -> rh.gs(R.string.aimi_adv_error_oom)
                            else -> t.localizedMessage ?: t.javaClass.simpleName
                        }
                        contentText.text = rh.gs(R.string.aimi_coach_error) + "\n" + detail
                    }
                }
            }
        }
        return card
    }

    private fun createTuningContextCard(report: AdvisorReport, cardColor: Int): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_tuning_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_tuning_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 16)
        })
        column.addView(createTuningContextChipRow())
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }
        buttonRow.addView(Button(this).apply {
            text = rh.gs(R.string.aimi_tuning_preview_btn)
            setOnClickListener { showTuningPreviewDialog(report) }
        })
        buttonRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 0)
        })
        buttonRow.addView(Button(this).apply {
            text = rh.gs(R.string.aimi_tuning_apply_btn)
            setOnClickListener { showTuningApplyDialog(report) }
        })
        column.addView(buttonRow)
        card.addView(column)
        return card
    }

    private fun createBehaviorCausalMapCard(
        insights: List<AimiBehaviorCausalInsight>,
        familyBridgeSuggestions: List<AimiFamilyBridgeSuggestion>,
        cardColor: Int,
    ): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_behavior_causal_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_behavior_causal_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 16)
        })

        if (insights.isEmpty()) {
            column.addView(TextView(this).apply {
                text = rh.gs(R.string.aimi_behavior_causal_none)
                textSize = 13f
                setTextColor(Color.WHITE)
            })
        } else {
            insights.forEach { insight ->
                val linkedSuggestion = insight.relatedSuggestionId?.let { relatedId ->
                    familyBridgeSuggestions.firstOrNull { it.id == relatedId }
                }
                column.addView(createBehaviorCausalInsightView(insight, linkedSuggestion))
            }
        }

        card.addView(column)
        return card
    }

    private fun createBehaviorFamilyBridgeCard(
        suggestions: List<AimiFamilyBridgeSuggestion>,
        cardColor: Int,
    ): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_family_bridge_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_family_bridge_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 16)
        })

        if (suggestions.isEmpty()) {
            column.addView(TextView(this).apply {
                text = rh.gs(R.string.aimi_family_bridge_none)
                textSize = 13f
                setTextColor(Color.WHITE)
            })
        } else {
            suggestions.forEach { suggestion ->
                column.addView(createBehaviorFamilySuggestionView(suggestion, cardColor))
            }
        }

        card.addView(column)
        return card
    }

    private fun createBehaviorCausalInsightView(
        insight: AimiBehaviorCausalInsight,
        linkedSuggestion: AimiFamilyBridgeSuggestion?,
    ): View {
        val card = CardView(this).apply {
            radius = 14f
            setCardBackgroundColor(Color.parseColor("#162033"))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 16) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(insight.titleResId)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        column.addView(TextView(this).apply {
            text = if (insight.bodyArgs.isEmpty()) {
                rh.gs(insight.bodyResId)
            } else {
                rh.gs(insight.bodyResId, *insight.bodyArgs.toTypedArray())
            }
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 12)
        })
        column.addView(
            createFamilyChipRow(
                listOf(insight.primaryFamily) + insight.secondaryFamilies,
            ),
        )
        column.addView(TextView(this).apply {
            text = rh.gs(
                R.string.aimi_behavior_causal_confidence,
                (insight.confidence * 100f).roundToInt(),
            )
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 12, 0, 0)
        })
        insight.evidence.forEach { line ->
            column.addView(TextView(this).apply {
                text = "• $line"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 6, 0, 0)
            })
        }
        linkedSuggestion?.let { suggestion ->
            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 16, 0, 0)
            }
            buttonRow.addView(Button(this).apply {
                text = rh.gs(R.string.aimi_family_bridge_preview_btn)
                setOnClickListener { showBehaviorFamilyPreviewDialog(suggestion) }
            })
            buttonRow.addView(Space(this).apply {
                layoutParams = LinearLayout.LayoutParams(16, 0)
            })
            buttonRow.addView(Button(this).apply {
                text = rh.gs(R.string.aimi_family_bridge_apply_btn)
                setOnClickListener { showBehaviorFamilyApplyDialog(suggestion) }
            })
            column.addView(buttonRow)
        }
        card.addView(column)
        return card
    }

    private fun createBehaviorFamilySuggestionView(
        suggestion: AimiFamilyBridgeSuggestion,
        cardColor: Int,
    ): View {
        val card = CardView(this).apply {
            radius = 14f
            setCardBackgroundColor(Color.parseColor("#162033"))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 16) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(suggestion.titleResId)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        column.addView(TextView(this).apply {
            text = if (suggestion.bodyArgs.isEmpty()) {
                rh.gs(suggestion.bodyResId)
            } else {
                rh.gs(suggestion.bodyResId, *suggestion.bodyArgs.toTypedArray())
            }
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 12)
        })
        column.addView(createFamilyChipRow(suggestion.affectedFamilies))
        column.addView(TextView(this).apply {
            text = buildFamilyBridgeDeltaLabel(suggestion)
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 12, 0, 0)
        })
        if (suggestion.driverKeys.isNotEmpty()) {
            column.addView(TextView(this).apply {
                text = rh.gs(R.string.aimi_family_bridge_driver_keys, formatDriverKeyLabels(suggestion.driverKeys))
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 8, 0, 0)
            })
        }
        column.addView(TextView(this).apply {
            text = rh.gs(
                R.string.aimi_family_bridge_preview_count,
                suggestion.pendingChanges.changedFamilyCount,
                suggestion.pendingChanges.changedSettingsCount,
            )
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 8, 0, 0)
        })
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }
        buttonRow.addView(Button(this).apply {
            text = rh.gs(R.string.aimi_family_bridge_preview_btn)
            setOnClickListener { showBehaviorFamilyPreviewDialog(suggestion) }
        })
        buttonRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 0)
        })
        buttonRow.addView(Button(this).apply {
            text = rh.gs(R.string.aimi_family_bridge_apply_btn)
            setOnClickListener { showBehaviorFamilyApplyDialog(suggestion) }
        })
        column.addView(buttonRow)
        card.addView(column)
        return card
    }

    private fun createFamilyChipRow(families: List<AimiBehaviorFamilyId>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        families.forEachIndexed { index, familyId ->
            if (index > 0) {
                row.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(8, 0)
                })
            }
            row.addView(TextView(this).apply {
                text = rh.gs(familyTitleResId(familyId))
                textSize = 11f
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(18, 8, 18, 8)
                setBackgroundColor(Color.parseColor("#C4B5FD"))
            })
        }
        return row
    }

    private fun showBehaviorFamilyPreviewDialog(suggestion: AimiFamilyBridgeSuggestion) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_family_bridge_preview_title))
            .setMessage(formatBehaviorFamilyPreview(suggestion.pendingChanges))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showBehaviorFamilyApplyDialog(suggestion: AimiFamilyBridgeSuggestion) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_family_bridge_apply_title))
            .setMessage(formatBehaviorFamilyPreview(suggestion.pendingChanges))
            .setPositiveButton(rh.gs(R.string.aimi_family_bridge_apply_btn)) { _, _ ->
                applyBehaviorFamilySuggestion(suggestion)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyBehaviorFamilySuggestion(suggestion: AimiFamilyBridgeSuggestion) {
        lifecycleScope.launch(Dispatchers.IO) {
            applyAimiFamilyBridgeSuggestion(preferences, suggestion)
            suggestion.pendingChanges.familyPlans
                .flatMap { it.changes }
                .forEach { change ->
                    historyRepo.logAction(
                        AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                        change.preferenceKey,
                        "AIMI family bridge",
                        change.before.valueText ?: change.before.valueResId ?: "",
                        change.after.valueText ?: change.after.valueResId ?: "",
                    )
                }
            withContext(Dispatchers.Main) {
                if (isFinishing) return@withContext
                android.widget.Toast.makeText(
                    this@AimiProfileAdvisorActivity,
                    rh.gs(R.string.aimi_control_center_apply_done),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                recreate()
            }
        }
    }

    private fun formatBehaviorFamilyPreview(pendingChanges: AimiControlCenterPendingChanges): String {
        if (!pendingChanges.hasChanges) return rh.gs(R.string.aimi_control_center_no_pending_changes)
        return buildString {
            append(
                rh.gs(
                    R.string.aimi_family_bridge_preview_count,
                    pendingChanges.changedFamilyCount,
                    pendingChanges.changedSettingsCount,
                ),
            )
            append("\n\n")
            pendingChanges.familyPlans.forEachIndexed { index, plan ->
                if (index > 0) append("\n\n")
                append(rh.gs(familyTitleResId(plan.familyId)))
                append(" — ")
                append(formatPlanLevelDelta(plan))
                plan.changes.take(8).forEach { change ->
                    append("\n• ")
                    // Same title-less-key guard as formatDriverKeyLabels: rh.gs(0) would throw.
                    append(if (change.titleResId != 0) rh.gs(change.titleResId) else humanizePrefKey(change.preferenceKey))
                    append(": ")
                    append(change.before.valueText ?: change.before.valueResId?.takeIf { it != 0 }?.let(rh::gs).orEmpty())
                    append(" -> ")
                    append(change.after.valueText ?: change.after.valueResId?.takeIf { it != 0 }?.let(rh::gs).orEmpty())
                }
                if (plan.changes.size > 8) {
                    append("\n• ")
                    append(rh.gs(R.string.aimi_family_bridge_more_changes, plan.changes.size - 8))
                }
            }
        }
    }

    private fun buildFamilyBridgeDeltaLabel(suggestion: AimiFamilyBridgeSuggestion): String =
        suggestion.pendingChanges.familyPlans.joinToString(" • ") { plan ->
            "${rh.gs(familyTitleResId(plan.familyId))}: ${formatPlanLevelDelta(plan)}"
        }

    private fun formatPlanLevelDelta(plan: AimiFamilyWritebackPlan): String =
        rh.gs(plan.currentLabelResId) + " -> " + rh.gs(plan.targetLabelResId)

    private fun formatDriverKeyLabels(keys: List<PreferenceKey>): String =
        keys.take(4).joinToString(", ") { key ->
            // AIMI tuning keys are programmatic (no prefs screen) so titleResId is 0; rh.gs(0) throws
            // Resources$NotFoundException. Fall back to a humanized pref-id instead of crashing.
            if (key.titleResId != 0) rh.gs(key.titleResId) else humanizePrefKey(key.key)
        }

    /** Human-friendly label for a title-less programmatic preference key (e.g. "key_openapsaimi_max_smb" → "Max Smb"). */
    private fun humanizePrefKey(rawKey: String): String =
        rawKey
            .removePrefix("key_")
            .replace(Regex("^(oaps_?aimi_?|openapsaimi_?|aimi_?)", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .ifBlank { rawKey }

    private fun familyTitleResId(familyId: AimiBehaviorFamilyId): Int =
        when (familyId) {
            AimiBehaviorFamilyId.Protection -> R.string.aimi_control_center_protection_title
            AimiBehaviorFamilyId.MealCapture -> R.string.aimi_control_center_meal_title
            AimiBehaviorFamilyId.Stability -> R.string.aimi_control_center_stability_title
            AimiBehaviorFamilyId.Physio -> R.string.aimi_control_center_physio_title
            AimiBehaviorFamilyId.Autonomy -> R.string.aimi_control_center_autonomy_title
        }

    private fun createRecursiveBeliefUnfoldCard(
        cardColor: Int,
        lastExport: org.json.JSONObject?,
    ): CardView {
        val shadowEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow)
        val authorityEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
        val waveletEnabled = preferences.get(BooleanKey.OApsAIMIRecursiveBeliefWavelet)
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_rbt_unfold_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_rbt_unfold_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 12)
        })
        column.addView(TextView(this).apply {
            text = rh.gs(
                R.string.aimi_rbt_unfold_mode,
                shadowEnabled.toString(),
                authorityEnabled.toString(),
                waveletEnabled.toString(),
            )
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        })
        val summaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        column.addView(summaryText)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }
        val viewBtn = Button(this).apply {
            text = rh.gs(R.string.aimi_rbt_unfold_view_btn)
            isEnabled = false
        }
        buttonRow.addView(viewBtn)
        column.addView(buttonRow)
        card.addView(column)

        if (lastExport == null) {
            summaryText.text = rh.gs(R.string.aimi_rbt_unfold_no_data)
        } else {
            val resolution = lastExport.optJSONObject("resolution")
            val auth = resolution?.optString("release_authority", "NONE") ?: "NONE"
            val smb = resolution?.optDouble("smb_demand_u", 0.0) ?: 0.0
            val paradoxCount = lastExport.optJSONArray("paradoxes")?.length() ?: 0
            val shadowOnly = lastExport.optBoolean("shadow_only", true)
            summaryText.text = rh.gs(
                R.string.aimi_rbt_unfold_summary,
                auth,
                smb,
                paradoxCount,
                shadowOnly.toString(),
            )
            viewBtn.isEnabled = true
            viewBtn.setOnClickListener {
                showRecursiveBeliefUnfoldDialog(lastExport.toString(2))
            }
        }
        return card
    }

    private fun createT3cRuntimeHistoryCard(
        cardColor: Int,
        summary: T3cRuntimeHistorySummary?,
    ): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_t3c_history_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_t3c_history_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 12)
        })

        if (summary == null) {
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_unavailable)))
            card.addView(column)
            return card
        }

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_period)))
        pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_ticks, summary.tickCount)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 0, 0, 0)
        })
        summary.dominantStatus?.let { status ->
            pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_dominant, t3cStatusLabel(status))).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 0, 0, 0)
            })
        }
        column.addView(pillRow)

        if (summary.notEnoughData) {
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_not_enough_data, summary.tickCount)).apply {
                setPadding(0, 16, 0, 0)
            })
            column.addView(createT3cFamilySignalsSection(summary))
            card.addView(column)
            return card
        }

        column.addView(createT3cHistoryBodyText(buildT3cHistoryObservation(summary)).apply {
            setPadding(0, 16, 0, 0)
        })
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_native_applied, summary.nativeAppliedCount, percentOf(summary.nativeAppliedCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_native_blocked, summary.nativeBlockedCount, percentOf(summary.nativeBlockedCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_legacy_fallback, summary.legacyFallbackCount, percentOf(summary.legacyFallbackCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_safety_terminal, summary.safetyTerminalCount, percentOf(summary.safetyTerminalCount, summary.tickCount))))
        summary.dominantBlocker?.let { blocker ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_blocker, blocker)))
        }
        summary.demandStats?.let { stats ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_demand, formatT3cRateStats(stats))))
        }
        summary.appliedRateStats?.let { stats ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_applied_rate, formatT3cRateStats(stats))))
        }
        if (summary.transitionCount > 0) {
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_transitions, summary.transitionCount)))
            summary.dominantTransition?.let { transition ->
                column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_transition_detail, formatT3cTransition(transition))))
            }
        }

        column.addView(createT3cFamilySignalsSection(summary))

        card.addView(column)
        return card
    }

    private fun loadLastRecursiveBeliefJson(): org.json.JSONObject? {
        val jsonFile = T3cRuntimeHistoryReader.aimiDecisionsJsonlFile()
        if (!jsonFile.exists() || !jsonFile.canRead()) return null
        val tail = JsonlTailReader.readTailLines(jsonFile, maxLines = 80)
        for (line in tail) {
            if (!line.contains("recursive_belief")) continue
            try {
                val root = org.json.JSONObject(line)
                val adj = root.optJSONObject("adjustments") ?: continue
                val rb = adj.optJSONObject("recursive_belief") ?: continue
                return rb
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun createHarmoniaRuntimeHistoryCard(
        cardColor: Int,
        summary: HarmoniaRuntimeHistorySummary?,
    ): CardView {
        val card = CardView(this).apply {
            radius = 16f
            setCardBackgroundColor(cardColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, 32) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_harmonia_history_section_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        column.addView(TextView(this).apply {
            text = rh.gs(R.string.aimi_harmonia_history_section_desc)
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 8, 0, 12)
        })

        if (summary == null) {
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_unavailable)))
            card.addView(column)
            return card
        }

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_period)))
        pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_ticks, summary.tickCount)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 0, 0, 0)
        })
        summary.dominantStatus?.let { status ->
            pillRow.addView(createT3cHistoryPill(rh.gs(R.string.aimi_t3c_history_dominant, harmoniaStatusLabel(status))).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 0, 0, 0)
            })
        }
        column.addView(pillRow)

        if (summary.notEnoughData) {
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_not_enough_data, summary.tickCount)).apply {
                setPadding(0, 16, 0, 0)
            })
            card.addView(column)
            return card
        }

        column.addView(createT3cHistoryBodyText(buildHarmoniaHistoryObservation(summary)).apply {
            setPadding(0, 16, 0, 0)
        })
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_native_applied, summary.nativeAppliedCount, percentOf(summary.nativeAppliedCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_native_ready, summary.nativeReadyCount, percentOf(summary.nativeReadyCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_native_blocked, summary.nativeBlockedCount, percentOf(summary.nativeBlockedCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_t3c_priority, summary.t3cPriorityCount, percentOf(summary.t3cPriorityCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_smb_applied, summary.smbAppliedCount, percentOf(summary.smbAppliedCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_smb_ready, summary.smbReadyCount, percentOf(summary.smbReadyCount, summary.tickCount))))
        column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_smb_blocked, summary.smbBlockedCount, percentOf(summary.smbBlockedCount, summary.tickCount))))
        summary.dominantBlocker?.let { blocker ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_blocker, blocker)))
        }
        summary.demandStats?.let { stats ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_demand, formatHarmoniaRateStats(stats))))
        }
        summary.appliedRateStats?.let { stats ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_history_applied_rate, formatHarmoniaRateStats(stats))))
        }
        summary.smbDemandStats?.let { stats ->
            column.addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_harmonia_history_smb_demand, formatHarmoniaSmbStats(stats))))
        }
        card.addView(column)
        return card
    }

    private fun createT3cHistoryPill(text: String): CardView =
        CardView(this).apply {
            radius = 50f
            setCardBackgroundColor(Color.parseColor("#2A3345"))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                this.text = text
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(24, 10, 24, 10)
            })
        }

    private fun createT3cHistoryBodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 0)
        }

    private fun createT3cFamilySignalsSection(
        summary: T3cRuntimeHistorySummary,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 0)
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(R.string.aimi_t3c_family_signals_title)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#E2E8F0"))
            })
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = rh.gs(R.string.aimi_t3c_family_signals_desc)
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 6, 0, 4)
            })
            if (summary.notEnoughData || summary.familyObservations.isEmpty()) {
                addView(createT3cHistoryBodyText(rh.gs(R.string.aimi_t3c_family_signals_not_enough)))
            } else {
                summary.familyObservations.forEach { observation ->
                    addView(createT3cHistoryBodyText(formatT3cFamilyObservation(observation)))
                }
            }
        }

    private fun buildT3cHistoryObservation(summary: T3cRuntimeHistorySummary): String =
        when (summary.dominantStatus) {
            T3cRuntimeTickStatus.NATIVE_APPLIED -> rh.gs(
                R.string.aimi_t3c_history_observation_applied,
                percentOf(summary.nativeAppliedCount, summary.tickCount),
            )
            T3cRuntimeTickStatus.NATIVE_BLOCKED -> rh.gs(
                R.string.aimi_t3c_history_observation_blocked,
                summary.dominantBlocker ?: "unknown runtime blocker",
            )
            T3cRuntimeTickStatus.LEGACY_FALLBACK -> rh.gs(
                R.string.aimi_t3c_history_observation_legacy,
                percentOf(summary.legacyFallbackCount, summary.tickCount),
            )
            T3cRuntimeTickStatus.SAFETY_TERMINAL -> rh.gs(R.string.aimi_t3c_history_observation_safety)
            else -> rh.gs(R.string.aimi_t3c_history_observation_mixed)
        }

    private fun buildHarmoniaHistoryObservation(summary: HarmoniaRuntimeHistorySummary): String =
        when (summary.dominantStatus) {
            HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> rh.gs(
                R.string.aimi_harmonia_history_observation_applied,
                percentOf(summary.nativeAppliedCount, summary.tickCount),
            )
            HarmoniaRuntimeTickStatus.NATIVE_READY -> rh.gs(R.string.aimi_harmonia_history_observation_ready)
            HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> rh.gs(
                R.string.aimi_harmonia_history_observation_blocked,
                summary.dominantBlocker ?: "unknown runtime blocker",
            )
            HarmoniaRuntimeTickStatus.T3C_PRIORITY -> rh.gs(
                R.string.aimi_harmonia_history_observation_t3c_priority,
                percentOf(summary.t3cPriorityCount, summary.tickCount),
            )
            else -> rh.gs(R.string.aimi_harmonia_history_observation_mixed)
        }

    private fun formatT3cRateStats(stats: T3cRuntimeNumericStats): String {
        val average = String.format(Locale.US, "%.2f", stats.average)
        val min = String.format(Locale.US, "%.2f", stats.min)
        val max = String.format(Locale.US, "%.2f", stats.max)
        return "$average U/h ($min-$max)"
    }

    private fun formatHarmoniaRateStats(stats: HarmoniaRuntimeNumericStats): String {
        val average = String.format(Locale.US, "%.2f", stats.average)
        val min = String.format(Locale.US, "%.2f", stats.min)
        val max = String.format(Locale.US, "%.2f", stats.max)
        return "$average U/h ($min-$max)"
    }

    private fun formatHarmoniaSmbStats(stats: HarmoniaRuntimeNumericStats): String {
        val average = String.format(Locale.US, "%.2f", stats.average)
        val min = String.format(Locale.US, "%.2f", stats.min)
        val max = String.format(Locale.US, "%.2f", stats.max)
        return "$average U ($min-$max)"
    }

    private fun formatT3cTransition(transition: T3cOwnershipTransition): String =
        "${t3cOwnershipLabel(transition.from)} -> ${t3cOwnershipLabel(transition.to)}"

    private fun formatT3cFamilyObservation(
        observation: T3cAdvisorObservation,
    ): String =
        rh.gs(
            R.string.aimi_t3c_family_signal_row,
            t3cObservationFamilyLabel(observation.family),
            t3cObservationLevelLabel(observation.level),
            t3cObservationSignalLabel(observation.signal),
        )

    private fun t3cObservationFamilyLabel(
        family: T3cAdvisorObservationFamily,
    ): String =
        when (family) {
            T3cAdvisorObservationFamily.STABILITY -> rh.gs(R.string.aimi_t3c_family_stability)
            T3cAdvisorObservationFamily.MEAL_CAPTURE -> rh.gs(R.string.aimi_t3c_family_meal_capture)
            T3cAdvisorObservationFamily.PHYSIO_AMBIGUITY -> rh.gs(R.string.aimi_t3c_family_physio_ambiguity)
            T3cAdvisorObservationFamily.POST_HYPO_RECOVERY -> rh.gs(R.string.aimi_t3c_family_post_hypo)
            T3cAdvisorObservationFamily.ACTIVITY -> rh.gs(R.string.aimi_t3c_family_activity)
            T3cAdvisorObservationFamily.AUTONOMY -> rh.gs(R.string.aimi_t3c_family_autonomy)
            T3cAdvisorObservationFamily.NATIVE_RBT -> rh.gs(R.string.aimi_t3c_family_native_rbt)
        }

    private fun t3cObservationLevelLabel(
        level: T3cAdvisorObservationLevel,
    ): String =
        when (level) {
            T3cAdvisorObservationLevel.HIGH -> rh.gs(R.string.aimi_t3c_level_high)
            T3cAdvisorObservationLevel.MEDIUM -> rh.gs(R.string.aimi_t3c_level_medium)
            T3cAdvisorObservationLevel.LOW -> rh.gs(R.string.aimi_t3c_level_low)
            T3cAdvisorObservationLevel.STABLE -> rh.gs(R.string.aimi_t3c_level_stable)
        }

    private fun t3cObservationSignalLabel(
        signal: T3cAdvisorObservationSignal,
    ): String =
        when (signal) {
            T3cAdvisorObservationSignal.SAFETY_GATES_OFTEN_BLOCK -> rh.gs(R.string.aimi_t3c_signal_safety_gates_block)
            T3cAdvisorObservationSignal.MEAL_CONFLICTS_APPEAR -> rh.gs(R.string.aimi_t3c_signal_meal_conflicts)
            T3cAdvisorObservationSignal.POST_HYPO_GUARD_DOMINATES -> rh.gs(R.string.aimi_t3c_signal_post_hypo)
            T3cAdvisorObservationSignal.ACTIVITY_LOCKOUT_VISIBLE -> rh.gs(R.string.aimi_t3c_signal_activity_lockout)
            T3cAdvisorObservationSignal.LEGACY_FALLBACK_VISIBLE -> rh.gs(R.string.aimi_t3c_signal_legacy_fallback)
            T3cAdvisorObservationSignal.NATIVE_APPLIES_WHEN_CLEAR -> rh.gs(R.string.aimi_t3c_signal_native_clear)
            T3cAdvisorObservationSignal.BLOCKERS_STAY_MIXED -> rh.gs(R.string.aimi_t3c_signal_blockers_mixed)
        }

    private fun t3cStatusLabel(status: T3cRuntimeTickStatus): String =
        when (status) {
            T3cRuntimeTickStatus.NATIVE_APPLIED -> rh.gs(R.string.aimi_control_center_t3c_status_native_applied)
            T3cRuntimeTickStatus.NATIVE_READY -> rh.gs(R.string.aimi_control_center_t3c_status_native_ready)
            T3cRuntimeTickStatus.NATIVE_BLOCKED -> rh.gs(R.string.aimi_control_center_t3c_status_native_blocked)
            T3cRuntimeTickStatus.LEGACY_FALLBACK -> rh.gs(R.string.aimi_control_center_t3c_status_legacy_fallback)
            T3cRuntimeTickStatus.SAFETY_TERMINAL -> rh.gs(R.string.aimi_control_center_t3c_status_safety_terminal)
            T3cRuntimeTickStatus.UNAVAILABLE -> rh.gs(R.string.aimi_control_center_t3c_status_unavailable)
        }

    private fun harmoniaStatusLabel(status: HarmoniaRuntimeTickStatus): String =
        when (status) {
            HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> rh.gs(R.string.aimi_control_center_harmonia_status_native_applied)
            HarmoniaRuntimeTickStatus.NATIVE_READY -> rh.gs(R.string.aimi_control_center_harmonia_status_native_ready)
            HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> rh.gs(R.string.aimi_control_center_harmonia_status_native_blocked)
            HarmoniaRuntimeTickStatus.T3C_PRIORITY -> rh.gs(R.string.aimi_control_center_harmonia_status_t3c_priority)
            HarmoniaRuntimeTickStatus.UNAVAILABLE -> rh.gs(R.string.aimi_control_center_harmonia_status_unavailable)
        }

    private fun t3cOwnershipLabel(category: T3cRuntimeOwnershipCategory): String =
        when (category) {
            T3cRuntimeOwnershipCategory.NATIVE -> rh.gs(R.string.aimi_control_center_t3c_owner_native)
            T3cRuntimeOwnershipCategory.LEGACY -> rh.gs(R.string.aimi_control_center_t3c_owner_legacy)
            T3cRuntimeOwnershipCategory.SAFETY -> rh.gs(R.string.aimi_control_center_t3c_owner_safety)
            T3cRuntimeOwnershipCategory.UNAVAILABLE -> rh.gs(R.string.aimi_control_center_t3c_owner_unavailable)
        }

    private fun percentOf(
        count: Int,
        total: Int,
    ): Int =
        if (count <= 0 || total <= 0) 0 else ((count * 100.0) / total).roundToInt()

    private fun showRecursiveBeliefUnfoldDialog(prettyJson: String) {
        val scroll = ScrollView(this).apply {
            addView(TextView(this@AimiProfileAdvisorActivity).apply {
                text = prettyJson
                textSize = 11f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(32, 24, 32, 24)
                setTextIsSelectable(true)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_rbt_unfold_dialog_title))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun createTuningContextChipRow(): LinearLayout {
        val contexts = listOf(
            AimiTuningContext.MEAL_RISE to R.string.aimi_tuning_context_meal_rise,
            AimiTuningContext.HYPO_GUARD to R.string.aimi_tuning_context_hypo_guard,
            AimiTuningContext.HYPER_STABLE to R.string.aimi_tuning_context_hyper_stable,
            AimiTuningContext.AUTO_BALANCE to R.string.aimi_tuning_context_auto,
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        contexts.forEachIndexed { index, (ctx, labelRes) ->
            if (index > 0) {
                row.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(8, 0)
                })
            }
            row.addView(Button(this).apply {
                text = rh.gs(labelRes)
                isAllCaps = false
                val selected = selectedTuningContext == ctx
                setBackgroundColor(
                    if (selected) Color.parseColor("#38BDF8") else Color.parseColor("#334155"),
                )
                setTextColor(if (selected) Color.parseColor("#0F172A") else Color.WHITE)
                setOnClickListener {
                    selectedTuningContext = ctx
                    preferences.put(StringKey.AimiTuningContextSelection, ctx.name)
                    recreate()
                }
            })
        }
        return row
    }

    private fun buildTuningPlan(report: AdvisorReport): TuningPlan {
        val t3c = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        return TuningContextEngine.computePlan(
            requestedContext = selectedTuningContext,
            metrics = report.metrics,
            preferences = preferences,
            t3cBrittleMode = t3c,
        )
    }

    private fun tierLabel(tier: TuningStepTier): String = when (tier) {
        TuningStepTier.MICRO -> rh.gs(R.string.aimi_tuning_tier_micro)
        TuningStepTier.MODERATE -> rh.gs(R.string.aimi_tuning_tier_moderate)
        TuningStepTier.STRONG -> rh.gs(R.string.aimi_tuning_tier_strong)
    }

    private fun showTuningPreviewDialog(report: AdvisorReport) {
        val plan = buildTuningPlan(report)
        val body = formatTuningDialogBody(plan)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_tuning_dialog_preview_title))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTuningApplyDialog(report: AdvisorReport) {
        val plan = buildTuningPlan(report)
        if (!plan.isActionable) {
            val msg = plan.blockedReason
                ?: rh.gs(R.string.aimi_tuning_no_changes)
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val body = formatTuningDialogBody(plan)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(rh.gs(R.string.aimi_tuning_dialog_apply_title))
            .setMessage(body)
            .setPositiveButton(rh.gs(R.string.aimi_tuning_apply_btn)) { _, _ ->
                executeTuningApply(plan)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatTuningDialogBody(plan: TuningPlan): String {
        val tierLine = rh.gs(R.string.aimi_tuning_dialog_tier, tierLabel(plan.dominantTier))
        if (plan.blockedReason != null) {
            return "$tierLine\n\n${rh.gs(R.string.aimi_tuning_blocked, plan.blockedReason!!)}"
        }
        if (plan.changes.isEmpty()) {
            return "$tierLine\n\n${rh.gs(R.string.aimi_tuning_no_changes)}"
        }
        return "$tierLine\n\n${TuningContextApplySupport.formatPlanPreview(plan)}"
    }

    private fun executeTuningApply(plan: TuningPlan) {
        lifecycleScope.launch(Dispatchers.IO) {
            val applyResult = TuningContextApplySupport.applyTuningPlan(plan, preferences, historyRepo)
            val exportStatus = if (applyResult.appliedCount > 0) {
                TuningContextApplySupport.tryExportSettings(
                    this@AimiProfileAdvisorActivity,
                    importExportPrefs,
                    exportPasswordDataStore,
                )
            } else {
                TuningExportStatus.SKIPPED_DISABLED
            }
            val resultMessage = buildTuningResultMessage(applyResult.copy(exportStatus = exportStatus))
            withContext(Dispatchers.Main) {
                if (isFinishing) return@withContext
                androidx.appcompat.app.AlertDialog.Builder(this@AimiProfileAdvisorActivity)
                    .setTitle(rh.gs(R.string.aimi_tuning_result_title))
                    .setMessage(resultMessage)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        refreshRecommendationRowsAfterApply()
                    }
                    .show()
            }
        }
    }

    private fun buildTuningResultMessage(result: TuningApplyResult): String {
        val sb = StringBuilder()
        if (result.appliedCount == 0) {
            sb.append(result.plan.blockedReason ?: rh.gs(R.string.aimi_tuning_no_changes))
        } else {
            sb.append(
                rh.gs(
                    R.string.aimi_tuning_result_summary,
                    result.appliedCount,
                    tierLabel(result.plan.dominantTier),
                ),
            ).append("\n\n")
            result.summaryLines.forEach { sb.append("• ").append(it).append('\n') }
            sb.append('\n')
            sb.append(
                when (result.exportStatus) {
                    TuningExportStatus.SUCCESS -> rh.gs(R.string.aimi_tuning_result_export_ok)
                    TuningExportStatus.SKIPPED_DISABLED -> rh.gs(R.string.aimi_tuning_result_export_skipped)
                    TuningExportStatus.SKIPPED_NO_PASSWORD -> rh.gs(R.string.aimi_tuning_result_export_skipped)
                    TuningExportStatus.SKIPPED_PASSWORD_EXPIRED -> rh.gs(R.string.aimi_tuning_result_export_expired)
                    TuningExportStatus.FAILED -> rh.gs(R.string.aimi_tuning_result_export_failed)
                },
            )
        }
        return sb.toString().trimEnd()
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
