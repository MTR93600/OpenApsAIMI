# DIAGNOSTIC BLOCAGE PREBOLUS MODES REPAS

**Date:** 2025-12-18 20:45  
**Problème:** P1 et P2 ne partent pas pour lunch, dinner, bfast, highcarb, meal, snack  
**Objectif:** Identifier TOUS les points de blocage possibles

---

## 🔍 FLOW COMPLET PREBOLUS

### **Step 1: tryManualModes() calcule le bolus**
```kotlin
// Ligne 5883-5900 (P1)
if (!state.pre1 && activeRuntimeMin <= 30) {
    val basePre1 = pre1Config  // ← Config prebolus (ex: 6.0U)
    if (basePre1 > 0) {
        actionBolus = (basePre1 * plan.bolusFactor)  // ← Facteur dégradation
        actionPhase = "P1"
        state.pre1 = true  // ← Marque comme envoyé
        state.pre1SentMs = now
    }
}

return DecisionResult.Applied(
    source = "ManualMode_$activeName",
    bolusU = actionBolus,  // ← Retourné
    ...
)
```

**Points de Blocage Possibles:**
1. ❌ `basePre1 = 0` → Config non définie
2. ❌ `plan.bolusFactor = 0` → Dégradation CRITICAL
3. ❌ `state.pre1 = true` déjà → État persisté d'une activ précédente
4. ❌ `activeRuntimeMin > 30` → Mode activé il y a >30min

---

### **Step 2: determine_basal() reçoit le résultat**
```kotlin
// Ligne 4091-4099
val manualRes = tryManualModes(...)
if (manualRes is DecisionResult.Applied) {
    consoleLog.add("MODE_ACTIVE source=${manualRes.source} bolus=${manualRes.bolusU}")
    
    if (manualRes.bolusU != null && manualRes.bolusU > 0) {  // ← CHECK ICI
        finalizeAndCapSMB(rT, manualRes.bolusU, ..., true, ...)
    }
}
```

**Points de Blocage Possibles:**
5. ❌ `manualRes.bolusU = 0.0` → Bolus calculé à 0 (Step 1)
6. ❌ `manualRes.bolusU = null` → Pas de bolus retourné

---

### **Step 3: finalizeAndCapSMB() traite le bolus**
```kotlin
// Ligne 1389-1402: Reactivity Clamp
if (bg < 120.0 && !isExplicitUserAction) {  // ← Modes repas bypassent
    // Clamp reactivity
}

// Ligne 1419-1428: Safety Precautions
var safetyCappedUnits = applySafetyPrecautions(
    ...,
    ignoreSafetyConditions = isExplicitUserAction  // ← true pour modes
)

// Ligne 1439-1445: LOW_BG_GUARD
if (bg < 120.0 && !isExplicitUserAction) {  // ← Modes repas bypassent
    // Réduit maxSMB
}

// Ligne 1459-1465: Refractory Block
val refractoryBlocked = sinceBolus < refractoryWindow && !isExplicitUserAction
if (refractoryBlocked) {
    gatedUnits = 0f  // ← BLOQUE si bolus récent
}

// Ligne 1471-1475: Absorption Guard
if (sinceBolus < 20.0 && iobActivityNow > activityThreshold && !isExplicitUserAction) {
    absorptionFactor = 0.5
    gatedUnits = gatedUnits * 0.5  // ← Réduit 50%
}

// Ligne 1477-1480: Pred Missing
if (predMissing && !isExplicitUserAction) {
    val degraded = (maxSMB * 0.5).toFloat()
    if (gatedUnits > degraded) gatedUnits = degraded
}

// Ligne 1483-1531: PKPD Throttle (NOUVEAU)
if (!isExplicitUserAction) {
    // Throttle SMB
    pkpdThrottleIntervalAdd = ...
    pkpdPreferTbrBoost = ...
} else {
    // Reset pour modes repas ← OK
    pkpdThrottleIntervalAdd = 0
    pkpdPreferTbrBoost = 1.0
}

// Ligne 1532-1541: capSmbDose() - CAP FINAL
val safeCap = capSmbDose(
    proposedSmb = gatedUnits,
    bg = this.bg,
    maxSmbConfig = kotlin.math.max(baseLimit, proposedUnits),  // ← Modes peuvent dépasser
    iob = this.iob.toDouble(),
    maxIob = this.maxIob
)

// Ligne 1548-1550: Affectation finale
rT.units = safeCap.toDouble().coerceAtLeast(0.0)
```

**Points de Blocage Possibles:**
7. ❌ `applySafetyPrecautions` réduit malgré `ignoreSafetyConditions=true`
8. ❌ `capSmb Dose` plafonne à cause de maxIOB
9. ❌ `refractory` active malgré bypass (bug check)
10. ❌ `absorptionGuard` active malgré bypass (bug check)

---

## 🎯 CAUSES PROBABLES (Classées par Fréquence)

### **#1: État Persisté (pre1=true déjà)**
**Symptôme:** Mode activé mais P1 ne part pas  
**Cause:** `state.pre1 = true` d'une activation précédente  
**Test:** Vérifier log `MODE_DEBUG state.pre1=true`  
**Fix:** Reset state si nouveau mode ou gap >5min

### **#2: Config Prebolus = 0**
**Symptôme:** Log `MODE_DEBUG_P1 decision=SKIP reason=basePre1_is_zero`  
**Cause:** Prebolus1 Lunch non configuré dans les préférences  
**Fix:** Configurer `OApsAIMILunchPrebolus` > 0

