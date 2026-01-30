package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

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
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "HealthContextRepo"
    }

    // In-memory cache of the last valid snapshot
    private var lastSnapshot: HealthContextSnapshot = HealthContextSnapshot.EMPTY
    
    /**
     * Fetches and builds the current Health Snapshot.
     * Merges HC data with Watch data and calculates derived metrics.
     */
    fun fetchSnapshot(): HealthContextSnapshot {
        // 1. Fetch Basic Data (HC)
        // We use fetchSleepData because we need sleep info for context
        // We use step/HR fetchers separately if needed, but for now relying on existing repo structures
        val sleepData = hcRepo.fetchSleepData()
        val hrvList = hcRepo.fetchHRVData(1) // Last 24h only for snapshot
        val stepsToday = hcRepo.fetchStepsData(0) // Today
        val currentHR = hcRepo.fetchLastHeartRate()
        val rhrList = hcRepo.fetchMorningRHR(7)
        
        // 2. Aggregate/Calculate Derived Metrics
        val steps15 = aggregator.getStepsLast(15)
        val steps60 = aggregator.getStepsLast(60)
        val hrAvg15 = aggregator.getHrAverage(15)

        // Use FeatureExtractor logic to get normalized HRV (Nocturnal priority)
        // We re-use specific extraction logic but targeted for the snapshot
        // We can't reuse extractor directly returning PhysioFeatures because Snapshot structure is different
        // So we do a mini-extraction here or refactor Extractor later. 
        // For efficiency, let's keep it simple:
        
        // HRV: Filter for most recent valid or finding nocturnal
        val hrv = if (hrvList.isNotEmpty()) {
             // Try to find nocturnal if sleep data exists
             if (sleepData != null && sleepData.hasValidData()) {
                 hrvList.filter { it.timestamp >= sleepData.startTime && it.timestamp <= sleepData.endTime }
                        .map { it.rmssd }.average().takeIf { !it.isNaN() } 
                        ?: hrvList.lastOrNull()?.rmssd ?: 0.0
             } else {
                 hrvList.lastOrNull()?.rmssd ?: 0.0
             }
        } else 0.0

        val rhr = if (rhrList.isNotEmpty()) {
            rhrList.minByOrNull { it.bpm }?.bpm ?: 60
        } else 60

        // Sleep Debt (Simple calc: Baseline 7.5h - Actual)
        val sleepDebt = if (sleepData != null && sleepData.hasValidData()) {
            ((7.5 - sleepData.durationHours) * 60).toInt().coerceAtLeast(0)
        } else 0

        // Confidence calculation
        var confidence = 0.0
        if (hrv > 0) confidence += 0.4
        if (currentHR > 0) confidence += 0.3
        if (sleepData != null) confidence += 0.3

        val snapshot = HealthContextSnapshot(
            stepsLast15m = steps15,
            stepsLast60m = steps60,
            activityState = if (steps15 > 500) "ACTIVE" else "IDLE", // Dynamic check
            hrNow = currentHR,
            hrAvg15m = hrAvg15,
            hrvRmssd = hrv,
            rhrResting = rhr,
            sleepDebtMinutes = sleepDebt,
            sleepEfficiency = sleepData?.efficiency ?: 0.0,
            timestamp = System.currentTimeMillis(),
            confidence = confidence.coerceIn(0.0, 1.0),
            source = "HealthConnect+Repo",
            isValid = confidence > 0.3
        )
        
        lastSnapshot = snapshot
        return snapshot
    }

    // Pass-through for legacy or specific access if needed
    fun getLastSnapshot(): HealthContextSnapshot = lastSnapshot
    
    // For Workers: Access underlying HC Repo
    fun getHcRepo(): AIMIPhysioDataRepositoryMTR = hcRepo
    
    // For Daily Worker: Force heavy refresh
    fun forceHeavyRefresh() {
        hcRepo.fetchSleepData()
        hcRepo.fetchMorningRHR(7)
        hcRepo.fetchHRVData(7)
        fetchSnapshot()
    }
}
