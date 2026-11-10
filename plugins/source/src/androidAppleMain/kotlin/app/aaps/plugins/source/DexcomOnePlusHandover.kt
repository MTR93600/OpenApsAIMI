package app.aaps.plugins.source

import app.aaps.core.interfaces.maintenance.HandoverFields
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.parse.OnePlusExtendedVersionRx
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The ONE+ / G7 sensor a "Transfer to another phone" export carries, the same on Android and iPhone.
 *
 * The pairing code is what lets the next phone authenticate, so the sensor goes on with its session:
 * no new sensor, no new warm-up. The session start and length keep the sensor age right.
 *
 * The address is only kept from Android to Android. An Android phone names the sensor by its
 * Bluetooth address, which is the same on every Android phone. An iPhone names it by an identifier
 * that iOS makes up for that one phone, so an address from or for an iPhone means nothing on the
 * other phone. There the sensor is found again by the name it advertises
 * (`DexcomOnePlusPlugin.findTransferredSensor`).
 */
@OptIn(ExperimentalEncodingApi::class)
internal object DexcomOnePlusHandover {

    const val ID = "dexcom_oneplus"

    private const val PIN = "pin"
    private const val SERIAL = "serial"
    private const val GTIN = "gtin"
    private const val RAW_GS1 = "rawGs1"
    private const val ADDRESS = "address"
    private const val DEVICE_NAME = "deviceName"
    private const val SHARED_KEY = "sharedKey"
    private const val SESSION_START = "sessionStartMs"
    private const val SESSION_LENGTH = "sessionLengthS"
    private const val WARMUP = "warmupS"
    private const val PLATFORM = "platform"

    /** What [parse] gives back: the session to adopt, its start and what the sensor said about itself. */
    class Taken(val session: OnePlusStoredSession, val sessionStartMs: Long, val sensorInfo: OnePlusExtendedVersionRx?)

    fun export(session: OnePlusStoredSession, sessionStartMs: Long, sensorInfo: OnePlusExtendedVersionRx?, platform: AppPlatform): HandoverFields =
        buildMap {
            put(PIN, session.identity.pin)
            put(PLATFORM, platform.name)
            session.identity.serial?.let { put(SERIAL, it) }
            session.identity.gtin?.let { put(GTIN, it) }
            session.identity.rawGs1?.let { put(RAW_GS1, it) }
            session.lastMac?.let { put(ADDRESS, it) }
            session.lastDeviceName?.let { put(DEVICE_NAME, it) }
            session.sharedKey?.let { put(SHARED_KEY, Base64.encode(it)) }
            if (sessionStartMs > 0L) put(SESSION_START, sessionStartMs.toString())
            sensorInfo?.let {
                put(SESSION_LENGTH, it.sessionLengthSeconds.toString())
                put(WARMUP, it.warmupSeconds.toString())
            }
        }

    /** The sensor in [fields] for a phone running on [here], or null when the pairing code is missing. */
    fun parse(fields: HandoverFields, here: AppPlatform): Taken? {
        val pin = fields[PIN]?.takeIf { it.length == 4 } ?: return null
        val from = fields[PLATFORM]
        val addressValid = from == AppPlatform.Android.name && here == AppPlatform.Android
        val sharedKey = fields[SHARED_KEY]?.let { runCatching { Base64.decode(it) }.getOrNull() }?.takeIf { it.size == 16 }
        val session = OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = pin, serial = fields[SERIAL], gtin = fields[GTIN], rawGs1 = fields[RAW_GS1]),
            lastMac = fields[ADDRESS]?.takeIf { addressValid && it.isNotBlank() },
            lastDeviceName = fields[DEVICE_NAME]?.takeIf { it.isNotBlank() },
            sharedKey = sharedKey,
        )
        val length = fields[SESSION_LENGTH]?.toLongOrNull()
        val warmup = fields[WARMUP]?.toIntOrNull()
        val info = if (length != null && warmup != null) OnePlusExtendedVersionRx(sessionLengthSeconds = length, warmupSeconds = warmup) else null
        return Taken(session, fields[SESSION_START]?.toLongOrNull() ?: 0L, info)
    }
}
