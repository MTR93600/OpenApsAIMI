package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashCommandOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.DashUntrackedBolus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.session.dashCountAsTakenWhileUnknown
import kotlin.concurrent.Volatile
import kotlinx.coroutines.runBlocking

/**
 * Writes what the pod was asked and what it did to `PumpSync`. Port of the `PumpSync` part of Android
 * `OmnipodDashPumpPlugin`: `pumpSyncBolusStart`, `pumpSyncTempBasal` (also from
 * `suspendDeliveryIfActive`), `handleCommandConfirmation` and the bolus part of `checkPodKaput`.
 *
 * ## When a record is written: as Android
 *
 * A bolus and a temp basal are written when the command is SENT ([onCommandSent], the receiver of
 * `IosDashPump.onCommandSent`, Android `isCommandSent()`), before the answer of the pod is read; a
 * temp basal below the scheduled rate and a suspend are not (see the second deliberate difference). The confirmation then keeps, corrects or takes them back
 * ([record], the receiver of `IosDashPump.onCommandDecided`), at once or late:
 *
 * | Outcome                                  | Record (all at the command time, pump id = command time)   |
 * |------------------------------------------|------------------------------------------------------------|
 * | BOLUS sent                               | bolus at the asked amount (`syncBolusWithPumpId`)          |
 * | TEMP_BASAL sent, not below the schedule  | temp basal (`syncTemporaryBasalWithPumpId`)                |
 * | TEMP_BASAL below the schedule, SUCCESS   | that temp basal, written then (at the command time)        |
 * | SUSPEND_DELIVERY SUCCESS                 | PUMP_SUSPEND temp basal of 0 U/h, 80 h, written then       |
 * | BOLUS DENIED                             | the bolus record set to 0 U, type kept (Android)           |
 * | TEMP_BASAL, SUSPEND_DELIVERY DENIED      | `invalidateTemporaryBasalWithPumpId` (Android)             |
 * | STOP_TEMP_BASAL, BASAL_PROGRAM, RESUME_DELIVERY SUCCESS | `syncStopTemporaryBasalWithPumpId`          |
 * | an outcome with `endedBolus`             | the bolus record corrected to what went in                 |
 * | [DashBolusEndedByFault]                  | the bolus record corrected to what went in                 |
 *
 * A correction always uses the pump id of the bolus record, so `PumpSync` updates that record: one
 * record per bolus, never a second one. A SUCCESS that is decided late (after the call returned a
 * failure) writes nothing more: the record of the send is already there, as on Android.
 *
 * ## The one deliberate difference: a record that the send did not write
 *
 * Android writes nothing when the write of the command threw (no `CommandSent` event) and then
 * clears the command as a send failure, so a bolus the pod may have taken is never counted. The iOS
 * driver keeps such a command open until a status decides it (it may have reached the pod). Also, the
 * app may stop between the write of the command and the write of the record. In both cases the
 * record of the send is missing, and a later SUCCESS (or a pod discarded while it is open) would
 * count no insulin that went in. So a SUCCESS, or a `podDiscarded` outcome, of a BOLUS, TEMP_BASAL
 * or SUSPEND_DELIVERY whose record was not written by this run writes it then, with the same call,
 * time and pump id as the send would have. When the record does exist (written before an app
 * restart), `PumpSync` finds it by its pump id and updates it with the same values: still one record.
 *
 * ## The second deliberate difference: assume more insulin, as OmnipodKit
 *
 * Android counts a temp basal and a suspend as taken as soon as they are sent. A zero or low temp basal,
 * or a suspend, the pod never got then counts basal the pod gives as not given (insulin counted wrong),
 * and the loop may dose on top of it. As OmnipodKit (Trio) `PodState.resolveAnyPendingCommandWithUncertainty`
 * ("assume commands failed/succeeded in the direction of positive net delivery"), a command whose
 * outcome is not known is counted as taken only when that gives more insulin ([countAsTakenWhileUnknown],
 * see [dashCountAsTakenWhileUnknown]):
 * - a temp basal below the scheduled rate and a suspend are not written when sent, nor when the pod is
 *   discarded with them open. Their SUCCESS writes them, at the command time with the same pump id, as
 *   the late path above;
 * - a cancel of a high temp basal discarded open writes nothing (the high rate stays counted), as
 *   before; a cancel of a low temp basal discarded open writes the stop at its command time;
 * - a resume or a basal program discarded open writes the stop at its command time (the basal may run
 *   again, OmnipodKit `.resume`), unless a temp basal not below the scheduled rate still runs;
 * - a bolus and a temp basal that is not below the scheduled rate stay written when sent, as Android.
 *
 * ## An untracked bolus
 *
 * [DashUntrackedBolus] (OmnipodKit `updateDeliveryStatus`): one bolus record of the units left, at the
 * time the pod reported it, with that time as the pump id, so a second write after a crash updates the
 * same record. Only when `PumpSync` took it is [DashUntrackedBolus.onWritten] called, which clears the
 * mark in the pod state. A write that throws leaves the mark, and the next status writes it again:
 * without that the bolus would never be counted (insulin counted too low).
 *
 * ## The pump id
 *
 * Android uses the id of its pod history record. iOS has no pod history yet, so the pump id is the
 * command time ([DashActiveCommand.createdAtMs]): it is stored with the open command, so the same id
 * comes back after a restart, and a correction finds the record of its bolus by it
 * ([DashLastBolus.createdAtMs] is the same time). Pump type and serial are Android's:
 * [PumpType.OMNIPOD_DASH] and the unique id of the pod ([serialNumber]).
 *
 * ## The type of a bolus and of a temp basal
 *
 * The driver does not store them. [beginBolus] and [beginTempBasal] give them for the call that sends
 * now, and the record of the send is written with them, as Android passes `detailedBolusInfo.bolusType`
 * and `tbrType`. [endCommand] keeps the type of a command the call left open, for the rare record a
 * late outcome must still write (see above). A correction passes no type (`PumpSync` keeps the stored
 * one), and a record written after an app restart passes none either (NORMAL when new).
 *
 * Calls come from inside the command lock of the driver, on the queue thread. `PumpSync` is suspend,
 * so [onCommandSent] and [onOutcome] block that thread until the record is written, as Android writes
 * inside its command chain.
 */
