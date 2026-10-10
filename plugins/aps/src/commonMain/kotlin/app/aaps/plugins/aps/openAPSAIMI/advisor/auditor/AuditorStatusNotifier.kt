package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

/**
 * The one thing the auditor writers need from the status view: "the status changed, read it again".
 *
 * [AuditorAIService] and [AuditorOrchestrator] never read the state back. They only announce that
 * [AuditorStatusTracker] or [AuditorVerdictCache] now holds something new. Naming that single call
 * here keeps the writers free of the state they write to.
 *
 * The one implementation is
 * [app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusStateHolder], in shared code,
 * so both phones publish the same state from the same rule. It must be one instance for the whole
 * app: two instances would mean two states, and the chip would read the one nobody writes to.
 */
interface AuditorStatusNotifier {

    /**
     * Reads [AuditorStatusTracker] again and publishes the resulting state to whoever observes it.
     *
     * Can be called from any thread.
     */
    fun notifyUpdate()
}
