package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmKeyValueRow
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.CgmUiState
import app.aaps.plugins.source.compose.CgmWarmupRing
import app.aaps.plugins.source.compose.DexcomOnePlusUiLabels
import app.aaps.plugins.source.compose.DexcomOnePlusWarmupCountdown
import app.aaps.plugins.source.compose.rememberCgmWindow
import app.aaps.plugins.source.compose.toUiState
import app.aaps.plugins.source.logs.DriverLogFilter
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
 *
 * Layout follows the window: in portrait the ring is the subject of the screen and the details sit
 * under it; on a short screen (phone in landscape) the ring moves beside the details, which is the
 * only arrangement that fits about 360 dp of height without cutting anything off.
 */
@AndroidEntryPoint
class DexcomOnePlusWarmupActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusWarmupScreen(
                        onBack = { finish() },
                        onRetry = {
                            val driver = OnePlusCgmDrivers.default()
                            driver.setContext(applicationContext)
                            (driver as? OnePlusCgmDriverReal)?.resumeStoredSession()
                        },
                        onOpenLog = {
                            startActivity(
                                Intent(this, CgmDriverLogActivity::class.java)
                                    .putExtra(CgmDriverLogActivity.EXTRA_FILTER, DriverLogFilter.DEXCOM_ONE_PLUS.name)
                            )
                        },
                        onOpenStart = {
                            startActivity(Intent(this, DexcomOnePlusStartActivity::class.java))
                        },
                        onOpenStatus = {
                            // CLEAR_TOP so coming here from the status screen returns to that
                            // instance instead of stacking a second one on every round trip.
                            startActivity(
                                Intent(this, DexcomOnePlusStatusActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Everything the screen shows, gathered once per tick so both layouts read the same values. */
private data class WarmupUiModel(
    val state: OnePlusWarmupState,
    val sessionUp: Boolean,
    val remainingMs: Long?,
    val localFallbackEndsAt: Long?,
    val usingLocalFallback: Boolean,
)

@Composable
private fun DexcomOnePlusWarmupScreen(
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenStart: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    val context = LocalContext.current
    val window = rememberCgmWindow()
    val driver = remember { OnePlusCgmDrivers.default() }
    // Identity is read once: it only changes when a sensor is started, which leaves this screen.
    val storedSession = remember {
        OnePlusSensorStore(
            context.applicationContext,
            OnePlusCgmDrivers.storeNamespace(SensorSlot.PRODUCTION),
        ).load()
    }
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

    val model = WarmupUiModel(
        state = state,
        sessionUp = sessionUp,
        remainingMs = remainingMs,
        localFallbackEndsAt = localFallbackEndsAt,
        usingLocalFallback = usingLocalFallback,
    )

    CgmScaffold(
        title = stringResource(R.string.dexcom_oneplus_warmup_title),
        onNavigate = onBack,
        actions = {
            IconButton(onClick = onOpenLog) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cgm_driver_log_open),
                )
            }
        },
        // The short layout places the ring beside the text and manages its own width.
        constrainWidth = !window.isShort,
    ) {
        if (window.isShort) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AapsSpacing.extraLarge),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.xxLarge),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CgmWarmupRing(
                    progress = ringProgress(model),
                    state = model.state.phase.toUiState(),
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                ) {
                    RingCenter(model)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                ) {
                    WarmupDetails(
                        model = model,
                        deviceName = storedSession?.lastDeviceName,
                        deviceAddress = storedSession?.lastMac,
                        compactHeadline = true,
                        onRetry = onRetry,
                        onOpenLog = onOpenLog,
                        onOpenStart = onOpenStart,
                        onOpenStatus = onOpenStatus,
                    )
                }
            }
        } else {
            CgmLazyColumn {
                item(key = "ring") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                    ) {
                        StateChips(model)
                        // Width only: the box wraps the ring's own height, so the ring is not
                        // parked in the middle of a full width square of empty space.
                        CgmWarmupRing(
                            progress = ringProgress(model),
                            state = model.state.phase.toUiState(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RingCenter(model)
                        }
                    }
                }
                item(key = "details") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
                    ) {
                        WarmupDetails(
                            model = model,
                            deviceName = storedSession?.lastDeviceName,
                            deviceAddress = storedSession?.lastMac,
                            compactHeadline = false,
                            onRetry = onRetry,
                            onOpenLog = onOpenLog,
                            onOpenStart = onOpenStart,
                            onOpenStatus = onOpenStatus,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Determinate only when the local ~30 min fallback owns the clock, because only then is the total
 * known. Otherwise a full ring signals honest, open-ended progress instead of inventing a fraction.
 */
private fun ringProgress(model: WarmupUiModel): Float? =
    if (model.state.phase == OnePlusWarmupState.Phase.WARMING && model.usingLocalFallback) {
        val remaining = model.remainingMs ?: 0L
        (1f - remaining.toFloat() / DexcomOnePlusWarmupCountdown.LOCAL_FALLBACK_DURATION_MS)
            .coerceIn(0f, 1f)
    } else {
        null
    }

@Composable
private fun RingCenter(model: WarmupUiModel) {
    // Keep the mm:ss countdown while the link is being re-established: a normal duty-cycle
    // disconnect during warm-up used to blank it until the next EGV packet.
    val countdownText = model.remainingMs?.let { DexcomOnePlusWarmupCountdown.formatMmSs(it) }
    val warmupClockPhase = model.state.phase == OnePlusWarmupState.Phase.WARMING ||
        model.state.phase == OnePlusWarmupState.Phase.IDLE ||
        model.state.phase == OnePlusWarmupState.Phase.PAIRING
    val centerText = when {
        DexcomOnePlusWarmupCountdown.showsCountdown(model.state.phase) && countdownText != null -> countdownText
        warmupClockPhase                                                                        ->
            stringResource(R.string.dexcom_oneplus_warmup_countdown_unknown)

        else                                                                                    ->
            DexcomOnePlusUiLabels.phaseLabel(model.state.phase)
    }
    val showsRemainingLabel = countdownText != null &&
        DexcomOnePlusWarmupCountdown.showsCountdown(model.state.phase)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
    ) {
        Text(
            text = centerText,
            style = MaterialTheme.typography.headlineMedium,
            color = if (model.state.phase == OnePlusWarmupState.Phase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
        )
        if (showsRemainingLabel) {
            Text(
                text = stringResource(R.string.cgm_warmup_remaining_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StateChips(model: WarmupUiModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        CgmStateChip(
            state = model.state.phase.toUiState(),
            label = DexcomOnePlusUiLabels.phaseLabel(model.state.phase),
        )
        CgmStateChip(
            state = if (model.sessionUp) CgmUiState.Ready else CgmUiState.Waiting,
            label = stringResource(
                if (model.sessionUp) R.string.dexcom_oneplus_session_up else R.string.dexcom_oneplus_session_down,
            ),
        )
    }
}

@Composable
private fun ColumnScope.WarmupDetails(
    model: WarmupUiModel,
    deviceName: String?,
    deviceAddress: String?,
    compactHeadline: Boolean,
    onRetry: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenStart: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    if (compactHeadline) StateChips(model)

    // Honest end-of-warm-up annotation, from the protocol clock or the local fallback. Shown during
    // the connection phases too, so the user keeps a landmark while the sensor's radio window is
    // closed.
    val endMs = model.state.endsAtEpochMs ?: model.localFallbackEndsAt
    endMs?.let {
        Text(
            text = stringResource(
                R.string.cgm_warmup_ends_at,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
    }

    when (model.state.phase) {
        OnePlusWarmupState.Phase.READY  -> {
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_done),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OnePlusWarmupState.Phase.FAILED -> {
            // An interrupted warm-up used to be a red line and nothing else. These are the three
            // ways out that actually exist, in the order they are worth trying.
            CgmCard(tone = CgmCardTone.Warning) {
                CgmCardHeader(stringResource(R.string.cgm_warmup_what_happened))
                Text(
                    text = DexcomOnePlusUiLabels.userMessage(model.state.message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dexcom_oneplus_warmup_retry))
            }
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cgm_driver_log_open))
            }
            TextButton(onClick = onOpenStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dexcom_oneplus_warmup_start_other))
            }
        }

        OnePlusWarmupState.Phase.CONNECTING,
        OnePlusWarmupState.Phase.RECONNECTING -> {
            // Link is being (re)established — reassure, don't show a fake countdown or the terminal
            // red FAILED that used to flash between retries.
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_connecting_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else                            -> {
            Text(
                text = stringResource(R.string.dexcom_oneplus_warmup_no_loop_bg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Typed driver signals the old screen carried but never showed: how long the sensor has been
    // silent, and whether the sensor answering nearby is not the paired one at all.
    model.state.advSilenceMinutes?.let { minutes ->
        Text(
            text = stringResource(R.string.dexcom_oneplus_notif_waiting_adv, minutes.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (model.state.staleMacSuspected) {
        CgmCard(tone = CgmCardTone.Warning) {
            CgmCardHeader(stringResource(R.string.dexcom_oneplus_notif_stale_mac_title))
            Text(
                text = stringResource(R.string.dexcom_oneplus_notif_stale_mac_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenStart) {
                Text(stringResource(R.string.dexcom_oneplus_warmup_start_other))
            }
        }
    }

    // Which sensor this countdown belongs to — a real question once a pre-soak sensor can be
    // warming up at the same time.
    if (deviceName != null || deviceAddress != null) {
        CgmCard {
            CgmCardHeader(stringResource(R.string.cgm_sensor_heading))
            deviceName?.let {
                CgmKeyValueRow(stringResource(R.string.cgm_sensor_name), it)
            }
            deviceAddress?.let {
                CgmKeyValueRow(stringResource(R.string.cgm_sensor_address), it)
            }
            if (model.usingLocalFallback) {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_warmup_local_fallback_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (model.state.phase != OnePlusWarmupState.Phase.FAILED) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cgm_driver_log_open))
            }
            OutlinedButton(onClick = onOpenStatus, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dexcom_oneplus_open_status))
            }
        }
    }
}
