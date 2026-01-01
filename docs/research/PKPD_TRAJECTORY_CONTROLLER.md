# PKPD as Phase-Space Trajectory Controller
## AIMI Research Mode - Advanced Control Theory for Type 1 Diabetes

**Date**: 2026-01-01  
**Status**: Research & Conceptual Design  
**Author**: AIMI Research Team  
**Classification**: Advanced Algorithm Development

---

## 🎯 Executive Summary

This document explores a paradigm shift in insulin delivery control: moving from **temporal PKPD modeling** to **phase-space trajectory control**. Instead of optimizing instantaneous doses based on time-dependent functions, we interpret insulin action as a **trajectory in phase space** that must be harmoniously closed toward a stable orbit.

**Key Insight**: The quality of an insulin decision is not measured by its immediate amplitude, but by its ability to guide the biological system back to a stable, damped orbit while avoiding dangerous excursions.

---

## 1️⃣ Conceptual Framework

### 1.1 The Phase-Space Paradigm

#### Traditional Temporal View (Current AIMI)
```
Time →
├─ BG(t)
├─ IOB(t) 
├─ Activity(t)
├─ Delta(t)
└─ Decision at t₀ based on current state
```

**Limitation**: Each decision is locally optimal but may be globally destabilizing.

#### Phase-Space Trajectory View (Proposed)
```
State Space: Ψ = (BG, dBG/dt, InsulinActivity, PKPD_Stage, TimeSinceBolus)
```

The system traces a **trajectory** τ(t) through this space:
- **Open trajectory** → system diverging, insufficient insulin action
- **Closed trajectory** → system returning to stable orbit
- **Tight spiral** → over-correction risk, potential hypoglycemia
- **Damped oscillation** → optimal convergence to target

### 1.2 Defining the Stable Orbit

For a T1D patient in steady-state, the **target orbit** Ω_stable is characterized by:

```
Physiological Stable Orbit (Ω_stable):
┌─────────────────────────────────────────┐
│ BG: 80-120 mg/dL                       │
│ dBG/dt: -2 to +2 mg/dL/5min           │
│ IOB: near basal equilibrium            │
│ Insulin Activity: smooth, predictable  │
│ Tissue Response: synchronized          │
└─────────────────────────────────────────┘
```

**Key Properties**:
- **Bounded**: No unbounded excursions
- **Attracting**: Perturbations naturally decay
- **Resilient**: Tolerates small disturbances (meals, stress)

---

## 2️⃣ Reinterpreting Current PKPD

### 2.1 Current AIMI PKPD Model (Temporal)

From `PKPDModelEnhanced.kt`:

```kotlin
// Temporal phases
PRE_ONSET    → 0-15 min: insulin present but not acting
ONSET        → 15-45 min: action beginning  
PEAK         → 45-90 min: maximum effect
POST_PEAK    → 90-180 min: declining action
TAIL         → 180-360 min: residual effect
```

**Temporal Logic**:
- Activity = f(time_since_bolus, dose, DIA)
- Decision made based on: "What time-phase are we in?"

### 2.2 Geometric Reinterpretation

**The Problem**: Temporal phases don't capture **trajectory shape**.

**Example Scenario**:
```
t = 30 min (ONSET phase)
BG = 180 mg/dL
Delta = +8 mg/dL/5min
IOB = 3.5U

Temporal view: "We're in ONSET, activity is ramping up, be cautious"
Trajectory view: "Trajectory is STILL OPENING despite 3.5U IOB
                 → System is under-controlled, action needed"
```

### 2.3 Where Current PKPD Fails

#### Failure Mode 1: Stacking Invisibility
```
Multiple small boluses create distributed IOB
Each individually "safe" in its temporal phase
But combined trajectory shows DANGEROUS COMPRESSION
→ Late hypoglycemia not predicted
```

#### Failure Mode 2: False Safety in PRE_ONSET
```
Fresh IOB → algorithm sees "dormant insulin"
But trajectory already shows BG slowing
→ Additional correction creates over-shoot
```

#### Failure Mode 3: Slow Drift Underestimation
```
BG rising +3 mg/dL/5min for 2 hours
Each timepoint: "Not urgent, IOB present"
Trajectory view: "Orbit is DIVERGING, never closing"
→ Prolonged hyperglycemia
```

---

## 3️⃣ PKPD Trajectory Guard

### 3.1 Core Concept

**Not a blocker, but a modulator**: The Trajectory Guard observes the system's path through phase space and **softly adjusts** decision parameters.

```
Trajectory Guard: Ψ_guard
├─ Input: State history H(t-90 → t)
├─ Analysis: Trajectory geometry
├─ Output: Modulation factors
│   ├─ SMB_damping ∈ [0.5, 1.5]
│   ├─ Interval_stretch ∈ [1.0, 2.0]
│   ├─ Basal_preference ∈ [0.0, 1.0]
│   └─ Safety_margin_expand ∈ [1.0, 1.3]
└─ Effect: Gentle steering, not hard limits
```

### 3.2 Trajectory Classification

The guard classifies the current trajectory into geometric types:

#### Type A: Open Diverging
```
Visual: ↗️ ↗️ ↗️ (expanding away from target)

Characteristics:
- BG increasing AND delta NOT decreasing
- IOB present but insufficient
- Distance to Ω_stable GROWING

Action Modulation:
✓ Enable more aggressive SMB
✓ Shorten interval if safe
✓ Reduce safety margin buffer
✗ Don't wait for "IOB to kick in"
```

