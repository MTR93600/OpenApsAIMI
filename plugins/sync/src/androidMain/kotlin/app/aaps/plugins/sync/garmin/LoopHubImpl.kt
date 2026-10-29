package app.aaps.plugins.sync.garmin

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.bolus.MasterActionExecutor
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
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToPercent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import kotlin.math.abs

/**
 * Interface to the functionality of the looping algorithm and storage systems.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class LoopHubImpl(
    private val aapsLogger: AAPSLogger,
    private val commandQueue: CommandQueue,
    private val constraintChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val masterActionExecutor: MasterActionExecutor,
    private val userEntryLogger: UserEntryLogger,
    @ApplicationScope private val appScope: CoroutineScope
) : LoopHub {

    val disposable = CompositeDisposable()

    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()

    // The same half bucket that AutosensDataStoreObject.isAbout5minData() uses to decide that readings
    // are "about" 5 minutes apart. See getGlucoseValues for why an exact time match cannot be used.
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

    override val temporaryBasalRemainingMinutes: Int?
        get() = runBlocking {
            val now = clock.millis()
            val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now) ?: return@runBlocking null
            val remainingMs = tb.end - now
            if (remainingMs <= 0L) null else (remainingMs / 60_000L).toInt().coerceAtLeast(1)
        }

    override val currentTargetMgdl: Double?
        get() = runBlocking {
            // Prefer the targetBG of the last APS run (AIMI writes it in rT.targetBG), the same
            // value the overview shows. Not the static profile middle.
            val apsTarget = loop.lastRun?.constraintsProcessed?.targetBG
            if (apsTarget != null && apsTarget.isFinite() && apsTarget > 0.0) {
                return@runBlocking apsTarget
            }
            val now = clock.millis()
            val tt = persistenceLayer.getTemporaryTargetActiveAt(now)
            when {
                tt != null -> (tt.lowTarget + tt.highTarget) / 2.0
                else -> currentProfile?.getRoundedTargetMgdl()
            }
        }

    override val activeTherapyMode: String?
        get() = runBlocking {
            val now = clock.millis()
            // Look back far enough for long modes (sport / highcarb up to several hours).
            val from = now - TimeUnit.HOURS.toMillis(12)
            val notes = persistenceLayer.getTherapyEventDataFromTime(from, TE.Type.NOTE, ascending = false)
            GarminActiveTherapyMode.detect(notes, now)
        }

    /**
     * Reads the TBR (also a converted extended bolus) every 5 minutes over [windowMs] and keeps a
     * step only when the percent changes.
     * ⚠️ ASYNC IMPACT: runBlocking on processedTbrEbData + profile.
     */
    override fun temporaryBasalHistory(windowMs: Long): List<BasalHistoryStep> = runBlocking {
        val now = clock.millis()
        val from = now - windowMs.coerceAtLeast(TimeUnit.MINUTES.toMillis(5))
        val profile = currentProfile
        val stepMs = TimeUnit.MINUTES.toMillis(5)
        val out = ArrayList<BasalHistoryStep>()
        var t = from
        var lastPct = Int.MIN_VALUE
        while (t <= now) {
            val pct = if (profile == null) {
                100
            } else {
                val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(t)
                tb?.convertedToPercent(t, profile) ?: 100
            }
            if (pct != lastPct) {
                out.add(BasalHistoryStep(t = t, p = pct.coerceIn(0, 500)))
                lastPct = pct
            }
            t += stepMs
        }
        if (out.isEmpty()) out.add(BasalHistoryStep(t = from, p = 100))
        out
    }

    /**
     * Valid SMBs in [windowMs] with timestamp + amount (U).
     * ⚠️ ASYNC IMPACT: runBlocking on persistenceLayer.getBolusesFromTime.
     */
    override fun smbHistory(windowMs: Long): List<SmbHistoryStep> = runBlocking {
        val from = clock.millis() - windowMs.coerceAtLeast(0L)
        persistenceLayer.getBolusesFromTime(from, ascending = true)
            .asSequence()
            .filter { it.isValid && it.type == BS.Type.SMB }
            .map { SmbHistoryStep(t = it.timestamp, u = it.amount) }
            .toList()
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
     * The stored [GV] rows hold the raw sensor value. When the recent smoothing or calibration data
     * ([iobCobCalculator]'s `ads.bucketedData`) has an entry within [overlayToleranceMs] that is not a
     * filled gap, its `recalculated` value (smoothed, else calibrated) is used instead. So the watch
     * shows the same number as the main screen of the app.
     *
     * The time of that entry does not always match the raw row exactly: when a CGM does not send
     * readings every 5 minutes on the dot, the smoothed data is rebuilt on a 5 minute grid. So the
     * nearest entry within the tolerance is used, not an exact match. Rows with no entry near them
     * (older history, or no smoothed data at all) keep their raw value.
     *
     * With a CGM that sends more often than every 5 minutes (a 1 minute CGM like Libre 3), several raw
     * rows fall on the same grid entry and would all get the same value. The delta the watch computes
     * from rows next to each other would then be about 0, with the whole change in one jump at the
     * next entry. So rows that share one grid entry are reduced to one row: the one nearest to the
     * time of that entry.
     */
    override fun getGlucoseValues(from: Instant, ascending: Boolean): List<GV> = runBlocking {
        val raw = persistenceLayer.getBgReadingsDataFromTime(from.toEpochMilli(), ascending)
        // Copied under the data lock: the store is rebuilt by another thread while we read it.
        val ads = iobCobCalculator.ads
        val overlay = ads.dataLock.withLock {
            ads.bucketedData.orEmpty().filterNot { it.filledGap }.sortedBy { it.timestamp }
        }
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
            masterActionExecutor.deliverCarbs(
                carbs = carbsAfterConstraints,
                note = null,
                source = Sources.Garmin,
                onError = { aapsLogger.error(LTag.GARMIN, "carbs delivery failed: ${it.comment}") }
            )
        }
    }

    /**
     * Limits the bolus with the bolus constraints (max bolus) and sends it through the shared executor,
     * the same path as a fixed-amount bolus from the phone: it logs the user entry and refuses the bolus
     * when the running mode does not allow one.
     * ⚠️ ASYNC IMPACT: the delivery runs on [appScope]; the limited amount is returned at once.
     */
    override fun postBolus(bolus: Double, onNotDelivered: (String) -> Unit): Double {
        if (!bolus.isFinite() || bolus <= 0.0) return 0.0
        val limited = constraintChecker.applyBolusConstraints(ConstraintObject(bolus, aapsLogger)).value()
        if (limited <= 0.0) {
            aapsLogger.warn(LTag.GARMIN, "bolus of $bolus U refused by the bolus constraints")
            return 0.0
        }
        aapsLogger.info(LTag.GARMIN, "bolus of $bolus U asked, $limited U after the bolus constraints")
        appScope.launch {
            masterActionExecutor.deliverInsulin(
                insulin = limited,
                note = null,
                source = Sources.Garmin,
                onError = {
                    aapsLogger.error(LTag.GARMIN, "bolus delivery failed: ${it.comment}")
                    onNotDelivered(it.comment)
                }
            )
        }
        return limited
    }

    /**
     * ⚠️ ASYNC IMPACT: inserts or cancels the temporary target on [appScope], same as [postCarbs].
     * A target of 0 or a duration of 0 cancels. Otherwise the target is stored in mg/dL via
     * [profileUtil] (same conversion as the reference `postTempTarget`).
     */
    override fun postTempTarget(target: Double, duration: Int) {
        if (target == 0.0 || duration == 0) {
            appScope.launch {
                persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                    timestamp = clock.millis(),
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
                        timestamp = clock.millis(),
                        duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                        reason = TT.Reason.WEAR,
                        lowTarget = profileUtil.convertToMgdl(target, profileUtil.units),
                        highTarget = profileUtil.convertToMgdl(target, profileUtil.units)
                    ),
                    action = Action.TT,
                    source = Sources.Garmin,
                    note = null,
                    listValues = listOf<ValueWithUnit?>(
                        ValueWithUnit.TETTReason(TT.Reason.AUTOMATION),
                        ValueWithUnit.Mgdl(target),
                        ValueWithUnit.Minute(duration)
                    ).filterNotNull()
                )
            }
        }
    }

    /**
     * ⚠️ ASYNC IMPACT: inserts the therapy NOTE on [appScope], same as [postTempTarget].
     * NOTE text is the keyword only (e.g. "lunch"); duration goes on TE.duration so AIMI Therapy
     * windows correctly without embedding minutes in the note string.
     */
    override fun postTherapyMode(keyword: String, durationMin: Int) {
        val normalized = keyword.trim().lowercase()
        if (normalized.isEmpty()) {
            aapsLogger.warn(LTag.GARMIN, "postTherapyMode ignored: empty keyword")
            return
        }
        val safeDurationMin = GarminTherapyMode.storedDurationMin(normalized, durationMin)
        val note = normalized
        val durationMs = TimeUnit.MINUTES.toMillis(safeDurationMin.toLong())
        aapsLogger.info(LTag.GARMIN, "postTherapyMode note='$note' durationMin=$safeDurationMin")
        userEntryLogger.log(
            action = Action.CAREPORTAL,
            source = Sources.Garmin,
            note = note,
        )
        val te = TE(
            timestamp = clock.millis(),
            type = TE.Type.NOTE,
            glucoseUnit = GlucoseUnit.MGDL,
            note = note,
            duration = durationMs,
            enteredBy = "Garmin Widget",
        )
        appScope.launch {
            try {
                persistenceLayer.insertOrUpdateTherapyEvent(te)
                aapsLogger.info(LTag.GARMIN, "Therapy mode stored: $note (${safeDurationMin} min)")
            } catch (error: Exception) {
                aapsLogger.error(LTag.GARMIN, "Failed to store therapy mode: ${error.message}")
            }
        }
    }

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
                val result = persistenceLayer.insertOrUpdateStepsCount(sc)
                val id = result.inserted.firstOrNull()?.id ?: result.updated.firstOrNull()?.id
                aapsLogger.info(LTag.GARMIN, "steps stored: id=$id 5min=$steps5min at ${samplingEnd.toEpochMilli()}")
            } catch (error: Exception) {
                aapsLogger.error(LTag.GARMIN, "failed to store steps", error)
            }
        }
    }
}
