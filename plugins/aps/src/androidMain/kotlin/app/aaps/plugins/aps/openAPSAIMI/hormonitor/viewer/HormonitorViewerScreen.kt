package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * In-app viewer for the exported Hormonitor study data. Read-only. Day list comes from the compact
 * daily_outcomes file; the rich per-day detail is aggregated from the event stream. The goal is to let the
 * user see that the data is well-structured AND meaningful — how the tree/Harmonia, physio and hormonal
 * context were actually deployed across a day. Embedded in AIMI settings via [ComposeScreenContent].
 */
@Composable
fun HormonitorViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val reader = remember {
        val dirs = buildList {
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(File(it, "Documents/AAPS")) }
            runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(File(it, "AAPS")) }
        }
        HormonitorReader(dirs)
    }

    var days by remember { mutableStateOf<List<HormonitorDaySummary>>(emptyList()) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<HormonitorDayDetail?>(null) }
    var loadingDays by remember { mutableStateOf(true) }
    var loadingDetail by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        days = reader.readDays()
        selectedDay = days.firstOrNull()?.dayLocal
        loadingDays = false
    }
    LaunchedEffect(selectedDay) {
        val day = selectedDay ?: return@LaunchedEffect
        loadingDetail = true
        detail = reader.readDayDetail(day)
        loadingDetail = false
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_hormonitor_viewer_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when {
                    loadingDays -> CenteredProgress()
                    days.isEmpty() -> CenteredText(stringResource(R.string.aimi_hormonitor_viewer_empty))
                    else -> {
                        DaySelector(days = days, selected = selectedDay, onSelect = { selectedDay = it })
                        val summary = days.firstOrNull { it.dayLocal == selectedDay }
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = AapsSpacing.large)
                                .padding(bottom = AapsSpacing.xxLarge),
                        ) {
                            summary?.let { DaySummaryCard(it) }
                            when {
                                loadingDetail -> CenteredProgress()
                                detail == null -> CenteredText(stringResource(R.string.aimi_hormonitor_viewer_no_events))
                                else -> DayDetailSections(detail!!)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySelector(days: List<HormonitorDaySummary>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(AapsSpacing.large),
    ) {
        items(days) { d ->
            FilterChip(
                selected = d.dayLocal == selected,
                onClick = { onSelect(d.dayLocal) },
                label = { Text(shortDay(d.dayLocal)) },
            )
        }
    }
}

@Composable
private fun DaySummaryCard(s: HormonitorDaySummary) {
    SectionCard(s.dayLocal) {
        TirBar(s.tirLowPct, s.tirInRangePct, s.tirAbovePct)
        Spacer(Modifier.height(AapsSpacing.small))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_tir), tirText(s))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_tdd), num1(s.tdd24hU) + " U")
        KeyValueRow(
            stringResource(R.string.aimi_hormonitor_decisions),
            "${s.decisionTotal}  ·  SMB ${s.decisionSmb} · TBR↑ ${s.decisionTbrUp} · TBR↓ ${s.decisionTbrDown} · " +
                "susp ${s.decisionSuspend} · none ${s.decisionNone} · veto ${s.decisionVeto}",
        )
        KeyValueRow(
            stringResource(R.string.aimi_hormonitor_reliability),
            (s.sourceReliabilityScore?.let { "${(it * 100).roundToInt()}%" } ?: "—") +
                (s.sourceStale?.let { if (it) "  (stale)" else "" } ?: ""),
        )
    }
}

