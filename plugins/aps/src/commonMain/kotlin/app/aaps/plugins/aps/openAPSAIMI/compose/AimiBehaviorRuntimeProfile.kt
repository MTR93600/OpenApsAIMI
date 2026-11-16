package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisTuning

/**
 * How much production authority AIMI has been given, from Observation (nothing applied) up to
 * ControlledAuthority. It comes from the real Autodrive switches, see [autonomyModeFromAutodrive].
 */
enum class AimiAutonomyMode {
    Observation,
    Recommendations,
    AssistedApplication,
    ControlledAuthority,
}

internal fun AimiAutonomyMode.authorityRank(): Int =
    when (this) {
        AimiAutonomyMode.Observation -> 0
        AimiAutonomyMode.Recommendations -> 1
        AimiAutonomyMode.AssistedApplication -> 2
        AimiAutonomyMode.ControlledAuthority -> 3
    }

/**
 * The autonomy mode from the real Autodrive switches. Classic (V1/V2) Autodrive is removed, so
 * autonomy is a gradation of V3 production authority: Autodrive off gives Observation, the
 * authoritative or recursive belief authority switch gives ControlledAuthority, the hyper trajectory
 * release gives AssistedApplication, else Recommendations.
 *
 * Used by the profile dosing uses (`NeutralAimiBehaviorProfileSource`, since step T2, the same as
 * before step C1), by the TPO gate and by the AI coach notes.
 */
internal fun autonomyModeFromAutodrive(preferences: Preferences): AimiAutonomyMode {
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val hyperTrajectory = autoDriveActive && preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
    val authoritative = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)
    val recursiveAuthority = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
    return when {
        !autoDriveActive -> AimiAutonomyMode.Observation
        recursiveAuthority || authoritative -> AimiAutonomyMode.ControlledAuthority
        hyperTrajectory -> AimiAutonomyMode.AssistedApplication
        else -> AimiAutonomyMode.Recommendations
    }
}

data class AimiBehaviorRuntimeProfile(
    val protectionLevel: Int,
    val mealCaptureLevel: Int,
    val stabilityLevel: Int,
    val physioLevel: Int,
    val autonomyMode: AimiAutonomyMode,
) : UamHypothesisTuning {
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

    override fun mealSuppressionCap(): Double =
        when {
            cautiousFalseMealGuard -> 0.24
            assertiveMealAuthority -> 0.42
            else -> 0.32
        }

    override fun competingNonMealDominanceMargin(): Double =
        when {
            cautiousFalseMealGuard -> 0.06
            assertiveMealAuthority -> 0.14
            else -> 0.10
        }

    override fun competingNonMealConfidenceFloor(): Double =
        when {
            cautiousFalseMealGuard -> 0.52
            assertiveMealAuthority -> 0.68
            else -> 0.60
        }

    override fun suppressMealDecisionMargin(): Double =
        when {
            cautiousFalseMealGuard -> 0.04
            assertiveMealAuthority -> 0.12
            else -> 0.08
        }

    override fun suppressMealDecisionFloor(): Double =
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

    companion object {

        /**
         * The display-level part of the profile dosing uses (step C1 of the settings plan): the
         * balanced middle of each family (protection, meal capture and stability at level 2 of 0-4,
         * physio at level 1 of 0-2). Its autonomy mode is controlled authority; dosing replaces it
         * with the mode from the real Autodrive switches (`autonomyModeFromAutodrive`, step T2).
         *
         * With these values the PK/PD meal absorption, correction and stability factors are 1.0,
         * the ML correction limit multiplier is 1.0 (the same as no profile), and the meal
         * hypothesis uses the same margins as with no profile (0.32, 0.10, 0.60, 0.08, 0.60). The
         * PK/PD physio blend is 0.725: half of the physio signal is pulled toward neutral.
         *
         * Before C1 the levels were read from the settings. With default settings that gave
         * protection level 1, so for a default user C1 changes two values: the PK/PD correction
         * factor goes from 0.98 to 1.0 (the fused ISF is up to 2 % higher, a little less insulin),
         * and the ML correction limit goes from 0.88 to 1.0 of its base. The base is at most
         * 0.05 U (`AimiSmbTrainer.correctionClamp`), so the limit grows by at most about 0.006 U
         * per SMB, and only when an ML model is loaded.
         *
         * The autonomy mode is not a display level. Before C1 it came from the real Autodrive
         * switches; C1 fixed it at controlled authority, and step T2 brings the switches back (owner
         * decision): Autodrive off gives Observation (ML correction limit 0.55 of its base), on but
         * not authoritative gives Recommendations (0.72) or Assisted (0.88, hyper trajectory
         * release), authoritative gives 1.0. With the key defaults (all on) it stays controlled
         * authority, so a default user keeps 1.0. The 0.88 factor of the old default protection
         * level 1 does not come back, because the protection level stays neutral (so 0.55, not the
         * 0.484 of before C1). The owner decided to keep 0.55 / 0.72 / 0.88 / 1.0 (T2 follow-up,
         * docs/aimi-settings/PLAN.md): the difference to the old 0.484 / 0.634 is at most about
         * 0.003 U per SMB, and only with an ML model loaded.
         */
        val NEUTRAL: AimiBehaviorRuntimeProfile = AimiBehaviorRuntimeProfile(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.ControlledAuthority,
        )
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

