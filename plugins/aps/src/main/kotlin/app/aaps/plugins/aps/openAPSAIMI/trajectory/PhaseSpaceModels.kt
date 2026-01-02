package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Phase-Space Trajectory Models for PKPD Control
 * 
 * This package implements trajectory-based control theory for insulin delivery,
 * moving from temporal PKPD modeling to geometric phase-space analysis.
 * 
 * Key insight: The quality of an insulin decision is measured by its ability
 * to guide the system back to a stable orbit, not by instantaneous optimality.
 * 
 * @see docs/research/PKPD_TRAJECTORY_CONTROLLER.md
 * @see docs/research/TRAJECTORY_SIGNATURE_CLASSIFICATION.md
 */

/**
 * Represents a single point in phase space at a given moment
 * 
 * Phase space coordinates: Ψ = (BG, dBG/dt, InsulinActivity, PKPD_Stage, Time)
 */
data class PhaseSpaceState(
    val timestamp: Long,                    // milliseconds (epoch)
    val bg: Double,                         // mg/dL
    val bgDelta: Double,                    // mg/dL/5min (first derivative)
    val bgAccel: Double,                    // mg/dL/5min² (second derivative)
    val insulinActivity: Double,            // U/hr equivalent 
    val iob: Double,                        // U (total active insulin)
    val pkpdStage: InsulinActivityStage,   // PKPD temporal stage
    val timeSinceLastBolus: Int,           // minutes
    val cob: Double = 0.0,                  // g (optional, for meal context)
    val tissueDelay: Double = 0.0           // estimated insulin-tissue lag (0-1)
) {
    /**
     * Calculate Euclidean distance to another state in phase space
     * Uses weighted dimensions to normalize different units
     */
    fun distanceTo(other: PhaseSpaceState, weights: PhaseSpaceWeights = PhaseSpaceWeights.DEFAULT): Double {
        val bgDiff = (bg - other.bg) / weights.bgNorm
        val deltaDiff = (bgDelta - other.bgDelta) / weights.deltaNorm
        val activityDiff = (insulinActivity - other.insulinActivity) / weights.activityNorm
        
        return sqrt(bgDiff.pow(2) + deltaDiff.pow(2) + activityDiff.pow(2))
    }
    
    /**
     * Project this state onto 2D (BG, delta) plane for visualization
     */
    fun to2DPoint(): Point2D = Point2D(bg, bgDelta)
}

/**
 * Normalization weights for phase-space distance calculations
 */
data class PhaseSpaceWeights(
    val bgNorm: Double,         // Typical BG scale (~40 mg/dL = 1σ)
    val deltaNorm: Double,      // Typical delta scale (~5 mg/dL/5min = 1σ)
    val activityNorm: Double    // Typical activity scale (~2 U/hr = 1σ)
) {
    companion object {
        val DEFAULT = PhaseSpaceWeights(
            bgNorm = 40.0,
            deltaNorm = 5.0,
            activityNorm = 2.0
        )
    }
}

/**
 * 2D point for geometric calculations
 */
data class Point2D(val x: Double, val y: Double) {
    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * 2D vector for trajectory analysis
 */
data class Vector2D(val dx: Double, val dy: Double) {
    val magnitude: Double get() = sqrt(dx * dx + dy * dy)
    
    fun normalized(): Vector2D {
        val mag = magnitude
        return if (mag > 1e-9) Vector2D(dx / mag, dy / mag) else Vector2D(0.0, 0.0)
    }
    
    /**
     * Dot product with another vector
     */
    fun dot(other: Vector2D): Double = dx * other.dx + dy * other.dy
    
    /**
     * Angle between this vector and another (radians)
     */
    fun angleTo(other: Vector2D): Double {
        val mag1 = magnitude
        val mag2 = other.magnitude
        if (mag1 < 1e-9 || mag2 < 1e-9) return 0.0
        
        val cosAngle = (dot(other) / (mag1 * mag2)).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(cosAngle)
    }
    
