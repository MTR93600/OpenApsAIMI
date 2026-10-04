package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.max
import kotlin.math.min

/**
 * Numeric policy of `runPkpdGuardEndoDampenRedCarpetAndCapSmb`.
 *
 * The tick still applies the PKPD absorption guard, the binding trace and the
 * endo dampen log. These functions are the MaxIOB relief and the legacy red carpet.
 * They are not the same formulas as [AimiSmbFinalizeMath]: the restore threshold
 * and the MaxIOB used for the clamp differ, and that difference is kept.
 */
internal object AimiLegacySmbCapMath {

    fun currentMaxSmb(
        isExplicitAdvisorRun: Boolean,
        bg: Double,
        honeymoon: Boolean,
        slopeFromMinDeviation: Double,
        mealLunchDinnerOrHc: Boolean,
        maxSmb: Double,
        maxSmbHb: Double,
    ): Double = if (isExplicitAdvisorRun) {
        max(maxSmbHb, 10.0)
    } else if ((bg > 120 && !honeymoon && slopeFromMinDeviation >= 1.0) || (mealLunchDinnerOrHc && bg > 100)) {
        maxSmbHb
    } else {
        maxSmb
    }

    data class IobRelief(
        val effectiveMaxIobForPriority: Double,
        val iobTargetU: Double?,
        val effectiveMaxIobForDebridage: Double,
        val isAggressiveRise: Boolean,
        val logs: List<String>,
    )

    fun iobRelief(
        pkpdReliefEnabled: Boolean,
        isAggressivePriorityContext: Boolean,
        maxIob: Double,
        priorityMaxIobFactor: Double,
        priorityMaxIobExtraU: Double,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        predictedBg: Double,
        eventualBg: Double,
    ): IobRelief {
        val effectiveMaxIobForPriority = if (pkpdReliefEnabled && isAggressivePriorityContext) {
            val uplift = (maxIob * priorityMaxIobFactor).coerceAtMost(maxIob + priorityMaxIobExtraU)
            uplift.coerceAtMost(25.0)
        } else {
            maxIob
        }
        val isAggressiveRise =
            (bg >= 140.0 && (delta >= 15.0 || shortAvgDelta >= 10.0)) &&
                (predictedBg >= 160.0 || eventualBg >= 160.0)
        val iobTargetU: Double? =
            if (pkpdReliefEnabled && isAggressivePriorityContext && isAggressiveRise) {
                val base = when {
                    bg >= 250.0 -> 10.0
                    bg >= 200.0 -> 9.0
                    bg >= 170.0 -> 8.0
                    else -> 6.0
                }
                val velocityBonus = when {
                    delta >= 30.0 || shortAvgDelta >= 20.0 -> 2.0
                    delta >= 22.0 || shortAvgDelta >= 15.0 -> 1.0
                    else -> 0.0
                }
                (base + velocityBonus).coerceIn(5.0, 12.0)
            } else {
                null
            }
        val effectiveMaxIobForDebridage =
            if (iobTargetU != null) max(effectiveMaxIobForPriority, iobTargetU).coerceAtMost(25.0)
            else effectiveMaxIobForPriority
        val logs = mutableListOf<String>()
        if (isAggressivePriorityContext && pkpdReliefEnabled) {
            if (effectiveMaxIobForPriority > maxIob) {
                logs += "MAXIOB_RELIEF: ${aimiFmt2(maxIob)} -> ${aimiFmt2(effectiveMaxIobForPriority)} (priority context)"
            }
            if (effectiveMaxIobForDebridage > effectiveMaxIobForPriority + 0.01) {
                logs += "MEAL_DEBRIDAGE_MAXIOB: ${aimiFmt2(effectiveMaxIobForPriority)} -> ${aimiFmt2(effectiveMaxIobForDebridage)} " +
                    "(target=${iobTargetU?.let { aimiFmt2(it) } ?: "n/a"}U, BG=${aimiFmt0(bg)}, Δ=${aimiFmt1(delta)})"
            }
        }
        return IobRelief(
            effectiveMaxIobForPriority,
            iobTargetU,
            effectiveMaxIobForDebridage,
            isAggressiveRise,
            logs,
        )
    }

    data class RedCarpet(
        val units: Float,
        val isRedCarpetSituation: Boolean,
        val logs: List<String>,
        val reasonCap: String?,
    )

