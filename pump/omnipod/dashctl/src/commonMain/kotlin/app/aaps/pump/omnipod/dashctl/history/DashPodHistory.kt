package app.aaps.pump.omnipod.dashctl.history

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus

/**
 * Android `OmnipodCommandType`, the commands the iPhone history knows. GET_POD_STATUS, SET_TIME and
 * READ_POD_PULSE_LOG are not here: see [DashPodHistory].
 */
enum class DashHistoryType {
    SET_BOLUS,
    CANCEL_BOLUS,
    SET_TEMPORARY_BASAL,
    CANCEL_TEMPORARY_BASAL,
    SET_BASAL_PROFILE,
    SUSPEND_DELIVERY,

    /** The stop of all delivery (`IosDashPump.stopDelivery`); Android has no own type for it. */
    STOP_DELIVERY,
    RESUME_DELIVERY,
    ACKNOWLEDGE_ALERTS,
    CONFIGURE_ALERTS,
    PLAY_TEST_BEEP,
    INITIALIZE_POD,
    INSERT_CANNULA,
    DEACTIVATE_POD,
    DISCARD_POD;

    companion object {

        /** The history type of a delivery command, null for one the history does not show. */
        fun of(kind: DashCommandKind): DashHistoryType? = when (kind) {
            DashCommandKind.BOLUS            -> SET_BOLUS
            DashCommandKind.TEMP_BASAL       -> SET_TEMPORARY_BASAL
            DashCommandKind.STOP_TEMP_BASAL  -> CANCEL_TEMPORARY_BASAL
            DashCommandKind.STOP_BOLUS       -> CANCEL_BOLUS
            DashCommandKind.STOP_ALL         -> STOP_DELIVERY
            DashCommandKind.BASAL_PROGRAM    -> SET_BASAL_PROFILE
            DashCommandKind.SUSPEND_DELIVERY -> SUSPEND_DELIVERY
            DashCommandKind.RESUME_DELIVERY  -> RESUME_DELIVERY
            DashCommandKind.SILENCE_ALERTS   -> ACKNOWLEDGE_ALERTS
            DashCommandKind.PROGRAM_ALERTS   -> CONFIGURE_ALERTS
            DashCommandKind.PROGRAM_BEEPS    -> PLAY_TEST_BEEP
            DashCommandKind.DEACTIVATE       -> DEACTIVATE_POD
            DashCommandKind.OTHER            -> null
        }
    }
}

/** Android `InitialResult`. */
enum class DashHistoryInitialResult { NOT_SENT, FAILURE_SENDING, SENT }

/** Android `ResolvedResult`. */
enum class DashHistoryResolvedResult { SUCCESS, FAILURE }

/** What kind of bolus a [DashHistoryType.SET_BOLUS] was (Android `BolusType`, without PRIMING). */
enum class DashHistoryBolusType { DEFAULT, SMB, BASAL_CORRECTION }

/**
 * One line of the pod history, Android `HistoryRecord`. The id is the time the command was made
 * ([createdAtMs]), which is also the pump id of its `PumpSync` record on the iPhone.
 *
 * @property bolusUnits the bolus asked, for [DashHistoryType.SET_BOLUS] (Android `BolusRecord.amount`).
 * @property tempBasalRate and [tempBasalMinutes]: Android `TempBasalRecord`.
 * @property totalAmountDelivered Android `totalAmountDelivered`: what a bolus gave once it ended early
 *   (a stop or a fault).
 */
data class DashHistoryRecord(
    val createdAtMs: Long,
    val type: DashHistoryType,
    val initialResult: DashHistoryInitialResult,
    val bolusUnits: Double? = null,
    val bolusType: DashHistoryBolusType? = null,
    val tempBasalRate: Double? = null,
    val tempBasalMinutes: Int? = null,
    val totalAmountDelivered: Double? = null,
    val resolvedResult: DashHistoryResolvedResult? = null,
    val resolvedAtMs: Long? = null,
) {

    /** Android `isSuccess`: sent and confirmed by the pod. */
    fun isSuccess(): Boolean = initialResult == DashHistoryInitialResult.SENT && resolvedResult == DashHistoryResolvedResult.SUCCESS
}

/** Where the pod history is kept: on the iPhone the user defaults ([DashPodHistoryCodec] bytes). */
interface DashPodHistoryStore {

    fun load(): ByteArray?
    fun save(bytes: ByteArray)
}

/**
 * The pod history of the iPhone, the counterpart of Android `DashHistory` (a Room database there): one
 * record per delivery command and per activation part, written at the same points as Android writes
 * its records:
 * - [onCommandSent]: the command reached the radio (Android `createRecord` + `updateInitialResult(SENT)`);
 * - [onCommandDecided]: the pod decided it (Android `updateFromState`): SUCCESS, or FAILURE for a refusal;
 *   a command that could not be sent is FAILURE_SENDING;
 * - [onBolusEnded]: a bolus that ended early, with what went in (Android `totalAmountDelivered`);
 * - [addActivationStep]: INITIALIZE_POD, INSERT_CANNULA and DISCARD_POD, from the wizard.
 *
 * Deliberate differences from Android: status reads (GET_POD_STATUS) are not kept, nor the time change
 * as SET_TIME (it shows as SET_BASAL_PROFILE); records older than [KEEP_MS] (the 5 days Android shows)
 * are dropped when a record is written, and at most [MAX_RECORDS] are kept, so the store stays small.
 *
 * Errors of the store never reach the caller: the history is for the user to read, the dosing does not
 * depend on it.
 *
 * Thread safe: the queue thread writes the command records and the wizard and the discard write the
 * activation records, at the same time. Each change of [records] (read, change, write back and save)
 * holds [lock], so two writes at once cannot lose one record. Android keeps its rows in Room, which
 * does not lose them either.
 */
