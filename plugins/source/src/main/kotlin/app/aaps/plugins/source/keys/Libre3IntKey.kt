package app.aaps.plugins.source.keys

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.plugins.source.R

enum class Libre3IntKey(
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

    /** Hours after the NFC activation before [Libre3BooleanKey.AutoPromote] switches the sensor. */
    AutoPromoteHours(
        key = "libre3_staging_auto_promote_hours",
        defaultValue = 10,
        min = 6,
        max = 24,
        titleResId = R.string.staging_auto_promote_hours_title,
        dependency = Libre3BooleanKey.AutoPromote,
        unitType = UnitType.HOURS,
    ),
}
