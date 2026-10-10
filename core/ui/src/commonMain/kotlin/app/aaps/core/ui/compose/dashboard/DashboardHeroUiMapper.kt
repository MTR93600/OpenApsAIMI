package app.aaps.core.ui.compose.dashboard

import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.formatKmp
import kotlin.time.Clock

/**
 * Minimal UI state for the dashboard glucose hero, ported from the Android
 * `StatusCardState` (which lives in the app module and carries Android resource IDs).
 *
 * Only the fields consumed by [DashboardHeroUiMapper] are carried over; all strings
 * are pre-formatted by the platform, all colors are ARGB ints supplied via
 * [DashboardHeroPalette].
 */
data class HeroStatusCardState(
    val glucoseText: String,
    val glucoseColor: Int,
    val deltaText: String,
    val timeAgo: String,
    val isGlucoseActual: Boolean,
    val glucoseMgdl: Int? = null,
    val noseAngleDeg: Float? = null,
    val trajectoryRelevanceScore: Double? = null,
    val aimiHealthScore: Double? = null,
    val adaptiveSmoothingQualityTier: AdaptiveSmoothingQualityTier? = null,
    val warmup: CgmWarmupStatus? = null,
    val lifecycle: CgmSensorLifecycle? = null,
)

/**
 * ARGB colors for the hero, supplied by the platform (theme).
 * Replaces `ContextCompat.getColor` / `TypedValue` theme resolution.
 */
data class DashboardHeroPalette(
    val step1: Int,
    val step2: Int,
    val step3: Int,
    val step4: Int,
    val surface: Int,
    val textSecondary: Int,
    val attention: Int,
    val warmupTeal: Int,
)

/**
 * Pre-formatted strings for the hero. Replaces `context.getString(R.string.*)`.
 * Parameterized entries are lambdas so the platform controls formatting.
 */
data class DashboardHeroSensorStrings(
    val sensorEndOfLife: (hours: Int) -> String,
    val sensorEarlyLife: (day: Int, hours: Int) -> String,
    val warmupEndsAt: (time: String) -> String,
    val warmupPhaseConnecting: String,
    val warmupPhaseReconnecting: String,
    val warmupPhasePairing: String,
    val warmupPhaseLabel: String,
)

/**
 * Builds [GlucoseHeroUiState] for the Compose dashboard hero — logic aligned with
 * the Android `CircleTopDashboardView` (ring, nose, telemetry arc).
 *
 * Ported from `plugins/main/.../dashboard/compose/DashboardComposeHeroUiMapper` to
 * commonMain: `Context`/`DateFormat`/`TypedValue` replaced by [DashboardHeroPalette],
 * [DashboardHeroSensorStrings] and a time-formatting lambda (platform supplies
 * `AimiDateFormatter`-backed formatting).
 */
object DashboardHeroUiMapper {

    /** Symbolic placeholder for the mm:ss countdown when the warm-up duration is not yet known. */
    const val COUNTDOWN_PENDING = "--:--"

    /** ms per hour, for lifecycle age / remaining formatting. */
    private const val HOUR_MS = 3_600_000L

    fun buildHeroState(
        state: HeroStatusCardState,
        palette: DashboardHeroPalette,
        strings: DashboardHeroSensorStrings,
        formatTime: (epochMs: Long) -> String,
    ): GlucoseHeroUiState? {
        val warmup = state.warmup
        val hasFreshGlucose = state.glucoseMgdl != null && state.isGlucoseActual
        if (warmup != null && warmup.active && !hasFreshGlucose) {
            return buildWarmupHeroState(state, warmup, palette, strings, formatTime)
        }
        val bgMgdl = state.glucoseMgdl ?: return null
        val arcP = telemetryArcProgress(state)
        val arcC = arcP?.let { telemetryArcColor(it, palette) }
        val ringArgb = GlucoseRingColorComputer.compute(
            bgMgdl = bgMgdl,
            hypoMaxFromProfile = null,
            severeHypoMaxMgdl = 54f,
            hypoMaxMgdlAttr = 70f,
            useSteppedColors = true,
            step1MaxMgdl = 120f,
            step2MaxMgdl = 160f,
            step3MaxMgdl = 220f,
            stepColor1 = palette.step1,
            stepColor2 = palette.step2,
            stepColor3 = palette.step3,
            stepColor4 = palette.step4,
        )
        return GlucoseHeroUiState(
            mainText = state.glucoseText,
            subLeftText = state.deltaText,
            subRightText = state.timeAgo,
            noseAngleDeg = state.noseAngleDeg,
            ringColorArgb = ringArgb,
            centerTextColorArgb = state.glucoseColor,
            subTextColorArgb = palette.textSecondary,
            surfaceColorArgb = palette.surface,
            telemetryProgress = arcP,
            telemetryColorArgb = arcC,
            strokeWidthDp = 4f,
        )
    }

