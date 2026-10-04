package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.smb.RiseCeilingGuard
import kotlin.math.max
import kotlin.math.min

/**
 * SMB finalize chain lifted out of `finalizeAndCapSMB`.
 *
 * The tick still reads preferences, the insulin observer, thyroid gates, SafetyNet and
 * the binding trace. This object only combines those already-computed values. Formulas
 * match the Android tick, including the original `proposedUnits` (before wait-bias)
 * on the red-carpet restore.
 */
internal object AimiSmbFinalizeMath {

    data class ThyroidGate(
        val block: Boolean = false,
        val capUnits: Float? = null,
    )

    data class Throttle(
        val smbFactor: Double,
        val intervalAddMin: Int,
        val preferTbr: Boolean,
        val reason: String,
    )

    data class Input(
        val proposedUnits: Double,
        val waitBias: Double = 1.0,
        val isExplicitUserAction: Boolean = false,
        val isMealActive: Boolean = false,
        val hyperReleaseFloorU: Double = 0.0,
        val bypassSmbRefractory: Boolean = false,
        val bg: Double = 180.0,
        val delta: Double = 2.0,
        val shortAvgDelta: Double = 1.0,
        val targetBg: Double = 100.0,
        val iob: Double = 1.0,
        val maxIob: Double = 10.0,
        val mealCob: Double = 0.0,
        val uamConfidence: Double = 0.0,
        val mealCompatibleProb: Double = 0.0,
        val suppressMealInterpretation: Boolean = false,
        val mealDeliveryPriority: Boolean = false,
        val rbtMealPriority: Boolean = false,
        val mealPriorityEligible: Boolean = false,
        val redCarpetEligible: Boolean = false,
        val mealSummary: String = "",
        val mealPhaseName: String = "legacy",
        val highBgBand: Double = 40.0,
        val baseLimit: Double = 5.0,
        val safetyUnits: Float = 2f,
        val maxSmb: Double = 2.0,
        val maxSmbHb: Double = 5.0,
        val predMissing: Boolean = false,
        val baseRefractoryMinutes: Double = 10.0,
        val lastBolusAgeMinutes: Double = 999.0,
        val thyroid: ThyroidGate = ThyroidGate(),
        val tdd24h: Double = 30.0,
        val iobActivityNow: Double = 0.0,
        val throttle: Throttle = Throttle(1.0, 0, false, ""),
        val stacking: InsulinStackingStance.Evaluation = InsulinStackingStance.Evaluation.ACTIVE_DEFAULT,
        val mealModeCondition: Boolean = false,
        val criticalSafetyZeroed: Boolean = false,
        val contextSuppressSmb: Boolean = false,
        val contextCeilingU: Double? = null,
        val slowCarbEarlyStartMs: Long? = null,
        val slowCarbBudgetU: Double = 2.0,
        val slowCarbWindowMs: Long = 0L,
        val slowCarbDeliveredU: Double = 0.0,
        val effortFactorRaw: Double = 1.0,
        val effortFactorApplied: Double = 1.0,
        val confirmedMeal: Boolean = false,
        val effortStateName: String = "",
        val effortPostureName: String = "",
        val surveillancePhrase: String = "",
        val riseCeilingArmed: Boolean = false,
        val ceilingRepeatCount: Int = 0,
        val ceilingRepeatLastMs: Long = 0L,
        val nowMs: Long = 0L,
        val format2f: (Float) -> String = { it.toString() },
    )

