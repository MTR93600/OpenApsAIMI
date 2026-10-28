package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.aps.AimiAuditorReport
import app.aaps.core.interfaces.overview.PluginStatusBadge
import app.aaps.core.interfaces.overview.PluginStatusBadgeSource
import app.aaps.core.interfaces.overview.PluginStatusLevel
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * AIMI half of [PluginStatusBadgeSource] - turns [AuditorStatusStateHolder]'s auditor state into
 * the chip the shared Overview (`:ui`) draws.
 *
 * The state itself is shared code now, so this class is only the mapping plus the click.
 * [onBadgeClick] opens the report through the shared [AimiAuditorReport], the same call the Glass
 * Tools tile makes. Nothing here is Android any more, so this class could move to commonMain and
 * give the iOS Overview the same chip.
 *
 * The scope below is never cancelled, on purpose: this is a process-scoped singleton with no
 * lifecycle of its own, so the badge must follow the state for as long as the app runs. The
 * Compose collector of [badge] is what has a lifecycle.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorStatusBadgeSource @Inject constructor(
    auditorStatusStateHolder: AuditorStatusStateHolder,
    private val auditorReport: AimiAuditorReport,
) : PluginStatusBadgeSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val badge: StateFlow<PluginStatusBadge> =
        auditorStatusStateHolder.uiState
            .map { toBadge(it) }
            .stateIn(scope, SharingStarted.Eagerly, toBadge(auditorStatusStateHolder.uiState.value))

    override fun onBadgeClick() {
        auditorReport.openReport()
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
