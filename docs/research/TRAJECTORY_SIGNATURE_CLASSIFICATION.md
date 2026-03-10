# Trajectory Signature Classification
## Identifying Glycemic Disturbance Origins from Phase-Space Geometry

**Companion Document to**: PKPD_TRAJECTORY_CONTROLLER.md  
**Date**: 2026-01-01  
**Status**: Advanced Research Concept  

---

## 🎯 Core Hypothesis

**Different physiological perturbations create distinct geometric signatures in phase-space trajectories.**

By analyzing the **shape, velocity, and evolution** of the (BG, dBG/dt, InsulinActivity) trajectory, we can infer the **likely cause** of a glycemic excursion:

- 🍽️ **Alimentaire** (meal): Rapid rise, predictable arc, correlates with COB
- 😰 **Stress/Adrénaline**: Sharp spike, rapid reversal, no COB correlation
- 🌙 **Hormonal** (dawn phenomenon, menstrual): Slow drift, persistent, time-of-day pattern
- 🤒 **Maladie/Infection**: Sustained elevation, poor insulin response (low coherence)
- 💪 **Exercise**: Rapid drop, delayed rise (anaerobic), or sustained drop (aerobic)
- 💉 **Insulin malfunction**: Unexplained rise despite adequate IOB

This enables **diagnostic closed-loop**: The algorithm doesn't just react, it **understands WHY** and adapts accordingly.

---

## 1️⃣ Geometric Signatures by Cause

### 1.1 Signature: Meal (Repas)

#### Phase-Space Characteristics
```
Trajectory shape in (BG, delta) space:

    delta
      ↑
  +10 ┤         ●─●  ← Peak (45-60 min)
      │       ╱     ╲
   +5 ┤     ●         ●
      │   ╱             ╲
    0 ┼─●                 ●─● ← Return (120-180 min)
      │                     ╲
   -5 ┤                       ●
      │
      └──────┴────┴────┴────┴────→ BG
         100  120  140  160  180  200

Pattern: PARABOLIC ARC
- Origin: Near baseline
- Rise: Smooth acceleration (5-15 min to peak delta)
- Peak: Clear vertex (delta max)
- Descent: Symmetric or asymmetric based on insulin response
- Closure: Typically 90-180 min for normal meal
```

#### Quantitative Features
```kotlin
data class MealSignature(
    // Temporal
    val riseTime: Int,              // ~15-30 min to peak delta
    val totalDuration: Int,         // ~120-180 min to closure
    
    // Geometric
    val trajectoryShape: Shape,     // PARABOLIC
    val symmetry: Double,           // 0.6-0.9 (moderately symmetric)
    val curvature: Double,          // 0.1-0.3 (gentle arc)
    
    // Response
    val insulinCoherence: Double,   // 0.6-0.9 (good response)
    val predictability: Double,     // 0.7-0.95 (follows expected COB curve)
    
    // Context
    val cobPresent: Boolean,        // TRUE
    val cobAlignment: Double        // 0.8-1.0 (trajectory matches COB decay)
)
```

#### Distinctive Markers
✓ **COB correlation**: Trajectory shape matches carb absorption model  
✓ **Predictable peak timing**: 45-75 min for fast carbs, 90-150 for slow  
✓ **Insulin sensitivity intact**: Good coherence (ρ > 0.6)  
✓ **Symmetric descent** (if properly dosed)

---

### 1.2 Signature: Stress / Adrénaline

#### Phase-Space Characteristics
```
Trajectory shape:

    delta
      ↑
  +15 ┤     ●  ← SPIKE (5-10 min!)
      │   ╱ ╲
  +10 ┤  ●   ╲
      │ ╱     ●
   +5 ┤●       ╲
      │         ●─●  ← Reversal (20-30 min)
    0 ┼●            ╲
      │              ●
   -5 ┤               ● ← Often overshoots
      │
      └──────┴────┴────┴────┴────→ BG
         100  120  140  160  180

Pattern: SHARP SPIKE + RAPID REVERSAL
- Rise: VERY fast (delta > +10 mg/dL/5min)
- Peak: Sharp vertex (high curvature)
- Reversal: Counter-regulatory hormones kick in
- Often: Self-correcting without insulin
```

