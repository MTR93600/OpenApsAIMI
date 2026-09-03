package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.decimalPlaces
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.step
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.core.ui.compose.unitLabel
import app.aaps.core.ui.compose.valueFormat
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.ui.compose.preference.AdaptiveDoublePreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveSwitchPreferenceItem
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiRecommendation
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class PkpdSettingsLevel { SIMPLE, ADVANCED, EXPERT }

@Composable
fun PkpdSetupWizardDialog(
    preferences: Preferences,
    profileDiaHours: Double?,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedPreset by remember { mutableStateOf(PkpdInsulinPreset.ULTRA_FAST) }

    val title = when (step) {
        0 -> stringResource(R.string.aimi_pkpd_wizard_step_insulin_title)
        1 -> stringResource(R.string.aimi_pkpd_wizard_step_profile_title)
        else -> stringResource(R.string.aimi_pkpd_wizard_step_patience_title)
    }
    val body = when (step) {
        0 -> stringResource(R.string.aimi_pkpd_wizard_step_insulin_body)
        1 -> {
            val profileDia = profileDiaHours ?: 6.0
            stringResource(R.string.aimi_pkpd_wizard_step_profile_body, profileDia)
        }
        else -> stringResource(R.string.aimi_pkpd_wizard_step_patience_body)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (step == 0) {
                    PkpdPresetChipRow(
                        selectedPreset = selectedPreset,
                        onPresetSelected = { selectedPreset = it },
                        showCustom = false,
                        onApplyPreset = { preset -> selectedPreset = preset },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step == 0) {
                        applyPkpdInsulinPreset(preferences, selectedPreset)
                        // First run only: seed the settings the preset does not own, so a new
                        // user does not start on the raw key defaults. Both prudence sliders
                        // start at the neutral centre; same polarity, left cautious.
                        PkpdLearningPace.NORMAL.applyTo(preferences)
                        PkpdCorrectionPrudence.applyLevel(preferences, 0.5)
                        PkpdTailPrudence.applyLevel(preferences, 0.5)
                        preferences.put(BooleanKey.OApsAIMIPkpdEnabled, true)
                        step = 1
                    } else if (step == 1) {
                        step = 2
                    } else {
                        preferences.put(BooleanKey.OApsAIMIPkpdSetupWizardCompleted, true)
                        onCompleted()
                        onDismiss()
                    }
                },
            ) {
                Text(
                    if (step < 2) stringResource(R.string.aimi_pkpd_wizard_next)
                    else stringResource(R.string.aimi_pkpd_wizard_done),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (step == 0) {
                        onDismiss()
                    } else {
                        preferences.put(BooleanKey.OApsAIMIPkpdSetupWizardCompleted, true)
                        onCompleted()
                        onDismiss()
                    }
                },
            ) {
                Text(
                    if (step == 0) stringResource(android.R.string.cancel)
                    else stringResource(R.string.aimi_pkpd_wizard_skip),
                )
            }
        },
    )
}

