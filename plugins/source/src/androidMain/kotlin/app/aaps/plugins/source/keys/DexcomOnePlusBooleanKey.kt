package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.R

enum class DexcomOnePlusBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = true,
    override val exportable: Boolean = false,
) : BooleanPreferenceKey {

    /**
     * Routes [app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers.default] to the Real skeleton
     * (still fails closed at GATT/auth). For A3 spike only — not a production BLE claim.
     */
    UseRealSkeleton(
        key = "dexcom_oneplus_use_real_skeleton",
        defaultValue = true,
        titleResId = R.string.dexcom_oneplus_use_real_skeleton,
        summaryResId = R.string.dexcom_oneplus_use_real_skeleton_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Send a fingerstick to the sensor instead of correcting its readings inside the phone.
     *
     * Off by default, and engineering only. A sensor keeps a calibration it accepts for good — it
     * cannot be edited or deleted — and a Dexcom ONE+ does not answer in a way anyone has decoded,
     * so the app cannot tell the user whether the value was taken. Owner decision 7 is unsettled:
     * the code is ported and the switch stays off.
     *
     * Provenance: ref `DexcomOnePlusBooleanKey.SendCalibrationToSensor` at `3dd0ca64772`.
     */
    SendCalibrationToSensor(
        key = "dexcom_oneplus_send_calibration_to_sensor",
        defaultValue = false,
        titleResId = R.string.dexcom_oneplus_send_calibration_to_sensor,
        summaryResId = R.string.dexcom_oneplus_send_calibration_to_sensor_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Put a missing SENSOR_CHANGE back for the running ONE+ session.
     *
     * Parity with dev_OAPSAIMI: the reference writes the missing event whenever
     * "create sensor change" is on, so this defaults to true. When the repair is
     * on but "create sensor change" is off, the refresh still writes nothing.
     */
    RepairMissingSensorChange(
        key = "dexcom_oneplus_repair_missing_sensor_change",
        defaultValue = true,
        titleResId = R.string.dexcom_oneplus_repair_missing_sensor_change,
        summaryResId = R.string.dexcom_oneplus_repair_missing_sensor_change_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Let the Status screen move the insertion time of the sensor that feeds the loop.
     *
     * Parity with dev_OAPSAIMI: the reference exposes the correction without a gate,
     * so this defaults to true. Invalidating SENSOR_CHANGE events rewrites the sensor
     * start; calibration rows are never removed.
     */
    CorrectSensorStart(
        key = "dexcom_oneplus_correct_sensor_start",
        defaultValue = true,
        titleResId = R.string.dexcom_oneplus_correct_sensor_start,
        summaryResId = R.string.dexcom_oneplus_correct_sensor_start_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Replace the automatic session start with a SENSOR_CHANGE the user already logged.
     *
     * The reference does this on every start and on the first accepted reading. That rolls
     * the stored start back by up to 24 hours. The owner asked for the same code behind a
     * switch that stays off, so a start keeps the time it had before this lot.
     */
    AnchorSessionToManualSensorChange(
        key = "dexcom_oneplus_anchor_session_to_manual_sensor_change",
        defaultValue = false,
        titleResId = R.string.dexcom_oneplus_anchor_session_to_manual_sensor_change,
        summaryResId = R.string.dexcom_oneplus_anchor_session_to_manual_sensor_change_summary,
        engineeringModeOnly = true,
        exportable = false,
    )
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
