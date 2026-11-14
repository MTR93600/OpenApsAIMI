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

    /**
     * Android `resumeDelivery` (RESUME_DELIVERY): the basal program sent to a suspended pod. When the
     * pod takes it, it becomes the running program, as [BASAL_PROGRAM].
     */
    RESUME_DELIVERY,

    /** Android `silenceAlerts` (ACKNOWLEDGE_ALERTS, `SilenceAlertsCommand`). */
    SILENCE_ALERTS,

    /** Android `programAlerts` (CONFIGURE_ALERTS, `ProgramAlertsCommand`). */
    PROGRAM_ALERTS,

    /** Android `playBeep` (PLAY_TEST_BEEP, `ProgramBeepsCommand`). */
    PROGRAM_BEEPS,

    /** Android `deactivatePod` (DEACTIVATE_POD, `DeactivateCommand`): the pod stops all delivery for good. */
    DEACTIVATE,
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
 * @property basalProgram for [DashCommandKind.BASAL_PROGRAM] and [DashCommandKind.RESUME_DELIVERY]: the program sent, Android
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
    /**
     * A [DashCommandKind.BOLUS] that is a basal correction (Android `BolusRecord` with
     * `BolusType.BASAL_CORRECTION`): never written to `PumpSync` as a bolus, and its pulses count as
     * basal (`DashPodStateManager.basalCorrectionInProgress`).
     */
    val basalCorrection: Boolean = false,
)

/** A new delivery command while the last one is still open. Ask the pod for its status first. */
class DashCommandNotConfirmedException(message: String) : Exception(message)

/**
 * Whether [command], when nobody knows if the pod took it, is counted as taken. Port of OmnipodKit
 * (Trio) `PodState.resolveAnyPendingCommandWithUncertainty`: "assume commands failed/succeeded in the
 * direction of positive net delivery", so insulin on board is never lower than what the pod may give.
 *
 * Deliberate difference from Android, which counts a temp basal and a suspend as taken as soon as they
 * are sent, also a zero temp basal or a suspend the pod never got: the basal it then gives is counted
 * as not given (insulin counted wrong), and the loop may dose on top of it.
 *
 * - BOLUS: taken (more insulin).
 * - TEMP_BASAL: not taken when its rate is below the scheduled rate ([scheduledBasalRate]); a zero
 *   temp basal always. Taken otherwise, also when the scheduled rate is not known (Android).
 * - SUSPEND_DELIVERY: not taken (the basal goes on).
 * - STOP_TEMP_BASAL: taken only when it stops a temp basal below the scheduled rate (the basal comes
 *   back). Not taken when it stops a high one (the high rate goes on), nor when the temp basal it stops
 *   or the scheduled rate is not known: nothing is written, as today.
 * - RESUME_DELIVERY, BASAL_PROGRAM: taken (OmnipodKit `.resume`: the basal starts again), so a pod
 *   discarded with one open ends the suspend at the command time. Before this, AAPS kept counting
 *   0 U/h while the pod may have given the scheduled basal (insulin counted too low). Not taken when
 *   a temp basal not below the schedule still runs on the pod: ending it would count less (as a stop).
 * - any other kind: false (nothing is written for it while its outcome is not known).
 *
 * @param runningTempBasal the temp basal that runs when the command is made, the one a stop ends.
 */
fun dashCountAsTakenWhileUnknown(command: DashActiveCommand, scheduledBasalRate: Double?, runningTempBasal: DashTempBasal?): Boolean =
    when (command.kind) {
        DashCommandKind.BOLUS            -> true
        DashCommandKind.TEMP_BASAL       -> {
            val rate = command.tempBasalRate
            when {
                rate == null               -> true
                rate == 0.0                -> false
                scheduledBasalRate == null -> true
                else                       -> rate >= scheduledBasalRate
            }
        }

        DashCommandKind.SUSPEND_DELIVERY -> false
        DashCommandKind.STOP_TEMP_BASAL  -> stopsLowTempBasal(scheduledBasalRate, runningTempBasal)

        DashCommandKind.RESUME_DELIVERY,
        DashCommandKind.BASAL_PROGRAM    -> runningTempBasal == null || stopsLowTempBasal(scheduledBasalRate, runningTempBasal)

        else                             -> false
    }

/** True when [runningTempBasal] is known to be below [scheduledBasalRate] (a zero one always): ending it gives more insulin. */
private fun stopsLowTempBasal(scheduledBasalRate: Double?, runningTempBasal: DashTempBasal?): Boolean {
    val running = runningTempBasal?.rate
    return when {
        running == null            -> false
        running == 0.0             -> true
        scheduledBasalRate == null -> false
        else                       -> running < scheduledBasalRate
    }
}
