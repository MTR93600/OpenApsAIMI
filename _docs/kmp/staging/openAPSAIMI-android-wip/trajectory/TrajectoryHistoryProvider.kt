package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinWeibullCurve
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trajectory History Provider
 * 
 * Collects and maintains phase-space history for trajectory analysis.
 * Bridges AIMI's existing data sources (BG history, IOB, PKPD) into
 * PhaseSpaceState sequences.
 */
@SingleIn(AppScope::class)
class TrajectoryHistoryProvider @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        const val DEFAULT_HISTORY_MINUTES = 90  // 90 minutes of history
        const val MIN_HISTORY_MINUTES = 30      // Minimum for meaningful analysis
    }
    
    /**
     * Build phase-space history from current AIMI state
     * 
     * @param nowMillis Current timestamp
     * @param historyMinutes How far back to look (default: 90 min)
     * @param currentBg Current BG reading
     * @param currentDelta Current delta (mg/dL/5min)
     * @param currentAccel Current acceleration (mg/dL/5min²)
     * @param insulinActivityNow Current insulin activity (U/hr)
     * @param iobNow Current IOB
     * @param pkpdStage Current PKPD stage
     * @param timeSinceLastBolus Minutes since last bolus
     * @param cobNow Current COB
     * @param effectiveProfile When non-null, historical samples use [IobCobCalculator.calculateFromTreatmentsAndTemps]
     * at each BG timestamp (TAP-G / trajectory fidelity); when null, IOB at past samples falls back to current bolus IOB.
     * @param historicalInsulinPeakMinutes When non-null together with [effectiveProfile], past-sample **stage** and
     * activity fallback use [InsulinWeibullCurve] (same kernel as [app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionProfiler]) (RFC C.2).
     * @return List of PhaseSpaceState (oldest first)
     */
    suspend fun buildHistory(
        nowMillis: Long,
        historyMinutes: Int = DEFAULT_HISTORY_MINUTES,
        currentBg: Double,
        currentDelta: Double,
        currentAccel: Double,
        insulinActivityNow: Double,
        iobNow: Double,
        pkpdStage: ActivityStage,
        timeSinceLastBolus: Int,
        cobNow: Double = 0.0,
        effectiveProfile: EffectiveProfile? = null,
        historicalInsulinPeakMinutes: Int? = null,
    ): List<PhaseSpaceState> = withContext(Dispatchers.Default) {

        val history = mutableListOf<PhaseSpaceState>()
        val fromMillis = nowMillis - (historyMinutes * 60_000L)

        try {
            // Get BG history from iobCobCalculator (bucketed data)
            val bgReadings = iobCobCalculator.ads.getBucketedDataTableCopy() ?: emptyList()

            val filteredBgReadings = bgReadings
                .filter { it.timestamp >= fromMillis }
                .sortedBy { it.timestamp }

            if (filteredBgReadings.isEmpty()) {
                aapsLogger.warn(LTag.APS, "TrajectoryHistory: No BG readings found in last $historyMinutes min")
                return@withContext listOf(
                    createCurrentState(
                        nowMillis, currentBg, currentDelta, currentAccel,
                        insulinActivityNow, iobNow, pkpdStage, timeSinceLastBolus, cobNow
                    )
                )
            }
            
            // Sample at 5-minute intervals
            var lastSampledTime = fromMillis
            val sampleInterval = 5 * 60_000L // 5 minutes
            
            for (bg in filteredBgReadings) {
                if (bg.timestamp >= lastSampledTime + sampleInterval || 
                    bg.timestamp >= nowMillis - (5 * 60_000L)) { // Always include last 5 min
                    
                    // Calculate delta and accel at this point (shared kernel: RFC C.3 tests)
                    val delta = TrajectoryBgDerivatives.deltaAt(bg.timestamp, filteredBgReadings)
                    val accel = TrajectoryBgDerivatives.accelAt(bg.timestamp, filteredBgReadings)
                    
                    // Get IOB at this time
                    val iobTotalObj = if (effectiveProfile != null) {
                        try {
                            iobCobCalculator.calculateFromTreatmentsAndTemps(bg.timestamp, effectiveProfile)
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.APS, "Error getting IOB at t=${bg.timestamp}: ${e.message}")
                            null
                        }
                    } else {
                        try {
                            iobCobCalculator.calculateIobFromBolus()
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.APS, "Error getting IOB for history: ${e.message}")
                            null
                        }
                    }
                    
                    val iob = iobTotalObj?.iob ?: 0.0
                    val coreActivity = iobTotalObj?.activity ?: 0.0
                    val minsSinceBolus = estimateTimeSinceLastBolus(bg.timestamp)
                    val peakHistMin = historicalInsulinPeakMinutes
                    val useKernelHistory =
                        peakHistMin != null &&
                            effectiveProfile != null &&
                            iob > 0.01

                    val activity = when {
                        iobTotalObj != null && coreActivity > 1e-6 -> coreActivity
                        useKernelHistory && peakHistMin != null -> {
                            val norm = InsulinWeibullCurve.activityNormalized(
                                minsSinceBolus.toDouble(),
                                peakHistMin.toDouble(),
                            )
                            (iob * norm * 2.5).coerceIn(0.0, 5.0)
                        }
                        else -> estimateInsulinActivity(iob, delta)
                    }

                    val stage = when {
                        useKernelHistory && peakHistMin != null ->
                            InsulinWeibullCurve.activityStage(
                                minsSinceBolus.toDouble(),
                                peakHistMin.toDouble(),
                                iob,
                            )
                        iobTotalObj != null && coreActivity > 0.0 -> {
                            when {
                                iob < 0.1 -> ActivityStage.TAIL
                                coreActivity > 0.015 -> ActivityStage.PEAK
                                else -> ActivityStage.TAIL
                            }
                        }
                        else -> estimatePkpdStage(iob, delta)
                    }

                    val timeSinceBolus = minsSinceBolus
                    
                    // Get COB at this time
                    val cob = try {
                        iobCobCalculator.getCobInfo("TrajectoryHistory").displayCob ?: 0.0
                    } catch (e: Exception) {
                        0.0
                    }
                    
                    history.add(PhaseSpaceState(
                        timestamp = bg.timestamp,
                        bg = bg.recalculated,
                        bgDelta = delta,
                        bgAccel = accel,
                        insulinActivity = activity,
                        iob = iob,
                        pkpdStage = stage,
                        timeSinceLastBolus = timeSinceBolus,
                        cob = cob
                    ))
                    
                    lastSampledTime = bg.timestamp
                }
            }
            
            // Always add current state as last point
            history.add(createCurrentState(
                nowMillis, currentBg, currentDelta, currentAccel,
                insulinActivityNow, iobNow, pkpdStage, timeSinceLastBolus, cobNow
            ))
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error building trajectory history: ${e.message}")
            return@withContext listOf(
                createCurrentState(
                    nowMillis, currentBg, currentDelta, currentAccel,
                    insulinActivityNow, iobNow, pkpdStage, timeSinceLastBolus, cobNow
                )
            )
        }

        aapsLogger.debug(LTag.APS, "TrajectoryHistory: Built ${history.size} states over $historyMinutes min")
        history
    }
    
    /**
     * Create current phase-space state
     */
    private fun createCurrentState(
        timestamp: Long,
        bg: Double,
        delta: Double,
        accel: Double,
        activity: Double,
        iob: Double,
        stage: ActivityStage,
        timeSinceBolus: Int,
        cob: Double
    ): PhaseSpaceState = PhaseSpaceState(
        timestamp = timestamp,
        bg = bg,
        bgDelta = delta,
        bgAccel = accel,
        insulinActivity = activity,
        iob = iob,
        pkpdStage = stage,
        timeSinceLastBolus = timeSinceBolus,
        cob = cob
    )
    
    /**
     * Rough estimate of insulin activity from IOB and BG trend
     * 
     * TODO: Use actual PKPD model for precision
     */
    private fun estimateInsulinActivity(iob: Double, delta: Double): Double {
        // Rough heuristic: if IOB present and BG falling, activity is high
        // More precise would use bolus timing and PKPD curves
        
        if (iob < 0.1) return 0.0
        
        // Assume average DIA ~6h, peak at ~60 min
        // Rough activity ~ IOB * responsiveness
        val baseActivity = iob / 3.0 // Spread over 3 hours (rough half-DIA)
        
        // If BG falling, insulin is likely active
        val responsiveness = when {
            delta < -5.0 -> 1.5  // Strong response
            delta < -2.0 -> 1.2  // Moderate response
            delta < 0.0 -> 1.0   // Mild response
            else -> 0.7          // No response yet (PRE_ONSET?)
        }
        
        return (baseActivity * responsiveness).coerceIn(0.0, 5.0)
    }
    
    /**
     * Rough estimate of PKPD stage
     * 
     * TODO: Use actual bolus timing and PKPD model
     */
    private fun estimatePkpdStage(iob: Double, delta: Double): ActivityStage {
        return when {
            iob < 0.1 -> ActivityStage.TAIL
            delta > 0 -> ActivityStage.RISING
            delta < -5 -> ActivityStage.PEAK
            delta < -2 -> ActivityStage.FALLING
            else -> ActivityStage.TAIL
        }
    }
    
    /**
     * Rough estimate of time since last bolus
     * 
     * TODO: Use actual bolus history
     */
    private suspend fun estimateTimeSinceLastBolus(timestamp: Long): Int {
        try {
            val boluses = persistenceLayer.getBolusesFromTime(
                timestamp - (4 * 3600_000L),
                ascending = false
            )

            val lastBolus = boluses
                .filter { it.timestamp <= timestamp && it.amount > 0.1 }
                .maxByOrNull { it.timestamp }
            
            if (lastBolus != null) {
                val diffMin = ((timestamp - lastBolus.timestamp) / 60_000.0).toInt()
                return max(0, diffMin)
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.APS, "Could not estimate time since last bolus: ${e.message}")
        }
        
        return 120 // Default: assume 2 hours ago
    }
}
