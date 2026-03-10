package app.aaps.plugins.aps.openAPSAIMI.comparison

import kotlin.math.sqrt

/**
 * =============================================================================
 * KPI CALCULATOR
 * =============================================================================
 * 
 * Calculates Key Performance Indicators (KPIs) from comparison decisions
 * and BG readings over a specified period.
 * 
 * Usage:
 *   val kpi = KpiCalculator.calculate(
 *       algo = AlgorithmType.AIMI,
 *       decisions = listOf(...),
 *       bgReadings = listOf(...),
 *       periodStart = startTimestamp,
 *       periodEnd = endTimestamp
 *   )
 * =============================================================================
 */
object KpiCalculator {
    
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_HOUR = 3_600_000L
    private const val MS_PER_DAY = 86_400_000L
    private const val DEFAULT_TICK_MINUTES = 5.0
    
    /**
     * Represents a BG reading for KPI calculation.
     */
    data class BgReading(
        val timestamp: Long,
        val valueMgdl: Double
    )
    
    /**
     * Calculate full KPIs for an algorithm over a period.
     * 
     * @param algo The algorithm type (AIMI or OPENAPS_SMB)
     * @param decisions List of decisions made by this algorithm
     * @param bgReadings List of actual BG readings during the period
     * @param periodStart Start timestamp in milliseconds
     * @param periodEnd End timestamp in milliseconds
     */
    fun calculate(
        algo: AlgorithmType,
        decisions: List<ComparisonDecision>,
        bgReadings: List<BgReading>,
        periodStart: Long,
        periodEnd: Long
    ): AlgorithmKpi {
        val durationHours = (periodEnd - periodStart).toDouble() / MS_PER_HOUR
        
        // Filter to period
        val periodDecisions = decisions.filter { it.timestamp in periodStart..periodEnd }
        val periodBg = bgReadings.filter { it.timestamp in periodStart..periodEnd }
        
        // === GLYCEMIC METRICS ===
        val glycemicMetrics = calculateGlycemicMetrics(periodBg)
        
        // === INSULIN METRICS ===
        val insulinMetrics = calculateInsulinMetrics(periodDecisions, durationHours)
        
        // === LOOP BEHAVIOR ===
        val loopMetrics = calculateLoopMetrics(periodDecisions)
        
        // === SAFETY EVENTS ===
        val safetyMetrics = calculateSafetyEvents(periodBg)
        
        return AlgorithmKpi(
            algo = algo,
            periodStart = periodStart,
            periodEnd = periodEnd,
            durationHours = durationHours,
            // Glycemic
            tir70_180 = glycemicMetrics.tir70_180,
            tir70_140 = glycemicMetrics.tir70_140,
            timeBelow70 = glycemicMetrics.timeBelow70,
            timeBelow54 = glycemicMetrics.timeBelow54,
            timeAbove180 = glycemicMetrics.timeAbove180,
            timeAbove250 = glycemicMetrics.timeAbove250,
            meanBg = glycemicMetrics.meanBg,
            medianBg = glycemicMetrics.medianBg,
            bgStdDev = glycemicMetrics.stdDev,
            bgCv = glycemicMetrics.cv,
            gmi = glycemicMetrics.gmi,
            // Insulin
            tdd = insulinMetrics.tdd,
            basalTotal = insulinMetrics.basalTotal,
            smbTotal = insulinMetrics.smbTotal,
            smbCount = insulinMetrics.smbCount,
            smbMax = insulinMetrics.smbMax,
            avgBasalRate = insulinMetrics.avgBasalRate,
            // Loop
            tbrChangesCount = loopMetrics.tbrChangesCount,
            avgTempBasalPercent = loopMetrics.avgTempBasalPercent,
            zeroBasalMinutes = loopMetrics.zeroBasalMinutes,
            // Safety
            hypoEventsCount = safetyMetrics.hypoEventsCount,
            severeHypoEventsCount = safetyMetrics.severeHypoEventsCount,
            hyperEventsCount = safetyMetrics.hyperEventsCount
        )
    }
    
    // =========================================================================
    // GLYCEMIC METRICS
    // =========================================================================
    
    private data class GlycemicResult(
        val tir70_180: Double,
        val tir70_140: Double,
        val timeBelow70: Double,
        val timeBelow54: Double,
        val timeAbove180: Double,
        val timeAbove250: Double,
        val meanBg: Double,
        val medianBg: Double,
        val stdDev: Double,
        val cv: Double,
        val gmi: Double
    )
    
