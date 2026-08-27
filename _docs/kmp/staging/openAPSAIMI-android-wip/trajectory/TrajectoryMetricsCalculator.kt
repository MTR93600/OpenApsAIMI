package app.aaps.plugins.aps.openAPSAIMI.trajectory

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Trajectory Metrics Calculator
 * 
 * Computes geometric properties of phase-space trajectories:
 * - Curvature (κ): trajectory turning rate
 * - Convergence velocity (v_conv): approach/divergence from stable orbit
 * - Coherence (ρ): insulin-glucose correlation
 * - Energy balance (E): insulin accumulation vs dissipation
 * - Openness (Θ): trajectory loop closure
 * 
 * All metrics are designed to be robust to CGM noise and missing data.
 */
object TrajectoryMetricsCalculator {
    
    /**
     * Calculate all trajectory metrics from phase-space history
     * 
     * @param history Recent phase-space states (ideally 60-90 minutes)
     * @param stableOrbit Target orbit to converge to
     * @return Complete TrajectoryMetrics
     */
    fun calculateAll(
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): TrajectoryMetrics? {
        if (history.size < 3) return null // Need minimum 3 points (15 minutes)
        
        val curvature = calculateCurvature(history)
        val convergenceVelocity = calculateConvergenceVelocity(history, stableOrbit)
        val coherence = calculateCoherence(history)
        val energyBalance = calculateEnergyBalance(history, stableOrbit.targetBg)
        val openness = calculateOpenness(history, stableOrbit)
        
        return TrajectoryMetrics(
            curvature = curvature,
            convergenceVelocity = convergenceVelocity,
            coherence = coherence,
            energyBalance = energyBalance,
            openness = openness
        )
    }
    
    /**
     * Calculate trajectory curvature (κ)
     * 
     * Measures how sharply the trajectory is turning in (BG, delta) space.
     * Uses discrete approximation of curvature from consecutive point triples.
     * 
     * High κ → tight spiral (over-correction risk)
     * Low κ → gentle arc (good control)
     * 
     * @return κ ∈ [0, ∞), typical range [0, 0.5]
     */
    fun calculateCurvature(history: List<PhaseSpaceState>): Double {
        if (history.size < 6) return 0.0 // Need at least 30 min for reliable curvature
        
        // Work in 2D (BG, delta) space for simplicity
        val path = history.takeLast(12).map { it.to2DPoint() } // Last 60 min
        
        var totalCurvature = 0.0
        var count = 0
        
        // Calculate curvature using Menger curvature formula for each triple of points
        for (i in 1 until path.size - 1) {
            val p0 = path[i - 1]
            val p1 = path[i]
            val p2 = path[i + 1]
            
            // Menger curvature: κ = 4 * Area(triangle) / (a * b * c)
            // where a, b, c are triangle side lengths
            val a = p0.distanceTo(p1)
            val b = p1.distanceTo(p2)
            val c = p2.distanceTo(p0)
            
            if (a < 1e-6 || b < 1e-6 || c < 1e-6) continue // Skip degenerate triangles
            
            // Area using Heron's formula
            val s = (a + b + c) / 2.0
            val areaSq = s * (s - a) * (s - b) * (s - c)
            if (areaSq <= 0.0) continue
            
            val area = sqrt(areaSq)
            val k = 4.0 * area / (a * b * c)
            
            // Normalize by typical BG scale to get dimensionless curvature
            // Divide by 100 to map typical values to [0, 0.5] range
            totalCurvature += k / 100.0
            count++
        }
        
        return if (count > 0) (totalCurvature / count).coerceIn(0.0, 2.0) else 0.0
    }
    
    /**
     * Calculate convergence velocity (v_conv)
     * 
     * Measures rate of approach to (or divergence from) stable orbit.
     * 
     * v_conv > 0 → converging (good)
     * v_conv ≈ 0 → stable or cycling
     * v_conv < 0 → diverging (needs action)
     * 
     * @return v_conv in mg/dL per minute (can be negative)
     */
    fun calculateConvergenceVelocity(
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): Double {
        if (history.size < 2) return 0.0
        
        val current = history.last()
        val previous = history[max(0, history.size - 3)] // 10-15 min ago
        
        val target = stableOrbit.toPhaseSpaceState()
        
        // Distance in phase space (weighted)
        val distCurrent = current.distanceTo(target)
        val distPrevious = previous.distanceTo(target)
        
        // Time difference in minutes
        val timeDiffMin = (current.timestamp - previous.timestamp) / 60000.0
        if (timeDiffMin < 1.0) return 0.0
        
        // Rate of change of distance (positive = getting closer)
        val velocityRaw = (distPrevious - distCurrent) / timeDiffMin
        
        // Map to mg/dL/min scale for interpretability
        return (velocityRaw * 40.0).coerceIn(-5.0, 5.0) // Clip to reasonable range
    }
    
