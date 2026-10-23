package app.aaps.implementation.alerts

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.glucose.GlucoseCorrection
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.notifications.AlarmAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.alerts.keys.LocalAlertLongKey
import app.aaps.implementation.glucose.GlucoseCorrectionImpl
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocalAlertUtilsImplTest : TestBase() {

    @Mock lateinit var preferences: Preferences
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var activePlugin: ActivePlugin
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var profileUtil: ProfileUtil
    @Mock lateinit var smsCommunicator: SmsCommunicator
    @Mock lateinit var config: Config
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var glucoseCorrection: GlucoseCorrection
    @Mock lateinit var pump: PumpWithConcentration
    @Mock lateinit var pumpDescription: PumpDescription

    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var localAlertUtils: LocalAlertUtilsImpl

    private val now = 100000000L

    companion object {

        @JvmStatic
        @BeforeAll
        fun initializeEnums() {
            // Force enum initialization before any tests run to avoid circular dependency
            // This must happen at class load time, before test methods try to use IntKey
            try {
                // Initialize in dependency order: BooleanKey and StringKey first, then IntKey
                Class.forName("app.aaps.core.keys.BooleanKey")
                Class.forName("app.aaps.core.keys.StringKey")
                Class.forName("app.aaps.core.keys.IntKey")
            } catch (e: Throwable) {
                // Swallow initialization errors - they'll surface in actual test execution
                System.err.println("Warning: Enum initialization failed in test setup: ${e.message}")
            }
        }
    }

    @BeforeEach
    fun setup() {
        localAlertUtils = LocalAlertUtilsImpl(
            aapsLogger,
            preferences,
            rh,
            activePlugin,
            profileFunction,
            profileUtil,
            smsCommunicator,
            config,
            persistenceLayer,
            dateUtil,
            notificationManager,
            glucoseCorrection,
            testScope
        )
        whenever(dateUtil.now()).thenReturn(now)
        // Mockito answers 0.0 for a Double returning call, which would look like a hypo to every
        // alarm. Default to "no corrected value available" so each test states its own correction.
        whenever(glucoseCorrection.correctedMgdl(any(), any())).thenReturn(null)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(pump.pumpDescription).thenReturn(pumpDescription)
        whenever(pumpDescription.hasCustomUnreachableAlertCheck).thenReturn(false)
        runTest {
            whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any()))
                .thenReturn(PersistenceLayer.TransactionResult())
        }
    }

    @Test
    fun `preSnoozeAlarms sets next missed readings alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update next missed readings alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextMissedReadingsAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms sets next pump disconnected alarm when expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `preSnoozeAlarms does not update pump disconnected alarm when not expired`() {
        val futureTime = now + 10000
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(futureTime)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureTime)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms updates both alarms when both expired`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 2000)

        localAlertUtils.preSnoozeAlarms()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + 5 * 60 * 1000)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + 5 * 60 * 1000)
    }

    @Test
    fun `shortenSnoozeInterval limits missed readings alarm to threshold`() {
        val thresholdMinutes = 30
        val farFutureAlarm = now + T.hours(5).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(farFutureAlarm)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval does not change alarm if already within threshold`() {
        val thresholdMinutes = 30
        val alarmTime = now + T.mins(10).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(alarmTime)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(alarmTime)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, alarmTime)
    }

    @Test
    fun `shortenSnoozeInterval limits pump disconnected alarm to threshold`() {
        val thresholdMinutes = 20
        val farFutureAlarm = now + T.hours(10).msecs()

        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(farFutureAlarm)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `shortenSnoozeInterval handles both alarms correctly`() {
        val missedReadingsThreshold = 25
        val pumpUnreachableThreshold = 35

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(missedReadingsThreshold)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(pumpUnreachableThreshold)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + T.hours(1).msecs())
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + T.hours(2).msecs())

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(missedReadingsThreshold.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(pumpUnreachableThreshold.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead updates alarm when profile is available`() = runTest {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.EffectiveProfile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(MutableStateFlow(lastDataTime))
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.reportPumpStatusRead()

        val expectedAlarmTime = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedAlarmTime)
    }

    @Test
    fun `reportPumpStatusRead does not update alarm when profile is null`() = runTest {
        whenever(profileFunction.getProfile()).thenReturn(null)

        localAlertUtils.reportPumpStatusRead()

        verify(preferences, never()).put(any<LocalAlertLongKey>(), any<Long>())
    }

    @Test
    fun `reportPumpStatusRead does not decrease alarm time`() = runTest {
        val lastDataTime = now - T.mins(5).msecs()
        val thresholdMinutes = 30
        val futureAlarmTime = now + T.hours(2).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.EffectiveProfile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(MutableStateFlow(lastDataTime))
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(futureAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        // Should not update because futureAlarmTime is already later than earliestAlarmTime
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextPumpDisconnectedAlarm), any())
    }

    @Test
    fun `preSnoozeAlarms uses exact 5 minute snooze`() {
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now - 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now - 1000)

        localAlertUtils.preSnoozeAlarms()

        val expectedSnooze = now + 5 * 60 * 1000
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, expectedSnooze)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedSnooze)
    }

    @Test
    fun `shortenSnoozeInterval handles edge case with zero threshold`() {
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(0)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(0)
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(now + 1000)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(now + 1000)

        localAlertUtils.shortenSnoozeInterval()

        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now)
    }

    @Test
    fun `shortenSnoozeInterval uses minimum of current alarm and threshold`() {
        val thresholdMinutes = 30
        val currentAlarmClose = now + T.mins(10).msecs()
        val currentAlarmFar = now + T.mins(50).msecs()

        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)

        // Test with close alarm - should keep it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmClose)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmClose)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, currentAlarmClose)
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, currentAlarmClose)

        // Test with far alarm - should shorten it
        whenever(preferences.get(LocalAlertLongKey.NextMissedReadingsAlarm)).thenReturn(currentAlarmFar)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmFar)
        localAlertUtils.shortenSnoozeInterval()
        verify(preferences).put(LocalAlertLongKey.NextMissedReadingsAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, now + T.mins(thresholdMinutes.toLong()).msecs())
    }

    @Test
    fun `reportPumpStatusRead calculates earliest alarm time correctly`() = runTest {
        val lastDataTime = now - T.mins(10).msecs()
        val thresholdMinutes = 40
        val currentAlarmTime = now + T.mins(5).msecs()
        val profile = org.mockito.kotlin.mock<app.aaps.core.interfaces.profile.EffectiveProfile>()

        whenever(profileFunction.getProfile()).thenReturn(profile)
        whenever(pump.lastDataTime).thenReturn(MutableStateFlow(lastDataTime))
        whenever(preferences.get(IntKey.AlertsPumpUnreachableThreshold)).thenReturn(thresholdMinutes)
        whenever(preferences.get(LocalAlertLongKey.NextPumpDisconnectedAlarm)).thenReturn(currentAlarmTime)

        localAlertUtils.reportPumpStatusRead()

        val expectedEarliestAlarm = lastDataTime + T.mins(thresholdMinutes.toLong()).msecs()
        verify(preferences).put(LocalAlertLongKey.NextPumpDisconnectedAlarm, expectedEarliestAlarm)
    }

    // region checkGlucoseAlerts — hypo / hyper / rapid fall (notification-only, never dosing)

    private fun gv(value: Double, timestamp: Long = now) = GV(
        timestamp = timestamp, raw = null, value = value,
        trendArrow = TrendArrow.NONE, noise = null, sourceSensor = SourceSensor.UNKNOWN
    )

    /** Fresh last reading + non-stale threshold so the freshness guard passes. */
    private suspend fun freshLast(value: Double) {
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(gv(value, timestamp = now))
    }

    @Test // A1 — hypo fires below threshold
    fun `hypo alarm fires when glucose at or below threshold`() = runTest {
        freshLast(68.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
    }

    @Test // A1b — the alarm must judge the value the screen shows, not the plain sensor value
    fun `hypo alarm uses the corrected value and not the stored sensor value`() = runTest {
        freshLast(56.0) // sensor says 56, the screen shows 65
        whenever(glucoseCorrection.correctedMgdl(now, 56.0)).thenReturn(65.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(60.0)

        localAlertUtils.checkGlucoseAlerts()

        // 65 is above threshold 60 plus the 5 hysteresis, so it counts as recovered, not as a hypo.
        verify(preferences, never()).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
        verify(notificationManager).dismiss(NotificationId.BG_HYPO)
        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, 0L)
    }

    @Test // A1c — no corrected value available: the stored value still has to raise the alarm
    fun `hypo alarm falls back to the stored value when no correction is available`() = runTest {
        freshLast(56.0)
        whenever(glucoseCorrection.correctedMgdl(now, 56.0)).thenReturn(null)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(60.0)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
    }

    @Test // A1d — real correction, stale series: the old head of 100 must not hide a fresh 50
    fun `hypo alarm with a stale corrected series uses the stored low value`() = runTest {
        val iobCobCalculator: IobCobCalculator = mock()
        val autosensDataStore: AutosensDataStore = mock()
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)
        // The newest corrected point is 20 minutes old and shows 100. Taking its value would pass the
        // plausibility net (ratio 2.0) and hide the hypo.
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(
            mutableListOf(InMemoryGlucoseValue(timestamp = now - T.mins(20).msecs(), value = 100.0, smoothed = 100.0))
        )
        val withRealCorrection = LocalAlertUtilsImpl(
            aapsLogger, preferences, rh, activePlugin, profileFunction, profileUtil, smsCommunicator, config,
            persistenceLayer, dateUtil, notificationManager, GlucoseCorrectionImpl { iobCobCalculator }, testScope
        )
        freshLast(50.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1)

        withRealCorrection.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
        verify(notificationManager, never()).dismiss(NotificationId.BG_HYPO)
    }

    @Test // The drop is measured on the corrected curve the user sees, not on the raw readings
    fun `rapid fall alarm uses the corrected values`() = runTest {
        freshLast(115.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(preferences.get(IntKey.AlertRapidFallDrop)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextRapidFallAlarm)).thenReturn(now - 1)
        val start = now - T.mins(15).msecs()
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(150.0, start), gv(115.0, now))) // raw drop 35
        // The screen shows 140 -> 125: a drop of 15, not a fast fall.
        whenever(glucoseCorrection.correctedMgdl(start, 150.0)).thenReturn(140.0)
        whenever(glucoseCorrection.correctedMgdl(now, 115.0)).thenReturn(125.0)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(LocalAlertLongKey.NextRapidFallAlarm, now + T.mins(15).msecs())
    }

    @Test // A3 — hypo disabled: nothing
    fun `hypo alarm does not fire when disabled`() = runTest {
        freshLast(50.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(false)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
        verify(notificationManager, never()).dismiss(NotificationId.BG_HYPO)
    }

    @Test // A10 — throttle: active but within re-alarm interval → no re-post
    fun `hypo alarm is throttled while re-alarm interval not reached`() = runTest {
        freshLast(68.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now + T.mins(5).msecs())

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
    }

    @Test // A2b — hypo dismissed once clearly above threshold + hysteresis
    fun `hypo alarm dismissed when recovered beyond hysteresis`() = runTest {
        freshLast(76.0) // threshold 70 + 5 hysteresis = 75 → 76 >= 75
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)

        localAlertUtils.checkGlucoseAlerts()

        verify(notificationManager).dismiss(NotificationId.BG_HYPO)
        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, 0L)
    }

    @Test // A2a — still within hysteresis band: neither re-post nor dismiss
    fun `hypo alarm held within hysteresis band`() = runTest {
        freshLast(73.0) // above threshold 70 but below 75 → hold
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
        verify(notificationManager, never()).dismiss(NotificationId.BG_HYPO)
    }

    @Test // A4 — hyper fires at or above threshold
    fun `hyper alarm fires when glucose at or above threshold`() = runTest {
        freshLast(260.0)
        whenever(preferences.get(BooleanKey.AlertHyper)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHyperThreshold)).thenReturn(250.0)
        whenever(preferences.get(LocalAlertLongKey.NextHyperAlarm)).thenReturn(now - 1)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextHyperAlarm, now + T.mins(30).msecs())
    }

    @Test // A5 — hyper dismissed below threshold - hysteresis
    fun `hyper alarm dismissed when recovered beyond hysteresis`() = runTest {
        freshLast(239.0) // threshold 250 - 10 = 240 → 239 <= 240
        whenever(preferences.get(BooleanKey.AlertHyper)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHyperThreshold)).thenReturn(250.0)

        localAlertUtils.checkGlucoseAlerts()

        verify(notificationManager).dismiss(NotificationId.BG_HYPER)
        verify(preferences).put(LocalAlertLongKey.NextHyperAlarm, 0L)
    }

    @Test // A6 — rapid fall fires when drop over window exceeds threshold
    fun `rapid fall alarm fires when drop exceeds threshold`() = runTest {
        freshLast(115.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(preferences.get(IntKey.AlertRapidFallDrop)).thenReturn(30)
        whenever(preferences.get(LocalAlertLongKey.NextRapidFallAlarm)).thenReturn(now - 1)
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(150.0, now - T.mins(15).msecs()), gv(115.0, now)))

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextRapidFallAlarm, now + T.mins(15).msecs())
    }

    @Test // A7 — drop below threshold but not flat enough to dismiss: nothing
    fun `rapid fall alarm does not fire for a shallow drop`() = runTest {
        freshLast(130.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(preferences.get(IntKey.AlertRapidFallDrop)).thenReturn(30)
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(150.0, now - T.mins(15).msecs()), gv(130.0, now))) // drop 20

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextRapidFallAlarm), any())
    }

    @Test // The drop size is mg/dL for both unit settings: 20 means 20 mg/dL, also on an mmol phone
    fun `rapid fall alarm uses a 20 mg per dL drop on an mmol phone`() = runTest {
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MMOL)
        freshLast(128.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(preferences.get(IntKey.AlertRapidFallDrop)).thenReturn(20)
        whenever(preferences.get(LocalAlertLongKey.NextRapidFallAlarm)).thenReturn(now - 1)
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(150.0, now - T.mins(15).msecs()), gv(128.0, now))) // drop 22

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextRapidFallAlarm, now + T.mins(15).msecs())
    }

    @Test // Same 20 mg/dL threshold, a drop of 18 is not a rapid fall
    fun `rapid fall alarm stays quiet below a 20 mg per dL drop`() = runTest {
        freshLast(132.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(preferences.get(IntKey.AlertRapidFallDrop)).thenReturn(20)
        whenever(preferences.get(LocalAlertLongKey.NextRapidFallAlarm)).thenReturn(now - 1)
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(150.0, now - T.mins(15).msecs()), gv(132.0, now))) // drop 18

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(LocalAlertLongKey.NextRapidFallAlarm, now + T.mins(15).msecs())
    }

    @Test // A8 — data gap: fewer than 2 readings in window → nothing
    fun `rapid fall alarm skips on data gap`() = runTest {
        freshLast(115.0)
        whenever(preferences.get(BooleanKey.AlertRapidFall)).thenReturn(true)
        whenever(preferences.get(IntKey.AlertRapidFallWindow)).thenReturn(15)
        whenever(persistenceLayer.getBgReadingsDataFromTime(any(), eq(true)))
            .thenReturn(listOf(gv(115.0, now)))

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextRapidFallAlarm), any())
    }

    @Test // Regression: field report 2026-08-06 — "high glucose" alerts at 5.5 mmol/L on an mmol phone
    fun `mmol phone does not raise a hyper alarm at normal glucose`() = runTest {
        freshLast(100.0) // 5.5 mmol/L
        whenever(preferences.get(BooleanKey.AlertHyper)).thenReturn(true)
        // Stored threshold is always mg/dL; the display getter converts it to the user's units.
        // Reading the converted 13.9 made every value look "high".
        whenever(preferences.getRaw(UnitDoubleKey.AlertHyperThreshold)).thenReturn(250.0)
        whenever(preferences.get(UnitDoubleKey.AlertHyperThreshold)).thenReturn(13.9)
        whenever(preferences.get(LocalAlertLongKey.NextHyperAlarm)).thenReturn(now - 1)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHyperAlarm), eq(now + T.mins(30).msecs()))
    }

    @Test // Regression: the same unit bug silently disabled the hypo alarm for every mmol user
    fun `mmol phone still raises the hypo alarm below threshold`() = runTest {
        freshLast(68.0) // 3.8 mmol/L
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        whenever(preferences.get(UnitDoubleKey.AlertHypoThreshold)).thenReturn(3.9)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
    }

    @Test // "Hypo treated" action: hold the alarm while the carbs work
    fun `hypo treated action holds the alarm for 20 minutes and clears it`() {
        localAlertUtils.snoozeHypoAfterTreatment()

        verify(preferences).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(20).msecs())
        verify(notificationManager).dismiss(NotificationId.BG_HYPO)
    }

    @Test // A tap during a running check must not be overwritten by that check
    fun `hypo treated tap waits for a running check and is written last`() = runTest {
        val readingGate = CompletableDeferred<Unit>()
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        // The check stops inside the lock until the gate opens.
        whenever(persistenceLayer.getLastGlucoseValue()).doSuspendableAnswer { readingGate.await(); gv(60.0) }
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1)

        val check = testScope.launch { localAlertUtils.checkGlucoseAlerts() }
        localAlertUtils.snoozeHypoAfterTreatment()
        // The tap is waiting for the check: nothing written yet.
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())

        readingGate.complete(Unit)
        check.join()

        val order = inOrder(preferences)
        order.verify(preferences, timeout(1_000)).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(15).msecs())
        order.verify(preferences, timeout(1_000)).put(LocalAlertLongKey.NextHypoAlarm, now + T.mins(20).msecs())
    }

    @Test // The lock screen is the only surface the user sees during a real hypo
    fun `the hypo alarm declares the treated action for its system notification`() {
        assertThat(NotificationId.BG_HYPO.alarmAction).isEqualTo(AlarmAction.HYPO_TREATED)
        // Not offered where no treatment can clear the condition.
        assertThat(NotificationId.BG_HYPER.alarmAction).isNull()
        assertThat(NotificationId.BG_RAPID_FALL.alarmAction).isNull()
    }

    @Test // A9 + invariant J2 — stale data: no glucose alarm at all
    fun `no glucose alarm fires on stale data`() = runTest {
        whenever(preferences.get(IntKey.AlertsStaleDataThreshold)).thenReturn(30)
        whenever(persistenceLayer.getLastGlucoseValue())
            .thenReturn(gv(50.0, timestamp = now - T.mins(45).msecs())) // older than 30 min
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHyperAlarm), any())
        verify(preferences, never()).put(eq(LocalAlertLongKey.NextRapidFallAlarm), any())
    }

    @Test // guard — no stored glucose at all → nothing
    fun `no glucose alarm fires when there is no reading`() = runTest {
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)

        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, never()).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
    }

    @Test // The periodic pass and the new-reading event can both run: the second must not post again
    fun `a second check right after a hypo alarm does not post again`() = runTest {
        freshLast(68.0)
        whenever(preferences.get(BooleanKey.AlertHypo)).thenReturn(true)
        whenever(preferences.getRaw(UnitDoubleKey.AlertHypoThreshold)).thenReturn(70.0)
        // First read: due. Second read: the time the first check just stored.
        whenever(preferences.get(LocalAlertLongKey.NextHypoAlarm)).thenReturn(now - 1, now + T.mins(15).msecs())

        localAlertUtils.checkGlucoseAlerts()
        localAlertUtils.checkGlucoseAlerts()

        verify(preferences, times(1)).put(eq(LocalAlertLongKey.NextHypoAlarm), any())
    }

    // endregion
}
