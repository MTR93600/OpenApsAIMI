package app.aaps.ui.compose.calibrationDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.data.ui.ConfirmationRole
import app.aaps.core.data.ui.confirmationLines
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.SensorCalibrationResult
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.interfaces.utils.DateUtil
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
import kotlin.math.abs
import kotlin.math.roundToInt
import app.aaps.core.ui.R as CoreUiR

@HiltViewModel
@Stable
class CalibrationDialogViewModel @Inject constructor(
    private val profileUtil: ProfileUtil,
    @Suppress("unused") private val profileFunction: ProfileFunction,
    private val xDripBroadcast: XDripBroadcast,
    private val xDripSource: XDripSource,
    private val uel: UserEntryLogger,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val rh: ResourceHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationDialogUiState())
    val uiState: StateFlow<CalibrationDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {

        /**
         * @param message set when the entry was saved but did not yet change the sensor value
         *   (e.g. the session's first entry, or a fit outside the safe range) — the dialog shows
         *   it before navigating back, so the user is not left thinking nothing happened.
         */
        data class EntryAccepted(val message: String?) : SideEffect()
        data class EntryRejected(val message: String) : SideEffect()
    }

    // replay = 1 so that an EntryAccepted emitted just before screen rotation reaches the
    // recreated collector and the user still navigates back. Snackbar messages may re-show
    // once after rotation; acceptable trade-off to avoid losing navigation events.
    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    init {
        val units = profileUtil.units
        val sensorBgMgdl = glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0
        val currentBg = profileUtil.fromMgdlToUnits(sensorBgMgdl)
        val isMmol = units == GlucoseUnit.MMOL

        _uiState.update {
            CalibrationDialogUiState(
                bg = currentBg,
                units = units,
                bgRange = if (isMmol) 2.0..30.0 else 36.0..500.0,
                bgStep = if (isMmol) 0.1 else 1.0,
                bgDecimalPlaces = if (isMmol) 1 else 0,
                sensorBgMgdl = sensorBgMgdl
            )
        }
        refreshPreconditions()
    }

    private fun refreshPreconditions() {
        viewModelScope.launch {
            val result = activePlugin.activeCalibration.checkPreconditions()
            _uiState.update { it.copy(preconditions = result) }
        }
    }

    fun markSensorChangeNow() {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                val timestamp = dateUtil.now()
                persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = TE(
                        timestamp = timestamp,
                        type = TE.Type.SENSOR_CHANGE,
                        glucoseUnit = GlucoseUnit.MGDL
                    ),
                    action = Action.CAREPORTAL,
                    source = Sources.SensorInsert,
                    note = null,
                    listValues = listOf(
                        ValueWithUnit.Timestamp(timestamp),
                        ValueWithUnit.TEType(TE.Type.SENSOR_CHANGE)
                    )
                )
                val result = activePlugin.activeCalibration.checkPreconditions()
                _uiState.update { it.copy(preconditions = result) }
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun updateBg(value: Double) {
        _uiState.update { it.copy(bg = value, gapWarning = gapWarningFor(value, it)) }
    }

    /**
     * Advisory check only. The calibration engine needs at least two entries before it corrects
     * anything, so a single very different fingerstick value changes nothing. Tell the user that
     * instead of letting them believe the sensor was fixed.
     */
    private fun gapWarningFor(value: Double, state: CalibrationDialogUiState): CalibrationGapWarning? {
        val sensorBgMgdl = state.sensorBgMgdl
        if (value <= 0.0 || sensorBgMgdl <= 0.0) return null
        val bloodMgdl = profileUtil.convertToMgdl(value, state.units)
        if (!isLargeGap(bloodMgdl, sensorBgMgdl)) return null
        return CalibrationGapWarning(
            bloodValue = value,
            sensorValue = profileUtil.fromMgdlToUnits(sensorBgMgdl, state.units)
        )
    }

    fun hasAction(): Boolean = uiState.value.bg > 0.0

    private var confirmedState: CalibrationDialogUiState? = null

    fun buildConfirmationSummary(): List<ConfirmationLine> {
        val state = uiState.value
        confirmedState = state
        val bgText = profileUtil.stringInCurrentUnitsDetect(state.bg)
        val bgWithUnit = rh.gs(CoreUiR.string.value_with_unit, bgText, state.unitLabel)
        return confirmationLines {
            line(ConfirmationRole.PRIMARY, rh.gs(CoreUiR.string.confirmation_line, rh.gs(CoreUiR.string.bg_label), bgWithUnit))
        }
    }

    fun confirmAndSave() {
        if (_uiState.value.submitting) return
        val state = confirmedState ?: return
        if (state.bg <= 0) return
        _uiState.update { it.copy(submitting = true) }
        val bgMgdl = profileUtil.convertToMgdl(state.bg, state.units)
        val timestamp = dateUtil.now()
        viewModelScope.launch {
            try {
                val unitValue = ValueWithUnit.fromGlucoseUnit(state.bg, state.units)
                if (sendToSensor(bgMgdl, timestamp, unitValue)) return@launch
                val result = activePlugin.activeCalibration.addEntry(bgMgdl, timestamp)
                when (result) {
                    AddEntryResult.Accepted    -> {
                        uel.log(action = Action.CALIBRATION, source = Sources.CalibrationDialog, value = unitValue)
                        if (xDripSource.isEnabled()) xDripBroadcast.sendCalibration(state.bg)
                        val message = notYetEffectiveMessage(activePlugin.activeCalibration.status())
                        _sideEffect.emit(SideEffect.EntryAccepted(message))
                    }

                    is AddEntryResult.Rejected -> {
                        val message = result.message()
                        uel.log(
                            action = Action.CALIBRATION,
                            source = Sources.CalibrationDialog,
                            note = "rejected: $message",
                            value = unitValue
                        )
                        _sideEffect.emit(SideEffect.EntryRejected(message))
                    }
                }
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    /**
     * Hand the value to the sensor instead of fitting a line on top of it, when the source does that.
     *
     * Never both. A sensor that holds its own calibration re-bases its algorithm, so a line fitted
     * in the app on top of the corrected readings would apply the correction a second time. The way
     * that is enforced is simply not to store a calibration entry at all — with nothing to fit, the
     * software plugin stays identity for this sensor by itself, which is also what xDrip does for
     * this sensor family.
     *
     * The value is only on its way when this returns: the sensor sleeps between its radio windows,
     * and this one does not report back in a way the app can read (see
     * docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md §2c). So the user is told it was sent and told to
     * check, never told it was accepted.
     *
     * @return true when the value was routed to the sensor and nothing else should be done with it
     */
    private suspend fun sendToSensor(bgMgdl: Double, timestamp: Long, unitValue: ValueWithUnit): Boolean {
        val source = activePlugin.activeBgSource
        if (!source.calibratesInSensor()) return false
        when (val sent = source.calibrateSensor(bgMgdl.roundToInt(), timestamp)) {
            SensorCalibrationResult.Queued       -> {
                uel.log(
                    action = Action.CALIBRATION,
                    source = Sources.CalibrationDialog,
                    note = "sent to sensor",
                    value = unitValue
                )
                _sideEffect.emit(SideEffect.EntryAccepted(rh.gs(R.string.cal_sent_to_sensor)))
            }

            is SensorCalibrationResult.Refused   -> {
                uel.log(
                    action = Action.CALIBRATION,
                    source = Sources.CalibrationDialog,
                    note = "sensor refused: ${sent.reason}",
                    value = unitValue
                )
                _sideEffect.emit(SideEffect.EntryRejected(sent.reason))
            }

            SensorCalibrationResult.NotSupported -> return false
        }
        return true
    }

    // NoSession/WarmUp are unreachable here: addEntry() already required a running, past-warm-up
    // session for the entry to be Accepted in the first place.
    private fun notYetEffectiveMessage(status: CalibrationStatus): String? = when (status) {
        is CalibrationStatus.NeedMoreEntries -> rh.gs(R.string.cal_saved_need_more_entries, status.entryCount)
        CalibrationStatus.UnsafeFit          -> rh.gs(R.string.cal_saved_unsafe_fit)
        else                                  -> null
    }

    fun preconditionMessage(rejected: AddEntryResult.Rejected): String = when (rejected) {
        AddEntryResult.Rejected.NoSession       -> rh.gs(R.string.cal_precheck_no_session)
        is AddEntryResult.Rejected.InWarmUp     -> rh.gs(R.string.cal_precheck_warmup, dateUtil.timeString(rejected.warmUpEndsAt))
        is AddEntryResult.Rejected.DeltaTooHigh -> rh.gs(
            R.string.cal_precheck_delta_too_high,
            formatDeltaInDisplayUnit(rejected.deltaMgdlPer5Min),
            formatDeltaInDisplayUnit(rejected.thresholdMgdlPer5Min),
            profileUtil.unitLabel
        )

        AddEntryResult.Rejected.NoSensorPair    -> rh.gs(R.string.cal_precheck_no_pair)
    }

    private fun AddEntryResult.Rejected.message(): String = when (this) {
        is AddEntryResult.Rejected.DeltaTooHigh -> rh.gs(
            R.string.cal_reject_delta_too_high,
            formatDeltaInDisplayUnit(deltaMgdlPer5Min),
            formatDeltaInDisplayUnit(thresholdMgdlPer5Min),
            profileUtil.unitLabel
        )

        AddEntryResult.Rejected.NoSensorPair    -> rh.gs(R.string.cal_reject_no_pair)
        is AddEntryResult.Rejected.InWarmUp     -> rh.gs(R.string.cal_reject_warmup)
        AddEntryResult.Rejected.NoSession       -> rh.gs(R.string.cal_reject_no_session)
    }

    // Input is already in mg/dL per 5 min — just convert to the user's display unit.
    private fun formatDeltaInDisplayUnit(mgdlPer5Min: Double): String {
        val displayDelta = profileUtil.fromMgdlToUnits(mgdlPer5Min)
        return if (profileUtil.units == GlucoseUnit.MMOL) "%.1f".format(displayDelta) else "%.0f".format(displayDelta)
    }

    companion object {

        /** A gap of this share of the sensor value is already large, whatever the glucose level. */
        const val GAP_WARN_FRACTION = 0.25

        /** A gap of this many mg/dL is large even when the sensor value is high. */
        const val GAP_WARN_MGDL = 50.0

        /**
         * True when the fingerstick value is far away from the sensor value. Both inputs are in
         * mg/dL. Either rule is enough: the share rule catches low glucose, the absolute rule
         * catches high glucose.
         */
        fun isLargeGap(bloodMgdl: Double, sensorMgdl: Double): Boolean {
            val gap = abs(bloodMgdl - sensorMgdl)
            return gap >= GAP_WARN_MGDL || gap >= sensorMgdl * GAP_WARN_FRACTION
        }
    }
}
