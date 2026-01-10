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
            binding.infusionAgeText.text = getProp<String>("infusionAgeText") ?: "--"
            binding.pumpBatteryText.text = getProp<String>("pumpBatteryText") ?: "--"
            binding.sensorAgeText.text = getProp<String>("sensorAgeText") ?: "--"

            // ═══════════════════════════════════════════════════════════════
            // 3. Right Column Metrics
            // ═══════════════════════════════════════════════════════════════
            binding.lastSensorValueText.text = getProp<String>("lastSensorValueText") ?: "--"
            binding.activityText.text = getProp<String>("activityPctText") ?: "0%"
            binding.tbrRateText.text = getProp<String>("tbrRateText") ?: "0.00 U/h"
            binding.basalText.text = getProp<String>("basalText") ?: "--"



            // ═══════════════════════════════════════════════════════════════
            // 5. Loop Status
            // ═══════════════════════════════════════════════════════════════
            binding.loopStatus.text = getProp<String>("loopStatusText") ?: "Closed Loop"
            

            
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