#### Type B: Closing Converging
```
Visual: ↗️ → ↘️ (arc returning to target)

Characteristics:
- BG high but delta turning negative
- IOB activity rising
- Distance to Ω_stable DECREASING

Action Modulation:
✓ Gentle SMB if needed
✓ Extend interval
✓ Monitor for over-correction
✗ Don't add aggressive boluses
```

#### Type C: Tight Spiral (Risk)
```
Visual: 🌀 (rapid circulation, potential overshoot)

Characteristics:
- Multiple recent corrections
- High IOB with fast BG descent
- Phase-space velocity HIGH

Action Modulation:
⚠️ STRONG SMB damping
⚠️ Prefer temp basal over bolus
⚠️ Expand safety margins
⚠️ Alert: "Trajectory compression detected"
```

#### Type D: Stable Orbit
```
Visual: ⭕ (bounded oscillation around target)

Characteristics:
- BG 80-140 mg/dL
- Delta ±2 mg/dL/5min
- IOB near basal equilibrium

Action Modulation:
✓ Minimal intervention
✓ Maintain current strategy
✓ Small corrections only
```

### 3.3 Mathematical Formulation

#### 3.3.1 Phase-Space Coordinates

```kotlin
data class PhaseSpaceState(
    val bg: Double,                    // mg/dL
    val bgDelta: Double,               // mg/dL/5min
    val bgAccel: Double,               // d²BG/dt² (trend acceleration)
    val insulinActivity: Double,       // U/hr equivalent
    val iob: Double,                   // U
    val pkpdStage: PKPDStage,         // ONSET, PEAK, etc.
    val timeSinceLastBolus: Int,      // minutes
    val tissueDelay: Double            // estimated lag (0-1)
)
```

#### 3.3.2 Trajectory Metrics

##### Metric 1: Trajectory Curvature κ
```kotlin
/**
 * Measures how quickly the system is changing direction.
 * High κ → tight turn (possible over-correction)
 * Low κ → gentle arc (good control)
 */
fun calculateCurvature(history: List<PhaseSpaceState>): Double {
    // Simplified 2D curvature in (BG, delta) space
    val states = history.takeLast(6) // 30-minute window
    if (states.size < 3) return 0.0
    
    val path = states.map { Point(it.bg, it.bgDelta) }
    
    // κ = |dT/ds| where T is unit tangent
    // Approximation using discrete points
    var totalCurvature = 0.0
    for (i in 1 until path.size - 1) {
        val v1 = Vector(path[i].x - path[i-1].x, path[i].y - path[i-1].y)
        val v2 = Vector(path[i+1].x - path[i].x, path[i+1].y - path[i].y)
        
        val angle = angleBetween(v1, v2)
        val arcLength = v1.magnitude() + v2.magnitude()
        
        totalCurvature += abs(angle) / (arcLength + 1e-6)
    }
    
    return totalCurvature / (path.size - 2)
}
```

##### Metric 2: Convergence Velocity v_conv
```kotlin
/**
 * Measures rate of approach to stable orbit.
 * v_conv > 0 → approaching target
 * v_conv < 0 → diverging
 */
fun calculateConvergenceVelocity(
    current: PhaseSpaceState,
    previous: PhaseSpaceState,
    target: PhaseSpaceState = STABLE_ORBIT
): Double {
    val distCurrent = phaseSpaceDistance(current, target)
    val distPrevious = phaseSpaceDistance(previous, target)
    
    // Rate of change of distance (per 5 min)
    return (distPrevious - distCurrent) / 5.0
}

fun phaseSpaceDistance(state: PhaseSpaceState, target: PhaseSpaceState): Double {
    // Weighted Euclidean distance
    val bgComponent = (state.bg - target.bg) / 40.0          // normalized to ~±2σ
    val deltaComponent = (state.bgDelta - target.bgDelta) / 5.0
    val activityComponent = (state.insulinActivity - target.insulinActivity) / 2.0
    
    return sqrt(
        bgComponent.pow(2) + 
        deltaComponent.pow(2) + 
        activityComponent.pow(2)
    )
}
```

##### Metric 3: Insulin-Glucose Coherence ρ
```kotlin
/**
 * Measures alignment between insulin activity and BG response.
 * ρ → 1: Good response (insulin working as expected)
 * ρ → 0: Poor response (resistance, or wrong phase)
 * ρ < 0: Paradoxical (BG rising despite high activity)
 */
fun calculateCoherence(history: List<PhaseSpaceState>): Double {
    if (history.size < 12) return 0.5 // Need 1 hour of data
    
    val bgTrend = history.takeLast(12).map { it.bg }
    val activityTrend = history.takeLast(12).map { it.insulinActivity }
    
    // Expected: high activity → falling BG
    // Correlation between activity and -dBG/dt
    val bgChanges = bgTrend.zipWithNext { a, b -> b - a }
    val avgActivity = activityTrend.dropLast(1)
    
    // Pearson correlation: activity vs (-delta_BG)
    return correlation(avgActivity, bgChanges.map { -it })
}
```