    /**
     * Non-alarming "beginning of life" / "expires soon" subtext for the production sensor, shown UNDER
     * the normal glucose hero (the ring is untouched). Returns null when there is no lifecycle info, no
     * fresh glucose to annotate, or the sensor is in neither its early- nor end-of-life window.
     */
    fun buildLifecycleSubtext(
        state: HeroStatusCardState,
        strings: DashboardHeroSensorStrings,
    ): String? {
        val lifecycle = state.lifecycle ?: return null
        val hasFreshGlucose = state.glucoseMgdl != null && state.isGlucoseActual
        if (!hasFreshGlucose) return null
        val ageMs = lifecycle.ageMs
        val remainingMs = lifecycle.remainingMs
        return when {
            lifecycle.endOfLife && remainingMs != null -> {
                val hours = (remainingMs / HOUR_MS).coerceAtLeast(0L).toInt()
                strings.sensorEndOfLife(hours)
            }
            lifecycle.earlyLife && ageMs != null -> {
                val hours = (ageMs / HOUR_MS).coerceAtLeast(0L).toInt()
                val day = (hours / 24) + 1
                strings.sensorEarlyLife(day, hours)
            }
            else -> null
        }
    }

    private fun telemetryArcProgress(state: HeroStatusCardState): Float? {
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

    private fun telemetryArcColor(progress: Float, palette: DashboardHeroPalette): Int {
        return when {
            progress >= 0.72f -> palette.step1
            progress >= 0.45f -> palette.step2
            else -> palette.step3
        }
    }

    private fun buildWarmupHeroState(
        state: HeroStatusCardState,
        warmup: CgmWarmupStatus,
        palette: DashboardHeroPalette,
        strings: DashboardHeroSensorStrings,
        formatTime: (epochMs: Long) -> String,
    ): GlucoseHeroUiState {
        // Matches Tier 1 (DashboardStagingCard): Clock.System.now().toEpochMilliseconds() in commonMain.
        val now = Clock.System.now().toEpochMilliseconds()
        val endsAt = warmup.endsAtEpochMs
        val remaining = warmup.remainingMs
        val remainingMs: Long? = when {
            endsAt != null -> (endsAt - now).coerceAtLeast(0L)
            remaining != null -> remaining.coerceAtLeast(0L)
            else -> null
        }
        val mainText = remainingMs?.let { formatCountdown(it) } ?: COUNTDOWN_PENDING
        val phaseColor = warmupPhaseColor(warmup.phase, palette)
        val subRight = warmup.endsAtEpochMs?.let { ends ->
            strings.warmupEndsAt(formatTime(ends))
        } ?: ""
        val total = warmup.totalMs
        val progress: Float? =
            if (total != null && total > 0L && remainingMs != null) {
                ((total - remainingMs).toFloat() / total).coerceIn(0f, 1f)
            } else {
                null
            }
        return GlucoseHeroUiState(
            mainText = mainText,
            subLeftText = warmupPhaseLabel(warmup.phase, strings),
            subRightText = subRight,
            noseAngleDeg = null,
            ringColorArgb = phaseColor,
            centerTextColorArgb = phaseColor,
            subTextColorArgb = palette.textSecondary,
            surfaceColorArgb = palette.surface,
            telemetryProgress = progress,
            telemetryColorArgb = progress?.let { phaseColor },
            strokeWidthDp = 4f,
        )
    }

    private fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = remainingMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    private fun warmupPhaseColor(phase: CgmWarmupStatus.Phase, palette: DashboardHeroPalette): Int = when (phase) {
        CgmWarmupStatus.Phase.CONNECTING,
        CgmWarmupStatus.Phase.RECONNECTING -> palette.attention
        CgmWarmupStatus.Phase.WARMING,
        CgmWarmupStatus.Phase.PAIRING,
        CgmWarmupStatus.Phase.OTHER -> palette.warmupTeal
    }

    private fun warmupPhaseLabel(phase: CgmWarmupStatus.Phase, strings: DashboardHeroSensorStrings): String = when (phase) {
        CgmWarmupStatus.Phase.CONNECTING -> strings.warmupPhaseConnecting
        CgmWarmupStatus.Phase.RECONNECTING -> strings.warmupPhaseReconnecting
        CgmWarmupStatus.Phase.PAIRING -> strings.warmupPhasePairing
        CgmWarmupStatus.Phase.WARMING,
        CgmWarmupStatus.Phase.OTHER -> strings.warmupPhaseLabel
    }
}

/** Warm-up countdown formatting is pure; the platform clock comes from the caller. */