#### Quantitative Features
```kotlin
data class StressSignature(
    // Temporal
    val riseTime: Int,              // ~5-15 min (VERY fast)
    val peakSharpness: Double,      // >0.4 curvature
    val reversalSpeed: Double,      // Often spontaneous
    
    // Geometric
    val trajectoryShape: Shape,     // SPIKE (high curvature)
    val symmetry: Double,           // 0.2-0.5 (asymmetric - fast up, variable down)
    
    // Response
    val insulinIndependence: Double,// 0.3-0.7 (often self-corrects)
    val coherence: Double,          // Variable (0.3-0.8)
    
    // Context
    val cobPresent: Boolean,        // FALSE
    val timeOfDay: String,          // Often morning or during known stressors
    val heartRateElevated: Boolean, // TRUE (if available from wearable)
    val suddenOnset: Boolean        // TRUE (no warning signs)
)
```

#### Distinctive Markers
⚡ **No COB**: BG rise without carb intake  
⚡ **Rapid onset**: Delta jumps from 0 to +10+ in 5-10 min  
⚡ **High curvature**: Sharp peak in phase space  
⚡ **Self-limiting**: Often reverses spontaneously (counter-regulatory response)  
⚡ **Poor insulin prediction**: Classical PKPD models fail to predict this

---

### 1.3 Signature: Hormonal (Dawn Phenomenon, Menstrual Cycle)

#### Phase-Space Characteristics
```
Trajectory shape:

    delta
      ↑
   +4 ┤                    ●─●─●─●─●  ← Plateau
      │                  ╱
   +2 ┤              ●─●
      │          ●─●
    0 ┼●─●─●─●─●
      │
   -2 ┤
      │
      └──────┴────┴────┴────┴────┴────→ BG
         100  110  120  130  140  150  160

Pattern: SLOW RAMP + PLATEAU
- Rise: Very gradual (delta ~+2 to +3 mg/dL/5min)
- Duration: LONG (60-180 min)
- Plateau: Sustained elevation
- Predictability: TIME-CORRELATED (same time each day/month)
```

#### Quantitative Features
```kotlin
data class HormonalSignature(
    // Temporal
    val rampDuration: Int,          // 60-180 min (very long)
    val plateauDuration: Int,       // 60-240 min
    val circadianPhase: Double,     // Strong correlation with time
    
    // Geometric
    val trajectoryShape: Shape,     // LINEAR RAMP
    val curvature: Double,          // <0.05 (very flat)
    val openness: Double,           // 0.8-1.0 (persistently open)
    
    // Response
    val insulinResistance: Double,  // 0.3-0.6 (moderate resistance)
    val coherence: Double,          // 0.4-0.7 (reduced sensitivity)
    
    // Context
    val cobPresent: Boolean,        // FALSE
    val repeatability: Double,      // 0.8-0.95 (VERY predictable timing)
    val basalInadequacy: Double     // 0.6-0.9 (basal too low for this period)
)
```

#### Distinctive Markers
🌅 **Circadian correlation**: Same time every day (dawn: 4-8am)  
🌙 **Menstrual correlation**: Same phase of cycle (luteal phase)  
📈 **Slow persistent rise**: Low delta but sustained  
📉 **Poor closure**: Trajectory stays open despite corrections  
⏰ **Predictable**: Historical data shows recurring pattern  
💊 **Responds to basal adjustment**: Not acute correction

---

### 1.4 Signature: Maladie / Infection

#### Phase-Space Characteristics
```
Trajectory shape:

    delta
      ↑
   +4 ┤  ●─●─●─●─●─●─●─●─●─●  ← Persistent elevation
      │ ╱
   +2 ┤●
      │
    0 ┼●
      │
      └──────┴────┴────┴────┴────┴────→ BG
         140  160  180  200  220  240  260

Pattern: PERSISTENT DIVERGENCE + LOW COHERENCE
- Rise: Moderate but unrelenting
- Corrections: INEFFECTIVE (low coherence ρ < 0.3)
- IOB: Accumulates without effect
- Duration: HOURS to DAYS
```

#### Quantitative Features
```kotlin
data class IllnessSignature(
    // Temporal
    val duration: Int,              // Hours to days (very long)
    val persistenceScore: Double,   // 0.8-1.0 (won't close)
    
    // Geometric
    val trajectoryShape: Shape,     // OPEN DIVERGING
    val openness: Double,           // 0.9-1.0 (extremely open)
    val convergenceVelocity: Double,// <-0.5 (diverging despite IOB)
    
    // Response
    val insulinCoherence: Double,   // <0.3 (VERY poor response)
    val resistanceFactor: Double,   // 2.0-4.0x normal insulin need
    val iobAccumulation: Double,    // High IOB, little effect
    
    // Context
    val ketoneRisk: Boolean,        // Likely elevated
    val historicalDeviation: Double,// 0.8-1.0 (very abnormal for this patient)
    val basalMultiplierNeeded: Double // 2-3x normal
)
```

