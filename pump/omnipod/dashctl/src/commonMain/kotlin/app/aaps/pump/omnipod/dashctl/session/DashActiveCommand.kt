package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram

/**
 * What an active command asks the pod to do. Kept with the command, so the history can be written
 * once it is known whether the pod took it.
 *
 * Stored by ordinal (`DashPodStateCodec`): add new values at the end only.
 */
enum class DashCommandKind {
    BOLUS,
    TEMP_BASAL,
    STOP_TEMP_BASAL,
    STOP_BOLUS,
    STOP_ALL,
    BASAL_PROGRAM,
    OTHER,

    /**
     * Android `suspendDelivery` (`SuspendDeliveryCommand`: stop of all delivery and the "suspend
     * ended" alert), sent before a new basal program when delivery is not suspended yet.
     */
    SUSPEND_DELIVERY,
}

/**
 * A command that changes delivery, from before its bytes go until a pod status says whether the pod
 * took it. Ported from `OmnipodDashPodStateManager.ActiveCommand`.
 *
 * Android orders the events with `SystemClock.elapsedRealtime()`: `createdRealtime`, `sentRealtime`
 * and `lastStatusResponseReceived`. That clock starts again at 0 after a reboot while the command is
 * stored, so after a reboot the order is wrong. Here the order is a stored count of the status answers
 * ([DashPodState.statusResponseCount]): [statusCountAtCreation] is that count when the command was
 * made, so a status counted after it was made is a later one. The decisions are the same as Android's,
 * see [DashCommandConfirmation.of].
 *
 * @property sequence the message sequence number the command was built with (0..15). The pod reports
 *   the sequence number of the last programming command it took; the same number means it took this one.
 * @property sent true once the bytes may have reached the pod. It is set before the write, so a crash
 *   in the middle of the write leaves "maybe sent", never "not sent".
 * @property createdAtMs the wall clock (epoch ms) when the command was made, just before its bytes go.
 *   Android `HistoryRecord.createdAt`: Android records the start of a temp basal
 *   (`pumpSyncTempBasal`) and the stop of one (`handleCommandConfirmation`, CANCEL_TEMPORARY_BASAL) at
 *   this time, not at the time the outcome became known. An outcome decided late, maybe hours later,
 *   must still be recorded at this time, or a stopped zero temp basal is counted as running for those
 *   hours and the insulin on board is wrong. Stored (`DashPodStateCodec` version 4), so it survives a
 *   restart. Null only for a command stored by an older version: the time is then unknown.
 * @property basalProgram for [DashCommandKind.BASAL_PROGRAM]: the program sent, Android
 *   `ActiveCommand.basalProgram`. When the pod takes it, it becomes the running program
 *   ([DashPodState.basalProgram]). Stored (`DashPodStateCodec` version 4), so a program confirmed after
 *   a restart is still known. Null for every other kind.
 */
data class DashActiveCommand(
    val sequence: Int,
    val kind: DashCommandKind,
    val historyId: Long,
    val statusCountAtCreation: Long,
    val sent: Boolean = false,
    val requestedBolusUnits: Double? = null,
    val tempBasalRate: Double? = null,
    val tempBasalMinutes: Int? = null,
    val createdAtMs: Long? = null,
    val basalProgram: BasalProgram? = null,
)

/** A new delivery command while the last one is still open. Ask the pod for its status first. */
class DashCommandNotConfirmedException(message: String) : Exception(message)