##### Metric 4: Energy Injection vs Dissipation E_balance
```kotlin
/**
 * Tracks cumulative "control energy" added vs. absorbed.
 * E > 0: Accumulating interventions (stacking risk)
 * E ≈ 0: Balanced
 * E < 0: Under-acting
 */
fun calculateEnergyBalance(history: List<PhaseSpaceState>): Double {
    var energyIn = 0.0    // Boluses delivered
    var energyOut = 0.0   // BG corrections achieved
    
    for (i in 1 until history.size) {
        val state = history[i]
        val prev = history[i - 1]
        
        // Energy in: IOB increase
        val iobIncrease = max(0.0, state.iob - prev.iob)
        energyIn += iobIncrease
        
        // Energy out: BG descent (if above target)
        if (prev.bg > 110) {
            val bgDrop = max(0.0, prev.bg - state.bg)
            energyOut += bgDrop / 40.0 // Normalize to ~ISF equivalent
        }
    }
    
    return energyIn - energyOut  // Positive = accumulating
}
```

##### Metric 5: Trajectory Openness Θ
```kotlin
/**
 * Measures how "open" or "closed" the trajectory is.
 * Θ → 0: Tightly closed loop (converging)
 * Θ → 1: Wide open (diverging or unstable)
 */
fun calculateOpenness(history: List<PhaseSpaceState>): Double {
    if (history.size < 6) return 0.5
    
    val recentPath = history.takeLast(12) // 1 hour
    
    // Check if trajectory is returning to origin
    val start = phaseSpaceDistance(recentPath.first(), STABLE_ORBIT)
    val current = phaseSpaceDistance(recentPath.last(), STABLE_ORBIT)
    val maxDeviation = recentPath.maxOf { phaseSpaceDistance(it, STABLE_ORBIT) }
    
    // If we're closer than we started, trajectory is closing
    val closure = (start - current) / (maxDeviation + 1e-6)
    
    // Openness is inverse of closure
    return (1.0 - closure).coerceIn(0.0, 1.0)
}
```

---

## 4️⃣ Algorithmic Integration

### 4.1 Integration Points in AIMI

The Trajectory Guard integrates at **multiple layers**:

```
AIMI Decision Pipeline:
┌─────────────────────────────────────────┐
│ 1. Data Collection (BG, IOB, Profile)  │
└────────────┬────────────────────────────┘
             ↓
┌─────────────────────────────────────────┐
│ 2. PKPD State Estimation                │
│    └→ + TRAJECTORY ANALYZER (NEW)       │ ← Compute metrics
└────────────┬────────────────────────────┘
             ↓
┌─────────────────────────────────────────┐
│ 3. UnifiedReactivity + Learners         │
│    └→ + TRAJECTORY MODULATION (NEW)     │ ← Adjust parameters
└────────────┬────────────────────────────┘
             ↓
┌─────────────────────────────────────────┐
│ 4. SMB/Basal Decision                   │
│    └→ + TRAJECTORY DAMPING (NEW)        │ ← Soft limits
└────────────┬────────────────────────────┘
             ↓
┌─────────────────────────────────────────┐
│ 5. Safety Checks                        │
│    └→ + TRAJECTORY SAFETY (NEW)         │ ← Expand margins if needed
└────────────┬────────────────────────────┘
             ↓
┌─────────────────────────────────────────┐
│ 6. Execute & Log                        │
│    └→ + rT Trajectory Display (NEW)     │ ← Visualize in console
└─────────────────────────────────────────┘
```

### 4.2 Proposed Implementation Structure

