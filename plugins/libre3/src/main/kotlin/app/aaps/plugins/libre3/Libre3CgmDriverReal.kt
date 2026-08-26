package app.aaps.plugins.libre3

import android.content.Context
import app.aaps.plugins.libre3.crypto.Libre3FirstPairEphemeral
import app.aaps.plugins.libre3.gatt.Libre3BluetoothUuids
import app.aaps.plugins.libre3.gatt.Libre3GattClientAndroid
import app.aaps.plugins.libre3.gatt.Libre3GattClient
import app.aaps.plugins.libre3.identity.Libre3SensorIdentity
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.libre3.parse.Libre3DataFrame
import app.aaps.plugins.libre3.parse.Libre3GlucoseParser
import app.aaps.plugins.libre3.parse.Libre3ParseException
import app.aaps.plugins.libre3.parse.Libre3PatchStatusParser
import app.aaps.plugins.libre3.parse.toSampleOrNull
import app.aaps.plugins.libre3.reconnect.Libre3ReconnectPolicy
import app.aaps.plugins.libre3.reconnect.Libre3RecoveryAction
import app.aaps.plugins.libre3.session.Libre3BleSession
import app.aaps.plugins.libre3.session.Libre3DisconnectPolicy
import app.aaps.plugins.libre3.session.Libre3PairingBlockFactory
import app.aaps.plugins.libre3.session.Libre3StartRefusal
import app.aaps.plugins.libre3.warmup.Libre3WarmupClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The real driver: one Bluetooth session, one thread, one sensor.
 *
 * It is only reached when the engineering switch is on. The stub stays the default until a user
 * has confirmed this driver on a real sensor.
 *
 * ⚠️ ASYNC IMPACT: everything that talks to the sensor runs on [bleExecutor], one thing at a time.
 * The watchers are called from that thread, so they must not block it.
 */
