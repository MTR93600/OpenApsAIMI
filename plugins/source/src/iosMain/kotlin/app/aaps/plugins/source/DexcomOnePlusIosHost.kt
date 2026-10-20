package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
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
import app.aaps.plugins.source.compose.OnePlusBlePermissionGate
import app.aaps.plugins.source.keys.DexcomOnePlusIntentKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * [DexcomOnePlusHost] on iPhone.
 *
 * ## The plugin is listed, locked off, and says why
 *
 * [notUsableReason] is not null, so [DexcomOnePlusPlugin] carries `EnforcedState.Disabled`: the
 * user can see that Dexcom ONE+ exists, can read in the same place why it does not work here, and
 * cannot switch it on. That is deliberate. A BG source the user can select but that never produces
 * a reading is the worst shape this could take - the loop would simply see "no glucose" and the
 * person would not know why.
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
 * - **A new sensor still cannot be set up.** An unbonded pairing reaches a point where the sensor
 *   asks for an OS pairing. CoreBluetooth has no API for that, so `createBond` answers false and
 *   the handshake stops there. This is not a gap in the port; iOS does not offer it.
 * - **A terminated app is not woken.** `bluetooth-central` in the Info.plist lets a *suspended*
 *   app be woken by a connected peripheral, which is the five minute heartbeat every iOS loop app
 *   relies on. CoreBluetooth **state restoration is not implemented** for this driver - every
 *   restored peripheral is released by `OnePlusIosRadio` - so once iOS reclaims the app, or the
 *   phone restarts, nothing brings it back until someone opens it by hand.
 * - **There is no wake lock.** Android holds the processor through connect and authenticate;
 *   `OnePlusIosCpuLease` answers "no lease" because iOS has nothing with that meaning. The session
 *   already treats that as "the work goes ahead, less protected", and the background mode above is
 *   what stands in its place. It has not been measured on a phone.
 * - **One screen, not three.** The sensor start screen is shared code now and opens from this
 *   plugin's settings, so a person can scan, pick their sensor and type the four digit pairing
 *   code. There is still no Status and no Warm-up screen, and the pre-soak slot is left out
 *   because one app gets one scan - see [screenItems].
 *
 * ## Why it stays locked off even so
 *
 * A Dexcom ONE+ or G7 admits **one display at a time**. `OnePlusMacArbiter` records that two
 * sessions on one transmitter is not a race that sometimes bites but a certainty. So an attempt
 * from an iPhone is not a harmless experiment: it can take a running sensor away from the Dexcom
 * app or from the person's Android phone. Switching this on is the owner's call after a device
 * test, not something a green test run earns.
 *
 * ## What the members below do
 *
 * Only [isAvailable], [notUsableReason] and [screenItems] are reached while the plugin is locked
 * off; the plugin's `onStart` never runs. The rest answer honestly anyway, so nothing here lies
 * if the enforcement is ever lifted:
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
 * - the service and notification members have no iPhone equivalent in this app, so they write one
 *   line each rather than pretending the session is protected or that the user was told.
 */
@ContributesBinding(AppScope::class, binding = binding<DexcomOnePlusHost>())
@SingleIn(AppScope::class)
@Inject
class DexcomOnePlusIosHost(
    private val aapsLogger: AAPSLogger,
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

    override val notUsableReason: TextRef = SourceStrings.dexcom_oneplus_ios_not_ready

    /**
     * Listed on purpose, which is the one place this host does not copy Android.
     *
     * On Android the plugin is offered only when the engineering marker file
     * `AAPS/extra/engineering_oneplus` exists, because the driver there is an engineering build
     * that a user should not stumble into. Here the entry is locked off and carries
     * [notUsableReason]. Listing it is therefore how the person gets told, in the place they would
     * go looking, instead of finding nothing and wondering.
     *
     * One line to change if the owner would rather hide it: return `config.isEngineeringMode()`.
     */
    override fun isAvailable(): Boolean = true

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

    override fun showWarmup(state: OnePlusWarmupState) {
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_WARMUP: phase=${state.phase} (not shown, no iPhone display for it)")
    }

    override fun cancelWarmupNotification() {
        aapsLogger.info(LTag.BGSOURCE, "DEXCOM_ONEPLUS_WARMUP: nothing to take down on iPhone")
    }

    override fun alert(message: String) {
        aapsLogger.warn(LTag.BGSOURCE, "DEXCOM_ONEPLUS: not shown to the user on iPhone: $message")
    }

    /**
     * One scan on this phone, so this is the scan: asking for it takes the radio from whatever the
     * driver was doing. That is the right trade on the screen whose whole job is to find a sensor.
     */
    override fun screenScanner(): OnePlusBleScanner =
        platform.scanner(hint = null, slot = OnePlusLogMarkers.SLOT_PRODUCTION)

    /**
     * The sensor start screen, and only that one.
     *
     * It is the screen that stands between an iPhone and a first reading: scan, pick the sensor,
     * type the four digit code from the applicator, connect. The screen itself is shared code, so
     * what a person reads and the order they do things in are the same as on Android.
     *
     * It opens inside the settings tree through [withCompose] rather than through an Activity,
     * which is how every other shared screen reaches iOS.
     *
     * Status and Warm-up are still Android Activities and have no entry here, so the settings
     * screen offers no button for them rather than a button that does nothing.
     *
     * Three callbacks have no iPhone answer and say so instead of pretending:
     *
     * - **The pre-soak slot is absent.** `onStagingDriver` is null, so the screen does not draw the
     *   slot chooser at all. A second slot needs a second scan and CoreBluetooth gives an app one;
     *   with a chooser the user could pick a slot that would quietly take the radio from the other.
     *   Promotion follows from that: it is reached only from the slot conflict dialog, which cannot
     *   appear when there is no pre-soak sensor to conflict with.
     * - **The plugin is not switched on.** It carries `EnforcedState.Disabled` while
     *   [notUsableReason] is set, and a screen must not undo that. So Connect adopts the sensor and
     *   starts the session, and the plugin stays locked off until the owner lifts the enforcement.
     * - **There is no warm-up screen to hand over to**, so a started sensor closes the screen.
     */
    override fun screenItems(plugin: DexcomOnePlusPlugin): List<PreferenceItem> = listOf(
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
                        aapsLogger.warn(
                            LTag.BGSOURCE,
                            "DEXCOM_ONEPLUS: sensor adopted, plugin left off - it is locked while notUsableReason is set",
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
    )
}
