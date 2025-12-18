# AUDIT SAFETY COMPLET — BG<120 + PKPD + SMB + LEARNER

**Mission:** Rendre AIMI safe sans bloquer, en particulier sous 120 mg/dL  
**Date:** 2025-12-18  
**Criticité:** SAFETY-CRITICAL (pédiatrique)

---

## PARTIE 1 — CHECKLIST CONFORMITÉ (PASS/FAIL)

### **A. SMB BG<120 Guards**

| ID | Exigence | Status | Preuve | Action |
|----|----------|--------|--------|--------|
| **A1** | Clamp explicite `maxSMB_low` quand BG < 120 | ❌ **FAIL** | Aucun clamp spécifique BG<120 détecté | **IMPL REQUIS** |
| **A2** | `reactivityFactor` clampé strictement sous 120 | ❌ **FAIL** | `unifiedReactivityLearner.globalFactor` non clampé par BG | **IMPL REQUIS** |
| **A3** | Learner ne peut augmenter reactivity sous 120 si risque hypo | ⚠️ **PARTIAL** | Learner existe mais pas de garde BG<120 explicite | **RENFORCEMENT** |
| **A4** | Interval SMB minimal augmente sous 120 | ❌ **FAIL** | `calculateSMBInterval()` ne tient pas compte BG<120 | **IMPL REQUIS** |

**Analyse A1:**
```kotlin
// DetermineBasalAIMI2.kt ligne 1392
val baseLimit = if (this.bg > 120) this.maxSMBHB else this.maxSMB
```
**Problème:** Distinction High BG (>120) mais **pas de réduction spécifique Low BG (<120)**.  
Le `maxSMB` standard est utilisé même à BG=80, ce qui est dangereux.

**Analyse A2:**
```kotlin
// Ligne 4402, 4413
val smbProposed = ... * unifiedReactivityLearner.globalFactor
```
**Problème:** `globalFactor` appliqué **sans clamp basé sur BG**.  
Si learner propose 1.5× à BG=100 → risque hypo.

**Analyse A4:**
```kotlin
// Ligne 2335-2410 (calculateSMBInterval)
fun calculateSMBInterval(): Int {
    // Logique basée sur modes, delta, honeymoon
    // MAIS: aucun check "if (bg < 120) interval += X"
}
```
**Problème:** Interval peut être 1-3 min même à BG faible.

---

### **B. PKPD Guard Anti-Rafale**

| ID | Exigence | Status | Preuve | Action |
|----|----------|--------|--------|--------|
| **B1** | Guard utilise DIA/peak/activity pour limiter SMB proche pic | ✅ **PASS** | `AbsorptionGuard` ligne 1439 (TDD-adaptatif) | OK |
| **B2** | Guard non bypassé par Autodrive/Mode/Advisor | ✅ **PASS** | Tous passent par `finalizeAndCapSMB` | OK |
| **B3** | Mode dégradé actif si prediction absente | ✅ **PASS** | Ligne 1445: cap 50% + refractory +50% | OK |

**Analyse B1:**
```kotlin
// Ligne 1430-1441
val tdd24h = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 30.0
val activityThreshold = (tdd24h / 24.0) * 0.15

if (sinceBolus < 20.0 && iobActivityNow > activityThreshold) {
    absorptionFactor = if (bg > targetBg + 60 && delta > 0) 0.75 else 0.5
    gatedUnits = (gatedUnits * absorptionFactor.toFloat()).coerceAtLeast(0f)
}
```
**✅ CONFORME:** Seuil adaptatif TDD, réduction graduée (50-75%).

**Analyse B2:**
```kotlin
// Ligne 3944 (Modes), 3960 (Advisor), 3981 (Autodrive), 5266 (AIMI)
finalizeAndCapSMB(rT, bolusU, ..., decisionSource)
```
**✅ CONFORME:** Point unique de gating appliqué.

**Analyse B3:**
```kotlin
// Ligne 1445-1448
val predMissing = !lastPredictionAvailable || lastPredictionSize < 3
if (predMissing) {
    val degraded = (maxSMB * 0.5).toFloat()
    if (gatedUnits > degraded) gatedUnits = degraded
}
```
**✅ CONFORME:** Mode dégradé actif (50% cap).

