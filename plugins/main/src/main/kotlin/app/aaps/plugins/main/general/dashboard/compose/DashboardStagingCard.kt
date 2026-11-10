package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState

/**
 * Compact "new sensor" (staging slot) card for the Compose dashboard. Only rendered when a second
 * sensor is being warmed up / stabilised over the production one. The staging slot is collect-only and
 * NEVER feeds the loop — the only action that swaps the loop's glucose source is the explicit Promote
 * button, which routes through the view model to `promoteStagingToProduction()`.
 *
 * When [StatusCardState.stagingState] is [StagingState.ABSENT] this renders nothing, so the dashboard
 * is byte-identical to before for anyone not running a dual-sensor overlap.
 */
@Composable
fun DashboardStagingCard(
    state: StatusCardState,
    onPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.stagingState == StagingState.ABSENT) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
        ) {
            when (state.stagingState) {
                StagingState.WARMUP   -> StagingWarmupContent(state.stagingWarmup)
                StagingState.SETTLING -> StagingSettlingContent(state.stagingLifecycle, state.stagingEvidence)
                StagingState.READY    -> StagingReadyContent(onPromote)
                StagingState.ABSENT   -> Unit
            }
        }
    }
}

@Composable
private fun StagingWarmupContent(warmup: CgmWarmupStatus?) {
    Text(
        text = stringResource(R.string.dashboard_staging_title_warmup),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val remainingMinutes = warmup?.remainingMinutesOrNull()
    if (remainingMinutes != null) {
        Text(
            text = stringResource(R.string.dashboard_staging_warmup_countdown, remainingMinutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val total = warmup?.totalMs
    if (warmup != null && total != null && total > 0L) {
        val remaining = warmup.remainingMsOrNull()
        if (remaining != null) {
            val progress = ((total - remaining).toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AapsSpacing.extraSmall),
            )
        }
    }
}

@Composable
private fun StagingSettlingContent(lifecycle: CgmSensorLifecycle?, evidence: CgmStagingEvidence?) {
    val ageMs = lifecycle?.ageMs ?: 0L
    val remainingMs = (STAGING_MIN_SETTLE_MS - ageMs).coerceAtLeast(0L)
    // Ceil to whole hours so "ready in 1 h" is shown until the final minutes rather than "0 h".
    val readyInHours = ((remainingMs + HOUR_MS - 1L) / HOUR_MS).coerceAtLeast(0L).toInt()
    Text(
        text = stringResource(R.string.dashboard_staging_title_settling, readyInHours),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    // Reading count tells the user the staging sensor is really sending data, not just counting hours.
    Text(
        text = stringResource(R.string.dashboard_staging_readings, evidence?.validCount ?: 0),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val progress = (ageMs.toFloat() / STAGING_MIN_SETTLE_MS).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AapsSpacing.extraSmall),
    )
}

@Composable
private fun StagingReadyContent(onPromote: () -> Unit) {
    val readyColor = colorResource(R.color.dashboard_tir_in_range)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.dashboard_staging_ready),
            style = MaterialTheme.typography.labelLarge,
            color = readyColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onPromote,
            colors = ButtonDefaults.buttonColors(containerColor = readyColor),
        ) {
            Text(text = stringResource(R.string.dashboard_staging_promote_button))
        }
    }
}

/**
 * Minimum settle time before a staging sensor may be promoted, mirrored from the source plugin's
 * `DexcomOnePlusStaging.STAGING_MIN_SETTLE_MS` (12 h). Kept as a local constant so this UI module does
 * not depend on `plugins:source`; the promotion decision itself is still owned by the plugin.
 */
private const val HOUR_MS = 3_600_000L
private const val STAGING_MIN_SETTLE_MS = 12L * HOUR_MS

/** Remaining warm-up in ms from either the explicit countdown or the wall-clock end time. */
private fun CgmWarmupStatus.remainingMsOrNull(): Long? {
    val ends = endsAtEpochMs
    val remaining = remainingMs
    return when {
        ends != null      -> (ends - System.currentTimeMillis()).coerceAtLeast(0L)
        remaining != null -> remaining.coerceAtLeast(0L)
        else              -> null
    }
}

/** Remaining warm-up rounded up to whole minutes, or null when unknown. */
private fun CgmWarmupStatus.remainingMinutesOrNull(): Int? =
    remainingMsOrNull()?.let { (((it + 59_999L) / 60_000L)).coerceAtLeast(0L).toInt() }
