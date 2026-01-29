package app.aaps.plugins.aps.openAPSAIMI.comparison

import java.io.File
import kotlin.math.abs

class ComparisonCsvParser {

    fun parse(file: File): List<ComparisonEntry> {
        if (!file.exists() || !file.canRead()) {
            return emptyList()
        }

        val entries = mutableListOf<ComparisonEntry>()
        
        try {
            file.bufferedReader().use { reader ->
                // Skip header
                reader.readLine()
                
                reader.lineSequence().forEach { line ->
                    parseLine(line)?.let { entries.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return entries
    }

    private fun parseLine(line: String): ComparisonEntry? {
        return try {
            // Nouveau format CSV (voir header dans AimiSmbComparator)
            // Timestamp,Date,BG,Delta,ShortAvgDelta,LongAvgDelta,IOB,COB,
            // AIMI_Rate,AIMI_SMB,AIMI_Duration,AIMI_EventualBG,AIMI_TargetBG,
            // SMB_Rate,SMB_SMB,SMB_Duration,SMB_EventualBG,SMB_TargetBG,
            // Diff_Rate,Diff_SMB,Diff_EventualBG,
            // MaxIOB,MaxBasal,MicroBolus_Allowed,
            // AIMI_Insulin_30min,SMB_Insulin_30min,Cumul_Diff,
            // AIMI_Active,SMB_Active,Both_Active,
            // AIMI_UAM_Last,SMB_UAM_Last,
            // Reason_AIMI,Reason_SMB

            val parts = line.split(
                ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()
            )

            if (parts.size < 34) return null

            ComparisonEntry(
                timestamp = parts[0].toLongOrNull() ?: return null,
                date = parts[1],
                bg = parts[2].toDoubleOrNull() ?: return null,
                delta = parts[3].toDoubleOrNull(),
                shortAvgDelta = parts[4].toDoubleOrNull(),
                longAvgDelta = parts[5].toDoubleOrNull(),
                iob = parts[6].toDoubleOrNull() ?: 0.0,
                cob = parts[7].toDoubleOrNull() ?: 0.0,
                aimiRate = parts[8].toDoubleOrNull(),
                aimiSmb = parts[9].toDoubleOrNull(),
                aimiDuration = parts[10].toIntOrNull() ?: 0,
                aimiEventualBg = parts[11].toDoubleOrNull(),
                aimiTargetBg = parts[12].toDoubleOrNull(),
                smbRate = parts[13].toDoubleOrNull(),
                smbSmb = parts[14].toDoubleOrNull(),
                smbDuration = parts[15].toIntOrNull() ?: 0,
                smbEventualBg = parts[16].toDoubleOrNull(),
                smbTargetBg = parts[17].toDoubleOrNull(),
                diffRate = parts[18].toDoubleOrNull(),
                diffSmb = parts[19].toDoubleOrNull(),
                diffEventualBg = parts[20].toDoubleOrNull(),
                maxIob = parts[21].toDoubleOrNull(),
                maxBasal = parts[22].toDoubleOrNull(),
                microBolusAllowed = parts[23] == "1",
                aimiInsulin30 = parts[24].toDoubleOrNull(),
                smbInsulin30 = parts[25].toDoubleOrNull(),
                cumulativeDiff = parts[26].toDoubleOrNull(),
                aimiActive = parts[27] == "1",
                smbActive = parts[28] == "1",
                bothActive = parts[29] == "1",
                aimiUamLast = parts.getOrNull(30)?.toDoubleOrNull(),
                smbUamLast = parts.getOrNull(31)?.toDoubleOrNull(),
                
                // Handle versioning (Logic Update Step 6904)
                // Old Schema (size ~34): reasons at 32, 33
                // New Schema (size ~37): 32=Verdict, 33=Artifact, 34=Sign, 35=ReasonAimi, 36=ReasonSmb
                
                reasonAimi = if (parts.size >= 37) parts.getOrNull(35)?.trim('"') ?: "" else parts.getOrNull(32)?.trim('"') ?: "",
                reasonSmb = if (parts.size >= 37) parts.getOrNull(36)?.trim('"') ?: "" else parts.getOrNull(33)?.trim('"') ?: "",
                
                // New Fields Population
                verdict = if (parts.size >= 37) parts.getOrNull(32) ?: "" else "",
                artifactFlag = if (parts.size >= 37) parts.getOrNull(33) ?: "" else "",
                diffSign = if (parts.size >= 37) parts.getOrNull(34) ?: "" else ""
            )
        } catch (e: Exception) {
            null
        }
    }
    // TIR réel basé sur BG
    fun calculateTimeInRange(
        entries: List<ComparisonEntry>,
        lower: Double,
        upper: Double
    ): Double {
        if (entries.isEmpty()) return 0.0
        val inRange = entries.count { it.bg in lower..upper }
        return inRange.toDouble() / entries.size * 100.0
    }

    // TIR prédit basé sur eventualBG de chaque algo
    fun calculatePredictedTimeInRange(
        entries: List<ComparisonEntry>,
        lower: Double,
        upper: Double
    ): Pair<Double, Double> {
        if (entries.isEmpty()) return 0.0 to 0.0

        val aimiValid = entries.filter { it.aimiEventualBg != null }
        val smbValid = entries.filter { it.smbEventualBg != null }

        val aimiInRange = aimiValid.count { it.aimiEventualBg!! in lower..upper }
        val smbInRange = smbValid.count { it.smbEventualBg!! in lower..upper }

        val aimiTir = if (aimiValid.isNotEmpty())
            aimiInRange.toDouble() / aimiValid.size * 100.0 else 0.0
        val smbTir = if (smbValid.isNotEmpty())
            smbInRange.toDouble() / smbValid.size * 100.0 else 0.0

        return aimiTir to smbTir
    }

    fun calculateStats(entries: List<ComparisonEntry>): ComparisonStats {
        if (entries.isEmpty()) {
            return ComparisonStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val rateDiffs = entries.mapNotNull { it.diffRate }
        val smbDiffs = entries.mapNotNull { it.diffSmb }
        
        val avgRateDiff = if (rateDiffs.isNotEmpty()) rateDiffs.average() else 0.0
        val avgSmbDiff = if (smbDiffs.isNotEmpty()) smbDiffs.average() else 0.0
        
        // Agreement: diff rate < 0.1 U/h and diff SMB < 0.1 U
        val agreementCount = entries.count { entry ->
            val rateDiff = entry.diffRate ?: 0.0
            val smbDiff = entry.diffSmb ?: 0.0
            abs(rateDiff) < 0.1 && abs(smbDiff) < 0.1
        }
        val agreementRate = agreementCount.toDouble() / entries.size * 100.0
        
        // Win rate: AIMI more aggressive (higher rate or SMB)
        val aimiWinCount = entries.count { entry ->
            val rateDiff = entry.diffRate ?: 0.0
            val smbDiff = entry.diffSmb ?: 0.0
            rateDiff > 0.1 || smbDiff > 0.1
        }
        val aimiWinRate = aimiWinCount.toDouble() / entries.size * 100.0
        
        val smbWinCount = entries.count { entry ->
            val rateDiff = entry.diffRate ?: 0.0
            val smbDiff = entry.diffSmb ?: 0.0
            rateDiff < -0.1 || smbDiff < -0.1
        }
        val smbWinRate = smbWinCount.toDouble() / entries.size * 100.0
        
        return ComparisonStats(
            totalEntries = entries.size,
            avgRateDiff = avgRateDiff,
            avgSmbDiff = avgSmbDiff,
            agreementRate = agreementRate,
            aimiWinRate = aimiWinRate,
            smbWinRate = smbWinRate
        )
    }

    fun calculateSafetyMetrics(entries: List<ComparisonEntry>): SafetyMetrics {
        if (entries.isEmpty()) {
            return SafetyMetrics(0.0, "Faible", "Faible", 0.0, 0.0)
        }

        // Calculate rate variability (standard deviation)
        val aimiRates = entries.mapNotNull { it.aimiRate }
        val smbRates = entries.mapNotNull { it.smbRate }

        val aimiVariability = if (aimiRates.isNotEmpty()) {
            val mean = aimiRates.average()
            kotlin.math.sqrt(aimiRates.map { (it - mean) * (it - mean) }.average())
        } else 0.0

        val smbVariability = if (smbRates.isNotEmpty()) {
            val mean = smbRates.average()
            kotlin.math.sqrt(smbRates.map { (it - mean) * (it - mean) }.average())
        } else 0.0

        val variabilityScore = ((smbVariability / (aimiVariability + 0.1)) * 50).coerceIn(0.0, 100.0)
        
        val variabilityLabel = when {
            variabilityScore < 30 -> "Faible"
            variabilityScore < 60 -> "Modéré"
            else -> "Élevé"
        }

        // Estimate hypo risk based on aggressive low basal decisions
        val aggressiveLowCount = entries.count { 
            (it.aimiRate ?: 0.0) < 0.5 || (it.smbRate ?: 0.0) < 0.5
        }
        val hypoRiskPercent = (aggressiveLowCount.toDouble() / entries.size) * 100

        val estimatedHypoRisk = when {
            hypoRiskPercent < 20 -> "Faible"
            hypoRiskPercent < 40 -> "Modéré"
            else -> "Élevé"
        }

        return SafetyMetrics(
            variabilityScore = variabilityScore,
            variabilityLabel = variabilityLabel,
            estimatedHypoRisk = estimatedHypoRisk,
            aimiVariability = aimiVariability,
            smbVariability = smbVariability
        )
    }

    fun calculateClinicalImpact(entries: List<ComparisonEntry>): ClinicalImpact {
        if (entries.isEmpty()) {
            return ClinicalImpact(0.0, 0.0, 0.0, 0.0, 0.0)
        }

        var totalInsulinAimi = 0.0
        var totalInsulinSmb = 0.0
        
        // Iterate with index to calculate deltas
        for (i in entries.indices) {
            val entry = entries[i]
            val nextEntry = entries.getOrNull(i + 1)
            
            // Calculate duration until next entry (default 5 min if last or gap > 10 min)
            val durationMin = if (nextEntry != null) {
                val diff = (nextEntry.timestamp - entry.timestamp) / 60000.0 // ms to min
                if (diff in 1.0..15.0) diff else 5.0 // Cap holes at 5 min assumption or use 5 min if gap is huge
            } else {
                5.0
            }
            val durationHours = durationMin / 60.0

            // AIMI
            val aimiBasal = (entry.aimiRate ?: 0.0) * durationHours
            val aimiSmb = entry.aimiSmb ?: 0.0
            totalInsulinAimi += (aimiBasal + aimiSmb)

            // SMB
            val smbBasal = (entry.smbRate ?: 0.0) * durationHours
            val smbSmb = entry.smbSmb ?: 0.0
            totalInsulinSmb += (smbBasal + smbSmb)
        }

        val cumulativeDiff = totalInsulinAimi - totalInsulinSmb

        // Calculate average per hour
        // Total duration is roughly (last - first) or entries * 5min
        val totalHours = if (entries.isNotEmpty()) {
             (entries.last().timestamp - entries.first().timestamp) / 3600000.0 
        } else 0.0
        
        // Avoid division by zero
        val safeHours = if (totalHours > 0.1) totalHours else (entries.size * 5.0 / 60.0)

        val avgInsulinPerHourAimi = if (safeHours > 0) totalInsulinAimi / safeHours else 0.0
        val avgInsulinPerHourSmb = if (safeHours > 0) totalInsulinSmb / safeHours else 0.0

        return ClinicalImpact(
            totalInsulinAimi = totalInsulinAimi,
            totalInsulinSmb = totalInsulinSmb,
            cumulativeDiff = cumulativeDiff,
            avgInsulinPerHourAimi = avgInsulinPerHourAimi,
            avgInsulinPerHourSmb = avgInsulinPerHourSmb
        )
    }

    fun calculateExpandedGlycemicMetrics(entries: List<ComparisonEntry>): GlycemicMetrics {
        if (entries.isEmpty()) return GlycemicMetrics()
        
        val bgs = entries.map { it.bg }
        val meanBg = bgs.average()
        val stdDev = kotlin.math.sqrt(bgs.map { (it - meanBg) * (it - meanBg) }.average())
        val cv = if (meanBg > 0) (stdDev / meanBg) * 100.0 else 0.0
        
        // GMI Formula: 3.31 + 0.02392 * meanBG_mg/dL
        val gmi = 3.31 + 0.02392 * meanBg

        val sortedBgs = bgs.sorted()
        val medianBg = if (sortedBgs.isNotEmpty()) sortedBgs[sortedBgs.size / 2] else 0.0

        // Time calculations (assuming 5 min per entry for simplicity, or we could use deltas like above)
        // Here we use % of entries to match typical TIR calc
        fun percentIn(range: ClosedFloatingPointRange<Double>) = 
            (bgs.count { it in range }.toDouble() / bgs.size) * 100.0

        return GlycemicMetrics(
            meanBg = meanBg,
            medianBg = medianBg,
            stdDev = stdDev,
            cv = cv,
            gmi = gmi,
            tir70_180 = percentIn(70.0..180.0),
            tir70_140 = percentIn(70.0..140.0),
            timeBelow70 = percentIn(0.0..69.9),
            timeBelow54 = percentIn(0.0..53.9),
            timeAbove180 = percentIn(180.1..1000.0),
            timeAbove250 = percentIn(250.1..1000.0)
        )
    }

    fun findCriticalMoments(entries: List<ComparisonEntry>): List<CriticalMoment> {
        return entries
            .mapIndexed { index, entry ->
                val totalDivergence = abs(entry.diffRate ?: 0.0) + abs(entry.diffSmb ?: 0.0)
                CriticalMoment(
                    index = index,
                    timestamp = entry.timestamp,
                    date = entry.date,
                    bg = entry.bg,
                    iob = entry.iob,
                    cob = entry.cob,
                    divergenceRate = entry.diffRate,
                    divergenceSmb = entry.diffSmb,
                    reasonAimi = entry.reasonAimi,
                    reasonSmb = entry.reasonSmb
                ) to totalDivergence
            }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }

    fun generateRecommendation(
        stats: ComparisonStats,
        safety: SafetyMetrics,
        impact: ClinicalImpact
    ): Recommendation {
        val aggressivenessRatio = if (stats.aimiWinRate > 0) {
            stats.smbWinRate / stats.aimiWinRate
        } else {
            stats.smbWinRate / 1.0
        }

        val preferredAlgorithm = when {
            stats.agreementRate > 70 -> "Équivalent"
            impact.cumulativeDiff > 2.0 -> "SMB" // AIMI delivered significantly more
            impact.cumulativeDiff < -2.0 && safety.variabilityScore < 50 -> "AIMI" // SMB more aggressive but stable
            impact.cumulativeDiff < -2.0 && safety.variabilityScore >= 50 -> "AIMI" // SMB more aggressive and variable
            else -> "Équivalent"
        }

        val reason = when (preferredAlgorithm) {
            "AIMI" -> {
                if (aggressivenessRatio > 2.0) {
                    "SMB ${String.format("%.1f", aggressivenessRatio)}x plus agressif avec variabilité ${safety.variabilityLabel.lowercase()}"
                } else {
                    "Approche plus conservatrice avec variabilité ${safety.variabilityLabel.lowercase()}"
                }
            }
            "SMB" -> "Plus réactif aux variations glycémiques"
            else -> "Les deux algorithmes montrent des performances similaires (${String.format("%.1f", stats.agreementRate)}% d'accord)"
        }

        val confidenceLevel = when {
            stats.totalEntries < 10 -> "Faible"
            stats.totalEntries < 30 -> "Modérée"
            else -> "Élevée"
        }

        val safetyNote = when {
            safety.estimatedHypoRisk == "Élevé" -> "⚠️ Surveillance accrue recommandée"
            safety.variabilityScore > 70 -> "⚠️ Variabilité importante détectée"
            impact.cumulativeDiff < -5.0 -> "⚠️ Grande différence d'insuline totale"
            else -> "Profil de sécurité acceptable"
        }

        return Recommendation(
            preferredAlgorithm = preferredAlgorithm,
            reason = reason,
            confidenceLevel = confidenceLevel,
            safetyNote = safetyNote
        )
    }
    fun analyze(entries: List<ComparisonEntry>): FullComparisonReport {
        val stats = calculateStats(entries)
        val safety = calculateSafetyMetrics(entries)
        val impact = calculateClinicalImpact(entries)
        val criticalMoments = findCriticalMoments(entries)
        val recommendation = generateRecommendation(stats, safety, impact)

        // Calculate TIR
        val actualTir = calculateTimeInRange(entries, 70.0, 180.0)
        val (aimiPredTir, smbPredTir) = calculatePredictedTimeInRange(entries, 70.0, 180.0)
        
        val tir = ComparisonTir(
            actualTir = actualTir,
            aimiPredictedTir = aimiPredTir,
            smbPredictedTir = smbPredTir
        )

        return FullComparisonReport(
            stats = stats,
            safety = safety,
            impact = impact,
            glycemic = calculateExpandedGlycemicMetrics(entries),
            tir = tir,
            criticalMoments = criticalMoments,
            recommendation = recommendation
        )
    }
    fun getLast24h(entries: List<ComparisonEntry>, now: Long): List<ComparisonEntry> {
        val window = 24 * 60 * 60 * 1000L
        return entries.filter { it.timestamp >= now - window }
    }

    fun getLast7d(entries: List<ComparisonEntry>, now: Long): List<ComparisonEntry> {
        val window = 7 * 24 * 60 * 60 * 1000L
        return entries.filter { it.timestamp >= now - window }
    }

    fun generateLlmSummary(
        periodLabel: String,
        stats: ComparisonStats,
        safety: SafetyMetrics,
        impact: ClinicalImpact,
        criticalMoments: List<CriticalMoment>,
        recommendation: Recommendation
    ): String {
        val sb = StringBuilder()
        sb.append("=== AIMI vs SMB Comparison Report ($periodLabel) ===\n\n")

        sb.append("## 1. Global Performance\n")
        sb.append("- Agreement Rate: %.1f%%\n".format(stats.agreementRate))
        sb.append("- AIMI More Aggressive: %.1f%%\n".format(stats.aimiWinRate))
        sb.append("- SMB More Aggressive: %.1f%%\n".format(stats.smbWinRate))
        sb.append("- Total Insulin Difference: %.2f U (AIMI - SMB)\n".format(impact.cumulativeDiff))
        
        sb.append("\n## 2. Safety Analysis\n")
        sb.append("- Variability Score: %.1f/100 (%s)\n".format(safety.variabilityScore, safety.variabilityLabel))
        sb.append("- Estimated Hypo Risk: %s\n".format(safety.estimatedHypoRisk))
        sb.append("- Safety Note: %s\n".format(recommendation.safetyNote))

        sb.append("\n## 3. Recommendation\n")
        sb.append("- Preferred Algorithm: **${recommendation.preferredAlgorithm}**\n")
        sb.append("- Reason: ${recommendation.reason}\n")
        sb.append("- Confidence: ${recommendation.confidenceLevel}\n")

        sb.append("\n## 4. Critical Moments (Top Divergences)\n")
        criticalMoments.take(3).forEach { m ->
            sb.append("- [${m.date}] BG: ${m.bg} | IOB: ${m.iob} | COB: ${m.cob}\n")
            sb.append("  AIMI Reason: ${m.reasonAimi}\n")
            sb.append("  SMB Reason: ${m.reasonSmb}\n")
            sb.append("  (Diff Rate: ${m.divergenceRate}, Diff SMB: ${m.divergenceSmb})\n\n")
        }

        sb.append("\n## 5. Request to LLM\n")
        sb.append("Based on this data, analyze why the algorithms diverged. Focus on the 'Critical Moments' and the 'Safety Analysis'. Does the aggressive behavior of the winner seem justified given the glucose context?")
        
        return sb.toString()
    }
}

