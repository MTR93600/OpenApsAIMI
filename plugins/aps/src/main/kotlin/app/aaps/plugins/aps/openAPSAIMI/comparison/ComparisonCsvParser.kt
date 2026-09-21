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
                val firstLine = reader.readLine()
                // The header is normally the first line. A file that lost its header still holds
                // valid rows, so only skip the first line when it really is a header.
                if (firstLine != null && !isHeaderLine(firstLine)) {
                    parseLine(firstLine)?.let { entries.add(it) }
                }

                reader.lineSequence().forEach { line ->
                    parseLine(line)?.let { entries.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return entries
    }

    private fun isHeaderLine(line: String): Boolean =
        line.startsWith("SchemaVersion,") || line.startsWith("Timestamp,")

    /**
     * Reads one data row of `comparison_aimi_smb.csv`.
     *
     * Three layouts have been written over time, and one file can hold rows of more than one of
     * them, because the header is written only when the file is created. So the layout is detected
     * per row, not from the header:
     *  - schema 3 (current, see `AimiSmbComparator.CSV_HEADER`): 49 columns, first column is
     *    SchemaVersion, then Timestamp, Date, BG, ...
     *  - older layout: 37 columns, starts at Timestamp, carries Verdict, Artifact_Flag, Diff_Sign.
     *  - oldest layout: 34 columns, starts at Timestamp, no Verdict block.
     *
     * Column indexes below are relative to Timestamp, so they hold for all three layouts. The two
     * reason columns have always been written last, so they are read from the end of the row.
     *
     * The Verdict block and the cause flag block are read only when the row is long enough to
     * really hold them, so an old row keeps the defaults of [ComparisonEntry].
     */
    private fun parseLine(line: String): ComparisonEntry? {
        return try {
            val parts = line.split(FIELD_SEPARATOR)
            val offset = schemaOffset(parts)
            val columns = parts.size - offset
            if (columns < MIN_COLUMNS) return null

            fun field(index: Int): String = parts.getOrNull(offset + index)?.trim() ?: ""

            val hasVerdictBlock = columns >= VERDICT_COLUMNS
            // Only the current layout carries the flag block. On an older row the columns do not
            // exist at all, so the entry keeps its defaults instead of reporting a false that would
            // look like a real "this cause did not fire".
            val hasFlagBlock = columns >= FLAG_COLUMNS
            fun flag(index: Int): Boolean = hasFlagBlock && field(index) == "1"
            val reasonAimi = parts[parts.size - 2].trim().trim('"')
            val reasonSmb = parts[parts.size - 1].trim().trim('"')

            ComparisonEntry(
                timestamp = field(0).toLongOrNull() ?: return null,
                date = field(1),
                bg = field(2).toDoubleOrNull() ?: return null,
                delta = field(3).toDoubleOrNull(),
                shortAvgDelta = field(4).toDoubleOrNull(),
                longAvgDelta = field(5).toDoubleOrNull(),
                iob = field(6).toDoubleOrNull() ?: 0.0,
                cob = field(7).toDoubleOrNull() ?: 0.0,
                aimiRate = field(8).toDoubleOrNull(),
                aimiSmb = field(9).toDoubleOrNull(),
                aimiDuration = field(10).toIntOrNull() ?: 0,
                aimiEventualBg = field(11).toDoubleOrNull(),
                aimiTargetBg = field(12).toDoubleOrNull(),
                smbRate = field(13).toDoubleOrNull(),
                smbSmb = field(14).toDoubleOrNull(),
                smbDuration = field(15).toIntOrNull() ?: 0,
                smbEventualBg = field(16).toDoubleOrNull(),
                smbTargetBg = field(17).toDoubleOrNull(),
                diffRate = field(18).toDoubleOrNull(),
                diffSmb = field(19).toDoubleOrNull(),
                diffEventualBg = field(20).toDoubleOrNull(),
                maxIob = field(21).toDoubleOrNull(),
                maxBasal = field(22).toDoubleOrNull(),
                microBolusAllowed = field(23) == "1",
                aimiInsulin30 = field(24).toDoubleOrNull(),
                smbInsulin30 = field(25).toDoubleOrNull(),
                cumulativeDiff = field(26).toDoubleOrNull(),
                aimiActive = field(27) == "1",
                smbActive = field(28) == "1",
                bothActive = field(29) == "1",
                aimiUamLast = field(30).toDoubleOrNull(),
                smbUamLast = field(31).toDoubleOrNull(),
                reasonAimi = reasonAimi,
                reasonSmb = reasonSmb,
                verdict = if (hasVerdictBlock) field(32) else "",
                artifactFlag = if (hasVerdictBlock) field(33) else "",
                diffSign = if (hasVerdictBlock) field(34) else "",
                aimiFlagMealPriority = flag(35),
                aimiFlagRefractory = flag(36),
                aimiFlagThrottle = flag(37),
                aimiFlagCbf = flag(38),
                smbFlagRefractory = flag(39),
                smbFlagThrottle = flag(40),
                smbFlagCbf = flag(41),
                contextMealRise = flag(42),
                contextCobActive = flag(43),
                contextUamBias = flag(44),
                smbLastBolusAgeMin = if (hasFlagBlock) field(45).toDoubleOrNull() else null
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns 1 when the row starts with a SchemaVersion column, and 0 for the older layouts that
     * start with the timestamp. Epoch milliseconds are far larger than any schema number, so a
     * small first field followed by a numeric second field can only be a schema version.
     */
    private fun schemaOffset(parts: List<String>): Int {
        val first = parts.firstOrNull()?.trim()?.toLongOrNull() ?: return 0
        if (first < 1L || first > MAX_SCHEMA_VERSION) return 0
        return if (parts.getOrNull(1)?.trim()?.toLongOrNull() != null) 1 else 0
    }

    // Actual TIR, based on measured BG.
    fun calculateTimeInRange(
        entries: List<ComparisonEntry>,
        lower: Double,
        upper: Double
    ): Double {
        if (entries.isEmpty()) return 0.0
        val inRange = entries.count { it.bg in lower..upper }
        return inRange.toDouble() / entries.size * 100.0
    }

    // Predicted TIR, based on each algorithm's eventualBG.
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
            return SafetyMetrics(0.0, ComparisonLevel.LOW, ComparisonLevel.LOW, 0.0, 0.0)
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

        val variabilityLevel = when {
            variabilityScore < 30 -> ComparisonLevel.LOW
            variabilityScore < 60 -> ComparisonLevel.MODERATE
            else -> ComparisonLevel.HIGH
        }

        // Estimate hypo risk based on aggressive low basal decisions
        val aggressiveLowCount = entries.count {
            (it.aimiRate ?: 0.0) < 0.5 || (it.smbRate ?: 0.0) < 0.5
        }
        val hypoRiskPercent = (aggressiveLowCount.toDouble() / entries.size) * 100

        val estimatedHypoRisk = when {
            hypoRiskPercent < 20 -> ComparisonLevel.LOW
            hypoRiskPercent < 40 -> ComparisonLevel.MODERATE
            else -> ComparisonLevel.HIGH
        }

        return SafetyMetrics(
            variabilityScore = variabilityScore,
            variabilityLevel = variabilityLevel,
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
                    reasonSmb = entry.reasonSmb,
                    verdict = entry.verdict,
                    artifactFlag = entry.artifactFlag,
                    causes = entry.causes()
                ) to totalDivergence
            }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }

    fun generateRecommendation(
        stats: ComparisonStats,
        safety: SafetyMetrics,
        impact: ClinicalImpact,
        mode: ScoringMode = ScoringMode.BALANCED
    ): Recommendation {
        val modeShift = when (mode) {
            ScoringMode.BALANCED -> 0.0
            ScoringMode.POSTPRANDIAL -> 1.0
            ScoringMode.OVERNIGHT -> -1.0
        }

        val aggressivenessRatio = if (stats.aimiWinRate > 0) {
            stats.smbWinRate / stats.aimiWinRate
        } else {
            stats.smbWinRate / 1.0
        }

        val smbBiasThreshold = 2.0 + modeShift
        val aimiBiasThreshold = -2.0 + modeShift
        val preferredAlgorithm = when {
            stats.agreementRate > 70 -> null
            impact.cumulativeDiff > smbBiasThreshold -> AlgorithmType.OPENAPS_SMB // AIMI delivered significantly more
            impact.cumulativeDiff < aimiBiasThreshold && safety.variabilityScore < 50 -> AlgorithmType.AIMI // SMB more aggressive but stable
            impact.cumulativeDiff < aimiBiasThreshold && safety.variabilityScore >= 50 -> AlgorithmType.AIMI // SMB more aggressive and variable
            else -> null
        }

        val reasonKind: RecommendationReasonKind
        var reasonVariability: ComparisonLevel? = null
        var reasonAggressivenessRatio: Double? = null
        var reasonAgreementRate: Double? = null
        when (preferredAlgorithm) {
            AlgorithmType.AIMI -> {
                reasonVariability = safety.variabilityLevel
                if (aggressivenessRatio > 2.0) {
                    reasonKind = RecommendationReasonKind.SMB_MORE_AGGRESSIVE_WITH_VARIABILITY
                    reasonAggressivenessRatio = aggressivenessRatio
                } else {
                    reasonKind = RecommendationReasonKind.MORE_CONSERVATIVE_WITH_VARIABILITY
                }
            }
            AlgorithmType.OPENAPS_SMB -> reasonKind = RecommendationReasonKind.MORE_REACTIVE_TO_GLUCOSE_CHANGES
            null -> {
                reasonKind = RecommendationReasonKind.SIMILAR_PERFORMANCE
                reasonAgreementRate = stats.agreementRate
            }
        }

        val confidenceLevel = when {
            stats.totalEntries < 10 -> ComparisonLevel.LOW
            stats.totalEntries < 30 -> ComparisonLevel.MODERATE
            else -> ComparisonLevel.HIGH
        }

        val safetyNote = when {
            safety.estimatedHypoRisk == ComparisonLevel.HIGH -> SafetyNoteKind.INCREASED_MONITORING_RECOMMENDED
            safety.variabilityScore > 70 -> SafetyNoteKind.SIGNIFICANT_VARIABILITY_DETECTED
            impact.cumulativeDiff < -5.0 -> SafetyNoteKind.LARGE_INSULIN_DIFFERENCE
            else -> SafetyNoteKind.ACCEPTABLE_SAFETY_PROFILE
        }

        return Recommendation(
            preferredAlgorithm = preferredAlgorithm,
            reasonKind = reasonKind,
            reasonVariability = reasonVariability,
            reasonAggressivenessRatio = reasonAggressivenessRatio,
            reasonAgreementRate = reasonAgreementRate,
            confidenceLevel = confidenceLevel,
            safetyNote = safetyNote
        )
    }
    fun analyze(entries: List<ComparisonEntry>, mode: ScoringMode = ScoringMode.BALANCED): FullComparisonReport {
        val stats = calculateStats(entries)
        val safety = calculateSafetyMetrics(entries)
        val impact = calculateClinicalImpact(entries)
        val criticalMoments = findCriticalMoments(entries)
        val recommendation = generateRecommendation(stats, safety, impact, mode)

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

    /**
     * Plain English rendering of [Recommendation.reasonKind] for [generateLlmSummary]. Not shown in
     * the UI, so it does not need string resources — the UI module maps the same enum to translated
     * templates instead, see `ComparatorActivity.recommendationReasonText`.
     */
    private fun recommendationReasonForLlm(recommendation: Recommendation): String =
        when (recommendation.reasonKind) {
            RecommendationReasonKind.SMB_MORE_AGGRESSIVE_WITH_VARIABILITY ->
                "SMB %.1fx more aggressive with %s variability".format(
                    recommendation.reasonAggressivenessRatio ?: 0.0,
                    recommendation.reasonVariability?.name ?: ComparisonLevel.LOW.name
                )
            RecommendationReasonKind.MORE_CONSERVATIVE_WITH_VARIABILITY ->
                "More conservative approach with %s variability".format(
                    recommendation.reasonVariability?.name ?: ComparisonLevel.LOW.name
                )
            RecommendationReasonKind.MORE_REACTIVE_TO_GLUCOSE_CHANGES -> "More reactive to glucose changes"
            RecommendationReasonKind.SIMILAR_PERFORMANCE ->
                "Both algorithms show similar performance (%.1f%% agreement)".format(
                    recommendation.reasonAgreementRate ?: 0.0
                )
        }

    fun generateLlmSummary(
        periodLabel: String,
        stats: ComparisonStats,
        safety: SafetyMetrics,
        impact: ClinicalImpact,
        criticalMoments: List<CriticalMoment>,
        recommendation: Recommendation,
        mode: ScoringMode = ScoringMode.BALANCED
    ): String {
        val sb = StringBuilder()
        sb.append("=== AIMI vs SMB Comparison Report ($periodLabel) ===\n\n")
        sb.append("Scoring mode: ${mode.name}\n\n")

        sb.append("## 1. Global Performance\n")
        sb.append("- Agreement Rate: %.1f%%\n".format(stats.agreementRate))
        sb.append("- AIMI More Aggressive: %.1f%%\n".format(stats.aimiWinRate))
        sb.append("- SMB More Aggressive: %.1f%%\n".format(stats.smbWinRate))
        sb.append("- Total Insulin Difference: %.2f U (AIMI - SMB)\n".format(impact.cumulativeDiff))
        
        // This text is read by a model, not shown in the UI, so plain enum names are enough (same
        // reasoning as the "Causes" line below).
        sb.append("\n## 2. Safety Analysis\n")
        sb.append("- Variability Score: %.1f/100 (%s)\n".format(safety.variabilityScore, safety.variabilityLevel.name))
        sb.append("- Estimated Hypo Risk: %s\n".format(safety.estimatedHypoRisk.name))
        sb.append("- Safety Note: %s\n".format(recommendation.safetyNote.name))

        sb.append("\n## 3. Recommendation\n")
        sb.append("- Preferred Algorithm: **${recommendation.preferredAlgorithm?.name ?: "EQUIVALENT"}**\n")
        sb.append("- Reason: ${recommendationReasonForLlm(recommendation)}\n")
        sb.append("- Confidence: ${recommendation.confidenceLevel.name}\n")

        sb.append("\n## 4. Critical Moments (Top Divergences)\n")
        criticalMoments.take(3).forEach { m ->
            sb.append("- [${m.date}] BG: ${m.bg} | IOB: ${m.iob} | COB: ${m.cob}\n")
            // This text is read by a model, not shown in the UI, so plain names are enough.
            val causes = if (m.causes.isEmpty()) "none recorded" else m.causes.joinToString(", ") { it.name }
            sb.append("  Causes: $causes\n")
            if (m.verdict.isNotEmpty()) sb.append("  Verdict: ${m.verdict}\n")
            if (m.artifactFlag.isNotEmpty()) sb.append("  Artifact flag: ${m.artifactFlag}\n")
            sb.append("  AIMI Reason: ${m.reasonAimi}\n")
            sb.append("  SMB Reason: ${m.reasonSmb}\n")
            sb.append("  (Diff Rate: ${m.divergenceRate}, Diff SMB: ${m.divergenceSmb})\n\n")
        }

        sb.append("\n## 5. Request to LLM\n")
        sb.append("Based on this data, analyze why the algorithms diverged. Focus on the 'Causes' of the 'Critical Moments' and on the 'Safety Analysis'. Does the aggressive behavior of the winner seem justified given the glucose context?")
        
        return sb.toString()
    }

    private companion object {

        /** Splits on commas that are outside quotes: the two reason columns are quoted. */
        val FIELD_SEPARATOR = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()

        /** Column count of the oldest layout, from Timestamp to Reason_SMB. */
        const val MIN_COLUMNS = 34

        /** From this column count on, the row also carries Verdict, Artifact_Flag and Diff_Sign. */
        const val VERDICT_COLUMNS = 37

        /**
         * From this column count on, the row also carries the ten cause flags and
         * SMB_LastBolusAgeMin. This is the current layout, 48 columns from Timestamp on.
         */
        const val FLAG_COLUMNS = 48

        /** Highest value the first field may have to be read as a schema version. */
        const val MAX_SCHEMA_VERSION = 999L
    }
}
