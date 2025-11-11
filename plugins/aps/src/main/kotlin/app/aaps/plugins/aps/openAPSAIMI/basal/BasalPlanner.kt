package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.BasalPlan
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Planificateur basal (pré-filtre sécurité/plateau) basé sur LoopContext du package model.
 * Retourne un BasalPlan? si une action simple et prioritaire est décidée (suspend, micro-resume, kicker, anti-stall).
 */
@Reusable
class BasalPlanner @Inject constructor(
    private val adaptiveBasal: AIMIAdaptiveBasal,
    private val log: AAPSLogger
) {
    // ===== Paramètres par défaut (prudence) =====
    private val HYPO_SUSPEND_MGDL = 75.0      // seuil direct suspend
    private val HYPO_SUSPEND_SOFT = 85.0      // seuil "soft" si Δ négatif
    private val HYPO_SUSPEND_MIN = 30

    private val ZERO_RESUME_MIN = 10          // reprise si >=10 min à 0U/h
    private val ZERO_RESUME_FRAC = 0.25       // 25% du profil
    private val ZERO_RESUME_MAX_MIN = 30

    private val HIGH_BG = 180.0
    private val PLATEAU_DELTA_ABS = 2.5       // mg/dL/5min
    private val KICK_FRAC = 0.15              // +15% profil
    private val KICK_MIN_UPH = 0.20
    private val KICK_MINUTES = 10

    private val ANTI_STALL_FRAC = 0.10        // +10% si Δ≈0
    private val DELTA_POS_RELEASE = 1.0

    private val MAX_MULT = 1.60               // plafond: 1.6× profil

    fun plan(ctx: LoopContext): BasalPlan? {
        val mgdl = ctx.bg.mgdl
        val d5 = ctx.bg.delta5
        val short = ctx.bg.shortAvgDelta ?: d5
        val long = ctx.bg.longAvgDelta ?: d5

        val profileBasal = ctx.profile.basalProfileUph
        val pump = ctx.pump

        val maxBasal = pump.maxBasal.coerceAtLeast(profileBasal)
        val step = pump.basalStep.coerceAtLeast(0.05)
        val minDur = pump.minDurationMin.coerceAtLeast(10)

        // Historique via provider (neutre = 0/false si non branché)
        val hist = BasalHistoryUtils.historyProvider
        val zeroSinceMin = hist.zeroBasalDurationMinutes(lookBackHours = 6)
        val lastTempIsZero = hist.lastTempIsZero()
        val minutesSinceLastChange = hist.minutesSinceLastChange()

        if (profileBasal <= 0.0) return null

        // 1) Hypo guard / suspend
        if (mgdl <= HYPO_SUSPEND_MGDL || (mgdl <= HYPO_SUSPEND_SOFT && d5 < 0.0)) {
            return BasalPlan(
                rateUph = 0.0,
                durationMin = HYPO_SUSPEND_MIN,
                reason = "Hypo guard: BG=$mgdl, Δ=${fmt1(d5)} → suspend ${HYPO_SUSPEND_MIN}m"
            )
        }

        // 2) Micro-resume après 0 basal prolongé
        if (lastTempIsZero && zeroSinceMin >= ZERO_RESUME_MIN) {
            val base = max(KICK_MIN_UPH, profileBasal * ZERO_RESUME_FRAC)
            val rate = clampAndQuantize(base, profileBasal, maxBasal, step)
            val dur = min(ZERO_RESUME_MAX_MIN, max(minDur, minutesSinceLastChange / 2))
            return BasalPlan(
                rateUph = rate,
                durationMin = dur,
                reason = "Micro-resume after ${zeroSinceMin}m @0U/h → ${fmt2(rate)}U/h × ${dur}m"
            )
        }

        // 3) Kicker plateau haut (BG élevé & plat)
        val plateau = (abs(d5) <= PLATEAU_DELTA_ABS) && (abs(short) <= PLATEAU_DELTA_ABS) && (abs(long) <= PLATEAU_DELTA_ABS)
        if (plateau && mgdl >= HIGH_BG) {
            val baseKick = max(KICK_MIN_UPH, profileBasal * (1.0 + KICK_FRAC))
            val rate = clampAndQuantize(baseKick, profileBasal, maxBasal, step)
            val dur = max(minDur, KICK_MINUTES)
            return BasalPlan(
                rateUph = rate,
                durationMin = dur,
                reason = "High-flat kicker @${mgdl.toInt()}mg/dL (Δ≈0) → ${fmt2(rate)}U/h × ${dur}m"
            )
        }

        // 4) Anti-stall léger (Δ≈0 et pas franchement positif)
        val nearFlat = abs(d5) <= PLATEAU_DELTA_ABS && abs(short) <= PLATEAU_DELTA_ABS
        if (nearFlat && d5 < DELTA_POS_RELEASE) {
            val base = profileBasal * (1.0 + ANTI_STALL_FRAC)
            val rate = clampAndQuantize(base, profileBasal, maxBasal, step)
            return BasalPlan(
                rateUph = rate,
                durationMin = minDur,
                reason = "Anti-stall (Δ≈0) → ${fmt2(rate)}U/h × ${minDur}m"
            )
        }

        // Sinon : pas d'action prioritaire → laisser le moteur principal décider
        return null
    }

    // ===== Helpers =====
    private fun clampAndQuantize(desiredUph: Double, profileBasal: Double, maxBasal: Double, step: Double): Double {
        val limited = min(desiredUph, profileBasal * MAX_MULT).coerceAtMost(maxBasal)
        return quantize(limited, step)
    }

    private fun quantize(value: Double, step: Double): Double = round(value / step) * step

    private fun fmt1(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)
    private fun fmt2(x: Double): String = String.format(java.util.Locale.US, "%.2f", x)
}
