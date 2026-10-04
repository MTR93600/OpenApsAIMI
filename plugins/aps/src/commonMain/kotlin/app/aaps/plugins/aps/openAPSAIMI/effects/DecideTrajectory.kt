package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.trajectory.PhaseSpaceState
import app.aaps.plugins.aps.openAPSAIMI.trajectory.StableOrbit
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.WarningSeverity
import kotlin.math.abs
import kotlin.math.max

/**
 * Android reads and writes of [decideTrajectoryAnalysis], each at the line that uses it.
 * History refresh and the notification stay Android.
 */
internal interface AimiTrajectoryCalls {
    fun refreshEffectiveProfile(currentTime: Long)
    fun trajectoryHistory(
        currentTime: Long,
        bg: Double,
        delta: Double,
        bgacc: Double,
        iobActivityNow: Double,
        iob: Float,
        insulinActionState: InsulinActionState,
        lastBolusAgeMinutes: Double,
        cob: Float,
        profile: OapsProfileAimi,
    ): List<PhaseSpaceState>
    fun analyze(history: List<PhaseSpaceState>, orbit: StableOrbit): TrajectoryAnalysis?
    fun uamConfidence(): Double
    fun maxSmb(): Double
    fun setMaxSmb(value: Double)
    fun maxSmbHb(): Double
    fun setMaxSmbHb(value: Double)
    fun intervalSmb(): Int
    fun setIntervalSmb(value: Int)
    fun maxIob(): Double
    fun setMaxIob(value: Double)
    fun postTrajectoryWarning(message: String)
    fun logTrajectoryFailure(error: Exception)
}

/**
 * `applyTrajectoryAnalysis`.
 *
 * When the guard is on and the modulation is significant, the SMB ceiling is scaled by the
 * damping. In the locked scene 2.00 U becomes 1.00 U. A failed critical notification is logged
 * and the dose is kept. The outer failure still logs `Trajectory: ❌ Error` and disables the flag.
 */
