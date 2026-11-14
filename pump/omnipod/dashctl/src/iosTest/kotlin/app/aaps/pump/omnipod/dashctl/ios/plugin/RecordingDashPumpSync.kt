package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync

/**
 * A [PumpSync] that keeps every call as one line of text, the shape of `RecordingPumpSync` in
 * `:pump:medtrum`. The Dash recorder uses only a few calls; every other one is kept too, so a test
 * sees a call it did not expect.
 */
internal class RecordingDashPumpSync : PumpSync {

    val calls = mutableListOf<String>()

    /** What `expectedPumpState().temporaryBasal` answers. */
    var expectedTemporaryBasal: PumpSync.PumpState.TemporaryBasal? = null

    /**
     * The registration rule of `PumpSyncImplementation.confirmActivePump`, for boluses: a bolus older
     * than this time is refused (false, nothing written), as a bolus older than the registration of
     * the pump is. Null: every bolus is taken.
     */
    var bolusAllowedFrom: Long? = null

    override fun connectNewPump(endRunning: Boolean) {
        calls += "connectNewPump"
    }

    override fun verifyPumpIdentification(type: PumpType, serialNumber: String): Boolean = true

    override suspend fun expectedPumpState(): PumpSync.PumpState =
        PumpSync.PumpState(expectedTemporaryBasal, null, null, null, "")

    override suspend fun addBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "addBolusWithTempId"
        return true
    }

    override suspend fun syncBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncBolusWithTempId"
        return true
    }

    override suspend fun syncBolusWithPumpId(timestamp: Long, amount: PumpInsulin, type: BS.Type?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        val allowedFrom = bolusAllowedFrom
        if (allowedFrom != null && timestamp < allowedFrom) {
            calls += "bolusRefused(timestamp=$timestamp, pumpId=$pumpId)"
            return false
        }
        calls += "bolus(timestamp=$timestamp, amount=${amount.cU}, type=$type, pumpId=$pumpId, pumpType=$pumpType, serial=$pumpSerial)"
        return true
    }

    override suspend fun syncCarbsWithTimestamp(timestamp: Long, amount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncCarbsWithTimestamp"
        return true
    }

    override suspend fun insertTherapyEventIfNewWithTimestamp(timestamp: Long, type: TE.Type, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "therapyEvent(timestamp=$timestamp, type=$type)"
        return true
    }

    override suspend fun insertFingerBgIfNewWithTimestamp(timestamp: Long, glucose: Double, glucoseUnit: GlucoseUnit, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "insertFingerBgIfNewWithTimestamp"
        return true
    }

    override suspend fun insertAnnouncement(error: String, pumpId: Long?, pumpType: PumpType, pumpSerial: String) {
        calls += "announcement($error)"
    }

    override suspend fun syncTemporaryBasalWithPumpId(timestamp: Long, rate: PumpRate, duration: Long, isAbsolute: Boolean, type: PumpSync.TemporaryBasalType?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "tempBasal(timestamp=$timestamp, rate=${rate.cU}, duration=$duration, isAbsolute=$isAbsolute, type=$type, pumpId=$pumpId, pumpType=$pumpType, serial=$pumpSerial)"
        return true
    }

    override suspend fun syncStopTemporaryBasalWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String, ignorePumpIds: Boolean): Boolean {
        calls += "stopTempBasal(timestamp=$timestamp, endPumpId=$endPumpId, pumpType=$pumpType, serial=$pumpSerial)"
        return true
    }

    override suspend fun addTemporaryBasalWithTempId(timestamp: Long, rate: PumpRate, duration: Long, isAbsolute: Boolean, tempId: Long, type: PumpSync.TemporaryBasalType, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "addTemporaryBasalWithTempId"
        return true
    }

    override suspend fun syncTemporaryBasalWithTempId(
        timestamp: Long,
        rate: PumpRate,
        duration: Long,
        isAbsolute: Boolean,
        temporaryId: Long,
        type: PumpSync.TemporaryBasalType?,
        pumpId: Long?,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        calls += "syncTemporaryBasalWithTempId"
        return true
    }

    override suspend fun invalidateTemporaryBasal(id: Long, sources: Sources, timestamp: Long): Boolean {
        calls += "invalidateTemporaryBasal"
        return true
    }

    override suspend fun invalidateTemporaryBasalWithPumpId(pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "invalidateTemporaryBasalWithPumpId($pumpId)"
        return true
    }

    override suspend fun invalidateTemporaryBasalWithTempId(temporaryId: Long): Boolean {
        calls += "invalidateTemporaryBasalWithTempId"
        return true
    }

    override suspend fun syncExtendedBolusWithPumpId(timestamp: Long, rate: PumpRate, duration: Long, isEmulatingTB: Boolean, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncExtendedBolusWithPumpId"
        return true
    }

    override suspend fun syncStopExtendedBolusWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncStopExtendedBolusWithPumpId"
        return true
    }

    override suspend fun createOrUpdateTotalDailyDose(timestamp: Long, bolusAmount: Double, basalAmount: Double, totalAmount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "createOrUpdateTotalDailyDose"
        return true
    }

    override suspend fun isProfileRunning(time: Long): Boolean = true
}
