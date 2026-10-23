package app.aaps.plugins.dexcomoneplus.gatt

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.plugins.dexcomoneplus.OnePlusLog
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.onePlusIosElapsedMs
import app.aaps.plugins.dexcomoneplus.onePlusSleep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [OnePlusGattClient] on CoreBluetooth, through [BleCentral].
 *
 * The order of the handshake is the Android one, because it is the sensor's order and not the
 * platform's: connect, discover, ExtraData subscription, Authentication subscription, then ready.
 * The KEKS writes and the Control / Backfill subscriptions follow, each one at a time.
 *
 * **Control is INDICATE, not NOTIFY, and that is the whole point.** The Android driver failed for
 * weeks with peer status 19 until it stopped insisting on notifications. CoreBluetooth takes the
 * choice out of our hands - `setNotifyValue(true)` subscribes with whichever of the two the
 * characteristic declares - so the only thing that could go wrong here is code that *checks* the
 * properties and refuses an indicate-only characteristic. [subscribeControl] accepts either, and
 * logs which one the sensor offered so a field trace says it out loud.
 *
 * What CoreBluetooth cannot do, and what this does instead of pretending:
 * - **Bonding.** There is no API to start, watch or drop a pairing: iOS pairs by itself on the
 *   first operation that needs an encrypted link, and shows the person its own pairing dialog.
 *   [createBond] therefore says yes - the pairing will happen - and [awaitBondComplete] gives the
 *   next write the whole pairing window, so the person has time to accept the dialog. [isBonded]
 *   stays false because the honest answer is "we cannot know", and [removeBond] stays false
 *   because only the person can forget the device in Settings.
 * - **Connection interval.** There is no equivalent of `requestConnectionPriority`, so
 *   [setLowPower] records that the request was dropped rather than reporting a change nobody made.
 * - **Write-without-response completion.** iOS does not call back for one, so the ExtraData chunk
 *   loop keeps the same gaps Android uses and does not wait for an acknowledgement that will never
 *   arrive. Before each chunk it asks [BleCentral.canSendWriteWithoutResponse], which is
 *   CoreBluetooth's `canSendWriteWithoutResponse`, and waits a short time while the stack has no
 *   room - a chunk sent then may be dropped. If the room does not come back in time, the chunk is
 *   sent anyway and the log says so, which is what happened before this check existed. A dropped
 *   chunk still cannot be noticed here; the KEKS round that follows fails instead.
 *
 * Three settings in [OemDeviceProfile] have no iOS meaning and are not acted on: `requestMtu` and
 * `preferredMtu` (CoreBluetooth negotiates the ATT size by itself and never asks us),
 * `useGattRefresh` (a hidden Android API for a stale service cache, which iOS does not have) and
 * `forceHighConnectionPriority` (see [setLowPower]). `scanHandoffMs`, `connectTimeoutMs` and
 * `postCloseSettleMs` are honoured.
 *
 * ⚠️ ASYNC IMPACT: every `await*`, [connect] and every write blocks the calling thread until the
 * central answers. The central answers on its own serial queue, so none of these may be called
 * from that queue or from the main thread.
 */
