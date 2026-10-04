package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cAnticipation
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cAutodriveBasalBridge
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cTrajectoryContext
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.math.abs
import kotlin.math.min

/** Learner call that used to sit inside the T3C head. The learner itself stays on Android. */
internal interface AimiT3cAdaptiveFactor {
    fun factor(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double,
    ): Double
}

/** Wearable snapshot, read only when CFRD mode is on. May throw; the caller records the failure. */
internal fun interface AimiT3cHrSnapshot {
    fun latest(): HealthContextSnapshot
}

/** The two lookbacks the T3C head reads, at the lines the reference reads them. */
internal interface AimiT3cLookbacks {
    fun postHypoRecoveryActive(): Boolean
    fun minBgInLastMinutes(lookbackMinutes: Int): Double
}

/** Learning update and the final-loop mark. Both stay on Android and run after the rate is chosen. */
internal interface AimiT3cTickTail {
    fun applyBasalNeuralLearning(rT: RT, tbrUph: Double)
    fun markFinalLoopDecision(rT: RT, currentTemp: CurrentTemp)
}

/**
 * T3C brittle basal decision. Sets [RT.rate] and [RT.duration] only. Never writes [RT.units].
 * The heart-rate snapshot failure keeps the reference fallback (boost 0.0) and is a typed
 * [OptionalSignal.Failed] plus the existing console line.
 */
