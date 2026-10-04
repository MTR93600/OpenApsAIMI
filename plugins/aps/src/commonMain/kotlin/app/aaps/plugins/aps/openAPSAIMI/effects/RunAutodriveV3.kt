package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AutodriveBasalPolicy
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientRefreshSource
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryMpcFeedForward
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import kotlin.math.abs
import kotlin.math.min

internal const val AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES = 75

internal data class AutodriveV3BranchResult(
    /** V3 or HTR delivered any pump command (TBR and/or SMB) this tick. */
    val appliedAction: Boolean,
    val skipLegacySmbBlender: Boolean,
)

/** Members the branch reads. Writes go through [AimiAutodriveTickWrites] at the assignment line. */
internal class AutodriveV3TickState(
    val mealTime: Boolean,
    val bfastTime: Boolean,
    val lunchTime: Boolean,
    val dinnerTime: Boolean,
    val highCarbTime: Boolean,
    val snackTime: Boolean,
    val variableSensitivity: Float,
    val hourOfDay: Int,
    val exerciseInsulinLockoutActive: Boolean,
    val adaptiveMult: Double,
    val maxIob: Double,
    val iob: Float,
    val maxSmb: Double,
    val maxSmbHb: Double,
    val postHypo: PostHypoDeliveryAuthority.Decision,
    val mealChannelHint: MealChannelHint?,
    val basePhysioMultipliers: PhysioMultipliersMTR,
    val eventualBg: Double,
    val targetBg: Float,
    val delta: Float,
    val smbBindingDraft: SmbBindingTrace.Draft,
)

