package app.aaps.plugins.source.dexcomoneplus

import android.content.Context
import android.os.SystemClock
import app.aaps.plugins.source.dexcomoneplus.gatt.OnePlusGattClientAndroid
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.source.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.source.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusBleScannerStub
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanListener
import app.aaps.plugins.source.dexcomoneplus.scan.OnePlusScanResult
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusBleSessionSkeleton
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusConnectPrep
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusSessionAuthKeks
import app.aaps.plugins.source.dexcomoneplus.session.OnePlusSessionStart
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object OnePlusCgmDriverResumePolicy {
    fun canResume(storedSession: OnePlusStoredSession?): Boolean =
        storedSession?.lastMac?.isNotBlank() == true &&
            OnePlusSessionStart.isValidPairingCode(storedSession.identity.pin) &&
            storedSession.sharedKey?.size == SHARED_KEY_SIZE

    private const val SHARED_KEY_SIZE = 16
}

/**
 * Real driver: LE scan + GATT + KEKS + Control EGV loop.
 *
 * Still requires device A3 confirmation before any production claim.
 *
 * ⚠️ ASYNC IMPACT: scan on binder; connect/auth/EGV on [bleExecutor] (blocks that thread).
 * [disconnect] / [shutdown] call [OnePlusBleSession.stop] directly so GATT disconnect can
 * unblock [OnePlusGattClient.awaitControlNotify]. Watchers may be off main.
 */
/**
 * @param storeNamespace per-slot SharedPreferences namespace for the sensor store (null = the
 *   original single-sensor file; "staging" = the pre-soak second sensor). Lets two driver instances
 *   run concurrently without sharing identity / MAC / KEKS key / ingest markers.
 */
class OnePlusCgmDriverReal(private val storeNamespace: String? = null) : OnePlusCgmDriver {

    /** `prod` / `staging` — logged on every marker so a dual-sensor trace is attributable. */
    private val slot: String = OnePlusLogMarkers.slotOf(storeNamespace)

    private val watchers = CopyOnWriteArrayList<OnePlusGlucoseWatcher>()
    private val lifecycleLock = Any()
    private var context: Context? = null
    private var operationGeneration = 0L
    private var resumeQueued = false

    @Volatile
    private var bleExecutor: ExecutorService = newBleExecutor()

    @Volatile
    private var scanner: OnePlusBleScanner = OnePlusBleScannerStub()

    @Volatile
    private var session: OnePlusBleSession? = null

    @Volatile
    private var profile: OemDeviceProfile = DeviceProfileRegistry.GenericFallback

    @Volatile
    private var sensorStore: OnePlusSensorStore? = null

    /** Last ADV name seen for [connect] target — persisted on auth success. */
    @Volatile
    private var pendingDeviceName: String? = null

    /**
     * `SystemClock.elapsedRealtime()` of the ADV sighting handed off from the UI for the [connect]
     * target (0 = none). Lets [prepareConnect] skip the blind re-scan when the sensor was just seen.
     */
    @Volatile
    private var pendingAdvSightingElapsedMs: Long = 0L

    override fun setContext(context: Context) {
        val app = context.applicationContext
        this.context = app
        val store = OnePlusSensorStore(app, storeNamespace)
        sensorStore = store
        scanner = OnePlusBleScannerAndroid(app, sessionHint = store.load(), slot = slot)
        profile = DeviceProfileRegistry.resolve()
    }

    fun sensorStore(): OnePlusSensorStore? = sensorStore

    fun saveIdentity(identity: OnePlusSensorIdentity) {
        sensorStore?.saveIdentity(identity)
        (scanner as? OnePlusBleScannerAndroid)?.sessionHint = sensorStore?.load()
    }

