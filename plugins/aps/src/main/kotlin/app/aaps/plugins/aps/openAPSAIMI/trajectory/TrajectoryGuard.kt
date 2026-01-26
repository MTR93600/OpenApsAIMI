package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Trajectory Guard - Phase-Space Control System
 * 
 * Analyzes insulin-glucose dynamics as geometric trajectories in phase space
 * rather than purely temporal sequences. Provides soft modulation of SMB/basal
 * decisions to guide the system toward stable orbits.
 * 
 * NOT a hard blocker - generates modulation factors and warnings.
 * 
 * Key principles:
 * - Open diverging trajectory → permit more aggressive action
 * - Closing converging trajectory → reduce intervention, allow natural convergence
 * - Tight spiral → strong damping, expand safety margins
 * - Stable orbit → minimal intervention
 * 
 * @see docs/research/PKPD_TRAJECTORY_CONTROLLER.md
 */
@Singleton
class TrajectoryGuard @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    // Store last analysis for UI display
    private var lastAnalysis: TrajectoryAnalysis? = null

    fun getLastAnalysis(): TrajectoryAnalysis? = lastAnalysis
    
    companion object {
        // Metric thresholds for classification
        const val CURVATURE_HIGH = 0.3          // Tight spiral warning
        const val CONVERGENCE_SLOW = -0.5       // Diverging threshold
        const val COHERENCE_LOW = 0.3           // Poor insulin response
        const val ENERGY_STACKING = 2.0         // Accumulation risk
        const val OPENNESS_DIVERGING = 0.7      // Wide open trajectory
        
        // Minimum history requirements (reduced for faster activation)
        const val MIN_HISTORY_STATES = 4        // 20 minutes minimum (was 6/30min)
        const val IDEAL_HISTORY_STATES = 18     // 90 minutes ideal
    }
    
    /**
     * Main analysis function - analyzes trajectory and returns modulation
     * 
     * @param history Phase-space history (ideally 60-90 minutes)
     * @param stableOrbit Target orbit definition
     * @return Complete trajectory analysis with modulation factors
     */
    fun analyzeTrajectory(
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): TrajectoryAnalysis? {
        
        // Require minimum history
        if (history.size < MIN_HISTORY_STATES) {
            aapsLogger.debug(LTag.APS, "TrajectoryGuard: Insufficient history (${history.size} states, need $MIN_HISTORY_STATES)")
            return null
        }
        
        // Calculate metrics
        val metrics = TrajectoryMetricsCalculator.calculateAll(history, stableOrbit)
            ?: return null
        
        // Classify trajectory type
        val classification = classifyTrajectory(metrics, history, stableOrbit)
        
        // Compute modulation factors
        val modulation = computeModulation(classification, metrics, history)
        
        // Generate warnings
        val warnings = generateWarnings(metrics, classification, history)
        
        // Calculate distance to orbit and convergence estimate
        val orbitDistance = history.last().distanceTo(stableOrbit.toPhaseSpaceState())
        val convergenceTime = TrajectoryMetricsCalculator.estimateConvergenceTime(history, stableOrbit)
        
        val analysis = TrajectoryAnalysis(
            classification = classification,
            metrics = metrics,
            modulation = modulation,
            warnings = warnings,
            stableOrbitDistance = orbitDistance,
            predictedConvergenceTime = convergenceTime
        )
        
        // Log analysis
        aapsLogger.debug(LTag.APS, "TrajectoryGuard: ${classification.emoji()} ${classification.name} " +
                "κ=%.3f v_conv=%.2f ρ=%.2f E=%.2f Θ=%.2f".format(
                    metrics.curvature, metrics.convergenceVelocity, 
                    metrics.coherence, metrics.energyBalance, metrics.openness
                ))
        
        if (modulation.isSignificant()) {
            aapsLogger.info(LTag.APS, "TrajectoryGuard: Modulation active - ${modulation.reason}")
        }
        
        warnings.forEach { warning ->
            when (warning.severity) {
                WarningSeverity.CRITICAL -> aapsLogger.error(LTag.APS, "TrajectoryGuard CRITICAL: ${warning.message}")
                WarningSeverity.HIGH -> aapsLogger.warn(LTag.APS, "TrajectoryGuard: ${warning.message}")
                WarningSeverity.MEDIUM -> aapsLogger.info(LTag.APS, "TrajectoryGuard: ${warning.message}")
                WarningSeverity.LOW -> aapsLogger.debug(LTag.APS, "TrajectoryGuard: ${warning.message}")
            }
        }
        
        return analysis.also { lastAnalysis = it }
    }
    
    /**
     * Classify trajectory into geometric type
     */
    private fun classifyTrajectory(
        metrics: TrajectoryMetrics,
        history: List<PhaseSpaceState>,
        stableOrbit: StableOrbit
    ): TrajectoryType {
        
        // Priority 1: Tight spiral (over-correction risk) - CRITICAL
        if (metrics.curvature > CURVATURE_HIGH && 
            metrics.energyBalance > ENERGY_STACKING) {
            return TrajectoryType.TIGHT_SPIRAL
        }
        
        // Priority 2: Stable orbit achieved
        if (stableOrbit.contains(history.last()) && 
            metrics.curvature < 0.1 && 
            abs(metrics.convergenceVelocity) < 0.2) {
            return TrajectoryType.STABLE_ORBIT
        }
        
        // Priority 3: Open diverging (needs action)
        if (metrics.openness > OPENNESS_DIVERGING && 
            metrics.convergenceVelocity < CONVERGENCE_SLOW) {
            return TrajectoryType.OPEN_DIVERGING
        }
        
        // Priority 4: Closing converging (let it work)
        if (metrics.convergenceVelocity > 0.2 && 
            metrics.openness < 0.5) {
            return TrajectoryType.CLOSING_CONVERGING
        }
        
        // Default: Uncertain
        return TrajectoryType.UNCERTAIN
    }
    
    /**
     * Compute modulation factors based on trajectory classification
     * 
     * These are SOFT adjustments - not hard blocks!
     */
    private fun computeModulation(
        classification: TrajectoryType,
        metrics: TrajectoryMetrics,
        history: List<PhaseSpaceState>
    ): TrajectoryModulation {
        
        return when (classification) {
            
            TrajectoryType.OPEN_DIVERGING -> {
                // Trajectory not closing despite insulin → permit stronger action
                val dampingBoost = when {
                    metrics.coherence < COHERENCE_LOW -> 1.4  // Very poor response, likely resistance
                    metrics.openness > 0.85 -> 1.3            // Extremely open
                    else -> 1.2                               // Moderately open
                }
                
                TrajectoryModulation(
                    smbDamping = dampingBoost,
                    intervalStretch = 1.0,          // No delay needed
                    basalPreference = 0.2,          // Prefer bolus for acute action
                    safetyMarginExpand = 0.95,      // Slightly tighter margins OK
                    reason = "Trajectory diverging, need stronger action (coherence=${"%.2f".format(metrics.coherence)})"
                )
            }
            
            TrajectoryType.TIGHT_SPIRAL -> {
                // High curvature + energy accumulation → HIGH RISK
                val dampingStrength = when {
                    metrics.energyBalance > 3.5 -> 0.3   // SEVERE stacking
                    metrics.energyBalance > 2.5 -> 0.5   // Strong stacking
                    else -> 0.7                          // Moderate correction risk
                }
                
                TrajectoryModulation(
                    smbDamping = dampingStrength,
                    intervalStretch = 1.8,           // Wait much longer
                    basalPreference = 0.85,          // Strongly prefer temp basal
                    safetyMarginExpand = 1.3,        // Expand safety margins significantly
                    reason = "Trajectory compressed - over-correction risk (E=${"%.2f".format(metrics.energyBalance)}U, κ=${"%.3f".format(metrics.curvature)})"
                )
            }
            
            TrajectoryType.CLOSING_CONVERGING -> {
                // System returning to target - gentle damping, let it converge
                val dampingLevel = when {
                    metrics.convergenceVelocity > 1.0 -> 0.7  // Fast convergence, be cautious
                    metrics.convergenceVelocity > 0.5 -> 0.85 // Moderate convergence
                    else -> 0.9                               // Slow but steady
                }
                
                TrajectoryModulation(
                    smbDamping = dampingLevel,
                    intervalStretch = 1.3,           // Slightly longer wait
                    basalPreference = 0.5,           // Neutral
                    safetyMarginExpand = 1.1,        // Slight caution
                    reason = "Trajectory closing naturally (v_conv=${"+%.2f".format(metrics.convergenceVelocity)} mg/dL/min)"
                )
            }
            
            TrajectoryType.STABLE_ORBIT -> {
                // Perfect! Minimal intervention
                TrajectoryModulation(
                    smbDamping = 1.0,
                    intervalStretch = 1.0,
                    basalPreference = 0.5,
                    safetyMarginExpand = 1.0,
                    reason = "Stable orbit maintained - continue current strategy"
                )
            }
            
            TrajectoryType.UNCERTAIN -> {
                // Not enough data or ambiguous → neutral modulation
                TrajectoryModulation.NEUTRAL
            }
        }
    }
    
    /**
     * Generate warnings based on trajectory analysis
     */
    private fun generateWarnings(
        metrics: TrajectoryMetrics,
        classification: TrajectoryType,
        history: List<PhaseSpaceState>
    ): List<TrajectoryWarning> {
        
        val warnings = mutableListOf<TrajectoryWarning>()
        
        // Warning 1: Insulin stacking detected
        if (metrics.energyBalance > ENERGY_STACKING && metrics.curvature > 0.2) {
            val severity = when {
                metrics.energyBalance > 4.0 -> WarningSeverity.CRITICAL
                metrics.energyBalance > 3.0 -> WarningSeverity.HIGH
                else -> WarningSeverity.MEDIUM
            }
            
            warnings.add(TrajectoryWarning(
                severity = severity,
                type = "INSULIN_STACKING",
                message = "Multiple corrections accumulating (E=${"%.2f".format(metrics.energyBalance)}U) - hypo risk in 60-90 min",
                suggestedAction = "Reduce SMB, prefer temp basal, monitor closely"
            ))
        }
        
        // Warning 2: Poor insulin-glucose coherence (resistance suspected)
        if (metrics.coherence < COHERENCE_LOW && history.last().iob > 2.0) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.HIGH,
                type = "LOW_COHERENCE",
                message = "IOB ${" %.2f".format(history.last().iob)}U present but BG not responding (ρ=${"%.2f".format(metrics.coherence)})",
                suggestedAction = "Possible insulin resistance, site failure, or illness - check pump site and insulin quality"
            ))
        }
        
        // Warning 3: Persistent divergence (slow drift)
        if (metrics.openness > 0.75 && 
            metrics.convergenceVelocity < -0.3 &&
            history.last().bg > 140.0) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.MEDIUM,
                type = "PERSISTENT_DIVERGENCE",
                message = "BG drifting upward (${history.last().bg.toInt()} mg/dL) despite IOB - trajectory not closing (Θ=${"%.2f".format(metrics.openness)})",
                suggestedAction = "Consider additional correction if safe, or increase basal rate"
            ))
        }
        
        // Warning 4: PRE_ONSET stacking risk
        if (history.last().pkpdStage == ActivityStage.RISING &&
            history.last().iob > 1.5 &&
            metrics.curvature > 0.15) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.LOW,
                type = "PRE_ONSET_COMPRESSION",
                message = "Fresh IOB (${" %.2f".format(history.last().iob)}U) in PRE_ONSET but trajectory already tightening (κ=${"%.3f".format(metrics.curvature)})",
                suggestedAction = "Caution: avoid additional bolus, trajectory will tighten further as insulin activates"
            ))
        }
        
        // Warning 5: Paradoxical response (BG rising despite high insulin activity)
        if (metrics.coherence < -0.3 && history.last().insulinActivity > 1.5) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.HIGH,
                type = "PARADOXICAL_RESPONSE",
                message = "BG rising despite high insulin activity (${" %.2f".format(history.last().insulinActivity)} U/hr) - negative coherence (ρ=${"%.2f".format(metrics.coherence)})",
                suggestedAction = "Check for illness, stress, or pump failure - consider testing ketones"
            ))
        }
        
        // Warning 6: Excellent control achieved
        if (classification == TrajectoryType.STABLE_ORBIT && metrics.healthScore > 0.85) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.LOW,
                type = "STABLE_ORBIT_ACHIEVED",
                message = "Excellent glycemic control - stable orbit maintained (health=${"%.0f".format(metrics.healthScore * 100)}%)",
                suggestedAction = "Continue current strategy"
            ))
        }
        
        return warnings
    }
}
    