@Composable
fun PkpdSimpleSettingsContent(
    preferences: Preferences,
    profileDiaHours: Double?,
    profilePeakMin: Double?,
    preferenceRevision: Int,
    onPreferenceRevisionBump: () -> Unit,
    recommendations: List<AimiRecommendation>,
    onApplyRecommendation: (AimiAction.PreferenceUpdate) -> Unit,
    onOpenAdvanced: () -> Unit,
    onRerunWizard: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: CoroutineScope,
) {
    var selectedPreset by remember(preferenceRevision) {
        mutableStateOf(detectPkpdInsulinPreset(preferences))
    }
    val presetAppliedMessage = stringResource(R.string.aimi_pkpd_preset_applied)
    val customHintMessage = stringResource(R.string.aimi_pkpd_custom_use_advanced)

    PkpdSimpleStatusCard(
        preferences = preferences,
        profileDiaHours = profileDiaHours,
        profilePeakMin = profilePeakMin,
    )

    recommendations.filter { it.domain == AimiDomain.Pkpd && it.action is AimiAction.PreferenceUpdate }
        .take(2)
        .forEach { rec ->
            PkpdAdvisorSuggestionCard(
                recommendation = rec,
                onApply = { onApplyRecommendation(it) },
            )
        }

    AdaptiveSwitchPreferenceItem(
        booleanKey = BooleanKey.OApsAIMIPkpdEnabled,
        title = ApsStrings.oaps_aimi_pkpd_enabled_title,
        summary = ApsStrings.aimi_pkpd_enabled_simple_summary,
    )

    Text(
        stringResource(R.string.aimi_pkpd_preset_section_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(R.string.aimi_pkpd_preset_simple_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PkpdPresetChipRow(
        selectedPreset = selectedPreset,
        onPresetSelected = { selectedPreset = it },
        showCustom = false,
        onApplyPreset = { preset ->
            if (preset == PkpdInsulinPreset.CUSTOM) {
                selectedPreset = PkpdInsulinPreset.CUSTOM
                scope.launch {
                    snackbarHostState.showSnackbar(customHintMessage)
                }
            } else {
                applyPkpdInsulinPreset(preferences, preset)
                selectedPreset = preset
                onPreferenceRevisionBump()
                scope.launch { snackbarHostState.showSnackbar(presetAppliedMessage) }
            }
        },
    )

    PkpdLabeledSlider(
        title = stringResource(R.string.aimi_pkpd_prudence_corrections_title),
        summary = stringResource(R.string.aimi_pkpd_prudence_corrections_summary),
        value = PkpdCorrectionPrudence.readLevel(preferences),
        valueRange = 0.0..1.0,
        leftLabel = stringResource(R.string.aimi_pkpd_prudence_left),
        rightLabel = stringResource(R.string.aimi_pkpd_prudence_right),
        onValueChange = {
            PkpdCorrectionPrudence.applyLevel(preferences, it)
            onPreferenceRevisionBump()
        },
    )

    // Same polarity as corrections: left = cautious (less delivery), right = allow more.
    PkpdLabeledSlider(
        title = stringResource(R.string.aimi_pkpd_tail_prudence_title),
        summary = stringResource(R.string.aimi_pkpd_tail_prudence_summary),
        value = PkpdTailPrudence.readUiLevel(preferences),
        valueRange = 0.0..1.0,
        leftLabel = stringResource(R.string.aimi_pkpd_tail_left),
        rightLabel = stringResource(R.string.aimi_pkpd_tail_right),
        onValueChange = {
            PkpdTailPrudence.applyUiLevel(preferences, it)
            onPreferenceRevisionBump()
        },
    )

    OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.aimi_pkpd_open_advanced))
    }

    TextButton(onClick = onRerunWizard, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.aimi_pkpd_rerun_wizard))
    }
}

