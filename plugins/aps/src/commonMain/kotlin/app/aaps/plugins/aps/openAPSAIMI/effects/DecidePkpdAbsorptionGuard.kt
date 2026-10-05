package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdAbsorptionGuard
import kotlin.math.max

internal enum class AimiPkpdGuardLogChannel {
    PIPELINE,
    FINALIZE,
}

internal data class AimiPkpdAbsorptionGuardResult(
    val smbOut: Float,
    val intervalAddMin: Int,
    val guard: PkpdAbsorptionGuard?,
    val effectiveFactor: Double,
    val multiplicationApplied: Boolean,
    val skippedDuplicate: Boolean,
)

/**
 * Android reads and writes around the one absorption-guard multiply.
 * Each method is the call that already existed at that line.
 */
internal interface AimiPkpdAbsorptionGuardCalls {
    fun alreadyApplied(): Boolean
    fun markApplied()
    fun predBg(): Double?
    fun bg(): Double
    fun delta(): Double
    fun shortAvgDelta(): Double
    fun targetBg(): Double
    fun intervalSmb(): Int
    fun setIntervalSmb(value: Int)
    fun logGuardError(line: String)
}

internal fun decidePkpdAbsorptionGuardOncePerTick(
    smbIn: Float,
    pkpdRuntime: PkPdRuntime?,
    windowSinceLastDoseMin: Double,
    anyMealModeForGuard: Boolean,
    isConfirmedHighRise: Boolean,
    mealAdvisorOneShot: Boolean,
    reason: StringBuilder?,
    logChannel: AimiPkpdGuardLogChannel,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiPkpdAbsorptionGuardCalls,
): AimiPkpdAbsorptionGuardResult {
    if (calls.alreadyApplied()) {
        return AimiPkpdAbsorptionGuardResult(
            smbOut = smbIn,
            intervalAddMin = 0,
            guard = null,
            effectiveFactor = 1.0,
            multiplicationApplied = false,
            skippedDuplicate = true,
        )
    }

    val guard = PkpdAbsorptionGuard.compute(
        pkpdRuntime = pkpdRuntime,
        windowSinceLastDoseMin = windowSinceLastDoseMin,
        bg = calls.bg(),
        delta = calls.delta(),
        shortAvgDelta = calls.shortAvgDelta(),
        targetBg = calls.targetBg(),
        predBg = calls.predBg(),
        isMealMode = anyMealModeForGuard,
        isConfirmedHighRise = isConfirmedHighRise,
    )
    calls.markApplied()

    val aggressivePriority = mealAdvisorOneShot || anyMealModeForGuard || isConfirmedHighRise
    val pkpdReliefEnabled = preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)
    val pkpdReliefMinFactor = preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor).coerceIn(0.50, 1.0)
    val effectiveFactor = if (aggressivePriority && pkpdReliefEnabled) {
        max(guard.factor, pkpdReliefMinFactor)
    } else {
        guard.factor
    }

    if (!guard.isActive()) {
        return AimiPkpdAbsorptionGuardResult(
            smbOut = smbIn,
            intervalAddMin = 0,
            guard = guard,
            effectiveFactor = effectiveFactor,
            multiplicationApplied = false,
            skippedDuplicate = false,
        )
    }

    val beforeGuard = smbIn
    val smbOut = (smbIn * effectiveFactor.toFloat()).coerceAtLeast(0f)
    if (guard.intervalAddMin > 0) {
        val updated = (calls.intervalSmb() + guard.intervalAddMin).coerceAtMost(10)
        calls.setIntervalSmb(updated)
        if (logChannel == AimiPkpdGuardLogChannel.PIPELINE) {
            consoleLog.add("INTERVAL_ADJUSTED: +${guard.intervalAddMin}m → ${updated}m total")
        }
    }
    if (smbOut < beforeGuard) {
        when (logChannel) {
            AimiPkpdGuardLogChannel.PIPELINE -> {
                calls.logGuardError(guard.toLogString())
                consoleLog.add("SMB_GUARDED: ${aimiFmt2(beforeGuard)}U → ${aimiFmt2(smbOut)}U")
                if (aggressivePriority && effectiveFactor > guard.factor) {
                    consoleLog.add(
                        "PKPD_RELIEF: factor ${aimiFmt2(guard.factor)} -> ${aimiFmt2(effectiveFactor)} " +
                            "(meal/advisor/high-rise priority)",
                    )
                }
            }
            AimiPkpdGuardLogChannel.FINALIZE -> {
                reason?.appendLine(
                    "🛡️ PKPD Guard (${guard.reason}): ${aimiFmt2(beforeGuard)} → ${aimiFmt2(smbOut)} U",
                )
                if (aggressivePriority && effectiveFactor > guard.factor) {
                    consoleLog.add(
                        "PKPD_RELIEF_FINALIZE: factor ${aimiFmt2(guard.factor)} -> ${aimiFmt2(effectiveFactor)} " +
                            "(meal/high-rise priority, finalizeAndCapSMB)",
                    )
                }
            }
        }
    }

    return AimiPkpdAbsorptionGuardResult(
        smbOut = smbOut,
        intervalAddMin = guard.intervalAddMin,
        guard = guard,
        effectiveFactor = effectiveFactor,
        multiplicationApplied = smbOut < beforeGuard,
        skippedDuplicate = false,
    )
}
