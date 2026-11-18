package app.aaps.ui.compose.dashboard.essential

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData.ContextKind
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * The context satellite: the declared AIMI context (sport, illness, ...) as a small dot with its glyph, at
 * the top left of the shape, opposite the maintenance satellite. It is drawn only while a context is active.
 *
 * Filled in the tertiary colour when the loop really uses the context. Only outlined, in the outline colour,
 * when it is declared but the loop does not use it (context setting off, another algorithm, no recent run,
 * or a kind the dosing does not read): the screen must never say "the loop knows" when it does not.
 * Like the maintenance satellite it never pulses and never moves. A tap opens the context screen.
 */
@Composable
internal fun ContextSatellite(kind: ContextKind, usedByLoop: Boolean, label: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val used = MaterialTheme.colorScheme.tertiary.soften()
    val declared = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(EssentialShapeDimens.maintenanceTouch)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(EssentialShapeDimens.maintenanceDot * 1.8f)) {
            val dot = EssentialShapeDimens.maintenanceDot.toPx() / 2f
            if (usedByLoop) {
                drawCircle(Brush.radialGradient(listOf(used.copy(alpha = 0.35f), Color.Transparent), radius = size.minDimension / 2f))
                drawCircle(used, radius = dot)
            } else {
                drawCircle(declared, radius = dot, style = Stroke(width = EssentialShapeDimens.contextOutline.toPx()))
            }
        }
        Icon(
            imageVector = kind.glyph(),
            contentDescription = null,
            tint = if (usedByLoop) MaterialTheme.colorScheme.onTertiary else declared,
            modifier = Modifier.size(EssentialShapeDimens.maintenanceGlyph),
        )
    }
}

/** The short name of a context kind, for the satellite's label. */
@Composable
internal fun ContextKind.label(): String = stringResource(
    when (this) {
        ContextKind.HYPO_RECOVERY  -> UiStrings.essential_context_hypo_recovery
        ContextKind.ILLNESS        -> UiStrings.essential_context_illness
        ContextKind.ACTIVITY       -> UiStrings.essential_context_activity
        ContextKind.ALCOHOL        -> UiStrings.essential_context_alcohol
        ContextKind.STRESS         -> UiStrings.essential_context_stress
        ContextKind.MEAL_RISK      -> UiStrings.essential_context_meal_risk
        ContextKind.SLOW_CARB_MEAL -> UiStrings.essential_context_slow_carb_meal
        ContextKind.TRAVEL         -> UiStrings.essential_context_travel
        ContextKind.CYCLE          -> UiStrings.essential_context_cycle
        ContextKind.CUSTOM         -> UiStrings.essential_context_custom
    }
)

private fun ContextKind.glyph(): ImageVector = when (this) {
    ContextKind.HYPO_RECOVERY  -> Icons.Filled.Healing
    ContextKind.ILLNESS        -> Icons.Filled.Sick
    ContextKind.ACTIVITY       -> Icons.AutoMirrored.Filled.DirectionsRun
    ContextKind.ALCOHOL        -> Icons.Filled.LocalBar
    ContextKind.STRESS         -> Icons.Filled.Psychology
    ContextKind.MEAL_RISK      -> Icons.Filled.Restaurant
    ContextKind.SLOW_CARB_MEAL -> Icons.Filled.RiceBowl
    ContextKind.TRAVEL         -> Icons.Filled.Flight
    ContextKind.CYCLE          -> Icons.Filled.Female
    ContextKind.CUSTOM         -> Icons.AutoMirrored.Filled.Label
}
