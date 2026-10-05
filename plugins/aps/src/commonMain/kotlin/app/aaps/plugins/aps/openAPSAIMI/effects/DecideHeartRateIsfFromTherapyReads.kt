package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.roundToInt

internal data class HeartRateIsfFromReads(
    val variableSensitivity: Float,
    val logLine: String?,
    val boluses: List<BS>,
)

/**
 * Reads heart rate, steps and boluses through [AimiTherapyReads], then runs [decideHeartRateIsf].
 *
 * The four watch samples of `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf`
 * (80, 80, 80, then 110 bpm) produce `HR_TREND_ISF x0.90` and ISF 45 from 50.
 * A failed heart-rate read leaves the sensitivity unchanged.
 */
internal fun decideHeartRateIsfFromTherapyReads(
    reads: AimiTherapyReads,
    nowMs: Long,
    stepsFromWatch: Boolean,
    startingSensitivity: Float,
    delta: Float,
    glucoseMgdl: Double,
    bgMgdl: Double,
    iob: Double,
    bolusFromMs: Long,
    bolusAscending: Boolean,
    profile: OapsProfileAimi,
    preferences: Preferences,
    consoleLog: MutableList<String>,
): HeartRateIsfFromReads {
    check(stepsFromWatch == preferences.get(BooleanKey.OApsAIMIEnableStepsFromWatch)) {
        "stepsFromWatch disagrees with OApsAIMIEnableStepsFromWatch"
    }
    val caches = readTherapyCaches(
        reads = reads,
        nowMs = nowMs,
        bolusFromMs = bolusFromMs,
        bolusAscending = bolusAscending,
        consoleLog = consoleLog,
    )
    var sensitivity = startingSensitivity
    var recentSteps10 = 0
    var averageBpm = Double.NaN
    var averageBpm10 = Double.NaN
    var averageBpm60 = 80.0
    var baselineReal = false
    val glucose = GlucoseStatusAIMI(
        glucose = glucoseMgdl,
        delta = delta.toDouble(),
        shortAvgDelta = delta.toDouble(),
        longAvgDelta = delta.toDouble(),
        date = nowMs,
        combinedDelta = delta.toDouble(),
    )
    val before = consoleLog.size
    decideHeartRateIsf(
        glucoseStatus = glucose,
        profile = profile,
        iobData = IobTotal(time = nowMs, iob = iob),
        bg = bgMgdl,
        preferences = preferences,
        consoleLog = consoleLog,
        consoleError = mutableListOf(),
        calls = object : AimiHeartRateIsfCalls {
            override fun iob(): Float = iob.toFloat()
            override fun roundDisplay(value: Double): Int = value.roundToInt()
            override fun stepsCached(now: Long): List<SC> = caches.steps
            override fun logSteps(samples: List<SC>) = Unit
            override fun setRecentSteps(steps5: Int, steps10: Int, steps15: Int, steps30: Int, steps60: Int, steps180: Int) {
                recentSteps10 = steps10
            }
            override fun phoneSteps5(): Int = 0
            override fun phoneSteps10(): Int = 0
            override fun phoneSteps15(): Int = 0
            override fun phoneSteps30(): Int = 0
            override fun phoneSteps60(): Int = 0
            override fun phoneSteps180(): Int = 0
            override fun heartRatesCached(now: Long): List<HR> = caches.heartRates
            override fun logHeartRates(samples: List<HR>) = Unit
            override fun setAverageBpm(value: Double) { averageBpm = value }
            override fun averageBpm(): Double = averageBpm
            override fun setAverageBpm10(value: Double) { averageBpm10 = value }
            override fun setAverageBpm60(value: Double) { averageBpm60 = value }
            override fun setAverageBpm180(value: Double) = Unit
            override fun setBaselineReal(value: Boolean) { baselineReal = value }
            override fun logHeartRateFailure(error: Exception) {
                consoleLog.add(
                    "HR windows failed (${error::class.simpleName}): ${error.message.orEmpty()} — averages 80, baseline not real",
                )
            }
            override fun recentSteps10(): Int = recentSteps10
            override fun averageBpm10(): Double = averageBpm10
            override fun averageBpm60(): Double = averageBpm60
            override fun baselineReal(): Boolean = baselineReal
            override fun delta(): Float = delta
            override fun scaleVariableSensitivity(factor: Float) {
                sensitivity *= factor
            }
        },
    )
    check(sensitivity <= startingSensitivity) { "heart-rate ISF $sensitivity exceeds the start $startingSensitivity" }
    val logLine = consoleLog.drop(before).singleOrNull { it.startsWith("💓 HR_TREND_ISF") }
    return HeartRateIsfFromReads(
        variableSensitivity = sensitivity,
        logLine = logLine,
        boluses = caches.boluses,
    )
}
