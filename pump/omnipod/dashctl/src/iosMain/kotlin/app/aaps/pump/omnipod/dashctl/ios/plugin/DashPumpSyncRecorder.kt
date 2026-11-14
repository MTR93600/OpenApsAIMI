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
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * Writes what the pod did to `PumpSync`: the receiver of `IosDashPump.onCommandDecided`. Port of the
 * `PumpSync` part of Android `OmnipodDashPumpPlugin` (`pumpSyncBolusStart`, `pumpSyncTempBasal`,
 * `suspendDeliveryIfActive`, `handleCommandConfirmation`, `checkPodKaput`).
 *
 * ## When a record is written
 *
 * Android writes a bolus, a temp basal and the PUMP_SUSPEND temp basal of a suspend when the command
 * is SENT, and takes them back (`invalidate...`, a bolus of 0 U) when the pod refuses it. The iOS
 * driver gives only final outcomes, so here they are written when the pod CONFIRMED them (SUCCESS),
 * at once or late, at the time the command was made ([DashActiveCommand.createdAtMs], Android
 * `HistoryRecord.createdAt`). A refused or never sent command writes nothing, so nothing has to be
 * taken back. While a command is not confirmed, the driver refuses every new delivery command, so
 * the loop cannot dose on top of a bolus that is not counted yet (fail closed).
 *
 * | Outcome                                       | Record (all at the command time)                    |
 * |-----------------------------------------------|-----------------------------------------------------|
 * | BOLUS SUCCESS                                 | bolus at the asked amount (`syncBolusWithPumpId`)   |
 * | TEMP_BASAL SUCCESS                            | temp basal (`syncTemporaryBasalWithPumpId`)         |
 * | STOP_TEMP_BASAL SUCCESS                       | stop of the temp basal                              |
 * | SUSPEND_DELIVERY SUCCESS                      | PUMP_SUSPEND temp basal of 0 U/h, 80 h              |
 * | BASAL_PROGRAM, RESUME_DELIVERY SUCCESS        | stop of the temp basal (ends the PUMP_SUSPEND one)  |
 * | an outcome with `endedBolus`                  | the bolus record corrected to what went in          |
 * | [DashBolusEndedByFault]                       | the bolus record corrected to what went in          |
 * | BOLUS / TEMP_BASAL / SUSPEND, pod discarded   | as SUCCESS: Android had written them when sent      |
 *
 * A bolus that `PumpSync` refuses because it is older than the registration of the pump is written
 * again at the time it was decided (see [recordBolus]). A temp basal needs no such step: `PumpSync`
 * itself starts one that began before the registration at the registration.
 *
 * ## The pump id
 *
 * Android uses the id of its pod history record. iOS has no pod history yet, so the pump id is the
 * command time ([DashActiveCommand.createdAtMs]): it is stored with the open command, so the same
 * id comes back after a restart, and a correction finds the record of its bolus by it
 * ([DashLastBolus.createdAtMs] is the same time). Pump type and serial are Android's:
 * [PumpType.OMNIPOD_DASH] and the unique id of the pod ([serialNumber]).
 *
 * ## The type of a bolus and of a temp basal
 *
 * The driver does not store them. [beginBolus] and [beginTempBasal] give them for the command that is
 * sent now; [endCommand] keeps the type of a command that is still open after the call, so a late
 * outcome is written with it. After an app restart the type of an open command is lost: the bolus is
 * then written as NORMAL and the temp basal as NORMAL. The amount and the time are right either way,
 * so insulin on board is right; only the label differs.
 *
 * Calls come from inside the command lock of the driver, on the queue thread. `PumpSync` is suspend,
 * so [onOutcome] blocks that thread until the record is written, as Android writes inside its
 * command chain.
 */
