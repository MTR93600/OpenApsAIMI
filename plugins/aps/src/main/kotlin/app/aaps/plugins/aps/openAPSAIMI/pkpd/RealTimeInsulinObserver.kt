package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.abs

/**
 * Observateur temps réel de l'action insulinique.
 * 
 * Détecte:
 * - Onset réel (quand l'insuline commence vraiment à agir)
 * - Stage d'activité (RISING/PEAK/FALLING/TAIL)
 * - Time-to-peak et time-to-end
 * - Residual effect (aire restante)
 */
class RealTimeInsulinObserver {
    
    // État interne
    private var lastOnsetConfirmedAt: Long = 0L
    private val bgSlopeHistory = ArrayDeque<Double>(4)
    private val correlationHistory = ArrayDeque<Double>(3)
    
    /**
     * Reset l'observateur (ex: après un long gap sans insuline)
     */
    fun reset() {
        lastOnsetConfirmedAt = 0L
        bgSlopeHistory.clear()
        correlationHistory.clear()
    }
    
    /**
     * Met à jour l'observateur avec nouvelles données
     */
    fun update(
        currentBg: Double,
        bgDelta: Double,
        iobTotal: Double,
        iobActivityNow: Double,
        iobActivityIn30: Double,
        peakMinutesAbs: Int,
        diaHours: Double,
        carbsActiveG: Double,
        now: Long
    ): InsulinActionState {
        
        // Si IOB négligeable, reset et retourner état par défaut
        if (iobTotal < 0.1) {
            reset()
            return InsulinActionState.default()
        }
        
        // 1. Calcul BG slope lissé (EMA)
        val bgSlope = computeSmoothedSlope(bgDelta)
        
        // 2. Drive insulinique attendu (négatif si insuline devrait baisser BG)
        // Approximation: iobActivity * 50 mg/dL/h (ajustable)
        val expectedDrive = -iobActivityNow * 50.0
        
        // 3. Corrélation slope vs drive (neutraliser effet glucides)
        val carbNeutralizer = if (carbsActiveG > 5.0) 0.5 else 1.0
        val correlation = computeCorrelation(bgSlope, expectedDrive) * carbNeutralizer
        
        // 4. Détection onset
        val onsetConfirmed = detectOnset(correlation, iobTotal, now)
        val onsetConfidence = correlation.coerceIn(0.0, 1.0)
        val timeSinceOnset = if (onsetConfirmed && lastOnsetConfirmedAt > 0L) {
            (now - lastOnsetConfirmedAt) / 60000.0
        } else {
            0.0
        }
        
        // 5. Stage détection
        val stage = detectActivityStage(iobActivityNow, peakMinutesAbs, timeSinceOnset, diaHours)
        
        // 6. Time-to-peak/end
        val timeToPeak = if (stage == ActivityStage.RISING) peakMinutesAbs else 0
        val timeToEnd = estimateTimeToEnd(diaHours, timeSinceOnset)
        
        // 7. Residual effect (aire restante / aire totale)
        val residual = computeResidualEffect(timeSinceOnset, diaHours, stage)
        val effectiveIob = iobTotal * iobActivityNow
        
        // 8. Reason (debug)
        val reason = buildReason(onsetConfirmed, stage, correlation, residual)
        
        return InsulinActionState(
            onsetConfirmed = onsetConfirmed,
            onsetConfidenceScore = onsetConfidence,
            timeSinceOnsetMin = timeSinceOnset,
            activityNow = iobActivityNow,
            activityStage = stage,
            timeToPeakMin = timeToPeak,
            timeToEndMin = timeToEnd,
            residualEffect = residual,
            effectiveIob = effectiveIob,
            reason = reason
        )
    }
    
    private fun computeSmoothedSlope(bgDelta: Double): Double {
        bgSlopeHistory.addLast(bgDelta)
        if (bgSlopeHistory.size > 4) bgSlopeHistory.removeFirst()
        return if (bgSlopeHistory.isNotEmpty()) {
            bgSlopeHistory.average()
        } else {
            bgDelta
        }
    }
    
    private fun computeCorrelation(bgSlope: Double, expectedDrive: Double): Double {
        // Corrélation simple: si slope et drive vont dans même sens
        // expectedDrive négatif (insuline baisse BG) et bgSlope négatif → corrélation positive
        val alignment = if (bgSlope * expectedDrive > 0) 1.0 else -1.0
        val magnitude = abs(bgSlope) / (abs(expectedDrive) + 1.0)
        return (alignment * magnitude).coerceIn(-1.0, 1.0)
    }
    
    private fun detectOnset(correlation: Double, iobTotal: Double, now: Long): Boolean {
        correlationHistory.addLast(correlation)
        if (correlationHistory.size > 3) correlationHistory.removeFirst()
        
        // Onset confirmé si:
        // 1. IOB > 0.5U (assez d'insuline pour effet mesurable)
        // 2. Corrélation stable > 0.5 pendant 3 ticks (~15 min)
        val stableCorrelation = correlationHistory.size >= 3 && correlationHistory.all { it > 0.5 }
        val sufficientIob = iobTotal > 0.5
        
        if (stableCorrelation && sufficientIob && lastOnsetConfirmedAt == 0L) {
            lastOnsetConfirmedAt = now
            return true
        }
        
        return lastOnsetConfirmedAt > 0L
    }
    
    private fun detectActivityStage(
        activityNow: Double, 
        timeToPeak: Int, 
        timeSinceOnset: Double,
        diaHours: Double
    ): ActivityStage {
        val progressPct = timeSinceOnset / (diaHours * 60.0)
        
        return when {
            timeToPeak in 1..15 -> ActivityStage.PEAK      // À 15 min du pic
            timeToPeak > 15 -> ActivityStage.RISING         // Avant pic
            progressPct > 0.7 || activityNow < 0.2 -> ActivityStage.TAIL  // >70% ou activité faible
            else -> ActivityStage.FALLING                   // Post-pic normal
        }
    }
    
    private fun estimateTimeToEnd(diaHours: Double, timeSinceOnset: Double): Int {
        val totalDurationMin = diaHours * 60.0
        val remaining = totalDurationMin - timeSinceOnset
        return remaining.coerceAtLeast(0.0).toInt()
    }
    
    private fun computeResidualEffect(timeSinceOnset: Double, diaHours: Double, stage: ActivityStage): Double {
        // Approximation aire restante Weibull
        // Pic à ~1/3 du DIA, puis decay exponentiel
        val progress = timeSinceOnset / (diaHours * 60.0)
        
        return when (stage) {
            ActivityStage.RISING -> 0.9 - progress * 0.2   // Quasi tout reste avant pic
            ActivityStage.PEAK -> 0.7                       // 70% après pic
            ActivityStage.FALLING -> 0.5 - progress * 0.3   // Décroissance
            ActivityStage.TAIL -> 0.2 - progress * 0.2      // Queue résiduelle
        }.coerceIn(0.0, 1.0)
    }
    
    private fun buildReason(onset: Boolean, stage: ActivityStage, corr: Double, residual: Double): String {
        val onsetStr = if (onset) "✓" else "✗"
        return "onset=$onsetStr stage=$stage corr=${"%.2f".format(corr)} resid=${"%.2f".format(residual)}"
    }
}
