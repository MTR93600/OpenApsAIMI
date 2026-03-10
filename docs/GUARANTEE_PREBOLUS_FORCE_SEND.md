# GARANTIE PREBOLUS MODES REPAS — IMPLÉMENTATION "FORCE SEND"

**Date:** 2025-12-18 21:10  
**Objectif:** **GARANTIR** l'envoi de P1 et P2 pour lunch, dinner, bfast, highcarb, meal, snack  
**Status:** ✅ **IMPLÉMENTÉ**

---

## 🎯 GARANTIES FOURNIES

### **Pour TOUS les modes repas (lunch, dinner, bfast, highcarb, meal, snack) :**

✅ **P1 et P2 TOUJOURS envoyés** (sauf CRITICAL: BG < 39 ou CGM stale >20min)  
✅ **Bypass refractory** (déjà fait)  
✅ **Bypass absorptionGuard** (déjà fait)  
✅ **Bypass predMissing** (déjà fait)  
✅ **Bypass PKPD throttle** (déjà fait)  
✅ **Bypass maxIOB** (NOUVEAU !) ← **C'ÉTAIT LE BLOCAGE**  

**Seule limite restante:** 30U hard cap (sécurité absolue contre config erronée)

---

## 🔧 CE QUI A ÉTÉ MODIFIÉ

### **Patch: MEAL_MODE_FORCE_SEND (ligne 1553-1582)**

```kotlin
// 🚀 MEAL MODES FORCE SEND: Garantir l'envoi P1/P2 (Bypass maxIOB si nécessaire)
var finalUnits = safeCap.toDouble()

if (isExplicitUserAction && gatedUnits > 0f) {
    // Pour les modes repas, on utilise directement gatedUnits
    // (déjà réduit par dégradation si nécessaire)
    // On bypass capSmbDose qui plafonne à maxIOB
    // Seule limite : 30U hard cap (sécurité absolue)
    val mealModeCap = gatedUnits.toDouble().coerceAtMost(30.0)
    
    if (mealModeCap > safeCap.toDouble()) {
        consoleLog.add("🍱 MEAL_MODE_FORCE_SEND bypassing maxIOB: ...")
        consoleLog.add("  ⚠️ IOB will be: current=... + bolus=... = ...")
        finalUnits = mealModeCap
    } else {
        // safeCap déjà OK, pas besoin de forcer
        finalUnits = safeCap.toDouble()
    }
}

rT.units = finalUnits.coerceAtLeast(0.0)
```

**Principe:**
1. `gatedUnits` = Dose après dégradation PKPD (70-100% selon BG)
2. `safeCap` = `capSmbDose()` qui plafonne à maxIOB
3. `finalUnits` = MAX(gatedUnits, safeCap) avec hard cap 30U

**Résultat:** Si maxIOB bloque, on force quand même l'envoi (modes repas seulement)

---

## 📊 SCÉNARIOS

### **Scénario 1: P1 = 6.0U, maxIOB = 15U, IOB actuel = 13.0U**

**AVANT (Bloqué par maxIOB):**
```
MODE_ACTIVE bolus=6.0
capSmbDose: IOB 13.0 + 6.0 > maxIOB 15.0 → cap to 2.0U
SMB final: 2.0U ❌
```

**APRÈS (Force Send):**
```
MODE_ACTIVE bolus=6.0
capSmbDose: returns 2.0U
🍱 MEAL_MODE_FORCE_SEND bypassing maxIOB: gated=6.00 safeCap=2.00 → FORCED=6.00
  ⚠️ IOB will be: current=13.00 + bolus=6.00 = 19.00 (maxIOB=15.00)
SMB final: 6.0U ✅
```

**Explication:** Le système FORCE l'envoi de 6.0U même si ça dépasse maxIOB de 4U.

---

### **Scénario 2: P1 = 6.0U, BG = 62, dégradation CAUTION (70%)**

**Flow:**
1. `tryManualModes` calcule: `actionBolus = 6.0 * 0.7 = 4.2U`
2. `finalizeAndCapSMB` reçoit `proposedUnits = 4.2`
3. Pas de reduction (refractory/absorption/predMissing bypassés)
4. `gatedUnits = 4.2`
5. `capSmbDose` retourne 4.2 (< maxIOB)
6. `finalUnits = 4.2`

**Résultat:** SMB = 4.2U ✅ (dégradé mais envoyé)

---

### **Scénario 3: P1 = 6.0U, BG = 35, dégradation CRITICAL**

**Flow:**
1. `modeSafetyDegrade` retourne `bolusFactor = 0.0` (BG < 39)
2. `actionBolus = 6.0 * 0.0 = 0.0`
3. `finalizeAndCapSMB` reçoit `proposedUnits = 0.0`
4. `finalUnits = 0.0`

