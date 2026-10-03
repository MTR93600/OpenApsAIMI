package app.aaps.plugins.source

import android.content.Context
import android.content.Intent
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
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
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withClick
import app.aaps.core.ui.compose.icons.IcPluginByoda
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.libre3.Libre3CgmDriverReal
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.Libre3GlucoseSample
import app.aaps.plugins.libre3.Libre3GlucoseWatcher
import app.aaps.plugins.libre3.Libre3LogMarkers
import app.aaps.plugins.libre3.Libre3WarmupState
import app.aaps.plugins.libre3.identity.Libre3SensorIdentity
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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Native Libre 3 and Libre 3 Plus BG source, driven by an in process BLE driver.
 *
 * The stub driver is the default. The real driver is only reached when the engineering switch
 * [Libre3BooleanKey.UseRealSkeleton] is on, and that switch is off by default. Until the user
 * confirms the native driver on a real sensor, Libre 3 through Juggluco or xDrip stays the
 * production path.
 *
 * See docs/LIBRE3_NATIVE_AGENT_PLAN.md.
 *
 * Registers itself into the plugin list. Scoped with Metro's own [SingleIn], not javax `@Singleton`.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(447)
@SingleIn(AppScope::class)
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
        .composeContent { plugin ->
            BgSourceComposeContent(
                title = rh.gs(R.string.libre3_native),
            )
        }
        .icon(IcPluginByoda)
        .pluginName(TextRef.AndroidRes(R.string.libre3_native))
        .shortName(TextRef.AndroidRes(R.string.libre3_short))
        .preferencesVisibleInSimpleMode(false)
        .description(TextRef.AndroidRes(R.string.description_source_libre3_native)),
    ownPreferences = Libre3IntentKey.entries + Libre3BooleanKey.entries,
    aapsLogger,
    rh,
    preferences,
    config,
), BgSource, Libre3GlucoseWatcher, CgmSensorStatusProvider {

    /**
     * Where every database write and every wait of this plugin runs.
     *
     * The handler is not decoration. Without one, anything thrown inside an `ioScope.launch` walks
     * up to the default handler of the process and takes the whole app down. The promotion is the
     * worst moment for that: it would leave one sensor written into both slot files and the loop
     * with no sensor at all on the next launch. A Bluetooth or database failure has to cost a log
     * line, never the app.
     */
    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.ERROR}: background work failed, ${t.message}", t)
        },
    )

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

    /** Watches the keep-alive switch, so flipping it takes effect at once. */
    private var keepAliveWatcher: Job? = null

    private val driver
        get() = Libre3CgmDrivers.default()

    /**
     * The driver's own store. The plugin reads two things from it: how far ingest had got before
     * the last restart, and where to write that mark again after every insert.
     */
    private val sensorStore by lazy { Libre3SensorStore(context) }

    /** The status bar message, so the user can leave the warm-up screen and still see progress. */
    private val warmupNotification by lazy { Libre3WarmupNotification(context) }

    /**
     * The same message for the pre-soak slot, with its own id and its own title.
     *
     * `by lazy`, so with the pre-soak switched off no second message and no second channel
     * registration is ever made.
     */
    private val stagingWarmupNotification by lazy { Libre3WarmupNotification(context, SensorSlot.STAGING) }

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

    // ---- The pre-soak slot ----
    //
    // Everything below is collect-only. It is switched off by default, and with the switch off none
    // of it is reachable: no second driver instance, no second preferences file, and the plugin
    // behaves exactly as it did before.

    private val _stagingWarmup = MutableStateFlow<CgmWarmupStatus?>(null)
    override val stagingWarmupStatus: StateFlow<CgmWarmupStatus?> = _stagingWarmup.asStateFlow()

    private val _stagingLifecycle = MutableStateFlow<CgmSensorLifecycle?>(null)
    override val stagingLifecycle: StateFlow<CgmSensorLifecycle?> = _stagingLifecycle.asStateFlow()

    private val _stagingState = MutableStateFlow(StagingState.ABSENT)
    override val stagingState: StateFlow<StagingState> = _stagingState.asStateFlow()

    private val _stagingEvidence = MutableStateFlow<CgmStagingEvidence?>(null)
    override val stagingEvidence: StateFlow<CgmStagingEvidence?> = _stagingEvidence.asStateFlow()

    private val _stagingCurve = MutableStateFlow<List<Libre3PresoakPoint>>(emptyList())

    /**
     * The pre-soak readings collected so far, newest last.
     *
     * Capped at [Libre3Staging.CURVE_CAP] and kept in memory only, so it starts empty after a
     * restart while the counters do survive.
     */
    val stagingCurve: StateFlow<List<Libre3PresoakPoint>> = _stagingCurve.asStateFlow()

    /** The pre-soak driver instance. Built on first use, and only ever by a pre-soak action. */
    private val stagingDriver: Libre3CgmDriverReal
        get() = Libre3CgmDrivers.staging()

    /**
     * The pre-soak slot's own preferences file.
     *
     * `by lazy`, so with the pre-soak switched off this file is never even opened.
     */
    private val stagingStore by lazy { Libre3SensorStore(context, Libre3CgmDrivers.STAGING_NAMESPACE) }

    /** A pre-soak sensor is running. */
    @Volatile
    private var stagingPresent = false

    /** That sensor has not left warm-up yet, so it has sent no glucose at all. */
    @Volatile
    private var stagingWarming = false

    /** Latched once the pre-soak sensor has left warm-up. Kept on disk across restarts. */
    @Volatile
    private var stagingWarmupDone = false

    /** How many good readings the pre-soak slot has collected. */
    @Volatile
    private var stagingValidReadingCount = 0

    /** Highest pre-soak life counter taken into the curve, -1 when there is none. */
    @Volatile
    private var stagingLastLifeCount = -1

    /** Last reading collected from the pre-soak sensor, for the evidence surface. */
    @Volatile
    private var stagingLastValueMgdl: Double? = null

    /** Time of [stagingLastValueMgdl]. */
    @Volatile
    private var stagingLastValueAtMs: Long? = null

    /**
     * Watches the pre-soak driver.
     *
     * Every path here is collect-only. It never touches [persistenceLayer] and it never calls
     * [Libre3Ingest].
     *
     * It is `internal` so the invariant test in this module can drive it directly.
     */
    internal val stagingWatcher: Libre3GlucoseWatcher = object : Libre3GlucoseWatcher {
        override fun onWarmup(state: Libre3WarmupState) = handleStagingWarmup(state)
        override fun onGlucose(sample: Libre3GlucoseSample) = handleStagingGlucose(sample)
        override fun onSession(up: Boolean, reason: String?) {
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: session up=$up reason=$reason")
        }

        override fun onError(message: String, fatal: Boolean) {
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: fatal=$fatal $message")
        }
    }

    /**
     * Pre-soak promotion is not in this lot, so the calibration cutoff is not written here.
     *
     * On `origin/dev_OAPSAIMI` @ `3dd0ca64772`, the success path of this function writes
     * `logSensorChangeOnce(staged.activatedAtMs)` then
     * `activePlugin.activeCalibration.ignoreEntriesBefore(System.currentTimeMillis())`
     * (`Libre3NativePlugin.kt` L1051–1055). That body is P5.3. This function still returns
     * [PromotionRejectReason.STAGING_ABSENT] for every caller, including a status-screen button,
     * and `allowEarly` is not read. `onSensorChanged` and the production glucose path date the
     * `SENSOR_CHANGE` and do not call `ignoreEntriesBefore`. `:plugins:source` does not depend on
     * `:plugins:calibration`, and `ActivePlugin` is not in this constructor.
     */
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
            Libre3IntentKey.Status.withClick {
                context.startActivity(
                    Intent(context, Libre3StatusActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            Libre3IntentKey.Start.withClick {
                context.startActivity(
                    Intent(context, Libre3StartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            Libre3IntentKey.Warmup.withClick {
                context.startActivity(
                    Intent(context, Libre3WarmupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            Libre3BooleanKey.UseRealSkeleton,
            Libre3BooleanKey.PresoakEnabled,
            Libre3BooleanKey.KeepSessionAlive,
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
        // After the production resume on purpose: production always comes first, and a pre-soak
        // that is not picked up again would soak on invisibly.
        resumeStagingSessionIfStored()
        refreshSessionService()
        watchKeepSessionAlivePreference()
    }

    /**
     * Makes the keep-alive switch take effect the moment the user flips it.
     *
     * `drop(1)` because [Preferences.observe] starts with the value as it already is, and [onStart]
     * has just acted on that one.
     */
    private fun watchKeepSessionAlivePreference() {
        keepAliveWatcher?.cancel()
        keepAliveWatcher = ioScope.launch {
            preferences.observe(Libre3BooleanKey.KeepSessionAlive).drop(1).collect { wanted ->
                aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: keep session alive switched to $wanted")
                refreshSessionService()
            }
        }
    }

    /**
     * Keep the `connectedDevice` service alive while either slot wants a Bluetooth session, and give
     * the privilege back when neither does.
     *
     * It never throws, because it is called from production paths as well as from pre-soak ones.
     * The `runCatching` is the reference `refreshSessionService` (`dev_OAPSAIMI` @ `3dd0ca64772`):
     * a failure is logged and the caller continues.
     *
     * `internal` so the status screen can call it after the user forgets a sensor.
     */
    internal fun refreshSessionService() {
        runCatching {
            if (!preferences.get(Libre3BooleanKey.KeepSessionAlive)) {
                Libre3SessionService.stop(context.applicationContext)
                return@runCatching
            }
            val wanted = sensorStore.isReadyForBle() ||
                (preferences.get(Libre3BooleanKey.PresoakEnabled) && stagingStore.isReadyForBle())
            if (wanted) Libre3SessionService.start(context.applicationContext)
            else Libre3SessionService.stop(context.applicationContext)
        }.onFailure { t ->
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: session service refresh failed, ${t.message}", t)
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
                // The pre-soak is a second link on the same radio. Only an instance that really
                // exists is asked, so a phone without a pre-soak never builds one here.
                runCatching { Libre3CgmDrivers.stagingOrNull()?.setRadioBackOff(lentOut) }
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
        keepAliveWatcher?.cancel()
        keepAliveWatcher = null
        cancelReconnectWatchdog()
        // The pre-soak link goes down with the plugin, but the pre-soak file is kept on purpose:
        // the plugin being switched off must not throw a soak of many hours away.
        Libre3CgmDrivers.releaseStagingInstance()?.let { presoak ->
            runCatching { presoak.removeWatcher(stagingWatcher) }
            runCatching { presoak.shutdown() }
        }
        driver.removeWatcher(this)
        driver.shutdown()
        warmupNotification.cancel()
        runCatching { stagingWarmupNotification.cancel() }
        Libre3SessionService.stop(context.applicationContext)
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

    // ---------------- The pre-soak slot ----------------

    /**
     * Whether that sensor is the one the pre-soak slot holds. Serial or MAC is enough.
     *
     * With the pre-soak switched off there is no pre-soak sensor, so the answer is always no and
     * the pre-soak file is not even opened.
     */
    fun isStagingSensor(serial: String?, mac: String?): Boolean {
        if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) return false
        return runCatching {
            val identity = stagingStore.loadIdentity() ?: return@runCatching false
            Libre3Staging.isSameSensor(identity.serialNumber, identity.bleAddress, serial, mac)
        }.getOrDefault(false)
    }

    /** Whether that sensor is the one that feeds the loop right now — see [isStagingSensor]. */
    fun isProductionSensor(serial: String?, mac: String?): Boolean =
        runCatching {
            val identity = sensorStore.loadIdentity() ?: return@runCatching false
            Libre3Staging.isSameSensor(identity.serialNumber, identity.bleAddress, serial, mac)
        }.getOrDefault(false)

    /**
     * Starts a pre-soak on the sensor the NFC scan has just written into the pre-soak slot.
     *
     * @return false when the request was refused, because the pre-soak is switched off or because
     *   that sensor already feeds the loop. A throw inside the slot write is also false: the
     *   `runCatching` / `getOrElse` is the reference `beginStaging` (`dev_OAPSAIMI` @ `3dd0ca64772`).
     */
    fun beginStaging(identity: Libre3SensorIdentity): Boolean {
        if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) {
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: refused, the pre-soak is switched off")
            return false
        }
        if (isProductionSensor(identity.serialNumber, identity.bleAddress)) {
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: refused, this sensor already feeds the loop")
            return false
        }
        return runCatching {
            val presoak = stagingDriver
            presoak.setContext(context)
            // The NFC scan has already written the new sensor into this file, so the stored serial
            // is the new one by now. The activation time tells a re-scan of the same sensor from a
            // different one.
            val sameSensor = identity.activatedAtMs > 0L &&
                identity.activatedAtMs == stagingStore.loadSlotActivatedAt()
            if (sameSensor) {
                stagingValidReadingCount = stagingStore.loadSlotValidReadingCount()
                stagingWarmupDone = stagingStore.loadSlotWarmupDone()
            } else {
                stagingValidReadingCount = 0
                stagingWarmupDone = false
                stagingLastValueMgdl = null
                stagingLastValueAtMs = null
                _stagingCurve.value = emptyList()
                _stagingWarmup.value = null
            }
            stagingLastLifeCount = -1
            stagingPresent = true
            stagingWarming = !stagingWarmupDone
            stagingStore.saveSlotActivatedAt(identity.activatedAtMs)
            stagingStore.saveSlotWarmupDone(stagingWarmupDone)
            stagingStore.saveSlotProgress(present = true, validReadingCount = stagingValidReadingCount)
            presoak.addWatcher(stagingWatcher)
            refreshStagingLifecycle()
            refreshStagingState()
            refreshStagingEvidence()
            refreshSessionService()
            aapsLogger.info(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.PRESOAK}: begin serial=${identity.serialNumber} sameSensor=$sameSensor " +
                    "readings=$stagingValidReadingCount warmupDone=$stagingWarmupDone",
            )
            true
        }.getOrElse { t ->
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: begin failed, ${t.message}", t)
            false
        }
    }

    /** Starts Bluetooth for the pre-soak slot. It never touches the production driver. */
    fun connectStagingSensor(deviceAddress: String) {
        if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) return
        val blocked = Libre3CgmDrivers.stagingBlockedReason()
        if (blocked != null) {
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: BLE not started, $blocked")
            return
        }
        runCatching {
            val presoak = stagingDriver
            presoak.setContext(context)
            presoak.connect(deviceAddress)
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: BLE connect requested")
        }.onFailure { t ->
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: BLE connect failed, ${t.message}", t)
        }
    }

    /**
     * Stops the pre-soak sensor and throws it away. It has no effect on production.
     *
     * The sensor itself keeps running on the arm; only this phone forgets it.
     */
    fun cancelStaging() {
        Libre3CgmDrivers.releaseStagingInstance()?.let { presoak ->
            runCatching { presoak.removeWatcher(stagingWatcher) }
            runCatching { presoak.disconnect() }
            runCatching { presoak.shutdown() }
        }
        runCatching { stagingStore.clearAll() }
        clearStagingState()
        refreshSessionService()
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: cancelled")
    }

    /**
     * Picks a pre-soak up again after a restart.
     *
     * The two refusals below come before a driver instance is built, so a slot that must not be
     * picked up never even opens a thread. A throw is a missed resume (`getOrElse` returns false),
     * the same shape as the reference `resumeStagingSessionIfStored`.
     *
     * @return true when a pre-soak was picked up again.
     */
    internal fun resumeStagingSessionIfStored(): Boolean {
        if (!preferences.get(Libre3BooleanKey.PresoakEnabled)) return false
        return runCatching {
            if (!stagingStore.loadSlotPresent()) return@runCatching false
            val identity = stagingStore.loadIdentity()
            if (identity == null) {
                runCatching { stagingStore.saveSlotProgress(present = false, validReadingCount = 0) }
                clearStagingState()
                aapsLogger.warn(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.PRESOAK}: not picked up again, the stored pre-soak sensor is incomplete, " +
                        "please start the pre-soak once more",
                )
                return@runCatching false
            }
            if (isProductionSensor(identity.serialNumber, identity.bleAddress)) {
                runCatching { stagingStore.clearAll() }
                clearStagingState()
                aapsLogger.warn(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.PRESOAK}: not picked up again, this sensor already feeds the loop; " +
                        "the leftover pre-soak slot was cleared",
                )
                return@runCatching false
            }
            stagingPresent = true
            stagingValidReadingCount = stagingStore.loadSlotValidReadingCount()
            stagingWarmupDone = stagingStore.loadSlotWarmupDone()
            stagingWarming = !stagingWarmupDone
            stagingLastLifeCount = -1
            _stagingCurve.value = emptyList()
            val presoak = stagingDriver
            presoak.setContext(context)
            presoak.addWatcher(stagingWatcher)
            refreshStagingLifecycle()
            refreshStagingState()
            refreshStagingEvidence()
            presoak.connect(identity.bleAddress)
            aapsLogger.info(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.PRESOAK}: picked up again readings=$stagingValidReadingCount " +
                    "warmupDone=$stagingWarmupDone",
            )
            true
        }.getOrElse { t ->
            aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: could not be picked up again, ${t.message}", t)
            false
        }
    }

    private fun handleStagingWarmup(state: Libre3WarmupState) {
        if (!stagingPresent) return
        val decision = Libre3Staging.applyWarmupPhase(
            warmupDoneBefore = stagingWarmupDone,
            readyPhase = state.phase == Libre3WarmupState.Phase.READY,
        )
        if (decision.warmupDone) markStagingWarmupDone() else stagingWarming = decision.warming
        _stagingWarmup.value = Libre3WarmupMapper.toCgmWarmupStatus(state)
        runCatching { stagingWarmupNotification.update(state) }
        refreshStagingState()
        aapsLogger.info(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.PRESOAK}: warmup phase=${state.phase} warmupDone=$stagingWarmupDone",
        )
    }

    /** Latches "this pre-soak sensor has left warm-up", and keeps it — see [handleStagingWarmup]. */
    private fun markStagingWarmupDone() {
        if (stagingWarmupDone) return
        stagingWarmupDone = true
        stagingWarming = false
        runCatching { stagingStore.saveSlotWarmupDone(true) }
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: warm-up done, the slot is settling")
    }

    /**
     * ⚠️ ASYNC IMPACT: the pre-soak driver calls this from its own BLE thread. Nothing here waits
     * on anything, and nothing here reaches the database.
     */
    private fun handleStagingGlucose(sample: Libre3GlucoseSample) {
        if (!stagingPresent) return
        if (!Libre3Staging.acceptForCurve(stagingLastLifeCount, sample)) return
        stagingLastLifeCount = sample.lifeCount
        _stagingCurve.value = (_stagingCurve.value + Libre3PresoakPoint(sample.timestampMs, sample.mgdl))
            .takeLast(Libre3Staging.CURVE_CAP)
        markStagingWarmupDone()
        stagingValidReadingCount++
        stagingLastValueMgdl = sample.mgdl
        stagingLastValueAtMs = sample.timestampMs
        runCatching { stagingStore.saveSlotProgress(present = true, validReadingCount = stagingValidReadingCount) }
        refreshStagingLifecycle()
        refreshStagingState()
        refreshStagingEvidence()
        aapsLogger.debug(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.PRESOAK}: collected ${sample.mgdl.toInt()} count=$stagingValidReadingCount, not published",
        )
    }

    /** Puts the pre-soak slot back to "no sensor". It never touches a file. */
    private fun clearStagingState() {
        runCatching { stagingWarmupNotification.cancel() }
        stagingPresent = false
        stagingWarming = false
        stagingWarmupDone = false
        stagingValidReadingCount = 0
        stagingLastLifeCount = -1
        stagingLastValueMgdl = null
        stagingLastValueAtMs = null
        _stagingCurve.value = emptyList()
        _stagingWarmup.value = null
        _stagingLifecycle.value = null
        _stagingEvidence.value = null
        _stagingState.value = StagingState.ABSENT
    }

    private fun refreshStagingLifecycle() {
        if (!stagingPresent) {
            _stagingLifecycle.value = null
            return
        }
        val identity = runCatching { stagingStore.loadIdentity() }.getOrNull()
        val activatedAtMs = identity?.activatedAtMs?.takeIf { it > 0L }
            ?: runCatching { stagingStore.loadSlotActivatedAt() }.getOrDefault(0L)
        _stagingLifecycle.value = Libre3Staging.computeLifecycle(
            slot = SensorSlot.STAGING,
            activatedAtMs = activatedAtMs,
            wearMinutes = identity?.wearDurationMinutes,
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun refreshStagingEvidence() {
        _stagingEvidence.value =
            if (!stagingPresent) null
            else CgmStagingEvidence(
                validCount = stagingValidReadingCount,
                lastValueMgdl = stagingLastValueMgdl,
                lastValueAtEpochMs = stagingLastValueAtMs,
            )
    }

    private fun refreshStagingState() {
        _stagingState.value = Libre3Staging.computeStagingState(
            present = stagingPresent,
            warming = stagingWarming,
            validReadingCount = stagingValidReadingCount,
        )
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
