package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

/**
 * Static metadata and policy hints for each [PhysiologicalPatternId].
 */
object PhysiologicalPatternCatalog {

    private val definitions: Map<PhysiologicalPatternId, PatternDefinition> = listOf(
        hormonalDef(
            PhysiologicalPatternId.DAWN_CORTISOL,
            smbCapFraction = 0.343,
        ),
        hormonalDef(
            PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
            smbCapFraction = 0.343,
        ),
        hormonalDef(
            PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
            smbCapFraction = 0.343,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
            category = PhysiologicalPatternCategory.ENDOCRINE,
            dominantScaleMinutes = 180,
            suppressMealInterpretation = true,
            suppressHyperRelease = true,
            suppressWaveletBoost = true,
            smbCapFraction = 0.187,
            mealLeafCredScale = 0.10,
            hyperLeafCredScale = 0.25,
            waveletCredScale = 0.15,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.NGR_NIGHT_GROWTH,
            category = PhysiologicalPatternCategory.ENDOCRINE,
            dominantScaleMinutes = 480,
            suppressHyperRelease = true,
            smbCapFraction = 0.281,
            hyperLeafCredScale = 0.55,
        ),
        // Meal patterns carry their own (generous) SMB cap so cap coverage stays continuous when a
        // meal classification displaces a capped pattern mid-rise (e.g. SEDENTARY_DAY flapping) —
        // otherwise patternCapU goes null and the HTR min-cap branch can be skipped entirely.
        PatternDefinition(
            id = PhysiologicalPatternId.MEAL_DECLARED,
            category = PhysiologicalPatternCategory.MEAL,
            dominantScaleMinutes = 60,
            smbCapFraction = 0.937,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            category = PhysiologicalPatternCategory.MEAL,
            dominantScaleMinutes = 60,
            smbCapFraction = 0.75,
            // Soft proposal: Harmonia may lift within maxSMBHB / hard envelope on confirmed rise.
            capKind = PatternCapKind.SOFT,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
            category = PhysiologicalPatternCategory.MEAL,
            dominantScaleMinutes = 15,
            smbCapFraction = 0.75,
            capKind = PatternCapKind.SOFT,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.MEAL_SECOND_WAVE,
            category = PhysiologicalPatternCategory.MEAL,
            dominantScaleMinutes = 60,
            smbCapFraction = 0.625,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.LATE_FAT_PROTEIN,
            category = PhysiologicalPatternCategory.MEAL,
            dominantScaleMinutes = 180,
            smbCapFraction = 0.531,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.STRESS_CORTISOL_ACUTE,
            category = PhysiologicalPatternCategory.STRESS_RECOVERY,
            dominantScaleMinutes = 60,
            suppressMealInterpretation = true,
            smbCapFraction = 0.468,
            mealLeafCredScale = 0.35,
            hyperLeafCredScale = 0.65,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.PSYCHOSOCIAL_STRESS,
            category = PhysiologicalPatternCategory.STRESS_RECOVERY,
            dominantScaleMinutes = 480,
            suppressMealInterpretation = true,
            suppressHyperRelease = true,
            smbCapFraction = 0.437,
            mealLeafCredScale = 0.30,
            hyperLeafCredScale = 0.50,
        ),
        recoveryDef(PhysiologicalPatternId.SLEEP_DEBT),
        recoveryDef(PhysiologicalPatternId.HRV_DEPRESSED),
        recoveryDef(PhysiologicalPatternId.RECOVERY_NEEDED),
        PatternDefinition(
            id = PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
            category = PhysiologicalPatternCategory.STRESS_RECOVERY,
            dominantScaleMinutes = 480,
            suppressMealInterpretation = true,
            suppressHyperRelease = true,
            suppressWaveletBoost = true,
            smbCapFraction = 0.312,
            mealLeafCredScale = 0.12,
            hyperLeafCredScale = 0.35,
            waveletCredScale = 0.15,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.INFECTION_RISK,
            category = PhysiologicalPatternCategory.STRESS_RECOVERY,
            dominantScaleMinutes = 480,
            suppressHyperRelease = true,
            smbCapFraction = 0.375,
            hyperLeafCredScale = 0.45,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.EXERCISE_ACUTE,
            category = PhysiologicalPatternCategory.ACTIVITY,
            dominantScaleMinutes = 15,
            suppressHyperRelease = true,
            smbCapFraction = 0.25,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.POST_EXERCISE_SENSITIVITY,
            category = PhysiologicalPatternCategory.ACTIVITY,
            dominantScaleMinutes = 180,
            suppressHyperRelease = true,
            smbCapFraction = 0.343,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.SEDENTARY_DAY,
            category = PhysiologicalPatternCategory.ACTIVITY,
            dominantScaleMinutes = 480,
            smbCapFraction = 0.562,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.EXERCISE_LOCKOUT,
            category = PhysiologicalPatternCategory.ACTIVITY,
            dominantScaleMinutes = 480,
            suppressHyperRelease = true,
            smbCapFraction = 0.218,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
            category = PhysiologicalPatternCategory.INSULIN_TRAJECTORY,
            dominantScaleMinutes = 60,
            suppressHyperRelease = true,
            smbCapFraction = 0.312,
            hyperLeafCredScale = 0.40,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.POST_HYPO_REBOUND,
            category = PhysiologicalPatternCategory.INSULIN_TRAJECTORY,
            dominantScaleMinutes = 15,
            suppressMealInterpretation = true,
            suppressHyperRelease = true,
            suppressWaveletBoost = true,
            smbCapFraction = 0.343,
            mealLeafCredScale = 0.15,
            hyperLeafCredScale = 0.35,
            waveletCredScale = 0.20,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.HYPER_INSTALLED,
            category = PhysiologicalPatternCategory.INSULIN_TRAJECTORY,
            dominantScaleMinutes = 180,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.COMPRESSION_ARTIFACT,
            category = PhysiologicalPatternCategory.INSULIN_TRAJECTORY,
            dominantScaleMinutes = 15,
            suppressHyperRelease = true,
            mealLeafCredScale = 0.20,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.CONTEXT_ILLNESS,
            category = PhysiologicalPatternCategory.CONTEXT,
            dominantScaleMinutes = 480,
            suppressHyperRelease = true,
            smbCapFraction = 0.406,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.CONTEXT_STRESS_INTENT,
            category = PhysiologicalPatternCategory.CONTEXT,
            dominantScaleMinutes = 480,
            suppressMealInterpretation = true,
            smbCapFraction = 0.437,
        ),
        PatternDefinition(
            id = PhysiologicalPatternId.CONTEXT_ACTIVITY_INTENT,
            category = PhysiologicalPatternCategory.ACTIVITY,
            dominantScaleMinutes = 480,
            suppressHyperRelease = true,
            smbCapFraction = 0.281,
        ),
    ).associateBy { it.id }

