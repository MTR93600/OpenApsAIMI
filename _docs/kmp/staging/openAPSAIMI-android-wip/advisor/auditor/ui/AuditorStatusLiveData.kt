package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * Reactive layer transforming [AuditorStatusTracker.Status] → [AuditorUIState].
 */
@SingleIn(AppScope::class)
class AuditorStatusLiveData @Inject constructor() {

  private val _uiState = MutableLiveData(AuditorUIState.idle())
  val uiState: LiveData<AuditorUIState> = _uiState

  @Volatile
  private var lastReadTimestampMs: Long = 0L

  fun notifyUpdate() {
    val (status, ageMs) = AuditorStatusTracker.getStatus()
    val newState = transformStatusToUIState(status, ageMs)
    _uiState.postValue(newState)
  }

  private fun transformStatusToUIState(
    status: AuditorStatusTracker.Status,
    ageMs: Long?,
  ): AuditorUIState {
    if (status.name.contains("PROCESSING")) {
      return AuditorUIState.processing()
    }

    val resolved = AuditorVerdictCache.resolveForDisplay()
    if (resolved != null) {
      val verdictType = resolved.cached.verdict.verdict
      val ui = uiStateFromVerdict(verdictType, alignedWithCurrentBg = resolved.alignedWithCurrentBg)
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

  fun markAsRead() {
    val verdictTimestamp = AuditorVerdictCache.resolveForDisplay()?.cached?.timestamp
    lastReadTimestampMs = verdictTimestamp ?: System.currentTimeMillis()
    notifyUpdate()
  }

  private fun uiStateFromVerdict(verdictType: VerdictType, alignedWithCurrentBg: Boolean): AuditorUIState {
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

  fun forceUpdate() {
    notifyUpdate()
  }

  fun reset() {
    lastReadTimestampMs = 0L
    _uiState.postValue(AuditorUIState.idle())
  }
}
