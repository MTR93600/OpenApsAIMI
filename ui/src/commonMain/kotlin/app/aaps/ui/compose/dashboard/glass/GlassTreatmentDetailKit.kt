package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.consumeOverscroll
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.stringResource

/*
 * The frame of the Glass screens that start a treatment or change the loop (insulin, cannula, basal,
 * sensor insert). Only the look is Glass: each screen keeps the view model, the confirmation dialog
 * and every check of the normal screen it replaces.
 */

/**
 * Glass background, header, [banners] that never scroll away, the scrolling [content], and the
 * [confirmButton] fixed at the bottom, where the normal dialogs have it too.
 */
@Composable
internal fun GlassTreatmentDetailFrame(
    title: String,
    isDark: Boolean,
    onNavigateBack: () -> Unit,
    confirmButton: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    banners: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    GlassScreenBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)) {
                GlassDetailHeader(title = title, isDark = isDark, onBack = onNavigateBack, actions = actions)
            }
            banners()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeOverscroll()
                    .verticalScroll(rememberScrollState())
                    .clearFocusOnTap(focusManager)
                    .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                content = content,
            )
            confirmButton()
        }
    }
}

/** A Glass card with the inner padding the Glass treatment screens use. */
@Composable
internal fun GlassDetailCard(isDark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(isDark = isDark) {
        Column(
            modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            content = content,
        )
    }
}

/**
 * The OK button of a Glass treatment screen. [GlassScreenBackground] already keeps it above the
 * navigation bar and the keyboard. As on the normal dialogs, the screen reader says
 * "Confirm" and then the [label], which carries the amount.
 */
@Composable
internal fun GlassConfirmButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val confirmDescription = stringResource(CoreUiStrings.confirm)
    Button(
        onClick = {
            focusManager.clearFocus()
            onClick()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
            .semantics { contentDescription = confirmDescription },
    ) {
        Icon(imageVector = Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(AapsSpacing.medium))
        Text(label)
    }
}
