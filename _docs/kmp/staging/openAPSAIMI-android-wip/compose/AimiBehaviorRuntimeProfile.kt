package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.Preferences

internal data class AimiBehaviorRuntimeProfile(
    val protectionLevel: Int,
    val mealCaptureLevel: Int,
    val stabilityLevel: Int,
    val physioLevel: Int,
    val autonomyMode: AimiAutonomyMode,
) {
    val protectionBias: Double
        get() = protectionLevel.coerceIn(0, 4) / 4.0

    val mealAssertiveness: Double
        get() = mealCaptureLevel.coerceIn(0, 4) / 4.0

    val stabilityResponsiveness: Double
        get() = stabilityLevel.coerceIn(0, 4) / 4.0

    val physioInfluence: Double
        get() = physioLevel.coerceIn(0, 2) / 2.0

    val autonomyAuthority: Double
        get() = autonomyMode.authorityRank() / 3.0

    val autonomyLevel: Int
        get() = autonomyMode.authorityRank()

    val cautiousFalseMealGuard: Boolean
        get() = mealCaptureLevel <= 1 && physioLevel >= 1

    val assertiveMealAuthority: Boolean
        get() = mealCaptureLevel >= 3 && autonomyMode.authorityRank() >= AimiAutonomyMode.AssistedApplication.authorityRank()

    fun mealSuppressionCap(): Double =
        when {
            cautiousFalseMealGuard -> 0.24
            assertiveMealAuthority -> 0.42
            else -> 0.32
        }

    fun competingNonMealDominanceMargin(): Double =
        when {
            cautiousFalseMealGuard -> 0.06
            assertiveMealAuthority -> 0.14
            else -> 0.10
        }

    fun competingNonMealConfidenceFloor(): Double =
        when {
            cautiousFalseMealGuard -> 0.52
            assertiveMealAuthority -> 0.68
            else -> 0.60
        }

    fun suppressMealDecisionMargin(): Double =
        when {
            cautiousFalseMealGuard -> 0.04
            assertiveMealAuthority -> 0.12
            else -> 0.08
        }

    fun suppressMealDecisionFloor(): Double =
        when {
            cautiousFalseMealGuard -> 0.54
            assertiveMealAuthority -> 0.66
            else -> 0.60
        }

    fun pkpdPhysioBlendFraction(): Double = 0.45 + physioInfluence * 0.55

    fun pkpdMealAbsorptionFactor(mealModeActive: Boolean): Double =
        if (mealModeActive) {
            0.96 + mealAssertiveness * 0.08
        } else {
            1.0
        }

    fun pkpdCorrectionAggressionFactor(): Double = 0.96 + protectionBias * 0.08

    fun pkpdStabilityAggressionFactor(isRising: Boolean): Double =
        if (isRising) {
            0.97 + stabilityResponsiveness * 0.06
        } else {
            1.0
        }

    fun mlCorrectionFractionMultiplier(): Float {
        var multiplier = when (autonomyMode) {
            AimiAutonomyMode.Observation -> 0.55f
            AimiAutonomyMode.Recommendations -> 0.72f
            AimiAutonomyMode.AssistedApplication -> 0.88f
            AimiAutonomyMode.ControlledAuthority -> 1.0f
        }
        if (protectionLevel <= 1) multiplier *= 0.88f
        if (physioLevel >= 2) multiplier *= 0.90f
        if (assertiveMealAuthority) multiplier *= 1.05f
        return multiplier.coerceIn(0.45f, 1.0f)
    }
}

internal fun readAimiBehaviorRuntimeProfile(preferences: Preferences): AimiBehaviorRuntimeProfile {
    val draft = readAimiControlCenterDraft(preferences)
    return AimiBehaviorRuntimeProfile(
        protectionLevel = draft.protectionLevel,
        mealCaptureLevel = draft.mealCaptureLevel,
        stabilityLevel = draft.stabilityLevel,
        physioLevel = draft.physioLevel,
        autonomyMode = draft.autonomyMode,
    )
}

internal fun AimiAutonomyMode.authorityRank(): Int =
    when (this) {
        AimiAutonomyMode.Observation -> 0
        AimiAutonomyMode.Recommendations -> 1
        AimiAutonomyMode.AssistedApplication -> 2
        AimiAutonomyMode.ControlledAuthority -> 3
    }
