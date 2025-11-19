package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import dagger.Reusable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale
import android.content.Context
import app.aaps.plugins.aps.R

@Reusable
class AIMIAdaptiveBasal @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger,
    private val fmt: DecimalFormatter
) {

    data class Input(
        val bg: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val accel: Double,
        val r2: Double,
        val parabolaMin: Double,
        val combinedDelta: Double,
        val profileBasal: Double,
        val lastTempIsZero: Boolean,
        val zeroSinceMin: Int,
        val minutesSinceLastChange: Int
    )

    data class Decision(
        val rateUph: Double?,
        val durationMin: Int,
        val reason: String
    )

    private data class PlateauSettings(
        val highBg: Double,
        val plateauBand: Double,
        val r2Conf: Double,
        val maxMultiplier: Double,
        val kickerStep: Double,
        val kickerMinUph: Double,
        val kickerStartMin: Int,
        val kickerMaxMin: Int,
        val zeroResumeMin: Int,
        val zeroResumeRateFrac: Double,
        val zeroResumeMax: Int,
        val antiStallBias: Double,
        val deltaPosRelease: Double
    )

    private object Defaults {
        const val HIGH_BG = 180.0
        const val PLATEAU_DELTA_ABS = 2.5
        const val R2_CONFIDENT = 0.7
        const val MAX_MULTIPLIER = 1.6
        const val KICKER_MIN = 0.2
        const val KICKER_STEP = 0.15
        const val KICKER_START_MIN = 10
        const val KICKER_MAX_MIN = 30
        const val ZERO_MICRO_RESUME_MIN = 10
        const val ZERO_MICRO_RESUME_RATE = 0.25
        const val ZERO_MICRO_RESUME_MAX = 30
        const val ANTI_STALL_BIAS = 0.10
        const val DELTA_POS_FOR_RELEASE = 1.0
    }

    fun suggest(input: Input): Decision {
        val settings = buildSettings(input)
        val highBg = settings.highBg
        val plateauBand = settings.plateauBand
        val r2Conf = settings.r2Conf
        val maxMult = settings.maxMultiplier
        val kickerStep = settings.kickerStep
        val kickerMinUph = settings.kickerMinUph
        val kickerStartMin = settings.kickerStartMin
        val kickerMaxMin = settings.kickerMaxMin
        val zeroResumeMin = settings.zeroResumeMin
        val zeroResumeRateFrac = settings.zeroResumeRateFrac
        val zeroResumeMax = settings.zeroResumeMax
        val antiStallBias = settings.antiStallBias
        val deltaPosRelease = settings.deltaPosRelease

       //if (input.profileBasal <= 0.0) return Decision(null, 0, "profile basal = 0")
        if (input.profileBasal <= 0.0) return Decision(null, 0, context.getString(R.string.aimi_profile_basal_zero))

        if (input.lastTempIsZero && input.zeroSinceMin >= zeroResumeMin) {
            val rate = max(kickerMinUph, input.profileBasal * zeroResumeRateFrac)
            val dur = min(zeroResumeMax, max(10, input.minutesSinceLastChange / 2))
          //val r = "micro-resume after ${input.zeroSinceMin}m @0U/h → ${fmt.to2Decimal(rate)}U/h × ${dur}m"
            val r = context.getString(R.string.aimi_micro_resume,input.zeroSinceMin,fmt.to2Decimal(rate),dur)
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(rate, dur, r)
        }

        val plateau = abs(input.delta) <= plateauBand && abs(input.shortAvgDelta) <= plateauBand
        val highAndFlat = input.bg > highBg && plateau

        if (highAndFlat) {
            val conf = min(1.0, max(0.0, (input.r2 - 0.3) / (r2Conf - 0.3)))
            val accelBrake = if (input.accel < 0) 0.6 else 1.0
            val mult = 1.0 + kickerStep * conf * accelBrake * (1.0 + min(1.0, input.parabolaMin / 15.0))
            val target = min(input.profileBasal * maxMult, max(kickerMinUph, input.profileBasal * mult))
            val dur = when {
                input.minutesSinceLastChange < 5  -> kickerStartMin
                input.minutesSinceLastChange < 15 -> (kickerStartMin + 10)
                else                              -> kickerMaxMin
            }
          //val r = "plateau kicker (BG=${fmt.to0Decimal(input.bg)}, Δ≈0, R2=${fmt.to2Decimal(input.r2)}) → ${fmt.to2Decimal(target)}U/h × ${dur}m"
            val r = context.getString(R.string.aimi_plateau_kicker,fmt.to0Decimal(input.bg),fmt.to2Decimal(input.r2),fmt.to2Decimal(target),dur)
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(target, dur, r)
        }

        val glued = input.r2 >= r2Conf && abs(input.delta) <= plateauBand && abs(input.longAvgDelta) <= plateauBand
        if (glued && input.bg > highBg && input.delta < deltaPosRelease) {
            val rate = min(input.profileBasal * (1.0 + antiStallBias), input.profileBasal * maxMult)
            val dur = 10
          //val r = "anti-stall bias (+${(antiStallBias*100).toInt()}%) because R2=${fmt.to2Decimal(input.r2)} & Δ≈0"
            val r = context.getString(R.string.aimi_anti_stall_bias,(antiStallBias * 100).toInt(),fmt.to2Decimal(input.r2))
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(rate, dur, r)
        }

        //return Decision(null, 0, "no AIMI+ action")
        return Decision(null, 0, context.getString(R.string.aimi_no_action))
    }

    // helpers
    companion object {
        /**
         * Version statique “sans DI” : aucune dépendance à Preferences/Logger/Formatter.
         * Utilise uniquement les Defaults. Utile pour tests ou appels outils.
         */
        @JvmStatic
        fun pureSuggest(context: Context,input: Input): Decision {
            val settings = buildSettings(input)
          //if (input.profileBasal <= 0.0) return Decision(null, 0, "profile basal = 0")
            if (input.profileBasal <= 0.0) return Decision(null, 0, context.getString(R.string.aimi_profile_basal_zero))

            fun d0(v: Double) = String.format(Locale.US, "%.0f", v)
            fun d2(v: Double) = String.format(Locale.US, "%.2f", v)

            if (input.lastTempIsZero && input.zeroSinceMin >= settings.zeroResumeMin) {
                val rate = max(settings.kickerMinUph, input.profileBasal * settings.zeroResumeRateFrac)
                val dur = min(settings.zeroResumeMax, max(10, input.minutesSinceLastChange / 2))
              //val r = "micro-resume after ${input.zeroSinceMin}m @0U/h → ${d2(rate)}U/h × ${dur}m"
                val r = context.getString(R.string.aimi_micro_resume, input.zeroSinceMin, d2(rate), dur)
                return Decision(rate, dur, r)
            }

            val plateau = abs(input.delta) <= settings.plateauBand &&
                abs(input.shortAvgDelta) <= settings.plateauBand
            val highAndFlat = input.bg > settings.highBg && plateau

            if (highAndFlat) {
                val conf = min(1.0, max(0.0, (input.r2 - 0.3) / (settings.r2Conf - 0.3)))
                val accelBrake = if (input.accel < 0) 0.6 else 1.0
                val mult = 1.0 + settings.kickerStep * conf * accelBrake *
                    (1.0 + min(1.0, input.parabolaMin / 15.0))
                val target = min(
                    input.profileBasal * settings.maxMultiplier,
                    max(settings.kickerMinUph, input.profileBasal * mult)
                )
                val dur = when {
                    input.minutesSinceLastChange < 5  -> settings.kickerStartMin
                    input.minutesSinceLastChange < 15 -> (settings.kickerStartMin + 10)
                    else                              -> settings.kickerMaxMin
                }
              //val r = "plateau kicker (BG=${d0(input.bg)}, Δ≈0, R2=${d2(input.r2)}) → ${d2(target)}U/h × ${dur}m"
                val r = context.getString(R.string.aimi_plateau_kicker, d0(input.bg), d2(input.r2), d2(target), dur)
                return Decision(target, dur, r)
            }

            val glued = input.r2 >= settings.r2Conf &&
                abs(input.delta) <= settings.plateauBand &&
                abs(input.longAvgDelta) <= settings.plateauBand
            if (glued && input.bg > settings.highBg && input.delta < settings.deltaPosRelease) {
                val rate = min(
                    input.profileBasal * (1.0 + settings.antiStallBias),
                    input.profileBasal * settings.maxMultiplier
                )
                val dur = 10
              //val r = "anti-stall bias (+${(settings.antiStallBias*100).toInt()}%) because R2=${d2(input.r2)} & Δ≈0"
                val r = context.getString(R.string.aimi_anti_stall_bias, (settings.antiStallBias*100).toInt(), d2(input.r2))
                return Decision(rate, dur, r)
            }

          //return Decision(null, 0, "no AIMI+ action")
            return Decision(null, 0, context.getString(R.string.aimi_no_action))
        }

        private fun buildSettings(input: Input): PlateauSettings {
            val highBg = when {
                input.profileBasal < 0.4 -> 150.0
                input.profileBasal < 0.8 -> 165.0
                else -> Defaults.HIGH_BG
            }
            val plateauBand = (
                Defaults.PLATEAU_DELTA_ABS +
                    max(0.0, 1.2 - abs(input.delta)) * 0.2 +
                    max(0.0, -input.accel) * 0.25
                ).coerceIn(1.5, 3.5)
            val r2Conf = (
                Defaults.R2_CONFIDENT -
                    min(0.15, abs(input.combinedDelta) / 40.0)
                ).coerceIn(0.55, 0.8)
            val maxMult = (
                Defaults.MAX_MULTIPLIER +
                    min(0.15, input.parabolaMin / 80.0)
                ).coerceIn(1.35, 1.8)
            val kickerStep = (
                Defaults.KICKER_STEP +
                    min(0.1, input.parabolaMin / 90.0)
                ).coerceIn(0.1, 0.3)
            val kickerMinUph = max(Defaults.KICKER_MIN, input.profileBasal * 0.35)
            val kickerStartMin = max(Defaults.KICKER_START_MIN, min(25, input.minutesSinceLastChange / 2 + 5))
            val kickerMaxMin = max(kickerStartMin + 10, Defaults.KICKER_MAX_MIN)
            val zeroResumeMin = max(Defaults.ZERO_MICRO_RESUME_MIN - input.zeroSinceMin / 8, 5)
            val zeroResumeRate = (
                Defaults.ZERO_MICRO_RESUME_RATE + input.profileBasal * 0.05
                ).coerceIn(0.15, 0.35)
            val zeroResumeMax = Defaults.ZERO_MICRO_RESUME_MAX
            val antiStallBias = (
                Defaults.ANTI_STALL_BIAS + max(0.0, -input.accel) * 0.05
                ).coerceIn(0.1, 0.2)
            val deltaPosRelease = (
                Defaults.DELTA_POS_FOR_RELEASE + max(-0.3, input.delta / 15.0)
                ).coerceIn(0.5, 1.5)
            return PlateauSettings(
                highBg = highBg,
                plateauBand = plateauBand,
                r2Conf = r2Conf,
                maxMultiplier = maxMult,
                kickerStep = kickerStep,
                kickerMinUph = kickerMinUph,
                kickerStartMin = kickerStartMin,
                kickerMaxMin = kickerMaxMin,
                zeroResumeMin = zeroResumeMin,
                zeroResumeRateFrac = zeroResumeRate,
                zeroResumeMax = zeroResumeMax,
                antiStallBias = antiStallBias,
                deltaPosRelease = deltaPosRelease
            )
        }
    }

}
