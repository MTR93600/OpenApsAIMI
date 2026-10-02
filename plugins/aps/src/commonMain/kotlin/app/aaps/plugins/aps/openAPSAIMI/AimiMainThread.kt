package app.aaps.plugins.aps.openAPSAIMI

/**
 * Says whether the calling thread is the platform's user interface thread.
 *
 * This exists for one caller:
 * [app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR]. That class reads heart rate
 * and step rows from the database with a blocking call, on purpose - see the note on its class
 * documentation. A blocking read on the user interface thread would freeze the screen, so the
 * provider asks this first and returns an empty list instead.
 *
 * The check itself is the one line that has to stay on the platform: Android has `Looper`, iOS has
 * `NSThread`, and a plain JVM has no user interface thread at all.
 *
 * @return `true` when this thread draws the user interface, so a blocking call must be avoided.
 */
expect fun aimiIsMainThread(): Boolean