```kotlin
// New file: TrajectoryGuard.kt
package app.aaps.plugins.aps.openAPSAIMI.trajectory

/**
 * Phase-Space Trajectory Controller for AIMI
 * 
 * Analyzes insulin-glucose dynamics as geometric trajectories
 * rather than purely temporal sequences, enabling safer and
 * more harmonious closed-loop control.
 */
class TrajectoryGuard(
    private val historyProvider: AIMIHistoryProvider,
    private val pkpdModel: PKPDModelEnhanced
) {
    
    companion object {
        // Stable orbit definition for adult T1D
        val STABLE_ORBIT = PhaseSpaceState(
            bg = 100.0,
            bgDelta = 0.0,
            bgAccel = 0.0,
            insulinActivity = 1.0,  // ~1U/hr basal
            iob = 0.0,
            pkpdStage = PKPDStage.TAIL,
            timeSinceLastBolus = 240,
            tissueDelay = 0.0
        )
        
        // Metric thresholds
        const val CURVATURE_HIGH = 0.3         // Tight spiral warning
        const val CONVERGENCE_SLOW = -0.5      // Diverging
        const val COHERENCE_LOW = 0.3          // Poor insulin response
        const val ENERGY_STACKING = 2.0        // Accumulation risk
        const val OPENNESS_DIVERGING = 0.7     // Wide open trajectory
    }
    
    /**
     * Main analysis function
     */
    fun analyzeTrajectory(): TrajectoryAnalysis {
        val history = collectPhaseSpaceHistory()
        
        return TrajectoryAnalysis(
            classification = classifyTrajectory(history),
            metrics = TrajectoryMetrics(
                curvature = calculateCurvature(history),
                convergenceVelocity = calculateConvergenceVelocity(
                    history.last(), 
                    history[history.size - 2]
                ),
                coherence = calculateCoherence(history),
                energyBalance = calculateEnergyBalance(history),
                openness = calculateOpenness(history)
            ),
            modulation = computeModulation(history),
            warnings = generateWarnings(history),
            rTdata = formatForRealTime(history)
        )
    }
    
    private fun classifyTrajectory(history: List<PhaseSpaceState>): TrajectoryType {
        val metrics = computeAllMetrics(history)
        
        return when {
            // Tight spiral → over-correction risk
            metrics.curvature > CURVATURE_HIGH && 
            metrics.energyBalance > ENERGY_STACKING -> 
                TrajectoryType.TIGHT_SPIRAL
            
            // Open diverging → insufficient action
            metrics.openness > OPENNESS_DIVERGING && 
            metrics.convergenceVelocity < CONVERGENCE_SLOW ->
                TrajectoryType.OPEN_DIVERGING
            
            // Closing converging → good control
            metrics.convergenceVelocity > 0 && 
            metrics.openness < 0.4 ->
                TrajectoryType.CLOSING_CONVERGING
            
            // Stable orbit → maintain
            phaseSpaceDistance(history.last(), STABLE_ORBIT) < 1.5 &&
            metrics.curvature < 0.1 ->
                TrajectoryType.STABLE_ORBIT
            
            // Default: uncertain
            else -> TrajectoryType.UNCERTAIN
        }
    }
    
    /**
     * Compute modulation factors based on trajectory
     */
    private fun computeModulation(history: List<PhaseSpaceState>): TrajectoryModulation {
        val classification = classifyTrajectory(history)
        val metrics = computeAllMetrics(history)
        
        return when (classification) {
            TrajectoryType.OPEN_DIVERGING -> TrajectoryModulation(
                smbDamping = 1.2,           // Permit more aggressive SMB
                intervalStretch = 1.0,       // No delay
                basalPreference = 0.2,       // Still prefer bolus for acute rise
                safetyMarginExpand = 0.95,   // Slightly tighter margins OK
                reason = "Trajectory diverging, need stronger action"
            )
            
            TrajectoryType.TIGHT_SPIRAL -> TrajectoryModulation(
                smbDamping = 0.5,            // Strong SMB reduction
                intervalStretch = 1.8,        // Wait longer between doses
                basalPreference = 0.8,        // Strongly prefer temp basal
                safetyMarginExpand = 1.3,     // Expand safety buffers
                reason = "Trajectory compressed, over-correction risk"
            )
            
            TrajectoryType.CLOSING_CONVERGING -> TrajectoryModulation(
                smbDamping = 0.85,           // Gentle damping
                intervalStretch = 1.3,        // Slightly longer wait
                basalPreference = 0.4,        // Mild basal preference
                safetyMarginExpand = 1.1,     // Slight caution
                reason = "Trajectory closing, allow natural convergence"
            )
            
            TrajectoryType.STABLE_ORBIT -> TrajectoryModulation(
                smbDamping = 1.0,            // Neutral
                intervalStretch = 1.0,
                basalPreference = 0.5,
                safetyMarginExpand = 1.0,
                reason = "Stable orbit maintained"
            )
            
            else -> TrajectoryModulation.NEUTRAL
        }
    }
    
    /**
     * Generate warnings for clinical decision support
     */
    private fun generateWarnings(history: List<PhaseSpaceState>): List<TrajectoryWarning> {
        val warnings = mutableListOf<TrajectoryWarning>()
        val metrics = computeAllMetrics(history)
        
        // Warning 1: Stacking detected
        if (metrics.energyBalance > ENERGY_STACKING && 
            metrics.curvature > 0.2) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.HIGH,
                type = "INSULIN_STACKING",
                message = "Multiple corrections accumulating, hypo risk in 60-90 min",
                suggestedAction = "Reduce SMB, prefer temp basal"
            ))
        }
        
        // Warning 2: Poor insulin response
        if (metrics.coherence < COHERENCE_LOW && 
            history.last().iob > 2.0) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.MEDIUM,
                type = "LOW_COHERENCE",
                message = "IOB present but BG not responding (resistance?)",
                suggestedAction = "Wait and observe, check site/insulin"
            ))
        }
        
        // Warning 3: Slow drift going unaddressed
        if (metrics.openness > 0.75 && 
            metrics.convergenceVelocity < -0.3 &&
            history.last().bg > 140) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.MEDIUM,
                type = "PERSISTENT_DIVERGENCE",
                message = "BG drifting upward despite IOB, trajectory not closing",
                suggestedAction = "Consider additional correction if safe"
            ))
        }
        
        // Warning 4: PRE_ONSET false safety
        if (history.last().pkpdStage == PKPDStage.PRE_ONSET &&
            history.last().iob > 1.5 &&
            metrics.curvature > 0.15) {
            warnings.add(TrajectoryWarning(
                severity = WarningSeverity.LOW,
                type = "PRE_ONSET_COMPRESSION",
                message = "Fresh IOB in PRE_ONSET but trajectory already tightening",
                suggestedAction = "Caution: avoid additional bolus"
            ))
        }
        
        return warnings
    }
    
    private fun collectPhaseSpaceHistory(): List<PhaseSpaceState> {
        val bgHistory = historyProvider.getBGHistory(90) // 90 min
        val iobHistory = historyProvider.getIOBHistory(90)
        val bolusHistory = historyProvider.getBolusHistory(90)
        
        return bgHistory.indices.map { i ->
            val bg = bgHistory[i]
            val iob = iobHistory[i]
            
            PhaseSpaceState(
                bg = bg.value,
                bgDelta = if (i > 0) (bg.value - bgHistory[i-1].value) else 0.0,
                bgAccel = if (i > 1) computeAcceleration(bgHistory, i) else 0.0,
                insulinActivity = pkpdModel.computeActivity(iob),
                iob = iob.iob,
                pkpdStage = pkpdModel.getCurrentStage(),
                timeSinceLastBolus = computeTimeSinceLastBolus(bolusHistory, bg.timestamp),
                tissueDelay = estimateTissueDelay(bg, iob)
            )
        }
    }
}

// Data classes
data class TrajectoryAnalysis(
    val classification: TrajectoryType,
    val metrics: TrajectoryMetrics,
    val modulation: TrajectoryModulation,
    val warnings: List<TrajectoryWarning>,
    val rTdata: Map<String, Any>
)

data class TrajectoryMetrics(
    val curvature: Double,
    val convergenceVelocity: Double,
    val coherence: Double,
    val energyBalance: Double,
    val openness: Double
)

data class TrajectoryModulation(
    val smbDamping: Double,        // Multiply SMB by this (0.5-1.5)
    val intervalStretch: Double,    // Multiply interval by this (1.0-2.0)
    val basalPreference: Double,    // 0=SMB only, 1=basal only
    val safetyMarginExpand: Double, // Multiply safety margins (0.9-1.3)
    val reason: String
) {
    companion object {
        val NEUTRAL = TrajectoryModulation(
            smbDamping = 1.0,
            intervalStretch = 1.0,
            basalPreference = 0.5,
            safetyMarginExpand = 1.0,
            reason = "No trajectory modulation"
        )
    }
}

enum class TrajectoryType {
    OPEN_DIVERGING,        // ↗️ System escaping
    CLOSING_CONVERGING,    // ↗️→↘️ Returning to target
    TIGHT_SPIRAL,          // 🌀 Over-correction risk
    STABLE_ORBIT,          // ⭕ Optimal
    UNCERTAIN              // ? Need more data
}

data class TrajectoryWarning(
    val severity: WarningSeverity,
    val type: String,
    val message: String,
    val suggestedAction: String
)

enum class WarningSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}
```

