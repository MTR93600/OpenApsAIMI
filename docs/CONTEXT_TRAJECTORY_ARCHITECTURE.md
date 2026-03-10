# 🏗️ CONTEXT CHAT + TRAJECTORY GUARD - ARCHITECTURE COMPLÈTE
## **Expert Kotlin Senior - Production Ready**

**Date** : 2026-01-02 21:45  
**Architecte** : Lyra  
**Objectif** : Intégration sécurisée Context + Trajectory dans AIMI

---

## 📋 **RÉSUMÉ EXÉCUTIF**

Cette architecture intègre:
1. **Trajectory Guard** (✅ DÉJÀ EXISTANT) - Analyse phase-space 6 types
2. **Context Module** (🆕 NOUVEAU) - User context via chat/presets + LLM optionnel
3. **Point unique de gating** (🔒 SÉCURITÉ) - `finalizeAndCapSMB` obligatoire

**Règles de sécurité** :
- ❌ LLM ne décide JAMAIS de dose
- ✅ Offline-first (pas de crash sans réseau)
- ✅ Soft-control (±10% max)
- ✅ Traçabilité complète (rT détaillé)

---

## 🎯 **ARCHITECTURE GLOBALE**

### **Pipeline de décision (AIMI Core)**

```
┌─────────────────────────────────────────────────────────────┐
│                    USER INPUT (optional)                    │
│  Chat: "heavy cardio 1h" / Presets: Activity=HIGH ⏱️60min  │
└──────────────────────────────┬──────────────────────────────┘
                               ↓
┌──────────────────────────────────────────────────────────────┐
│              CONTEXT MODULE (🆕 NEW)                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ContextManager                                      │    │
│  │  - Store intents (Activity/Illness/Stress/Meal...)│    │
│  │  - Lifecycle management (start/end/expire)         │    │
│  │  - LLM parsing (optional) + Offline fallback       │    │
│  └─────────────────────────────────────────────────────┘    │
│              ↓                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ContextSnapshot (state at tick T)                  │    │
│  │  - Active intents aggregation                      │    │
│  │  - Flags: hasActivity, hasIllness, hasMealRisk...  │    │
│  └─────────────────────────────────────────────────────┘    │
│              ↓                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ ContextInfluenceEngine                              │    │
│  │  → preferBasal: Boolean                            │    │
│  │  → smbFactorClamp: Float (0.5..1.1)               │    │
│  │  → extraIntervalMin: Int (0..10)                   │    │
│  │  → autodriveEligibilityBoost: Boolean              │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
                               ↓
┌──────────────────────────────────────────────────────────────┐
│          AIMI CORE CALCULATION                               │
│  - IOB/COB                                                   │
│  - PKPD predictions                                          │
│  - ISF/CR adaptive                                           │
│  - Core SMB proposal: smbCore                                │
│  - Core TBR proposal: tbrCore                                │
└──────────────────────────┬───────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────┐
│       TRAJECTORY GUARD (✅ EXISTING)                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ TrajectoryHistoryProvider                           │    │
│  │  - Last 20min BG history                           │    │
│  │  - Delta, shortAvgDelta, acceleration             │    │
│  └─────────────────────────────────────────────────────┘    │
│              ↓                                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ TrajectoryGuard.analyze()                           │    │
│  │  → type: CONVERGENT/ORBIT/STABLE/DIVERGENT/...     │    │
│  │  → score: Float                                     │    │
│  │  → recommendation:                                  │    │
│  │      • smbFactor: Float (0.90..1.10)               │    │
│  │      • extraIntervalMin: Int (0..6)                │    │
│  │      • preferBasal: Boolean                        │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────┐
│         INFLUENCE COMPOSITION (🔒 SAFE MERGE)                │
│                                                              │
│  finalSmbFactor = clamp(                                     │
│      coreFactor                                              │
│      * trajectoryFactor    // 0.90..1.10                     │
│      * contextFactor,      // 0.50..1.10                     │
│      min = 0.50,                                             │
│      max = 1.10                                              │
│  )                                                           │
│                                                              │
│  finalExtraInterval = max(                                   │
│      trajectoryInterval,   // 0..6 min                       │
│      contextInterval       // 0..10 min                      │
│  )                                                           │
│                                                              │
│  finalPreferBasal = trajectoryPreferBasal                    │
│                      || contextPreferBasal                   │
└──────────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────┐
│      SAFETY LAYERS (PKPD, Limits, LGS...)                    │
│  - Max IOB check                                             │
│  - PKPD bounds                                               │
│  - LGS trigger                                               │
│  - Safety halt conditions                                    │
└──────────────────────────┬───────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────┐
│    🔒 SINGLE GATING POINT: finalizeAndCapSMB()               │
│                                                              │
│  Input:                                                      │
│    - smbProposal (with trajectory + context modulation)     │
│    - tbrProposal                                             │
│    - preferBasal flag                                        │
│    - extraIntervalMin                                        │
│                                                              │
│  Output (to rT):                                             │
│    - final SMB (after all caps)                              │
│    - final TBR                                               │
│    - final interval                                          │
│    - reason (with Trajectory + Context explanation)          │
└──────────────────────────┬───────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────────┐
│                  ACTUATION (PUMP)                            │
│  - Enact SMB if > 0                                          │
│  - Set TBR if changed                                        │
│  - Schedule next loop tick (+interval)                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 📦 **MODULES & FILES**

### **🆕 Context Module** (`app.aaps.plugins.aps.openAPSAIMI.context`)

| File | Lines | Description |
|------|-------|-------------|
| **ContextIntent.kt** | ~300 | ✅ CRÉÉ - Sealed classes for all intent types |
| **ContextManager.kt** | ~200 | 🔨 À CRÉER - Storage + lifecycle management |
| **ContextInfluenceEngine.kt** | ~250 | 🔨 À CRÉER - Intent → modulation logic |
| **ContextParser.kt** | ~150 | 🔨 À CRÉER - Offline + LLM parsing |
| **ContextLLMClient.kt** | ~180 | 🔨 À CRÉER - Optional LLM integration |
| **ContextPresets.kt** | ~100 | 🔨 À CRÉER - UI preset definitions |

**TOTAL** : ~1180 lignes

---

### **✅ Trajectory Module** (`app.aaps.plugins.aps.openAPSAIMI.trajectory`)

| File | Lines | Status |
|------|-------|--------|
| TrajectoryGuard.kt | 400 | ✅ EXISTANT |
| TrajectoryHistoryProvider.kt | 300 | ✅ EXISTANT |
| PhaseSpaceModels.kt | 350 | ✅ EXISTANT |
| TrajectoryMetricsCalculator.kt | 250 | ✅ EXISTANT |

**TOTAL** : ~1300 lignes

---

### **🔧 Core Integration** (`DetermineBasalAIMI2.kt`)

| Modification | Lines | Impact |
|--------------|-------|--------|
| Context injection | +50 | Medium |
| Trajectory integration refinement | +30 | Low |
| Influence composition logic | +80 | High |
| finalizeAndCapSMB enhancement | +60 | Critical |
| rT logging enhancement | +40 | Medium |

**TOTAL** : ~260 lignes ajoutées

---

## 🔐 **SÉCURITÉ : RÈGLES STRICTES**

### **Rule #1 : LLM Never Decides Dose**

```kotlin
// ❌ FORBIDDEN
val smb = llm.getSMBDecision() // JAMAIS !

