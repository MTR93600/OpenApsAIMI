package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.plugins.aps.openAPSAIMI.model.*

interface Clock { fun now(): Long }
interface BasalActuator { fun setTempBasal(plan: BasalPlan): Boolean }
interface SmbActuator { fun deliver(plan: SmbPlan): Boolean }

interface PkpdPort {
    data class Snapshot(
        val diaMin: Int,
        val peakMin: Int,
        val fusedIsf: Double,
        val tailFrac: Double,
        val smbProposalU: Double? = null,
        val tailMult: Double? = null,
        val exerciseMult: Double? = null,
        val lateFatMult: Double? = null,
        val lateFatRise: Boolean? = null
    )
    fun snapshot(ctx: LoopContext): Snapshot

    data class DampingAudit(
        val out: Double,
        val tailApplied: Boolean, val tailMult: Double,
        val exerciseApplied: Boolean, val exerciseMult: Double,
        val lateFatApplied: Boolean, val lateFatMult: Double,
        val mealBypass: Boolean
    )
    fun dampSmb(units: Double, ctx: LoopContext, bypassDamping: Boolean): DampingAudit

    fun logCsv(
        ctx: LoopContext,
        pkpd: Snapshot,
        smbProposed: Double,
        smbFinal: Double,
        audit: DampingAudit?
    )
}

interface MlUamPort { fun predictSmbDelta(ctx: LoopContext): Double }
interface SafetyGuards { fun apply(ctx: LoopContext, basal: BasalPlan?, smb: SmbPlan?): SafetyReport }
interface ModeEngine { fun stateFrom(ctx: LoopContext): ModeState; fun reactivityFactor(ctx: LoopContext): Double }
