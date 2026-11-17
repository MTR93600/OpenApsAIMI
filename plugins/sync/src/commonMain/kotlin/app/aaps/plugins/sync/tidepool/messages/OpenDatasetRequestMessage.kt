package app.aaps.plugins.sync.tidepool.messages

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.tidepool.TidepoolDevice
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenDatasetRequestMessage(config: Config, dateUtil: DateUtil) : BaseMessage() {

    // TidepoolUploader.startSession finds this dataset again by this id
    var deviceId: String = TidepoolDevice.NAME

    var time: String = dateUtil.toISOAsUTC(dateUtil.now())

    // With DST, like the offset of every uploaded record
    var timezoneOffset = (dateUtil.getTimeZoneOffsetMsWithDST() / T.mins(1).msecs()).toInt()

    var type = "upload"

    //public String byUser;
    var client = ClientInfo(config.APPLICATION_ID, config.VERSION_NAME)

    var computerTime: String = dateUtil.toISONoZone(dateUtil.now())

    var dataSetType = "continuous"

    var deviceManufacturers = arrayOf(TidepoolDevice.NAME)

    var deviceModel = TidepoolDevice.NAME

    var deviceTags = arrayOf("bgm", "cgm", "insulin-pump")

    var deduplicator = Deduplicator()

    var timeProcessing = "none"

    var timezone: String = TimeZone.currentSystemDefault().id

    var version = config.VERSION_NAME

    override fun toJson(): JsonObject = buildJsonObject {
        put("deviceId", deviceId)
        put("time", time)
        put("timezoneOffset", timezoneOffset)
        put("type", type)
        put("client", buildJsonObject {
            put("name", client.name)
            put("version", client.version)
        })
        put("computerTime", computerTime)
        put("dataSetType", dataSetType)
        put("deviceManufacturers", JsonArray(deviceManufacturers.map { JsonPrimitive(it) }))
        put("deviceModel", deviceModel)
        put("deviceTags", JsonArray(deviceTags.map { JsonPrimitive(it) }))
        put("deduplicator", buildJsonObject { put("name", deduplicator.name) })
        put("timeProcessing", timeProcessing)
        put("timezone", timezone)
        put("version", version)
    }

    inner class ClientInfo(
        val name: String,
        val version: String
    )

    inner class Deduplicator {

        val name = "org.tidepool.deduplicator.dataset.delete.origin"
    }

}
