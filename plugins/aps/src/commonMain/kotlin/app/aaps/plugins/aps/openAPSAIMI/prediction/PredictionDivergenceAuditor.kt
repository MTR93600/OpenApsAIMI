package app.aaps.plugins.aps.openAPSAIMI.prediction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import kotlin.math.roundToInt

/**
 * Per-tick divergence between the two eventual-BG paths (audit 2026-06, log-only):
 *
 * - `pkpdEventualMgdl` — PKPD eventual (insulin/carb kinetics; physio enters only as the scalar
 *   ISF factor through `variableSensitivity`). This is the value the SMB gates read
 *   ([app.aaps.plugins.aps.openAPSAIMI.safety.SafetyNet], stacking stance).
 * - `scenarioBestMgdl` — scenario-best terminal (physio phases, meal absorption, trajectory,
 *   activity layers). This is the value the hypo/safety pipeline reads.
 *
 * Goal: quantify how often the SafetyNet zone-2 predictive low clamp
 * (`bg < 170 && eventual < 120 -> maxSmbLow`) fires on the PKPD value while the enriched
 * scenario disagrees, before deciding any dosing-path fusion. No behaviour impact.
 */
data class PredictionDivergenceAudit(
    val bgMgdl: Double,
    val pkpdEventualMgdl: Double,
    val scenarioBestMgdl: Double?,
    /** scenario − pkpd; positive = the enriched scenario sees a higher terminal. */
    val divergenceMgdl: Double?,
    val pkpdTriggersLowClamp: Boolean,
    val scenarioTriggersLowClamp: Boolean?,
    /** PKPD would clamp the SMB limit to the low max but the enriched scenario would not. */
    val lowClampDisagreement: Boolean,
)

object PredictionDivergenceAuditor {

    // Audit-only mirror of the SafetyNet zone-2 predictive clamp thresholds (keep in sync with
    // SafetyNet.calculateSafeSmbLimit). Below 120 mg/dL the low max applies regardless of the
    // eventual, so the eventual-driven clamp only discriminates inside [120, 170).
    private const val ZONE2_BG_MIN_MGDL = 120.0
    private const val ZONE2_BG_MAX_MGDL = 170.0
    private const val LOW_CLAMP_EVENTUAL_MGDL = 120.0

    fun audit(
        bgMgdl: Double,
        pkpdEventualMgdl: Double,
        scenarioBestMgdl: Double?,
    ): PredictionDivergenceAudit {
        val scenario = scenarioBestMgdl?.takeIf { it.isFinite() }
        val inZone2 = bgMgdl >= ZONE2_BG_MIN_MGDL && bgMgdl < ZONE2_BG_MAX_MGDL
        val pkpdClamp = inZone2 && pkpdEventualMgdl < LOW_CLAMP_EVENTUAL_MGDL
        val scenarioClamp = scenario?.let { inZone2 && it < LOW_CLAMP_EVENTUAL_MGDL }
        return PredictionDivergenceAudit(
            bgMgdl = bgMgdl,
            pkpdEventualMgdl = pkpdEventualMgdl,
            scenarioBestMgdl = scenario,
            divergenceMgdl = scenario?.minus(pkpdEventualMgdl),
            pkpdTriggersLowClamp = pkpdClamp,
            scenarioTriggersLowClamp = scenarioClamp,
            lowClampDisagreement = pkpdClamp && scenarioClamp == false,
        )
    }

    /** `adjustments.pred_divergence` object for AIMI_Decisions.jsonl (same fields as the log line). */
    fun toJsonObject(
        audit: PredictionDivergenceAudit,
        physioPhase: String?,
        mealPhase: String?,
    ): JsonObject = buildJsonObject {
        put("bg_mgdl", audit.bgMgdl)
        put("pkpd_eventual_mgdl", audit.pkpdEventualMgdl)
        put("scenario_best_mgdl", audit.scenarioBestMgdl)
        put("divergence_mgdl", audit.divergenceMgdl)
        put("physio_phase", physioPhase)
        put("meal_phase", mealPhase)
        put("pkpd_triggers_low_clamp", audit.pkpdTriggersLowClamp)
        put("scenario_triggers_low_clamp", audit.scenarioTriggersLowClamp)
        put("low_clamp_disagreement", audit.lowClampDisagreement)
    }

    fun formatLogLine(
        audit: PredictionDivergenceAudit,
        physioPhase: String?,
        mealPhase: String?,
    ): String = buildString {
        append("PRED_DIVERGENCE: bg=${audit.bgMgdl.roundToInt()}")
        append(" evPkpd=${audit.pkpdEventualMgdl.roundToInt()}")
        append(" bestScn=${audit.scenarioBestMgdl?.roundToInt() ?: "-"}")
        append(" Δ=${audit.divergenceMgdl?.roundToInt() ?: "-"}")
        append(" phase=${physioPhase ?: "-"}")
        append(" meal=${mealPhase ?: "-"}")
        append(" clampPkpd=${audit.pkpdTriggersLowClamp}")
        append(" clampScn=${audit.scenarioTriggersLowClamp ?: "-"}")
        if (audit.lowClampDisagreement) append(" ⚠️CLAMP_DISAGREE")
    }
}
