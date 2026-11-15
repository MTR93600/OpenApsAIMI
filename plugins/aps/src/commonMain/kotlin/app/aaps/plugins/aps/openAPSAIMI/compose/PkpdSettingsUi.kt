package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Modifier
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.SliderWithButtons
import app.aaps.core.ui.compose.preference.AdaptiveDoublePreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveIntPreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveSwitchPreferenceItem
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiRecommendation
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdUpdateReason
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningSummary
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdProfileInsulinMemory
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdTickTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PkpdSettingsLevel { SIMPLE, ADVANCED, EXPERT }

@Composable
fun PkpdSetupWizardDialog(
    preferences: Preferences,
    profileDiaHours: Double?,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
) {
    // Two steps: the profile insulin (the source of the learner), then patience.
    var step by remember { mutableIntStateOf(0) }

    val title = when (step) {
        0 -> stringResource(ApsStrings.aimi_pkpd_wizard_step_profile_title)
        else -> stringResource(ApsStrings.aimi_pkpd_wizard_step_patience_title)
    }
    val body = when (step) {
        0 -> {
            val profileDia = profileDiaHours ?: 6.0
            stringResource(ApsStrings.aimi_pkpd_wizard_step_profile_body, profileDia)
        }
        else -> stringResource(ApsStrings.aimi_pkpd_wizard_step_patience_body)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step == 0) {
                        // First run only: seed the user settings, so a new user does not start on
                        // the raw key defaults. The learning pace is the default one (FASTER). Both
                        // prudence sliders start at the neutral centre; same polarity, left cautious.
                        // The DIA and peak come from the profile insulin, there is nothing to pick.
                        PkpdLearningPace.FASTER.applyTo(preferences)
                        PkpdCorrectionPrudence.applyLevel(preferences, 0.5)
                        PkpdTailPrudence.applyLevel(preferences, 0.5)
                        preferences.put(BooleanKey.OApsAIMIPkpdEnabled, true)
                        step = 1
                    } else {
                        preferences.put(BooleanNonKey.OApsAIMIPkpdSetupWizardCompleted, true)
                        onCompleted()
                        onDismiss()
                    }
                },
            ) {
                Text(
                    if (step < 1) stringResource(ApsStrings.aimi_pkpd_wizard_next)
                    else stringResource(ApsStrings.aimi_pkpd_wizard_done),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (step == 0) {
                        onDismiss()
                    } else {
                        preferences.put(BooleanNonKey.OApsAIMIPkpdSetupWizardCompleted, true)
                        onCompleted()
                        onDismiss()
                    }
                },
            ) {
                Text(
                    if (step == 0) stringResource(CoreUiStrings.cancel)
                    else stringResource(ApsStrings.aimi_pkpd_wizard_skip),
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
) {
    PkpdSimpleStatusCard(
        preferences = preferences,
        profileDiaHours = profileDiaHours,
        profilePeakMin = profilePeakMin,
    )

    recommendations.filter { it.domain == AimiDomain.Pkpd && it.action is AimiAction.PreferenceUpdate }
        .take(2)
        .forEach { rec ->
            AimiRecommendationCard(
                recommendation = rec,
                onApply = { onApplyRecommendation(it) },
            )
        }

    PkpdScreenLayout.simpleFieldSections.forEach { PkpdDeclaredFields(it) }

    PkpdLabeledSlider(
        title = stringResource(ApsStrings.aimi_pkpd_prudence_corrections_title),
        summary = stringResource(ApsStrings.aimi_pkpd_prudence_corrections_summary),
        value = PkpdCorrectionPrudence.readLevel(preferences),
        valueRange = 0.0..1.0,
        leftLabel = stringResource(ApsStrings.aimi_pkpd_prudence_left),
        rightLabel = stringResource(ApsStrings.aimi_pkpd_prudence_right),
        onValueChange = {
            PkpdCorrectionPrudence.applyLevel(preferences, it)
            onPreferenceRevisionBump()
        },
    )

    // Same polarity as corrections: left = cautious (less delivery), right = allow more.
    PkpdLabeledSlider(
        title = stringResource(ApsStrings.aimi_pkpd_tail_prudence_title),
        summary = stringResource(ApsStrings.aimi_pkpd_tail_prudence_summary),
        value = PkpdTailPrudence.readUiLevel(preferences),
        valueRange = 0.0..1.0,
        leftLabel = stringResource(ApsStrings.aimi_pkpd_tail_left),
        rightLabel = stringResource(ApsStrings.aimi_pkpd_tail_right),
        onValueChange = {
            PkpdTailPrudence.applyUiLevel(preferences, it)
            onPreferenceRevisionBump()
        },
    )

    OutlinedButton(onClick = onOpenAdvanced, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(ApsStrings.aimi_pkpd_open_advanced))
    }

    TextButton(onClick = onRerunWizard, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(ApsStrings.aimi_pkpd_rerun_wizard))
    }
}

