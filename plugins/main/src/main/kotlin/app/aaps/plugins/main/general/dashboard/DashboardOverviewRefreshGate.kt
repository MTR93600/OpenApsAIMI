package app.aaps.plugins.main.general.dashboard

import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopRuntimeGuard

/**
 * Defers heavy overview/dashboard Rx refreshes while an AIMI determine_basal tick holds the loop lock.
 */
internal object DashboardOverviewRefreshGate {

    fun deferMsIfAimiTickActive(): Long = AimiLoopRuntimeGuard.overviewRefreshDeferMs()
}
