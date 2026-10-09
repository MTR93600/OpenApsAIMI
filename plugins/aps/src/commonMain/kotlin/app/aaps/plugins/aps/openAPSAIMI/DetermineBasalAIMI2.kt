package app.aaps.plugins.aps.openAPSAIMI

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.UE
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.effects.basalGovLine
import app.aaps.plugins.aps.openAPSAIMI.effects.learnerHealthLines
import app.aaps.plugins.aps.openAPSAIMI.effects.decideEffortSuppressesUndeclaredMeal
import app.aaps.plugins.aps.openAPSAIMI.effects.OptionalSignal
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRefreshEffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.effects.readRbtOptional
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEffectProbe
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEffectSink
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiLatestSmbCached
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSingleFlightCache
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSmbActionType
import app.aaps.plugins.aps.openAPSAIMI.effects.LegacyMealTickState
import app.aaps.plugins.aps.openAPSAIMI.effects.LegacyPrebolusMemory
import app.aaps.plugins.aps.openAPSAIMI.effects.applyLegacyMealModes as decideLegacyMealModes
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiNightGrowthConfig
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPhysioTick
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPostHypoClassification
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtTickWrites
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRecentGlucose
import app.aaps.plugins.aps.openAPSAIMI.effects.PostHypoState
import app.aaps.plugins.aps.openAPSAIMI.effects.RbtExtendedTickState
import app.aaps.plugins.aps.openAPSAIMI.effects.buildRbtExtendedSignals as decideRbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAggressiveRiseFloor
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAutodriveDebug
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAutodriveGater
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEstimatedRa
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAutodriveTickWrites
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBasalCap
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionLog
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDoseTerminal
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFclDeclared
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiHtrExport
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiHtrTerminals
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiHyperSeverity
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealAbsorption
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealSafety
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMinBgLookback
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPatientStateRefresh
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPhysioLatentUpdate
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPostHypoRecovery
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRaObservation
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtLive
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRiseFloorNote
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTdd24h
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiUamConfidence
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiV3SmbDelivery
import app.aaps.plugins.aps.openAPSAIMI.effects.AutodriveV3BranchResult
import app.aaps.plugins.aps.openAPSAIMI.effects.AutodriveV3TickState
import app.aaps.plugins.aps.openAPSAIMI.effects.RbtLiveCommitResult
import app.aaps.plugins.aps.openAPSAIMI.effects.runAutodriveV3MultiVariableBranch as decideAutodriveV3
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cAdaptiveFactor
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cHrSnapshot
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cLookbacks
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cTickTail
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeBindingDraft
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeClock
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeContextSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeCriticalFlag
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeDoseBg
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeLatch
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeMealCorrection
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeMealFlags
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizePhrases
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeRbtMinPred
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeRiseExport
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeSafety
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeSeal
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeSlowCarb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeSmbInterval
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeTdd
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeThrottle
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiFinalizeThyroid
import app.aaps.plugins.aps.openAPSAIMI.effects.decideFinalizeAndCapSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtDeltaPrev
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtDwell
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtExtendedBuild
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolveClock
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolveDoseBg
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolveFields
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolveLatent
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolvePhysio
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtResolveWrites
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtTrajectory
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtWCycleEnsure
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRecursiveBeliefResolve
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealHyperBasalBoostOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealHyperClock
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealHyperFields
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealHyperTempBasal
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMealHyperBasalBoost
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBadDayDeletion
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiCarbContextRead
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiCarbContextView
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiCachedSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiLegacyPrebolusDelivered
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMaxIobPhrase
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiNoteTags
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickClockState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickClockTirCarbGlucoseBootstrap
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTirWarmupRead
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTirWarmupView
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickSmbCache
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTickClockMaxSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAuthoritativeEventual
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAuthoritativeMinPred
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealCorrectionContext
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealCorrectionView
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMinPredWiring
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdAbsorptionGuard
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdGuardApply
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdGuardEndoRedCarpetSmbStage
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdGuardState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9ConsoleError
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9EarlyRuntime
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9G6Lead
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9G6Source
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9Inflammation
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9PhysioDetail
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9PhysioMultipliers
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9PhysioPkpdTubeBootstrap
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9Predictions
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9PumpAge
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT9State
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePkpdGuardEndoDampenRedCarpetAndCapSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.decideT9PhysioEarlyPkpd
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionBootstrapState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionContextFactory
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionLearnersHealth
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionLocalHour
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionRtBootstrap
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiDecisionStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtHtrMerge
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtRefineState
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDecisionContextInitRtSosAndFlatShadow
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBasalFirstAdaptiveState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBasalPaiState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSmoothBasalRate
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiUnifiedReactivityFactor
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealFirstNgrStage
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealFirstNgrState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealFirstTempBasal
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiNightGrowthEvaluate
import app.aaps.plugins.aps.openAPSAIMI.effects.decideBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf
import app.aaps.plugins.aps.openAPSAIMI.effects.decideBasalFirstAdaptiveMultiplier
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiInsulinReqFinalize
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiInsulinReqSmbInterval
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiInsulinReqState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMaxIobGateStage
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMaxIobGateState
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMaxIobTempBasal
import app.aaps.plugins.aps.openAPSAIMI.effects.decideInsulinReqActivityRelaxAndMicrobolus
import app.aaps.plugins.aps.openAPSAIMI.effects.tpoTickSmbCeiling
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSafetyGuardApply
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSafetyPrecautionsCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMaxIobExceededTempBasal
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMealFirst30NgrHeadroomBasalSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.decideSafetyPrecautions
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPredPipeCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSafetyHalt
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSafetyHaltCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideAdvancedPredictionsAndPredPipePrep
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePredPipelineSafetyHalt
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRefineRbtMergeAfterDoseSnapshot
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMealAdvisorOrReturn
import app.aaps.plugins.aps.openAPSAIMI.effects.decideT3cBrittleMode
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cBypassCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideT3cBrittleBypass
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiHarmoniaRampCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideHarmoniaProductionRamp
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBasalScheduleCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiHeartRateIsfCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideBasalSchedule
import app.aaps.plugins.aps.openAPSAIMI.effects.decideHeartRateIsf
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdCurveCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdTargetCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideComputePkpdPredictions
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePkpdPredictionsAndNoisyTargets
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSignalPrepPkpd
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSignalPrepPkpdCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideSignalPreparationPkpdRuntime
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTrajectoryCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSmbExecution
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSmbOneShotCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideSmbAdvisorOneShot
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtLiveTickCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRbtLiveTick
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdAbsorptionGuardCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdGuardLogChannel
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePkpdAbsorptionGuardOncePerTick
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRbtMergeCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRbtMerge
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiT3cBasalFirstCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideT3cBasalFirstProduction
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiCarbsAdvisorEnableSmbCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideCarbsAdvisorEnableSmbBasalHistoryAndSafety
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiUamPostHypoCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideUamPostHypoSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTherapyExerciseCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTherapyExerciseDecision
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTherapyExerciseLockout
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPublishDoseTerminalCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePublishDoseTerminalAuthorityAndSnapshot
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiBasalDecisionEngineCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideBasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMealOnsetBehindEffortVeto
import app.aaps.plugins.aps.openAPSAIMI.effects.decideEstimateUndeclaredVirtualCob
import app.aaps.plugins.aps.openAPSAIMI.effects.undeclaredVirtualCobInput
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPkpdSoftFloorWrite
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRecordPkpdSoftFloor
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiContextModuleCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPhysioLatentCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTubeAdvisorCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTubeDoseBaseline
import app.aaps.plugins.aps.openAPSAIMI.effects.decideApplyTubeAdvisorFromDoseSnapshot
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiAdvancedPredictionCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideApplyAdvancedPredictions
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiMealAbsorptionCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRefreshMealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPostHypoDriftCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decidePostHypoCompressionAndDriftTerminatorOrReturn
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEarlyTickCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideCalculateRate
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDriftTerminatorCondition
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEarlyTickOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPrefixAutodrive
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPrefixCombined
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPrefixGlucose
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPrefixIob
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPrefixStep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickPrefixCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickPrefixOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickPredPrep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickSignalCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickSignalData
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickSignalOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickSignalStep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiPostHypoClassified
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickPostHypoCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickPostHypoOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickTrajectoryPrep
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickPrefix
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickSignal
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickPostHypo
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickSchedule
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickMealNgr
import app.aaps.plugins.aps.openAPSAIMI.effects.decideDetermineBasalTickEngine
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickEngineCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealNgrCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealNgrOutcome
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealHyperStep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealBoost
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickCsf
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickCarbsGate
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickCarbsSafety
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealNgrStep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMealNgrContinue
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickMaxIobStep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTickScheduleCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiScheduleBootstrap
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiScheduleVitals
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSchedulePai
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSchedulePkpdTargets
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiScheduleSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiSchedulePkpdGuard
import app.aaps.plugins.aps.openAPSAIMI.effects.decideEarlyDetermineBasalStages
import app.aaps.plugins.aps.openAPSAIMI.effects.decideLateFatProteinRise
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTrajectoryContextPrepCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTrajectoryContextModuleTddIsfAndDynamicPbolusPrep
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiRaObservationCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideBuildRaObservationState
import app.aaps.plugins.aps.openAPSAIMI.effects.PatientRuntimeCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideRefreshPatientStateRuntime
import app.aaps.plugins.aps.openAPSAIMI.effects.decideUpdatePhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.effects.enrichPatientThermal
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEnableSmbCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideApplyContextModule
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTrajectorySpiralCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideEnableSmb
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTrajectoryTightSpiralSafetyBridge
import app.aaps.plugins.aps.openAPSAIMI.effects.mealPriorityAlignedForSpiralSmbCap
import app.aaps.plugins.aps.openAPSAIMI.effects.recordSmbActionType as recordSmbActionTypeOn
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalTerminalInvariants
import app.aaps.plugins.aps.openAPSAIMI.basal.AnticipationBasalFloor
import app.aaps.plugins.aps.openAPSAIMI.basal.FclMealBasal
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cAnticipation
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cAutodriveBasalBridge
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cTrajectoryContext
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalFirstPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.carbs.CarbsAdvisor
import app.aaps.plugins.aps.openAPSAIMI.ISF.HeartRateTrendIsf
import app.aaps.plugins.aps.openAPSAIMI.ISF.ObservedSensitivityMeter
import app.aaps.plugins.aps.openAPSAIMI.ISF.SensitivityRatioEstimator
import app.aaps.plugins.aps.openAPSAIMI.ISF.WorkingIsf
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaCounterfactual
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSafetyVerdict
import app.aaps.plugins.aps.openAPSAIMI.quality.InsulinOriginMeter
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendCap
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiCorpusPruner
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiTrainingCsvWriter
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbTrainingRowBuffer
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorIsfRaw
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorJsonlExport
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileFactorCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileFactorCodes
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileFactorGate
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileFactorRequest
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileTickState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorTickFact
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorTickRing
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.TickSafety
import app.aaps.plugins.aps.openAPSAIMI.smb.RiseCeilingGuard
import app.aaps.plugins.aps.openAPSAIMI.smb.MaxSmbLadder
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbIntervalPolicy
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefPredictionReasonSuffix
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.MealAggressionContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdBolusSample
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLogRow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.IsfTddProvider
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PredictionPhysioModulationResolver
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CausalKineticsModulator
import app.aaps.plugins.aps.openAPSAIMI.pkpd.DiaGovernor
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinKineticsAuthority
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdLearningDiagnostics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapSitePeakShift
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiIntelligenceSnapshot
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiIntelligenceSnapshotBuilder
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiAdaptationStatusBuilder
import app.aaps.plugins.aps.openAPSAIMI.orchestration.IntelligenceSnapshotJson
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshotBuilder
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityApplier
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityApplyResult
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiSmbComparison
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTpo
import app.aaps.plugins.aps.openAPSAIMI.ports.PkpdPort
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.prediction.NaiveEventualBgSignGuard
import app.aaps.plugins.aps.openAPSAIMI.prediction.PredictionSanityResult
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.quality.IobSurveillanceExport
import app.aaps.plugins.aps.openAPSAIMI.quality.ReplayQualityExportBuilder
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.recursive.BasalFirstChannel
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefEngine
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefPreferences
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefResolver
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefTickContext
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtChaosEvaluator
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtEpisodeMemory
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtResolutionBridge
import app.aaps.plugins.aps.openAPSAIMI.recursive.T3cBasalFirstResolution
import app.aaps.plugins.aps.openAPSAIMI.recursive.UnfoldExporter
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryMpcFeedForward
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleasePreferences
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoLgsBlockReason
import app.aaps.plugins.aps.openAPSAIMI.prediction.PredictionDivergenceAuditor
import app.aaps.plugins.aps.openAPSAIMI.prediction.sanitizePredictionValues
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelopeBuilder
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthorityResolver
import app.aaps.plugins.aps.openAPSAIMI.risk.MealConfirmedEarlyReleaseLatch
import app.aaps.plugins.aps.openAPSAIMI.risk.IobConsensus
import app.aaps.plugins.aps.openAPSAIMI.risk.IobDecisionSource
import app.aaps.plugins.aps.openAPSAIMI.risk.PredictionPathBounds
import app.aaps.plugins.aps.openAPSAIMI.risk.PredictionPathMath
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminals
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminalsResolver
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopPhase
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopTelemetry
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporterProvider
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonalScenarioTerminalCap
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorDecisionEventMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioPhaseFusion
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.EndogenousBasalBridgePolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalAdaptiveMultiplier
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.physio.CircadianMealProfileStore
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapHold
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternExport
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternInputBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.EffectiveIobReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionBasalCap
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoGuard
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiPostHypoClassifier
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiPostHypoState
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiLegacySmbCapMath
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiSmbFinalizeMath
import app.aaps.plugins.aps.openAPSAIMI.safety.signalEventualDrop
import app.aaps.plugins.aps.openAPSAIMI.safety.signalMinPredDrop
import app.aaps.plugins.aps.openAPSAIMI.safety.capSmbDose
import app.aaps.plugins.aps.openAPSAIMI.safety.clampSmbToMaxSmbAndMaxIob
import app.aaps.plugins.aps.openAPSAIMI.control.StraightLineTubeAdvisor
import app.aaps.plugins.aps.openAPSAIMI.safety.signalTrajectoryStack
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinLoadGovernor
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.safety.HyperInstalledDroppingExemption
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoEvaluator
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoInput
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionApplicator
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionContext
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionInput
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyStartResolution
import app.aaps.plugins.aps.openAPSAIMI.safety.resolveSafetyStart
import app.aaps.plugins.aps.openAPSAIMI.safety.CompressionReboundGuard
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoTools
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbDampingUsecase
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbInstructionExecutor
import app.aaps.plugins.aps.openAPSAIMI.smb.computeMealHighIobDecision
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiDetermineBasalTickOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopTickRecovery
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemoryCalculator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientRefreshSource
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateLoopCache
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientRuntimeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaHarmonizer
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaProductionDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaProductionMode
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaSensorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.patient.putFiniteOrNull
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEnvironment
import app.aaps.plugins.aps.openAPSAIMI.patient.AimiCascadeArbitrationArtifacts
import app.aaps.plugins.aps.openAPSAIMI.patient.BodyKineticsDigest
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.MealRiseGeometry
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.InsulinIntent
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefEngine
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleInfo
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmplitudeGovernor
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmpAxis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineApplicationMode
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineDosePathOwner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionProfiler
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdAbsorptionGuard
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.PhysiologicalStressMaskBuilder
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.collections.asSequence
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant as KotlinInstant
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate as KxLocalDate
import kotlinx.datetime.LocalTime as KxLocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiJson
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonArr
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonObj
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt



/**
 * When [TrajectoryType.TIGHT_SPIRAL] is active with high trajectory energy and IOB already elevated,
 * SMB caps must not stay at the high-BG ceiling ([DoubleKey.OApsAIMIHighBGMaxSMB]) — see
 * [applyTrajectoryTightSpiralStandardSmbCapIfNeeded].
 *
 * Seuils **TDD + poids** (énergie / IOB) : ancres adultes ~**7 / 8 U** à [TIGHT_SPIRAL_CAP_TDD_REFERENCE_U], bornés
 * pour pédiatrie / forts besoins.
 *
 * **Alignement produit** : relaxation du cap = même logique que [finalizeAndCapSMB] `mealPriorityContext`
 * (COB / UAM / **fenêtre repas horloge thérapie** pour la voie NGR / repas déclaré, **delta ou shortAvg**, IOB sous 75 % du maxIOB)
 * — cohérent avec [InsulinStackingStance] `meal_absorption_rise_priority` sans repas déclaré.
 * L’**état** NGR (moniteur nocturne) n’est pas ré‑évalué ici : une seule passe dans [runPostSafetyMealFirst30NgrHeadroomBasalSmbStage].
 *
 * **Cap gradué** : si le cap s’applique mais montée **aiguë** (mêmes seuils que la sortie « sharp rise »
 * stacking), [maxSMB] reste plafonné au standard et [maxSMBHB] conserve une **fraction** du headroom
 * high-BG (évite double pénalité basale relax + SMB au plancher).
 *
 * **Prédictions** : eventual aberrants → [SafetyNet.sanitizeEventualMgdlForSmbZones] et
 * [InsulinStackingStance.sanitizeEventualMgdlForStackingSignals].
 */
/**
 * Idle time after which the aggressive-rise SMB floor may serve a fresh prebolus, in ms.
 *
 * 90 minutes: long enough that a second absorption wave of the same meal does not re-arm the budget,
 * short enough that a genuinely new meal does.
 */
private const val RISE_FLOOR_REARM_MS = 90L * 60L * 1000L

private fun tightSpiralSmbCapEnergyThresholdU(tdd24hU: Double): Double =
    AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(tdd24hU)

private fun tightSpiralSmbCapIobThresholdU(tdd24hU: Double, patientWeightKg: Double): Double =
    AimiTickPolicyMath.tightSpiralSmbCapIobThresholdU(tdd24hU, patientWeightKg)

/** Bundles locals produced by [DetermineBasalaimiSMB2.runEarlyDetermineBasalStages]. */
private data class AimiDetermineBasalEarlyTickState(
    val originalProfile: OapsProfileAimi,
    val isExplicitAdvisorRun: Boolean,
    val tdd7P: Double,
    val tdd7Days: Double
)

/** IOB action profile scalars + PKPD insulin observer state after realtime physio hook. */
private data class AimiRealtimePhysioIobBootstrap(
    val iobTotal: Double,
    val iobPeakMinutes: Double,
    val iobActivityIn30Min: Double,
    val insulinActionState: InsulinActionState,
)

private sealed class AimiGlucosePackLoadOutcome {
    data class Abort(val returnValue: RT) : AimiGlucosePackLoadOutcome()
    data class Continue(
        val glucoseStatus: GlucoseStatusAIMI,
        val aimiBgFeatures: AimiBgFeatures?,
    ) : AimiGlucosePackLoadOutcome()
}

/** T9 / early PKPD / tube stage: pump age for downstream logic + physio multipliers still read later in the tick. */
/**
 * Après [applyAdvancedPredictions] : résultat [sanitizePredictionValues], min BG « composite » (BG / pred / eventual),
 * et seuil hypo LGS pour le tick. Réutilisé par [trySafetyStart], Autodrive V3/V2, et [runUamModelCalHypoGuardPostHypoAndSetPredictedSmb] (`minBgHypoComposite`).
 */
private data class AimiAdvancedPredictionsPredPipePrep(
    val sanity: PredictionSanityResult,
    val minBg: Double,
    val threshold: Double,
    val scenario: ScenarioProjectionPair,
)

/**
 * Après PKPD runtime + [applyBasalFirstPolicy] : trajectoire, pont TIGHT_SPIRAL, module contexte,
 * fusion TDD/ISF, puis microbolus dynamiques pour Autodrive. Ordre et effets de bord identiques à l’historique inline.
 */
private data class AimiTrajectoryContextIsfPrep(
    val sens: Double,
    val baseSensitivity: Double,
    val contextTargetOverride: Double?,
    val dynamicPbolusLarge: Double,
    val dynamicPbolusSmall: Double,
)

/** CGM trop vieux → sortie anticipée ; sinon données signal + PKPD pour la suite du tick. */
private sealed class AimiSignalPreparationPkpdOutcome {
    data class StaleAbort(val rT: RT) : AimiSignalPreparationPkpdOutcome()
    data class Continue(val data: AimiSignalPreparationPkpdContinue) : AimiSignalPreparationPkpdOutcome()
}

private data class AimiSignalPreparationPkpdContinue(
    val modesCondition: Boolean,
    val pbolusAS: Double,
    val pbolusA: Double,
    val reason: StringBuilder,
    val recentBGs: List<Float>,
    val totalBolusLastHour: Double,
    val autosensRatio: Double,
    val iob_data: IobTotal,
    val lastBolusTimeMs: Long?,
    val lateFatRiseFlag: Boolean,
    val tdd24Hrs: Float,
    val minAgo: Double,
    val windowSinceDoseInt: Int,
    val pkpdRuntime: PkPdRuntime?,
)

/**
 * Combined delta, BYODA short-average adjustment, and dynamic peak time for this tick.
 * Intentionally runs **before** chargement therapy / horloges repas et `applyLegacyMealModes` pour ne pas perturber bfast/lunch/dinner/snack/highcarb.
 */
private data class AimiCombinedDeltaAndPeakTick(
    val combinedDelta: Float,
    val shortAvgDeltaAdj: Float,
    val tp: Double,
)

/** BYODA G6 flag + Autodrive prefs for UI label — extracted so [determine_basal] stays a thin prelude before [runTickClockMaxSmbTirCarbAndGlucoseCopy]. */
private data class AimiPreTherapyAutodriveByodaBootstrap(
    val isG6Byoda: Boolean,
    val autodriveEnabled: Boolean,
    val isAutodriveV3: Boolean,
    val autodriveDisplay: String,
)

/**
 * Hour/minute context, SMB ceilings from prefs + slope/plateau logic, NGR config, TIR snapshot,
 * carb context, tags, and glucose deltas copied onto members. Runs **before** `Therapy` / meal mode clocks.
 */
/** [Continue] keeps the tick alive with [nightbis]; [ReturnEarly] is the same `return` as the historical inline branches. */
private sealed class AimiTherapyExerciseGate {
    data class Continue(
        val nightbis: Boolean,
    ) : AimiTherapyExerciseGate()

    data class ReturnEarly(
        val result: RT,
    ) : AimiTherapyExerciseGate()
}

/** After [runAdvancedPredictionsAndPredPipePrep]: [trySafetyStart] before Meal Advisor — roadmap invariant 5. */
private sealed class AimiPredPipelineSafetyGate {
    data object Continue : AimiPredPipelineSafetyGate()
    data class Halt(val rT: RT) : AimiPredPipelineSafetyGate()
}

/**
 * After therapy / exercise gate: manual meal-mode TBR cap, [applyLegacyMealModes], and [activeModeName] for [appendAutodriveStatusTirAndCompactPhysioSummaryToReason].
 * **Order:** before [runT3cBrittleBypassOrReturn] (roadmap invariant 4).
 */
private sealed class AimiManualMealModesGate {
    data class Continue(val activeModeName: String) : AimiManualMealModesGate()
    data class ReturnEarly(val rT: RT) : AimiManualMealModesGate()
}

/**
 * 🛰️ DetermineBasalaimiSMB2
 *
 * The primary medical orchestrator for the AIMI Advanced Hybrid Closed Loop (AHCL).
 * It coordinates insulin delivery decisions by balancing physiological predictions (PKPD),
 * learned user behavior (WCycle), and real-time safety constraints.
 *
 * ### Core Responsibilities:
 * 1. **Context Synthesis**: Aggregates glucose history, IOB, COB, and physiological stress (steps/HR).
 * 2. **PKPD Modeling**: Uses [AdvancedPredictionEngine] to forecast glucose trajectories.
 * 3. **Modular Decision Making**: Delegates to [AutodriveEngine] (V3) or [DynamicBasalController] (V2).
 * 4. **Safety Verification**: Enforces strict insulin ceilings via [trajectoryGuard] and [PkpdAbsorptionGuard].
 *
 * ### Medical Flow:
 * - **T3C (Temporary 3-hour Control)**: Logic for managing nocturnal stability.
 * - **Basal Pulse**: Proportional-Integral (PI) control for long-term drift.
 * - **SMB (Super Micro Bolus)**: Aggressive correction for acute hyperglycemia or meals.
 *
 * @property profileUtil Utility for accessing user insulin profiles.
 * @property preferences Access to user-defined settings and feature toggles.
 * @property wCycleFacade Entry point for hormonal cycle-aware adjustments.
 * @property autodriveEngine The next-generation MPC controller (iLet-like).
 */
@SingleIn(AppScope::class)
class DetermineBasalaimiSMB2 @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val preferences: Preferences,
    private val pkPdLearnedState: PkPdLearnedState,
    private val gestationalAutopilot: app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot,
    private val auditorOrchestrator: AimiAuditor,
    private val behaviorProfileSource: AimiBehaviorProfileSource,
    private val uiInteraction: UiInteraction,
    private val notificationManager: app.aaps.core.interfaces.notifications.NotificationManager,
    private val wCycleFacade: WCycleFacade,
    private val wCyclePreferences: WCyclePreferences,
    private val wCycleLearner: WCycleLearner,
    private val pumpCapabilityValidator: app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator,
    private val dynamicBasalController: app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController,
    private val autodriveEngine: AutodriveEngine,
    private val rh: TextResolver
) {
    /**
     * Causal state: hysteresis, holds, dwell timers, memories, learner state.
     * Persists between ticks. See [AimiCausalState].
     */
    private val causalState = AimiCausalState()

    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var aimiLogger: app.aaps.plugins.aps.openAPSAIMI.utils.AimiLogger
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var basalDecisionEngine: BasalDecisionEngine
    @Inject
    lateinit var autodriveGater: app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
    @Inject lateinit var activityManager: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityManager // Agnostic injection
    @Inject lateinit var glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi
    @Inject lateinit var comparator: AimiSmbComparison
    @Inject lateinit var emergencySos: AimiEmergencySos
    @Inject lateinit var basalLearner: app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
    @Inject lateinit var unifiedReactivityLearner: app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
    @Inject lateinit var basalNeuralLearner: app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
    @Inject lateinit var basalMlTrainingCoordinator: app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
    // The shared storage seam. Every file this tick names goes through it, so the tick holds no
    // `java.io.File` any more and the Android storage helper is reached only through this port.
    @Inject lateinit var storage: AimiStorage

    // Keeps the decisions journal under its hard cap between retention passes. See AimiAppendCap.
    @Inject lateinit var appendCap: AimiAppendCap

    // Study telemetry, as shared code sees it. The provider answers null when this platform has no
    // exporter, or when the Android one could not be built - see AndroidHormonitorStudyExporterProvider.
    @Inject lateinit var hormonitorStudyExporterProvider: HormonitorStudyExporterProvider

    // Helper to safely access learner (handles potential early access before injection)
    private val safeReactivityFactor: Double
        get() {
            // 🛡️ PHYSIO: Integration Refactored.
            // Aggressive boosts removed. All physio modulation is now handled
            // by AIMIInsulinDecisionAdapterMTR via getMultipliers().
            // We return only the Learner's factor here.
            return if (::unifiedReactivityLearner.isInitialized) unifiedReactivityLearner.getCombinedFactor(work.hourOfDay) else 1.0
        }
    @Inject lateinit var aapsLogger: AAPSLogger  // 📊 Logger for health monitoring

    @Inject lateinit var trajectoryGuard: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
    @Inject lateinit var trajectoryHistoryProvider: app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryHistoryProvider
    @Inject lateinit var contextManager: app.aaps.plugins.aps.openAPSAIMI.context.ContextManager  // 🎯 Context Module
    @Inject lateinit var contextInfluenceEngine: app.aaps.plugins.aps.openAPSAIMI.context.ContextInfluenceEngine  // 🎯 Context Influence
    @Inject lateinit var physioAdapter: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR  // 🏥 Physiological Modulation
    @Inject lateinit var straightLineTubeAdvisor: StraightLineTubeAdvisor  // 📐 MPC-lite hypo tube + SMB-cap smoothing
    /**
     * Passive reference instrument. It measures the sensitivity the outcomes imply and writes it to
     * `baseline_state` only. It is a plain private field on purpose: not @Inject, not @Singleton, so
     * nothing else can reach it. Any new call site is a bug. See `ObservedSensitivityMeter`.
     */
    private val observedSensitivityMeter = ObservedSensitivityMeter()

    /**
     * Passive reference instrument. It measures which share of the delivered insulin the model
     * really asked for, and which share the floors added under it, and writes the answer to
     * `adjustments.insulin_origin` only.
     *
     * **Only the export stage may reach it.** It is a plain private field on purpose: not @Inject,
     * not @Singleton, so nothing else can. Any other call site is a bug — it would mean a dose
     * depends on a passive instrument. See `InsulinOriginMeter`.
     */
    private val insulinOriginMeter = InsulinOriginMeter()

    @Inject lateinit var sensitivityRatioEstimator: SensitivityRatioEstimator
    @Inject lateinit var continuousStateEstimator: app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
    @Inject lateinit var tpoOrchestrator: AimiTpo

    // 🌸 Endometriosis Adjuster (Lazy init manually since not in graph yet or use manual passing)
    private val endoAdjuster by lazy { app.aaps.plugins.aps.openAPSAIMI.wcycle.EndometriosisAdjuster(preferences, aapsLogger) }

    // 🏥 Inflammation Adjuster (New Refactor - Decoupled from WCycle)
    private val inflammationAdjuster by lazy {
        app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster(wCyclePreferences)
    }

    // One read-ahead cache per slow read. Each one owns its value and its own in-flight guard; see
    // [AimiSingleFlightCache] for why the matching read never waits for the refresh it just started.
    private val pumpAgeDaysCache = AimiSingleFlightCache(0f)
    private val lastSmbCache = AimiSingleFlightCache<BS?>(null)
    private val tirWarmupCache = AimiSingleFlightCache<TirWarmupSnapshot?>(null)
    private val carbContextCache = AimiSingleFlightCache<CarbContextSnapshot?>(null)
    private val tdd2DaysCache = AimiSingleFlightCache<Float?>(null)
    private val tdd30DaysCache = AimiSingleFlightCache<Double?>(null)
    private val sensorInsertionMsCache = AimiSingleFlightCache<Long?>(null)
    @Volatile private var lastBasalLearnerHypoNotifyMs: Long = 0L
    @Volatile private var lastBasalLearnerHyperNotifyMs: Long = 0L
    private val stepsCache = AimiSingleFlightCache<List<SC>>(emptyList())
    private val heartRatesCache = AimiSingleFlightCache<List<HR>>(emptyList())
    private val tempBasalsCache = AimiSingleFlightCache<List<TB>>(emptyList())
    private val bolusCache = AimiSingleFlightCache<List<BS>>(emptyList())
    private val effectiveProfileCache = AimiSingleFlightCache<EffectiveProfile?>(null)
    private val trajectoryHistoryCache = AimiSingleFlightCache<List<PhaseSpaceState>>(emptyList())
    private val determineIoScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)

    /** Latest CGM noise from the current determine_basal invocation (for basal governance context). */
    private var lastLoopCgmNoise: Double = 0.0

    // 🦋 Thyroid (Basedow) Module
    private val thyroidPreferences by lazy { app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidPreferences(preferences) }
    private val thyroidStateEstimator = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidStateEstimator()
    private val thyroidEffectModel = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffectModel()
    private val thyroidSafetyGates = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidSafetyGates()

    // ❌ OLD reactivityLearner removed - UnifiedReactivityLearner is now the only one
    init {
        // Branche l’historique basal (TBR) sur la persistence réelle
        BasalHistoryUtils.installHistoryProvider(
            BasalHistoryUtils.FetcherProvider(
                fetcher = { fromMillis: Long ->
                    refreshTempBasalsAsync(fromMillis)
                    val raws: List<TB> = tempBasalsCache.get()

                    raws.asSequence()
                        .filter { it.timestamp > 0L && it.timestamp >= fromMillis }
                        .sortedByDescending { it.timestamp }
                        .toList()
                },
                // Optionnel : aligne "now" sur ton utilitaire de date
                nowProvider = { dateUtil.now() }
            )
        )
    }

    private val EPS_FALL = 0.3      // mg/dL/5min : seuil de baisse
    private val EPS_ACC  = 0.2      // mg/dL/5min : seuil d'écart short vs long

    private fun pumpAgeDaysCached(): Float {
        refreshPumpAgeAsync()
        return pumpAgeDaysCache.get()
    }

    private fun refreshPumpAgeAsync() {
        pumpAgeDaysCache.refresh(
            scope = determineIoScope,
            load = {
                val fromTime = aimiWallClockMs() - 7 * MILLIS_PER_DAY
                val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)
                if (siteChanges.isNotEmpty()) {
                    val latestChangeTimestamp = siteChanges.last().timestamp
                    ((aimiWallClockMs() - latestChangeTimestamp).toFloat() / (1000f * 60f * 60f * 24f))
                } else {
                    0f
                }
            },
            onFailure = { 0f },
        )
    }

    private fun latestSmbCached(): BS? {
        refreshLatestSmbAsync()
        return lastSmbCache.get()
    }

    private fun refreshLatestSmbAsync() {
        lastSmbCache.refresh(
            scope = determineIoScope,
            load = { persistenceLayer.getNewestBolusOfType(BS.Type.SMB) },
            onFailure = { null },
        )
    }

    private fun latestTirWarmupSnapshot(): TirWarmupSnapshot {
        refreshTirWarmupAsync()
        return tirWarmupCache.get() ?: TirWarmupSnapshot()
    }

    private fun refreshTirWarmupAsync() {
        tirWarmupCache.refresh(
            scope = determineIoScope,
            load = {
                val tir1Day = tirCalculator.calculate(1, 65.0, 180.0)
                determineBasalInvocationCaches.storeTir65180FromWarmup(tir1Day)
                TirWarmupSnapshot(
                    tir1DayAbove = tirCalculator.averageTIR(tir1Day).abovePct() ?: 0.0,
                    tir1DayInRange = tirCalculator.averageTIR(tir1Day).inRangePct() ?: 0.0,
                    currentTirLow = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0)).belowPct() ?: 0.0,
                    currentTirRange = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0)).inRangePct() ?: 0.0,
                    currentTirAbove = tirCalculator.averageTIR(tirCalculator.calculateDaily(65.0, 180.0)).abovePct() ?: 0.0,
                    lastHourTirLow = tirCalculator.averageTIR(tirCalculator.calculateHour(80.0, 140.0)).belowPct() ?: 0.0,
                    lastHourTirAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(72.0, 140.0)).abovePct(),
                    lastHourTirLow100 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 140.0)).belowPct() ?: 0.0,
                    lastHourTirAbove170 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 170.0)).abovePct() ?: 0.0,
                    lastHourTirAbove120 = tirCalculator.averageTIR(tirCalculator.calculateHour(100.0, 120.0)).abovePct() ?: 0.0,
                    tirBasal3InRange = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0)).inRangePct(),
                    tirBasal3Below = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0)).belowPct(),
                    tirBasal3Above = tirCalculator.averageTIR(tirCalculator.calculate(3, 65.0, 120.0)).abovePct(),
                    tirBasalHourAbove = tirCalculator.averageTIR(tirCalculator.calculateHour(65.0, 100.0)).abovePct(),
                )
            },
            onFailure = { TirWarmupSnapshot() },
        )
    }

    private fun latestCarbContextSnapshot(nowMs: Long, mealDataLastCarbTime: Long, cobNow: Float): CarbContextSnapshot {
        refreshCarbContextAsync(nowMs, mealDataLastCarbTime, cobNow)
        return carbContextCache.get() ?: CarbContextSnapshot(
            lastCarbTimestamp = mealDataLastCarbTime.takeIf { it > 0L } ?: nowMs - MILLIS_PER_DAY,
            lastCarbAgeMin = 0,
            futureCarbs = 0.0f,
            effectiveCob = cobNow,
            recentNotes = emptyList(),
        )
    }

    private fun refreshCarbContextAsync(nowMs: Long, mealDataLastCarbTime: Long, cobNow: Float) {
        carbContextCache.refresh(
            scope = determineIoScope,
            load = {
                var lastCarbTimestamp = mealDataLastCarbTime
                val oneDayAgoIfNotFound = nowMs - MILLIS_PER_DAY
                if (lastCarbTimestamp == 0L) {
                    lastCarbTimestamp = persistenceLayer.getMostRecentCarbByDate() ?: oneDayAgoIfNotFound
                }
                val ageMin = ((nowMs - lastCarbTimestamp) / (60 * 1000)).toInt()
                val future = persistenceLayer.getFutureCob().toFloat()
                val effectiveCob = if (ageMin < 15 && cobNow == 0.0f) {
                    persistenceLayer.getMostRecentCarbAmount()?.toFloat() ?: 0.0f
                } else {
                    cobNow
                }
                val recentNotesLocal = persistenceLayer.getUserEntryDataFromTime(nowMs - 4 * MILLIS_PER_HOUR)
                CarbContextSnapshot(
                    lastCarbTimestamp = lastCarbTimestamp,
                    lastCarbAgeMin = ageMin,
                    futureCarbs = future,
                    effectiveCob = effectiveCob,
                    recentNotes = recentNotesLocal,
                )
            },
            onFailure = {
                CarbContextSnapshot(
                    lastCarbTimestamp = mealDataLastCarbTime.takeIf { it > 0L } ?: nowMs - MILLIS_PER_DAY,
                    lastCarbAgeMin = 0,
                    futureCarbs = 0.0f,
                    effectiveCob = cobNow,
                    recentNotes = emptyList(),
                )
            },
        )
    }

    private data class TirWarmupSnapshot(
        val tir1DayAbove: Double = 0.0,
        val tir1DayInRange: Double = 0.0,
        val currentTirLow: Double = 0.0,
        val currentTirRange: Double = 0.0,
        val currentTirAbove: Double = 0.0,
        val lastHourTirLow: Double = 0.0,
        val lastHourTirAbove: Double? = null,
        val lastHourTirLow100: Double = 0.0,
        val lastHourTirAbove170: Double = 0.0,
        val lastHourTirAbove120: Double = 0.0,
        val tirBasal3InRange: Double? = null,
        val tirBasal3Below: Double? = null,
        val tirBasal3Above: Double? = null,
        val tirBasalHourAbove: Double? = null,
    )

    private data class CarbContextSnapshot(
        val lastCarbTimestamp: Long,
        val lastCarbAgeMin: Int,
        val futureCarbs: Float,
        val effectiveCob: Float,
        val recentNotes: List<UE>,
    )

    private fun tdd2DaysCached(tdd7P: Double): Float {
        refreshTdd2DaysAsync()
        val cached = tdd2DaysCache.get()
        if (cached == null || cached == 0.0f || cached < tdd7P.toFloat()) return tdd7P.toFloat()
        return cached
    }

    private fun refreshTdd2DaysAsync() {
        tdd2DaysCache.refresh(
            scope = determineIoScope,
            load = {
                tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))
                    ?.data?.totalAmount?.toFloat() ?: 0.0f
            },
            onFailure = { null },
        )
    }

    private fun resolveTdd30DaysForLearner(fallback7Day: Double): Double {
        refreshTdd30DaysAsync()
        return tdd30DaysCache.get()?.takeIf { it > 0.0 } ?: 0.0
    }

    private fun refreshTdd30DaysAsync() {
        tdd30DaysCache.refresh(
            scope = determineIoScope,
            load = {
                tddCalculator.averageTDD(tddCalculator.calculate(30, allowMissingDays = true))
                    ?.data?.totalAmount?.takeIf { it > 0.0 }
            },
            onFailure = { null },
        )
    }

    private fun resolveSensorInsertionMsCached(nowMs: Long): Long? {
        refreshSensorInsertionAsync(nowMs)
        return sensorInsertionMsCache.get()
    }

    private fun refreshSensorInsertionAsync(nowMs: Long) {
        sensorInsertionMsCache.refresh(
            scope = determineIoScope,
            load = {
                val fromTime = nowMs - 45L * 24L * 60L * 60L * 1000L
                val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.SENSOR_CHANGE, true)
                val latest = events.maxByOrNull { it.timestamp }?.timestamp
                latest?.takeIf { it > 0L }
            },
            onFailure = { null },
        )
    }

    private fun notifyBasalLearnerHypoIfNeeded(nowMs: Long) {
        val hypoFloor = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES)
        if (hypoFloor >= 70.0 && bg >= 70.0) return
        if (nowMs - lastBasalLearnerHypoNotifyMs < 30 * 60_000L) return
        lastBasalLearnerHypoNotifyMs = nowMs
        basalLearner.onHypoDetected()
    }

    /**
     * Pendant symétrique de [notifyBasalLearnerHypoIfNeeded] : une hyperglycémie qui dure doit elle aussi
     * être apprise, sinon le learner ne reçoit que des signaux à la baisse.
     * `onPersistentHyper` existait dans [BasalLearner] mais n'avait **aucun appelant** — l'événement hypo
     * se déclenchait, l'événement hyper jamais, ce qui rendait l'apprentissage structurellement asymétrique.
     *
     * Critère « persistant » : le **minimum** des 60 dernières minutes reste au-dessus de
     * [PERSISTENT_HYPER_BG_MGDL], donc la glycémie n'est pas redescendue une seule fois sous le seuil sur
     * la fenêtre — un pic transitoire ne suffit pas. Même cadence que le versant hypo (une fois / 30 min).
     *
     * La garde post-hypo est appliquée dans [BasalLearner.onPersistentHyper] : un rebond consécutif à une
     * hypo ne doit pas entraîner à la hausse, c'est précisément ce que la fenêtre d'exclusion écarte.
     */
    private fun notifyBasalLearnerPersistentHyperIfNeeded(nowMs: Long) {
        if (bg <= PERSISTENT_HYPER_BG_MGDL) return
        if (minBgInLastMinutes(PERSISTENT_HYPER_LOOKBACK_MINUTES) <= PERSISTENT_HYPER_BG_MGDL) return
        if (nowMs - lastBasalLearnerHyperNotifyMs < 30 * 60_000L) return
        lastBasalLearnerHyperNotifyMs = nowMs
        basalLearner.onPersistentHyper()
    }

    private fun stepsCountsCached(now: Long): List<SC> {
        refreshStepsAsync(now)
        return stepsCache.get()
    }

    private fun refreshStepsAsync(now: Long) {
        stepsCache.refresh(
            scope = determineIoScope,
            load = {
                val start = now - 210 * 60 * 1000
                persistenceLayer.getStepsCountFromTimeToTime(start, now)
            },
            onFailure = { emptyList() },
        )
    }

    private fun heartRatesCached(now: Long): List<HR> {
        refreshHeartRatesAsync(now)
        return heartRatesCache.get()
    }

    private fun refreshHeartRatesAsync(now: Long) {
        heartRatesCache.refresh(
            scope = determineIoScope,
            load = {
                val start = now - 200 * 60 * 1000
                persistenceLayer.getHeartRatesFromTimeToTime(start, now)
            },
            onFailure = { emptyList() },
        )
    }

    private fun refreshTempBasalsAsync(fromMillis: Long) {
        tempBasalsCache.refresh(
            scope = determineIoScope,
            load = { persistenceLayer.getTemporaryBasalsStartingFromTime(fromMillis, ascending = false) },
            onFailure = { emptyList() },
        )
    }

    private fun bolusesFromTimeCached(startTime: Long, ascending: Boolean): List<BS> {
        refreshBolusesAsync(startTime, ascending)
        val cached = bolusCache.get()
        return if (ascending) {
            cached.filter { it.timestamp >= startTime }.sortedBy { it.timestamp }
        } else {
            cached.filter { it.timestamp >= startTime }.sortedByDescending { it.timestamp }
        }
    }

    private fun refreshBolusesAsync(startTime: Long, ascending: Boolean) {
        bolusCache.refresh(
            scope = determineIoScope,
            load = { persistenceLayer.getBolusesFromTime(startTime, ascending) },
            onFailure = { emptyList() },
        )
    }

    private fun effectiveProfileCached(time: Long): EffectiveProfile? {
        refreshEffectiveProfileAsync(time)
        return effectiveProfileCache.get()
    }

    private fun refreshEffectiveProfileAsync(time: Long) {
        effectiveProfileCache.refresh(
            scope = determineIoScope,
            load = { profileFunction.getProfile(time) },
            onFailure = { null },
        )
    }

    private fun trajectoryHistoryCached(
        currentTime: Long,
        bg: Double,
        delta: Double,
        bgacc: Double,
        iobActivityNow: Double,
        iob: Float,
        insulinActionState: InsulinActionState,
        lastBolusAgeMinutes: Double,
        cob: Float,
        profile: OapsProfileAimi,
    ): List<PhaseSpaceState> {
        refreshTrajectoryHistoryAsync(
            currentTime = currentTime,
            bg = bg,
            delta = delta,
            bgacc = bgacc,
            iobActivityNow = iobActivityNow,
            iob = iob,
            insulinActionState = insulinActionState,
            lastBolusAgeMinutes = lastBolusAgeMinutes,
            cob = cob,
            profile = profile,
        )
        return trajectoryHistoryCache.get()
    }

    private fun refreshTrajectoryHistoryAsync(
        currentTime: Long,
        bg: Double,
        delta: Double,
        bgacc: Double,
        iobActivityNow: Double,
        iob: Float,
        insulinActionState: InsulinActionState,
        lastBolusAgeMinutes: Double,
        cob: Float,
        profile: OapsProfileAimi,
    ) {
        trajectoryHistoryCache.refresh(
            scope = determineIoScope,
            load = {
                // The reference reads the profile cache here and writes back whatever it had to read,
                // so the profile cache is warmed by this refresh as well. Kept as it is: the write
                // does not take the profile cache's own guard, so it can still race a profile refresh
                // that is running at the same time, exactly as before.
                val effectiveProfile = effectiveProfileCache.get() ?: profileFunction.getProfile(currentTime)
                effectiveProfileCache.set(effectiveProfile)
                trajectoryHistoryProvider.buildHistory(
                    nowMillis = currentTime,
                    historyMinutes = 90,
                    currentBg = bg,
                    currentDelta = delta,
                    currentAccel = bgacc,
                    insulinActivityNow = iobActivityNow,
                    iobNow = iob.toDouble(),
                    pkpdStage = insulinActionState.activityStage,
                    timeSinceLastBolus = if (lastBolusAgeMinutes.isFinite()) lastBolusAgeMinutes.toInt() else 120,
                    cobNow = cob.toDouble(),
                    effectiveProfile = effectiveProfile,
                    historicalInsulinPeakMinutes = profile.peakTime.toInt().coerceAtLeast(35),
                )
            },
            onFailure = { emptyList() },
        )
    }

    /**
     * Last value the `isLateFatProteinRise` predicate produced this tick, kept only so the export
     * can compare it with the shadow damping window. Never read by the dosing chain.
     */
    private var lateFatRiseFlagForExport: Boolean = false
    // — Hystérèse anti-pompage —
    private val HYPO_RELEASE_MARGIN   = 5.0      // mg/dL au-dessus du seuil
    private val HYPO_RELEASE_HOLD_MIN = 5        // minutes à rester > seuil+margin
    private val INSULIN_STEP = Constants.DEFAULT_INSULIN_STEP_U.toFloat()

    /** Suspend stats caches for one [determine_basal] pass; see [DetermineBasalInvocationCaches]. */
    private val determineBasalInvocationCaches = DetermineBasalInvocationCaches()
    private val bolusQueryCache = mutableMapOf<Pair<Long, Boolean>, List<BS>>()

    /**
     * Phase 2: early orchestration — cache lifecycle, telemetry pulse, meal hydration,
     * advisor/TDD bootstrap, profile snapshot, BOOTSTRAP phase marker.
     */
    private fun runEarlyDetermineBasalStages(ctx: AimiTickContext): AimiDetermineBasalEarlyTickState {
        val out = decideEarlyDetermineBasalStages(
            ctx = ctx,
            preferences = preferences,
            calls = object : AimiEarlyTickCalls {
                override fun beginInvocation() {
                    determineBasalInvocationCaches.beginInvocation()
                }
                override fun clearBolusCache() {
                    bolusQueryCache.clear()
                }
                override fun resetConsoles() {
                    work.consoleError = mutableListOf()
                    work.consoleLog = mutableListOf()
                }
                override fun loggerReady() = ::aapsLogger.isInitialized
                override fun recordLoopPulse(nowMs: Long) {
                    hormonitorStudyExporter?.recordLoopPulse(nowMs, AimiLoopTelemetry.activeTickId)
                }
                override fun logPulseFailed(typeName: String?, message: String?) {
                    work.consoleLog.add("Loop pulse failed ($typeName): $message — pulse skipped")
                }
                override fun resetEarlyScratch(ctx: AimiTickContext) {
                    work.exerciseInsulinLockoutActive = false
                    work.exerciseHyperBasalOverrideActive = false
                    work.aimiContextActivityActive = false
                    work.pkpdAbsorptionGuardAppliedThisTick = false
                    work.criticalSafetyZeroedThisTick = false
                    cachedRiskEnvelopeEarly = null
                    cachedRiskEnvelopeDecision = null
                    lastSafetyRiskExport = null
                    lastScenarioProjection = null
                    lastPredDivergenceExport = null
                    work.lastDecisionPredictionAuthority = null
                    lastIntelligenceSnapshot = null
                    work.lastPredictionAuthorityApplyResult = null
                    lastDoseTerminalSnapshot = null
                    lastPkpdSoftFloorTelemetry = null
                    work.tubeDoseBaseline = null
                    work.tubeAppliedFromDoseSnapshotThisTick = false
                    work.isConfirmedHighRiseThisTick = false
                    work.correctionAggressionDecision = null
                    work.mealAdvisorOneShotThisTick = false
                    work.lastTubeAdvisorSmbCapScale = null
                    lastTubeAdvisorTrace = null
                    work.lastInflammationResult = null
                    work.tickInsulinActionState = null
                    work.tickEffectiveDiaHours = ctx.effectiveDiaHours
                    work.tickEffectivePeakMinutes = ctx.effectivePeakMinutes
                    lastLoopCgmNoise = ctx.glucoseStatus.noise
                }
                override fun appendDebug(line: String) {
                    work.consoleLog.add(line)
                    work.consoleError.add(line)
                }
                override fun hydrate(mealData: MealData) {
                    hydrateMealDataIfTriggered(mealData)
                }
                override fun enterBootstrap() {
                    AimiLoopTelemetry.enterPhase(AimiLoopPhase.BOOTSTRAP, hormonitorStudyExporter)
                }
            },
        )
        return AimiDetermineBasalEarlyTickState(
            originalProfile = out.originalProfile,
            isExplicitAdvisorRun = out.isExplicitAdvisorRun,
            tdd7P = out.tdd7P,
            tdd7Days = out.tdd7Days,
        )
    }


    /**
     * Phase 2 (P2): gestational autopilot, early IOB / dura ISF / acceleration, harmonized basal multipliers,
     * confirmed high-rise flag, thyroid module — same order and side effects as inline sequence.
     */
    private fun bootstrapPhysiologyAfterEarlyTick(ctx: AimiTickContext, tdd7Days: Double): Boolean {
        applyGestationalAutopilot(ctx.profile)
        work.duraISFminutes = ctx.glucoseStatus.duraISFminutes
        work.duraISFaverage = ctx.glucoseStatus.duraISFaverage
        val iobObj = ctx.iobDataArray.firstOrNull() ?: IobTotal(ctx.currentTime)
        work.iobNet = iobObj.iob
        work.iob = iobObj.iob.toFloat() // 🛡️ Early Initialization for Safety Guards
        val accel = ctx.glucoseStatus.bgAcceleration ?: 0.0
        work.bgacc = accel
        val adaptiveBasalEnabled = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
        val hMultRaw = if (adaptiveBasalEnabled && tdd7Days.toFloat() != 0.0f) {
            basalLearner.getMultiplier()
        } else {
            1.0
        }
        val maxBasalMult = preferences.get(DoubleKey.OApsAIMIMaxMultiplier).coerceIn(1.0, 2.5)
        // H channel floor: 0.70, and on purpose NOT the 0.80 of the learned N channel
        // (BasalNeuralLearner.RUNTIME_BASAL_FLOOR). BasalAdaptiveMultiplier.combine keeps the smaller
        // channel as soon as either one is defensive, so an applied 0.70 can come from H alone. That is
        // why h_mult_raw / h_mult live next to n_raw / n_source in adjustments.adaptive_basal. Raising
        // this floor to 0.80 is a therapy decision that needs field evidence, so it stays at 0.70.
        val hMult = hMultRaw.coerceIn(0.70, maxBasalMult)
        val nDecision = if (adaptiveBasalEnabled) {
            basalNeuralLearner.getUniversalBasalDecision(
                bg = ctx.glucoseStatus.glucose,
                basal = ctx.profile.current_basal,
                accel = accel,
                duraMin = ctx.glucoseStatus.duraISFminutes,
                duraAvg = ctx.glucoseStatus.duraISFaverage,
                iob = iobObj.iob,
                physioFeatures = currentBasalPhysioFeatures()
            )
        } else null
        val nMult = nDecision?.multiplier ?: 1.0
        causalState.adaptiveMult = BasalAdaptiveMultiplier.combine(hMult, nMult)
        if (abs(causalState.adaptiveMult - 1.0) > 0.01) {
            work.consoleLog.add("🛡️ BASAL_UNIFIED_SCALING: H=${aimiFmt2(hMult)}x / N=${aimiFmt2(nMult)}x -> Applied=${aimiFmt2(causalState.adaptiveMult)}x")
        }
        lastAdaptiveBasalTrace = buildAdaptiveBasalTrace(hMultRaw, hMult, nDecision, causalState.adaptiveMult)
        val isConfirmedHighRiseLocal =
            ctx.glucoseStatus.glucose > 150.0 && ctx.glucoseStatus.combinedDelta > 1.5 && (ctx.glucoseStatus.bgAcceleration ?: 0.0) > 0.4
        applyThyroidModule(ctx.profile)
        return isConfirmedHighRiseLocal
    }

    /**
     * Builds the `adjustments.adaptive_basal` block of AIMI_Decisions.jsonl.
     *
     * `n_raw` is the point of this block: it is the learned value BEFORE the runtime clamp. A model
     * that returns one constant and a model that really learned that number produce the same `n_mult`,
     * and the JSONL used to carry only the blended result, as free text inside `outcome.narrative`.
     *
     * The two channels do not share a floor: N clamps at 0.80
     * (`BasalNeuralLearner.RUNTIME_BASAL_FLOOR`) while H still clamps at 0.70, and the blend keeps the
     * smaller one when either is defensive. So `h_mult_raw` / `h_mult` and `n_raw` / `n_source` together
     * are what tells a saturated clamp from a model that really learned that value — the question that
     * two field reports of "exactly 0.70 on every tick", 40 days apart, could not answer.
     */
    private fun buildAdaptiveBasalTrace(
        hMultRaw: Double,
        hMult: Double,
        nDecision: BasalNeuralLearner.UniversalBasalDecision?,
        combined: Double,
    ): JsonObject = JsonObj().apply {
        put("h_mult_raw", hMultRaw)
        put("h_mult", hMult)
        put("n_mult", nDecision?.multiplier ?: 1.0)
        put("n_raw", nDecision?.rawValue ?: AimiJson.NULL)
        put("n_source", (nDecision?.source ?: BasalNeuralLearner.BasalMultiplierSource.DISABLED).name.lowercase())
        put("n_clamped", nDecision?.clamped ?: false)
        put("n_floor", nDecision?.floor ?: AimiJson.NULL)
        put("n_ceiling", nDecision?.ceiling ?: AimiJson.NULL)
        put("combined", combined)
        val gov = basalNeuralLearner.getGovernanceSnapshot()
        put("governance_action", gov.action.name)
        put("governance_basal_floor", gov.activeBasalFloor ?: AimiJson.NULL)
    }.build()

    /**
     * Phase 2 (P2): [AimiDecisionContext], initial [RT], CONTEXT telemetry phase, SOS evaluation,
     * learner health log, WCycle / lastProfile reset, flat-BG shadow from delta override.
     */
    private fun buildDecisionContextInitRtSosAndFlatShadow(ctx: AimiTickContext): AimiDecisionRtBootstrap<AimiDecisionContext> =
        decideDecisionContextInitRtSosAndFlatShadow(
            ctx = ctx,
            preferences = preferences,
            consoleLog = work.consoleLog,
            consoleError = work.consoleError,
            aapsLogger = aapsLogger,
            nowMs = dateUtil.now(),
            hour = AimiDecisionLocalHour { aimiLocalHour() },
            state = object : AimiDecisionBootstrapState<AimiDecisionContext> {
                override fun resetPerTickShadow(nowMs: Long) = resetDecisionShadow(nowMs)
                override fun lastBgRiseFastNightMs() = this@DetermineBasalaimiSMB2.causalState.lastBgRiseFastNightMs
                override fun setLastBgRiseFastNightMs(epochMs: Long) {
                    causalState.lastBgRiseFastNightMs = epochMs
                }
                override fun rememberAuditor(state: app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorProfileTickState) {
                    auditorProfileTick = state
                }
                override fun forgetWCycle() {
                    work.wCycleInfoForRun = null
                    work.wCycleReasonLogged = false
                    causalState.lastWCycleBelief = null
                }
                override fun rememberProfile(profile: OapsProfileAimi) {
                    lastProfile = profile
                }
                override fun rememberPending(decisionCtx: AimiDecisionContext) {
                    work.pendingDecisionCtxForExport = decisionCtx
                }
            },
            contexts = AimiDecisionContextFactory { trigger, eventId ->
                decisionContextForTrigger(ctx, trigger, eventId)
            },
            sos = emergencySos,
            learners = AimiDecisionLearnersHealth { logLearnersHealth(it) },
            study = AimiDecisionStudyExporter { hormonitorStudyExporter },
        )

    private fun resetDecisionShadow(nowMs: Long) {
        lastIobSurveillanceExport = null
        lastIobReleaseExport = null
        work.tickIobEffectiveU = null
        causalState.lastPostHypoOrdinal = 0
        // Reset SMB trace each tick so a basal-only T3C row can't carry stale SMB values from a prior SMB tick
        // (replay_quality export). The standard SMB pipeline overwrites these before use.
        work.lastSmbProposed = 0.0
        work.lastSmbCapped = 0.0
        work.lastSmbFinal = 0.0
        work.lastSmbBindingTraceDraft = SmbBindingTrace.Draft(timestampMs = nowMs)
        // The maxSMB ladder runs later in the tick, and a tick can abort before it. Without this
        // reset the export would stamp the previous tick's branch tag and slope onto an otherwise
        // empty trace, and a reader could not tell. Null means "the ladder did not run this tick".
        lastMaxSmbLadderBranch = null
        work.lastSlopeFromMinDeviation = null
        lastShortAvgDeltaAtLadder = null
        // Effort reduction telemetry is per tick — a basal-only tick must export null, not the last
        // SMB tick's multiplier.
        work.lastEffortSmbFactorRaw = null
        // What the stress floor did belongs to this tick only. A tick that returns before the working
        // sensitivity is finalised must export nothing rather than the previous tick's numbers.
        WorkingIsf.resetLastApplied()
        work.lastEffortSmbFactorApplied = null
        work.lastEffortSmbBeforeU = null
        work.lastEffortSmbAfterU = null
        work.lastNgrBasalMultiplier = 1.0
        work.lastHyperTrajectoryRelease = null
        work.lastRecursiveBeliefSnapshot = null
        work.lastRecursiveAuthorityGateDecision = null
        work.lastRbtChaosEvaluation = null
        work.lastRbtAppliedHints = null
        work.lastT3cHistoricalBypassNeutralizedThisTick = false
        work.lastT3cRuntimeOwnership = null
        work.rbtResolvedThisTick = false
        work.lastRbtLiveCommitResult = null
        work.lastLoadGovernorMultiplierG = 1.0
        work.lastPhysiologicalPhaseOutput = null
        work.lastPhysiologicalPatternSnapshot = null
        AimiCascadeArbitrationArtifacts.clear()
        work.lastMealAbsorptionOutput = null
        causalState.lastPhysioLatentState = null
        work.lastEffortAssessment = null // per-tick computed; memory (causalState.lastEffortMemory) persists across ticks
        causalState.lastUamHypothesisState = null
        work.lastContextSnapshot = null
        work.lastPatientState = null
        work.lastPatientModeDecision = null
        work.lastPhysiologicalTreeSnapshot = null
        work.lastHarmoniaDecision = null
        work.lastMealCertainty = null
        work.lastHarmonizerOutcome = null
        work.lastHarmoniaProductionDecision = null
        lastAuditorTickDisposition = null
        lastAuditorLoopSnapshot = null
        work.currentTickDecisionEventId = null
        lastAuditorAuditStartedAtMs = 0L
        work.lastPostHypoDeliveryAuthority = PostHypoDeliveryAuthority.INACTIVE
        work.lastPostHypoSmbBeforeCapU = null
        work.lastPostHypoSmbAfterCapU = null
        // Cross-tick hysteresis (InsulinSlope / Endogenous) must NOT reset here — that zeroed
        // holdTicks every loop and made 15–20 min holds dead on arrival. reset() belongs in
        // tests / plugin restart only.
        work.pendingTrajSpiralBasal = null
        // 🔭 Lot 0 — l'export JSONL doit avoir lieu sur TOUS les chemins de sortie du tick, pas seulement
        // sur les deux qui appellent explicitement le stage. On repart d'un état non exporté à chaque tick.
        work.aimiDecisionExportedThisTick = false
        work.pendingDecisionCtxForExport = null
    }

    private fun decisionContextForTrigger(
        ctx: AimiTickContext,
        trigger: String,
        eventId: String,
    ): AimiDecisionContext {
        work.currentTickDecisionEventId = eventId
        return decideAimiDecisionContext(
            eventId = eventId,
            timestamp = ctx.currentTime,
            trigger = trigger,
            profileIsfMgdl = ctx.profile.sens,
            profileBasalUph = ctx.profile.current_basal,
            currentBgMgdl = ctx.glucoseStatus.glucose,
            cobG = ctx.mealData.mealCOB,
            iobU = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            estimatedRaMgdlPerMin = runCatching { continuousStateEstimator.getLastRa() }.getOrNull(),
            sensitivityRatioR = runCatching { sensitivityRatioEstimator.ratio }.getOrNull(),
            isfShadowSMgdl = runCatching {
                IsfSourceTelemetry.lastProfileStaticMgdl?.let { sensitivityRatioEstimator.sensitivityMgdl(it) }
            }.getOrNull(),
            sensitivityObservations = runCatching { sensitivityRatioEstimator.observationCount }.getOrNull(),
        )
    }


    /**
     * Realtime steps/HR log, maxSMB reset, physio snapshot → [decisionCtx] physio branch,
     * [InsulinActionProfiler], class [iobActivityNow], [insulinObserver] update + log.
     */
    private fun runRealtimePhysioIobProfilerAndInsulinObserver(
        ctx: AimiTickContext,
        decisionCtx: AimiDecisionContext,
    ): AimiRealtimePhysioIobBootstrap {
        val rtActivity = physioAdapter.getRealTimeActivity()
        work.consoleLog.add("PHYSIO_RT Steps=${rtActivity.stepsToday} HR=${rtActivity.heartRate}bpm")
        applyTpoTickSmbCeiling(dateUtil.now())
        val physioSnapshot = physioAdapter.getLatestSnapshot()
        val snsDominance = physioSnapshot.toSNSDominance()
        decisionCtx.adjustments.physiological_context = AimiDecisionContext.PhysioContext(
            hormonal_cycle_phase = work.wCycleInfoForRun?.let { "${it.phase.name}_Day${it.dayInCycle}" } ?: "Unknown",
            physical_activity_mode = if (snsDominance > 0.6) "Stress/Activity" else "Resting"
        )
        val iobArrayForProfiler = if (preferences.get(BooleanKey.OApsAIMIIntelligenceKineticsProfiler)) {
            ctx.pkpdIobDataArray ?: ctx.iobDataArray
        } else {
            ctx.iobDataArray
        }
        // Effective-IOB "now" (learned kinetics) for the maxIOB release gate — index 0 is the current tick.
        work.tickIobEffectiveU = ctx.pkpdIobDataArray?.firstOrNull()?.iob
        val iobActionProfile = InsulinActionProfiler.calculate(iobArrayForProfiler, ctx.profile, snsDominance)
        val iobTotal = iobActionProfile.iobTotal
        val iobPeakMinutes = iobActionProfile.peakMinutes
        work.iobActivityNow = iobActionProfile.activityNow
        val iobActivityIn30Min = iobActionProfile.activityIn30Min
        work.consoleLog.add(
            "PAI: Peak in ${aimiFmt0(iobPeakMinutes)}m | " +
                "Activity Now=${aimiFmt0(work.iobActivityNow * 100)}%, " +
                "in 30m=${aimiFmt0(iobActivityIn30Min * 100)}%"
        )
        val observerDiaHours = ctx.effectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 } ?: ctx.profile.dia
        // Wave2 F1: minutes-to-peak from profiler (signed); never absolute effective peak.
        val minutesToPeak = iobPeakMinutes.toInt()
        val insulinActionState = insulinObserver.update(
            currentBg = bg,
            bgDelta = delta.toDouble(),
            iobTotal = iobTotal,
            iobActivityNow = work.iobActivityNow,
            iobActivityIn30 = iobActivityIn30Min,
            minutesToPeak = minutesToPeak,
            diaHours = observerDiaHours,
            carbsActiveG = work.cob.toDouble(),
            now = dateUtil.now()
        )
        work.consoleLog.add("PKPD_OBS ${insulinActionState.reason}")
        work.tickInsulinActionState = insulinActionState
        return AimiRealtimePhysioIobBootstrap(
            iobTotal = iobTotal,
            iobPeakMinutes = iobPeakMinutes,
            iobActivityIn30Min = iobActivityIn30Min,
            insulinActionState = insulinActionState
        )
    }

    /**
     * WCycle CSV hook, [glucoseStatusCalculatorAimi] pack, [ensurePredictionFallback] on success;
     * on missing GS returns [AimiGlucosePackLoadOutcome.Abort] with the same [RT] side effects as before.
     */
    private fun ensureWCycleAndLoadGlucoseStatusOrAbort(
        ctx: AimiTickContext,
        rT: RT,
    ): AimiGlucosePackLoadOutcome {
        ensureWCycleInfo()
        val pack = try {
            glucoseStatusCalculatorAimi.compute(true)
        } catch (e: Exception) {
            work.consoleError.add("❌ GlucoseStatusCalculatorAimi.compute() failed: ${e.message}")
            null
        }
        if (pack == null || pack.gs == null) {
            work.consoleError.add("❌ No glucose data (AIMI pack empty)")
            return AimiGlucosePackLoadOutcome.Abort(
                rT.also {
                    it.reason.append("no GS")
                    ensurePredictionFallback(it, bg)
                    markFinalLoopDecisionFromRT(it)
                }
            )
        }
        val gs = pack.gs!!
        val f = pack.features
        val glucoseStatus = when {
            ctx.glucoseStatus == null -> GlucoseStatusAIMI(
                glucose = gs.glucose,
                noise = gs.noise,
                delta = gs.delta,
                shortAvgDelta = gs.shortAvgDelta,
                longAvgDelta = gs.longAvgDelta,
                date = gs.date,
                duraISFminutes = f?.stable5pctMinutes ?: 0.0,
                duraISFaverage = f?.stable5pctAverage ?: 0.0,
                parabolaMinutes = f?.parabolaMinutes ?: 0.0,
                deltaPl = f?.delta5Prev ?: 0.0,
                deltaPn = f?.delta5Next ?: 0.0,
                bgAcceleration = f?.accel ?: 0.0,
                a0 = f?.a0 ?: 0.0,
                a1 = f?.a1 ?: 0.0,
                a2 = f?.a2 ?: 0.0,
                corrSqu = f?.corrR2 ?: 0.0,
                sourceSensor = gs.sourceSensor
            )
            // Enrich if caller omitted sensor but calculator pack has it (One+ / G7 / G6)
            ctx.glucoseStatus.sourceSensor == null && gs.sourceSensor != null ->
                ctx.glucoseStatus.copy(sourceSensor = gs.sourceSensor)
            else -> ctx.glucoseStatus
        }
        ensurePredictionFallback(rT, glucoseStatus.glucose)
        return AimiGlucosePackLoadOutcome.Continue(glucoseStatus, f)
    }

    /**
     * T9 G6 lead log, physio multipliers + trace, early PKPD + [cachedPkpdRuntime], physio/inflammation
     * mutations, TAP-G peak governor echo (prefs).
     * Same effect order as historical inline block. **Not** BYODA combinedΔ — that is [runCombinedDeltaByodaAndDynamicPeak].
     *
     * This function does **not** run the straight-line tube advisor, and the `effectiveDiaH` local it
     * computes reaches nothing: it is written and never read. The tube runs once per tick from
     * `publishDoseTerminalAuthorityAndSnapshot`, after the gated dose terminals exist, and it takes
     * its DIA from the `tickEffectiveDiaHours` member, not from that local. See the note above the
     * return statement below.
     *
     * @return Pump age from [pumpAgeDaysCached] and [physioMultipliers] for the rest of the tick (trajectory / caps).
     */
    private fun runT9PhysioEarlyPkpdAndTubeBootstrap(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        rT: RT,
        iobTotal: Double,
    ): AimiT9PhysioPkpdTubeBootstrap = decideT9PhysioEarlyPkpd(
        profile = ctx.profile,
        glucoseStatus = glucoseStatus,
        rT = rT,
        iobTotal = iobTotal,
        preferences = preferences,
        consoleLog = work.consoleLog,
        consoleError = AimiT9ConsoleError { work.consoleError.add(it) },
        state = object : AimiT9State {
            override fun setBaseMultipliers(value: PhysioMultipliersMTR) { work.lastBasePhysioMultipliers = value }
            override fun setPkpdRuntime(value: PkPdRuntime?) { cachedPkpdRuntime = value }
            override fun pkpdRuntime() = cachedPkpdRuntime
            override fun setEventualBg(value: Double) { work.eventualBG = value }
            override fun setPredictedBg(value: Float) { predictedBg = value }
            override fun setVariableSensitivity(value: Float) { variableSensitivity = value }
            override fun maxSmb() = work.maxSMB
            override fun setMaxSmb(value: Double) { work.maxSMB = value }
            override fun maxSmbHb() = work.maxSMBHB
            override fun setMaxSmbHb(value: Double) { work.maxSMBHB = value }
            override fun setInflammation(result: app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster.InflammationResult) {
                work.lastInflammationResult = result
            }
            override fun causalPosterior() = work.lastPatientState?.causalPosterior
        },
        g6 = AimiT9G6Source {
            val name = activePlugin.activeBgSource::class.simpleName ?: ""
            name.contains("Dexcom", ignoreCase = true) && name.contains("G6", ignoreCase = true)
        },
        lead = AimiT9G6Lead { raw, isG6 -> continuousStateEstimator.applyG6LeadCompensation(raw, isG6) },
        physioMultipliers = AimiT9PhysioMultipliers { bg, delta ->
            try {
                physioAdapter.getMultipliers(currentBG = bg, currentDelta = delta)
            } catch (e: Exception) {
                aapsLogger.error(app.aaps.core.interfaces.logging.LTag.APS, "Physio adapter error - using defaults", e)
                PhysioMultipliersMTR.NEUTRAL
            }
        },
        physioDetail = AimiT9PhysioDetail {
            try {
                val physioLog = physioAdapter.getDetailedLogString()
                work.consoleError.add(physioLog)
                physioAdapter.getLastDecisionTrace()?.let { trace ->
                    work.consoleLog.add(
                        "PHYSIO_TRACE state=${trace.physioState} conf=${aimiFmt2(trace.physioConfidence)} " +
                            "q=${aimiFmt2(trace.physioDataQuality)} " +
                            "isf=${aimiFmt3(trace.isfFactor)} basal=${aimiFmt3(trace.basalFactor)} " +
                            "smb=${aimiFmt3(trace.smbFactor)} " +
                            "inflam=${aimiFmt3(trace.inflammationLatentIndex)}(${trace.inflammationTimescale}) " +
                            "shadow(smb=${aimiFmt3(trace.shadowBudgetedSmbFactor)} ov=${aimiFmt3(trace.shadowOverlapPenalty)}) " +
                            "veto=${trace.vetoReason ?: "none"} " +
                            "loop=${trace.finalLoopDecisionType ?: "pending"}"
                    )
                }
            } catch (e: Exception) {
                work.consoleError.add("❌ Physio Log Error: ${e.message}")
            }
        },
        runtime = AimiT9EarlyRuntime { earlySens, totalIob, allowLearning ->
            val iobForEarlyPkpd = ctx.iobDataArray.firstOrNull()
            val window = if (iobForEarlyPkpd != null && iobForEarlyPkpd.lastBolusTime > 0L) {
                ((dateUtil.now() - iobForEarlyPkpd.lastBolusTime) / 60000.0).toInt().coerceAtLeast(0)
            } else {
                90
            }
            val mealContext = buildPkpdMealContext(
                mealData = ctx.mealData,
                predictedBgMgdl = glucoseStatus.glucose,
                targetBgMgdl = ctx.profile.target_bg,
            )
            pkpdIntegration.setRecentBolusSamples(
                buildRecentPkpdBolusSamples(nowMillis = dateUtil.now(), fallbackWindowMin = window),
            )
            pkpdIntegration.computeRuntime(
                epochMillis = dateUtil.now(),
                bg = glucoseStatus.glucose,
                deltaMgDlPer5 = glucoseStatus.delta,
                iobU = totalIob,
                carbsActiveG = ctx.mealData.mealCOB,
                windowMin = window,
                exerciseFlag = work.sportTime,
                profileIsf = earlySens,
                tdd24h = ctx.profile.max_daily_basal * 24.0,
                mealContext = mealContext,
                consoleLog = work.consoleLog,
                combinedDelta = glucoseStatus.combinedDelta,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                physioLatentState = causalState.lastPhysioLatentState,
                estimatedRaMgdlPerMin = continuousStateEstimator.getLastRa().takeIf { it.isFinite() && it > 0.0 },
                causalStatePosterior = work.lastPatientState?.causalPosterior,
                patientEventMemory = work.lastPatientState?.eventMemory,
                allowLearning = allowLearning,
                isfRateLimitAuthority = false,
            )
        },
        predictions = AimiT9Predictions { earlySens, pkpdRuntime ->
            computePkpdPredictions(
                currentBg = glucoseStatus.glucose,
                iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
                finalSensitivity = earlySens,
                cobG = ctx.mealData.mealCOB,
                profile = ctx.profile,
                rT = rT,
                delta = glucoseStatus.delta,
                pkpdRuntime = pkpdRuntime,
                mealAbsorptionOutput = work.lastMealAbsorptionOutput,
                hypothesisState = causalState.lastUamHypothesisState,
                latentState = causalState.lastPhysioLatentState,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
            ).eventual
        },
        inflammation = AimiT9Inflammation { inflammationAdjuster.getAdjustments() },
        pumpAge = AimiT9PumpAge { pumpAgeDaysCached() },
    )


    /**
     * Recent deltas → combined delta → G6 BYODA lead on combined/short avg → dynamic peak vs profile peak.
     * Uses the same member [hourOfDay]/[delta]/[bg]/[shortAvgDelta] visibility as the historical inline block (before this tick’s GS copy onto members).
     */
    private fun runCombinedDeltaByodaAndDynamicPeak(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        useLegacyDynamics: Boolean,
        reasonAimi: StringBuilder,
    ): AimiCombinedDeltaAndPeakTick {
        val profile = ctx.profile
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)
        // Calcul du delta combiné : on combine le delta mesuré et le delta prédit
        val rawCombinedDelta: Float = ((delta + predicted) / 2.0).toFloat()

        /* G6 BYODA (`DEXCOM_G6_NATIVE`): jour seulement +30% / +20% sur combinedΔ et shortAvgΔ ; nuit et autres capteurs → pas de compensation. */
        val isG6Byoda = ctx.glucoseStatus.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE
        val isNight = work.hourOfDay >= 23 || work.hourOfDay < 6
        val combinedDelta: Float
        val shortAvgDeltaAdj: Float
        if (isG6Byoda && !isNight) {
            combinedDelta = rawCombinedDelta * 1.30f
            shortAvgDeltaAdj = shortAvgDelta * 1.20f
            work.consoleLog.add(
                "📡 G6_LEAD rawΔcomb=${aimiFmt2(rawCombinedDelta)} → ${aimiFmt2(combinedDelta)} | rawΔshort=${aimiFmt2(shortAvgDelta)} → ${aimiFmt2(shortAvgDeltaAdj)} (BYODA +30/+20%)"
            )
        } else {
            if (isG6Byoda) work.consoleLog.add("📡 G6_LEAD nuit [${work.hourOfDay}h] → pas de compensation (sécurité nocturne)")
            combinedDelta = rawCombinedDelta
            shortAvgDeltaAdj = shortAvgDelta
        }

        val tp = if (useLegacyDynamics) {
            calculateDynamicPeakTime(
                currentActivity = profile.currentActivity,
                futureActivity = profile.futureActivity,
                sensorLagActivity = profile.sensorLagActivity,
                historicActivity = profile.historicActivity,
                profile,
                recentSteps15Minutes,
                averageBeatsPerMinute.toInt(),
                bg,
                combinedDelta.toDouble(),
                reasonAimi
            )
        } else {
            profile.peakTime
        }
        return AimiCombinedDeltaAndPeakTick(combinedDelta, shortAvgDeltaAdj, tp)
    }

    /** G6 BYODA flag + Autodrive prefs; [autodriveDisplay] feeds advisor / reason lines downstream. */
    private fun buildPreTherapyAutodriveByodaBootstrap(ctx: AimiTickContext): AimiPreTherapyAutodriveByodaBootstrap {
        val isG6Byoda = ctx.glucoseStatus.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE
        val isAutodriveV3 = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
        // Classic (V1/V2) autodrive removed — "autodrive enabled" now means V3 active.
        val autodriveEnabled = isAutodriveV3
        val autodriveDisplay = if (isAutodriveV3) "✔V3" else "✘"
        return AimiPreTherapyAutodriveByodaBootstrap(
            isG6Byoda = isG6Byoda,
            autodriveEnabled = autodriveEnabled,
            isAutodriveV3 = isAutodriveV3,
            autodriveDisplay = autodriveDisplay,
        )
    }

    /**
     * Calendar → [hourOfDay], honeymoon, BG + SMB history, maxIOB/maxSMB (plateau/slope), NGR, TIR, carb/tags, GS→member deltas.
     * Stops immediately before `Therapy` so bfast/lunch/dinner/snack/highcarb timing stays unchanged.
     */
    private fun runTickClockMaxSmbTirCarbAndGlucoseCopy(
        ctx: AimiTickContext,
        glucoseStatus: GlucoseStatusAIMI,
        rT: RT,
        combinedDelta: Float,
    ): AimiTickClockTirCarbGlucoseBootstrap = decideTickClockMaxSmb(
        profile = ctx.profile,
        autosens = ctx.autosensData,
        glucoseStatus = glucoseStatus,
        mealLastCarbTime = ctx.mealData.lastCarbTime,
        mealSlope = ctx.mealData.slopeFromMinDeviation,
        rT = rT,
        combinedDelta = combinedDelta,
        preferences = preferences,
        consoleLog = work.consoleLog,
        state = object : AimiTickClockState {
            override fun nowMs() = now
            override fun setNow(ms: Long) { now = ms }
            override fun epochMs(): Long = injectedTickEpochMs ?: aimiWallClockMs()
            override fun setHourOfDay(hour: Int) { work.hourOfDay = hour }
            override fun setBg(bg: Double) { this@DetermineBasalaimiSMB2.bg = bg }
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun setTickCombinedDelta(delta: Float) { work.tickCombinedDelta = delta }
            override fun internalLastSmbMillis() = this@DetermineBasalaimiSMB2.internalLastSmbMillis
            override fun setLastBolusSmbUnit(unit: Float) { work.lastBolusSMBUnit = unit }
            override fun setLastSmbTime(minutes: Int) { work.lastsmbtime = minutes }
            override fun pendingLegacyPrebolusUnit() = this@DetermineBasalaimiSMB2.pendingLegacyPrebolusUnit
            override fun setPendingLegacyPrebolusUnit(unit: Float) { pendingLegacyPrebolusUnit = unit }
            override fun pendingLegacyPrebolusExpiry() = this@DetermineBasalaimiSMB2.pendingLegacyPrebolusExpiry
            override fun setPendingLegacyPrebolusExpiry(ms: Long) { pendingLegacyPrebolusExpiry = ms }
            override fun internalLastLegacyPrebolusMillis() = this@DetermineBasalaimiSMB2.internalLastLegacyPrebolusMillis
            override fun setMaxIob(value: Double) { work.maxIob = value }
            override fun maxIob() = work.maxIob
            override fun setMaxSmb(value: Double) { work.maxSMB = value }
            override fun maxSmb() = work.maxSMB
            override fun setMaxSmbHb(value: Double) { work.maxSMBHB = value }
            override fun maxSmbHb() = work.maxSMBHB
            override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
            override fun cob() = work.cob
            override fun setCob(value: Float) { work.cob = value }
            override fun setLastSlope(value: Double?) { work.lastSlopeFromMinDeviation = value }
            override fun setLastShortAvg(value: Double?) { lastShortAvgDeltaAtLadder = value }
            override fun setLadderBranch(branch: String?) { lastMaxSmbLadderBranch = branch }
            override fun ladderBranch() = lastMaxSmbLadderBranch
            override fun setTir1DayAbove(value: Double) { tir1DAYabove = value }
            override fun setCurrentTirLow(value: Double) { currentTIRLow = value }
            override fun setCurrentTirRange(value: Double) { currentTIRRange = value }
            override fun setCurrentTirAbove(value: Double) { currentTIRAbove = value }
            override fun setLastHourTirLow(value: Double) { lastHourTIRLow = value }
            override fun setLastHourTirLow100(value: Double) { lastHourTIRLow100 = value }
            override fun setLastHourTirAbove170(value: Double) { lastHourTIRabove170 = value }
            override fun setLastHourTirAbove120(value: Double) { lastHourTIRabove120 = value }
            override fun setWeekend(value: Int) { work.weekend = value }
            override fun setLastCarbAgeMin(value: Int) { lastCarbAgeMin = value }
            override fun lastCarbAgeMin() = this@DetermineBasalaimiSMB2.lastCarbAgeMin
            override fun setFutureCarbs(value: Float) { futureCarbs = value }
            override fun setRecentNotes(notes: List<UE>?) { recentNotes = notes }
            override fun setTags0to60(value: String) { work.tags0to60minAgo = value }
            override fun setTags60to120(value: String) { work.tags60to120minAgo = value }
            override fun setTags120to180(value: String) { work.tags120to180minAgo = value }
            override fun setTags180to240(value: String) { work.tags180to240minAgo = value }
            override fun setDelta(value: Float) { this@DetermineBasalaimiSMB2.delta = value }
            override fun setShortAvgDelta(value: Float) { shortAvgDelta = value }
            override fun setLongAvgDelta(value: Float) { longAvgDelta = value }
            override fun setBgAcc(value: Double) { work.bgacc = value }
        },
        smbCache = AimiTickSmbCache {
            latestSmbCached()?.let { AimiCachedSmb(it.timestamp, it.amount) }
        },
        prebolus = AimiLegacyPrebolusDelivered { since, minAmount ->
            runBlocking {
                persistenceLayer.getBolusesFromTime(since, true).any {
                    (it.type == BS.Type.NORMAL || it.type == BS.Type.SMB) && it.amount >= minAmount
                }
            }
        },
        maxIobPhrase = AimiMaxIobPhrase { target, work.maxIob ->
            target.reason.append(rh.gs(ApsStrings.reason_max_iob, work.maxIob))
        },
        nightGrowth = AimiNightGrowthConfig { profile, autosens, glucose, targetBg ->
            buildNightGrowthResistanceConfig(profile, autosens, glucose, targetBg)
        },
        tir = AimiTirWarmupRead {
            val snapshot = latestTirWarmupSnapshot()
            AimiTirWarmupView(
                tir1DayAbove = snapshot.tir1DayAbove,
                tir1DayInRange = snapshot.tir1DayInRange,
                currentTirLow = snapshot.currentTirLow,
                currentTirRange = snapshot.currentTirRange,
                currentTirAbove = snapshot.currentTirAbove,
                lastHourTirLow = snapshot.lastHourTirLow,
                lastHourTirAbove = snapshot.lastHourTirAbove,
                lastHourTirLow100 = snapshot.lastHourTirLow100,
                lastHourTirAbove170 = snapshot.lastHourTirAbove170,
                lastHourTirAbove120 = snapshot.lastHourTirAbove120,
                tirBasal3InRange = snapshot.tirBasal3InRange,
                tirBasal3Below = snapshot.tirBasal3Below,
                tirBasal3Above = snapshot.tirBasal3Above,
                tirBasalHourAbove = snapshot.tirBasalHourAbove,
            )
        },
        badDay = AimiBadDayDeletion { automateDeletionIfBadDay(it) },
        carbs = AimiCarbContextRead { nowMs, lastCarb, cobNow ->
            val snapshot = latestCarbContextSnapshot(nowMs, lastCarb, cobNow)
            AimiCarbContextView(
                lastCarbTimestamp = snapshot.lastCarbTimestamp,
                lastCarbAgeMin = snapshot.lastCarbAgeMin,
                futureCarbs = snapshot.futureCarbs,
                effectiveCob = snapshot.effectiveCob,
                recentNotes = snapshot.recentNotes,
            )
        },
        notes = AimiNoteTags { start, end -> parseNotes(start, end) },
    )


    private fun runTherapyHydrateClocksAndExerciseLockoutGate(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
    ): AimiTherapyExerciseGate {
        val decided = decideTherapyExerciseLockout(
            profile = profile,
            rT = rT,
            currentTemp = ctx.currentTemp,
            resumeBgMgdl = EXERCISE_BASAL_RESUME_BG_MGDL,
            consoleLog = work.consoleLog,
            calls = object : AimiTherapyExerciseCalls {
                override fun hydrateClocks(): Boolean {
                    val therapy = Therapy(persistenceLayer).also {
                        it.updateStatesBasedOnTherapyEvents(forceRefresh = true)
                    }
                    val deleteTime = therapy.deleteTime
                    if (deleteTime) {
                        // Still the count based clean up on purpose: the owner asked for the date based one on
                        // the "bad day" trigger only. `Therapy.deleteEventDate` does carry the day of the note,
                        // so this path could be moved to `removeRowsForDay` later, but that is a change of
                        // behaviour and needs to be asked for.
                        removeLast200Lines(csvfilePath)
                    }
                    work.sleepTime = therapy.sleepTime
                    work.snackTime = therapy.snackTime
                    work.sportTime = therapy.sportTime
                    work.lowCarbTime = therapy.lowCarbTime
                    this@DetermineBasalaimiSMB2.highCarbTime = therapy.highCarbTime
                    work.mealTime = therapy.mealTime
                    this@DetermineBasalaimiSMB2.anticipTime = therapy.anticipTime
                    this@DetermineBasalaimiSMB2.fclTime = therapy.fclTime
                    this@DetermineBasalaimiSMB2.bfastTime = therapy.bfastTime
                    this@DetermineBasalaimiSMB2.lunchTime = therapy.lunchTime
                    this@DetermineBasalaimiSMB2.dinnerTime = therapy.dinnerTime
                    work.fastingTime = therapy.fastingTime
                    work.stopTime = therapy.stopTime
                    work.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
                    this@DetermineBasalaimiSMB2.anticipruntime = therapy.getTimeElapsedSinceLastEvent("anticip")
                    this@DetermineBasalaimiSMB2.fclruntime = therapy.getTimeElapsedSinceLastEvent("fcl")
                    work.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
                    work.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
                    work.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
                    work.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
                    work.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
                    observeCircadianMealProfile(ctx.currentTime)
                    work.iscalibration = therapy.calibrationTime
                    val fieldDelta = this@DetermineBasalaimiSMB2.delta
                    val fieldShort = this@DetermineBasalaimiSMB2.shortAvgDelta
                    val fieldLong = this@DetermineBasalaimiSMB2.longAvgDelta
                    work.acceleratingUp = if (fieldDelta > 2 && fieldDelta - fieldLong > 2) 1 else 0
                    work.decceleratingUp = if (fieldDelta > 0 && (fieldDelta < fieldShort || fieldDelta < fieldLong)) 1 else 0
                    work.acceleratingDown = if (fieldDelta < -2 && fieldDelta - fieldLong < -2) 1 else 0
                    work.decceleratingDown = if (fieldDelta < 0 && (fieldDelta > fieldShort || fieldDelta > fieldLong)) 1 else 0
                    work.stable = if (fieldDelta > -3 && fieldDelta < 3 && fieldShort > -3 && fieldShort < 3 && fieldLong > -3 && fieldLong < 3) 1 else 0
                    return work.hourOfDay <= 7
                }
                override fun refreshActivity() = refreshAimiContextActivityFlag()
                override fun sportTime() = work.sportTime
                override fun aimiActivity() = work.aimiContextActivityActive
                override fun setLockout(active: Boolean) {
                    work.exerciseInsulinLockoutActive = active
                }
                override fun refreshHyper(profile: OapsProfileAimi) = refreshExerciseHyperBasalOverride(profile)
                override fun lockout() = work.exerciseInsulinLockoutActive
                override fun hyperOverride() = work.exerciseHyperBasalOverrideActive
                override fun zeroMaxSmb() {
                    work.maxSMB = 0.0
                    work.maxSMBHB = 0.0
                }
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun mealPriorityBypass() = mealDeliveryOverridesLockouts()
                override fun t3cBrittle() = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
                override fun markExerciseSafety() = markT3cRuntimeOwnership("SAFETY_TERMINAL", "exercise_lockout")
                override fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float) {
                    this@DetermineBasalaimiSMB2.logDecisionFinal(tag, rT, bg, delta)
                }
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun setZeroTemp(profile: OapsProfileAimi, rT: RT, currentTemp: CurrentTemp) =
                    setTempBasal(
                        0.0,
                        30,
                        profile,
                        rT,
                        currentTemp,
                        overrideSafetyLimits = false,
                        adaptiveMultiplier = causalState.adaptiveMult,
                    )
            },
        )
        return when (decided) {
            is AimiTherapyExerciseDecision.ReturnZeroBasal -> AimiTherapyExerciseGate.ReturnEarly(decided.rT)
            is AimiTherapyExerciseDecision.Continue -> AimiTherapyExerciseGate.Continue(decided.nightbis)
        }
    }

    /**
     * Manual meal modes: max TBR from pref vs profile, human-readable active mode, then [applyLegacyMealModes].
     * Reads meal clock flags from the instance (same as historical inline).
     */
    private fun runManualMealModesAfterTherapyGate(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
    ): AimiManualMealModesGate {
        val mealLimitPref = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val modeTbrLimit = if (mealLimitPref > 0.1) mealLimitPref else profile.max_basal
        val activeModeName = when {
            lunchTime -> "Lunch"
            dinnerTime -> "Dinner"
            bfastTime -> "Breakfast"
            work.snackTime -> "Snack"
            highCarbTime -> "HighCarb"
            work.mealTime -> "Meal"
            else -> "N/A"
        }
        applyLegacyMealModes(profile, rT, ctx.currentTemp, modeTbrLimit.toDouble())?.let { early ->
            return AimiManualMealModesGate.ReturnEarly(early)
        }
        return AimiManualMealModesGate.Continue(activeModeName)
    }

    /**
     * T3c brittle: pre-bolus guard logging, shadow Autodrive tick, predictions + trajectory, `executeT3cBrittleMode`.
     * **Placement:** immediately after `applyLegacyMealModes` so therapy/prebolus legacy ran first. Returns null if brittle disabled.
     *
     * @param pkpdRuntime local PKPD runtime alias from the tick (same as historical `var pkpdRuntime` at call site).
     */
    private fun runT3cBrittleBypassOrReturn(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        originalProfile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        shortAvgDeltaAdj: Float,
        physioMultipliers: PhysioMultipliersMTR,
        insulinActionState: InsulinActionState,
    ): RT? = decideT3cBrittleBypass(
        ctx = ctx,
        profile = profile,
        rT = rT,
        originalProfile = originalProfile,
        pkpdRuntime = pkpdRuntime,
        shortAvgDeltaAdj = shortAvgDeltaAdj,
        physioMultipliers = physioMultipliers,
        insulinActionState = insulinActionState,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiT3cBypassCalls {
            override fun legacyBypassAllowed() = legacyT3cBypassAllowed()
            override fun markHistoricalBypassNeutralized() {
                work.lastT3cHistoricalBypassNeutralizedThisTick = true
            }
            override fun markRuntimeOwnership(mode: String, reason: String) {
                markT3cRuntimeOwnership(mode, reason)
            }
            override fun setDecisionSource(source: String) {
                work.lastDecisionSource = source
            }
            override fun bolusesSince(startMs: Long, ascending: Boolean) =
                getBolusesFromTimeCached(startMs, ascending)
            override fun internalLastSmbMillis() = this@DetermineBasalaimiSMB2.internalLastSmbMillis
            override fun iob() = work.iob
            override fun maxIob() = work.maxIob
            override fun runAutodriveShadow(ctx: AimiTickContext, profile: OapsProfileAimi, shortAvgDeltaAdj: Float) {
                runT3cAutodriveShadowTick(ctx, profile, shortAvgDeltaAdj)
            }
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun delta() = this@DetermineBasalaimiSMB2.delta
            override fun applyAdvancedPredictions(
                bg: Double,
                delta: Float,
                sens: Double,
                iobDataArray: Array<IobTotal>,
                mealData: MealData,
                profile: OapsProfileAimi,
                rT: RT,
            ) {
                this@DetermineBasalaimiSMB2.applyAdvancedPredictions(
                    bg, delta, sens, iobDataArray, mealData, profile, rT,
                )
            }
            override fun bgacc() = work.bgacc
            override fun iobActivityNow() = work.iobActivityNow
            override fun cob() = work.cob
            override fun applyTrajectoryAnalysis(
                currentTime: Long,
                bg: Double,
                delta: Double,
                bgacc: Double,
                iobActivityNow: Double,
                iob: Float,
                insulinActionState: InsulinActionState,
                lastBolusAgeMinutes: Double,
                cob: Float,
                targetBg: Double,
                profile: OapsProfileAimi,
                rT: RT,
                uiInteraction: UiInteraction,
                relevanceScore: Double,
            ) {
                this@DetermineBasalaimiSMB2.applyTrajectoryAnalysis(
                    currentTime, bg, delta, work.bgacc, work.iobActivityNow, work.iob, insulinActionState,
                    work.lastBolusAgeMinutes, work.cob, targetBg, profile, rT, uiInteraction, relevanceScore,
                )
            }
            override fun eventualBg() = work.eventualBG
            override fun lastTrajectoryAnalysis() = trajectoryGuard.getLastAnalysis()
            override fun treeSnapshotMissing() = work.lastPhysiologicalTreeSnapshot == null
            override fun deployPhysioTree(sourceSensor: SourceSensor?) {
                updatePhysioLatentState(
                    snapshot = physioAdapter.getLatestSnapshot(),
                    sourceSensor = sourceSensor,
                )
            }
            override fun logPhysioDeployFailure(error: Throwable) {
                aapsLogger.error(LTag.APS, "T3C physio/tree deploy failed", error)
            }
            override fun proposeAutodriveBasal(
                ctx: AimiTickContext,
                profile: OapsProfileAimi,
                shortAvgDeltaAdj: Float,
                lgsThresholdMgdl: Double,
            ) = proposeT3cAutodriveBasalOnly(ctx, profile, shortAvgDeltaAdj, lgsThresholdMgdl)
            override fun variableSensitivity() = this@DetermineBasalaimiSMB2.variableSensitivity
            override fun executeT3c(
                bg: Double,
                delta: Float,
                shortAvgDelta: Double,
                longAvgDelta: Double,
                accel: Double,
                duraISFminutes: Double,
                duraISFaverage: Double,
                profile: OapsProfileAimi,
                currentTemp: CurrentTemp,
                iob: IobTotal,
                targetBg: Double,
                variableSensitivity: Double,
                maxIob: Double,
                eventualBg: Double,
                rT: RT,
                trajectoryContext: T3cTrajectoryContext?,
                cgmNoise: Double,
                autodriveBasalProposal: AutodriveEngine.BasalOnlyTbrProposal?,
            ) = executeT3cBrittleMode(
                bg, delta, shortAvgDelta, longAvgDelta, accel, work.duraISFminutes, work.duraISFaverage,
                profile, currentTemp, work.iob, targetBg, variableSensitivity, work.maxIob, eventualBg, rT,
                trajectoryContext, cgmNoise, autodriveBasalProposal,
            )
        },
    )

    /**
     * Meal Advisor: [tryMealAdvisor] and, if applied, TBR + direct-send bolus, telemetry, and final [RT].
     * **Placement:** caller must run [trySafetyStart] first and only invoke this when safety did not apply.
     * @return [rT] when the advisor applied a decision; **null** when falling through to later pipeline stages.
     */
    internal fun runMealAdvisorDecisionOrReturn(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        iobData: IobTotal,
        modesCondition: Boolean,
        isExplicitAdvisorRun: Boolean,
        lastBolusTimeMs: Long?,
        autodriveDisplay: String,
        hasRecentBolus45m: Boolean,
    ): RT? = decideMealAdvisorOrReturn(
        bg = bg,
        delta = delta,
        iobData = iobData,
        profile = profile,
        lastBolusTimeMs = lastBolusTimeMs,
        modesCondition = modesCondition,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        hasRecentBolus45m = hasRecentBolus45m,
        autodriveDisplay = autodriveDisplay,
        rT = rT,
        currentTemp = ctx.currentTemp,
        preferences = preferences,
        consoleLog = work.consoleLog,
        logger = aapsLogger,
        effects = legacyEffectSink,
        hasHypoRecovery = work.lastContextSnapshot?.hasHypoRecovery == true,
        causalState.adaptiveMult = causalState.adaptiveMult,
        statusLine = { display -> rh.gs(ApsStrings.autodrive_status, display, "Meal Advisor") },
        onSmbDelivered = { units ->
            internalLastSmbMillis = dateUtil.now()
            work.lastSmbCapped = units
            work.lastSmbFinal = units
        },
        logFinal = { decided -> logDecisionFinal("MEAL_ADVISOR", decided, bg, delta) },
        markFinal = { decided, temp -> markFinalLoopDecisionFromRT(decided, temp) },
    )

    /**
     * Lyra **Hard Brake**: falling + decelerating glycemia near target → zero TBR 30m, then finalize.
     * **Placement:** after Meal Advisor, **before** Autodrive V3 — avoids fueling a drop while deltas are still negative but slowing.
     * Uses [EPS_FALL] / [EPS_ACC] on this instance.
     * @return [rT] when triggered; **null** otherwise.
     */
    private fun runHardBrakeLyraOrReturn(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        targetBgMgdl: Float,
    ): RT? {
        val fallingDecelerating = delta < -EPS_FALL &&
            shortAvgDelta < -EPS_FALL &&
            longAvgDelta < -EPS_FALL &&
            shortAvgDelta > longAvgDelta + EPS_ACC
        if (!fallingDecelerating || bg >= targetBgMgdl + 10) return null
        work.consoleLog.add("🛑 HARD_BRAKE triggered: delta=$delta, short=$shortAvgDelta")
        rT.reason.append("🛑 Hard Brake: Falling Fast & Decelerating -> Zero Basal\n")
        setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = causalState.adaptiveMult)
        work.lastSafetySource = "HardBrake"
        logDecisionFinal("HARD_BRAKE", rT, bg, delta)
        markFinalLoopDecisionFromRT(rT, ctx.currentTemp)
        return rT
    }

    private fun updateHyperDwellAboveHighBgClock(nowMs: Long = aimiWallClockMs()) {
        val highBgPref = preferences.get(DoubleKey.OApsAIMIHighBg)
        val band = HyperTrajectoryHypoCredibility.highBgBandMgdl(targetBg.toDouble(), highBgPref)
        if (bg >= targetBg + band) {
            if (causalState.hyperDwellAboveHighBgSinceMs <= 0L) {
                causalState.hyperDwellAboveHighBgSinceMs = nowMs
            }
        } else {
            causalState.hyperDwellAboveHighBgSinceMs = 0L
        }
    }

    private fun dwellAboveHighBgMinutes(nowMs: Long = aimiWallClockMs()): Int {
        if (causalState.hyperDwellAboveHighBgSinceMs <= 0L) return 0
        return ((nowMs - causalState.hyperDwellAboveHighBgSinceMs) / 60_000L).toInt().coerceAtLeast(0)
    }

    private fun htrPreferences(): HyperTrajectoryReleasePreferences =
        HyperTrajectoryReleasePreferences.from(preferences)

    private fun classifyHyperSeverityForTick(
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
    ): HyperSeverityClassifier.Output {
        val htrPrefs = htrPreferences()
        val (floorTerminal, bestTerminal) = resolveHtrScenarioTerminals(rT)
        val highBg = preferences.get(DoubleKey.OApsAIMIHighBg)
        return HyperSeverityClassifier.classify(
            HyperSeverityClassifier.Input(
                bgMgdl = bg,
                targetBgMgdl = targetBg.toDouble(),
                highBgPreferenceMgdl = highBg,
                deltaMgdlPer5 = delta.toDouble(),
                shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
                combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
                floorTerminalMgdl = floorTerminal,
                bestTerminalMgdl = bestTerminal,
                tdd24hU = tdd24hU,
                dwellAboveHighBgMinutes = dwellAboveHighBgMinutes(),
                trajectoryType = trajectoryGuard.getLastAnalysis()?.classification,
                establishedDevOverrideMgdl = htrPrefs.establishedDevOverrideMgdl,
                deepDevOverrideMgdl = htrPrefs.deepDevOverrideMgdl,
                mealAbsorptionPhase = work.lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
                gapPrevMgdl = MealAbsorptionMemory.lastGapMgdl,
            ),
        )
    }

    private fun buildPhysiologicalPhaseClassifierInput(
        rT: RT,
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        bestTerminalMgdl: Double,
        floorTerminalMgdl: Double,
        mealAbsorptionMemoryActive: Boolean,
    ): PhysiologicalPhaseClassifier.Input {
        val htrClass = classifyHyperSeverityForTick(rT, combinedDelta, preferences.get(DoubleKey.OApsAIMITDD7))
        ensureWCycleInfo()
        val wInfo = work.wCycleInfoForRun
        return PhysioPhaseFusion.buildClassifierInput(
            bgMgdl = bg,
            targetBgMgdl = targetBg.toDouble(),
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = delta.toDouble(),
            shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
            combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
            mealCobG = work.cob.toDouble(),
            hourOfDay = work.hourOfDay,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            bestTerminalMgdl = bestTerminalMgdl,
            floorTerminalMgdl = floorTerminalMgdl,
            dwellAboveHighBgMinutes = dwellAboveHighBgMinutes(),
            wCycleEnabled = wCyclePreferences.enabled(),
            wCycleTrackingMode = wCyclePreferences.trackingMode(),
            wCyclePhase = wInfo?.phase,
            htrTier = htrClass.tier,
            plateauSustain = htrClass.plateauSustain,
            mealAbsorptionMemoryActive = mealAbsorptionMemoryActive,
        )
    }

    private fun refreshPhysiologicalPhase(
        rT: RT,
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        basePhysioMultipliers: PhysioMultipliersMTR,
    ): PhysioMultipliersMTR {
        val scenario = lastScenarioProjection
        val (floorT, rawBestT) = if (scenario != null) {
            scenario.clinicalFloor.terminalMgdl to scenario.scenarioBest.terminalMgdl
        } else {
            val floor = minPredictedAcrossCurves(rT.predBGs) ?: bg
            floor to bg
        }
        val fused = PhysioPhaseFusion.classifyAndFuse(
            buildPhysiologicalPhaseClassifierInput(
                rT = rT,
                combinedDelta = combinedDelta,
                stepsLast15m = stepsLast15m,
                heartRateBpm = heartRateBpm,
                restingHeartRateBpm = restingHeartRateBpm,
                bestTerminalMgdl = rawBestT,
                floorTerminalMgdl = floorT,
                mealAbsorptionMemoryActive = MealAbsorptionMemory.isActive(dateUtil.now()),
            ),
            basePhysioMultipliers,
        )
        work.lastPhysiologicalPhaseOutput = fused.phaseOutput
        work.lastFusedPhysioMultipliers = fused.multipliers
        CircadianMealProfileStore.observeDawnPhase(
            storage = storage,
            eventTimeMs = dateUtil.now(),
            phaseOutput = fused.phaseOutput,
            mealSignalsActive = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime || work.cob > 1.0,
        )
        val policy = fused.phaseOutput.policy
        work.lastScenarioBestCappedForPhysio = scenario != null &&
            HormonalScenarioTerminalCap.capBestTerminalMgdl(bg, rawBestT, policy) < rawBestT - 0.5
        if (!fused.multipliers.isNeutral() || policy.phase != PhysiologicalPhase.OFF) {
            work.consoleLog.add(
                "🌅 PHYSIO_FUSE: phase=${policy.phase.name} SMB×${aimiFmt3(fused.multipliers.smbFactor)} " +
                    "react×${aimiFmt3(fused.multipliers.reactivityFactor)} " +
                    "basal×${aimiFmt3(fused.multipliers.basalFactor)} (${policy.reason})",
            )
        }
        return fused.multipliers
    }

    private fun refreshMealAbsorptionPhase(
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        mealContext: MealSafetyContext,
        lastBolusTimeMs: Long?,
        nowMs: Long,
    ): MealAbsorptionPhaseEngine.Output = decideRefreshMealAbsorptionPhase(
        combinedDelta = combinedDelta,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = restingHeartRateBpm,
        mealContext = mealContext,
        lastBolusTimeMs = lastBolusTimeMs,
        nowMs = nowMs,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiMealAbsorptionCalls {
            override fun scenario() = lastScenarioProjection
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun predictedBg() = this@DetermineBasalaimiSMB2.predictedBg.toDouble()
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta.toDouble()
            override fun longAvgDelta() = this@DetermineBasalaimiSMB2.longAvgDelta.toDouble()
            override fun iob() = work.iob.toDouble()
            override fun cob() = work.cob.toDouble()
            override fun maxSmb() = work.maxSMB
            override fun mealTime() = work.mealTime
            override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
            override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
            override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
            override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
            override fun lateFat(
                bg: Double,
                predictedBg: Double,
                delta: Double,
                shortAvgDelta: Double,
                longAvgDelta: Double,
                iob: Double,
                cob: Double,
                maxSmb: Double,
                lastBolusTimeMs: Long?,
                mealTime: Boolean,
                bfastTime: Boolean,
                lunchTime: Boolean,
                dinnerTime: Boolean,
                highCarbTime: Boolean,
                nowMs: Long,
            ) = isLateFatProteinRise(
                bg = bg,
                predictedBg = predictedBg,
                delta = delta,
                shortAvgDelta = shortAvgDelta,
                longAvgDelta = longAvgDelta,
                iob = work.iob,
                cob = work.cob,
                maxSMB = maxSmb,
                lastBolusTimeMs = lastBolusTimeMs,
                mealFlags = MealFlags(work.mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime),
                nowMs = nowMs,
            )
            override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
            override fun deltaPrev() = mealAbsorptionDeltaPrevOfTick()
            override fun hourOfDay() = work.hourOfDay
            override fun maxIob() = work.maxIob
            override fun gapPrev() = MealAbsorptionMemory.lastGapMgdl
            override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
            override fun physiologicalPhase() =
                work.lastPhysiologicalPhaseOutput?.phase ?: PhysiologicalPhase.OFF
            override fun estimatedRa() =
                continuousStateEstimator.getLastRa().takeIf { it.isFinite() && it > 0.0 }
            override fun writeOutput(output: MealAbsorptionPhaseEngine.Output) {
                work.lastMealAbsorptionOutput = output
            }
        },
    )


    private fun physiologicalPhaseExport(): AimiDecisionContext.PhysiologicalPhaseExport? {
        val out = work.lastPhysiologicalPhaseOutput ?: return null
        val policy = out.policy
        val capU = policy.smbFloorCapU.takeIf { it.isFinite() && it < 100.0 }
        val fused = work.lastFusedPhysioMultipliers
        return AimiDecisionContext.PhysiologicalPhaseExport(
            phase = out.phase.name,
            confidence = out.confidence,
            behavioral_risk = policy.phase.name,
            reason = policy.reason,
            extended_dawn_guard = policy.extendedDawnGuard,
            scenario_best_capped = work.lastScenarioBestCappedForPhysio,
            max_htr_tier = policy.maxHtrTier.name,
            smb_floor_cap_u = capU,
            physio_smb_factor_fused = fused?.smbFactor,
            physio_phase_source = fused?.source,
        )
    }

    private fun mealAbsorptionPhaseExport(): AimiDecisionContext.MealAbsorptionPhaseExport? {
        val out = work.lastMealAbsorptionOutput ?: return null
        return AimiDecisionContext.MealAbsorptionPhaseExport(
            phase = out.phase.name,
            belief = out.belief,
            reason = out.reason,
            memory_active = out.memoryActive,
            wave_count = out.waveCount,
            meal_delivery_priority = out.mealDeliveryPriority,
            chrono_prior = out.chronoPrior,
            kinetic_score = out.kineticScore,
            trajectory_score = out.trajectoryScore,
            physio_score = out.physioScore,
        )
    }

    /**
     * Session expiry, then the SMB ceiling.
     *
     * `onTickStart` reverts an expired session into [preferences]. The two reads afterwards are
     * [tpoTickSmbCeiling]. [nowMs] is `dateUtil.now()`, the clock this tick already used.
     */
    private fun applyTpoTickSmbCeiling(nowMs: Long) {
        if (::tpoOrchestrator.isInitialized) {
            tpoOrchestrator.onTickStart(nowMs)
        }
        val ceiling = tpoTickSmbCeiling(preferences)
        work.maxSMB = ceiling.maxSmb
        work.maxSMBHB = ceiling.maxSmbHb
    }

    private fun updatePhysioLatentState(
        snapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
        patternSnapshot: PhysiologicalPatternSnapshot? = work.lastPhysiologicalPatternSnapshot,
    ): PhysioLatentState = decideUpdatePhysioLatentState(
        snapshot = snapshot,
        sourceSensor = sourceSensor,
        patternSnapshot = patternSnapshot,
        preferences = preferences,
        calls = object : AimiPhysioLatentCalls {
            override fun physioContext() = physioAdapter.getEffectiveContext()
            override fun physioTrace() = physioAdapter.getLastDecisionTrace()
            override fun phaseOutput() = work.lastPhysiologicalPhaseOutput
            override fun mealAbsorption() = work.lastMealAbsorptionOutput
            override fun aggression() = work.correctionAggressionDecision
            override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
            override fun behaviorProfile() = behaviorProfileSource.read(preferences)
            override fun inflammation() = work.lastInflammationResult
            override fun writeHypothesis(state: UamHypothesisState) {
                causalState.lastUamHypothesisState = state
            }
            override fun writeLatent(state: PhysioLatentState) {
                causalState.lastPhysioLatentState = state
            }
            override fun refreshEffort() = refreshEffortActivityBelief()
            override fun nowMs() = dateUtil.now()
            override fun contextSnapshot() = work.lastContextSnapshot
            override fun refreshPatient(
                nowMs: Long,
                contextSnapshot: ContextSnapshot?,
                healthSnapshot: HealthContextSnapshot,
                sourceSensor: SourceSensor?,
            ) {
                refreshPatientStateRuntime(
                    nowMs = nowMs,
                    contextSnapshot = contextSnapshot,
                    healthSnapshot = healthSnapshot,
                    sourceSensor = sourceSensor,
                )
            }
            override fun tpoOrNull() = if (::tpoOrchestrator.isInitialized) tpoOrchestrator else null
            override fun patientState() = work.lastPatientState
            override fun patientMode() = work.lastPatientModeDecision
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun cob() = work.cob.toDouble()
            override fun minBgLookback() = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES)
            override fun writeMaxSmb(value: Double) {
                work.maxSMB = value
            }
            override fun maxSmb() = work.maxSMB
            override fun writeMaxSmbHb(value: Double) {
                work.maxSMBHB = value
            }
        },
    )

    private fun resolvePatientRuntimeSnapshotForExport(timestampMs: Long): PatientRuntimeSnapshot? {
        PatientStateRuntimeRepository.getLatest()?.let { return it }
        val patientState = work.lastPatientState ?: return null
        val patientModeDecision = work.lastPatientModeDecision ?: return null
        return PatientRuntimeSnapshot(
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            updatedAtMs = timestampMs,
            physiologicalTree = work.lastPhysiologicalTreeSnapshot,
            harmoniaDecision = work.lastHarmoniaDecision,
        )
    }

    private fun publishPatientStateAfterPhysiologyRefresh(
        glucoseStatus: GlucoseStatusAIMI,
        nowMs: Long,
    ) {
        val wearableSnap = try {
            physioAdapter.getLatestSnapshot()
        } catch (_: Exception) {
            HealthContextSnapshot()
        }
        val contextSnap = try {
            if (preferences.get(BooleanKey.OApsAIMIContextEnabled)) {
                contextManager.getSnapshot(nowMs)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        work.lastContextSnapshot = contextSnap
        updatePhysioLatentState(
            snapshot = enrichThermalSnapshot(wearableSnap),
            sourceSensor = glucoseStatus.sourceSensor,
            patternSnapshot = work.lastPhysiologicalPatternSnapshot,
        )
    }

    private fun enrichThermalSnapshot(snapshot: HealthContextSnapshot): HealthContextSnapshot =
        enrichPatientThermal(snapshot, work.wCycleInfoForRun?.phase)

    private fun refreshPatientStateRuntime(
        nowMs: Long = dateUtil.now(),
        contextSnapshot: ContextSnapshot? = work.lastContextSnapshot,
        healthSnapshot: HealthContextSnapshot? = null,
        sourceSensor: SourceSensor? = lastPatientSourceSensor,
        refreshSource: PatientRefreshSource = PatientRefreshSource.LOOP_TICK,
    ): PatientStateSnapshot {
        val wearableSnap = healthSnapshot ?: try {
            physioAdapter.getLatestSnapshot()
        } catch (_: Exception) {
            HealthContextSnapshot()
        }
        val result = decideRefreshPatientStateRuntime(
            nowMs = nowMs,
            contextSnapshot = contextSnapshot,
            healthSnapshot = wearableSnap,
            sourceSensor = sourceSensor,
            refreshSource = refreshSource,
            calls = patientRuntimeCalls(),
        )
        work.lastContextSnapshot = contextSnapshot
        if (sourceSensor != null) {
            lastPatientSourceSensor = sourceSensor
        }
        work.lastPatientState = result.patientState
        work.lastPatientModeDecision = result.patientModeDecision
        causalState.lastWCycleBelief = result.wCycleBelief
        work.lastPhysiologicalTreeSnapshot = result.physiologicalTree
        work.lastMealCertainty = result.mealCertainty
        work.lastHarmoniaDecision = result.harmoniaDecision
        return result.patientState
    }

    private fun patientRuntimeCalls(): PatientRuntimeCalls = object : PatientRuntimeCalls {
        override fun enrichThermal(snapshot: HealthContextSnapshot) =
            enrichThermalSnapshot(snapshot)

        override fun eventMemory(
            currentBgMgdl: Double,
            latentState: app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState?,
            thermalBelief: app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest?,
            nowMs: Long,
        ) = buildPatientEventMemory(currentBgMgdl, latentState, thermalBelief, nowMs)

        override fun phase() = work.lastPhysiologicalPhaseOutput
        override fun mealAbsorption() = work.lastMealAbsorptionOutput
        override fun pattern() = work.lastPhysiologicalPatternSnapshot
        override fun latent() = causalState.lastPhysioLatentState
        override fun hypothesis() = causalState.lastUamHypothesisState
        override fun ensureWCycle() = ensureWCycleInfo()
        override fun wCyclePreferences() = wCyclePreferences
        override fun hourOfDay() = work.hourOfDay
        override fun hypoGuardActive() =
            TuningContextEngine.parseContext(preferences.get(StringKey.AimiTuningContextSelection)) ==
                AimiTuningContext.HYPO_GUARD
        override fun effectiveDiaHours() = work.tickEffectiveDiaHours
        override fun effectivePeakMinutes() = work.tickEffectivePeakMinutes
        override fun insulinAction() = work.tickInsulinActionState
        override fun effortAssessment() = work.lastEffortAssessment
        override fun cgmNoise() = lastLoopCgmNoise
        override fun sensorInsertionMs(nowMs: Long) = resolveSensorInsertionMsCached(nowMs)
        override fun scenarioBest() = lastScenarioProjection?.scenarioBest
        override fun cachedEventualTerminal() = cachedRiskEnvelopeDecision?.eventualTerminalMgdl
        override fun eventualBg() = work.eventualBG
        override fun authoritativeEventual(fallback: Double) = authoritativeEventualBg(fallback)
        override fun bg() = bg
        override fun delta() = delta.toDouble()
        override fun targetBg() = targetBg.toDouble()
        override fun cob() = work.cob.toDouble()
        override fun shortAvgDelta() = shortAvgDelta.toDouble()
        override fun effortVeto() = effortSuppressesUndeclaredMeal()
        override fun effortLive() = effortIsLiveMovement()
        override fun basalUph() = work.basalaimi
        override fun autodriveMaxBasal() = preferences.get(DoubleKey.autodriveMaxBasal)
        override fun maxSmb() = work.maxSMB
        override fun maxSmbHb() = work.maxSMBHB
        override fun maxIob() = work.maxIob
        override fun iob() = work.iob.toDouble()
        override fun chaosScore() = work.lastRbtChaosEvaluation?.score ?: 0.0
        override fun priorRuntimeBlocker() = harmoniaPrevRuntimeBlocker
        override fun priorBlockedStreak() = harmoniaBlockedStreak
        override fun aggression() = work.correctionAggressionDecision
        override fun inflammation() = work.lastInflammationResult
        override fun physioContext() = physioAdapter.getEffectiveContext()
        override fun physioTrace() = physioAdapter.getLastDecisionTrace()
        override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
        override fun storedSourceSensor() = lastPatientSourceSensor
        override fun log(line: String) {
            work.consoleLog.add(line)
        }
    }

    private fun buildPatientEventMemory(
        currentBgMgdl: Double,
        latentState: PhysioLatentState?,
        thermalBelief: app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest?,
        nowMs: Long,
    ): PatientEventMemory {
        val windowedSamples = glucoseStatusCalculatorAimi.getBucketedGlucoseSinceMinutes(
            PatientEventMemoryCalculator.LOAD_LOOKBACK_MINUTES,
        )
        return PatientEventMemoryCalculator.compute(
            currentBgMgdl = currentBgMgdl,
            windowedSamples = windowedSamples,
            hypoFloor75m = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            latentState = latentState,
            recoveryBurden = thermalBelief?.recoveryBurden ?: 0.0,
            nowMs = nowMs,
        )
    }

    private fun observeCircadianMealProfile(nowMs: Long) {
        CircadianMealProfileStore.ensureLoaded(storage)

        fun observeIfFresh(active: Boolean, runtimeMinutes: Long, slotLabel: String) {
            if (!active) return
            if (runtimeMinutes !in 0L..10L) return
            val eventTimeMs = nowMs - (runtimeMinutes * 60_000L)
            CircadianMealProfileStore.observeMealWindow(storage, slotLabel, eventTimeMs)
        }

        observeIfFresh(bfastTime, work.bfastruntime, "bfast")
        observeIfFresh(lunchTime, work.lunchruntime, "lunch")
        observeIfFresh(dinnerTime, work.dinnerruntime, "dinner")
        observeIfFresh(work.snackTime, work.snackrunTime, "snack")
        observeIfFresh(highCarbTime, work.highCarbrunTime, "highcarb")
        observeIfFresh(work.mealTime, work.mealruntime, "meal")

        val estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val estimatedCarbsTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        if (estimatedCarbs > 10.0 && estimatedCarbsTime > 0L) {
            val estimatedAgeMinutes = ((nowMs - estimatedCarbsTime) / 60_000L).coerceAtLeast(0L)
            if (estimatedAgeMinutes in 0L..10L) {
                CircadianMealProfileStore.observeEstimatedMeal(storage, estimatedCarbsTime)
            }
        }
    }

    /** Scenario terminals when available; otherwise eventual / prediction fallbacks for the same tick. */
    private fun resolveHtrScenarioTerminals(rT: RT): Pair<Double, Double> {
        val scenario = lastScenarioProjection
        if (scenario != null) {
            val rawBest = scenario.scenarioBest.terminalMgdl
            val capped = HormonalScenarioTerminalCap.capBestTerminalMgdl(
                bg,
                rawBest,
                work.lastPhysiologicalPhaseOutput?.policy,
            )
            return scenario.clinicalFloor.terminalMgdl to capped
        }
        val floorTerminal = minPredictedAcrossCurves(rT.predBGs) ?: bg
        val bestTerminal = when {
            work.eventualBG.isFinite() && work.eventualBG > bg + 15.0 -> work.eventualBG
            work.lastEventualBgSnapshot.isFinite() && work.lastEventualBgSnapshot > bg + 15.0 -> work.lastEventualBgSnapshot
            predictedBg > bg + 15f -> predictedBg.toDouble()
            else -> bg
        }
        return floorTerminal to bestTerminal
    }

    internal fun buildRbtExtendedSignals(
        rT: RT,
        profile: OapsProfileAimi,
        htr: HyperTrajectoryReleaseResult,
        v3SmbU: Double,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        mpcFeedForwardRa: Double?,
        cbfShieldDeltaU: Double?,
    ): RbtExtendedSignals {
        val state = RbtExtendedTickState(
            iob = work.iob,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            eventualBG = work.eventualBG,
            targetBg = targetBg,
            hourOfDay = work.hourOfDay,
            causalState.adaptiveMult = causalState.adaptiveMult,
            bgacc = work.bgacc,
            duraISFminutes = work.duraISFminutes,
            duraISFaverage = work.duraISFaverage,
            maxIob = work.maxIob,
            variableSensitivity = variableSensitivity,
            maxSMB = work.maxSMB,
            maxSMBHB = work.maxSMBHB,
            mealTime = work.mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = work.snackTime,
            sleepTime = work.sleepTime,
            exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
            exerciseHyperBasalOverrideActive = work.exerciseHyperBasalOverrideActive,
            exerciseBasalResumeBgMgdl = EXERCISE_BASAL_RESUME_BG_MGDL,
            causalState.highBgOverrideUsed = causalState.highBgOverrideUsed,
            mealAdvisorOneShotThisTick = work.mealAdvisorOneShotThisTick,
            lastScenarioBestCappedForPhysio = work.lastScenarioBestCappedForPhysio,
            causalState.lastPhysioLatentState = causalState.lastPhysioLatentState,
            causalState.lastUamHypothesisState = causalState.lastUamHypothesisState,
            lastPatientState = work.lastPatientState,
            lastPatientModeDecision = work.lastPatientModeDecision,
            lastMealAbsorptionOutput = work.lastMealAbsorptionOutput,
            lastPhysiologicalPhaseOutput = work.lastPhysiologicalPhaseOutput,
            lastPostHypoDeliveryAuthority = work.lastPostHypoDeliveryAuthority,
            lastHarmoniaDecision = work.lastHarmoniaDecision,
            lastPhysiologicalTreeSnapshot = work.lastPhysiologicalTreeSnapshot,
            lastTubeAdvisorSmbCapScale = work.lastTubeAdvisorSmbCapScale,
            tickInsulinActionState = work.tickInsulinActionState,
            correctionAggressionDecision = work.correctionAggressionDecision,
            lastInflammationResult = work.lastInflammationResult,
            currentThyroidEffects = work.currentThyroidEffects,
            lastMealCertainty = work.lastMealCertainty,
        )
        return decideRbtExtendedSignals(
            rT = rT,
            profile = profile,
            htr = htr,
            v3SmbU = v3SmbU,
            autosens = autosens,
            glucoseStatus = glucoseStatus,
            mpcFeedForwardRa = mpcFeedForwardRa,
            cbfShieldDeltaU = cbfShieldDeltaU,
            preferences = preferences,
            dateUtil = dateUtil,
            consoleLog = work.consoleLog,
            state = state,
            pkpd = cachedPkpdRuntime,
            pkpdIntegration = pkpdIntegration,
            basalNeuralLearner = basalNeuralLearner,
            basalLearner = basalLearner,
            nightGrowthResistanceMode = nightGrowthResistanceMode,
            trajectoryGuard = trajectoryGuard,
            endoAdjuster = endoAdjuster,
            recentGlucose = AimiRecentGlucose { glucoseStatusCalculatorAimi.getRecentGlucose() },
            postHypoClassification = AimiPostHypoClassification { recentBGs, work.cob, explicitMealMode, shortAvgDelta, delta, slopeFromMinDeviation, estimatedCarbs, estimatedCarbsAgeMs, localHour, reason ->
                classifyPostHypoState(
                    recentBGs = recentBGs,
                    cob = work.cob,
                    explicitMealMode = explicitMealMode,
                    shortAvgDelta = shortAvgDelta,
                    delta = delta,
                    slopeFromMinDeviation = slopeFromMinDeviation,
                    estimatedCarbs = estimatedCarbs,
                    estimatedCarbsAgeMs = estimatedCarbsAgeMs,
                    localHour = localHour,
                    reason = reason,
                )
            },
            nightGrowthConfig = AimiNightGrowthConfig { ngrProfile, ngrAutosens, ngrGlucose, ngrTarget ->
                buildNightGrowthResistanceConfig(ngrProfile, ngrAutosens, ngrGlucose, ngrTarget)
            },
            physio = object : AimiPhysioTick {
                override fun getLastDecisionTrace() = physioAdapter.getLastDecisionTrace()
                override fun getEffectiveContext() = physioAdapter.getEffectiveContext()
                override fun getLatestSnapshot() = physioAdapter.getLatestSnapshot()
            },
            tickWrites = object : AimiRbtTickWrites {
                override fun setLastPostHypoOrdinal(ordinal: Int) {
                    causalState.lastPostHypoOrdinal = ordinal
                }
                override fun setLastNgrBasalMultiplier(multiplier: Double) {
                    work.lastNgrBasalMultiplier = multiplier
                }
            },
        )
    }

    private fun rbtIgnoreMinPredictedCurve(): Boolean =
        work.lastRbtAppliedHints?.ignoreMinPredictedCurve == true

    private fun minPredictedBgForRbtWiring(rawMinPred: Double?): Double? {
        if (rawMinPred == null) return null
        val hints = work.lastRbtAppliedHints ?: return rawMinPred
        val dev = bg - targetBg
        val drop = HyperTrajectoryHypoCredibility.hypoCredibilityDropMgdl(dev)
        return when {
            hints.ignoreMinPredictedCurve -> max(rawMinPred, bg - drop)
            hints.partialMinPredCredibility ->
                max(rawMinPred, bg - drop * RbtResolutionBridge.PARTIAL_CREDIBILITY_BLEND)
            else -> rawMinPred
        }
    }

    private fun runRecursiveBeliefResolve(
        v3SmbU: Double,
        htr: HyperTrajectoryReleaseResult,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        profile: OapsProfileAimi,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        stepsLast15m: Int = 0,
        heartRateBpm: Int = 0,
        autodriveGateOpen: Boolean = false,
        mpcFeedForwardRa: Double? = null,
        cbfShieldDeltaU: Double? = null,
    ): RecursiveBeliefSnapshot? = decideRecursiveBeliefResolve(
        v3SmbU = v3SmbU,
        htr = htr,
        rT = rT,
        combinedDelta = combinedDelta,
        tdd24hU = tdd24hU,
        profile = profile,
        autosens = autosens,
        glucoseStatus = glucoseStatus,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        autodriveGateOpen = autodriveGateOpen,
        mpcFeedForwardRa = mpcFeedForwardRa,
        cbfShieldDeltaU = cbfShieldDeltaU,
        preferences = preferences,
        consoleLog = work.consoleLog,
        fields = object : AimiRbtResolveFields {
            override fun scenario() = lastScenarioProjection
            override fun curves() = work.lastAdvancedPredictionCurves
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta.toDouble()
            override fun mealAbsorption() = work.lastMealAbsorptionOutput
            override fun phaseOutput() = work.lastPhysiologicalPhaseOutput
            override fun uamHypothesis() = causalState.lastUamHypothesisState
            override fun iob() = work.iob.toDouble()
            override fun maxIob() = work.maxIob
            override fun eventualBg() = work.eventualBG
            override fun mealModeActive() =
                work.mealTime || bfastTime || lunchTime || dinnerTime || work.snackTime || highCarbTime
            override fun hourOfDay() = work.hourOfDay
            override fun exerciseLockout() = work.exerciseInsulinLockoutActive
            override fun sportTime() = work.sportTime
            override fun sleepTime() = work.sleepTime
            override fun trajectoryRelevance() =
                work.lastFusedPhysioMultipliers?.trajectoryRelevanceScore?.toDouble()
                    ?: work.lastBasePhysioMultipliers.trajectoryRelevanceScore.toDouble()
            override fun maxSmbEffective() = work.maxSMBHB.coerceAtLeast(work.maxSMB)
            override fun barrierPermittedU() = autodriveEngine.lastCbfPermittedU.takeIf { it.isFinite() && it >= 0.0 }
            override fun insulinActivity() = work.tickInsulinActionState
            override fun loadGovernorMultiplierG() = work.lastLoadGovernorMultiplierG
            override fun safetyTerminals() = work.lastSafetyTerminalsForRbt
            override fun riskEnvelope() = cachedRiskEnvelopeEarly
            override fun contextActivityActive() = work.aimiContextActivityActive
            override fun trajBridgePending() = work.pendingTrajSpiralBasal != null
            override fun fusedMultipliers() = work.lastFusedPhysioMultipliers
            override fun wCycleBelief() = causalState.lastWCycleBelief
            override fun patientMode() = work.lastPatientModeDecision
            override fun contextIntentCount() = work.lastContextSnapshot?.intentCount ?: 0
        },
        htrTerminals = AimiHtrTerminals { resolveHtrScenarioTerminals(it) },
        doseBg = object : AimiRbtResolveDoseBg {
            override fun authoritativeMinPredBg(rT: RT, rawMinPred: Double?) =
                this@DetermineBasalaimiSMB2.authoritativeMinPredBg(rT, rawMinPred)
            override fun authoritativeEventualBg(fallback: Double) =
                this@DetermineBasalaimiSMB2.authoritativeEventualBg(fallback)
        },
        dwell = AimiRbtDwell { dwellAboveHighBgMinutes() },
        trajectory = AimiRbtTrajectory { trajectoryGuard.getLastAnalysis() },
        recentGlucose = AimiRecentGlucose { glucoseStatusCalculatorAimi.getRecentGlucose() },
        uamConfidence = AimiUamConfidence { AimiUamHandler.confidenceOrZero() },
        wCycle = AimiRbtWCycleEnsure { ensureWCycleInfo() },
        extendedBuild = AimiRbtExtendedBuild { rT, profile, htr, v3SmbU, autosens, glucoseStatus, mpcFeedForwardRa, cbfShieldDeltaU ->
            buildRbtExtendedSignals(rT, profile, htr, v3SmbU, autosens, glucoseStatus, mpcFeedForwardRa, cbfShieldDeltaU)
        },
        physio = object : AimiRbtResolvePhysio {
            override fun getEffectiveContext() = physioAdapter.getEffectiveContext()
            override fun getLatestSnapshot() = physioAdapter.getLatestSnapshot()
        },
        contextSnapshot = AimiRbtContextSnapshot { contextManager.getSnapshot(it) },
        writes = object : AimiRbtResolveWrites {
            override fun setStacking(evaluation: InsulinStackingStance.Evaluation) {
                work.lastInsulinStackingEvaluation = evaluation
            }
            override fun setPattern(snapshot: PhysiologicalPatternSnapshot) {
                work.lastPhysiologicalPatternSnapshot = snapshot
            }
            override fun setContext(snapshot: ContextSnapshot?) {
                work.lastContextSnapshot = snapshot
            }
        },
        latent = AimiRbtResolveLatent { snapshot, sourceSensor, patternSnapshot ->
            updatePhysioLatentState(snapshot, sourceSensor, patternSnapshot)
        },
        deltaPrev = AimiRbtDeltaPrev { mealAbsorptionDeltaPrevOfTick() },
        clock = AimiRbtResolveClock { dateUtil.now() },
    )

    private fun resolveAndWireRbtLiveTick(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        v3SmbU: Double,
        stepsLast15m: Int = 0,
        heartRateBpm: Int = 0,
        autodriveGateOpen: Boolean = false,
        mpcFeedForwardRa: Double? = null,
        cbfShieldDeltaU: Double? = null,
    ): RbtLiveCommitResult? = decideRbtLiveTick(
        ctx = ctx,
        profile = profile,
        rT = rT,
        combinedDelta = combinedDelta,
        tdd24hU = tdd24hU,
        v3SmbU = v3SmbU,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        autodriveGateOpen = autodriveGateOpen,
        mpcFeedForwardRa = mpcFeedForwardRa,
        cbfShieldDeltaU = cbfShieldDeltaU,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiRbtLiveTickCalls {
            override fun alreadyResolved() = work.rbtResolvedThisTick
            override fun lastCommit() = work.lastRbtLiveCommitResult
            override fun markResolved() {
                work.rbtResolvedThisTick = true
            }
            override fun evaluateHtr(
                v3SmbU: Double,
                rT: RT,
                combinedDelta: Float,
                tdd24hU: Double,
                rbtLeafOnly: Boolean,
            ) = this@DetermineBasalaimiSMB2.evaluateHyperTrajectoryRelease(
                v3SmbU, rT, combinedDelta, tdd24hU, rbtLeafOnly,
            )
            override fun resolveBelief(
                v3SmbU: Double,
                htr: HyperTrajectoryReleaseResult,
                rT: RT,
                combinedDelta: Float,
                tdd24hU: Double,
                profile: OapsProfileAimi,
                autosens: AutosensResult,
                glucoseStatus: GlucoseStatusAIMI?,
                stepsLast15m: Int,
                heartRateBpm: Int,
                autodriveGateOpen: Boolean,
                mpcFeedForwardRa: Double?,
                cbfShieldDeltaU: Double?,
            ) = this@DetermineBasalaimiSMB2.runRecursiveBeliefResolve(
                v3SmbU, htr, rT, combinedDelta, tdd24hU, profile, autosens, glucoseStatus,
                stepsLast15m, heartRateBpm, autodriveGateOpen, mpcFeedForwardRa, cbfShieldDeltaU,
            )
            override fun storeSnapshot(snapshot: RecursiveBeliefSnapshot?) {
                work.lastRecursiveBeliefSnapshot = snapshot
            }
            override fun storeLoadGovernor(multiplierG: Double) {
                work.lastLoadGovernorMultiplierG = multiplierG
            }
            override fun trajectoryUncertain() =
                trajectoryGuard.getLastAnalysis()?.classification == TrajectoryType.UNCERTAIN
            override fun patternCapFlapping() = patternCapHold.holding
            override fun storeChaos(chaos: RbtChaosEvaluator.Result?) {
                work.lastRbtChaosEvaluation = chaos
            }
            override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun delta() = this@DetermineBasalaimiSMB2.delta
            override fun nowMs() = dateUtil.now()
            override fun latentState() = causalState.lastPhysioLatentState
            override fun recentNadir(minutes: Int) = this@DetermineBasalaimiSMB2.minBgInLastMinutes(minutes)
            override fun predictionAvailable() = work.lastPredictionAvailable
            override fun phaseOutput() = work.lastPhysiologicalPhaseOutput
            override fun patternSnapshot() = work.lastPhysiologicalPatternSnapshot
            override fun hypothesisState() = causalState.lastUamHypothesisState
            override fun patientState() = work.lastPatientState
            override fun patientModeDecision() = work.lastPatientModeDecision
            override fun safetyRisk() = lastSafetyRiskExport
            override fun treeInsulinIntent() = work.lastPhysiologicalTreeSnapshot?.insulinIntent ?: InsulinIntent.NONE
            override fun treeInsulinUrgency() = work.lastPhysiologicalTreeSnapshot?.insulinUrgency ?: 0.0
            override fun mealDeliveryPriority() = work.lastMealAbsorptionOutput?.mealDeliveryPriority == true
            override fun storeAuthority(decision: RecursiveBeliefAuthorityGate.Decision) {
                work.lastRecursiveAuthorityGateDecision = decision
            }
            override fun storeHints(hints: RbtResolutionBridge.AppliedHints) {
                work.lastRbtAppliedHints = hints
            }
            override fun appliedHints() = work.lastRbtAppliedHints
            override fun merge(
                htr: HyperTrajectoryReleaseResult,
                rbtSnapshot: RecursiveBeliefSnapshot?,
                authorityGate: RecursiveBeliefAuthorityGate.Decision,
                rT: RT,
            ) = mergeRbtHyperTrajectoryRelease(htr, rbtSnapshot, authorityGate, rT)
            override fun storeCommit(result: RbtLiveCommitResult) {
                work.lastRbtLiveCommitResult = result
            }
        },
    )

    /**
     * Merge RBT authority and the hyper-trajectory release. The decision is [decideRbtMerge].
     * Caps, the pattern hold and the binding draft are read at the line.
     * `rT` stays on the signature: the body does not read it.
     */
    private fun mergeRbtHyperTrajectoryRelease(
        htr: HyperTrajectoryReleaseResult,
        rbtSnapshot: RecursiveBeliefSnapshot?,
        authorityGate: RecursiveBeliefAuthorityGate.Decision,
        rT: RT,
    ): RbtLiveCommitResult = decideRbtMerge(
        htr = htr,
        rbtSnapshot = rbtSnapshot,
        authorityGate = authorityGate,
        consoleLog = work.consoleLog,
        calls = object : AimiRbtMergeCalls {
            override fun physioCapU() = work.lastPhysiologicalPhaseOutput?.policy
                ?.takeIf { it.capsHtrRelease() }
                ?.smbFloorCapU
            override fun stackingCapU() = work.lastInsulinStackingEvaluation?.takeIf {
                it.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB
            }?.smbAbsoluteCapU
            override fun patternCapU() = patternCapHold.resolve(
                rawCapU = work.lastPhysiologicalPatternSnapshot?.smbCapU,
                rising = delta > 0f,
                rawKind = work.lastPhysiologicalPatternSnapshot?.smbCapKind,
            )
            override fun patternHolding() = patternCapHold.holding
            override fun softPatternProposalU() = work.lastPhysiologicalPatternSnapshot?.softProposedCapU()
            override fun patternActiveLabel() = work.lastPhysiologicalPatternSnapshot?.active
                ?.joinToString(separator = "+") { it.id.name }
                ?.takeIf { it.isNotEmpty() }
            override fun bindingDraft() = work.lastSmbBindingTraceDraft
            override fun setBindingDraft(value: app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace.Draft) {
                work.lastSmbBindingTraceDraft = value
            }
            override fun ignoreMinPredictedCurve() = work.lastRbtAppliedHints?.ignoreMinPredictedCurve == true
            override fun storeEffective(effective: HyperTrajectoryReleaseResult) {
                work.lastHyperTrajectoryRelease = effective
            }
        },
    )

    private fun deliverV3SmbFromRbt(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        hypoThresholdMgdl: Double,
        v3CommandSafe: Boolean,
        adCommandReason: String?,
        rbtCommit: RbtLiveCommitResult?,
    ) {
        val effectiveHtr = rbtCommit?.effectiveHtr ?: work.lastHyperTrajectoryRelease ?: return
        val rbtAuthority = rbtCommit?.rbtAuthority == true
        val liftedV3Smb = effectiveHtr.v3SmbAfterU
        if (liftedV3Smb > 0.0) {
            val v3Reason = if (v3CommandSafe) adCommandReason ?: "Autodrive V3" else "V3 unsafe"
            val htrReason = if (effectiveHtr.active) {
                "Autodrive V3+HTR: $v3Reason | 🚀 ${effectiveHtr.reason}"
            } else {
                "Autodrive V3: $v3Reason"
            }
            finalizeAndCapSMB(
                rT = rT,
                proposedUnits = liftedV3Smb,
                reasonHeader = htrReason,
                mealData = ctx.mealData,
                hypoThreshold = hypoThresholdMgdl,
                isExplicitUserAction = false,
                decisionSource = if (effectiveHtr.active) {
                    if (rbtAuthority) "AutodriveV3+RBT" else "AutodriveV3+HTR"
                } else {
                    "AutodriveV3"
                },
                hyperReleaseFloorU = if (effectiveHtr.active) effectiveHtr.smbFloorU else 0.0,
            )
        } else if (effectiveHtr.active) {
            work.consoleLog.add("🚀 HTR active but lifted SMB=0 (IOB/headroom); floor=${aimiFmt2(effectiveHtr.smbFloorU)}U")
        }
    }

    private fun evaluateHyperTrajectoryRelease(
        v3SmbU: Double,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        rbtLeafOnly: Boolean = false,
    ): HyperTrajectoryReleaseResult {
        val htrPrefs = htrPreferences()
        val (floorTerminal, bestTerminal) = resolveHtrScenarioTerminals(rT)
        if (htrPrefs.masterEnabled) {
            updateHyperDwellAboveHighBgClock()
        }
        val isNight = work.hourOfDay >= 23 || work.hourOfDay < 6
        val physioOut = work.lastPhysiologicalPhaseOutput
        val mealOut = work.lastMealAbsorptionOutput
        val effectiveRisk = if (physioOut != null && mealOut != null) {
            BehavioralRiskPolicy.effectiveForHtr(
                physiologicalPhase = physioOut.phase,
                mealAbsorptionPhase = mealOut.phase,
                confidence = physioOut.confidence,
                reason = physioOut.policy.reason,
            )
        } else {
            physioOut?.policy
        }
        return HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = htrPrefs.masterEnabled && !rbtLeafOnly,
                bgMgdl = bg,
                targetBgMgdl = targetBg.toDouble(),
                highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
                deltaMgdlPer5 = delta.toDouble(),
                shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
                combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
                floorTerminalMgdl = floorTerminal,
                bestTerminalMgdl = bestTerminal,
                tdd24hU = tdd24hU,
                iobU = work.iob.toDouble(),
                maxIobU = work.maxIob,
                maxSmbEffectiveU = work.maxSMBHB.coerceAtLeast(work.maxSMB),
                v3SmbU = v3SmbU,
                dwellAboveHighBgMinutes = dwellAboveHighBgMinutes(),
                trajectoryType = trajectoryGuard.getLastAnalysis()?.classification,
                minPredictedBgMgdl = minPredictedAcrossCurves(rT.predBGs),
                aggressive = htrPrefs.aggressive,
                isNight = isNight,
                exerciseLockout = work.exerciseInsulinLockoutActive,
                mealCobG = work.cob.toDouble(),
                establishedDevOverrideMgdl = htrPrefs.establishedDevOverrideMgdl,
                deepDevOverrideMgdl = htrPrefs.deepDevOverrideMgdl,
                behavioralRisk = effectiveRisk,
                mealAbsorptionPhase = mealOut?.phase ?: MealAbsorptionPhase.NONE,
                gapPrevMgdl = MealAbsorptionMemory.lastGapMgdl,
            ),
        )
    }

    private fun HyperTrajectoryReleaseResult.toDecisionContextExport(): AimiDecisionContext.HyperTrajectoryReleaseExport {
        val target = targetBg.toDouble()
        val bestT = lastScenarioProjection?.scenarioBest?.terminalMgdl ?: bg
        return AimiDecisionContext.HyperTrajectoryReleaseExport(
            active = active,
            tier = tier.name,
            dev_above_target_mgdl = bg - target,
            projected_dev_mgdl = bestT - target,
            severity_weight = severityWeight,
            absorption_offset_mgdl = absorptionOffsetMgdl,
            smb_floor_u = smbFloorU,
            v3_smb_before_u = v3SmbBeforeU,
            v3_smb_after_u = v3SmbAfterU,
            suppress_traj_basal_shift = suppressTrajBasalShift,
            hypo_min_pred_ignored = hypoMinPredIgnored,
            reason = reason,
        )
    }

    /**
     * Traj-Bridge runs before [runAdvancedPredictionsAndPredPipePrep]; apply deferred basal after HTR.
     */
    private fun applyPendingTrajSpiralBasalIfNotSuppressed(
        rT: RT,
        bg: Double,
        delta: Float,
    ) {
        val pending = work.pendingTrajSpiralBasal ?: return
        work.pendingTrajSpiralBasal = null
        if (work.lastHyperTrajectoryRelease?.suppressTrajBasalShift == true) {
            work.consoleLog.add("🌀 HTR/RBT: deferred Traj-Bridge basal skipped — ${pending.reason}")
            return
        }
        val tbrFrac = work.lastRecursiveBeliefSnapshot?.resolutions?.tbrDemandFraction ?: 1.0
        rT.rate = pending.proactiveBasalUph * tbrFrac
        rT.duration = pending.durationMin
        rT.reason.append(" | 🌀 Traj-Bridge: ${pending.reason}")
        work.lastSafetySource = pending.safetyTierLabel
        logDecisionFinal("TRAJ_SAFETY", rT, bg, delta)
    }

    private fun sanitizedHypoGuardPredictedEventual(
        rT: RT,
        predictedBg: Double,
        eventualBg: Double,
    ): Pair<Double, Double> =
        HyperTrajectoryHypoCredibility.sanitizeTerminalsForHypoGuard(
            bgMgdl = bg,
            predictedBgMgdl = predictedBg,
            eventualBgMgdl = eventualBg,
            minPredictedBgMgdl = minPredictedAcrossCurves(rT.predBGs),
            targetBgMgdl = targetBg.toDouble(),
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            scenarioBestTerminalMgdl = lastScenarioProjection?.scenarioBest?.terminalMgdl,
            deltaMgdlPer5 = delta.toDouble(),
        )

    /**
     * Autodrive V3 (MPC): preference [BooleanKey.OApsAIMIautoDriveActive], [autodriveGater.shouldEngageV3], engine tick, TBR + SMB when safe.
     *
     * When [BooleanKey.OApsAIMIautoDriveAuthoritative] is on and a safe V3 command is applied,
     * [skipLegacySmbBlender] is true so the legacy MPC/PI path does not overwrite V3 SMB.
     *
     * @param hypoThresholdMgdl same as `threshold` after [HypoThresholdMath.computeHypoThreshold] in [determine_basal] (LGS guard for MPC `lgsThreshold` cap).
     */
    /**
     * Opt-in absorption of the former classic-autodrive aggressive-rise SMB. Returns the user-defined
     * Autodrive prebolus floor for the current rise (Large tier ≥5 rise & ≥3 avg, else Small tier ≥2
     * rise), or 0.0 when the floor is disabled, BG is below 120, or the rise is too weak. The caller
     * applies it as `max(modelSmb, floor)` and re-bounds it with all Autodrive V3 safety caps.
     */
    private fun aggressiveRiseSmbFloorU(bgMgdl: Double, riseSignal: Float, shortAvgDelta: Float): Double {
        if (!preferences.get(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor)) return 0.0
        if (bgMgdl < 120.0) return 0.0
        val largePrebolus = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val smallPrebolus = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val tierFloor = when {
            riseSignal >= 5.0f && shortAvgDelta >= 3.0f -> largePrebolus
            riseSignal >= 2.0f                          -> smallPrebolus
            else                                        -> 0.0
        }.coerceAtLeast(0.0)
        if (tierFloor <= 0.0) return 0.0
        // No episode budget here — see [remainingRiseFloorBudgetU] for why it was removed after a
        // measured hyperglycaemia.
        return tierFloor
    }

    /**
     * Removed from the dose path on 2026-08-10. Kept for the export and for the record.
     *
     * ## Why it was removed
     *
     * It capped the floor at one prebolus per rise. Measured on two consecutive undeclared lunches,
     * same patient, no meal mode, COB 0:
     *
     * | | 2026-08-09, no budget | 2026-08-10, with budget |
     * |---|---|---|
     * | peak BG | 225 | **268.7** |
     * | SMB at peak | 14.06 U | **6.53 U** |
     *
     * The design error: the floor was not only a prebolus, it was **carrying the whole meal**. The
     * governed path was not. `harmonia_smb_authority.insulin_intent` was `PROTECTIVE` on every tick of
     * the rise — at BG 269 with Ra 5.33 — and that branch of `HarmoniaSmbArbiter` can only accept or
     * reduce, so `LIFT_WITHIN_ENVELOPE` was unreachable. Removing the bypass without first checking
     * that the legitimate path could carry the load produced the hyperglycaemia.
     *
     * The real brake is elsewhere and is already in place: the `iobSafe < budget` guard on
     * `ESCAPE_RISE` takes the load governor out of `FULL` at the physiological budget, which would
     * have stopped 2026-08-09 at IOB 9.04 instead of 16.75. It bounds the accumulation without
     * starving the start of a meal, which a per-rise budget cannot do.
     *
     * Do not reinstate it until Harmonia's intent switches to `MEAL_SUPPORT` on a confirmed meal —
     * that is, until the governed path can carry a meal on its own.
     *
     * ## What it computed
     *
     * The floor is a **prebolus**: it exists to put insulin in before a rise is visible in the model.
     * A prebolus is served once. This one had no memory, so it re-armed on every tick and, at a
     * 1.6 U ceiling on 5-minute ticks, behaved as a **19 U/h floor** for as long as the rise held.
     *
     * Measured on 2026-08-09: it delivered 1.088 U per tick for six consecutive ticks starting at
     * 14:41, while the MPC was asking for exactly 0.000 and the safety barrier was permitting exactly
     * 0.000. Peak IOB 16.75 U against a physiological budget of 8.11. The patient needed rescue carbs.
     *
     * One large prebolus per rise is the whole intent. The budget re-arms only on a fresh onset —
     * defined as the floor having been idle for [RISE_FLOOR_REARM_MS], which is long enough that a
     * second wave of the same meal does not qualify.
     */
    private fun remainingRiseFloorBudgetU(largePrebolusU: Double): Double {
        val now = dateUtil.now()
        val budget = largePrebolusU.coerceAtLeast(0.0)
        if (budget <= 0.0) return 0.0
        if (now - causalState.lastRiseFloorContributionMs > RISE_FLOOR_REARM_MS) {
            causalState.riseFloorSpentU = 0.0
        }
        return (budget - causalState.riseFloorSpentU).coerceAtLeast(0.0)
    }

    /**
     * Records what the floor actually contributed, so the budget above can be spent down.
     *
     * Called with the amount by which the floor raised the dose above the model — not with the whole
     * dose. Insulin the model asked for is not the floor's doing and must not consume its budget.
     */
    private fun noteRiseFloorContribution(contributedU: Double) {
        if (contributedU <= 0.0) return
        causalState.riseFloorSpentU += contributedU
        causalState.lastRiseFloorContributionMs = dateUtil.now()
    }

    /**
     * Minimal state for **observation only** — the fields [ContinuousStateEstimator] actually reads.
     *
     * ## Why this exists
     *
     * `updateAndPredict` is the only update path of the meal model, and its single call site sits
     * inside `AutodriveEngine.tick()`, itself behind `if (gate.engage)`. Measured on 2026-08-08 over
     * 282 ticks: `Ra` changed on 81 transitions (81 % of them with Autodrive owning the dose) and was
     * frozen on 200 (7 %), the longest freezes running 37, 32, 23 and 21 consecutive ticks — up to
     * three hours. An estimator that only advances when a controller engages is not an estimator, and
     * the decay added to it could not work because the function was never called.
     *
     * ## Why a separate builder rather than hoisting `adState`
     *
     * The engaged path rebuilds physiology (`refreshPhysiologicalPhase`, `refreshMealAbsorptionPhase`,
     * `updatePhysioLatentState`) inside the gate, but those already ran unconditionally earlier in the
     * tick. Hoisting them would fire their persisted side effects — TPO episode ledger, circadian dawn
     * learning, meal-absorption wave counting — on every tick instead of engaged ones. This builder
     * reads the values they already published instead.
     *
     * ## Contract
     *
     * Returns `null` when the tick cannot be observed. It deliberately does **not** lean on
     * `AutoDriveState.createSafe`'s catch-all, which turns any failure into `bg = 100, velocity = 0,
     * iob = 0` — a fabricated "quiet" observation is exactly the input that would drive `Ra` to zero
     * for no reason.
     *
     * Nothing here mutates learner state: `learnAndUpdate` and `applyAttention` change values that
     * engaged ticks depend on. See `docs/adr/0008-isf-decision-architecture.md`.
     */
    private fun buildRaObservationState(
        ctx: AimiTickContext,
        combinedDelta: Float,
        shortAvgDeltaAdj: Float,
        pkpdRuntime: PkPdRuntime?,
        hasRecentMealEstimate: Boolean,
    ): app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState? =
        decideBuildRaObservationState(
            ctx = ctx,
            combinedDelta = combinedDelta,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            pkpdRuntime = pkpdRuntime,
            hasRecentMealEstimate = hasRecentMealEstimate,
            preferences = preferences,
            calls = object : AimiRaObservationCalls {
                override fun variableSensitivity() = this@DetermineBasalaimiSMB2.variableSensitivity
                override fun mealTime() = work.mealTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun snackTime() = work.snackTime
                override fun stressMask() = causalState.lastPhysioLatentState?.toAttentionMask() ?: DoubleArray(0)
                override fun hourOfDay() = work.hourOfDay
                override fun stepsLast15m() = physioAdapter.getLatestSnapshot().stepsLast15m
                override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
                override fun postHypoRecoveryActive() = this@DetermineBasalaimiSMB2.postHypoRecoveryActive()
                override fun extendedDawnGuard() = work.lastPhysiologicalPhaseOutput?.policy?.extendedDawnGuard == true
                override fun logObservationFailed(typeName: String?, message: String?) {
                    work.consoleLog.add("RA observation failed ($typeName): $message — state null")
                }
            },
        )

    /**
     * Records one training row for a tick where Autodrive did **not** engage.
     *
     * The attention classifier answers a physiological question — given this state, what is the risk
     * of hypoglycaemia within the hour — and that does not depend on whether a controller engaged.
     * Recording only engaged ticks biased the set twice over: `AutoDriveGater` selects high or rising
     * glucose, where hypoglycaemia is rarer than in reality, on top of a positive rate already around
     * 3 %. Worse, the subset is defined by a gate that keeps being retuned, so the training
     * distribution moved every time the policy did.
     *
     * The row carries `engaged = 0` and neutral decision columns, so the model can condition on
     * engagement explicitly instead of the dataset being filtered by it in silence.
     */
    /**
     * Identity of the observation the meal model is allowed to consume once.
     *
     * The CGM sample time, not `ctx.currentTime`. `determine_basal` can be invoked more than once for
     * the same sample — measured on the production corpus: 2610 consecutive dataset rows carry an
     * identical `BG_Current` **and** `BG_Velocity` less than 60 s apart, and `Estimated_Ra` moved
     * between them on 1974 of those (76 %), by more than 0.2 mg/dL/min on 405, up to 2.83. Keying on
     * the invocation would let every one of those advance the filter again on evidence it has already
     * used.
     *
     * Falls back to the invocation time when the sample carries no date, so the guard degrades to
     * "once per invocation" rather than to "never".
     */
    /**
     * Writes the hyper-trajectory Ra floor, and the Ra the controller used, into this tick's export.
     *
     * Called from inside the engaged branch, because that is where the two values first exist. The
     * export object is built at tick bootstrap, so reading them there — as the fields used to do —
     * gave `null` for the floor and the previous tick's estimate for Ra.
     */
    /** Poses estimator counters and the aligned-tau shadow on [decisionCtx] before it is serialised. */
    /**
     * Owners allowed to raise the dose after the terminal has sealed it.
     *
     * The meal advisor is a user-initiated action, not a loop decision, and it deliberately bypasses
     * `finalizeAndCapSMB` — the comment at its call site says so. It stays allowed, but it is now
     * counted and logged instead of being indistinguishable from a defect.
     */
    private val smbPostSealRaiseAllowedOwners = setOf("MealAdvisor")

    /**
     * The one way to write the tick's SMB.
     *
     * ## Why this exists
     *
     * `finalizeAndCapSMB` is meant to be the terminal: every cap, guard and budget converges there.
     * It was not the last word. Five places wrote `rT.units`, and three of them ran after it — the AI
     * auditor, and two legacy meal-mode paths. The auditor's own prompt says its role is
     * *"CONFIRM or SOFTEN only — never invent a lift"*; nothing in the code enforced that, so it could
     * write any value straight over the terminal's result.
     *
     * That is the same shape as the two other defects found on 2026-08-09: a constraint stated in
     * documentation and absent from the code. Here it becomes structural — after the seal, a write may
     * only ever **lower** the dose, unless its owner is on [smbPostSealRaiseAllowedOwners].
     *
     * Refusals are counted and exported rather than silently swallowed, because a refused raise means
     * a component disagreed with the terminal and that is worth seeing.
     *
     * Note what this does **not** cover: the aggressive-rise floor feeds `finalizeAndCapSMB` as an
     * input, so it is upstream of the seal. Its problem is that it bypasses `ControlBarrierShield`,
     * which is a different boundary.
     */
    private fun applySmbUnits(rT: RT, requestedU: Double, owner: String) {
        val requested = if (requestedU.isFinite()) requestedU.coerceAtLeast(0.0) else 0.0
        AimiEffectProbe.noteSmb(requested, owner)
        val current = rT.units ?: 0.0
        if (!work.smbTerminalSealed || requested <= current + 1e-9) {
            rT.units = requested
            return
        }
        if (owner in smbPostSealRaiseAllowedOwners) {
            work.smbSealAllowedRaiseCount++
            work.consoleLog.add("🔓 SMB_SEAL_EXCEPTION[$owner]: ${aimiFmt2(current)} -> ${aimiFmt2(requested)} U")
            rT.units = requested
            return
        }
        work.smbSealRefusedCount++
        work.smbSealRefusedTotalU += requested - current
        work.consoleError.add(
            "🔒 SMB_SEAL_REFUSED[$owner]: tentative ${aimiFmt2(current)} -> ${aimiFmt2(requested)} U " +
                "apres le terminal ; la dose reste a ${aimiFmt2(current)} U"
        )
    }

    /** Closes the terminal for this tick. Called once, at the end of `finalizeAndCapSMB`. */
    private fun sealSmbTerminal() {
        work.smbTerminalSealed = true
    }

    private fun markEstimatorDiagnosticsForExport(decisionCtx: AimiDecisionContext) {
        runCatching {
            decisionCtx.baseline_state.let { b ->
                b.ra_estimator_advances = continuousStateEstimator.runCount
                b.ra_estimator_replayed_calls = continuousStateEstimator.replayedCallCount
                b.ra_aligned_tau_shadow_mgdl_per_min = continuousStateEstimator.lastRaAlignedTauShadow
                b.smb_seal_refused_count = work.smbSealRefusedCount
                b.smb_seal_refused_total_u = work.smbSealRefusedTotalU.takeIf { it > 0.0 }
                b.smb_seal_allowed_raise_count = work.smbSealAllowedRaiseCount
                // Written here, immediately before serialisation, because this is the one point every
                // export path goes through — the mistake that made `ra_estimator_advances` reach
                // 7 ticks out of 93 (Part A-bis correction 1).
                b.effort_smb_factor_requested = work.lastEffortSmbFactorRaw
                b.effort_smb_factor_applied = work.lastEffortSmbFactorApplied
                b.effort_smb_before_u = work.lastEffortSmbBeforeU
                b.effort_smb_after_u = work.lastEffortSmbAfterU
                b.effort_smb_floored_by_meal = work.lastEffortSmbFactorRaw?.let { raw ->
                    work.lastEffortSmbFactorApplied?.let { applied -> applied > raw + 1e-9 }
                }
                b.variable_sens_mgdl = variableSensitivity.toDouble().takeIf { it.isFinite() && it > 0.0 }
                // Written here for the same reason as the block above: this is the one point every
                // export path goes through, and the floor is applied thousands of lines after the
                // decision context is built.
                WorkingIsf.lastApplied?.let { applied ->
                    b.stress_floor_isf_before_mgdl = applied.beforeMgdlPerU.takeIf { it.isFinite() }
                    b.stress_floor_isf_after_mgdl = applied.afterMgdlPerU.takeIf { it.isFinite() }
                    b.stress_floor_raised_isf = applied.raised
                }
                // The before/after pair above measures the late application only. A tick can be
                // floored early — the call that guards the AutodriveV3 fallback — and never reach
                // the late one, so without this the export would say the floor changed nothing on
                // exactly the ticks where it changed a bolus.
                if (WorkingIsf.raisedEarly) b.stress_floor_raised_isf = true
                b.rise_floor_spent_u = causalState.riseFloorSpentU
                b.rise_floor_minutes_since_contribution =
                    causalState.lastRiseFloorContributionMs
                        .takeIf { it > 0L }
                        ?.let { (dateUtil.now() - it) / 60000.0 }
            }
        }
    }

    private fun markHtrRaFloorForExport(floorMgdlPerMin: Double?, raUsedMgdlPerMin: Double) {
        val baseline = work.pendingDecisionCtxForExport?.baseline_state ?: return
        baseline.htr_ra_floor_mgdl_per_min = floorMgdlPerMin
        baseline.estimated_ra_used_mgdl_per_min = raUsedMgdlPerMin.takeIf { it.isFinite() }
        baseline.cbf_coefficient_used = autodriveEngine.lastControlCoefficientUsed.takeIf { it > 0.0 }
        baseline.cbf_coefficient_unfloored = autodriveEngine.lastControlCoefficientUnfloored.takeIf { it > 0.0 }
        baseline.cbf_permitted_u = autodriveEngine.lastCbfPermittedU.takeIf { it.isFinite() }
        baseline.cbf_permitted_unfloored_u = autodriveEngine.lastCbfPermittedUnflooredU.takeIf { it.isFinite() }
        baseline.cbf_profile_isf_mgdl = autodriveEngine.lastProfileIsfSeen.takeIf { it > 0.0 }
        work.pendingDecisionCtxForExport?.adjustments?.control_barrier = buildControlBarrierExport()
    }

    /**
     * What the solver asked for and what the barrier did with it.
     *
     * `smb_binding_trace.model_output_u` is read **after** the barrier, so a 0.000 there was being
     * used to conclude "the controller asked for nothing". On the 2026-08-14 lunch that reading was
     * wrong: the barrier suspended the dose completely (`cbf_permitted_u` 0.000 from 13:52 to the end
     * of the meal) because its own `lfh` reached -11.2 mg/dL/min — a predicted fall of 56 mg/dL per
     * 5 minutes against a measured 9. The two cases need opposite fixes, so they are now separated.
     */
    private fun buildControlBarrierExport(): JsonObject? {
        val d = autodriveEngine.lastBarrierDiagnostics ?: return null
        return JsonObj().apply {
            put("mpc_raw_smb_u", autodriveEngine.lastMpcRawSmbU)
            put("mpc_raw_tbr_uph", autodriveEngine.lastMpcRawTbrUph)
            put("h_mgdl", d.hMgdl)
            put("lfh_mgdl_per_min", d.lfhMgdlPerMin)
            put("lgh_mgdl_per_u_per_min", d.lghMgdlPerUPerMin)
            put("insulin_term_mgdl_per_min", d.insulinTermMgdlPerMin)
            put("active_gamma", d.activeGamma)
            put("safety_boundary", d.safetyBoundary)
            put("system_evolution", d.systemEvolution)
            put("si_metabolic", d.siMetabolic)
            put("fully_suspended", d.fullySuspended)
            put("anchor_is_dynamic_isf", d.anchorIsDynamicIsf)
            d.safeU?.let { put("safe_u", it) }
        }.build()
    }

    private fun raObservationId(ctx: AimiTickContext): Long =
        ctx.glucoseStatus.date.takeIf { it > 0L } ?: ctx.currentTime

    private fun stageDisengagedTrainingRow(
        ctx: AimiTickContext,
        state: app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState,
    ) {
        runCatching {
            autodriveEngine.stageDisengagedSnapshot(state, tickId = ctx.currentTime, currentEpochMs = dateUtil.now())
        }
    }

    /** Runs the meal-model estimator for this tick when no other path has. Observation only. */
    private fun observeRaIfNotAlreadyRun(
        ctx: AimiTickContext,
        combinedDelta: Float,
        shortAvgDeltaAdj: Float,
        pkpdRuntime: PkPdRuntime?,
        hasRecentMealEstimate: Boolean,
        reason: String,
    ) {
        val before = continuousStateEstimator.runCount
        if (before != work.raEstimatorRunCountAtTickStart) return // something already observed this tick
        val state = buildRaObservationState(ctx, combinedDelta, shortAvgDeltaAdj, pkpdRuntime, hasRecentMealEstimate)
            ?: return
        val observed = runCatching {
            continuousStateEstimator.updateAndPredict(state, tickId = raObservationId(ctx))
        }.getOrNull() ?: state
        work.consoleLog.add("🍽️ RA_OBSERVE[$reason]: Ra=${aimiFmt2(continuousStateEstimator.getLastRa())}")
        stageDisengagedTrainingRow(ctx, observed)
    }

    internal fun runAutodriveV3MultiVariableBranch(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        combinedDelta: Float,
        shortAvgDeltaAdj: Float,
        hypoThresholdMgdl: Double,
        pkpdRuntime: PkPdRuntime?,
    ): AutodriveV3BranchResult {
        val state = AutodriveV3TickState(
            mealTime = work.mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = work.snackTime,
            variableSensitivity = variableSensitivity,
            hourOfDay = work.hourOfDay,
            exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
            causalState.adaptiveMult = causalState.adaptiveMult,
            maxIob = work.maxIob,
            iob = work.iob,
            maxSmb = work.maxSMB,
            maxSmbHb = work.maxSMBHB,
            postHypo = work.lastPostHypoDeliveryAuthority,
            mealChannelHint = work.lastRbtAppliedHints?.mealChannel,
            basePhysioMultipliers = work.lastBasePhysioMultipliers,
            eventualBg = work.eventualBG,
            targetBg = targetBg,
            delta = delta,
            smbBindingDraft = work.lastSmbBindingTraceDraft,
        )
        return decideAutodriveV3(
            ctx = ctx,
            profile = profile,
            rT = rT,
            bg = bg,
            combinedDelta = combinedDelta,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            hypoThresholdMgdl = hypoThresholdMgdl,
            pkpdRuntime = pkpdRuntime,
            preferences = preferences,
            dateUtil = dateUtil,
            consoleLog = work.consoleLog,
            gater = AimiAutodriveGater { bg, combined, work.cob, uamConfidence, explicitMeal, recentMeal, minBg75, ra, mealChannel ->
                autodriveGater.shouldEngageV3(
                    bg = bg,
                    combinedDelta = combined,
                    cob = work.cob,
                    uamConfidence = uamConfidence,
                    explicitMealMode = explicitMeal,
                    hasRecentMealEstimate = recentMeal,
                    minBgLookback75m = minBg75,
                    estimatedRa = ra,
                    mealChannelHint = mealChannel,
                )
            },
            estimatedRa = AimiEstimatedRa { continuousStateEstimator.getLastRa() },
            debug = AimiAutodriveDebug { message -> aapsLogger.debug(LTag.APS, message) },
            engine = autodriveEngine,
            physio = object : AimiPhysioTick {
                override fun getLastDecisionTrace() = physioAdapter.getLastDecisionTrace()
                override fun getEffectiveContext() = physioAdapter.getEffectiveContext()
                override fun getLatestSnapshot() = physioAdapter.getLatestSnapshot()
            },
            effects = legacyEffectSink,
            state = state,
            writes = object : AimiAutodriveTickWrites {
                override fun setRaNetDeltas(combinedDelta: Float, shortAvgDeltaAdj: Float) {
                    work.raNetCombinedDelta = combinedDelta
                    work.raNetShortAvgDeltaAdj = shortAvgDeltaAdj
                }
                override fun noteAutodriveGate(engaged: Boolean, kindName: String, reason: String) {
                    work.pendingDecisionCtxForExport?.baseline_state?.let { baseline ->
                        baseline.autodrive_gate_engaged = engaged
                        baseline.autodrive_gate_kind = kindName
                        baseline.autodrive_gate_reason = reason
                    }
                }
                override fun setAutodriveEngaged() {
                    causalState.lastAutodriveState = AutodriveState.ENGAGED
                }
                override fun setLastHtrRaFloorMgdlPerMin(floor: Double?) {
                    work.lastHtrRaFloorMgdlPerMin = floor
                }
                override fun setSmbBindingDraft(draft: SmbBindingTrace.Draft) {
                    work.lastSmbBindingTraceDraft = draft
                }
                override fun setPostHypoSmbBeforeCapU(units: Double) {
                    work.lastPostHypoSmbBeforeCapU = units
                }
                override fun setPostHypoSmbAfterCapU(units: Double) {
                    work.lastPostHypoSmbAfterCapU = units
                }
            },
            uam = AimiUamConfidence { AimiUamHandler.confidenceOrZero() },
            fcl = AimiFclDeclared { fclProfile -> fclDeclaredThisTick(fclProfile) },
            minBg = AimiMinBgLookback { lookback -> minBgInLastMinutes(lookback) },
            postHypoRecovery = AimiPostHypoRecovery { postHypoRecoveryActive() },
            tdd24h = AimiTdd24h { resolveTdd24hForExport() },
            phase = AimiPhysiologicalPhase { rt, deltaNow, steps, hr, rhr, base ->
                refreshPhysiologicalPhase(rt, deltaNow, steps, hr, rhr, base)
                work.lastPhysiologicalPhaseOutput
            },
            mealSafety = AimiMealSafety { explicit, iobData -> buildMealSafetyContext(explicit, iobData) },
            absorption = AimiMealAbsorption { deltaNow, steps, hr, rhr, mealContext, lastBolus, nowMs ->
                refreshMealAbsorptionPhase(deltaNow, steps, hr, rhr, mealContext, lastBolus, nowMs)
            },
            latent = AimiPhysioLatentUpdate { snapshot, source -> updatePhysioLatentState(snapshot, source) },
            hyper = AimiHyperSeverity { rt, deltaNow, tdd -> classifyHyperSeverityForTick(rt, deltaNow, tdd) },
            htrTerminals = AimiHtrTerminals { rt -> resolveHtrScenarioTerminals(rt) },
            basalCap = AimiBasalCap { requested, basal, source ->
                capBasalRateForCorrectionAggression(requested, basal, source)
            },
            riseFloor = AimiAggressiveRiseFloor { bgMgdl, rise, shortAvg ->
                aggressiveRiseSmbFloorU(bgMgdl, rise, shortAvg)
            },
            riseNote = AimiRiseFloorNote { contributed -> noteRiseFloorContribution(contributed) },
            patient = AimiPatientStateRefresh { nowMs, health, source, refreshSource ->
                refreshPatientStateRuntime(
                    nowMs = nowMs,
                    healthSnapshot = health,
                    sourceSensor = source,
                    refreshSource = refreshSource,
                )
            },
            terminal = AimiDoseTerminal { rt, doseProfile, mealData, eventual, pred, target, stage ->
                publishDoseTerminalAuthorityAndSnapshot(rt, doseProfile, mealData, eventual, pred, target, stage)
            },
            rbt = AimiRbtLive { tickCtx, rbtProfile, rt, deltaNow, tdd, smb, steps, hr, gateOpen, ra, shield ->
                resolveAndWireRbtLiveTick(
                    ctx = tickCtx,
                    profile = rbtProfile,
                    rT = rt,
                    combinedDelta = deltaNow,
                    tdd24hU = tdd,
                    v3SmbU = smb,
                    stepsLast15m = steps,
                    heartRateBpm = hr,
                    autodriveGateOpen = gateOpen,
                    mpcFeedForwardRa = ra,
                    cbfShieldDeltaU = shield,
                )
            },
            smbDelivery = AimiV3SmbDelivery { tickCtx, smbProfile, rt, hypo, safe, reason, commit ->
                deliverV3SmbFromRbt(tickCtx, smbProfile, rt, hypo, safe, reason, commit)
            },
            raObserve = AimiRaObservation { tickCtx, deltaNow, shortAvg, pkpd, recentMeal, reason ->
                observeRaIfNotAlreadyRun(tickCtx, deltaNow, shortAvg, pkpd, recentMeal, reason)
            },
            htrExport = AimiHtrExport { floor, ra -> markHtrRaFloorForExport(floor, ra) },
            decisionLog = AimiDecisionLog { tag, rt, loggedBg, loggedDelta ->
                logDecisionFinal(tag, rt, loggedBg, loggedDelta)
            },
        )
    }

    /**
     * Compression protection stop, then Drift Terminator micro-SMB. **Does not** call [classifyPostHypoState] — the caller must run it
     * **once** per tick and pass [postHypoState] (used later in `determine_basal` for SMB disambiguation; double invocation would duplicate side effects).
     *
     * **Critical:** [isDriftTerminatorCondition] uses **raw** [shortAvgDeltaRawForDrift] (same as member [shortAvgDelta]), not BYODA-adjusted delta.
     *
     * @return [rT] when compression or drift path finalizes the tick; **null** to fall through to global AIMI.
     */
    private fun runPostHypoCompressionAndDriftTerminatorOrReturn(
        ctx: AimiTickContext,
        rT: RT,
        bg: Double,
        delta: Float,
        threshold: Double,
        combinedDelta: Float,
        shortAvgDeltaRawForDrift: Float,
        targetBgMgdl: Float,
        postHypoState: PostHypoState,
        autosensRatio: Double,
        nightbis: Boolean,
        autodriveEnabledPref: Boolean,
        modesCondition: Boolean,
        hasRecentBolus45m: Boolean,
        totalBolusLastHour: Double,
        dynamicPbolusSmall: Double,
        exerciseInsulinLockoutActive: Boolean,
        reason: StringBuilder,
    ): RT? = decidePostHypoCompressionAndDriftTerminatorOrReturn(
        ctx = ctx,
        rT = rT,
        bg = bg,
        delta = delta,
        threshold = threshold,
        combinedDelta = combinedDelta,
        shortAvgDeltaRawForDrift = shortAvgDeltaRawForDrift,
        targetBgMgdl = targetBgMgdl,
        postHypoState = postHypoState,
        autosensRatio = autosensRatio,
        nightbis = nightbis,
        autodriveEnabledPref = autodriveEnabledPref,
        modesCondition = modesCondition,
        hasRecentBolus45m = hasRecentBolus45m,
        totalBolusLastHour = totalBolusLastHour,
        dynamicPbolusSmall = dynamicPbolusSmall,
        exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
        reason = reason,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiPostHypoDriftCalls {
            override fun compression(delta: Float, reason: StringBuilder) =
                isCompressionProtectionCondition(delta, reason)
            override fun drift(
                bg: Float,
                targetBg: Float,
                delta: Float,
                avgDelta: Float,
                combinedDelta: Float,
                minDeviation: Double,
                lastBolusVolume: Double,
                reason: StringBuilder,
            ) = isDriftTerminatorCondition(
                bg, targetBg, delta, avgDelta, combinedDelta, minDeviation, lastBolusVolume, reason,
            )
            override fun maxSmb() = work.maxSMB
            override fun writeMaxSmb(value: Double) {
                work.maxSMB = value
            }
            override fun finalize(
                rT: RT,
                proposedUnits: Double,
                reasonHeader: String,
                mealData: MealData,
                hypoThreshold: Double,
                isExplicitUserAction: Boolean,
                decisionSource: String,
                isMealActive: Boolean,
                hyperReleaseFloorU: Double,
                bypassSmbRefractory: Boolean,
            ) {
                finalizeAndCapSMB(
                    rT, proposedUnits, reasonHeader, mealData, hypoThreshold,
                    isExplicitUserAction, decisionSource, isMealActive, hyperReleaseFloorU, bypassSmbRefractory,
                )
            }
            override fun logFinal(tag: String, rT: RT, bg: Double, delta: Float) {
                logDecisionFinal(tag, rT, bg, delta)
            }
            override fun markFinal(rT: RT, currentTemp: CurrentTemp?) {
                markFinalLoopDecisionFromRT(rT, currentTemp)
            }
        },
    )


    private data class GlobalAimiBasalScheduleBootstrap(
        val pumpCaps: PumpCaps,
        val profileCurrentBasal: Double,
        val basal: Double,
        val targetBg: Double,
        val minBg: Double,
        val maxBg: Double,
        val sensitivityRatio: Double,
        val deliverAt: Long,
        val maxIobLimit: Double,
    )

    /**
     * Post-Autodrive slice: circadian minute, pump caps + validated profile basal, CGM reason lines,
     * temp targets / [sensitivityRatio], rounded basal with WCycle — through autosens adjustment of min/max/target_bg.
     */
    private fun buildGlobalAimiBasalScheduleBootstrap(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        contextTargetOverride: Double?,
        bg: Double,
        predictedBg: Float,
        combinedDelta: Float,
        minAgo: Double,
        systemTime: Long,
        bgTime: Long,
        flatBGsDetected: Boolean,
        honeymoon: Boolean,
        circadianMinute: Int,
        circadianSecond: Int,
    ): GlobalAimiBasalScheduleBootstrap {
        val schedule = decideBasalSchedule(
            ctx, profile, rT, glucoseStatus, contextTargetOverride, bg, predictedBg, combinedDelta,
            minAgo, systemTime, bgTime, flatBGsDetected, honeymoon, circadianMinute, circadianSecond,
            rh, work.consoleLog,
            calls = object : AimiBasalScheduleCalls {
                override fun maxSmb() = work.maxSMB
                override fun hourOfDay() = work.hourOfDay
                override fun pumpSteps(): Pair<Double, Double> {
                    val desc = activePlugin.activePump.pumpDescription
                    return desc.basalStep to desc.bolusStep
                }
                override fun validateBasal(rate: Double, caps: PumpCaps) =
                    pumpCapabilityValidator.validateBasal(rate, caps)
                override fun maxIob() = work.maxIob
                override fun recentSteps5() = recentSteps5Minutes
                override fun recentSteps10() = recentSteps10Minutes
                override fun recentSteps30() = recentSteps30Minutes
                override fun recentSteps180() = recentSteps180Minutes
                override fun setTargetBg(value: Float) { targetBg = value }
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
            },
        )
        return GlobalAimiBasalScheduleBootstrap(
            pumpCaps = schedule.pumpCaps,
            profileCurrentBasal = schedule.profileCurrentBasal,
            basal = schedule.basal,
            targetBg = schedule.targetBg,
            minBg = schedule.minBg,
            maxBg = schedule.maxBg,
            sensitivityRatio = schedule.sensitivityRatio,
            deliverAt = schedule.deliverAt,
            maxIobLimit = schedule.maxIobLimit,
        )
    }

    /**
     * After [buildGlobalAimiBasalScheduleBootstrap]: IOB profiler vs system log, display [tick] / delta aggregates,
     * carb ratio error line, activity steps (watch cache vs [StepService]), HR windows + optional ISF nudge from HR trend.
     * Mutates step/HR fields and possibly [variableSensitivity] — preserve call order vs downstream basalaimi / PAI logic.
     */
    private data class AimiPostBasalBootstrapActivityVitals(
        val tick: String,
        val minDelta: Double,
        val minAvgDelta: Double,
    )

    private fun runPostBasalBootstrapIobTickStepsAndHeartRate(
        glucoseStatus: GlucoseStatusAIMI,
        profile: OapsProfileAimi,
        iobData: IobTotal,
        bg: Double,
    ): AimiPostBasalBootstrapActivityVitals {
        val vitals = decideHeartRateIsf(
            glucoseStatus, profile, iobData, bg, preferences, work.consoleLog, work.consoleError,
            calls = object : AimiHeartRateIsfCalls {
                override fun iob() = work.iob
                override fun roundDisplay(value: Double) = round(value)
                override fun stepsCached(now: Long) = stepsCountsCached(now)
                override fun logSteps(samples: List<SC>) {
                    if (samples.isNotEmpty()) {
                        val lastSteps = samples.maxByOrNull { it.timestamp }
                        aapsLogger.debug(LTag.APS, "Steps Data: Found ${samples.size} records. Last: ${lastSteps?.steps5min} steps @ ${Instant.fromEpochMilliseconds(lastSteps?.timestamp ?: 0)}")
                    } else {
                        aapsLogger.debug(LTag.APS, "Steps Data: No records found in last 210 mins")
                    }
                }
                override fun setRecentSteps(steps5: Int, steps10: Int, steps15: Int, steps30: Int, steps60: Int, steps180: Int) {
                    recentSteps5Minutes = steps5
                    recentSteps10Minutes = steps10
                    recentSteps15Minutes = steps15
                    recentSteps30Minutes = steps30
                    recentSteps60Minutes = steps60
                    recentSteps180Minutes = steps180
                }
                override fun phoneSteps5() = StepService.getRecentStepCount5Min()
                override fun phoneSteps10() = StepService.getRecentStepCount10Min()
                override fun phoneSteps15() = StepService.getRecentStepCount15Min()
                override fun phoneSteps30() = StepService.getRecentStepCount30Min()
                override fun phoneSteps60() = StepService.getRecentStepCount60Min()
                override fun phoneSteps180() = StepService.getRecentStepCount180Min()
                override fun heartRatesCached(now: Long) = this@DetermineBasalaimiSMB2.heartRatesCached(now)
                override fun logHeartRates(samples: List<HR>) {
                    if (samples.isNotEmpty()) {
                        val lastHR = samples.maxByOrNull { it.timestamp }
                        aapsLogger.debug(LTag.APS, "HR Data: Found ${samples.size} records. Last: ${lastHR?.beatsPerMinute} @ ${Instant.fromEpochMilliseconds(lastHR?.timestamp ?: 0)}")
                    } else {
                        aapsLogger.debug(LTag.APS, "HR Data: No records found in last 200 mins")
                    }
                }
                override fun setAverageBpm(value: Double) { averageBeatsPerMinute = value }
                override fun averageBpm() = averageBeatsPerMinute
                override fun setAverageBpm10(value: Double) { averageBeatsPerMinute10 = value }
                override fun setAverageBpm60(value: Double) { averageBeatsPerMinute60 = value }
                override fun setAverageBpm180(value: Double) { averageBeatsPerMinute180 = value }
                override fun setBaselineReal(value: Boolean) { heartRateBaselineIsReal = value }
                override fun logHeartRateFailure(error: Exception) {
                    aapsLogger.error(LTag.APS, "Error processing Heart Rate data", error)
                    work.consoleLog.add(
                        "HR windows failed (${error::class.simpleName}): ${error.message.orEmpty()} — averages 80, baseline not real",
                    )
                }
                override fun recentSteps10() = recentSteps10Minutes
                override fun averageBpm10() = averageBeatsPerMinute10
                override fun averageBpm60() = averageBeatsPerMinute60
                override fun baselineReal() = heartRateBaselineIsReal
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun scaleVariableSensitivity(factor: Float) {
                    variableSensitivity *= factor
                }
            },
        )
        return AimiPostBasalBootstrapActivityVitals(vitals.tick, vitals.minDelta, vitals.minAvgDelta)
    }

    /** Hour snapshot + pregnancy pref captured after `work.aimilimit` adjust (historical position) for downstream [BasalDecisionEngine.Input]. */
    private data class AimiBasalAimiThroughPaiStageSnapshot(
        val timenowHour: Int,
        val sixAMHour: Int,
        val pregnancyEnable: Boolean,
    )

    /**
     * TDD→`basalaimi`, carb index / `aimilimit`, grossesse+TIR basales, accélération BG / early basal, puis PAI sur ISF.
     * Runs **after** [runPostBasalBootstrapIobTickStepsAndHeartRate], **before** [applyEndoAndActivityAdjustments].
     * Mutates [basalaimi], [ci], [aimilimit], [variableSensitivity]; reads [causalState.adaptiveMult], prefs, [unifiedReactivityLearner], [basalDecisionEngine].
     */
    private fun runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf(
        glucoseStatus: GlucoseStatusAIMI,
        profile: OapsProfileAimi,
        profileCurrentBasal: Double,
        bg: Double,
        delta: Float,
        tdd7Days: Double,
        tdd7P: Double,
        paiBaseSensitivity: Double,
        honeymoon: Boolean,
        tirbasal3B: Double?,
        tirbasal3IR: Double?,
        tirbasal3A: Double?,
        tirbasalhAP: Double?,
        lastHourTIRAbove: Double?,
        iobPeakMinutes: Double,
        iobActivityIn30Min: Double,
        iobActivityNow: Double,
    ): AimiBasalAimiThroughPaiStageSnapshot {
        val stage = decideBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf(
            glucoseStatus = glucoseStatus,
            profile = profile,
            profileCurrentBasal = profileCurrentBasal,
            bg = bg,
            delta = delta,
            tdd7Days = tdd7Days,
            tdd7P = tdd7P,
            paiBaseSensitivity = paiBaseSensitivity,
            honeymoon = honeymoon,
            tirbasal3B = tirbasal3B,
            tirbasal3IR = tirbasal3IR,
            tirbasal3A = tirbasal3A,
            tirbasalhAP = tirbasalhAP,
            lastHourTIRAbove = lastHourTIRAbove,
            iobPeakMinutes = iobPeakMinutes,
            iobActivityIn30Min = iobActivityIn30Min,
            iobActivityNow = iobActivityNow,
            preferences = preferences,
            consoleLog = work.consoleLog,
            state = object : AimiBasalPaiState {
                override fun basalAimi() = work.basalaimi
                override fun setBasalAimi(value: Float) { work.basalaimi = value }
                override fun ci() = work.ci
                override fun setCi(value: Float) { work.ci = value }
                override fun aimiLimit() = work.aimilimit
                override fun setAimiLimit(value: Float) { work.aimilimit = value }
                override fun adaptiveMult() = this@DetermineBasalaimiSMB2.causalState.adaptiveMult
                override fun setVariableSensitivity(value: Float) { variableSensitivity = value }
            },
            smooth = AimiSmoothBasalRate { tddRecent, tddPrevious, currentBasalRate ->
                basalDecisionEngine.smoothBasalRate(tddRecent, tddPrevious, currentBasalRate)
            },
            hour = AimiDecisionLocalHour { aimiLocalHour() },
            reactivity = AimiUnifiedReactivityFactor { unifiedReactivityLearner.globalFactor },
        )
        return AimiBasalAimiThroughPaiStageSnapshot(
            timenowHour = stage.timenowHour,
            sixAMHour = stage.sixAMHour,
            pregnancyEnable = stage.pregnancyEnable,
        )
    }

    /**
     * Immediately after [applyEndoAndActivityAdjustments]: clamp [variableSensitivity], then physio ISF/basal/SMB factors,
     * then the stress ISF floor.
     * Mutates [variableSensitivity], [profile.max_daily_basal], [maxSMB] / [maxSMBHB] (lockout). Returns the same value
     * historically assigned to local `sens` via `variableSensitivity.toDouble()`.
     *
     * This is the LAST place in a tick where [variableSensitivity] changes, which is why the stress
     * floor is applied here rather than where the value is assembled: `HeartRateTrendIsf` multiplies
     * the same member by 0.9 on nearly the same signature, and it runs earlier (the assembly in
     * [runTddRatesAndIsfFusionAfterContext], then the trend, then the endocrine and activity factors,
     * then this function). The protective gesture has to win when the two fire together. The three
     * steps live in `WorkingIsf` so a test can hold that order in place.
     *
     * With `BooleanKey.OApsAIMIStressIsfFloor` off, `profile.stress_floor_isf_mgdl` is null and this
     * function is bit-for-bit what it was before.
     */
    private fun applyIsfBoundsAndPhysioMultipliersAfterEndoActivity(
        profile: OapsProfileAimi,
        physioMultipliers: PhysioMultipliersMTR,
        exerciseInsulinLockoutActive: Boolean,
    ): Double {
        this.variableSensitivity = WorkingIsf.finalize(
            workingIsfMgdlPerU = this.variableSensitivity.toDouble(),
            physioIsfFactor = physioMultipliers.isfFactor,
            stressFloorIsfMgdlPerU = profile.stress_floor_isf_mgdl,
        ).toFloat()
        applyAuditorIsfFactorToWorkingIsf(profile)
        WorkingIsf.lastApplied?.let { applied ->
            val floorMgdl = applied.floorMgdlPerU ?: return@let
            work.consoleLog.add(
                "🧷 STRESS_ISF_FLOOR ${aimiFmt1(applied.beforeMgdlPerU)} -> ${aimiFmt1(applied.afterMgdlPerU)} (floor ${aimiFmt1(floorMgdl)})"
            )
        }
        profile.max_daily_basal = profile.max_daily_basal * physioMultipliers.basalFactor
        if (exerciseInsulinLockoutActive) {
            work.maxSMB = 0.0
            work.maxSMBHB = 0.0
        } else {
            work.maxSMB = (work.maxSMB * physioMultipliers.smbFactor).coerceAtLeast(0.1)
        }
        return variableSensitivity.toDouble()
    }

    /**
     * The one place the auditor's ISF factor reaches a dose.
     *
     * It runs straight after `WorkingIsf.finalize`, which is the last step of the dose-facing
     * sensitivity and the step that applies the stress floor. Two things follow from that order and
     * both are deliberate:
     *
     * - the factor lands on the number the SMB, the basal engine and the predictions really read,
     *   not on the commanded ISF, which sits on a floor about half the time and would swallow it;
     * - the floor is applied BEFORE the factor, and `AuditorProfileFactorGate.scaleIsf` may never
     *   pull the value back under it, so a protective floor always wins over a factor that asks for
     *   more insulin.
     *
     * With the opt-in key off nothing is assigned: the shadow decision is recorded and the
     * sensitivity keeps the very bits `WorkingIsf.finalize` returned.
     */
    private fun applyAuditorIsfFactorToWorkingIsf(profile: OapsProfileAimi) {
        val decision = auditorProfileTick.isfDecision ?: return
        val raw = AuditorIsfRaw(
            workingMgdl = this.variableSensitivity.toDouble(),
            stressFloorMgdl = profile.stress_floor_isf_mgdl,
            profileStaticMgdl = auditorProfileTick.profileStaticIsfMgdl,
        )
        val floored = AuditorProfileFactorGate.applyIsfFloor(decision, raw, auditorProfileTick.keyOn)
        auditorProfileTick.isfDecision = floored
        auditorProfileTick.isfPath =
            if (floored.applied) AuditorProfileFactorCodes.PATH_APPLIED
            else AuditorProfileFactorCodes.PATH_SHADOW
        if (!floored.applied) return
        auditorProfileTick.isfAppliedFactor = floored.effective
        this.variableSensitivity = (floored.workingAdjustedMgdl ?: raw.workingMgdl).toFloat()
        work.consoleLog.add(
            "🧪 AUDITOR_ISF ${aimiFmt1(raw.workingMgdl)} -> ${aimiFmt1(this.variableSensitivity.toDouble())} (x${aimiFmt3(floored.effective)}, floor ${aimiFmt1(floored.lowerBoundMgdl ?: 0.0)})"
        )
    }

    /**
     * Judges the auditor's ISF factor against the live state of this tick. Changes nothing.
     *
     * It runs early, before the target decision, because the two factors share one budget and the
     * ISF has priority. The floor step happens later, where the sensitivity is final, in
     * [applyAuditorIsfFactorToWorkingIsf].
     *
     * The minimum predicted glucose comes from the PREVIOUS tick: this tick has no predictions yet.
     */
    private fun decideAuditorIsfFactorForTick(ctx: AimiTickContext) {
        val glucoseStatus = ctx.glucoseStatus
        val previous = auditorTickRing.latest()
        val safety = TickSafety(
            bgMgdl = glucoseStatus.glucose,
            deltaMgdl5m = glucoseStatus.delta,
            shortAvgDeltaMgdl5m = glucoseStatus.shortAvgDelta,
            cgmNoise = glucoseStatus.noise,
            hypoThresholdMgdl = HypoThresholdMath.computeHypoThreshold(ctx.profile.min_bg, ctx.profile.lgsThreshold),
            minPredBgMgdl = previous?.minPredBgMgdl,
            minPredThresholdMgdl = previous?.hypoThresholdMgdl,
            minPredFromPreviousTick = true,
            postHypoActive = work.lastPostHypoDeliveryAuthority.active,
            minBg75mMgdl = minBgInLastMinutesOrNull(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            exerciseLockout = work.exerciseInsulinLockoutActive || work.sportTime,
        )
        auditorProfileTick.safetyAtA = safety
        auditorProfileTick.isfDecision = AuditorProfileFactorGate.evaluateIsf(
            proposal = auditorProfileTick.proposal,
            keyOn = auditorProfileTick.keyOn,
            tickTimestampMs = ctx.currentTime,
            safety = safety,
        )
    }

    /**
     * Judges the auditor's target factor. Changes nothing, not even the local target.
     *
     * Every target-relative guard of the engine keeps the raw target; only the two dose formulas see
     * the ratio, through [auditorDoseTarget]. A lower target must never be able to loosen a guard.
     */
    private fun decideAuditorTargetFactorForTick(
        ctx: AimiTickContext,
        workingTargetRawMgdl: Double,
        hypoThresholdMgdl: Double,
        minPredBgMgdl: Double?,
    ) {
        val glucoseStatus = ctx.glucoseStatus
        val safety = TickSafety(
            bgMgdl = glucoseStatus.glucose,
            deltaMgdl5m = glucoseStatus.delta,
            shortAvgDeltaMgdl5m = glucoseStatus.shortAvgDelta,
            cgmNoise = glucoseStatus.noise,
            hypoThresholdMgdl = hypoThresholdMgdl,
            minPredBgMgdl = minPredBgMgdl,
            minPredThresholdMgdl = hypoThresholdMgdl,
            minPredFromPreviousTick = false,
            postHypoActive = work.lastPostHypoDeliveryAuthority.active,
            minBg75mMgdl = minBgInLastMinutesOrNull(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            exerciseLockout = work.exerciseInsulinLockoutActive || work.sportTime,
        )
        auditorProfileTick.safetyAtB = safety
        val decision = AuditorProfileFactorGate.evaluateTarget(
            proposal = auditorProfileTick.proposal,
            keyOn = auditorProfileTick.keyOn,
            tickTimestampMs = ctx.currentTime,
            workingTargetRawMgdl = workingTargetRawMgdl,
            // The shadow uses the shadow ISF factor, so the logged target is what the key being on
            // would really have produced.
            //
            // Note the two points read different predictions on purpose: the early ISF point has
            // only the previous tick's, this one has this tick's own. So a tick where the previous
            // prediction was low and this one is not refuses the ISF (`effective` 1.0) and then hands
            // the target the whole budget, because the budget formula reads "the ISF spent nothing".
            // That is what the spec asks for, and the target still passes every live rule here, but
            // the more careful A-rule does not carry over to B.
            isfEffectiveFactor = auditorProfileTick.isfDecision?.effective ?: 1.0,
            safety = safety,
        )
        auditorProfileTick.targetDecision = decision
        auditorProfileTick.targetPath =
            if (decision.applied) AuditorProfileFactorCodes.PATH_APPLIED
            else AuditorProfileFactorCodes.PATH_SHADOW
    }

    /**
     * The target one dose formula must use, bounded against that formula's own target.
     *
     * The two sites do not hold the same number: the SMB site reads the local working target, the
     * basal engine reads the loop target member, and the step-activity branch moves one without the
     * other. So the shared budget is re-computed here, on the value this site will really use, with
     * the ISF factor that really reached the dose. A ratio decided somewhere else would not be a
     * bound here.
     */
    private fun auditorDoseTarget(targetMgdl: Double, site: String): Double {
        val decision = auditorProfileTick.targetDecision ?: return targetMgdl
        if (!decision.applied) return targetMgdl
        val adjusted = AuditorProfileFactorGate.targetForDoseSite(
            targetMgdl = targetMgdl,
            decision = decision,
            bgMgdl = auditorProfileTick.safetyAtB?.bgMgdl ?: bg,
            isfEffectiveFactor = auditorProfileTick.isfAppliedFactor,
        )
        if (adjusted == targetMgdl) return targetMgdl
        auditorProfileTick.targetDoseSites.add(site)
        return adjusted
    }

    /** This tick as the auditor must remember it: raw values, plus what the auditor really changed. */
    private fun buildAuditorTickFact(ctx: AimiTickContext, profile: OapsProfileAimi): AuditorTickFact =
        AuditorTickFact(
            timestampMs = ctx.currentTime,
            bgMgdl = ctx.glucoseStatus.glucose,
            deltaMgdl5m = ctx.glucoseStatus.delta,
            shortAvgDeltaMgdl5m = ctx.glucoseStatus.shortAvgDelta,
            iobU = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            cobG = ctx.mealData.mealCOB,
            profileIsfStaticMgdl = auditorProfileTick.profileStaticIsfMgdl,
            dynamicIsfRawMgdl = auditorProfileTick.dynamicIsfMgdl ?: profile.variable_sens,
            commandIsfRawMgdl = auditorProfileTick.commandIsfMgdl ?: profile.sens,
            commandIsfPreFloorMgdl = auditorProfileTick.commandPreFloorIsfMgdl,
            commandFloorMultiplier = auditorProfileTick.commandFloorMultiplier,
            workingIsfRawMgdl = auditorProfileTick.isfDecision?.workingRawMgdl,
            profileTargetMgdl = auditorProfileTick.profileTargetMgdl ?: profile.target_bg,
            tempTargetActive = auditorProfileTick.tempTargetActive,
            workingTargetRawMgdl = auditorProfileTick.workingTargetMgdl,
            minPredBgMgdl = auditorProfileTick.safetyAtB?.minPredBgMgdl,
            hypoThresholdMgdl = auditorProfileTick.safetyAtB?.hypoThresholdMgdl
                ?: auditorProfileTick.safetyAtA?.hypoThresholdMgdl,
            runningBasalUph = if (ctx.currentTemp.duration > 0) ctx.currentTemp.rate else profile.current_basal,
            profileBasalUph = profile.current_basal,
            postHypoActive = work.lastPostHypoDeliveryAuthority.active,
            exerciseLockout = work.exerciseInsulinLockoutActive || work.sportTime,
            isfFactorApplied = auditorProfileTick.isfAppliedFactor,
            targetFactorApplied =
                auditorProfileTick.targetDecision?.takeIf { it.applied }?.effective ?: 1.0,
        )

    /**
     * PKPD eventual BG → membres + [rT], BGI / deviation 30m, eventual « legacy » pour heuristiques,
     * puis ajustement min/target/max si BG haute + `adv_target_adjustments`.
     * **Downstream** : [bgi] et [deviation] sont réutilisés plus bas (CI, raison) ; cibles renvoyées pour réassigner les `var` du tick.
     */
    private data class AimiPkpdBgiDeviationAndTargetsStage(
        val bgi: Double,
        /** Same type as historical `round(...)` one-arg (Int mg/dL deviation bucket). */
        val deviation: Int,
        val minBg: Double,
        val targetBg: Double,
        val maxBg: Double,
    )

    private fun runPkpdPredictionsBgiDeviationAndNoisyTargetsStage(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        pkpdRuntime: PkPdRuntime?,
        iobData: IobTotal,
        bg: Double,
        delta: Float,
        sens: Double,
        minDelta: Double,
        minAvgDelta: Double,
        minBg: Double,
        targetBg: Double,
        maxBg: Double,
    ): AimiPkpdBgiDeviationAndTargetsStage {
        val stage = decidePkpdPredictionsAndNoisyTargets(
            ctx = ctx,
            profile = profile,
            rT = rT,
            glucoseStatus = glucoseStatus,
            pkpdRuntime = pkpdRuntime,
            iobData = iobData,
            bg = bg,
            delta = delta,
            sens = sens,
            minDelta = minDelta,
            minAvgDelta = minAvgDelta,
            minBg = minBg,
            targetBg = targetBg,
            maxBg = maxBg,
            preferences = preferences,
            consoleLog = work.consoleLog,
            texts = rh,
            calls = object : AimiPkpdTargetCalls {
                override fun mealAbsorptionOutput() = work.lastMealAbsorptionOutput
                override fun uamHypothesis() = causalState.lastUamHypothesisState
                override fun latentState() = causalState.lastPhysioLatentState
                override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
                override fun setAdvancedCurves(curves: AdvancedPredictionCurves) {
                    work.lastAdvancedPredictionCurves = curves
                }
                override fun recordSoftFloor(curves: AdvancedPredictionCurves) = recordPkpdSoftFloor(curves)
                override fun setEventualBg(value: Double) {
                    work.eventualBG = value
                }
                override fun eventualBg() = work.eventualBG
                override fun setPredictedBg(value: Float) {
                    this@DetermineBasalaimiSMB2.predictedBg = value
                }
                override fun scenarioBestTerminalMgdl() = lastScenarioProjection?.scenarioBest?.terminalMgdl
                override fun physioPhaseName() = work.lastPhysiologicalPhaseOutput?.phase?.name
                override fun mealPhaseName() = work.lastMealAbsorptionOutput?.phase?.name
                override fun setPredDivergenceExport(value: kotlinx.serialization.json.JsonObject) {
                    lastPredDivergenceExport = value
                }
                override fun reconstructedIobUnits() = pkpdIntegration.reconstructedIobUnits()
                override fun cachedPkpdRuntimePresent() = cachedPkpdRuntime != null
                override fun pkpdRelativeActivity() = cachedPkpdRuntime?.activity?.relativeActivity
                override fun pkpdActivityStage() = cachedPkpdRuntime?.activity?.stage
                override fun minBgInLastMinutes(minutes: Int) = this@DetermineBasalaimiSMB2.minBgInLastMinutes(minutes)
                override fun roundToIntUnits(value: Double) = round(value)
                override fun publishDoseTerminal(
                    rT: RT,
                    profile: OapsProfileAimi,
                    mealData: MealData,
                    pkpdEventualMgdl: Double,
                    pkpdPredTerminalMgdl: Double,
                    targetBgMgdl: Double,
                    stageTag: String,
                ) {
                    publishDoseTerminalAuthorityAndSnapshot(
                        rT, profile, mealData, pkpdEventualMgdl, pkpdPredTerminalMgdl, targetBgMgdl, stageTag,
                    )
                }
                override fun refineAfterDose(rT: RT) {
                    refineRbtMergeAfterDoseSnapshot(rT)
                }
                override fun decisionPrediction() = work.lastDecisionPredictionAuthority
                override fun cob() = work.cob
                override fun projectionInput(
                    targetBgValue: Double,
                    cobValue: Double,
                    combinedDeltaValue: Float,
                ) = correctionAggressionProjectionInput(targetBgValue, cobValue, combinedDeltaValue)
                override fun doseSnapshotTerminals(): Pair<Double?, Double?> {
                    val snap = lastDoseTerminalSnapshot
                    return snap?.minPredMgdl to snap?.eventualMgdl
                }
                override fun mealSafetyContext(iobData: IobTotal) =
                    buildMealSafetyContext(isExplicitAdvisorRun = false, iobData = iobData)
                override fun mealCertainty() = work.lastMealCertainty
                override fun setRiskEnvelope(envelope: AimiRiskEnvelope) {
                    cachedRiskEnvelopeDecision = envelope
                }
                override fun reconcileSafetyRisk() {
                    reconcileSafetyRiskWithDecisionEnvelope()
                }
                override fun logError(line: String) {
                    work.consoleError.add(line)
                }
            },
        )
        return AimiPkpdBgiDeviationAndTargetsStage(
            bgi = stage.bgi,
            deviation = stage.deviation,
            minBg = stage.minBg,
            targetBg = stage.targetBg,
            maxBg = stage.maxBg,
        )
    }

    /**
     * UAM SMB from [calculateSMBFromModel], hypo hysteresis + rocket override, optional hyper fallback dampening,
     * puis [PostHypoState] (rebound bridge / meal cap). Met à jour [predictedSMB] et parfois [rT].
     * @param minBgHypoComposite même sémantique que le `minBg` hoisted (min BG / pred / eventual pour seuil hypo).
     * @return [modelcal] brut (inchangé) pour logs et [executeSmbInstruction] aval.
     */
    private fun runUamModelCalHypoGuardPostHypoAndSetPredictedSmb(
        rT: RT,
        bg: Double,
        delta: Float,
        iob: Float,
        predictedBg: Float,
        eventualBg: Double,
        threshold: Double,
        minBgHypoComposite: Double,
        targetBg: Double,
        profile: OapsProfileAimi,
        postHypoState: PostHypoState,
        cob: Float,
    ): Float = decideUamPostHypoSmb(
        rT = rT,
        bg = bg,
        delta = delta,
        iob = iob,
        predictedBg = predictedBg,
        eventualBg = eventualBg,
        threshold = threshold,
        minBgHypoComposite = minBgHypoComposite,
        targetBg = targetBg,
        profile = profile,
        postHypoState = postHypoState,
        cob = cob,
        consoleLog = work.consoleLog,
        calls = object : AimiUamPostHypoCalls {
            override fun modelSmb(reason: StringBuilder) = calculateSMBFromModel(reason)
            override fun riskEnvelope() = cachedRiskEnvelopeDecision
            override fun sanitizedPredictedEventual(rT: RT, predictedBg: Double, eventualBg: Double) =
                sanitizedHypoGuardPredictedEventual(rT, predictedBg, eventualBg)
            override fun nowMs() = aimiWallClockMs()
            override fun hypoState() = AimiHypoSmbSafety.HypoHysteresisState(
                causalState.lastHypoBlockAt,
                causalState.hypoClearCandidateSince,
            )
            override fun writeHypoState(state: AimiHypoSmbSafety.HypoHysteresisState) {
                causalState.lastHypoBlockAt = state.causalState.lastHypoBlockAt
                causalState.hypoClearCandidateSince = state.causalState.hypoClearCandidateSince
            }
            override fun aggression() = work.correctionAggressionDecision
            override fun clearHypoBlockAt() {
                causalState.lastHypoBlockAt = 0L
            }
            override fun appendHypoGuard(
                rT: RT,
                composite: Double,
                hypoThreshold: Double,
                bg: Double,
                predicted: Double,
                eventual: Double,
            ) {
                rT.reason.appendLine(
                    rh.gs(
                        ApsStrings.reason_hypo_guard,
                        convertBG(composite),
                        convertBG(hypoThreshold),
                        convertBG(bg),
                        convertBG(predicted),
                        convertBG(eventual),
                    )
                )
            }
            override fun setPredictedSmb(value: Float) {
                work.predictedSMB = value
            }
            override fun maxSmb() = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        },
    )

    /**
     * Log + prefs one-shot advisor + [executeSmbInstruction].
     * Ordre des propriétés : [smbExecution] puis [isMealAdvisorOneShot] (déstructuration dans [determine_basal]).
     * [isMealAdvisorOneShot] = valeur lue avant `put(false)` sur la préf (comportement historique).
     */
    private data class AimiSmbAdvisorLogAndExecutionStage(
        val smbExecution: SmbInstructionExecutor.Result,
        val isMealAdvisorOneShot: Boolean,
    )

    private fun runSmbDecisionLogAdvisorOneShotAndExecuteInstruction(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        bg: Double,
        delta: Float,
        iob: Float,
        shortAvgDelta: Float,
        predictedBg: Float,
        eventualBG: Double,
        sens: Double,
        tp: Double,
        variableSensitivity: Float,
        targetBg: Double,
        basalaimi: Float,
        basal: Double,
        honeymoon: Boolean,
        hourOfDay: Int,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        sportTime: Boolean,
        causalState.lateFatRiseFlag: Boolean,
        highCarbrunTime: Long,
        threshold: Double,
        windowSinceDoseInt: Int,
        intervalsmb: Int,
        pumpCaps: PumpCaps,
        causalState.highBgOverrideUsed: Boolean,
        cob: Float,
        pkpdRuntime: PkPdRuntime?,
        pumpAgeDays: Float,
        modelcal: Float,
        profileCurrentBasal: Double,
        isConfirmedHighRiseLocal: Boolean,
        exerciseInsulinLockoutActive: Boolean,
        combinedDelta: Float,
        skipLegacySmbBlender: Boolean,
        minBgLookbackMgdl: Double,
    ): AimiSmbAdvisorLogAndExecutionStage {
        val decided = decideSmbAdvisorOneShot(
            ctx = ctx,
            profile = profile,
            rT = rT,
            glucoseStatus = glucoseStatus,
            bg = bg,
            delta = delta,
            iob = iob,
            shortAvgDelta = shortAvgDelta,
            predictedBg = predictedBg,
            eventualBg = eventualBG,
            sens = sens,
            tp = tp,
            variableSensitivity = variableSensitivity,
            targetBg = targetBg,
            basalAimi = basalaimi,
            basal = basal,
            honeymoon = honeymoon,
            hourOfDay = hourOfDay,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = work.snackTime,
            sportTime = work.sportTime,
            causalState.lateFatRiseFlag = causalState.lateFatRiseFlag,
            highCarbRuntime = work.highCarbrunTime,
            threshold = threshold,
            windowSinceDoseInt = windowSinceDoseInt,
            intervalSmb = intervalsmb,
            insulinStep = pumpCaps.bolusStep.toFloat(),
            causalState.highBgOverrideUsed = causalState.highBgOverrideUsed,
            cob = work.cob,
            pkpdRuntime = pkpdRuntime,
            pumpAgeDays = pumpAgeDays,
            modelCal = modelcal,
            profileCurrentBasal = profileCurrentBasal,
            isConfirmedHighRise = isConfirmedHighRiseLocal,
            exerciseInsulinLockout = work.exerciseInsulinLockoutActive,
            combinedDelta = combinedDelta,
            skipLegacySmbBlender = skipLegacySmbBlender,
            minBgLookbackMgdl = minBgLookbackMgdl,
            preferences = preferences,
            consoleLog = work.consoleLog,
            calls = object : AimiSmbOneShotCalls {
                override fun setMealAdvisorOneShot(value: Boolean) {
                    work.mealAdvisorOneShotThisTick = value
                }
                override fun maxSmb() = work.maxSMB
                override fun setMaxSmb(value: Double) {
                    work.maxSMB = value
                }
                override fun maxSmbHb() = work.maxSMBHB
                override fun setMaxSmbHb(value: Double) {
                    work.maxSMBHB = value
                }
                override fun predictedSmb() = work.predictedSMB
                override fun logSmbDecision(
                    bg: Double,
                    delta: Float,
                    iob: Float,
                    hasPred: Boolean,
                    hyperKicker: Boolean,
                    modelCal: Float,
                    proposed: Float,
                ) {
                    work.consoleLog.add(
                        "SMB Decision: BG=${aimiFmt0(bg)}, Delta=${aimiFmt1(delta)}, IOB=${aimiFmt2(iob)}, HasPred=$hasPred, HyperKicker=$hyperKicker, UAM=${aimiFmt2(modelCal)}, Proposed=${aimiFmt2(proposed)}"
                    )
                }
                override fun authoritativePhrase() =
                    rh.gs(ApsStrings.reason_autodrive_v3_authoritative_blender_skipped)
                override fun executeLegacy(
                    bg: Double,
                    delta: Float,
                    iob: Float,
                    basalAimi: Float,
                    basal: Double,
                    honeymoon: Boolean,
                    hourOfDay: Int,
                    mealTime: Boolean,
                    bfastTime: Boolean,
                    lunchTime: Boolean,
                    dinnerTime: Boolean,
                    highCarbTime: Boolean,
                    snackTime: Boolean,
                    sens: Double,
                    tp: Float,
                    variableSensitivity: Float,
                    targetBg: Double,
                    predictedBg: Float,
                    eventualBg: Double,
                    isMealAdvisorOneShot: Boolean,
                    mealData: MealData,
                    pkpdRuntime: PkPdRuntime?,
                    sportTime: Boolean,
                    causalState.lateFatRiseFlag: Boolean,
                    highCarbRuntime: Long,
                    threshold: Double,
                    currentTime: Long,
                    windowSinceDoseInt: Int,
                    intervalSmb: Int,
                    insulinStep: Float,
                    causalState.highBgOverrideUsed: Boolean,
                    cob: Float,
                    pkpdDiaMinutesOverride: Double?,
                    profile: OapsProfileAimi,
                    rT: RT,
                    combinedDelta: Float,
                    glucoseStatus: GlucoseStatusAIMI,
                    pumpAgeDays: Float,
                    modelCal: Double,
                    profileCurrentBasal: Double,
                    isConfirmedHighRise: Boolean,
                    exerciseInsulinLockout: Boolean,
                    minBgLookbackMgdl: Double,
                ) = executeSmbInstruction(
                    bg = bg, delta = delta, iob = iob, basalaimi = basalAimi, basal = basal,
                    honeymoon = honeymoon, hourOfDay = hourOfDay,
                    mealTime = mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
                    dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = work.snackTime,
                    sens = sens, tp = tp, variableSensitivity = variableSensitivity,
                    target_bg = targetBg, predictedBg = predictedBg, eventualBG = eventualBg,
                    isMealAdvisorOneShot = isMealAdvisorOneShot, mealData = mealData,
                    pkpdRuntime = pkpdRuntime, sportTime = work.sportTime, causalState.lateFatRiseFlag = causalState.lateFatRiseFlag,
                    highCarbrunTime = highCarbRuntime, threshold = threshold,
                    currentTime = currentTime, windowSinceDoseInt = windowSinceDoseInt,
                    intervalsmb = intervalSmb, insulinStep = insulinStep,
                    causalState.highBgOverrideUsed = causalState.highBgOverrideUsed, cob = work.cob,
                    pkpdDiaMinutesOverride = pkpdDiaMinutesOverride,
                    profile = profile, rT = rT,
                    combinedDeltaLocal = combinedDelta, glucoseStatusLocal = glucoseStatus,
                    pumpAgeDaysLocal = pumpAgeDays, modelcalLocal = modelCal,
                    profileCurrentBasalLocal = profileCurrentBasal,
                    isConfirmedHighRise = isConfirmedHighRise,
                    exerciseInsulinLockout = exerciseInsulinLockout,
                    minBgLookbackMgdl = minBgLookbackMgdl,
                ).let { executed ->
                    AimiSmbExecution(
                        predictedSmb = executed.predictedSmb,
                        basal = executed.basal,
                        finalSmb = executed.finalSmb,
                        causalState.highBgOverrideUsed = executed.causalState.highBgOverrideUsed,
                        newSmbInterval = executed.newSmbInterval,
                    )
                }
            },
        )
        return AimiSmbAdvisorLogAndExecutionStage(
            smbExecution = SmbInstructionExecutor.Result(
                predictedSmb = decided.smbExecution.predictedSmb,
                basal = decided.smbExecution.basal,
                finalSmb = decided.smbExecution.finalSmb,
                causalState.highBgOverrideUsed = decided.smbExecution.causalState.highBgOverrideUsed,
                newSmbInterval = decided.smbExecution.newSmbInterval,
            ),
            isMealAdvisorOneShot = decided.isMealAdvisorOneShot,
        )
    }

    /**
     * Applies [SmbInstructionExecutor.Result] to tick state, then the historical SMB result console line.
     * [assignLocalBasalFromExecution] updates the **local** `basal` in [determine_basal] (not a class field — same order as historical inline).
     */
    private fun applySmbAdvisorExecutionToTickStateAndLog(
        smbExecution: SmbInstructionExecutor.Result,
        assignLocalBasalFromExecution: (Double) -> Unit,
    ): Float {
        work.predictedSMB = smbExecution.predictedSmb
        assignLocalBasalFromExecution(smbExecution.basal)
        causalState.highBgOverrideUsed = smbExecution.causalState.highBgOverrideUsed
        smbExecution.newSmbInterval?.let { intervalsmb = it }
        val smbToGive = smbExecution.finalSmb
        if (work.lastSmbBindingTraceDraft.originOwner == "NONE") {
            work.lastSmbBindingTraceDraft = work.lastSmbBindingTraceDraft.copy(
                originOwner = "GlobalAIMI",
                modelOutputU = work.predictedSMB.toDouble(),
                maxSmbU = work.maxSMB,
                maxSmbHighBgU = work.maxSMBHB,
                iobHeadroomU = (work.maxIob - work.iob).coerceAtLeast(0.0),
            ).appendStage(
                "SMB_EXECUTOR",
                work.predictedSMB.toDouble(),
                smbToGive.toDouble(),
                phase = "GLOBAL_AIMI_PROPOSAL",
                kind = "PROPOSAL",
            )
        }
        work.consoleLog.add(
            "💉 SMB result: raw=${aimiFmt2(work.predictedSMB)} -> final=${aimiFmt2(smbToGive)}"
        )
        return smbToGive
    }

    /**
     * PKPD absorption guard (relief + meal debridage maxIOB), endo SMB dampen, red carpet vs [capSmbDose], cap reason line.
     * Mutates [rT.reason], [intervalsmb] via returned value; reads [endoSmbMult], [maxSMB]/[maxSMBHB], [iob], [maxIob] membres.
     */
    private fun runPkpdGuardEndoDampenRedCarpetAndCapSmb(
        ctx: AimiTickContext,
        rT: RT,
        pkpdRuntime: PkPdRuntime?,
        smbExecution: SmbInstructionExecutor.Result,
        isExplicitAdvisorRun: Boolean,
        isMealAdvisorOneShot: Boolean,
        isConfirmedHighRiseLocal: Boolean,
        bg: Double,
        delta: Float,
        shortAvgDelta: Float,
        predictedBg: Float,
        eventualBG: Double,
        targetBg: Double,
        honeymoon: Boolean,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        windowSinceDoseInt: Int,
        intervalsmb: Int,
        smbToGive: Float,
        iob: Float,
    ): AimiPkpdGuardEndoRedCarpetSmbStage = decidePkpdGuardEndoDampenRedCarpetAndCapSmb(
        ctx = ctx,
        rT = rT,
        finalSmb = smbExecution.finalSmb,
        isExplicitAdvisorRun = isExplicitAdvisorRun,
        isMealAdvisorOneShot = isMealAdvisorOneShot,
        isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
        bg = bg,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        predictedBg = predictedBg,
        eventualBG = eventualBG,
        targetBg = targetBg,
        honeymoon = honeymoon,
        mealTime = mealTime,
        bfastTime = bfastTime,
        lunchTime = lunchTime,
        dinnerTime = dinnerTime,
        highCarbTime = highCarbTime,
        snackTime = snackTime,
        intervalsmb = intervalsmb,
        smbToGive = smbToGive,
        iob = work.iob,
        preferences = preferences,
        consoleLog = work.consoleLog,
        state = object : AimiPkpdGuardState {
            override fun maxSmb() = work.maxSMB
            override fun maxSmbHb() = work.maxSMBHB
            override fun maxIob() = work.maxIob
            override fun memberIob() = work.iob.toDouble()
            override fun endoSmbMult() = work.endoSmbMult
            override fun bindingDraft() = work.lastSmbBindingTraceDraft
            override fun setBindingDraft(value: SmbBindingTrace.Draft) { work.lastSmbBindingTraceDraft = value }
            override fun criticalSafetyZeroed() = work.criticalSafetyZeroedThisTick
            override fun endogenousCounterRegulatory() =
                work.lastPhysiologicalPhaseOutput?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
            override fun mealAbsorptionPhase() = work.lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE
        },
        absorptionGuard = AimiPkpdAbsorptionGuard { smbIn, reason ->
            val applied = applyPkpdAbsorptionGuardOncePerTick(
                smbIn = smbIn,
                pkpdRuntime = pkpdRuntime,
                windowSinceLastDoseMin = windowSinceLastPkpdDoseMin(windowSinceDoseInt),
                anyMealModeForGuard = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime,
                isConfirmedHighRise = isConfirmedHighRiseLocal,
                mealAdvisorOneShot = isMealAdvisorOneShot,
                reason = reason,
                logChannel = PkpdGuardLogChannel.PIPELINE,
            )
            AimiPkpdGuardApply(
                smbOut = applied.smbOut,
                skippedDuplicate = applied.skippedDuplicate,
                multiplicationApplied = applied.multiplicationApplied,
                guardReason = applied.guard?.reason,
                effectiveFactor = applied.effectiveFactor,
            )
        },
        mealCorrection = AimiMealCorrectionContext { mealData, bgMgdl, deltaMgdl, shortAvg ->
            val out = resolveMealCorrectionContext(
                mealData = mealData,
                bgMgdl = bgMgdl,
                deltaMgdlPer5 = deltaMgdl,
                shortAvgDeltaMgdlPer5 = shortAvg,
            )
            AimiMealCorrectionView(out.redCarpetEligible, out.summary())
        },
        eventual = AimiAuthoritativeEventual { fallback -> authoritativeEventualBg(fallback) },
        minPred = AimiAuthoritativeMinPred { rt, raw -> authoritativeMinPredBg(rt, raw) },
        minPredWiring = AimiMinPredWiring { raw -> minPredictedBgForRbtWiring(raw) },
    )

    /**
     * Snapshot `reason` / command slots / `predBGs`, blank [rT] for a clean enactment slice, apply delivery metadata,
     * restore predictions via [ensurePredictionFallback], then restore units/rate/duration and re-append saved reason.
     */
    private fun snapshotRtResetEnactmentFieldsRestorePredictionsAndPriorityCommands(
        rT: RT,
        deliverAt: Long,
        targetBg: Double,
        sensitivityRatio: Double,
        variableSensitivity: Float,
        bg: Double,
    ) {
        val savedReason = rT.reason.toString()
        val savedPredBGs = rT.predBGs
        val savedUnits = rT.units
        val savedRate = rT.rate
        val savedDuration = rT.duration

        rT.reason = StringBuilder("")
        rT.units = null
        rT.rate = null
        rT.duration = null
        rT.insulinReq = 0.0
        rT.deliverAt = deliverAt
        rT.targetBG = targetBg
        rT.sensitivityRatio = sensitivityRatio
        rT.variable_sens = variableSensitivity.toDouble()

        // Restore preserved predictions for the final engine path.
        rT.predBGs = savedPredBGs ?: rT.predBGs
        ensurePredictionFallback(rT, bg)

        // RESTORE PRIORITY COMMANDS (from early blocks)
        rT.units = savedUnits
        rT.rate = savedRate
        rT.duration = savedDuration
        rT.reason.append(savedReason)
    }

    /**
     * Meal / hyper / prudent / fasting basal-boost path: either an optional overlay rate for later application,
     * or a completed loop decision from an early max-TBR branch (advisor one-shot, snack, 30 min meal boost).
     *
     * Reads therapy flags + runtimes + [bg]/[delta]/[shortAvgDelta] from instance state like the inlined `when` did.
     */
    private fun resolveMealHyperBasalBoostOutcome(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        basal: Double,
        profileCurrentBasal: Double,
        isMealAdvisorOneShot: Boolean,
        targetBg: Double,
        timeSinceEstimateMin: Double,
        estimatedCarbs: Double,
    ): AimiMealHyperBasalBoostOutcome {
        return decideMealHyperBasalBoost(
            profile = profile,
            rT = rT,
            basal = basal,
            profileCurrentBasal = profileCurrentBasal,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            targetBg = targetBg,
            timeSinceEstimateMin = timeSinceEstimateMin,
            estimatedCarbs = estimatedCarbs,
            currentTemp = ctx.currentTemp,
            preferences = preferences,
            consoleLog = work.consoleLog,
            fields = object : AimiMealHyperFields {
                override fun snackTime() = work.snackTime
                override fun snackRunTime() = work.snackrunTime
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun mealTime() = work.mealTime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun mealRuntime() = work.mealruntime
                override fun lunchRuntime() = work.lunchruntime
                override fun dinnerRuntime() = work.dinnerruntime
                override fun highCarbRunTime() = work.highCarbrunTime
                override fun bfastRuntime() = work.bfastruntime
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta.toDouble()
                override fun mealAbsorption() = work.lastMealAbsorptionOutput
                override fun cob() = work.cob
                override fun phase() = work.lastPhysiologicalPhaseOutput?.phase
                override fun hyperReleaseActive() = work.lastHyperTrajectoryRelease?.active == true
                override fun aggression() = work.correctionAggressionDecision
                override fun basalFirstActive() = cachedBasalFirstActive
                override fun fragileBg() = cachedIsFragileBg
                override fun fastingTime() = work.fastingTime
            },
            basalCap = AimiBasalCap { requested, profileBasal, source ->
                capBasalRateForCorrectionAggression(requested, profileBasal, source)
            },
            tempBasal = AimiMealHyperTempBasal { rate, durationMin, profile, rT, currentTemp, overrideSafetyLimits, adaptiveMultiplier ->
                setTempBasal(
                    rate,
                    durationMin,
                    profile,
                    rT,
                    currentTemp,
                    overrideSafetyLimits = overrideSafetyLimits,
                    adaptiveMultiplier = adaptiveMultiplier,
                )
            },
            clock = AimiMealHyperClock { dateUtil.now() },
        )
    }

    /**
     * Résultat du tronçon meal/hyper basal dans le tick : soit **sortie loop** (TBR max 30 min déjà posé),
     * soit **overlay** optionnel sur [rT] (SMB aval inchangé).
     */
    private sealed class AimiMealHyperBasalBoostTickResult {
        data class CompleteLoop(val rT: RT) : AimiMealHyperBasalBoostTickResult()
        data class ContinueWithOverlay(val overlayRate: Double?) : AimiMealHyperBasalBoostTickResult()
    }

    /**
     * État basal-boost pour logs SMB aval ([runInsulinReqActivityRelaxAndMicrobolusStage]) — pas des membres d’instance.
     */
    private data class AimiMealHyperBasalOverlayState(
        val basalBoostApplied: Boolean,
        val basalBoostSource: String?,
    )

    /**
     * [timeSinceEstimateMin] puis [resolveMealHyperBasalBoostOutcome] — même horloge « maintenant » qu’avant l’extraction.
     */
    private fun runMealHyperBasalBoostTickStage(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        basal: Double,
        profileCurrentBasal: Double,
        isMealAdvisorOneShot: Boolean,
        targetBg: Double,
        estimatedCarbs: Double,
        estimatedCarbsTimeMs: Long,
    ): AimiMealHyperBasalBoostTickResult {
        val timeSinceEstimateMin =
            if (estimatedCarbsTimeMs > 0L) (aimiWallClockMs() - estimatedCarbsTimeMs) / 60000.0 else Double.MAX_VALUE
        return when (
            val o = resolveMealHyperBasalBoostOutcome(
                ctx = ctx,
                profile = profile,
                rT = rT,
                basal = basal,
                profileCurrentBasal = profileCurrentBasal,
                isMealAdvisorOneShot = isMealAdvisorOneShot,
                targetBg = targetBg,
                timeSinceEstimateMin = timeSinceEstimateMin,
                estimatedCarbs = estimatedCarbs,
            )
        ) {
            is AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal ->
                AimiMealHyperBasalBoostTickResult.CompleteLoop(o.rT)
            is AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate ->
                AimiMealHyperBasalBoostTickResult.ContinueWithOverlay(o.rate)
        }
    }

    /**
     * Applique l’overlay basal (30 min) si [overlayRate] non null ; retourne les flags pour la suite du tick.
     */
    private fun applyMealHyperBasalBoostOverlayIfNeeded(
        overlayRate: Double?,
        deliverAt: Long,
        rT: RT,
    ): AimiMealHyperBasalOverlayState {
        val basalBoostApplied = overlayRate != null
        val basalBoostSource: String? = when {
            overlayRate != null && rT.reason.contains("Endogenous basal bridge") -> "EndogenousBridge"
            overlayRate != null && rT.reason.contains("Global Hyper Kicker") -> "HyperKicker"
            overlayRate != null && rT.reason.contains("Post-Meal Boost") -> "PostMealBoost"
            overlayRate != null && rT.reason.contains("Meal") -> "MealMode"
            overlayRate != null && rT.reason.contains("fasting") -> "Fasting"
            else -> null
        }
        if (basalBoostApplied && overlayRate != null) {
            rT.rate = overlayRate.coerceAtLeast(0.0)
            rT.deliverAt = deliverAt
            rT.duration = 30
            work.consoleLog.add("BOOST_BASAL_APPLIED source=${basalBoostSource ?: "Unknown"} rate=${aimiFmt2(overlayRate)}U/h")
            rT.reason.append("BasalBoost: ${basalBoostSource ?: "?"} ${aimiFmt2(overlayRate)}U/h. ")
        }
        return AimiMealHyperBasalOverlayState(basalBoostApplied, basalBoostSource)
    }

    /**
     * WCycle IC multiplier → adjusted CR → CSF, clamp **[ci]** (mg/dL/5m, field) to absorption cap, remaining-CA bookkeeping + slopes, then CI / duration log line.
     * Mutates [ci] (Float field) like the inlined block; returns **[csf]** and **[slopeFromDeviations]** for [CarbsAdvisor] / hypo minutes below.
     */
    private data class AimiWCycleCsfCarbImpactStage(
        val csf: Double,
        val slopeFromDeviations: Double,
    )

    private fun runWCycleIcCsfClampCiAndCarbImpactLogs(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        sens: Double,
        baseSensitivity: Double,
        minDelta: Double,
        bgi: Double,
        sensitivityRatio: Double,
    ): AimiWCycleCsfCarbImpactStage {
        val icMult = EndocrineAmplitudeGovernor.productionAmp(
            causalState.lastWCycleBelief,
            EndocrineAmpAxis.IC,
        )
        val adjustedCR = profile.carb_ratio / icMult

        val csf = sens / adjustedCR
        work.consoleError.add(rh.gs(ApsStrings.console_profile_sens, baseSensitivity, sens, csf))

        val maxCarbAbsorptionRate = 30
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (work.ci > maxCI) {
            work.consoleError.add(rh.gs(ApsStrings.console_limiting_carb_impact, work.ci, maxCI, maxCarbAbsorptionRate))
            work.ci = maxCI.toFloat()
        }
        var remainingCATimeMin = 2.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        var remainingCATime = remainingCATimeMin
        val totalCI = max(0.0, work.ci / 5 * 60 * remainingCATime / 2)
        val totalCA = totalCI / csf
        val remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, ctx.mealData.mealCOB - totalCA)
        remainingCarbs = min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        val slopeFromMaxDeviation = ctx.mealData.slopeFromMaxDeviation
        val slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation
        val slopeFromDeviations = min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)

        val ciCurrentImpact = round((minDelta - bgi), 1)
        val cid: Double = if (ciCurrentImpact == 0.0) {
            0.0
        } else {
            min(remainingCATime * 60 / 5 / 2, max(0.0, ctx.mealData.mealCOB * csf / ciCurrentImpact))
        }
        work.consoleError.add(rh.gs(ApsStrings.console_carb_impact, ciCurrentImpact, round(cid * 5 / 60 * 2, 1), round(remainingCIpeak, 1)))

        return AimiWCycleCsfCarbImpactStage(
            csf = csf,
            slopeFromDeviations = slopeFromDeviations,
        )
    }

    /**
     * CarbsAdvisor (hypo carbs hint) → prefs repas max basal / autodrive max → **`enablesmb`** → COB/IOB reason line →
     * historique basal zéro → **`safetyAdjustment`** + hypo notification.
     *
     * [CarbsAdvisor.estimateRequiredCarbs] uses **member** [targetBg] (not [targetBgSchedule]); [enablesmb] and COB line use [targetBgSchedule].
     */
    private data class AimiCarbsAdvisorEnableSmbSafetyStage(
        val forcedBasalmealmodes: Double,
        val forcedBasal: Double,
        val enableSMB: Boolean,
        val mealModeActive: Boolean,
        val zeroSinceMin: Int,
        val minutesSinceLastChange: Int,
        val safetyDecision: SafetyDecision,
    )

    private fun runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        iobData: IobTotal,
        csf: Double,
        slopeFromDeviations: Double,
        sens: Double,
        bg: Double,
        iob: Float,
        cob: Float,
        delta: Float,
        eventualBG: Double,
        combinedDelta: Float,
        deviation: Int,
        bgi: Double,
        targetBgSchedule: Double,
        maxBgSchedule: Double,
        windowSinceDoseInt: Int,
    ): AimiCarbsAdvisorEnableSmbSafetyStage {
        val decided = decideCarbsAdvisorEnableSmbBasalHistoryAndSafety(
            profile = profile,
            ctx = ctx,
            rT = rT,
            glucoseStatus = glucoseStatus,
            iobData = iobData,
            csf = csf,
            slopeFromDeviations = slopeFromDeviations,
            sens = sens,
            bg = bg,
            iob = iob,
            cob = cob,
            delta = delta,
            eventualBG = eventualBG,
            combinedDelta = combinedDelta,
            deviation = deviation,
            bgi = bgi,
            targetBgSchedule = targetBgSchedule,
            maxBgSchedule = maxBgSchedule,
            windowSinceDoseInt = windowSinceDoseInt,
            calls = object : AimiCarbsAdvisorEnableSmbCalls {
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun mealTime() = work.mealTime
                override fun mealModesMaxBasal() = preferences.get(DoubleKey.meal_modes_MaxBasal)
                override fun autodriveMaxBasal() = preferences.get(DoubleKey.autodriveMaxBasal)
                override fun enableSmb(
                    profile: OapsProfileAimi,
                    microBolusAllowed: Boolean,
                    mealData: MealData,
                    targetBgSchedule: Double,
                    mealModeActive: Boolean,
                    currentBg: Double,
                    delta: Double,
                    eventualBg: Double,
                    combinedDelta: Double,
                ) = enablesmb(
                    profile,
                    microBolusAllowed,
                    mealData,
                    targetBgSchedule,
                    mealModeActive,
                    currentBg,
                    delta,
                    eventualBg,
                    combinedDelta,
                )
                override fun mealModeSmbReason() = work.mealModeSmbReason
                override fun reason(rT: RT, msg: String) {
                    this@DetermineBasalaimiSMB2.reason(rT, msg)
                }
                override fun withoutZeros(value: Double) = this@DetermineBasalaimiSMB2.run { value.withoutZeros() }
                override fun convertBg(value: Double) = convertBG(value)
                override fun appendAdditionalCarbs(rT: RT, carbsRequired: Int, minutesAboveThreshold: Int) {
                    rT.reason.append(rh.gs(ApsStrings.reason_additional_carbs, carbsRequired, minutesAboveThreshold))
                }
                override fun writeZeroBasalAccumulated(minutes: Int) {
                    this@DetermineBasalaimiSMB2.causalState.zeroBasalAccumulatedMinutes = minutes
                }
                override fun appendEventualBg(rT: RT, eventualBg: Double, maxBgSchedule: Double) {
                    rT.reason.append(rh.gs(ApsStrings.reason_eventual_bg, convertBG(eventualBg), convertBG(maxBgSchedule)))
                }
                override fun tdd24h(): Double = tddCalculator.averageTDD(
                    resolveTdd1DaySparseForAverage()
                )?.data?.totalAmount ?: 0.0
                override fun tirInHypo(): Double = tirCalculator.averageTIR(
                    resolveTir65180ForAverage()
                )?.belowPct() ?: 0.0
                override fun recentGlucose() = glucoseStatusCalculatorAimi.getRecentGlucose()
                override fun tddPerHour() = this@DetermineBasalaimiSMB2.tddPerHour
                override fun fieldDelta() = this@DetermineBasalaimiSMB2.delta
                override fun honeymoon() = preferences.get(BooleanKey.OApsAIMIhoneymoon)
                override fun phraseBook() = safetyPhraseBook()
                override fun postHypoRisk() {
                    notificationManager.post(
                        id = app.aaps.core.interfaces.notifications.NotificationId.HYPO_RISK_ALARM,
                        text = rh.gs(ApsStrings.hypo_risk_notification_text)
                    )
                }
            },
        )
        return AimiCarbsAdvisorEnableSmbSafetyStage(
            forcedBasalmealmodes = decided.forcedBasalmealmodes,
            forcedBasal = decided.forcedBasal,
            enableSMB = decided.enableSMB,
            mealModeActive = decided.mealModeActive,
            zeroSinceMin = decided.zeroSinceMin,
            minutesSinceLastChange = decided.minutesSinceLastChange,
            safetyDecision = decided.safetyDecision,
        )
    }

    private sealed class AimiCarbsAdvisorHardHypoBasalGateResult {
        data class ReturnZeroTempBasal(val rT: RT) : AimiCarbsAdvisorHardHypoBasalGateResult()
        data class Continue(val stage: AimiCarbsAdvisorEnableSmbSafetyStage) : AimiCarbsAdvisorHardHypoBasalGateResult()
    }

    /**
     * [runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage] puis, si [SafetyDecision.stopBasal], **TBR 0 % 30 min** (même [setTempBasal] / [causalState.adaptiveMult] qu’avant).
     */
    private fun runCarbsAdvisorEnableSmbSafetyAndHardHypoBasalStopOrReturn(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        iobData: IobTotal,
        csf: Double,
        slopeFromDeviations: Double,
        sens: Double,
        bg: Double,
        iob: Float,
        cob: Float,
        delta: Float,
        eventualBG: Double,
        combinedDelta: Float,
        deviation: Int,
        bgi: Double,
        targetBgSchedule: Double,
        maxBgSchedule: Double,
        windowSinceDoseInt: Int,
    ): AimiCarbsAdvisorHardHypoBasalGateResult {
        val stage = runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage(
            profile = profile,
            ctx = ctx,
            rT = rT,
            glucoseStatus = glucoseStatus,
            iobData = iobData,
            csf = csf,
            slopeFromDeviations = slopeFromDeviations,
            sens = sens,
            bg = bg,
            iob = iob,
            cob = cob,
            delta = delta,
            eventualBG = eventualBG,
            combinedDelta = combinedDelta,
            deviation = deviation,
            bgi = bgi,
            targetBgSchedule = targetBgSchedule,
            maxBgSchedule = maxBgSchedule,
            windowSinceDoseInt = windowSinceDoseInt,
        )
        if (stage.safetyDecision.stopBasal) {
            return AimiCarbsAdvisorHardHypoBasalGateResult.ReturnZeroTempBasal(
                setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, adaptiveMultiplier = causalState.adaptiveMult),
            )
        }
        return AimiCarbsAdvisorHardHypoBasalGateResult.Continue(stage)
    }

    private sealed class AimiPostSafetyMealNgrStageResult {
        data class EarlyTempBasal(val rt: RT) : AimiPostSafetyMealNgrStageResult()
        data class Continue(
            val isMealActive: Boolean,
            val runtimeMinValue: Int,
            val maxIobLimit: Double,
            val basal: Double,
            val smbToGive: Float,
        ) : AimiPostSafetyMealNgrStageResult()
    }

    /**
     * Repas 0–30 min (TBR forcée éventuelle) → **NGR** (evaluate + headroom IOB + boost basal/SMB).
     * La décision est [decideMealFirst30NgrHeadroomBasalSmb]. Cette coquille lit les champs à la ligne.
     */
    private fun runPostSafetyMealFirst30NgrHeadroomBasalSmbStage(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        rT: RT,
        ngrConfig: NGRConfig,
        safetyDecision: SafetyDecision,
        forcedBasalmealmodes: Double,
        maxIobLimitIn: Double,
        basalIn: Double,
        smbToGiveIn: Float,
        bg: Double,
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        eventualBG: Double,
        targetBgSchedule: Double,
    ): AimiPostSafetyMealNgrStageResult {
        val stage = decideMealFirst30NgrHeadroomBasalSmb(
            profile = profile,
            ctx = ctx,
            rT = rT,
            ngrConfig = ngrConfig,
            safetyDecision = safetyDecision,
            forcedBasalmealmodes = forcedBasalmealmodes,
            maxIobLimitIn = maxIobLimitIn,
            basalIn = basalIn,
            smbToGiveIn = smbToGiveIn,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            eventualBG = eventualBG,
            targetBgSchedule = targetBgSchedule,
            preferences = preferences,
            texts = rh,
            consoleLog = work.consoleLog,
            state = object : AimiMealFirstNgrState {
                override fun mealTime() = work.mealTime
                override fun mealRuntime() = work.mealruntime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun bfastRuntime() = work.bfastruntime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun lunchRuntime() = work.lunchruntime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun dinnerRuntime() = work.dinnerruntime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun highCarbRuntime() = work.highCarbrunTime
                override fun adaptiveMult() = this@DetermineBasalaimiSMB2.causalState.adaptiveMult
                override fun maxSMB() = work.maxSMB
                override fun setMaxIob(value: Double) {
                    work.maxIob = value
                }
            },
            effects = AimiMealFirstTempBasal { rate, durationMin, profile, rT, currenttemp, overrideSafetyLimits, forceExact, adaptiveMultiplier ->
                setTempBasal(
                    rate,
                    durationMin,
                    profile,
                    rT,
                    currenttemp,
                    overrideSafetyLimits = overrideSafetyLimits,
                    forceExact = forceExact,
                    adaptiveMultiplier = adaptiveMultiplier,
                )
            },
            ngr = AimiNightGrowthEvaluate { now, bg, delta, shortAvgDelta, longAvgDelta, eventualBG, targetBG, work.iob, work.cob, react, isMealActive, config ->
                nightGrowthResistanceMode.evaluate(
                    now = now,
                    bg = bg,
                    delta = delta,
                    shortAvgDelta = shortAvgDelta,
                    longAvgDelta = longAvgDelta,
                    eventualBG = eventualBG,
                    targetBG = targetBG,
                    iob = work.iob,
                    cob = work.cob,
                    react = react,
                    isMealActive = isMealActive,
                    config = config,
                )
            },
        )
        return when (stage) {
            is AimiMealFirstNgrStage.EarlyTempBasal ->
                AimiPostSafetyMealNgrStageResult.EarlyTempBasal(stage.rt)
            is AimiMealFirstNgrStage.Continue ->
                AimiPostSafetyMealNgrStageResult.Continue(
                    isMealActive = stage.isMealActive,
                    runtimeMinValue = stage.runtimeMinValue,
                    maxIobLimit = stage.maxIobLimit,
                    basal = stage.basal,
                    smbToGive = stage.smbToGive,
                )
        }
    }

    private sealed class AimiCoreDecisionMaxIobGateResult {
        data class ReturnTempBasal(val rt: RT) : AimiCoreDecisionMaxIobGateResult()
        data class ContinueSMBPath(
            val allowMealHighIob: Boolean,
            val mealHighIobDamping: Double,
        ) : AimiCoreDecisionMaxIobGateResult()
    }

    /**
     * Repas-montée IOB relax puis gate MAX_IOB. La décision est [decideMaxIobExceededTempBasal].
     * Cette coquille lit les champs à la ligne.
     */
    private fun runCoreDecisionMaxIobExceededTempBasalGate(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        rT: RT,
        originalProfile: OapsProfileAimi,
        flatBGsDetected: Boolean,
        mealModeActive: Boolean,
        maxIobLimit: Double,
        safetyDecision: SafetyDecision,
        basal: Double,
        bg: Double,
        delta: Float,
        eventualBG: Double,
        targetBgSchedule: Double,
        loopIob: Double,
    ): AimiCoreDecisionMaxIobGateResult {
        val stage = decideMaxIobExceededTempBasal(
            profile = profile,
            ctx = ctx,
            rT = rT,
            originalProfile = originalProfile,
            flatBGsDetected = flatBGsDetected,
            mealModeActive = mealModeActive,
            maxIobLimit = maxIobLimit,
            safetyDecision = safetyDecision,
            basal = basal,
            bg = bg,
            delta = delta,
            eventualBG = eventualBG,
            targetBgSchedule = targetBgSchedule,
            loopIob = loopIob,
            texts = rh,
            state = object : AimiMaxIobGateState {
                override fun studyExporter() = hormonitorStudyExporter
                override fun activityContext() =
                    cachedActivityContext ?: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext()
                override fun adaptiveMult() = this@DetermineBasalaimiSMB2.causalState.adaptiveMult
                override fun withoutZeros(value: Double) = value.withoutZeros()
            },
            effects = AimiMaxIobTempBasal { rate, durationMin, profile, rT, currenttemp, overrideSafetyLimits, forceExact, adaptiveMultiplier ->
                setTempBasal(
                    rate,
                    durationMin,
                    profile,
                    rT,
                    currenttemp,
                    overrideSafetyLimits = overrideSafetyLimits,
                    forceExact = forceExact,
                    adaptiveMultiplier = adaptiveMultiplier,
                )
            },
            comparison = comparator,
            decisionLog = AimiDecisionLog { tag, result, bg, delta ->
                logDecisionFinal(tag, result, bg, delta)
            },
        )
        return when (stage) {
            is AimiMaxIobGateStage.ReturnTempBasal ->
                AimiCoreDecisionMaxIobGateResult.ReturnTempBasal(stage.rt)
            is AimiMaxIobGateStage.ContinueSMBPath ->
                AimiCoreDecisionMaxIobGateResult.ContinueSMBPath(
                    allowMealHighIob = stage.allowMealHighIob,
                    mealHighIobDamping = stage.mealHighIobDamping,
                )
        }
    }

    /**
     * Chemin **insulinReq** après le gate MAX_IOB. La décision est
     * [decideInsulinReqActivityRelaxAndMicrobolus]. Cette coquille lit les champs à la ligne.
     */
    private fun runInsulinReqActivityRelaxAndMicrobolusStage(
        ctx: AimiTickContext,
        rT: RT,
        iobTotal: IobTotal,
        smbToGive: Float,
        allowMealHighIob: Boolean,
        mealHighIobDamping: Double,
        maxIobLimit: Double,
        safetyDecision: SafetyDecision,
        enableSMB: Boolean,
        isMealActive: Boolean,
        bg: Double,
        delta: Float,
        hypoThresholdMgdl: Double,
        systemTime: Long,
        basalBoostApplied: Boolean,
        basalBoostSource: String?,
    ) {
        decideInsulinReqActivityRelaxAndMicrobolus(
            ctx = ctx,
            rT = rT,
            iobTotal = iobTotal,
            smbToGive = smbToGive,
            allowMealHighIob = allowMealHighIob,
            mealHighIobDamping = mealHighIobDamping,
            maxIobLimit = maxIobLimit,
            safetyDecision = safetyDecision,
            enableSMB = enableSMB,
            isMealActive = isMealActive,
            bg = bg,
            delta = delta,
            hypoThresholdMgdl = hypoThresholdMgdl,
            systemTime = systemTime,
            basalBoostApplied = basalBoostApplied,
            basalBoostSource = basalBoostSource,
            texts = rh,
            consoleLog = work.consoleLog,
            state = object : AimiInsulinReqState {
                override fun activityProtectionMode() = work.activityProtectionMode
                override fun activityStateIntense() = work.activityStateIntense
                override fun maxSMB() = work.maxSMB
                override fun hyperReleaseFloorU(): Double =
                    work.lastHyperTrajectoryRelease?.takeIf { it.active }?.smbFloorU ?: 0.0
            },
            smbIntervalPort = AimiInsulinReqSmbInterval { calculateSMBInterval() },
            finalize = AimiInsulinReqFinalize { rT, proposedUnits, reasonHeader, mealData, hypoThreshold, isExplicitUserAction, decisionSource, isMealActive, hyperReleaseFloorU ->
                finalizeAndCapSMB(
                    rT = rT,
                    proposedUnits = proposedUnits,
                    reasonHeader = reasonHeader,
                    mealData = mealData,
                    hypoThreshold = hypoThreshold,
                    isExplicitUserAction = isExplicitUserAction,
                    decisionSource = decisionSource,
                    isMealActive = isMealActive,
                    hyperReleaseFloorU = hyperReleaseFloorU,
                )
            },
        )
    }

    /**
     * Paramètres explicites pour [runBasalDecisionEngineDecideStage] ; drapeaux repas / runtimes lus sur l’instance
     * (**`snackTime`**, **`mealruntime`**, etc.) comme dans le bloc historique.
     */
    private data class AimiBasalDecisionEngineStageBundle(
        val ctx: AimiTickContext,
        val profile: OapsProfileAimi,
        val rT: RT,
        val glucoseStatus: GlucoseStatusAIMI,
        val featuresCombinedDelta: Double?,
        val profileCurrentBasal: Double,
        val basalEstimate: Double,
        val tdd7P: Double,
        val tdd7Days: Double,
        val variableSensitivity: Double,
        val predictedBg: Double,
        val targetBg: Double,
        val tickIobForEngine: Double,
        val engineMaxIob: Double,
        val eventualBg: Double,
        val bg: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val combinedDelta: Double,
        val bgAcceleration: Double,
        val allowMealHighIob: Boolean,
        val safetyDecision: SafetyDecision,
        val forcedBasal: Double,
        val forcedBasalMealModesMax: Double,
        val isMealActive: Boolean,
        val runtimeMinValue: Int,
        val smbToGive: Double,
        val zeroSinceMin: Int,
        val minutesSinceLastChange: Int,
        val pumpCaps: PumpCaps,
        val timenowHour: Int,
        val sixAmHour: Int,
        val pregnancyEnable: Boolean,
        val nightMode: Boolean,
        val modesCondition: Boolean,
        val autodrivePref: Boolean,
        val honeymoon: Boolean,
    )

    /** Construit [BasalDecisionEngine.Input], [BasalDecisionEngine.Helpers], appelle [BasalDecisionEngine.decide]. */
    private fun runBasalDecisionEngineDecideStage(
        bundle: AimiBasalDecisionEngineStageBundle,
    ): BasalDecisionEngine.Decision = decideBasalDecisionEngine(
        currentTemp = bundle.ctx.currentTemp,
        mealData = bundle.ctx.mealData,
        profile = bundle.profile,
        rT = bundle.rT,
        glucoseStatus = bundle.glucoseStatus,
        featuresCombinedDelta = bundle.featuresCombinedDelta,
        profileCurrentBasal = bundle.profileCurrentBasal,
        basalEstimate = bundle.basalEstimate,
        tdd7P = bundle.tdd7P,
        tdd7Days = bundle.tdd7Days,
        variableSensitivity = bundle.variableSensitivity,
        predictedBg = bundle.predictedBg,
        targetBg = bundle.targetBg,
        tickIobForEngine = bundle.tickIobForEngine,
        engineMaxIob = bundle.engineMaxIob,
        eventualBg = bundle.eventualBg,
        bg = bundle.bg,
        delta = bundle.delta,
        shortAvgDelta = bundle.shortAvgDelta,
        longAvgDelta = bundle.longAvgDelta,
        combinedDelta = bundle.combinedDelta,
        bgAcceleration = bundle.bgAcceleration,
        allowMealHighIob = bundle.allowMealHighIob,
        safetyDecision = bundle.safetyDecision,
        forcedBasal = bundle.forcedBasal,
        forcedBasalMealModesMax = bundle.forcedBasalMealModesMax,
        isMealActive = bundle.isMealActive,
        runtimeMinValue = bundle.runtimeMinValue,
        smbToGive = bundle.smbToGive,
        zeroSinceMin = bundle.zeroSinceMin,
        minutesSinceLastChange = bundle.minutesSinceLastChange,
        pumpCaps = bundle.pumpCaps,
        timenowHour = bundle.timenowHour,
        sixAmHour = bundle.sixAmHour,
        pregnancyEnable = bundle.pregnancyEnable,
        nightMode = bundle.nightMode,
        modesCondition = bundle.modesCondition,
        autodrivePref = bundle.autodrivePref,
        honeymoon = bundle.honeymoon,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiBasalDecisionEngineCalls {
            override fun snackTime() = work.snackTime
            override fun snackRuntime() = work.snackrunTime
            override fun fastingTime() = work.fastingTime
            override fun sportTime() = work.sportTime
            override fun mealTime() = work.mealTime
            override fun mealRuntime() = work.mealruntime
            override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
            override fun bfastRuntime() = work.bfastruntime
            override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
            override fun lunchRuntime() = work.lunchruntime
            override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
            override fun dinnerRuntime() = work.dinnerruntime
            override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
            override fun highCarbRuntime() = work.highCarbrunTime
            override fun recentSteps5Minutes() = this@DetermineBasalaimiSMB2.recentSteps5Minutes
            override fun calculateRate(
                basal: Double,
                currentBasal: Double,
                multiplier: Double,
                reason: String,
                currentTemp: CurrentTemp,
                rT: RT,
            ) = this@DetermineBasalaimiSMB2.calculateRate(
                basal, currentBasal, multiplier, reason, currentTemp, rT,
            )
            override fun detectMealOnset(
                delta: Float,
                predictedDelta: Float,
                acceleration: Float,
                predictedBg: Float,
                targetBg: Float,
            ) = this@DetermineBasalaimiSMB2.detectMealOnset(
                delta, predictedDelta, acceleration, predictedBg, targetBg,
            )
            override fun engine() = basalDecisionEngine
        },
    )

    /**
     * Paramètres pour [runPostBasalEngineLearnersRtInstrumentationAndAuditorStage] : repères tick
     * (**[flatBGsDetected]** local post-bootstrap), **[pkpdRuntime]** courant, **[intervalsmb]** SMB.
     */
    private data class AimiPostBasalEngineFinalizeBundle(
        val ctx: AimiTickContext,
        val profile: OapsProfileAimi,
        val originalProfile: OapsProfileAimi,
        val rT: RT,
        val basalDecision: BasalDecisionEngine.Decision,
        val flatBGsDetected: Boolean,
        val pkpdRuntime: PkPdRuntime?,
        val tdd7Days: Double,
        val intervalsmb: Int,
    )

    private fun isNativeT3cRuntimeOwnerConfigured(): Boolean =
        preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) &&
            RecursiveBeliefPreferences.from(preferences).authorityEnabled

    private fun legacyT3cBypassAllowed(): Boolean =
        preferences.get(BooleanKey.OApsAIMIT3cBrittleMode) &&
            !isNativeT3cRuntimeOwnerConfigured()

    private fun markT3cRuntimeOwnership(
        mode: String,
        reason: String,
    ) {
        work.lastT3cRuntimeOwnership = AimiDecisionContext.T3cRuntimeOwnershipExport(
            mode = mode,
            native_owner_active = isNativeT3cRuntimeOwnerConfigured(),
            legacy_fallback_allowed = legacyT3cBypassAllowed(),
            reason = reason,
        )
    }

    private data class T3cBasalFirstApplyPlan(
        val rateUph: Double,
        val durationMin: Int,
        val decisionSource: String = "RBT_T3C_BASAL_FIRST",
    )

    private data class HarmoniaProductionApplyPlan(
        val rateUph: Double,
        val requestedRateUph: Double,
        val sourceAction: HarmoniaAction,
        val branch: String,
        val durationMin: Int = 30,
        val decisionSource: String = "HARMONIA_PRODUCTION_BASAL_FIRST",
    )

    private fun updateT3cBasalFirstRuntimeState(
        selectedForProduction: Boolean,
        appliedRateUph: Double? = null,
        appliedDurationMin: Int? = null,
        runtimeBlocker: String? = null,
    ) {
        val snapshot = work.lastRecursiveBeliefSnapshot ?: return
        val t3cState = snapshot.resolutions.t3cBasalFirst ?: return
        work.lastRecursiveBeliefSnapshot = snapshot.copy(
            resolutions = snapshot.resolutions.copy(
                t3cBasalFirst = t3cState.copy(
                    selectedForProduction = selectedForProduction,
                    historicalBypassNeutralized = work.lastT3cHistoricalBypassNeutralizedThisTick,
                    appliedRateUph = appliedRateUph,
                    appliedDurationMin = appliedDurationMin,
                    runtimeBlocker = runtimeBlocker,
                ),
            ),
        )
    }

    private fun updateHarmoniaBasalFirstRuntimeState(
        selectedForProduction: Boolean,
        appliedRateUph: Double? = null,
        appliedDurationMin: Int? = null,
        runtimeBlocker: String? = null,
    ) {
        val snapshot = work.lastRecursiveBeliefSnapshot ?: return
        val harmoniaState = snapshot.resolutions.harmoniaBasalFirst ?: return
        work.lastRecursiveBeliefSnapshot = snapshot.copy(
            resolutions = snapshot.resolutions.copy(
                harmoniaBasalFirst = harmoniaState.copy(
                    selectedForProduction = selectedForProduction,
                    appliedRateUph = appliedRateUph,
                    appliedDurationMin = appliedDurationMin,
                    runtimeBlocker = runtimeBlocker,
                ),
            ),
        )
    }

    private fun blockT3cBasalFirstProduction(
        t3cState: T3cBasalFirstResolution?,
        runtimeBlocker: String,
    ): T3cBasalFirstApplyPlan? {
        markT3cRuntimeOwnership("NATIVE_BLOCKED", runtimeBlocker)
        if (t3cState != null) {
            updateT3cBasalFirstRuntimeState(
                selectedForProduction = false,
                runtimeBlocker = runtimeBlocker,
            )
        }
        work.consoleLog.add("🌳 T3C_NATIVE: not applied blocker=$runtimeBlocker")
        return null
    }

    /**
     * Native T3C basal-first plan. The decision is [decideT3cBasalFirstProduction].
     * Gates that can change are read at the line. A block still logs and returns null.
     */
    private fun planT3cBasalFirstProduction(
        b: AimiPostBasalEngineFinalizeBundle,
    ): T3cBasalFirstApplyPlan? {
        val decided = decideT3cBasalFirstProduction(
            profile = b.profile,
            rT = b.rT,
            consoleLog = work.consoleLog,
            calls = object : AimiT3cBasalFirstCalls {
                override fun nativeOwnerConfigured() = isNativeT3cRuntimeOwnerConfigured()
                override fun snapshot() = work.lastRecursiveBeliefSnapshot
                override fun usLower(value: String) = value.lowercase()
                override fun block(state: T3cBasalFirstResolution?, reason: String) {
                    blockT3cBasalFirstProduction(state, reason)
                }
                override fun historicalBypassNeutralized() = work.lastT3cHistoricalBypassNeutralizedThisTick
                override fun smbAuthorityActive() =
                    work.lastRecursiveAuthorityGateDecision?.effectiveAuthority != ReleaseAuthority.NONE
                override fun guardsActive() = basalChannelSafetyGuardsActive()
                override fun smbZeroedBySafety() = smbZeroedBySafetyThisTick()
                override fun noteGuardBlocked() {
                    work.basalChannelGuardBlockedT3cCount++
                }
                override fun exerciseLockout() = work.exerciseInsulinLockoutActive
                override fun postHypoActive() = work.lastPostHypoDeliveryAuthority.active
                override fun iobForGate() = resolveIobForGate()
                override fun maxIob() = work.maxIob
                override fun stackingSurveillance() =
                    work.lastInsulinStackingEvaluation?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB
                override fun mealContext() = MealSafetyContext(
                    mealModeActive = work.mealTime || lunchTime || dinnerTime || work.snackTime || highCarbTime || bfastTime,
                    manualBolusAgeMin = internalLastSmbMillis.takeIf { it > 0L }?.let { (dateUtil.now() - it) / 60000.0 },
                    inferredMealSignal = inferredMealSafetyIntent(),
                )
                override fun sanitizedPredictedEventual(): Pair<Double, Double> =
                    sanitizedHypoGuardPredictedEventual(
                        rT = b.rT,
                        predictedBg = predictedBg.toDouble(),
                        eventualBg = work.eventualBG,
                    )
                override fun lgsCurve(): Pair<Double?, Boolean> = resolveLgsMinPredictedCurve(b.rT)
                override fun bg() = bg
                override fun delta() = delta.toDouble()
                override fun currentTempDuration() = b.ctx.currentTemp.duration
                override fun currentTempRate() = b.ctx.currentTemp.rate
                override fun capRate(requestedRateUph: Double, profileBasalUph: Double) =
                    capBasalRateForCorrectionAggression(
                        requestedRateUph = requestedRateUph,
                        profileBasalUph = profileBasalUph,
                        source = "RBT_T3C_BASAL_FIRST",
                    )
                override fun markReady() {
                    markT3cRuntimeOwnership("NATIVE_READY", "native_rbt_owner")
                }
            },
        )
        return decided?.let {
            T3cBasalFirstApplyPlan(
                rateUph = it.rateUph,
                durationMin = it.durationMin,
            )
        }
    }

    private fun recordHarmoniaProductionDecision(
        mode: HarmoniaProductionMode,
        selectedForProduction: Boolean,
        requestedRateUph: Double?,
        boundedRateUph: Double?,
        appliedRateUph: Double?,
        appliedDurationMin: Int?,
        runtimeBlocker: String?,
        safetyBlockers: List<String>,
        sourceAction: HarmoniaAction?,
        branch: String?,
        reason: String,
    ) {
        work.lastHarmoniaProductionDecision = HarmoniaProductionDecision(
            timestampMs = dateUtil.now(),
            mode = mode,
            selectedForProduction = selectedForProduction,
            requestedRateUph = requestedRateUph,
            boundedRateUph = boundedRateUph,
            appliedRateUph = appliedRateUph,
            appliedDurationMin = appliedDurationMin,
            runtimeBlocker = runtimeBlocker,
            safetyBlockers = safetyBlockers.distinct(),
            sourceAction = sourceAction,
            branch = branch,
            reason = reason,
        )
    }

    private fun blockHarmoniaProduction(
        simulation: HarmoniaDecision?,
        runtimeBlocker: String,
    ): HarmoniaProductionApplyPlan? {
        recordHarmoniaProductionDecision(
            mode = HarmoniaProductionMode.BLOCKED,
            selectedForProduction = false,
            requestedRateUph = simulation?.targetBasalUph,
            boundedRateUph = null,
            appliedRateUph = null,
            appliedDurationMin = null,
            runtimeBlocker = runtimeBlocker,
            safetyBlockers = listOf(runtimeBlocker),
            sourceAction = simulation?.action,
            branch = simulation?.branch,
            reason = runtimeBlocker,
        )
        updateHarmoniaBasalFirstRuntimeState(
            selectedForProduction = false,
            runtimeBlocker = runtimeBlocker,
        )
        work.consoleLog.add("🌿 HARMONIA_PROD: not applied blocker=$runtimeBlocker")
        return null
    }

    private fun harmoniaCriticalMealConflict(): Boolean =
        work.lastMealAbsorptionOutput?.mealDeliveryPriority == true &&
            (
                causalState.lastUamHypothesisState?.suppressMealInterpretation == true ||
                    work.lastPhysiologicalPhaseOutput?.policy?.suppressMealLikeScenario == true
                )

    private fun planHarmoniaProductionBranch(
        b: AimiPostBasalEngineFinalizeBundle,
    ): HarmoniaProductionApplyPlan? {
        val ramp = decideHarmoniaProductionRamp(
            rT = b.rT,
            profileMinBg = b.profile.min_bg,
            profileMaxBasal = b.profile.max_basal,
            profileCurrentBasal = b.profile.current_basal,
            profileLgsThreshold = b.profile.lgsThreshold,
            currentTempDuration = b.ctx.currentTemp.duration,
            currentTempRate = b.ctx.currentTemp.rate,
            consoleLog = work.consoleLog,
            calls = object : AimiHarmoniaRampCalls {
                override fun harmoniaDecision() = work.lastHarmoniaDecision
                override fun beliefSnapshot() = work.lastRecursiveBeliefSnapshot
                override fun block(simulation: HarmoniaDecision, blocker: String) {
                    blockHarmoniaProduction(simulation, blocker)
                }
                override fun effectiveAuthority() = work.lastRecursiveAuthorityGateDecision?.effectiveAuthority
                override fun basalChannelGuardsActive() = basalChannelSafetyGuardsActive()
                override fun smbZeroedBySafety() = smbZeroedBySafetyThisTick()
                override fun noteBasalChannelBlockedHarmonia() {
                    work.basalChannelGuardBlockedHarmoniaCount++
                }
                override fun exerciseLockout() = work.exerciseInsulinLockoutActive
                override fun postHypoGuardActive() = work.lastPostHypoDeliveryAuthority.active
                override fun criticalMealConflict() = harmoniaCriticalMealConflict()
                override fun physioRisk() = work.lastPhysiologicalTreeSnapshot?.trunk?.riskLevel
                override fun iobForGate() = resolveIobForGate()
                override fun maxIob() = work.maxIob
                override fun stackingKind() = work.lastInsulinStackingEvaluation?.kind
                override fun mealModeActive() =
                    work.mealTime || lunchTime || dinnerTime || work.snackTime || highCarbTime || bfastTime
                override fun manualBolusAgeMin() =
                    internalLastSmbMillis.takeIf { it > 0L }?.let { (dateUtil.now() - it) / 60000.0 }
                override fun inferredMealIntent() = inferredMealSafetyIntent()
                override fun predictedBg() = this@DetermineBasalaimiSMB2.predictedBg
                override fun eventualBg() = work.eventualBG
                override fun sanitizedHypoTerminals(predictedBg: Double, eventualBg: Double) =
                    sanitizedHypoGuardPredictedEventual(b.rT, predictedBg, eventualBg)
                override fun lgsMinPredictedCurve(rT: RT) = resolveLgsMinPredictedCurve(rT)
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun correctionFragility() =
                    work.lastPatientState?.eventMemory?.correctionFragilityScore ?: 0.0
                override fun capForCorrectionAggression(
                    requestedRateUph: Double,
                    profileBasalUph: Double,
                    source: String,
                ) = capBasalRateForCorrectionAggression(requestedRateUph, profileBasalUph, source)
                override fun recordReady(
                    requestedRateUph: Double,
                    boundedRateUph: Double,
                    sourceAction: HarmoniaAction,
                    branch: String,
                ) {
                    recordHarmoniaProductionDecision(
                        mode = HarmoniaProductionMode.READY,
                        selectedForProduction = true,
                        requestedRateUph = requestedRateUph,
                        boundedRateUph = boundedRateUph,
                        appliedRateUph = null,
                        appliedDurationMin = null,
                        runtimeBlocker = null,
                        safetyBlockers = emptyList(),
                        sourceAction = sourceAction,
                        branch = branch,
                        reason = "production_basal_first_ready",
                    )
                }
            },
        ) ?: return null
        return HarmoniaProductionApplyPlan(
            rateUph = ramp.rateUph,
            requestedRateUph = ramp.requestedRateUph,
            sourceAction = ramp.sourceAction,
            branch = ramp.branch,
        )
    }

    private fun updateHarmoniaProductionAfterFinal(
        plan: HarmoniaProductionApplyPlan,
        finalResult: RT,
    ) {
        val appliedRate = finalResult.rate?.coerceAtLeast(0.0) ?: 0.0
        val appliedDuration = maxOf(finalResult.duration ?: plan.durationMin, 30)
        val runtimeBlocker = if (appliedRate <= 0.0 && plan.rateUph > 0.0) {
            "final_settemp_block"
        } else {
            null
        }
        recordHarmoniaProductionDecision(
            mode = if (runtimeBlocker == null) HarmoniaProductionMode.APPLIED else HarmoniaProductionMode.BLOCKED,
            selectedForProduction = runtimeBlocker == null,
            requestedRateUph = plan.requestedRateUph,
            boundedRateUph = plan.rateUph,
            appliedRateUph = appliedRate,
            appliedDurationMin = appliedDuration,
            runtimeBlocker = runtimeBlocker,
            safetyBlockers = if (runtimeBlocker != null) listOf(runtimeBlocker) else emptyList(),
            sourceAction = plan.sourceAction,
            branch = plan.branch,
            reason = runtimeBlocker ?: "harmonia_basal_first_applied",
        )
        updateHarmoniaBasalFirstRuntimeState(
            selectedForProduction = runtimeBlocker == null,
            appliedRateUph = appliedRate,
            appliedDurationMin = appliedDuration,
            runtimeBlocker = runtimeBlocker,
        )
        work.consoleLog.add(
            if (runtimeBlocker == null) {
                "🌿 HARMONIA_PROD: applied ${aimiFmt2(appliedRate)}U/h for ${appliedDuration}m"
            } else {
                "🌿 HARMONIA_PROD: blocked after setTempBasal ($runtimeBlocker)"
            },
        )
    }

    /**
     * Après [runBasalDecisionEngineDecideStage] : learners (dont [basalLearner.process] **après** le moteur),
     * visualisation trajectoire, fusion TBR prioritaire, [setTempBasal], [comparator], instrumentation RT,
     * puis [auditorOrchestrator.auditDecision].
     *
     * **Async / ordre** : [auditDecision] peut compléter plus tard dans un callback (mutation de [RT]).
     * [runAimiSnapshotMedicalJsonAndHormonitorExportStage] s’exécute **après** l’**appel** à [auditDecision],
     * pas après le callback — la ligne JSONL principale inclut `adjustments.auditor_tick` (disposition sync) ;
     * un verdict externe tardif est appendé en `record_type=auditor_followup` (advisory, non bloquant).
     */
    private fun runPostBasalEngineLearnersRtInstrumentationAndAuditorStage(
        b: AimiPostBasalEngineFinalizeBundle,
    ): RT {
        val iob_data = b.ctx.iobDataArray[0]

        // --- Update Learners BEFORE building final result ---
        val currentHour = aimiLocalHour()
        val anyMealActive = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime
        val isNight = currentHour >= 22 || currentHour <= 6

        basalLearner.process(
            currentBg = bg,
            currentDelta = delta.toDouble(),
            tdd7Days = b.tdd7Days,
            tdd30Days = resolveTdd30DaysForLearner(b.tdd7Days),
            isFastingTime = isNight && !anyMealActive
        )
        notifyBasalLearnerHypoIfNeeded(b.ctx.currentTime)
        notifyBasalLearnerPersistentHyperIfNeeded(b.ctx.currentTime)

        // 📊 Expose BasalLearner state in rT for visibility
        work.consoleLog.add("📊 BASAL_LEARNER:")
        work.consoleLog.add("  │ shortTerm: ${aimiFmt3(basalLearner.shortTermMultiplier)}")
        work.consoleLog.add("  │ mediumTerm: ${aimiFmt3(basalLearner.mediumTermMultiplier)}")
        work.consoleLog.add("  │ longTerm: ${aimiFmt3(basalLearner.longTermMultiplier)}")
        work.consoleLog.add("  └ combined: ${aimiFmt3(basalLearner.getMultiplier())}")

        // 🎯 Process UnifiedReactivityLearner
        unifiedReactivityLearner.processIfNeeded()

        // 📊 Expose UnifiedReactivityLearner state in rT for visibility
        unifiedReactivityLearner.lastAnalysis?.let { analysis ->
            work.consoleLog.add("📊 REACTIVITY_LEARNER:")
            work.consoleLog.add("  │ globalFactor: ${aimiFmt3(analysis.globalFactor)}")
            work.consoleLog.add("  │ shortTermFactor: ${aimiFmt3(analysis.shortTermFactor)}")
            work.consoleLog.add("  │ segmentFactor (${analysis.segmentDaypart.name}): ${aimiFmt3(analysis.segmentFactor)}")
            work.consoleLog.add("  │ combinedFactor: ${aimiFmt3(unifiedReactivityLearner.getCombinedFactor(work.hourOfDay))}")
            work.consoleLog.add("  │ TIR 70-180: ${analysis.tir70_180.toInt()}%")
            work.consoleLog.add("  │ CV%: ${analysis.cv_percent.toInt()}%")
            work.consoleLog.add("  │ Hypo count (24h): ${analysis.hypo_count}")
            if (analysis.floorLocked) {
                work.consoleLog.add("  │ floorLock: ACTIVE @0.50 (${analysis.floorLockReason})")
            } else if (analysis.floorReleased) {
                work.consoleLog.add("  │ floorLock: RELEASED progressively")
            }
            work.consoleLog.add("  │ Reason: ${analysis.adjustmentReason}")
            work.consoleLog.add("  └ Analyzed at: ${aimiCsvTimestamp(analysis.timestamp)}")
        }

        // 🔮 WCycle Active Learning
        if (wCyclePreferences.enabled()) {
            val phase = wCycleFacade.getPhase()
            if (phase != app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase.UNKNOWN) {
                wCycleFacade.updateLearning(phase, b.ctx.autosensData.ratio)
            }
        }

        // 🌀 TRAJECTORY VISUALIZATION (AIMI 2.1)
        try {
            val now = aimiWallClockMs()
            val currentActivity = (iob_data.iob * 1.0) // simplified activity equivalent
            val targetOrb = app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit(targetBg = targetBg.toDouble(), targetActivity = 0.0)

            val history = listOf(
                app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                    timestamp = now - 900000,
                    bg = bg - (shortAvgDelta * 3),
                    bgDelta = shortAvgDelta.toDouble(),
                    bgAccel = 0.0,
                    insulinActivity = currentActivity,
                    iob = iob_data.iob,
                    pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                    timeSinceLastBolus = 0
                ),
                app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                    timestamp = now - 300000,
                    bg = bg - delta,
                    bgDelta = delta.toDouble(),
                    bgAccel = (delta - shortAvgDelta).toDouble(),
                    insulinActivity = currentActivity,
                    iob = iob_data.iob,
                    pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                    timeSinceLastBolus = 0
                ),
                app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState(
                    timestamp = now,
                    bg = bg,
                    bgDelta = delta.toDouble(),
                    bgAccel = (delta - shortAvgDelta).toDouble(),
                    insulinActivity = currentActivity,
                    iob = iob_data.iob,
                    pkpdStage = app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL,
                    timeSinceLastBolus = 0
                )
            )

            val metrics = app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetricsCalculator.calculateAll(history, targetOrb)

            if (metrics != null) {
                val healthPercent = (metrics.healthScore * 100).toInt()
                val healthBar = "█".repeat(healthPercent / 10) + "░".repeat(10 - (healthPercent / 10))

                val type = when {
                    metrics.isStable -> "⭕ Stable Orbit"
                    metrics.isConverging -> "🔄 Converging"
                    metrics.isDiverging -> "↗️ Diverging"
                    metrics.isTightSpiral -> "🌀 Spiral"
                    else -> "❓ Uncertain"
                }

                val etaText = if (metrics.convergenceVelocity > 0) {
                    val eta = app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetricsCalculator.estimateConvergenceTime(history, targetOrb)
                    if (eta != null) "$eta min to work.stable orbit" else "Approaching..."
                } else {
                    "Diverging from target"
                }

                work.consoleLog.add("─────────────────────────────────┐")
                work.consoleLog.add("│ 🌀 TRAJECTORY STATUS            │")
                work.consoleLog.add("├─────────────────────────────────┤")
                work.consoleLog.add("│ Type: ${type.padEnd(26)}│")
                work.consoleLog.add("│ Health: $healthBar $healthPercent%          │")
                work.consoleLog.add("│ ETA: ${etaText.padEnd(27)}│")
                work.consoleLog.add("│                                 │")
                work.consoleLog.add("│ Metrics:                        │")
                work.consoleLog.add("│ ├─ Curvature:    ${aimiFmt2(metrics.curvature).padEnd(15)}│")
                work.consoleLog.add("│ ├─ Convergence:  ${aimiFmtSigned2(metrics.convergenceVelocity).padEnd(15)}│")
                work.consoleLog.add("│ ├─ Coherence:    ${aimiFmt2(metrics.coherence).padEnd(15)}│")
                work.consoleLog.add("│ ├─ Energy:       ${aimiFmtSigned1(metrics.energyBalance).padEnd(15)}│")

                try {
                    val cached = app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(600_000)
                    if (cached != null) {
                        work.consoleLog.add("│                                 │")
                        val aiIcon = "🤖"
                        val verdictEnum = cached.verdict.verdict
                        val action = verdictEnum.name
                        work.consoleLog.add("│ $aiIcon AI: ${"$action (${(cached.verdict.confidence * 100).toInt()}%)".padEnd(25)}│")
                        val evidenceList = cached.verdict.evidence
                        val evidenceStr = if (evidenceList.isNotEmpty()) evidenceList[0] else ""
                        val evidence = evidenceStr.replace("\n", " ").take(30)
                        work.consoleLog.add("│ > ${evidence.padEnd(30)}│")
                    }
                } catch (_: Exception) {
                }

                work.consoleLog.add("└─────────────────────────────────┘")
            }
        } catch (_: Exception) {
        }

        // 📊 Build learners summary for RT visibility (finalResult.learnersInfo)
        val learnersParts = mutableListOf<String>()
        val basalMult = basalLearner.getMultiplier()
        if (kotlin.math.abs(basalMult - 1.0) > 0.01) {
            learnersParts.add("Basal×" + aimiFmt2(basalMult))
        }
        b.pkpdRuntime?.let { runtime ->
            val profileIsf = b.profile.sens
            if (kotlin.math.abs(runtime.fusedIsf - profileIsf) > 0.5) {
                learnersParts.add("ISF:" + runtime.fusedIsf.toInt())
            }
        }
        val reactivityFactor = unifiedReactivityLearner.getCombinedFactor(work.hourOfDay)
        if (kotlin.math.abs(reactivityFactor - 1.0) > 0.01) {
            learnersParts.add("React×" + aimiFmt2(reactivityFactor))
        }
        val learnersSummary = learnersParts.joinToString(", ")

        val engineRate = b.basalDecision.rate
        val mergedRate = CorrectionAggressionBasalCap.mergeEngineAndRtRates(
            engineRateUph = engineRate,
            rtRateUph = b.rT.rate,
            gate = work.correctionAggressionDecision,
        )
        var finalProposedRate = capBasalRateForCorrectionAggression(
            requestedRateUph = mergedRate,
            profileBasalUph = b.profile.current_basal,
            source = "FINAL_BASAL_MERGE",
        )
        var finalDuration = if (b.rT.rate != null) {
            maxOf(b.rT.duration ?: 30, 30)
        } else {
            maxOf(b.basalDecision.duration, 30)
        }
        var finalOverrideSafetyLimits = b.basalDecision.overrideSafety
        var finalAdaptiveMultiplier = causalState.adaptiveMult

        val t3cNativePlan = planT3cBasalFirstProduction(b)
        val harmoniaProductionPlan = if (t3cNativePlan == null) {
            planHarmoniaProductionBranch(b)
        } else {
            recordHarmoniaProductionDecision(
                mode = HarmoniaProductionMode.SKIPPED,
                selectedForProduction = false,
                requestedRateUph = work.lastHarmoniaDecision?.targetBasalUph,
                boundedRateUph = null,
                appliedRateUph = null,
                appliedDurationMin = null,
                runtimeBlocker = "t3c_native_owner",
                safetyBlockers = emptyList(),
                sourceAction = work.lastHarmoniaDecision?.action,
                branch = work.lastHarmoniaDecision?.branch,
                reason = "t3c_native_owner",
            )
            null
        }
        if (t3cNativePlan != null) {
            finalProposedRate = t3cNativePlan.rateUph
            finalDuration = t3cNativePlan.durationMin
            finalOverrideSafetyLimits = false
            finalAdaptiveMultiplier = basalFirstAdaptiveMultiplier()
            work.lastDecisionSource = t3cNativePlan.decisionSource
            b.rT.reason.append("; 🌳T3C_NATIVE_BASAL_FIRST")
        } else if (harmoniaProductionPlan != null) {
            finalProposedRate = harmoniaProductionPlan.rateUph
            finalDuration = harmoniaProductionPlan.durationMin
            finalOverrideSafetyLimits = false
            finalAdaptiveMultiplier = basalFirstAdaptiveMultiplier()
            work.lastDecisionSource = harmoniaProductionPlan.decisionSource
            b.rT.reason.append("; 🌿HARMONIA_PRODUCTION_BASAL_FIRST")
        }

        val harmonizerOutcome = HarmoniaHarmonizer.evaluate(
            tree = work.lastPhysiologicalTreeSnapshot,
            simulation = work.lastHarmoniaDecision,
            bgMgdl = bg,
            deltaMgdl5m = delta.toDouble(),
            profileBasalUph = b.profile.current_basal,
            proposedTbrUph = finalProposedRate,
            eventualBgMgdl = work.eventualBG.takeIf { it.isFinite() && it > 1.0 },
            targetBgMgdl = b.profile.target_bg.toDouble(),
            correctionFragilityScore = work.lastPatientState?.eventMemory?.correctionFragilityScore ?: 0.0,
            postHyperExhaustionScore = work.lastPatientState?.eventMemory?.postHyperExhaustionScore ?: 0.0,
            minBgLookback75m = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            mealCertainty = work.lastMealCertainty,
        )
        work.lastHarmonizerOutcome = harmonizerOutcome
        when (harmonizerOutcome?.posture) {
            HarmoniaHarmonizer.Posture.BLOCK -> {
                finalProposedRate = b.profile.current_basal.coerceAtLeast(0.0)
                b.rT.reason.append("; 🌿HARMONIA_HARMONIZER_BLOCK")
            }
            HarmoniaHarmonizer.Posture.SOFTEN -> {
                finalProposedRate = (finalProposedRate * harmonizerOutcome.tbrFactor)
                    .coerceAtLeast(0.0)
                b.rT.reason.append("; 🌿HARMONIA_HARMONIZER_SOFTEN")
            }
            HarmoniaHarmonizer.Posture.CONFIRM,
            null,
            -> Unit
        }
        harmonizerOutcome?.let { outcome ->
            work.consoleLog.add("🌿 HARMONIA_HARMONIZER: ${outcome.toJsonSummary()} ${outcome.reasons.joinToString(",")}")
        }

        // 🏃 Effort/activity protection — basal side. Reduction-only damping from the single sensor effort belief
        // (graded, fail-safe: only lowers). SINGLE reduction per tick, guaranteed coverage: applied here for every
        // tick EXCEPT when Harmonia already owns the basal via PROTECTIVE_REDUCTION (fed by the same effort branch,
        // so it already reduced) — that avoids double-counting while keeping the same protection on every tick.
        val harmoniaAlreadyReducedForEffort =
            harmoniaProductionPlan?.sourceAction == HarmoniaAction.PROTECTIVE_REDUCTION
        work.lastEffortAssessment?.basalFactor?.takeIf { it < 1.0 && !harmoniaAlreadyReducedForEffort }?.let { factor ->
            val damped = (finalProposedRate * factor).coerceAtLeast(0.0)
            work.consoleLog.add(
                "🏃 EFFORT_BASAL_DAMP: ${aimiFmt2(finalProposedRate)}→${aimiFmt2(damped)} U/h (×${aimiFmt2(factor)}, ${work.lastEffortAssessment?.state?.name})"
            )
            b.rT.reason.append("; 🏃EFFORT×${aimiFmt2(factor)}")
            finalProposedRate = damped
        }

        // 🩸 Anti-whiplash : borne la HAUSSE de basale par tick (la baisse reste instantanée pour LGS). Exempté :
        // boost forcé/override, modes repas et sport, qui doivent réagir vite. Voir [slewLimitBasalUp].
        val mealModeActiveForSlew = work.mealTime || bfastTime || lunchTime || dinnerTime || work.snackTime || highCarbTime
        if (preferences.get(BooleanKey.OApsAIMIBasalSlewLimitEnabled) &&
            !finalOverrideSafetyLimits && !mealModeActiveForSlew && !work.sportTime
        ) {
            val prevRateUph = b.ctx.currentTemp.rate
            val slewed = slewLimitBasalUp(prevRateUph, finalProposedRate, b.profile.current_basal)
            if (slewed < finalProposedRate) {
                work.consoleLog.add(
                    "🩸 BASAL_SLEW_LIMIT: ${aimiFmt2(finalProposedRate)}→${aimiFmt2(slewed)} U/h (prev ${aimiFmt2(prevRateUph)})"
                )
                b.rT.reason.append("; 🩸SLEW")
                finalProposedRate = slewed
            }
        }

        // 🍽️ Declared-meal anticipation — see [AnticipationBasalFloor].
        // Applied last, after the slew limiter, because a floor that the limiter can clamp away is
        // not a floor. It only ever RAISES the rate (max of the two), and only while the note window
        // is open, the opt-in key is on and the declaration still looks true; the gesture stands down
        // on its own under 80 mg/dL or on a fall, and deleting the note ends it at once.
        if (preferences.get(BooleanKey.OApsAIMIAnticipBasalFloor) && anticipTime) {
            AnticipationBasalFloor.floorRateUph(
                budgetU = preferences.get(DoubleKey.OApsAIMIAnticipBudgetU),
                elapsedMinutes = anticipruntime.toDouble(),
                profileBasalUph = b.profile.current_basal,
                // Same ceiling the declared meal modes use, so a declaration cannot reach higher
                // than a meal mode already can.
                maxBasalUph = maxOf(b.profile.max_basal, preferences.get(DoubleKey.meal_modes_MaxBasal)),
                bgMgdl = bg.toDouble(),
                deltaMgdl5m = delta.toDouble(),
            )?.let { floorUph ->
                if (floorUph > finalProposedRate) {
                    work.consoleLog.add(
                        "🍽️ ANTICIP_BASAL_FLOOR: ${aimiFmt2(finalProposedRate)}→${aimiFmt2(floorUph)} U/h (budget ${aimiFmt2(preferences.get(DoubleKey.OApsAIMIAnticipBudgetU))} U over ${aimiFmt0(AnticipationBasalFloor.WINDOW_MINUTES)} min, elapsed $anticipruntime min)"
                    )
                    b.rT.reason.append("; 🍽️anticip ${aimiFmt2(floorUph)}U/h")
                    finalProposedRate = floorUph
                }
            }
        }

        // 🍽️ FCL declared meal — see [FclMealBasal]. Applied here, beside the declared-meal floor and
        // for the same two reasons: this is the last point where the rate can still be raised, and the
        // SMB stage has already run by now, so the bolus channel stays alive. An early return from the
        // meal-boost stage would have skipped it, which is how the declared meal modes behave and is
        // not what was asked for here.
        FclMealBasal.rateUph(
            fclNoteActive = fclTime,
            sportNoteActive = work.sportTime,
            tempTargetSet = b.profile.temptargetSet,
            // The raw profile target on purpose: it carries the temp target the person set, while the
            // engine's own working target has already been reshaped by this point.
            targetBgMgdl = b.profile.target_bg,
            mealModesMaxBasalUph = preferences.get(DoubleKey.meal_modes_MaxBasal),
            profileMaxBasalUph = b.profile.max_basal,
            profileBasalUph = b.profile.current_basal,
            bgMgdl = bg.toDouble(),
            deltaMgdl5m = delta.toDouble(),
        )?.let { floorUph ->
            if (floorUph > finalProposedRate) {
                work.consoleLog.add(
                    "🍽️ FCL_MEAL_BASAL: ${aimiFmt2(finalProposedRate)}→${aimiFmt2(floorUph)} U/h (temp target ${aimiFmt0(b.profile.target_bg)} mg/dL, note $fclruntime min ago)"
                )
                b.rT.reason.append("; 🍽️FCL ${aimiFmt2(floorUph)}U/h")
                finalProposedRate = floorUph
                // The same bypass the declared meal modes already use, so FCL is "lunch without the
                // prebolus" and not a weaker version of it. It lifts one clamp only — the daily-safety
                // one — up to max_basal. The LGS block, the DynamicBasalController brake and the
                // max_basal hard cap inside setTempBasal all still apply.
                finalOverrideSafetyLimits = true
            }
        }

        val finalResult = setTempBasal(
            _rate = finalProposedRate,
            duration = finalDuration,
            profile = b.profile,
            rT = b.rT,
            currenttemp = b.ctx.currentTemp,
            overrideSafetyLimits = finalOverrideSafetyLimits,
            adaptiveMultiplier = finalAdaptiveMultiplier
        )
        if (t3cNativePlan != null) {
            val appliedRate = finalResult.rate?.coerceAtLeast(0.0) ?: 0.0
            val appliedDuration = maxOf(finalResult.duration ?: finalDuration, 30)
            val runtimeBlocker = if (appliedRate <= 0.0 && t3cNativePlan.rateUph > 0.0) {
                "final_settemp_block"
            } else {
                null
            }
            markT3cRuntimeOwnership(
                mode = if (runtimeBlocker == null) "NATIVE_APPLIED" else "NATIVE_BLOCKED",
                reason = runtimeBlocker ?: "t3c_basal_first_applied",
            )
            updateT3cBasalFirstRuntimeState(
                selectedForProduction = runtimeBlocker == null,
                appliedRateUph = appliedRate,
                appliedDurationMin = appliedDuration,
                runtimeBlocker = runtimeBlocker,
            )
            work.consoleLog.add(
                if (runtimeBlocker == null) {
                    "🌳 T3C_NATIVE: applied ${aimiFmt2(appliedRate)}U/h for ${appliedDuration}m"
                } else {
                    "🌳 T3C_NATIVE: blocked after setTempBasal ($runtimeBlocker)"
                },
            )
        } else if (harmoniaProductionPlan != null) {
            updateHarmoniaProductionAfterFinal(harmoniaProductionPlan, finalResult)
        }
        comparator.compare(
            aimiResult = finalResult,
            glucoseStatus = b.ctx.glucoseStatus,
            currentTemp = b.ctx.currentTemp,
            iobData = b.ctx.iobDataArray,
            profileAimi = b.originalProfile,
            autosens = b.ctx.autosensData,
            mealData = b.ctx.mealData,
            microBolusAllowed = b.ctx.microBolusAllowed,
            currentTime = b.ctx.currentTime,
            flatBGsDetected = b.flatBGsDetected,
            dynIsfMode = b.ctx.dynIsfMode
        )

        finalResult.rate = finalResult.rate?.coerceAtLeast(0.0) ?: 0.0

        if (learnersSummary.isNotEmpty()) {
            finalResult.learnersInfo = learnersSummary
            finalResult.reason.append("; [").append(learnersSummary).append("]")
            work.consoleLog.add("📊 Learners applied to finalResult.reason: [" + learnersSummary + "]")
        }

        val urFactor = unifiedReactivityLearner.getCombinedFactor(work.hourOfDay)
        val profileIsf = b.profile.sens
        val fusedIsf = b.pkpdRuntime?.fusedIsf
        val pkpdDiaMin = b.pkpdRuntime?.params?.diaHrs?.let { (it * 60).toInt() }
        val pkpdPeakMin = b.pkpdRuntime?.params?.peakMin?.toInt()
        val pkpdTailPct = b.pkpdRuntime?.tailFraction?.let { (it * 100).toInt() }
        val learnersDebugLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildLearnersLine(
            unifiedReactivityFactor = urFactor,
            profileIsf = profileIsf,
            fusedIsf = fusedIsf,
            pkpdDiaMin = pkpdDiaMin,
            pkpdPeakMin = pkpdPeakMin,
            pkpdTailPct = pkpdTailPct
        )
        finalResult.reason.append("\n").append(learnersDebugLine)
        if (wCyclePreferences.enabled()) {
            val wcyclePhase = wCycleFacade.getPhase()?.name
            val wcycleFactor = wCycleFacade.getIcMultiplier()
            val wcycleLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildWCycleLine(
                enabled = true,
                phase = wcyclePhase,
                factor = wcycleFactor
            )
            if (wcycleLine != null) {
                finalResult.reason.append("\n").append(wcycleLine)
            }
        }
        val auditorDebugLine = app.aaps.plugins.aps.openAPSAIMI.utils.RtInstrumentationHelpers.buildAuditorLine(
            enabled = preferences.get(BooleanKey.AimiAuditorEnabled)
        )
        finalResult.reason.append("\n").append(auditorDebugLine)
        work.consoleLog.add("📊 RT instrumentation: 2-3 debug lines added to reason")

        val auditorEnabled = preferences.get(BooleanKey.AimiAuditorEnabled)
        aapsLogger.debug(LTag.APS, "🧠 AI Auditor: Preference value = $auditorEnabled")
        finalResult.aiAuditorEnabled = auditorEnabled

        if (auditorEnabled) {
            try {
                val smbProposed = (finalResult.units ?: b.rT.units ?: 0.0)
                val tbrRate = finalResult.rate
                val tbrDuration = finalResult.duration
                val intervalMin = b.intervalsmb
                val smb30min = calculateSmbLast30Min()
                val predictionAvailable = (finalResult.predBGs?.IOB?.size ?: 0) > 0
                val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
                val inPrebolusWindow = when {
                    therapy.bfastTime -> {
                        val runtimeMin = therapy.getTimeElapsedSinceLastEvent("bfast") / 60000
                        runtimeMin in 0..30
                    }
                    therapy.lunchTime -> {
                        val runtimeMin = therapy.getTimeElapsedSinceLastEvent("lunch") / 60000
                        runtimeMin in 0..30
                    }
                    therapy.dinnerTime -> {
                        val runtimeMin = therapy.getTimeElapsedSinceLastEvent("dinner") / 60000
                        runtimeMin in 0..30
                    }
                    therapy.highCarbTime -> {
                        val runtimeMin = therapy.getTimeElapsedSinceLastEvent("highcarb") / 60000
                        runtimeMin in 0..30
                    }
                    else -> false
                }
                val modeType = when {
                    therapy.bfastTime -> "breakfast"
                    therapy.lunchTime -> "lunch"
                    therapy.dinnerTime -> "dinner"
                    therapy.highCarbTime -> "highCarb"
                    therapy.snackTime -> "snack"
                    therapy.mealTime -> "meal"
                    else -> null
                }
                val modeRuntimeMin = when {
                    therapy.bfastTime -> (therapy.getTimeElapsedSinceLastEvent("bfast") / 60000).toInt()
                    therapy.lunchTime -> (therapy.getTimeElapsedSinceLastEvent("lunch") / 60000).toInt()
                    therapy.dinnerTime -> (therapy.getTimeElapsedSinceLastEvent("dinner") / 60000).toInt()
                    therapy.highCarbTime -> (therapy.getTimeElapsedSinceLastEvent("highcarb") / 60000).toInt()
                    therapy.snackTime -> (therapy.getTimeElapsedSinceLastEvent("snack") / 60000).toInt()
                    therapy.mealTime -> (therapy.getTimeElapsedSinceLastEvent("meal") / 60000).toInt()
                    else -> null
                }
                val autodriveState = causalState.lastAutodriveState.toString()
                val wcyclePhase = wCycleFacade.getPhase()?.name
                val wcycleFactor = wCycleFacade.getIcMultiplier()
                val reasonTags = finalResult.reason.toString().split(". ").map { it.trim() }
                val auditorEffectiveProfile: EffectiveProfile? = effectiveProfileCached(dateUtil.now())
                lastAuditorAuditStartedAtMs = dateUtil.now()
                // Captured here, not read again inside the callback: `currentTickDecisionEventId` is
                // reset on the next tick (see its declaration), and the profile proposal can land
                // after that has already happened.
                val auditEventId = work.currentTickDecisionEventId ?: ""
                // The audited tick itself, built the same way every ring entry is, so the window the
                // profile checker is shown always ends on a fact of the same shape as the rest of it.
                val currentAuditorFact = buildAuditorTickFact(b.ctx, b.profile)
                val profileFactorRequest = AuditorProfileFactorRequest(
                    auditEventId = auditEventId,
                    ticks = auditorTickRing.snapshot(b.ctx.currentTime) + currentAuditorFact,
                    mealCertainty = work.lastMealCertainty,
                    mealModeName = modeType,
                    minBg75mMgdl = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
                    cgmNoise = b.ctx.glucoseStatus.noise,
                    keyOn = auditorProfileTick.keyOn,
                    contextBuiltAtMs = b.ctx.currentTime,
                )
                auditorOrchestrator.auditDecision(
                    bg = bg,
                    delta = delta.toDouble(),
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    longAvgDelta = longAvgDelta.toDouble(),
                    glucoseStatus = b.ctx.glucoseStatus,
                    iob = b.ctx.iobDataArray.firstOrNull() ?: IobTotal(dateUtil.now()).apply { iob = 0.0; activity = 0.0 },
                    cob = work.cob.toDouble(),
                    profile = b.profile,
                    pkpdRuntime = b.pkpdRuntime,
                    isfUsed = b.profile.variable_sens,
                    smbProposed = smbProposed,
                    tbrRate = tbrRate,
                    tbrDuration = tbrDuration,
                    intervalMin = intervalMin.toDouble(),
                    maxSMB = work.maxSMB,
                    maxSMBHB = work.maxSMBHB,
                    maxIOB = work.maxIob,
                    maxBasal = b.profile.max_basal,
                    reasonTags = reasonTags,
                    modeType = modeType,
                    modeRuntimeMin = modeRuntimeMin,
                    autodriveState = autodriveState,
                    wcyclePhase = wcyclePhase,
                    wcycleFactor = wcycleFactor,
                    tbrMaxMode = null,
                    tbrMaxAutoDrive = null,
                    smb30min = smb30min,
                    predictionAvailable = predictionAvailable,
                    predictedBg = this.predictedBg?.toDouble(),
                    eventualBg = b.rT.eventualBG,
                    inPrebolusWindow = inPrebolusWindow,
                    effectiveProfile = auditorEffectiveProfile,
                    mealCertainty = work.lastMealCertainty,
                    harmoniaProduction = work.lastHarmoniaProductionDecision,
                    harmonizerOutcome = work.lastHarmonizerOutcome,
                    // Null while the key is off, and the auditor then gets exactly the fields it
                    // has always been given.
                    levels = auditorProfileTick.snapshotLevelsIfArmed(),
                    profileFactorRequest = profileFactorRequest,
                    // One line per proposal, in the same JSONL the loop already writes to. The parent
                    // event id was captured above, before this closure can run.
                    onProfileProposal = { proposal ->
                        appendAimiDecisionsJsonlLine(proposal.toJsonLine(auditEventId))
                    },
                    onSyncDisposition = { disposition ->
                        recordAuditorSyncDisposition(
                            disposition = disposition,
                            loopSmbU = smbProposed,
                            loopTbrUph = tbrRate,
                            loopIntervalMin = intervalMin,
                        )
                    },
                ) { verdict: AuditorVerdict?, result: DecisionResult ->
                    when (result) {
                        is DecisionResult.Applied -> {
                            work.consoleLog.add(sanitizeForJson("🧠 AI Auditor: ✅ APPLIED - ${result.reason}"))
                            if (verdict != null) {
                                work.consoleLog.add(sanitizeForJson("   Verdict: ${verdict.verdict}, Conf: ${aimiFmt2(verdict.confidence)}"))
                            }
                            applySmbUnits(finalResult, result.bolusU ?: 0.0, "AiAuditor")
                            if (result.tbrUph != null) {
                                finalResult.rate = result.tbrUph
                            }
                            if (result.tbrMin != null) {
                                finalResult.duration = result.tbrMin
                            }
                        }
                        is DecisionResult.Rejected -> {
                            work.consoleLog.add(sanitizeForJson("🧠 AI Auditor: 🛑 REJECTED - ${result.reason}"))
                            work.consoleLog.add(sanitizeForJson("   Severity: ${result.severity}"))
                            finalResult.units = 0.0
                            finalResult.reason.setLength(0)
                            finalResult.reason.append("Auditor Rejected: ${result.reason}")
                        }
                        is DecisionResult.Skipped -> {
                            work.consoleLog.add(sanitizeForJson("🧠 AI Auditor: ⏸ SKIPPED - ${result.reason}"))
                        }
                        else -> {}
                    }
                    maybeAppendAuditorFollowupLine(verdict, result)
                }
            } catch (e: Exception) {
                work.consoleLog.add(sanitizeForJson("⚠️ AI Auditor error: ${e.message}"))
                aapsLogger.error(LTag.APS, "AI Auditor exception", e)
            }
        }

        // Main-path basal neural CSV + ML training (same hook as logDecisionFinal early exits).
        applyBasalNeuralLearningAndTraining(
            rT = finalResult,
            tbrUph = (finalResult.rate ?: 0.0).coerceAtLeast(0.0),
            govTag = "MAIN",
        )

        // Refresh the earlier early-exit snapshot after main-path governance learning has completed.
        assignAimiAdaptationStatus(finalResult)
        return finalResult
    }

    /**
     * Builds the unified intelligence snapshot for JSONL export and downstream consumers.
     */
    private fun buildIntelligenceSnapshot(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
    ): AimiIntelligenceSnapshot? {
        val effectiveProfile = effectiveProfileCached(ctx.currentTime) ?: return null
        val physioMults = work.lastFusedPhysioMultipliers ?: work.lastBasePhysioMultipliers
        val learningDiagnostics = PkpdLearningDiagnostics.from(
            causalStatePosterior = work.lastPatientState?.causalPosterior,
            allowLearning = preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath),
            exerciseFlag = work.sportTime,
            iobU = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            carbsActiveG = ctx.mealData.mealCOB,
            bg = ctx.glucoseStatus.glucose,
            deltaMgDlPer5 = ctx.glucoseStatus.delta,
        )
        return AimiIntelligenceSnapshotBuilder.build(
            AimiIntelligenceSnapshotBuilder.BuildInput(
                timestampMs = ctx.currentTime,
                accountingIobArray = ctx.iobDataArray,
                profile = profile,
                effectiveProfile = effectiveProfile,
                pkpdRuntime = pkpdRuntime,
                peakGovernor = null,
                causalPosterior = work.lastPatientState?.causalPosterior,
                physioPeakShiftMinutes = physioMults.peakShiftMinutes,
                // Site branch: the snapshot used to leave this at its 0.0 default, so the peak
                // governor always reported site=0.0. The cannula age is already cached here.
                sitePeakShiftMinutes = TapSitePeakShift.minutesForSiteAge(pumpAgeDaysCached()),
                preferences = preferences,
                iobCobCalculator = iobCobCalculator,
                pkpdPredictionIobArray = ctx.pkpdIobDataArray,
                learningDiagnostics = learningDiagnostics,
                predictionAuthority = work.lastDecisionPredictionAuthority,
            ),
        )
    }

    /**
     * [decisionCtx] dynamique ISF + outcome + surveillance IOB ; persistance **AIMI_Decisions.jsonl** ;
     * [AimiLoopTelemetry.enterPhase] EXPORT ; export Hormonitor (**I/O disque**).
     */
    private fun runAimiSnapshotMedicalJsonAndHormonitorExportStage(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        decisionCtx: AimiDecisionContext,
        finalResult: RT,
        pkpdRuntime: PkPdRuntime?,
    ) {
        val snapshotFusedIsf = pkpdRuntime?.fusedIsf ?: profile.sens
        val snapshotProfileIsf = profile.sens

        // Shadow only — nothing reads the result. Feeds the outcome-based sensitivity estimator once
        // per tick, unconditionally: an estimator that only runs when a controller engages is not an
        // estimator. See `docs/adr/0008-isf-decision-architecture.md`.
        val staticIsfForRatio = IsfSourceTelemetry.lastProfileStaticMgdl ?: 0.0
        runCatching {
            // Basal that was actually **running** over the interval just elapsed. `finalResult.rate`
            // is the rate this tick is about to request, which the pump has not delivered yet — using
            // it made the basal-deficit correction read the future instead of the past. A temp basal
            // with no remaining duration means the profile rate is what ran.
            val runningTemp = ctx.currentTemp
            val deliveredBasalUph =
                if (runningTemp.duration > 0) runningTemp.rate else profile.current_basal
            sensitivityRatioEstimator.observe(
                SensitivityRatioEstimator.Sample(
                    timestampMs = decisionCtx.timestamp,
                    bgMgdl = decisionCtx.baseline_state.current_bg_mgdl,
                    iobU = decisionCtx.baseline_state.iob_u,
                    profileBasalUph = profile.current_basal,
                    deliveredBasalUph = deliveredBasalUph,
                    smbU = finalResult.units ?: 0.0,
                    profileIsfMgdl = staticIsfForRatio,
                    // Any digestion disqualifies the window: absorption hides part of the fall, so
                    // the ratio reads low, so the sensitivity commanded from it reads low, so the
                    // loop would give more insulin. See SensitivityRatioEstimator.
                    cobG = decisionCtx.baseline_state.cob_g,
                    // Boluses of any origin, not only AIMI's own SMBs.
                    lastBolusMs = ctx.iobDataArray.firstOrNull()?.lastBolusTime ?: 0L,
                ),
            )
        }

        // Reference measurement, strictly passive. It rebuilds the sensitivity the outcomes imply,
        // -dBG / absorbed insulin over clean falls, outside the ISF chain. No dosing decision reads
        // it: it only reaches baseline_state, next to command_isf_mgdl, so the two can be compared.
        runCatching {
            val runningTempForMeter = ctx.currentTemp
            observedSensitivityMeter.observe(
                ObservedSensitivityMeter.Sample(
                    timestampMs = decisionCtx.timestamp,
                    localHourOfDay = aimiLocalHour(decisionCtx.timestamp),
                    bgMgdl = decisionCtx.baseline_state.current_bg_mgdl,
                    // Net of the profile basal: it goes negative when the loop cuts the basal for a
                    // long time, which is why the basal integral below is needed.
                    iobU = decisionCtx.baseline_state.iob_u,
                    cobG = decisionCtx.baseline_state.cob_g,
                    smbU = finalResult.units ?: 0.0,
                    // The basal that ran is the temp in progress, not the rate this tick asks for.
                    deliveredBasalUph =
                        if (runningTempForMeter.duration > 0) runningTempForMeter.rate else profile.current_basal,
                    profileBasalUph = profile.current_basal,
                    // Previous tick's value. A one-tick lag does not matter for a threshold filter
                    // over a 30 to 120 minute window. Do not swap this for the "used" field: it is
                    // only set on the engaged Autodrive branch, so it would be null most of the time
                    // and the fail-closed rule would reject every window.
                    raMgdlPerMin = decisionCtx.baseline_state.estimated_ra_mgdl_per_min,
                    lastBolusMs = ctx.iobDataArray.firstOrNull()?.lastBolusTime ?: 0L,
                ),
            )
            val observed = observedSensitivityMeter.read(decisionCtx.timestamp)
            decisionCtx.baseline_state.isf_obs_median_mgdl = observed.medianMgdlPerU
            decisionCtx.baseline_state.isf_obs_night_median_mgdl = observed.nightMedianMgdlPerU
            decisionCtx.baseline_state.isf_obs_day_median_mgdl = observed.dayMedianMgdlPerU
            decisionCtx.baseline_state.isf_obs_window_count = observed.windowCount
            decisionCtx.baseline_state.isf_obs_night_count = observed.nightCount
            decisionCtx.baseline_state.isf_obs_day_count = observed.dayCount
            decisionCtx.baseline_state.isf_obs_last_window_end_ms = observed.lastWindow?.endMs
            decisionCtx.baseline_state.isf_obs_last_window_mgdl = observed.lastWindow?.isfMgdlPerU
            decisionCtx.baseline_state.isf_obs_last_window_drop_mgdl = observed.lastWindow?.dropMgdl
            decisionCtx.baseline_state.isf_obs_last_window_absorbed_u = observed.lastWindow?.absorbedU
        }

        // Shadow only — measurement of the late fat damping window against the old rise predicate.
        // Nothing downstream reads these three fields; they exist to size the divergence on a real
        // support package before any wiring is decided.
        runCatching {
            decisionCtx.baseline_state.late_fat_damping_window = isLateFatDampingWindow(decisionCtx.timestamp)
            decisionCtx.baseline_state.late_fat_rise_flag = lateFatRiseFlagForExport
            decisionCtx.baseline_state.late_fat_onset_age_min =
                MealAbsorptionMemory.onsetAgeMin(decisionCtx.timestamp)?.roundToInt()
        }

        // Observation only — the Harmonia counterfactual. It answers two questions and changes
        // nothing: "would Harmonia propose something else if it were told the safety verdict", and
        // "how much insulin does the refusal move". The verdict below is a mirror of the real guard;
        // it is never handed back to the engine, which is what `harmonia_verdict_known_to_engine`
        // records. See `HarmoniaCounterfactual`.
        runCatching {
            val verdict = HarmoniaSafetyVerdict(
                criticalSafetyZeroed = work.criticalSafetyZeroedThisTick,
                contextSuppressSmb = work.lastContextSuppressSmb,
                mealModeActive = manualMealModeActive(),
                guardsEnabled = basalChannelSafetyGuardsActive(),
            )
            val counterfactual = HarmoniaCounterfactual.evaluate(
                simulation = work.lastHarmoniaDecision,
                production = work.lastHarmoniaProductionDecision,
                verdict = verdict,
                profileBasalUph = profile.current_basal,
                appliedRateUph = finalResult.rate,
                appliedDurationMin = finalResult.duration ?: 30,
            )
            decisionCtx.baseline_state.harmonia_cf_rule = counterfactual.rule.name
            decisionCtx.baseline_state.harmonia_cf_action = counterfactual.counterfactualAction?.name
            decisionCtx.baseline_state.harmonia_cf_basal_uph = counterfactual.counterfactualBasalUph
            decisionCtx.baseline_state.harmonia_cf_changes_proposal = counterfactual.changesProposal
            decisionCtx.baseline_state.harmonia_block_was_escalation = counterfactual.requestWasEscalation
            decisionCtx.baseline_state.harmonia_block_delta_uph = counterfactual.requestDeltaVsProfileUph
            decisionCtx.baseline_state.harmonia_block_stake_u = counterfactual.blockStakeU
            decisionCtx.baseline_state.harmonia_applied_gap_uph = counterfactual.appliedGapUph
            decisionCtx.baseline_state.harmonia_verdict_critical_safety = verdict.criticalSafetyZeroed
            decisionCtx.baseline_state.harmonia_verdict_context_suppress = verdict.contextSuppressSmb
            decisionCtx.baseline_state.harmonia_verdict_meal_mode = verdict.mealModeActive
            // Hard-coded false: witness of inertia. It stays false for as long as no decision path
            // reads the verdict. The day one does, this line has to change with it.
            decisionCtx.baseline_state.harmonia_verdict_known_to_engine = false
            decisionCtx.baseline_state.harmonia_prev_blocker = harmoniaPrevRuntimeBlocker
            decisionCtx.baseline_state.harmonia_prev_blocked_streak = harmoniaBlockedStreak
            val trunkRisk = work.lastPhysiologicalTreeSnapshot?.trunk?.riskLevel
            val harmoniaRisk = work.lastHarmoniaDecision?.decisionBasis?.trunkRisk
            decisionCtx.baseline_state.harmonia_tree_risk_divergence =
                if (trunkRisk != null && harmoniaRisk != null && trunkRisk != harmoniaRisk) {
                    "tronc=${trunkRisk.name}|harmonia=${harmoniaRisk.name}"
                } else {
                    null
                }
        }

        decisionCtx.adjustments.dynamic_isf = AimiDecisionContext.DynamicIsf(
            final_value_mgdl = snapshotFusedIsf,
            modifiers = mutableListOf<AimiDecisionContext.Modifier>().apply {
                if (ctx.autosensData.ratio != 1.0) {
                    add(AimiDecisionContext.Modifier(
                        source = "Autosensitivity",
                        factor = 1.0 / ctx.autosensData.ratio,
                        clinical_reason = "Rolling avg sensitivity: ${aimiFmt2(ctx.autosensData.ratio)}"
                    ))
                }
                val autosensAdjIsf = snapshotProfileIsf * (1.0 / ctx.autosensData.ratio)
                if (abs(snapshotFusedIsf - autosensAdjIsf) > 1.0) {
                    add(AimiDecisionContext.Modifier(
                        source = "PkPd_Fusion",
                        factor = snapshotFusedIsf / autosensAdjIsf,
                        clinical_reason = "Fusion with TDD & Profile"
                    ))
                }
            }
        )

        val terminalSmbAmountU = SmbBindingTrace.terminalAmountU(finalResult.units)
        decisionCtx.outcome = AimiDecisionContext.Outcome(
            clinical_decision = if ((finalResult.units ?: 0.0) > 0) "SMB_Delivery" else if ((finalResult.rate ?: profile.current_basal) != profile.current_basal) "Basal_Modulation" else "No_Action",
            dosage_u = terminalSmbAmountU,
            target_basal_uph = finalResult.rate,
            narrative_explanation = finalResult.reason.toString().replace("\n", " | ").take(2048)
        )

        decisionCtx.adjustments.iob_surveillance = lastIobSurveillanceExport
        decisionCtx.adjustments.iob_release = lastIobReleaseExport
        decisionCtx.adjustments.safety_risk = lastSafetyRiskExport?.toDecisionContextExport()
        decisionCtx.adjustments.scenario_projection = lastScenarioProjection?.toDecisionContextExport()
        decisionCtx.adjustments.hyper_trajectory_release = work.lastHyperTrajectoryRelease?.toDecisionContextExport()
        decisionCtx.adjustments.physiological_phase = physiologicalPhaseExport()
        val patternsJson = work.lastPhysiologicalPatternSnapshot?.takeIf { it.active.isNotEmpty() }?.let { patterns ->
            PhysiologicalPatternExport.toJsonObject(patterns).also {
                decisionCtx.adjustments.physiological_patterns = it
            }
        }
        decisionCtx.adjustments.meal_absorption_phase = mealAbsorptionPhaseExport()
        decisionCtx.adjustments.pred_divergence = lastPredDivergenceExport
        val rbtPrefs = RecursiveBeliefPreferences.from(preferences)
        var harmoniaSmbAuthorityJson: JsonObject? = null
        work.lastRecursiveBeliefSnapshot?.let { snap ->
            val t3cApplied = snap.resolutions.t3cBasalFirst?.selectedForProduction == true
            val harmoniaApplied = snap.resolutions.harmoniaBasalFirst?.selectedForProduction == true
            val export = UnfoldExporter.toExport(
                snapshot = snap,
                shadowOnly = if (t3cApplied || harmoniaApplied) false else work.lastRecursiveAuthorityGateDecision?.shadowOnly == true,
                authorityApplied = t3cApplied || harmoniaApplied || (
                    work.lastRecursiveAuthorityGateDecision?.effectiveAuthority?.let {
                        it != ReleaseAuthority.NONE
                    } ?: false
                    ),
                waveletBands = snap.waveletBands,
                authorityGate = work.lastRecursiveAuthorityGateDecision,
            )
            decisionCtx.adjustments.recursive_belief = UnfoldExporter.toJsonObject(export)
            snap.resolutions.harmoniaSmb?.let { smb ->
                // Prefer the arbiter's own record. Its `demand_before_u` is the demand BEFORE the
                // lift, and it also carries `mpc_demand_u`, `envelope_max_u`,
                // `catalog_proposed_cap_u` and the arbiter reasons. The rebuilt record below reads
                // `demand_before_u` AFTER the lift was already applied, so a lift always measured as
                // zero. Fall back to it only on a tick where no arbiter ran.
                harmoniaSmbAuthorityJson = smb.authorityDecision?.toJsonObject()
                    ?: JsonObj().apply {
                        put("mode", smb.authorityMode ?: AimiJson.NULL)
                        putFiniteOrNull("smb_u", smb.demandAfterU)
                        putFiniteOrNull("demand_before_u", smb.demandBeforeU)
                        putFiniteOrNull("max_smb_cap_u", smb.maxSmbCapU)
                        put("adds_smb_authority", smb.addsSmbAuthority)
                        put("insulin_intent", smb.insulinIntent ?: AimiJson.NULL)
                        put("reason_codes", JsonArr(smb.reasonCodes))
                        put("source", "harmonia_smb_authority_v1")
                    }.build()
                decisionCtx.adjustments.harmonia_smb_authority = harmoniaSmbAuthorityJson
            }
        }
        AimiCascadeArbitrationArtifacts.publish(
            physiologicalPatterns = patternsJson,
            harmoniaSmbAuthority = harmoniaSmbAuthorityJson,
        )
        decisionCtx.adjustments.recursive_authority_gate = work.lastRecursiveAuthorityGateDecision?.toJsonObject()
        decisionCtx.adjustments.physio_latent_state = causalState.lastPhysioLatentState?.toJsonObject()
        decisionCtx.adjustments.uam_hypotheses = causalState.lastUamHypothesisState?.toJsonObject()
        decisionCtx.adjustments.patient_state = work.lastPatientState?.toJsonObject()
        decisionCtx.adjustments.patient_mode = work.lastPatientModeDecision?.toJsonObject()
        decisionCtx.adjustments.endocrine_belief = causalState.lastWCycleBelief?.toJsonObject()
        // Refresh cycle phase after WCycle resolve (early physio_context often ran with null wCycle).
        val existingPhysio = decisionCtx.adjustments.physiological_context
        decisionCtx.adjustments.physiological_context = AimiDecisionContext.PhysioContext(
            hormonal_cycle_phase = causalState.lastWCycleBelief?.takeIf { it.enabled }?.let {
                "${it.phase.name}_Day${it.dayInCycle}"
            } ?: work.wCycleInfoForRun?.let { "${it.phase.name}_Day${it.dayInCycle}" } ?: "Unknown",
            physical_activity_mode = existingPhysio?.physical_activity_mode ?: "Resting",
        )

        if (preferences.get(BooleanKey.OApsAIMIIntelligenceSnapshotExport)) {
            lastIntelligenceSnapshot = buildIntelligenceSnapshot(ctx, profile, pkpdRuntime)
            decisionCtx.adjustments.intelligence_snapshot =
                lastIntelligenceSnapshot?.let { snap ->
                    // Rebuilt rather than mutated: a kotlinx JsonObject is immutable, where the
                    // org.json one this replaced was not. The behaviour is the same - when there is no
                    // apply result, or no "predictions" member, the snapshot is returned untouched,
                    // exactly as the old `?.apply` was a no-op in those cases.
                    val base = IntelligenceSnapshotJson.toJsonObject(snap)
                    val ar = work.lastPredictionAuthorityApplyResult
                    val predictions = base["predictions"] as? JsonObject
                    if (ar == null || predictions == null) base
                    else {
                        val enriched = JsonObj().apply {
                            predictions.forEach { (key, value) -> put(key, value) }
                            put("authority_applied", ar.applied)
                            put("shadow_only", ar.shadowOnly)
                            ar.shadowDeltaEventualMgdl?.let { put("shadow_delta_eventual", it) }
                            ar.shadowDeltaPredTerminalMgdl?.let { put("shadow_delta_pred_terminal", it) }
                        }.build()
                        JsonObj().apply {
                            base.forEach { (key, value) ->
                                put(key, if (key == "predictions") enriched else value)
                            }
                        }.build()
                    }
                }
        }

        // Rebuilt rather than mutated: a kotlinx JsonObject is immutable where the org.json one was
        // not. Same result - the snapshot's own members first, then the two additions.
        decisionCtx.adjustments.physiological_tree = work.lastPhysiologicalTreeSnapshot?.toJsonObject()?.let { tree ->
            JsonObj().apply {
                tree.forEach { (key, value) -> put(key, value) }
                // Keep tree authority label honest: Harmonia arbitrates basal + SMB (not "none"/context-only).
                put("insulin_authority", "harmonia_basal_and_smb_arbitration")
                lastIntelligenceSnapshot?.let { snap ->
                    put("insulin_kinetics_context", JsonObj().apply {
                        put("effective_dia_h", snap.kinetics.effective.diaHours)
                        put("effective_peak_min", snap.kinetics.effective.peakMinutes)
                        put("structural_dia_h", snap.kinetics.structural.diaHrs)
                        put("structural_peak_min", snap.kinetics.structural.peakMin)
                        put("learning_gate_pass", snap.kinetics.learning.learningGatePass)
                        put("causal_modulation", snap.causal.causalModulationReason)
                    })
                }
            }.build()
        }
        decisionCtx.adjustments.meal_certainty = work.lastMealCertainty?.toJsonObject()
        decisionCtx.adjustments.dose_terminal_snapshot = lastDoseTerminalSnapshot?.toJsonObject()
        decisionCtx.adjustments.tube_advisor = lastTubeAdvisorTrace
        decisionCtx.adjustments.pkpd_soft_floor = lastPkpdSoftFloorTelemetry?.toJsonObject()
        decisionCtx.adjustments.basal_terminal = lastBasalTerminalTelemetry
        decisionCtx.adjustments.adaptive_basal = lastAdaptiveBasalTrace
        decisionCtx.adjustments.harmonia_simulation = work.lastHarmoniaDecision?.toJsonObject()
        decisionCtx.adjustments.harmonia_production = work.lastHarmoniaProductionDecision?.toJsonObject()
        decisionCtx.adjustments.t3c_runtime_ownership = work.lastT3cRuntimeOwnership
        decisionCtx.adjustments.replay_quality = ReplayQualityExportBuilder.toJsonObject(
            ReplayQualityExportBuilder.build(
                phaseOutput = work.lastPhysiologicalPhaseOutput,
                mealAbsorptionOutput = work.lastMealAbsorptionOutput,
                hypothesisState = causalState.lastUamHypothesisState,
                patientState = work.lastPatientState,
                patientModeDecision = work.lastPatientModeDecision,
                patternSnapshot = work.lastPhysiologicalPatternSnapshot,
                iobSurveillanceExport = lastIobSurveillanceExport,
                safetyRiskExport = lastSafetyRiskExport,
                recursiveBeliefSnapshot = work.lastRecursiveBeliefSnapshot,
                authorityGateDecision = work.lastRecursiveAuthorityGateDecision,
                correctionAggressionDecision = work.correctionAggressionDecision,
                predictionAvailable = work.lastPredictionAvailable,
                smbProposedU = work.lastSmbProposed,
                smbCappedU = work.lastSmbCapped,
                smbFinalU = work.lastSmbFinal,
                decisionSource = work.lastDecisionSource,
                safetySource = work.lastSafetySource,
                rbtPreferences = rbtPrefs,
            ),
        )
        val bindingFinalU = terminalSmbAmountU
        // Written here, after the RT is final, because this is the one point every export path goes
        // through — the same reason `markEstimatorDiagnosticsForExport` writes its fields late. The
        // three draft copy sites do not all run on every tick, so setting the ladder fields there
        // would leave them null on the paths that skip that site.
        val bindingExportDraft = work.lastSmbBindingTraceDraft.copy(
            maxSmbLadderBranch = lastMaxSmbLadderBranch,
            slopeFromMinDeviation = work.lastSlopeFromMinDeviation,
            shortAvgDeltaMgdl5m = lastShortAvgDeltaAtLadder,
        ).appendStage(
            "FINAL",
            bindingFinalU,
            bindingFinalU,
            phase = "EXPORT",
            kind = "OBSERVATION",
        )
        val bindingTrace = bindingExportDraft.build(bindingFinalU)
        decisionCtx.adjustments.smb_binding_trace = bindingTrace.toJsonObject()

        // Observation only — stamps the origin of this tick's dose on the training row queued
        // earlier in the same tick. Read here, not at CSV time: the row is built inside the SMB
        // executor, before the owner fallback and the late caps have run, so reading it there would
        // report "NONE" on ticks that do have an owner.
        runCatching {
            smbTrainingRowBuffer.stampOrigin(
                tickKey = smbTrainingRowTickKey,
                smbModelU = bindingTrace.modelOutputU,
                smbFloorU = bindingTrace.autodriveFloorU,
                bindingStage = bindingTrace.bindingStage,
                originOwner = bindingTrace.originOwner,
                smbMpcRequestedU = bindingTrace.mpcRequestedU,
            )
        }

        // Observation only — running share of the delivered insulin the model really asked for.
        // Placed here on purpose, **after** `bindingExportDraft` is complete: read any earlier and
        // the model output and the floor of this tick are not both known yet, so the split would be
        // wrong on exactly the ticks it is meant to describe. See `InsulinOriginMeter`.
        runCatching {
            insulinOriginMeter.observe(
                InsulinOriginMeter.Sample(
                    timestampMs = decisionCtx.timestamp,
                    finalU = bindingFinalU,
                    modelOutputU = bindingExportDraft.modelOutputU,
                    mpcOutputU = bindingExportDraft.mpcOutputU,
                    autodriveFloorU = bindingExportDraft.autodriveFloorU,
                    bindingStage = bindingExportDraft.stages.lastOrNull()?.name,
                    originOwner = bindingExportDraft.originOwner,
                ),
            )
            decisionCtx.adjustments.insulin_origin =
                insulinOriginMeter.read(decisionCtx.timestamp).toJsonObject()
        }

        lastAuditorLoopSnapshot?.let { snapshot ->
            decisionCtx.adjustments.auditor_tick = snapshot.toJsonObject()
        }
        // Written on every tick, key on or off: the levels the auditor works from, so a support
        // package shows what it was given before any factor is ever applied.
        decisionCtx.adjustments.auditor_profile_factors = auditorProfileTick.toJsonObject()
        // Feeds the 30-minute context the next audit is judged against. Every exit path runs through
        // this stage (main path, T3C, early exits), so the ring never misses a tick. Recorded last,
        // after every auditor field of this tick is final, so a later audit sees exactly what this
        // tick's own export saw.
        auditorTickRing.record(buildAuditorTickFact(ctx, profile))
        decisionCtx.adjustments.post_hypo_delivery = PostHypoDeliveryAuthority.toJsonObject(
            decision = work.lastPostHypoDeliveryAuthority,
            smbBeforeCapU = work.lastPostHypoSmbBeforeCapU,
            smbAfterCapU = work.lastPostHypoSmbAfterCapU,
        )

        // Les compteurs et l'ombre Ra doivent être posés ICI, pas en fin de tick.
        //
        // Mesuré sur le paquet du 09/08 : écrits après `runDetermineBasalTick`, ils n'atteignaient
        // que 7 lignes sur 93 — celles qui tombent dans le filet de rattrapage. Toutes les autres
        // étaient déjà sérialisées. Et les 7 survivantes étaient toutes des `Basal_Modulation`, donc
        // l'échantillon était en plus biaisé vers les ticks calmes.
        //
        // Ce point-ci est le seul par lequel tous les chemins d'export passent.
        markEstimatorDiagnosticsForExport(decisionCtx)
        val medicalJson = decisionCtx.toMedicalJson()
        // NB: do NOT push medicalJson into consoleLog — consoleLog is serialized into the NS deviceStatus
        // (suggested + enacted, twice per document); this multi-hundred-KB blob makes the deviceStatus
        // multi-MB and OOMs any client re-parsing it on receive (DeviceStatusMapper.toString). The full
        // snapshot is already persisted locally on the next line (AIMI_Decisions.jsonl), so the NS copy
        // was pure redundancy. Keep it out of consoleLog.
        appendAimiDecisionsJsonlLine(medicalJson)
        // 🔭 Lot 0 — marque le tick comme exporté pour que l'enveloppe [runDetermineBasalTick] ne double
        // pas la ligne sur les deux chemins qui appellent déjà ce stage.
        work.aimiDecisionExportedThisTick = true

        AimiLoopTelemetry.enterPhase(AimiLoopPhase.EXPORT, hormonitorStudyExporter)
        try {
            val fallbackTrace = PhysioDecisionTraceMTR(
                timestamp = dateUtil.now(),
                finalLoopDecisionType = inferFinalLoopDecisionFromResult(finalResult),
                source = "fallback_no_physio_trace"
            )
            val latestSnapshot = physioAdapter.getLatestSnapshot()
            val wCycleInfo = work.wCycleInfoForRun
            val traceForExport = physioAdapter.getLastDecisionTrace() ?: fallbackTrace
            val safetyExport = lastSafetyRiskExport
            val patientRuntimeSnapshot = resolvePatientRuntimeSnapshotForExport(decisionCtx.timestamp)
            val patientPresentation = patientRuntimeSnapshot?.let { runtimeSnapshot ->
                PatientStatePresentationBuilder.build(
                    snapshot = runtimeSnapshot,
                    nowMs = decisionCtx.timestamp,
                )
            }
            val event = HormonitorDecisionEventMTR(
                eventId = decisionCtx.event_id,
                eventTimestamp = decisionCtx.timestamp,
                trigger = decisionCtx.trigger,
                profileIsfMgdl = decisionCtx.baseline_state.profile_isf_mgdl,
                profileBasalUph = decisionCtx.baseline_state.profile_basal_uph,
                currentBgMgdl = decisionCtx.baseline_state.current_bg_mgdl,
                cobG = decisionCtx.baseline_state.cob_g,
                iobU = decisionCtx.baseline_state.iob_u,
                cyclePhase = wCycleInfo?.phase?.name,
                cycleDay = wCycleInfo?.dayInCycle,
                cycleTrackingMode = wCyclePreferences.trackingMode().name,
                contraceptiveType = wCyclePreferences.contraceptive().name,
                wcycleBasalMult = wCycleInfo?.basalMultiplier,
                wcycleSmbMult = wCycleInfo?.smbMultiplier,
                wcycleIsfMult = if (variableSensitivity != 0.0f) (1.0 / variableSensitivity.toDouble()) else null,
                thyroidStatus = work.currentThyroidEffects.status.name,
                inflammationStatus = wCyclePreferences.verneuil().name,
                hrNowBpm = latestSnapshot.hrNow,
                hrAvg15mBpm = latestSnapshot.hrAvg15m,
                rhrRestingBpm = latestSnapshot.rhrResting,
                hrvRmssdMs = latestSnapshot.hrvRmssd,
                steps5m = latestSnapshot.stepsLast5m,
                steps15m = latestSnapshot.stepsLast15m,
                steps60m = latestSnapshot.stepsLast60m,
                activityState = latestSnapshot.activityState,
                sleepDebtMinutes = latestSnapshot.sleepDebtMinutes,
                sleepEfficiency = latestSnapshot.sleepEfficiency,
                physioSnapshotTimestamp = latestSnapshot.timestamp,
                physioSnapshotValidFlag = latestSnapshot.isValid,
                physioTrace = if (traceForExport.finalLoopDecisionType.isNullOrBlank()) {
                    traceForExport.copy(finalLoopDecisionType = inferFinalLoopDecisionFromResult(finalResult))
                } else {
                    traceForExport
                },
                safetyPhase = safetyExport?.phase?.name,
                predictiveHypoSuppressed = safetyExport?.predictiveHypoSuppressed,
                safetyGate = safetyExport?.safetyGate,
                safetyCompositeMinMgdl = safetyExport?.compositeMinMgdl,
                safetyUamTerminalMgdl = safetyExport?.uamTerminalMgdl,
                decisionCompositeMinMgdl = safetyExport?.decisionCompositeMinMgdl,
                safetyReconcileDeltaMgdl = safetyExport?.reconcileDeltaMgdl,
                patientMode = patientRuntimeSnapshot?.patientModeDecision?.mode?.name,
                patientModeConfidence = patientRuntimeSnapshot?.patientModeDecision?.confidence,
                patientStrategyHint = patientRuntimeSnapshot?.patientModeDecision?.strategyHint?.name,
                patientNarrative = patientPresentation?.narrative,
                patientReasonCodes = patientRuntimeSnapshot?.patientModeDecision?.reasonCodes,
                patientPhysioLive = patientRuntimeSnapshot?.physioLive
                    ?: PhysioLiveDigest.from(latestSnapshot, decisionCtx.timestamp),
                patientThermalBelief = patientRuntimeSnapshot?.thermalBelief
                    ?: enrichThermalSnapshot(latestSnapshot).thermalBelief,
            )
            hormonitorStudyExporter?.export(event)
            hormonitorStudyExporter?.exportShadowContributions(event)
            hormonitorStudyExporter?.exportDailyOutcomes(
                event = event,
                tirLowPct = currentTIRLow,
                tirInRangePct = currentTIRRange,
                tirAbovePct = currentTIRAbove,
                tdd24hTotalU = resolveTdd24hForExport(),
                snapshotSource = latestSnapshot.source,
                snapshotAgeSeconds = ((dateUtil.now() - latestSnapshot.timestamp) / 1000L).coerceAtLeast(0L),
                snapshotConfidence = latestSnapshot.confidence
            )
        } catch (e: Exception) {
            work.consoleError.add("Failed to save HORMONITOR event JSON: ${e.message}")
        }

        // Very last thing the stage does, and it must stay last: the export above has already read
        // `harmoniaPrevRuntimeBlocker`, so updating here is what makes "previous" mean the previous
        // tick. Move this up and every line would export its own blocker while claiming it is the
        // one before. Observation only.
        val nowBlocker = work.lastHarmoniaProductionDecision?.runtimeBlocker
        harmoniaBlockedStreak = when {
            nowBlocker != null && nowBlocker == harmoniaPrevRuntimeBlocker -> harmoniaBlockedStreak + 1
            nowBlocker != null                                            -> 1
            else                                                          -> 0
        }
        harmoniaPrevRuntimeBlocker = nowBlocker
    }

    private fun aimiDecisionsJsonlFile(): AimiPath = storage.file("AIMI_Decisions.jsonl")

    private fun appendAimiDecisionsJsonlLine(jsonLine: String) {
        try {
            AuditorJsonlExport.appendLine(storage, appendCap, aimiDecisionsJsonlFile(), jsonLine)
        } catch (e: Exception) {
            work.consoleError.add("Failed to save AIMI Decision JSON: ${e.message}")
        }
    }

    private fun recordAuditorSyncDisposition(
        disposition: AuditorJsonlExport.TickDisposition,
        loopSmbU: Double,
        loopTbrUph: Double?,
        loopIntervalMin: Int,
    ) {
        lastAuditorTickDisposition = disposition
        lastAuditorLoopSnapshot = AuditorJsonlExport.TickSnapshot(
            disposition = disposition,
            recordedAtMs = dateUtil.now(),
            loopSmbU = loopSmbU,
            loopTbrUph = loopTbrUph,
            loopIntervalMin = loopIntervalMin,
            sentinelAgreement = auditorOrchestrator.lastSentinelAdvice?.agreement,
            sentinelSmbFactor = auditorOrchestrator.lastSentinelAdvice?.smbFactor,
            sentinelReason = auditorOrchestrator.lastSentinelAdvice?.reason,
        )
    }

    /**
     * Async external auditor only: append advisory follow-up without blocking the loop tick.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun maybeAppendAuditorFollowupLine(
        verdict: AuditorVerdict?,
        result: DecisionResult,
    ) {
        if (lastAuditorTickDisposition != AuditorJsonlExport.TickDisposition.EXTERNAL_PENDING) return
        val parentEventId = work.currentTickDecisionEventId ?: return
        if (!auditorFollowupAppendInProgress.compareAndSet(false, true)) return
        try {
            val followup = AuditorJsonlExport.followupToJsonObject(
                parentEventId = parentEventId,
                recordedAtMs = dateUtil.now(),
                auditStartedAtMs = lastAuditorAuditStartedAtMs,
                verdict = verdict,
                result = result,
            )
            appendAimiDecisionsJsonlLine(followup.toString())
        } finally {
            auditorFollowupAppendInProgress.store(false)
        }
    }

    /**
     * Fenêtre repas **horloge thérapie** — mêmes drapeaux que le premier critère de
     * [runPostSafetyMealFirst30NgrHeadroomBasalSmbStage], valables **après**
     * [runTherapyHydrateClocksAndExerciseLockoutGate] (donc avant pont trajectory / cap spiral).
     * Ne duplique pas [nightGrowthResistanceMode.evaluate] (machine d’état singleton).
     */
    private fun therapyMealWindowActiveForSpiralAlign(): Boolean =
        work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime

    /**
     * Miroir strict de [finalizeAndCapSMB] `mealPriorityContext` : absorption / repas **sans** se limiter au
     * delta instantané (shortAvg pour montées lissées) + tête IOB sous 75 % du max — aligné export JSONL
     * `meal_absorption_rise_priority` / [InsulinStackingStance].
     *
     * @param mealClockActiveForSpiralRelax Fenêtre repas **horloge thérapie** (meal/bfast/lunch/dinner/highcarb),
     * identique au premier critère de [runPostSafetyMealFirst30NgrHeadroomBasalSmbStage] — disponible après
     * [runTherapyHydrateClocksAndExerciseLockoutGate]. L’évaluation d’état NGR (monitor) reste **unique** dans ce stage
     * pour ne pas corrompre la machine d’état.
     */
    private fun isMealPriorityAlignedForSpiralSmbCap(
        bgValue: Double,
        deltaValue: Float,
        shortAvgDeltaValue: Float,
        mealData: MealData,
        iobNow: Double,
        maxIobValue: Double,
        isExplicitUserAction: Boolean,
        mealClockActiveForSpiralRelax: Boolean,
    ): Boolean = mealPriorityAlignedForSpiralSmbCap(
        bgValue = bgValue,
        deltaValue = deltaValue,
        shortAvgDeltaValue = shortAvgDeltaValue,
        mealData = mealData,
        iobNow = iobNow,
        maxIobValue = maxIobValue,
        isExplicitUserAction = isExplicitUserAction,
        mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
        uamConfidence = AimiUamHandler.confidenceOrZero(),
    )

    private fun sharpRiseEligibleForTrajectorySpiralSoftCap(deltaValue: Float, shortAvgDeltaValue: Float): Boolean =
        AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(deltaValue, shortAvgDeltaValue)

    /**
     * When spiral energy and IOB exceed **TDD‑scaled** safety thresholds (see file-level
     * [tightSpiralSmbCapEnergyThresholdU] / [tightSpiralSmbCapIobThresholdU]), clamp [maxSMB] and [maxSMBHB] toward the user’s
     * standard SMB cap ([DoubleKey.OApsAIMIMaxSMB]) so high-BG SMB headroom cannot stack insulin during
     * a tight spiral. If [capRelaxContext] is true (**meal priority align**), the cap is **not** applied.
     * On **sharp rise** without relax, only [maxSMBHB] is partially relaxed (50 % of span toward high cap).
     * Invoked from [runTrajectoryTightSpiralSafetyBridge] and again after
     * [applyIsfBoundsAndPhysioMultipliersAfterEndoActivity] so physio SMB scaling cannot re-expand past it.
     */
    private fun applyTrajectoryTightSpiralStandardSmbCapIfNeeded(
        energy: Double,
        iobNow: Double,
        tdd24hU: Double,
        deltaValue: Float,
        shortAvgDeltaValue: Float,
        mealData: MealData,
        isExplicitUserAction: Boolean,
        mealClockActiveForSpiralRelax: Boolean,
    ) {
        val weightKg = preferences.get(DoubleKey.OApsAIMIweight)
        val energyTh = tightSpiralSmbCapEnergyThresholdU(tdd24hU)
        val iobTh = tightSpiralSmbCapIobThresholdU(tdd24hU, weightKg)
        val wouldCap = energy > energyTh && iobNow > iobTh
        val capRelaxContext = isMealPriorityAlignedForSpiralSmbCap(
            bgValue = bg,
            deltaValue = deltaValue,
            shortAvgDeltaValue = shortAvgDeltaValue,
            mealData = mealData,
            iobNow = iobNow,
            maxIobValue = work.maxIob.toDouble(),
            isExplicitUserAction = isExplicitUserAction,
            mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
        )
        if (capRelaxContext) {
            if (wouldCap) {
                work.consoleLog.add(
                    "🌀🛡️ TRAJ_SPIRAL SMB-cap: skipped (MEAL_PRIORITY_ALIGN) E=${aimiFmt1(energy)}U " +
                        "(>${aimiFmt1(energyTh)}) IOB=${aimiFmt1(iobNow)}U " +
                        "(>${aimiFmt1(iobTh)}) Δ=${aimiFmt1(deltaValue.toDouble())} " +
                        "sΔ=${aimiFmt1(shortAvgDeltaValue.toDouble())} TDD24h=${aimiFmt1(tdd24hU)}U"
                )
            }
            return
        }
        if (!wouldCap) return
        val stdMaxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        if (!stdMaxSMB.isFinite() || stdMaxSMB <= 0.0) return
        val prevMb = work.maxSMB
        val prevHb = work.maxSMBHB
        work.maxSMB = minOf(work.maxSMB, stdMaxSMB)
        val sharp = sharpRiseEligibleForTrajectorySpiralSoftCap(deltaValue, shortAvgDeltaValue)
        work.maxSMBHB = if (sharp) {
            val span = (prevHb - stdMaxSMB).coerceAtLeast(0.0)
            minOf(prevHb, stdMaxSMB + span * 0.5)
        } else {
            minOf(work.maxSMBHB, stdMaxSMB)
        }
        if (work.maxSMB == prevMb && work.maxSMBHB == prevHb) return
        val modeNote = if (sharp) " [SOFT_HB]" else ""
        work.consoleLog.add(
            "🌀🛡️ TRAJ_SPIRAL SMB-cap$modeNote: E=${aimiFmt1(energy)}U (>${aimiFmt1(energyTh)}) " +
                "IOB=${aimiFmt1(iobNow)}U (>${aimiFmt1(iobTh)}) " +
                "TDD24h=${aimiFmt1(tdd24hU)}U w=${aimiFmt0(weightKg)}kg " +
                "work.maxSMB ${aimiFmt2(prevMb)}→${aimiFmt2(work.maxSMB)}U " +
                "work.maxSMBHB ${aimiFmt2(prevHb)}→${aimiFmt2(work.maxSMBHB)}U " +
                "(≤OApsAIMIMaxSMB ${aimiFmt2(stdMaxSMB)}U)"
        )
    }

    /**
     * Pont **TIGHT_SPIRAL** ([trajectoryGuard]) : réduction proactive de basale sur [rT] selon énergie / CGate,
     * plus plafond SMB standard si énergie / IOB dépassent les seuils (voir [applyTrajectoryTightSpiralStandardSmbCapIfNeeded]).
     * [physioMultipliers] = même instance que le tick (T9 bootstrap), pas un membre de classe.
     * **Ne retourne pas** — les garde-fous LGS aval peuvent encore abaisser le TBR. Appelé après [applyTrajectoryAnalysis], avant [applyContextModule].
     */
    private fun runTrajectoryTightSpiralSafetyBridge(
        profile: OapsProfileAimi,
        rT: RT,
        iobData: IobTotal,
        bg: Double,
        delta: Float,
        cob: Float,
        physioMultipliers: PhysioMultipliersMTR,
        tdd24Hrs: Float,
        mealData: MealData,
        isExplicitUserAction: Boolean,
        mealClockActiveForSpiralRelax: Boolean,
    ) {
        decideTrajectoryTightSpiralSafetyBridge(
            profile = profile,
            rT = rT,
            iobNow = iobData.iob,
            bg = bg,
            delta = delta,
            physioMultipliers = physioMultipliers,
            tdd24Hrs = tdd24Hrs,
            mealData = mealData,
            isExplicitUserAction = isExplicitUserAction,
            mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
            consoleLog = work.consoleLog,
            calls = object : AimiTrajectorySpiralCalls {
                override fun lastAnalysis() = trajectoryGuard.getLastAnalysis()
                override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta
                override fun maxIob() = work.maxIob.toDouble()
                override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
                override fun hyperTier(rT: RT, combinedDelta: Float, tdd24hU: Double) =
                    classifyHyperSeverityForTick(rT, combinedDelta, tdd24hU).tier
                override fun setPending(rateUph: Double, durationMin: Int, reason: String, tierLabel: String) {
                    work.pendingTrajSpiralBasal = PendingTrajSpiralBasal(
                        proactiveBasalUph = rateUph,
                        durationMin = durationMin,
                        reason = reason,
                        safetyTierLabel = tierLabel,
                    )
                }
                override fun applySmbCap(
                    energy: Double,
                    iobNow: Double,
                    tdd24hU: Double,
                    deltaValue: Float,
                    shortAvgDeltaValue: Float,
                    mealData: MealData,
                    isExplicitUserAction: Boolean,
                    mealClockActiveForSpiralRelax: Boolean,
                ) = applyTrajectoryTightSpiralStandardSmbCapIfNeeded(
                    energy, iobNow, tdd24hU, deltaValue, shortAvgDeltaValue, mealData,
                    isExplicitUserAction, mealClockActiveForSpiralRelax,
                )
            },
        )
    }

    /**
     * Après [applyContextModule] : dérivés TDD horaires sur les membres ([tdd7DaysPerHour], [tdd2DaysPerHour],
     * [tddPerHour], [tdd24HrsPerHour]) puis fusion ISF (`pkpdRuntime.fusedIsf` vs variable/profil), assignation
     * [variableSensitivity], journal `consoleError` et [IsfTddProvider.set] si fusion active.
     *
     * **Comportement** : identique à l’ancien bloc inline — même formules, même ordre d’effets (y compris effet
     * global `IsfTddProvider`, pas async).
     *
     * @return `sens` (Double) pour [applyAdvancedPredictions] et le reste du tick ; peut être réassigné plus bas
     *         (ex. [applyIsfBoundsAndPhysioMultipliersAfterEndoActivity]).
     *
     * The stress ISF floor is applied here, on the assembled value, and **again** at the end of
     * [applyIsfBoundsAndPhysioMultipliersAfterEndoActivity]. Both are needed and neither is enough:
     * - here, because the AutodriveV3 stage runs between the two and falls back to
     *   [variableSensitivity] when the PKPD runtime is missing or failed to build;
     * - there, because `HeartRateTrendIsf` multiplies the same member by 0.9 after this point, on
     *   nearly the same signature, and the protective gesture has to win.
     *
     * The floor is a `max` against a number that does not change inside a tick, so applying it twice
     * gives exactly what applying it once gives. See `WorkingIsf.raiseToStressFloor`.
     */
    private fun runTddRatesAndIsfFusionAfterContext(
        profile: OapsProfileAimi,
        tdd7Days: Double,
        tdd7P: Double,
        tdd24Hrs: Float,
        pkpdRuntime: PkPdRuntime?,
    ): Double {
        tdd7DaysPerHour = (tdd7Days / 24).toFloat()
        val tdd2Days = tdd2DaysCached(tdd7P)
        tdd2DaysPerHour = tdd2Days / 24
        var tddDaily = tddCalculator.averageTDD(
            resolveTdd1DaySparseForAverage()
        )?.data?.totalAmount?.toFloat() ?: 0.0f
        if (tddDaily == 0.0f || tddDaily < tdd7P / 2) tddDaily = tdd7P.toFloat()
        tddPerHour = tddDaily / 24
        tdd24HrsPerHour = tdd24Hrs / 24

        val fusedSensitivity = pkpdRuntime?.fusedIsf
        val dynSensitivity = profile.variable_sens.takeIf { it > 0.0 } ?: profile.sens
        val baseSensitivity = fusedSensitivity ?: profile.sens

        var sens = when {
            fusedSensitivity == null -> dynSensitivity
            dynSensitivity <= 0.0 -> fusedSensitivity
            else -> min(fusedSensitivity, dynSensitivity)
        }
        if (sens <= 0.0) sens = baseSensitivity
        // First of the two applications of the stress floor — see this function's KDoc. Idempotent,
        // and inert when the key is off (`stress_floor_isf_mgdl` is null then).
        sens = WorkingIsf.raiseToStressFloor(sens, profile.stress_floor_isf_mgdl)
        variableSensitivity = sens.toFloat()

        if (fusedSensitivity != null) {
            work.consoleError.add("ISF fusionné=${aimiFmt1(fusedSensitivity)} dynISF=${aimiFmt1(dynSensitivity)} → appliqué=${aimiFmt1(sens)}")
            try {
                IsfTddProvider.set(fusedSensitivity)
            } catch (e: Exception) {
                work.consoleError.add("Impossible de mettre à jour IsfTddProvider: ${e.message}")
            }
        }
        return sens
    }

    /**
     * `modesCondition` … PKPD runtime + [applyBasalFirstPolicy] : même ordre que l’historique inline dans [determine_basal].
     * Mutations : [lastBolusAgeMinutes], [pkpdIntegration], logs, [rT] via basal-first ; lecture BG/IOB/COB sur l’instance.
     */
    private fun runSignalPreparationPkpdRuntimePhase(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        glucoseStatus: GlucoseStatusAIMI,
        combinedDelta: Float,
        tdd7P: Double,
        isExplicitAdvisorRun: Boolean,
        isConfirmedHighRiseLocal: Boolean,
        pkpdRuntimeIn: PkPdRuntime?,
    ): AimiSignalPreparationPkpdOutcome {
        val decided = decideSignalPreparationPkpdRuntime(
            ctx = ctx,
            profile = profile,
            rT = rT,
            glucoseStatus = glucoseStatus,
            combinedDelta = combinedDelta,
            tdd7P = tdd7P,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
            pkpdRuntimeIn = pkpdRuntimeIn,
            pkpdIntegration = pkpdIntegration,
            preferences = preferences,
            consoleLog = work.consoleLog,
            calls = object : AimiSignalPrepPkpdCalls {
                override fun mealTime() = work.mealTime
                override fun mealRuntime() = work.mealruntime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun lunchRuntime() = work.lunchruntime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun bfastRuntime() = work.bfastruntime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun dinnerRuntime() = work.dinnerruntime
                override fun sportTime() = work.sportTime
                override fun snackTime() = work.snackTime
                override fun snackRuntime() = work.snackrunTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun highCarbRuntime() = work.highCarbrunTime
                override fun sleepTime() = work.sleepTime
                override fun lowCarbTime() = work.lowCarbTime
                override fun recentBgs() = getRecentBGs()
                override fun nowMs() = now
                override fun bolusesSince(startMs: Long, ascending: Boolean) = getBolusesFromTimeCached(startMs, ascending)
                override fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder) {
                    this@DetermineBasalaimiSMB2.calculateBgTrend(recentBGs, reason)
                }
                override fun studyExporter() = hormonitorStudyExporter
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun predictedBg() = this@DetermineBasalaimiSMB2.predictedBg
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta
                override fun longAvgDelta() = this@DetermineBasalaimiSMB2.longAvgDelta
                override fun iob() = work.iob
                override fun cob() = work.cob
                override fun maxSmb() = work.maxSMB
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
                override fun lateFatProteinRise(
                    bg: Double,
                    predictedBg: Double,
                    delta: Double,
                    shortAvgDelta: Double,
                    longAvgDelta: Double,
                    iob: Double,
                    cob: Double,
                    maxSmb: Double,
                    lastBolusTimeMs: Long?,
                    mealTime: Boolean,
                    bfastTime: Boolean,
                    lunchTime: Boolean,
                    dinnerTime: Boolean,
                    highCarbTime: Boolean,
                ) = isLateFatProteinRise(
                    bg = bg,
                    predictedBg = predictedBg,
                    delta = delta,
                    shortAvgDelta = shortAvgDelta,
                    longAvgDelta = longAvgDelta,
                    iob = work.iob,
                    cob = work.cob,
                    maxSMB = maxSmb,
                    lastBolusTimeMs = lastBolusTimeMs,
                    mealFlags = MealFlags(work.mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime),
                )
                override fun setLateFatRiseFlag(value: Boolean) {
                    lateFatRiseFlagForExport = value
                }
                override fun tdd24hState() = determineBasalInvocationCaches.getTdd24hTotalAmountState(tddCalculator)
                override fun noteStaleData(minAgo: Double) {
                    work.consoleError.add("Data Stale (${minAgo}m) -> Logic Paused")
                }
                override fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float) {
                    this@DetermineBasalaimiSMB2.logDecisionFinal(tag, rT, bg, delta)
                }
                override fun ensurePredictionFallback(rT: RT, bg: Double) {
                    this@DetermineBasalaimiSMB2.ensurePredictionFallback(rT, bg)
                }
                override fun markFinalLoopDecision(rT: RT) {
                    markFinalLoopDecisionFromRT(rT)
                }
                override fun internalLastSmbMillis() = this@DetermineBasalaimiSMB2.internalLastSmbMillis
                override fun setLastBolusAgeMinutes(minutes: Double) {
                    work.lastBolusAgeMinutes = minutes
                }
                override fun pkpdMealContext(mealData: MealData, predictedBgMgdl: Double, targetBgMgdl: Double) =
                    buildPkpdMealContext(mealData, predictedBgMgdl, targetBgMgdl)
                override fun recentPkpdBolusSamples(nowMillis: Long, fallbackWindowMin: Int) =
                    buildRecentPkpdBolusSamples(nowMillis, fallbackWindowMin)
                override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
                override fun physioLatentState() = causalState.lastPhysioLatentState
                override fun lastRa() = continuousStateEstimator.getLastRa()
                override fun causalPosterior() = work.lastPatientState?.causalPosterior
                override fun eventMemory() = work.lastPatientState?.eventMemory
                override fun logPkpdRuntimeFailure(error: Exception) {
                    work.consoleError.add("❌ PKPD runtime failed: ${error.message}")
                    aapsLogger.error(LTag.APS, "PKPD computeRuntime failed", error)
                    work.consoleLog.add(
                        "PKPD runtime failed (${error::class.simpleName}): ${error.message.orEmpty()} — value null",
                    )
                }
                override fun setCachedPkpdRuntime(runtime: PkPdRuntime) {
                    cachedPkpdRuntime = runtime
                }
                override fun applyBasalFirst(
                    bg: Double,
                    delta: Float,
                    combinedDelta: Float,
                    mealData: MealData,
                    autosens: AutosensResult,
                    isMealAdvisorOneShot: Boolean,
                    targetBg: Double,
                    rT: RT,
                    isConfirmedHighRise: Boolean,
                ) {
                    applyBasalFirstPolicy(
                        bg = bg,
                        delta = delta,
                        combinedDelta = combinedDelta,
                        mealData = mealData,
                        autosens_data = autosens,
                        isMealAdvisorOneShot = isMealAdvisorOneShot,
                        targetBg = targetBg,
                        rT = rT,
                        isConfirmedHighRise = isConfirmedHighRise,
                    )
                }
            },
        )
        return when (decided) {
            is AimiSignalPrepPkpd.StaleAbort -> AimiSignalPreparationPkpdOutcome.StaleAbort(decided.rT)
            is AimiSignalPrepPkpd.Continue -> AimiSignalPreparationPkpdOutcome.Continue(
                AimiSignalPreparationPkpdContinue(
                    modesCondition = decided.data.modesCondition,
                    pbolusAS = decided.data.pbolusAS,
                    pbolusA = decided.data.pbolusA,
                    reason = decided.data.reason,
                    recentBGs = decided.data.recentBGs,
                    totalBolusLastHour = decided.data.totalBolusLastHour,
                    autosensRatio = decided.data.autosensRatio,
                    iob_data = decided.data.iobData,
                    lastBolusTimeMs = decided.data.lastBolusTimeMs,
                    causalState.lateFatRiseFlag = decided.data.causalState.lateFatRiseFlag,
                    tdd24Hrs = decided.data.tdd24Hrs,
                    minAgo = decided.data.minAgo,
                    windowSinceDoseInt = decided.data.windowSinceDoseInt,
                    pkpdRuntime = decided.data.pkpdRuntime,
                )
            )
        }
    }

    /**
     * [applyTrajectoryAnalysis] → [runTrajectoryTightSpiralSafetyBridge] (basal + [applyTrajectoryTightSpiralStandardSmbCapIfNeeded]) → [applyContextModule] →
     * [runTddRatesAndIsfFusionAfterContext] → microbolus dynamiques (prefs prébolus ou [calculateDynamicMicroBolus]).
     * Le plafond SMB spiral est ré-appliqué après [applyIsfBoundsAndPhysioMultipliersAfterEndoActivity] dans le tick principal.
     * Lit les champs d’instance courants (BG, delta, IOB, COB, …) comme l’ancien bloc dans [determine_basal].
     */
    private fun runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        iobData: IobTotal,
        physioMultipliers: PhysioMultipliersMTR,
        insulinActionState: InsulinActionState,
        pkpdRuntime: PkPdRuntime?,
        tdd7Days: Double,
        tdd7P: Double,
        tdd24Hrs: Float,
        pbolusA: Double,
        pbolusAS: Double,
        reason: StringBuilder,
        isExplicitAdvisorRun: Boolean,
    ): AimiTrajectoryContextIsfPrep {
        val out = decideTrajectoryContextModuleTddIsfAndDynamicPbolusPrep(
            ctx = ctx,
            profile = profile,
            rT = rT,
            iobData = iobData,
            physioMultipliers = physioMultipliers,
            insulinActionState = insulinActionState,
            pkpdRuntime = pkpdRuntime,
            tdd7Days = tdd7Days,
            tdd7P = tdd7P,
            tdd24Hrs = tdd24Hrs,
            pbolusA = pbolusA,
            pbolusAS = pbolusAS,
            reason = reason,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            calls = object : AimiTrajectoryContextPrepCalls {
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun bgacc() = work.bgacc
                override fun iobActivityNow() = work.iobActivityNow
                override fun iob() = work.iob
                override fun lastBolusAgeMinutes() = work.lastBolusAgeMinutes
                override fun cob() = work.cob
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
                override fun mealWindow() = therapyMealWindowActiveForSpiralAlign()
                override fun autosensRatio() = ctx.autosensData.ratio
                override fun analyzeTrajectory(
                    currentTime: Long,
                    bg: Double,
                    delta: Double,
                    bgacc: Double,
                    iobActivityNow: Double,
                    iob: Float,
                    insulinActionState: InsulinActionState,
                    lastBolusAgeMinutes: Double,
                    cob: Float,
                    targetBg: Double,
                    profile: OapsProfileAimi,
                    rT: RT,
                    uiInteraction: UiInteraction,
                    relevanceScore: Double,
                ) {
                    this@DetermineBasalaimiSMB2.applyTrajectoryAnalysis(
                        currentTime = currentTime,
                        bg = bg,
                        delta = delta,
                        bgacc = work.bgacc,
                        iobActivityNow = work.iobActivityNow,
                        iob = work.iob,
                        insulinActionState = insulinActionState,
                        lastBolusAgeMinutes = work.lastBolusAgeMinutes,
                        cob = work.cob,
                        targetBg = targetBg,
                        profile = profile,
                        rT = rT,
                        uiInteraction = uiInteraction,
                        relevanceScore = relevanceScore,
                    )
                }
                override fun spiralBridge(
                    profile: OapsProfileAimi,
                    rT: RT,
                    iobData: IobTotal,
                    bg: Double,
                    delta: Float,
                    cob: Float,
                    physioMultipliers: PhysioMultipliersMTR,
                    tdd24Hrs: Float,
                    isExplicitUserAction: Boolean,
                    mealClockActiveForSpiralRelax: Boolean,
                ) {
                    this@DetermineBasalaimiSMB2.runTrajectoryTightSpiralSafetyBridge(
                        profile = profile,
                        rT = rT,
                        iobData = iobData,
                        bg = bg,
                        delta = delta,
                        cob = work.cob,
                        physioMultipliers = physioMultipliers,
                        tdd24Hrs = tdd24Hrs,
                        mealData = ctx.mealData,
                        isExplicitUserAction = isExplicitUserAction,
                        mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
                    )
                }
                override fun applyContext(bg: Double, iob: Double, cob: Double, rT: RT) =
                    this@DetermineBasalaimiSMB2.applyContextModule(bg, iob, cob, rT)
                override fun fuseIsf(
                    profile: OapsProfileAimi,
                    tdd7Days: Double,
                    tdd7P: Double,
                    tdd24Hrs: Float,
                    pkpdRuntime: PkPdRuntime?,
                ) = runTddRatesAndIsfFusionAfterContext(
                    profile = profile,
                    tdd7Days = tdd7Days,
                    tdd7P = tdd7P,
                    tdd24Hrs = tdd24Hrs,
                    pkpdRuntime = pkpdRuntime,
                )
            },
        )
        return AimiTrajectoryContextIsfPrep(
            sens = out.sens,
            baseSensitivity = out.baseSensitivity,
            contextTargetOverride = out.contextTargetOverride,
            dynamicPbolusLarge = out.dynamicPbolusLarge,
            dynamicPbolusSmall = out.dynamicPbolusSmall,
        )
    }

    /**
     * [applyAdvancedPredictions] puis [sanitizePredictionValues], min BG composite, [HypoThresholdMath.computeHypoThreshold],
     * log **PRED_PIPE** (pompe joignable = diagnostic uniquement).
     *
     * **Ordre** : les prédictions mutent [rT] ; le sanity lit `rT.eventualBG` / `rT.predBGs` **après** — ne pas réordonner
     * avant [trySafetyStart] ni avant Autodrive (même [threshold] que l’historique inline).
     */
    private fun runAdvancedPredictionsAndPredPipePrep(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        sens: Double,
        predictedBg: Float,
        glucoseStatus: GlucoseStatusAIMI,
        minAgo: Double,
        isExplicitAdvisorRun: Boolean,
        physioMultipliers: PhysioMultipliersMTR,
        iobData: IobTotal,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        combinedDelta: Float,
    ): AimiAdvancedPredictionsPredPipePrep {
        val prep = decideAdvancedPredictionsAndPredPipePrep(
            ctx = ctx,
            profile = profile,
            rT = rT,
            bg = bg,
            delta = delta,
            sens = sens,
            predictedBg = predictedBg,
            glucoseStatus = glucoseStatus,
            minAgo = minAgo,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            physioMultipliers = physioMultipliers,
            iobData = iobData,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            combinedDelta = combinedDelta,
            preferences = preferences,
            consoleLog = work.consoleLog,
            consoleError = work.consoleError,
            calls = object : AimiPredPipeCalls {
                override fun nowMs() = dateUtil.now()
                override fun setAdvancedCurves(curves: AdvancedPredictionCurves) {
                    work.lastAdvancedPredictionCurves = curves
                }
                override fun recordSoftFloor(curves: AdvancedPredictionCurves) {
                    recordPkpdSoftFloor(curves)
                }
                override fun mealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal) =
                    buildMealSafetyContext(isExplicitAdvisorRun, iobData)
                override fun phaseClassifierInput(
                    rT: RT,
                    combinedDelta: Float,
                    stepsLast15m: Int,
                    heartRateBpm: Int,
                    restingHeartRateBpm: Int,
                    bestTerminalMgdl: Double,
                    floorTerminalMgdl: Double,
                    mealAbsorptionMemoryActive: Boolean,
                ) = buildPhysiologicalPhaseClassifierInput(
                    rT, combinedDelta, stepsLast15m, heartRateBpm, restingHeartRateBpm,
                    bestTerminalMgdl, floorTerminalMgdl, mealAbsorptionMemoryActive,
                )
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
                override fun trajectoryAnalysis() = trajectoryGuard.getLastAnalysis()
                override fun activityProtection() = work.activityProtectionMode
                override fun contextActivityActive() = work.aimiContextActivityActive
                override fun setScenario(scenario: ScenarioProjectionPair) {
                    lastScenarioProjection = scenario
                }
                override fun refreshPhysiologicalPhase(
                    rT: RT,
                    combinedDelta: Float,
                    stepsLast15m: Int,
                    heartRateBpm: Int,
                    restingHeartRateBpm: Int,
                    basePhysioMultipliers: PhysioMultipliersMTR,
                ) {
                    this@DetermineBasalaimiSMB2.refreshPhysiologicalPhase(
                        rT, combinedDelta, stepsLast15m, heartRateBpm, restingHeartRateBpm, basePhysioMultipliers,
                    )
                }
                override fun refreshMealAbsorptionPhase(
                    combinedDelta: Float,
                    stepsLast15m: Int,
                    heartRateBpm: Int,
                    restingHeartRateBpm: Int,
                    mealContext: MealSafetyContext,
                    lastBolusTimeMs: Long?,
                    nowMs: Long,
                ) = this@DetermineBasalaimiSMB2.refreshMealAbsorptionPhase(
                    combinedDelta, stepsLast15m, heartRateBpm, restingHeartRateBpm, mealContext, lastBolusTimeMs, nowMs,
                )
                override fun publishPatientState(glucoseStatus: GlucoseStatusAIMI, nowMs: Long) {
                    publishPatientStateAfterPhysiologyRefresh(glucoseStatus, nowMs)
                }
                override fun setPredictedBg(value: Float) {
                    this@DetermineBasalaimiSMB2.predictedBg = value
                }
                override fun setEventualBgSnapshot(value: Double) {
                    work.lastEventualBgSnapshot = value
                }
                override fun setPredictionSize(value: Int) {
                    work.lastPredictionSize = value
                }
                override fun setPredictionAvailable(value: Boolean) {
                    work.lastPredictionAvailable = value
                }
                override fun pumpReachable() =
                    activePlugin.activePump.isInitialized() && activePlugin.activePump.isConnected()
                override fun setEarlyRiskEnvelope(envelope: AimiRiskEnvelope) {
                    cachedRiskEnvelopeEarly = envelope
                }
            },
        )
        return AimiAdvancedPredictionsPredPipePrep(prep.sanity, prep.minBg, prep.threshold, prep.scenario)
    }

    /**
     * Decision pipeline: safety before meal advisor — see roadmap invariant 5.
     * Mechanical extraction of historical inline [trySafetyStart] + TBR zero + [logDecisionFinal] / [markFinalLoopDecisionFromRT].
     */
    private fun runPredPipelineSafetyHaltOrReturn(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        bg: Double,
        delta: Float,
        combinedDelta: Float,
        iobData: IobTotal,
        glucoseStatus: GlucoseStatusAIMI,
        scenario: ScenarioProjectionPair,
        isExplicitAdvisorRun: Boolean,
    ): AimiPredPipelineSafetyGate {
        return when (
            val halt = decidePredPipelineSafetyHalt(
                ctx = ctx,
                profile = profile,
                rT = rT,
                bg = bg,
                delta = delta,
                combinedDelta = combinedDelta,
                iobData = iobData,
                glucoseStatus = glucoseStatus,
                scenario = scenario,
                isExplicitAdvisorRun = isExplicitAdvisorRun,
                consoleLog = work.consoleLog,
                calls = object : AimiSafetyHaltCalls {
                    override fun mealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal) =
                        buildMealSafetyContext(isExplicitAdvisorRun, iobData)
                    override fun projectionInput(
                        targetBgValue: Double,
                        cobValue: Double,
                        combinedDeltaValue: Float,
                    ) = correctionAggressionProjectionInput(targetBgValue, cobValue, combinedDeltaValue)
                    override fun cob() = work.cob
                    override fun mealAbsorptionPhase() =
                        work.lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE
                    override fun mealCertainty() = work.lastMealCertainty
                    override fun setSafetyTerminals(terminals: SafetyPredictionTerminals) {
                        work.lastSafetyTerminalsForRbt = terminals
                    }
                    override fun trySafetyStart(
                        bg: Double,
                        delta: Float,
                        profile: OapsProfileAimi,
                        iob: IobTotal,
                        noise: Int,
                        predBg: Double,
                        eventualBg: Double,
                        mealContext: MealSafetyContext,
                    ) = this@DetermineBasalaimiSMB2.trySafetyStart(
                        bg, delta, profile, work.iob, noise, predBg, eventualBg, mealContext,
                    )
                    override fun setSafetyRiskExport(snapshot: SafetyRiskExportSnapshot) {
                        lastSafetyRiskExport = snapshot
                    }
                    override fun adaptiveMult() = this@DetermineBasalaimiSMB2.causalState.adaptiveMult
                    override fun requestTempBasal(
                        rate: Double,
                        durationMin: Int,
                        profile: OapsProfileAimi,
                        rT: RT,
                        currentTemp: CurrentTemp,
                        overrideSafetyLimits: Boolean,
                        adaptiveMultiplier: Double,
                        allowPartialSafetyTbr: Boolean,
                        mealContext: MealSafetyContext,
                    ) {
                        setTempBasal(
                            rate,
                            durationMin,
                            profile,
                            rT,
                            currentTemp,
                            overrideSafetyLimits = overrideSafetyLimits,
                            adaptiveMultiplier = adaptiveMultiplier,
                            allowPartialSafetyTbr = allowPartialSafetyTbr,
                            mealContext = mealContext,
                        )
                    }
                    override fun setDecisionSource(source: String) {
                        work.lastDecisionSource = source
                    }
                    override fun logDecisionFinal(tag: String, rT: RT, bg: Double, delta: Float) {
                        this@DetermineBasalaimiSMB2.logDecisionFinal(tag, rT, bg, delta)
                    }
                    override fun markFinalLoop(rT: RT, currentTemp: CurrentTemp) {
                        markFinalLoopDecisionFromRT(rT, currentTemp)
                    }
                },
            )
        ) {
            AimiSafetyHalt.Continue -> AimiPredPipelineSafetyGate.Continue
            is AimiSafetyHalt.Halt -> AimiPredPipelineSafetyGate.Halt(halt.rT)
        }
    }

    /**
     * Phase 4C — reconcile EARLY safety snapshot with authoritative DECISION envelope (invariant 5 preserved:
     * safety still runs before advisor; DECISION enriches export only).
     */
    private fun reconcileSafetyRiskWithDecisionEnvelope() {
        val early = lastSafetyRiskExport ?: return
        val decision = cachedRiskEnvelopeDecision ?: return
        val deltaComposite = decision.compositeMinMgdl - early.compositeMinMgdl
        lastSafetyRiskExport = early.copy(
            decisionCompositeMinMgdl = decision.compositeMinMgdl,
            decisionHypoThresholdMgdl = decision.hypoThresholdMgdl,
            reconcileDeltaMgdl = deltaComposite,
        )
        work.consoleLog.add(
            "RISK_SAFETY_RECONCILE: earlyComposite=${early.compositeMinMgdl.toInt()} " +
                "decisionComposite=${decision.compositeMinMgdl.toInt()} Δ=${aimiFmt0(deltaComposite)} " +
                "earlyGate=${early.safetyGate} pathFloorHit=${if (decision.pathMinHitNumericFloor) 1 else 0}",
        )
    }

    private fun ScenarioProjectionPair.toDecisionContextExport(): AimiDecisionContext.ScenarioProjectionExport =
        AimiDecisionContext.ScenarioProjectionExport(
            floor_terminal_mgdl = clinicalFloor.terminalMgdl,
            floor_path_min_mgdl = clinicalFloor.pathMinMgdl,
            best_terminal_mgdl = scenarioBest.terminalMgdl,
            best_path_min_mgdl = scenarioBest.pathMinMgdl,
            best_gate_path_min_mgdl = scenarioBest.gatePathMinMgdl,
            best_gate_path_min_hit_floor = scenarioBest.gatePathMinHitFloor,
            terminal_gap_mgdl = scenarioBest.terminalMgdl - clinicalFloor.terminalMgdl,
            trajectory_type = trajectoryType,
            contributors = contributors.map { "${it.id.name}:${it.summary}" },
        )

    private fun SafetyRiskExportSnapshot.toDecisionContextExport(): AimiDecisionContext.SafetyRiskExport =
        AimiDecisionContext.SafetyRiskExport(
            phase = phase.name,
            predictive_hypo_suppressed = predictiveHypoSuppressed,
            safety_gate = safetyGate,
            halt_remaining_pipeline = haltRemainingPipeline,
            meal_context_active = mealContextActive,
            meal_rise_confirmed = mealRiseConfirmed,
            composite_min_mgdl = compositeMinMgdl,
            pred_bg_mgdl = predBgMgdl,
            eventual_bg_mgdl = eventualBgMgdl,
            uam_terminal_mgdl = uamTerminalMgdl,
            hypo_threshold_mgdl = hypoThresholdMgdl,
            decision_composite_min_mgdl = decisionCompositeMinMgdl,
            decision_hypo_threshold_mgdl = decisionHypoThresholdMgdl,
            reconcile_delta_mgdl = reconcileDeltaMgdl,
        )

    private fun logInvocationCacheState(tag: String, state: AsyncDataState<*>) {
        val msg = when (state) {
            is AsyncDataState.Fresh<*> -> "CACHE $tag=FRESH ageMs=${state.ageMs}"
            is AsyncDataState.Stale<*> -> "CACHE $tag=STALE ageMs=${state.ageMs}"
            is AsyncDataState.Missing -> "CACHE $tag=MISSING reason=${state.reason}"
        }
        work.consoleLog.add("📦 $msg")
    }

    /** TDD 24h from invocation cache; uses [fallback] when async data not yet available. */
    private fun resolveTdd24hForLoop(fallback: Double = 30.0): Double {
        val state = determineBasalInvocationCaches.getTdd24hTotalAmountState(tddCalculator)
        logInvocationCacheState("TDD24H", state)
        return state.valueOrNull() ?: fallback
    }

    /** For study export: null if cache missing (caller may omit field). */
    private fun resolveTdd24hForExport(): Double? {
        val state = determineBasalInvocationCaches.getTdd24hTotalAmountState(tddCalculator)
        return state.valueOrNull()
    }

    private fun resolveTdd1DaySparseForAverage(): LongSparseArray<TDD>? {
        val state = determineBasalInvocationCaches.getTddCalculate1DaySparseState(tddCalculator)
        logInvocationCacheState("TDD1D_SPARSE", state)
        return state.valueOrNull()
    }

    private fun resolveTir65180ForAverage(): LongSparseArray<TIR> {
        val state = determineBasalInvocationCaches.getTirCalculate1Day65180State(tirCalculator)
        logInvocationCacheState("TIR65180_1D", state)
        return state.valueOrNull() ?: LongSparseArray()
    }

    // État interne d’hystérèse
    // T6: Verrou nocturne anti-bang-bang pour BG_Rise_Fast
    // Empêche le trigger de s'emballer si l'IOB est déjà élevé la nuit
    // Tick-local scratch, reset at the start of every determine_basal call.
    private var work = AimiTickWorkingState()
    // The AIMI directory and the two training CSVs, named through the storage port. These were
    // `java.io.File` fields resolved from `AimiStorageHelper`; `AimiStorage` delegates to the very
    // same helper, so the files are in the same places as before.
    private val externalDirPath: AimiPath by lazy { storage.directory() }
    private val csvfilePath: AimiPath by lazy { storage.file("oapsaimiML2_records.csv") }
    private val csvfile2Path: AimiPath by lazy { storage.file("oapsaimi2_records.csv") }
    // Telemetry must never crash the loop: if the study exporter can't initialize (storage / permissions / context),
    // degrade to no telemetry rather than letting its construction abort the tick into a safe-hold. Null → all
    // telemetry calls below are no-ops (`?.`); AimiLoopTelemetry.enterPhase already accepts a null exporter.
    // The runCatching that used to live here moved into AndroidHormonitorStudyExporterProvider, unchanged.
    private val hormonitorStudyExporter: HormonitorStudyExporter? by lazy {
        hormonitorStudyExporterProvider.exporter()
    }
    /**
     * Appends a row to a training CSV. Shared code: see [AimiTrainingCsvWriter].
     *
     * A field of the tick rather than an injected singleton on purpose - it carries the "header
     * already checked" set and the one-shot "primary storage refused" flag, which were fields of this
     * class before, and a `lazy` here keeps them with exactly the same lifetime.
     */
    private val trainingCsvWriter by lazy { AimiTrainingCsvWriter(storage, aapsLogger) }
    private val pkpdIntegration = PkPdIntegration(preferences, pkPdLearnedState, behaviorProfileSource)
    private var variableSensitivity = 0.0f
    private var averageBeatsPerMinute = 0.0
    private var averageBeatsPerMinute10 = 0.0
    private var averageBeatsPerMinute60 = 0.0

    /**
     * True when [averageBeatsPerMinute60] came from real records rather than the 80 bpm substitute.
     * Read only by [HeartRateTrendIsf], which is the one gesture that can strengthen a dose.
     */
    private var heartRateBaselineIsReal = false
    private var averageBeatsPerMinute180 = 0.0
    private var now = aimiWallClockMs()
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var lastCycleNotificationDay: Int = -1 // State for cycle notification spam prevention
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var cachedPkpdRuntime: PkPdRuntime? = null // 🔧 FIX (MTR): Global cache for Safety methods
    private var cachedRiskEnvelopeEarly: AimiRiskEnvelope? = null
    private var cachedRiskEnvelopeDecision: AimiRiskEnvelope? = null
    private var lastSafetyRiskExport: SafetyRiskExportSnapshot? = null
    private var lastScenarioProjection: ScenarioProjectionPair? = null

    /** PKPD vs scenario divergence audit of the current tick (PredictionDivergenceAuditor). */
    private var lastPredDivergenceExport: JsonObject? = null
    private var lastIntelligenceSnapshot: AimiIntelligenceSnapshot? = null
    /** Cascade D4 / C1 — single dose terminal pair for the tick (post-Authority + thin Clamp). */
    private var lastDoseTerminalSnapshot: DoseTerminalSnapshot? = null

    private fun predictionAuthorityEnabled(): Boolean =
        preferences.get(BooleanKey.OApsAIMIPredictionAuthorityEnabled)

    /**
     * Dose-facing eventual: only [DoseTerminalSnapshot] / Authority / envelope — never an ungated
     * scenario preview (that bypassed Clamp sport/post-hypo/pathMin gates).
     */
    private fun authoritativeEventualBg(fallback: Double = work.eventualBG): Double {
        lastDoseTerminalSnapshot?.eventualMgdl?.takeIf { it.isFinite() }?.let { return it }
        if (predictionAuthorityEnabled()) {
            work.lastDecisionPredictionAuthority?.eventualTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
            cachedRiskEnvelopeDecision?.eventualTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
        }
        return fallback
    }

    private fun authoritativeMinPredBg(rT: RT, rawMinPred: Double?): Double? {
        lastDoseTerminalSnapshot?.minPredMgdl?.takeIf { it.isFinite() }?.let { return it }
        if (predictionAuthorityEnabled()) {
            work.lastDecisionPredictionAuthority?.predTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
            cachedRiskEnvelopeDecision?.predTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
        }
        return rawMinPred
    }

    /**
     * Wave1 H1: LGS PREDICTED_MIN_CURVE must use dose-facing minPred (snapshot), not raw
     * curve floor 39. Ignore raw floor when snapshot already lifted it.
     */
    private fun resolveLgsMinPredictedCurve(rT: RT): Pair<Double?, Boolean> {
        val rawMinPred = minPredictedAcrossCurves(rT.predBGs)
        val doseMinPred = authoritativeMinPredBg(rT, rawMinPred)
        val snapshotLiftedFloor =
            lastDoseTerminalSnapshot?.plateauFloorLifted == true ||
                (
                    rawMinPred != null &&
                        doseMinPred != null &&
                        rawMinPred <= DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL &&
                        doseMinPred > rawMinPred + 5.0
                    )
        val ignoreMinPredictedCurve = rbtIgnoreMinPredictedCurve() || snapshotLiftedFloor
        return doseMinPred to ignoreMinPredictedCurve
    }

    private data class TubeDoseBaseline(
        val maxSmb: Double,
        val maxSmbHb: Double,
        val currentBasal: Double,
        val maxDailyBasal: Double,
    )

    /** True after Tube has been applied from a pre-delivery snapshot this tick. */

    /**
     * Tube advise driven by [lastDoseTerminalSnapshot].
     * - Applied on pre-delivery publishes (`pre_rbt`, `pre_v3_rbt`) from a frozen baseline.
     * - Skipped on `late_pkpd` so mid-tick SMB/basal caps are not wiped after V3/RBT delivery.
     */
    private fun applyTubeAdvisorFromDoseSnapshot(
        profile: OapsProfileAimi,
        mealData: MealData,
        targetBgMgdl: Double,
        stageTag: String,
    ) = decideApplyTubeAdvisorFromDoseSnapshot(
        profile = profile,
        mealData = mealData,
        targetBgMgdl = targetBgMgdl,
        stageTag = stageTag,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiTubeAdvisorCalls {
            override fun snapshot() = lastDoseTerminalSnapshot
            override fun diaHours() = work.tickEffectiveDiaHours
            override fun isf() = variableSensitivity.toDouble()
            override fun baseline() = work.tubeDoseBaseline?.let {
                AimiTubeDoseBaseline(it.maxSmb, it.maxSmbHb, it.currentBasal, it.maxDailyBasal)
            }
            override fun writeBaseline(value: AimiTubeDoseBaseline) {
                work.tubeDoseBaseline = TubeDoseBaseline(value.maxSmb, value.maxSmbHb, value.currentBasal, value.maxDailyBasal)
            }
            override fun maxSmb() = work.maxSMB
            override fun maxSmbHb() = work.maxSMBHB
            override fun writeMaxSmb(value: Double) {
                work.maxSMB = value
            }
            override fun writeMaxSmbHb(value: Double) {
                work.maxSMBHB = value
            }
            override fun writeScale(value: Double?) {
                work.lastTubeAdvisorSmbCapScale = value
            }
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun iob() = work.iob.toDouble()
            override fun advise(input: StraightLineTubeAdvisor.Input) = straightLineTubeAdvisor.advise(input)
            override fun noteTrace(
                outcome: StraightLineTubeAdvisor.Outcome,
                snapshot: DoseTerminalSnapshot,
                stageTag: String,
                baseline: AimiTubeDoseBaseline,
            ) {
                noteTubeAdvisorTrace(
                    outcome,
                    snapshot,
                    stageTag,
                    TubeDoseBaseline(baseline.maxSmb, baseline.maxSmbHb, baseline.currentBasal, baseline.maxDailyBasal),
                )
            }
            override fun markApplied() {
                work.tubeAppliedFromDoseSnapshotThisTick = true
            }
            override fun logError(message: String) {
                work.consoleError.add(message)
            }
        },
    )

    /**
     * Record what the tube actually decided from, so the next support package can settle it by
     * measurement instead of inference.
     *
     * Why this is needed: the tube runs on the `pre_rbt` and `pre_v3_rbt` snapshots and is skipped on
     * `late_pkpd`, but `dose_terminal_snapshot` is exported **after** the `late_pkpd` republish. So the
     * exported `min_pred_mgdl` can be a very different, and better, number than the one that set the
     * ceiling. Measured on the 2026-08-13 package: on 6 of 81 zero-cap ticks the exported snapshot
     * says the tube should have allowed a scale of 0.10 to 1.00, which is only explicable if the
     * deciding snapshot was an earlier and worse one. Nothing exported could confirm that.
     *
     * Called once per applied stage; the last stage to run wins, because its caps are the ones that
     * survive into `finalizeAndCapSMB`.
     */
    private fun noteTubeAdvisorTrace(
        outcome: StraightLineTubeAdvisor.Outcome,
        snapshot: DoseTerminalSnapshot,
        stageTag: String,
        baseline: TubeDoseBaseline,
    ) {
        lastTubeAdvisorTrace = JsonObj().apply {
            put("deciding_stage", stageTag)
            put("branch", outcome.branch.name)
            put("feasible", outcome.feasible)
            put("smb_cap_scale", outcome.smbCapScale)
            put("basal_cap_scale", outcome.basalCapScale)
            // The deciding input. Compare with adjustments.dose_terminal_snapshot.min_pred_mgdl:
            // a difference means the late republish moved the prediction after the caps were frozen.
            outcome.minPredUsedMgdl?.let { put("min_pred_used_mgdl", it) }
            put("eventual_used_mgdl", snapshot.eventualMgdl)
            put("snapshot_source_used", snapshot.source)
            put("hypo_floor_mgdl", outcome.hypoFloorMgdl)
            put("kappa_mgdl_per_u", outcome.kappaMgdlPerU)
            // The dose-facing sensitivity the tube reasoned with. kappa cannot stand in for it: the
            // kappa curve saturates at 45 for every sensitivity at or below ~29.7 mg/dL/U.
            put("isf_used_mgdl_per_u", outcome.isfUsedMgdlPerU)
            put("max_smb_in_u", outcome.maxSmbU)
            put("s_max_feasible", outcome.sMaxFeasible)
            put("hyper_excess_mgdl", outcome.hyperExcessMgdl)
            put("max_smb_baseline_u", baseline.maxSmb)
            put("max_smb_after_u", work.maxSMB)
            put("max_smb_hb_after_u", work.maxSMBHB)
        }.build()
    }

    /**
     * Resolve Authority + Applicator + DoseTerminalSnapshot (+ optional Tube).
     * Must run **before** RBT/V3 so stacking/HTR see gated terminals, then again after full PKPD.
     */
    private fun publishDoseTerminalAuthorityAndSnapshot(
        rT: RT,
        profile: OapsProfileAimi,
        mealData: MealData,
        pkpdEventualMgdl: Double,
        pkpdPredTerminalMgdl: Double,
        targetBgMgdl: Double,
        stageTag: String,
    ) = decidePublishDoseTerminalAuthorityAndSnapshot(
        rT = rT,
        profile = profile,
        mealData = mealData,
        pkpdEventualMgdl = pkpdEventualMgdl,
        pkpdPredTerminalMgdl = pkpdPredTerminalMgdl,
        targetBgMgdl = targetBgMgdl,
        stageTag = stageTag,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiPublishDoseTerminalCalls {
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun scenarioProjection() = lastScenarioProjection
            override fun mealAbsorption() = work.lastMealAbsorptionOutput
            override fun hypothesis() = causalState.lastUamHypothesisState
            override fun latent() = causalState.lastPhysioLatentState
            override fun causalPosterior() = work.lastPatientState?.causalPosterior
            override fun trajectoryAnalysis() = trajectoryGuard.getLastAnalysis()
            override fun physioPolicy() = work.lastPhysiologicalPhaseOutput?.policy
            override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
            override fun postHypo() = work.lastPostHypoDeliveryAuthority
            override fun mealCertainty() = work.lastMealCertainty
            override fun trunk() = work.lastPhysiologicalTreeSnapshot?.trunk?.globalState
            override fun combinedDelta() = work.tickCombinedDelta.toDouble()
            override fun iob() = work.iob.toDouble()
            override fun maxIob() = work.maxIob
            override fun mcerLatch() = mcerTailLatch
            override fun anticipTime() = this@DetermineBasalaimiSMB2.anticipTime
            override fun setLatch(value: MealConfirmedEarlyReleaseLatch.State) {
                mcerTailLatch = value
            }
            override fun setAuthority(value: DecisionPredictionAuthority) {
                work.lastDecisionPredictionAuthority = value
            }
            override fun setApplyResult(value: PredictionAuthorityApplyResult) {
                work.lastPredictionAuthorityApplyResult = value
            }
            override fun writeEventual(mgdl: Double, rT: RT) {
                work.eventualBG = mgdl
                this@DetermineBasalaimiSMB2.predictedBg = mgdl.toFloat()
                rT.eventualBG = mgdl
            }
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun sportTime() = work.sportTime
            override fun setSnapshot(value: DoseTerminalSnapshot) {
                lastDoseTerminalSnapshot = value
            }
            override fun applyTube(
                profile: OapsProfileAimi,
                mealData: MealData,
                targetBgMgdl: Double,
                stageTag: String,
            ) = applyTubeAdvisorFromDoseSnapshot(profile, mealData, targetBgMgdl, stageTag)
        },
    )

    /**
     * Re-merge RBT HTR after late PKPD snapshot for finalize/SafetyNet consumers.
     * Does not re-deliver V3 SMB (pump path already used pre_v3_rbt / pre_rbt terminals).
     */
    private fun refineRbtMergeAfterDoseSnapshot(rT: RT) =
        decideRefineRbtMergeAfterDoseSnapshot(
            rT = rT,
            preferences = preferences,
            consoleLog = work.consoleLog,
            state = object : AimiRbtRefineState {
                override fun resolvedThisTick() = work.rbtResolvedThisTick
                override fun previousCommit() = work.lastRbtLiveCommitResult
                override fun authorityGate() = work.lastRecursiveAuthorityGateDecision
                override fun doseSnapshot() = lastDoseTerminalSnapshot
                override fun physiologicalPhase() = work.lastPhysiologicalPhaseOutput?.phase
                override fun bg() = this@DetermineBasalaimiSMB2.bg
                override fun delta() = this@DetermineBasalaimiSMB2.delta
                override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta
                override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg
                override fun iob() = work.iob
                override fun maxIob() = work.maxIob
                override fun mealDeliveryPriority() = work.lastMealAbsorptionOutput?.mealDeliveryPriority == true
                override fun suppressMealInterpretation() =
                    causalState.lastUamHypothesisState?.suppressMealInterpretation == true
                override fun mealAbsorptionPhase() = work.lastMealAbsorptionOutput?.phase
                override fun mealTime() = work.mealTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun snackTime() = work.snackTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun setStackingEvaluation(value: InsulinStackingStance.Evaluation) {
                    work.lastInsulinStackingEvaluation = value
                }
                override fun beliefSnapshot() = work.lastRecursiveBeliefSnapshot
                override fun setLiveCommit(value: RbtLiveCommitResult) {
                    work.lastRbtLiveCommitResult = value
                }
            },
            minPred = AimiMinPredWiring { rawMinPred -> minPredictedBgForRbtWiring(rawMinPred) },
            merge = AimiRbtHtrMerge { htr, rbtSnapshot, authorityGate, rt ->
                mergeRbtHyperTrajectoryRelease(htr, rbtSnapshot, authorityGate, rt)
            },
        )
    /** Wave4 H3 — last soft-floor path-min telemetry (JSON study + production curves). */
    private var lastPkpdSoftFloorTelemetry: PkpdSoftFloorTelemetry? = null
    /** Sync auditor disposition for the current tick (JSONL `adjustments.auditor_tick`). */
    private var lastAuditorTickDisposition: AuditorJsonlExport.TickDisposition? = null
    private var lastAuditorLoopSnapshot: AuditorJsonlExport.TickSnapshot? = null
    private var lastAuditorAuditStartedAtMs: Long = 0L

    /**
     * ISF and target of the running tick, at every level, for the auditor and for the JSONL.
     *
     * A fresh instance per tick, filled where the loop already holds each value. It also carries the
     * profile factor decision of the tick; `isfAppliedFactor` is the only field a dose path reads,
     * and it is exactly 1.0 unless the opt-in key is on.
     */
    private var auditorProfileTick = AuditorProfileTickState()

    /**
     * The last 45 minutes of ticks, as the engine saw them, for the auditor's 30-minute window.
     *
     * Written once per tick in the export stage, which every exit path runs. Read by the auditor
     * coroutine. Empty after a restart, and the window is then incomplete, which refuses every
     * proposal for half an hour.
     */
    private val auditorTickRing = AuditorTickRing()
    /**
     * Guards the follow-up append against being entered twice.
     *
     * A compare and set, not an [app.aaps.core.interfaces.concurrent.AapsLock]: that lock is
     * reentrant, so a second entry on the *same* thread would be let through, which is the opposite
     * of what this guard is for.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private val auditorFollowupAppendInProgress = AtomicBoolean(false)
    private val patternCapHold = PatternCapHold()

    private data class PendingTrajSpiralBasal(
        val proactiveBasalUph: Double,
        val durationMin: Int,
        val reason: String,
        val safetyTierLabel: String,
    )
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var lastProfile: OapsProfileAimi? = null
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 90.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f

    /**
     * Null in production: the tick clock port calls [aimiWallClockMs], which is `Clock.System`.
     * Tests set a fixed epoch so `hourOfDay` and `nightbis` do not follow the machine clock.
     */
    internal var injectedTickEpochMs: Long? = null
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var cachedActivityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext? = null  // Written by applyEndoAndActivityAdjustments
    private var cachedBasalFirstActive = false    // Written by applyBasalFirstPolicy
    private var cachedIsFragileBg = false         // Written by applyBasalFirstPolicy
    /** Intent contexte AIMI : activité déclarée (snapshot.hasActivity). */
    /** Sport thérapie OU activité AIMI : SMB off ; basale off si BG ≤ [EXERCISE_BASAL_RESUME_BG_MGDL], sauf T3c/standard si BG > seuil. */
    /** Hyper en montée pendant lockout exercice : basale non réduite (thyroïde / stress activité). SMB reste off par défaut. */
    /** Combined Δ G6-adjusted for the current tick (set in [runTickClockMaxSmbTirCarbAndGlucoseCopy]). */
    private var highCarbTime = false

    /** A meal the person declared with an "anticip" note; carries no prebolus. See [AnticipationBasalFloor]. */
    private var anticipTime = false

    /** Minutes since that declaration. Same type as [work.mealruntime], which this mirrors. */
    private var anticipruntime: Long = 0

    /**
     * The FCL mode: a declared meal that forces the meal basal ceiling while a low temp target runs,
     * and sends no prebolus. See [FclMealBasal].
     */
    private var fclTime = false

    /** Minutes since that declaration. Also the activation window for the one-shot prebolus latch. */
    private var fclruntime: Long = 0

    /**
     * Is an FCL meal declared right now. Single source of truth for the places that need it — see
     * [FclMealBasal.declared]. Takes the profile because the raw `target_bg` is what carries the temp
     * target the person set; the engine's own working target has already been reshaped by then.
     */
    private fun fclDeclaredThisTick(profile: OapsProfileAimi): Boolean =
        FclMealBasal.declared(
            fclNoteActive = fclTime,
            sportNoteActive = work.sportTime,
            tempTargetSet = profile.temptargetSet,
            targetBgMgdl = profile.target_bg,
        )
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var intervalsmb = 1
    /** One PKPD absorption-guard multiply per [determine_basal] tick (see [applyPkpdAbsorptionGuardOncePerTick]). */
    /**
     * Sécurité VITALE ce tick : [applySafetyPrecautions] a mis le SMB à 0 via [isCriticalSafetyCondition]
     * (protection hypo minPredBG, chute rapide, …). Le restore Red Carpet ne doit JAMAIS ressusciter ce
     * zéro-là — il n'est autorisé que sur les réductions « mineures » (throttle, refractory, damping).
     * N'affecte pas les actions explicites (elles passent avec ignoreSafetyConditions=true, le flag ne se
     * pose pas) ni les prébolus des modes manuels (chemin applyLegacyMealModes, hors Red Carpet).
     */

    /** 🔒 Lot 2 — dernier verdict des invariants terminaux du tick, exporté en JSON structuré. */
    private var lastBasalTerminalTelemetry: JsonObject? = null

    /** Universal Adaptive Basal scaling trace for this tick; exported as `adjustments.adaptive_basal`. */
    private var lastAdaptiveBasalTrace: JsonObject? = null

    /**
     * Carbs on board (g) read at the start of the current tick, for the basal-learning CSV.
     *
     * It is reset to `NaN` on every tick on purpose. A row written before the tick has read `mealData`
     * then says "not reported" instead of repeating the carbs of the previous tick. See
     * [basalLearningCobGrams].
     */

    /** 🔭 Lot 0 — `true` dès qu'une ligne `AIMI_Decisions.jsonl` a été écrite pour le tick courant. */

    /** 🔭 Lot 0 — contexte de décision du tick, conservé pour l'export des sorties anticipées. */

    /** 🛡️ Lot 3 — nombre de fois que le garde-fou a bloqué chaque canal basal-first (télémétrie). */

    /** 🛡️ Lot 3 — garde-fous du canal basal, voir [BooleanKey.OApsAIMIBasalChannelSafetyGuards]. */
    private fun basalChannelSafetyGuardsActive(): Boolean =
        preferences.get(BooleanKey.OApsAIMIBasalChannelSafetyGuards)

    /** Mode repas manuel déclaré (l'un des six). Même expression que les [MealSafetyContext] du tick. */
    private fun manualMealModeActive(): Boolean =
        work.mealTime || lunchTime || dinnerTime || work.snackTime || highCarbTime || bfastTime

    /**
     * `true` quand le canal basal-first doit être bloqué parce que le SMB de ce tick a été mis à zéro par
     * une **règle de sécurité** ([criticalSafetyZeroedThisTick] ou `lastContextSuppressSmb`), et non
     * simplement « pas demandé ». Les modes repas manuels sont exclus : leur basale doit s'appliquer.
     * Règle pure dans
     * [app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards.shouldBlockBasalFirst].
     */
    private fun smbZeroedBySafetyThisTick(): Boolean =
        BasalChannelSafetyGuards.shouldBlockBasalFirst(
            guardsEnabled = true, // le gate de préférence est évalué par l'appelant
            criticalSafetyZeroed = work.criticalSafetyZeroedThisTick,
            contextSuppressSmb = work.lastContextSuppressSmb,
            mealModeActive = manualMealModeActive(),
        )

    /**
     * Multiplicateur adaptatif conservé quand un plan basal-first (T3C natif / Harmonia production) possède
     * le taux. Historiquement forcé à `1.0`, ce qui jetait la réduction protectrice des learners — le seul
     * amortisseur qui liait encore. Règle pure dans
     * [app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier].
     */
    private fun basalFirstAdaptiveMultiplier(): Double =
        decideBasalFirstAdaptiveMultiplier(
            preferences = preferences,
            consoleLog = work.consoleLog,
            state = object : AimiBasalFirstAdaptiveState {
                override fun adaptiveMult() = this@DetermineBasalaimiSMB2.causalState.adaptiveMult
                override fun mealTime() = work.mealTime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun snackTime() = work.snackTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
            },
        )


    /** [ContinuousStateEstimator.runCount] at tick entry, to enforce "observe exactly once per tick". */

    /**
     * `MealAbsorptionMemory.lastDeltaMgdlPer5` as it stood at tick entry, i.e. the **previous tick's**
     * delta.
     *
     * `refreshMealAbsorptionPhase` reads that memory as `deltaPrevMgdlPer5` and then overwrites it
     * with the current delta. On an engaged tick the function runs twice — once unconditionally in
     * the prediction stage, once again inside the Autodrive gate — so the second, authoritative run
     * was reading `deltaPrev == deltaNow`, making the acceleration term zero by construction exactly
     * on the ticks that drive dosing.
     *
     * Latching the value here makes "previous" mean previous for every read of the tick, whichever
     * runs first, without having to decide which invocation is authoritative.
     */

    /** Whether [work.mealAbsorptionDeltaPrevForTick] has been latched this tick — `null` is a valid value. */

    /** HTR Ra floor computed this tick, or `null` when none. Reaches the belief tree, not the MPC. */

    /** True once `finalizeAndCapSMB` has written the tick's dose. See [applySmbUnits]. */

    /** Post-seal raises that were refused this tick, and by how much in total. Exported. */

    /** Post-seal raises that were allowed because their owner is on the exception list. Exported. */



    /**
     * Which branch of the maxSMB ladder fired this tick, and the two rise signals it read.
     *
     * The rise floor now obeys the ceiling this ladder picks, so we must be able to see whether the
     * ladder is sane. Measured on 2026-08-15: on the two rises that ended below 70 mg/dL the ladder
     * stayed on `STANDARD` while BG climbed 7 to 12 mg/dL per 5 minutes, and the only condition that
     * can have failed there is `slopeFromMinDeviation >= 1.0`. We cannot tell from the exports alone
     * whether the slope was right or simply blind, so both rise signals are written out.
     *
     * See [SmbBindingTrace] fields `max_smb_ladder_branch`, `slope_from_min_deviation` and
     * `short_avg_delta_mgdl_5m`.
     */
    private var lastMaxSmbLadderBranch: String? = null
    private var lastShortAvgDeltaAtLadder: Double? = null

    /**
     * Deltas the end-of-tick safety net feeds the estimator with.
     *
     * Seeded from the tick's own fields and overwritten with the exact values once the Autodrive
     * branch runs. On paths that return before that branch, `combinedDelta` was never computed, so
     * the seed is the honest best available — it only drives the process-noise term, not the model.
     */

    /** SMB proposed before the post-hypo cap, for `adjustments.post_hypo_delivery`. Per tick. */

    /** SMB left after the post-hypo cap, for `adjustments.post_hypo_delivery`. Per tick. */

    /** Effort SMB multiplier the belief asked for, before the confirmed-meal floor. Per tick. */

    /** Effort SMB multiplier actually applied, after the confirmed-meal floor. Per tick. */

    /** SMB entering the effort reduction, in units. Per tick. */

    /** SMB leaving the effort reduction, in units. Per tick. */

    /**
     * Ticks in a row where the bolus came out exactly at a configured ceiling. Cross-tick on purpose
     * — the whole point of [RiseCeilingGuard] is what happens across several ticks, so this must NOT
     * be reset per tick.
     */
    private var ceilingRepeatCount: Int = 0

    /** Clock of the last tick counted in [ceilingRepeatCount]; a hole restarts the count. */
    private var ceilingRepeatLastMs: Long = 0L

    /**
     * Holds the meal-confirmed early release off after a post-peak tail. Cross-tick on purpose — the
     * whole point of [MealConfirmedEarlyReleaseLatch] is that one noisy tick must not undo the
     * breaker, so this must NOT be reset per tick.
     */
    private var mcerTailLatch = MealConfirmedEarlyReleaseLatch.State()

    /**
     * Inputs and branch of the tube call that set this tick's SMB ceiling. See
     * `AimiDecisionContext.Adjustments.tube_advisor`. Written by `noteTubeAdvisorTrace`, per tick.
     */
    private var lastTubeAdvisorTrace: JsonObject? = null
    /** Diagnostic-only immutable SMB cap chain, replaced at every tick bootstrap. */

    /**
     * Holds the SMB training rows until their origin stamp and their realised glucose are known.
     *
     * Instrumentation only: it changes when a row reaches `oapsaimiML2_records.csv`, never what the
     * pump is asked to do. See [app.aaps.plugins.aps.openAPSAIMI.ml.SmbTrainingRowBuffer].
     *
     * Plain private field on purpose: not @Inject, not @Singleton. One consumer (this tick), same
     * sibling as [observedSensitivityMeter] / [insulinOriginMeter].
     */
    private val smbTrainingRowBuffer = SmbTrainingRowBuffer()

    /**
     * Tick clock shared by the queued training row and by its origin stamp.
     *
     * The row is queued in the middle of the tick and stamped at its end, so both need the same key
     * to be sure they speak about the same tick. `dateUtil.now()` moves between the two points and
     * would not match.
     */
    private var smbTrainingRowTickKey: Long = 0L
    /** Absolute context SMB ceiling (SlowCarbMeal); enforced robustly at [finalizeAndCapSMB]. Per-tick. */
    /** Hard context SMB-off (HypoRecovery); enforced robustly at [finalizeAndCapSMB]. Per-tick. */
    /** Cumulative context-SMB budget for a SlowCarbMeal early window (anti-overshoot, Q5). Cross-tick. */
    private val slowCarbEarlyBudgetU = 2.0

    /** NGR (Night Growth Resistance) basal multiplier for THIS tick (1.0 unless NGR active in its night window).
     *  Set in [refreshPatientStateRuntime]; consumed by [executeT3cBrittleMode] so NGR reaches the T3C basal. */

    /**
     * Physiological-context feature vector for the basal NN (record + inference), mirroring the SMB feature schema so
     * the basal model shares the tree's physiological view (latent physio + patient-mode + causal) instead of being
     * context-blind. Same order as [BasalNeuralLearner.modelInput] and the CSV physio columns: 4 latent + 3 mode + 3 causal.
     */
    private fun currentBasalPhysioFeatures(): FloatArray =
        SmbRefinementFeatureSchema.latentFeatureValues(causalState.lastPhysioLatentState) +
            SmbRefinementFeatureSchema.modeFeatureValues(work.lastPatientModeDecision) +
            SmbRefinementFeatureSchema.causalFeatureValues(work.lastPatientState?.causalPosterior)

    /**
     * Runtime blocker that refused Harmonia on the **previous** tick, and how many ticks in a row the
     * same blocker has refused it.
     *
     * Deliberately **not** reset with the other `last*` fields at the start of a tick: a memory that
     * is cleared every tick is not a memory. Same reason as `causalState.lastEffortMemory`, which the reset block
     * also leaves alone. Both are written at the very end of the export stage, after the export has
     * already read the previous value, so a tick exports the previous tick and never itself.
     *
     * Observation only. Nothing in the dosing chain reads either field.
     */
    private var harmoniaPrevRuntimeBlocker: String? = null
    private var harmoniaBlockedStreak: Int = 0
    private var lastPatientSourceSensor: SourceSensor? = null
    /** Latest IOB surveillance snapshot for JSONL (updated each [finalizeAndCapSMB]). */
    private var lastIobSurveillanceExport: IobSurveillanceExport? = null

    /** Current IOB (U) on effective (learned) kinetics for this tick; null when unavailable. See [resolveIobForGate]. */


    /** Effective-IOB release decision snapshot for AIMI_Decisions.jsonl. See [resolveIobForGate]. */
    private var lastIobReleaseExport: AimiDecisionContext.IobReleaseExport? = null
    fun isAutodriveEngaged(): Boolean = causalState.lastAutodriveState == AutodriveState.ENGAGED

    // 🛡️ PERSISTENT PREBOLUS LOCKOUT (MTR Safety Patch)
    // Survives instance re-creations and app restarts by combining Memory + SharedPreferences.
        companion object {
        /** Milliseconds per minute/day/hour. Replaces `java.util.concurrent.TimeUnit`. */
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val MILLIS_PER_DAY = 86_400_000L

        /**
         * Lookback (minutes) used to report the non-basal insulin of one basal-learning row. Slightly
         * longer than the 5-minute loop tick, so a bolus written a few seconds late is still reported.
         * See `basalLearningBolusUnits`.
         */
        private const val BASAL_LEARN_BOLUS_LOOKBACK_MIN = 6L

        // 🩸 Limiteur de pente MONTANTE de la basale (anti-whiplash). Voir [slewLimitBasalUp].
        private const val BASAL_SLEW_UP_ABS_MIN_UPH = 1.5    // hausse mini autorisée par tick (permet de repartir de 0)
        private const val BASAL_SLEW_UP_REL_FACTOR = 1.0     // +100 % du taux courant par tick
        private const val BASAL_SLEW_UP_PROFILE_MULT = 2.0   // +2× la basale profil par tick

        // 🏃 Effort veto lives in decideEffortSuppressesUndeclaredMeal.
        /**
         * Décision pure du verrou one-shot par tag. Le calcul vit avec la décision repas, dans
         * [app.aaps.plugins.aps.openAPSAIMI.effects.legacyPrebolusLatchBlocks].
         */
        internal fun legacyPrebolusLatchBlocks(firedAtMs: Long?, nowMs: Long, runtimeMin: Long): Boolean =
            app.aaps.plugins.aps.openAPSAIMI.effects.legacyPrebolusLatchBlocks(firedAtMs, nowMs, runtimeMin)

        /**
         * Limiteur de pente MONTANTE de la basale (anti-whiplash, testable). La cascade de règles produisait des
         * à-coups (0→7 U/h en un tick puis coupure) — le ressenti « corrige trop ». On borne la HAUSSE par tick à
         * `max(min absolu, %du taux courant, ×basale profil)` ; la BAISSE reste instantanée (sécurité LGS : jamais
         * limitée). Une vraie correction rampe alors sur ~2-3 ticks au lieu de piquer d'un coup.
         * @return le taux (U/h) borné à la hausse.
         */
        internal fun slewLimitBasalUp(prevRateUph: Double, proposedRateUph: Double, profileBasalUph: Double): Double {
            if (!proposedRateUph.isFinite()) return proposedRateUph
            if (proposedRateUph <= prevRateUph) return proposedRateUph              // descente → instantanée
            if (!prevRateUph.isFinite() || prevRateUph < 0.0) return proposedRateUph
            val maxIncrease = maxOf(
                BASAL_SLEW_UP_ABS_MIN_UPH,
                prevRateUph * BASAL_SLEW_UP_REL_FACTOR,
                profileBasalUph.coerceAtLeast(0.0) * BASAL_SLEW_UP_PROFILE_MULT,
            )
            return minOf(proposedRateUph, prevRateUph + maxIncrease)
        }

        /**
         * Délai minimal (ms) après un tir prébolus avant de pouvoir conclure « non délivré ».
         * Doit rester inférieur au TTL de délivrance (30 min) pour laisser au
         * carry-forward le temps de retenter (cooldown 6 min) avant d'alerter l'utilisateur —
         * un délai trop court (ex. 6,5 min) déclenchait l'alerte Option A pendant que le carry
         * retentait encore, souvent avec succès quelques minutes plus tard.
         */
        internal const val LEGACY_PREBOLUS_CONFIRM_DELAY_MS = LegacyPrebolusMemory.CONFIRM_DELAY_MS

        internal fun legacyPrebolusMissedDelivery(
            firedAtMs: Long?,
            lastSmbConfirmedMs: Long?,
            nowMs: Long,
            runtimeMin: Long,
        ): Boolean = app.aaps.plugins.aps.openAPSAIMI.effects.legacyPrebolusMissedDelivery(
            firedAtMs,
            lastSmbConfirmedMs,
            nowMs,
            runtimeMin,
        )

        /** Clears the static prebolus latch so a trace test starts from an empty memory. */
        internal fun resetLegacyPrebolusMemoryForTrace() {
            LegacyPrebolusMemory.reset()
        }
        /** Glycémie (mg/dL) au-dessus de laquelle la basale peut corriger malgré sport / contexte activité (SMB toujours off). */
        const val EXERCISE_BASAL_RESUME_BG_MGDL: Double = 220.0

        /**
         * Plancher hypo sévère (mg/dL, ≈ 3.0 mmol/L) : seul cas où un repas explicitement déclaré
         * (mode legacy actif ou insuline validée Meal Advisor) ne reçoit PAS son prebolus malgré la priorité repas.
         */
        const val SEVERE_HYPO_MEAL_OVERRIDE_MGDL: Double =
            app.aaps.plugins.aps.openAPSAIMI.effects.SEVERE_HYPO_MEAL_OVERRIDE_MGDL

        /** Fenêtre pour [minBgInLastMinutes] : min BG &lt; 70 dans cette durée → amortissement Ra post-hypo (AutoDrive V3). */
        private const val AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES = 75

        /** Seuil et fenêtre de l'hyperglycémie « persistante » qui entraîne le [BasalLearner] à la hausse. */
        private const val PERSISTENT_HYPER_BG_MGDL = 180.0
        private const val PERSISTENT_HYPER_LOOKBACK_MINUTES = 60
    }

    private var internalLastSmbMillis: Long
        get() = LegacyPrebolusMemory.lastSmbMillis(preferences)
        set(value) {
            LegacyPrebolusMemory.setLastSmbMillis(preferences, value)
        }

    /** Référence du dernier prébolus legacy demandé — sert d'origine à la recherche de confirmation en base. */
    private var internalLastLegacyPrebolusMillis: Long
        get() = LegacyPrebolusMemory.lastLegacyPrebolusMillis(preferences, dateUtil.now())
        set(value) {
            LegacyPrebolusMemory.setLastLegacyPrebolusMillis(preferences, value)
        }

    /** Montant (U) du prébolus legacy demandé mais pas encore confirmé en base. 0 si rien en vol. */
    private var pendingLegacyPrebolusUnit: Float
        get() = LegacyPrebolusMemory.pendingUnit(preferences)
        set(value) {
            LegacyPrebolusMemory.setPendingUnit(preferences, value)
        }

    /** Expiration (ms epoch) de l'état pending. */
    private var pendingLegacyPrebolusExpiry: Long
        get() = LegacyPrebolusMemory.pendingExpiry(preferences)
        set(value) {
            LegacyPrebolusMemory.setPendingExpiry(preferences, value)
        }
    private val nightGrowthResistanceMode = NightGrowthResistanceMode()
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal
    private val insulinObserver = app.aaps.plugins.aps.openAPSAIMI.pkpd.RealTimeInsulinObserver()  // 🚀 Real-Time Insulin Observer

    private fun sanitizeForJson(input: String): String =
        AimiTickPolicyMath.sanitizeForJson(input)

    private fun Double.toFixed2(): String = aimiFmt2(this)
    // kotlinx, because NGRConfig in commonMain is typed with kotlinx.datetime.LocalTime. Its
    // `parse` reads "HH:mm" without a formatter, so `ngrTimeFormatter` is no longer needed here.
    // The failure path is unchanged: anything unparseable falls back, exactly as before.
    private fun parseNgrTime(value: String, fallback: KxLocalTime): KxLocalTime =
        runCatching { KxLocalTime.parse(value) }.getOrElse { fallback }

    private class PkpdPortAdapter(
        private val pkpdIntegration: PkPdIntegration,
        private val patientWeightKgProvider: () -> Double = { 70.0 },
        private val physioLatentStateProvider: () -> PhysioLatentState? = { null },
        private val estimatedRaProvider: () -> Double? = { null },
    ) : PkpdPort {

        private fun pkpdLearningWindowMin(ctx: app.aaps.plugins.aps.openAPSAIMI.model.LoopContext): Int =
            ctx.pkpdWindowMin ?: 90

        private fun app.aaps.plugins.aps.openAPSAIMI.model.LoopContext.mealModeActive(): Boolean =
            modes.meal || modes.breakfast || modes.lunch || modes.dinner || modes.highCarb || modes.snack

        override fun snapshot(ctx: app.aaps.plugins.aps.openAPSAIMI.model.LoopContext): PkpdPort.Snapshot {
            val mealCtx = MealAggressionContext(
                mealModeActive = ctx.mealModeActive(),
                predictedBgMgdl = ctx.eventualBg,
                targetBgMgdl = ctx.profile.targetMgdl
            )
            val rt = pkpdIntegration.computeRuntime(
                epochMillis = ctx.nowEpochMillis,
                bg = ctx.bg.mgdl,
                deltaMgDlPer5 = ctx.bg.delta5,
                iobU = ctx.iobU,
                carbsActiveG = ctx.cobG,
                windowMin = pkpdLearningWindowMin(ctx),
                exerciseFlag = false, // remplace par ctx.modes.sport si dispo
                profileIsf = ctx.profile.isfMgdlPerU,
                tdd24h = ctx.tdd24hU,
                mealContext = mealCtx,
                combinedDelta = ctx.bg.combinedDelta,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                patientWeightKg = patientWeightKgProvider(),
                physioLatentState = physioLatentStateProvider(),
                estimatedRaMgdlPerMin = estimatedRaProvider()?.takeIf { it.isFinite() && it > 0.0 },
                allowLearning = false,
            )
            return if (rt != null) {
                PkpdPort.Snapshot(
                    diaMin   = (rt.params.diaHrs * 60.0).toInt(), // ✅ diaHrs
                    peakMin  = rt.params.peakMin.toInt(),
                    fusedIsf = rt.fusedIsf,
                    tailFrac = rt.tailFraction
                    // ⚠ champs SMB optionnels laissent null ici
                )
            } else {
                PkpdPort.Snapshot(diaMin = 6*60, peakMin = 60, fusedIsf = ctx.profile.isfMgdlPerU, tailFrac = 0.0)
            }
        }

        override fun dampSmb(units: Double, ctx: app.aaps.plugins.aps.openAPSAIMI.model.LoopContext, bypassDamping: Boolean): PkpdPort.DampingAudit {
            val mealCtx = MealAggressionContext(
                mealModeActive = ctx.mealModeActive(),
                predictedBgMgdl = ctx.eventualBg,
                targetBgMgdl = ctx.profile.targetMgdl
            )
            val rt = pkpdIntegration.computeRuntime(epochMillis = ctx.nowEpochMillis,
                                                    bg = ctx.bg.mgdl,
                                                    deltaMgDlPer5 = ctx.bg.delta5,
                                                    iobU = ctx.iobU,
                                                    carbsActiveG = ctx.cobG,
                                                    windowMin = pkpdLearningWindowMin(ctx),
                                                    exerciseFlag = false, // remplace par ctx.modes.sport si dispo
                                                    profileIsf = ctx.profile.isfMgdlPerU,
                                                    tdd24h = ctx.tdd24hU,
                                                    mealContext = mealCtx,
                                                    combinedDelta = ctx.bg.combinedDelta,
                                                    uamConfidence = AimiUamHandler.confidenceOrZero(),
                                                    patientWeightKg = patientWeightKgProvider(),
                                                    physioLatentState = physioLatentStateProvider(),
                                                    estimatedRaMgdlPerMin = estimatedRaProvider()?.takeIf { it.isFinite() && it > 0.0 },
                                                    allowLearning = false)

            val damping = SmbDampingUsecase.run(
                rt,
                SmbDampingUsecase.Input(
                    smbDecision = units,
                    exercise = false, // adapte si tu as un flag d’exercice
                    suspectedLateFatMeal = ctx.modes.highCarb, // ✅ depuis les modes
                    mealModeRun = bypassDamping,
                    highBgRiseActive = false
                )
            )
            val audit = damping.audit
            return if (audit != null) {
                PkpdPort.DampingAudit(
                    out = damping.smbAfterDamping,
                    tailApplied = audit.tailApplied, tailMult = audit.tailMult,
                    exerciseApplied = audit.exerciseApplied, exerciseMult = audit.exerciseMult,
                    lateFatApplied = audit.lateFatApplied, lateFatMult = audit.lateFatMult,
                    mealBypass = audit.mealBypass
                )
            } else {
                PkpdPort.DampingAudit(damping.smbAfterDamping, false, 1.0, false, 1.0, false, 1.0, mealBypass = false)
            }
        }


        override fun logCsv(
            ctx: app.aaps.plugins.aps.openAPSAIMI.model.LoopContext,
            pkpd: PkpdPort.Snapshot,
            smbProposed: Double,
            smbFinal: Double,
            audit: PkpdPort.DampingAudit?
        ) {
            val dateStr = aimiCsvTimestampMinute(ctx.nowEpochMillis)
            val epochMin = ctx.nowEpochMillis / MILLIS_PER_MINUTE
            PkPdCsvLogger.append(
                PkPdLogRow(
                    dateStr = dateStr,
                    epochMin = epochMin,
                    bg = ctx.bg.mgdl,
                    delta5 = ctx.bg.delta5,
                    iobU = ctx.iobU,
                    carbsActiveG = ctx.cobG,
                    windowMin = pkpdLearningWindowMin(ctx),
                    diaH = pkpd.diaMin / 60.0,
                    peakMin = pkpd.peakMin.toDouble(),
                    fusedIsf = pkpd.fusedIsf,
                    tddIsf = 1800.0 / (ctx.tdd24hU.coerceAtLeast(0.1)),
                    profileIsf = ctx.profile.isfMgdlPerU,
                    tailFrac = pkpd.tailFrac,
                    smbProposedU = smbProposed,
                    smbFinalU = smbFinal,
                    tailMult = audit?.tailMult,
                    exerciseMult = audit?.exerciseMult,
                    lateFatMult = audit?.lateFatMult,
                    highBgOverride = null,
                    lateFatRise = pkpd.lateFatRise,
                    quantStepU = ctx.pump.bolusStep
                )
            )
        }
    }

    private val nightGrowthLearner = NightGrowthResistanceLearner()

    private fun buildNightGrowthResistanceConfig(
        profile: OapsProfileAimi,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        targetBg: Double
    ): NGRConfig {
        val age = preferences.get(IntKey.OApsAIMINightGrowthAgeYears).coerceAtLeast(0)
        val enabledPref = preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled)
        val nightStart = parseNgrTime(preferences.get(StringKey.OApsAIMINightGrowthStart), KxLocalTime(22, 0))
        val nightEnd = parseNgrTime(preferences.get(StringKey.OApsAIMINightGrowthEnd), KxLocalTime(6, 0))
        val extraIobPerSlot = max(0.0, preferences.get(DoubleKey.OApsAIMINightGrowthMaxIobExtra))
        val diaMinutes = max(60, (profile.dia * 60.0).roundToInt())
        val features = glucoseStatusCalculatorAimi.getAimiFeatures(true)
        val learnerOutput = nightGrowthLearner.derive(
            NightGrowthResistanceLearner.Input(
                ageYears = age,
                autosensRatio = autosens.ratio,
                diaMinutes = diaMinutes,
                isfMgdl = profile.sens,
                targetBg = targetBg,
                basalRate = profile.current_basal,
                stabilityMinutes = features?.stable5pctMinutes ?: 0.0,
                combinedDelta = features?.combinedDelta ?: 0.0,
                bgNoise = glucoseStatus?.noise ?: 0.0
            )
        )
        // T3C dependency: manual T3C activation implies NGR ON (nocturnal basal support) even if its own toggle is off.
        val enabled = (enabledPref ?: (age < 18)) || t3cModeEnabled()
        val slotCap = if (age < 10) 6 else 4
        return NGRConfig(
            enabled = enabled,
            pediatricAgeYears = age,
            nightStart = nightStart,
            nightEnd = nightEnd,
            minRiseSlope = learnerOutput.minRiseSlope,
            minDurationMin = learnerOutput.minDurationMinutes,
            minEventualOverTarget = learnerOutput.minEventualOverTarget,
            allowSMBBoostFactor = learnerOutput.smbBoost,
            allowBasalBoostFactor = learnerOutput.basalBoost,
            maxSMBClampU = learnerOutput.maxSmbClamp,
            extraIobPer30Min = extraIobPerSlot,
            decayMinutes = learnerOutput.decayMinutes,
            headroomSlotCap = slotCap
        )
    }
    private fun predictGlycemia(
        currentBG: Double,
        basalCandidateUph: Double,
        horizonMinutes: Int,
        insulinSensitivityMgdlPerU: Double,
        stepMinutes: Int = 5,
        minBgClamp: Double = 40.0,
        maxBgClamp: Double = 400.0,
        diaMinutes: Int = 300,
        timeToPeakMinutes: Int = 75,
    ): List<Double> = AimiTickPolicyMath.predictGlycemia(
        currentBG,
        basalCandidateUph,
        horizonMinutes,
        insulinSensitivityMgdlPerU,
        stepMinutes,
        minBgClamp,
        maxBgClamp,
        diaMinutes,
        timeToPeakMinutes,
    )

    /**
     * Calcule la fonction de coût, ici la somme des carrés des écarts entre les glycémies prédites et la glycémie cible.
     *
     * @param basalCandidate La dose candidate de basal.
     * @param currentBG La glycémie actuelle.
     * @param targetBG La glycémie cible.
     * @param horizonMinutes L’horizon de prédiction (en minutes).
     * @param insulinSensitivity La sensibilité insulinique.
     * @return Le coût cumulé.
     */
    fun costFunction(
        basalCandidate: Double, currentBG: Double,
        targetBG: Double, horizonMinutes: Int,
        insulinSensitivity: Double, nnPrediction: Double
    ): Double {
        // C2: shape the basal cost-function's glycemia projection with the EFFECTIVE learned kinetics
        // (InsulinKineticsAuthority → tickEffectiveDia/Peak) instead of the static 300/75, so the basal optimizer
        // predicts on how THIS patient's insulin acts. Fail-safe to the profile default when unavailable/invalid.
        val diaMinutes = work.tickEffectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 60.0).roundToInt() } ?: 300
        val timeToPeakMinutes = work.tickEffectivePeakMinutes?.takeIf { it.isFinite() && it > 0.0 }?.toInt() ?: 75
        val predictions = predictGlycemia(
            currentBG, basalCandidate, horizonMinutes, insulinSensitivity,
            diaMinutes = diaMinutes, timeToPeakMinutes = timeToPeakMinutes,
        )
        val predictionCost = predictions.sumOf { (it - targetBG).pow(2) }
        val nnPenalty = (basalCandidate - nnPrediction).pow(2)
        return predictionCost + 0.5 * nnPenalty  // Pondération du terme de pénalité
    }

    private fun roundBasal(value: Double): Double =
        AimiTickPolicyMath.roundBasal(value)


    /**
     * Ajuste la dose d'insuline (SMB) et décide éventuellement de stopper la basale.
     *
     * @param currentBG Glycémie actuelle (mg/dL).
     * @param predictedBG Glycémie prédite par l'algorithme (mg/dL).
     * @param bgHistory Historique des BG récents (pour calculer le drop/h).
     * @param combinedDelta Delta combiné mesuré et prédit (mg/dL/5min).
     * @param iob Insuline active (IOB).
     * @param maxIob IOB maximum autorisé.
     * @param tdd24Hrs Total daily dose sur 24h (U).
     * @param tddPerHour TDD/h sur la dernière heure (U/h).
     * @param tirInhypo Pourcentage du temps passé en hypo.
     * @param targetBG Objectif de glycémie (mg/dL).
     * @param zeroBasalDurationMinutes Durée cumulée en minutes pendant laquelle la basale est déjà à zéro.
     */
    private fun safetyPhrase(id: String, vararg args: Any?): String = when (id) {
        "bg_drop_high_critical" -> rh.gs(ApsStrings.bg_drop_high_critical, *args)
        "bg_drop_high_warning" -> rh.gs(ApsStrings.bg_drop_high_warning, *args)
        "bg_rapid_rise" -> rh.gs(ApsStrings.bg_rapid_rise, *args)
        "bg_combined_delta_weak" -> rh.gs(ApsStrings.bg_combined_delta_weak, *args)
        "bg_combined_delta_moderate" -> rh.gs(ApsStrings.bg_combined_delta_moderate, *args)
        "bg_combined_delta_high" -> rh.gs(ApsStrings.bg_combined_delta_high, *args)
        "bg_stable_high_delta_low" -> rh.gs(ApsStrings.bg_stable_high_delta_low, *args)
        "iob_high_reduction" -> rh.gs(ApsStrings.iob_high_reduction, *args)
        "tdd_per_hour_high" -> rh.gs(ApsStrings.tdd_per_hour_high, *args)
        "tir_high" -> rh.gs(ApsStrings.tir_high, *args)
        "bg_near_target" -> rh.gs(ApsStrings.bg_near_target, *args)
        "bg_near_target_but_rising" -> rh.gs(ApsStrings.bg_near_target_but_rising, *args)
        "zero_basal_forced" -> rh.gs(ApsStrings.zero_basal_forced, *args)
        "dia_base_info" -> rh.gs(ApsStrings.dia_base_info, *args)
        "morning_adjustment" -> rh.gs(ApsStrings.morning_adjustment, *args)
        "night_adjustment" -> rh.gs(ApsStrings.night_adjustment, *args)
        "reason_bio_sync_stress" -> rh.gs(ApsStrings.reason_bio_sync_stress, *args)
        "reason_bio_sync_flow" -> rh.gs(ApsStrings.reason_bio_sync_flow, *args)
        "pump_age_adjustment" -> rh.gs(ApsStrings.pump_age_adjustment, *args)
        "final_dia_constrained" -> rh.gs(ApsStrings.final_dia_constrained, *args)
        "dia_calculation_details" -> rh.gs(ApsStrings.dia_calculation_details, *args)
        "insulin_effect" -> rh.gs(ApsStrings.insulin_effect, *args)
        "calc_dynamic_peaktime" -> rh.gs(ApsStrings.calc_dynamic_peaktime, *args)
        "profile_peak_time" -> rh.gs(ApsStrings.profile_peak_time, *args)
        "bg_delta" -> rh.gs(ApsStrings.bg_delta, *args)
        "reason_hyper_correction" -> rh.gs(ApsStrings.reason_hyper_correction, *args)
        "reason_iob_adjustment_inverted" -> rh.gs(ApsStrings.reason_iob_adjustment_inverted, *args)
        "reason_activity_ratio" -> rh.gs(ApsStrings.reason_activity_ratio, *args)
        "reason_sensor_lag" -> rh.gs(ApsStrings.reason_sensor_lag, *args)
        "reason_sensor_lag_lower" -> rh.gs(ApsStrings.reason_sensor_lag_lower, *args)
        else -> throw IllegalArgumentException("unknown safety phrase $id")
    }

    private fun safetyPhraseBook() = AimiHypoSmbSafety.PhraseBook { id, args -> safetyPhrase(id, *args) }

    fun safetyAdjustment(
        currentBG: Float,
        predictedBG: Float,
        bgHistory: List<Float>,
        combinedDelta: Float,
        iob: Float,
        maxIob: Float,
        tdd24Hrs: Float,
        tddPerHour: Float,
        tirInhypo: Float,
        targetBG: Float,
        zeroBasalDurationMinutes: Int
    ): SafetyDecision = AimiHypoSmbSafety.safetyAdjustment(
        currentBG = currentBG,
        predictedBG = predictedBG,
        bgHistory = bgHistory,
        combinedDelta = combinedDelta,
        iob = iob,
        maxIob = maxIob,
        tdd24Hrs = tdd24Hrs,
        tddPerHour = tddPerHour,
        tirInhypo = tirInhypo,
        targetBG = targetBG,
        zeroBasalDurationMinutes = zeroBasalDurationMinutes,
        delta = delta,
        honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon),
        phrase = safetyPhraseBook(),
    )

    fun adjustDIAForIOB(diaMinutes: Float, currentIOB: Float, threshold: Float = 2f): Float =
        AimiTickPolicyMath.adjustDIAForIOB(diaMinutes, currentIOB, threshold)
    /**
     * Calcule le DIA ajusté en minutes en fonction de plusieurs paramètres :
     * - baseDIAHours : le DIA de base en heures (par exemple, 9.0 pour 9 heures)
     * - currentHour : l'heure actuelle (0 à 23)
     * - recentSteps5Minutes : nombre de pas sur les 5 dernières minutes
     * - currentHR : fréquence cardiaque actuelle (bpm)
     * - averageHR60 : fréquence cardiaque moyenne sur les 60 dernières minutes (bpm)
     *
     * La logique appliquée :
     * 1. Conversion du DIA de base en minutes.
     * 2. Ajustement selon l'heure de la journée :
     *    - Matin (6-10h) : réduction de 20% (×0.8),
     *    - Soir/Nuit (22-23h et 0-5h) : augmentation de 20% (×1.2).
     * 3. Ajustement en fonction de l'activité physique :
     *    - Si recentSteps5Minutes > 200 et que currentHR > averageHR60, on réduit le DIA de 30% (×0.7).
     *    - Si recentSteps5Minutes == 0 et que currentHR > averageHR60, on augmente le DIA de 30% (×1.3).
     * 4. Ajustement selon la fréquence cardiaque absolue :
     *    - Si currentHR > 130 bpm, on réduit le DIA de 30% (×0.7).
     * 5. Le résultat final est contraint entre 180 minutes (3h) et 720 minutes (12h).
     */
    fun calculateAdjustedDIA(
        baseDIAHours: Float,
        currentHour: Int,
        pumpAgeDays: Float,
        iob: Double = 0.0,
        activityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext,
        steps: Int? = null,
        heartRate: Int? = null
    ): Double {
        val out = AimiHypoSmbSafety.adjustedDiaMinutes(
            baseDIAHours = baseDIAHours,
            currentHour = currentHour,
            pumpAgeDays = pumpAgeDays,
            iob = iob,
            activityContext = activityContext,
            steps = steps,
            heartRate = heartRate,
            phrase = safetyPhraseBook(),
        )
        println(safetyPhrase("dia_calculation_details"))
        println(out.reason)
        work.latestAdjustedDia = out.minutes
        return out.minutes
    }

    // -- Méthode pour obtenir l'historique récent de BG, similaire à getRecentBGs() --
    private fun getRecentBGs(): List<Float> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()
        val intervalMinutes = if (bg < 130) 50f else 25f
        val nowTimestamp = data.first().timestamp
        val recentBGs = mutableListOf<Float>()

        for (i in 1 until data.size) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val minutesAgo = ((nowTimestamp - data[i].timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 1.0f..intervalMinutes) {
                    // Utilisation de la valeur recalculée comme BG
                    recentBGs.add(data[i].recalculated.toFloat())
                }
            }
        }
        return recentBGs
    }

    private fun buildCorrectionAggressionInput(
        bg: Double,
        targetBg: Double,
        delta: Float,
        shortAvgDelta: Float,
        combinedDelta: Float,
        cob: Double,
        postHypoHint: CorrectionAggressionGate.PostHypoHint = CorrectionAggressionGate.PostHypoHint.NONE,
    ): CorrectionAggressionGate.Input {
        val recentEstimateCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val recentEstimateTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val estimateAgeMin = if (recentEstimateTime > 0L) {
            (aimiWallClockMs() - recentEstimateTime) / 60000.0
        } else {
            Double.MAX_VALUE
        }
        val hasRecentMealEstimate = recentEstimateCarbs > 10.0 && estimateAgeMin in 0.0..45.0
        return CorrectionAggressionGate.Input(
            bg = bg,
            targetBg = targetBg,
            deltaMgdl5m = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            combinedDelta = combinedDelta.toDouble(),
            cob = cob,
            minBgLookback75m = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            estimatedCarbs = recentEstimateCarbs,
            estimatedCarbsAgeMin = estimateAgeMin,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
            estimatedRa = continuousStateEstimator.getLastRa(),
            explicitMealMode = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime,
            hasRecentMealEstimate = hasRecentMealEstimate,
            isConfirmedHighRise = work.isConfirmedHighRiseThisTick,
            postHypoHint = postHypoHint,
        )
    }

    private fun correctionAggressionProjectionInput(
        targetBgValue: Double,
        cobValue: Double,
        combinedDeltaValue: Float,
    ): CorrectionAggressionGate.Input =
        buildCorrectionAggressionInput(
            bg = bg,
            targetBg = targetBgValue,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            combinedDelta = combinedDeltaValue,
            cob = cobValue,
        )


    private fun capBasalRateForCorrectionAggression(
        requestedRateUph: Double,
        profileBasalUph: Double,
        source: String,
    ): Double {
        val cap = CorrectionAggressionBasalCap.apply(
            requestedRateUph = requestedRateUph,
            profileBasalUph = profileBasalUph,
            gate = work.correctionAggressionDecision,
        )
        if (cap.wasCapped) {
            work.consoleLog.add(
                CorrectionAggressionBasalCap.formatLogLine(
                    source = source,
                    requestedUph = requestedRateUph,
                    result = cap,
                    tier = work.correctionAggressionDecision?.tier,
                ),
            )
        }
        // 🏃 Activity basal ceiling: during an active exercise / AIMI activity context, insulin sensitivity
        // rises, so the basal must be capped too — not just the SMB. Without this, branches like the post-hypo
        // REBOUND_GUARD TBR bridge or the meal boost concentrate the (SMB-suppressed) correction into a
        // runaway TBR (observed 8–9× profile). Ceiling only (coerceAtMost): never raises a rate, never
        // affects a 0/suspend command. Factor is user-tunable via [DoubleKey.OApsAIMIActivityBasalCapFactor].
        var finalRateUph = cap.cappedRateUph
        if (work.exerciseInsulinLockoutActive && profileBasalUph > 0.0) {
            val activityFactor = preferences.get(DoubleKey.OApsAIMIActivityBasalCapFactor)
            val activityCapUph = profileBasalUph * activityFactor
            if (finalRateUph > activityCapUph) {
                work.consoleLog.add(
                    "🏃 ACTIVITY_BASAL_CAP[$source]: ${aimiFmt2(finalRateUph)}→${aimiFmt2(activityCapUph)} U/h (≤ ${aimiFmt2(activityFactor)}× profile ${aimiFmt2(profileBasalUph)})"
                )
                finalRateUph = activityCapUph
            }
        }
        return finalRateUph
    }

    private fun evaluateAndLogCorrectionAggression(
        bg: Double,
        targetBg: Double,
        delta: Float,
        shortAvgDelta: Float,
        combinedDelta: Float,
        cob: Double,
        postHypoHint: CorrectionAggressionGate.PostHypoHint = CorrectionAggressionGate.PostHypoHint.NONE,
    ): CorrectionAggressionGate.Decision {
        val input = buildCorrectionAggressionInput(
            bg = bg,
            targetBg = targetBg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            combinedDelta = combinedDelta,
            cob = cob,
            postHypoHint = postHypoHint,
        )
        val decision = CorrectionAggressionGate.evaluate(input)
        work.correctionAggressionDecision = decision
        CorrectionAggressionGate.appendLogs(decision, input, work.consoleLog, aapsLogger)
        refreshPostHypoDeliveryAuthority(
            combinedDelta = combinedDelta,
            postHypoHint = postHypoHint,
        )
        return decision
    }

    private fun refreshPostHypoDeliveryAuthority(
        combinedDelta: Float,
        postHypoHint: CorrectionAggressionGate.PostHypoHint = CorrectionAggressionGate.PostHypoHint.NONE,
    ) {
        val aggressionInput = buildCorrectionAggressionInput(
            bg = bg,
            targetBg = targetBg.toDouble(),
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            combinedDelta = combinedDelta,
            cob = work.cob.toDouble(),
            postHypoHint = postHypoHint,
        )
        work.lastPostHypoDeliveryAuthority = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = work.correctionAggressionDecision,
                patientMode = work.lastPatientModeDecision?.mode,
                aggressionInput = aggressionInput,
            ),
        )
        if (work.lastPostHypoDeliveryAuthority.active) {
            work.consoleLog.add(PostHypoDeliveryAuthority.formatLogLine(work.lastPostHypoDeliveryAuthority))
        }
    }

    private fun refreshPostHypoDeliveryAuthorityForTick(
        combinedDelta: Float,
        recentBGs: List<Float>,
        shortAvgDeltaAdj: Float,
        slopeFromMinDeviation: Double,
        reason: StringBuilder,
    ) {
        val postHypoState = classifyPostHypoState(
            recentBGs = recentBGs,
            cob = work.cob.toDouble(),
            explicitMealMode = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime,
            shortAvgDelta = shortAvgDeltaAdj,
            delta = delta,
            slopeFromMinDeviation = slopeFromMinDeviation,
            estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs),
            estimatedCarbsAgeMs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong(),
            localHour = work.hourOfDay,
            reason = reason,
        )
        val postHypoHint = mapPostHypoToAggressionHint(postHypoState)
        val aggressionInputForRefine = buildCorrectionAggressionInput(
            bg = bg,
            targetBg = targetBg.toDouble(),
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            combinedDelta = combinedDelta,
            cob = work.cob.toDouble(),
            postHypoHint = postHypoHint,
        )
        work.correctionAggressionDecision = work.correctionAggressionDecision?.let { prior ->
            CorrectionAggressionGate.refineForPostHypo(
                prior,
                aggressionInputForRefine,
                postHypoHint,
            )
        } ?: CorrectionAggressionGate.evaluate(aggressionInputForRefine)
        work.correctionAggressionDecision?.let { refined ->
            CorrectionAggressionGate.appendLogs(refined, aggressionInputForRefine, work.consoleLog, aapsLogger)
        }
        refreshPostHypoDeliveryAuthority(
            combinedDelta = combinedDelta,
            postHypoHint = postHypoHint,
        )
    }

    private fun mapPostHypoToAggressionHint(state: PostHypoState): CorrectionAggressionGate.PostHypoHint =
        when (state) {
            is PostHypoState.ReboundSuspected -> CorrectionAggressionGate.PostHypoHint.REBOUND_SUSPECTED
            is PostHypoState.MealConfirmed -> CorrectionAggressionGate.PostHypoHint.MEAL_CONFIRMED
            PostHypoState.None -> CorrectionAggressionGate.PostHypoHint.NONE
        }

    /**
     * Minimum recalculated BG (mg/dL) over bucketed data in [0, lookbackMinutes].
     * Used for AutoDrive post-hypo rescue rebound guard (companion: AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES).
     * Returns a high sentinel if no valid points.
     */
    private fun minBgInLastMinutes(lookbackMinutes: Int): Double =
        minBgInLastMinutesOrNull(lookbackMinutes) ?: 200.0

    /**
     * The same minimum, but null instead of the high sentinel when the history cannot answer.
     *
     * [minBgInLastMinutes] returns 200.0 when the bucketed table is missing, empty, or made only of
     * gap-filled rows. For the engine's own rebound guard that fails open on purpose. For the auditor
     * gate it must not: "no history" would read as "no low in 75 minutes" right after a sensor gap,
     * which is when a low is most likely. The auditor reads this one and refuses on null.
     */
    private fun minBgInLastMinutesOrNull(lookbackMinutes: Int): Double? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        if (data.isEmpty()) return null
        val nowTimestamp = data.first().timestamp
        val cutoff = nowTimestamp - lookbackMinutes * 60_000L
        var minVal = Double.MAX_VALUE
        for (i in data.indices) {
            val row = data[i]
            if (row.timestamp < cutoff) continue
            if (row.value <= 39 || row.filledGap) continue
            val v = row.recalculated
            if (v < minVal) minVal = v
        }
        return if (minVal == Double.MAX_VALUE) null else minVal
    }

    fun appendCompactLog(
        reason: StringBuilder,
        peakTime: Double,
        bg: Double,
        delta: Float,
        stepCount: Int?,
        heartRate: Double?
    ) {
        val bgStr = aimiFmt0(bg)
        val deltaStr = aimiFmt1(delta)
        val peakStr = aimiFmt1(peakTime)

//  reason.append("  → 🕒 PeakTime=$peakStr min | BG=$bgStr Δ$deltaStr")
        reason.append(rh.gs(ApsStrings.peak_time, peakStr, bgStr, deltaStr))
        stepCount?.let { reason.append(rh.gs(ApsStrings.steps, it)) }
        //  heartRate?.let { reason.append(" | HR=$it bpm") }
        heartRate?.let { reason.append(rh.gs(ApsStrings.heart_rate, if (it.isNaN()) "--" else aimiFmt0(it))) }
        reason.append("\n")
    }

    /**
     * Lignes **rT.reason** : statut Autodrive / mode repas, snapshot TIR (membres [currentTIRLow] / [currentTIRRange] / [currentTIRAbove]),
     * puis [appendCompactLog] sur [reasonAimi] et concaténation — inchangé vs inline historique.
     */
    private fun appendAutodriveStatusTirAndCompactPhysioSummaryToReason(
        rT: RT,
        autodriveDisplay: String,
        activeModeName: String,
        reasonAimi: StringBuilder,
        tp: Double,
        bg: Double,
        delta: Float,
        recentSteps5Minutes: Int,
        averageBeatsPerMinute: Double,
    ) {
        rT.reason.appendLine(
            rh.gs(ApsStrings.autodrive_status, autodriveDisplay, activeModeName),
        )
        rT.reason.appendLine(
            "📊 TIR: <70: ${aimiFmt1(currentTIRLow)}% | 70–180: ${aimiFmt1(currentTIRRange)}% | >180: ${aimiFmt1(currentTIRAbove)}%",
        )
        appendCompactLog(reasonAimi, tp, bg, delta, recentSteps5Minutes, averageBeatsPerMinute)
        rT.reason.append(reasonAimi.toString())
    }

    /**
     * 🧠 AI Auditor Helper: Calculate cumulative SMB delivered in last 30 minutes
     * Used for intelligent audit triggering
     */
    private fun getBolusesFromTimeCached(startTime: Long, ascending: Boolean): List<BS> {
        val key = startTime to ascending
        return bolusQueryCache.getOrPut(key) {
            bolusesFromTimeCached(startTime, ascending)
        }
    }

    private fun buildRecentPkpdBolusSamples(nowMillis: Long, fallbackWindowMin: Int): List<PkpdBolusSample> {
        val diaHours = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH).coerceIn(4.0, 8.0)
        val lookbackByDiaMs = (diaHours * 60.0 * 60.0 * 1000.0).toLong()
        val lookbackByFallbackMs = fallbackWindowMin.coerceAtLeast(60) * 60_000L
        val lookbackStart = nowMillis - max(lookbackByDiaMs, lookbackByFallbackMs)
        return getBolusesFromTimeCached(lookbackStart, ascending = true)
            .asSequence()
            .filter { it.amount > 0.05 && (it.type == BS.Type.SMB || it.type == BS.Type.NORMAL) }
            .map { bolus ->
                val ageMin = ((nowMillis - bolus.timestamp).toDouble() / 60_000.0).coerceAtLeast(0.0)
                PkpdBolusSample(ageMin = ageMin, units = bolus.amount)
            }
            .toList()
    }

    private fun calculateSmbLast30Min(): Double {
        val now = dateUtil.now()
        val lookback30min = now - 30 * 60 * 1000L

        return try {
            val boluses = getBolusesFromTimeCached(lookback30min, ascending = false)
                .filter { it.type == BS.Type.SMB }

            boluses.sumOf { it.amount }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Failed to calculate SMB last 30min", e)
            0.0
        }
    }

    // Rounds value to 'digits' decimal places. Half ties follow Java Math.round (toward +∞).
    fun round(value: Double, digits: Int): Double =
        AimiTickPolicyMath.round(value, digits)

    private fun Double.withoutZeros(): String =
        app.aaps.core.data.format.NumberFormat.UP_TO_2_DECIMALS.format(this)
    fun round(value: Double): Int {
        // Crash backstop: roundToInt() throws on NaN and saturates at Int.MAX_VALUE on ±Infinity.
        // Substitute 0, but keep the fallback observable by PersistenceLayerImpl's non-finite
        // APS-result diagnostic, so laundering here does not hide the real bug.
        if (!value.isFinite()) {
            work.consoleError.add("round(): non-finite value substituted with 0 (roundNonFinite=$value)")
            return 0
        }
        val scale = 10.0.pow(2.0)
        return (round(value * scale) / scale).toInt()
    }
    private fun adjustBasalForMealHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        isMealModeActive: Boolean,
        minutesSinceMealStart: Int,
        mealMaxBasalUph: Double,
    ): Double = AimiTickPolicyMath.adjustBasalForMealHyper(
        suggestedBasalUph,
        bg,
        targetBg,
        delta,
        shortAvgDelta,
        isMealModeActive,
        minutesSinceMealStart,
        mealMaxBasalUph,
    )

    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT, overrideSafety: Boolean = false): Double =
        decideCalculateRate(basal, currentBasal, multiplier, reason, currenttemp, rT, overrideSafety)
    private fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double =
        AimiTickPolicyMath.calculateBasalRate(basal, currentBasal, multiplier)

    private fun convertBG(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")

    private fun enablesmb(
        profile: OapsProfileAimi,
        microBolusAllowed: Boolean,
        mealData: MealData,
        targetbg: Double,
        mealModeActive: Boolean,
        currentBg: Double,
        delta: Double,
        eventualBg: Double,
        combinedDelta: Double
    ): Boolean = decideEnableSmb(
        profile = profile,
        microBolusAllowed = microBolusAllowed,
        mealData = mealData,
        targetbg = targetbg,
        mealModeActive = mealModeActive,
        currentBg = currentBg,
        delta = delta,
        eventualBg = eventualBg,
        combinedDelta = combinedDelta,
        calls = object : AimiEnableSmbCalls {
            override fun writeMealModeReason(reason: String?) {
                work.mealModeSmbReason = reason
            }
            override fun logError(message: String) {
                work.consoleError.add(message)
            }
            override fun log(message: String) {
                work.consoleLog.add(message)
            }
            override fun smbDisabled() = rh.gs(ApsStrings.smb_disabled)
            override fun convertBg(value: Double) = convertBG(value)
            override fun smbDisabledHighTarget(targetBg: Double) = rh.gs(ApsStrings.smb_disabled_high_target, targetBg)
            override fun smbEnabledAlways() = rh.gs(ApsStrings.smb_enabled_always)
            override fun smbEnabledForCob(cob: Double) = rh.gs(ApsStrings.smb_enabled_for_cob, cob)
            override fun smbEnabledAfterCarbEntry() = rh.gs(ApsStrings.smb_enabled_after_carb_entry)
            override fun smbEnabledForTempTarget(bgText: String) = rh.gs(ApsStrings.smb_enabled_for_temp_target, bgText)
            override fun smbEnabledMealMode(currentBg: String, combinedDelta: Double, eventualBg: String) =
                rh.gs(ApsStrings.smb_enabled_meal_mode, currentBg, combinedDelta, eventualBg)
            override fun smbDisabledNoPref() = rh.gs(ApsStrings.smb_disabled_no_pref_or_condition)
        },
    )


    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        work.consoleError.add(msg)
    }

    private fun markFinalLoopDecisionFromRT(rT: RT, currenttemp: CurrentTemp? = null) {
        val units = rT.units ?: 0.0
        val duration = rT.duration ?: 0
        val rate = rT.rate ?: 0.0
        val smbDecisionType = if (units > 0.0) "smb" else "none"
        val basalDecisionType = when {
            duration > 0 && rate <= 0.0 -> "suspend"
            duration > 0 && currenttemp != null && rate > currenttemp.rate -> "tbr_up"
            duration > 0 && currenttemp != null && rate < currenttemp.rate -> "tbr_down"
            duration > 0 && rate > 0.0 -> "tbr_up"
            else -> "none"
        }
        recordSmbActionType(smbDecisionType)
        recordBasalActionType(basalDecisionType)
        physioAdapter.setFinalLoopDecisionType(
            when {
                smbDecisionType == "smb" -> "smb"
                basalDecisionType != "none" -> basalDecisionType
                else -> "none"
            },
        )
        assignAimiAdaptationStatus(rT)
    }

    private fun assignAimiAdaptationStatus(rT: RT) {
        val now = dateUtil.now()
        rT.aimiAdaptationStatus = AimiAdaptationStatusBuilder.build(
            AimiAdaptationStatusBuilder.BuildInput(
                now = now,
                basalGovernanceEnabled = AimiAdaptationStatusBuilder.basalGovernanceEnabled(
                    t3cBrittleModeEnabled = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode),
                    adaptiveBasalEnabled = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled),
                ),
                basalGovernance = if (::basalNeuralLearner.isInitialized) {
                    basalNeuralLearner.getGovernanceSnapshot()
                } else {
                    null
                },
                unifiedReactivityEnabled = preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled),
                unifiedReactivity = if (::unifiedReactivityLearner.isInitialized) {
                    unifiedReactivityLearner.statusSnapshot()
                } else {
                    null
                },
                // BasalLearner.process runs on every AIMI tick; it has no independent feature switch.
                basalLearnerEnabled = true,
                basalLearner = if (::basalLearner.isInitialized) basalLearner.statusSnapshot() else null,
                pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
                pkpd = pkpdIntegration.learningStatusSnapshot(),
                onlineLearnerEnabled = preferences.get(BooleanKey.OApsAIMIautoDriveActive) ||
                    preferences.get(BooleanKey.OApsAIMIT3cBrittleMode),
                onlineLearner = autodriveEngine.onlineLearnerStatus(),
                ngrEnabled = preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled) == true,
                ngr = nightGrowthResistanceMode.latestResult(),
                wCycleEnabled = wCyclePreferences.enabled(),
                wCycle = work.wCycleInfoForRun,
                peakGovernorEnabled = preferences.get(BooleanKey.OApsAIMIPeakGovernorEnabled),
                diaGovernorEnabled = preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled),
            )
        )
    }

    private fun recordBasalActionType(decisionType: String) {
        physioAdapter.setBasalActionType(decisionType)
        val currentFinal = physioAdapter.getLastDecisionTrace()?.finalLoopDecisionType
        if (decisionType != "none" || currentFinal.isNullOrBlank() || currentFinal == "pending") {
            physioAdapter.setFinalLoopDecisionType(decisionType)
        }
    }

    private val legacyEffectSink = object : AimiEffectSink {
        override fun setTempBasal(
            rate: Double,
            durationMin: Int,
            profile: OapsProfileAimi,
            rT: RT,
            currenttemp: CurrentTemp,
            overrideSafetyLimits: Boolean,
            forceExact: Boolean,
            adaptiveMultiplier: Double,
            mealContext: MealSafetyContext?,
        ): RT = this@DetermineBasalaimiSMB2.setTempBasal(
            rate,
            durationMin,
            profile,
            rT,
            currenttemp,
            overrideSafetyLimits = overrideSafetyLimits,
            forceExact = forceExact,
            adaptiveMultiplier = adaptiveMultiplier,
            mealContext = mealContext,
        )

        override fun applySmbUnits(rT: RT, requestedU: Double, owner: String) {
            this@DetermineBasalaimiSMB2.applySmbUnits(rT, requestedU, owner)
        }
    }

    private val legacySmbAction = object : AimiSmbActionType {
        override fun setSmbActionType(decisionType: String) {
            physioAdapter.setSmbActionType(decisionType)
        }

        override fun finalLoopDecisionType(): String? =
            physioAdapter.getLastDecisionTrace()?.finalLoopDecisionType

        override fun setFinalLoopDecisionType(decisionType: String) {
            physioAdapter.setFinalLoopDecisionType(decisionType)
        }
    }

    private val legacyLatestSmb = AimiLatestSmbCached { latestSmbCached()?.timestamp }

    private fun recordSmbActionType(decisionType: String) {
        recordSmbActionTypeOn(legacySmbAction, decisionType)
    }

    fun setTempBasal(
        _rate: Double,
        duration: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean = false,
        forceExact: Boolean = false,
        adaptiveMultiplier: Double = 1.0,
        allowPartialSafetyTbr: Boolean = false,
        mealContext: MealSafetyContext? = null,
    ): RT {
        if (AimiEffectProbe.captureSetTbr(
                rateUph = _rate,
                durationMin = duration,
                overrideSafetyLimits = overrideSafetyLimits,
                forceExact = forceExact,
                adaptiveMultiplier = adaptiveMultiplier,
            )
        ) {
            return rT
        }
        // Upstream parity (`DetermineBasalSMB.setTempBasal`, non-finite hardening merged 2026-08-08):
        // a non-finite rate slips through every clamp below, because every comparison with NaN is
        // false, and then lands in rT.rate, which DetermineBasalResult turns into a real pump command.
        // Do not invent a dose out of a broken value: fall back to the profile basal. Zero would be
        // worse, because that actively withholds basal for 30 minutes. The interpolated value leaves a
        // literal `setTempBasalRate=NaN` token in consoleError, which is what the
        // `PersistenceLayerImpl` non-finite tripwire scans for.
        val requestedRate = if (_rate.isFinite()) _rate else {
            work.consoleError.add("setTempBasal: setTempBasalRate=$_rate is not finite, using profile basal instead")
            profile.current_basal
        }
        val lgsPref = profile.lgsThreshold
        val hypoGuard = HypoThresholdMath.computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = lgsPref)
        val floor = PredictiveHypoEvaluator.floor(hypoGuard)
        val effectiveMealContext = mealContext ?: MealSafetyContext(
            mealModeActive = work.mealTime || lunchTime || dinnerTime || work.snackTime || highCarbTime || bfastTime,
            manualBolusAgeMin = internalLastSmbMillis.takeIf { it > 0L }?.let { (dateUtil.now() - it) / 60000.0 },
            inferredMealSignal = inferredMealSafetyIntent(),
        )

        if (forceExact) {
            val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
            val isMealMode = therapy.snackTime || therapy.highCarbTime || therapy.mealTime
                || therapy.lunchTime || therapy.dinnerTime || therapy.bfastTime
            when {
                bg <= floor -> {
                    rT.reason.append(rh.gs(ApsStrings.lgs_triggered, aimiFmt0(bg), aimiFmt0(hypoGuard)))
                    rT.duration = maxOf(duration, 30)
                    rT.rate = 0.0
                    recordBasalActionType("suspend")
                    return rT
                }
                isMealMode && bg > hypoGuard -> {
                    val rate = requestedRate.coerceAtLeast(0.0)
                    rT.reason.append(
                        rh.gs(
                            ApsStrings.manual_basal_override,
                            rate,
                            duration,
                            "✔",
                        ),
                    )
                    rT.duration = duration
                    rT.rate = rate
                    val decisionType = when {
                        rate == 0.0 -> "suspend"
                        rate > currenttemp.rate -> "tbr_up"
                        rate < currenttemp.rate -> "tbr_down"
                        else -> "none"
                    }
                    recordBasalActionType(decisionType)
                    return rT
                }
            }
        }

        if (!(allowPartialSafetyTbr && requestedRate > 0.0)) {
            val (hypoPredForLgs, hypoEventualForLgs) = sanitizedHypoGuardPredictedEventual(
                rT = rT,
                predictedBg = predictedBg.toDouble(),
                eventualBg = work.eventualBG,
            )
            val (minPredCurve, ignoreMinPredCurve) = resolveLgsMinPredictedCurve(rT)
            val lgsReason = HypoLgsBlockReason.detect(
                bgNow = bg,
                predicted = hypoPredForLgs,
                eventual = hypoEventualForLgs,
                minPredictedCurve = minPredCurve,
                hypo = hypoGuard,
                delta = delta.toDouble(),
                mealContext = effectiveMealContext,
                ignoreMinPredictedCurve = ignoreMinPredCurve,
            )
            if (lgsReason != null) {
                val lgsLine = when (lgsReason) {
                    HypoLgsBlockReason.BG_NOW ->
                        rh.gs(ApsStrings.lgs_triggered, aimiFmt0(bg), aimiFmt0(hypoGuard))
                    HypoLgsBlockReason.PREDICTED_MIN_CURVE ->
                        rh.gs(
                            ApsStrings.lgs_triggered_min_pred,
                            aimiFmt0(minPredCurve ?: hypoPredForLgs),
                            aimiFmt0(hypoGuard),
                        )
                    HypoLgsBlockReason.PREDICTED_AND_EVENTUAL, HypoLgsBlockReason.FAST_FALL ->
                        rh.gs(
                            ApsStrings.lgs_triggered_predicted,
                            aimiFmt0(hypoPredForLgs),
                            aimiFmt0(hypoEventualForLgs),
                            aimiFmt0(hypoGuard),
                        )
                }
                rT.reason.append(lgsLine)
                rT.duration = maxOf(duration, 30)
                rT.rate = 0.0
                recordBasalActionType("suspend")
                return rT
            }
        }
        val isLgsEnabled = profile.lgsThreshold != null && profile.lgsThreshold!! > 0

        val bgNow = bg

        // 1) Mode manuel : on pose exactement la valeur demandée (toujours bornée ≥ 0)
        if (forceExact) {
            val rate = requestedRate.coerceAtLeast(0.0)
            rT.reason.append(
                rh.gs(
                    ApsStrings.manual_basal_override,
                    rate,
                    duration,
                    if (Therapy(persistenceLayer).let { it.updateStatesBasedOnTherapyEvents();
                            it.snackTime || it.highCarbTime || it.mealTime || it.lunchTime || it.dinnerTime || it.bfastTime
                        }) "✔" else "✘"
                )
            )
            rT.duration = duration
            rT.rate = rate
            val decisionType = when {
                rate == 0.0 -> "suspend"
                rate > currenttemp.rate -> "tbr_up"
                rate < currenttemp.rate -> "tbr_down"
                else -> "none"
            }
            recordBasalActionType(decisionType)
            return rT
        }

        // 2) Contexte
        lastProfile = profile
        val therapy = Therapy(persistenceLayer).also { it.updateStatesBasedOnTherapyEvents() }
        val isMealMode = therapy.snackTime || therapy.highCarbTime || therapy.mealTime
            || therapy.lunchTime || therapy.dinnerTime || therapy.bfastTime

        val hour = aimiLocalHour()
        val night = hour <= 7 // (OK tel quel, utilisé pour l’autodrive)
        val predDelta = predictedDelta(getRecentDeltas()).toFloat()
        val isAutodriveV3Local = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
        val isEarlyAutodrive = !night && !isMealMode && isAutodriveV3Local &&
            bgNow > hypoGuard && bgNow > 110 && detectMealOnset(delta, predDelta, work.bgacc.toFloat(), predictedBg, profile.target_bg.toFloat())

        // 3) Tendance & ajustement dynamique
        val bgTrend = calculateBgTrend(getRecentBGs(), StringBuilder())

        // Use the new progressive Sigmoid/PD controller instead of the old fixed 1.2x limit
        val dynamicState = dynamicBasalController.calculateDynamicRate(
            currentRate = requestedRate,
            bg = bgNow,
            targetBg = profile.target_bg.toDouble(),
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            projectionHorizonMin = DynamicBasalController.PROJECTION_HORIZON_MIN
                .takeIf { preferences.get(BooleanKey.OApsAIMIBasalProjectedError) },
        )
        var rateAdjustment = dynamicState.finalRate.coerceAtLeast(0.0)

        // Log the math for debugging and transparency
        work.consoleLog.add(
            "DYNAMIC_BASAL P-Err=${aimiFmt1(dynamicState.errorP)} " +
            "D-Err=${aimiFmt1(dynamicState.errorD)} " +
            "Total=${aimiFmt2(dynamicState.totalError)} " +
            "Mult=${aimiFmt2(dynamicState.sigmoidMultiplier)}x " +
            "Brake=${dynamicState.isBraking}"
        )

        // 🚀 PKPD TBR Boost: Augmenter TBR si preferTbr (sauf modes repas)
        // Note: pkpdPreferTbrBoost est déjà à 1.0 pour les modes repas (via reset dans finalizeAndCapSMB)
        if (work.pkpdPreferTbrBoost > 1.0 && !isMealMode) {
            val originalRate = rateAdjustment
            rateAdjustment = (rateAdjustment * work.pkpdPreferTbrBoost).coerceAtLeast(0.0)
            work.consoleLog.add("PKPD_TBR_BOOST original=${aimiFmt2(originalRate)} boost=${aimiFmt2(work.pkpdPreferTbrBoost)} → ${aimiFmt2(rateAdjustment)}U/h")
        }

        // 4) Limites de sécurité
        val maxSafe = min(
            profile.max_basal,
            min(
                profile.max_daily_safety_multiplier * profile.max_daily_basal,
                profile.current_basal_safety_multiplier * profile.current_basal
            )
        )

        // 5) Application des limites
        val bypassSafety = (overrideSafetyLimits || isMealMode || isEarlyAutodrive) && bgNow > hypoGuard

        // même en bypass, on ne dépasse JAMAIS max_basal (hard cap)
        var rate = when {
            bgNow <= hypoGuard -> 0.0
            // [BASAL FLOOR] Rising & > 85 mg/dL -> Maintain floor (ML-Aware) instead of 0.0
            // Only if prediction would otherwise set it to 0.0 (e.g. safety logic)
            rateAdjustment == 0.0 && bgNow > 85.0 &&
            (delta > (if (adaptiveMultiplier > 1.1) -0.5 else 1.0)) &&
            !isMealMode && !isLgsEnabled -> {
                 val baseFloor = profile.current_basal * 0.5
                 val adaptiveFloor = baseFloor * adaptiveMultiplier
                 rT.reason.append(" [BASAL_FLOOR: ${aimiFmt2(adaptiveFloor)}U/h (ML:${aimiFmt2(adaptiveMultiplier)}x)] ")
                 adaptiveFloor
            }
            bypassSafety       -> rateAdjustment.coerceIn(0.0, profile.max_basal)
            else               -> rateAdjustment.coerceIn(0.0, maxSafe)
        }

        // Apply final Universal Adaptive Multiplier (only if > hypoGuard and not 0.0)
        if (rate > 0.0 && abs(adaptiveMultiplier - 1.0) > 0.01) {
            val originalBeforeScaling = rate
            // Shared IOB-budget brake on the single basal apply-point: the boost ABOVE 1.0x fades linearly
            // as total IOB fills the physiological budget. Because every basal/TBR path routes through here,
            // one brake damps every source (manual bolus + forced TBR + adaptive basal) against one budget.
            // Strictly conservative: only applied to a boost (>1.0x), never raises it, never adds insulin
            // (already inside rate>0 and downstream of the bgNow<=hypoGuard → 0.0 cut). Linear headroom is
            // intentionally chosen over a softened pow() for a tighter brake on a stacking-prone profile.
            val effectiveMultiplier =
                if (adaptiveMultiplier > 1.0) {
                    val budgetU = InsulinLoadGovernor.physiologicalBudgetU(
                        tdd24hU = resolveTdd24hForExport() ?: 0.0,
                        patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                    )
                    val iobNow = work.iobNet
                    val braked = InsulinLoadGovernor.iobBudgetBrakedMultiplier(adaptiveMultiplier, iobNow, budgetU)
                    rT.reason.append(
                        " | 🧬AdaptiveBasal IOB-budget ${aimiFmt1(iobNow)}/${aimiFmt1(budgetU)}U: ${aimiFmt2(adaptiveMultiplier)}x→${aimiFmt2(braked)}x"
                    )
                    braked
                } else {
                    adaptiveMultiplier
                }
            rate = (rate * effectiveMultiplier).coerceAtMost(if (bypassSafety) profile.max_basal else maxSafe)
            rT.reason.append(" | 🧬AdaptiveBasal: ${aimiFmt2(effectiveMultiplier)}x (${aimiFmt2(originalBeforeScaling)}->${aimiFmt2(rate)}U/h)")
        }

        // 6) Endocrine governor (production) — skip if Harmonia basal-first already embedded amp in rate
        val wCycleInfo = ensureWCycleInfo()
        if (wCycleInfo != null) {
            appendWCycleReason(rT.reason, wCycleInfo)
        }
        val harmoniaOwnedBasal = work.lastHarmoniaProductionDecision?.selectedForProduction == true
        if (bgNow > hypoGuard && !harmoniaOwnedBasal) {
            val endocrineBasalAmp = EndocrineAmplitudeGovernor.productionAmp(
                causalState.lastWCycleBelief,
                EndocrineAmpAxis.BASAL,
            )
            if (endocrineBasalAmp != 1.0) {
                val pre = rate
                val scaled = rate * endocrineBasalAmp
                val limit = if (bypassSafety) profile.max_basal else maxSafe
                rate = scaled.coerceIn(0.0, limit)
                val need = if (pre > 0.0) rate / pre else null
                updateWCycleLearner(need, null)
                val profileForLog = lastProfile
                if (profileForLog != null) {
                    wCycleFacade.infoAndLog(
                        mapOf(
                            "trackingMode" to wCyclePreferences.trackingMode().name,
                            "contraceptive" to wCyclePreferences.contraceptive().name,
                            "thyroid" to wCyclePreferences.thyroid().name,
                            "verneuil" to wCyclePreferences.verneuil().name,
                            "bg" to bg,
                            "delta5" to delta.toDouble(),
                            "work.iob" to work.iob.toDouble(),
                            "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                            "isfProfile" to profileForLog.sens,
                            "dynIsf" to variableSensitivity.toDouble(),
                            "needBasalScale" to need,
                            "endocrineBasalAmp" to endocrineBasalAmp,
                        )
                    )
                }
            }
            rate = if (bypassSafety) rate.coerceAtMost(profile.max_basal) else rate.coerceAtMost(maxSafe)
        } else if (harmoniaOwnedBasal && causalState.lastWCycleBelief != null) {
            causalState.lastWCycleBelief = causalState.lastWCycleBelief?.copy(
                dosePathOwner = EndocrineDosePathOwner.HARMONIA_PRODUCTION_BASAL_FIRST,
            )
        }

        // 🔒 Lot 2 — invariants terminaux : dernier point où le taux peut encore être borné. Tout ce qui
        // précède (DynamicBasalController ×[0..10], AdaptiveBasal, ampli endocrine) a déjà été appliqué,
        // donc un plafond posé ici ne peut plus être écrasé. Réduction seule.
        val terminal = BasalTerminalInvariants.resolve(
            BasalTerminalInvariants.Input(
                enabled = preferences.get(BooleanKey.OApsAIMIBasalTerminalInvariants),
                rateUph = rate,
                profileBasalUph = profile.current_basal,
                bgMgdl = bgNow,
                targetBgMgdl = targetBg.toDouble(),
                eventualBgMgdl = work.eventualBG.takeIf { it.isFinite() && it > 1.0 },
                deltaMgdl5m = delta.toDouble(),
                iobU = work.iobNet,
                // A manually declared mode exempts these invariants — that is this module's own
                // stated contract ("Modes repas exclus"), and FCL is a manually declared mode.
                // Leaving it out pinned the FCL basal to the profile rate: the floor raised it to the
                // meal ceiling and postHypoCap pulled it straight back to profile.current_basal a few
                // lines later. Reported from the field 2026-09-17.
                // `isMealMode` itself is deliberately NOT widened: it also builds MealSafetyContext,
                // which loosens an LGS guard, and FCL must not buy a weaker hypo interlock.
                mealModeActive = isMealMode || fclDeclaredThisTick(profile),
                postHypoActive = work.lastPostHypoDeliveryAuthority.active,
            )
        )
        lastBasalTerminalTelemetry = JsonObj().apply {
            put("enabled", preferences.get(BooleanKey.OApsAIMIBasalTerminalInvariants))
            put("rate_in_uph", rate)
            put("rate_out_uph", terminal.rateUph)
            put("bound_by", terminal.boundBy ?: AimiJson.NULL)
            put("trace", terminal.trace)
            put("profile_basal_uph", profile.current_basal)
            put("bg_mgdl", bgNow)
            put("target_bg_mgdl", targetBg.toDouble())
            put("eventual_bg_mgdl", work.eventualBG.takeIf { it.isFinite() && it > 1.0 } ?: AimiJson.NULL)
            put("delta_mgdl_5m", delta.toDouble())
            put("iob_u", work.iobNet)
            put("meal_mode_active", isMealMode)
            put("post_hypo_active", work.lastPostHypoDeliveryAuthority.active)
        }.build()
        if (terminal.boundBy != null) {
            work.consoleLog.add("🔒 BASAL_TERMINAL[${terminal.boundBy}] ${terminal.trace}")
            rT.reason.append(" [BASAL_TERMINAL:${terminal.boundBy} ${aimiFmt2(rate)}→${aimiFmt2(terminal.rateUph)}U/h]")
        }
        rate = terminal.rateUph

        rT.reason.append(rh.gs(ApsStrings.temp_basal_pose, aimiFmt2(rate), duration))
        rT.duration = duration
        rT.rate = rate
        val decisionType = when {
            rate == 0.0 -> "suspend"
            rate > currenttemp.rate -> "tbr_up"
            rate < currenttemp.rate -> "tbr_down"
            else -> "none"
        }
        recordBasalActionType(decisionType)
        return rT
    }




    private fun calculateBgTrend(recentBGs: List<Float>, reason: StringBuilder): Float {
        if (recentBGs.isEmpty()) {
            reason.append(rh.gs(ApsStrings.no_bg_history))
            return 0.0f
        }

        val sortedBGs = recentBGs.reversed()
        val firstValue = sortedBGs.first()
        val lastValue = sortedBGs.last()
        val count = sortedBGs.size

        val bgTrend = (lastValue - firstValue) / count.toFloat()

        reason.append(rh.gs(ApsStrings.bg_trend_analysis))
        reason.append(rh.gs(ApsStrings.first_bg_value, firstValue))
        reason.append(rh.gs(ApsStrings.last_bg_value, lastValue))
        reason.append(rh.gs(ApsStrings.number_of_values, count))
        reason.append(rh.gs(ApsStrings.calculated_trend, bgTrend))
        return bgTrend
    }


    private fun logDataMLToCsv(predictedSMB: Float, smbToGive: Float) {
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())
        val latentFeatures = SmbRefinementFeatureSchema.latentFeatureValues(causalState.lastPhysioLatentState)
        val modeFeatures = SmbRefinementFeatureSchema.modeFeatureValues(work.lastPatientModeDecision)
        val causalFeatures = SmbRefinementFeatureSchema.causalFeatureValues(work.lastPatientState?.causalPosterior)
        val behaviorProfile = behaviorProfileSource.read(preferences)
        val eventMemory = work.lastPatientState?.eventMemory ?: PatientEventMemory.EMPTY
        val decisionConflictFlags = physioAdapter.getLastDecisionTrace()?.decisionConflictFlags?.joinToString("|").orEmpty()

        // One source of truth for the column order: the writer and the trainer read the same list.
        // Building the header here by hand is what let the file on disk drift away from the rows.
        val headerRow = SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n"
        val valuesToRecord = "$dateStr," +
            "$bg,${work.iob},${work.cob},$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "${latentFeatures[0]},${latentFeatures[1]},${latentFeatures[2]},${latentFeatures[3]}," +
            "${modeFeatures[0]},${modeFeatures[1]},${modeFeatures[2]}," +
            "${causalFeatures[0]},${causalFeatures[1]},${causalFeatures[2]}," +
            "${behaviorProfile.protectionLevel},${behaviorProfile.mealCaptureLevel},${behaviorProfile.stabilityLevel}," +
            "${behaviorProfile.physioLevel},${behaviorProfile.autonomyLevel}," +
            "${eventMemory.postHyperExhaustionScore},${eventMemory.correctionFragilityScore},$decisionConflictFlags," +
            "$predictedSMB,$smbToGive," +
            "${work.peakintermediaire},${work.latestAdjustedDia}"
        // The row is queued, not written. Its origin fields are only complete at the end of the
        // tick, and its realised glucose only about half an hour later, so it leaves the queue once
        // its outcome window has closed. This delays when a row appears in the CSV; it does not
        // change the row itself, the label, or anything the pump is asked to do.
        val nowMs = smbTrainingRowTickKey.takeIf { it > 0L } ?: dateUtil.now()
        smbTrainingRowBuffer.fillRealisedOutcomes(nowMs = nowMs, observedBg = bg)
        smbTrainingRowBuffer.enqueue(timestampMs = nowMs, valuesPrefix = valuesToRecord)
        smbTrainingRowBuffer.drainWritableRows(nowMs).forEach { readyRow ->
            trainingCsvWriter.appendRowWithFallback(
                primary = csvfilePath,
                fallbackFileName = "oapsaimiML2_records.csv",
                headerRow = headerRow,
                valuesRow = readyRow,
            )
        }
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val dateStr = dateUtil.dateAndTimeString(dateUtil.now())

        val headerRow = "dateStr,work.hourOfDay,work.weekend," +
            "bg,targetBg,work.iob,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "work.tags0to60minAgo,work.tags60to120minAgo,work.tags120to180minAgo,work.tags180to240minAgo," +
            "predictedSMB,work.maxIob,work.maxSMB,smbGiven,dynamicPeak,adjustedDia\n"
        val valuesToRecord = "$dateStr,${work.hourOfDay},${work.weekend}," +
            "$bg,$targetBg,${work.iob},$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "${work.tags0to60minAgo},${work.tags60to120minAgo},${work.tags120to180minAgo},${work.tags180to240minAgo}," +
            "$predictedSMB,${work.maxIob},${work.maxSMB},$smbToGive,${work.peakintermediaire},${work.latestAdjustedDia}"
        trainingCsvWriter.appendRowWithFallback(
            primary = csvfile2Path,
            fallbackFileName = "oapsaimi2_records.csv",
            headerRow = headerRow,
            valuesRow = valuesToRecord,
        )
    }

    /**
     * The name of the copy of the training CSV, taken before the file is rewritten.
     *
     * `backup_yyyyMMdd_HHmmss.csv`, which is the name `AimiRetentionPolicy.DROP_GLOBS` already looks
     * for when it tidies old backups up. The caller puts it in the AIMI directory with
     * [AimiStorage.file]; before the storage port it was placed next to the CSV itself, which is the
     * same place, because both pruned files are named with [AimiStorage.file] as well.
     *
     * `Locale.getDefault()` rather than `Locale.US` is kept from the reference even though it means a
     * non Gregorian calendar writes a non Gregorian year into the name - a Thai locale writes 2569 for
     * 2026. It is the reference's own behaviour and changing it is the owner's call, not the port's.
     */
    private fun backupFileName(): String {
        // KMP: "yyyyMMdd_HHmmss" via kotlinx.datetime. Gregorian year always; the reference's
        // `Locale.getDefault()` could write a Buddhist year (2569) on a Thai-locale device.
        // That edge case is not reproduced here (see KDoc above); the digit layout is identical.
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val timestamp = buildString {
            append(now.year)
            append(now.monthNumber.toString().padStart(2, '0'))
            append(now.dayOfMonth.toString().padStart(2, '0'))
            append('_')
            append(now.hour.toString().padStart(2, '0'))
            append(now.minute.toString().padStart(2, '0'))
            append(now.second.toString().padStart(2, '0'))
        }
        return "backup_$timestamp.csv"
    }

    /**
     * Removes the newest 200 lines of the training CSV, after copying the whole file aside.
     *
     * This is the count based clean up, kept for the therapy note trigger, which asks for a deletion
     * without naming a day. The rule itself lives in [AimiCorpusPruner.keepAllButNewest] so it can be
     * tested; the "bad day" trigger uses [removeRowsForDay] instead, which removes the rows of the
     * day the user is told about.
     */
    fun removeLast200Lines(csvFile: AimiPath) {
        val reasonBuilder = StringBuilder()
        if (!storage.exists(csvFile)) {
            aapsLogger.info(LTag.APS, rh.gs(ApsStrings.original_file_missing))
            return
        }

        val backupName = backupFileName()
        val result = AimiCorpusPruner.removeNewest(
            storage = storage,
            csv = csvFile,
            backup = storage.file(backupName),
            count = 200,
        )
        when (result.outcome) {
            AimiCorpusPruner.Outcome.REMOVED         ->
                reasonBuilder.append(rh.gs(ApsStrings.last_200_deleted, backupName))

            AimiCorpusPruner.Outcome.NOTHING_REMOVED ->
                reasonBuilder.append(rh.gs(ApsStrings.file_too_short))

            else                                     ->
                aapsLogger.warn(LTag.APS, "AIMI training CSV not cleaned up: ${result.outcome}")
        }
    }

    /**
     * Removes the rows of one day from the training CSV, keeping every other row and the header.
     *
     * The day is passed as text, not as a date, because column 0 of a row is written with
     * `DateUtil.dateAndTimeString`, which is the short date in the phone's own language and time
     * zone. The caller builds the same text with the same function, so the two can only match when
     * they mean the same day. A row whose date cannot be read, or that was written while the phone
     * was set to another language, does not match and is kept.
     */
    private fun removeRowsForDay(csvFile: AimiPath, dayText: String): AimiCorpusPruner.Result {
        if (!storage.exists(csvFile)) {
            aapsLogger.info(LTag.APS, rh.gs(ApsStrings.original_file_missing))
            return AimiCorpusPruner.Result(
                outcome = AimiCorpusPruner.Outcome.FILE_MISSING,
                removedRows = 0,
                keptRows = 0,
            )
        }
        val result = AimiCorpusPruner.removeDay(
            storage = storage,
            csv = csvFile,
            backup = storage.file(backupFileName()),
            dayText = dayText,
        )
        aapsLogger.info(
            LTag.APS,
            "AIMI training CSV clean up for $dayText: ${result.outcome}, " +
                "removed ${result.removedRows} rows, kept ${result.keptRows}",
        )
        return result
    }

    private fun automateDeletionIfBadDay(tir1DAYIR: Int) {
        val reasonBuilder = StringBuilder()
        // Only when the time in range of the last day is under 85 %.
        if (tir1DAYIR < 85) {
            // Only between 00:05 and 00:10, local time.
            val deletionNowMs = aimiWallClockMs()
            if (aimiStrictlyInsideLocalWindow(deletionNowMs, 0, 5, 0, 10)) {
                // The day that has just ended, in the phone's own time zone, which is both the zone
                // the window above is read in and the zone the rows were dated in. Midday is used
                // rather than midnight because in a few time zones a day starts without a midnight
                // when the clocks change.
                val yesterdayMiddayMs = aimiYesterdayMiddayEpochMs(deletionNowMs)
                // The very text the row writer put in column 0 for that day.
                val dateToRemove = dateUtil.dateString(yesterdayMiddayMs)

                val result = removeRowsForDay(csvfilePath, dateToRemove)
                if (result.outcome == AimiCorpusPruner.Outcome.REMOVED) {
                    reasonBuilder.append(rh.gs(ApsStrings.reason_data_removed, dateToRemove))
                }
            } else {
                reasonBuilder.append(rh.gs(ApsStrings.reason_deletion_time_restricted))
            }
        }
    }

    private fun finalizeAndCapSMB(
        rT: RT,
        proposedUnits: Double,
        reasonHeader: String,
        mealData: MealData,
        hypoThreshold: Double,
        isExplicitUserAction: Boolean = false,
        decisionSource: String = "AIMI",
        isMealActive: Boolean = false,
        hyperReleaseFloorU: Double = 0.0,
        bypassSmbRefractory: Boolean = false,
    ) {
        val side = decideFinalizeAndCapSmb(
            rT = rT,
            proposedUnits = proposedUnits,
            reasonHeader = reasonHeader,
            mealData = mealData,
            hypoThreshold = hypoThreshold,
            isExplicitUserAction = isExplicitUserAction,
            decisionSource = decisionSource,
            isMealActive = isMealActive,
            hyperReleaseFloorU = hyperReleaseFloorU,
            bypassSmbRefractory = bypassSmbRefractory,
            postHypo = work.lastPostHypoDeliveryAuthority,
            uamHypotheses = causalState.lastUamHypothesisState,
            mealAbsorption = work.lastMealAbsorptionOutput,
            rbtHints = work.lastRbtAppliedHints,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            targetBg = targetBg,
            iob = work.iob,
            maxIob = work.maxIob,
            maxSmb = work.maxSMB,
            maxSmbHb = work.maxSMBHB,
            memberEventualBg = work.eventualBG,
            harmoniaDecision = work.lastHarmoniaDecision,
            harmonizerOutcome = work.lastHarmonizerOutcome,
            pkpdRuntime = cachedPkpdRuntime,
            sportTime = work.sportTime,
            lateFatRise = causalState.lateFatRiseFlag,
            thyroidEffects = work.currentThyroidEffects,
            predictionAvailable = work.lastPredictionAvailable,
            predictionSize = work.lastPredictionSize,
            lastBolusAgeMinutes = work.lastBolusAgeMinutes,
            iobActivityNow = work.iobActivityNow,
            cob = work.cob,
            effort = work.lastEffortAssessment,
            mealCertainty = work.lastMealCertainty,
            physiologicalPhase = work.lastPhysiologicalPhaseOutput,
            slowCarbBudgetU = slowCarbEarlyBudgetU,
            slowCarbWindowMs = causalState.slowCarbBudgetWindowMs,
            slowCarbDeliveredU = causalState.slowCarbBudgetDeliveredU,
            ceilingRepeatCount = ceilingRepeatCount,
            ceilingRepeatLastMs = ceilingRepeatLastMs,
            preferences = preferences,
            consoleLog = work.consoleLog,
            uamConfidence = AimiUamConfidence { AimiUamHandler.confidenceOrZero() },
            mealCorrection = AimiFinalizeMealCorrection { meal, bgMgdl, deltaMgdl, shortAvg ->
                resolveMealCorrectionContext(meal, bgMgdl, deltaMgdl, shortAvg)
            },
            doseBg = object : AimiFinalizeDoseBg {
                override fun eventual(fallback: Double): Double = authoritativeEventualBg(fallback)
                override fun minPred(rT: RT, rawMinPred: Double?): Double? = authoritativeMinPredBg(rT, rawMinPred)
            },
            safety = AimiFinalizeSafety { meal, smb, hypo, reason, runtime, exercise, lateFat, ignore ->
                applySafetyPrecautions(
                    mealData = meal,
                    smbToGiveParam = smb,
                    hypoThreshold = hypo,
                    reason = reason,
                    pkpdRuntime = runtime,
                    exerciseFlag = exercise,
                    suspectedLateFatMeal = lateFat,
                    ignoreSafetyConditions = ignore,
                )
            },
            smbInterval = AimiFinalizeSmbInterval { calculateSMBInterval() },
            throttleInputs = object : AimiFinalizeThrottle {
                override fun diaHoursOrNull(): Double? = work.tickEffectiveDiaHours
                override fun profileDiaOrNull(): Double? = lastProfile?.dia
                override fun actionStateOrNull(): InsulinActionState? = work.tickInsulinActionState
                override fun updateAction(
                    currentBg: Double,
                    bgDelta: Double,
                    iobTotal: Double,
                    iobActivityNow: Double,
                    iobActivityIn30: Double,
                    minutesToPeak: Int,
                    diaHours: Double,
                    carbsActiveG: Double,
                    now: Long,
                ): InsulinActionState = insulinObserver.update(
                    currentBg = currentBg,
                    bgDelta = bgDelta,
                    iobTotal = iobTotal,
                    iobActivityNow = work.iobActivityNow,
                    iobActivityIn30 = iobActivityIn30,
                    minutesToPeak = minutesToPeak,
                    diaHours = diaHours,
                    carbsActiveG = carbsActiveG,
                    now = now,
                )
            },
            tdd = AimiFinalizeTdd { fallback -> resolveTdd24hForLoop(fallback) },
            mealFlags = object : AimiFinalizeMealFlags {
                override fun mealModeCondition(): Boolean = isMealModeCondition()
                override fun anyManualMeal(): Boolean =
                    work.mealTime || bfastTime || lunchTime || dinnerTime || work.snackTime || highCarbTime
            },
            rbtMinPred = AimiFinalizeRbtMinPred { raw -> minPredictedBgForRbtWiring(raw) },
            criticalFlag = AimiFinalizeCriticalFlag { work.criticalSafetyZeroedThisTick },
            contextSmb = object : AimiFinalizeContextSmb {
                override fun suppress(): Boolean = work.lastContextSuppressSmb
                override fun ceilingU(): Double? = work.lastContextSmbCeilingU
            },
            slowCarb = AimiFinalizeSlowCarb {
                work.lastContextSnapshot?.activeIntents
                    ?.filterIsInstance<app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.SlowCarbMeal>()
                    ?.maxByOrNull { it.intensity }
                    ?.takeIf { (dateUtil.now() - it.startTimeMs) < it.absorptionDelay.inWholeMilliseconds }
                    ?.startTimeMs
            },
            thyroidGate = AimiFinalizeThyroid {
                val inputs = thyroidPreferences.inputsFlow.value
                val gatedEffects = thyroidSafetyGates.applyGates(
                    inputs = inputs,
                    effects = work.currentThyroidEffects,
                    currentBg = bg,
                    bgDelta = delta.toDouble(),
                    currentIob = work.iob.toDouble(),
                )
                app.aaps.plugins.aps.openAPSAIMI.safety.AimiSmbFinalizeMath.ThyroidGate(
                    block = gatedEffects.blockSmb,
                    capUnits = gatedEffects.smbCapUnits?.toFloat(),
                )
            },
            phrases = object : AimiFinalizePhrases {
                override fun surveillanceApplied(): String = rh.gs(ApsStrings.aimi_iob_surveillance_applied)
                override fun limitsSmb(proposed: Float, allowed: Float): String =
                    rh.gs(ApsStrings.limits_smb, proposed, allowed)
            },
            clock = AimiFinalizeClock { dateUtil.now() },
            latch = AimiFinalizeLatch { nowMs -> internalLastSmbMillis = nowMs },
            seal = AimiFinalizeSeal { sealSmbTerminal() },
            riseExport = AimiFinalizeRiseExport { block, reason, repeats, withheldU ->
                work.pendingDecisionCtxForExport?.baseline_state?.let { baseline ->
                    baseline.rise_ceiling_guard_would_block = block
                    baseline.rise_ceiling_guard_reason = reason
                    baseline.rise_ceiling_guard_repeats = repeats
                    if (block) baseline.rise_ceiling_guard_withheld_u = withheldU
                }
            },
            bindingDraft = AimiFinalizeBindingDraft { work.lastSmbBindingTraceDraft },
            smbAction = legacySmbAction,
            format2f = { app.aaps.core.data.format.NumberFormat.withDecimalsHalfUp(6).format(it.toDouble(), '.') },
        ) ?: return
        work.lastDecisionSource = side.decisionSource
        work.lastSmbProposed = side.smbProposed
        work.pkpdThrottleIntervalAdd = side.pkpdThrottleIntervalAdd
        work.pkpdPreferTbrBoost = side.pkpdPreferTbrBoost
        causalState.slowCarbBudgetWindowMs = side.slowCarbWindowMs
        causalState.slowCarbBudgetDeliveredU = side.slowCarbDeliveredU
        ceilingRepeatCount = side.ceilingRepeatCount
        ceilingRepeatLastMs = side.ceilingRepeatLastMs
        work.lastEffortSmbFactorRaw = side.effortFactorRaw
        work.lastEffortSmbFactorApplied = side.effortFactorApplied
        work.lastEffortSmbBeforeU = side.effortBeforeU
        work.lastEffortSmbAfterU = side.effortAfterU
        work.lastSmbBindingTraceDraft = side.bindingTrace
        work.lastSmbCapped = side.smbCapped
        work.lastSmbFinal = side.smbFinal
        lastIobSurveillanceExport = side.iobSurveillance
    }

    private data class PkpdAbsorptionGuardApplyResult(
        val smbOut: Float,
        val intervalAddMin: Int,
        val guard: PkpdAbsorptionGuard?,
        val effectiveFactor: Double,
        val multiplicationApplied: Boolean,
        val skippedDuplicate: Boolean,
    )

    private enum class PkpdGuardLogChannel {
        PIPELINE,
        FINALIZE,
    }

    private fun resolvePkpdGuardPredBg(): Double? {
        val predicted = predictedBg.toDouble()
        if (predicted > 20) return predicted
        if (work.eventualBG.isFinite() && work.eventualBG > 20) return work.eventualBG
        return null
    }

    private fun windowSinceLastPkpdDoseMin(fallbackWindowInt: Int = 0): Double =
        if (work.lastBolusAgeMinutes.isFinite()) work.lastBolusAgeMinutes else fallbackWindowInt.toDouble()

    /**
     * Single PKPD absorption-guard multiply per tick. The decision is
     * [decidePkpdAbsorptionGuardOncePerTick]. This shell reads the fields at the line.
     */
    private fun applyPkpdAbsorptionGuardOncePerTick(
        smbIn: Float,
        pkpdRuntime: PkPdRuntime?,
        windowSinceLastDoseMin: Double,
        anyMealModeForGuard: Boolean,
        isConfirmedHighRise: Boolean,
        mealAdvisorOneShot: Boolean,
        reason: StringBuilder?,
        logChannel: PkpdGuardLogChannel,
    ): PkpdAbsorptionGuardApplyResult {
        val decided = decidePkpdAbsorptionGuardOncePerTick(
            smbIn = smbIn,
            pkpdRuntime = pkpdRuntime,
            windowSinceLastDoseMin = windowSinceLastDoseMin,
            anyMealModeForGuard = anyMealModeForGuard,
            isConfirmedHighRise = isConfirmedHighRise,
            mealAdvisorOneShot = mealAdvisorOneShot,
            reason = reason,
            logChannel = when (logChannel) {
                PkpdGuardLogChannel.PIPELINE -> AimiPkpdGuardLogChannel.PIPELINE
                PkpdGuardLogChannel.FINALIZE -> AimiPkpdGuardLogChannel.FINALIZE
            },
            preferences = preferences,
            consoleLog = work.consoleLog,
            calls = object : AimiPkpdAbsorptionGuardCalls {
                override fun alreadyApplied() = work.pkpdAbsorptionGuardAppliedThisTick
                override fun markApplied() {
                    work.pkpdAbsorptionGuardAppliedThisTick = true
                }
                override fun predBg() = resolvePkpdGuardPredBg()
                override fun bg() = bg
                override fun delta() = delta.toDouble()
                override fun shortAvgDelta() = shortAvgDelta.toDouble()
                override fun targetBg() = targetBg.toDouble()
                override fun intervalSmb() = intervalsmb
                override fun setIntervalSmb(value: Int) {
                    intervalsmb = value
                }
                override fun logGuardError(line: String) {
                    work.consoleError.add(line)
                }
            },
        )
        return PkpdAbsorptionGuardApplyResult(
            smbOut = decided.smbOut,
            intervalAddMin = decided.intervalAddMin,
            guard = decided.guard,
            effectiveFactor = decided.effectiveFactor,
            multiplicationApplied = decided.multiplicationApplied,
            skippedDuplicate = decided.skippedDuplicate,
        )
    }

    /**
     * Garde sport, repas, endocrine, ajustements, PKPD, plancher et plafonds SMB.
     * La décision est [decideSafetyPrecautions]. Cette coquille appelle les méthodes à la ligne.
     * `exerciseFlag` et `suspectedLateFatMeal` restent sur la signature : le corps ne les lit pas.
     */
    private fun applySafetyPrecautions(
        mealData: MealData,
        smbToGiveParam: Float,
        hypoThreshold: Double,
        reason: StringBuilder? = null,
        pkpdRuntime: PkPdRuntime? = null,
        exerciseFlag: Boolean = false,
        suspectedLateFatMeal: Boolean = false,
        isConfirmedHighRise: Boolean = false,
        ignoreSafetyConditions: Boolean = false
    ): Float {
        return decideSafetyPrecautions(
            mealData = mealData,
            smbToGiveParam = smbToGiveParam,
            hypoThreshold = hypoThreshold,
            reason = reason,
            pkpdRuntime = pkpdRuntime,
            isConfirmedHighRise = isConfirmedHighRise,
            ignoreSafetyConditions = ignoreSafetyConditions,
            preferences = preferences,
            texts = rh,
            consoleLog = work.consoleLog,
            calls = object : AimiSafetyPrecautionsCalls {
                override fun mealWeights(mealData: MealData, hypoThreshold: Double) =
                    computeMealAggressionWeights(mealData, hypoThreshold)
                override fun critical(mealData: MealData, hypoThreshold: Double) =
                    isCriticalSafetyCondition(mealData, hypoThreshold)
                override fun markCriticalSafetyZeroed() {
                    work.criticalSafetyZeroedThisTick = true
                }
                override fun sportSafety() = isSportSafetyCondition()
                override fun ensureWCycleInfo() = this@DetermineBasalaimiSMB2.ensureWCycleInfo()
                override fun wCycleBelief() = causalState.lastWCycleBelief
                override fun updateWCycleLearner(needSmbScale: Double?) {
                    this@DetermineBasalaimiSMB2.updateWCycleLearner(null, needSmbScale)
                }
                override fun logEndocrineSmb(need: Double?, endocrineSmbAmp: Double) {
                    val profile = lastProfile ?: return
                    wCycleFacade.infoAndLog(
                        mapOf(
                            "trackingMode" to wCyclePreferences.trackingMode().name,
                            "contraceptive" to wCyclePreferences.contraceptive().name,
                            "thyroid" to wCyclePreferences.thyroid().name,
                            "verneuil" to wCyclePreferences.verneuil().name,
                            "bg" to bg,
                            "delta5" to delta.toDouble(),
                            "work.iob" to work.iob.toDouble(),
                            "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                            "isfProfile" to profile.sens,
                            "dynIsf" to variableSensitivity.toDouble(),
                            "needSmbScale" to need,
                            "endocrineSmbAmp" to endocrineSmbAmp,
                        )
                    )
                }
                override fun specificAdjustments(smbAmount: Float, ignoreSafetyRestrictions: Boolean) =
                    applySpecificAdjustments(smbAmount, ignoreSafetyRestrictions)
                override fun mealTime() = work.mealTime
                override fun bfastTime() = this@DetermineBasalaimiSMB2.bfastTime
                override fun lunchTime() = this@DetermineBasalaimiSMB2.lunchTime
                override fun dinnerTime() = this@DetermineBasalaimiSMB2.dinnerTime
                override fun highCarbTime() = this@DetermineBasalaimiSMB2.highCarbTime
                override fun snackTime() = work.snackTime
                override fun confirmedHighRiseThisTick() = work.isConfirmedHighRiseThisTick
                override fun mealAdvisorOneShotThisTick() = work.mealAdvisorOneShotThisTick
                override fun windowSinceLastPkpdDoseMin() = this@DetermineBasalaimiSMB2.windowSinceLastPkpdDoseMin()
                override fun applyPkpdGuard(
                    smbIn: Float,
                    pkpdRuntime: PkPdRuntime?,
                    windowSinceLastDoseMin: Double,
                    anyMealModeForGuard: Boolean,
                    isConfirmedHighRise: Boolean,
                    mealAdvisorOneShot: Boolean,
                    reason: StringBuilder?,
                ): AimiSafetyGuardApply {
                    val applied = applyPkpdAbsorptionGuardOncePerTick(
                        smbIn = smbIn,
                        pkpdRuntime = pkpdRuntime,
                        windowSinceLastDoseMin = windowSinceLastDoseMin,
                        anyMealModeForGuard = anyMealModeForGuard,
                        isConfirmedHighRise = isConfirmedHighRise,
                        mealAdvisorOneShot = mealAdvisorOneShot,
                        reason = reason,
                        logChannel = PkpdGuardLogChannel.FINALIZE,
                    )
                    return AimiSafetyGuardApply(applied.smbOut, applied.skippedDuplicate)
                }
                override fun finalizeSmb(smbToGive: Float) = finalizeSmbToGive(smbToGive)
                override fun maxSMB() = work.maxSMB
                override fun maxIob() = work.maxIob
                override fun iob() = work.iob
            },
        )
    }
    // Helper to check for recent bolus activity (prevent double dosing)
    private fun hasReceivedRecentBolus(minutes: Int, lastBolusTimeMs: Long): Boolean {
        val lookbackTime = dateUtil.now() - minutes * 60 * 1000L

        // 1. Check DB
        val boluses = getBolusesFromTimeCached(lookbackTime, true)
        val dbHasBolus = boluses.any { it.amount > 0.3 }

        // 2. Check Pump Status Memory (Fallback)
        val memoryHasBolus = lastBolusTimeMs > lookbackTime

        if (dbHasBolus || memoryHasBolus) {
            return true
        }
        return false
    }

    /**
     * Drift terminator: sustained plateau above target with weak rise, positive deviation vs IOB prediction, no recent bolus.
     */
    private fun isDriftTerminatorCondition(
        bg: Float,
        targetBg: Float,
        delta: Float,
        avgDelta: Float,
        combinedDelta: Float,
        minDeviation: Double,
        lastBolusVolume: Double,
        reason: StringBuilder
    ): Boolean = decideDriftTerminatorCondition(
        bg, targetBg, delta, avgDelta, combinedDelta, minDeviation, lastBolusVolume, reason,
    )

    private fun calculateDynamicMicroBolus(
        isf: Double,
        baseFactor: Double = 20.0,
        reason: StringBuilder,
    ): Double = AimiTickPolicyMath.calculateDynamicMicroBolus(isf, baseFactor, reason)



    private fun isCompressionProtectionCondition(
        delta: Float,
        reason: StringBuilder,
    ): Boolean = AimiTickPolicyMath.isCompressionProtectionCondition(delta, reason)

    // Post-hypo disambiguation ([PostHypoState]); [causalState.lastHypoBelow70At] persists for rebound timer.

    /**
     * Trois états possibles après un épisode BG < 70 :
     *   None              → aucune hypo récente  → flux SMB normal
     *   ReboundSuspected  → hypo récente, pas de repas détecté → SMB=0, TBR bridge
     *   MealConfirmed     → hypo récente MAIS repas confirmé   → SMB cappé 50%
     */
    /**
     * [classifyPostHypoState] + prefs carbs réutilisées plus bas (`timeSinceEstimateMin`, [resolveMealHyperBasalBoostOutcome]).
     */
    private data class AimiPostAutodrivePostHypoBundle(
        val postHypoState: PostHypoState,
        val estimatedCarbs: Double,
        /** Horodatage prefs advisor (ms) ; l’âge se recalcule en aval avec `aimiWallClockMs()`. */
        val estimatedCarbsTimeMs: Long,
    )

    private fun isMealLikelyWithoutDeclaration(
        shortAvgDelta: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        recentBGs: List<Float>,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        localHour: Int,
    ): Boolean = AimiTickPolicyMath.isMealLikelyWithoutDeclaration(
        shortAvgDelta,
        delta,
        slopeFromMinDeviation,
        recentBGs,
        estimatedCarbs,
        estimatedCarbsAgeMs,
        localHour,
    )

    /**
     * Classifie l'état post-hypo et met à jour [causalState.lastHypoBelow70At].
     *
     * @param recentBGs         Lectures récentes (les + récentes en premier)
     * @param cob               COB actuel en grammes
     * @param explicitMealMode  true si un mode repas manuel est actif (mealTime/lunchTime…)
     * @param shortAvgDelta     Moyenne courte des deltas
     * @param delta             Delta instantané
     * @param slopeFromMinDeviation Signal UAM
     * @param estimatedCarbs    Glucides estimés par AIMI Advisor
     * @param estimatedCarbsAgeMs Âge de l'estimation en ms
     * @param localHour         Heure locale (0-23)
     * @param reason            StringBuilder pour les logs
     * @param now               Timestamp courant en ms
     */
    private fun classifyPostHypoState(
        recentBGs: List<Float>,
        cob: Double,
        explicitMealMode: Boolean,
        shortAvgDelta: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        localHour: Int,
        reason: StringBuilder,
        now: Long = aimiWallClockMs()
    ): PostHypoState {
        val step = AimiPostHypoClassifier.classify(
            recentBGs = recentBGs,
            cob = cob,
            explicitMealMode = explicitMealMode,
            shortAvgDelta = shortAvgDelta,
            delta = delta,
            slopeFromMinDeviation = slopeFromMinDeviation,
            estimatedCarbs = estimatedCarbs,
            estimatedCarbsAgeMs = estimatedCarbsAgeMs,
            localHour = localHour,
            targetBg = targetBg.toDouble(),
            causalState.lastHypoBelow70At = causalState.lastHypoBelow70At,
            now = now,
            uamConfidence = { AimiUamHandler.confidenceOrZero() },
        )
        causalState.lastHypoBelow70At = step.causalState.lastHypoBelow70At
        if (step.reason.isNotEmpty()) reason.append(step.reason)
        return when (val state = step.state) {
            AimiPostHypoState.None -> PostHypoState.None
            is AimiPostHypoState.ReboundSuspected -> PostHypoState.ReboundSuspected(state.sinceMs)
            is AimiPostHypoState.MealConfirmed -> PostHypoState.MealConfirmed(state.sinceMs)
        }
    }

    /**
     * Après la branche Autodrive V3 : lecture prefs advisor (carbs / horodatage), âge pour [classifyPostHypoState],
     * agrégat mode repas explicite, puis classification (**effets** sur fenêtre hypo & logs [reason]).
     *
     * Retourne aussi **`estimatedCarbs` / `estimatedCarbsTimeMs`** pour le même tick (overlay repas / hyper plus bas).
     *
     * **Invariant** : une seule invocation par tick — [AimiPostAutodrivePostHypoBundle.postHypoState] pour drift/SMB (roadmap §8).
     */
    private fun runPostAutodrivePostHypoClassification(
        recentBGs: List<Float>,
        cob: Float,
        shortAvgDeltaAdj: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        reason: StringBuilder,
    ): AimiPostAutodrivePostHypoBundle {
        val localHour = aimiLocalHour(injectedTickEpochMs ?: aimiWallClockMs())
        val estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val estimatedCarbsTimeDouble = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime)
        val estimatedCarbsTime = estimatedCarbsTimeDouble.toLong()
        val estimatedCarbsAgeMs =
            if (estimatedCarbsTime > 0L) aimiWallClockMs() - estimatedCarbsTime else Long.MAX_VALUE
        val explicitMealMode =
            mealTime || lunchTime || dinnerTime || bfastTime || highCarbTime || snackTime
        val postHypoState = classifyPostHypoState(
            recentBGs = recentBGs,
            cob = cob.toDouble(),
            explicitMealMode = explicitMealMode,
            shortAvgDelta = shortAvgDeltaAdj,
            delta = delta,
            slopeFromMinDeviation = slopeFromMinDeviation,
            estimatedCarbs = estimatedCarbs,
            estimatedCarbsAgeMs = estimatedCarbsAgeMs,
            localHour = localHour,
            reason = reason,
        )
        return AimiPostAutodrivePostHypoBundle(
            postHypoState = postHypoState,
            estimatedCarbs = estimatedCarbs,
            estimatedCarbsTimeMs = estimatedCarbsTime,
        )
    }

    /**
     * Repas explicitement déclaré par l'utilisateur : un mode legacy actif
     * (meal/bfast/lunch/dinner/highcarb/snack) **ou** insuline validée via AIMI Meal Advisor.
     */
    private fun explicitMealDeliveryRequested(): Boolean =
        work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime ||
            preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)

    /**
     * Le repas déclaré doit primer sur les verrous (lockout exercice/activité, blocage post-hypo),
     * sauf en hypo sévère (BG ≤ [SEVERE_HYPO_MEAL_OVERRIDE_MGDL]).
     */
    private fun mealDeliveryOverridesLockouts(): Boolean =
        explicitMealDeliveryRequested() && bg > SEVERE_HYPO_MEAL_OVERRIDE_MGDL

    // Conditions repas legacy : fenêtre + mode actif UNIQUEMENT.
    // Le test de valeur (lastBolusSMBUnit != pbolus) est retiré : il sur-bloquait P1 dès qu'un SMB/bolus
    // valait par coïncidence la valeur du prébolus (bug « P1 ne part pas, P2 oui »). L'unicité du prébolus
    // est désormais garantie par le verrou one-shot par tag dans applyLegacyMealModes (voir [prebolusAlreadyFiredThisActivation]).
    private fun isMealModeCondition(): Boolean = work.mealruntime in 0..7 && work.mealTime
    private fun isbfastModeCondition(): Boolean = work.bfastruntime in 0..7 && bfastTime
    private fun isbfast2ModeCondition(): Boolean = work.bfastruntime in 15..29 && bfastTime
    private fun isLunchModeCondition(): Boolean = work.lunchruntime in 0..7 && lunchTime
    private fun isLunch2ModeCondition(): Boolean = work.lunchruntime in 15..24 && lunchTime
    private fun isDinnerModeCondition(): Boolean = work.dinnerruntime in 0..7 && dinnerTime
    private fun isDinner2ModeCondition(): Boolean = work.dinnerruntime in 15..24 && dinnerTime
    private fun isHighCarbModeCondition(): Boolean = work.highCarbrunTime in 0..7 && highCarbTime
    private fun isHighCarb2ModeCondition(): Boolean = work.highCarbrunTime in 15..23 && highCarbTime
    private fun issnackModeCondition(): Boolean = work.snackrunTime in 0..7 && work.snackTime
    private fun mealModeRuntimeToNullableMinutes(rt: Long?): Int =
        AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(rt)

    private fun runtimeToMinutes(rt: Long): Int =
        AimiTickPolicyMath.runtimeToMinutes(rt)


    private fun isMealContextActive(mealData: MealData): Boolean {
        val manualFlags = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime
        val cobActive = mealData.mealCOB > 5.0
        return manualFlags || cobActive
    }

    private fun resolveMealCorrectionContext(
        mealData: MealData,
        bgMgdl: Double = bg,
        deltaMgdlPer5: Double = delta.toDouble(),
        shortAvgDeltaMgdlPer5: Double = shortAvgDelta.toDouble(),
        explicitMealMode: Boolean = work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime,
    ): MealCorrectionContextResolver.Output =
        MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = bgMgdl,
                deltaMgdlPer5 = deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = shortAvgDeltaMgdlPer5,
                explicitMealMode = explicitMealMode,
                mealCobG = mealData.mealCOB,
                mealAbsorptionOutput = work.lastMealAbsorptionOutput,
                hypothesisState = causalState.lastUamHypothesisState,
                latentState = causalState.lastPhysioLatentState,
                patientModeDecision = work.lastPatientModeDecision,
                patientState = work.lastPatientState,
                postHypoDelivery = work.lastPostHypoDeliveryAuthority,
                harmoniaAction = work.lastHarmoniaDecision?.action,
                harmoniaEligible = work.lastHarmoniaDecision?.eligible == true,
            ),
        )

    private fun buildPkpdMealContext(
        mealData: MealData,
        predictedBgMgdl: Double,
        targetBgMgdl: Double,
    ): MealAggressionContext {
        val explicitMeal = isMealContextActive(mealData)
        val mealCorrectionContext = resolveMealCorrectionContext(mealData = mealData)
        return MealAggressionContext(
            mealModeActive = explicitMeal || mealCorrectionContext.mealPriorityEligible,
            predictedBgMgdl = predictedBgMgdl,
            targetBgMgdl = targetBgMgdl,
        )
    }

    private fun computeMealAggressionWeights(mealData: MealData, hypoThreshold: Double): AimiHypoSmbSafety.MealAggressionWeights =
        AimiHypoSmbSafety.mealAggression(
            AimiHypoSmbSafety.MealAggressionInput(
                mealContextActive = isMealContextActive(mealData),
                predictedBg = predictedBg.toDouble(),
                targetBg = targetBg,
                bg = bg,
                hypoThreshold = hypoThreshold,
                mealCob = mealData.mealCOB,
            ),
        )

    private fun isCriticalSafetyCondition(mealData: MealData, hypoThreshold: Double): Pair<Boolean, String> {
        val cobFromMeal = try {
            mealData.mealCOB
        } catch (_: Throwable) {
            work.cob
        }.toDouble()
        val scan = AimiHypoSmbSafety.criticalConditions(
            AimiHypoSmbSafety.CriticalInput(
                context = AimiHypoSmbSafety.SafetyContext(
                    delta = delta.toDouble(),
                    bg = bg,
                    iob = work.iob.toDouble(),
                    predictedBg = predictedBg.toDouble(),
                    eventualBG = work.eventualBG,
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    longAvgDelta = longAvgDelta.toDouble(),
                    fastingTime = work.fastingTime,
                    iscalibration = work.iscalibration,
                    targetBg = targetBg.toDouble(),
                    maxSMB = work.maxSMB,
                    maxIob = work.maxIob,
                    mealTime = work.mealTime,
                    bfastTime = bfastTime,
                    lunchTime = lunchTime,
                    dinnerTime = dinnerTime,
                    highCarbTime = highCarbTime,
                    snackTime = work.snackTime,
                    cob = cobFromMeal,
                    hypoThreshold = hypoThreshold,
                ),
                honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon),
                hyperDropExemptEnabled = preferences.get(BooleanKey.OApsAIMIHyperDroppingExemptEnabled),
                labels = AimiHypoSmbSafety.CriticalLabels(
                    hypoGuard = rh.gs(ApsStrings.condition_hypoguard),
                    honeysmb = rh.gs(ApsStrings.condition_honeysmb),
                    negDelta = rh.gs(ApsStrings.condition_negdelta),
                    nosmb = rh.gs(ApsStrings.condition_nosmb),
                    fasting = rh.gs(ApsStrings.condition_fasting),
                    belowMin = rh.gs(ApsStrings.condition_belowminthreshold),
                    newCalibration = rh.gs(ApsStrings.condition_newcalibration),
                    belowTargetDropping = rh.gs(ApsStrings.condition_belowtarget_dropping),
                    belowTargetStableNoCob = rh.gs(ApsStrings.condition_belowtarget_stable_nocob),
                    droppingFast = rh.gs(ApsStrings.condition_droppingfast),
                    droppingFastAtHigh = rh.gs(ApsStrings.condition_droppingfastathigh),
                    droppingVeryFast = rh.gs(ApsStrings.condition_droppingveryfast),
                    prediction = rh.gs(ApsStrings.condition_prediction),
                    bg90 = rh.gs(ApsStrings.condition_bg90),
                    acceleratingDown = rh.gs(ApsStrings.condition_acceleratingdown),
                ),
                hysteresis = AimiHypoSmbSafety.HypoHysteresisState(causalState.lastHypoBlockAt, causalState.hypoClearCandidateSince),
                nowMs = aimiWallClockMs(),
            ),
        )
        causalState.lastHypoBlockAt = scan.hysteresis.causalState.lastHypoBlockAt
        causalState.hypoClearCandidateSince = scan.hysteresis.causalState.hypoClearCandidateSince
        scan.logs.forEach { work.consoleLog.add(it) }
        return scan.conditions.isNotEmpty() to buildConditionMessage(scan.conditions.isNotEmpty(), scan.conditions)
    }


    /**
     * Construction du message de retour décrivant les conditions remplies
     */
    private fun buildConditionMessage(isCritical: Boolean, conditions: List<String>): String {
        val conditionsString = if (conditions.isNotEmpty()) {
            conditions.joinToString(", ")
        } else {
//          "No conditions met"
            rh.gs(ApsStrings.no_conditions_met_2)
        }

//      return "Safety condition $isCritical : $conditionsString"
        val critical = if (isCritical) "✔"  else ""
        return rh.gs(ApsStrings.safety_condition, critical, conditionsString)
    }


    private fun isSportSafetyCondition(): Boolean = AimiHypoSmbSafety.sportSafety(
        AimiHypoSmbSafety.SportSafetyInput(
            recentSteps5 = recentSteps5Minutes,
            recentSteps10 = recentSteps10Minutes,
            recentSteps30 = recentSteps30Minutes,
            recentSteps60 = recentSteps60Minutes,
            recentSteps180 = recentSteps180Minutes,
            sportTime = work.sportTime,
            activityActive = work.aimiContextActivityActive,
            averageHr = averageBeatsPerMinute,
            averageHr10 = averageBeatsPerMinute10,
            targetBg = targetBg,
        ),
    )
    private fun calculateSMBInterval(): Int {
        val out = AimiHypoSmbSafety.smbInterval(
            AimiHypoSmbSafety.SmbIntervalInput(
                delta = delta,
                bg = bg.toFloat(),
                targetBg = targetBg,
                iob = work.iob.toDouble(),
                maxSmb = work.maxSMB,
                honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon),
                night = preferences.get(BooleanKey.OApsAIMInight),
                currentHour = aimiLocalHour(),
                snackTime = work.snackTime,
                mealTime = work.mealTime,
                bfastTime = bfastTime,
                lunchTime = lunchTime,
                dinnerTime = dinnerTime,
                sleepTime = work.sleepTime,
                highCarbTime = highCarbTime,
                lowCarbTime = work.lowCarbTime,
                intervals = AimiHypoSmbSafety.SmbIntervals(
                    snack = preferences.get(IntKey.OApsAIMISnackinterval),
                    meal = preferences.get(IntKey.OApsAIMImealinterval),
                    bfast = preferences.get(IntKey.OApsAIMIBFinterval),
                    lunch = preferences.get(IntKey.OApsAIMILunchinterval),
                    dinner = preferences.get(IntKey.OApsAIMIDinnerinterval),
                    sleep = preferences.get(IntKey.OApsAIMISleepinterval),
                    hc = preferences.get(IntKey.OApsAIMIHCinterval),
                    highBG = preferences.get(IntKey.OApsAIMIHighBGinterval),
                ),
                pkpdThrottleIntervalAdd = work.pkpdThrottleIntervalAdd,
                recentSteps5 = recentSteps5Minutes,
                recentSteps30 = recentSteps30Minutes,
                recentSteps180 = recentSteps180Minutes,
                lastSmbTime = work.lastsmbtime,
            ),
        )
        out.logs.forEach { work.consoleLog.add(it) }
        return out.minutes
    }

    private fun canFallbackSmbWithoutPrediction(
        bg: Double,
        delta: Double,
        targetBg: Double,
        iob: Double,
        profile: OapsProfileAimi,
    ): Boolean = AimiTickPolicyMath.canFallbackSmbWithoutPrediction(bg, delta, targetBg, iob, profile)

    private fun shouldBlockHypoWithHysteresis(
        bg: Double,
        predictedBg: Double,
        eventualBg: Double,
        threshold: Double,
        deltaMgdlPer5min: Double,
        now: Long = aimiWallClockMs(),
    ): Boolean {
        val step = AimiHypoSmbSafety.stepHypoHysteresis(
            bg = bg,
            predictedBg = predictedBg,
            eventualBg = eventualBg,
            threshold = threshold,
            deltaMgdlPer5min = deltaMgdlPer5min,
            now = now,
            state = AimiHypoSmbSafety.HypoHysteresisState(causalState.lastHypoBlockAt, causalState.hypoClearCandidateSince),
        )
        causalState.lastHypoBlockAt = step.state.causalState.lastHypoBlockAt
        causalState.hypoClearCandidateSince = step.state.causalState.hypoClearCandidateSince
        return step.blocked
    }

    private fun applySpecificAdjustments(smbAmount: Float, ignoreSafetyRestrictions: Boolean = false): Float {
        val currentHour = aimiLocalHour()
        return AimiHypoSmbSafety.specificAdjustment(
            AimiHypoSmbSafety.SpecificAdjustmentInput(
                smbAmount = smbAmount,
                ignoreSafetyRestrictions = ignoreSafetyRestrictions,
                delta = delta,
                shortAvgDelta = shortAvgDelta,
                longAvgDelta = longAvgDelta,
                bg = bg,
                targetBg = targetBg,
                honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon),
                iob = work.iob,
                maxSmb = work.maxSMB,
                currentHour = currentHour,
            ),
        )
    }

    private fun finalizeSmbToGive(smbToGive: Float): Float = AimiHypoSmbSafety.finalizeSmbFloor(
        AimiHypoSmbSafety.SmbFloorInput(
            smbToGive = smbToGive,
            iob = work.iob,
            bg = bg,
            delta = delta,
            lateFatRise = causalState.lateFatRiseFlag,
        ),
    )

    // DetermineBasalAIMI2.kt
    private fun calculateSMBFromModel(reason: StringBuilder? = null): Float {
        val smb = AimiUamHandler.predictSmbUam(
            floatArrayOf(
                work.hourOfDay.toFloat(), work.weekend.toFloat(),
                bg.toFloat(), targetBg, work.iob,
                delta, shortAvgDelta, longAvgDelta,
                tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour,
                recentSteps5Minutes.toFloat(), recentSteps10Minutes.toFloat(),
                recentSteps15Minutes.toFloat(), recentSteps30Minutes.toFloat(),
                recentSteps60Minutes.toFloat(), recentSteps180Minutes.toFloat()
            ),
            reason, // 👈 logs visibles si non-null
            rh
        )
        return smb.coerceAtLeast(0f)
    }
    private data class MealFlags(
        val mealTime: Boolean,
        val bfastTime: Boolean,
        val lunchTime: Boolean,
        val dinnerTime: Boolean,
        val highCarbTime: Boolean
    )
    private fun isLateFatProteinRise(
        bg: Double,
        predictedBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        iob: Double,
        cob: Double,
        maxSMB: Double,
        lastBolusTimeMs: Long?,           // null si inconnu
        mealFlags: MealFlags,
        nowMs: Long = dateUtil.now()      // ou aimiWallClockMs()
    ): Boolean = decideLateFatProteinRise(
        bg = bg,
        predictedBg = predictedBg,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        iob = iob,
        cob = cob,
        maxSMB = maxSMB,
        lastBolusTimeMs = lastBolusTimeMs,
        mealTime = mealFlags.mealTime,
        bfastTime = mealFlags.bfastTime,
        lunchTime = mealFlags.lunchTime,
        dinnerTime = mealFlags.dinnerTime,
        highCarbTime = mealFlags.highCarbTime,
        nowMs = nowMs,
    )

    /**
     * Damping-only sibling of `isLateFatProteinRise`. SHADOW for now: computed and exported, not
     * wired into any dose. It is deliberately NOT fed to the meal absorption phase engine nor to any
     * belief layer, because that predicate also drives an SMB floor whose IOB requirement is the
     * opposite of this one.
     *
     * It looks for the late part of an absorption episode while a large insulin stack is already
     * working, which is where extra SMB overshoots.
     */
    private fun isLateFatDampingWindow(nowMs: Long = dateUtil.now()): Boolean {
        val ageMin = MealAbsorptionMemory.onsetAgeMin(nowMs) ?: return false
        if (ageMin !in 120.0..420.0) return false
        if (cob > 1.0f) return false
        if (work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime) return false
        val rising = delta >= 1.0f && (shortAvgDelta >= 0.5f || longAvgDelta >= 0.3f)
        val highish = bg > 130.0 || predictedBg > 140.0f
        // The stack floor is a STOCK, homogeneous with IOB. maxSMB is a per bolus cap and was the
        // wrong scale: during the incident IOB was 6 to 10 U against a maxSMB of 0.05 to 1.5.
        // There is no 24h TDD field on this class, only the hourly rate, so rebuild the stock.
        val tdd24hU = tdd24HrsPerHour.toDouble() * 24.0
        val stackFloorU = maxOf(2.0 * work.basalaimi.toDouble(), 0.15 * tdd24hU, 1.0)
        return rising && highish && iob >= stackFloorU
    }

    private fun neuralnetwork5(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        predictedSMB: Float,
        profile: OapsProfileAimi
    ): Float {
        val recentDeltas = getRecentDeltas()
        val predicted = predictedDelta(recentDeltas)

        // 🛡️ Fallback baseline (always available, no IO)
        val finalRefinedSMB: Float = calculateSMBFromModel()

        // 🧠 Feature vector (10 physio + 1 trendIndicator)
        val trendIndicator = calculateTrendIndicator(
            delta, shortAvgDelta, longAvgDelta,
            bg.toFloat(), work.iob, variableSensitivity, work.cob, work.normalBgThreshold,
            recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
            profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
        )
        val baseFeatures = floatArrayOf(
            bg.toFloat(), work.iob.toFloat(), work.cob.toFloat(), delta, shortAvgDelta, longAvgDelta,
            tdd7DaysPerHour.toFloat(), tdd2DaysPerHour.toFloat(), tddPerHour.toFloat(), tdd24HrsPerHour.toFloat()
        )
        val features = SmbRefinementFeatureSchema.buildRuntimeFeatures(
            baseFeatures = baseFeatures,
            trendIndicator = trendIndicator.toFloat(),
            physioLatentState = causalState.lastPhysioLatentState,
            patientModeDecision = work.lastPatientModeDecision,
            causalStatePosterior = work.lastPatientState?.causalPosterior,
        )
        val behaviorProfile = behaviorProfileSource.read(preferences)

        // 🔥 Trigger async training (fire-and-forget, rate-limited to 1/6h, never blocks)
        AimiSmbTrainer.maybeTrainAsync(
            storage = storage,
            dir = externalDirPath,
            csvFile = csvfilePath
        )

        // 🎯 Inference-only O(1): fallback to predictedSMB on any issue
        val mlRefined = AimiSmbTrainer.refine(finalRefinedSMB, features, behaviorProfile)

        if (mlRefined > predictedSMB && bg > 150 && delta > 5) {
            return mlRefined
        }

        val alpha = 0.7f
        return alpha * mlRefined + (1 - alpha) * predictedSMB
    }

    private fun computeDynamicBolusMultiplier(delta: Float): Float =
        AimiTickPolicyMath.computeDynamicBolusMultiplier(delta)

    private fun FloatArray.toDoubleArray(): DoubleArray {
        return this.map { it.toDouble() }.toDoubleArray()
    }

    private fun getRecentDeltas(): List<Double> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return emptyList()
        if (data.isEmpty()) return emptyList()

        // Fenêtre standard selon BG
        val standardWindow = if (bg < 130) 40f else 20f
        // Fenêtre raccourcie pour détection rapide
        val rapidRiseWindow = 10f
        // Si le delta instantané est supérieur à 15 mg/dL, on choisit la fenêtre rapide
        val intervalMinutes = if (delta > 15) rapidRiseWindow else standardWindow

        val nowTimestamp = data.first().timestamp
        return data.drop(1).filter { it.value > 39 && !it.filledGap }
            .mapNotNull { entry ->
                val minutesAgo = ((nowTimestamp - entry.timestamp) / (1000.0 * 60)).toFloat()
                if (minutesAgo in 0.0f..intervalMinutes) {
                    val delta = (data.first().recalculated - entry.recalculated) / minutesAgo * 5f
                    delta
                } else {
                    null
                }
            }
    }


    private fun predictedDelta(deltaHistory: List<Double>): Double =
        AimiTickPolicyMath.predictedDelta(deltaHistory)

    // ❌ adjustFactorsBasedOnBgAndHypo() REMOVED (was lines 3191-3251)
    // Legacy function for time-based reactivity (morning/afternoon/evening factors)
    // Replaced by UnifiedReactivityLearner.globalFactor which learns optimal reactivity
    // from actual glycemic outcomes (hypos, hypers, variability)



    private fun calculateAdjustedDelayFactor(
        bg: Float,
        recentSteps180Minutes: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float
    ): Float = AimiHypoSmbSafety.adjustedDelayFactor(
        AimiHypoSmbSafety.DelayFactorInput(
            bg = bg,
            recentSteps180 = recentSteps180Minutes,
            averageHr = averageBeatsPerMinute,
            averageHr10 = averageBeatsPerMinute10,
            currentHour = aimiLocalHour(),
            normalBgThreshold = work.normalBgThreshold,
        ),
    )


    private fun calculateInsulinEffect(
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float
    ): Float = AimiHypoSmbSafety.insulinEffect(
        AimiHypoSmbSafety.InsulinEffectInput(
            bg = bg,
            iob = iob,
            variableSensitivity = variableSensitivity,
            cob = cob,
            normalBgThreshold = normalBgThreshold,
            recentSteps180Min = recentSteps180Min,
            memberRecentSteps180 = recentSteps180Minutes,
            averageHr = averageBeatsPerMinute,
            averageHr10 = averageBeatsPerMinute10,
            insulinDivisor = insulinDivisor,
            currentHour = aimiLocalHour(),
            phrase = safetyPhraseBook(),
        ),
    )
    private fun calculateTrendIndicator(
        delta: Float,
        shortAvgDelta: Float,
        longAvgDelta: Float,
        bg: Float,
        iob: Float,
        variableSensitivity: Float,
        cob: Float,
        normalBgThreshold: Float,
        recentSteps180Min: Int,
        averageBeatsPerMinute: Float,
        averageBeatsPerMinute10: Float,
        insulinDivisor: Float,
        recentSteps5min: Int,
        recentSteps10min: Int
    ): Int = AimiHypoSmbSafety.trendIndicator(
        AimiHypoSmbSafety.TrendInput(
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            insulin = AimiHypoSmbSafety.InsulinEffectInput(
                bg = bg,
                iob = iob,
                variableSensitivity = variableSensitivity,
                cob = cob,
                normalBgThreshold = normalBgThreshold,
                recentSteps180Min = recentSteps180Min,
                memberRecentSteps180 = recentSteps180Minutes,
                averageHr = averageBeatsPerMinute,
                averageHr10 = averageBeatsPerMinute10,
                insulinDivisor = insulinDivisor,
                currentHour = aimiLocalHour(),
                phrase = safetyPhraseBook(),
            ),
            recentSteps5 = recentSteps5min,
            recentSteps10 = recentSteps10min,
        ),
    )

    private data class PredictionResult(
        val eventual: Double,
        val series: List<Int>,
        val pathBounds: PredictionPathBounds,
    )

    /**
     * Wave4 H3 — record soft-floor/EGP path-min after [AdvancedPredictionEngine.predictCurves].
     * Physics are already on insulin curves; JSON keeps raw vs soft for study.
     */
    private fun recordPkpdSoftFloor(
        curves: AdvancedPredictionCurves,
    ): PkpdSoftFloorTelemetry = decideRecordPkpdSoftFloor(
        curves = curves,
        endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
        calls = object : AimiPkpdSoftFloorWrite {
            override fun writeTelemetryAndLog(telemetry: PkpdSoftFloorTelemetry) {
                lastPkpdSoftFloorTelemetry = telemetry
                work.consoleLog.add(PkpdSoftFloorPathMin.formatLogLine(telemetry))
            }
        },
    )

    private fun applySoftFloorToPredSeries(
        series: List<Int>,
        telemetry: PkpdSoftFloorTelemetry,
    ): List<Int> = AimiTickPolicyMath.applySoftFloorToPredSeries(series, telemetry)

    private fun computePkpdPredictions(
        currentBg: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        rT: RT,
        delta: Double,
        pkpdRuntime: PkPdRuntime? = null,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output? = null,
        hypothesisState: UamHypothesisState? = null,
        latentState: PhysioLatentState? = null,
        uamConfidence: Double = 0.0,
    ): PredictionResult {
        val predicted = decideComputePkpdPredictions(
            currentBg = currentBg,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile,
            rT = rT,
            delta = delta,
            pkpdRuntime = pkpdRuntime,
            mealAbsorptionOutput = mealAbsorptionOutput,
            hypothesisState = hypothesisState,
            latentState = latentState,
            uamConfidence = uamConfidence,
            preferences = preferences,
            consoleLog = work.consoleLog,
            calls = object : AimiPkpdCurveCalls {
                override fun setAdvancedCurves(curves: AdvancedPredictionCurves) {
                    work.lastAdvancedPredictionCurves = curves
                }
                override fun recordSoftFloor(curves: AdvancedPredictionCurves) = recordPkpdSoftFloor(curves)
            },
        )
        return PredictionResult(predicted.eventual, predicted.series, predicted.pathBounds)
    }

    private fun ensurePredictionFallback(rt: RT, bgNow: Double) {
        if (rt.predBGs == null) {
            val safeBg = bgNow.roundToInt()
            rt.predBGs = Predictions().apply {
                IOB = listOf(safeBg)
                COB = listOf(safeBg)
                ZT = listOf(safeBg)
                UAM = listOf(safeBg)
            }
            work.consoleLog.add("GATE_PKPD_MISSING: injected fallback prediction @${safeBg}mg/dL")
        }
        if (rt.eventualBG == null) {
            rt.eventualBG = bgNow
        }
    }


    private fun determineNoteBasedOnBg(bg: Double): String {
        return when {
            //bg > 170 -> "more aggressive"
            bg > 170 -> rh.gs(ApsStrings.bg_note_more_aggressive)
            //bg in 90.0..100.0 -> "less aggressive"
            bg in 90.0..100.0 -> rh.gs(ApsStrings.bg_note_less_aggressive)
            //bg in 80.0..89.9 -> "too aggressive" // Vous pouvez ajuster ces valeurs selon votre logique
            bg in 80.0..89.9 -> rh.gs(ApsStrings.bg_note_too_aggressive)
            //bg < 80 -> "low treatment"
            bg < 80 -> rh.gs(ApsStrings.bg_note_low_treatment)
            //else -> "normal" // Vous pouvez définir un autre message par défaut pour les cas non couverts
            else -> rh.gs(ApsStrings.bg_note_normal)
        }
    }

    private fun processNotesAndCleanUp(notes: String): String =
        AimiTickPolicyMath.processNotesAndCleanUp(notes)
    private fun ensureWCycleInfo(): WCycleInfo? {
        val profile = lastProfile ?: return null
        work.wCycleInfoForRun?.let { return it }
        val info = wCycleFacade.infoAndLog(
            mapOf(
                "trackingMode" to wCyclePreferences.trackingMode().name,
                "contraceptive" to wCyclePreferences.contraceptive().name,
                "thyroid" to wCyclePreferences.thyroid().name,
                "verneuil" to wCyclePreferences.verneuil().name,
                "bg" to bg,
                "delta5" to delta.toDouble(),
                "work.iob" to work.iob.toDouble(),
                "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                "isfProfile" to profile.sens,
                "dynIsf" to variableSensitivity.toDouble()
            )
        )
        work.wCycleInfoForRun = info
        checkCycleDayNotification(info)
        return info
    }

    private fun checkCycleDayNotification(info: WCycleInfo) {
        val mode = wCyclePreferences.trackingMode()
        val tracking = mode != CycleTrackingMode.MENOPAUSE && mode != CycleTrackingMode.NO_MENSES_LARC

        // Trigger: Late Period (Day > Avg Length)
        // Spam Prevention: Notify only once per day (if day index changed)
        val limit = wCyclePreferences.avgLen()
        if (tracking && info.dayInCycle > limit) {
             if (info.dayInCycle != lastCycleNotificationDay) {
                 val msg = "⚠️ WCycle: J${info.dayInCycle} > $limit. Retard détecté.\nMettre à jour le 1er jour des règles ?"
                 notificationManager.post(
                     id = app.aaps.core.interfaces.notifications.NotificationId.HYPO_RISK_ALARM,
                     text = msg
                 )
                 lastCycleNotificationDay = info.dayInCycle
             }
        }
    }

    private fun appendWCycleReason(target: StringBuilder, info: WCycleInfo) {
        if (work.wCycleReasonLogged) return
        if (info.reason.isBlank()) return
        target.append(", WCycle: ").append(info.reason)
        work.wCycleReasonLogged = true
    }

    private fun updateWCycleLearner(needBasalScale: Double?, needSmbScale: Double?) {
        val info = work.wCycleInfoForRun ?: return
        if (!info.enabled) return
        val minClamp = wCyclePreferences.clampMin()
        val maxClamp = wCyclePreferences.clampMax()
        wCycleLearner.update(
            info.phase,
            needBasalScale?.coerceIn(minClamp, maxClamp),
            needSmbScale?.coerceIn(minClamp, maxClamp)
        )
    }

    private fun calculateDynamicPeakTime(
        currentActivity: Double,
        futureActivity: Double,
        sensorLagActivity: Double,
        historicActivity: Double,
        profile: OapsProfileAimi,
        stepCount: Int? = null,
        heartRate: Int? = null,
        bg: Double,
        delta: Double,
        reasonBuilder: StringBuilder
    ): Double {
        val out = AimiHypoSmbSafety.dynamicPeak(
            AimiHypoSmbSafety.DynamicPeakInput(
                currentActivity, futureActivity, sensorLagActivity, historicActivity,
                profile.peakTime, stepCount, heartRate, bg, delta,
            ),
            safetyPhraseBook(),
        )
        work.peakintermediaire = out.intermediate
        out.logs.forEach { work.consoleLog.add(it) }
        reasonBuilder.append(out.reason)
        return out.finalPeak
    }

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float, predictedBg: Float, targetBg: Float): Boolean {
        val declaredMeal = work.mealTime || bfastTime || lunchTime || dinnerTime || work.snackTime || highCarbTime
        return decideMealOnsetBehindEffortVeto(
            delta = delta,
            predictedDelta = predictedDelta,
            acceleration = acceleration,
            predictedBg = predictedBg,
            targetBg = targetBg,
            assessment = work.lastEffortAssessment,
            declaredMeal = declaredMeal,
            cobG = work.cob.toDouble(),
        )
    }

    private fun parseNotes(startMinAgo: Int, endMinAgo: Int): String {
        val olderTimeStamp = now - endMinAgo * 60 * 1000
        val moreRecentTimeStamp = now - startMinAgo * 60 * 1000
        var notes = ""
        val recentNotes2: MutableList<String> = mutableListOf()
        val autoNote = determineNoteBasedOnBg(bg)
        recentNotes2.add(autoNote)
        notes += autoNote  // Ajout de la note auto générée

        recentNotes?.forEach { note ->
            if(note.timestamp > olderTimeStamp && note.timestamp <= moreRecentTimeStamp) {
                val noteText = note.note.lowercase()
                if (noteText.contains("sleep") || noteText.contains("sport") || noteText.contains("snack") || noteText.contains("bfast") || noteText.contains("lunch") || noteText.contains("dinner") ||
                    noteText.contains("lowcarb") || noteText.contains("highcarb") || noteText.contains("meal") || noteText.contains("fasting") ||
                    noteText.contains("low treatment") || noteText.contains("less aggressive") ||
                    noteText.contains("more aggressive") || noteText.contains("too aggressive") ||
                    noteText.contains("normal")) {

                    notes += if (notes.isEmpty()) recentNotes2 else " "
                    notes += note.note
                    recentNotes2.add(note.note)
                }
            }
        }

        notes = processNotesAndCleanUp(notes)
        return notes
    }

    /**
     * 🛡️ Log de santé du stockage et des learners AIMI.
     * Affiche l'état du système dans l'UI (Reasoning) ET dans les logs système.
     * NOUVEAU: Populate aussi rT.learnersInfo pour affichage comme section dédiée.
     */
    private fun logLearnersHealth(rT: RT) {
        val storageReport = storage.healthReport()
        val reactivityFactor = safeReactivityFactor // Safety check added
        val basalMultiplier = basalLearner.getMultiplier()

        val healthLines = learnerHealthLines(storageReport, reactivityFactor, basalMultiplier)

        // 📊 NOUVEAU: Afficher en HAUT de la page AIMI via rT.learnersInfo (section dédiée)
        val reactivityPct = (reactivityFactor * 100).toInt()
        val reactivityTrend = when {
            reactivityFactor < 0.5 -> "↓ prudent"
            reactivityFactor > 1.2 -> "↑ agressif"
            else -> "→ neutre"
        }

        val basalTrend = when {
            basalMultiplier < 0.9 -> "↓ basal réduit"
            basalMultiplier > 1.1 -> "↑ basal augmenté"
            else -> "→ basal neutre"
        }

        // ✅ Populate rT.learnersInfo for UI section display (like "Profil :", "Données repas :", etc.)
        rT.learnersInfo = buildString {
            appendLine("UnifiedReactivity: $reactivityPct% ($reactivityTrend)")
            appendLine("BasalLearner: ×${aimiFmt2(basalMultiplier)} ($basalTrend)")
            appendLine("PkPdEstimator: ℹ️ runtime-only")
            append("Storage: $storageReport")
        }

        // Aussi dans consoleLog pour affichage UI (Reasoning)
        healthLines.forEach { line ->
            work.consoleLog.add(line)
        }

        // Logger aussi dans logcat pour debug
        aapsLogger.info(LTag.APS, "╔═══════════════════════════════════════════════╗")
        aapsLogger.info(LTag.APS, "║ 📦 AIMI SYSTEM HEALTH                          ║")
        aapsLogger.info(LTag.APS, "╠═══════════════════════════════════════════════╣")
        aapsLogger.info(LTag.APS, "║ Storage: $storageReport")
        aapsLogger.info(LTag.APS, "║ UnifiedReactivity: ✅ factor=${aimiFmt3(reactivityFactor)}")
        aapsLogger.info(LTag.APS, "║ BasalLearner: ✅ multiplier=${aimiFmt3(basalMultiplier)}")
        aapsLogger.info(LTag.APS, "║ PkPdEstimator: ℹ️ runtime-only")
        aapsLogger.info(LTag.APS, "╚═══════════════════════════════════════════════╝")
    }

    private fun applyGestationalAutopilot(profile: OapsProfileAimi) {
        try {
            if (preferences.get(BooleanKey.OApsAIMIpregnancy)) {
                val dueDateString = preferences.get(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.PregnancyDueDateString)
                if (dueDateString.isNotEmpty()) {
                    try {
                        val dueDate = KxLocalDate.parse(dueDateString)
                        val gState = gestationalAutopilot.calculateState(dueDate)
                        val mult = gestationalAutopilot.getProfileMultipliers(gState)

                        val factorBasal = mult["basal"] ?: 1.0
                        val factorISF = mult["isf"] ?: 1.0
                        val factorCR = mult["cr"] ?: 1.0

                        val oldBasal = profile.current_basal
                        val oldISF = profile.sens
                        val oldCR = profile.carb_ratio

                        profile.current_basal *= factorBasal
                        profile.sens *= factorISF
                        profile.carb_ratio *= factorCR
                        profile.variable_sens *= factorISF

                        aapsLogger.debug(LTag.APS, "🤰 Pregnancy Mode Active: Week ${gState.gestationalWeek} (${gState.description}) -> Basal*${factorBasal}, ISF*${factorISF}")
                        work.consoleLog.add("🤰 GESTATION ACTIVE: ${gState.gestationalWeek.toInt()} SA (${gState.description})")
                        work.consoleLog.add("   └ Factors: Basal x${aimiFmt2(factorBasal)} | ISF x${aimiFmt2(factorISF)} | CR x${aimiFmt2(factorCR)}")
                        work.consoleLog.add("   └ Adjusted: Basal ${aimiFmt2(oldBasal)}->${aimiFmt2(profile.current_basal)} | ISF ${oldISF.toInt()}->${profile.sens.toInt()}")
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "Error parsing pregnancy due date: $dueDateString", e)
                    }
                } else {
                    work.consoleLog.add("🤰 PREGNANCY MODE ON but No Due Date set in WCycle prefs.")
                }
            }
        } catch (e: Exception) {
            work.consoleLog.add("🤰 Error in Gestation logic: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun applyThyroidModule(profile: OapsProfileAimi) {
        work.currentThyroidEffects = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects()
        try {
            thyroidPreferences.update()
            val thyroidInputs = thyroidPreferences.inputsFlow.value
            if (thyroidInputs.isEnabled) {
                thyroidStateEstimator.updateState(thyroidInputs)
                val status = thyroidStateEstimator.currentState.value
                val confidence = thyroidStateEstimator.confidence.value
                work.currentThyroidEffects = thyroidEffectModel.calculateEffects(status, confidence)

                val logMsg = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidDiagnosticsLogger.formatDecisionLog(
                    inputs = thyroidInputs,
                    status = status,
                    effects = work.currentThyroidEffects,
                    confidence = confidence,
                    direction = "INIT",
                    reason = ""
                )
                if (logMsg.isNotBlank()) work.consoleLog.add("🦋 $logMsg")

                if (work.currentThyroidEffects.diaMultiplier != 1.0) {
                     profile.dia *= work.currentThyroidEffects.diaMultiplier
                }
                if (work.currentThyroidEffects.egpMultiplier != 1.0) {
                     profile.current_basal *= work.currentThyroidEffects.egpMultiplier
                }
                if (work.currentThyroidEffects.isfMultiplier != 1.0) {
                     profile.sens *= work.currentThyroidEffects.isfMultiplier
                     profile.variable_sens *= work.currentThyroidEffects.isfMultiplier
                }
            }
        } catch (e: Exception) {
            work.consoleLog.add("🦋 Error in Thyroid logic: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun executeSmbInstruction(
        bg: Double, delta: Float, iob: Float, basalaimi: Float, basal: Double,
        honeymoon: Boolean, hourOfDay: Int,
        mealTime: Boolean, bfastTime: Boolean, lunchTime: Boolean,
        dinnerTime: Boolean, highCarbTime: Boolean, snackTime: Boolean,
        sens: Double, tp: Float, variableSensitivity: Float,
        target_bg: Double, predictedBg: Float, eventualBG: Double,
        isMealAdvisorOneShot: Boolean, mealData: MealData,
        pkpdRuntime: app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime?,
        sportTime: Boolean, causalState.lateFatRiseFlag: Boolean,
        highCarbrunTime: Long, threshold: Double,
        currentTime: Long, windowSinceDoseInt: Int,
        intervalsmb: Int, insulinStep: Float,
        causalState.highBgOverrideUsed: Boolean, cob: Float,
        pkpdDiaMinutesOverride: Double?,
        profile: OapsProfileAimi, rT: RT,
        // Local determine_basal vars — not class fields
        combinedDeltaLocal: Float, glucoseStatusLocal: GlucoseStatusAIMI,
        pumpAgeDaysLocal: Float, modelcalLocal: Double, profileCurrentBasalLocal: Double,
        isConfirmedHighRise: Boolean = false,
        exerciseInsulinLockout: Boolean = false,
        minBgLookbackMgdl: Double = Double.MAX_VALUE,
    ): SmbInstructionExecutor.Result {
        return SmbInstructionExecutor.execute(
            SmbInstructionExecutor.Input(
                rh = rh, preferences = preferences, csvFile = csvfilePath, rT = rT,
                consoleLog = work.consoleLog, consoleError = work.consoleError,
                combinedDelta = combinedDeltaLocal.toDouble(), shortAvgDelta = shortAvgDelta.toFloat(), longAvgDelta = longAvgDelta.toFloat(),
                profile = profile, glucoseStatus = glucoseStatusLocal,
                bg = bg, delta = delta.toDouble(), iob = iob,
                basalaimi = basalaimi, initialBasal = basal,
                honeymoon = honeymoon, hourOfDay = hourOfDay,
                mealTime = mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
                dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = snackTime,
                sleepTime = work.sleepTime,
                recentSteps5Minutes = recentSteps5Minutes, recentSteps10Minutes = recentSteps10Minutes,
                recentSteps30Minutes = recentSteps30Minutes, recentSteps60Minutes = recentSteps60Minutes,
                recentSteps180Minutes = recentSteps180Minutes,
                averageBeatsPerMinute = averageBeatsPerMinute, averageBeatsPerMinute60 = averageBeatsPerMinute60,
                pumpAgeDays = pumpAgeDaysLocal.toInt(),
                sens = sens, tp = tp.toInt(), variableSensitivity = variableSensitivity,
                targetBg = target_bg, predictedBg = predictedBg, eventualBg = eventualBG,
                maxSmb = if (isMealAdvisorOneShot) max(work.maxSMBHB, 10.0)
                    else if ((bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0) ||
                        ((mealTime || lunchTime || dinnerTime || highCarbTime) && bg > 100)) work.maxSMBHB
                    else work.maxSMB,
                maxIob = preferences.get(DoubleKey.ApsSmbMaxIob),
                predictedSmb = work.predictedSMB, modelValue = modelcalLocal.toFloat(),
                mealData = mealData, pkpdRuntime = pkpdRuntime,
                sportTime = sportTime || exerciseInsulinLockout,
                exerciseInsulinLockout = exerciseInsulinLockout,
                causalState.lateFatRiseFlag = causalState.lateFatRiseFlag,
                highCarbRunTime = highCarbrunTime, threshold = threshold,
                dateUtil = dateUtil, currentTime = currentTime,
                windowSinceDoseInt = windowSinceDoseInt, currentInterval = intervalsmb,
                insulinStep = insulinStep,
                causalState.highBgOverrideUsed = causalState.highBgOverrideUsed,
                profileCurrentBasal = profileCurrentBasalLocal,
                cob = cob,
                globalReactivityFactor = if (preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)) {
                    if (isConfirmedHighRise) max(safeReactivityFactor, 1.0) else safeReactivityFactor
                } else 1.0,
                isConfirmedHighRise = isConfirmedHighRise,
                minBgLookbackMgdl = minBgLookbackMgdl,
            ),
            SmbInstructionExecutor.Hooks(
                refineSmb = { combined, short, long, predicted, profileInput ->
                    neuralnetwork5(combined, short, long, predicted, profileInput)
                },
                calculateAdjustedDia = { baseDia, currentHour, steps5, currentHr, avgHr60, pumpAge, iobValue ->
                    val effectiveBaseDia = pkpdDiaMinutesOverride?.let { (it / 60.0).toFloat() } ?: baseDia
                    calculateAdjustedDIA(
                        baseDIAHours = effectiveBaseDia, currentHour = currentHour,
                        pumpAgeDays = pumpAge, iob = iobValue,
                        activityContext = cachedActivityContext ?: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext(),
                        steps = steps5, heartRate = currentHr?.toInt()
                    )
                },
                costFunction = { basalInput, bgInput, targetInput, horizon, sensitivity, candidate ->
                    costFunction(basalInput, bgInput, targetInput, horizon, sensitivity, candidate)
                },
                applySafety = { meal, smb, guard, reasonBuilder, runtime, exercise, suspected, confirmedRise ->
                    applySafetyPrecautions(meal, smb, guard ?: 0.0, reasonBuilder, runtime, exercise, suspected, confirmedRise)
                },
                runtimeToMinutes = { runtimeToMinutes(it!!) },
                computeHypoThreshold = { minBg, lgs -> HypoThresholdMath.computeHypoThreshold(minBg, lgs) },
                isBelowHypo = { bgNow, predictedValue, eventualValue, hypo, deltaValue ->
                    HypoGuard.isBelowHypoThreshold(bgNow, predictedValue, eventualValue, hypo, deltaValue)
                },
                logDataMl = { predicted, given -> logDataMLToCsv(predicted, given) },
                logData = { predicted, given -> logDataToCsv(predicted, given) },
                roundBasal = { value -> roundBasal(value) },
                roundDouble = { value, digits -> round(value, digits) }
            )
        )
    }

    private fun applyBasalFirstPolicy(
        bg: Double, delta: Float, combinedDelta: Float,
        mealData: MealData, autosens_data: AutosensResult,
        isMealAdvisorOneShot: Boolean, targetBg: Double, rT: RT,
        isConfirmedHighRise: Boolean = false
    ) {
        val learnerFactor = safeReactivityFactor
        val autosensResistance = autosens_data.ratio < 0.8
        val isLearnerPrudent = learnerFactor < 0.75 && !autosensResistance
        val basalFirstMealActive = mealData.mealCOB > 0.1
        val basalFirstHeavyMeal = mealData.mealCOB > 20.0
        val isPersistentRise = bg > targetBg && combinedDelta >= 0.3f

        // 🛡️ Basal-First Policy: bypassed if the rise is confirmed, if a meal rise is anticipated,
        // or if BG is above 110. See [BasalFirstPolicyMath] for the decision math.
        val decision = BasalFirstPolicyMath.decide(
            bg = bg,
            delta = delta,
            combinedDelta = combinedDelta,
            mealCob = mealData.mealCOB,
            autosensRatio = autosens_data.ratio,
            learnerFactor = learnerFactor,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            targetBg = targetBg,
            isConfirmedHighRise = isConfirmedHighRise,
        )
        val basalFirstActive = decision.active
        val isFragileBg = decision.fragileBg

        this.cachedBasalFirstActive = basalFirstActive
        this.cachedIsFragileBg = isFragileBg
        if (basalFirstActive) {
            work.maxSMB = 0.0; work.maxSMBHB = 0.0
            val reason = when (decision.reason) {
                BasalFirstPolicyMath.Reason.FRAGILE_BG       -> "Fragile BG (<110 & falling)"
                BasalFirstPolicyMath.Reason.LEARNER_PRUDENCE -> "Learner Prudence (Factor=${aimiFmt2(learnerFactor)})"
                else                                         -> "Unknown Safety Trigger"
            }
            work.consoleLog.add("🛡️ BASAL-FIRST ACTIVE: $reason -> SMB DISABLED")
            rT.reason.append(" [Basal-First: SMB OFF]")
        } else {
            if (autosensResistance && learnerFactor < 0.75)
                work.consoleLog.add("⚡ RESISTANCE EXEMPTION: Autosens ${aimiFmt2(autosens_data.ratio)} < 0.8")
            if (isLearnerPrudent && basalFirstMealActive)
                work.consoleLog.add("🍕 MEAL EXEMPTION: COB=${aimiFmt1(mealData.mealCOB)}g")
            if (isLearnerPrudent && isPersistentRise)
                work.consoleLog.add("📈 RISE EXEMPTION: CombinedDelta=${aimiFmt1(combinedDelta)}")
            if (isFragileBg && basalFirstHeavyMeal)
                work.consoleLog.add("🍔 HEAVY MEAL EXEMPTION: COB=${aimiFmt1(mealData.mealCOB)}g")
            if (decision.anticipatedRise) {
                work.consoleLog.add(
                    "🚀 ANTICIPATED RISE EXEMPTION: Δ=${aimiFmt1(delta)} combΔ=${aimiFmt1(combinedDelta)} " +
                        "BG=${aimiFmt0(bg)} proj30=${aimiFmt0(decision.projectedBgMgdl)} " +
                        "> target+${aimiFmt0(BasalFirstPolicyMath.ANTICIPATED_RISE_TARGET_MARGIN_MGDL)} " +
                        "(${aimiFmt0(targetBg)}) -> SMB ceiling kept"
                )
                rT.reason.append(" [Basal-First: rise exemption]")
            }
        }
    }

    internal fun applyLegacyMealModes(profile: OapsProfileAimi, rT: RT, currenttemp: CurrentTemp, modeTbrLimit: Double): RT? {
        val state = LegacyMealTickState(
            mealTime = work.mealTime,
            mealRuntimeMin = work.mealruntime,
            bfastTime = bfastTime,
            bfastRuntimeMin = work.bfastruntime,
            lunchTime = lunchTime,
            lunchRuntimeMin = work.lunchruntime,
            dinnerTime = dinnerTime,
            dinnerRuntimeMin = work.dinnerruntime,
            highCarbTime = highCarbTime,
            highCarbRuntimeMin = work.highCarbrunTime,
            snackTime = work.snackTime,
            snackRuntimeMin = work.snackrunTime,
            fclTime = fclTime,
            sportTime = work.sportTime,
            fclRuntimeMin = fclruntime,
            iob = work.iob,
            maxIob = work.maxIob,
            bg = bg,
            hasHypoRecovery = work.lastContextSnapshot?.hasHypoRecovery == true,
            postHypo = work.lastPostHypoDeliveryAuthority,
            lastBolusSmbUnit = work.lastBolusSMBUnit,
            lastSmbCapped = work.lastSmbCapped,
            lastSmbFinal = work.lastSmbFinal,
        )
        val result = decideLegacyMealModes(
            profile = profile,
            rT = rT,
            currenttemp = currenttemp,
            modeTbrLimit = modeTbrLimit,
            preferences = preferences,
            dateUtil = dateUtil,
            texts = rh,
            notifications = notificationManager,
            effects = legacyEffectSink,
            smbAction = legacySmbAction,
            latestSmb = legacyLatestSmb,
            log = work.consoleLog,
            state = state,
        )
        work.lastBolusSMBUnit = state.lastBolusSmbUnit
        work.lastSmbCapped = state.lastSmbCapped
        work.lastSmbFinal = state.lastSmbFinal
        return result
    }

    private fun applyEndoAndActivityAdjustments(
        bg: Double, delta: Float,
        mealTime: Boolean, bfastTime: Boolean, lunchTime: Boolean,
        dinnerTime: Boolean, highCarbTime: Boolean, snackTime: Boolean,
        recentSteps5Minutes: Int, recentSteps10Minutes: Int,
        averageBeatsPerMinute: Double, averageBeatsPerMinute60: Double
    ) {
        val endoFactors = endoAdjuster.calculateFactors(bg, delta.toDouble())
        if (endoFactors.reason.isNotEmpty()) {
            work.consoleLog.add("Endo: ${endoFactors.reason} (Basal x${endoFactors.basalMult}, SMB x${endoFactors.smbMult}, ISF x${endoFactors.isfMult})")
            this.variableSensitivity *= endoFactors.isfMult.toFloat()
            work.basalaimi *= endoFactors.basalMult.toFloat()
        }
        work.endoSmbMult = endoFactors.smbMult  // Persist for downstream SMB dampening
        val activityContext = activityManager.process(
            steps5min = recentSteps5Minutes, steps10min = recentSteps10Minutes,
            avgHr = averageBeatsPerMinute, avgHrResting = averageBeatsPerMinute60
        )
        if (activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST || activityContext.isRecovery) {
            work.consoleLog.add("Activity: ${activityContext.description} → ISF x${aimiFmt2(activityContext.isfMultiplier)}")
        }
        this.variableSensitivity *= activityContext.isfMultiplier.toFloat()
        if (activityContext.protectionMode) work.consoleLog.add("Activity Protection Mode Active (Recovery/Intense)")
        work.activityProtectionMode = activityContext.protectionMode
        work.activityStateIntense = (activityContext.state == app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE)
        this.cachedActivityContext = activityContext
        val anyMealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        var basalFactor = when (activityContext.state) {
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST  -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.LIGHT -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.MODERATE -> if (anyMealModeActive) 0.9f else 0.8f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE  -> if (anyMealModeActive) 0.8f else 0.6f
        }
        if (work.exerciseHyperBasalOverrideActive) {
            val strongRise = delta >= 3.0f || work.tickCombinedDelta >= 3.0f
            val resolved = app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy.resolveBasalFactor(
                basalFactor,
                overrideActive = true,
                strongRise = strongRise,
            )
            if (resolved != basalFactor) {
                work.consoleLog.add(
                    "🏃 HYPER_EXERCISE_OVERRIDE: basal x${aimiFmt2(basalFactor)} → x${aimiFmt2(resolved)} " +
                        "(BG=${bg.toInt()} Δ=${aimiFmt1(delta)})"
                )
                basalFactor = resolved
            }
        }
        if (basalFactor != 1.0f) {
            work.basalaimi *= basalFactor
            work.consoleLog.add("Basal Activity Redux: x${aimiFmt2(basalFactor)} -> ${aimiFmt2(work.basalaimi)}U/h")
        }
    }

    private fun refreshAimiContextActivityFlag(nowMs: Long = aimiWallClockMs()) {
        work.aimiContextActivityActive = false
        if (!preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled)) return
        try {
            val snap = contextManager.getSnapshot(nowMs)
            work.aimiContextActivityActive = snap.hasActivity && snap.intentCount > 0
        } catch (_: Exception) {
            work.aimiContextActivityActive = false
        }
    }

    private fun refreshExerciseHyperBasalOverride(profile: OapsProfileAimi) {
        work.exerciseHyperBasalOverrideActive = false
        if (!work.exerciseInsulinLockoutActive) return
        val policyInput = app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy.buildInput(
            bgMgdl = bg.toDouble(),
            targetBgMgdl = profile.target_bg,
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = delta.toDouble(),
            shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
            combinedDeltaMgdlPer5 = work.tickCombinedDelta.toDouble(),
            thyroidEgpMultiplier = work.currentThyroidEffects.egpMultiplier,
        )
        work.exerciseHyperBasalOverrideActive =
            app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy
                .isHyperRisingDuringExercise(policyInput)
        if (work.exerciseHyperBasalOverrideActive) {
            work.consoleLog.add(
                "🏃 HYPER_EXERCISE_OVERRIDE active: BG=${bg.toInt()} Δ=${aimiFmt1(delta)} " +
                    "combΔ=${aimiFmt1(work.tickCombinedDelta)} thyroidEGP=${aimiFmt2(work.currentThyroidEffects.egpMultiplier)}"
            )
        }
    }

    /**
     * Sensor-driven effort belief (reduction-only, opt-in). Computes [EffortActivityBelief] from
     * steps/HR — **independent of any declared AIMI Context activity intent** (the gap that let
     * insulin flow while walking) — and stores it in [lastEffortAssessment]. The actual SMB reduction
     * is applied **once, at the universal exit [finalizeAndCapSMB]**, so no later `maxSMB` reset
     * (meal-advisor one-shot, drift terminator, physio-latent refresh) can silently bypass it.
     * Fail-safe: only lowers SMB, never under a stress posture, never on explicit user actions. Basal
     * damping / HRV / RBT+Harmonia authority are deferred (docs/AIMI_ARCHITECTURE_MAP.md §11.6).
     */
    /**
     * T3C dependency hub: when the user has MANUALLY enabled T3C (brittle) mode, a defined whitelist of **non-SMB**
     * assistance features is treated as ON even if their own toggle is off — so T3C basal management gets the
     * physiological signals it needs. Read-time only: never mutates the stored pref, auto-reverts when T3C is off,
     * and is NEVER used to enable SMB (T3C keeps SMB=0). T3C activation itself stays a manual choice.
     */
    private fun t3cModeEnabled(): Boolean = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)

    /**
     * Depth-scaled post-hypo protection window: a LIGHT hypo (nadir 60-70) keeps hypo-recovery dampening active for
     * 30 min; a DEEPER hypo (nadir < 60) for 45 min. The offending reading ages out of its own lookback → protection
     * releases by itself. Expressed with the existing recent-floor primitive (no new state). Meal/rise release stays
     * the caller's job (e.g. `&& !autodriveMealSignals`), so a meal always hands control back to rise management.
     * Aggressive rise exit ([PostHypoAggressiveRiseExit]: bg ≥ target+30 and Δ>15) also releases protection.
     */
    private fun postHypoRecoveryActive(): Boolean {
        val target = targetBg.toDouble().takeIf { it > 0.0 } ?: 100.0
        if (PostHypoAggressiveRiseExit.shouldExit(bg, target, delta.toDouble())) return false
        return minBgInLastMinutes(45) < 60.0 || minBgInLastMinutes(30) < 70.0
    }

    private fun refreshEffortActivityBelief() {
        work.lastEffortAssessment = null
        // Dependency: under T3C, activity awareness is required for the physio-informed basal (workstream C).
        // Effort protection is reduce-only, so this never adds insulin.
        val protection = preferences.get(BooleanKey.OApsAIMIEffortActivityProtection)
        val t3c = t3cModeEnabled()
        if (!protection && !t3c) return
        val signal = readRbtOptional<HealthContextSnapshot>(
            source = "wearableSnapshot",
            consoleLog = work.consoleLog,
            failureLine = { errorType, message ->
                "WEARABLE snapshot failed ($errorType): ${message.orEmpty()} — snapshot empty"
            },
        ) { physioAdapter.getLatestSnapshot() }
        val snapshot = when (signal) {
            is OptionalSignal.Ready -> signal.value
            is OptionalSignal.Failed -> null
        }
        val refresh = decideRefreshEffortActivityBelief(
            protectionEnabled = protection,
            t3cEnabled = t3c,
            snapshot = snapshot,
            nowMs = dateUtil.now(),
            stressResistanceProb = causalState.lastPhysioLatentState?.transientResistanceProb ?: 0.0,
            prior = causalState.lastEffortMemory,
        )
        causalState.lastEffortMemory = refresh.memory
        work.lastEffortAssessment = refresh.assessment
        refresh.logLine?.let { work.consoleLog.add(it) }
    }

    /**
     * True when a BG rise should be read as EFFORT / post-effort adrenaline, NOT an undeclared meal. Uses the
     * effort belief's EXERTION posture in ACTIVE **or** RECENT_EFFORT state — its ~120-min memory covers the
     * post-effort adrenaline window (residual adrenaline can push BG up after exercise). Vetoes only the
     * *undeclared* interpretation: a declared meal mode or real COB is still treated as a meal. Fail-safe —
     * this only suppresses an insulin escalation, never adds insulin. See [detectMealOnset] / [inferredMealSafetyIntent].
     */
    private fun effortSuppressesUndeclaredMeal(): Boolean {
        val declaredMeal = work.mealTime || bfastTime || lunchTime || dinnerTime || work.snackTime || highCarbTime
        return decideEffortSuppressesUndeclaredMeal(
            assessment = work.lastEffortAssessment,
            declaredMeal = declaredMeal,
            cobG = work.cob.toDouble(),
        )
    }

    /**
     * Is the effort belief reporting movement **now**, rather than remembering it?
     *
     * `effortSuppressesUndeclaredMeal` accepts both `ACTIVE` and `RECENT_EFFORT`, so the veto it raises
     * cannot tell a walk in progress from a two-hour-old memory of one. Measured over 24 h: the effort
     * multiplier removed 21.71 U, and **61 of the 82 reduced ticks had `effort = 0.00`** — no live
     * movement at all, a median of 12 steps per 15 min.
     *
     * `MealCertainty` uses this to decide whether the veto may be overridden by an unambiguous glucose
     * rise. Only a memory can be overridden; live movement keeps the protection whatever glucose does.
     *
     * Fails safe: no assessment is treated as live, so the override stays shut.
     *
     * @return true when the effort state is `ACTIVE`, or when no assessment is available.
     */
    private fun effortIsLiveMovement(): Boolean {
        val a = work.lastEffortAssessment ?: return true
        return a.state == EffortActivityBelief.State.ACTIVE
    }

    /**
     * Contexte utilisateur (intentions actives) : peut moduler SMB / intervalle / préférence basale vs SMB.
     * @return Surcharge cible glycémique (ex. sport ~150 mg/dL) ou null.
     */
    private fun applyContextModule(
        bg: Double, iob: Double, cob: Double, rT: RT
    ): Double? = decideApplyContextModule(
        bg = bg,
        iob = iob,
        cob = cob,
        rT = rT,
        preferences = preferences,
        engine = contextInfluenceEngine,
        consoleLog = work.consoleLog,
        calls = object : AimiContextModuleCalls {
            override fun writeSmbCeiling(value: Double?) {
                work.lastContextSmbCeilingU = value
            }
            override fun writeSuppressSmb(value: Boolean) {
                work.lastContextSuppressSmb = value
            }
            override fun snapshot() = contextManager.getSnapshot(aimiWallClockMs())
            override fun writeSnapshot(snapshot: ContextSnapshot) {
                work.lastContextSnapshot = snapshot
            }
            override fun writeActivityActive(value: Boolean) {
                work.aimiContextActivityActive = value
            }
            override fun contextModeName() = preferences.get(app.aaps.core.keys.StringKey.ContextMode)
            override fun maxSmb() = work.maxSMB
            override fun writeMaxSmb(value: Double) {
                work.maxSMB = value
            }
            override fun maxSmbHb() = work.maxSMBHB
            override fun writeMaxSmbHb(value: Double) {
                work.maxSMBHB = value
            }
            override fun intervalSmb() = intervalsmb
            override fun writeIntervalSmb(value: Int) {
                intervalsmb = value
            }
            override fun smbScaleLine(original: Double, updated: Double, factor: Float) =
                "  SMB: ${aimiFmt2(original)}→${aimiFmt2(updated)}U (×${aimiFmt2(factor)})"
            override fun intervalLine(original: Int, updated: Int, extra: Int) =
                "  Interval: $original→${updated}min (+$extra)"
            override fun exerciseHyperOverride() = work.exerciseHyperBasalOverrideActive
            override fun sportTime() = work.sportTime
            override fun activityActive() = work.aimiContextActivityActive
            override fun writeExerciseLockout(value: Boolean) {
                work.exerciseInsulinLockoutActive = value
            }
            override fun logContextFailure(error: Exception) {
                aapsLogger.error(LTag.APS, "Context Module failed", error)
            }
        },
    )

    /**
     * 🍽️ Bounded virtual COB for an undeclared meal (Option A — feeds prediction/TBR anticipation only,
     * never SMB). Returns 0.0 unless [BooleanKey.OApsAIMIUndeclaredCobEnabled] is on, no explicit carbs
     * are already present, and all [UndeclaredCobEstimator] safety gates pass. Read-only w.r.t. the async
     * physio / Ra state (same snapshots the tick already refreshed).
     */
    private fun estimateUndeclaredVirtualCob(
        bg: Double,
        delta: Float,
        sens: Double,
        profile: OapsProfileAimi,
        mealData: MealData,
        declaredOrAdvisorCob: Double,
    ): Double = decideEstimateUndeclaredVirtualCob(
        enabled = preferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled),
        declaredOrAdvisorCob = declaredOrAdvisorCob,
        consoleLog = work.consoleLog,
    ) {
        // Explicit carbs already returned above. These reads are the snapshot and the preferences.
        val snapshot = physioAdapter.getLatestSnapshot()
        val cfrdExacerbationActive =
            preferences.get(BooleanKey.OApsAIMIT3cCfrdMode) &&
                preferences.get(BooleanKey.OApsAIMIT3cCfrdExacerbationMode)
        undeclaredVirtualCobInput(
            snapshot = snapshot,
            estimatedRaMgdlPerMin = continuousStateEstimator.getLastRa(),
            isfMgdlPerU = sens,
            carbRatioGPerU = profile.carb_ratio,
            bgMgdl = bg,
            deltaMgdl5m = delta.toDouble(),
            slopeFromMinDeviation = mealData.slopeFromMinDeviation,
            patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
            tdd24hU = resolveTdd24hForExport(),
            activityContextActive = work.aimiContextActivityActive,
            mealProb = causalState.lastPhysioLatentState?.mealProb ?: 0.0,
            falseMealSuppression = causalState.lastPhysioLatentState?.falseMealSuppression ?: false,
            exerciseLockoutActive = work.exerciseInsulinLockoutActive,
            postHypoActive = work.lastPostHypoDeliveryAuthority.active,
            cfrdExacerbationActive = cfrdExacerbationActive,
            maxGramsPref = preferences.get(DoubleKey.OApsAIMIUndeclaredCobMaxG),
        )
    }

    private fun applyAdvancedPredictions(
        bg: Double, delta: Float, sens: Double,
        iob_data_array: Array<IobTotal>,
        mealData: MealData, profile: OapsProfileAimi, rT: RT
    ) = decideApplyAdvancedPredictions(
        bg = bg,
        delta = delta,
        sens = sens,
        iobDataArray = iob_data_array,
        mealData = mealData,
        profile = profile,
        rT = rT,
        preferences = preferences,
        consoleLog = work.consoleLog,
        calls = object : AimiAdvancedPredictionCalls {
            override fun nowMs() = dateUtil.now()
            override fun virtualCob(
                bg: Double,
                delta: Float,
                sens: Double,
                profile: OapsProfileAimi,
                mealData: MealData,
                declaredOrAdvisorCob: Double,
            ) = estimateUndeclaredVirtualCob(bg, delta, sens, profile, mealData, declaredOrAdvisorCob)
            override fun recordSoftFloor(curves: AdvancedPredictionCurves) = recordPkpdSoftFloor(curves)
            override fun writeCurves(curves: AdvancedPredictionCurves) {
                work.lastAdvancedPredictionCurves = curves
            }
            override fun writePredictionSize(size: Int) {
                work.lastPredictionSize = size
            }
            override fun writePredictionAvailable(available: Boolean) {
                work.lastPredictionAvailable = available
            }
            override fun writeEventualSnapshot(value: Double) {
                work.lastEventualBgSnapshot = value
            }
            override fun writePredictedBg(value: Float) {
                this@DetermineBasalaimiSMB2.predictedBg = value
            }
            override fun logError(message: String) {
                work.consoleError.add(message)
            }
        },
    )


    private fun applyTrajectoryAnalysis(
        currentTime: Long, bg: Double, delta: Double, bgacc: Double, iobActivityNow: Double,
        iob: Float, insulinActionState: app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState,
        lastBolusAgeMinutes: Double, cob: Float, targetBg: Double, profile: OapsProfileAimi,
        rT: RT, uiInteraction: UiInteraction,
        relevanceScore: Double = 0.0 // 🌀 Relevance from Cosine Gate
    ) {
        decideTrajectoryAnalysis(
            currentTime = currentTime,
            bg = bg,
            delta = delta,
            bgacc = bgacc,
            iobActivityNow = iobActivityNow,
            iob = iob,
            insulinActionState = insulinActionState,
            lastBolusAgeMinutes = lastBolusAgeMinutes,
            cob = cob,
            targetBg = targetBg,
            profile = profile,
            rT = rT,
            relevanceScore = relevanceScore,
            preferences = preferences,
            consoleLog = work.consoleLog,
            calls = object : AimiTrajectoryCalls {
                override fun refreshEffectiveProfile(currentTime: Long) {
                    effectiveProfileCached(currentTime)
                }
                override fun trajectoryHistory(
                    currentTime: Long,
                    bg: Double,
                    delta: Double,
                    bgacc: Double,
                    iobActivityNow: Double,
                    iob: Float,
                    insulinActionState: app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState,
                    lastBolusAgeMinutes: Double,
                    cob: Float,
                    profile: OapsProfileAimi,
                ) = trajectoryHistoryCached(
                    currentTime, bg, delta, bgacc, iobActivityNow, iob, insulinActionState,
                    lastBolusAgeMinutes, cob, profile,
                )
                override fun analyze(
                    history: List<app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState>,
                    orbit: app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit,
                ) = trajectoryGuard.analyzeTrajectory(history, orbit)
                override fun uamConfidence() = AimiUamHandler.confidenceOrZero()
                override fun maxSmb() = work.maxSMB
                override fun setMaxSmb(value: Double) {
                    work.maxSMB = value
                }
                override fun maxSmbHb() = work.maxSMBHB
                override fun setMaxSmbHb(value: Double) {
                    work.maxSMBHB = value
                }
                override fun intervalSmb() = this@DetermineBasalaimiSMB2.intervalsmb
                override fun setIntervalSmb(value: Int) {
                    this@DetermineBasalaimiSMB2.intervalsmb = value
                }
                override fun maxIob() = work.maxIob
                override fun setMaxIob(value: Double) {
                    work.maxIob = value
                }
                override fun postTrajectoryWarning(message: String) {
                    notificationManager.post(
                        id = app.aaps.core.interfaces.notifications.NotificationId.AUTOMATION_MESSAGE,
                        text = message,
                    )
                }
                override fun logTrajectoryFailure(error: Exception) {
                    aapsLogger.error(LTag.APS, "Trajectory Guard failed", error)
                }
            },
        )
    }

    /**
     * Corps du tick AIMI — ordre figé § **Carte P3a** (`orchestration/AIMI_ORCHESTRATION_ROADMAP.md`).
     * Point d’entrée public : [determine_basal] → [AimiDetermineBasalTickOrchestrator.run] → ici (P3b).
     *
     * @see app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiDetermineBasalTickOrchestrator
     */
    /**
     * 🔭 Lot 0 — enveloppe d'export. [runDetermineBasalTickInner] possède **quatorze** points de sortie
     * (abort glucose, verrou exercice, modes repas manuels, T3C, stale, halt LGS TIER1, Meal Advisor,
     * HARD_BRAKE, compression/drift, meal-hyper boost, arrêt basal hypo, TBR précoce repas, MAX_IOB, plus
     * la queue principale) et seuls **deux** appelaient le stage d'export : les douze autres décidaient,
     * dosaient et entraînaient les learners sans laisser la moindre ligne dans `AIMI_Decisions.jsonl`.
     *
     * Ce biais n'était pas neutre : il portait précisément sur les descentes basses (HARD_BRAKE exige
     * `bg < targetBg + 10`, l'arrêt hypo `bg < 85`, le halt LGS `bg < seuil`) et sur les décisions au
     * basal le plus élevé (les 30 premières minutes de tout mode repas manuel, qui posent la TBR à
     * `meal_modes_MaxBasal`). Toute statistique de fréquence tirée du JSONL héritait de cette censure.
     *
     * L'export reste **idempotent** : les deux sites historiques positionnent
     * [aimiDecisionExportedThisTick], et cette enveloppe ne fait que rattraper les sorties qui n'ont rien
     * écrit. Un échec d'export ne doit jamais compromettre la décision, d'où le `runCatching`.
     */
    /**
     * Previous-tick meal delta, latched on first read so any later read within the same tick sees the
     * same value. `null` is a legitimate latched value, hence the explicit flag rather than `?:`.
     */
    private fun mealAbsorptionDeltaPrevOfTick(): Double? {
        if (!work.mealAbsorptionDeltaPrevLatched) {
            work.mealAbsorptionDeltaPrevForTick = MealAbsorptionMemory.lastDeltaMgdlPer5
            work.mealAbsorptionDeltaPrevLatched = true
        }
        return work.mealAbsorptionDeltaPrevForTick
    }

    internal fun runDetermineBasalTick(ctx: AimiTickContext): RT {
        work.raEstimatorRunCountAtTickStart = continuousStateEstimator.runCount
        work.mealAbsorptionDeltaPrevForTick = null
        work.mealAbsorptionDeltaPrevLatched = false
        work.lastHtrRaFloorMgdlPerMin = null
        work.smbTerminalSealed = false
        work.smbSealRefusedCount = 0
        work.smbSealRefusedTotalU = 0.0
        work.smbSealAllowedRaiseCount = 0
        work.raNetCombinedDelta = shortAvgDelta
        work.raNetShortAvgDeltaAdj = shortAvgDelta
        smbTrainingRowTickKey = ctx.currentTime
        val result = try {
            val inner = runDetermineBasalTickInner(ctx)
            observeRaIfNotAlreadyRun(
                ctx = ctx,
                combinedDelta = work.raNetCombinedDelta,
                shortAvgDeltaAdj = work.raNetShortAvgDeltaAdj,
                pkpdRuntime = cachedPkpdRuntime,
                hasRecentMealEstimate = false,
                reason = "tick_net",
            )
            inner
        } finally {
            // One training row per tick, written after the dose is decided rather than in the middle
            // of it.
            //
            // In `finally`, not as a plain statement: `AimiLoopTickRecovery` exists because the inner
            // tick does throw, and a staged row lost on those ticks would bias the training set away
            // from exactly the anomalous ones. Under the previous immediate write, that row was
            // already on disk.
            //
            // Unconditional, too: the engaged branch stages its row inside `AutodriveEngine.tick`, and
            // `observeRaIfNotAlreadyRun` returns early on exactly those ticks — flushing from inside
            // it would drop every engaged row.
            runCatching { autodriveEngine.flushTickRow(ctx.currentTime) }
        }
        exportAimiDecisionIfNotYetExported(ctx, result)
        return result
    }

    /**
     * Filet de rattrapage de l'export JSONL, appelé une fois par tick depuis [runDetermineBasalTick].
     *
     * Utilise [cachedPkpdRuntime] plutôt que la variable locale du tick : sur une sortie anticipée le
     * runtime pkpd local peut ne pas encore exister, et le champ porte alors la dernière valeur connue
     * (ou `null`, que le stage accepte).
     */
    private fun exportAimiDecisionIfNotYetExported(ctx: AimiTickContext, finalResult: RT) {
        if (work.aimiDecisionExportedThisTick) return
        val decisionCtx = work.pendingDecisionCtxForExport ?: return
        runCatching {
            runAimiSnapshotMedicalJsonAndHormonitorExportStage(
                ctx = ctx,
                profile = ctx.profile,
                decisionCtx = decisionCtx,
                finalResult = finalResult,
                pkpdRuntime = cachedPkpdRuntime,
            )
        }.onFailure { e ->
            work.consoleError.add("AIMI decision export (early-exit path) failed: ${e.message}")
            aapsLogger.error(LTag.APS, "AIMI decision export (early-exit path) failed", e)
        }
    }

    private fun runDetermineBasalTickInner(ctx: AimiTickContext): RT {
        val profile = ctx.profile
        // Causal censoring of the basal label needs the carbs of THIS tick, and the basal-learning hook
        // runs on paths that have no access to `ctx`. See [basalLearningCobGrams].
        work.tickCobGrams = ctx.mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
        val continued = when (
            val prefix = decideDetermineBasalTickPrefix(
                ctx,
                profile,
                object : AimiTickPrefixCalls<AimiDecisionContext> {
                    override fun earlyStages(ctx: AimiTickContext): AimiEarlyTickOutcome {
                        val state = runEarlyDetermineBasalStages(ctx)
                        return AimiEarlyTickOutcome(
                            originalProfile = state.originalProfile,
                            isExplicitAdvisorRun = state.isExplicitAdvisorRun,
                            tdd7P = state.tdd7P,
                            tdd7Days = state.tdd7Days,
                        )
                    }
                    override fun bootstrapPhysiology(ctx: AimiTickContext, tdd7Days: Double) =
                        bootstrapPhysiologyAfterEarlyTick(ctx, tdd7Days)
                    override fun writeConfirmedHighRise(value: Boolean) {
                        work.isConfirmedHighRiseThisTick = value
                    }
                    override fun decisionContext(ctx: AimiTickContext) =
                        buildDecisionContextInitRtSosAndFlatShadow(ctx)
                    override fun realtimePhysio(ctx: AimiTickContext, decisionCtx: AimiDecisionContext): AimiPrefixIob {
                        val bundle = runRealtimePhysioIobProfilerAndInsulinObserver(ctx, decisionCtx)
                        return AimiPrefixIob(
                            bundle.iobTotal,
                            bundle.iobPeakMinutes,
                            bundle.iobActivityIn30Min,
                            bundle.insulinActionState,
                        )
                    }
                    override fun loadGlucose(ctx: AimiTickContext, rT: RT) = when (
                        val outcome = ensureWCycleAndLoadGlucoseStatusOrAbort(ctx, rT)
                    ) {
                        is AimiGlucosePackLoadOutcome.Abort -> AimiPrefixGlucose.Abort(outcome.returnValue)
                        is AimiGlucosePackLoadOutcome.Continue ->
                            AimiPrefixGlucose.Continue(outcome.glucoseStatus, outcome.aimiBgFeatures)
                    }
                    override fun t9Bootstrap(
                        ctx: AimiTickContext,
                        glucoseStatus: GlucoseStatusAIMI,
                        rT: RT,
                        iobTotal: Double,
                    ) = runT9PhysioEarlyPkpdAndTubeBootstrap(ctx, glucoseStatus, rT, iobTotal)
                    override fun cachedPkpdRuntime() = this@DetermineBasalaimiSMB2.cachedPkpdRuntime
                    override fun combinedDelta(
                        ctx: AimiTickContext,
                        glucoseStatus: GlucoseStatusAIMI,
                        useLegacyDynamics: Boolean,
                        reasonAimi: StringBuilder,
                    ): AimiPrefixCombined {
                        val tick = runCombinedDeltaByodaAndDynamicPeak(
                            ctx,
                            glucoseStatus,
                            useLegacyDynamics,
                            reasonAimi,
                        )
                        return AimiPrefixCombined(tick.combinedDelta, tick.shortAvgDeltaAdj, tick.tp)
                    }
                    override fun autodriveBootstrap(ctx: AimiTickContext): AimiPrefixAutodrive {
                        val boot = buildPreTherapyAutodriveByodaBootstrap(ctx)
                        return AimiPrefixAutodrive(boot.isG6Byoda, boot.autodriveEnabled, boot.autodriveDisplay)
                    }
                    override fun tickClock(
                        ctx: AimiTickContext,
                        glucoseStatus: GlucoseStatusAIMI,
                        rT: RT,
                        combinedDelta: Float,
                    ) = runTickClockMaxSmbTirCarbAndGlucoseCopy(ctx, glucoseStatus, rT, combinedDelta)
                    override fun therapyGate(ctx: AimiTickContext, profile: OapsProfileAimi, rT: RT): AimiPrefixStep<Boolean> =
                        when (val gate = runTherapyHydrateClocksAndExerciseLockoutGate(ctx, profile, rT)) {
                            is AimiTherapyExerciseGate.ReturnEarly -> AimiPrefixStep.Stop(gate.result)
                            is AimiTherapyExerciseGate.Continue -> AimiPrefixStep.Go(gate.nightbis)
                        }
                    override fun recentGlucose() = glucoseStatusCalculatorAimi.getRecentGlucose()
                    override fun refreshPostHypo(
                        combinedDelta: Float,
                        recentBGs: List<Float>,
                        shortAvgDeltaAdj: Float,
                        slopeFromMinDeviation: Double,
                        reason: StringBuilder,
                    ) = refreshPostHypoDeliveryAuthorityForTick(
                        combinedDelta = combinedDelta,
                        recentBGs = recentBGs,
                        shortAvgDeltaAdj = shortAvgDeltaAdj,
                        slopeFromMinDeviation = slopeFromMinDeviation,
                        reason = reason,
                    )
                    override fun auditorIsf(ctx: AimiTickContext) = decideAuditorIsfFactorForTick(ctx)
                    override fun mealModes(ctx: AimiTickContext, profile: OapsProfileAimi, rT: RT): AimiPrefixStep<String> =
                        when (val gate = runManualMealModesAfterTherapyGate(ctx, profile, rT)) {
                            is AimiManualMealModesGate.ReturnEarly -> AimiPrefixStep.Stop(gate.rT)
                            is AimiManualMealModesGate.Continue -> AimiPrefixStep.Go(gate.activeModeName)
                        }
                    override fun t3cBrittle(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        originalProfile: OapsProfileAimi,
                        pkpdRuntime: PkPdRuntime?,
                        shortAvgDeltaAdj: Float,
                        physioMultipliers: PhysioMultipliersMTR,
                        insulinActionState: InsulinActionState,
                    ) = runT3cBrittleBypassOrReturn(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        originalProfile = originalProfile,
                        pkpdRuntime = pkpdRuntime,
                        shortAvgDeltaAdj = shortAvgDeltaAdj,
                        physioMultipliers = physioMultipliers,
                        insulinActionState = insulinActionState,
                    )
                },
            )
        ) {
            is AimiTickPrefixOutcome.ReturnEarly -> return prefix.rT
            is AimiTickPrefixOutcome.T3cReturn -> {
                // T3C returns early, before the shared decision-export tail. The export stays here.
                runCatching {
                    runAimiSnapshotMedicalJsonAndHormonitorExportStage(
                        ctx,
                        profile,
                        prefix.decisionCtx,
                        prefix.rT,
                        prefix.pkpdRuntime,
                    )
                }.onFailure { aapsLogger.error(LTag.APS, "T3C decision export failed", it) }
                return prefix.rT
            }
            is AimiTickPrefixOutcome.Continue -> prefix
        }
        val originalProfile = continued.originalProfile
        val isExplicitAdvisorRun = continued.isExplicitAdvisorRun
        val tdd7P = continued.tdd7P
        val tdd7Days = continued.tdd7Days
        val isConfirmedHighRiseLocal = continued.isConfirmedHighRiseLocal
        val decisionCtx = continued.decisionCtx
        val rT = continued.rT
        val flatBGsDetected = continued.flatBGsDetected
        val iobTotal = continued.iobTotal
        val iobPeakMinutes = continued.iobPeakMinutes
        val iobActivityIn30Min = continued.iobActivityIn30Min
        val insulinActionState = continued.insulinActionState
        val glucoseStatus = continued.glucoseStatus
        val f = continued.features
        val pumpAgeDays = continued.pumpAgeDays
        val physioMultipliers = continued.physioMultipliers
        var pkpdRuntime = continued.pkpdRuntime
        val reasonAimi = continued.reasonAimi
        val combinedDelta = continued.combinedDelta
        val shortAvgDeltaAdj = continued.shortAvgDeltaAdj
        val tp = continued.tp
        val isG6Byoda = continued.isG6Byoda
        val autodrive = continued.autodrive
        val autodriveDisplay = continued.autodriveDisplay
        val honeymoon = continued.honeymoon
        val ngrConfig = continued.ngrConfig
        val tir1DAYIR = continued.tir1DAYIR
        val lastHourTIRAbove = continued.lastHourTIRAbove
        val tirbasal3IR = continued.tirbasal3IR
        val tirbasal3B = continued.tirbasal3B
        val tirbasal3A = continued.tirbasal3A
        val tirbasalhAP = continued.tirbasalhAP
        val circadianMinute = continued.circadianMinute
        val circadianSecond = continued.circadianSecond
        val bgAcceleration = continued.bgAcceleration
        val nightbis = continued.nightbis
        val activeModeName = continued.activeModeName

        val signaled = when (
            val outcome = decideDetermineBasalTickSignal(
                ctx = ctx,
                profile = profile,
                rT = rT,
                glucoseStatus = glucoseStatus,
                combinedDelta = combinedDelta,
                tdd7P = tdd7P,
                tdd7Days = tdd7Days,
                isExplicitAdvisorRun = isExplicitAdvisorRun,
                isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
                pkpdRuntimeIn = pkpdRuntime,
                physioMultipliers = physioMultipliers,
                insulinActionState = insulinActionState,
                autodriveDisplay = autodriveDisplay,
                consoleLog = work.consoleLog,
                calls = object : AimiTickSignalCalls {
                    override fun signalPrep(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        glucoseStatus: GlucoseStatusAIMI,
                        combinedDelta: Float,
                        tdd7P: Double,
                        isExplicitAdvisorRun: Boolean,
                        isConfirmedHighRiseLocal: Boolean,
                        pkpdRuntime: PkPdRuntime?,
                    ) = when (
                        val prepared = runSignalPreparationPkpdRuntimePhase(
                            ctx, profile, rT, glucoseStatus, combinedDelta, tdd7P,
                            isExplicitAdvisorRun, isConfirmedHighRiseLocal, pkpdRuntime,
                        )
                    ) {
                        is AimiSignalPreparationPkpdOutcome.StaleAbort ->
                            AimiTickSignalStep.StaleAbort(prepared.rT)
                        is AimiSignalPreparationPkpdOutcome.Continue -> AimiTickSignalStep.Continue(
                            AimiTickSignalData(
                                modesCondition = prepared.data.modesCondition,
                                pbolusAS = prepared.data.pbolusAS,
                                pbolusA = prepared.data.pbolusA,
                                reason = prepared.data.reason,
                                recentBGs = prepared.data.recentBGs,
                                totalBolusLastHour = prepared.data.totalBolusLastHour,
                                autosensRatio = prepared.data.autosensRatio,
                                iobData = prepared.data.iob_data,
                                lastBolusTimeMs = prepared.data.lastBolusTimeMs,
                                causalState.lateFatRiseFlag = prepared.data.causalState.lateFatRiseFlag,
                                tdd24Hrs = prepared.data.tdd24Hrs,
                                minAgo = prepared.data.minAgo,
                                windowSinceDoseInt = prepared.data.windowSinceDoseInt,
                                pkpdRuntime = prepared.data.pkpdRuntime,
                            ),
                        )
                    }
                    override fun trajectoryPrep(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        iobData: IobTotal,
                        physioMultipliers: PhysioMultipliersMTR,
                        insulinActionState: InsulinActionState,
                        pkpdRuntime: PkPdRuntime?,
                        tdd7Days: Double,
                        tdd7P: Double,
                        tdd24Hrs: Float,
                        pbolusA: Double,
                        pbolusAS: Double,
                        reason: StringBuilder,
                        isExplicitAdvisorRun: Boolean,
                    ): AimiTickTrajectoryPrep {
                        val prep = runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep(
                            ctx, profile, rT, iobData, physioMultipliers, insulinActionState, pkpdRuntime,
                            tdd7Days, tdd7P, tdd24Hrs, pbolusA, pbolusAS, reason, isExplicitAdvisorRun,
                        )
                        return AimiTickTrajectoryPrep(
                            prep.sens, prep.baseSensitivity, prep.contextTargetOverride, prep.dynamicPbolusSmall,
                        )
                    }
                    override fun wearableSnapshot() = physioAdapter.getLatestSnapshot()
                    override fun bg() = bg
                    override fun delta() = delta
                    override fun predictedBg() = predictedBg
                    override fun shortAvgDelta() = shortAvgDelta
                    override fun longAvgDelta() = longAvgDelta
                    override fun targetBg() = targetBg
                    override fun advancedPredictions(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        bg: Double,
                        delta: Float,
                        sens: Double,
                        predictedBg: Float,
                        glucoseStatus: GlucoseStatusAIMI,
                        minAgo: Double,
                        isExplicitAdvisorRun: Boolean,
                        physioMultipliers: PhysioMultipliersMTR,
                        iobData: IobTotal,
                        stepsLast15m: Int,
                        heartRateBpm: Int,
                        restingHeartRateBpm: Int,
                        combinedDelta: Float,
                    ): AimiTickPredPrep {
                        val prep = runAdvancedPredictionsAndPredPipePrep(
                            ctx, profile, rT, bg, delta, sens, predictedBg, glucoseStatus, minAgo,
                            isExplicitAdvisorRun, physioMultipliers, iobData, stepsLast15m, heartRateBpm,
                            restingHeartRateBpm, combinedDelta,
                        )
                        return AimiTickPredPrep(prep.minBg, prep.threshold, prep.scenario)
                    }
                    override fun safetyHalt(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        bg: Double,
                        delta: Float,
                        combinedDelta: Float,
                        iobData: IobTotal,
                        glucoseStatus: GlucoseStatusAIMI,
                        scenario: ScenarioProjectionPair,
                        isExplicitAdvisorRun: Boolean,
                    ) = when (
                        val gate = runPredPipelineSafetyHaltOrReturn(
                            ctx, profile, rT, bg, delta, combinedDelta, iobData, glucoseStatus, scenario,
                            isExplicitAdvisorRun,
                        )
                    ) {
                        is AimiPredPipelineSafetyGate.Halt -> gate.rT
                        AimiPredPipelineSafetyGate.Continue -> null
                    }
                    override fun hasRecentBolus45m(lastBolusTimeMs: Long) =
                        hasReceivedRecentBolus(45, lastBolusTimeMs)
                    override fun mealAdvisor(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        bg: Double,
                        delta: Float,
                        iobData: IobTotal,
                        modesCondition: Boolean,
                        isExplicitAdvisorRun: Boolean,
                        lastBolusTimeMs: Long?,
                        autodriveDisplay: String,
                        hasRecentBolus45m: Boolean,
                    ) = runMealAdvisorDecisionOrReturn(
                        ctx, profile, rT, bg, delta, iobData, modesCondition, isExplicitAdvisorRun,
                        lastBolusTimeMs, autodriveDisplay, hasRecentBolus45m,
                    )
                    override fun hardBrake(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        bg: Double,
                        delta: Float,
                        shortAvgDelta: Float,
                        longAvgDelta: Float,
                        targetBgMgdl: Float,
                    ) = runHardBrakeLyraOrReturn(
                        ctx, profile, rT, bg, delta, shortAvgDelta, longAvgDelta, targetBgMgdl,
                    )
                },
            )
        ) {
            is AimiTickSignalOutcome.ReturnEarly -> return outcome.rT
            is AimiTickSignalOutcome.Continue -> outcome
        }
        val modesCondition = signaled.modesCondition
        val reason = signaled.reason
        val recentBGs = signaled.recentBGs
        val totalBolusLastHour = signaled.totalBolusLastHour
        val autosensRatio = signaled.autosensRatio
        val iob_data = signaled.iobData
        val lateFatRiseFlag = signaled.lateFatRiseFlag
        val tdd24Hrs = signaled.tdd24Hrs
        val minAgo = signaled.minAgo
        val windowSinceDoseInt = signaled.windowSinceDoseInt
        pkpdRuntime = signaled.pkpdRuntime
        val (systemTime, bgTime) = signaled.systemTime to signaled.bgTime
        var sens = signaled.sens
        val baseSensitivity = signaled.baseSensitivity
        val contextTargetOverride = signaled.contextTargetOverride
        val dynamicPbolusSmall = signaled.dynamicPbolusSmall
        val wearableSnapshot = signaled.wearableSnapshot
        val minBg = signaled.minBg
        val threshold = signaled.threshold
        val hasRecentBolus45m = signaled.hasRecentBolus45m

        val postHypo = when (
            val outcome = decideDetermineBasalTickPostHypo(
                ctx = ctx,
                profile = profile,
                rT = rT,
                recentBGs = recentBGs,
                reason = reason,
                tdd24Hrs = tdd24Hrs,
                wearableSnapshot = wearableSnapshot,
                threshold = threshold,
                autosensRatio = autosensRatio,
                nightbis = nightbis,
                autodriveEnabledPref = autodrive,
                modesCondition = modesCondition,
                hasRecentBolus45m = hasRecentBolus45m,
                totalBolusLastHour = totalBolusLastHour,
                dynamicPbolusSmall = dynamicPbolusSmall,
                pkpdRuntime = pkpdRuntime,
                preferences = preferences,
                calls = object : AimiTickPostHypoCalls {
                    override fun classify(
                        ctx: AimiTickContext,
                        recentBGs: List<Float>,
                        reason: StringBuilder,
                    ): AimiPostHypoClassified {
                        val bundle = runPostAutodrivePostHypoClassification(
                            recentBGs = recentBGs,
                            cob = work.cob,
                            shortAvgDeltaAdj = shortAvgDeltaAdj,
                            delta = delta,
                            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
                            mealTime = work.mealTime,
                            bfastTime = bfastTime,
                            lunchTime = lunchTime,
                            dinnerTime = dinnerTime,
                            highCarbTime = highCarbTime,
                            snackTime = work.snackTime,
                            reason = reason,
                        )
                        return AimiPostHypoClassified(
                            postHypoState = bundle.postHypoState,
                            estimatedCarbs = bundle.estimatedCarbs,
                            estimatedCarbsTimeMs = bundle.estimatedCarbsTimeMs,
                        )
                    }
                    override fun refreshPostHypo(
                        ctx: AimiTickContext,
                        recentBGs: List<Float>,
                        reason: StringBuilder,
                    ) = refreshPostHypoDeliveryAuthorityForTick(
                        combinedDelta = combinedDelta,
                        recentBGs = recentBGs,
                        shortAvgDeltaAdj = shortAvgDeltaAdj,
                        slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
                        reason = reason,
                    )
                    override fun eventualBg() = work.eventualBG
                    override fun bg() = this@DetermineBasalaimiSMB2.bg
                    override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
                    override fun publishDoseTerminal(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        pkpdEventualMgdl: Double,
                        pkpdPredTerminalMgdl: Double,
                        targetBgMgdl: Double,
                    ) = publishDoseTerminalAuthorityAndSnapshot(
                        rT = rT,
                        profile = profile,
                        mealData = ctx.mealData,
                        pkpdEventualMgdl = pkpdEventualMgdl,
                        pkpdPredTerminalMgdl = pkpdPredTerminalMgdl,
                        targetBgMgdl = targetBgMgdl,
                        stageTag = "pre_rbt",
                    )
                    override fun tdd24hForExport() = resolveTdd24hForExport()
                    override fun wireRbt(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        tdd24hU: Double,
                        wearableSnapshot: HealthContextSnapshot,
                    ) {
                        resolveAndWireRbtLiveTick(
                            ctx = ctx,
                            profile = profile,
                            rT = rT,
                            combinedDelta = combinedDelta,
                            tdd24hU = tdd24hU,
                            v3SmbU = 0.0,
                            stepsLast15m = wearableSnapshot.stepsLast15m,
                            heartRateBpm = wearableSnapshot.hrNow,
                        )
                    }
                    override fun autodriveV3(
                        ctx: AimiTickContext,
                        profile: OapsProfileAimi,
                        rT: RT,
                        hypoThresholdMgdl: Double,
                        pkpdRuntime: PkPdRuntime?,
                    ) = runAutodriveV3MultiVariableBranch(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        bg = bg,
                        combinedDelta = combinedDelta,
                        shortAvgDeltaAdj = shortAvgDeltaAdj,
                        hypoThresholdMgdl = hypoThresholdMgdl,
                        pkpdRuntime = pkpdRuntime,
                    )
                    override fun rbtResolvedThisTick() = work.rbtResolvedThisTick
                    override fun applyPendingSpiral(rT: RT) =
                        applyPendingTrajSpiralBasalIfNotSuppressed(rT = rT, bg = bg, delta = delta)
                    override fun compressionAndDrift(
                        ctx: AimiTickContext,
                        rT: RT,
                        threshold: Double,
                        postHypoState: PostHypoState,
                        autosensRatio: Double,
                        nightbis: Boolean,
                        autodriveEnabledPref: Boolean,
                        modesCondition: Boolean,
                        hasRecentBolus45m: Boolean,
                        totalBolusLastHour: Double,
                        dynamicPbolusSmall: Double,
                        reason: StringBuilder,
                    ) = runPostHypoCompressionAndDriftTerminatorOrReturn(
                        ctx = ctx,
                        rT = rT,
                        bg = bg,
                        delta = delta,
                        threshold = threshold,
                        combinedDelta = combinedDelta,
                        shortAvgDeltaRawForDrift = shortAvgDelta,
                        targetBgMgdl = targetBg,
                        postHypoState = postHypoState,
                        autosensRatio = autosensRatio,
                        nightbis = nightbis,
                        autodriveEnabledPref = autodriveEnabledPref,
                        modesCondition = modesCondition,
                        hasRecentBolus45m = hasRecentBolus45m,
                        totalBolusLastHour = totalBolusLastHour,
                        dynamicPbolusSmall = dynamicPbolusSmall,
                        exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
                        reason = reason,
                    )
                },
            )
        ) {
            is AimiTickPostHypoOutcome.ReturnEarly -> return outcome.rT
            is AimiTickPostHypoOutcome.Continue -> outcome
        }
        val postHypoState = postHypo.postHypoState
        val estimatedCarbs = postHypo.estimatedCarbs
        val estimatedCarbsTime = postHypo.estimatedCarbsTimeMs
        val skipLegacySmbBlender = postHypo.skipLegacySmbBlender

        val schedule = decideDetermineBasalTickSchedule(
            initialSens = sens,
            postHypoState = postHypoState,
            calls = object : AimiTickScheduleCalls<SmbInstructionExecutor.Result> {
                override fun bootstrap(): AimiScheduleBootstrap {
                    val built = buildGlobalAimiBasalScheduleBootstrap(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        glucoseStatus = glucoseStatus,
                        contextTargetOverride = contextTargetOverride,
                        bg = bg,
                        predictedBg = predictedBg,
                        combinedDelta = combinedDelta,
                        minAgo = minAgo,
                        systemTime = systemTime,
                        bgTime = bgTime,
                        flatBGsDetected = flatBGsDetected,
                        honeymoon = honeymoon,
                        circadianMinute = circadianMinute,
                        circadianSecond = circadianSecond,
                    )
                    return AimiScheduleBootstrap(
                        pumpCaps = built.pumpCaps,
                        profileCurrentBasal = built.profileCurrentBasal,
                        basal = built.basal,
                        targetBg = built.targetBg,
                        minBg = built.minBg,
                        maxBg = built.maxBg,
                        sensitivityRatio = built.sensitivityRatio,
                        deliverAt = built.deliverAt,
                        maxIobLimit = built.maxIobLimit,
                    )
                }

                override fun setWorkingTargetMgdl(targetBg: Double) {
                    // Observation only: the auditor is told this level next to the profile target.
                    // A tick that ends earlier never reaches this line, and the field then stays null.
                    auditorProfileTick.workingTargetMgdl = targetBg
                }

                override fun activityVitals(): AimiScheduleVitals {
                    val vitals = runPostBasalBootstrapIobTickStepsAndHeartRate(
                        glucoseStatus = glucoseStatus,
                        profile = profile,
                        iobData = iob_data,
                        bg = bg,
                    )
                    return AimiScheduleVitals(vitals.tick, vitals.minDelta, vitals.minAvgDelta)
                }

                override fun pai(profileCurrentBasal: Double, paiBaseSensitivity: Double): AimiSchedulePai {
                    val stage = runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf(
                        glucoseStatus = glucoseStatus,
                        profile = profile,
                        profileCurrentBasal = profileCurrentBasal,
                        bg = bg,
                        delta = delta,
                        tdd7Days = tdd7Days,
                        tdd7P = tdd7P,
                        paiBaseSensitivity = paiBaseSensitivity,
                        honeymoon = honeymoon,
                        tirbasal3B = tirbasal3B,
                        tirbasal3IR = tirbasal3IR,
                        tirbasal3A = tirbasal3A,
                        tirbasalhAP = tirbasalhAP,
                        lastHourTIRAbove = lastHourTIRAbove,
                        iobPeakMinutes = iobPeakMinutes,
                        iobActivityIn30Min = iobActivityIn30Min,
                        iobActivityNow = work.iobActivityNow,
                    )
                    return AimiSchedulePai(stage.timenowHour, stage.sixAMHour, stage.pregnancyEnable)
                }

                override fun endoAndActivity() {
                    applyEndoAndActivityAdjustments(
                        bg = bg, delta = delta,
                        mealTime = work.mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
                        dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = work.snackTime,
                        recentSteps5Minutes = recentSteps5Minutes, recentSteps10Minutes = recentSteps10Minutes,
                        averageBeatsPerMinute = averageBeatsPerMinute.toDouble(), averageBeatsPerMinute60 = averageBeatsPerMinute60,
                    )
                }

                override fun isfAfterEndo(): Double = applyIsfBoundsAndPhysioMultipliersAfterEndoActivity(
                    profile = profile,
                    physioMultipliers = physioMultipliers,
                    exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
                )

                override fun auditorTarget(workingTargetRawMgdl: Double) {
                    // The combined bound needs the ISF factor after the stress floor. `threshold` and
                    // the signal `minBg` are unchanged since the basal-schedule bootstrap.
                    decideAuditorTargetFactorForTick(
                        ctx = ctx,
                        workingTargetRawMgdl = workingTargetRawMgdl,
                        hypoThresholdMgdl = threshold,
                        minPredBgMgdl = minBg,
                    )
                }

                override fun tightSpiralCapIfNeeded() {
                    trajectoryGuard.getLastAnalysis()?.takeIf { it.classification == TrajectoryType.TIGHT_SPIRAL }?.let { analysis ->
                        applyTrajectoryTightSpiralStandardSmbCapIfNeeded(
                            energy = analysis.metrics.energyBalance,
                            iobNow = iob_data.iob,
                            tdd24hU = tdd24Hrs.toDouble(),
                            deltaValue = delta,
                            shortAvgDeltaValue = shortAvgDelta,
                            mealData = ctx.mealData,
                            isExplicitUserAction = isExplicitAdvisorRun,
                            mealClockActiveForSpiralRelax = therapyMealWindowActiveForSpiralAlign(),
                        )
                    }
                }

                override fun pkpdTargets(
                    sens: Double,
                    minDelta: Double,
                    minAvgDelta: Double,
                    minBg: Double,
                    targetBg: Double,
                    maxBg: Double,
                ): AimiSchedulePkpdTargets {
                    val stage = runPkpdPredictionsBgiDeviationAndNoisyTargetsStage(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        glucoseStatus = glucoseStatus,
                        pkpdRuntime = pkpdRuntime,
                        iobData = iob_data,
                        bg = bg,
                        delta = delta,
                        sens = sens,
                        minDelta = minDelta,
                        minAvgDelta = minAvgDelta,
                        minBg = minBg,
                        targetBg = targetBg,
                        maxBg = maxBg,
                    )
                    return AimiSchedulePkpdTargets(stage.bgi, stage.deviation, stage.minBg, stage.targetBg, stage.maxBg)
                }

                override fun uam(targetBg: Double, postHypoState: PostHypoState): Float =
                    runUamModelCalHypoGuardPostHypoAndSetPredictedSmb(
                        rT = rT,
                        bg = bg,
                        delta = delta,
                        iob = work.iob,
                        predictedBg = predictedBg,
                        eventualBg = work.eventualBG,
                        threshold = threshold,
                        minBgHypoComposite = minBg,
                        targetBg = targetBg,
                        profile = profile,
                        postHypoState = postHypoState,
                        cob = work.cob,
                    )

                override fun smb(
                    targetBg: Double,
                    basal: Double,
                    sens: Double,
                    pumpCaps: PumpCaps,
                    profileCurrentBasal: Double,
                    modelcal: Float,
                ): AimiScheduleSmb<SmbInstructionExecutor.Result> {
                    val stage = runSmbDecisionLogAdvisorOneShotAndExecuteInstruction(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        glucoseStatus = glucoseStatus,
                        bg = bg,
                        delta = delta,
                        iob = work.iob,
                        shortAvgDelta = shortAvgDelta,
                        predictedBg = predictedBg,
                        eventualBG = work.eventualBG,
                        sens = sens,
                        tp = tp,
                        variableSensitivity = variableSensitivity,
                        // The only SMB site that sees the auditor's target ratio.
                        targetBg = auditorDoseTarget(targetBg, AuditorProfileFactorCodes.DOSE_SITE_SMB),
                        basalaimi = work.basalaimi,
                        basal = basal,
                        honeymoon = honeymoon,
                        hourOfDay = work.hourOfDay,
                        mealTime = work.mealTime,
                        bfastTime = bfastTime,
                        lunchTime = lunchTime,
                        dinnerTime = dinnerTime,
                        highCarbTime = highCarbTime,
                        snackTime = work.snackTime,
                        sportTime = work.sportTime,
                        causalState.lateFatRiseFlag = causalState.lateFatRiseFlag,
                        highCarbrunTime = work.highCarbrunTime,
                        threshold = threshold,
                        windowSinceDoseInt = windowSinceDoseInt,
                        intervalsmb = intervalsmb,
                        pumpCaps = pumpCaps,
                        causalState.highBgOverrideUsed = causalState.highBgOverrideUsed,
                        cob = work.cob,
                        pkpdRuntime = pkpdRuntime,
                        pumpAgeDays = pumpAgeDays,
                        modelcal = modelcal,
                        profileCurrentBasal = profileCurrentBasal,
                        isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
                        exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
                        combinedDelta = combinedDelta.toFloat(),
                        skipLegacySmbBlender = skipLegacySmbBlender,
                        minBgLookbackMgdl = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
                    )
                    return AimiScheduleSmb(stage.smbExecution, stage.isMealAdvisorOneShot)
                }

                override fun applySmb(
                    execution: SmbInstructionExecutor.Result,
                    assignBasal: (Double) -> Unit,
                ): Float = applySmbAdvisorExecutionToTickStateAndLog(execution, assignBasal)

                override fun pkpdGuard(
                    execution: SmbInstructionExecutor.Result,
                    isMealAdvisorOneShot: Boolean,
                    smbToGive: Float,
                    targetBg: Double,
                ): AimiSchedulePkpdGuard {
                    val guarded = runPkpdGuardEndoDampenRedCarpetAndCapSmb(
                        ctx = ctx,
                        rT = rT,
                        pkpdRuntime = pkpdRuntime,
                        smbExecution = execution,
                        isExplicitAdvisorRun = isExplicitAdvisorRun,
                        isMealAdvisorOneShot = isMealAdvisorOneShot,
                        isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
                        bg = bg,
                        delta = delta,
                        shortAvgDelta = shortAvgDelta,
                        predictedBg = predictedBg,
                        eventualBG = work.eventualBG,
                        targetBg = targetBg,
                        honeymoon = honeymoon,
                        mealTime = work.mealTime,
                        bfastTime = bfastTime,
                        lunchTime = lunchTime,
                        dinnerTime = dinnerTime,
                        highCarbTime = highCarbTime,
                        snackTime = work.snackTime,
                        windowSinceDoseInt = windowSinceDoseInt,
                        intervalsmb = intervalsmb,
                        smbToGive = smbToGive,
                        iob = work.iob,
                    )
                    return AimiSchedulePkpdGuard(guarded.smbToGive, guarded.intervalsmb)
                }
            },
        )
        val pumpCaps = schedule.pumpCaps
        val profile_current_basal = schedule.profileCurrentBasal
        var basal = schedule.basal
        var target_bg = schedule.targetBg
        var min_bg = schedule.minBg
        var max_bg = schedule.maxBg
        var sensitivityRatio = schedule.sensitivityRatio
        val deliverAt = schedule.deliverAt
        var maxIobLimit = schedule.maxIobLimit
        val tick = schedule.tick
        val minDelta = schedule.minDelta
        val minAvgDelta = schedule.minAvgDelta
        val timenow = schedule.timenowHour
        val sixAMHour = schedule.sixAmHour
        val pregnancyEnable = schedule.pregnancyEnable
        sens = schedule.sens
        val bgi = schedule.bgi
        val deviation = schedule.deviation
        val isMealAdvisorOneShot = schedule.isMealAdvisorOneShot
        var smbToGive = schedule.smbToGive
        intervalsmb = schedule.intervalSmb
        snapshotRtResetEnactmentFieldsRestorePredictionsAndPriorityCommands(
            rT = rT,
            deliverAt = deliverAt,
            targetBg = target_bg,
            sensitivityRatio = sensitivityRatio,
            variableSensitivity = variableSensitivity,
            bg = bg,
        )

        val mealNgr = decideDetermineBasalTickMealNgr(
            basalIn = basal,
            maxIobLimitIn = maxIobLimit,
            smbToGiveIn = smbToGive,
            profileCurrentBasal = profile_current_basal,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            targetBg = target_bg,
            maxBg = max_bg,
            estimatedCarbs = estimatedCarbs,
            estimatedCarbsTimeMs = estimatedCarbsTime,
            deliverAt = deliverAt,
            sens = sens,
            minDelta = minDelta,
            bgi = bgi,
            deviation = deviation,
            sensitivityRatio = sensitivityRatio,
            calls = object : AimiTickMealNgrCalls {
                override fun mealHyper(
                    basal: Double,
                    profileCurrentBasal: Double,
                    isMealAdvisorOneShot: Boolean,
                    targetBg: Double,
                    estimatedCarbs: Double,
                    estimatedCarbsTimeMs: Long,
                ): AimiTickMealHyperStep = when (
                    val stage = runMealHyperBasalBoostTickStage(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        basal = basal,
                        profileCurrentBasal = profileCurrentBasal,
                        isMealAdvisorOneShot = isMealAdvisorOneShot,
                        targetBg = targetBg,
                        estimatedCarbs = estimatedCarbs,
                        estimatedCarbsTimeMs = estimatedCarbsTimeMs,
                    )
                ) {
                    is AimiMealHyperBasalBoostTickResult.CompleteLoop -> AimiTickMealHyperStep.ReturnEarly(stage.rT)
                    is AimiMealHyperBasalBoostTickResult.ContinueWithOverlay -> AimiTickMealHyperStep.Continue(stage.overlayRate)
                }

                override fun applyOverlay(overlayRate: Double?, deliverAt: Long): AimiTickMealBoost {
                    val state = applyMealHyperBasalBoostOverlayIfNeeded(overlayRate, deliverAt, rT)
                    return AimiTickMealBoost(state.basalBoostApplied, state.basalBoostSource)
                }

                override fun appendAutodriveSummary() {
                    appendAutodriveStatusTirAndCompactPhysioSummaryToReason(
                        rT = rT,
                        autodriveDisplay = autodriveDisplay,
                        activeModeName = activeModeName,
                        reasonAimi = reasonAimi,
                        tp = tp,
                        bg = bg,
                        delta = delta,
                        recentSteps5Minutes = recentSteps5Minutes,
                        averageBeatsPerMinute = averageBeatsPerMinute,
                    )
                }

                override fun csf(sens: Double, minDelta: Double, bgi: Double, sensitivityRatio: Double): AimiTickCsf {
                    val stage = runWCycleIcCsfClampCiAndCarbImpactLogs(
                        profile = profile,
                        ctx = ctx,
                        sens = sens,
                        baseSensitivity = baseSensitivity,
                        minDelta = minDelta,
                        bgi = bgi,
                        sensitivityRatio = sensitivityRatio,
                    )
                    return AimiTickCsf(stage.csf, stage.slopeFromDeviations)
                }

                override fun carbsGate(
                    csf: Double,
                    slopeFromDeviations: Double,
                    sens: Double,
                    bgi: Double,
                    deviation: Int,
                    targetBg: Double,
                    maxBg: Double,
                ): AimiTickCarbsGate = when (
                    val gate = runCarbsAdvisorEnableSmbSafetyAndHardHypoBasalStopOrReturn(
                        profile = profile,
                        ctx = ctx,
                        rT = rT,
                        glucoseStatus = glucoseStatus,
                        iobData = iob_data,
                        csf = csf,
                        slopeFromDeviations = slopeFromDeviations,
                        sens = sens,
                        bg = bg,
                        iob = work.iob,
                        cob = work.cob,
                        delta = delta,
                        eventualBG = work.eventualBG,
                        combinedDelta = combinedDelta,
                        deviation = deviation,
                        bgi = bgi,
                        targetBgSchedule = targetBg,
                        maxBgSchedule = maxBg,
                        windowSinceDoseInt = windowSinceDoseInt,
                    )
                ) {
                    is AimiCarbsAdvisorHardHypoBasalGateResult.ReturnZeroTempBasal -> AimiTickCarbsGate.ReturnEarly(gate.rT)
                    is AimiCarbsAdvisorHardHypoBasalGateResult.Continue -> AimiTickCarbsGate.Continue(
                        AimiTickCarbsSafety(
                            forcedBasalMealModes = gate.stage.forcedBasalmealmodes,
                            forcedBasal = gate.stage.forcedBasal,
                            enableSmb = gate.stage.enableSMB,
                            mealModeActive = gate.stage.mealModeActive,
                            zeroSinceMin = gate.stage.zeroSinceMin,
                            minutesSinceLastChange = gate.stage.minutesSinceLastChange,
                            safetyDecision = gate.stage.safetyDecision,
                        ),
                    )
                }

                override fun mealNgr(
                    safetyDecision: SafetyDecision,
                    forcedBasalMealModes: Double,
                    maxIobLimit: Double,
                    basal: Double,
                    smbToGive: Float,
                    targetBg: Double,
                ): AimiTickMealNgrStep = when (
                    val mealNgr = runPostSafetyMealFirst30NgrHeadroomBasalSmbStage(
                        profile = profile,
                        ctx = ctx,
                        rT = rT,
                        ngrConfig = ngrConfig,
                        safetyDecision = safetyDecision,
                        forcedBasalmealmodes = forcedBasalMealModes,
                        maxIobLimitIn = maxIobLimit,
                        basalIn = basal,
                        smbToGiveIn = smbToGive,
                        bg = bg,
                        delta = delta,
                        shortAvgDelta = shortAvgDelta,
                        longAvgDelta = longAvgDelta,
                        eventualBG = work.eventualBG,
                        targetBgSchedule = targetBg,
                    )
                ) {
                    is AimiPostSafetyMealNgrStageResult.EarlyTempBasal -> AimiTickMealNgrStep.ReturnEarly(mealNgr.rt)
                    is AimiPostSafetyMealNgrStageResult.Continue -> AimiTickMealNgrStep.Continue(
                        AimiTickMealNgrContinue(
                            isMealActive = mealNgr.isMealActive,
                            runtimeMinValue = mealNgr.runtimeMinValue,
                            maxIobLimit = mealNgr.maxIobLimit,
                            basal = mealNgr.basal,
                            smbToGive = mealNgr.smbToGive,
                        ),
                    )
                }

                override fun maxIob(
                    mealModeActive: Boolean,
                    maxIobLimit: Double,
                    safetyDecision: SafetyDecision,
                    basal: Double,
                    targetBg: Double,
                ): AimiTickMaxIobStep = when (
                    val gate = runCoreDecisionMaxIobExceededTempBasalGate(
                        profile = profile,
                        ctx = ctx,
                        rT = rT,
                        originalProfile = originalProfile,
                        flatBGsDetected = flatBGsDetected,
                        mealModeActive = mealModeActive,
                        maxIobLimit = maxIobLimit,
                        safetyDecision = safetyDecision,
                        basal = basal,
                        bg = bg,
                        delta = delta,
                        eventualBG = work.eventualBG,
                        targetBgSchedule = targetBg,
                        loopIob = iob_data.iob,
                    )
                ) {
                    is AimiCoreDecisionMaxIobGateResult.ReturnTempBasal -> AimiTickMaxIobStep.ReturnEarly(gate.rt)
                    is AimiCoreDecisionMaxIobGateResult.ContinueSMBPath -> AimiTickMaxIobStep.Continue(
                        gate.allowMealHighIob,
                        gate.mealHighIobDamping,
                    )
                }

                override fun insulinReq(
                    smbToGive: Float,
                    allowMealHighIob: Boolean,
                    mealHighIobDamping: Double,
                    maxIobLimit: Double,
                    safetyDecision: SafetyDecision,
                    enableSmb: Boolean,
                    isMealActive: Boolean,
                    basalBoostApplied: Boolean,
                    basalBoostSource: String?,
                ) {
                    runInsulinReqActivityRelaxAndMicrobolusStage(
                        ctx = ctx,
                        rT = rT,
                        iobTotal = iob_data,
                        smbToGive = smbToGive,
                        allowMealHighIob = allowMealHighIob,
                        mealHighIobDamping = mealHighIobDamping,
                        maxIobLimit = maxIobLimit,
                        safetyDecision = safetyDecision,
                        enableSMB = enableSmb,
                        isMealActive = isMealActive,
                        bg = bg,
                        delta = delta,
                        hypoThresholdMgdl = threshold,
                        systemTime = systemTime,
                        basalBoostApplied = basalBoostApplied,
                        basalBoostSource = basalBoostSource,
                    )
                }
            },
        )
        if (mealNgr is AimiTickMealNgrOutcome.ReturnEarly) return mealNgr.rT
        val continuedMeal = mealNgr as AimiTickMealNgrOutcome.Continue
        basal = continuedMeal.basal
        maxIobLimit = continuedMeal.maxIobLimit
        smbToGive = continuedMeal.smbToGive
        val isMealActive = continuedMeal.isMealActive
        val runtimeMinValue = continuedMeal.runtimeMinValue
        val forcedBasalmealmodes = continuedMeal.forcedBasalMealModes
        val forcedBasal = continuedMeal.forcedBasal
        val enableSMB = continuedMeal.enableSmb
        val zeroSinceMin = continuedMeal.zeroSinceMin
        val minutesSinceLastChange = continuedMeal.minutesSinceLastChange
        val safetyDecision = continuedMeal.safetyDecision
        val allowMealHighIob = continuedMeal.allowMealHighIob
        val mealHighIobDamping = continuedMeal.mealHighIobDamping

        // BasalDecisionEngine: the member targetBg is the loop target, not the schedule band.
        // nightMode is nightbis, already set by the tick clock.
        return decideDetermineBasalTickEngine(
            calls = object : AimiTickEngineCalls<BasalDecisionEngine.Decision> {
                override fun basalEngine(): BasalDecisionEngine.Decision = runBasalDecisionEngineDecideStage(
                    AimiBasalDecisionEngineStageBundle(
                        ctx = ctx,
                        profile = profile,
                        rT = rT,
                        glucoseStatus = glucoseStatus,
                        featuresCombinedDelta = f?.combinedDelta,
                        profileCurrentBasal = profile_current_basal,
                        basalEstimate = work.basalaimi.toDouble(),
                        tdd7P = tdd7P,
                        tdd7Days = tdd7Days,
                        variableSensitivity = variableSensitivity.toDouble(),
                        predictedBg = predictedBg.toDouble(),
                        // The only basal site that sees the auditor's target ratio. The member targetBg
                        // itself is never reassigned, so the learners and the ML CSV keep the raw value.
                        targetBg = auditorDoseTarget(targetBg.toDouble(), AuditorProfileFactorCodes.DOSE_SITE_BASAL),
                        tickIobForEngine = work.iob.toDouble(),
                        engineMaxIob = work.maxIob,
                        eventualBg = work.eventualBG,
                        bg = bg,
                        delta = delta.toDouble(),
                        shortAvgDelta = shortAvgDelta.toDouble(),
                        longAvgDelta = longAvgDelta.toDouble(),
                        combinedDelta = combinedDelta.toDouble(),
                        bgAcceleration = bgAcceleration.toDouble(),
                        allowMealHighIob = allowMealHighIob,
                        safetyDecision = safetyDecision,
                        forcedBasal = forcedBasal.toDouble(),
                        forcedBasalMealModesMax = forcedBasalmealmodes.toDouble(),
                        isMealActive = isMealActive,
                        runtimeMinValue = runtimeMinValue,
                        smbToGive = smbToGive.toDouble(),
                        zeroSinceMin = zeroSinceMin,
                        minutesSinceLastChange = minutesSinceLastChange,
                        pumpCaps = pumpCaps,
                        timenowHour = timenow,
                        sixAmHour = sixAMHour,
                        pregnancyEnable = pregnancyEnable,
                        nightMode = nightbis,
                        modesCondition = modesCondition,
                        autodrivePref = autodrive,
                        honeymoon = honeymoon,
                    ),
                )

                override fun learners(decision: BasalDecisionEngine.Decision): RT =
                    runPostBasalEngineLearnersRtInstrumentationAndAuditorStage(
                        AimiPostBasalEngineFinalizeBundle(
                            ctx = ctx,
                            profile = profile,
                            originalProfile = originalProfile,
                            rT = rT,
                            basalDecision = decision,
                            flatBGsDetected = flatBGsDetected,
                            pkpdRuntime = pkpdRuntime,
                            tdd7Days = tdd7Days,
                            intervalsmb = intervalsmb,
                        ),
                    )

                override fun export(finalResult: RT) {
                    runAimiSnapshotMedicalJsonAndHormonitorExportStage(
                        ctx = ctx,
                        profile = profile,
                        decisionCtx = decisionCtx,
                        finalResult = finalResult,
                        pkpdRuntime = pkpdRuntime,
                    )
                }
            },
        )
    }

    /**
     * Main entry point for the medical decision loop.
     *
     * Wraps the tick in [AimiLoopTelemetry.traceDetermineBasalTick], builds [AimiTickContext], then delegates to
     * [AimiDetermineBasalTickOrchestrator] → [runDetermineBasalTick] (§ Carte P3a in orchestration roadmap).
     *
     * @param glucose_status Current glucose status including delta, shortAvgDelta, and longAvgDelta.
     * @param currenttemp Current temporary basal rate active on the pump.
     * @param iob_data_array Collection of IobTotal objects representing active insulin from different sources.
     * @param profile The user's active OAPS profile (ISF, basal, target).
     * @param autosens_data Results from autosens sensitivity analysis.
     * @param mealData Current carb data (COB and recent meal announcements).
     * @param microBolusAllowed Feature toggle: true if the pump supports and allows SMB.
     * @param currentTime Current epoch timestamp in milliseconds.
     * @param flatBGsDetected True if the sensor signals a period of unchanging glucose.
     * @param dynIsfMode True if Dynamic ISF modulation is active.
     * @param uiInteraction Interface for communicating status/warnings to the user interface.
     * @param extraDebug Optional debug string injected from external gateways (e.g., Cosine Gate).
     * @return [RT] (Result Type) containing the finalized basal and SMB instructions.
     * @see runDetermineBasalTick
     * @see app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiDetermineBasalTickOrchestrator
     */
    fun determine_basal(
        glucose_status: GlucoseStatusAIMI, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean, uiInteraction: UiInteraction,
        pkpd_iob_data_array: Array<IobTotal>? = null,
        effective_dia_hours: Double? = null,
        effective_peak_minutes: Double? = null,
        extraDebug: String = ""
    ): RT {
        // Sync legacy prebolus latch into causal state for the replay harness.
        // Transitional: the getters below read preferences (shell concern).
        causalState.syncFromLegacyPrebolus(
            lastSmbMillis = internalLastSmbMillis,
            lastLegacyPrebolusMillis = internalLastLegacyPrebolusMillis,
            pendingUnit = pendingLegacyPrebolusUnit,
            pendingExpiry = pendingLegacyPrebolusExpiry,
        )
        val ctx = AimiTickContext(
            glucoseStatus = glucose_status,
            currentTemp = currenttemp,
            iobDataArray = iob_data_array,
            pkpdIobDataArray = pkpd_iob_data_array,
            effectiveDiaHours = effective_dia_hours,
            effectivePeakMinutes = effective_peak_minutes,
            profile = profile,
            autosensData = autosens_data,
            mealData = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = currentTime,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode,
            uiInteraction = uiInteraction,
            extraDebug = extraDebug,
        )
        return AimiLoopTelemetry.traceDetermineBasalTick(
            preferences = preferences,
            wallClockMs = currentTime,
            onLockTimeout = { AimiLoopTickRecovery.skippedPriorTickStillRunning(ctx) },
            recoverFromError = { error ->
                AimiLoopTickRecovery.safeResultAfterUnhandledError(ctx, error, work.consoleLog, work.consoleError)
            },
            onTickEnd = { tickId, startedWallMs, endedWallMs ->
                try {
                    hormonitorStudyExporter?.recordLoopTickEnd(
                        tickId = tickId,
                        startedWallMs = startedWallMs,
                        endedWallMs = endedWallMs,
                        lastPhaseName = AimiLoopTelemetry.currentLoopPhase.name
                    )
                } catch (_: Throwable) {
                    // Never break determine_basal on telemetry.
                }
            },
            onTickAbort = { tickId, startedWallMs, endedWallMs, error ->
                determineBasalInvocationCaches.abandonInvocationAfterUnhandledError()
                try {
                    hormonitorStudyExporter?.recordLoopTickAborted(
                        tickId = tickId,
                        startedWallMs = startedWallMs,
                        endedWallMs = endedWallMs,
                        errorClass = error::class.simpleName ?: "Throwable",
                        errorMessage = error.message ?: "",
                        lastPhaseName = AimiLoopTelemetry.currentLoopPhase.name
                    )
                } catch (_: Throwable) {
                    // Never break determine_basal on telemetry.
                }
            },
        ) {
            // Fresh tick-local scratch. Helpers access via work.field.
            // Reset under the tick lock: a tick that cannot acquire the lock must not
            // clobber the scratch of the tick currently holding it.
            work = AimiTickWorkingState()
            AimiDetermineBasalTickOrchestrator.run(this, ctx)
        }
    }

    private fun inferFinalLoopDecisionFromResult(result: RT): String =
        AimiTickPolicyMath.inferFinalLoopDecisionFromResult(result)

    private fun applyBasalFloor(
        suggestedRate: Double,
        profileBasal: Double,
        safetyDecision: SafetyDecision,
        activityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        predictedBg: Double,
        isMealActive: Boolean,
        lgsThreshold: Double,
    ): Double = AimiTickPolicyMath.applyBasalFloor(
        suggestedRate,
        profileBasal,
        safetyDecision,
        activityContext,
        bg,
        delta,
        shortAvgDelta,
        predictedBg,
        isMealActive,
        lgsThreshold,
    )




    private fun adjustBasalForGeneralHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        maxBasalConfig: Double,
        maxScaleCap: Double = 10.0,
    ): Double = AimiTickPolicyMath.adjustBasalForGeneralHyper(
        suggestedBasalUph,
        bg,
        targetBg,
        delta,
        shortAvgDelta,
        maxBasalConfig,
        maxScaleCap,
    )

    // Decision pipeline helpers: degrade plan, DECISION_FINAL / basal neural, safety entry.
    private enum class ModeDegradeLevel(val value: Int, val label: String) {
        NORMAL(0, "Normal"),
        CAUTION(1, "Caution"),
        HIGH_RISK(2, "High Risk"),
        CRITICAL(3, "Critical")
    }

    private data class DegradePlan(
        val level: ModeDegradeLevel,
        val reason: String,
        val bolusFactor: Double,
        val tbrFactor: Double,
        val banner: String?
    )

    private data class ModeState(
        var name: String = "",
        var startMs: Long = 0L,
        var pre1: Boolean = false,
        var pre2: Boolean = false,
        var pre1SentMs: Long = 0L,
        var pre2SentMs: Long = 0L,
        var tbrStartedMs: Long = 0L,
        var degradeLevel: Int = 0,
    )

    /**
     * Immutable snapshot for the TICK line and basal-neural step — keeps [logDecisionFinal]
     * phases explicit without changing field reads vs the previous monolithic implementation.
     */
    private data class DecisionFinalDiagSnapshot(
        val smbFinal: Double,
        val tbrUph: Double,
        val bgValue: Double,
        val deltaValue: Double,
        val modeLabel: String,
        val predChunk: String,
        val refractoryStatus: String,
        val cgmNoise: Double,
    )

    /** Console: DECISION_FINAL line + [work.lastSmbFinal]; no ML side effects. */
    private fun appendDecisionFinalSummaryLine(
        tag: String,
        rT: RT,
        bg: Double?,
        delta: Float?,
    ): DecisionFinalDiagSnapshot {
        val smb = rT.insulinReq ?: 0.0
        val smbUnits = rT.units ?: 0.0
        val tbr = (rT.rate ?: 0.0).coerceAtLeast(0.0)
        val dur = rT.duration ?: 0
        val builder = StringBuilder("DECISION_FINAL[$tag]: smb=${aimiFmt2(smb)}U tbr=${aimiFmt2(tbr)}U/h dur=${dur}m")
        if (bg != null) builder.append(" bg=${bg.roundToInt()}")
        if (delta != null) builder.append(" Δ=${aimiFmt1(delta)}")
        val reasonText = rT.reason.toString().replace("\n", " | ")
        builder.append(" reason=${reasonText.take(180)}")
        work.consoleLog.add(builder.toString())

        val modeLabel = when {
            work.mealTime -> "Meal"
            lunchTime -> "Lunch"
            dinnerTime -> "Dinner"
            highCarbTime -> "HighCarb"
            work.snackTime -> "Snack"
            else -> "None"
        }
        val predSize = rT.predBGs?.IOB?.size ?: work.lastPredictionSize
        val predAvailable = predSize > 0 || work.lastPredictionAvailable
        val eventual = (rT.eventualBG ?: work.lastEventualBgSnapshot)
        val bgValue = bg ?: this.bg
        val deltaValue = delta?.toDouble() ?: this.delta.toDouble()
        val refractoryStatus = if (!work.lastBolusAgeMinutes.isNaN() && work.lastBolusAgeMinutes < intervalsmb) "YES" else "NO"
        val smbFinalValue = if (smbUnits > 0.0) smbUnits else smb
        work.lastSmbFinal = smbFinalValue
        val predChunk = "${if (predAvailable) "Y" else "N"}(sz=${predSize} ev=${eventual.roundToInt()})"
        return DecisionFinalDiagSnapshot(
            smbFinal = smbFinalValue,
            tbrUph = tbr,
            bgValue = bgValue,
            deltaValue = deltaValue,
            modeLabel = modeLabel,
            predChunk = predChunk,
            refractoryStatus = refractoryStatus,
            cgmNoise = lastLoopCgmNoise,
        )
    }

    /**
     * Records one basal-neural CSV row, triggers async ML training, and logs BASAL_GOV.
     * Shared by the main post-engine path and [logDecisionFinal] early exits.
     *
     * The row also carries the two causal facts of the label window that opens at this tick: the
     * non-basal insulin ([basalLearningBolusUnits]) and the carbs on board ([basalLearningCobGrams]).
     * Without them the trainer cannot tell a basal response from a bolus or a meal, and every row is
     * kept as "legacy, nothing proven". See [BasalNeuralLearner.updateLearning].
     */
    private fun applyBasalNeuralLearningAndTraining(
        rT: RT,
        tbrUph: Double,
        govTag: String,
    ) {
        val eventual = (rT.eventualBG ?: work.lastEventualBgSnapshot)
        val windowBolusU = basalLearningBolusUnits(rT)
        val windowCobG = basalLearningCobGrams(rT)
        basalNeuralLearner.updateLearning(
            bgBefore = bg,
            bgAfter = eventual,
            basalDelivered = tbrUph,
            targetBg = targetBg.toDouble(),
            accel = work.bgacc,
            duraISFminutes = work.duraISFminutes,
            duraISFaverage = work.duraISFaverage,
            iob = work.iobNet,
            loopDeltaMgDl5m = delta.toDouble(),
            sensorNoise = lastLoopCgmNoise,
            shortMinPredBg = minPredictedAcrossCurves(rT.predBGs),
            physioFeatures = currentBasalPhysioFeatures(),
            bolusInsulinU = windowBolusU,
            cobGrams = windowCobG,
        )
        triggerBasalMlTrainingIfNeeded()
        work.consoleLog.add(
            basalGovLine(
                govTag = govTag,
                gov = basalNeuralLearner.getGovernanceSnapshot(),
                windowBolusU = windowBolusU,
                windowCobG = windowCobG,
            )
        )
    }

    /**
     * Non-basal insulin (U) that belongs to the label window opening at this tick.
     *
     * The basal label reads the BG move of the next 30 minutes as basal work, so the trainer must know
     * whether insulin outside basal entered that window. Two sources are added:
     * - the SMB this tick asks for ([RT.units]). It is known here, synchronously, before the pump and
     *   the database know about it.
     * - every SMB or manual bolus already recorded in the last [BASAL_LEARN_BOLUS_LOOKBACK_MIN]
     *   minutes. This is what catches a bolus the user gave by hand, and also the SMB of the previous
     *   tick while the bolus cache was still catching up.
     *
     * The same SMB can therefore be reported on two rows in a row, and an SMB that the loop finally
     * drops (it can be gated behind a temp-basal confirmation) is still reported. Both are on purpose:
     * the trainer only compares this number with a small threshold, so a repeat marks one extra row as
     * contaminated. Losing a training row costs little; teaching the model that bolus work was basal
     * work is the failure this whole path exists to stop.
     *
     * `NaN` means "not reported" and sends the trainer back to its IOB-jump heuristic. It is returned
     * when the bolus history cannot be read, because a partial sum would look like a proven clean
     * window.
     *
     * PRIMING boluses are left out: that insulin never reaches the body.
     *
     * `internal` so the engine tests can read what the tick reports, instead of only checking that some
     * number was written.
     */
    internal fun basalLearningBolusUnits(rT: RT): Double {
        val smbNowU = (rT.units ?: 0.0).coerceAtLeast(0.0)
        val recentU = try {
            val since = dateUtil.now() - BASAL_LEARN_BOLUS_LOOKBACK_MIN * 60_000L
            getBolusesFromTimeCached(since, ascending = false)
                .filter { it.type == BS.Type.SMB || it.type == BS.Type.NORMAL }
                .sumOf { it.amount }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "BasalLearning: recent bolus read failed, window reported as unknown", e)
            return Double.NaN
        }
        val total = smbNowU + recentU
        return if (total.isFinite()) total else Double.NaN
    }

    /**
     * Carbs on board (g) for the label window opening at this tick.
     *
     * [tickCobGrams] is the value read from `mealData` at the start of the tick, so it is present on
     * every path, including the early exits. [RT.COB] is the fallback, since only the main path fills
     * it. `NaN` means "not reported": a tick that ended before reading `mealData` must not claim zero
     * carbs.
     *
     * `internal` for the same reason as [basalLearningBolusUnits].
     */
    internal fun basalLearningCobGrams(rT: RT): Double {
        work.tickCobGrams.takeIf { it.isFinite() && it >= 0.0 }?.let { return it }
        return rT.COB?.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
    }

    private fun triggerBasalMlTrainingIfNeeded() {
        if (::basalMlTrainingCoordinator.isInitialized) {
            aapsLogger.debug(LTag.APS, "BasalMlTraining: maybeTrainAsync triggered")
            basalMlTrainingCoordinator.maybeTrainAsync()
        }
    }

    private fun runDecisionFinalBasalNeuralStep(rT: RT, diag: DecisionFinalDiagSnapshot): Double {
        val tdd24h = resolveTdd24hForLoop(30.0)
        val activityThreshold = (tdd24h / 24.0) * 0.15
        applyBasalNeuralLearningAndTraining(rT, diag.tbrUph, "FINAL")
        return activityThreshold
    }

    private fun appendDecisionFinalTickLine(diag: DecisionFinalDiagSnapshot, activityThreshold: Double) {
        val tickLine =
            "TICK ts=${aimiWallClockMs()} bg=${diag.bgValue.roundToInt()} d=${aimiFmt1(diag.deltaValue)} iob=${aimiFmt2(work.iob)} act=${aimiFmt3(work.iobActivityNow)} th=${aimiFmt3(activityThreshold)} " +
                "cob=${aimiFmt1(work.cob)} mode=${diag.modeLabel} autodriveState=$causalState.lastAutodriveState pred=${diag.predChunk} " +
                "safety=${work.lastSafetySource} ref=${diag.refractoryStatus} maxIOB=${aimiFmt2(work.maxIob)} maxSMB=${aimiFmt2(work.maxSMB)} " +
                "smb=${aimiFmt2(work.lastSmbProposed)}->${aimiFmt2(work.lastSmbCapped)}->${aimiFmt2(diag.smbFinal)} " +
                "tbr=${aimiFmt2(diag.tbrUph)} src=${work.lastDecisionSource}"
        work.consoleLog.add(tickLine)
    }

    /**
     * Effective-IOB release gate (AIMI-local). Returns the IOB the maxIOB **production** gate should compare
     * against: the ledger IOB partially released toward the (lower) effective-kinetics IOB, throttled by hypo
     * evidence. Returns the ledger unchanged (θ=0, no-op) whenever the feature is disabled, the effective IOB is
     * unavailable, effective ≥ ledger, or any hypo signal fires. Caches [lastIobReleaseExport] for the JSONL.
     * @see EffectiveIobReleaseAuthority
     */
    private fun resolveIobForGate(): Double {
        val enabled = preferences.get(BooleanKey.OApsAIMIEffectiveIobReleaseEnabled)
        val decision = EffectiveIobReleaseAuthority.evaluate(
            EffectiveIobReleaseAuthority.Input(
                enabled = enabled,
                iobLedgerU = work.iob.toDouble(),
                iobEffectiveU = work.tickIobEffectiveU,
                postHypoAuthorityActive = work.lastPostHypoDeliveryAuthority.active,
                postHypoStateOrdinal = causalState.lastPostHypoOrdinal,
                postHypoProb = null,
                minBgRecentMgdl = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            )
        )
        lastIobReleaseExport = AimiDecisionContext.IobReleaseExport(
            enabled = enabled,
            theta = decision.theta,
            iob_ledger_u = decision.iobLedgerU,
            iob_effective_u = decision.iobEffectiveU,
            iob_for_gate_u = decision.iobForGateU,
            released_u = decision.releasedU,
            gate_flips_block_to_allow = decision.iobLedgerU > work.maxIob && decision.iobForGateU <= work.maxIob,
            reason = decision.reasonTag,
        )
        if (decision.releasedU > 0.0) {
            work.consoleLog.add(EffectiveIobReleaseAuthority.formatLogLine(decision))
        }
        return decision.iobForGateU
    }

    private fun logDecisionFinal(tag: String, rT: RT, bg: Double? = null, delta: Float? = null) {
        val diag = appendDecisionFinalSummaryLine(tag, rT, bg, delta)
        val activityThreshold = runDecisionFinalBasalNeuralStep(rT, diag)
        appendDecisionFinalTickLine(diag, activityThreshold)
    }

    // Safety (LGS / hypo): [trySafetyStart] entry.
    private fun buildMealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal): MealSafetyContext {
        val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val advisorFresh = advisorCarbs > 0.0 && (dateUtil.now() - advisorTime) < 60 * 60_000L
        val effectiveLastBolusMs = kotlin.math.max(iobData.lastBolusTime, internalLastSmbMillis).takeIf { it > 0L }
        val manualBolusAgeMin = effectiveLastBolusMs?.let { (dateUtil.now() - it) / 60000.0 }
        return MealSafetyContext(
            mealModeActive = work.mealTime || lunchTime || dinnerTime || work.snackTime || highCarbTime || bfastTime,
            manualBolusAgeMin = manualBolusAgeMin,
            mealAdvisorCarbsFresh = advisorFresh,
            explicitMealTrigger = isExplicitAdvisorRun,
            inferredMealSignal = inferredMealSafetyIntent(),
        )
    }

    private fun inferredMealSafetyIntent(): Boolean {
        val postHypo = work.lastPostHypoDeliveryAuthority
        if (postHypo.active && postHypo.forceMealInterpretationSuppressed) return false
        // 🏃 Effort / post-effort adrenaline rise is not a meal (undeclared context only).
        if (effortSuppressesUndeclaredMeal()) return false

        val patientState = work.lastPatientState
        val patientModeDecision = work.lastPatientModeDecision
        val falseMealSuppression =
            causalState.lastUamHypothesisState?.suppressMealInterpretation == true ||
                patientState?.falseMealSuppression == true
        if (falseMealSuppression) return false

        val phaseEvidence = when (work.lastMealAbsorptionOutput?.phase) {
            MealAbsorptionPhase.FIRST_WAVE,
            MealAbsorptionPhase.SECOND_WAVE,
            MealAbsorptionPhase.INTER_WAVE,
            MealAbsorptionPhase.PEAK_CORRECTION,
            -> true

            else -> false
        }
        val patientModeMealEvidence =
            (patientModeDecision?.mode == PatientMode.FAST_MEAL &&
                patientModeDecision.confidence >= 0.60 &&
                patientModeDecision.mealBias >= 0.75) ||
                (patientModeDecision?.mode == PatientMode.PROLONGED_MEAL &&
                    patientModeDecision.confidence >= 0.64 &&
                    patientModeDecision.mealBias >= 0.68)
        val patientStateMealEvidence =
            (patientState?.mealProb ?: 0.0) >= 0.72 ||
                (
                    patientState?.uamDominant == app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId.MEAL &&
                        patientState.uamDominantConfidence >= 0.60
                    ) ||
                (patientState?.causalPosterior?.supportsMealInterpretation(
                    minConfidence = 0.55,
                    mealMargin = 0.04,
                ) == true)
        return work.lastMealAbsorptionOutput?.mealDeliveryPriority == true ||
            phaseEvidence ||
            patientModeMealEvidence ||
            patientStateMealEvidence
    }

    private fun trySafetyStart(
        bg: Double,
        delta: Float,
        profile: OapsProfileAimi,
        iob: IobTotal,
        noise: Int,
        predBg: Double,
        eventualBg: Double,
        mealContext: MealSafetyContext,
    ): SafetyStartResolution {
        work.lastSafetySource = "CALLED"
        val resolution = resolveSafetyStart(
            bg = bg,
            delta = delta,
            noise = noise,
            predBg = predBg,
            eventualBg = eventualBg,
            currentBasalUph = profile.current_basal,
            lgsThreshold = profile.lgsThreshold,
            mealContext = mealContext,
        )
        resolution.consoleLines.forEach { work.consoleLog.add(it) }
        work.lastSafetySource = resolution.lastSafetySource
        return resolution
    }


    /** Hydrate [MealData.mealCOB] from prefs when advisor triggered and DB COB still zero (latency bypass). */
    private fun hydrateMealDataIfTriggered(mealData: MealData) {
        // We handle the read directly to keep the stack simple in the main method
        val isExplicitAdvisorRun: Boolean = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)

        if (isExplicitAdvisorRun) {
            val fallbackCarbs: Double = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
            // Use explicit comparison (0.0) and safe casting
            if (mealData.mealCOB < 0.1 && fallbackCarbs > 0.0) {
                 mealData.mealCOB = fallbackCarbs
                 work.consoleLog.add("⚡ COB HYDRATION: Injected ${fallbackCarbs.toInt()}g from Advisor Prefs (DB latency bypass)")
            }
        }
    }

    /** T3c brittle mode: dynamic PI basal (basal-first isolation). */
    /** DataLake-only Autodrive tick when T3C basal-authority fusion is off. */
    private fun runT3cAutodriveShadowTick(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        shortAvgDeltaAdj: Float,
    ) {
        try {
            val state = buildT3cAutoDriveState(ctx, shortAvgDeltaAdj) ?: return
            val snapshot = physioAdapter.getLatestSnapshot()
            autodriveEngine.setShadowMode(true)
            autodriveEngine.setIsActive(false)
            autodriveEngine.tick(
                currentState = state,
                profileBasal = profile.current_basal,
                profileIsf = profile.sens,
                lgsThreshold = minOf(90.0, (profile.lgsThreshold?.toDouble() ?: 70.0).coerceAtLeast(70.0)),
                hour = work.hourOfDay,
                steps = snapshot.stepsLast15m,
                hr = snapshot.hrNow,
                rhr = snapshot.rhrResting,
                // No `mpcRaFloorMgdlPerMin`: the floor is a hyper-trajectory feed-forward produced
                // by the engaged branch, and this path has no classification to derive it from. The
                // 0.0 default is the intended value here, not an oversight.
                tickId = ctx.currentTime,
                observationId = raObservationId(ctx),
                // Shadow tick: it enacts nothing, so it must not be labelled as owning the dose.
                engaged = false,
                // Same honesty as the engaged path: `profile.sens` is the dynamic ISF.
                profileIsfIsDynamic = true,
            )
            work.consoleLog.add("👻 [T3c_SHADOW] DataLake tick fired for V3 ML continuity.")
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[T3c_SHADOW] Shadow tick failed silently: ${e.message}")
        }
    }

    private fun buildT3cAutoDriveState(
        ctx: AimiTickContext,
        shortAvgDeltaAdj: Float,
    ): AutoDriveState? {
        return try {
            val snapshot = physioAdapter.getLatestSnapshot()
            val recentEstCarbsT3c = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
            val recentEstTimeT3c = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
            val estAgeMinT3c =
                if (recentEstTimeT3c > 0L) (aimiWallClockMs() - recentEstTimeT3c) / 60000.0
                else Double.MAX_VALUE
            val hasRecentMealEstT3c = recentEstCarbsT3c > 10.0 && estAgeMinT3c in 0.0..45.0
            val applyHypoRecoveryRaT3c = postHypoRecoveryActive() &&
                ctx.mealData.mealCOB < 0.1 &&
                !(work.mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || work.snackTime || hasRecentMealEstT3c)
            val t3cLatentState = updatePhysioLatentState(
                snapshot = snapshot,
                sourceSensor = ctx.glucoseStatus.sourceSensor,
            )
            AutoDriveState.createSafe(
                bg = ctx.glucoseStatus.glucose,
                bgVelocity = (shortAvgDeltaAdj.toDouble() / 5.0),
                iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
                cob = ctx.mealData.mealCOB,
                estimatedSI = (variableSensitivity.toDouble() / 10000.0),
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                physiologicalStressMask = t3cLatentState.toAttentionMask(),
                isNight = work.hourOfDay >= 23 || work.hourOfDay < 6,
                hour = work.hourOfDay,
                steps = snapshot.stepsLast15m,
                hr = snapshot.hrNow,
                rhr = snapshot.rhrResting,
                sourceSensor = ctx.glucoseStatus.sourceSensor,
                maxIOB = work.maxIob,
                maxSMB = work.maxSMB,
                highBgMaxSMB = work.maxSMBHB,
                applyHypoRecoveryRaDampening = applyHypoRecoveryRaT3c,
            )
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[T3c_AD_BASAL] AutoDriveState build failed: ${e.message}")
            null
        }
    }

    private fun proposeT3cAutodriveBasalOnly(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        shortAvgDeltaAdj: Float,
        lgsThresholdMgdl: Double,
    ): AutodriveEngine.BasalOnlyTbrProposal? {
        if (!preferences.get(BooleanKey.OApsAIMIT3cAutodriveBasalAuthority)) return null
        return try {
            val state = buildT3cAutoDriveState(ctx, shortAvgDeltaAdj) ?: return null
            val snapshot = physioAdapter.getLatestSnapshot()
            autodriveEngine.proposeBasalOnlyTbr(
                currentState = state,
                profileBasal = profile.current_basal,
                profileIsf = profile.sens,
                lgsThreshold = lgsThresholdMgdl,
                hour = work.hourOfDay,
                steps = snapshot.stepsLast15m,
                hr = snapshot.hrNow,
                rhr = snapshot.rhrResting,
                tickId = ctx.currentTime,
                observationId = raObservationId(ctx),
                // Same reasoning as the shadow tick: no hyper-trajectory classification on this path.
                mpcRaFloorMgdlPerMin = 0.0,
            )
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[T3c_AD_BASAL] proposeBasalOnlyTbr failed: ${e.message}")
            null
        }
    }

    internal fun executeT3cBrittleMode(
        bg: Double,
        delta: Float,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        accel: Double,
        duraISFminutes: Double,
        duraISFaverage: Double,
        profile: OapsProfileAimi,
        currenttemp: CurrentTemp,
        iob: IobTotal,
        targetBg: Double,
        variableSensitivity: Double,
        maxIob: Double,
        eventualBg: Double,
        rT: RT,
        trajectoryContext: T3cTrajectoryContext? = null,
        @Suppress("UNUSED_PARAMETER") cgmNoise: Double = 0.0,
        autodriveBasalProposal: AutodriveEngine.BasalOnlyTbrProposal? = null,
    ): RT = decideT3cBrittleMode(
        bg = bg,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        accel = accel,
        duraISFminutes = duraISFminutes,
        duraISFaverage = duraISFaverage,
        profile = profile,
        currenttemp = currenttemp,
        iob = iob,
        targetBg = targetBg,
        variableSensitivity = variableSensitivity,
        maxIob = maxIob,
        eventualBg = eventualBg,
        rT = rT,
        trajectoryContext = trajectoryContext,
        autodriveBasalProposal = autodriveBasalProposal,
        exerciseInsulinLockoutActive = work.exerciseInsulinLockoutActive,
        exerciseBasalResumeBgMgdl = EXERCISE_BASAL_RESUME_BG_MGDL,
        causalState.adaptiveMult = causalState.adaptiveMult,
        tree = work.lastPhysiologicalTreeSnapshot,
        effortSmbFactor = work.lastEffortAssessment?.smbFactor,
        ngrBasalMultiplier = work.lastNgrBasalMultiplier,
        preferences = preferences,
        consoleLog = work.consoleLog,
        adaptiveFactor = object : AimiT3cAdaptiveFactor {
            override fun factor(
                bg: Double,
                basal: Double,
                accel: Double,
                duraMin: Double,
                duraAvg: Double,
                iob: Double,
            ): Double = basalNeuralLearner.getT3cAdaptiveFactor(
                bg = bg,
                basal = basal,
                accel = accel,
                duraMin = duraMin,
                duraAvg = duraAvg,
                iob = iob,
                physioFeatures = currentBasalPhysioFeatures(),
            )
        },
        hrSnapshot = AimiT3cHrSnapshot { physioAdapter.getLatestSnapshot() },
        lookbacks = object : AimiT3cLookbacks {
            override fun postHypoRecoveryActive(): Boolean =
                this@DetermineBasalaimiSMB2.postHypoRecoveryActive()

            override fun minBgInLastMinutes(lookbackMinutes: Int): Double =
                this@DetermineBasalaimiSMB2.minBgInLastMinutes(lookbackMinutes)
        },
        tail = object : AimiT3cTickTail {
            override fun applyBasalNeuralLearning(rT: RT, tbrUph: Double) {
                applyBasalNeuralLearningAndTraining(rT, tbrUph, govTag = "T3C")
            }

            override fun markFinalLoopDecision(rT: RT, currentTemp: CurrentTemp) {
                markFinalLoopDecisionFromRT(rT, currentTemp)
            }
        },
    )
}

enum class AutodriveState {
    IDLE,
    WATCHING,
    ENGAGED
}
