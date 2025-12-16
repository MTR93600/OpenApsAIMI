package app.aaps.plugins.aps.openAPSAIMI

sealed class AutodriveDecision {
    object Off : AutodriveDecision()
    data class Early(val amount: Double, val reason: String) : AutodriveDecision()
    data class Confirmed(val amount: Double, val reason: String) : AutodriveDecision()
}
