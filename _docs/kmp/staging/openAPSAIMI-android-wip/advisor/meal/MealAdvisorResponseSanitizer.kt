package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

/**
 * Hardening of vision-LLM meal estimates **after** JSON parse (defense in depth).
 *
 * Rationale: even with a strict prompt and JSON mode, models can return inconsistent ranges,
 * non-finite numbers, oversized strings, or control characters. This layer bounds what reaches
 * the UI and the bolus pipeline, similar in spirit to post-parse validation / schema checks
 * recommended for LLM integrations.
 */
object MealAdvisorResponseSanitizer {

    /** Absolute cap for “recommended carbs” used for dosing context (grams). */
    internal const val MAX_RECOMMENDED_CARB_GRAMS = 400.0

    /** Absolute cap for FPU-equivalent display (grams carb equivalent). */
    internal const val MAX_FPU_EQUIVALENT_GRAMS = 120.0

    private const val MAX_DESCRIPTION_CHARS = 500
    private const val MAX_REASONING_CHARS = 2_000
    private const val MAX_RECOMMENDED_REASON_CHARS = 1_000
    private const val MAX_ITEM_NAME_CHARS = 200
    private const val MAX_ITEM_AMOUNT_CHARS = 120
    private const val MAX_VISIBLE_ITEMS = 30
    private const val MAX_UNCERTAIN_ITEMS = 30
    private const val MAX_INSULIN_NOTES = 20
    private const val MAX_NOTE_CHARS = 240

    fun secureEstimationResult(raw: EstimationResult): EstimationResult {
        val carbs = fixMacroRange(raw.carbs)
        val protein = fixMacroRange(raw.protein)
        val fat = fixMacroRange(raw.fat)

        val fpu = raw.fpuEquivalent
            .takeIf { it.isFinite() }?.coerceIn(0.0, MAX_FPU_EQUIVALENT_GRAMS) ?: 0.0

        // If the model explicitly recommends 0 g for dosing but reports meaningful meal carbs,
        // do not coerce upward into [min,max] — that would hide the inconsistency.
        val recCarbs = if (raw.recommendedCarbsForDose <= 0.0 &&
            raw.recommendedCarbsForDose.isFinite() &&
            carbs.estimate > 1.0
        ) {
            0.0
        } else {
            raw.recommendedCarbsForDose
                .takeIf { it.isFinite() }?.coerceIn(
                    carbs.min,
                    minOf(MAX_RECOMMENDED_CARB_GRAMS, maxOf(carbs.max, carbs.estimate)),
                ) ?: carbs.estimate.coerceIn(carbs.min, minOf(MAX_RECOMMENDED_CARB_GRAMS, carbs.max))
        }

        val visible = raw.visibleItems.asSequence()
            .take(MAX_VISIBLE_ITEMS)
            .map {
                VisibleFoodItem(
                    name = sanitizeModelText(it.name, MAX_ITEM_NAME_CHARS),
                    amountInfo = sanitizeModelText(it.amountInfo, MAX_ITEM_AMOUNT_CHARS),
                )
            }
            .filter { it.name.isNotBlank() || it.amountInfo.isNotBlank() }
            .toList()

        val uncertain = raw.uncertainItems.asSequence()
            .take(MAX_UNCERTAIN_ITEMS)
            .map { sanitizeModelText(it, MAX_ITEM_NAME_CHARS) }
            .filter { it.isNotBlank() }
            .toList()

        val notes = raw.insulinRelevantNotes.asSequence()
            .take(MAX_INSULIN_NOTES)
            .map { sanitizeModelText(it, MAX_NOTE_CHARS) }
            .filter { it.isNotBlank() }
            .toList()

        var desc = sanitizeModelText(raw.description, MAX_DESCRIPTION_CHARS)
        if (desc.isBlank()) desc = "Unknown Food"

        val reasoning = sanitizeModelText(raw.reasoning, MAX_REASONING_CHARS)
        val recReason = sanitizeModelText(raw.recommendedCarbsReason, MAX_RECOMMENDED_REASON_CHARS)

        var needsManual = raw.needsManualConfirmation
        if (recCarbs <= 0.0 && carbs.estimate > 1.0) needsManual = true

        return EstimationResult(
            description = desc,
            visibleItems = visible,
            uncertainItems = uncertain,
            carbs = carbs,
            protein = protein,
            fat = fat,
            fpuEquivalent = fpu,
            glycemicIndex = raw.glycemicIndex,
            absorptionSpeed = raw.absorptionSpeed,
            confidence = raw.confidence,
            portionConfidence = raw.portionConfidence,
            hiddenCarbRisk = raw.hiddenCarbRisk,
            needsManualConfirmation = needsManual,
            insulinRelevantNotes = notes,
            reasoning = reasoning,
            recommendedCarbsForDose = recCarbs,
            recommendedCarbsReason = recReason,
        )
    }

    internal fun fixMacroRange(r: MacroRange): MacroRange {
        fun finite(v: Double, fallback: Double): Double =
            if (v.isFinite()) v else fallback

        var est = finite(r.estimate, 0.0).coerceIn(0.0, 500.0)
        var lo = finite(r.min, est).coerceIn(0.0, 500.0)
        var hi = finite(r.max, est).coerceIn(0.0, 500.0)
        lo = minOf(lo, est)
        hi = maxOf(hi, est)
        est = est.coerceIn(lo, hi)
        return MacroRange(estimate = est, min = lo, max = hi)
    }

    internal fun sanitizeModelText(input: String, maxLen: Int): String {
        if (input.isEmpty()) return ""
        val stripped = buildString(input.length) {
            for (ch in input) {
                val c = ch.code
                if (c in 32..0xD7FF || c in 0xE000..0xFFFD) append(ch)
                else if (c == '\n'.code || c == '\r'.code || c == '\t'.code) append(' ')
            }
        }.trim()
        return if (stripped.length <= maxLen) stripped else stripped.substring(0, maxLen).trimEnd()
    }
}
