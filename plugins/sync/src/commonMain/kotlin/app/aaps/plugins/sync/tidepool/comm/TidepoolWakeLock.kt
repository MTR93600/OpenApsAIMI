package app.aaps.plugins.sync.tidepool.comm

/**
 * Keeps the phone running while Tidepool talks to the server, so an upload or a purge is not cut half
 * way when the screen goes off. Android holds a partial wake lock; iOS asks for background time.
 */
interface TidepoolWakeLock {

    /** Hold the phone awake for [ms] from now, replacing the time asked before. */
    fun extend(ms: Long)

    /** Let the phone sleep again. */
    fun release()
}
