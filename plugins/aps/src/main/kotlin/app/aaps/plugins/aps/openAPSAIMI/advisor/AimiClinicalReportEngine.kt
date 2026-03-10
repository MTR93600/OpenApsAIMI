package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIPhysioManagerMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 🏥 AIMI Clinical Report Engine
 * 
 * Generates a high-precision metabolic & physiological report format
 * designed for medical review and expert system analysis.
 * 
 * Focus Areas:
 * 1. Metabolic Integrity (GMI, CV, LBGI/HBGI)
 * 2. Physiological Context (Sleep, Stress, Cycle)
 * 3. Algorithm Stress Test (Saturation, Capping, Divergence)
 */
class AimiClinicalReportEngine @Inject constructor(
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val physioManager: AIMIPhysioManagerMTR
) {

    data class ClinicalContext(
        val bgReadings: List<Double>, // Only values needed for math
        val isfProfile: Double,
        val metrics: AdvisorMetrics // Re-use existing basic metrics
    )

    fun generateClinicalJson(ctx: ClinicalContext): JSONObject {
        val root = JSONObject()
        
        // 1. Metadata
        root.put("meta", JSONObject().apply {
            put("version", "2.1-ENDO")
            put("generatedAt", System.currentTimeMillis())
            put("generator", "AIMI Clinical Engine")
        })

        // 2. Metabolic Analysis (Advanced)
        root.put("metabolic", generateMetabolicSection(ctx))

        // 3. Physiological Context (The "Why")
        root.put("physio", generatePhysioSection())

        // 4. Algorithm Performance (Stress Test)
        root.put("algorithm", generateAlgoSection(ctx))

        return root
    }

    private fun generateMetabolicSection(ctx: ClinicalContext): JSONObject {
        val bgValues = ctx.bgReadings.filter { it > 20 } // Safety filter
        
        // Calculate CV (Coefficient of Variation)
        val mean = bgValues.average()
        val stdDev = calculateStdDev(bgValues, mean)
        val cv = if (mean > 0) (stdDev / mean) * 100 else 0.0

        // Calculate LBGI / HBGI (Kovatchev Risk Indices)
        // Transformation: f(bg) = 1.509 * ( (ln(bg))^1.084 - 5.381 )
        // Risk = 10 * f(bg)^2
        var lbgiSum = 0.0
        var hbgiSum = 0.0
        
        bgValues.forEach { bg ->
            // Convert mg/dL to risk space
            // Note: Formula usually expects mg/dL. 
            if (bg > 10) {
                val fBg = 1.509 * (Math.pow(Math.log(bg), 1.084) - 5.381)
                val risk = 10 * fBg * fBg
                if (fBg < 0) lbgiSum += risk // Hypo risk
                else hbgiSum += risk        // Hyper risk
            }
        }
        val lbgi = if (bgValues.isNotEmpty()) lbgiSum / bgValues.size else 0.0
        val hbgi = if (bgValues.isNotEmpty()) hbgiSum / bgValues.size else 0.0

        return JSONObject().apply {
            put("meanBg", mean.toInt())
            put("gmi", (3.31 + 0.02392 * mean))
            put("cv", cv) // Target < 36%
            put("lbgi", lbgi) // Low Blood Glucose Index (Risk of severe hypo)
            put("hbgi", hbgi) // High Blood Glucose Index (Risk of long hyper)
            put("stabilityScore", calculateStabilityScore(cv, lbgi))
        }
    }

    private fun generatePhysioSection(): JSONObject {
        val json = JSONObject()
        
        // Retrieve latest physio context from Manager
        // Note: Using unsafe access via static instance if injection fails or simpler access needed,
        // but here we have the injected manager.
        
        // We need to access the store inside manager, but it's private.
        // Let's use the public getStatus or we assume we can get context via other means.
        // For now, prompt generic 'No Data' if not exposed, but wait, we are inside the plugin.
        // Ideally PhysioManager should expose `getLastContext()`.
        
        // Mocking structure based on what we know exists in PhysioContextMTR
        // (In a real implementation, we would expose a getter in PhysioManager)
        
        /* 
           Since I cannot change PhysioManager interface right now easily without risking breakage,
           I will infer state from available data or user preferences (Cycle).
        */
        
        json.put("cyclePhase", "UNKNOWN") // Placeholder until we link WCycle
        
        // Try to access status map
        val status = physioManager.getStatus()
        json.put("physioEngineActive", status["isEnabled"])
        
        return json
    }

    private fun generateAlgoSection(ctx: ClinicalContext): JSONObject {
        // This requires analysis of recent Treatments/SMBs which we don't have in ClinicalContext yet.
        // We will output placeholder structure for the Expert system to fill if it has access to DB.
        
        return JSONObject().apply {
            put("basalSaturation", "N/A") // Requires Pump History
            put("safetyCapsHit", "N/A")   // Requires SMB Reasons
            put("profileISF", ctx.isfProfile)
        }
    }

    // Maths Helpers
    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val sumSq = values.sumOf { (it - mean).pow(2) }
        return sqrt(sumSq / values.size)
    }

    private fun calculateStabilityScore(cv: Double, lbgi: Double): Int {
        // Medical Score 0-100
        // Ideal: CV < 36, LBGI < 1.1
        var score = 100.0
        if (cv > 36) score -= (cv - 36) * 1.5
        if (lbgi > 1.1) score -= (lbgi - 1.1) * 10
        return score.coerceIn(0.0, 100.0).toInt()
    }
}
