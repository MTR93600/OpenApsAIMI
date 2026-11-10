# AUDIT MODES REPAS — FIABILITÉ PREBOLUS

## 🔴 PROBLÈME IDENTIFIÉ

### **A) État Actuel du Tracking**

**Code actuel (lignes 5717-5821):**
```kotlin
// Phase 1: 0..7 min
if (activeRuntimeMin in 0..7 && !state.pre1) {
    if (pre1Config > 0) {
        actionBolus = pre1Config
        actionPhase = "Pre1"
        newState = state.copy(pre1 = true)
    }
}

// Phase 2: 15..23 min
if (activeRuntimeMin in 15..23 && !state.pre2 && pre2Config > 0) {
    actionBolus = pre2Config
    actionPhase = "Pre2"
    newState = state.copy(pre2 = true)
}
```

**Flags utilisés:**
- ✅ `state.pre1` (boolean) → persisté via `StringKey.OApsAIMIUnstableModeState`
- ✅ `state.pre2` (boolean) → persisté de la même façon
- ✅ `state.startMs` (timestamp) → permet de calculer runtime

**⚠️ PROBLÈME CRITIQUE:**
Les conditions `in 0..7` et `in 15..23` sont **BLOQUANTES**.
Si un tick arrive à `runtime = 9 min`:
- P1 n'est jamais envoyé (fenêtre ratée)
- État `pre1 = false` reste faux indéfiniment
- Utilisateur risque hyperglycémie

---

## 🔍 POURQUOI P1/P2 PEUVENT ÊTRE RATÉS

### **Scénario 1: Tick manqué dans fenêtre**
```
Mode Lunch activé à 12:00:00
Tick 1: 12:02:00 (runtime=2min) → trop tôt, attente
Tick 2: 12:05:00 (runtime=5min) → OK, P1 envoyé ✅
Tick 3: 12:13:00 (runtime=13min) → attente P2
Tick 4: 12:18:00 (runtime=18min) → P2 envoyé ✅
```
**✅ CAS NOMINAL**

### **Scénario 2: Tick retardé → P1 raté**
```
Mode Lunch activé à 12:00:00
Tick 1: 12:02:00 (runtime=2min) → trop tôt, attente
Tick 2: 12:09:00 (runtime=9min) → ❌ HORS FENÊTRE [0-7], P1 RATÉ
État: pre1=false persiste
Tick 3: 12:18:00 (runtime=18min) → P2 bloqué car "pre2 only if in 15..23"
```
**❌ P1 JAMAIS ENVOYÉ → Hyper risque**

### **Scénario 3: Système suspendu (BT lag, pump disconnect)**
```
Mode Lunch activé à 12:00:00
Système suspendu 12:00-12:20 (Bluetooth erreur)
Tick 1: 12:21:00 (runtime=21min) → ❌ TOUTES FENÊTRES RATÉES
État: pre1=false, pre2=false
```
**❌ AUCUN PREBOLUS → Hyper garantie**

---

## 📊 IMPACT SAFETY

**Sans catch-up:**
- Si P1 raté → pas de couverture insulinique précoce
- Si P2 raté → pas de renfort à mi-repas
- **Conséquence:** Hyperglycémie +100-150 mg/dL possible (enfant)

**Avec catch-up:**
- P1 envoyé dès que possible (ex: runtime=9min au lieu de 0-7)
- P2 envoyé dès que gap ≥ 15 min après P1
- **Conséquence:** Couverture partielle restaurée, hyper limitée

---

## ✅ SOLUTION: SYSTÈME DE CATCH-UP

### **Principe**

1. **P1 Catch-Up:**
   - Si `!state.pre1` ET `pre1Config > 0` → envoyer P1 **immédiatement**
   - Peu importe le runtime (sauf si > 30 min = mode terminé)
   
2. **P2 Catch-Up:**
   - Si `state.pre1` ET `!state.pre2` ET `pre2Config > 0`
   - ET `elapsedSinceP1 ≥ MIN_GAP` (15 min)
   - → envoyer P2 **immédiatement**

3. **TBR Mode:**
   - Appliquée dès activation OU dès P1
   - Durée: 30 min à partir de l'application

4. **Safety HARD respectée:**
   - Si LGS/hypo → aucun bolus, log explicite
   - Cooldown anti-double-bolus: 10-15 min minimum

---

## 🛠️ NOUVELLE STATE MACHINE

```
État: { name, startMs, pre1, pre2, pre1SentMs?, pre2SentMs? }

Transitions:
1. Mode Start → pre1=false, pre2=false
2. P1 Decision:
   - Condition: !pre1 && pre1Config > 0 && safetyOK
   - Action: send P1, pre1=true, pre1SentMs=now
3. P2 Decision:
   - Condition: pre1 && !pre2 && pre2Config > 0 
                && (now - pre1SentMs) >= MIN_GAP && safetyOK
   - Action: send P2, pre2=true, pre2SentMs=now
4. Mode End (runtime > 30min):
   - Reset state (éviter réutilisation)
```

**Avantages:**
- ✅ P1/P2 toujours envoyés si configurés
- ✅ Délai minimal P1 → P2 respecté (safety)
- ✅ Pas de double bolus (timestamps)
- ✅ Logs clairs pour debug