### 4.3 Integration into DetermineBasalAIMI2

```kotlin
// In DetermineBasalAIMI2.kt - main decision function

fun determine(...): DetermineBasalResultAIMI2 {
    
    // ... existing setup code ...
    
    // NEW: Trajectory analysis
    val trajectoryGuard = TrajectoryGuard(historyProvider, pkpdModel)
    val trajectoryAnalysis = trajectoryGuard.analyzeTrajectory()
    
    // Add to console log
    consoleLog.add("🌀 TRAJECTORY GUARD")
    consoleLog.add("  Type: ${trajectoryAnalysis.classification}")
    consoleLog.add("  Curvature: %.3f".format(trajectoryAnalysis.metrics.curvature))
    consoleLog.add("  Convergence: %.3f mg/dL/min".format(trajectoryAnalysis.metrics.convergenceVelocity))
    consoleLog.add("  Coherence: %.2f".format(trajectoryAnalysis.metrics.coherence))
    consoleLog.add("  Energy: %.2fU".format(trajectoryAnalysis.metrics.energyBalance))
    consoleLog.add("  Openness: %.2f".format(trajectoryAnalysis.metrics.openness))
    
    // Display warnings
    trajectoryAnalysis.warnings.forEach { warning ->
        consoleLog.add("  ⚠️ ${warning.severity}: ${warning.message}")
    }
    
    // ... existing unified reactivity ...
    
    // NEW: Apply trajectory modulation to SMB decision
    val baseSMB = computeBaseSMB(...)  // existing logic
    val modulatedSMB = baseSMB * trajectoryAnalysis.modulation.smbDamping
    
    consoleLog.add("  SMB modulation: ${baseSMB.fmt()} → ${modulatedSMB.fmt()}")
    consoleLog.add("  Reason: ${trajectoryAnalysis.modulation.reason}")
    
    // Decision: SMB vs Basal based on trajectory preference
    val useBasal = when {
        trajectoryAnalysis.modulation.basalPreference > 0.7 -> true
        trajectoryAnalysis.classification == TrajectoryType.TIGHT_SPIRAL -> true
        else -> false // use existing logic
    }
    
    if (useBasal) {
        consoleLog.add("  → Trajectory prefers TEMP BASAL over SMB")
        return buildTempBasalResult(...)
    }
    
    // Adjust safety margins based on trajectory
    val adjustedMaxIOB = maxIOB * trajectoryAnalysis.modulation.safetyMarginExpand
    val adjustedMaxSMB = maxSMB * trajectoryAnalysis.modulation.smbDamping
    
    // ... continue with existing safety checks using adjusted limits ...
    
    return finalResult
}
```

---

## 5️⃣ Clinical Case Analysis

### Case 1: Slow Infectious Rise (Resistance)

**Scenario**:
```
t=0:   BG=140, delta=+3, IOB=1.2U → SMB 0.5U
t=15:  BG=155, delta=+3, IOB=1.5U → SMB 0.3U  
t=30:  BG=170, delta=+3, IOB=1.6U → SMB 0.3U
t=45:  BG=185, delta=+3, IOB=1.7U → Wait (PRE_ONSET)
t=60:  BG=200, delta=+2, IOB=1.5U → Wait (ONSET active)
t=90:  BG=215, delta=+2, IOB=1.2U → ???
```

**Temporal View**: "IOB present, phases active, be patient"

**Trajectory View**:
```
Metrics at t=90:
- Curvature: 0.05 (very flat, no turn)
- Convergence velocity: -1.2 (DIVERGING)
- Coherence: 0.15 (very poor - insulin not working)
- Energy balance: +1.5U (accumulating but not effective)
- Openness: 0.85 (WIDE OPEN)

Classification: OPEN_DIVERGING with LOW COHERENCE

→ Trajectory shows: System is NOT closing, insulin resistance likely
→ Action: Permit additional SMB despite IOB (modulation = 1.3x)
→ Warning: "Poor insulin-glucose coherence, check ketones/site"
```

