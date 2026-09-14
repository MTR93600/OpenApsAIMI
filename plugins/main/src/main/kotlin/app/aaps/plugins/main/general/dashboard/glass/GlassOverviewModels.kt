package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.ui.compose.StatusLevel

/** UI state for the ported Glass StatusAgoraCard — trimmed to only the fields it renders. */
data class GlassUiState(
    val currentBg: String = "--",
    val unit: String = "mg/dL",
    val glucoseColor: Int = 0xFF94A3B8.toInt(),
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
    val loopIsRunning: Boolean = true,
    val iobText: String = "--",
    val isTempTargetActive: Boolean = false,
    val targetText: String = "--",
    val basalPercentText: String = "--",
    val stepsText: String = "--",
    val hrText: String = "--",
    val lastBolusText: String = "--",
    val lastCarbsText: String = "--",
)
