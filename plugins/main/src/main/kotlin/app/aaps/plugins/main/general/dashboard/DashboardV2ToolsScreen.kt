@file:Suppress("ktlint:standard:function-naming")

package app.aaps.plugins.main.general.dashboard

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.plugins.main.R

enum class DashboardV2ToolSection {
    GENERAL,
    AIMI,
}

sealed interface DashboardV2ToolDestination {
    data object Actions : DashboardV2ToolDestination

    data class Element(
        val type: ElementType,
    ) : DashboardV2ToolDestination

    data class Plugin(
        val className: String,
    ) : DashboardV2ToolDestination

    data class AimiActivity(
        val protection: ProtectionCheck.Protection,
    ) : DashboardV2ToolDestination
}

/** Fixed Tools inventory inspired by the reference screen, plus the four AIMI shortcuts. */
enum class DashboardV2ToolAction(
    val section: DashboardV2ToolSection,
    val destination: DashboardV2ToolDestination,
) {
    ACTIONS(DashboardV2ToolSection.GENERAL, DashboardV2ToolDestination.Actions),
    RAPID_ACTING(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Element(ElementType.INSULIN_MANAGEMENT),
    ),
    PROFILE(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Element(ElementType.PROFILE_MANAGEMENT),
    ),
    AUTOMATION(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Element(ElementType.AUTOMATION_MANAGEMENT),
    ),
    NSCLIENT(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Plugin("NSClientV3Plugin"),
    ),
    TIDEPOOL(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Plugin("TidepoolPlugin"),
    ),
    XDRIP(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Plugin("XdripPlugin"),
    ),
    MAINTENANCE(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Element(ElementType.MAINTENANCE),
    ),
    XDRIP_BG(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Plugin("XdripSourcePlugin"),
    ),
    SENSOR(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Element(ElementType.BGSOURCE),
    ),
    ADVISOR(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.AimiActivity(ProtectionCheck.Protection.PREFERENCES),
    ),
    MEAL_ADVISOR(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.AimiActivity(ProtectionCheck.Protection.BOLUS),
    ),
    AIMI_CONTEXT(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.AimiActivity(ProtectionCheck.Protection.BOLUS),
    ),
    AUDITOR_REPORT(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.AimiActivity(ProtectionCheck.Protection.NONE),
    ),
    ;

    companion object {
        val general: List<DashboardV2ToolAction> = entries.filter { it.section == DashboardV2ToolSection.GENERAL }
        val aimi: List<DashboardV2ToolAction> = entries.filter { it.section == DashboardV2ToolSection.AIMI }
    }
}

fun DashboardV2ToolAction.isAvailable(availablePluginClassNames: Set<String>): Boolean =
    (destination as? DashboardV2ToolDestination.Plugin)?.className?.let(availablePluginClassNames::contains) ?: true

@Composable
fun DashboardV2ToolsScreen(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    availablePluginClassNames: Set<String>,
    onAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Null shows every tile (today's behavior, used by every DASHBOARD_V2 caller). A non-null set hides
     *  any tile not in it — used by Glass's Tools-tile personalization. */
    visibleActions: Set<DashboardV2ToolAction>? = null,
) {
    val columns =
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
            GridCells.Fixed(3)
        } else {
            GridCells.Adaptive(minSize = 108.dp)
        }
    LazyVerticalGrid(
        columns = columns,
        modifier =
            modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(bottom = fabBottomOffset),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.dashboard_v2_tools_title),
                modifier =
                    Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val visibleGeneral = DashboardV2ToolAction.general.filter { visibleActions == null || it in visibleActions }
        items(visibleGeneral, key = DashboardV2ToolAction::name) { action ->
            DashboardV2ToolTile(
                action = action,
                enabled = action.isAvailable(availablePluginClassNames),
                onClick = { onAction(action) },
            )
        }
        val visibleAimi = DashboardV2ToolAction.aimi.filter { visibleActions == null || it in visibleActions }
        if (visibleAimi.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.dashboard_v2_tools_aimi_section),
                    modifier =
                        Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(visibleAimi, key = DashboardV2ToolAction::name) { action ->
            DashboardV2ToolTile(
                action = action,
                enabled = action.isAvailable(availablePluginClassNames),
                onClick = { onAction(action) },
            )
        }
    }
}

@Composable
private fun DashboardV2ToolTile(
    action: DashboardV2ToolAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(action.labelRes())
    val unavailable = stringResource(R.string.dashboard_v2_tools_unavailable)
    val description = if (enabled) label else "$label, $unavailable"
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 48.dp, minHeight = 104.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier =
                Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = action.icon(),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun DashboardV2ToolAction.icon(): ImageVector =
    when (this) {
        DashboardV2ToolAction.ACTIONS -> Icons.Default.PlayArrow
        DashboardV2ToolAction.RAPID_ACTING -> Icons.Default.Medication
        DashboardV2ToolAction.PROFILE -> Icons.Default.Person
        DashboardV2ToolAction.AUTOMATION -> Icons.Default.AutoAwesome
        DashboardV2ToolAction.NSCLIENT -> Icons.Default.Cloud
        DashboardV2ToolAction.TIDEPOOL -> Icons.Default.Sync
        DashboardV2ToolAction.XDRIP -> Icons.Default.WaterDrop
        DashboardV2ToolAction.MAINTENANCE -> Icons.Default.Build
        DashboardV2ToolAction.XDRIP_BG -> Icons.Default.Storage
        DashboardV2ToolAction.SENSOR -> IcGenericCgm
        DashboardV2ToolAction.ADVISOR -> Icons.Default.Settings
        DashboardV2ToolAction.MEAL_ADVISOR -> Icons.Default.Restaurant
        DashboardV2ToolAction.AIMI_CONTEXT -> Icons.Default.Favorite
        DashboardV2ToolAction.AUDITOR_REPORT -> Icons.Default.Assessment
    }

@StringRes
internal fun DashboardV2ToolAction.labelRes(): Int =
    when (this) {
        DashboardV2ToolAction.ACTIONS -> R.string.dashboard_v2_tool_actions
        DashboardV2ToolAction.RAPID_ACTING -> R.string.dashboard_v2_tool_rapid_acting
        DashboardV2ToolAction.PROFILE -> R.string.dashboard_v2_tool_profile
        DashboardV2ToolAction.AUTOMATION -> R.string.dashboard_v2_tool_automation
        DashboardV2ToolAction.NSCLIENT -> R.string.dashboard_v2_tool_nsclient
        DashboardV2ToolAction.TIDEPOOL -> R.string.dashboard_v2_tool_tidepool
        DashboardV2ToolAction.XDRIP -> R.string.dashboard_v2_tool_xdrip
        DashboardV2ToolAction.MAINTENANCE -> R.string.dashboard_v2_tool_maintenance
        DashboardV2ToolAction.XDRIP_BG -> R.string.dashboard_v2_tool_xdrip_bg
        DashboardV2ToolAction.SENSOR -> R.string.dashboard_v2_tool_sensor
        DashboardV2ToolAction.ADVISOR -> R.string.dashboard_v2_tool_advisor
        DashboardV2ToolAction.MEAL_ADVISOR -> R.string.dashboard_v2_tool_meal_advisor
        DashboardV2ToolAction.AIMI_CONTEXT -> R.string.dashboard_v2_tool_aimi_context
        DashboardV2ToolAction.AUDITOR_REPORT -> R.string.dashboard_v2_tool_auditor_report
    }
