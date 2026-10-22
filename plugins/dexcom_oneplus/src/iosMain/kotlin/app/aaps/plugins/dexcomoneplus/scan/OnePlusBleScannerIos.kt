package app.aaps.plugins.dexcomoneplus.scan

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.plugins.dexcomoneplus.OnePlusLog
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.identity.OnePlusAdvCandidate
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.onePlusIosElapsedMs
import app.aaps.plugins.dexcomoneplus.onePlusSleep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [OnePlusBleScanner] on CoreBluetooth, through [BleCentral].
 *
 * The matching rules are the shared ones, so the two platforms agree on what a ONE+ looks like:
 * [OnePlusAdvCandidate] for the sticky match and the ranking, the FEBC advertisement service for a
 * sensor that advertises with no name at all, and no `Dexcom*` marketing name ever.
 *
 * Four things are genuinely different here, and none of them is hidden:
 * - **No MAC address.** iOS never shows one. The "address" of a sensor is the peripheral identifier
 *   CoreBluetooth gives it: stable for this app on this phone, and different on another phone. It
 *   is stored and matched exactly where Android stores and matches the MAC, so the stored value
 *   keeps meaning "the sensor we talked to last time".
 * - **No scan filter.** `CBCentralManager` can filter by service UUID but not by peripheral, so
 *   [awaitTarget] filters in software instead. Same answer, a little more work per advertisement.
 * - **One scan for the whole app.** A second `scanForPeripherals` replaces the first, so the trick
 *   Android uses - a private `ScanCallback` the screen cannot cancel - has no equivalent. Instead
 *   [stopScan] leaves the radio on while an [awaitTarget] is running, which keeps the guarantee the
 *   interface asks for.
 * - **No scan quota.** iOS does not throttle an app that scans often, so none of Android's
 *   back-off heuristics are ported: there is no silent-window counter and no unfiltered probe.
 *   [OnePlusScanBudget] is still honoured, because it is also what holds scans back while the radio
 *   is lent to a pump setup, and because scanning costs battery on any phone.
 *
 * Events arrive from the central's own serial queue. Nothing here blocks on that queue: [onEvent]
 * only records what it heard and wakes whoever is waiting.
 *
 * ⚠️ ASYNC IMPACT: [awaitTarget] blocks the calling thread. Never call it from the main thread, and
 * never from the central's queue - the answer it waits for is delivered on that queue.
 */