### **#3: Dégradation CRITICAL (bolusFactor=0)**
**Symptôme:** Log `MODE_DEGRADED_3` + `UI_BANNER HALTED`  
**Cause:** BG < 39 ou CGM stale >20min  
**Fix:** Attendre CGM valide ou BG remonte

### **#4: maxIOB Saturé**
**Symptôme:** SMB proposé = 6.0U, final = 0-2U  
**Log:** `IOB_SATURATION (IOB 13.0 >= MaxIOB 15.0)`  
**Fix:** Attendre IOB descende ou augmenter maxIOB

### **#5: Runtime > 30 min**
**Symptôme:** Log `MODE_DEBUG_P1 entered=false rt=35`  
**Cause:** Mode activé il y a >30min, catchup expiré  
**Fix:** Réactiver le mode

---

## 🔧 LOGS À CHERCHER (Ordre de Priorité)

### **1. Vérifier si mode détecté:**
```
MODE_DEBUG mode=Lunch rt=2 state.pre1=false p1Cfg=6.0 p2Cfg=2.0
```
- ✅ Si absent → Mode pas détecté (thérapie events?)
- ✅ Si `p1Cfg=0.0` → Config manquante

### **2. Vérifier décision P1:**
```
MODE_DEBUG_P1 entered=true basePre1=6.0
MODE_DEBUG_P1 decision=SEND bolus=6.0 factor=1.0
```
- ✅ Si `entered=false` → `state.pre1=true` déjà OU `runtime>30`
- ✅ Si `decision=SKIP` → `basePre1=0`

### **3. Vérifier dégradation:**
```
MODE_DEGRADED_3 ... reason=CGM Stale (>20min)
UI_BANNER ⚠️ Mode Meal: HALTED (CGM Stale)
```
- ✅ Si DEGRADED_3 → Bolus forcé à 0.0

### **4. Vérifier SMB final:**
```
MODE_ACTIVE mode=Lunch phase=P1 bolus=6.00 tbr=4.50
SMB_CAP: Proposed=6.0 Allowed=2.0
IOB_SATURATION (IOB 13.0 >= MaxIOB 15.0)
```
- ✅ Si `bolus=6.00` mais `Allowed=0-2` → maxIOB problème

### **5. Vérifier refractory (ne devrait PAS apparaître):**
```
Refractory reduced SMB: 6.0 -> 0
```
- ❌ Si présent → BUG, `isExplicitUserAction` pas respecté

---

## ✅ CHECKLIST DIAGNOSTIC

### A. Vérifier Configuration
- [ ] `OApsAIMILunchPrebolus` > 0 ?
- [ ] `OApsAIMIDinnerPrebolus` > 0 ?
- [ ] `OApsAIMIBFPrebolus` > 0 ?
- [ ] TBR max configurée ?

### B. Vérifier État Système
- [ ] CGM age < 20 min ?
- [ ] BG > 39 mg/dL ?
- [ ] IOB < maxIOB ?
- [ ] Pas de refractory actif ?

### C. Vérifier Logs
- [ ] `MODE_DEBUG` présent ?
- [ ] `MODE_DEBUG_P1 entered=true` ?
- [ ] `MODE_DEBUG_P1 decision=SEND` ?
- [ ] `MODE_ACTIVE bolus=X.XX` ?

### D. Vérifier Logs d'Erreur
- [ ] Pas de `MODE_DEGRADED_3` ?
- [ ] Pas de `UI_BANNER HALTED` ?
- [ ] Pas de `IOB_SATURATION` ?
- [ ] Pas de refractory logs ?

---

## 🚨 BUGS POTENTIELS À VÉRIFIER

### **Bug 1: isExplicitUserAction pas propagé**
**Location:** `finalizeAndCapSMB`  
**Check:** Vérifier que `isExplicitUserAction=true` est bien passé  
**Symptôme:** Refractory/absorption guards actifs pour modes

### **Bug 2: État persisté corrompu**
**Location:** `ModeState.deserialize`  
**Check:** Si `state.pre1=true` alors que mode vient d'être activé  
**Fix:** Forcer reset si `timeDiff > 300000L` (5 min)

### **Bug 3: Runtime mal calculé**
**Location:** `tryManualModes` ligne 5850  
**Check:** `activeRuntimeMin` est-il correct?  
**Fix:** Vérifier calcul `runtimeToMinutes()`

---

## 💡 COMMANDES DIAGNOSTIC

### Chercher logs modes:
```bash
adb logcat | grep "MODE_DEBUG"
adb logcat | grep "MODE_ACTIVE"
adb logcat | grep "MODE_DEGRADED"
```

### Chercher logs SMB caps:
```bash
adb logcat | grep "SMB_CAP"
adb logcat | grep "IOB_SATURATION"
```

### Chercher état:
```bash
adb logcat | grep "ModeState"
```

---

## 🎯 ACTION IMMÉDIATE RECOMMANDÉE

1. **Activer mode Lunch**
2. **Collecter logs:**
   - Chercher `MODE_DEBUG mode=Lunch`
   - Chercher `MODE_DEBUG_P1`
   - Chercher `MODE_ACTIVE`
   - Chercher `SMB_CAP`
3. **Partager ici les 20 premières lignes contenant "MODE_"**

**Avec ces logs, je pourrai identifier précisément le blocage.** 🔍
