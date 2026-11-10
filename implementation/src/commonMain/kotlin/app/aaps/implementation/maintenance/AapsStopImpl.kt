package app.aaps.implementation.maintenance

import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.maintenance.AapsStop
import app.aaps.core.interfaces.maintenance.StopsWithAaps
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.CoreUiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AapsStop], the same on Android and iPhone.
 *
 * The state is one stored key, [BooleanNonKey.AapsStoppedByUser], and every place that must obey it
 * reads that key: the pump command executor, the loop constraint in `SafetyPlugin`, the keep-alive
 * checks, the pump unreachable and missed readings alarms, and the sensor plugins when they start.
 * So the stop also holds after a restart, before this class is even built.
 *
 * This class only changes the key and does the one-off work around it: drop the waiting pump
 * commands, let the sensors go, and show a card that says AAPS is stopped. It does not disconnect the
 * pump itself: a command may be running, maybe a bolus, and cutting it would be worse than letting it
 * end. The executor disconnects once that command is done, see `CommandExecutor`.
 *
 * [activePlugin] and [commandQueue] are providers: the sensor plugins are part of the plugin list,
 * so asking for them directly would build a cycle.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AapsStopImpl(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val notificationManager: NotificationManager,
    private val uel: UserEntryLogger,
    private val activePlugin: () -> ActivePlugin,
    private val commandQueue: () -> CommandQueue,
    appScope: CoroutineScope
) : AapsStop {

    private val _isStopped = MutableStateFlow(preferences.get(BooleanNonKey.AapsStoppedByUser))
    override val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()

    /** One change at a time: a stop and a resume must not mix their steps. */
    private val mutex = Mutex()

    init {
        // After a restart the card is gone, but the stop is not. Show it again.
        if (_isStopped.value) appScope.launch { showStoppedCard() }
    }

    override suspend fun stop(source: Sources) = mutex.withLock {
        if (_isStopped.value) return@withLock
        // The key first: from here on the executor and the loop refuse, whatever happens below.
        preferences.put(BooleanNonKey.AapsStoppedByUser, true)
        _isStopped.value = true
        aapsLogger.info(LTag.CORE, "AAPS stopped by the user, source=$source")
        uel.log(Action.EXIT_AAPS, source, note = rh.gs(CoreUiStrings.aaps_stopped_short))
        step("drop waiting pump commands") { commandQueue().cancelAll(CoreUiStrings.aaps_stopped_short, success = false) }
        stopsWithAaps().forEach { plugin -> step("stop ${plugin::class.simpleName}") { plugin.onAapsStop() } }
        showStoppedCard()
    }

    override suspend fun resume(source: Sources) = mutex.withLock {
        if (!_isStopped.value) return@withLock
        preferences.put(BooleanNonKey.AapsStoppedByUser, false)
        _isStopped.value = false
        aapsLogger.info(LTag.CORE, "AAPS started again by the user, source=$source")
        uel.log(Action.START_AAPS, source)
        notificationManager.dismiss(NotificationId.AAPS_STOPPED)
        stopsWithAaps().forEach { plugin -> step("resume ${plugin::class.simpleName}") { plugin.onAapsResume() } }
        // Reach the pump again now, rather than at the next loop or keep-alive run.
        step("read pump status") { commandQueue().readStatus(rh.gs(CoreUiStrings.maintenance_resume_aaps)) }
    }

    private fun showStoppedCard() {
        notificationManager.post(
            NotificationId.AAPS_STOPPED,
            CoreUiStrings.aaps_stopped_notification,
            validityCheck = { preferences.get(BooleanNonKey.AapsStoppedByUser) }
        )
    }

    /** The enabled plugins that hold a radio link of their own, see [StopsWithAaps]. */
    private fun stopsWithAaps(): List<StopsWithAaps> =
        activePlugin().getPluginsList().filter { it is StopsWithAaps && it.isEnabled() }.map { it as StopsWithAaps }

    /** One step on its own: a step that throws must not skip the others. A cancellation still goes up. */
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            aapsLogger.error(LTag.CORE, "AAPS stop/resume: '$name' failed: ${t.message}", t)
        }
    }
}
