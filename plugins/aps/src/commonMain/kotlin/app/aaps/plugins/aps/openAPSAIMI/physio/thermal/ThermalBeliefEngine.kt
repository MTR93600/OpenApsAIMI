package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import kotlin.math.abs

internal object ThermalBeliefEngine {

    /** Wearable skin-temperature noise band — deltas below this are treated as zero. */
    private const val NOISE_FLOOR_DELTA_C = 0.03

    /** Minimum meaningful warming/cooling rate (°C per hour). */
    private const val NOISE_FLOOR_SLOPE_C_PER_H = 0.01

    fun build(
        window: ThermalDataWindowMTR,
        hrNowBpm: Int,
        rhrRestingBpm: Int,
        sleepDebtMinutes: Int,
        hrvRmssd: Double,
        wCyclePhase: CyclePhase? = null,
    ): ThermalBeliefDigest {
        val samples = window.skinSamples.sortedBy { it.timestampMs }
        if (samples.isEmpty() && window.basalBodyTemperature == null) {
            return ThermalBeliefDigest.EMPTY.copy(
                narrative = "Thermal rhythm pending · sync sleep and RHR via Health Connect, or add an Oura API token in Physio settings",
            )
        }

        ThermalBaselineStore.observeSamples(samples)
        val baseline = ThermalBaselineStore.personalBaselineDeltaC() ?: 0.0
        val latest = samples.lastOrNull()
        val latestDelta = latest?.deltaCelsius ?: 0.0
        val rawDeltaVsBaseline = latestDelta - baseline
        val deltaVsBaseline = deadbandDelta(rawDeltaVsBaseline)
        val nowMs = window.fetchedAtMs.takeIf { it > 0L } ?: aimiWallClockMs()
        val slope6h = deadbandSlope(slope(samples, nowMs, hours = 6))
        val slope24h = deadbandSlope(slope(samples, nowMs, hours = 24))
        val circadianDisruption = circadianDisruptionScore(samples, baseline)
        val hrElevation = if (hrNowBpm > 0 && rhrRestingBpm > 0) {
            ((hrNowBpm - rhrRestingBpm).toDouble() / 40.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val sleepDebtScore = (sleepDebtMinutes / 180.0).coerceIn(0.0, 1.0)
        val hrvStress = if (hrvRmssd in 1.0..30.0) {
            ((30.0 - hrvRmssd) / 30.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val inflammationIndex = combine(
            positive(deltaVsBaseline) * 0.45,
            positive(slope6h) * 0.25,
            hrElevation * 0.15,
            sleepDebtScore * 0.10,
            circadianDisruption * 0.15,
        )
        val recoveryBurden = combine(
            positive(-deltaVsBaseline) * 0.35,
            positive(-slope6h) * 0.20,
            sleepDebtScore * 0.25,
            hrvStress * 0.20,
        )

        val basalC = window.basalBodyTemperature?.temperatureCelsius
        val wCycleHint = resolveWCycleHint(wCyclePhase, basalC, rawDeltaVsBaseline)
        val hypothesis = resolveHypothesis(
            deltaVsBaseline = deltaVsBaseline,
            slope6h = slope6h,
            inflammationIndex = inflammationIndex,
            recoveryBurden = recoveryBurden,
            hrElevation = hrElevation,
            wCycleHint = wCycleHint,
        )
        val confidence = resolveConfidence(
            sampleCount = samples.size,
            deltaVsBaseline = deltaVsBaseline,
            hasBaseline = ThermalBaselineStore.personalBaselineDeltaC() != null,
            hasBasalBody = basalC != null,
            sourceTier = window.sourceTier,
        )
        val dataOrigin = window.resolvedSource.ifBlank {
            latest?.dataOrigin ?: window.basalBodyTemperature?.dataOrigin ?: "Unknown"
        }

        return ThermalBeliefDigest(
            hypothesis = hypothesis,
            deltaVsBaselineC = deltaVsBaseline,
            slope6hC = slope6h,
            slope24hC = slope24h,
            circadianDisruption = circadianDisruption,
            inflammationIndex = inflammationIndex,
            recoveryBurden = recoveryBurden,
            confidence = confidence,
            narrative = buildNarrative(hypothesis, deltaVsBaseline, wCycleHint, window),
            wCycleHint = wCycleHint,
            basalBodyTempC = basalC,
            sampleCount = samples.size,
            dataOrigin = dataOrigin,
            sourceTier = window.sourceTier,
        )
    }

    fun buildFromCache(
        hrNowBpm: Int,
        rhrRestingBpm: Int,
        sleepDebtMinutes: Int,
        hrvRmssd: Double,
        wCyclePhase: CyclePhase? = null,
    ): ThermalBeliefDigest =
        build(
            window = ThermalDataCache.get(),
            hrNowBpm = hrNowBpm,
            rhrRestingBpm = rhrRestingBpm,
            sleepDebtMinutes = sleepDebtMinutes,
            hrvRmssd = hrvRmssd,
            wCyclePhase = wCyclePhase,
        )

    private fun slope(samples: List<ThermalSampleMTR>, nowMs: Long, hours: Int): Double {
        if (samples.size < 2) return 0.0
        val cutoff = nowMs - hours * 3_600_000L
        val window = samples.filter { it.timestampMs >= cutoff }
        if (window.size < 2) return 0.0
        val first = window.first()
        val last = window.last()
        val elapsedHours = ((last.timestampMs - first.timestampMs).coerceAtLeast(1L)) / 3_600_000.0
        return (last.deltaCelsius - first.deltaCelsius) / elapsedHours
    }

    private fun circadianDisruptionScore(samples: List<ThermalSampleMTR>, baseline: Double): Double {
        if (samples.size < 4) return 0.0
        val recent = samples.takeLast(8)
        val deadbanded = recent.map { deadbandDelta(it.deltaCelsius - baseline) }
        val spread = deadbanded.maxOrNull()!! - deadbanded.minOrNull()!!
        val drift = abs(deadbanded.last())
        return ((spread / 1.2) + (drift / 0.8)).coerceIn(0.0, 1.0) / 2.0
    }

    private fun deadbandDelta(deltaCelsius: Double): Double =
        if (abs(deltaCelsius) < NOISE_FLOOR_DELTA_C) 0.0 else deltaCelsius

    private fun deadbandSlope(slopeCPerHour: Double): Double =
        if (abs(slopeCPerHour) < NOISE_FLOOR_SLOPE_C_PER_H) 0.0 else slopeCPerHour

    private fun resolveWCycleHint(
        phase: CyclePhase?,
        basalC: Double?,
        deltaVsBaseline: Double,
    ): String? {
        if (phase == null) return null
        return when {
            phase == CyclePhase.LUTEAL && basalC != null && basalC >= 36.4 && deltaVsBaseline >= 0.15 ->
                "LUTEAL_BBT_RISE"
            phase == CyclePhase.OVULATION && deltaVsBaseline >= 0.10 ->
                "OVULATION_THERMAL_SHIFT"
            phase == CyclePhase.MENSTRUATION && deltaVsBaseline <= -0.10 ->
                "MENSTRUAL_THERMAL_DIP"
            else -> null
        }
    }

    private fun resolveHypothesis(
        deltaVsBaseline: Double,
        slope6h: Double,
        inflammationIndex: Double,
        recoveryBurden: Double,
        hrElevation: Double,
        wCycleHint: String?,
    ): ThermalHypothesis {
        if (wCycleHint == "LUTEAL_BBT_RISE" || wCycleHint == "OVULATION_THERMAL_SHIFT") {
            return ThermalHypothesis.CYCLE_BBT_RISE
        }
        if (deltaVsBaseline <= -0.25 && slope6h <= -0.05 && hrElevation >= 0.35) {
            return ThermalHypothesis.HYPO_SYMPATHETIC_COOLING
        }
        if (inflammationIndex >= 0.55 && (deltaVsBaseline >= 0.20 || slope6h >= 0.04)) {
            return ThermalHypothesis.INFLAMMATORY_DRIFT
        }
        if (recoveryBurden >= 0.55 && (deltaVsBaseline <= -0.10 || slope6h <= -0.03)) {
            return ThermalHypothesis.RECOVERY_COOLING
        }
        if (recoveryBurden >= 0.45 && inflammationIndex < 0.35) {
            return ThermalHypothesis.FATIGUE_DYSREGULATION
        }
        if (abs(deltaVsBaseline) < 0.12 && abs(slope6h) < 0.03) {
            return ThermalHypothesis.BASELINE_STABLE
        }
        return if (inflammationIndex >= recoveryBurden) {
            ThermalHypothesis.INFLAMMATORY_DRIFT
        } else {
            ThermalHypothesis.FATIGUE_DYSREGULATION
        }
    }

    private fun resolveConfidence(
        sampleCount: Int,
        deltaVsBaseline: Double,
        hasBaseline: Boolean,
        hasBasalBody: Boolean,
        sourceTier: ThermalSourceTier,
    ): Double {
        var score = 0.25
        if (sampleCount >= 3) score += 0.20
        if (sampleCount >= 12) score += 0.15
        if (hasBaseline) score += 0.20
        if (hasBasalBody) score += 0.10
        if (abs(deltaVsBaseline) >= 0.08) score += 0.10
        val capped = score.coerceIn(0.0, 1.0)
        return if (sourceTier == ThermalSourceTier.INFERRED) {
            capped.coerceAtMost(0.50)
        } else {
            capped
        }
    }

    private fun buildNarrative(
        hypothesis: ThermalHypothesis,
        deltaVsBaseline: Double,
        wCycleHint: String?,
        window: ThermalDataWindowMTR,
    ): String {
        val prefix = when {
            window.resolvedSource.startsWith(ThermalDataOrigins.OURA_API) ->
                "Oura temperature deviation vs your baseline. "
            window.resolvedSource.startsWith(ThermalDataOrigins.HC_INFERRED) ->
                "Recovery rhythm inferred from Health Connect sleep, RHR, and HRV (not measured skin temperature). "
            window.resolvedSource.startsWith(ThermalDataOrigins.HC_SKIN) ->
                ""
            else -> ""
        }
        val magnitude = aimiFmt1(abs(deltaVsBaseline))
        val body = when (hypothesis) {
            ThermalHypothesis.DATA_PENDING ->
                "Thermal rhythm pending · sync sleep and RHR via Health Connect, or add an Oura API token in Physio settings"
            ThermalHypothesis.BASELINE_STABLE ->
                if (window.sourceTier == ThermalSourceTier.INFERRED) {
                    "Recovery signals are stable around your personal baseline."
                } else {
                    "Skin temperature rhythm is stable around your personal baseline."
                }
            ThermalHypothesis.INFLAMMATORY_DRIFT ->
                if (window.sourceTier == ThermalSourceTier.INFERRED) {
                    "AIMI sees warming recovery stress (+${magnitude}°C proxy vs baseline) consistent with inflammatory or illness load."
                } else {
                    "AIMI sees a progressive skin warming (+${magnitude}°C vs baseline) consistent with inflammatory or illness stress."
                }
            ThermalHypothesis.RECOVERY_COOLING ->
                if (window.sourceTier == ThermalSourceTier.INFERRED) {
                    "AIMI sees a cooling recovery drift that may reflect reduced metabolic drive or recovery burden."
                } else {
                    "AIMI sees a cooling skin drift that may reflect recovery burden or reduced metabolic drive."
                }
            ThermalHypothesis.HYPO_SYMPATHETIC_COOLING ->
                "AIMI sees rapid cooling with elevated heart rate — cross-check CGM for sympathetic hypo stress."
            ThermalHypothesis.FATIGUE_DYSREGULATION ->
                "AIMI sees rhythm disruption that may reflect fatigue or poor recovery."
            ThermalHypothesis.CYCLE_BBT_RISE ->
                when (wCycleHint) {
                    "LUTEAL_BBT_RISE" ->
                        "Basal temperature rise aligns with luteal phase — AIMI reads cycle physiology before insulin posture."
                    "OVULATION_THERMAL_SHIFT" ->
                        "Thermal shift aligns with ovulation window — cycle context informs AIMI understanding."
                    else ->
                        "Cycle-related temperature shift detected — AIMI reads hormonal physiology before insulin posture."
                }
        }
        return if (hypothesis == ThermalHypothesis.DATA_PENDING) body else prefix + body
    }

    private fun positive(value: Double): Double = value.coerceAtLeast(0.0)

    private fun combine(vararg parts: Double): Double =
        parts.sum().coerceIn(0.0, 1.0)
}