    data class Output(
        val effectiveProposed: Double,
        val finalUnits: Double,
        val mealPriorityContext: Boolean,
        val hyperTrajectoryPriorityContext: Boolean,
        val smbDeliveryPriorityContext: Boolean,
        val isRedCarpetSituation: Boolean,
        val smbFinalSource: String,
        val safeCap: Float,
        val gatedAfterStacking: Float,
        val sinceBolus: Double,
        val refractoryWindow: Double,
        val absorptionFactor: Double,
        val predMissing: Boolean,
        val activityThreshold: Double,
        val chainBaseLimit: Double,
        val chainSafetyCapped: Float,
        val chainAfterRefractory: Float,
        val beforeThrottle: Float,
        val chainAfterThrottle: Float,
        val afterRedCarpet: Double,
        val chainThrottleFactor: Double,
        val chainIntervalAdd: Int,
        val chainFinal: Double,
        val pkpdThrottleIntervalAdd: Int,
        val pkpdPreferTbrBoost: Double,
        val stackingReduced: Boolean,
        val slowCarbWindowMs: Long,
        val slowCarbDeliveredU: Double,
        val ceilingRepeatCount: Int,
        val ceilingRepeatLastMs: Long,
        val riseCeilingBlock: Boolean,
        val riseCeilingReason: String,
        val riseCeilingRepeats: Int,
        val riseCeilingWithheldU: Double,
        val effortFactorRaw: Double,
        val effortFactorApplied: Double,
        val effortBeforeU: Double,
        val effortAfterU: Double,
        val logs: List<String>,
        val reasonBits: List<String>,
        val mealPriorityChainLine: String?,
    )

    data class Contexts(
        val mealPriorityContext: Boolean,
        val hyperTrajectoryPriorityContext: Boolean,
        val smbDeliveryPriorityContext: Boolean,
        val effectiveProposed: Double,
    )

    fun contexts(input: Input): Contexts {
        val effectiveProposed = input.proposedUnits * input.waitBias
        val legacyMealPriority =
            !input.isExplicitUserAction &&
                !input.suppressMealInterpretation &&
                (
                    input.isMealActive ||
                        input.mealCob >= 6.0 ||
                        input.uamConfidence >= 0.45 ||
                        input.mealCompatibleProb >= 0.55
                    ) &&
                (input.bg >= 145.0) &&
                (input.delta >= 1.8 || input.shortAvgDelta >= 1.5) &&
                (input.iob < input.maxIob * 0.75)
        val mealPriorityContext =
            input.rbtMealPriority ||
                input.mealDeliveryPriority ||
                legacyMealPriority ||
                (!input.isExplicitUserAction && input.mealPriorityEligible)
        val hyperTrajectoryPriorityContext =
            !input.isExplicitUserAction &&
                input.hyperReleaseFloorU > 0.02 &&
                input.bg >= input.targetBg + input.highBgBand * 0.85 &&
                (input.delta >= 1.0 || input.shortAvgDelta >= 0.8) &&
                (input.iob < input.maxIob * 0.92)
        return Contexts(
            mealPriorityContext = mealPriorityContext,
            hyperTrajectoryPriorityContext = hyperTrajectoryPriorityContext,
            smbDeliveryPriorityContext = mealPriorityContext || hyperTrajectoryPriorityContext,
            effectiveProposed = effectiveProposed,
        )
    }

    fun openingLogs(input: Input): List<String> {
        val contexts = contexts(input)
        val logs = mutableListOf<String>()
        if (input.waitBias < 0.99) {
            logs += "⏳ RBT wait_bias: ${aimiFmt2(input.proposedUnits)}→${aimiFmt2(contexts.effectiveProposed)}U " +
                "(×${aimiFmt2(input.waitBias)})"
        }
        if (contexts.mealPriorityContext) {
            logs += "🍽️ MEAL_PRIORITY_CONTEXT ON (BG=${aimiFmt0(input.bg)} Δ=${aimiFmt1(input.delta)} " +
                "sΔ=${aimiFmt1(input.shortAvgDelta)} COB=${aimiFmt1(input.mealCob)} " +
                "UAM=${aimiFmt2(input.uamConfidence)} phase=${input.mealPhaseName} " +
                "IOB=${aimiFmt2(input.iob)}/${aimiFmt2(input.maxIob)} " +
                "implicit=${input.mealSummary.ifBlank { "none" }})"
        }
        if (contexts.hyperTrajectoryPriorityContext && !contexts.mealPriorityContext) {
            logs += "🚀 HTR_PRIORITY_CONTEXT ON (BG=${aimiFmt0(input.bg)} Δ=${aimiFmt1(input.delta)} " +
                "floor=${aimiFmt2(input.hyperReleaseFloorU)}U IOB=${aimiFmt2(input.iob)}/${aimiFmt2(input.maxIob)})"
        }
        return logs
    }

