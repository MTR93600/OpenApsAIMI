package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.AimiAdaptationStatus
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.dashboard.AimiAdaptationModuleUiState
import app.aaps.core.ui.compose.dashboard.AimiAdaptationStatusUiState
import app.aaps.core.ui.compose.pump.tickerFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * State of the AIMI learners for the adaptation status screen. Port of `AimiAdaptationStatusViewModel`
 * on `dev_OAPSAIMI`: it reads the status the last AIMI run put on its result, again after each loop run
 * and once a minute, so the "N min ago" texts and the stale check move on.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class AimiAdaptationStatusViewModel(
    private val loop: Loop,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val dateUtil: DateUtil,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AimiAdaptationStatusUiState())
    val uiState: StateFlow<AimiAdaptationStatusUiState> = _uiState.asStateFlow()

    init {
        merge(tickerFlow(FRESHNESS_TICK_MS), rxBus.toFlow(EventLoopUpdateGui::class).map { })
            .onEach { refresh() }
            .launchIn(viewModelScope)
    }

    private fun refresh() {
        _uiState.value = AimiAdaptationStatusPresenter.present(
            status = (loop.lastRun?.request?.rawData() as? RT)?.aimiAdaptationStatus,
            now = dateUtil.now(),
            staleAfterMs = preferences.get(IntKey.AlertsStaleDataThreshold) * 60_000L,
        )
    }

    private companion object {

        const val FRESHNESS_TICK_MS = 60_000L
    }
}

/**
 * Pure rules of the adaptation status, also used for the summary on the dashboard. Same rules as DEV:
 * a learner whose last update is older than the stale data alert threshold is shown as stale, and the
 * list is sorted with the learners that need attention first.
 */
internal object AimiAdaptationStatusPresenter {

    fun present(status: AimiAdaptationStatus?, now: Long, staleAfterMs: Long): AimiAdaptationStatusUiState {
        if (status == null) return AimiAdaptationStatusUiState()

        val modules = status.modules
            .map { module ->
                val updatedAt = module.updatedAt
                val ageMillis = updatedAt?.let { (now - it).coerceAtLeast(0L) }
                val isStale = module.phase != AimiAdaptationPhase.DISABLED &&
                    updatedAt != null && updatedAt > 0L &&
                    ageMillis != null && ageMillis > staleAfterMs
                AimiAdaptationModuleUiState(
                    status = if (isStale) module.copy(phase = AimiAdaptationPhase.STALE, reason = AimiAdaptationReasonCode.DATA_STALE) else module,
                    effectiveUpdatedAt = updatedAt,
                    ageMillis = ageMillis,
                )
            }
            .sortedWith(compareBy({ phasePriority(it.status.phase) }, { it.status.moduleId.ordinal }))

        fun count(phase: AimiAdaptationPhase): Int = modules.count { it.status.phase == phase }

        return AimiAdaptationStatusUiState(
            hasStatus = true,
            updatedAt = status.updatedAt,
            snapshotAgeMillis = (now - status.updatedAt).coerceAtLeast(0L),
            modules = modules,
            activeCount = count(AimiAdaptationPhase.ACTIVE),
            readyCount = count(AimiAdaptationPhase.READY),
            waitingCount = count(AimiAdaptationPhase.WAITING),
            learningCount = count(AimiAdaptationPhase.LEARNING),
            blockedCount = count(AimiAdaptationPhase.BLOCKED),
            staleCount = count(AimiAdaptationPhase.STALE),
            disabledCount = count(AimiAdaptationPhase.DISABLED),
        )
    }

    private fun phasePriority(phase: AimiAdaptationPhase): Int = when (phase) {
        AimiAdaptationPhase.BLOCKED  -> 0
        AimiAdaptationPhase.STALE    -> 1
        AimiAdaptationPhase.ACTIVE   -> 2
        AimiAdaptationPhase.LEARNING -> 3
        AimiAdaptationPhase.WAITING  -> 4
        AimiAdaptationPhase.READY    -> 5
        AimiAdaptationPhase.DISABLED -> 6
    }
}
