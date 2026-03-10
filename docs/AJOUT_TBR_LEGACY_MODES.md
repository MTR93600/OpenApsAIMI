# AJOUT TBR POUR LEGACY MEAL MODES

**Date:** 2025-12-18 22:02  
**Problème:** Les TBR des modes repas ont été perdues avec la restauration legacy  
**Solution:** Ajouter `setTempBasal()` dans chaque legacy check

---

## 🎯 PROBLÈME IDENTIFIÉ

### **Avant (avec tryManualModes):**
```kotlin
return DecisionResult.Applied(
    bolusU = 6.0,
    tbrUph = 12.0,  // ← TBR envoyée !
    tbrMin = 30
)
```

### **Maintenant (legacy):**
```kotlin
if (isLunchModeCondition()) {
    rT.units = 6.0
    return rT  // ← PAS DE TBR !
}
```

**Résultat:** Prebolus envoyé ✅, TBR manquante ❌

---

## 🔧 SOLUTION

Ajouter `setTempBasal()` **AVANT** `return rT` dans chaque legacy check.

### **TBR à appliquer:**
- **Rate:** `meal_modes_MaxBasal` preference OU `profile.max_basal`
- **Durée:** 30 minutes
- **Condition:** Runtime < 30 min (sinon pas de TBR, juste prebolus)

---

## 📋 CODE À AJOUTER

### **Étape 1: Calculer modeTbrLimit (AVANT les legacy checks)**

**Localisation:** Juste AVANT `if (isMealModeCondition())` (ligne ~4075)

```kotlin
// 🍱 LEGACY MEAL MODES: Calculate TBR limit
val maxBasalPref = preferences.get(DoubleKey.meal_modes_MaxBasal)
val modeTbrLimit = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal
```

---

### **Étape 2: Modifier CHAQUE legacy check pour ajouter TBR**

**Pattern:**
```kotlin
if (is*ModeCondition()) {
    val prebolus* = preferences.get(DoubleKey.OApsAIMI*Prebolus)
    
    // 🚀 TBR: Apply if runtime < 30 min    if (*runtime < 30 * 60) {  // runtime en secondes        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = prebolus*
    rT.reason.append(...)
    consoleLog.add("🍱 LEGACY_MODE_* P*=...U (DIRECT SEND)")
    return rT
}
```

---

### **Étape 3: Code complet pour CHAQUE mode**

#### **Meal Mode:**
```kotlin
if (isMealModeCondition()) {
    val pbolusM = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
    
    // TBR
    if (mealruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_MEAL rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusM
    rT.reason.append(context.getString(R.string.manual_meal_prebolus, pbolusM))
    consoleLog.add("🍱 LEGACY_MODE_MEAL P1=${"%.2f".format(pbolusM)}U (DIRECT SEND)")
    return rT
}
```

#### **Breakfast Mode (P1):**
```kotlin
if (isbfastModeCondition()) {
    val pbolusbfast = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
    
    // TBR
    if (bfastruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_BFAST rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusbfast
    rT.reason.append(context.getString(R.string.reason_prebolus_bfast1, pbolusbfast))
    consoleLog.add("🍱 LEGACY_MODE_BFAST P1=${"%.2f".format(pbolusbfast)}U (DIRECT SEND)")
    return rT
}
```

#### **Breakfast Mode (P2):**
```kotlin
if (isbfast2ModeCondition()) {
    val pbolusbfast2 = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
    
    // TBR (still active if runtime < 30)
    if (bfastruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_BFAST rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusbfast2
    rT.reason.append(context.getString(R.string.reason_prebolus_bfast2, pbolusbfast2))
    consoleLog.add("🍱 LEGACY_MODE_BFAST P2=${"%.2f".format(pbolusbfast2)}U (DIRECT SEND)")
    return rT
}
```

#### **Lunch Mode (P1):**
```kotlin
if (isLunchModeCondition()) {
    val pbolusLunch = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
    
    // TBR
    if (lunchruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_LUNCH rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusLunch
    rT.reason.append(context.getString(R.string.reason_prebolus_lunch1, pbolusLunch))
    consoleLog.add("🍱 LEGACY_MODE_LUNCH P1=${"%.2f".format(pbolusLunch)}U (DIRECT SEND)")
    return rT
}
```

#### **Lunch Mode (P2):**
```kotlin
if (isLunch2ModeCondition()) {
    val pbolusLunch2 = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
    
    // TBR
    if (lunchruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_LUNCH rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusLunch2
    rT.reason.append(context.getString(R.string.reason_prebolus_lunch2, pbolusLunch2))
    consoleLog.add("🍱 LEGACY_MODE_LUNCH P2=${"%.2f".format(pbolusLunch2)}U (DIRECT SEND)")
    return rT
}
```

