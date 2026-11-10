package app.aaps.plugins.main.general.dashboard.glass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.ui.UiMode
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.PumpInfoState
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolsScreen
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import java.util.Locale
import kotlin.math.roundToInt
import app.aaps.ui.compose.overview.graphs.ChartConfig
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusItem
import app.aaps.ui.compose.overview.statusLights.StatusUiState
import app.aaps.ui.compose.overview.statusLights.StatusViewModel

@Composable
internal fun GlassOverviewComposeEmbedded(
    overviewViewModel: OverviewViewModel,
    statusViewModel: StatusViewModel,
    graphViewModel: GraphViewModel,
    embeddedState: DashboardEmbeddedComposeState,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by overviewViewModel.statusCardState.observeAsState()
    val statusLights by statusViewModel.uiState.collectAsStateWithLifecycle()
    val commands = LocalGlassHeroCommands.current
    // The visible window in minutes (15 min .. 24 h); every chart uses the same one.
    var rangeMinutes by remember { mutableStateOf(360) }
    val rangeHours = rangeMinutes / 60f
    // How far the window is moved back from now (finger drag), in milliseconds; 0 = live.
    var panMs by remember { mutableStateOf(0L) }
    val panBy: (Float) -> Unit = { frac ->
        val windowMs = rangeMinutes * 60_000L
        panMs = (panMs + (frac * windowMs).toLong()).coerceIn(0L, ((1440 - rangeMinutes).coerceAtLeast(0)) * 60_000L)
    }
    val panMinutes = (panMs / 60_000L).toInt()
    // One finger position shared by the BG and IOB charts: touching either one moves both.
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    // Lower panels of the combined chart card; more than one can be on at the same time.
    var showIob by remember { mutableStateOf(true) }
    var showBalance by remember { mutableStateOf(true) }
    var showCob by remember { mutableStateOf(false) }
    // Two-finger pinch steps through these hour ranges; the buttons set 6/12/18/24 directly.
    var pinchFactor by remember { mutableStateOf(1f) }
    // Opening the app (or coming back to it): the chart goes back to now and the marker stands on the current reading.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                panMs = 0L
                scrubProgress = 1f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(rangeMinutes) {
        scrubProgress = 1f
        // One day of history is the limit: a longer window leaves less room to move back.
        panMs = panMs.coerceAtMost(((1440 - rangeMinutes).coerceAtLeast(0)) * 60_000L)
    }
    var showTools by remember { mutableStateOf(false) }
    var showPersonalize by remember { mutableStateOf(false) }
    LaunchedEffect(showTools) { if (!showTools) showPersonalize = false }

    AapsTheme {
        val preferences = LocalPreferences.current
        val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
        val isDark = when (UiMode.fromString(darkModeValue)) {
            UiMode.LIGHT  -> false
            UiMode.DARK   -> true
            UiMode.SYSTEM -> isSystemInDarkTheme()
        }
        val selectedPillsRaw by preferences.observe(StringNonKey.GlassSelectedPills).collectAsState()
        val selectedPills = remember(selectedPillsRaw) { parseSelectedGlassPills(selectedPillsRaw) }
        LaunchedEffect(Unit) {
            if (!preferences.get(BooleanNonKey.GlassTopGridPillsMigrated)) {
                val current = parseSelectedGlassPills(preferences.get(StringNonKey.GlassSelectedPills)).toSet()
                preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(withTopGridDefaultsMerged(current)))
                preferences.put(BooleanNonKey.GlassTopGridPillsMigrated, true)
            }
        }
        val selectedToolsRaw by preferences.observe(StringNonKey.GlassSelectedTools).collectAsState()
        val selectedTools = remember(selectedToolsRaw) { parseSelectedGlassTools(selectedToolsRaw).toSet() }
        val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
        val state = buildGlassUiState(status, statusLights, treatmentData)

        val cobData by graphViewModel.cobGraphFlow.collectAsStateWithLifecycle()
        val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
        val iobData by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
        val basalData by graphViewModel.basalGraphFlow.collectAsStateWithLifecycle()
        val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
        val deviationsData by graphViewModel.deviationsGraphFlow.collectAsStateWithLifecycle()
        val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
        val nowEpochMs = System.currentTimeMillis() - panMs
        val chartState = remember(
            rangeMinutes, panMs, bgReadings, iobData, basalData, treatmentData, chartConfig, predictions,
            status?.pumpStatusText, status?.iobTotalU,
        ) {
            buildGlassChartState(
                rangeHours = rangeHours,
                nowEpochMs = nowEpochMs,
                iobTotalU = status?.iobTotalU,
                bgReadings = bgReadings,
                iobPoints = iobData.iob,
                iobPredictionPoints = iobData.predictions,
                basalData = basalData,
                boluses = treatmentData.boluses,
                carbs = treatmentData.carbs,
                chartConfig = chartConfig,
                mgdlToChartY = graphViewModel::glucoseMgdlToChartY,
                pumpStatusText = status?.pumpStatusText.orEmpty(),
                predictions = if (panMs > 0L) predictions.filter { false } else predictions,
                // Looking back in time there is no forecast: the history uses the whole width, no empty area on the right.
                predictionHorizonHours = if (panMs > 0L) 0 else 2,
            )
        }

        // The pump line is shown in a popup when the Pump button of the bottom bar is held.
        LaunchedEffect(chartState.pumpStatusText) { PumpInfoState.update(chartState.pumpStatusText) }
        val deviationBars = remember(deviationsData, chartState) {
            buildDeviationBars(
                deviations = deviationsData.deviations,
                nowEpochMs = nowEpochMs,
                rangeHours = chartState.rangeHours,
                readings = chartState.bgReadings,
                lowLine = chartState.lowLine,
                highLine = chartState.highLine,
            )
        }

        Box(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusAgoraCard(
                    state = state,
                    isDark = isDark,
                    onOpenLoop = commands::openLoop,
                    onOpenLoopDashboard = commands::openLoopDashboard,
                    onOpenTarget = commands::openTarget,
                    onOpenInsulin = commands::openInsulin,
                    onOpenPump = commands::openPump,
                    onOpenCannula = commands::openCannula,
                    onOpenBattery = commands::openBattery,
                    onOpenBasal = commands::openBasal,
                    onOpenSensorInsert = commands::openSensorInsert,
                    onOpenSensorQuality = commands::openSensorQuality,
                    onOpenTools = { showTools = true },
                    selectedPills = selectedPills,
                )
                GlassCombinedChartCard(
                    isDark = isDark,
                    selectedMinutes = rangeMinutes,
                    onSelectMinutes = { rangeMinutes = it },
                    onOpenStats = commands::openStatsScreen,
                    panned = panMs > 0L,
                    onResetPan = { panMs = 0L },
                    windowMinutes = rangeMinutes,
                    panMinutes = panMinutes,
                    onPanToMinutes = { panMs = it.coerceIn(0, (1440 - rangeMinutes).coerceAtLeast(0)) * 60_000L },
                    statsLabel = stringResource(R.string.stats_button),
                    showIob = showIob,
                    showBalance = showBalance,
                    onToggleIob = { showIob = !showIob },
                    onToggleBalance = { showBalance = !showBalance },
                    showCob = showCob,
                    onToggleCob = { showCob = !showCob },
                    cobChart = {
                        val cobWindowMs = (rangeHours * 3_600_000f).toLong()
                        val cobPoints = remember(cobData, rangeMinutes, panMs) {
                            cobData.cob.filter { it.timestamp in (nowEpochMs - cobWindowMs)..nowEpochMs }.map {
                                IobReadingPoint(((it.timestamp - (nowEpochMs - cobWindowMs)).toFloat() / cobWindowMs.toFloat()).coerceIn(0f, 1f), it.value.toFloat())
                            }
                        }
                        IobChartCard(
                            embedded = true,
                            scrubProgress = scrubProgress,
                            onScrub = { scrubProgress = it },
                            panMinutes = panMinutes,
                            iobReadings = cobPoints,
                            currentIob = cobPoints.lastOrNull()?.iob ?: 0f,
                            timeRangeHours = chartState.rangeHours,
                            historyFraction = chartState.historyFraction,
                            isDark = isDark,
                            title = "COB",
                            accent = Color(0xFFF59E0B),
                            valueFormat = "%.0f g",
                        )
                    },
                    onPinch = { factor ->
                        pinchFactor *= factor
                        val steps = listOf(15, 30, 60, 120, 180, 360, 720, 1080, 1440)
                        val index = steps.indexOfFirst { it >= rangeMinutes }.let { if (it < 0) steps.lastIndex else it }
                        if (pinchFactor > 1.25f && index > 0) {
                            rangeMinutes = steps[index - 1]
                            pinchFactor = 1f
                        } else if (pinchFactor < 0.8f && index < steps.lastIndex) {
                            rangeMinutes = steps[index + 1]
                            pinchFactor = 1f
                        }
                    },
                    balanceChart = {
                        DeviationStrip(
                            bars = deviationBars,
                            timeRangeHours = chartState.rangeHours,
                            historyFraction = chartState.historyFraction,
                            isDark = isDark,
                            scrubProgress = scrubProgress,
                        )
                    },
                    bgChart = {
                        BgChartCard(
                            embedded = true,
                            scrubProgress = scrubProgress,
                            onScrub = { scrubProgress = it },
                            panMinutes = panMinutes,
                            readings = chartState.bgReadings,
                            treatments = chartState.treatments,
                            timeRangeHours = chartState.rangeHours,
                            currentBgValue = chartState.currentBgValue,
                            lowLine = chartState.lowLine,
                            highLine = chartState.highLine,
                            predictions = chartState.predictions,
                            historyFraction = chartState.historyFraction,
                            axisMinValue = chartState.axisMinValue,
                            axisHeadroom = chartState.axisHeadroom,
                            basalReadings = chartState.basalReadings,
                            profileBasalReadings = chartState.profileBasalReadings,
                            maxBasalRateUh = chartState.maxBasalRateUh,
                            formatValue = { v -> graphViewModel.formatBgChartAxisTick(v.toDouble()) },
                            isDark = isDark,
                        )
                    },
                    iobChart = {
                        IobChartCard(
                            embedded = true,
                            scrubProgress = scrubProgress,
                            onScrub = { scrubProgress = it },
                            panMinutes = panMinutes,
                            iobReadings = chartState.iobReadings,
                            predictions = chartState.iobPredictions,
                            currentIob = chartState.currentIob,
                            timeRangeHours = chartState.rangeHours,
                            historyFraction = chartState.historyFraction,
                            isDark = isDark,
                        )
                    },
                )
                if (embeddedState.notifications.isNotEmpty()) {
                    NotificationsSection(
                        notifications = embeddedState.notifications,
                        isDark = isDark,
                        onDismiss = { id -> embeddedState.onDismissNotification?.invoke(id) },
                    )
                }
            }
            if (showTools) {
                BackHandler(enabled = showTools) {
                    if (showPersonalize) showPersonalize = false else showTools = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                if (isDark) {
                                    listOf(Color(0xFF070E1B), Color(0xFF0B1424), Color(0xFF070E1B))
                                } else {
                                    listOf(Color(0xFFF1F5F9), Color(0xFFE8EEF8), Color(0xFFF1F5F9))
                                }
                            )
                        )
                ) {
                    if (showPersonalize) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_title),
                                    color = if (isDark) Color.White else Color(0xFF0D1B2A),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(app.aaps.core.ui.R.string.ok),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            onClickLabel = stringResource(app.aaps.core.ui.R.string.ok),
                                            role = Role.Button,
                                        ) { showPersonalize = false }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            GlassPersonalizeScreen(
                                selectedPills = selectedPills.toSet(),
                                onToggle = { id, checked ->
                                    val next = selectedPills.toMutableSet()
                                    if (checked) next.add(id) else next.remove(id)
                                    preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(next))
                                },
                                selectedTools = selectedTools,
                                onToolToggle = { action, checked ->
                                    val next = selectedTools.toMutableSet()
                                    if (checked) next.add(action) else next.remove(action)
                                    preferences.put(StringNonKey.GlassSelectedTools, serializeSelectedGlassTools(next))
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_button),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            onClickLabel = stringResource(R.string.dashboard_glass_personalize_button),
                                            role = Role.Button,
                                        ) { showPersonalize = true }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            DashboardV2ToolsScreen(
                                paddingValues = PaddingValues(0.dp),
                                fabBottomOffset = 0.dp,
                                availablePluginClassNames = availablePluginClassNames,
                                onAction = { action ->
                                    showTools = false
                                    onToolAction(action)
                                },
                                modifier = Modifier.fillMaxSize(),
                                visibleActions = selectedTools,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A BG reading older than this is treated as stale for chart-boundary/prediction purposes — see
 *  [buildGlassChartState]. 15 minutes is 3x a normal ~5-minute CGM cadence, room for one missed reading
 *  without flagging normal jitter as a dropout. */
private const val FRESH_READING_WINDOW_MS = 15 * 60_000L

internal fun buildGlassChartState(
    rangeHours: Float,
    nowEpochMs: Long,
    bgReadings: List<BgDataPoint>,
    iobPoints: List<GraphDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>,
    chartConfig: ChartConfig,
    mgdlToChartY: (Double) -> Double,
    pumpStatusText: String,
    predictions: List<BgDataPoint>,
    iobPredictionPoints: List<GraphDataPoint> = emptyList(),
    basalData: BasalGraphData = BasalGraphData(emptyList(), emptyList(), 0.0),
    predictionHorizonHours: Int = 2,
    /** Insulin on board as the header prints it; null falls back to the last plotted point. */
    iobTotalU: Double? = null,
): GlassChartState {
    val windowStart = nowEpochMs - (rangeHours * 3_600_000f).toLong()
    fun progress(timestamp: Long): Float =
        ((timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)

    val windowedBg = bgReadings.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedIob = iobPoints.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedBoluses = boluses.filter { it.timestamp in windowStart..nowEpochMs }
    val windowedCarbs = carbs.filter { it.timestamp in windowStart..nowEpochMs }

    val predictionWindowEnd = nowEpochMs + predictionHorizonHours * 3_600_000L
    val windowedPredictions = predictions
        .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
        .sortedBy { it.timestamp }

    fun predictionProgress(timestamp: Long): Float =
        ((timestamp - nowEpochMs).toFloat() / maxOf(1L, predictionWindowEnd - nowEpochMs).toFloat()).coerceIn(0f, 1f)

    fun predictionType(type: BgType): PredictionType? = when (type) {
        BgType.IOB_PREDICTION   -> PredictionType.IOB
        BgType.COB_PREDICTION   -> PredictionType.COB
        BgType.A_COB_PREDICTION -> PredictionType.A_COB
        BgType.UAM_PREDICTION   -> PredictionType.UAM
        BgType.ZT_PREDICTION    -> PredictionType.ZT
        else                    -> null
    }

    val predictionPointsRaw = windowedPredictions.mapNotNull { p ->
        val type = predictionType(p.type) ?: return@mapNotNull null
        PredictionPoint(
            progress = predictionProgress(p.timestamp),
            value = mgdlToChartY(p.value).toFloat(),
            type = type,
        )
    }
    // The history/prediction boundary (historyX(1f) == predictionX(0f), see GlassChartComponents.kt) is
    // fixed at "now" (nowEpochMs). But the last real BG reading is almost always a few minutes OLDER than
    // wall-clock "now" — the CGM only reports every ~5 minutes — so its own progress() is < 1f, and it
    // renders slightly to the LEFT of the boundary. Predictions, in contrast, always start exactly AT the
    // boundary. Left alone, that mismatch draws a visible horizontal gap with nothing in it: this is the
    // real root cause of the "misaligned" jump between the solid and dashed lines (a prior fix only
    // anchored the prediction's Y value, which did not close this X-axis gap).
    //
    // Fix: when the last reading is fresh enough to trust (within FRESH_READING_WINDOW_MS), extend the
    // history line with one more point pinned exactly at the boundary (progress=1f, same Y as the last
    // reading) — a short flat connector that closes the gap. If the last reading is older than that (sensor
    // dropout / stale data), do NOT synthesize a connector, and suppress predictions entirely: a forward
    // projection computed from stale data would be actively misleading, especially in a closed loop.
    val lastReading = windowedBg.lastOrNull()
    val lastReadingAgeMs = lastReading?.let { nowEpochMs - it.timestamp }
    val lastReadingIsFresh = lastReadingAgeMs != null && lastReadingAgeMs <= FRESH_READING_WINDOW_MS
    val lastReadingY = lastReading?.let { mgdlToChartY(it.value).toFloat() }

    // A real prediction point can itself land at progress=0 (its timestamp equals nowEpochMs) — drop those
    // before prepending the synthetic anchor so a type never ends up with two progress=0 points (which would
    // just move the discontinuity instead of removing it, since the two values are not guaranteed equal).
    val predictionPoints = if (lastReadingIsFresh && lastReadingY != null) {
        val anchors = predictionPointsRaw.map { it.type }.distinct().map { type ->
            PredictionPoint(progress = 0f, value = lastReadingY, type = type)
        }
        anchors + predictionPointsRaw.filter { it.progress > 0f }
    } else {
        emptyList()
    }

    val historyFraction = rangeHours / (rangeHours + predictionHorizonHours)

    // BgDataPoint.value is always mg/dL; chartConfig.lowMark/highMark are already in the user's DISPLAY
    // unit (UnitDoubleKey preferences are unit-aware). Convert the readings to display-unit space via
    // mgdlToChartY so everything drawn (curve, low/high band, current value) agrees with what
    // formatValue() will show — do not mix mg/dL and display-unit values in the same chart.
    val lastReadingProgress = lastReading?.let { progress(it.timestamp) }
    val bgReadingPoints = windowedBg.map {
        BgReadingPoint(progress = progress(it.timestamp), value = mgdlToChartY(it.value).toFloat())
    } + if (lastReadingIsFresh && lastReadingY != null && lastReadingProgress != null && lastReadingProgress < 1f) {
        // Only add the connector when there is an actual gap to close — a reading already at progress=1f
        // (its timestamp is nowEpochMs itself) would otherwise get a redundant duplicate point.
        listOf(BgReadingPoint(progress = 1f, value = lastReadingY))
    } else {
        emptyList()
    }
    // Same anchor/continuity idea as the BG chart's predictions, but simpler: IOB is always locally
    // computable from known treatments (never "unavailable" the way a CGM reading can be), so there is no
    // staleness gate here — the projection is trustworthy whenever it exists at all.
    val windowedIobPredictions = iobPredictionPoints
        .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
        .sortedBy { it.timestamp }
    val iobPredictionPointsRaw = windowedIobPredictions.map {
        IobReadingPoint(progress = predictionProgress(it.timestamp), iob = it.value.toFloat())
    }
    // The first raw point represents "IOB right now," computed fresh in the same call that produced the
    // whole projection — use its value as the shared anchor for both the prediction's own progress=0 point
    // and the history line's connector to the boundary, guaranteeing the two curves meet exactly.
    val iobAnchorValue = iobPredictionPointsRaw.firstOrNull()?.iob
    val iobPredictions = if (iobAnchorValue != null) {
        listOf(IobReadingPoint(progress = 0f, iob = iobAnchorValue)) + iobPredictionPointsRaw.filter { it.progress > 0f }
    } else {
        emptyList()
    }

    val lastIobProgress = windowedIob.lastOrNull()?.let { progress(it.timestamp) }
    val iobReadingPoints = windowedIob.map {
        IobReadingPoint(progress = progress(it.timestamp), iob = it.value.toFloat())
    } + if (iobAnchorValue != null && lastIobProgress != null && lastIobProgress < 1f) {
        listOf(IobReadingPoint(progress = 1f, iob = iobAnchorValue))
    } else {
        emptyList()
    }
    val treatmentPoints = buildList {
        windowedBoluses.forEach { b ->
            add(TreatmentPoint(progress = progress(b.timestamp), isCarb = false, label = b.label, timestamp = b.timestamp, amount = b.amount.toFloat(), isSmb = b.bolusType.name == "SMB"))
        }
        windowedCarbs.forEach { c ->
            add(TreatmentPoint(progress = progress(c.timestamp), isCarb = true, label = c.label, timestamp = c.timestamp, amount = c.amount.toFloat()))
        }
    }

    // BasalGraphData only carries a point where the rate CHANGES (a step series) — see its KDoc. Carry the
    // last known value from before the window forward as a progress=0 point, so a window that starts mid-step
    // (no change since before windowStart) still renders a value instead of starting empty.
    fun stepSeries(points: List<GraphDataPoint>): List<BasalReadingPoint> {
        val carriedForward = points.lastOrNull { it.timestamp <= windowStart }
        // Strictly after windowStart: a point AT windowStart is already covered by carriedForward above,
        // and including it again here would render a redundant duplicate progress=0 point.
        val withinWindow = points.filter { it.timestamp > windowStart && it.timestamp <= nowEpochMs }
        return buildList {
            carriedForward?.let { add(BasalReadingPoint(progress = 0f, rateUh = it.value.toFloat())) }
            withinWindow.forEach { add(BasalReadingPoint(progress = progress(it.timestamp), rateUh = it.value.toFloat())) }
        }
    }

    return GlassChartState(
        bgReadings = bgReadingPoints,
        iobReadings = iobReadingPoints,
        treatments = treatmentPoints,
        predictions = predictionPoints,
        iobPredictions = iobPredictions,
        basalReadings = stepSeries(basalData.actualBasal),
        profileBasalReadings = stepSeries(basalData.profileBasal),
        maxBasalRateUh = basalData.maxBasal.toFloat().coerceAtLeast(0.1f),
        historyFraction = historyFraction,
        currentBgValue = bgReadingPoints.lastOrNull()?.value ?: 0f,
        // The headline number is the one the header shows, not the last point of the five-minute
        // grid this curve is drawn on: the two are computed at different moments, and insulin on
        // board decays between them, so they used to disagree by a few hundredths of a unit in front
        // of the user. The curve keeps its own points; only the figure is shared.
        currentIob = iobTotalU?.toFloat() ?: iobReadingPoints.lastOrNull()?.iob ?: 0f,
        lowLine = chartConfig.lowMark.toFloat(),
        highLine = chartConfig.highMark.toFloat(),
        axisMinValue = mgdlToChartY(30.0).toFloat(),
        axisHeadroom = (mgdlToChartY(40.0).toFloat() - mgdlToChartY(0.0).toFloat()) * 0.3f,
        rangeHours = rangeHours,
        pumpStatusText = pumpStatusText,
    )
}

internal fun buildGlassUiState(
    status: StatusCardState?,
    lights: StatusUiState?,
    treatmentData: TreatmentGraphData,
): GlassUiState {
    if (status == null) return GlassUiState()
    return GlassUiState(
        currentBg = status.glucoseText,
        unit = status.unitText,
        glucoseColor = status.glucoseColor,
        glucoseDigitsColor = status.glucoseGradientColor,
        deltaText = status.deltaText,
        trendArrowRes = status.trendArrowRes,
        timeAgo = status.timeAgo,
        insulinAge = lights?.insulinStatus?.age ?: "--",
        insulinAgeStatus = lights?.insulinStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        insulinLabel = lights?.insulinStatus?.label ?: "Insulin",
        cannulaAge = lights?.cannulaStatus?.age ?: "--",
        cannulaAgeStatus = lights?.cannulaStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        cannulaLabel = lights?.cannulaStatus?.label ?: "Cannula",
        batteryAge = lights?.batteryStatus?.age ?: "--",
        batteryAgeStatus = lights?.batteryStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        batteryLabel = lights?.batteryStatus?.label ?: "Battery",
        sensorAge = lights?.sensorStatus?.age ?: "--",
        sensorAgeStatus = lights?.sensorStatus?.ageStatus ?: StatusLevel.UNSPECIFIED,
        sensorLabel = lights?.sensorStatus?.label ?: "Sensor",
        loopStatusText = status.loopStatusText,
        loopMode = status.loopMode,
        reservoirText = status.reservoirText ?: "--",
        loopIsRunning = status.loopIsRunning,
        // lastSensorValueText holds the total IOB WITHOUT the "IOB: " prefix baked into status.iobText
        // (which DASHBOARD_V2/legacy views display standalone) — reused here to avoid Glass's own separate
        // pill title duplicating that prefix. Do not "fix" this back to status.iobText.
        iobText = status.lastSensorValueText ?: "--",
        isTempTargetActive = status.isTempTargetActive,
        targetText = status.targetText ?: "--",
        basalPercentText = status.effectiveBasalText ?: "--",
        stepsText = status.stepsText ?: "--",
        hrText = status.hrText ?: "--",
        lastBolusText = treatmentData.boluses.filter { it.isValid && it.timestamp <= System.currentTimeMillis() }.maxByOrNull { it.timestamp }?.label ?: "--",
        lastCarbsText = treatmentData.carbs.filter { it.isValid && it.timestamp <= System.currentTimeMillis() }.maxByOrNull { it.timestamp }?.label ?: "--",
        tir = buildGlassTir(status),
        warmup = status.warmup,
        isGlucoseActual = status.isGlucoseActual,
        glucoseTimestampMs = status.glucoseTimestampMs,
        avgBgText = formatAverageGlucose(status.avgBgMgdl, status.unitText),
        cvText = status.bgCv?.let { "${it.roundToInt()}%" } ?: "--",
        gmiText = status.a1c?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--",
    )
}

/** Average glucose in the unit shown on the screen; mmol/L with one decimal, mg/dL as a whole number. */
private fun formatAverageGlucose(avgMgdl: Double?, unit: String): String {
    if (avgMgdl == null) return "--"
    return if (unit.contains("mmol", ignoreCase = true)) String.format(Locale.US, "%.1f", avgMgdl / 18.0182) else avgMgdl.roundToInt().toString()
}

/** Reuses the TIR-of-day already computed by the dashboard ViewModel: no extra DB read here. */
private fun buildGlassTir(status: StatusCardState): GlassTir? {
    if (!status.tirDayReady) return null
    val inRange = status.tirTarget ?: return null
    return GlassTir(
        belowPct = ((status.tirVeryLow ?: 0.0) + (status.tirLow ?: 0.0)).toFloat(),
        inRangePct = inRange.toFloat(),
        abovePct = ((status.tirHigh ?: 0.0) + (status.tirVeryHigh ?: 0.0)).toFloat(),
        deltaVsYesterday = status.tirYesterdayTarget?.let { (inRange - it).roundToInt() },
    )
}
