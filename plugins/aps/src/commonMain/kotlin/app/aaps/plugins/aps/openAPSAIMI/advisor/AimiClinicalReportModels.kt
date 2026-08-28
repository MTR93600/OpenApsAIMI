package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class ClinicalContext(
    val bgReadings: List<Double>, // Only values needed for math
    val isfProfile: Double,
    val metrics: AdvisorMetrics // Re-use existing basic metrics
)

object AimiClinicalMetabolicReport {

    fun generateMetabolicSection(ctx: ClinicalContext): JsonObject {
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
                val fBg = 1.509 * (ln(bg).pow(1.084) - 5.381)
                val risk = 10 * fBg * fBg
                if (fBg < 0) lbgiSum += risk // Hypo risk
                else hbgiSum += risk        // Hyper risk
            }
        }
        val lbgi = if (bgValues.isNotEmpty()) lbgiSum / bgValues.size else 0.0
        val hbgi = if (bgValues.isNotEmpty()) hbgiSum / bgValues.size else 0.0

        return buildJsonObject {
            put("meanBg", mean.toInt())
            put("gmi", (3.31 + 0.02392 * mean))
            put("cv", cv) // Target < 36%
            put("lbgi", lbgi) // Low Blood Glucose Index (Risk of severe hypo)
            put("hbgi", hbgi) // High Blood Glucose Index (Risk of long hyper)
            put("stabilityScore", calculateStabilityScore(cv, lbgi))
        }
    }

    fun generateAlgoSection(ctx: ClinicalContext): JsonObject {
        // This requires analysis of recent Treatments/SMBs which we don't have in ClinicalContext yet.
        // We will output placeholder structure for the Expert system to fill if it has access to DB.

        return buildJsonObject {
            put("basalSaturation", "N/A") // Requires Pump History
            put("safetyCapsHit", "N/A")   // Requires SMB Reasons
            put("profileISF", ctx.isfProfile)
        }
    }

    fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val sumSq = values.sumOf { (it - mean).pow(2) }
        return sqrt(sumSq / values.size)
    }

    fun calculateStabilityScore(cv: Double, lbgi: Double): Int {
        // Medical Score 0-100
        // Ideal: CV < 36, LBGI < 1.1
        var score = 100.0
        if (cv > 36) score -= (cv - 36) * 1.5
        if (lbgi > 1.1) score -= (lbgi - 1.1) * 10
        return score.coerceIn(0.0, 100.0).toInt()
    }
}
