package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.context.data.AimiPhysioInputs
import kotlin.math.min

/**
 * AIMI Endometriosis Adjuster (Based on MTR "Basal-First / SMB-Sober" Strategy)
 *
 * Physiopathology Model:
 * 1. Hormonal Suppression (Continuous) -> Steady state inflammation -> Mild Basal Boost (+5%), SMB Neutral.
 * 2. Pain Flare (Acute) -> High Stress/Cortisol -> Ramp up Basal (up to limits), Dampen SMB (prevent stacking).
 * 3. Recovery (NSAID) -> Rapid drop in resistance -> Taper down.
 *
 * Safety:
 * - If BG < 120, disables all aggression.
 * - Caps multipliers to safe limits.
 */
class EndometriosisAdjuster(
    private val preferences: Preferences,
    private val logger: AAPSLogger
) {

    data class EndoFactors(
        val basalMult: Double = 1.0,
        val isfMult: Double = 1.0,
        val smbMult: Double = 1.0,
        val reason: String = ""
    )

    fun calculateFactors(bg: Double, delta: Double): EndoFactors {
        if (!preferences.get(BooleanKey.AimiEndometriosisEnable)) {
            return EndoFactors()
        }

        // 1. GLOBAL SAFETY (Hypo protection)
        // If we are touching hypo territory, kill all boosts immediately regardless of inflammation.
        if (bg < 85.0) {
            return EndoFactors(reason = "Endo: Safety Low (BG<85)")
        }

        var basalMult = 1.0
        var smbMult = 1.0
        var isfMult = 1.0
        val reasons = ArrayList<String>()

        // 2. STATIC CONTEXT (Suppression - Chronic)
        // Active as long as safely above hypo (BG > 85) to maintain steady states
        if (preferences.get(BooleanKey.AimiEndometriosisHormonalSuppression)) {
            basalMult = 1.05 // +5% basal
            isfMult = 0.95   // Slightly stronger ISF
            smbMult = 0.95   // Slightly cautious SMB
            reasons.add("Suppression")
        }

        // 3. DYNAMIC CONTEXT (Pain Flare - Acute)
        // Only active if clearly safe (BG > 110) to avoid crashing a recovery
        if (preferences.get(BooleanKey.AimiEndometriosisPainFlare)) {
            if (bg > 110.0) {
                val maxBasalMult = preferences.get(DoubleKey.AimiEndometriosisBasalMult)
                val dampening = preferences.get(DoubleKey.AimiEndometriosisSmbDampen)

                // Apply aggressive factors
                // If suppression was already on, we take the MAX of basal boosts (don't stack excessively)
                basalMult = listOf(basalMult, maxBasalMult.coerceIn(1.0, 1.5)).maxOrNull() ?: 1.0
                
                // SMB is dampened (Safety priority) - Apply the lowest multiplier
                smbMult = listOf(smbMult, dampening.coerceIn(0.0, 1.0)).minOrNull() ?: 1.0
                
                // ISF follows Basal resistance (Stronger = Lower value)
                // We combine the resistance factor
                isfMult = (1.0 / basalMult).coerceIn(0.7, 1.0)
                
                reasons.add("PainFlare")
            } else {
                reasons.add("PainFlare(Paused:BG<110)")
            }
        }

        // 4. Final Safety Clamps (Rapid Drop)
        // If dropping fast, kill SMBs even if flare active
        if (delta < -5.0) {
            smbMult = 0.0
            reasons.add("RapidDrop")
        }

        if (reasons.isEmpty()) {
            return EndoFactors()
        }

        return EndoFactors(
            basalMult = basalMult,
            isfMult = isfMult,
            smbMult = smbMult,
            reason = "Endo:${reasons.joinToString(",")}"
        )
    }
}
