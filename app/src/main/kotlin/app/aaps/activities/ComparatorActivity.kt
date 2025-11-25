package app.aaps.activities

import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.R
import app.aaps.databinding.ActivityComparatorBinding
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import app.aaps.plugins.aps.openAPSAIMI.comparison.ComparisonCsvParser
import app.aaps.plugins.aps.openAPSAIMI.comparison.ComparisonEntry
import app.aaps.plugins.aps.openAPSAIMI.comparison.ComparisonStats
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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

        val aimiDataSet = LineDataSet(aimiEntries, "AIMI").apply {
            color = getColor(app.aaps.core.ui.R.color.aimi_color)
            setCircleColor(getColor(app.aaps.core.ui.R.color.aimi_color))
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        val smbDataSet = LineDataSet(smbEntries, "SMB").apply {
            color = getColor(app.aaps.core.ui.R.color.smb_color)
            setCircleColor(getColor(app.aaps.core.ui.R.color.smb_color))
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

        val aimiDataSet = LineDataSet(aimiEntries, "AIMI SMB").apply {
            color = getColor(app.aaps.core.ui.R.color.aimi_color)
            setCircleColor(getColor(app.aaps.core.ui.R.color.aimi_color))
            lineWidth = 2f
            circleRadius = 1f
            setDrawValues(false)
        }

        val smbDataSet = LineDataSet(smbEntries, "SMB SMB").apply {
            color = getColor(app.aaps.core.ui.R.color.smb_color)
            setCircleColor(getColor(app.aaps.core.ui.R.color.smb_color))
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
