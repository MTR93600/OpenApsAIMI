package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

internal data class AimiBehaviorFamilyCoverage(
    val managedKeys: Set<String>,
    val expertKeys: Set<String> = emptySet(),
)

internal object AimiBehaviorFamilyRegistry {

    private val coverageByFamily: Map<AimiBehaviorFamilyId, AimiBehaviorFamilyCoverage> = mapOf(
        AimiBehaviorFamilyId.Protection to AimiBehaviorFamilyCoverage(
            managedKeys = setOf(
                DoubleKey.OApsAIMIMaxSMB.key,
                DoubleKey.OApsAIMIHighBGMaxSMB.key,
                DoubleKey.OApsAIMIPriorityMaxIobFactor.key,
                DoubleKey.OApsAIMIPriorityMaxIobExtraU.key,
                DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key,
                DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key,
            ),
            expertKeys = setOf(
                BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key,
                BooleanKey.OApsAIMIIobSurveillanceGuard.key,
            ),
        ),
        AimiBehaviorFamilyId.MealCapture to AimiBehaviorFamilyCoverage(
            managedKeys = setOf(
                BooleanKey.OApsAIMIHyperTrajectoryRelease.key,
                BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive.key,
                DoubleKey.autodriveMaxBasal.key,
                DoubleKey.meal_modes_MaxBasal.key,
                DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep.key,
                DoubleKey.OApsAIMIautodrivePrebolus.key,
                DoubleKey.OApsAIMIautodrivesmallPrebolus.key,
                DoubleKey.OApsAIMIHyperEstablishedDevMgdl.key,
                DoubleKey.OApsAIMIHyperDeepDevMgdl.key,
            ),
            expertKeys = setOf(
                DoubleKey.OApsAIMIBFFactor.key,
                DoubleKey.OApsAIMILunchFactor.key,
                DoubleKey.OApsAIMIDinnerFactor.key,
                DoubleKey.OApsAIMIHCFactor.key,
                DoubleKey.OApsAIMISnackFactor.key,
                DoubleKey.OApsAIMIMealFactor.key,
                DoubleKey.OApsAIMIBFPrebolus.key,
                DoubleKey.OApsAIMILunchPrebolus.key,
                DoubleKey.OApsAIMIDinnerPrebolus.key,
                DoubleKey.OApsAIMIHighCarbPrebolus.key,
                DoubleKey.OApsAIMIMealPrebolus.key,
                IntKey.OApsAIMIBFinterval.key,
                IntKey.OApsAIMILunchinterval.key,
                IntKey.OApsAIMIDinnerinterval.key,
                IntKey.OApsAIMIHCinterval.key,
                IntKey.OApsAIMImealinterval.key,
            ),
        ),
        AimiBehaviorFamilyId.Stability to AimiBehaviorFamilyCoverage(
            managedKeys = setOf(
                DoubleKey.OApsAIMISmbTailDamping.key,
                DoubleKey.OApsAIMISmbExerciseDamping.key,
                DoubleKey.OApsAIMISmbLateFatDamping.key,
                BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled.key,
                BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled.key,
                DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.key,
            ),
            expertKeys = setOf(
                DoubleKey.OApsAIMISmbTailThreshold.key,
                BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly.key,
                DoubleKey.OApsAIMIAdaptiveBasalMaxScaling.key,
                DoubleKey.OApsAIMIGovernanceHypoRateEnter.key,
                DoubleKey.OApsAIMIGovernanceHypoRateExit.key,
                DoubleKey.OApsAIMIGovernanceHypoBgMgdl.key,
                DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl.key,
                DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate.key,
                DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate.key,
                DoubleKey.OApsAIMIGovernanceHoldAggFloorRate.key,
                DoubleKey.OApsAIMIGovernanceHoldAggDecayRate.key,
                DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere.key,
                DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere.key,
                DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere.key,
                DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere.key,
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples.key,
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl.key,
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp.key,
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax.key,
                BooleanKey.OApsAIMITrajectoryGuardEnabled.key,
                BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled.key,
                DoubleKey.AimiTubeHypoFloorMgdl.key,
                DoubleKey.AimiTubeHyperBandMgdl.key,
                DoubleKey.AimiTubeAggressiveness.key,
                DoubleKey.AimiTubeBasalTrimMax.key,
                DoubleKey.AimiTubeKappaSafetyMargin.key,
            ),
        ),
        AimiBehaviorFamilyId.Physio to AimiBehaviorFamilyCoverage(
            managedKeys = setOf(
                BooleanKey.AimiPhysioAssistantEnable.key,
                BooleanKey.AimiPhysioSleepDataEnable.key,
                BooleanKey.AimiPhysioHRVDataEnable.key,
            ),
            expertKeys = setOf(
                BooleanKey.AimiPhysioLLMAnalysisEnable.key,
                StringKey.AimiPhysioLLMProvider.key,
                BooleanKey.OApsAIMIContextEnabled.key,
                BooleanKey.OApsAIMIContextLLMEnabled.key,
                AimiStringKey.ActivitySourceMode.key,
                AimiStringKey.OuraPersonalAccessToken.key,
            ),
        ),
        AimiBehaviorFamilyId.Autonomy to AimiBehaviorFamilyCoverage(
            managedKeys = setOf(
                BooleanKey.OApsAIMIautoDriveActive.key,
                BooleanKey.OApsAIMIautodriveAggressiveSmbFloor.key,
                BooleanKey.OApsAIMIRecursiveBeliefShadow.key,
                BooleanKey.OApsAIMIRecursiveBeliefAuthority.key,
                BooleanKey.OApsAIMIautoDriveAuthoritative.key,
            ),
            expertKeys = setOf(
                BooleanKey.OApsAIMIRecursiveBeliefWavelet.key,
                BooleanKey.OApsAIMIAutodriveV3EnhancedGater.key,
                BooleanKey.OApsAIMIMLtraining.key,
                BooleanKey.AimiAuditorEnabled.key,
                StringKey.AimiAuditorMode.key,
                IntKey.AimiAuditorMaxPerHour.key,
                IntKey.AimiAuditorTimeoutSeconds.key,
                IntKey.AimiAuditorMinConfidence.key,
            ),
        ),
    )

    private val familyByKey: Map<String, AimiBehaviorFamilyId> = buildMap {
        coverageByFamily.forEach { (family, coverage) ->
            coverage.managedKeys.forEach { putIfAbsent(it, family) }
            coverage.expertKeys.forEach { putIfAbsent(it, family) }
        }
    }

    fun managedCount(familyId: AimiBehaviorFamilyId): Int =
        coverageByFamily[familyId]?.managedKeys?.size ?: 0

    fun expertCount(familyId: AimiBehaviorFamilyId): Int =
        coverageByFamily[familyId]?.expertKeys?.size ?: 0

    fun totalManagedCount(): Int =
        coverageByFamily.values.sumOf { it.managedKeys.size }

    fun totalExpertCount(): Int =
        coverageByFamily.values.sumOf { it.expertKeys.size }

    fun familyForKey(key: PreferenceKey): AimiBehaviorFamilyId? =
        familyByKey[key.key]
}