@Composable
fun PkpdAdvancedSettingsContent(
    preferences: Preferences,
    preferenceRevision: Int,
    onPreferenceRevisionBump: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    var showResetConfirm by remember { mutableStateOf(false) }
    val syncDoneMessage = stringResource(ApsStrings.aimi_pkpd_sync_done)
    var learningPace by remember(preferenceRevision) { mutableStateOf(PkpdLearningPace.FASTER.readFrom(preferences)) }

    Text(
        stringResource(ApsStrings.aimi_pkpd_advanced_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(stringResource(ApsStrings.aimi_pkpd_learning_pace_title), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        FilterChip(
            selected = learningPace == PkpdLearningPace.SLOW,
            onClick = {
                learningPace = PkpdLearningPace.SLOW
                PkpdLearningPace.SLOW.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(ApsStrings.aimi_pkpd_pace_slow)) },
        )
        FilterChip(
            selected = learningPace == PkpdLearningPace.NORMAL,
            onClick = {
                learningPace = PkpdLearningPace.NORMAL
                PkpdLearningPace.NORMAL.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(ApsStrings.aimi_pkpd_pace_normal)) },
        )
        FilterChip(
            selected = learningPace == PkpdLearningPace.FAST,
            onClick = {
                learningPace = PkpdLearningPace.FAST
                PkpdLearningPace.FAST.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(ApsStrings.aimi_pkpd_pace_fast)) },
        )
        FilterChip(
            selected = learningPace == PkpdLearningPace.FASTER,
            onClick = {
                learningPace = PkpdLearningPace.FASTER
                PkpdLearningPace.FASTER.applyTo(preferences)
                onPreferenceRevisionBump()
            },
            label = { Text(stringResource(ApsStrings.aimi_pkpd_pace_faster)) },
        )
    }

    PkpdScreenLayout.advancedFieldSections.forEach { PkpdDeclaredFields(it) }

    OutlinedButton(onClick = { showResetConfirm = true }) {
        Text(stringResource(ApsStrings.aimi_pkpd_reset_to_profile_action))
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(ApsStrings.aimi_pkpd_reset_confirm_title)) },
            text = { Text(stringResource(ApsStrings.aimi_pkpd_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        PkpdProfileInsulinMemory.requestReset(preferences)
                        onPreferenceRevisionBump()
                        showResetConfirm = false
                        scope.launch { snackbarHostState.showSnackbar(syncDoneMessage) }
                    },
                ) { Text(stringResource(ApsStrings.aimi_pkpd_reset_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(CoreUiStrings.cancel))
                }
            },
        )
    }
}

@Composable
fun PkpdExpertSettingsContent(preferenceRevision: Int) {
    // The groups and their keys come from PkpdScreenLayout and AimiSettingsScreens, so a key that is
    // declared for this content is always drawn. preferenceRevision makes the items read again after
    // the wizard changed a key.
    key(preferenceRevision) {
        PkpdScreenLayout.expertGroups.forEach { group ->
            ExpandableSection(title = stringResource(group.title), initiallyExpanded = false) {
                group.sections.forEach { PkpdDeclaredFields(it) }
            }
        }
    }
}

/**
 * The PK/PD status card of the Simple tab (step P3): the DIA and peak that dosed on the last loop
 * run, the profile insulin, and how learning went over the last 24 h (accepted steps, blocked loop
 * runs and the main reason). The values come from [PkpdTickTelemetry]. The card reads them again
 * every [PKPD_STATUS_REFRESH_MS] while it is shown, and it does not show the DIA and peak of a loop
 * run that is older than [PkpdTickTelemetry.KINETICS_FRESH_MS].
 */