    private fun calculateGlycemicMetrics(bgReadings: List<BgReading>): GlycemicResult {
        if (bgReadings.isEmpty()) {
            return GlycemicResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        val values = bgReadings.map { it.valueMgdl }
        val count = values.size.toDouble()
        
        // Time in range calculations (% of readings)
        fun percentIn(range: ClosedFloatingPointRange<Double>): Double =
            (values.count { it in range } / count) * 100.0
        
        val tir70_180 = percentIn(70.0..180.0)
        val tir70_140 = percentIn(70.0..140.0)
        val timeBelow70 = percentIn(0.0..69.99)
        val timeBelow54 = percentIn(0.0..53.99)
        val timeAbove180 = percentIn(180.01..Double.MAX_VALUE)
        val timeAbove250 = percentIn(250.01..Double.MAX_VALUE)
        
        // Central tendency
        val meanBg = values.average()
        val sortedValues = values.sorted()
        val medianBg = if (sortedValues.isNotEmpty()) {
            if (sortedValues.size % 2 == 0) {
                (sortedValues[sortedValues.size / 2 - 1] + sortedValues[sortedValues.size / 2]) / 2.0
            } else {
                sortedValues[sortedValues.size / 2]
            }
        } else 0.0
        
        // Variability
        val variance = values.map { (it - meanBg) * (it - meanBg) }.average()
        val stdDev = sqrt(variance)
        val cv = if (meanBg > 0) (stdDev / meanBg) * 100.0 else 0.0
        
        // GMI Formula: 3.31 + 0.02392 × mean_glucose_mg/dL
        val gmi = 3.31 + 0.02392 * meanBg
        
        return GlycemicResult(
            tir70_180 = tir70_180,
            tir70_140 = tir70_140,
            timeBelow70 = timeBelow70,
            timeBelow54 = timeBelow54,
            timeAbove180 = timeAbove180,
            timeAbove250 = timeAbove250,
            meanBg = meanBg,
            medianBg = medianBg,
            stdDev = stdDev,
            cv = cv,
            gmi = gmi
        )
    }
    
    // =========================================================================
    // INSULIN METRICS
    // =========================================================================
    
    private data class InsulinResult(
        val tdd: Double,
        val basalTotal: Double,
        val smbTotal: Double,
        val smbCount: Int,
        val smbMax: Double,
        val avgBasalRate: Double
    )
    
    private fun calculateInsulinMetrics(
        decisions: List<ComparisonDecision>,
        periodHours: Double
    ): InsulinResult {
        if (decisions.isEmpty()) {
            return InsulinResult(0.0, 0.0, 0.0, 0, 0.0, 0.0)
        }
        
        var basalTotal = 0.0
        var smbTotal = 0.0
        var smbCount = 0
        var smbMax = 0.0
        
        // Process each decision
        for (i in decisions.indices) {
            val decision = decisions[i]
            val nextDecision = decisions.getOrNull(i + 1)
            
            // Calculate duration until next decision (or assume 5 min)
            val durationMinutes = if (nextDecision != null) {
                val diff = (nextDecision.timestamp - decision.timestamp).toDouble() / MS_PER_MINUTE
                if (diff in 1.0..15.0) diff else DEFAULT_TICK_MINUTES
            } else {
                DEFAULT_TICK_MINUTES
            }
            
            // Basal contribution: rate (U/h) × duration (hours)
            val durationHours = durationMinutes / 60.0
            basalTotal += decision.basalRateUph * durationHours
            
            // SMB contribution
            if (decision.smbU > 0) {
                smbTotal += decision.smbU
                smbCount++
                if (decision.smbU > smbMax) smbMax = decision.smbU
            }
        }
        
        val tdd = basalTotal + smbTotal
        val avgBasalRate = if (periodHours > 0) basalTotal / periodHours else 0.0
        
        return InsulinResult(
            tdd = tdd,
            basalTotal = basalTotal,
            smbTotal = smbTotal,
            smbCount = smbCount,
            smbMax = smbMax,
            avgBasalRate = avgBasalRate
        )
    }
    
    // =========================================================================
    // LOOP BEHAVIOR METRICS
    // =========================================================================
    
    private data class LoopResult(
        val tbrChangesCount: Int,
        val avgTempBasalPercent: Double,
        val zeroBasalMinutes: Double
    )
    
    private fun calculateLoopMetrics(decisions: List<ComparisonDecision>): LoopResult {
        if (decisions.isEmpty()) {
            return LoopResult(0, 100.0, 0.0)
        }
        
        var tbrChanges = 0
        var zeroBasalMinutes = 0.0
        var lastRate: Double? = null
        val ratePercents = mutableListOf<Double>()
        
        for (i in decisions.indices) {
            val decision = decisions[i]
            val nextDecision = decisions.getOrNull(i + 1)
            
            val durationMinutes = if (nextDecision != null) {
                val diff = (nextDecision.timestamp - decision.timestamp).toDouble() / MS_PER_MINUTE
                if (diff in 1.0..15.0) diff else DEFAULT_TICK_MINUTES
            } else {
                DEFAULT_TICK_MINUTES
            }
            
            // Count TBR changes (rate changed from last)
            if (lastRate != null && kotlin.math.abs(decision.basalRateUph - lastRate) > 0.05) {
                tbrChanges++
            }
            lastRate = decision.basalRateUph
            
            // Track zero basal time
            if (decision.basalRateUph < 0.01) {
                zeroBasalMinutes += durationMinutes
            }
            
            // For average percent, we'd need profile basal. Approximate with rate if available.
            // Here we just store rates and compute average
            ratePercents.add(decision.basalRateUph)
        }
        
        // Avg temp basal percent is approximate (would need profile basal to be accurate)
        val avgRate = if (ratePercents.isNotEmpty()) ratePercents.average() else 0.0
        
        return LoopResult(
            tbrChangesCount = tbrChanges,
            avgTempBasalPercent = avgRate * 100.0, // Placeholder, ideally rate/profileRate * 100
            zeroBasalMinutes = zeroBasalMinutes
        )
    }
    
    // =========================================================================
    // SAFETY EVENTS
    // =========================================================================
    
    private data class SafetyResult(
        val hypoEventsCount: Int,
        val severeHypoEventsCount: Int,
        val hyperEventsCount: Int
    )
    
    private fun calculateSafetyEvents(bgReadings: List<BgReading>): SafetyResult {
        if (bgReadings.isEmpty()) {
            return SafetyResult(0, 0, 0)
        }
        
        var hypoEvents = 0
        var severeHypoEvents = 0
        var hyperEvents = 0
        
        var inHypo = false
        var inSevereHypo = false
        var inHyper = false
        var hyperStartTime = 0L
        
        val sortedReadings = bgReadings.sortedBy { it.timestamp }
        
        for (i in sortedReadings.indices) {
            val reading = sortedReadings[i]
            val bg = reading.valueMgdl
            
            // Hypo event: entering <70 from >=70
            if (bg < 70.0 && !inHypo) {
                hypoEvents++
                inHypo = true
            } else if (bg >= 70.0) {
                inHypo = false
            }
            
            // Severe hypo event: entering <54 from >=54
            if (bg < 54.0 && !inSevereHypo) {
                severeHypoEvents++
                inSevereHypo = true
            } else if (bg >= 54.0) {
                inSevereHypo = false
            }
            
            // Hyper event: >250 for >30 minutes
            if (bg > 250.0) {
                if (!inHyper) {
                    hyperStartTime = reading.timestamp
                    inHyper = true
                } else if (reading.timestamp - hyperStartTime >= 30 * MS_PER_MINUTE) {
                    hyperEvents++
                    // Reset to avoid counting same episode multiple times
                    hyperStartTime = reading.timestamp
                }
            } else {
                inHyper = false
            }
        }
        
        return SafetyResult(
            hypoEventsCount = hypoEvents,
            severeHypoEventsCount = severeHypoEvents,
            hyperEventsCount = hyperEvents
        )
    }
    
    // =========================================================================
    // MULTI-DAY AGGREGATION
    // =========================================================================
    
    /**
     * Calculate KPIs for multiple days and return daily + aggregate results.
     */
    fun calculateMultiDay(
        algo: AlgorithmType,
        decisions: List<ComparisonDecision>,
        bgReadings: List<BgReading>,
        periodStart: Long,
        periodEnd: Long
    ): List<AlgorithmKpi> {
        val results = mutableListOf<AlgorithmKpi>()
        var dayStart = periodStart
        
        while (dayStart < periodEnd) {
            val dayEnd = minOf(dayStart + MS_PER_DAY, periodEnd)
            val dailyKpi = calculate(algo, decisions, bgReadings, dayStart, dayEnd)
            results.add(dailyKpi)
            dayStart = dayEnd
        }
        
        return results
    }
}
