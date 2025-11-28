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
    private var entries: List<ComparisonEntry> = emptyList()

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

        entries = parser.parse(csvFile)
        
        if (entries.isEmpty()) {
            binding.noDataText.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE
            return
        }

        binding.noDataText.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE

        displayStats()
        displayAnalytics()
        setupCharts()
    }

    private fun displayStats() {
        val stats = parser.calculateStats(entries)
        
        binding.totalEntriesValue.text = stats.totalEntries.toString()
        binding.avgRateDiffValue.text = String.format(Locale.US, "%.2f U/h", stats.avgRateDiff)
        binding.avgSmbDiffValue.text = String.format(Locale.US, "%.2f U", stats.avgSmbDiff)
        binding.agreementRateValue.text = String.format(Locale.US, "%.1f%%", stats.agreementRate)
        binding.aimiWinRateValue.text = String.format(Locale.US, "%.1f%%", stats.aimiWinRate)
        binding.smbWinRateValue.text = String.format(Locale.US, "%.1f%%", stats.smbWinRate)
    }

    private fun displayAnalytics() {
        val stats = parser.calculateStats(entries)
        val safetyMetrics = parser.calculateSafetyMetrics(entries)
        val clinicalImpact = parser.calculateClinicalImpact(entries)
        val criticalMoments = parser.findCriticalMoments(entries)
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
        
        moments.forEach { moment ->
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
                
                text = "$entryText\n$divergenceText"
                textSize = 13f
            }
            binding.criticalMomentsContainer.addView(momentView)
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

        entries.forEachIndexed { index, entry ->
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

        entries.forEachIndexed { index, entry ->
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
}