    /**
     * Calculate insulin-glucose coherence (ρ)
     * 
     * Measures correlation between insulin activity and BG response.
     * High insulin activity should lead to falling BG (negative correlation with delta).
     * 
     * ρ → 1: Perfect response (insulin working as expected)
     * ρ → 0: No correlation (random)
     * ρ → -1: Paradoxical (BG rising despite insulin - resistance!)
     * 
     * @return ρ ∈ [-1, 1]
     */
    fun calculateCoherence(history: List<PhaseSpaceState>): Double {
        if (history.size < 12) return 0.5 // Need ~60 min for reliable correlation
        
        val recent = history.takeLast(12) // Last 60 minutes
        
        // Extract time series
        val activitySeries = recent.map { it.insulinActivity }
        val deltaSeries = recent.map { it.bgDelta }
        
        // Expected: high activity → negative delta (BG falling)
        // So we correlate activity with (-delta)
        val negativeDeltaSeries = deltaSeries.map { -it }
        
        // Pearson correlation coefficient
        val correlation = pearsonCorrelation(activitySeries, negativeDeltaSeries)
        
        return correlation.coerceIn(-1.0, 1.0)
    }
    
    /**
     * Calculate energy balance (E)
     * 
     * Tracks cumulative "control energy":
     * - Energy IN: insulin injected (IOB increases)
     * - Energy OUT: BG corrected (descent from above target)
     * 
     * E > 0: Insulin accumulating (stacking risk)
     * E ≈ 0: Balanced
     * E < 0: Under-acting
     * 
     * @return E in "insulin unit equivalents"
     */
    fun calculateEnergyBalance(
        history: List<PhaseSpaceState>,
        targetBg: Double
    ): Double {
        if (history.size < 2) return 0.0
        
        var energyIn = 0.0
        var energyOut = 0.0
        
        for (i in 1 until history.size) {
            val current = history[i]
            val previous = history[i - 1]
            
            // Energy IN: IOB increase (new insulin delivered)
            val iobIncrease = max(0.0, current.iob - previous.iob)
            energyIn += iobIncrease
            
            // Energy OUT: BG descent when above target
            if (previous.bg > targetBg + 10) {
                val bgDrop = max(0.0, previous.bg - current.bg)
                // Convert to "insulin equivalent" using rough ISF ~40 mg/dL/U
                energyOut += bgDrop / 40.0
            }
        }
        
        return (energyIn - energyOut).coerceIn(-10.0, 10.0)
    }
    
    /**
     * Calculate trajectory openness (Θ)
     * 
     * Measures whether the trajectory forming a closed loop (converging)
     * or staying open (diverging or unstable).
     * 
     * Θ → 0: Tightly closed loop (excellent convergence)
     * Θ → 1: Wide open (diverging or poor control)
     * 
     * @return Θ ∈ [0, 1]
     */
    fun calculateOpenness(
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): Double {
        if (history.size < 6) return 0.5 // Default: uncertain
        
        val recentPath = history.takeLast(12) // Last 60 min
        val target = stableOrbit.toPhaseSpaceState()
        
        // Measure deviation over the path
        val start = recentPath.first()
        val current = recentPath.last()
        
        val startDist = start.distanceTo(target)
        val currentDist = current.distanceTo(target)
        val maxDeviation = recentPath.maxOf { it.distanceTo(target) }
        
        if (maxDeviation < 1e-6) return 0.0 // Already at target
        
        // If we're much closer than we started, trajectory is closing
        val closure = (startDist - currentDist) / (maxDeviation + 1e-6)
        
        // Openness is inverse of closure
        val openness = 1.0 - closure.coerceIn(-0.5, 1.0)
        
        return openness.coerceIn(0.0, 1.0)
    }
    
    /**
     * Pearson correlation coefficient between two time series
     * 
     * r = Cov(X,Y) / (σ_X * σ_Y)
     * 
     * @return r ∈ [-1, 1]
     */
    private fun pearsonCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        
        val n = x.size
        val meanX = x.average()
        val meanY = y.average()
        
        var covXY = 0.0
        var varX = 0.0
        var varY = 0.0
        
        for (i in 0 until n) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            covXY += dx * dy
            varX += dx * dx
            varY += dy * dy
        }
        
        val denominator = sqrt(varX * varY)
        
        return if (denominator > 1e-9) {
            (covXY / denominator).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
    }
    
    /**
     * Estimate time to convergence based on current velocity
     * 
     * @return estimated minutes until orbit reached, or null if diverging
     */
    fun estimateConvergenceTime(
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): Int? {
        if (history.isEmpty()) return null
        
        val current = history.last()
        val target = stableOrbit.toPhaseSpaceState()
        
        val distance = current.distanceTo(target)
        val velocity = calculateConvergenceVelocity(history, stableOrbit)
        
        // If diverging, no convergence
        if (velocity <= 0.0) return null
        
        // Time = distance / velocity
        // But map back from normalized distance to minutes
        val timeMin = (distance / (velocity / 40.0)).coerceIn(0.0, 300.0)
        
        return timeMin.toInt()
    }
}