@Composable
fun PkpdAdvancedSettingsContent(
    preferences: Preferences,
    preferenceRevision: Int,
    onPreferenceRevisionBump: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: CoroutineScope,
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    val syncDoneMessage = stringResource(R.string.aimi_pkpd_sync_done)
    val preset = remember(preferenceRevision) { detectPkpdInsulinPreset(preferences) }
    var learningPace by remember(preferenceRevision) { mutableStateOf(PkpdLearningPace.NORMAL.readFrom(preferences)) }

    Text(
        stringResource(R.string.aimi_pkpd_advanced_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(stringResource(R.string.aimi_pkpd_learning_pace_title), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        FilterChip(
            selected = learningPace == PkpdLearningPace.SLOW,
            onClick = {
                learningPace = PkpdLearningPace.SLOW
                PkpdLearningPace.SLOW.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(R.string.aimi_pkpd_pace_slow)) },
        )
        FilterChip(
            selected = learningPace == PkpdLearningPace.NORMAL,
            onClick = {
                learningPace = PkpdLearningPace.NORMAL
                PkpdLearningPace.NORMAL.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(R.string.aimi_pkpd_pace_normal)) },
        )
        FilterChip(
            selected = learningPace == PkpdLearningPace.FAST,
            onClick = {
                learningPace = PkpdLearningPace.FAST
                PkpdLearningPace.FAST.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(R.string.aimi_pkpd_pace_fast)) },
        )
    }

    PkpdReactiveDoubleSlider(
        key = DoubleKey.OApsAIMIPkpdInitialDiaH,
        titleResId = R.string.aimi_pkpd_starting_dia_title,
        preferenceRevision = preferenceRevision,
    )
    PkpdReactiveDoubleSlider(
        key = DoubleKey.OApsAIMIPkpdInitialPeakMin,
        titleResId = R.string.aimi_pkpd_starting_peak_title,
        preferenceRevision = preferenceRevision,
    )

    if (preset == PkpdInsulinPreset.CUSTOM) {
        Text(
            stringResource(R.string.aimi_pkpd_custom_bounds_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = AapsSpacing.small),
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdBoundsDiaMinH,
            titleResId = R.string.oaps_aimi_pkpd_dia_min_title,
            preferenceRevision = preferenceRevision,
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdBoundsDiaMaxH,
            titleResId = R.string.oaps_aimi_pkpd_dia_max_title,
            preferenceRevision = preferenceRevision,
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdBoundsPeakMinMin,
            titleResId = R.string.oaps_aimi_pkpd_peak_min_title,
            preferenceRevision = preferenceRevision,
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdBoundsPeakMinMax,
            titleResId = R.string.oaps_aimi_pkpd_peak_max_title,
            preferenceRevision = preferenceRevision,
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdAnchorDiaH,
            titleResId = R.string.oaps_aimi_pkpd_anchor_dia_title,
            preferenceRevision = preferenceRevision,
        )
        PkpdReactiveDoubleSlider(
            key = DoubleKey.OApsAIMIPkpdAnchorPeakMin,
            titleResId = R.string.oaps_aimi_pkpd_anchor_peak_title,
            preferenceRevision = preferenceRevision,
        )
    } else {
        Text(
            stringResource(R.string.aimi_pkpd_bounds_from_preset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AdaptiveSwitchPreferenceItem(
        booleanKey = BooleanKey.OApsAIMIPkpdStackAwareGuardB,
        title = ApsStrings.oaps_aimi_pkpd_stack_aware_guardb_title,
        summary = ApsStrings.oaps_aimi_pkpd_stack_aware_guardb_summary,
    )

    OutlinedButton(onClick = { showResetConfirm = true }) {
        Text(stringResource(R.string.aimi_pkpd_reset_to_profile_action))
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.aimi_pkpd_reset_confirm_title)) },
            text = { Text(stringResource(R.string.aimi_pkpd_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetPkpdLearnedStateToInitial(preferences)
                        onPreferenceRevisionBump()
                        showResetConfirm = false
                        scope.launch { snackbarHostState.showSnackbar(syncDoneMessage) }
                    },
                ) { Text(stringResource(R.string.aimi_pkpd_reset_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun PkpdExpertSettingsContent(preferenceRevision: Int) {
    ExpandableSection(title = stringResource(R.string.aimi_pkpd_expert_peak_governor), initiallyExpanded = false) {
        AdaptiveSwitchPreferenceItem(booleanKey = BooleanKey.OApsAIMIPeakGovernorEnabled)
        AdaptiveDoublePreferenceItem(doubleKey = DoubleKey.OApsAIMIPeakGovernorLearnedWeight)
    }

    ExpandableSection(title = stringResource(R.string.aimi_pkpd_expert_isf_fusion), initiallyExpanded = false) {
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIIsfFusionMinFactor,
            title = ApsStrings.oaps_aimi_isf_fusion_min_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxFactor,
            title = ApsStrings.oaps_aimi_isf_fusion_max_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIIsfFusionMaxChangePerTick,
            title = ApsStrings.oaps_aimi_isf_fusion_slope_title,
        )
    }

    ExpandableSection(title = stringResource(R.string.aimi_dyn_isf_trajectory_section_title), initiallyExpanded = false) {
        AdaptiveSwitchPreferenceItem(booleanKey = BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled)
        AdaptiveSwitchPreferenceItem(booleanKey = BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly)
        AdaptiveDoublePreferenceItem(doubleKey = DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction)
    }

    ExpandableSection(title = stringResource(R.string.aimi_pkpd_expert_smb_tail), initiallyExpanded = false) {
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMISmbTailThreshold,
            title = ApsStrings.oaps_aimi_smb_tail_threshold_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMISmbTailDamping,
            title = ApsStrings.oaps_aimi_smb_tail_damping_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMISmbExerciseDamping,
            title = ApsStrings.oaps_aimi_smb_exercise_damping_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMISmbLateFatDamping,
            title = ApsStrings.oaps_aimi_smb_late_fat_damping_title,
        )
    }

    ExpandableSection(title = stringResource(R.string.aimi_pkpd_section_expert), initiallyExpanded = false) {
        AdaptiveSwitchPreferenceItem(
            booleanKey = BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled,
            title = ApsStrings.oaps_aimi_pkpd_relief_enabled_title,
            summary = ApsStrings.oaps_aimi_pkpd_relief_enabled_summary,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
            title = ApsStrings.oaps_aimi_pkpd_relief_factor_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
            title = ApsStrings.oaps_aimi_redcarpet_restore_title,
        )
        AdaptiveSwitchPreferenceItem(
            booleanKey = BooleanKey.OApsAIMIIobSurveillanceGuard,
            title = ApsStrings.aimi_iob_surveillance_guard_title,
            summary = ApsStrings.aimi_iob_surveillance_guard_summary,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIPriorityMaxIobFactor,
            title = ApsStrings.oaps_aimi_priority_max_iob_factor_title,
        )
        AdaptiveDoublePreferenceItem(
            doubleKey = DoubleKey.OApsAIMIPriorityMaxIobExtraU,
            title = ApsStrings.oaps_aimi_priority_max_iob_extra_title,
        )
    }
}

@Composable
fun PkpdSimpleStatusCard(
    preferences: Preferences,
    profileDiaHours: Double?,
    profilePeakMin: Double?,
) {
    var showTechnical by remember { mutableStateOf(false) }
    val learnedDia = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)
    val learnedPeak = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
    val profileDia = profileDiaHours ?: learnedDia
    val profilePeak = profilePeakMin ?: preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin)
    val pkpdOn = preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
    val fasterInsulin = learnedDia < profileDia - 0.15 || learnedPeak < profilePeak - 8.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showTechnical = !showTechnical },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(AapsSpacing.medium)) {
            Text(
                stringResource(R.string.aimi_pkpd_status_card_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.aimi_pkpd_status_learned_format,
                    learnedDia,
                    learnedPeak,
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = AapsSpacing.small),
            )
            Text(
                stringResource(
                    R.string.aimi_pkpd_status_profile_format,
                    profileDia,
                    profilePeak,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    !pkpdOn -> stringResource(R.string.aimi_pkpd_status_learning_off)
                    fasterInsulin -> stringResource(R.string.aimi_pkpd_status_faster_insulin)
                    else -> stringResource(R.string.aimi_pkpd_status_learning_on)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AapsSpacing.small),
            )
            if (showTechnical) {
                PkpdTechnicalPeakDetails(preferences)
            } else {
                Text(
                    stringResource(R.string.aimi_pkpd_tap_for_technical),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AapsSpacing.extraSmall),
                )
            }
        }
    }
}

