package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRefresher
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalDataWindowMTR
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 🏥 Health Context Repository
 * 
 * Responsible for merging physiological data from multiple sources:
 * 1. Health Connect (Primary, Historical)
 * 2. Real-time Watch Service (Fallback/Augmentation)
 * 3. Aggregators (Sliding windows)
 * 
 * Provides the `HealthContextSnapshot` to the rest of the app.
 */
@Singleton
class HealthContextRepository @Inject constructor(
    private val context: Context,
    private val hcRepo: AIMIPhysioDataRepositoryMTR,
    private val featureExtractor: AIMIPhysioFeatureExtractorMTR,
    private val aggregator: PhysioAggregator,
    private val unifiedProvider: app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR, // 🚀 NEW INJECTION
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger
) {

    companion object {
        private const val TAG = "HealthContextRepo"
        /** Re-use steps/HR snapshot for Autodrive gating when loop ticks are frequent. */
        private const val AUTODRIVE_GATER_SNAPSHOT_MAX_AGE_MS = 90_000L
        /**
         * How often the awake resting heart rate is measured again.
         *
         * It is a centile over seven days, so it moves by about one beat from one day to the next.
         * Once an hour is far more often than it can change, and it keeps the seven-day read off the
         * loop's own cadence. See [AwakeRestingHeartRate].
         */
        private const val AWAKE_RESTING_REFRESH_MS = 60 * 60 * 1000L
        /**
         * How old a step record may be and still describe the moment of a heart-rate reading.
         *
         * `SC.steps15min` at time T counts the steps of `[T-15 min, T]`, so a record 14 minutes older
         * than the reading describes a window that does not contain it. Five minutes keeps the
         * overlap real. Being too generous here is the safe direction anyway — it keeps samples that
         * should be dropped, which can only lower the baseline and make the gesture fire more often —
         * but "the steps of that moment" should mean what it says.
         */
        private const val STEPS_MATCH_WINDOW_MS = 5 * 60 * 1000L
    }

    // In-memory cache of the last valid snapshot
    private var lastSnapshot: HealthContextSnapshot = HealthContextSnapshot.EMPTY
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sleepRef = AtomicReference<Any?>(null)
    private val hrvRef = AtomicReference<List<Any>>(emptyList())
    private val rhrRef = AtomicReference<List<Any>>(emptyList())
    private val thermalRef = AtomicReference<ThermalDataWindowMTR?>(null)
    private val refreshInFlight = AtomicBoolean(false)
    private val awakeRestingRef = AtomicReference<Int?>(null)
    private val awakeRestingMeasuredAtMs = AtomicReference(0L)
    private val awakeRestingInFlight = AtomicBoolean(false)
    
    /**
     * Fetches and builds the current Health Snapshot.
     * Merges HC data with Watch data and calculates derived metrics.
     */
    fun fetchSnapshot(): HealthContextSnapshot = fetchSnapshotInternal()

    /**
     * Throttled snapshot for Autodrive V3 gater — avoids four DB reads every 5 min when unchanged.
     */
    fun fetchSnapshotForAutodriveGater(): HealthContextSnapshot {
        val ageMs = System.currentTimeMillis() - lastSnapshot.timestamp
        if (lastSnapshot.isValid && ageMs in 0..AUTODRIVE_GATER_SNAPSHOT_MAX_AGE_MS) {
            return lastSnapshot
        }
        return fetchSnapshotInternal()
    }

    private fun fetchSnapshotInternal(): HealthContextSnapshot {
        // 1. Fetch Basic Data (HC)
        refreshCoreDataAsync()
        @Suppress("UNCHECKED_CAST")
        val sleepData = sleepRef.get() as? SleepDataMTR
        @Suppress("UNCHECKED_CAST")
        val hrvList = hrvRef.get() as List<HRVDataMTR> // Last 24h
        @Suppress("UNCHECKED_CAST")
        val rhrList = rhrRef.get() as List<RHRDataMTR>

        // HC core (sleep/HRV/RHR) loads async; do not skip Unified steps/HR while waiting.
        // Reuse last valid HC-derived fields only so confidence/merge stay stable until refs fill.
        val warmupHcPending =
            sleepData == null && hrvList.isEmpty() && rhrList.isEmpty() && lastSnapshot.isValid

        // 2. Fetch Real-Time Data (Unified Provider: Watch > Phone > HC)
        val steps5Result = unifiedProvider.getStepsTotalSince(System.currentTimeMillis() - 5 * 60 * 1000)
        val steps15Result = unifiedProvider.getStepsTotalSince(System.currentTimeMillis() - 15 * 60 * 1000)
        val steps60Result = unifiedProvider.getStepsTotalSince(System.currentTimeMillis() - 60 * 60 * 1000)
        val hrResult = unifiedProvider.getLatestHeartRate(15 * 60 * 1000)

        // Extract values or defaults
        val steps5: Int = steps5Result?.steps ?: 0
        val steps15: Int = steps15Result?.steps ?: 0
        val steps60: Int = steps60Result?.steps ?: 0
        val currentHR: Int = (hrResult?.bpm ?: 0.0).toInt()

        // Use FeatureExtractor logic to get normalized HRV (Nocturnal priority)
        val hrv = when {
            hrvList.isNotEmpty() -> {
                if (sleepData != null && sleepData.hasValidData()) {
                    hrvList.filter { it.timestamp >= sleepData.startTime && it.timestamp <= sleepData.endTime }
                        .map { it.rmssd }.average().takeIf { !it.isNaN() }
                        ?: hrvList.lastOrNull()?.rmssd ?: 0.0
                } else {
                    hrvList.lastOrNull()?.rmssd ?: 0.0
                }
            }
            warmupHcPending -> lastSnapshot.hrvRmssd
            else -> 0.0
        }

        val rhr = when {
            rhrList.isNotEmpty() -> rhrList.minByOrNull { it.bpm }?.bpm ?: 60
            warmupHcPending -> lastSnapshot.rhrResting
            else -> 60
        }

        // Sleep Debt (Simple calc: Baseline 7.5h - Actual)
        val sleepDebt = when {
            sleepData != null && sleepData.hasValidData() ->
                ((7.5 - sleepData.durationHours) * 60).toInt().coerceAtLeast(0)
            warmupHcPending -> lastSnapshot.sleepDebtMinutes
            else -> 0
        }

        val sleepEfficiency = when {
            sleepData != null -> sleepData.efficiency
            warmupHcPending -> lastSnapshot.sleepEfficiency
            else -> 0.0
        }

        // Confidence calculation
        var confidence = 0.0
        if (hrv > 0) confidence += 0.4
        if (currentHR > 0) confidence += 0.3
        if (sleepData != null) {
            confidence += 0.3
        } else if (warmupHcPending && lastSnapshot.sleepDebtMinutes > 0) {
            confidence += 0.3
        }

        val thermalWindow = thermalRef.get() ?: ThermalDataWindowMTR()
        val thermalBelief = ThermalBeliefEngine.build(
            window = thermalWindow,
            hrNowBpm = currentHR,
            rhrRestingBpm = rhr,
            sleepDebtMinutes = sleepDebt,
            hrvRmssd = hrv,
            wCyclePhase = null,
        )
        if (thermalBelief.hasUsableData()) {
            confidence = (confidence + 0.15).coerceAtMost(1.0)
        }

        val hcSleepActive = sleepData?.isOngoingAt(System.currentTimeMillis()) == true
        val clockIsNight = clockIsNightHour(System.currentTimeMillis())
        val sleepLive = SleepLiveDetector.evaluate(
            SleepLiveDetector.Input(
                stepsLast15m = steps15,
                stepsLast5m = steps5,
                hrNowBpm = currentHR,
                rhrRestingBpm = rhr,
                hcSessionActive = hcSleepActive,
                clockIsNight = clockIsNight,
            ),
        )
        // Efficient movement detection: >200 steps in the last 5 min already means "on the move" (a burst that
        // the old 1000/15m threshold missed), or a sustained ~25 steps/min over 15 min. Aligned with
        // EffortActivityBelief's ACTIVE reference so the displayed state matches the effort branch that protects.
        val activityState = when {
            sleepLive.isAsleep -> "SLEEPING"
            steps5 >= 200 || steps15 >= 375 -> "ACTIVE"
            else -> "IDLE"
        }

        val snapshot = HealthContextSnapshot(
            stepsLast5m = steps5,
            stepsLast15m = steps15,
            stepsLast60m = steps60,
            activityState = activityState,
            hrNow = currentHR,
            hrAvg15m = currentHR, // Approximation if simple point
            hrMeasuredAtMs = if (currentHR > 0) System.currentTimeMillis() else 0L,
            hrvRmssd = hrv,
            rhrResting = rhr,
            sleepDebtMinutes = sleepDebt,
            sleepEfficiency = sleepEfficiency,
            hcSleepSessionActive = hcSleepActive,
            asleepLiveConfidence = sleepLive.confidence,
            asleepLiveSource = sleepLive.source.name,
            thermalBelief = thermalBelief,
            timestamp = System.currentTimeMillis(),
            confidence = confidence.coerceIn(0.0, 1.0),
            source = "Merged(Unified+HC)",
            isValid = confidence > 0.3
        )
        // Do not freeze steps/FC when Unified refreshed but HC-only confidence is low (e.g. no HRV/sleep yet).
        if (!snapshot.isValid && lastSnapshot.isValid) {
            val carriedHeartRate = HeartRateCarryForward.resolve(
                freshHrNow = snapshot.hrNow,
                freshHrAvg15m = snapshot.hrAvg15m,
                nowMs = snapshot.timestamp,
                previousHrNow = lastSnapshot.hrNow,
                previousHrAvg15m = lastSnapshot.hrAvg15m,
                previousMeasuredAtMs = lastSnapshot.hrMeasuredAtMs,
            )
            val merged = lastSnapshot.copy(
                stepsLast5m = snapshot.stepsLast5m,
                stepsLast15m = snapshot.stepsLast15m,
                stepsLast60m = snapshot.stepsLast60m,
                activityState = snapshot.activityState,
                hcSleepSessionActive = snapshot.hcSleepSessionActive,
                asleepLiveConfidence = snapshot.asleepLiveConfidence,
                asleepLiveSource = snapshot.asleepLiveSource,
                // The heart rate keeps its OWN age — see [HeartRateCarryForward]. Before this, a
                // carried reading was stamped with the new snapshot's timestamp and stored as the
                // new previous one, so the re-dating compounded and an hours-old value was presented
                // as current for ever. Past the provider's own 15-minute lookback the reading is
                // dropped to 0, which every consumer already reads as missing.
                hrNow = carriedHeartRate.hrNow,
                hrAvg15m = carriedHeartRate.hrAvg15m,
                hrMeasuredAtMs = carriedHeartRate.measuredAtMs,
                timestamp = snapshot.timestamp,
                source = snapshot.source,
            )
            lastSnapshot = merged
            maybeRefreshPatientStateFromPhysio(merged)
            return merged
        }

        lastSnapshot = snapshot
        maybeRefreshPatientStateFromPhysio(snapshot)
        return snapshot
    }

    private fun maybeRefreshPatientStateFromPhysio(snapshot: HealthContextSnapshot) {
        val previous = PatientStateRuntimeRepository.getLatest()?.physioLive
        val stepsChanged = previous == null || previous.stepsLast15m != snapshot.stepsLast15m ||
            previous.stepsLast60m != snapshot.stepsLast60m
        val hrChanged = previous == null || previous.hrNowBpm != snapshot.hrNow ||
            previous.hrAvg15mBpm != snapshot.hrAvg15m
        val activityChanged = previous == null || previous.activityState != snapshot.activityState
        val thermalChanged = previous == null ||
            previous.thermalHypothesis != snapshot.thermalBelief.hypothesis.name ||
            kotlin.math.abs(previous.thermalDeltaVsBaselineC - snapshot.thermalBelief.deltaVsBaselineC) >= 0.05
        if (!stepsChanged && !hrChanged && !activityChanged && !thermalChanged) {
            return
        }
        PatientStateRuntimeRefresher.refreshFromHealthSnapshot(
            healthSnapshot = snapshot,
            nowMs = System.currentTimeMillis(),
        )
    }

    // Pass-through for legacy or specific access if needed
    fun getLastSnapshot(): HealthContextSnapshot = lastSnapshot
    
    // For Workers: Access underlying HC Repo
    fun getHcRepo(): AIMIPhysioDataRepositoryMTR = hcRepo
    
    // For Daily Worker: Force heavy refresh
    fun forceHeavyRefresh() {
        refreshCoreDataAsync(daysHrv = 7, force = true)
        fetchSnapshot()
    }

    /**
     * The awake resting heart rate of the last [AwakeRestingHeartRate.WINDOW_DAYS] days, or null.
     *
     * Null means "not measured", and every caller must read it as "stand down", never as a number to
     * replace. [HealthContextSnapshot.rhrResting] keeps its own meaning — the lowest morning value —
     * for the readers that already use it; this is a second, awake quantity and only the stress ISF
     * floor reads it today.
     *
     * The value is measured on a background scope and served from memory, so this call never touches
     * the database. It is null until the first measurement lands, which is the safe direction: the
     * gesture that reads it can only withhold insulin.
     *
     * A measurement that **fails** keeps the last value for one more cycle — a database error must not
     * end a protection. A measurement that **succeeds with too little data** does return null: that is
     * an honest answer, not an error.
     */
    fun awakeRestingHeartRateBpm(): Int? {
        refreshAwakeRestingHeartRateAsync()
        return awakeRestingRef.get()
    }

    private fun refreshAwakeRestingHeartRateAsync() {
        val now = System.currentTimeMillis()
        val measuredAt = awakeRestingMeasuredAtMs.get()
        if (measuredAt != 0L && now - measuredAt in 0..AWAKE_RESTING_REFRESH_MS) return
        if (!awakeRestingInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val windowStart = now - AwakeRestingHeartRate.WINDOW_DAYS * 24 * 60 * 60 * 1000L
                val heartRates = persistenceLayer.getHeartRatesFromTimeToTime(windowStart, now)
                val steps = persistenceLayer.getStepsCountFromTimeToTime(windowStart, now)
                val samples = buildAwakeSamples(heartRates, steps)
                val estimate = AwakeRestingHeartRate.estimate(samples, ZoneId.systemDefault())
                awakeRestingRef.set(estimate)
                awakeRestingMeasuredAtMs.set(System.currentTimeMillis())
                aapsLogger.debug(
                    LTag.APS,
                    "[$TAG] awake resting HR: ${estimate ?: "stand down"} from ${samples.size} samples",
                )
            } catch (e: Exception) {
                // The last good baseline is KEPT for one more cycle. Nulling it here would drop an
                // active stress floor at once and with no grace, because a missing baseline stands the
                // gesture down — so a database hiccup would end a protection. One hour of a baseline
                // that moves by about one beat a day is a far smaller error than that.
                aapsLogger.warn(LTag.APS, "[$TAG] awake resting HR measurement failed, keeping the last value", e)
                awakeRestingMeasuredAtMs.set(System.currentTimeMillis())
            } finally {
                awakeRestingInFlight.set(false)
            }
        }
    }

    /**
     * Pairs each heart-rate reading with the step count of that moment, when one is known.
     *
     * Both lists come from the same database the loop reads its live heart rate from, so the baseline
     * is measured on exactly the signal it will be compared against.
     */
    private fun buildAwakeSamples(heartRates: List<HR>, steps: List<SC>): List<AwakeRestingHeartRate.Sample> {
        val stepsByTime = steps.sortedBy { it.timestamp }
        val heartRatesByTime = heartRates.sortedBy { it.timestamp }
        var stepsIndex = 0
        return heartRatesByTime.map { hr ->
            // Both lists are sorted, so one walk over each is enough: advance to the last step record
            // that is not newer than this reading, then keep it only if it is recent enough.
            while (stepsIndex + 1 < stepsByTime.size && stepsByTime[stepsIndex + 1].timestamp <= hr.timestamp) {
                stepsIndex++
            }
            val candidate = stepsByTime.getOrNull(stepsIndex)
                ?.takeIf { it.timestamp <= hr.timestamp && hr.timestamp - it.timestamp <= STEPS_MATCH_WINDOW_MS }
            AwakeRestingHeartRate.Sample(
                timestampMs = hr.timestamp,
                bpm = hr.beatsPerMinute,
                stepsLast15m = candidate?.steps15min,
            )
        }
    }

    private fun clockIsNightHour(nowMs: Long): Boolean {
        val hour = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault()).hour
        return hour >= 23 || hour < 6
    }

    private fun refreshCoreDataAsync(daysHrv: Int = 1, force: Boolean = false) {
        if (!force && !refreshInFlight.compareAndSet(false, true)) return
        if (force) refreshInFlight.set(true)
        ioScope.launch {
            try {
                sleepRef.set(hcRepo.fetchSleepData())
                hrvRef.set(hcRepo.fetchHRVData(daysHrv))
                rhrRef.set(hcRepo.fetchMorningRHR(7))
                thermalRef.set(hcRepo.fetchThermalWindow(daysBack = 3))
            } catch (_: Exception) {
                sleepRef.set(null)
                hrvRef.set(emptyList())
                rhrRef.set(emptyList())
                thermalRef.set(null)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }
}
