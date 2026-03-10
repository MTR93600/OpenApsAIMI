# DIAGNOSTIC MODES MEAL — ÉTAT ACTUEL vs REQUIS

## 🔴 PROBLÈMES IDENTIFIÉS (FAIL/PASS)

### **FAIL #1: Blocage Silencieux LGS (Ligne 5836-5845)**

**Code Actuel:**
```kotlin
if (minBg < lgsThreshold) {
    consoleLog.add("MODE_BLOCK mode=$activeName reason=LGS...")
    return DecisionResult.Applied(
        bolusU = 0.0,
        tbrUph = 0.0,
        ...
    )
}
```

**Problème:**
- ❌ Retourne `bolusU=0.0` sans exécuter de **degraded action**
- ❌ Log "MODE_BLOCK" n'indique pas une dégradation explicite
- ❌ Pas de niveau de dégradation (LEVEL 3 attendu)
- ❌ Pas de banner UI

**Attendu:**
- ✅ DEGRADE LEVEL 3: `bolusU=0.0` EXPLICITE avec raison
- ✅ Log `MODE_DEGRADED level=3 reason=LGS`
- ✅ Banner UI "Meal mode degraded: LGS"
- ✅ State preserved (p1SentAt = now pour éviter retry infini)

---

### **FAIL #2: Blocage Cooldown (Ligne 5851-5853)**

**Code Actuel:**
```kotlin
if (sinceLast < MIN_COOLDOWN_MIN) {
    consoleLog.add("MODE_BLOCK mode=$activeName reason=Cooldown...")
    return DecisionResult.Fallthrough("Cooldown active...")
}
```

**Problème:**
- ❌ `Fallthrough` = pas d'action = **blocage silencieux**
- ❌ P1 ne sera jamais envoyé si cooldown persiste
- ❌ TBR mode non appliquée

**Attendu:**
- ✅ DEGRADE LEVEL 1: P1 cap 50-70%, TBR conservée
- ✅ OU: si cooldown > 10 min, forcer P1 (modes prioritaires)
- ✅ Log + raison explicite

---

### **FAIL #3: Pas de State Machine Robuste**

**Code Actuel:**
```kotlin
private data class ModeState(
    var name: String = "",
    var startMs: Long = 0L,
    var pre1: Boolean = false,
    var pre2: Boolean = false,
    var pre1SentMs: Long = 0L,
    var pre2SentMs: Long = 0L
)
```

**Problème:**
- ❌ Pas de `tbrStartAt`, `tbrEndAt`
- ❌ Pas de `degradeLevelLast`
- ❌ Pas de tracking "TBR appliquée?"

**Attendu:**
```kotlin
data class ModeMealState(
    val type: String,
    val startedAt: Long,
    var p1SentAt: Long? = null,
    var p2SentAt: Long? = null,
    var tbrStartAt: Long? = null,
    var tbrEndAt: Long? = null,
    var degradeLevelLast: Int = 0
)
```

---

### **FAIL #4: TBR Mode Pas Garantie**

**Code Actuel:**
```kotlin
if (actionBolus > 0.0) {
    val modeTbr = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal
    return DecisionResult.Applied(
        tbrUph = modeTbr,
        tbrMin = 30,
        ...
    )
}
```

**Problème:**
- ❌ TBR appliquée UNIQUEMENT si `actionBolus > 0`
- ❌ Si P1 dégradé à 0U → pas de TBR
- ❌ Si fallthrough → pas de TBR

**Attendu:**
- ✅ `ensureModeTbr()` appelé AVANT décision P1/P2
- ✅ TBR appliquée même si bolus = 0
- ✅ TBR maintenue 30 min min

---

### **FAIL #5: Autodrive Peut Capturer**

**Code Actuel (determine_basal):**
```kotlin
val manualRes = tryManualModes(...)
if (manualRes is Applied) { return manualRes }

val autoRes = tryAutodrive(...)
if (autoRes is Applied) { return autoRes }
```

**Problème:**
- ⚠️ Si `tryManualModes` retourne `Fallthrough` (cooldown par ex)
- ⚠️ Autodrive s'engage → **bypass mode meal**

