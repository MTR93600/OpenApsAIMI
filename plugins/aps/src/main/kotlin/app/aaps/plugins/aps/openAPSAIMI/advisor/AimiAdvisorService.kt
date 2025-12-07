package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.comparison.KpiCalculator
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * =============================================================================
 * AIMI ADVISOR SERVICE
 * =============================================================================
 * 
 * Injectable service that collects metrics from various sources and generates
 * advisor reports. This is the main entry point for the Advisor UI.
 * =============================================================================
 */
@Singleton
class AimiAdvisorService @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val unifiedReactivityLearner: UnifiedReactivityLearner,
    private val aapsLogger: AAPSLogger
) {
    companion object {
        private const val MS_PER_DAY = 86_400_000L
    }
    
    /**
     * Generate a full advisor report for the specified period.
     * @param periodDays Number of days to analyze (default: 7)
     */
    fun generateReport(periodDays: Int = 7): AdvisorReport {
        aapsLogger.info(LTag.APS, "AimiAdvisorService: Generating report for $periodDays days")
        
        val metrics = collectMetrics(periodDays)
        return AimiAdvisorEngine.analyze(metrics)
    }
    
    /**
     * Collect metrics from AAPS database and learners.
     */
    fun collectMetrics(periodDays: Int = 7): AdvisorMetrics {
        val now = dateUtil.now()
        val start = now - (periodDays * MS_PER_DAY)
        
        // Get BG readings
        val bgReadings = try {
            persistenceLayer.getBgReadingsDataFromTime(start, false)
                .blockingGet()
                .filter { it.value > 39 }
                .map { KpiCalculator.BgReading(it.timestamp, it.value) }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error fetching BG readings", e)
            emptyList()
        }
        
        if (bgReadings.isEmpty()) {
            aapsLogger.warn(LTag.APS, "AimiAdvisorService: No BG readings found")
            return createEmptyMetrics(periodDays)
        }
        
        // Calculate glycemic metrics
        val values = bgReadings.map { it.valueMgdl }
        val count = values.size.toDouble()
        
        fun percentIn(range: ClosedFloatingPointRange<Double>): Double =
            values.count { it in range } / count
        
        val tir70_180 = percentIn(70.0..180.0)
        val tir70_140 = percentIn(70.0..140.0)
        val timeBelow70 = percentIn(0.0..69.99)
        val timeBelow54 = percentIn(0.0..53.99)
        val timeAbove180 = percentIn(180.01..Double.MAX_VALUE)
        val timeAbove250 = percentIn(250.01..Double.MAX_VALUE)
        val meanBg = values.average()
        
        // CV calculation
        val variance = values.map { (it - meanBg).pow(2) }.average()
        val stdDev = sqrt(variance)
        val bgCv = if (meanBg > 0) (stdDev / meanBg) * 100 else 0.0
        
        // Safety events (hypo/hyper episodes)
        val (hypoEvents, severeHypos) = countHypoEvents(bgReadings)
        val hyperEvents = countHyperEvents(bgReadings)
        
        // Get insulin data (simplified - would need treatment DB access for full data)
        val tdd = estimateTdd(periodDays)
        val basalPercent = 0.50  // Default estimate if no detailed data
        val smbPercent = 0.20
        
        return AdvisorMetrics(
            periodLabel = "$periodDays derniers jours",
            periodDays = periodDays,
            tir70_180 = tir70_180,
            tir70_140 = tir70_140,
            timeBelow70 = timeBelow70,
            timeBelow54 = timeBelow54,
            timeAbove180 = timeAbove180,
            timeAbove250 = timeAbove250,
            meanBg = meanBg,
            bgCv = bgCv,
            tdd = tdd,
            basalPercent = basalPercent,
            smbPercent = smbPercent,
            avgBasalRate = tdd * basalPercent / 24.0,
            hypoEvents = hypoEvents,
            severeHypoEvents = severeHypos,
            hyperEvents = hyperEvents,
            avgActivityScore = null,  // TODO: integrate ActivityManager data
            activityDaysDetected = 0
        )
    }
    
    /**
     * Count distinct hypo events (not individual readings).
     */
    private fun countHypoEvents(readings: List<KpiCalculator.BgReading>): Pair<Int, Int> {
        var hypoEvents = 0
        var severeEvents = 0
        var inHypo = false
        var inSevere = false
        
        val sorted = readings.sortedBy { it.timestamp }
        for (r in sorted) {
            if (r.valueMgdl < 70 && !inHypo) {
                hypoEvents++
                inHypo = true
            } else if (r.valueMgdl >= 70) {
                inHypo = false
            }
            
            if (r.valueMgdl < 54 && !inSevere) {
                severeEvents++
                inSevere = true
            } else if (r.valueMgdl >= 54) {
                inSevere = false
            }
        }
        
        return hypoEvents to severeEvents
    }
    
    /**
     * Count distinct hyper events (>250 for >30 min).
     */
    private fun countHyperEvents(readings: List<KpiCalculator.BgReading>): Int {
        var hyperEvents = 0
        var inHyper = false
        var hyperStart = 0L
        
        val sorted = readings.sortedBy { it.timestamp }
        for (r in sorted) {
            if (r.valueMgdl > 250) {
                if (!inHyper) {
                    hyperStart = r.timestamp
                    inHyper = true
                } else if (r.timestamp - hyperStart >= 30 * 60 * 1000) {
                    hyperEvents++
                    hyperStart = r.timestamp  // Reset to avoid double-counting
                }
            } else {
                inHyper = false
            }
        }
        
        return hyperEvents
    }
    
    /**
     * Estimate TDD (simplified - would need treatment DB for accurate calculation).
     */
    private fun estimateTdd(periodDays: Int): Double {
        // This is a placeholder. In full implementation, query treatments DB.
        // Using reactivity learner data as proxy if available.
        return 35.0  // Default estimate
    }
    
    private fun createEmptyMetrics(periodDays: Int): AdvisorMetrics {
        return AdvisorMetrics(
            periodLabel = "$periodDays derniers jours (Pas de données)",
            periodDays = periodDays,
            tir70_180 = 0.0,
            tir70_140 = 0.0,
            timeBelow70 = 0.0,
            timeBelow54 = 0.0,
            timeAbove180 = 0.0,
            timeAbove250 = 0.0,
            meanBg = 0.0,
            bgCv = 0.0,
            tdd = 0.0,
            basalPercent = 0.0,
            smbPercent = 0.0,
            avgBasalRate = 0.0,
            hypoEvents = 0,
            severeHypoEvents = 0,
            hyperEvents = 0
        )
    }
}
