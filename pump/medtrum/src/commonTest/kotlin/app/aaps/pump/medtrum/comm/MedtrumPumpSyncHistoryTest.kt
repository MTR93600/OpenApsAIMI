package app.aaps.pump.medtrum.ios

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.medtrum.comm.MedtrumBasalRecord
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalEndReason
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.CommandType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.comm.packets.GetRecordPacket
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSessionState
import app.aaps.pump.medtrum.session.successResponse
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MedtrumPumpSyncHistory]. Records are built as the bytes the pump sends and parsed by the
 * real `GetRecordPacket`, so each test shows: pump bytes in, exact `PumpSync` call out.
 * The expected values are the arguments the Android driver passes in the same case.
 */
class MedtrumPumpSyncHistoryTest {

    private val now = 1_800_000_000_000L
    private val serial = "12345678" // pumpSN 0x12345678 as hex, like Android

    /** Pump seconds 300 000 000 are this system time. */
    private fun pumpSecs(ms: Long): Long = (ms - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000
    private val t1 = MedtrumTimeUtil.PUMP_EPOCH_MILLIS + 300_000_000L * 1000
    private val t2 = t1 + 30 * 60_000L

    private class Rig(
        val sync: RecordingPumpSync,
        val infos: FakeDetailedBolusInfoStorage,
        val tbrs: FakeTemporaryBasalStorage,
        val state: MedtrumSessionState,
        val history: MedtrumPumpSyncHistory,
    )

    private fun rig(deviceType: Int = 80): Rig {
        val sync = RecordingPumpSync()
        val infos = FakeDetailedBolusInfoStorage()
        val tbrs = FakeTemporaryBasalStorage()
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(now)).apply {
            pumpSN = 0x12345678L
            this.deviceType = deviceType
            patchId = 7
            // One basal in the profile: 1.0 U/h from 00:00. Count 1, rate 20 * 0.05 in the upper 4 bits... see below.
        }
        val history = MedtrumPumpSyncHistory(
            pumpSync = sync, detailedBolusInfoStorage = infos, temporaryBasalStorage = tbrs,
            deviceType = { state.deviceType }, serialProvider = { state.pumpSN.toString(radix = 16) },
            nowMillis = { now },
        )
        state.recordSync = history
        return Rig(sync, infos, tbrs, state, history)
    }

    // -- Bytes -------------------------------------------------------------------------------