#### Distinctive Markers
🤒 **Insulin resistance**: Very low coherence (ρ < 0.3)  
📊 **Persistent openness**: Trajectory never closes  
💉 **IOB accumulation**: High IOB without BG response  
⚠️ **Ketone risk**: Important to monitor  
🔍 **Abnormal for patient**: Deviates from learned patterns  
🚨 **Alert clinician**: May need medical intervention

---

### 1.5 Signature: Exercise

#### Aerobic Exercise (Running, Cycling)
```
Trajectory shape:

    delta
      ↑
    0 ┼●
      │ ╲
   -5 ┤  ●─●─●─●─●  ← Sustained drop during exercise
      │           ╲
  -10 ┤            ●
      │             ╲
      │              ●─●  ← Gradual recovery
      └──────┴────┴────┴────┴────→ BG
         100  90   80   70   60   50

Pattern: SUSTAINED DROP + GRADUAL RECOVERY
```

#### Anaerobic Exercise (Sprints, Weightlifting)
```
Trajectory shape:

    delta
      ↑
  +10 ┤        ●─●  ← Adrenaline spike
      │      ╱     ╲
   +5 ┤    ●         ●
      │  ╱             ╲
    0 ┼●                 ●─●─●  ← Extended elevation
      │                       ╲
   -5 ┤                         ●─●  ← Late drop (hours later)
      │
      └──────┴────┴────┴────┴────┴────→ BG
         100  120  140  160  140  120  100

Pattern: SPIKE + PLATEAU + DELAYED DROP
```

#### Quantitative Features
```kotlin
data class ExerciseSignature(
    val type: ExerciseType,         // AEROBIC vs ANAEROBIC
    
    // Aerobic
    val sustainedDropRate: Double,  // -2 to -5 mg/dL/5min
    val duration: Int,              // Matches exercise duration
    val recoverySlope: Double,      // Gradual rebound
    
    // Anaerobic
    val initialSpike: Double,       // +10 to +20 mg/dL (adrenaline)
    val plateauDuration: Int,       // 30-120 min
    val delayedDropRisk: Boolean,   // TRUE (hours later)
    
    // Geometric
    val coherence: Double,          // Low (0.2-0.5) - insulin not main driver
    val predictability: Double,     // 0.6-0.8 if exercise routine
    
    // Context
    val heartRatePattern: String,   // If wearable available
    val timeOfDay: String,          // Morning exercise vs evening
    val carbLoad: Boolean           // Pre-exercise carbs?
)
```

#### Distinctive Markers
💪 **Heart rate correlation**: If wearable data available  
⏱️ **Timing pattern**: Often predictable daily routine  
🏃 **Insulin-independent drop**: Low coherence for aerobic  
⚡ **Paradoxical rise**: Anaerobic creates temporary spike  
⏰ **Delayed effects**: Late hypo risk (2-12 hours post-exercise)

---

### 1.6 Signature: Insulin Malfunction (Site, Pump, Degraded Insulin)

#### Phase-Space Characteristics
```
Trajectory shape:

    delta
      ↑
   +6 ┤              ●─●─●─●  ← Despite high IOB!
      │            ╱
   +3 ┤        ●─●
      │    ●─●
    0 ┼●─●
      │
      └──────┴────┴────┴────┴────→ BG
         120  140  160  180  200  220

Pattern: DIVERGENCE DESPITE HIGH IOB
```

#### Quantitative Features
```kotlin
data class InsulinMalfunctionSignature(
    // Critical indicators
    val iobVsEffect: Double,        // >3.0 (IOB very high, BG still rising)
    val coherence: Double,          // <0.2 (ZERO response)
    val suddenOnset: Boolean,       // Often TRUE (site failure)
    
    // Geometric
    val openness: Double,           // 1.0 (completely open)
    val energyBalance: Double,      // >5.0 (massive accumulation, no dissipation)
    
    // Context
    val timeSinceSiteChange: Int,   // Often >3 days
    val bolusSent: Boolean,         // TRUE (but not working)
    val previousCohrence: Double    // Was normal, suddenly dropped
)
```

#### Distinctive Markers
💉 **IOB paradox**: Very high IOB, zero effect  
🚨 **Sudden coherence drop**: Was 0.7, now 0.1  
⏰ **Site age**: Often >72 hours  
🔧 **Mechanical**: Occlusion, air bubble, kinked cannula  
⚠️ **CRITICAL**: Requires immediate user intervention

---

## 2️⃣ Classification Algorithm

### 2.1 Multi-Modal Feature Extraction

