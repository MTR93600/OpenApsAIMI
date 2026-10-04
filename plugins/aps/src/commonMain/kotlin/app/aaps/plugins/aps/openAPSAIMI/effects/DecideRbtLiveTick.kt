package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.InsulinIntent
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtChaosEvaluator
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtEpisodeMemory
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtResolutionBridge
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefPreferences
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.recursive.UnfoldExporter
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot

/**
 * Android reads and writes of [decideRbtLiveTick], each at the line that uses it.
 * HTR, the belief resolver, and the merge stay Android and run at the call that used them.
 */
internal interface AimiRbtLiveTickCalls {
    fun alreadyResolved(): Boolean
    fun lastCommit(): RbtLiveCommitResult?
    fun markResolved()
    fun evaluateHtr(
        v3SmbU: Double,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        rbtLeafOnly: Boolean,
    ): HyperTrajectoryReleaseResult
    fun resolveBelief(
        v3SmbU: Double,
        htr: HyperTrajectoryReleaseResult,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        profile: OapsProfileAimi,
        autosens: AutosensResult,
        glucoseStatus: GlucoseStatusAIMI?,
        stepsLast15m: Int,
        heartRateBpm: Int,
        autodriveGateOpen: Boolean,
        mpcFeedForwardRa: Double?,
        cbfShieldDeltaU: Double?,
    ): RecursiveBeliefSnapshot?
    fun storeSnapshot(snapshot: RecursiveBeliefSnapshot?)
    fun storeLoadGovernor(multiplierG: Double)
    fun trajectoryUncertain(): Boolean
    fun patternCapFlapping(): Boolean
    fun storeChaos(chaos: RbtChaosEvaluator.Result?)
    fun targetBg(): Float
    fun bg(): Double
    fun delta(): Float
    fun nowMs(): Long
    fun latentState(): PhysioLatentState?
    fun recentNadir(minutes: Int): Double
    fun predictionAvailable(): Boolean
    fun phaseOutput(): PhysiologicalPhaseClassifier.Output?
    fun patternSnapshot(): PhysiologicalPatternSnapshot?
    fun hypothesisState(): UamHypothesisState?
    fun patientState(): PatientStateSnapshot?
    fun patientModeDecision(): PatientModeOrchestrator.Decision?
    fun safetyRisk(): SafetyRiskExportSnapshot?
    fun treeInsulinIntent(): InsulinIntent
    fun treeInsulinUrgency(): Double
    fun mealDeliveryPriority(): Boolean
    fun storeAuthority(decision: RecursiveBeliefAuthorityGate.Decision)
    fun storeHints(hints: RbtResolutionBridge.AppliedHints)
    fun appliedHints(): RbtResolutionBridge.AppliedHints?
    fun merge(
        htr: HyperTrajectoryReleaseResult,
        rbtSnapshot: RecursiveBeliefSnapshot?,
        authorityGate: RecursiveBeliefAuthorityGate.Decision,
        rT: RT,
    ): RbtLiveCommitResult
    fun storeCommit(result: RbtLiveCommitResult)
}

/**
 * `resolveAndWireRbtLiveTick`.
 *
 * Shadow or authority must be on. In the locked scene shadow is on, authority is off,
 * and HTR lifts a 0.40 U V3 SMB to a 2.00 U floor. This head swallows no exception.
 * The merge stays the Android port called at the line.
 */
