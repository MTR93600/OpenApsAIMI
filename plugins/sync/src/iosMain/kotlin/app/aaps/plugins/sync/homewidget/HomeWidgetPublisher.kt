package app.aaps.plugins.sync.homewidget

import app.aaps.core.interfaces.widget.WidgetStateExport
import app.aaps.plugins.sync.liveactivity.LiveActivityPayload
import app.aaps.plugins.sync.liveactivity.LiveActivitySettings
import app.aaps.plugins.sync.liveactivity.LiveActivityThrottle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides when the iOS widgets get a new state, and hands it to Swift.
 *
 * The same timing as the Live Activity ([LiveActivityThrottle]): a new reading goes out at once,
 * anything else waits until 30 s after the last save and then goes out once, built fresh at that time.
 * On top of that, a state that is the same text as the last one saved is not saved again: iOS gives
 * an app only a small daily number of widget reloads in the background, and a reload that changes
 * nothing would use one up.
 *
 * Saving and reloading are two steps. Every saved state is written to the file, but WidgetKit is asked
 * to draw the widgets again only for the first save and for a new reading, and then at most once in
 * [MIN_RELOAD_INTERVAL_MS]. iOS gives an app in the background only about 40 to 70 reloads a day, and a
 * reload on every loop result and every reading of a 1 minute CGM would use them up early in the day.
 * A loop result alone is only written: the next reload (a new reading, or WidgetKit's own reread) shows
 * it. A 5 minute CGM still reloads on every reading, which can be more than iOS allows; when iOS
 * refuses, the widget turns stale on time by itself (see below), it never shows an old value as fresh.
 *
 * There is no switch. A widget is something the user adds on the home screen, and it must show data
 * then. Saving a small file costs little when no widget is shown.
 *
 * Safety: the widget may show the file long after it was saved (the app was frozen, or iOS refused a
 * reload). The JSON carries `bgTime` and `staleAt`, and the widget judges the age again at the time
 * it draws, so an old reading turns grey and struck out by itself.
 *
 * Nothing is built before [appInitialized] says true. Building the state reads the active pump (for
 * the IOB), and on a fresh install there is no pump until start up has elected the default one. The
 * first save at start used to run during that election and took the app down on its first launch
 * ("No pump selected"). The same wait covers a settings import, which has the same window.
 *
 * All state is changed under [lock]. Calls come from the plugin's collectors.
 */
class HomeWidgetPublisher(
    private val scope: CoroutineScope,
    private val export: WidgetStateExport,
    /** `Config.appInitializedFlow`: true once plugin state may be read. */
    private val appInitialized: Flow<Boolean>,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val companion: () -> HomeWidgetCompanion? = { HomeWidgetRegistry.companion },
) {

    private val lock = Mutex()
    private val throttle = LiveActivityThrottle()
    private var pending: Job? = null
    private var lastSaved: String? = null
    private var lastReloadAt: Long? = null
    private var lastReloadBg: Long? = null
    private var reportedMissingBridge = false

    /** Saves the current state at once, so a widget shows data soon after the app starts. */
    fun start() {
        request("start", force = true)
    }

    fun stop() {
        scope.launch {
            lock.withLock {
                pending?.cancel()
                pending = null
            }
        }
    }

    /** A new glucose value or a loop result. */
    fun request(reason: String, force: Boolean = false) {
        scope.launch { publish(reason, force, trailing = false) }
    }

    internal suspend fun publish(reason: String, force: Boolean, trailing: Boolean) {
        // Outside the lock, so that stop() is not held up while start up is still running.
        appInitialized.first { it }
        publishLocked(reason, force, trailing)
    }

    private suspend fun publishLocked(reason: String, force: Boolean, trailing: Boolean): Unit = lock.withLock {
        if (trailing) pending = null
        val bridge = companion()
        if (bridge == null) {
            if (!reportedMissingBridge) {
                reportedMissingBridge = true
                log("Home widget bridge is not installed, save skipped ($reason)")
            }
            return@withLock
        }
        val snapshot = export.export(withChart = true, withDetails = true)
        val now = nowMs()
        val wait = throttle.waitBeforeSend(now, snapshot.bgTimestamp, force)
        if (wait > 0) {
            // One delayed save is enough: it builds a fresh state when it runs.
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
        val text = LiveActivityPayload.encode(snapshot.json, SHOW_ALL)
        if (text == lastSaved) return@withLock
        val reload = shouldReload(now, snapshot.bgTimestamp)
        bridge.save(text, reload)
        lastSaved = text
        throttle.sent(now, snapshot.bgTimestamp)
        if (reload) {
            lastReloadAt = now
            lastReloadBg = snapshot.bgTimestamp
        }
    }

    /** The first save, or a new reading at least [MIN_RELOAD_INTERVAL_MS] after the last reload. */
    private fun shouldReload(now: Long, bgTimestamp: Long?): Boolean {
        val reloadAt = lastReloadAt ?: return true
        if (bgTimestamp == null || bgTimestamp == lastReloadBg) return false
        // A clock that went back must not block reloads for a long time.
        return now < reloadAt || now - reloadAt >= MIN_RELOAD_INTERVAL_MS
    }

    companion object {

        /**
         * The shortest time between two reloads. A reading held back waits for the next one, so the
         * value on screen can be up to this plus one CGM interval old. With a 5 minute CGM that must
         * stay below the 9 minute stale edge, or a working CGM would look stale.
         */
        const val MIN_RELOAD_INTERVAL_MS = 210_000L

        /**
         * The home widgets have no settings of their own. The flags are only there because Swift decodes
         * the file with the Live Activity struct, which requires them.
         */
        private val SHOW_ALL = LiveActivitySettings(enabled = true, showChart = true, showIobCob = true, showLoop = true)
    }
}
