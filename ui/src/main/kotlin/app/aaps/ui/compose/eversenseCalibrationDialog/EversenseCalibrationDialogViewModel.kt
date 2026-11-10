package app.aaps.ui.compose.eversenseCalibrationDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt
import app.aaps.core.ui.R as CoreUiR

@HiltViewModel
@Stable
class EversenseCalibrationDialogViewModel @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val eversenseCalibrationSource: EversenseCalibrationSource,
    private val uel: UserEntryLogger,
    private val rh: ResourceHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(EversenseCalibrationDialogUiState())
    val uiState: StateFlow<EversenseCalibrationDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data object CalibrationAccepted : SideEffect()
        data class CalibrationFailed(val message: String) : SideEffect()
    }

    // replay = 1 so a side effect emitted just before the screen rotates still reaches the new
    // collector — same reasoning as CalibrationDialogViewModel.
    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    init {
        val units = profileUtil.units
        val isMmol = units == GlucoseUnit.MMOL
        // The bound is one rule in mg/dL, shown in whichever unit the user reads. 40 mg/dL is
        // 2.2 mmol/L and 400 mg/dL is 22.2 mmol/L.
        val lowMgdl = EversenseCalibrationSource.MIN_CALIBRATION_MGDL.toDouble()
        val highMgdl = EversenseCalibrationSource.MAX_CALIBRATION_MGDL.toDouble()
        _uiState.update {
            EversenseCalibrationDialogUiState(
                units = units,
                bgRange = profileUtil.fromMgdlToUnits(lowMgdl)..profileUtil.fromMgdlToUnits(highMgdl),
                bgStep = if (isMmol) 0.1 else 1.0,
                bgDecimalPlaces = if (isMmol) 1 else 0,
                notConnected = !eversenseCalibrationSource.isConnected(),
                notReadyMessage = eversenseCalibrationSource.readinessMessage()
            )
        }
    }

    fun updateBg(value: Double) {
        _uiState.update { it.copy(bg = value) }
    }

    /** Called by the input field when its text becomes bad, and again when it becomes good. */
    fun updateBgInputError(hasError: Boolean) {
        _uiState.update { it.copy(inputError = hasError) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        // roundToInt, not toInt: 2.2 mmol/L is 39.63 mg/dL and truncating would push it below the
        // lowest value the transmitter accepts.
        val bgMgdl = profileUtil.convertToMgdl(state.bg, state.units).roundToInt()
        // Check the bound here as well, not only in the input field. This value becomes the
        // transmitter's own reference and shifts every reading it sends afterwards, so the last
        // guard must sit next to the call that sends it. The check is in mg/dL because that is the
        // unit the rule is written in: the range shown in the field is rounded to one decimal in
        // mmol/L, so comparing the displayed number against the raw range would reject 2.2 mmol/L.
        if (bgMgdl < EversenseCalibrationSource.MIN_CALIBRATION_MGDL || bgMgdl > EversenseCalibrationSource.MAX_CALIBRATION_MGDL) {
            viewModelScope.launch {
                _sideEffect.emit(
                    SideEffect.CalibrationFailed(
                        rh.gs(
                            CoreUiR.string.eversense_calibration_value_out_of_range,
                            profileUtil.fromMgdlToStringWithUnits(EversenseCalibrationSource.MIN_CALIBRATION_MGDL.toDouble()),
                            profileUtil.fromMgdlToStringWithUnits(EversenseCalibrationSource.MAX_CALIBRATION_MGDL.toDouble())
                        )
                    )
                )
            }
            return
        }
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                val success = eversenseCalibrationSource.calibrate(bgMgdl)
                if (success) {
                    uel.log(
                        action = Action.CALIBRATION,
                        source = Sources.Eversense,
                        value = ValueWithUnit.fromGlucoseUnit(state.bg, state.units)
                    )
                    _sideEffect.emit(SideEffect.CalibrationAccepted)
                } else {
                    _sideEffect.emit(SideEffect.CalibrationFailed(rh.gs(R.string.eversense_calibration_send_failed)))
                }
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }
}
