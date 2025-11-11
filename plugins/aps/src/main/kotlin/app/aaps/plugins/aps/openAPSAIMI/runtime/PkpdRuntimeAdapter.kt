package app.aaps.plugins.aps.openAPSAIMI.runtime

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLogRow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import java.util.concurrent.TimeUnit

object PkpdRuntimeAdapter {

    data class Input(
        val nowEpochMs: Long,
        val bg: Double,
        val delta5mgdl: Double,
        val iobU: Double,
        val carbsActiveG: Double,
        val windowMin: Int,
        val exerciseFlag: Boolean,
        val profileIsf: Double,
        val tdd24h: Double,
        val dateStr: String? = null,
        val epochMinOverride: Long? = null,
        val diaHrs: Double? = null,
        val peakMinGuess: Double? = null,
        val smbProposedU: Double? = null,
        val smbFinalU: Double? = null,
        val tailMultLog: Double? = null,
        val exerciseMultLog: Double? = null,
        val lateFatMultLog: Double? = null,
        val highBgOverride: Boolean? = null,
        val lateFatRise: Boolean? = null,
        val quantStepU: Double? = null
    )

    data class Output(
        val runtime: PkPdRuntime?,
        val pkpdScale: Double?, val tailFraction: Double?, val fusedIsf: Double?
    )

    fun computeAndMaybeLog(pkpd: PkPdIntegration, input: Input): Output {
        val rt = pkpd.computeRuntime(
            epochMillis   = input.nowEpochMs,
            bg            = input.bg,
            deltaMgDlPer5 = input.delta5mgdl,
            iobU          = input.iobU,
            carbsActiveG  = input.carbsActiveG,
            windowMin     = input.windowMin,
            exerciseFlag  = input.exerciseFlag,
            profileIsf    = input.profileIsf,
            tdd24h        = input.tdd24h
        )

        if (rt != null && input.dateStr != null) {
            val epochMin = input.epochMinOverride ?: TimeUnit.MILLISECONDS.toMinutes(input.nowEpochMs)
            PkPdCsvLogger.append(
                PkPdLogRow(
                    dateStr       = input.dateStr,
                    epochMin      = epochMin,
                    bg            = input.bg,
                    delta5        = input.delta5mgdl,
                    iobU          = input.iobU,
                    carbsActiveG  = input.carbsActiveG,
                    windowMin     = input.windowMin,
                    diaH          = input.diaHrs ?: rt.params.diaHrs,
                    peakMin       = input.peakMinGuess ?: rt.params.peakMin,
                    fusedIsf      = rt.fusedIsf,
                    tddIsf        = rt.tddIsf,
                    profileIsf    = input.profileIsf,
                    tailFrac      = rt.tailFraction,
                    smbProposedU  = input.smbProposedU ?: 0.0,
                    smbFinalU     = input.smbFinalU ?: 0.0,
                    tailMult      = input.tailMultLog,
                    exerciseMult  = input.exerciseMultLog,
                    lateFatMult   = input.lateFatMultLog,
                    highBgOverride= input.highBgOverride,
                    lateFatRise   = input.lateFatRise,
                    quantStepU    = input.quantStepU
                )
            )
        }

        return Output(rt, rt?.pkpdScale, rt?.tailFraction, rt?.fusedIsf)
    }
}
