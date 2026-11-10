package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskPhase
import app.aaps.plugins.aps.openAPSAIMI.risk.IobDecisionSource
import app.aaps.plugins.aps.openAPSAIMI.risk.SafetyPredictionTerminals
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioContributor
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioContributorId
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType

/** Shared fixtures for RBT unit tests. */
object RecursiveBeliefMr7TestHelper {

    fun minimalCtx(
        v3Smb: Double = 0.0,
        bestTerminal: Double = 200.0,
        floorTerminal: Double = 80.0,
        bg: Double = 226.0,
        delta: Double = 2.5,
        iob: Double = 8.8,
        maxIob: Double = 12.0,
        tier1Hypo: Boolean = false,
        dwellMin: Int = 30,
        replaceHtrRelease: Boolean = false,
        hypoMinPredIgnored: Boolean = true,
        behavioralRisk: BehavioralRiskPolicy? = null,
        extended: RbtExtendedSignals = RbtExtendedSignals.EMPTY,
    ): RecursiveBeliefTickContext {
        val hybrid = listOf(120.0, 150.0, bestTerminal)
        val floorHybrid = listOf(floorTerminal, floorTerminal, floorTerminal)
        val curves = app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves(
            iob = hybrid,
            cob = hybrid,
            uam = hybrid,
            zt = hybrid,
            hybrid = hybrid,
        )
        val scenario = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair(
            clinicalFloor = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve.fromRawPoints(
                app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind.CLINICAL_FLOOR,
                floorHybrid,
            ),
            scenarioBest = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve.fromRawPoints(
                app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind.SCENARIO_BEST,
                hybrid,
            ),
            contributors = emptyList(),
            cobPointsMgdl = emptyList(),
            ztPointsMgdl = emptyList(),
            trajectoryType = TrajectoryType.OPEN_DIVERGING.name,
        )
        return RecursiveBeliefTickContext(
            bgMgdl = bg,
            targetBgMgdl = 100.0,
            highBgPreferenceMgdl = 180.0,
            deltaMgdlPer5 = delta,
            shortAvgDeltaMgdlPer5 = delta * 0.9,
            combinedDeltaMgdlPer5 = delta,
            iobU = iob,
            maxIobU = maxIob,
            maxSmbEffectiveU = 5.0,
            tdd24hU = 40.0,
            patientWeightKg = 70.0,
            curves = curves,
            scenario = scenario,
            mealAbsorption = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.85,
                reason = "test",
                deltaMgdlPer5 = delta,
                gapMgdl = 175.0,
                bestTerminalMgdl = bestTerminal,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.8,
                kineticScore = 0.9,
                trajectoryScore = 0.85,
                physioScore = 0.5,
            ),
            physioPhase = null,
            behavioralRisk = behavioralRisk,
            trajectoryAnalysis = null,
            trajectoryRelevanceScore = 0.7,
            safetyTerminals = null,
            riskEnvelope = null,
            stackingStance = null,
            uamConfidence = 0.5,
            contextSmbFactor = 1.0f,
            contextActivityActive = false,
            stepsLast15m = 0,
            heartRateBpm = 0,
            isNight = false,
            exerciseLockout = false,
            hypoMinPredIgnored = hypoMinPredIgnored,
            minPredictedBgMgdl = floorTerminal,
            dwellAboveHighBgMinutes = dwellMin,
            trajBridgePending = false,
            tubeAdvisorCapScale = null,
            v3SmbU = v3Smb,
            htrResult = null,
            htrClassification = null,
            tier1Hypo = tier1Hypo,
            replaceHtrRelease = replaceHtrRelease,
            extended = extended,
        )
    }

    /** Rich tick context for §15 adapter coverage tests. */
    fun coverageCtx(): RecursiveBeliefTickContext {
        val scenarioContributors = listOf(
            ScenarioContributorId.TRAJECTORY_RISE,
            ScenarioContributorId.TRAJECTORY_SPIRAL_DAMP,
            ScenarioContributorId.TRAJECTORY_CONVERGENCE,
            ScenarioContributorId.MEAL_CONTEXT,
            ScenarioContributorId.MEAL_ADVISOR_COB,
            ScenarioContributorId.ACTIVITY_PROTECTION,
            ScenarioContributorId.PHYSIO_REACTIVITY,
            ScenarioContributorId.PHYSIOLOGICAL_PHASE,
            ScenarioContributorId.CONTEXT_MODULE,
            ScenarioContributorId.TARGET_BLEND,
            ScenarioContributorId.INSULIN_SLOPE_RESTORE,
        ).map { ScenarioContributor(id = it, summary = "coverage", terminalDeltaMgdl = 8.0) }
        val hybrid = listOf(120.0, 150.0, 320.0)
        val floorHybrid = listOf(80.0, 85.0, 90.0)
        val curves = app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves(
            iob = hybrid,
            cob = hybrid,
            uam = hybrid,
            zt = hybrid,
            hybrid = hybrid,
        )
        val scenario = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair(
            clinicalFloor = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve.fromRawPoints(
                app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind.CLINICAL_FLOOR,
                floorHybrid,
            ),
            scenarioBest = app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve.fromRawPoints(
                app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind.SCENARIO_BEST,
                hybrid,
            ),
            contributors = scenarioContributors,
            cobPointsMgdl = hybrid.map { it.toInt() },
            ztPointsMgdl = hybrid.map { it.toInt() },
            trajectoryType = TrajectoryType.OPEN_DIVERGING.name,
        )
        val extended = RbtExtendedSignals(
            tubeAdvisorCapScale = 0.85,
            insulinActivityStageOrdinal = 1,
            pkpdTailFactor = 0.9,
            compressionImpossibleRise = true,
            decisionModulationFactor = 0.92,
            highBgOverrideActive = true,
            iobConsensusDelta = 0.15,
            realtimeIobUnits = 8.5,
            mealAdvisorEstimateU = 0.8,
            mpcFeedForwardRa = 1.5,
            cbfShieldDeltaU = 0.2,
            t3cAnticipationStrength = 0.6,
            postHypoOrdinal = 1,
            trajSpiralCapMaxSmb = 2.0,
            pkpdLearnedDiaH = 5.0,
            pkpdLearnedPeakMin = 75.0,
            isfFusionRatio = 1.05,
            kalmanIsf = 42.0,
            kalmanIsfTrust = 0.8,
            peakBiasMinutes = 5,
            peakMismatchScore = 0.2,
            pkpdTailDamping = 0.9,
            correctionAggressionLevel = 1.0,
            hormonalCapApplied = true,
            ngrSmbMult = 1.05,
            ngrBasalMult = 1.02,
            inflammationSmbMult = 0.95,
            inflammationIsfMult = 0.98,
            basalAdaptMult = 1.03,
            dynBasalFactor = 1.01,
            ctxManagerIntentCount = 2,
            endometriosisFactor = 1.02,
            thyroidIsfMult = 0.95,
            thyroidDiaMult = 1.05,
            thyroidGuardActive = true,
            gestationBasalMult = 1.01,
            basalLearnerShortMult = 1.02,
            basalLearnerMedMult = 1.01,
            basalLearnerLongMult = 1.0,
            onlineLearnFactor = 1.02,
            attentionGateScore = 0.7,
            dynIsfTrajFactor = 0.95,
            stackingEventualDrop = 0.5,
            stackingMinPredDrop = 0.4,
            htrLeafSmbFloorU = 1.0,
            shadowComparatorDeltaU = 0.1,
            shadowVirtualBgMgdl = 210.0,
            shadowAuditorConfidence = 0.7,
            shadowSentinelVerdictLabel = "OK",
            shadowOrchestratorActive = true,
            shadowVisionTriggered = true,
            shadowMlTrainActive = true,
            tuningContextLabel = "AUTO_BALANCE",
            sleepDebtMinutes = 75.0,
            physioMtrStateOrdinal = PhysioStateMTR.RECOVERY_NEEDED.ordinal,
            hrvDeviationZ = -1.3,
            sleepQualityScore = 0.42,
            sleepLiveConfidence = 0.62,
            sleepLiveSource = SleepLiveDetector.Source.WEARABLE.name,
        )
        val trajectory = TrajectoryAnalysis(
            classification = TrajectoryType.OPEN_DIVERGING,
            metrics = TrajectoryMetrics(
                curvature = 0.1,
                convergenceVelocity = 0.5,
                coherence = 0.7,
                energyBalance = 1.5,
                openness = 0.4,
            ),
            modulation = TrajectoryModulation(
                smbDamping = 0.85,
                intervalStretch = 1.0,
                basalPreference = 0.4,
                safetyMarginExpand = 1.0,
                relevanceScore = 0.7,
                reason = "coverage",
            ),
            warnings = emptyList(),
            stableOrbitDistance = 20.0,
            predictedConvergenceTime = 45,
        )
        val physioMultipliers = PhysioMultipliersMTR(
            isfFactor = 0.95,
            basalFactor = 1.02,
            smbFactor = 1.05,
            reactivityFactor = 1.03,
            trajectoryRelevanceScore = 0.75,
            confidence = 0.8,
        )
        return RecursiveBeliefTickContext(
            bgMgdl = 226.0,
            targetBgMgdl = 100.0,
            highBgPreferenceMgdl = 180.0,
            deltaMgdlPer5 = 2.5,
            shortAvgDeltaMgdlPer5 = 2.2,
            combinedDeltaMgdlPer5 = 2.5,
            iobU = 8.8,
            maxIobU = 12.0,
            maxSmbEffectiveU = 5.0,
            tdd24hU = 40.0,
            patientWeightKg = 70.0,
            curves = curves,
            scenario = scenario,
            mealAbsorption = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.85,
                reason = "coverage",
                deltaMgdlPer5 = 2.5,
                gapMgdl = 175.0,
                bestTerminalMgdl = 320.0,
                memoryActive = true,
                waveCount = 2,
                mealDeliveryPriority = true,
                chronoPrior = 0.8,
                kineticScore = 0.9,
                trajectoryScore = 0.85,
                physioScore = 0.5,
            ),
            physioPhase = PhysiologicalPhaseClassifier.Output(
                phase = PhysiologicalPhase.STRESS_CORTISOL,
                confidence = 0.75,
                policy = BehavioralRiskPolicy.forPhase(
                    PhysiologicalPhase.STRESS_CORTISOL,
                    0.75,
                    "coverage",
                ),
            ),
            behavioralRisk = BehavioralRiskPolicy.forPhase(
                PhysiologicalPhase.STRESS_CORTISOL,
                0.75,
                "coverage",
            ),
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.RECOVERY_NEEDED,
                confidence = 0.78,
                poorSleepDetected = true,
                hrvDepressed = true,
                hrvDeviationZ = -1.3,
                features = app.aaps.plugins.aps.openAPSAIMI.physio.PhysioFeaturesMTR(
                    sleepQualityScore = 0.42,
                ),
            ),
            physiologicalPatterns = PhysiologicalPatternSnapshot(
                active = listOf(
                    PhysiologicalPatternReading(
                        PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
                        0.82,
                        "coverage",
                    ),
                ),
                dominant = PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
                dominantConfidence = 0.82,
                suppressMealInterpretation = true,
                suppressHyperRelease = true,
                suppressWaveletBoost = true,
                smbCapU = 0.50,
                reasonSummary = "POOR_SLEEP_MORNING_RISE@0.82",
            ),
            trajectoryAnalysis = trajectory,
            trajectoryRelevanceScore = 0.75,
            safetyTerminals = SafetyPredictionTerminals(
                predBg = 200.0,
                eventualBg = 210.0,
                uamTerminalMgdl = 220.0,
                mealRiseConfirmed = true,
                compositeMinMgdl = 85.0,
            ),
            riskEnvelope = AimiRiskEnvelope(
                phase = AimiRiskPhase.EARLY,
                bgNowMgdl = 226.0,
                deltaMgdlPer5 = 2.5f,
                predTerminalMgdl = 200.0,
                eventualTerminalMgdl = 210.0,
                pathMinRawMgdl = 85.0,
                pathMinClampedMgdl = 85.0,
                pathMinHitNumericFloor = false,
                compositeMinMgdl = 85.0,
                hypoThresholdMgdl = 70.0,
                aapsIobUnits = 8.8,
                pkpdIobUnits = 8.5,
                iobDecisionUnits = 8.8,
                iobSource = IobDecisionSource.AAPS_ALIGNED,
            ),
            stackingStance = InsulinStackingStance.Evaluation(
                kind = InsulinStackingStance.Kind.SURVEILLANCE_IOB,
                smbMultiplier = 0.7,
                smbAbsoluteCapU = 0.5,
                suppressRedCarpetRestore = true,
                tbrBoostFloor = 1.1,
                summary = "coverage stacking",
            ),
            uamConfidence = 0.65,
            contextSmbFactor = 1.05f,
            contextActivityActive = true,
            stepsLast15m = 120,
            heartRateBpm = 95,
            isNight = false,
            exerciseLockout = true,
            asleepLiveConfidence = 0.62,
            hypoMinPredIgnored = true,
            minPredictedBgMgdl = 85.0,
            dwellAboveHighBgMinutes = 45,
            trajBridgePending = true,
            tubeAdvisorCapScale = 0.85,
            v3SmbU = 0.8,
            htrResult = HyperTrajectoryReleaseResult(
                active = true,
                tier = HyperSeverityTier.EMERGING,
                severityWeight = 0.6,
                smbFloorU = 1.0,
                v3SmbBeforeU = 0.5,
                v3SmbAfterU = 1.0,
                absorptionOffsetMgdl = 0.0,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = true,
                reason = "coverage",
            ),
            htrClassification = HyperSeverityClassifier.Output(
                tier = HyperSeverityTier.EMERGING,
                devAboveTargetMgdl = 126.0,
                projectedDevMgdl = 220.0,
                terminalGapMgdl = 240.0,
                highBgBandMgdl = 80.0,
                establishedDevMgdl = 50.0,
                deepDevMgdl = 80.0,
                riseActive = true,
                projectionHyper = true,
                bestCredible = true,
                plateauSustain = false,
            ),
            tier1Hypo = false,
            bgHistoryMgdl = (1..48).map { 120.0 + it * 2.0 },
            physioMultipliers = physioMultipliers,
            wCycleBasalMult = 1.02,
            wCycleSmbMult = 0.98,
            bgDerivShort = 4.0,
            insulinActivityStageOrdinal = 1,
            autodriveV3GateOpen = true,
            endogenousCounterRegulatory = true,
            correctionAggressionLevel = 1.0,
            pkpdTailDamping = 0.9,
            shadowAuditorConfidence = 0.7,
            shadowOrchestratorActive = true,
            tuningContextLabel = "AUTO_BALANCE",
            replaceHtrRelease = true,
            extended = extended,
        )
    }
}
