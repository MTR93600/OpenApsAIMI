package app.aaps.plugins.calibration

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.CalibrationContext
import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.calibration.keys.CalibrationLongKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LinearCalibrationPluginTest : TestBase() {

    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var preferences: Preferences

    private lateinit var plugin: LinearCalibrationPlugin

    private val now: Long = 1_700_000_000_000L

    /** Default sessionStart for tests that don't override — 12h ago, past warm-up. */
    private val defaultSessionStart: Long get() = now - T.hours(12).msecs()

    @BeforeEach
    fun setUp() = runTest {
        whenever(dateUtil.now()).thenReturn(now)
        whenever(dateUtil.timeString(any())).thenReturn("12:00")
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(defaultSessionStart))
        whenever(persistenceLayer.getTherapyEventDataFromToTime(any(), any())).thenReturn(emptyList())
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(emptyList())
        // Gap detection reads the stored readings, so every calibrate() touches this.
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(preferences.get(CalibrationLongKey.IgnoredSensorGapAt)).thenReturn(0L)
        plugin = LinearCalibrationPlugin(
            aapsLogger, rh, dateUtil, persistenceLayer, notificationManager, glucoseStatusProvider, rxBus, preferences
        )
    }

    // ------------ calibrate() ------------

    @Test
    fun calibrate_emptyData_returnsEmpty() = runTest {
        val data = mutableListOf<InMemoryGlucoseValue>()
        val result = plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(result).isEmpty()
    }

    @Test
    fun calibrate_inWarmUp_returnsIdentity() = runTest {
        // Session started 1h ago — within 2h warm-up
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(now - T.hours(1).msecs()))
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        val data = bucketed(timestamps(now, every = 5))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data.all { it.calibrated == null }).isTrue()
    }

    @Test
    fun calibrate_noSessionStart_returnsIdentity() = runTest {
        // No SENSOR_CHANGE recorded — calibration must NOT blend across sensor sessions
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        val data = bucketed(listOf(now to 150.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated).isNull()
    }

    @Test
    fun calibrate_fewerThanTwoEntries_returnsIdentity() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(listOf(entry(sensor = 100.0, fs = 110.0, ageDays = 1L)))
        val data = bucketed(timestamps(now, every = 5))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data.all { it.calibrated == null }).isTrue()
    }

    @Test
    fun calibrate_validFit_appliesSlopeAndOffset() = runTest {
        // Two entries on the line y = 1.1 * x + 0
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 110.0, ageDays = 1L),
                entry(sensor = 200.0, fs = 220.0, ageDays = 1L)
            )
        )
        val data = bucketed(listOf(now to 150.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated!!).isWithin(0.01).of(165.0)
    }

    @Test
    fun calibrate_pureOffset_appliesCorrectly() = runTest {
        // Sensor reads 10 mg/dL too low across the range -> slope=1, offset=10
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 110.0, ageDays = 1L),
                entry(sensor = 200.0, fs = 210.0, ageDays = 1L)
            )
        )
        val data = bucketed(listOf(now to 150.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated!!).isWithin(0.01).of(160.0)
    }

    @Test
    fun calibrate_slopeOutOfRange_returnsIdentity() = runTest {
        // y = 2x — slope 2.0 is way outside [0.6, 1.4]
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 200.0, ageDays = 1L),
                entry(sensor = 200.0, fs = 400.0, ageDays = 1L)
            )
        )
        val data = bucketed(listOf(now to 150.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated).isNull()
    }

    @Test
    fun calibrate_clusteredEntries_appliesOffsetOnly() = runTest {
        // Five entries all within ~6 mg/dL of each other — slope estimate would be noise.
        // Mean delta (FS - sensor) = (3 + 5 + 4 + 6 + 2) / 5 = 4 mg/dL → expected correction.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 140.0, fs = 143.0, ageDays = 0L),
                entry(sensor = 141.0, fs = 146.0, ageDays = 0L),
                entry(sensor = 142.0, fs = 146.0, ageDays = 0L),
                entry(sensor = 143.0, fs = 149.0, ageDays = 0L),
                entry(sensor = 144.0, fs = 146.0, ageDays = 0L)
            )
        )
        val data = bucketed(listOf(now to 80.0, now - T.mins(5).msecs() to 250.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        // Slope locked to 1.0, so correction is constant ~4 mg/dL across the whole range.
        assertThat(data[0].calibrated!!).isWithin(0.5).of(84.0)
        assertThat(data[1].calibrated!!).isWithin(0.5).of(254.0)
    }

    @Test
    fun calibrate_offsetOutOfRange_returnsIdentity() = runTest {
        // y = x + 50 — offset 50 is outside [-30, +30]
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 150.0, ageDays = 1L),
                entry(sensor = 200.0, fs = 250.0, ageDays = 1L)
            )
        )
        val data = bucketed(listOf(now to 150.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated).isNull()
    }

    @Test
    fun calibrate_onlyAppliesAfterSessionStart() = runTest {
        // Session start 6h ago. Old (8h ago) point should NOT be calibrated.
        val sessionStart = now - T.hours(6).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        whenever(persistenceLayer.getValidCalibrationEntriesSince(eq(sessionStart))).thenReturn(goodEntriesWithSlope())
        val data = bucketed(
            listOf(
                now to 150.0,
                now - T.hours(5).msecs() to 150.0,
                now - T.hours(8).msecs() to 150.0
            )
        )
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].calibrated).isNotNull()
        assertThat(data[1].calibrated).isNotNull()
        assertThat(data[2].calibrated).isNull() // older than session start
    }

    @Test
    fun calibrate_gapDetected_postsNotification() = runTest {
        whenever(rh.gs(any<Int>(), any())).thenReturn("Possible sensor change")
        // The break must be looked for in the STORED readings: bucketed data is filled in for every
        // five minute slot, so a break can never be seen there.
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verify(notificationManager).post(
            eq(NotificationId.SENSOR_CHANGE_DETECTED),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_bucketedDataOnly_neverDetectsGap() = runTest {
        // Guards the whole point of the fix: a gap-free reading table means no notification, even
        // when the caller's bucketed data looks like it has a hole in it.
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet too, so this
        // test only exercises gap detection.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        val data = mutableListOf(
            value(now, 150.0),
            value(now - T.mins(60).msecs(), 150.0),
            value(now - T.mins(65).msecs(), 150.0)
        )
        plugin.calibrate(data, CalibrationContext.NONE)
        verify(notificationManager, never()).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_sameGapTwice_notifiesOnce() = runTest {
        whenever(rh.gs(any<Int>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet, so the only
        // notification in play is the gap one this test is actually about.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verify(notificationManager, times(1)).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_repeatedCalls_scanReadingsOnlyOncePerInterval() = runTest {
        // On a one minute sensor calibrate() runs five times more often than it was written for.
        // The scan must not read the database on every one of those runs.
        repeat(5) { plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE) }
        verify(persistenceLayer, times(1)).getBgReadingsDataFromTimeToTime(any(), any(), any())
    }

    @Test
    fun calibrate_gapWithNearbySensorChange_skipsNotification() = runTest {
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet too.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        whenever(persistenceLayer.getTherapyEventDataFromToTime(any(), any())).thenReturn(
            listOf(sensorChange(now - T.mins(35).msecs()))
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verify(notificationManager, never()).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_notificationAction_insertsSensorChange() = runTest {
        whenever(rh.gs(any<Int>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check from posting a second,
        // competing notification — this test only wants the gap-detection one.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any()))
            .thenReturn(PersistenceLayer.TransactionResult())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        val actionsCaptor = argumentCaptor<List<NotificationAction>>()
        verify(notificationManager).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            actionsCaptor.capture(),
            anyOrNull()
        )
        actionsCaptor.firstValue.first().action.invoke()

        verify(persistenceLayer).insertPumpTherapyEventIfNewByTimestamp(
            any(), any(), any(), any(), anyOrNull(), any()
        )
    }

    @Test
    fun calibrate_gapDetected_offersIgnoreWithoutLogging() = runTest {
        whenever(rh.gs(any<Int>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check from posting a second,
        // competing notification — this test only wants the gap-detection one.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        val actionsCaptor = argumentCaptor<List<NotificationAction>>()
        verify(notificationManager).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            actionsCaptor.capture(),
            anyOrNull()
        )
        assertThat(actionsCaptor.firstValue).hasSize(2)

        actionsCaptor.firstValue[1].action.invoke()
        verify(persistenceLayer, never()).insertPumpTherapyEventIfNewByTimestamp(
            any(), any(), any(), any(), anyOrNull(), any()
        )
        verify(preferences).put(CalibrationLongKey.IgnoredSensorGapAt, now - T.mins(30).msecs())
    }

    @Test
    fun calibrate_ignoredGap_doesNotRenotifyAfterRestart() = runTest {
        whenever(rh.gs(any<Int>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet too.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        val ignoredAt = now - T.mins(30).msecs()
        whenever(preferences.get(CalibrationLongKey.IgnoredSensorGapAt)).thenReturn(ignoredAt)

        val restarted = LinearCalibrationPlugin(
            aapsLogger, rh, dateUtil, persistenceLayer, notificationManager, glucoseStatusProvider, rxBus, preferences
        )
        restarted.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        verify(notificationManager, never()).post(
            any<NotificationId>(),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    // ------------ calibration health notifications ------------

    private fun verifyHealthNotificationPosted(expectedText: String) {
        verify(notificationManager).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            eq(expectedText),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    private fun verifyNoHealthNotificationPosted() {
        verify(notificationManager, never()).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            any<String>(),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_needsMoreEntries_notifiesToAddCalibration() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        // getValidCalibrationEntriesSince defaults to emptyList() from setUp().
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyHealthNotificationPosted("NEED_MORE")
    }

    @Test
    fun calibrate_unsafeFit_notifiesInconsistentCalibration() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_unsafe_fit))).thenReturn("UNSAFE")
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 200.0, ageDays = 0L),
                entry(sensor = 200.0, fs = 400.0, ageDays = 0L)
            )
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyHealthNotificationPosted("UNSAFE")
    }

    @Test
    fun calibrate_narrowRangeAndStale_notifiesToSpreadCalibrations() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_narrow_range))).thenReturn("NARROW")
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 140.0, fs = 143.0, ageDays = 3L),
                entry(sensor = 141.0, fs = 146.0, ageDays = 3L)
            )
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyHealthNotificationPosted("NARROW")
    }

    @Test
    fun calibrate_narrowRangeButFresh_doesNotNotifyYet() = runTest {
        // Same narrow-range shape as above, but the entries were just added — too soon to nag
        // about spreading calibrations out.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 140.0, fs = 143.0, ageDays = 0L),
                entry(sensor = 141.0, fs = 146.0, ageDays = 0L)
            )
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyNoHealthNotificationPosted()
        verify(notificationManager).dismiss(NotificationId.CALIBRATION_HEALTH)
    }

    @Test
    fun calibrate_goodFitButStale_notifiesToRecalibrate() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_stale))).thenReturn("STALE")
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 110.0, ageDays = 3L),
                entry(sensor = 150.0, fs = 165.0, ageDays = 3L),
                entry(sensor = 200.0, fs = 220.0, ageDays = 3L)
            )
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyHealthNotificationPosted("STALE")
    }

    @Test
    fun calibrate_healthyFreshFit_dismissesAnyExistingHealthNotification() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyNoHealthNotificationPosted()
        verify(notificationManager).dismiss(NotificationId.CALIBRATION_HEALTH)
    }

    @Test
    fun calibrate_noSessionStart_dismissesHealthNotificationInsteadOfNagging() = runTest {
        // detectAndNotifyGap already owns this case (offers to log a sensor change) — the health
        // check must stay quiet rather than pile on a second, redundant notification.
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyNoHealthNotificationPosted()
        verify(notificationManager).dismiss(NotificationId.CALIBRATION_HEALTH)
    }

    @Test
    fun calibrate_repeatedCalls_healthCheckOnlyOncePerInterval() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        repeat(5) { plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE) }
        verify(notificationManager, times(1)).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            eq("NEED_MORE"),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_sameReasonAcrossManyScans_notifiesOnlyOnce() = runTest {
        // Regression guard: an unresolved reason must not repost every scan interval forever — it
        // reads to the user as a notification roughly every 30 minutes for as long as it persists,
        // which for a condition like "stale" can be most of a sensor's life.
        whenever(rh.gs(eq(R.string.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        whenever(dateUtil.now()).thenReturn(now + T.mins(31).msecs())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        whenever(dateUtil.now()).thenReturn(now + T.mins(62).msecs())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        verify(notificationManager, times(1)).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            eq("NEED_MORE"),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_reasonChangesOnLaterScan_notifiesAgainWithTheNewReason() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        whenever(rh.gs(eq(R.string.cal_notify_unsafe_fit))).thenReturn("UNSAFE")
        // First scan: no entries yet -> "need more entries".
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        // Second scan, past the interval: entries now exist but the fit is unsafe -> a genuinely
        // different reason, which must still be announced despite the de-dup above.
        whenever(dateUtil.now()).thenReturn(now + T.mins(31).msecs())
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 200.0, ageDays = 0L),
                entry(sensor = 200.0, fs = 400.0, ageDays = 0L)
            )
        )
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        verifyHealthNotificationPosted("NEED_MORE")
        verify(notificationManager).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            eq("UNSAFE"),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    @Test
    fun calibrate_reasonResolvesThenRecurs_notifiesAgain() = runTest {
        whenever(rh.gs(eq(R.string.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        // First scan: no entries -> notifies.
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        // Second scan: healthy fit -> resolves, dismissed, and the "last reason" memory is cleared.
        whenever(dateUtil.now()).thenReturn(now + T.mins(31).msecs())
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        // Third scan: back to no entries (e.g. entries invalidated) -> the SAME reason as the first
        // scan, but it must be announced again since it had genuinely resolved in between.
        whenever(dateUtil.now()).thenReturn(now + T.mins(62).msecs())
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(emptyList())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        verify(notificationManager, times(2)).post(
            eq(NotificationId.CALIBRATION_HEALTH),
            eq("NEED_MORE"),
            any<NotificationLevel>(),
            any<Int>(),
            anyOrNull(),
            any<List<NotificationAction>>(),
            anyOrNull()
        )
    }

    // ------------ addEntry() ------------

    @Test
    fun addEntry_calmDelta_inserts() = runTest {
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(listOf(bgReading(now, 145.0)))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isEqualTo(AddEntryResult.Accepted)
        verify(persistenceLayer).insertOrUpdateCalibrationEntry(eq(CAL(timestamp = now, fingerstickMgdl = 150.0, sensorMgdlAtPairing = 145.0)))
    }

    @Test
    fun addEntry_oneMinuteReadings_pairsWithMedianNotWithTheNewest() = runTest {
        // A one minute sensor is noisier per reading. Pairing on the newest reading alone would
        // write the spike (118) into the entry and bend the fit; the median holds at 140.
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(
            listOf(
                bgReading(now, 118.0),
                bgReading(now - T.mins(1).msecs(), 140.0),
                bgReading(now - T.mins(2).msecs(), 142.0),
                bgReading(now - T.mins(3).msecs(), 141.0),
                bgReading(now - T.mins(4).msecs(), 139.0)
            )
        )
        assertThat(plugin.addEntry(bgMgdl = 150.0, timestamp = now)).isEqualTo(AddEntryResult.Accepted)
        verify(persistenceLayer).insertOrUpdateCalibrationEntry(
            eq(CAL(timestamp = now, fingerstickMgdl = 150.0, sensorMgdlAtPairing = 140.0))
        )
    }

    @Test
    fun addEntry_nullGlucoseStatus_inserts() = runTest {
        // No glucose status available -> delta gate falls through, insert proceeds
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(listOf(bgReading(now, 145.0)))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isEqualTo(AddEntryResult.Accepted)
        verify(persistenceLayer).insertOrUpdateCalibrationEntry(eq(CAL(timestamp = now, fingerstickMgdl = 150.0, sensorMgdlAtPairing = 145.0)))
    }

    @Test
    fun addEntry_highDelta_rejectsDeltaTooHigh() = runTest {
        // shortAvgDelta is in mg/dL per 5 min; threshold is 5.0 with no fit (slope=1 effective).
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 6.0))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isInstanceOf(AddEntryResult.Rejected.DeltaTooHigh::class.java)
        assertThat((result as AddEntryResult.Rejected.DeltaTooHigh).deltaMgdlPer5Min).isWithin(0.01).of(6.0)
        assertThat(result.thresholdMgdlPer5Min).isWithin(0.01).of(5.0)
        verify(persistenceLayer, never()).insertOrUpdateCalibrationEntry(any())
    }

    @Test
    fun addEntry_deltaThresholdScaledBySlopeWhenFitApplicable() = runTest {
        // Three entries imply slope = 1.05, well inside clamps → fit is applicable.
        // Effective threshold becomes 5.0 * 1.05 = 5.25 mg/dL/5min.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        // Delta 5.2: would be rejected without scaling, accepted with slope-scaled threshold.
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 5.2))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(listOf(bgReading(now, 145.0)))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isEqualTo(AddEntryResult.Accepted)
    }

    @Test
    fun addEntry_deltaExceedsScaledThreshold_rejectsWithScaledThreshold() = runTest {
        // Same fit (slope=1.05), but delta 6.0 still exceeds the scaled threshold 5.25.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 6.0))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isInstanceOf(AddEntryResult.Rejected.DeltaTooHigh::class.java)
        // The carried threshold should be the scaled value, not the raw base.
        assertThat((result as AddEntryResult.Rejected.DeltaTooHigh).thresholdMgdlPer5Min).isWithin(0.01).of(5.25)
    }

    @Test
    fun addEntry_noNearbyReading_rejectsNoSensorPair() = runTest {
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(emptyList())
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isEqualTo(AddEntryResult.Rejected.NoSensorPair)
        verify(persistenceLayer, never()).insertOrUpdateCalibrationEntry(any())
    }

    @Test
    fun addEntry_inWarmUp_rejectsInWarmUp() = runTest {
        // Session started 1h ago — inside 2h warm-up window
        val sessionStart = now - T.hours(1).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isInstanceOf(AddEntryResult.Rejected.InWarmUp::class.java)
        assertThat((result as AddEntryResult.Rejected.InWarmUp).warmUpEndsAt).isEqualTo(sessionStart + T.hours(2).msecs())
        verify(persistenceLayer, never()).insertOrUpdateCalibrationEntry(any())
    }

    @Test
    fun addEntry_noSession_rejectsNoSession() = runTest {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        val result = plugin.addEntry(bgMgdl = 150.0, timestamp = now)
        assertThat(result).isEqualTo(AddEntryResult.Rejected.NoSession)
        verify(persistenceLayer, never()).insertOrUpdateCalibrationEntry(any())
    }

    // ------------ checkPreconditions() ------------

    @Test
    fun checkPreconditions_noSession_returnsNoSession() = runTest {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        assertThat(plugin.checkPreconditions()).isEqualTo(AddEntryResult.Rejected.NoSession)
    }

    @Test
    fun checkPreconditions_inWarmUp_returnsInWarmUpWithEndsAt() = runTest {
        val sessionStart = now - T.hours(1).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        val result = plugin.checkPreconditions()
        assertThat(result).isInstanceOf(AddEntryResult.Rejected.InWarmUp::class.java)
        assertThat((result as AddEntryResult.Rejected.InWarmUp).warmUpEndsAt).isEqualTo(sessionStart + T.hours(2).msecs())
    }

    @Test
    fun checkPreconditions_highDelta_returnsDeltaTooHigh() = runTest {
        // shortAvgDelta is mg/dL per 5 min; 6.0 is above the 5.0 threshold.
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 6.0))
        val result = plugin.checkPreconditions()
        assertThat(result).isInstanceOf(AddEntryResult.Rejected.DeltaTooHigh::class.java)
    }

    @Test
    fun checkPreconditions_noNearbyReading_returnsNoSensorPair() = runTest {
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(emptyList())
        assertThat(plugin.checkPreconditions()).isEqualTo(AddEntryResult.Rejected.NoSensorPair)
    }

    @Test
    fun checkPreconditions_allClear_returnsAccepted() = runTest {
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus(shortAvgDelta = 0.5))
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false)))
            .thenReturn(listOf(bgReading(now, 145.0)))
        assertThat(plugin.checkPreconditions()).isEqualTo(AddEntryResult.Accepted)
    }

    // ------------ status() ------------

    @Test
    fun status_noSession_returnsNoSession() = runTest {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.NoSession)
    }

    @Test
    fun status_inWarmUp_returnsWarmUpWithEndsAt() = runTest {
        val sessionStart = now - T.hours(1).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        val result = plugin.status()
        assertThat(result).isEqualTo(CalibrationStatus.WarmUp(sessionStart + T.hours(2).msecs()))
    }

    @Test
    fun status_oneEntry_returnsNeedMoreEntriesWithCount() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any()))
            .thenReturn(listOf(entry(sensor = 100.0, fs = 110.0, ageDays = 1L)))
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.NeedMoreEntries(1))
    }

    @Test
    fun status_slopeOutOfRange_returnsUnsafeFit() = runTest {
        // Same fit as calibrate_slopeOutOfRange_returnsIdentity: slope 2.0 is outside [0.55, 1.6]
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 200.0, ageDays = 1L),
                entry(sensor = 200.0, fs = 400.0, ageDays = 1L)
            )
        )
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.UnsafeFit)
    }

    @Test
    fun status_clusteredEntries_returnsAppliedOffsetOnly() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 140.0, fs = 143.0, ageDays = 0L),
                entry(sensor = 141.0, fs = 146.0, ageDays = 0L)
            )
        )
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.AppliedOffsetOnly)
    }

    @Test
    fun status_validFit_returnsApplied() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(goodEntriesWithSlope())
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.Applied)
    }

    // ------------ helpers ------------

    private fun value(ts: Long, v: Double) = InMemoryGlucoseValue(
        timestamp = ts,
        value = v,
        trendArrow = TrendArrow.NONE,
        sourceSensor = SourceSensor.UNKNOWN
    )

    /** Builds a bucketed list, newest first, evenly spaced 5 minutes apart. */
    private fun bucketed(pairs: List<Pair<Long, Double>>) =
        pairs.map { (ts, v) -> value(ts, v) }.toMutableList()

    private fun timestamps(start: Long, every: Long, count: Int = 6): List<Pair<Long, Double>> =
        (0 until count).map { i -> (start - T.mins(every * i).msecs()) to 150.0 }

    private fun entry(sensor: Double, fs: Double, ageDays: Long): CAL =
        CAL(
            id = 0L,
            timestamp = now - T.days(ageDays).msecs(),
            fingerstickMgdl = fs,
            sensorMgdlAtPairing = sensor
        )

    /**
     * Three, because MIN_ENTRIES_FOR_SLOPE: with two the fit is offset-only and carries no slope.
     * Dated now, so the stored pairing is used as-is — an older entry is re-paired against the
     * readings that follow it (see the plugin's `entriesForFit`), which these tests do not set up.
     */
    private fun goodEntriesWithSlope() = listOf(
        entry(sensor = 100.0, fs = 105.0, ageDays = 0L),
        entry(sensor = 150.0, fs = 157.5, ageDays = 0L),
        entry(sensor = 200.0, fs = 210.0, ageDays = 0L)
    )

    private fun sensorChange(timestamp: Long): TE =
        TE(timestamp = timestamp, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)

    private fun glucoseStatus(shortAvgDelta: Double): GlucoseStatusSMB =
        GlucoseStatusSMB(glucose = 150.0, shortAvgDelta = shortAvgDelta, date = now)

    /** Stored readings, newest first, with a 60 minute break inside the running session. */
    private fun readingsWithGap(): List<GV> = listOf(
        bgReading(now, 150.0),
        bgReading(now - T.mins(60).msecs(), 150.0),
        bgReading(now - T.mins(61).msecs(), 150.0)
    )

    // ------------ a promoted pre-soak sensor ------------

    @Test
    fun `entries of the retired sensor are left out of the fit after a promotion`() = runTest {
        // A promoted pre-soak sensor is given a session dated at its own activation, hours before
        // the swap, so its session covers fingersticks that were taken on the sensor just retired.
        val promotedAt = now - T.hours(1).msecs()
        whenever(preferences.get(CalibrationLongKey.EntriesValidFrom)).thenReturn(0L)

        plugin.ignoreEntriesBefore(promotedAt)

        verify(preferences).put(CalibrationLongKey.EntriesValidFrom, promotedAt)
    }

    @Test
    fun `the fit only reads entries newer than the promotion`() = runTest {
        val promotedAt = now - T.hours(1).msecs()
        whenever(preferences.get(CalibrationLongKey.EntriesValidFrom)).thenReturn(promotedAt)

        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        // Not the session start, which is 12 h ago and belongs to the pre-soak. (calibrate() also
        // runs the health check, so the entries are read more than once per call.)
        verify(persistenceLayer, atLeastOnce()).getValidCalibrationEntriesSince(promotedAt)
        verify(persistenceLayer, never()).getValidCalibrationEntriesSince(defaultSessionStart)
    }

    @Test
    fun `a cut-off older than the session start changes nothing`() = runTest {
        whenever(preferences.get(CalibrationLongKey.EntriesValidFrom)).thenReturn(now - T.days(5).msecs())

        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        verify(persistenceLayer, atLeastOnce()).getValidCalibrationEntriesSince(defaultSessionStart)
    }

    // ------------ lag pairing and the stability gate ------------

    @Test
    fun `an old entry is paired again with the readings that follow it`() = runTest {
        // A fingerstick of 150 typed while the sensor still showed 120 because the fluid it reads
        // lags the blood. Ten minutes later the sensor is at 150 too. The stored pair says the
        // sensor is 30 low; the pair re-made against the later readings says it is right.
        val stickAt = now - T.hours(1).msecs()
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(CAL(id = 7L, timestamp = stickAt, fingerstickMgdl = 150.0, sensorMgdlAtPairing = 120.0))
        )
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(eq(stickAt), eq(stickAt + T.mins(15).msecs()), any()))
            .thenReturn(
                listOf(
                    bgReading(stickAt + T.mins(10).msecs(), 150.0),
                    bgReading(stickAt + T.mins(5).msecs(), 140.0)
                )
            )

        val status = plugin.status()

        // One entry only, so no line can be fitted — but the pair it now carries is the corrected
        // one, which is what the next entry will be fitted against.
        assertThat(status).isEqualTo(CalibrationStatus.NeedMoreEntries(1))
        verify(persistenceLayer).getBgReadingsDataFromTimeToTime(eq(stickAt), eq(stickAt + T.mins(15).msecs()), any())
        // The stored entry itself is never rewritten.
        verify(persistenceLayer, never()).insertOrUpdateCalibrationEntry(any())
    }

    @Test
    fun `a fresh entry keeps the pair it was stored with`() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(CAL(id = 8L, timestamp = now - T.mins(2).msecs(), fingerstickMgdl = 150.0, sensorMgdlAtPairing = 120.0))
        )

        plugin.status()

        // The window after the fingerstick is not complete yet, so nothing is read for it.
        verify(persistenceLayer, never()).getBgReadingsDataFromTimeToTime(
            eq(now - T.mins(2).msecs()),
            any(),
            any()
        )
    }

    @Test
    fun `a rising sensor is refused even when the glucose status has nothing to say`() = runTest {
        // The provider answers only for a reading of the last few minutes, so a user whose last
        // reading is 8 min old used to skip the stability check completely. The readings are still
        // there: 20 mg/dL in 10 min is 10 per 5 min, far above the gate.
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(
            listOf(
                bgReading(now - T.mins(8).msecs(), 170.0),
                bgReading(now - T.mins(18).msecs(), 150.0)
            )
        )

        val result = plugin.addEntry(bgMgdl = 175.0, timestamp = now)

        assertThat(result).isInstanceOf(AddEntryResult.Rejected.DeltaTooHigh::class.java)
        assertThat((result as AddEntryResult.Rejected.DeltaTooHigh).deltaMgdlPer5Min).isWithin(0.1).of(10.0)
    }

    @Test
    fun `a flat sensor is still accepted when the glucose status has nothing to say`() = runTest {
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), eq(false))).thenReturn(
            listOf(
                bgReading(now - T.mins(8).msecs(), 151.0),
                bgReading(now - T.mins(18).msecs(), 150.0)
            )
        )

        assertThat(plugin.addEntry(bgMgdl = 155.0, timestamp = now)).isEqualTo(AddEntryResult.Accepted)
    }

    private fun bgReading(timestamp: Long, value: Double): GV = GV(
        timestamp = timestamp,
        value = value,
        raw = null,
        noise = null,
        trendArrow = TrendArrow.NONE,
        sourceSensor = SourceSensor.UNKNOWN
    )
}