---

## 📝 LOGS REQUIS

```kotlin
// Mode actif
"MODE name=Lunch rt=12m pre1=sent(12:05) pre2=pending gap=7m"

// Catch-up P1
"MODE_CATCHUP_P1 mode=Lunch rt=9m reason=missedWindow send=2.0U"

// Catch-up P2
"MODE_CATCHUP_P2 mode=Lunch rt=25m gapSinceP1=16m send=1.5U"

// Block safety
"MODE_BLOCK mode=Lunch reason=LGS minBG=58 th=65"

// Block cooldown
"MODE_BLOCK mode=Lunch reason=Cooldown sinceLastBolus=3m"

// Fallthrough
"MODE_FALLTHROUGH mode=Lunch reason=AllSent pre1=✅ pre2=✅"
```

---

## 🧪 TESTS SIMULATION

### **Test 1: Fenêtre P1 ratée (runtime=9min)**
```
Input:
- Mode: Lunch activé
- Runtime: 9 min
- État: pre1=false, pre2=false
- P1 Config: 2.0U
- Safety: OK

Expected Output:
- ✅ P1 envoyé (catch-up)
- Log: "MODE_CATCHUP_P1 mode=Lunch rt=9m send=2.0U"
- État updated: pre1=true, pre1SentMs=now
- TBR: TBRmaxMode pendant 30 min
```

### **Test 2: Fenêtre P2 ratée + gap insuffisant**
```
Input:
- Mode: Lunch actif
- Runtime: 25 min
- État: pre1=true (sent at t+9min), pre2=false
- Gap depuis P1: 16 min (25 - 9)
- P2 Config: 1.5U
- Safety: OK

Expected Output:
- ✅ P2 envoyé (catch-up)
- Log: "MODE_CATCHUP_P2 mode=Lunch rt=25m gapSinceP1=16m send=1.5U"
- État: pre2=true, pre2SentMs=now
```

### **Test 3: Safety LGS bloque P1**
```
Input:
- Mode: Lunch actif
- Runtime: 5 min (fenêtre P1 OK)
- État: pre1=false
- P1 Config: 2.0U
- Safety: BLOCKED (minBG=58 < 65)

Expected Output:
- ❌ P1 NOT sent
- Log: "MODE_BLOCK mode=Lunch reason=LGS minBG=58 th=65"
- État: pre1 reste false (retry au tick suivant)
- TBR: 0.0 (LGS)
```

### **Test 4: Autodrive juste avant → cooldown**
```
Input:
- Mode: Lunch actif
- Runtime: 6 min
- État: pre1=false
- Last Bolus: 2 min ago (Autodrive)
- P1 Config: 2.0U
- Safety: OK mais cooldown=10min

Expected Output:
- ❌ P1 NOT sent (cooldown)
- Log: "MODE_BLOCK mode=Lunch reason=Cooldown sinceLastBolus=2m"
- État: pre1 reste false
- Retry au prochain tick (runtime=7, 8, 9...)
```

### **Test 5: Tout envoyé → Fallthrough vers ML**
```
Input:
- Mode: Lunch actif
- Runtime: 28 min
- État: pre1=true (sent), pre2=true (sent)
- Safety: OK

Expected Output:
- Fallthrough vers logique ML normale
- Log: "MODE_FALLTHROUGH mode=Lunch pre1=✅ pre2=✅ → ML"
- Mode continue d'appliquer reactivity/SMB interval jusqu'à fin
```

---

## 🔧 INTERACTIONS AVEC AUTODRIVE/MEALADVISOR

### **Règle 1: Mode prioritaire sur Autodrive**
```kotlin
// Dans determine_basal
val manualRes = tryManualModes(...)
if (manualRes is Applied) {
    // Mode a envoyé un bolus → apply ET skip Autodrive
    return manualRes
}

val autoRes = tryAutodrive(...)
if (autoRes is Applied) {
    // Autodrive a envoyé → update lastBolusTime pour cooldown Mode
    return autoRes
}
```

### **Règle 2: MealAdvisor marque état**
```kotlin
// Si MealAdvisor envoie un bolus pour ce repas
val advisorRes = tryMealAdvisor(...)
if (advisorRes is Applied && advisorRes.isForCurrentMode) {
    // Marquer pre1=true OU pre2=true selon contexte
    // Éviter double bolus
}
```

### **Règle 3: Autodrive ne capture pas tick si no-op**
```kotlin
if (autoRes is Fallthrough) {
    // Autodrive n'a rien appliqué → continuer pipeline
    // Mode peut encore agir si besoin
}
```

---

## ✅ PROCHAINES ÉTAPES

1. **Implémenter** nouvelle logique tryManualModes avec:
   - Ajout timestamps `pre1SentMs`, `pre2SentMs` dans ModeState
   - Logique catch-up pour P1 et P2
   - Logs détaillés
   - Safety checks (LGS, cooldown)

2. **Compiler** et vérifier `BUILD SUCCESSFUL`

3. **Tester** les 5 scénarios sur device

4. **Monitorer** logs pour validation

**🎯 Objectif:** Zéro prebolus raté → zéro hyper évitable.