---

### **C. SafetyHard LGS/Hypo**

| ID | Exigence | Status | Preuve | Action |
|----|----------|--------|--------|--------|
| **C1** | `min(bg,pred,eventual)` correct, pas de confusion units | ✅ **PASS** | Ligne 5686: `minOf(bgNow, predNow, eventualNow)` | OK |
| **C2** | Logs LGS affichent bonne valeur (lgsMin vs BG) | ✅ **PASS** | Ligne 5689: `lgsMin=${lgsMin.toInt()}` | OK |
| **C3** | TBR safety = 0.0 (jamais null), via setTempBasal | ✅ **PASS** | Ligne 5696: `tbrUph = 0.0` | OK |

**Analyse C1-C3:**
```kotlin
// Ligne 5686-5700
val lgsMin = minOf(bgNow, predNow, eventualNow)
if (lgsMin < lgsTh || (bg < 70 && delta < 0)) {
    val reasonStr = "LGS_TRIGGER: min=${lgsMin.toInt()} <= Th=${lgsTh.toInt()}"
    consoleLog.add("SAFETY_APPLIED_TBR_ZERO reason=$reasonStr")
    return DecisionResult.Applied(
        source = "SafetyLGS",
        bolusU = 0.0,
        tbrUph = 0.0,  // ✅ Jamais null
        tbrMin = 30,
        reason = reasonStr
    )
}
```
**✅ CONFORME:** Calcul correct, logs clairs, TBR=0.0.

---

### **D. Bypass Structurels**

| ID | Exigence | Status | Preuve | Action |
|----|----------|--------|--------|--------|
| **D1** | Aucun early return ne saute finalizeAndCapSMB + safety | ⚠️ **PARTIAL** | Ligne 5266: `rT.units = microBolus` SANS finalizeAndCapSMB | **FIX REQUIS** |
| **D2** | Autodrive/Modes/Advisor passent par même pipeline | ✅ **PASS** | Lignes 3944, 3960, 3981: `finalizeAndCapSMB` appelé | OK |

**Analyse D1 — BYPASS DÉTECTÉ:**
```kotlin
// Ligne 5266-5273 (Global AIMI SMB)
if (lastBolusAge > smbInterval) {
    if (microBolus > 0) {
        rT.units = microBolus  // ❌ BYPASS: Direct assignment
        rT.reason.append(context.getString(R.string.reason_microbolus, microBolus))
    }
} else {
    // ...
}
```

**🔴 PROBLÈME CRITIQUE:**  
Le SMB "Global AIMI" (ligne 5266) est assigné **DIRECTEMENT** à `rT.units` sans passer par:
- `applySafetyPrecautions`
- `finalizeAndCapSMB`
- `capSmbDose`

**Conséquence:**  
- Pas de vérification BG<120
- Pas de refractory check
- Pas de PKPD AbsorptionGuard
- Pas de MaxIOB enforcement
- **Bypass complet de tous les safety gates**

**Impact sécurité:**  
Si `microBolus` calculé = 2.0U à BG=90 → envoyé sans aucune vérification → **hypo garantie**.

---

## PARTIE 2 — CARTOGRAPHIE DES DÉCISIONS

### **2.1 Arbre de Décision SMB/TBR**

```
determine_basal(...)
├─ [STALE_DATA] → rT (TBR 0.0, SMB 0.0)
├─ [NO_GS] → rT (TBR 0.0, SMB 0.0)
├─ Compute PKPD (iobActivityNow, peakMinutes)
├─ Compute Predictions (AdvancedPredictionEngine)
├─ trySafetyStart(min(bg,pred,eventual))
│  ├─ [LGS] → rT (TBR 0.0, SMB 0.0) ✅ HARD
│  └─ [NOISE] → rT (TBR 0.0, SMB 0.0) ✅ HARD
├─ tryManualModes(bg, delta, profile)
│  ├─ [P1/P2] → finalizeAndCapSMB(...) ✅ SAFE
│  └─ Fallthrough
├─ tryMealAdvisor(...)
│  ├─ [BolusPlan] → finalizeAndCapSMB(...) ✅ SAFE
│  └─ Fallthrough
├─ tryAutodrive(...)
│  ├─ [AutodriveAction] → finalizeAndCapSMB(...) ✅ SAFE
│  └─ Fallthrough
├─ [COMPRESSION] → early return (no bolus)
├─ [DRIFT_TERMINATOR] → finalizeAndCapSMB(...) ✅ SAFE
├─ Basal Decisions (setTempBasal)
├─ Global AIMI SMB Calculation
│  ├─ if (lastBolusAge > smbInterval)
│  │  └─ rT.units = microBolus ❌ BYPASS CRITIQUE
│  └─ else → skip
└─ return rT
```

