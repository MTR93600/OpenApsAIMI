package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Unified Reactivity Learner - Remplace le système de buckets temporels
 * par une analyse basée sur métriques cliniques réelles (TIR, CV%, oscillations).
 * 
 * Objectifs:
 * 1. Éviter hyper prolongée > 180 mg/dL
 * 2. Éviter hypo répétées < 70 mg/dL  
 * 3. Réduire oscillations glycémiques (déviations/vagues)
 */
@SingleIn(AppScope::class)
class UnifiedReactivityLearner @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val log: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    internal data class BgSample(val value: Double, val timestamp: Long)

    data class SegmentFactorSnapshot(
        val daypart: ReactivityDaypart,
        val factor: Double,
    )

    data class StatusSnapshot(
        val globalFactor: Double,
        val shortTermFactor: Double,
        val combinedFactor: Double,
        val segmentFactors: List<SegmentFactorSnapshot>,
        val lastAnalysis: AnalysisSnapshot?,
        val last24hSampleCount: Int,
        val last2hSampleCount: Int,
        val shortAnalysisCount: Long,
        val longAnalysisCount: Long,
        val lastShortAnalysisTime: Long,
        val lastAnalysisTime: Long,
        val updatedAt: Long?,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bg24hRef = AtomicReference<List<BgSample>>(emptyList())
    private val bg2hRef = AtomicReference<List<BgSample>>(emptyList())
    private val exerciseEventsRef = AtomicReference<List<Long>>(emptyList())
    private val bg24hRefreshInFlight = AtomicBoolean(false)
    private val bg2hRefreshInFlight = AtomicBoolean(false)
    private val exerciseRefreshInFlight = AtomicBoolean(false)
    private val shortAnalysisCount = AtomicLong(0L)
    private val longAnalysisCount = AtomicLong(0L)
    private val statusRef = AtomicReference<StatusSnapshot>()
    private var last24hSampleCount = 0
    private var last2hSampleCount = 0
    
    companion object {
        private const val ANALYSIS_INTERVAL_MS = 30 * 60 * 1000L  // 30 minutes
        private const val SHORT_ANALYSIS_INTERVAL_MS = 10 * 60 * 1000L  // 10 minutes for short-term
    }
    

    
    // 📊 Expose last analysis for rT display
    data class AnalysisSnapshot(
        val timestamp: Long,
        val tir70_180: Double,
        val cv_percent: Double,
        val hypo_count: Int,
        val globalFactor: Double,
        val shortTermFactor: Double,
        val segmentFactor: Double,
        val segmentDaypart: ReactivityDaypart,
        val previousFactor: Double,
        val adjustmentReason: String,
        val floorLocked: Boolean = false,
        val floorLockReason: String = "",
        val floorReleased: Boolean = false,
        val totalReadings: Int = 0,
    )
    
    var lastAnalysis: AnalysisSnapshot? = null
        private set

    // 📁 Utilise AimiStorageHelper pour stockage robuste
    private val fileName = "aimi_unified_reactivity.json"
    private val csvFileName = "aimi_reactivity_analysis.csv"
    
    private val file by lazy { storageHelper.getAimiFile(fileName) }
    
    private val csvFile by lazy {
        storageHelper.getAimiFile(csvFileName).apply {
            if (!exists()) {
                storageHelper.saveFileSafe(this, 
                    "Timestamp,Date,TIR_70_180,TIR_70_140,TIR_140_180,TIR_180_250,TIR_Above_250," +
                    "Hypo_Count,CV_Percent,Crossing_Count,Mean_BG,GlobalFactor,Adjustment_Reason\n")
            }
        }
    }
    
    /**
     * Facteur de réactivité global
     * 1.0 = neutre
     * < 1.0 = réduire agressivité (si hypo répétées ou oscillations)
     * > 1.0 = augmenter agressivité (si hyper prolongée)
     */
    var globalFactor = 1.0
        private set
    
    /**
     * Facteur court terme (2h) pour réaction rapide aux événements aigus.
     * S'adapte plus vite que globalFactor.
     */
    var shortTermFactor = 1.0
        private set

    private val segmentFactors = ReactivityDaypart.entries.associateWith { 1.0 }.toMutableMap()
    
    private var lastAnalysisTime = 0L
    private var lastShortAnalysisTime = 0L
    // D3: latch a confirmed rise between 30-min analyses so a mid-interval meal rise isn't lost before consumption.
    private var pendingConfirmedRise = false
    // F2: last hypo burden (% <70 over 24h) already priced into globalFactor. The burden is a rolling-window
    // metric; without this latch a single episode re-applies its penalty on every 30-min analysis for 24h
    // (up to 48×). We only penalize the *increment* of burden since the previous analysis. Persisted so the
    // "already priced in" state survives restarts.
    private var lastSeenHypoBurdenPct = 0.0
    
    init {
        load()
        publishStatus()
    }
    
    /**
     * Retourne le facteur combiné (40% global, 30% court terme, 30% segment horaire).
     */
    fun getCombinedFactor(hourOfDay: Int = currentHourOfDay()): Double {
        val daypart = ReactivityDaypart.fromHour(hourOfDay)
        val segment = segmentFactors[daypart] ?: globalFactor
        return ReactivityDaypart.combineFactors(globalFactor, shortTermFactor, segment)
    }

    fun segmentFactorFor(hourOfDay: Int): Double {
        val daypart = ReactivityDaypart.fromHour(hourOfDay)
        return segmentFactors[daypart] ?: globalFactor
    }

    private fun currentHourOfDay(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.HOUR_OF_DAY)
    }
    
    /**
     * Métriques de performance glycémique détaillées
     */
    data class GlycemicPerformance(
        val tir70_180: Double,        // % Time In Range 70-180 mg/dL
        val tir70_140: Double,        // % Time In optimal Range 70-140 mg/dL  
        val tir140_180: Double,       // % Time In acceptable Range 140-180 mg/dL
        val tir180_250: Double,       // % Time In moderate hyper 180-250 mg/dL
        val tir_above_250: Double,    // % Time In severe hyper > 250 mg/dL
        val tir_above_180: Double,    // % temps en hyperglycémie totale (>180)
        val hypo_count: Int,          // Nombre d'épisodes hypo < 70
        val tir_below_70: Double,     // % temps < 70 sur 24h (charge hypo pondérée par la durée) — D1
        val cv_percent: Double,       // Coefficient de Variation (%)
        val crossing_count: Int,      // Oscillations (crossings de seuil 120)
        val mean_bg: Double,          // Glycémie moyenne
        val total_readings: Int       // Nombre total de lectures
    )
    
    /**
     * Analyse les dernières 24h pour calculer les métriques glycémiques
     */
    fun analyzeLast24h(): GlycemicPerformance? {
        val now = dateUtil.now()
        val start = now - (24 * 60 * 60 * 1000L)
        
        try {
            refreshBg24hAsync(start)
            refreshExerciseEventsAsync(start)
            val bgSamples = bg24hRef.get()
            val bgReadings = bgSamples.map { it.value }
            
            if (bgReadings.isEmpty() || bgReadings.size < 12) {
                log.warn(LTag.APS, "UnifiedReactivityLearner: Pas assez de données BG (${bgReadings.size})")
                return null
            }
            
            // 1. TIR (Time In Range) - Breakdown détaillé
            val inRange70_140 = bgReadings.count { it in 70.0..140.0 }
            val inRange140_180 = bgReadings.count { it in 140.0..180.0 }
            val inRange180_250 = bgReadings.count { it in 180.0..250.0 }
            val above250 = bgReadings.count { it > 250.0 }
            val above180 = bgReadings.count { it > 180.0 }
            
            val tir70_140 = (inRange70_140.toDouble() / bgReadings.size.toDouble()) * 100.0
            val tir140_180 = (inRange140_180.toDouble() / bgReadings.size.toDouble()) * 100.0
            val tir70_180 = tir70_140 + tir140_180
            val tir180_250 = (inRange180_250.toDouble() / bgReadings.size.toDouble()) * 100.0
            val tir_above_250 = (above250.toDouble() / bgReadings.size.toDouble()) * 100.0
            val tir_above_180 = (above180.toDouble() / bgReadings.size.toDouble()) * 100.0
            val tir_below_70 = (bgReadings.count { it < 70.0 }.toDouble() / bgReadings.size.toDouble()) * 100.0
            
            // 2. Détection épisodes hypo (groupements contigus < 70)
            val hypo_count = ReactivityDaypart.countHypoEpisodes(bgReadings)
            
            // 3. Coefficient de Variation (CV%)
            val mean = bgReadings.average()
            val variance = bgReadings.map { (it - mean).pow(2) }.average()
            val stdDev = sqrt(variance)
            val cv_percent = (stdDev / mean) * 100.0
            
            // 4. Oscillations (crossings du seuil 120 mg/dL)
            var crossing_count = 0
            for (i in 1 until bgReadings.size) {
                val prev = bgReadings[i - 1]
                val curr = bgReadings[i]
                if ((prev < 120.0 && curr > 120.0) || (prev > 120.0 && curr < 120.0)) {
                    crossing_count++
                }
            }
            
            val performance = GlycemicPerformance(
                tir70_180 = tir70_180,
                tir70_140 = tir70_140,
                tir140_180 = tir140_180,
                tir180_250 = tir180_250,
                tir_above_250 = tir_above_250,
                tir_above_180 = tir_above_180,
                hypo_count = hypo_count,
                tir_below_70 = tir_below_70,
                cv_percent = cv_percent,
                crossing_count = crossing_count,
                mean_bg = mean,
                total_readings = bgReadings.size
            )
            
            log.debug(LTag.APS, "UnifiedReactivityLearner: TIR=${tir70_180.toInt()}%, Hyper=${tir_above_180.toInt()}%, " +
                "Hypo=$hypo_count, CV=${cv_percent.toInt()}%, Crossings=$crossing_count")
            
            return performance
            
        } catch (e: Exception) {
            log.error(LTag.APS, "UnifiedReactivityLearner: Erreur analyse 24h", e)
            return null
        }
    }
    
    /**
     * Calcule l'ajustement du facteur global basé sur la performance glycémique
     * 
     * Priorités:
     * 1. 🔴 SÉCURITÉ : Hypo répétées → réduction agressive
     * 2. 🟡 EFFICACITÉ : Hyper prolongée → augmentation modérée
     * 3. 🟢 STABILITÉ : Oscillations → légère réduction
     */
    fun computeAdjustment(perf: GlycemicPerformance, isConfirmedRise: Boolean = false): Double {
        var adjustment = 1.0
        val reasons = mutableListOf<String>()
        
        // 🔴 PRIORITÉ 1 : charge hypo pondérée par la DURÉE (D1). Un unique relevé de 5 min ne doit PAS épingler
        // le plancher 24 h : la pénalité suit la fraction de temps < 70 (charge réelle), pas un comptage binaire.
        val hypoBurdenPct = perf.tir_below_70
        // F2 — event-based penalty. Only a genuinely new/worsening episode (burden increased since the
        // previous analysis) applies the penalty; an episode already priced in keeps depressing the factor
        // via the persisted state, but no longer *re-compounds* while it lingers in the 24h window. Burden
        // decreasing (episode aging out) is not credited here — upward recovery is handled by the optimal /
        // good-enough convergence paths below. Note: `hypoBurdenPct` itself (current burden) still drives the
        // hyper safety-damping, the adaptive α, and the floor-lock reason — only the penalty *application* is gated.
        val burdenIncreased = hypoBurdenPct > lastSeenHypoBurdenPct + 1e-9
        lastSeenHypoBurdenPct = hypoBurdenPct
        val hypoPenalty = if (!burdenIncreased) 1.0 else when {
            hypoBurdenPct >= 5.0 -> 0.80   // charge sévère / prolongée
            hypoBurdenPct >= 2.0 -> 0.88
            hypoBurdenPct >= 0.8 -> 0.94   // ~2-3 relevés contigus
            hypoBurdenPct > 0.0  -> 0.98   // relevé isolé → quasi neutre
            else               -> 1.0
        }
        if (hypoPenalty < 1.0) {
            adjustment *= hypoPenalty
            reasons.add("Hypo burden ${"%.1f".format(hypoBurdenPct)}% (${perf.hypo_count} ep, new) → factor ×${"%.2f".format(hypoPenalty)}")
        }
        
        // 🟡 PRIORITÉ 2 : Hyperglycémie prolongée
        // MODIFICATION : On autorise l'augmentation même si hypo_count > 0, 
        // mais avec un amorti de sécurité si des hypos sont présentes.
        val hyperAdjustment = when {
            perf.tir_above_250 > 20 -> 1.30
            perf.tir_above_180 > 50 -> 1.25
            perf.tir_above_180 > 40 -> 1.20
            perf.tir_above_180 > 30 -> 1.15
            perf.tir_above_180 > 20 -> 1.08
            else -> 1.0
        }

        if (hyperAdjustment > 1.0) {
            // SÉCURITÉ : On autorise l'augmentation même si hypo_count > 0,
            // avec amorti progressif selon la charge hypo récente.
            // EXCEPTION : Si la montée est confirmée (isConfirmedRise), on ignore l'amorti hypo.
            val safetyDamping = when {
                isConfirmedRise -> 1.0
                hypoBurdenPct >= 5.0 -> 0.50
                hypoBurdenPct >= 2.0 -> 0.70
                hypoBurdenPct >= 0.8 -> 0.85
                else -> 1.0
            }
            val finalHyperAdj = 1.0 + (hyperAdjustment - 1.0) * safetyDamping
            adjustment *= finalHyperAdj
            
            val hypoWarning = when {
                isConfirmedRise && perf.hypo_count > 0 -> " (Hypo damping bypassed - Confirmed Rise)"
                perf.hypo_count > 0 -> " (Damped by hypos)"
                else -> ""
            }
            reasons.add("Hyper detection: adj x${"%.2f".format(finalHyperAdj)}$hypoWarning")
        }
        
        // 🟢 PRIORITÉ 3 : Oscillations (stabilité glycémique). D2 : ne pénaliser QUE si la variabilité est réellement
        // élevée (CV ET crossings) — le comptage de crossings du seuil fixe 120 pénalisait à tort une cible ~100
        // (glycémie 100-130 = bonne journée) tant que le CV était bas. Le `&&` neutralise ce faux positif.
        if (perf.cv_percent > 40 && perf.crossing_count > 10) {
            adjustment *= 0.93  // Légère réduction pour amortir
            reasons.add("Variabilité élevée (CV=${perf.cv_percent.toInt()}%, Crossings=${perf.crossing_count}) → factor × 0.93")
        }
        
        // 🎯 Convergence vers 1.0 si performance optimale
        // F4 — use hypo *burden* (< 0.4% ≈ a single stray reading), not a binary episode count. A lone
        // reading <70 previously set hypo_count > 0 and blocked the only fast recovery path for a full 24h.
        val isOptimal = perf.tir70_180 > 70 &&
                       hypoBurdenPct < 0.4 &&
                       perf.cv_percent < 36 &&
                       perf.tir_above_180 < 15
        
        val previousFactor = globalFactor
        var targetFactor = globalFactor * adjustment
        var floorReleased = false
        var floorLockReason = ""

        if (isOptimal) {
            // EMA douce vers 1.0 (decay de 5% par analyse)
            // Si tout va bien, on relaxe doucement vers la neutralité
            targetFactor = 1.0
            val decayAlpha = 0.05
            globalFactor = (targetFactor * decayAlpha + globalFactor * (1 - decayAlpha))
            reasons.add("Performance optimale → convergence vers 1.0")
        } else {
            // 🎯 Adaptive Learning Rate based on glycemic context
            // IMPROVEMENT: Adjust learning speed based on situation severity
            val alpha = when {
                hypoBurdenPct >= 2.0 -> 0.80     // Very fast: real hypo burden (safety critical)
                perf.cv_percent > 40 -> 0.50     // Moderate: High variability
                perf.tir_above_180 > 40 -> 0.60  // Fast: Persistent hyper
                else -> 0.70                      // Standard: Normal conditions
            }
            
            log.debug(LTag.APS, "UnifiedReactivityLearner: Adaptive α=$alpha (hypo=${perf.hypo_count}, CV=${perf.cv_percent.toInt()}%, hyper=${perf.tir_above_180.toInt()}%)")
            
            // Apply EMA: New = (Target * alpha) + (Old * (1-alpha))
            globalFactor = (targetFactor * alpha + globalFactor * (1 - alpha)).coerceIn(0.5, 1.5)

            // F4 — "good enough" recovery (fixes the frozen zone A4). Without this, a good-but-not-optimal
            // day (CV 36–44, hypo-free, mild/no hyper) has `adjustment == 1.0` → target == globalFactor →
            // the EMA above freezes the factor wherever it last fell. Nudge gently back toward neutral so a
            // clean day recovers even when it doesn't clear the strict `isOptimal` bar. Only upward, capped
            // at 1.0, and only when no directional signal fired this tick (adjustment == 1.0).
            val goodEnough = adjustment == 1.0 &&
                hypoBurdenPct < 0.4 &&
                perf.tir70_180 > 75 &&
                perf.tir_above_180 < 15 &&
                perf.cv_percent < 45
            if (goodEnough && globalFactor < 1.0) {
                val nudgeAlpha = 0.03
                globalFactor = (1.0 * nudgeAlpha + globalFactor * (1 - nudgeAlpha)).coerceIn(0.5, 1.5)
                reasons.add("Bonne journée (récupération douce) → nudge vers 1.0")
            }
        }

        // 🛡️ Floor release: keep an escape path for real meal rises even with generally good TIR.
        // F3 — widen the release band from the hard floor (≤0.55) up to <0.75 so a confirmed meal rise at
        // BG 120–150 (which never qualifies as `isConfirmedRise` at higher BG) can still escape a depressed
        // factor. Deliberately conservative vs the report's <1.0: the release stays rise/hyper-gated and
        // capped at 0.95, so it can only open the throttle where there is genuine upward pressure.
        val belowFloorBand = globalFactor < 0.75
        val acceptableCv = perf.cv_percent < 45
        val sustainedHyper = perf.tir_above_180 > 25
        val acuteRiseHyper = isConfirmedRise && perf.tir_above_180 > 10
        val acuteRiseWithGoodTir = isConfirmedRise && perf.tir70_180 > 80
        val riseAgainstSlowShortTerm = isConfirmedRise && shortTermFactor < 1.0
        val canReleaseFloor = belowFloorBand &&
            acceptableCv &&
            ((hypoBurdenPct < 1.0 && sustainedHyper) || acuteRiseHyper || acuteRiseWithGoodTir || riseAgainstSlowShortTerm)
        if (canReleaseFloor) {
            val releaseStep = when {
                isConfirmedRise && perf.tir_above_180 > 25 -> 0.10
                isConfirmedRise -> 0.07
                else -> 0.04
            }
            val released = (globalFactor + releaseStep).coerceAtMost(0.95)
            if (released > globalFactor) {
                floorReleased = true
                globalFactor = released
                reasons.add("Floor release +${"%.2f".format(releaseStep)} (rise=$isConfirmedRise, TIR>180=${perf.tir_above_180.toInt()}%)")
            }
        }
        if (globalFactor <= 0.5501) {
            floorLockReason = when {
                hypoBurdenPct >= 0.8 -> "Recent hypo burden"
                perf.cv_percent > 40 -> "High variability (CV)"
                perf.tir_above_180 < 25 -> "No sustained hyper pressure"
                else -> "Awaiting stronger rise confirmation"
            }
        }
        
        val reasonsStr = reasons.joinToString(", ")
        log.info(LTag.APS, "UnifiedReactivityLearner: Nouveau globalFactor = ${"%.3f".format(globalFactor)} | $reasonsStr")
        if (globalFactor <= 0.5501) {
            log.info(LTag.APS, "UnifiedReactivityLearner: Floor lock active (<=0.55) | reason=$floorLockReason")
        } else if (floorReleased) {
            log.info(LTag.APS, "UnifiedReactivityLearner: Floor released progressively to ${"%.3f".format(globalFactor)}")
        }
        
        // 📊 Capture snapshot for rT display
        val now = dateUtil.now()
        val windowStart = now - (24 * 60 * 60 * 1000L)
        updateSegmentFactors(windowStart, now)

        val currentDaypart = ReactivityDaypart.fromHour(currentHourOfDay())
        lastAnalysis = AnalysisSnapshot(
            timestamp = now,
            tir70_180 = perf.tir70_180,
            cv_percent = perf.cv_percent,
            hypo_count = perf.hypo_count,
            globalFactor = globalFactor,
            shortTermFactor = shortTermFactor,
            segmentFactor = segmentFactors[currentDaypart] ?: globalFactor,
            segmentDaypart = currentDaypart,
            previousFactor = previousFactor,
            adjustmentReason = reasonsStr,
            floorLocked = globalFactor <= 0.5501,
            floorLockReason = floorLockReason,
            floorReleased = floorReleased,
            totalReadings = perf.total_readings,
        )
        last24hSampleCount = perf.total_readings
        longAnalysisCount.incrementAndGet()
        publishStatus(now)
        
        save()
        exportToCSV(perf, reasonsStr)
        
        return globalFactor
    }

    private fun updateSegmentFactors(windowStart: Long, windowEnd: Long) {
        val samples = bg24hRef.get()
        if (samples.size < 12) return

        val exerciseTimestamps = exerciseEventsRef.get()
        for (daypart in ReactivityDaypart.entries) {
            val segmentSamples = samples.filter { sample ->
                ReactivityDaypart.fromHour(hourFromTimestamp(sample.timestamp)) == daypart
            }
            if (segmentSamples.size < 3) continue

            val values = segmentSamples.map { it.value }
            val hypoCount = ReactivityDaypart.countHypoEpisodes(values)
            val above180 = values.count { it > 180.0 }
            val tirAbove180 = (above180.toDouble() / values.size) * 100.0
            val exerciseInSegment = ReactivityDaypart.hasExerciseInDaypart(
                exerciseTimestamps = exerciseTimestamps,
                daypart = daypart,
                windowStart = windowStart,
                windowEnd = windowEnd,
                hourFromTimestamp = ::hourFromTimestamp,
            )

            val currentSegment = segmentFactors[daypart] ?: globalFactor
            val rawSegment = ReactivityDaypart.computeRawSegmentFactor(
                currentSegment = currentSegment,
                hypoCount = hypoCount,
                tirAbove180 = tirAbove180,
                exerciseInSegment = exerciseInSegment,
            )
            val shrunk = ReactivityDaypart.shrinkTowardGlobal(
                rawSegment = rawSegment,
                global = globalFactor,
                sampleCount = segmentSamples.size,
            )
            segmentFactors[daypart] = ReactivityDaypart.capSegmentAgainstGlobal(
                segment = shrunk,
                global = globalFactor,
                hypoCount = hypoCount,
            ).coerceIn(ReactivityDaypart.FACTOR_MIN, ReactivityDaypart.FACTOR_MAX)
        }
    }

    private fun hourFromTimestamp(timestamp: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.HOUR_OF_DAY)
    }
    
    /**
     * Appeler toutes les 5 min depuis DetermineBasalAIMI2.
     * Gère deux échelles de temps: court terme (10 min) et long terme (30 min).
     */
    fun processIfNeeded(isConfirmedRise: Boolean = false) {
        val now = dateUtil.now()

        // D3: latch the confirmed rise. The 24h analysis (the ONLY consumer of the flag) runs every 30 min; without
        // latching, a meal rise seen between two analyses never reaches computeAdjustment, so the floor-release and
        // hypo-damping-bypass escapes never fire. Any `true` within the interval is preserved until consumed.
        if (isConfirmedRise) pendingConfirmedRise = true

        // === SHORT-TERM ANALYSIS (every 10 min on last 2h) ===
        if (now - lastShortAnalysisTime >= SHORT_ANALYSIS_INTERVAL_MS) {
            val shortPerf = analyzeLast2h()
            if (shortPerf != null) {
                computeShortTermAdjustment(shortPerf)
                last2hSampleCount = shortPerf.total_readings
                shortAnalysisCount.incrementAndGet()
            }
            lastShortAnalysisTime = now
            publishStatus(now)
        }
        
        // === LONG-TERM ANALYSIS (every 30 min on last 24h) ===
        if (now - lastAnalysisTime >= ANALYSIS_INTERVAL_MS) {
            val perf = analyzeLast24h() ?: return
            computeAdjustment(perf, pendingConfirmedRise)
            pendingConfirmedRise = false
            lastAnalysisTime = now
            publishStatus(now)
        }
        
        save()
    }
    
    /**
     * Analyse les dernières 2h pour réaction rapide
     */
    fun analyzeLast2h(): GlycemicPerformance? {
        val now = dateUtil.now()
        val start = now - (2 * 60 * 60 * 1000L)  // 2 hours
        
        try {
            refreshBg2hAsync(start)
            val bgSamples = bg2hRef.get()
            val bgReadings = bgSamples.map { it.value }
            
            if (bgReadings.isEmpty() || bgReadings.size < 6) {
                return null  // Need at least 30 min of data
            }
            
            val inRange70_140 = bgReadings.count { it in 70.0..140.0 }
            val inRange140_180 = bgReadings.count { it in 140.0..180.0 }
            val inRange180_250 = bgReadings.count { it in 180.0..250.0 }
            val above250 = bgReadings.count { it > 250.0 }
            val above180 = bgReadings.count { it > 180.0 }
            
            val tir70_140 = (inRange70_140.toDouble() / bgReadings.size) * 100.0
            val tir140_180 = (inRange140_180.toDouble() / bgReadings.size) * 100.0
            val tir70_180 = tir70_140 + tir140_180
            val tir180_250 = (inRange180_250.toDouble() / bgReadings.size) * 100.0
            val tir_above_250 = (above250.toDouble() / bgReadings.size) * 100.0
            val tir_above_180 = (above180.toDouble() / bgReadings.size) * 100.0
            
            var hypo_count = ReactivityDaypart.countHypoEpisodes(bgReadings)
            
            val mean = bgReadings.average()
            val variance = bgReadings.map { (it - mean).pow(2) }.average()
            val cv_percent = (sqrt(variance) / mean) * 100.0
            
            var crossing_count = 0
            for (i in 1 until bgReadings.size) {
                if ((bgReadings[i-1] < 120 && bgReadings[i] > 120) || 
                    (bgReadings[i-1] > 120 && bgReadings[i] < 120)) crossing_count++
            }
            
            val tir_below_70 = (bgReadings.count { it < 70.0 }.toDouble() / bgReadings.size) * 100.0
            return GlycemicPerformance(
                tir70_180, tir70_140, tir140_180, tir180_250, tir_above_250,
                tir_above_180, hypo_count, tir_below_70, cv_percent, crossing_count, mean, bgReadings.size
            )
        } catch (e: Exception) {
            log.error(LTag.APS, "UnifiedReactivityLearner: Error in 2h analysis", e)
            return null
        }
    }

    private fun refreshBg24hAsync(start: Long) {
        if (!bg24hRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val values = persistenceLayer.getBgReadingsDataFromTime(start, ascending = false)
                    .mapNotNull { gv -> toBgSample(gv) }
                bg24hRef.set(values)
            } catch (_: Exception) {
                bg24hRef.set(emptyList())
            } finally {
                bg24hRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshBg2hAsync(start: Long) {
        if (!bg2hRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val values = persistenceLayer.getBgReadingsDataFromTime(start, ascending = false)
                    .mapNotNull { gv -> toBgSample(gv) }
                bg2hRef.set(values)
            } catch (_: Exception) {
                bg2hRef.set(emptyList())
            } finally {
                bg2hRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshExerciseEventsAsync(start: Long) {
        if (!exerciseRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                val timestamps = persistenceLayer.getTherapyEventDataFromTime(start, ascending = true)
                    .filter { event -> event.isValid && event.type == TE.Type.EXERCISE }
                    .map { event -> event.timestamp }
                exerciseEventsRef.set(timestamps)
            } catch (_: Exception) {
                exerciseEventsRef.set(emptyList())
            } finally {
                exerciseRefreshInFlight.set(false)
            }
        }
    }

    private fun toBgSample(gv: GV): BgSample? {
        val value = gv.value
        if (value <= 39.0) return null
        return BgSample(value = value, timestamp = gv.timestamp)
    }
    
    /**
     * Ajustement court terme plus agressif
     */
    internal fun computeShortTermAdjustment(perf: GlycemicPerformance) {
        var adjustment = 1.0
        
        // Hypo in last 2h: Strong reduction
        if (perf.hypo_count >= 1) {
            adjustment *= 0.85
            log.info(LTag.APS, "UnifiedReactivityLearner: Short-term hypo detected, reducing factor")
        }
        
        // Persistent hyper in last 2h
        if (perf.tir_above_180 > 60 && perf.hypo_count == 0) {  // More than 60% of 2h in hyper
            adjustment *= 1.20
            log.info(LTag.APS, "UnifiedReactivityLearner: Short-term hyper (${perf.tir_above_180.toInt()}%), increasing factor")
        } else if (perf.tir_above_180 > 40 && perf.hypo_count == 0) {
            adjustment *= 1.10
        }
        
        // High variability
        if (perf.cv_percent > 35) {
            adjustment *= 0.95
        }
        
        // F1 — Neutral convergence (recovery path).
        // A stable, hypo-free, low-variability 2h window carries no directional signal, so `adjustment`
        // stays exactly 1.0. Without a rappel term the EMA below is algebraically a no-op
        // (f·α + f·(1−α) = f), so shortTermFactor stays pinned wherever it last fell — e.g. at the 0.5
        // floor after a single hypo — indefinitely, and the pin is persisted across restarts. Give it a
        // gentle pull back toward neutral on a good window so a clean day actually recovers.
        // ~1h half-life at the 10-min short-term cadence.
        if (adjustment == 1.0) {
            val neutralAlpha = 0.08
            shortTermFactor = (1.0 * neutralAlpha + shortTermFactor * (1 - neutralAlpha)).coerceIn(0.5, 1.5)
            return
        }

        val target = shortTermFactor * adjustment

        // Adaptive learning rate for short-term (faster than long-term)
        val alpha = when {
            perf.hypo_count >= 1 -> 0.70        // Ultra-fast: Hypo is urgent
            perf.tir_above_180 > 60 -> 0.50      // Fast: Severe hyper
            perf.cv_percent > 35 -> 0.45         // Moderate-fast: High variability
            else -> 0.40                         // Standard short-term rate
        }

        shortTermFactor = (target * alpha + shortTermFactor * (1 - alpha)).coerceIn(0.5, 1.5)
    }
    
    /**
     * Exporte les métriques et le facteur vers CSV pour analyse post-traitement
     */
    private fun exportToCSV(perf: GlycemicPerformance, reasonsStr: String) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = System.currentTimeMillis()
            val date = sdf.format(Date(timestamp))
            
            val line = listOf(
                timestamp,
                date,
                "%.1f".format(Locale.US, perf.tir70_180),
                "%.1f".format(Locale.US, perf.tir70_140),
                "%.1f".format(Locale.US, perf.tir140_180),
                "%.1f".format(Locale.US, perf.tir180_250),
                "%.1f".format(Locale.US, perf.tir_above_250),
                perf.hypo_count,
                "%.1f".format(Locale.US, perf.cv_percent),
                perf.crossing_count,
                "%.1f".format(Locale.US, perf.mean_bg),
                "%.3f".format(Locale.US, globalFactor),
                "\"${reasonsStr.replace("\"", "'")}\""
            ).joinToString(",") + "\n"
            
            FileWriter(csvFile, true).use { it.append(line) }
            log.debug(LTag.APS, "UnifiedReactivityLearner: Exported analysis to CSV")
        } catch (e: Exception) {
            log.error(LTag.APS, "UnifiedReactivityLearner: CSV export error", e)
        }
    }
    
    /**
     * Chargement robuste avec fallback complet.
     * ⚠️ CRITIQUE: Ne doit JAMAIS crasher au démarrage!
     * En cas d'erreur (permissions, fichier corrompu, etc.), utilise les valeurs par défaut.
     */
    private fun load() {
        storageHelper.loadFileSafe(file, 
            onSuccess = { content ->
                val json = JSONObject(content)
                globalFactor = json.optDouble("globalFactor", 1.0).coerceIn(0.5, 1.5)
                shortTermFactor = json.optDouble("shortTermFactor", 1.0).coerceIn(0.5, 1.5)
                lastAnalysisTime = json.optLong("lastAnalysisTime", 0L)
                lastShortAnalysisTime = json.optLong("lastShortAnalysisTime", 0L)
                lastSeenHypoBurdenPct = json.optDouble("lastSeenHypoBurdenPct", 0.0)
                val segmentJson = json.optJSONObject("segmentFactors")
                ReactivityDaypart.entries.forEach { daypart ->
                    segmentFactors[daypart] = segmentJson
                        ?.optDouble(daypart.jsonKey(), globalFactor)
                        ?.coerceIn(ReactivityDaypart.FACTOR_MIN, ReactivityDaypart.FACTOR_MAX)
                        ?: globalFactor
                }
                log.info(LTag.APS, "UnifiedReactivityLearner: ✅ Loaded state")
                log.info(LTag.APS, "  → globalFactor=$globalFactor, shortTerm=$shortTermFactor")
            },
            onError = { e ->
                log.warn(LTag.APS, "UnifiedReactivityLearner: Load failed, using defaults (factor=1.0)")
                globalFactor = 1.0
                shortTermFactor = 1.0
                lastAnalysisTime = 0L
                lastShortAnalysisTime = 0L
                lastSeenHypoBurdenPct = 0.0
                ReactivityDaypart.entries.forEach { daypart ->
                    segmentFactors[daypart] = 1.0
                }
            }
        )
    }

    fun statusSnapshot(): StatusSnapshot {
        val snapshot = statusRef.get()
        return snapshot.copy(segmentFactors = snapshot.segmentFactors.toList())
    }

    private fun publishStatus(observedAt: Long? = null) {
        val currentDaypart = ReactivityDaypart.fromHour(currentHourOfDay())
        val immutableSegments = ReactivityDaypart.entries.map { daypart ->
            SegmentFactorSnapshot(daypart, segmentFactors[daypart] ?: globalFactor)
        }
        val lastEngineUpdate = maxOf(lastAnalysisTime, lastShortAnalysisTime, lastAnalysis?.timestamp ?: 0L)
        statusRef.set(
            StatusSnapshot(
                globalFactor = globalFactor,
                shortTermFactor = shortTermFactor,
                combinedFactor = ReactivityDaypart.combineFactors(
                    globalFactor,
                    shortTermFactor,
                    segmentFactors[currentDaypart] ?: globalFactor,
                ),
                segmentFactors = immutableSegments,
                lastAnalysis = lastAnalysis,
                last24hSampleCount = last24hSampleCount,
                last2hSampleCount = last2hSampleCount,
                shortAnalysisCount = shortAnalysisCount.get(),
                longAnalysisCount = longAnalysisCount.get(),
                lastShortAnalysisTime = lastShortAnalysisTime,
                lastAnalysisTime = lastAnalysisTime,
                updatedAt = observedAt ?: lastEngineUpdate.takeIf { it > 0L },
            )
        )
    }
    
    /**
     * Sauvegarde robuste avec gestion d'erreurs.
     * Si la sauvegarde échoue, l'app continue de fonctionner (perte de l'état seulement).
     */
    private fun save() {
        val json = JSONObject()
        json.put("globalFactor", globalFactor)
        json.put("shortTermFactor", shortTermFactor)
        json.put("lastAnalysisTime", lastAnalysisTime)
        json.put("lastShortAnalysisTime", lastShortAnalysisTime)
        json.put("lastSeenHypoBurdenPct", lastSeenHypoBurdenPct)
        val segmentJson = JSONObject()
        ReactivityDaypart.entries.forEach { daypart ->
            segmentJson.put(daypart.jsonKey(), segmentFactors[daypart] ?: globalFactor)
        }
        json.put("segmentFactors", segmentJson)
        
        if (storageHelper.saveFileSafe(file, json.toString())) {
            log.debug(LTag.APS, "UnifiedReactivityLearner: ✅ Saved state")
            log.debug(LTag.APS, "  → globalFactor=$globalFactor, shortTerm=$shortTermFactor")
        }
    }
}
