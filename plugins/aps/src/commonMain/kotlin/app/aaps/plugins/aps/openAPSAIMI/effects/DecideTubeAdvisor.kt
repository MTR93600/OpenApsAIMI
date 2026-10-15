package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSAIMI.control.StraightLineTubeAdvisor
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.HyperClampTubeBound
import app.aaps.plugins.aps.openAPSAIMI.safety.TubeFloorArtefactRule

/**
 * Android reads of the straight-line tube.
 * Each method is the call that already existed at that line.
 * The advisor trace JSON stays Android.
 */
internal data class AimiTubeDoseBaseline(
    val maxSmb: Double,
    val maxSmbHb: Double,
    val currentBasal: Double,
    val maxDailyBasal: Double,
)

internal interface AimiTubeAdvisorCalls {
    fun snapshot(): DoseTerminalSnapshot?
    fun diaHours(): Double?
    fun isf(): Double
    fun baseline(): AimiTubeDoseBaseline?
    fun writeBaseline(value: AimiTubeDoseBaseline)
    fun maxSmb(): Double
    fun maxSmbHb(): Double
    fun writeMaxSmb(value: Double)
    fun writeMaxSmbHb(value: Double)
    fun writeScale(value: Double?)
    fun bg(): Double
    fun delta(): Double
    fun iob(): Double

    /** The ISF the dose path uses (the PK/PD fused ISF), or null when there is no PK/PD runtime. */
    fun doseIsf(): Double?
    fun sportActive(): Boolean
    fun postHypoActive(): Boolean

    /** Keeps this tick's hyper-clamp verdict so the trace can export it. */
    fun writeHyperClamp(verdict: HyperClampTubeBound.Verdict)

    /** Keeps this tick's floor-artefact answer so the trace can export it. */
    fun writeFloorArtefact(strict: Boolean, wide: Boolean)
    fun advise(input: StraightLineTubeAdvisor.Input): StraightLineTubeAdvisor.Outcome
    fun noteTrace(
        outcome: StraightLineTubeAdvisor.Outcome,
        snapshot: DoseTerminalSnapshot,
        stageTag: String,
        baseline: AimiTubeDoseBaseline,
    )
    fun markApplied()
    fun logError(message: String)
}

