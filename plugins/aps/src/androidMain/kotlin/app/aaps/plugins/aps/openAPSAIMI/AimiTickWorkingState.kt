package app.aaps.plugins.aps.openAPSAIMI

/**
 * Tick-local scratch for one `determine_basal` execution.
 *
 * Created fresh at the start of every tick and never read by the next tick.
 * Fields moved here from `DetermineBasalAIMI2` are pure working state:
 * BG/delta, IOB/COB, predictions, meal flags, SMB seal counters, console buffers.
 * CACHE, EFFECT and LEARNER_STATE fields stay in the owning class.
 *
 * See `_docs/kmp/annex-8-state-replay-and-extraction-contract.md`, lot E2.
 */
class AimiTickWorkingState {
    var lastPostHypoDeliveryAuthority: PostHypoDeliveryAuthority.Decision =
        PostHypoDeliveryAuthority.INACTIVE
    var mealModeSmbReason: String? = null
    var consoleError = mutableListOf<String>()
    var consoleLog = mutableListOf<String>()
    var bgacc = 0.0
    var predictedSMB = 0.0f
    var eventualBG = 0.0
    var iob = 0.0f
    var cob = 0.0f
    var tags0to60minAgo = ""
    var lastDecisionPredictionAuthority: DecisionPredictionAuthority? = null
    var lastPredictionAuthorityApplyResult: PredictionAuthorityApplyResult? = null
    var tubeDoseBaseline: TubeDoseBaseline? = null
    var tubeAppliedFromDoseSnapshotThisTick: Boolean = false
    var lastAdvancedPredictionCurves: AdvancedPredictionCurves? = null
    var lastSafetyTerminalsForRbt: SafetyPredictionTerminals? = null
    var lastHyperTrajectoryRelease: HyperTrajectoryReleaseResult? = null
    var lastRecursiveBeliefSnapshot: RecursiveBeliefSnapshot? = null
    var lastRecursiveAuthorityGateDecision: RecursiveBeliefAuthorityGate.Decision? = null
    var lastT3cHistoricalBypassNeutralizedThisTick: Boolean = false
    var lastT3cRuntimeOwnership: AimiDecisionContext.T3cRuntimeOwnershipExport? = null
    var lastRbtChaosEvaluation: RbtChaosEvaluator.Result? = null
    var lastRbtAppliedHints: RbtResolutionBridge.AppliedHints? = null
    var rbtResolvedThisTick = false
    var lastRbtLiveCommitResult: RbtLiveCommitResult? = null
    var currentTickDecisionEventId: String? = null
    var lastLoadGovernorMultiplierG: Double = 1.0
    var lastPhysiologicalPhaseOutput: PhysiologicalPhaseClassifier.Output? = null
    var lastPhysiologicalPatternSnapshot: PhysiologicalPatternSnapshot? = null
    var lastMealAbsorptionOutput: MealAbsorptionPhaseEngine.Output? = null
    var lastInsulinStackingEvaluation: InsulinStackingStance.Evaluation? = null
    var lastBasePhysioMultipliers: PhysioMultipliersMTR = PhysioMultipliersMTR.NEUTRAL
    var lastFusedPhysioMultipliers: PhysioMultipliersMTR? = null
    var lastScenarioBestCappedForPhysio: Boolean = false
    var pendingTrajSpiralBasal: PendingTrajSpiralBasal? = null
    var tags60to120minAgo = ""
    var tags120to180minAgo = ""
    var tags180to240minAgo = ""
    var wCycleInfoForRun: WCycleInfo? = null
    var wCycleReasonLogged: Boolean = false
    var normalBgThreshold = 110.0f
    var lastsmbtime = 0
    var acceleratingUp: Int = 0
    var decceleratingUp: Int = 0
    var acceleratingDown: Int = 0
    var decceleratingDown: Int = 0
    var stable: Int = 0
    var maxIob = 0.0
    var maxSMB = 0.5
    var maxSMBHB = 0.5
    var lastBolusSMBUnit = 0.0f
    var hourOfDay: Int = 0
    var weekend: Int = 0
    var basalaimi = 0.0f
    var endoSmbMult = 1.0  // Written by applyEndoAndActivityAdjustments each cycle
    var activityProtectionMode = false  // Written by applyEndoAndActivityAdjustments
    var activityStateIntense = false     // Written by applyEndoAndActivityAdjustments
    var aimilimit = 0.0f
    var ci = 0.0f
    var sleepTime = false
    var sportTime = false
    var aimiContextActivityActive = false
    var exerciseInsulinLockoutActive = false
    var exerciseHyperBasalOverrideActive = false
    var tickCombinedDelta = 0f
    var snackTime = false
    var lowCarbTime = false
    var mealTime = false
    var fastingTime = false
    var stopTime = false
    var iscalibration = false
    var mealruntime: Long = 0
    var bfastruntime: Long = 0
    var lunchruntime: Long = 0
    var dinnerruntime: Long = 0
    var highCarbrunTime: Long = 0
    var snackrunTime: Long = 0
    var peakintermediaire = 0.0
    var latestAdjustedDia: Double = 0.0 // Captured for logging
    var insulinPeakTime = 0.0
    var iobActivityNow: Double = 0.0
    var lastBolusAgeMinutes: Double = Double.NaN
    var pkpdAbsorptionGuardAppliedThisTick: Boolean = false
    var isConfirmedHighRiseThisTick: Boolean = false
    var criticalSafetyZeroedThisTick: Boolean = false
    var tickCobGrams: Double = Double.NaN
    var aimiDecisionExportedThisTick: Boolean = false
    var pendingDecisionCtxForExport: AimiDecisionContext? = null
    var basalChannelGuardBlockedT3cCount: Int = 0
    var basalChannelGuardBlockedHarmoniaCount: Int = 0
    var correctionAggressionDecision: CorrectionAggressionGate.Decision? = null
    var raEstimatorRunCountAtTickStart: Long = -1L
    var mealAbsorptionDeltaPrevForTick: Double? = null
    var mealAbsorptionDeltaPrevLatched: Boolean = false
    var lastHtrRaFloorMgdlPerMin: Double? = null
    var smbTerminalSealed: Boolean = false
    var smbSealRefusedCount: Int = 0
    var smbSealRefusedTotalU: Double = 0.0
    var smbSealAllowedRaiseCount: Int = 0
    var lastSlopeFromMinDeviation: Double? = null
    var raNetCombinedDelta: Float = 0.0f
    var raNetShortAvgDeltaAdj: Float = 0.0f
    var lastPostHypoSmbBeforeCapU: Double? = null
    var lastPostHypoSmbAfterCapU: Double? = null
    var lastEffortSmbFactorRaw: Double? = null
    var lastEffortSmbFactorApplied: Double? = null
    var lastEffortSmbBeforeU: Double? = null
    var lastEffortSmbAfterU: Double? = null
    var mealAdvisorOneShotThisTick: Boolean = false
    var lastTubeAdvisorSmbCapScale: Double? = null
    var lastInflammationResult: app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster.InflammationResult? = null
    var tickInsulinActionState: InsulinActionState? = null
    var tickEffectiveDiaHours: Double? = null
    var tickEffectivePeakMinutes: Double? = null
    var lastDecisionSource: String = "AIMI"
    var lastSafetySource: String = "NONE"
    var lastPredictionAvailable: Boolean = false
    var lastPredictionSize: Int = 0
    var lastEventualBgSnapshot: Double = 0.0
    var lastSmbProposed: Double = 0.0
    var lastSmbBindingTraceDraft = SmbBindingTrace.Draft()
    var lastEffortAssessment: EffortActivityBelief.Assessment? = null
    var lastContextSmbCeilingU: Double? = null
    var lastContextSuppressSmb: Boolean = false
    var lastContextSnapshot: ContextSnapshot? = null
    var lastPatientState: PatientStateSnapshot? = null
    var lastPatientModeDecision: PatientModeOrchestrator.Decision? = null
    var lastPhysiologicalTreeSnapshot: PhysiologicalTreeSnapshot? = null
    var lastNgrBasalMultiplier: Double = 1.0
    var lastHarmoniaDecision: HarmoniaDecision? = null
    var lastMealCertainty: MealCertainty? = null
    var lastHarmonizerOutcome: HarmoniaHarmonizer.Outcome? = null
    var lastHarmoniaProductionDecision: HarmoniaProductionDecision? = null
    var tickIobEffectiveU: Double? = null
    var lastSmbCapped: Double = 0.0
    var currentThyroidEffects = app.aaps.plugins.aps.openAPSAIMI.physio.thyroid.ThyroidEffects()
    var lastSmbFinal: Double = 0.0
    var duraISFminutes: Double = 0.0
    var duraISFaverage: Double = 0.0
    var iobNet: Double = 0.0 // Corrected IOB for learning
    var pkpdThrottleIntervalAdd: Int = 0       // 🚀 PKPD interval boost (0 si normal/modes repas)
    var pkpdPreferTbrBoost: Double = 1.0       // 🚀 PKPD TBR boost factor (1.0 si normal/modes repas)
}
