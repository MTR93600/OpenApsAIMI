package app.aaps.pump.medtrum.ios

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.DetailedBolusInfoStorage
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import kotlin.math.abs

/**
 * A [PumpSync] that writes every call as one text line to [calls], so a test can assert the exact
 * method and arguments. Amounts are written as plain numbers (`cU`). The result of each call is
 * set by a field, so a test can also make a call fail.
 */
class RecordingPumpSync : PumpSync {

    val calls = mutableListOf<String>()

    var expectedTemporaryBasal: PumpSync.PumpState.TemporaryBasal? = null
    var syncBolusWithTempIdResult = true
    var syncBolusWithPumpIdResult = true
    var addBolusWithTempIdResult = true

    override fun connectNewPump(endRunning: Boolean) { calls += "connectNewPump" }
    override fun verifyPumpIdentification(type: PumpType, serialNumber: String): Boolean = true

    override suspend fun expectedPumpState(): PumpSync.PumpState =
        PumpSync.PumpState(expectedTemporaryBasal, null, null, null, "")

    override suspend fun addBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "addBolusWithTempId(timestamp=$timestamp, amount=${amount.cU}, temporaryId=$temporaryId, type=$type, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return addBolusWithTempIdResult
    }

    override suspend fun syncBolusWithTempId(timestamp: Long, amount: PumpInsulin, temporaryId: Long, type: BS.Type?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncBolusWithTempId(timestamp=$timestamp, amount=${amount.cU}, temporaryId=$temporaryId, type=$type, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return syncBolusWithTempIdResult
    }

    override suspend fun syncBolusWithPumpId(timestamp: Long, amount: PumpInsulin, type: BS.Type?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncBolusWithPumpId(timestamp=$timestamp, amount=${amount.cU}, type=$type, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return syncBolusWithPumpIdResult
    }

    override suspend fun syncCarbsWithTimestamp(timestamp: Long, amount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncCarbsWithTimestamp"
        return true
    }

    override suspend fun insertTherapyEventIfNewWithTimestamp(timestamp: Long, type: TE.Type, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "insertTherapyEventIfNewWithTimestamp(timestamp=$timestamp, type=$type, note=$note, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return true
    }

    override suspend fun insertFingerBgIfNewWithTimestamp(timestamp: Long, glucose: Double, glucoseUnit: GlucoseUnit, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "insertFingerBgIfNewWithTimestamp"
        return true
    }

    override suspend fun insertAnnouncement(error: String, pumpId: Long?, pumpType: PumpType, pumpSerial: String) {
        calls += "insertAnnouncement(error=$error, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
    }

    override suspend fun syncTemporaryBasalWithPumpId(timestamp: Long, rate: PumpRate, duration: Long, isAbsolute: Boolean, type: PumpSync.TemporaryBasalType?, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncTemporaryBasalWithPumpId(timestamp=$timestamp, rate=${rate.cU}, duration=$duration, isAbsolute=$isAbsolute, type=$type, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return true
    }

    override suspend fun syncStopTemporaryBasalWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String, ignorePumpIds: Boolean): Boolean {
        calls += "syncStopTemporaryBasalWithPumpId(timestamp=$timestamp, endPumpId=$endPumpId, pumpType=$pumpType, pumpSerial=$pumpSerial, ignorePumpIds=$ignorePumpIds)"
        return true
    }

    override suspend fun addTemporaryBasalWithTempId(timestamp: Long, rate: PumpRate, duration: Long, isAbsolute: Boolean, tempId: Long, type: PumpSync.TemporaryBasalType, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "addTemporaryBasalWithTempId"
        return true
    }

    override suspend fun syncTemporaryBasalWithTempId(timestamp: Long, rate: PumpRate, duration: Long, isAbsolute: Boolean, temporaryId: Long, type: PumpSync.TemporaryBasalType?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncTemporaryBasalWithTempId"
        return true
    }

    override suspend fun invalidateTemporaryBasal(id: Long, sources: Sources, timestamp: Long): Boolean {
        calls += "invalidateTemporaryBasal"
        return true
    }

    override suspend fun invalidateTemporaryBasalWithPumpId(pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "invalidateTemporaryBasalWithPumpId(pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return true
    }

    override suspend fun invalidateTemporaryBasalWithTempId(temporaryId: Long): Boolean {
        calls += "invalidateTemporaryBasalWithTempId"
        return true
    }

    override suspend fun syncExtendedBolusWithPumpId(timestamp: Long, rate: PumpRate, duration: Long, isEmulatingTB: Boolean, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncExtendedBolusWithPumpId(timestamp=$timestamp, rate=${rate.cU}, duration=$duration, isEmulatingTB=$isEmulatingTB, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return true
    }

    override suspend fun syncStopExtendedBolusWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "syncStopExtendedBolusWithPumpId"
        return true
    }

    override suspend fun createOrUpdateTotalDailyDose(timestamp: Long, bolusAmount: Double, basalAmount: Double, totalAmount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean {
        calls += "createOrUpdateTotalDailyDose(timestamp=$timestamp, bolusAmount=$bolusAmount, basalAmount=$basalAmount, totalAmount=$totalAmount, pumpId=$pumpId, pumpType=$pumpType, pumpSerial=$pumpSerial)"
        return true
    }

    override suspend fun isProfileRunning(time: Long): Boolean = true
}

/**
 * Keeps the infos in a list. `find` follows the real storage: first a match on time and amount, then
 * a match on time only when the pump delivered no more than was asked. The match is removed.
 */
class FakeDetailedBolusInfoStorage : DetailedBolusInfoStorage {

    val infos = mutableListOf<DetailedBolusInfo>()
    val findCalls = mutableListOf<Pair<Long, Double>>()

    override fun add(detailedBolusInfo: DetailedBolusInfo) { infos += detailedBolusInfo }

    override fun findDetailedBolusInfo(bolusTime: Long, bolus: Double): DetailedBolusInfo? {
        findCalls += bolusTime to bolus
        val oneMinute = 60_000L
        fun inWindow(d: DetailedBolusInfo) = bolusTime > d.timestamp - oneMinute && bolusTime < d.timestamp + oneMinute
        val found = infos.firstOrNull { inWindow(it) && abs(it.insulin - bolus) < 0.01 }
            ?: infos.firstOrNull { inWindow(it) && bolus <= it.insulin + 0.01 }
            ?: return null
        infos.remove(found)
        return found
    }
}

/** Keeps the requests in a list. `find` returns the first one with this rate and removes it. */
class FakeTemporaryBasalStorage : TemporaryBasalStorage {

    val items = mutableListOf<PumpSync.PumpState.TemporaryBasal>()

    override fun add(temporaryBasal: PumpSync.PumpState.TemporaryBasal) { items += temporaryBasal }

    override fun findTemporaryBasal(time: Long, rate: Double): PumpSync.PumpState.TemporaryBasal? {
        val found = items.firstOrNull { it.rate == rate } ?: return null
        items.remove(found)
        return found
    }
}
