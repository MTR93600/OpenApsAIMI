package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.SourceStrings

/**
 * Eversense 365 cloud (DMS) sign in. The user types both values; nothing is built in.
 *
 * Android only, like the whole Eversense driver.
 */
enum class EversenseStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false
) : StringPreferenceKey {

    EversenseUsername(
        key = "eversense_credentials_username",
        defaultValue = "",
        title = SourceStrings.eversense_credentials_username,
        summary = SourceStrings.eversense_credentials_not_set,
        exportable = true
    ),
    EversensePassword(
        key = "eversense_credentials_password",
        defaultValue = "",
        title = SourceStrings.eversense_credentials_password,
        summary = SourceStrings.eversense_credentials_not_set,
        isPassword = true,
        exportable = true
    )
}
