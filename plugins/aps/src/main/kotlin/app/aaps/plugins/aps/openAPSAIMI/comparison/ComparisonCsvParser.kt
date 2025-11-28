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
            // CSV format: Timestamp,Date,BG,IOB,COB,AIMI_Rate,AIMI_SMB,AIMI_Duration,SMB_Rate,SMB_SMB,SMB_Duration,Diff_Rate,Diff_SMB,Reason_AIMI,Reason_SMB
            val parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()) // Handle quoted strings with commas
            
            if (parts.size < 15) return null
            
            ComparisonEntry(
                timestamp = parts[0].toLongOrNull() ?: return null,
                date = parts[1],
                bg = parts[2].toDoubleOrNull() ?: return null,
                iob = parts[3].toDoubleOrNull() ?: return null,
                cob = parts[4].toDoubleOrNull() ?: return null,
                aimiRate = parts[5].toDoubleOrNull(),
                aimiSmb = parts[6].toDoubleOrNull(),
                aimiDuration = parts[7].toIntOrNull() ?: 0,
                smbRate = parts[8].toDoubleOrNull(),
                smbSmb = parts[9].toDoubleOrNull(),
                smbDuration = parts[10].toIntOrNull() ?: 0,
                diffRate = parts[11].toDoubleOrNull(),
                diffSmb = parts[12].toDoubleOrNull(),
                reasonAimi = parts.getOrNull(13)?.trim('"') ?: "",
                reasonSmb = parts.getOrNull(14)?.trim('"') ?: ""
            )
        } catch (e: Exception) {
            null
        }
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

        // Estimate total insulin = (basal rate * duration/60) + SMB
        val totalInsulinAimi = entries.sumOf { entry ->
            val basalInsulin = (entry.aimiRate ?: 0.0) * (entry.aimiDuration / 60.0)
            val smbInsulin = entry.aimiSmb ?: 0.0
            basalInsulin + smbInsulin
        }

        val totalInsulinSmb = entries.sumOf { entry ->
            val basalInsulin = (entry.smbRate ?: 0.0) * (entry.smbDuration / 60.0)
            val smbInsulin = entry.smbSmb ?: 0.0
            basalInsulin + smbInsulin
        }

        val cumulativeDiff = totalInsulinAimi - totalInsulinSmb

        // Calculate average per hour (assuming 5-minute cycles)
        val totalHours = (entries.size * 5.0) / 60.0
        val avgInsulinPerHourAimi = if (totalHours > 0) totalInsulinAimi / totalHours else 0.0
        val avgInsulinPerHourSmb = if (totalHours > 0) totalInsulinSmb / totalHours else 0.0

        return ClinicalImpact(
            totalInsulinAimi = totalInsulinAimi,
            totalInsulinSmb = totalInsulinSmb,
            cumulativeDiff = cumulativeDiff,
            avgInsulinPerHourAimi = avgInsulinPerHourAimi,
            avgInsulinPerHourSmb = avgInsulinPerHourSmb
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
}

