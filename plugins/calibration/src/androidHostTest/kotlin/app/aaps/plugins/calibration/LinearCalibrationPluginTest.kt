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
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.calibration.keys.CalibrationLongKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
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
    @Mock lateinit var profileUtil: ProfileUtil
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
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(emptyList())
        whenever(preferences.get(CalibrationLongKey.EntriesValidFrom)).thenReturn(0L)
        whenever(preferences.get(CalibrationLongKey.IgnoredSensorGapAt)).thenReturn(0L)
        plugin = LinearCalibrationPlugin(
            aapsLogger, rh, dateUtil, persistenceLayer, notificationManager, glucoseStatusProvider, rxBus, profileUtil, preferences
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
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
        val data = bucketed(timestamps(now, every = 5))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data.all { it.calibrated == null }).isTrue()
    }

    @Test
    fun calibrate_noSessionStart_returnsIdentity() = runTest {
        // No SENSOR_CHANGE recorded — calibration must NOT blend across sensor sessions
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
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
        // Two points on y = 2x. Fewer than three entries stay offset-only (slope locked at 1).
        // The offset is +150, so the centre lift is above 30 and the line is not applied.
        // Slope bounds, when a slope is fitted, are [0.55, 1.6].
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
        // y = x + 50. The centre bounds a lift only: +50 is above 30, so the line is not applied.
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
    fun calibrate_lowEndUnsafeFit_returnsRawValue() = runTest {
        // Three points 110→135, 145→155, 180→175. Full slope 4/7, correction at 40 mg/dL is +55.
        // status() is UnsafeFit. calibrate() must leave the raw reading untouched.
        // Age 0 keeps the stored pairs: an older entry would be re-paired.
        val entries = listOf(
            entry(sensor = 110.0, fs = 135.0, ageDays = 0L),
            entry(sensor = 145.0, fs = 155.0, ageDays = 0L),
            entry(sensor = 180.0, fs = 175.0, ageDays = 0L)
        )
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(entries)
        val fit = fitLinearCalibration(entries, now)!!
        assertThat(fit.mode).isEqualTo(FitMode.Full)
        assertThat((fit.slope - 1.0) * LOW_MGDL + fit.offset).isWithin(1e-6).of(55.0)
        assertThat(fit.lowEndSafe).isFalse()
        assertThat(fit.isApplicable).isFalse()
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.UnsafeFit)

        val data = bucketed(listOf(now to 40.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].value).isEqualTo(40.0)
        assertThat(data[0].calibrated).isNull()
    }

    @Test
    fun calibrate_highEndUnsafeFit_returnsRawValue() = runTest {
        // Ref fixture is slope 1.6, offset −30 (a sensor at 300 would become 450, ratio 1.5).
        // These three points sit on y = 1.6x − 31: same slope, centre lift +29 (still ≤ 30),
        // low end safe, high-end ratio ≈ 1.497 > 1.45. highEndSafe is the only refusal.
        // status() is UnsafeFit, so calibrate() must not apply the line.
        val entries = listOf(
            entry(sensor = 100.0, fs = 129.0, ageDays = 0L),
            entry(sensor = 150.0, fs = 209.0, ageDays = 0L),
            entry(sensor = 200.0, fs = 289.0, ageDays = 0L)
        )
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(entries)
        val fit = fitLinearCalibration(entries, now)!!
        assertThat(fit.mode).isEqualTo(FitMode.Full)
        assertThat(fit.slope).isWithin(1e-9).of(1.6)
        assertThat(fit.offset).isWithin(1e-9).of(-31.0)
        assertThat(fit.correctionInRange).isTrue()
        assertThat(fit.lowEndSafe).isTrue()
        assertThat(fit.highEndSafe).isFalse()
        assertThat(fit.isApplicable).isFalse()
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.UnsafeFit)

        val data = bucketed(listOf(now to 300.0))
        plugin.calibrate(data, CalibrationContext.NONE)
        assertThat(data[0].value).isEqualTo(300.0)
        assertThat(data[0].calibrated).isNull()
    }

    @Test
    fun calibrate_onlyAppliesAfterSessionStart() = runTest {
        // Session start 6h ago. Old (8h ago) point should NOT be calibrated.
        val sessionStart = now - T.hours(6).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        whenever(persistenceLayer.getValidCalibrationEntriesSince(eq(sessionStart))).thenReturn(twoGoodEntries())
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
        whenever(rh.gs(any<TextRef>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet too, so this
        // test only exercises gap detection.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
        // The break is in the stored readings, not in the bucketed series passed to calibrate.
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        val data = mutableListOf(
            value(now, 150.0),
            value(now - T.mins(60).msecs(), 150.0),
            value(now - T.mins(65).msecs(), 150.0)
        )
        plugin.calibrate(data, CalibrationContext.NONE)
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
    fun calibrate_gapWithNearbySensorChange_skipsNotification() = runTest {
        // A healthy, fresh fit keeps the (unrelated) calibration-health check quiet too.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        whenever(persistenceLayer.getTherapyEventDataFromToTime(any(), any())).thenReturn(
            listOf(sensorChange(now - T.mins(35).msecs()))
        )
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
    fun calibrate_notificationAction_insertsSensorChange() = runTest {
        whenever(rh.gs(any<TextRef>(), any())).thenReturn("Possible sensor change")
        // A healthy, fresh fit keeps the (unrelated) calibration-health check from posting a second,
        // competing notification — this test only wants the gap-detection one.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
        whenever(persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any()))
            .thenReturn(PersistenceLayer.TransactionResult())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        val data = mutableListOf(
            value(now, 150.0),
            value(now - T.mins(60).msecs(), 150.0),
            value(now - T.mins(65).msecs(), 150.0)
        )
        plugin.calibrate(data, CalibrationContext.NONE)

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
    fun calibrate_gapDetected_ignoreWritesTheKeyAndDoesNotLogASensorChange() = runTest {
        whenever(rh.gs(any<TextRef>(), any())).thenReturn("Possible sensor change")
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
        whenever(persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any())).thenReturn(readingsWithGap())
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)

        val actionsCaptor = argumentCaptor<List<NotificationAction>>()
        verify(notificationManager).post(
            eq(NotificationId.SENSOR_CHANGE_DETECTED),
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        // getValidCalibrationEntriesSince defaults to emptyList() from setUp().
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        verifyHealthNotificationPosted("NEED_MORE")
    }

    @Test
    fun calibrate_unsafeFit_notifiesInconsistentCalibration() = runTest {
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_unsafe_fit))).thenReturn("UNSAFE")
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_narrow_range))).thenReturn("NARROW")
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_stale))).thenReturn("STALE")
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
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_unsafe_fit))).thenReturn("UNSAFE")
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
        whenever(rh.gs(eq(CalibrationStrings.cal_notify_need_more_entries))).thenReturn("NEED_MORE")
        // First scan: no entries -> notifies.
        plugin.calibrate(bucketed(listOf(now to 150.0)), CalibrationContext.NONE)
        // Second scan: healthy fit -> resolves, dismissed, and the "last reason" memory is cleared.
        whenever(dateUtil.now()).thenReturn(now + T.mins(31).msecs())
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(twoGoodEntries())
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
    fun addEntry_nullGlucoseStatus_inserts() = runTest {
        // No glucose status, and a single reading: fallbackDeltaPer5Min is null (fewer than two
        // readings), so the gate stays open and the insert proceeds. A rising pair is refused.
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
        // Fresh pairs: an entry older than PAIR_LAG_WINDOW_MS is re-paired (entriesForFit) and the
        // broad glucose stub below would replace the stored sensor values. Age 0 keeps the stored pair,
        // which is what this slope check is about. Ref dates its slope fixtures at now for the same reason.
        // Two points would be offset-only (slope 1) and the 5.2 delta would be rejected.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                CAL(id = 1L, timestamp = now, fingerstickMgdl = 105.0, sensorMgdlAtPairing = 100.0),
                CAL(id = 2L, timestamp = now, fingerstickMgdl = 157.5, sensorMgdlAtPairing = 150.0),
                CAL(id = 3L, timestamp = now, fingerstickMgdl = 210.0, sensorMgdlAtPairing = 200.0)
            )
        )
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
        // Three points: two would lock the slope at 1 and the threshold would stay 5.0.
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 105.0, ageDays = 0L),
                entry(sensor = 150.0, fs = 157.5, ageDays = 0L),
                entry(sensor = 200.0, fs = 210.0, ageDays = 0L)
            )
        )
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

    // ------------ status() — ref LinearCalibrationPluginTest L725–775 @ 6598201d ------------
    // The seven results are decided by calibrationStatus (commonTest). These lock the plugin
    // wiring: dateUtil.now(), the session read, and entriesForFit only after warm-up.

    @Test
    fun status_noSession_returnsNoSession() = runTest {
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(null)
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.NoSession)
        verify(persistenceLayer, never()).getValidCalibrationEntriesSince(any())
    }

    @Test
    fun status_inWarmUp_returnsWarmUpWithEndsAt() = runTest {
        val sessionStart = now - T.hours(1).msecs()
        whenever(persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)).thenReturn(sensorChange(sessionStart))
        val result = plugin.status()
        assertThat(result).isEqualTo(CalibrationStatus.WarmUp(sessionStart + T.hours(2).msecs()))
        verify(persistenceLayer, never()).getValidCalibrationEntriesSince(any())
    }

    @Test
    fun status_oneEntry_returnsNeedMoreEntriesWithCount() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any()))
            .thenReturn(listOf(entry(sensor = 100.0, fs = 110.0, ageDays = 0L)))
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.NeedMoreEntries(1))
    }

    @Test
    fun status_slopeOutOfRange_returnsUnsafeFit() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 200.0, ageDays = 0L),
                entry(sensor = 200.0, fs = 400.0, ageDays = 0L)
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
    fun status_slopeClamped_returnsAppliedSlopeClamped() = runTest {
        val slope = 12.0 / 7.0
        val intercept = 54.0 - slope * 72.0
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(72.0, 120.0, 172.8).map { sensor ->
                entry(sensor = sensor, fs = slope * sensor + intercept, ageDays = 0L)
            }
        )
        assertThat(plugin.status()).isEqualTo(CalibrationStatus.AppliedSlopeClamped)
    }

    @Test
    fun status_validFit_returnsApplied() = runTest {
        whenever(persistenceLayer.getValidCalibrationEntriesSince(any())).thenReturn(
            listOf(
                entry(sensor = 100.0, fs = 105.0, ageDays = 0L),
                entry(sensor = 150.0, fs = 157.5, ageDays = 0L),
                entry(sensor = 200.0, fs = 210.0, ageDays = 0L)
            )
        )
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

    private fun twoGoodEntries() = listOf(
        entry(sensor = 100.0, fs = 105.0, ageDays = 1L),
        entry(sensor = 200.0, fs = 210.0, ageDays = 1L)
    )

    private fun sensorChange(timestamp: Long): TE =
        TE(timestamp = timestamp, type = TE.Type.SENSOR_CHANGE, glucoseUnit = GlucoseUnit.MGDL)

    private fun glucoseStatus(shortAvgDelta: Double): GlucoseStatusSMB =
        GlucoseStatusSMB(glucose = 150.0, shortAvgDelta = shortAvgDelta, date = now)

    private fun readingsWithGap(): List<GV> = listOf(
        bgReading(now, 150.0),
        bgReading(now - T.mins(60).msecs(), 150.0),
        bgReading(now - T.mins(61).msecs(), 150.0)
    )

    private fun bgReading(timestamp: Long, value: Double): GV = GV(
        timestamp = timestamp,
        value = value,
        raw = null,
        noise = null,
        trendArrow = TrendArrow.NONE,
        sourceSensor = SourceSensor.UNKNOWN
    )
}
