package app.aaps.plugins.aps.openAPSAIMI

import platform.Foundation.NSThread

/**
 * iOS half of [aimiIsMainThread], on `NSThread`.
 *
 * `NSThread.isMainThread` is the direct counterpart of the Android `Looper` check: on iOS the main
 * thread is the one that draws the user interface.
 */
actual fun aimiIsMainThread(): Boolean = NSThread.isMainThread()