    val all: List<PatternDefinition> = definitions.values.toList()

    fun definitionOf(id: PhysiologicalPatternId): PatternDefinition =
        definitions[id] ?: error("Missing pattern definition for $id")

    fun categoryOf(id: PhysiologicalPatternId): PhysiologicalPatternCategory =
        definitionOf(id).category

    private fun hormonalDef(id: PhysiologicalPatternId, smbCapFraction: Double) = PatternDefinition(
        id = id,
        category = PhysiologicalPatternCategory.ENDOCRINE,
        dominantScaleMinutes = 480,
        suppressMealInterpretation = true,
        suppressHyperRelease = true,
        suppressWaveletBoost = true,
        smbCapFraction = smbCapFraction,
        mealLeafCredScale = 0.15,
        hyperLeafCredScale = 0.40,
        waveletCredScale = 0.20,
    )

    private fun recoveryDef(id: PhysiologicalPatternId) = PatternDefinition(
        id = id,
        category = PhysiologicalPatternCategory.STRESS_RECOVERY,
        dominantScaleMinutes = 480,
        suppressMealInterpretation = true,
        suppressHyperRelease = true,
        suppressWaveletBoost = true,
        smbCapFraction = 0.406,
        mealLeafCredScale = 0.20,
        hyperLeafCredScale = 0.45,
        waveletCredScale = 0.25,
    )
}
