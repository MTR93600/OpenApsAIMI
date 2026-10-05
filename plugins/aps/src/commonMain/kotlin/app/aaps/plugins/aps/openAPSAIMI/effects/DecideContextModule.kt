package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.context.ContextInfluenceEngine
import app.aaps.plugins.aps.openAPSAIMI.context.ContextMode
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import kotlin.math.abs

/**
 * Android reads and writes of the context module.
 * Each method is the call that already existed at that line.
 * `String.format` and `aapsLogger` stay Android.
 */
internal interface AimiContextModuleCalls {
    fun writeSmbCeiling(value: Double?)
    fun writeSuppressSmb(value: Boolean)
    fun snapshot(): ContextSnapshot
    fun writeSnapshot(snapshot: ContextSnapshot)
    fun writeActivityActive(value: Boolean)
    fun contextModeName(): String
    fun maxSmb(): Double
    fun writeMaxSmb(value: Double)
    fun maxSmbHb(): Double
    fun writeMaxSmbHb(value: Double)
    fun intervalSmb(): Int
    fun writeIntervalSmb(value: Int)
    fun smbScaleLine(original: Double, updated: Double, factor: Float): String
    fun intervalLine(original: Int, updated: Int, extra: Int): String
    fun exerciseHyperOverride(): Boolean
    fun sportTime(): Boolean
    fun activityActive(): Boolean
    fun writeExerciseLockout(value: Boolean)
    fun logContextFailure(error: Exception)
}

internal fun decideApplyContextModule(
    bg: Double,
    iob: Double,
    cob: Double,
    rT: RT,
    preferences: Preferences,
    engine: ContextInfluenceEngine,
    consoleLog: MutableList<String>,
    calls: AimiContextModuleCalls,
): Double? {
    var contextTargetOverride: Double? = null
    // Reset per tick; set below when a context influence is computed. Enforced at finalizeAndCapSMB.
    calls.writeSmbCeiling(null)
    calls.writeSuppressSmb(false)
    val contextEnabled = preferences.get(BooleanKey.OApsAIMIContextEnabled)
    if (contextEnabled) {
        try {
            consoleLog.add("═══ CONTEXT MODULE ═══")
            val contextSnapshot = calls.snapshot()
            // Keep the fresh snapshot as the tick's source of truth so the meal-priority guards
            // (legacy prebolus / meal advisor) read the same context as the finalize gate.
            calls.writeSnapshot(contextSnapshot)
            if (contextSnapshot.intentCount > 0) {
                calls.writeActivityActive(contextSnapshot.hasActivity)
                val modeStr = calls.contextModeName()
                val contextMode = when (modeStr) {
                    "CONSERVATIVE" -> ContextMode.CONSERVATIVE
                    "AGGRESSIVE" -> ContextMode.AGGRESSIVE
                    else -> ContextMode.BALANCED
                }
                val contextInfluence = engine.computeInfluence(
                    snapshot = contextSnapshot,
                    currentBG = bg,
                    iob = iob,
                    cob = cob,
                    mode = contextMode,
                )
                // Carry protective SMB caps to the universal finalize gate (robust vs upstream maxSMB resets).
                calls.writeSmbCeiling(contextInfluence.smbCeilingU)
                calls.writeSuppressSmb(contextInfluence.suppressSmb)
                consoleLog.add("🎯 Active Contexts: ${contextSnapshot.intentCount}")
                contextSnapshot.activeIntents.take(3).forEach { intent ->
                    consoleLog.add("  • ${intent::class.simpleName ?: "Unknown"}")
                }
                if (abs(contextInfluence.smbFactorClamp - 1.0f) > 0.05f) {
                    val origMaxSMB = calls.maxSmb()
                    val scaled = origMaxSMB * contextInfluence.smbFactorClamp
                    calls.writeMaxSmb(scaled)
                    val origHb = calls.maxSmbHb()
                    calls.writeMaxSmbHb(origHb * contextInfluence.smbFactorClamp)
                    consoleLog.add(calls.smbScaleLine(origMaxSMB, scaled, contextInfluence.smbFactorClamp))
                }
                if (contextInfluence.extraIntervalMin > 0) {
                    val origInterval = calls.intervalSmb()
                    val updated = (origInterval + contextInfluence.extraIntervalMin).coerceIn(1, 20)
                    calls.writeIntervalSmb(updated)
                    consoleLog.add(calls.intervalLine(origInterval, updated, contextInfluence.extraIntervalMin))
                }
                if (contextInfluence.preferBasal && !calls.exerciseHyperOverride()) {
                    consoleLog.add("  ⚠️ Prefers TEMP BASAL over SMB (SMB Disabled)")
                    calls.writeMaxSmb(0.0)
                    calls.writeMaxSmbHb(0.0)
                    if (contextSnapshot.hasActivity) {
                        contextTargetOverride = 150.0
                        consoleLog.add("  🎯 Sport Target Override -> 150 mg/dL")
                    }
                } else if (contextInfluence.preferBasal && calls.exerciseHyperOverride()) {
                    consoleLog.add("  🏃 Activity preferBasal skipped (hyper+exercise basal override)")
                }
                contextInfluence.reasoningSteps.take(3).forEach { reason ->
                    consoleLog.add("  → $reason")
                }
                rT.contextEnabled = true
                rT.contextIntentCount = contextSnapshot.intentCount
                rT.contextModulation = contextInfluence.smbFactorClamp.toDouble()
            } else {
                consoleLog.add("🎯 Context: No active intents")
                calls.writeActivityActive(false)
                rT.contextEnabled = true
                rT.contextIntentCount = 0
            }
        } catch (e: Exception) {
            consoleLog.add("⚠️ Context error: ${e.message}")
            consoleLog.add("Context module failed (${e::class.simpleName}): ${e.message} — target null")
            calls.logContextFailure(e)
            rT.contextEnabled = false
        }
    } else {
        rT.contextEnabled = false
    }
    val lockout = calls.sportTime() || calls.activityActive()
    calls.writeExerciseLockout(lockout)
    if (lockout) {
        calls.writeMaxSmb(0.0)
        calls.writeMaxSmbHb(0.0)
    }
    consoleLog.add("═══════════════════════════════════")
    return contextTargetOverride
}