    override fun addWatcher(watcher: OnePlusGlucoseWatcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher)
    }

    override fun removeWatcher(watcher: OnePlusGlucoseWatcher) {
        watchers.remove(watcher)
    }

    override fun startScan(listener: OnePlusScanListener) {
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: [$slot] start")
        scanner.startScan(listener)
    }

    override fun stopScan() {
        OnePlusLog.i("${OnePlusLogMarkers.SCAN}: [$slot] stop")
        scanner.stopScan()
    }

    override fun connect(deviceAddress: String, pairingCode: String) {
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: [$slot] connect requested (Real GATT+KEKS+EGV)",
        )
        scanner.stopScan()
        // Stop any in-flight reconnect loop (may still be targeting a previous MAC).
        val previousSession: OnePlusBleSession?
        val generation: Long
        val executor: ExecutorService
        synchronized(lifecycleLock) {
            operationGeneration++
            generation = operationGeneration
            previousSession = session
            session = null
            resumeQueued = false
            executor = bleExecutor
        }
        previousSession?.stop("superseded")
        sensorStore?.saveIdentity(
            OnePlusSensorIdentity(
                pin = pairingCode,
                serial = sensorStore?.load()?.identity?.serial,
                gtin = sensorStore?.load()?.identity?.gtin,
                rawGs1 = sensorStore?.load()?.identity?.rawGs1,
            ),
        )
        sensorStore?.saveLastMac(deviceAddress)
        // Fresh (re)pairing from the UI: drop any stale KEKS shared key so attempt 0 runs full
        // J-PAKE instead of a short-auth that the transmitter rejects (field: stale preload →
        // auth=2 → a wasted connect cycle + FAILED). A successful auth re-persists a new key, so
        // silent reconnects inside the session loop still short-auth.
        sensorStore?.clearSharedKey()
        (scanner as? OnePlusBleScannerAndroid)?.sessionHint = sensorStore?.load()
        try {
            synchronized(lifecycleLock) {
                if (generation != operationGeneration || executor !== bleExecutor) return
                executor.execute {
                    val created = synchronized(lifecycleLock) {
                        if (generation != operationGeneration) return@execute
                        createSession(
                            requestNewSensorStart = true,
                            generation = generation,
                        ).also { session = it }
                    }
                    try {
                        created.startWithPairingCode(deviceAddress, pairingCode)
                    } catch (t: Throwable) {
                        OnePlusLog.e("${OnePlusLogMarkers.ERROR}: [$slot] ${t.message}", t)
                        watchers.forEach {
                            it.onError(t.message ?: "ONEPLUS_CONNECT_FAILED", fatal = false)
                            it.onWarmup(
                                OnePlusWarmupState(
                                    phase = OnePlusWarmupState.Phase.FAILED,
                                    message = t.message,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            OnePlusLog.e(
                "${OnePlusLogMarkers.ERROR}: [$slot] connect queue ${t.message}",
                t,
            )
        }
    }

    /**
     * Restores an authenticated sensor after process/plugin recreation without clearing its KEKS
     * key and without allowing SessionStart.
     *
     * ⚠️ ASYNC IMPACT: queues at most one resume on [bleExecutor]. The resumed session itself owns
     * persistent ADV waiting and all later reconnects.
     */
    fun resumeStoredSession(): Boolean {
        val stored = sensorStore?.load()
        if (!OnePlusCgmDriverResumePolicy.canResume(stored)) {
            OnePlusLog.i(
                "${OnePlusLogMarkers.SESSION}: [$slot] auto-resume skipped — stored session incomplete",
            )
            return false
        }
        val deviceAddress = stored?.lastMac ?: return false
        val pairingCode = stored.identity.pin
        val generation: Long
        val executor: ExecutorService
        synchronized(lifecycleLock) {
            if (resumeQueued || session != null) {
                OnePlusLog.i(
                    "${OnePlusLogMarkers.SESSION}: [$slot] auto-resume already active",
                )
                return false
            }
            resumeQueued = true
            operationGeneration++
            generation = operationGeneration
            executor = bleExecutor
            // A stored device name is identity metadata, not a fresh UI ADV selection.
            pendingDeviceName = null
            pendingAdvSightingElapsedMs = 0L
            (scanner as? OnePlusBleScannerAndroid)?.sessionHint = stored
        }
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: [$slot] auto-resume queued mac=***${deviceAddress.takeLast(5)} " +
                "savedKey=true newStart=false",
        )
        try {
            synchronized(lifecycleLock) {
                if (generation != operationGeneration || executor !== bleExecutor) return false
                executor.execute {
                    val created = synchronized(lifecycleLock) {
                        if (generation != operationGeneration) return@execute
                        createSession(
                            requestNewSensorStart = false,
                            generation = generation,
                        ).also { session = it }
                    }
                    try {
                        created.startWithPairingCode(deviceAddress, pairingCode)
                    } catch (t: Throwable) {
                        synchronized(lifecycleLock) {
                            if (generation == operationGeneration && session === created) {
                                resumeQueued = false
                                session = null
                            }
                        }
                        OnePlusLog.e(
                            "${OnePlusLogMarkers.ERROR}: [$slot] auto-resume ${t.message}",
                            t,
                        )
                        watchers.forEach {
                            it.onError(t.message ?: "ONEPLUS_AUTO_RESUME_FAILED", fatal = false)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(lifecycleLock) {
                if (generation == operationGeneration) {
                    resumeQueued = false
                }
            }
            OnePlusLog.e(
                "${OnePlusLogMarkers.ERROR}: [$slot] auto-resume queue ${t.message}",
                t,
            )
            return false
        }
        return true
    }

    /**
     * UI entry point: hands off the freshest ADV [sighting] (name + `seenElapsedMs`) so
     * [prepareConnect] can connect in-window without a blind re-scan.
     */
    fun connect(
        deviceAddress: String,
        pairingCode: String,
        sighting: OnePlusScanResult?,
    ) {
        val name = sighting?.name
        pendingDeviceName = name
        pendingAdvSightingElapsedMs = sighting?.seenElapsedMs ?: 0L
        if (!name.isNullOrBlank()) {
            sensorStore?.saveLastDeviceName(name)
        }
        connect(deviceAddress, pairingCode)
    }

    override fun disconnect() {
        // Must not queue behind a blocking Control/EGV loop on bleExecutor.
        val previousSession = synchronized(lifecycleLock) {
            operationGeneration++
            resumeQueued = false
            session.also { session = null }
        }
        previousSession?.stop("disconnect")
    }

    override fun shutdown() {
        try {
            scanner.stopScan()
        } catch (_: Throwable) {
        }
        val previousSession: OnePlusBleSession?
        val previousExecutor: ExecutorService
        synchronized(lifecycleLock) {
            operationGeneration++
            resumeQueued = false
            previousSession = session
            session = null
            previousExecutor = bleExecutor
            bleExecutor = newBleExecutor()
        }
        try {
            previousSession?.stop("shutdown")
        } catch (_: Throwable) {
        }
        watchers.clear()
        previousExecutor.shutdownNow()
        OnePlusLog.i("${OnePlusLogMarkers.SESSION}: [$slot] shutdown")
    }

    override fun warmupState(): OnePlusWarmupState =
        session?.warmupState() ?: OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE)

    override fun isSessionUp(): Boolean = session?.isUp() == true

    private fun createSession(
        requestNewSensorStart: Boolean,
        generation: Long,
    ): OnePlusBleSession {
        val ctx = context ?: error("ONEPLUS_SESSION: setContext required")
        val gatt = OnePlusGattClientAndroid(ctx, profile)
        val auth = OnePlusSessionAuthKeks(gatt)
        val store = sensorStore
        val created = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            profile = profile,
            onWarmup = { state ->
                ifCurrentOperation(generation) {
                    watchers.forEach { it.onWarmup(state) }
                }
            },
            onSession = { up, reason ->
                ifCurrentOperation(generation) {
                    watchers.forEach { it.onSession(up, reason) }
                }
            },
            onError = { message, fatal ->
                ifCurrentOperation(generation) {
                    watchers.forEach { it.onError(message, fatal) }
                }
            },
            onGlucose = { sample ->
                ifCurrentOperation(generation) {
                    watchers.forEach { it.onGlucose(sample) }
                }
            },
            // Auto-resume always passes false; only an explicit new-sensor connect may start.
            requestNewSensorStart = requestNewSensorStart,
            beforeConnect = { address, attempt -> prepareConnect(address, attempt) },
            savedSharedKeyProvider = { store?.load()?.sharedKey },
            onAuthSuccess = { address, key ->
                ifCurrentOperation(generation) {
                    store?.saveLastMac(address)
                    store?.saveSharedKey(key)
                    pendingDeviceName?.let { store?.saveLastDeviceName(it) }
                    (scanner as? OnePlusBleScannerAndroid)?.sessionHint = store?.load()
                }
            },
            onAuthInvalidate = {
                ifCurrentOperation(generation) {
                    store?.clearSharedKey()
                    (scanner as? OnePlusBleScannerAndroid)?.sessionHint = store?.load()
                    OnePlusLog.i(
                        "${OnePlusLogMarkers.SESSION}: [$slot] cleared persisted KEKS shared key",
                    )
                }
            },
            appContext = ctx,
            slot = slot,
        )
        return created
    }

    private fun ifCurrentOperation(generation: Long, action: () -> Unit) {
        synchronized(lifecycleLock) {
            if (generation == operationGeneration) {
                action()
            }
        }
    }

    /**
     * Ob1 / Samsung recovery before the GATT `connect`, returning [OnePlusConnectPrep]
     * (whether a **fresh ADV** is in hand → session does a fast direct connect).
     *
     * 1. **Handoff fast-path:** if the UI (or a prior in-window rescan) saw this MAC within
     *    [ADV_HANDOFF_FRESH_MS], connect immediately — no blind re-scan. This is what removes the
     *    ~8 s wasted scan + the ~48 s autoConnect park seen in the field log.
     * 2. Otherwise LE-scan via [OnePlusBleScanner.awaitTarget] (private ScanCallback so UI
     *    [stopScan] cannot cancel it). A hit → fresh; a miss with
     *    [OemDeviceProfile.requireAdvBeforeConnect] on attempt 0 (and no UI selection) → defer.
     *
     * ⚠️ ASYNC IMPACT: blocks bleExecutor; binder delivers ADV into awaitTarget.
     */
    private fun prepareConnect(deviceAddress: String, attempt: Int): OnePlusConnectPrep {
        val target = deviceAddress.uppercase()
        val sightingAgeMs =
            if (pendingAdvSightingElapsedMs > 0L) {
                SystemClock.elapsedRealtime() - pendingAdvSightingElapsedMs
            } else {
                Long.MAX_VALUE
            }
        if (sightingAgeMs in 0..ADV_HANDOFF_FRESH_MS) {
            OnePlusLog.i(
                "${OnePlusLogMarkers.SCAN}: [$slot] pre-connect handoff — fresh ADV ${sightingAgeMs}ms old, " +
                    "skip rescan (attempt=$attempt mac=***${target.takeLast(5)})",
            )
            return OnePlusConnectPrep(advFresh = true)
        }

        val scanMs = profile.preConnectScanMs
        if (scanMs <= 0L) return OnePlusConnectPrep(advFresh = false)
        // UI Connect just selected this ADV → don't hard-fail if re-scan misses briefly.
        val uiJustSelected = !pendingDeviceName.isNullOrBlank()
        val hardRequireAdv =
            profile.requireAdvBeforeConnect && attempt == 0 && !uiJustSelected
        OnePlusLog.i(
            "${OnePlusLogMarkers.SCAN}: [$slot] pre-connect awaitTarget ${scanMs}ms attempt=$attempt " +
                "requireAdv=${profile.requireAdvBeforeConnect} hardRequire=$hardRequireAdv " +
                "uiJustSelected=$uiJustSelected",
        )
        try {
            val wait = scanner.awaitTarget(target, scanMs)
            val hit = wait.target
            if (hit != null) {
                OnePlusLog.i(
                    "${OnePlusLogMarkers.SCAN}: [$slot] pre-connect ADV name=${hit.name} " +
                        "rssi=${hit.rssi} mac=***${target.takeLast(5)}",
                )
                if (!hit.name.isNullOrBlank()) pendingDeviceName = hit.name
                return OnePlusConnectPrep(advFresh = true)
            }
            // Our MAC stayed silent. Any OTHER ONE+ heard in the same window is the signature of a
            // stale stored MAC (sensor replaced, or started into the other slot) — the session turns
            // this into a user-visible error once the silence is long enough to be conclusive.
            val foreign = wait.strongestForeign()?.let { other ->
                "name=${other.name ?: "?"} rssi=${other.rssi} mac=***${other.address.takeLast(5)}"
            }
            if (hardRequireAdv) {
                throw IllegalStateException(
                    "ONEPLUS_SCAN: ADV not seen in ${scanMs}ms — defer connect (keep screen on, sensor close)",
                )
            }
            OnePlusLog.w(
                "${OnePlusLogMarkers.SCAN}: [$slot] pre-connect ADV not seen in ${scanMs}ms " +
                    "(attempt=$attempt known MAC uiJustSelected=$uiJustSelected foreign=${foreign ?: "-"})",
            )
            return OnePlusConnectPrep(advFresh = false, foreignAdv = foreign)
        } catch (t: IllegalStateException) {
            throw t
        } catch (t: Throwable) {
            OnePlusLog.w("${OnePlusLogMarkers.SCAN}: [$slot] pre-connect ${t.message}")
            if (hardRequireAdv) {
                throw IllegalStateException(
                    "ONEPLUS_SCAN: pre-connect failed — ${t.message}",
                    t,
                )
            }
            return OnePlusConnectPrep(advFresh = false)
        }
    }

    private fun newBleExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "OnePlusBleExecutor").apply { isDaemon = true }
        }

    private companion object {
        /**
         * Max age of a handed-off ADV sighting still treated as "fresh" (skip rescan, direct
         * connect). G7/ONE+ advertises in short windows, so a sighting older than this may already
         * be quiet and a direct connect would miss the window.
         */
        const val ADV_HANDOFF_FRESH_MS = 6_000L
    }
}
