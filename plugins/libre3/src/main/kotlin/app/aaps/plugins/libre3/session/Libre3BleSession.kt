package app.aaps.plugins.libre3.session

import android.util.Log
import app.aaps.plugins.libre3.Libre3LogMarkers
import app.aaps.plugins.libre3.crypto.Libre3AesBlock
import app.aaps.plugins.libre3.crypto.Libre3CryptoException
import app.aaps.plugins.libre3.crypto.Libre3DataPlaneCrypto
import app.aaps.plugins.libre3.crypto.Libre3FirstPairEphemeral
import app.aaps.plugins.libre3.crypto.Libre3SessionMaterial
import app.aaps.plugins.libre3.gatt.Libre3BluetoothUuids
import app.aaps.plugins.libre3.gatt.Libre3GattClient
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.libre3.parse.Libre3GlucoseFrameAssembler
import java.security.SecureRandom

/**
 * Builds the block maker that the pairing messages use.
 *
 * The pairing plane and the glucose plane use different block makers, and mixing them is a safety
 * rule of this project. This is the pairing one. It is passed in so the session does not depend on
 * how it is built, and so it can be replaced in tests.
 */
fun interface Libre3PairingBlockFactory {

    /** @param phase5RawKey the stored pairing key of this sensor. */
    fun blockFor(phase5RawKey: ByteArray): Libre3AesBlock
}

/**
 * One whole Bluetooth session, from connect to a running glucose stream.
 *
 * The order of the steps is a safety order, not a style choice:
 *
 * 1. Ask the start policy what may be done at all. Nothing happens without a stored PIN.
 * 2. Connect and turn on the three pairing channels **before** any command goes out, otherwise the
 *    answers of the sensor are lost.
 * 3. Run the short reconnect, or the first pairing, but never one after the other.
 * 4. Only after the last pairing step, turn the seven data channels off and on again. Without that
 *    the link stays open and no glucose ever arrives.
 *
 * ⚠️ ASYNC IMPACT: [open] blocks until the session is up or has failed. It runs on the driver's
 * own executor, never on the main thread and never on the NFC thread.
 */
