package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync

/**
 * A [PumpSync] that keeps every call as one line of text, the shape of `RecordingPumpSync` in
 * `:pump:medtrum`. The Dash recorder uses only a few calls; every other one is kept too, so a test
 * sees a call it did not expect.
 */
internal class RecordingDashPumpSync : PumpSync {

    val calls = mutableListOf<String>()

    /**
     * What `expectedPumpState().temporaryBasal` answers. Follows the temp basal calls as `PumpSync` does
     * (a new one runs, a stop or an invalidation of it ends it; time is not looked at), and a test may
     * set it.
     */
    var expectedTemporaryBasal: PumpSync.PumpState.TemporaryBasal? = null

    /** What `expectedPumpState().profile` answers. */
    var expectedProfile: PumpProfile? = null

    /**
     * The bolus records as `PumpSyncImplementation.syncBolusWithPumpId` keeps them, by pump id: a new
     * pump id inserts a record (type NORMAL when none is given), a known one updates its amount and,
     * when a type is given, its type. So a test can count the records of a bolus, not only the calls.
     */
    val bolusRecords = mutableMapOf<Long, Pair<Double, BS.Type>>()

    /** How many of the next `syncBolusWithPumpId` calls throw (a database error), with nothing written. */
    var bolusWritesToFail = 0

    override fun connectNewPump(endRunning: Boolean) {
        calls += "connectNewPump"
    }

    override fun verifyPumpIdentification(type: PumpType, serialNumber: String): Boolean = true

    override suspend fun expectedPumpState(): PumpSync.PumpState =
        PumpSync.PumpState(expectedTemporaryBasal, null, null, expectedProfile, "")

    override suspend fun addBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "addBolusWithTempId"
        return true
    }

    override suspend fun syncBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncBolusWithTempId"
        return true
    }

    override suspend fun syncBolusWithPumpId(timestamp: Long, amount: PumpInsulin, type: BS.Type?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        if (bolusWritesToFail > 0) {
            bolusWritesToFail--
            error("database closed")
        }
        calls += "bolus(timestamp=$timestamp, amount=${amount.cU}, type=$type, pumpId=$pumpId, pumpType=$pumpType, serial=$pumpSerial)"
        val known = bolusRecords[pumpId]
        bolusRecords[pumpId] = amount.cU to (type ?: known?.second ?: BS.Type.NORMAL)
        return known == null
    }

    override suspend fun syncCarbsWithTimestamp(timestamp: Long, amount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncCarbsWithTimestamp"
        return true
    }

    override suspend fun insertTherapyEventIfNewWithTimestamp(timestamp: Long, type: TE.Type, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "therapyEvent(timestamp=$timestamp, type=$type, serial=$pumpSerial)"
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
        expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = timestamp, duration = duration, rate = rate.cU, isAbsolute = isAbsolute,
            type = type ?: PumpSync.TemporaryBasalType.NORMAL, id = pumpId, pumpId = pumpId
        )
        return true
    }

    override suspend fun syncStopTemporaryBasalWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String, ignorePumpIds: Boolean): Boolean {
        calls += "stopTempBasal(timestamp=$timestamp, endPumpId=$endPumpId, pumpType=$pumpType, serial=$pumpSerial)"
        expectedTemporaryBasal = null
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
        if (expectedTemporaryBasal?.pumpId == pumpId) expectedTemporaryBasal = null
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
