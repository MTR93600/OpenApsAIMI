package app.aaps.plugins.sync.tidepool.comm

import app.aaps.plugins.sync.garmin.runOnMain
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import kotlin.concurrent.AtomicReference

/**
 * Background time from iOS while Tidepool talks to the server, so an upload is not cut when the user
 * leaves the app. iOS decides how long it gives (about 30 s); the time asked for in [extend] is not
 * something iOS lets an app choose. It is not a wake lock: when the app is suspended later, the upload
 * waits for the next time the app runs.
 *
 * UIKit is only touched on the main queue, never with `dispatch_sync`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosTidepoolWakeLock : TidepoolWakeLock {

    private val task = AtomicReference(UIBackgroundTaskInvalid)

    override fun extend(ms: Long) = runOnMain {
        if (task.value != UIBackgroundTaskInvalid) return@runOnMain
        // The expiration handler must end the task, or iOS ends the app
        task.value = UIApplication.sharedApplication.beginBackgroundTaskWithName("tidepool-upload") { end() }
    }

    override fun release() = runOnMain { end() }

    private fun end() {
        val current = task.getAndSet(UIBackgroundTaskInvalid)
        if (current != UIBackgroundTaskInvalid) UIApplication.sharedApplication.endBackgroundTask(current)
    }
}
