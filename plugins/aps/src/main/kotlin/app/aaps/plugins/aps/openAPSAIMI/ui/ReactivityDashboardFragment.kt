package app.aaps.plugins.aps.openAPSAIMI.ui

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.FragmentReactivityDashboardBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Dashboard visuel pour UnifiedReactivityLearner
 * Affiche l'évolution du globalFactor et des métriques TIR/CV%/Hypo
 */
class ReactivityDashboardFragment @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) : Fragment() {

    private var _binding: FragmentReactivityDashboardBinding? = null
    private val binding get() = _binding!!
    
    private val csvFile by lazy {
        File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/aimi_reactivity_analysis.csv")
    }
    
    data class ReactivityAnalysis(
        val timestamp: Long,
        val date: String,
        val tir70_180: Double,
        val tir70_140: Double,
        val tir140_180: Double,
        val tir180_250: Double,
        val tir_above_250: Double,
        val hypo_count: Int,
        val cv_percent: Double,
        val crossing_count: Int,
        val mean_bg: Double,
        val globalFactor: Double,
        val reason: String
    )
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReactivityDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupCharts()
        loadData()
    }
    
    private fun setupCharts() {
        // Configuration chart globalFactor
        setupChart(binding.chartGlobalFactor, "Global Factor", 0.5f, 1.6f)
        
        // Configuration chart TIR
        setupChart(binding.chartTir, "TIR (%)", 0f, 100f)
        
        // Configuration chart CV%
        setupChart(binding.chartCv, "CV (%)", 0f, 50f)
    }
    
    private fun setupChart(chart: LineChart, label: String, minY: Float, maxY: Float) {
        chart.apply {
            description.text = label
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                valueFormatter = object : ValueFormatter() {
                    private val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                    override fun getFormattedValue(value: Float): String {
                        return sdf.format(Date(value.toLong()))
                    }
                }
            }
            
            axisLeft.apply {
                axisMinimum = minY
                axisMaximum = maxY
                setDrawGridLines(true)
            }
            
            axisRight.isEnabled = false
            legend.isEnabled = true
        }
    }
    
    private fun loadData() {
        lifecycleScope.launch {
            try {
                val analyses = withContext(Dispatchers.IO) {
                    parseCSV()
                }
                
                if (analyses.isEmpty()) {
                    binding.tvNoData.visibility = View.VISIBLE
                    return@launch
                }
                
                binding.tvNoData.visibility = View.GONE
                
                // Update statistics
                val latest = analyses.last()
                binding.tvCurrentFactor.text = "%.3f".format(latest.globalFactor)
                binding.tvTir.text = "%.1f%%".format(latest.tir70_180)
                binding.tvCv.text = "%.1f%%".format(latest.cv_percent)
                binding.tvHypoCount.text = "${analyses.sumOf { it.hypo_count }} hypos (7j)"
                binding.tvLastReason.text = latest.reason
                
                // Update charts
                updateGlobalFactorChart(analyses)
                updateTirChart(analyses)
                updateCvChart(analyses)
                
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "ReactivityDashboard: Error loading data", e)
                binding.tvNoData.visibility = View.VISIBLE
                binding.tvNoData.text = "Error: ${e.message}"
            }
        }
    }
    
    private fun parseCSV(): List<ReactivityAnalysis> {
        if (!csvFile.exists()) return emptyList()
        
        return csvFile.readLines()
            .drop(1) // Skip header
            .mapNotNull { line ->
                try {
                    val parts = line.split(",")
                    if (parts.size < 13) return@mapNotNull null
                    
                    ReactivityAnalysis(
                        timestamp = parts[0].toLong(),
                        date = parts[1],
                        tir70_180 = parts[2].toDouble(),
                        tir70_140 = parts[3].toDouble(),
                        tir140_180 = parts[4].toDouble(),
                        tir180_250 = parts[5].toDouble(),
                        tir_above_250 = parts[6].toDouble(),
                        hypo_count = parts[7].toInt(),
                        cv_percent = parts[8].toDouble(),
                        crossing_count = parts[9].toInt(),
                        mean_bg = parts[10].toDouble(),
                        globalFactor = parts[11].toDouble(),
                        reason = parts.drop(12).joinToString(",").trim('"')
                    )
                } catch (e: Exception) {
                    aapsLogger.error(LTag.APS, "ReactivityDashboard: Error parsing line: $line", e)
                    null
                }
            }
    }
    
    private fun updateGlobalFactorChart(analyses: List<ReactivityAnalysis>) {
        val entries = analyses.map { Entry(it.timestamp.toFloat(), it.globalFactor.toFloat()) }
        
        val dataSet = LineDataSet(entries, "Global Factor").apply {
            color = resources.getColor(R.color.primary, null)
            setCircleColor(resources.getColor(R.color.primary, null))
            lineWidth = 2f
            circleRadius = 3f
            setDrawValues(false)
        }
        
        binding.chartGlobalFactor.data = LineData(dataSet)
        binding.chartGlobalFactor.invalidate()
    }
    
    private fun updateTirChart(analyses: List<ReactivityAnalysis>) {
        val entries70_140 = analyses.map { Entry(it.timestamp.toFloat(), it.tir70_140.toFloat()) }
        val entries140_180 = analyses.map { Entry(it.timestamp.toFloat(), it.tir140_180.toFloat()) }
        val entriesAbove180 = analyses.map { Entry(it.timestamp.toFloat(), it.tir_above_250.toFloat()) }
        
        val dataSet70_140 = LineDataSet(entries70_140, "TIR 70-140").apply {
            color = resources.getColor(android.R.color.holo_green_dark, null)
            setDrawValues(false)
        }
        
        val dataSet140_180 = LineDataSet(entries140_180, "TIR 140-180").apply {
            color = resources.getColor(android.R.color.holo_orange_light, null)
            setDrawValues(false)
        }
        
        val dataSetAbove180 = LineDataSet(entriesAbove180, "Above 250").apply {
            color = resources.getColor(android.R.color.holo_red_dark, null)
            setDrawValues(false)
        }
        
        binding.chartTir.data = LineData(dataSet70_140, dataSet140_180, dataSetAbove180)
        binding.chartTir.invalidate()
    }
    
    private fun updateCvChart(analyses: List<ReactivityAnalysis>) {
        val entries = analyses.map { Entry(it.timestamp.toFloat(), it.cv_percent.toFloat()) }
        
        val dataSet = LineDataSet(entries, "CV%").apply {
            color = resources.getColor(R.color.tempTargetConfirmation, null)
            lineWidth = 2f
            setDrawValues(false)
        }
        
        binding.chartCv.data = LineData(dataSet)
        binding.chartCv.invalidate()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
