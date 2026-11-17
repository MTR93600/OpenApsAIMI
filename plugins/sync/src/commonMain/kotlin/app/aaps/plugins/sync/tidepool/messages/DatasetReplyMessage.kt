package app.aaps.plugins.sync.tidepool.messages

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A dataset as Tidepool returns it. Only the upload id is used: every upload and delete needs it.
 *
 * It can come in two shapes: inside `data` (answer of "open dataset") or in the root of the reply
 * (answer of "list datasets").
 */
class DatasetReplyMessage {

    internal var data: Data? = null

    internal var id: String? = null
    internal var uploadId: String? = null

    class Data {

        internal var id: String? = null
        internal var uploadId: String? = null
    }

    fun getUploadId(): String? {
        return data?.uploadId ?: uploadId
    }

    companion object {

        /**
         * Reads a dataset from [json], or null when it is not an object.
         *
         * A reader over the tree on purpose, not a strict decoder: a field Tidepool adds or sends with
         * another type must not make the reply unreadable. Gson, used before, read it the same lenient way.
         */
        fun from(json: JsonElement): DatasetReplyMessage? {
            val root = json as? JsonObject ?: return null
            return DatasetReplyMessage().apply {
                id = root.string("id")
                uploadId = root.string("uploadId")
                data = (root["data"] as? JsonObject)?.let { inner ->
                    Data().apply {
                        id = inner.string("id")
                        uploadId = inner.string("uploadId")
                    }
                }
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
