package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ============================================================================
 * LOCAL SENTINEL SCORER (Offline, Gratuit, Toujours Actif)
 * ============================================================================
 * 
 * "Premier filtre" du système Dual-Brain Auditor
 * 
 * Rôle:
 * - Détecte incertitudes, contradictions, stacking, drift
 * - Calcule score 0-100 et tier (NONE/LOW/MEDIUM/HIGH)
 * - Produit recommandation soft (CONFIRM, REDUCE_SMB, INCREASE_INTERVAL, PREFER_BASAL)
 * - Déclenche External Auditor uniquement si tier >= HIGH (ou MEDIUM en mode aggressive)
 * 
 * Principe: "Injecter → Observer → Corriger" au lieu de "Corriger à chaque tick"
 */
object LocalSentinel {
    
    /**
     * Sentinel Tier (niveau de préoccupation)
     */
    enum class Tier {
        NONE,      // Tout va bien, pas de préoccupation
        LOW,       // Attention légère (monitoring)
        MEDIUM,    // Préoccupation modérée (soft intervention locale)
        HIGH       // Préoccupation élevée (appel External Auditor si dispo)
    }
    
    /**
     * Sentinel Recommendation
     */
    enum class Recommendation {
        CONFIRM,             // Garder décision AIMI as-is
        REDUCE_SMB,          // Réduire SMB (factor < 1.0)
        INCREASE_INTERVAL,   // Augmenter intervalle SMB
        PREFER_BASAL,        // Préférer basal au lieu de SMB
        HOLD_SOFT            // Pause soft (factor 0.5-0.7 + interval)
    }
    
    /**
     * Sentinel Advice (Résultat)
     */
    data class SentinelAdvice(
        val score: Int,                    // 0-100 (100 = préoccupation maximale)
        val tier: Tier,                    // Niveau de préoccupation
        val reason: String,                // Raison principale (ex: "STACKING_RISK")
        val recommendation: Recommendation, // Recommandation
        val smbFactor: Double,             // 0.0-1.0 (multiplicateur SMB)
        val extraIntervalMin: Int,         // 0-20 min (ajout à l'intervalle)
        val preferBasal: Boolean,          // Préférer basal vs SMB
        val details: List<String>          // Détails pour logs
    )
    
