package app.aaps.plugins.source.keys

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.plugins.source.R

enum class DexcomOnePlusIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val unitType: UnitType = UnitType.NONE,
) : IntPreferenceKey {

    /**
     * Hours after the pre-soak start before [DexcomOnePlusBooleanKey.AutoPromote] switches the sensor.
     *
     * 12 by default, the same settling time the promote button asks for. Below 12 the switch uses
     * the "not settled" path of the button, with its own checks on recent readings.
     */
    AutoPromoteHours(
        key = "dexcom_oneplus_staging_auto_promote_hours",
        defaultValue = 12,
        min = 6,
        max = 24,
        titleResId = R.string.staging_auto_promote_hours_title,
        dependency = DexcomOnePlusBooleanKey.AutoPromote,
        unitType = UnitType.HOURS,
    ),
}
