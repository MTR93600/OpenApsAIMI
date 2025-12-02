package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val log: AAPSLogger
) {
    
    companion object {
        private const val ANALYSIS_INTERVAL_MS = 30 * 60 * 1000L  // 30 minutes
    }
    

    
    // 📊 Expose last analysis for rT display
    data class AnalysisSnapshot(
        val timestamp: Long,
        val tir70_180: Double,
        val cv_percent: Double,
        val hypo_count: Int,
        val globalFactor: Double,
        val previousFactor: Double,
        val adjustmentReason: String
    )
    
    var lastAnalysis: AnalysisSnapshot? = null
        private set

    // 📁 JSON stocké dans Documents/AAPS comme les autres fichiers AIMI
    private val fileName = "aimi_unified_reactivity.json"
    private val csvFileName = "aimi_reactivity_analysis.csv"
    private val file by lazy { 
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
        externalDir.mkdirs()
        File(externalDir, fileName)
    }
    private val csvFile by lazy {
        val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
        File(externalDir, csvFileName).apply {
            if (!exists()) {
                writeText("Timestamp,Date,TIR_70_180,TIR_70_140,TIR_140_180,TIR_180_250,TIR_Above_250," +
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
    
    private var lastAnalysisTime = 0L
    
    init {
        load()
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
            // getBgReadingsDataFromTime retourne Single<List<GV>>, donc on utilise blockingGet()
            val bgReadingsList = persistenceLayer.getBgReadingsDataFromTime(start, false)
                .blockingGet()
            
            // Extraire les valeurs et filtrer
            val bgReadings = bgReadingsList
                .mapNotNull { gv ->
                    // GV type a la propriété 'value' de type Double
                    val value = gv.value
                    if (value > 39.0) value else null
                }
            
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
            
            // 2. Détection épisodes hypo (groupements contigus < 70)
            var hypo_count = 0
            var inHypo = false
            for (bg in bgReadings) {
                if (bg < 70.0 && !inHypo) {
                    hypo_count++
                    inHypo = true
                } else if (bg >= 70.0) {
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
                perf.tir_above_250 > 20 -> {  // Hyper sévère prolongée
                    adjustment *= 1.30
                    reasons.add("Hyper sévère >250: ${perf.tir_above_250.toInt()}% → factor × 1.30")
                }
                perf.tir_above_180 > 50 -> {  // Plus de la moitié en hyper
                    adjustment *= 1.25
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.25")
                }
                perf.tir_above_180 > 40 -> {
                    adjustment *= 1.20
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.20")
                }
                perf.tir_above_180 > 30 -> {
                    adjustment *= 1.15
                    reasons.add("Hyper ${perf.tir_above_180.toInt()}% → factor × 1.15")
                }
                perf.tir_above_180 > 20 -> {
                    adjustment *= 1.08
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
        
        val previousFactor = globalFactor
        var targetFactor = globalFactor * adjustment

        if (isOptimal) {
            // EMA douce vers 1.0 (decay de 5% par analyse)
            // Si tout va bien, on relaxe doucement vers la neutralité
            targetFactor = 1.0
            val decayAlpha = 0.05
            globalFactor = (targetFactor * decayAlpha + globalFactor * (1 - decayAlpha))
            reasons.add("Performance optimale → convergence vers 1.0")
        } else {
            // 🎯 Calcul du nouveau facteur avec EMA smoothing
            // FIX: Logic was pulling towards adjustment multiplier instead of multiplying by it.
            // New Logic: Target = Current * Adjustment
            
            val alpha = 0.40  // Faster adaptation (was 0.15)
            
            // Apply EMA: New = (Target * alpha) + (Old * (1-alpha))
            globalFactor = (targetFactor * alpha + globalFactor * (1 - alpha)).coerceIn(0.6, 2.5)
        }
        
        val reasonsStr = reasons.joinToString(", ")
        log.info(LTag.APS, "UnifiedReactivityLearner: Nouveau globalFactor = ${"%.3f".format(globalFactor)} | $reasonsStr")
        
        // 📊 Capture snapshot for rT display
        val now = dateUtil.now()
        lastAnalysis = AnalysisSnapshot(
            timestamp = now,
            tir70_180 = perf.tir70_180,
            cv_percent = perf.cv_percent,
            hypo_count = perf.hypo_count,
            globalFactor = globalFactor,
            previousFactor = previousFactor,
            adjustmentReason = reasonsStr
        )
        
        save()
        exportToCSV(perf, reasonsStr)
        
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
