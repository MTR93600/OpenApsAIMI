package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot

/**
 * Android `refreshEffortActivityBelief` behind an already-read snapshot.
 * Protection off and T3C off leave the assessment null. An invalid snapshot does the same.
 * The caller logs a snapshot failure before passing null. This function does not catch.
 * [EffortBeliefRefresh.smbFactor] is never above 1.
 */
internal data class EffortBeliefRefresh(
    val assessment: EffortActivityBelief.Assessment?,
    val memory: EffortActivityBelief.Memory,
    val logLine: String?,
) {
    val smbFactor: Double get() {
        val factor = assessment?.smbFactor ?: 1.0
        check(factor <= 1.0) { "effort smbFactor $factor exceeds 1" }
        return factor
    }
}

internal fun decideRefreshEffortActivityBelief(
    protectionEnabled: Boolean,
    t3cEnabled: Boolean,
    snapshot: HealthContextSnapshot?,
    nowMs: Long,
    stressResistanceProb: Double,
    prior: EffortActivityBelief.Memory,
): EffortBeliefRefresh {
    if (!protectionEnabled && !t3cEnabled) {
        return EffortBeliefRefresh(assessment = null, memory = prior, logLine = null)
    }
    if (snapshot == null || !snapshot.isValid) {
        return EffortBeliefRefresh(assessment = null, memory = prior, logLine = null)
    }
    val (assessment, memory) = EffortActivityBelief.assess(
        EffortActivityBelief.Inputs(
            nowMs = nowMs,
            stepsLast5m = snapshot.stepsLast5m,
            stepsLast15m = snapshot.stepsLast15m,
            stepsLast60m = snapshot.stepsLast60m,
            hrAvg15mBpm = snapshot.hrAvg15m,
            hrRestingBpm = snapshot.rhrResting,
            hrvDeviationZ = null,
            stressResistanceProb = stressResistanceProb,
        ),
        prior,
    )
    check(assessment.smbFactor <= 1.0) { "effort smbFactor ${assessment.smbFactor} exceeds 1" }
    check(assessment.basalFactor <= 1.0) { "effort basalFactor ${assessment.basalFactor} exceeds 1" }
    val logLine = if (assessment.smbFactor < 1.0) {
        "🏃 EFFORT_BELIEF[${assessment.state.name}/${assessment.posture.name}] " +
            "SMB ×${aimiFmt2(assessment.smbFactor)} (applied at SMB finalize) " +
            assessment.reasons.joinToString(",")
    } else {
        null
    }
    return EffortBeliefRefresh(assessment = assessment, memory = memory, logLine = logLine)
}
