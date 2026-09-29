package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

/**
 * The one thing the auditor writers need from the status view: "the status changed, read it again".
 *
 * `AuditorAIService` and `AuditorOrchestrator` never read the state back. They only announce that
 * [AuditorStatusTracker] or [AuditorVerdictCache] now holds something new. Naming that single call
 * here lets them live in shared code, while the Android side keeps its `LiveData` implementation in
 * `advisor/auditor/ui/`, where the Overview chip and the notification read it.
 *
 * The implementation must be one instance for the whole app. Two instances would mean two states,
 * and the chip would observe the one nobody writes to.
 */
interface AuditorStatusNotifier {

    /**
     * Reads [AuditorStatusTracker] again and publishes the resulting state to whoever observes it.
     *
     * Can be called from any thread.
     */
    fun notifyUpdate()
}