**Outcome**: Earlier recognition of resistance, safer escalation.

---

### Case 2: Fatty Breakfast (Undeclared)

**Scenario**:
```
t=0:   BG=95, delta=0, IOB=0 → Stable
t=30:  BG=110, delta=+3, IOB=0 → Small SMB 0.4U
t=60:  BG=130, delta=+4, IOB=0.3U → SMB 0.6U
t=90:  BG=155, delta=+4, IOB=0.6U → SMB 0.8U
t=120: BG=175, delta=+3, IOB=0.8U → Waiting (ONSET)
t=150: BG=185, delta=+2, IOB=0.6U → Still rising slowly
```

**Temporal View**: "Multiple corrections sent, phases distributed, wait"

**Trajectory View**:
```
Metrics at t=150:
- Curvature: 0.12 (moderate)
- Convergence velocity: +0.3 (SLOWLY converging)
- Coherence: 0.55 (moderate - typical for distributed absorption)
- Energy balance: +0.8U (moderate accumulation)
- Openness: 0.5 (MODERATE - closing but slowly)

Classification: CLOSING_CONVERGING (slow)

→ Trajectory shows: System IS turning, but meal is extending
→ Action: Gentle SMB OK, but damping 0.85x, interval +30%
→ No warning: Natural slow convergence for fatty meal
```

**Outcome**: Patience enforced, avoids over-stacking while still active.

---

### Case 3: Discrete Stacking (Hidden Danger)

**Scenario**:
```
t=0:   BG=180, delta=+6 → SMB 1.2U
t=10:  BG=188, delta=+5 → SMB 0.8U
t=20:  BG=194, delta=+4 → SMB 0.6U
t=30:  BG=198, delta=+3 → SMB 0.4U (total IOB now 2.8U)
t=40:  BG=200, delta=+2 → Algorithm sees "BG slowing, wait"
t=60:  BG=195, delta=-1 → "Good, turning"
t=90:  BG=175, delta=-4 → "Descending normally"
t=120: BG=140, delta=-7 → ⚠️ "Rapid fall!"
t=150: BG=95,  delta=-8 → 🚨 HYPO incoming
```

**Temporal View**: Each bolus was "justified" at its time. No single red flag.

**Trajectory View**:
```
Metrics at t=30 (BEFORE the fall):
- Curvature: 0.35 (HIGH - trajectory tightening fast)
- Convergence velocity: +0.8 (Converging, but...)
- Coherence: 0.75 (Good - insulin working well!)
- Energy balance: +2.8U (HIGH accumulation)
- Openness: 0.25 (VERY TIGHT)

Classification: TIGHT_SPIRAL ⚠️

→ Trajectory shows: Multiple interventions creating compressed path
→ Action: STRONG SMB damping (0.5x), prefer temp basal
→ Warning: "Trajectory compression detected, hypo risk 60-90min"
```

**Outcome**: Early warning at t=30, prevention of 4th bolus, likely avoids hypo.

---

### Case 4: False Safety in PRE_ONSET

**Scenario**:
```
t=0:   BG=220, delta=+8 → Large SMB 2.0U
t=5:   BG=228, delta=+7, IOB=2.0U (PRE_ONSET)
       User sees: "Still rising, but 2U just sent"
       Algorithm: "PRE_ONSET phase, be cautious"
       
Should we add more? Risk of stacking...
```

**Temporal View**: "2U in PRE_ONSET, don't touch it"

**Trajectory View**:
```
Metrics at t=5:
- Curvature: 0.02 (still very flat - no turn yet)
- Convergence velocity: -0.6 (STILL diverging)
- Coherence: N/A (too early to measure)
- Energy balance: +2.0U (just added)
- Openness: 0.80 (OPEN - no sign of closing)

Classification: OPEN_DIVERGING

→ Trajectory shows: 2U just sent, but trajectory has NOT started turning
→ This is OK! The turn will come at t=15-20 min
→ Action: Wait, but NOT because of "PRE_ONSET fear"
→ Reason: "Trajectory still open, but freshly injected energy sufficient"
```

**Outcome**: Confidence in waiting, but for the RIGHT reason (geometric, not temporal).

---

### Case 5: Delayed Hypo Post-Correction

**Scenario**:
```
Dinner spike:
t=0:   BG=250, delta=+10 → SMB 1.5U + meal mode boost
t=20:  BG=265, delta=+6 → SMB 1.0U
t=40:  BG=275, delta=+4 → SMB 0.8U
t=60:  BG=280, delta=+2 → SMB 0.5U (total IOB ~3.5U)
t=90:  BG=270, delta=-2 → "Finally turning"
t=120: BG=240, delta=-6 → "Good descent"
t=150: BG=200, delta=-8 → "Still OK"
t=180: BG=160, delta=-8 → ⚠️ "Fast drop"
t=210: BG=110, delta=-10 → 🚨 Alert!
t=240: BG=65 → HYPO
```

**Temporal View**: Each correction seemed reasonable given persistent rise.