```kotlin
class TrajectorySignatureClassifier(
    private val historyProvider: AIMIHistoryProvider,
    private val contextProvider: ContextDataProvider  // COB, time, HR, etc.
) {
    
    fun classifyDisturbance(trajectory: List<PhaseSpaceState>): DisturbanceClassification {
        
        // Extract geometric features
        val geometric = GeometricFeatures(
            curvature = calculateCurvature(trajectory),
            symmetry = calculateSymmetry(trajectory),
            openness = calculateOpenness(trajectory),
            convergenceVelocity = calculateConvergenceVelocity(trajectory),
            shape = detectShape(trajectory)  // PARABOLIC, SPIKE, RAMP, etc.
        )
        
        // Extract temporal features
        val temporal = TemporalFeatures(
            riseTime = calculateRiseTime(trajectory),
            totalDuration = calculateDuration(trajectory),
            peakTiming = findPeakTime(trajectory),
            circadianPhase = getCurrentCircadianPhase()
        )
        
        // Extract response features
        val response = ResponseFeatures(
            insulinCoherence = calculateCoherence(trajectory),
            iobVsEffect = calculateIOBEfficiency(trajectory),
            energyBalance = calculateEnergyBalance(trajectory),
            predictability = compareToHistorical(trajectory)
        )
        
        // Extract context features
        val context = ContextFeatures(
            cobPresent = contextProvider.getCOB() > 10,
            cobAlignment = calculateCOBAlignment(trajectory),
            timeOfDay = getCurrentHour(),
            dayOfCycle = getMenstrualDayIfApplicable(),
            heartRateElevated = contextProvider.getHeartRate() > baseline + 15,
            timeSinceSiteChange = contextProvider.getTimeSinceSiteChange(),
            recentExercise = contextProvider.getRecentExercise()
        )
        
        // Combine into feature vector
        val features = FeatureVector(geometric, temporal, response, context)
        
        // Classify using decision tree + ML ensemble
        return classify(features)
    }
    
    private fun classify(features: FeatureVector): DisturbanceClassification {
        
        // Decision tree for high-confidence cases
        
        // Rule 1: Insulin malfunction (CRITICAL)
        if (features.response.iobVsEffect > 3.0 && 
            features.response.insulinCoherence < 0.2 &&
            features.geometric.openness > 0.9) {
            return DisturbanceClassification(
                primaryCause = CauseType.INSULIN_MALFUNCTION,
                confidence = 0.95,
                urgency = Urgency.CRITICAL,
                suggestedAction = "Check pump site, infusion set, insulin degradation"
            )
        }
        
        // Rule 2: Meal (with COB correlation)
        if (features.context.cobPresent &&
            features.context.cobAlignment > 0.75 &&
            features.geometric.shape == Shape.PARABOLIC &&
            features.response.insulinCoherence > 0.6) {
            return DisturbanceClassification(
                primaryCause = CauseType.MEAL,
                confidence = 0.90,
                urgency = Urgency.LOW,
                suggestedAction = "Normal meal response, continue monitoring"
            )
        }
        
        // Rule 3: Stress/Adrenaline spike
        if (features.geometric.curvature > 0.4 &&
            features.temporal.riseTime < 15 &&
            !features.context.cobPresent &&
            (features.context.heartRateElevated || features.temporal.peakSharpness > 0.5)) {
            return DisturbanceClassification(
                primaryCause = CauseType.STRESS_ADRENALINE,
                confidence = 0.85,
                urgency = Urgency.MEDIUM,
                suggestedAction = "Stress-induced spike, may self-correct, monitor closely"
            )
        }
        
        // Rule 4: Hormonal (circadian or menstrual)
        if (features.geometric.curvature < 0.05 &&
            features.temporal.riseTime > 60 &&
            features.response.predictability > 0.8 &&
            (features.temporal.circadianPhase in 4..8 || 
             features.context.dayOfCycle in 14..28)) {
            return DisturbanceClassification(
                primaryCause = CauseType.HORMONAL,
                confidence = 0.80,
                urgency = Urgency.LOW,
                suggestedAction = "Consider basal profile adjustment for this time period"
            )
        }
        
        // Rule 5: Illness/Infection
        if (features.response.insulinCoherence < 0.3 &&
            features.geometric.openness > 0.85 &&
            features.temporal.duration > 240 &&
            features.response.iobVsEffect > 2.0) {
            return DisturbanceClassification(
                primaryCause = CauseType.ILLNESS,
                confidence = 0.75,
                urgency = Urgency.HIGH,
                suggestedAction = "Possible illness/infection, check ketones, increase monitoring"
            )
        }
        
        // Rule 6: Aerobic Exercise
        if (features.geometric.convergenceVelocity < -0.8 &&
            features.response.insulinCoherence < 0.4 &&
            features.context.heartRateElevated &&
            features.context.recentExercise?.type == ExerciseType.AEROBIC) {
            return DisturbanceClassification(
                primaryCause = CauseType.EXERCISE_AEROBIC,
                confidence = 0.85,
                urgency = Urgency.MEDIUM,
                suggestedAction = "Exercise-induced drop, consider carbs, reduce temp basal"
            )
        }
        
        // Rule 7: Anaerobic Exercise
        if (features.geometric.shape == Shape.SPIKE_PLATEAU &&
            features.temporal.riseTime < 20 &&
            features.context.recentExercise?.type == ExerciseType.ANAEROBIC) {
            return DisturbanceClassification(
                primaryCause = CauseType.EXERCISE_ANAEROBIC,
                confidence = 0.80,
                urgency = Urgency.MEDIUM,
                suggestedAction = "Anaerobic spike, monitor for delayed drop in 2-6 hours"
            )
        }
        
        // Fallback: ML ensemble for ambiguous cases
        return mlEnsembleClassify(features)
    }
    
    /**
     * Machine Learning ensemble for complex/overlapping cases
     */
    private fun mlEnsembleClassify(features: FeatureVector): DisturbanceClassification {
        // Use trained model (Random Forest, Gradient Boosting, or Neural Net)
        // Trained on historical labeled data
        
        val probabilities = mlModel.predict(features)
        
        // probabilities = {
        //   MEAL: 0.35,
        //   STRESS: 0.15,
        //   HORMONAL: 0.25,
        //   ILLNESS: 0.10,
        //   EXERCISE: 0.05,
        //   ...
        // }
        
        val topCause = probabilities.maxBy { it.value }
        val secondaryCause = probabilities.filter { it.key != topCause.key }.maxBy { it.value }
        
        return DisturbanceClassification(
            primaryCause = topCause.key,
            confidence = topCause.value,
            secondaryCause = secondaryCause?.key,
            secondaryConfidence = secondaryCause?.value,
            urgency = determineUrgency(topCause.key, topCause.value),
            suggestedAction = getActionForCause(topCause.key),
            alternativeCauses = probabilities.filter { it.value > 0.15 }.toMap()
        )
    }
}

// Data classes
data class DisturbanceClassification(
    val primaryCause: CauseType,
    val confidence: Double,              // 0-1
    val secondaryCause: CauseType? = null,
    val secondaryConfidence: Double? = null,
    val urgency: Urgency,
    val suggestedAction: String,
    val alternativeCauses: Map<CauseType, Double> = emptyMap()
)

enum class CauseType {
    MEAL,
    STRESS_ADRENALINE,
    HORMONAL_DAWN,
    HORMONAL_MENSTRUAL,
    ILLNESS,
    EXERCISE_AEROBIC,
    EXERCISE_ANAEROBIC,
    INSULIN_MALFUNCTION,
    UNKNOWN
}

enum class Urgency {
    LOW,        // Normal, expected
    MEDIUM,     // Monitor closely
    HIGH,       // User intervention suggested
    CRITICAL    // Immediate action required
}
```