    /**
     * Calcule Sentinel Advice
     * 
     * @param bg BG actuel (mg/dL)
     * @param target Target BG (mg/dL)
     * @param delta Delta 5min (mg/dL/5min)
     * @param shortAvgDelta Average delta court terme (mg/dL/5min)
     * @param longAvgDelta Average delta long terme (mg/dL/5min)
     * @param predictedBg Predicted BG (mg/dL, peut être null)
     * @param eventualBg Eventual BG (mg/dL, peut être null)
     * @param predBgsAvailable Prédictions disponibles ?
     * @param iobTotal IOB total (U)
     * @param iobActivity IOB activity (0.0-1.0, peut être null)
     * @param pkpdStage PKPD stage (PRE_ONSET, RISING, PEAK, TAIL, EXHAUSTED, peut être null)
     * @param lastBolusAgeMin Age dernier bolus (min)
     * @param smbCount30min Nombre SMB 30 dernières min
     * @param smbTotal60min Total SMB 60 dernières min (U)
     * @param smbProposed SMB proposé par AIMI (U)
     * @param noise Noise level (0-3)
     * @param isStale Data stale ?
     * @param pumpUnreachable Pump unreachable ?
     * @param autodriveActive Autodrive actif ?
     * @param modeActive Mode repas actif ?
     * @param bgHistory Historique BG dernières 30-60min (peut être null)
     * @return SentinelAdvice
     */
    fun computeAdvice(
        bg: Double,
        target: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        predictedBg: Double?,
        eventualBg: Double?,
        predBgsAvailable: Boolean,
        iobTotal: Double,
        iobActivity: Double?,
        pkpdStage: String?,
        lastBolusAgeMin: Double,
        smbCount30min: Int,
        smbTotal60min: Double,
        smbProposed: Double,
        noise: Int,
        isStale: Boolean,
        pumpUnreachable: Boolean,
        autodriveActive: Boolean,
        modeActive: Boolean,
        bgHistory: List<Double>?
    ): SentinelAdvice {
        
        val details = mutableListOf<String>()
        var score = 0
        var mainReason = "NORMAL"
        
        // ================================================================
        // 1. DRIFT DURABLE / PLATEAU HAUT
        // ================================================================
        
        val persistentHigh = bg > target + 30
        val slowDrift = delta in 0.5..3.0 && longAvgDelta > 0.3
        val littleAction = smbProposed < 0.1 && iobTotal < 1.0
        
        if (persistentHigh && slowDrift && lastBolusAgeMin > 20) {
            score += 30
            details.add("DRIFT: BG>${target+30} delta=${"%.1f".format(delta)} age=${lastBolusAgeMin.toInt()}m")
            mainReason = "DRIFT_PERSISTENT"
        }
        
        if (bg > 140 && littleAction && delta > 0.5 && lastBolusAgeMin > 30) {
            score += 20
            details.add("PLATEAU_HIGH: BG=${"%.0f".format(bg)} littleAction SMB=${"%.2f".format(smbProposed)} IOB=${"%.1f".format(iobTotal)}")
            if (mainReason == "NORMAL") mainReason = "PLATEAU_HIGH"
        }
        
        // ================================================================
        // 2. VARIABILITÉ / OSCILLATIONS
        // ================================================================
        
        if (bgHistory != null && bgHistory.size >= 6) {
            // Calcul std deviation
            val mean = bgHistory.average()
            val variance = bgHistory.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance)
            
            if (std > 30.0) {
                score += 25
                details.add("VARIABILITY: std=${"%.1f".format(std)}")
                if (mainReason == "NORMAL") mainReason = "HIGH_VARIABILITY"
            }
            
            // Sign flips (oscillations)
            val deltas = bgHistory.zipWithNext { a, b -> b - a }
            val signFlips = deltas.zipWithNext { d1, d2 -> 
                (d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)
            }.count { it }
            
            if (signFlips >= 2) {
                score += 20
                details.add("OSCILLATIONS: signFlips=$signFlips")
                if (mainReason == "NORMAL") mainReason = "OSCILLATIONS"
            }
        }
        
        // ================================================================
        // 3. STACKING RISK
        // ================================================================
        
        val highIOB = iobTotal > 2.0
        val nearPeak = pkpdStage in listOf("RISING", "PEAK") && iobActivity?.let { it > 0.4 } ?: false
        val smbChain = smbCount30min >= 3
        val heavySmb60 = smbTotal60min > 3.0
        val recentBolus = lastBolusAgeMin < 15
        
        if (highIOB || nearPeak) {
            score += 35
            details.add("STACKING: IOB=${"%.1f".format(iobTotal)} stage=$pkpdStage activity=${"%.2f".format(iobActivity ?: 0.0)}")
            mainReason = "STACKING_RISK"
        }
        
        if (smbChain || heavySmb60) {
            score += 30
            details.add("SMB_CHAIN: count30=$smbCount30min total60=${"%.1f".format(smbTotal60min)}")
            if (mainReason == "NORMAL") mainReason = "SMB_CHAIN"
        }
        
        if (recentBolus && delta > 0.5 && smbProposed > 0.5) {
            score += 15
            details.add("RECENT_BOLUS_STACKING: age=${lastBolusAgeMin.toInt()}m delta=${"%.1f".format(delta)} SMBprop=${"%.2f".format(smbProposed)}")
            if (mainReason == "NORMAL") mainReason = "RECENT_BOLUS_STACKING"
        }
        
        // ================================================================
        // 4. CONTRADICTIONS / DÉGRADATION
        // ================================================================
        
        if (!predBgsAvailable || predictedBg == null || eventualBg == null) {
            score += 40
            details.add("PREDICTION_MISSING: predAvail=$predBgsAvailable pred=$predictedBg eventual=$eventualBg")
            mainReason = "PREDICTION_MISSING"
        }
        
