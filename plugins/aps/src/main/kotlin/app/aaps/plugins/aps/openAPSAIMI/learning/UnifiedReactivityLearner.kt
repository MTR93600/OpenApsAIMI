package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Unified Reactivity Learner - Remplace le système de buckets temporels
 * par une analyse basée sur métriques cliniques réelles (TIR, CV%, oscillations).
 * 
 * Objectifs:
 * 1. Éviter hyper prolongée > 180 mg/dL
 * 2. Éviter hypo répétées < 70 mg/dL  
 * 3. Réduire oscillations glycémiques (déviations/vagues)
 */
@Singleton
class UnifiedReactivityLearner @Inject constructor(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val log: AAPSLogger
) {
    private val fileName = "aimi_unified_reactivity.json"
    private val file by lazy { File(context.filesDir, fileName) }
    
    /**
     * Facteur de réactivité global
     * 1.0 = neutre
     * < 1.0 = réduire agressivité (si hypo répétées ou oscillations)
     * > 1.0 = augmenter agressivité (si hyper prolongée)
     */
    var globalFactor = 1.0
        private set
    
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6 heures
    
    init {
        load()
    }
    
    /**
     * Métriques de performance glycémique
     */
    data class GlycemicPerformance(
        val tir70_180: Double,        // % Time In Range 70-180 mg/dL
        val tir_above_180: Double,    // % temps en hyperglycémie
        val hypo_count: Int,          // Nombre d'épisodes hypo < 70
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
            // Récupérer toutes les valeurs BG des 24 dernières heures
            val bgReadings = persistenceLayer.getBgReadingsDataFromTime(start, now, false)
                .map { it.value }
                .filter { it > 39 }  // Exclure valeurs invalides
            
            if (bgReadings.size < 12) {  // Minimum 12 lectures (au moins 1h de données si CGM 5min)
                log.warn(LTag.APS, "UnifiedReactivityLearner: Pas assez de données BG (${bgReadings.size})")
                return null
            }
            
            // 1. TIR (Time In Range)
            val inRange = bgReadings.count { it in 70.0..180.0 }
            val above180 = bgReadings.count { it > 180 }
            val tir70_180 = (inRange.toDouble() / bgReadings.size) * 100.0
            val tir_above_180 = (above180.toDouble() / bgReadings.size) * 100.0
            
            // 2. Détection épisodes hypo (groupements contigus < 70)
            var hypo_count = 0
            var inHypo = false
            for (bg in bgReadings) {
                if (bg < 70 && !inHypo) {
                    hypo_count++
                    inHypo = true
                } else if (bg >= 70) {
                    inHypo = false
                }
            }
            
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
                if ((prev < 120 && curr > 120) || (prev > 120 && curr < 120)) {
                    crossing_count++
                }
            }
            
            val performance = GlycemicPerformance(
                tir70_180 = tir70_180,
                tir_above_180 = tir_above_180,
                hypo_count = hypo_count,
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
    fun computeAdjustment(perf: GlycemicPerformance): Double {
        var adjustment = 1.0
        val reasons = mutableListOf<String>()
        
        // 🔴 PRIORITÉ 1 : Hypo répétées (SÉCURITÉ ABSOLUE)
        when {
            perf.hypo_count >= 3 -> {
                adjustment *= 0.80  // Réduction forte
                reasons.add("3+ hypos → factor × 0.80")
            }
            perf.hypo_count == 2 -> {
                adjustment *= 0.85  // Réduction modérée
                reasons.add("2 hypos → factor × 0.85")
            }
            perf.hypo_count == 1 -> {
                adjustment *= 0.92  // Réduction légère
                reasons.add("1 hypo → factor × 0.92")
            }
        }
        
        // 🟡 PRIORITÉ 2 : Hyperglycémie prolongée (si pas d'hypo)
        if (perf.hypo_count == 0) {
            when {
                perf.tir_above_180 > 40 -> {
                    adjustment *= 1.20  // Augmentation importante
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.20")
                }
                perf.tir_above_180 > 30 -> {
                    adjustment *= 1.15  // Augmentation modérée
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.15")
                }
                perf.tir_above_180 > 20 -> {
                    adjustment *= 1.08  // Augmentation légère
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.08")
                }
            }
        }
        
        // 🟢 PRIORITÉ 3 : Oscillations (stabilité glycémique)
        if (perf.cv_percent > 40 || perf.crossing_count > 10) {
            adjustment *= 0.93  // Légère réduction pour amortir
            reasons.add("Variabilité élevée (CV=${perf.cv_percent.toInt()}%, Crossings=${perf.crossing_count}) → factor × 0.93")
        }
        
        // 🎯 Convergence vers 1.0 si performance optimale
        val isOptimal = perf.tir70_180 > 70 && 
                       perf.hypo_count == 0 && 
                       perf.cv_percent < 36 &&
                       perf.tir_above_180 < 15
        
        if (isOptimal) {
            // EMA douce vers 1.0 (decay de 5% par analyse)
            globalFactor += 0.05 * (1.0 - globalFactor)
            reasons.add("Performance optimale → convergence vers 1.0")
        } else {
            // EMA entre ancien facteur et nouvel ajustement
            val alpha = 0.15  // Poids du nouvel ajustement
            globalFactor = (globalFactor * (1 - alpha)) + (globalFactor * adjustment * alpha)
        }
        
        // Bornes de sécurité
        globalFactor = globalFactor.coerceIn(0.6, 1.4)
        
        log.info(LTag.APS, "UnifiedReactivityLearner: Nouveau globalFactor = ${"%.3f".format(globalFactor)} | ${reasons.joinToString(", ")}")
        
        return globalFactor
    }
    
    /**
     * Appeler toutes les 6h depuis DetermineBasalAIMI2
     */
    fun processIfNeeded() {
        val now = dateUtil.now()
        
        if (now - lastAnalysisTime < ANALYSIS_INTERVAL_MS) {
            return  // Pas encore le moment
        }
        
        val perf = analyzeLast24h() ?: return
        computeAdjustment(perf)
        save()
        
        lastAnalysisTime = now
    }
    
    private fun load() {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                globalFactor = json.optDouble("globalFactor", 1.0)
                lastAnalysisTime = json.optLong("lastAnalysisTime", 0L)
                log.info(LTag.APS, "UnifiedReactivityLearner: Loaded globalFactor=$globalFactor")
            }
        } catch (e: Exception) {
            log.error(LTag.APS, "UnifiedReactivityLearner: Erreur chargement", e)
        }
    }
    
    private fun save() {
        try {
            val json = JSONObject()
            json.put("globalFactor", globalFactor)
            json.put("lastAnalysisTime", lastAnalysisTime)
            file.writeText(json.toString())
            log.debug(LTag.APS, "UnifiedReactivityLearner: Saved globalFactor=$globalFactor")
        } catch (e: Exception) {
            log.error(LTag.APS, "UnifiedReactivityLearner: Erreur sauvegarde", e)
        }
    }
}
