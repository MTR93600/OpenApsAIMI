package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningApplyResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextApplySupport
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningExportStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningPlan
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose port of the parked `AimiProfileAdvisorActivity` - Tuning Context section only
 * (sub-lot 1/5). Later sub-lots add more cards/sections to this same screen; this one owns
 * loading the [AdvisorReport] since every future section reads from it too.
 */
@Composable
fun AimiProfileAdvisorScreen(
    preferences: Preferences,
    advisorService: AimiAdvisorService,
    historyRepo: AdvisorHistoryRepository,
    importExportPrefs: ImportExportPrefs,
    exportPasswordDataStore: ExportPasswordDataStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var report by remember { mutableStateOf<AdvisorReport?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedTuningContext by remember {
        mutableStateOf(TuningContextEngine.parseContext(preferences.get(StringKey.AimiTuningContextSelection)))
    }
    val t3cBrittleMode = remember { preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) }

    var previewPlan by remember { mutableStateOf<TuningPlan?>(null) }
    var applyPlan by remember { mutableStateOf<TuningPlan?>(null) }
    var applying by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<TuningApplyResult?>(null) }

    val noChangesMessage = stringResource(R.string.aimi_tuning_no_changes)
    val errorPrefix = stringResource(R.string.aimi_adv_error_prefix)
    val errorOom = stringResource(R.string.aimi_adv_error_oom)

    LaunchedEffect(Unit) {
        try {
            // history feeds the 48h-cooldown filter on recommendations a later sub-lot renders from
            // this same report; assetContext lets the OREF pipeline load its bundled ML asset. Both
            // silently degrade the report (not a crash) if omitted, so pass them like the original did.
            report = withContext(Dispatchers.IO) {
                advisorService.generateReport(
                    history = historyRepo.getRecentActions(10),
                    assetContext = context,
                )
            }
        } catch (t: Throwable) {
            loadError = when (t) {
                is OutOfMemoryError -> errorOom
                else                -> "$errorPrefix${t.localizedMessage ?: t.javaClass.simpleName}"
            }
        }
    }

    fun buildPlan(): TuningPlan? {
        val metrics = report?.metrics ?: return null
        return TuningContextEngine.computePlan(
            requestedContext = selectedTuningContext,
            metrics = metrics,
            preferences = preferences,
            t3cBrittleMode = t3cBrittleMode,
        )
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_advisor_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = AapsSpacing.large)
                    .padding(bottom = AapsSpacing.xxLarge)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                val currentReport = report
                val currentLoadError = loadError
                if (currentLoadError != null) {
                    Text(
                        text = currentLoadError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(AapsSpacing.large),
                    )
                } else if (currentReport == null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(AapsSpacing.large))
                    }
                } else {
                    TuningContextCard(
                        selectedContext = selectedTuningContext,
                        onSelectContext = { ctx ->
                            selectedTuningContext = ctx
                            preferences.put(StringKey.AimiTuningContextSelection, ctx.name)
                        },
                        onPreview = { previewPlan = buildPlan() },
                        onApply = {
                            val plan = buildPlan()
                            when {
                                plan == null            -> Unit
                                !plan.isActionable -> scope.launch {
                                    snackbarHostState.showSnackbar(plan.blockedReason ?: noChangesMessage)
                                }
                                else                     -> applyPlan = plan
                            }
                        },
                    )
                }
            }
        }
    }

    previewPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { previewPlan = null },
            title = { Text(stringResource(R.string.aimi_tuning_dialog_preview_title)) },
            text = { Text(formatTuningDialogBody(plan)) },
            confirmButton = {
                TextButton(onClick = { previewPlan = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    applyPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!applying) applyPlan = null },
            title = { Text(stringResource(R.string.aimi_tuning_dialog_apply_title)) },
            text = { Text(formatTuningDialogBody(plan)) },
            confirmButton = {
                TextButton(
                    enabled = !applying,
                    onClick = {
                        applying = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val applied = TuningContextApplySupport.applyTuningPlan(plan, preferences, historyRepo)
                                val exportStatus = if (applied.appliedCount > 0) {
                                    TuningContextApplySupport.tryExportSettings(importExportPrefs, exportPasswordDataStore)
                                } else {
                                    TuningExportStatus.SKIPPED_DISABLED
                                }
                                applied.copy(exportStatus = exportStatus)
                            }
                            applying = false
                            applyPlan = null
                            applyResult = result
                        }
                    },
                ) {
                    Text(stringResource(R.string.aimi_tuning_apply_btn))
                }
            },
            dismissButton = {
                TextButton(enabled = !applying, onClick = { applyPlan = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    applyResult?.let { result ->
        AlertDialog(
            onDismissRequest = { applyResult = null },
            title = { Text(stringResource(R.string.aimi_tuning_result_title)) },
            text = { Text(buildTuningResultMessage(result)) },
            confirmButton = {
                TextButton(onClick = { applyResult = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

private val TUNING_CONTEXT_CHIPS = listOf(
    AimiTuningContext.MEAL_RISE to R.string.aimi_tuning_context_meal_rise,
    AimiTuningContext.HYPO_GUARD to R.string.aimi_tuning_context_hypo_guard,
    AimiTuningContext.HYPER_STABLE to R.string.aimi_tuning_context_hyper_stable,
    AimiTuningContext.AUTO_BALANCE to R.string.aimi_tuning_context_auto,
)

@Composable
private fun TuningContextCard(
    selectedContext: AimiTuningContext,
    onSelectContext: (AimiTuningContext) -> Unit,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_tuning_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.aimi_tuning_section_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                TUNING_CONTEXT_CHIPS.forEach { (ctx, labelRes) ->
                    FilterChip(
                        selected = selectedContext == ctx,
                        onClick = { onSelectContext(ctx) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small, Alignment.End),
            ) {
                OutlinedButton(onClick = onPreview) {
                    Text(stringResource(R.string.aimi_tuning_preview_btn))
                }
                Button(onClick = onApply) {
                    Text(stringResource(R.string.aimi_tuning_apply_btn))
                }
            }
        }
    }
}

@Composable
private fun tierLabel(tier: TuningStepTier): String = when (tier) {
    TuningStepTier.MICRO    -> stringResource(R.string.aimi_tuning_tier_micro)
    TuningStepTier.MODERATE -> stringResource(R.string.aimi_tuning_tier_moderate)
    TuningStepTier.STRONG   -> stringResource(R.string.aimi_tuning_tier_strong)
}

@Composable
private fun formatTuningDialogBody(plan: TuningPlan): String {
    val tierLine = stringResource(R.string.aimi_tuning_dialog_tier, tierLabel(plan.dominantTier))
    val blocked = plan.blockedReason
    return when {
        blocked != null        -> "$tierLine\n\n" + stringResource(R.string.aimi_tuning_blocked, blocked)
        plan.changes.isEmpty() -> "$tierLine\n\n" + stringResource(R.string.aimi_tuning_no_changes)
        else                   -> "$tierLine\n\n" + TuningContextApplySupport.formatPlanPreview(plan)
    }
}

@Composable
private fun buildTuningResultMessage(result: TuningApplyResult): String {
    if (result.appliedCount == 0) {
        return result.plan.blockedReason ?: stringResource(R.string.aimi_tuning_no_changes)
    }
    val sb = StringBuilder()
    sb.append(
        stringResource(
            R.string.aimi_tuning_result_summary,
            result.appliedCount,
            tierLabel(result.plan.dominantTier),
        ),
    ).append("\n\n")
    result.summaryLines.forEach { sb.append("• ").append(it).append('\n') }
    sb.append('\n')
    sb.append(
        when (result.exportStatus) {
            TuningExportStatus.SUCCESS                -> stringResource(R.string.aimi_tuning_result_export_ok)
            TuningExportStatus.SKIPPED_DISABLED        -> stringResource(R.string.aimi_tuning_result_export_skipped)
            TuningExportStatus.SKIPPED_NO_PASSWORD     -> stringResource(R.string.aimi_tuning_result_export_skipped)
            TuningExportStatus.SKIPPED_PASSWORD_EXPIRED -> stringResource(R.string.aimi_tuning_result_export_expired)
            TuningExportStatus.FAILED                  -> stringResource(R.string.aimi_tuning_result_export_failed)
        },
    )
    return sb.toString().trimEnd()
}