    companion object {
        fun between(from: Point2D, to: Point2D) = Vector2D(to.x - from.x, to.y - from.y)
    }
}

/**
 * Quantitative trajectory metrics computed from phase-space history
 */
data class TrajectoryMetrics(
    val curvature: Double,              // κ: How sharply the trajectory is turning (0=straight, >0.3=tight spiral)
    val convergenceVelocity: Double,    // v_conv: Rate of approach to stable orbit (>0=converging, <0=diverging)
    val coherence: Double,              // ρ: Insulin-glucose response correlation (-1 to 1, >0.6=good)
    val energyBalance: Double,          // E: Insulin injected vs BG corrected (>0=accumulating, <0=under-acting)
    val openness: Double,               // Θ: How open the trajectory is (0=closed loop, 1=wide open)
    
    // Derived indicators
    val isConverging: Boolean = convergenceVelocity > 0,
    val isDiverging: Boolean = convergenceVelocity < -0.3,
    val isTightSpiral: Boolean = curvature > 0.3 && energyBalance > 2.0,
    val hasLowCoherence: Boolean = coherence < 0.3,
    val isStable: Boolean = openness < 0.3 && abs(convergenceVelocity) < 0.2
) {
    /**
     * Overall trajectory health score (0-1, 1=excellent)
     */
    val healthScore: Double
        get() {
            val convergenceFactor = (convergenceVelocity + 0.5).coerceIn(0.0, 1.0)
            val coherenceFactor = (coherence + 1.0) / 2.0  // Map [-1,1] to [0,1]
            val opennessFactor = 1.0 - openness.coerceIn(0.0, 1.0)
            val energyFactor = (1.0 - (energyBalance / 5.0)).coerceIn(0.0, 1.0)
            
            return (convergenceFactor * 0.3 + coherenceFactor * 0.3 + opennessFactor * 0.2 + energyFactor * 0.2)
                .coerceIn(0.0, 1.0)
        }
}

/**
 * Classification of trajectory geometric type
 */
enum class TrajectoryType {
    OPEN_DIVERGING,        // ↗️ System escaping, insufficient insulin
    CLOSING_CONVERGING,    // ↗️→↘️ Returning to target
    TIGHT_SPIRAL,          // 🌀 Over-correction risk
    STABLE_ORBIT,          // ⭕ Optimal control achieved
    UNCERTAIN;             // ? Insufficient data or ambiguous
    
    /**
     * User-friendly description
     */
    fun description(): String = when (this) {
        OPEN_DIVERGING -> "Trajectory diverging - BG not controlled"
        CLOSING_CONVERGING -> "Trajectory closing - returning to target"
        TIGHT_SPIRAL -> "Trajectory compressed - over-correction risk"
        STABLE_ORBIT -> "Stable orbit maintained"
        UNCERTAIN -> "Trajectory unclear - need more data"
    }
    