---

## 3️⃣ Adaptive Control Based on Classification

### 3.1 Cause-Specific Control Strategies

Once the cause is identified, AIMI can adapt its control strategy:

#### Strategy: MEAL Detected
```kotlin
when (classification.primaryCause) {
    CauseType.MEAL -> {
        // Use standard COB-based PKPD
        // Allow normal SMB progression
        // Monitor for under-bolusing
        
        modulation = TrajectoryModulation(
            smbDamping = 1.0,              // Normal SMB
            intervalStretch = 1.0,          // No delay
            basalPreference = 0.3,          // Prefer bolus for meal
            safetyMarginExpand = 1.0,
            reason = "Meal detected: ${classification.confidence.fmt()} confidence"
        )
    }
}
```

#### Strategy: STRESS Detected
```kotlin
    CauseType.STRESS_ADRENALINE -> {
        // Be conservative: stress often self-corrects
        // High risk of late hypo if over-corrected
        
        modulation = TrajectoryModulation(
            smbDamping = 0.6,              // Reduced SMB
            intervalStretch = 1.5,          // Wait longer
            basalPreference = 0.7,          // Prefer temp basal
            safetyMarginExpand = 1.2,       // Wider safety margin
            reason = "Stress spike detected, likely self-limiting"
        )
        
        warnings.add("⚡ Stress-induced rise detected, may reverse spontaneously")
    }
```

