package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bolus.WizardBolusExecutor
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.convertedToPercent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
@Singleton
class LoopHubImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val constraintChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val persistenceLayer: PersistenceLayer,
    private val userEntryLogger: UserEntryLogger,
    private val preferences: Preferences,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val dateUtil: DateUtil,
    private val wizardBolusExecutor: WizardBolusExecutor,
    @ApplicationScope private val appScope: CoroutineScope
) : LoopHub {

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    // Same half-bucket threshold AutosensDataStoreObject.isAbout5minData() uses to decide whether
    // readings are "about" 5 minutes apart. Used below because the smoothing/calibration overlay's
    // timestamps do not always match a raw reading's own timestamp exactly (see getGlucoseValues doc).
    private val overlayToleranceMs = T.mins(2).plus(T.secs(30)).msecs()

    /** Returns the active insulin profile. */
    override val currentProfile: Profile? get() = runBlocking { profileFunction.getProfile() }

    /** Returns the name of the active insulin profile. */
    override val currentProfileName: String
        get() = runBlocking { profileFunction.getProfileName() }

    /** Returns the glucose unit (mg/dl or mmol/l) as selected by the user. */
    override val glucoseUnit: GlucoseUnit
        get() = GlucoseUnit.fromText(preferences.get(StringKey.GeneralUnits))

    /** Returns the remaining bolus insulin on board. */
    override val insulinOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromBolus() }.iob

    /** Returns the remaining bolus and basal insulin on board. */
    override val insulinBasalOnboard: Double
        get() = runBlocking { iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended() }.basaliob

    /** Returns the remaining carbs on board. */
    override val carbsOnboard: Double?
        get() = runBlocking { iobCobCalculator.getCobInfo("LoopHubImpl") }.displayCob

    /** Returns true if the pump is connected. */
    override val isConnected: Boolean get() = runBlocking { loop.runningMode() } != RM.Mode.DISCONNECTED_PUMP

    /** Returns true if the current profile is set of a limited amount of time. */
    override val isTemporaryProfile: Boolean
        get() {
            val ps = runBlocking { persistenceLayer.getEffectiveProfileSwitchActiveAt(clock.millis()) }
            return ps != null && ps.originalDuration > 0
        }

    /** Returns the factor by which the basal rate is currently raised (> 1) or lowered (< 1). */
    override val temporaryBasal: Double
        get() {
            return currentProfile?.let {
                val tb = runBlocking { processedTbrEbData.getTempBasalIncludingConvertedExtended(clock.millis()) }
                tb?.convertedToPercent(clock.millis(), it)?.div(100.0)
            } ?: Double.NaN
        }

    override val lowGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewLowMark), glucoseUnit
        )

    override val highGlucoseMark
        get() = profileUtil.convertToMgdl(
            preferences.get(UnitDoubleKey.OverviewHighMark), glucoseUnit
        )

    /** Tells the loop algorithm that the pump is physically connected. */
    override fun connectPump() {
        appScope.launch {
            persistenceLayer.cancelCurrentRunningMode(clock.millis(), Action.RECONNECT, Sources.Garmin)
            commandQueue.cancelTempBasal(enforceNew = true)
        }
    }

    /** Tells the loop algorithm that the pump will be physically disconnected
     *  for the given number of minutes. */
    override fun disconnectPump(minutes: Int) {
        currentProfile?.let { p ->
            appScope.launch {
                loop.handleRunningModeChange(
                    durationInMinutes = minutes,
                    profile = p,
                    newRM = RM.Mode.DISCONNECTED_PUMP,
                    action = Action.DISCONNECT,
                    source = Sources.Garmin,
                    listValues = listOf(ValueWithUnit.Minute(minutes))
                )
            }
        }
    }

    /**
     * Retrieves the glucose values starting at from.
     *
     * The persisted [GV] rows hold the raw sensor value. Where a nearby entry (within
     * [overlayToleranceMs], not a filled gap) exists in the recent smoothing/calibration overlay
     * ([iobCobCalculator]'s [app.aaps.core.interfaces.aps.AutosensDataStore.bucketedData]), that
     * entry's `recalculated` value (smoothed, falling back to calibrated) is used instead, so the
     * watch shows the same number as the main app screen.
     *
     * An exact timestamp match cannot be assumed: when a CGM source's readings are not perfectly
     * 5-minute-aligned, the overlay is rebuilt on a re-gridded timeline
     * (`AutosensDataStoreObject.createBucketedDataRecalculated`) whose timestamps only
     * coincidentally match a raw row's own timestamp, so we match to the nearest overlay entry
     * within tolerance instead of requiring an exact key match. Rows with no overlay entry within
     * tolerance (older backfill history, or no overlay available at all) keep their raw value.
     *
     * For a re-gridded source (readings more frequent than the overlay's 5-minute slots, e.g. a
     * 1-minute CGM like Libre 3), several consecutive raw rows land in the same overlay slot and
     * would otherwise all get that slot's identical value. That flattens the glucose delta Garmin
     * computes from consecutive rows to ~0 between them, with the whole 5-minute change instead
     * dumped as a spike at the slot boundary. So consecutive rows sharing the same overlay slot are
     * collapsed to a single row (the one closest to the slot's own timestamp), keeping one row per
     * distinct value the watch would actually need to see.
     */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GV> = runBlocking {
        val raw = persistenceLayer.getBgReadingsDataFromTime(from.toEpochMilli(), ascending)
        val overlay = iobCobCalculator.ads.bucketedData.orEmpty()
            .filterNot { it.filledGap }
            .sortedBy { it.timestamp }
        val result = mutableListOf<GV>()
        var lastSlot: InMemoryGlucoseValue? = null
        for (gv in raw) {
            val slot = nearestOverlay(overlay, gv.timestamp)
            if (slot != null && slot === lastSlot) {
                val prev = result[result.lastIndex]
                if (abs(gv.timestamp - slot.timestamp) < abs(prev.timestamp - slot.timestamp)) {
                    result[result.lastIndex] = gv.copy(value = slot.recalculated)
                }
                continue
            }
            result.add(slot?.let { gv.copy(value = it.recalculated) } ?: gv)
            lastSlot = slot
        }
        result
    }

    private fun nearestOverlay(overlay: List<InMemoryGlucoseValue>, timestamp: Long): InMemoryGlucoseValue? {
        if (overlay.isEmpty()) return null
        val index = overlay.binarySearch { it.timestamp.compareTo(timestamp) }
        if (index >= 0) return overlay[index]
        val insertionPoint = -(index + 1)
        val candidates = listOfNotNull(overlay.getOrNull(insertionPoint - 1), overlay.getOrNull(insertionPoint))
        return candidates.minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { abs(it.timestamp - timestamp) <= overlayToleranceMs }
    }

    /** Notifies the system that carbs were eaten and stores the value. */
    override fun postCarbs(carbohydrates: Int) {
        aapsLogger.info(LTag.GARMIN, "post $carbohydrates g carbohydrates")
        val carbsAfterConstraints =
            carbohydrates.coerceAtMost(constraintChecker.getMaxCarbsAllowed().value())
        // Instant carbs now ride the shared executor (one audited path); it logs the user entry + delivers.
        appScope.launch {
            wizardBolusExecutor.deliverCarbs(
                carbs = carbsAfterConstraints,
                note = null,
                source = Sources.Garmin,
                onError = { aapsLogger.error(LTag.GARMIN, "carbs delivery failed: $it") }
            )
        }
    }

    // mod Bolus and temp target
    /** Triggers a bolus. */
    override fun postBolus(bolus: Double) {
        aapsLogger.info(LTag.GARMIN, "trigger a bolus of $bolus U")
        userEntryLogger.log(
            action = Action.BOLUS,
            source = Sources.Garmin,
            note = null,
            ValueWithUnit.Insulin(bolus)
        )
        val detailedBolusInfo = DetailedBolusInfo().apply {
            eventType = TE.Type.SNACK_BOLUS
            insulin = bolus
        }
        appScope.launch {
            commandQueue.bolus(detailedBolusInfo)
        }
    }

    override fun postTempTarget(target: Double, duration: Int) {
        if (target == 0.0 || duration == 0) {
            appScope.launch {
                persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                    timestamp = dateUtil.now(),
                    action = Action.TT,
                    source = Sources.TTDialog,
                    note = null,
                    listValues = listOf()
                )
            }
        } else {
            appScope.launch {
                persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                    temporaryTarget = TT(
                        timestamp = dateUtil.now(),
                        duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                        reason = TT.Reason.WEAR,
                        lowTarget = profileUtil.convertToMgdl(target, profileUtil.units),
                        highTarget = profileUtil.convertToMgdl(target, profileUtil.units)
                    ),
                    action = Action.TT,
                    source = Sources.Garmin,
                    note = null,
                    listValues = listOf(
                        ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                        ValueWithUnit.Mgdl(target),
                        ValueWithUnit.Minute(duration)
                    ).filterNotNull()
                )
            }
        }
    }
    // end mod

    /** Stores hear rate readings that a taken and averaged of the given interval. */
    override fun storeHeartRate(
        samplingStart: Instant, samplingEnd: Instant,
        avgHeartRate: Int,
        device: String?
    ) {
        val hr = HR(
            timestamp = samplingStart.toEpochMilli(),
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            dateCreated = clock.millis(),
            beatsPerMinute = avgHeartRate.toDouble(),
            device = device ?: "Garmin",
        )
        appScope.launch {
            persistenceLayer.insertOrUpdateHeartRates(listOf(hr))
        }
    }

    override fun storeStepsCount(
        samplingStart: Instant,
        samplingEnd: Instant,
        steps5min: Int,
        steps10min: Int,
        steps15min: Int,
        steps30min: Int,
        steps60min: Int,
        steps180min: Int,
        device: String?,
    ) {
        val sc = SC(
            duration = samplingEnd.toEpochMilli() - samplingStart.toEpochMilli(),
            timestamp = samplingEnd.toEpochMilli(),
            steps5min = steps5min,
            steps10min = steps10min,
            steps15min = steps15min,
            steps30min = steps30min,
            steps60min = steps60min,
            steps180min = steps180min,
            device = device ?: "Garmin",
            dateCreated = clock.millis(),
        )
        appScope.launch {
            try {
                val result: PersistenceLayer.TransactionResult<SC> = persistenceLayer.insertOrUpdateStepsCount(sc)
                val id = result.inserted.firstOrNull()?.id ?: result.updated.firstOrNull()?.id
                aapsLogger.info(
                    LTag.GARMIN,
                    "✅ Steps stored in DB: ID=$id, 5min=$steps5min, timestamp=${java.util.Date(samplingEnd.toEpochMilli())}"
                )
            } catch (error: Exception) {
                aapsLogger.error(
                    LTag.GARMIN,
                    "❌ Failed to store steps: ${error.message}"
                )
            }
        }
    }
}
