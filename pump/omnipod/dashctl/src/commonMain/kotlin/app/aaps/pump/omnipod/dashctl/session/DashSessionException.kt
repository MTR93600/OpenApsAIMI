package app.aaps.pump.omnipod.dashctl.session

/** Transport or protocol failure during pairing or session establishment. */
class DashSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)
