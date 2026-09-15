package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetrics
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorSeverity
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiAdvisorService
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiRecommendation
import app.aaps.plugins.aps.openAPSAIMI.advisor.buildAimiBehaviorCausalInsights
import app.aaps.plugins.aps.openAPSAIMI.advisor.buildAimiFamilyBridgeSuggestions
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.RecursiveBeliefExportReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningApplyResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextApplySupport
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningExportStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningPlan
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningPreferenceLabels
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiRecommendationCard
import app.aaps.plugins.aps.openAPSAIMI.compose.ProviderDropdown
import app.aaps.plugins.aps.openAPSAIMI.compose.applyPkpdPreferenceUpdate
import app.aaps.plugins.aps.openAPSAIMI.compose.readPreferenceValueAsString
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * The AIMI Profile Advisor screen.
 *
 * This is the finished Compose port of the old `AimiProfileAdvisorActivity`, which is gone: header,
 * tuning context, metrics, recommendations and their apply flow, the three read-only runtime-history
 * cards, the brain / OREF / AI coach cards, the quick actions and the footer. Two sections of the
 * old screen were deliberately not ported - the support ZIP flow, superseded by
 * [app.aaps.plugins.aps.openAPSAIMI.advisor.compose.AimiSupportPackageScreen], and the behavior
 * causal map, which had no live callers. See `_docs/kmp/AIMI_PORT_STATE.md` for the reasoning.
 *
 * This function owns loading the [AdvisorReport], because every section reads from it.
 */

/**
 * Runs one history load and answers null when it fails, so one unreadable section cannot hide the
 * other cards.
 *
 * Cancellation is not a load failure - the screen was left - so it is rethrown instead of being
 * turned into "no data". A bare `runCatching` would swallow it and break cancellation.
 */
private inline fun <T> loadOrNull(load: () -> T): T? =
    runCatching(load).getOrElse { if (it is CancellationException) throw it else null }

