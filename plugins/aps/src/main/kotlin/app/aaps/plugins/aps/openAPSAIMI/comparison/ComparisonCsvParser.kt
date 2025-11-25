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
}
