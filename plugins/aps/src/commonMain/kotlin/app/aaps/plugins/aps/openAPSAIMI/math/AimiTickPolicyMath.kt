package app.aaps.plugins.aps.openAPSAIMI.math

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.safety.CompressionReboundGuard
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure helpers lifted out of `DetermineBasalaimiSMB2`.
 *
 * [aimiMathRoundToLong] is Java `Math.round(double)`: a halfway value goes toward +∞,
 * so `-1.5` rounds to `-1`. `kotlin.math.round` sends that halfway to `-2`.
 */
internal object AimiTickPolicyMath {

    /**
     * OpenJDK `Math.round(double)` bit algorithm. Ties toward +∞. NaN is 0.
     * ±Infinity and values outside the long range saturate at the long extremes.
     */
    fun aimiMathRoundToLong(value: Double): Long {
        val longBits = value.toRawBits()
        val expBitMask = 0x7FF0000000000000L
        val signifBitMask = 0x000FFFFFFFFFFFFFL
        val significandWidth = 53
        val expBias = 1023
        val biasedExp = (longBits and expBitMask) shr (significandWidth - 1)
        val shift = (significandWidth - 2 + expBias).toLong() - biasedExp
        if ((shift and -64L) == 0L) {
            var magnitude = (longBits and signifBitMask) or (signifBitMask + 1L)
            if (longBits < 0L) magnitude = -magnitude
            return ((magnitude shr shift.toInt()) + 1L) shr 1
        }
        return value.toLong()
    }


    private const val TIGHT_SPIRAL_CAP_TDD_REFERENCE_U = 55.0

    /** Seuil énergie spiral (U) à TDD = [TIGHT_SPIRAL_CAP_TDD_REFERENCE_U] (échelle adulte repas). */
    private const val TIGHT_SPIRAL_SMB_CAP_ENERGY_LEGACY_U = 7.0

    /** Seuil IOB spiral (U) à TDD = [TIGHT_SPIRAL_CAP_TDD_REFERENCE_U] (IOB repas > ~8 U toléré). */
    private const val TIGHT_SPIRAL_SMB_CAP_IOB_LEGACY_U = 8.0

    /** Poids de référence (kg) : second terme IOB = [TIGHT_SPIRAL_SMB_CAP_IOB_LEGACY_U] U à 75 kg. */
    private const val TIGHT_SPIRAL_WEIGHT_REFERENCE_KG = 75.0

    private const val TIGHT_SPIRAL_CAP_ENERGY_MIN_U = 3.0
    private const val TIGHT_SPIRAL_CAP_ENERGY_MAX_U = 18.0
    private const val TIGHT_SPIRAL_CAP_IOB_MIN_U = 3.0
    private const val TIGHT_SPIRAL_CAP_IOB_MAX_U = 20.0

    fun tightSpiralSmbCapEnergyThresholdU(tdd24hU: Double): Double {
        if (!tdd24hU.isFinite() || tdd24hU <= 0.0) return TIGHT_SPIRAL_SMB_CAP_ENERGY_LEGACY_U
        val scaled = tdd24hU * (TIGHT_SPIRAL_SMB_CAP_ENERGY_LEGACY_U / TIGHT_SPIRAL_CAP_TDD_REFERENCE_U)
        return scaled.coerceIn(TIGHT_SPIRAL_CAP_ENERGY_MIN_U, TIGHT_SPIRAL_CAP_ENERGY_MAX_U)
    }

    fun tightSpiralSmbCapIobThresholdU(tdd24hU: Double, patientWeightKg: Double): Double {
        val fromTdd = if (tdd24hU.isFinite() && tdd24hU > 0.0) {
            tdd24hU * (TIGHT_SPIRAL_SMB_CAP_IOB_LEGACY_U / TIGHT_SPIRAL_CAP_TDD_REFERENCE_U)
        } else {
            TIGHT_SPIRAL_SMB_CAP_IOB_LEGACY_U
        }
        val fromWeight = if (patientWeightKg.isFinite() && patientWeightKg > 0.0) {
            patientWeightKg * (TIGHT_SPIRAL_SMB_CAP_IOB_LEGACY_U / TIGHT_SPIRAL_WEIGHT_REFERENCE_KG)
        } else {
            0.0
        }
        return max(fromTdd, fromWeight).coerceIn(TIGHT_SPIRAL_CAP_IOB_MIN_U, TIGHT_SPIRAL_CAP_IOB_MAX_U)
    }