#### Strategy: HORMONAL Detected
```kotlin
    CauseType.HORMONAL_DAWN -> {
        // Suggests basal profile inadequacy
        // Permit more aggressive correction
        // Log for profile advisor
        
        modulation = TrajectoryModulation(
            smbDamping = 1.2,              // More aggressive OK
            intervalStretch = 1.0,
            basalPreference = 0.5,          // SMB + temp basal combo
            safetyMarginExpand = 1.0,
            reason = "Dawn phenomenon: basal profile insufficient"
        )
        
        // Alert profile advisor
        profileAdvisor.logHormonalPattern(
            type = "DAWN_PHENOMENON",
            timeWindow = 4..8,  // 4am-8am
            suggestedBasalIncrease = 0.2  // +20% during this period
        )
    }
```

#### Strategy: ILLNESS Detected
```kotlin
    CauseType.ILLNESS -> {
        // High insulin resistance
        // Permit higher doses BUT with extreme caution
        // Alert user to check ketones
        
        modulation = TrajectoryModulation(
            smbDamping = 1.4,              // Higher doses needed
            intervalStretch = 1.0,
            basalPreference = 0.4,
            safetyMarginExpand = 1.3,       // But expand safety margins
            reason = "Illness/resistance detected: coherence ${classification.confidence.fmt()}"
        )
        
        warnings.add(TrajectoryWarning(
            severity = WarningSeverity.HIGH,
            type = "SUSPECTED_ILLNESS",
            message = "Very low insulin response. Possible illness or infection.",
            suggestedAction = "Check ketones, monitor closely, consider medical consultation"
        ))
        
        // Increase monitoring frequency
        recommendedCheckInterval = 15  // Check BG every 15 min
    }
```

#### Strategy: INSULIN MALFUNCTION Detected
```kotlin
    CauseType.INSULIN_MALFUNCTION -> {
        // CRITICAL: Insulin not being delivered
        // DO NOT add more doses until resolved
        // Alert user immediately
        
        modulation = TrajectoryModulation(
            smbDamping = 0.0,              // STOP all SMB
            intervalStretch = 999.0,        // Effectively disabled
            basalPreference = 0.0,          // Stop temp basal too
            safetyMarginExpand = 999.0,
            reason = "CRITICAL: Insulin delivery malfunction suspected"
        )
        
        warnings.add(TrajectoryWarning(
            severity = WarningSeverity.CRITICAL,
            type = "INSULIN_MALFUNCTION",
            message = "⚠️⚠️⚠️ Very high IOB with ZERO glucose response. Possible pump/site failure.",
            suggestedAction = "IMMEDIATE: Check infusion site, tubing, reservoir, insulin. Consider manual injection."
        ))
        
        // Send notification
        sendUrgentNotification("Insulin delivery problem detected - check pump NOW")
        
        // Flag for manual override required
        requireManualConfirmation = true
    }
```

#### Strategy: EXERCISE Detected
```kotlin
    CauseType.EXERCISE_AEROBIC -> {
        // Reduce insulin aggressiveness
        // Prepare for sustained drop
        // Consider carb recommendation
        
        modulation = TrajectoryModulation(
            smbDamping = 0.3,              // Minimal SMB
            intervalStretch = 2.0,          // Long delays
            basalPreference = 0.9,          // Strong basal preference (reduce via temp basal)
            safetyMarginExpand = 1.4,
            reason = "Aerobic exercise: reduce insulin delivery"
        )
        
        // Set low temp basal
        recommendedTempBasal = 0.5  // 50% of normal
        
        // Carb suggestion if BG dropping fast
        if (currentDelta < -5) {
            suggestCarbs = 10  // 10g fast carbs
        }
    }
    
    CauseType.EXERCISE_ANAEROBIC -> {
        // Initial spike: don't over-correct
        // But monitor for delayed drop hours later
        
        modulation = TrajectoryModulation(
            smbDamping = 0.7,
            intervalStretch = 1.5,
            basalPreference = 0.6,
            safetyMarginExpand = 1.1,
            reason = "Anaerobic exercise: transient spike, delayed drop risk"
        )
        
        warnings.add("💪 Anaerobic exercise detected. Monitor for delayed hypoglycemia in 2-6 hours.")
        
        // Set reminder
        scheduleDelayedWarning(
            delayHours = 3,
            message = "Post-exercise hypo risk: consider reducing basal or adding snack"
        )
    }
```

---

## 4️⃣ Learning & Personalization

