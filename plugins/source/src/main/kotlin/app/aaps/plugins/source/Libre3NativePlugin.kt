package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.ble.BleRadioPriority
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
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withActivity
import app.aaps.core.ui.compose.icons.IcPluginByoda
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.Libre3GlucoseSample
import app.aaps.plugins.libre3.Libre3GlucoseWatcher
import app.aaps.plugins.libre3.Libre3LogMarkers
import app.aaps.plugins.libre3.Libre3WarmupState
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.libre3.nfc.Libre3NfcSession
import app.aaps.plugins.libre3.session.Libre3DisconnectPolicy
import app.aaps.plugins.libre3.warmup.Libre3WarmupClock
import app.aaps.plugins.source.activities.Libre3StartActivity
import app.aaps.plugins.source.activities.Libre3StatusActivity
import app.aaps.plugins.source.activities.Libre3WarmupActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.Libre3BooleanKey
import app.aaps.plugins.source.keys.Libre3IntentKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Libre 3 and Libre 3 Plus BG source, driven by an in process BLE driver.
 *
 * The stub driver is the default. The real driver is only reached when the engineering switch
 * [Libre3BooleanKey.UseRealSkeleton] is on, and that switch is off by default. Until the user
 * confirms the native driver on a real sensor, Libre 3 through Juggluco or xDrip stays the
 * production path.
 *
 * See docs/LIBRE3_NATIVE_AGENT_PLAN.md.
 */
