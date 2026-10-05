package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt3
import app.aaps.plugins.aps.openAPSAIMI.control.StraightLineTubeAdvisor
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot

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
                minPredictedBg = snap.minPredMgdl,
                eventualBgMgdl = snap.eventualMgdl,
            ),
        )
        if (!tubeOut.feasible) {
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