### 4.1 Building Patient-Specific Signature Database

```kotlin
class SignatureLibrary(private val storage: StorageProvider) {
    
    /**
     * Learn from labeled events
     */
    fun learnFromEvent(event: GlycemicEvent, userLabel: CauseType?) {
        val trajectory = extractTrajectory(event)
        val features = extractFeatures(trajectory)
        
        // Store in personal database
        storage.saveSignature(SignatureRecord(
            timestamp = event.timestamp,
            trajectory = trajectory,
            features = features,
            userLabel = userLabel,
            autoClassification = classify(features),
            outcome = event.outcome  // Did it resolve? Hypo? Hyper?
        ))
        
        // Retrain personal model periodically
        if (storage.getSignatureCount() % 50 == 0) {
            retrainPersonalModel()
        }
    }
    
    /**
     * Compare current event to historical similar events
     */
    fun findSimilarEvents(current: List<PhaseSpaceState>): List<HistoricalMatch> {
        val currentFeatures = extractFeatures(current)
        val allSignatures = storage.getAllSignatures()
        
        return allSignatures
            .map { sig -> 
                HistoricalMatch(
                    signature = sig,
                    similarity = calculateSimilarity(currentFeatures, sig.features),
                    outcome = sig.outcome
                )
            }
            .filter { it.similarity > 0.7 }
            .sortedByDescending { it.similarity }
            .take(5)
    }
    
    private fun calculateSimilarity(f1: FeatureVector, f2: FeatureVector): Double {
        // Weighted Euclidean distance in feature space
        val geometricSim = 1.0 - abs(f1.geometric.curvature - f2.geometric.curvature) / 1.0
        val temporalSim = 1.0 - abs(f1.temporal.riseTime - f2.temporal.riseTime) / 60.0
        val responseSim = 1.0 - abs(f1.response.insulinCoherence - f2.response.insulinCoherence)
        
        return (geometricSim * 0.4 + temporalSim * 0.3 + responseSim * 0.3).coerceIn(0.0, 1.0)
    }
}
```

### 4.2 User Feedback Loop

```
User Interface:

┌─────────────────────────────────────────────┐
│ 🌀 Trajectory Analysis                      │
├─────────────────────────────────────────────┤
│ Current Classification:                     │
│   Primary: MEAL (85% confidence)            │
│   Secondary: STRESS (12%)                   │
│                                             │
│ Similar past events:                        │
│   • 2025-12-28 12:30 - Same pattern        │
│     Resolved naturally in 90 min           │
│   • 2025-12-25 13:15 - Similar rise        │
│     Required +0.8U additional              │
│                                             │
│ ┌─────────────────────────────────────────┐ │
│ │ Was this classification correct?        │ │
│ │                                         │ │
│ │ [✓ Yes - Meal]                         │ │
│ │ [Not quite - it was: __________]       │ │
│ │ [Unsure]                                │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘

→ User feedback improves personal model
```

---

## 5️⃣ Visualizations

### 5.1 Signature Gallery (Educational)

```
Display in AIMI UI: "Learn Your Patterns"

┌─────────────────────────────────────────────┐
│ 📊 Your Glycemic Signatures                 │
├─────────────────────────────────────────────┤
│                                             │
│ 🍽️ MEAL PATTERN (45 occurrences)          │
│    Typical shape: [parabolic arc visual]    │
│    Avg rise time: 25 min                    │
│    Avg duration: 135 min                    │
│    Success rate: 87%                        │
│    → When detected: Standard SMB            │
│                                             │
│ 😰 STRESS PATTERN (12 occurrences)         │
│    Typical shape: [spike visual]            │
│    Avg rise time: 8 min                     │
│    Self-resolved: 75%                       │
│    → When detected: Reduced SMB, wait       │
│                                             │
│ 🌅 DAWN PHENOMENON (Daily 5-7am)           │
│    Typical shape: [slow ramp visual]        │
│    Predictability: 92%                      │
│    → Suggestion: Increase 5am basal by 15%  │
│                                             │
└─────────────────────────────────────────────┘
```

### 5.2 Real-Time Classification Display

```
In rT Console Log:

🌀 TRAJECTORY SIGNATURE ANALYSIS
──────────────────────────────────
  Current shape: PARABOLIC ARC
  Rise time: 22 min (expected: 20-30)
  COB alignment: 0.89 (strong match)
  
  CLASSIFICATION:
    Primary: MEAL (confidence: 0.87)
    Reasoning:
      ✓ COB present (35g)
      ✓ Rise matches carb absorption
      ✓ Good insulin coherence (0.74)
      ✓ Typical parabolic shape
    
  Similar events: 18 matches found
    - Avg outcome: TIR in 105 min
    - Avg additional insulin: 0.6U
  
  CONTROL ADAPTATION:
    SMB modulation: 1.0× (normal)
    Strategy: Standard meal handling
    Confidence: HIGH
```