    fun redCarpetOrCap(
        smbAfterGuards: Float,
        proposedUnits: Float,
        finalSmb: Double,
        isExplicitAction: Boolean,
        anyMealMode: Boolean,
        redCarpetEligible: Boolean,
        mealSummary: String,
        isConfirmedHighRise: Boolean,
        mealCob: Double,
        delta: Double,
        bg: Double,
        shortAvgDelta: Double,
        pkpdReliefEnabled: Boolean,
        isAggressivePriorityContext: Boolean,
        restoreThresholdPref: Float,
        criticalSafetyZeroed: Boolean,
        suppressRedCarpet: Boolean,
        suppressSummary: String,
        currentMaxSmb: Double,
        maxSmbHb: Double,
        effectiveMaxIob: Double,
        iobForCap: Double,
        memberIob: Double,
    ): RedCarpet {
        val logs = mutableListOf<String>()
        val isMealChaos = mealCob > 10.0 && delta > 5.0
        val isRedCarpetSituation =
            isExplicitAction || anyMealMode || redCarpetEligible || isConfirmedHighRise ||
                (isMealChaos && finalSmb > 0.5)
        if (!(isRedCarpetSituation && proposedUnits > 0f)) {
            val capped = capSmbDose(smbAfterGuards, bg, currentMaxSmb, iobForCap, effectiveMaxIob)
            val reason = if (capped < smbAfterGuards) " | 🛡️ Cap: ${aimiFmt2(smbAfterGuards)} → ${aimiFmt2(capped)}" else null
            return RedCarpet(capped, isRedCarpetSituation, logs, reason)
        }
        if (redCarpetEligible && !anyMealMode && !isExplicitAction) {
            logs += "🍽️ IMPLICIT_MEAL_REDCARPET ${mealSummary.ifBlank { "signals" }} " +
                "(BG=${aimiFmt0(bg)} Δ=${aimiFmt1(delta)} sΔ=${aimiFmt1(shortAvgDelta)})"
        }
        val baseRestoreThreshold = 0.60f
        val restoreThreshold = if (isAggressivePriorityContext && pkpdReliefEnabled) {
            max(baseRestoreThreshold, restoreThresholdPref)
        } else {
            baseRestoreThreshold
        }
        val candidateUnits = if (smbAfterGuards <= proposedUnits * restoreThreshold) {
            when {
                criticalSafetyZeroed -> {
                    logs += "⛔ RED_CARPET_DENIED: vital hypo safety zeroed SMB this tick — no restore (Proposed=${aimiFmt2(proposedUnits)} Gated=${aimiFmt2(smbAfterGuards)})"
                    smbAfterGuards
                }
                suppressRedCarpet -> {
                    logs += "⛔ RED_CARPET_DENIED: IOB surveillance active — no restore (${suppressSummary.ifBlank { "surveillance" }})"
                    smbAfterGuards
                }
                else -> {
                    logs += "✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=${aimiFmt2(proposedUnits)} vs Gated=${aimiFmt2(smbAfterGuards)})"
                    proposedUnits
                }
            }
        } else {
            smbAfterGuards
        }
        val redCarpetMaxSmb = max(currentMaxSmb, maxSmbHb)
        var mealBolus = min(candidateUnits.toDouble(), redCarpetMaxSmb).toFloat()
        val iobSpace = (effectiveMaxIob - memberIob).coerceAtLeast(0.0)
        if (mealBolus > iobSpace.toFloat()) {
            logs += "🛡️ RED CARPET: Clamped by MaxIOB (Need=${aimiFmt2(mealBolus)}, Space=${aimiFmt2(iobSpace)})"
            mealBolus = iobSpace.toFloat()
        }
        mealBolus = mealBolus.coerceAtMost(30f)
        if (mealBolus.toDouble() > smbAfterGuards + 0.1) {
            val reason = when {
                isExplicitAction -> "UserAction"
                isMealChaos -> "CarbChaos"
                redCarpetEligible -> "ImplicitMeal:${mealSummary.ifBlank { "signals" }}"
                else -> "MealMode"
            }
            logs += "🍱 MEAL_FORCE_EXECUTED ($reason): ${aimiFmt2(mealBolus)} U (Overrides minor safety checks)"
        }
        val reasonCap = if (mealBolus < smbAfterGuards) {
            " | 🛡️ Cap: ${aimiFmt2(smbAfterGuards)} → ${aimiFmt2(mealBolus)}"
        } else {
            null
        }
        return RedCarpet(mealBolus, isRedCarpetSituation, logs, reasonCap)
    }
}