internal fun runAutodriveV3MultiVariableBranch(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    bg: Double,
    combinedDelta: Float,
    shortAvgDeltaAdj: Float,
    hypoThresholdMgdl: Double,
    pkpdRuntime: PkPdRuntime?,
    preferences: Preferences,
    dateUtil: DateUtil,
    consoleLog: MutableList<String>,
    gater: AimiAutodriveGater,
    estimatedRa: AimiEstimatedRa,
    debug: AimiAutodriveDebug,
    engine: AutodriveEngine,
    physio: AimiPhysioTick,
    effects: AimiEffectSink,
    state: AutodriveV3TickState,
    writes: AimiAutodriveTickWrites,
    uam: AimiUamConfidence,
    fcl: AimiFclDeclared,
    minBg: AimiMinBgLookback,
    postHypoRecovery: AimiPostHypoRecovery,
    tdd24h: AimiTdd24h,
    phase: AimiPhysiologicalPhase,
    mealSafety: AimiMealSafety,
    absorption: AimiMealAbsorption,
    latent: AimiPhysioLatentUpdate,
    hyper: AimiHyperSeverity,
    htrTerminals: AimiHtrTerminals,
    basalCap: AimiBasalCap,
    riseFloor: AimiAggressiveRiseFloor,
    riseNote: AimiRiseFloorNote,
    patient: AimiPatientStateRefresh,
    terminal: AimiDoseTerminal,
    rbt: AimiRbtLive,
    smbDelivery: AimiV3SmbDelivery,
    raObserve: AimiRaObservation,
    htrExport: AimiHtrExport,
    decisionLog: AimiDecisionLog,
): AutodriveV3BranchResult {
    if (!preferences.get(BooleanKey.OApsAIMIautoDriveActive)) {
        raObserve.observeRaIfNotAlreadyRun(ctx, combinedDelta, shortAvgDeltaAdj, pkpdRuntime, false, "autodrive_off")
        return AutodriveV3BranchResult(
            appliedAction = false,
            skipLegacySmbBlender = false,
        )
    }
    writes.setRaNetDeltas(combinedDelta, shortAvgDeltaAdj)
    var v3AppliedAction = false
    var skipLegacySmbBlender = false
    var smbDraft = state.smbBindingDraft
    val recentEstimateCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
    val recentEstimateTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
    val estimateAgeMinutes = if (recentEstimateTime > 0L) {
        (aimiWallClockMs() - recentEstimateTime) / 60000.0
    } else {
        Double.MAX_VALUE
    }
    val hasRecentMealEstimate = recentEstimateCarbs > 10.0 && estimateAgeMinutes in 0.0..45.0

    val gate = gater.shouldEngageV3(
        bg = ctx.glucoseStatus.glucose,
        combinedDelta = combinedDelta.toDouble(),
        cob = ctx.mealData.mealCOB,
        uamConfidence = uam.confidenceOrZero(),
        explicitMealMode = state.mealTime || state.bfastTime || state.lunchTime || state.dinnerTime ||
            state.highCarbTime || state.snackTime || fcl.fclDeclaredThisTick(profile),
        hasRecentMealEstimate = hasRecentMealEstimate,
        minBgLookback75m = minBg.minBgInLastMinutes(AUTODRIVE_POST_HYPO_MIN_BG_LOOKBACK_MINUTES),
        estimatedRa = estimatedRa.getLastRa(),
        mealChannelHint = state.mealChannelHint,
    )

    writes.noteAutodriveGate(engaged = gate.engage, kindName = gate.kind.name, reason = gate.reason)

    if (!gate.engage) {
        raObserve.observeRaIfNotAlreadyRun(
            ctx, combinedDelta, shortAvgDeltaAdj, pkpdRuntime, hasRecentMealEstimate, "gate_disengaged",
        )
    }

    if (gate.engage) {
        writes.setAutodriveEngaged()
        debug.debug("🚦 [AUTODRIVE V3] ${gate.reason} - Engaging Control Loop...")

        val snapshot = physio.getLatestSnapshot()
        val canonicalSI = if (pkpdRuntime != null) {
            pkpdRuntime.fusedIsf / 10000.0
        } else {
            state.variableSensitivity.toDouble() / 10000.0
        }

        val autodriveMealSignals = state.mealTime || state.bfastTime || state.lunchTime || state.dinnerTime ||
            state.highCarbTime || state.snackTime || ctx.mealData.mealCOB >= 0.1 || hasRecentMealEstimate
        val applyHypoRecoveryRaDampening = postHypoRecovery.postHypoRecoveryActive() && !autodriveMealSignals

        val tdd24hForHtr = tdd24h.resolveTdd24hForExport()
            ?: (profile.max_daily_basal * 24.0).coerceAtLeast(1.0)
        val isNightAutodrive = state.hourOfDay >= 23 || state.hourOfDay < 6
        val phaseOutput = phase.refreshPhysiologicalPhase(
            rT = rT,
            combinedDelta = combinedDelta,
            stepsLast15m = snapshot.stepsLast15m,
            heartRateBpm = snapshot.hrNow,
            restingHeartRateBpm = snapshot.rhrResting,
            basePhysioMultipliers = state.basePhysioMultipliers,
        )
        val autodriveMealContext = mealSafety.buildMealSafetyContext(
            isExplicitAdvisorRun = false,
            iobData = ctx.iobDataArray.firstOrNull() ?: IobTotal(ctx.currentTime),
        )
        absorption.refreshMealAbsorptionPhase(
            combinedDelta = combinedDelta,
            stepsLast15m = snapshot.stepsLast15m,
            heartRateBpm = snapshot.hrNow,
            restingHeartRateBpm = snapshot.rhrResting,
            mealContext = autodriveMealContext,
            lastBolusTimeMs = ctx.iobDataArray.firstOrNull()?.lastBolusTime?.takeIf { it > 0L },
            nowMs = dateUtil.now(),
        )
        val physioLatentState = latent.updatePhysioLatentState(
            snapshot = snapshot,
            sourceSensor = ctx.glucoseStatus.sourceSensor,
        )
        val physioPolicy = phaseOutput?.policy
        val htrClassification = hyper.classifyHyperSeverityForTick(
            rT = rT,
            combinedDelta = combinedDelta,
            tdd24hU = tdd24hForHtr,
        )
        val (_, bestTerminalForMpc) = htrTerminals.resolveHtrScenarioTerminals(rT)
        val mpcHints = HyperTrajectoryMpcFeedForward.hintsFromClassification(
            classification = htrClassification,
            bgMgdl = bg,
            bestTerminalMgdl = bestTerminalForMpc,
            isNight = isNightAutodrive,
            exerciseLockout = state.exerciseInsulinLockoutActive,
        )
        val estimatedRaForMpc = HyperTrajectoryMpcFeedForward.blendEstimatedRa(
            baseRa = estimatedRa.getLastRa(),
            hints = mpcHints,
        )
        val htrRaFloor = mpcHints.estimatedRaFloorMgdlPerMin.takeIf { it > 0.0 }
        writes.setLastHtrRaFloorMgdlPerMin(htrRaFloor)

        val adState = AutoDriveState.createSafe(
            bg = ctx.glucoseStatus.glucose,
            bgVelocity = (shortAvgDeltaAdj.toDouble() / 5.0),
            iob = ctx.iobDataArray.firstOrNull()?.iob ?: 0.0,
            cob = ctx.mealData.mealCOB,
            estimatedSI = canonicalSI,
            estimatedRa = estimatedRaForMpc,
            patientWeightKg = preferences.get(DoubleKey.OApsAIMIweight),
            physiologicalStressMask = physioLatentState.toAttentionMask(),
            isNight = isNightAutodrive,
            hour = state.hourOfDay,
            steps = snapshot.stepsLast15m,
            hr = snapshot.hrNow,
            rhr = snapshot.rhrResting,
            sourceSensor = ctx.glucoseStatus.sourceSensor,
            maxIOB = state.maxIob,
            maxSMB = state.maxSmb,
            highBgMaxSMB = state.maxSmbHb,
            combinedDelta = combinedDelta.toDouble(),
            uamConfidence = uam.confidenceOrZero(),
            applyHypoRecoveryRaDampening = applyHypoRecoveryRaDampening,
            htrTierOrdinal = mpcHints.tierOrdinal,
            htrProjectedDevMgdl = mpcHints.projectedDevMgdl,
            htrProjectionLeadMgdl = mpcHints.projectionLeadMgdl,
            physioExtendedDawnGuard = physioPolicy?.extendedDawnGuard == true,
        )

        if (physioLatentState.isActive()) {
            consoleLog.add("🧠 ATTN_MASK: ${physioLatentState.toAttentionDebugString()}")
            consoleLog.add("🧠 LATENT: ${physioLatentState.toDebugString()}")
        }

        if (physioPolicy != null && physioPolicy.capsHtrRelease()) {
            consoleLog.add(
                "🌅 PHYSIO_RISK: ${physioPolicy.phase.name} conf=${aimiFmt2(phaseOutput?.confidence)} " +
                    "${physioPolicy.reason}",
            )
        }

        if (adState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G6_NATIVE) {
            consoleLog.add("🤖 SENSOR_AWARE: G6 Detected -> Engaging Lead Compensator (UKF +50% Vel).")
        } else if (adState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_ONEPLUS_NATIVE) {
            consoleLog.add("🤖 SENSOR_AWARE: One+ Detected -> Fast Sensor, Real-Time Maths Engaged (no G6 lead).")
        } else if (adState.sourceSensor == app.aaps.core.data.model.SourceSensor.DEXCOM_G7_NATIVE) {
            consoleLog.add("🤖 SENSOR_AWARE: G7 Detected -> Fast Sensor, Real-Time Maths Engaged.")
        } else if (adState.sourceSensor == app.aaps.core.data.model.SourceSensor.LIBRE_3_NATIVE) {
            consoleLog.add("🤖 SENSOR_AWARE: Libre 3 native Detected -> Fast Sensor, Real-Time Maths Engaged (no G6 lead).")
        }

        engine.setShadowMode(false)
        engine.setIsActive(true)

        val adCommand = engine.tick(
            currentState = adState,
            profileBasal = profile.current_basal,
            profileIsf = profile.sens,
            lgsThreshold = min(90.0, hypoThresholdMgdl),
            hour = state.hourOfDay,
            steps = snapshot.stepsLast15m,
            hr = snapshot.hrNow,
            rhr = snapshot.rhrResting,
            mpcRaFloorMgdlPerMin = mpcHints.estimatedRaFloorMgdlPerMin,
            tickId = ctx.currentTime,
            observationId = raObservationId(ctx),
            engaged = true,
            profileIsfIsDynamic = true,
        )

        htrExport.markHtrRaFloorForExport(htrRaFloor, estimatedRaForMpc)

        val v3CommandSafe = adCommand != null && adCommand.isSafe
        if (v3CommandSafe) {
            val v3TbrRate = adCommand!!.temporaryBasalRate
            if (v3TbrRate != null && v3TbrRate >= 0.0) {
                val v3AdaptiveMult = AutodriveBasalPolicy.adaptiveMultiplierForDirectTbr(
                    requestedRateUph = v3TbrRate,
                    bgMgdl = bg,
                    targetBgMgdl = ctx.profile.target_bg.toDouble(),
                    profileMaxBasalUph = profile.max_basal.toDouble(),
                    learnedAdaptiveMultiplier = state.adaptiveMult,
                )
                if (v3AdaptiveMult > state.adaptiveMult + 0.001) {
                    consoleLog.add(
                        "🚀 AUTODRIVE_V3_CAP_KEEP: adaptive ${aimiFmt2(state.adaptiveMult)}x -> " +
                            "${aimiFmt2(v3AdaptiveMult)}x at BG=${aimiFmt0(bg)}",
                    )
                }
                val cappedV3Tbr = basalCap.capBasalRateForCorrectionAggression(
                    requestedRateUph = v3TbrRate,
                    profileBasalUph = profile.current_basal,
                    source = "AUTODRIVE_V3_DIRECT",
                )
                effects.setTempBasal(
                    cappedV3Tbr,
                    30,
                    profile,
                    rT,
                    ctx.currentTemp,
                    overrideSafetyLimits = true,
                    forceExact = false,
                    adaptiveMultiplier = v3AdaptiveMult,
                    mealContext = autodriveMealContext,
                )
            }
        } else {
            consoleLog.add("🧘 [AUTODRIVE V3] Command not safe — HTR may still lift SMB (${adCommand?.reason ?: "null"})")
            debug.debug("🛑 [AUTODRIVE V3] Unsafe or null command")
        }

        val v3SmbModel = if (v3CommandSafe) adCommand!!.scheduledMicroBolus ?: 0.0 else 0.0
        val iobHeadroomForFloor = (state.maxIob - state.iob).coerceAtLeast(0.0)
        val smbCeilingForFloor = state.maxSmb.coerceAtLeast(0.0)
        val v3SmbFloor = if (v3CommandSafe) {
            riseFloor.aggressiveRiseSmbFloorU(bg, combinedDelta, shortAvgDeltaAdj)
                .coerceAtMost(minOf(smbCeilingForFloor, iobHeadroomForFloor))
        } else {
            0.0
        }
        val v3SmbRaw = maxOf(v3SmbModel, v3SmbFloor)
        riseNote.noteRiseFloorContribution(v3SmbRaw - v3SmbModel)
        val smallPrebolusPref = preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus)
        val largePrebolusPref = preferences.get(DoubleKey.OApsAIMIautodrivePrebolus)
        val v3FloorTier = when {
            v3SmbFloor <= 0.0 -> "OFF"
            combinedDelta >= 5.0f && shortAvgDeltaAdj >= 3.0f -> "LARGE"
            else -> "SMALL"
        }
        smbDraft = smbDraft.copy(
            originOwner = "AutodriveV3",
            modelOutputU = v3SmbModel,
            mpcOutputU = v3SmbModel,
            mpcRequestedU = engine.lastMpcRawSmbU.takeIf { it.isFinite() },
            tier = v3FloorTier,
            smallPrebolusPrefU = smallPrebolusPref,
            largePrebolusPrefU = largePrebolusPref,
            autodriveFloorU = v3SmbFloor,
            maxSmbU = state.maxSmb,
            maxSmbHighBgU = state.maxSmbHb,
            iobHeadroomU = iobHeadroomForFloor,
        ).appendStage(
            name = "AUTODRIVE_FLOOR",
            beforeU = v3SmbModel,
            afterU = v3SmbRaw,
            referenceU = v3SmbFloor,
            phase = "AUTODRIVE_PRE_TERMINAL",
            kind = "FLOOR",
        )
        writes.setSmbBindingDraft(smbDraft)
        if (v3SmbFloor > v3SmbModel + 1e-6) {
            consoleLog.add(
                "🚀 AUTODRIVE_AGGR_SMB_FLOOR: model=${aimiFmt2(v3SmbModel)} → " +
                    "floor=${aimiFmt2(v3SmbFloor)} U",
            )
        }
        val v3Smb = state.postHypo.capSmbU(v3SmbRaw)
        writes.setPostHypoSmbBeforeCapU(v3SmbRaw)
        writes.setPostHypoSmbAfterCapU(v3Smb)
        smbDraft = smbDraft.appendStage(
            name = "POST_HYPO_CAP",
            beforeU = v3SmbRaw,
            afterU = v3Smb,
            phase = "AUTODRIVE_PRE_TERMINAL",
            kind = "CAP",
        )
        writes.setSmbBindingDraft(smbDraft)
        if (v3Smb < v3SmbRaw - 1e-6) {
            consoleLog.add(
                "${PostHypoDeliveryAuthority.LOG_PREFIX}: v3_smb " +
                    "${aimiFmt2(v3SmbRaw)}→${aimiFmt2(v3Smb)} U",
            )
        }
        val cbfShieldDeltaU = adCommand?.scheduledMicroBolus?.takeIf { !v3CommandSafe && it > 0.01 }
        patient.refreshPatientStateRuntime(
            nowMs = dateUtil.now(),
            healthSnapshot = snapshot,
            sourceSensor = ctx.glucoseStatus.sourceSensor,
            refreshSource = PatientRefreshSource.PHYSIO_SIGNAL,
        )
        val preV3Eventual =
            state.eventualBg.takeIf { it.isFinite() && it > 1.0 }
                ?: rT.eventualBG?.takeIf { it.isFinite() && it > 1.0 }
                ?: bg
        val preV3MinPred = minPredictedAcrossCurves(rT.predBGs) ?: preV3Eventual
        terminal.publishDoseTerminalAuthorityAndSnapshot(
            rT = rT,
            profile = profile,
            mealData = ctx.mealData,
            pkpdEventualMgdl = preV3Eventual,
            pkpdPredTerminalMgdl = preV3MinPred,
            targetBgMgdl = state.targetBg.toDouble(),
            stageTag = "pre_v3_rbt",
        )
        val rbtCommit = rbt.resolveAndWireRbtLiveTick(
            ctx = ctx,
            profile = profile,
            rT = rT,
            combinedDelta = combinedDelta,
            tdd24hU = tdd24hForHtr,
            v3SmbU = v3Smb,
            stepsLast15m = snapshot.stepsLast15m,
            heartRateBpm = snapshot.hrNow,
            autodriveGateOpen = v3CommandSafe,
            mpcFeedForwardRa = estimatedRaForMpc,
            cbfShieldDeltaU = cbfShieldDeltaU,
        )
        smbDelivery.deliverV3SmbFromRbt(
            ctx = ctx,
            profile = profile,
            rT = rT,
            hypoThresholdMgdl = hypoThresholdMgdl,
            v3CommandSafe = v3CommandSafe,
            adCommandReason = adCommand?.reason,
            rbtCommit = rbtCommit,
        )
        val effectiveHtr = rbtCommit?.effectiveHtr

        val effectiveSmbUnits = rT.units ?: 0.0
        val effectiveTbr = rT.rate ?: profile.current_basal
        val effectiveDuration = rT.duration ?: 0
        val v3SmbDelivered = effectiveSmbUnits > 0.01
        val v3TbrDelivered =
            effectiveDuration > 0 && abs(effectiveTbr - profile.current_basal) > 0.01
        if (v3CommandSafe) {
            v3AppliedAction = v3SmbDelivered || v3TbrDelivered
            val v3TbrRate = adCommand!!.temporaryBasalRate
            consoleLog.add("🚀 ${gate.reason} intent=$v3Smb actual=$effectiveSmbUnits tbr=$v3TbrRate")
            decisionLog.logDecisionFinal("AUTODRIVE_V3", rT, bg, state.delta)
        } else if (effectiveHtr?.active == true && v3SmbDelivered) {
            v3AppliedAction = true
            consoleLog.add("🚀 HTR-only SMB after unsafe V3: actual=$effectiveSmbUnits U")
            decisionLog.logDecisionFinal("AUTODRIVE_V3+HTR", rT, bg, state.delta)
        }
        if (v3SmbDelivered && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)) {
            skipLegacySmbBlender = true
            consoleLog.add("AUTODRIVE_V3_AUTHORITATIVE: Legacy MPC/PI blender will be skipped this tick")
        }
    }
    return AutodriveV3BranchResult(
        appliedAction = v3AppliedAction,
        skipLegacySmbBlender = skipLegacySmbBlender,
    )
}

private fun raObservationId(ctx: AimiTickContext): Long =
    ctx.glucoseStatus.date.takeIf { it > 0L } ?: ctx.currentTime
