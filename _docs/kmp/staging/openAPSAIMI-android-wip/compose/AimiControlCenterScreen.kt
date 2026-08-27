package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoActiveSessionUi
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoUiSupport
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun AimiControlCenterScreen(
    preferences: Preferences,
    tpoOrchestrator: TpoOrchestrator,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var preferenceRevision by remember { mutableIntStateOf(0) }
    // Persist remapped legacy tail floors (≤0.55 → 0.85) so CC detail/slider/stored pref stay aligned.
    LaunchedEffect(Unit) {
        if (PkpdSmbTailDamping.migrateLegacyStoredPreference(preferences)) {
            preferenceRevision++
        }
    }
    val currentT3cRuntime = remember(preferenceRevision) { loadLatestT3cRuntimeSnapshot() }
    val currentHarmoniaRuntime = remember(preferenceRevision) { loadLatestHarmoniaRuntimeSnapshot() }
    val currentSnapshot = remember(preferenceRevision, currentT3cRuntime, currentHarmoniaRuntime) {
        buildAimiControlCenterSnapshot(
            preferences = preferences,
            t3cRuntime = currentT3cRuntime,
            harmoniaRuntime = currentHarmoniaRuntime,
        )
    }
    val currentDraft = remember(preferenceRevision) { readAimiControlCenterDraft(preferences) }
    var protectionLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.protectionLevel) }
    var mealCaptureLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.mealCaptureLevel) }
    var stabilityLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.stabilityLevel) }
    var physioLevel by remember(preferenceRevision) { mutableIntStateOf(currentDraft.physioLevel) }
    var autonomyMode by remember(preferenceRevision) { mutableStateOf(currentDraft.autonomyMode) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    var showTpoRevertConfirm by remember { mutableStateOf(false) }
    var tpoUiRevision by remember { mutableIntStateOf(0) }

    val targetDraft = AimiControlCenterDraft(
        protectionLevel = protectionLevel,
        mealCaptureLevel = mealCaptureLevel,
        stabilityLevel = stabilityLevel,
        physioLevel = physioLevel,
        autonomyMode = autonomyMode,
    )
    val pendingChanges = remember(
        preferenceRevision,
        protectionLevel,
        mealCaptureLevel,
        stabilityLevel,
        physioLevel,
        autonomyMode,
    ) {
        buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )
    }
    val advisorRecommendations = remember(
        preferenceRevision,
        protectionLevel,
        mealCaptureLevel,
        stabilityLevel,
        physioLevel,
        autonomyMode,
    ) {
        buildAimiControlCenterAdvisorRecommendations(
            preferences = preferences,
            draft = targetDraft,
        )
    }
    val appliedMessage = stringResource(R.string.aimi_control_center_apply_done)
    val recommendationLoadedMessage = stringResource(R.string.aimi_control_center_advisor_loaded)
    val tpoRevertDoneMessage = stringResource(R.string.aimi_tpo_revert_done)
    val tpoActiveSessionUi = remember(tpoUiRevision) {
        TpoUiSupport.buildActiveSessionUi(
            session = tpoOrchestrator.currentSession(),
            nowMs = System.currentTimeMillis(),
        )
    }

    fun resetDraft() {
        protectionLevel = currentDraft.protectionLevel
        mealCaptureLevel = currentDraft.mealCaptureLevel
        stabilityLevel = currentDraft.stabilityLevel
        physioLevel = currentDraft.physioLevel
        autonomyMode = currentDraft.autonomyMode
    }

    fun loadDraft(draft: AimiControlCenterDraft) {
        protectionLevel = draft.protectionLevel
        mealCaptureLevel = draft.mealCaptureLevel
        stabilityLevel = draft.stabilityLevel
        physioLevel = draft.physioLevel
        autonomyMode = draft.autonomyMode
    }

    fun applyDraft() {
        applyAimiControlCenterPendingChanges(preferences, pendingChanges)
        preferenceRevision++
        scope.launch { snackbarHostState.showSnackbar(appliedMessage) }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(stringResource(R.string.aimi_control_center_confirm_apply_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.aimi_control_center_confirm_apply_body,
                        pendingChanges.changedFamilyCount,
                        pendingChanges.changedSettingsCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showApplyConfirm = false
                        applyDraft()
                    },
                ) {
                    Text(stringResource(R.string.aimi_control_center_apply_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showTpoRevertConfirm) {
        AlertDialog(
            onDismissRequest = { showTpoRevertConfirm = false },
            title = { Text(stringResource(R.string.aimi_tpo_revert_confirm_title)) },
            text = { Text(stringResource(R.string.aimi_tpo_revert_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTpoRevertConfirm = false
                        if (tpoOrchestrator.revertNow()) {
                            tpoUiRevision++
                            preferenceRevision++
                            scope.launch { snackbarHostState.showSnackbar(tpoRevertDoneMessage) }
                        }
                    },
                ) {
                    Text(stringResource(R.string.aimi_tpo_revert_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTpoRevertConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(R.string.aimi_control_center_title)) },
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
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    ControlCenterIntroCard(
                        familyCount = currentSnapshot.families.size,
                        expertFamilyCount = currentSnapshot.families.count { it.status == AimiProjectionStatus.ExpertPersonalized },
                        managedSettingCount = AimiBehaviorFamilyRegistry.totalManagedCount(),
                        expertSettingCount = AimiBehaviorFamilyRegistry.totalExpertCount(),
                    )

                    tpoActiveSessionUi?.let { sessionUi ->
                        TpoActiveSessionCard(
                            sessionUi = sessionUi,
                            onRevertNow = { showTpoRevertConfirm = true },
                        )
                    }

                    AdvisorRecommendationsCard(
                        recommendations = advisorRecommendations,
                        onLoadRecommendation = { recommendation ->
                            loadDraft(recommendation.targetDraft)
                            scope.launch { snackbarHostState.showSnackbar(recommendationLoadedMessage) }
                        },
                    )

                    currentSnapshot.families.forEach { family ->
                        val familyPlan = pendingChanges.familyPlan(family.id)
                        when (family.id) {
                            AimiBehaviorFamilyId.Protection -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = protectionLevelLabelForIndex(protectionLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { protectionLevel = currentDraft.protectionLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = protectionLevel,
                                        maxLevel = 4,
                                        onValueChange = { protectionLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.MealCapture -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = mealLevelLabelForIndex(mealCaptureLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { mealCaptureLevel = currentDraft.mealCaptureLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = mealCaptureLevel,
                                        maxLevel = 4,
                                        onValueChange = { mealCaptureLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Stability -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = stabilityLevelLabelForIndex(stabilityLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { stabilityLevel = currentDraft.stabilityLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = stabilityLevel,
                                        maxLevel = 4,
                                        onValueChange = { stabilityLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Physio -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = physioLevelLabelForIndex(physioLevel),
                                pendingPlan = familyPlan,
                                onResetFamily = { physioLevel = currentDraft.physioLevel },
                                control = {
                                    FamilyLevelSlider(
                                        value = physioLevel,
                                        maxLevel = 2,
                                        onValueChange = { physioLevel = it },
                                        leftAnchorResId = family.leftAnchorResId,
                                        rightAnchorResId = family.rightAnchorResId,
                                    )
                                },
                            )
                            AimiBehaviorFamilyId.Autonomy -> AimiFamilyCard(
                                snapshot = family,
                                targetLabelResId = autonomyMode.labelResId,
                                pendingPlan = familyPlan,
                                onResetFamily = { autonomyMode = currentDraft.autonomyMode },
                                control = {
                                    AutonomyModeSelector(
                                        selectedMode = autonomyMode,
                                        onModeSelected = { autonomyMode = it },
                                    )
                                },
                            )
                        }
                    }

                    PendingChangesCard(
                        pendingChanges = pendingChanges,
                        onResetDraft = ::resetDraft,
                        onApplyChanges = { showApplyConfirm = true },
                    )

                    ControlSectionCard(section = currentSnapshot.contextSection)
                    ControlSectionCard(section = currentSnapshot.sourceSection)

                    Text(
                        text = stringResource(R.string.aimi_control_center_footer_v2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TpoActiveSessionCard(
    sessionUi: TpoActiveSessionUi,
    onRevertNow: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_tpo_active_session_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (sessionUi.status == TpoSessionStatus.PENDING_LLM) {
                Text(
                    text = stringResource(R.string.aimi_tpo_active_session_pending),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    R.string.aimi_tpo_active_session_summary,
                    stringResource(sessionUi.packTitleResId),
                    sessionUi.tierLabel,
                    sessionUi.remainingMinutes,
                    sessionUi.changedKeyCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            sessionUi.deltaPreviewLines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sessionUi.extraChangeCount > 0) {
                Text(
                    text = stringResource(R.string.aimi_tpo_extra_changes, sessionUi.extraChangeCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sessionUi.status == TpoSessionStatus.ACTIVE) {
                Button(
                    onClick = onRevertNow,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.aimi_tpo_revert_now))
                }
            }
        }
    }
}

@Composable
private fun ControlCenterIntroCard(
    familyCount: Int,
    expertFamilyCount: Int,
    managedSettingCount: Int,
    expertSettingCount: Int,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_intro_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.aimi_control_center_intro_v2),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(R.string.aimi_control_center_preserve_note))
                ControlPill(text = stringResource(R.string.aimi_control_center_family_count, familyCount))
                ControlPill(text = stringResource(R.string.aimi_control_center_expert_count, expertFamilyCount))
                ControlPill(text = stringResource(R.string.aimi_control_center_managed_count, managedSettingCount))
                ControlPill(text = stringResource(R.string.aimi_control_center_expert_setting_count, expertSettingCount))
            }
        }
    }
}

@Composable
private fun AdvisorRecommendationsCard(
    recommendations: List<AimiControlCenterAdvisorRecommendation>,
    onLoadRecommendation: (AimiControlCenterAdvisorRecommendation) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_advisor_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.aimi_control_center_advisor_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (recommendations.isEmpty()) {
                Text(
                    text = stringResource(R.string.aimi_control_center_advisor_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                recommendations.forEach { recommendation ->
                    AdvisorRecommendationItem(
                        recommendation = recommendation,
                        onLoadRecommendation = { onLoadRecommendation(recommendation) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvisorRecommendationItem(
    recommendation: AimiControlCenterAdvisorRecommendation,
    onLoadRecommendation: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(
                text = stringResource(recommendation.titleResId),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(recommendation.bodyResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                recommendation.affectedFamilies.forEach { familyId ->
                    ControlPill(text = stringResource(familyId.titleResId()))
                }
            }
            TextButton(
                onClick = onLoadRecommendation,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.aimi_control_center_advisor_load))
            }
        }
    }
}

@Composable
private fun AimiFamilyCard(
    snapshot: AimiBehaviorFamilySnapshot,
    targetLabelResId: Int,
    pendingPlan: AimiFamilyWritebackPlan?,
    onResetFamily: () -> Unit,
    control: @Composable () -> Unit,
) {
    var expandedCurrent by rememberSaveable(snapshot.id.name) { mutableStateOf(false) }
    Card {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(snapshot.titleResId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(snapshot.questionResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.aimi_control_center_current_profile),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(snapshot.levelLabelResId),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(snapshot.status.labelResId))
                ControlPill(text = stringResource(R.string.aimi_control_center_confidence, (snapshot.confidence * 100).roundToInt()))
                ControlPill(text = stringResource(R.string.aimi_control_center_settings_count, snapshot.managedPreferenceCount))
                if (snapshot.expertPreferenceCount > 0) {
                    ControlPill(text = stringResource(R.string.aimi_control_center_expert_setting_count, snapshot.expertPreferenceCount))
                }
            }
            Text(
                text = stringResource(projectionStatusSummaryResId(snapshot.status)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            snapshot.t3cRuntime?.let { runtime ->
                T3cRuntimeCard(runtime = runtime)
            }
            snapshot.harmoniaRuntime?.let { runtime ->
                HarmoniaRuntimeCard(runtime = runtime)
            }

            if (pendingPlan != null) {
                Text(
                    text = stringResource(R.string.aimi_control_center_target_after_apply),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(targetLabelResId),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            control()

            if (pendingPlan != null) {
                PreviewImpactCard(
                    plan = pendingPlan,
                    onResetFamily = onResetFamily,
                )
            }

            TextButton(
                onClick = { expandedCurrent = !expandedCurrent },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    text = stringResource(
                        if (expandedCurrent) R.string.aimi_control_center_hide_details
                        else R.string.aimi_control_center_show_current_details,
                    ),
                )
            }
            if (expandedCurrent) {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    snapshot.details.forEach { detail ->
                        DetailRow(detail = detail)
                    }
                }
            }
        }
    }
}

@Composable
private fun HarmoniaRuntimeCard(runtime: AimiHarmoniaRuntimeSnapshot) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_harmonia_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.aimi_control_center_harmonia_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(runtime.status.labelResId))
                if (runtime.selectedForProduction) {
                    ControlPill(text = stringResource(R.string.aimi_control_center_harmonia_chip_selected))
                }
                if (!runtime.addsSmbAuthority) {
                    ControlPill(text = stringResource(R.string.aimi_control_center_harmonia_chip_no_smb_authority))
                }
            }
            if (runtime.status == AimiHarmoniaRuntimeStatus.Unavailable && runtime.details.isEmpty()) {
                Text(
                    text = stringResource(R.string.aimi_control_center_harmonia_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                runtime.details.forEach { detail ->
                    DetailRow(detail = detail)
                }
            }
        }
    }
}

@Composable
private fun T3cRuntimeCard(runtime: AimiT3cRuntimeSnapshot) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_t3c_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.aimi_control_center_t3c_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                ControlPill(text = stringResource(runtime.status.labelResId))
                ControlPill(text = stringResource(runtime.owner.labelResId))
                if (runtime.authorityApplied) {
                    ControlPill(text = stringResource(R.string.aimi_control_center_t3c_chip_authority_applied))
                }
                if (runtime.shadowOnly) {
                    ControlPill(text = stringResource(R.string.aimi_control_center_t3c_chip_shadow_only))
                }
            }
            if (runtime.status == AimiT3cRuntimeStatus.Unavailable && runtime.details.isEmpty()) {
                Text(
                    text = stringResource(R.string.aimi_control_center_t3c_unavailable_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                runtime.details.forEach { detail ->
                    DetailRow(detail = detail)
                }
            }
        }
    }
}

@Composable
private fun FamilyLevelSlider(
    value: Int,
    maxLevel: Int,
    onValueChange: (Int) -> Unit,
    leftAnchorResId: Int,
    rightAnchorResId: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        SliderWithButtons(
            value = value.toDouble(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maxLevel)) },
            valueRange = 0.0..maxLevel.toDouble(),
            step = 1.0,
            showValue = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(leftAnchorResId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(rightAnchorResId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutonomyModeSelector(
    selectedMode: AimiAutonomyMode,
    onModeSelected: (AimiAutonomyMode) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        AimiAutonomyMode.entries.forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onModeSelected(mode) },
                label = { Text(stringResource(mode.labelResId)) },
            )
        }
    }
}

@Composable
private fun PreviewImpactCard(
    plan: AimiFamilyWritebackPlan,
    onResetFamily: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.aimi_control_center_preview_impacts),
                    style = MaterialTheme.typography.titleSmall,
                )
                ControlPill(
                    text = stringResource(
                        R.string.aimi_control_center_preview_changes_count,
                        plan.changes.size,
                    ),
                )
            }
            Text(
                text = stringResource(plan.currentLabelResId) + " -> " + stringResource(plan.targetLabelResId),
                style = MaterialTheme.typography.bodyMedium,
            )
            plan.noteResId?.let { noteResId ->
                Text(
                    text = stringResource(noteResId),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.changes.forEach { change ->
                PreferenceChangeRow(change = change)
            }
            TextButton(
                onClick = onResetFamily,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.aimi_control_center_reset_family))
            }
        }
    }
}

@Composable
private fun PendingChangesCard(
    pendingChanges: AimiControlCenterPendingChanges,
    onResetDraft: () -> Unit,
    onApplyChanges: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_control_center_pending_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (pendingChanges.hasChanges) {
                Text(
                    text = stringResource(
                        R.string.aimi_control_center_pending_summary,
                        pendingChanges.changedFamilyCount,
                        pendingChanges.changedSettingsCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                ) {
                    Button(
                        onClick = onApplyChanges,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.aimi_control_center_apply_changes))
                    }
                    TextButton(
                        onClick = onResetDraft,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.aimi_control_center_reset_draft))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.aimi_control_center_no_pending_changes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreferenceChangeRow(change: AimiPreferenceChange) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        Text(
            text = stringResource(change.titleResId),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = descriptorText(change.before) + " -> " + descriptorText(change.after),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun ControlSectionCard(section: AimiControlSectionSnapshot) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(section.titleResId),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(section.summaryResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            section.details.forEach { detail ->
                DetailRow(detail = detail)
            }
        }
    }
}

@Composable
private fun DetailRow(detail: AimiControlDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = detailTitle(detail),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detailText(detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun detailTitle(detail: AimiControlDetail): String =
    if (detail.titleResId != 0) stringResource(detail.titleResId)
    else stringResource(R.string.aimi_control_center_unlabeled_preference)

@Composable
private fun detailText(detail: AimiControlDetail): String =
    when {
        detail.valueResId != null -> stringResource(detail.valueResId)
        !detail.valueText.isNullOrBlank() -> detail.valueText
        else -> stringResource(R.string.aimi_control_center_not_configured)
    }

@Composable
private fun descriptorText(value: AimiValueDescriptor): String =
    when {
        value.valueResId != null -> stringResource(value.valueResId)
        !value.valueText.isNullOrBlank() -> value.valueText
        else -> stringResource(R.string.aimi_control_center_not_configured)
    }

@Composable
private fun ControlPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small),
        )
    }
}

@StringRes
private fun AimiBehaviorFamilyId.titleResId(): Int =
    when (this) {
        AimiBehaviorFamilyId.Protection -> R.string.aimi_control_center_protection_title
        AimiBehaviorFamilyId.MealCapture -> R.string.aimi_control_center_meal_title
        AimiBehaviorFamilyId.Stability -> R.string.aimi_control_center_stability_title
        AimiBehaviorFamilyId.Physio -> R.string.aimi_control_center_physio_title
        AimiBehaviorFamilyId.Autonomy -> R.string.aimi_control_center_autonomy_title
    }
