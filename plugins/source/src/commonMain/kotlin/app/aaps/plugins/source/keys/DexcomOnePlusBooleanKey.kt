package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.SourceStrings

enum class DexcomOnePlusBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
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
     * Routes the production slot to the Real skeleton
     * (still fails closed at GATT/auth). For A3 spike only — not a production BLE claim.
     */
    UseRealSkeleton(
        key = "dexcom_oneplus_use_real_skeleton",
        defaultValue = true,
        title = SourceStrings.dexcom_oneplus_use_real_skeleton,
        summary = SourceStrings.dexcom_oneplus_use_real_skeleton_summary,
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
        title = SourceStrings.dexcom_oneplus_send_calibration_to_sensor,
        summary = SourceStrings.dexcom_oneplus_send_calibration_to_sensor_summary,
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
        title = SourceStrings.dexcom_oneplus_repair_missing_sensor_change,
        summary = SourceStrings.dexcom_oneplus_repair_missing_sensor_change_summary,
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
        title = SourceStrings.dexcom_oneplus_correct_sensor_start,
        summary = SourceStrings.dexcom_oneplus_correct_sensor_start_summary,
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
        title = SourceStrings.dexcom_oneplus_anchor_session_to_manual_sensor_change,
        summary = SourceStrings.dexcom_oneplus_anchor_session_to_manual_sensor_change_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Switches the pre-soak sensor to production by itself, [DexcomOnePlusIntKey.AutoPromoteHours]
     * after the pre-soak start, when its readings are recent and real. The promote button still works.
     *
     * Off by default: the switch stops the sensor in use, and the rest of its life is lost.
     *
     * Provenance: ref `DexcomOnePlusBooleanKey.AutoPromote` at `a88a0e588c`.
     */
    AutoPromote(
        key = "dexcom_oneplus_staging_auto_promote",
        defaultValue = false,
        title = SourceStrings.staging_auto_promote_title,
        summary = SourceStrings.staging_auto_promote_summary,
    ),
}