@Composable
fun PkpdSimpleStatusCard(
    preferences: Preferences,
    profileDiaHours: Double?,
    profilePeakMin: Double?,
) {
    var showTechnical by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(aimiWallClockMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PKPD_STATUS_REFRESH_MS)
            nowMs = aimiWallClockMs()
        }
    }
    // Telemetry is plain memory, not Compose state: read it again on every refresh.
    val tick = remember(nowMs) { PkpdTickTelemetry.kinetics }
    val freshTick = tick?.takeIf { PkpdTickTelemetry.isFresh(it.atMs, nowMs) }
    val learning = remember(nowMs) { PkpdTickTelemetry.learning.summary(nowMs) }
    val learnedDia = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)
    val learnedPeak = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
    val profileDia = profileDiaHours ?: tick?.profileDiaHours ?: learnedDia
    val profilePeak = profilePeakMin
        ?: tick?.profilePeakMinutes
        ?: PkpdProfileInsulinMemory.lastSeen(preferences)?.peakMinutes
        ?: learnedPeak
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
                stringResource(ApsStrings.aimi_pkpd_status_card_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when {
                    freshTick != null -> stringResource(
                        ApsStrings.aimi_pkpd_status_used_format,
                        freshTick.effectiveDiaHours,
                        freshTick.effectivePeakMinutes,
                    )
                    tick != null      -> stringResource(ApsStrings.aimi_pkpd_status_no_recent_loop)
                    else              -> stringResource(ApsStrings.aimi_pkpd_status_no_loop_yet)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = AapsSpacing.small),
            )
            Text(
                stringResource(
                    ApsStrings.aimi_pkpd_status_profile_format,
                    profileDia,
                    profilePeak,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pkpdOn) {
                Text(
                    if (learning.learningTicks > 0) {
                        stringResource(ApsStrings.aimi_pkpd_status_learning_24h_format, learning.acceptedSteps, learning.blockedPercent)
                    } else {
                        stringResource(ApsStrings.aimi_pkpd_status_no_learning_24h)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = AapsSpacing.small),
                )
                learning.topBlockReason?.let { reason ->
                    val reasonText = pkpdBlockReasonText(reason)?.let { stringResource(it) } ?: reason
                    Text(
                        stringResource(ApsStrings.aimi_pkpd_status_top_reason_format, reasonText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                when {
                    !pkpdOn -> stringResource(ApsStrings.aimi_pkpd_status_learning_off)
                    fasterInsulin -> stringResource(ApsStrings.aimi_pkpd_status_faster_insulin)
                    else -> stringResource(ApsStrings.aimi_pkpd_status_learning_on)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = AapsSpacing.small),
            )
            if (showTechnical) {
                Text(
                    stringResource(ApsStrings.aimi_pkpd_status_learned_format, learnedDia, learnedPeak),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AapsSpacing.small),
                )
                freshTick?.let { PkpdTechnicalPeakDetails(it) }
            } else {
                Text(
                    stringResource(ApsStrings.aimi_pkpd_tap_for_technical),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AapsSpacing.extraSmall),
                )
            }
        }
    }
}

/** Plain text for a block reason category of [PkpdLearningSummary.topBlockReason], or null for an unknown one. */
internal fun pkpdBlockReasonText(category: String): TextRef? = when (category) {
    PkPdIntegration.LEARN_BLOCKED_CAUSAL_UNCLEAN                                  -> ApsStrings.aimi_pkpd_block_reason_causal_unclean
    PkpdLearningSummary.reasonCategory(PkPdIntegration.LEARN_BLOCKED_CAUSAL_MODULATOR_PREFIX) -> ApsStrings.aimi_pkpd_block_reason_causal_modulator
    PkpdLearningSummary.reasonCategory(PkPdIntegration.LEARN_BLOCKED_CURVE_PREFIX) -> ApsStrings.aimi_pkpd_block_reason_curve
    estimatorReason(PkPdUpdateReason.IOB_TOO_LOW)                                 -> ApsStrings.aimi_pkpd_block_reason_iob_too_low
    estimatorReason(PkPdUpdateReason.CARBS_ACTIVE)                                -> ApsStrings.aimi_pkpd_block_reason_carbs_active
    estimatorReason(PkPdUpdateReason.EXERCISE)                                    -> ApsStrings.aimi_pkpd_block_reason_exercise
    estimatorReason(PkPdUpdateReason.RISE_TOO_FAST),
    estimatorReason(PkPdUpdateReason.FAST_FALL)                                   -> ApsStrings.aimi_pkpd_block_reason_fast_change
    estimatorReason(PkPdUpdateReason.HYPO),
    estimatorReason(PkPdUpdateReason.NEAR_HYPO_FALLING)                           -> ApsStrings.aimi_pkpd_block_reason_low_glucose
    estimatorReason(PkPdUpdateReason.WINDOW_OUT_OF_RANGE)                         -> ApsStrings.aimi_pkpd_block_reason_window
    else                                                                          -> null
}

