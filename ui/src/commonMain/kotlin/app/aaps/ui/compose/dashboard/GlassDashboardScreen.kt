package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared GLASS home skin.
 *
 * The home content is supplied by the same Overview-backed dashboard graph and summary used by
 * Dashboard V2. This screen changes presentation only: it does not synthesize loop/therapy metrics
 * that are unavailable from the shared runtime feeds, and all app routes remain in the host shell.
 */
@Composable
fun GlassDashboardScreen(
    title: String,
    settingsLabel: String,
    onOpenSettings: () -> Unit,
    metricsContent: @Composable () -> Unit = {},
    dashboardContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val glassBorder = colors.onSurface.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF213145),
                        Color(0xFF0F1C2C),
                        Color(0xFF18283A),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, glassBorder, RoundedCornerShape(18.dp)),
                color = Color(0xCC14263A),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = title },
                        color = Color(0xFFEAF1FF),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.semantics { contentDescription = settingsLabel },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = settingsLabel,
                            tint = Color(0xFFEAF1FF),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    metricsContent()
                }
            }
            Box(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxWidth(),
            ) {
                dashboardContent()
            }
        }
    }
}