        if (pkpdStage == "PRE_ONSET" && iobTotal > 1.0 && smbProposed > 0.8) {
            score += 25
            details.add("CONTRADICTION_PKPD: stage=PRE_ONSET IOB=${"%.1f".format(iobTotal)} SMBprop=${"%.2f".format(smbProposed)}")
            if (mainReason == "NORMAL") mainReason = "CONTRADICTION_PKPD_ML"
        }
        
        if (autodriveActive && smbProposed < 0.05 && iobTotal < 0.5 && delta > 1.0 && lastBolusAgeMin > 30) {
            score += 20
            details.add("AUTODRIVE_STUCK: active=$autodriveActive SMBprop=${"%.2f".format(smbProposed)} delta=${"%.1f".format(delta)}")
            if (mainReason == "NORMAL") mainReason = "AUTODRIVE_STUCK"
        }
        
        // ================================================================
        // 5. SAFETY DEGRADATION
        // ================================================================
        
        if (noise >= 3) {
            score += 15
            details.add("HIGH_NOISE: level=$noise")
        }
        
        if (isStale) {
            score += 25
            details.add("STALE_DATA")
            if (mainReason == "NORMAL") mainReason = "STALE_DATA"
        }
        
        if (pumpUnreachable) {
            score += 30
            details.add("PUMP_UNREACHABLE")
            mainReason = "PUMP_UNREACHABLE"
        }
        
        // ================================================================
        // 6. DETERMINE TIER
        // ================================================================
        
        val tier = when {
            score >= 70 -> Tier.HIGH
            score >= 40 -> Tier.MEDIUM
            score >= 20 -> Tier.LOW
            else -> Tier.NONE
        }
        
        // ================================================================
        // 7. COMPUTE RECOMMENDATION
        // ================================================================
        
        val (recommendation, smbFactor, extraInterval, preferBasal) = when {
            mainReason == "STACKING_RISK" || mainReason == "SMB_CHAIN" -> {
                // Stacking : réduire fortement + augmenter interval
                Tuple4(Recommendation.HOLD_SOFT, 0.6, 6, false)
            }
            mainReason == "PREDICTION_MISSING" -> {
                // Degraded mode : cap modéré + interval
                Tuple4(Recommendation.REDUCE_SMB, 0.7, 4, false)
            }
            mainReason == "DRIFT_PERSISTENT" || mainReason == "PLATEAU_HIGH" -> {
                // Drift lent : préférer basal
                Tuple4(Recommendation.PREFER_BASAL, 0.8, 2, true)
            }
            mainReason in listOf("HIGH_VARIABILITY", "OSCILLATIONS") -> {
                // Variabilité : limiter SMB pour stabiliser
                Tuple4(Recommendation.REDUCE_SMB, 0.75, 3, false)
            }
            mainReason == "CONTRADICTION_PKPD_ML" -> {
                // Contradiction PKPD : prudent
                Tuple4(Recommendation.INCREASE_INTERVAL, 0.8, 4, false)
            }
            mainReason == "RECENT_BOLUS_STACKING" -> {
                // Recent bolus : augmenter interval
                Tuple4(Recommendation.INCREASE_INTERVAL, 0.85, 3, false)
            }
            mainReason == "AUTODRIVE_STUCK" -> {
                // Autodrive stuck : switch to basal
                Tuple4(Recommendation.PREFER_BASAL, 0.9, 2, true)
            }
            bg < 120 && delta > 0 -> {
                // BG proche target + montée : limiter variabilité (éviter hypo)
                Tuple4(Recommendation.CONFIRM, 0.9.coerceIn(0.6, 1.0), 1, false)
            }
            else -> {
                // Normal
                Tuple4(Recommendation.CONFIRM, 1.0, 0, false)
            }
        }
        
        return SentinelAdvice(
            score = score.coerceIn(0, 100),
            tier = tier,
            reason = mainReason,
            recommendation = recommendation,
            smbFactor = smbFactor,
            extraIntervalMin = extraInterval,
            preferBasal = preferBasal,
            details = details
        )
    }
    
    // Tuple helper (Kotlin n'a pas Tuple4 natif)
    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
