package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import app.aaps.core.keys.BooleanKey
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.dashboard.GlucoseHeroRing
import app.aaps.core.ui.compose.dashboard.GlucoseHeroUiState
import app.aaps.core.ui.compose.icons.IcCarbs
import app.aaps.core.ui.compose.icons.IcPluginObjectives
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.views.GlucoseRingColorComputer
import app.aaps.plugins.main.databinding.ComponentCircleTopStatusHybridBinding
import app.aaps.plugins.main.general.dashboard.compose.DashboardMetricIcon
import app.aaps.plugins.main.general.dashboard.compose.DashboardQuickActionsBar
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import java.util.Locale
import java.util.TimeZone
/**
 * CircleTopDashboardView - Modern Circle-Top Hybrid Dashboard
 * 
 * ✨ Features:
 * - Compose glucose hero ([GlucoseHeroRing]) under [AapsTheme] (ring, nose, telemetry arc, typography)
 * - Context & Auditor badges (repositioned top-left/right)
 * - 2 columns of detailed metrics (8 infos)
 * - 4 quick actions (Advisor, Adjust, Meal, Context) en Compose
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

    private val heroState = mutableStateOf(
        GlucoseHeroUiState(
            ringColorArgb = android.graphics.Color.GRAY,
            centerTextColorArgb = android.graphics.Color.WHITE,
            subTextColorArgb = android.graphics.Color.LTGRAY,
            surfaceColorArgb = android.graphics.Color.TRANSPARENT,
        )
    )

    private var composeHeroAttached: Boolean = false

    private val actionListenerState = mutableStateOf<CircleTopActionListener?>(null)

    /** Tint for adaptive smoothing badge wave icon (updated from [updateWithState]). */
    private val adaptiveSmoothingIconTintState = mutableStateOf(Color.Gray)

    private var dashboardPreferences: Preferences? = null

    private var suppressDashboardMetricsModeCallback: Boolean = false
    private var metricsModeToggleListenerInstalled: Boolean = false

    /**
     * Wire les [ComposeView] du hero glucose et de la barre d’actions + [AapsTheme] ([LocalPreferences]).
     * À appeler une fois depuis [androidx.fragment.app.Fragment.onViewCreated], avant [setActionListener].
     */
    fun attachComposeHeroDependencies(preferences: Preferences) {
        dashboardPreferences = preferences
        if (composeHeroAttached) return
        composeHeroAttached = true
        val heroCompose: ComposeView = binding.glucoseHeroCompose
        // Detach-driven disposal: correct when this view is inside Compose AndroidView (activity
        // lifecycle stays alive while the view tree is torn down and rebuilt).
        heroCompose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        heroCompose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    val hero by heroState
                    GlucoseHeroRing(state = hero, modifier = Modifier.fillMaxSize())
                }
            }
        }

        val quickActionsCompose: ComposeView = binding.dashboardQuickActionsCompose
        quickActionsCompose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        quickActionsCompose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    val listener by actionListenerState
                    DashboardQuickActionsBar(
                        onAdvisor = { listener?.onAimiAdvisorClicked() },
                        onAdjust = { listener?.onAdjustClicked() },
                        onMeal = { listener?.onAimiPreferencesClicked() },
                        onContext = { listener?.onStatsClicked() },
                    )
                }
            }
        }
        attachDashboardMetricComposeIcons(preferences)
        installDashboardMetricsModeToggle()
    }

    private fun attachDashboardMetricComposeIcons(preferences: Preferences) {
        fun dashboardColor(resId: Int): Color = Color(ContextCompat.getColor(context, resId))

        fun bindMetricIcon(
            view: ComposeView,
            tint: Color,
            sizeDp: Dp = 18.dp,
            icon: ImageVector,
        ) {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.setContent {
                CompositionLocalProvider(LocalPreferences provides preferences) {
                    AapsTheme {
                        DashboardMetricIcon(imageVector = icon, tint = tint, size = sizeDp)
                    }
                }
            }
        }

        val muted = dashboardColor(app.aaps.plugins.main.R.color.dashboard_on_surface_muted)
        val onSurface = dashboardColor(app.aaps.plugins.main.R.color.dashboard_on_surface)
        val attention = dashboardColor(app.aaps.plugins.main.R.color.dashboard_metric_attention)
        val info = dashboardColor(app.aaps.plugins.main.R.color.dashboard_metric_info)
        val warning = dashboardColor(app.aaps.plugins.main.R.color.dashboard_metric_warning)

        binding.aimiContextIndicator.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        binding.aimiContextIndicator.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Icon(
                        imageVector = IcPluginObjectives,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        bindMetricIcon(binding.metricIconSteps, muted, 18.dp, Icons.Outlined.DirectionsWalk)
        bindMetricIcon(binding.metricIconCob, attention, 18.dp, IcCarbs)
        bindMetricIcon(binding.metricIconCv, onSurface, 18.dp, Icons.Outlined.Waves)
        bindMetricIcon(binding.metricIconHr, muted, 18.dp, Icons.Outlined.Favorite)
        bindMetricIcon(binding.metricIconLastReading, info, 18.dp, Icons.Outlined.WaterDrop)
        bindMetricIcon(binding.metricIconTbr, warning, 18.dp, Icons.Outlined.ErrorOutline)
        bindMetricIcon(binding.metricIconBasal, onSurface, 18.dp, Icons.Outlined.Tune)
        bindMetricIcon(binding.metricIconActivity, onSurface, 18.dp, Icons.Outlined.Tune)

        adaptiveSmoothingIconTintState.value = muted
        binding.metricIconAdaptiveSmoothing.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        binding.metricIconAdaptiveSmoothing.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    val tint by adaptiveSmoothingIconTintState
                    Icon(
                        imageVector = Icons.Outlined.Waves,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = tint,
                    )
                }
            }
        }
    }

    /**
     * Reconcile chip + visibilité avec les préférences (ex. changement depuis l’écran Paramètres).
     */
    fun syncDashboardMetricsModeFromPreferences() {
        val prefs = dashboardPreferences ?: return
        val extended = prefs.get(BooleanKey.OverviewDashboardExtendedMetrics)
        suppressDashboardMetricsModeCallback = true
        binding.btnDashboardMetricsSimple.isChecked = !extended
        binding.btnDashboardMetricsExtended.isChecked = extended
        suppressDashboardMetricsModeCallback = false
        applyDashboardMetricsMode(extended)
    }

    private fun installDashboardMetricsModeToggle() {
        val prefs = dashboardPreferences ?: return
        if (!metricsModeToggleListenerInstalled) {
            metricsModeToggleListenerInstalled = true
            binding.btnDashboardMetricsSimple.setOnClickListener {
                if (suppressDashboardMetricsModeCallback) return@setOnClickListener
                if (!prefs.get(BooleanKey.OverviewDashboardExtendedMetrics)) {
                    suppressDashboardMetricsModeCallback = true
                    binding.btnDashboardMetricsSimple.isChecked = true
                    binding.btnDashboardMetricsExtended.isChecked = false
                    suppressDashboardMetricsModeCallback = false
                    return@setOnClickListener
                }
                applyDashboardMetricsModeFromUserSelection(prefs, extended = false)
            }
            binding.btnDashboardMetricsExtended.setOnClickListener {
                if (suppressDashboardMetricsModeCallback) return@setOnClickListener
                if (prefs.get(BooleanKey.OverviewDashboardExtendedMetrics)) {
                    suppressDashboardMetricsModeCallback = true
                    binding.btnDashboardMetricsSimple.isChecked = false
                    binding.btnDashboardMetricsExtended.isChecked = true
                    suppressDashboardMetricsModeCallback = false
                    return@setOnClickListener
                }
                applyDashboardMetricsModeFromUserSelection(prefs, extended = true)
            }
        }
        syncDashboardMetricsModeFromPreferences()
    }

    private fun applyDashboardMetricsModeFromUserSelection(
        prefs: Preferences,
        extended: Boolean,
    ) {
        suppressDashboardMetricsModeCallback = true
        binding.btnDashboardMetricsSimple.isChecked = !extended
        binding.btnDashboardMetricsExtended.isChecked = extended
        suppressDashboardMetricsModeCallback = false
        prefs.put(BooleanKey.OverviewDashboardExtendedMetrics, extended)
        applyDashboardMetricsMode(extended)
    }

    private fun applyDashboardMetricsMode(extended: Boolean) {
        binding.dashboardMetricsExtendedContainer.isVisible = extended
        binding.dashboardMetricsCompactScroll.isVisible = !extended
        binding.aimiInsightsContainer.isVisible = extended
        binding.aimiTelemetrySectionLabel.isVisible = extended
        binding.dashboardHeroStatusIob.isVisible = extended
        if (!extended) {
            binding.aimiMlConfidenceStrip.isGone = true
        }
    }

    private fun applyLoopStatusChip(loopIsRunning: Boolean) {
        val btn = binding.loopStatusChip
        val bg = ContextCompat.getColor(context, app.aaps.plugins.main.R.color.dashboard_chip_background)
        val strokeOk = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step1)
        val strokeWarn = ContextCompat.getColor(context, app.aaps.plugins.main.R.color.dashboard_metric_attention)
        val iconOk = ContextCompat.getColor(context, app.aaps.plugins.main.R.color.dashboard_metric_info)
        val iconWarn = ContextCompat.getColor(context, app.aaps.plugins.main.R.color.dashboard_metric_attention)
        btn.backgroundTintList = ColorStateList.valueOf(bg)
        btn.strokeColor = ColorStateList.valueOf(if (loopIsRunning) strokeOk else strokeWarn)
        btn.iconTint = ColorStateList.valueOf(if (loopIsRunning) iconOk else iconWarn)
        btn.alpha = if (loopIsRunning) 1f else 0.92f
    }

    /**
     * Text strip under trajectory insights: sensor tier (if any) + relevance + health. Shown only in extended metrics mode.
     */
    private fun updateAimiMlConfidenceStrip(state: StatusCardState) {
        val extended = dashboardPreferences?.get(BooleanKey.OverviewDashboardExtendedMetrics) == true
        if (!extended) {
            binding.aimiMlConfidenceStrip.isGone = true
            return
        }
        val sep = context.getString(app.aaps.plugins.main.R.string.dashboard_aimi_ml_strip_separator)
        val parts = mutableListOf<String>()
        state.adaptiveSmoothingQualityTier?.let { tier ->
            val wordRes = when (tier) {
                AdaptiveSmoothingQualityTier.OK -> app.aaps.plugins.main.R.string.dashboard_aimi_ml_sensor_ok
                AdaptiveSmoothingQualityTier.UNCERTAIN -> app.aaps.plugins.main.R.string.dashboard_aimi_ml_sensor_uncertain
                AdaptiveSmoothingQualityTier.BAD -> app.aaps.plugins.main.R.string.dashboard_aimi_ml_sensor_low
            }
            parts.add(
                context.getString(
                    app.aaps.plugins.main.R.string.dashboard_aimi_ml_strip_part_sensor,
                    context.getString(wordRes),
                ),
            )
        }
        val rel = state.trajectoryRelevanceScore ?: 0.0
        val relPct = when {
            rel in 0.0..1.0 -> (rel * 100.0).toInt().coerceIn(0, 100)
            rel > 1.0 -> rel.toInt().coerceIn(0, 100)
            else -> 0
        }
        parts.add(
            context.getString(app.aaps.plugins.main.R.string.dashboard_aimi_ml_strip_relevance, relPct),
        )
        val health = (state.aimiHealthScore ?: 0.0).coerceIn(0.0, 1.0)
        val healthPct = (health * 100.0).toInt().coerceIn(0, 100)
        parts.add(
            context.getString(app.aaps.plugins.main.R.string.dashboard_aimi_ml_strip_health, healthPct),
        )
        val detail = parts.joinToString(separator = sep)
        binding.aimiMlConfidenceDetail.text = detail
        binding.aimiMlConfidenceStrip.isGone = detail.isBlank()
    }

    private fun updateCompactMetricChips(state: StatusCardState) {
        binding.dashboardCompactSteps.text = state.stepsText ?: "--"
        binding.dashboardCompactIob.text =
            state.iobText.trim().takeUnless { it.isEmpty() } ?: "--"
        binding.dashboardCompactHr.text = state.hrText ?: "--"
        binding.dashboardCompactBasal.text = state.tbrRateCompactText ?: state.tbrRateText ?: "--"
    }

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
                    val accessor = "get" + name.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                    }
                    val getter = stateClass.getMethod(accessor)
                    @Suppress("UNCHECKED_CAST")
                    getter.invoke(state) as? T
                } catch (e: Exception) {
                    null
                }
            }
            
            // ═══════════════════════════════════════════════════════════════
            // 1. Glucose hero (Compose / AapsTheme)
            // ═══════════════════════════════════════════════════════════════
            // Use [StatusCardState] fields directly so delta/time/angles are never dropped by reflection.
            if (state is StatusCardState) {
                updateCompactMetricChips(state)
                state.glucoseMgdl?.let { bgMgdl ->
                    val arcP = telemetryArcProgress(state)
                    val arcC = arcP?.let { telemetryArcColor(it) }
                    heroState.value = buildGlucoseHeroUiState(
                        bgMgdl = bgMgdl,
                        cardState = state,
                        glucoseText = state.glucoseText,
                        timeAgo = state.timeAgo,
                        deltaText = state.deltaText,
                        noseAngle = state.noseAngleDeg,
                        glucoseColor = state.glucoseColor,
                        arcProgress = arcP,
                        arcColorArgb = arcC,
                    )
                    binding.glucoseHeroCompose.contentDescription = context.getString(
                        app.aaps.plugins.main.R.string.dashboard_glucose_ring_content_description,
                        state.glucoseText,
                        state.deltaText,
                    )
                }
            }

            if (state is StatusCardState) {
                updateAimiMlConfidenceStrip(state)
            } else {
                binding.aimiMlConfidenceStrip.isGone = true
            }

            // ═══════════════════════════════════════════════════════════════
            // 2. Left Column Metrics
            // ═══════════════════════════════════════════════════════════════
            binding.cobText.text = getProp<String>("cobText") ?: "0g"
            binding.cvText.text = getProp<String>("cvText") ?: "CV --%"
            binding.activityText.text = getProp<String>("activityPctText") ?: "0%"

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
                    adaptiveSmoothingIconTintState.value = Color(context.getColor(tintRes))

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

            binding.tirStatsText.text = getProp<String>("tirStatsLine")?.takeIf { it.isNotBlank() }
                ?: context.getString(app.aaps.plugins.main.R.string.dashboard_tir_stats_placeholder)

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
            // 5. Loop chip + hero column (IOB si étendu, lecture / capteur)
            // ═══════════════════════════════════════════════════════════════
            val loopLabel = getProp<String>("loopStatusText")
                ?: context.getString(app.aaps.plugins.main.R.string.closed_loop)
            binding.loopStatusChip.text = loopLabel
            binding.loopStatusChip.contentDescription = loopLabel
            val loopRunning =
                if (state is StatusCardState) state.loopIsRunning
                else getProp<Boolean>("loopIsRunning") ?: true
            applyLoopStatusChip(loopRunning)

            // Pompe / réservoir / site — pastilles lisibles (même esprit que la puce boucle / CGM).
            if (state is StatusCardState) {
                val dash = context.getString(app.aaps.core.ui.R.string.value_unavailable_short)
                val resV = state.reservoirText?.trim().orEmpty().ifBlank { dash }
                val battV = state.pumpBatteryText?.trim().orEmpty().ifBlank { dash }
                val siteV = state.infusionAgeText?.trim().orEmpty().ifBlank { dash }
                binding.dashboardPumpChipReservoir.text =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_reservoir, resV)
                binding.dashboardPumpChipReservoir.contentDescription =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_reservoir_a11y, resV)
                binding.dashboardPumpChipBattery.text =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_battery, battV)
                binding.dashboardPumpChipBattery.contentDescription =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_battery_a11y, battV)
                binding.dashboardPumpChipSite.text =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_site, siteV)
                binding.dashboardPumpChipSite.contentDescription =
                    context.getString(app.aaps.plugins.main.R.string.dashboard_pump_pill_site_a11y, siteV)
                binding.dashboardPumpChipReservoir.isClickable = false
                binding.dashboardPumpChipReservoir.isFocusable = false
                binding.dashboardPumpChipBattery.isClickable = false
                binding.dashboardPumpChipBattery.isFocusable = false
                binding.dashboardPumpChipSite.isClickable = false
                binding.dashboardPumpChipSite.isFocusable = false
            }

            val extendedMetrics =
                dashboardPreferences?.get(BooleanKey.OverviewDashboardExtendedMetrics) == true
            binding.dashboardHeroStatusIob.isVisible = extendedMetrics
            if (extendedMetrics) {
                val iobLine =
                    if (state is StatusCardState) state.iobText else getProp<String>("iobText")
                binding.dashboardHeroStatusIob.text =
                    iobLine?.trim().takeUnless { it.isNullOrEmpty() } ?: "—"
            }

            // Prefer human-readable age (minAgoLong), not minAgoShort "(-2)" delta convention.
            val readingAgeHuman =
                if (state is StatusCardState) state.timeAgoDescription
                else getProp<String>("timeAgoDescription")
            val sensorAge =
                if (state is StatusCardState) state.sensorAgeText else getProp<String>("sensorAgeText")
            val readingPart = readingAgeHuman?.trim().orEmpty().ifBlank { "—" }
            binding.dashboardHeroStatusMeta.text = buildString {
                append(
                    context.getString(
                        app.aaps.plugins.main.R.string.dashboard_hero_status_reading_line,
                        readingPart,
                    ),
                )
                val s = sensorAge?.trim().orEmpty()
                if (s.isNotEmpty()) {
                    append('\n')
                    append(
                        context.getString(
                            app.aaps.plugins.main.R.string.dashboard_hero_status_sensor_line,
                            s,
                        ),
                    )
                }
            }
            
            // Steps & HR
            binding.stepsText.text = getProp<String>("stepsText") ?: "--"
            binding.hrText.text = getProp<String>("hrText") ?: "--"
            
            // ═══════════════════════════════════════════════════════════════
            // 5b. AIMI Pulse (real APS reason + facts)
            // ═══════════════════════════════════════════════════════════════
            val showAimiPulse = dashboardPreferences?.get(BooleanKey.OverviewShowHybridDashboardAimiPulse) == true
            binding.aimiPulseContainer.isGone = !showAimiPulse
            if (state is StatusCardState && showAimiPulse) {
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
            
            // Insights container: trajectory health drives emphasis (aligned with telemetry arc thresholds)
            val health = getProp<Double>("aimiHealthScore") ?: 1.0
            binding.aimiInsightsContainer.setBackgroundResource(
                when {
                    health < 0.45 -> app.aaps.plugins.main.R.drawable.dashboard_chip_background_warning
                    health < 0.72 -> app.aaps.plugins.main.R.drawable.dashboard_chip_background_quality_uncertain
                    else -> app.aaps.plugins.main.R.drawable.dashboard_chip_background
                }
            )
            

            
        } catch (e: Exception) {
            // Fallback: Log error but don't crash
            e.printStackTrace()
        }
    }

    /**
     * Set action listeners for quick actions (Compose) and the AIMI pulse card.
     */
    fun setActionListener(listener: CircleTopActionListener) {
        circleTopActionListener = listener
        actionListenerState.value = listener

        fun announceIfAccessibilityEnabled(messageRes: Int) {
            val manager = accessibilityManager
            if (manager != null && manager.isEnabled && manager.isTouchExplorationEnabled) {
                announceForAccessibility(context.getString(messageRes))
            }
        }

        fun withHaptic(action: () -> Unit): View.OnClickListener = View.OnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }

        binding.aimiPulseContainer.setOnClickListener(withHaptic {
            circleTopActionListener?.onAimiPulseClicked()
            announceIfAccessibilityEnabled(app.aaps.plugins.main.R.string.dashboard_chip_announced_aimi_pulse_details)
        })
    }
    
    // ═══════════════════════════════════════════════════════════════
    // Accessors for DashboardFragment integration
    // ═══════════════════════════════════════════════════════════════
    
    /** Get container for Auditor badge (will be populated by DashboardFragment) */
    fun getAuditorContainer(): FrameLayout = binding.aimiAuditorIndicatorContainer
    
    /** Get AIMI Context indicator (visibility controlled by DashboardFragment) */
    fun getContextIndicator(): View = binding.aimiContextIndicator
    
    /** Cible tactile pour ouvrir le dialog boucle ([DashboardFragment]). */
    fun getLoopIndicator(): View = binding.loopStatusChip

    /**
     * Blended telemetry arc progress (0..1): relevance, health, or sensor-quality proxy when APS data sparse.
     */
    private fun telemetryArcProgress(state: StatusCardState): Float? {
        val rel = state.trajectoryRelevanceScore
        val health = state.aimiHealthScore
        val tierProxy = state.adaptiveSmoothingQualityTier?.let { tier ->
            when (tier) {
                AdaptiveSmoothingQualityTier.OK -> 0.88
                AdaptiveSmoothingQualityTier.UNCERTAIN -> 0.58
                AdaptiveSmoothingQualityTier.BAD -> 0.35
            }
        }
        val combined: Double? = when {
            rel != null && health != null -> 0.5 * (rel + health)
            rel != null -> rel
            health != null -> health
            tierProxy != null -> tierProxy
            else -> null
        }
        return combined?.toFloat()?.coerceIn(0f, 1f)
    }

    private fun telemetryArcColor(progress: Float): Int {
        val resId = when {
            progress >= 0.72f -> app.aaps.core.ui.R.color.glucose_ring_step1
            progress >= 0.45f -> app.aaps.core.ui.R.color.glucose_ring_step2
            else -> app.aaps.core.ui.R.color.glucose_ring_step3
        }
        return ContextCompat.getColor(context, resId)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else 0xFF888888.toInt()
    }

    private fun buildGlucoseHeroUiState(
        bgMgdl: Int,
        cardState: StatusCardState?,
        glucoseText: String,
        timeAgo: String,
        deltaText: String,
        noseAngle: Float?,
        glucoseColor: Int?,
        arcProgress: Float?,
        arcColorArgb: Int?,
    ): GlucoseHeroUiState {
        val step1 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step1)
        val step2 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step2)
        val step3 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step3)
        val step4 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step4)
        // Ring palette: stepped TIR-style bands (green→120, etc.). Hypo band uses default clinical floor (70)
        // from [hypoMaxMgdlAttr], not profile target low — otherwise BG just under a high target-low (e.g. 100 vs 105)
        // stays orange and ignores step1MaxMgdl. Compose [GlucoseHeroRing] uses this [ringArgb] only.
        val ringArgb = GlucoseRingColorComputer.compute(
            bgMgdl = bgMgdl,
            hypoMaxFromProfile = null,
            severeHypoMaxMgdl = 54f,
            hypoMaxMgdlAttr = 70f,
            useSteppedColors = true,
            step1MaxMgdl = 120f,
            step2MaxMgdl = 160f,
            step3MaxMgdl = 220f,
            stepColor1 = step1,
            stepColor2 = step2,
            stepColor3 = step3,
            stepColor4 = step4,
        )
        // Delta + time: [GlucoseHeroRing] uses the same order as Overview [BgInfoSection] (delta on top).
        return GlucoseHeroUiState(
            mainText = glucoseText,
            subLeftText = deltaText,
            subRightText = timeAgo,
            noseAngleDeg = noseAngle,
            ringColorArgb = ringArgb,
            centerTextColorArgb = glucoseColor
                ?: ContextCompat.getColor(context, app.aaps.core.ui.R.color.white),
            subTextColorArgb = resolveThemeColor(android.R.attr.textColorSecondary),
            surfaceColorArgb = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_surface),
            telemetryProgress = arcProgress,
            telemetryColorArgb = arcColorArgb,
            strokeWidthDp = 4f,
        )
    }
}

/**
 * Listener for dashboard quick actions and the AIMI pulse card.
 */
interface CircleTopActionListener {
    fun onAimiAdvisorClicked()
    fun onAdjustClicked()
    fun onAimiPreferencesClicked()
    fun onStatsClicked()
    fun onAimiPulseClicked()
}
