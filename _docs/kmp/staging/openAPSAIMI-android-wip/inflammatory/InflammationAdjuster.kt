package app.aaps.plugins.aps.openAPSAIMI.inflammatory

import app.aaps.plugins.aps.openAPSAIMI.wcycle.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.VerneuilStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleDefaults

/**
 * 🏥 Inflammation Adjuster
 * Handles adjustments for Inflammatory and Autoimmune diseases (Verneuil, Thyroid, etc.)
 * independent of the Menstrual Cycle (WCycle).
 * 
 * Logic decoupled from WCycle to support male users.
 */
class InflammationAdjuster(
    private val prefs: WCyclePreferences // We reuse WCyclePreferences for now as they store the keys, 
                                         // eventually these should be migrated to a dedicated Preferences class if needed.
) {

    data class InflammationResult(
        val basalMultiplier: Double = 1.0,
        val smbMultiplier: Double = 1.0,
        val isfMultiplier: Double = 1.0, // Future use
        val reason: String = ""
    )

    fun getAdjustments(): InflammationResult {
        // Verneuil Logic
        val verneuilStatus = prefs.verneuil()
        val (vBasal, vSmb) = WCycleDefaults.verneuilBump(verneuilStatus)
        
        // Thyroid Logic
        val thyroidStatus = prefs.thyroid()
        val tBasal = 1.0 
        val tSmb = 1.0 // Thyroid dampening was removed in WCycleAdjuster, keeping it neutral here too.

        // Combine
        val finalBasal = vBasal * tBasal
        val finalSmb = vSmb * tSmb
        
        val reasonParts = mutableListOf<String>()
        if (verneuilStatus != VerneuilStatus.NONE) reasonParts.add("Verneuil:${verneuilStatus.name}")
        if (thyroidStatus != ThyroidStatus.EUTHYROID) reasonParts.add("Thyroid:${thyroidStatus.name}")
        
        return InflammationResult(
            basalMultiplier = finalBasal,
            smbMultiplier = finalSmb,
            reason = if(reasonParts.isNotEmpty()) "🏥 Inflam:[${reasonParts.joinToString(",")}]" else ""
        )
    }
}