internal fun decideTrajectoryAnalysis(
    currentTime: Long,
    bg: Double,
    delta: Double,
    bgacc: Double,
    iobActivityNow: Double,
    iob: Float,
    insulinActionState: InsulinActionState,
    lastBolusAgeMinutes: Double,
    cob: Float,
    targetBg: Double,
    profile: OapsProfileAimi,
    rT: RT,
    relevanceScore: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiTrajectoryCalls,
) {
    val trajectoryFlagEnabled = preferences.get(BooleanKey.OApsAIMITrajectoryGuardEnabled)

    rT.trajectoryRelevanceScore = relevanceScore

    if (trajectoryFlagEnabled) {
        try {
            calls.refreshEffectiveProfile(currentTime)
            val trajectoryHistory = calls.trajectoryHistory(
                currentTime = currentTime,
                bg = bg,
                delta = delta,
                bgacc = bgacc,
                iobActivityNow = iobActivityNow,
                iob = iob,
                insulinActionState = insulinActionState,
                lastBolusAgeMinutes = lastBolusAgeMinutes,
                cob = cob,
                profile = profile,
            )

            val stableOrbit = StableOrbit.fromProfile(targetBg, profile.current_basal)
            val traj = calls.analyze(trajectoryHistory, stableOrbit)

            if (traj == null) {
                consoleLog.add("🌀 Trajectory: ⏳ Warming up (${trajectoryHistory.size}/4 states, need 20min)")
                rT.trajectoryEnabled = false
            } else {
                val analysis = traj
                val statusEmoji = analysis.classification.emoji()
                val typeDesc = analysis.classification.description()

                consoleLog.add("🌀 Trajectory: $statusEmoji $typeDesc | κ=${aimiFmt2(analysis.metrics.curvature)} conv=${aimiFmt1(analysis.metrics.convergenceVelocity)} health=${aimiFmt0(analysis.metrics.healthScore * 100)}%")

                val artLines = analysis.classification.asciiArt().split("\n")
                artLines.forEach { line -> consoleLog.add("  $line") }
                consoleLog.add("  📊 Metrics: Coherence=${aimiFmt2(analysis.metrics.coherence)} Energy=${aimiFmt1(analysis.metrics.energyBalance)}U Openness=${aimiFmt2(analysis.metrics.openness)}")

                val mod = analysis.modulation
                val uamConfidence = calls.uamConfidence()
                val strongMealRiseContext =
                    bg >= 145.0 &&
                        delta >= 1.8 &&
                        (cob >= 6.0 || uamConfidence >= 0.45)
                if (relevanceScore > 0.4 && mod.isSignificant()) {
                    val effectiveSmbDamping = if (strongMealRiseContext) {
                        mod.smbDamping.coerceAtLeast(0.70)
                    } else {
                        mod.smbDamping
                    }
                    val effectiveIntervalStretch = if (strongMealRiseContext) {
                        mod.intervalStretch.coerceAtMost(1.10)
                    } else {
                        mod.intervalStretch
                    }
                    if (strongMealRiseContext && (effectiveSmbDamping != mod.smbDamping || effectiveIntervalStretch != mod.intervalStretch)) {
                        consoleLog.add(
                            "  🚀 TRAJ_RELAX meal-rise: SMB×${aimiFmt2(mod.smbDamping)}→${aimiFmt2(effectiveSmbDamping)} " +
                                "Int×${aimiFmt2(mod.intervalStretch)}→${aimiFmt2(effectiveIntervalStretch)} " +
                                "(BG=${aimiFmt0(bg)} Δ=${aimiFmt1(delta)} COB=${aimiFmt1(cob)} UAM=${aimiFmt2(uamConfidence)})"
                        )
                    }
                    consoleLog.add("  🎛 Modulation: SMB×${aimiFmt2(effectiveSmbDamping)} Int×${aimiFmt2(effectiveIntervalStretch)} (${mod.reason})")

                    if (abs(effectiveSmbDamping - 1.0) > 0.05) {
                        val orig = calls.maxSmb()
                        calls.setMaxSmb(orig * effectiveSmbDamping)
                        calls.setMaxSmbHb(calls.maxSmbHb() * effectiveSmbDamping)
                        consoleLog.add("    → SMB: ${aimiFmt2(orig)}U → ${aimiFmt2(calls.maxSmb())}U")
                    }
                    if (abs(effectiveIntervalStretch - 1.0) > 0.05) {
                        val orig = calls.intervalSmb()
                        calls.setIntervalSmb((orig * effectiveIntervalStretch).toInt().coerceIn(1, 20))
                        consoleLog.add("    → Interval: ${orig}min → ${calls.intervalSmb()}min")
                    }
                    if (abs(mod.safetyMarginExpand - 1.0) > 0.05) {
                        val origLimit = preferences.get(DoubleKey.ApsSmbMaxIob)
                        val floor = if (delta > 0.3) origLimit * 0.5 else 0.0
                        val candidate = calls.maxIob() * mod.safetyMarginExpand
                        val beforeMod = calls.maxIob()
                        calls.setMaxIob(max(candidate, floor))

                        if (calls.maxIob() < beforeMod) {
                            consoleLog.add("    → MaxIOB Modulation: ${aimiFmt2(beforeMod)}U → ${aimiFmt2(calls.maxIob())}U (Floor=${aimiFmt2(floor)}U)")
                        }
                    }
                } else if (relevanceScore <= 0.4) {
                    consoleLog.add("  ⏸ Modulation Gated (Relevance ${aimiFmt2(relevanceScore)} <= 0.4)")
                }

                analysis.warnings.filter { it.severity >= WarningSeverity.HIGH }.forEach { w ->
                    consoleLog.add("  🚨 ${w.severity.emoji()} ${w.message}")
                    if (w.severity == WarningSeverity.CRITICAL) {
                        try {
                            calls.postTrajectoryWarning(w.message)
                        } catch (e: Exception) {
                            consoleLog.add(
                                "Trajectory notification failed (${e::class.simpleName}): ${e.message.orEmpty()} — post skipped",
                            )
                        }
                    }
                }
                analysis.predictedConvergenceTime?.let {
                    consoleLog.add("  ⏱ Est. convergence: ${it}min")
                }

                rT.trajectoryEnabled = true
                rT.trajectoryType = analysis.classification.name
                rT.trajectoryCurvature = analysis.metrics.curvature
                rT.trajectoryConvergence = analysis.metrics.convergenceVelocity
                rT.trajectoryCoherence = analysis.metrics.coherence
                rT.trajectoryEnergy = analysis.metrics.energyBalance
                rT.trajectoryOpenness = analysis.metrics.openness
                rT.trajectoryHealth = (analysis.metrics.healthScore * 100).toInt()
                rT.trajectoryModulationActive = relevanceScore > 0.4 && analysis.modulation.isSignificant()
                rT.trajectoryWarningsCount = analysis.warnings.size
                rT.trajectoryConvergenceETA = analysis.predictedConvergenceTime
            }
        } catch (e: Exception) {
            consoleLog.add("🌀 Trajectory: ❌ Error (${e.message})")
            calls.logTrajectoryFailure(e)
            rT.trajectoryEnabled = false
        }
    } else {
        consoleLog.add("🌀 Trajectory: ⏸ Disabled")
        rT.trajectoryEnabled = false
    }
}
