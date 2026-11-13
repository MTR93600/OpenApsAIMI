package app.aaps.pump.omnipod.dashctl.comm.io

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommand
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandHello
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandRTS
import app.aaps.pump.omnipod.dashctl.comm.exceptions.ConnectException
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * The BLE link to one Dash pod, built on the shared [BleCentral]. It gives [cmd] and [data], the
 * two characteristic IO objects that `MessageIO` needs, and [prepare], which sets the link up after
 * connect.
 *
 * This is the common-code version of the Android `legacy/io/BleIO.kt`, `legacy/io/IncomingPackets.kt`
 * and `legacy/callbacks/BleCommCallbacks.kt`, plus the part of `legacy/session/SharedBleConnection.connect`
 * and `ServiceDiscoverer` that runs after the GATT link is up.
 *
 * Events: the owner of [central] must give EVERY [BleCentralEvent] to [onEvent]. [onEvent] never
 * blocks, so it can run on the CoreBluetooth queue or on any other thread. Events for another
 * peripheral are ignored.
 *
 * Peripheral id: [peripheralId] must be the identifier exactly as [central] reports it in its
 * events (on iOS the upper case `CBPeripheral.identifier.UUIDString`). It is compared with `==`,
 * like `CoreBluetoothCentral` looks up its peripherals, so an id in another case matches nothing:
 * its events are ignored, the same way the central ignores the calls.
 *
 * Threading: the functions of [cmd], [data] and [prepare] BLOCK the calling thread (see
 * [BleCharacteristicIO]). Call them from one worker thread, one after the other. Never call them
 * on the thread that delivers the events, or a wait can only end by its timeout.
 *
 * Calls into [central] are made on that worker thread. So the [BleCentral] given here must make
 * each of its calls safe from any thread. `CoreBluetoothCentral` alone is NOT: it must run every
 * call on its radio queue. On iOS, wrap it in `QueuedBleCentral` (iosMain) before giving it here.
 *
 * One object serves one connection. After the link is lost (see [isLinkLost]) every wait ends at
 * once, no new write is started, and new [BleCentralEvent.Value], [BleCentralEvent.Written],
 * [BleCentralEvent.DescriptorWritten], [BleCentralEvent.Services] and
 * [BleCentralEvent.Characteristics] events are ignored. Packets that came before the loss can
 * still be read. Make a new object for the next connection: this way no packet and no
 * confirmation can cross from one connection to the other, in either direction.
 *
 * Differences from Android, because CoreBluetooth gives less information:
 * - A write confirmation ([BleCentralEvent.Written]) carries no bytes. Android checks that the
 *   confirmed bytes are the bytes that were sent. Here confirmations are paired with writes by
 *   count instead: CoreBluetooth answers the writes with response of one peripheral in order. Each
 *   characteristic keeps its own queue of confirmations and counts its writes whose wait timed
 *   out. The late confirmations of those writes are skipped first, so a late confirmation never
 *   confirms a newer write. A CMD confirmation can never confirm a DATA write (on Android one
 *   queue is shared, and the uuid is compared). This needs every write with response to get its
 *   confirmation while the link is up, which CoreBluetooth does. If one were really lost, the
 *   later writes on that characteristic would each time out, until the link is made again.
 * - A late HELLO confirmation: on Android, when the HELLO confirmation comes after its 1 s wait
 *   but before the CMD descriptor confirmation, it takes the place of the descriptor confirmation
 *   (one shared queue), and setup fails with "Received incorrect writeConfirmation". Here it goes
 *   to the CMD confirmation queue and is skipped at the next CMD write, and setup is Ready.
 * - The confirmation of "turn on indications" ([BleCentralEvent.DescriptorWritten]) does not say
 *   which characteristic it is for. So there is one descriptor slot for both, and indications must
 *   be turned on one characteristic at a time ([prepare] does this).
 * - Android checks that the characteristic has exactly one descriptor before it writes the CCC.
 *   [BleCentral] does not list descriptors, so this check is not done. CoreBluetooth writes the CCC
 *   itself (`setNotifyValue`).
 * - Android discovers services and characteristics in one step. CoreBluetooth needs two steps, so
 *   [prepare] has a separate characteristic discovery, with the same timeout.
 */
