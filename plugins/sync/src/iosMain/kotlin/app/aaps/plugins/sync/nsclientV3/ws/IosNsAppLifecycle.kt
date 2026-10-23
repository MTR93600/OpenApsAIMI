package app.aaps.plugins.sync.nsclientV3.ws

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.nsclientV3.NSClientV3Plugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Checks the Nightscout connection each time the app comes back from the background.
 *
 * See `NSClientV3Plugin.onAppForeground` for what the check does. This class only measures how long
 * the app was away, which is what decides whether the socket can still be trusted.
 *
 * Started once by the iOS app, after the graph is built. It needs UIKit, so it cannot run in a test
 * binary; the timing rule is in [BackgroundTimer], which is tested.
 *
 * The plugin arrives as `() -> T` so building this class does not build the plugin.
 */
@SingleIn(AppScope::class)
@Inject
class IosNsAppLifecycle(
    private val aapsLogger: AAPSLogger,
    dateUtil: DateUtil,
    private val nsClientV3Plugin: () -> NSClientV3Plugin
) {

    private val timer = BackgroundTimer(dateUtil::now)
    private var watcher: IosForegroundWatcher? = null

    fun start() {
        if (watcher != null) return
        watcher = IosForegroundWatcher(
            aapsLogger = aapsLogger,
            onForeground = { timer.cameToForeground()?.let { awayMs -> nsClientV3Plugin().onAppForeground(awayMs) } },
            onBackground = { timer.wentToBackground() }
        ).also { it.start() }
    }
}

/**
 * How long the app was in the background, from the two lifecycle events.
 *
 * "Became active" also fires when the app was never in the background: at launch, and after the
 * control centre or a system dialog covered it. Those return null, because there is nothing to
 * check - the plugin's own start covers the launch.
 */
internal class BackgroundTimer(private val now: () -> Long) {

    private var backgroundSince: Long? = null

    fun wentToBackground() {
        backgroundSince = now()
    }

    /** The time spent in the background, or null when the app had not been there. */
    fun cameToForeground(): Long? {
        val since = backgroundSince ?: return null
        backgroundSince = null
        return (now() - since).coerceAtLeast(0L)
    }
}