**Attendu:**
- ✅ Si mode actif → **toujours** retourner `Applied` (même dégradé)
- ✅ Autodrive skip automatique si mode actif

---

## ✅ CHECKLIST CONFORMITÉ

| Item | Exigence | Status Actuel | Action Requise |
|------|----------|---------------|----------------|
| **1** | Pas de `return Fallthrough` pendant mode actif | ❌ **FAIL** | Implémenter degrade |
| **2** | Pas de `bolusU=0` sans dégradation explicite | ❌ **FAIL** | Ajouter levels |
| **3** | TBR mode toujours appliquée | ❌ **FAIL** | `ensureModeTbr()` |
| **4** | State machine robuste | ⚠️ **PARTIAL** | Ajouter champs |
| **5** | Autodrive ne bypass pas | ⚠️ **PARTIAL** | Forcer Applied |
| **6** | Logs dégradation | ❌ **FAIL** | Implémenter |
| **7** | UI banner si degrade>=2 | ❌ **FAIL** | Implémenter |
| **8** | Anti-double via state, pas refractory global | ✅ **PASS** | OK (timestamps) |

**Score:** 1/8 PASS, 2/8 PARTIAL, 5/8 FAIL

---

## 📊 PATCH REQUIS — ARCHITECTURE

### **1. Degrade Levels (Nouveau)**

```kotlin
enum class ModeDegradeLevel(val value: Int, val label: String) {
    NORMAL(0, "Normal"),
    CAUTION(1, "Caution"),
    HIGH_RISK(2, "High Risk"),
    CRITICAL(3, "Critical")
}

data class DegradePlan(
    val level: ModeDegradeLevel,
    val reason: String,
    val bolus Factor: Double,  // 0.0-1.0
    val tbrFactor: Double,     // 0.0-1.0
    val banner: String?
)
```

### **2. Safety Degrade Function**

```kotlin
private fun modeSafetyDegrade(
    bg: Double,
    delta: Float,
    minBg: Double,
    lgsThreshold: Double,
    iobActivityNow: Double,
    glucoseStatus: GlucoseStatus?
): DegradePlan {
    
    // LEVEL 3: CRITICAL
    if (bg < 25 || bg > 600 || bg.isNaN() || bg.isInfinite()) {
        return DegradePlan(
            level = CRITICAL,
            reason = "Data incoherent",
            bolusFactor = 0.0,
            tbrFactor = 0.0,
            banner = "⚠️ Data issue: meal mode suspended"
        )
    }
    
    if (glucoseStatus == null || isStale > 12) {
        return DegradePlan(
            level = CRITICAL,
            reason = "CGM stale",
            bolusFactor = 0.0,
            tbrFactor = 0.0,
            banner = "⚠️ CGM stale: meal mode suspended"
        )
    }
    
    if (bg < 70 && delta < -2) {
        return DegradePlan(
            level = CRITICAL,
            reason = "Hypo critical (bg<70, delta<-2)",
            bolusFactor = 0.0,
            tbrFactor = 0.0,
            banner = "⚠️ Hypo risk: meal mode suspended"
        )
    }
    
    if (minBg < 55) {
        return DegradePlan(
            level = CRITICAL,
            reason = "LGS trigger (minBg<55)",
            bolusFactor = 0.0,
            tbrFactor = 0.0,
            banner = "⚠️ LGS: meal mode suspended"
        )
    }
    
    // LEVEL 2: HIGH_RISK
    if (minBg < lgsThreshold) {
        return DegradePlan(
            level = HIGH_RISK,
            reason = "LGS threshold (minBg<$lgsThreshold)",
            bolusFactor = 0.0,
            tbrFactor = 0.5,
            banner = "⚠️ Low BG: meal mode reduced"
        )
    }
    
    if (iobActivityNow > 0.8 && delta < -1.0) {
        return DegradePlan(
            level = HIGH_RISK,
            reason = "High IOB activity + falling",
            bolusFactor = 0.1, // Micro-dose 10%
            tbrFactor = 0.5,
            banner = "⚠️ High insulin: meal mode reduced"
        )
    }
    
    // LEVEL 1: CAUTION
    if (bg < 80) {
        return DegradePlan(
            level = CAUTION,
            reason = "BG < 80",
            bolusFactor = 0.5,
            tbrFactor = 0.7,
            banner = null // No banner for level 1
        )
    }
    
    // LEVEL 0: NORMAL
    return DegradePlan(
        level = NORMAL,
        reason = "Normal operation",
        bolusFactor = 1.0,
        tbrFactor = 1.0,
        banner = null
    )
}
```