class DashBleLink(
    private val central: BleCentral,
    val peripheralId: String,
    private val aapsLogger: AAPSLogger
) {

    private val cmdQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val dataQueue = Channel<ByteArray>(Channel.UNLIMITED)

    // Every write confirmation is kept, in order: they are paired with the writes by count.
    private val cmdWritten = Channel<Int>(Channel.UNLIMITED)
    private val dataWritten = Channel<Int>(Channel.UNLIMITED)

    // One item each: a new confirmation replaces an old one.
    private val descriptorWritten = Channel<Int>(Channel.CONFLATED)
    private val servicesFound = Channel<BleCentralEvent.Services>(Channel.CONFLATED)
    private val characteristicsFound = Channel<BleCentralEvent.Characteristics>(Channel.CONFLATED)

    /** Completed once, when the link is lost. Every wait listens to it too. */
    private val linkLost = CompletableDeferred<Unit>()

    /** The CMD characteristic. */
    val cmd: CmdBleIO = DashCmdBleIO()

    /** The DATA characteristic. */
    val data: DataBleIO = DashDataBleIO()

    /**
     * True after a disconnect of this peripheral, after the Bluetooth radio went away, or after
     * [retire].
     */
    val isLinkLost: Boolean get() = linkLost.isCompleted

    /**
     * This link will not be used again (it was closed, or a newer connection took its place). It
     * acts as lost from now on: every wait ends at once and no new write is started, so nothing more
     * of this connection can reach the pod while the next connection is set up. Android gets the
     * same by setting `msgIO` to null in `SharedBleConnection.disconnect`.
     */
    internal fun retire() {
        linkLost.complete(Unit)
    }

    /**
     * Take one event from the central. Never blocks.
     *
     * - [BleCentralEvent.Value] goes to the CMD or DATA incoming queue, by characteristic uuid.
     * - [BleCentralEvent.Written] goes to the confirmation slot of that characteristic.
     * - [BleCentralEvent.DescriptorWritten] for the CCC descriptor goes to the descriptor slot.
     * - [BleCentralEvent.Services] and [BleCentralEvent.Characteristics] (Dash service only) go to
     *   the slots that [prepare] waits on.
     * - [BleCentralEvent.Link] with `connected = false` marks the link as lost and wakes every
     *   waiter. So does an adapter state that means the radio is gone (off, resetting, not
     *   allowed, not supported): CoreBluetooth does not always report a disconnect then.
     *
     * Anything else, and anything for another peripheral id, is ignored. After the link is lost,
     * values, confirmations and discovery results are ignored too (see the class KDoc).
     */
    fun onEvent(event: BleCentralEvent) {
        if (isLinkLost && event.isLinkTraffic()) return
        when (event) {
            is BleCentralEvent.Value             -> if (isMine(event.id)) {
                when {
                    sameUuid(event.characteristicUuid, CharacteristicType.CMD.value)  -> cmdQueue.trySend(event.data)
                    sameUuid(event.characteristicUuid, CharacteristicType.DATA.value) -> dataQueue.trySend(event.data)
                }
            }

            is BleCentralEvent.Written           -> if (isMine(event.id)) {
                when {
                    sameUuid(event.characteristicUuid, CharacteristicType.CMD.value)  -> cmdWritten.trySend(event.status)
                    sameUuid(event.characteristicUuid, CharacteristicType.DATA.value) -> dataWritten.trySend(event.status)
                }
            }

            is BleCentralEvent.DescriptorWritten -> if (isMine(event.id) && sameUuid(event.descriptorUuid, CCC_DESCRIPTOR_UUID)) {
                descriptorWritten.trySend(event.status)
            }

            is BleCentralEvent.Services          -> if (isMine(event.id)) {
                servicesFound.trySend(event)
            }

            is BleCentralEvent.Characteristics   -> if (isMine(event.id) && sameUuid(event.serviceUuid, SERVICE_UUID)) {
                characteristicsFound.trySend(event)
            }

            is BleCentralEvent.Link              -> if (isMine(event.id) && !event.connected) {
                linkLost.complete(Unit)
            }

            is BleCentralEvent.AdapterState      -> if (event.state in RADIO_GONE) {
                linkLost.complete(Unit)
            }

            else                                 -> Unit
        }
    }

    /**
     * Set up the link on a peripheral that is already connected, in the Android order
     * (`SharedBleConnection.connect` after `waitForConnection`):
     *
     * 1. discover services and wait at most [discoveryTimeoutMs];
     * 2. check that the Dash service [SERVICE_UUID] is there;
     * 3. discover the characteristics of that service and wait at most [discoveryTimeoutMs]
     *    (iOS only, see the class KDoc);
     * 4. check that CMD and DATA are there;
     * 5. send HELLO on CMD ([CmdBleIO.hello]). Like Android, its result is only logged, never a
     *    failure;
     * 6. turn on indications on CMD and wait for the confirmation ([BleCharacteristicIO.readyToRead],
     *    [BleCharacteristicIO.DEFAULT_IO_TIMEOUT_MS]);
     * 7. then the same on DATA. Never both at once: the confirmation does not say which
     *    characteristic it is for.
     *
     * [discoveryTimeoutMs] defaults to [MIN_DISCOVERY_TIMEOUT_MS], the shortest time Android gives
     * to service discovery after connect.
     *
     * Blocks the calling thread. Never throws for a BLE failure: it returns the step that failed.
     * On Android the same failures throw `ConnectException`.
     */
    fun prepare(discoveryTimeoutMs: Long = MIN_DISCOVERY_TIMEOUT_MS): DashLinkSetupResult {
        // 1. services
        drop(servicesFound)
        if (isLinkLost) return failed(DashLinkSetupStep.DISCOVER_SERVICES, LINK_LOST)
        try {
            central.discoverServices(peripheralId)
        } catch (e: Exception) {
            return failed(DashLinkSetupStep.DISCOVER_SERVICES, "Could not start discovering services: ${e.message}")
        }
        val services = await(servicesFound, discoveryTimeoutMs)
            ?: return failed(DashLinkSetupStep.DISCOVER_SERVICES, waitFailure("services"))
        if (services.status != 0) {
            return failed(DashLinkSetupStep.DISCOVER_SERVICES, "Service discovery failed with status ${services.status}")
        }

        // 2. the Dash service
        if (services.services.none { sameUuid(it.uuid, SERVICE_UUID) }) {
            for (service in services.services) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Found service: ${service.uuid}")
            }
            return failed(DashLinkSetupStep.FIND_SERVICE, "Service not found: $SERVICE_UUID")
        }

        // 3. characteristics
        drop(characteristicsFound)
        if (isLinkLost) return failed(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, LINK_LOST)
        try {
            central.discoverCharacteristics(peripheralId, SERVICE_UUID)
        } catch (e: Exception) {
            return failed(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, "Could not start discovering characteristics: ${e.message}")
        }
        val characteristics = await(characteristicsFound, discoveryTimeoutMs)
            ?: return failed(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, waitFailure("characteristics"))
        if (characteristics.status != 0) {
            return failed(
                DashLinkSetupStep.DISCOVER_CHARACTERISTICS,
                "Characteristic discovery failed with status ${characteristics.status}"
            )
        }

        // 4. CMD and DATA, checked in the Android order
        for (type in listOf(CharacteristicType.CMD, CharacteristicType.DATA)) {
            if (characteristics.characteristics.none { sameUuid(it.uuid, type.value) }) {
                return failed(DashLinkSetupStep.FIND_CHARACTERISTICS, "Characteristic not found: ${type.value}")
            }
        }

        // 5. HELLO: Android `Connection.hello` ignores the result.
        val hello = cmd.hello()
        if (hello !is BleSendSuccess) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "HELLO was not confirmed: $hello")
        }

        // 6. and 7. indications, one at a time
        try {
            cmd.readyToRead()
        } catch (e: ConnectException) {
            return failed(DashLinkSetupStep.ENABLE_CMD_INDICATIONS, e.msg)
        }
        try {
            data.readyToRead()
        } catch (e: ConnectException) {
            return failed(DashLinkSetupStep.ENABLE_DATA_INDICATIONS, e.msg)
        }
        return DashLinkSetupResult.Ready
    }

    private fun failed(step: DashLinkSetupStep, reason: String): DashLinkSetupResult {
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash link setup failed at $step: $reason")
        return DashLinkSetupResult.Failed(step, reason)
    }

    private fun waitFailure(what: String): String =
        if (isLinkLost) LINK_LOST else "Timeout waiting for $what"

    private fun isMine(id: String): Boolean = id == peripheralId

    private fun BleCentralEvent.isLinkTraffic(): Boolean =
        this is BleCentralEvent.Value || this is BleCentralEvent.Written || this is BleCentralEvent.DescriptorWritten ||
            this is BleCentralEvent.Services || this is BleCentralEvent.Characteristics

    /** Throw away what waits in [slot]. Returns how many items were dropped. */
    private fun <T> drop(slot: Channel<T>): Int {
        var count = 0
        while (slot.tryReceive().isSuccess) count++
        return count
    }

    /**
     * Wait at most [timeoutMs] for one item of [channel], on the calling thread.
     * Returns null on timeout, or at once when the link is lost and nothing is waiting.
     * An item that is already there is always returned, also after the link is lost.
     */
    private fun <T> await(channel: ReceiveChannel<T>, timeoutMs: Long): T? {
        channel.tryReceive().getOrNull()?.let { return it }
        if (isLinkLost || timeoutMs <= 0) return null
        return runBlocking {
            withTimeoutOrNull(timeoutMs) {
                select<T?> {
                    // select prefers the first clause, so an item wins over the lost signal.
                    channel.onReceive { it }
                    linkLost.onAwait { null }
                }
            }
        }
    }

    /**
     * What `legacy/io/BleIO.kt` does, on [BleCentral].
     *
     * [peeked] is the packet that [CmdBleIO.peekCommand] took out of the channel, because a
     * channel cannot show its head without taking it. It is the head of the queue, so every read
     * looks at it first. It is touched only by the worker thread.
     */
    private abstract inner class DashCharacteristicIO(
        private val type: CharacteristicType,
        private val incoming: Channel<ByteArray>,
        private val written: Channel<Int>
    ) : BleCharacteristicIO {

        protected var peeked: ByteArray? = null

        /**
         * Writes on this characteristic whose wait for a confirmation timed out, and whose
         * confirmation has not come yet. Their confirmations are skipped when they come. Touched
         * only by the worker thread.
         */
        private var stale = 0

        override fun receivePacket(timeoutMs: Long): ByteArray? {
            peeked?.let {
                peeked = null
                return it
            }
            val packet = await(incoming, timeoutMs)
            if (packet == null) {
                aapsLogger.debug(LTag.PUMPBTCOMM, if (isLinkLost) "$LINK_LOST, reading $type packet" else "Timeout reading $type packet")
            }
            return packet
        }

        /**
         * Android order: clear the old confirmations, start the write (with response), wait.
         *
         * On iOS a confirmation has no bytes, so it is paired with its write by count (see the
         * class KDoc). Before the write, every confirmation that is already there is taken without
         * waiting: first the late ones of [stale] writes, then any other one, which is unexpected
         * and logged like Android does. While waiting, a confirmation that comes while [stale] is
         * above zero belongs to an older write and is skipped. The next one is the answer to this
         * write. A timeout adds this write to [stale].
         */
        override fun sendAndConfirmPacket(payload: ByteArray): BleSendResult {
            aapsLogger.debug(LTag.PUMPBTCOMM, "BleIO: Sending on $type: ${payload.toCommLogHex()}")
            var unexpected = 0
            while (written.tryReceive().isSuccess) {
                if (stale > 0) stale-- else unexpected++
            }
            if (unexpected > 0) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Write queue should be empty, found: $unexpected")
            }
            if (isLinkLost) {
                return BleSendErrorSending("Could not writeCharacteristic on $type: $LINK_LOST")
            }
            val started = try {
                central.write(peripheralId, type.value, payload, withResponse = true)
            } catch (e: Exception) {
                return BleSendErrorSending("Could not writeCharacteristic on $type", e)
            }
            if (!started) {
                return BleSendErrorSending("Could not writeCharacteristic on $type")
            }
            val start = TimeSource.Monotonic.markNow()
            var status: Int? = null
            while (status == null) {
                val left = BleCharacteristicIO.DEFAULT_IO_TIMEOUT_MS - start.elapsedNow().inWholeMilliseconds
                val next = await(written, left)
                if (next == null) {
                    stale++
                    return BleSendErrorConfirming(if (isLinkLost) LINK_LOST else "Timeout waiting for writeConfirmation")
                }
                if (stale > 0) {
                    stale--
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Skipped the late confirmation of an older write on $type")
                } else {
                    status = next
                }
            }
            if (status != 0) {
                return BleSendErrorConfirming("Write status is not success: $status")
            }
            return BleSendSuccess
        }

        override fun flushIncomingQueue(): Boolean {
            var foundRTS = false
            var packet = peeked ?: incoming.tryReceive().getOrNull()
            peeked = null
            while (packet != null) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "BleIO: queue not empty, flushing: ${packet.toCommLogHex()}")
                if (packet.isNotEmpty() && packet[0] == BleCommandRTS.data[0]) {
                    foundRTS = true
                }
                packet = incoming.tryReceive().getOrNull()
            }
            return foundRTS
        }

        /**
         * Turn on indications and wait for the CCC confirmation. Throws [ConnectException] on every
         * failure, like Android. Only one characteristic at a time may be waiting here.
         */
        override fun readyToRead(): BleSendResult {
            drop(descriptorWritten)
            if (isLinkLost) throw ConnectException("Could not enable notifications: $LINK_LOST")
            val started = try {
                central.enableNotify(peripheralId, type.value, indicate = true)
            } catch (e: Exception) {
                throw ConnectException("Could not enable notifications: ${e.message}")
            }
            if (!started) throw ConnectException("Could not enable notifications")
            aapsLogger.debug(LTag.PUMPBTCOMM, "Enabling indications for $type")
            val status = await(descriptorWritten, BleCharacteristicIO.DEFAULT_IO_TIMEOUT_MS)
                ?: throw ConnectException(if (isLinkLost) LINK_LOST else "Timeout waiting for writeConfirmation")
            if (status != 0) throw ConnectException("onDescriptorWrite status is not success: $status")
            return BleSendSuccess
        }
    }

    private inner class DashCmdBleIO : DashCharacteristicIO(CharacteristicType.CMD, cmdQueue, cmdWritten), CmdBleIO {

        override fun peekCommand(): ByteArray? {
            if (peeked == null) peeked = cmdQueue.tryReceive().getOrNull()
            return peeked
        }

        override fun hello(): BleSendResult = sendAndConfirmPacket(BleCommandHello(CONTROLLER_ID).data)

        override fun expectCommandType(expected: BleCommand, timeoutMs: Long): BleConfirmResult =
            receivePacket(timeoutMs)?.let {
                if (it.isNotEmpty() && it[0] == expected.data[0]) BleConfirmSuccess
                else BleConfirmIncorrectData(it)
            } ?: BleConfirmError("Error reading packet")
    }

    private inner class DashDataBleIO : DashCharacteristicIO(CharacteristicType.DATA, dataQueue, dataWritten), DataBleIO

    companion object {

        /** The Dash pod GATT service. Android `ServiceDiscoverer.SERVICE_UUID`. */
        const val SERVICE_UUID = "1a7e4024-e3ed-4464-8b7e-751e03d0dc5f"

        /** The Client Characteristic Configuration descriptor, as [BleCentralEvent.DescriptorWritten] reports it. */
        const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        /** Android `OmnipodDashBleManagerImpl.CONTROLLER_ID`. */
        const val CONTROLLER_ID = 4242

        /** Android `SharedBleConnection.MIN_DISCOVERY_TIMEOUT_MS`. */
        const val MIN_DISCOVERY_TIMEOUT_MS = 10_000L

        private const val LINK_LOST = "Link lost"

        private val RADIO_GONE = setOf(
            BleAdapterState.PoweredOff,
            BleAdapterState.Resetting,
            BleAdapterState.Unauthorized,
            BleAdapterState.Unsupported
        )
    }
}

