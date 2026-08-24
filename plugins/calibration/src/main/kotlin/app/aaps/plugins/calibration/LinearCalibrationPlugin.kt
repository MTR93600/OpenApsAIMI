package app.aaps.plugins.calibration

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.calibration.AddEntryResult
import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.core.interfaces.calibration.CalibrationContext
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventCalibrationChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.icons.IcCalibration
import app.aaps.plugins.calibration.compose.CalibrationComposeContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class LinearCalibrationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val notificationManager: NotificationManager,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val rxBus: RxBus
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.CALIBRATION)
        .icon(IcCalibration)
        .pluginName(R.string.linear_calibration_name)
        .shortName(R.string.calibration_shortname)
        .description(R.string.description_linear_calibration)
        .composeContent { CalibrationComposeContent() },
    aapsLogger, rh
), Calibration {

    private var scope: CoroutineScope? = null

    /** When the readings were last searched for a break. See `detectAndNotifyGap`. */
    @Volatile
    private var lastGapScanAt: Long = 0L

    /** Break the user was already told about, so the same one is not reported again. */
    @Volatile
    private var lastNotifiedGapAt: Long = 0L

    override suspend fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Calibration entries now live in the main DB and arrive both from local entry (master)
        // and NS sync (follower). Re-emit EventCalibrationChanged on any change so the BG graph
        // recomputes the fit — replaces the event the old repository fired on insert/invalidate.
        scope?.launch {
            persistenceLayer.observeChanges<CAL>().collect {
                rxBus.send(EventCalibrationChanged())
            }
        }
        // App-wide "Reset databases" wipes the table via Room clearAllTables, which bypasses the
        // change-tracking flow above — observe the dedicated cleared signal so the graph recomputes.
        scope?.launch {
            persistenceLayer.databaseClearedFlow.collect {
                rxBus.send(EventCalibrationChanged())
            }
        }
    }

    override suspend fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    override suspend fun calibrate(
        data: MutableList<InMemoryGlucoseValue>,
        context: CalibrationContext
    ): MutableList<InMemoryGlucoseValue> {
        if (data.isEmpty()) return data

        val now = dateUtil.now()
        val sessionStart = context.sensorSessionStart
            ?: persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp

        if (sessionStart != null && now - sessionStart < T.hours(WARM_UP_HOURS).msecs()) {
            aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: in warm-up window, identity" }
            return data
        }

        detectAndNotifyGap(sessionStart, now)

        // Without a recorded SENSOR_CHANGE, entries can span multiple sensors with
        // different bias — fitting across them is unsafe. Gap detection above will
        // prompt the user to log a sensor change; until then we apply identity.
        if (sessionStart == null) {
            aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: no sensor session start, identity" }
            return data
        }

        val entries = persistenceLayer.getValidCalibrationEntriesSince(sessionStart)
        val fit = fitLinearCalibration(entries, now)
        if (fit == null) {
            aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: ${entries.size} entries (<$MIN_ENTRIES_FOR_FIT), identity" }
            return data
        }
        if (!fit.slopeInRange) {
            aapsLogger.warn(LTag.GLUCOSE, "LinearCalibration: slope ${fit.slope} outside [$SLOPE_MIN, $SLOPE_MAX], identity")
            return data
        }
        if (!fit.correctionInRange) {
            aapsLogger.warn(
                LTag.GLUCOSE,
                "LinearCalibration: mid-range correction ${fit.correctionAtCenter} mg/dL outside [$CORRECTION_AT_CENTER_MIN, $CORRECTION_AT_CENTER_MAX], identity"
            )
            return data
        }

        for (entry in data) {
            if (entry.timestamp >= sessionStart) {
                entry.calibrated = fit.slope * entry.value + fit.offset
            }
        }
        aapsLogger.debug(LTag.GLUCOSE) {
            "LinearCalibration: slope=${fit.slope}, offset=${fit.offset}, applied to ${data.count { it.calibrated != null }}/${data.size}"
        }
        return data
    }

    override suspend fun checkPreconditions(): AddEntryResult = checkPreconditionsAt(dateUtil.now())

    private suspend fun checkPreconditionsAt(timestamp: Long): AddEntryResult {
        val sessionStart = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp
            ?: return AddEntryResult.Rejected.NoSession
        val warmUpEndsAt = sessionStart + T.hours(WARM_UP_HOURS).msecs()
        if (timestamp < warmUpEndsAt) return AddEntryResult.Rejected.InWarmUp(warmUpEndsAt)
        val delta = glucoseStatusProvider.glucoseStatusData?.shortAvgDelta
        if (delta != null) {
            // shortAvgDelta is computed on .recalculated (calibrated) values once an applicable
            // fit is in place, so its magnitude scales with slope. Scale the raw-units threshold
            // by the active slope so a sensor rate of e.g. 5 mg/dL/5min (the "stable enough"
            // bar) is treated identically whether or not calibration is multiplying the signal.
            val activeFit = fitLinearCalibration(persistenceLayer.getValidCalibrationEntriesSince(sessionStart), timestamp)
            val effectiveThreshold = if (activeFit != null && activeFit.isApplicable) {
                DELTA_GATE_MGDL_PER_5MIN * activeFit.slope
            } else {
                DELTA_GATE_MGDL_PER_5MIN
            }
            if (abs(delta) > effectiveThreshold) return AddEntryResult.Rejected.DeltaTooHigh(delta, effectiveThreshold)
        }
        pairingReadings(timestamp).firstOrNull() ?: return AddEntryResult.Rejected.NoSensorPair
        return AddEntryResult.Accepted
    }

    /**
     * Sensor readings a fingerstick at [timestamp] may be paired with: the ones just before it.
     *
     * Only the past is read. A fingerstick is normally entered as it is measured, so there is
     * nothing after it yet, and a window that reached into the future would accept a pair that did
     * not exist when the user asked whether they could calibrate.
     */
    private suspend fun pairingReadings(timestamp: Long): List<GV> =
        persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = timestamp - PAIR_LOOKBACK_MS,
            end = timestamp,
            ascending = false
        )

    override suspend fun addEntry(bgMgdl: Double, timestamp: Long): AddEntryResult {
        val pre = checkPreconditionsAt(timestamp)
        if (pre is AddEntryResult.Rejected) {
            aapsLogger.warn(LTag.GLUCOSE, "LinearCalibration.addEntry rejected: $pre")
            return pre
        }
        // checkPreconditionsAt has already verified that a pair exists; re-fetch for its value.
        val readings = pairingReadings(timestamp)
        val sensorAtPairing = sensorValueForPairing(readings, timestamp) ?: return AddEntryResult.Rejected.NoSensorPair
        persistenceLayer.insertOrUpdateCalibrationEntry(CAL(timestamp = timestamp, fingerstickMgdl = bgMgdl, sensorMgdlAtPairing = sensorAtPairing))
        aapsLogger.debug(LTag.GLUCOSE) {
            "LinearCalibration.addEntry: fingerstick=$bgMgdl sensorAtPairing=$sensorAtPairing from ${readings.size} reading(s)"
        }
        return AddEntryResult.Accepted
    }

    /**
     * Looks for a break in the sensor readings that suggests a sensor change nobody wrote down,
     * and offers to write it.
     *
     * It reads the **stored** readings on purpose. It used to be given the bucketed data of the
     * caller, and could therefore never find anything: bucketing works out a value for every five
     * minute slot, so a bucketed series has no breaks left to find, whatever the sensor did. That
     * made this whole safety net silent, and a user whose source never writes a sensor change was
     * left without any session at all — no calibration, and no way to add one.
     *
     * Reading the database is not free, and this runs once per glucose value, which on a sensor
     * that speaks every minute is five times more often than the code was written for. So the scan
     * is spaced out in time, and it only looks at the recent past: a break from yesterday that the
     * user chose to ignore is old news, and asking again about it would only be noise.
     */
    private suspend fun detectAndNotifyGap(sessionStart: Long?, now: Long) {
        if (now - lastGapScanAt < GAP_SCAN_INTERVAL_MS) return
        lastGapScanAt = now

        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = now - GAP_SCAN_WINDOW_MS,
            end = now,
            ascending = false
        )
        val detectedAt = newestGapMidpoint(
            readings = readings,
            gapThresholdMs = T.mins(GAP_THRESHOLD_MIN).msecs(),
            notBefore = sessionStart
        ) ?: return
        // The same break is found again on every scan until the user acts on it. Asking once is
        // enough; a restart of AAPS asks again, which is the honest cost of keeping this in memory.
        if (detectedAt == lastNotifiedGapAt) return

        val nearby = persistenceLayer.getTherapyEventDataFromToTime(
            from = detectedAt - SENSOR_CHANGE_PROXIMITY_MS,
            to = detectedAt + SENSOR_CHANGE_PROXIMITY_MS
        ).any { it.type == TE.Type.SENSOR_CHANGE }
        if (nearby) return

        lastNotifiedGapAt = detectedAt
        aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: possible sensor change at ${dateUtil.timeString(detectedAt)}" }
        notificationManager.post(
            id = NotificationId.SENSOR_CHANGE_DETECTED,
            text = rh.gs(R.string.sensor_change_detected_text, dateUtil.timeString(detectedAt)),
            actions = listOf(
                NotificationAction(R.string.sensor_change_detected_action) {
                    runBlocking { insertSensorChange(detectedAt) }
                }
            )
        )
    }

    private suspend fun insertSensorChange(timestamp: Long) {
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = timestamp,
                type = TE.Type.SENSOR_CHANGE,
                glucoseUnit = GlucoseUnit.MGDL
            ),
            action = Action.CAREPORTAL,
            source = Sources.SensorInsert,
            note = null,
            listValues = listOf(
                ValueWithUnit.Timestamp(timestamp),
                ValueWithUnit.TEType(TE.Type.SENSOR_CHANGE)
            )
        )
    }

    private companion object {

        const val GAP_THRESHOLD_MIN = 30L
        const val WARM_UP_HOURS = 2L

        /**
         * How often the readings may be searched for a break.
         *
         * Half of [GAP_THRESHOLD_MIN], so a break is still found while it is fresh, and the
         * database is read six times an hour instead of sixty on a one minute sensor.
         */
        val GAP_SCAN_INTERVAL_MS = T.mins(GAP_THRESHOLD_MIN / 2).msecs()

        /**
         * How far back a break is looked for. A sensor change worth writing down is a recent one:
         * an older break was either already answered or knowingly left alone.
         */
        val GAP_SCAN_WINDOW_MS = T.hours(6).msecs()

        // GlucoseStatus.shortAvgDelta is mg/dL per 5 min — match the unit here. 5 mg/dL / 5 min
        // ≈ 1 mg/dL / min, the conventional "stable enough to calibrate" threshold across CGM apps.
        const val DELTA_GATE_MGDL_PER_5MIN = 5.0
        const val SENSOR_CHANGE_PROXIMITY_MS = 60L * 60L * 1000L
        const val PAIR_LOOKBACK_MS = 10L * 60L * 1000L
    }
}