    /** Mêmes seuils que la sortie « sharp rise » dans [InsulinStackingStance.evaluate] (évite divergence produit). */
    fun sharpRiseEligibleForTrajectorySpiralSoftCap(deltaValue: Float, shortAvgDeltaValue: Float): Boolean =
        deltaValue >= 4.5f || shortAvgDeltaValue >= 4.5f ||
            (deltaValue >= 3.2f && shortAvgDeltaValue >= 3.0f)

    /**
     * 🛡️ Sanitize strings before adding to consoleLog to prevent JSON deserialization crashes.
     * Escapes quotes, backslashes, and removes control characters that could break JSON parsing.
     *
     * Critical for backward compatibility with database records containing special characters.
     */
    fun sanitizeForJson(input: String): String {
        return input
            .replace("\\", "\\\\")     // Escape backslashes first!
            .replace("\"", "\\\"")     // Escape quotes
            .replace("\n", "\\n")      // Escape newlines
            .replace("\r", "\\r")      // Escape carriage returns
            .replace("\t", "\\t")      // Escape tabs
            .filter { it.code >= 32 || it in "\n\r\t" }  // Remove other control chars
    }

    /**
     * Prédit l’évolution de la glycémie sur un horizon donné (en minutes),
     * avec des pas de 5 minutes.
     *
     * @param currentBG La glycémie actuelle (mg/dL)
     * @param basalCandidate La dose basale candidate (en U/h)
     * @param horizonMinutes L’horizon de prédiction (ex. 30 minutes)
     * @param insulinSensitivity La sensibilité insulinique (mg/dL/U)
     * @return Une liste de glycémies prédites pour chaque pas de 5 minutes.
     */
    fun predictGlycemia(
        currentBG: Double,
        basalCandidateUph: Double,
        horizonMinutes: Int,
        insulinSensitivityMgdlPerU: Double,
        stepMinutes: Int = 5,
        minBgClamp: Double = 40.0,
        maxBgClamp: Double = 400.0,
        // ↓ nouveaux paramètres optionnels (par défaut 5h de DIA, pic à 75 min)
        diaMinutes: Int = 300,
        timeToPeakMinutes: Int = 75
    ): List<Double> {
        val predictions = ArrayList<Double>(maxOf(0, horizonMinutes / stepMinutes))
        if (horizonMinutes <= 0 || stepMinutes <= 0) return predictions

        var bg = currentBG
        val steps = horizonMinutes / stepMinutes
        val uPerStep = basalCandidateUph * (stepMinutes / 60.0)

        fun triangularActivity(tMin: Int, tp: Int, dia: Int): Double {
            if (tMin <= 0 || tMin >= dia) return 0.0
            val tpClamped = tp.coerceIn(1, dia - 1)
            val rise = if (tMin <= tpClamped) (2.0 / tpClamped) * tMin else 0.0
            val fall = if (tMin > tpClamped) 2.0 * (1.0 - (tMin - tpClamped).toDouble() / (dia - tpClamped)) else 0.0
            // Hauteur max = 2.0 → aire totale sur [0, DIA] ≈ DIA (même “dose” qu’activité = 1)
            return if (tMin <= tpClamped) rise else fall
        }

        repeat(steps) { k ->
            val tMin = (k + 1) * stepMinutes

            // activité réaliste (pic à tp, s’éteint à DIA)
            val activity = triangularActivity(tMin, timeToPeakMinutes, diaMinutes)

            // effet du pas courant (pas de convolution pour rester simple comme ton code)
            val delta = insulinSensitivityMgdlPerU * uPerStep * activity

            bg = (bg - delta).coerceIn(minBgClamp, maxBgClamp)
            predictions.add(bg)

            // early stop en hypo profonde
            if (bg <= minBgClamp) return predictions
        }
        return predictions
    }

    fun roundBasal(value: Double): Double {
        val safeValue = if (value < 0.0) 0.0 else value
        // Standard rounding to 2 decimals (OpenAPS style 0.00)
        return aimiMathRoundToLong(safeValue * 100.0) / 100.0
    }

