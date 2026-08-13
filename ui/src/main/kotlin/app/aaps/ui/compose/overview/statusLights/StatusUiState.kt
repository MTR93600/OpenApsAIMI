package app.aaps.ui.compose.overview.statusLights

import androidx.compose.runtime.Immutable

/**
 * UI state for Overview status section (sensor/insulin/cannula/battery).
 *
 * [warmUpStatus] and [secondSensorStatus] are transient: they only exist while a CGM source reports
 * a warm-up, or while a second sensor is being warmed up next to the one in use. Both stay null for
 * every source that does not report them, so the section looks exactly as before.
 *
 * @param canPromoteSecondSensor the second sensor has settled and may now feed the loop, so the row
 *   offers the switch. False while it is still warming up or settling — the source refuses early
 *   switches anyway, and its own screen is the place that explains the risk of forcing one.
 * @param promotionMessage result of the last switch attempt (done, or why it was refused), shown
 *   once and then cleared by [StatusViewModel.clearPromotionMessage].
 */
@Immutable
data class StatusUiState(
    val sensorStatus: StatusItem? = null,
    val warmUpStatus: StatusItem? = null,
    val secondSensorStatus: StatusItem? = null,
    val canPromoteSecondSensor: Boolean = false,
    val promotionMessage: String? = null,
    val insulinStatus: StatusItem? = null,
    val cannulaStatus: StatusItem? = null,
    val batteryStatus: StatusItem? = null,
    val showFill: Boolean = false,
    val showPumpBatteryChange: Boolean = false,
    val isPatchPump: Boolean = false
)