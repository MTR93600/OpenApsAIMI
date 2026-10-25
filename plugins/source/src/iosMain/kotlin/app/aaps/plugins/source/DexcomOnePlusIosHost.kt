package app.aaps.plugins.source

import androidx.compose.runtime.LaunchedEffect
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusIosPlatform
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.source.compose.DexcomOnePlusStartScreen
import app.aaps.plugins.source.compose.DexcomOnePlusStatusSummaryScreen
import app.aaps.plugins.source.compose.DexcomOnePlusWarmupScreen
import app.aaps.plugins.source.compose.OnePlusBlePermissionGate
import app.aaps.plugins.source.compose.formatSensorAge
import app.aaps.plugins.source.keys.DexcomOnePlusIntentKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Clock

/**
 * [DexcomOnePlusHost] on iPhone.
 *
 * ## The plugin can be switched on, and says what is different here
 *
 * [notUsableReason] is null, so nothing locks the switch: the owner asked for the iPhone to be
 * testable on a real sensor, and that call is theirs to make. What stays is [platformNote], which
 * replaces the plugin description with the limits below. The person reads them in the place they
 * switch the plugin on, and then decides.
 *
 * ## What is there now and what is not
 *
 * The driver itself is no longer missing. `OnePlusCgmDriverReal` is shared code: the same class
 * runs the scan, the connect, the KEKS handshake, the Control/EGV loop and the reconnect ladder on
 * both phones, and everything the phone itself owns is behind `OnePlusPlatform`, which
 * `OnePlusIosPlatform` now answers over CoreBluetooth and `NSUserDefaults`. So there **is** an
 * iPhone object that connects, authenticates and parses glucose. These are the steps still between
 * that and a reading a person could rely on:
 *
 * - **Nothing has met a sensor.** The handshake matches the Java byte for byte on recorded
 *   exchanges, and two branches have no recorded exchange at all - the unbonded four digit
 *   pairing, and the short reconnect with a saved key. Nothing in this lot changes that.
 * - **A new sensor pairs through the iPhone dialog.** An unbonded pairing reaches a point where
 *   the sensor asks for an OS pairing. CoreBluetooth has no API for that and pairs by itself on
 *   the next encrypted operation, showing its own dialog; `OnePlusGattClientIos` lets the
 *   handshake go on and waits for the person to accept. Not yet tried with a real sensor.
 * - **An ended app is woken only if the plugin starts again.** `bluetooth-central` in the
 *   Info.plist lets a *suspended* app be woken by a connected peripheral, which is the five minute
 *   heartbeat every iOS loop app relies on. The ONE+ central is opened with its own restore
 *   identifier, so iOS can also start an ended app again for a pending scan or connect. The
 *   restored link itself is not taken over - `OnePlusIosRadio` says why - and the driver connects
 *   again from the stored sensor, as at every plugin start. Whether the app starts this plugin
 *   during such a background launch is decided outside this module and has not been tried. After
 *   a force quit or a phone restart iOS starts nothing.
 * - **A missed reading is told even when the app cannot run.** After each reading,
 *   [onReadingStored] arms a local notification at "reading + stale limit" with the text of the
 *   missed BG alarm, and the next reading moves it. See `DexcomOnePlusMissedReadingAlarm`.
 * - **There is no wake lock.** Android holds the processor through connect and authenticate;
 *   `OnePlusIosCpuLease` answers "no lease" because iOS has nothing with that meaning. The session
 *   already treats that as "the work goes ahead, less protected", and the background mode above is
 *   what stands in its place. It has not been measured on a phone.
 * - **Three screens, with one slot.** Start, Warm-up and a status summary are shared code and
 *   open from this plugin's settings. The pre-soak slot is left out because one app gets one
 *   scan - see [screenItems].
 *
 * ## The one that is not a missing feature
 *
 * A Dexcom ONE+ or G7 admits **one display at a time**. `OnePlusMacArbiter` records that two
 * sessions on one transmitter is not a race that sometimes bites but a certainty. So an attempt
 * from an iPhone can take a running sensor away from the Dexcom app or from the person's Android
 * phone. That is why [platformNote] says it first: no amount of code here removes it, the person
 * has to know not to run both.
 *
 * ## What the members below do
 *
 * Every member below is now reachable, because the plugin can start:
 *
 * - [productionDriver] is `OnePlusCgmDrivers.default()`, the same registry Android uses: the stub
 *   until the engineering switch is turned on, the shared real driver after that. The stub reports
 *   FAILED on connect and never claims a session or a reading, so the default cannot be mistaken
 *   for a working sensor;
 * - [stagingDriver] stays the stub, and [promoteStagingInstance] stays null. The pre-soak slot is
 *   a **second** driver with its own scanner and its own link, and an app gets one Bluetooth scan
 *   from CoreBluetooth: a second slot would take the radio from the first and the two would stop
 *   working in ways nobody would see. See `OnePlusIosPlatform`;
 * - [slotStore] is a real store on this phone, because that part does work here;
 * - [setSessionServiceWanted] has no iPhone equivalent, so it writes one line rather than
 *   pretending the session is protected;
 * - [showWarmup], [cancelWarmupNotification] and [alert] go to the shared [NotificationManager],
 *   which shows a card in the app and an iOS notification. Android shows the same messages in an
 *   ongoing, silent status-bar notification; iOS has none, and every post is a banner, so
 *   `DexcomOnePlusIosNotices` posts a message only when it changes.
 */