internal fun decideApplyTubeAdvisorFromDoseSnapshot(
    profile: OapsProfileAimi,
    mealData: MealData,
    targetBgMgdl: Double,
    stageTag: String,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiTubeAdvisorCalls,
) {
    val snap = calls.snapshot() ?: return
    if (!preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) return
    if (stageTag == "late_pkpd") {
        consoleLog.add("📐 TUBE-LINE-D4[$stageTag]: skip (caps frozen after pre-delivery publish)")
        return
    }
    val dia = calls.diaHours()?.takeIf { it.isFinite() && it > 0.0 } ?: return
    val isf = calls.isf().takeIf { it.isFinite() && it > 1.0 } ?: return
    if (calls.baseline() == null) {
        calls.writeBaseline(
            AimiTubeDoseBaseline(
                maxSmb = calls.maxSmb(),
                maxSmbHb = calls.maxSmbHb(),
                currentBasal = profile.current_basal,
                maxDailyBasal = profile.max_daily_basal,
            ),
        )
    }
    val baseline = calls.baseline()!!
    calls.writeMaxSmb(baseline.maxSmb)
    calls.writeMaxSmbHb(baseline.maxSmbHb)
    profile.current_basal = baseline.currentBasal
    profile.max_daily_basal = baseline.maxDailyBasal
    calls.writeScale(null)
    try {
        // In clear hyper the min-pred can be the PK/PD clamp value (80), which ignores insulin on
        // board. Swap it for BG - IOB x ISF so the tube sees the real room. See HyperClampTubeBound.
        val hyperClamp = HyperClampTubeBound.evaluate(
            minPredMgdl = snap.minPredMgdl,
            bgMgdl = calls.bg(),
            iobU = calls.iob(),
            tubeIsfMgdlPerU = isf,
            doseIsfMgdlPerU = calls.doseIsf(),
            hypoFloorMgdl = preferences.get(DoubleKey.AimiTubeHypoFloorMgdl),
            clampPossible = preferences.get(BooleanKey.OApsAIMIPkpdEndogenousReversion) &&
                preferences.get(BooleanKey.OApsAIMIPkpdHyperReversion),
            armed = preferences.get(BooleanKey.OApsAIMITubeHyperClampPhysicalBound),
        )
        calls.writeHyperClamp(hyperClamp)
        val tubeOut = calls.advise(
            StraightLineTubeAdvisor.Input(
                bgMgdl = calls.bg(),
                deltaMgdlPer5m = calls.delta(),
                iobU = calls.iob(),
                cobG = mealData.mealCOB.toDouble(),
                isfMgdlPerU = isf,
                diaHours = dia,
                targetMgdl = targetBgMgdl,
                maxSmbU = calls.maxSmb(),
                minPredictedBg = hyperClamp.minPredForTubeMgdl,
                eventualBgMgdl = snap.eventualMgdl,
            ),
        )
        // Is this veto resting on a railed prediction (a curve that hit its own 39 mg/dL floor)?
        // `strict` keeps the 160 mg/dL plateau band and is the only one the opt-in key can act on.
        // `wide` drops the band and is only exported. See TubeFloorArtefactRule.
        val floorArtefact = TubeFloorArtefactRule.evaluate(
            feasible = tubeOut.feasible,
            minPredUsedMgdl = tubeOut.minPredUsedMgdl ?: snap.minPredMgdl,
            bgMgdl = calls.bg(),
            deltaMgdl5m = calls.delta(),
            sportActive = calls.sportActive(),
            postHypoActive = calls.postHypoActive(),
        )
        calls.writeFloorArtefact(floorArtefact.strict, floorArtefact.wide)
        // A veto built on the hyper-clamp bound is not a railed curve: the insulin on board can
        // really reach the floor. The floor-artefact lift must never undo it.
        val liftTubeVeto = floorArtefact.strict &&
            !hyperClamp.applied &&
            preferences.get(BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact)
        if (!tubeOut.feasible && liftTubeVeto) {
            // The SMB cap goes back to the pre-advisor baseline. The basal trim the advisor asked
            // for is KEPT: it is protective, and releasing it too would raise two channels.
            if (tubeOut.basalCapScale < 0.999) {
                profile.current_basal = baseline.currentBasal * tubeOut.basalCapScale
                profile.max_daily_basal = baseline.maxDailyBasal * tubeOut.basalCapScale
            }
            consoleLog.add(
                "📐 TUBE-LINE-D4[$stageTag]: veto lifted, min-pred ${aimiFmt1(floorArtefact.minPredUsedMgdl)} is a floor artefact",
            )
            calls.noteTrace(tubeOut, snap, stageTag, baseline)
        } else if (!tubeOut.feasible) {
            calls.writeMaxSmb(0.05)
            calls.writeMaxSmbHb(0.05)
            calls.writeScale(0.0)
            if (tubeOut.basalCapScale < 0.999) {
                profile.current_basal = baseline.currentBasal * tubeOut.basalCapScale
                profile.max_daily_basal = baseline.maxDailyBasal * tubeOut.basalCapScale
            }
            consoleLog.add("📐 TUBE-LINE-D4[$stageTag]: infeasible ${tubeOut.reason}")
            calls.noteTrace(tubeOut, snap, stageTag, baseline)
        } else {
            if (tubeOut.smbCapScale < 0.999) {
                calls.writeScale(tubeOut.smbCapScale)
                calls.writeMaxSmb((baseline.maxSmb * tubeOut.smbCapScale).coerceAtLeast(0.05))
                calls.writeMaxSmbHb((baseline.maxSmbHb * tubeOut.smbCapScale).coerceAtLeast(0.05))
            }
            if (tubeOut.basalCapScale < 0.999) {
                profile.current_basal = baseline.currentBasal * tubeOut.basalCapScale
                profile.max_daily_basal = baseline.maxDailyBasal * tubeOut.basalCapScale
            }
            consoleLog.add(
                "📐 TUBE-LINE-D4[$stageTag]: maxSMB=${aimiFmt2(calls.maxSmb())} " +
                    "basal×${aimiFmt3(tubeOut.basalCapScale)} | ${tubeOut.reason}",
            )
            calls.noteTrace(tubeOut, snap, stageTag, baseline)
        }
        calls.markApplied()
    } catch (e: Exception) {
        calls.logError("📐 TUBE-LINE-D4[$stageTag]: ${e.message}")
        consoleLog.add("Tube advisor failed (${e::class.simpleName}): ${e.message} — apply skipped")
    }
}
