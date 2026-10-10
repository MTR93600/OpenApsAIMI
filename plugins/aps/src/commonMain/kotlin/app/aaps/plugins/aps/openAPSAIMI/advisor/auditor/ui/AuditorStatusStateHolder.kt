package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusNotifier
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The auditor state the UI shows, and the one implementation of [AuditorStatusNotifier].
 *
 * The writers ([app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorAIService] and
 * [app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorOrchestrator]) only say "the status
 * changed". This class reads [AuditorStatusTracker] again, asks [AuditorStatusStateMapper] what
 * that means for the UI, and publishes the answer on [uiState]. The Overview chip reads that flow
 * through `AuditorStatusBadgeSource`.
 *
 * It must stay a single instance for the whole app. A second one would carry a second state that
 * nobody reads.
 *
 * This used to be an Android class called `AuditorStatusLiveData`, built on
 * `androidx.lifecycle.MutableLiveData`. Nothing else about it was Android, so the rule it holds is
 * now shared and AIMI behaves the same on both phones. Two points of the swap are worth knowing:
 *
 * - `LiveData.postValue` was asynchronous and dropped a value when a second one was posted before
 *   the main looper ran. A [StateFlow] is synchronous and only drops a value that is *equal* to the
 *   one before it. Every [AuditorUIState] carries its own `timestampMs`, so two states built at
 *   different milliseconds are never equal and nothing is swallowed. Two built inside the same
 *   millisecond are equal in every field, so dropping one shows the reader exactly the same thing.
 * - A new reader of a [StateFlow] always receives the current value when it starts collecting.
 *   That is why the old `forceUpdate()` is gone: it existed only to push the state at a `LiveData`
 *   observer that had just attached.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorStatusStateHolder @Inject constructor() : AuditorStatusNotifier {

    private val _uiState = MutableStateFlow(AuditorUIState.idle())

    /** The current auditor state and every later one. Safe to collect from any thread. */
    val uiState: StateFlow<AuditorUIState> = _uiState.asStateFlow()

    @Volatile
    private var lastReadTimestampMs: Long = 0L

    override fun notifyUpdate() {
        val (status, ageMs) = AuditorStatusTracker.getStatus()
        _uiState.value = AuditorStatusStateMapper.transformStatusToUIState(status, ageMs, lastReadTimestampMs)
    }

    /**
     * The user opened the report, so the verdict in it is no longer new.
     *
     * Remembers the time of the verdict that was on screen and publishes the state again, which
     * clears the "unread" part of it.
     */
    fun markAsRead() {
        val verdictTimestamp = AuditorVerdictCache.resolveForDisplay()?.cached?.timestamp
        lastReadTimestampMs = verdictTimestamp ?: aimiWallClockMs()
        notifyUpdate()
    }
}