/** The step of [DashBleLink.prepare] that failed. */
enum class DashLinkSetupStep {
    DISCOVER_SERVICES,
    FIND_SERVICE,
    DISCOVER_CHARACTERISTICS,
    FIND_CHARACTERISTICS,
    ENABLE_CMD_INDICATIONS,
    ENABLE_DATA_INDICATIONS
}

/** Result of [DashBleLink.prepare]. */
sealed class DashLinkSetupResult {

    /** The link is ready: both characteristics found, indications on for both. */
    object Ready : DashLinkSetupResult()

    /** [step] failed for [reason]. The link is not usable; disconnect and try again. */
    data class Failed(val step: DashLinkSetupStep, val reason: String) : DashLinkSetupResult()
}

/**
 * True when [a] and [b] name the same BLE uuid. Case does not matter, and a 16 or 32 bit short
 * form is expanded to the full 128 bit form first, like `expandUuid` in `CoreBluetoothCentral`.
 */
internal fun sameUuid(a: String, b: String): Boolean = expandBleUuid(a) == expandBleUuid(b)

internal fun expandBleUuid(raw: String): String {
    val hex = raw.lowercase()
    return when (hex.length) {
        4    -> "0000$hex-0000-1000-8000-00805f9b34fb"
        8    -> "$hex-0000-1000-8000-00805f9b34fb"
        else -> hex
    }
}
