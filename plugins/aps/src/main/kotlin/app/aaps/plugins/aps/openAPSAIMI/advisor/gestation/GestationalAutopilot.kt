package app.aaps.plugins.aps.openAPSAIMI.advisor.gestation

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * 🤰 AIMI Gestational Autopilot (Prototype)
 * 
 * Innovating Pregnancy Management:
 * Instead of a static "Pregnancy Mode" toggle, this engine calculates
 * specific insulin resistance factors based on the exact Gestational Week (GW/SA).
 * 
 * Logic:
 * - Input: Due Date (DPA)
 * - Output: Dynamic multipliers for Basal, ISF, CR
 */
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤰 AIMI Gestational Autopilot (Prototype)
 * ...
 */
@Singleton
class GestationalAutopilot @Inject constructor() {
    
    private val dateProvider: () -> LocalDate = { LocalDate.now() }

    data class GestationalState(
        val gestationalWeek: Double, // SA (Semaines d'Aménorrhée)
        val trimester: Int, // 1, 2, 3
        val resistanceFactor: Double, // Multiplier for Basal (e.g., 1.5 = +50%)
        val description: String,
        val targetRange: IntRange = 70..140
    )

    /**
     * Calculate current gestational state based on Due Date
     * @param dueDate Expected date of delivery (40 SA)
     */
    fun calculateState(dueDate: LocalDate): GestationalState {
        val today = dateProvider()
        val daysUntilDue = ChronoUnit.DAYS.between(today, dueDate)
        
        // Standard pregnancy is 280 days (40 weeks)
        val daysPregnant = 280 - daysUntilDue
        val weeksPregnant = daysPregnant / 7.0
        
        // Clamp for safety (Post-term or Pre-conception)
        val sa = weeksPregnant.coerceIn(0.0, 42.0)
        
        val trimester = when {
            sa < 14 -> 1
            sa < 28 -> 2
            else -> 3
        }
        
        // 🧬 THE INNOVATION: Dynamic Resistance Curve
        // Model based on standard T1D pregnancy insulin needs
        val factor = when {
            // T1: Increased Sensitivity (Risk of Hypo)
            // Needs drop by ~10-20% around week 8-12
            sa < 14 -> {
                // Dip at week 9-11
                if (sa in 8.0..12.0) 0.85 else 0.95
            }
            
            // T2: Linear Increase (Placental Growth)
            // Rises from 1.0 to ~1.4
            sa < 28 -> {
                val progress = (sa - 14) / (28 - 14) // 0.0 to 1.0
                1.0 + (progress * 0.4) 
            }
            
            // T3: Exponential/Plateau (Max Resistance)
            // Peaks around week 36, then slight drop
            sa < 36 -> {
                val progress = (sa - 28) / (36 - 28)
                1.4 + (progress * 0.4) // Max 1.8 (+80% baseline)
            }
            
            // Term: Slight drop (Placental aging)
            else -> 1.7
        } 
        
        // Description logic
        val desc = when {
            factor < 1.0 -> "T1 Sensitivity (Protect Hypo)"
            factor > 1.5 -> "T3 High Resistance (Aggressive)"
            daysUntilDue < 3 -> "Near Term (Be Ready)"
            else -> "T${trimester} Progressive"
        }

        return GestationalState(
            gestationalWeek = sa,
            trimester = trimester,
            resistanceFactor = Math.round(factor * 100) / 100.0,
            description = desc
        )
    }

    /**
     * Recommends profile adjustments
     */
    fun getProfileMultipliers(state: GestationalState): Map<String, Double> {
        val f = state.resistanceFactor
        // Basal increases with resistance
        // ISF decreases with resistance (inverse)
        // CR decreases with resistance (inverse) - stronger ratio
        return mapOf(
            "basal" to f,
            "isf" to (1.0 / f),
            "cr" to (1.0 / f)
        )
    }
}
