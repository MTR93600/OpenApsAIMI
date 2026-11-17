package app.aaps.plugins.sync.tidepool.comm

import app.aaps.plugins.sync.tidepool.messages.DatasetReplyMessage
import app.aaps.plugins.sync.tidepool.messages.OpenDatasetRequestMessage

/** The header that carries the access token. Never write it to a log: it gives full access to the data. */
const val SESSION_TOKEN_HEADER: String = "x-tidepool-session-token"

/**
 * One answer from Tidepool.
 *
 * @property code the HTTP status code
 * @property message the HTTP status text, for the log
 * @property body the parsed answer, or null on an error or when there is nothing to read
 */
class TidepoolResponse<T>(val code: Int, val message: String, val body: T?) {

    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The Tidepool calls AAPS makes. A call that cannot reach the server throws; a call the server
 * refuses returns a [TidepoolResponse] that is not successful.
 *
 * There is no call to close a dataset on purpose: a continuous dataset must stay open, and a closed one
 * cannot be written to again.
 */
interface TidepoolApi {

    /**
     * List the user's datasets, newest first. Deleted datasets are not returned.
     * A null [deviceId] is left out of the query, so datasets with any device id (or none) match.
     */
    suspend fun getDataSets(token: String, userId: String, clientName: String, deviceId: String?, size: Int): TidepoolResponse<List<DatasetReplyMessage>>

    suspend fun openDataSet(token: String, userId: String, request: OpenDatasetRequestMessage): TidepoolResponse<DatasetReplyMessage>

    /** Send [records], a JSON array, to the dataset [uploadId]. */
    suspend fun doUpload(token: String, uploadId: String, records: String): TidepoolResponse<Unit>

    /** Tidepool answers 200 with an empty body, and only after all records of the dataset are gone. */
    suspend fun deleteDataSet(token: String, uploadId: String): TidepoolResponse<Unit>
}
