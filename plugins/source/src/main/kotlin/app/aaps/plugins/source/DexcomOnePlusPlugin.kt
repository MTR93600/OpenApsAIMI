package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withActivity
import app.aaps.core.ui.compose.icons.IcPluginByoda
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDriverReal
import app.aaps.plugins.source.dexcomoneplus.OnePlusGlucoseSample
import app.aaps.plugins.source.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.source.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.source.activities.DexcomOnePlusStartActivity
import app.aaps.plugins.source.activities.DexcomOnePlusStatusActivity
import app.aaps.plugins.source.activities.DexcomOnePlusWarmupActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.plugins.source.keys.DexcomOnePlusIntentKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Dexcom ONE+ BG source (in-process BLE driver).
 *
 * Default driver is Stub; engineering pref can select Real skeleton (still fail-closed).
 * See docs/ONEPLUS_G7_MIGRATION.md.
 */
@Singleton
class DexcomOnePlusPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val warmupBasalGuard: DexcomOnePlusWarmupBasalGuard,
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent {
            BgSourceComposeContent(
                title = rh.gs(R.string.dexcom_oneplus_native),
            )
        }
        .icon(IcPluginByoda)
        .pluginName(R.string.dexcom_oneplus_native)
        .shortName(R.string.dexcom_oneplus_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_dexcom_oneplus_native),
    ownPreferences = listOf(
        DexcomOnePlusIntentKey::class.java,
        DexcomOnePlusBooleanKey::class.java,
    ),
    aapsLogger,
    rh,
    preferences,
    config,
), BgSource, OnePlusGlucoseWatcher, CgmSensorStatusProvider {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val driver
        get() = OnePlusCgmDrivers.default()

    /** Dedicated STAGING (pre-soak) driver instance — its own BLE session / store, see plan §4. */
    private val stagingDriver: OnePlusCgmDriverReal
        get() = OnePlusCgmDrivers.staging()

    @Volatile
    private var warmupPhase: OnePlusWarmupState.Phase = OnePlusWarmupState.Phase.IDLE

    private val _warmup = MutableStateFlow(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.IDLE))

    /** Live warm-up / session status of the native driver — feeds the ongoing notification and the dashboard ring. */
    val warmup: StateFlow<OnePlusWarmupState> = _warmup.asStateFlow()

    /**
     * Generic [CgmWarmupProvider] view derived from the single source of truth [_warmup].
     * null while nothing is warming up / (re)connecting (READY / IDLE / FAILED).
     */
    override val warmupStatus: StateFlow<CgmWarmupStatus?> =
        _warmup
            .map { DexcomOnePlusWarmupMapper.toCgmWarmupStatus(it) }
            .stateIn(ioScope, SharingStarted.Eagerly, DexcomOnePlusWarmupMapper.toCgmWarmupStatus(_warmup.value))

    private val warmupNotification by lazy { DexcomOnePlusWarmupNotification(context) }

    /** Private SharedPreferences-backed sensor store — used here only to persist/read the ingest
     *  high-water mark so restarts/updates don't re-insert duplicate readings. */
    private val sensorStore by lazy { OnePlusSensorStore(context) }

    /** Namespaced store for the STAGING sensor (identity / markers / session start kept separate). */
    private val stagingStore by lazy { OnePlusSensorStore(context, OnePlusCgmDrivers.STAGING_NAMESPACE) }

    /** Collector that drives the safety basal guard from the warm-up state (cancelled in onStop). */
    private var warmupGuardJob: Job? = null

    // ---- Dual-sensor (staging / pre-soak) state — see docs/ONEPLUS_G7_MIGRATION.md ----

    private val _lifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
    override val lifecycle: StateFlow<CgmSensorLifecycle?> = _lifecycle.asStateFlow()

    private val _stagingWarmup = MutableStateFlow<CgmWarmupStatus?>(null)
    override val stagingWarmupStatus: StateFlow<CgmWarmupStatus?> = _stagingWarmup.asStateFlow()

    private val _stagingLifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
    override val stagingLifecycle: StateFlow<CgmSensorLifecycle?> = _stagingLifecycle.asStateFlow()

    private val _stagingState = MutableStateFlow(StagingState.ABSENT)
    override val stagingState: StateFlow<StagingState> = _stagingState.asStateFlow()

    private val _stagingEvidence = MutableStateFlow<CgmStagingEvidence?>(null)
    override val stagingEvidence: StateFlow<CgmStagingEvidence?> = _stagingEvidence.asStateFlow()

    private val _stagingSettleRemainingMs = MutableStateFlow<Long?>(null)
    override val stagingSettleRemainingMs: StateFlow<Long?> = _stagingSettleRemainingMs.asStateFlow()

    /** A staging sensor session exists (warming/settling), collect-only until promoted. */
    @Volatile private var stagingPresent = false

    /** Staging sensor is still warming up / (re)connecting (no glucose yet). */
    @Volatile private var stagingWarming = false

    /** Latched once the staging sensor has left warm-up; survives restarts through the slot store. */
    @Volatile private var stagingWarmupDone = false

    /** Count of valid staging EGVs collected (promotion gate). */
    @Volatile private var stagingValidEgvCount = 0

    /** Last reading collected from the staging sensor (mg/dL), for the evidence surface. */
    @Volatile private var stagingLastValueMgdl: Double? = null

    /** Timestamp of [stagingLastValueMgdl]. */
    @Volatile private var stagingLastValueAtMs: Long? = null

    /** Set true after promotion: the (formerly staging) driver now feeds the loop. */
    @Volatile private var stagingPublishesToLoop = false

    /** Recent staging readings (ts→mgdl) for QA / dashboard comparison overlay. Never published. */
    private val stagingBuffer = ArrayDeque<Pair<Long, Double>>()

    /** Watches the STAGING driver; routes glucose to a local buffer (never the loop) until promoted. */
    private val stagingWatcher = object : OnePlusGlucoseWatcher {
        override fun onWarmup(state: OnePlusWarmupState) = handleStagingWarmup(state)
        override fun onGlucose(sample: OnePlusGlucoseSample) = handleStagingGlucose(sample)
        override fun onSession(up: Boolean, reason: String?) {
            aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: session up=$up reason=$reason")
        }

        override fun onError(message: String, fatal: Boolean) {
            aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: error fatal=$fatal $message")
        }
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "dexcom_oneplus_settings",
        titleResId = R.string.dexcom_oneplus_native,
        summaryResId = R.string.dexcom_oneplus_plugin_summary,
        items = listOf(
            DexcomOnePlusIntentKey.Status.withActivity(DexcomOnePlusStatusActivity::class.java),
            DexcomOnePlusIntentKey.Start.withActivity(DexcomOnePlusStartActivity::class.java),
            DexcomOnePlusIntentKey.Warmup.withActivity(DexcomOnePlusWarmupActivity::class.java),
            DexcomOnePlusBooleanKey.UseRealSkeleton,
            // Sensor age on the dashboard comes from the SENSOR_CHANGE therapy event this writes.
            BooleanKey.BgSourceCreateSensorChange,
        ),
        icon = pluginDescription.icon,
    )

    override suspend fun onStart() {
        super.onStart()
        syncDriverFromPrefs()
        // Rehydrate the ingest dedup BEFORE any reconnect can deliver glucose, so an app update /
        // restart cannot re-insert already-stored readings (duplicates halt the loop). Sequence floor
        // from the sensor store + recent already-stored ONE+ timestamps from the DB.
        val recentTs = try {
            persistenceLayer.getBgReadingsDataFromTime(System.currentTimeMillis() - INGEST_SEED_WINDOW_MS, ascending = true)
                .filter { it.sourceSensor == SourceSensor.DEXCOM_ONEPLUS_NATIVE }
                .map { it.timestamp }
        } catch (t: Throwable) {
            aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS_BG: ingest seed query failed: ${t.message}", t)
            emptyList()
        }
        DexcomOnePlusIngest.seed(sensorStore.loadLastIngestSequence(), recentTs)
        refreshProductionLifecycle()
        val autoResumeQueued = (driver as? OnePlusCgmDriverReal)?.resumeStoredSession() == true
        val stagingResumeQueued = resumeStagingSessionIfStored()
        warmupPhase = driver.warmupState().phase
        // Reconcile a warm-up notification that survived a process restart with the driver's current
        // state — cancels it when warm-up is already READY/IDLE (otherwise nothing clears the stale
        // status-bar notification until the next onWarmup event, which may never arrive after restart).
        warmupNotification.update(driver.warmupState())
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_SESSION: plugin start " +
                "realSkeleton=${OnePlusCgmDrivers.useRealSkeleton} autoResumeQueued=$autoResumeQueued " +
                "stagingResumeQueued=$stagingResumeQueued",
        )
        // SAFETY (DRAFT — see DexcomOnePlusWarmupBasalGuard): while warm-up is active and no glucose
        // is available, revert the pump to profile basal (option b: only cancel a high residual temp).
        // Driven by the warm-up state on ioScope so it works in standby without any Activity; stops
        // forcing the moment warm-up ends (status → null), letting the normal loop reclaim dosing.
        warmupGuardJob?.cancel()
        warmupGuardJob = ioScope.launch {
            warmupStatus.collect { status ->
                if (status != null) {
                    try {
                        warmupBasalGuard.ensureProfileBasalDuringWarmup(this@DexcomOnePlusPlugin, ioScope)
                    } catch (t: Throwable) {
                        aapsLogger.error(LTag.BGSOURCE, "ONEPLUS_WARMUP_BASAL guard error: ${t.message}", t)
                    }
                }
            }
        }
    }

    override suspend fun onStop() {
        warmupGuardJob?.cancel()
        warmupGuardJob = null
        driver.removeWatcher(this)
        driver.shutdown()
        // Tear down any staging session too (independent of production).
        runCatching { stagingDriver.removeWatcher(stagingWatcher) }
        runCatching { stagingDriver.shutdown() }
        warmupNotification.cancel()
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_SESSION: plugin stop")
        super.onStop()
    }

    /**
     * Keep Stub/Real selection aligned with the engineering pref and ensure this plugin
     * watches the active driver. Safe to call from Start UI before connect.
     */
    fun syncDriverFromPrefs() {
        val wantReal = preferences.get(DexcomOnePlusBooleanKey.UseRealSkeleton)
        val selected = OnePlusCgmDrivers.select(useReal = wantReal, watcher = this)
        selected.setContext(context)
    }

    override fun onWarmup(state: OnePlusWarmupState) {
        warmupPhase = state.phase
        _warmup.value = state
        warmupNotification.update(state)
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_WARMUP: phase=${state.phase} remainingMs=${state.remainingMs} msg=${state.message}",
        )
    }

    /**
     * Watcher callback thread is not guaranteed (stub: caller thread; Real may use bleExecutor).
     *
     * ⚠️ ASYNC IMPACT: GV mapping on the callback thread; [PersistenceLayer.insertCgmSourceData]
     * runs on [ioScope] (IO). Do not block the BLE executor from this callback.
     */
    override fun onGlucose(sample: OnePlusGlucoseSample) {
        if (DexcomOnePlusIngest.isWarmupBlockingIngest(warmupPhase)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: ignored during WARMING ${sample.mgdl.toInt()} @${sample.timestampMs}",
            )
            return
        }
        // A real glucose reading means warm-up / (re)connection is over. Force READY so the lingering
        // ongoing notification is cancelled and the basal guard released, even if the driver never
        // emits an explicit READY event (the reported "notification stuck forever" case). Idempotent:
        // only fires while a non-terminal warm-up phase is still showing.
        if (warmupPhase != OnePlusWarmupState.Phase.READY && warmupPhase != OnePlusWarmupState.Phase.IDLE) {
            onWarmup(OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY))
        }
        ingestToLoop(sample, sensorStore)
    }

    /**
     * Dedup + publish a reading to the loop (`insertCgmSourceData`) and persist its ingest high-water
     * mark to [store]. Shared by the production watcher and, after promotion, the promoted staging
     * sensor. [store] is the namespaced store of whichever sensor currently feeds the loop.
     */
    private fun ingestToLoop(sample: OnePlusGlucoseSample, store: OnePlusSensorStore) {
        if (!DexcomOnePlusIngest.shouldAccept(sample)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: dedup drop ${sample.mgdl.toInt()} @${sample.timestampMs} seq=${sample.sequence}",
            )
            return
        }
        val glucoseValues = listOf(DexcomOnePlusIngest.mapToGv(sample))
        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.DexcomOnePlus,
                glucoseValues,
                emptyList(),
                sensorInsertionTime = null,
            )
            aapsLogger.info(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_BG: insert complete — inserted: ${result.inserted.size}, updated: ${result.updated.size}",
            )
            // Persist the ingest high-water mark so a restart/update can't re-insert this reading,
            // and anchor the production sensor's lifecycle (session start) on first reading.
            store.saveLastIngest(sample.sequence, sample.timestampMs)
            store.saveSessionStartIfAbsent(sample.timestampMs)
            refreshProductionLifecycle()
        }
    }

    /**
     * The user explicitly started [deviceAddress] from the Start screen: anchor the sensor age on
     * that moment and log a `SENSOR_CHANGE` therapy event.
     *
     * Without it the dashboard sensor age stayed empty for this source (nothing ever wrote the event)
     * and had to be entered by hand in Care. Anchoring on the user action rather than on the first
     * reading keeps the age honest — the first reading only arrives after the ~30 min warm-up.
     *
     * No-op when the same sensor is simply re-connected, so re-pairing a running sensor does not
     * rejuvenate its age. The therapy event itself follows [BooleanKey.BgSourceCreateSensorChange].
     *
     * @param previousMac MAC stored for the running session before this start, so a sensor anchored
     *   by an older build (session start without its owner MAC) is still seen as the same sensor.
     */
    fun onSensorSessionStarted(
        deviceAddress: String,
        previousMac: String?,
        startMs: Long = System.currentTimeMillis(),
    ) {
        if (!sensorStore.startSessionForSensor(deviceAddress, startMs, previousMac)) return
        refreshProductionLifecycle()
        logSensorChange(startMs)
    }

    /**
     * Creates the `SENSOR_CHANGE` therapy event the dashboard sensor age reads. The DB transaction
     * ignores a second event with the same timestamp, so this is safe to call again for one session.
     */
    private fun logSensorChange(startMs: Long) {
        if (!preferences.get(BooleanKey.BgSourceCreateSensorChange)) return
        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.DexcomOnePlus,
                emptyList(),
                emptyList(),
                sensorInsertionTime = startMs,
            )
            aapsLogger.info(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_SESSION: sensor change logged startMs=$startMs " +
                    "inserted=${result.sensorInsertionsInserted.size}",
            )
        }
    }

    override fun onSession(up: Boolean, reason: String?) {
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_SESSION: up=$up reason=$reason")
    }

    override fun onError(message: String, fatal: Boolean) {
        aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS_ERROR: fatal=$fatal $message")
    }

    // ---------------- Dual-sensor (staging / pre-soak) ----------------

    /**
     * Start watching the STAGING sensor (collect-only, never published to the loop). The caller (Start
     * UI) then drives scan/connect on [stagingDriverForConnect]. Safe to call while production runs.
     */
    fun beginStaging(deviceAddress: String) {
        stagingDriver.setContext(context)
        stagingDriver.addWatcher(stagingWatcher)
        stagingPublishesToLoop = false
        stagingPresent = true
        // The soak clock starts when the sensor is applied, not at its first reading (~30 min later).
        // MAC-owned, so re-running the start on the same sensor keeps its clock while a different
        // sensor restarts it — a PIN-only entry can no longer inherit a previous sensor's soak.
        // Read before the driver overwrites the slot's MAC in saveIdentity / connect.
        val previousStagingMac = stagingStore.load()?.lastMac
        val newSensor = stagingStore.startSessionForSensor(deviceAddress, System.currentTimeMillis(), previousStagingMac)
        if (newSensor) {
            // Another sensor: everything the previous soak collected is void.
            stagingWarmupDone = false
            stagingValidEgvCount = 0
            stagingLastValueMgdl = null
            stagingLastValueAtMs = null
            synchronized(stagingBuffer) { stagingBuffer.clear() }
            // Durability: a restart must be able to tell "a staging sensor is warming" from "no
            // staging sensor" — see resumeStagingSessionIfStored.
            stagingStore.saveSlotProgress(present = true, validEgvCount = 0)
            stagingStore.saveSlotWarmupDone(false)
            _stagingWarmup.value = null
        } else {
            // Same sensor started again (one extra tap on the Start screen is enough). The store keeps
            // its soak clock, so its progress must be kept too: resetting it sent a settled slot back
            // to warm-up and hid the promote button for another ~30 min for nothing.
            stagingWarmupDone = stagingStore.loadSlotWarmupDone()
            stagingValidEgvCount = stagingStore.loadSlotValidEgvCount()
            stagingStore.saveSlotProgress(present = true, validEgvCount = stagingValidEgvCount)
        }
        stagingWarming = !stagingWarmupDone
        refreshStagingLifecycle()
        refreshStagingState()
        refreshStagingEvidence()
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: begin")
    }

    /**
     * Restore a STAGING sensor after a process / plugin restart.
     *
     * Without this the dual-instance split silently lost the pre-soak sensor: [onStart] only resumed
     * the production driver, so a staged sensor that was already authenticated (identity + MAC +
     * KEKS key persisted in its own store) had **no** session recreated — it could never reconnect,
     * never leave warm-up and never reach the promotion gate, while its in-memory state reset to
     * ABSENT. Never publishes to the loop: promotion stays an explicit user action.
     */
    private fun resumeStagingSessionIfStored(): Boolean {
        if (!stagingStore.loadSlotPresent()) return false
        stagingDriver.setContext(context)
        stagingDriver.addWatcher(stagingWatcher)
        // The driver itself vetoes an incomplete stored session (no MAC / PIN / KEKS key).
        // It also returns false when a session is ALREADY running (nothing to queue) — tearing the
        // slot down there would drop a live pre-soak on a plugin disable/enable cycle.
        val alreadyRunning = stagingDriver.isSessionUp()
        val queued = stagingDriver.resumeStoredSession()
        if (!queued && !alreadyRunning) {
            // Nothing is running for this slot, so it must not be shown as a warming sensor — that
            // was the "stuck in warm-up forever" state. The store is kept intact (clearing it would
            // reset session_start and let a re-start skip the soak), so the user can simply re-run
            // the staging start from the Start screen.
            runCatching { stagingDriver.removeWatcher(stagingWatcher) }
            stagingPresent = false
            stagingWarming = false
            _stagingWarmup.value = null
            _stagingLifecycle.value = null
            _stagingState.value = StagingState.ABSENT
            aapsLogger.warn(
                LTag.BGSOURCE,
                "DEXCOM_ONEPLUS_STAGING: auto-resume skipped — stored staging session incomplete, re-run the sensor start",
            )
            return false
        }
        stagingPublishesToLoop = false
        stagingPresent = true
        stagingValidEgvCount = stagingStore.loadSlotValidEgvCount()
        // A settled sensor must not be shown as warming again after a restart — the latch is durable.
        stagingWarmupDone = stagingStore.loadSlotWarmupDone()
        stagingWarming = !stagingWarmupDone
        refreshStagingLifecycle()
        refreshStagingState()
        refreshStagingEvidence()
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_STAGING: auto-resume queued=$queued alreadyRunning=$alreadyRunning " +
                "egvCount=$stagingValidEgvCount warmupDone=$stagingWarmupDone",
        )
        return true
    }

    /** The STAGING driver instance, for the Start UI to run scan/connect against the new sensor. */
    fun stagingDriverForConnect(): OnePlusCgmDriverReal = stagingDriver

    /** Stop and discard the staging sensor (no effect on production). */
    fun cancelStaging() {
        // Never tear down a promoted sensor: once promoted, the staging driver IS the loop's source
        // (invariant I1/I5). Refuse the cancel in that case.
        if (stagingPublishesToLoop) {
            aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: cancel ignored — already promoted to production")
            return
        }
        runCatching { stagingDriver.removeWatcher(stagingWatcher) }
        runCatching { stagingDriver.disconnect() }
        runCatching { stagingDriver.shutdown() }
        // Clear the staging store so a re-started staging session gets a FRESH session start. Without
        // this, a stale session_start_ms would let a new sensor reach READY without a real 12 h soak
        // (safety-critical settle-guard bypass).
        runCatching { stagingStore.clearAll() }
        stagingPresent = false
        stagingWarming = false
        stagingWarmupDone = false
        stagingValidEgvCount = 0
        stagingLastValueMgdl = null
        stagingLastValueAtMs = null
        synchronized(stagingBuffer) { stagingBuffer.clear() }
        _stagingWarmup.value = null
        _stagingLifecycle.value = null
        _stagingEvidence.value = null
        _stagingState.value = StagingState.ABSENT
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: cancelled")
    }

    /**
     * SAFETY-CRITICAL (plan §8, pending device + maintainer/clinician gate): promote the staging
     * sensor to production — the ONLY action that changes the loop's glucose source. Guarded on
     * [StagingState.READY]. On success: stops the old production sensor, resets the ingest dedup floor
     * (new EGV sequence space), migrates the staging identity into the production store (so a restart
     * durably resumes the promoted sensor), and routes the staging driver's glucose to the loop.
     */
    override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult {
        if (!stagingPresent) return PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)
        if (stagingValidEgvCount < DexcomOnePlusStaging.STAGING_MIN_VALID_EGV)
            return PromotionResult.Rejected(PromotionRejectReason.STAGING_NO_VALID_GLUCOSE)
        // Defense-in-depth: re-verify the REAL soak time from the trusted persisted start rather than
        // trusting the cached _stagingState — a stale start (e.g. cancel/restage) must never authorise
        // an under-soaked promotion onto the loop.
        val startMs = stagingStore.loadSessionStart()
        if (startMs <= 0L) return PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED)
        if (allowEarly) {
            // The user knowingly gives up the soak (production sensor stopped early). The evidence
            // gates stay: enough valid readings, and one of them recent — never a silent sensor.
            if (!DexcomOnePlusStaging.canPromoteEarly(stagingValidEgvCount, stagingLastValueAtMs, System.currentTimeMillis()))
                return PromotionResult.Rejected(PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE)
        } else {
            if (System.currentTimeMillis() - startMs < DexcomOnePlusStaging.STAGING_MIN_SETTLE_MS)
                return PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED)
            if (_stagingState.value != StagingState.READY)
                return PromotionResult.Rejected(PromotionRejectReason.STAGING_NOT_SETTLED)
        }

        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_PROMOTE: staging → production (by user) early=$allowEarly " +
                "soakMs=${System.currentTimeMillis() - startMs} egvCount=$stagingValidEgvCount",
        )
        // The promoted sensor is a real sensor — ensure it resumes on the Real driver after a restart.
        preferences.put(DexcomOnePlusBooleanKey.UseRealSkeleton, true)
        // 1) Retire the old production sensor — stop its callbacks and BLE session (no more loop feed).
        runCatching { driver.removeWatcher(this) }
        runCatching { driver.disconnect() }
        runCatching { driver.shutdown() }
        // 2) New sensor = different EGV sequence space → reset the persistent dedup floor.
        DexcomOnePlusIngest.reset()
        // 3) Durability: migrate the staging identity into the production store so a later restart
        //    resumes the promoted sensor on the production driver. Then retire the staging store.
        stagingStore.load()?.let { sensorStore.adopt(it, startMs) }
        stagingStore.clearAll()
        // The promoted sensor becomes the loop's sensor: its age must show on the dashboard from the
        // moment it was applied (its staging start, verified above), not from the promotion.
        logSensorChange(startMs)
        // 4) In-session: the already-connected staging driver now feeds the loop (no gap).
        stagingPublishesToLoop = true
        stagingPresent = false
        stagingWarming = false
        stagingWarmupDone = false
        stagingValidEgvCount = 0
        stagingLastValueMgdl = null
        stagingLastValueAtMs = null
        synchronized(stagingBuffer) { stagingBuffer.clear() }
        _stagingWarmup.value = null
        _stagingLifecycle.value = null
        _stagingEvidence.value = null
        _stagingState.value = StagingState.ABSENT
        refreshProductionLifecycle()
        return PromotionResult.Ok
    }

    private fun handleStagingWarmup(state: OnePlusWarmupState) {
        // After promotion the staging driver behaves as production for warm-up too.
        if (stagingPublishesToLoop) {
            onWarmup(state)
            return
        }
        // Leaving warm-up is a latched EVENT, never re-derived from the live phase — see
        // [DexcomOnePlusStaging.applyWarmupPhase] for why.
        val decision = DexcomOnePlusStaging.applyWarmupPhase(
            warmupDoneBefore = stagingWarmupDone,
            readyPhase = state.phase == OnePlusWarmupState.Phase.READY,
        )
        if (decision.warmupDone) markStagingWarmupDone() else stagingWarming = decision.warming
        _stagingWarmup.value = DexcomOnePlusWarmupMapper.toCgmWarmupStatus(state)
        refreshStagingState()
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_STAGING: warmup phase=${state.phase} warmupDone=$stagingWarmupDone",
        )
    }

    /** Latch (and persist) "this staging sensor has left warm-up" — see [handleStagingWarmup]. */
    private fun markStagingWarmupDone() {
        if (stagingWarmupDone) return
        stagingWarmupDone = true
        stagingWarming = false
        stagingStore.saveSlotWarmupDone(true)
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_STAGING: warm-up done — slot is settling")
    }

    private fun handleStagingGlucose(sample: OnePlusGlucoseSample) {
        // Promoted → this sensor now feeds the loop through the shared ingest path.
        if (stagingPublishesToLoop) {
            ingestToLoop(sample, sensorStore)
            return
        }
        // Collect-only: buffer for QA / dashboard, NEVER insertCgmSourceData (invariant I2).
        synchronized(stagingBuffer) {
            stagingBuffer.addLast(sample.timestampMs to sample.mgdl)
            while (stagingBuffer.size > STAGING_BUFFER_CAP) stagingBuffer.removeFirst()
        }
        // A collected reading is proof warm-up is over, even if no READY phase was ever emitted.
        markStagingWarmupDone()
        stagingValidEgvCount++
        stagingLastValueMgdl = sample.mgdl
        stagingLastValueAtMs = sample.timestampMs
        // Survive a restart with the promotion gate intact (the count is the gate, plan §8).
        stagingStore.saveSlotProgress(present = true, validEgvCount = stagingValidEgvCount)
        refreshStagingLifecycle()
        refreshStagingState()
        refreshStagingEvidence()
        aapsLogger.debug(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_STAGING: buffered ${sample.mgdl.toInt()} count=$stagingValidEgvCount (not published)",
        )
    }

    private fun refreshProductionLifecycle() {
        _lifecycle.value =
            DexcomOnePlusStaging.computeLifecycle(SensorSlot.PRODUCTION, sensorStore.loadSessionStart(), System.currentTimeMillis())
    }

    private fun refreshStagingLifecycle() {
        _stagingLifecycle.value =
            if (stagingPresent) DexcomOnePlusStaging.computeLifecycle(SensorSlot.STAGING, stagingStore.loadSessionStart(), System.currentTimeMillis())
            else null
    }

    /**
     * Publish what the collect-only slot has actually gathered. Without it the user is asked to wait
     * ~12 h on a sensor that shows no reading anywhere, so a dead staging sensor is indistinguishable
     * from a healthy one.
     */
    private fun refreshStagingEvidence() {
        _stagingEvidence.value =
            if (!stagingPresent) null
            else CgmStagingEvidence(
                validCount = stagingValidEgvCount,
                lastValueMgdl = stagingLastValueMgdl,
                lastValueAtEpochMs = stagingLastValueAtMs,
            )
    }

    private fun refreshStagingState() {
        val sessionStartMs = stagingStore.loadSessionStart()
        val nowMs = System.currentTimeMillis()
        _stagingState.value = DexcomOnePlusStaging.computeStagingState(
            present = stagingPresent,
            warming = stagingWarming,
            sessionStartMs = sessionStartMs,
            validEgvCount = stagingValidEgvCount,
            nowMs = nowMs,
        )
        // Published so the UI can count down to the promotion gate without holding its own copy of
        // the settle rule. Null once the wait is over, so a caller cannot show a stale "ready in".
        _stagingSettleRemainingMs.value =
            if (!stagingPresent || sessionStartMs <= 0L) null
            else (DexcomOnePlusStaging.STAGING_MIN_SETTLE_MS - (nowMs - sessionStartMs)).takeIf { it > 0L }
    }

    companion object {

        /** How far back to seed the ingest dedup from the DB on start — wide enough to cover any
         *  plausible on-reconnect backfill, capped downstream by [DexcomOnePlusIngest] RECENT_CAP. */
        private const val INGEST_SEED_WINDOW_MS = 6L * 60L * 60L * 1000L

        /** Staging QA buffer cap (~24 h at 5-min cadence). */
        private const val STAGING_BUFFER_CAP = 288
    }
}