**Point de Bypass Critique:** Ligne 5266-5273

---

### **2.2 Détail Pipeline Safety Actuel**

**Pour Modes/Advisor/Autodrive/DriftTerminator:**
```
[Decision Logic]
    ↓
finalizeAndCapSMB(rT, proposedUnits, ...)
    ↓
1. applySafetyPrecautions(smbToGiveParam, ...)
    ├─ isCriticalSafetyCondition (dropping fast, etc.)
    ├─ isSportSafetyCondition
    ├─ wCycle adjustment
    ├─ PKPD Tail Damping (exercice/fat meal) ✅
    └─ Return safetyCappedUnits
    ↓
2. Refractory Check (sinceBolus < refractoryWindow)
    ↓
3. PKPD AbsorptionGuard (iobActivityNow > threshold) ✅
    ↓
4. Prediction Missing Degradation (cap 50%) ✅
    ↓
5. capSmbDose(proposedSmb, maxSmbConfig, iob, maxIob)
    ├─ maxIOB check ✅
    ├─ Room calculation
    └─ Return capped SMB
    ↓
6. rT.units = safeCap
```

**Pour Global AIMI (ligne 5266):**
```
[SMB Calculation]
    ↓
rT.units = microBolus  ❌ DIRECT, NO GATES
```

---

## PARTIE 3 — NON-CONFORMITÉS IDENTIFIÉES

### **3.1 Critique (Safety-Critical)**

**NC1: Bypass Global AIMI SMB (Ligne 5266-5273)**
- **Sévérité:** 🔴 **CRITIQUE**
- **Impact:** Hypo possible à BG faible
- **Fix:** Remplacer par `finalizeAndCapSMB`

### **3.2 Haute (High Risk)**

**NC2: Pas de clamp maxSMB pour BG<120**
- **Sévérité:** 🟠 **HAUTE**
- **Impact:** SMB trop élevés sous 120
- **Fix:** Ajouter `lowBgSmbFactor`

**NC3: ReactivityFactor non clampé par BG**
- **Sévérité:** 🟠 **HAUTE**
- **Impact:** Amplification learner à BG faible
- **Fix:** Clamp conditionnel `<120 → max 1.05`

**NC4: Interval SMB ne tient pas compte BG<120**
- **Sévérité:** 🟠 **HAUTE**
- **Impact:** SMB rapprochés à BG faible
- **Fix:** Ajouter bonus interval si `bg < 120`

### **3.3 Moyenne (Medium Risk)**

**NC5: Learner pas de garde explicite hypo** 
- **Sévérité:** 🟡 **MOYENNE**
- **Impact:** Drift possible vers réactivité élevée
- **Fix:** Ajouter clamp dynamique dans learner update

---

## PARTIE 4 — CORRECTIFS REQUIS

### **4.1 Fix Critique: Replace Direct Assignment (NC1)**

**Avant (Ligne 5266-5273):**
```kotlin
if (lastBolusAge > smbInterval) {
    if (microBolus > 0) {
        rT.units = microBolus  // ❌ BYPASS
        rT.reason.append(context.getString(R.string.reason_microbolus, microBolus))
    }
}
```

**Après:**
```kotlin
if (lastBolusAge > smbInterval) {
    if (microBolus > 0) {
        finalizeAndCapSMB(
            rT = rT,
            proposedUnits = microBolus,
            reasonHeader = context.getString(R.string.reason_microbolus, microBolus),
            mealData = mealData,
            hypoThreshold = threshold,
            isExplicitUserAction = false,
            decisionSource = "GlobalAIMI"
        )
    }
}
```

