package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import app.aaps.plugins.main.databinding.ComponentCircleTopStatusHybridBinding

/**
 * CircleTopDashboardView - Modern Circle-Top Hybrid Dashboard
 * 
 * ✨ Features:
 * - GlucoseRingView with dynamic nose pointer
 * - Context & Auditor badges (repositioned top-left/right)
 * - 2 columns of detailed metrics (8 infos)
 * - 4 action chips (Advisor, Adjust, Prefs, Stats)
 * - Trend arrow + delta display
 * - Loop status indicator
 * 
 * 🎯 Design: Hybrid of feature/circle-top + existing AIMI badges
 * 
 * 🔧 Technical: Uses reflection to bypass Kotlin cache issues
 */
class CircleTopDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding = ComponentCircleTopStatusHybridBinding.inflate(
        LayoutInflater.from(context),
        this
    )

    /**
     * Update all dashboard components with fresh state data
     * Uses reflection to access properties (bypasses Kotlin cache)
     */
    fun updateWithState(state: Any) {
        try {
            val stateClass = state::class.java
            
            // Helper function to safely get property value
            fun <T> getProp(name: String): T? {
                return try {
                    val getter = stateClass.getMethod("get${name.capitalize()}")
                    @Suppress("UNCHECKED_CAST")
                    getter.invoke(state) as? T
                } catch (e: Exception) {
                    null
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // 1. GlucoseRingView (Center Circle)
            // ═══════════════════════════════════════════════════════════════
            getProp<Int>("glucoseMgdl")?.let { bgMgdl ->
                binding.glucoseRing.update(
                    bgMgdl = bgMgdl,
                    mainText = getProp<String>("glucoseText") ?: "--",
                    subLeftText = getProp<String>("timeAgo") ?: "",
                    subRightText = getProp<String>("deltaText") ?: "",
                    noseAngleDeg = getProp<Float>("noseAngleDeg"),
                    overrideColor = getProp<Int>("glucoseColor")
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // 2. Left Column Metrics
            // ═══════════════════════════════════════════════════════════════
            binding.reservoirChip.text = getProp<String>("reservoirText") ?: "--"
            binding.cobText.text = getProp<String>("cobText") ?: "0g"
            binding.cvText.text = getProp<String>("cvText") ?: "CV --%"
            binding.activityText.text = getProp<String>("activityPctText") ?: "0%"

            // ═══════════════════════════════════════════════════════════════
            // 3. Right Column Metrics
            // ═══════════════════════════════════════════════════════════════
            binding.lastSensorValueText.text = getProp<String>("lastSensorValueText") ?: "--"
            binding.tbrRateText.text = getProp<String>("tbrRateText") ?: "0.00 U/h"
            binding.basalText.text = getProp<String>("basalText") ?: "--"

            // ═══════════════════════════════════════════════════════════════
            // 4. TIR Bar (24H)
            // ═══════════════════════════════════════════════════════════════
            val avg = getProp<Double>("avgBgMgdl")
            val a1c = getProp<Double>("a1c")
            if (avg != null && a1c != null) {
                binding.tirStatsText.text = String.format("Avg %.0f • A1C %.1f%%", avg, a1c)
            } else {
                binding.tirStatsText.text = "Avg -- • A1C --"
            }

            val vl = getProp<Double>("tirVeryLow") ?: 0.0
            val l = getProp<Double>("tirLow") ?: 0.0
            val tr = getProp<Double>("tirTarget") ?: 0.0
            val h = getProp<Double>("tirHigh") ?: 0.0
            val vh = getProp<Double>("tirVeryHigh") ?: 0.0

            fun updateBar(view: View, label: android.widget.TextView, value: Double) {
                val params = view.layoutParams as android.widget.LinearLayout.LayoutParams
                // ensure at least a small sliver is shown so view doesn't collapse if value=0, 
                // but if we want to hide 0, we can use weight 0
                params.weight = Math.max(0.00001f, (value / 100.0).toFloat())
                view.layoutParams = params
                
                // only show text label if segment is large enough to display it
                if (value >= 5.0) {
                    label.text = String.format("%.0f%%", value)
                } else {
                    label.text = ""
                }
            }

            updateBar(binding.tirVeryLowBar, binding.tirVeryLowLabel, vl)
            updateBar(binding.tirLowBar, binding.tirLowLabel, l)
            updateBar(binding.tirInRangeBar, binding.tirInRangeLabel, tr)
            updateBar(binding.tirHighBar, binding.tirHighLabel, h)
            updateBar(binding.tirVeryHighBar, binding.tirVeryHighLabel, vh)

            // ═══════════════════════════════════════════════════════════════
            // 5. Loop Status & New Metrics (Steps/HR)
            // ═══════════════════════════════════════════════════════════════
            binding.loopStatus.text = getProp<String>("loopStatusText") ?: "Closed Loop"
            
            // Steps & HR
            binding.stepsText.text = getProp<String>("stepsText") ?: "--"
            binding.hrText.text = getProp<String>("hrText") ?: "--"
            

            
        } catch (e: Exception) {
            // Fallback: Log error but don't crash
            e.printStackTrace()
        }
    }

    /**
     * Set action listeners for the 4 chips
     */
    fun setActionListener(listener: CircleTopActionListener) {
        binding.chipAimiAdvisor.setOnClickListener { listener.onAimiAdvisorClicked() }
        binding.chipAdjust.setOnClickListener { listener.onAdjustClicked() }
        binding.chipAimiPref.setOnClickListener { listener.onAimiPreferencesClicked() }
        binding.chipStat.setOnClickListener { listener.onStatsClicked() }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Accessors for DashboardFragment integration
    // ═══════════════════════════════════════════════════════════════
    
    /** Get container for Auditor badge (will be populated by DashboardFragment) */
    fun getAuditorContainer(): FrameLayout = binding.aimiAuditorIndicatorContainer
    
    /** Get AIMI Context indicator (visibility controlled by DashboardFragment) */
    fun getContextIndicator(): View = binding.aimiContextIndicator
    
    /** Get Loop indicator (icon updated by DashboardFragment) */
    fun getLoopIndicator(): View = binding.loopIndicator
    

}

/**
 * Listener interface for the 4 action chips
 */
interface CircleTopActionListener {
    fun onAimiAdvisorClicked()
    fun onAdjustClicked()
    fun onAimiPreferencesClicked()
    fun onStatsClicked()
}
