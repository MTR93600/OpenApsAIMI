package app.aaps.ui.compose.dashboard.glass

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsFab
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.ScreenMode
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.components.ContentContainer
import app.aaps.ui.compose.components.ManagementCarousel
import app.aaps.ui.compose.tempTarget.TempTargetCarouselCard
import app.aaps.ui.compose.tempTarget.TempTargetManagementUiState
import app.aaps.ui.compose.tempTarget.TempTargetManagementViewModel
import kotlinx.coroutines.launch

/**
 * The Glass temp target screen. Port of `GlassTargetDetailScreen` on `dev_OAPSAIMI`.
 *
 * A narrower view than [app.aaps.ui.compose.tempTarget.TempTargetManagementScreen], in PLAY mode
 * only: see the running target, browse the presets, activate the shown preset or cancel the running
 * target. Presets are not edited here; that stays on the full screen.
 *
 * It uses the same activity scoped [TempTargetManagementViewModel] as the full screen, so the caller
 * passes that instance in. Activate and Cancel go through [TempTargetManagementViewModel.activateWithEditorValues]
 * and [TempTargetManagementViewModel.cancelActive], which ask the master to prepare the change and
 * show its confirmation dialog. As on the full screen, both are hidden on a client whose master
 * cannot be reached ([masterEditingEnabled]), and [MasterOfflineBanner] says why.
 */
@Composable
fun GlassTargetDetailScreen(
    viewModel: TempTargetManagementViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.setScreenMode(ScreenMode.PLAY)
    }

    // Same reload as the full screen: the view model outlives the screen, the running target may have changed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshData()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // As on the full screen: the reload skipped while a confirmed change commits is done once out of sight.
    DisposableEffect(Unit) {
        onDispose { viewModel.onScreenClosed() }
    }

    GlassTargetDetailContent(
        uiState = uiState,
        units = viewModel.units,
        isDark = isGlassDarkMode(),
        editingEnabled = masterEditingEnabled(),
        onCardShown = { page, presetIndex ->
            viewModel.updateCurrentCardIndex(page)
            if (presetIndex != null) viewModel.selectPreset(presetIndex) else viewModel.selectActiveTT()
        },
        onExpired = { viewModel.refreshData() },
        onActivate = { viewModel.activateWithEditorValues(onSuccess = onNavigateBack) },
        onCancelActive = { viewModel.cancelActive(onSuccess = onNavigateBack) },
        onNavigateBack = onNavigateBack
    )
}

/**
 * The stateless body of [GlassTargetDetailScreen].
 *
 * @param onCardShown called when the carousel settles on a card: the page, and the preset index, or
 *   null for the card of a running target that matches no preset
 */
@Composable
internal fun GlassTargetDetailContent(
    uiState: TempTargetManagementUiState,
    units: GlucoseUnit,
    isDark: Boolean,
    editingEnabled: Boolean,
    onCardShown: (page: Int, presetIndex: Int?) -> Unit,
    onExpired: () -> Unit,
    onActivate: () -> Unit,
    onCancelActive: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    GlassScreenBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)) {
                GlassDetailHeader(
                    title = stringResourceOrNull(ElementType.TEMP_TARGET_MANAGEMENT.label()) ?: "",
                    isDark = isDark,
                    onBack = onNavigateBack
                )
            }
            MasterOfflineBanner(editingEnabled = editingEnabled)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ContentContainer(isLoading = uiState.isLoading, isEmpty = uiState.presets.isEmpty()) {
                    // A card of its own only for a running target that matches no preset, as on the full screen.
                    val hasStandaloneActiveTT = uiState.activeTT != null && uiState.activePresetIndex == null
                    val presetOffset = if (hasStandaloneActiveTT) 1 else 0
                    val cardCount = uiState.presets.size + presetOffset

                    val pagerState = rememberPagerState(
                        initialPage = uiState.currentCardIndex.coerceIn(0, (cardCount - 1).coerceAtLeast(0)),
                        pageCount = { cardCount }
                    )

                    // Follow the stored card index when a reload moves it (for example to a new running target).
                    LaunchedEffect(uiState.currentCardIndex, cardCount) {
                        val target = uiState.currentCardIndex.coerceIn(0, (cardCount - 1).coerceAtLeast(0))
                        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
                    }

                    // The shown card is what Activate uses: select it once the carousel stops.
                    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
                        if (!pagerState.isScrollInProgress) {
                            val page = pagerState.currentPage
                            val presetIndex = if (hasStandaloneActiveTT && page == 0) null else page - presetOffset
                            onCardShown(page, presetIndex)
                        }
                    }

                    val selectLabel = stringResource(CoreUiStrings.carousel_show_card)
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = AapsSpacing.extraLarge),
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
                                isSelected = itemState.isSelected,
                                units = units,
                                onExpired = onExpired,
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
                    // Cancelling and activating are master actions: hidden when the master cannot be reached.
                    if (editingEnabled && uiState.activeTT != null) {
                        SmallFloatingActionButton(
                            onClick = onCancelActive,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(CoreUiStrings.clientcontrol_action_cancel_temp_target)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (editingEnabled && !uiState.isLoading && uiState.presets.isNotEmpty()) {
                        AapsFab(onClick = onActivate) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(UiStrings.activate_label)
                            )
                        }
                    }
                }
            }
        }
    }
}
