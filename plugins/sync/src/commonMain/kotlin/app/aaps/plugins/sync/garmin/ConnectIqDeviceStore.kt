package app.aaps.plugins.sync.garmin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One watch the user allowed Garmin Connect to share with this phone.
 *
 * The Connect IQ SDK returns these from the URL Garmin Connect opens, and it does not keep them
 * across a restart. We do. [uuid] is the SDK's UUID string (uppercase, with dashes).
 */
internal data class ConnectIqDevice(
    val uuid: String,
    val modelName: String,
    val friendlyName: String,
    val partNumber: String,
    val status: ConnectIqDeviceStatus = ConnectIqDeviceStatus.UNKNOWN,
)

/**
 * Whether a push to this watch can be attempted.
 *
 * The Connect IQ header says a device whose status is connected has not had its services discovered
 * yet. Communication waits for `deviceCharacteristicsDiscovered`, which this code reports as [READY].
 * Anything else, including connected-but-not-ready, is skipped.
 */
internal enum class ConnectIqDeviceStatus {
    UNKNOWN,
    INVALID,
    BLUETOOTH_NOT_READY,
    NOT_FOUND,
    NOT_CONNECTED,
    CONNECTED,
    READY,
    ;

    val canPush: Boolean
        get() = this == READY

    companion object {
        fun fromWire(status: String): ConnectIqDeviceStatus = when (status) {
            "INVALID" -> INVALID
            "BLUETOOTH_NOT_READY" -> BLUETOOTH_NOT_READY
            "NOT_FOUND" -> NOT_FOUND
            "NOT_CONNECTED" -> NOT_CONNECTED
            "CONNECTED" -> CONNECTED
            "READY" -> READY
            else -> UNKNOWN
        }
    }
}

/** True only on the transition into [ConnectIqDeviceStatus.READY], which is when a push may start. */
internal fun shouldPushOnStatus(previous: ConnectIqDeviceStatus, next: ConnectIqDeviceStatus): Boolean =
    !previous.canPush && next.canPush

/**
 * The device list as stored text. A broken value becomes an empty list: a bad save must not stop
 * the plugin from starting, the user can pick the watches again.
 */
internal object ConnectIqDeviceStore {

    fun encode(devices: List<ConnectIqDevice>): String = buildJsonArray {
        normalize(devices).forEach { device ->
            add(buildJsonObject {
                put("uuid", device.uuid)
                put("modelName", device.modelName)
                put("friendlyName", device.friendlyName)
                put("partNumber", device.partNumber)
            })
        }
    }.toString()

    fun decode(raw: String?): List<ConnectIqDevice> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val parsed = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val uuid = text(obj, "uuid") ?: return@mapNotNull null
            ConnectIqDevice(
                uuid = uuid,
                modelName = text(obj, "modelName").orEmpty(),
                friendlyName = text(obj, "friendlyName").orEmpty(),
                partNumber = text(obj, "partNumber").orEmpty(),
            )
        }
        return normalize(parsed)
    }

    /** Uppercases the UUID, drops a blank one, and keeps the last copy of a repeated UUID. */
    fun normalize(devices: List<ConnectIqDevice>): List<ConnectIqDevice> {
        val byId = LinkedHashMap<String, ConnectIqDevice>()
        for (device in devices) {
            val uuid = device.uuid.trim().uppercase()
            if (uuid.isEmpty()) continue
            byId[uuid] = device.copy(uuid = uuid)
        }
        return byId.values.toList()
    }

    private fun text(obj: JsonObject, name: String): String? {
        val primitive = obj[name] as? JsonPrimitive ?: return null
        return primitive.jsonPrimitive.contentOrNull
    }
}
