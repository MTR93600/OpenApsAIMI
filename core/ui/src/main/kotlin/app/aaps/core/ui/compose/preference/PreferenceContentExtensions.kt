package app.aaps.core.ui.compose.preference

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.rememberBringIntoViewOnExpand
import kotlinx.coroutines.delay

/**
 * Helper function to add preference content inline in a LazyListScope.
 * Handles PreferenceSubScreenDef only.
 */
fun LazyListScope.addPreferenceContent(
    content: Any,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState? = null
) {
    when (content) {
        is PreferenceSubScreenDef -> addPreferenceSubScreenDef(content, onShowMessage, sectionState)
    }
}

/**
 * Helper function to add PreferenceSubScreenDef inline in a LazyListScope.
 * This displays as one collapsible card with main content and nested subscreens inside.
 * Nested paths use slash-separated keys so expansion state stays unique across the whole app.
 *
 * From [treeDepth] 1 onward, nested [PreferenceSubScreenDef] rows use full-screen drill-down when
 * [LocalOpenPreferenceSubScreen] is provided — avoids deep accordion / lazy-column pitfalls.
 */
fun LazyListScope.addPreferenceSubScreenDef(
    def: PreferenceSubScreenDef,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState? = null
) {
    val sectionKey = "${def.key}_main"
    item(key = sectionKey) {
        val isExpanded = sectionState?.isExpanded(sectionKey) ?: false
        val visibilityContext = LocalVisibilityContext.current
        CollapsibleCardSectionContent(
            titleResId = def.titleResId,
            summaryItems = def.effectiveSummaryItems(),
            expanded = isExpanded,
            onToggle = { sectionState?.toggle(sectionKey, SectionLevel.TOP_LEVEL) },
            icon = def.icon
        ) {
            RenderPreferenceItems(
                items = def.items,
                pathPrefix = def.key,
                treeDepth = 0,
                onShowMessage = onShowMessage,
                sectionState = sectionState,
                visibilityContext = visibilityContext
            )
        }
    }
}

@Composable
private fun PreferenceSubScreenDrillDownRow(
    titleResId: Int,
    summaryItems: List<Int>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPreferenceTheme.current
    val resolvedSummaries = summaryItems.filter { it != 0 }.map { stringResource(it) }
    val summaryText = if (resolvedSummaries.isNotEmpty()) {
        resolvedSummaries.joinToString(", ")
    } else {
        null
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(theme.headerPaddingInsideCard)
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (titleResId != 0) stringResource(titleResId) else "",
                style = theme.categoryTextStyle,
                color = theme.categoryColor
            )
            if (summaryText != null) {
                Text(
                    text = summaryText,
                    style = theme.summaryCategoryTextStyle,
                    color = theme.summaryCategoryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
            contentDescription = stringResource(app.aaps.core.ui.R.string.expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(theme.expandIconSize)
        )
    }
}

/**
 * @param pathPrefix Slash-separated path of the **parent** node (e.g. plugin root key).
 * @param treeDepth Nesting level inside inline accordions; 0 = direct children of the plugin card.
 */
@Composable
private fun RenderPreferenceItems(
    items: List<PreferenceItem>,
    pathPrefix: String,
    treeDepth: Int,
    onShowMessage: (String) -> Unit,
    sectionState: PreferenceSectionState?,
    visibilityContext: VisibilityContext?
) {
    val opener = LocalOpenPreferenceSubScreen.current
    val theme = LocalPreferenceTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            when (item) {
                is PreferenceKey          -> {
                    HighlightablePreference(preferenceKey = item.key) {
                        AdaptivePreferenceItem(
                            key = item,
                            onShowMessage = onShowMessage,
                            visibilityContext = visibilityContext
                        )
                    }
                }

                is PreferenceSubScreenDef -> {
                    val shouldShow = shouldShowSubScreenInline(
                        subScreen = item,
                        visibilityContext = visibilityContext
                    )

                    if (!shouldShow) {
                        return@forEach
                    }

                    val subPath = "$pathPrefix/${item.key}"
                    val useDrillDown = treeDepth > 0 && opener != null

                    if (useDrillDown) {
                        PreferenceSubScreenDrillDownRow(
                            titleResId = item.titleResId,
                            summaryItems = item.effectiveSummaryItems(),
                            onOpen = { opener.invoke(item) }
                        )
                    } else {
                        val isSubExpanded = sectionState?.isExpanded(subPath) ?: false

                        ClickablePreferenceCategoryHeader(
                            titleResId = item.titleResId,
                            summaryItems = item.effectiveSummaryItems(),
                            expanded = isSubExpanded,
                            onToggle = { sectionState?.toggle(subPath, SectionLevel.SUB_SECTION, parentKey = pathPrefix) },
                            insideCard = true
                        )

                        if (isSubExpanded && item.items.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(start = theme.nestedContentIndent)
                            ) {
                                RenderPreferenceItems(
                                    items = item.items,
                                    pathPrefix = subPath,
                                    treeDepth = treeDepth + 1,
                                    onShowMessage = onShowMessage,
                                    sectionState = sectionState,
                                    visibilityContext = visibilityContext
                                )
                            }
                        }
                    }
                }

                else                      -> Unit
            }
        }
    }
}

/**
 * Determines if a subscreen should be shown based on hideParentScreenIfHidden logic.
 * Used in inline rendering context (AllPreferencesScreen).
 */
@Composable
private fun shouldShowSubScreenInline(
    subScreen: PreferenceSubScreenDef,
    visibilityContext: VisibilityContext?
): Boolean {
    for (checkItem in subScreen.items) {
        if (checkItem is PreferenceKey && checkItem.hideParentScreenIfHidden) {
            val visibility = if (checkItem is IntentPreferenceKey) {
                calculateIntentPreferenceVisibility(
                    intentKey = checkItem,
                    visibilityContext = visibilityContext
                )
            } else {
                val engineeringModeOnly = when (checkItem) {
                    is BooleanPreferenceKey -> checkItem.engineeringModeOnly
                    is IntPreferenceKey     -> checkItem.engineeringModeOnly
                    is LongPreferenceKey    -> checkItem.engineeringModeOnly
                    else                    -> false
                }
                calculatePreferenceVisibility(
                    preferenceKey = checkItem,
                    engineeringModeOnly = engineeringModeOnly,
                    visibilityContext = visibilityContext
                )
            }
            if (!visibility.visible) {
                return false
            }
        }
    }
    return true
}

/** How long a search-highlighted preference stays tinted before fading out. */
private const val HIGHLIGHT_DURATION_MS = 5000L

/**
 * Wrapper that highlights a preference if it matches the LocalHighlightKey.
 * Scrolls the preference into view, then shows a color flash animation to draw attention to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HighlightablePreference(
    preferenceKey: String,
    content: @Composable () -> Unit
) {
    val highlightKey = LocalHighlightKey.current
    val shouldHighlight = highlightKey == preferenceKey

    var isHighlighted by remember { mutableStateOf(shouldHighlight) }

    // The whole screen renders as one LazyColumn item, so index-based scrolling can't reach the
    // row — bring it into view through the nearest scrollable ancestor instead.
    val bringIntoViewRequester = rememberBringIntoViewOnExpand(shouldHighlight)

    // Animate highlight fade out
    LaunchedEffect(shouldHighlight) {
        if (shouldHighlight) {
            isHighlighted = true
            delay(HIGHLIGHT_DURATION_MS)
            isHighlighted = false
        }
    }

    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 500),
        label = "highlightColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .background(highlightColor)
    ) {
        content()
    }
}
