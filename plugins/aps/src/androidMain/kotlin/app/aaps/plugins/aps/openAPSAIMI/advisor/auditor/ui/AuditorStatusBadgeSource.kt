package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.overview.PluginStatusBadge
import app.aaps.core.interfaces.overview.PluginStatusBadgeSource
import app.aaps.core.interfaces.overview.PluginStatusLevel
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android/AIMI half of [PluginStatusBadgeSource] - bridges [AuditorStatusLiveData]'s `LiveData`
 * (Android-only) to the plain [StateFlow] the shared Overview chip (`:ui`) can consume.
 *
 * `observeForever` is safe here: this is a process-scoped singleton with no view lifecycle of its
 * own, mirroring how [AuditorStatusLiveData] itself already behaves - the Overview chip observes
 * the resulting [StateFlow], not this class, and Compose collection is what has a lifecycle.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorStatusBadgeSource @Inject constructor(
    private val auditorStatusLiveData: AuditorStatusLiveData,
    private val auditorNotificationManager: AuditorNotificationManager,
) : PluginStatusBadgeSource {

    private val _badge = MutableStateFlow(toBadge(AuditorUIState.idle()))
    override val badge: StateFlow<PluginStatusBadge> = _badge.asStateFlow()

    init {
        auditorStatusLiveData.uiState.observeForever { uiState ->
            _badge.value = toBadge(uiState)
        }
    }

    override fun onBadgeClick() {
        auditorNotificationManager.openReport()
    }

    companion object {

        /**
         * Maps one auditor state onto the shared chip contract.
         *
         * [AuditorUIState] itself carries no colour any more, so this mapping is the only thing
         * that keeps the five auditor states visually apart: the Overview chip picks its colours
         * from [PluginStatusLevel]. It is pure and `internal` so a test can pin the five-to-five
         * mapping - see `AuditorStatusBadgeSourceMappingTest`.
         */
        internal fun toBadge(uiState: AuditorUIState): PluginStatusBadge = PluginStatusBadge(
            level = when (uiState.type) {
                AuditorUIState.StateType.IDLE -> PluginStatusLevel.IDLE
                AuditorUIState.StateType.PROCESSING -> PluginStatusLevel.PROCESSING
                AuditorUIState.StateType.READY -> PluginStatusLevel.READY
                AuditorUIState.StateType.WARNING -> PluginStatusLevel.WARNING
                AuditorUIState.StateType.ERROR -> PluginStatusLevel.ERROR
            },
            badgeCount = uiState.insightCount,
            statusMessage = uiState.statusMessage,
        )
    }
}
