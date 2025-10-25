package app.aaps.plugins.aps.openAPSAIMI


import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.Preferences
import javax.inject.Inject
import dagger.Reusable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AIMIAdaptiveBasal
 * - "Kicker plateau": si BG haut & pente ~0, on pousse une basale courte et raisonnable.
 * - "Micro-resume": si basale à 0 depuis longtemps (plate), on redémarre petit à petit.
 * - "Anti-stall": si R² élevé mais dérivée ≈ 0 (courbe collée), on injecte un petit biais.
 * - Décroissance/relance en fonction de la "confiance" (R²) et de l'accélération.
 */
@Reusable
class AIMIAdaptiveBasal @Inject constructor(
    private val prefs: Preferences,
    private val log: AAPSLogger,
    private val fmt: DecimalFormatter
) {

    data class Input(
        val bg: Double,                 // mg/dL
        val delta: Double,              // mg/dL/5m
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val accel: Double,              // mg/dL/min^2 (AIMI)
        val r2: Double,                 // corrélation du fit
        val parabolaMin: Double,        // minutes couvertes par le fit
        val combinedDelta: Double,      // delta pondéré AIMI
        val profileBasal: Double,       // U/h (basal du profil à l'instant)
        val lastTempIsZero: Boolean,    // vrai si temp basal = 0 actuellement
        val zeroSinceMin: Int,          // durée (minutes) depuis laquelle on est à 0 si applicable
        val minutesSinceLastChange: Int // âge de la TBR active (minutes)
    )

    data class Decision(
        val rateUph: Double?,   // null -> pas de changement
        val durationMin: Int,   // durée suggérée si rateUph != null
        val reason: String
    )

    private object Defaults {
        // Seuils "sécurité/plateau"
        const val HIGH_BG = 180.0
        const val PLATEAU_DELTA_ABS = 2.5      // |Δ| <= 2.5 mg/dL/5m => plateau
        const val R2_CONFIDENT = 0.7
        const val MAX_MULTIPLIER = 1.6         // plafond U/h = 1.6× basal profil
        const val KICKER_MIN = 0.2             // plancher absolu U/h pour les kicks très bas
        const val KICKER_STEP = 0.15           // incrément multiplicatif par palier
        const val KICKER_START_MIN = 10        // première impulsion courte
        const val KICKER_MAX_MIN = 30          // durée max d’une impulsion
        const val ZERO_MICRO_RESUME_MIN = 10   // micro reprise après 10 min à 0
        const val ZERO_MICRO_RESUME_RATE = 0.25 // 25% du basal profil
        const val ZERO_MICRO_RESUME_MAX = 30   // n'allonge pas trop la 1ère reprise
        const val ANTI_STALL_BIAS = 0.10       // +10% si "collé"
        const val DELTA_POS_FOR_RELEASE = 1.0  // si Δ > 1 → on évite d’accentuer encore
    }

    /**
     * Calcule une suggestion basale "intelligente" additionnelle — à combiner
     * avec la stratégie principale (determine_basal).
     */
    fun suggest(input: Input): Decision {

        // --- 0) Charger les prefs avec fallback sur Defaults ---
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
        if (input.profileBasal <= 0.0) return Decision(null, 0, "profile basal = 0")

        // 1) Micro-resume après longue coupure à 0 (sécurité + confort)
        if (input.lastTempIsZero && input.zeroSinceMin >= zeroResumeMin) {
            val rate = max(kickerMinUph, input.profileBasal * zeroResumeRateFrac)
            val dur = min(zeroResumeMax, max(10, input.minutesSinceLastChange / 2))
            val r = "micro-resume after ${input.zeroSinceMin}m @0U/h → ${fmt.to2Decimal(rate)}U/h × ${dur}m"
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(rateUph = rate, durationMin = dur, reason = r)
        }

        val plateau = abs(input.delta) <= plateauBand && abs(input.shortAvgDelta) <= plateauBand
        val highAndFlat = input.bg > highBg && plateau

        // 2) Kicker plateau (BG haut + pente ~0)
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
            val r = "plateau kicker (BG=${fmt.to0Decimal(input.bg)}, Δ≈0, R2=${fmt.to2Decimal(input.r2)}) → ${fmt.to2Decimal(target)}U/h × ${dur}m"
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(rateUph = target, durationMin = dur, reason = r)
        }

        // 3) Anti-stall (collé) si forte confiance
        val glued = input.r2 >= r2Conf && abs(input.delta) <= plateauBand && abs(input.longAvgDelta) <= plateauBand
        if (glued && input.bg > highBg && input.delta < deltaPosRelease) {
            val rate = min(input.profileBasal * (1.0 + antiStallBias), input.profileBasal * maxMult)
            val dur = 10
            val r = "anti-stall bias (+${(antiStallBias*100).toInt()}%) because R2=${fmt.to2Decimal(input.r2)} & Δ≈0"
            log.debug(LTag.APS, "AIMI+ $r")
            return Decision(rateUph = rate, durationMin = dur, reason = r)
        }

        return Decision(null, 0, "no AIMI+ action")
    }


    // --- helpers prefs avec défauts ---
    private fun Preferences.getOr(key: DoubleKey, default: Double) =
        runCatching { this.get(key) }.getOrNull() ?: default
    private fun Preferences.getOr(key: IntKey, default: Int) =
        runCatching { this.get(key) }.getOrNull() ?: default
    private fun Preferences.getOr(key: BooleanKey, default: Boolean) =
        runCatching { this.get(key) }.getOrNull() ?: default
}
