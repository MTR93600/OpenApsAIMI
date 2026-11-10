package app.aaps.plugins.main.general.dashboard.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.AimiAdaptationModuleStatus
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.AimiAdaptationStatus
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AimiAdaptationStatusViewModel(
    private val loop: Loop,
    private val rxBus: RxBus,
    private val preferences: Preferences,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    @Immutable
    data class UiState(
        val hasStatus: Boolean = false,
        val updatedAt: Long? = null,
        val snapshotAgeMillis: Long? = null,
        val modules: List<ModuleUiState> = emptyList(),
        val activeCount: Int = 0,
        val readyCount: Int = 0,
        val waitingCount: Int = 0,
        val learningCount: Int = 0,
        val blockedCount: Int = 0,
        val staleCount: Int = 0,
        val disabledCount: Int = 0,
    ) {
        val waitingOrLearningCount: Int get() = waitingCount + learningCount
        val attentionCount: Int get() = blockedCount + staleCount
    }

    @Immutable
    data class ModuleUiState(
        val status: AimiAdaptationModuleStatus,
        val effectiveUpdatedAt: Long?,
        val ageMillis: Long?,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        rxBus.toFlow(EventOpenAPSUpdateGui::class.java)
            .onEach { refresh() }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            while (isActive) {
                delay(FRESHNESS_TICK_MS)
                refresh()
            }
        }

        refresh()
    }

    private fun refresh() {
        val status = ((loop.lastRun?.request?.rawData() as? RT)?.aimiAdaptationStatus)
        val staleAfterMs = TimeUnit.MINUTES.toMillis(
            preferences.get(IntKey.AlertsStaleDataThreshold).toLong(),
        )
        _uiState.value = AimiAdaptationStatusPresenter.present(
            status = status,
            now = now(),
            staleAfterMs = staleAfterMs,
        )
    }

    class Factory(
        private val loop: Loop,
        private val rxBus: RxBus,
        private val preferences: Preferences,
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AimiAdaptationStatusViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AimiAdaptationStatusViewModel(loop, rxBus, preferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }

    private companion object {
        val FRESHNESS_TICK_MS: Long = TimeUnit.MINUTES.toMillis(1)
    }
}

internal object AimiAdaptationStatusPresenter {

    fun present(
        status: AimiAdaptationStatus?,
        now: Long,
        staleAfterMs: Long,
    ): AimiAdaptationStatusViewModel.UiState {
        if (status == null) return AimiAdaptationStatusViewModel.UiState()

        val normalizedModules = status.modules
            .map { module ->
                val effectiveUpdatedAt = module.updatedAt
                val ageMillis = effectiveUpdatedAt?.let { (now - it).coerceAtLeast(0L) }
                val normalizedStatus = if (
                    module.phase != AimiAdaptationPhase.DISABLED &&
                    effectiveUpdatedAt != null &&
                    effectiveUpdatedAt > 0L &&
                    ageMillis != null &&
                    ageMillis > staleAfterMs
                ) {
                    module.copy(
                        phase = AimiAdaptationPhase.STALE,
                        reason = AimiAdaptationReasonCode.DATA_STALE,
                    )
                } else {
                    module
                }
                AimiAdaptationStatusViewModel.ModuleUiState(
                    status = normalizedStatus,
                    effectiveUpdatedAt = effectiveUpdatedAt,
                    ageMillis = ageMillis,
                )
            }
            .sortedWith(
                compareBy<AimiAdaptationStatusViewModel.ModuleUiState>(
                    { phasePriority(it.status.phase) },
                    { it.status.moduleId.ordinal },
                )
            )

        fun count(phase: AimiAdaptationPhase): Int =
            normalizedModules.count { it.status.phase == phase }

        return AimiAdaptationStatusViewModel.UiState(
            hasStatus = true,
            updatedAt = status.updatedAt,
            snapshotAgeMillis = (now - status.updatedAt).coerceAtLeast(0L),
            modules = normalizedModules,
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
        AimiAdaptationPhase.BLOCKED -> 0
        AimiAdaptationPhase.STALE -> 1
        AimiAdaptationPhase.ACTIVE -> 2
        AimiAdaptationPhase.LEARNING -> 3
        AimiAdaptationPhase.WAITING -> 4
        AimiAdaptationPhase.READY -> 5
        AimiAdaptationPhase.DISABLED -> 6
    }
}