    /**
     * Ajuste le DIA (en minutes) en fonction du niveau d'IOB.
     *
     * @param diaMinutes Le DIA courant (en minutes) après les autres ajustements.
     * @param currentIOB La quantité actuelle d'insuline active (U).
     * @param threshold Le seuil d'IOB à partir duquel on commence à augmenter le DIA (par défaut 7 U).
     * @return Le DIA ajusté en minutes tenant compte de l'impact de l'IOB.
     */
    fun adjustDIAForIOB(diaMinutes: Float, currentIOB: Float, threshold: Float = 2f): Float {
        // Si l'IOB est inférieur ou égal au seuil, pas d'ajustement.
        if (currentIOB <= threshold) return diaMinutes

        // Calculer l'excès d'IOB
        val excess = currentIOB - threshold
        // Pour chaque unité au-dessus du seuil, augmenter le DIA de 5 %.
        val multiplier = 1 + 0.05f * excess
        return diaMinutes * multiplier
    }

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        // Pass NaN AND ±Infinity through untouched. aimiMathRoundToLong saturates at Long.MAX_VALUE, so without
        // this an infinite value would come back as a normal looking ~9.2e18/scale and every isFinite()
        // guard downstream would be blind to it - the guards all read values that went through here.
        if (!value.isFinite()) return value
        val scale = 10.0.pow(digits.toDouble())
        return aimiMathRoundToLong(value * scale) / scale
    }

    // Helper for Post-Meal Basal Boost (AIMI 2.0)
    fun adjustBasalForMealHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        isMealModeActive: Boolean,
        minutesSinceMealStart: Int,
        mealMaxBasalUph: Double
    ): Double {
        val mealPhase = isMealModeActive && minutesSinceMealStart in 0..120
        if (!mealPhase) return suggestedBasalUph

        val risingOrFlat = delta >= 0.3 || shortAvgDelta >= 0.2
        val moderatelyHigh = bg > targetBg + 30.0
        val veryHigh = bg > targetBg + 90.0   // ex. cible 100 → 190+

        if (!risingOrFlat || !moderatelyHigh) return suggestedBasalUph

        val boostFactor = when {
            veryHigh -> 10    // ex : 250+ → +50 %
            else -> 8       // ex : 180–250 → +25 %
        }

        val boosted = suggestedBasalUph * boostFactor

        // Plafond sécurisé : on ne dépasse pas mealMaxBasalUph
        return if (boosted > mealMaxBasalUph) mealMaxBasalUph else boosted
    }

    fun calculateBasalRate(basal: Double, currentBasal: Double, multiplier: Double): Double {
        val raw = if (basal == 0.0) currentBasal * multiplier else roundBasal(basal * multiplier)
        return raw.coerceAtLeast(0.0)
    }

    fun calculateDynamicMicroBolus(
        isf: Double,
        baseFactor: Double = 20.0,
        reason: StringBuilder
    ): Double {
        // Formula: MicroBolus = 20 / ISF
        // Example: ISF 50 -> 0.4U. ISF 100 -> 0.2U.
        // Safety: ISF is rarely < 10 or > 500.
        // Cap max bolus to 0.5U for safety by default (unless baseFactor changes)
        if (isf <= 0) return 0.0 // Should not happen

        var bolus = baseFactor / isf

        // Safety Caps
        bolus = bolus.coerceIn(0.05, 0.5)

        return bolus
    }

    fun isCompressionProtectionCondition(
        delta: Float,
        reason: StringBuilder
    ): Boolean {
        if (CompressionReboundGuard.isImpossibleRise(delta)) {
            reason.append(CompressionReboundGuard.reasonLine())
            return true
        }
        return false
    }

    /**
     * Retourne vrai si le contexte ressemble à un repas SANS déclaration explicite
     * (cas Autodrive V3 où l'utilisateur ne déclare pas de repas manuellement).
     * Requiert ≥ 2 critères parmi 4 pour éviter les faux positifs.
     */
    fun isMealLikelyWithoutDeclaration(
        shortAvgDelta: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        recentBGs: List<Float>,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        localHour: Int
    ): Boolean {
        var score = 0

        // Critère 1 : AIMI Advisor a estimé des glucides récemment (≤ 90 min)
        if (estimatedCarbs > 20.0 && estimatedCarbsAgeMs in 0..(90 * 60_000L)) score++

        // Critère 2 : signal UAM post-prandial (courbe S)
        if (slopeFromMinDeviation >= 2.0) score++

        // Critère 3 : accélération glycémique soutenue sur 2 lectures consécutives
        val sustainedRise = shortAvgDelta >= 3.0f && delta >= 2.0f &&
            recentBGs.take(3).zipWithNext().all { (prev, curr) -> curr > prev }
        if (sustainedRise) score++

        // Critère 4 : heure diurne (les rebonds hormonaux nuit sont plus probables)
        if (localHour in 7..21) score++

        return score >= 2
    }

    // --- Helpers "fenêtre repas 30 min" ---
    /**
     * Runtime repas pour le gros `determine_basal` : **nullable**, millisecondes si >600_000, sinon secondes si >180, sinon minutes.
     * Ne pas confondre avec [runtimeToMinutes] (`Long` non null, heuristique >180 = secondes) utilisé ailleurs sur l’instance.
     */
    fun mealModeRuntimeToNullableMinutes(rt: Long?): Int {
        if (rt == null) return Int.MAX_VALUE
        if (rt > 600_000L) return (rt / 60_000L).toInt()
        if (rt > 180L) return (rt / 60L).toInt()
        return rt.toInt()
    }

    fun runtimeToMinutes(rt: Long): Int {
        return if (rt > 180) { // heuristique : si >180, on suppose secondes
            (rt / 60).toInt()
        } else {
            rt.toInt()
        }
    }

    // Hystérèse : on ne débloque qu’après avoir été > (seuil+margin) pendant X minutes
    fun canFallbackSmbWithoutPrediction(
        bg: Double,
        delta: Double,
        targetBg: Double,
        iob: Double,
        profile: OapsProfileAimi
    ): Boolean {
        // Fallback SMB allowed if clearly high and rising, even if prediction is missing
        val clearlyHigh = bg > targetBg + 30.0
        val stronglyRising = delta >= 2.0 // mg/dl/5min
        // Ensure IOB is not already saturating safety
        val iobSafe = iob < profile.max_iob * 0.8

        return clearlyHigh && stronglyRising && iobSafe
    }

    fun computeDynamicBolusMultiplier(delta: Float): Float {
        // Centrer la sigmoïde autour de 5 mg/dL, avec une pente modérée (échelle 10)
        val x = (delta - 5f) / 10f
        val sig = (1f / (1f + exp(-x)))  // sigmoïde entre 0 et 1
        return 0.5f + sig * 0.7f  // multipliateur lissé entre 0,5 et 1,2
    }

    // Calcul d'un delta prédit à partir d'une moyenne pondérée
    fun predictedDelta(deltaHistory: List<Double>): Double {
        if (deltaHistory.isEmpty()) return 0.0
        // Par exemple, on peut utiliser une moyenne pondérée avec des poids croissants pour donner plus d'importance aux valeurs récentes
        val weights = (1..deltaHistory.size).map { it.toDouble() }
        val weightedSum = deltaHistory.zip(weights).sumOf { it.first * it.second }
        return weightedSum / weights.sum()
    }

    /** When EGP soft-floor applied, lift floor-band points so path-min / graphs match production. */
    fun applySoftFloorToPredSeries(
        series: List<Int>,
        telemetry: PkpdSoftFloorTelemetry,
    ): List<Int> {
        val soft = telemetry.softPathMinMgdl ?: return series
        if (!telemetry.applied) return series
        return PkpdSoftFloorPathMin.liftFloorBandPoints(series, soft)
    }

    fun processNotesAndCleanUp(notes: String): String {
        return notes.lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace("!", " ")
            //.replace("a", " ")
            .replace("an", " ")
            .replace("and", " ")
            .replace("\\s+", " ")
    }

    fun inferFinalLoopDecisionFromResult(result: RT): String {
        val units = result.units ?: 0.0
        val duration = result.duration ?: 0
        val rate = result.rate ?: 0.0
        return when {
            units > 0.0 -> "smb"
            duration > 0 && rate <= 0.0 -> "suspend"
            duration > 0 && rate > 0.0 -> "tbr_up"
            else -> "none"
        }
    }

    /**
     * Applies a safety floor to the basal rate to prevent unnecessary cutoffs (0 U/h)
     * during "cruise mode" or moderate activity, unless critical safety conditions are met.
     */
    fun applyBasalFloor(
        suggestedRate: Double,
        profileBasal: Double,
        safetyDecision: SafetyDecision,
        activityContext: app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        predictedBg: Double,
        isMealActive: Boolean,
        lgsThreshold: Double
    ): Double {
        // 1. Critical Safety: Hypo REELLE seulement permet 0 U/h
        if (safetyDecision.stopBasal || bg < lgsThreshold) {
            return suggestedRate // Allow 0.0 pour hypo réelle
        }

        // 2. ⚡ Prediction basse MAIS montée → ne pas bypasser le floor
        if (predictedBg < 65) {
            if (delta > 0 && bg > 90) {
                // Prédiction pessimiste, BG monte → appliquer floor quand même
                // Note: logging handled at caller level
            } else {
                return suggestedRate // Allow 0.0 si vraiment en baisse
            }
        }

        // 3. ⚡ Mode Repas Actif : Floor plus élevé (60% profil)
        if (isMealActive && suggestedRate < profileBasal * 0.6) {
            val mealFloor = profileBasal * 0.6
            if (bg > 90 && delta > -1) {
                return mealFloor
            }
        }

        // 4. Activity Context
        val isActivity = activityContext.state != app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState.REST
        if (isActivity) {
            // If dropping fast during activity, allow low basal/zero
            if (delta < -3 || bg < 90) {
                return suggestedRate
            }
            // Recovery: If rising/stable during activity, avoid ZERO.
            val activityFloor = profileBasal * 0.3  // 30% floor en activité
            if (suggestedRate < activityFloor) {
                // If rising, push higher
                if (delta > 0) {
                    val risingFloor = profileBasal * 0.6  // 60% si montée
                    return risingFloor
                }
                return activityFloor
            }
            return suggestedRate
        }

        // 5. Persistent Rise (Standard Mode Boost)
        // Si ça monte de façon persistante (AvgDelta > 0.5) et Delta > 0, on ne laisse pas chuter en dessous de 80%
        if (delta > 0 && shortAvgDelta > 0.5 && bg > 100) {
             val persistentFloor = profileBasal * 0.8
             if (suggestedRate < persistentFloor) {
                 return persistentFloor
             }
        }

        // 6. Cruise Mode (No Activity, No Critical Low)
        val cruiseFloor = profileBasal * 0.55 // 55% floor (augmenté de 45%)
        if (suggestedRate < cruiseFloor) {
            // Only enforce floor if strictly safe
            if (bg > 100 && delta > -2 && predictedBg > 80) {
                return cruiseFloor
            }
        }

        return suggestedRate
    }

    // Helper for General Hyper Kicker (Non-Meal) (AIMI 2.0)
    fun adjustBasalForGeneralHyper(
        suggestedBasalUph: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        maxBasalConfig: Double,
        maxScaleCap: Double = 10.0,
    ): Double {
        // "Progressivement rapidement" logic requested by user

        // Risque montée franche ou plateau haut persistant
        val rising = delta >= 0.5 || shortAvgDelta >= 0.3
        val plateauHigh = delta >= -0.1 && bg > targetBg + 50
        val rocketStart = delta > 10.0 &&
            (bg > targetBg + 20.0 || maxScaleCap >= 8.0)

        if (!rising && !plateauHigh && !rocketStart) return suggestedBasalUph

        val deviation = bg - targetBg

        // Progressive scaling based on deviation severity
        // 30mg au dessus: x2
        // 60mg au dessus: x5
        // 90mg au dessus: x8
        // 120mg+        : x10 (Authorized by user)
        // Rocket Start : Auto Max (x10) if delta > 10.0 and not post-hypo rebound guard

        var scaleFactor = when {
            rocketStart || deviation >= 120 -> 10.0
            deviation >= 90  -> 8.0
            deviation >= 60  -> 5.0
            deviation >= 30  -> 2.0
            else -> 1.0
        }
        scaleFactor = min(scaleFactor, maxScaleCap)

        if (scaleFactor == 1.0) return suggestedBasalUph

        val boosted = suggestedBasalUph * scaleFactor

        // Cap only by absolute max config (safety)
        return if (boosted > maxBasalConfig) maxBasalConfig else boosted
    }

}
