package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.interfaces.utils.DateUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class BaseElement(timestamp: Long, uuid: String, dateUtil: DateUtil) {

    var deviceTime: String = ""

    var time: String = ""

    var timezoneOffset: Int = 0

    var type: String? = null

    var origin: Origin? = null

    // The device that recorded it. Tidepool groups data by this id; `UploadChunk` fills it for every record.
    var deviceId: String? = null

    /** The record as Tidepool expects it. A null field is left out of the JSON. */
    fun toJson(): JsonObject = buildJsonObject { putFields(this) }

    /** Adds this record's fields. A subclass adds its own after calling `super`. */
    protected open fun putFields(json: JsonObjectBuilder) {
        json.put("deviceTime", deviceTime)
        json.put("time", time)
        json.put("timezoneOffset", timezoneOffset)
        type?.let { json.put("type", it) }
        origin?.let { origin ->
            json.put("origin", buildJsonObject {
                put("id", origin.id)
                origin.name?.let { put("name", it) }
                origin.version?.let { put("version", it) }
                origin.type?.let { put("type", it) }
            })
        }
        deviceId?.let { json.put("deviceId", it) }
    }

    init {
        deviceTime = dateUtil.toISONoZone(timestamp)
        time = dateUtil.toISOAsUTC(timestamp)
        timezoneOffset = dateUtil.getTimeZoneOffsetMinutes(timestamp) // TODO
        origin = Origin(uuid)
    }

    /**
     * [id] is what the Tidepool deduplicator matches on, so it must stay stable for the same record.
     * [name], [version] and [type] say which app sent it; `UploadChunk` fills them for every record.
     */
    inner class Origin internal constructor(
        internal var id: String
    ) {

        internal var name: String? = null

        internal var version: String? = null

        internal var type: String? = null
    }
}