package app.aaps.plugins.main.general.dashboard.compose

import android.content.Context
import android.text.format.DateFormat
import android.util.TypedValue
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.dashboard.GlucoseHeroUiState
import app.aaps.core.ui.views.GlucoseRingColorComputer
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import java.util.Date
import java.util.Locale

/**
 * Builds [GlucoseHeroUiState] for the Compose dashboard hero — logic aligned with
 * [app.aaps.plugins.main.general.dashboard.views.CircleTopDashboardView] (ring, nose, telemetry arc).
 */
internal object DashboardComposeHeroUiMapper {

    /** Symbolic placeholder for the mm:ss countdown when the warm-up duration is not yet known. */
    private const val COUNTDOWN_PENDING = "--:--"

    fun buildHeroState(context: Context, state: StatusCardState): GlucoseHeroUiState? {
        // Warm-up takes over the hero only while the active source is warming up / (re)connecting AND
        // there is no fresh glucose to show. As soon as a valid, actual reading is present the normal
        // glucose ring wins (clean handoff). Sources that don't report warm-up leave [state.warmup] null,
        // so this branch is never taken and the glucose path below is byte-identical to before.
        val warmup = state.warmup
        val hasFreshGlucose = state.glucoseMgdl != null && state.isGlucoseActual
        if (warmup != null && warmup.active && !hasFreshGlucose) {
            return buildWarmupHeroState(context, warmup)
        }
        val bgMgdl = state.glucoseMgdl ?: return null
        val arcP = telemetryArcProgress(state)
        val arcC = arcP?.let { telemetryArcColor(context, it) }
        val step1 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step1)
        val step2 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step2)
        val step3 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step3)
        val step4 = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_step4)
        val ringArgb = GlucoseRingColorComputer.compute(
            bgMgdl = bgMgdl,
            hypoMaxFromProfile = null,
            severeHypoMaxMgdl = 54f,
            hypoMaxMgdlAttr = 70f,
            useSteppedColors = true,
            step1MaxMgdl = 120f,
            step2MaxMgdl = 160f,
            step3MaxMgdl = 220f,
            stepColor1 = step1,
            stepColor2 = step2,
            stepColor3 = step3,
            stepColor4 = step4,
        )
        return GlucoseHeroUiState(
            mainText = state.glucoseText,
            subLeftText = state.deltaText,
            subRightText = state.timeAgo,
            noseAngleDeg = state.noseAngleDeg,
            ringColorArgb = ringArgb,
            centerTextColorArgb = state.glucoseColor,
            subTextColorArgb = resolveThemeColor(context, android.R.attr.textColorSecondary),
            surfaceColorArgb = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_surface),
            telemetryProgress = arcP,
            telemetryColorArgb = arcC,
            strokeWidthDp = 4f,
        )
    }

    /** ms per hour, for lifecycle age / remaining formatting. */
    private const val HOUR_MS = 3_600_000L

    /**
     * Non-alarming "beginning of life" / "expires soon" subtext for the production sensor, shown UNDER
     * the normal glucose hero (the ring is untouched). Returns null when there is no lifecycle info, no
     * fresh glucose to annotate, or the sensor is in neither its early- nor end-of-life window.
     *
     * - early life → "Day N · settling · Xh" (readings may be a little jumpy while the sensor settles).
     * - end of life → "Expires in Xh" (time to prepare a new sensor).
     */
    fun buildLifecycleSubtext(context: Context, state: StatusCardState): String? {
        val lifecycle = state.lifecycle ?: return null
        val hasFreshGlucose = state.glucoseMgdl != null && state.isGlucoseActual
        if (!hasFreshGlucose) return null
        // Smart-cast across a module boundary is not possible; capture into locals first.
        val ageMs = lifecycle.ageMs
        val remainingMs = lifecycle.remainingMs
        return when {
            lifecycle.endOfLife && remainingMs != null -> {
                val hours = (remainingMs / HOUR_MS).coerceAtLeast(0L).toInt()
                context.getString(R.string.dashboard_sensor_end_of_life, hours)
            }

            lifecycle.earlyLife && ageMs != null       -> {
                val hours = (ageMs / HOUR_MS).coerceAtLeast(0L).toInt()
                val day = (hours / 24) + 1
                context.getString(R.string.dashboard_sensor_early_life, day, hours)
            }

            else                                       -> null
        }
    }

    private fun telemetryArcProgress(state: StatusCardState): Float? {
        val rel = state.trajectoryRelevanceScore
        val health = state.aimiHealthScore
        val tierProxy = state.adaptiveSmoothingQualityTier?.let { tier ->
            when (tier) {
                AdaptiveSmoothingQualityTier.OK -> 0.88
                AdaptiveSmoothingQualityTier.UNCERTAIN -> 0.58
                AdaptiveSmoothingQualityTier.BAD -> 0.35
            }
        }
        val combined: Double? = when {
            rel != null && health != null -> 0.5 * (rel + health)
            rel != null -> rel
            health != null -> health
            tierProxy != null -> tierProxy
            else -> null
        }
        return combined?.toFloat()?.coerceIn(0f, 1f)
    }

    private fun telemetryArcColor(context: Context, progress: Float): Int {
        val resId = when {
            progress >= 0.72f -> app.aaps.core.ui.R.color.glucose_ring_step1
            progress >= 0.45f -> app.aaps.core.ui.R.color.glucose_ring_step2
            else -> app.aaps.core.ui.R.color.glucose_ring_step3
        }
        return ContextCompat.getColor(context, resId)
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else 0xFF888888.toInt()
    }

    /**
     * Builds the warm-up hero: countdown mm:ss (or [COUNTDOWN_PENDING] when the duration is unknown)
     * as the main text, an honest phase label above it, and the end time below it. Colours resolve from
     * this module's dashboard palette / the current theme — no hard-coded ARGB, no vendor reference.
     * The telemetry arc is left off because the contract exposes only the remaining time, not the total
     * warm-up duration, so an elapsed/total fraction cannot be derived honestly.
     */
    private fun buildWarmupHeroState(context: Context, warmup: CgmWarmupStatus): GlucoseHeroUiState {
        val now = System.currentTimeMillis()
        // Capture the cross-module properties into locals: Kotlin cannot smart-cast public API
        // properties declared in another module (core:interfaces) after a null check.
        val endsAt = warmup.endsAtEpochMs
        val remaining = warmup.remainingMs
        val remainingMs: Long? = when {
            endsAt != null    -> (endsAt - now).coerceAtLeast(0L)
            remaining != null -> remaining.coerceAtLeast(0L)
            else              -> null
        }
        val mainText = remainingMs?.let { formatCountdown(it) } ?: COUNTDOWN_PENDING
        val phaseColor = ContextCompat.getColor(context, warmupPhaseColorRes(warmup.phase))
        val subRight = warmup.endsAtEpochMs?.let { ends ->
            context.getString(
                R.string.dashboard_warmup_ends_at,
                DateFormat.getTimeFormat(context).format(Date(ends)),
            )
        } ?: ""
        // Determinate progress arc when the source supplies the nominal total (fills as it counts
        // down); null → indeterminate ring (e.g. connecting, or total unknown).
        val total = warmup.totalMs
        val progress: Float? =
            if (total != null && total > 0L && remainingMs != null) {
                ((total - remainingMs).toFloat() / total).coerceIn(0f, 1f)
            } else {
                null
            }
        return GlucoseHeroUiState(
            mainText = mainText,
            subLeftText = context.getString(warmupPhaseLabelRes(warmup.phase)),
            subRightText = subRight,
            noseAngleDeg = null,
            ringColorArgb = phaseColor,
            centerTextColorArgb = phaseColor,
            subTextColorArgb = resolveThemeColor(context, android.R.attr.textColorSecondary),
            surfaceColorArgb = ContextCompat.getColor(context, app.aaps.core.ui.R.color.glucose_ring_surface),
            telemetryProgress = progress,
            telemetryColorArgb = progress?.let { phaseColor },
            strokeWidthDp = 4f,
        )
    }

    private fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = remainingMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun warmupPhaseColorRes(phase: CgmWarmupStatus.Phase): Int = when (phase) {
        // Amber = "not ready / (re)connecting" (honest: no session yet).
        CgmWarmupStatus.Phase.CONNECTING,
        CgmWarmupStatus.Phase.RECONNECTING -> R.color.dashboard_metric_attention
        // Teal = benign warm-up / pairing in progress.
        CgmWarmupStatus.Phase.WARMING,
        CgmWarmupStatus.Phase.PAIRING,
        CgmWarmupStatus.Phase.OTHER        -> R.color.dashboard_warmup_teal
    }

    private fun warmupPhaseLabelRes(phase: CgmWarmupStatus.Phase): Int = when (phase) {
        CgmWarmupStatus.Phase.CONNECTING   -> R.string.dashboard_warmup_phase_connecting
        CgmWarmupStatus.Phase.RECONNECTING -> R.string.dashboard_warmup_phase_reconnecting
        CgmWarmupStatus.Phase.PAIRING      -> R.string.dashboard_warmup_phase_pairing
        CgmWarmupStatus.Phase.WARMING,
        CgmWarmupStatus.Phase.OTHER        -> R.string.dashboard_warmup_label
    }
}
