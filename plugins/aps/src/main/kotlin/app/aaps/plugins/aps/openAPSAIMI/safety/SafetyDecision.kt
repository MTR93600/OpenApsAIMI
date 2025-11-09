package app.aaps.plugins.aps.openAPSAIMI.safety

data class SafetyDecision(
    val stopBasal: Boolean,
    val bolusFactor: Double,
    val reason: String,
    val basalLS: Boolean
)
