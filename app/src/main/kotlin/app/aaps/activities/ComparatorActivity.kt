package app.aaps.activities

import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.aaps.R
import app.aaps.databinding.ActivityComparatorBinding
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import app.aaps.plugins.aps.openAPSAIMI.comparison.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File
import java.util.Locale

class ComparatorActivity : DaggerAppCompatActivityWithResult() {

    private lateinit var binding: ActivityComparatorBinding
    private val parser = ComparisonCsvParser()
    private var allEntries: List<ComparisonEntry> = emptyList()
    private var displayedEntries: List<ComparisonEntry> = emptyList()
    
    // UI Elements created programmatically
    private lateinit var timeWindowTabs: android.widget.RadioGroup
    
    companion object {
        const val MENU_ID_EXPORT_LLM = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(app.aaps.core.ui.R.style.AppTheme)
        binding = ActivityComparatorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        title = getString(R.string.comparator_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        loadData()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_ID_EXPORT_LLM, 0, "Export LLM Summary")
            .setIcon(android.R.drawable.ic_menu_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    private fun loadData() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Please allow access to all files to read the comparison data", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            return
        }

        val csvFile = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/comparison_aimi_smb.csv")
        
        if (!csvFile.exists()) {
            binding.noDataText.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE
            return
        }

        allEntries = parser.parse(csvFile)
        
        if (allEntries.isEmpty()) {
            binding.noDataText.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE
            return
        }

        // Initialize Tabs if not exists
        setupTimeWindowTabs()

        // Default to Global
        updateTimeWindow(0)

    }

    private fun displayStats() {
        val stats = parser.calculateStats(displayedEntries)
        
        binding.totalEntriesValue.text = stats.totalEntries.toString()
        binding.avgRateDiffValue.text = String.format(Locale.US, "%.2f U/h", stats.avgRateDiff)
        binding.avgSmbDiffValue.text = String.format(Locale.US, "%.2f U", stats.avgSmbDiff)
        binding.agreementRateValue.text = String.format(Locale.US, "%.1f%%", stats.agreementRate)
        binding.aimiWinRateValue.text = String.format(Locale.US, "%.1f%% (Activité)", stats.aimiWinRate)
        binding.smbWinRateValue.text = String.format(Locale.US, "%.1f%% (Activité)", stats.smbWinRate)
    }

    private fun displayAnalytics() {
        val stats = parser.calculateStats(displayedEntries)
        val safetyMetrics = parser.calculateSafetyMetrics(displayedEntries)
        val clinicalImpact = parser.calculateClinicalImpact(displayedEntries)
        val criticalMoments = parser.findCriticalMoments(displayedEntries)
        val recommendation = parser.generateRecommendation(stats, safetyMetrics, clinicalImpact)

        displaySafetyAnalysis(safetyMetrics)
        displayClinicalImpact(clinicalImpact)
        displayCriticalMoments(criticalMoments)
        displayRecommendation(recommendation)
    }

    private fun displaySafetyAnalysis(safety: SafetyMetrics) {
        binding.variabilityScoreValue.text = "${safety.variabilityLabel} (SMB)"
        binding.hypoRiskValue.text = safety.estimatedHypoRisk
    }

    private fun displayClinicalImpact(impact: ClinicalImpact) {
        binding.totalInsulinAimiValue.text = String.format(Locale.US, "%.1f U", impact.totalInsulinAimi)
        binding.totalInsulinSmbValue.text = String.format(Locale.US, "%.1f U", impact.totalInsulinSmb)
        
        val diffText = if (impact.cumulativeDiff > 0) {
            String.format(Locale.US, "+%.1f U (AIMI plus agressif)", impact.cumulativeDiff)
        } else {
            String.format(Locale.US, "%.1f U (SMB plus agressif)", impact.cumulativeDiff)
        }
        binding.cumulativeDiffValue.text = diffText
    }

