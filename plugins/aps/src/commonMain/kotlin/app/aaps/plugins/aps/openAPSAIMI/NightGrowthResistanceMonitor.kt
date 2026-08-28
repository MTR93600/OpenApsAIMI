package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class NGRConfig(
    val enabled: Boolean,
    val pediatricAgeYears: Int,
    val nightStart: LocalTime,
    val nightEnd: LocalTime,
    val minRiseSlope: Double,
    val minDurationMin: Int,
    val minEventualOverTarget: Int,
    val allowSMBBoostFactor: Double,
    val allowBasalBoostFactor: Double,
    val maxSMBClampU: Double,
    val extraIobPer30Min: Double,
    val decayMinutes: Int,
    val headroomSlotCap: Int = 4
)

enum class NGRState { INACTIVE, SUSPECTED, CONFIRMED, DECAY }

data class NGRResult(
    val state: NGRState,
    val smbMultiplier: Double,
    val basalMultiplier: Double,
    val extraIOBHeadroomU: Double,
    val reason: String
)

interface NightGrowthResistanceMonitor {
    fun evaluate(
        now: Instant,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        eventualBG: Double,
        targetBG: Double,
        iob: Double,
        cob: Double,
        react: Double,
        isMealActive: Boolean,
        config: NGRConfig
    ): NGRResult
}