---

## 6️⃣ Clinical Benefits

### 6.1 Improved Decision Quality

**Before (No Signature Recognition)**:
```
BG rising +6 mg/dL/5min
→ Unknown cause
→ Generic aggressive correction
→ Risk: over-correction if stress spike
```

**After (With Signature Recognition)**:
```
BG rising +6 mg/dL/5min
→ Classified as STRESS (0.85 confidence)
→ Reduced correction (likely self-limiting)
→ Outcome: Avoided late hypo
```

### 6.2 Proactive Profile Optimization

```
After 2 weeks of data:
- DAWN PHENOMENON detected 13/14 mornings
- Occurs 5:15am - 7:30am
- Requires avg +0.35U extra basalper hour

→ AIMI Profile Advisor suggests:
  "Increase basal rate 5am-8am from 0.85 to 1.05 U/hr"
  
User accepts → Future dawn rises prevented
```

### 6.3 Critical Failure Detection

```
IOB = 4.2U
BG = 240 mg/dL and rising
Coherence = 0.08 (VERY low)

→ INSULIN MALFUNCTION detected (0.92 confidence)
→ CRITICAL ALERT: "Check pump site immediately"
→ User checks → finds kinked cannula
→ Site change → BG normalizes

Outcome: Prevented DKA
```

---

## 7️⃣ Implementation Roadmap

### Phase 1: Signature Database (Weeks 1-3)
```
☐ Implement FeatureVector extraction
☐ Create SignatureLibrary storage
☐ Collect baseline signatures from test users
☐ Build initial decision tree classifier
```

### Phase 2: Basic Classification (Weeks 4-6)
```
☐ Implement rule-based classifier (MEAL, STRESS, HORMONAL)
☐ Add to rT console logging
☐ User feedback mechanism
☐ A/B testing: classification accuracy
```

### Phase 3: Control Adaptation (Weeks 7-9)
```
☐ Cause-specific modulation strategies
☐ Integration with TrajectoryGuard
☐ Safety validation (no increased hypo risk)
☐ Logging & monitoring
```

### Phase 4: ML Enhancement (Weeks 10-14)
```
☐ Train ML ensemble on collected data
☐ Personalized model per user
☐ Similarity matching to historical events
☐ Continuous learning updates
```

### Phase 5: Visualization & UX (Weeks 15-17)
```
☐ Signature gallery in UI
☐ Real-time classification display
☐ Educational tooltips
☐ User feedback integration
```

### Phase 6: Advanced Features (Weeks 18+)
```
☐ Wearable integration (heart rate, activity)
☐ Menstrual cycle tracking (optional)
☐ Exercise type auto-detection
☐ Ketone risk prediction
```

---

## 8️⃣ Research Questions & Future Work

### Open Questions

1. **Multi-Factor Events**: How to handle overlapping causes (e.g., meal + stress)?
2. **Individual Variability**: How much personalization is needed?
3. **Rare Events**: How to classify infrequent patterns (e.g., sick days)?
4. **Sensor Noise**: How robust is geometric analysis to CGM noise?
5. **Real-Time Performance**: Can classification run within 5-min AIMI cycle?

### Future Enhancements

- **Federated Learning**: Pool anonymous signatures across users for better classification
- **Explainable AI**: SHAP values to explain ML predictions
- **Causal Inference**: True causal discovery, not just correlation
- **Predictive Alerts**: "Pattern suggests stress spike incoming in 10 min"
- **Integration with Healthcare**: Share signature patterns with endocrinologist

---

## 9️⃣ Conclusion

**Yes, trajectory geometry CAN identify disturbance origins.**

The key insights:
1. **Different causes create different shapes** in phase space
2. **Geometric + temporal + response features** are highly discriminative
3. **Classification enables adaptive control** - right strategy for right cause
4. **Continuous learning** improves accuracy over time
5. **Clinical value** - better outcomes, fewer complications, user empowerment

This transforms AIMI from:
- **Reactive** → **Diagnostic**
- **Generic** → **Personalized**
- **Opaque** → **Explainable**

The loop closes:
```
Observe trajectory → Classify cause → Adapt control → Learn outcome → Improve
```

---

**Next Step**: Implement Phase 1 and validate on historical AIMI data.

