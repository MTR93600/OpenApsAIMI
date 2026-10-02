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
import app.aaps.core.interfaces.plugin.ActivePlugin
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
    private val activePlugin: ActivePlugin,
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

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ties "this sample was accepted" to "this is still the sensor that feeds the loop".
     *
     * [Libre3Ingest] keeps one process wide high-water mark, and it is keyed on the sensor's own
     * minute counter. A new sensor starts that counter at zero, so a reading of the retired sensor
     * that raised the mark after the swap would refuse every reading of the new sensor for its
     * whole life. Taking the mark and the epoch under one lock makes those two steps one step.
     *
     * ⚠️ ASYNC IMPACT: [newProductionEpoch] and [handleProductionGlucose] share this lock. The
     * promotion runs on the caller thread (the status screen uses the main dispatcher). A glucose
     * callback still arrives on the driver's BLE thread and only the database insert moves to
     * [ioScope].
     */
    private val ingestLock = Any()

    /**
     * The watcher of one production sensor.
     *
     * A fresh object is made every time the sensor that feeds the loop changes, and that object is
     * what tells a reading of the retired sensor from a reading of the new one.
     * `Libre3CgmDriverReal` hands a sample to a snapshot of its watcher list, so `removeWatcher`
     * cannot stop a sample that is already on its way, and the plugin's own insert runs later still
     * on [ioScope]. The watcher object travels with the sample through both, which a generation
     * number read at the start of the call could not do: the swap can happen between that read and
     * the accept.
     */
    private inner class ProductionWatcher : Libre3GlucoseWatcher {

        override fun onWarmup(state: Libre3WarmupState) = handleProductionWarmup(state)
        override fun onGlucose(sample: Libre3GlucoseSample) = handleProductionGlucose(sample, this)
        override fun onSession(up: Boolean, reason: String?) = handleProductionSession(up, reason)
        override fun onError(message: String, fatal: Boolean) = handleProductionError(message, fatal)
    }

    /**
     * The watcher of the sensor that feeds the loop right now — see [ProductionWatcher].
     *
     * It is `internal` so the regression test in this module can keep a retired watcher and prove
     * that a late reading through it changes nothing.
     */
    @Volatile
    internal var productionWatcher: Libre3GlucoseWatcher = ProductionWatcher()
        private set

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
     * Hands the loop over from the running sensor to the pre-soak sensor.
     *
     * This is the only action that changes which sensor feeds the loop, and it is always a user
     * action. The order of the steps is chosen so that every step that can fail comes before the
     * first step that cannot be undone: the store write of the new sensor is the last reversible
     * one. See `docs/LIBRE3_PRESOAK_PLAN.md` §10.
     *
     * The calibration cutoff is written only after that store write, the driver swap and
     * [logSensorChangeOnce]. Reference `Libre3NativePlugin.promoteStagingToProduction` on
     * `dev_OAPSAIMI` @ `3dd0ca64772`, L980–1078. The cutoff call is inside `runCatching`, same as
     * reference L1055: a throw there does not undo the swap, and the function still returns
     * [PromotionResult.Ok].
     *
     * @param allowEarly accepted and ignored. A Libre 3 pre-soak has no soak gate, because the user
     *   already pays real sensor wear time for the soak, so there is nothing here to relax. Please
     *   do not turn this into a gate.
     */
    override suspend fun promoteStagingToProduction(allowEarly: Boolean): PromotionResult {
        if (!stagingPresent) return PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)
        // Non-null only when serial, MAC and PIN are all there, that is when the NFC write landed.
        val staged = runCatching { stagingStore.loadIdentity() }.getOrNull()
            ?: return PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)
        val keys = runCatching { stagingStore.loadSessionKeys() }.getOrNull()
            ?: return PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)
        if (keys.phase5RawKey == null) {
            // Not a reason to refuse: a sensor taken over in mid life may hold only the session
            // keys, and the next handshake writes a pairing key of its own.
            aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: the pre-soak sensor has no pairing key")
        }
        val nowMs = System.currentTimeMillis()
        aapsLogger.info(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.PRESOAK}: promote asked serial=${staged.serialNumber} " +
                "soakMs=${nowMs - staged.activatedAtMs} readings=$stagingValidReadingCount",
        )
        // The last reversible step. One commit, so a false means nothing at all was written and the
        // running sensor keeps going, untouched. There is no reject reason for "the phone could not
        // write", and a phone that cannot write its own private file has a much larger problem, so
        // the honest answer is to change nothing and report the promotion as refused.
        if (!sensorStore.adopt(staged, keys)) {
            aapsLogger.error(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.PRESOAK}: promote refused, the phone could not store the new sensor",
            )
            return PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT)
        }
        // The promoted sensor is a real sensor. Without this a restart would send production to the
        // stub. It is idempotent, and a failure must not stop a swap that is already half done.
        runCatching { preferences.put(Libre3BooleanKey.UseRealSkeleton, true) }
        // The net must not ask for a link while the two instances are being swapped: it would ask
        // the instance that is about to be retired. It arms itself again on the next lost link.
        cancelReconnectWatchdog()
        // Taken before the swap: after it, `stagingDriver` would build a brand new instance.
        val promoted = stagingDriver
        // Retire the outgoing sensor. The watcher goes first, because that is the step that stops
        // it feeding the loop, and it is also the one least able to fail. `newProductionEpoch` does
        // that and, in the same move, makes every reading of the retired sensor that is still on
        // its way harmless: the repeat guard forgets the old counter, the stored mark goes back to
        // "nothing accepted yet", and anything late carries the retired watcher and is refused.
        newProductionEpoch()
        // Held in a local, because after the swap below `driver` is the promoted instance.
        val outgoing = driver
        runCatching { outgoing.disconnect() }
        // The live link is kept, so there is no gap in the glucose. Adding first and removing
        // second on purpose: a moment where both watchers fire costs one buffered reading, while
        // removing first would lose one.
        runCatching { promoted.setContext(context) }
        promoted.addWatcher(productionWatcher)
        runCatching { promoted.removeWatcher(stagingWatcher) }
        // The pre-soak state goes before the pre-soak file, so a reading that is still in flight
        // finds the slot already empty and does not write the "a sensor is present" flag back after
        // the wipe.
        clearStagingState()
        // The pre-soak copy of the PIN and of the pairing key must not survive the promotion. The
        // running read loop kept its own store, so its wear extension writes still land in the old
        // file until the link ends; everything else, this instance included, already points at the
        // production file, because `promoteStagingInstance` rebinds it below.
        runCatching { stagingStore.clearAll() }
        // The swap comes **before** the old instance is stopped. The other way round there is a
        // window in which `Libre3CgmDrivers.default()` still hands out an instance whose executor
        // is already dead, and anything that asked it for a link in that window would throw.
        val retired = Libre3CgmDrivers.promoteStagingInstance()
        runCatching { retired?.shutdown() }
        // The stub is not `retired`, so it needs stopping of its own when it was the one in use.
        runCatching { if (outgoing !== promoted && outgoing !== retired) outgoing.shutdown() }
        // After the adopt, so the new serial is already the stored one while the "already written"
        // mark is gone. Dated on the real activation of the pre-soak sensor, so the sensor age and
        // the calibration session are right from the first minute.
        logSensorChangeOnce(staged.activatedAtMs)
        // The session is dated at the pre-soak activation, hours before this swap. Without this,
        // every fingerstick taken during the pre-soak — all paired against the OLD sensor — would
        // be fitted onto the new one, and applied from its first minute with no warm-up left.
        // Reference L1055. The `runCatching` is the reference: a throw is logged by returning from
        // the lambda and the swap still reports [PromotionResult.Ok].
        runCatching { activePlugin.activeCalibration.ignoreEntriesBefore(System.currentTimeMillis()) }
        // Make the promoted instance the driver the plugin really talks to from now on. `true` is
        // written here and not read back from the preference on purpose: a promoted sensor IS a
        // real sensor, and a `select(false)` at this point would stop the instance that has just
        // taken the loop over.
        runCatching { Libre3CgmDrivers.select(useReal = true, watcher = productionWatcher) }
        refreshProductionLifecycle()
        // One sensor moved from the pre-soak slot into production, so the service is still wanted,
        // but the reason for it has changed. Asked again so the two slots are counted as they are.
        refreshSessionService()
        // A pre-soak whose link happened to be down at this moment must not leave the loop without
        // a sensor until the watchdog wakes up. Asked straight of the promoted instance, so no
        // driver choice can be undone here either.
        if (!promoted.isSessionUp()) {
            val blocked = Libre3CgmDrivers.realDriverBlockedReason()
            if (blocked != null) aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.PRESOAK}: BLE not started, $blocked")
            else runCatching { promoted.connect(staged.bleAddress) }
        }
        aapsLogger.info(
            LTag.BGSOURCE,
            "${Libre3LogMarkers.PRESOAK}: promote done serial=${staged.serialNumber} " +
                "retired=${retired != null} sessionUp=${promoted.isSessionUp()}",
        )
        return PromotionResult.Ok
    }

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
        refreshProductionLifecycle()
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
        // Also swaps the production watcher, so a reading of the sensor that was just replaced
        // cannot raise the mark again after this point — the same hole the promotion had.
        newProductionEpoch()
        driver.addWatcher(productionWatcher)
        // The scan has already stored when this sensor was started, so the sensor change can be
        // written now instead of waiting for the first reading an hour later. That matters for the
        // calibration plugin: its own warm-up window is counted from this event, so anchoring it on
        // the real start means the user may calibrate as soon as the sensor is really settled.
        logSensorChangeOnce(sensorStore.loadIdentity()?.activatedAtMs ?: Libre3NfcSession.UNKNOWN_ACTIVATION_TIME)
        aapsLogger.info(LTag.BGSOURCE, "${Libre3LogMarkers.SESSION}: new sensor, ingest starts counting again")
        // A sensor was just added or replaced, so what the slots want may have changed.
        refreshSessionService()
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
     * @param owner the watcher of the sensor this activation time belongs to. A sensor swap while
     *   this call was queued would otherwise date the **new** sensor's start on the **old** one's
     *   activation, because by then the stored serial is already the new one and the "already
     *   written" mark is already gone. The sensor age and the calibration session would then both
     *   be wrong by a whole sensor life.
     */
    private fun logSensorChangeOnce(activatedAtMs: Long, owner: Libre3GlucoseWatcher = productionWatcher) {
        if (activatedAtMs <= Libre3NfcSession.UNKNOWN_ACTIVATION_TIME) return
        if (!preferences.get(BooleanKey.BgSourceCreateSensorChange)) return
        ioScope.launch {
            if (owner !== productionWatcher) {
                aapsLogger.info(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.SESSION}: sensor change dropped, it belongs to the sensor that was replaced",
                )
                return@launch
            }
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
        driver.removeWatcher(productionWatcher)
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
        val selected = Libre3CgmDrivers.select(useReal = wantReal, watcher = productionWatcher)
        selected.setContext(context)
    }

    // The four calls below are the plugin's own view of the production path. They always mean "the
    // sensor that feeds the loop right now", so a caller that has no watcher object of its own — a
    // screen, or a test — can still drive the production path in a way that cannot go stale.

    override fun onWarmup(state: Libre3WarmupState) = handleProductionWarmup(state)

    override fun onGlucose(sample: Libre3GlucoseSample) = handleProductionGlucose(sample, productionWatcher)

    override fun onSession(up: Boolean, reason: String?) = handleProductionSession(up, reason)

    override fun onError(message: String, fatal: Boolean) = handleProductionError(message, fatal)

    private fun handleProductionWarmup(state: Libre3WarmupState) {
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
     *
     * @param owner the watcher the sample was delivered to. It is checked against
     *   [productionWatcher] at every step that writes, so a sample of the retired sensor can never
     *   raise the ingest mark of the sensor that has just taken over — see [newProductionEpoch].
     */
    private fun handleProductionGlucose(sample: Libre3GlucoseSample, owner: Libre3GlucoseWatcher) {
        if (Libre3Ingest.isWarmupBlockingIngest(warmupPhase)) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.BG}: ignored during warm-up ${sample.mgdl.toInt()} @${sample.timestampMs}",
            )
            return
        }
        val accepted = synchronized(ingestLock) {
            if (owner !== productionWatcher) {
                aapsLogger.info(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.BG}: reading of the retired sensor dropped lifeCount=${sample.lifeCount}",
                )
                return
            }
            Libre3Ingest.shouldAccept(sample)
        }
        if (!accepted) {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "${Libre3LogMarkers.BG}: repeated reading dropped ${sample.mgdl.toInt()} lifeCount=${sample.lifeCount}",
            )
            return
        }
        // Self-healing net for a sensor that was started before this build, or whose scan happened
        // while the setting was off. The sensor's own minute counter is the honest start: the
        // reading time is built from it, so this gives back exactly the stored activation moment.
        logSensorChangeOnce(
            Libre3WarmupClock.activationTimeFromReading(sample.timestampMs, sample.lifeCount),
            owner,
        )
        refreshProductionLifecycle()
        val glucoseValues = listOf(Libre3Ingest.mapToGv(sample))
        ioScope.launch {
            if (owner !== productionWatcher) {
                aapsLogger.info(
                    LTag.BGSOURCE,
                    "${Libre3LogMarkers.BG}: insert dropped, the sensor was replaced while it was queued",
                )
                return@launch
            }
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
            // two inserts that overlap could otherwise store the lower of the two. Under the same
            // lock as the epoch, so the mark of a retired sensor can never land in the file of the
            // sensor that took over.
            synchronized(ingestLock) {
                if (owner !== productionWatcher) return@launch
                sensorStore.saveLastLifeCount(Libre3Ingest.lastAcceptedLifeCount())
            }
        }
    }

    private fun handleProductionSession(up: Boolean, reason: String?) {
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

    private fun handleProductionError(message: String, fatal: Boolean) {
        aapsLogger.error(LTag.BGSOURCE, "${Libre3LogMarkers.ERROR}: fatal=$fatal $message")
    }

    /**
     * Says "from now on a different sensor feeds the loop".
     *
     * Everything that could carry the old sensor's minute counter into the new sensor's file is
     * dropped here, under one lock: the repeat guard forgets it, the mark on the disk is set back
     * to "nothing accepted yet", and a new [ProductionWatcher] takes over so anything still on its
     * way from the retired sensor is refused instead of written.
     *
     * Without this the promoted sensor's readings all sit below a mark left by the sensor it
     * replaced, so the user sees a connected sensor and a loop with no glucose, and a restart does
     * not help because the mark is on the disk as well.
     *
     * ⚠️ ASYNC IMPACT: the caller thread holds [ingestLock] across [Libre3Ingest.reset] and the
     * disk write of the life-count mark. Promotion calls this from the status screen's coroutine.
     */
    private fun newProductionEpoch() {
        val retiredWatcher = synchronized(ingestLock) {
            val previous = productionWatcher
            productionWatcher = ProductionWatcher()
            Libre3Ingest.reset()
            // -1 is what "nothing accepted yet" means to `loadLastLifeCount`, so this is the same
            // as no mark at all. It also undoes a write of the retired sensor that landed after the
            // store was handed to the new one.
            runCatching { sensorStore.saveLastLifeCount(NO_LIFE_COUNT) }
            previous
        }
        // A driver that outlives the swap must not keep the retired watcher as a second listener.
        runCatching { driver.removeWatcher(retiredWatcher) }
        runCatching { Libre3CgmDrivers.stagingOrNull()?.removeWatcher(retiredWatcher) }
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

    /** The early life and end of life hint of the sensor that feeds the loop. */
    private fun refreshProductionLifecycle() {
        val identity = runCatching { sensorStore.loadIdentity() }.getOrNull()
        _lifecycle.value = Libre3Staging.computeLifecycle(
            slot = SensorSlot.PRODUCTION,
            activatedAtMs = identity?.activatedAtMs ?: 0L,
            wearMinutes = identity?.wearDurationMinutes,
            nowMs = System.currentTimeMillis(),
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

        /** What the stored ingest mark holds when no reading of this sensor was accepted yet. */
        private const val NO_LIFE_COUNT = -1

        /**
         * How long a session may stay down before the plugin asks for a connection itself.
         *
         * Long enough that the driver's own ladder has had every chance first, short enough that a
         * user is not left without glucose for a quarter of an hour.
         */
        private const val RECONNECT_WATCHDOG_MS = 5L * 60L * 1000L
    }
}
