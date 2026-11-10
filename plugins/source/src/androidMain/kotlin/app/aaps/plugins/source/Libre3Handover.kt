package app.aaps.plugins.source

import app.aaps.core.interfaces.maintenance.HandoverFields
import app.aaps.plugins.libre3.identity.Libre3SensorIdentity
import app.aaps.plugins.libre3.identity.Libre3SessionKeys
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Libre 3 sensor a "Transfer to another phone" export carries. Android to Android only: the
 * native Libre 3 driver does not exist on iPhone.
 *
 * The sensor was activated over NFC for one receiver id and answers only that one, so the id goes
 * along with the address, the Bluetooth pin and the session keys. With all of them the next phone
 * connects to the running sensor, with no new activation.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object Libre3Handover {

    const val ID = "libre3"

    private const val SERIAL = "serial"
    private const val ADDRESS = "address"
    private const val PIN = "blePin"
    private const val RECEIVER_ID = "receiverId"
    private const val GENERATION = "generation"
    private const val WARMUP = "warmupMinutes"
    private const val WEAR = "wearDurationMinutes"
    private const val ACTIVATED_AT = "activatedAtMs"
    private const val PHASE5_KEY = "phase5RawKey"
    private const val K_ENC = "kEnc"
    private const val IV_ENC = "ivEnc"

    fun export(identity: Libre3SensorIdentity, keys: Libre3SessionKeys): HandoverFields =
        buildMap {
            put(SERIAL, identity.serialNumber)
            put(ADDRESS, identity.bleAddress)
            put(PIN, Base64.encode(identity.blePin))
            put(RECEIVER_ID, identity.receiverId.toString())
            put(GENERATION, identity.generation.toString())
            put(WARMUP, identity.warmupMinutes.toString())
            put(WEAR, identity.wearDurationMinutes.toString())
            put(ACTIVATED_AT, identity.activatedAtMs.toString())
            keys.phase5RawKey?.let { put(PHASE5_KEY, Base64.encode(it)) }
            keys.kEnc?.let { put(K_ENC, Base64.encode(it)) }
            keys.ivEnc?.let { put(IV_ENC, Base64.encode(it)) }
        }

    /** The sensor and its keys in [fields], or null when a field the sensor needs is missing. */
    fun parse(fields: HandoverFields): Pair<Libre3SensorIdentity, Libre3SessionKeys>? = runCatching {
        val identity = Libre3SensorIdentity(
            serialNumber = fields.getValue(SERIAL).takeIf { it.isNotBlank() } ?: return null,
            bleAddress = fields.getValue(ADDRESS).takeIf { it.isNotBlank() } ?: return null,
            blePin = Base64.decode(fields.getValue(PIN)),
            receiverId = fields.getValue(RECEIVER_ID).toInt(),
            generation = fields.getValue(GENERATION).toInt(),
            warmupMinutes = fields.getValue(WARMUP).toInt(),
            wearDurationMinutes = fields.getValue(WEAR).toInt(),
            activatedAtMs = fields.getValue(ACTIVATED_AT).toLong(),
        )
        val keys = Libre3SessionKeys(
            phase5RawKey = fields[PHASE5_KEY]?.let { Base64.decode(it) },
            kEnc = fields[K_ENC]?.let { Base64.decode(it) },
            ivEnc = fields[IV_ENC]?.let { Base64.decode(it) },
        )
        identity to keys
    }.getOrNull()
}
