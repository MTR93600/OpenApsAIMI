package app.aaps.plugins.sync.tidepool.messages

/** Who is logged in. Filled from the saved subscription id after the OpenID login, not from a reply. */
class AuthReplyMessage {

    internal var userid: String? = null
}