#### **Dinner Mode (P1):**
```kotlin
if (isDinnerModeCondition()) {
    val pbolusDinner = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
    
    // TBR
    if (dinnerruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_DINNER rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusDinner
    rT.reason.append(context.getString(R.string.reason_prebolus_dinner1, pbolusDinner))
    consoleLog.add("🍱 LEGACY_MODE_DINNER P1=${"%.2f".format(pbolusDinner)}U (DIRECT SEND)")
    return rT
}
```

#### **Dinner Mode (P2):**
```kotlin
if (isDinner2ModeCondition()) {
    val pbolusDinner2 = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
    
    // TBR
    if (dinnerruntime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_DINNER rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusDinner2
    rT.reason.append(context.getString(R.string.reason_prebolus_dinner2, pbolusDinner2))
    consoleLog.add("🍱 LEGACY_MODE_DINNER P2=${"%.2f".format(pbolusDinner2)}U (DIRECT SEND)")
    return rT
}
```

#### **HighCarb Mode:**
```kotlin
if (isHighCarbModeCondition()) {
    val pbolusHC = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
    
    // TBR
    if (highCarbrunTime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_HIGHCARB rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolusHC
    rT.reason.append(context.getString(R.string.reason_prebolus_highcarb, pbolusHC))
    consoleLog.add("🍱 LEGACY_MODE_HIGHCARB P1=${"%.2f".format(pbolusHC)}U (DIRECT SEND)")
    return rT
}
```

#### **Snack Mode:**
```kotlin
if (issnackModeCondition()) {
    val pbolussnack = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
    
    // TBR
    if (snackrunTime < 30 * 60) {
        setTempBasal(modeTbrLimit, 30, profile, rT, currenttemp, overrideSafetyLimits = false)
        consoleLog.add("🍱 LEGACY_TBR_SNACK rate=${"%.2f".format(modeTbrLimit)}U/h duration=30m")
    }
    
    rT.units = pbolussnack
    rT.reason.append(context.getString(R.string.reason_prebolus_snack, pbolussnack))
    consoleLog.add("🍱 LEGACY_MODE_SNACK P1=${"%.2f".format(pbolussnack)}U (DIRECT SEND)")
    return rT
}
```

---

## 📊 LOGS ATTENDUS

### **Mode Lunch activé (runtime = 5 min):**
```
🍱 LEGACY_TBR_LUNCH rate=12.00U/h duration=30m
🍱 LEGACY_MODE_LUNCH P1=6.00U (DIRECT SEND)
Temp Basal Started 12.00 for 30m
Microbolusing 1/2 Lunch Mode 6.0U
```

### **Mode Lunch P2 (runtime = 20 min):**
```
🍱 LEGACY_TBR_LUNCH rate=12.00U/h duration=30m
🍱 LEGACY_MODE_LUNCH P2=2.00U (DIRECT SEND)
Temp Basal Started 12.00 for 30m
Microbolusing 2/2 Lunch Mode 2.0U
```

### **Mode Lunch runtime > 30 min (pas de TBR):**
```
🍱 LEGACY_MODE_LUNCH P2=2.00U (DIRECT SEND)
Microbolusing 2/2 Lunch Mode 2.0U
(pas de TBR car runtime > 30 min)
```

---

## ⚠️ ATTENTION

**Runtime Check:** Les variables runtime sont en **SECONDES**, pas minutes !

- `lunchruntime` = secondes depuis activation
- Check: `if (lunchruntime < 30 * 60)` = si < 30 minutes

Si runtime est déjà en minutes (vérifier !), alors :
```kotlin
if (lunchruntime < 30) {  // si en minutes
    setTempBasal(...)
}
```

**Vérifier avec un log de debug d'abord !**

---

## ✅ BÉNÉFICES

1. ✅ **Prebolus garanti** (déjà fait)
2. ✅ **TBR accompagnante** (comme avant)
3. ✅ **Système complet** (SMB + basal boosté)
4. ✅ **Meilleure couverture** des repas

---

## 🚀 PROCHAINES ÉTAPES

1. **Ajouter** `modeTbrLimit` calculation (ligne ~4075)
2. **Modifier** TOUS les legacy checks (9 blocs)
3. **Compiler** et tester
4. **Vérifier** logs TBR + prebolus ensemble

**Le système sera alors 100% complet !** 🎉
