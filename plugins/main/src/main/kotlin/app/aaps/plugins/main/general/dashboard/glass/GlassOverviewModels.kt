package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.data.model.RM

import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.StatusLevel

/** UI state for the ported Glass StatusAgoraCard — trimmed to only the fields it renders. */
data class GlassUiState(
    val currentBg: String = "--",
    val unit: String = "mg/dL",
    val glucoseColor: Int = 0xFF94A3B8.toInt(),
    /** Colour of the big digits along the glucose value; 0 means use [glucoseColor]. */
    val glucoseDigitsColor: Int = 0,
    val deltaText: String = "",
    val trendArrowRes: Int? = null,
    val timeAgo: String = "--",
    val insulinAge: String = "--",
    val insulinAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val insulinLabel: String = "Insulin",
    val cannulaAge: String = "--",
    val cannulaAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val cannulaLabel: String = "Cannula",
    val batteryAge: String = "--",
    val batteryAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val batteryLabel: String = "Battery",
    val sensorAge: String = "--",
    val sensorAgeStatus: StatusLevel = StatusLevel.UNSPECIFIED,
    val sensorLabel: String = "Sensor",
    val loopStatusText: String = "Loop",
    /** The running mode; the main screen shows its icon next to the glucose. */
    val loopMode: RM.Mode? = null,
    val loopIsRunning: Boolean = true,
    val iobText: String = "--",
    val isTempTargetActive: Boolean = false,
    val targetText: String = "--",
    val basalPercentText: String = "--",
    val stepsText: String = "--",
    val hrText: String = "--",
    val lastBolusText: String = "--",
    val lastCarbsText: String = "--",
    /** Insulin left in the pump reservoir, for the optional reservoir block. */
    val reservoirText: String = "--",
    /** Today's time in range since midnight; null early in the day or without data (ring shows an empty track). */
    val tir: GlassTir? = null,
    /** CGM link state of the active source (connecting, reconnecting, warm-up); null when it reports none. */
    val warmup: CgmWarmupStatus? = null,
    /** False when the shown glucose is older than 9 min (from the view model). */
    val isGlucoseActual: Boolean = true,
    /** Time of the shown glucose, to keep "last value N min ago" moving between refreshes. */
    val glucoseTimestampMs: Long? = null,
    /** Daily numbers for the dashboard row under the glucose. */
    val avgBgText: String = "--",
    val cvText: String = "--",
    val gmiText: String = "--",
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
