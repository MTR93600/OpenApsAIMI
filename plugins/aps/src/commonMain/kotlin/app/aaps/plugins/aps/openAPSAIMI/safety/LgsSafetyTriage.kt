package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult

/**
 * Pure LGS / noise triage formerly embedded in `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2.trySafetyStart`.
 * Callers apply [SafetyStartResolution.consoleLines] and [SafetyStartResolution.lastSafetySource].
 */
internal data class SafetyStartResolution(
    val decision: DecisionResult,
    val lastSafetySource: String,
    val consoleLines: List<String>,
    val haltRemainingPipeline: Boolean,
)

internal fun resolveSafetyStart(
    bg: Double,
    delta: Float,
    noise: Int,
    predBg: Double,
    eventualBg: Double,
    currentBasalUph: Double,
    lgsThreshold: Int?,
    mealContext: MealSafetyContext = MealSafetyContext(),
): SafetyStartResolution {
    val unitLines = buildList {
        if (bg < 25 || bg > 600) {
            add("⚠ Unit Mismatch Suspected? BG=$bg")
        }
    }

    fun safe(v: Double) = if (v.isFinite()) v else 999.0
    val bgNow = safe(bg)
    val predNow = safe(predBg)
    val eventualNow = safe(eventualBg)
    val lgsTh = HypoThresholdMath.computeHypoThreshold(
        minOf(bgNow, predNow, eventualNow),
        lgsThreshold,
    )

    val hypoInput = PredictiveHypoInput(
        bgNow = bgNow,
        predicted = predNow,
        eventual = eventualNow,
        hypoThreshold = lgsTh,
        delta = delta.toDouble(),
        mealContext = mealContext,
    )
    val suppression = PredictiveHypoEvaluator.evaluateSuppression(hypoInput)
    val consoleLines = unitLines.toMutableList()
    PredictiveHypoEvaluator.formatSuppressionLogLine(hypoInput, suppression, predNow, eventualNow)?.let {
        consoleLines.add(it)
    }

    val tierMatch = LgsTierRules.resolveTier(
        bgNow = bgNow,
        predNow = predNow,
        eventualNow = eventualNow,
        lgsTh = lgsTh,
        delta = delta,
        predictiveSuppressed = suppression.suppressed,
    )

    when (tierMatch?.tier) {
        LgsTierKind.TIER1_BG_REAL -> {
            val reasonStr =
                "LGS_BG_ACTUEL: BG=${bgNow.toInt()} <= Th=${lgsTh.toInt()} — Arrêt insuline total (pred=${predNow.toInt()} ev=${eventualNow.toInt()})"
            val line = "🛑 SAFETY_LGS_TIER1 $reasonStr"
            return SafetyStartResolution(
                decision = DecisionResult.Applied(
                    source = "SafetyLGS_T1",
                    bolusU = 0.0,
                    tbrUph = 0.0,
                    tbrMin = 30,
                    reason = reasonStr,
                ),
                lastSafetySource = "SafetyLGS_T1",
                consoleLines = consoleLines + line,
                haltRemainingPipeline = tierMatch.haltRemainingPipeline,
            )
        }
        LgsTierKind.TIER2_PRED_LOW -> {
            val safeBasal = currentBasalUph * 0.25
            val reasonStr =
                "LGS_PRED_LOW: pred=${predNow.toInt()} <= Th=${lgsTh.toInt()} (BG actuel=${bgNow.toInt()} OK) — Basale réduite 25%"
            val line = "🟠 SAFETY_LGS_TIER2 $reasonStr"
            return SafetyStartResolution(
                decision = DecisionResult.Applied(
                    source = "SafetyLGS_T2",
                    bolusU = 0.0,
                    tbrUph = safeBasal,
                    tbrMin = 30,
                    reason = reasonStr,
                ),
                lastSafetySource = "SafetyLGS_T2",
                consoleLines = consoleLines + line,
                haltRemainingPipeline = tierMatch.haltRemainingPipeline,
            )
        }
        LgsTierKind.TIER3_EVENTUAL_LOW -> {
            val safeBasal = currentBasalUph * 0.50
            val reasonStr =
                "LGS_EVENTUAL_LOW: ev=${eventualNow.toInt()} <= Th=${lgsTh.toInt()} (BG=${bgNow.toInt()} pred=${predNow.toInt()} OK) — Basale réduite 50%"
            val line = "🟡 SAFETY_LGS_TIER3 $reasonStr"
            return SafetyStartResolution(
                decision = DecisionResult.Applied(
                    source = "SafetyLGS_T3",
                    bolusU = 0.0,
                    tbrUph = safeBasal,
                    tbrMin = 15,
                    reason = reasonStr,
                ),
                lastSafetySource = "SafetyLGS_T3",
                consoleLines = consoleLines + line,
                haltRemainingPipeline = tierMatch.haltRemainingPipeline,
            )
        }
        null -> Unit
    }

    if (noise >= 3) {
        val reasonNoise = "High Noise ($noise) - Force TBR 0.0"
        return SafetyStartResolution(
            decision = DecisionResult.Applied(
                source = "SafetyNoise",
                bolusU = 0.0,
                tbrUph = 0.0,
                tbrMin = 30,
                reason = reasonNoise,
            ),
            lastSafetySource = "SafetyNoise",
            consoleLines = consoleLines,
            haltRemainingPipeline = true,
        )
    }

    return SafetyStartResolution(
        decision = DecisionResult.Fallthrough("Safety OK"),
        lastSafetySource = "SafetyPass",
        consoleLines = consoleLines,
        haltRemainingPipeline = false,
    )
}
