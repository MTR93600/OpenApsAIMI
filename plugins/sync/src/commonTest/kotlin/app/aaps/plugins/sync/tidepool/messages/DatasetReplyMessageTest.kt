package app.aaps.plugins.sync.tidepool.messages

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests reading the dataset reply of Tidepool. The upload id can come in two shapes: inside `data`
 * (answer of "open dataset") or in the root of the reply (answer of "list datasets"), and the upload
 * calls need it either way.
 */
class DatasetReplyMessageTest {

    private fun read(json: String) = DatasetReplyMessage.from(Json.parseToJsonElement(json))

    @Test
    fun upload_id_inside_data_is_used() {
        assertEquals("upload-1", read("""{"data":{"uploadId":"upload-1","id":"data-1","type":"upload"}}""")?.getUploadId())
    }

    @Test
    fun upload_id_in_the_root_is_used_when_there_is_no_data() {
        assertEquals("upload-2", read("""{"id":"dataset-1","uploadId":"upload-2"}""")?.getUploadId())
    }

    @Test
    fun data_wins_over_the_root() {
        assertEquals("upload-data", read("""{"uploadId":"upload-root","data":{"uploadId":"upload-data"}}""")?.getUploadId())
    }

    @Test
    fun reply_without_any_upload_id_gives_null() {
        assertNull(read("""{"id":"dataset-1"}""")?.getUploadId())
    }

    @Test
    fun unknown_fields_and_other_types_do_not_break_the_reply() {
        val reply = read("""{"uploadId":"upload-3","timezoneOffset":"not a number","client":{"name":1},"deviceTags":null}""")
        assertEquals("upload-3", reply?.getUploadId())
    }

    @Test
    fun a_reply_that_is_not_an_object_is_not_a_dataset() {
        assertNull(read("""["upload-1"]"""))
    }
}