@ContributesBinding(AppScope::class, binding = binding<DexcomOnePlusHost>())
@SingleIn(AppScope::class)
@Inject
class DexcomOnePlusIosHost(
    private val aapsLogger: AAPSLogger,
    // A function, not the type: ConfigBuilder reaches every plugin through ActivePlugin, and this
    // host is built as part of one of them. Asking for it late keeps that out of the graph build.
    private val configBuilder: () -> ConfigBuilder,
    // Functions too, for two reasons. The plugin is built with this host, so nothing the plugin
    // graph needs may be pulled in while the host itself is built. And a unit test on the simulator
    // can build the host without faking six interfaces it never reaches.
    private val notificationManager: () -> NotificationManager,
    private val rh: () -> TextResolver,
    private val dateUtil: () -> DateUtil,
    private val preferences: () -> Preferences,
    private val persistenceLayer: () -> PersistenceLayer,
    private val profileUtil: () -> ProfileUtil,
) : DexcomOnePlusHost {

    /**
     * Built on first use, never while the dependency graph is being assembled.
     *
     * The iOS graph is built eagerly at startup, and building this opens a `CBCentralManager`,
     * which is what raises the Bluetooth permission prompt. Only the plugin's start path asks for
     * it, and that path does not run while the plugin is locked off.
     */
    private val platform: OnePlusIosPlatform by lazy { OnePlusIosPlatform() }

    override val stagingNamespace: String = OnePlusCgmDrivers.STAGING_NAMESPACE

    /** Nothing stops the plugin here any more - the limits are in [platformNote]. */
    override val notUsableReason: TextRef? = null

    override val platformNote: TextRef = SourceStrings.dexcom_oneplus_ios_caution

    override fun useRealSkeleton(): Boolean = OnePlusCgmDrivers.useRealSkeleton

    override fun attachPlatform(driver: OnePlusCgmDriver) {
        driver.attach(platform)
    }

    override fun slotStore(namespace: String?): OnePlusSensorStore = OnePlusSensorStore(namespace)

    override fun productionDriver(): OnePlusCgmDriver = OnePlusCgmDrivers.default()

    /** The stub, always. A second slot cannot have its own scan on this phone - see the class doc. */
    override fun stagingDriver(): OnePlusCgmDriver = OnePlusCgmDriverStub.instance

    override fun selectProductionDriver(useReal: Boolean, watcher: OnePlusGlucoseWatcher?): OnePlusCgmDriver =
        OnePlusCgmDrivers.select(useReal, watcher)

    /** There is no pre-soak driver to promote, because [stagingDriver] never builds one. */
    override fun promoteStagingInstance(): OnePlusCgmDriver? = null

    override fun setSessionServiceWanted(wanted: Boolean) {
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_SESSION: iPhone has no foreground service, wanted=$wanted changed nothing",
        )
    }

    /** Decides which warm-up states become a message; see `DexcomOnePlusIosNotices`. */
    private val notices = DexcomOnePlusIosNotices()

    /**
     * [showWarmup] is called from the driver's threads and from the plugin, and [onReadingStored]
     * from the plugin's IO scope; their state is changed one caller at a time.
     */
    private val noticesLock = AapsLock()

    private val missedReadingAlarm by lazy { DexcomOnePlusMissedReadingAlarm(aapsLogger) }

    /** Time of the newest reading that armed [missedReadingAlarm], so an older one cannot move it back. */
    private var newestReadingMs = 0L

    override fun showWarmup(state: OnePlusWarmupState) {
        val decision = noticesLock.withLock { notices.decide(state, nowMs()) }
        when (decision) {
            is DexcomOnePlusIosNotices.Decision.Post -> {
                val args = listOfNotNull<Any>(decision.minutes, decision.endsAtMs?.let { dateUtil().timeString(it) })
                val text = rh().gs(decision.text, *args.toTypedArray())
                notificationManager().post(WARMUP_NOTICE_ID, text)
                aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_WARMUP: phase=${state.phase} posted to the person")
            }

            DexcomOnePlusIosNotices.Decision.Dismiss -> notificationManager().dismiss(WARMUP_NOTICE_ID)
            DexcomOnePlusIosNotices.Decision.Keep    -> Unit
        }
    }

    override fun cancelWarmupNotification() {
        noticesLock.withLock { notices.reset() }
        notificationManager().dismiss(WARMUP_NOTICE_ID)
    }

    override fun alert(message: String) {
        aapsLogger.warn(LTag.BGSOURCE, "DEXCOM_ONEPLUS: alert to the person: $message")
        notificationManager().post(ALERT_NOTICE_ID, message)
    }

    /**
     * Moves the missed-reading alarm to "this reading + stale limit".
     *
     * Reads the same two settings as `LocalAlertUtils.checkStaleBGAlert`: whether the alarm is on,
     * and the stale limit. With the alarm off, an armed one is taken down.
     */
    override fun onReadingStored(timestampMs: Long) {
        val prefs = preferences()
        if (!prefs.get(BooleanKey.AlertMissedBgReading)) {
            missedReadingAlarm.disarm()
            return
        }
        // A backfilled reading is older than the newest one and must not pull the alarm earlier.
        val newest = noticesLock.withLock {
            if (timestampMs <= newestReadingMs) {
                false
            } else {
                newestReadingMs = timestampMs
                true
            }
        }
        if (!newest) return
        val delaySeconds = DexcomOnePlusMissedReadingAlarm.delaySeconds(
            readingAtMs = timestampMs,
            nowMs = nowMs(),
            enabled = true,
            staleLimitMinutes = prefs.get(IntKey.AlertsStaleDataThreshold),
        ) ?: return
        val resolver = rh()
        missedReadingAlarm.arm(
            delaySeconds = delaySeconds,
            title = resolver.gs(CoreUiStrings.urgent_alarm),
            text = resolver.gs(CoreUiStrings.missed_bg_readings),
            breakThroughFocus = prefs.get(BooleanKey.AlertOverrideDoNotDisturb),
        )
    }

    override fun cancelMissedReadingAlarm() {
        noticesLock.withLock { newestReadingMs = 0L }
        missedReadingAlarm.disarm()
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * One scan on this phone, so this is the scan: asking for it takes the radio from whatever the
     * driver was doing. That is the right trade on the screen whose whole job is to find a sensor.
     */
    override fun screenScanner(): OnePlusBleScanner =
        platform.scanner(hint = null, slot = OnePlusLogMarkers.SLOT_PRODUCTION)

    /**
     * The three screens, in the Android order: Status, Start, Warm-up.
     *
     * All three are shared code, so what a person reads and the order they do things in are the
     * same as on Android. They open inside the settings tree through [withCompose] rather than
     * through an Activity, which is how every other shared screen reaches iOS. One screen cannot
     * open another from there, so the buttons that do that on Android are not drawn here: the
     * entries next to each other in the settings are the way between them.
     *
     * - **Status** is the production card of the Android status screen. The pre-soak and promotion
     *   cards are about a second slot, which this phone does not have.
     * - **Start** is the shared start screen. The pre-soak slot is absent: `onStagingDriver` is
     *   null, so the screen does not draw the slot chooser at all. A second slot needs a second
     *   scan and CoreBluetooth gives an app one; with a chooser the user could pick a slot that
     *   would quietly take the radio from the other. Promotion follows from that: it is reached
     *   only from the slot conflict dialog, which cannot appear when there is no pre-soak sensor to
     *   conflict with. A started sensor closes the screen; the warm-up is the next entry.
     * - **Warm-up** is the shared countdown screen. There is no driver log screen on iPhone, so
     *   that button is not drawn.
     *
     * Switching the plugin on after a sensor is adopted goes through [ConfigBuilder], exactly as
     * the Android Activity does, so the BG source is selected the same way on both phones.
     */
    override fun screenItems(plugin: DexcomOnePlusPlugin): List<PreferenceItem> = listOf(
        DexcomOnePlusIntentKey.Status.withCompose(
            ComposeScreenContent { onBack ->
                // As the Android status Activity does when it opens: the driver in use follows
                // the engineering switch before the screen reads it.
                LaunchedEffect(Unit) { plugin.syncDriverFromPrefs() }
                DexcomOnePlusStatusSummaryScreen(
                    onBack = onBack,
                    lifecycleFlow = plugin.lifecycle,
                    lastGlucose = { persistenceLayer().getLastGlucoseValue() },
                    formatGlucose = { mgdl -> profileUtil().fromMgdlToStringWithUnits(mgdl) },
                    formatTime = { epochMs -> dateUtil().timeString(epochMs) },
                    formatAge = { millis -> formatSensorAge(millis, rh()) },
                )
            },
        ),
        DexcomOnePlusIntentKey.Start.withCompose(
            ComposeScreenContent { onBack ->
                DexcomOnePlusStartScreen(
                    host = this@DexcomOnePlusIosHost,
                    // iOS has no permission to check before a scan: CoreBluetooth prompts on the
                    // first one.
                    permissions = OnePlusBlePermissionGate.NoSeparateStep,
                    onBack = onBack,
                    onEnsureDriver = {
                        plugin.syncDriverFromPrefs()
                        OnePlusCgmDrivers.default()
                    },
                    onActivatePlugin = {
                        configBuilder().performPluginSwitch(
                            changedPlugin = plugin,
                            enabled = true,
                            type = PluginType.BGSOURCE,
                        )
                    },
                    onStarted = { onBack() },
                    onBeginStaging = { plugin.beginStaging(it) },
                    onIsStagingSensor = { plugin.isStagingSensor(it) },
                    onCancelStaging = { plugin.cancelStaging() },
                    onOpenPromote = {
                        aapsLogger.warn(LTag.BGSOURCE, "DEXCOM_ONEPLUS: no promote screen on iPhone")
                    },
                    onStagingDriver = null,
                    onSensorSessionStarted = { address, previousMac ->
                        plugin.onSensorSessionStarted(address, previousMac)
                    },
                )
            },
        ),
        DexcomOnePlusIntentKey.Warmup.withCompose(
            ComposeScreenContent { onBack ->
                DexcomOnePlusWarmupScreen(
                    storedSession = { slotStore(OnePlusCgmDrivers.storeNamespace(SensorSlot.PRODUCTION)).load() },
                    onBack = onBack,
                    onRetry = {
                        plugin.syncDriverFromPrefs()
                        OnePlusCgmDrivers.default().resumeStoredSession()
                    },
                    onOpenLog = null,
                    onOpenStart = null,
                    onOpenStatus = null,
                    formatTime = { epochMs -> dateUtil().timeString(epochMs) },
                )
            },
        ),
    )

    internal companion object {

        /** The card for warm-up and connection messages. */
        val WARMUP_NOTICE_ID = NotificationId.DEXCOM_ONEPLUS_WARMUP

        /** The card for [alert]. */
        val ALERT_NOTICE_ID = NotificationId.DEXCOM_ONEPLUS_ALERT
    }
}