// ✅ CORRECT
val intents: List<ContextIntent> = llm.parseUserInput(text)
val influence: ContextInfluence = engine.compute(snapshot)
val smbAfterInfluence = clamp(smbCore * influence.smbFactor, 0.5, 1.1)
```

**Garantie** : LLM produit uniquement des `ContextIntent` structurés. L'influence est toujours bornée et passe par le gating.

---

### **Rule #2 : Offline First**

```kotlin
class ContextManager(...) {
    fun addIntent(text: String): ContextIntent {
        return if (preferences.contextLLMEnabled && networkAvailable()) {
            try {
                llmClient.parse(text, timeout = 3.seconds)
            } catch (e: Exception) {
                logger.warn("LLM offline, using fallback parser")
                offlineParser.parse(text) // ✅ ALWAYS WORKS
            }
        } else {
            offlineParser.parse(text) // ✅ DEFAULT
        }
    }
}
```

**Garantie** : Aucun crash si réseau KO, timeout, ou API key manquante.

---

### **Rule #3 : Soft Control (±10% max)**

```kotlin
data class ContextInfluence(
    val smbFactorClamp: Float,     // MUST be in [0.5, 1.1]
    val extraIntervalMin: Int,     // MUST be in [0, 10]
    val preferBasal: Boolean
) {
    init {
        require(smbFactorClamp in 0.5f..1.1f) {
            "smbFactorClamp must be [0.5, 1.1], got $smbFactorClamp"
        }
        require(extraIntervalMin in 0..10) {
            "extraIntervalMin must be [0, 10], got $extraIntervalMin"
        }
    }
}
```

**Garantie** : Validation at construction, impossible de dépasser les bounds.

---

### **Rule #4 : Traçabilité (rT Premium)**

```kotlin
// ✅ rT MUST explain everything
rT.reason = """
Adjustments : MaxIob 10,00 U
🌀 Trajectory: type=ORBIT score=0.82 → smbFactor=0.95 interval=+2min preferBasal=false
🎯 Context: Activity=HIGH Illness=MED → prefer Basal, smbClamp=0.90 interval=+4min
📊 Applied: smbCore=0.80U * 0.95 * 0.90 = 0.68U interval=5+max(2,4)=11min
🚦 Safety: LGS=OK PKPD=OK MaxIOB=OK → ENACTED
""".trimIndent()

