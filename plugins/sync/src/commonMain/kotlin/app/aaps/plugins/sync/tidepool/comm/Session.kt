package app.aaps.plugins.sync.tidepool.comm

import app.aaps.plugins.sync.tidepool.messages.AuthReplyMessage
import app.aaps.plugins.sync.tidepool.messages.DatasetReplyMessage
import kotlin.concurrent.Volatile

/**
 * One connection to Tidepool: who is logged in, the access token and the dataset the uploads go to.
 *
 * @param service the Tidepool API, or null in a test that must not make a call
 */
class Session(val service: TidepoolApi?) {

    internal var token: String? = null
    internal var authReply: AuthReplyMessage? = null
    internal var datasetReply: DatasetReplyMessage? = null
    internal var start: Long = 0
    internal var end: Long = 0

    @Volatile
    internal var iterations: Int = 0
}