@Composable
private fun DayDetailSections(d: HormonitorDayDetail) {
    // Integrity — proof the data is well-formed.
    SectionCard(stringResource(R.string.aimi_hormonitor_integrity)) {
        KeyValueRow(stringResource(R.string.aimi_hormonitor_records), "${d.eventCount}")
        KeyValueRow(stringResource(R.string.aimi_hormonitor_schema), d.integrity.schemaVersions.joinToString(", ").ifBlank { "—" })
        KeyValueRow(stringResource(R.string.aimi_hormonitor_span), timeSpan(d.integrity.firstTimestamp, d.integrity.lastTimestamp))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_patient_story), sharePct(d.integrity.patientStoryCoverage))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_physio_snapshot), sharePct(d.integrity.physioSnapshotCoverage))
        if (d.integrity.malformedLineCount > 0) {
            KeyValueRow(stringResource(R.string.aimi_hormonitor_malformed), "${d.integrity.malformedLineCount}")
        }
    }
    // Tree / Harmonia — the core "does it make sense" evidence, shown first.
    SectionCard(stringResource(R.string.aimi_hormonitor_tree_harmonia)) {
        DistributionBlock(stringResource(R.string.aimi_hormonitor_patient_modes), d.treeHarmonia.patientModes)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_strategy_hints), d.treeHarmonia.strategyHints)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_reason_codes), d.treeHarmonia.reasonCodes)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_final_decisions), d.treeHarmonia.finalDecisions)
        KeyValueRow(stringResource(R.string.aimi_hormonitor_mode_confidence), num2(d.treeHarmonia.meanModeConfidence))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_vetoes), "${d.treeHarmonia.vetoCount}")
        if (d.treeHarmonia.narrativeSamples.isNotEmpty()) {
            Spacer(Modifier.height(AapsSpacing.small))
            Text(stringResource(R.string.aimi_hormonitor_narratives), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            d.treeHarmonia.narrativeSamples.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // Physio.
    SectionCard(stringResource(R.string.aimi_hormonitor_physio)) {
        DistributionBlock(stringResource(R.string.aimi_hormonitor_physio_states), d.physio.physioStates)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_activity_states), d.physio.activityStates)
        KeyValueRow(stringResource(R.string.aimi_hormonitor_confidence), num2(d.physio.meanConfidence))
        KeyValueRow(stringResource(R.string.aimi_hormonitor_data_quality), num2(d.physio.meanDataQuality))
        StatRow("ISF ×", d.physio.isfFactor, ::num2)
        StatRow("Basal ×", d.physio.basalFactor, ::num2)
        StatRow("SMB ×", d.physio.smbFactor, ::num2)
        StatRow(stringResource(R.string.aimi_hormonitor_steps15), d.physio.steps15m, ::num0)
        StatRow(stringResource(R.string.aimi_hormonitor_hr), d.physio.hrNowBpm, ::num0)
    }
    // Hormonal.
    SectionCard(stringResource(R.string.aimi_hormonitor_hormonal)) {
        DistributionBlock(stringResource(R.string.aimi_hormonitor_cycle_phase), d.hormonal.cyclePhases)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_thyroid), d.hormonal.thyroidStatuses)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_inflammation), d.hormonal.inflammationStatuses)
        StatRow(stringResource(R.string.aimi_hormonitor_cycle_day), d.hormonal.cycleDayRange, ::num0)
        StatRow("wCycle basal ×", d.hormonal.wcycleBasalMult, ::num2)
        StatRow("wCycle SMB ×", d.hormonal.wcycleSmbMult, ::num2)
        StatRow("wCycle ISF ×", d.hormonal.wcycleIsfMult, ::num2)
    }
    // Safety.
    SectionCard(stringResource(R.string.aimi_hormonitor_safety)) {
        DistributionBlock(stringResource(R.string.aimi_hormonitor_safety_gates), d.safety.safetyGates)
        DistributionBlock(stringResource(R.string.aimi_hormonitor_safety_phases), d.safety.safetyPhases)
        KeyValueRow(stringResource(R.string.aimi_hormonitor_hypo_suppressed), "${d.safety.predictiveHypoSuppressedCount}")
        StatRow(stringResource(R.string.aimi_hormonitor_composite_min), d.safety.compositeMinMgdl, ::num0)
    }
}

// --- reusable building blocks ---

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(AapsSpacing.medium))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AapsSpacing.large)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = AapsSpacing.small))
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = AapsSpacing.extraSmall), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatRow(label: String, range: StatRange?, fmt: (Double?) -> String) {
    KeyValueRow(label, range?.let { "${fmt(it.min)} – ${fmt(it.max)}  (${stringResource(R.string.aimi_hormonitor_mean)} ${fmt(it.mean)})" } ?: "—")
}

@Composable
private fun DistributionBlock(title: String, items: List<LabelCount>, max: Int = 6) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(AapsSpacing.small))
    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    items.take(max).forEach { lc ->
        Row(Modifier.fillMaxWidth().padding(vertical = AapsSpacing.extraSmall), verticalAlignment = Alignment.CenterVertically) {
            Text(HormonitorLabels.humanize(lc.label), modifier = Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val barWeight = lc.share.toFloat().coerceIn(0.02f, 1f)
            Box(
                Modifier
                    .weight(barWeight)
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
            if (barWeight < 1f) Box(Modifier.weight(1f - barWeight))
            Spacer(Modifier.width(AapsSpacing.small))
            Text("${lc.count} (${(lc.share * 100).roundToInt()}%)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TirBar(low: Double?, inRange: Double?, above: Double?) {
    val l = (low ?: 0.0).toFloat()
    val r = (inRange ?: 0.0).toFloat()
    val a = (above ?: 0.0).toFloat()
    if (l + r + a <= 0f) return
    Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        if (l > 0f) Box(Modifier.weight(l).fillMaxHeight().background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp)))
        if (r > 0f) Box(Modifier.weight(r).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
        if (a > 0f) Box(Modifier.weight(a).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxWidth().padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- formatting helpers ---

private fun tirText(s: HormonitorDaySummary): String =
    "${pct(s.tirLowPct)} / ${pct(s.tirInRangePct)} / ${pct(s.tirAbovePct)}"

private fun pct(d: Double?): String = d?.let { "${it.roundToInt()}%" } ?: "—"
private fun sharePct(d: Double): String = "${(d * 100).roundToInt()}%"
private fun num0(d: Double?): String = d?.let { "${it.roundToInt()}" } ?: "—"
private fun num1(d: Double?): String = d?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
private fun num2(d: Double?): String = d?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

private fun shortDay(dayLocal: String): String =
    runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayLocal)
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(parsed ?: Date())
    }.getOrDefault(dayLocal)

private fun timeSpan(first: Long?, last: Long?): String {
    if (first == null || last == null) return "—"
    val f = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${f.format(Date(first))} – ${f.format(Date(last))}"
}