    private fun le(value: Long, size: Int): ByteArray = ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }
    private fun leFloat(value: Float): ByteArray = le(value.toRawBits().toLong() and 0xFFFFFFFFL, 4)

    /** A GET_RECORD answer: 6 byte answer header, record header 170, record type, serial, patch id, sequence, then the data. */
    private fun frame(type: Int, sequence: Int, data: ByteArray): ByteArray =
        successResponse(CommandType.GET_RECORD.code) +
            byteArrayOf(170.toByte(), 0, type.toByte(), 0) + le(0x12345678L, 4) + le(7, 2) + le(sequence.toLong(), 2) + data

    private fun bolusData(
        type: Int, wizard: Boolean = false, startMs: Long,
        normalRaw: Int, deliveredRaw: Int, extRaw: Int = 0, extMinutes: Int = 0, extDeliveredRaw: Int = 0,
    ): ByteArray =
        byteArrayOf((type or (if (wizard) 0x10 else 0)).toByte(), 0, 0, 0) +
            le(pumpSecs(startMs), 4) + le(normalRaw.toLong(), 2) + le(deliveredRaw.toLong(), 2) +
            le(extRaw.toLong(), 2) + le(extMinutes.toLong(), 2) + le(extDeliveredRaw.toLong(), 2) +
            ByteArray(10)

    private fun basalData(
        type: BasalType, startMs: Long, endMs: Long, endReason: BasalEndReason = BasalEndReason.SUCCESS,
        rateRaw: Int = 0, percent: Int = 0,
    ): ByteArray =
        le(pumpSecs(startMs), 4) + le(pumpSecs(endMs), 4) + byteArrayOf(type.ordinal.toByte(), endReason.ordinal.toByte()) +
            le(rateRaw.toLong(), 2) + le(0, 2) + le(percent.toLong(), 2)

    private fun tddData(tsMs: Long, tdd: Float, basalTdd: Float): ByteArray =
        le(pumpSecs(tsMs), 4) + ByteArray(8) + leFloat(tdd) + leFloat(basalTdd) + ByteArray(44)

    private fun Rig.feed(frame: ByteArray, sequence: Int = 1) {
        val packet = GetRecordPacket(state, sequence, history)
        assertTrue(packet.handleResponse(frame), "the record must be accepted")
    }

    // -- Bolus records -----------------------------------------------------------------------

    @Test
    fun normalBolusRecordWithoutStoredInfoIsSyncedByPumpIdAndHasNoType() {
        val r = rig()
        r.feed(frame(1, 5, bolusData(type = 1, startMs = t1, normalRaw = 20, deliveredRaw = 18)))
        assertEquals(
            listOf("syncBolusWithPumpId(timestamp=$t1, amount=${18 * 0.05}, type=null, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
        // Same as Android: the packet also keeps the last bolus.
        assertEquals(t1, r.state.lastBolusTime)
        assertEquals(18 * 0.05, r.state.lastBolusAmount)
        assertEquals(5, r.state.syncedSequenceNumber)
    }

    @Test
    fun alternateBolusRecordTypeIsTreatedAsABolus() {
        val r = rig()
        r.feed(frame(65, 5, bolusData(type = 1, wizard = true, startMs = t1, normalRaw = 20, deliveredRaw = 20)))
        assertEquals(1, r.sync.calls.size)
        assertTrue(r.sync.calls[0].startsWith("syncBolusWithPumpId(timestamp=$t1, amount=1.0, type=null"))
    }

    @Test
    fun normalBolusRecordWithStoredInfoKeepsTheAppBolusTypeAndTempId() {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 0.9; bolusType = BS.Type.SMB; timestamp = t1 - 1500 }
        r.infos.add(info)
        r.feed(frame(1, 5, bolusData(type = 1, startMs = t1, normalRaw = 20, deliveredRaw = 18)))
        assertEquals(
            listOf("syncBolusWithTempId(timestamp=$t1, amount=${18 * 0.05}, temporaryId=${t1 - 1500}, type=SMB, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
        assertTrue(r.infos.infos.isEmpty(), "the info was used")
        assertEquals(listOf(t1 to 18 * 0.05), r.infos.findCalls)
    }

    @Test
    fun failedTempIdSyncPutsTheStoredInfoBack() {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 0.9; bolusType = BS.Type.SMB; timestamp = t1 }
        r.infos.add(info)
        r.sync.syncBolusWithTempIdResult = false
        r.feed(frame(1, 5, bolusData(type = 1, startMs = t1, normalRaw = 20, deliveredRaw = 18)))
        assertEquals(listOf(info), r.infos.infos, "Android adds the info again, it can belong to another record")
    }

    @Test
    fun extendedBolusRecordIsSyncedAsExtendedBolusWithDeliveredRate() {
        val r = rig()
        r.feed(frame(1, 5, bolusData(type = 2, startMs = t1, normalRaw = 0, deliveredRaw = 0, extRaw = 40, extMinutes = 90, extDeliveredRaw = 30)))
        assertEquals(
            listOf("syncExtendedBolusWithPumpId(timestamp=$t1, rate=${30 * 0.05}, duration=${90 * 60_000L}, isEmulatingTB=false, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun comboBolusRecordWritesBothParts() {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 1.0; bolusType = BS.Type.NORMAL; timestamp = t1 }
        r.infos.add(info)
        r.feed(frame(1, 5, bolusData(type = 3, startMs = t1, normalRaw = 20, deliveredRaw = 20, extRaw = 40, extMinutes = 60, extDeliveredRaw = 40)))
        assertEquals(
            listOf(
                "syncBolusWithPumpId(timestamp=$t1, amount=1.0, type=NORMAL, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)",
                "syncExtendedBolusWithPumpId(timestamp=$t1, rate=2.0, duration=${60 * 60_000L}, isEmulatingTB=false, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)",
            ),
            r.sync.calls
        )
        assertTrue(r.infos.infos.isEmpty())
    }

    @Test
    fun comboBolusThatWasNotNewPutsTheInfoBack() {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 1.0; timestamp = t1 }
        r.infos.add(info)
        r.sync.syncBolusWithPumpIdResult = false
        r.feed(frame(1, 5, bolusData(type = 3, startMs = t1, normalRaw = 20, deliveredRaw = 20, extRaw = 40, extMinutes = 60, extDeliveredRaw = 40)))
        assertEquals(listOf(info), r.infos.infos)
    }

    @Test
    fun bolusTypeNoneWritesNothing() {
        val r = rig()
        r.feed(frame(1, 5, bolusData(type = 0, startMs = t1, normalRaw = 20, deliveredRaw = 20)))
        assertTrue(r.sync.calls.isEmpty())
    }

    @Test
    fun pumpTypeFollowsTheDeviceType() {
        assertEquals("MEDTRUM_NANO", rigFeedBolus(80))
        assertEquals("MEDTRUM_NANO", rigFeedBolus(88))
        assertEquals("MEDTRUM_300U", rigFeedBolus(98))
        assertEquals("MEDTRUM_UNTESTED", rigFeedBolus(81))
        assertEquals("MEDTRUM_UNTESTED", rigFeedBolus(0))
    }

    private fun rigFeedBolus(deviceType: Int): String {
        val r = rig(deviceType)
        r.feed(frame(1, 5, bolusData(type = 1, startMs = t1, normalRaw = 20, deliveredRaw = 20)))
        return r.sync.calls.single().substringAfter("pumpType=").substringBefore(",")
    }

    // -- Basal records -----------------------------------------------------------------------

    @Test
    fun absoluteTempBasalRecordIsSyncedWithRateAndRecordDuration() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.ABSOLUTE_TEMP, t1, t2, rateRaw = 30, percent = 150)))
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=${30 * 0.05}, duration=${30 * 60_000L}, isAbsolute=true, type=NORMAL, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun relativeTempBasalRecordUsesThePercentNotTheRate() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.RELATIVE_TEMP, t1, t2, rateRaw = 30, percent = 150)))
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=150.0, duration=${30 * 60_000L}, isAbsolute=false, type=NORMAL, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun tempBasalRecordWithZeroDurationGetsTheMinimum250Ms() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.ABSOLUTE_TEMP, t1, t1, rateRaw = 30)))
        assertTrue(r.sync.calls.single().contains("duration=250,"), r.sync.calls.single())
    }

    @Test
    fun suspendBasalRecordIsSyncedAsPumpSuspendWithRateZero() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.SUSPEND_MANUAL, t1, t2)))
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=0.0, duration=${30 * 60_000L}, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun standardBasalRecordWritesNothing() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.STANDARD, t1, t2, rateRaw = 20)))
        assertTrue(r.sync.calls.isEmpty())
    }

    // -- End reason: the pump suspended a temp basal ---------------------------------------------

    @Test
    fun endReasonSuspendWritesTheSuspendWhenNoNewerTempBasalRuns() {
        val r = rig()
        r.feed(frame(2, 6, basalData(BasalType.ABSOLUTE_TEMP, t1, t2, endReason = BasalEndReason.SUSPEND_MANUAL, rateRaw = 30)))
        assertEquals(2, r.sync.calls.size)
        assertTrue(r.sync.calls[0].startsWith("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=1.5,"))
        // The status path: SUSPEND_MANUAL starts at the end time of the record, a fake long suspend.
        assertEquals(
            "syncTemporaryBasalWithPumpId(timestamp=$t2, rate=0.0, duration=${4800 * 60_000L}, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$t2, pumpType=MEDTRUM_NANO, pumpSerial=$serial)",
            r.sync.calls[1]
        )
        assertEquals(BasalType.SUSPEND_MANUAL, r.state.lastBasalType)
    }

    @Test
    fun endReasonSuspendIsIgnoredWhenANewerRealTempBasalRuns() {
        val r = rig()
        r.sync.expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = t2 + 60_000, duration = 30 * 60_000L, rate = 1.0, isAbsolute = true,
            type = PumpSync.TemporaryBasalType.NORMAL, id = 1, pumpId = t2 + 60_000
        )
        r.feed(frame(2, 6, basalData(BasalType.ABSOLUTE_TEMP, t1, t2, endReason = BasalEndReason.SUSPEND_MANUAL, rateRaw = 30)))
        assertEquals(1, r.sync.calls.size, "only the record itself")
        assertEquals(BasalType.NONE, r.state.lastBasalType, "the state was not touched")
    }

    @Test
    fun suspendCheckTable() {
        val fake = TbrOf(timestamp = 100, duration = 10_000)
        assertTrue(check(null, 50))
        assertTrue(check(fake, 100), "the running one is not newer than the end time")
        assertTrue(check(fake, 200))
        assertFalse(check(fake, 50), "newer and real")
        assertTrue(check(TbrOf(timestamp = 100, duration = 4800 * 60_000L), 50), "a fake long suspend is always replaced")
    }

    private fun TbrOf(timestamp: Long, duration: Long) = PumpSync.PumpState.TemporaryBasal(
        timestamp, duration, 1.0, true, PumpSync.TemporaryBasalType.NORMAL, 1, 1
    )

    private fun check(expected: PumpSync.PumpState.TemporaryBasal?, end: Long): Boolean {
        val r = rig()
        r.sync.expectedTemporaryBasal = expected
        return r.history.shouldSyncPumpSuspend(end)
    }

    // -- TDD ----------------------------------------------------------------------------------

    @Test
    fun tddRecordIsSyncedWithBolusAsTotalMinusBasal() {
        val r = rig()
        r.feed(frame(9, 8, tddData(t1, tdd = 12.5f, basalTdd = 7.25f)))
        assertEquals(
            listOf("createOrUpdateTotalDailyDose(timestamp=$t1, bolusAmount=5.25, basalAmount=7.25, totalAmount=12.5, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun recordsOfOtherKindsWriteNothingAndStillMoveTheSequence() {
        val r = rig()
        for (type in listOf(3, 4, 5, 6, 7, 8)) r.feed(frame(type, 10 + type, ByteArray(40)), 10 + type)
        assertTrue(r.sync.calls.isEmpty())
        assertEquals(18, r.state.syncedSequenceNumber)
    }

    // -- Basal status (command answers and notifications) ----------------------------------------

    @Test
    fun absoluteTempFromStatusUsesTheStoredRequestTypeAndDuration() {
        val r = rig()
        r.tbrs.add(PumpSync.PumpState.TemporaryBasal(t1 - 2000, 20 * 60_000L, 1.5, true, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND, 0, 0))
        r.state.handleBasalStatusUpdate(BasalType.ABSOLUTE_TEMP, 1.5, 9, 7, t1)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=1.5, duration=${20 * 60_000L}, isAbsolute=true, type=EMULATED_PUMP_SUSPEND, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
        assertEquals(20, r.state.lastBasalDuration)
        assertEquals(BasalType.ABSOLUTE_TEMP, r.state.lastBasalType)
    }

    @Test
    fun tempFromStatusWithoutStoredRequestGetsTheFakeLengthAndNoType() {
        val r = rig()
        r.state.handleBasalStatusUpdate(BasalType.ABSOLUTE_TEMP, 1.5, 9, 7, t1)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=1.5, duration=${4800 * 60_000L}, isAbsolute=true, type=null, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
        assertEquals(4800, r.state.lastBasalDuration)
    }

    @Test
    fun relativeTempFromStatusIsWrittenAsPercentOfTheBaseBasal() {
        val r = rig()
        // Profile: one basal, 1.0 U/h from minute 0. Word = (rate/0.05 shl 12) or minutes = (20 shl 12). 3 bytes, little endian.
        r.state.actualBasalProfile = byteArrayOf(1) + le((20L shl 12), 3)
        r.tbrs.add(PumpSync.PumpState.TemporaryBasal(t1, 30 * 60_000L, 1.5, false, PumpSync.TemporaryBasalType.NORMAL, 0, 0))
        r.state.handleBasalStatusUpdate(BasalType.RELATIVE_TEMP, 1.5, 9, 7, t1)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=150.0, duration=${30 * 60_000L}, isAbsolute=false, type=NORMAL, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun tempFromStatusThatIsAlreadyKnownByPumpIdWritesNothing() {
        val r = rig()
        r.sync.expectedTemporaryBasal = TbrOf(t1, 30 * 60_000L).copy(pumpId = t1)
        r.state.handleBasalStatusUpdate(BasalType.ABSOLUTE_TEMP, 1.5, 9, 7, t1)
        assertTrue(r.sync.calls.isEmpty())
    }

    @Test
    fun suspendFromStatusIsAFakeLongPumpSuspend() {
        val r = rig()
        r.state.handleBasalStatusUpdate(BasalType.SUSPEND_LOW_GLUCOSE, 0.0, 9, 7, t1)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=0.0, duration=${4800 * 60_000L}, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$t1, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun suspendFromStatusInvalidatesANewerFakeSuspend() {
        val r = rig()
        val fake = PumpSync.PumpState.TemporaryBasal(t1 + 5000, 4800 * 60_000L, 0.0, true, PumpSync.TemporaryBasalType.PUMP_SUSPEND, 1, 4242, app.aaps.core.data.pump.defs.PumpType.MEDTRUM_NANO, "AB")
        r.sync.expectedTemporaryBasal = fake
        r.state.handleBasalStatusUpdate(BasalType.SUSPEND_AUTO, 0.0, 9, 7, t1)
        assertEquals("invalidateTemporaryBasalWithPumpId(pumpId=4242, pumpType=MEDTRUM_NANO, pumpSerial=AB)", r.sync.calls[0])
        assertTrue(r.sync.calls[1].startsWith("syncTemporaryBasalWithPumpId(timestamp=$t1, rate=0.0,"))
        assertEquals(2, r.sync.calls.size)
    }

    @Test
    fun basalTypeNoneSetsTheFakeTempBasalAtPhoneTime() {
        val r = rig()
        r.state.handleBasalStatusUpdate(BasalType.NONE, 0.0, 9, 7, t1)
        assertEquals(
            listOf("syncTemporaryBasalWithPumpId(timestamp=$now, rate=0.0, duration=${4800 * 60_000L}, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$now, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun basalTypeNoneWritesNothingWhenTheFakeIsAlreadyRunning() {
        val r = rig()
        r.sync.expectedTemporaryBasal = TbrOf(t1, 4800 * 60_000L)
        r.state.handleBasalStatusUpdate(BasalType.NONE, 0.0, 9, 7, t1)
        assertTrue(r.sync.calls.isEmpty())
    }

    @Test
    fun standardBasalFromStatusStopsARunningTempBasal() {
        val r = rig()
        r.sync.expectedTemporaryBasal = TbrOf(t1 - 600_000, 30 * 60_000L)
        r.state.handleBasalStatusUpdate(BasalType.STANDARD, 1.0, 9, 7, t1)
        assertEquals(
            listOf("syncStopTemporaryBasalWithPumpId(timestamp=${t1 + 250}, endPumpId=${t1 + 250}, pumpType=MEDTRUM_NANO, pumpSerial=$serial, ignorePumpIds=false)"),
            r.sync.calls
        )
    }

    @Test
    fun standardBasalFromStatusWritesNothingWithoutATempBasal() {
        val r = rig()
        r.state.handleBasalStatusUpdate(BasalType.STANDARD, 1.0, 9, 7, t1)
        assertTrue(r.sync.calls.isEmpty())
    }

    // -- New patch -------------------------------------------------------------------------

    @Test
    fun newPatchWritesCannulaAndInsulinChange() {
        val r = rig()
        r.state.handleNewPatch(8, 1, t1)
        assertEquals(
            listOf(
                "insertTherapyEventIfNewWithTimestamp(timestamp=$t1, type=CANNULA_CHANGE, note=null, pumpId=null, pumpType=MEDTRUM_NANO, pumpSerial=$serial)",
                "insertTherapyEventIfNewWithTimestamp(timestamp=$t1, type=INSULIN_CHANGE, note=null, pumpId=null, pumpType=MEDTRUM_NANO, pumpSerial=$serial)",
            ),
            r.sync.calls
        )
    }

    // -- Bolus started by this app ---------------------------------------------------------

    @Test
    fun startedBolusIsStoredAndAddedAsTemporaryBolus() = kotlinx.coroutines.runBlocking {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 1.2; bolusType = BS.Type.SMB; timestamp = 5 }
        r.history.bolusStarted(info, now)
        assertEquals(now, info.timestamp, "the timestamp is the start of the bolus")
        assertEquals(listOf(info), r.infos.infos)
        assertEquals(
            listOf("addBolusWithTempId(timestamp=$now, amount=1.2, temporaryId=$now, type=SMB, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun bolusStoppedWithoutInsulinSyncsZeroAndRemovesTheStoredInfo() = kotlinx.coroutines.runBlocking {
        val r = rig()
        val info = DetailedBolusInfo().apply { insulin = 1.2; bolusType = BS.Type.SMB }
        r.history.bolusStarted(info, now)
        r.sync.calls.clear()
        r.history.bolusStoppedWithoutInsulin(info, now)
        assertEquals(
            listOf("syncBolusWithTempId(timestamp=$now, amount=0.0, temporaryId=$now, type=SMB, pumpId=$now, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
        assertTrue(r.infos.infos.isEmpty())
    }

    // -- Temp basal started by this app --------------------------------------------------------

    @Test
    fun tempBasalRequestIsStoredWithPhoneTimeAndDuration() {
        val r = rig()
        r.history.rememberTempBasalRequest(1.5, 30, PumpSync.TemporaryBasalType.SUPERBOLUS)
        val stored = r.tbrs.items.single()
        assertEquals(now, stored.timestamp)
        assertEquals(30 * 60_000L, stored.duration)
        assertEquals(1.5, stored.rate)
        assertTrue(stored.isAbsolute)
        assertEquals(PumpSync.TemporaryBasalType.SUPERBOLUS, stored.type)
        assertEquals(0L, stored.id)
        assertEquals(0L, stored.pumpId)
    }

    // -- Alarms ----------------------------------------------------------------------------------

    @Test
    fun alarmStateIsAnnounced() {
        val r = rig()
        val alarm = r.history.announcePumpState(MedtrumPumpState.OCCLUSION)
        assertEquals(AlarmState.OCCLUSION, alarm)
        assertEquals(
            listOf("insertAnnouncement(error=Occlusion, pumpId=null, pumpType=MEDTRUM_NANO, pumpSerial=$serial)"),
            r.sync.calls
        )
    }

    @Test
    fun patchFault2ShowsAsPatchFaultLikeAndroid() {
        val r = rig()
        r.history.announcePumpState(MedtrumPumpState.PATCH_FAULT2)
        assertTrue(r.sync.calls.single().startsWith("insertAnnouncement(error=Patch fault,"))
    }

    @Test
    fun statesWithoutAlarmAreNotAnnounced() {
        val r = rig()
        for (s in listOf(MedtrumPumpState.NONE, MedtrumPumpState.IDLE, MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT, MedtrumPumpState.STOPPED)) {
            assertNull(r.history.announcePumpState(s), "$s")
        }
        assertTrue(r.sync.calls.isEmpty())
    }

    @Test
    fun everyAlarmStateHasTheSameMappingAsAndroid() {
        val expected = mapOf(
            MedtrumPumpState.LOW_BG_SUSPENDED to AlarmState.LOW_BG_SUSPENDED,
            MedtrumPumpState.LOW_BG_SUSPENDED2 to AlarmState.LOW_BG_SUSPENDED2,
            MedtrumPumpState.AUTO_SUSPENDED to AlarmState.AUTO_SUSPENDED,
            MedtrumPumpState.HOURLY_MAX_SUSPENDED to AlarmState.HOURLY_MAX_SUSPENDED,
            MedtrumPumpState.DAILY_MAX_SUSPENDED to AlarmState.DAILY_MAX_SUSPENDED,
            MedtrumPumpState.SUSPENDED to AlarmState.SUSPENDED,
            MedtrumPumpState.PAUSED to AlarmState.PAUSED,
            MedtrumPumpState.OCCLUSION to AlarmState.OCCLUSION,
            MedtrumPumpState.EXPIRED to AlarmState.EXPIRED,
            MedtrumPumpState.RESERVOIR_EMPTY to AlarmState.RESERVOIR_EMPTY,
            MedtrumPumpState.PATCH_FAULT to AlarmState.PATCH_FAULT,
            MedtrumPumpState.PATCH_FAULT2 to AlarmState.PATCH_FAULT2,
            MedtrumPumpState.BASE_FAULT to AlarmState.BASE_FAULT,
            MedtrumPumpState.BATTERY_OUT to AlarmState.BATTERY_OUT,
            MedtrumPumpState.NO_CALIBRATION to AlarmState.NO_CALIBRATION,
        )
        for (s in MedtrumPumpState.entries) assertEquals(expected[s], MedtrumPumpSyncHistory.alarmFor(s), "$s")
    }

    // -- Fake temp basal -----------------------------------------------------------------------

    @Test
    fun fakeTempBasalIsSetOnlyWhenNotAlreadyRunning() {
        val r = rig()
        assertTrue(r.history.setFakeTbrIfNotSet())
        assertEquals(1, r.sync.calls.size)
        r.sync.expectedTemporaryBasal = TbrOf(now, 4800 * 60_000L)
        assertFalse(r.history.setFakeTbrIfNotSet())
        assertEquals(1, r.sync.calls.size)
    }

    // -- The record data class carries what Android reads ----------------------------------------

    @Test
    fun basalRecordKeepsSequenceAndPatchId() {
        val r = rig()
        var seen: MedtrumBasalRecord? = null
        val spy = object : app.aaps.pump.medtrum.comm.MedtrumRecordSync by r.history {
            override fun syncBasalRecord(record: MedtrumBasalRecord, pumpSerialHex: String) { seen = record }
        }
        GetRecordPacket(r.state, 1, spy).handleResponse(frame(2, 6, basalData(BasalType.ABSOLUTE_TEMP, t1, t2, rateRaw = 30)))
        assertEquals(6, seen?.basalSequence)
        assertEquals(7L, seen?.basalPatchId)
    }
}
