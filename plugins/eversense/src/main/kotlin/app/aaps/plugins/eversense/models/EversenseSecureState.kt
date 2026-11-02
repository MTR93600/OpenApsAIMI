package app.aaps.plugins.eversense.models

import kotlinx.serialization.Serializable

@Serializable
class EversenseSecureState {
    var canUseShortcut: Boolean = false
    var username: String = ""
    var password: String = ""
    var clientId: String = ""
    var privateKey: String = ""
    var publicKey: String = ""

    // 365 only. Picks the EU / OUS DMS hosts in EversenseHttp365Util instead of the US ones.
    // Defaults to false so every state saved by an existing (US) user still reads back unchanged.
    // E3 does not need this: its OUS support is unconditional, see EversenseHttpE3Util.
    var isEuropeanRegion: Boolean = false
}
