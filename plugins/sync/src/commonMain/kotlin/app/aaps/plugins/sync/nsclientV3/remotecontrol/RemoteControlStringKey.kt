package app.aaps.plugins.sync.nsclientV3.remotecontrol

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * What the AIMI Remote Control keeps on the follower phone.
 *
 * Both values protect remote commands, so they are never exported: a settings file must not carry
 * them to another phone. [RemoteControlPlugin] keeps them through a settings import.
 */
enum class RemoteControlStringKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false
) : StringNonPreferenceKey {

    /** The PIN sent with every remote command. It must be the AIMI remote PIN of the master. */
    FollowerPin("aimi_remote_follower_pin", ""),

    /** Salted hash of the password that unlocks the Remote Control screen. Empty when not set. */
    AccessPasswordHash("aimi_remote_access_password_hash", "")
}
