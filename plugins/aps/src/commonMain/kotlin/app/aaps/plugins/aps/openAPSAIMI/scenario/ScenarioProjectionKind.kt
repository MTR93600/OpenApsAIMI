package app.aaps.plugins.aps.openAPSAIMI.scenario

/**
 * Authoritative scenario curves exposed to UI, safety, and export.
 *
 * - [CLINICAL_FLOOR] — pessimistic insulin-led path for LGS / hypo composite.
 * - [SCENARIO_BEST] — fused metabolic scenario for display and decision narrative.
 */
enum class ScenarioProjectionKind {
    CLINICAL_FLOOR,
    SCENARIO_BEST,
}