### **3. Nouvelle tryManualModes (Robuste)**

```kotlin
private fun tryManualModes(...): DecisionResult {
    if (bg < 60) {
        // Même à BG très bas, retourner Applied (dégradé)
        val degradePlan = modeSafetyDegrade(...)
        return DecisionResult.Applied(
            source = "ModeDegrade",
            bolusU = 0.0,
            tbrUph = 0.0,
            tbrMin = 30,
            reason = "MODE_DEGRADED level=${degradePlan.level.value} reason=${degradePlan.reason}"
        )
    }
    
    // 1. Detect Active Mode
    val (activeName, runtime, p1Config, p2Config) = detectActiveMode()
    
    if (activeName.isEmpty()) {
        return DecisionResult.Fallthrough("No Active Mode")
    }
    
    // 2. Load State
    var state = ModeMealState.load(preferences)
    if (state.type != activeName || timeDiff > 5min) {
        state = ModeMealState(type = activeName, startedAt = now - runtime)
        state.save(preferences)
    }
    
    // 3. Compute Degrade Plan
    val degradePlan = modeSafetyDegrade(...)
    
    // 4. Ensure TBR FIRST (même si bolus=0)
    ensureModeTbr(state, degradePlan)
    
    // 5. P1 Decision
    if (state.p1SentAt == null && p1Config > 0.0) {
        val degradedBolus = p1Config * degradePlan.bolusFactor
        executeP1(state, degradedBolus, degradePlan)
        state.p1SentAt = now
        state.degradeLevelLast = degradePlan.level.value
        state.save()
        
        return DecisionResult.Applied(
            source = "ModeP1",
            bolusU = degradedBolus,
            tbrUph = getTbrRate(degradePlan),
            tbrMin = 30,
            reason = buildReason(degradePlan, "P1", degradedBolus)
        )
    }
    
    // 6. P2 Decision (avec gap check)
    if (state.p1SentAt != null && state.p2SentAt == null && p2Config > 0.0) {
        val gapMin = (now - state.p1SentAt!!) / 60000.0
        if (gapMin >= 15.0) {
            val degradedBolus = p2Config * degradePlan.bolusFactor
            executeP2(state, degradedBolus, degradePlan)
            state.p2SentAt = now
            state.degradeLevelLast = degradePlan.level.value
            state.save()
            
            return DecisionResult.Applied(
                source = "ModeP2",
                bolusU = degradedBolus,
                tbrUph = getTbrRate(degradePlan),
                tbrMin = 30,
                reason = buildReason(degradePlan, "P2", degradedBolus)
            )
        }
    }
    
    // 7. Handoff to ML (après P1/P2 ou en attente)
    val allSent = (p1Config == 0.0 || state.p1SentAt != null) &&
                  (p2Config == 0.0 || state.p2SentAt != null)
    
    if (allSent && runtime > 23) {
        consoleLog.add("MODE_HANDOFF_TO_ML mode=$activeName")
        return DecisionResult.Fallthrough("Handoff to ML after P1/P2")
    }
    
    // 8. Waiting state (TBR active, pas de bolus ce tick)
    return DecisionResult.Applied(
        source = "ModeWait",
        bolusU = 0.0,
        tbrUph = getTbrRate(degradePlan),
        tbrMin = 30,
        reason = "MODE_WAIT mode=$activeName runtime=${runtime}m"
    )
}
```

### **4. Helper: ensureModeTbr**

