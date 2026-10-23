package app.aaps.plugins.sync.nsclientV3.ws

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObject

/**
 * Tells the Nightscout code when the app goes to the background and when it comes back.
 *
 * Android keeps the socket alive with a wake lock in a sticky service. iOS has no counterpart: a
 * backgrounded app is suspended, and its websocket with it. Started by `IosNsAppLifecycle`, which
 * turns the two events into a check of the connection when the app comes back.
 *
 * ## Nothing is closed on the way out
 *
 * [onBackground] only records the moment. Closing the socket there would gain little and lose a
 * lot: AAPS often keeps running in the background for Bluetooth (pump, sensor), and then the socket
 * still works. When iOS does suspend the app, the socket is rebuilt on the way back in, which is
 * where the stale state is visible and can be fixed.
 *
 * Both callbacks run on the main queue.
 */
class IosForegroundWatcher(
    private val aapsLogger: AAPSLogger,
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit
) {

    private val observers = mutableListOf<NSObject>()

    fun start() {
        if (observers.isNotEmpty()) return
        observe(UIApplicationDidBecomeActiveNotification) {
            aapsLogger.debug(LTag.NSCLIENT, "App became active")
            onForeground()
        }
        observe(UIApplicationDidEnterBackgroundNotification) {
            aapsLogger.debug(LTag.NSCLIENT, "App went to the background")
            onBackground()
        }
    }

    fun stop() {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
    }

    private fun observe(name: String?, action: () -> Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { action() }
        )
        observers.add(observer as NSObject)
    }
}