class Libre3CgmDriverReal(
    private val pairingBlocks: Libre3PairingBlockFactory,
) : Libre3CgmDriver {

    private val watchers = CopyOnWriteArrayList<Libre3GlucoseWatcher>()

    private val bleExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "libre3-ble").apply { isDaemon = true }
    }

    @Volatile
    private var context: Context? = null

    @Volatile
    private var store: Libre3SensorStore? = null

    @Volatile
    private var session: Libre3BleSession? = null

    @Volatile
    private var gatt: Libre3GattClient? = null

    @Volatile
    private var warmup = Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE)

    @Volatile
    private var sessionUp = false

    @Volatile
    private var failedAttempts = 0

    /** True while another job on the same radio must not be disturbed. See [setRadioBackOff]. */
    @Volatile
    private var radioBackOff = false

    /**
     * Raised on every new [connect] and every [stopSession], so a retry that was already queued
     * cannot start after the user asked to stop, or after a newer connect has begun.
     */
    @Volatile
    private var connectGeneration = 0

    override fun setContext(context: Context) {
        this.context = context.applicationContext
        this.store = Libre3SensorStore(context.applicationContext)
    }

    override fun addWatcher(watcher: Libre3GlucoseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    override fun removeWatcher(watcher: Libre3GlucoseWatcher) {
        watchers.remove(watcher)
    }

    override fun connect(deviceAddress: String) {
        failedAttempts = 0
        val generation = synchronized(this) { ++connectGeneration }
        // Drop a connect that is already waiting, so a new NFC scan is not stuck behind it.
        gatt?.disconnect()
        bleExecutor.execute { openSession(generation) }
    }

    override fun disconnect() {
        stopSession(Libre3DisconnectPolicy.Reason.USER_DISCONNECTED)
    }

    override fun shutdown() {
        stopSession(Libre3DisconnectPolicy.Reason.USER_STOPPED_PLUGIN)
        watchers.clear()
        bleExecutor.shutdownNow()
    }

    /**
     * ⚠️ ASYNC IMPACT: called from the thread that watches the lease, not from [bleExecutor]. It
     * only sets a flag and asks the platform for an interval, neither of which waits on anything,
     * so it does not have to be queued behind a running session.
     */
    override fun setRadioBackOff(backOff: Boolean) {
        if (radioBackOff == backOff) return
        radioBackOff = backOff
        Libre3Log.i("${Libre3LogMarkers.SESSION}: radio back off = $backOff")
        gatt?.setLowPower(backOff)
    }

    /**
     * Ends a running session from **any** thread.
     *
     * The read loop below holds the driver's only executor for the whole life of a session, so a
     * stop that was queued on that same executor could never run while a sensor was connected: the
     * plugin could be switched off and the link would stay up for days. So the two steps that
     * actually stop it are done here, on the caller's thread:
     *
     * 1. the loop is told to end, and
     * 2. the link is dropped, which wakes the loop out of its wait at once.
     *
     * Dropping the link is a link level action only. No command is sent to the sensor, whatever
     * the reason, see [Libre3DisconnectPolicy].
     */
    private fun stopSession(reason: Libre3DisconnectPolicy.Reason) {
        check(!Libre3DisconnectPolicy.mayWriteSensorCommand(reason)) {
            "no reason may ever write a command to the sensor"
        }
        synchronized(this) { connectGeneration++ }
        sessionUp = false
        val current = session
        session = null
        gatt = null
        current?.close(reason)
        publishWarmup(Libre3WarmupState.Phase.IDLE)
        watchers.forEach { it.onSession(false, reason.name) }
    }

    /**
     * Ends a session whose link died, and gets the driver ready to try again.
     *
     * It does everything [stopSession] does **except raise the generation**, and that is the whole
     * point: the generation is what tells a queued retry that it is stale, so raising it here would
     * cancel the very retry this failure has to start. A newer [connect] or a [stopSession] still
     * raises it, and still wins over anything queued here.
     */
    private fun endSessionAfterLinkLoss(generation: Int) {
        sessionUp = false
        val current = session
        session = null
        gatt = null
        current?.close(Libre3DisconnectPolicy.Reason.LINK_LOST)
        watchers.forEach { it.onSession(false, Libre3DisconnectPolicy.Reason.LINK_LOST.name) }
        Libre3Log.i("${Libre3LogMarkers.SESSION}: reading stopped, the link is gone")

        if (generation != connectGeneration) return
        failedAttempts++
        val message = "the link to the sensor was lost"
        publishWarmup(Libre3WarmupState.Phase.RECONNECTING, message = message)
        Libre3Log.w("${Libre3LogMarkers.RECONNECT}: link lost, attempt $failedAttempts")
        watchers.forEach { it.onError(message, false) }
        scheduleRetry(generation)
    }

    override fun warmupState(): Libre3WarmupState = warmup

    override fun isSessionUp(): Boolean = sessionUp

    private fun openSession(generation: Int) {
        if (generation != connectGeneration) return
        // Opening a session begins with a scan, so it waits for the radio to come back. The sensor
        // is not given up: the retry keeps knocking at the slow pace until the lease ends.
        if (radioBackOff) {
            Libre3Log.i("${Libre3LogMarkers.SESSION}: not opening a session, the radio is lent out")
            scheduleRetry(generation)
            return
        }
        val appContext = context ?: return
        val sensorStore = store ?: return
        val client = Libre3GattClientAndroid(appContext)
        gatt = client
        val newSession = Libre3BleSession(client, sensorStore, pairingBlocks)
        session = newSession

        publishWarmup(Libre3WarmupState.Phase.CONNECTING)
        when (val result = newSession.open(firstPairAvailable = Libre3FirstPairEphemeral.isAvailable)) {
            is Libre3BleSession.Result.Up      -> {
                failedAttempts = 0
                sessionUp = true
                // The back off may have been asked for while this link was still coming up, and
                // the interval is only settable once there is a link to set it on.
                if (radioBackOff) client.setLowPower(true)
                watchers.forEach { it.onSession(true, null) }
                startReading(newSession, client, sensorStore, generation)
            }

            is Libre3BleSession.Result.Refused -> {
                sessionUp = false
                publishWarmup(Libre3WarmupState.Phase.FAILED, message = refusalMessage(result.refusal))
                watchers.forEach { it.onError(refusalMessage(result.refusal), true) }
            }

            is Libre3BleSession.Result.Failed  -> {
                sessionUp = false
                failedAttempts++
                val action = Libre3ReconnectPolicy.actionAfterFailure(failedAttempts, result.handshakeReached)
                val fatal = action == Libre3RecoveryAction.ASK_FOR_NFC_SCAN
                publishWarmup(
                    if (fatal) Libre3WarmupState.Phase.FAILED else Libre3WarmupState.Phase.RECONNECTING,
                    message = result.reason,
                )
                Libre3Log.w(
                    "${Libre3LogMarkers.RECONNECT}: attempt $failedAttempts failed, " +
                        "handshakeReached=${result.handshakeReached}, next action $action",
                )
                watchers.forEach { it.onError(result.reason, fatal) }
                if (action == Libre3RecoveryAction.RETRY_CACHED_RECONNECT) {
                    scheduleRetry(generation)
                }
            }
        }
    }

    /**
     * Waits then opens the same stored sensor again, unless a newer [connect] or a stop has
     * cancelled this generation.
     *
     * ⚠️ ASYNC IMPACT: the wait runs on [bleExecutor]. A stop interrupts it through
     * [ExecutorService.shutdownNow].
     */
    private fun scheduleRetry(generation: Int) {
        // A retry means a scan, and a scan is the one thing a backed off driver must not do. So the
        // wait is stretched to the slow pace and the ladder is not climbed, which keeps this driver
        // off the air without giving the sensor up.
        val delayMs = if (radioBackOff) Libre3ReconnectPolicy.SLOW_RETRY_MS else Libre3ReconnectPolicy.nextDelayMs(failedAttempts)
        Libre3Log.i("${Libre3LogMarkers.RECONNECT}: retry in ${delayMs}ms, backOff=$radioBackOff")
        bleExecutor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            if (generation != connectGeneration) return@execute
            if (sessionUp) return@execute
            if (radioBackOff) {
                scheduleRetry(generation)
                return@execute
            }
            openSession(generation)
        }
    }

    /**
     * Reads whatever the sensor sends, for as long as the link holds.
     *
     * Every glucose message is put together from its two pieces, read, checked against the sensor
     * life, and only then handed on. A message that fails any check is dropped here and never
     * reaches the plugin.
     */
    private fun startReading(
        openSession: Libre3BleSession,
        client: Libre3GattClient,
        sensorStore: Libre3SensorStore,
        generation: Int,
    ) {
        val crypto = openSession.dataPlaneCrypto() ?: return
        val identity = sensorStore.loadIdentity() ?: return
        while (sessionUp) {
            // One stream for all seven channels. Glucose and sensor health arrive mixed, and the
            // health channel is the only thing that speaks during the hour of warm-up, so waiting
            // on glucose alone would look like a dead session.
            val event = client.awaitDataPlaneNotify(DATA_WAIT_MS)
            // A stop from another thread drops the link, which is what wakes this wait.
            if (!sessionUp) break
            if (event == null) {
                if (!client.isConnected()) break
                continue
            }
            val (channel, piece) = event
            if (piece.isEmpty()) break
            val isGlucose = channel == Libre3BluetoothUuids.GLUCOSE_DATA
            val isPatchStatus = channel == Libre3BluetoothUuids.PATCH_STATUS
            // Only two channels are read in this version. The others are armed because the sensor
            // will not send without them, but nothing is made of what they say.
            if (!isGlucose && !isPatchStatus) continue
            // Only the glucose channel arrives in two pieces. Everything else is whole already.
            val whole = openSession.glucoseFrames.feed(piece, isGlucoseChannel = isGlucose) ?: continue
            try {
                // The packet number comes from the message itself. Counting on this side would
                // drift apart from the sensor after the first missed packet, and every message
                // after that would fail its own check.
                val frame = Libre3DataFrame.parse(whole)
                val plaintext = crypto.decryptTryingAllKinds(frame.encrypted, frame.sequenceNumber).plaintext
                handlePlaintext(plaintext, isGlucose, identity, sensorStore)
            } catch (e: Libre3ParseException) {
                Libre3Log.w("${Libre3LogMarkers.BG}: unreadable message dropped, ${e.message}")
            } catch (e: Exception) {
                Libre3Log.w("${Libre3LogMarkers.BG}: message dropped, ${e.javaClass.simpleName}")
            }
        }
        // The loop ends for one of two reasons, and they must not be treated alike.
        //
        // - A stop was asked for. Then [sessionUp] is already false, the generation was already
        //   raised, and the driver must stay quiet.
        // - The link died on its own. Then nobody asked for anything, the sensor is still on the
        //   arm and its key is still good, so the driver has to knock again. Before this existed
        //   the session simply ended here and only a new NFC scan brought the sensor back.
        if (sessionUp) {
            endSessionAfterLinkLoss(generation)
        } else {
            Libre3Log.i("${Libre3LogMarkers.SESSION}: reading stopped")
        }
    }

    /**
     * @param fromGlucoseChannel which channel the message came from. The kind of record is decided
     *   by that and never by how long the plain text happens to be: another channel could one day
     *   send a record of the same length, and it would then be published as a glucose value.
     */
    private fun handlePlaintext(
        plaintext: ByteArray,
        fromGlucoseChannel: Boolean,
        identity: Libre3SensorIdentity,
        sensorStore: Libre3SensorStore,
    ) {
        if (!fromGlucoseChannel) {
            if (plaintext.size != Libre3PatchStatusParser.PLAINTEXT_SIZE) return
            val status = Libre3PatchStatusParser.parse(plaintext)
            if (status.hasSensorError()) {
                watchers.forEach { it.onError("the sensor reports a problem: ${status.sensorError}", false) }
            }
            return
        }
        if (plaintext.size != Libre3GlucoseParser.PLAINTEXT_SIZE) return

        val reading = Libre3GlucoseParser.parse(plaintext)
        // A sensor that is still sending good readings is alive, whatever the stored end says. One
        // bad NFC reading of the wear time must not be able to end the stream in silence.
        val wearMinutes = if (reading.dataQualityGood && reading.glucoseMgdl != null) {
            sensorStore.extendWearIfStillAlive(reading.lifeCount)
        } else {
            identity.wearDurationMinutes
        }
        val clock = Libre3WarmupClock(
            lifeCountMinutes = reading.lifeCount,
            warmupMinutes = identity.warmupMinutes,
            wearMinutes = wearMinutes.takeIf { it > 0 },
        )
        if (clock.isWarmingUp) {
            publishWarmup(Libre3WarmupState.Phase.WARMING, remainingMs = clock.remainingWarmupMs)
            return
        }

        // The one and only way a reading becomes something the loop may dose on.
        val sample = reading.toSampleOrNull(
            activatedAtMs = identity.activatedAtMs,
            wearMinutes = wearMinutes.takeIf { it > 0 },
            warmupMinutes = identity.warmupMinutes,
        ) ?: return
        // Ready is said only once a reading has really passed every check, so the screen never
        // shows a working sensor while its readings are being refused.
        publishWarmup(Libre3WarmupState.Phase.READY)
        // The restart mark is written by the plugin, after the reading has reached the database.
        // Writing it here would raise it for a reading that was then dropped, and that reading
        // could never be offered again.
        watchers.forEach { it.onGlucose(sample) }
    }

    private fun publishWarmup(
        phase: Libre3WarmupState.Phase,
        remainingMs: Long? = null,
        message: String? = null,
    ) {
        warmup = Libre3WarmupState(phase = phase, remainingMs = remainingMs, message = message)
        watchers.forEach { it.onWarmup(warmup) }
    }

    private fun refusalMessage(refusal: Libre3StartRefusal): String = when (refusal) {
        Libre3StartRefusal.NO_SENSOR_STORED         -> "no sensor has been scanned yet"
        Libre3StartRefusal.PIN_NOT_WRITTEN_YET      -> "the sensor was not stored yet"
        Libre3StartRefusal.FIRST_PAIR_NOT_AVAILABLE -> "a new sensor cannot be paired by this build"
    }

    companion object {

        /** How long one wait for a sensor message lasts before the link is checked again. */
        const val DATA_WAIT_MS = 90_000L
    }
}
