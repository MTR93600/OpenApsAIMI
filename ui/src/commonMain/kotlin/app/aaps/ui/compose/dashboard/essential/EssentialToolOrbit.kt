package app.aaps.ui.compose.dashboard.essential

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.aaps.core.ui.compose.AapsTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One AIMI screen offered in the orbit around the shape. */
data class EssentialOrbitItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The orbit closes by itself after this time without a choice. */
private const val ORBIT_OPEN_MS = 6_000L

/** How far from the centre the tools sit, as a part of the shape box. */
private const val ORBIT_RADIUS_RATIO = 0.46f

/**
 * The tools a long press on the shape shows: small satellites on an arc above the shape, in the same
 * quiet language as the maintenance and context satellites. A choice closes the orbit, and so does a tap
 * on the shape or six seconds without a choice. The detail sheet holds the same tools, so this is never
 * the only way to them.
 */
@Composable
internal fun ToolOrbit(items: List<EssentialOrbitItem>, boxSize: Dp, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Grows out of the shape, so the person sees where the tools come from
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.7f)) }
    LaunchedEffect(Unit) {
        delay(ORBIT_OPEN_MS)
        onDismiss()
    }
    val shown = grow.value
    val radius = boxSize * ORBIT_RADIUS_RATIO
    Box(modifier, contentAlignment = Alignment.Center) {
        items.forEachIndexed { index, item ->
            // Spread evenly on the upper arc, from 150 to 30 degrees, left to right
            val angle = if (items.size == 1) -PI / 2 else -PI + PI / 6 + index * (2 * PI / 3) / (items.size - 1)
            val x = radius * (cos(angle).toFloat() * shown)
            val y = radius * (sin(angle).toFloat() * shown)
            OrbitTool(
                item = item,
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                modifier = Modifier
                    .offset(x = x, y = y)
                    .graphicsLayer {
                        scaleX = shown
                        scaleY = shown
                        alpha = shown.coerceIn(0f, 1f)
                    },
            )
        }
    }
}

@Composable
private fun OrbitTool(item: EssentialOrbitItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shadowElevation = AapsTheme.spacing.extraSmall,
            modifier = Modifier
                .size(EssentialShapeDimens.maintenanceTouch)
                .semantics {
                    contentDescription = item.label
                    role = Role.Button
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(top = AapsTheme.spacing.extraSmall)) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = AapsTheme.spacing.medium, vertical = AapsTheme.spacing.extraSmall),
            )
        }
    }
}
