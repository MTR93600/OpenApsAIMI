package app.aaps.core.interfaces.maintenance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A pump or sensor plugin whose pairing can move to another phone, Android or iPhone.
 *
 * The pairing travels in a settings export made by "Transfer to another phone" ([DeviceHandovers]),
 * which first stops AAPS on the phone that makes it ([AapsStop]). Without that stop two phones would
 * talk to the same pump. A normal export never carries it.
 *
 * The data is plain text pairs ([HandoverFields]), so a pump driver on Android and the one on iPhone
 * read the same thing, as long as they use the same [handoverId].
 */
interface DeviceHandover {

    /** The same on every platform for the same device, for example "medtrum". */
    val handoverId: String

    /** The pairing for another phone, or null when this plugin has no device paired. */
    suspend fun exportHandover(): HandoverFields?

    /**
     * Takes over the pairing made on another phone, and connects when it can. Returns false when
     * the data does not describe a device this plugin can use; nothing is changed then.
     */
    suspend fun importHandover(fields: HandoverFields): Boolean
}

/** The pairing of one device: plain text pairs, so every platform reads it the same way. */
typealias HandoverFields = Map<String, String>

/**
 * Moves the pump and sensor pairing between phones, inside a settings export.
 *
 * Each device is one entry of the export, named [PREFIX] + its [DeviceHandover.handoverId]. An
 * older AAPS does not know these names and leaves them alone when it imports the file.
 */
interface DeviceHandovers {

    /**
     * The pairing of the active pump and BG source, ready to go into an export. Waits for a pump
     * command that still runs, so the numbers in it are the last ones. Call it only after
     * [AapsStop.stop]: the pump must not get another command from this phone afterwards.
     */
    suspend fun collect(): Map<String, String>

    /** The devices an imported file can hand over, by [DeviceHandover.handoverId]. */
    fun idsIn(values: Map<String, String>): List<String>

    /**
     * Takes over every device in [values]: selects its plugin, gives it the pairing and lets it
     * connect. Returns the ids that were taken over.
     */
    suspend fun apply(values: Map<String, String>): List<String>

    companion object {

        const val PREFIX = "aaps_device_handover."

        /** [fields] as one line of JSON, for the export. */
        fun encode(fields: HandoverFields): String =
            JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()

        /** The fields of [text], or null when it is not what [encode] writes. */
        fun decode(text: String): HandoverFields? =
            runCatching {
                Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: return@runCatching null }
            }.getOrNull()
    }
}
