package app.aaps.plugins.source.keys

import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.R

enum class DexcomOnePlusIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    private val titleResId: Int,
    override val dependency: BooleanPreferenceKey? = null,
    override val unitType: UnitType = UnitType.NONE,
    override val exportable: Boolean = true,
) : IntPreferenceKey {

    /**
     * Hours after the pre-soak start before [DexcomOnePlusBooleanKey.AutoPromote] switches the sensor.
     *
     * 12 by default, the same settling time the promote button asks for. Below 12 the switch uses
     * the "not settled" path of the button, with its own checks on recent readings.
     *
     * Provenance: ref `DexcomOnePlusIntKey.AutoPromoteHours` at `a88a0e588c`.
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
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
}
