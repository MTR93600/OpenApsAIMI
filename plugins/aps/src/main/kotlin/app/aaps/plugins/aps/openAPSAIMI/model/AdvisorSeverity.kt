package app.aaps.plugins.aps.openAPSAIMI.model

/**
 * 🛡️ AdvisorSeverity
 * 
 * Represents the clinical severity and confidence level of an advisor's recommendation.
 * Used by the Auditor to weigh conflicting advice and by the UI to style alerts.
 * 
 * DESIGN PRINCIPLE:
 * - Sealed classes ensure exhaustive handling in 'when' expressions across the loop.
 */
sealed class AdvisorSeverity {
    
    /**
     * ✅ Good: Recommendation is within safe bounds and high confidence.
     * No additional safeguards required beyond standard checks.
     */
    object Good : AdvisorSeverity() {
        override fun toString() = "Good"
    }

    /**
     * ⚠️ Warning: Recommendation presents a potential risk or requires verification.
     * Triggers additional audit levels or visual alerts for the user.
     * 
     * @property reason Clinical justification for the warning.
     */
    data class Warning(val reason: String) : AdvisorSeverity() {
        override fun toString() = "Warning: $reason"
    }

    /**
     * 🚨 Critical: Recommendation is high risk or violates physiological safety gates.
     * Requires immediate Auditor intervention or hard safety halt.
     * 
     * @property clinicalDanger Detailed description of the detected risk (e.g., "MaxIOB Violation").
     */
    data class Critical(val clinicalDanger: String) : AdvisorSeverity() {
        override fun toString() = "CRITICAL: $clinicalDanger"
    }
}
