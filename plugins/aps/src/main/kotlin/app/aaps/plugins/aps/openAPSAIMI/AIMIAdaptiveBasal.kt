package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.BooleanKey
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
    private val prefs: Preferences,
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
        val highBg = prefs.getOr(DoubleKey.OApsAIMIHighBg, Defaults.HIGH_BG)
        val plateauBand = prefs.getOr(DoubleKey.OApsAIMIPlateauBandAbs, Defaults.PLATEAU_DELTA_ABS)
        val r2Conf = prefs.getOr(DoubleKey.OApsAIMIR2Confident, Defaults.R2_CONFIDENT)
        val maxMult = prefs.getOr(DoubleKey.OApsAIMIMaxMultiplier, Defaults.MAX_MULTIPLIER)
        val kickerStep = prefs.getOr(DoubleKey.OApsAIMIKickerStep, Defaults.KICKER_STEP)
        val kickerMinUph = prefs.getOr(DoubleKey.OApsAIMIKickerMinUph, Defaults.KICKER_MIN)
        val kickerStartMin = prefs.getOr(IntKey.OApsAIMIKickerStartMin, Defaults.KICKER_START_MIN)
        val kickerMaxMin = prefs.getOr(IntKey.OApsAIMIKickerMaxMin, Defaults.KICKER_MAX_MIN)
        val zeroResumeMin = prefs.getOr(IntKey.OApsAIMIZeroResumeMin, Defaults.ZERO_MICRO_RESUME_MIN)
        val zeroResumeRateFrac = prefs.getOr(DoubleKey.OApsAIMIZeroResumeFrac, Defaults.ZERO_MICRO_RESUME_RATE)
        val zeroResumeMax = prefs.getOr(IntKey.OApsAIMIZeroResumeMax, Defaults.ZERO_MICRO_RESUME_MAX)
        val antiStallBias = prefs.getOr(DoubleKey.OApsAIMIAntiStallBias, Defaults.ANTI_STALL_BIAS)
        val deltaPosRelease = prefs.getOr(DoubleKey.OApsAIMIDeltaPosRelease, Defaults.DELTA_POS_FOR_RELEASE)

        // 0) garde-fous
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
    private fun Preferences.getOr(key: DoubleKey, default: Double) =
        runCatching { this.get(key) }.getOrNull() ?: default
    private fun Preferences.getOr(key: IntKey, default: Int) =
        runCatching { this.get(key) }.getOrNull() ?: default
    private fun Preferences.getOr(key: BooleanKey, default: Boolean) =
        runCatching { this.get(key) }.getOrNull() ?: default

    companion object {
        /**
         * Version statique “sans DI” : aucune dépendance à Preferences/Logger/Formatter.
         * Utilise uniquement les Defaults. Utile pour tests ou appels outils.
         */
        @JvmStatic
        fun pureSuggest(input: Input): Decision {
            if (input.profileBasal <= 0.0) return Decision(null, 0, "profile basal = 0")

            fun d0(v: Double) = String.format(Locale.US, "%.0f", v)
            fun d2(v: Double) = String.format(Locale.US, "%.2f", v)

            if (input.lastTempIsZero && input.zeroSinceMin >= Defaults.ZERO_MICRO_RESUME_MIN) {
                val rate = max(Defaults.KICKER_MIN, input.profileBasal * Defaults.ZERO_MICRO_RESUME_RATE)
                val dur = min(Defaults.ZERO_MICRO_RESUME_MAX, max(10, input.minutesSinceLastChange / 2))
                val r = "micro-resume after ${input.zeroSinceMin}m @0U/h → ${d2(rate)}U/h × ${dur}m"
                return Decision(rate, dur, r)
            }

            val plateau = abs(input.delta) <= Defaults.PLATEAU_DELTA_ABS &&
                abs(input.shortAvgDelta) <= Defaults.PLATEAU_DELTA_ABS
            val highAndFlat = input.bg > Defaults.HIGH_BG && plateau

            if (highAndFlat) {
                val r2Conf = Defaults.R2_CONFIDENT
                val conf = min(1.0, max(0.0, (input.r2 - 0.3) / (r2Conf - 0.3)))
                val accelBrake = if (input.accel < 0) 0.6 else 1.0
                val mult = 1.0 + Defaults.KICKER_STEP * conf * accelBrake *
                    (1.0 + min(1.0, input.parabolaMin / 15.0))
                val target = min(
                    input.profileBasal * Defaults.MAX_MULTIPLIER,
                    max(Defaults.KICKER_MIN, input.profileBasal * mult)
                )
                val dur = when {
                    input.minutesSinceLastChange < 5  -> Defaults.KICKER_START_MIN
                    input.minutesSinceLastChange < 15 -> (Defaults.KICKER_START_MIN + 10)
                    else                              -> Defaults.KICKER_MAX_MIN
                }
                val r = "plateau kicker (BG=${d0(input.bg)}, Δ≈0, R2=${d2(input.r2)}) → ${d2(target)}U/h × ${dur}m"
                return Decision(target, dur, r)
            }

            val glued = input.r2 >= Defaults.R2_CONFIDENT &&
                abs(input.delta) <= Defaults.PLATEAU_DELTA_ABS &&
                abs(input.longAvgDelta) <= Defaults.PLATEAU_DELTA_ABS
            if (glued && input.bg > Defaults.HIGH_BG && input.delta < Defaults.DELTA_POS_FOR_RELEASE) {
                val rate = min(input.profileBasal * (1.0 + Defaults.ANTI_STALL_BIAS),
                               input.profileBasal * Defaults.MAX_MULTIPLIER)
                val dur = 10
                val r = "anti-stall bias (+${(Defaults.ANTI_STALL_BIAS*100).toInt()}%) because R2=${d2(input.r2)} & Δ≈0"
                return Decision(rate, dur, r)
            }

            return Decision(null, 0, "no AIMI+ action")
        }
    }

}
