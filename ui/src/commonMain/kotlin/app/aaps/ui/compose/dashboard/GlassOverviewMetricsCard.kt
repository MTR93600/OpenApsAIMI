package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.BgInfoSection

internal fun glassMetricDisplayText(value: String, unavailableLabel: String): String =
    value.takeUnless(String::isBlank) ?: unavailableLabel

/**
 * GLASS-style metric card populated only with the already formatted Overview values.
 * No clinical values are recalculated or assigned fallback telemetry by this presentation.
 *
 * @param cgmWarmup link state of the active CGM source (connecting, reconnecting, warm-up), or
 *   null when the source reports none. With a stale value it shows the banner of [GlassCgmLinkStatus].
 * @param nowMs current time from a ticker, so a value that gets old between two overview refreshes
 *   is still shown as stale. Null keeps `BgInfoData.isOutdated` as it is.
 */
@Composable
internal fun GlassOverviewMetricsCard(
    glucoseLabel: String,
    bgInfo: BgInfoData?,
    timeAgoText: String,
    iobLabel: String,
    iobText: String,
    cobLabel: String,
    cobText: String,
    sensitivityLabel: String,
    sensitivityText: String,
    statisticsLabel: String,
    treatmentsLabel: String,
    onStatistics: () -> Unit,
    onTreatments: () -> Unit,
    modifier: Modifier = Modifier,
    cgmWarmup: CgmWarmupStatus? = null,
    nowMs: Long? = null,
) {
    val glucoseTimestampMs = bgInfo?.timestamp?.takeIf { nowMs != null }
    val checkNowMs = nowMs ?: 0L
    val glucoseStale = GlassCgmLinkStatus.isStale(
        isGlucoseActual = bgInfo != null && !bgInfo.isOutdated,
        glucoseTimestampMs = glucoseTimestampMs,
        nowMs = checkNowMs,
    )
    val linkBanner = GlassCgmLinkStatus.banner(
        warmup = cgmWarmup,
        isGlucoseActual = bgInfo != null && !bgInfo.isOutdated,
        glucoseTimestampMs = glucoseTimestampMs,
        nowMs = checkNowMs,
    )
    // An old value stays visible, struck through, like the classic overview.
    val shownBgInfo = if (bgInfo != null && glucoseStale && !bgInfo.isOutdated) bgInfo.copy(isOutdated = true) else bgInfo
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val unavailableLabel = stringResource(CoreUiStrings.value_unavailable_short)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = glucoseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BgInfoSection(
                    bgInfo = shownBgInfo,
                    timeAgoText = timeAgoText,
                    size = 96.dp,
                )
                linkBanner?.let { GlassCgmLinkBannerText(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassMetricTile(iobLabel, glassMetricDisplayText(iobText, unavailableLabel), modifier = Modifier.weight(1f))
                GlassMetricTile(cobLabel, glassMetricDisplayText(cobText, unavailableLabel), modifier = Modifier.weight(1f))
                GlassMetricTile(
                    sensitivityLabel,
                    glassMetricDisplayText(sensitivityText, unavailableLabel),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassDestination(statisticsLabel, onStatistics, Modifier.weight(1f))
                GlassDestination(treatmentsLabel, onTreatments, Modifier.weight(1f))
            }
        }
    }
}

/** "Reconnecting… · last value 14 min ago", in the warning colour of the theme. */
@Composable
private fun GlassCgmLinkBannerText(banner: GlassCgmLinkStatus.Banner) {
    val phase = stringResource(
        when (banner.phase) {
            CgmWarmupStatus.Phase.CONNECTING -> UiStrings.dashboard_glass_cgm_phase_connecting
            CgmWarmupStatus.Phase.RECONNECTING -> UiStrings.dashboard_glass_cgm_phase_reconnecting
            CgmWarmupStatus.Phase.PAIRING -> UiStrings.dashboard_glass_cgm_phase_pairing
            CgmWarmupStatus.Phase.WARMING,
            CgmWarmupStatus.Phase.OTHER -> UiStrings.dashboard_glass_cgm_phase_warmup
        },
    )
    Text(
        text = banner.minutesSinceLastValue?.let {
            stringResource(UiStrings.dashboard_glass_cgm_link_banner, phase, it.toInt())
        } ?: phase,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = AapsTheme.generalColors.statusWarning,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GlassMetricTile(
    label: String,
    value: String,
    detail: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassDestination(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