    fun decide(input: Input): Output = decideChain(input)

    private fun decideChain(input: Input): Output {
        val logs = mutableListOf<String>()
        val reasonBits = mutableListOf<String>()
        val contexts = contexts(input)
        val effectiveProposed = contexts.effectiveProposed
        val mealPriorityContext = contexts.mealPriorityContext
        val hyperTrajectoryPriorityContext = contexts.hyperTrajectoryPriorityContext
        val smbDeliveryPriorityContext = contexts.smbDeliveryPriorityContext
        val proposedFloat = effectiveProposed.toFloat()

        val safetyCappedUnits = input.safetyUnits.coerceAtMost(input.baseLimit.toFloat())

        val refractoryWindow = if (input.predMissing) {
            (input.baseRefractoryMinutes * 1.5).coerceAtLeast(5.0)
        } else {
            input.baseRefractoryMinutes
        }
        val sinceBolus = if (input.lastBolusAgeMinutes.isNaN()) 999.0 else input.lastBolusAgeMinutes
        val refractoryBlocked = sinceBolus < refractoryWindow && !input.isExplicitUserAction && !input.bypassSmbRefractory
        var gatedUnits = safetyCappedUnits
        var absorptionFactor = 1.0

        if (input.thyroid.block) {
            gatedUnits = 0f
            logs += "🦋 THYROID_GUARD: SMB Blocked (Normalizing Phase risk)"
            reasonBits += "🦋 Thyroid Guard: Blocked. "
        } else if (input.thyroid.capUnits != null) {
            val cap = input.thyroid.capUnits
            if (gatedUnits > cap) {
                logs += "🦋 THYROID_GUARD: SMB Capped to $cap (was $gatedUnits)"
                reasonBits += "🦋 Thyroid Guard: Cap ${cap}U. "
                gatedUnits = cap
            }
        }

        if (refractoryBlocked) {
            if (smbDeliveryPriorityContext) {
                val before = gatedUnits
                val refractoryProgress = (sinceBolus / refractoryWindow).coerceIn(0.0, 1.0)
                val relaxFactor = (0.35 + 0.35 * refractoryProgress).coerceIn(0.35, 0.70)
                gatedUnits = (gatedUnits * relaxFactor.toFloat()).coerceAtLeast(0f)
                logs += "⏸️➡️ REFRACTORY_RELAX_MEAL_PRIORITY sinceBolus=${aimiFmt1(sinceBolus)}m " +
                    "window=${aimiFmt1(refractoryWindow)}m progress=${aimiFmt2(refractoryProgress)} " +
                    "factor=${aimiFmt2(relaxFactor)} SMB ${aimiFmt2(before)}→${aimiFmt2(gatedUnits)}U"
            } else {
                gatedUnits = 0f
                logs += "⏸️ REFRACTORY_BLOCK sinceBolus=${aimiFmt1(sinceBolus)}m window=${aimiFmt1(refractoryWindow)}m (SMB blocked)"
            }
        } else if (sinceBolus < refractoryWindow && (input.isExplicitUserAction || input.bypassSmbRefractory)) {
            val bypassLabel = if (input.bypassSmbRefractory) "Classic autodrive prebolus" else "Meal mode override"
            logs += "✅ REFRACTORY_BYPASS sinceBolus=${aimiFmt1(sinceBolus)}m " +
                "window=${aimiFmt1(refractoryWindow)}m ($bypassLabel)"
        }
        val chainAfterRefractory = gatedUnits

        val activityThreshold = (input.tdd24h / 24.0) * 0.15
        if (sinceBolus < 20.0 && input.iobActivityNow > activityThreshold && !input.isExplicitUserAction && !smbDeliveryPriorityContext) {
            absorptionFactor = if (input.bg > input.targetBg + 60 && input.delta > 0) 0.75 else 0.5
            gatedUnits = (gatedUnits * absorptionFactor.toFloat()).coerceAtLeast(0f)
        }
        if (input.predMissing && !input.isExplicitUserAction) {
            val degraded = (input.maxSmb * 0.5).toFloat()
            if (gatedUnits > degraded) gatedUnits = degraded
        }
        val beforeThrottle = gatedUnits

        var chainThrottleFactor = 1.0
        var chainIntervalAdd = 0
        var pkpdPreferTbrBoost = 1.0
        var pkpdThrottleIntervalAdd = 0
        if (!input.isExplicitUserAction) {
            val effectiveSmbFactor = if (smbDeliveryPriorityContext) {
                input.throttle.smbFactor.coerceAtLeast(if (hyperTrajectoryPriorityContext) 0.88 else 0.80)
            } else {
                input.throttle.smbFactor
            }
            val effectiveIntervalAdd = if (smbDeliveryPriorityContext) {
                min(input.throttle.intervalAddMin, 1)
            } else {
                input.throttle.intervalAddMin
            }
            chainThrottleFactor = effectiveSmbFactor
            chainIntervalAdd = effectiveIntervalAdd
            val originalGated = gatedUnits
            gatedUnits = (gatedUnits * effectiveSmbFactor.toFloat()).coerceAtLeast(0f)
            if (effectiveSmbFactor < 1.0 || input.throttle.preferTbr) {
                logs += "PKPD_THROTTLE smbFactor=${aimiFmt2(effectiveSmbFactor)} intervalAdd=$effectiveIntervalAdd " +
                    "preferTbr=${input.throttle.preferTbr} reason=${input.throttle.reason}" +
                    when {
                        hyperTrajectoryPriorityContext -> " [HTR_PRIORITY_RELAX]"
                        mealPriorityContext -> " [MEAL_PRIORITY_RELAX]"
                        else -> ""
                    }
                if (originalGated > 0f && gatedUnits < originalGated * 0.6f) {
                    logs += "  ⚠️ SMB reduced ${input.format2f(originalGated)} → ${aimiFmt2(gatedUnits)}U (PKPD throttle)"
                }
            }
            if (input.throttle.preferTbr && gatedUnits < proposedFloat * 0.5) {
                reasonBits += " | 💡 TBR recommended (${input.throttle.reason})"
            }
            pkpdThrottleIntervalAdd = effectiveIntervalAdd
            pkpdPreferTbrBoost = if (input.throttle.preferTbr) 1.15 else 1.0
        }

        val chainAfterThrottle = gatedUnits
        var stackingReduced = false
        if (input.stacking.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
            val beforeSurv = gatedUnits
            val scaledSurv = (gatedUnits * input.stacking.smbMultiplier.toFloat())
                .coerceAtMost(input.stacking.smbAbsoluteCapU.toFloat())
                .coerceAtLeast(0f)
            gatedUnits = scaledSurv
            stackingReduced = beforeSurv > gatedUnits + 0.02f
            pkpdPreferTbrBoost = max(pkpdPreferTbrBoost, input.stacking.tbrBoostFloor)
            if (beforeSurv > gatedUnits + 0.02f) {
                logs += "🧭 IOB_SURVEILLANCE SMB ${aimiFmt2(beforeSurv)}→${aimiFmt2(gatedUnits)} | ${input.stacking.summary}"
                reasonBits += " | ${input.surveillancePhrase} [${input.stacking.summary}]"
            } else if (beforeSurv > 0.05f) {
                reasonBits += " | ${input.surveillancePhrase} [${input.stacking.summary}]"
            }
        }
        val gatedAfterStacking = gatedUnits

        val safeCap = capSmbDose(
            proposedSmb = gatedUnits,
            bg = input.bg,
            maxSmbConfig = input.baseLimit,
            iob = input.iob,
            maxIob = input.maxIob,
        )

        val isMealChaos = input.mealCob > 10.0 && input.delta > 5.0
        val isAimiContextMeal = !input.isExplicitUserAction && input.redCarpetEligible
        val isRedCarpetSituation = input.isExplicitUserAction || input.mealModeCondition || isAimiContextMeal ||
            ((isMealChaos || input.isMealActive) && input.proposedUnits > 0.5)
        var finalUnits: Double
        if (isRedCarpetSituation && input.proposedUnits > 0.0 && !input.stacking.suppressRedCarpetRestore) {
            if (isAimiContextMeal && !input.mealModeCondition) {
                logs += "🍽️ IMPLICIT_MEAL_REDCARPET ${input.mealSummary.ifBlank { "signals" }} " +
                    "(BG=${aimiFmt0(input.bg)} Δ=${aimiFmt1(input.delta)} sΔ=${aimiFmt1(input.shortAvgDelta)})"
            }
            val candidateUnits = if (gatedUnits < input.proposedUnits.toFloat() * 0.6f) {
                if (input.criticalSafetyZeroed) {
                    logs += "⛔ RED_CARPET_DENIED: vital hypo safety zeroed SMB this tick — no restore (Proposed=${aimiFmt2(input.proposedUnits)} Gated=${aimiFmt2(gatedUnits)})"
                    gatedUnits
                } else {
                    logs += "✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=${aimiFmt2(input.proposedUnits)} vs Gated=${aimiFmt2(gatedUnits)})"
                    input.proposedUnits.toFloat()
                }
            } else {
                gatedUnits
            }
            val maxSmbCap = if (input.maxSmbHb > input.baseLimit) input.maxSmbHb.toFloat() else input.baseLimit.toFloat()
            var mealBolus = min(candidateUnits, maxSmbCap)
            val iobSpace = (input.maxIob - input.iob).coerceAtLeast(0.0)
            logs += "MEAL_DEBUG Need=${aimiFmt2(candidateUnits)} MaxSMB=${aimiFmt2(input.baseLimit)} MaxSMBHB=${aimiFmt2(input.maxSmbHb)} Cap=${aimiFmt2(maxSmbCap)} MaxIOB=${aimiFmt2(input.maxIob)} IOB=${aimiFmt2(input.iob)} Space=${aimiFmt2(iobSpace)}"
            if (mealBolus > iobSpace.toFloat()) {
                logs += "🛡️ RED CARPET: Clamped by MaxIOB (Need=${aimiFmt2(mealBolus)}, Space=${aimiFmt2(iobSpace)})"
                mealBolus = iobSpace.toFloat()
            }
            mealBolus = mealBolus.coerceAtMost(30f)
            finalUnits = mealBolus.toDouble()
            if (finalUnits > gatedUnits + 0.1) {
                val reason = when {
                    input.isExplicitUserAction -> "UserAction"
                    isMealChaos -> "CarbChaos"
                    isAimiContextMeal -> "ImplicitMeal:${input.mealSummary.ifBlank { "signals" }}"
                    else -> "MealMode/Context"
                }
                logs += "🍱 MEAL_FORCE_EXECUTED ($reason): ${aimiFmt2(finalUnits)} U (Overrides minor safety checks)"
            }
        } else {
            finalUnits = safeCap.toDouble()
        }
        val afterRedCarpet = finalUnits
        if (input.hyperReleaseFloorU > 0.0 && !isRedCarpetSituation) {
            val iobSpace = (input.maxIob - input.iob).coerceAtLeast(0.0)
            val floorCap = minOf(input.hyperReleaseFloorU, iobSpace, input.baseLimit)
            if (finalUnits + 0.02 < floorCap) {
                logs += "🚀 HTR finalize floor: ${aimiFmt2(finalUnits)}→${aimiFmt2(floorCap)}U " +
                    "(hyperReleaseFloor=${aimiFmt2(input.hyperReleaseFloorU)}U)"
                finalUnits = floorCap
            }
        }

        var slowCarbWindow = input.slowCarbWindowMs
        var slowCarbDelivered = input.slowCarbDeliveredU
        var chargeSlowCarb = false
        if (!input.isExplicitUserAction && finalUnits > 0.0) {
            if (input.contextSuppressSmb) {
                logs += "🍬 CTX_HYPO_RECOVERY: SMB off (was ${aimiFmt2(finalUnits)}U)"
                reasonBits += "🍬hypoRecovery SMB off "
                finalUnits = 0.0
            } else {
                input.contextCeilingU?.let { ceil ->
                    if (finalUnits > ceil) {
                        logs += "🍕 CTX_SMB_CEILING: ${aimiFmt2(finalUnits)}→${aimiFmt2(ceil)}U"
                        reasonBits += "🍕slowCarb cap${aimiFmt1(ceil)} "
                        finalUnits = ceil.coerceAtLeast(0.0)
                    }
                }
                val slowCarbEarlyStart = input.slowCarbEarlyStartMs
                if (slowCarbEarlyStart != null && finalUnits > 0.0) {
                    if (slowCarbEarlyStart != slowCarbWindow) {
                        slowCarbWindow = slowCarbEarlyStart
                        slowCarbDelivered = 0.0
                    }
                    val remaining = (input.slowCarbBudgetU - slowCarbDelivered).coerceAtLeast(0.0)
                    if (finalUnits > remaining) {
                        logs += "🍕 CTX_SLOWCARB_BUDGET: ${aimiFmt2(finalUnits)}→${aimiFmt2(remaining)}U (used ${aimiFmt2(slowCarbDelivered)}/${aimiFmt1(input.slowCarbBudgetU)}U)"
                        reasonBits += "🍕slowCarb budget "
                        finalUnits = remaining
                    }
                    chargeSlowCarb = true
                }
            }
        }

        val rawEffort = input.effortFactorRaw
        val effortFactor = input.effortFactorApplied
        var effortBefore = finalUnits
        var effortAfter = finalUnits
        if (effortFactor < 1.0 && !input.isExplicitUserAction && finalUnits > 0.0) {
            val beforeEffort = finalUnits
            finalUnits = (finalUnits * effortFactor).coerceAtLeast(0.0)
            effortBefore = beforeEffort
            effortAfter = finalUnits
            val floored = input.confirmedMeal && effortFactor > rawEffort + 1e-9
            logs += "🏃 EFFORT_PROTECT_SMB ×${aimiFmt2(effortFactor)} " +
                "${aimiFmt2(beforeEffort)}→${aimiFmt2(finalUnits)}U " +
                "[${input.effortStateName}/${input.effortPostureName}]" +
                if (floored) " (floored from ×${aimiFmt2(rawEffort)}, meal certainty HIGH)" else ""
            reasonBits += "🏃effort×${aimiFmt2(effortFactor)} "
        }

        val atSmbCeiling = RiseCeilingGuard.isAtCeiling(
            units = finalUnits,
            ceilingU = input.baseLimit,
            highGlucoseCeilingU = input.maxSmbHb,
        )
        val nextRepeats = RiseCeilingGuard.nextRepeatCount(
            previous = input.ceilingRepeatCount,
            previousMs = input.ceilingRepeatLastMs,
            nowMs = input.nowMs,
            atCeiling = atSmbCeiling,
        )
        val ceilingLastMs = if (atSmbCeiling) input.nowMs else input.ceilingRepeatLastMs
        val riseVerdict = RiseCeilingGuard.evaluate(
            atCeiling = atSmbCeiling,
            repeats = nextRepeats,
            deltaMgdl5m = input.delta,
        )
        val withheldU = if (riseVerdict.block) finalUnits else 0.0
        val withhold = RiseCeilingGuard.shouldWithhold(
            verdict = riseVerdict,
            armed = input.riseCeilingArmed,
            isExplicitUserAction = input.isExplicitUserAction,
            proposedUnits = finalUnits,
        )
        if (withhold) {
            logs += "🧱 RISE_CEILING_GUARD: ${aimiFmt2(finalUnits)}→0.00U (${riseVerdict.reason})"
            reasonBits += "🧱rise ceiling "
            finalUnits = 0.0
        }
        if (chargeSlowCarb && finalUnits > 0.0) slowCarbDelivered += finalUnits

        val mealPriorityChainLine = if (mealPriorityContext) {
            "🍽️ MEAL_PRIORITY_CHAIN proposed=${aimiFmt2(proposedFloat)} " +
                "baseLimit=${aimiFmt2(input.baseLimit)} safety=${aimiFmt2(safetyCappedUnits)} " +
                "refr=${aimiFmt2(chainAfterRefractory)} throttle=${aimiFmt2(chainAfterThrottle)} " +
                "tf=${aimiFmt2(chainThrottleFactor)} iAdd=+$chainIntervalAdd " +
                "final=${aimiFmt2(finalUnits)}"
        } else {
            null
        }

        val smbFinalSource =
            if (isRedCarpetSituation && input.proposedUnits > 0.0 && !input.stacking.suppressRedCarpetRestore) {
                "red_carpet"
            } else {
                "standard_safe_cap"
            }

        return Output(
            effectiveProposed = effectiveProposed,
            finalUnits = finalUnits,
            mealPriorityContext = mealPriorityContext,
            hyperTrajectoryPriorityContext = hyperTrajectoryPriorityContext,
            smbDeliveryPriorityContext = smbDeliveryPriorityContext,
            isRedCarpetSituation = isRedCarpetSituation,
            smbFinalSource = smbFinalSource,
            safeCap = safeCap,
            gatedAfterStacking = gatedAfterStacking,
            sinceBolus = sinceBolus,
            refractoryWindow = refractoryWindow,
            absorptionFactor = absorptionFactor,
            predMissing = input.predMissing,
            activityThreshold = activityThreshold,
            chainBaseLimit = input.baseLimit,
            chainSafetyCapped = safetyCappedUnits,
            chainAfterRefractory = chainAfterRefractory,
            beforeThrottle = beforeThrottle,
            chainAfterThrottle = chainAfterThrottle,
            afterRedCarpet = afterRedCarpet,
            chainThrottleFactor = chainThrottleFactor,
            chainIntervalAdd = chainIntervalAdd,
            chainFinal = finalUnits,
            pkpdThrottleIntervalAdd = pkpdThrottleIntervalAdd,
            pkpdPreferTbrBoost = pkpdPreferTbrBoost,
            stackingReduced = stackingReduced,
            slowCarbWindowMs = slowCarbWindow,
            slowCarbDeliveredU = slowCarbDelivered,
            ceilingRepeatCount = nextRepeats,
            ceilingRepeatLastMs = ceilingLastMs,
            riseCeilingBlock = riseVerdict.block,
            riseCeilingReason = riseVerdict.reason,
            riseCeilingRepeats = riseVerdict.repeats,
            riseCeilingWithheldU = withheldU,
            effortFactorRaw = rawEffort,
            effortFactorApplied = effortFactor,
            effortBeforeU = effortBefore,
            effortAfterU = effortAfter,
            logs = logs,
            reasonBits = reasonBits,
            mealPriorityChainLine = mealPriorityChainLine,
        )
    }
}
