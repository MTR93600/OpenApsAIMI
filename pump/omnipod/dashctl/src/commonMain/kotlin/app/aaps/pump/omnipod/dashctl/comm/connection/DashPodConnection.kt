package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupResult
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupStep
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import kotlin.concurrent.Volatile

/**
 * The side of the pump that [DashPodConnection] talks to. The pump and its stored pod state live on
 * across BLE connections; only the link and the session belong to one connection.
 *
 * On iOS this is `IosDashPump`. It is an interface so that the connection rules stay in commonMain
 * and run in the JVM tests too.
 */
interface DashLinkClient {

    /** True when the pod is paired (an LTK is stored). */
    fun isPaired(): Boolean

    /** True when a session is set up on the link the client holds now, and that link is usable. */
    fun hasSession(): Boolean

    /**
     * Takes the link lock without waiting, as Android `SharedBleManager.acquireBusy`. The same lock
     * is held by every command, by pairing and by the session set up of the client. False when one
     * of them (or another [DashPodConnection.connect]) runs now: the connect is then refused
     * ([DashConnectResult.Busy]), as Android throws `BusyException`.
     *
     * It never waits, so a connect called from inside a command (on the thread that holds the lock)
     * is refused instead of blocking for ever.
     */
    fun tryLockLink(): Boolean

    /** Gives back the lock taken by [tryLockLink]. */
    fun unlockLink()

    /**
     * A new connection: from now on use [link] only. The old session (if any) ends, the stored pod
     * state (counters, open command) stays. The next session starts its outer counter from 1, unless
     * a pairing on this link gives another start. Called only while [tryLockLink] holds the lock.
     */
    fun attachLink(link: DashPodLink)

    /**
     * EAP-AKA on the attached link (Android `SharedBleManager.establishSession`). Throws on failure.
     * Called only while [tryLockLink] holds the lock.
     */
    fun startSession()
}

/**
 * One BLE connection to the pod, as [DashPodConnection] gives it to the pump.
 *
 * It is usable until the BLE link is lost, until [close] is called, or until a newer connection
 * takes its place. After that it is never usable again: every command on it must be refused.
 */
class DashPodLink internal constructor(
    /** The `MessageIO` of this connection. Do not use it once [isUsable] is false. */
    val messageIO: MessageIO,
    private val bleLink: DashBleLink,
    /** Counts the connections of one [DashPodConnection], from 1. For logs and tests. */
    val number: Int,
    private val onClose: (DashPodLink, String) -> Unit,
) {

    @Volatile
    private var closed = false

    @Volatile
    internal var replaced = false
        private set

    /** False after the BLE link was lost, after [close], or after a newer connection was made. */
    val isUsable: Boolean get() = !closed && !replaced && !bleLink.isLinkLost

    /**
     * Closes this connection for good, as Android `disconnect(false)` after an error: the BLE
     * connection is cancelled, so the next session is on a new connection. Only the connection
     * that is still the current one is cancelled: closing an old link never touches a newer one.
     *
     * The [DashBleLink] of this connection is retired first: a command that still runs on it (the
     * close can come from another thread) ends at once and writes nothing more.
     */
    fun close(reason: String) {
        if (closed) return
        closed = true
        bleLink.retire()
        onClose(this, reason)
    }

    /** A newer connection takes the place of this one. Nothing more is written on it. */
    internal fun retire() {
        replaced = true
        bleLink.retire()
    }
}

/** Result of [DashPodConnection.connect]. */
sealed class DashConnectResult {

    /** The current link is usable and has a session: nothing was done (Android `AlreadyConnected`). */
    object AlreadyConnected : DashConnectResult()

    /** The link is ready but the pod is not paired: pair on this link, then set up the session. */
    object LinkReady : DashConnectResult()

    /**
     * Nothing was done: a command, a pairing, a session set up or another connect holds the link
     * lock (Android `BusyException`). Try again when it has ended.
     */
    object Busy : DashConnectResult()

    /** A new link and a new session: commands can go. */
    object SessionReady : DashConnectResult()

    /** The link could not be set up; the BLE connection was cancelled. */
    data class LinkFailed(val step: DashLinkSetupStep, val reason: String) : DashConnectResult()

    /** The session could not be set up; the BLE connection was cancelled. */
    data class SessionFailed(val reason: String) : DashConnectResult()
}