**Trajectory View at t=60**:
```
Metrics:
- Curvature: 0.28 (MODERATE-HIGH)
- Energy balance: +3.5U (HIGH)
- Openness: 0.35 (Starting to close)
- Coherence: 0.45 (Meal absorption interfering)

Classification: CLOSING_CONVERGING but with HIGH energy

→ Trajectory shows: System IS turning (finally)
→ BUT: 3.5U accumulated, will continue working for 2-3 hours
→ Warning: "Trajectory closing with high energy accumulation"
→ Action: STOP adding insulin, let trajectory complete
```

**Outcome**: Recognition at t=60 that enough insulin is aboard, avoids 4th bolus, reduces hypo severity.

---

## 6️⃣ Benefits vs. Classical PKPD

### Classical Temporal PKPD

**Strengths**:
- Simple, interpretable
- Works well for single isolated corrections
- Easy to explain phases to patients

**Weaknesses**:
- No cumulative trajectory awareness
- Each decision is locally optimal but may be globally risky
- Stacking detection is heuristic (IOB > threshold)
- Doesn't capture insulin-glucose coherence
- Oscillations not predicted

### Trajectory-Based Control

**Strengths**:
- **Global awareness**: Sees the full path, not just current state
- **Early warnings**: Detects compression before hypo manifests
- **Resistance detection**: Low coherence signals insulin not working
- **Harmonious control**: Guides system back to stable orbit, not just "fix BG now"
- **Adaptive**: Same trajectory framework works for meals, corrections, exercise