/** The block reason that [PkPdIntegration.learningTickBlockedBy] writes for [reason]. */
private fun estimatorReason(reason: PkPdUpdateReason): String =
    PkPdIntegration.learningTickBlockedBy(gateBlockedBy = null, accepted = false, estimatorReason = reason).orEmpty()

/** How often the PK/PD status card reads the tick telemetry again while it is shown. */
private const val PKPD_STATUS_REFRESH_MS = 30_000L

@Composable
private fun PkpdTechnicalPeakDetails(tick: PkpdTickTelemetry.Kinetics) {
    if (tick.dominantBranch.isNotEmpty()) {
        Text(
            text = stringResource(
                ApsStrings.aimi_pkpd_tap_g_detail,
                tick.dominantBranch,
                tick.effectivePeakMinutes,
                tick.peakPrior,
                tick.peakPhysio,
                tick.peakSite,
                tick.peakTrajectory,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = AapsSpacing.small),
        )
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

/** One folding group of the PK/PD expert tab: a title and the declared sections it draws. */
internal data class PkpdExpertGroup(
    val title: TextRef,
    val sections: List<AimiSettingsSectionId>,
)

/**
 * Which declared sections each PK/PD tab draws, and in which order.
 *
 * The keys themselves come from `AimiSettingsScreens`. This object only says where a section goes
 * on the screen. `PkpdScreenLayoutTest` checks that every `FIELDS` section of a PK/PD content is
 * listed here, so a declared key can not be left out of the screen again (that is how the curve
 * learning gate and the whole expert tab were lost).
 */
internal object PkpdScreenLayout {

    /** Simple tab: sections shown as plain preference items. */
    val simpleFieldSections: List<AimiSettingsSectionId> = listOf(
        AimiSettingsSectionId.PkpdSimpleEnable,
    )

    /** Advanced tab: sections shown as plain preference items, after the learning pace chips. */
    val advancedFieldSections: List<AimiSettingsSectionId> = listOf(
        AimiSettingsSectionId.PkpdAdvancedCurveLearningGate,
    )

    /** Expert tab: folding groups, each with its sections. */
    val expertGroups: List<PkpdExpertGroup> = listOf(
        PkpdExpertGroup(
            title = ApsStrings.aimi_pkpd_expert_peak_governor,
            sections = listOf(AimiSettingsSectionId.PkpdExpertPeakGovernor),
        ),
        PkpdExpertGroup(
            title = ApsStrings.aimi_pkpd_expert_dia_governor,
            sections = listOf(AimiSettingsSectionId.PkpdExpertDiaGovernor),
        ),
        PkpdExpertGroup(
            title = ApsStrings.aimi_pkpd_expert_isf_fusion,
            sections = listOf(
                AimiSettingsSectionId.PkpdExpertIsfFusionBounds,
                // The slope budget: the key that was stored but unreachable before this screen was routed.
                AimiSettingsSectionId.PkpdExpertIsfFusionSlope,
            ),
        ),
        PkpdExpertGroup(
            title = ApsStrings.aimi_dyn_isf_trajectory_section_title,
            sections = listOf(
                AimiSettingsSectionId.PkpdExpertDynIsfTuning,
                AimiSettingsSectionId.PkpdExpertDynIsfShadow,
                AimiSettingsSectionId.PkpdExpertDynIsfFraction,
            ),
        ),
        PkpdExpertGroup(
            title = ApsStrings.aimi_pkpd_expert_smb_tail,
            sections = listOf(
                AimiSettingsSectionId.PkpdExpertSmbTailThreshold,
                AimiSettingsSectionId.PkpdExpertSmbDampings,
            ),
        ),
        PkpdExpertGroup(
            title = ApsStrings.aimi_pkpd_section_expert,
            sections = listOf(
                AimiSettingsSectionId.PkpdExpertReliefEnable,
                AimiSettingsSectionId.PkpdExpertReliefFactors,
                AimiSettingsSectionId.PkpdExpertIobSurveillance,
                AimiSettingsSectionId.PkpdExpertPriorityMaxIob,
            ),
        ),
    )

    /** Sections a PK/PD content draws with [PkpdDeclaredFields]. */
    fun drawnSectionsOf(content: AimiSettingsContentId): List<AimiSettingsSectionId> = when (content) {
        AimiSettingsContentId.PkpdSimple   -> simpleFieldSections
        AimiSettingsContentId.PkpdAdvanced -> advancedFieldSections
        AimiSettingsContentId.PkpdExpert   -> expertGroups.flatMap { it.sections }
        else                               -> emptyList()
    }

    /**
     * Screen titles that replace the key's own title on the PK/PD screens.
     *
     * These keep the wording the screens had before the key lists became data. A key with no entry
     * here keeps its own title.
     */
    val titleOverrides: Map<String, TextRef> = mapOf(
        BooleanKey.OApsAIMIPkpdCurveLearningGate.key to ApsStrings.oaps_aimi_pkpd_curve_learning_gate_title,
        BooleanKey.OApsAIMIPkpdEnabled.key to ApsStrings.oaps_aimi_pkpd_enabled_title,
        DoubleKey.OApsAIMIIsfFusionMinFactor.key to ApsStrings.oaps_aimi_isf_fusion_min_title,
        DoubleKey.OApsAIMIIsfFusionMaxFactor.key to ApsStrings.oaps_aimi_isf_fusion_max_title,
        DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key to ApsStrings.oaps_aimi_isf_fusion_slope_title,
        DoubleKey.OApsAIMISmbTailThreshold.key to ApsStrings.oaps_aimi_smb_tail_threshold_title,
        DoubleKey.OApsAIMISmbTailDamping.key to ApsStrings.oaps_aimi_smb_tail_damping_title,
        DoubleKey.OApsAIMISmbExerciseDamping.key to ApsStrings.oaps_aimi_smb_exercise_damping_title,
        DoubleKey.OApsAIMISmbLateFatDamping.key to ApsStrings.oaps_aimi_smb_late_fat_damping_title,
        BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key to ApsStrings.oaps_aimi_pkpd_relief_enabled_title,
        DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key to ApsStrings.oaps_aimi_pkpd_relief_factor_title,
        DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key to ApsStrings.oaps_aimi_redcarpet_restore_title,
        BooleanKey.OApsAIMIIobSurveillanceGuard.key to ApsStrings.aimi_iob_surveillance_guard_title,
        DoubleKey.OApsAIMIPriorityMaxIobFactor.key to ApsStrings.oaps_aimi_priority_max_iob_factor_title,
        DoubleKey.OApsAIMIPriorityMaxIobExtraU.key to ApsStrings.oaps_aimi_priority_max_iob_extra_title,
    )

    /** Screen summaries that replace the key's own summary on the PK/PD screens. */
    val summaryOverrides: Map<String, TextRef> = mapOf(
        BooleanKey.OApsAIMIPkpdEnabled.key to ApsStrings.aimi_pkpd_enabled_simple_summary,
        BooleanKey.OApsAIMIPkpdCurveLearningGate.key to ApsStrings.oaps_aimi_pkpd_curve_learning_gate_summary,
        BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key to ApsStrings.oaps_aimi_pkpd_relief_enabled_summary,
        BooleanKey.OApsAIMIIobSurveillanceGuard.key to ApsStrings.aimi_iob_surveillance_guard_summary,
    )

    /**
     * True when [PkpdDeclaredFields] can draw this key. A key of another type would be skipped
     * without a sign, so `PkpdScreenLayoutTest` checks every drawn key with this.
     */
    fun canDrawAsField(key: PreferenceKey): Boolean =
        key is BooleanPreferenceKey || key is DoublePreferenceKey || key is IntPreferenceKey
}

/**
 * Shows every key of a declared section as one editable item, in declaration order.
 *
 * The layout is unchanged: this only moves the key list out of the layout code, so
 * `AimiSettingsManifestTest` and `PkpdScreenLayoutTest` can prove each of these keys is reachable.
 */
@Composable
private fun PkpdDeclaredFields(sectionId: AimiSettingsSectionId) {
    AimiSettingsScreens.preferenceKeysOf(sectionId).forEach { preferenceKey ->
        val title = PkpdScreenLayout.titleOverrides[preferenceKey.key]
        when (preferenceKey) {
            is BooleanPreferenceKey -> AdaptiveSwitchPreferenceItem(
                booleanKey = preferenceKey,
                title = title,
                summary = PkpdScreenLayout.summaryOverrides[preferenceKey.key],
            )
            is DoublePreferenceKey  -> AdaptiveDoublePreferenceItem(
                doubleKey = preferenceKey,
                title = title,
            )
            is IntPreferenceKey     -> AdaptiveIntPreferenceItem(
                intKey = preferenceKey,
                title = title,
            )
            else                    -> Unit
        }
    }
}