/**
 * Owns the link to one pod across BLE connections: a new [DashBleLink] and a new [MessageIO] for
 * every connection, as Android `SharedBleConnection.connect` makes a new `MessageIO` each time. The
 * pump ([client]) and its stored state live on.
 *
 * Port of `SharedBleManager.connect` after the GATT link is up:
 * 1. the current link is usable and the client has a session: [DashConnectResult.AlreadyConnected];
 * 2. otherwise a new [DashBleLink] is made and [DashBleLink.prepare] runs (services,
 *    characteristics, HELLO, indications);
 * 3. the new link is given to the client ([DashLinkClient.attachLink]);
 * 4. the session is set up with the outer counter from 1 (`establishSession(1)`).
 *
 * When the pod is not paired yet, step 4 is skipped and [DashConnectResult.LinkReady] is returned:
 * the caller pairs on this link and then sets up the session with the counter from the pair result,
 * as Android `pairNewPod` does after `conn.connect`.
 *
 * On a failure in step 2 or 4 the BLE connection is cancelled ([BleCentral.cancel]), as Android
 * `disconnect(false)`: a half set up link, or a pod that may still answer an old challenge, is never
 * used again.
 *
 * Events: the owner of [central] gives EVERY [BleCentralEvent] to [onEvent]. They go to the current
 * link only, so nothing of an old connection can reach a new one. An old link is retired when it is
 * replaced or closed ([DashBleLink.retire]): it writes nothing more, so nothing of it can reach the
 * pod during the next connection either.
 *
 * One at a time: [connect] runs while it holds the link lock of the client
 * ([DashLinkClient.tryLockLink]), the lock every command, pairing and session set up holds too. As
 * Android `acquireBusy`, it does not wait: when the lock is taken, [DashConnectResult.Busy] is
 * returned and nothing changes. So the events switch to a new link only when nothing runs on the old
 * one, and a connect from inside a command can never block for ever.
 *
 * Who connects: [connect] runs on a peripheral that is already connected. Starting the BLE connection
 * (the held `connect` of OmniBLE) is the job of the iOS central owner (plan, step 9).
 *
 * Threading: [connect] blocks, like [DashBleLink.prepare]. Call it from the worker thread that sends
 * the commands, never from the thread that delivers the events. As on Android, connect before a
 * command and then send the command, one after the other; never connect from inside a command (it
 * returns [DashConnectResult.Busy]).
 */
class DashPodConnection(
    private val central: BleCentral,
    val peripheralId: String,
    private val aapsLogger: AAPSLogger,
    private val client: DashLinkClient,
    private val discoveryTimeoutMs: Long = DashBleLink.MIN_DISCOVERY_TIMEOUT_MS,
) {

    @Volatile
    private var bleLink: DashBleLink? = null

    @Volatile
    private var current: DashPodLink? = null

    private var connections = 0

    /** The link given to the client last, or null before the first connection. */
    val link: DashPodLink? get() = current

    /** Give every event of the central here. Never blocks. */
    fun onEvent(event: BleCentralEvent) {
        bleLink?.onEvent(event)
    }

    /** See the class KDoc. Never throws for a BLE or session failure: it returns it. */
    fun connect(): DashConnectResult {
        if (!client.tryLockLink()) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Dash connect refused: a command, a pairing or another connect is running")
            return DashConnectResult.Busy
        }
        try {
            return connectWithLock()
        } finally {
            client.unlockLink()
        }
    }

    private fun connectWithLock(): DashConnectResult {
        val old = current
        if (old != null && old.isUsable && client.hasSession()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash already connected (link ${old.number})")
            return DashConnectResult.AlreadyConnected
        }
        old?.retire()

        val newBleLink = DashBleLink(central, peripheralId, aapsLogger)
        bleLink = newBleLink
        val setup = newBleLink.prepare(discoveryTimeoutMs)
        if (setup is DashLinkSetupResult.Failed) {
            current = null
            newBleLink.retire()
            cancelConnection("link setup failed at ${setup.step}: ${setup.reason}")
            return DashConnectResult.LinkFailed(setup.step, setup.reason)
        }

        connections++
        val newLink = DashPodLink(
            messageIO = MessageIO(aapsLogger, newBleLink.cmd, newBleLink.data),
            bleLink = newBleLink,
            number = connections,
            onClose = ::onLinkClosed,
        )
        current = newLink
        client.attachLink(newLink)

        if (!client.isPaired()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link ${newLink.number} ready, pod not paired")
            return DashConnectResult.LinkReady
        }
        return try {
            client.startSession()
            aapsLogger.info(LTag.PUMPBTCOMM, "Dash link ${newLink.number}: session set up")
            DashConnectResult.SessionReady
        } catch (e: Exception) {
            // The client closes the link itself on a failure; this makes sure the radio is cancelled.
            newLink.close("session set up failed: ${e.message}")
            DashConnectResult.SessionFailed(e.message ?: e::class.simpleName ?: "session set up failed")
        }
    }

    /** Closes the current link (Android `disconnect(false)`). */
    fun disconnect(reason: String) {
        current?.close(reason)
    }

    private fun onLinkClosed(link: DashPodLink, reason: String) {
        if (link !== current || link.replaced) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Old Dash link ${link.number} closed ($reason); the current connection is kept")
            return
        }
        cancelConnection("link ${link.number} closed: $reason")
    }

    private fun cancelConnection(reason: String) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash connection cancelled: $reason")
        try {
            central.cancel(peripheralId)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Could not cancel the Dash connection", e)
        }
    }
}