class Libre3BleSession(
    private val gatt: Libre3GattClient,
    private val store: Libre3SensorStore,
    private val pairingBlocks: Libre3PairingBlockFactory,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Joins the two pieces of every glucose message. Cleared whenever a link ends. */
    val glucoseFrames = Libre3GlucoseFrameAssembler()

    @Volatile
    private var dataPlane: Libre3DataPlaneCrypto? = null

    /** What came out of a session attempt. */
    sealed interface Result {

        /** The session is up. Glucose can now arrive. */
        data class Up(val material: Libre3SessionMaterial) : Result

        /** Nothing was even tried, and why. */
        data class Refused(val refusal: Libre3StartRefusal) : Result

        /**
         * The attempt failed.
         *
         * @param handshakeReached true when the sensor answered the first pairing message. That
         *   tells the retry policy whether trying the same key again can ever work.
         */
        data class Failed(val reason: String, val handshakeReached: Boolean) : Result
    }

    /**
     * @param firstPairAvailable whether this build can pair a sensor it has never seen.
     */
    fun open(firstPairAvailable: Boolean): Result = try {
        openOrFail(firstPairAvailable)
    } catch (e: Exception) {
        // Any way out that is not a working session must still drop the link. Android allows only
        // a few Bluetooth clients per app, and one leaked per failed attempt means that after a
        // handful of retries nothing can connect again until AAPS is force stopped.
        gatt.disconnect()
        Result.Failed(e.message ?: "the session could not be opened", handshakeReached = false)
    }

    private fun openOrFail(firstPairAvailable: Boolean): Result {
        val identity = store.loadIdentity()
        val keys = store.loadSessionKeys()
        val decision = Libre3SessionStartPolicy.decide(
            hasStoredSensor = identity != null,
            // The store only ever returns a sensor whose PIN really reached the disk, so a sensor
            // that is there at all has a finished write behind it.
            pinWriteFinished = identity != null,
            hasPairingKey = keys.hasPairingKey,
            firstPairAvailable = firstPairAvailable,
        )
        if (decision.start == Libre3SessionStart.BLOCKED || identity == null) {
            val refusal = decision.refusal ?: Libre3StartRefusal.NO_SENSOR_STORED
            Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SESSION}: nothing sent, $refusal")
            return Result.Refused(refusal)
        }
        // From here on the link may be opened, so every way out has to close it again.

        glucoseFrames.reset()
        gatt.connect(identity.bleAddress)
        if (!gatt.isConnected()) return failed("the sensor could not be reached", handshakeReached = false)

        // The three pairing channels have to listen first, or the answers are lost.
        for (channel in Libre3BluetoothUuids.HANDSHAKE_CHANNELS) {
            if (!gatt.setNotify(channel, true)) {
                return failed("a pairing channel could not be opened", handshakeReached = false)
            }
        }

        val auth = Libre3SessionAuth(Libre3GattHandshakeTransport(gatt))
        val phoneR2 = ByteArray(16).also { random.nextBytes(it) }

        val material = try {
            when (decision.start) {
                Libre3SessionStart.CACHED_RECONNECT -> {
                    val pairingKey = keys.phase5RawKey
                        ?: return failed("the stored pairing key disappeared", handshakeReached = false)
                    Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.PAIRING}: short reconnect")
                    auth.runCachedReconnect(identity.blePin, pairingBlocks.blockFor(pairingKey), phoneR2)
                }

                Libre3SessionStart.FIRST_PAIR       -> {
                    // The full clock is written and tested, but it needs two pieces that are not
                    // ported yet: the key pair the sensor's own scheme produces, and the block
                    // maker that protects the pairing messages. When they land, this branch runs
                    // the clock and then MUST store the key with savePhase5RawKeyAndWait, because
                    // without that stored key no reconnect can ever happen again.
                    return failed(
                        "a first pairing is not possible in this build, " +
                            "${Libre3FirstPairEphemeral.unavailableReason()}",
                        handshakeReached = false,
                    )
                }

                Libre3SessionStart.BLOCKED          -> return Result.Refused(Libre3StartRefusal.NO_SENSOR_STORED)
            }
        } catch (e: Libre3HandshakeException) {
            Log.e(Libre3LogMarkers.TAG, "${Libre3LogMarkers.ERROR}: pairing failed, ${e.message}")
            // The sensor answered and then refused, so the stored key is no longer good.
            return failed(e.message ?: "the pairing failed", handshakeReached = true)
        } catch (e: Libre3CryptoException) {
            Log.e(Libre3LogMarkers.TAG, "${Libre3LogMarkers.ERROR}: pairing message could not be read, ${e.message}")
            return failed(e.message ?: "the pairing failed", handshakeReached = true)
        }

        store.saveSessionKeys(material.kEnc, material.ivEnc)
        dataPlane = Libre3DataPlaneCrypto(material.kEnc, material.ivEnc)

        // Only now. The sensor looks at all seven before it starts sending.
        if (!armDataChannels()) {
            return failed("the sensor did not open its data channels", handshakeReached = true)
        }
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SESSION}: session up, data channels open")
        return Result.Up(material)
    }

    /** Ends a failed attempt: the link is dropped, then the reason is reported. */
    private fun failed(reason: String, handshakeReached: Boolean): Result {
        gatt.disconnect()
        return Result.Failed(reason, handshakeReached)
    }

    /** The crypto of the running session, or null when no session is up. */
    fun dataPlaneCrypto(): Libre3DataPlaneCrypto? = dataPlane

    /**
     * Ends the session.
     *
     * Link level only. No command is written to the sensor, whatever the reason.
     */
    fun close(reason: Libre3DisconnectPolicy.Reason) {
        check(!Libre3DisconnectPolicy.mayWriteSensorCommand(reason)) {
            "no reason may ever write a command to the sensor"
        }
        dataPlane = null
        // A piece of a message left over from this link must never be joined to a piece from the
        // next one.
        glucoseFrames.reset()
        gatt.disconnect()
        Log.i(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SESSION}: session closed, reason=$reason")
    }

    /** Turns the seven data channels off and then on again, in the order the sensor expects. */
    private fun armDataChannels(): Boolean {
        for (channel in Libre3BluetoothUuids.DATA_PLANE_CHANNELS) {
            gatt.setNotify(channel, false)
            if (!gatt.setNotify(channel, true)) {
                Log.w(Libre3LogMarkers.TAG, "${Libre3LogMarkers.SESSION}: channel $channel stayed shut")
                return false
            }
        }
        return true
    }
}
