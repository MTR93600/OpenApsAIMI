package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.plugins.source.R

enum class Libre3BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
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
    override val hideParentScreenIfHidden: Boolean = true,
    override val exportable: Boolean = false,
) : BooleanPreferenceKey {

    /**
     * Routes `app.aaps.plugins.libre3.Libre3CgmDrivers.default` to the real driver instead of the
     * stub. Default off on purpose: the real driver must stay unreachable until the NFC step and
     * the crypto vectors are proven, and until the user has confirmed it on a real sensor.
     */
    UseRealSkeleton(
        key = "libre3_use_real_skeleton",
        defaultValue = false,
        titleResId = R.string.libre3_use_real_skeleton,
        summaryResId = R.string.libre3_use_real_skeleton_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Turns the Libre 3 pre-soak on: a second sensor may be started in the pre-soak slot while the
     * current one keeps feeding the loop.
     *
     * Off by default. With it off there is no second driver instance, no second preferences file,
     * no slot choice on the start screen, and the plugin behaves exactly as it did before. A
     * pre-soak spends real sensor wear time, because a Libre 3 starts its 14 day clock at the NFC
     * activation; see `docs/LIBRE3_PRESOAK_PLAN.md`.
     */
    PresoakEnabled(
        key = "libre3_presoak_enabled",
        defaultValue = false,
        titleResId = R.string.libre3_presoak_enabled,
        summaryResId = R.string.libre3_presoak_enabled_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),

    /**
     * Runs a foreground service for as long as a Libre 3 session is wanted.
     *
     * Kept apart from [PresoakEnabled] on purpose: it also helps the ordinary single sensor case,
     * where an aggressive phone tears the Bluetooth link down in standby. A pre-soak wants it on,
     * because two links have twice as much to lose, but the pre-soak must never switch it on by
     * itself.
     */
    KeepSessionAlive(
        key = "libre3_keep_session_alive",
        defaultValue = false,
        titleResId = R.string.libre3_keep_session_alive,
        summaryResId = R.string.libre3_keep_session_alive_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),
}
