package app.aaps.ios.shell.config

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.configuration.InitProgress
import app.aaps.core.interfaces.ios.IosAapsDirectories
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.UIKit.UIDevice

/**
 * What kind of build this is, answered for iOS.
 *
 * On Android these values come from the Gradle flavour: `full`, `pumpcontrol`, `aapsclient`. iOS
 * builds two kinds, chosen by the `AAPSRole` key of the app's Info.plist ([IosAppRole]):
 *
 * - the **master** app (Xcode target AAPS), the same role as the Android `full` flavour. It runs the
 *   loop, talks to the pump and the sensor, and writes and uploads the therapy records.
 * - the **follower** app (Xcode target AAPS2, shown as AAPSClient), the same role as the Android
 *   `aapsclient` flavour. It shows the master's data from Nightscout and sends commands to the master.
 *
 * The bundle ids are still `app.aaps.client` and `app.aaps.client2`, a name from the first iOS port.
 * They do not decide the role; the Info.plist key does.
 *
 * This is a real implementation, not a stand-in. Getting [APS] or [PUMPDRIVERS] wrong here would
 * make shared code offer features the app cannot perform.
 */
class IosConfig(
    override val VERSION_NAME: String = GeneratedBuildInfo.VERSION,
    override val APPLICATION_ID: String = NSBundle.mainBundle.bundleIdentifier ?: DEFAULT_BUNDLE_ID,
    /**
     * Where the option marker files are looked for. A test points it elsewhere.
     *
     * `Documents/AAPS/extra` ([IosAapsDirectories.extra]), which the Files app shows as
     * **On My iPhone › AAPS › AAPS › extra**. The folder is reachable by the person using the
     * phone, so `extra/engineering_mode` can be created by hand there, exactly as it is created in
     * `AAPS/extra` on Android. The folder is made at app start by [IosAapsDirectories.prepare], not
     * here.
     */
    private val extraDir: String? = IosAapsDirectories().extra,
    /** Master or follower. From the Info.plist; a test passes it. */
    val role: IosAppRole = IosAppRole.fromBundle(),
) : Config {

    override val SUPPORTED_NS_VERSION: Int = 150000

    /*
     * The master app has the values of the Android `full` flavour: the loop runs here (APS), pump
     * drivers are offered (PUMPDRIVERS), and the phone is not a follower. The owner chose this on
     * 2026-10-22: the iPhone is a master phone, exactly like AAPS on Android.
     *
     * The follower app has the values of the Android `aapsclient` flavour, the same as the desktop
     * follower (`DesktopClientConfig`): no loop, no pump drivers, AAPSCLIENT. The owner added it on
     * 2026-11-22, in the AAPS2 target, which was not used as a second master.
     *
     * PUMPCONTROL stays false: that flavour means "pump remote control without a loop".
     */
    override val APS: Boolean = role == IosAppRole.MASTER
    override val PUMPCONTROL: Boolean = false
    override val PUMPDRIVERS: Boolean = role == IosAppRole.MASTER

    override val AAPSCLIENT: Boolean = role == IosAppRole.CLIENT

    /** The name of the Android flavour with the same role, so shared code that compares it agrees. */
    override val FLAVOR: String = if (role == IosAppRole.CLIENT) "aapsclient" else "full"

    /** One follower app only, so it is follower number 1, as the Android `aapsclient` flavour. */
    override val AAPSCLIENT1: Boolean = role == IosAppRole.CLIENT
    override val AAPSCLIENT2: Boolean = false
    override val AAPSCLIENT3: Boolean = false
    /**
     * Which configuration built this, from the bundle - the same trick as [appName] and the client
     * number, and for the same reason: one Kotlin framework serves every configuration, so it cannot
     * know on its own. Pinned to "debug" before, which made a release build describe itself as a
     * development one.
     */
    override val BUILD_TYPE: String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("AAPSBuildType") as? String)?.lowercase() ?: "debug"
    override val PLATFORM: String = GeneratedBuildInfo.PLATFORM
    override val platform: AppPlatform = AppPlatform.Ios
    override val VERSION: String = VERSION_NAME
    override val BUILD_VERSION: String = GeneratedBuildInfo.BUILD
    /** Anything that is not a release build is treated as a debug one, which is the safe way round. */
    override val DEBUG: Boolean = BUILD_TYPE != "release"

    // Filled by the build, the same three facts Android has sent since 2019: which commit, which
    // repository, and whether the tree was clean. Together they are what tells a crash report from
    // upstream apart from one out of a fork. A build with no git says "NoGitSystemAvailable" and
    // reports not-committed, rather than inventing a commit or claiming a clean tree.
    override val HEAD: String = GeneratedBuildInfo.HEAD
    override val COMMITTED: Boolean = GeneratedBuildInfo.COMMITTED
    override val REMOTE: String = GeneratedBuildInfo.REMOTE

    override val currentDeviceModelString: String =
        UIDevice.currentDevice.let { "${it.model} ${it.systemName} ${it.systemVersion}" }

    /** Nightscout stores this verbatim, so keep it to the device identity - no OS version. */
    override val deviceModelForUpload: String = UIDevice.currentDevice.model
    // Apple is the only maker of iOS devices, so there is nothing to look up.
    override val deviceManufacturer: String = "Apple"

    /**
     * What the app calls itself, taken from the bundle it was built into.
     *
     * Read rather than written down: one Kotlin framework is linked into both Xcode targets, and
     * each target sets its own `CFBundleDisplayName`. Asking the bundle therefore names the app that
     * is really running, and cannot disagree with the name under its icon on the home screen.
     *
     * The fallback matters only in a test binary, which has no app bundle to ask.
     */
    override val appName: TextRef = TextRef.Literal(
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String
            ?: NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String
            ?: "AAPS"
    )

    /**
     * Not done at the start. `IosAppStartup` calls [initCompleted] once `ConfigBuilder.initialize` has
     * elected a plugin in each category. Before that, a fresh install has no pump at all, and reading
     * the active pump throws "No pump selected". `appInitialized` is what background work waits on, as
     * on Android. This used to start at `done = true`, so nothing waited: the home widget calculated
     * the IOB during the election and the app died on its very first launch.
     *
     * The first screen is composed only after the election, so it never sees `done = false` and no
     * splash is shown.
     */
    private val _initProgressFlow = MutableStateFlow(InitProgress(done = false))
    override val initProgressFlow: StateFlow<InitProgress> = _initProgressFlow.asStateFlow()

    private val _initSnackbarFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val initSnackbarFlow: SharedFlow<String> = _initSnackbarFlow.asSharedFlow()

    // Each of these replaces the whole value rather than copying it, which is how they were written
    // and what the start-up progress wants. The one field that must survive is the reconfigure depth:
    // it belongs to whoever opened the window, not to init progress, and clobbering it here would
    // close a window that `whileReconfiguring` still believes is open - reopening the app to readers
    // in the middle of an import. It matters more on iOS than anywhere else, because iOS marks start
    // up done only once, early, and has no later start-up step that would put the state right again.
    override fun updateInitProgress(step: String, current: Int, total: Int) {
        _initProgressFlow.update { InitProgress(step = step, current = current, total = total, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun initCompleted() {
        _initProgressFlow.update { InitProgress(done = true, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun initFailed(error: String) {
        _initProgressFlow.update { InitProgress(done = true, error = error, reconfiguringDepth = it.reconfiguringDepth) }
    }

    override fun beginReconfiguring() {
        _initProgressFlow.update { it.enteringReconfigure() }
    }

    override fun endReconfiguring() {
        _initProgressFlow.update { it.leavingReconfigure() }
    }

    override fun showInitSnackbar(message: String) {
        _initSnackbarFlow.tryEmit(message)
    }

    override fun isDev(): Boolean = DEBUG

    /** Read from the file, the same as Android and the desktop do. */
    override fun isEngineeringMode(): Boolean = isEnabled(ExternalOptions.ENGINEERING_MODE)

    /**
     * The same rule as Android's `ConfigImpl`. A build that is not a release one may run the closed
     * loop only with the `engineering_mode` file in `extra`; `SafetyPlugin` reads this.
     */
    override fun isEngineeringModeOrRelease(): Boolean =
        if (!APS) true else isEngineeringMode() || !isDev()

    /**
     * Read the same way Android does - by the presence of a file in the `extra` folder.
     *
     * This answered a flat `false` before, on the grounds that iOS had nowhere for a user to put such
     * a file. That was true when it was written and stopped being true when the app's Documents
     * directory was exposed to the Files app: a person can now create
     * **On My iPhone → AAPS → AAPS → extra → engineering_mode** with no computer and no cable,
     * which is the same gesture as `touch AAPS/extra/engineering_mode` on the other two platforms.
     * The folder has a `README.txt` that lists every option file name.
     *
     * Checked on each call rather than cached, because the file appears while the app is running -
     * the user has no way to restart it first, and a value read once at start up would ignore them
     * until the next launch.
     */
    override fun isEnabled(option: ExternalOptions): Boolean {
        val directory = extraDir ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath("$directory/${option.filename}")
    }

    companion object {

        /** Used only where there is no app bundle to ask, which in practice means a test binary. */
        internal const val DEFAULT_BUNDLE_ID = "app.aaps.client"
    }
}

/**
 * The role of the iOS app, from the `AAPSRole` key of its Info.plist. Each Xcode target sets the key
 * through the `AAPS_ROLE` build setting.
 */
enum class IosAppRole {

    MASTER,
    CLIENT;

    companion object {

        /** The Info.plist key. */
        const val INFO_PLIST_KEY = "AAPSRole"

        /** The value of [INFO_PLIST_KEY] that makes the follower app. */
        const val CLIENT_VALUE = "aapsclient"

        /**
         * Only the exact value [CLIENT_VALUE] makes a follower. Anything else is the master, which is
         * what the app was before the key existed: a test binary has no bundle, and the master app
         * must not change because a key is missing.
         */
        fun fromValue(value: String?): IosAppRole =
            if (value?.trim()?.lowercase() == CLIENT_VALUE) CLIENT else MASTER

        fun fromBundle(): IosAppRole =
            fromValue(NSBundle.mainBundle.objectForInfoDictionaryKey(INFO_PLIST_KEY) as? String)
    }
}
