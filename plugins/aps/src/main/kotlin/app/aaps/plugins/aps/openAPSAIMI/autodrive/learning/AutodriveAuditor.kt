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

    /**
     * Traduit le comportement mathématique complexe en explications compréhensibles.
     */
    fun generateHumanReadableReason(
        state: app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState,
        baseProfileIsf: Double,
        rawCommand: app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand,
        safeCommand: app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
    ): String {
        val sb = StringBuilder()
        
        if (state.bgVelocity > 1.5) sb.append("🚀 Montée Rapide")
        else if (state.bgVelocity < -1.5) sb.append("📉 Chute Rapide")
        
        if (state.estimatedRa > 0.5) {
            sb.append(" | 🍽️ Absorption Détectée (~${(state.estimatedRa).toInt()} mg/dL/min)")
        }

        if (safeCommand.scheduledMicroBolus > 0) {
            sb.append(" | 💉 SMB Optimal: ${safeCommand.scheduledMicroBolus}U")
        } else if (safeCommand.temporaryBasalRate > 0) {
            sb.append(" | 💧 TBR: ${safeCommand.temporaryBasalRate}U/h")
        }

        if (rawCommand.temporaryBasalRate != safeCommand.temporaryBasalRate) {
            sb.append(" | 🛡️ Sécurité Activée")
        }

        return if (sb.isEmpty()) "V3: État Stable" else "V3: ${sb.toString().trim()}"
    }
}
