package app.aaps.core.ui.compose.preference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.R
import kotlinx.coroutines.launch

/**
 * Full-screen host for a [PreferenceSubScreenDef] tree: main card, inline compose intents, and
 * recursive drill-down for nested sub-screens (via [LocalOpenPreferenceSubScreen]).
 */
@Composable
fun PreferenceSubScreenHost(
    screenDef: PreferenceSubScreenDef,
    highlightKey: String? = null,
    onBackClick: () -> Unit,
) {
    val title = if (screenDef.titleResId != 0) {
        stringResource(screenDef.titleResId)
    } else {
        screenDef.key
    }

    val sectionState = rememberSaveable(screenDef.key, saver = PreferenceSectionState.Saver) {
        PreferenceSectionState()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val onShowMessage: (String) -> Unit = { message ->
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }
    var composeScreen: ComposeScreenContent? by remember { mutableStateOf(null) }
    var drilledSub: PreferenceSubScreenDef? by remember { mutableStateOf(null) }

    LaunchedEffect(screenDef.key) {
        val mainKey = "${screenDef.key}_main"
        if (!sectionState.isExpanded(mainKey)) {
            sectionState.toggle(mainKey, SectionLevel.TOP_LEVEL)
        }
    }

    BackHandler(enabled = composeScreen != null) {
        composeScreen = null
    }

    composeScreen?.let { screen ->
        screen.Content(onBack = { composeScreen = null })
        return
    }

    drilledSub?.let { sub ->
        PreferenceSubScreenHost(
            screenDef = sub,
            highlightKey = null,
            onBackClick = { drilledSub = null }
        )
        return
    }

    CompositionLocalProvider(
        LocalSnackbarHostState provides snackbarHostState,
        LocalHighlightKey provides highlightKey,
        LocalNavigateToCompose provides { screen -> composeScreen = screen },
        LocalOpenPreferenceSubScreen provides { sub -> drilledSub = sub }
    ) {
        ProvidePreferenceTheme {
            Scaffold(
                topBar = {
                    AapsTopAppBar(
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { paddingValues ->
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScrollIndicators(listState),
                    state = listState
                ) {
                    item { MasterOfflineBanner(editingEnabled = masterEditingEnabled()) }
                    addPreferenceContent(
                        content = screenDef,
                        onShowMessage = onShowMessage,
                        sectionState = sectionState
                    )
                }
            }
        }
    }
}