**Weaknesses**:
- More complex to implement
- Requires history (won't work first 30 min after startup)
- Harder to explain to users (but can be visualized!)
- Needs tuning of metric thresholds

### Why It Reduces Both Slow Hypers AND Late Hypos

**The key insight**: Both problems share the same root cause: **local optimization without global trajectory awareness**.

#### Slow Hypers (Undercorrection)
```
Classical: "IOB present, wait"
Trajectory: "IOB present but trajectory STILL OPEN → act"
```
By detecting when accumulated insulin is insufficient (low coherence, persistent openness), the system can safely escalate even with IOB.

#### Late Hypos (Overcorrection)
```
Classical: "BG high, delta positive → correct" (repeated)
Trajectory: "Trajectory already TIGHTENING → stop"
```
By detecting early signs of trajectory closure (curvature, energy accumulation), the system can stop before stacking becomes dangerous.

#### The Balance
```
Trajectory control doesn't make the system "less aggressive" or "more aggressive"
It makes it CONTEXTUALLY APPROPRIATE:
- Aggressive when trajectory is open
- Conservative when trajectory is closing
```

---

## 7️⃣ Practical Implementation Roadmap

### Phase 1: Foundation (Weeks 1-2)
```
☐ Implement PhaseSpaceState data structure
☐ Create history collection from existing AIMI data
☐ Implement 5 core metrics (κ, v_conv, ρ, E, Θ)
☐ Add unit tests for metric calculations
☐ Feature flag: ENABLE_TRAJECTORY_GUARD = false
```

### Phase 2: Classification (Weeks 3-4)
```
☐ Implement trajectory classification logic
☐ Create TrajectoryGuard class
☐ Integration with existing PKPD model
☐ Add rT console logging for trajectory data
☐ Test on historical data (offline analysis)
```

### Phase 3: Modulation (Weeks 5-6)
```
☐ Implement modulation factors
☐ Integrate into DetermineBasalAIMI2 SMB decision
☐ Add trajectory preference to basal vs. bolus choice
☐ Safety margin adjustment
☐ Warning generation system
```

### Phase 4: Testing & Tuning (Weeks 7-10)
```
☐ Enable on test devices (adult users first)
☐ Collect real-world trajectory logs
☐ Tune metric thresholds based on data
☐ A/B testing: trajectory-on vs. trajectory-off
☐ Validate hypo reduction metrics
```

### Phase 5: Visualization (Weeks 11-12)
```
☐ Add phase-space plot to AIMI UI
☐ Real-time trajectory display in Nightscout/rT
☐ Historical trajectory replay
☐ Patient-friendly explanations
```

### Phase 6: Pediatric Safety (Weeks 13-14)
```
☐ Adjust thresholds for children (more conservative)
☐ Parental alerts for trajectory warnings
☐ Extended testing with pediatric endocrinologist review
☐ Documentation for medical review
```

### Phase 7: Production Rollout (Week 15+)
```
☐ Feature flag default: true (opt-out available)
☐ Monitoring dashboard for trajectory metrics
☐ User education materials
☐ Medical publication preparation
```

---

## 8️⃣ Visualizations for User Interface

### Real-Time Phase-Space Plot

```
         dBG/dt (mg/dL/5min)
              ↑
         +10 ┤        ●  ← Current (rising fast)
              │       ╱
          +5 ┤     ╱
              │   ╱
           0 ┼─●────────────────────● ← Target
              │ ╲                  ▲
          -5 ┤   ╲             Stable orbit
              │     ●  ← Path shows closing
         -10 ┤
              └────┴────┴────┴────┴────→ BG
                  80  100  120  140  160

Legend:
● = 5-minute intervals
Curved line = trajectory path
Shaded circle = stable orbit target
Color: 🟢 closing / 🟡 uncertain / 🔴 diverging
```

### Trajectory Dashboard Widget

```
┌─────────────────────────────────────┐
│ 🌀 TRAJECTORY STATUS                │
├─────────────────────────────────────┤
│ Type: CLOSING CONVERGING 🟢         │
│                                     │
│ Metrics:                            │
│  Curvature:    ████░░░░░ 0.12      │ 
│  Convergence:  ██████░░░ +0.4      │
│  Coherence:    ███████░░ 0.71      │
│  Energy:       ███░░░░░░ 1.2U      │
│  Openness:     █████░░░░ 0.48      │
│                                     │
│ Assessment:                         │
│ "BG turning toward target.          │
│  Current IOB sufficient.            │
│  Allow natural convergence."        │
│                                     │
│ Next action in: 15 min              │
└─────────────────────────────────────┘
```

---

## 9️⃣ Medical & Regulatory Considerations

### Clinical Validation Requirements

1. **Retrospective Analysis**
   - Run trajectory algorithm on 6+ months historical data
   - Compare decisions: trajectory-on vs. trajectory-off
   - Measure:
     - Time in range (TIR) improvement
     - Hypoglycemia reduction
     - Hyperglycemia > 180 reduction
     - Glycemic variability (CV)

2. **Prospective Testing**
   - IRB-approved protocol
   - Adult T1D volunteers first
   - Continuous monitoring (Dexcom/Guardian link)
   - Weekly endocrinologist review

3. **Safety Endpoints**
   - No increase in severe hypo events
   - No increase in DKA events
   - User satisfaction scores
   - Algorithm understandability rating

### Documentation for Medical Review

```
Required documents:
☐ Algorithm specification (this document)
☐ Risk analysis (FMEA)
☐ Verification & validation plan
☐ Clinical data package
☐ User manual updates
☐ Informed consent for testing
```

### Regulatory Path (if applicable)

- **Europe (CE Mark)**: Likely class IIb medical device
- **USA (FDA)**: Likely 510(k) if predicate exists, otherwise De Novo
- **Open source consideration**: Mark as "research use" initially

---

## 🔟 Advanced Extensions (Future Research)

### Multi-Variable Phase Space

Current: (BG, dBG/dt, InsulinActivity)

Future extensions:
- **Add carb absorption state**: COB dynamics
- **Add exercise state**: Heart rate, activity level
- **Add circadian rhythm**: Time-of-day insulin sensitivity
- **Add stress markers**: Cortisol proxy (HRV), illness detection

→ Higher-dimensional trajectory with **manifold learning** to find natural low-dim representation.

### Adaptive Orbit Definition

Current: Fixed STABLE_ORBIT target

Future:
- **Personalized orbit** learned from patient's data
- **Time-varying orbit** (stricter at night, relaxed post-meal)
- **Multi-stability**: Different attractors for exercise, sleep, meal

### Trajectory Prediction

Current: Reactive (respond to current trajectory)

Future:
- **Predictive**: Use ML to forecast trajectory 30-60 min ahead
- **Model-Predictive Control (MPC)**: Optimize future control sequence
- **Ensemble simulations**: Run N possible futures, choose safest path

### Closed-Loop Learning

Current: Fixed metric thresholds

Future:
- **Online learning**: Adjust thresholds based on patient outcomes
- **Reinforcement learning**: Reward = time in orbit, penalty = excursions
- **Transfer learning**: Pool knowledge across similar patients (federated)

---

## 📚 References & Theoretical Background

### Control Theory
- **Phase-Space Methods**: Strogatz, "Nonlinear Dynamics and Chaos"
- **Limit Cycles & Attractors**: Perko, "Differential Equations and Dynamical Systems"
- **Model-Predictive Control**: Camacho & Alba, "Model Predictive Control"

### Diabetes Closed-Loop
- **Artificial Pancreas Review**: Bekiari et al., Cochrane 2018
- **OpenAPS Documentation**: openaps.org
- **AndroidAPS Algorithm**: github.com/nightscout/AndroidAPS
- **PKPD Models**: Walsh & Roberts, "Pumping Insulin"

### Relevant Papers
- Percival et al., "Closed-Loop Control and Advisory Mode Evaluation"
- Hovorka model: "Nonlinear model predictive control of glucose concentration in subjects with type 1 diabetes"
- Cambridge FlorenceM algorithm trajectory concepts

---

## 💡 Final Thoughts

### The Core Philosophy

**Classical control**: "What dose should I give NOW to fix THIS problem?"

**Trajectory control**: "What gentle steering will harmoniously guide the system back to its natural stable state?"

It's the difference between:
- **Fighting the system** → aggressive local corrections, oscillations
- **Guiding the system** → smooth global convergence, stability

### Why This Matters for AIMI

AIMI already has:
- ✓ Advanced PKPD modeling
- ✓ Unified reactivity learning
- ✓ Multi-layer safety checks
- ✓ Meal detection & handling

What trajectory control adds:
- ✓ **Geometric awareness**: See the shape of the response
- ✓ **Early warnings**: Detect problems before they manifest
- ✓ **Harmonious decisions**: Choose actions that close orbits, not just lower BG
- ✓ **Explainable AI**: Visualizable, understandable reasoning

### The Promise

> **Reduce slow hypers AND late hypos simultaneously**
> **without making the algorithm timid**
> **by teaching it to see the dance, not just the steps**

---

## 📋 Next Actions

For immediate implementation:

1. **Review this document** with AIMI development team
2. **Create GitHub issue** for trajectory guard feature
3. **Set up feature flag** in preferences
4. **Implement Phase 1** (foundation + metrics)
5. **Run offline analysis** on historical data
6. **Propose testing protocol** to medical advisor

---

**Document Status**: Draft for review  
**Next Review**: After team feedback  
**Implementation Start**: TBD  

---

*"The system is not the sum of its states, but the trajectory through them."*
