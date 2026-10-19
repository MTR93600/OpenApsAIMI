package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriverStub
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
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
 * Two separate things are missing, and both are in the text the user reads:
 *
 * - **The sensor cannot be paired.** A ONE+ sends nothing until a J-PAKE handshake succeeds, and
 *   the implementation of that handshake in this project (`:plugins:libkeks`) is a Java library
 *   with no Apple build. `OnePlusGattClientIos` and `OnePlusBleScannerIos` exist and are tested,
 *   so the radio half is ready; the handshake is what is not.
 * - **A terminated app is not woken.** `bluetooth-central` in the Info.plist lets a *suspended*
 *   app be woken by a connected peripheral, which is the five minute heartbeat every iOS loop app
 *   relies on. CoreBluetooth **state restoration is not implemented**, so once iOS reclaims the
 *   app, or the phone restarts, nothing brings it back until someone opens it by hand.
 *
 * ## What the members below do
 *
 * Only [isAvailable], [notUsableReason] and [screenItems] are reached while the plugin is locked
 * off; the plugin's `onStart` never runs. The rest answer honestly anyway, so that lifting the
 * enforcement needs no second change here:
 *
 * - the driver is `OnePlusCgmDriverStub`, which reports FAILED on connect and never claims a
 *   session or a reading - it cannot be mistaken for a working sensor;
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

    override val stagingNamespace: String = STAGING_NAMESPACE

    override val notUsableReason: TextRef = SourceStrings.dexcom_oneplus_ios_not_ready

    /**
     * Listed on purpose, which is the one place this host does not copy Android.
     *
     * On Android the plugin is offered only when the engineering marker file
     * `AAPS/extra/engineering_oneplus` exists, because the driver there is an engineering build
     * that a user should not stumble into. Here there is no driver to stumble into at all: the
     * entry is locked off and carries [notUsableReason]. Listing it is therefore how the person
     * gets told, in the place they would go looking, instead of finding nothing and wondering.
     *
     * One line to change if the owner would rather hide it: return `config.isEngineeringMode()`.
     */
    override fun isAvailable(): Boolean = true

    override fun useRealSkeleton(): Boolean = false

    override fun attachPlatform(driver: OnePlusCgmDriver) {
        aapsLogger.info(
            LTag.BGSOURCE,
            "DEXCOM_ONEPLUS_SESSION: no ONE+ driver on iPhone (the pairing handshake has no Apple " +
                "build), so there is no platform to attach",
        )
    }

    override fun slotStore(namespace: String?): OnePlusSensorStore = OnePlusSensorStore(namespace)

    override fun productionDriver(): OnePlusCgmDriver = OnePlusCgmDriverStub.instance

    override fun stagingDriver(): OnePlusCgmDriver = OnePlusCgmDriverStub.instance

    override fun selectProductionDriver(useReal: Boolean, watcher: OnePlusGlucoseWatcher?): OnePlusCgmDriver {
        val stub = OnePlusCgmDriverStub.instance
        if (watcher != null) stub.addWatcher(watcher)
        return stub
    }

    /** There is no second driver to promote, because there is no first one. */
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

    /** No Status, Start or Warmup screen here, so the settings screen offers no button for them. */
    override fun screenItems(): List<PreferenceItem> = emptyList()

    companion object {

        /**
         * The pre-soak slot's own store name.
         *
         * The same literal as `OnePlusCgmDrivers.STAGING_NAMESPACE`, which is Android only because
         * it sits next to the real driver. Repeated rather than reached for, so this file does not
         * need a driver registry it has no use for.
         */
        const val STAGING_NAMESPACE: String = "staging"
    }
}