internal fun decideRbtLiveTick(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    combinedDelta: Float,
    tdd24hU: Double,
    v3SmbU: Double,
    stepsLast15m: Int,
    heartRateBpm: Int,
    autodriveGateOpen: Boolean,
    mpcFeedForwardRa: Double?,
    cbfShieldDeltaU: Double?,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiRbtLiveTickCalls,
): RbtLiveCommitResult? {
    if (calls.alreadyResolved()) {
        return calls.lastCommit()
    }
    val rbtPrefsEarly = RecursiveBeliefPreferences.from(preferences)
    if (!RecursiveBeliefPreferences.isActive(rbtPrefsEarly)) return null
    calls.markResolved()
    val htr = calls.evaluateHtr(
        v3SmbU = v3SmbU,
        rT = rT,
        combinedDelta = combinedDelta,
        tdd24hU = tdd24hU,
        rbtLeafOnly = rbtPrefsEarly.authorityEnabled,
    )
    val rbtSnapshot = calls.resolveBelief(
        v3SmbU = v3SmbU,
        htr = htr,
        rT = rT,
        combinedDelta = combinedDelta,
        tdd24hU = tdd24hU,
        profile = profile,
        autosens = ctx.autosensData,
        glucoseStatus = ctx.glucoseStatus,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        autodriveGateOpen = autodriveGateOpen,
        mpcFeedForwardRa = mpcFeedForwardRa,
        cbfShieldDeltaU = cbfShieldDeltaU,
    )
    calls.storeSnapshot(rbtSnapshot)
    rbtSnapshot?.let { snap ->
        snap.loadGovernor?.let { lg ->
            calls.storeLoadGovernor(lg.multiplierG)
            if (lg.applied || lg.multiplierG < 0.99) {
                consoleLog.add("⚖️ ${lg.summary}${if (lg.applied) "" else " [pref-off]"}")
            }
        }
        consoleLog.add(UnfoldExporter.formatLogLine(snap))
    }
    val chaosEval = rbtSnapshot?.let { snap ->
        RbtChaosEvaluator.evaluate(
            RbtChaosEvaluator.Input(
                snapshot = snap,
                trajectoryUncertain = calls.trajectoryUncertain(),
                patternCapFlapping = calls.patternCapFlapping(),
            ),
        )
    }
    calls.storeChaos(chaosEval)
    val targetForPostHypoExit = profile.target_bg.takeIf { it > 0.0 }
        ?: calls.targetBg().toDouble().takeIf { it > 0.0 }
        ?: 100.0
    val aggressiveRiseExit = PostHypoAggressiveRiseExit.shouldExit(
        bgMgdl = calls.bg(),
        targetBgMgdl = targetForPostHypoExit,
        deltaMgdl5m = calls.delta().toDouble(),
    )
    if (aggressiveRiseExit) {
        consoleLog.add(
            "🚀 POST_HYPO_AGGRESSIVE_RISE_EXIT: bg=${aimiFmt0(calls.bg())} " +
                "≥ target+30 (${aimiFmt0(targetForPostHypoExit + 30.0)}) Δ=${aimiFmt1(calls.delta())} > 15 → act normally",
        )
    }
    RbtEpisodeMemory.tick(
        nowMs = calls.nowMs(),
        postHypoReboundProb = calls.latentState()?.postHypoReboundProb ?: 0.0,
        chaosScore = chaosEval?.score ?: 0.0,
        mealProb = calls.latentState()?.mealProb ?: 0.0,
        recentNadirBgMgdl = calls.recentNadir(45),
        aggressiveRiseExit = aggressiveRiseExit,
    )
    val activeEpisode = RbtEpisodeMemory.activeEpisode(calls.nowMs())
    chaosEval?.takeIf { it.active || it.caution }?.let {
        consoleLog.add("🌪️ RBT_CHAOS: ${it.summary()}")
    }
    activeEpisode?.let {
        consoleLog.add(
            "📖 RBT_EPISODE: ${it.kind.name} age=${aimiFmt0(it.ageMinutes(calls.nowMs()))}min " +
                "peak=${aimiFmt2(it.peakScore)} ticks=${it.tickCount}" +
                if (it.deepHypo) " deep" else " light",
        )
    }
    val rbtPrefs = RecursiveBeliefPreferences.from(preferences)
    val authorityGate = RecursiveBeliefAuthorityGate.evaluate(
        RecursiveBeliefAuthorityGate.Input(
            authorityEnabled = rbtPrefs.authorityEnabled,
            requestedAuthority = rbtSnapshot?.resolutions?.releaseAuthority ?: ReleaseAuthority.NONE,
            predictionAvailable = calls.predictionAvailable(),
            phaseOutput = calls.phaseOutput(),
            patternSnapshot = calls.patternSnapshot(),
            latentState = calls.latentState(),
            hypothesisState = calls.hypothesisState(),
            patientState = calls.patientState(),
            patientModeDecision = calls.patientModeDecision(),
            safetyRiskExport = calls.safetyRisk(),
            chaos = chaosEval,
            episode = activeEpisode,
            bgMgdl = calls.bg(),
            targetBgMgdl = targetForPostHypoExit,
            deltaMgdl5m = calls.delta().toDouble(),
            mealHyperBypassEnabled = rbtPrefs.mealHyperBypassEnabled,
            treeInsulinIntent = calls.treeInsulinIntent(),
            treeInsulinUrgency = calls.treeInsulinUrgency(),
            treeMealRiseFrontLoadEnabled = rbtPrefs.treeMealRiseFrontLoadEnabled,
        ),
    )
    calls.storeAuthority(authorityGate)
    calls.storeHints(
        RbtResolutionBridge.apply(
            resolution = rbtSnapshot?.resolutions,
            effectiveAuthority = authorityGate.effectiveAuthority,
            chaos = chaosEval,
            episode = activeEpisode,
            defaultMealPriority = calls.mealDeliveryPriority(),
        ),
    )
    consoleLog.add("🔌 RBT_WIRE: ${calls.appliedHints()?.summary ?: "inactive"}")
    val result = calls.merge(
        htr = htr,
        rbtSnapshot = rbtSnapshot,
        authorityGate = authorityGate,
        rT = rT,
    )
    calls.storeCommit(result)
    return result
}
