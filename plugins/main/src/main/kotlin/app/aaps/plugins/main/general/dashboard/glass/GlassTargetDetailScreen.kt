package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.AapsFab
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.ScreenMode
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.compose.navigation.labelResId
import app.aaps.ui.compose.components.ContentContainer
import app.aaps.ui.compose.components.ManagementCarousel
import app.aaps.ui.compose.tempTarget.TempTargetCarouselCard
import app.aaps.ui.compose.tempTarget.TempTargetManagementViewModel
import kotlinx.coroutines.launch
import app.aaps.core.ui.R as CoreUiR
import app.aaps.ui.R as UiR

/**
 * Glass-branded quick-access view onto [TempTargetManagementViewModel] — PLAY mode only.
 *
 * This is a deliberately narrower-than-1:1 reskin: view the active target (with its live progress /
 * remaining-time indicator via [TempTargetCarouselCard]), browse the preset carousel, activate the
 * selected preset, or cancel the active target. Preset EDITING is out of scope here — no add, delete,
 * rename, retarget, or reorder — those stay on the full shared
 * [app.aaps.ui.compose.tempTarget.TempTargetManagementScreen], which remains fully intact and reachable
 * elsewhere (e.g. Gestion / Management) for anyone who needs them.
 *
 * Binds the SAME [TempTargetManagementViewModel] instance the shared screen uses. This ViewModel is
 * Activity-scoped (not route-scoped), so the caller must pass in the existing instance rather than
 * obtaining a fresh one via `hiltViewModel()`.
 */
@Composable
fun GlassTargetDetailScreen(
    viewModel: TempTargetManagementViewModel,
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Same gate the shared screen applies (masterEditingEnabled(), see AapsTheme.kt): on a client whose
    // master is unreachable, Activate/Cancel are master-bound actions that could not be delivered, so
    // they are hidden here exactly as the shared screen hides its Activate FAB / Cancel mini-FAB, and
    // MasterOfflineBanner renders the same "master offline" / "remote control disabled" messaging.
    val editingEnabled = masterEditingEnabled()
    val scope = rememberCoroutineScope()

    // First composition: switch the shared ViewModel into PLAY mode, then refresh its runtime data
    // (active TT / presets / remaining time) from source-of-truth. refreshData() — not loadData() — is
    // the correct entrypoint here: loadData() already ran once in the ViewModel's init{} when it was
    // first constructed (this Activity-scoped instance is shared with, and may already be showing on,
    // the full TempTargetManagementScreen route), and refreshData() is exactly the "screen came back
    // into view" reload the shared screen itself uses on ON_RESUME, without resetting carousel position.
    LaunchedEffect(Unit) {
        viewModel.setScreenMode(ScreenMode.PLAY)
        viewModel.refreshData()
    }

    AapsTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(ElementType.TEMP_TARGET_MANAGEMENT.labelResId())) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreUiR.string.back)
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                MasterOfflineBanner(editingEnabled = editingEnabled)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ContentContainer(
                        isLoading = uiState.isLoading,
                        isEmpty = uiState.presets.isEmpty()
                    ) {
                        // Standalone active card only when the active TT doesn't match any preset —
                        // same rule the shared screen uses.
                        val hasStandaloneActiveTT = uiState.activeTT != null && uiState.activePresetIndex == null
                        val cardCount = if (hasStandaloneActiveTT) uiState.presets.size + 1 else uiState.presets.size

                        val pagerState = rememberPagerState(
                            initialPage = uiState.currentCardIndex.coerceIn(0, (cardCount - 1).coerceAtLeast(0)),
                            pageCount = { cardCount }
                        )

                        // Keep the pager in sync with the ViewModel's stored card index (e.g. after an
                        // external refresh moves the active card).
                        LaunchedEffect(uiState.currentCardIndex, cardCount) {
                            val target = uiState.currentCardIndex.coerceIn(0, (cardCount - 1).coerceAtLeast(0))
                            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                        }

                        // Update the selected preset (and its mirrored editor values, which
                        // activateWithEditorValues() reads) whenever the user swipes to a new card.
                        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
                            if (!pagerState.isScrollInProgress) {
                                viewModel.updateCurrentCardIndex(pagerState.currentPage)
                                val presetIndex = when {
                                    hasStandaloneActiveTT && pagerState.currentPage > 0 -> pagerState.currentPage - 1
                                    !hasStandaloneActiveTT                              -> pagerState.currentPage
                                    else                                                -> null // standalone active-TT card
                                }
                                if (presetIndex != null) viewModel.selectPreset(presetIndex)
                                else viewModel.selectActiveTT()
                            }
                        }

                        val presetOffset = if (hasStandaloneActiveTT) 1 else 0
                        val selectLabel = stringResource(CoreUiR.string.carousel_show_card)

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = AapsSpacing.extraLarge),
                            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
                        ) {
                            ManagementCarousel(state = pagerState) { itemState ->
                                val page = itemState.page
                                val isStandaloneActiveCard = hasStandaloneActiveTT && page == 0
                                val presetIndex = if (isStandaloneActiveCard) null else page - presetOffset
                                val preset = presetIndex?.let { uiState.presets.getOrNull(it) }
                                val isActivePreset = presetIndex != null && presetIndex == uiState.activePresetIndex

                                TempTargetCarouselCard(
                                    preset = preset,
                                    activeTT = if (isStandaloneActiveCard || isActivePreset) uiState.activeTT else null,
                                    remainingTimeMs = uiState.remainingTimeMs,
                                    isSelected = itemState.isSelected,
                                    units = viewModel.units,
                                    onExpired = { viewModel.refreshData() },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(onClickLabel = selectLabel) {
                                            scope.launch { pagerState.animateScrollToPage(page) }
                                        }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(AapsSpacing.extraLarge),
                        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cancel — hidden (not just disabled) on a client whose master is unreachable,
                        // same as the shared screen's mini-FAB: canceling a TT is a master/NS action
                        // that could not be delivered.
                        if (editingEnabled && uiState.activeTT != null) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.cancelActive(onSuccess = onNavigateBack) },
                                containerColor = MaterialTheme.colorScheme.error
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(CoreUiR.string.cancel)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Activate — same gate as the shared screen's primary FAB: hidden on a client
                        // whose master is unreachable, since activating a TT is a master/remote action.
                        if (editingEnabled) {
                            AapsFab(
                                onClick = { viewModel.activateWithEditorValues(onSuccess = onNavigateBack) }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(UiR.string.activate_label)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