internal fun decideT3cBrittleMode(
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
    autodriveBasalProposal: AutodriveEngine.BasalOnlyTbrProposal? = null,
    exerciseInsulinLockoutActive: Boolean,
    exerciseBasalResumeBgMgdl: Double,
    adaptiveMult: Double,
    tree: PhysiologicalTreeSnapshot?,
    effortSmbFactor: Double?,
    ngrBasalMultiplier: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    adaptiveFactor: AimiT3cAdaptiveFactor,
    hrSnapshot: AimiT3cHrSnapshot,
    lookbacks: AimiT3cLookbacks,
    tail: AimiT3cTickTail,
): RT {
    rT.reason = StringBuilder("")
    rT.deliverAt = aimiWallClockMs()
    // maxSMB = 0.0 is enforced: this function ONLY sets TBR, never rT.units
    // rT.units is preserved for pre-bolus from applyLegacyMealModes

    if (exerciseInsulinLockoutActive && bg > exerciseBasalResumeBgMgdl) {
        consoleLog.add(
            "🏃 T3c + exercice: BG ${bg.toInt()} > ${exerciseBasalResumeBgMgdl.toInt()} → basale PI activee, SMB=0"
        )
        rT.reason.append(
            "🏃 Exercice : BG>${exerciseBasalResumeBgMgdl.toInt()} → basale T3c pour freiner l'hyper (SMB=0).\n"
        )
    }

    val baseBasal = profile.current_basal

    val activationThreshold = preferences.get(DoubleKey.OApsAIMIT3cActivationThreshold)

    val steadyBasalCap = profile.max_basal.coerceAtLeast(baseBasal)
    val riseHardCeiling = preferences.get(DoubleKey.autodriveMaxBasal).coerceAtLeast(steadyBasalCap)
    val riseBasalCap = preferences.get(DoubleKey.meal_modes_MaxBasal).coerceIn(steadyBasalCap, riseHardCeiling)

    val cfrdMode = preferences.get(BooleanKey.OApsAIMIT3cCfrdMode)
    val cfrdExac = cfrdMode && preferences.get(BooleanKey.OApsAIMIT3cCfrdExacerbationMode)
    val cfrdLgsFloor = if (cfrdMode) preferences.get(DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl) else 70.0
    val cfrdCobDelaySteps = if (cfrdMode) {
        (preferences.get(DoubleKey.OApsAIMIT3cCfrdCobDelayMin) / T3cAnticipation.PREDICTION_STEP_MINUTES)
            .toInt().coerceIn(0, 18)
    } else 0
    val cfrdHrInflammationBoost: Double = if (cfrdMode) {
        val snapshot = readRbtOptional(
            source = "t3cHrSnapshot",
            consoleLog = consoleLog,
            failureLine = { type, _ ->
                "🫁 T3c CFRD: hr snapshot failed ($type) — boost 0.00"
            },
        ) { hrSnapshot.latest() }.valueOrNull()
        if (snapshot == null) 0.0 else cfrdHrInflammationBoostOf(snapshot.hrNow, snapshot.rhrResting)
    } else 0.0

    if (cfrdMode) {
        consoleLog.add(
            "🫁 T3c CFRD: lgsFloor=${cfrdLgsFloor.toInt()} " +
                "cobDelay=${cfrdCobDelaySteps * T3cAnticipation.PREDICTION_STEP_MINUTES}m " +
                "exac=$cfrdExac hrBoost=${aimiFmt2(cfrdHrInflammationBoost)}"
        )
    }

    val rawAggressiveness = adaptiveFactor.factor(
        bg = bg,
        basal = baseBasal,
        accel = accel,
        duraMin = duraISFminutes,
        duraAvg = duraISFaverage,
        iob = iob.iob,
    )
    val adaptiveBoost = if (adaptiveMult > 1.0) {
        (adaptiveMult - 1.0).coerceAtMost(0.40)
    } else {
        adaptiveMult - 1.0
    }
    val cfrdResistanceBoost = (cfrdHrInflammationBoost + if (cfrdExac) 0.45 else 0.0).coerceAtMost(0.65)
    val baseAggressivenessCap = if (cfrdMode && (cfrdExac || cfrdHrInflammationBoost >= 0.20)) 3.0 else 2.0

    val physioInformed = preferences.get(BooleanKey.OApsAIMIT3cPhysioInformedEnabled)
    val treeRisk = tree?.trunk?.riskLevel
    val physioPermitsHigherAggression = physioInformed &&
        (treeRisk == PhysiologicalRiskLevel.LOW || treeRisk == PhysiologicalRiskLevel.MODERATE) &&
        bg > activationThreshold + 20.0 &&
        (eventualBg <= 0.0 || eventualBg > targetBg)
    val configuredAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness).coerceIn(0.3, 3.0)
    val aggressivenessCap = if (physioPermitsHigherAggression)
        maxOf(baseAggressivenessCap, configuredAggressiveness) else baseAggressivenessCap
    val activityDampen = if (physioInformed) (effortSmbFactor?.coerceIn(0.5, 1.0) ?: 1.0) else 1.0
    val aggressiveness = (rawAggressiveness * (1.0 + adaptiveBoost + cfrdResistanceBoost) * activityDampen)
        .coerceIn(0.3, aggressivenessCap)
    if (physioInformed && (physioPermitsHigherAggression || activityDampen < 1.0)) {
        consoleLog.add(
            "🌳 T3c physio: risk=${treeRisk?.name ?: "—"} capRaised=$physioPermitsHigherAggression " +
                "cap=${aimiFmt1(aggressivenessCap)} activityDampen=${aimiFmt2(activityDampen)}"
        )
    }

    val lgsForAnticipation = min(
        90.0,
        (profile.lgsThreshold?.toDouble() ?: 70.0).coerceAtLeast(cfrdLgsFloor)
    )
    val anticipationStrength = preferences.get(DoubleKey.OApsAIMIT3cAnticipationStrength)
    val t3cAnticipationHints = T3cAnticipation.buildHints(
        predictions = rT.predBGs,
        bgNow = bg,
        lgsThresholdMgdl = lgsForAnticipation,
        lgsFloorMgdl = cfrdLgsFloor,
        cobDelaySteps = cfrdCobDelaySteps,
        activationThreshold = activationThreshold,
        eventualBg = if (eventualBg > 0) eventualBg else null,
        strengthRaw = anticipationStrength
    )
    if (anticipationStrength > 0.01) {
        consoleLog.add(
            "🔮 T3cANT str=${aimiFmt2(anticipationStrength)} " +
                "tSoftHypo=${t3cAnticipationHints.minutesToSoftHypo?.toString() ?: "—"}m " +
                "nadir=${t3cAnticipationHints.defensiveNadirBg?.let { aimiFmt0(it) } ?: "—"} " +
                "tHyperBand=${t3cAnticipationHints.minutesToHyperExcursion?.toString() ?: "—"}m"
        )
    }

    val unlockProjectedBg = DynamicBasalController.projectBg(bg, delta, shortAvgDelta, accel)
    val treeUnlock = T3cAutodriveBasalBridge.evaluateTreeUnlock(
        tree = tree,
        bg = bg,
        delta = delta,
        activationThreshold = activationThreshold,
        postHypoActive = lookbacks.postHypoRecoveryActive(),
        eventualBg = eventualBg.takeIf { it > 0 },
        targetBg = targetBg,
        projectedBg = unlockProjectedBg,
    )
    val maxBasalCapForPi = if (treeUnlock.unlock) riseBasalCap else steadyBasalCap

    val computedRate = DynamicBasalController.computeT3c(
        bg = bg,
        targetBg = targetBg,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        accel = accel,
        iob = iob.iob,
        maxIob = maxIob,
        profileBasal = baseBasal,
        isf = variableSensitivity.coerceAtLeast(10.0),
        duraISFminutes = duraISFminutes,
        duraISFaverage = duraISFaverage,
        eventualBg = if (eventualBg > 0) eventualBg else null,
        activationThreshold = activationThreshold,
        aggressiveness = aggressiveness,
        maxBasalCap = maxBasalCapForPi,
        trajectory = trajectoryContext,
        anticipationHints = t3cAnticipationHints
    )

    val prevRate = if (currenttemp.duration > 0) currenttemp.rate else baseBasal
    val fusion = T3cAutodriveBasalBridge.fuse(
        piUph = computedRate,
        adTbrUph = autodriveBasalProposal?.tbrUph,
        strippedSmbU = autodriveBasalProposal?.strippedSmbU ?: 0.0,
        profileBasalUph = baseBasal,
        steadyCapUph = steadyBasalCap,
        riseCapUph = riseBasalCap,
        previousRateUph = prevRate,
        unlock = treeUnlock,
    )
    consoleLog.add(fusion.toLogLine())
    if (autodriveBasalProposal != null && autodriveBasalProposal.strippedSmbU > 0.0) {
        consoleLog.add(
            "T3C_AD_BASAL: smb stripped ${aimiFmt2(autodriveBasalProposal.strippedSmbU)}U " +
                "(reason=${autodriveBasalProposal.reason.take(80)})"
        )
    }
    val maxBasalCap = fusion.maxBasalCapUph
    val targetRate = fusion.fusedTargetUph
    val maxStepUp = fusion.maxStepUpUph
    val safeRate = T3cAutodriveBasalBridge.applyRamp(prevRate, targetRate, maxStepUp)

    val ngrBasalMult = if (physioInformed) ngrBasalMultiplier.coerceIn(1.0, 2.0) else 1.0
    val t3cRateMult = adaptiveMult * ngrBasalMult
    val t3cFinalRate = if (safeRate > 0.0 && abs(t3cRateMult - 1.0) > 0.01) {
        (safeRate * t3cRateMult).coerceIn(0.0, maxBasalCap)
    } else {
        safeRate
    }
    if (physioInformed && ngrBasalMult > 1.0) {
        consoleLog.add("🌙 T3c NGR nocturnal basal boost ×${aimiFmt2(ngrBasalMult)} → ${aimiFmt2(t3cFinalRate)}U/h")
    }

    val hyperFloorBgMgdl = 160.0
    val hyperFloorDwellMin = 20
    val hyperFloorApplies = preferences.get(BooleanKey.OApsAIMIT3cHyperBasalFloor) &&
        bg >= hyperFloorBgMgdl &&
        lookbacks.minBgInLastMinutes(hyperFloorDwellMin) >= hyperFloorBgMgdl
    val hyperFloorUph = if (hyperFloorApplies) profile.max_basal.coerceIn(0.0, maxBasalCap) else 0.0
    val t3cFlooredRate = t3cFinalRate.coerceAtLeast(hyperFloorUph)
    if (hyperFloorApplies && t3cFlooredRate > t3cFinalRate + 0.01) {
        consoleLog.add(
            "🧱 T3c hyper floor: BG≥${hyperFloorBgMgdl.toInt()} for ≥${hyperFloorDwellMin}m → basal held at maxBasal " +
                "${aimiFmt2(hyperFloorUph)}U/h (was ${aimiFmt2(t3cFinalRate)})"
        )
    }

    rT.rate = t3cFlooredRate
    rT.duration = 30
    rT.reason.append(
        "🛡️T3c | Thresh: ${activationThreshold.toInt()} | Agg: ${aimiFmt1(aggressiveness)} (raw=${aimiFmt1(rawAggressiveness)} AML=${aimiFmt2(adaptiveMult)}) | " +
            "ANT:${aimiFmt2(anticipationStrength)} | unlock=${fusion.unlock} | " +
            "PI/AD: ${aimiFmt2(t3cFlooredRate)}U/h (target=${aimiFmt2(targetRate)} cap=${aimiFmt2(maxBasalCap)} stepUp=${aimiFmt2(maxStepUp)})"
    )

    tail.applyBasalNeuralLearning(rT, t3cFlooredRate)
    consoleLog.add(rT.reason.toString())
    tail.markFinalLoopDecision(rT, currenttemp)
    return rT
}
