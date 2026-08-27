package app.aaps.plugins.aps.openAPSAIMI.scenario

/**
 * One auditable adjustment applied while building [ScenarioProjectionPair.scenarioBest].
 */
data class ScenarioContributor(
    val id: ScenarioContributorId,
    val summary: String,
    val terminalDeltaMgdl: Double = 0.0,
)