@Composable
private fun PkpdTechnicalPeakDetails(preferences: Preferences) {
    val peakPrior = preferences.get(DoubleKey.OApsAIMIPkpdStatePriorPeak)
    val peakEffective = preferences.get(DoubleKey.OApsAIMIPkpdStateEffectivePeak)
    val peakPhysio = preferences.get(DoubleKey.OApsAIMIPkpdStatePhysioPeak)
    val peakSite = preferences.get(DoubleKey.OApsAIMIPkpdStateSitePeak)
    val peakTraj = preferences.get(DoubleKey.OApsAIMIPkpdStateTrajectoryPeak)
    val dominantBranch = preferences.get(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.OApsAIMIPkpdStateDominantBranch)
    if (dominantBranch.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.aimi_pkpd_tap_g_detail,
                dominantBranch,
                peakEffective,
                peakPrior,
                peakPhysio,
                peakSite,
                peakTraj,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = AapsSpacing.small),
        )
    }
}

@Composable
private fun formatPkpdRecommendationDescription(recommendation: AimiRecommendation): String {
    val args = recommendation.descriptionArgs
    return when (args.size) {
        0 -> stringResource(recommendation.description)
        1 -> stringResource(recommendation.description, args[0])
        2 -> stringResource(recommendation.description, args[0], args[1])
        else -> stringResource(recommendation.description)
    }
}

@Composable
fun PkpdAdvisorSuggestionCard(
    recommendation: AimiRecommendation,
    onApply: (AimiAction.PreferenceUpdate) -> Unit,
) {
    val action = recommendation.action as? AimiAction.PreferenceUpdate ?: return
    val description = formatPkpdRecommendationDescription(recommendation)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(AapsSpacing.medium)) {
            Text(stringResource(recommendation.title), style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = AapsSpacing.extraSmall))
            Button(
                onClick = { onApply(action) },
                modifier = Modifier.padding(top = AapsSpacing.small),
            ) {
                Text(stringResource(R.string.aimi_pkpd_advisor_apply))
            }
        }
    }
}

