package app.aaps.pump.omnipod.dashctl.ios.plugin

import androidx.compose.ui.text.AnnotatedString
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A [NotificationManager] that keeps every post and dismiss as one line of text. */
internal class RecordingNotificationManager(private val rh: TextResolver) : NotificationManager {

    val calls = mutableListOf<String>()

    /** The buttons of the last post of each notice; a dismiss removes them. */
    val actions = mutableMapOf<NotificationId, List<NotificationAction>>()

    override val notifications: StateFlow<List<AapsNotification>> = MutableStateFlow(emptyList())

    override fun cleanUp() = Unit

    override fun post(
        id: NotificationId, text: String, level: NotificationLevel, validMinutes: Int, sound: AlarmSound?,
        actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        calls += "post($id, $text, sound=$sound)"
        return NotificationHandle(calls.size)
    }

    override fun post(
        id: NotificationId, text: String, level: NotificationLevel, date: Long, validTo: Long, sound: AlarmSound?,
        actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        calls += "post($id, $text, sound=$sound)"
        return NotificationHandle(calls.size)
    }

    override fun post(
        id: NotificationId, textRef: TextRef, level: NotificationLevel, validMinutes: Int, date: Long, validTo: Long,
        sound: AlarmSound?, actions: List<NotificationAction>, validityCheck: (() -> Boolean)?
    ): NotificationHandle {
        calls += "post($id, ${rh.gs(textRef)}, sound=$sound)"
        this.actions[id] = actions
        return NotificationHandle(calls.size)
    }

    override fun dismiss(id: NotificationId) {
        calls += "dismiss($id)"
        actions.remove(id)
    }

    override fun dismiss(handle: NotificationHandle) {
        calls += "dismiss(handle)"
    }

    override fun muteAllAlarms() = Unit

    fun posts(): List<String> = calls.filter { it.startsWith("post") }
}

/** Resolves a text to its name and its arguments, so a test reads which text was used without a string table. */
internal object NameTextResolver : TextResolver {

    override fun gs(ref: TextRef): String = when (ref) {
        is TextRef.Named      -> if (ref.args.isEmpty()) ref.name else "${ref.name}${ref.args}"
        is TextRef.Literal    -> ref.text
        is TextRef.AndroidRes -> "res${ref.id}"
    }

    override fun gs(ref: TextRef, vararg args: Any?): String = when (ref) {
        is TextRef.Named -> "${ref.name}${args.toList()}"
        else             -> gs(ref)
    }

    override fun gsNotLocalised(ref: TextRef): String = gs(ref)
}

/** A [UiInteraction] that keeps every alarm. */
internal class RecordingUiInteraction : UiInteraction {

    val alarms = mutableListOf<String>()

    override val mainActivity: KClass<*> get() = RecordingUiInteraction::class
    override val errorHelperActivity: KClass<*> get() = RecordingUiInteraction::class

    override fun runAlarm(status: String, title: String, sound: AlarmSound?) {
        alarms += "alarm($status, $title, sound=$sound)"
    }

    override fun stopAlarm(reason: String) = Unit
}

/** An [RxBus] that keeps every event. */
internal class RecordingRxBus : RxBus {

    val events = mutableListOf<Event>()

    override fun send(event: Event) {
        events += event
    }

    override fun <T : Event> toFlow(eventType: KClass<T>): Flow<T> = emptyFlow()
}

/**
 * A [CommandQueue] with only the calls the Dash plugin makes: each queued command and status read is
 * kept as one line, and [running], [size], [inQueue] say what the queue is doing. Any other call fails
 * the test.
 */
internal open class RecordingCommandQueue : CommandQueue {

    val queued = mutableListOf<String>()

    /** What [isRunning] answers. */
    var running: Command.CommandType? = null

    /** What [size] answers. */
    var size = 0

    /** The custom commands [isCustomCommandInQueue] says are waiting. */
    val inQueue = mutableSetOf<KClass<out CustomCommand>>()

    override var waitingForDisconnect: Boolean = false
    override fun isRunning(type: Command.CommandType): Boolean = running == type
    override fun pickup() = Unit
    override fun isHeld(): Boolean = false
    override suspend fun withHold(reason: String, timeout: Duration, block: suspend () -> Unit): Boolean = error("not used")
    override fun clear() = Unit
    override fun clear(comment: TextRef) = Unit
    override fun cancelAll(comment: TextRef, success: Boolean) = Unit
    override fun size(): Int = size
    override fun performing(): Command? = null
    override fun resetPerforming() = Unit
    override fun bolusInQueue(): Boolean = false
    override val smbBeingDelivered: StateFlow<Boolean> = MutableStateFlow(false)
    override fun dropWaitingSmb() = Unit
    override suspend fun bolus(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = error("not used")
    override fun cancelAllBoluses(id: Long?) = Unit
    override suspend fun stopPump(): PumpEnactResult = error("not used")
    override suspend fun startPump(): PumpEnactResult = error("not used")
    override suspend fun setTBROverNotification(enable: Boolean): PumpEnactResult = error("not used")
    override suspend fun tempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, profile: Profile, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        error("not used")

    override suspend fun tempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, profile: Profile, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        error("not used")

    override suspend fun extendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = error("not used")
    override suspend fun cancelTempBasal(enforceNew: Boolean, autoForced: Boolean): PumpEnactResult = error("not used")
    override suspend fun cancelExtended(): PumpEnactResult = error("not used")
    override suspend fun readStatus(reason: String): PumpEnactResult {
        queued += "readStatus($reason)"
        return DashPumpEnactResult(success = true, enacted = false)
    }

    override fun statusInQueue(): Boolean = false
    override suspend fun loadHistory(type: Byte): PumpEnactResult = error("not used")
    override suspend fun setUserOptions(): PumpEnactResult = error("not used")
    override suspend fun loadTDDs(): PumpEnactResult = error("not used")
    override suspend fun loadEvents(): PumpEnactResult = error("not used")
    override suspend fun clearAlarms(): PumpEnactResult = error("not used")
    override suspend fun deactivate(): PumpEnactResult = error("not used")
    override suspend fun updateTime(): PumpEnactResult = error("not used")
    override suspend fun customCommand(customCommand: CustomCommand): PumpEnactResult {
        queued += "customCommand(${customCommand.statusDescription})"
        return DashPumpEnactResult(success = true, enacted = false)
    }

    override fun isCustomCommandRunning(customCommandType: KClass<out CustomCommand>): Boolean = false
    override fun isCustomCommandInQueue(customCommandType: KClass<out CustomCommand>): Boolean = customCommandType in inQueue
    override fun statusAsAnnotated(): AnnotatedString = AnnotatedString("")
    override suspend fun isThisProfileSet(requestedProfile: EffectiveProfile): Boolean = error("not used")
}
