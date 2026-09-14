package app.aaps.ui.compose.stats

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.GlassSectionCard
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.ui.R
import app.aaps.ui.compose.stats.viewmodels.StatsViewModel

/**
 * Composable screen displaying statistics including TDD, TIR, Dexcom TIR, and Activity Monitor.
 * Uses the Glass visual language (gradient background, glass section cards).
 *
 * @param viewModel ViewModel containing all statistics state and business logic
 * @param onNavigateBack Callback when back navigation is requested
 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.loadAllStats()
        }
    }

    val isDark = isGlassDarkMode()

    GlassScreenBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                        tint = GlassColors.textBright(isDark)
                    )
                }
                Text(
                    text = stringResource(app.aaps.core.ui.R.string.statistics),
                    color = GlassColors.textBright(isDark),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // TDD Section
            GlassSectionCard(
                title = stringResource(app.aaps.core.ui.R.string.tdd),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                expanded = state.tddExpanded,
                onToggleExpanded = { viewModel.toggleTddExpanded() },
                trailingAction = if (state.tddExpanded && !state.tddLoading) {
                    {
                        FilledTonalButton(onClick = { viewModel.showRecalculateDialog() }) {
                            Text(text = stringResource(R.string.recalculate))
                        }
                    }
                } else null
            ) {
                Crossfade(
                    targetState = state.tddLoading,
                    label = stringResource(app.aaps.core.ui.R.string.loading)
                ) { isLoading ->
                    if (isLoading) {
                        LoadingSection(
                            title = stringResource(app.aaps.core.ui.R.string.tdd),
                            message = stringResource(R.string.calculation_in_progress)
                        )
                    } else {
                        state.tddStatsData?.let { data ->
                            TddStatsCompose(
                                tddStatsData = data,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // TIR Section
            GlassSectionCard(
                title = stringResource(app.aaps.core.ui.R.string.tir),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                expanded = state.tirExpanded,
                onToggleExpanded = { viewModel.toggleTirExpanded() }
            ) {
                Crossfade(
                    targetState = state.tirLoading,
                    label = stringResource(app.aaps.core.ui.R.string.loading)
                ) { isLoading ->
                    if (isLoading) {
                        LoadingSection(
                            title = stringResource(app.aaps.core.ui.R.string.tir),
                            message = stringResource(R.string.calculation_in_progress)
                        )
                    } else {
                        state.tirStatsData?.let { data ->
                            TirStatsCompose(
                                tirStatsData = data,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Dexcom TIR Section
            GlassSectionCard(
                title = stringResource(R.string.dexcom_tir),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                expanded = state.dexcomTirExpanded,
                onToggleExpanded = { viewModel.toggleDexcomTirExpanded() }
            ) {
                Crossfade(
                    targetState = state.dexcomTirLoading,
                    label = stringResource(app.aaps.core.ui.R.string.loading)
                ) { isLoading ->
                    if (isLoading) {
                        LoadingSection(
                            title = stringResource(R.string.dexcom_tir),
                            message = stringResource(R.string.calculation_in_progress)
                        )
                    } else {
                        state.dexcomTirData?.let { data ->
                            DexcomTirStatsCompose(
                                dexcomTir = data,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Activity Section
            GlassSectionCard(
                title = stringResource(R.string.activity_monitor),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                expanded = state.activityExpanded,
                onToggleExpanded = { viewModel.toggleActivityExpanded() },
                trailingAction = if (state.activityExpanded && !state.activityLoading) {
                    {
                        FilledTonalButton(onClick = { viewModel.showResetActivityDialog() }) {
                            Text(text = stringResource(app.aaps.core.ui.R.string.reset))
                        }
                    }
                } else null
            ) {
                Crossfade(
                    targetState = state.activityLoading,
                    label = stringResource(app.aaps.core.ui.R.string.loading)
                ) { isLoading ->
                    if (isLoading) {
                        LoadingSection(
                            title = stringResource(R.string.activity_monitor),
                            message = stringResource(R.string.calculation_in_progress)
                        )
                    } else {
                        state.activityStatsData?.let { data ->
                            ActivityStatsCompose(
                                activityStats = data,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // TDD Cycle Pattern Section
            GlassSectionCard(
                title = stringResource(app.aaps.core.ui.R.string.tdd_cycle_pattern),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                expanded = state.tddCycleExpanded,
                onToggleExpanded = { viewModel.toggleTddCycleExpanded() }
            ) {
                Column {
                    // Progress indicator (visible while still loading)
                    if (state.tddCycleLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { state.tddCycleProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = GlassColors.skyBlue,
                                trackColor = GlassColors.borderCard(isDark)
                            )
                            Text(
                                text = stringResource(
                                    R.string.calculation_in_progress_percent,
                                    (state.tddCycleProgress * 100).toInt()
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // Graph (shown as soon as data is available, even while loading continues)
                    if (state.tddCyclePatternData != null) {
                        TddCyclePatternCompose(
                            data = state.tddCyclePatternData!!,
                            offset = state.tddCycleOffset,
                            onOffsetChange = { viewModel.updateCycleOffset(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!state.tddCycleLoading) {
                        Text(
                            text = stringResource(R.string.not_enough_data_for_cycles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlassColors.textMuted(isDark)
                        )
                    }
                }
            }

            // CGP Section (Comprehensive Glucose Pentagon) - always expanded
            GlassSectionCard(
                title = stringResource(R.string.cgp_title),
                isDark = isDark,
                modifier = Modifier.fillMaxWidth(),
                onToggleExpanded = null
            ) {
                Crossfade(targetState = state.dexcomTirLoading, label = "cgp_loading") { isLoading ->
                    if (isLoading) {
                        LoadingSection(title = stringResource(R.string.cgp_title), message = stringResource(R.string.calculation_in_progress))
                    } else {
                        state.dexcomTirData?.let { data ->
                            GlucosePentagonCompose(dexcomTir = data, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    if (state.showRecalculateDialog) {
        OkCancelDialog(
            message = stringResource(R.string.do_you_want_recalculate_tdd_stats),
            onConfirm = { viewModel.confirmRecalculateTdd() },
            onDismiss = { viewModel.dismissRecalculateDialog() }
        )
    }

    if (state.showResetActivityDialog) {
        OkCancelDialog(
            message = stringResource(R.string.do_you_want_reset_stats),
            onConfirm = { viewModel.confirmResetActivityStats() },
            onDismiss = { viewModel.dismissResetActivityDialog() }
        )
    }
}
