package app.aaps.ui.compose.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/** One part of the manual: a title and its text. */
internal data class ManualSection(val title: TextRef, val content: TextRef)

/** The 20 sections of the manual, in reading order. Same text as the dev_OAPSAIMI manual. */
internal val MANUAL_SECTIONS: List<ManualSection> = listOf(
    ManualSection(UiStrings.manual_section_1_title, UiStrings.manual_section_1_content),
    ManualSection(UiStrings.manual_section_2_title, UiStrings.manual_section_2_content),
    ManualSection(UiStrings.manual_section_3_title, UiStrings.manual_section_3_content),
    ManualSection(UiStrings.manual_section_4_title, UiStrings.manual_section_4_content),
    ManualSection(UiStrings.manual_section_5_title, UiStrings.manual_section_5_content),
    ManualSection(UiStrings.manual_section_6_title, UiStrings.manual_section_6_content),
    ManualSection(UiStrings.manual_section_7_title, UiStrings.manual_section_7_content),
    ManualSection(UiStrings.manual_section_8_title, UiStrings.manual_section_8_content),
    ManualSection(UiStrings.manual_section_9_title, UiStrings.manual_section_9_content),
    ManualSection(UiStrings.manual_section_10_title, UiStrings.manual_section_10_content),
    ManualSection(UiStrings.manual_section_11_title, UiStrings.manual_section_11_content),
    ManualSection(UiStrings.manual_section_12_title, UiStrings.manual_section_12_content),
    ManualSection(UiStrings.manual_section_13_title, UiStrings.manual_section_13_content),
    ManualSection(UiStrings.manual_section_14_title, UiStrings.manual_section_14_content),
    ManualSection(UiStrings.manual_section_15_title, UiStrings.manual_section_15_content),
    ManualSection(UiStrings.manual_section_16_title, UiStrings.manual_section_16_content),
    ManualSection(UiStrings.manual_section_17_title, UiStrings.manual_section_17_content),
    ManualSection(UiStrings.manual_section_18_title, UiStrings.manual_section_18_content),
    ManualSection(UiStrings.manual_section_19_title, UiStrings.manual_section_19_content),
    ManualSection(UiStrings.manual_section_20_title, UiStrings.manual_section_20_content),
)

/**
 * The in-app user manual, opened from the book button in the main top bar.
 *
 * Ported from the dev_OAPSAIMI `UserManualActivity`: an introduction card, then one card per
 * section. It only shows text, so it needs no protection and works the same on every phone.
 */
@Composable
fun UserManualScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(UiStrings.manual_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiStrings.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
        ) {
            item(key = "intro") {
                ManualCard(title = null, content = stringResource(UiStrings.manual_intro))
            }
            items(items = MANUAL_SECTIONS) { section ->
                ManualCard(title = stringResource(section.title), content = stringResource(section.content))
            }
        }
    }
}

@Composable
private fun ManualCard(title: String?, content: String) {
    val styledContent = remember(content) { manualText(content) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(text = styledContent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Shows the manual's `**bold**` marks as bold text, instead of the raw stars that the old screen
 * showed. Nothing else in the text is changed. A star pair that is not closed is kept as it is.
 */
internal fun manualText(raw: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < raw.length) {
        val start = raw.indexOf(BOLD_MARK, index)
        val end = if (start >= 0) raw.indexOf(BOLD_MARK, start + BOLD_MARK.length) else -1
        if (start < 0 || end < 0) {
            append(raw.substring(index))
            break
        }
        append(raw.substring(index, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(raw.substring(start + BOLD_MARK.length, end))
        }
        index = end + BOLD_MARK.length
    }
}

private const val BOLD_MARK = "**"
