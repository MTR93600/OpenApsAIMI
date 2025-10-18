package app.aaps.plugins.aps.openAPSAIMI

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
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
    val maxIOBExtraU: Double,
    val decayMinutes: Int
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
    private val zoneId: ZoneId = ZoneId.systemDefault()
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
        val localTime = now.atZone(zoneId).toLocalTime()

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
            lastSustainedMinutes = Duration.between(riseStart, now).toMinutes().coerceAtLeast(0).toInt()
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
            val multipliers = computeActiveMultipliers(candidateState, config, isMealActive)
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
            decayEnd = now.plus(Duration.ofMinutes(config.decayMinutes.toLong()))
        } else if (decayEnd == null) {
            decayEnd = now.plus(Duration.ofMinutes(config.decayMinutes.toLong()))
            decayStart = now
        }
        return decayResult(now, config)
    }

    private fun decayResult(now: Instant, config: NGRConfig): NGRResult {
        if (config.decayMinutes <= 0 || !hasActiveMultipliers()) {
            return inactiveResult("NGR decay finished", reset = true)
        }
        val end = decayEnd ?: now.plus(Duration.ofMinutes(config.decayMinutes.toLong())).also {
            decayEnd = it
            decayStart = now
        }
        val remainingMillis = Duration.between(now, end).toMillis()
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
        val reason = String.format(Locale.US, "NGR decay: multipliers %.2f/%.2f, %d min remaining.", smb, basal, minutesCeil)
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
            !time.isBefore(start) && !time.isAfter(end)
        } else {
            !time.isBefore(start) || !time.isAfter(end)
        }
    }

    private fun computeActiveMultipliers(state: NGRState, config: NGRConfig, isMealActive: Boolean): Triple<Double, Double, Double> {
        val intensity = if (state == NGRState.CONFIRMED) 1.0 else 0.6
        val mealFactor = if (isMealActive) 0.5 else 1.0
        val smb = 1.0 + (config.allowSMBBoostFactor - 1.0) * intensity * mealFactor
        val basal = 1.0 + (config.allowBasalBoostFactor - 1.0) * intensity * mealFactor
        val headroom = config.maxIOBExtraU * intensity * mealFactor
        return Triple(smb.coerceAtLeast(1.0), basal.coerceAtLeast(1.0), headroom.coerceAtLeast(0.0))
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
            String.format(Locale.US, " (persistence %d×5')", max(3, persistenceCount))
        } else {
            ""
        }
        return String.format(
            Locale.US,
            "NGR %s: rise %.1f mg/dL/5' for %d min, eventual +%d mg/dL%s.",
            label,
            slope,
            minutes,
            over,
            persistence
        )
    }
}

class NightGrowthResistanceMode(
    private val monitor: NightGrowthResistanceMonitor = DefaultNightGrowthResistanceMonitor()
) {
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
    ): NGRResult = monitor.evaluate(
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
}