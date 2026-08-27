package app.aaps.plugins.aps.openAPSAIMI.model

sealed class AdvisorSeverity {

    object Good : AdvisorSeverity() {
        override fun toString() = "Good"
    }

    data class Warning(val reason: String) : AdvisorSeverity() {
        override fun toString() = "Warning: $reason"
    }

    data class Critical(val clinicalDanger: String) : AdvisorSeverity() {
        override fun toString() = "CRITICAL: $clinicalDanger"
    }
}
