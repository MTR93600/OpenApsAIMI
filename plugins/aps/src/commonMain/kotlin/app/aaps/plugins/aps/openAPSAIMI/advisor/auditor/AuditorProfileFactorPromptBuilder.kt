package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble

/**
 * The prompt of the second, separate request: the profile check.
 *
 * It is kept apart from the audit verdict on purpose. The verdict already moves doses for users in
 * soft modulation, so its prompt must not change at all while this work is behind an opt-in key.
 * This prompt is small, plain English and asks for one thing only: two small factors, with the six
 * numbers that justify them copied straight from the data.
 */
object AuditorProfileFactorPromptBuilder {

    /** Builds the prompt for one audited tick. */
    fun buildPrompt(context: AuditorProfileContext, verdict: AuditorVerdict): String = """
You are the AIMI profile checker. You never give insulin doses.
You may propose two small factors for the next minutes only:
- isfFactor: multiplies the insulin sensitivity (ISF) the loop uses.
  Below 1.0 = the body needs MORE insulin than the loop thinks (resistance).
  Above 1.0 = the body needs LESS insulin than the loop thinks (more sensitive).
- targetFactor: multiplies the glucose target the loop aims at.
  Below 1.0 = aim lower (more insulin). Above 1.0 = aim higher (less insulin).
Both must be between ${AuditorProfileFactorLimits.FACTOR_MIN} and ${AuditorProfileFactorLimits.FACTOR_MAX}. Use 1.0 when you are not sure. 1.0 is always a good answer.

${LlmWorldConservativePreamble.FOR_JSON_CONTRACT}

RULES
1. Look only at the 30-minute data below. Do not guess what happened before or after.
2. Propose below 1.0 only if glucose stayed high or kept rising while insulin was clearly absorbed
   (insulin_u.absorbed at least 0.8 U), with no carbs, no meal mode and meal_certainty NONE or LOW.
3. Propose above 1.0 only if glucose fell faster than the loop expected, or it is close to a low.
4. isfFactor and targetFactor must go in the same direction (both <= 1.0 or both >= 1.0).
5. If an undeclared meal, exercise, stress, illness or a sensor error could explain the data, say so
   in competingHypothesis. Then do not propose below 1.0.
6. Copy the six numbers in "claims" from the data below. Do not compute new numbers. Do not round
   by more than 1 decimal.
7. The loop keeps all its safety limits after your factors. Your factors can be refused by the loop.

MAIN VERDICT ALREADY GIVEN FOR THIS TICK: ${verdict.verdict.name}, confidence ${verdict.confidence}

DATA (30 minutes, mg/dL and U)
${context.toPromptJson()}

OUTPUT: JSON only, no other text:
{
  "isfFactor": 1.0,
  "targetFactor": 1.0,
  "reasonCode": "NONE | RESISTANCE_UNDER_CORRECTION | SENSITIVITY_OVER_CORRECTION",
  "confidence": 0.0,
  "competingHypothesis": "none | undeclared_meal | exercise | stress_or_illness | sensor_error | other",
  "claims": {
    "bg_start_mgdl": 0, "bg_end_mgdl": 0, "bg_min_mgdl": 0,
    "iob_start_u": 0, "iob_end_u": 0, "insulin_delivered_u": 0
  },
  "rationale": "max ${AuditorProfileFactorLimits.RATIONALE_MAX_CHARS} characters"
}
    """.trimIndent()
}