```kotlin
private fun ensureModeTbr(state: ModeMealState, degradePlan: DegradePlan) {
    val now = System.currentTimeMillis()
    
    // Si TBR déjà active et pas expirée → skip
    if (state.tbrStartAt != null && state.tbrEndAt != null) {
        if (now < state.tbrEndAt!!) {
            consoleLog.add("MODE_TBR_ACTIVE remaining=${((state.tbrEndAt!! - now) / 60000).toInt()}m")
            return
        }
    }
    
    // Appliquer TBR mode
    val tbrRate = calculateModeTbr() * degradePlan.tbrFactor
    state.tbrStartAt = now
    state.tbrEndAt = now + (30 * 60000) // 30 min
    
    consoleLog.add("MODE_TBR_SET rate=${\"%.2f\".format(tbrRate)} dur=30m degrade=${degradePlan.level.label}")
}
```

---

## 📝 LOGS ATTENDUS

### **Scénario Normal (LEVEL 0)**
```
MODE_ACTIVE type=Lunch runtimeMin=3
MODE_DEGRADE level=0 reason=Normal operation
MODE_TBR_SET rate=4.00 dur=30m degrade=Normal
MODE_P1_SENT u=2.00 degrade=Normal
```

### **Scénario Caution BG=75 (LEVEL 1)**
```
MODE_ACTIVE type=Lunch runtimeMin=5
MODE_DEGRADE level=1 reason=BG < 80
MODE_TBR_SET rate=2.80 dur=30m degrade=Caution   # 4.0 × 0.7
MODE_P1_SENT u=1.00 degrade=Caution                # 2.0 × 0.5
```

### **Scénario LGS (LEVEL 2)**
```
MODE_ACTIVE type=Lunch runtimeMin=6
MODE_DEGRADE level=2 reason=LGS threshold (minBg<65)
MODE_TBR_SET rate=2.00 dur=30m degrade=High Risk   # 4.0 × 0.5
MODE_P1_SENT u=0.00 degrade=High Risk               # 2.0 × 0.0
UI_BANNER ⚠️ Low BG: meal mode reduced
```

### **Scénario CGM Stale (LEVEL 3)**
```
MODE_ACTIVE type=Lunch runtimeMin=8
MODE_DEGRADE level=3 reason=CGM stale
MODE_TBR_SET rate=0.00 dur=30m degrade=Critical    # 4.0 × 0.0
MODE_P1_SENT u=0.00 degrade=Critical                # 2.0 × 0.0
UI_BANNER ⚠️ CGM stale: meal mode suspended
```

---

## 🧪 TESTS SIMULATION

### **Test 1: Normal (40 min)**
```
T+1 : P1 sent 2.0U, TBR 4.0
T+18: P2 sent 1.5U, TBR active
T+25: Handoff ML
```

### **Test 2: Autodrive Ignore**
```
T+1 : Mode Lunch active
      Autodrive condition OK → SKIPPED (mode priority)
T+5 : P1 sent
```

### **Test 3: CGM Stale**
```
T+1 : CGM stale detected
      DEGRADE LEVEL 3
      P1 0.0U EXPLICIT
      TBR 0.0
      Banner displayed
T+6 : CGM recovered
      P1 already marked sent → skip retry loop
      Wait P2
```

### **Test 4: BG 85, Delta -3**
```
T+1 : BG 85, delta -3
      DEGRADE LEVEL 2 ou 3 (selon iobActivity)
      P1 micro-dose 0.2U (2.0 × 0.1)
      TBR 2.0 (4.0 × 0.5)
      Banner "High insulin: meal mode reduced"
```

---

## ✅ IMPLÉMENTATION REQUEST

**Fichiers à modifier:**
1. `DetermineBasalAIMI2.kt` (tryManualModes refactor)
2. Ajouter `ModeDegradeLevel.kt` (enum)
3. Ajouter `ModeMealState.kt` (data class robuste)

**Garanties:**
- ✅ Aucun `return Fallthrough` pendant mode actif
- ✅ Toujours `return Applied` (même dégradé)
- ✅ TBR toujours appliquée
- ✅ Logs + UI banner
- ✅ State persistent

**Prochaine étape:** Implémenter patch complet.
