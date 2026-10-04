package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
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
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEffectProbe
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiEffectSink
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiLatestSmbCached
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
import app.aaps.plugins.aps.openAPSAIMI.effects.decideMealAdvisorOrReturn
import app.aaps.plugins.aps.openAPSAIMI.effects.decideT3cBrittleMode
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
import app.aaps.plugins.aps.openAPSAIMI.ISF.CommandedIsf
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
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiCorpusPruner
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbTrainingRowBuffer
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingCsvHeader
import app.aaps.plugins.aps.openAPSAIMI.ml.ensureCurrent
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
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdAbsorptionGuard
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.PhysiologicalStressMaskBuilder
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.collections.asSequence
import app.aaps.core.interfaces.profile.EffectiveProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant as KotlinInstant
import kotlinx.datetime.LocalDate as KxLocalDate
import kotlinx.datetime.LocalTime as KxLocalTime
import kotlinx.serialization.json.JsonObject
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiJson
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonArr
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonObj
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class AimiDecisionContext(
    val event_id: String,
    val timestamp: Long,
    val trigger: String,
    val baseline_state: BaselineState,
    val adjustments: Adjustments = Adjustments(),
    var outcome: Outcome? = null
) {
    data class BaselineState(
        /**
         * Historical field. Despite its name it carries `profile.sens`, which is the **command**
         * sensitivity (dynamic ISF x physiological factor), not the profile block value. Kept
         * populated so existing analysis keeps working; use [command_isf_mgdl] in new work and
         * [profile_isf_static_mgdl] when a static baseline is needed.
         * See `docs/adr/0002-sensitivity-three-levels.md`.
         */
        val profile_isf_mgdl: Double,
        val profile_basal_uph: Double,
        val current_bg_mgdl: Double,
        val cob_g: Double,
        val iob_u: Double,
        /** User profile ISF block for this time of day. Static within the tick. */
        val profile_isf_static_mgdl: Double? = null,
        /**
         * `ctx.profile.sens`, read once at tick bootstrap. Same value as [profile_isf_mgdl] on every
         * tick, because both are that one read.
         *
         * This is **not** the sensitivity the SMB dose used. It reaches two places only: the control
         * barrier anchor in `AutodriveEngine.profileAnchoredSafetySi`, and the endogenous basal
         * bridge in `EndogenousBasalBridgePolicy.computeBridgeRateUph`. The delivered SMB is sized
         * from the fused PKPD sensitivity instead — see [variable_sens_mgdl] for where to read it.
         *
         * Even the barrier rarely reads it: `InsulinActionModel.LEGACY_CONTROL_COEFFICIENT` floors
         * the coefficient at the value for 45 mg/dL/U, and on the 2026-08-15 package the exported
         * `si_metabolic` sat exactly on that floor on 61 of 72 ticks.
         */
        val command_isf_mgdl: Double? = null,
        /** Which source produced the dynamic value this tick. See [IsfSourceTelemetry]. */
        val isf_source: String? = null,
        /** Age (ms) of the cached dynamic entry that was used, when one was used. */
        val isf_age_ms: Long? = null,
        /** Key of the cached entry used. Distinguishes two entries sharing a 30-minute bucket. */
        val isf_cache_key: Long? = null,
        /** Glucose the cached value was computed for (the key's within-bucket remainder). */
        val isf_cache_glucose_mgdl: Long? = null,
        /**
         * Sensitivity the **outcomes** imply, in mg/dL per U, measured as `-dBG / insulin absorbed`
         * over clean falls. See `ObservedSensitivityMeter`.
         *
         * A fall is counted only when it lasts 30 to 120 minutes, drops at least 25 mg/dL, has no
         * carbs on board and no meal in the 30 minutes before it, shows a mean rate of glucose
         * appearance below 0.30 mg/dL/min, and cost at least 0.8 U. The insulin credited is the fall
         * in IOB, plus the basal above the profile rate, plus the SMBs decided inside the window.
         *
         * The median is `null` below three windows, never `0.0`: zero would read as a real
         * sensitivity of zero. [isf_obs_window_count] says how far the instrument is from being able
         * to answer.
         *
         * These fields are **strictly passive**. Nothing in the dosing chain reads them. They exist
         * for one purpose: to be compared with [command_isf_mgdl], so that the question "does the ISF
         * chain estimate the right quantity" can finally be answered from exported data.
         *
         * `var`, and written after this object is built, like the fields below.
         */
        var isf_obs_median_mgdl: Double? = null,
        /** Same measure, night windows only (local hour 0 to 8). */
        var isf_obs_night_median_mgdl: Double? = null,
        /** Same measure, day windows only. */
        var isf_obs_day_median_mgdl: Double? = null,
        /** How many windows the look-back holds. Reported even when the median is `null`. */
        var isf_obs_window_count: Int? = null,
        /** How many of them are night windows. */
        var isf_obs_night_count: Int? = null,
        /** How many of them are day windows. */
        var isf_obs_day_count: Int? = null,
        /** End time of the most recent window, so a reading can be aged. */
        var isf_obs_last_window_end_ms: Long? = null,
        /** Sensitivity of that most recent window alone. */
        var isf_obs_last_window_mgdl: Double? = null,
        /** Fall of that window, mg/dL. */
        var isf_obs_last_window_drop_mgdl: Double? = null,
        /** Insulin credited to that window, U. */
        var isf_obs_last_window_absorbed_u: Double? = null,
        /**
         * Shadow late fat damping window: the late part of an absorption episode while a large
         * insulin stack is already working. Strictly passive — nothing in the dosing chain reads it.
         * It exists to be compared with [late_fat_rise_flag] before any wiring is decided.
         */
        var late_fat_damping_window: Boolean? = null,
        /** The old `isLateFatProteinRise` predicate for the same tick, to measure the divergence. */
        var late_fat_rise_flag: Boolean? = null,
        /** Minutes since the absorption episode started, `null` when there is no episode. */
        var late_fat_onset_age_min: Int? = null,
        /**
         * Harmonia counterfactual, strictly passive. Nothing in the dosing chain reads these fifteen
         * fields, and nothing may ever read them: they exist only so that what Harmonia proposed can
         * be compared, after the fact, with what was really done.
         *
         * Harmonia is asked for a plan before the tick knows whether the SMB channel was zeroed for
         * safety. It is then judged on exactly that verdict, and never told it, nor told that it was
         * refused. These fields write down the missing message and its price:
         *
         *  - `harmonia_cf_*` — what Harmonia would propose if it were told, and whether that differs
         *    from what it really proposed. [harmonia_cf_changes_proposal] is the whole question.
         *  - `harmonia_block_*` — the size of the refusal. [harmonia_block_stake_u] is signed:
         *    negative means the refusal **added** insulin, because it refused an under-dose.
         *  - `harmonia_verdict_*` — the mirrored safety verdict.
         *    [harmonia_verdict_known_to_engine] is the witness of inertia: it is written `false`, and
         *    stays `false` for as long as nothing feeds the verdict back into the decision.
         *  - `harmonia_prev_*` — what refused the previous tick, and for how many ticks in a row.
         *  - [harmonia_tree_risk_divergence] — set only when the tree trunk and Harmonia disagree on
         *    the risk level.
         *
         * `var`, and written after this object is built, like the fields above. See
         * `HarmoniaCounterfactual`.
         */
        var harmonia_cf_rule: String? = null,
        /** Action Harmonia would propose knowing the verdict. */
        var harmonia_cf_action: String? = null,
        /** Basal that goes with that action, U/h. */
        var harmonia_cf_basal_uph: Double? = null,
        /** `true` only when knowing the verdict would change the proposal. */
        var harmonia_cf_changes_proposal: Boolean? = null,
        /** The refused request was above the profile basal by more than one pump step. */
        var harmonia_block_was_escalation: Boolean? = null,
        /** Request minus profile basal, U/h, signed. */
        var harmonia_block_delta_uph: Double? = null,
        /** Insulin the refusal moved over the applied duration, U, signed. */
        var harmonia_block_stake_u: Double? = null,
        /** Applied rate minus requested rate, U/h. */
        var harmonia_applied_gap_uph: Double? = null,
        /** Mirrored verdict: a safety rule zeroed the SMB of this tick. */
        var harmonia_verdict_critical_safety: Boolean? = null,
        /** Mirrored verdict: the active context suppresses the SMB of this tick. */
        var harmonia_verdict_context_suppress: Boolean? = null,
        /** Mirrored verdict: a manual meal mode is declared, which exempts the channel. */
        var harmonia_verdict_meal_mode: Boolean? = null,
        /** Witness of inertia. Written `false`: the engine is never told the verdict. */
        var harmonia_verdict_known_to_engine: Boolean? = null,
        /** Runtime blocker of the previous tick, `null` when there was none. */
        var harmonia_prev_blocker: String? = null,
        /** How many ticks in a row the same blocker has refused Harmonia. */
        var harmonia_prev_blocked_streak: Int? = null,
        /** `"tronc=X|harmonia=Y"` when the two risk views differ, `null` when they agree. */
        var harmonia_tree_risk_divergence: String? = null,
        /** Fast estimator 1: Kalman-filtered raw ISF. */
        val isf_kalman_fast_mgdl: Double? = null,
        /** Fast estimator 2: IsfAdjustmentEngine output. */
        val isf_adj_engine_mgdl: Double? = null,
        /** Slow floor: profile/TDD fusion scaled by PKPD. */
        val isf_fused_slow_mgdl: Double? = null,
        /** Weight given to the fast estimators in the final blend. */
        val isf_trust_fast: Double? = null,
        /** Delta-driven correction factor applied after the blend. */
        val isf_dynamic_factor: Double? = null,
        /** AutoISF-style trajectory multiplier (1.0 when the layer did not fire). */
        val isf_trajectory_multiplier: Double? = null,
        /** Estimated rate of glucose appearance (mg/dL/min) from the continuous state estimator. */
        val estimated_ra_mgdl_per_min: Double? = null,
        /** Physiological ISF factor of the tick, bounds [0.85, 1.15]. Applied once since ADR 0007. */
        val physio_isf_factor: Double? = null,
        /**
         * Commanded sensitivity before the profile-relative floor, mg/dL per U.
         *
         * Next to `command_isf_mgdl` it says how much the floor moved this tick, which no exported
         * field could say while the shadow witness was reading the already-floored value.
         */
        val isf_pre_floor_mgdl: Double? = null,
        /**
         * Stress-ISF-floor signature of the tick: does it hold, and why.
         *
         * Written on every tick whether `BooleanKey.OApsAIMIStressIsfFloor` is armed or not, so the
         * gesture can be measured before it is armed. See `StressIsfFloor`.
         */
        val stress_isf_floor_active: Boolean? = null,
        val stress_isf_floor_reason: String? = null,
        /**
         * Sensitivity commanded with the floor at 1.0 x profile, mg/dL per U.
         *
         * Present whenever the signature is active, armed or not. Absent means "the signature does
         * not hold", not zero.
         */
        val stress_isf_floor_isf_mgdl: Double? = null,
        /**
         * Awake resting heart rate the signature was measured against, bpm.
         *
         * Absent means the gesture stood down for want of data. It is not the same quantity as the
         * exported `rhr_resting_bpm`, which is the lowest SLEEPING value of the last seven days and
         * was pinned at 50 on every tick of three packages. See `AwakeRestingHeartRate`.
         */
        val stress_floor_awake_resting_bpm: Int? = null,
        /**
         * Dose-facing sensitivity before the stress floor, mg/dL per U.
         *
         * `var`, and set late: the value only exists once the working sensitivity is finalised,
         * thousands of lines after this object is built at tick bootstrap. Without these three fields
         * no support package can say whether the wiring works — `variable_sens_mgdl` is read after
         * every multiplier and cannot show what the floor moved.
         */
        var stress_floor_isf_before_mgdl: Double? = null,
        /** Dose-facing sensitivity after the stress floor, mg/dL per U. */
        var stress_floor_isf_after_mgdl: Double? = null,
        /** True when the floor really raised the dose-facing sensitivity this tick. */
        var stress_floor_raised_isf: Boolean? = null,
        /** Shadow: sensitivity an unconditional exit clamp relative to the profile would command. */
        val isf_profile_relative_shadow_mgdl: Double? = null,
        /** Shadow: true when that clamp would have changed the value. */
        val isf_profile_relative_bound_hit: Boolean? = null,
        /** Shadow: sensitivity ratio measured from outcomes, dimensionless, 1.0 = the profile is right. */
        val sensitivity_ratio_r: Double? = null,
        /** Shadow: sensitivity this ratio would command, i.e. profile x ratio, bounded. */
        val isf_shadow_s_mgdl: Double? = null,
        /** Shadow: how many closed windows have been folded in so far. */
        val sensitivity_observations: Int? = null,
        /**
         * Hyper-trajectory Ra floor for this tick, in mg/dL/min, or null when no floor applied.
         *
         * `var`, and set late: the floor is computed inside the engaged Autodrive branch, thousands of
         * lines after this object is built at tick bootstrap. As a `val` read at construction it
         * exported `null` on every tick, for ever — the one instrument added to make the floor
         * measurable could not measure it. Written through [markHtrRaFloorForExport].
         */
        var htr_ra_floor_mgdl_per_min: Double? = null,
        /**
         * Ra the controller actually used this tick, in mg/dL/min.
         *
         * Same reason for being a `var`: read at bootstrap it carried the **previous** tick's estimate,
         * which makes the comparison with [htr_ra_floor_mgdl_per_min] meaningless — that comparison is
         * the entire point of exporting the two side by side.
         */
        var estimated_ra_used_mgdl_per_min: Double? = null,
        /** Diagnostic: how many times the meal filter advanced, and how many calls were replays. */
        var ra_estimator_advances: Long? = null,
        var ra_estimator_replayed_calls: Long? = null,
        /**
         * Shadow: Ra the filter would report with its insulin term aligned on the controller's.
         *
         * Never dosed on. Answers whether aligning `InsulinActionModel.ESTIMATOR_TAU_MIN` would pin Ra
         * above the 0.6 / 0.7 / 0.8 gates, which is the one thing the medians could not settle.
         */
        var ra_aligned_tau_shadow_mgdl_per_min: Double? = null,
        /**
         * Writes to the SMB refused after `finalizeAndCapSMB` sealed the tick, and their total size.
         *
         * A non-zero count means a component tried to raise the dose past the terminal. That is the
         * signal `AiAuditor` and the legacy meal paths never produced before the seal existed.
         */
        var smb_seal_refused_count: Int? = null,
        var smb_seal_refused_total_u: Double? = null,
        /** Post-seal raises allowed because the owner is a user-initiated action (meal advisor). */
        var smb_seal_allowed_raise_count: Int? = null,
        /** Coefficient the barrier used, and what it would be with the floor removed. */
        var cbf_coefficient_used: Double? = null,
        var cbf_coefficient_unfloored: Double? = null,
        /** Shadow: insulin the barrier permitted, floored vs unfloored, in U per 5 min. */
        var cbf_permitted_u: Double? = null,
        var cbf_permitted_unfloored_u: Double? = null,
        /** Profile ISF the barrier was handed, so the two above are interpretable. */
        var cbf_profile_isf_mgdl: Double? = null,
        /**
         * Whether the Autodrive gate let the MPC run this tick.
         *
         * Everything the barrier exports only exists on engaged ticks. Without this the disengaged
         * ticks are a blank, and a blank reads as "nothing happened" rather than "the gate was shut".
         *
         * Observation only. Nothing dose-facing reads these three fields.
         */
        var autodrive_gate_engaged: Boolean? = null,
        /** Stable token for why the gate opened or stayed shut, for counting. */
        var autodrive_gate_kind: String? = null,
        /** The same reason with its live numbers, for reading. */
        var autodrive_gate_reason: String? = null,
        /**
         * Shadow measurement of the rise ceiling guard (`RiseCeilingGuard`).
         *
         * Written on every tick that reaches the universal SMB exit, whether
         * [app.aaps.core.keys.BooleanKey.OApsAIMIRiseCeilingGuard] is on or off. That is the whole
         * point: the thresholds were chosen after seeing the data, so they need a measurement made
         * in advance before the gesture is armed.
         */
        var rise_ceiling_guard_would_block: Boolean? = null,
        /** Reason token plus its live numbers (ticks in a row at the ceiling, rise). */
        var rise_ceiling_guard_reason: String? = null,
        /** How many ticks in a row the bolus has come out at a ceiling, this tick included. */
        var rise_ceiling_guard_repeats: Int? = null,
        /**
         * Bolus the guard would have refused, U.
         *
         * Set only when the verdict is "block", so a tick that did not block leaves the field absent
         * instead of reporting a zero that means nothing.
         */
        var rise_ceiling_guard_withheld_u: Double? = null,
        /**
         * Effort SMB reduction, as actually applied at the universal SMB exit.
         *
         * `_requested` is what the effort belief asked for, `_applied` is what was used after the
         * confirmed-meal floor, and the two unit fields bracket the reduction. Before this existed the
         * multiplier could only be recovered by parsing the narrative, which cost one wrong
         * attribution (see docs/AIMI_NEXT_SESSION.md Part A-quater).
         */
        var effort_smb_factor_requested: Double? = null,
        var effort_smb_factor_applied: Double? = null,
        var effort_smb_before_u: Double? = null,
        var effort_smb_after_u: Double? = null,
        /** True when the confirmed-meal floor raised the multiplier this tick. */
        var effort_smb_floored_by_meal: Boolean? = null,
        /**
         * Aggressive-rise floor budget state. The episode budget is out of the dose path, but its
         * accounting still runs, and its absence from the export is why the 2026-08-10 diagnosis
         * rested on inference.
         */
        var rise_floor_spent_u: Double? = null,
        var rise_floor_minutes_since_contribution: Double? = null,
        /**
         * The `variableSensitivity` member, in mg/dL/U, read late in the tick.
         *
         * This is a **post-dose** read, not the sensitivity the dose used. It is taken in
         * `markEstimatorDiagnosticsForExport`, which runs after `applyEndoAndActivityAdjustments`
         * and `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity` have already multiplied
         * `variableSensitivity` by the endo, activity and physiological factors, and long after the
         * MPC and the tube advisor sized the dose.
         *
         * The number the delivered dose used is the fused PKPD sensitivity, exported as
         * `adjustments.intelligence_snapshot_v1.isf.fused_mgdl_per_u`. It is what the MPC reads as
         * `estimatedSI` and what the tube advisor reports as
         * `adjustments.tube_advisor.isf_used_mgdl_per_u`. On the 2026-08-15 package the two agreed
         * on 142 of 160 ticks, while this field agreed with the tube on 0 of 160.
         *
         * Do not compare this field with anything dose-related. An earlier note here claimed a 0.70
         * to 2.65 ratio against [command_isf_mgdl]; that was obtained by inverting the tube's kappa,
         * which cannot be inverted below about 29.7 mg/dL/U because the curve saturates there. The
         * direct instruments contradict it.
         */
        var variable_sens_mgdl: Double? = null,
    )
    data class Adjustments(
        var dynamic_isf: DynamicIsf? = null,
        var basal_safety_cap: BasalCap? = null,
        var physiological_context: PhysioContext? = null,
        /** IOB surveillance / anti-stacking snapshot for AIMI_Decisions.jsonl analysis */
        var iob_surveillance: IobSurveillanceExport? = null,
        /** Effective-IOB release (maxIOB gate) decision snapshot for AIMI_Decisions.jsonl analysis */
        var iob_release: IobReleaseExport? = null,
        /** LGS / predictive hypo safety snapshot (Phase 5 export) */
        var safety_risk: SafetyRiskExport? = null,
        /** Dual scenario curves (CLINICAL_FLOOR + SCENARIO_BEST) */
        var scenario_projection: ScenarioProjectionExport? = null,
        /** Hyper Trajectory Release (projection → SMB floor) */
        var hyper_trajectory_release: HyperTrajectoryReleaseExport? = null,
        /** Physiological phase + behavioral risk policy (HTR / MPC / scenario) */
        var physiological_phase: PhysiologicalPhaseExport? = null,
        /** Multi-label body-state pattern catalog (RBT meta + HTR caps) */
        var physiological_patterns: JsonObject? = null,
        /** Unified meal absorption belief + phase (IOB / HTR / SMB priority) */
        var meal_absorption_phase: MealAbsorptionPhaseExport? = null,
        /** PKPD vs scenario eventual-BG divergence audit (PredictionDivergenceAuditor) */
        var pred_divergence: JsonObject? = null,
        /** Recursive Belief Tree — full JSON object for AIMI_Decisions.jsonl */
        var recursive_belief: JsonObject? = null,
        /** Progressive RBT authority gate decision for shadow -> soft -> hard transitions. */
        var recursive_authority_gate: JsonObject? = null,
        /** Replay-oriented quality bridge built from existing guards and shadow channels. */
        var replay_quality: JsonObject? = null,
        /** Diagnostic-only ordered SMB cap chain; never consumed by dose calculation. */
        var smb_binding_trace: JsonObject? = null,
        /** Shared latent physiological state reused across engines for the tick. */
        var physio_latent_state: JsonObject? = null,
        /** Multi-hypothesis UAM interpretation for meal vs endogenous vs stress vs rebound. */
        var uam_hypotheses: JsonObject? = null,
        /** Unified patient-state snapshot bridging physiology, meal state and user intent. */
        var patient_state: JsonObject? = null,
        /** High-level patient mode and strategy derived from the shared state. */
        var patient_mode: JsonObject? = null,
        /** AIMI Harmonia physiological tree — deploys insulin intent; Harmonia arbitrates dose. */
        var physiological_tree: JsonObject? = null,
        /** Lot A endocrine belief (WCycle + hypo dampen) — context for tree/Harmonia/forensics. */
        var endocrine_belief: JsonObject? = null,
        /** Cascade meal language (Tree→Harmonia→Auditor) — meal_certainty_v1. */
        var meal_certainty: JsonObject? = null,
        /** Cascade D4 / C1 — single dose-facing eventual + minPred for the tick. */
        var dose_terminal_snapshot: JsonObject? = null,
        /**
         * Straight-line tube decision that set this tick's SMB ceiling.
         *
         * `dose_terminal_snapshot` is republished at `late_pkpd`, after the tube has already frozen
         * the caps, so the exported snapshot is **not** the input the tube used. This block carries
         * the inputs of the stage that actually decided, so the two can be compared.
         */
        var tube_advisor: JsonObject? = null,
        /** Wave4 H3 — soft-floor/EGP path-min (production curves + study JSON raw/soft). */
        var pkpd_soft_floor: JsonObject? = null,
        /**
         * What the MPC asked for before the control barrier, and the barrier's own terms.
         *
         * Separates "the solver wanted nothing" from "the barrier suspended everything", which
         * `model_output_u` alone cannot do because it is read after the barrier.
         */
        var control_barrier: JsonObject? = null,
        /** Lot 2 — invariants terminaux du canal basal: taux avant/apres et invariant liant. */
        var basal_terminal: JsonObject? = null,
        /**
         * Universal Adaptive Basal scaling for this tick: heuristic, learned head, and the blend.
         *
         * Carries `n_raw`, the learned value BEFORE the runtime clamp. Only the blended result used to
         * be exported (as free text in the narrative), so a model stuck on one constant and a model
         * that had really learned the same number were impossible to tell apart in a log.
         */
        var adaptive_basal: JsonObject? = null,
        /** AIMI Harmonia simulated production branch; virtual only, never applied to the real pump. */
        var harmonia_simulation: JsonObject? = null,
        /** AIMI Harmonia production owner state; basal-first only and safety-gated. */
        var harmonia_production: JsonObject? = null,
        /** Harmonia SMB authority arbitration (ACCEPT / LIFT_WITHIN_ENVELOPE / REDUCE). */
        var harmonia_smb_authority: JsonObject? = null,
        /** Unified intelligence snapshot (kinetics, causal, ISF, predictions) — intelligence_snapshot_v1. */
        var intelligence_snapshot: JsonObject? = null,
        /** Runtime ownership of T3C for this tick: native RBT, legacy fallback, or safety terminal. */
        var t3c_runtime_ownership: T3cRuntimeOwnershipExport? = null,
        /** Loop vs auditor binding for this tick (sync disposition; follow-up may arrive async). */
        var auditor_tick: JsonObject? = null,
        /**
         * ISF and target of this tick at every level, plus the state of the auditor profile-factor
         * key. Written on every tick, key on or off. Observation only; no dose reads it.
         */
        var auditor_profile_factors: JsonObject? = null,
        /**
         * Post-hypo delivery authority for this tick: whether it applied, which condition declined
         * it, and the SMB before / after its cap. See `docs/adr/0006-autodrive-consumes-authority.md`.
         */
        var post_hypo_delivery: JsonObject? = null,
        /**
         * Running share of the delivered insulin the model actually asked for, against the share the
         * floors added. Observation only; no dose reads it. See `InsulinOriginMeter`.
         */
        var insulin_origin: JsonObject? = null,
    )

    data class T3cRuntimeOwnershipExport(
        val mode: String,
        val native_owner_active: Boolean,
        val legacy_fallback_allowed: Boolean,
        val reason: String,
    )

    data class MealAbsorptionPhaseExport(
        val phase: String,
        val belief: Double,
        val reason: String,
        val memory_active: Boolean,
        val wave_count: Int,
        val meal_delivery_priority: Boolean,
        val chrono_prior: Double,
        val kinetic_score: Double,
        val trajectory_score: Double,
        val physio_score: Double,
    )

    data class PhysiologicalPhaseExport(
        val phase: String,
        val confidence: Double,
        val behavioral_risk: String,
        val reason: String,
        val extended_dawn_guard: Boolean,
        val scenario_best_capped: Boolean,
        val max_htr_tier: String,
        val smb_floor_cap_u: Double?,
        val physio_smb_factor_fused: Double?,
        val physio_phase_source: String?,
    )

    data class HyperTrajectoryReleaseExport(
        val active: Boolean,
        val tier: String,
        val dev_above_target_mgdl: Double,
        val projected_dev_mgdl: Double,
        val severity_weight: Double,
        val absorption_offset_mgdl: Double,
        val smb_floor_u: Double,
        val v3_smb_before_u: Double,
        val v3_smb_after_u: Double,
        val suppress_traj_basal_shift: Boolean,
        val hypo_min_pred_ignored: Boolean,
        val reason: String,
    )

    data class SafetyRiskExport(
        val phase: String,
        val predictive_hypo_suppressed: Boolean,
        val safety_gate: String,
        val halt_remaining_pipeline: Boolean,
        val meal_context_active: Boolean,
        val meal_rise_confirmed: Boolean,
        val composite_min_mgdl: Double,
        val pred_bg_mgdl: Double,
        val eventual_bg_mgdl: Double,
        val uam_terminal_mgdl: Double?,
        val hypo_threshold_mgdl: Double,
        val decision_composite_min_mgdl: Double?,
        val decision_hypo_threshold_mgdl: Double?,
        val reconcile_delta_mgdl: Double?,
    )

    data class ScenarioProjectionExport(
        val floor_terminal_mgdl: Double,
        val floor_path_min_mgdl: Double,
        val best_terminal_mgdl: Double,
        val best_path_min_mgdl: Double,
        /** Pre meal-absorption-lift path-min used by Clamp / DoseTerminal gates. */
        val best_gate_path_min_mgdl: Double,
        val best_gate_path_min_hit_floor: Boolean,
        val terminal_gap_mgdl: Double,
        val trajectory_type: String?,
        val contributors: List<String>,
    )

    /**
     * Effective-IOB release decision (maxIOB gate) for external analytics. `gate_flips_block_to_allow` marks the
     * safety-relevant event: the ledger IOB would block production but the hypo-governed release lets it through.
     */
    data class IobReleaseExport(
        val enabled: Boolean,
        val theta: Double,
        val iob_ledger_u: Double,
        val iob_effective_u: Double?,
        val iob_for_gate_u: Double,
        val released_u: Double,
        val gate_flips_block_to_allow: Boolean,
        val reason: String,
    )

    /**
     * One row-friendly snapshot for external analytics (plateau + high IOB + predicted drop).
     */
    data class DynamicIsf(
        var final_value_mgdl: Double = 0.0,
        val modifiers: MutableList<Modifier> = mutableListOf()
    )
    data class Modifier(val source: String, val factor: Double, val clinical_reason: String)
    data class BasalCap(val status: String, val limit_uph: Double, val safety_reason: String)
    data class PhysioContext(val hormonal_cycle_phase: String, val physical_activity_mode: String)
    data class Outcome(val clinical_decision: String, val dosage_u: Double, val target_basal_uph: Double? = null, val narrative_explanation: String)

    fun toMedicalJson(): String {
        return try {
            val json = JsonObj()
            json.put("event_id", event_id)
            json.put("timestamp", timestamp)
            json.put("trigger", trigger)

            val base = JsonObj()
            base.put("profile_isf_mgdl", baseline_state.profile_isf_mgdl)
            base.put("profile_basal_uph", baseline_state.profile_basal_uph)
            base.put("current_bg_mgdl", baseline_state.current_bg_mgdl)
            base.put("cob_g", baseline_state.cob_g)
            base.put("iob_u", baseline_state.iob_u)
            base.put("profile_isf_static_mgdl", baseline_state.profile_isf_static_mgdl ?: AimiJson.NULL)
            base.put("command_isf_mgdl", baseline_state.command_isf_mgdl ?: AimiJson.NULL)
            base.put("isf_source", baseline_state.isf_source ?: AimiJson.NULL)
            base.put("isf_age_ms", baseline_state.isf_age_ms ?: AimiJson.NULL)
            base.put("isf_cache_key", baseline_state.isf_cache_key ?: AimiJson.NULL)
            base.put("isf_cache_glucose_mgdl", baseline_state.isf_cache_glucose_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_median_mgdl", baseline_state.isf_obs_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_night_median_mgdl", baseline_state.isf_obs_night_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_day_median_mgdl", baseline_state.isf_obs_day_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_window_count", baseline_state.isf_obs_window_count ?: AimiJson.NULL)
            base.put("isf_obs_night_count", baseline_state.isf_obs_night_count ?: AimiJson.NULL)
            base.put("isf_obs_day_count", baseline_state.isf_obs_day_count ?: AimiJson.NULL)
            base.put("isf_obs_last_window_end_ms", baseline_state.isf_obs_last_window_end_ms ?: AimiJson.NULL)
            base.put("isf_obs_last_window_mgdl", baseline_state.isf_obs_last_window_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_last_window_drop_mgdl", baseline_state.isf_obs_last_window_drop_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_last_window_absorbed_u", baseline_state.isf_obs_last_window_absorbed_u ?: AimiJson.NULL)
            base.put("late_fat_damping_window", baseline_state.late_fat_damping_window ?: AimiJson.NULL)
            base.put("late_fat_rise_flag", baseline_state.late_fat_rise_flag ?: AimiJson.NULL)
            base.put("late_fat_onset_age_min", baseline_state.late_fat_onset_age_min ?: AimiJson.NULL)
            base.put("harmonia_cf_rule", baseline_state.harmonia_cf_rule ?: AimiJson.NULL)
            base.put("harmonia_cf_action", baseline_state.harmonia_cf_action ?: AimiJson.NULL)
            base.put("harmonia_cf_basal_uph", baseline_state.harmonia_cf_basal_uph ?: AimiJson.NULL)
            base.put("harmonia_cf_changes_proposal", baseline_state.harmonia_cf_changes_proposal ?: AimiJson.NULL)
            base.put("harmonia_block_was_escalation", baseline_state.harmonia_block_was_escalation ?: AimiJson.NULL)
            base.put("harmonia_block_delta_uph", baseline_state.harmonia_block_delta_uph ?: AimiJson.NULL)
            base.put("harmonia_block_stake_u", baseline_state.harmonia_block_stake_u ?: AimiJson.NULL)
            base.put("harmonia_applied_gap_uph", baseline_state.harmonia_applied_gap_uph ?: AimiJson.NULL)
            base.put("harmonia_verdict_critical_safety", baseline_state.harmonia_verdict_critical_safety ?: AimiJson.NULL)
            base.put("harmonia_verdict_context_suppress", baseline_state.harmonia_verdict_context_suppress ?: AimiJson.NULL)
            base.put("harmonia_verdict_meal_mode", baseline_state.harmonia_verdict_meal_mode ?: AimiJson.NULL)
            base.put("harmonia_verdict_known_to_engine", baseline_state.harmonia_verdict_known_to_engine ?: AimiJson.NULL)
            base.put("harmonia_prev_blocker", baseline_state.harmonia_prev_blocker ?: AimiJson.NULL)
            base.put("harmonia_prev_blocked_streak", baseline_state.harmonia_prev_blocked_streak ?: AimiJson.NULL)
            base.put("harmonia_tree_risk_divergence", baseline_state.harmonia_tree_risk_divergence ?: AimiJson.NULL)
            base.put("isf_kalman_fast_mgdl", baseline_state.isf_kalman_fast_mgdl ?: AimiJson.NULL)
            base.put("isf_adj_engine_mgdl", baseline_state.isf_adj_engine_mgdl ?: AimiJson.NULL)
            base.put("isf_fused_slow_mgdl", baseline_state.isf_fused_slow_mgdl ?: AimiJson.NULL)
            base.put("isf_trust_fast", baseline_state.isf_trust_fast ?: AimiJson.NULL)
            base.put("isf_dynamic_factor", baseline_state.isf_dynamic_factor ?: AimiJson.NULL)
            base.put("isf_trajectory_multiplier", baseline_state.isf_trajectory_multiplier ?: AimiJson.NULL)
            base.put("estimated_ra_mgdl_per_min", baseline_state.estimated_ra_mgdl_per_min ?: AimiJson.NULL)
            base.put("physio_isf_factor", baseline_state.physio_isf_factor ?: AimiJson.NULL)
            base.put("isf_pre_floor_mgdl", baseline_state.isf_pre_floor_mgdl ?: AimiJson.NULL)
            base.put("stress_isf_floor_active", baseline_state.stress_isf_floor_active ?: AimiJson.NULL)
            base.put("stress_isf_floor_reason", baseline_state.stress_isf_floor_reason ?: AimiJson.NULL)
            base.put("stress_isf_floor_isf_mgdl", baseline_state.stress_isf_floor_isf_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_awake_resting_bpm", baseline_state.stress_floor_awake_resting_bpm ?: AimiJson.NULL)
            base.put("stress_floor_isf_before_mgdl", baseline_state.stress_floor_isf_before_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_isf_after_mgdl", baseline_state.stress_floor_isf_after_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_raised_isf", baseline_state.stress_floor_raised_isf ?: AimiJson.NULL)
            base.put("isf_profile_relative_shadow_mgdl", baseline_state.isf_profile_relative_shadow_mgdl ?: AimiJson.NULL)
            base.put("isf_profile_relative_bound_hit", baseline_state.isf_profile_relative_bound_hit ?: AimiJson.NULL)
            base.put("sensitivity_ratio_r", baseline_state.sensitivity_ratio_r ?: AimiJson.NULL)
            base.put("isf_shadow_s_mgdl", baseline_state.isf_shadow_s_mgdl ?: AimiJson.NULL)
            base.put("sensitivity_observations", baseline_state.sensitivity_observations ?: AimiJson.NULL)
            base.put("htr_ra_floor_mgdl_per_min", baseline_state.htr_ra_floor_mgdl_per_min ?: AimiJson.NULL)
            base.put("estimated_ra_used_mgdl_per_min", baseline_state.estimated_ra_used_mgdl_per_min ?: AimiJson.NULL)
            base.put("ra_estimator_advances", baseline_state.ra_estimator_advances ?: AimiJson.NULL)
            base.put("ra_estimator_replayed_calls", baseline_state.ra_estimator_replayed_calls ?: AimiJson.NULL)
            base.put("ra_aligned_tau_shadow_mgdl_per_min", baseline_state.ra_aligned_tau_shadow_mgdl_per_min ?: AimiJson.NULL)
            base.put("smb_seal_refused_count", baseline_state.smb_seal_refused_count ?: AimiJson.NULL)
            base.put("smb_seal_refused_total_u", baseline_state.smb_seal_refused_total_u ?: AimiJson.NULL)
            base.put("smb_seal_allowed_raise_count", baseline_state.smb_seal_allowed_raise_count ?: AimiJson.NULL)
            base.put("cbf_coefficient_used", baseline_state.cbf_coefficient_used ?: AimiJson.NULL)
            base.put("cbf_coefficient_unfloored", baseline_state.cbf_coefficient_unfloored ?: AimiJson.NULL)
            base.put("cbf_permitted_u", baseline_state.cbf_permitted_u ?: AimiJson.NULL)
            base.put("cbf_permitted_unfloored_u", baseline_state.cbf_permitted_unfloored_u ?: AimiJson.NULL)
            base.put("cbf_profile_isf_mgdl", baseline_state.cbf_profile_isf_mgdl ?: AimiJson.NULL)
            base.put("autodrive_gate_engaged", baseline_state.autodrive_gate_engaged ?: AimiJson.NULL)
            base.put("autodrive_gate_kind", baseline_state.autodrive_gate_kind ?: AimiJson.NULL)
            base.put("autodrive_gate_reason", baseline_state.autodrive_gate_reason ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_would_block", baseline_state.rise_ceiling_guard_would_block ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_reason", baseline_state.rise_ceiling_guard_reason ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_repeats", baseline_state.rise_ceiling_guard_repeats ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_withheld_u", baseline_state.rise_ceiling_guard_withheld_u ?: AimiJson.NULL)
            base.put("effort_smb_factor_requested", baseline_state.effort_smb_factor_requested ?: AimiJson.NULL)
            base.put("effort_smb_factor_applied", baseline_state.effort_smb_factor_applied ?: AimiJson.NULL)
            base.put("effort_smb_before_u", baseline_state.effort_smb_before_u ?: AimiJson.NULL)
            base.put("effort_smb_after_u", baseline_state.effort_smb_after_u ?: AimiJson.NULL)
            base.put("effort_smb_floored_by_meal", baseline_state.effort_smb_floored_by_meal ?: AimiJson.NULL)
            base.put("rise_floor_spent_u", baseline_state.rise_floor_spent_u ?: AimiJson.NULL)
            base.put("variable_sens_mgdl", baseline_state.variable_sens_mgdl ?: AimiJson.NULL)
            base.put(
                "rise_floor_minutes_since_contribution",
                baseline_state.rise_floor_minutes_since_contribution ?: AimiJson.NULL,
            )
            json.put("baseline_state", base)

            val adj = JsonObj()
            adjustments.dynamic_isf?.let { d ->
                val dJson = JsonObj()
                dJson.put("final_value_mgdl", d.final_value_mgdl)
                val modsIdx = JsonArr()
                d.modifiers.forEach { m ->
                    val mJson = JsonObj()
                    mJson.put("source", m.source)
                    mJson.put("factor", m.factor)
                    mJson.put("reason", m.clinical_reason)
                    modsIdx.put(mJson)
                }
                dJson.put("modifiers", modsIdx)
                adj.put("dynamic_isf", dJson)
            }
            adjustments.physiological_context?.let { p ->
                val pJson = JsonObj()
                pJson.put("cycle_phase", p.hormonal_cycle_phase)
                pJson.put("activity_mode", p.physical_activity_mode)
                adj.put("physio_context", pJson)
            }
            // Add Basal Cap if present
            adjustments.basal_safety_cap?.let { c ->
                val cJson = JsonObj()
                cJson.put("status", c.status)
                cJson.put("limit_uph", c.limit_uph)
                cJson.put("reason", c.safety_reason)
                adj.put("basal_cap", cJson)
            }
            adjustments.iob_surveillance?.let { s ->
                val sJson = JsonObj()
                sJson.put("pref_enabled", s.pref_enabled)
                sJson.put("preference_key", s.preference_key)
                sJson.put("kind", s.kind)
                sJson.put("active_reason", s.active_reason ?: AimiJson.NULL)
                sJson.put("meal_priority_context", s.meal_priority_context)
                sJson.put("bg_mgdl", s.bg_mgdl)
                sJson.put("target_bg_mgdl", s.target_bg_mgdl)
                sJson.put("delta_mgdl_5m", s.delta_mgdl_5m)
                sJson.put("short_avg_delta_mgdl_5m", s.short_avg_delta_mgdl_5m)
                sJson.put("iob_u", s.iob_u)
                sJson.put("max_iob_u", s.max_iob_u)
                sJson.put("iob_floor_u", s.iob_floor_u)
                sJson.put("eventual_bg", s.eventual_bg ?: AimiJson.NULL)
                sJson.put("min_predicted_bg", s.min_predicted_bg ?: AimiJson.NULL)
                sJson.put("trajectory_energy", s.trajectory_energy ?: AimiJson.NULL)
                sJson.put("signal_eventual_drop", s.signal_eventual_drop)
                sJson.put("signal_min_pred_drop", s.signal_min_pred_drop)
                sJson.put("signal_trajectory_stack", s.signal_trajectory_stack)
                sJson.put("smb_multiplier", s.smb_multiplier)
                sJson.put("smb_cap_u", s.smb_cap_u)
                sJson.put("suppress_red_carpet_restore", s.suppress_red_carpet_restore)
                sJson.put("tbr_boost_floor", s.tbr_boost_floor)
                sJson.put("smb_u_after_pkpd_before_stacking", s.smb_u_after_pkpd_before_stacking)
                sJson.put("smb_u_after_stacking_step", s.smb_u_after_stacking_step)
                sJson.put("stacking_reduced_smb", s.stacking_reduced_smb)
                sJson.put("pkpd_tbr_boost_after_finalize", s.pkpd_tbr_boost_after_finalize)
                sJson.put("smb_u_after_cap_smb_dose", s.smb_u_after_cap_smb_dose)
                sJson.put("smb_u_final_for_delivery", s.smb_u_final_for_delivery)
                sJson.put("smb_final_source", s.smb_final_source)
                sJson.put("summary_line", s.summary_line)
                sJson.put("tuning_reference", s.tuning_reference)
                adj.put("iob_surveillance", sJson)
            }
            adjustments.iob_release?.let { r ->
                val rJson = JsonObj()
                rJson.put("enabled", r.enabled)
                rJson.put("theta", r.theta)
                rJson.put("iob_ledger_u", r.iob_ledger_u)
                rJson.put("iob_effective_u", r.iob_effective_u ?: AimiJson.NULL)
                rJson.put("iob_for_gate_u", r.iob_for_gate_u)
                rJson.put("released_u", r.released_u)
                rJson.put("gate_flips_block_to_allow", r.gate_flips_block_to_allow)
                rJson.put("reason", r.reason)
                adj.put("iob_release", rJson)
            }
            adjustments.safety_risk?.let { r ->
                val rJson = JsonObj()
                rJson.put("phase", r.phase)
                rJson.put("predictive_hypo_suppressed", r.predictive_hypo_suppressed)
                rJson.put("safety_gate", r.safety_gate)
                rJson.put("halt_remaining_pipeline", r.halt_remaining_pipeline)
                rJson.put("meal_context_active", r.meal_context_active)
                rJson.put("meal_rise_confirmed", r.meal_rise_confirmed)
                rJson.put("composite_min_mgdl", r.composite_min_mgdl)
                rJson.put("pred_bg_mgdl", r.pred_bg_mgdl)
                rJson.put("eventual_bg_mgdl", r.eventual_bg_mgdl)
                rJson.put("uam_terminal_mgdl", r.uam_terminal_mgdl ?: AimiJson.NULL)
                rJson.put("hypo_threshold_mgdl", r.hypo_threshold_mgdl)
                rJson.put("decision_composite_min_mgdl", r.decision_composite_min_mgdl ?: AimiJson.NULL)
                rJson.put("decision_hypo_threshold_mgdl", r.decision_hypo_threshold_mgdl ?: AimiJson.NULL)
                rJson.put("reconcile_delta_mgdl", r.reconcile_delta_mgdl ?: AimiJson.NULL)
                adj.put("safety_risk", rJson)
            }
            adjustments.scenario_projection?.let { s ->
                val sJson = JsonObj()
                sJson.put("floor_terminal_mgdl", s.floor_terminal_mgdl)
                sJson.put("floor_path_min_mgdl", s.floor_path_min_mgdl)
                sJson.put("best_terminal_mgdl", s.best_terminal_mgdl)
                sJson.put("best_path_min_mgdl", s.best_path_min_mgdl)
                sJson.put("best_gate_path_min_mgdl", s.best_gate_path_min_mgdl)
                sJson.put("best_gate_path_min_hit_floor", s.best_gate_path_min_hit_floor)
                sJson.put("terminal_gap_mgdl", s.terminal_gap_mgdl)
                sJson.put("trajectory_type", s.trajectory_type ?: AimiJson.NULL)
                sJson.put("contributors", JsonArr(s.contributors))
                adj.put("scenario_projection", sJson)
            }
            adjustments.hyper_trajectory_release?.let { h ->
                val hJson = JsonObj()
                hJson.put("active", h.active)
                hJson.put("tier", h.tier)
                hJson.put("dev_above_target_mgdl", h.dev_above_target_mgdl)
                hJson.put("projected_dev_mgdl", h.projected_dev_mgdl)
                hJson.put("severity_weight", h.severity_weight)
                hJson.put("absorption_offset_mgdl", h.absorption_offset_mgdl)
                hJson.put("smb_floor_u", h.smb_floor_u)
                hJson.put("v3_smb_before_u", h.v3_smb_before_u)
                hJson.put("v3_smb_after_u", h.v3_smb_after_u)
                hJson.put("suppress_traj_basal_shift", h.suppress_traj_basal_shift)
                hJson.put("hypo_min_pred_ignored", h.hypo_min_pred_ignored)
                hJson.put("reason", h.reason)
                adj.put("hyper_trajectory_release", hJson)
            }
            adjustments.physiological_phase?.let { p ->
                val pJson = JsonObj()
                pJson.put("phase", p.phase)
                pJson.put("confidence", p.confidence)
                pJson.put("behavioral_risk", p.behavioral_risk)
                pJson.put("reason", p.reason)
                pJson.put("extended_dawn_guard", p.extended_dawn_guard)
                pJson.put("scenario_best_capped", p.scenario_best_capped)
                pJson.put("max_htr_tier", p.max_htr_tier)
                pJson.put("smb_floor_cap_u", p.smb_floor_cap_u ?: AimiJson.NULL)
                pJson.put("physio_smb_factor_fused", p.physio_smb_factor_fused ?: AimiJson.NULL)
                pJson.put("physio_phase_source", p.physio_phase_source ?: AimiJson.NULL)
                adj.put("physiological_phase", pJson)
            }
            adjustments.physiological_patterns?.let { pp ->
                adj.put("physiological_patterns", pp)
            }
            adjustments.meal_absorption_phase?.let { m ->
                val mJson = JsonObj()
                mJson.put("phase", m.phase)
                mJson.put("belief", m.belief)
                mJson.put("reason", m.reason)
                mJson.put("memory_active", m.memory_active)
                mJson.put("wave_count", m.wave_count)
                mJson.put("meal_delivery_priority", m.meal_delivery_priority)
                mJson.put("chrono_prior", m.chrono_prior)
                mJson.put("kinetic_score", m.kinetic_score)
                mJson.put("trajectory_score", m.trajectory_score)
                mJson.put("physio_score", m.physio_score)
                adj.put("meal_absorption_phase", mJson)
            }
            adjustments.pred_divergence?.let { pd ->
                adj.put("pred_divergence", pd)
            }
            adjustments.recursive_belief?.let { rb ->
                adj.put("recursive_belief", rb)
            }
            adjustments.recursive_authority_gate?.let { rag ->
                adj.put("recursive_authority_gate", rag)
            }
            adjustments.replay_quality?.let { rq ->
                adj.put("replay_quality", rq)
            }
            adjustments.smb_binding_trace?.let { trace ->
                adj.put("smb_binding_trace", trace)
            }
            adjustments.physio_latent_state?.let { latent ->
                adj.put("physio_latent_state", latent)
            }
            adjustments.uam_hypotheses?.let { hypotheses ->
                adj.put("uam_hypotheses", hypotheses)
            }
            adjustments.patient_state?.let { patientState ->
                adj.put("patient_state", patientState)
            }
            adjustments.patient_mode?.let { patientMode ->
                adj.put("patient_mode", patientMode)
            }
            adjustments.endocrine_belief?.let { endocrine ->
                adj.put("endocrine_belief", endocrine)
            }
            adjustments.physiological_tree?.let { tree ->
                adj.put("physiological_tree", tree)
            }
            adjustments.meal_certainty?.let { mealCertainty ->
                adj.put("meal_certainty", mealCertainty)
            }
            adjustments.dose_terminal_snapshot?.let { doseTerminal ->
                adj.put("dose_terminal_snapshot", doseTerminal)
            }
            adjustments.control_barrier?.let { cb ->
                adj.put("control_barrier", cb)
            }
            adjustments.tube_advisor?.let { tube ->
                adj.put("tube_advisor", tube)
            }
            adjustments.pkpd_soft_floor?.let { softFloor ->
                adj.put("pkpd_soft_floor", softFloor)
            }
            adjustments.basal_terminal?.let { terminal ->
                adj.put("basal_terminal", terminal)
            }
            adjustments.adaptive_basal?.let { adaptiveBasal ->
                adj.put("adaptive_basal", adaptiveBasal)
            }
            adjustments.harmonia_simulation?.let { simulation ->
                adj.put("harmonia_simulation", simulation)
            }
            adjustments.harmonia_production?.let { production ->
                adj.put("harmonia_production", production)
            }
            adjustments.harmonia_smb_authority?.let { smbAuthority ->
                adj.put("harmonia_smb_authority", smbAuthority)
            }
            adjustments.intelligence_snapshot?.let { snapshot ->
                adj.put("intelligence_snapshot_v1", snapshot)
            }
            adjustments.t3c_runtime_ownership?.let { ownership ->
                val ownershipJson = JsonObj()
                ownershipJson.put("mode", ownership.mode)
                ownershipJson.put("native_owner_active", ownership.native_owner_active)
                ownershipJson.put("legacy_fallback_allowed", ownership.legacy_fallback_allowed)
                ownershipJson.put("reason", ownership.reason)
                adj.put("t3c_runtime_ownership", ownershipJson)
            }
            adjustments.auditor_tick?.let { auditorTick ->
                adj.put("auditor_tick", auditorTick)
            }
            adjustments.auditor_profile_factors?.let { profileFactors ->
                adj.put("auditor_profile_factors", profileFactors)
            }
            adjustments.post_hypo_delivery?.let { postHypoDelivery ->
                adj.put("post_hypo_delivery", postHypoDelivery)
            }
            adjustments.insulin_origin?.let { insulinOrigin ->
                adj.put("insulin_origin", insulinOrigin)
            }
            json.put("adjustments", adj)

            outcome?.let { o ->
                val oJson = JsonObj()
                oJson.put("decision", o.clinical_decision)
                oJson.put("amount", o.dosage_u)
                o.target_basal_uph?.let { oJson.put("target_basal_rate_uph", it) }
                oJson.put("narrative", o.narrative_explanation)
                json.put("outcome", oJson)
            }
            json.toString()
        } catch (_: Exception) { "{ \"error\": \"JSON Generation Failed\" }" }
    }
}

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

/**
 * Decision transparency context, initial loop [RT], and **shadowed** flat-BG flag after delta override.
 * @see DetermineBasalaimiSMB2.buildDecisionContextInitRtSosAndFlatShadow
 */
private data class AimiTickDecisionRtBootstrap(
    val decisionCtx: AimiDecisionContext,
    val rT: RT,
    val flatBGsDetected: Boolean,
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
private data class AimiT9PhysioPkpdTubeBootstrap(
    val pumpAgeDays: Float,
    val physioMultipliers: PhysioMultipliersMTR,
)

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
private data class AimiTickClockTirCarbGlucoseBootstrap(
    val honeymoon: Boolean,
    val ngrConfig: NGRConfig,
    val tir1DAYIR: Double,
    val lastHourTIRAbove: Double?,
    val tirbasal3IR: Double?,
    val tirbasal3B: Double?,
    val tirbasal3A: Double?,
    val tirbasalhAP: Double?,
    /** Minute/second from the same [aimiCivilClock] snapshot as `hourOfDay` (circadian math later). */
    val circadianMinute: Int,
    val circadianSecond: Int,
    val bgAcceleration: Float,
)

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
    @Inject lateinit var storageHelper: AimiStorageHelper  // 🛡️ Restored StorageHelper

    // The shared storage seam, next to the Android helper above. Transitional: the four
    // CircadianMealProfileStore entry points took `AimiStorage` when they were restored, while the
    // File-typed members below still need the helper.
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
            return if (::unifiedReactivityLearner.isInitialized) unifiedReactivityLearner.getCombinedFactor(hourOfDay) else 1.0
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

    private var adaptiveMult: Double = 1.0
    @Volatile private var cachedPumpAgeDays: Float = 0f
    private val pumpAgeRefreshInFlight = AtomicBoolean(false)
    @Volatile private var cachedLastSmb: BS? = null
    private val lastSmbRefreshInFlight = AtomicBoolean(false)
    private val tirWarmupSnapshotRef = AtomicReference<TirWarmupSnapshot?>(null)
    private val tirWarmupRefreshInFlight = AtomicBoolean(false)
    private val carbContextSnapshotRef = AtomicReference<CarbContextSnapshot?>(null)
    private val carbContextRefreshInFlight = AtomicBoolean(false)
    private val tdd2DaysRef = AtomicReference<Float?>(null)
    private val tdd2DaysRefreshInFlight = AtomicBoolean(false)
    private val tdd30DaysRef = AtomicReference<Double?>(null)
    private val tdd30DaysRefreshInFlight = AtomicBoolean(false)
    private val sensorInsertionMsRef = AtomicReference<Long?>(null)
    private val sensorInsertionRefreshInFlight = AtomicBoolean(false)
    @Volatile private var lastBasalLearnerHypoNotifyMs: Long = 0L
    @Volatile private var lastBasalLearnerHyperNotifyMs: Long = 0L
    private val stepsSnapshotRef = AtomicReference<List<SC>>(emptyList())
    private val stepsRefreshInFlight = AtomicBoolean(false)
    private val heartRatesSnapshotRef = AtomicReference<List<HR>>(emptyList())
    private val heartRatesRefreshInFlight = AtomicBoolean(false)
    private val tempBasalsSnapshotRef = AtomicReference<List<TB>>(emptyList())
    private val tempBasalsRefreshInFlight = AtomicBoolean(false)
    private val bolusSnapshotRef = AtomicReference<List<BS>>(emptyList())
    private val bolusRefreshInFlight = AtomicBoolean(false)
    @Volatile private var cachedEffectiveProfile: EffectiveProfile? = null
    private val effectiveProfileRefreshInFlight = AtomicBoolean(false)
    private val trajectoryHistoryRef = AtomicReference<List<app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState>>(emptyList())
    private val trajectoryHistoryRefreshInFlight = AtomicBoolean(false)
    private val determineIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    val raws: List<TB> = tempBasalsSnapshotRef.get()

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
        return cachedPumpAgeDays
    }

    private fun refreshPumpAgeAsync() {
        if (!pumpAgeRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val fromTime = aimiWallClockMs() - TimeUnit.DAYS.toMillis(7)
                val siteChanges = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.CANNULA_CHANGE, true)
                cachedPumpAgeDays = if (siteChanges.isNotEmpty()) {
                    val latestChangeTimestamp = siteChanges.last().timestamp
                    ((aimiWallClockMs() - latestChangeTimestamp).toFloat() / (1000f * 60f * 60f * 24f))
                } else {
                    0f
                }
            } catch (_: Exception) {
                cachedPumpAgeDays = 0f
            } finally {
                pumpAgeRefreshInFlight.set(false)
            }
        }
    }

    private fun latestSmbCached(): BS? {
        refreshLatestSmbAsync()
        return cachedLastSmb
    }

    private fun refreshLatestSmbAsync() {
        if (!lastSmbRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                cachedLastSmb = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
            } catch (_: Exception) {
                cachedLastSmb = null
            } finally {
                lastSmbRefreshInFlight.set(false)
            }
        }
    }

    private fun latestTirWarmupSnapshot(): TirWarmupSnapshot {
        refreshTirWarmupAsync()
        return tirWarmupSnapshotRef.get() ?: TirWarmupSnapshot()
    }

    private fun refreshTirWarmupAsync() {
        if (!tirWarmupRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val tir1Day = tirCalculator.calculate(1, 65.0, 180.0)
                determineBasalInvocationCaches.storeTir65180FromWarmup(tir1Day)
                tirWarmupSnapshotRef.set(
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
                )
            } catch (_: Exception) {
                tirWarmupSnapshotRef.set(TirWarmupSnapshot())
            } finally {
                tirWarmupRefreshInFlight.set(false)
            }
        }
    }

    private fun latestCarbContextSnapshot(nowMs: Long, mealDataLastCarbTime: Long, cobNow: Float): CarbContextSnapshot {
        refreshCarbContextAsync(nowMs, mealDataLastCarbTime, cobNow)
        return carbContextSnapshotRef.get() ?: CarbContextSnapshot(
            lastCarbTimestamp = mealDataLastCarbTime.takeIf { it > 0L } ?: nowMs - TimeUnit.DAYS.toMillis(1),
            lastCarbAgeMin = 0,
            futureCarbs = 0.0f,
            effectiveCob = cobNow,
            recentNotes = emptyList(),
        )
    }

    private fun refreshCarbContextAsync(nowMs: Long, mealDataLastCarbTime: Long, cobNow: Float) {
        if (!carbContextRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                var lastCarbTimestamp = mealDataLastCarbTime
                val oneDayAgoIfNotFound = nowMs - TimeUnit.DAYS.toMillis(1)
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
                val recentNotesLocal = persistenceLayer.getUserEntryDataFromTime(nowMs - TimeUnit.HOURS.toMillis(4))
                carbContextSnapshotRef.set(
                    CarbContextSnapshot(
                        lastCarbTimestamp = lastCarbTimestamp,
                        lastCarbAgeMin = ageMin,
                        futureCarbs = future,
                        effectiveCob = effectiveCob,
                        recentNotes = recentNotesLocal,
                    )
                )
            } catch (_: Exception) {
                carbContextSnapshotRef.set(
                    CarbContextSnapshot(
                        lastCarbTimestamp = mealDataLastCarbTime.takeIf { it > 0L } ?: nowMs - TimeUnit.DAYS.toMillis(1),
                        lastCarbAgeMin = 0,
                        futureCarbs = 0.0f,
                        effectiveCob = cobNow,
                        recentNotes = emptyList(),
                    )
                )
            } finally {
                carbContextRefreshInFlight.set(false)
            }
        }
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
        val cached = tdd2DaysRef.get()
        if (cached == null || cached == 0.0f || cached < tdd7P.toFloat()) return tdd7P.toFloat()
        return cached
    }

    private fun refreshTdd2DaysAsync() {
        if (!tdd2DaysRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val tdd2 = tddCalculator.averageTDD(tddCalculator.calculate(2, allowMissingDays = false))
                    ?.data?.totalAmount?.toFloat() ?: 0.0f
                tdd2DaysRef.set(tdd2)
            } catch (_: Exception) {
                tdd2DaysRef.set(null)
            } finally {
                tdd2DaysRefreshInFlight.set(false)
            }
        }
    }

    private fun resolveTdd30DaysForLearner(fallback7Day: Double): Double {
        refreshTdd30DaysAsync()
        return tdd30DaysRef.get()?.takeIf { it > 0.0 } ?: 0.0
    }

    private fun refreshTdd30DaysAsync() {
        if (!tdd30DaysRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val avg = tddCalculator.averageTDD(tddCalculator.calculate(30, allowMissingDays = true))
                    ?.data?.totalAmount
                tdd30DaysRef.set(avg?.takeIf { it > 0.0 })
            } catch (_: Exception) {
                tdd30DaysRef.set(null)
            } finally {
                tdd30DaysRefreshInFlight.set(false)
            }
        }
    }

    private fun resolveSensorInsertionMsCached(nowMs: Long): Long? {
        refreshSensorInsertionAsync(nowMs)
        return sensorInsertionMsRef.get()
    }

    private fun refreshSensorInsertionAsync(nowMs: Long) {
        if (!sensorInsertionRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val fromTime = nowMs - 45L * 24L * 60L * 60L * 1000L
                val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.SENSOR_CHANGE, true)
                val latest = events.maxByOrNull { it.timestamp }?.timestamp
                sensorInsertionMsRef.set(latest?.takeIf { it > 0L })
            } catch (_: Exception) {
                sensorInsertionMsRef.set(null)
            } finally {
                sensorInsertionRefreshInFlight.set(false)
            }
        }
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
        return stepsSnapshotRef.get()
    }

    private fun refreshStepsAsync(now: Long) {
        if (!stepsRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val start = now - 210 * 60 * 1000
                stepsSnapshotRef.set(persistenceLayer.getStepsCountFromTimeToTime(start, now))
            } catch (_: Exception) {
                stepsSnapshotRef.set(emptyList())
            } finally {
                stepsRefreshInFlight.set(false)
            }
        }
    }

    private fun heartRatesCached(now: Long): List<HR> {
        refreshHeartRatesAsync(now)
        return heartRatesSnapshotRef.get()
    }

    private fun refreshHeartRatesAsync(now: Long) {
        if (!heartRatesRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val start = now - 200 * 60 * 1000
                heartRatesSnapshotRef.set(persistenceLayer.getHeartRatesFromTimeToTime(start, now))
            } catch (_: Exception) {
                heartRatesSnapshotRef.set(emptyList())
            } finally {
                heartRatesRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshTempBasalsAsync(fromMillis: Long) {
        if (!tempBasalsRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                tempBasalsSnapshotRef.set(
                    persistenceLayer.getTemporaryBasalsStartingFromTime(fromMillis, ascending = false)
                )
            } catch (_: Exception) {
                tempBasalsSnapshotRef.set(emptyList())
            } finally {
                tempBasalsRefreshInFlight.set(false)
            }
        }
    }

    private fun bolusesFromTimeCached(startTime: Long, ascending: Boolean): List<BS> {
        refreshBolusesAsync(startTime, ascending)
        val cached = bolusSnapshotRef.get()
        return if (ascending) {
            cached.filter { it.timestamp >= startTime }.sortedBy { it.timestamp }
        } else {
            cached.filter { it.timestamp >= startTime }.sortedByDescending { it.timestamp }
        }
    }

    private fun refreshBolusesAsync(startTime: Long, ascending: Boolean) {
        if (!bolusRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                bolusSnapshotRef.set(persistenceLayer.getBolusesFromTime(startTime, ascending))
            } catch (_: Exception) {
                bolusSnapshotRef.set(emptyList())
            } finally {
                bolusRefreshInFlight.set(false)
            }
        }
    }

    private fun effectiveProfileCached(time: Long): EffectiveProfile? {
        refreshEffectiveProfileAsync(time)
        return cachedEffectiveProfile
    }

    private fun refreshEffectiveProfileAsync(time: Long) {
        if (!effectiveProfileRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                cachedEffectiveProfile = profileFunction.getProfile(time)
            } catch (_: Exception) {
                cachedEffectiveProfile = null
            } finally {
                effectiveProfileRefreshInFlight.set(false)
            }
        }
    }

    private fun trajectoryHistoryCached(
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
    ): List<app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState> {
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
        return trajectoryHistoryRef.get()
    }

    private fun refreshTrajectoryHistoryAsync(
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
    ) {
        if (!trajectoryHistoryRefreshInFlight.compareAndSet(false, true)) return
        determineIoScope.launch {
            try {
                val effectiveProfile = cachedEffectiveProfile ?: profileFunction.getProfile(currentTime)
                cachedEffectiveProfile = effectiveProfile
                trajectoryHistoryRef.set(
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
                )
            } catch (_: Exception) {
                trajectoryHistoryRef.set(emptyList())
            } finally {
                trajectoryHistoryRefreshInFlight.set(false)
            }
        }
    }
    private var lateFatRiseFlag: Boolean = false

    /**
     * Last value the `isLateFatProteinRise` predicate produced this tick, kept only so the export
     * can compare it with the shadow damping window. Never read by the dosing chain.
     */
    private var lateFatRiseFlagForExport: Boolean = false
    // — Hystérèse anti-pompage —
    private val HYPO_RELEASE_MARGIN   = 5.0      // mg/dL au-dessus du seuil
    private val HYPO_RELEASE_HOLD_MIN = 5        // minutes à rester > seuil+margin
    private var highBgOverrideUsed = false
    private val INSULIN_STEP = Constants.DEFAULT_INSULIN_STEP_U.toFloat()

    /** Suspend stats caches for one [determine_basal] pass; see [DetermineBasalInvocationCaches]. */
    private val determineBasalInvocationCaches = DetermineBasalInvocationCaches()
    private val bolusQueryCache = mutableMapOf<Pair<Long, Boolean>, List<BS>>()

    /**
     * Phase 2: early orchestration — cache lifecycle, telemetry pulse, meal hydration,
     * advisor/TDD bootstrap, profile snapshot, BOOTSTRAP phase marker.
     */
    private fun runEarlyDetermineBasalStages(ctx: AimiTickContext): AimiDetermineBasalEarlyTickState {
        determineBasalInvocationCaches.beginInvocation()
        bolusQueryCache.clear()
        consoleError = mutableListOf()
        consoleLog = mutableListOf()
        if (::aapsLogger.isInitialized) {
            try {
                hormonitorStudyExporter?.recordLoopPulse(ctx.currentTime, AimiLoopTelemetry.activeTickId)
            } catch (_: Throwable) {
                // Never break determine_basal on telemetry.
            }
        }
        exerciseInsulinLockoutActive = false
        exerciseHyperBasalOverrideActive = false
        aimiContextActivityActive = false
        pkpdAbsorptionGuardAppliedThisTick = false
        criticalSafetyZeroedThisTick = false
        cachedRiskEnvelopeEarly = null
        cachedRiskEnvelopeDecision = null
        lastSafetyRiskExport = null
        lastScenarioProjection = null
        lastPredDivergenceExport = null
        lastDecisionPredictionAuthority = null
        lastIntelligenceSnapshot = null
        lastPredictionAuthorityApplyResult = null
        lastDoseTerminalSnapshot = null
        lastPkpdSoftFloorTelemetry = null
        tubeDoseBaseline = null
        tubeAppliedFromDoseSnapshotThisTick = false
        isConfirmedHighRiseThisTick = false
        correctionAggressionDecision = null
        mealAdvisorOneShotThisTick = false
        lastTubeAdvisorSmbCapScale = null
        lastTubeAdvisorTrace = null
        lastInflammationResult = null
        tickInsulinActionState = null
        tickEffectiveDiaHours = ctx.effectiveDiaHours
        tickEffectivePeakMinutes = ctx.effectivePeakMinutes
        lastLoopCgmNoise = ctx.glucoseStatus.noise

        if (ctx.extraDebug.isNotEmpty()) {
            consoleLog.add(ctx.extraDebug)
            consoleError.add(ctx.extraDebug)
        }

        hydrateMealDataIfTriggered(ctx.mealData)

        val isExplicitAdvisorRun = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
        val tdd7P = preferences.get(DoubleKey.OApsAIMITDD7)
        var tdd7Days = ctx.profile.TDD
        // `!isFinite()` first: the `tdd7Days.toFloat() != 0.0f` guards further down are TRUE for NaN,
        // so a NaN would enter those branches and make `basalaimi` (tdd7Days / weight) and
        // `ci` (450 / tdd7Days) NaN for the whole tick. No change for any finite value.
        if (!tdd7Days.isFinite() || tdd7Days == 0.0 || tdd7Days < tdd7P) tdd7Days = tdd7P

        val originalProfile = ctx.profile.copy()
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.BOOTSTRAP, hormonitorStudyExporter)

        return AimiDetermineBasalEarlyTickState(
            originalProfile = originalProfile,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            tdd7P = tdd7P,
            tdd7Days = tdd7Days
        )
    }

    /**
     * Phase 2 (P2): gestational autopilot, early IOB / dura ISF / acceleration, harmonized basal multipliers,
     * confirmed high-rise flag, thyroid module — same order and side effects as inline sequence.
     */
    private fun bootstrapPhysiologyAfterEarlyTick(ctx: AimiTickContext, tdd7Days: Double): Boolean {
        applyGestationalAutopilot(ctx.profile)
        this.duraISFminutes = ctx.glucoseStatus.duraISFminutes
        this.duraISFaverage = ctx.glucoseStatus.duraISFaverage
        val iobObj = ctx.iobDataArray.firstOrNull() ?: IobTotal(ctx.currentTime)
        this.iobNet = iobObj.iob
        this.iob = iobObj.iob.toFloat() // 🛡️ Early Initialization for Safety Guards
        val accel = ctx.glucoseStatus.bgAcceleration ?: 0.0
        this.bgacc = accel
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
        adaptiveMult = BasalAdaptiveMultiplier.combine(hMult, nMult)
        if (Math.abs(adaptiveMult - 1.0) > 0.01) {
            consoleLog.add("🛡️ BASAL_UNIFIED_SCALING: H=${aimiFmt2(hMult)}x / N=${aimiFmt2(nMult)}x -> Applied=${aimiFmt2(adaptiveMult)}x")
        }
        lastAdaptiveBasalTrace = buildAdaptiveBasalTrace(hMultRaw, hMult, nDecision, adaptiveMult)
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
        put("n_source", (nDecision?.source ?: BasalNeuralLearner.BasalMultiplierSource.DISABLED).name.lowercase(Locale.US))
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
    private fun buildDecisionContextInitRtSosAndFlatShadow(ctx: AimiTickContext): AimiTickDecisionRtBootstrap {
        lastIobSurveillanceExport = null
        lastIobReleaseExport = null
        tickIobEffectiveU = null
        lastPostHypoOrdinal = 0
        // Reset SMB trace each tick so a basal-only T3C row can't carry stale SMB values from a prior SMB tick
        // (replay_quality export). The standard SMB pipeline overwrites these before use.
        lastSmbProposed = 0.0
        lastSmbCapped = 0.0
        lastSmbFinal = 0.0
        lastSmbBindingTraceDraft = SmbBindingTrace.Draft(timestampMs = ctx.currentTime)
        // The maxSMB ladder runs later in the tick, and a tick can abort before it. Without this
        // reset the export would stamp the previous tick's branch tag and slope onto an otherwise
        // empty trace, and a reader could not tell. Null means "the ladder did not run this tick".
        lastMaxSmbLadderBranch = null
        lastSlopeFromMinDeviation = null
        lastShortAvgDeltaAtLadder = null
        // Effort reduction telemetry is per tick — a basal-only tick must export null, not the last
        // SMB tick's multiplier.
        lastEffortSmbFactorRaw = null
        // What the stress floor did belongs to this tick only. A tick that returns before the working
        // sensitivity is finalised must export nothing rather than the previous tick's numbers.
        WorkingIsf.resetLastApplied()
        lastEffortSmbFactorApplied = null
        lastEffortSmbBeforeU = null
        lastEffortSmbAfterU = null
        lastNgrBasalMultiplier = 1.0
        lastHyperTrajectoryRelease = null
        lastRecursiveBeliefSnapshot = null
        lastRecursiveAuthorityGateDecision = null
        lastRbtChaosEvaluation = null
        lastRbtAppliedHints = null
        lastT3cHistoricalBypassNeutralizedThisTick = false
        lastT3cRuntimeOwnership = null
        rbtResolvedThisTick = false
        lastRbtLiveCommitResult = null
        lastLoadGovernorMultiplierG = 1.0
        lastPhysiologicalPhaseOutput = null
        lastPhysiologicalPatternSnapshot = null
        AimiCascadeArbitrationArtifacts.clear()
        lastMealAbsorptionOutput = null
        lastPhysioLatentState = null
        lastEffortAssessment = null // per-tick computed; memory (lastEffortMemory) persists across ticks
        lastUamHypothesisState = null
        lastContextSnapshot = null
        lastPatientState = null
        lastPatientModeDecision = null
        lastPhysiologicalTreeSnapshot = null
        lastHarmoniaDecision = null
        lastMealCertainty = null
        lastHarmonizerOutcome = null
        lastHarmoniaProductionDecision = null
        lastAuditorTickDisposition = null
        lastAuditorLoopSnapshot = null
        currentTickDecisionEventId = null
        lastAuditorAuditStartedAtMs = 0L
        lastPostHypoDeliveryAuthority = PostHypoDeliveryAuthority.INACTIVE
        lastPostHypoSmbBeforeCapU = null
        lastPostHypoSmbAfterCapU = null
        // Cross-tick hysteresis (InsulinSlope / Endogenous) must NOT reset here — that zeroed
        // holdTicks every loop and made 15–20 min holds dead on arrival. reset() belongs in
        // tests / plugin restart only.
        pendingTrajSpiralBasal = null
        // 🔭 Lot 0 — l'export JSONL doit avoir lieu sur TOUS les chemins de sortie du tick, pas seulement
        // sur les deux qui appellent explicitement le stage. On repart d'un état non exporté à chaque tick.
        aimiDecisionExportedThisTick = false
        pendingDecisionCtxForExport = null
        val decisionCtx = AimiDecisionContext(
            event_id = "evt_${ctx.currentTime}".also { currentTickDecisionEventId = it },
            timestamp = ctx.currentTime,
            trigger = run {
                val iobNow = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0
                val bgNow = ctx.glucoseStatus.glucose
                val hour = aimiLocalHour()
                val isNight = hour >= 22 || hour <= 7
                val isBgRiseFast = ctx.glucoseStatus.delta > 5
                val nightBangBangBlock = isNight && isBgRiseFast && iobNow > 2.0 && bgNow < 100.0 &&
                    (ctx.currentTime - lastBgRiseFastNightMs) < 15 * 60_000L
                if (isBgRiseFast && isNight && iobNow > 2.0 && bgNow < 100.0) {
                    if (lastBgRiseFastNightMs == 0L || (ctx.currentTime - lastBgRiseFastNightMs) >= 15 * 60_000L) {
                        lastBgRiseFastNightMs = ctx.currentTime
                    }
                }
                when {
                    nightBangBangBlock -> {
                        consoleLog.add("🚫 T6 NIGHT_RATE_LIMIT: BG_Rise_Fast bloqué (IOB=${aimiFmt2(iobNow)}U > 2.0 ET BG=${bgNow.toInt()} < 100 la nuit)")
                        "Routine_Cycle"
                    }
                    isBgRiseFast -> "BG_Rise_Fast"
                    else -> "Routine_Cycle"
                }
            },
            baseline_state = AimiDecisionContext.BaselineState(
                profile_isf_mgdl = ctx.profile.sens,
                profile_basal_uph = ctx.profile.current_basal,
                current_bg_mgdl = ctx.glucoseStatus.glucose,
                cob_g = ctx.mealData.mealCOB,
                iob_u = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
                profile_isf_static_mgdl = IsfSourceTelemetry.lastProfileStaticMgdl,
                command_isf_mgdl = ctx.profile.sens,
                isf_source = IsfSourceTelemetry.lastSource,
                isf_age_ms = IsfSourceTelemetry.lastAgeMs,
                isf_cache_key = IsfSourceTelemetry.lastCacheKey,
                isf_cache_glucose_mgdl = IsfSourceTelemetry.lastCacheGlucoseMgdl,
                isf_kalman_fast_mgdl = IsfSourceTelemetry.lastKalmanFastIsf,
                isf_adj_engine_mgdl = IsfSourceTelemetry.lastIsfAdjEngine,
                isf_fused_slow_mgdl = IsfSourceTelemetry.lastFusedSlowIsf,
                isf_trust_fast = IsfSourceTelemetry.lastTrustFast,
                isf_dynamic_factor = IsfSourceTelemetry.lastDynamicFactor,
                isf_trajectory_multiplier = IsfSourceTelemetry.lastTrajectoryMultiplier,
                estimated_ra_mgdl_per_min = runCatching { continuousStateEstimator.getLastRa() }.getOrNull(),
                physio_isf_factor = IsfSourceTelemetry.lastPhysioIsfFactor,
                isf_pre_floor_mgdl = CommandedIsf.lastPreFloorMgdlPerU,
                stress_isf_floor_active = IsfSourceTelemetry.lastStressIsfFloorActive,
                stress_isf_floor_reason = IsfSourceTelemetry.lastStressIsfFloorReason,
                stress_isf_floor_isf_mgdl = IsfSourceTelemetry.lastStressIsfFloorIsfMgdl,
                stress_floor_awake_resting_bpm = IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm,
                isf_profile_relative_shadow_mgdl = IsfSourceTelemetry.lastProfileRelativeShadowMgdl,
                isf_profile_relative_bound_hit = IsfSourceTelemetry.lastProfileRelativeBoundHit,
                sensitivity_ratio_r = runCatching { sensitivityRatioEstimator.ratio }.getOrNull(),
                isf_shadow_s_mgdl = runCatching {
                    IsfSourceTelemetry.lastProfileStaticMgdl?.let { sensitivityRatioEstimator.sensitivityMgdl(it) }
                }.getOrNull(),
                sensitivity_observations = runCatching { sensitivityRatioEstimator.observationCount }.getOrNull(),
                // Laissé null ici et écrit tard par `markHtrRaFloorForExport` : la valeur n'existe
                // pas encore au bootstrap du tick.
                htr_ra_floor_mgdl_per_min = null
            )
        )
        // ISF and target levels of this tick for the auditor and the JSONL. Read once, here, where
        // `baseline_state` reads the same numbers, so the rest of the tick works from this object
        // instead of reading the telemetry singletons again. The gestational and thyroid modules
        // have already changed `profile.sens` above, so these are the values the tick really uses.
        // The working target is added later, where the basal schedule sets it.
        auditorProfileTick = AuditorProfileTickState().apply {
            profileStaticIsfMgdl = IsfSourceTelemetry.lastProfileStaticMgdl
            dynamicIsfMgdl = ctx.profile.variable_sens
            commandIsfMgdl = ctx.profile.sens
            commandPreFloorIsfMgdl = CommandedIsf.lastPreFloorMgdlPerU
            commandFloorMultiplier = IsfSourceTelemetry.lastCommandFloorMultiplier
            profileTargetMgdl = ctx.profile.target_bg
            tempTargetActive = ctx.profile.temptargetSet
            keyOn = preferences.get(BooleanKey.OApsAIMIAuditorProfileFactors)
            // Read once per tick, so every later step of the tick judges the same proposal even if
            // a new answer lands from the auditor coroutine in the middle of the tick.
            //
            // Turning the auditor itself off drops whatever it had already proposed. The key
            // dependency only hides the factor switch in the settings, it does not clear it, so
            // without this a user who switches the auditor off would keep dosing on a cached
            // proposal until it expired.
            if (!preferences.get(BooleanKey.AimiAuditorEnabled)) AuditorProfileFactorCache.clear()
            proposal = AuditorProfileFactorCache.latest()
        }
        val rT = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = ctx.dynIsfMode,
            timestamp = ctx.currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.CONTEXT, hormonitorStudyExporter)
        if (ctx.extraDebug.isNotEmpty()) {
            rT.reason.append("${ctx.extraDebug}\n")
        }
        // ⚠️ ASYNC IMPACT: SOS may launch IO work (location + SMS) on a process-scoped scope.
        emergencySos.evaluate(
            aapsLogger = aapsLogger,
            bg = ctx.glucoseStatus.glucose,
            delta = ctx.glucoseStatus.delta,
            iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            preferences = this.preferences,
            nowMs = dateUtil.now()
        )
        logLearnersHealth(rT)
        wCycleInfoForRun = null
        wCycleReasonLogged = false
        lastWCycleBelief = null
        lastProfile = ctx.profile
        val flatBGsDetected = if (ctx.flatBGsDetected && abs(ctx.glucoseStatus.delta) > 3.0) {
            consoleLog.add("⚠️ FLAT OVERRIDE: Delta=${ctx.glucoseStatus.delta} > 3.0 -> Sensor ALIVE.")
            false
        } else {
            ctx.flatBGsDetected
        }
        pendingDecisionCtxForExport = decisionCtx
        return AimiTickDecisionRtBootstrap(decisionCtx, rT, flatBGsDetected)
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
        consoleLog.add("PHYSIO_RT Steps=${rtActivity.stepsToday} HR=${rtActivity.heartRate}bpm")
        if (::tpoOrchestrator.isInitialized) {
            tpoOrchestrator.onTickStart(dateUtil.now())
        }
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB).coerceAtLeast(this.maxSMB)
        val physioSnapshot = physioAdapter.getLatestSnapshot()
        val snsDominance = physioSnapshot.toSNSDominance()
        decisionCtx.adjustments.physiological_context = AimiDecisionContext.PhysioContext(
            hormonal_cycle_phase = wCycleInfoForRun?.let { "${it.phase.name}_Day${it.dayInCycle}" } ?: "Unknown",
            physical_activity_mode = if (snsDominance > 0.6) "Stress/Activity" else "Resting"
        )
        val iobArrayForProfiler = if (preferences.get(BooleanKey.OApsAIMIIntelligenceKineticsProfiler)) {
            ctx.pkpdIobDataArray ?: ctx.iobDataArray
        } else {
            ctx.iobDataArray
        }
        // Effective-IOB "now" (learned kinetics) for the maxIOB release gate — index 0 is the current tick.
        tickIobEffectiveU = ctx.pkpdIobDataArray?.firstOrNull()?.iob
        val iobActionProfile = InsulinActionProfiler.calculate(iobArrayForProfiler, ctx.profile, snsDominance)
        val iobTotal = iobActionProfile.iobTotal
        val iobPeakMinutes = iobActionProfile.peakMinutes
        iobActivityNow = iobActionProfile.activityNow
        val iobActivityIn30Min = iobActionProfile.activityIn30Min
        consoleLog.add(
            "PAI: Peak in ${aimiFmt0(iobPeakMinutes)}m | " +
                "Activity Now=${aimiFmt0(iobActivityNow * 100)}%, " +
                "in 30m=${aimiFmt0(iobActivityIn30Min * 100)}%"
        )
        val observerDiaHours = ctx.effectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 } ?: ctx.profile.dia
        // Wave2 F1: minutes-to-peak from profiler (signed); never absolute effective peak.
        val minutesToPeak = iobPeakMinutes.toInt()
        val insulinActionState = insulinObserver.update(
            currentBg = bg,
            bgDelta = delta.toDouble(),
            iobTotal = iobTotal,
            iobActivityNow = iobActivityNow,
            iobActivityIn30 = iobActivityIn30Min,
            minutesToPeak = minutesToPeak,
            diaHours = observerDiaHours,
            carbsActiveG = cob.toDouble(),
            now = dateUtil.now()
        )
        consoleLog.add("PKPD_OBS ${insulinActionState.reason}")
        tickInsulinActionState = insulinActionState
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
            consoleError.add("❌ GlucoseStatusCalculatorAimi.compute() failed: ${e.message}")
            null
        }
        if (pack == null || pack.gs == null) {
            consoleError.add("❌ No glucose data (AIMI pack empty)")
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
    ): AimiT9PhysioPkpdTubeBootstrap {
        val profile = ctx.profile
        // T9: G6 lead compensation (+25%) so physio, PKPD, LGS, SMB share corrected delta (not only Autodrive V3).
        val isG6Sensor = try {
            activePlugin.activeBgSource.javaClass.simpleName.contains("Dexcom", ignoreCase = true) &&
            activePlugin.activeBgSource.javaClass.simpleName.contains("G6", ignoreCase = true)
        } catch (e: Exception) { false }
        val rawVelocityMgdlMin = glucoseStatus.delta / 5.0 // delta mg/dL/5min → mg/dL/min
        val correctedVelocityMgdlMin = continuousStateEstimator.applyG6LeadCompensation(rawVelocityMgdlMin, isG6Sensor)
        val correctedDelta = correctedVelocityMgdlMin * 5.0
        if (isG6Sensor && correctedDelta != glucoseStatus.delta.toDouble()) {
            consoleLog.add("🌐 T9 G6-Lead: delta_raw=${aimiFmt1(glucoseStatus.delta)} → delta_corr=${aimiFmt1(correctedDelta)} mg/dL/5min")
        }

        // Physio assistant (MTR): multipliers + trace / status line.
        val physioMultipliers = if (preferences.get(app.aaps.core.keys.BooleanKey.AimiPhysioAssistantEnable)) {
            try {
                physioAdapter.getMultipliers(
                    currentBG = glucoseStatus.glucose,
                    currentDelta = glucoseStatus.delta
                    // recentHypoTimestamp will be fetched internally by adapter
                )
            } catch (e: Exception) {
                aapsLogger.error(app.aaps.core.interfaces.logging.LTag.APS, "Physio adapter error - using defaults", e)
                PhysioMultipliersMTR.NEUTRAL
            }
        } else {
            PhysioMultipliersMTR.NEUTRAL
        }
        lastBasePhysioMultipliers = physioMultipliers

        // Log physio modulation if active
        if (!physioMultipliers.isNeutral()) {
            consoleLog.add(
                "🏥 PHYSIO: ISF×${aimiFmt3(physioMultipliers.isfFactor)} " +
                "Basal×${aimiFmt3(physioMultipliers.basalFactor)} " +
                "SMB×${aimiFmt3(physioMultipliers.smbFactor)} " +
                "Conf=${(physioMultipliers.confidence * 100).toInt()}%"
            )
        }

        // Detailed physio log + optional decision trace (adapter may throw — same as historical).
        try {
            val physioLog = physioAdapter.getDetailedLogString()
            consoleError.add(physioLog)
            physioAdapter.getLastDecisionTrace()?.let { trace ->
                consoleLog.add(
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
            consoleError.add("❌ Physio Log Error: ${e.message}")
        }

        // Early PKPD: predictions before SafetyNet, Meal Advisor, and legacy branches (autosens ratio 1.0 here; refined later in tick).
        val earlyAutosensRatio = 1.0
        val earlySens = ctx.profile.sens / earlyAutosensRatio

        val iobForEarlyPkpd = ctx.iobDataArray.firstOrNull()
        val earlyPkpdWindowSinceDoseMin = if (iobForEarlyPkpd != null && iobForEarlyPkpd.lastBolusTime > 0L) {
            ((dateUtil.now() - iobForEarlyPkpd.lastBolusTime) / 60000.0).toInt().coerceAtLeast(0)
        } else {
            90
        }
        val earlyPkpdMealContext = buildPkpdMealContext(
            mealData = ctx.mealData,
            predictedBgMgdl = glucoseStatus.glucose,
            targetBgMgdl = ctx.profile.target_bg,
        )
        val singleLearnPath = preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath)
        this.cachedPkpdRuntime = try {
            pkpdIntegration.setRecentBolusSamples(
                buildRecentPkpdBolusSamples(
                    nowMillis = dateUtil.now(),
                    fallbackWindowMin = earlyPkpdWindowSinceDoseMin
                )
            )
            pkpdIntegration.computeRuntime(
                epochMillis = dateUtil.now(),
                bg = glucoseStatus.glucose,
                deltaMgDlPer5 = glucoseStatus.delta,
                iobU = iobTotal,
                carbsActiveG = ctx.mealData.mealCOB,
                windowMin = earlyPkpdWindowSinceDoseMin,
                exerciseFlag = sportTime,
                profileIsf = earlySens,
                tdd24h = ctx.profile.max_daily_basal * 24.0,
                mealContext = earlyPkpdMealContext,
                consoleLog = consoleLog,
                combinedDelta = glucoseStatus.combinedDelta,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                physioLatentState = lastPhysioLatentState,
                estimatedRaMgdlPerMin = continuousStateEstimator.getLastRa().takeIf { it.isFinite() && it > 0.0 },
                causalStatePosterior = lastPatientState?.causalPosterior,
                patientEventMemory = lastPatientState?.eventMemory,
                allowLearning = !singleLearnPath,
                // Substitute TDD (max basal x 24): read-only for the slew limiter too, otherwise
                // this call would pin the whole tick on a lower-quality input.
                isfRateLimitAuthority = false,
            )
        } catch (e: Exception) {
            consoleError.add("❌ Early PKPD Runtime init failed: ${e.message}")
            null
        }

        val earlyPkpdPredictions = computePkpdPredictions(
            currentBg = glucoseStatus.glucose,
            iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
            finalSensitivity = earlySens,
            cobG = ctx.mealData.mealCOB,
            profile = ctx.profile,
            rT = rT,
            delta = glucoseStatus.delta,
            pkpdRuntime = this.cachedPkpdRuntime,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            hypothesisState = lastUamHypothesisState,
            latentState = lastPhysioLatentState,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
        )

        this.eventualBG = earlyPkpdPredictions.eventual
        this.predictedBg = earlyPkpdPredictions.eventual.toFloat()
        rT.eventualBG = earlyPkpdPredictions.eventual

        var pkpdRuntime = this.cachedPkpdRuntime

        if (!physioMultipliers.isNeutral()) {
            this.variableSensitivity = (earlySens * physioMultipliers.isfFactor).toFloat()
            profile.max_daily_basal = profile.max_daily_basal * physioMultipliers.basalFactor
            this.maxSMB = (this.maxSMB * physioMultipliers.smbFactor).coerceAtLeast(0.1)
            consoleLog.add("🏥 PHYSIO APPLIED: MaxSMB=${aimiFmt2(this.maxSMB)} MaxBasal=${aimiFmt2(profile.max_daily_basal)}")
        }

        val inflamResult = inflammationAdjuster.getAdjustments()
        lastInflammationResult = inflamResult
        if (inflamResult.basalMultiplier != 1.0 || inflamResult.smbMultiplier != 1.0) {
            profile.current_basal = profile.current_basal * inflamResult.basalMultiplier
            profile.max_daily_basal = profile.max_daily_basal * inflamResult.basalMultiplier
            val prevMaxSMB = this.maxSMB
            this.maxSMB = (this.maxSMB * inflamResult.smbMultiplier).coerceAtLeast(0.1)
            this.maxSMBHB = (this.maxSMBHB * inflamResult.smbMultiplier).coerceAtLeast(0.1)
            consoleLog.add("${inflamResult.reason} -> Basal×${aimiFmt2(inflamResult.basalMultiplier)} SMB: ${aimiFmt2(prevMaxSMB)}->${aimiFmt2(this.maxSMB)}U")
        }

        val pumpAgeDays: Float = pumpAgeDaysCached()
        val causalMod = CausalKineticsModulator.modulate(lastPatientState?.causalPosterior)
        val effectiveDiaH = if (preferences.get(BooleanKey.OApsAIMIDiaGovernorEnabled)) {
            DiaGovernor.resolve(
                profileDiaHours = profile.dia,
                contextualDiaShiftHours = causalMod.diaShiftHours,
                pkpdLearnedDiaHours = pkpdRuntime?.params?.diaHrs,
                pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
                governorEnabled = true,
                diaMinBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
                diaMaxBound = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
                learnedBlendWeight = preferences.get(DoubleKey.OApsAIMIDiaGovernorLearnedWeight),
            ).effectiveDiaHours
        } else {
            pkpdRuntime?.params?.diaHrs ?: profile.dia
        }

        // TAP-G peak governor: echo last log line once per distinct string (clipped).
        val peakGovLine = preferences.get(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine)
        if (peakGovLine.isNotBlank()) {
            val alreadyEchoed =
                preferences.get(app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed)
            if (peakGovLine != alreadyEchoed) {
                val clipped = if (peakGovLine.length > 220) peakGovLine.substring(0, 220) + "..." else peakGovLine
                consoleLog.add(clipped)
                preferences.put(
                    app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed,
                    peakGovLine,
                )
            }
        }

        // Tube is intentionally NOT applied on early PKPD floors (T9). It runs once from
        // publishDoseTerminalAuthorityAndSnapshot after gated dose terminals exist.
        return AimiT9PhysioPkpdTubeBootstrap(pumpAgeDays, physioMultipliers)
    }

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
        val isNight = hourOfDay >= 23 || hourOfDay < 6
        val combinedDelta: Float
        val shortAvgDeltaAdj: Float
        if (isG6Byoda && !isNight) {
            combinedDelta = rawCombinedDelta * 1.30f
            shortAvgDeltaAdj = shortAvgDelta * 1.20f
            consoleLog.add(
                "📡 G6_LEAD rawΔcomb=%.2f → %.2f | rawΔshort=%.2f → %.2f (BYODA +30/+20%%)".format(
                    rawCombinedDelta, combinedDelta, shortAvgDelta, shortAvgDeltaAdj
                )
            )
        } else {
            if (isG6Byoda) consoleLog.add("📡 G6_LEAD nuit [${hourOfDay}h] → pas de compensation (sécurité nocturne)")
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
    ): AimiTickClockTirCarbGlucoseBootstrap {
        val profile = ctx.profile
        val civilNow = aimiCivilClock(aimiWallClockMs())
        this.hourOfDay = civilNow.hour
        val circadianMinute = civilNow.minute
        val circadianSecond = civilNow.second
        val dayOfWeek = civilNow.calendarDayOfWeek
        val honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon)
        this.bg = glucoseStatus.glucose
        this.tickCombinedDelta = combinedDelta
        val getlastBolusSMB = latestSmbCached()
        val cacheSmbTimestamp = getlastBolusSMB?.timestamp ?: 0L
        // latestSmbCached() triggers async refresh — cache can lag one or more loop ticks after a legacy prebolus.
        // If DB/cache is older than [internalLastSmbMillis], do not overwrite [lastBolusSMBUnit] (set synchronously in markLegacyMealDecision),
        // or legacy meal prebolus can fire twice within the same runtime window.
        if (cacheSmbTimestamp >= internalLastSmbMillis) {
            this.lastBolusSMBUnit = getlastBolusSMB?.amount?.toFloat() ?: 0.0F
            val diff = abs(now - cacheSmbTimestamp)
            this.lastsmbtime = (diff / (60 * 1000)).toInt()
        } else {
            val diff = abs(now - internalLastSmbMillis)
            this.lastsmbtime = (diff / (60 * 1000)).toInt()
        }
        // ── Suivi de délivrance du prébolus legacy (carry-forward) ─────────────
        // TTL expiré sans confirmation : on abandonne l'état pending (le carry cesse de re-proposer).
        if (pendingLegacyPrebolusUnit > 0.0f && now > pendingLegacyPrebolusExpiry) {
            pendingLegacyPrebolusUnit = 0.0f
            pendingLegacyPrebolusExpiry = 0L
        }
        // Clear du pending dès qu'un bolus correspondant apparaît en base. Seuil 35 % du montant demandé
        // pour absorber le capping AAPS (ex. 7.5 U demandés → 3.75 U délivrés via maxSMBBasalMinutes).
        if (pendingLegacyPrebolusUnit > 0.0f && internalLastLegacyPrebolusMillis > 0L) {
            val prebolusDelivered = try {
                runBlocking {
                    persistenceLayer
                        .getBolusesFromTime(internalLastLegacyPrebolusMillis, true)
                        .any {
                            (it.type == BS.Type.NORMAL || it.type == BS.Type.SMB) &&
                                it.amount >= pendingLegacyPrebolusUnit * 0.35f
                        }
                }
            } catch (_: Exception) {
                false
            }
            if (prebolusDelivered) {
                consoleLog.add("🍱 PREBOLUS_DELIVERED_CONFIRMED: clearing pending ${pendingLegacyPrebolusUnit}U")
                pendingLegacyPrebolusUnit = 0.0f
                pendingLegacyPrebolusExpiry = 0L
            }
        }
        this.maxIob = preferences.get(DoubleKey.ApsSmbMaxIob)
// Tarciso Dynamic Max IOB
        // [FIX] User Request: Strict MaxIOB Limit (Preference Only).
        // Dynamic calculations removed to prevent "dangerous variations".
        this.maxIob = maxIob
        rT.reason.append(rh.gs(ApsStrings.reason_max_iob, maxIob))
        consoleLog.add("MAX_IOB_STATIC: Pref=$maxIob (Dynamic disabled by request)")
        this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)
        // 🔒 STRICT LIMITS: User preferences are HARD CAPS.
        // Dynamic Autodrive boosting removed to prevent overriding user settings.
        val enableUAM = profile.enableUAM
        this.maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)

        // 🔧 ENHANCED MaxSMB Selection: Plateau OR Slope logic
        // Addresses critical edge case: BG stuck high (270-300) with small deltas → slope < 1.0
        // Solution: Use maxSMBHB if EITHER:
        //   1. Active rise detected (slope >= 1.0) - Original logic
        //   2. High plateau (BG >= 250) - NEW, regardless of slope
        // The rise floor obeys whichever ceiling this ladder picks, so remember the branch and both
        // rise signals it read. All three go out in `smb_binding_trace` so the next package can say
        // whether the ladder we now trust is reading the rise correctly.
        this.lastSlopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation.takeIf { it.isFinite() }
        this.lastShortAvgDeltaAtLadder = glucoseStatus.shortAvgDelta.takeIf { it.isFinite() }
        // Trap: `this.shortAvgDelta` is only copied from `glucoseStatus` further down this method,
        // so reading the bare member here would read the PREVIOUS tick. Always pass
        // `glucoseStatus.shortAvgDelta`.
        val ladder = MaxSmbLadder.decide(
            bgMgdl = bg,
            combinedDelta = combinedDelta.toDouble(),
            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
            shortAvgDeltaMgdl5m = glucoseStatus.shortAvgDelta,
            honeymoon = honeymoon,
            maxSmb = this.maxSMB,
            maxSmbHighBg = this.maxSMBHB,
        )
        this.lastMaxSmbLadderBranch = ladder.branch
        consoleLog.add(
            when (ladder.branch) {
                MaxSmbLadder.LADDER_PLATEAU_CRITICAL     ->
                    "MAXSMB_PLATEAU_CRITICAL BG=${bg.roundToInt()} Δ=${aimiFmt1(combinedDelta)} slope=${aimiFmt2(ctx.mealData.slopeFromMinDeviation)} -> maxSMBHB=${aimiFmt2(maxSMBHB)}U (plateau)"

                MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH  ->
                    "MAXSMB_SLOPE_HIGH BG=${bg.roundToInt()} slope=${aimiFmt2(ctx.mealData.slopeFromMinDeviation)} Δ=${aimiFmt1(combinedDelta)} -> maxSMBHB=${aimiFmt2(maxSMBHB)}U (confirmed rise)"

                MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA ->
                    "MAXSMB_DELTA_HIGH BG=${bg.roundToInt()} shortAvgDelta=${aimiFmt2(glucoseStatus.shortAvgDelta)} slope=${aimiFmt2(ctx.mealData.slopeFromMinDeviation)} Δ=${aimiFmt1(combinedDelta)} -> maxSMBHB=${aimiFmt2(maxSMBHB)}U (confirmed rise by delta)"

                MaxSmbLadder.LADDER_SENSITIVE_85         ->
                    "MAXSMB_SLOPE_SENSITIVE BG=${bg.roundToInt()} slope=${aimiFmt2(ctx.mealData.slopeFromMinDeviation)} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (85% maxSMBHB - confirmed rise)"

                MaxSmbLadder.LADDER_PLATEAU_MODERATE_75  ->
                    "MAXSMB_PLATEAU_MODERATE BG=${bg.roundToInt()} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (75% maxSMBHB)"

                MaxSmbLadder.LADDER_FALLING_60           ->
                    "MAXSMB_FALLING BG=${bg.roundToInt()} Δ=${aimiFmt1(combinedDelta)} -> ${aimiFmt2(ladder.ceilingU)}U (60% maxSMBHB)"

                else                                     ->
                    "MAXSMB_STANDARD BG=${bg.roundToInt()} -> ${aimiFmt2(ladder.ceilingU)}U"
            }
        )
        this.maxSMB = ladder.ceilingU

        // 🔒 SAFETY CLAMP: Force Standard MaxSMB if < 120
        // User Rule: "lowbg when < 120". No bypass allowed.
        val stdMaxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
        if (bg < 120.0 && this.maxSMB > stdMaxSMB) {
             this.maxSMB = stdMaxSMB
             // The exported branch tag must show that the clamp won, otherwise the tag reports a
             // promotion that never reached the dose.
             this.lastMaxSmbLadderBranch = when (this.lastMaxSmbLadderBranch) {
                 MaxSmbLadder.LADDER_PLATEAU_CRITICAL    -> MaxSmbLadder.LADDER_PLATEAU_CRITICAL_CLAMPED
                 MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH -> MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_CLAMPED

                 MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA ->
                     MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA_CLAMPED

                 MaxSmbLadder.LADDER_SENSITIVE_85        -> MaxSmbLadder.LADDER_SENSITIVE_85_CLAMPED
                 MaxSmbLadder.LADDER_PLATEAU_MODERATE_75 -> MaxSmbLadder.LADDER_PLATEAU_MODERATE_75_CLAMPED
                 MaxSmbLadder.LADDER_FALLING_60          -> MaxSmbLadder.LADDER_FALLING_60_CLAMPED
                 else                                    -> MaxSmbLadder.LADDER_STANDARD
             }
             consoleLog.add("🔒 STRICT CLAMP: BG<120 -> Forced Standard MaxSMB (${aimiFmt2(stdMaxSMB)}U)")
        }
        val ngrConfig = buildNightGrowthResistanceConfig(ctx.profile, ctx.autosensData, glucoseStatus, targetBg.toDouble())
        var tir1DAYIR = 0.0
        var lastHourTIRAbove: Double? = null
        var tirbasal3IR: Double? = null
        var tirbasal3B: Double? = null
        var tirbasal3A: Double? = null
        var tirbasalhAP: Double? = null
        val tirSnapshot = latestTirWarmupSnapshot()
        this.tir1DAYabove = tirSnapshot.tir1DayAbove
        tir1DAYIR = tirSnapshot.tir1DayInRange
        this.currentTIRLow = tirSnapshot.currentTirLow
        this.currentTIRRange = tirSnapshot.currentTirRange
        this.currentTIRAbove = tirSnapshot.currentTirAbove
        this.lastHourTIRLow = tirSnapshot.lastHourTirLow
        lastHourTIRAbove = tirSnapshot.lastHourTirAbove
        this.lastHourTIRLow100 = tirSnapshot.lastHourTirLow100
        this.lastHourTIRabove170 = tirSnapshot.lastHourTirAbove170
        this.lastHourTIRabove120 = tirSnapshot.lastHourTirAbove120
        tirbasal3IR = tirSnapshot.tirBasal3InRange
        tirbasal3B = tirSnapshot.tirBasal3Below
        tirbasal3A = tirSnapshot.tirBasal3Above
        tirbasalhAP = tirSnapshot.tirBasalHourAbove
        //this.enablebasal = preferences.get(BooleanKey.OApsAIMIEnableBasal)
        this.now = aimiWallClockMs()
        automateDeletionIfBadDay(tir1DAYIR.toInt())

        this.weekend = if (dayOfWeek == Calendar.SUNDAY || dayOfWeek == Calendar.SATURDAY) 1 else 0
        var lastCarbTimestamp = ctx.mealData.lastCarbTime
        val carbSnapshot = latestCarbContextSnapshot(nowMs = now, mealDataLastCarbTime = lastCarbTimestamp, cobNow = cob)
        lastCarbTimestamp = carbSnapshot.lastCarbTimestamp
        this.lastCarbAgeMin = carbSnapshot.lastCarbAgeMin
        this.futureCarbs = carbSnapshot.futureCarbs
        if (this.lastCarbAgeMin < 15 && cob == 0.0f) {
            this.cob = carbSnapshot.effectiveCob
        }
        this.recentNotes = carbSnapshot.recentNotes

        this.tags0to60minAgo = parseNotes(0, 60)
        this.tags60to120minAgo = parseNotes(60, 120)
        this.tags120to180minAgo = parseNotes(120, 180)
        this.tags180to240minAgo = parseNotes(180, 240)
        this.delta = glucoseStatus.delta.toFloat()
        this.shortAvgDelta = glucoseStatus.shortAvgDelta.toFloat()
        this.longAvgDelta = glucoseStatus.longAvgDelta.toFloat()
        val bgAcceleration = glucoseStatus.bgAcceleration.toFloat()
        this.bgacc = bgAcceleration.toDouble()
        return AimiTickClockTirCarbGlucoseBootstrap(
            honeymoon = honeymoon,
            ngrConfig = ngrConfig,
            tir1DAYIR = tir1DAYIR,
            lastHourTIRAbove = lastHourTIRAbove,
            tirbasal3IR = tirbasal3IR,
            tirbasal3B = tirbasal3B,
            tirbasal3A = tirbasal3A,
            tirbasalhAP = tirbasalhAP,
            circadianMinute = circadianMinute,
            circadianSecond = circadianSecond,
            bgAcceleration = bgAcceleration,
        )
    }

    /**
     * `Therapy` hydration (meal clocks, runtimes), trend flags, exercise SMB lockout, T3c/non-T3c exercise early return.
     * Stops **before** manual `applyLegacyMealModes` so prebolus priority is unchanged.
     */
    private fun runTherapyHydrateClocksAndExerciseLockoutGate(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
    ): AimiTherapyExerciseGate {
        val therapy = Therapy(persistenceLayer).also {
            it.updateStatesBasedOnTherapyEvents(forceRefresh = true)
        }
        val deleteTime = therapy.deleteTime
        if (deleteTime) {
            // Still the count based clean up on purpose: the owner asked for the date based one on
            // the "bad day" trigger only. `Therapy.deleteEventDate` does carry the day of the note,
            // so this path could be moved to `removeRowsForDay` later, but that is a change of
            // behaviour and needs to be asked for.
            removeLast200Lines(csvfile)
        }
        this.sleepTime = therapy.sleepTime
        this.snackTime = therapy.snackTime
        this.sportTime = therapy.sportTime
        this.lowCarbTime = therapy.lowCarbTime
        this.highCarbTime = therapy.highCarbTime
        this.mealTime = therapy.mealTime
        this.anticipTime = therapy.anticipTime
        this.fclTime = therapy.fclTime
        this.bfastTime = therapy.bfastTime
        this.lunchTime = therapy.lunchTime
        this.dinnerTime = therapy.dinnerTime
        this.fastingTime = therapy.fastingTime
        this.stopTime = therapy.stopTime
        this.mealruntime = therapy.getTimeElapsedSinceLastEvent("meal")
        this.anticipruntime = therapy.getTimeElapsedSinceLastEvent("anticip")
        this.fclruntime = therapy.getTimeElapsedSinceLastEvent("fcl")
        this.bfastruntime = therapy.getTimeElapsedSinceLastEvent("bfast")
        this.lunchruntime = therapy.getTimeElapsedSinceLastEvent("lunch")
        this.dinnerruntime = therapy.getTimeElapsedSinceLastEvent("dinner")
        this.highCarbrunTime = therapy.getTimeElapsedSinceLastEvent("highcarb")
        this.snackrunTime = therapy.getTimeElapsedSinceLastEvent("snack")
        observeCircadianMealProfile(ctx.currentTime)
        this.iscalibration = therapy.calibrationTime
        this.acceleratingUp = if (delta > 2 && delta - longAvgDelta > 2) 1 else 0
        this.decceleratingUp = if (delta > 0 && (delta < shortAvgDelta || delta < longAvgDelta)) 1 else 0
        this.acceleratingDown = if (delta < -2 && delta - longAvgDelta < -2) 1 else 0
        this.decceleratingDown = if (delta < 0 && (delta > shortAvgDelta || delta > longAvgDelta)) 1 else 0
        this.stable = if (delta > -3 && delta < 3 && shortAvgDelta > -3 && shortAvgDelta < 3 && longAvgDelta > -3 && longAvgDelta < 3) 1 else 0
        val nightbis = hourOfDay <= 7

        refreshAimiContextActivityFlag()
        exerciseInsulinLockoutActive = sportTime || aimiContextActivityActive
        refreshExerciseHyperBasalOverride(profile)
        if (exerciseInsulinLockoutActive) {
            this.maxSMB = 0.0
            this.maxSMBHB = 0.0
            val basalHint = if (exerciseHyperBasalOverrideActive) {
                "basale renforcée (hyper+activité)"
            } else {
                "basale autorisée seulement si BG>${EXERCISE_BASAL_RESUME_BG_MGDL.toInt()} (T3c PI ou flux standard)"
            }
            consoleLog.add(
                "🏃 EXERCISE_LOCKOUT[therapy]: SMB off (sportTime=$sportTime aimiActivity=$aimiContextActivityActive) | $basalHint"
            )
        }

        // 🍱 Exception repas : un repas explicitement déclaré (mode legacy ou Meal Advisor validé) prime sur le
        // lockout exercice/activité — sauf hypo sévère. On NE coupe PAS le tick ici pour laisser passer le prebolus.
        val mealPriorityBypass = mealDeliveryOverridesLockouts()
        if (mealPriorityBypass && exerciseInsulinLockoutActive) {
            consoleLog.add(
                "🍱 MEAL_PRIORITY: lockout exercice/activité contourné pour repas déclaré " +
                    "(bg=${bg.toInt()} > ${SEVERE_HYPO_MEAL_OVERRIDE_MGDL.toInt()}) — prebolus autorisé"
            )
        }

        val t3cBrittle = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        if (t3cBrittle && exerciseInsulinLockoutActive && !exerciseHyperBasalOverrideActive &&
            bg <= EXERCISE_BASAL_RESUME_BG_MGDL && !mealPriorityBypass
        ) {
            markT3cRuntimeOwnership("SAFETY_TERMINAL", "exercise_lockout")
            rT.reason.append(
                "🏃 T3c + sport/contexte activité : basale & SMB arrêtés (BG≤${EXERCISE_BASAL_RESUME_BG_MGDL.toInt()}).\n"
            )
            consoleLog.add("🏃 T3c EXERCISE: return 0 U/h basal (BG=${bg.toInt()} ≤ ${EXERCISE_BASAL_RESUME_BG_MGDL.toInt()})")
            rT.units = 0.0
            logDecisionFinal("T3C_EXERCISE_LOCKOUT", rT, bg, delta)
            return AimiTherapyExerciseGate.ReturnEarly(
                setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, adaptiveMultiplier = adaptiveMult)
            )
        }
        if (!t3cBrittle && exerciseInsulinLockoutActive && !exerciseHyperBasalOverrideActive &&
            bg <= EXERCISE_BASAL_RESUME_BG_MGDL && !mealPriorityBypass
        ) {
            rT.reason.append(
                "🏃 Sport / contexte AIMI activité : basale & SMB arrêtés (BG≤${EXERCISE_BASAL_RESUME_BG_MGDL.toInt()}).\n"
            )
            consoleLog.add("🏃 EXERCISE_LOCKOUT: flux standard interrompu → 0 U/h (BG=${bg.toInt()})")
            rT.units = 0.0
            logDecisionFinal("EXERCISE_LOCKOUT", rT, bg, delta)
            return AimiTherapyExerciseGate.ReturnEarly(
                setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, adaptiveMultiplier = adaptiveMult)
            )
        }
        return AimiTherapyExerciseGate.Continue(nightbis)
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
            snackTime -> "Snack"
            highCarbTime -> "HighCarb"
            mealTime -> "Meal"
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
    ): RT? {
        if (!preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)) return null
        if (!legacyT3cBypassAllowed()) {
            lastT3cHistoricalBypassNeutralizedThisTick = true
            markT3cRuntimeOwnership("LEGACY_SKIPPED", "native_rbt_owner")
            consoleLog.add("🌳 T3C_NATIVE: legacy bypass skipped (native RBT owns T3C)")
            return null
        }
        markT3cRuntimeOwnership("LEGACY_FALLBACK", "rbt_authority_off")
        lastDecisionSource = "T3C_LEGACY_BYPASS"
        consoleLog.add("⚡ T3c Brittle Mode Active: Bypassing standard AIMI algorithm.")

        // 🛡️ T3c Pre-bolus Safety Guard
        // Without robust one-shot guards, lag in database persistence can cause a 24U+ runaway (4U every 5min).
        // We now use a triple-layer safety net:
        // 1. Database History (including manual boluses)
        // 2. Internal Memory (last suggested SMB time - lag-free)
        // 3. Absolute IOB Cap (Emergency fallback)

        val t3cCapWindowMs = 20 * 60 * 1000L
        val t3cCapCutoff   = aimiWallClockMs() - t3cCapWindowMs

        // 1. Check Database (Harden: count ALL bolus types, not just SMB)
        val recentBolusCount = getBolusesFromTimeCached(t3cCapCutoff, true)
            .count { it.type == BS.Type.SMB || it.type == BS.Type.NORMAL }

        // 2. Check Internal Memory (Ensures 1 tick = 1 dose max even if DB is slow)
        val timeSinceInternalSmbMs = aimiWallClockMs() - internalLastSmbMillis
        val internalBlock = timeSinceInternalSmbMs < t3cCapWindowMs

        // 3. Absolute IOB Guard (Safety Floor)
        // 🔒 Respect the USER'S maxIob setting. No hardcoded limits.
        val iobSafetyBlock = iob > maxIob

        if (recentBolusCount < 2 && !internalBlock && !iobSafetyBlock) {
            // 🍱 Legacy Meal Prebolus Support for T3c (Already handled by top-level call above)
            // internalLastSmbMillis + lastBolusSMBUnit for legacy prebolus: see [markLegacyMealDecision] (async SMB cache can lag).
        } else {
            val reason = when {
                iobSafetyBlock -> "IOB_LIMIT (${aimiFmt2(iob)}U > MaxIOB)"
                internalBlock -> "INTERNAL_LOCKOUT (${timeSinceInternalSmbMs/60000}m < 20m)"
                else -> "DB_CAP ($recentBolusCount boluses in 20min)"
            }
            consoleLog.add("🛡️ T3c pre-bolus BLOCKED: $reason — skipping applyLegacyMealModes")
        }

        // Autodrive under T3C: when basal-authority fusion is ON, the proposal runs inside
        // executeT3cBrittleMode (TBR fused, SMB stripped). Otherwise keep DataLake shadow only.
        if (!preferences.get(BooleanKey.OApsAIMIT3cAutodriveBasalAuthority)) {
            runT3cAutodriveShadowTick(ctx, profile, shortAvgDeltaAdj)
        }

        // 🔮 T3c + trajectory / advanced predictions (isolated path — same engines as main loop)
        val iobRowT3c = ctx.iobDataArray.firstOrNull() ?: IobTotal(ctx.currentTime)
        val lastBolusAgeT3c = if (iobRowT3c.lastBolusTime > 0L || internalLastSmbMillis > 0L) {
            val tEff = kotlin.math.max(iobRowT3c.lastBolusTime, internalLastSmbMillis)
            ((ctx.currentTime - tEff) / 60000.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val dynSensT3c = profile.variable_sens.takeIf { it > 0.0 } ?: profile.sens
        val fusedT3c = pkpdRuntime?.fusedIsf
        val sensForT3cPred = (
            when {
                fusedT3c != null && dynSensT3c > 0.0 -> kotlin.math.min(fusedT3c, dynSensT3c)
                fusedT3c != null -> fusedT3c
                else -> dynSensT3c
            }.coerceAtLeast(10.0) * ctx.autosensData.ratio.coerceIn(0.25, 4.0)
            )
        applyAdvancedPredictions(
            bg = bg,
            delta = delta,
            sens = sensForT3cPred,
            iob_data_array = ctx.iobDataArray,
            mealData = ctx.mealData,
            profile = ctx.profile,
            rT = rT
        )
        applyTrajectoryAnalysis(
            currentTime = ctx.currentTime,
            bg = bg,
            delta = delta.toDouble(),
            bgacc = bgacc,
            iobActivityNow = iobActivityNow,
            iob = iob,
            insulinActionState = insulinActionState,
            lastBolusAgeMinutes = lastBolusAgeT3c,
            cob = cob,
            targetBg = originalProfile.target_bg,
            profile = profile,
            rT = rT,
            uiInteraction = ctx.uiInteraction,
            relevanceScore = physioMultipliers.trajectoryRelevanceScore
        )
        // CFRD: enforce the higher LGS floor on the trajectory context as well
        val cfrdLgsFloorForTraj = if (preferences.get(BooleanKey.OApsAIMIT3cCfrdMode))
            preferences.get(DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl) else 70.0
        val lgsT3c = kotlin.math.min(
            90.0,
            (profile.lgsThreshold?.toDouble() ?: 70.0).coerceAtLeast(cfrdLgsFloorForTraj)
        )
        val minPredT3c = rT.predBGs?.IOB?.minOrNull()?.toDouble() ?: bg
        val eventualT3c = rT.eventualBG?.takeIf { it.isFinite() } ?: this.eventualBG.coerceAtLeast(40.0)
        val t3cTrajCtx = T3cTrajectoryContext.build(
            minPredBg = minPredT3c,
            eventualPredBg = eventualT3c,
            bg = bg,
            lgsThresholdMgdl = lgsT3c,
            trajectoryEnabled = rT.trajectoryEnabled == true,
            lastAnalysis = trajectoryGuard.getLastAnalysis()
        )
        consoleLog.add(
            "🛡️ T3c predict+traj: min=${minPredT3c.toInt()} ev=${eventualT3c.toInt()} LGS=${lgsT3c.toInt()} " +
                "traj=${t3cTrajCtx.trajectoryTypeName ?: "—"} E=${t3cTrajCtx.energyBalance?.let { aimiFmt1(it) } ?: "—"}"
        )

        // T3C dependency guarantee (B): deploy the physiological tree + activity belief for the T3C decision,
        // same as the standard path — even when the autodrive shadow tick above didn't run (autodriveEngine null).
        // Guarded so we never rebuild twice per tick. SMB stays 0 (enforced in executeT3cBrittleMode); this only
        // makes the BASAL decision physio-informed (consumed in executeT3cBrittleMode, workstream C).
        if (lastPhysiologicalTreeSnapshot == null) {
            runCatching {
                updatePhysioLatentState(
                    snapshot = physioAdapter.getLatestSnapshot(),
                    sourceSensor = ctx.glucoseStatus.sourceSensor,
                )
            }.onFailure { aapsLogger.error(LTag.APS, "T3C physio/tree deploy failed", it) }
        }

        val adBasalProposal = proposeT3cAutodriveBasalOnly(
            ctx = ctx,
            profile = profile,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            lgsThresholdMgdl = lgsT3c,
        )

        return executeT3cBrittleMode(
            bg = ctx.glucoseStatus.glucose,
            delta = ctx.glucoseStatus.delta.toFloat(),
            shortAvgDelta = ctx.glucoseStatus.shortAvgDelta,
            longAvgDelta = ctx.glucoseStatus.longAvgDelta,
            accel = ctx.glucoseStatus.bgAcceleration,
            duraISFminutes = ctx.glucoseStatus.duraISFminutes,
            duraISFaverage = ctx.glucoseStatus.duraISFaverage,
            profile = profile,
            currenttemp = ctx.currentTemp,
            iob = ctx.iobDataArray.firstOrNull() ?: IobTotal(aimiWallClockMs()),
            targetBg = originalProfile.target_bg,
            variableSensitivity = variableSensitivity.toDouble(),
            maxIob = maxIob,
            eventualBg = eventualT3c.coerceAtLeast(40.0),
            rT = rT,
            trajectoryContext = t3cTrajCtx,
            cgmNoise = ctx.glucoseStatus.noise,
            autodriveBasalProposal = adBasalProposal,
        )
    }

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
        consoleLog = consoleLog,
        logger = aapsLogger,
        effects = legacyEffectSink,
        hasHypoRecovery = lastContextSnapshot?.hasHypoRecovery == true,
        adaptiveMult = adaptiveMult,
        statusLine = { display -> rh.gs(ApsStrings.autodrive_status, display, "Meal Advisor") },
        onSmbDelivered = { units ->
            internalLastSmbMillis = dateUtil.now()
            lastSmbCapped = units
            lastSmbFinal = units
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
        consoleLog.add("🛑 HARD_BRAKE triggered: delta=$delta, short=$shortAvgDelta")
        rT.reason.append("🛑 Hard Brake: Falling Fast & Decelerating -> Zero Basal\n")
        setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = adaptiveMult)
        lastSafetySource = "HardBrake"
        logDecisionFinal("HARD_BRAKE", rT, bg, delta)
        markFinalLoopDecisionFromRT(rT, ctx.currentTemp)
        return rT
    }

    private fun updateHyperDwellAboveHighBgClock(nowMs: Long = aimiWallClockMs()) {
        val highBgPref = preferences.get(DoubleKey.OApsAIMIHighBg)
        val band = HyperTrajectoryHypoCredibility.highBgBandMgdl(targetBg.toDouble(), highBgPref)
        if (bg >= targetBg + band) {
            if (hyperDwellAboveHighBgSinceMs <= 0L) {
                hyperDwellAboveHighBgSinceMs = nowMs
            }
        } else {
            hyperDwellAboveHighBgSinceMs = 0L
        }
    }

    private fun dwellAboveHighBgMinutes(nowMs: Long = aimiWallClockMs()): Int {
        if (hyperDwellAboveHighBgSinceMs <= 0L) return 0
        return ((nowMs - hyperDwellAboveHighBgSinceMs) / 60_000L).toInt().coerceAtLeast(0)
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
                mealAbsorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
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
        val wInfo = wCycleInfoForRun
        return PhysioPhaseFusion.buildClassifierInput(
            bgMgdl = bg,
            targetBgMgdl = targetBg.toDouble(),
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = delta.toDouble(),
            shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
            combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
            mealCobG = cob.toDouble(),
            hourOfDay = hourOfDay,
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
        lastPhysiologicalPhaseOutput = fused.phaseOutput
        lastFusedPhysioMultipliers = fused.multipliers
        CircadianMealProfileStore.observeDawnPhase(
            storage = storage,
            eventTimeMs = dateUtil.now(),
            phaseOutput = fused.phaseOutput,
            mealSignalsActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime || cob > 1.0,
        )
        val policy = fused.phaseOutput.policy
        lastScenarioBestCappedForPhysio = scenario != null &&
            HormonalScenarioTerminalCap.capBestTerminalMgdl(bg, rawBestT, policy) < rawBestT - 0.5
        if (!fused.multipliers.isNeutral() || policy.phase != PhysiologicalPhase.OFF) {
            consoleLog.add(
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
    ): MealAbsorptionPhaseEngine.Output {
        val scenario = lastScenarioProjection
        val floorT = scenario?.clinicalFloor?.terminalMgdl ?: bg
        val bestT = scenario?.scenarioBest?.terminalMgdl ?: bg
        val lateFatRise = isLateFatProteinRise(
            bg = bg,
            predictedBg = predictedBg.toDouble(),
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            iob = iob.toDouble(),
            cob = cob.toDouble(),
            maxSMB = maxSMB,
            lastBolusTimeMs = lastBolusTimeMs,
            mealFlags = MealFlags(
                mealTime = mealTime,
                bfastTime = bfastTime,
                lunchTime = lunchTime,
                dinnerTime = dinnerTime,
                highCarbTime = highCarbTime,
            ),
            nowMs = nowMs,
        )
        val hoursSinceBolus = lastBolusTimeMs?.let { (nowMs - it) / 3_600_000.0 }
        val output = MealAbsorptionPhaseEngine.evaluate(
            MealAbsorptionPhaseEngine.Input(
                bgMgdl = bg,
                targetBgMgdl = targetBg.toDouble(),
                highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
                deltaMgdlPer5 = delta.toDouble(),
                shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
                combinedDeltaMgdlPer5 = combinedDelta.toDouble(),
                deltaPrevMgdlPer5 = mealAbsorptionDeltaPrevOfTick(),
                mealCobG = cob.toDouble(),
                hourOfDay = hourOfDay,
                iobU = iob.toDouble(),
                maxIobU = maxIob,
                bestTerminalMgdl = bestT,
                floorTerminalMgdl = floorT,
                gapPrevMgdl = MealAbsorptionMemory.lastGapMgdl,
                heartRateBpm = heartRateBpm,
                restingHeartRateBpm = restingHeartRateBpm,
                stepsLast15m = stepsLast15m,
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                mealIntent = mealContext.hasMealIntent,
                physiologicalPhase = lastPhysiologicalPhaseOutput?.phase ?: PhysiologicalPhase.OFF,
                estimatedRa = continuousStateEstimator.getLastRa().takeIf { it.isFinite() && it > 0.0 },
                hoursSinceBolus = hoursSinceBolus,
                lateFatProteinRise = lateFatRise,
                nowMs = nowMs,
            ),
        )
        lastMealAbsorptionOutput = output
        if (output.phase.isActive) {
            consoleLog.add(
                "🍽️ MEAL_ABSORPTION: ${output.phase.name} B=${aimiFmt2(output.belief)} " +
                    "pri=${output.mealDeliveryPriority} waves=${output.waveCount} (${output.reason})",
            )
        }
        return output
    }

    private fun physiologicalPhaseExport(): AimiDecisionContext.PhysiologicalPhaseExport? {
        val out = lastPhysiologicalPhaseOutput ?: return null
        val policy = out.policy
        val capU = policy.smbFloorCapU.takeIf { it.isFinite() && it < 100.0 }
        val fused = lastFusedPhysioMultipliers
        return AimiDecisionContext.PhysiologicalPhaseExport(
            phase = out.phase.name,
            confidence = out.confidence,
            behavioral_risk = policy.phase.name,
            reason = policy.reason,
            extended_dawn_guard = policy.extendedDawnGuard,
            scenario_best_capped = lastScenarioBestCappedForPhysio,
            max_htr_tier = policy.maxHtrTier.name,
            smb_floor_cap_u = capU,
            physio_smb_factor_fused = fused?.smbFactor,
            physio_phase_source = fused?.source,
        )
    }

    private fun mealAbsorptionPhaseExport(): AimiDecisionContext.MealAbsorptionPhaseExport? {
        val out = lastMealAbsorptionOutput ?: return null
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

    private fun updatePhysioLatentState(
        snapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
        patternSnapshot: PhysiologicalPatternSnapshot? = lastPhysiologicalPatternSnapshot,
    ): PhysioLatentState {
        val physioContext = physioAdapter.getEffectiveContext()
        val physioTrace = physioAdapter.getLastDecisionTrace()
        val hypothesisState = UamHypothesisStateBuilder.build(
            phaseOutput = lastPhysiologicalPhaseOutput,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            patternSnapshot = patternSnapshot,
            correctionAggressionDecision = correctionAggressionDecision,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
            behaviorProfile = behaviorProfileSource.read(preferences),
        )
        val stressMask = PhysiologicalStressMaskBuilder.build(
            snapshot = snapshot,
            physioContext = physioContext,
            physioTrace = physioTrace,
            phaseOutput = lastPhysiologicalPhaseOutput,
            patternSnapshot = patternSnapshot,
            correctionAggressionDecision = correctionAggressionDecision,
            chronicInflammation = lastInflammationResult,
        )
        val latentState = PhysioLatentStateBuilder.build(
            snapshot = snapshot,
            sourceSensor = sourceSensor,
            phaseOutput = lastPhysiologicalPhaseOutput,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            physioContext = physioContext,
            physioTrace = physioTrace,
            correctionAggressionDecision = correctionAggressionDecision,
            chronicInflammation = lastInflammationResult,
            autonomicStress = stressMask.autonomicStress,
            inflammationRecovery = stressMask.inflammationRecovery,
            hormonalCircadian = stressMask.hormonalCircadian,
            cgmFirstSensorConfidence = preferences.get(BooleanKey.OApsAIMISensorConfidenceCgmFirst),
        )
        lastUamHypothesisState = hypothesisState
        lastPhysioLatentState = latentState
        // Effort/activity belief computed HERE (before the basal decision + meal detection) so its posture and
        // effort-memory can both (a) reduce SMB/basal and (b) veto the undeclared-meal reading of an effort or
        // post-effort adrenaline rise — see [effortSuppressesUndeclaredMeal].
        refreshEffortActivityBelief()
        refreshPatientStateRuntime(
            nowMs = dateUtil.now(),
            contextSnapshot = lastContextSnapshot,
            healthSnapshot = snapshot,
            sourceSensor = sourceSensor,
        )
        if (::tpoOrchestrator.isInitialized) {
            val patientState = lastPatientState
            val patientModeDecision = lastPatientModeDecision
            if (patientState != null && patientModeDecision != null) {
                tpoOrchestrator.onPatientStateReady(
                    patientState = patientState,
                    patientModeName = patientModeDecision.mode.name,
                    patientModeConfidence = patientModeDecision.confidence,
                    correctionAggressionDecision = correctionAggressionDecision,
                    bgMgdl = bg,
                    deltaMgdl5m = delta.toDouble(),
                    cobGrams = cob.toDouble(),
                    minBgLookback75m = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
                    nowMs = dateUtil.now(),
                )
                if (tpoOrchestrator.consumePrefsChangedThisTick()) {
                    maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                    maxSMBHB = preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB).coerceAtLeast(maxSMB)
                }
            }
        }
        return latentState
    }

    private fun resolvePatientRuntimeSnapshotForExport(timestampMs: Long): PatientRuntimeSnapshot? {
        PatientStateRuntimeRepository.getLatest()?.let { return it }
        val patientState = lastPatientState ?: return null
        val patientModeDecision = lastPatientModeDecision ?: return null
        return PatientRuntimeSnapshot(
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            updatedAtMs = timestampMs,
            physiologicalTree = lastPhysiologicalTreeSnapshot,
            harmoniaDecision = lastHarmoniaDecision,
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
        lastContextSnapshot = contextSnap
        updatePhysioLatentState(
            snapshot = enrichThermalSnapshot(wearableSnap),
            sourceSensor = glucoseStatus.sourceSensor,
            patternSnapshot = lastPhysiologicalPatternSnapshot,
        )
    }

    private fun enrichThermalSnapshot(snapshot: HealthContextSnapshot): HealthContextSnapshot {
        val thermalBelief = ThermalBeliefEngine.buildFromCache(
            hrNowBpm = snapshot.hrNow,
            rhrRestingBpm = snapshot.rhrResting,
            sleepDebtMinutes = snapshot.sleepDebtMinutes,
            hrvRmssd = snapshot.hrvRmssd,
            wCyclePhase = wCycleInfoForRun?.phase,
        )
        return snapshot.copy(thermalBelief = thermalBelief)
    }

    private fun refreshPatientStateRuntime(
        nowMs: Long = dateUtil.now(),
        contextSnapshot: ContextSnapshot? = lastContextSnapshot,
        healthSnapshot: HealthContextSnapshot? = null,
        sourceSensor: SourceSensor? = lastPatientSourceSensor,
        refreshSource: PatientRefreshSource = PatientRefreshSource.LOOP_TICK,
    ): PatientStateSnapshot {
        lastContextSnapshot = contextSnapshot
        if (sourceSensor != null) {
            lastPatientSourceSensor = sourceSensor
        }
        val wearableSnap = healthSnapshot ?: try {
            physioAdapter.getLatestSnapshot()
        } catch (_: Exception) {
            HealthContextSnapshot()
        }
        val enrichedSnap = enrichThermalSnapshot(wearableSnap)
        val eventMemory = buildPatientEventMemory(
            currentBgMgdl = bg,
            latentState = lastPhysioLatentState,
            thermalBelief = enrichedSnap.thermalBelief,
            nowMs = nowMs,
        )
        val patientState = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = lastPhysiologicalPhaseOutput,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            patternSnapshot = lastPhysiologicalPatternSnapshot,
            latentState = lastPhysioLatentState,
            hypothesisState = lastUamHypothesisState,
            contextSnapshot = lastContextSnapshot,
            thermalBelief = enrichedSnap.thermalBelief,
            eventMemory = eventMemory,
        )
        lastPatientState = patientState
        val patientModeDecision = PatientModeOrchestrator.evaluate(patientState)
        lastPatientModeDecision = patientModeDecision
        val physioLive = PhysioLiveDigest.from(enrichedSnap, nowMs)
        ensureWCycleInfo()
        val hypoGuardActive =
            TuningContextEngine.parseContext(preferences.get(StringKey.AimiTuningContextSelection)) ==
                AimiTuningContext.HYPO_GUARD
        lastWCycleBelief = EndocrineAmplitudeGovernor.from(
            info = wCycleInfoForRun,
            prefs = wCyclePreferences,
            hypoLoad = eventMemory.recentHypoLoad,
            hypoGuardActive = hypoGuardActive,
            hourOfDay = hourOfDay,
        )
        // Cascade native (R1): tree always deploys on the dose path. AimiPhysioAssistantEnable only
        // gates vitals multipliers / assistant extras — never the spine Tree→Harmonia.
        val bodyKinetics = BodyKineticsDigest.fromTick(
            effectiveDiaHours = tickEffectiveDiaHours,
            effectivePeakMinutes = tickEffectivePeakMinutes,
            insulinActionState = tickInsulinActionState,
        )
        var physiologicalTree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            physioLive = physioLive,
            thermalBelief = enrichedSnap.thermalBelief,
            timestampMs = nowMs,
            currentBgMgdl = bg,
            deltaMgdl5m = delta.toDouble(),
            // Effort belief → tree branches: ACTIVE drives `activity`, RECENT_EFFORT (adrenaline memory) drives
            // `postActivity`, so Harmonia chooses PROTECTIVE_REDUCTION natively during and after effort.
            effortActiveConfidence = lastEffortAssessment
                ?.takeIf { it.state == EffortActivityBelief.State.ACTIVE }?.confidence ?: 0.0,
            effortRecentConfidence = lastEffortAssessment
                ?.takeIf { it.state == EffortActivityBelief.State.RECENT_EFFORT }?.confidence ?: 0.0,
            wCycleBelief = lastWCycleBelief,
            bodyKinetics = bodyKinetics,
        )
        lastPhysiologicalTreeSnapshot = physiologicalTree
        val sensorTelemetry = HarmoniaSensorTelemetry.resolve(
            cgmNoiseRaw = lastLoopCgmNoise,
            sensorInsertionMs = resolveSensorInsertionMsCached(nowMs),
            nowMs = nowMs,
        )
        val scenarioBestForMeal = lastScenarioProjection?.scenarioBest
        val pkpdForMeal =
            cachedRiskEnvelopeDecision?.eventualTerminalMgdl?.takeIf { it.isFinite() }
                ?: authoritativeEventualBg(this.eventualBG).takeIf { it.isFinite() && it > 1.0 }
        // Cascade D3: MealCertainty first — meal_rise_confirmed derives from it (not sticky phase).
        val mealCertainty = physiologicalTree?.let { tree ->
            MealCertaintyBuilder.evaluate(
                MealCertaintyBuilder.Input(
                    trunkState = tree.trunk.globalState,
                    mealBranchConfidence = tree.branches.meal.confidence,
                    digestionDetected = tree.branches.digestion.detected,
                    absorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
                    bgMgdl = bg,
                    deltaMgdl5m = delta.toDouble(),
                    targetBgMgdl = targetBg.toDouble(),
                    cobG = cob.toDouble(),
                    mealRiseConfirmedLegacy = false,
                    effortVeto = effortSuppressesUndeclaredMeal(),
                    shortAvgDeltaMgdl5m = this.shortAvgDelta.toDouble(),
                    effortLive = effortIsLiveMovement(),
                    softCorroboration = MealCertaintyBuilder.softCorroborationFromPhysio(physioLive),
                    pkpdEventualMgdl = pkpdForMeal,
                    scenarioTerminalMgdl = scenarioBestForMeal?.terminalMgdl,
                    scenarioPathMinMgdl = scenarioBestForMeal?.gatePathMinMgdl,
                    scenarioPathMinHitFloor = scenarioBestForMeal?.gatePathMinHitFloor == true,
                ),
            )
        }
        lastMealCertainty = mealCertainty
        // Cascade D3-bis: a confirmed meal (MealCertainty HIGH) outranks the tree's activity veto.
        // The tree is built first because MealCertainty reads its trunk and branches, so the intent
        // has to be re-resolved here. Hypo protection is untouched — only the activity gate moves.
        val treeBeforeMealCertainty = physiologicalTree
        if (treeBeforeMealCertainty != null && mealCertainty?.supportsMealOverProtective == true) {
            val revisedTree = PhysiologicalTreeBuilder.withMealCertainty(
                snapshot = treeBeforeMealCertainty,
                mealOverridesProtective = true,
                deltaMgdl5m = delta.toDouble(),
                currentBgMgdl = bg,
            )
            if (revisedTree.insulinIntent != treeBeforeMealCertainty.insulinIntent) {
                consoleLog.add(
                    "🌳 TREE_INTENT: ${treeBeforeMealCertainty.insulinIntent} → ${revisedTree.insulinIntent} " +
                        "(meal certainty HIGH outranks activity veto)",
                )
            }
            physiologicalTree = revisedTree
            lastPhysiologicalTreeSnapshot = revisedTree
        }
        val harmoniaMealRiseConfirmed = mealCertainty?.supportsMealSupport == true
        val harmoniaEnvironment = physiologicalTree?.let {
            val currentBasalForSimulation = basalaimi.toDouble().takeIf { basal -> basal.isFinite() && basal > 0.0 } ?: 1.0
            val maxBasalForSimulation = preferences.get(DoubleKey.autodriveMaxBasal)
                .takeIf { maxBasal -> maxBasal.isFinite() && maxBasal > 0.1 }
                ?: maxOf(currentBasalForSimulation * 3.0, currentBasalForSimulation + 2.0, 3.0)
            HarmoniaDecisionEnvironment(
                currentBgMgdl = bg,
                deltaMgdl5m = delta.toDouble(),
                iobU = iob.toDouble(),
                cobG = cob.toDouble(),
                currentBasalUph = currentBasalForSimulation,
                maxBasalUph = maxBasalForSimulation,
                maxSmbU = maxOf(maxSMB, maxSMBHB),
                maxIobU = maxIob,
                sensorAgeMin = sensorTelemetry.sensorAgeMin,
                sensorNoise = sensorTelemetry.sensorNoise,
                mealRiseConfirmed = harmoniaMealRiseConfirmed,
                targetBgMgdl = targetBg.toDouble(),
                correctionFragilityScore = eventMemory.correctionFragilityScore,
                postHyperExhaustionScore = eventMemory.postHyperExhaustionScore,
                chaoticEpisodeLoad = lastRbtChaosEvaluation?.score ?: 0.0,
                effectiveDiaHours = tickEffectiveDiaHours,
                effectivePeakMinutes = tickEffectivePeakMinutes,
                bodyKinetics = bodyKinetics,
                endocrineBasalAmp = lastWCycleBelief
                    ?.takeIf {
                        it.enabled && it.applicationMode == EndocrineApplicationMode.APPLIED
                    }
                    ?.effectiveBasalAmp,
                // Carried for the export and the counterfactual only. The decision engine does not
                // read these two, and a test locks that down.
                priorRuntimeBlocker = harmoniaPrevRuntimeBlocker,
                priorBlockedStreak = harmoniaBlockedStreak,
            )
        }
        val harmoniaDecision = HarmoniaDecisionEngine.evaluate(
            tree = physiologicalTree,
            environment = harmoniaEnvironment,
            timestampMs = nowMs,
            mealCertainty = mealCertainty,
        )
        lastHarmoniaDecision = harmoniaDecision
        if (refreshSource == PatientRefreshSource.LOOP_TICK) {
            physiologicalTree?.let { tree ->
                consoleLog.add(
                    "TREE_DEPLOYED trunk=${tree.trunk.globalState.name} " +
                        "conf=${aimiFmt2(tree.trunk.confidence)} " +
                        "risk=${tree.trunk.riskLevel.name} " +
                        "kinetics=${bodyKinetics.reason}",
                )
                consoleLog.add(tree.compactSummary)
            }
            mealCertainty?.let { mc ->
                consoleLog.add(
                    "MEAL_CERTAINTY level=${mc.level.name} tree=${mc.treeState.name} " +
                        "rise=${mc.riseGeometry.name} terminals=${mc.terminalsAgree.name} " +
                        "effortVeto=${mc.effortVeto}",
                )
            }
            harmoniaDecision?.let { decision ->
                consoleLog.add(decision.compactSummary)
                if (!decision.decisionBasis.actionCoherentWithTrunk) {
                    consoleLog.add(
                        "HARMONIA_BRANCH_MISMATCH action=${decision.action.name} " +
                            "trunk=${decision.decisionBasis.trunkState.name} " +
                            "reason=${decision.decisionBasis.mismatchReason} " +
                            "primary=${decision.decisionBasis.primaryReason}",
                    )
                }
            }
        }
        val loopCache = PatientStateLoopCache(
            phaseOutput = lastPhysiologicalPhaseOutput,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            patternSnapshot = lastPhysiologicalPatternSnapshot,
            contextSnapshot = lastContextSnapshot,
            sourceSensor = lastPatientSourceSensor,
            correctionAggressionDecision = correctionAggressionDecision,
            chronicInflammation = lastInflammationResult,
            physioContext = physioAdapter.getEffectiveContext(),
            physioTrace = physioAdapter.getLastDecisionTrace(),
            hypothesisState = lastUamHypothesisState,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
        )
        PatientStateRuntimeRepository.publish(
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            updatedAtMs = nowMs,
            physioLive = physioLive,
            thermalBelief = enrichedSnap.thermalBelief,
            physiologicalTree = physiologicalTree,
            harmoniaDecision = harmoniaDecision,
            loopCache = loopCache,
            refreshSource = refreshSource,
        )
        return patientState
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

        observeIfFresh(bfastTime, bfastruntime, "bfast")
        observeIfFresh(lunchTime, lunchruntime, "lunch")
        observeIfFresh(dinnerTime, dinnerruntime, "dinner")
        observeIfFresh(snackTime, snackrunTime, "snack")
        observeIfFresh(highCarbTime, highCarbrunTime, "highcarb")
        observeIfFresh(mealTime, mealruntime, "meal")

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
                lastPhysiologicalPhaseOutput?.policy,
            )
            return scenario.clinicalFloor.terminalMgdl to capped
        }
        val floorTerminal = minPredictedAcrossCurves(rT.predBGs) ?: bg
        val bestTerminal = when {
            eventualBG.isFinite() && eventualBG > bg + 15.0 -> eventualBG
            lastEventualBgSnapshot.isFinite() && lastEventualBgSnapshot > bg + 15.0 -> lastEventualBgSnapshot
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
            iob = iob,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            eventualBG = eventualBG,
            targetBg = targetBg,
            hourOfDay = hourOfDay,
            adaptiveMult = adaptiveMult,
            bgacc = bgacc,
            duraISFminutes = duraISFminutes,
            duraISFaverage = duraISFaverage,
            maxIob = maxIob,
            variableSensitivity = variableSensitivity,
            maxSMB = maxSMB,
            maxSMBHB = maxSMBHB,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            sleepTime = sleepTime,
            exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
            exerciseHyperBasalOverrideActive = exerciseHyperBasalOverrideActive,
            exerciseBasalResumeBgMgdl = EXERCISE_BASAL_RESUME_BG_MGDL,
            highBgOverrideUsed = highBgOverrideUsed,
            mealAdvisorOneShotThisTick = mealAdvisorOneShotThisTick,
            lastScenarioBestCappedForPhysio = lastScenarioBestCappedForPhysio,
            lastPhysioLatentState = lastPhysioLatentState,
            lastUamHypothesisState = lastUamHypothesisState,
            lastPatientState = lastPatientState,
            lastPatientModeDecision = lastPatientModeDecision,
            lastMealAbsorptionOutput = lastMealAbsorptionOutput,
            lastPhysiologicalPhaseOutput = lastPhysiologicalPhaseOutput,
            lastPostHypoDeliveryAuthority = lastPostHypoDeliveryAuthority,
            lastHarmoniaDecision = lastHarmoniaDecision,
            lastPhysiologicalTreeSnapshot = lastPhysiologicalTreeSnapshot,
            lastTubeAdvisorSmbCapScale = lastTubeAdvisorSmbCapScale,
            tickInsulinActionState = tickInsulinActionState,
            correctionAggressionDecision = correctionAggressionDecision,
            lastInflammationResult = lastInflammationResult,
            currentThyroidEffects = currentThyroidEffects,
            lastMealCertainty = lastMealCertainty,
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
            consoleLog = consoleLog,
            state = state,
            pkpd = cachedPkpdRuntime,
            pkpdIntegration = pkpdIntegration,
            basalNeuralLearner = basalNeuralLearner,
            basalLearner = basalLearner,
            nightGrowthResistanceMode = nightGrowthResistanceMode,
            trajectoryGuard = trajectoryGuard,
            endoAdjuster = endoAdjuster,
            recentGlucose = AimiRecentGlucose { glucoseStatusCalculatorAimi.getRecentGlucose() },
            postHypoClassification = AimiPostHypoClassification { recentBGs, cob, explicitMealMode, shortAvgDelta, delta, slopeFromMinDeviation, estimatedCarbs, estimatedCarbsAgeMs, localHour, reason ->
                classifyPostHypoState(
                    recentBGs = recentBGs,
                    cob = cob,
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
                    lastPostHypoOrdinal = ordinal
                }
                override fun setLastNgrBasalMultiplier(multiplier: Double) {
                    lastNgrBasalMultiplier = multiplier
                }
            },
        )
    }

    private fun rbtIgnoreMinPredictedCurve(): Boolean =
        lastRbtAppliedHints?.ignoreMinPredictedCurve == true

    private fun minPredictedBgForRbtWiring(rawMinPred: Double?): Double? {
        if (rawMinPred == null) return null
        val hints = lastRbtAppliedHints ?: return rawMinPred
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
        consoleLog = consoleLog,
        fields = object : AimiRbtResolveFields {
            override fun scenario() = lastScenarioProjection
            override fun curves() = lastAdvancedPredictionCurves
            override fun bg() = this@DetermineBasalaimiSMB2.bg
            override fun targetBg() = this@DetermineBasalaimiSMB2.targetBg.toDouble()
            override fun delta() = this@DetermineBasalaimiSMB2.delta.toDouble()
            override fun shortAvgDelta() = this@DetermineBasalaimiSMB2.shortAvgDelta.toDouble()
            override fun mealAbsorption() = lastMealAbsorptionOutput
            override fun phaseOutput() = lastPhysiologicalPhaseOutput
            override fun uamHypothesis() = lastUamHypothesisState
            override fun iob() = this@DetermineBasalaimiSMB2.iob.toDouble()
            override fun maxIob() = this@DetermineBasalaimiSMB2.maxIob
            override fun eventualBg() = eventualBG
            override fun mealModeActive() =
                this@DetermineBasalaimiSMB2.mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime
            override fun hourOfDay() = this@DetermineBasalaimiSMB2.hourOfDay
            override fun exerciseLockout() = exerciseInsulinLockoutActive
            override fun sportTime() = this@DetermineBasalaimiSMB2.sportTime
            override fun sleepTime() = this@DetermineBasalaimiSMB2.sleepTime
            override fun trajectoryRelevance() =
                lastFusedPhysioMultipliers?.trajectoryRelevanceScore?.toDouble()
                    ?: lastBasePhysioMultipliers.trajectoryRelevanceScore.toDouble()
            override fun maxSmbEffective() = maxSMBHB.coerceAtLeast(maxSMB)
            override fun barrierPermittedU() = autodriveEngine.lastCbfPermittedU.takeIf { it.isFinite() && it >= 0.0 }
            override fun insulinActivity() = tickInsulinActionState
            override fun loadGovernorMultiplierG() = lastLoadGovernorMultiplierG
            override fun safetyTerminals() = lastSafetyTerminalsForRbt
            override fun riskEnvelope() = cachedRiskEnvelopeEarly
            override fun contextActivityActive() = aimiContextActivityActive
            override fun trajBridgePending() = pendingTrajSpiralBasal != null
            override fun fusedMultipliers() = lastFusedPhysioMultipliers
            override fun wCycleBelief() = lastWCycleBelief
            override fun patientMode() = lastPatientModeDecision
            override fun contextIntentCount() = lastContextSnapshot?.intentCount ?: 0
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
                lastInsulinStackingEvaluation = evaluation
            }
            override fun setPattern(snapshot: PhysiologicalPatternSnapshot) {
                lastPhysiologicalPatternSnapshot = snapshot
            }
            override fun setContext(snapshot: ContextSnapshot?) {
                lastContextSnapshot = snapshot
            }
        },
        latent = AimiRbtResolveLatent { snapshot, sourceSensor, patternSnapshot ->
            updatePhysioLatentState(snapshot, sourceSensor, patternSnapshot)
        },
        deltaPrev = AimiRbtDeltaPrev { mealAbsorptionDeltaPrevOfTick() },
        clock = AimiRbtResolveClock { dateUtil.now() },
    )

    /**
     * Resolve RBT + physio gate + wiring once per loop tick (dedup-guarded).
     */
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
    ): RbtLiveCommitResult? {
        if (rbtResolvedThisTick) {
            return lastRbtLiveCommitResult
        }
        val rbtPrefsEarly = RecursiveBeliefPreferences.from(preferences)
        if (!RecursiveBeliefPreferences.isActive(rbtPrefsEarly)) return null
        rbtResolvedThisTick = true
        val htr = evaluateHyperTrajectoryRelease(
            v3SmbU = v3SmbU,
            rT = rT,
            combinedDelta = combinedDelta,
            tdd24hU = tdd24hU,
            rbtLeafOnly = rbtPrefsEarly.authorityEnabled,
        )
        val rbtSnapshot = runRecursiveBeliefResolve(
            v3SmbU = v3SmbU,
            htr = htr,
            rT = rT,
            combinedDelta = combinedDelta,
            tdd24hU = tdd24hU,
            profile = profile,
            autosens = ctx.autosensData,
            glucoseStatus = ctx.glucoseStatus,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            autodriveGateOpen = autodriveGateOpen,
            mpcFeedForwardRa = mpcFeedForwardRa,
            cbfShieldDeltaU = cbfShieldDeltaU,
        )
        lastRecursiveBeliefSnapshot = rbtSnapshot
        rbtSnapshot?.let { snap ->
            snap.loadGovernor?.let { lg ->
                lastLoadGovernorMultiplierG = lg.multiplierG
                if (lg.applied || lg.multiplierG < 0.99) {
                    consoleLog.add("⚖️ ${lg.summary}${if (lg.applied) "" else " [pref-off]"}")
                }
            }
            consoleLog.add(UnfoldExporter.formatLogLine(snap))
        }
        val chaosEval = rbtSnapshot?.let { snap ->
            RbtChaosEvaluator.evaluate(
                RbtChaosEvaluator.Input(
                    snapshot = snap,
                    trajectoryUncertain = trajectoryGuard.getLastAnalysis()?.classification == TrajectoryType.UNCERTAIN,
                    patternCapFlapping = patternCapHold.holding,
                ),
            )
        }
        lastRbtChaosEvaluation = chaosEval
        val targetForPostHypoExit = profile.target_bg.takeIf { it > 0.0 }
            ?: targetBg.toDouble().takeIf { it > 0.0 }
            ?: 100.0
        val aggressiveRiseExit = PostHypoAggressiveRiseExit.shouldExit(
            bgMgdl = bg,
            targetBgMgdl = targetForPostHypoExit,
            deltaMgdl5m = delta.toDouble(),
        )
        if (aggressiveRiseExit) {
            consoleLog.add(
                "🚀 POST_HYPO_AGGRESSIVE_RISE_EXIT: bg=${aimiFmt0(bg)} " +
                    "≥ target+30 (${aimiFmt0(targetForPostHypoExit + 30.0)}) Δ=${aimiFmt1(delta)} > 15 → act normally"
            )
        }
        RbtEpisodeMemory.tick(
            nowMs = dateUtil.now(),
            postHypoReboundProb = lastPhysioLatentState?.postHypoReboundProb ?: 0.0,
            chaosScore = chaosEval?.score ?: 0.0,
            mealProb = lastPhysioLatentState?.mealProb ?: 0.0,
            recentNadirBgMgdl = minBgInLastMinutes(45),
            aggressiveRiseExit = aggressiveRiseExit,
        )
        val activeEpisode = RbtEpisodeMemory.activeEpisode(dateUtil.now())
        chaosEval?.takeIf { it.active || it.caution }?.let {
            consoleLog.add("🌪️ RBT_CHAOS: ${it.summary()}")
        }
        activeEpisode?.let {
            consoleLog.add(
                "📖 RBT_EPISODE: ${it.kind.name} age=${aimiFmt0(it.ageMinutes(dateUtil.now()))}min " +
                    "peak=${aimiFmt2(it.peakScore)} ticks=${it.tickCount}" +
                    if (it.deepHypo) " deep" else " light",
            )
        }
        val rbtPrefs = RecursiveBeliefPreferences.from(preferences)
        val authorityGate = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = rbtPrefs.authorityEnabled,
                requestedAuthority = rbtSnapshot?.resolutions?.releaseAuthority ?: ReleaseAuthority.NONE,
                predictionAvailable = lastPredictionAvailable,
                phaseOutput = lastPhysiologicalPhaseOutput,
                patternSnapshot = lastPhysiologicalPatternSnapshot,
                latentState = lastPhysioLatentState,
                hypothesisState = lastUamHypothesisState,
                patientState = lastPatientState,
                patientModeDecision = lastPatientModeDecision,
                safetyRiskExport = lastSafetyRiskExport,
                chaos = chaosEval,
                episode = activeEpisode,
                bgMgdl = bg,
                targetBgMgdl = targetForPostHypoExit,
                deltaMgdl5m = delta.toDouble(),
                mealHyperBypassEnabled = rbtPrefs.mealHyperBypassEnabled,
                treeInsulinIntent = lastPhysiologicalTreeSnapshot?.insulinIntent ?: InsulinIntent.NONE,
                treeInsulinUrgency = lastPhysiologicalTreeSnapshot?.insulinUrgency ?: 0.0,
                treeMealRiseFrontLoadEnabled = rbtPrefs.treeMealRiseFrontLoadEnabled,
            ),
        )
        lastRecursiveAuthorityGateDecision = authorityGate
        lastRbtAppliedHints = RbtResolutionBridge.apply(
            resolution = rbtSnapshot?.resolutions,
            effectiveAuthority = authorityGate.effectiveAuthority,
            chaos = chaosEval,
            episode = activeEpisode,
            defaultMealPriority = lastMealAbsorptionOutput?.mealDeliveryPriority == true,
        )
        consoleLog.add("🔌 RBT_WIRE: ${lastRbtAppliedHints?.summary ?: "inactive"}")
        val result = mergeRbtHyperTrajectoryRelease(
            htr = htr,
            rbtSnapshot = rbtSnapshot,
            authorityGate = authorityGate,
            rT = rT,
        )
        lastRbtLiveCommitResult = result
        return result
    }

    private fun mergeRbtHyperTrajectoryRelease(
        htr: HyperTrajectoryReleaseResult,
        rbtSnapshot: RecursiveBeliefSnapshot?,
        authorityGate: RecursiveBeliefAuthorityGate.Decision,
        rT: RT,
    ): RbtLiveCommitResult {
        val physioCapU = lastPhysiologicalPhaseOutput?.policy
            ?.takeIf { it.capsHtrRelease() }
            ?.smbFloorCapU
        val stackingCapU = lastInsulinStackingEvaluation?.takeIf {
            it.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB
        }?.smbAbsoluteCapU
        // Soft meal proposals never bind here; PatternCapHold only re-applies HARD caps.
        val patternCapU = patternCapHold.resolve(
            rawCapU = lastPhysiologicalPatternSnapshot?.smbCapU,
            rising = delta > 0f,
            rawKind = lastPhysiologicalPatternSnapshot?.smbCapKind,
        )
        val softPatternProposalU = lastPhysiologicalPatternSnapshot?.softProposedCapU()
        if (patternCapHold.holding && patternCapU != null) {
            consoleLog.add("🧷 Pattern cap hold: keeping HARD ${aimiFmt2(patternCapU)}U during rise (pattern flapped)")
        }
        if (softPatternProposalU != null) {
            consoleLog.add("🍽️ Pattern soft proposal ${aimiFmt2(softPatternProposalU)}U (Harmonia may lift within maxSMBHB)")
        }
        consoleLog.add("🪜 RBT_GATE: ${authorityGate.summary()}")
        val rbtAuthority = authorityGate.effectiveAuthority != ReleaseAuthority.NONE
        val effectiveHtr = if (rbtSnapshot != null &&
            (rbtAuthority || physioCapU != null || stackingCapU != null || patternCapU != null)
        ) {
            val r = rbtSnapshot.resolutions
            val rawLifted = if (rbtAuthority) {
                val rbtLifted =
                    htr.v3SmbBeforeU +
                        (r.smbDemandU - htr.v3SmbBeforeU).coerceAtLeast(0.0) * authorityGate.liftBlend
                max(htr.v3SmbBeforeU, rbtLifted)
            } else {
                htr.v3SmbBeforeU
            }
            var lifted = rawLifted
            physioCapU?.let { lifted = min(lifted, it) }
            stackingCapU?.let { lifted = min(lifted, it) }
            patternCapU?.let { cap -> lifted = min(lifted, cap) }
            var traceDraft = lastSmbBindingTraceDraft.copy(
                htrBeforeU = htr.v3SmbBeforeU,
                htrAfterU = htr.v3SmbAfterU,
                rbtBeforeU = htr.v3SmbAfterU,
                rbtAfterU = rawLifted,
                patternActive = lastPhysiologicalPatternSnapshot?.active
                    ?.joinToString(separator = "+") { it.id.name }
                    ?.takeIf { it.isNotEmpty() },
                patternCapU = patternCapU ?: softPatternProposalU,
            )
                .appendStage("HTR", htr.v3SmbBeforeU, htr.v3SmbAfterU, phase = "AUTODRIVE_PRE_TERMINAL", kind = "LIFT")
                .appendStage("RBT", htr.v3SmbAfterU, rawLifted, phase = "AUTODRIVE_PRE_TERMINAL", kind = "LIFT")
            var traceValue = rawLifted
            physioCapU?.let { cap ->
                val after = min(traceValue, cap)
                traceDraft = traceDraft.appendStage("PHYSIO_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
                traceValue = after
            }
            stackingCapU?.let { cap ->
                val after = min(traceValue, cap)
                traceDraft = traceDraft.appendStage("IOB_SURVEILLANCE_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
                traceValue = after
            }
            softPatternProposalU?.let { proposal ->
                // Telemetry only — soft meal proposal must not bind terminal pre-caps.
                traceDraft = traceDraft.appendStage(
                    "PATTERN_SOFT_PROPOSAL",
                    traceValue,
                    traceValue,
                    proposal,
                    "AUTODRIVE_PRE_TERMINAL",
                    "PROPOSAL",
                )
            }
            patternCapU?.let { cap ->
                val after = min(traceValue, cap)
                traceDraft = traceDraft.appendStage("PATTERN_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
                traceValue = after
            }
            traceDraft = traceDraft.copy(preTerminalAfterCapsU = traceValue)
            lastSmbBindingTraceDraft = traceDraft
            htr.copy(
                active = lifted > htr.v3SmbBeforeU + 0.02,
                smbFloorU = if (rbtAuthority) min(r.smbDemandU, lifted) else min(htr.smbFloorU, lifted),
                v3SmbAfterU = lifted,
                suppressTrajBasalShift = r.suppressTrajBasalShift || htr.suppressTrajBasalShift,
                hypoMinPredIgnored = lastRbtAppliedHints?.ignoreMinPredictedCurve == true || r.hypoMinPredIgnored,
                reason = htr.reason + " | RBT[${authorityGate.effectiveAuthority}] ${r.reasonCodes.joinToString(",")} gate=${authorityGate.reasonCodes.joinToString("+")}",
            )
        } else {
            htr
        }
        lastHyperTrajectoryRelease = effectiveHtr
        return RbtLiveCommitResult(
            baselineHtr = htr,
            effectiveHtr = effectiveHtr,
            rbtAuthority = rbtAuthority,
        )
    }

    private fun deliverV3SmbFromRbt(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        hypoThresholdMgdl: Double,
        v3CommandSafe: Boolean,
        adCommandReason: String?,
        rbtCommit: RbtLiveCommitResult?,
    ) {
        val effectiveHtr = rbtCommit?.effectiveHtr ?: lastHyperTrajectoryRelease ?: return
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
            consoleLog.add("🚀 HTR active but lifted SMB=0 (IOB/headroom); floor=${aimiFmt2(effectiveHtr.smbFloorU)}U")
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
        val isNight = hourOfDay >= 23 || hourOfDay < 6
        val physioOut = lastPhysiologicalPhaseOutput
        val mealOut = lastMealAbsorptionOutput
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
                iobU = iob.toDouble(),
                maxIobU = maxIob,
                maxSmbEffectiveU = maxSMBHB.coerceAtLeast(maxSMB),
                v3SmbU = v3SmbU,
                dwellAboveHighBgMinutes = dwellAboveHighBgMinutes(),
                trajectoryType = trajectoryGuard.getLastAnalysis()?.classification,
                minPredictedBgMgdl = minPredictedAcrossCurves(rT.predBGs),
                aggressive = htrPrefs.aggressive,
                isNight = isNight,
                exerciseLockout = exerciseInsulinLockoutActive,
                mealCobG = cob.toDouble(),
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
        val pending = pendingTrajSpiralBasal ?: return
        pendingTrajSpiralBasal = null
        if (lastHyperTrajectoryRelease?.suppressTrajBasalShift == true) {
            consoleLog.add("🌀 HTR/RBT: deferred Traj-Bridge basal skipped — ${pending.reason}")
            return
        }
        val tbrFrac = lastRecursiveBeliefSnapshot?.resolutions?.tbrDemandFraction ?: 1.0
        rT.rate = pending.proactiveBasalUph * tbrFrac
        rT.duration = pending.durationMin
        rT.reason.append(" | 🌀 Traj-Bridge: ${pending.reason}")
        lastSafetySource = pending.safetyTierLabel
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
        if (now - lastRiseFloorContributionMs > RISE_FLOOR_REARM_MS) {
            riseFloorSpentU = 0.0
        }
        return (budget - riseFloorSpentU).coerceAtLeast(0.0)
    }

    /**
     * Records what the floor actually contributed, so the budget above can be spent down.
     *
     * Called with the amount by which the floor raised the dose above the model — not with the whole
     * dose. Insulin the model asked for is not the floor's doing and must not consume its budget.
     */
    private fun noteRiseFloorContribution(contributedU: Double) {
        if (contributedU <= 0.0) return
        riseFloorSpentU += contributedU
        lastRiseFloorContributionMs = dateUtil.now()
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
    ): app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState? {
        val bgNow = ctx.glucoseStatus.glucose
        if (!bgNow.isFinite() || bgNow <= 0.0) return null
        val velocity = shortAvgDeltaAdj.toDouble() / 5.0
        if (!velocity.isFinite()) return null

        val canonicalSI = if (pkpdRuntime != null) pkpdRuntime.fusedIsf / 10000.0
        else variableSensitivity.toDouble() / 10000.0
        if (!canonicalSI.isFinite() || canonicalSI <= 0.0) return null

        val mealSignals = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime ||
            ctx.mealData.mealCOB >= 0.1 || hasRecentMealEstimate

        return runCatching {
            app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState.createSafe(
                bg = bgNow,
                bgVelocity = velocity,
                iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
                cob = ctx.mealData.mealCOB,
                // No online-learner factor: the engaged path stopped applying it too, so the estimator
                // sees the same sensitivity on every tick. See the note in AutodriveEngine.tick.
                estimatedSI = canonicalSI,
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                physiologicalStressMask = lastPhysioLatentState?.toAttentionMask() ?: DoubleArray(0),
                hour = hourOfDay,
                steps = physioAdapter.getLatestSnapshot().stepsLast15m,
                sourceSensor = ctx.glucoseStatus.sourceSensor,
                combinedDelta = combinedDelta.toDouble(),
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                applyHypoRecoveryRaDampening = postHypoRecoveryActive() && !mealSignals,
                physioExtendedDawnGuard = lastPhysiologicalPhaseOutput?.policy?.extendedDawnGuard == true,
            )
        }.getOrNull()
    }

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
        if (!smbTerminalSealed || requested <= current + 1e-9) {
            rT.units = requested
            return
        }
        if (owner in smbPostSealRaiseAllowedOwners) {
            smbSealAllowedRaiseCount++
            consoleLog.add("🔓 SMB_SEAL_EXCEPTION[$owner]: ${aimiFmt2(current)} -> ${aimiFmt2(requested)} U")
            rT.units = requested
            return
        }
        smbSealRefusedCount++
        smbSealRefusedTotalU += requested - current
        consoleError.add(
            "🔒 SMB_SEAL_REFUSED[$owner]: tentative ${aimiFmt2(current)} -> ${aimiFmt2(requested)} U " +
                "apres le terminal ; la dose reste a ${aimiFmt2(current)} U"
        )
    }

    /** Closes the terminal for this tick. Called once, at the end of `finalizeAndCapSMB`. */
    private fun sealSmbTerminal() {
        smbTerminalSealed = true
    }

    private fun markEstimatorDiagnosticsForExport(decisionCtx: AimiDecisionContext) {
        runCatching {
            decisionCtx.baseline_state.let { b ->
                b.ra_estimator_advances = continuousStateEstimator.runCount
                b.ra_estimator_replayed_calls = continuousStateEstimator.replayedCallCount
                b.ra_aligned_tau_shadow_mgdl_per_min = continuousStateEstimator.lastRaAlignedTauShadow
                b.smb_seal_refused_count = smbSealRefusedCount
                b.smb_seal_refused_total_u = smbSealRefusedTotalU.takeIf { it > 0.0 }
                b.smb_seal_allowed_raise_count = smbSealAllowedRaiseCount
                // Written here, immediately before serialisation, because this is the one point every
                // export path goes through — the mistake that made `ra_estimator_advances` reach
                // 7 ticks out of 93 (Part A-bis correction 1).
                b.effort_smb_factor_requested = lastEffortSmbFactorRaw
                b.effort_smb_factor_applied = lastEffortSmbFactorApplied
                b.effort_smb_before_u = lastEffortSmbBeforeU
                b.effort_smb_after_u = lastEffortSmbAfterU
                b.effort_smb_floored_by_meal = lastEffortSmbFactorRaw?.let { raw ->
                    lastEffortSmbFactorApplied?.let { applied -> applied > raw + 1e-9 }
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
                b.rise_floor_spent_u = riseFloorSpentU
                b.rise_floor_minutes_since_contribution =
                    lastRiseFloorContributionMs
                        .takeIf { it > 0L }
                        ?.let { (dateUtil.now() - it) / 60000.0 }
            }
        }
    }

    private fun markHtrRaFloorForExport(floorMgdlPerMin: Double?, raUsedMgdlPerMin: Double) {
        val baseline = pendingDecisionCtxForExport?.baseline_state ?: return
        baseline.htr_ra_floor_mgdl_per_min = floorMgdlPerMin
        baseline.estimated_ra_used_mgdl_per_min = raUsedMgdlPerMin.takeIf { it.isFinite() }
        baseline.cbf_coefficient_used = autodriveEngine.lastControlCoefficientUsed.takeIf { it > 0.0 }
        baseline.cbf_coefficient_unfloored = autodriveEngine.lastControlCoefficientUnfloored.takeIf { it > 0.0 }
        baseline.cbf_permitted_u = autodriveEngine.lastCbfPermittedU.takeIf { it.isFinite() }
        baseline.cbf_permitted_unfloored_u = autodriveEngine.lastCbfPermittedUnflooredU.takeIf { it.isFinite() }
        baseline.cbf_profile_isf_mgdl = autodriveEngine.lastProfileIsfSeen.takeIf { it > 0.0 }
        pendingDecisionCtxForExport?.adjustments?.control_barrier = buildControlBarrierExport()
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
        if (before != raEstimatorRunCountAtTickStart) return // something already observed this tick
        val state = buildRaObservationState(ctx, combinedDelta, shortAvgDeltaAdj, pkpdRuntime, hasRecentMealEstimate)
            ?: return
        val observed = runCatching {
            continuousStateEstimator.updateAndPredict(state, tickId = raObservationId(ctx))
        }.getOrNull() ?: state
        consoleLog.add("🍽️ RA_OBSERVE[$reason]: Ra=${aimiFmt2(continuousStateEstimator.getLastRa())}")
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
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            variableSensitivity = variableSensitivity,
            hourOfDay = hourOfDay,
            exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
            adaptiveMult = adaptiveMult,
            maxIob = maxIob,
            iob = iob,
            maxSmb = maxSMB,
            maxSmbHb = maxSMBHB,
            postHypo = lastPostHypoDeliveryAuthority,
            mealChannelHint = lastRbtAppliedHints?.mealChannel,
            basePhysioMultipliers = lastBasePhysioMultipliers,
            eventualBg = eventualBG,
            targetBg = targetBg,
            delta = delta,
            smbBindingDraft = lastSmbBindingTraceDraft,
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
            consoleLog = consoleLog,
            gater = AimiAutodriveGater { bg, combined, cob, uamConfidence, explicitMeal, recentMeal, minBg75, ra, mealChannel ->
                autodriveGater.shouldEngageV3(
                    bg = bg,
                    combinedDelta = combined,
                    cob = cob,
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
                    raNetCombinedDelta = combinedDelta
                    raNetShortAvgDeltaAdj = shortAvgDeltaAdj
                }
                override fun noteAutodriveGate(engaged: Boolean, kindName: String, reason: String) {
                    pendingDecisionCtxForExport?.baseline_state?.let { baseline ->
                        baseline.autodrive_gate_engaged = engaged
                        baseline.autodrive_gate_kind = kindName
                        baseline.autodrive_gate_reason = reason
                    }
                }
                override fun setAutodriveEngaged() {
                    lastAutodriveState = AutodriveState.ENGAGED
                }
                override fun setLastHtrRaFloorMgdlPerMin(floor: Double?) {
                    lastHtrRaFloorMgdlPerMin = floor
                }
                override fun setSmbBindingDraft(draft: SmbBindingTrace.Draft) {
                    lastSmbBindingTraceDraft = draft
                }
                override fun setPostHypoSmbBeforeCapU(units: Double) {
                    lastPostHypoSmbBeforeCapU = units
                }
                override fun setPostHypoSmbAfterCapU(units: Double) {
                    lastPostHypoSmbAfterCapU = units
                }
            },
            uam = AimiUamConfidence { AimiUamHandler.confidenceOrZero() },
            fcl = AimiFclDeclared { fclProfile -> fclDeclaredThisTick(fclProfile) },
            minBg = AimiMinBgLookback { lookback -> minBgInLastMinutes(lookback) },
            postHypoRecovery = AimiPostHypoRecovery { postHypoRecoveryActive() },
            tdd24h = AimiTdd24h { resolveTdd24hForExport() },
            phase = AimiPhysiologicalPhase { rt, deltaNow, steps, hr, rhr, base ->
                refreshPhysiologicalPhase(rt, deltaNow, steps, hr, rhr, base)
                lastPhysiologicalPhaseOutput
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
    ): RT? {
        val isPostHypo = postHypoState !is PostHypoState.None

        val isCompression = isCompressionProtectionCondition(delta.toFloat(), reason)

        if (isCompression) {
            logDecisionFinal("COMPRESSION", rT, bg, delta)
            markFinalLoopDecisionFromRT(rT, ctx.currentTemp)
            return rT
        }

        val terminatorThresholdAdd = when {
            autosensRatio < 0.8 -> 10.0
            autosensRatio > 1.2 -> 30.0
            else -> 15.0
        }
        val terminatorTarget = targetBgMgdl + terminatorThresholdAdd

        if (!nightbis && autodriveEnabledPref && bg >= 80 && !isPostHypo && !hasRecentBolus45m &&
            isDriftTerminatorCondition(
                bg.toFloat(),
                terminatorTarget.toFloat(),
                delta.toFloat(),
                shortAvgDeltaRawForDrift,
                combinedDelta.toFloat(),
                ctx.mealData.slopeFromMinDeviation,
                totalBolusLastHour,
                reason
            ) && modesCondition
        ) {
            val terminatortap = dynamicPbolusSmall

            if (this.maxSMB < 0.1 && !exerciseInsulinLockoutActive) {
                this.maxSMB = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                if (this.maxSMB < 0.1) this.maxSMB = 0.5
                reason.append(" [Drift Override]")
                consoleLog.add("⚡ DriftTerminator: Overrode Basal-First block (MaxSMB 0.0 -> ${aimiFmt2(this.maxSMB)})")
            }

            reason.append("→ Drift Terminator (Trigger +${terminatorThresholdAdd}): Micro-Tap ${terminatortap}U\n")
            consoleLog.add("AD_EARLY_TBR_TRIGGER rate=0.0 duration=0 reason=DriftTerminator_Tap")
            consoleLog.add("AD_SMALL_PREBOLUS_TRIGGER amount=$terminatortap reason=DriftTerminator")
            finalizeAndCapSMB(rT, terminatortap, reason.toString(), ctx.mealData, threshold, decisionSource = "DriftTerminator")
            logDecisionFinal("DRIFT_TERMINATOR", rT, bg, delta)
            markFinalLoopDecisionFromRT(rT, ctx.currentTemp)
            return rT
        }

        return null
    }

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
        rT.reason.append(rh.gs(ApsStrings.reason_maxsmb, maxSMB))
        var nowMinutes = hourOfDay + circadianMinute / 60.0 + circadianSecond / 3600.0
        nowMinutes = (kotlin.math.round(nowMinutes * 100) / 100)
        val circadianSensitivity = circadianSensitivityHourly(nowMinutes)
        val deliverAt = ctx.currentTime

        val pumpDesc = activePlugin.activePump.pumpDescription
        val pumpCaps = PumpCaps(
            basalStep = if (pumpDesc.basalStep > 0) pumpDesc.basalStep else 0.05,
            bolusStep = if (pumpDesc.bolusStep > 0) pumpDesc.bolusStep else 0.05,
            minDurationMin = 30,
            maxBasal = profile.max_basal,
            maxSmb = 3.0
        )
        val profileCurrentBasal = pumpCapabilityValidator.validateBasal(profile.current_basal, pumpCaps)
        var basal: Double

        val noise = glucoseStatus.noise
        if (bg <= 10 || bg == 38.0 || noise >= 3) {
            rT.reason.append(rh.gs(ApsStrings.reason_cgm_calibrating))
        }
        if (minAgo > 12 || minAgo < -5) {
            rT.reason.append(rh.gs(ApsStrings.reason_bg_data_old, systemTime, minAgo, bgTime))
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append(rh.gs(ApsStrings.reason_cgm_flat))
        }

        val maxIobLimit = maxIob
        var targetBgLocal = (profile.min_bg + profile.max_bg) / 2
        var minBgLocal = profile.min_bg
        var maxBgLocal = profile.max_bg

        if (contextTargetOverride != null) {
            val override = contextTargetOverride
            if (minBgLocal < override) minBgLocal = override
            if (maxBgLocal < override) maxBgLocal = override
        }

        var sensitivityRatioLocal = 0.0
        val highTemptargetRaisesSensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = if (honeymoon) 130 else 100
        val halfBasalTarget = profile.half_basal_exercise_target

        when {
            !profile.temptargetSet && recentSteps5Minutes >= 0 && (recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500) && recentSteps10Minutes > 0 && predictedBg < 140 -> {
                this.targetBg = 130.0f
            }

            !profile.temptargetSet && predictedBg >= 120 && combinedDelta > 3 -> {
                var baseTarget = if (honeymoon) 110.0 else 70.0
                if (hourOfDay in 0..11 || hourOfDay in 15..19 || hourOfDay >= 22) {
                    baseTarget = if (honeymoon) 110.0 else 90.0
                }
                var hyperTarget = max(baseTarget, profile.target_bg - (bg - profile.target_bg) / 3).toInt()
                hyperTarget = (hyperTarget * min(circadianSensitivity, 1.0)).toInt()
                hyperTarget = max(hyperTarget, baseTarget.toInt())

                this.targetBg = hyperTarget.toFloat()
                targetBgLocal = hyperTarget.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
                sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
                sensitivityRatioLocal = round(sensitivityRatioLocal, 2)
                consoleLog.add(rh.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
            }

            !profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120 -> {
                val baseHypoTarget = if (honeymoon) 130.0 else 110.0
                val hypoTarget = baseHypoTarget * max(1.0, circadianSensitivity)
                this.targetBg = min(hypoTarget.toFloat(), 166.0f)
                targetBgLocal = targetBg.toDouble()
                val c = (halfBasalTarget - normalTarget).toDouble()
                sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
                sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
                sensitivityRatioLocal = round(sensitivityRatioLocal, 2)
                consoleLog.add(rh.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
            }

            else -> {
                val defaultTarget = profile.target_bg
                this.targetBg = defaultTarget.toFloat()
                targetBgLocal = targetBg.toDouble()
            }
        }
        if (highTemptargetRaisesSensitivity && profile.temptargetSet && targetBgLocal > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && targetBgLocal < normalTarget
        ) {
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatioLocal = c / (c + targetBgLocal - normalTarget)
            sensitivityRatioLocal = min(sensitivityRatioLocal, profile.autosens_max)
            sensitivityRatioLocal = round(sensitivityRatioLocal, 2)
            consoleLog.add(rh.gs(ApsStrings.sensitivity_ratio_temp_target, sensitivityRatioLocal, targetBgLocal))
        } else {
            sensitivityRatioLocal = ctx.autosensData.ratio
            consoleLog.add(rh.gs(ApsStrings.autosens_ratio_log, sensitivityRatioLocal))
        }
        basal = profile.current_basal / sensitivityRatioLocal
        // Endocrine amp applied once in setTempBasal / Harmonia production — not here (avoids double scale).
        basal = roundBasal(basal)
        if (basal != profileCurrentBasal) {
            consoleLog.add(rh.gs(ApsStrings.console_adjust_basal, profileCurrentBasal, basal))
        } else {
            consoleLog.add(rh.gs(ApsStrings.console_basal_unchanged, basal))
        }

        if (profile.temptargetSet) {
            consoleLog.add(rh.gs(ApsStrings.console_temp_target_set))
        } else {
            if (profile.sensitivity_raises_target && ctx.autosensData.ratio > 1 || profile.resistance_lowers_target && ctx.autosensData.ratio < 1) {
                minBgLocal = round((minBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
                maxBgLocal = round((maxBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
                var newTargetBg = round((targetBgLocal - 60) * ctx.autosensData.ratio, 0) + 60
                newTargetBg = max(80.0, newTargetBg)
                if (targetBgLocal == newTargetBg) {
                    consoleLog.add(rh.gs(ApsStrings.console_target_bg_unchanged, newTargetBg))
                } else {
                    consoleLog.add(rh.gs(ApsStrings.console_target_bg_changed, targetBgLocal, newTargetBg))
                }
                targetBgLocal = newTargetBg
            }
        }

        return GlobalAimiBasalScheduleBootstrap(
            pumpCaps = pumpCaps,
            profileCurrentBasal = profileCurrentBasal,
            basal = basal,
            targetBg = targetBgLocal,
            minBg = minBgLocal,
            maxBg = maxBgLocal,
            sensitivityRatio = sensitivityRatioLocal,
            deliverAt = deliverAt,
            maxIobLimit = maxIobLimit,
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
        if (abs(this.iob - iobData.iob.toFloat()) > 1.0) {
            consoleLog.add("⚠️ IOB Mismatch: Profiler=${this.iob} vs System=${iobData.iob}")
        }

        val tick: String = if (glucoseStatus.delta > -0.5) {
            "+" + round(glucoseStatus.delta)
        } else {
            round(glucoseStatus.delta).toString()
        }
        val minDelta = min(glucoseStatus.delta, glucoseStatus.shortAvgDelta)
        val minAvgDelta = min(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta)

        consoleError.add("CR:${profile.carb_ratio}")

        val now = aimiWallClockMs()
        val timeMillis5 = now - 5 * 60 * 1000
        val timeMillis10 = now - 10 * 60 * 1000
        val timeMillis15 = now - 15 * 60 * 1000
        val timeMillis30 = now - 30 * 60 * 1000
        val timeMillis60 = now - 60 * 60 * 1000
        val timeMillis180 = now - 180 * 60 * 1000

        if (preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
            val stepsSearchStart = now - 210 * 60 * 1000
            val allStepsCounts = stepsCountsCached(now)

            if (allStepsCounts.isNotEmpty()) {
                val lastSteps = allStepsCounts.maxByOrNull { it.timestamp }
                aapsLogger.debug(LTag.APS, "Steps Data: Found ${allStepsCounts.size} records. Last: ${lastSteps?.steps5min} steps @ ${java.util.Date(lastSteps?.timestamp ?: 0)}")
            } else {
                aapsLogger.debug(LTag.APS, "Steps Data: No records found in last 210 mins")
            }

            val valid5 = allStepsCounts.filter { it.timestamp >= timeMillis5 }.maxByOrNull { it.timestamp }
            val fallbackRecord = if (valid5 == null) {
                allStepsCounts.filter { it.timestamp >= (now - 30 * 60 * 1000) }.maxByOrNull { it.timestamp }
            } else null

            this.recentSteps5Minutes = valid5?.steps5min ?: fallbackRecord?.steps5min ?: 0

            this.recentSteps10Minutes = allStepsCounts.filter { it.timestamp >= timeMillis10 }
                .maxByOrNull { it.timestamp }?.steps10min ?: 0

            this.recentSteps15Minutes = allStepsCounts.filter { it.timestamp >= timeMillis15 }
                .maxByOrNull { it.timestamp }?.steps15min ?: 0

            this.recentSteps30Minutes = allStepsCounts.filter { it.timestamp >= timeMillis30 }
                .maxByOrNull { it.timestamp }?.steps30min ?: 0

            this.recentSteps60Minutes = allStepsCounts.filter { it.timestamp >= timeMillis60 }
                .maxByOrNull { it.timestamp }?.steps60min ?: 0

            this.recentSteps180Minutes = allStepsCounts.filter { it.timestamp >= timeMillis180 }
                .maxByOrNull { it.timestamp }?.steps180min ?: 0
        } else {
            this.recentSteps5Minutes = StepService.getRecentStepCount5Min()
            this.recentSteps10Minutes = StepService.getRecentStepCount10Min()
            this.recentSteps15Minutes = StepService.getRecentStepCount15Min()
            this.recentSteps30Minutes = StepService.getRecentStepCount30Min()
            this.recentSteps60Minutes = StepService.getRecentStepCount60Min()
            this.recentSteps180Minutes = StepService.getRecentStepCount180Min()
        }

        try {
            val allHeartRates = heartRatesCached(now)

            if (allHeartRates.isNotEmpty()) {
                val lastHR = allHeartRates.maxByOrNull { it.timestamp }
                aapsLogger.debug(LTag.APS, "HR Data: Found ${allHeartRates.size} records. Last: ${lastHR?.beatsPerMinute} @ ${java.util.Date(lastHR?.timestamp ?: 0)}")
            } else {
                aapsLogger.debug(LTag.APS, "HR Data: No records found in last 200 mins")
            }

            fun getRateForWindow(windowMillis: Long): List<HR> {
                val windowStart = now - windowMillis
                return allHeartRates.filter {
                    val end = it.timestamp + it.duration
                    end >= windowStart
                }
            }

            val hr5List = getRateForWindow(5 * 60 * 1000)
            this.averageBeatsPerMinute = if (hr5List.isNotEmpty()) {
                hr5List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                val partialFallback = allHeartRates.filter { (it.timestamp + it.duration) >= (now - 30 * 60 * 1000) }
                val lastKnown = partialFallback.maxByOrNull { it.timestamp }
                if (lastKnown != null) {
                    lastKnown.beatsPerMinute
                } else {
                    Double.NaN
                }
            }

            val hr10List = getRateForWindow(10 * 60 * 1000)
            this.averageBeatsPerMinute10 = if (hr10List.isNotEmpty()) {
                hr10List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                this.averageBeatsPerMinute
            }

            val hr60List = getRateForWindow(60 * 60 * 1000)
            // The 80.0 below is a substitute, not a measurement. It stays because other readers
            // (ActivityManager's avgHrResting) depend on a non-zero number, but anything that
            // STRENGTHENS a dose must know the difference — see [HeartRateTrendIsf].
            this.heartRateBaselineIsReal = hr60List.isNotEmpty()
            this.averageBeatsPerMinute60 = if (hr60List.isNotEmpty()) {
                hr60List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                80.0
            }

            val hr180List = getRateForWindow(180 * 60 * 1000)
            this.averageBeatsPerMinute180 = if (hr180List.isNotEmpty()) {
                hr180List.map { it.beatsPerMinute.toInt() }.average()
            } else {
                80.0
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Error processing Heart Rate data", e)
            averageBeatsPerMinute = 80.0
            averageBeatsPerMinute10 = 80.0
            averageBeatsPerMinute60 = 80.0
            averageBeatsPerMinute180 = 80.0
            heartRateBaselineIsReal = false
        }
        // 💓 Heart-rate trend — the ONE heart-rate path that strengthens a dose. It now stands down
        // during a fast rise, where an elevated heart rate is a consequence of the rise rather than
        // information about its cause, and on a baseline that was substituted rather than measured.
        // See [HeartRateTrendIsf].
        val heartRateTrendMultiplier = HeartRateTrendIsf.multiplier(
            steps10m = recentSteps10Minutes,
            avgBpm10 = averageBeatsPerMinute10,
            avgBpm60 = averageBeatsPerMinute60,
            baselineIsReal = heartRateBaselineIsReal,
            bgMgdl = bg.toDouble(),
            deltaMgdl5m = delta.toDouble(),
        )
        if (heartRateTrendMultiplier < 1.0) {
            this.variableSensitivity *= heartRateTrendMultiplier.toFloat()
            consoleLog.add(
                "💓 HR_TREND_ISF x%.2f (hr10 %.0f / hr60 %.0f, steps10 %d)".format(
                    Locale.US, heartRateTrendMultiplier,
                    averageBeatsPerMinute10, averageBeatsPerMinute60, recentSteps10Minutes,
                )
            )
        }

        return AimiPostBasalBootstrapActivityVitals(
            tick = tick,
            minDelta = minDelta,
            minAvgDelta = minAvgDelta,
        )
    }

    /** Hour snapshot + pregnancy pref captured after `aimilimit` adjust (historical position) for downstream [BasalDecisionEngine.Input]. */
    private data class AimiBasalAimiThroughPaiStageSnapshot(
        val timenowHour: Int,
        val sixAMHour: Int,
        val pregnancyEnable: Boolean,
    )

    /**
     * TDD→`basalaimi`, carb index / `aimilimit`, grossesse+TIR basales, accélération BG / early basal, puis PAI sur ISF.
     * Runs **after** [runPostBasalBootstrapIobTickStepsAndHeartRate], **before** [applyEndoAndActivityAdjustments].
     * Mutates [basalaimi], [ci], [aimilimit], [variableSensitivity]; reads [adaptiveMult], prefs, [unifiedReactivityLearner], [basalDecisionEngine].
     */
    @SuppressLint("DefaultLocale")
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
        if (tdd7Days.toFloat() != 0.0f) {
            // [FIX] Use raw TDD-based basal for the decision loop to avoid double-scaling in setTempBasal
            // but use it here for the internal 'basalaimi' reference.
            basalaimi = (tdd7Days / preferences.get(DoubleKey.OApsAIMIweight)).toFloat()
        } else {
            basalaimi = profileCurrentBasal.toFloat()
            consoleLog.add("TDDis 0 -> Baseline Basal: ${basalaimi}U/h")
        }
        this.basalaimi = basalDecisionEngine.smoothBasalRate(tdd7P.toFloat(), tdd7Days.toFloat(), basalaimi)
        if (tdd7Days.toFloat() != 0.0f) {
            this.ci = (450 / tdd7Days).toFloat()
            // 🎯 Update carb limit using the harmonized learner (Unified Scaling)
            val learnedBasalForLimit = basalaimi * adaptiveMult
            this.aimilimit = (preferences.get(DoubleKey.OApsAIMICHO) / (450 / tdd7Days)).toFloat() * adaptiveMult.toFloat()
        }

        val choKey = preferences.get(DoubleKey.OApsAIMICHO)
        this.aimilimit = when {
            ci != 0.0f && ci.isFinite() -> (choKey / ci).toFloat()
            else -> (choKey / profile.carb_ratio).toFloat()
        }

        // 🎯 Apply Harmonized Adaptive Multiplier to carb limits
        if (adaptiveMult != 1.0) {
            this.aimilimit *= adaptiveMult.toFloat()
        }
        val timenowCaptured = aimiLocalHour()
        val sixAMHourCaptured = 6

        val pregnancyEnableCaptured = preferences.get(BooleanKey.OApsAIMIpregnancy)

        if (tirbasal3B != null && pregnancyEnableCaptured && tirbasal3IR != null) {
            // 🎯 UnifiedReactivityLearner is now used exclusively
            val useUnified = preferences.get(BooleanKey.OApsAIMIUnifiedReactivityEnabled)

            this.basalaimi = when {
                tirbasalhAP != null && tirbasalhAP >= 5           -> (basalaimi * 2.0).toFloat()
                lastHourTIRAbove != null && lastHourTIRAbove >= 2 -> (basalaimi * 1.8).toFloat()

                timenowCaptured < sixAMHourCaptured                               -> {
                    val multiplier = if (honeymoon) 1.2 else 1.4
                    val reactivity = if (useUnified) {
                        unifiedReactivityLearner.globalFactor
                    } else {
                        1.0  // Fallback to neutral if disabled
                    }
                    consoleLog.add("Reactivity (< 6AM): enabled=$useUnified, factor=${aimiFmt3(reactivity)}")
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                timenowCaptured > sixAMHourCaptured                               -> {
                    val multiplier = if (honeymoon) 1.4 else 1.6
                    val reactivity = if (useUnified) {
                        unifiedReactivityLearner.globalFactor
                    } else {
                        1.0  // Fallback to neutral if disabled
                    }
                    consoleLog.add("Reactivity (> 6AM): enabled=$useUnified, factor=${aimiFmt3(reactivity)}")
                    (basalaimi * multiplier * reactivity).toFloat()
                }

                tirbasal3B <= 5 && tirbasal3IR in 70.0..80.0      -> (basalaimi * 1.1).toFloat()
                tirbasal3B <= 5 && tirbasal3IR <= 70              -> (basalaimi * 1.3).toFloat()
                tirbasal3B > 5 && tirbasal3A!! < 5                -> (basalaimi * 0.85).toFloat()
                else                                              -> basalaimi
            }
        }

        this.basalaimi = if (honeymoon && basalaimi > profileCurrentBasal * 2) (profileCurrentBasal.toFloat() * 2) else basalaimi

        this.basalaimi = if (basalaimi < 0.0f) 0.0f else basalaimi
        val deltaAcceleration = glucoseStatus.delta - glucoseStatus.shortAvgDelta
        if (deltaAcceleration > 1.5 && bg > 130) {
            // Si la glycémie accélère (+1.5mg/dL/5min par rapport à la moyenne), on augmente le basal
            val boostFactor = 1.2f // Boost de 20%
            this.basalaimi = (this.basalaimi * boostFactor).coerceAtMost(profile.max_basal.toFloat())
            consoleLog.add("Basal boosté (+20%) pour accélération BG.")
        } else if (bg in 80.0..115.0 && glucoseStatus.delta > 1.0) {
            // 🚀 EARLY BASAL: Réactivité précoce pour les montées douces (80-115 mg/dL)
            // L'objectif est de ne pas attendre 130 mg/dL pour réagir.

            var earlyFactor = 1.0f
            if (deltaAcceleration > 0.5) {
                // Accélération détectée (même faible)
                earlyFactor = 1.25f // +25%
                consoleLog.add("Early Basal: Accélération détectée en zone basse (+25%)")
            } else {
                // Montée linéaire simple
                earlyFactor = 1.15f // +15%
                consoleLog.add("Early Basal: Montée progressive (+15%)")
            }

            // Application sécurisée : Max 1.5x le profil (restons modérés en zone basse)
            val safeCap = (profileCurrentBasal * 1.5).toFloat()
            this.basalaimi = (this.basalaimi * earlyFactor).coerceAtMost(safeCap)
        }
        var newVariableSensitivity = paiBaseSensitivity // fused base ISF before PAI adjustment

        // PAI: proactive ISF vs BG rise and IOB peak / fade (InsulinActionProfiler signals).
        consoleLog.add("PAI Logic: Base ISF=${aimiFmt1(paiBaseSensitivity)}")

        // Rising BG + high: urgency factor from IOB peak timing.
        if (delta > 1.5 && bg > 120) {
            val urgencyFactor = when {
                // Le pic est loin (>45min) OU le pic est déjà bien passé (<-30min) -> URGENCE
                iobPeakMinutes > 45 || iobPeakMinutes < -30 -> {
                    consoleLog.add("PAI: BG rising & IOB badly timed. AGGRESSIVE.")
                    0.60 // ISF réduit de 40%
                }
                // L'activité de l'insuline va diminuer. On anticipe.
                iobActivityIn30Min < iobActivityNow * 0.9 -> {
                    consoleLog.add("PAI: BG rising & IOB activity will drop. PROACTIVE.")
                    0.90 // ISF réduit de 10%
                }
                // Le pic est dans un avenir proche (0-45min). On peut être patient.
                iobPeakMinutes in 0.0..45.0 -> {
                    consoleLog.add("PAI: BG rising but IOB peak is coming. PATIENT.")
                    1.0 // Pas de changement
                }
                else -> 1.0 // Cas par défaut
            }
            newVariableSensitivity *= urgencyFactor
            if (urgencyFactor != 1.0) {
                consoleLog.add("PAI: Urgency factor ${aimiFmt2(urgencyFactor)} applied. New ISF=${aimiFmt1(newVariableSensitivity)}")
            }
        }

        // High BG, flat/slow drift: slight aggressiveness if IOB will fade (anti-rebound).
        if (delta in -1.0..1.5 && bg > 140) {
            // Si l'activité de l'insuline va chuter, on risque un rebond.
            if (iobActivityIn30Min < iobActivityNow * 0.8) {
                consoleLog.add("PAI: BG high/stable but IOB will fade. Anti-rebound.")
                newVariableSensitivity *= 0.95 // On est 5% plus agressif
            }
        }

        this.variableSensitivity = newVariableSensitivity.toFloat()

        return AimiBasalAimiThroughPaiStageSnapshot(
            timenowHour = timenowCaptured,
            sixAMHour = sixAMHourCaptured,
            pregnancyEnable = pregnancyEnableCaptured,
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
            consoleLog.add(
                "🧷 STRESS_ISF_FLOOR %.1f -> %.1f (floor %.1f)".format(
                    Locale.US, applied.beforeMgdlPerU, applied.afterMgdlPerU, floorMgdl,
                )
            )
        }
        profile.max_daily_basal = profile.max_daily_basal * physioMultipliers.basalFactor
        if (exerciseInsulinLockoutActive) {
            this.maxSMB = 0.0
            this.maxSMBHB = 0.0
        } else {
            this.maxSMB = (this.maxSMB * physioMultipliers.smbFactor).coerceAtLeast(0.1)
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
        consoleLog.add(
            "🧪 AUDITOR_ISF %.1f -> %.1f (x%.3f, floor %.1f)".format(
                Locale.US,
                raw.workingMgdl,
                this.variableSensitivity.toDouble(),
                floored.effective,
                floored.lowerBoundMgdl ?: 0.0,
            )
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
            postHypoActive = lastPostHypoDeliveryAuthority.active,
            minBg75mMgdl = minBgInLastMinutesOrNull(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            exerciseLockout = exerciseInsulinLockoutActive || sportTime,
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
            postHypoActive = lastPostHypoDeliveryAuthority.active,
            minBg75mMgdl = minBgInLastMinutesOrNull(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            exerciseLockout = exerciseInsulinLockoutActive || sportTime,
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
            postHypoActive = lastPostHypoDeliveryAuthority.active,
            exerciseLockout = exerciseInsulinLockoutActive || sportTime,
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
        val effectiveSens = sens * ctx.autosensData.ratio
        val pkpdPredictions = computePkpdPredictions(
            currentBg = bg,
            iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
            finalSensitivity = effectiveSens,
            cobG = ctx.mealData.mealCOB,
            profile = profile,
            rT = rT,
            delta = delta.toDouble(),
            pkpdRuntime = pkpdRuntime,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            hypothesisState = lastUamHypothesisState,
            latentState = lastPhysioLatentState,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
        )
        this.eventualBG = pkpdPredictions.eventual
        this.predictedBg = pkpdPredictions.eventual.toFloat()
        rT.eventualBG = pkpdPredictions.eventual
        // Audit instrumentation (log + JSONL export, no behaviour impact): divergence between the
        // PKPD eventual consumed by the SMB gates and the physio-enriched scenario terminal
        // computed earlier in this tick.
        run {
            val divergenceAudit = PredictionDivergenceAuditor.audit(
                bgMgdl = bg,
                pkpdEventualMgdl = pkpdPredictions.eventual,
                scenarioBestMgdl = lastScenarioProjection?.scenarioBest?.terminalMgdl,
            )
            val physioPhaseName = lastPhysiologicalPhaseOutput?.phase?.name
            val mealPhaseName = lastMealAbsorptionOutput?.phase?.name
            consoleLog.add(PredictionDivergenceAuditor.formatLogLine(divergenceAudit, physioPhaseName, mealPhaseName))
            lastPredDivergenceExport = PredictionDivergenceAuditor.toJsonObject(divergenceAudit, physioPhaseName, mealPhaseName)
        }
        val iobConsensus = IobConsensus.resolve(
            aapsIobUnits = iobData.iob,
            pkpdIobUnits = pkpdIntegration.reconstructedIobUnits().takeIf { cachedPkpdRuntime != null },
        )
        if (iobConsensus.source == IobDecisionSource.PKPD_WHEN_AAPS_NEGATIVE) {
            consoleLog.add(
                "🛡️ IOB_CONSENSUS_PKPD: AAPS=${aimiFmt2(iobConsensus.aapsIobUnits)} → " +
                    "PKPD=${aimiFmt2(iobConsensus.pkpdIobUnits)} (Δ=${aimiFmt2(iobConsensus.deltaUnits)})"
            )
        }
        val bgi = round((-iobData.activity * effectiveSens * 5), 2)
        var deviation = round(30 / 5 * (minDelta - bgi))
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucoseStatus.longAvgDelta - bgi))
            }
        }
        val naiveEbgResolution = NaiveEventualBgSignGuard.resolve(
            bgMgdl = bg,
            iobUnits = iobConsensus.decisionIobUnits,
            sensMgDlPerU = sens,
            pkpdRelativeActivity = cachedPkpdRuntime?.activity?.relativeActivity,
            pkpdStage = cachedPkpdRuntime?.activity?.stage,
            minBgLookback75mMgdl = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
        )
        if (naiveEbgResolution.signGuardApplied) {
            consoleLog.add(
                "🛡️ NAIVE_EBG_SIGN_GUARD: ${naiveEbgResolution.collapseReason} " +
                    "→ collapse naive ebg ${naiveEbgResolution.rawNaiveRoundedMgdl.toInt()} → ${bg.toInt()}"
            )
        }
        val naiveEventualBg = naiveEbgResolution.naiveEventualBgMgdl
        val legacyEventual = naiveEventualBg + deviation
        // Late PKPD refine of the dose snapshot (early publish already ran before RBT/V3).
        val pkpdPredTerminalBefore = minPredictedAcrossCurves(rT.predBGs) ?: pkpdPredictions.eventual
        publishDoseTerminalAuthorityAndSnapshot(
            rT = rT,
            profile = profile,
            mealData = ctx.mealData,
            pkpdEventualMgdl = pkpdPredictions.eventual,
            pkpdPredTerminalMgdl = pkpdPredTerminalBefore,
            targetBgMgdl = targetBg.toDouble(),
            stageTag = "late_pkpd",
        )
        refineRbtMergeAfterDoseSnapshot(rT)
        val decisionPrediction = checkNotNull(lastDecisionPredictionAuthority) {
            "Dose terminal snapshot publish must set lastDecisionPredictionAuthority"
        }

        val projectionInput = correctionAggressionProjectionInput(
            targetBgValue = targetBg,
            cobValue = cob.toDouble(),
            combinedDeltaValue = glucoseStatus.combinedDelta.toFloat(),
        )
        val snapForEnvelope = lastDoseTerminalSnapshot
        cachedRiskEnvelopeDecision = AimiRiskEnvelopeBuilder.buildDecision(
            bg = bg,
            delta = delta,
            predTerminal = snapForEnvelope?.minPredMgdl ?: pkpdPredictions.eventual,
            eventualTerminal = snapForEnvelope?.eventualMgdl ?: pkpdPredictions.eventual,
            pathBounds = pkpdPredictions.pathBounds,
            aapsIobUnits = iobData.iob,
            iobConsensus = iobConsensus,
            lgsThreshold = profile.lgsThreshold,
            naiveEbgSignGuardApplied = naiveEbgResolution.signGuardApplied,
            predictionAuthority = decisionPrediction,
            mealSafetyContext = buildMealSafetyContext(isExplicitAdvisorRun = false, iobData = iobData),
            mealAbsorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
            targetBgMgdl = projectionInput.targetBg,
            minBgLookback75m = projectionInput.minBgLookback75m,
            hasIndependentMealEvidence = CorrectionAggressionGate.hasIndependentMealEvidence(projectionInput),
            mealCertainty = lastMealCertainty,
        )
        consoleLog.add(AimiRiskEnvelopeBuilder.formatLogLine(cachedRiskEnvelopeDecision!!))
        reconcileSafetyRiskWithDecisionEnvelope()

        var minBgOut = minBg
        var targetBgOut = targetBg
        var maxBgOut = maxBg

        if (bg > maxBg && profile.adv_target_adjustments && !profile.temptargetSet) {
            val adjustedMinBG = round(max(80.0, minBgOut - (bg - minBgOut) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, targetBgOut - (bg - targetBgOut) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, maxBgOut - (bg - maxBgOut) / 3.0), 0)
            if (eventualBG > adjustedMinBG && legacyEventual > adjustedMinBG && minBgOut > adjustedMinBG) {
                consoleLog.add(rh.gs(ApsStrings.console_min_bg_adjusted, minBgOut, adjustedMinBG))
                minBgOut = adjustedMinBG
            } else {
                consoleLog.add(rh.gs(ApsStrings.console_min_bg_unchanged, minBgOut))
            }
            if (eventualBG > adjustedTargetBG && legacyEventual > adjustedTargetBG && targetBgOut > adjustedTargetBG) {
                consoleLog.add(rh.gs(ApsStrings.console_target_bg_adjusted, targetBgOut, adjustedTargetBG))
                targetBgOut = adjustedTargetBG
            } else {
                consoleLog.add(rh.gs(ApsStrings.console_target_bg_unchanged, targetBgOut))
            }
            if (eventualBG > adjustedMaxBG && legacyEventual > adjustedMaxBG && maxBgOut > adjustedMaxBG) {
                consoleError.add(rh.gs(ApsStrings.console_max_bg_adjusted, maxBgOut, adjustedMaxBG))
                maxBgOut = adjustedMaxBG
            } else {
                consoleError.add(rh.gs(ApsStrings.console_max_bg_unchanged, maxBgOut))
            }
        }

        return AimiPkpdBgiDeviationAndTargetsStage(
            bgi = bgi,
            deviation = deviation,
            minBg = minBgOut,
            targetBg = targetBgOut,
            maxBg = maxBgOut,
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
    ): Float {
        val modelcal = calculateSMBFromModel(rT.reason)
        val decisionRisk = cachedRiskEnvelopeDecision
        val hypoThresholdForGuard = decisionRisk?.hypoThresholdMgdl ?: threshold
        val hypoCompositeForReason = decisionRisk?.compositeMinMgdl ?: minBgHypoComposite
        val (hypoPredSanitized, hypoEventualSanitized) = sanitizedHypoGuardPredictedEventual(
            rT = rT,
            predictedBg = predictedBg.toDouble(),
            eventualBg = eventualBg,
        )
        var isHypoBlocked = shouldBlockHypoWithHysteresis(
            bg = bg,
            predictedBg = hypoPredSanitized,
            eventualBg = hypoEventualSanitized,
            threshold = hypoThresholdForGuard,
            deltaMgdlPer5min = delta.toDouble(),
        )

        val aggression = correctionAggressionDecision
        if (isHypoBlocked && aggression?.allowRocketHypoOverride == true) {
            isHypoBlocked = false
            lastHypoBlockAt = 0L
            rT.reason.append(
                "🚀 Rocket Override (${CorrectionAggressionGate.LOG_PREFIX} ${aggression.tier.name}): Hypo Block IGNORED. "
            )
        } else if (isHypoBlocked && (delta > 5.0 || bg > targetBg + 40)) {
            consoleLog.add(
                "${CorrectionAggressionGate.LOG_PREFIX}: hypo rocket override BLOCKED " +
                    "(tier=${aggression?.tier?.name ?: "n/a"} tag=${aggression?.reasonTag ?: "n/a"})"
            )
        }

        var fallbackActive = false
        if (isHypoBlocked) {
            if (canFallbackSmbWithoutPrediction(bg, delta.toDouble(), targetBg, iob.toDouble(), profile)) {
                fallbackActive = true
            }
        }

        if (isHypoBlocked && !fallbackActive) {
            rT.reason.appendLine(
                rh.gs(
                    ApsStrings.reason_hypo_guard,
                    convertBG(hypoCompositeForReason),
                    convertBG(hypoThresholdForGuard),
                    convertBG(bg),
                    convertBG(predictedBg.toDouble()),
                    convertBG(eventualBg)
                )
            )
            this.predictedSMB = 0f
        } else {
            var finalModelSmb = modelcal

            if (fallbackActive) {
                finalModelSmb = modelcal * 0.5f
                rT.reason.appendLine(
                    "Hyper fallback active: SMB unblocked (50% damped) despite missing prediction. UAM: ${aimiFmt2(modelcal)} -> ${aimiFmt2(finalModelSmb)}"
                )
            } else {
                rT.reason.appendLine("💉 SMB (UAM): ${aimiFmt2(modelcal)} U")
            }

            when (postHypoState) {
                is PostHypoState.ReboundSuspected -> {
                    val bridgeTbr = (profile.current_basal * 2.0)
                        .coerceAtMost(profile.max_basal * 0.35)
                    finalModelSmb = 0f
                    rT.rate = bridgeTbr
                    rT.duration = 5
                    consoleLog.add(
                        "🛡️ POST_HYPO_REBOUND: SMB=0 → TBR bridge ${aimiFmt2(bridgeTbr)} U/h " +
                            "(${postHypoState.sinceMs / 60_000}min depuis BG<70, COB=${aimiFmt1(cob)}g)"
                    )
                }
                is PostHypoState.MealConfirmed -> {
                    val maxSmbPref = preferences.get(DoubleKey.OApsAIMIMaxSMB).toFloat()
                    finalModelSmb = (finalModelSmb * 0.5f).coerceAtMost(maxSmbPref * 0.5f)
                    consoleLog.add(
                        "🍽️ POST_HYPO_MEAL: SMB capped 50% → ${aimiFmt2(finalModelSmb)} U " +
                            "(COB=${aimiFmt1(cob)}g, ${postHypoState.sinceMs / 60_000}min post-hypo)"
                    )
                }
                PostHypoState.None -> { /* flux normal */ }
            }

            this.predictedSMB = finalModelSmb
        }
        return modelcal
    }

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
        lateFatRiseFlag: Boolean,
        highCarbrunTime: Long,
        threshold: Double,
        windowSinceDoseInt: Int,
        intervalsmb: Int,
        pumpCaps: PumpCaps,
        highBgOverrideUsed: Boolean,
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
        val hasPred = predictedBg > 20
        val hyperKicker = (bg > targetBg + 30 && (delta >= 0.3 || shortAvgDelta >= 0.2))

        val isMealAdvisorOneShot = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
        mealAdvisorOneShotThisTick = isMealAdvisorOneShot
        // 🍱 Exception repas : l'insuline validée par le Meal Advisor prime sur le lockout exercice/activité,
        // sauf hypo sévère (BG ≤ [SEVERE_HYPO_MEAL_OVERRIDE_MGDL]).
        val advisorMealPriority = bg > SEVERE_HYPO_MEAL_OVERRIDE_MGDL
        if (isMealAdvisorOneShot && (!exerciseInsulinLockoutActive || advisorMealPriority)) {
            preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)

            this.maxSMB = Math.max(this.maxSMB, 30.0)
            this.maxSMBHB = Math.max(this.maxSMBHB, 30.0)

            if (exerciseInsulinLockoutActive) {
                consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT: priorité repas — lockout exercice/activité contourné. MaxSMB raised to 30U.")
            } else {
                consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT: Forcing Aggression. MaxSMB raised to 30U.")
            }
            rT.reason.append("🚀 Advisor Trigger: MaxSMB Bypass Active. ")
        } else if (isMealAdvisorOneShot && exerciseInsulinLockoutActive) {
            preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)
            consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT ignoré (hypo sévère sous sport / contexte activité).")
            rT.reason.append("🚀 Advisor Trigger ignoré (hypo sévère / exercice). ")
        }

        consoleLog.add(
            String.format(
                java.util.Locale.US,
                "SMB Decision: BG=%.0f, Delta=%.1f, IOB=%.2f, HasPred=%s, HyperKicker=%s, UAM=%.2f, Proposed=%.2f",
                bg, delta, iob, hasPred, hyperKicker, modelcal, this.predictedSMB
            )
        )
        val pkpdDiaMinutesOverride: Double? = pkpdRuntime?.params?.diaHrs?.let { it * 60.0 }
        @Suppress("UNUSED_VARIABLE")
        val useLegacyDynamicsdia = pkpdDiaMinutesOverride == null

        val smbExecution = if (skipLegacySmbBlender) {
            val v3SmbUnits = (rT.insulinReq ?: 0.0).coerceAtLeast(0.0)
            rT.reason.appendLine(rh.gs(ApsStrings.reason_autodrive_v3_authoritative_blender_skipped))
            consoleLog.add(
                "AUTODRIVE_V3_AUTHORITATIVE: SMB ${aimiFmt2(v3SmbUnits)} U from V3 (legacy blender skipped)"
            )
            SmbInstructionExecutor.Result(
                predictedSmb = predictedSMB,
                basal = basal,
                finalSmb = v3SmbUnits.toFloat(),
                highBgOverrideUsed = highBgOverrideUsed,
                newSmbInterval = intervalsmb,
            )
        } else {
            executeSmbInstruction(
                bg = bg, delta = delta, iob = iob, basalaimi = basalaimi, basal = basal,
                honeymoon = honeymoon, hourOfDay = hourOfDay,
                mealTime = mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
                dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = snackTime,
                sens = sens, tp = tp.toFloat(), variableSensitivity = variableSensitivity,
                target_bg = targetBg, predictedBg = predictedBg, eventualBG = eventualBG,
                isMealAdvisorOneShot = isMealAdvisorOneShot, mealData = ctx.mealData,
                pkpdRuntime = pkpdRuntime, sportTime = sportTime, lateFatRiseFlag = lateFatRiseFlag,
                highCarbrunTime = highCarbrunTime, threshold = threshold,
                currentTime = ctx.currentTime, windowSinceDoseInt = windowSinceDoseInt,
                intervalsmb = intervalsmb, insulinStep = pumpCaps.bolusStep.toFloat(),
                highBgOverrideUsed = highBgOverrideUsed, cob = cob,
                pkpdDiaMinutesOverride = pkpdDiaMinutesOverride,
                profile = profile, rT = rT,
                combinedDeltaLocal = combinedDelta, glucoseStatusLocal = glucoseStatus,
                pumpAgeDaysLocal = pumpAgeDays, modelcalLocal = modelcal.toDouble(),
                profileCurrentBasalLocal = profileCurrentBasal,
                isConfirmedHighRise = isConfirmedHighRiseLocal,
                exerciseInsulinLockout = exerciseInsulinLockoutActive,
                minBgLookbackMgdl = minBgLookbackMgdl,
            )
        }

        return AimiSmbAdvisorLogAndExecutionStage(
            smbExecution = smbExecution,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
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
        predictedSMB = smbExecution.predictedSmb
        assignLocalBasalFromExecution(smbExecution.basal)
        highBgOverrideUsed = smbExecution.highBgOverrideUsed
        smbExecution.newSmbInterval?.let { intervalsmb = it }
        val smbToGive = smbExecution.finalSmb
        if (lastSmbBindingTraceDraft.originOwner == "NONE") {
            lastSmbBindingTraceDraft = lastSmbBindingTraceDraft.copy(
                originOwner = "GlobalAIMI",
                modelOutputU = predictedSMB.toDouble(),
                maxSmbU = maxSMB,
                maxSmbHighBgU = maxSMBHB,
                iobHeadroomU = (maxIob - iob).coerceAtLeast(0.0),
            ).appendStage(
                "SMB_EXECUTOR",
                predictedSMB.toDouble(),
                smbToGive.toDouble(),
                phase = "GLOBAL_AIMI_PROPOSAL",
                kind = "PROPOSAL",
            )
        }
        consoleLog.add(
            String.format(
                java.util.Locale.US,
                "💉 SMB result: raw=%.2f -> final=%.2f",
                predictedSMB,
                smbToGive,
            )
        )
        return smbToGive
    }

    /**
     * PKPD absorption guard (relief + meal debridage maxIOB), endo SMB dampen, red carpet vs [capSmbDose], cap reason line.
     * Mutates [rT.reason], [intervalsmb] via returned value; reads [endoSmbMult], [maxSMB]/[maxSMBHB], [iob], [maxIob] membres.
     */
    private data class AimiPkpdGuardEndoRedCarpetSmbStage(
        val smbToGive: Float,
        val intervalsmb: Int,
    )

    @SuppressLint("DefaultLocale")
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
    ): AimiPkpdGuardEndoRedCarpetSmbStage {
        var smbToGiveLocal = smbToGive
        var intervalsmbLocal = intervalsmb

        val anyMealModeForGuard = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        val isAggressivePriorityContext = isMealAdvisorOneShot || anyMealModeForGuard || isConfirmedHighRiseLocal
        val pkpdReliefEnabled = preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
        val redCarpetRestoreThresholdPref = preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold).coerceIn(0.50, 0.95).toFloat()
        val currentMaxSmb = AimiLegacySmbCapMath.currentMaxSmb(
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            bg = bg,
            honeymoon = honeymoon,
            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
            mealLunchDinnerOrHc = mealTime || lunchTime || dinnerTime || highCarbTime,
            maxSmb = maxSMB,
            maxSmbHb = maxSMBHB,
        )
        val iobRelief = AimiLegacySmbCapMath.iobRelief(
            pkpdReliefEnabled = pkpdReliefEnabled,
            isAggressivePriorityContext = isAggressivePriorityContext,
            maxIob = this.maxIob,
            priorityMaxIobFactor = preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor).coerceIn(1.0, 1.6),
            priorityMaxIobExtraU = preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU).coerceIn(0.0, 5.0),
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            predictedBg = predictedBg.toDouble(),
            eventualBg = eventualBG,
        )
        val effectiveMaxIobForPriority = iobRelief.effectiveMaxIobForPriority
        val effectiveMaxIobForDebridage = iobRelief.effectiveMaxIobForDebridage
        val pkpdGuardInput = smbToGiveLocal
        val pkpdGuardApply = applyPkpdAbsorptionGuardOncePerTick(
            smbIn = smbToGiveLocal,
            pkpdRuntime = pkpdRuntime,
            windowSinceLastDoseMin = windowSinceLastPkpdDoseMin(windowSinceDoseInt),
            anyMealModeForGuard = anyMealModeForGuard,
            isConfirmedHighRise = isConfirmedHighRiseLocal,
            mealAdvisorOneShot = isMealAdvisorOneShot,
            reason = rT.reason,
            logChannel = PkpdGuardLogChannel.PIPELINE,
        )
        smbToGiveLocal = pkpdGuardApply.smbOut
        lastSmbBindingTraceDraft = if (pkpdGuardApply.skippedDuplicate) {
            lastSmbBindingTraceDraft.appendStage(
                "PKPD_GUARD_SKIPPED_DUPLICATE",
                pkpdGuardInput.toDouble(),
                pkpdGuardInput.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "OBSERVATION",
            )
        } else {
            lastSmbBindingTraceDraft.copy(
                pkpdBeforeU = lastSmbBindingTraceDraft.pkpdBeforeU ?: pkpdGuardInput.toDouble(),
                pkpdAfterU = lastSmbBindingTraceDraft.pkpdAfterU ?: smbToGiveLocal.toDouble(),
            ).appendStage(
                "PKPD_GUARD",
                pkpdGuardInput.toDouble(),
                smbToGiveLocal.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "GUARD",
            )
        }
        intervalsmbLocal = intervalsmb
        if (pkpdGuardApply.skippedDuplicate) {
            consoleLog.add("PKPD_GUARD_SKIP: already applied this tick (e.g. Autodrive V3 finalize)")
        } else if (pkpdGuardApply.multiplicationApplied) {
            pkpdGuardApply.guard?.let { pkpdGuard ->
                rT.reason.append(" | ${pkpdGuard.reason} x${aimiFmt2(pkpdGuardApply.effectiveFactor)}")
            }
        }
        iobRelief.logs.forEach { consoleLog.add(it) }

        if (endoSmbMult < 1.0) {
            val beforeEndo = smbToGiveLocal
            smbToGiveLocal = (smbToGiveLocal * endoSmbMult.toFloat()).coerceAtLeast(0f)
            lastSmbBindingTraceDraft = lastSmbBindingTraceDraft.appendStage(
                "ENDO_DAMPEN",
                beforeEndo.toDouble(),
                smbToGiveLocal.toDouble(),
                phase = "LEGACY_GUARD",
                kind = "DAMPEN",
            )
            if (smbToGiveLocal < beforeEndo) {
                consoleLog.add("SMB_ENDO_DAMPEN: ${aimiFmt2(beforeEndo)}U → ${aimiFmt2(smbToGiveLocal)}U (x${aimiFmt2(endoSmbMult)})")
                rT.reason.append(" | EndoDampen x${aimiFmt2(endoSmbMult)}")
            }
        }

        val beforeCap = smbToGiveLocal

        val isExplicitAction = isMealAdvisorOneShot
        val implicitMealCorrection = resolveMealCorrectionContext(
            mealData = ctx.mealData,
            bgMgdl = bg,
            deltaMgdlPer5 = delta.toDouble(),
            shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
        )
        val proposedUnits = smbExecution.finalSmb.toFloat()

        // F1-bis : cette copie V3 ignorait le frein IOB-surveillance que le site legacy
        // (finalizeAndCapSMB) consulte déjà. Même évaluation (fonction pure), même sémantique.
        val stackingEvalV3 = InsulinStackingStance.evaluate(
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            targetBg = targetBg,
            iob = iob.toDouble(),
            maxIob = this.maxIob,
            eventualBg = authoritativeEventualBg(eventualBG).takeIf { it > 1.0 && it.isFinite() },
            minPredBg = minPredictedBgForRbtWiring(
                authoritativeMinPredBg(rT, minPredictedAcrossCurves(rT.predBGs)),
            ),
            trajectoryEnergy = rT.trajectoryEnergy,
            isExplicitUserAction = isExplicitAction,
            enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
            mealPriorityContext = isAggressivePriorityContext,
            endogenousCounterRegulatory = lastPhysiologicalPhaseOutput?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY,
            mealAbsorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
            mealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime,
        )
        val carpet = AimiLegacySmbCapMath.redCarpetOrCap(
            smbAfterGuards = smbToGiveLocal,
            proposedUnits = proposedUnits,
            finalSmb = smbExecution.finalSmb.toDouble(),
            isExplicitAction = isExplicitAction,
            anyMealMode = anyMealModeForGuard,
            redCarpetEligible = implicitMealCorrection.redCarpetEligible,
            mealSummary = implicitMealCorrection.summary(),
            isConfirmedHighRise = isConfirmedHighRiseLocal,
            mealCob = ctx.mealData.mealCOB,
            delta = delta.toDouble(),
            bg = bg,
            shortAvgDelta = shortAvgDelta.toDouble(),
            pkpdReliefEnabled = pkpdReliefEnabled,
            isAggressivePriorityContext = isAggressivePriorityContext,
            restoreThresholdPref = redCarpetRestoreThresholdPref,
            criticalSafetyZeroed = criticalSafetyZeroedThisTick,
            suppressRedCarpet = stackingEvalV3.suppressRedCarpetRestore,
            suppressSummary = stackingEvalV3.summary,
            currentMaxSmb = currentMaxSmb,
            maxSmbHb = maxSMBHB,
            effectiveMaxIob = effectiveMaxIobForDebridage,
            iobForCap = iob.toDouble(),
            memberIob = this.iob.toDouble(),
        )
        smbToGiveLocal = carpet.units
        carpet.logs.forEach { consoleLog.add(it) }
        carpet.reasonCap?.let { rT.reason.append(it) }
        lastSmbBindingTraceDraft = lastSmbBindingTraceDraft.copy(
            safetyNetBaseLimitU = lastSmbBindingTraceDraft.safetyNetBaseLimitU ?: currentMaxSmb,
            redCarpetBeforeU = lastSmbBindingTraceDraft.redCarpetBeforeU ?: beforeCap.toDouble(),
            redCarpetAfterU = lastSmbBindingTraceDraft.redCarpetAfterU ?: smbToGiveLocal.toDouble(),
        ).appendStage(
            "LEGACY_RED_CARPET_MAX_SMB_IOB",
            beforeCap.toDouble(),
            smbToGiveLocal.toDouble(),
            currentMaxSmb,
            phase = "LEGACY_GUARD",
            kind = "COMPOSITE",
        )

        return AimiPkpdGuardEndoRedCarpetSmbStage(
            smbToGive = smbToGiveLocal,
            intervalsmb = intervalsmbLocal,
        )
    }

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
    private sealed class AimiMealHyperBasalBoostOutcome {
        data class ContinueWithOptionalRate(val rate: Double?) : AimiMealHyperBasalBoostOutcome()
        data class CompleteWithTempBasal(val rT: RT) : AimiMealHyperBasalBoostOutcome()
    }

    @SuppressLint("DefaultLocale")
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
        val mealModesMaxBasal = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val aggressionDecision = correctionAggressionDecision
        return when {
            isMealAdvisorOneShot -> {
                val safeMax = if (mealModesMaxBasal > 0.1) mealModesMaxBasal else profile.max_basal
                val rawRate = calculateRate(basal, safeMax, 1.3, "Meal Advisor Trigger (One-Shot)", ctx.currentTemp, rT, overrideSafety = true)
                // REBOUND_GUARD cap: a recognized post-hypo context must never receive meal_modes_MaxBasal
                // even when a meal/advisor flag is active simultaneously — same principle as V3/V2/Harmonia paths.
                val boostedRate = capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "MealAdvisorOneShot")
                AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(setTempBasal(boostedRate, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0))
            }
            snackTime && snackrunTime in 0..30 && delta < 15 -> {
                val rawRate = calculateRate(basal, profileCurrentBasal, 4.0, "AI Force basal because Snack Time $snackrunTime.", ctx.currentTemp, rT, overrideSafety = true)
                val boostedRate = capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "SnackTimeBoost")
                AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(setTempBasal(boostedRate, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0))
            }

            (mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime) && (listOf(mealruntime, lunchruntime, dinnerruntime, highCarbrunTime, bfastruntime).maxOrNull() ?: 0) in 0..30 -> {
                val safeMax = if (mealModesMaxBasal > 0.1) mealModesMaxBasal else profileCurrentBasal * 5.0
                val rawRate = calculateRate(basal, safeMax, 1.0, "Meal Boost 30min (Force MaxBasal)", ctx.currentTemp, rT, overrideSafety = true)
                val boostedRate = capBasalRateForCorrectionAggression(rawRate, profileCurrentBasal, "MealBoost30m")
                AimiMealHyperBasalBoostOutcome.CompleteWithTempBasal(setTempBasal(boostedRate, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = true, adaptiveMultiplier = 1.0))
            }

            (mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime || snackTime || (timeSinceEstimateMin <= 120 && estimatedCarbs > 10.0)) -> {
                val runTime = listOf(mealruntime, lunchruntime, dinnerruntime, highCarbrunTime, bfastruntime, snackrunTime).maxOrNull() ?: timeSinceEstimateMin.toInt()
                val target = targetBg
                val rocketStart = delta > 5.0f || bg > targetBg + 40
                val safeMax = if (rocketStart) profile.max_basal else if (mealModesMaxBasal > 0) mealModesMaxBasal else profileCurrentBasal * 2.0

                val boostedRate = adjustBasalForMealHyper(
                    suggestedBasalUph = profileCurrentBasal,
                    bg = bg,
                    targetBg = target,
                    delta = delta.toDouble(),
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    isMealModeActive = true,
                    minutesSinceMealStart = runTime.toInt(),
                    mealMaxBasalUph = safeMax
                )

                val optionalRate = if (boostedRate > profileCurrentBasal * 1.05) {
                    calculateRate(basal, profileCurrentBasal, boostedRate / profileCurrentBasal, "Post-Meal Boost active ($runTime m)", ctx.currentTemp, rT)
                } else {
                    null
                }
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
            }

            aggressionDecision?.tier == CorrectionAggressionGate.Tier.REBOUND_GUARD &&
                aggressionDecision.allowGlobalHyperKicker == false &&
                bg < targetBg + CorrectionAggressionGate.REBOUND_BG_MARGIN_MGDL &&
                delta >= 0.0f -> {
                val bridgeRate = calculateRate(
                    basal,
                    profileCurrentBasal,
                    1.5,
                    "${CorrectionAggressionGate.LOG_PREFIX}: post-hypo TBR bridge (REBOUND_GUARD)",
                    ctx.currentTemp,
                    rT,
                )
                consoleLog.add(
                    "${CorrectionAggressionGate.LOG_PREFIX}: rebound basal bridge " +
                        "${aimiFmt2(bridgeRate)} U/h (no Global Hyper Kicker)"
                )
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
                    if (bridgeRate > profileCurrentBasal * 1.05) bridgeRate else null
                )
            }

            lastMealAbsorptionOutput?.phase?.isActive == true &&
                (
                    lastMealAbsorptionOutput?.phase == MealAbsorptionPhase.FIRST_WAVE ||
                        lastMealAbsorptionOutput?.phase == MealAbsorptionPhase.SECOND_WAVE ||
                        lastMealAbsorptionOutput?.phase == MealAbsorptionPhase.INTER_WAVE ||
                        lastMealAbsorptionOutput?.phase == MealAbsorptionPhase.PEAK_CORRECTION
                    ) -> {
                val runTime: Int = MealAbsorptionMemory.lastActiveAtMs.takeIf { it > 0L }?.let {
                    ((dateUtil.now() - it) / 60_000L).toInt().coerceAtLeast(0)
                } ?: listOf(mealruntime, lunchruntime, dinnerruntime, highCarbrunTime, bfastruntime, snackrunTime)
                    .maxOrNull()?.toInt() ?: 0
                val target = targetBg
                val rocketStart = delta > 5.0f || bg > targetBg + 40
                val safeMax = if (rocketStart) profile.max_basal else if (mealModesMaxBasal > 0) mealModesMaxBasal else profileCurrentBasal * 2.0
                val boostedRate = adjustBasalForMealHyper(
                    suggestedBasalUph = profileCurrentBasal,
                    bg = bg,
                    targetBg = target,
                    delta = delta.toDouble(),
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    isMealModeActive = true,
                    minutesSinceMealStart = runTime,
                    mealMaxBasalUph = safeMax,
                )
                val optionalRate = if (boostedRate > profileCurrentBasal * 1.05) {
                    calculateRate(
                        basal,
                        profileCurrentBasal,
                        boostedRate / profileCurrentBasal,
                        "Meal absorption ${lastMealAbsorptionOutput?.phase?.name} ($runTime m)",
                        ctx.currentTemp,
                        rT,
                    )
                } else {
                    null
                }
                consoleLog.add(
                    "🍽️ MEAL_ABSORPTION_BASAL: phase=${lastMealAbsorptionOutput?.phase?.name} " +
                        "rate=${optionalRate?.let { r -> aimiFmt2(r) } ?: "skip"}",
                )
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
            }

            lastPhysiologicalPhaseOutput?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY &&
                cob < 1.0f &&
                estimatedCarbs < 1.0 -> {
                val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
                val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
                val bridge = EndogenousBasalBridgePolicy.computeBridgeRateUph(
                    bgMgdl = bg.toDouble(),
                    targetBgMgdl = targetBg,
                    isfMgdlPerU = profile.sens,
                    profileBasalUph = profileCurrentBasal,
                    maxBasalUph = safeMax,
                )
                val optionalRate = bridge?.let {
                    calculateRate(
                        basal,
                        profileCurrentBasal,
                        it / profileCurrentBasal,
                        "Endogenous basal bridge (R_HGP)",
                        ctx.currentTemp,
                        rT,
                    )
                }
                consoleLog.add(
                    "🌅 ENDOGENOUS_BRIDGE: rate=${optionalRate?.let { r -> aimiFmt2(r) } ?: "skip"} " +
                        "ISF=${aimiFmt1(profile.sens)} profileBasal=${aimiFmt2(profileCurrentBasal)}",
                )
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
            }

            run {
                val aggression = correctionAggressionDecision
                val htrActive = lastHyperTrajectoryRelease?.active == true
                val endogenousActive =
                    lastPhysiologicalPhaseOutput?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
                val allowHyper = aggression?.allowGlobalHyperKicker == true &&
                    (delta >= 0.3 || shortAvgDelta >= 0.2) &&
                    !htrActive &&
                    !endogenousActive
                if (htrActive && aggression?.allowGlobalHyperKicker == true) {
                    consoleLog.add("🚀 HTR: Global Hyper Kicker skipped (trajectory SMB release active)")
                }
                allowHyper
            } -> {
                val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
                val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
                val scaleCap = correctionAggressionDecision?.maxBasalScaleCap ?: 10.0

                val boostedRate = adjustBasalForGeneralHyper(
                    suggestedBasalUph = profileCurrentBasal,
                    bg = bg,
                    targetBg = targetBg,
                    delta = delta.toDouble(),
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    maxBasalConfig = safeMax,
                    maxScaleCap = scaleCap,
                )

                val tag = correctionAggressionDecision?.tier?.name ?: "n/a"
                val optionalRate = if (boostedRate > profileCurrentBasal * 1.1) {
                    calculateRate(
                        basal,
                        profileCurrentBasal,
                        boostedRate / profileCurrentBasal,
                        "Global Hyper Kicker ($tag / ${CorrectionAggressionGate.LOG_PREFIX})",
                        ctx.currentTemp,
                        rT,
                        overrideSafety = true,
                    )
                } else {
                    null
                }
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(optionalRate)
            }

            cachedBasalFirstActive && !cachedIsFragileBg && bg > targetBg -> {
                val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
                val safeMax = if (autodriveMaxBasal > 0.1) autodriveMaxBasal else profile.max_basal
                AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
                    calculateRate(basal, safeMax, 1.4, "Prudent Compensation (SMB blocked)", ctx.currentTemp, rT)
                )
            }

            fastingTime -> AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(
                calculateRate(profileCurrentBasal, profileCurrentBasal, delta.coerceAtLeast(0.0f).toDouble(), "AI Force basal because fastingTime", ctx.currentTemp, rT)
            )
            else -> AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate(null)
        }
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
            consoleLog.add("BOOST_BASAL_APPLIED source=${basalBoostSource ?: "Unknown"} rate=${aimiFmt2(overlayRate)}U/h")
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
            lastWCycleBelief,
            EndocrineAmpAxis.IC,
        )
        val adjustedCR = profile.carb_ratio / icMult

        val csf = sens / adjustedCR
        consoleError.add(rh.gs(ApsStrings.console_profile_sens, baseSensitivity, sens, csf))

        val maxCarbAbsorptionRate = 30
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            consoleError.add(rh.gs(ApsStrings.console_limiting_carb_impact, ci, maxCI, maxCarbAbsorptionRate))
            ci = maxCI.toFloat()
        }
        var remainingCATimeMin = 2.0
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        var remainingCATime = remainingCATimeMin
        val totalCI = max(0.0, ci / 5 * 60 * remainingCATime / 2)
        val totalCA = totalCI / csf
        val remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, ctx.mealData.mealCOB - totalCA)
        remainingCarbs = min(remainingCarbsCap.toDouble(), remainingCarbs)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        val slopeFromMaxDeviation = ctx.mealData.slopeFromMaxDeviation
        val slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)

        val ciCurrentImpact = round((minDelta - bgi), 1)
        val cid: Double = if (ciCurrentImpact == 0.0) {
            0.0
        } else {
            min(remainingCATime * 60 / 5 / 2, Math.max(0.0, ctx.mealData.mealCOB * csf / ciCurrentImpact))
        }
        consoleError.add(rh.gs(ApsStrings.console_carb_impact, ciCurrentImpact, round(cid * 5 / 60 * 2, 1), round(remainingCIpeak, 1)))

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
        val thresholdBG = 70.0
        val carbsRequired = CarbsAdvisor.estimateRequiredCarbs(
            bg = bg,
            targetBG = targetBg.toDouble(),
            slope = slopeFromDeviations,
            iob = iob.toDouble(),
            csf = csf,
            isf = sens,
            cob = cob.toDouble()
        )
        val minutesAboveThreshold = HypoTools.calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
        if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !lunchTime && !dinnerTime && !bfastTime && !highCarbTime && !mealTime) {
            rT.carbsReq = carbsRequired
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append(rh.gs(ApsStrings.reason_additional_carbs, carbsRequired, minutesAboveThreshold))
        }

        val forcedBasalmealmodes = preferences.get(DoubleKey.meal_modes_MaxBasal)
        val forcedBasal = preferences.get(DoubleKey.autodriveMaxBasal)

        val mealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime

        val enableSMB = enablesmb(
            profile,
            ctx.microBolusAllowed,
            ctx.mealData,
            targetBgSchedule,
            mealModeActive,
            bg,
            delta.toDouble(),
            eventualBG,
            combinedDelta.toDouble()
        )

        mealModeSmbReason?.let { reason(rT, it) }

        rT.COB = ctx.mealData.mealCOB
        rT.IOB = iobData.iob
        rT.reason.append(
            "COB: ${round(ctx.mealData.mealCOB, 1).withoutZeros()}, Dev: ${convertBG(deviation.toDouble())}, BGI: ${convertBG(bgi)}, ISF: ${convertBG(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convertBG(targetBgSchedule)}${
                OrefPredictionReasonSuffix.build(rT) { v -> convertBG(v) }
            } \uD83D\uDCD2 "
        )
        val zeroSinceMin = BasalHistoryUtils.historyProvider.zeroBasalDurationMinutes(2)
        val minutesSinceLastChange = BasalHistoryUtils.historyProvider.minutesSinceLastChange()
        this.zeroBasalAccumulatedMinutes = zeroSinceMin
        if (eventualBG >= maxBgSchedule) {
            rT.reason.append(rh.gs(ApsStrings.reason_eventual_bg, convertBG(eventualBG), convertBG(maxBgSchedule)))
        }
        val tdd24h = tddCalculator.averageTDD(
            resolveTdd1DaySparseForAverage()
        )?.data?.totalAmount ?: 0.0
        val tirInHypo = tirCalculator.averageTIR(
            resolveTir65180ForAverage()
        )?.belowPct() ?: 0.0
        val safetyDecision = safetyAdjustment(
            currentBG = glucoseStatus.glucose.toFloat(),
            predictedBG = eventualBG.toFloat(),
            bgHistory = glucoseStatusCalculatorAimi.getRecentGlucose(),
            combinedDelta = combinedDelta.toFloat(),
            iob = iob,
            maxIob = profile.max_iob.toFloat(),
            tdd24Hrs = tdd24h.toFloat(),
            tddPerHour = tddPerHour,
            tirInhypo = tirInHypo.toFloat(),
            targetBG = profile.target_bg.toFloat(),
            zeroBasalDurationMinutes = windowSinceDoseInt
        )
        rT.isHypoRisk = safetyDecision.isHypoRisk

        if (safetyDecision.isHypoRisk) {
            notificationManager.post(
                id = app.aaps.core.interfaces.notifications.NotificationId.HYPO_RISK_ALARM,
                text = rh.gs(ApsStrings.hypo_risk_notification_text)
            )
        }

        return AimiCarbsAdvisorEnableSmbSafetyStage(
            forcedBasalmealmodes = forcedBasalmealmodes,
            forcedBasal = forcedBasal,
            enableSMB = enableSMB,
            mealModeActive = mealModeActive,
            zeroSinceMin = zeroSinceMin,
            minutesSinceLastChange = minutesSinceLastChange,
            safetyDecision = safetyDecision,
        )
    }

    private sealed class AimiCarbsAdvisorHardHypoBasalGateResult {
        data class ReturnZeroTempBasal(val rT: RT) : AimiCarbsAdvisorHardHypoBasalGateResult()
        data class Continue(val stage: AimiCarbsAdvisorEnableSmbSafetyStage) : AimiCarbsAdvisorHardHypoBasalGateResult()
    }

    /**
     * [runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage] puis, si [SafetyDecision.stopBasal], **TBR 0 % 30 min** (même [setTempBasal] / [adaptiveMult] qu’avant).
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
                setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, adaptiveMultiplier = adaptiveMult),
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
     * Ordre et effets identiques au bloc historique ; [mealModeRuntimeToNullableMinutes] pour les runtimes nullable.
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
        val (isMealActive, runtimeMinLabel, runtimeMinValue) = when {
            mealTime -> Triple(true, "meal", mealModeRuntimeToNullableMinutes(mealruntime))
            bfastTime -> Triple(true, "bfast", mealModeRuntimeToNullableMinutes(bfastruntime))
            lunchTime -> Triple(true, "lunch", mealModeRuntimeToNullableMinutes(lunchruntime))
            dinnerTime -> Triple(true, "dinner", mealModeRuntimeToNullableMinutes(dinnerruntime))
            highCarbTime -> Triple(true, "highcarb", mealModeRuntimeToNullableMinutes(highCarbrunTime))
            else -> Triple(false, "", Int.MAX_VALUE)
        }

        if (isMealActive && runtimeMinValue in 0..30) {
            val forced = forcedBasalmealmodes.coerceAtLeast(0.05)
            val alreadyForced = abs(ctx.currentTemp.rate - forced) < 0.05 && ctx.currentTemp.duration >= 25
            if (!alreadyForced) {
                rT.reason.append(
                    rh.gs(
                        ApsStrings.meal_mode_first_30,
                        "$runtimeMinLabel($runtimeMinValue)",
                        forced
                    )
                )
                return AimiPostSafetyMealNgrStageResult.EarlyTempBasal(
                    setTempBasal(
                        forced, 30, profile, rT, ctx.currentTemp,
                        overrideSafetyLimits = true,
                        adaptiveMultiplier = adaptiveMult
                    )
                )
            }
        }

        val systemTime = ctx.currentTime
        val iobTotal = ctx.iobDataArray[0]
        val ngrResult = nightGrowthResistanceMode.evaluate(
            now = KotlinInstant.fromEpochMilliseconds(systemTime),
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            eventualBG = eventualBG,
            targetBG = targetBgSchedule,
            iob = iobTotal.iob,
            cob = ctx.mealData.mealCOB,
            react = bg,
            isMealActive = isMealActive,
            config = ngrConfig
        )
        if (ngrResult.reason.isNotEmpty()) {
            rT.reason.appendLine(ngrResult.reason)
            consoleLog.add(ngrResult.reason)
        }
        val lowTempTarget = profile.temptargetSet && targetBgSchedule <= profile.target_bg
        var maxIobLimit = maxIobLimitIn
        val originalMaxIobLimit = maxIobLimit
        if (!lowTempTarget && ngrResult.extraIOBHeadroomU > 0.0) {
            val slotBudget = ngrConfig.extraIobPer30Min * ngrConfig.headroomSlotCap
            val absoluteMaxIob = preferences.get(DoubleKey.ApsSmbMaxIob) + slotBudget
            val candidate = maxIobLimit + ngrResult.extraIOBHeadroomU
            val updatedLimit = min(candidate, absoluteMaxIob)
            if (updatedLimit > originalMaxIobLimit + 0.01) {
                maxIobLimit = updatedLimit
                this.maxIob = maxIobLimit
                val headroomMessage = rh.gs(
                    ApsStrings.oaps_aimi_ngr_headroom,
                    round(maxIobLimit - originalMaxIobLimit, 2),
                    round(maxIobLimit, 2)
                )
                rT.reason.appendLine(headroomMessage)
                consoleLog.add(headroomMessage)
            }
        }
        this.maxIob = maxIobLimit
        val safeBgThreshold = max(110.0, targetBgSchedule)
        var basal = basalIn
        val originalBasal = basal
        val shouldApplyBasalBoost = ngrResult.basalMultiplier > 1.0001 && !lowTempTarget && delta > 0 && shortAvgDelta > 0 && bg > targetBgSchedule
        if (shouldApplyBasalBoost && originalBasal > 0.0) {
            val boostedBasal = roundBasal((originalBasal * ngrResult.basalMultiplier).coerceAtLeast(0.05))
            if (boostedBasal > originalBasal + 0.01) {
                basal = boostedBasal
                val basalMessage = rh.gs(
                    ApsStrings.oaps_aimi_ngr_basal_applied,
                    boostedBasal / originalBasal,
                    round(boostedBasal, 2)
                )
                rT.reason.appendLine(basalMessage)
                consoleLog.add(basalMessage)
            }
        }
        var smbToGive = smbToGiveIn
        val originalSmb = smbToGive.toDouble()
        val shouldApplySmbBoost = ngrResult.smbMultiplier > 1.0001 && !lowTempTarget && safetyDecision.bolusFactor >= 1.0 && eventualBG > targetBgSchedule && delta > 0 && bg >= safeBgThreshold
        if (shouldApplySmbBoost && originalSmb > 0.0) {
            val boosted = originalSmb * ngrResult.smbMultiplier
            val smbClamp = min(ngrConfig.maxSMBClampU, maxSMB)
            val finalSmb = boosted.coerceAtMost(smbClamp)
            val appliedMultiplier = finalSmb / originalSmb
            if (appliedMultiplier > 1.0001) {
                smbToGive = finalSmb.toFloat()
                val smbMessage = rh.gs(
                    ApsStrings.oaps_aimi_ngr_smb_applied,
                    appliedMultiplier,
                    round(finalSmb, 3),
                    round(smbClamp, 3)
                )
                rT.reason.appendLine(smbMessage)
                consoleLog.add(smbMessage)
            }
        }
        return AimiPostSafetyMealNgrStageResult.Continue(
            isMealActive = isMealActive,
            runtimeMinValue = runtimeMinValue,
            maxIobLimit = maxIobLimit,
            basal = basal,
            smbToGive = smbToGive,
        )
    }

    private sealed class AimiCoreDecisionMaxIobGateResult {
        data class ReturnTempBasal(val rt: RT) : AimiCoreDecisionMaxIobGateResult()
        data class ContinueSMBPath(
            val allowMealHighIob: Boolean,
            val mealHighIobDamping: Double,
        ) : AimiCoreDecisionMaxIobGateResult()
    }

    /**
     * Repas-montée IOB relax ([computeMealHighIobDecision]) → phase CORE_DECISION → si IOB > plafond et pas relax,
     * même branche TBR/comparateur/log MAX_IOB qu’historiquement ; sinon retourne flags pour le chemin SMB/basal aval.
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
        val mealHighIobDecision = computeMealHighIobDecision(
            mealModeActive,
            bg,
            delta.toDouble(),
            eventualBG,
            targetBgSchedule,
            loopIob,
            maxIobLimit
        )
        val allowMealHighIob = mealHighIobDecision.relax
        val mealHighIobDamping = mealHighIobDecision.damping
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.CORE_DECISION, hormonitorStudyExporter)

        if (loopIob > maxIobLimit && !allowMealHighIob) {
            rT.reason.append(rh.gs(ApsStrings.reason_iob_max, round(loopIob, 2), round(maxIobLimit, 2)))
            val finalResult = if (delta < 0) {
                val floorRate = applyBasalFloor(
                    0.0,
                    profile.current_basal,
                    safetyDecision,
                    cachedActivityContext ?: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext(),
                    bg,
                    delta.toDouble(),
                    ctx.glucoseStatus.shortAvgDelta.toDouble(),
                    eventualBG.toDouble(),
                    mealModeActive,
                    HypoThresholdMath.getLgsThresholdSafe(profile)
                )

                if (floorRate > 0.0) {
                    rT.reason.append(rh.gs(ApsStrings.reason_bg_dropping_floor, delta, floorRate))
                    setTempBasal(floorRate, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, adaptiveMultiplier = adaptiveMult)
                } else {
                    rT.reason.append(rh.gs(ApsStrings.reason_bg_dropping, delta))
                    setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, adaptiveMultiplier = adaptiveMult)
                }
            } else if (ctx.currentTemp.duration > 15 && (roundBasal(basal) == roundBasal(ctx.currentTemp.rate))) {
                rT.reason.append(", temp ${ctx.currentTemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                rT
            } else {
                val safeBasal = applyBasalFloor(
                    basal,
                    profile.current_basal,
                    safetyDecision,
                    cachedActivityContext ?: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext(),
                    bg,
                    delta.toDouble(),
                    ctx.glucoseStatus.shortAvgDelta.toDouble(),
                    eventualBG.toDouble(),
                    mealModeActive,
                    HypoThresholdMath.getLgsThresholdSafe(profile)
                )
                rT.reason.append(rh.gs(ApsStrings.reason_set_temp_basal, round(safeBasal, 2)))
                setTempBasal(safeBasal, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, adaptiveMultiplier = adaptiveMult)
            }
            comparator.compare(
                aimiResult = finalResult,
                glucoseStatus = ctx.glucoseStatus,
                currentTemp = ctx.currentTemp,
                iobData = ctx.iobDataArray,
                profileAimi = originalProfile,
                autosens = ctx.autosensData,
                mealData = ctx.mealData,
                microBolusAllowed = ctx.microBolusAllowed,
                currentTime = ctx.currentTime,
                flatBGsDetected = flatBGsDetected,
                dynIsfMode = ctx.dynIsfMode
            )
            logDecisionFinal("MAX_IOB", finalResult, bg, delta)
            return AimiCoreDecisionMaxIobGateResult.ReturnTempBasal(finalResult)
        }
        return AimiCoreDecisionMaxIobGateResult.ContinueSMBPath(
            allowMealHighIob = allowMealHighIob,
            mealHighIobDamping = mealHighIobDamping,
        )
    }

    /**
     * Chemin **insulinReq** après le gate MAX_IOB : clamp activité (champs instance **`activityProtectionMode`** /
     * **`activityStateIntense`** + **`maxSMB`**), atténuation repas-IOB élevé, **`safetyDecision.bolusFactor`**,
     * journal SMB / intervalle, **`finalizeAndCapSMB`** si éligible.
     *
     * **Downstream** : seul **`rT.insulinReq`** (et raison) est consommé plus bas ; pas de `return` local.
     * **`basalBoostApplied`** / **`basalBoostSource`** : `val` du tick (overlay basal), passés explicitement — pas des membres.
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
        var insulinReq = smbToGive.toDouble()

        if (activityProtectionMode || activityStateIntense) {
            val safetyMax = maxSMB * 0.5
            if (insulinReq > safetyMax) {
                insulinReq = safetyMax
                rT.reason.append(rh.gs(ApsStrings.reason_activity_cap, safetyMax))
                consoleLog.add("SMB capped by Activity/Recovery (Limit: ${aimiFmt2(safetyMax)})")
            }
        }

        if (allowMealHighIob) {
            insulinReq *= mealHighIobDamping
            rT.reason.append(
                rh.gs(
                    ApsStrings.reason_meal_high_iob_relaxed,
                    round(iobTotal.iob, 2),
                    round(maxIobLimit, 2),
                    (mealHighIobDamping * 100).roundToInt()
                )
            )
        }

        insulinReq = insulinReq * safetyDecision.bolusFactor
        insulinReq = round(insulinReq, 3)
        rT.insulinReq = insulinReq
        val lastBolusAge = round((systemTime - iobTotal.lastBolusTime) / 60000.0, 1)

        if (basalBoostApplied) {
            consoleLog.add("SMB_FLOW_CONTINUES afterBasalBoost=true source=${basalBoostSource ?: "?"}")
        }

        if (ctx.microBolusAllowed && enableSMB) {
            val microBolus = insulinReq
            rT.reason.append(rh.gs(ApsStrings.reason_insulin_required, insulinReq))
            if (microBolus >= maxSMB) {
                rT.reason.append(rh.gs(ApsStrings.reason_max_smb, maxSMB))
            }
            rT.reason.append(". ")

            val smbInterval = calculateSMBInterval()
            val intervalStr = aimiFmt1(smbInterval.toDouble())
            val lastBolusStr = aimiFmt1(lastBolusAge)
            val deltaStr = aimiFmt1(delta.toDouble())
            rT.reason.append(" [SMB interval=")
            rT.reason.append(intervalStr)
            rT.reason.append(" min, lastBolusAge=")
            rT.reason.append(lastBolusStr)
            rT.reason.append(" min, Δ=")
            rT.reason.append(deltaStr)
            rT.reason.append(", BG=")
            rT.reason.append(bg.toInt().toString())
            rT.reason.append("] ")

            val nextBolusMins = round(smbInterval - lastBolusAge, 0)
            val nextBolusSeconds = round((smbInterval - lastBolusAge) * 60, 0) % 60
            if (lastBolusAge > smbInterval) {
                if (microBolus > 0) {
                    val htrFloorU = lastHyperTrajectoryRelease
                        ?.takeIf { it.active }
                        ?.smbFloorU
                        ?: 0.0
                    finalizeAndCapSMB(
                        rT = rT,
                        proposedUnits = microBolus,
                        reasonHeader = rh.gs(ApsStrings.reason_microbolus, microBolus),
                        mealData = ctx.mealData,
                        hypoThreshold = hypoThresholdMgdl,
                        isExplicitUserAction = false,
                        decisionSource = "GlobalAIMI",
                        isMealActive = isMealActive,
                        hyperReleaseFloorU = htrFloorU,
                    )
                }
            } else {
                rT.reason.append(
                    rh.gs(
                        ApsStrings.reason_wait_microbolus,
                        nextBolusMins,
                        nextBolusSeconds
                    )
                )
            }
        }
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
    ): BasalDecisionEngine.Decision {
        val forcedMealActive =
            abs(bundle.ctx.currentTemp.rate - bundle.forcedBasalMealModesMax) < 0.05 && bundle.ctx.currentTemp.duration > 0
        val auditorConfidence =
            try {
                app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(300_000)?.verdict?.confidence
            } catch (e: Exception) {
                0.0
            } ?: 0.0
        val basalInput = BasalDecisionEngine.Input(
            bg = bundle.bg,
            profileCurrentBasal = bundle.profileCurrentBasal,
            basalEstimate = bundle.basalEstimate,
            tdd7P = bundle.tdd7P,
            tdd7Days = bundle.tdd7Days,
            variableSensitivity = bundle.variableSensitivity,
            profileSens = bundle.profile.sens,
            // Carried on the profile of THIS tick, never read from a diagnostic global: `profile.sens`
            // is the commanded value and carries both floors, and a process-global would hand this
            // basal a value captured at another time of day.
            preFloorCommandedSens = bundle.profile.pre_floor_isf_mgdl,
            predictedBg = bundle.predictedBg,
            targetBg = bundle.targetBg,
            minBg = bundle.profile.min_bg,
            lgsThreshold = HypoThresholdMath.getLgsThresholdSafe(bundle.profile),
            eventualBg = bundle.eventualBg,
            iob = bundle.tickIobForEngine,
            maxIob = bundle.engineMaxIob,
            allowMealHighIob = bundle.allowMealHighIob,
            safetyDecision = bundle.safetyDecision,
            mealData = bundle.ctx.mealData,
            delta = bundle.delta,
            shortAvgDelta = bundle.shortAvgDelta,
            longAvgDelta = bundle.longAvgDelta,
            combinedDelta = bundle.combinedDelta,
            bgAcceleration = bundle.bgAcceleration,
            slopeFromMaxDeviation = bundle.ctx.mealData.slopeFromMaxDeviation,
            slopeFromMinDeviation = bundle.ctx.mealData.slopeFromMinDeviation,
            forcedBasal = bundle.forcedBasal,
            forcedMealActive = forcedMealActive,
            isMealActive = bundle.isMealActive,
            runtimeMinValue = bundle.runtimeMinValue,
            snackTime = snackTime,
            snackRuntimeMin = mealModeRuntimeToNullableMinutes(snackrunTime),
            fastingTime = fastingTime,
            sportTime = sportTime,
            honeymoon = bundle.honeymoon,
            pregnancyEnable = bundle.pregnancyEnable,
            mealTime = mealTime,
            mealRuntimeMin = mealModeRuntimeToNullableMinutes(mealruntime),
            bfastTime = bfastTime,
            bfastRuntimeMin = mealModeRuntimeToNullableMinutes(bfastruntime),
            lunchTime = lunchTime,
            lunchRuntimeMin = mealModeRuntimeToNullableMinutes(lunchruntime),
            dinnerTime = dinnerTime,
            dinnerRuntimeMin = mealModeRuntimeToNullableMinutes(dinnerruntime),
            highCarbTime = highCarbTime,
            highCarbRuntimeMin = mealModeRuntimeToNullableMinutes(highCarbrunTime),
            timenow = bundle.timenowHour,
            sixAmHour = bundle.sixAmHour,
            recentSteps5Minutes = recentSteps5Minutes,
            nightMode = bundle.nightMode,
            modesCondition = bundle.modesCondition,
            autodrive = bundle.autodrivePref,
            currentTemp = bundle.ctx.currentTemp,
            glucoseStatus = bundle.glucoseStatus,
            featuresCombinedDelta = bundle.featuresCombinedDelta,
            smbToGive = bundle.smbToGive,
            zeroSinceMin = bundle.zeroSinceMin,
            minutesSinceLastChange = bundle.minutesSinceLastChange,
            pumpCaps = bundle.pumpCaps,
            auditorConfidence = auditorConfidence,
            projectionHorizonMin = DynamicBasalController.PROJECTION_HORIZON_MIN
                .takeIf { preferences.get(BooleanKey.OApsAIMIBasalProjectedError) },
        )
        val helpers = BasalDecisionEngine.Helpers(
            calculateRate = { basalValue, currentBasalValue, multiplier, label ->
                calculateRate(basalValue, currentBasalValue, multiplier, label, bundle.ctx.currentTemp, bundle.rT)
            },
            calculateBasalRate = { basalValue, currentBasalValue, multiplier ->
                calculateBasalRate(basalValue, currentBasalValue, multiplier)
            },
            detectMealOnset = { deltaValue, predictedDelta, acceleration, predBg, targBg ->
                detectMealOnset(deltaValue, predictedDelta, acceleration, predBg, targBg)
            },
            round = { value, digits -> round(value, digits) }
        )
        return basalDecisionEngine.decide(basalInput, bundle.rT, helpers)
    }

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
        lastT3cRuntimeOwnership = AimiDecisionContext.T3cRuntimeOwnershipExport(
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
        val snapshot = lastRecursiveBeliefSnapshot ?: return
        val t3cState = snapshot.resolutions.t3cBasalFirst ?: return
        lastRecursiveBeliefSnapshot = snapshot.copy(
            resolutions = snapshot.resolutions.copy(
                t3cBasalFirst = t3cState.copy(
                    selectedForProduction = selectedForProduction,
                    historicalBypassNeutralized = lastT3cHistoricalBypassNeutralizedThisTick,
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
        val snapshot = lastRecursiveBeliefSnapshot ?: return
        val harmoniaState = snapshot.resolutions.harmoniaBasalFirst ?: return
        lastRecursiveBeliefSnapshot = snapshot.copy(
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
        consoleLog.add("🌳 T3C_NATIVE: not applied blocker=$runtimeBlocker")
        return null
    }

    private fun planT3cBasalFirstProduction(
        b: AimiPostBasalEngineFinalizeBundle,
    ): T3cBasalFirstApplyPlan? {
        if (!isNativeT3cRuntimeOwnerConfigured()) return null
        val snapshot = lastRecursiveBeliefSnapshot
            ?: return blockT3cBasalFirstProduction(null, "native_unavailable_no_snapshot")
        if (snapshot.resolutions.basalFirstChannel != BasalFirstChannel.T3C_BASAL_FIRST) {
            val reason = snapshot.resolutions.t3cBasalFirst?.dominantBlocker?.lowercase(Locale.US)
                ?: "native_no_basal_first_channel"
            return blockT3cBasalFirstProduction(snapshot.resolutions.t3cBasalFirst, reason)
        }
        val t3cState = snapshot.resolutions.t3cBasalFirst
            ?: return blockT3cBasalFirstProduction(null, "missing_t3c_state")

        if (!t3cState.active) {
            return blockT3cBasalFirstProduction(t3cState, "inactive")
        }
        if (!t3cState.eligible) {
            val blocker = t3cState.dominantBlocker?.lowercase(Locale.US) ?: "resolver_ineligible"
            return blockT3cBasalFirstProduction(t3cState, blocker)
        }
        if (!lastT3cHistoricalBypassNeutralizedThisTick) {
            return blockT3cBasalFirstProduction(t3cState, "historical_bypass_not_neutralized")
        }
        if (lastRecursiveAuthorityGateDecision?.effectiveAuthority != ReleaseAuthority.NONE) {
            return blockT3cBasalFirstProduction(t3cState, "smb_authority_active")
        }
        if ((b.rT.units ?: 0.0) > 0.0 || (b.rT.insulinReq ?: 0.0) > 0.0) {
            return blockT3cBasalFirstProduction(t3cState, "smb_already_requested")
        }
        if (basalChannelSafetyGuardsActive() && smbZeroedBySafetyThisTick()) {
            basalChannelGuardBlockedT3cCount++
            return blockT3cBasalFirstProduction(t3cState, "smb_zeroed_by_safety")
        }
        if (exerciseInsulinLockoutActive || t3cState.exerciseBlock) {
            return blockT3cBasalFirstProduction(t3cState, "exercise_lockout")
        }
        if (lastPostHypoDeliveryAuthority.active || t3cState.postHypoBlock) {
            return blockT3cBasalFirstProduction(t3cState, "post_hypo_guard")
        }
        if (t3cState.mealConflict) {
            return blockT3cBasalFirstProduction(t3cState, "meal_conflict")
        }
        if (t3cState.hardSafetyBlock) {
            return blockT3cBasalFirstProduction(t3cState, "hard_safety_block")
        }
        if (resolveIobForGate() > maxIob) {
            return blockT3cBasalFirstProduction(t3cState, "max_iob")
        }
        if (lastInsulinStackingEvaluation?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            return blockT3cBasalFirstProduction(t3cState, "stacking_cap")
        }

        val hypoGuard = HypoThresholdMath.computeHypoThreshold(
            minBg = b.profile.min_bg,
            lgsThreshold = b.profile.lgsThreshold,
        )
        val mealContext = MealSafetyContext(
            mealModeActive = mealTime || lunchTime || dinnerTime || snackTime || highCarbTime || bfastTime,
            manualBolusAgeMin = internalLastSmbMillis.takeIf { it > 0L }?.let { (dateUtil.now() - it) / 60000.0 },
            inferredMealSignal = inferredMealSafetyIntent(),
        )
        val (hypoPredForLgs, hypoEventualForLgs) = sanitizedHypoGuardPredictedEventual(
            rT = b.rT,
            predictedBg = predictedBg.toDouble(),
            eventualBg = eventualBG,
        )
        val (minPredCurve, ignoreMinPredCurve) = resolveLgsMinPredictedCurve(b.rT)
        val lgsReason = HypoLgsBlockReason.detect(
            bgNow = bg,
            predicted = hypoPredForLgs,
            eventual = hypoEventualForLgs,
            minPredictedCurve = minPredCurve,
            hypo = hypoGuard,
            delta = delta.toDouble(),
            mealContext = mealContext,
            ignoreMinPredictedCurve = ignoreMinPredCurve,
        )
        if (lgsReason != null) {
            return blockT3cBasalFirstProduction(
                t3cState,
                "final_hypo_${lgsReason.name.lowercase(Locale.US)}",
            )
        }

        val previousRate = if (b.ctx.currentTemp.duration > 0) b.ctx.currentTemp.rate else b.profile.current_basal
        val maxStepUp = max(0.30, previousRate * 0.20)
        val rampedRate = if (t3cState.boundedRateUph > previousRate) {
            min(t3cState.boundedRateUph, previousRate + maxStepUp)
        } else {
            t3cState.boundedRateUph
        }
        val finalRate = capBasalRateForCorrectionAggression(
            requestedRateUph = rampedRate,
            profileBasalUph = b.profile.current_basal,
            source = "RBT_T3C_BASAL_FIRST",
        ).coerceIn(0.0, t3cState.maxBasalCapUph.coerceAtLeast(rampedRate))
        if (finalRate <= 0.0) {
            return blockT3cBasalFirstProduction(t3cState, "no_basal_demand")
        }

        consoleLog.add(
            "🌳 T3C_NATIVE: ready rate=${aimiFmt2(finalRate)}U/h " +
                "demand=${aimiFmt2(t3cState.boundedRateUph)}U/h",
        )
        markT3cRuntimeOwnership("NATIVE_READY", "native_rbt_owner")
        return T3cBasalFirstApplyPlan(
            rateUph = finalRate,
            durationMin = 30,
        )
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
        lastHarmoniaProductionDecision = HarmoniaProductionDecision(
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
        consoleLog.add("🌿 HARMONIA_PROD: not applied blocker=$runtimeBlocker")
        return null
    }

    private fun harmoniaCriticalMealConflict(): Boolean =
        lastMealAbsorptionOutput?.mealDeliveryPriority == true &&
            (
                lastUamHypothesisState?.suppressMealInterpretation == true ||
                    lastPhysiologicalPhaseOutput?.policy?.suppressMealLikeScenario == true
                )

    private fun planHarmoniaProductionBranch(
        b: AimiPostBasalEngineFinalizeBundle,
    ): HarmoniaProductionApplyPlan? {
        val simulation = lastHarmoniaDecision ?: return null
        val rbtSnapshot = lastRecursiveBeliefSnapshot
        val rbtHarmonia = rbtSnapshot?.resolutions?.harmoniaBasalFirst
        if (rbtSnapshot != null) {
            if (rbtSnapshot.resolutions.basalFirstChannel != BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST) {
                val blocker = rbtHarmonia?.dominantBlocker?.lowercase(Locale.US)
                    ?: "rbt_no_harmonia_channel"
                return blockHarmoniaProduction(simulation, blocker)
            }
            if (rbtHarmonia == null) {
                return blockHarmoniaProduction(simulation, "rbt_missing_harmonia_state")
            }
            if (!rbtHarmonia.eligible) {
                val blocker = rbtHarmonia.dominantBlocker?.lowercase(Locale.US)
                    ?: "rbt_harmonia_ineligible"
                return blockHarmoniaProduction(simulation, blocker)
            }
        }

        val sourceAction = simulation.action
        if (!simulation.eligible) {
            val blocker = simulation.blockers.firstOrNull()?.lowercase(Locale.US) ?: "simulation_ineligible"
            return blockHarmoniaProduction(simulation, blocker)
        }
        if (
            sourceAction != HarmoniaAction.BASAL_FIRST &&
            sourceAction != HarmoniaAction.MEAL_SUPPORT &&
            sourceAction != HarmoniaAction.PROTECTIVE_REDUCTION &&
            sourceAction != HarmoniaAction.STABILIZE
        ) {
            return blockHarmoniaProduction(simulation, "no_production_action")
        }
        if (!simulation.targetBasalUph.isFinite()) {
            return blockHarmoniaProduction(simulation, "invalid_basal_demand")
        }
        val effectiveAuthority = lastRecursiveAuthorityGateDecision?.effectiveAuthority
        if (effectiveAuthority != null && effectiveAuthority != ReleaseAuthority.NONE) {
            // Mirror RBT soft-meal basal exception: DIGESTION MEAL_SUPPORT may own basal under SOFT
            // so production is not starved by smb_authority_active while SMB caps crush delivery.
            val softMealBasalException =
                effectiveAuthority == ReleaseAuthority.SOFT &&
                    sourceAction == HarmoniaAction.MEAL_SUPPORT &&
                    simulation.branch == "DIGESTION_ACTIVE"
            if (!softMealBasalException) {
                return blockHarmoniaProduction(simulation, "smb_authority_active")
            }
        }
        if ((b.rT.units ?: 0.0) > 0.0 || (b.rT.insulinReq ?: 0.0) > 0.0) {
            return blockHarmoniaProduction(simulation, "smb_already_requested")
        }
        if (basalChannelSafetyGuardsActive() && smbZeroedBySafetyThisTick()) {
            basalChannelGuardBlockedHarmoniaCount++
            return blockHarmoniaProduction(simulation, "smb_zeroed_by_safety")
        }
        if (exerciseInsulinLockoutActive) {
            return blockHarmoniaProduction(simulation, "exercise_lockout")
        }
        if (lastPostHypoDeliveryAuthority.active) {
            return blockHarmoniaProduction(simulation, "post_hypo_guard")
        }
        if (harmoniaCriticalMealConflict()) {
            return blockHarmoniaProduction(simulation, "meal_conflict")
        }
        if (lastPhysiologicalTreeSnapshot?.trunk?.riskLevel == PhysiologicalRiskLevel.CRITICAL) {
            return blockHarmoniaProduction(simulation, "critical_physio_risk")
        }
        if (resolveIobForGate() > maxIob) {
            return blockHarmoniaProduction(simulation, "max_iob")
        }
        if (lastInsulinStackingEvaluation?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            return blockHarmoniaProduction(simulation, "stacking_cap")
        }

        val hypoGuard = HypoThresholdMath.computeHypoThreshold(
            minBg = b.profile.min_bg,
            lgsThreshold = b.profile.lgsThreshold,
        )
        val mealContext = MealSafetyContext(
            mealModeActive = mealTime || lunchTime || dinnerTime || snackTime || highCarbTime || bfastTime,
            manualBolusAgeMin = internalLastSmbMillis.takeIf { it > 0L }?.let { (dateUtil.now() - it) / 60000.0 },
            inferredMealSignal = inferredMealSafetyIntent(),
        )
        val (hypoPredForLgs, hypoEventualForLgs) = sanitizedHypoGuardPredictedEventual(
            rT = b.rT,
            predictedBg = predictedBg.toDouble(),
            eventualBg = eventualBG,
        )
        val (minPredCurve, ignoreMinPredCurve) = resolveLgsMinPredictedCurve(b.rT)
        val lgsReason = HypoLgsBlockReason.detect(
            bgNow = bg,
            predicted = hypoPredForLgs,
            eventual = hypoEventualForLgs,
            minPredictedCurve = minPredCurve,
            hypo = hypoGuard,
            delta = delta.toDouble(),
            mealContext = mealContext,
            ignoreMinPredictedCurve = ignoreMinPredCurve,
        )
        if (lgsReason != null) {
            return blockHarmoniaProduction(
                simulation,
                "final_hypo_${lgsReason.name.lowercase(Locale.US)}",
            )
        }

        val profileMaxBasal = b.profile.max_basal.coerceAtLeast(b.profile.current_basal)
        val envMaxBasal = simulation.environment.maxBasalUph.takeIf { it.isFinite() && it > 0.0 }
            ?: profileMaxBasal
        val hardCap = minOf(envMaxBasal, profileMaxBasal).coerceAtLeast(0.0)
        val requestedRate = simulation.targetBasalUph.coerceIn(0.0, hardCap)
        val previousRate = if (b.ctx.currentTemp.duration > 0) b.ctx.currentTemp.rate else b.profile.current_basal
        val fragility = lastPatientState?.eventMemory?.correctionFragilityScore ?: 0.0
        val maxStepUp = when {
            fragility >= 0.68 -> max(0.15, previousRate * 0.10)
            fragility >= 0.55 -> max(0.20, previousRate * 0.15)
            else -> max(0.30, previousRate * 0.20)
        }
        val rampedRate = if (requestedRate > previousRate) {
            min(requestedRate, previousRate + maxStepUp)
        } else {
            requestedRate
        }
        val finalRate = capBasalRateForCorrectionAggression(
            requestedRateUph = rampedRate,
            profileBasalUph = b.profile.current_basal,
            source = "HARMONIA_PRODUCTION_BASAL_FIRST",
        ).coerceIn(0.0, hardCap)
        if (finalRate <= 0.0) {
            return blockHarmoniaProduction(simulation, "no_basal_demand")
        }

        recordHarmoniaProductionDecision(
            mode = HarmoniaProductionMode.READY,
            selectedForProduction = true,
            requestedRateUph = simulation.targetBasalUph,
            boundedRateUph = finalRate,
            appliedRateUph = null,
            appliedDurationMin = null,
            runtimeBlocker = null,
            safetyBlockers = emptyList(),
            sourceAction = sourceAction,
            branch = simulation.branch,
            reason = "production_basal_first_ready",
        )
        consoleLog.add(
            "🌿 HARMONIA_PROD: ready action=${sourceAction.name} rate=${aimiFmt2(finalRate)}U/h " +
                "requested=${aimiFmt2(simulation.targetBasalUph)}U/h",
        )
        return HarmoniaProductionApplyPlan(
            rateUph = finalRate,
            requestedRateUph = simulation.targetBasalUph,
            sourceAction = sourceAction,
            branch = simulation.branch,
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
        consoleLog.add(
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
        val anyMealActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime
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
        consoleLog.add("📊 BASAL_LEARNER:")
        consoleLog.add("  │ shortTerm: ${aimiFmt3(basalLearner.shortTermMultiplier)}")
        consoleLog.add("  │ mediumTerm: ${aimiFmt3(basalLearner.mediumTermMultiplier)}")
        consoleLog.add("  │ longTerm: ${aimiFmt3(basalLearner.longTermMultiplier)}")
        consoleLog.add("  └ combined: ${aimiFmt3(basalLearner.getMultiplier())}")

        // 🎯 Process UnifiedReactivityLearner
        unifiedReactivityLearner.processIfNeeded()

        // 📊 Expose UnifiedReactivityLearner state in rT for visibility
        unifiedReactivityLearner.lastAnalysis?.let { analysis ->
            consoleLog.add("📊 REACTIVITY_LEARNER:")
            consoleLog.add("  │ globalFactor: ${aimiFmt3(analysis.globalFactor)}")
            consoleLog.add("  │ shortTermFactor: ${aimiFmt3(analysis.shortTermFactor)}")
            consoleLog.add("  │ segmentFactor (${analysis.segmentDaypart.name}): ${aimiFmt3(analysis.segmentFactor)}")
            consoleLog.add("  │ combinedFactor: ${aimiFmt3(unifiedReactivityLearner.getCombinedFactor(hourOfDay))}")
            consoleLog.add("  │ TIR 70-180: ${analysis.tir70_180.toInt()}%")
            consoleLog.add("  │ CV%: ${analysis.cv_percent.toInt()}%")
            consoleLog.add("  │ Hypo count (24h): ${analysis.hypo_count}")
            if (analysis.floorLocked) {
                consoleLog.add("  │ floorLock: ACTIVE @0.50 (${analysis.floorLockReason})")
            } else if (analysis.floorReleased) {
                consoleLog.add("  │ floorLock: RELEASED progressively")
            }
            consoleLog.add("  │ Reason: ${analysis.adjustmentReason}")
            consoleLog.add("  └ Analyzed at: ${aimiCsvTimestamp(analysis.timestamp)}")
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
                    if (eta != null) "$eta min to stable orbit" else "Approaching..."
                } else {
                    "Diverging from target"
                }

                consoleLog.add("─────────────────────────────────┐")
                consoleLog.add("│ 🌀 TRAJECTORY STATUS            │")
                consoleLog.add("├─────────────────────────────────┤")
                consoleLog.add("│ Type: %-26s│".format(type))
                consoleLog.add("│ Health: %s %d%%          │".format(healthBar, healthPercent))
                consoleLog.add("│ ETA: %-27s│".format(etaText))
                consoleLog.add("│                                 │")
                consoleLog.add("│ Metrics:                        │")
                consoleLog.add("│ ├─ Curvature:    %-15s│".format(aimiFmt2(metrics.curvature)))
                consoleLog.add("│ ├─ Convergence:  %-15s│".format("%+.2f".format(metrics.convergenceVelocity)))
                consoleLog.add("│ ├─ Coherence:    %-15s│".format(aimiFmt2(metrics.coherence)))
                consoleLog.add("│ ├─ Energy:       %-15s│".format("%+.1f".format(metrics.energyBalance)))

                try {
                    val cached = app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache.get(600_000)
                    if (cached != null) {
                        consoleLog.add("│                                 │")
                        val aiIcon = "🤖"
                        val verdictEnum = cached.verdict.verdict
                        val action = verdictEnum.name
                        consoleLog.add("│ $aiIcon AI: %-25s│".format("$action (${(cached.verdict.confidence * 100).toInt()}%)"))
                        val evidenceList = cached.verdict.evidence
                        val evidenceStr = if (evidenceList.isNotEmpty()) evidenceList[0] else ""
                        val evidence = evidenceStr.replace("\n", " ").take(30)
                        consoleLog.add("│ > %-30s│".format(evidence))
                    }
                } catch (_: Exception) {
                }

                consoleLog.add("└─────────────────────────────────┘")
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
        val reactivityFactor = unifiedReactivityLearner.getCombinedFactor(hourOfDay)
        if (kotlin.math.abs(reactivityFactor - 1.0) > 0.01) {
            learnersParts.add("React×" + aimiFmt2(reactivityFactor))
        }
        val learnersSummary = learnersParts.joinToString(", ")

        val engineRate = b.basalDecision.rate
        val mergedRate = CorrectionAggressionBasalCap.mergeEngineAndRtRates(
            engineRateUph = engineRate,
            rtRateUph = b.rT.rate,
            gate = correctionAggressionDecision,
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
        var finalAdaptiveMultiplier = adaptiveMult

        val t3cNativePlan = planT3cBasalFirstProduction(b)
        val harmoniaProductionPlan = if (t3cNativePlan == null) {
            planHarmoniaProductionBranch(b)
        } else {
            recordHarmoniaProductionDecision(
                mode = HarmoniaProductionMode.SKIPPED,
                selectedForProduction = false,
                requestedRateUph = lastHarmoniaDecision?.targetBasalUph,
                boundedRateUph = null,
                appliedRateUph = null,
                appliedDurationMin = null,
                runtimeBlocker = "t3c_native_owner",
                safetyBlockers = emptyList(),
                sourceAction = lastHarmoniaDecision?.action,
                branch = lastHarmoniaDecision?.branch,
                reason = "t3c_native_owner",
            )
            null
        }
        if (t3cNativePlan != null) {
            finalProposedRate = t3cNativePlan.rateUph
            finalDuration = t3cNativePlan.durationMin
            finalOverrideSafetyLimits = false
            finalAdaptiveMultiplier = basalFirstAdaptiveMultiplier()
            lastDecisionSource = t3cNativePlan.decisionSource
            b.rT.reason.append("; 🌳T3C_NATIVE_BASAL_FIRST")
        } else if (harmoniaProductionPlan != null) {
            finalProposedRate = harmoniaProductionPlan.rateUph
            finalDuration = harmoniaProductionPlan.durationMin
            finalOverrideSafetyLimits = false
            finalAdaptiveMultiplier = basalFirstAdaptiveMultiplier()
            lastDecisionSource = harmoniaProductionPlan.decisionSource
            b.rT.reason.append("; 🌿HARMONIA_PRODUCTION_BASAL_FIRST")
        }

        val harmonizerOutcome = HarmoniaHarmonizer.evaluate(
            tree = lastPhysiologicalTreeSnapshot,
            simulation = lastHarmoniaDecision,
            bgMgdl = bg,
            deltaMgdl5m = delta.toDouble(),
            profileBasalUph = b.profile.current_basal,
            proposedTbrUph = finalProposedRate,
            eventualBgMgdl = eventualBG.takeIf { it.isFinite() && it > 1.0 },
            targetBgMgdl = b.profile.target_bg.toDouble(),
            correctionFragilityScore = lastPatientState?.eventMemory?.correctionFragilityScore ?: 0.0,
            postHyperExhaustionScore = lastPatientState?.eventMemory?.postHyperExhaustionScore ?: 0.0,
            minBgLookback75m = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
            mealCertainty = lastMealCertainty,
        )
        lastHarmonizerOutcome = harmonizerOutcome
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
            consoleLog.add("🌿 HARMONIA_HARMONIZER: ${outcome.toJsonSummary()} ${outcome.reasons.joinToString(",")}")
        }

        // 🏃 Effort/activity protection — basal side. Reduction-only damping from the single sensor effort belief
        // (graded, fail-safe: only lowers). SINGLE reduction per tick, guaranteed coverage: applied here for every
        // tick EXCEPT when Harmonia already owns the basal via PROTECTIVE_REDUCTION (fed by the same effort branch,
        // so it already reduced) — that avoids double-counting while keeping the same protection on every tick.
        val harmoniaAlreadyReducedForEffort =
            harmoniaProductionPlan?.sourceAction == HarmoniaAction.PROTECTIVE_REDUCTION
        lastEffortAssessment?.basalFactor?.takeIf { it < 1.0 && !harmoniaAlreadyReducedForEffort }?.let { factor ->
            val damped = (finalProposedRate * factor).coerceAtLeast(0.0)
            consoleLog.add(
                "🏃 EFFORT_BASAL_DAMP: %.2f→%.2f U/h (×%.2f, %s)".format(
                    Locale.US, finalProposedRate, damped, factor, lastEffortAssessment?.state?.name,
                )
            )
            b.rT.reason.append("; 🏃EFFORT×${aimiFmt2(factor)}")
            finalProposedRate = damped
        }

        // 🩸 Anti-whiplash : borne la HAUSSE de basale par tick (la baisse reste instantanée pour LGS). Exempté :
        // boost forcé/override, modes repas et sport, qui doivent réagir vite. Voir [slewLimitBasalUp].
        val mealModeActiveForSlew = mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime
        if (preferences.get(BooleanKey.OApsAIMIBasalSlewLimitEnabled) &&
            !finalOverrideSafetyLimits && !mealModeActiveForSlew && !sportTime
        ) {
            val prevRateUph = b.ctx.currentTemp.rate
            val slewed = slewLimitBasalUp(prevRateUph, finalProposedRate, b.profile.current_basal)
            if (slewed < finalProposedRate) {
                consoleLog.add(
                    "🩸 BASAL_SLEW_LIMIT: %.2f→%.2f U/h (prev %.2f)".format(Locale.US, finalProposedRate, slewed, prevRateUph)
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
                    consoleLog.add(
                        "🍽️ ANTICIP_BASAL_FLOOR: %.2f→%.2f U/h (budget %.2f U over %.0f min, elapsed %d min)".format(
                            Locale.US, finalProposedRate, floorUph,
                            preferences.get(DoubleKey.OApsAIMIAnticipBudgetU),
                            AnticipationBasalFloor.WINDOW_MINUTES, anticipruntime,
                        )
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
            sportNoteActive = sportTime,
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
                consoleLog.add(
                    "🍽️ FCL_MEAL_BASAL: %.2f→%.2f U/h (temp target %.0f mg/dL, note %d min ago)".format(
                        Locale.US, finalProposedRate, floorUph, b.profile.target_bg, fclruntime,
                    )
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
            consoleLog.add(
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
            consoleLog.add("📊 Learners applied to finalResult.reason: [" + learnersSummary + "]")
        }

        val urFactor = unifiedReactivityLearner.getCombinedFactor(hourOfDay)
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
        consoleLog.add("📊 RT instrumentation: 2-3 debug lines added to reason")

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
                val autodriveState = lastAutodriveState.toString()
                val wcyclePhase = wCycleFacade.getPhase()?.name
                val wcycleFactor = wCycleFacade.getIcMultiplier()
                val reasonTags = finalResult.reason.toString().split(". ").map { it.trim() }
                val auditorEffectiveProfile: EffectiveProfile? = effectiveProfileCached(dateUtil.now())
                lastAuditorAuditStartedAtMs = dateUtil.now()
                // Captured here, not read again inside the callback: `currentTickDecisionEventId` is
                // reset on the next tick (see its declaration), and the profile proposal can land
                // after that has already happened.
                val auditEventId = currentTickDecisionEventId ?: ""
                // The audited tick itself, built the same way every ring entry is, so the window the
                // profile checker is shown always ends on a fact of the same shape as the rest of it.
                val currentAuditorFact = buildAuditorTickFact(b.ctx, b.profile)
                val profileFactorRequest = AuditorProfileFactorRequest(
                    auditEventId = auditEventId,
                    ticks = auditorTickRing.snapshot(b.ctx.currentTime) + currentAuditorFact,
                    mealCertainty = lastMealCertainty,
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
                    cob = cob.toDouble(),
                    profile = b.profile,
                    pkpdRuntime = b.pkpdRuntime,
                    isfUsed = b.profile.variable_sens,
                    smbProposed = smbProposed,
                    tbrRate = tbrRate,
                    tbrDuration = tbrDuration,
                    intervalMin = intervalMin.toDouble(),
                    maxSMB = maxSMB,
                    maxSMBHB = maxSMBHB,
                    maxIOB = maxIob,
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
                    mealCertainty = lastMealCertainty,
                    harmoniaProduction = lastHarmoniaProductionDecision,
                    harmonizerOutcome = lastHarmonizerOutcome,
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
                            consoleLog.add(sanitizeForJson("🧠 AI Auditor: ✅ APPLIED - ${result.reason}"))
                            if (verdict != null) {
                                consoleLog.add(sanitizeForJson("   Verdict: ${verdict.verdict}, Conf: ${aimiFmt2(verdict.confidence)}"))
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
                            consoleLog.add(sanitizeForJson("🧠 AI Auditor: 🛑 REJECTED - ${result.reason}"))
                            consoleLog.add(sanitizeForJson("   Severity: ${result.severity}"))
                            finalResult.units = 0.0
                            finalResult.reason.setLength(0)
                            finalResult.reason.append("Auditor Rejected: ${result.reason}")
                        }
                        is DecisionResult.Skipped -> {
                            consoleLog.add(sanitizeForJson("🧠 AI Auditor: ⏸ SKIPPED - ${result.reason}"))
                        }
                        else -> {}
                    }
                    maybeAppendAuditorFollowupLine(verdict, result)
                }
            } catch (e: Exception) {
                consoleLog.add(sanitizeForJson("⚠️ AI Auditor error: ${e.message}"))
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
        val physioMults = lastFusedPhysioMultipliers ?: lastBasePhysioMultipliers
        val learningDiagnostics = PkpdLearningDiagnostics.from(
            causalStatePosterior = lastPatientState?.causalPosterior,
            allowLearning = preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath),
            exerciseFlag = sportTime,
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
                causalPosterior = lastPatientState?.causalPosterior,
                physioPeakShiftMinutes = physioMults.peakShiftMinutes,
                // Site branch: the snapshot used to leave this at its 0.0 default, so the peak
                // governor always reported site=0.0. The cannula age is already cached here.
                sitePeakShiftMinutes = TapSitePeakShift.minutesForSiteAge(pumpAgeDaysCached()),
                preferences = preferences,
                iobCobCalculator = iobCobCalculator,
                pkpdPredictionIobArray = ctx.pkpdIobDataArray,
                learningDiagnostics = learningDiagnostics,
                predictionAuthority = lastDecisionPredictionAuthority,
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
                criticalSafetyZeroed = criticalSafetyZeroedThisTick,
                contextSuppressSmb = lastContextSuppressSmb,
                mealModeActive = manualMealModeActive(),
                guardsEnabled = basalChannelSafetyGuardsActive(),
            )
            val counterfactual = HarmoniaCounterfactual.evaluate(
                simulation = lastHarmoniaDecision,
                production = lastHarmoniaProductionDecision,
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
            val trunkRisk = lastPhysiologicalTreeSnapshot?.trunk?.riskLevel
            val harmoniaRisk = lastHarmoniaDecision?.decisionBasis?.trunkRisk
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
        decisionCtx.adjustments.hyper_trajectory_release = lastHyperTrajectoryRelease?.toDecisionContextExport()
        decisionCtx.adjustments.physiological_phase = physiologicalPhaseExport()
        val patternsJson = lastPhysiologicalPatternSnapshot?.takeIf { it.active.isNotEmpty() }?.let { patterns ->
            PhysiologicalPatternExport.toJsonObject(patterns).also {
                decisionCtx.adjustments.physiological_patterns = it
            }
        }
        decisionCtx.adjustments.meal_absorption_phase = mealAbsorptionPhaseExport()
        decisionCtx.adjustments.pred_divergence = lastPredDivergenceExport
        val rbtPrefs = RecursiveBeliefPreferences.from(preferences)
        var harmoniaSmbAuthorityJson: JsonObject? = null
        lastRecursiveBeliefSnapshot?.let { snap ->
            val t3cApplied = snap.resolutions.t3cBasalFirst?.selectedForProduction == true
            val harmoniaApplied = snap.resolutions.harmoniaBasalFirst?.selectedForProduction == true
            val export = UnfoldExporter.toExport(
                snapshot = snap,
                shadowOnly = if (t3cApplied || harmoniaApplied) false else lastRecursiveAuthorityGateDecision?.shadowOnly == true,
                authorityApplied = t3cApplied || harmoniaApplied || (
                    lastRecursiveAuthorityGateDecision?.effectiveAuthority?.let {
                        it != ReleaseAuthority.NONE
                    } ?: false
                    ),
                waveletBands = snap.waveletBands,
                authorityGate = lastRecursiveAuthorityGateDecision,
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
        decisionCtx.adjustments.recursive_authority_gate = lastRecursiveAuthorityGateDecision?.toJsonObject()
        decisionCtx.adjustments.physio_latent_state = lastPhysioLatentState?.toJsonObject()
        decisionCtx.adjustments.uam_hypotheses = lastUamHypothesisState?.toJsonObject()
        decisionCtx.adjustments.patient_state = lastPatientState?.toJsonObject()
        decisionCtx.adjustments.patient_mode = lastPatientModeDecision?.toJsonObject()
        decisionCtx.adjustments.endocrine_belief = lastWCycleBelief?.toJsonObject()
        // Refresh cycle phase after WCycle resolve (early physio_context often ran with null wCycle).
        val existingPhysio = decisionCtx.adjustments.physiological_context
        decisionCtx.adjustments.physiological_context = AimiDecisionContext.PhysioContext(
            hormonal_cycle_phase = lastWCycleBelief?.takeIf { it.enabled }?.let {
                "${it.phase.name}_Day${it.dayInCycle}"
            } ?: wCycleInfoForRun?.let { "${it.phase.name}_Day${it.dayInCycle}" } ?: "Unknown",
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
                    val ar = lastPredictionAuthorityApplyResult
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
        decisionCtx.adjustments.physiological_tree = lastPhysiologicalTreeSnapshot?.toJsonObject()?.let { tree ->
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
        decisionCtx.adjustments.meal_certainty = lastMealCertainty?.toJsonObject()
        decisionCtx.adjustments.dose_terminal_snapshot = lastDoseTerminalSnapshot?.toJsonObject()
        decisionCtx.adjustments.tube_advisor = lastTubeAdvisorTrace
        decisionCtx.adjustments.pkpd_soft_floor = lastPkpdSoftFloorTelemetry?.toJsonObject()
        decisionCtx.adjustments.basal_terminal = lastBasalTerminalTelemetry
        decisionCtx.adjustments.adaptive_basal = lastAdaptiveBasalTrace
        decisionCtx.adjustments.harmonia_simulation = lastHarmoniaDecision?.toJsonObject()
        decisionCtx.adjustments.harmonia_production = lastHarmoniaProductionDecision?.toJsonObject()
        decisionCtx.adjustments.t3c_runtime_ownership = lastT3cRuntimeOwnership
        decisionCtx.adjustments.replay_quality = ReplayQualityExportBuilder.toJsonObject(
            ReplayQualityExportBuilder.build(
                phaseOutput = lastPhysiologicalPhaseOutput,
                mealAbsorptionOutput = lastMealAbsorptionOutput,
                hypothesisState = lastUamHypothesisState,
                patientState = lastPatientState,
                patientModeDecision = lastPatientModeDecision,
                patternSnapshot = lastPhysiologicalPatternSnapshot,
                iobSurveillanceExport = lastIobSurveillanceExport,
                safetyRiskExport = lastSafetyRiskExport,
                recursiveBeliefSnapshot = lastRecursiveBeliefSnapshot,
                authorityGateDecision = lastRecursiveAuthorityGateDecision,
                correctionAggressionDecision = correctionAggressionDecision,
                predictionAvailable = lastPredictionAvailable,
                smbProposedU = lastSmbProposed,
                smbCappedU = lastSmbCapped,
                smbFinalU = lastSmbFinal,
                decisionSource = lastDecisionSource,
                safetySource = lastSafetySource,
                rbtPreferences = rbtPrefs,
            ),
        )
        val bindingFinalU = terminalSmbAmountU
        // Written here, after the RT is final, because this is the one point every export path goes
        // through — the same reason `markEstimatorDiagnosticsForExport` writes its fields late. The
        // three draft copy sites do not all run on every tick, so setting the ladder fields there
        // would leave them null on the paths that skip that site.
        val bindingExportDraft = lastSmbBindingTraceDraft.copy(
            maxSmbLadderBranch = lastMaxSmbLadderBranch,
            slopeFromMinDeviation = lastSlopeFromMinDeviation,
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
            decision = lastPostHypoDeliveryAuthority,
            smbBeforeCapU = lastPostHypoSmbBeforeCapU,
            smbAfterCapU = lastPostHypoSmbAfterCapU,
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
        aimiDecisionExportedThisTick = true

        AimiLoopTelemetry.enterPhase(AimiLoopPhase.EXPORT, hormonitorStudyExporter)
        try {
            val fallbackTrace = PhysioDecisionTraceMTR(
                timestamp = dateUtil.now(),
                finalLoopDecisionType = inferFinalLoopDecisionFromResult(finalResult),
                source = "fallback_no_physio_trace"
            )
            val latestSnapshot = physioAdapter.getLatestSnapshot()
            val wCycleInfo = wCycleInfoForRun
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
                thyroidStatus = currentThyroidEffects.status.name,
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
            consoleError.add("Failed to save HORMONITOR event JSON: ${e.message}")
        }

        // Very last thing the stage does, and it must stay last: the export above has already read
        // `harmoniaPrevRuntimeBlocker`, so updating here is what makes "previous" mean the previous
        // tick. Move this up and every line would export its own blocker while claiming it is the
        // one before. Observation only.
        val nowBlocker = lastHarmoniaProductionDecision?.runtimeBlocker
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
            consoleError.add("Failed to save AIMI Decision JSON: ${e.message}")
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
    private fun maybeAppendAuditorFollowupLine(
        verdict: AuditorVerdict?,
        result: DecisionResult,
    ) {
        if (lastAuditorTickDisposition != AuditorJsonlExport.TickDisposition.EXTERNAL_PENDING) return
        val parentEventId = currentTickDecisionEventId ?: return
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
            auditorFollowupAppendInProgress.set(false)
        }
    }

    /**
     * Fenêtre repas **horloge thérapie** — mêmes drapeaux que le premier critère de
     * [runPostSafetyMealFirst30NgrHeadroomBasalSmbStage], valables **après**
     * [runTherapyHydrateClocksAndExerciseLockoutGate] (donc avant pont trajectory / cap spiral).
     * Ne duplique pas [nightGrowthResistanceMode.evaluate] (machine d’état singleton).
     */
    private fun therapyMealWindowActiveForSpiralAlign(): Boolean =
        mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime

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
    ): Boolean {
        if (isExplicitUserAction) return false
        val uam = AimiUamHandler.confidenceOrZero()
        // Feedforward front-load (field data 2026-07-23): on a strong, *sustained* unannounced rise the
        // UAM model already asks for 2+ U, but the tight-spiral anti-stacking cap withholds it (freezes
        // maxSMBHB ~1.3) because COB=0 and UAM confidence hasn't yet crossed 0.45 — so the dose only
        // catches up once BG is already high. Treat such a rise as meal-priority so the cap relaxes and
        // the SMB is released early. shortAvgDelta ≥ 5 requires the trend to hold across several readings
        // (not a single spike); the safety floors below (bg ≥ 145, iob < maxIob×0.75) still bind, and all
        // downstream hypo guards (SafetyNet zones, LGS, minPred, PKPD Guard A/B) are unchanged.
        val strongConfirmedRise = deltaValue >= 8.0f && shortAvgDeltaValue >= 5.0f
        if (!(mealClockActiveForSpiralRelax || mealData.mealCOB >= 6.0 || uam >= 0.45 || strongConfirmedRise)) return false
        if (bgValue < 145.0) return false
        if (deltaValue < 1.8f && shortAvgDeltaValue < 1.5f) return false
        if (!maxIobValue.isFinite() || maxIobValue <= 0.0) return false
        if (iobNow >= maxIobValue * 0.75) return false
        return true
    }

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
            maxIobValue = maxIob.toDouble(),
            isExplicitUserAction = isExplicitUserAction,
            mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
        )
        if (capRelaxContext) {
            if (wouldCap) {
                consoleLog.add(
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
        val prevMb = maxSMB
        val prevHb = maxSMBHB
        maxSMB = minOf(maxSMB, stdMaxSMB)
        val sharp = sharpRiseEligibleForTrajectorySpiralSoftCap(deltaValue, shortAvgDeltaValue)
        maxSMBHB = if (sharp) {
            val span = (prevHb - stdMaxSMB).coerceAtLeast(0.0)
            minOf(prevHb, stdMaxSMB + span * 0.5)
        } else {
            minOf(maxSMBHB, stdMaxSMB)
        }
        if (maxSMB == prevMb && maxSMBHB == prevHb) return
        val modeNote = if (sharp) " [SOFT_HB]" else ""
        consoleLog.add(
            "🌀🛡️ TRAJ_SPIRAL SMB-cap$modeNote: E=${aimiFmt1(energy)}U (>${aimiFmt1(energyTh)}) " +
                "IOB=${aimiFmt1(iobNow)}U (>${aimiFmt1(iobTh)}) " +
                "TDD24h=${aimiFmt1(tdd24hU)}U w=${aimiFmt0(weightKg)}kg " +
                "maxSMB ${aimiFmt2(prevMb)}→${aimiFmt2(maxSMB)}U " +
                "maxSMBHB ${aimiFmt2(prevHb)}→${aimiFmt2(maxSMBHB)}U " +
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
        val lastTraj = trajectoryGuard.getLastAnalysis()
        if (lastTraj == null || lastTraj.classification != TrajectoryType.TIGHT_SPIRAL) {
            return
        }

        val energy = lastTraj.metrics.energyBalance
        val curvature = lastTraj.metrics.curvature
        val iobNow = iobData.iob

        val mealPriorityAlign = isMealPriorityAlignedForSpiralSmbCap(
            bgValue = bg,
            deltaValue = delta,
            shortAvgDeltaValue = shortAvgDelta,
            mealData = mealData,
            iobNow = iobNow,
            maxIobValue = maxIob.toDouble(),
            isExplicitUserAction = isExplicitUserAction,
            mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
        )
        val tdd24Bridge = if (tdd24Hrs.isFinite() && tdd24Hrs > 0f) tdd24Hrs.toDouble() else 50.0
        val bridgeCombinedDelta = (delta + shortAvgDelta) / 2f
        val bridgeHyperTier = classifyHyperSeverityForTick(
            rT = rT,
            combinedDelta = bridgeCombinedDelta,
            tdd24hU = tdd24Bridge,
        ).tier
        val hyperTrajectorySpiral = bridgeHyperTier >= HyperSeverityTier.EMERGING
        val stackingSpiral = !hyperTrajectorySpiral
        val spiralMealAlign = mealPriorityAlign || hyperTrajectorySpiral

        val basalFraction = when {
            spiralMealAlign && energy > 3.5 -> 0.70
            spiralMealAlign && energy > 2.5 -> 0.85
            spiralMealAlign && energy > 1.5 -> 0.95
            stackingSpiral && energy > 3.5 -> 0.25
            stackingSpiral && energy > 2.5 -> 0.50
            stackingSpiral && energy > 1.5 -> 0.70
            else -> 1.0
        }

        val cgateAmplified = physioMultipliers.isfFactor > 1.05
        val effectiveFraction = if (cgateAmplified && basalFraction > 0.25) {
            (basalFraction - 0.20).coerceAtLeast(0.25)
        } else {
            basalFraction
        }

        if (effectiveFraction >= 1.0) return

        val proactiveBasal = profile.current_basal * effectiveFraction
        val cgateNote = if (cgateAmplified) " [CGate ISF↑ → amplification]" else ""
        val spiralNote = when {
            hyperTrajectorySpiral -> " [HTR_HYPER_SPIRAL tier=${bridgeHyperTier.name}]"
            mealPriorityAlign -> " [MEAL_PRIORITY_RELAX]"
            stackingSpiral -> " [STACKING_SPIRAL]"
            else -> ""
        }
        val reason = "TRAJ_TIGHT_SPIRAL: E=${aimiFmt1(energy)}U κ=${aimiFmt2(curvature)} IOB=${aimiFmt2(iobNow)}U → Basale proactive ${(effectiveFraction * 100).toInt()}%$cgateNote$spiralNote"

        consoleLog.add("🌀🛡️ TRAJECTORY_SAFETY_BRIDGE (deferred): $reason")

        pendingTrajSpiralBasal = PendingTrajSpiralBasal(
            proactiveBasalUph = proactiveBasal,
            durationMin = if (energy > 3.5) 30 else 15,
            reason = reason,
            safetyTierLabel = "TrajBridge_Tier${when { energy > 3.5 -> 1; energy > 2.5 -> 2; else -> 3 }}",
        )
        applyTrajectoryTightSpiralStandardSmbCapIfNeeded(
            energy = energy,
            iobNow = iobNow,
            tdd24hU = tdd24Hrs.toDouble(),
            deltaValue = delta,
            shortAvgDeltaValue = shortAvgDelta,
            mealData = mealData,
            isExplicitUserAction = isExplicitUserAction,
            mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
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
            consoleError.add("ISF fusionné=${aimiFmt1(fusedSensitivity)} dynISF=${aimiFmt1(dynSensitivity)} → appliqué=${aimiFmt1(sens)}")
            try {
                IsfTddProvider.set(fusedSensitivity)
            } catch (e: Exception) {
                consoleError.add("Impossible de mettre à jour IsfTddProvider: ${e.message}")
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
        val modesCondition = (!mealTime || mealruntime > 30) && (!lunchTime || lunchruntime > 30) && (!bfastTime || bfastruntime > 30) && (!dinnerTime || dinnerruntime > 30) && !sportTime && (!snackTime || snackrunTime > 30) && (!highCarbTime || highCarbrunTime > 30) && !sleepTime && !lowCarbTime
        val pbolusAS: Double = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val pbolusA: Double = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val reason = StringBuilder()
        val recentBGs = getRecentBGs()

        val oneHourAgo = now - (60 * 60 * 1000L)
        val bolusesHistory = getBolusesFromTimeCached(oneHourAgo, true)
        val totalBolusLastHour = bolusesHistory.sumOf { it.amount }

        calculateBgTrend(recentBGs, reason)

        val autosensRatio = if (ctx.autosensData.ratio != 1.0) ctx.autosensData.ratio else 1.0

        val systemTime = ctx.currentTime
        val iobArray = ctx.iobDataArray
        val iob_data = iobArray[0]
        val mealFlags = MealFlags(mealTime, bfastTime, lunchTime, dinnerTime, highCarbTime)
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.SIGNAL_PREPARATION, hormonitorStudyExporter)

        val lastBolusTimeMs: Long? = iob_data.lastBolusTime.takeIf { it > 0L }

        val lateFatRiseFlag = isLateFatProteinRise(
            bg = bg,
            predictedBg = predictedBg.toDouble(),
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            longAvgDelta = longAvgDelta.toDouble(),
            iob = iob.toDouble(),
            cob = cob.toDouble(),
            maxSMB = maxSMB,
            lastBolusTimeMs = lastBolusTimeMs,
            mealFlags = mealFlags
        )
        lateFatRiseFlagForExport = lateFatRiseFlag
        val tdd24hStateForPkpd = determineBasalInvocationCaches.getTdd24hTotalAmountState(tddCalculator)
        logInvocationCacheState("TDD24H_PKPD", tdd24hStateForPkpd)
        var tdd24Hrs = tdd24hStateForPkpd.valueOrNull()?.toFloat() ?: 0.0f
        if (tdd24Hrs == 0.0f) tdd24Hrs = tdd7P.toFloat()
        val bgTime = glucoseStatus.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)

        if (minAgo > 12.0) {
            reason.append("⚠️ Data Stale (${minAgo.toInt()}m) -> Logic Paused\n")
            consoleError.add("Data Stale (${minAgo}m) -> Logic Paused")
            logDecisionFinal("STALE_DATA", rT, bg, delta)
            return AimiSignalPreparationPkpdOutcome.StaleAbort(
                rT.also {
                    ensurePredictionFallback(it, bg)
                    markFinalLoopDecisionFromRT(it)
                }
            )
        }
        val windowSinceDoseMin = if (iob_data.lastBolusTime > 0 || internalLastSmbMillis > 0) {
            val effectiveLastBolusTime = kotlin.math.max(iob_data.lastBolusTime, internalLastSmbMillis)
            ((systemTime - effectiveLastBolusTime) / 60000.0).coerceAtLeast(0.0)
        } else 0.0
        val windowSinceDoseInt = windowSinceDoseMin.toInt()
        lastBolusAgeMinutes = windowSinceDoseMin
        val carbsActiveG = ctx.mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val pkpdMealContext = buildPkpdMealContext(
            mealData = ctx.mealData,
            predictedBgMgdl = predictedBg.toDouble(),
            targetBgMgdl = targetBg.toDouble(),
        )
        pkpdIntegration.setRecentBolusSamples(
            buildRecentPkpdBolusSamples(
                nowMillis = ctx.currentTime,
                fallbackWindowMin = windowSinceDoseInt
            )
        )
        val pkpdRuntimeTemp = try {
            pkpdIntegration.computeRuntime(
                epochMillis = ctx.currentTime,
                bg = bg,
                deltaMgDlPer5 = delta.toDouble(),
                iobU = iob.toDouble(),
                carbsActiveG = carbsActiveG,
                windowMin = windowSinceDoseInt,
                exerciseFlag = sportTime,
                profileIsf = profile.sens,
                tdd24h = tdd24Hrs.toDouble(),
                mealContext = pkpdMealContext,
                consoleLog = consoleLog,
                combinedDelta = combinedDelta.toDouble(),
                uamConfidence = AimiUamHandler.confidenceOrZero(),
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                physioLatentState = lastPhysioLatentState,
                estimatedRaMgdlPerMin = continuousStateEstimator.getLastRa().takeIf { it.isFinite() && it > 0.0 },
                causalStatePosterior = lastPatientState?.causalPosterior,
                patientEventMemory = lastPatientState?.eventMemory,
                allowLearning = true,
            )
        } catch (e: Exception) {
            consoleError.add("❌ PKPD runtime failed: ${e.message}")
            aapsLogger.error(LTag.APS, "PKPD computeRuntime failed", e)
            null
        }

        var pkpdRuntime = pkpdRuntimeIn
        if (pkpdRuntimeTemp != null) {
            pkpdRuntime = pkpdRuntimeTemp
            if (preferences.get(BooleanKey.OApsAIMIIntelligenceSingleLearnPath)) {
                this.cachedPkpdRuntime = pkpdRuntimeTemp
            }

            consoleLog.add("📊 PKPD_LEARNER:")
            consoleLog.add("  │ DIA (learned): ${aimiFmt2(pkpdRuntime.params.diaHrs)}h")
            consoleLog.add("  │ Peak (learned): ${aimiFmt0(pkpdRuntime.params.peakMin)}min")
            consoleLog.add("  │ fusedISF: ${aimiFmt1(pkpdRuntime.fusedIsf)} mg/dL/U")

            applyBasalFirstPolicy(
                bg = bg, delta = delta.toFloat(), combinedDelta = combinedDelta.toFloat(),
                mealData = ctx.mealData, autosens_data = ctx.autosensData, isMealAdvisorOneShot = isExplicitAdvisorRun,
                targetBg = targetBg.toDouble(), rT = rT, isConfirmedHighRise = isConfirmedHighRiseLocal
            )
            consoleLog.add("  └ adaptiveMode: ${if (pkpdRuntime.params.diaHrs != 4.0 || pkpdRuntime.params.peakMin != 75.0) "ACTIVE" else "DEFAULT"}")
        }

        return AimiSignalPreparationPkpdOutcome.Continue(
            AimiSignalPreparationPkpdContinue(
                modesCondition = modesCondition,
                pbolusAS = pbolusAS,
                pbolusA = pbolusA,
                reason = reason,
                recentBGs = recentBGs,
                totalBolusLastHour = totalBolusLastHour,
                autosensRatio = autosensRatio,
                iob_data = iob_data,
                lastBolusTimeMs = lastBolusTimeMs,
                lateFatRiseFlag = lateFatRiseFlag,
                tdd24Hrs = tdd24Hrs,
                minAgo = minAgo,
                windowSinceDoseInt = windowSinceDoseInt,
                pkpdRuntime = pkpdRuntime,
            )
        )
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
        applyTrajectoryAnalysis(
            currentTime = ctx.currentTime,
            bg = bg,
            delta = delta.toDouble(),
            bgacc = bgacc,
            iobActivityNow = iobActivityNow,
            iob = iob,
            insulinActionState = insulinActionState,
            lastBolusAgeMinutes = lastBolusAgeMinutes,
            cob = cob,
            targetBg = targetBg.toDouble(),
            profile = profile,
            rT = rT,
            uiInteraction = ctx.uiInteraction,
            relevanceScore = physioMultipliers.trajectoryRelevanceScore
        )
        runTrajectoryTightSpiralSafetyBridge(
            profile = profile,
            rT = rT,
            iobData = iobData,
            bg = bg,
            delta = delta,
            cob = cob,
            physioMultipliers = physioMultipliers,
            tdd24Hrs = tdd24Hrs,
            mealData = ctx.mealData,
            isExplicitUserAction = isExplicitAdvisorRun,
            mealClockActiveForSpiralRelax = therapyMealWindowActiveForSpiralAlign(),
        )
        val contextTargetOverride = applyContextModule(bg = bg, iob = iobData.iob, cob = cob.toDouble(), rT = rT)
        val sens = runTddRatesAndIsfFusionAfterContext(
            profile = profile,
            tdd7Days = tdd7Days,
            tdd7P = tdd7P,
            tdd24Hrs = tdd24Hrs,
            pkpdRuntime = pkpdRuntime,
        )
        val baseSensitivity = pkpdRuntime?.fusedIsf ?: profile.sens
        val effectiveISF = sens * ctx.autosensData.ratio
        val dynamicPbolusLarge = if (pbolusA > 0.0) pbolusA else calculateDynamicMicroBolus(effectiveISF, 25.0, reason)
        val dynamicPbolusSmall = if (pbolusAS > 0.0) pbolusAS else calculateDynamicMicroBolus(effectiveISF, 15.0, reason)
        return AimiTrajectoryContextIsfPrep(
            sens = sens,
            baseSensitivity = baseSensitivity,
            contextTargetOverride = contextTargetOverride,
            dynamicPbolusLarge = dynamicPbolusLarge,
            dynamicPbolusSmall = dynamicPbolusSmall,
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
        val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
        val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
        val isFreshAdvisor = (dateUtil.now() - advisorTime) < 60 * 60_000L
        val effectiveCob = if (ctx.mealData.mealCOB > 0) ctx.mealData.mealCOB
        else if (isFreshAdvisor) advisorCarbs else 0.0
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = bg,
            iobArray = ctx.pkpdIobDataArray ?: ctx.iobDataArray,
            finalSensitivity = sens,
            cobG = effectiveCob,
            profile = profile,
            delta = delta.toDouble(),
            endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
            hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
            stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
        )
        lastAdvancedPredictionCurves = curves
        recordPkpdSoftFloor(curves)
        val mealContext = buildMealSafetyContext(isExplicitAdvisorRun, iobData)
        val floorPreview = bg - 25.0
        val previewBest = PhysioPhaseFusion.previewBestTerminalMgdl(
            bgMgdl = bg,
            deltaMgdlPer5 = delta.toDouble(),
            hybridTerminalMgdl = curves.hybrid.lastOrNull(),
        )
        val preScenarioFused = PhysioPhaseFusion.classifyAndFuse(
            buildPhysiologicalPhaseClassifierInput(
                rT = rT,
                combinedDelta = combinedDelta,
                stepsLast15m = stepsLast15m,
                heartRateBpm = heartRateBpm,
                restingHeartRateBpm = restingHeartRateBpm,
                bestTerminalMgdl = previewBest,
                floorTerminalMgdl = floorPreview,
                mealAbsorptionMemoryActive = MealAbsorptionMemory.isActive(dateUtil.now()),
            ),
            physioMultipliers,
        )
        val phasePolicy = preScenarioFused.phaseOutput.policy
        val nowMs = dateUtil.now()
        val mealMemoryActive = MealAbsorptionMemory.isActive(nowMs)
        val mealHighBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            targetBg.toDouble(),
            preferences.get(DoubleKey.OApsAIMIHighBg),
        )
        val mealBestTFloorAbove = if (mealMemoryActive) {
            min(mealHighBand * 0.35, 55.0)
        } else {
            null
        }
        val scenarioCtx = ScenarioProjectionContext(
            mealContext = mealContext,
            effectiveCobG = effectiveCob,
            targetBgMgdl = targetBg.toDouble(),
            trajectoryAnalysis = trajectoryGuard.getLastAnalysis(),
            trajectoryRelevanceScore = preScenarioFused.multipliers.trajectoryRelevanceScore,
            activityProtectionMode = activityProtectionMode,
            contextActivityActive = aimiContextActivityActive,
            contextSmbFactor = rT.contextModulation.takeIf { rT.contextEnabled }?.toFloat() ?: 1.0f,
            physioSmbFactor = preScenarioFused.multipliers.smbFactor,
            physioReactivityFactor = preScenarioFused.multipliers.reactivityFactor,
            physioBasalFactor = preScenarioFused.multipliers.basalFactor,
            physiologicalPhase = phasePolicy.phase,
            suppressMealLikeUam = phasePolicy.suppressMealLikeScenario,
            scenarioBestCapAboveBgMgdl = phasePolicy.capScenarioBestAboveBgMgdl,
            mealAbsorptionPhase = MealAbsorptionMemory.lastPhase,
            mealAbsorptionMemoryActive = mealMemoryActive,
            mealAbsorptionBestTFloorAboveBgMgdl = mealBestTFloorAbove,
        )
        val scenario = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = delta,
                curves = curves,
                context = scenarioCtx,
            ),
        )
        lastScenarioProjection = scenario
        refreshPhysiologicalPhase(
            rT = rT,
            combinedDelta = combinedDelta,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            basePhysioMultipliers = physioMultipliers,
        )
        refreshMealAbsorptionPhase(
            combinedDelta = combinedDelta,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            mealContext = mealContext,
            lastBolusTimeMs = iobData.lastBolusTime.takeIf { it > 0L },
            nowMs = nowMs,
        )
        publishPatientStateAfterPhysiologyRefresh(
            glucoseStatus = glucoseStatus,
            nowMs = nowMs,
        )
        val cappedBest = scenario.scenarioBest.terminalMgdl
        this.predictedBg = cappedBest.toFloat()
        lastEventualBgSnapshot = cappedBest
        lastPredictionSize = scenario.scenarioBest.pointsMgdl.size
        lastPredictionAvailable = scenario.scenarioBest.pointsMgdl.isNotEmpty()
        consoleLog.add(scenario.formatLogLine())
        consoleError.add(
            "🔮 SCENARIO: floor=${scenario.clinicalFloor.terminalMgdl.toInt()} best=${scenario.scenarioBest.terminalMgdl.toInt()} " +
                "Δ=${(scenario.scenarioBest.terminalMgdl - scenario.clinicalFloor.terminalMgdl).toInt()}",
        )

        fun safePredPipeValue(v: Double) = if (v.isFinite()) v else Double.POSITIVE_INFINITY
        val sanity = sanitizePredictionValues(
            bg = bg,
            delta = delta,
            predBgRaw = scenario.scenarioBest.terminalMgdl,
            eventualBgRaw = scenario.scenarioBest.terminalMgdl,
            series = rT.predBGs,
            log = consoleLog,
        )
        val floorComposite = minOf(
            safePredPipeValue(bg),
            safePredPipeValue(scenario.clinicalFloor.pathMinMgdl),
            safePredPipeValue(scenario.clinicalFloor.terminalMgdl),
        )
        val minBg = minOf(
            safePredPipeValue(bg),
            safePredPipeValue(sanity.predBg),
            safePredPipeValue(sanity.eventualBg),
        )
        val threshold = HypoThresholdMath.computeHypoThreshold(floorComposite, profile.lgsThreshold)
        val pumpReachable = try {
            activePlugin.activePump.isInitialized() && activePlugin.activePump.isConnected()
        } catch (_: Exception) {
            false
        }
        consoleLog.add(
            "PRED_PIPE: bg=${bg.roundToInt()} delta=${aimiFmt1(delta)} bestT=${sanity.predBg.roundToInt()} " +
                "floorT=${scenario.clinicalFloor.terminalMgdl.toInt()} floorMin=${scenario.clinicalFloor.pathMinMgdl.toInt()} " +
                "min=${minBg.roundToInt()} th=${threshold.toInt()} " +
                "noise=${glucoseStatus.noise} dataAge=${minAgo}m pumpReachable=$pumpReachable sanity=${sanity.label}",
        )
        cachedRiskEnvelopeEarly = AimiRiskEnvelopeBuilder.buildEarly(
            bg = bg,
            delta = delta,
            predTerminal = scenario.clinicalFloor.terminalMgdl,
            eventualTerminal = scenario.scenarioBest.terminalMgdl,
            predBGs = rT.predBGs,
            lgsThreshold = profile.lgsThreshold,
        )
        consoleLog.add(AimiRiskEnvelopeBuilder.formatLogLine(cachedRiskEnvelopeEarly!!))
        return AimiAdvancedPredictionsPredPipePrep(sanity, minBg, threshold, scenario)
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
        val mealContext = buildMealSafetyContext(isExplicitAdvisorRun, iobData)
        val projectionInput = correctionAggressionProjectionInput(
            targetBgValue = profile.target_bg.toDouble(),
            cobValue = cob.toDouble(),
            combinedDeltaValue = combinedDelta,
        )
        val safetyTerminals = SafetyPredictionTerminalsResolver.resolveFromScenario(
            bg = bg,
            delta = delta,
            mealContext = mealContext,
            projection = scenario,
            mealAbsorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
            targetBgMgdl = projectionInput.targetBg,
            minBgLookback75m = projectionInput.minBgLookback75m,
            hasIndependentMealEvidence = CorrectionAggressionGate.hasIndependentMealEvidence(projectionInput),
            cobG = cob.toDouble(),
            mealCertainty = lastMealCertainty,
        )
        lastSafetyTerminalsForRbt = safetyTerminals
        val lgsTh = HypoThresholdMath.computeHypoThreshold(
            safetyTerminals.compositeMinMgdl,
            profile.lgsThreshold,
        )
        val suppression = PredictiveHypoEvaluator.evaluateSuppression(
            PredictiveHypoInput(
                bgNow = bg,
                predicted = safetyTerminals.predBg,
                eventual = safetyTerminals.eventualBg,
                hypoThreshold = lgsTh,
                delta = delta.toDouble(),
                mealContext = mealContext,
            ),
        )
        consoleLog.add(
            "RISK_SAFETY_EARLY: compositeMin=${safetyTerminals.compositeMinMgdl.toInt()} " +
                "predT=${safetyTerminals.predBg.toInt()} evT=${safetyTerminals.eventualBg.toInt()} " +
                "bestT=${scenario.scenarioBest.terminalMgdl.toInt()} floorT=${scenario.clinicalFloor.terminalMgdl.toInt()} " +
                "mealRise=${safetyTerminals.mealRiseConfirmed} suppressed=${suppression.suppressed}",
        )
        val resolution = trySafetyStart(
            bg = bg,
            delta = delta,
            profile = profile,
            iob = iobData,
            noise = glucoseStatus.noise.toInt(),
            predBg = safetyTerminals.predBg,
            eventualBg = safetyTerminals.eventualBg,
            mealContext = mealContext,
        )
        lastSafetyRiskExport = SafetyRiskExportSnapshot(
            predictiveHypoSuppressed = suppression.suppressed,
            safetyGate = resolution.lastSafetySource,
            haltRemainingPipeline = resolution.haltRemainingPipeline,
            mealContextActive = mealContext.hasMealIntent,
            mealRiseConfirmed = safetyTerminals.mealRiseConfirmed,
            compositeMinMgdl = safetyTerminals.compositeMinMgdl,
            predBgMgdl = safetyTerminals.predBg,
            eventualBgMgdl = safetyTerminals.eventualBg,
            uamTerminalMgdl = safetyTerminals.uamTerminalMgdl,
            hypoThresholdMgdl = lgsTh,
        )
        if (resolution.decision !is DecisionResult.Applied) return AimiPredPipelineSafetyGate.Continue
        val safetyRes = resolution.decision as DecisionResult.Applied
        consoleLog.add("SAFETY_APPLIED_TBR intent=${safetyRes.tbrUph} haltPipeline=${resolution.haltRemainingPipeline}")
        if (safetyRes.tbrUph != null) {
            setTempBasal(
                safetyRes.tbrUph,
                safetyRes.tbrMin ?: 30,
                profile,
                rT,
                ctx.currentTemp,
                overrideSafetyLimits = true,
                adaptiveMultiplier = adaptiveMult,
                allowPartialSafetyTbr = !resolution.haltRemainingPipeline,
                mealContext = mealContext,
            )
        }
        if (resolution.haltRemainingPipeline) {
            rT.insulinReq = 0.0
            rT.reason.append(" | ⚠ Safety Halt: ${safetyRes.reason}")
            lastDecisionSource = safetyRes.source
            logDecisionFinal("SAFETY", rT, bg, delta)
            markFinalLoopDecisionFromRT(rT, ctx.currentTemp)
            return AimiPredPipelineSafetyGate.Halt(rT)
        }
        rT.reason.append(" | ⚠ Safety TBR: ${safetyRes.reason}")
        lastDecisionSource = safetyRes.source
        return AimiPredPipelineSafetyGate.Continue
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
        consoleLog.add(
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
        consoleLog.add("📦 $msg")
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
    private var lastHypoBlockAt: Long = 0L
    private var hypoClearCandidateSince: Long? = null
    // T6: Verrou nocturne anti-bang-bang pour BG_Rise_Fast
    // Empêche le trigger de s'emballer si l'IOB est déjà élevé la nuit
    private var lastBgRiseFastNightMs: Long = 0L
    private var mealModeSmbReason: String? = null
    private var consoleError = mutableListOf<String>()
    private var consoleLog = mutableListOf<String>()
    private val externalDir by lazy { storageHelper.getAimiDirectory() }
    //private val modelFile = File(externalDir, "ml/model.tflite")
    //private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")
    private val csvfile by lazy { storageHelper.getAimiFile("oapsaimiML2_records.csv") }
    private val csvfile2 by lazy { storageHelper.getAimiFile("oapsaimi2_records.csv") }
    // AimiPath equivalents of externalDir/csvfile above, for the ml/* chain which takes the storage
    // port. Same locations as the File-based fields (both resolve through AimiStorageHelper).
    private val externalDirPath: AimiPath by lazy { storage.directory() }
    private val csvfilePath: AimiPath by lazy { storage.file("oapsaimiML2_records.csv") }
    /**
     * The app scoped file the CSV writer falls back to when the shared storage write is denied.
     *
     * Same place as before, named through the storage port instead of the platform: [AimiStorage.fallbackFile]
     * resolves `<app scoped external dir>/AAPS/<name>`, and falls back to the AIMI directory when the
     * platform has no app scoped external directory - which is the expression this replaces.
     */
    private fun appExternalFallbackFile(name: String): File = File(storage.fallbackFile(name).value)

    // Telemetry must never crash the loop: if the study exporter can't initialize (storage / permissions / context),
    // degrade to no telemetry rather than letting its construction abort the tick into a safe-hold. Null → all
    // telemetry calls below are no-ops (`?.`); AimiLoopTelemetry.enterPhase already accepts a null exporter.
    // The runCatching that used to live here moved into AndroidHormonitorStudyExporterProvider, unchanged.
    private val hormonitorStudyExporter: HormonitorStudyExporter? by lazy {
        hormonitorStudyExporterProvider.exporter()
    }
    private var csvPrimaryStorageDeniedLogged = false
    /** Files whose header was already compared with the wanted one since the app started. */
    private val csvHeaderCheckedPaths = mutableSetOf<String>()
    private val pkpdIntegration = PkPdIntegration(preferences, pkPdLearnedState, behaviorProfileSource)
    //private val tempFile = File(externalDir, "temp.csv")
    private var bgacc = 0.0
    private var predictedSMB = 0.0f
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
    private var eventualBG = 0.0
    private var now = aimiWallClockMs()
    private var iob = 0.0f
    private var cob = 0.0f
    private var predictedBg = 0.0f
    private var lastCarbAgeMin: Int = 0
    private var futureCarbs = 0.0f
    private var lastCycleNotificationDay: Int = -1 // State for cycle notification spam prevention
    //private var enablebasal: Boolean = false
    private var recentNotes: List<UE>? = null
    private var tags0to60minAgo = ""
    private var cachedPkpdRuntime: PkPdRuntime? = null // 🔧 FIX (MTR): Global cache for Safety methods
    private var cachedRiskEnvelopeEarly: AimiRiskEnvelope? = null
    private var cachedRiskEnvelopeDecision: AimiRiskEnvelope? = null
    private var lastSafetyRiskExport: SafetyRiskExportSnapshot? = null
    private var lastScenarioProjection: ScenarioProjectionPair? = null

    /** PKPD vs scenario divergence audit of the current tick (PredictionDivergenceAuditor). */
    private var lastPredDivergenceExport: JsonObject? = null
    private var lastDecisionPredictionAuthority: DecisionPredictionAuthority? = null
    private var lastIntelligenceSnapshot: AimiIntelligenceSnapshot? = null
    private var lastPredictionAuthorityApplyResult: PredictionAuthorityApplyResult? = null
    /** Cascade D4 / C1 — single dose terminal pair for the tick (post-Authority + thin Clamp). */
    private var lastDoseTerminalSnapshot: DoseTerminalSnapshot? = null

    private fun predictionAuthorityEnabled(): Boolean =
        preferences.get(BooleanKey.OApsAIMIPredictionAuthorityEnabled)

    /**
     * Dose-facing eventual: only [DoseTerminalSnapshot] / Authority / envelope — never an ungated
     * scenario preview (that bypassed Clamp sport/post-hypo/pathMin gates).
     */
    private fun authoritativeEventualBg(fallback: Double = this.eventualBG): Double {
        lastDoseTerminalSnapshot?.eventualMgdl?.takeIf { it.isFinite() }?.let { return it }
        if (predictionAuthorityEnabled()) {
            lastDecisionPredictionAuthority?.eventualTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
            cachedRiskEnvelopeDecision?.eventualTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
        }
        return fallback
    }

    private fun authoritativeMinPredBg(rT: RT, rawMinPred: Double?): Double? {
        lastDoseTerminalSnapshot?.minPredMgdl?.takeIf { it.isFinite() }?.let { return it }
        if (predictionAuthorityEnabled()) {
            lastDecisionPredictionAuthority?.predTerminalMgdl?.takeIf { it.isFinite() }?.let { return it }
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

    /** Clamp digestion arm: tree / absorption / meal-priority only — not MealCertainty alone. */
    private fun digestionOrMealActiveForDose(
        mealPriorityContext: Boolean = false,
    ): Boolean {
        val mealAbsorptionActive = when (lastMealAbsorptionOutput?.phase) {
            MealAbsorptionPhase.FIRST_WAVE,
            MealAbsorptionPhase.SECOND_WAVE,
            MealAbsorptionPhase.INTER_WAVE,
            MealAbsorptionPhase.PEAK_CORRECTION,
            -> true
            else -> false
        }
        val treeDigestionOrMeal =
            when (lastPhysiologicalTreeSnapshot?.trunk?.globalState) {
                GlobalPhysiologicalState.DIGESTION_ACTIVE,
                GlobalPhysiologicalState.MEAL_PROBABLE,
                -> true
                else -> false
            }
        return treeDigestionOrMeal || mealAbsorptionActive || mealPriorityContext
    }

    private fun buildDoseTerminalSnapshot(
        authority: DecisionPredictionAuthority?,
        applyResult: PredictionAuthorityApplyResult?,
        authorityEnabled: Boolean,
        fallbackEventualMgdl: Double,
        fallbackMinPredMgdl: Double,
        targetBgMgdl: Double,
        mealPriorityContext: Boolean,
    ): DoseTerminalSnapshot {
        val scenarioBest = lastScenarioProjection?.scenarioBest
        return DoseTerminalSnapshotBuilder.build(
            authority = authority,
            applyResult = applyResult,
            authorityEnabled = authorityEnabled,
            fallbackEventualMgdl = fallbackEventualMgdl,
            fallbackMinPredMgdl = fallbackMinPredMgdl,
            clampInput = ClampPkpdScenarioReconcile.Input(
                bgMgdl = bg,
                targetBgMgdl = targetBgMgdl,
                deltaMgdl5m = delta.toDouble(),
                pkpdEventualMgdl = fallbackEventualMgdl,
                scenarioTerminalMgdl = scenarioBest?.terminalMgdl,
                scenarioPathMinMgdl = scenarioBest?.gatePathMinMgdl,
                scenarioPathMinHitFloor = scenarioBest?.gatePathMinHitFloor == true,
                digestionOrMealActive = digestionOrMealActiveForDose(mealPriorityContext),
                sportTime = sportTime,
                postHypoDeliveryActive = lastPostHypoDeliveryAuthority.active,
            ),
        )
    }

    private data class TubeDoseBaseline(
        val maxSmb: Double,
        val maxSmbHb: Double,
        val currentBasal: Double,
        val maxDailyBasal: Double,
    )

    private var tubeDoseBaseline: TubeDoseBaseline? = null
    /** True after Tube has been applied from a pre-delivery snapshot this tick. */
    private var tubeAppliedFromDoseSnapshotThisTick: Boolean = false

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
    ) {
        val snap = lastDoseTerminalSnapshot ?: return
        if (!preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) return
        if (stageTag == "late_pkpd") {
            consoleLog.add("📐 TUBE-LINE-D4[$stageTag]: skip (caps frozen after pre-delivery publish)")
            return
        }
        val dia = tickEffectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val isf = variableSensitivity.toDouble().takeIf { it.isFinite() && it > 1.0 } ?: return
        if (tubeDoseBaseline == null) {
            tubeDoseBaseline = TubeDoseBaseline(
                maxSmb = this.maxSMB,
                maxSmbHb = this.maxSMBHB,
                currentBasal = profile.current_basal,
                maxDailyBasal = profile.max_daily_basal,
            )
        }
        val baseline = tubeDoseBaseline!!
        this.maxSMB = baseline.maxSmb
        this.maxSMBHB = baseline.maxSmbHb
        profile.current_basal = baseline.currentBasal
        profile.max_daily_basal = baseline.maxDailyBasal
        lastTubeAdvisorSmbCapScale = null
        try {
            val tubeOut = straightLineTubeAdvisor.advise(
                StraightLineTubeAdvisor.Input(
                    bgMgdl = bg,
                    deltaMgdlPer5m = delta.toDouble(),
                    iobU = iob.toDouble(),
                    cobG = mealData.mealCOB.toDouble(),
                    isfMgdlPerU = isf,
                    diaHours = dia,
                    targetMgdl = targetBgMgdl,
                    maxSmbU = this.maxSMB,
                    minPredictedBg = snap.minPredMgdl,
                    eventualBgMgdl = snap.eventualMgdl,
                ),
            )
            if (!tubeOut.feasible) {
                this.maxSMB = 0.05
                this.maxSMBHB = 0.05
                lastTubeAdvisorSmbCapScale = 0.0
                if (tubeOut.basalCapScale < 0.999) {
                    profile.current_basal = baseline.currentBasal * tubeOut.basalCapScale
                    profile.max_daily_basal = baseline.maxDailyBasal * tubeOut.basalCapScale
                }
                consoleLog.add("📐 TUBE-LINE-D4[$stageTag]: infeasible ${tubeOut.reason}")
                noteTubeAdvisorTrace(tubeOut, snap, stageTag, baseline)
            } else {
                if (tubeOut.smbCapScale < 0.999) {
                    lastTubeAdvisorSmbCapScale = tubeOut.smbCapScale
                    this.maxSMB = (baseline.maxSmb * tubeOut.smbCapScale).coerceAtLeast(0.05)
                    this.maxSMBHB = (baseline.maxSmbHb * tubeOut.smbCapScale).coerceAtLeast(0.05)
                }
                if (tubeOut.basalCapScale < 0.999) {
                    profile.current_basal = baseline.currentBasal * tubeOut.basalCapScale
                    profile.max_daily_basal = baseline.maxDailyBasal * tubeOut.basalCapScale
                }
                consoleLog.add(
                    "📐 TUBE-LINE-D4[$stageTag]: maxSMB=${aimiFmt2(this.maxSMB)} " +
                        "basal×${aimiFmt3(tubeOut.basalCapScale)} | ${tubeOut.reason}",
                )
                noteTubeAdvisorTrace(tubeOut, snap, stageTag, baseline)
            }
            tubeAppliedFromDoseSnapshotThisTick = true
        } catch (e: Exception) {
            consoleError.add("📐 TUBE-LINE-D4[$stageTag]: ${e.message}")
        }
    }

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
            put("max_smb_after_u", this@DetermineBasalaimiSMB2.maxSMB)
            put("max_smb_hb_after_u", this@DetermineBasalaimiSMB2.maxSMBHB)
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
    ) {
        val authorityEnabled = predictionAuthorityEnabled()
        val authorityShadow = preferences.get(BooleanKey.OApsAIMIPredictionAuthorityShadow)
        val decisionPrediction = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = bg,
            pkpdEventualMgdl = pkpdEventualMgdl,
            scenarioProjection = lastScenarioProjection,
            mealAbsorptionOutput = lastMealAbsorptionOutput,
            hypothesisState = lastUamHypothesisState,
            latentState = lastPhysioLatentState,
            causalStatePosterior = lastPatientState?.causalPosterior,
            trajectoryAnalysis = trajectoryGuard.getLastAnalysis(),
            physioPolicy = lastPhysiologicalPhaseOutput?.policy,
            uamConfidence = AimiUamHandler.confidenceOrZero(),
            postHypoDelivery = lastPostHypoDeliveryAuthority,
            mealCertainty = lastMealCertainty,
            trunkGlobalState = lastPhysiologicalTreeSnapshot?.trunk?.globalState,
            mealConfirmedEarlyReleaseEnabled = preferences.get(BooleanKey.OApsAIMIMealConfirmedEarlyRelease),
            // The smoothed combined delta, not the raw 5-minute one. The parameter has always been
            // named for the combined signal; passing the raw delta let a single sensor step of +24
            // satisfy the "rising" test and clear the "falling" breaker on the same tick.
            combinedDeltaMgdl5m = tickCombinedDelta.toDouble(),
            targetBgMgdl = targetBgMgdl,
            iobU = iob.toDouble(),
            maxIobU = maxIob,
            mcerTailLatched = mcerTailLatch.latched,
            declaredMeal = anticipTime && preferences.get(BooleanKey.OApsAIMIAnticipMealEvidence),
        )
        // Carry the latch to the next tick. Done after the call because the resolver is stateless and
        // reports the trip; it can only keep an opt-in escalation off, never raise a dose.
        mcerTailLatch = MealConfirmedEarlyReleaseLatch.next(
            previous = mcerTailLatch,
            armedThisTick = decisionPrediction.mcerArmed,
            tailTripped = decisionPrediction.mcerTailTripped,
            bgMgdl = bg.toDouble(),
            targetBgMgdl = targetBgMgdl,
            iobU = iob.toDouble(),
        )
        lastDecisionPredictionAuthority = decisionPrediction
        consoleLog.add(
            DecisionPredictionAuthorityResolver.formatLogLine(decisionPrediction) + " [$stageTag]",
        )
        val applyResult = PredictionAuthorityApplier.apply(
            rT = rT,
            authority = decisionPrediction,
            scenarioProjection = lastScenarioProjection,
            enabled = authorityEnabled,
            shadowOnly = authorityShadow && !authorityEnabled,
            pkpdEventualBeforeApply = pkpdEventualMgdl,
            pkpdPredTerminalBeforeApply = pkpdPredTerminalMgdl,
        )
        lastPredictionAuthorityApplyResult = applyResult
        PredictionAuthorityApplier.formatShadowLogLine(applyResult)?.let { line -> consoleLog.add(line) }
        if (applyResult.applied) {
            this.eventualBG = applyResult.eventualMgdl
            this.predictedBg = applyResult.eventualMgdl.toFloat()
            rT.eventualBG = applyResult.eventualMgdl
            consoleLog.add(
                "PRED_AUTHORITY_C1[$stageTag]: eventual=${applyResult.eventualMgdl.toInt()} " +
                    "predT=${applyResult.predTerminalMgdl.toInt()} " +
                    "curves=${applyResult.predBGsRemapped} src=${applyResult.source}",
            )
        }
        val mealPriorityContext = lastMealAbsorptionOutput?.mealDeliveryPriority == true
        val doseSnapshot = buildDoseTerminalSnapshot(
            authority = decisionPrediction,
            applyResult = applyResult,
            authorityEnabled = authorityEnabled,
            fallbackEventualMgdl = pkpdEventualMgdl,
            fallbackMinPredMgdl = pkpdPredTerminalMgdl,
            targetBgMgdl = targetBgMgdl,
            mealPriorityContext = mealPriorityContext,
        )
        lastDoseTerminalSnapshot = doseSnapshot
        this.eventualBG = doseSnapshot.eventualMgdl
        this.predictedBg = doseSnapshot.eventualMgdl.toFloat()
        rT.eventualBG = doseSnapshot.eventualMgdl
        consoleLog.add(DoseTerminalSnapshot.formatLogLine(doseSnapshot) + " [$stageTag]")
        if (doseSnapshot.clampReconciled) {
            consoleLog.add(
                "🩹 CLAMP_RECONCILE (in snapshot)[$stageTag] reason=${doseSnapshot.clampReason} " +
                    "ev=${doseSnapshot.eventualMgdl.toInt()}",
            )
        }
        applyTubeAdvisorFromDoseSnapshot(profile, mealData, targetBgMgdl, stageTag)
    }

    /**
     * Re-merge RBT HTR after late PKPD snapshot for finalize/SafetyNet consumers.
     * Does not re-deliver V3 SMB (pump path already used pre_v3_rbt / pre_rbt terminals).
     */
    private fun refineRbtMergeAfterDoseSnapshot(rT: RT) {
        if (!rbtResolvedThisTick) return
        val prev = lastRbtLiveCommitResult ?: return
        val gate = lastRecursiveAuthorityGateDecision ?: return
        val snap = lastDoseTerminalSnapshot ?: return
        val endogenousCounterRegulatory =
            lastPhysiologicalPhaseOutput?.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
        val stackingEval = InsulinStackingStance.evaluate(
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            targetBg = targetBg.toDouble(),
            iob = iob.toDouble(),
            maxIob = maxIob,
            eventualBg = snap.eventualMgdl.takeIf { it.isFinite() },
            minPredBg = minPredictedBgForRbtWiring(snap.minPredMgdl),
            trajectoryEnergy = rT.trajectoryEnergy,
            isExplicitUserAction = false,
            enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
            mealPriorityContext =
                lastMealAbsorptionOutput?.mealDeliveryPriority == true &&
                    lastUamHypothesisState?.suppressMealInterpretation != true,
            endogenousCounterRegulatory = endogenousCounterRegulatory,
            mealAbsorptionPhase = lastMealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
            mealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime,
        )
        lastInsulinStackingEvaluation = stackingEval
        val refreshed = mergeRbtHyperTrajectoryRelease(
            htr = prev.baselineHtr,
            rbtSnapshot = lastRecursiveBeliefSnapshot,
            authorityGate = gate,
            rT = rT,
        )
        if (abs(refreshed.effectiveHtr.v3SmbAfterU - prev.effectiveHtr.v3SmbAfterU) > 0.02) {
            consoleLog.add(
                "RBT_REFINE_AFTER_DOSE_SNAPSHOT: " +
                    "${aimiFmt2(prev.effectiveHtr.v3SmbAfterU)}→" +
                    "${aimiFmt2(refreshed.effectiveHtr.v3SmbAfterU)}U " +
                    "ev=${snap.eventualMgdl.toInt()} minPred=${snap.minPredMgdl.toInt()}",
            )
        }
        lastRbtLiveCommitResult = refreshed
    }
    private var lastAdvancedPredictionCurves: AdvancedPredictionCurves? = null
    /** Wave4 H3 — last soft-floor path-min telemetry (JSON study + production curves). */
    private var lastPkpdSoftFloorTelemetry: PkpdSoftFloorTelemetry? = null
    private var lastSafetyTerminalsForRbt: SafetyPredictionTerminals? = null
    private var lastHyperTrajectoryRelease: HyperTrajectoryReleaseResult? = null
    private var lastRecursiveBeliefSnapshot: RecursiveBeliefSnapshot? = null
    private var lastRecursiveAuthorityGateDecision: RecursiveBeliefAuthorityGate.Decision? = null
    private var lastT3cHistoricalBypassNeutralizedThisTick: Boolean = false
    private var lastT3cRuntimeOwnership: AimiDecisionContext.T3cRuntimeOwnershipExport? = null
    private var lastRbtChaosEvaluation: RbtChaosEvaluator.Result? = null
    private var lastRbtAppliedHints: RbtResolutionBridge.AppliedHints? = null
    private var rbtResolvedThisTick = false
    private var lastRbtLiveCommitResult: RbtLiveCommitResult? = null
    /** Sync auditor disposition for the current tick (JSONL `adjustments.auditor_tick`). */
    private var lastAuditorTickDisposition: AuditorJsonlExport.TickDisposition? = null
    private var lastAuditorLoopSnapshot: AuditorJsonlExport.TickSnapshot? = null
    private var currentTickDecisionEventId: String? = null
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
    private val auditorFollowupAppendInProgress = AtomicBoolean(false)
    private var lastLoadGovernorMultiplierG: Double = 1.0
    private var lastPhysiologicalPhaseOutput: PhysiologicalPhaseClassifier.Output? = null
    private var lastPhysiologicalPatternSnapshot: PhysiologicalPatternSnapshot? = null
    private val patternCapHold = PatternCapHold()
    private var lastMealAbsorptionOutput: MealAbsorptionPhaseEngine.Output? = null
    private var lastInsulinStackingEvaluation: InsulinStackingStance.Evaluation? = null
    private var lastBasePhysioMultipliers: PhysioMultipliersMTR = PhysioMultipliersMTR.NEUTRAL
    private var lastFusedPhysioMultipliers: PhysioMultipliersMTR? = null
    private var lastScenarioBestCappedForPhysio: Boolean = false
    private var hyperDwellAboveHighBgSinceMs: Long = 0L
    private var pendingTrajSpiralBasal: PendingTrajSpiralBasal? = null

    private data class PendingTrajSpiralBasal(
        val proactiveBasalUph: Double,
        val durationMin: Int,
        val reason: String,
        val safetyTierLabel: String,
    )
    private var tags60to120minAgo = ""
    private var tags120to180minAgo = ""
    private var tags180to240minAgo = ""
    private var tir1DAYabove: Double = 0.0
    private var currentTIRLow: Double = 0.0
    private var lastProfile: OapsProfileAimi? = null
    private var wCycleInfoForRun: WCycleInfo? = null
    private var lastWCycleBelief: WCycleBelief? = null
    private var wCycleReasonLogged: Boolean = false
    private var currentTIRRange: Double = 0.0
    private var currentTIRAbove: Double = 0.0
    private var lastHourTIRLow: Double = 0.0
    private var lastHourTIRLow100: Double = 0.0
    private var lastHourTIRabove170: Double = 0.0
    private var lastHourTIRabove120: Double = 0.0
    private var bg = 0.0
    private var targetBg = 90.0f
    private var normalBgThreshold = 110.0f
    private var delta = 0.0f
    private var shortAvgDelta = 0.0f
    private var longAvgDelta = 0.0f
    private var lastsmbtime = 0
    private var acceleratingUp: Int = 0
    private var decceleratingUp: Int = 0
    private var acceleratingDown: Int = 0
    private var decceleratingDown: Int = 0
    private var stable: Int = 0
    private var maxIob = 0.0
    private var maxSMB = 0.5
    private var maxSMBHB = 0.5
    private var lastBolusSMBUnit = 0.0f
    private var tdd7DaysPerHour = 0.0f
    private var tdd2DaysPerHour = 0.0f
    private var tddPerHour = 0.0f
    private var tdd24HrsPerHour = 0.0f
    private var hourOfDay: Int = 0
    private var weekend: Int = 0
    private var recentSteps5Minutes: Int = 0
    private var recentSteps10Minutes: Int = 0
    private var recentSteps15Minutes: Int = 0
    private var recentSteps30Minutes: Int = 0
    private var recentSteps60Minutes: Int = 0
    private var recentSteps180Minutes: Int = 0
    private var basalaimi = 0.0f
    private var endoSmbMult = 1.0  // Written by applyEndoAndActivityAdjustments each cycle
    private var activityProtectionMode = false  // Written by applyEndoAndActivityAdjustments
    private var activityStateIntense = false     // Written by applyEndoAndActivityAdjustments
    private var cachedActivityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext? = null  // Written by applyEndoAndActivityAdjustments
    private var cachedBasalFirstActive = false    // Written by applyBasalFirstPolicy
    private var cachedIsFragileBg = false         // Written by applyBasalFirstPolicy
    private var aimilimit = 0.0f
    private var ci = 0.0f
    private var sleepTime = false
    private var sportTime = false
    /** Intent contexte AIMI : activité déclarée (snapshot.hasActivity). */
    private var aimiContextActivityActive = false
    /** Sport thérapie OU activité AIMI : SMB off ; basale off si BG ≤ [EXERCISE_BASAL_RESUME_BG_MGDL], sauf T3c/standard si BG > seuil. */
    private var exerciseInsulinLockoutActive = false
    /** Hyper en montée pendant lockout exercice : basale non réduite (thyroïde / stress activité). SMB reste off par défaut. */
    private var exerciseHyperBasalOverrideActive = false
    /** Combined Δ G6-adjusted for the current tick (set in [runTickClockMaxSmbTirCarbAndGlucoseCopy]). */
    private var tickCombinedDelta = 0f
    private var snackTime = false
    private var lowCarbTime = false
    private var highCarbTime = false
    private var mealTime = false

    /** A meal the person declared with an "anticip" note; carries no prebolus. See [AnticipationBasalFloor]. */
    private var anticipTime = false

    /** Minutes since that declaration. Same type as [mealruntime], which this mirrors. */
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
            sportNoteActive = sportTime,
            tempTargetSet = profile.temptargetSet,
            targetBgMgdl = profile.target_bg,
        )
    private var bfastTime = false
    private var lunchTime = false
    private var dinnerTime = false
    private var fastingTime = false
    private var stopTime = false
    private var iscalibration = false
    private var mealruntime: Long = 0
    private var bfastruntime: Long = 0
    private var lunchruntime: Long = 0
    private var dinnerruntime: Long = 0
    private var highCarbrunTime: Long = 0
    private var snackrunTime: Long = 0
    private var intervalsmb = 1
    private var peakintermediaire = 0.0
    private var latestAdjustedDia: Double = 0.0 // Captured for logging
    private var insulinPeakTime = 0.0
    private var iobActivityNow: Double = 0.0
    private var lastBolusAgeMinutes: Double = Double.NaN
    /** One PKPD absorption-guard multiply per [determine_basal] tick (see [applyPkpdAbsorptionGuardOncePerTick]). */
    private var pkpdAbsorptionGuardAppliedThisTick: Boolean = false
    private var isConfirmedHighRiseThisTick: Boolean = false
    /**
     * Sécurité VITALE ce tick : [applySafetyPrecautions] a mis le SMB à 0 via [isCriticalSafetyCondition]
     * (protection hypo minPredBG, chute rapide, …). Le restore Red Carpet ne doit JAMAIS ressusciter ce
     * zéro-là — il n'est autorisé que sur les réductions « mineures » (throttle, refractory, damping).
     * N'affecte pas les actions explicites (elles passent avec ignoreSafetyConditions=true, le flag ne se
     * pose pas) ni les prébolus des modes manuels (chemin applyLegacyMealModes, hors Red Carpet).
     */
    private var criticalSafetyZeroedThisTick: Boolean = false

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
    private var tickCobGrams: Double = Double.NaN

    /** 🔭 Lot 0 — `true` dès qu'une ligne `AIMI_Decisions.jsonl` a été écrite pour le tick courant. */
    private var aimiDecisionExportedThisTick: Boolean = false

    /** 🔭 Lot 0 — contexte de décision du tick, conservé pour l'export des sorties anticipées. */
    private var pendingDecisionCtxForExport: AimiDecisionContext? = null

    /** 🛡️ Lot 3 — nombre de fois que le garde-fou a bloqué chaque canal basal-first (télémétrie). */
    private var basalChannelGuardBlockedT3cCount: Int = 0
    private var basalChannelGuardBlockedHarmoniaCount: Int = 0

    /** 🛡️ Lot 3 — garde-fous du canal basal, voir [BooleanKey.OApsAIMIBasalChannelSafetyGuards]. */
    private fun basalChannelSafetyGuardsActive(): Boolean =
        preferences.get(BooleanKey.OApsAIMIBasalChannelSafetyGuards)

    /** Mode repas manuel déclaré (l'un des six). Même expression que les [MealSafetyContext] du tick. */
    private fun manualMealModeActive(): Boolean =
        mealTime || lunchTime || dinnerTime || snackTime || highCarbTime || bfastTime

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
            criticalSafetyZeroed = criticalSafetyZeroedThisTick,
            contextSuppressSmb = lastContextSuppressSmb,
            mealModeActive = manualMealModeActive(),
        )

    /**
     * Multiplicateur adaptatif conservé quand un plan basal-first (T3C natif / Harmonia production) possède
     * le taux. Historiquement forcé à `1.0`, ce qui jetait la réduction protectrice des learners — le seul
     * amortisseur qui liait encore. Règle pure dans
     * [app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier].
     */
    private fun basalFirstAdaptiveMultiplier(): Double {
        val kept = BasalChannelSafetyGuards.basalFirstAdaptiveMultiplier(
            guardsEnabled = basalChannelSafetyGuardsActive(),
            adaptiveMult = adaptiveMult,
            mealModeActive = manualMealModeActive(),
        )
        if (kept < 1.0) {
            consoleLog.add(
                "🛡️ BASAL_FIRST_GOV: adaptiveMult conservé à ${aimiFmt2(kept)}x (legacy forçait 1.00x)"
            )
        }
        return kept
    }

    private var correctionAggressionDecision: CorrectionAggressionGate.Decision? = null
    private var lastPostHypoDeliveryAuthority: PostHypoDeliveryAuthority.Decision =
        PostHypoDeliveryAuthority.INACTIVE

    /** [ContinuousStateEstimator.runCount] at tick entry, to enforce "observe exactly once per tick". */
    private var raEstimatorRunCountAtTickStart: Long = -1L

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
    private var mealAbsorptionDeltaPrevForTick: Double? = null

    /** Whether [mealAbsorptionDeltaPrevForTick] has been latched this tick — `null` is a valid value. */
    private var mealAbsorptionDeltaPrevLatched: Boolean = false

    /** HTR Ra floor computed this tick, or `null` when none. Reaches the belief tree, not the MPC. */
    private var lastHtrRaFloorMgdlPerMin: Double? = null

    /** True once `finalizeAndCapSMB` has written the tick's dose. See [applySmbUnits]. */
    private var smbTerminalSealed: Boolean = false

    /** Post-seal raises that were refused this tick, and by how much in total. Exported. */
    private var smbSealRefusedCount: Int = 0
    private var smbSealRefusedTotalU: Double = 0.0

    /** Post-seal raises that were allowed because their owner is on the exception list. Exported. */
    private var smbSealAllowedRaiseCount: Int = 0

    /** Units the aggressive-rise floor has contributed to the current rise. See `remainingRiseFloorBudgetU`. */
    private var riseFloorSpentU: Double = 0.0

    /** When the floor last contributed, so a fresh rise can re-arm the budget. */
    private var lastRiseFloorContributionMs: Long = 0L

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
    private var lastSlopeFromMinDeviation: Double? = null
    private var lastShortAvgDeltaAtLadder: Double? = null

    /**
     * Deltas the end-of-tick safety net feeds the estimator with.
     *
     * Seeded from the tick's own fields and overwritten with the exact values once the Autodrive
     * branch runs. On paths that return before that branch, `combinedDelta` was never computed, so
     * the seed is the honest best available — it only drives the process-noise term, not the model.
     */
    private var raNetCombinedDelta: Float = 0.0f
    private var raNetShortAvgDeltaAdj: Float = 0.0f

    /** SMB proposed before the post-hypo cap, for `adjustments.post_hypo_delivery`. Per tick. */
    private var lastPostHypoSmbBeforeCapU: Double? = null

    /** SMB left after the post-hypo cap, for `adjustments.post_hypo_delivery`. Per tick. */
    private var lastPostHypoSmbAfterCapU: Double? = null

    /** Effort SMB multiplier the belief asked for, before the confirmed-meal floor. Per tick. */
    private var lastEffortSmbFactorRaw: Double? = null

    /** Effort SMB multiplier actually applied, after the confirmed-meal floor. Per tick. */
    private var lastEffortSmbFactorApplied: Double? = null

    /** SMB entering the effort reduction, in units. Per tick. */
    private var lastEffortSmbBeforeU: Double? = null

    /** SMB leaving the effort reduction, in units. Per tick. */
    private var lastEffortSmbAfterU: Double? = null

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
    private var mealAdvisorOneShotThisTick: Boolean = false
    private var lastTubeAdvisorSmbCapScale: Double? = null

    /**
     * Inputs and branch of the tube call that set this tick's SMB ceiling. See
     * `AimiDecisionContext.Adjustments.tube_advisor`. Written by `noteTubeAdvisorTrace`, per tick.
     */
    private var lastTubeAdvisorTrace: JsonObject? = null
    private var lastInflammationResult: app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster.InflammationResult? = null
    private var tickInsulinActionState: InsulinActionState? = null
    private var tickEffectiveDiaHours: Double? = null
    private var tickEffectivePeakMinutes: Double? = null
    private var lastDecisionSource: String = "AIMI"
    private var lastSafetySource: String = "NONE"
    private var lastPredictionAvailable: Boolean = false
    private var lastPredictionSize: Int = 0
    private var lastEventualBgSnapshot: Double = 0.0
    private var lastSmbProposed: Double = 0.0
    /** Diagnostic-only immutable SMB cap chain, replaced at every tick bootstrap. */
    private var lastSmbBindingTraceDraft = SmbBindingTrace.Draft()

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
    /** Cross-tick effort-load memory for [EffortActivityBelief]; intentionally NOT reset per tick. */
    private var lastEffortMemory = EffortActivityBelief.Memory()
    private var lastEffortAssessment: EffortActivityBelief.Assessment? = null
    /** Absolute context SMB ceiling (SlowCarbMeal); enforced robustly at [finalizeAndCapSMB]. Per-tick. */
    private var lastContextSmbCeilingU: Double? = null
    /** Hard context SMB-off (HypoRecovery); enforced robustly at [finalizeAndCapSMB]. Per-tick. */
    private var lastContextSuppressSmb: Boolean = false
    /** Cumulative context-SMB budget for a SlowCarbMeal early window (anti-overshoot, Q5). Cross-tick. */
    private val slowCarbEarlyBudgetU = 2.0
    private var slowCarbBudgetWindowMs: Long = 0L
    private var slowCarbBudgetDeliveredU: Double = 0.0
    private var lastPhysioLatentState: PhysioLatentState? = null
    private var lastUamHypothesisState: UamHypothesisState? = null
    private var lastContextSnapshot: ContextSnapshot? = null
    private var lastPatientState: PatientStateSnapshot? = null
    private var lastPatientModeDecision: PatientModeOrchestrator.Decision? = null
    private var lastPhysiologicalTreeSnapshot: PhysiologicalTreeSnapshot? = null

    /** NGR (Night Growth Resistance) basal multiplier for THIS tick (1.0 unless NGR active in its night window).
     *  Set in [refreshPatientStateRuntime]; consumed by [executeT3cBrittleMode] so NGR reaches the T3C basal. */
    private var lastNgrBasalMultiplier: Double = 1.0

    /**
     * Physiological-context feature vector for the basal NN (record + inference), mirroring the SMB feature schema so
     * the basal model shares the tree's physiological view (latent physio + patient-mode + causal) instead of being
     * context-blind. Same order as [BasalNeuralLearner.modelInput] and the CSV physio columns: 4 latent + 3 mode + 3 causal.
     */
    private fun currentBasalPhysioFeatures(): FloatArray =
        SmbRefinementFeatureSchema.latentFeatureValues(lastPhysioLatentState) +
            SmbRefinementFeatureSchema.modeFeatureValues(lastPatientModeDecision) +
            SmbRefinementFeatureSchema.causalFeatureValues(lastPatientState?.causalPosterior)
    private var lastHarmoniaDecision: HarmoniaDecision? = null
    private var lastMealCertainty: MealCertainty? = null
    private var lastHarmonizerOutcome: HarmoniaHarmonizer.Outcome? = null
    private var lastHarmoniaProductionDecision: HarmoniaProductionDecision? = null

    /**
     * Runtime blocker that refused Harmonia on the **previous** tick, and how many ticks in a row the
     * same blocker has refused it.
     *
     * Deliberately **not** reset with the other `last*` fields at the start of a tick: a memory that
     * is cleared every tick is not a memory. Same reason as `lastEffortMemory`, which the reset block
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
    private var tickIobEffectiveU: Double? = null

    /** Post-hypo state ordinal captured this tick (0 None, 1 ReboundSuspected, 2 MealConfirmed). */
    private var lastPostHypoOrdinal: Int = 0

    /** Effective-IOB release decision snapshot for AIMI_Decisions.jsonl. See [resolveIobForGate]. */
    private var lastIobReleaseExport: AimiDecisionContext.IobReleaseExport? = null
    private var lastSmbCapped: Double = 0.0
    private var currentThyroidEffects = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects()
    private var lastSmbFinal: Double = 0.0
    private var lastAutodriveState: AutodriveState = AutodriveState.IDLE
    private var duraISFminutes: Double = 0.0
    private var duraISFaverage: Double = 0.0
    private var iobNet: Double = 0.0 // Corrected IOB for learning
    fun isAutodriveEngaged(): Boolean = lastAutodriveState == AutodriveState.ENGAGED

    // 🛡️ PERSISTENT PREBOLUS LOCKOUT (MTR Safety Patch)
    // Survives instance re-creations and app restarts by combining Memory + SharedPreferences.
        companion object {
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

        // 🏃 Effort → veto de l'interprétation « repas non déclaré » (voir [effortSuppressesUndeclaredMeal]).
        private const val EFFORT_MEAL_SUPPRESS_CONF = 0.30       // confiance mini de la croyance d'effort
        private const val EFFORT_MEAL_SUPPRESS_MAX_COB_G = 12.0  // au-delà = vrai repas (COB) → pas de veto
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
    private val ngrTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var zeroBasalAccumulatedMinutes: Int = 0
    private val MAX_ZERO_BASAL_DURATION = 60  // Durée maximale autorisée en minutes à 0 basal
    private val insulinObserver = app.aaps.plugins.aps.openAPSAIMI.pkpd.RealTimeInsulinObserver()  // 🚀 Real-Time Insulin Observer
    private var pkpdThrottleIntervalAdd: Int = 0       // 🚀 PKPD interval boost (0 si normal/modes repas)
    private var pkpdPreferTbrBoost: Double = 1.0       // 🚀 PKPD TBR boost factor (1.0 si normal/modes repas)

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
            val epochMin = TimeUnit.MILLISECONDS.toMinutes(ctx.nowEpochMillis)
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
        val diaMinutes = tickEffectiveDiaHours?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 60.0).roundToInt() } ?: 300
        val timeToPeakMinutes = tickEffectivePeakMinutes?.takeIf { it.isFinite() && it > 0.0 }?.toInt() ?: 75
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
        this.latestAdjustedDia = out.minutes
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
            explicitMealMode = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime,
            hasRecentMealEstimate = hasRecentMealEstimate,
            isConfirmedHighRise = isConfirmedHighRiseThisTick,
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
            gate = correctionAggressionDecision,
        )
        if (cap.wasCapped) {
            consoleLog.add(
                CorrectionAggressionBasalCap.formatLogLine(
                    source = source,
                    requestedUph = requestedRateUph,
                    result = cap,
                    tier = correctionAggressionDecision?.tier,
                ),
            )
        }
        // 🏃 Activity basal ceiling: during an active exercise / AIMI activity context, insulin sensitivity
        // rises, so the basal must be capped too — not just the SMB. Without this, branches like the post-hypo
        // REBOUND_GUARD TBR bridge or the meal boost concentrate the (SMB-suppressed) correction into a
        // runaway TBR (observed 8–9× profile). Ceiling only (coerceAtMost): never raises a rate, never
        // affects a 0/suspend command. Factor is user-tunable via [DoubleKey.OApsAIMIActivityBasalCapFactor].
        var finalRateUph = cap.cappedRateUph
        if (exerciseInsulinLockoutActive && profileBasalUph > 0.0) {
            val activityFactor = preferences.get(DoubleKey.OApsAIMIActivityBasalCapFactor)
            val activityCapUph = profileBasalUph * activityFactor
            if (finalRateUph > activityCapUph) {
                consoleLog.add(
                    "🏃 ACTIVITY_BASAL_CAP[$source]: %.2f→%.2f U/h (≤ %.2f× profile %.2f)".format(
                        Locale.US, finalRateUph, activityCapUph, activityFactor, profileBasalUph
                    )
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
        correctionAggressionDecision = decision
        CorrectionAggressionGate.appendLogs(decision, input, consoleLog, aapsLogger)
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
            cob = cob.toDouble(),
            postHypoHint = postHypoHint,
        )
        lastPostHypoDeliveryAuthority = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = correctionAggressionDecision,
                patientMode = lastPatientModeDecision?.mode,
                aggressionInput = aggressionInput,
            ),
        )
        if (lastPostHypoDeliveryAuthority.active) {
            consoleLog.add(PostHypoDeliveryAuthority.formatLogLine(lastPostHypoDeliveryAuthority))
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
            cob = cob.toDouble(),
            explicitMealMode = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime,
            shortAvgDelta = shortAvgDeltaAdj,
            delta = delta,
            slopeFromMinDeviation = slopeFromMinDeviation,
            estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs),
            estimatedCarbsAgeMs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong(),
            localHour = hourOfDay,
            reason = reason,
        )
        val postHypoHint = mapPostHypoToAggressionHint(postHypoState)
        val aggressionInputForRefine = buildCorrectionAggressionInput(
            bg = bg,
            targetBg = targetBg.toDouble(),
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            combinedDelta = combinedDelta,
            cob = cob.toDouble(),
            postHypoHint = postHypoHint,
        )
        correctionAggressionDecision = correctionAggressionDecision?.let { prior ->
            CorrectionAggressionGate.refineForPostHypo(
                prior,
                aggressionInputForRefine,
                postHypoHint,
            )
        } ?: CorrectionAggressionGate.evaluate(aggressionInputForRefine)
        correctionAggressionDecision?.let { refined ->
            CorrectionAggressionGate.appendLogs(refined, aggressionInputForRefine, consoleLog, aapsLogger)
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

    private fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    fun round(value: Double): Int {
        // Crash backstop: roundToInt() throws on NaN and saturates at Int.MAX_VALUE on ±Infinity.
        // Substitute 0, but keep the fallback observable by PersistenceLayerImpl's non-finite
        // APS-result diagnostic, so laundering here does not hide the real bug.
        if (!value.isFinite()) {
            consoleError.add("round(): non-finite value substituted with 0 (roundNonFinite=$value)")
            return 0
        }
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
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

    private fun calculateRate(basal: Double, currentBasal: Double, multiplier: Double, reason: String, currenttemp: CurrentTemp, rT: RT, overrideSafety: Boolean = false): Double {
        rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} $reason")
        val rawRate = if (overrideSafety || basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
        return rawRate.coerceAtLeast(0.0)
    }
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
    ): Boolean {
        mealModeSmbReason = null

        // 0) Garde globale
        if (!microBolusAllowed) {
            consoleError.add(rh.gs(ApsStrings.smb_disabled))
            return false
        }

        // 🔒 SAFETY: Hard Floor for SMB. No SMB below 80 mg/dL ever.
        // Even if predicted to rise, we don't SuperBolus a hypo.
        if (currentBg < 80) {
            consoleError.add("SMB disabled: BG ${convertBG(currentBg)} < 80")
            return false
        }

        // 1) Détection meal-rise plus tolérante
        val safeFloor = max(100.0, targetbg - 5.0)
// avant : delta >= 0.3 && currentBg > safeFloor && eventualBg > safeFloor
        val isMealRise = mealModeActive &&
            (delta >= 0.1) &&
            (currentBg > safeFloor)

// 2) Garde high TT : bypass si mode repas actif et pas de risque hypo
        val hypoGuard = HypoThresholdMath.computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = profile.lgsThreshold)
        val mealBypassHighTT = mealModeActive && currentBg > hypoGuard

        if (!profile.allowSMB_with_high_temptarget &&
            profile.temptargetSet && targetbg > 100 &&
            !mealBypassHighTT && !isMealRise
        ) {
            consoleError.add(rh.gs(ApsStrings.smb_disabled_high_target, targetbg))
            return false
        }

        // 3) Enable cases (préférences)
        if (profile.enableSMB_always) {
            consoleLog.add(rh.gs(ApsStrings.smb_enabled_always))
            return true
        }
        if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
            consoleLog.add(rh.gs(ApsStrings.smb_enabled_for_cob, mealData.mealCOB))
            return true
        }
        if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
            consoleLog.add(rh.gs(ApsStrings.smb_enabled_after_carb_entry))
            return true
        }
        if (profile.enableSMB_with_temptarget && profile.temptargetSet && targetbg < 100) {
            consoleLog.add(rh.gs(ApsStrings.smb_enabled_for_temp_target, convertBG(targetbg)))
            return true
        }

        // 4) Enfin, l'exception meal-rise si elle est vraie
        if (mealModeActive) {
            val safeFloorValue = max(100.0, targetbg - 5)
            val risingFast = combinedDelta >= 2.0 || (combinedDelta > 0 && currentBg > 120)

            // 🚀 EXPLOSIVE RISE EXCEPTION: Allow SMB at 90mg/dL if combinedDelta is huge (> 4.0)
            val isExplosive = combinedDelta > 4.0 && currentBg > 90.0

            // Condition assouplie: eventualBg ignoré si montée confirmée
            if ((currentBg > safeFloorValue || isExplosive) && combinedDelta > 0.5 && (eventualBg > safeFloorValue || risingFast || isExplosive)) {
                mealModeSmbReason = rh.gs(
                    ApsStrings.smb_enabled_meal_mode,
                    convertBG(currentBg),
                    combinedDelta,
                    convertBG(eventualBg)
                ) + if (isExplosive) " [🚀 EXPLOSIVE]" else ""
                return true
            }
        }

        consoleError.add(rh.gs(ApsStrings.smb_disabled_no_pref_or_condition))
        return false
    }


    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
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
                wCycle = wCycleInfoForRun,
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
            consoleError.add("setTempBasal: setTempBasalRate=$_rate is not finite, using profile basal instead")
            profile.current_basal
        }
        val lgsPref = profile.lgsThreshold
        val hypoGuard = HypoThresholdMath.computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = lgsPref)
        val floor = PredictiveHypoEvaluator.floor(hypoGuard)
        val effectiveMealContext = mealContext ?: MealSafetyContext(
            mealModeActive = mealTime || lunchTime || dinnerTime || snackTime || highCarbTime || bfastTime,
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
                eventualBg = eventualBG,
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
            bgNow > hypoGuard && bgNow > 110 && detectMealOnset(delta, predDelta, bgacc.toFloat(), predictedBg, profile.target_bg.toFloat())

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
        consoleLog.add(
            "DYNAMIC_BASAL P-Err=${aimiFmt1(dynamicState.errorP)} " +
            "D-Err=${aimiFmt1(dynamicState.errorD)} " +
            "Total=${aimiFmt2(dynamicState.totalError)} " +
            "Mult=${aimiFmt2(dynamicState.sigmoidMultiplier)}x " +
            "Brake=${dynamicState.isBraking}"
        )

        // 🚀 PKPD TBR Boost: Augmenter TBR si preferTbr (sauf modes repas)
        // Note: pkpdPreferTbrBoost est déjà à 1.0 pour les modes repas (via reset dans finalizeAndCapSMB)
        if (pkpdPreferTbrBoost > 1.0 && !isMealMode) {
            val originalRate = rateAdjustment
            rateAdjustment = (rateAdjustment * pkpdPreferTbrBoost).coerceAtLeast(0.0)
            consoleLog.add("PKPD_TBR_BOOST original=${aimiFmt2(originalRate)} boost=${aimiFmt2(pkpdPreferTbrBoost)} → ${aimiFmt2(rateAdjustment)}U/h")
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
        if (rate > 0.0 && Math.abs(adaptiveMultiplier - 1.0) > 0.01) {
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
                    val iobNow = iobNet
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
        val harmoniaOwnedBasal = lastHarmoniaProductionDecision?.selectedForProduction == true
        if (bgNow > hypoGuard && !harmoniaOwnedBasal) {
            val endocrineBasalAmp = EndocrineAmplitudeGovernor.productionAmp(
                lastWCycleBelief,
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
                            "iob" to iob.toDouble(),
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
        } else if (harmoniaOwnedBasal && lastWCycleBelief != null) {
            lastWCycleBelief = lastWCycleBelief?.copy(
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
                eventualBgMgdl = eventualBG.takeIf { it.isFinite() && it > 1.0 },
                deltaMgdl5m = delta.toDouble(),
                iobU = iobNet,
                // A manually declared mode exempts these invariants — that is this module's own
                // stated contract ("Modes repas exclus"), and FCL is a manually declared mode.
                // Leaving it out pinned the FCL basal to the profile rate: the floor raised it to the
                // meal ceiling and postHypoCap pulled it straight back to profile.current_basal a few
                // lines later. Reported from the field 2026-09-17.
                // `isMealMode` itself is deliberately NOT widened: it also builds MealSafetyContext,
                // which loosens an LGS guard, and FCL must not buy a weaker hypo interlock.
                mealModeActive = isMealMode || fclDeclaredThisTick(profile),
                postHypoActive = lastPostHypoDeliveryAuthority.active,
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
            put("eventual_bg_mgdl", eventualBG.takeIf { it.isFinite() && it > 1.0 } ?: AimiJson.NULL)
            put("delta_mgdl_5m", delta.toDouble())
            put("iob_u", iobNet)
            put("meal_mode_active", isMealMode)
            put("post_hypo_active", lastPostHypoDeliveryAuthority.active)
        }.build()
        if (terminal.boundBy != null) {
            consoleLog.add("🔒 BASAL_TERMINAL[${terminal.boundBy}] ${terminal.trace}")
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
        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)
        val latentFeatures = SmbRefinementFeatureSchema.latentFeatureValues(lastPhysioLatentState)
        val modeFeatures = SmbRefinementFeatureSchema.modeFeatureValues(lastPatientModeDecision)
        val causalFeatures = SmbRefinementFeatureSchema.causalFeatureValues(lastPatientState?.causalPosterior)
        val behaviorProfile = behaviorProfileSource.read(preferences)
        val eventMemory = lastPatientState?.eventMemory ?: PatientEventMemory.EMPTY
        val decisionConflictFlags = physioAdapter.getLastDecisionTrace()?.decisionConflictFlags?.joinToString("|").orEmpty()

        // One source of truth for the column order: the writer and the trainer read the same list.
        // Building the header here by hand is what let the file on disk drift away from the rows.
        val headerRow = SmbRefinementFeatureSchema.trainingCsvHeaderLine() + "\n"
        val valuesToRecord = "$dateStr," +
            "$bg,$iob,$cob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "${latentFeatures[0]},${latentFeatures[1]},${latentFeatures[2]},${latentFeatures[3]}," +
            "${modeFeatures[0]},${modeFeatures[1]},${modeFeatures[2]}," +
            "${causalFeatures[0]},${causalFeatures[1]},${causalFeatures[2]}," +
            "${behaviorProfile.protectionLevel},${behaviorProfile.mealCaptureLevel},${behaviorProfile.stabilityLevel}," +
            "${behaviorProfile.physioLevel},${behaviorProfile.autonomyLevel}," +
            "${eventMemory.postHyperExhaustionScore},${eventMemory.correctionFragilityScore},$decisionConflictFlags," +
            "$predictedSMB,$smbToGive," +
            "$peakintermediaire,$latestAdjustedDia"
        // The row is queued, not written. Its origin fields are only complete at the end of the
        // tick, and its realised glucose only about half an hour later, so it leaves the queue once
        // its outcome window has closed. This delays when a row appears in the CSV; it does not
        // change the row itself, the label, or anything the pump is asked to do.
        val nowMs = smbTrainingRowTickKey.takeIf { it > 0L } ?: dateUtil.now()
        smbTrainingRowBuffer.fillRealisedOutcomes(nowMs = nowMs, observedBg = bg)
        smbTrainingRowBuffer.enqueue(timestampMs = nowMs, valuesPrefix = valuesToRecord)
        smbTrainingRowBuffer.drainWritableRows(nowMs).forEach { readyRow ->
            appendCsvSafely(
                primaryFile = csvfile,
                fallbackFileName = "oapsaimiML2_records.csv",
                headerRow = headerRow,
                valuesRow = readyRow,
            )
        }
    }

    private fun logDataToCsv(predictedSMB: Float, smbToGive: Float) {

        val usFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        val dateStr = dateUtil.dateAndTimeString(dateUtil.now()).format(usFormatter)

        val headerRow = "dateStr,hourOfDay,weekend," +
            "bg,targetBg,iob,delta,shortAvgDelta,longAvgDelta," +
            "tdd7DaysPerHour,tdd2DaysPerHour,tddPerHour,tdd24HrsPerHour," +
            "recentSteps5Minutes,recentSteps10Minutes,recentSteps15Minutes,recentSteps30Minutes,recentSteps60Minutes,recentSteps180Minutes," +
            "tags0to60minAgo,tags60to120minAgo,tags120to180minAgo,tags180to240minAgo," +
            "predictedSMB,maxIob,maxSMB,smbGiven,dynamicPeak,adjustedDia\n"
        val valuesToRecord = "$dateStr,$hourOfDay,$weekend," +
            "$bg,$targetBg,$iob,$delta,$shortAvgDelta,$longAvgDelta," +
            "$tdd7DaysPerHour,$tdd2DaysPerHour,$tddPerHour,$tdd24HrsPerHour," +
            "$recentSteps5Minutes,$recentSteps10Minutes,$recentSteps15Minutes,$recentSteps30Minutes,$recentSteps60Minutes,$recentSteps180Minutes," +
            "$tags0to60minAgo,$tags60to120minAgo,$tags120to180minAgo,$tags180to240minAgo," +
            "$predictedSMB,$maxIob,$maxSMB,$smbToGive,$peakintermediaire,$latestAdjustedDia"
        appendCsvSafely(
            primaryFile = csvfile2,
            fallbackFileName = "oapsaimi2_records.csv",
            headerRow = headerRow,
            valuesRow = valuesToRecord,
        )
    }

    private fun appendCsvSafely(
        primaryFile: File,
        fallbackFileName: String,
        headerRow: String,
        valuesRow: String,
    ) {
        runCatching {
            appendCsvToFile(primaryFile, headerRow, valuesRow)
        }.onFailure { primaryError ->
            if (!csvPrimaryStorageDeniedLogged) {
                csvPrimaryStorageDeniedLogged = true
                aapsLogger.warn(
                    LTag.APS,
                    "CSV write denied on shared storage (${primaryFile.absolutePath}). " +
                        "Switching to app-scoped fallback at ${appExternalFallbackFile(fallbackFileName).absolutePath}. " +
                        "Reason=${primaryError.message}",
                )
            }
            runCatching {
                appendCsvToFile(appExternalFallbackFile(fallbackFileName), headerRow, valuesRow)
            }.onFailure { fallbackError ->
                aapsLogger.error(
                    LTag.APS,
                    "CSV write failed on both primary and fallback paths. primary=${primaryFile.absolutePath}, " +
                        "fallback=${appExternalFallbackFile(fallbackFileName).absolutePath}",
                    fallbackError,
                )
            }
        }
    }

    private fun appendCsvToFile(file: File, headerRow: String, valuesRow: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
            file.appendText(headerRow)
        } else {
            ensureCsvHeaderIsCurrent(file, headerRow)
        }
        // The guard in front of the row is what stops it being glued to the last stored one. A clean
        // up used to rewrite the file without a final line break, so the next row written started on
        // the same line and the reader dropped both of them. See [AimiCorpusPruner].
        file.appendText(AimiCorpusPruner.rowPrefix(lastCharOf(file)) + valuesRow + "\n")
    }

    /**
     * The last character stored in [file], or `null` when it is empty or could not be read.
     *
     * Only the last byte is read: this runs on every loop tick, on a file that only grows, so reading
     * the whole thing to look at its end would not scale. Comparing a single byte to a line break is
     * safe on UTF-8 text, because the bytes that make up a multi byte character are never `0x0A`.
     */
    private fun lastCharOf(file: File): Char? {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(length - 1)
                reader.read().takeIf { it >= 0 }?.toChar()
            }
        }.getOrNull()
    }

    /**
     * Makes sure an existing CSV carries the header the writer builds today.
     *
     * The rewrite itself, and why it is safe to replace the first line whatever its old shape, live in
     * [app.aaps.plugins.aps.openAPSAIMI.ml.TrainingCsvHeader]. Here we only add the two things that
     * belong to the running app: the file is checked once per path per app start, and any failure is
     * logged and swallowed, because a header that could not be fixed must never stop a row from being
     * written.
     *
     * ⚠️ ASYNC IMPACT: File I/O on the tick writer thread (same as the previous prefix-only upgrade).
     * The path is remembered after the first check so later ticks do not re-read the whole CSV.
     */
    private fun ensureCsvHeaderIsCurrent(file: File, headerRow: String) {
        if (!csvHeaderCheckedPaths.add(file.absolutePath)) return
        runCatching {
            val outcome = TrainingCsvHeader.ensureCurrent(storage, AimiPath(file.absolutePath), headerRow)
            if (outcome == TrainingCsvHeader.Outcome.REPLACED) {
                aapsLogger.info(LTag.APS, "CSV header replaced in place for ${file.name}")
            }
        }.onFailure { error ->
            aapsLogger.warn(LTag.APS, "CSV header refresh skipped for ${file.name}: ${error.message}")
        }
    }

    /**
     * Where the copy of the training CSV goes before it is rewritten.
     *
     * The name is `backup_yyyyMMdd_HHmmss.csv` next to the file itself, which is the name
     * `AimiRetentionPolicy.DROP_GLOBS` already looks for when it tidies old backups up.
     */
    private fun backupFileFor(csvFile: File): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        return File(csvFile.parentFile, "backup_$timestamp.csv")
    }

    /**
     * Removes the newest 200 lines of the training CSV, after copying the whole file aside.
     *
     * This is the count based clean up, kept for the therapy note trigger, which asks for a deletion
     * without naming a day. The rule itself lives in [AimiCorpusPruner.keepAllButNewest] so it can be
     * tested; the "bad day" trigger uses [removeRowsForDay] instead, which removes the rows of the
     * day the user is told about.
     */
    fun removeLast200Lines(csvFile: File) {
        val reasonBuilder = StringBuilder()
        if (!csvFile.exists()) {
            aapsLogger.info(LTag.APS, rh.gs(ApsStrings.original_file_missing))
            return
        }

        val backupFile = backupFileFor(csvFile)
        val result = AimiCorpusPruner.removeNewest(
            storage = storage,
            csv = AimiPath(csvFile.absolutePath),
            backup = AimiPath(backupFile.absolutePath),
            count = 200,
        )
        when (result.outcome) {
            AimiCorpusPruner.Outcome.REMOVED         ->
                reasonBuilder.append(rh.gs(ApsStrings.last_200_deleted, backupFile.name))

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
    private fun removeRowsForDay(csvFile: File, dayText: String): AimiCorpusPruner.Result {
        if (!csvFile.exists()) {
            aapsLogger.info(LTag.APS, rh.gs(ApsStrings.original_file_missing))
            return AimiCorpusPruner.Result(
                outcome = AimiCorpusPruner.Outcome.FILE_MISSING,
                removedRows = 0,
                keptRows = 0,
            )
        }
        val result = AimiCorpusPruner.removeDay(
            storage = storage,
            csv = AimiPath(csvFile.absolutePath),
            backup = AimiPath(backupFileFor(csvFile).absolutePath),
            dayText = dayText,
        )
        aapsLogger.info(
            LTag.APS,
            "AIMI training CSV clean up for $dayText: ${result.outcome}, " +
                "removed ${result.removedRows} rows, kept ${result.keptRows}",
        )
        return result
    }

    @SuppressLint("StringFormatInvalid")
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

                val result = removeRowsForDay(csvfile, dateToRemove)
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
            postHypo = lastPostHypoDeliveryAuthority,
            uamHypotheses = lastUamHypothesisState,
            mealAbsorption = lastMealAbsorptionOutput,
            rbtHints = lastRbtAppliedHints,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            targetBg = targetBg,
            iob = iob,
            maxIob = maxIob,
            maxSmb = maxSMB,
            maxSmbHb = maxSMBHB,
            memberEventualBg = eventualBG,
            harmoniaDecision = lastHarmoniaDecision,
            harmonizerOutcome = lastHarmonizerOutcome,
            pkpdRuntime = cachedPkpdRuntime,
            sportTime = sportTime,
            lateFatRise = lateFatRiseFlag,
            thyroidEffects = currentThyroidEffects,
            predictionAvailable = lastPredictionAvailable,
            predictionSize = lastPredictionSize,
            lastBolusAgeMinutes = lastBolusAgeMinutes,
            iobActivityNow = iobActivityNow,
            cob = cob,
            effort = lastEffortAssessment,
            mealCertainty = lastMealCertainty,
            physiologicalPhase = lastPhysiologicalPhaseOutput,
            slowCarbBudgetU = slowCarbEarlyBudgetU,
            slowCarbWindowMs = slowCarbBudgetWindowMs,
            slowCarbDeliveredU = slowCarbBudgetDeliveredU,
            ceilingRepeatCount = ceilingRepeatCount,
            ceilingRepeatLastMs = ceilingRepeatLastMs,
            preferences = preferences,
            consoleLog = consoleLog,
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
                override fun diaHoursOrNull(): Double? = tickEffectiveDiaHours
                override fun profileDiaOrNull(): Double? = lastProfile?.dia
                override fun actionStateOrNull(): InsulinActionState? = tickInsulinActionState
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
                    iobActivityNow = iobActivityNow,
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
                    mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime
            },
            rbtMinPred = AimiFinalizeRbtMinPred { raw -> minPredictedBgForRbtWiring(raw) },
            criticalFlag = AimiFinalizeCriticalFlag { criticalSafetyZeroedThisTick },
            contextSmb = object : AimiFinalizeContextSmb {
                override fun suppress(): Boolean = lastContextSuppressSmb
                override fun ceilingU(): Double? = lastContextSmbCeilingU
            },
            slowCarb = AimiFinalizeSlowCarb {
                lastContextSnapshot?.activeIntents
                    ?.filterIsInstance<app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.SlowCarbMeal>()
                    ?.maxByOrNull { it.intensity }
                    ?.takeIf { (dateUtil.now() - it.startTimeMs) < it.absorptionDelay.inWholeMilliseconds }
                    ?.startTimeMs
            },
            thyroidGate = AimiFinalizeThyroid {
                val inputs = thyroidPreferences.inputsFlow.value
                val gatedEffects = thyroidSafetyGates.applyGates(
                    inputs = inputs,
                    effects = currentThyroidEffects,
                    currentBg = bg,
                    bgDelta = delta.toDouble(),
                    currentIob = iob.toDouble(),
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
                pendingDecisionCtxForExport?.baseline_state?.let { baseline ->
                    baseline.rise_ceiling_guard_would_block = block
                    baseline.rise_ceiling_guard_reason = reason
                    baseline.rise_ceiling_guard_repeats = repeats
                    if (block) baseline.rise_ceiling_guard_withheld_u = withheldU
                }
            },
            bindingDraft = AimiFinalizeBindingDraft { lastSmbBindingTraceDraft },
            smbAction = legacySmbAction,
            format2f = { "%2f".format(it) },
        ) ?: return
        lastDecisionSource = side.decisionSource
        lastSmbProposed = side.smbProposed
        pkpdThrottleIntervalAdd = side.pkpdThrottleIntervalAdd
        pkpdPreferTbrBoost = side.pkpdPreferTbrBoost
        slowCarbBudgetWindowMs = side.slowCarbWindowMs
        slowCarbBudgetDeliveredU = side.slowCarbDeliveredU
        ceilingRepeatCount = side.ceilingRepeatCount
        ceilingRepeatLastMs = side.ceilingRepeatLastMs
        lastEffortSmbFactorRaw = side.effortFactorRaw
        lastEffortSmbFactorApplied = side.effortFactorApplied
        lastEffortSmbBeforeU = side.effortBeforeU
        lastEffortSmbAfterU = side.effortAfterU
        lastSmbBindingTraceDraft = side.bindingTrace
        lastSmbCapped = side.smbCapped
        lastSmbFinal = side.smbFinal
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
        if (eventualBG.isFinite() && eventualBG > 20) return eventualBG
        return null
    }

    private fun windowSinceLastPkpdDoseMin(fallbackWindowInt: Int = 0): Double =
        if (lastBolusAgeMinutes.isFinite()) lastBolusAgeMinutes else fallbackWindowInt.toDouble()

    private fun isPkpdAggressivePriorityContext(
        anyMealModeForGuard: Boolean,
        isConfirmedHighRise: Boolean,
        mealAdvisorOneShot: Boolean,
    ): Boolean = mealAdvisorOneShot || anyMealModeForGuard || isConfirmedHighRise

    private fun effectivePkpdGuardFactor(
        guard: PkpdAbsorptionGuard,
        aggressivePriority: Boolean,
    ): Double {
        val pkpdReliefEnabled = preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
        val pkpdReliefMinFactor = preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor).coerceIn(0.50, 1.0)
        return if (aggressivePriority && pkpdReliefEnabled) {
            max(guard.factor, pkpdReliefMinFactor)
        } else {
            guard.factor
        }
    }

    /**
     * Single PKPD absorption-guard multiply per tick. [runPkpdGuardEndoDampenRedCarpetAndCapSmb] and
     * [applySafetyPrecautions] must both use this helper so relief, pred BG, and high-rise flags stay aligned.
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
        if (pkpdAbsorptionGuardAppliedThisTick) {
            return PkpdAbsorptionGuardApplyResult(
                smbOut = smbIn,
                intervalAddMin = 0,
                guard = null,
                effectiveFactor = 1.0,
                multiplicationApplied = false,
                skippedDuplicate = true,
            )
        }

        val guard = PkpdAbsorptionGuard.compute(
            pkpdRuntime = pkpdRuntime,
            windowSinceLastDoseMin = windowSinceLastDoseMin,
            bg = bg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta.toDouble(),
            targetBg = targetBg.toDouble(),
            predBg = resolvePkpdGuardPredBg(),
            isMealMode = anyMealModeForGuard,
            isConfirmedHighRise = isConfirmedHighRise,
        )
        pkpdAbsorptionGuardAppliedThisTick = true

        val aggressivePriority = isPkpdAggressivePriorityContext(
            anyMealModeForGuard = anyMealModeForGuard,
            isConfirmedHighRise = isConfirmedHighRise,
            mealAdvisorOneShot = mealAdvisorOneShot,
        )
        val effectiveFactor = effectivePkpdGuardFactor(guard, aggressivePriority)

        if (!guard.isActive()) {
            return PkpdAbsorptionGuardApplyResult(
                smbOut = smbIn,
                intervalAddMin = 0,
                guard = guard,
                effectiveFactor = effectiveFactor,
                multiplicationApplied = false,
                skippedDuplicate = false,
            )
        }

        val beforeGuard = smbIn
        val smbOut = (smbIn * effectiveFactor.toFloat()).coerceAtLeast(0f)
        if (guard.intervalAddMin > 0) {
            intervalsmb = (intervalsmb + guard.intervalAddMin).coerceAtMost(10)
            if (logChannel == PkpdGuardLogChannel.PIPELINE) {
                consoleLog.add("INTERVAL_ADJUSTED: +${guard.intervalAddMin}m → ${intervalsmb}m total")
            }
        }
        if (smbOut < beforeGuard) {
            when (logChannel) {
                PkpdGuardLogChannel.PIPELINE -> {
                    consoleError.add(guard.toLogString())
                    consoleLog.add("SMB_GUARDED: ${aimiFmt2(beforeGuard)}U → ${aimiFmt2(smbOut)}U")
                    if (aggressivePriority && effectiveFactor > guard.factor) {
                        consoleLog.add(
                            "PKPD_RELIEF: factor ${aimiFmt2(guard.factor)} -> ${aimiFmt2(effectiveFactor)} " +
                                "(meal/advisor/high-rise priority)"
                        )
                    }
                }
                PkpdGuardLogChannel.FINALIZE -> {
                    reason?.appendLine(
                        "🛡️ PKPD Guard (${guard.reason}): ${aimiFmt2(beforeGuard)} → ${aimiFmt2(smbOut)} U"
                    )
                    if (aggressivePriority && effectiveFactor > guard.factor) {
                        consoleLog.add(
                            "PKPD_RELIEF_FINALIZE: factor ${aimiFmt2(guard.factor)} -> ${aimiFmt2(effectiveFactor)} " +
                                "(meal/high-rise priority, finalizeAndCapSMB)"
                        )
                    }
                }
            }
        }

        return PkpdAbsorptionGuardApplyResult(
            smbOut = smbOut,
            intervalAddMin = guard.intervalAddMin,
            guard = guard,
            effectiveFactor = effectiveFactor,
            multiplicationApplied = smbOut < beforeGuard,
            skippedDuplicate = false,
        )
    }

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
        var smbToGive = smbToGiveParam
        val mealWeights = computeMealAggressionWeights(mealData, hypoThreshold)

        val (isCrit, critMsg) = isCriticalSafetyCondition(mealData, hypoThreshold)
        if (isCrit && !ignoreSafetyConditions) {
            criticalSafetyZeroedThisTick = true // sécurité VITALE : interdit le restore Red Carpet ce tick
            reason?.appendLine("🛑 $critMsg → SMB=0")
            consoleLog.add("SMB forced to 0 by critical safety: $critMsg")
            return 0f
        }

        if (isSportSafetyCondition()) {
            if (mealWeights.guardScale > 0.0 && smbToGive > 0f) {
                val before = smbToGive
                smbToGive = (smbToGive * mealWeights.guardScale.toFloat()).coerceAtLeast(0f)
                reason?.appendLine(
                    rh.gs(ApsStrings.reason_safety_sport_meal_reduction, before, smbToGive)
                )
            } else {
                // Sport is a vital safety, not a minor one, so Red Carpet must not put this back.
                // Without this flag the zero below counted as a minor cut: measured 2026-08-18, the
                // sport guard zeroed four ticks between 19:32 and 19:57 and Red Carpet restored
                // 6.52 U, which took BG from 168.8 down to 50.6 after a three hour hike.
                criticalSafetyZeroedThisTick = true
                reason?.appendLine(rh.gs(ApsStrings.safety_sport_smb_zero))
                consoleLog.add("SMB forced to 0 by sport safety guard")
                return 0f
            }
        }
        val wCycleInfo = ensureWCycleInfo()
        if (wCycleInfo != null) {
            val endocrineSmbAmp = EndocrineAmplitudeGovernor.productionAmp(
                lastWCycleBelief,
                EndocrineAmpAxis.SMB,
            )
            if (endocrineSmbAmp != 1.0) {
                val pre = smbToGive
                smbToGive = (smbToGive * endocrineSmbAmp.toFloat()).coerceAtLeast(0f)
                val need = if (pre > 0f) (smbToGive / pre).toDouble() else null
                updateWCycleLearner(null, need)

                val profile = lastProfile
                if (profile != null) {
                    wCycleFacade.infoAndLog(
                        mapOf(
                            "trackingMode" to wCyclePreferences.trackingMode().name,
                            "contraceptive" to wCyclePreferences.contraceptive().name,
                            "thyroid" to wCyclePreferences.thyroid().name,
                            "verneuil" to wCyclePreferences.verneuil().name,
                            "bg" to bg,
                            "delta5" to delta.toDouble(),
                            "iob" to iob.toDouble(),
                            "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                            "isfProfile" to profile.sens,
                            "dynIsf" to variableSensitivity.toDouble(),
                            "needSmbScale" to need,
                            "endocrineSmbAmp" to endocrineSmbAmp,
                        )
                    )
                }
            }
        }
        // Ajustements spécifiques
        val beforeAdj = smbToGive
        smbToGive = applySpecificAdjustments(smbToGive, ignoreSafetyRestrictions = ignoreSafetyConditions)
        if (smbToGive != beforeAdj) {
            //reason?.appendLine("🎛️ Ajustements: ${aimiFmt2(beforeAdj)} → ${aimiFmt2(smbToGive)} U")
            reason?.appendLine(rh.gs(ApsStrings.adjustments_smb, beforeAdj, smbToGive))
        }
        if (mealWeights.active && mealWeights.boostFactor > 1.0 && smbToGive > 0f) {
            val beforeBoost = smbToGive
            smbToGive = (smbToGive * mealWeights.boostFactor.toFloat()).coerceAtLeast(0f)
            reason?.appendLine(
                rh.gs(
                    ApsStrings.reason_meal_aggression_boost,
                    beforeBoost,
                    smbToGive,
                    mealWeights.boostFactor
                )
            )
        }
        // 🛡️ PKPD Guard — shared with [runPkpdGuardEndoDampenRedCarpetAndCapSmb] (once per tick)
        val anyMealModeForGuard = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        val confirmedHighRiseForGuard = isConfirmedHighRise || isConfirmedHighRiseThisTick
        val mealAdvisorForGuard = mealAdvisorOneShotThisTick ||
            preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
        val pkpdGuardApply = applyPkpdAbsorptionGuardOncePerTick(
            smbIn = smbToGive,
            pkpdRuntime = pkpdRuntime,
            windowSinceLastDoseMin = windowSinceLastPkpdDoseMin(),
            anyMealModeForGuard = anyMealModeForGuard,
            isConfirmedHighRise = confirmedHighRiseForGuard,
            mealAdvisorOneShot = mealAdvisorForGuard,
            reason = reason,
            logChannel = PkpdGuardLogChannel.FINALIZE,
        )
        smbToGive = pkpdGuardApply.smbOut
        if (pkpdGuardApply.skippedDuplicate) {
            consoleLog.add("PKPD_GUARD_SKIP_FINALIZE: guard already applied earlier this tick")
        }

        // Finalisation
        val beforeFinalize = smbToGive
        smbToGive = finalizeSmbToGive(smbToGive)
        if (smbToGive != beforeFinalize) {
            //reason?.appendLine("🧩 Finalisation: ${aimiFmt2(beforeFinalize)} → ${aimiFmt2(smbToGive)} U")
            reason?.appendLine(rh.gs(ApsStrings.finalization_smb, beforeFinalize, smbToGive))
        }

        // Limites max
        val beforeLimits = smbToGive
        smbToGive = clampSmbToMaxSmbAndMaxIob(smbToGive, maxSMB, maxIob, iob)
        if (smbToGive != beforeLimits) {
            //reason?.appendLine("🧱 Limites: ${aimiFmt2(beforeLimits)} → ${aimiFmt2(smbToGive)} U")
            reason?.appendLine(rh.gs(ApsStrings.limits_smb, beforeLimits, smbToGive))
        }
        smbToGive = smbToGive.coerceAtLeast(0f)
        return smbToGive
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
    ): Boolean {
        // 1. Slow Creep (Target + 15)
        if (bg <= targetBg + 15) return false

        // 2. Nature of the Drift: Must be Flat or Rising Slow (CONFIRMED BY 15m AVG & DEVIATION)
        // [FIX] Plateau/Hovering Detection with Deep Analysis:
        // - Instant Delta must be > -1.5 (Not falling)
        // - Avg Delta (15m) must be > -1.5 (Sustained not falling)
        // - Both must be < 6.0 (Not a spike)

        if (delta < -1.5 || avgDelta < -1.5) return false // Falling real (instant or trend)
        if (delta > 6.0 || avgDelta > 6.0) return false // Rising fast (Not a creep)

        // 3. Confirmation by MinDeviation (Are we stuck *worse* than IOB allows?)
        // If deviation is positive, it means BG > IOB prediction -> Resistance/Drift
        // If combinedDelta is also weak (-1 to +2), it confirms the "stuck" nature.
        val isStuck = minDeviation > 0 && combinedDelta > -1.0 && combinedDelta < 3.0

        if (!isStuck) {
             // Fallback: If deviation isn't available/positive, ensure delta is strictly flat
             if (delta < -0.5) return false
        }

        // 4. No recent bolus activity (Clean slate)
        if (lastBolusVolume > 0.1) return false

        reason.append("🧹 Drift Terminator: Plateau detected (Δ${aimiFmt1(delta)} Avg${aimiFmt1(avgDelta)} Dev${aimiFmt0(minDeviation)}) -> ENGAGED\n")
        return true
    }

    private fun calculateDynamicMicroBolus(
        isf: Double,
        baseFactor: Double = 20.0,
        reason: StringBuilder,
    ): Double = AimiTickPolicyMath.calculateDynamicMicroBolus(isf, baseFactor, reason)



    private fun isCompressionProtectionCondition(
        delta: Float,
        reason: StringBuilder,
    ): Boolean = AimiTickPolicyMath.isCompressionProtectionCondition(delta, reason)

    // Post-hypo disambiguation ([PostHypoState]); [lastHypoBelow70At] persists for rebound timer.
    private var lastHypoBelow70At: Long = 0L

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
     * Classifie l'état post-hypo et met à jour [lastHypoBelow70At].
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
            lastHypoBelow70At = lastHypoBelow70At,
            now = now,
            uamConfidence = { AimiUamHandler.confidenceOrZero() },
        )
        lastHypoBelow70At = step.lastHypoBelow70At
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
        val localHour = aimiLocalHour()
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
        mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime ||
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
    private fun isMealModeCondition(): Boolean = mealruntime in 0..7 && mealTime
    private fun isbfastModeCondition(): Boolean = bfastruntime in 0..7 && bfastTime
    private fun isbfast2ModeCondition(): Boolean = bfastruntime in 15..29 && bfastTime
    private fun isLunchModeCondition(): Boolean = lunchruntime in 0..7 && lunchTime
    private fun isLunch2ModeCondition(): Boolean = lunchruntime in 15..24 && lunchTime
    private fun isDinnerModeCondition(): Boolean = dinnerruntime in 0..7 && dinnerTime
    private fun isDinner2ModeCondition(): Boolean = dinnerruntime in 15..24 && dinnerTime
    private fun isHighCarbModeCondition(): Boolean = highCarbrunTime in 0..7 && highCarbTime
    private fun isHighCarb2ModeCondition(): Boolean = highCarbrunTime in 15..23 && highCarbTime
    private fun issnackModeCondition(): Boolean = snackrunTime in 0..7 && snackTime
    private fun mealModeRuntimeToNullableMinutes(rt: Long?): Int =
        AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(rt)

    private fun runtimeToMinutes(rt: Long): Int =
        AimiTickPolicyMath.runtimeToMinutes(rt)


    private fun isMealContextActive(mealData: MealData): Boolean {
        val manualFlags = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        val cobActive = mealData.mealCOB > 5.0
        return manualFlags || cobActive
    }

    private fun resolveMealCorrectionContext(
        mealData: MealData,
        bgMgdl: Double = bg,
        deltaMgdlPer5: Double = delta.toDouble(),
        shortAvgDeltaMgdlPer5: Double = shortAvgDelta.toDouble(),
        explicitMealMode: Boolean = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime,
    ): MealCorrectionContextResolver.Output =
        MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = bgMgdl,
                deltaMgdlPer5 = deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = shortAvgDeltaMgdlPer5,
                explicitMealMode = explicitMealMode,
                mealCobG = mealData.mealCOB,
                mealAbsorptionOutput = lastMealAbsorptionOutput,
                hypothesisState = lastUamHypothesisState,
                latentState = lastPhysioLatentState,
                patientModeDecision = lastPatientModeDecision,
                patientState = lastPatientState,
                postHypoDelivery = lastPostHypoDeliveryAuthority,
                harmoniaAction = lastHarmoniaDecision?.action,
                harmoniaEligible = lastHarmoniaDecision?.eligible == true,
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
            cob
        }.toDouble()
        val scan = AimiHypoSmbSafety.criticalConditions(
            AimiHypoSmbSafety.CriticalInput(
                context = AimiHypoSmbSafety.SafetyContext(
                    delta = delta.toDouble(),
                    bg = bg,
                    iob = iob.toDouble(),
                    predictedBg = predictedBg.toDouble(),
                    eventualBG = eventualBG,
                    shortAvgDelta = shortAvgDelta.toDouble(),
                    longAvgDelta = longAvgDelta.toDouble(),
                    fastingTime = fastingTime,
                    iscalibration = iscalibration,
                    targetBg = targetBg.toDouble(),
                    maxSMB = maxSMB,
                    maxIob = maxIob,
                    mealTime = mealTime,
                    bfastTime = bfastTime,
                    lunchTime = lunchTime,
                    dinnerTime = dinnerTime,
                    highCarbTime = highCarbTime,
                    snackTime = snackTime,
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
                hysteresis = AimiHypoSmbSafety.HypoHysteresisState(lastHypoBlockAt, hypoClearCandidateSince),
                nowMs = aimiWallClockMs(),
            ),
        )
        lastHypoBlockAt = scan.hysteresis.lastHypoBlockAt
        hypoClearCandidateSince = scan.hysteresis.hypoClearCandidateSince
        scan.logs.forEach { consoleLog.add(it) }
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
            sportTime = sportTime,
            activityActive = aimiContextActivityActive,
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
                iob = iob.toDouble(),
                maxSmb = maxSMB,
                honeymoon = preferences.get(BooleanKey.OApsAIMIhoneymoon),
                night = preferences.get(BooleanKey.OApsAIMInight),
                currentHour = aimiLocalHour(),
                snackTime = snackTime,
                mealTime = mealTime,
                bfastTime = bfastTime,
                lunchTime = lunchTime,
                dinnerTime = dinnerTime,
                sleepTime = sleepTime,
                highCarbTime = highCarbTime,
                lowCarbTime = lowCarbTime,
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
                pkpdThrottleIntervalAdd = pkpdThrottleIntervalAdd,
                recentSteps5 = recentSteps5Minutes,
                recentSteps30 = recentSteps30Minutes,
                recentSteps180 = recentSteps180Minutes,
                lastSmbTime = lastsmbtime,
            ),
        )
        out.logs.forEach { consoleLog.add(it) }
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
            state = AimiHypoSmbSafety.HypoHysteresisState(lastHypoBlockAt, hypoClearCandidateSince),
        )
        lastHypoBlockAt = step.state.lastHypoBlockAt
        hypoClearCandidateSince = step.state.hypoClearCandidateSince
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
                iob = iob,
                maxSmb = maxSMB,
                currentHour = currentHour,
            ),
        )
    }

    private fun finalizeSmbToGive(smbToGive: Float): Float = AimiHypoSmbSafety.finalizeSmbFloor(
        AimiHypoSmbSafety.SmbFloorInput(
            smbToGive = smbToGive,
            iob = iob,
            bg = bg,
            delta = delta,
            lateFatRise = lateFatRiseFlag,
        ),
    )

    // DetermineBasalAIMI2.kt
    private fun calculateSMBFromModel(reason: StringBuilder? = null): Float {
        val smb = AimiUamHandler.predictSmbUam(
            floatArrayOf(
                hourOfDay.toFloat(), weekend.toFloat(),
                bg.toFloat(), targetBg, iob,
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
    ): Boolean {
        val hoursSinceBolus = lastBolusTimeMs?.let { (nowMs - it) / 3_600_000.0 } ?: Double.POSITIVE_INFINITY
        val rising = delta >= 1.0 && (shortAvgDelta >= 0.5 || longAvgDelta >= 0.3)
        val highish = bg > 130 || predictedBg > 140
        val lowIOB  = iob < maxSMB
        val noMeal  = !(mealFlags.mealTime || mealFlags.bfastTime || mealFlags.lunchTime
            || mealFlags.dinnerTime || mealFlags.highCarbTime)
        return noMeal && hoursSinceBolus in 2.0..7.0 && rising && highish && lowIOB && cob <= 1.0
    }

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
        if (mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime) return false
        val rising = delta >= 1.0f && (shortAvgDelta >= 0.5f || longAvgDelta >= 0.3f)
        val highish = bg > 130.0 || predictedBg > 140.0f
        // The stack floor is a STOCK, homogeneous with IOB. maxSMB is a per bolus cap and was the
        // wrong scale: during the incident IOB was 6 to 10 U against a maxSMB of 0.05 to 1.5.
        // There is no 24h TDD field on this class, only the hourly rate, so rebuild the stock.
        val tdd24hU = tdd24HrsPerHour.toDouble() * 24.0
        val stackFloorU = maxOf(2.0 * basalaimi.toDouble(), 0.15 * tdd24hU, 1.0)
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
            bg.toFloat(), iob, variableSensitivity, cob, normalBgThreshold,
            recentSteps180Minutes, averageBeatsPerMinute.toFloat(), averageBeatsPerMinute10.toFloat(),
            profile.insulinDivisor.toFloat(), recentSteps5Minutes, recentSteps10Minutes
        )
        val baseFeatures = floatArrayOf(
            bg.toFloat(), iob.toFloat(), cob.toFloat(), delta, shortAvgDelta, longAvgDelta,
            tdd7DaysPerHour.toFloat(), tdd2DaysPerHour.toFloat(), tddPerHour.toFloat(), tdd24HrsPerHour.toFloat()
        )
        val features = SmbRefinementFeatureSchema.buildRuntimeFeatures(
            baseFeatures = baseFeatures,
            trendIndicator = trendIndicator.toFloat(),
            physioLatentState = lastPhysioLatentState,
            patientModeDecision = lastPatientModeDecision,
            causalStatePosterior = lastPatientState?.causalPosterior,
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
            normalBgThreshold = normalBgThreshold,
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
    ): PkpdSoftFloorTelemetry {
        val endoEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion)
        val telemetry = PkpdSoftFloorPathMin.fromCurves(
            curves = curves,
            endogenousReversionEnabled = endoEnabled,
        )
        lastPkpdSoftFloorTelemetry = telemetry
        consoleLog.add(PkpdSoftFloorPathMin.formatLogLine(telemetry))
        return telemetry
    }

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
        consoleLog.add("Debug: computePkpdPredictions called with delta=$delta")
        val predictionModulation = PredictionPhysioModulationResolver.resolve(
            fallbackSensitivityMgdlPerU = finalSensitivity,
            pkpdRuntime = pkpdRuntime,
            mealAbsorptionOutput = mealAbsorptionOutput,
            hypothesisState = hypothesisState,
            latentState = latentState,
            uamConfidence = uamConfidence,
        )
        if (!predictionModulation.isNeutral() || predictionModulation.falseMealSuppression) {
            consoleLog.add(PredictionPhysioModulationResolver.formatLogLine(predictionModulation))
        }
        val curves = try {
            AdvancedPredictionEngine.predictCurves(
                currentBG = currentBg,
                iobArray = iobArray,
                finalSensitivity = finalSensitivity,
                cobG = cobG,
                profile = profile,
                delta = delta,
                modulation = predictionModulation,
                endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
                hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
                stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
            )
        } catch (e: Exception) {
            consoleLog.add("Error in AdvancedPredictionEngine: ${e.message}")
            val flat = List(48) { currentBg }
            AdvancedPredictionCurves(flat, flat, flat, flat, flat)
        }
        lastAdvancedPredictionCurves = curves
        val softFloor = recordPkpdSoftFloor(curves)

        val pathBounds = PredictionPathMath.boundsFromPredictions(
            Predictions().apply {
                IOB = curves.iob.map { round(min(401.0, max(39.0, it)), 0).toInt() }
                COB = curves.cob.map { round(min(401.0, max(39.0, it)), 0).toInt() }
                UAM = curves.uam.map { round(min(401.0, max(39.0, it)), 0).toInt() }
                ZT = curves.zt.map { round(min(401.0, max(39.0, it)), 0).toInt() }
            },
        ).let { curveBounds ->
            val rawBounds = PredictionPathMath.boundsFromRawSeries(curves.hybrid)
            PredictionPathBounds(
                pathMinRawMgdl = rawBounds.pathMinRawMgdl,
                pathMinClampedMgdl = curveBounds.pathMinClampedMgdl ?: rawBounds.pathMinClampedMgdl,
                pathMinHitNumericFloor = rawBounds.pathMinHitNumericFloor || curveBounds.pathMinHitNumericFloor,
            )
        }
        fun sanitizeInts(points: List<Double>): List<Int> =
            points.map { round(min(401.0, max(39.0, it)), 0).toInt() }
        val iobInts = applySoftFloorToPredSeries(sanitizeInts(curves.iob), softFloor)
        val cobInts = applySoftFloorToPredSeries(sanitizeInts(curves.cob), softFloor)
        val uamInts = applySoftFloorToPredSeries(sanitizeInts(curves.uam), softFloor)
        val ztInts = applySoftFloorToPredSeries(sanitizeInts(curves.zt), softFloor)
        val hybridInts = sanitizeInts(curves.hybrid)
        rT.predBGs = Predictions().apply {
            IOB = iobInts
            COB = cobInts
            ZT = ztInts
            UAM = uamInts
        }

        val eventual = hybridInts.lastOrNull()?.toDouble() ?: currentBg
        consoleLog.add(
            "PKPD predictions → eventual=${aimiFmt0(eventual)} mg/dL from ${hybridInts.size} steps " +
                "uamT=${uamInts.lastOrNull() ?: "n/a"} " +
                "pathMinRaw=${pathBounds.pathMinRawMgdl?.let { aimiFmt0(it) } ?: "n/a"} " +
                "pathMinClamp=${pathBounds.pathMinClampedMgdl?.let { aimiFmt0(it) } ?: "n/a"}"
        )
        return PredictionResult(eventual, hybridInts, pathBounds)
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
            consoleLog.add("GATE_PKPD_MISSING: injected fallback prediction @${safeBg}mg/dL")
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
        wCycleInfoForRun?.let { return it }
        val info = wCycleFacade.infoAndLog(
            mapOf(
                "trackingMode" to wCyclePreferences.trackingMode().name,
                "contraceptive" to wCyclePreferences.contraceptive().name,
                "thyroid" to wCyclePreferences.thyroid().name,
                "verneuil" to wCyclePreferences.verneuil().name,
                "bg" to bg,
                "delta5" to delta.toDouble(),
                "iob" to iob.toDouble(),
                "tdd24h" to (tdd24HrsPerHour * 24f).toDouble(),
                "isfProfile" to profile.sens,
                "dynIsf" to variableSensitivity.toDouble()
            )
        )
        wCycleInfoForRun = info
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
        if (wCycleReasonLogged) return
        if (info.reason.isBlank()) return
        target.append(", WCycle: ").append(info.reason)
        wCycleReasonLogged = true
    }

    private fun updateWCycleLearner(needBasalScale: Double?, needSmbScale: Double?) {
        val info = wCycleInfoForRun ?: return
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
        peakintermediaire = out.intermediate
        out.logs.forEach { consoleLog.add(it) }
        reasonBuilder.append(out.reason)
        return out.finalPeak
    }

    fun detectMealOnset(delta: Float, predictedDelta: Float, acceleration: Float, predictedBg: Float, targetBg: Float): Boolean {
        // 🏃 Under effort / post-effort adrenaline (undeclared context), a rise is NOT a meal onset → never trigger
        // the undeclared-meal escalation (forced meal TBR, meal-onset SMB). Declared meals keep their own paths.
        if (effortSuppressesUndeclaredMeal()) return false
        val combinedDelta = (delta + predictedDelta) / 2.0f

        // 1. Existing strict check (Explosive Rise)
        if (combinedDelta > 3.0f && acceleration > 1.2f) return true

        // 2. Harmonized check (Steady Meal Rise)
        // Relaxed acceleration req if rise is clearly above noise
        val normalizedRise = ((predictedBg - targetBg) / 70.0f).coerceIn(0.0f, 1.0f)
        if (normalizedRise > 0.3f && combinedDelta > 2.0f && acceleration > 0.3f) return true

        // 3. [FIX] Smart Rise Detection (TIR 70-140)
        // Require acceleration OR sustained high delta, rejecting single-point noise
        val isHighNoise = (delta > 5.0f && acceleration < 0.0f) // Sharp jump but slowing down
        if (!isHighNoise && (combinedDelta > 6.0f || (delta > 5.0f && acceleration > 0.5f))) return true

        return false
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
        val storageReport = storageHelper.getHealthReport()
        val reactivityFactor = safeReactivityFactor // Safety check added
        val basalMultiplier = basalLearner.getMultiplier()

        // Construire le rapport de santé
        val healthLines = listOf(
            "═══════════════════════════════",
            "🛡️ AIMI LEARNERS HEALTH",
            "Storage: $storageReport",
            "UnifiedReactivity: factor=${aimiFmt3(reactivityFactor)}",
            "BasalLearner: multiplier=${aimiFmt3(basalMultiplier)}",
            "PkPdEstimator: runtime-only",
            "═══════════════════════════════"
        )

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
            consoleLog.add(line)
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
                        consoleLog.add("🤰 GESTATION ACTIVE: ${gState.gestationalWeek.toInt()} SA (${gState.description})")
                        consoleLog.add("   └ Factors: Basal x${aimiFmt2(factorBasal)} | ISF x${aimiFmt2(factorISF)} | CR x${aimiFmt2(factorCR)}")
                        consoleLog.add("   └ Adjusted: Basal ${aimiFmt2(oldBasal)}->${aimiFmt2(profile.current_basal)} | ISF ${oldISF.toInt()}->${profile.sens.toInt()}")
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "Error parsing pregnancy due date: $dueDateString", e)
                    }
                } else {
                    consoleLog.add("🤰 PREGNANCY MODE ON but No Due Date set in WCycle prefs.")
                }
            }
        } catch (e: Exception) {
            consoleLog.add("🤰 Error in Gestation logic: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun applyThyroidModule(profile: OapsProfileAimi) {
        this.currentThyroidEffects = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects()
        try {
            thyroidPreferences.update()
            val thyroidInputs = thyroidPreferences.inputsFlow.value
            if (thyroidInputs.isEnabled) {
                thyroidStateEstimator.updateState(thyroidInputs)
                val status = thyroidStateEstimator.currentState.value
                val confidence = thyroidStateEstimator.confidence.value
                currentThyroidEffects = thyroidEffectModel.calculateEffects(status, confidence)

                val logMsg = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidDiagnosticsLogger.formatDecisionLog(
                    inputs = thyroidInputs,
                    status = status,
                    effects = currentThyroidEffects,
                    confidence = confidence,
                    direction = "INIT",
                    reason = ""
                )
                if (logMsg.isNotBlank()) consoleLog.add("🦋 $logMsg")

                if (currentThyroidEffects.diaMultiplier != 1.0) {
                     profile.dia *= currentThyroidEffects.diaMultiplier
                }
                if (currentThyroidEffects.egpMultiplier != 1.0) {
                     profile.current_basal *= currentThyroidEffects.egpMultiplier
                }
                if (currentThyroidEffects.isfMultiplier != 1.0) {
                     profile.sens *= currentThyroidEffects.isfMultiplier
                     profile.variable_sens *= currentThyroidEffects.isfMultiplier
                }
            }
        } catch (e: Exception) {
            consoleLog.add("🦋 Error in Thyroid logic: ${e.message}")
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
        sportTime: Boolean, lateFatRiseFlag: Boolean,
        highCarbrunTime: Long, threshold: Double,
        currentTime: Long, windowSinceDoseInt: Int,
        intervalsmb: Int, insulinStep: Float,
        highBgOverrideUsed: Boolean, cob: Float,
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
                consoleLog = consoleLog, consoleError = consoleError,
                combinedDelta = combinedDeltaLocal.toDouble(), shortAvgDelta = shortAvgDelta.toFloat(), longAvgDelta = longAvgDelta.toFloat(),
                profile = profile, glucoseStatus = glucoseStatusLocal,
                bg = bg, delta = delta.toDouble(), iob = iob,
                basalaimi = basalaimi, initialBasal = basal,
                honeymoon = honeymoon, hourOfDay = hourOfDay,
                mealTime = mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
                dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = snackTime,
                sleepTime = sleepTime,
                recentSteps5Minutes = recentSteps5Minutes, recentSteps10Minutes = recentSteps10Minutes,
                recentSteps30Minutes = recentSteps30Minutes, recentSteps60Minutes = recentSteps60Minutes,
                recentSteps180Minutes = recentSteps180Minutes,
                averageBeatsPerMinute = averageBeatsPerMinute, averageBeatsPerMinute60 = averageBeatsPerMinute60,
                pumpAgeDays = pumpAgeDaysLocal.toInt(),
                sens = sens, tp = tp.toInt(), variableSensitivity = variableSensitivity,
                targetBg = target_bg, predictedBg = predictedBg, eventualBg = eventualBG,
                maxSmb = if (isMealAdvisorOneShot) max(maxSMBHB, 10.0)
                    else if ((bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0) ||
                        ((mealTime || lunchTime || dinnerTime || highCarbTime) && bg > 100)) maxSMBHB
                    else maxSMB,
                maxIob = preferences.get(DoubleKey.ApsSmbMaxIob),
                predictedSmb = predictedSMB, modelValue = modelcalLocal.toFloat(),
                mealData = mealData, pkpdRuntime = pkpdRuntime,
                sportTime = sportTime || exerciseInsulinLockout,
                exerciseInsulinLockout = exerciseInsulinLockout,
                lateFatRiseFlag = lateFatRiseFlag,
                highCarbRunTime = highCarbrunTime, threshold = threshold,
                dateUtil = dateUtil, currentTime = currentTime,
                windowSinceDoseInt = windowSinceDoseInt, currentInterval = intervalsmb,
                insulinStep = insulinStep,
                highBgOverrideUsed = highBgOverrideUsed,
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
            this.maxSMB = 0.0; this.maxSMBHB = 0.0
            val reason = when (decision.reason) {
                BasalFirstPolicyMath.Reason.FRAGILE_BG       -> "Fragile BG (<110 & falling)"
                BasalFirstPolicyMath.Reason.LEARNER_PRUDENCE -> "Learner Prudence (Factor=${aimiFmt2(learnerFactor)})"
                else                                         -> "Unknown Safety Trigger"
            }
            consoleLog.add("🛡️ BASAL-FIRST ACTIVE: $reason -> SMB DISABLED")
            rT.reason.append(" [Basal-First: SMB OFF]")
        } else {
            if (autosensResistance && learnerFactor < 0.75)
                consoleLog.add("⚡ RESISTANCE EXEMPTION: Autosens ${aimiFmt2(autosens_data.ratio)} < 0.8")
            if (isLearnerPrudent && basalFirstMealActive)
                consoleLog.add("🍕 MEAL EXEMPTION: COB=${aimiFmt1(mealData.mealCOB)}g")
            if (isLearnerPrudent && isPersistentRise)
                consoleLog.add("📈 RISE EXEMPTION: CombinedDelta=${aimiFmt1(combinedDelta)}")
            if (isFragileBg && basalFirstHeavyMeal)
                consoleLog.add("🍔 HEAVY MEAL EXEMPTION: COB=${aimiFmt1(mealData.mealCOB)}g")
            if (decision.anticipatedRise) {
                consoleLog.add(
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
            mealTime = mealTime,
            mealRuntimeMin = mealruntime,
            bfastTime = bfastTime,
            bfastRuntimeMin = bfastruntime,
            lunchTime = lunchTime,
            lunchRuntimeMin = lunchruntime,
            dinnerTime = dinnerTime,
            dinnerRuntimeMin = dinnerruntime,
            highCarbTime = highCarbTime,
            highCarbRuntimeMin = highCarbrunTime,
            snackTime = snackTime,
            snackRuntimeMin = snackrunTime,
            fclTime = fclTime,
            sportTime = sportTime,
            fclRuntimeMin = fclruntime,
            iob = iob,
            maxIob = maxIob,
            bg = bg,
            hasHypoRecovery = lastContextSnapshot?.hasHypoRecovery == true,
            postHypo = lastPostHypoDeliveryAuthority,
            lastBolusSmbUnit = lastBolusSMBUnit,
            lastSmbCapped = lastSmbCapped,
            lastSmbFinal = lastSmbFinal,
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
            log = consoleLog,
            state = state,
        )
        lastBolusSMBUnit = state.lastBolusSmbUnit
        lastSmbCapped = state.lastSmbCapped
        lastSmbFinal = state.lastSmbFinal
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
            consoleLog.add("Endo: ${endoFactors.reason} (Basal x${endoFactors.basalMult}, SMB x${endoFactors.smbMult}, ISF x${endoFactors.isfMult})")
            this.variableSensitivity *= endoFactors.isfMult.toFloat()
            this.basalaimi *= endoFactors.basalMult.toFloat()
        }
        this.endoSmbMult = endoFactors.smbMult  // Persist for downstream SMB dampening
        val activityContext = activityManager.process(
            steps5min = recentSteps5Minutes, steps10min = recentSteps10Minutes,
            avgHr = averageBeatsPerMinute, avgHrResting = averageBeatsPerMinute60
        )
        if (activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST || activityContext.isRecovery) {
            consoleLog.add("Activity: ${activityContext.description} → ISF x${aimiFmt2(activityContext.isfMultiplier)}")
        }
        this.variableSensitivity *= activityContext.isfMultiplier.toFloat()
        if (activityContext.protectionMode) consoleLog.add("Activity Protection Mode Active (Recovery/Intense)")
        this.activityProtectionMode = activityContext.protectionMode
        this.activityStateIntense = (activityContext.state == app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE)
        this.cachedActivityContext = activityContext
        val anyMealModeActive = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime
        var basalFactor = when (activityContext.state) {
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST  -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.LIGHT -> 1.0f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.MODERATE -> if (anyMealModeActive) 0.9f else 0.8f
            app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.INTENSE  -> if (anyMealModeActive) 0.8f else 0.6f
        }
        if (exerciseHyperBasalOverrideActive) {
            val strongRise = delta >= 3.0f || tickCombinedDelta >= 3.0f
            val resolved = app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy.resolveBasalFactor(
                basalFactor,
                overrideActive = true,
                strongRise = strongRise,
            )
            if (resolved != basalFactor) {
                consoleLog.add(
                    "🏃 HYPER_EXERCISE_OVERRIDE: basal x${aimiFmt2(basalFactor)} → x${aimiFmt2(resolved)} " +
                        "(BG=${bg.toInt()} Δ=${aimiFmt1(delta)})"
                )
                basalFactor = resolved
            }
        }
        if (basalFactor != 1.0f) {
            this.basalaimi *= basalFactor
            consoleLog.add("Basal Activity Redux: x${aimiFmt2(basalFactor)} -> ${aimiFmt2(this.basalaimi)}U/h")
        }
    }

    private fun refreshAimiContextActivityFlag(nowMs: Long = aimiWallClockMs()) {
        aimiContextActivityActive = false
        if (!preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled)) return
        try {
            val snap = contextManager.getSnapshot(nowMs)
            aimiContextActivityActive = snap.hasActivity && snap.intentCount > 0
        } catch (_: Exception) {
            aimiContextActivityActive = false
        }
    }

    private fun refreshExerciseHyperBasalOverride(profile: OapsProfileAimi) {
        exerciseHyperBasalOverrideActive = false
        if (!exerciseInsulinLockoutActive) return
        val policyInput = app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy.buildInput(
            bgMgdl = bg.toDouble(),
            targetBgMgdl = profile.target_bg,
            highBgPreferenceMgdl = preferences.get(DoubleKey.OApsAIMIHighBg),
            deltaMgdlPer5 = delta.toDouble(),
            shortAvgDeltaMgdlPer5 = shortAvgDelta.toDouble(),
            combinedDeltaMgdlPer5 = tickCombinedDelta.toDouble(),
            thyroidEgpMultiplier = currentThyroidEffects.egpMultiplier,
        )
        exerciseHyperBasalOverrideActive =
            app.aaps.plugins.aps.openAPSAIMI.activity.ExerciseHyperOverridePolicy
                .isHyperRisingDuringExercise(policyInput)
        if (exerciseHyperBasalOverrideActive) {
            consoleLog.add(
                "🏃 HYPER_EXERCISE_OVERRIDE active: BG=${bg.toInt()} Δ=${aimiFmt1(delta)} " +
                    "combΔ=${aimiFmt1(tickCombinedDelta)} thyroidEGP=${aimiFmt2(currentThyroidEffects.egpMultiplier)}"
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
        lastEffortAssessment = null
        // Dependency: under T3C, activity awareness is required for the physio-informed basal (workstream C).
        // Effort protection is reduce-only, so this never adds insulin.
        if (!preferences.get(BooleanKey.OApsAIMIEffortActivityProtection) && !t3cModeEnabled()) return
        val snap = try {
            physioAdapter.getLatestSnapshot()
        } catch (_: Exception) {
            return
        }
        if (!snap.isValid) return // no/stale wearable data → fail open (no reduction)
        val (assessment, memory) = EffortActivityBelief.assess(
            EffortActivityBelief.Inputs(
                nowMs = dateUtil.now(),
                stepsLast5m = snap.stepsLast5m,
                stepsLast15m = snap.stepsLast15m,
                stepsLast60m = snap.stepsLast60m,
                hrAvg15mBpm = snap.hrAvg15m,
                hrRestingBpm = snap.rhrResting,
                hrvDeviationZ = null, // HRV plumbing is a follow-up; steps + HR drive v1
                stressResistanceProb = lastPhysioLatentState?.transientResistanceProb ?: 0.0,
            ),
            lastEffortMemory,
        )
        lastEffortMemory = memory
        lastEffortAssessment = assessment
        if (assessment.smbFactor < 1.0) {
            consoleLog.add(
                "🏃 EFFORT_BELIEF[${assessment.state.name}/${assessment.posture.name}] " +
                    "SMB ×${aimiFmt2(assessment.smbFactor)} (applied at SMB finalize) " +
                    assessment.reasons.joinToString(","),
            )
        }
    }

    /**
     * True when a BG rise should be read as EFFORT / post-effort adrenaline, NOT an undeclared meal. Uses the
     * effort belief's EXERTION posture in ACTIVE **or** RECENT_EFFORT state — its ~120-min memory covers the
     * post-effort adrenaline window (residual adrenaline can push BG up after exercise). Vetoes only the
     * *undeclared* interpretation: a declared meal mode or real COB is still treated as a meal. Fail-safe —
     * this only suppresses an insulin escalation, never adds insulin. See [detectMealOnset] / [inferredMealSafetyIntent].
     */
    private fun effortSuppressesUndeclaredMeal(): Boolean {
        val a = lastEffortAssessment ?: return false
        if (a.posture != EffortActivityBelief.Posture.EXERTION) return false
        if (a.state != EffortActivityBelief.State.ACTIVE && a.state != EffortActivityBelief.State.RECENT_EFFORT) return false
        if (a.confidence < EFFORT_MEAL_SUPPRESS_CONF) return false
        val declaredMeal = mealTime || bfastTime || lunchTime || dinnerTime || snackTime || highCarbTime
        return !declaredMeal && cob.toDouble() < EFFORT_MEAL_SUPPRESS_MAX_COB_G
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
        val a = lastEffortAssessment ?: return true
        return a.state == EffortActivityBelief.State.ACTIVE
    }

    /**
     * Contexte utilisateur (intentions actives) : peut moduler SMB / intervalle / préférence basale vs SMB.
     * @return Surcharge cible glycémique (ex. sport ~150 mg/dL) ou null.
     */
    private fun applyContextModule(
        bg: Double, iob: Double, cob: Double, rT: RT
    ): Double? {
        var contextTargetOverride: Double? = null
        // Reset per tick; set below when a context influence is computed. Enforced at finalizeAndCapSMB.
        lastContextSmbCeilingU = null
        lastContextSuppressSmb = false
        val contextEnabled = preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled)
        if (contextEnabled) {
            try {
                consoleLog.add("═══ CONTEXT MODULE ═══")
                val contextSnapshot = contextManager.getSnapshot(aimiWallClockMs())
                // Keep the fresh snapshot as the tick's source of truth so the meal-priority guards
                // (legacy prebolus / meal advisor) read the same context as the finalize gate.
                lastContextSnapshot = contextSnapshot
                if (contextSnapshot.intentCount > 0) {
                    aimiContextActivityActive = contextSnapshot.hasActivity
                    val modeStr = preferences.get(app.aaps.core.keys.StringKey.ContextMode)
                    val contextMode = when (modeStr) {
                        "CONSERVATIVE" -> app.aaps.plugins.aps.openAPSAIMI.context.ContextMode.CONSERVATIVE
                        "AGGRESSIVE" -> app.aaps.plugins.aps.openAPSAIMI.context.ContextMode.AGGRESSIVE
                        else -> app.aaps.plugins.aps.openAPSAIMI.context.ContextMode.BALANCED
                    }
                    val contextInfluence = contextInfluenceEngine.computeInfluence(
                        snapshot = contextSnapshot, currentBG = bg,
                        iob = iob, cob = cob, mode = contextMode
                    )
                    // Carry protective SMB caps to the universal finalize gate (robust vs upstream maxSMB resets).
                    lastContextSmbCeilingU = contextInfluence.smbCeilingU
                    lastContextSuppressSmb = contextInfluence.suppressSmb
                    consoleLog.add("🎯 Active Contexts: ${contextSnapshot.intentCount}")
                    contextSnapshot.activeIntents.take(3).forEach { intent ->
                        consoleLog.add("  • ${intent::class.simpleName ?: "Unknown"}")
                    }
                    if (kotlin.math.abs(contextInfluence.smbFactorClamp - 1.0f) > 0.05f) {
                        val origMaxSMB = maxSMB
                        maxSMB *= contextInfluence.smbFactorClamp
                        maxSMBHB *= contextInfluence.smbFactorClamp
                        consoleLog.add("  SMB: %.2f→%.2fU (×%.2f)".format(java.util.Locale.US, origMaxSMB, maxSMB, contextInfluence.smbFactorClamp))
                    }
                    if (contextInfluence.extraIntervalMin > 0) {
                        val origInterval = intervalsmb
                        intervalsmb = (intervalsmb + contextInfluence.extraIntervalMin).coerceIn(1, 20)
                        consoleLog.add("  Interval: %d→%dmin (+%d)".format(origInterval, intervalsmb, contextInfluence.extraIntervalMin))
                    }
                    if (contextInfluence.preferBasal && !exerciseHyperBasalOverrideActive) {
                        consoleLog.add("  ⚠️ Prefers TEMP BASAL over SMB (SMB Disabled)")
                        maxSMB = 0.0
                        maxSMBHB = 0.0
                        if (contextSnapshot.hasActivity) {
                            contextTargetOverride = 150.0
                            consoleLog.add("  🎯 Sport Target Override -> 150 mg/dL")
                        }
                    } else if (contextInfluence.preferBasal && exerciseHyperBasalOverrideActive) {
                        consoleLog.add("  🏃 Activity preferBasal skipped (hyper+exercise basal override)")
                    }
                    contextInfluence.reasoningSteps.take(3).forEach { reason ->
                        consoleLog.add("  → $reason")
                    }
                    rT.contextEnabled = true
                    rT.contextIntentCount = contextSnapshot.intentCount
                    rT.contextModulation = contextInfluence.smbFactorClamp.toDouble()
                } else {
                    consoleLog.add("🎯 Context: No active intents")
                    aimiContextActivityActive = false
                    rT.contextEnabled = true
                    rT.contextIntentCount = 0
                }
            } catch (e: Exception) {
                consoleLog.add("⚠️ Context error: ${e.message}")
                aapsLogger.error(LTag.APS, "Context Module failed", e)
                rT.contextEnabled = false
            }
        } else {
            rT.contextEnabled = false
        }
        exerciseInsulinLockoutActive = sportTime || aimiContextActivityActive
        if (exerciseInsulinLockoutActive) {
            maxSMB = 0.0
            maxSMBHB = 0.0
        }
        consoleLog.add("═══════════════════════════════════")
        return contextTargetOverride
    }

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
    ): Double {
        if (!preferences.get(BooleanKey.OApsAIMIUndeclaredCobEnabled)) return 0.0
        // Explicit carbs already drive the curve — do not stack a virtual estimate on top.
        if (declaredOrAdvisorCob > 0.0) return 0.0

        val snapshot = physioAdapter.getLatestSnapshot()
        val hrElevation = (snapshot.hrNow - snapshot.rhrResting)
        val hrInflammationElevated = snapshot.hrNow > 0 && snapshot.rhrResting > 0 && hrElevation >= 15
        val cfrdExacerbationActive =
            preferences.get(BooleanKey.OApsAIMIT3cCfrdMode) &&
                preferences.get(BooleanKey.OApsAIMIT3cCfrdExacerbationMode)
        val tdd24h = resolveTdd24hForExport()

        val result = UndeclaredCobEstimator.estimate(
            UndeclaredCobEstimator.Input(
                estimatedRaMgdlPerMin = continuousStateEstimator.getLastRa(),
                isfMgdlPerU = sens,
                carbRatioGPerU = profile.carb_ratio,
                bgMgdl = bg,
                deltaMgdl5m = delta.toDouble(),
                slopeFromMinDeviation = mealData.slopeFromMinDeviation,
                patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
                tdd24hU = tdd24h,
                stepsLast5m = snapshot.stepsLast5m,
                stepsLast15m = snapshot.stepsLast15m,
                activityDetected = aimiContextActivityActive || snapshot.activityState != "IDLE",
                mealProb = lastPhysioLatentState?.mealProb ?: 0.0,
                falseMealSuppression = lastPhysioLatentState?.falseMealSuppression ?: false,
                exerciseLockoutActive = exerciseInsulinLockoutActive,
                postHypoActive = lastPostHypoDeliveryAuthority.active,
                cfrdExacerbationActive = cfrdExacerbationActive,
                hrInflammationElevated = hrInflammationElevated,
                maxGramsPref = preferences.get(DoubleKey.OApsAIMIUndeclaredCobMaxG),
            )
        )
        consoleLog.add("🍽️ VIRTUAL_COB: ${result.toLogString()}")
        return result.grams
    }

    private fun applyAdvancedPredictions(
        bg: Double, delta: Float, sens: Double,
        iob_data_array: Array<IobTotal>,
        mealData: MealData, profile: OapsProfileAimi, rT: RT
    ) {
        try {
            consoleError.add("🔮 PREDICT INIT: BG=$bg Delta=$delta Sens=${aimiFmt1(sens)} IOB=${iob_data_array.firstOrNull()?.iob}")
            val advisorTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
            val advisorCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
            val isFreshAdvisor = (dateUtil.now() - advisorTime) < 60 * 60000
            val declaredOrAdvisorCob = if (mealData.mealCOB > 0) mealData.mealCOB else if (isFreshAdvisor) advisorCarbs else 0.0
            // 🍽️ Undeclared-meal virtual COB (TBR anticipation only; never SMB). Off by default, and
            // only added on top of declared/advisor COB when no explicit carbs are present.
            val virtualCob = estimateUndeclaredVirtualCob(bg, delta, sens, profile, mealData, declaredOrAdvisorCob)
            val effectiveCOB = declaredOrAdvisorCob + virtualCob
            val curves = AdvancedPredictionEngine.predictCurves(
                currentBG = bg, iobArray = iob_data_array, finalSensitivity = sens,
                cobG = effectiveCOB, profile = profile, delta = delta.toDouble(),
                endogenousReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion),
                hyperReversionEnabled = preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
                stackAwareGuardBEnabled = preferences.get(BooleanKey.OApsAIMIPkpdStackAwareGuardB),
            )
            lastAdvancedPredictionCurves = curves
            val softFloor = recordPkpdSoftFloor(curves)
            fun sanitizeCurve(points: List<Double>): List<Int> =
                points.mapNotNull {
                    if (it.isNaN()) null else round(kotlin.math.min(401.0, kotlin.math.max(39.0, it)), 0).toInt()
                }
            val iobInts = applySoftFloorToPredSeries(sanitizeCurve(curves.iob), softFloor)
            val cobInts = applySoftFloorToPredSeries(sanitizeCurve(curves.cob), softFloor)
            val uamInts = applySoftFloorToPredSeries(sanitizeCurve(curves.uam), softFloor)
            val ztInts = applySoftFloorToPredSeries(sanitizeCurve(curves.zt), softFloor)
            val hybridInts = sanitizeCurve(curves.hybrid)
            val intsPredictions = hybridInts
            lastPredictionSize = intsPredictions.size
            lastPredictionAvailable = intsPredictions.isNotEmpty()
            if (intsPredictions.isNotEmpty()) {
                val lastPred = intsPredictions.last().toDouble()
                val minPred = intsPredictions.minOrNull()?.toDouble() ?: bg
                val uamTerminal = uamInts.lastOrNull()?.toDouble()
                lastEventualBgSnapshot = lastPred
                rT.eventualBG = lastPred
                this.predictedBg = lastPred.toFloat()
                rT.predBGs = Predictions().apply {
                    IOB = iobInts
                    COB = cobInts
                    ZT = ztInts
                    UAM = uamInts
                }
                consoleError.add("🔮 PREDICT GRAPH: IOB=${iobInts.size} COB=${cobInts.size} UAM=${uamInts.size}")
                consoleError.add("minGuardBG ${minPred.toInt()} IOBpredBG ${lastPred.toInt()} UAMterm=${uamTerminal?.toInt() ?: "n/a"}")
                if (uamInts.size < 6) consoleError.add("⚠ WARNING: UAM Series too short (<6) for Graph!")
                consoleLog.add(
                    "PRED_SET size=${intsPredictions.size} eventual=${lastPred.toInt()} min=${minPred.toInt()} " +
                        "uamT=${uamTerminal?.toInt() ?: "n/a"} source=AdvancedCurves",
                )
            } else {
                consoleError.add("🔮 PREDICT WARNING: Empty prediction list returned. Using Fallback.")
                val fallbackList = listOf(bg.toInt(), bg.toInt(), bg.toInt())
                rT.predBGs = Predictions().apply {
                    IOB = fallbackList; COB = fallbackList; ZT = fallbackList; UAM = fallbackList
                }
                rT.eventualBG = bg; this.predictedBg = bg.toFloat()
                consoleLog.add("PRED_SET size=3 eventual=${bg.toInt()} min=${bg.toInt()} source=FallbackBG")
            }
        } catch (e: Exception) {
            consoleError.add("🔮 PREDICT ERROR: ${e.message}")
            e.printStackTrace()
        }
        consoleLog.add("Prédiction avancée avec ISF final de ${aimiFmt1(sens)} (Avancé)")
    }

    private fun applyTrajectoryAnalysis(
        currentTime: Long, bg: Double, delta: Double, bgacc: Double, iobActivityNow: Double,
        iob: Float, insulinActionState: app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState,
        lastBolusAgeMinutes: Double, cob: Float, targetBg: Double, profile: OapsProfileAimi,
        rT: RT, uiInteraction: UiInteraction,
        relevanceScore: Double = 0.0 // 🌀 Relevance from Cosine Gate
    ) {
        val trajectoryFlagEnabled = preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)

        rT.trajectoryRelevanceScore = relevanceScore

        if (trajectoryFlagEnabled) {
            try {
                val effectiveProfileForTrajectory: EffectiveProfile? = effectiveProfileCached(currentTime)
                val trajectoryHistory = trajectoryHistoryCached(
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

                // 2. Run Trajectory Analysis (The "Insight" Gate)
                val stableOrbit = app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit.fromProfile(targetBg, profile.current_basal)
                val traj = trajectoryGuard.analyzeTrajectory(trajectoryHistory, stableOrbit)

                if (traj == null) {
                    consoleLog.add("🌀 Trajectory: ⏳ Warming up (${trajectoryHistory.size}/4 states, need 20min)")
                    rT.trajectoryEnabled = false
                } else {
                    val analysis = traj
                    val statusEmoji = analysis.classification.emoji()
                    val typeDesc = analysis.classification.description()

                    consoleLog.add("🌀 Trajectory: $statusEmoji $typeDesc | κ=${aimiFmt2(analysis.metrics.curvature)} conv=${aimiFmt1(analysis.metrics.convergenceVelocity)} health=${aimiFmt0(analysis.metrics.healthScore*100)}%")

                    // Display Visual Insights
                    val artLines = analysis.classification.asciiArt().split("\n")
                    artLines.forEach { line -> consoleLog.add("  $line") }
                    consoleLog.add("  📊 Metrics: Coherence=${aimiFmt2(analysis.metrics.coherence)} Energy=${aimiFmt1(analysis.metrics.energyBalance)}U Openness=${aimiFmt2(analysis.metrics.openness)}")

                    // 3. Apply Modulation (The "Safety" Gate)
                    // We only modify insulin delivery if relevance is sufficient (> 0.4)
                    val mod = analysis.modulation
                    val uamConfidence = AimiUamHandler.confidenceOrZero()
                    val strongMealRiseContext =
                        bg >= 145.0 &&
                            delta >= 1.8 &&
                            (cob >= 6.0 || uamConfidence >= 0.45)
                    if (relevanceScore > 0.4 && mod.isSignificant()) {
                        val effectiveSmbDamping = if (strongMealRiseContext) {
                            mod.smbDamping.coerceAtLeast(0.70)
                        } else {
                            mod.smbDamping
                        }
                        val effectiveIntervalStretch = if (strongMealRiseContext) {
                            mod.intervalStretch.coerceAtMost(1.10)
                        } else {
                            mod.intervalStretch
                        }
                        if (strongMealRiseContext && (effectiveSmbDamping != mod.smbDamping || effectiveIntervalStretch != mod.intervalStretch)) {
                            consoleLog.add(
                                "  🚀 TRAJ_RELAX meal-rise: SMB×${aimiFmt2(mod.smbDamping)}→${aimiFmt2(effectiveSmbDamping)} " +
                                    "Int×${aimiFmt2(mod.intervalStretch)}→${aimiFmt2(effectiveIntervalStretch)} " +
                                    "(BG=${aimiFmt0(bg)} Δ=${aimiFmt1(delta)} COB=${aimiFmt1(cob)} UAM=${aimiFmt2(uamConfidence)})"
                            )
                        }
                        consoleLog.add("  🎛 Modulation: SMB×${aimiFmt2(effectiveSmbDamping)} Int×${aimiFmt2(effectiveIntervalStretch)} (${mod.reason})")

                        if (kotlin.math.abs(effectiveSmbDamping - 1.0) > 0.05) {
                            val orig = maxSMB
                            maxSMB *= effectiveSmbDamping; maxSMBHB *= effectiveSmbDamping
                            consoleLog.add("    → SMB: ${aimiFmt2(orig)}U → ${aimiFmt2(maxSMB)}U")
                        }
                        if (kotlin.math.abs(effectiveIntervalStretch - 1.0) > 0.05) {
                            val orig = intervalsmb
                            intervalsmb = (intervalsmb * effectiveIntervalStretch).toInt().coerceIn(1, 20)
                            consoleLog.add("    → Interval: ${orig}min → ${intervalsmb}min")
                        }
                        if (kotlin.math.abs(mod.safetyMarginExpand - 1.0) > 0.05) {
                            val origLimit = preferences.get(app.aaps.core.keys.DoubleKey.ApsSmbMaxIob)
                            val floor = if (delta > 0.3) origLimit * 0.5 else 0.0
                            val candidate = maxIob * mod.safetyMarginExpand
                            val beforeMod = maxIob
                            maxIob = max(candidate, floor)

                            if (maxIob < beforeMod) {
                                consoleLog.add("    → MaxIOB Modulation: ${aimiFmt2(beforeMod)}U → ${aimiFmt2(maxIob)}U (Floor=${aimiFmt2(floor)}U)")
                            }
                        }
                    } else if (relevanceScore <= 0.4) {
                        consoleLog.add("  ⏸ Modulation Gated (Relevance ${aimiFmt2(relevanceScore)} <= 0.4)")
                    }

                    // Warning Propagation
                    analysis.warnings.filter { it.severity >= app.aaps.plugins.aps.openAPSAIMI.trajectory.WarningSeverity.HIGH }.forEach { w ->
                        consoleLog.add("  🚨 ${w.severity.emoji()} ${w.message}")
                        if (w.severity == app.aaps.plugins.aps.openAPSAIMI.trajectory.WarningSeverity.CRITICAL) {
                            try {
                                notificationManager.post(
                                    id = app.aaps.core.interfaces.notifications.NotificationId.AUTOMATION_MESSAGE,
                                    text = w.message
                                )
                            } catch (e: Exception) {}
                        }
                    }
                    analysis.predictedConvergenceTime?.let {
                        consoleLog.add("  ⏱ Est. convergence: ${it}min")
                    }

                    // 4. Populate RT for UI (Always if traj exists)
                    rT.trajectoryEnabled = true
                    rT.trajectoryType = analysis.classification.name
                    // Note: rT.trajectoryRelevanceScore is already set to the Cosine relevanceScore at start
                    rT.trajectoryCurvature = analysis.metrics.curvature
                    rT.trajectoryConvergence = analysis.metrics.convergenceVelocity
                    rT.trajectoryCoherence = analysis.metrics.coherence
                    rT.trajectoryEnergy = analysis.metrics.energyBalance
                    rT.trajectoryOpenness = analysis.metrics.openness
                    rT.trajectoryHealth = (analysis.metrics.healthScore * 100).toInt()
                    rT.trajectoryModulationActive = relevanceScore > 0.4 && analysis.modulation.isSignificant()
                    rT.trajectoryWarningsCount = analysis.warnings.size
                    rT.trajectoryConvergenceETA = analysis.predictedConvergenceTime
                }
            } catch (e: Exception) {
                consoleLog.add("🌀 Trajectory: ❌ Error (${e.message})")
                aapsLogger.error(LTag.APS, "Trajectory Guard failed", e)
                rT.trajectoryEnabled = false
            }
        } else {
            consoleLog.add("🌀 Trajectory: ⏸ Disabled")
            rT.trajectoryEnabled = false
        }
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
        if (!mealAbsorptionDeltaPrevLatched) {
            mealAbsorptionDeltaPrevForTick = MealAbsorptionMemory.lastDeltaMgdlPer5
            mealAbsorptionDeltaPrevLatched = true
        }
        return mealAbsorptionDeltaPrevForTick
    }

    internal fun runDetermineBasalTick(ctx: AimiTickContext): RT {
        raEstimatorRunCountAtTickStart = continuousStateEstimator.runCount
        mealAbsorptionDeltaPrevForTick = null
        mealAbsorptionDeltaPrevLatched = false
        lastHtrRaFloorMgdlPerMin = null
        smbTerminalSealed = false
        smbSealRefusedCount = 0
        smbSealRefusedTotalU = 0.0
        smbSealAllowedRaiseCount = 0
        raNetCombinedDelta = shortAvgDelta
        raNetShortAvgDeltaAdj = shortAvgDelta
        smbTrainingRowTickKey = ctx.currentTime
        val result = try {
            val inner = runDetermineBasalTickInner(ctx)
            observeRaIfNotAlreadyRun(
                ctx = ctx,
                combinedDelta = raNetCombinedDelta,
                shortAvgDeltaAdj = raNetShortAvgDeltaAdj,
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
        if (aimiDecisionExportedThisTick) return
        val decisionCtx = pendingDecisionCtxForExport ?: return
        runCatching {
            runAimiSnapshotMedicalJsonAndHormonitorExportStage(
                ctx = ctx,
                profile = ctx.profile,
                decisionCtx = decisionCtx,
                finalResult = finalResult,
                pkpdRuntime = cachedPkpdRuntime,
            )
        }.onFailure { e ->
            consoleError.add("AIMI decision export (early-exit path) failed: ${e.message}")
            aapsLogger.error(LTag.APS, "AIMI decision export (early-exit path) failed", e)
        }
    }

    private fun runDetermineBasalTickInner(ctx: AimiTickContext): RT {
        val profile = ctx.profile
        // Causal censoring of the basal label needs the carbs of THIS tick, and the basal-learning hook
        // runs on paths that have no access to `ctx`. See [basalLearningCobGrams].
        tickCobGrams = ctx.mealData.mealCOB.takeIf { it.isFinite() && it >= 0.0 } ?: Double.NaN
        val (
            originalProfile,
            isExplicitAdvisorRun,
            tdd7P,
            tdd7Days,
        ) = runEarlyDetermineBasalStages(ctx)

        val isConfirmedHighRiseLocal = bootstrapPhysiologyAfterEarlyTick(ctx, tdd7Days)
        isConfirmedHighRiseThisTick = isConfirmedHighRiseLocal

        val (
            decisionCtx,
            rT,
            flatBGsDetected,
        ) = buildDecisionContextInitRtSosAndFlatShadow(ctx)

        val (
            iobTotal,
            iobPeakMinutes,
            iobActivityIn30Min,
            insulinActionState,
        ) = runRealtimePhysioIobProfilerAndInsulinObserver(ctx, decisionCtx)

        val (glucoseStatus, f) = when (val gsOutcome = ensureWCycleAndLoadGlucoseStatusOrAbort(ctx, rT)) {
            is AimiGlucosePackLoadOutcome.Abort -> return gsOutcome.returnValue
            is AimiGlucosePackLoadOutcome.Continue -> gsOutcome.glucoseStatus to gsOutcome.aimiBgFeatures
        }

        val (pumpAgeDays, physioMultipliers) = runT9PhysioEarlyPkpdAndTubeBootstrap(ctx, glucoseStatus, rT, iobTotal)
        var pkpdRuntime = this.cachedPkpdRuntime

        val reasonAimi = StringBuilder()

        val useLegacyDynamics = (pkpdRuntime == null)
        val (combinedDelta, shortAvgDeltaAdj, tp) = runCombinedDeltaByodaAndDynamicPeak(
            ctx,
            glucoseStatus,
            useLegacyDynamics,
            reasonAimi,
        )
        val adBoot = buildPreTherapyAutodriveByodaBootstrap(ctx)
        val isG6Byoda = adBoot.isG6Byoda
        val autodrive = adBoot.autodriveEnabled
        val autodriveDisplay = adBoot.autodriveDisplay

        val (
            honeymoon,
            ngrConfig,
            tir1DAYIR,
            lastHourTIRAbove,
            tirbasal3IR,
            tirbasal3B,
            tirbasal3A,
            tirbasalhAP,
            circadianMinute,
            circadianSecond,
            bgAcceleration,
        ) = runTickClockMaxSmbTirCarbAndGlucoseCopy(ctx, glucoseStatus, rT, combinedDelta)
        val nightbis = when (val therapyGate = runTherapyHydrateClocksAndExerciseLockoutGate(ctx, profile, rT)) {
            is AimiTherapyExerciseGate.ReturnEarly -> return therapyGate.result
            is AimiTherapyExerciseGate.Continue -> therapyGate.nightbis
        }

        refreshPostHypoDeliveryAuthorityForTick(
            combinedDelta = combinedDelta,
            recentBGs = glucoseStatusCalculatorAimi.getRecentGlucose(),
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
            reason = StringBuilder(),
        )

        // Judges the auditor's ISF factor against this tick. It changes nothing here: the value is
        // put on the dose-facing sensitivity later, under its floor. It has to be decided before the
        // target, because the two factors share one budget and the ISF has priority.
        decideAuditorIsfFactorForTick(ctx)

        val activeModeName = when (val mealModesGate = runManualMealModesAfterTherapyGate(ctx, profile, rT)) {
            is AimiManualMealModesGate.ReturnEarly -> return mealModesGate.rT
            is AimiManualMealModesGate.Continue -> mealModesGate.activeModeName
        }

        // 🛡️ T3C BRITTLE MODE BRANCH (Moved here to capture `therapy` variables for Prebolus)
        runT3cBrittleBypassOrReturn(
            ctx = ctx,
            profile = profile,
            rT = rT,
            originalProfile = originalProfile,
            pkpdRuntime = pkpdRuntime,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            physioMultipliers = physioMultipliers,
            insulinActionState = insulinActionState,
        )?.let { t3cResult ->
            // T3C returns early, before the shared decision-export tail (runAimiSnapshotMedicalJsonAndHormonitorExportStage,
            // ~L15442). Run that export here so EVERY T3C tick is recorded in AIMI_Decisions.jsonl (empty JSONL bug).
            // basal-NN learning already ran inside executeT3cBrittleMode (govTag "T3C") — do NOT re-run it (no logDecisionFinal).
            runCatching {
                runAimiSnapshotMedicalJsonAndHormonitorExportStage(ctx, profile, decisionCtx, t3cResult, pkpdRuntime)
            }.onFailure { aapsLogger.error(LTag.APS, "T3C decision export failed", it) }
            return t3cResult
        }

        val spSignalPkpd = when (
            val outcome = runSignalPreparationPkpdRuntimePhase(
                ctx = ctx,
                profile = profile,
                rT = rT,
                glucoseStatus = glucoseStatus,
                combinedDelta = combinedDelta,
                tdd7P = tdd7P,
                isExplicitAdvisorRun = isExplicitAdvisorRun,
                isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
                pkpdRuntimeIn = pkpdRuntime,
            )
        ) {
            is AimiSignalPreparationPkpdOutcome.StaleAbort -> return outcome.rT
            is AimiSignalPreparationPkpdOutcome.Continue -> outcome.data
        }
        val (
            modesCondition,
            pbolusAS,
            pbolusA,
            reason,
            recentBGs,
            totalBolusLastHour,
            autosensRatio,
            iob_data,
            lastBolusTimeMs,
            lateFatRiseFlag,
            tdd24Hrs,
            minAgo,
            windowSinceDoseInt,
            pkpdRuntimeSignal,
        ) = spSignalPkpd
        pkpdRuntime = pkpdRuntimeSignal
        val (systemTime, bgTime) = ctx.currentTime to glucoseStatus.date

        val (
            sensInit,
            baseSensitivity,
            contextTargetOverride,
            _, // dynamicPbolusLarge — classic autodrive removed; Large-tier prebolus no longer consumed here
            dynamicPbolusSmall,
        ) = runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep(
            ctx = ctx,
            profile = profile,
            rT = rT,
            iobData = iob_data,
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
        )
        var sens = sensInit

        val wearableSnapshot = try {
            physioAdapter.getLatestSnapshot()
        } catch (_: Exception) {
            HealthContextSnapshot()
        }
        val (sanity, minBg, threshold, scenario) = runAdvancedPredictionsAndPredPipePrep(
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
            iobData = iob_data,
            stepsLast15m = wearableSnapshot.stepsLast15m,
            heartRateBpm = wearableSnapshot.hrNow,
            restingHeartRateBpm = wearableSnapshot.rhrResting,
            combinedDelta = combinedDelta,
        )

        when (
            val safetyGate = runPredPipelineSafetyHaltOrReturn(
                ctx = ctx,
                profile = profile,
                rT = rT,
                bg = bg,
                delta = delta,
                combinedDelta = combinedDelta,
                iobData = iob_data,
                glucoseStatus = glucoseStatus,
                scenario = scenario,
                isExplicitAdvisorRun = isExplicitAdvisorRun,
            )
        ) {
            is AimiPredPipelineSafetyGate.Halt -> return safetyGate.rT
            AimiPredPipelineSafetyGate.Continue -> Unit
        }

        // PRIORITY 3: Meal Advisor (after safety — see [runMealAdvisorDecisionOrReturn])
        val hasRecentBolus45m = hasReceivedRecentBolus(45, lastBolusTimeMs ?: 0L)
        runMealAdvisorDecisionOrReturn(
            ctx = ctx,
            profile = profile,
            rT = rT,
            bg = bg,
            delta = delta,
            iobData = iob_data,
            modesCondition = modesCondition,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            lastBolusTimeMs = lastBolusTimeMs,
            autodriveDisplay = autodriveDisplay,
            hasRecentBolus45m = hasRecentBolus45m,
        )?.let { return it }

        runHardBrakeLyraOrReturn(
            ctx = ctx,
            profile = profile,
            rT = rT,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            targetBgMgdl = targetBg,
        )?.let { return it }

        val (
            postHypoState,
            estimatedCarbs,
            estimatedCarbsTime,
        ) = runPostAutodrivePostHypoClassification(
            recentBGs = recentBGs,
            cob = cob,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            delta = delta,
            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            reason = reason,
        )
        refreshPostHypoDeliveryAuthorityForTick(
            combinedDelta = combinedDelta,
            recentBGs = recentBGs,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            slopeFromMinDeviation = ctx.mealData.slopeFromMinDeviation,
            reason = reason,
        )

        // Cascade D4: publish gated dose terminals BEFORE RBT/V3 so stacking/HTR/Tube see
        // Authority+Clamp truth (not raw PKPD floor). Late PKPD stage re-publishes to refine.
        val earlyPkpdEventual =
            this.eventualBG.takeIf { it.isFinite() && it > 1.0 }
                ?: rT.eventualBG?.takeIf { it.isFinite() && it > 1.0 }
                ?: bg
        val earlyPkpdMinPred = minPredictedAcrossCurves(rT.predBGs) ?: earlyPkpdEventual
        publishDoseTerminalAuthorityAndSnapshot(
            rT = rT,
            profile = profile,
            mealData = ctx.mealData,
            pkpdEventualMgdl = earlyPkpdEventual,
            pkpdPredTerminalMgdl = earlyPkpdMinPred,
            targetBgMgdl = targetBg.toDouble(),
            stageTag = "pre_rbt",
        )

        val tdd24hForRbt = resolveTdd24hForExport()
            ?: tdd24Hrs.takeIf { it > 0f }?.toDouble()
            ?: (profile.max_daily_basal * 24.0).coerceAtLeast(1.0)
        if (!preferences.get(BooleanKey.OApsAIMIautoDriveActive)) {
            resolveAndWireRbtLiveTick(
                ctx = ctx,
                profile = profile,
                rT = rT,
                combinedDelta = combinedDelta,
                tdd24hU = tdd24hForRbt,
                v3SmbU = 0.0,
                stepsLast15m = wearableSnapshot.stepsLast15m,
                heartRateBpm = wearableSnapshot.hrNow,
            )
        }

        // Autodrive V3 — see [runAutodriveV3MultiVariableBranch]
        val v3Branch = runAutodriveV3MultiVariableBranch(
            ctx = ctx,
            profile = profile,
            rT = rT,
            bg = bg,
            combinedDelta = combinedDelta,
            shortAvgDeltaAdj = shortAvgDeltaAdj,
            hypoThresholdMgdl = threshold,
            pkpdRuntime = pkpdRuntime,
        )
        val skipLegacySmbBlender = v3Branch.skipLegacySmbBlender
        if (preferences.get(BooleanKey.OApsAIMIautoDriveActive) && !rbtResolvedThisTick) {
            resolveAndWireRbtLiveTick(
                ctx = ctx,
                profile = profile,
                rT = rT,
                combinedDelta = combinedDelta,
                tdd24hU = tdd24hForRbt,
                v3SmbU = 0.0,
                stepsLast15m = wearableSnapshot.stepsLast15m,
                heartRateBpm = wearableSnapshot.hrNow,
            )
        }
        applyPendingTrajSpiralBasalIfNotSuppressed(rT = rT, bg = bg, delta = delta)

        runPostHypoCompressionAndDriftTerminatorOrReturn(
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
            autodriveEnabledPref = autodrive,
            modesCondition = modesCondition,
            hasRecentBolus45m = hasRecentBolus45m,
            totalBolusLastHour = totalBolusLastHour,
            dynamicPbolusSmall = dynamicPbolusSmall,
            exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
            reason = reason,
        )?.let { return it }

        val (
            pumpCaps,
            profile_current_basal,
            basalScheduleBasal,
            basalScheduleTargetBg,
            basalScheduleMinBg,
            basalScheduleMaxBg,
            basalScheduleSensitivityRatio,
            deliverAt,
            basalScheduleMaxIobLimit,
        ) = buildGlobalAimiBasalScheduleBootstrap(
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
        var basal = basalScheduleBasal
        var target_bg = basalScheduleTargetBg
        // The target the engine really works with, before any later adjustment. Observation only:
        // the auditor is told this level next to the profile target. A tick that ends earlier never
        // reaches this line, and the field then stays null, which is what the JSONL reports.
        auditorProfileTick.workingTargetMgdl = target_bg
        // The target decision itself is NOT taken here: it needs the ISF factor's final, floor-
        // adjusted effective value for the combined bound (see the call below,
        // `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity` is where that floor is decided), and
        // that runs later in this same tick. `target_bg`, `threshold` and `minBg` are read there, not
        // reassigned between here and there, so capturing `workingTargetMgdl` this early is still the
        // pre-adjustment value the spec asks for.
        var min_bg = basalScheduleMinBg
        var max_bg = basalScheduleMaxBg
        var sensitivityRatio = basalScheduleSensitivityRatio
        var maxIobLimit = basalScheduleMaxIobLimit

        val (tick, minDelta, minAvgDelta) = runPostBasalBootstrapIobTickStepsAndHeartRate(
            glucoseStatus = glucoseStatus,
            profile = profile,
            iobData = iob_data,
            bg = bg,
        )

        val (timenow, sixAMHour, pregnancyEnable) = runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf(
            glucoseStatus = glucoseStatus,
            profile = profile,
            profileCurrentBasal = profile_current_basal,
            bg = bg,
            delta = delta,
            tdd7Days = tdd7Days,
            tdd7P = tdd7P,
            paiBaseSensitivity = sens,
            honeymoon = honeymoon,
            tirbasal3B = tirbasal3B,
            tirbasal3IR = tirbasal3IR,
            tirbasal3A = tirbasal3A,
            tirbasalhAP = tirbasalhAP,
            lastHourTIRAbove = lastHourTIRAbove,
            iobPeakMinutes = iobPeakMinutes,
            iobActivityIn30Min = iobActivityIn30Min,
            iobActivityNow = iobActivityNow,
        )

        // Endo + activity (ActivityManager); then ISF bounds and physio multipliers.
        applyEndoAndActivityAdjustments(
            bg = bg, delta = delta,
            mealTime = mealTime, bfastTime = bfastTime, lunchTime = lunchTime,
            dinnerTime = dinnerTime, highCarbTime = highCarbTime, snackTime = snackTime,
            recentSteps5Minutes = recentSteps5Minutes, recentSteps10Minutes = recentSteps10Minutes,
            averageBeatsPerMinute = averageBeatsPerMinute.toDouble(), averageBeatsPerMinute60 = averageBeatsPerMinute60
        )

        sens = applyIsfBoundsAndPhysioMultipliersAfterEndoActivity(
            profile = profile,
            physioMultipliers = physioMultipliers,
            exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
        )
        // Judges the auditor's target factor now, not at the basal-schedule bootstrap: the combined
        // bound (`AuditorProfileFactorGate.combinedTargetBudgetMgdl`) needs the ISF factor's final
        // effective value, after the stress floor of `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity`
        // has had its say — the ISF is applied first and has priority, so the target must see what is
        // really left, not the pre-floor request. `target_bg`, `threshold` and `minBg` are unchanged
        // since the basal-schedule bootstrap above, so this is still the tick's raw working target.
        decideAuditorTargetFactorForTick(
            ctx = ctx,
            workingTargetRawMgdl = target_bg,
            hypoThresholdMgdl = threshold,
            minPredBgMgdl = minBg,
        )
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
        val (
            bgi,
            deviation,
            pkpdTargetsMinBg,
            pkpdTargetsTargetBg,
            pkpdTargetsMaxBg,
        ) = runPkpdPredictionsBgiDeviationAndNoisyTargetsStage(
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
            minBg = min_bg,
            targetBg = target_bg,
            maxBg = max_bg,
        )
        min_bg = pkpdTargetsMinBg
        target_bg = pkpdTargetsTargetBg
        max_bg = pkpdTargetsMaxBg
        val modelcal = runUamModelCalHypoGuardPostHypoAndSetPredictedSmb(
            rT = rT,
            bg = bg,
            delta = delta,
            iob = iob,
            predictedBg = predictedBg,
            eventualBg = eventualBG,
            threshold = threshold,
            minBgHypoComposite = minBg,
            targetBg = target_bg,
            profile = profile,
            postHypoState = postHypoState,
            cob = cob,
        )

        // Detailed logging, meal-advisor one-shot prefs/SMB caps, PKPD DIA override, execute SMB instruction
        val (smbExecution, isMealAdvisorOneShot) = runSmbDecisionLogAdvisorOneShotAndExecuteInstruction(
            ctx = ctx,
            profile = profile,
            rT = rT,
            glucoseStatus = glucoseStatus,
            bg = bg,
            delta = delta,
            iob = iob,
            shortAvgDelta = shortAvgDelta,
            predictedBg = predictedBg,
            eventualBG = eventualBG,
            sens = sens,
            tp = tp,
            variableSensitivity = variableSensitivity,
            // The only SMB site that sees the auditor's target ratio. Every target-relative guard
            // above still reads the raw `target_bg`.
            targetBg = auditorDoseTarget(target_bg, AuditorProfileFactorCodes.DOSE_SITE_SMB),
            basalaimi = basalaimi,
            basal = basal,
            honeymoon = honeymoon,
            hourOfDay = hourOfDay,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            sportTime = sportTime,
            lateFatRiseFlag = lateFatRiseFlag,
            highCarbrunTime = highCarbrunTime,
            threshold = threshold,
            windowSinceDoseInt = windowSinceDoseInt,
            intervalsmb = intervalsmb,
            pumpCaps = pumpCaps,
            highBgOverrideUsed = highBgOverrideUsed,
            cob = cob,
            pkpdRuntime = pkpdRuntime,
            pumpAgeDays = pumpAgeDays,
            modelcal = modelcal,
            profileCurrentBasal = profile_current_basal,
            isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
            exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
            combinedDelta = combinedDelta.toFloat(),
            skipLegacySmbBlender = skipLegacySmbBlender,
            minBgLookbackMgdl = minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
        )

        var smbToGive = applySmbAdvisorExecutionToTickStateAndLog(smbExecution) { basal = it }

        // 🛡️ PKPD ABSORPTION GUARD + endo dampen + red carpet / capSmbDose — see [runPkpdGuardEndoDampenRedCarpetAndCapSmb]
        val (pkpdGuardSmbToGive, pkpdGuardIntervalsmb) = runPkpdGuardEndoDampenRedCarpetAndCapSmb(
            ctx = ctx,
            rT = rT,
            pkpdRuntime = pkpdRuntime,
            smbExecution = smbExecution,
            isExplicitAdvisorRun = isExplicitAdvisorRun,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            isConfirmedHighRiseLocal = isConfirmedHighRiseLocal,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            predictedBg = predictedBg,
            eventualBG = eventualBG,
            targetBg = target_bg,
            honeymoon = honeymoon,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            windowSinceDoseInt = windowSinceDoseInt,
            intervalsmb = intervalsmb,
            smbToGive = smbToGive,
            iob = iob,
        )
        smbToGive = pkpdGuardSmbToGive
        intervalsmb = pkpdGuardIntervalsmb
        snapshotRtResetEnactmentFieldsRestorePredictionsAndPriorityCommands(
            rT = rT,
            deliverAt = deliverAt,
            targetBg = target_bg,
            sensitivityRatio = sensitivityRatio,
            variableSensitivity = variableSensitivity,
            bg = bg,
        )

        val (basalBoostApplied, basalBoostSource) = when (
            val stage = runMealHyperBasalBoostTickStage(
                ctx = ctx,
                profile = profile,
                rT = rT,
                basal = basal,
                profileCurrentBasal = profile_current_basal,
                isMealAdvisorOneShot = isMealAdvisorOneShot,
                targetBg = target_bg,
                estimatedCarbs = estimatedCarbs,
                estimatedCarbsTimeMs = estimatedCarbsTime,
            )
        ) {
            is AimiMealHyperBasalBoostTickResult.CompleteLoop -> return stage.rT
            is AimiMealHyperBasalBoostTickResult.ContinueWithOverlay ->
                applyMealHyperBasalBoostOverlayIfNeeded(stage.overlayRate, deliverAt, rT)
        }

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

        val (csf, slopeFromDeviations) = runWCycleIcCsfClampCiAndCarbImpactLogs(
            profile = profile,
            ctx = ctx,
            sens = sens,
            baseSensitivity = baseSensitivity,
            minDelta = minDelta,
            bgi = bgi,
            sensitivityRatio = sensitivityRatio,
        )

        val (
            forcedBasalmealmodes,
            forcedBasal,
            enableSMB,
            mealModeActive,
            zeroSinceMin,
            minutesSinceLastChange,
            safetyDecision,
        ) = when (
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
                iob = iob,
                cob = cob,
                delta = delta,
                eventualBG = eventualBG,
                combinedDelta = combinedDelta,
                deviation = deviation,
                bgi = bgi,
                targetBgSchedule = target_bg,
                maxBgSchedule = max_bg,
                windowSinceDoseInt = windowSinceDoseInt,
            )
        ) {
            is AimiCarbsAdvisorHardHypoBasalGateResult.ReturnZeroTempBasal -> return gate.rT
            is AimiCarbsAdvisorHardHypoBasalGateResult.Continue -> gate.stage
        }

        // NGR / repas 0–30 + headroom — see [runPostSafetyMealFirst30NgrHeadroomBasalSmbStage]
        var isMealActive = false
        var runtimeMinValue = Int.MAX_VALUE
        when (
            val mealNgr = runPostSafetyMealFirst30NgrHeadroomBasalSmbStage(
                profile = profile,
                ctx = ctx,
                rT = rT,
                ngrConfig = ngrConfig,
                safetyDecision = safetyDecision,
                forcedBasalmealmodes = forcedBasalmealmodes,
                maxIobLimitIn = maxIobLimit,
                basalIn = basal,
                smbToGiveIn = smbToGive,
                bg = bg,
                delta = delta,
                shortAvgDelta = shortAvgDelta,
                longAvgDelta = longAvgDelta,
                eventualBG = eventualBG,
                targetBgSchedule = target_bg,
            )
        ) {
            is AimiPostSafetyMealNgrStageResult.EarlyTempBasal -> return mealNgr.rt
            is AimiPostSafetyMealNgrStageResult.Continue -> {
                isMealActive = mealNgr.isMealActive
                runtimeMinValue = mealNgr.runtimeMinValue
                maxIobLimit = mealNgr.maxIobLimit
                basal = mealNgr.basal
                smbToGive = mealNgr.smbToGive
            }
        }
        // MAX_IOB gate — see [runCoreDecisionMaxIobExceededTempBasalGate]
        val (allowMealHighIob, mealHighIobDamping) = when (
            val maxIobGate = runCoreDecisionMaxIobExceededTempBasalGate(
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
                targetBgSchedule = target_bg,
                loopIob = iob_data.iob,
            )
        ) {
            is AimiCoreDecisionMaxIobGateResult.ReturnTempBasal -> return maxIobGate.rt
            is AimiCoreDecisionMaxIobGateResult.ContinueSMBPath -> maxIobGate
        }

        runInsulinReqActivityRelaxAndMicrobolusStage(
            ctx = ctx,
            rT = rT,
            iobTotal = iob_data,
            smbToGive = smbToGive,
            allowMealHighIob = allowMealHighIob,
            mealHighIobDamping = mealHighIobDamping,
            maxIobLimit = maxIobLimit,
            safetyDecision = safetyDecision,
            enableSMB = enableSMB,
            isMealActive = isMealActive,
            bg = bg,
            delta = delta,
            hypoThresholdMgdl = threshold,
            systemTime = systemTime,
            basalBoostApplied = basalBoostApplied,
            basalBoostSource = basalBoostSource,
        )

        // BasalDecisionEngine: [targetBg] = membre instance (objectif loop / temp target), pas le local [target_bg] (bande schedule) — même contrat qu’avant extraction orchestration.
        val basalDecision = runBasalDecisionEngineDecideStage(
            AimiBasalDecisionEngineStageBundle(
                ctx = ctx,
                profile = profile,
                rT = rT,
                glucoseStatus = glucoseStatus,
                featuresCombinedDelta = f?.combinedDelta,
                profileCurrentBasal = profile_current_basal,
                basalEstimate = basalaimi.toDouble(),
                tdd7P = tdd7P,
                tdd7Days = tdd7Days,
                variableSensitivity = variableSensitivity.toDouble(),
                predictedBg = predictedBg.toDouble(),
                // The only basal site that sees the auditor's target ratio. The member `targetBg`
                // itself is never reassigned, so the learners and the ML CSV keep the raw value.
                targetBg = auditorDoseTarget(targetBg.toDouble(), AuditorProfileFactorCodes.DOSE_SITE_BASAL),
                tickIobForEngine = iob.toDouble(),
                engineMaxIob = maxIob,
                eventualBg = eventualBG,
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
            )
        )

        // Learners post-moteur, TBR final, comparator, instrumentation RT, auditor — see [runPostBasalEngineLearnersRtInstrumentationAndAuditorStage]
        val finalResult = runPostBasalEngineLearnersRtInstrumentationAndAuditorStage(
            AimiPostBasalEngineFinalizeBundle(
                ctx = ctx,
                profile = profile,
                originalProfile = originalProfile,
                rT = rT,
                basalDecision = basalDecision,
                flatBGsDetected = flatBGsDetected,
                pkpdRuntime = pkpdRuntime,
                tdd7Days = tdd7Days,
                intervalsmb = intervalsmb,
            )
        )

        // 🏥 AIMI SNAPSHOT + JSONL + EXPORT — see [runAimiSnapshotMedicalJsonAndHormonitorExportStage]
        runAimiSnapshotMedicalJsonAndHormonitorExportStage(
            ctx = ctx,
            profile = profile,
            decisionCtx = decisionCtx,
            finalResult = finalResult,
            pkpdRuntime = pkpdRuntime,
        )

        return finalResult
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
    @SuppressLint("NewApi", "DefaultLocale") fun determine_basal(
        glucose_status: GlucoseStatusAIMI, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfileAimi, autosens_data: AutosensResult, mealData: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean, uiInteraction: UiInteraction,
        pkpd_iob_data_array: Array<IobTotal>? = null,
        effective_dia_hours: Double? = null,
        effective_peak_minutes: Double? = null,
        extraDebug: String = ""
    ): RT {
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
                AimiLoopTickRecovery.safeResultAfterUnhandledError(ctx, error, consoleLog, consoleError)
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

    /** Console: DECISION_FINAL line + [lastSmbFinal]; no ML side effects. */
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
        consoleLog.add(builder.toString())

        val modeLabel = when {
            mealTime -> "Meal"
            lunchTime -> "Lunch"
            dinnerTime -> "Dinner"
            highCarbTime -> "HighCarb"
            snackTime -> "Snack"
            else -> "None"
        }
        val predSize = rT.predBGs?.IOB?.size ?: lastPredictionSize
        val predAvailable = predSize > 0 || lastPredictionAvailable
        val eventual = (rT.eventualBG ?: lastEventualBgSnapshot)
        val bgValue = bg ?: this.bg
        val deltaValue = delta?.toDouble() ?: this.delta.toDouble()
        val refractoryStatus = if (!lastBolusAgeMinutes.isNaN() && lastBolusAgeMinutes < intervalsmb) "YES" else "NO"
        val smbFinalValue = if (smbUnits > 0.0) smbUnits else smb
        lastSmbFinal = smbFinalValue
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
        val eventual = (rT.eventualBG ?: lastEventualBgSnapshot)
        val windowBolusU = basalLearningBolusUnits(rT)
        val windowCobG = basalLearningCobGrams(rT)
        basalNeuralLearner.updateLearning(
            bgBefore = bg,
            bgAfter = eventual,
            basalDelivered = tbrUph,
            targetBg = targetBg.toDouble(),
            accel = bgacc,
            duraISFminutes = duraISFminutes,
            duraISFaverage = duraISFaverage,
            iob = iobNet,
            loopDeltaMgDl5m = delta.toDouble(),
            sensorNoise = lastLoopCgmNoise,
            shortMinPredBg = minPredictedAcrossCurves(rT.predBGs),
            physioFeatures = currentBasalPhysioFeatures(),
            bolusInsulinU = windowBolusU,
            cobGrams = windowCobG,
        )
        triggerBasalMlTrainingIfNeeded()
        val gov = basalNeuralLearner.getGovernanceSnapshot()
        consoleLog.add(
            "🧭 BASAL_GOV[$govTag]: action=${gov.action} conf=${aimiFmt2(gov.confidence)} " +
                "n=${gov.sampleCount} hypo=${aimiFmt2(gov.hypoRate)} hypoG=${aimiFmt2(gov.hypoRateGovernance)} " +
                "hypoAdj=${aimiFmt2(gov.hypoGovernanceAdjusted)} ant=${aimiFmt2(gov.anticipationRelief)} " +
                "wMean=${aimiFmt2(gov.meanGovernanceWeight)} high=${aimiFmt2(gov.highRate)} " +
                "mae=${aimiFmt1(gov.meanAbsTargetError)} latch=${gov.hypoHoldLatched} " +
                "floorB=${gov.activeBasalFloor?.let { aimiFmt2(it) } ?: "-"} " +
                "floorA=${gov.activeAggressivenessFloor?.let { aimiFmt2(it) } ?: "-"} " +
                "wBolus=${if (windowBolusU.isFinite()) aimiFmt2(windowBolusU) else "?"}U " +
                "wCob=${if (windowCobG.isFinite()) aimiFmt0(windowCobG) else "?"}g " +
                "reason=${gov.reason}"
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
        tickCobGrams.takeIf { it.isFinite() && it >= 0.0 }?.let { return it }
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
            "TICK ts=${aimiWallClockMs()} bg=${diag.bgValue.roundToInt()} d=${aimiFmt1(diag.deltaValue)} iob=${aimiFmt2(iob)} act=${aimiFmt3(iobActivityNow)} th=${aimiFmt3(activityThreshold)} " +
                "cob=${aimiFmt1(cob)} mode=${diag.modeLabel} autodriveState=$lastAutodriveState pred=${diag.predChunk} " +
                "safety=$lastSafetySource ref=${diag.refractoryStatus} maxIOB=${aimiFmt2(maxIob)} maxSMB=${aimiFmt2(maxSMB)} " +
                "smb=${aimiFmt2(lastSmbProposed)}->${aimiFmt2(lastSmbCapped)}->${aimiFmt2(diag.smbFinal)} " +
                "tbr=${aimiFmt2(diag.tbrUph)} src=$lastDecisionSource"
        consoleLog.add(tickLine)
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
                iobLedgerU = iob.toDouble(),
                iobEffectiveU = tickIobEffectiveU,
                postHypoAuthorityActive = lastPostHypoDeliveryAuthority.active,
                postHypoStateOrdinal = lastPostHypoOrdinal,
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
            gate_flips_block_to_allow = decision.iobLedgerU > maxIob && decision.iobForGateU <= maxIob,
            reason = decision.reasonTag,
        )
        if (decision.releasedU > 0.0) {
            consoleLog.add(EffectiveIobReleaseAuthority.formatLogLine(decision))
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
            mealModeActive = mealTime || lunchTime || dinnerTime || snackTime || highCarbTime || bfastTime,
            manualBolusAgeMin = manualBolusAgeMin,
            mealAdvisorCarbsFresh = advisorFresh,
            explicitMealTrigger = isExplicitAdvisorRun,
            inferredMealSignal = inferredMealSafetyIntent(),
        )
    }

    private fun inferredMealSafetyIntent(): Boolean {
        val postHypo = lastPostHypoDeliveryAuthority
        if (postHypo.active && postHypo.forceMealInterpretationSuppressed) return false
        // 🏃 Effort / post-effort adrenaline rise is not a meal (undeclared context only).
        if (effortSuppressesUndeclaredMeal()) return false

        val patientState = lastPatientState
        val patientModeDecision = lastPatientModeDecision
        val falseMealSuppression =
            lastUamHypothesisState?.suppressMealInterpretation == true ||
                patientState?.falseMealSuppression == true
        if (falseMealSuppression) return false

        val phaseEvidence = when (lastMealAbsorptionOutput?.phase) {
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
        return lastMealAbsorptionOutput?.mealDeliveryPriority == true ||
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
        lastSafetySource = "CALLED"
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
        resolution.consoleLines.forEach { consoleLog.add(it) }
        lastSafetySource = resolution.lastSafetySource
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
                 consoleLog.add("⚡ COB HYDRATION: Injected ${fallbackCarbs.toInt()}g from Advisor Prefs (DB latency bypass)")
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
                hour = hourOfDay,
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
            consoleLog.add("👻 [T3c_SHADOW] DataLake tick fired for V3 ML continuity.")
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
                !(mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime || hasRecentMealEstT3c)
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
                isNight = hourOfDay >= 23 || hourOfDay < 6,
                hour = hourOfDay,
                steps = snapshot.stepsLast15m,
                hr = snapshot.hrNow,
                rhr = snapshot.rhrResting,
                sourceSensor = ctx.glucoseStatus.sourceSensor,
                maxIOB = this.maxIob,
                maxSMB = this.maxSMB,
                highBgMaxSMB = this.maxSMBHB,
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
                hour = hourOfDay,
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
        exerciseInsulinLockoutActive = exerciseInsulinLockoutActive,
        exerciseBasalResumeBgMgdl = EXERCISE_BASAL_RESUME_BG_MGDL,
        adaptiveMult = adaptiveMult,
        tree = lastPhysiologicalTreeSnapshot,
        effortSmbFactor = lastEffortAssessment?.smbFactor,
        ngrBasalMultiplier = lastNgrBasalMultiplier,
        preferences = preferences,
        consoleLog = consoleLog,
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
