package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.min

object PhysiologicalPatternPolicy {

    /** Meal patterns at or above this confidence override activity caps and hyper suppression. */
    const val MEAL_ACTIVE_CONFIDENCE = 0.70

    /**
     * Pattern SMB caps at or below this threshold are not applied to V3 delivery unless RBT
     * authority is effective — they remain visible in resolver shadow / JSONL only.
     */
    const val RESTRICTIVE_SMB_CAP_SHADOW_THRESHOLD_U = 0.55

    /**
     * @param maxSmbHbU the user's high-BG SMB ceiling. Catalogue caps are fractions of it, so a
     *   pattern reduces the ceiling the user configured instead of imposing an absolute dose that
     *   only suits one patient. Defaults to the conversion reference so existing callers and tests
     *   keep the previous numbers.
     */
    fun aggregate(
        readings: List<PhysiologicalPatternReading>,
        maxSmbHbU: Double = LEGACY_REFERENCE_MAX_SMB_HB_U,
    ): PhysiologicalPatternSnapshot {
        if (readings.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        val active = readings
            .filter { it.confidence >= 0.35 }
            .sortedByDescending { it.confidence }

        if (active.isEmpty()) return PhysiologicalPatternSnapshot.EMPTY

        var suppressMeal = false
        var suppressHyper = false
        var suppressWavelet = false
        var restrictiveHardCap: Double? = null

        for (reading in active) {
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            if (def.suppressMealInterpretation && reading.confidence >= 0.45) suppressMeal = true
            if (def.suppressHyperRelease && reading.confidence >= 0.40) suppressHyper = true
            if (def.suppressWaveletBoost && reading.confidence >= 0.40) suppressWavelet = true
            // Only HARD protective caps participate in the binding restrictive min().
            if (def.capKind == PatternCapKind.HARD) {
                def.capU(maxSmbHbU)?.let { cap ->
                    restrictiveHardCap = restrictiveHardCap?.let { min(it, cap) } ?: cap
                }
            }
        }

        val mealActive = active.any { isMealPatternReading(it) && it.confidence >= MEAL_ACTIVE_CONFIDENCE }
        val mealCap = if (mealActive) mealPatternCap(active, maxSmbHbU) else null
        val smbCap: Double?
        val smbCapKind: PatternCapKind?
        if (mealActive) {
            suppressMeal = false
            suppressHyper = false
            smbCap = mealCap?.proposedCapU ?: restrictiveHardCap
            smbCapKind = mealCap?.kind ?: restrictiveHardCap?.let { PatternCapKind.HARD }
        } else {
            smbCap = restrictiveHardCap
            smbCapKind = restrictiveHardCap?.let { PatternCapKind.HARD }
        }

        val dominant = active.first()
        val summary = active.take(4).joinToString(",") { "${it.id.name}@${aimiFmt2(it.confidence)}" }

        return PhysiologicalPatternSnapshot(
            active = active,
            dominant = dominant.id,
            dominantConfidence = dominant.confidence,
            suppressMealInterpretation = suppressMeal,
            suppressHyperRelease = suppressHyper,
            suppressWaveletBoost = suppressWavelet,
            smbCapU = smbCap,
            maxSmbHbU = maxSmbHbU,
            smbCapKind = smbCapKind,
            mealPatternCap = mealCap,
            reasonSummary = summary,
        )
    }

    private fun isMealPatternReading(reading: PhysiologicalPatternReading): Boolean =
        reading.id.category == PhysiologicalPatternCategory.MEAL &&
            reading.id != PhysiologicalPatternId.LATE_FAT_PROTEIN

    /**
     * Selects the most permissive active meal proposed cap and exposes its soft/hard kind.
     * Soft meal caps are proposals (not binding mins); hard meal caps remain binding.
     */
    fun mealPatternCap(active: List<PhysiologicalPatternReading>, maxSmbHbU: Double): PatternCapProposal? {
        var best: PatternCapProposal? = null
        for (reading in active) {
            if (!isMealPatternReading(reading)) continue
            val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
            val cap = def.capU(maxSmbHbU) ?: continue
            val proposal = PatternCapProposal(
                proposedCapU = cap,
                kind = def.capKind,
                sourceId = reading.id,
            )
            val current = best
            if (current == null || proposal.proposedCapU > current.proposedCapU) {
                best = proposal
            }
        }
        return best
    }
}
