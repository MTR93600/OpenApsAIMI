package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusNotifier
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * Reactive layer transforming [AuditorStatusTracker.Status] → [AuditorUIState].
 *
 * This is the one instance the shared writers reach through [AuditorStatusNotifier], and the one the
 * Overview chip (`AuditorStatusBadgeSource`) and the notification (`AuditorNotificationManager`)
 * read. It stays a single instance for the whole app: a second one would carry a second state that
 * nobody observes.
 *
 * The mapping itself lives in shared [AuditorStatusStateMapper]; this class only holds the
 * `LiveData` the Android UI observes.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorStatusLiveData @Inject constructor() : AuditorStatusNotifier {

  private val _uiState = MutableLiveData(AuditorUIState.idle())
  val uiState: LiveData<AuditorUIState> = _uiState

  @Volatile
  private var lastReadTimestampMs: Long = 0L

  override fun notifyUpdate() {
    val (status, ageMs) = AuditorStatusTracker.getStatus()
    val newState = AuditorStatusStateMapper.transformStatusToUIState(status, ageMs, lastReadTimestampMs)
    _uiState.postValue(newState)
  }

  fun markAsRead() {
    val verdictTimestamp = AuditorVerdictCache.resolveForDisplay()?.cached?.timestamp
    lastReadTimestampMs = verdictTimestamp ?: aimiWallClockMs()
    notifyUpdate()
  }

  fun forceUpdate() {
    notifyUpdate()
  }

  fun reset() {
    lastReadTimestampMs = 0L
    _uiState.postValue(AuditorUIState.idle())
  }
}
