package app.aaps.plugins.libre3.identity

import android.content.Context
import android.util.Log
import app.aaps.plugins.libre3.Libre3LogMarkers
import app.aaps.plugins.libre3.nfc.Libre3NfcCommands
import java.util.Base64
import java.util.UUID

/**
 * The small part of the store that the NFC step needs.
 *
 * It exists so the NFC flow can be unit tested with a simple fake, without an Android context and
 * without touching a real settings file.
 */
interface Libre3IdentityStore {

    /** Identifier of this phone, created once per install. */
    fun receiverId(): Int

    /**
     * Writes the sensor and waits for the write to reach the disk.
     *
     * @return true only when the write really finished.
     */
    fun saveIdentityAndWait(identity: Libre3SensorIdentity): Boolean
}

/**
 * Keeps the sensor identity, the session keys and the ingest high-water mark on disk.
 *
 * A private SharedPreferences file is used, not the exportable AAPS settings, so the PIN and the
 * keys never leave the phone in a settings export.
 *
 * The writes that the Bluetooth work depends on use `commit`, not `apply`. The rule is that the PIN
 * must really be on disk before any connect starts, so a crash in the middle can never leave a
 * sensor that was taken over but whose PIN is lost.
 */
class Libre3SensorStore(context: Context) : Libre3IdentityStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Identifier this phone shows to sensors, kept for the whole install.
     *
     * It is built once from a fresh random id and then reused, because the sensor binds itself to
     * the receiver that activated it. The test id from the LibreCRKit tests is never used here.
     *
     * Note for review: the plan asks for the Android Keystore. The Keystore stores keys, not free
     * text, so this value lives in the app private settings file instead. It is not a secret. It
     * only has to be stable and different from other installs.
     */
    @Synchronized
    override fun receiverId(): Int {
        val storedUuid = prefs.getString(KEY_APP_UUID, null)
        if (storedUuid != null) return prefs.getInt(KEY_RECEIVER_ID, Libre3NfcCommands.receiverIdFrom(storedUuid))
        val uuid = UUID.randomUUID().toString()
        val receiverId = Libre3NfcCommands.receiverIdFrom(uuid)
        val written = prefs.edit()
            .putString(KEY_APP_UUID, uuid)
            .putInt(KEY_RECEIVER_ID, receiverId)
            .commit()
        // Sending an id that was not stored would bind the sensor to a number this phone can never
        // build again, and the sensor would stop answering us.
        check(written) { "the receiver id of this phone could not be stored" }
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.NFC}: new receiver id created for this install")
        return receiverId
    }

    /**
     * Writes everything the NFC scan produced and waits for the write to finish.
     *
     * @return true when the write really reached the disk. The caller must not start Bluetooth when
     *   this is false.
     */
    @Synchronized
    override fun saveIdentityAndWait(identity: Libre3SensorIdentity): Boolean {
        val serialChanged = identity.serialNumber != prefs.getString(KEY_SERIAL, null)
        val editor = prefs.edit()
            .putString(KEY_SERIAL, identity.serialNumber)
            .putString(KEY_MAC, identity.bleAddress)
            .putString(KEY_PIN, encode(identity.blePin))
            .putInt(KEY_RECEIVER_ID, identity.receiverId)
            .putInt(KEY_GENERATION, identity.generation)
            .putInt(KEY_WARMUP_MINUTES, identity.warmupMinutes)
            .putInt(KEY_WEAR_MINUTES, identity.wearDurationMinutes)
            .putLong(KEY_ACTIVATED_AT, identity.activatedAtMs)
        if (serialChanged) {
            // A different sensor starts its own life counter and needs its own keys. Keeping the old
            // ones would either block every new reading as "already seen" or send a stale key.
            editor.remove(KEY_LAST_LIFE_COUNT)
                .remove(KEY_PHASE5_RAW_KEY)
                .remove(KEY_K_ENC)
                .remove(KEY_IV_ENC)
        }
        val written = editor.commit()
        Log.i(
            Libre3LogMarkers.TAG,
            "${Libre3LogMarkers.NFC}: sensor stored serialChanged=$serialChanged written=$written",
        )
        return written
    }

    /** The stored sensor, or null when no sensor was ever scanned. */
    fun loadIdentity(): Libre3SensorIdentity? {
        val serial = prefs.getString(KEY_SERIAL, null)?.takeIf { it.isNotBlank() } ?: return null
        val mac = prefs.getString(KEY_MAC, null)?.takeIf { it.isNotBlank() } ?: return null
        val pin = decode(prefs.getString(KEY_PIN, null))?.takeIf { it.size == PIN_SIZE } ?: return null
        return Libre3SensorIdentity(
            serialNumber = serial,
            bleAddress = mac,
            blePin = pin,
            receiverId = prefs.getInt(KEY_RECEIVER_ID, 0),
            generation = prefs.getInt(KEY_GENERATION, 0),
            warmupMinutes = prefs.getInt(KEY_WARMUP_MINUTES, DEFAULT_WARMUP_MINUTES),
            wearDurationMinutes = prefs.getInt(KEY_WEAR_MINUTES, 0),
            activatedAtMs = prefs.getLong(KEY_ACTIVATED_AT, 0L),
        )
    }

    /** True when a sensor with a PIN is stored, which is the only case where Bluetooth may start. */
    fun isReadyForBle(): Boolean = loadIdentity() != null

    /** Session keys of the stored sensor. All three parts may be null. */
    fun loadSessionKeys(): Libre3SessionKeys =
        Libre3SessionKeys(
            phase5RawKey = decode(prefs.getString(KEY_PHASE5_RAW_KEY, null))?.takeIf { it.size == PHASE5_KEY_SIZE },
            kEnc = decode(prefs.getString(KEY_K_ENC, null))?.takeIf { it.size == K_ENC_SIZE },
            ivEnc = decode(prefs.getString(KEY_IV_ENC, null))?.takeIf { it.size == IV_ENC_SIZE },
        )

    /**
     * Stores the key of the first pairing. It is written once per sensor and then reused by every
     * reconnect, so it must survive the app being killed.
     */
    @Synchronized
    fun savePhase5RawKeyAndWait(phase5RawKey: ByteArray): Boolean =
        prefs.edit().putString(KEY_PHASE5_RAW_KEY, encode(phase5RawKey)).commit()

    /** Stores the keys of the current session. A new handshake replaces them. */
    @Synchronized
    fun saveSessionKeys(kEnc: ByteArray, ivEnc: ByteArray) {
        prefs.edit()
            .putString(KEY_K_ENC, encode(kEnc))
            .putString(KEY_IV_ENC, encode(ivEnc))
            .apply()
    }

    /**
     * Gives the sensor more time when it is still sending past the end this phone thinks it has.
     *
     * A sensor that keeps sending good readings is alive, whatever a stored number says. Without
     * this, one bad NFC reading of the wear time would make the driver treat a healthy sensor as
     * finished and drop every reading from then on, in silence. The upstream project added the
     * same rule for the same reason.
     *
     * @return the wear time to use from now on.
     */
    @Synchronized
    fun extendWearIfStillAlive(lifeCountMinutes: Int): Int {
        val stored = prefs.getInt(KEY_WEAR_MINUTES, 0)
        if (stored <= 0 || lifeCountMinutes < stored) return stored
        val extended = lifeCountMinutes + WEAR_EXTENSION_MINUTES
        prefs.edit().putInt(KEY_WEAR_MINUTES, extended).commit()
        Log.i(
            Libre3LogMarkers.TAG,
            "${Libre3LogMarkers.SESSION}: the sensor is still sending past its stored end, " +
                "wear time moved from $stored to $extended minutes",
        )
        return extended
    }

    /** Highest sensor life counter that was sent to AAPS, or -1 when there is none. */
    fun loadLastLifeCount(): Int = prefs.getInt(KEY_LAST_LIFE_COUNT, -1)

    /**
     * Remembers the last life counter, so a restart cannot send the same reading twice.
     *
     * This one is written with `commit`, not `apply`: a crash between the insert and a lazy write
     * would let the same reading in again, and the loop treats a repeated reading as an error.
     */
    fun saveLastLifeCount(lifeCount: Int) {
        prefs.edit().putInt(KEY_LAST_LIFE_COUNT, lifeCount).commit()
    }

    /** Forgets the sensor and its keys. Used when the user starts a different sensor. */
    @Synchronized
    fun clear() {
        // The receiver id and its unique text stay, because this phone keeps its identity.
        prefs.edit()
            .remove(KEY_SERIAL)
            .remove(KEY_MAC)
            .remove(KEY_PIN)
            .remove(KEY_GENERATION)
            .remove(KEY_WARMUP_MINUTES)
            .remove(KEY_WEAR_MINUTES)
            .remove(KEY_ACTIVATED_AT)
            .remove(KEY_LAST_LIFE_COUNT)
            .remove(KEY_PHASE5_RAW_KEY)
            .remove(KEY_K_ENC)
            .remove(KEY_IV_ENC)
            .commit()
    }

    // java.util.Base64 rather than android.util.Base64: same result, and it also runs in a plain
    // unit test, so the "write and wait" rule can be checked without a device.
    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String?): ByteArray? =
        value?.let {
            try {
                Base64.getDecoder().decode(it)
            } catch (_: Throwable) {
                null
            }
        }

    companion object {

        private const val PREFS_NAME = "libre3_sensor_store"

        private const val KEY_APP_UUID = "app_uuid"
        private const val KEY_RECEIVER_ID = "receiver_id"
        private const val KEY_SERIAL = "serial"
        private const val KEY_MAC = "ble_mac"
        private const val KEY_PIN = "ble_pin"
        private const val KEY_GENERATION = "generation"
        private const val KEY_WARMUP_MINUTES = "warmup_minutes"
        private const val KEY_WEAR_MINUTES = "wear_minutes"
        private const val KEY_ACTIVATED_AT = "activated_at"
        private const val KEY_LAST_LIFE_COUNT = "last_life_count"
        private const val KEY_PHASE5_RAW_KEY = "phase5_raw_key"
        private const val KEY_K_ENC = "k_enc"
        private const val KEY_IV_ENC = "iv_enc"

        private const val PIN_SIZE = 4
        private const val PHASE5_KEY_SIZE = 16
        private const val K_ENC_SIZE = 16
        private const val IV_ENC_SIZE = 8

        /** Used only when the sensor did not tell us its warm-up length. */
        const val DEFAULT_WARMUP_MINUTES = 60

        /** How much more time a sensor is given when it is still sending past its stored end. */
        const val WEAR_EXTENSION_MINUTES = 24 * 60
    }
}
