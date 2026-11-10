package app.aaps.core.ui.compose.preference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.masterEditingEnabled
import kotlinx.coroutines.launch

/**
 * Screen for displaying plugin preferences using Compose.
 * Uses the same rendering approach as AllPreferencesScreen - collapsible sections.
 *
 * @param plugin The plugin whose preferences to display
 * @param visibilityContext Context for evaluating visibility conditions
 * @param onBackClick Callback when back button is clicked
 * @param onOpenLegacyXmlPreferences If non-null, toolbar action opens the classic XML preference screen for this plugin
 */
@Composable
fun PluginPreferencesScreen(
    plugin: PluginBase,
    visibilityContext: VisibilityContext? = null,
    onBackClick: () -> Unit,
    onOpenLegacyXmlPreferences: (() -> Unit)? = null
) {
    val preferenceScreenContent = plugin.getPreferenceScreenContent()
    val title = plugin.name

    // State for inline Compose screen navigation
    var composeScreen: ComposeScreenContent? by remember { mutableStateOf(null) }
    var drilledSubScreen: PreferenceSubScreenDef? by remember { mutableStateOf(null) }

    BackHandler(enabled = composeScreen != null) {
        composeScreen = null
    }
    BackHandler(enabled = drilledSubScreen != null) {
        drilledSubScreen = null
    }

    // If a compose sub-screen is active, render it instead of preferences
    composeScreen?.let { screen ->
        screen.Content(onBack = { composeScreen = null })
        return
    }

    drilledSubScreen?.let { sub ->
        PreferenceSubScreenHost(
            screenDef = sub,
            highlightKey = null,
            onBackClick = { drilledSubScreen = null }
        )
        return
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(
            LocalNavigateToCompose provides { screen -> composeScreen = screen },
            LocalOpenPreferenceSubScreen provides { sub -> drilledSubScreen = sub }
        ) {
            when (preferenceScreenContent) {
                is PreferenceSubScreenDef -> {
                    // PreferenceSubScreenDef - use same rendering as AllPreferencesScreen
                    SinglePluginPreferencesRenderer(
                        screen = preferenceScreenContent,
                        title = title,
                        visibilityContext = visibilityContext,
                        onBackClick = onBackClick,
                        onOpenLegacyXmlPreferences = onOpenLegacyXmlPreferences
                    )
                }

                else                      -> {
                    // Fallback for plugins without compose preferences
                    Scaffold(
                        topBar = {
                            AapsTopAppBar(
                                title = { Text(title) },
                                navigationIcon = {
                                    IconButton(onClick = onBackClick) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(app.aaps.core.ui.R.string.back)
                                        )
                                    }
                                },
                                actions = {
                                    onOpenLegacyXmlPreferences?.let { openLegacy ->
                                        IconButton(onClick = openLegacy) {
                                            Icon(
                                                imageVector = Icons.Filled.ViewList,
                                                contentDescription = stringResource(app.aaps.core.ui.R.string.legacy_xml_preferences)
                                            )
                                        }
                                    }
                                }
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { paddingValues ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(app.aaps.core.ui.R.string.no_compose_preferences),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renderer for single plugin preferences using the same collapsible section approach as AllPreferencesScreen.
 * Shares the same code path - only difference is it displays ONE screen instead of multiple.
 */
@Composable
private fun SinglePluginPreferencesRenderer(
    screen: PreferenceSubScreenDef,
    title: String,
    visibilityContext: VisibilityContext?,
    onBackClick: () -> Unit,
    onOpenLegacyXmlPreferences: (() -> Unit)? = null
) {
    val sectionState = rememberSaveable(screen.key, saver = PreferenceSectionState.Saver) {
        PreferenceSectionState()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val onShowMessage: (String) -> Unit = { message ->
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }

    // Expand the plugin card once when opening (do not toggle — avoids collapsing restored state).
    LaunchedEffect(screen.key) {
        val mainKey = "${screen.key}_main"
        if (!sectionState.isExpanded(mainKey)) {
            sectionState.toggle(mainKey, SectionLevel.TOP_LEVEL)
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(app.aaps.core.ui.R.string.back)
                        )
                    }
                },
                actions = {
                    onOpenLegacyXmlPreferences?.let { openLegacy ->
                        IconButton(onClick = openLegacy) {
                            Icon(
                                imageVector = Icons.Filled.ViewList,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.legacy_xml_preferences)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        val listState = rememberLazyListState()

        // Provide visibility context via CompositionLocal (same as AllPreferencesScreen)
        CompositionLocalProvider(
            LocalVisibilityContext provides visibilityContext
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScrollIndicators(listState),
                state = listState
            ) {
                item { MasterOfflineBanner(editingEnabled = masterEditingEnabled()) }
                // Use the same addPreferenceContent() as AllPreferencesScreen
                // This renders as collapsible sections, not navigation
                addPreferenceContent(
                    content = screen,
                    onShowMessage = onShowMessage,
                    sectionState = sectionState
                )
            }
        }
    }
}