    /**
     * Emoji representation for logs
     */
    fun emoji(): String = when (this) {
        OPEN_DIVERGING -> "↗️"
        CLOSING_CONVERGING -> "🔄"
        TIGHT_SPIRAL -> "🌀"
        STABLE_ORBIT -> "⭕"
        UNCERTAIN -> "❓"
    }
}

/**
 * Modulation factors to apply to SMB/basal decisions based on trajectory
 * 
 * These are soft adjustments, not hard blocks.
 * Multiply existing thresholds/decisions by these factors.
 */
data class TrajectoryModulation(
    val smbDamping: Double,        // Multiply SMB by this (0.5-1.5)
    val intervalStretch: Double,    // Multiply interval by this (1.0-2.0)
    val basalPreference: Double,    // 0=prefer SMB, 1=prefer temp basal
    val safetyMarginExpand: Double, // Multiply safety margins (0.9-1.3)
    val reason: String              // Human-readable explanation
) {
    companion object {
        /**
         * Neutral modulation - no changes
         */
        val NEUTRAL = TrajectoryModulation(
            smbDamping = 1.0,
            intervalStretch = 1.0,
            basalPreference = 0.5,
            safetyMarginExpand = 1.0,
            reason = "No trajectory modulation applied"
        )
    }
    
    /**
     * Check if this modulation is significant (deviates from neutral)
     * 
     * Thresholds reduced for higher sensitivity:
     * - SMB damping: ±2% (was ±5%)
     * - Interval stretch: ±2% (was ±5%)
     * - Basal preference: ±5% (was ±10%)
     * - Safety margin: ±2% (was ±5%)
     */
    fun isSignificant(): Boolean {
        return abs(smbDamping - 1.0) > 0.02 ||           // ±2% SMB modulation
               abs(intervalStretch - 1.0) > 0.02 ||       // ±2% interval modulation
               abs(basalPreference - 0.5) > 0.05 ||       // ±5% basal preference shift
               abs(safetyMarginExpand - 1.0) > 0.02       // ±2% safety margin adjustment
    }
}

/**
 * Warning generated by trajectory analysis
 */
data class TrajectoryWarning(
    val severity: WarningSeverity,
    val type: String,
    val message: String,
    val suggestedAction: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Severity levels for trajectory warnings
 */
enum class WarningSeverity {
    LOW,        // Informational, no action required
    MEDIUM,     // Monitor closely
    HIGH,       // User intervention suggested
    CRITICAL;   // Immediate action required
    
    fun emoji(): String = when (this) {
        LOW -> "ℹ️"
        MEDIUM -> "⚠️"
        HIGH -> "🚨"
        CRITICAL -> "🔴"
    }
}

/**
 * Complete trajectory analysis result
 */
data class TrajectoryAnalysis(
    val classification: TrajectoryType,
    val metrics: TrajectoryMetrics,
    val modulation: TrajectoryModulation,
    val warnings: List<TrajectoryWarning>,
    val stableOrbitDistance: Double,       // Distance to target stable orbit
    val predictedConvergenceTime: Int?,    // Minutes until expected convergence (null if diverging)
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Format for console logging (compact, info-rich)
     */
    fun toConsoleLog(): List<String> {
        val log = mutableListOf<String>()
        log.add("🌀 TRAJECTORY ANALYSIS")
        log.add("  Type: ${classification.emoji()} ${classification.description()}")
        log.add("  Metrics:")
        log.add("    Curvature: %.3f %s".format(metrics.curvature, if (metrics.isTightSpiral) "⚠️ HIGH" else ""))
        log.add("    Convergence: %+.3f mg/dL/min %s".format(
            metrics.convergenceVelocity,
            when {
                metrics.isConverging -> "✓"
                metrics.isDiverging -> "⚠️"
                else -> ""
            }
        ))
        log.add("    Coherence: %.2f %s".format(metrics.coherence, if (metrics.hasLowCoherence) "⚠️ LOW" else ""))
        log.add("    Energy: %+.2fU %s".format(metrics.energyBalance, if (metrics.energyBalance > 2.5) "⚠️" else ""))
        log.add("    Openness: %.2f %s".format(metrics.openness, if (metrics.openness > 0.7) "⚠️ WIDE" else ""))
        log.add("    Health: %.1f%%".format(metrics.healthScore * 100))
        
        if (modulation.isSignificant()) {
            log.add("  Modulation:")
            log.add("    SMB damping: %.2fx".format(modulation.smbDamping))
            log.add("    Interval: %.2fx".format(modulation.intervalStretch))
            log.add("    Basal pref: %.0f%%".format(modulation.basalPreference * 100))
            log.add("    Safety margin: %.2fx".format(modulation.safetyMarginExpand))
            log.add("    → ${modulation.reason}")
        }
        
        if (warnings.isNotEmpty()) {
            log.add("  Warnings:")
            warnings.forEach { warning ->
                log.add("    ${warning.severity.emoji()} [${warning.type}] ${warning.message}")
                log.add("      → ${warning.suggestedAction}")
            }
        }
        
        predictedConvergenceTime?.let {
            log.add("  Expected convergence: ${it}min")
        }
        
        return log
    }
}

/**
 * Stable orbit definition for a T1D patient
 * 
 * This represents the "attractor" in phase space that the system
 * should naturally return to in steady-state.
 */
data class StableOrbit(
    val targetBg: Double,           // mg/dL (typical: 90-110)
    val targetDelta: Double = 0.0,  // mg/dL/5min (ideal: 0)
    val targetActivity: Double,     // U/hr (typically basal rate)
    val toleranceBg: Double = 20.0, // mg/dL (±20 is "in orbit")
    val toleranceDelta: Double = 2.0 // mg/dL/5min (±2 is stable)
) {
    /**
     * Convert to a PhaseSpaceState for distance calculations
     */
    fun toPhaseSpaceState(): PhaseSpaceState = PhaseSpaceState(
        timestamp = 0L,
        bg = targetBg,
        bgDelta = targetDelta,
        bgAccel = 0.0,
        insulinActivity = targetActivity,
        iob = 0.0,
        pkpdStage = InsulinActivityStage.TAIL,
        timeSinceLastBolus = 240
    )
    
    /**
     * Check if a state is within the stable orbit boundaries
     */
    fun contains(state: PhaseSpaceState): Boolean {
        return abs(state.bg - targetBg) <= toleranceBg &&
               abs(state.bgDelta - targetDelta) <= toleranceDelta
    }
    
    companion object {
        /**
         * Create a stable orbit from profile settings
         */
        fun fromProfile(targetBg: Double, basalRate: Double): StableOrbit = StableOrbit(
            targetBg = targetBg,
            targetActivity = basalRate,
            toleranceBg = 20.0,
            toleranceDelta = 2.0
        )
    }
}
