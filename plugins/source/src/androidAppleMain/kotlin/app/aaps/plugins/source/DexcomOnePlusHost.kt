package app.aaps.plugins.source

import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner

/**
 * Everything [DexcomOnePlusPlugin] needs from the phone it runs on.
 *
 * The plugin itself is shared code: the sensor slots, the pre-soak rules, the promotion gates and
 * the ingest path read the same on both platforms. What is not shared is the host around it - the
 * driver registry, the three setup screens, the foreground service and the status-bar notification
 * are Android things, and an iPhone has no equivalent of some of them.
 *
 * Each of those favours is named here one by one, the same way `OnePlusPlatform` names what the
 * driver needs, so the plugin never has to know what an Android `Context` is.
 *
 * ⚠️ Nothing here may answer with something that pretends to work. A host that cannot honour one of
 * these says so through [notUsableReason], and the plugin is then locked off and tells the user
 * why. A CGM plugin that is switched on and quietly never produces a reading is read by the dosing
 * engine as "no glucose", which is not the same thing as a reading.
 *
 * [platformNote] is the softer half of that: a host that CAN run the driver but has limits worth
 * reading says so there. It replaces the plugin description and locks nothing, so the person
 * reads it in the place they switch the plugin on and still decides for themselves.
 */
interface DexcomOnePlusHost {

    /**
     * Give [driver] what it needs from this phone, before anything else is asked of it.
     *
     * This is `OnePlusCgmDriver.attach` with the platform filled in by the host, because what a
     * driver needs - a key-value file, an LE scanner, a GATT stack, a wake lock - is not the same
     * set of things on every phone.
     */
    fun attachPlatform(driver: OnePlusCgmDriver)

    /**
     * The private key-value store of one sensor slot: its identity, its pairing code, its key and
     * the marks that let a restart pick the sensor up again.
     *
     * @param namespace null for the slot that feeds the loop, [stagingNamespace] for the pre-soak.
     */
    fun slotStore(namespace: String?): OnePlusSensorStore

    /** Key-value file namespace of the pre-soak slot, so both slots keep their own identity. */
    val stagingNamespace: String

    /**
     * Why this plugin cannot work on this phone, or null when it can.
     *
     * Not null means the plugin is listed but locked off, with this text under its name. It is the
     * one place the limitation is written, so a person reads it where they would try to switch the
     * plugin on.
     */
    val notUsableReason: TextRef?

    /**
     * What this phone does differently, or null when there is nothing to say.
     *
     * Shown as the plugin description instead of the generic one. Unlike [notUsableReason] it
     * never locks the switch - it is for limits the person should know about before they rely on
     * the plugin, not for a plugin that cannot work.
     */
    val platformNote: TextRef?

    /** True while the production slot is routed to the real driver rather than the stub. */
    fun useRealSkeleton(): Boolean

    /** The driver feeding the loop. */
    fun productionDriver(): OnePlusCgmDriver

    /** The second, independent driver for a pre-soak sensor. */
    fun stagingDriver(): OnePlusCgmDriver

    /** Switch the production slot between the stub and the real driver, carrying [watcher] over. */
    fun selectProductionDriver(useReal: Boolean, watcher: OnePlusGlucoseWatcher?): OnePlusCgmDriver

    /**
     * Hand the pre-soak driver the production role, keeping its live link.
     *
     * @return the driver that has just been retired, for the caller to shut down, or null when
     *   there was no pre-soak driver to promote.
     */
    fun promoteStagingInstance(): OnePlusCgmDriver?

    /**
     * Ask for, or give back, the privilege that keeps a Bluetooth session alive in the background.
     *
     * On Android that is the `connectedDevice` foreground service. A host without one says so in
     * its log and changes nothing - it must never be read as "the session is now protected".
     */
    fun setSessionServiceWanted(wanted: Boolean)

    /** Show the live warm-up / reconnect state where the user can see it. */
    fun showWarmup(state: OnePlusWarmupState)

    /** Take the warm-up display down. */
    fun cancelWarmupNotification()

    /** Tell the user about a problem that happened while they were not looking. */
    fun alert(message: String)

    /**
     * A scanner for the setup screens to drive themselves, separate from the driver's own.
     *
     * The start screen lists what is in range so the user can pick their sensor, which is a
     * different job from the driver's pre-connect wait for one known MAC.
     *
     * ⚠️ One radio. On Android this is a second scan next to the driver's, which is what it has
     * always been. On iPhone an app gets a single scan from CoreBluetooth, so asking for this takes
     * the radio from whatever the driver was doing. That is the right trade on a screen whose whole
     * purpose is to find a sensor, and it is the same limitation that leaves the pre-soak slot out.
     */
    fun screenScanner(): OnePlusBleScanner

    /**
     * The entries that open this plugin's own screens, for the settings screen.
     *
     * Empty on a host that has no such screens, so the settings screen simply does not offer them
     * rather than offering a button that does nothing.
     *
     * @param plugin the plugin these screens act on. It is passed in rather than injected because
     *   the plugin builds the host, so a host that asked for the plugin would be a loop.
     */
    fun screenItems(plugin: DexcomOnePlusPlugin): List<PreferenceItem>
}
