package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel

/**
 * UI state for the ported Glass StatusAgoraCard — trimmed to only the fields it renders.
 *
 * Colours and icons are not stored here as Android ids. [glucoseRange] says low / in range / high and the
 * UI maps it to a theme colour; [trendArrow] is the platform neutral trend and the UI picks its icon.
 * A null label means "use the default label" (a string resource the UI resolves).
 */
data class GlassUiState(
    val currentBg: String = "--",
    val unit: String = "mg/dL",
    /** Range of the shown glucose, for its colour. Null when unknown: the UI then uses the neutral grey 0xFF94A3B8. */
    val glucoseRange: BgRange? = null,
    val deltaText: String = "",
    val trendArrow: TrendArrow? = null,
    val timeAgo: String = "--",
    val insulinAge: String = "--",
    val insulinAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val insulinLabel: String? = null,
    val cannulaAge: String = "--",
    val cannulaAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val cannulaLabel: String? = null,
    val batteryAge: String = "--",
    val batteryAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val batteryLabel: String? = null,
    val sensorAge: String = "--",
    val sensorAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val sensorLabel: String? = null,
    /** Null shows the default "Loop" label. */
    val loopStatusText: String? = null,
    val loopIsRunning: Boolean = true,
    val iobText: String = "--",
    val isTempTargetActive: Boolean = false,
    val targetText: String = "--",
    val basalPercentText: String = "--",
    val stepsText: String = "--",
    val hrText: String = "--",
    val lastBolusText: String = "--",
    val lastCarbsText: String = "--",
    /** Today's time in range since midnight; null early in the day or without data (ring shows an empty track). */
    val tir: GlassTir? = null,
    /** CGM link state of the active source (connecting, reconnecting, warm-up); null when it reports none. */
    val warmup: CgmWarmupStatus? = null,
    /** False when the shown glucose is older than 9 min (from the view model). */
    val isGlucoseActual: Boolean = true,
    /** Time of the shown glucose, to keep "last value N min ago" moving between refreshes. */
    val glucoseTimestampMs: Long? = null,
)

/**
 * Today's time-in-range split (70–180 mg/dL) for the ring around the glucose value.
 * The three shares are percentages of today's readings and add up to about 100.
 *
 * @param deltaVsYesterday today's in-range % minus yesterday's, rounded; null when yesterday has no data
 */
data class GlassTir(
    val belowPct: Float,
    val inRangePct: Float,
    val abovePct: Float,
    val deltaVsYesterday: Int?,
)