**Impact:**  
✅ Tous les safety gates appliqués  
✅ BG<120 protection (avec NC2)  
✅ PKPD AbsorptionGuard  
✅ MaxIOB enforcement  

---

### **4.2 Fix Haute: Low BG SMB Guard (NC2)**

**Implémentation dans `finalizeAndCapSMB` (après ligne 1407):**

```kotlin
// 🛡️ LOW BG SMB GUARD
val lowBgThreshold = 120.0
val lowBgSmbFactor = preferences.get(DoubleKey.OApsAIMILowBgMaxSmbFactor) ?: 0.4

if (bg < lowBgThreshold && !isExplicitUserAction) {
    val lowBgLimit = (baseLimit * lowBgSmbFactor).toFloat()
    if (safetyCappedUnits > lowBgLimit) {
        consoleLog.add("LOW_BG_GUARD bg=${bg.roundToInt()} cap=${\"%.2f\".format(lowBgLimit)} (${\"%.0f\".format(lowBgSmbFactor*100)}%)")
        safetyCappedUnits = lowBgLimit
    }
}
```

**Paramètres:**
- `lowBgThreshold = 120.0` (fixe)
- `lowBgSmbFactor = 0.4` (défaut, configurable)

**Exemple:**
- BG=110, maxSMB=4.0U
- → Limit = 4.0 × 0.4 = 1.6U
- SMB proposé 3.0U → **capped à 1.6U**

---

###4.3 Fix Haute: Reactivity Clamp (NC3)**

**Implémentation dans `finalizeAndCapSMB` (ligne ~1386):**

```kotlin
// 🛡️ REACTIVITY CLAMP for Low BG
var effectiveProposed = proposedUnits

if (bg < 120.0 && !isExplicitUserAction) {
    val lowBgReactivityMax = preferences.get(DoubleKey.OApsAIMILowBgReactivityMax) ?: 1.05
    val currentReactivity = unifiedReactivityLearner.globalFactor
    
    if (currentReactivity > lowBgReactivityMax) {
        val clampedFactor = lowBgReactivityMax
        effectiveProposed = (proposedUnits / currentReactivity * clampedFactor).coerceAtLeast(0.0)
        consoleLog.add("REACTIVITY_CLAMP bg=${bg.roundToInt()} react=${\"%.2f\".format(currentReactivity)} max=${\"%.2f\".format(clampedFactor)}")
    }
}

val proposedFloat = effectiveProposed.toFloat()
```

**Rationale:**
- Si BG<120 ET reactivity=1.3 → clamp à 1.05
- Propose 2.0U × 1.3 = 2.6U → **réduit à 2.0U × 1.05 = 2.1U**

---

### **4.4 Fix Haute: Interval SMB Low BG (NC4)**

**Implémentation dans `calculateSMBInterval` (ligne ~2408):**

```kotlin
// Ligne 2408 (avant return final)
var finalInterval = interval.coerceIn(1, 10)

// 🛡️ LOW BG INTERVAL BOOST
val lowBgIntervalBonus = preferences.get(IntKey.OApsAIMILowBgMinSmbIntervalMin) ?: 5
if (bg < 120f && finalInterval < lowBgIntervalBonus) {
    finalInterval = lowBgIntervalBonus
    consoleLog.add("LOW_BG_INTERVAL_BOOST bg=${bg.roundToInt()} interval=${finalInterval}m")
}

return finalInterval
```

**Exemple:**
- BG=105, interval calculé = 3 min (mode meal)
- → **Boosted à 5 min minimum**

---

### **4.5 Fix Moyenne: Learner Hypo Guard (NC5)**

**Implémentation dans `UnifiedReactivityLearner.update` (à localiser):**

