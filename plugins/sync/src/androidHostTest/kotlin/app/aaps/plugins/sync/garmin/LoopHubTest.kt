package app.aaps.plugins.sync.garmin

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.bolus.MasterActionExecutor
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.StringKey
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

class LoopHubTest : TestBase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var constraints: ConstraintsChecker
    @Mock lateinit var iobCobCalculator: IobCobCalculator
    @Mock lateinit var loop: Loop
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var profileUtil: ProfileUtil
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var processedTbrEbData: ProcessedTbrEbData
    @Mock lateinit var masterActionExecutor: MasterActionExecutor
    @Mock lateinit var userEntryLogger: UserEntryLogger

    private lateinit var loopHub: LoopHubImpl
    private val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeEach
    fun setup() {
        whenever(profileUtil.convertToMgdl(any(), any())).thenAnswer { i ->
            val v: Double = i.getArgument(0)
            val u: GlucoseUnit = i.getArgument(1)
            if (u == GlucoseUnit.MGDL) v else (18.0 * v)
        }
        loopHub = LoopHubImpl(
            aapsLogger, commandQueue, constraints, iobCobCalculator, loop,
            profileFunction, profileUtil, persistenceLayer, preferences, processedTbrEbData,
            masterActionExecutor, userEntryLogger, testScope
        )
        loopHub.clock = clock
    }

    @AfterEach
    fun verifyNoFurtherInteractions() {
        verifyNoMoreInteractions(commandQueue)
        verifyNoMoreInteractions(constraints)
        verifyNoMoreInteractions(iobCobCalculator)
        verifyNoMoreInteractions(loop)
        verifyNoMoreInteractions(profileFunction)
        verifyNoMoreInteractions(persistenceLayer)
    }

    @Test
    fun testCurrentProfile() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        assertEquals(profile, loopHub.currentProfile)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testCurrentProfileName() = runTest {
        whenever(profileFunction.getProfileName()).thenReturn("pro")
        assertEquals("pro", loopHub.currentProfileName)
        verify(profileFunction, times(1)).getProfileName()
    }

    @Test
    fun testGlucoseRangeMgDl() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mg/dl")
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(76.0)
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(125.0)
        assertEquals(76.0, loopHub.lowGlucoseMark, 1e-6)
        assertEquals(125.0, loopHub.highGlucoseMark, 1e-6)
        verify(preferences, atLeast(1)).get(StringKey.GeneralUnits)
        verify(preferences).get(UnitDoubleKey.OverviewLowMark)
        verify(preferences).get(UnitDoubleKey.OverviewHighMark)
    }

    @Test
    fun testGlucoseRangeMmolL() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mmol")
        whenever(preferences.get(UnitDoubleKey.OverviewLowMark)).thenReturn(3.0)
        whenever(preferences.get(UnitDoubleKey.OverviewHighMark)).thenReturn(8.0)
        assertEquals(54.0, loopHub.lowGlucoseMark, 1e-6)
        assertEquals(144.0, loopHub.highGlucoseMark, 1e-6)
        verify(preferences, atLeast(1)).get(StringKey.GeneralUnits)
        verify(preferences).get(UnitDoubleKey.OverviewLowMark)
        verify(preferences).get(UnitDoubleKey.OverviewHighMark)
    }

    @Test
    fun testGlucoseUnit() {
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mg/dl")
        assertEquals(GlucoseUnit.MGDL, loopHub.glucoseUnit)
        whenever(preferences.get(StringKey.GeneralUnits)).thenReturn("mmol")
        assertEquals(GlucoseUnit.MMOL, loopHub.glucoseUnit)
    }

    @Test
    fun testInsulinOnBoard() = runTest {
        val iobTotal = IobTotal(time = 0).apply { iob = 23.9 }
        whenever(iobCobCalculator.calculateIobFromBolus()).thenReturn(iobTotal)
        assertEquals(23.9, loopHub.insulinOnboard, 1e-10)
        verify(iobCobCalculator, times(1)).calculateIobFromBolus()
    }

    @Test
    fun testBasalOnBoard() = runTest {
        val iobBasal = IobTotal(time = 0).apply { basaliob = 23.9 }
        whenever(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(iobBasal)
        assertEquals(23.9, loopHub.insulinBasalOnboard, 1e-10)
        verify(iobCobCalculator, times(1)).calculateIobFromTempBasalsIncludingConvertedExtended()
    }

    @Test
    fun testCarbsOnBoard() = runTest {
        val cobInfo = CobInfo(0, 12.0, 0.0)
        whenever(iobCobCalculator.getCobInfo(anyString())).thenReturn(cobInfo)
        assertEquals(12.0, loopHub.carbsOnboard)
        verify(iobCobCalculator, times(1)).getCobInfo(anyString())
    }

    @Test
    fun testIsConnected() = runTest {
        whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP)
        assertEquals(true, loopHub.isConnected)
        verify(loop, times(1)).runningMode()
    }

    private fun effectiveProfileSwitch(duration: Long) = EPS(
        timestamp = 100,
        basalBlocks = emptyList(),
        isfBlocks = emptyList(),
        icBlocks = emptyList(),
        targetBlocks = emptyList(),
        glucoseUnit = GlucoseUnit.MGDL,
        originalProfileName = "foo",
        originalCustomizedName = "bar",
        originalTimeshift = 0,
        originalPercentage = 100,
        originalDuration = duration,
        originalEnd = 100 + duration,
        iCfg = ICfg("label", 0, 0)
    )

    @Test
    fun testIsTemporaryProfileTrue() = runTest {
        val eps = effectiveProfileSwitch(10)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(eps)
        assertEquals(true, loopHub.isTemporaryProfile)
        verify(persistenceLayer, times(1)).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testIsTemporaryProfileFalse() = runTest {
        val eps = effectiveProfileSwitch(0)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis())).thenReturn(eps)
        assertEquals(false, loopHub.isTemporaryProfile)
        verify(persistenceLayer).getEffectiveProfileSwitchActiveAt(clock.millis())
    }

    @Test
    fun testTemporaryBasal() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        val tb = mock<TB> {
            on { isAbsolute }.thenReturn(false)
            on { rate }.thenReturn(45.0)
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(tb)
        assertEquals(0.45, loopHub.temporaryBasal, 1e-6)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testTemporaryBasalAbsolute() = runTest {
        val profile = mock<EffectiveProfile> {
            on { getBasal(clock.millis()) }.thenReturn(2.0)
        }
        whenever(profileFunction.getProfile()).thenReturn(profile)
        val tb = mock<TB> {
            on { isAbsolute }.thenReturn(true)
            on { rate }.thenReturn(0.9)
        }
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(tb)
        assertEquals(0.45, loopHub.temporaryBasal, 1e-6)
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testTemporaryBasalNoRun() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis())).thenReturn(null)
        assertTrue(loopHub.temporaryBasal.isNaN())
        verify(profileFunction, times(1)).getProfile()
    }

    @Test
    fun testConnectPump() = runTest {
        whenever(persistenceLayer.cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.connectPump()
        verify(persistenceLayer).cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)
        verify(commandQueue).cancelTempBasal(enforceNew = true, autoForced = false)
    }

    @Test
    fun testDisconnectPump() = runTest {
        val profile = mock<EffectiveProfile>()
        whenever(profileFunction.getProfile()).thenReturn(profile)
        loopHub.disconnectPump(23)
        verify(profileFunction).getProfile()
        verify(loop).handleRunningModeChange(
            durationInMinutes = 23, profile = profile, newRM = RM.Mode.DISCONNECTED_PUMP, action = Action.DISCONNECT,
            source = Sources.Garmin, listValues = listOf(ValueWithUnit.Minute(23))
        )
    }

    @Test
    fun testGetGlucoseValues() = runTest {
        val glucoseValues = listOf(
            GV(
                timestamp = 1_000_000L, raw = 90.0, value = 93.0,
                trendArrow = TrendArrow.FLAT, noise = null,
                sourceSensor = SourceSensor.DEXCOM_G6
            )
        )
        whenever(persistenceLayer.getBgReadingsDataFromTime(1001_000, false))
            .thenReturn(glucoseValues)
        // No smoothed or calibrated data: the stored values come back as they are.
        val ads = mock<AutosensDataStore>()
        whenever(ads.dataLock).thenReturn(AapsLock())
        whenever(iobCobCalculator.ads).thenReturn(ads)
        assertArrayEquals(
            glucoseValues.toTypedArray(),
            loopHub.getGlucoseValues(Instant.ofEpochMilli(1001_000), false).toTypedArray()
        )
        verify(persistenceLayer).getBgReadingsDataFromTime(1001_000, false)
        verify(iobCobCalculator).ads
    }

    private fun rawReading(timestamp: Long, value: Double) = GV(
        timestamp = timestamp, raw = value, value = value,
        trendArrow = TrendArrow.FLAT, noise = null, sourceSensor = SourceSensor.DEXCOM_G6
    )

    private fun stubOverlay(vararg entries: InMemoryGlucoseValue) {
        val ads = mock<AutosensDataStore>()
        whenever(ads.dataLock).thenReturn(AapsLock())
        whenever(ads.bucketedData).thenReturn(entries.toMutableList())
        whenever(iobCobCalculator.ads).thenReturn(ads)
    }

    @Test
    fun testGetGlucoseValues_UsesSmoothedValueNearby() = runTest {
        whenever(persistenceLayer.getBgReadingsDataFromTime(0, true)).thenReturn(
            listOf(rawReading(300_000L, 100.0), rawReading(600_000L, 110.0), rawReading(1_200_000L, 130.0))
        )
        // 2 min off still matches; the last reading has no entry within 2.5 min and stays raw.
        stubOverlay(
            InMemoryGlucoseValue(timestamp = 300_000L, value = 100.0, smoothed = 98.0),
            InMemoryGlucoseValue(timestamp = 720_000L, value = 110.0, smoothed = 107.0),
            InMemoryGlucoseValue(timestamp = 900_000L, value = 120.0, smoothed = 118.0, filledGap = true),
        )
        val values = loopHub.getGlucoseValues(Instant.ofEpochMilli(0), true).map { it.value }
        assertEquals(listOf(98.0, 107.0, 130.0), values)
        verify(persistenceLayer).getBgReadingsDataFromTime(0, true)
        verify(iobCobCalculator).ads
    }

    /** A 1 minute CGM: readings that share one 5 minute entry become one row, so the delta is not flattened. */
    @Test
    fun testGetGlucoseValues_OneRowPerGridEntry() = runTest {
        whenever(persistenceLayer.getBgReadingsDataFromTime(0, true)).thenReturn(
            (0..4).map { rawReading(240_000L + it * 60_000L, 100.0 + it) }
        )
        stubOverlay(InMemoryGlucoseValue(timestamp = 300_000L, value = 101.0, smoothed = 101.5))
        val values = loopHub.getGlucoseValues(Instant.ofEpochMilli(0), true)
        // 240..420 s fall on the entry at 300 s; 480 s is 3 min away and keeps its raw value.
        assertEquals(listOf(300_000L to 101.5, 480_000L to 104.0), values.map { it.timestamp to it.value })
        verify(persistenceLayer).getBgReadingsDataFromTime(0, true)
        verify(iobCobCalculator).ads
    }

    @Test
    fun testPostBolus_IsLimitedAndSentThroughTheExecutor() = runTest {
        whenever(constraints.applyBolusConstraints(any())).thenAnswer { ConstraintObject(10.0, aapsLogger) }
        assertEquals(10.0, loopHub.postBolus(12.0) { })
        verify(constraints).applyBolusConstraints(any())
        verify(masterActionExecutor).deliverInsulin(eq(10.0), isNull(), eq(Sources.Garmin), any(), anyOrNull(), anyOrNull(), eq(false), anyOrNull(), any())
    }

    @Test
    fun testPostBolus_RefusalIsReported() = runTest {
        whenever(constraints.applyBolusConstraints(any())).thenAnswer { ConstraintObject(1.0, aapsLogger) }
        whenever(masterActionExecutor.deliverInsulin(any(), anyOrNull(), any(), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), any())).thenAnswer {
            it.getArgument<(MasterActionExecutor.Failure) -> Unit>(3)(MasterActionExecutor.Failure("Loop is suspended"))
        }
        var reason: String? = null
        assertEquals(1.0, loopHub.postBolus(1.0) { reason = it })
        assertEquals("Loop is suspended", reason)
        verify(constraints).applyBolusConstraints(any())
    }

    @Test
    fun testPostBolus_NothingAllowedSendsNothing() {
        whenever(constraints.applyBolusConstraints(any())).thenAnswer { ConstraintObject(0.0, aapsLogger) }
        assertEquals(0.0, loopHub.postBolus(3.0) { })
        verify(constraints).applyBolusConstraints(any())
        verifyNoInteractions(masterActionExecutor)
    }

    @Test
    fun testPostBolus_BadValueSendsNothing() {
        assertEquals(0.0, loopHub.postBolus(Double.NaN) { })
        assertEquals(0.0, loopHub.postBolus(-1.0) { })
        verifyNoInteractions(masterActionExecutor)
    }

    @Test
    fun testStoreStepsCount() = runTest {
        val samplingStart = Instant.ofEpochMilli(1_001_000)
        val samplingEnd = Instant.ofEpochMilli(1_301_000)
        val sc = SC(
            duration = 300_000,
            timestamp = samplingEnd.toEpochMilli(),
            steps5min = 12,
            steps10min = 18,
            steps15min = 24,
            steps30min = 36,
            steps60min = 48,
            steps180min = 60,
            device = "Test Device",
            dateCreated = clock.millis(),
        )
        whenever(persistenceLayer.insertOrUpdateStepsCount(sc)).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.storeStepsCount(samplingStart, samplingEnd, 12, 18, 24, 36, 48, 60, "Test Device")
        verify(persistenceLayer).insertOrUpdateStepsCount(sc)
    }

    @Test
    fun testPostCarbs() {
        val constraint = mock<Constraint<Int>> {
            on { value() }.thenReturn(99)
        }
        whenever(constraints.getMaxCarbsAllowed()).thenReturn(constraint)
        loopHub.postCarbs(100)
        verify(constraints).getMaxCarbsAllowed()
        runBlocking {
            // Carbs delivery now rides the shared executor (one audited path).
            verify(masterActionExecutor).deliverCarbs(eq(99), isNull(), eq(Sources.Garmin), any(), any())
        }
    }

    @Test
    fun testStoreHeartRate() = runTest {
        val samplingStart = Instant.ofEpochMilli(1_001_000)
        val samplingEnd = Instant.ofEpochMilli(1_101_000)
        val hr = HR(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = 101.0,
            device = "Test Device"
        )
        whenever(persistenceLayer.insertOrUpdateHeartRates(listOf(hr))).thenReturn(
            PersistenceLayer.TransactionResult()
        )
        loopHub.storeHeartRate(
            samplingStart, samplingEnd, 101, "Test Device"
        )
        kotlinx.coroutines.delay(100.milliseconds) // Give time for GlobalScope.launch to complete
        verify(persistenceLayer).insertOrUpdateHeartRates(listOf(hr))
    }

    @Test
    fun testPostTherapyMode_SportStoresKeywordOnly() = runTest {
        whenever(persistenceLayer.insertOrUpdateTherapyEvent(any())).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.postTherapyMode(" Sport ", 120)
        val te = argumentCaptor<TE>()
        verify(persistenceLayer).insertOrUpdateTherapyEvent(te.capture())
        assertEquals("sport", te.firstValue.note)
        assertEquals(TE.Type.NOTE, te.firstValue.type)
        assertEquals(GlucoseUnit.MGDL, te.firstValue.glucoseUnit)
        assertEquals(120L * 60_000L, te.firstValue.duration)
        assertEquals(clock.millis(), te.firstValue.timestamp)
        assertEquals("Garmin Widget", te.firstValue.enteredBy)
        verify(userEntryLogger).log(Action.CAREPORTAL, Sources.Garmin, "sport", emptyList())
    }

    @Test
    fun testPostTherapyMode_EmptyKeywordIsIgnored() {
        loopHub.postTherapyMode("   ", 30)
        verifyNoInteractions(userEntryLogger)
    }

    @Test
    fun testPostTempTarget_FclCompanion() = runTest {
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(
            persistenceLayer.insertAndCancelCurrentTemporaryTarget(any(), any(), any(), anyOrNull(), any())
        ).thenReturn(PersistenceLayer.TransactionResult())
        loopHub.postTempTarget(80.0, 30)
        val tt = argumentCaptor<TT>()
        verify(persistenceLayer).insertAndCancelCurrentTemporaryTarget(
            temporaryTarget = tt.capture(),
            action = eq(Action.TT),
            source = eq(Sources.Garmin),
            note = isNull(),
            listValues = eq(
                listOf(
                    ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                    ValueWithUnit.Mgdl(80.0),
                    ValueWithUnit.Minute(30),
                )
            ),
        )
        assertEquals(80.0, tt.firstValue.lowTarget)
        assertEquals(80.0, tt.firstValue.highTarget)
        assertEquals(TT.Reason.WEAR, tt.firstValue.reason)
        assertEquals(30L * 60_000L, tt.firstValue.duration)
        assertEquals(clock.millis(), tt.firstValue.timestamp)
    }
}