@Composable
fun AimiProfileAdvisorScreen(
    preferences: Preferences,
    advisorService: AimiAdvisorService,
    historyRepo: AdvisorHistoryRepository,
    importExportPrefs: ImportExportPrefs,
    exportPasswordDataStore: ExportPasswordDataStore,
    aiCoachingService: AiCoachingService,
    rh: ResourceHelper,
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

    // The displayed recommendations are held separately from the report: after an Apply the list is
    // re-filtered in place instead of regenerating the whole (heavy) report.
    var recommendations by remember { mutableStateOf<List<AimiRecommendation>>(emptyList()) }
    var pendingRecommendation by remember { mutableStateOf<AimiAction.PreferenceUpdate?>(null) }
    var applyingRecommendation by remember { mutableStateOf(false) }

    // The three runtime-history cards read the AIMI decisions JSONL, which is slower than the report
    // and can fail on its own. They load separately so a missing log never delays the report. A null
    // result is a valid "nothing to show" answer, so a separate flag carries the loading state.
    var rbtExport by remember { mutableStateOf<JsonObject?>(null) }
    var t3cHistory by remember { mutableStateOf<T3cRuntimeHistorySummary?>(null) }
    var harmoniaHistory by remember { mutableStateOf<HarmoniaRuntimeHistorySummary?>(null) }
    var historyLoaded by remember { mutableStateOf(false) }

    // The AI Coach card loads independently of the report above: with a key configured it makes a
    // real network call, which must not delay or hide the rest of the screen if it is slow or fails.
    var coachAdvice by remember { mutableStateOf<String?>(null) }
    var coachLoading by remember { mutableStateOf(false) }
    var coachError by remember { mutableStateOf<String?>(null) }

    // The model selector (header) writes this preference; the coach effect below is keyed on it so
    // picking a different provider re-fetches advice without a full-screen reload (D4 - replaces the
    // staged Activity's recreate()).
    var selectedProvider by remember { mutableStateOf(preferences.get(StringKey.AimiAdvisorProvider)) }
    var showModelSelector by remember { mutableStateOf(false) }

    // Basal proposal (header): preview/export only, see requestBasalProposal() below. A null value
    // means "no proposal to show"; it never writes anything, so there is no separate "applied" state.
    var basalProposalLoading by remember { mutableStateOf(false) }
    var basalProposal by remember { mutableStateOf<AimiAdvisorService.BasalProfileProposal?>(null) }

    val noChangesMessage = stringResource(R.string.aimi_tuning_no_changes)
    val errorPrefix = stringResource(R.string.aimi_adv_error_prefix)
    val errorOom = stringResource(R.string.aimi_adv_error_oom)
    // The confirm dialog applies exactly one preference key, so the count is one. Derive it from
    // the apply result instead if this dialog ever applies a batch.
    val recommendationAppliedMessage = stringResource(R.string.aimi_adv_success_msg, 1)
    val recommendationNoChangeMessage = stringResource(R.string.aimi_adv_no_change_msg)
    val basalGeneratingMessage = stringResource(R.string.aimi_adv_basal_generating_msg)
    val basalFailedMessage = stringResource(R.string.aimi_adv_basal_failed_msg)
    val basalShareSubject = stringResource(R.string.aimi_adv_basal_share_subject)
    val basalShareChooserTitle = stringResource(R.string.aimi_adv_basal_share_chooser_title)

    LaunchedEffect(Unit) {
        try {
            // history feeds the 48h-cooldown filter on recommendations a later sub-lot renders from
            // this same report; assetContext lets the OREF pipeline load its bundled ML asset. Both
            // silently degrade the report (not a crash) if omitted, so pass them like the original did.
            val loaded = withContext(Dispatchers.IO) {
                advisorService.generateReport(
                    history = historyRepo.getRecentActions(10),
                    assetContext = context,
                )
            }
            report = loaded
            recommendations = loaded.recommendations
        } catch (t: Throwable) {
            loadError = when (t) {
                is OutOfMemoryError -> errorOom
                else                -> "$errorPrefix${t.localizedMessage ?: t.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // One load at a time, each on its own: a broken line in one section must not hide the
            // other two cards.
            rbtExport = loadOrNull { RecursiveBeliefExportReader.loadLastExport() }
            t3cHistory = loadOrNull { T3cRuntimeHistoryReader.summarizeLast24Hours() }
            harmoniaHistory = loadOrNull { HarmoniaRuntimeHistoryReader.summarizeLast24Hours() }
        }
        historyLoaded = true
    }

    // Keyed on `report`, not `Unit`: the coach needs the report's AdvisorContext, so this effect
    // waits for the first LaunchedEffect above to finish before it starts. Also keyed on
    // `selectedProvider` (D4): picking a different model in the header re-runs this effect instead
    // of needing a full-screen reload.
    LaunchedEffect(report, selectedProvider) {
        val currentReport = report ?: return@LaunchedEffect
        coachLoading = true
        coachError = null
        try {
            val advisorCtx = currentReport.advisorContext
            val providerName = selectedProvider
            val provider = when (providerName.uppercase()) {
                "GEMINI"   -> AiCoachingService.Provider.GEMINI
                "DEEPSEEK" -> AiCoachingService.Provider.DEEPSEEK
                "CLAUDE"   -> AiCoachingService.Provider.CLAUDE
                else       -> AiCoachingService.Provider.OPENAI
            }
            val activeKey = when (provider) {
                AiCoachingService.Provider.GEMINI   -> preferences.get(StringKey.AimiAdvisorGeminiKey)
                AiCoachingService.Provider.DEEPSEEK -> preferences.get(StringKey.AimiAdvisorDeepSeekKey)
                AiCoachingService.Provider.CLAUDE   -> preferences.get(StringKey.AimiAdvisorClaudeKey)
                else                                 -> preferences.get(StringKey.AimiAdvisorOpenAIKey)
            }
            coachAdvice = if (activeKey.isBlank()) {
                // No key configured: this is what most users see. Same deterministic summary as the
                // report's own text, plus a note asking for a key - never a network call.
                val basicAnalysis = advisorService.generatePlainTextAnalysis(advisorCtx, currentReport, insightContext = context)
                val note = rh.gs(R.string.aimi_coach_placeholder, provider.name)
                rh.gs(R.string.aimi_coach_basic_with_note, basicAnalysis, note)
            } else {
                // familyBridgeSuggestions/causalInsights are prompt input only - never rendered as a
                // card - and stay inside runCatching like the staged code, since either can throw on
                // unexpected preference combinations and must not break the coach call.
                val causalInsights = runCatching {
                    val familyBridgeSuggestions = buildAimiFamilyBridgeSuggestions(preferences, currentReport.metrics)
                    buildAimiBehaviorCausalInsights(preferences, currentReport.metrics, familyBridgeSuggestions)
                }.getOrDefault(emptyList())
                val history = withContext(Dispatchers.IO) { historyRepo.getRecentActions(7) }
                val richOref = preferences.get(BooleanKey.OApsAIMIAdvisorLlmRichOref)
                aiCoachingService.fetchAdvice(
                    androidContext = context,
                    context = advisorCtx,
                    report = currentReport,
                    apiKey = activeKey,
                    provider = provider,
                    history = history,
                    includeRichOref = richOref,
                    causalInsights = causalInsights,
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val detail = if (t is OutOfMemoryError) errorOom else (t.localizedMessage ?: t.javaClass.simpleName)
            coachError = rh.gs(R.string.aimi_coach_error_detail, rh.gs(R.string.aimi_coach_error), detail)
        } finally {
            coachLoading = false
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

    // Preview/export only - see the KDoc on the `basalProposal` state above. Uses loadOrNull like the
    // history cards: a failure becomes a generic snackbar rather than a crash, since this is a
    // secondary, opt-in action, not something the rest of the screen depends on.
    fun requestBasalProposal() {
        if (basalProposalLoading) return
        basalProposalLoading = true
        scope.launch { snackbarHostState.showSnackbar(basalGeneratingMessage) }
        scope.launch {
            val proposal = withContext(Dispatchers.IO) {
                loadOrNull { advisorService.generateBasalProfileProposal(periodDays = 7) }
            }
            basalProposalLoading = false
            if (proposal != null) {
                basalProposal = proposal
            } else {
                snackbarHostState.showSnackbar(basalFailedMessage)
            }
        }
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
                                contentDescription = stringResource(CoreUiR.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showModelSelector = true }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.aimi_advisor_model_title),
                            )
                        }
                        IconButton(onClick = { requestBasalProposal() }, enabled = !basalProposalLoading) {
                            Icon(
                                imageVector = Icons.Filled.Science,
                                contentDescription = stringResource(R.string.aimi_adv_basal_dialog_title),
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
                    DashboardHeader(
                        periodLabel = currentReport.metrics.periodLabel,
                        overallScore = currentReport.overallScore,
                        overallSeverity = currentReport.overallSeverity,
                    )

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

                    if (historyLoaded) {
                        RecursiveBeliefUnfoldCard(preferences = preferences, lastExport = rbtExport)
                        T3cRuntimeHistoryCard(t3cHistory)
                        HarmoniaRuntimeHistoryCard(harmoniaHistory)
                    } else {
                        Text(
                            text = stringResource(R.string.aimi_adv_loading_details),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AapsSpacing.large),
                        )
                    }

                    MetricsCard(currentReport.metrics)

                    val observationRecs = recommendations.filter { it.domain != AimiDomain.Pkpd }
                    val pkpdRecs = recommendations.filter { it.domain == AimiDomain.Pkpd }

                    if (observationRecs.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.aimi_adv_section_obs))
                        observationRecs.forEach { rec ->
                            AimiRecommendationCard(
                                recommendation = rec,
                                applyLabel = ApsStrings.aimi_adv_apply_btn,
                                showPriority = true,
                                onApply = { pendingRecommendation = it },
                            )
                        }
                    }

                    if (pkpdRecs.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.aimi_adv_section_pkpd))
                        pkpdRecs.forEach { rec ->
                            AimiRecommendationCard(
                                recommendation = rec,
                                applyLabel = ApsStrings.aimi_adv_apply_btn,
                                showPriority = true,
                                onApply = { pendingRecommendation = it },
                            )
                        }
                    }

                    SectionHeader(stringResource(R.string.aimi_adv_section_brain))
                    CognitiveBrainCard(currentReport.advisorContext.prefs.unifiedReactivityFactor)

                    currentReport.orefAnalysis?.let { oref ->
                        SectionHeader(stringResource(R.string.aimi_adv_section_oref))
                        OrefAnalysisCard(oref = oref, rh = rh)
                    }

                    SectionHeader(stringResource(R.string.aimi_adv_section_coach))
                    AiCoachCard(loading = coachLoading, advice = coachAdvice, error = coachError)

                    DashboardFooter(advisorService.formatTime(currentReport.generatedAt))
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

    pendingRecommendation?.let { action ->
        AlertDialog(
            onDismissRequest = { if (!applyingRecommendation) pendingRecommendation = null },
            title = { Text(stringResource(R.string.aimi_adv_apply_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    Text(stringResource(R.string.aimi_adv_apply_dialog_prefix))
                    Text(
                        stringResource(
                            R.string.aimi_adv_apply_dialog_change,
                            TuningPreferenceLabels.shortLabel(action.key),
                            TuningPreferenceLabels.formatValue(action.newValue),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(action.reason, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !applyingRecommendation,
                    onClick = {
                        applyingRecommendation = true
                        scope.launch {
                            // SharedPreferences + Gson, so never on the main thread.
                            val applied = withContext(Dispatchers.IO) {
                                // Read the value the setting really holds before overwriting it. The
                                // history is fed to the AI Coach, so a placeholder would mislead it.
                                val oldValue = readPreferenceValueAsString(preferences, action.key)
                                val ok = applyPkpdPreferenceUpdate(preferences, action)
                                if (ok) {
                                    historyRepo.logAction(
                                        AdvisorHistoryRepository.ActionType.PREFERENCE_CHANGE,
                                        action.key.key,
                                        action.reason,
                                        oldValue,
                                        action.newValue.toString(),
                                    )
                                }
                                ok
                            }
                            if (applied) {
                                val stillVisible = withContext(Dispatchers.IO) {
                                    val history = historyRepo.getRecentActions(10)
                                    recommendations.filter { advisorService.isRecommendationVisible(it, history) }
                                }
                                recommendations = stillVisible
                            }
                            applyingRecommendation = false
                            pendingRecommendation = null
                            snackbarHostState.showSnackbar(
                                if (applied) recommendationAppliedMessage else recommendationNoChangeMessage,
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.aimi_adv_apply_btn))
                }
            },
            dismissButton = {
                TextButton(enabled = !applyingRecommendation, onClick = { pendingRecommendation = null }) {
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

    if (showModelSelector) {
        AlertDialog(
            onDismissRequest = { showModelSelector = false },
            title = { Text(stringResource(R.string.aimi_advisor_model_title)) },
            text = {
                ProviderDropdown(
                    selected = selectedProvider.uppercase(),
                    onSelect = { provider ->
                        selectedProvider = provider
                        preferences.put(StringKey.AimiAdvisorProvider, provider)
                        showModelSelector = false
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showModelSelector = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    basalProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { basalProposal = null },
            title = { Text(stringResource(R.string.aimi_adv_basal_dialog_title)) },
            text = { Text(formatBasalProposalPreview(proposal)) },
            confirmButton = {
                TextButton(onClick = {
                    shareBasalProposal(
                        context = context,
                        content = advisorService.exportBasalProfileProposalText(proposal),
                        subject = basalShareSubject,
                        chooserTitle = basalShareChooserTitle,
                    )
                    basalProposal = null
                }) {
                    Text(stringResource(R.string.aimi_adv_basal_export_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { basalProposal = null }) {
                    Text(stringResource(android.R.string.cancel))
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = AapsSpacing.medium),
    )
}

/**
 * The headline numbers of the report. Each cell keeps the identity colour it had in the parked
 * Activity, mapped to a theme token so it follows light/dark mode.
 */
@Composable
private fun MetricsCard(metrics: AdvisorMetrics) {
    val percent = CoreUiR.string.format_percent
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.aimi_adv_metric_tir),
                    value = stringResource(percent, (metrics.tir70_180 * 100).roundToInt()),
                    valueColor = AapsTheme.generalColors.bgInRange,
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.aimi_adv_metric_tdd),
                    value = stringResource(CoreUiR.string.units_format_insulin_int, metrics.tdd.roundToInt()),
                    valueColor = AapsTheme.generalColors.activeInsulinText,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.aimi_adv_metric_gmi),
                    value = stringResource(R.string.aimi_adv_metric_value_gmi, metrics.gmi),
                    valueColor = AapsTheme.generalColors.bgHigh,
                )
                MetricCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.aimi_adv_metric_hypo54),
                    value = stringResource(percent, (metrics.timeBelow54 * 100).roundToInt()),
                    valueColor = AapsTheme.generalColors.bgLow,
                )
            }
            val todayTir = metrics.todayTir
            val todayTdd = metrics.todayTdd
            if (todayTir != null || todayTdd != null) {
                val missing = stringResource(R.string.aimi_adv_metric_value_missing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    MetricCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.aimi_adv_metric_today_tir),
                        value = todayTir?.let { stringResource(percent, (it * 100).roundToInt()) } ?: missing,
                        valueColor = AapsTheme.generalColors.bgInRange,
                    )
                    MetricCell(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.aimi_adv_metric_today_tdd),
                        value = todayTdd?.let { stringResource(CoreUiR.string.format_insulin_units1, it) } ?: missing,
                        valueColor = AapsTheme.generalColors.activeInsulinText,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCell(
    modifier: Modifier,
    label: String,
    value: String,
    valueColor: Color,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
    ) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall, color = valueColor)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Sub-lot 5/5: the report's title, period and overall score. Reads only
 * [AdvisorReport.metrics]`.periodLabel` and [AdvisorReport.overallScore] - `overallAssessment` and
 * `summary` are never referenced anywhere in the staged Activity either, so they stay un-ported.
 *
 * The score pill is coloured by [AdvisorReport.overallSeverity] instead of the staged file's
 * hardcoded green, following this project's colour-by-state convention.
 */
@Composable
private fun DashboardHeader(
    periodLabel: String,
    overallScore: Double,
    overallSeverity: AdvisorSeverity,
) {
    val severityColor = when (overallSeverity) {
        AdvisorSeverity.Good     -> AapsTheme.generalColors.statusNormal
        AdvisorSeverity.Warning  -> AapsTheme.generalColors.statusWarning
        AdvisorSeverity.Critical -> AapsTheme.generalColors.statusCritical
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.aimi_adv_report_weekly), style = MaterialTheme.typography.titleLarge)
            Text(
                text = periodLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = severityColor.copy(alpha = 0.15f),
        ) {
            Text(
                text = stringResource(R.string.aimi_adv_score_label, overallScore),
                style = MaterialTheme.typography.labelLarge,
                color = severityColor,
                modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small),
            )
        }
    }
}

/** Sub-lot 5/5: the report's generation time, at the bottom of the screen. */
@Composable
private fun DashboardFooter(generatedAtText: String) {
    Text(
        text = stringResource(R.string.aimi_adv_generated_footer, generatedAtText),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AapsSpacing.medium),
    )
}

/**
 * The basal-proposal dialog body. [AimiAdvisorService.generateBasalProfileProposal] writes nothing
 * and applies nothing - this only formats its result for display, matching the staged
 * `buildBasalProposalPreview`.
 */
@Composable
private fun formatBasalProposalPreview(proposal: AimiAdvisorService.BasalProfileProposal): String {
    if (proposal.rows.isEmpty()) return stringResource(R.string.aimi_adv_basal_no_profile_msg)
    val disclaimer = stringResource(R.string.aimi_adv_basal_disclaimer)
    val strategyLine = stringResource(R.string.aimi_adv_basal_strategy_line, proposal.strategy)
    val factorLine = stringResource(R.string.aimi_adv_basal_factor_line, proposal.scalingFactor)
    val rationaleLine = stringResource(R.string.aimi_adv_basal_rationale_line, proposal.rationale)
    val previewHeading = stringResource(R.string.aimi_adv_basal_preview_heading)
    val rowLines = proposal.rows.take(6).map { row ->
        val deltaPct = if (row.current > 0.0) ((row.proposed / row.current) - 1.0) * 100.0 else 0.0
        stringResource(R.string.aimi_adv_basal_row_line, row.hour, row.current, row.proposed, deltaPct)
    }
    return "$disclaimer\n$strategyLine\n$factorLine\n$rationaleLine\n\n$previewHeading\n${rowLines.joinToString("\n")}"
}

/**
 * Shares the basal-proposal export text via `ACTION_SEND`, exactly like the staged
 * `shareBasalProposal`. This is the only side effect the basal-proposal feature has - no
 * preference, profile or therapy write.
 */
private fun shareBasalProposal(context: Context, content: String, subject: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, content)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