@Composable
fun PkpdPresetChipRow(
    selectedPreset: PkpdInsulinPreset,
    onPresetSelected: (PkpdInsulinPreset) -> Unit,
    showCustom: Boolean,
    onApplyPreset: (PkpdInsulinPreset) -> Unit,
) {
    val chipScroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(chipScroll),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        PresetChip(
            label = stringResource(R.string.aimi_pkpd_preset_ultra_fast),
            selected = selectedPreset == PkpdInsulinPreset.ULTRA_FAST,
            onClick = {
                onPresetSelected(PkpdInsulinPreset.ULTRA_FAST)
                onApplyPreset(PkpdInsulinPreset.ULTRA_FAST)
            },
        )
        PresetChip(
            label = stringResource(R.string.aimi_pkpd_preset_rapid),
            selected = selectedPreset == PkpdInsulinPreset.RAPID,
            onClick = {
                onPresetSelected(PkpdInsulinPreset.RAPID)
                onApplyPreset(PkpdInsulinPreset.RAPID)
            },
        )
        PresetChip(
            label = stringResource(R.string.aimi_pkpd_preset_standard),
            selected = selectedPreset == PkpdInsulinPreset.STANDARD,
            onClick = {
                onPresetSelected(PkpdInsulinPreset.STANDARD)
                onApplyPreset(PkpdInsulinPreset.STANDARD)
            },
        )
        if (showCustom) {
            PresetChip(
                label = stringResource(R.string.aimi_pkpd_preset_custom),
                selected = selectedPreset == PkpdInsulinPreset.CUSTOM,
                onClick = {
                    onPresetSelected(PkpdInsulinPreset.CUSTOM)
                    onApplyPreset(PkpdInsulinPreset.CUSTOM)
                },
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
fun PkpdLabeledSlider(
    title: String,
    summary: String?,
    value: Double,
    valueRange: ClosedFloatingPointRange<Double>,
    leftLabel: String,
    rightLabel: String,
    onValueChange: (Double) -> Unit,
) {
    var local by remember { mutableDoubleStateOf(value) }
    var userEdited by remember { mutableStateOf(false) }
    LaunchedEffect(value) { local = value }
    LaunchedEffect(Unit) {
        snapshotFlow { local }
            .debounce(350)
            .collect { committed ->
                if (userEdited) {
                    onValueChange(committed)
                    userEdited = false
                }
            }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = AapsSpacing.extraSmall)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall)
        }
        SliderWithButtons(
            value = local,
            onValueChange = {
                userEdited = true
                local = it
            },
            valueRange = valueRange,
            step = 0.05,
            showValue = false,
        )
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = AapsSpacing.small),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun PkpdReactiveDoubleSlider(
    key: DoubleKey,
    titleResId: Int,
    preferenceRevision: Int,
) {
    val preferences = LocalPreferences.current
    val summaryRes = stringResourceOrNull(key.summary)
    val unitType = key.unitType
    val decimalPlaces = unitType.decimalPlaces()
    val step = unitType.step()
    val valueFormatRef = unitType.valueFormat()
    val valueFormat = NumberFormat.withDecimals(decimalPlaces)
    val unitLabelRef = unitType.unitLabel()

    var local by remember(key) { mutableDoubleStateOf(preferences.get(key)) }
    LaunchedEffect(key, preferenceRevision) {
        local = preferences.get(key)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AapsSpacing.extraSmall),
    ) {
        Text(stringResource(titleResId), style = MaterialTheme.typography.titleSmall)
        summaryRes?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SliderWithButtons(
            value = local,
            onValueChange = {
                val clamped = it.coerceIn(key.min, key.max)
                local = clamped
                preferences.put(key, clamped)
            },
            valueRange = key.min..key.max,
            step = step,
            showValue = true,
            valueFormatRef = valueFormatRef,
            valueFormat = valueFormat,
            unitLabel = unitLabelRef,
            dialogLabel = stringResource(titleResId),
            dialogSummary = summaryRes,
        )
    }
}
