package app.aaps.ui.compose.eversenseCalibrationDialog

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.GlucoseUnit

@Immutable
data class EversenseCalibrationDialogUiState(
    val bg: Double = 0.0,
    val units: GlucoseUnit = GlucoseUnit.MGDL,
    // Eversense accepts a finger-prick reference between 40 and 400 mg/dL. NumberInputRow keeps the
    // typed value inside this range and shows it under the field.
    val bgRange: ClosedFloatingPointRange<Double> = 40.0..400.0,
    val bgStep: Double = 1.0,
    val bgDecimalPlaces: Int = 0,
    val notConnected: Boolean = false,
    val notReadyMessage: String = "",
    val submitting: Boolean = false,
    // True while the typed text is not a number or sits outside bgRange. Such text is never
    // published as `bg`, so without this flag the field could show an error while `bg` still held
    // the last good value, and Send would stay enabled and send that older value instead.
    val inputError: Boolean = false
) {

    val isMgdl: Boolean get() = units == GlucoseUnit.MGDL
    val unitLabel: String get() = units.displayLabel
    val hasValidBg: Boolean get() = bg > 0.0
    val isReady: Boolean get() = notReadyMessage.isEmpty()
    val canSubmit: Boolean get() = hasValidBg && isReady && !submitting && !inputError
}
