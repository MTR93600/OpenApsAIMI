package app.aaps.plugins.source.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.DexcomOnePlusUiLabels
import app.aaps.plugins.source.compose.DexcomOnePlusWarmupCountdown
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Warm-up countdown UI bound to [OnePlusWarmupState].
 *
 * Countdown source (see [DexcomOnePlusWarmupCountdown]):
 * - Prefer protocol `remainingMs` / `endsAtEpochMs`.
 * - Local ~30 min fallback timer ONLY when both are null and phase is WARMING.
 */
@AndroidEntryPoint
class DexcomOnePlusWarmupActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusWarmupScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusWarmupScreen(onBack: () -> Unit) {
    val driver = remember { OnePlusCgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var localFallbackEndsAt by remember { mutableStateOf<Long?>(null) }
    var remainingMs by remember { mutableStateOf<Long?>(null) }
    var usingLocalFallback by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
            if (DexcomOnePlusWarmupCountdown.shouldStartLocalFallback(state) && localFallbackEndsAt == null) {
                // Local fallback ONLY if remainingMs (and endsAt) are null — documented above.
                localFallbackEndsAt = now + DexcomOnePlusWarmupCountdown.LOCAL_FALLBACK_DURATION_MS
            }
            if (DexcomOnePlusWarmupCountdown.shouldClearLocalFallback(state.phase)) {
                localFallbackEndsAt = null
            }
            usingLocalFallback =
                state.remainingMs == null &&
                    state.endsAtEpochMs == null &&
                    localFallbackEndsAt != null &&
                    state.phase == OnePlusWarmupState.Phase.WARMING
            remainingMs = DexcomOnePlusWarmupCountdown.resolveRemainingMs(
                state = state,
                nowEpochMs = now,
                localFallbackEndsAtEpochMs = localFallbackEndsAt,
            )
            delay(1_000L)
        }
    }

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_warmup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dexcom_oneplus_nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_heading),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_phase, DexcomOnePlusUiLabels.phaseLabel(state.phase)),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Teal-spirit warm-up ring. Determinate only when the local ~30 min fallback owns the
            // clock (known total); otherwise a full accent ring signals honest, open-ended progress.
            val ringProgress: Float? =
                if (state.phase == OnePlusWarmupState.Phase.WARMING && usingLocalFallback) {
                    val remaining = remainingMs ?: 0L
                    (1f - remaining.toFloat() / DexcomOnePlusWarmupCountdown.LOCAL_FALLBACK_DURATION_MS)
                        .coerceIn(0f, 1f)
                } else {
                    null
                }
            val ringColor = when (state.phase) {
                OnePlusWarmupState.Phase.READY  -> MaterialTheme.colorScheme.primary
                OnePlusWarmupState.Phase.FAILED -> MaterialTheme.colorScheme.error
                else                            -> MaterialTheme.colorScheme.tertiary
            }
            // Keep the mm:ss countdown while the link is being re-established: a normal duty-cycle
            // disconnect during warm-up used to blank it until the next EGV packet.
            val countdownText = remainingMs?.let { DexcomOnePlusWarmupCountdown.formatMmSs(it) }
            val warmupClockPhase = state.phase == OnePlusWarmupState.Phase.WARMING ||
                state.phase == OnePlusWarmupState.Phase.IDLE ||
                state.phase == OnePlusWarmupState.Phase.PAIRING
            val ringCenter = when {
                DexcomOnePlusWarmupCountdown.showsCountdown(state.phase) && countdownText != null -> countdownText
                warmupClockPhase -> stringResource(R.string.dexcom_oneplus_warmup_countdown_unknown)
                else             -> DexcomOnePlusUiLabels.phaseLabel(state.phase)
            }
            WarmupRing(
                progress = ringProgress,
                ringColor = ringColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = ringCenter,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Honest end-of-warm-up annotation, from the protocol clock or the local fallback. Shown
            // during the connection phases too, so the user keeps a landmark while the sensor's radio
            // window is closed.
            val endMs = state.endsAtEpochMs ?: localFallbackEndsAt
            val endsAtLabel = endMs?.let {
                stringResource(
                    R.string.dexcom_oneplus_warmup_ends_at,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)),
                )
            }
            when (state.phase) {
                OnePlusWarmupState.Phase.READY -> {
                    Text(
                        text = stringResource(R.string.dexcom_oneplus_warmup_done),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OnePlusWarmupState.Phase.FAILED -> {
                    Text(
                        text = stringResource(
                            R.string.dexcom_oneplus_warmup_failed,
                            DexcomOnePlusUiLabels.userMessage(state.message),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OnePlusWarmupState.Phase.CONNECTING,
                OnePlusWarmupState.Phase.RECONNECTING -> {
                    // Link is being (re)established — reassure, don't show a fake countdown or the
                    // terminal red FAILED that used to flash between retries.
                    Text(
                        text = stringResource(R.string.dexcom_oneplus_warmup_connecting_note),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    endsAtLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> {
                    // The mm:ss countdown itself is rendered inside the ring center above; here we
                    // only add the honest end-time / local-fallback annotations.
                    endsAtLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (usingLocalFallback) {
                        Text(
                            text = stringResource(R.string.dexcom_oneplus_warmup_local_fallback_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_no_loop_bg),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (sessionUp) {
                        R.string.dexcom_oneplus_warmup_session_up
                    } else {
                        R.string.dexcom_oneplus_warmup_session_down
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Warm-up ring: a teal-spirit circular indicator sized to the shared BG-circle dimensions.
 * A muted [trackColor] full circle sits under an accent [ringColor] arc. When [progress] is
 * non-null the arc is determinate (0f..1f, sweeping clockwise from the top); when null the arc is a
 * full circle, signalling open-ended (indeterminate) progress without faking a countdown fraction.
 * [content] is centered inside the ring (typically the mm:ss countdown or the phase label).
 */
@Composable
private fun WarmupRing(
    progress: Float?,
    ringColor: Color,
    trackColor: Color,
    content: @Composable () -> Unit,
) {
    val strokePx = with(LocalDensity.current) { AapsSpacing.bgRingStrokeWidth.toPx() }
    Box(
        modifier = Modifier.size(AapsSpacing.bgCircleSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = size.minDimension - strokePx
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            val sweep = if (progress == null) 360f else 360f * progress.coerceIn(0f, 1f)
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        content()
    }
}