// Structured fields for Nightscout
rT.trajectoryType = "ORBIT"
rT.trajectoryScore = 0.82
rT.trajectoryModulation = 0.95
rT.contextActiveIntents = "Activity=HIGH,Illness=MED"
rT.contextModulation = 0.90
rT.finalSmbFactor = 0.855 // 0.95 * 0.90
```

---

## 🎨 **UX PREMIUM**

### **Écran 1 : AIMI Context**

```
┌──────────────────────────────────────────────────────────────┐
│ ⬅️  AIMI Context                                    ⋮ Menu   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  💬 Quick Presets                                            │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ 🏃 Cardio   💪 Strength  🧘 Yoga   🤒 Sick  😰 Stress  │ │
│  │ 🍕 Meal Risk  🍷 Alcohol  ✈️ Travel  🩸 Period        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  📝 Chat (Optional)                                          │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Type your context...                                   │ │
│  │                                                        │ │
│  │ Examples:                                              │ │
│  │ • "heavy cardio session 1 hour"                       │ │
│  │ • "sick with flu, insulin resistant"                  │ │
│  │ • "eating out tonight, unannounced carbs"             │ │
│  └────────────────────────────────────────────────────────┘ │
│  [Send] [Clear]                                              │
│                                                              │
│  📋 Active Contexts                                          │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ 🏃 Activity: Cardio HIGH                               │ │
│  │    Started: 18:30  |  Ends: 19:30  |  ⏱️ 32min left    │ │
│  │    Effect: Prefer basal, -10% SMB, +4min interval      │ │
│  │    [Stop] [Extend +30min]                              │ │
│  ├────────────────────────────────────────────────────────┤ │
│  │ 🤒 Illness: Flu MEDIUM                                 │ │
│  │    Started: 14:00  |  Ends: Tomorrow 14:00             │ │
│  │    Effect: Resistant, higher TBR OK, careful SMB       │ │
│  │ [   Stop] [Mark as resolved]                              │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ⚙️ Settings                                                 │
│  [●] Enable Context Module                                  │
│  [○] Enable LLM Parsing (requires API key)                  │
│      Mode: ◉ Conservative  ○ Balanced  ○ Aggressive         │
└──────────────────────────────────────────────────────────────┘
```

---

### **Écran 2 : AIMI Trajectory Monitor**

```
┌──────────────────────────────────────────────────────────────┐
│ ⬅️  Trajectory Monitor                              ⋮ Menu   │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  🌀 Current Trajectory                                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                                                        │ │
│  │  Type: ⭕ STABLE ORBIT                                 │ │
│  │  Score: 0.82 / 1.00                                    │ │
│  │  Confidence: HIGH ✅                                    │ │
│  │                                                        │ │
│  │  📊 Last 20 minutes:                                   │ │
│  │  BG: 166 → 164 → 162 → 164 → 166 (stable oscillation) │ │
│  │  Delta: -4 → -2 → +2 → +2 (low variance)              │ │
│  │  Accel: -0.5 (gentle deceleration)                    │ │
│  │                                                        │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  🎯 Modulation Applied                                       │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  SMB Factor:      0.95  (gentle -5%)                  │ │
│  │  Extra Interval:  +2 min                               │ │
│  │  Prefer Basal:    No  (stay reactive)                 │ │
│  │                                                        │ │
│  │  💡 Reasoning: Orbit stable around target. Safe to    │ │
│  │     maintain current strategy with slight damping.    │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  📈 History (last 2 hours)                                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  17:00  CONVERGENT  → Increased SMB +5%               │ │
│  │  17:30  ORBIT       → Maintained, damped -5%           │ │
│  │  18:00  DIVERGENT   → Interval +4min, prefer basal    │ │
│  │  18:30  ORBIT       → Back to stable (current)         │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ℹ️ Trajectory Guard is ACTIVE since 20min                  │
│  [Disable Temporarily] [View Full Analysis]                 │
└──────────────────────────────────────────────────────────────┘
```

---

## 🧪 **TESTS OBLIGATOIRES**

### **Test #1 : BG Drift Lent + Context Activity**

**Setup**:
```kotlin
// BG: 140 → 142 → 144 → 145 (lent)
// Delta: +2, +2, +1 (faible)
// Context: Activity=HIGH (cardio)
```

**Expected**:
```
Trajectory: DIVERGENT (s'éloigne lentement de target)
  → trajectoryFactor = 0.92
  → trajectoryInterval = +3min

Context: Activity=HIGH
  → contextFactor = 0.85 (limiter SMB)
  → contextInterval = +4min
  → preferBasal = true

Applied:
  finalSmbFactor = 0.92 * 0.85 = 0.782
  finalInterval = max(3, 4) = 7min total
  TBR preferred over SMB
```

**Logs attendus**:
```
🌀 Trajectory: DIVERGENT score=0.68 → factor=0.92 interval=+3min
🏃 Context: Activity=HIGH → factor=0.85 interval=+4min preferBasal=true
📊 Applied: smb=0.50U*0.782=0.39U interval=12min TBR=0.8U/h
```

---

### **Test #2 : BG <120 + Illness Context**

**Setup**:
```kotlin
// BG: 118 → 115 → 114 (descente légère)
// Context: Illness=MEDIUM (résistance)
```

**Expected**:
```
Trajectory: CONVERGENT (vers target bas)
  → trajectoryFactor = 1.02 (légère accélération OK)
  → trajectoryInterval = +1min

Context: Illness=MEDIUM + BG<120 → CONFLICT
  → Illness suggère résistance (plus insuline)
  → Mais BG bas → SAFETY OVERRIDE
  → contextFactor = 0.95 (prudence)
  → contextInterval = +2min

Applied:
  finalSmbFactor = 1.02 * 0.95 = 0.969
  finalInterval = max(1, 2) = 3min
  Conservative approach (safety first)
```

---

### **Test #3 : Chain SMB (3 en 30min) → UNSTABLE**

**Setup**:
```kotlin
// SMB history: 0.5U @ T-30, 0.6U @ T-15, 0.7U @ T-5
// BG: zigzag 150 → 180 → 140 → 170
```

**Expected**:
```
Trajectory: UNSTABLE (haute variabilité)
  → trajectoryFactor = 0.88 (damping fort)
  → trajectoryInterval = +6min (ralentir)
  → preferBasal = true

Context: None active
  → contextFactor = 1.0
  → contextInterval = 0

Applied:
  finalSmbFactor = 0.88
  finalInterval = 6min
  Switch to TBR-heavy strategy
```

---

### **Test #4 : Sickness Intent ON + Near Peak IOB**

**Setup**:
```kotlin
// Context: Illness=HIGH (résistance forte)
// IOB: 3.5U (near peak, dans 30min)
// BG: 180 mg/dL stable
```

**Expected**:
```
Context: Illness=HIGH
  → contextFactor = 1.05 (permettre plus)
  → MAIS peak IOB imminent → SAFETY CHECK
  → Limiter agressivité si IOB>3U && timeToP

eak<45min
  → contextFactor downgraded to 0.98

Applied:
  SMB allowed but capped
  TBR preferred for sustained correction
```

---

### **Test #5 : Cardio Intent ON + Post-Sport (4h après)**

**Setup**:
```kotlin
// Context: Activity finished 4h ago
// Expected post-effect: +4h sensitivity
// BG: 100 mg/dL stable
```

**Expected**:
```
Context: Activity residual effect ACTIVE
  → contextFactor = 0.80 (haute sensibilité)
  → contextInterval = +5min
  → preferBasal = true

Applied:
  Very conservative SMB
  Prefer sustained basal reduction
  Hypo prevention mode
```

---

### **Test #6 : Network OFF + LLM Enabled**

**Setup**:
```kotlin
// User: "heavy cardio 1 hour"
// Network: OFFLINE
// LLM enabled: true
```

**Expected**:
```
[INFO] ContextManager: LLM parsing attempt
[WARN] ContextLLMClient: Timeout after 3s, network unreachable
[INFO] ContextManager: Falling back to offline parser
[INFO] ContextParser: Matched pattern "cardio" → Activity(type=CARDIO, intensity=HIGH, duration=60min)
[INFO] ContextManager: Intent added successfully (offline mode)

✅ NO CRASH
✅ Intent correctly parsed
✅ Loop continues normally
```

---

## 📋 **IMPLEMENTATION CHECKLIST**

### **Phase 1 : Context Module Core** (4h estimé)
- [x] ✅ ContextIntent.kt (sealed classes)
- [ ] 🔨 ContextManager.kt (storage + lifecycle)
- [ ] 🔨 ContextInfluenceEngine.kt (logic)
- [ ] 🔨 ContextParser.kt (offline parsing)
- [ ] 🔨 ContextLLMClient.kt (optional LLM)
- [ ] 🔨 ContextPresets.kt (UI presets)

### **Phase 2 : Integration DetermineBasalAIMI2** (3h estimé)
- [ ] 🔨 Inject ContextManager
- [ ] 🔨 Get ContextSnapshot at tick
- [ ] 🔨 Compute ContextInfluence
- [ ] 🔨 Compose Trajectory + Context
- [ ] 🔨 Pass to finalizeAndCapSMB
- [ ] 🔨 Enhanced rT logging

### **Phase 3 : Preferences & Keys** (2h estimé)
- [ ] 🔨 Add BooleanKey: ContextEnabled
- [ ] 🔨 Add BooleanKey: ContextLLMEnabled
- [ ] 🔨 Add StringKey: ContextLLMApiKey
- [ ] 🔨 Add StringKey: ContextMode (Conservative/Balanced/Aggressive)
- [ ] 🔨 Add preferences UI section

### **Phase 4 : UX Premium** (6h estimé)
- [ ] 🔨 ContextActivity.kt (écran context)
- [ ] 🔨 TrajectoryMonitorActivity.kt (écran trajectory)
- [ ] 🔨 XML layouts + Material Design 3
- [ ] 🔨 ViewModels + StateFlow
- [ ] 🔨 Presets UI (chips)
- [ ] 🔨 Chat UI (optional)

### **Phase 5 : Testing** (2h estimé)
- [ ] 🔨 Unit tests (6 scenarios)
- [ ] 🔨 Build validation
- [ ] 🔨 Integration tests
- [ ] 🔨 rT output validation

**TOTAL ESTIMÉ** : ~17 heures (1 dev senior)

---

## 🎯 **NEXT STEPS IMMÉDIATS**

### **Action #1 : Terminer Context Core**

Je vais maintenant créer les fichiers manquants du module Context:
1. ContextManager.kt
2. ContextInfluenceEngine.kt
3. ContextParser.kt (offline)
4. ContextLLMClient.kt (optional)

### **Action #2 : Patch DetermineBasalAIMI2**

Injection du Context + composition avec Trajectory.

### **Action #3 : Build & Test**

Compilation + validation des 6 scénarios.

---

**Veux-tu que je continue avec l'implémentation complète des fichiers manquants ?**

Ou préfères-tu d'abord un **diff/patch** montrant exactement où injecter dans DetermineBasalAIMI2.kt ?

---

**STATUS ACTUEL** :
- ✅ Architecture complète définie
- ✅ ContextIntent.kt créé (300 lignes)
- ⏳ 5 fichiers Context restants
- ⏳ Integration DetermineBasalAIMI2
- ⏳ UX Premium
- ⏳ Tests

---

**Je suis prêt à continuer. Dis-moi par quoi tu veux que je commence !** 🚀

