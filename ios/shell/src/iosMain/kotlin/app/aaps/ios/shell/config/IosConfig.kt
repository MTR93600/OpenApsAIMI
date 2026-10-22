package app.aaps.ios.shell.config

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ExternalOptions
import app.aaps.core.interfaces.configuration.InitProgress
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.core.keys.interfaces.TextRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/**
 * What kind of build this is, answered for iOS.
 *
 * On Android these values come from the Gradle flavour: `full`, `pumpcontrol`, `aapsclient`. iOS
 * builds one kind only: the **master** phone, the same role as the Android `full` flavour. It runs
 * the loop, talks to the pump and the sensor, and writes and uploads the therapy records.
 *
 * The Xcode targets are still called AAPSClient and AAPSClient2. That name comes from the first
 * iOS port, which was a follower. It does not decide the role any more; this class does.
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
     * `extra` inside the app's Documents directory, which the Files app shows as
     * **On My iPhone → AAPS**. That is the whole reason this can work at all: the folder is
     * reachable by the person using the phone, so `extra/engineering_mode` can be created by hand
     * there exactly as it is created in `AAPS/extra` on Android.
     */
    private val extraDir: String? = defaultExtraDir()
) : Config {

    override val SUPPORTED_NS_VERSION: Int = 150000

    /*
     * The values of the Android `full` flavour: the loop runs here (APS), pump drivers are offered
     * (PUMPDRIVERS), and this phone is not a follower (AAPSCLIENT false). The owner chose this on
     * 2026-10-22: the iPhone is a master phone, exactly like AAPS on Android.
     *
     * PUMPCONTROL stays false: that flavour means "pump remote control without a loop".
     */
    override val APS: Boolean = true
    override val PUMPCONTROL: Boolean = false
    override val PUMPDRIVERS: Boolean = true

    override val AAPSCLIENT: Boolean = false

    /** The name of the Android flavour with the same role, so shared code that compares it agrees. */
    override val FLAVOR: String = "full"

    override val AAPSCLIENT1: Boolean = false
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

    // Filled by the build on Android. iOS has no equivalent wired up yet, and saying so plainly is
    // better than inventing a commit hash.
    override val HEAD: String = "unknown"
    override val COMMITTED: Boolean = true
    override val REMOTE: String = "unknown"

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

    private val _initProgressFlow = MutableStateFlow(InitProgress(done = true))
    override val initProgressFlow: StateFlow<InitProgress> = _initProgressFlow.asStateFlow()

    private val _initSnackbarFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val initSnackbarFlow: SharedFlow<String> = _initSnackbarFlow.asSharedFlow()

    // Each of these replaces the whole value rather than copying it, which is how they were written
    // and what the start-up progress wants. The one field that must survive is the reconfigure depth:
    // it belongs to whoever opened the window, not to init progress, and clobbering it here would
    // close a window that `whileReconfiguring` still believes is open - reopening the app to readers
    // in the middle of an import. It matters more on iOS than anywhere else, because iOS starts at
    // `done = true` and has no start-up sequence that would put the state right again afterwards.
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
     * **On My iPhone → AAPS → extra → engineering_mode** with no computer and no cable, which
     * is the same gesture as `touch AAPS/extra/engineering_mode` on the other two platforms.
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
 * `extra` inside the app's own Documents directory, created if it is not there yet.
 *
 * Made rather than merely looked for, so the folder is visible in the Files app before anyone needs
 * it - a user told to create `extra/engineering_mode` should find `extra` already waiting rather
 * than have to guess the name and spelling.
 */
@OptIn(ExperimentalForeignApi::class)
private fun defaultExtraDir(): String? {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: return null
    val extra = "$documents/extra"
    NSFileManager.defaultManager.createDirectoryAtPath(extra, withIntermediateDirectories = true, attributes = null, error = null)
    return extra
}
