package app.aaps.plugins.aps.openAPSAIMI.compose

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.compose.preference.AdaptivePreferenceList
import app.aaps.core.ui.compose.preference.LocalHighlightKey
import app.aaps.core.ui.compose.preference.LocalNavigateToCompose
import app.aaps.core.ui.compose.preference.LocalVisibilityContext
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.preference.verticalScrollIndicators
import app.aaps.core.ui.compose.stringResource
import kotlinx.coroutines.launch

/*
 * Browsing to AIMI settings screens at any depth.
 *
 * The shared settings renderer in core/ui (`addPreferenceContent`) draws two levels: the plugin
 * card, and the sub screens directly inside it as open/close headers. A sub screen one level deeper
 * is not drawn at all (`AdaptivePreferenceList` skips every `PreferenceSubScreenDef`). That is how
 * upstream works, and core/ui stays identical to upstream so that merges stay clean.
 *
 * The AIMI tree is deeper than that: User preferences > Expert > Trajectory > Tube MPC, for example.
 * So AIMI puts a link row in front of each of its deeper sub screens. The link is an
 * [IntentPreferenceKey] with a Compose screen, which the renderer already knows how to draw and open.
 * The screen it opens ([AimiPreferenceSubScreen]) draws the sub screen's own items, again with
 * `AdaptivePreferenceList`, and its own sub screens are again links, so every level can be reached.
 * This is shared code: the same on Android and iOS.
 *
 * The `PreferenceSubScreenDef` itself stays in the tree, right after its link. The renderer skips it
 * while browsing, but search needs it: the search index and the "open the screen of this result"
 * navigation both walk `PreferenceSubScreenDef` items, at any depth. One side effect: when search
 * opens one of these screens as its own page, its direct sub screens show twice there (once as an
 * open/close header, once as a link row).
 */

/**
 * Returns [items] with a link row put in front of every sub screen, at every depth below.
 *
 * Call it on the items of a sub screen that the renderer draws as a header, never on the items of
 * the plugin card itself: the card's own sub screens are drawn already, and a link there would show
 * them twice.
 *
 * @param pageFor the page a link opens instead of the plain list of its screen (a feature card, the
 *   Expert area), or null for the plain list. It gets the screen with its own link rows.
 */
internal fun aimiWithDrillInLinks(
    items: List<PreferenceItem>,
    pageFor: (PreferenceSubScreenDef) -> ComposeScreenContent? = { null },
): List<PreferenceItem> =
    items.flatMap { item ->
        if (item is PreferenceSubScreenDef) {
            val screen = item.copy(items = aimiWithDrillInLinks(item.items, pageFor))
            listOf(AimiSubScreenLink(screen, pageFor(screen)), screen)
        } else {
            listOf(item)
        }
    }

/**
 * A row that opens a page. The page shows the keys of [screen].
 *
 * The tree tests follow every such row, so a key on one of these pages counts as reachable.
 */
internal interface AimiScreenLink : IntentPreferenceKey {

    /** The keys the opened page shows. */
    val screen: PreferenceSubScreenDef
}

/**
 * A row that opens [screen] as its own page.
 *
 * Only [aimiWithDrillInLinks] builds these, so [screen] already has links for its own sub screens.
 * The class is not in any key enum: it is not a stored setting, it is not exported, and search does
 * not list it (search lists the screen itself, from the `PreferenceSubScreenDef` that follows it).
 *
 * @param page the page to open, when it is more than the plain list of [screen] (a feature card or
 *   the Expert area); null opens the plain list
 */
internal class AimiSubScreenLink(
    override val screen: PreferenceSubScreenDef,
    page: ComposeScreenContent? = null,
) : AimiScreenLink {

    override val key: String = linkKeyOf(screen.key)
    override val title: TextRef = screen.title
    override val summary: TextRef? = screen.summary
    override val preferenceType: PreferenceType = PreferenceType.CLICK
    override val composeScreen: Any = page ?: ComposeScreenContent { onBack -> AimiPreferenceSubScreen(screen = screen, onBack = onBack) }

    companion object {

        /** Key of the link row for the sub screen with key [screenKey]. Never a stored preference key. */
        fun linkKeyOf(screenKey: String): String = "${screenKey}_link"
    }
}

/**
 * The row at the top of the AIMI settings that opens the Essentials page ([AimiEssentials]).
 *
 * Unlike [AimiSubScreenLink], no `PreferenceSubScreenDef` follows it in the tree: the plugin card
 * draws its own sub screens as headers, so one there would show the same keys a second time, without
 * the hint, the value in use and the "?" sheet. Every key of the page keeps its old place in the
 * tree as well, so search still finds it there.
 *
 * Not in any key enum, for the same reasons as [AimiSubScreenLink].
 *
 * @param content the Essentials page itself
 */
internal class AimiEssentialsLink(
    override val screen: PreferenceSubScreenDef,
    content: ComposeScreenContent,
) : AimiScreenLink {

    override val key: String = AimiSubScreenLink.linkKeyOf(screen.key)
    override val title: TextRef = screen.title
    override val summary: TextRef? = screen.summary
    override val preferenceType: PreferenceType = PreferenceType.CLICK
    override val composeScreen: Any = content
}

/**
 * One AIMI sub screen as its own page: the same rows the shared renderer draws, with a back arrow.
 *
 * A row in here that opens another screen (a deeper sub screen, or a Compose screen such as the
 * PK/PD setup) opens it on top of this page, and back returns here, not to the plugin page.
 *
 * @param header drawn above the rows, inside the same navigation, so it may open a page too
 * @param visibleItems the rows to draw out of the screen items (a feature card hides some)
 * @param highlightKey the key of a row to highlight and scroll to, after a search
 */
@Composable
internal fun AimiPreferenceSubScreen(
    screen: PreferenceSubScreenDef,
    onBack: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    visibleItems: @Composable (List<PreferenceItem>) -> List<PreferenceItem> = { it },
    highlightKey: String? = null,
) {
    var openScreen: ComposeScreenContent? by remember(screen.key) { mutableStateOf(null) }

    // Back closes the page opened from here. When nothing is open, the page that opened this one
    // has its own handler, which closes this page.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = openScreen != null,
        onBackCompleted = { openScreen = null },
    )

    openScreen?.let { child ->
        child.Content(onBack = { openScreen = null })
        return
    }

    val visibilityContext = LocalVisibilityContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onShowMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(
            LocalNavigateToCompose provides { child -> openScreen = child },
            LocalHighlightKey provides (highlightKey ?: LocalHighlightKey.current),
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(screen.title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(CoreUiStrings.back),
                                )
                            }
                        },
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { paddingValues ->
                val listState = rememberLazyListState()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScrollIndicators(listState),
                    state = listState,
                ) {
                    item { MasterOfflineBanner(editingEnabled = masterEditingEnabled()) }
                    header?.let { drawHeader -> item { drawHeader() } }
                    // Draws keys and link rows. The PreferenceSubScreenDef after each link is skipped
                    // here, exactly as in the shared renderer, so nothing shows twice.
                    item {
                        AdaptivePreferenceList(
                            items = visibleItems(screen.items),
                            onShowMessage = onShowMessage,
                            visibilityContext = visibilityContext,
                        )
                    }
                }
            }
        }
    }
}
