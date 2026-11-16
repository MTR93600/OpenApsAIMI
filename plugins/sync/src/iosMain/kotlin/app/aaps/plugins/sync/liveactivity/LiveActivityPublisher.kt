package app.aaps.plugins.sync.liveactivity

import app.aaps.core.interfaces.widget.WidgetStateExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides when the Live Activity gets a new state, and sends it to Swift.
 *
 * - Off ([LiveActivitySettings.enabled] false): nothing is sent, and every activity is ended.
 * - A new reading always goes out at once. Anything else (a loop result, a second trigger for the
 *   same reading) waits until 30 s after the last send ([LiveActivityThrottle]). It is not dropped:
 *   one delayed send builds a fresh state then, so the loop result a few seconds after a reading still
 *   reaches the lock screen.
 * - A refresh that Swift asks for, and switching on, go out at once.
 *
 * The state is built fresh at each send, so the age it carries is always the real one.
 *
 * Nothing is built before [appInitialized] says true: building the state reads the active pump, and
 * there is none while start up (or a settings import) is still electing the plugins. See
 * `HomeWidgetPublisher`, where this crashed the first launch of a fresh install.
 *
 * All state is changed under [lock]. Calls come from the plugin's collectors and from Swift.
 */
class LiveActivityPublisher(
    private val scope: CoroutineScope,
    private val export: WidgetStateExport,
    /** `Config.appInitializedFlow`: true once plugin state may be read. */
    private val appInitialized: Flow<Boolean>,
    private val settings: () -> LiveActivitySettings,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val companion: () -> LiveActivityCompanion? = { LiveActivityRegistry.companion },
) {

    private val lock = Mutex()
    private val throttle = LiveActivityThrottle()
    private var pending: Job? = null
    private var reportedMissingBridge = false

    private val callback = object : LiveActivityCallback {
        override fun refreshRequested() {
            request("swift", force = true)
        }
    }

    /** Hands the callback to Swift. When switched off, ends any activity left from before. */
    fun start() {
        val bridge = companion()
        if (bridge == null) {
            log("Live Activity bridge is not installed")
            return
        }
        bridge.start(callback)
        if (settings().enabled) request("start", force = true)
        else scope.launch { lock.withLock { endLocked() } }
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                pending?.cancel()
                pending = null
            }
        }
        companion()?.stop()
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (enabled) request("switch", force = true)
        else scope.launch { lock.withLock { endLocked() } }
    }

    /** A show flag changed. The user wants to see it now. */
    fun onShowChanged() {
        request("settings", force = true)
    }

    /** A new glucose value or a loop result. */
    fun request(reason: String, force: Boolean = false) {
        scope.launch { publish(reason, force, trailing = false) }
    }

    internal suspend fun publish(reason: String, force: Boolean, trailing: Boolean) {
        // Outside the lock, so that stop() and switching off are not held up while start up runs.
        appInitialized.first { it }
        publishLocked(reason, force, trailing)
    }

    private suspend fun publishLocked(reason: String, force: Boolean, trailing: Boolean): Unit = lock.withLock {
        if (trailing) pending = null
        val current = settings()
        if (!current.enabled) return@withLock
        val bridge = companion()
        if (bridge == null) {
            if (!reportedMissingBridge) {
                reportedMissingBridge = true
                log("Live Activity bridge is not installed, update skipped ($reason)")
            }
            return@withLock
        }
        val snapshot = export.export(withChart = current.showChart)
        val now = nowMs()
        val wait = throttle.waitBeforeSend(now, snapshot.bgTimestamp, force)
        if (wait > 0) {
            // One delayed send is enough: it builds a fresh state when it runs.
            if (pending?.isActive != true) {
                pending = scope.launch {
                    delay(wait)
                    publish("$reason, delayed", force = false, trailing = true)
                }
            }
            return@withLock
        }
        pending?.cancel()
        pending = null
        bridge.update(LiveActivityPayload.encode(snapshot.json, current))
        throttle.sent(now, snapshot.bgTimestamp)
    }

    private fun endLocked() {
        pending?.cancel()
        pending = null
        throttle.reset()
        companion()?.end()
    }
}
