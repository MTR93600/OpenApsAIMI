package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutodriveAuditor @Inject constructor() {
    fun isSafeToRun(state: AutoDriveState): Boolean {
        // Vérification basique: on ne tourne pas si Pas de BG info
        return state.bg > 0
    }
}
