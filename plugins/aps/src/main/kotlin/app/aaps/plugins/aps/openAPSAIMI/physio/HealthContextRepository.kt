package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.steps.StepsResult
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
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
    private val unifiedProvider: app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR, // 🚀 NEW INJECTION
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
        val sleepData = hcRepo.fetchSleepData()
        val hrvList = hcRepo.fetchHRVData(1) // Last 24h
        val rhrList = hcRepo.fetchMorningRHR(7)
        
        // 2. Fetch Real-Time Data (Unified Provider: Watch > Phone > HC)
        val steps15Result = unifiedProvider.getStepsTotalSince(System.currentTimeMillis() - 15 * 60 * 1000)
        val steps60Result = unifiedProvider.getStepsTotalSince(System.currentTimeMillis() - 60 * 60 * 1000)
        val hrResult = unifiedProvider.getLatestHeartRate(15 * 60 * 1000)

        // Extract values or defaults
        val steps15: Int = steps15Result?.steps ?: 0
        val steps60: Int = steps60Result?.steps ?: 0
        val currentHR: Int = (hrResult?.bpm ?: 0.0).toInt()

        // Use FeatureExtractor logic to get normalized HRV (Nocturnal priority)
        val hrv = if (hrvList.isNotEmpty()) {
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
            activityState = if (steps15 > 200) "ACTIVE" else "IDLE", // Lowered threshold for sensitivity
            hrNow = currentHR,
            hrAvg15m = currentHR, // Approximation if simple point
            hrvRmssd = hrv,
            rhrResting = rhr,
            sleepDebtMinutes = sleepDebt,
            sleepEfficiency = sleepData?.efficiency ?: 0.0,
            timestamp = System.currentTimeMillis(),
            confidence = confidence.coerceIn(0.0, 1.0),
            source = "Merged(Unified+HC)",
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