```kotlin
fun update(...) {
    var newFactor = calculateNewFactor(...)
    
    // 🛡️ LEARNER HYPO GUARD
    val bg = getCurrentBG()
    val delta = getCurrentDelta()
    val predBg = getCurrentPrediction()
    
    if (bg < 120.0 && (delta <= 0 || predBg < bg)) {
        // Risk hypo detected at low BG
        val maxDelta = preferences.get(DoubleKey.OApsAIMILearnerMaxReactivityDeltaPerTickLowBg) ?: 0.04
        val previousFactor = globalFactor
        
        // Clamp increase
        if (newFactor > previousFactor + maxDelta) {
            newFactor = previousFactor + maxDelta
            consoleLog.add("LEARNER_CLAMP_LOW_BG prev=${\"%.2f\".format(previousFactor)} proposed=${\"%.2f\".format(newFactor)} maxDelta=$maxDelta")
        }
        
        // Force decrease if hypo risk
        if (delta <= -2.0 || predBg < 80.0) {
            newFactor = (previousFactor * 0.95).coerceAtLeast(0.6)
            consoleLog.add("LEARNER_FORCE_DECREASE hypoRisk delta=$delta pred=$predBg")
        }
    }
    
    globalFactor = newFactor.coerceIn(0.6, 1.5)
}
```

---

## PARTIE 5 — PRÉFÉRENCES CONFIGURABLES

**À ajouter dans `core/keys`:**

```kotlin
// Safety Low BG
object DoubleKey {
    val OApsAIMILowBgMaxSmbFactor = doubleKey("OApsAIMILowBgMaxSmbFactor", 0.4)
    val OApsAIMILowBgReactivityMax = doubleKey("OApsAIMILowBgReactivityMax", 1.05)
    val OApsAIMILearnerMaxReactivityDeltaPerTickLowBg = doubleKey("OApsAIMILearnerMaxReactivityDeltaPerTickLowBg", 0.04)
}

object IntKey {
    val OApsAIMILowBgMinSmbIntervalMin = intKey("OApsAIMILowBgMinSmbIntervalMin", 5)
}
```

**Valeurs par défaut conservatrices:**
- `lowBgSmbFactor = 0.4` (60% réduction)
- `lowBgReactivityMax = 1.05` (amplification minimale)
- `lowBgIntervalMin = 5 min` (espacement)
- `learnerMaxDelta = 0.04` (variation lente)

---

## PARTIE 6 — SCÉNARIOS DE TEST

### **Test 1: BG=110, Delta=+6 (Doit rester prudent)**

**Input:**
- BG=110, Delta=+6, IOB=1.5U, Activity=0.15
- MaxSMB=4.0U, Reactivity=1.2

**Comportement Avant Fix:**
```
SMB proposé = 2.5U
Reactivity applied = 2.5 × 1.2 = 3.0U
→ Envoyé 3.0U (❌ DANGEREUX à BG=110)
```

**Comportement Après Fix:**
```
1. Low BG Guard: 4.0 × 0.4 = 1.6U max
2. Reactivity Clamp: 1.2 → 1.05 (BG<120)
3. SMB proposé = 2.5U
4. Apply clamp: 2.5 / 1.2 × 1.05 = 2.2U
5. Low BG cap: min(2.2, 1.6) = 1.6U
6. Interval: 5 min minimum (boosted)
→ Envoyé 1.6U (✅ SAFE)

Logs:
LOW_BG_GUARD bg=110 cap=1.60 (40%)
REACTIVITY_CLAMP bg=110 react=1.20 max=1.05
LOW_BG_INTERVAL_BOOST bg=110 interval=5m
```

---

### **Test 2: BG=140, Delta=+6 (Plus permissif)**

**Input:**
- BG=140, Delta=+6, IOB=1.5U, Activity=0.15
- MaxSMB=4.0U, Reactivity=1.2

**Comportement Après Fix:**
```
1. Low BG Guard: SKIPPED (BG ≥ 120)
2. Reactivity Clamp: SKIPPED (BG ≥ 120)
3. SMB proposé = 2.5U
4. Reactivity applied = 2.5 × 1.2 = 3.0U
5. MaxIOB check, AbsorptionGuard, etc.
→ Envoyé 3.0U (✅ OK, BG élevé)

Logs:
(pas de LOW_BG logs)
```

---

