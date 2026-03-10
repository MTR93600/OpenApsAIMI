# 🔧 CONTEXT + TRAJECTORY - INTEGRATION PATCH
## **Detailed Diff for DetermineBasalAIMI2.kt + StringKey.kt**

**Date** : 2026-01-02 22:30  
**Type** : Integration Patch (Review Before Implementation)  
**Files Modified** : 2 files, ~180 lines added

---

## 📋 **TABLE OF CONTENTS**

1. [StringKey.kt - Add Preferences](#stringkey-preferences)
2. [DetermineBasalAIMI2.kt - Imports](#imports)
3. [DetermineBasalAIMI2.kt - Constructor Injection](#constructor)
4. [DetermineBasalAIMI2.kt - Main Integration Point](#main-integration)
5. [DetermineBasalAIMI2.kt - finalizeAndCapSMB Enhancement](#finalize-enhancement)
6. [DetermineBasalAIMI2.kt - rT Logging](#rt-logging)
7. [Complete Example (Before/After)](#example)

---

## 1️⃣ **STRINGKEY.KT - ADD PREFERENCES** {#stringkey-preferences}

**File** : `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/StringKey.kt`

**Location** : Add after existing keys (around line 50-100, wherever keys are defined)

```diff
+ // Context Module
+ ContextEnabled("aimi_context_enabled"),
+ ContextLLMEnabled("aimi_context_llm_enabled"),
+ ContextLLMApiKey("aimi_context_llm_api_key"),
+ ContextMode("aimi_context_mode"), // CONSERVATIVE, BALANCED, AGGRESSIVE
```

**Total** : +4 lines

---

## 2️⃣ **DETERMINEBASALAIMI2.KT - IMPORTS** {#imports}

**File** : `DetermineBasalAIMI2.kt`

**Location** : Top of file, after existing imports (around line 1-30)

```diff
  import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
  import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryHistoryProvider
+ import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
+ import app.aaps.plugins.aps.openAPSAIMI.context.ContextInfluenceEngine
+ import app.aaps.plugins.aps.openAPSAIMI.context.ContextMode
```

**Total** : +3 lines

---

## 3️⃣ **DETERMINEBASALAIMI2.KT - CONSTRUCTOR INJECTION** {#constructor}

**File** : `DetermineBasalAIMI2.kt`

**Location** : Class constructor (around line 80-120, where @Inject dependencies are listed)

```diff
  class DetermineBasalAIMI2 @Inject constructor(
      private val aapsLogger: AAPSLogger,
      private val sp: SP,
      private val rh: ResourceHelper,
      private val profileFunction: ProfileFunction,
      private val activePlugin: ActivePlugin,
      // ... other dependencies
      private val trajectoryGuard: TrajectoryGuard,
      private val trajectoryHistoryProvider: TrajectoryHistoryProvider,
+     private val contextManager: ContextManager,
+     private val contextInfluenceEngine: ContextInfluenceEngine
  ) {
```

**Total** : +2 lines

---

## 4️⃣ **DETERMINEBASALAIMI2.KT - MAIN INTEGRATION POINT** {#main-integration}

**File** : `DetermineBasalAIMI2.kt`

**Location** : Inside `determine(...)` function, BEFORE final decision calculation

**Current Code Structure** (approximate line 800-1200) :
```kotlin
fun determine(...): DetermineBasalResultAIMI2 {
    // ... existing code ...
    
    // IOB/COB calculation
    val iob = ...
    val cob = ...
    
    // PKPD predictions
    val predictions = ...
    
    // Core SMB calculation
    var smbProposal = calculateCoreSMB(...)
    
    // Trajectory Guard (EXISTING - may already be here)
    val trajectoryResult = trajectoryGuard.analyze(...)
    
    // ⬇️ INSERT CONTEXT HERE (BEFORE finalizeAndCapSMB)
    
    // Final decision
    finalizeAndCapSMB(rT, smbProposal, ...)
}
```

**PATCH TO ADD** (around line 1100-1200, BEFORE finalizeAndCapSMB call) :

```diff
      // ... existing PKPD, safety checks, etc ...
      
+     // ═══════════════════════════════════════════════════════════════════
+     // CONTEXT MODULE INTEGRATION
+     // ═══════════════════════════════════════════════════════════════════
+     
+     var contextInfluence: ContextInfluenceEngine.ContextInfluence? = null
+     var trajectoryModulation = 1.0f
+     var trajectoryIntervalAdd = 0
+     var trajectoryPreferBasal = false
+     
+     // Check if modules enabled
+     val contextEnabled = sp.getBoolean(StringKey.ContextEnabled.key, false)
+     val trajectoryEnabled = sp.getBoolean(StringKey.TrajectoryGuardEnabled.key, false)
+     
+     if (trajectoryEnabled || contextEnabled) {
+         consoleLog.add("═══ TRAJECTORY + CONTEXT GUARDS ═══")
+     }
+     
+     // ─────────────────────────────────────────────────────────────────
+     // TRAJECTORY GUARD (if enabled)
+     // ─────────────────────────────────────────────────────────────────
+     
+     if (trajectoryEnabled) {
+         try {
+             val trajectoryResult = trajectoryGuard.analyze(
+                 currentBG = glucoseStatus.glucose,
+                 delta = glucoseStatus.delta,
+                 shortAvgDelta = glucoseStatus.shortAvgDelta,
+                 iob = iob,
+                 historyProvider = trajectoryHistoryProvider
+             )
+             
+             // Extract modulations
+             trajectoryModulation = trajectoryResult.smbFactor
+             trajectoryIntervalAdd = trajectoryResult.extraIntervalMin
+             trajectoryPreferBasal = trajectoryResult.preferBasal
+             
+             // Log
+             consoleLog.add(
+                 "🌀 Trajectory: type=${trajectoryResult.type} " +
+                 "score=${trajectoryResult.score.format(2)} " +
+                 "→ smbFactor=${trajectoryModulation.format(2)} " +
+                 "interval=+${trajectoryIntervalAdd}min " +
+                 "preferBasal=$trajectoryPreferBasal"
+             )
+             
+             rT.trajectoryType = trajectoryResult.type.toString()
+             rT.trajectoryScore = trajectoryResult.score
+             rT.trajectoryModulation = trajectoryModulation
+             
+         } catch (e: Exception) {
+             consoleError.add("⚠️ Trajectory Guard error: ${e.message}")
+             aapsLogger.error(LTag.APS, "Trajectory Guard failed", e)
+         }
+     }
+     
+     // ─────────────────────────────────────────────────────────────────
+     // CONTEXT MODULE (if enabled)
+     // ─────────────────────────────────────────────────────────────────
+     
+     if (contextEnabled) {
+         try {
+             // Get context snapshot
+             val snapshot = contextManager.getSnapshot(System.currentTimeMillis())
+             
+             if (snapshot.intentCount > 0) {
+                 // Get context mode from preferences
+                 val modeStr = sp.getString(StringKey.ContextMode.key, "BALANCED")
+                 val mode = when (modeStr) {
+                     "CONSERVATIVE" -> ContextMode.CONSERVATIVE
+                     "AGGRESSIVE" -> ContextMode.AGGRESSIVE
+                     else -> ContextMode.BALANCED
+                 }
+                 
+                 // Compute influence
+                 contextInfluence = contextInfluenceEngine.computeInfluence(
+                     snapshot = snapshot,
+                     currentBG = glucoseStatus.glucose,
+                     iob = iob.iob,
+                     cob = iob.cob,
+                     mode = mode
+                 )
+                 
+                 // Log active intents
+                 val activeIntentsStr = snapshot.activeIntents.joinToString(", ") { intent ->
+                     when (intent) {
+                         is ContextIntent.Activity -> "Activity=${intent.intensity}"
+                         is ContextIntent.Illness -> "Illness=${intent.intensity}"
+                         is ContextIntent.Stress -> "Stress=${intent.intensity}"
+                         is ContextIntent.Alcohol -> "Alcohol=${intent.intensity}"
+                         is ContextIntent.UnannouncedMealRisk -> "MealRisk=${intent.intensity}"
+                         else -> intent::class.simpleName ?: "Unknown"
+                     }
+                 }
+                 
+                 consoleLog.add("🎯 Context: $activeIntentsStr (${snapshot.intentCount} active)")
+                 
+                 // Log influence
+                 consoleLog.add(
+                     "   → smbClamp=${contextInfluence.smbFactorClamp.format(2)} " +
+                     "interval=+${contextInfluence.extraIntervalMin}min " +
+                     "preferBasal=${contextInfluence.preferBasal}"
+                 )
+                 
+                 // Log reasoning
+                 contextInfluence.reasoningSteps.forEach { reason ->
+                     consoleLog.add("   • $reason")
+                 }
+                 
+                 rT.contextActiveIntents = activeIntentsStr
+                 rT.contextModulation = contextInfluence.smbFactorClamp
+                 
+             } else {
+                 consoleLog.add("🎯 Context: No active intents")
+             }
+             
+         } catch (e: Exception) {
+             consoleError.add("⚠️ Context Module error: ${e.message}")
+             aapsLogger.error(LTag.APS, "Context Module failed", e)
+         }
+     }
+     
+     // ─────────────────────────────────────────────────────────────────
+     // COMPOSE INFLUENCES (Trajectory + Context)
+     // ─────────────────────────────────────────────────────────────────
+     
+     if (trajectoryEnabled || contextEnabled) {
+         val contextFactor = contextInfluence?.smbFactorClamp ?: 1.0f
+         val contextInterval = contextInfluence?.extraIntervalMin ?: 0
+         val contextPreferBasal = contextInfluence?.preferBasal ?: false
+         
+         // Compose SMB factor (multiplicative)
+         val composedSmbFactor = (trajectoryModulation * contextFactor).coerceIn(0.50f, 1.10f)
+         
+         // Compose interval (max)
+         val composedIntervalAdd = maxOf(trajectoryIntervalAdd, contextInterval)
+         
+         // Compose preferBasal (OR)
+         val composedPreferBasal = trajectoryPreferBasal || contextPreferBasal
+         
+         // Apply to SMB proposal
+         val smbBeforeGuards = smbProposal
+         smbProposal = (smbProposal * composedSmbFactor).toFloat()
+         
+         consoleLog.add(
+             "📊 Applied: smbCore=${smbBeforeGuards.format(2)}U " +
+             "× ${composedSmbFactor.format(2)} " +
+             "= ${smbProposal.format(2)}U"
+         )
+         
+         consoleLog.add(
+             "   interval=+${composedIntervalAdd}min " +
+             "preferBasal=$composedPreferBasal"
+         )
+         
+         rT.finalSmbFactor = composedSmbFactor
+         
+         // Store for finalizeAndCapSMB
+         rT.trajectoryContextIntervalAdd = composedIntervalAdd
+         rT.trajectoryContextPreferBasal = composedPreferBasal
+     }
+     
+     consoleLog.add("═══════════════════════════════════")
      
      // ... continue with finalizeAndCapSMB ...
```

**Total** : +145 lines (but well-structured and commented)

---

## 5️⃣ **DETERMINEBASALAIMI2.KT - FINALIZANDCAPSMB ENHANCEMENT** {#finalize-enhancement}

**File** : `DetermineBasalAIMI2.kt`

**Location** : Inside `finalizeAndCapSMB(...)` function (around line 1467-1600)

**EXISTING CODE** (approximate structure) :
```kotlin
private fun finalizeAndCapSMB(
    rT: DetermineBasalResultAIMI2,
    smb: Double,
    reason: String,
    mealData: MealData,
    threshold: Double,
    forceBolus: Boolean = false,
    decisionSource: String = "Core"
): Double {
    // ... existing caps and safety checks ...
    
    var finalSmb = smb
    
    // Apply various caps
    finalSmb = applyMaxIOBCap(finalSmb, ...)
    finalSmb = applyPKPDCap(finalSmb, ...)
    // etc.
    
    return finalSmb
}
```

**PATCH TO ADD** (at the BEGINNING of function, after parameter declaration) :

```diff
  private fun finalizeAndCapSMB(
      rT: DetermineBasalResultAIMI2,
      smb: Double,
      reason: String,
      mealData: MealData,
      threshold: Double,
      forceBolus: Boolean = false,
      decisionSource: String = "Core"
  ): Double {
      
+     // ═══════════════════════════════════════════════════════════════════
+     // TRAJECTORY + CONTEXT INFLUENCE (if present in rT)
+     // ═══════════════════════════════════════════════════════════════════
+     
+     // Check if Trajectory/Context modulation was applied
+     val hasTrajectoryContext = rT.finalSmbFactor != null && rT.finalSmbFactor != 1.0f
+     
+     if (hasTrajectoryContext) {
+         consoleLog.add("🔒 Gating: Trajectory/Context modulation already applied")
+         // SMB was already modulated in main flow
+         // Just respect preferBasal flag if set
+         
+         if (rT.trajectoryContextPreferBasal == true && smb > 0) {
+             consoleLog.add("   → preferBasal=true, converting SMB to TBR")
+             
+             // Convert SMB to equivalent TBR
+             // This is a simplification - real logic would be more sophisticated
+             val equivalentTBR = calculateEquivalentTBR(smb, profile.current_basal)
+             
+             rT.rate = equivalentTBR
+             rT.duration = 30 // Standard TBR duration
+             
+             consoleLog.add("   → TBR=${equivalentTBR.format(2)}U/h for 30min instead of SMB=${smb.format(2)}U")
+             
+             return 0.0 // No SMB
+         }
+     }
      
      var finalSmb = smb
      
      // ... rest of existing finalizeAndCapSMB logic ...
```

**Total** : +30 lines

---

## 6️⃣ **DETERMINEBASALAIMI2.KT - RT LOGGING** {#rt-logging}

**File** : `DetermineBasalAIMI2.kt`

**Location** : At the END of `determine(...)` function, when building final `rT.reason`

**EXISTING CODE** (approximate line 1800-1900) :
```kotlin
// Build final reason string
rT.reason = buildString {
    append("Adjustments : MaxIob ${maxIOB}U\n")
    append("Decision: SMB=${rT.smb}U TBR=${rT.rate}U/h\n")
    // ... other info ...
}
```

**PATCH TO ADD** (enhance reason string with Trajectory + Context) :

```diff
  rT.reason = buildString {
      append("Adjustments : MaxIob ${profile.max_iob.format(2)} U\n")
      
+     // Trajectory Guard info
+     if (rT.trajectoryType != null) {
+         append("🌀 Trajectory: type=${rT.trajectoryType} ")
+         append("score=${rT.trajectoryScore?.format(2)} ")
+         append("→ smb×${rT.trajectoryModulation?.format(2)} ")
+         append("interval=+${rT.trajectoryContextIntervalAdd ?: 0}min\n")
+     }
+     
+     // Context info
+     if (rT.contextActiveIntents != null) {
+         append("🎯 Context: ${rT.contextActiveIntents}\n")
+         append("   → smb×${rT.contextModulation?.format(2)}\n")
+     }
+     
+     // Composed result
+     if (rT.finalSmbFactor != null && rT.finalSmbFactor != 1.0f) {
+         append("📊 Final: smb×${rT.finalSmbFactor?.format(2)} ")
+         append("interval=${rT.trajectoryContextIntervalAdd ?: 0}min ")
+         append("preferBasal=${rT.trajectoryContextPreferBasal ?: false}\n")
+     }
      
      // ... rest of existing reason build ...
  }
```

**Total** : +20 lines

---

## 7️⃣ **COMPLETE EXAMPLE (BEFORE/AFTER)** {#example}

### **BEFORE (Current rT output)**

```
Adjustments : MaxIob 10,00 U
🛑 LGS: BG=166 ≤ 86 → TBR 0U/h (30m)
 | ⚠ Safety Halt: LGS_TRIGGER: min=40 <= Th=86
```

### **AFTER (With Trajectory + Context)**

```
Adjustments : MaxIob 10,00 U
🌀 Trajectory: type=ORBIT score=0.82 → smb×0.95 interval=+2min
🎯 Context: Activity=HIGH, Illness=MEDIUM
   → smb×0.80
   • Activity HIGH → SMB×0.75 +4min preferBasal=true
   • Illness MEDIUM BG=166 → SMB×1.05 +1min
📊 Final: smb×0.76 interval=4min preferBasal=true
Decision: SMB=0.00U TBR=0.85U/h (30m) [preferBasal applied]
```

---

## 📊 **SUMMARY OF CHANGES**

| File | Lines Added | Complexity |
|------|-------------|------------|
| **StringKey.kt** | +4 | Low |
| **DetermineBasalAIMI2.kt** (imports) | +3 | Low |
| **DetermineBasalAIMI2.kt** (constructor) | +2 | Low |
| **DetermineBasalAIMI2.kt** (main integration) | +145 | **High** |
| **DetermineBasalAIMI2.kt** (finalizeAndCapSMB) | +30 | Medium |
| **DetermineBasalAIMI2.kt** (rT logging) | +20 | Low |
| **TOTAL** | **~204 lines** | - |

---

## ⚠️ **CRITICAL NOTES**

### **1. rT Fields Required**

You'll need to add these fields to `DetermineBasalResultAIMI2`:

```kotlin
// In DetermineBasalResultAIMI2.kt
var trajectoryType: String? = null
var trajectoryScore: Double? = null
var trajectoryModulation: Float? = null

var contextActiveIntents: String? = null
var contextModulation: Float? = null

var finalSmbFactor: Float? = null
var trajectoryContextIntervalAdd: Int? = null
var trajectoryContextPreferBasal: Boolean? = null
```

### **2. Helper Functions Needed**

```kotlin
// Add to DetermineBasalAIMI2
private fun Float.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

private fun Double.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

private fun calculateEquivalentTBR(smb: Double, currentBasal: Double): Double {
    // Simplified: spread SMB over 30 min as TBR
    val smbHourly = smb * 2 // SMB spread over 30min → hourly rate
    return currentBasal + smbHourly
}
```

---

## 🎯 **INTEGRATION STRATEGY**

### **Phase 1 : Minimal (This Patch)**
- Add all code above
- No UI yet
- Test via logs only
- Verify build success

### **Phase 2 : Preferences UI** (Later)
- Add UI section in OpenAPSAIMIPlugin
- Switches for enabled flags
- Text field for API key
- Dropdown for mode

### **Phase 3 : Context UI** (Later)
- ContextActivity screen
- Preset buttons
- Active intents list

---

## ✅ **VALIDATION CHECKLIST**

Before implementing, verify:

- [ ] ContextManager injected correctly
- [ ] StringKey additions compile
- [ ] rT fields added to result class
- [ ] Helper functions present
- [ ] No circular dependencies
- [ ] Trajectory Guard module exists and accessible
- [ ] All imports resolve

---

## 🚀 **NEXT STEPS**

1. **Review this patch** carefully
2. **Ask questions** if anything unclear
3. **I'll implement** once you approve
4. **We'll test** with logs
5. **Then UI** if it works

---

**Ready for your review !** 🎯

**Questions ?** Ask now before implementation.

---