class DashPumpSyncRecorder(
    private val pumpSync: PumpSync,
    private val aapsLogger: AAPSLogger,
    /**
     * Whether a command whose outcome is not known is counted as taken: on the phone
     * `DashPodStateManager.countAsTakenWhileUnknown`. See the second deliberate difference above.
     */
    private val countAsTakenWhileUnknown: (DashActiveCommand) -> Boolean,
    /** Android `serialNumber()`: the unique id of the pod, "4241" without one. Read when a record is written. */
    private val serialNumber: () -> String,
) {

    /** The type of the bolus `deliverTreatment` is sending now, null outside it. */
    @Volatile
    private var currentBolusType: BS.Type? = null

    /** The type of the temp basal `setTempBasalAbsolute` is sending now, null outside it. */
    @Volatile
    private var currentTempBasalType: PumpSync.TemporaryBasalType? = null

    /** Bolus types of commands still open after their call, by command time. In memory only. */
    private val openBolusTypes = mutableMapOf<Long, BS.Type>()

    /** Temp basal types of commands still open after their call, by command time. In memory only. */
    private val openTempBasalTypes = mutableMapOf<Long, PumpSync.TemporaryBasalType>()

    /** The command times whose record of the send was written in this run and not decided yet. */
    private val writtenAtSend = mutableSetOf<Long>()

    /** `deliverTreatment` starts: a bolus of [type] may be sent. */
    fun beginBolus(type: BS.Type) {
        currentBolusType = type
    }

    /** `setTempBasalAbsolute` starts: a temp basal of [type] may be sent. */
    fun beginTempBasal(type: PumpSync.TemporaryBasalType) {
        currentTempBasalType = type
    }

    /**
     * The call that sent a command returned. When [stillOpen] is a bolus or a temp basal that is not
     * confirmed, its type is kept for a late outcome. The caller passes only a command that this call
     * made (`IosDashPluginPump`), and a type that is already kept is never replaced: a refused call must
     * not give its own type to the earlier command that is still open.
     */
    fun endCommand(stillOpen: DashActiveCommand?) {
        val at = stillOpen?.createdAtMs
        if (at != null) {
            when (stillOpen.kind) {
                DashCommandKind.BOLUS      -> currentBolusType?.let { if (at !in openBolusTypes) openBolusTypes[at] = it }
                DashCommandKind.TEMP_BASAL -> currentTempBasalType?.let { if (at !in openTempBasalTypes) openTempBasalTypes[at] = it }
                else                       -> Unit
            }
        }
        currentBolusType = null
        currentTempBasalType = null
    }

    /** The receiver of `IosDashPump.onCommandSent`. Blocks until the record is written. */
    fun onCommandSent(command: DashActiveCommand) {
        try {
            runBlocking { recordSent(command) }
        } catch (e: Exception) {
            // Never break the command: it is on its way to the pod. The outcome writes it if it is missing.
            aapsLogger.error(LTag.PUMP, "Dash: could not write the sent $command to PumpSync", e)
        }
    }

    /** The receiver of `IosDashPump.onCommandDecided`. Blocks until the record is written. */
    fun onOutcome(outcome: DashPumpOutcome) {
        try {
            runBlocking { record(outcome) }
        } catch (e: Exception) {
            // Never break the command that decided it: the pod state is already written.
            aapsLogger.error(LTag.PUMP, "Dash: could not write $outcome to PumpSync", e)
        }
    }

    /**
     * Android `isCommandSent()`: `pumpSyncBolusStart` for a bolus, `pumpSyncTempBasal` for a temp basal.
     * Nothing for any other command, and nothing for a temp basal or a suspend that is not counted as
     * taken while its outcome is not known ([countAsTakenWhileUnknown]; Android writes a suspend here,
     * 0 U/h, `MAX_POD_LIFETIME`, PUMP_SUSPEND): their SUCCESS writes them.
     */
    suspend fun recordSent(command: DashActiveCommand) {
        if (command.basalCorrection) {
            // Android never announces a basal correction to AAPS as a started bolus (no pumpSyncBolusStart).
            aapsLogger.info(LTag.PUMP, "Dash: basal correction sent, not written to PumpSync: $command")
            return
        }
        val at = command.createdAtMs
        if (at == null) {
            aapsLogger.error(LTag.PUMP, "Dash: sent command without its time, not written: $command")
            return
        }
        if (command.kind in RECORDED_AT_SEND && !countAsTakenWhileUnknown(command)) {
            aapsLogger.info(LTag.PUMP, "Dash: less insulin if taken, written only when the pod confirms it: $command")
            return
        }
        val written = when (command.kind) {
            DashCommandKind.BOLUS            -> writeBolus(command, at, currentBolusType ?: BS.Type.NORMAL)
            DashCommandKind.TEMP_BASAL       -> writeTempBasal(command, at, currentTempBasalType ?: PumpSync.TemporaryBasalType.NORMAL)
            DashCommandKind.SUSPEND_DELIVERY -> writeSuspend(at)
            else                             -> return
        }
        // Tried, whatever PumpSync answered: Android does not write it again either.
        if (written != null) writtenAtSend += at
    }

    /** Writes one outcome, see the table in the class comment. */
    suspend fun record(outcome: DashPumpOutcome) {
        when (outcome) {
            is DashBolusEndedByFault -> correctBolus(outcome.bolus, "pod fault ${outcome.alarmType}")
            is DashCommandOutcome    -> recordCommand(outcome)
            is DashUntrackedBolus    -> writeUntrackedBolus(outcome)
        }
    }

    private suspend fun recordCommand(outcome: DashCommandOutcome) {
        val command = outcome.command
        if (command.basalCorrection) {
            // Android: a basal correction has no BS.Type, so neither its denial nor its stop is synced.
            aapsLogger.info(LTag.PUMP, "Dash: basal correction ${outcome.confirmation}, not written to PumpSync")
            return
        }
        val at = command.createdAtMs
        if (at == null) {
            if (command.kind != DashCommandKind.OTHER) aapsLogger.error(LTag.PUMP, "Dash: command without its time (old stored state), not written: $outcome")
            outcome.endedBolus?.let { correctBolus(it, "stop ${command.kind}") }
            return
        }
        val discardedOpen = outcome.confirmation == DashCommandConfirmation.NOT_CONFIRMED && outcome.podDiscarded
        when (outcome.confirmation) {
            DashCommandConfirmation.SUCCESS -> when (command.kind) {
                DashCommandKind.BOLUS,
                DashCommandKind.TEMP_BASAL,
                DashCommandKind.SUSPEND_DELIVERY -> writeIfNotWrittenAtSend(command, at, "confirmed", inThisCall = !outcome.decidedLate)

                DashCommandKind.STOP_TEMP_BASAL,
                DashCommandKind.BASAL_PROGRAM,
                DashCommandKind.RESUME_DELIVERY  -> recordStopTempBasal(at, command.kind)

                else                             -> Unit
            }

            DashCommandConfirmation.DENIED  -> when (command.kind) {
                DashCommandKind.BOLUS            -> denyBolus(at)
                DashCommandKind.TEMP_BASAL,
                DashCommandKind.SUSPEND_DELIVERY -> invalidateTempBasal(at, command.kind)

                else                             -> Unit
            }

            else                            -> if (discardedOpen && countAsTakenWhileUnknown(command)) {
                // Android keeps what it wrote when it sent the command. Only what gives more insulin
                // is counted (OmnipodKit): a bolus, a temp basal not below the schedule, a stop of a low
                // one, a resume or basal program (the basal may run again).
                when (command.kind) {
                    in RECORDED_AT_SEND              -> writeIfNotWrittenAtSend(command, at, "pod discarded", inThisCall = false)
                    DashCommandKind.STOP_TEMP_BASAL,
                    DashCommandKind.RESUME_DELIVERY,
                    DashCommandKind.BASAL_PROGRAM    -> recordStopTempBasal(at, command.kind)
                    else                             -> Unit
                }
            }
        }
        if (outcome.confirmation.isFinal || discardedOpen) {
            writtenAtSend -= at
            openBolusTypes.remove(at)
            openTempBasalTypes.remove(at)
        }
        outcome.endedBolus?.let { correctBolus(it, "stop ${command.kind}") }
    }

    /**
     * The deliberate differences of the class comment: the record of the send, when this run did not
     * write it. [inThisCall]: the outcome was decided inside the call that sent the command, so its
     * type is the one of the call now.
     */
    private suspend fun writeIfNotWrittenAtSend(command: DashActiveCommand, at: Long, why: String, inThisCall: Boolean) {
        if (at in writtenAtSend) return
        aapsLogger.warn(LTag.PUMP, "Dash: $why command has no record of its send in this run, written now: $command")
        when (command.kind) {
            DashCommandKind.BOLUS            -> writeBolus(command, at, openBolusTypes[at] ?: currentBolusType.takeIf { inThisCall })
            DashCommandKind.TEMP_BASAL       -> writeTempBasal(command, at, openTempBasalTypes[at] ?: currentTempBasalType.takeIf { inThisCall })
            DashCommandKind.SUSPEND_DELIVERY -> writeSuspend(at)
            else                             -> Unit
        }
    }

    /** Android `pumpSyncBolusStart`. Returns what `PumpSync` answered, null when nothing could be written. */
    private suspend fun writeBolus(command: DashActiveCommand, at: Long, type: BS.Type?): Boolean? {
        val units = command.requestedBolusUnits
        if (units == null || units <= 0.0) {
            aapsLogger.error(LTag.PUMP, "Dash: bolus without its amount, not written: $command")
            return null
        }
        val created = pumpSync.syncBolusWithPumpId(
            timestamp = at,
            amount = PumpInsulin(units),
            type = type,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync bolus $units U $type at $at: $created")
        return created
    }

    /** Android `pumpSyncTempBasal` with the type of the call. */
    private suspend fun writeTempBasal(command: DashActiveCommand, at: Long, type: PumpSync.TemporaryBasalType?): Boolean? {
        val rate = command.tempBasalRate
        val minutes = command.tempBasalMinutes
        if (rate == null || minutes == null) {
            aapsLogger.error(LTag.PUMP, "Dash: temp basal without its rate or duration, not written: $command")
            return null
        }
        val created = pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = at,
            rate = PumpRate(rate),
            duration = minutes * MS_PER_MINUTE,
            isAbsolute = true,
            type = type,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync temp basal $rate U/h $minutes min $type at $at: $created")
        return created
    }

    /**
     * A [DashUntrackedBolus]: one record of the units left, keyed by the time the pod reported it. A bolus
     * that has already ended (a fault ended it while an earlier write failed) is written with what went
     * in, so this retry does not undo the correction. [DashUntrackedBolus.onWritten] only after
     * `PumpSync` returned: a write that throws keeps the mark, see the class comment.
     */
    private suspend fun writeUntrackedBolus(outcome: DashUntrackedBolus) {
        val bolus = outcome.bolus
        val at = bolus.createdAtMs
        val units = if (bolus.deliveryComplete) bolus.deliveredUnits() else bolus.requestedUnits
        if (at == null || units == null) {
            aapsLogger.error(LTag.PUMP, "Dash: untracked bolus without its time or amount, not written: $bolus")
            return
        }
        if (units <= 0.0) {
            // It ended before any of it went in: there is nothing to count.
            aapsLogger.info(LTag.PUMP, "Dash: untracked bolus ended with nothing delivered, not written: $bolus")
            outcome.onWritten()
            return
        }
        val created = pumpSync.syncBolusWithPumpId(
            timestamp = at,
            amount = PumpInsulin(units),
            type = BS.Type.NORMAL,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync untracked bolus $units U at $at: $created")
        outcome.onWritten()
    }

    /** Android `suspendDeliveryIfActive`: `pumpSyncTempBasal(0.0, MAX_POD_LIFETIME, PUMP_SUSPEND)`. */
    private suspend fun writeSuspend(at: Long): Boolean {
        val created = pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = at,
            rate = PumpRate(0.0),
            duration = PodConstants.MAX_POD_LIFETIME.inWholeMilliseconds,
            isAbsolute = true,
            type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync suspend (PUMP_SUSPEND temp basal) at $at: $created")
        return created
    }

    /** Android `handleCommandConfirmation` SET_BOLUS denied: the record of the send set to 0 U, type kept. */
    private suspend fun denyBolus(at: Long) {
        val result = pumpSync.syncBolusWithPumpId(
            timestamp = at,
            amount = PumpInsulin(0.0),
            type = null,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync bolus at $at denied by the pod, set to 0 U: $result")
    }

    /** Android `handleCommandConfirmation` SET_TEMPORARY_BASAL and SUSPEND_DELIVERY denied. */
    private suspend fun invalidateTempBasal(at: Long, kind: DashCommandKind) {
        val result = pumpSync.invalidateTemporaryBasalWithPumpId(at, PumpType.OMNIPOD_DASH, serialNumber())
        aapsLogger.info(LTag.PUMP, "Dash PumpSync temp basal at $at invalidated ($kind denied by the pod): $result")
    }

    /**
     * Android `handleCommandConfirmation` on CANCEL_TEMPORARY_BASAL, RESUME_DELIVERY and
     * SET_BASAL_PROFILE: `syncStopTemporaryBasalWithPumpId` at the command time.
     */
    private suspend fun recordStopTempBasal(at: Long, kind: DashCommandKind) {
        val done = pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = at,
            endPumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync stop temp basal ($kind) at $at: $done")
    }

    /**
     * Android CANCEL_BOLUS and `checkPodKaput`: `syncBolusWithPumpId` with the delivered units and the
     * same pump id, which updates the record of the bolus (one record, never a second bolus). The type
     * is left as it is (null: `PumpSync` keeps the stored type; Android passes the stored type).
     */
    private suspend fun correctBolus(bolus: DashLastBolus, reason: String) {
        if (bolus.basalCorrection) {
            // Android `bolusType.toBolusInfoBolusType()` is null for BASAL_CORRECTION: not synced.
            aapsLogger.info(LTag.PUMP, "Dash: basal correction ended ($reason), not written to PumpSync: $bolus")
            return
        }
        val delivered = bolus.deliveredUnits()
        if (delivered == null) {
            aapsLogger.error(LTag.PUMP, "Dash: bolus not ended, no correction ($reason): $bolus")
            return
        }
        val pumpId = bolus.createdAtMs
        if (pumpId == null) {
            // A bolus stored by codec version 3 or before: no record of it is known. Every record this
            // run wrote belongs to a command with a time, so to another bolus, and must not be changed.
            // The bolus stays counted at the asked amount (more than went in, never less), the side
            // Android also falls on after a crash.
            aapsLogger.error(LTag.PUMP, "Dash: no record known for the bolus to correct ($reason): $bolus")
            return
        }
        val result = pumpSync.syncBolusWithPumpId(
            timestamp = pumpId,
            amount = PumpInsulin(delivered),
            type = null,
            pumpId = pumpId,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        aapsLogger.info(LTag.PUMP, "Dash PumpSync bolus corrected to $delivered U of ${bolus.requestedUnits} U ($reason): $result")
    }

    private companion object {

        const val MS_PER_MINUTE = 60_000L

        /** The commands Android writes to `PumpSync` when they are sent. */
        val RECORDED_AT_SEND = setOf(DashCommandKind.BOLUS, DashCommandKind.TEMP_BASAL, DashCommandKind.SUSPEND_DELIVERY)
    }
}