@Singleton
class Libre3NativePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val availabilityProvider: Libre3AvailabilityProvider,
    private val bleRadioPriority: BleRadioPriority,
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent {
            BgSourceComposeContent(
                title = rh.gs(R.string.libre3_native),
            )
        }
        .icon(IcPluginByoda)
        .pluginName(R.string.libre3_native)
        .shortName(R.string.libre3_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_libre3_native),
    ownPreferences = listOf(
        Libre3IntentKey::class.java,
        Libre3BooleanKey::class.java,
    ),
    aapsLogger,
    rh,
    preferences,
    config,
), BgSource, Libre3GlucoseWatcher, CgmSensorStatusProvider {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The last resort that brings a sensor back.
     *
     * The driver has its own ladder of retries and it is the one that should do the work. This is
     * the safety net for the case where that ladder itself stops, whatever the reason: as long as a
     * sensor is stored and the session is down, the plugin asks for a connection again. Without it
     * the only way back is a hand held over the sensor, which is what the log of 2026-08-22 shows.
     */
    private var reconnectWatchdog: Job? = null

    /** Watches who owns the radio, so the driver backs off while a pump setup runs. */
    private var radioLeaseWatcher: Job? = null

    private val driver
        get() = Libre3CgmDrivers.default()

    /**
     * The driver's own store. The plugin reads two things from it: how far ingest had got before
     * the last restart, and where to write that mark again after every insert.
     */
    private val sensorStore by lazy { Libre3SensorStore(context) }

    /** The status bar message, so the user can leave the warm-up screen and still see progress. */
    private val warmupNotification by lazy { Libre3WarmupNotification(context) }

    @Volatile
    private var warmupPhase: Libre3WarmupState.Phase = Libre3WarmupState.Phase.IDLE

    private val _warmup = MutableStateFlow(Libre3WarmupState(phase = Libre3WarmupState.Phase.IDLE))

    /** Live warm-up and session state of the native driver, read by the status and warm-up screens. */
    val warmup: StateFlow<Libre3WarmupState> = _warmup.asStateFlow()

    /**
     * Warm-up view for the dashboard, built from the one source of truth [_warmup].
     *
     * It is null while there is nothing to show, which is what the dashboard expects.
     */
    override val warmupStatus: StateFlow<CgmWarmupStatus?> =
        _warmup
            .map { Libre3WarmupMapper.toCgmWarmupStatus(it) }
            .stateIn(ioScope, SharingStarted.Eagerly, Libre3WarmupMapper.toCgmWarmupStatus(_warmup.value))

    private val _lifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
    override val lifecycle: StateFlow<CgmSensorLifecycle?> = _lifecycle.asStateFlow()

    // ---- Staging (pre-soak) is out of scope for v1 ----
    //
    // The dashboard reads this surface generically, so the flows must exist, but this driver never
    // runs a second sensor. They stay empty, and promotion is always refused.

    private val _stagingWarmup = MutableStateFlow<CgmWarmupStatus?>(null)
    override val stagingWarmupStatus: StateFlow<CgmWarmupStatus?> = _stagingWarmup.asStateFlow()

    private val _stagingLifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
    override val stagingLifecycle: StateFlow<CgmSensorLifecycle?> = _stagingLifecycle.asStateFlow()

    private val _stagingState = MutableStateFlow(StagingState.ABSENT)
    override val stagingState: StateFlow<StagingState> = _stagingState.asStateFlow()

    private val _stagingEvidence = MutableStateFlow<CgmStagingEvidence?>(null)
    override val stagingEvidence: StateFlow<CgmStagingEvidence?> = _stagingEvidence.asStateFlow()

    override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult =
        PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)

    /**
     * Libre 3 native is only offered when the engineering marker file is present in the AAPS
     * `extra` directory. See [Libre3AvailabilityProvider], the only place that decides this.
     *
     * `showInList` is the project's own availability mechanism: it is what
     * [app.aaps.core.interfaces.plugin.ActivePlugin.getSpecificPluginsVisibleInList] filters on, so
     * hiding here removes the plugin from Config Builder, the Setup Wizard, search and Quick Launch
     * at the same time.
     *
     * On purpose this is **not** wired into `specialEnableCondition`: a plugin that is already
     * selected must keep feeding glucose exactly as before.
     */
    override fun specialShowInListCondition(): Boolean = availabilityProvider.isAvailable()

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "libre3_settings",
        titleResId = R.string.libre3_native,
        summaryResId = R.string.libre3_plugin_summary,
        items = listOf(
            Libre3IntentKey.Status.withActivity(Libre3StatusActivity::class.java),
            Libre3IntentKey.Start.withActivity(Libre3StartActivity::class.java),
            Libre3IntentKey.Warmup.withActivity(Libre3WarmupActivity::class.java),
            Libre3BooleanKey.UseRealSkeleton,
            // The sensor age on the dashboard and the calibration session both come from the
            // SENSOR_CHANGE therapy event written by `logSensorChangeOnce`.
            BooleanKey.BgSourceCreateSensorChange,
        ),
        icon = pluginDescription.icon,
    )

    override suspend fun onStart() {
        super.onStart()
        syncDriverFromPrefs()
        // Rebuild what ingest already knows BEFORE a reconnect can deliver anything. Without this,
        // an app restart would offer readings that are already in the database, and the loop treats
        // a repeated reading as an error.
        val recentTimestamps = try {
            persistenceLayer
                .getBgReadingsDataFromTime(System.currentTimeMillis() - INGEST_SEED_WINDOW_MS, ascending = true)
                .filter { it.sourceSensor == SourceSensor.LIBRE_3_NATIVE }
                .map { it.timestamp }
        } catch (t: Throwable) {
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.BG}: could not read back stored readings, ${t.message}", t)
            emptyList()
        }
        Libre3Ingest.seed(sensorStore.loadLastLifeCount(), recentTimestamps)
        warmupPhase = driver.warmupState().phase
        // A message that survived a restart is brought back in line with what the driver really
        // says, otherwise a stale countdown could sit in the status bar for ever.
        warmupNotification.update(driver.warmupState())
        aapsLogger.info(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.SESSION}: plugin start realDriver=${Libre3CgmDrivers.useRealSkeleton} " +
                "lastLifeCount=${sensorStore.loadLastLifeCount()} storedReadings=${recentTimestamps.size}",
        )
        watchRadioLease()
        sensorStore.loadIdentity()?.let { identity ->
            connectStoredSensor(identity.bleAddress)
        }
    }

    /**
     * Gives the radio up while a pump setup holds it, and comes back when it is free.
     *
     * The link is kept and only its share of the radio is made smaller, so readings keep arriving
     * through a pump change. The reconnect below is for the one case where the link had already
     * gone before the lease was taken: the driver was held off the air while it was lent out, so
     * somebody has to ask again once it is not.
     */
    private fun watchRadioLease() {
        radioLeaseWatcher?.cancel()
        radioLeaseWatcher = ioScope.launch {
            var wasLentOut = false
            bleRadioPriority.owner.collect { owner ->
                val lentOut = owner != null
                aapsLogger.info(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.SESSION}: radio lease owner=$owner, backing off=$lentOut",
                )
                driver.setRadioBackOff(lentOut)
                // Only a lease that has just ended needs a session asked for again. The first value
                // of the flow is the state as it already is, and onStart connects for that one, so
                // reacting to it here as well would ask for two sessions at start up.
                if (wasLentOut && !lentOut && !driver.isSessionUp()) {
                    sensorStore.loadIdentity()?.let { connectStoredSensor(it.bleAddress) }
                }
                wasLentOut = lentOut
            }
        }
    }

    /**
     * Called when the user starts a different sensor.
     *
     * A new sensor counts its own minutes from zero. The mark left by the old sensor is much
     * higher, so without this every reading of the new sensor would be refused as "already seen"
     * for its whole life, and the loop would quietly get nothing at all.
     */
    fun onSensorChanged() {
        Libre3Ingest.reset()
        // The scan has already stored when this sensor was started, so the sensor change can be
        // written now instead of waiting for the first reading an hour later. That matters for the
        // calibration plugin: its own warm-up window is counted from this event, so anchoring it on
        // the real start means the user may calibrate as soon as the sensor is really settled.
        logSensorChangeOnce(sensorStore.loadIdentity()?.activatedAtMs ?: Libre3NfcSession.UNKNOWN_ACTIVATION_TIME)
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: new sensor, ingest starts counting again")
    }

    /**
     * Writes the `SENSOR_CHANGE` therapy event of the running sensor, once per sensor.
     *
     * Two things read that event, and both were left empty by this source until now: the sensor age
     * on the dashboard, and the calibration plugin, which refuses to fit anything without a session
     * to fit it in. [Libre3SensorChange] holds the rule and keeps it unique per sensor; this method
     * only carries it out. It follows [BooleanKey.BgSourceCreateSensorChange], like every other
     * source, and the check comes first so switching the setting on later still writes the event.
     *
     * Called on every accepted reading as well as after a scan, so a sensor that was started by an
     * older build is repaired by itself. The database refuses a second event with the same moment,
     * so the worst a repeat can cost is one insert that changes nothing.
     *
     * @param activatedAtMs when the sensor was started, in phone time; zero when it is not known.
     */
    private fun logSensorChangeOnce(activatedAtMs: Long) {
        if (activatedAtMs <= Libre3NfcSession.UNKNOWN_ACTIVATION_TIME) return
        if (!preferences.get(BooleanKey.BgSourceCreateSensorChange)) return
        ioScope.launch {
            val serial = Libre3SensorChange.serialToLog(
                loggedSerial = sensorStore.loadSensorChangeLoggedSerial(),
                serialNumber = sensorStore.loadIdentity()?.serialNumber,
                activatedAtMs = activatedAtMs,
                nowMs = System.currentTimeMillis(),
            ) ?: return@launch
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Libre3Native,
                emptyList(),
                emptyList(),
                sensorInsertionTime = activatedAtMs,
            )
            // Marked only after the event really reached the database, so a failure in between
            // leaves the sensor without a mark and the next reading tries again.
            sensorStore.saveSensorChangeLoggedSerial(serial)
            aapsLogger.info(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.SESSION}: sensor change written activatedAtMs=$activatedAtMs " +
                    "inserted=${result.sensorInsertionsInserted.size}",
            )
        }
    }

    /**
     * Starts Bluetooth after the NFC step has stored the sensor, or when the plugin comes back
     * with a sensor that is already stored.
     *
     * Nothing is sent when the real driver is not selected. The stub would only report a fake
     * failure and hide the fact that the engineering switch is still off.
     */
    fun connectStoredSensor(deviceAddress: String) {
        syncDriverFromPrefs()
        val blocked = Libre3CgmDrivers.realDriverBlockedReason()
        if (blocked != null) {
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: BLE not started, $blocked")
            return
        }
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: BLE connect requested")
        driver.connect(deviceAddress)
    }

    override suspend fun onStop() {
        radioLeaseWatcher?.cancel()
        radioLeaseWatcher = null
        cancelReconnectWatchdog()
        driver.removeWatcher(this)
        driver.shutdown()
        warmupNotification.cancel()
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: plugin stop")
        super.onStop()
    }

    /**
     * Keep the stub or real choice in step with the engineering switch, and make sure this plugin
     * watches the driver that is really active. Safe to call from the Start screen before connect.
     */
    fun syncDriverFromPrefs() {
        val wantReal = preferences.get(Libre3BooleanKey.UseRealSkeleton)
        val selected = Libre3CgmDrivers.select(useReal = wantReal, watcher = this)
        selected.setContext(context)
    }

    override fun onWarmup(state: Libre3WarmupState) {
        warmupPhase = state.phase
        _warmup.value = state
        warmupNotification.update(state)
        aapsLogger.info(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.WARMUP}: phase=${state.phase} remainingMs=${state.remainingMs} msg=${state.message}",
        )
    }

    /**
     * ⚠️ ASYNC IMPACT: the real driver calls this from its BLE executor thread. The mapping is
     * cheap and stays here, but [PersistenceLayer.insertCgmSourceData] runs on [ioScope], so the
     * BLE thread is never blocked by database work.
     */
    override fun onGlucose(sample: Libre3GlucoseSample) {
        if (Libre3Ingest.isWarmupBlockingIngest(warmupPhase)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.BG}: ignored during warm-up ${sample.mgdl.toInt()} @${sample.timestampMs}",
            )
            return
        }
        if (!Libre3Ingest.shouldAccept(sample)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.BG}: repeated reading dropped ${sample.mgdl.toInt()} lifeCount=${sample.lifeCount}",
            )
            return
        }
        // Self-healing net for a sensor that was started before this build, or whose scan happened
        // while the setting was off. The sensor's own minute counter is the honest start: the
        // reading time is built from it, so this gives back exactly the stored activation moment.
        logSensorChangeOnce(Libre3WarmupClock.activationTimeFromReading(sample.timestampMs, sample.lifeCount))
        val glucoseValues = listOf(Libre3Ingest.mapToGv(sample))
        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Libre3Native,
                glucoseValues,
                emptyList(),
                sensorInsertionTime = null,
            )
            aapsLogger.info(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.BG}: insert done, inserted=${result.inserted.size} updated=${result.updated.size}",
            )
            // Write the mark only after the reading really reached the database, so a crash in
            // between loses nothing. The guard's own highest value is written, not this sample's:
            // two inserts that overlap could otherwise store the lower of the two.
            sensorStore.saveLastLifeCount(Libre3Ingest.lastAcceptedLifeCount())
        }
    }

    override fun onSession(up: Boolean, reason: String?) {
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: up=$up reason=$reason")
        // Only a link that died on its own deserves the net. Every other reason is somebody asking
        // for the session to end, and asking for it again a few minutes later is not a safety net,
        // it is a bug: it would undo a plugin switch, and it would take the radio back from a pump
        // setup in the middle of the setup.
        when {
            up                                                     -> cancelReconnectWatchdog()
            reason == Libre3DisconnectPolicy.Reason.LINK_LOST.name -> armReconnectWatchdog()
            else                                                  -> cancelReconnectWatchdog()
        }
    }

    /**
     * Asks for a connection again when the session has been down for a while.
     *
     * One watch at a time: a new one replaces the old, so a session that goes up and down does not
     * leave a queue of them behind. It does nothing when the driver has already brought the session
     * back by itself, which is the normal case.
     */
    private fun armReconnectWatchdog() {
        reconnectWatchdog?.cancel()
        reconnectWatchdog = ioScope.launch {
            delay(RECONNECT_WATCHDOG_MS)
            if (driver.isSessionUp()) return@launch
            val identity = sensorStore.loadIdentity() ?: return@launch
            aapsLogger.info(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.SESSION}: session still down after ${RECONNECT_WATCHDOG_MS / 60_000} min, asking again",
            )
            connectStoredSensor(identity.bleAddress)
        }
    }

    private fun cancelReconnectWatchdog() {
        reconnectWatchdog?.cancel()
        reconnectWatchdog = null
    }

    override fun onError(message: String, fatal: Boolean) {
        aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.ERROR}: fatal=$fatal $message")
    }

    companion object {

        /** How far back stored readings are read to rebuild the repeat guard after a restart. */
        private const val INGEST_SEED_WINDOW_MS = 6L * 60L * 60L * 1000L

        /**
         * How long a session may stay down before the plugin asks for a connection itself.
         *
         * Long enough that the driver's own ladder has had every chance first, short enough that a
         * user is not left without glucose for a quarter of an hour.
         */
        private const val RECONNECT_WATCHDOG_MS = 5L * 60L * 1000L
    }
}
