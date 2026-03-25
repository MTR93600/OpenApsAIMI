package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.viewbinding.ViewBinding
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import com.google.android.material.chip.Chip
import app.aaps.plugins.main.databinding.ComponentCircleTopStatusHybridBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import app.aaps.core.ui.dialogs.OKDialog
import java.util.Locale
import java.util.TimeZone


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
    private var circleTopActionListener: CircleTopActionListener? = null

    private val accessibilityManager: AccessibilityManager? by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }

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
            binding.pumpBatteryText.text = getProp<String>("pumpBatteryText") ?: "--"

            // ═══════════════════════════════════════════════════════════════
            // 3. Right Column Metrics
            // ═══════════════════════════════════════════════════════════════
            binding.lastSensorValueText.text = getProp<String>("lastSensorValueText") ?: "--"

            // Adaptive Smoothing Quality badge (informational, phase 1)
            if (state is StatusCardState) {
                val tier = state.adaptiveSmoothingQualityTier
                binding.adaptiveSmoothingQualityBadge.isGone = tier == null
                if (tier != null) {
                    val bgRes = when (tier) {
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.OK ->
                            app.aaps.plugins.main.R.drawable.dashboard_chip_background_quality_ok
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.UNCERTAIN ->
                            app.aaps.plugins.main.R.drawable.dashboard_chip_background_quality_uncertain
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.BAD ->
                            app.aaps.plugins.main.R.drawable.dashboard_chip_background_quality_bad
                    }
                    binding.adaptiveSmoothingQualityBadge.setBackgroundResource(bgRes)

                    val tintRes = when (tier) {
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.OK ->
                            app.aaps.plugins.main.R.color.dashboard_on_surface_muted
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.UNCERTAIN ->
                            app.aaps.plugins.main.R.color.dashboard_metric_attention
                        app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier.BAD ->
                            app.aaps.plugins.main.R.color.dashboard_chip_border_warning
                    }
                    binding.adaptiveSmoothingQualityIcon.setColorFilter(
                        context.getColor(tintRes)
                    )

                    binding.adaptiveSmoothingQualityBadge.contentDescription = state.adaptiveSmoothingQualityBadgeText
                    binding.adaptiveSmoothingQualityBadge.setOnClickListener {
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        if (state.adaptiveSmoothingQualityDialogMessage.isNotBlank()) {
                            OKDialog.show(
                                context,
                                context.getString(app.aaps.plugins.main.R.string.adaptive_smoothing_quality_dialog_title),
                                state.adaptiveSmoothingQualityDialogMessage
                            )
                        }

                        val manager = accessibilityManager
                        if (manager != null && manager.isEnabled && manager.isTouchExplorationEnabled) {
                            announceForAccessibility(state.adaptiveSmoothingQualityBadgeText)
                        }
                    }
                }
            }

            binding.tbrRateText.text = getProp<String>("tbrRateText") ?: "0.00 U/h"
            binding.basalText.text = getProp<String>("basalText") ?: "--"

            // ═══════════════════════════════════════════════════════════════
            // 4. TIR Bar (24H)
            // ═══════════════════════════════════════════════════════════════
            val currentTime = System.currentTimeMillis()
            val startOfDay = currentTime / (1000 * 3600 * 24) * (1000 * 3600 * 24) - TimeZone.getDefault().getOffset(currentTime)
            val endOfDay = startOfDay + (1000 * 3600 * 24)

            val avg = getProp<Double>("avgBgMgdl") ?: Double.NaN
            val a1c = getProp<Double>("a1c") ?: Double.NaN
            if (!avg.isNaN() && !a1c.isNaN()) {
                binding.tirStatsText.text = context.getString(
                    app.aaps.plugins.main.R.string.dashboard_tir_stats_format,
                    avg,
                    a1c
                )
            } else {
                binding.tirStatsText.text = context.getString(app.aaps.plugins.main.R.string.dashboard_tir_stats_placeholder)
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
                    label.text = String.format(Locale.getDefault(), "%.0f%%", value)
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
            binding.loopStatus.text = getProp<String>("loopStatusText")
                ?: context.getString(app.aaps.plugins.main.R.string.closed_loop)
            
            // Steps & HR
            binding.stepsText.text = getProp<String>("stepsText") ?: "--"
            binding.hrText.text = getProp<String>("hrText") ?: "--"
            
            // ═══════════════════════════════════════════════════════════════
            // 5b. AIMI Pulse (real APS reason + facts)
            // ═══════════════════════════════════════════════════════════════
            if (state is StatusCardState) {
                binding.aimiPulseTitle.text = state.aimiPulseTitle
                binding.aimiPulseSummary.text = state.aimiPulseSummary
                val meta = state.aimiPulseMeta
                binding.aimiPulseMeta.text = meta
                binding.aimiPulseMeta.isGone = meta.isBlank()
                val cd = buildString {
                    append(state.aimiPulseTitle)
                    append(". ")
                    append(state.aimiPulseSummary)
                    if (state.aimiPulseMeta.isNotBlank()) {
                        append(". ")
                        append(state.aimiPulseMeta)
                    }
                    append(". ")
                    append(context.getString(app.aaps.plugins.main.R.string.dashboard_cd_aimi_pulse))
                }
                binding.aimiPulseContainer.contentDescription = cd
                if (state.aimiPulseHypoRisk) {
                    binding.aimiPulseContainer.setBackgroundResource(app.aaps.plugins.main.R.drawable.dashboard_chip_background_warning)
                } else {
                    binding.aimiPulseContainer.setBackgroundResource(app.aaps.plugins.main.R.drawable.dashboard_chip_background)
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 6. AIMI Insights
            // ═══════════════════════════════════════════════════════════════
            binding.insightT3c.text = getProp<String>("insightT3c") ?: "🎯 --"
            binding.insightManoeuvre.text = getProp<String>("insightManoeuvre") ?: "🌀 --"
            binding.insightFactor.text = getProp<String>("insightFactor") ?: "⚡ x1.0"
            
            // Adjust container style based on health score (confidence)
            val health = getProp<Double>("aimiHealthScore") ?: 1.0
            if (health < 0.8) {
                binding.aimiInsightsContainer.setBackgroundResource(app.aaps.plugins.main.R.drawable.dashboard_chip_background_warning)
            } else {
                binding.aimiInsightsContainer.setBackgroundResource(app.aaps.plugins.main.R.drawable.dashboard_chip_background)
            }
            

            
        } catch (e: Exception) {
            // Fallback: Log error but don't crash
            e.printStackTrace()
        }
    }

    /**
     * Set action listeners for chips and the AIMI pulse card.
     */
    fun setActionListener(listener: CircleTopActionListener) {
        circleTopActionListener = listener

        fun applyChipStateDescription(chip: Chip) {
            val stateRes = if (chip.isChecked) {
                app.aaps.plugins.main.R.string.dashboard_chip_state_selected
            } else {
                app.aaps.plugins.main.R.string.dashboard_chip_state_not_selected
            }
            ViewCompat.setStateDescription(chip, context.getString(stateRes))
        }

        fun configureAccessibility(chip: Chip) {
            chip.setOnCheckedChangeListener { buttonView, _ ->
                applyChipStateDescription(buttonView as Chip)
            }
            applyChipStateDescription(chip)
        }

        fun announceIfAccessibilityEnabled(messageRes: Int) {
            val manager = accessibilityManager
            if (manager != null && manager.isEnabled && manager.isTouchExplorationEnabled) {
                announceForAccessibility(context.getString(messageRes))
            }
        }

        fun withHaptic(action: () -> Unit): View.OnClickListener = View.OnClickListener {
            // Light, immediate tactile acknowledgement for dashboard chip actions.
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        configureAccessibility(binding.chipAimiAdvisor)
        configureAccessibility(binding.chipAdjust)
        configureAccessibility(binding.chipAimiPref)
        configureAccessibility(binding.chipStat)

        binding.aimiPulseContainer.setOnClickListener(withHaptic {
            circleTopActionListener?.onAimiPulseClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_aimi_pulse_details)
        })

        binding.chipAimiAdvisor.setOnClickListener(withHaptic {
            listener.onAimiAdvisorClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_advisor_opened)
        })
        binding.chipAdjust.setOnClickListener(withHaptic {
            listener.onAdjustClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_adjust_opened)
        })
        binding.chipAimiPref.setOnClickListener(withHaptic {
            listener.onAimiPreferencesClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_meal_mode_opened)
        })
        binding.chipStat.setOnClickListener(withHaptic {
            listener.onStatsClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_context_opened)
        })
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
 * Listener for dashboard chips and the AIMI pulse card.
 */
interface CircleTopActionListener {
    fun onAimiAdvisorClicked()
    fun onAdjustClicked()
    fun onAimiPreferencesClicked()
    fun onStatsClicked()
    fun onAimiPulseClicked()
}