class OnePlusGattClientIos(
    private val central: BleCentral,
    private val profile: OemDeviceProfile = DeviceProfileRegistry.resolve(),
    private val nowMs: () -> Long = ::onePlusIosElapsedMs,
    private val sleep: (Long) -> Boolean = ::onePlusSleep,
) : OnePlusGattClient {

    /** Serializes one outstanding write or subscription, exactly as the Android client does. */
    private val opLock = AapsLock()
    private val stateLock = AapsLock()

    /** Set by [awaitBondComplete]: until then a write may wait for the person to accept pairing. */
    private var pairingWindowEnd: TimeMark? = null

    @Volatile
    private var linkId: String? = null

    @Volatile
    private var connected = false

    /**
     * Completed once per [connect]: `READY_OK` when the sensor is ready, otherwise the reason it
     * is not. A plain null for success could not be told apart from a timeout.
     */
    @Volatile
    private var ready: CompletableDeferred<String>? = null

    @Volatile
    private var pendingOp: CompletableDeferred<Int>? = null

    @Volatile
    private var cccdPhase = CccdPhase.NONE

    /** When the last link was cancelled, for the settle wait in [awaitPostCloseSettle]. */
    @Volatile
    private var lastCloseMs = 0L

    private var authProperties: Set<BleCharProperty> = emptySet()
    private var controlProperties: Set<BleCharProperty>? = null
    private var extraPresent = false
    private var backfillPresent = false

    private val keksQueue = Channel<TaggedKeksNotify>(capacity = KEKS_QUEUE_SIZE)
    private val controlQueue = Channel<ByteArray>(capacity = CONTROL_QUEUE_SIZE)
    private val backfillQueue = Channel<ByteArray>(capacity = BACKFILL_QUEUE_SIZE)

    /**
     * CCCD setup state machine. CoreBluetooth answers a subscription with one callback that does
     * not say which characteristic it belongs to, so the two setup subscriptions are made one after
     * the other and this says which answer is being waited for.
     */
    private enum class CccdPhase { NONE, EXTRA, AUTH, DONE }

    // -- lifecycle -------------------------------------------------------------------------------

    override fun connect(deviceAddress: String, autoConnect: Boolean) {
        disconnectInternal(poisonQueues = false)
        awaitPostCloseSettle()
        drainQueues()
        if (deviceAddress.isBlank()) {
            error("ONEPLUS_GATT: device identifier blank - scan UI required")
        }
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) {
            error("ONEPLUS_GATT: Bluetooth not ready, state=$state")
        }
        val handoff = profile.scanHandoffMs.coerceAtLeast(0L)
        if (handoff > 0L) {
            OnePlusLog.d("${OnePlusLogMarkers.SESSION}: scan→connect handoff ${handoff}ms")
            sleep(handoff)
        }
        val latch = CompletableDeferred<String>()
        stateLock.withLock {
            authProperties = emptySet()
            controlProperties = null
            extraPresent = false
            backfillPresent = false
        }
        cccdPhase = CccdPhase.NONE
        ready = latch
        linkId = deviceAddress
        // There is no autoConnect on CoreBluetooth: connectPeripheral simply never gives up by
        // itself. The flag therefore only widens our own patience, as it does on Android.
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: connect autoConnect=$autoConnect (iOS waits either way)")
        if (!central.connect(deviceAddress)) {
            linkId = null
            ready = null
            error("ONEPLUS_GATT: central refused the connection")
        }
        val timeout = if (autoConnect) {
            profile.connectTimeoutMs.coerceAtLeast(AUTO_CONNECT_MIN_TIMEOUT_MS)
        } else {
            profile.connectTimeoutMs
        }
        val outcome = runBlocking { withTimeoutOrNull(timeout) { latch.await() } }
        if (outcome == null) {
            disconnectInternal(poisonQueues = true)
            error("ONEPLUS_GATT: connect/discover timeout ${timeout}ms")
        }
        if (outcome != READY_OK) {
            disconnectInternal(poisonQueues = true)
            error("ONEPLUS_GATT: $outcome")
        }
        // Drop subscription noise and late disconnect sentinels before KEKS waits for Auth frames.
        drainQueues()
    }

    override fun disconnect() {
        disconnectInternal(poisonQueues = true)
    }

    override fun isConnected(): Boolean = connected && linkId != null

    override fun setLowPower(enabled: Boolean) {
        // CoreBluetooth has no connection-interval request. Saying so in the log is the whole
        // implementation: the caller is told nothing changed rather than being told it worked.
        OnePlusLog.w(
            "${OnePlusLogMarkers.SESSION}: link low power=$enabled ignored - iOS cannot ask for a " +
                "connection interval, so the radio lease only holds our scans back",
        )
    }

    // -- writes ----------------------------------------------------------------------------------

    override fun writeAuthentication(payload: ByteArray?) {
        writeWithResponse(OnePlusBluetoothUuids.Authentication, payload, "Auth")
    }

    override fun writeControl(payload: ByteArray?) {
        // Control (0x4E EGlucose request, 0x59 backfill, 0x26 SessionStart) must be written WITH
        // response. Sending 0x4E without one made the ONE+ drop the link with peer status 19.
        writeWithResponse(OnePlusBluetoothUuids.Control, payload, "Control")
    }

    override fun writeExtraData(payload: ByteArray?) {
        if (payload == null || payload.isEmpty()) return
        val id = linkId ?: error("ONEPLUS_GATT: not connected (ExtraData)")
        if (!stateLock.withLock { extraPresent }) error("ONEPLUS_GATT: missing ExtraData characteristic")
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + EXTRA_DATA_CHUNK, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            // Write without response, as Ob1 does. iOS never calls back for one; the stack only
            // says whether it has room for the next one, so that is asked first.
            awaitWriteWithoutResponseRoom(id, chunkStart = offset)
            val accepted = opLock.withLock {
                central.write(id, OnePlusBluetoothUuids.ExtraData, chunk, withResponse = false)
            }
            if (!accepted) error("ONEPLUS_GATT: write ExtraData refused by the central")
            offset = end
            if (offset < payload.size) sleep(EXTRA_DATA_CHUNK_GAP_MS)
        }
        sleep(EXTRA_DATA_AFTER_MS)
        OnePlusLog.d("${OnePlusLogMarkers.SESSION}: wrote ExtraData ${payload.size}b unacknowledged")
    }

    /**
     * Wait, a little, until the stack can take one more write without response.
     *
     * Counted in polls rather than measured on [nowMs], so a test clock that never moves cannot
     * make this loop forever. Gives up after [EXTRA_DATA_ROOM_POLLS] polls and lets the write go
     * ahead: that is the old behaviour, and refusing would end a handshake that might still work.
     */
    private fun awaitWriteWithoutResponseRoom(id: String, chunkStart: Int) {
        var polls = 0
        while (!central.canSendWriteWithoutResponse(id)) {
            if (polls >= EXTRA_DATA_ROOM_POLLS) {
                OnePlusLog.w(
                    "${OnePlusLogMarkers.SESSION}: ExtraData chunk at $chunkStart sent without room " +
                        "after ${polls * EXTRA_DATA_ROOM_POLL_MS}ms - iOS may drop it",
                )
                return
            }
            sleep(EXTRA_DATA_ROOM_POLL_MS)
            polls++
        }
        if (polls > 0) {
            OnePlusLog.d(
                "${OnePlusLogMarkers.SESSION}: ExtraData chunk at $chunkStart waited " +
                    "${polls * EXTRA_DATA_ROOM_POLL_MS}ms for room in the stack",
            )
        }
    }

    // -- subscriptions ---------------------------------------------------------------------------

    override fun enableControlNotifications() = subscribeControl(wantIndicate = false)

    override fun enableControlIndications() = subscribeControl(wantIndicate = true)

    override fun enableBackfillNotifications() {
        val id = linkId ?: error("ONEPLUS_GATT: not connected")
        if (!stateLock.withLock { backfillPresent }) {
            error("ONEPLUS_GATT: ProbablyBackfill characteristic missing")
        }
        subscribeBlocking(id, OnePlusBluetoothUuids.ProbablyBackfill, indicate = false, label = "Backfill")
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: Backfill subscription enabled")
    }

    /**
     * Subscribe to Control.
     *
     * [wantIndicate] is kept only so the log records which of the two the caller asked for.
     * CoreBluetooth decides from the characteristic itself, and on a ONE+ that decision is
     * indications. A characteristic that offers neither is a real fault and is refused here rather
     * than left to time out later with nothing to show for it.
     */
    private fun subscribeControl(wantIndicate: Boolean) {
        val id = linkId ?: error("ONEPLUS_GATT: not connected")
        val properties = stateLock.withLock { controlProperties }
            ?: error("ONEPLUS_GATT: Control characteristic missing")
        val canNotify = BleCharProperty.Notify in properties
        val canIndicate = BleCharProperty.Indicate in properties
        if (!canNotify && !canIndicate) {
            error("ONEPLUS_GATT: Control offers neither notify nor indicate, properties=$properties")
        }
        // Indicate wins when the sensor offers it, which is what a ONE+ does. This is what the
        // stack will use; it is not a choice we get to make.
        val used = if (canIndicate) "indicate" else "notify"
        subscribeBlocking(id, OnePlusBluetoothUuids.Control, indicate = canIndicate, label = "Control")
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: Control subscription enabled as $used " +
                "(asked for ${if (wantIndicate) "indicate" else "notify"}, properties=$properties)",
        )
    }

    // -- bonding, which iOS does not have --------------------------------------------------------

    override fun isBonded(): Boolean {
        // CoreBluetooth never shows the pairing state. False is not a claim that the sensor is
        // unpaired, it is "this platform cannot tell", and the KEKS path reads it the same way.
        return false
    }

    /**
     * Yes: on iOS the pairing happens by itself, the moment the sensor asks for an encrypted link,
     * and the person sees the system pairing dialog. No app can start it - and none needs to.
     * Answering false here stopped every new four digit pairing before iOS had the chance to pair.
     * Not yet tried with a real sensor.
     */
    override fun createBond(): Boolean {
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: createBond on iOS - CoreBluetooth pairs by itself on the next " +
                "encrypted operation, the person accepts the system pairing dialog",
        )
        return true
    }

    override fun removeBond(): Boolean {
        OnePlusLog.e(
            "${OnePlusLogMarkers.ERROR}: removeBond is not possible on iOS - a pairing can only be " +
                "removed by the user in Settings, Bluetooth, Forget This Device",
        )
        return false
    }

    /**
     * There is no bond state to watch on iOS. The pairing happens during the next operation
     * that needs an encrypted link - after the bond request that is the Control subscription of
     * the glucose loop - and that operation waits while the system dialog is up. So the next
     * write or subscription gets this whole [timeoutMs] instead of the usual operation timeout.
     * See [writeWithResponse] and [subscribeBlocking].
     */
    override fun awaitBondComplete(timeoutMs: Long): Boolean {
        stateLock.withLock { pairingWindowEnd = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds }
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: iOS pairing window ${timeoutMs}ms for the next write")
        return true
    }

    // -- incoming frames ---------------------------------------------------------------------------

    override fun awaitKeksNotify(timeoutMs: Long): OnePlusKeksNotify? {
        val frame = poll(keksQueue, timeoutMs) ?: return null
        if (frame === KEKS_DISCONNECT_SENTINEL) return null
        return OnePlusKeksNotify(frame.source, frame.payload)
    }

    override fun awaitNotify(timeoutMs: Long): ByteArray? = awaitKeksNotify(timeoutMs)?.payload

    override fun awaitControlNotify(timeoutMs: Long): ByteArray? = pollBytes(controlQueue, timeoutMs)

    override fun awaitBackfillNotify(timeoutMs: Long): ByteArray? = pollBytes(backfillQueue, timeoutMs)

    /**
     * Feed central events here.
     *
     * The owner wires `central.events = gatt::onEvent`; tests call it directly. Everything below
     * runs on the central's serial queue and must stay short.
     */
    fun onEvent(event: BleCentralEvent) {
        when (event) {
            is BleCentralEvent.Link              -> onLink(event)
            is BleCentralEvent.Services          -> onServices(event)
            is BleCentralEvent.Characteristics   -> onCharacteristics(event)
            is BleCentralEvent.DescriptorWritten -> onDescriptorWritten(event)
            is BleCentralEvent.Written           -> onWritten(event)
            is BleCentralEvent.Value             -> onValue(event)
            is BleCentralEvent.OperationFailed   ->
                OnePlusLog.e("${OnePlusLogMarkers.ERROR}: central ${event.operation} failed: ${event.message}")

            is BleCentralEvent.Restored          -> onRestored(event)

            // A scan belongs to OnePlusBleScannerIos, and the adapter state is read from
            // [BleCentral.adapterState] at the point where it decides something. Written out one
            // by one instead of an `else`, so the next event added to the sealed class has to be
            // looked at here rather than swallowed.
            is BleCentralEvent.AdapterState,
            is BleCentralEvent.ScanResult,
            is BleCentralEvent.ScanFailed        -> Unit
        }
    }

    /**
     * iOS started the app again and handed back peripherals. None of them is taken over.
     *
     * Nothing is adopted and nothing is marked connected. The handshake is per link and cannot be
     * resumed - running it again is the only way back, and after a relaunch the central holds no
     * characteristics to write to anyway. The peripheral itself is released by
     * `OnePlusIosRadio`, which owns the central and is there even when no client is.
     *
     * The dangerous part is the queues. A restored link can still deliver Control indications
     * that belong to the exchange before the app was ended, and `OnePlusEgvSession` would read
     * them as a current glucose value and a current transmitter time. That is a stale reading
     * stamped with the wrong moment, which the loop would act on.
     *
     * When a restored id **is** this client's link, that is treated as a fault and the link is
     * dropped: the restore says this process started again, so nothing this object believed about
     * that link survived. The tear-down also poisons the queues, which wakes every blocked `await`
     * with the disconnect sentinel instead of letting it return a leftover frame. In today's
     * wiring it cannot happen - iOS delivers `willRestoreState` while the manager is being built,
     * which is before anything can connect - so the error line is what would make a future wiring
     * that broke that order visible.
     */
    private fun onRestored(event: BleCentralEvent.Restored) {
        val id = linkId
        if (id == null || id !in event.ids) {
            OnePlusLog.i(
                "${OnePlusLogMarkers.SESSION}: iOS relaunch handed back ${event.ids.size} " +
                    "peripheral(s); none is this client's link and none is taken over",
            )
            return
        }
        OnePlusLog.e(
            "${OnePlusLogMarkers.ERROR}: iOS handed back this client's link ***${id.takeLast(5)} - " +
                "dropping it instead of carrying on, because a restored link can still deliver " +
                "frames from before the app was ended and they would be read as current glucose",
        )
        disconnectInternal(poisonQueues = true)
    }

    private fun onLink(event: BleCentralEvent.Link) {
        if (event.id != linkId) return
        if (event.connected && event.status == 0) {
            connected = true
            OnePlusLog.i("${OnePlusLogMarkers.SESSION}: connected, discovering services")
            central.discoverServices(event.id)
            return
        }
        connected = false
        failReady("disconnected status=${event.status}")
        completePendingOp(event.status.takeIf { it != 0 } ?: GATT_FAILURE)
        poisonQueues()
    }

    private fun onServices(event: BleCentralEvent.Services) {
        if (event.id != linkId) return
        if (event.status != 0) {
            failReady("discover status=${event.status}")
            return
        }
        val service = event.services.firstOrNull {
            it.uuid.equals(OnePlusBluetoothUuids.CgmService, ignoreCase = true)
        }
        if (service == null) {
            failReady("CGM service missing")
            return
        }
        central.discoverCharacteristics(event.id, service.uuid)
    }

    private fun onCharacteristics(event: BleCentralEvent.Characteristics) {
        if (event.id != linkId) return
        if (!event.serviceUuid.equals(OnePlusBluetoothUuids.CgmService, ignoreCase = true)) return
        if (event.status != 0) {
            failReady("characteristics status=${event.status}")
            return
        }
        fun find(uuid: String) = event.characteristics.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
        val auth = find(OnePlusBluetoothUuids.Authentication)
        val extra = find(OnePlusBluetoothUuids.ExtraData)
        val control = find(OnePlusBluetoothUuids.Control)
        val backfill = find(OnePlusBluetoothUuids.ProbablyBackfill)
        stateLock.withLock {
            authProperties = auth?.properties.orEmpty()
            controlProperties = control?.properties
            extraPresent = extra != null
            backfillPresent = backfill != null
        }
        if (auth == null) {
            failReady("Authentication characteristic missing")
            return
        }
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: characteristics auth=${auth.properties} " +
                "extra=${extra != null} control=${control?.properties} backfill=${backfill != null}",
        )
        if (extra != null) {
            cccdPhase = CccdPhase.EXTRA
            if (!central.enableNotify(event.id, OnePlusBluetoothUuids.ExtraData, indicate = false)) {
                failReady("ExtraData subscription refused")
            }
            return
        }
        cccdPhase = CccdPhase.AUTH
        if (!subscribeAuth(event.id)) failReady("Authentication subscription refused")
    }

    private fun subscribeAuth(id: String): Boolean {
        // The Ob1 KEKS path subscribes to Authentication with indications when it offers them.
        // CoreBluetooth reads the same properties and does the same thing; the flag is for the log.
        val indicate = BleCharProperty.Indicate in stateLock.withLock { authProperties }
        return central.enableNotify(id, OnePlusBluetoothUuids.Authentication, indicate = indicate)
    }

    private fun onDescriptorWritten(event: BleCentralEvent.DescriptorWritten) {
        if (event.id != linkId) return
        when (cccdPhase) {
            CccdPhase.EXTRA -> {
                if (event.status != 0) {
                    cccdPhase = CccdPhase.NONE
                    failReady("ExtraData subscription status=${event.status}")
                    return
                }
                cccdPhase = CccdPhase.AUTH
                if (!subscribeAuth(event.id)) {
                    cccdPhase = CccdPhase.NONE
                    failReady("Authentication subscription refused")
                }
            }

            CccdPhase.AUTH  -> {
                if (event.status != 0) {
                    cccdPhase = CccdPhase.NONE
                    failReady("Authentication subscription status=${event.status}")
                    return
                }
                cccdPhase = CccdPhase.DONE
                val hasControl = stateLock.withLock { controlProperties } != null
                val hasBackfill = stateLock.withLock { backfillPresent }
                OnePlusLog.i(
                    "${OnePlusLogMarkers.SESSION}: gatt ready control=$hasControl backfill=$hasBackfill",
                )
                ready?.complete(READY_OK)
            }

            else            -> completePendingOp(event.status)
        }
    }

    private fun onWritten(event: BleCentralEvent.Written) {
        if (event.id != linkId) return
        completePendingOp(event.status)
    }

    private fun onValue(event: BleCentralEvent.Value) {
        if (event.id != linkId) return
        val copy = event.data.copyOf()
        when {
            event.characteristicUuid.equals(OnePlusBluetoothUuids.Authentication, ignoreCase = true) ->
                keksQueue.trySend(TaggedKeksNotify(OnePlusKeksNotifySource.AUTHENTICATION, copy))

            event.characteristicUuid.equals(OnePlusBluetoothUuids.ExtraData, ignoreCase = true)      ->
                keksQueue.trySend(TaggedKeksNotify(OnePlusKeksNotifySource.EXTRA_DATA, copy))

            event.characteristicUuid.equals(OnePlusBluetoothUuids.Control, ignoreCase = true)        ->
                controlQueue.trySend(copy)

            event.characteristicUuid.equals(OnePlusBluetoothUuids.ProbablyBackfill, ignoreCase = true) ->
                backfillQueue.trySend(copy)
        }
    }

    // -- helpers ---------------------------------------------------------------------------------

    /** The usual operation timeout, or what is left of the pairing window when that is longer. Used by writes and subscriptions. */
    private fun writeTimeoutMs(): Long {
        val left = stateLock.withLock { pairingWindowEnd }?.let { -it.elapsedNow().inWholeMilliseconds } ?: 0L
        return maxOf(GATT_OP_TIMEOUT_MS, left)
    }

    private fun writeWithResponse(characteristicUuid: String, payload: ByteArray?, label: String) {
        if (payload == null || payload.isEmpty()) return
        val id = linkId ?: error("ONEPLUS_GATT: not connected ($label)")
        opLock.withLock {
            val op = CompletableDeferred<Int>()
            pendingOp = op
            if (!central.write(id, characteristicUuid, payload, withResponse = true)) {
                pendingOp = null
                error("ONEPLUS_GATT: write $label refused by the central")
            }
            val status = runBlocking { withTimeoutOrNull(writeTimeoutMs()) { op.await() } }
            pendingOp = null
            if (status == null) error("ONEPLUS_GATT: write $label timeout")
            if (status != 0) error("ONEPLUS_GATT: write $label status=$status")
            // The write went through, so a pairing it waited for is done.
            stateLock.withLock { pairingWindowEnd = null }
            OnePlusLog.d("${OnePlusLogMarkers.SESSION}: wrote $label ${payload.size}b")
        }
    }

    private fun subscribeBlocking(id: String, characteristicUuid: String, indicate: Boolean, label: String) {
        opLock.withLock {
            val op = CompletableDeferred<Int>()
            pendingOp = op
            if (!central.enableNotify(id, characteristicUuid, indicate)) {
                pendingOp = null
                error("ONEPLUS_GATT: $label subscription refused by the central")
            }
            val status = runBlocking { withTimeoutOrNull(writeTimeoutMs()) { op.await() } }
            pendingOp = null
            if (status == null) error("ONEPLUS_GATT: $label subscription timeout")
            if (status != 0) error("ONEPLUS_GATT: $label subscription status=$status")
            // The subscription went through, so a pairing it waited for is done.
            stateLock.withLock { pairingWindowEnd = null }
        }
    }

    private fun completePendingOp(status: Int) {
        pendingOp?.complete(status)
    }

    private fun failReady(reason: String) {
        ready?.complete(reason)
    }

    private fun disconnectInternal(poisonQueues: Boolean) {
        connected = false
        cccdPhase = CccdPhase.NONE
        completePendingOp(GATT_FAILURE)
        if (poisonQueues) poisonQueues()
        val id = linkId
        linkId = null
        ready = null
        if (id != null) {
            central.cancel(id)
            lastCloseMs = nowMs()
        }
    }

    /**
     * Give the stack the pause the profile asks for after a link was closed.
     *
     * Same reason as on Android: a reconnect issued immediately after a cancel lands while the old
     * link is still being torn down.
     */
    private fun awaitPostCloseSettle() {
        val settle = profile.postCloseSettleMs.coerceAtLeast(0L)
        if (settle <= 0L || lastCloseMs <= 0L) return
        val remaining = settle - (nowMs() - lastCloseMs)
        if (remaining <= 0L) return
        OnePlusLog.d("${OnePlusLogMarkers.SESSION}: post-close settle ${remaining}ms (floor=${settle}ms)")
        sleep(remaining)
    }

    /**
     * Wake every blocked `await*` with a sentinel that is recognised by identity.
     *
     * Not by being empty: an empty frame that happened to arrive from the sensor would otherwise
     * end a wait that should have gone on, which is exactly the bug the Android client carries a
     * comment about.
     */
    private fun poisonQueues() {
        keksQueue.trySend(KEKS_DISCONNECT_SENTINEL)
        controlQueue.trySend(DISCONNECT_SENTINEL)
        backfillQueue.trySend(DISCONNECT_SENTINEL)
    }

    private fun drainQueues() {
        drain(keksQueue)
        drain(controlQueue)
        drain(backfillQueue)
    }

    private fun <T> drain(queue: Channel<T>) {
        var taken = queue.tryReceive()
        while (taken.isSuccess) {
            taken = queue.tryReceive()
        }
    }

    private fun <T> poll(queue: Channel<T>, timeoutMs: Long): T? =
        runBlocking {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { queue.receive() }
        }

    private fun pollBytes(queue: Channel<ByteArray>, timeoutMs: Long): ByteArray? {
        val value = poll(queue, timeoutMs) ?: return null
        if (value === DISCONNECT_SENTINEL) return null
        return value
    }

    /** Internal KEKS queue frame; [KEKS_DISCONNECT_SENTINEL] is recognised by identity. */
    private class TaggedKeksNotify(
        val source: OnePlusKeksNotifySource,
        val payload: ByteArray,
    )

    companion object {

        /** What the ready latch carries when the sensor really is ready. */
        private const val READY_OK = "ok"

        private const val GATT_OP_TIMEOUT_MS = 10_000L
        private const val EXTRA_DATA_CHUNK = 20
        private const val EXTRA_DATA_CHUNK_GAP_MS = 40L

        /** Pause between two questions "is there room for a write without response?". */
        private const val EXTRA_DATA_ROOM_POLL_MS = 5L

        /** How many times that is asked before a chunk goes out anyway: 500 ms in all. */
        private const val EXTRA_DATA_ROOM_POLLS = 100

        /** Ob1 waits about half a second after the ExtraData chunks before the Auth write. */
        private const val EXTRA_DATA_AFTER_MS = 500L

        private const val AUTO_CONNECT_MIN_TIMEOUT_MS = 60_000L

        /** Stands in for the Android `BluetoothGatt.GATT_FAILURE`, which has no iOS equivalent. */
        private const val GATT_FAILURE = 257

        private const val KEKS_QUEUE_SIZE = 32
        private const val CONTROL_QUEUE_SIZE = 32
        private const val BACKFILL_QUEUE_SIZE = 64

        private val DISCONNECT_SENTINEL = ByteArray(0)

        private val KEKS_DISCONNECT_SENTINEL = TaggedKeksNotify(
            OnePlusKeksNotifySource.AUTHENTICATION,
            ByteArray(0),
        )
    }
}