class OnePlusBleScannerIos(
    private val central: BleCentral,
    sessionHint: OnePlusStoredSession? = null,
    /** Sensor slot owning this scanner (`prod` / `staging`) - logged so field traces are attributable. */
    private val slot: String = OnePlusLogMarkers.SLOT_PRODUCTION,
    private val nowMs: () -> Long = ::onePlusIosElapsedMs,
    private val sleep: (Long) -> Boolean = ::onePlusSleep,
) : OnePlusBleScanner {

    private val lock = AapsLock()

    private var hint: OnePlusStoredSession? = sessionHint
    private var uiListener: OnePlusScanListener? = null
    private var uiScanning = false
    private var radioOn = false
    private var pending: TargetWait? = null
    private val seen = mutableMapOf<String, OnePlusScanResult>()

    override fun startScan(listener: OnePlusScanListener) {
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) {
            OnePlusLog.e("${OnePlusLogMarkers.SCAN}: [$slot] adapter unavailable state=$state")
            return
        }
        lock.withLock {
            uiListener = listener
            uiScanning = true
            radioOn = true
            seen.clear()
        }
        // The screen scans on the main thread: book the quota slot but never wait for one. The
        // reserve OnePlusScanBudget keeps is what makes that always affordable.
        OnePlusScanBudget.record(nowMs())
        central.startScan()
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: [$slot] started")
    }

    override fun stopScan() {
        val keepRadio = lock.withLock {
            uiListener = null
            uiScanning = false
            pending != null
        }
        if (keepRadio) {
            OnePlusLog.i(
                "${OnePlusLogMarkers.SCAN}: [$slot] stopped for the screen - radio stays on for a pre-connect wait",
            )
            return
        }
        lock.withLock { radioOn = false }
        central.stopScan()
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: [$slot] stopped")
    }

    override fun isScanning(): Boolean = lock.withLock { uiScanning }

    override fun updateSessionHint(session: OnePlusStoredSession?) {
        lock.withLock { hint = session }
    }

    override fun awaitTarget(address: String, timeoutMs: Long): OnePlusAdvWaitResult {
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) {
            OnePlusLog.e("${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget adapter unavailable state=$state")
            return OnePlusAdvWaitResult()
        }
        val wait = TargetWait(normalizeTargetAddress(address))
        lock.withLock { pending = wait }
        awaitScanBudgetSlot()
        try {
            lock.withLock { radioOn = true }
            // Started again on purpose: CoreBluetooth reports each peripheral once per scan, so a
            // sensor already heard in the screen's scan would never be reported into this window.
            // Filtered by the Dexcom advertisement service: with the phone locked, iOS reports
            // nothing to a scan without a service filter, and this wait is what every reading
            // after the first one goes through.
            central.startScan(listOf(OnePlusBluetoothUuids.Advertisement))
            OnePlusLog.i(
                "${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget started " +
                    "id=***${wait.target.takeLast(5)} timeoutMs=$timeoutMs",
            )
            runBlocking {
                withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { wait.found.await() }
            }
            return OnePlusAdvWaitResult(target = wait.targetResult(), foreign = wait.foreignList())
        } finally {
            val keepRadio = lock.withLock {
                pending = null
                uiScanning
            }
            if (!keepRadio) {
                lock.withLock { radioOn = false }
                central.stopScan()
            } else {
                // The screen is still scanning: give it back its scan without a filter.
                central.startScan()
            }
            OnePlusLog.i("${OnePlusLogMarkers.SCAN}: [$slot] awaitTarget stopped")
        }
    }

    /**
     * Feed central events here.
     *
     * The owner wires `central.events = scanner::onEvent`; tests call it directly. Only
     * [BleCentralEvent.ScanResult] and a scan refusal change what a scanner knows.
     */
    fun onEvent(event: BleCentralEvent) {
        when (event) {
            is BleCentralEvent.ScanResult        -> handle(event)
            is BleCentralEvent.ScanFailed        ->
                OnePlusLog.e("${OnePlusLogMarkers.SCAN}: [$slot] scan refused: ${event.message}")

            is BleCentralEvent.Restored          -> onRestored(event)

            // A link and its traffic belong to OnePlusGattClientIos, and the adapter state is read
            // from [BleCentral.adapterState] where it decides something. Written out one by one
            // instead of an `else`, so the next event added to the sealed class has to be looked
            // at here rather than swallowed.
            is BleCentralEvent.AdapterState,
            is BleCentralEvent.OperationFailed,
            is BleCentralEvent.Link,
            is BleCentralEvent.Services,
            is BleCentralEvent.Characteristics,
            is BleCentralEvent.Written,
            is BleCentralEvent.Value,
            is BleCentralEvent.DescriptorWritten -> Unit
        }
    }

    /**
     * A restored identifier is **not** a sighting and is never recorded as one.
     *
     * iOS hands back the peripherals it kept when it ended the app, including ones it was only
     * still trying to connect to. That says the app was started again. It says nothing about the
     * sensor radiating now, and a ONE+ is asleep between its radio windows for most of every five
     * minutes.
     *
     * `OnePlusBleSessionCyclePolicy.allowConnection` refuses a connect in restored-session mode
     * until a fresh advertisement is in hand, exactly so that the driver does not connect into a
     * sleeping sensor. Feeding a restored id into the pending wait would buy that connect with
     * evidence that does not exist: the wait would end, the connect would be allowed, and the
     * attempt would burn a radio window and a retry on a sensor that is not listening. So this
     * records nothing and ends no wait.
     *
     * It is still logged, and the log names the waited-for id when it is in the list, because
     * "iOS kept this sensor and we still have not heard it" is worth seeing in a field trace.
     */
    private fun onRestored(event: BleCentralEvent.Restored) {
        OnePlusLog.i(
            "${OnePlusLogMarkers.SCAN}: [$slot] iOS relaunch handed back ${event.ids.size} " +
                "peripheral(s); a restored id is not an advertisement so none of them counts as a sighting",
        )
        val target = lock.withLock { pending }?.target ?: return
        if (event.ids.any { it.uppercase() == target }) {
            OnePlusLog.i(
                "${OnePlusLogMarkers.SCAN}: [$slot] the restored list names ***${target.takeLast(5)}, " +
                    "the id this wait is for; the wait goes on until the sensor really advertises",
            )
        }
    }

    private fun handle(event: BleCentralEvent.ScanResult) {
        val address = event.id
        val name = event.name
        val result = OnePlusScanResult(address = address, name = name, rssi = RSSI_UNKNOWN)
            .apply { seenElapsedMs = nowMs() }
        offerToPendingWait(address, name, result)
        offerToScreen(address, name, event, result)
    }

    /**
     * The pre-connect wait is served first and always, even when the screen is not scanning.
     *
     * Deliberately not filtered the way the screen list is: the sensor we are waiting for is known
     * by its identifier, and any other G7-family transmitter heard here is the evidence that makes
     * a stale stored identifier recognisable.
     */
    private fun offerToPendingWait(address: String, name: String?, result: OnePlusScanResult) {
        val wait = lock.withLock { pending } ?: return
        if (address.uppercase() == wait.target) {
            wait.recordTarget(result)
            return
        }
        if (isG7FamilyAdvertisement(name)) wait.recordForeign(address.uppercase(), result)
    }

    private fun offerToScreen(
        address: String,
        name: String?,
        event: BleCentralEvent.ScanResult,
        result: OnePlusScanResult,
    ) {
        val listener = lock.withLock { uiListener } ?: return
        val currentHint = lock.withLock { hint }
        val hasAdvUuid = event.serviceUuids.any {
            it.equals(OnePlusBluetoothUuids.Advertisement, ignoreCase = true)
        }
        val candidate = OnePlusAdvCandidate.isCandidate(name, address, currentHint)
        val g6MarketingName = name?.startsWith("Dexcom", ignoreCase = true) == true
        // FEBC alone is OK for nameless G7 ADVs; reject FEBC+Dexcom* (G6 companions).
        val febcOk = hasAdvUuid && !g6MarketingName
        if (!candidate && !febcOk && !nameMatches(name)) return
        // With a sticky session, drop weak unrelated FEBC noise unless the name or the id matches.
        val sticky = !currentHint?.lastMac.isNullOrBlank() || !currentHint?.lastDeviceName.isNullOrBlank()
        if (sticky && !candidate && febcOk && !nameMatches(name)) return

        val changed = lock.withLock {
            val previous = seen.put(address, result)
            previous == null || previous.name != result.name
        }
        if (!changed) return
        val score = OnePlusAdvCandidate.rankScore(name, address, result.rssi, currentHint)
        OnePlusLog.d(
            "${OnePlusLogMarkers.SCAN}: [$slot] device name=${name ?: "?"} rssi=${result.rssi} " +
                "score=$score id=***${address.takeLast(5)}",
        )
        listener.onDevice(result)
    }

    /**
     * Hold this thread until [OnePlusScanBudget] grants a start slot, so a pump setup that lent the
     * radio out is respected and two slots cannot start their scans together. Returns with the slot
     * already booked.
     */
    private fun awaitScanBudgetSlot() {
        var logged = false
        while (true) {
            val waitMs = OnePlusScanBudget.reserve(nowMs())
            if (waitMs <= 0L) return
            if (!logged) {
                OnePlusLog.i(
                    "${OnePlusLogMarkers.SCAN}: [$slot] scan budget full - deferring startScan ${waitMs}ms",
                )
                logged = true
            }
            if (!sleep(waitMs.coerceAtMost(OnePlusScanBudget.MAX_SLEEP_SLICE_MS))) return
        }
    }

    /** One pre-connect wait: the sensor we want, and every other ONE+ heard while we waited. */
    private class TargetWait(val target: String) {

        val found = CompletableDeferred<Unit>()
        private val lock = AapsLock()
        private var targetHit: OnePlusScanResult? = null
        private val others = mutableMapOf<String, OnePlusScanResult>()

        fun targetResult(): OnePlusScanResult? = lock.withLock { targetHit }

        fun recordTarget(result: OnePlusScanResult) {
            val first = lock.withLock {
                if (targetHit == null) {
                    targetHit = result
                    true
                } else {
                    false
                }
            }
            if (first) found.complete(Unit)
        }

        fun recordForeign(address: String, result: OnePlusScanResult) {
            lock.withLock { others[address] = result }
        }

        fun foreignList(): List<OnePlusScanResult> = lock.withLock { others.values.toList() }
    }

    companion object {

        /**
         * The RSSI of a hit, which iOS does not give us here.
         *
         * `BleCentralEvent.ScanResult` carries no signal strength. It is used for ranking only, and
         * every ranking rule in [OnePlusAdvCandidate] is a boost rather than a filter, so a fixed
         * value changes the order of two equally matching sensors and nothing else. It is NOT
         * reported as a real measurement: a screen that shows bars must treat this as unknown.
         */
        const val RSSI_UNKNOWN = 0

        fun nameMatches(name: String?): Boolean = OnePlusAdvCandidate.nameMatchesSoft(name)

        internal fun normalizeTargetAddress(address: String): String = address.uppercase()

        /**
         * A FEBC advertiser that is a G7-family transmitter: either a nameless ADV (G7 / ONE+ often
         * advertise without a local name) or a `DXCM` / `DX0x` name - never a G6 `Dexcom*` phone
         * companion.
         */
        internal fun isG7FamilyAdvertisement(name: String?): Boolean = when {
            name.isNullOrBlank()                         -> true
            name.startsWith("Dexcom", ignoreCase = true) -> false
            else                                         -> nameMatches(name)
        }
    }
}
