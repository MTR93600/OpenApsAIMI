package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDampingAudit

/**
 * Orchestration du damping SMB (avec bypass si repas/hyper franche).
 * Encapsule l’appel pkpdRuntime.dampSmbWithAudit(...) avec le flag bypass.
 */
object SmbDampingUsecase {

    data class Input(
        val smbDecision: Double,
        val exercise: Boolean,
        val suspectedLateFatMeal: Boolean,
        val mealModeRun: Boolean,
        val highBgRiseActive: Boolean
    )

    data class Output(
        val smbAfterDamping: Double,
        val audit: SmbDampingAudit?
    )

    fun run(pkpdRuntime: PkPdRuntime?, input: Input): Output {
        if (pkpdRuntime == null) return Output(input.smbDecision, null)
        val bypass = input.mealModeRun || input.highBgRiseActive
        val audit = pkpdRuntime.dampSmbWithAudit(
            smb = input.smbDecision,
            exercise = input.exercise,
            suspectedLateFatMeal = input.suspectedLateFatMeal,
            bypassDamping = bypass
        )
        return Output(audit?.out ?: input.smbDecision, audit)
    }
}