class DashPumpSyncRecorder(
    private val pumpSync: PumpSync,
    private val aapsLogger: AAPSLogger,
    /** Android `serialNumber()`: the unique id of the pod, "4241" without one. Read when a record is written. */
    private val serialNumber: () -> String,
    /** The wall clock, for a bolus older than the registration of the pump in `PumpSync`. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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

    /**
     * The pump id and time of the last bolus written, for a correction of a bolus whose command
     * time is not known (stored by codec version 3 or before), as the KDoc of
     * [DashBolusEndedByFault] asks.
     */
    private var lastBolusRecord: Pair<Long, Long>? = null

    /**
     * The time a bolus was written at, by pump id, when it is not its command time (see
     * [recordBolus]), so a correction goes to the same record. In memory only.
     */
    private val movedBolusTimes = mutableMapOf<Long, Long>()

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
     * confirmed, its type is kept for the late outcome. The caller passes only a command that this
     * call made (`IosDashPluginPump`), and a type that is already kept is never replaced: a refused
     * call must not give its own type to the earlier command that is still open.
     */
    fun endCommand(stillOpen: DashActiveCommand?) {
        val at = stillOpen?.createdAtMs
        if (at != null) {
            when (stillOpen.kind) {
                // Never over an entry that is there: that is a command an earlier call sent, which
                // was still open when this call was refused.
                DashCommandKind.BOLUS      -> currentBolusType?.let { if (at !in openBolusTypes) openBolusTypes[at] = it }
                DashCommandKind.TEMP_BASAL -> currentTempBasalType?.let { if (at !in openTempBasalTypes) openTempBasalTypes[at] = it }
                else                       -> Unit
            }
        }
        currentBolusType = null
        currentTempBasalType = null
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

    /** Writes one outcome, see the table in the class comment. */
    suspend fun record(outcome: DashPumpOutcome) {
        when (outcome) {
            is DashBolusEndedByFault -> correctBolus(outcome.bolus, "pod fault ${outcome.alarmType}")
            is DashCommandOutcome    -> recordCommand(outcome)
        }
    }

    private suspend fun recordCommand(outcome: DashCommandOutcome) {
        val command = outcome.command
        val confirmed = outcome.confirmation == DashCommandConfirmation.SUCCESS
        // Android wrote these when they were sent, so its "Discard pod" keeps them.
        val discardedOpen = outcome.confirmation == DashCommandConfirmation.NOT_CONFIRMED && outcome.podDiscarded
        val at = command.createdAtMs
        if (at == null && (confirmed || discardedOpen) && command.kind != DashCommandKind.OTHER) {
            aapsLogger.error(LTag.PUMP, "Dash: command without its time (old stored state), not written: $outcome")
        }
        when (command.kind) {
            DashCommandKind.BOLUS            -> if ((confirmed || discardedOpen) && at != null) recordBolus(command, at, outcome.decidedLate)

            DashCommandKind.TEMP_BASAL       -> if ((confirmed || discardedOpen) && at != null) recordTempBasal(command, at, outcome.decidedLate)

            DashCommandKind.SUSPEND_DELIVERY -> if ((confirmed || discardedOpen) && at != null) recordSuspend(at)

            DashCommandKind.STOP_TEMP_BASAL,
            DashCommandKind.BASAL_PROGRAM,
            DashCommandKind.RESUME_DELIVERY  -> if (confirmed && at != null) recordStopTempBasal(at, command.kind)

            else                             -> Unit
        }
        if (at != null) {
            openBolusTypes.remove(at)
            openTempBasalTypes.remove(at)
        }
        outcome.endedBolus?.let { correctBolus(it, "stop ${command.kind}") }
    }

    private suspend fun recordBolus(command: DashActiveCommand, at: Long, decidedLate: Boolean) {
        val units = command.requestedBolusUnits
        if (units == null || units <= 0.0) {
            aapsLogger.error(LTag.PUMP, "Dash: bolus without its amount, not written: $command")
            return
        }
        val type = openBolusTypes[at] ?: (if (decidedLate) null else currentBolusType) ?: BS.Type.NORMAL
        var recordedAt = at
        var created = pumpSync.syncBolusWithPumpId(
            timestamp = at,
            amount = PumpInsulin(units),
            type = type,
            pumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = serialNumber()
        )
        val decidedAt = now()
        if (!created && at < decidedAt - REGISTRATION_WINDOW_MS) {
            // PumpSync takes no bolus older than the registration of the pump (connectNewPump after a
            // pump change or a settings import; the first record then registers it with one minute
            // back). Android never meets this, it writes a bolus when it sends it. Here a bolus
            // confirmed late would be lost and insulin that went in never counted. So it is written
            // again at the time it was decided, with the same pump id: later than it went in, so
            // more insulin on board is counted, never less.
            aapsLogger.error(LTag.PUMP, "Dash: PumpSync refused the bolus at $at, written at $decidedAt instead")
            recordedAt = decidedAt
            created = pumpSync.syncBolusWithPumpId(
                timestamp = decidedAt,
                amount = PumpInsulin(units),
                type = type,
                pumpId = at,
                pumpType = PumpType.OMNIPOD_DASH,
                pumpSerial = serialNumber()
            )
            if (created) movedBolusTimes[at] = decidedAt
        }
        if (!created) aapsLogger.error(LTag.PUMP, "Dash: PumpSync did not write the bolus $units U at $at")
        lastBolusRecord = at to recordedAt
        aapsLogger.info(LTag.PUMP, "Dash PumpSync bolus $units U $type at $recordedAt (late=$decidedLate): $created")
    }

    private suspend fun recordTempBasal(command: DashActiveCommand, at: Long, decidedLate: Boolean) {
        val rate = command.tempBasalRate
        val minutes = command.tempBasalMinutes
        if (rate == null || minutes == null) {
            aapsLogger.error(LTag.PUMP, "Dash: temp basal without its rate or duration, not written: $command")
            return
        }
        val type = openTempBasalTypes[at] ?: (if (decidedLate) null else currentTempBasalType) ?: PumpSync.TemporaryBasalType.NORMAL
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
        aapsLogger.info(LTag.PUMP, "Dash PumpSync temp basal $rate U/h $minutes min $type at $at (late=$decidedLate): $created")
    }

    /** Android `suspendDeliveryIfActive`: `pumpSyncTempBasal(0.0, MAX_POD_LIFETIME, PUMP_SUSPEND)`. */
    private suspend fun recordSuspend(at: Long) {
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
     * same pump id, which updates the record of the bolus (one record, never a second bolus). The
     * type is left as it is (null: `PumpSync` keeps the stored type).
     */
    private suspend fun correctBolus(bolus: DashLastBolus, reason: String) {
        val delivered = bolus.deliveredUnits()
        if (delivered == null) {
            aapsLogger.error(LTag.PUMP, "Dash: bolus not ended, no correction ($reason): $bolus")
            return
        }
        val key = bolus.createdAtMs?.let { it to (movedBolusTimes[it] ?: it) } ?: lastBolusRecord
        if (key == null) {
            // Nothing to correct is known: the bolus stays counted at the asked amount (more than went
            // in, never less), the side Android also falls on after a crash.
            aapsLogger.error(LTag.PUMP, "Dash: no record known for the bolus to correct ($reason): $bolus")
            return
        }
        val (pumpId, timestamp) = key
        val result = pumpSync.syncBolusWithPumpId(
            timestamp = timestamp,
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

        /** How far back `PumpSyncImplementation.confirmActivePump` takes records when it registers a pump. */
        const val REGISTRATION_WINDOW_MS = 60_000L
    }
}
