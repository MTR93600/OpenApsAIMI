package app.aaps.activities

import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
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
import com.github.mikephil.charting.components.LimitLine
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
    private lateinit var scoringModeTabs: android.widget.RadioGroup
    private var selectedScoringMode: ScoringMode = ScoringMode.BALANCED
    
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
        menu.add(0, MENU_ID_EXPORT_LLM, 0, getString(R.string.comparator_export_llm_summary))
            .setIcon(android.R.drawable.ic_menu_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    private fun loadData() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, getString(R.string.comparator_storage_permission_required), Toast.LENGTH_LONG).show()
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
        setupScoringModeTabs()

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
        val recommendation = parser.generateRecommendation(
            stats = stats,
            safety = safetyMetrics,
            impact = clinicalImpact,
            mode = selectedScoringMode
        )

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
        setupBgChart()
        setupRateChart()
        setupSmbChart()
    }

    private fun setupBgChart() {
        val allBgEntries = mutableListOf<Entry>()
        val lowBgEntries = mutableListOf<Entry>()
        val inRangeBgEntries = mutableListOf<Entry>()
        val highBgEntries = mutableListOf<Entry>()

        displayedEntries.forEachIndexed { index, entry ->
            val x = index.toFloat()
            val y = entry.bg.toFloat()
            val point = Entry(x, y)
            allBgEntries.add(point)
            when {
                entry.bg < 70.0 -> lowBgEntries.add(Entry(x, y))
                entry.bg > 180.0 -> highBgEntries.add(Entry(x, y))
                else -> inRangeBgEntries.add(Entry(x, y))
            }
        }

        val baseDataSet = LineDataSet(allBgEntries, getString(R.string.comparator_dataset_bg_actual)).apply {
            color = Color.parseColor("#90A4AE")
            setCircleColor(Color.parseColor("#90A4AE"))
            lineWidth = 1.6f
            circleRadius = 0.8f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val lowDataSet = LineDataSet(lowBgEntries, getString(R.string.comparator_dataset_bg_low)).apply {
            color = Color.parseColor("#D32F2F")
            setCircleColor(Color.parseColor("#D32F2F"))
            lineWidth = 2.8f
            circleRadius = 2.0f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val inRangeDataSet = LineDataSet(inRangeBgEntries, getString(R.string.comparator_dataset_bg_in_range)).apply {
            color = Color.parseColor("#2E7D32")
            setCircleColor(Color.parseColor("#2E7D32"))
            lineWidth = 2.8f
            circleRadius = 2.0f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val highDataSet = LineDataSet(highBgEntries, getString(R.string.comparator_dataset_bg_high)).apply {
            color = Color.parseColor("#EF6C00")
            setCircleColor(Color.parseColor("#EF6C00"))
            lineWidth = 2.8f
            circleRadius = 2.0f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        binding.bgChart.apply {
            data = LineData(baseDataSet, lowDataSet, inRangeDataSet, highDataSet)
            description.text = getString(R.string.comparator_bg_chart_desc)
            configureLineChart(chart = this)

            axisLeft.removeAllLimitLines()
            addBgClinicalBands(axisLeft)
            axisLeft.axisMinimum = 40f
            axisLeft.axisMaximum = 300f

            invalidate()
        }
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

        val aimiDataSet = LineDataSet(aimiEntries, getString(R.string.comparator_dataset_aimi)).apply {
            color = aimiColor
            setCircleColor(aimiColor)
            lineWidth = 2.2f
            circleRadius = 1.8f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val smbDataSet = LineDataSet(smbEntries, getString(R.string.comparator_dataset_smb)).apply {
            color = smbColor
            setCircleColor(smbColor)
            lineWidth = 2.2f
            circleRadius = 1.8f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        binding.rateChart.apply {
            data = LineData(aimiDataSet, smbDataSet)
            description.text = getString(R.string.comparator_rate_chart_desc)
            configureLineChart(chart = this)
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

        val aimiDataSet = LineDataSet(aimiEntries, getString(R.string.comparator_dataset_aimi_smb)).apply {
            color = aimiColor
            setCircleColor(aimiColor)
            lineWidth = 2.2f
            circleRadius = 1.8f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        val smbDataSet = LineDataSet(smbEntries, getString(R.string.comparator_dataset_smb_smb)).apply {
            color = smbColor
            setCircleColor(smbColor)
            lineWidth = 2.2f
            circleRadius = 1.8f
            circleHoleRadius = 0.9f
            setDrawCircleHole(true)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }

        binding.smbChart.apply {
            data = LineData(aimiDataSet, smbDataSet)
            description.text = getString(R.string.comparator_smb_chart_desc)
            configureLineChart(chart = this)
            invalidate()
        }
    }

    private fun configureLineChart(chart: com.github.mikephil.charting.charts.LineChart) {
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setNoDataText(getString(R.string.comparator_chart_no_data))
        chart.setNoDataTextColor(Color.GRAY)

        chart.legend.apply {
            isEnabled = true
            textSize = 12f
            textColor = Color.DKGRAY
            formSize = 11f
            xEntrySpace = 12f
            yEntrySpace = 4f
            isWordWrapEnabled = true
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.LTGRAY
            textColor = Color.DKGRAY
            textSize = 11f
            granularity = 1f
            labelRotationAngle = -35f
            setAvoidFirstLastClipping(true)
        }

        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.LTGRAY
            textColor = Color.DKGRAY
            textSize = 11f
            axisMinimum = 0f
        }
        chart.axisRight.isEnabled = false

        // Keep first render readable on large windows while allowing zoom out.
        val visibleRange = when {
            displayedEntries.size <= 36 -> displayedEntries.size.toFloat().coerceAtLeast(6f)
            else -> 36f
        }
        chart.setVisibleXRangeMaximum(visibleRange)
        chart.moveViewToX((displayedEntries.size - visibleRange).coerceAtLeast(0f))
    }

    private fun addBgClinicalBands(axis: com.github.mikephil.charting.components.YAxis) {
        fun line(value: Float, label: String, color: Int): LimitLine {
            return LimitLine(value, label).apply {
                lineColor = color
                lineWidth = 1.4f
                textColor = color
                textSize = 10f
                enableDashedLine(10f, 6f, 0f)
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            }
        }

        axis.addLimitLine(line(54f, getString(R.string.comparator_bg_threshold_54), Color.parseColor("#C62828")))
        axis.addLimitLine(line(70f, getString(R.string.comparator_bg_threshold_70), Color.parseColor("#EF6C00")))
        axis.addLimitLine(line(180f, getString(R.string.comparator_bg_threshold_180), Color.parseColor("#2E7D32")))
        axis.addLimitLine(line(250f, getString(R.string.comparator_bg_threshold_250), Color.parseColor("#8E24AA")))
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

        val options = listOf(
            getString(R.string.comparator_window_global),
            getString(R.string.comparator_window_24h),
            getString(R.string.comparator_window_7d)
        )
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

    private fun setupScoringModeTabs() {
        if (this::scoringModeTabs.isInitialized) return

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

        val options = listOf(
            getString(R.string.comparator_scoring_mode_balanced) to ScoringMode.BALANCED,
            getString(R.string.comparator_scoring_mode_post_meal) to ScoringMode.POSTPRANDIAL,
            getString(R.string.comparator_scoring_mode_overnight) to ScoringMode.OVERNIGHT
        )

        options.forEachIndexed { index, (label, mode) ->
            val radioButton = android.widget.RadioButton(this).apply {
                text = label
                id = 100 + index
                tag = mode
                layoutParams = android.widget.RadioGroup.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            if (mode == ScoringMode.BALANCED) radioButton.isChecked = true
            radioGroup.addView(radioButton)
        }

        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val checked = group.findViewById<android.widget.RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            selectedScoringMode = checked.tag as? ScoringMode ?: ScoringMode.BALANCED
            refreshUI()
        }

        // Insert under time-window tabs.
        val insertIndex = if (this::timeWindowTabs.isInitialized) 1 else 0
        binding.contentLayout.addView(radioGroup, insertIndex)
        scoringModeTabs = radioGroup
    }

    private fun updateTimeWindow(index: Int) {
        val now = System.currentTimeMillis()
        displayedEntries = when (index) {
            1 -> parser.getLast24h(allEntries, now)
            2 -> parser.getLast7d(allEntries, now)
            else -> allEntries
        }
        
        if (displayedEntries.isEmpty()) {
            Toast.makeText(this, getString(R.string.comparator_no_data_for_period), Toast.LENGTH_SHORT).show()
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
         val rec = parser.generateRecommendation(
             stats = stats,
             safety = safety,
             impact = impact,
             mode = selectedScoringMode
         )
         
         val periodLabel = when(timeWindowTabs.checkedRadioButtonId) {
             1 -> getString(R.string.comparator_period_last_24h)
             2 -> getString(R.string.comparator_period_last_7d)
             else -> getString(R.string.comparator_period_global_history)
         }
         
         val summary = parser.generateLlmSummary(
             periodLabel, stats, safety, impact, moments, rec, selectedScoringMode
         )
         
         // Copy to clipboard
         val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
         val clip = android.content.ClipData.newPlainText(getString(R.string.comparator_export_llm_summary), summary)
         clipboard.setPrimaryClip(clip)
         
         Toast.makeText(this, getString(R.string.comparator_summary_copied), Toast.LENGTH_LONG).show()
         
         // Also share text intent
         val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, summary)
            type = "text/plain"
         }
         startActivity(Intent.createChooser(sendIntent, getString(R.string.comparator_export_using)))
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
