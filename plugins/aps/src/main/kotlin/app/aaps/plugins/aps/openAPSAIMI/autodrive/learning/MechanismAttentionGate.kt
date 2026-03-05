package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MechanismAttentionGate @Inject constructor() {
    fun processState(state: AutoDriveState): AutoDriveState {
        // Retourne l'état inchangé pour le moment.
        // C'est un stub pour abriter le futur réseau de neurones (ML) Attention Gate.
        return state
    }
}
