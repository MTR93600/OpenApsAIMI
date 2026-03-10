package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class AimiStringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false,
    override val dependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val negativeDependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    PregnancyDueDateString("aimi_pregnancy_due_date_string", ""),
    RemoteControlPin("aimi_remote_control_pin", "", isPin = true, isPassword = true),
}