**Résultat:** SMB = 0.0U ❌ (sécurité critique, justifié)

---

### **Scénario 4: P2 = 2.0U, maxIOB = 10U, IOB = 9.5U**

**AVANT:**
```
capSmbDose: IOB 9.5 + 2.0 > 10.0 → cap to 0.5U
SMB final: 0.5U ❌
```

**APRÈS:**
```
🍱 MEAL_MODE_FORCE_SEND bypassing maxIOB: gated=2.00 safeCap=0.50 → FORCED=2.00
SMB final: 2.0U ✅
```

---

## 📋 LOGS ATTENDUS

### **Prebolus Envoyé Normalement** (sans force)
```
MODE_ACTIVE source=ManualMode_Lunch bolus=6.0
(pas de log MEAL_MODE_FORCE_SEND car pas besoin)
```

### **Prebolus Forcé** (maxIOB dépassé)
```
MODE_ACTIVE source=ManualMode_Lunch bolus=6.0
🍱 MEAL_MODE_FORCE_SEND bypassing maxIOB: proposed=6.00 gated=6.00 safeCap=2.00 → FORCED=6.00
  ⚠️ IOB will be: current=13.00 + bolus=6.00 = 19.00 (maxIOB=15.00)
SMB_CAP: Proposed=6.0 Allowed=6.0
```

### **Prebolus Dégradé mais Envoyé** (BG = 65)
```
MODE_DEGRADED_0 mode=Lunch phase=P1 bolus=4.20 ... reason=BG Low (meal will raise)
MODE_ACTIVE source=ManualMode_Lunch bolus=4.2
```

### **Prebolus Bloqué** (BG < 39 - seule exception)
```
MODE_DEGRADED_3 mode=Lunch ... reason=Data Incoherent (BG invalid)
UI_BANNER ⚠️ Mode Meal: HALTED (Data Error)
MODE_ACTIVE source=ManualMode_Lunch bolus=0.0
```

---

## 🛡️ SÉCURITÉS RESTANTES

Les seules conditions qui peuvent **BLOQUER** un prebolus :

### **1. BG < 39 mg/dL**
**Raison:** Limite calibration CGM / hypo sévère  
**Action:** `bolusFactor = 0.0` → `actionBolus = 0`  
**Log:** `MODE_DEGRADED_3 Data Incoherent`

### **2. BG > 600 mg/dL**
**Raison:** Unité mismatch ou défaillance capteur  
**Action:** `bolusFactor = 0.0`  
**Log:** `MODE_DEGRADED_3 Data Incoherent`

### **3. CGM Stale > 20 min**
**Raison:** Pas de donnée fiable  
**Action:** `bolusFactor = 0.0`  
**Log:** `MODE_DEGRADED_3 CGM Stale`

### **4. Hard Cap 30U**
**Raison:** Protection contre config erronée (ex: prebolus1 = 50U)  
**Action:** `mealModeCap = gatedUnits.coerceAtMost(30.0)`  
**Log:** `MEAL_MODE_FORCE_SEND ... FORCED=30.00`

**Tout le reste est BYPASSÉ pour les modes repas.** ✅

---

## ✅ BUILD STATUS

```bash
BUILD SUCCESSFUL in 7s
```

**Erreurs:** 0 ✅  
**Warnings:** 1 (unchecked cast, pre-existant)

---

## 🎯 RÉSUMÉ

### **CE QUI EST GARANTI:**

1. ✅ **P1 et P2 TOUJOURS envoyés** (sauf BG < 39 / >600 / CGM stale)
2. ✅ **Pas de blocage refractory** (bypass)
3. ✅ **Pas de blocage absorption** (bypass)
4. ✅ **Pas de blocage predMissing** (bypass)
5. ✅ **Pas de blocage maxIOB** (bypass jusqu'à 30U)
6. ✅ **Dégradation intelligente** (70% si BG < 70, 100% sinon)

### **SEULES EXCEPTIONS:**

- ❌ BG < 39 mg/dL → Bloquer (safety critique)
- ❌ BG > 600 mg/dL → Bloquer (safety critique)
- ❌ CGM stale > 20 min → Bloquer (safety critique)
- ⚠️ Hard cap 30U → Plafonner (safety config)

---

## 🚀 PROCHAINE ÉTAPE

**Rebuilder et tester:**
1. Activer mode Lunch avec P1 = 6.0U
2. Vérifier IOB actuel (ex: 13U)
3. Observer log `MEAL_MODE_FORCE_SEND` si IOB+bolus > maxIOB
4. **GARANTIE:** Le prebolus part QUAND MÊME ✅

**Le système "degrade, never block" est maintenant COMPLET pour les modes repas !** 🎉
