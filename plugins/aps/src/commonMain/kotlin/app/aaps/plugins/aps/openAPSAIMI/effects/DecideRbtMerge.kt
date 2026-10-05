package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import kotlin.math.max
import kotlin.math.min

/**
 * Android reads around the RBT and hyper-trajectory merge.
 * Each method is the call that already existed at that line.
 */
internal interface AimiRbtMergeCalls {
    fun physioCapU(): Double?
    fun stackingCapU(): Double?
    fun patternCapU(): Double?
    fun patternHolding(): Boolean
    fun softPatternProposalU(): Double?
    fun patternActiveLabel(): String?
    fun bindingDraft(): SmbBindingTrace.Draft
    fun setBindingDraft(value: SmbBindingTrace.Draft)
    fun ignoreMinPredictedCurve(): Boolean
    fun storeEffective(htr: HyperTrajectoryReleaseResult)
}

internal fun decideRbtMerge(
    htr: HyperTrajectoryReleaseResult,
    rbtSnapshot: RecursiveBeliefSnapshot?,
    authorityGate: RecursiveBeliefAuthorityGate.Decision,
    consoleLog: MutableList<String>,
    calls: AimiRbtMergeCalls,
): RbtLiveCommitResult {
    val physioCapU = calls.physioCapU()
    val stackingCapU = calls.stackingCapU()
    val patternCapU = calls.patternCapU()
    val softPatternProposalU = calls.softPatternProposalU()
    if (calls.patternHolding() && patternCapU != null) {
        consoleLog.add("🧷 Pattern cap hold: keeping HARD ${aimiFmt2(patternCapU)}U during rise (pattern flapped)")
    }
    if (softPatternProposalU != null) {
        consoleLog.add("🍽️ Pattern soft proposal ${aimiFmt2(softPatternProposalU)}U (Harmonia may lift within maxSMBHB)")
    }
    consoleLog.add("🪜 RBT_GATE: ${authorityGate.summary()}")
    val rbtAuthority = authorityGate.effectiveAuthority != ReleaseAuthority.NONE
    val effectiveHtr = if (rbtSnapshot != null &&
        (rbtAuthority || physioCapU != null || stackingCapU != null || patternCapU != null)
    ) {
        val r = rbtSnapshot.resolutions
        val rawLifted = if (rbtAuthority) {
            val rbtLifted =
                htr.v3SmbBeforeU +
                    (r.smbDemandU - htr.v3SmbBeforeU).coerceAtLeast(0.0) * authorityGate.liftBlend
            max(htr.v3SmbBeforeU, rbtLifted)
        } else {
            htr.v3SmbBeforeU
        }
        var lifted = rawLifted
        physioCapU?.let { lifted = min(lifted, it) }
        stackingCapU?.let { lifted = min(lifted, it) }
        patternCapU?.let { cap -> lifted = min(lifted, cap) }
        var traceDraft = calls.bindingDraft().copy(
            htrBeforeU = htr.v3SmbBeforeU,
            htrAfterU = htr.v3SmbAfterU,
            rbtBeforeU = htr.v3SmbAfterU,
            rbtAfterU = rawLifted,
            patternActive = calls.patternActiveLabel(),
            patternCapU = patternCapU ?: softPatternProposalU,
        )
            .appendStage("HTR", htr.v3SmbBeforeU, htr.v3SmbAfterU, phase = "AUTODRIVE_PRE_TERMINAL", kind = "LIFT")
            .appendStage("RBT", htr.v3SmbAfterU, rawLifted, phase = "AUTODRIVE_PRE_TERMINAL", kind = "LIFT")
        var traceValue = rawLifted
        physioCapU?.let { cap ->
            val after = min(traceValue, cap)
            traceDraft = traceDraft.appendStage("PHYSIO_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
            traceValue = after
        }
        stackingCapU?.let { cap ->
            val after = min(traceValue, cap)
            traceDraft = traceDraft.appendStage("IOB_SURVEILLANCE_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
            traceValue = after
        }
        softPatternProposalU?.let { proposal ->
            traceDraft = traceDraft.appendStage(
                "PATTERN_SOFT_PROPOSAL",
                traceValue,
                traceValue,
                proposal,
                "AUTODRIVE_PRE_TERMINAL",
                "PROPOSAL",
            )
        }
        patternCapU?.let { cap ->
            val after = min(traceValue, cap)
            traceDraft = traceDraft.appendStage("PATTERN_CAP", traceValue, after, cap, "AUTODRIVE_PRE_TERMINAL", "CAP")
            traceValue = after
        }
        traceDraft = traceDraft.copy(preTerminalAfterCapsU = traceValue)
        calls.setBindingDraft(traceDraft)
        htr.copy(
            active = lifted > htr.v3SmbBeforeU + 0.02,
            smbFloorU = if (rbtAuthority) min(r.smbDemandU, lifted) else min(htr.smbFloorU, lifted),
            v3SmbAfterU = lifted,
            suppressTrajBasalShift = r.suppressTrajBasalShift || htr.suppressTrajBasalShift,
            hypoMinPredIgnored = calls.ignoreMinPredictedCurve() || r.hypoMinPredIgnored,
            reason = htr.reason + " | RBT[${authorityGate.effectiveAuthority}] ${r.reasonCodes.joinToString(",")} gate=${authorityGate.reasonCodes.joinToString("+")}",
        )
    } else {
        htr
    }
    calls.storeEffective(effectiveHtr)
    return RbtLiveCommitResult(
        baselineHtr = htr,
        effectiveHtr = effectiveHtr,
        rbtAuthority = rbtAuthority,
    )
}
