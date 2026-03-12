import kotlin.math.*

// Mock Objects for Simulation
data class State(
    val bg: Double,
    val bgVelocity: Double,
    val iob: Double,
    val estimatedSI: Double = 0.05, // Higher for better response
    val estimatedRa: Double = 0.0,
    val hour: Int = 12,
    val steps: Int = 0,
    val hr: Int = 70,
    val rhr: Int = 60
)

class DawnGuardSim {
    var lastRa = 0.0
    var pRa = 1.0
    val p1 = 0.015
    val bgTarget = 100.0

    fun runScenario(name: String, hours: List<Int>, bgs: List<Double>, velocities: List<Double>, steps: List<Int>, hrs: List<Int>, useDawnGuard: Boolean): Double {
        println("\n=== Scenario: $name (DawnGuard: $useDawnGuard) ===")
        var currentIob = 0.0
        var totalInsulin = 0.0
        lastRa = 0.0
        pRa = 1.0

        for (i in bgs.indices) {
            val state = State(bgs[i], velocities[i], currentIob, hour = hours[i], steps = steps[i], hr = hrs[i])
            
            // 1. PSE (Learning Ra)
            val expectedNaturalDelta = - (p1 + (state.estimatedSI * state.iob)) * (state.bg - bgTarget)
            val innovation = state.bgVelocity - (expectedNaturalDelta + lastRa)
            
            // Dawn Guard Learning Dampening
            val isDawnWindow = state.hour in 5..9
            val isLowActivity = state.steps < 200
            val shouldDampen = useDawnGuard && isDawnWindow && isLowActivity
            
            val rVariance = if (shouldDampen) 10.0 else 2.0 // Trust CGM less if it's dawn + still
            val qRa = if (innovation > 3.0) 5.0 else if (innovation > 1.0) 0.5 else 0.2
            
            pRa += qRa
            val kRa = pRa / (pRa + rVariance)
            val currentRa = lastRa + (kRa * innovation)
            val capRa = if (shouldDampen) 1.5 else 3.0 // 50% Cap
            lastRa = currentRa.coerceIn(0.0, capRa)

            // 2. MPC (Simplified Dosing)
            // If Ra is high, dose is aggressive.
            // If dawn guard enabled, insulin cost is higher.
            val insulinCost = if (shouldDampen) 100.0 else 20.0
            val errorCost = (state.bg - bgTarget).coerceAtLeast(0.0).pow(2)
            
            // Simple dose heuristic mirroring MPC behavior
            var dose = (lastRa * 0.1 + (state.bg - bgTarget) * 0.01) / (insulinCost * 0.1)
            dose = dose.coerceIn(0.0, 1.0)
            
            currentIob += dose
            totalInsulin += dose
            currentIob *= 0.90 // Decay
            
            // println("Tick $i: BG=${state.bg}, Ra=${"%.2f".format(lastRa)}, Dose=${"%.2f".format(dose)}, IOB=${"%.2f".format(currentIob)}")
        }
        println("TOTAL INSULIN: ${"%.2f".format(totalInsulin)} U")
        return totalInsulin
    }
}

fun main() {
    val sim = DawnGuardSim()
    
    // Scenario A: Morning Cortisol Spike (BG 100 -> 180 over 8 ticks/40min)
    val bgsA = listOf(110.0, 125.0, 140.0, 155.0, 170.0, 180.0, 185.0, 190.0)
    val velsA = listOf(3.0, 3.0, 3.0, 3.0, 3.0, 2.0, 1.0, 1.0)
    val hoursA = List(8) { 7 } // 7 AM
    val stepsA = List(8) { 20 }
    val hrsA = List(8) { 75 }
    
    // Scenario B: Real Meal (Same BG Rise but in the afternoon)
    val hoursB = List(8) { 13 } // 1 PM
    val stepsB = List(8) { 400 } // Active
    val hrsB = List(8) { 85 }

    println("--- BASELINE (Current V3) ---")
    val cA = sim.runScenario("Dawn Cortisol", hoursA, bgsA, velsA, stepsA, hrsA, false)
    val lB = sim.runScenario("Lunch Meal", hoursB, bgsA, velsA, stepsB, hrsB, false)
    
    println("\n--- OPTIMIZED (Dawn Guard) ---")
    val cA_opt = sim.runScenario("Dawn Cortisol", hoursA, bgsA, velsA, stepsA, hrsA, true)
    val lB_opt = sim.runScenario("Lunch Meal", hoursB, bgsA, velsA, stepsB, hrsB, true)
    
    println("\n--- COMPARISON ---")
    println("Reduction for Cortisol: ${"%.1f".format((1.0 - cA_opt/cA) * 100)}%")
    println("Impact on Lunch (Safety Check): ${"%.1f".format((1.0 - lB_opt/lB) * 100)}% (Should be 0.0%)")
}

main()
