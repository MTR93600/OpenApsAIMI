package app.aaps.pump.medtrum.comm

import app.aaps.pump.medtrum.comm.enums.BasalEndReason
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.BolusType

/**
 * Parsed pump history records.
 *
 * These data classes carry the protocol-decoded values from `GetRecordPacket`
 * without any Android/persistence types. The M2/M3 layers convert them to
 * `PumpSync` calls.
 */
data class MedtrumBolusRecord(
    val timestamp: Long,
    val normalAmount: Double,
    val normalDelivered: Double,
    val extendedAmount: Double,
    val extendedDurationMs: Long,
    val extendedDelivered: Double,
    val carbs: Int,
    val glucose: Int,
    val bolusType: BolusType,
    val bolusWizard: Boolean
)

data class MedtrumBasalRecord(
    val basalType: BasalType,
    val basalRate: Double,
    val basalDelivered: Double,
    val basalPercent: Int,
    val basalSequence: Int,
    val basalPatchId: Long,
    val basalStartTime: Long,
    val basalEndTime: Long,
    val basalEndReason: BasalEndReason?
)

data class MedtrumTddRecord(
    val timestamp: Long,
    val tdd: Double,
    val basalTdd: Double,
    /** Android computes this as a Float subtraction (`tdd - basalTdd`), so the packet does the same. */
    val bolusTdd: Double
)

/**
 * Persistence seam for pump history records.
 *
 * The Android driver calls `PumpSync` / `DetailedBolusInfoStorage` directly (via
 * `runBlocking`). This interface captures the sync intent with portable types.
 * The M2/M3 layers implement it; tests use a recording fake.
 */
interface MedtrumRecordSync {
    fun syncBolusRecord(record: MedtrumBolusRecord, pumpSerialHex: String)
    fun syncBasalRecord(record: MedtrumBasalRecord, pumpSerialHex: String)
    fun syncTddRecord(record: MedtrumTddRecord, pumpSerialHex: String)

    /**
     * Android asks `pumpSync.expectedPumpState()` before it treats a basal record end reason as a
     * pump suspend. Return false when the record is too old and a newer temp basal is running.
     */
    fun shouldSyncPumpSuspend(basalEndTime: Long): Boolean = true

    /**
     * The pump reported a basal status (from a notification or from a command answer). Android
     * reconciles the temp basal history here (`MedtrumPump.handleBasalStatusUpdate`).
     * [baseBasalRate] is the profile rate now, used to turn a relative rate into a percent.
     * Returns the duration of the temp basal in minutes, 0 when none was started.
     */
    fun syncBasalStatus(basalType: BasalType, basalRate: Double, basalStartTime: Long, baseBasalRate: Double): Int = 0

    /** A new patch was activated. Android adds a cannula change and an insulin change event. */
    fun syncNewPatch(newStartTime: Long) = Unit

    companion object {
        val NO_OP: MedtrumRecordSync = object : MedtrumRecordSync {
            override fun syncBolusRecord(record: MedtrumBolusRecord, pumpSerialHex: String) = Unit
            override fun syncBasalRecord(record: MedtrumBasalRecord, pumpSerialHex: String) = Unit
            override fun syncTddRecord(record: MedtrumTddRecord, pumpSerialHex: String) = Unit
        }
    }
}
