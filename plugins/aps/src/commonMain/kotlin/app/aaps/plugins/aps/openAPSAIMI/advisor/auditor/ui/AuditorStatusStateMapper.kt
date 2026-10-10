package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType

/**
 * Pure mapping from [AuditorStatusTracker.Status] to [AuditorUIState].
 *
 * Extracted from the Android `AuditorStatusLiveData` so shared code (and iOS) can compute
 * the same UI state without `androidx.lifecycle.LiveData`. The platform holder keeps the
 * reactive plumbing; this object owns the decision.
 */
object AuditorStatusStateMapper {

    fun transformStatusToUIState(
        status: AuditorStatusTracker.Status,
        ageMs: Long?,
        lastReadTimestampMs: Long,
    ): AuditorUIState {
        if (status.name.contains("PROCESSING")) {
            return AuditorUIState.processing()
        }

        val resolved = AuditorVerdictCache.resolveForDisplay()
        if (resolved != null) {
            val verdictType = resolved.cached.verdict.verdict
            val ui = uiStateFromVerdict(verdictType, alignedWithCurrentBg = resolved.alignedWithCurrentBg, lastReadTimestampMs)
            return ui
        }

        if (ageMs != null && ageMs > 300_000) {
            return AuditorUIState.idle()
        }

        return when {
            status == AuditorStatusTracker.Status.OFF -> AuditorUIState.idle()

            status.isSkipped() -> AuditorUIState.idle()

            status.isOffline() -> AuditorUIState.error(getOfflineMessage(status))

            status.isError() -> AuditorUIState.error(getErrorMessage(status))

            status.isActive() -> AuditorUIState.idle()

            else -> AuditorUIState.idle()
        }
    }

    private fun uiStateFromVerdict(
        verdictType: VerdictType,
        alignedWithCurrentBg: Boolean,
        lastReadTimestampMs: Long,
    ): AuditorUIState {
        val insightCount = AuditorReportFormatter.insightCount()
        val shouldNotify = AuditorReportFormatter.hasUnreadVerdict(lastReadTimestampMs)
        return when (verdictType) {
            VerdictType.Confirm -> {
                if (alignedWithCurrentBg) {
                    AuditorUIState.ready(insightCount, shouldNotify)
                } else {
                    AuditorUIState.warning(
                        message = "Prior tick verdict (new CGM reading)",
                        shouldNotify = shouldNotify,
                    )
                }
            }
            VerdictType.Soften,
            VerdictType.ShiftToTbr,
            -> AuditorUIState.warning(
                message = "Important: ${verdictType.name}",
                shouldNotify = shouldNotify,
            )
        }
    }

    private fun getOfflineMessage(status: AuditorStatusTracker.Status): String {
        return when (status) {
            AuditorStatusTracker.Status.OFFLINE_NO_APIKEY -> "No API key configured"
            AuditorStatusTracker.Status.OFFLINE_NO_NETWORK -> "No network connection"
            AuditorStatusTracker.Status.OFFLINE_NO_ENDPOINT -> "API endpoint unavailable"
            AuditorStatusTracker.Status.OFFLINE_DNS_FAIL -> "DNS resolution failed"
            else -> "Offline"
        }
    }

    private fun getErrorMessage(status: AuditorStatusTracker.Status): String {
        return when (status) {
            AuditorStatusTracker.Status.ERROR_TIMEOUT -> "Request timeout"
            AuditorStatusTracker.Status.ERROR_PARSE -> "Parse error"
            AuditorStatusTracker.Status.ERROR_HTTP -> "HTTP error"
            AuditorStatusTracker.Status.ERROR_EXCEPTION -> "Exception occurred"
            else -> "Error"
        }
    }
}
