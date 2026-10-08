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
    val tdd: Double
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

    companion object {
        val NO_OP: MedtrumRecordSync = object : MedtrumRecordSync {
            override fun syncBolusRecord(record: MedtrumBolusRecord, pumpSerialHex: String) = Unit
            override fun syncBasalRecord(record: MedtrumBasalRecord, pumpSerialHex: String) = Unit
            override fun syncTddRecord(record: MedtrumTddRecord, pumpSerialHex: String) = Unit
        }
    }
}