class DashPodHistory(
    private val store: DashPodHistoryStore,
    private val now: () -> Long,
) {

    /** Guards [records] and the save of it (see the class comment). */
    private val lock = AapsLock()

    private var records: List<DashHistoryRecord> = try {
        store.load()?.let { DashPodHistoryCodec.decode(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** The type of the next bolus the plugin sends (an SMB or not), set before the call as `DashPumpSyncRecorder.beginBolus`. */
    var nextBolusIsSmb: Boolean = false

    /** Android `getRecordsAfter`: the records made after [sinceMs], newest first. */
    fun recordsAfter(sinceMs: Long): List<DashHistoryRecord> =
        lock.withLock { records }.filter { it.createdAtMs > sinceMs }.sortedByDescending { it.createdAtMs }

    /** The records of the last 5 days, newest first, as the Android history screen shows them. */
    fun recentRecords(): List<DashHistoryRecord> = recordsAfter(now() - KEEP_MS)

    fun onCommandSent(command: DashActiveCommand): Unit = lock.withLock {
        val at = command.createdAtMs ?: return
        val type = DashHistoryType.of(command.kind) ?: return
        if (records.any { it.createdAtMs == at }) return
        put(
            DashHistoryRecord(
                createdAtMs = at,
                type = type,
                initialResult = DashHistoryInitialResult.SENT,
                bolusUnits = command.requestedBolusUnits,
                bolusType = if (command.kind != DashCommandKind.BOLUS) null
                else if (command.basalCorrection) DashHistoryBolusType.BASAL_CORRECTION
                else if (nextBolusIsSmb) DashHistoryBolusType.SMB
                else DashHistoryBolusType.DEFAULT,
                tempBasalRate = command.tempBasalRate,
                tempBasalMinutes = command.tempBasalMinutes,
            )
        )
    }

    fun onCommandDecided(command: DashActiveCommand, confirmation: DashCommandConfirmation, decidedAtMs: Long): Unit = lock.withLock {
        val at = command.createdAtMs ?: return
        val type = DashHistoryType.of(command.kind) ?: return
        val existing = records.firstOrNull { it.createdAtMs == at }
        val updated = when (confirmation) {
            DashCommandConfirmation.SUCCESS         ->
                (existing ?: sentRecord(command, at, type)).copy(resolvedResult = DashHistoryResolvedResult.SUCCESS, resolvedAtMs = decidedAtMs)

            DashCommandConfirmation.DENIED          ->
                (existing ?: sentRecord(command, at, type)).copy(resolvedResult = DashHistoryResolvedResult.FAILURE, resolvedAtMs = decidedAtMs)

            DashCommandConfirmation.SENDING_FAILURE ->
                (existing ?: sentRecord(command, at, type)).copy(initialResult = DashHistoryInitialResult.FAILURE_SENDING, resolvedAtMs = decidedAtMs)

            else                                    -> return
        }
        put(updated)
    }

    /** A bolus that ended early (a stop, a fault): what went in is kept on its record. */
    fun onBolusEnded(bolus: DashLastBolus): Unit = lock.withLock {
        val at = bolus.createdAtMs ?: return
        val delivered = bolus.deliveredUnits() ?: return
        val existing = records.firstOrNull { it.createdAtMs == at } ?: return
        put(existing.copy(totalAmountDelivered = delivered))
    }

    /** An activation part or a discard, from the wizard: one record, decided at once. */
    fun addActivationStep(type: DashHistoryType, success: Boolean): Unit = lock.withLock {
        var at = now()
        while (records.any { it.createdAtMs == at }) at++
        put(
            DashHistoryRecord(
                createdAtMs = at,
                type = type,
                initialResult = DashHistoryInitialResult.SENT,
                resolvedResult = if (success) DashHistoryResolvedResult.SUCCESS else DashHistoryResolvedResult.FAILURE,
                resolvedAtMs = at,
            )
        )
    }

    private fun sentRecord(command: DashActiveCommand, at: Long, type: DashHistoryType) = DashHistoryRecord(
        createdAtMs = at,
        type = type,
        initialResult = DashHistoryInitialResult.SENT,
        bolusUnits = command.requestedBolusUnits,
        bolusType = if (command.kind != DashCommandKind.BOLUS) null
        else if (command.basalCorrection) DashHistoryBolusType.BASAL_CORRECTION
        else DashHistoryBolusType.DEFAULT,
        tempBasalRate = command.tempBasalRate,
        tempBasalMinutes = command.tempBasalMinutes,
    )

    /** Call it with [lock] held. */
    private fun put(record: DashHistoryRecord) {
        val oldest = now() - KEEP_MS
        records = (records.filter { it.createdAtMs != record.createdAtMs && it.createdAtMs > oldest } + record)
            .sortedBy { it.createdAtMs }
            .takeLast(MAX_RECORDS)
        try {
            store.save(DashPodHistoryCodec.encode(records))
        } catch (e: Exception) {
            // The history is for reading only; a failed write must never break a command.
        }
    }

    companion object {

        /** Android `DashPodHistoryViewModel.DAYS_TO_DISPLAY`: 5 days. */
        const val KEEP_MS = 5 * 24 * 60 * 60_000L

        /** At most this many records are kept (about 3 a minute for 5 days would be far more than a pod does). */
        const val MAX_RECORDS = 2000
    }
}
