package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutodriveDataLake @Inject constructor() {
    fun ingest(state: AutoDriveState, rawCommand: AutoDriveCommand, finalCommand: AutoDriveCommand, timestampMs: Long) {
        // Enregistrement des logs pour le ML dans le futur
    }
    
    fun flushToDiskIfReady() {
        // Sauvegarde sur disque si nécessaire
    }
}
