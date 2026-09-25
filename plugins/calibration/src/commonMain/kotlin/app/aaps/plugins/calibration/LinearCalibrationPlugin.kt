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
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
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
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventCalibrationChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.calibration.keys.CalibrationLongKey
import app.aaps.core.ui.compose.icons.IcCalibration
import app.aaps.plugins.calibration.compose.CalibrationComposeContent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.Volatile
import kotlin.math.abs

@Inject
@SingleIn(AppScope::class)
// Bound as PluginBase, not implicitly: the class has more than one supertype, so Metro cannot pick
// one. The plugin list wants PluginBase.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(710)
class LinearCalibrationPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    private val notificationManager: NotificationManager,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val rxBus: RxBus,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.CALIBRATION)
        .icon(IcCalibration)
        .pluginName(CalibrationStrings.linear_calibration_name)
        .shortName(CalibrationStrings.calibration_shortname)
        .description(CalibrationStrings.description_linear_calibration)
        .composeContent { CalibrationComposeContent(persistenceLayer, profileUtil, aapsLogger, dateUtil) },
    aapsLogger, rh
), Calibration {

    private var scope: CoroutineScope? = null

    /**
     * When the stored readings were last searched for a break.
     * @Volatile, same as the ref (L69) and as [lastHealthScanAt]: two longs, not a map.
     */
    @Volatile
    private var lastGapScanAt: Long = 0L

    /** Break already told this process, so the same one is not reported again until restart. Ref L74. */
    @Volatile
    private var lastNotifiedGapAt: Long = 0L

    /** When calibration health was last checked. See `checkCalibrationHealthAndNotify`. */
    @Volatile
    private var lastHealthScanAt: Long = 0L

    /** Reason last told to the user (a `cal_notify_*` TextRef), so the same reason is
     *  not re-announced on every scan. Null once resolved or not yet checked. */
    @Volatile
    private var lastHealthMessageRes: TextRef? = null

    /**
     * Lag-corrected sensor value per calibration entry id. Worked out once per entry and kept in
     * memory only: the stored entry itself is never rewritten.
     *
     * ⚠️ ASYNC IMPACT: [AapsLock] replaces the ref's `ConcurrentHashMap` (JVM-only). The lock is not
     * held across the suspend glucose read, same as the ref, which also reads the database outside
     * the map. Two overlapping repairs of one entry both compute the same median.
     */
    private val lagPairLock = AapsLock()
    private val lagCache = CalibrationEntriesForFit.LagPairCache()

    init {
        preferences.registerPreferences(CalibrationLongKey.entries)
    }

    override suspend fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
        checkCalibrationHealthAndNotify(sessionStart, now)

        // Without a recorded SENSOR_CHANGE, entries can span multiple sensors with
        // different bias — fitting across them is unsafe. Gap detection above will
        // prompt the user to log a sensor change; until then we apply identity.
        if (sessionStart == null) {
            aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: no sensor session start, identity" }
            return data
        }

        val entries = entriesForFit(sessionStart, now)
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

        // Blend toward identity if the newest entry has gone stale — see stalenessConfidence's
        // KDoc. Applied only now, after both safety checks above passed on the RAW fit.
        val confidence = stalenessConfidence(entries.maxOf { it.timestamp }, now)
        val effective = fit.blendTowardIdentity(confidence)

        for (entry in data) {
            if (entry.timestamp >= sessionStart) {
                entry.calibrated = effective.slope * entry.value + effective.offset
            }
        }
        aapsLogger.debug(LTag.GLUCOSE) {
            "LinearCalibration: slope=${effective.slope}, offset=${effective.offset}, confidence=$confidence, applied to ${data.count { it.calibrated != null }}/${data.size}"
        }
        return data
    }

    override suspend fun checkPreconditions(): AddEntryResult = checkPreconditionsAt(dateUtil.now())

    private suspend fun checkPreconditionsAt(timestamp: Long): AddEntryResult {
        val sessionStart = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp
            ?: return AddEntryResult.Rejected.NoSession
        val warmUpEndsAt = sessionStart + T.hours(WARM_UP_HOURS).msecs()
        if (timestamp < warmUpEndsAt) return AddEntryResult.Rejected.InWarmUp(warmUpEndsAt)
        // Ref L231: shortAvgDelta ?: fallbackDeltaPer5Min(timestamp). The fallback reads the
        // stored window only when the provider has nothing (the ?: does not evaluate it otherwise).
        val shortAvgDelta = glucoseStatusProvider.glucoseStatusData?.shortAvgDelta
        val fallbackReadings = if (shortAvgDelta == null) fallbackReadings(timestamp) else emptyList()
        val delta = preconditionDelta(shortAvgDelta, fallbackReadings, timestamp)
        if (delta != null) {
            // shortAvgDelta is computed on .recalculated (calibrated) values once an applicable
            // fit is in place, so its magnitude scales with slope. Scale the raw-units threshold
            // by the active slope so a sensor rate of e.g. 5 mg/dL/5min (the "stable enough"
            // bar) is treated identically whether or not calibration is multiplying the signal.
            val activeFit = effectiveFit(entriesForFit(sessionStart, timestamp), timestamp)
            val effectiveThreshold = if (activeFit != null) {
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
     * The fit actually in effect right now, or null if there is none or it fails the safety-range
     * checks. Never a stale-but-unsafe fit "aged into" looking safe: [CalibrationFit.isApplicable]
     * is checked on the RAW fit, and staleness blending only runs afterwards.
     */
    private fun effectiveFit(entries: List<CAL>, now: Long): CalibrationFit? {
        val fit = fitLinearCalibration(entries, now) ?: return null
        if (!fit.isApplicable) return null
        return fit.blendTowardIdentity(stalenessConfidence(entries.maxOf { it.timestamp }, now))
    }

    /**
     * The session's entries, each paired with the sensor reading that actually matches it in time.
     *
     * A fingerstick measures blood, the sensor measures the fluid around the cells, and the fluid
     * follows the blood by roughly 5 to 15 minutes. Pairing a fingerstick with the reading taken
     * just BEFORE it (all that exists when the user types the value in) therefore compares two
     * different moments, and every mg/dL of that difference goes into the fit. Once the readings
     * that follow the fingerstick are in the database, the pair is re-made against them instead.
     *
     * The stored entry is left untouched. Only the fit sees the re-made pair.
     *
     * Ref `LinearCalibrationPlugin.entriesForFit` L276–284 and `lagPaired` L295–310 @ `6598201d`.
     */
    private suspend fun entriesForFit(sessionStart: Long, now: Long): List<CAL> {
        lagPairLock.withLock { lagCache.bindSession(sessionStart) }
        val from = CalibrationEntriesForFit.fitCutoff(sessionStart, preferences.get(CalibrationLongKey.EntriesValidFrom))
        return persistenceLayer.getValidCalibrationEntriesSince(from).map { lagPaired(it, now) }
    }

    override suspend fun ignoreEntriesBefore(timestamp: Long) {
        val current = preferences.get(CalibrationLongKey.EntriesValidFrom)
        if (!CalibrationEntriesForFit.shouldAdvanceEntriesValidFrom(current, timestamp)) return
        preferences.put(CalibrationLongKey.EntriesValidFrom, timestamp)
        lagPairLock.withLock { lagCache.dropPairs() }
        aapsLogger.info(LTag.GLUCOSE, "LinearCalibration: entries before $timestamp are left out of the fit")
        rxBus.send(EventCalibrationChanged())
    }

    private suspend fun lagPaired(entry: CAL, now: Long): CAL {
        val cached = lagPairLock.withLock { lagCache.cached(entry.id) }
        when (val step = CalibrationEntriesForFit.beginLagRepair(entry, now, cached)) {
            is CalibrationEntriesForFit.LagRepairStep.Keep -> return step.entry
            is CalibrationEntriesForFit.LagRepairStep.ReadWindow -> {
                val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(
                    start = step.startMs,
                    end = step.endMs,
                    ascending = false
                )
                val finished = CalibrationEntriesForFit.finishLagRepair(entry, readings, step.targetMs)
                val remembered = finished.storeInCache
                if (remembered != null) {
                    lagPairLock.withLock { lagCache.remember(entry.id, remembered) }
                }
                return finished.entry
            }
        }
    }

    override suspend fun addEntry(bgMgdl: Double, timestamp: Long): AddEntryResult {
        val pre = checkPreconditionsAt(timestamp)
        if (pre is AddEntryResult.Rejected) {
            aapsLogger.warn(LTag.GLUCOSE, "LinearCalibration.addEntry rejected: $pre")
            return pre
        }
        // Ref L356–358 (`8452e845f6`): the stored pair is the median, not the newest reading.
        // sensorValueForPairing is the existing commonMain function. It is not reimplemented here.
        val readings = pairingReadings(timestamp)
        val sensorAtPairing = sensorValueForPairing(readings, timestamp) ?: return AddEntryResult.Rejected.NoSensorPair
        persistenceLayer.insertOrUpdateCalibrationEntry(
            CAL(timestamp = timestamp, fingerstickMgdl = bgMgdl, sensorMgdlAtPairing = sensorAtPairing)
        )
        aapsLogger.debug(LTag.GLUCOSE) {
            "LinearCalibration.addEntry: fingerstick=$bgMgdl sensorAtPairing=$sensorAtPairing from ${readings.size} reading(s)"
        }
        return AddEntryResult.Accepted
    }

    /**
     * Sensor readings a fingerstick at [timestamp] may be paired with: the ones just before it.
     * Ref L343–348. Only the past is read.
     */
    private suspend fun pairingReadings(timestamp: Long): List<GV> =
        persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = timestamp - PAIR_LOOKBACK_MS,
            end = timestamp,
            ascending = false
        )

    /** Ref L321–326. The rate is [fallbackDeltaPer5Min]; this is only the window. */
    private suspend fun fallbackReadings(timestamp: Long): List<GV> =
        persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = timestamp - DELTA_FALLBACK_WINDOW_MS,
            end = timestamp,
            ascending = false
        )

    /**
     * Looks for a break in the **stored** readings that suggests a sensor change nobody wrote down.
     *
     * Ref L381–416 @ `6598201d`. The bucketed series passed to [calibrate] is not scanned: bucketing
     * fills every slot, so it has no breaks left. [now] is the plugin clock (`dateUtil.now()`),
     * not a direct system clock. `aimiWallClockMs` lives in `:plugins:aps` and is not a CGM dependency.
     *
     * ⚠️ ASYNC IMPACT: still called from the existing [calibrate] suspend path. The ignore action
     * writes a preference; it does not start a coroutine. Scan timestamps are `@Volatile`, like the
     * ref and like [lastHealthScanAt].
     */
    private suspend fun detectAndNotifyGap(sessionStart: Long?, now: Long) {
        if (!CalibrationGap.shouldScan(now, lastGapScanAt)) return
        lastGapScanAt = now

        val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(
            start = now - CalibrationGap.SCAN_WINDOW_MS,
            end = now,
            ascending = false
        )
        val detectedAt = CalibrationGap.gapWorthAsking(
            readings = readings,
            sessionStart = sessionStart,
            lastNotifiedGapAt = lastNotifiedGapAt,
            ignoredGapAt = { preferences.get(CalibrationLongKey.IgnoredSensorGapAt) }
        ) ?: return

        val nearby = persistenceLayer.getTherapyEventDataFromToTime(
            from = detectedAt - SENSOR_CHANGE_PROXIMITY_MS,
            to = detectedAt + SENSOR_CHANGE_PROXIMITY_MS
        ).any { it.type == TE.Type.SENSOR_CHANGE }
        if (nearby) return

        lastNotifiedGapAt = detectedAt
        aapsLogger.debug(LTag.GLUCOSE) { "LinearCalibration: possible sensor change at ${dateUtil.timeString(detectedAt)}" }
        notificationManager.post(
            id = NotificationId.SENSOR_CHANGE_DETECTED,
            text = rh.gs(CalibrationStrings.sensor_change_detected_text, dateUtil.timeString(detectedAt)),
            actions = listOf(
                NotificationAction(CalibrationStrings.sensor_change_detected_action) {
                    runBlocking { insertSensorChange(detectedAt) }
                },
                NotificationAction(CalibrationStrings.sensor_change_detected_ignore) {
                    preferences.put(CalibrationLongKey.IgnoredSensorGapAt, detectedAt)
                }
            )
        )
    }

    /**
     * Proactively tells the user when calibration needs attention, instead of only saying so once,
     * right after they submit a fingerstick (see `CalibrationDialogViewModel.notYetEffectiveMessage`).
     * Exactly one reason is shown at a time, most actionable first, and the notification is
     * dismissed once the situation resolves — same spaced-scan idea as `detectAndNotifyGap`.
     *
     * The same reason is asked about only once, not on every scan (same idea as
     * `detectAndNotifyGap`'s `lastNotifiedGapAt`): [NotificationManager.post] replaces the existing
     * `CALIBRATION_HEALTH` notification with a fresh one — new timestamp, and (by default
     * preference) a fresh Android system notification — on every call, whether or not anything
     * actually changed. Without this check, a persisting condition (e.g. a sensor nobody
     * recalibrated in days) reposts every [HEALTH_SCAN_INTERVAL_MS], which reads as a notification
     * every ~30 minutes for as long as the condition holds — not the single heads-up it should be.
     */
    private suspend fun checkCalibrationHealthAndNotify(sessionStart: Long?, now: Long) {
        if (now - lastHealthScanAt < HEALTH_SCAN_INTERVAL_MS) return
        lastHealthScanAt = now

        if (sessionStart == null) {
            // detectAndNotifyGap already covers this case (offers to log a sensor change).
            lastHealthMessageRes = null
            notificationManager.dismiss(NotificationId.CALIBRATION_HEALTH)
            return
        }

        val entries = entriesForFit(sessionStart, now)
        val fit = fitLinearCalibration(entries, now)
        val newestEntryAgeMs = entries.maxOfOrNull { now - it.timestamp }
        val isStale = newestEntryAgeMs != null && newestEntryAgeMs >= T.days(STALE_CONFIDENCE_FULL_DAYS).msecs()

        val messageRes = when {
            fit == null                               -> CalibrationStrings.cal_notify_need_more_entries
            !fit.isApplicable                         -> CalibrationStrings.cal_notify_unsafe_fit
            fit.mode == FitMode.OffsetOnly && isStale  -> CalibrationStrings.cal_notify_narrow_range
            isStale                                    -> CalibrationStrings.cal_notify_stale
            else                                        -> null
        }

        if (messageRes == null) {
            lastHealthMessageRes = null
            notificationManager.dismiss(NotificationId.CALIBRATION_HEALTH)
            return
        }
        if (messageRes == lastHealthMessageRes) return
        lastHealthMessageRes = messageRes
        notificationManager.post(id = NotificationId.CALIBRATION_HEALTH, text = rh.gs(messageRes))
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

        const val WARM_UP_HOURS = 2L

        /**
         * How often calibration health (need more entries / unsafe fit / narrow range / stale) may
         * be checked. Slower than gap detection: none of these conditions change on a
         * per-minute timescale, so checking every 30 minutes is plenty responsive without adding
         * noise.
         */
        val HEALTH_SCAN_INTERVAL_MS = T.mins(30).msecs()

        // GlucoseStatus.shortAvgDelta is mg/dL per 5 min — match the unit here. 5 mg/dL / 5 min
        // ≈ 1 mg/dL / min, the conventional "stable enough to calibrate" threshold across CGM apps.
        const val DELTA_GATE_MGDL_PER_5MIN = 5.0
        const val SENSOR_CHANGE_PROXIMITY_MS = 60L * 60L * 1000L
        const val PAIR_LOOKBACK_MS = 10L * 60L * 1000L
    }
}