class DefaultNightGrowthResistanceMonitor(
    private val zone: TimeZone = TimeZone.currentSystemDefault()
) : NightGrowthResistanceMonitor {

    private var state: NGRState = NGRState.INACTIVE
    private var stateSince: Instant? = null
    private var riseStart: Instant? = null
    private var positiveCount: Int = 0
    private var lastSlope: Double = 0.0
    private var lastSustainedMinutes: Int = 0
    private var decayStart: Instant? = null
    private var decayEnd: Instant? = null
    private var lastActiveMultipliers: Triple<Double, Double, Double> = Triple(1.0, 1.0, 0.0)

    override fun evaluate(
        now: Instant,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        eventualBG: Double,
        targetBG: Double,
        iob: Double,
        cob: Double,
        react: Double,
        isMealActive: Boolean,
        config: NGRConfig
    ): NGRResult {
        val localTime = now.toLocalDateTime(zone).time

        if (!config.enabled) {
            return inactiveResult("NGR inactive: disabled", reset = true)
        }
        if (config.pediatricAgeYears >= 18) {
            return inactiveResult("NGR inactive: age ≥ 18", reset = true)
        }
        if (!isWithinNight(localTime, config.nightStart, config.nightEnd)) {
            return inactiveResult("NGR inactive: outside night window", reset = true)
        }

        val slopeCandidates = listOf(delta, shortAvgDelta, longAvgDelta).filter { it.isFinite() }
        val slope = slopeCandidates.maxOrNull() ?: 0.0
        val positiveSlope = slope >= config.minRiseSlope

        if (positiveSlope) {
            if (riseStart == null) riseStart = now
            lastSustainedMinutes = ((now.toEpochMilliseconds() - riseStart!!.toEpochMilliseconds()) / 60_000L)
                .coerceAtLeast(0).toInt()
            positiveCount = (positiveCount + 1).coerceAtMost(12)
            lastSlope = slope
        } else {
            riseStart = null
            lastSustainedMinutes = 0
            positiveCount = 0
            lastSlope = slope
        }

        val eventualOver = eventualBG - targetBG
        val guardTriggered = bg < 110.0 || delta <= 0.0 || shortAvgDelta <= 0.0 || eventualBG <= targetBG ||
            (react.isFinite() && react > 0 && react < 120.0) || targetBG <= 90.0

        if (guardTriggered) {
            riseStart = null
            positiveCount = 0
            lastSustainedMinutes = 0
            return when (state) {
                NGRState.SUSPECTED, NGRState.CONFIRMED, NGRState.DECAY -> startDecay(now, config)
                else -> inactiveResult(reset = false)
            }
        }

        val suspicionMet = positiveSlope &&
            lastSustainedMinutes >= config.minDurationMin &&
            eventualOver >= config.minEventualOverTarget &&
            eventualBG > targetBG && cob <= 5.0

        val confirmMet = suspicionMet && (
            positiveCount >= 3 ||
                (longAvgDelta > shortAvgDelta && longAvgDelta > 0) ||
                state == NGRState.CONFIRMED
            )

        val candidateState = when {
            confirmMet -> NGRState.CONFIRMED
            suspicionMet -> NGRState.SUSPECTED
            else -> null
        }

        if (candidateState != null) {
            if (state != candidateState) {
                state = candidateState
                stateSince = now
            }
            val multipliers = computeActiveMultipliers(now, candidateState, config, isMealActive)
            lastActiveMultipliers = multipliers
            decayStart = null
            decayEnd = null
            val reason = buildActiveReason(candidateState, lastSlope, lastSustainedMinutes, eventualOver, positiveCount)
            return NGRResult(candidateState, multipliers.first, multipliers.second, multipliers.third, reason)
        }

        return when (state) {
            NGRState.SUSPECTED, NGRState.CONFIRMED, NGRState.DECAY -> startDecay(now, config)
            else -> inactiveResult(reset = false)
        }
    }

    private fun startDecay(now: Instant, config: NGRConfig): NGRResult {
        if (config.decayMinutes <= 0 || !hasActiveMultipliers()) {
            return inactiveResult("NGR decay finished", reset = true)
        }
        if (state != NGRState.DECAY) {
            state = NGRState.DECAY
            stateSince = now
            decayStart = now
            decayEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + config.decayMinutes * 60_000L)
        } else if (decayEnd == null) {
            decayEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + config.decayMinutes * 60_000L)
            decayStart = now
        }
        return decayResult(now, config)
    }

    private fun decayResult(now: Instant, config: NGRConfig): NGRResult {
        if (config.decayMinutes <= 0 || !hasActiveMultipliers()) {
            return inactiveResult("NGR decay finished", reset = true)
        }
        val end = decayEnd ?: Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + config.decayMinutes * 60_000L).also {
            decayEnd = it
            decayStart = now
        }
        val remainingMillis = end.toEpochMilliseconds() - now.toEpochMilliseconds()
        if (remainingMillis <= 0) {
            return inactiveResult("NGR decay finished", reset = true)
        }
        val remainingMinutes = remainingMillis / 60_000.0
        val ratio = min(1.0, max(0.0, remainingMinutes / config.decayMinutes))
        val smb = 1.0 + (lastActiveMultipliers.first - 1.0) * ratio
        val basal = 1.0 + (lastActiveMultipliers.second - 1.0) * ratio
        val headroom = lastActiveMultipliers.third * ratio
        state = NGRState.DECAY
        val minutesCeil = ceil(remainingMinutes).toInt()
        val reason = "NGR decay: multipliers ${aimiFmt2(smb)}/${aimiFmt2(basal)}, $minutesCeil min remaining."
        return NGRResult(NGRState.DECAY, smb, basal, headroom, reason)
    }

    private fun inactiveResult(message: String = "", reset: Boolean): NGRResult {
        val previous = state
        if (reset) {
            internalReset()
        } else {
            state = NGRState.INACTIVE
            stateSince = null
        }
        val reason = if (message.isNotEmpty() && previous != NGRState.INACTIVE) message else ""
        return NGRResult(NGRState.INACTIVE, 1.0, 1.0, 0.0, reason)
    }

    private fun internalReset() {
        state = NGRState.INACTIVE
        stateSince = null
        riseStart = null
        positiveCount = 0
        lastSlope = 0.0
        lastSustainedMinutes = 0
        decayStart = null
        decayEnd = null
        lastActiveMultipliers = Triple(1.0, 1.0, 0.0)
    }

    private fun hasActiveMultipliers(): Boolean {
        return lastActiveMultipliers.first > 1.0001 ||
            lastActiveMultipliers.second > 1.0001 ||
            lastActiveMultipliers.third > 0.0001
    }

    private fun isWithinNight(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        return if (start <= end) {
            time >= start && time <= end
        } else {
            time >= start || time <= end
        }
    }

    private fun computeActiveMultipliers(now: Instant, state: NGRState, config: NGRConfig, isMealActive: Boolean): Triple<Double, Double, Double> {
        val intensity = if (state == NGRState.CONFIRMED) 1.0 else 0.6
        val mealFactor = if (isMealActive) 0.5 else 1.0
        val smb = 1.0 + (config.allowSMBBoostFactor - 1.0) * intensity * mealFactor
        val basal = 1.0 + (config.allowBasalBoostFactor - 1.0) * intensity * mealFactor
        val headroom = computeHeadroom(now, config, intensity * mealFactor)
        return Triple(smb.coerceAtLeast(1.0), basal.coerceAtLeast(1.0), headroom.coerceAtLeast(0.0))
    }

    private fun computeHeadroom(now: Instant, config: NGRConfig, scaledIntensity: Double): Double {
        if (config.extraIobPer30Min <= 0.0 || stateSince == null) return 0.0
        val activeMinutes = ((now.toEpochMilliseconds() - stateSince!!.toEpochMilliseconds()) / 60_000L).coerceAtLeast(0)
        val slots = max(1, ceil(activeMinutes / 30.0).toInt())
        val cappedSlots = min(config.headroomSlotCap, slots)
        val raw = cappedSlots * config.extraIobPer30Min * scaledIntensity
        return raw.coerceAtMost(config.headroomSlotCap * config.extraIobPer30Min)
    }

    private fun buildActiveReason(
        state: NGRState,
        slope: Double,
        minutes: Int,
        eventualOver: Double,
        persistenceCount: Int
    ): String {
        val label = when (state) {
            NGRState.SUSPECTED -> "suspected"
            NGRState.CONFIRMED -> "confirmed"
            else -> "active"
        }
        val over = max(0, eventualOver.roundToInt())
        val persistence = if (state == NGRState.CONFIRMED) {
            " (persistence ${max(3, persistenceCount)}×5')"
        } else {
            ""
        }
        return "NGR $label: rise ${aimiFmt1(slope)} mg/dL/5' for $minutes min, eventual +$over mg/dL$persistence."
    }
}

class NightGrowthResistanceMode(
    private val monitor: NightGrowthResistanceMonitor = DefaultNightGrowthResistanceMonitor()
) {
    @Volatile
    private var latestResult: NGRResult? = null

    fun evaluate(
        now: Instant,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        eventualBG: Double,
        targetBG: Double,
        iob: Double,
        cob: Double,
        react: Double,
        isMealActive: Boolean,
        config: NGRConfig
    ): NGRResult {
        val result = monitor.evaluate(
            now = now,
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            eventualBG = eventualBG,
            targetBG = targetBG,
            iob = iob,
            cob = cob,
            react = react,
            isMealActive = isMealActive,
            config = config
        )
        latestResult = result
        return result
    }

    fun latestResult(): NGRResult? = latestResult?.copy()
}