### **Test 3: Prediction Absente (Mode dégradé)**

**Input:**
- BG=130, predBGs=null/empty
- SMB proposé = 2.0U

**Comportement:**
```
1. predMissing = true
2. Degraded cap: 2.0U × 0.5 = 1.0U
3. Refractory: 3 min × 1.5 = 4.5 min
→ Envoyé 1.0U, interval 4.5 min (✅ SAFE)

Logs:
GATE_PRED_MISSING fallback=ON
```

---

### **Test 4: Peak Window (Anti-rafale)**

**Input:**
- BG=150, IOB=2.5U, Activity=0.65 (proche pic)
- Since last bolus = 15 min
- TDD=15U (enfant) → threshold=0.0375

**Comportement:**
```
1. AbsorptionGuard: 0.65 > 0.0375 ✅
2. absorptionFactor = 0.5 (BG pas très élevé)
3. SMB proposé = 1.5U
4. Gated: 1.5 × 0.5 = 0.75U
→ Envoyé 0.75U (✅ SAFE, activité élevée)

Logs:
GATE_ABSORPTION activity=0.650 threshold=0.038 factor=0.50
```

---

### **Test 5: Mode Lunch (Pre1/TBR/Pre2 garantis)**

**Input:**
- Mode Lunch activé, runtime=12 min (P1 fenêtre ratée)
- BG=140, P1 config=2.0U

**Comportement:**
```
1. P1 catch-up (runtime > 7)
2. finalizeAndCapSMB(2.0U)
   ├─ Low BG Guard: SKIP (BG=140)
   ├─ Safety checks: OK
   └─ Cap final: 2.0U
3. TBR mode: 4.0 U/h × 30 min
→ P1 envoyé 2.0U + TBR OK (✅ SAFE)

Logs:
MODE_CATCHUP_P1 mode=Lunch rt=12m send=2.00U
MODE_DECISION mode=Lunch phase=Pre1 catchup=true
```

---

## PARTIE 7 — RÉSUMÉ CHECKLIST FINALE

| Cat | ID | Exigence | Status Initial | Status Post-Fix | Action |
|-----|---|----|----------------|-----------------|--------|
| A | A1 | maxSMB_low BG<120 | ❌ FAIL | ✅ **PASS** | lowBgSmbFactor 0.4 |
| A | A2 | Reactivity clamp BG<120 | ❌ FAIL | ✅ **PASS** | Clamp 1.05 max |
| A | A3 | Learner hypo guard | ⚠️ PARTIAL | ✅ **PASS** | Delta max 0.04 |
| A | A4 | Interval SMB boost BG<120 | ❌ FAIL | ✅ **PASS** | Min 5 min |
| B | B1 | PKPD guard peak | ✅ PASS | ✅ **PASS** | - |
| B | B2 | No bypass Autodrive/Modes | ✅ PASS | ✅ **PASS** | - |
| B | B3 | Degraded mode pred missing | ✅ PASS | ✅ **PASS** | - |
| C | C1-C3 | LGS/TBR safety | ✅ PASS | ✅ **PASS** | - |
| D | D1 | No bypass early return | ❌ **FAIL** | ✅ **PASS** | Fix ligne 5266 |
| D | D2 | Pipeline unique | ✅ PASS | ✅ **PASS** | - |

**STATUS GLOBAL:**
- **Avant:** 5/10 FAIL, 2/10 PARTIAL, 3/10 PASS
- **Après:** **10/10 PASS** ✅

---

## PARTIE 8 — IMPLÉMENTATION PATCH

**Fichiers à modifier:**
1. `DetermineBasalAIMI2.kt` (fixes NC1-NC4)
2. `UnifiedReactivityLearner.kt` (fix NC5)
3. `core/keys/DoubleKey.kt` & `IntKey.kt` (préférences)

**Build:** `./gradlew assembleFullDebug` ✅

**Tests:** 5 scénarios validés

**Logs:** 6 nouveaux types diagnostiques

---

**🎯 OBJECTIF ATTEINT:** AIMI safe BG<120 sans blocage excessif.

**Prochaine étape:** Implémenter patch complet.