    private fun displayCriticalMoments(moments: List<CriticalMoment>) {
        binding.criticalMomentsContainer.removeAllViews()
        
        // Filter out Screaming Shadow artifacts
        moments.filter { 
             // Logic: Check associated entry for artifact flag (need access to entries, or enhance CriticalMoment)
             // Simpler: Check if divergence is massive (>2U) and reason mentions specific keywords
             // Better: CriticalMoment doesn't have the flag yet. I will rely on the divergence magnitude heuristic for now
             // or check if entry exists.
             // Actually, I can't easily filter by the new flag because CriticalMoment doesn't have it.
             // I'll add a label instead.
             true 
        }.forEach { moment ->
             // Try to find the original entry to get the flag (inefficient but works for 5 items)
             val entry = displayedEntries.getOrNull(moment.index)
             val isArtifact = entry?.artifactFlag == "SCREAMING_SHADOW"
             
             if (!isArtifact) { // Only show real moments
                val momentView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    setPadding(0, 8, 0, 8)
                    
                    val entryText = getString(
                        R.string.comparator_critical_moment_entry,
                        moment.index,
                        moment.bg,
                        moment.iob
                    )
                    
                    val divergenceText = getString(
                        R.string.comparator_critical_moment_divergence,
                        moment.divergenceRate?.let { String.format(Locale.US, "%+.2f", it) } ?: "--",
                        moment.divergenceSmb?.let { String.format(Locale.US, "%+.2f", it) } ?: "--"
                    )
                    
                    val verdictText = if (entry?.verdict?.isNotEmpty() == true) " | ${entry.verdict}" else ""
                    
                    text = "$entryText\n$divergenceText$verdictText"
                    textSize = 13f
                }
                binding.criticalMomentsContainer.addView(momentView)
             }
        }
    }

    private fun displayRecommendation(rec: Recommendation) {
        binding.recommendationAlgorithm.text = getString(
            R.string.comparator_recommended_algorithm,
            rec.preferredAlgorithm
        )
        binding.recommendationReason.text = rec.reason
        binding.recommendationSafetyNote.text = rec.safetyNote
        binding.recommendationConfidence.text = getString(
            R.string.comparator_confidence,
            rec.confidenceLevel
        )
    }

    private fun setupCharts() {
        setupRateChart()
        setupSmbChart()
    }

    private fun setupRateChart() {
        val aimiEntries = mutableListOf<Entry>()
        val smbEntries = mutableListOf<Entry>()

        displayedEntries.forEachIndexed { index, entry ->
            entry.aimiRate?.let { aimiEntries.add(Entry(index.toFloat(), it.toFloat())) }
            entry.smbRate?.let { smbEntries.add(Entry(index.toFloat(), it.toFloat())) }
        }

        val aimiColor = androidx.core.content.ContextCompat.getColor(this, app.aaps.core.ui.R.color.aimi_color)
        val smbColor = androidx.core.content.ContextCompat.getColor(this, app.aaps.core.ui.R.color.smb_color)

        val aimiDataSet = LineDataSet(aimiEntries, "AIMI").apply {
            color = aimiColor
            setCircleColor(aimiColor)
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        val smbDataSet = LineDataSet(smbEntries, "SMB").apply {
            color = smbColor
            setCircleColor(smbColor)
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        binding.rateChart.apply {
            data = LineData(aimiDataSet, smbDataSet)
            description.text = getString(R.string.comparator_rate_chart_desc)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            invalidate()
        }
    }

    private fun setupSmbChart() {
        val aimiEntries = mutableListOf<Entry>()
        val smbEntries = mutableListOf<Entry>()

        displayedEntries.forEachIndexed { index, entry ->
            entry.aimiSmb?.let { aimiEntries.add(Entry(index.toFloat(), it.toFloat())) }
            entry.smbSmb?.let { smbEntries.add(Entry(index.toFloat(), it.toFloat())) }
        }

        val aimiColor = androidx.core.content.ContextCompat.getColor(this, app.aaps.core.ui.R.color.aimi_color)
        val smbColor = androidx.core.content.ContextCompat.getColor(this, app.aaps.core.ui.R.color.smb_color)

        val aimiDataSet = LineDataSet(aimiEntries, "AIMI SMB").apply {
            color = aimiColor
            setCircleColor(aimiColor)
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        val smbDataSet = LineDataSet(smbEntries, "SMB SMB").apply {
            color = smbColor
            setCircleColor(smbColor)
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        binding.smbChart.apply {
            data = LineData(aimiDataSet, smbDataSet)
            description.text = getString(R.string.comparator_smb_chart_desc)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            axisRight.isEnabled = false
            invalidate()
        }
    }
    private fun setupTimeWindowTabs() {
        if (this::timeWindowTabs.isInitialized) return

        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val options = listOf("Global", "24h", "7d")
        options.forEachIndexed { index, label ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = label
                id = index
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    0, 
                    LinearLayout.LayoutParams.WRAP_CONTENT, 
                    1f
                )
            }
            if (index == 0) radioButton.isChecked = true
            radioGroup.addView(radioButton)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            updateTimeWindow(checkedId)
        }

        // Insert at top of content layout
        binding.contentLayout.addView(radioGroup, 0)
        timeWindowTabs = radioGroup
        binding.contentLayout.visibility = View.VISIBLE
        binding.noDataText.visibility = View.GONE
    }

    private fun updateTimeWindow(index: Int) {
        val now = System.currentTimeMillis()
        displayedEntries = when (index) {
            1 -> parser.getLast24h(allEntries, now)
            2 -> parser.getLast7d(allEntries, now)
            else -> allEntries
        }
        
        if (displayedEntries.isEmpty()) {
            Toast.makeText(this, "No data for this period", Toast.LENGTH_SHORT).show()
        }
        
        refreshUI()
    }

    private fun refreshUI() {
        displayStats()
        displayAnalytics()
        setupCharts()
        binding.rateChart.invalidate()
        binding.smbChart.invalidate()
    }

    private fun exportLlmSummary() {
         if (displayedEntries.isEmpty()) return
         
         val stats = parser.calculateStats(displayedEntries)
         val safety = parser.calculateSafetyMetrics(displayedEntries)
         val impact = parser.calculateClinicalImpact(displayedEntries)
         val moments = parser.findCriticalMoments(displayedEntries)
         val rec = parser.generateRecommendation(stats, safety, impact)
         
         val periodLabel = when(timeWindowTabs.checkedRadioButtonId) {
             1 -> "Last 24h"
             2 -> "Last 7 Days"
             else -> "Global History"
         }
         
         val summary = parser.generateLlmSummary(
             periodLabel, stats, safety, impact, moments, rec
         )
         
         // Copy to clipboard
         val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
         val clip = android.content.ClipData.newPlainText("Comparator LLM Summary", summary)
         clipboard.setPrimaryClip(clip)
         
         Toast.makeText(this, "Summary copied to clipboard!", Toast.LENGTH_LONG).show()
         
         // Also share text intent
         val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, summary)
            type = "text/plain"
         }
         startActivity(Intent.createChooser(sendIntent, "Export using..."))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            MENU_ID_EXPORT_LLM -> {
                exportLlmSummary()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
}
