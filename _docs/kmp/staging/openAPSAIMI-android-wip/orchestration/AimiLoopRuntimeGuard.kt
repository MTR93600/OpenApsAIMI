package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * Public surface for UI / app shell to avoid colliding with an in-flight AIMI loop tick.
 */
object AimiLoopRuntimeGuard {

    /** True while [AimiLoopTelemetry.traceDetermineBasalTick] holds an active tick id. */
    fun isDetermineBasalTickInProgress(): Boolean = AimiLoopTelemetry.isTickInProgress()

    /** Milliseconds since the active tick started; 0 when idle. */
    fun activeTickAgeMs(): Long = AimiLoopTelemetry.activeTickAgeMs()

    /** Defer heavy overview/dashboard refresh while a determine_basal tick holds the loop lock. */
    fun overviewRefreshDeferMs(): Long = if (isDetermineBasalTickInProgress()) 2_500L else 0L
}
