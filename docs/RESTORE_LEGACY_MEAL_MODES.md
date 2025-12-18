# RESTAURATION LEGACY MEAL MODES — CODE À AJOUTER MANUELLEMENT

**Date:** 2025-12-18 21:20  
**Objectif:** Restaurer l'ancien système de prebolus DIRECT (sans safety)  
**Méthode:** Code à copier-coller manuellement

---

## 🎯 POURQUOI CE CODE

L'ancien système était **DIRECT** :
```kotlin
if (isLunchModeCondition()) {
    rT.units = pbolusLunch
    return rT  // ← DIRECT, pas de finalizeAndCapSMB !
}
```

Cela **garantit l'envoi** sans aucune safety intermédiaire.

---

## 📋 CODE À AJOUTER

### **ÉTAPE 1: Ajouter les fonctions de condition**

**Localisation:** Dans `DetermineBasalAIMI2.kt`, **AVANT** la fonction `activeMealRuntimeMinutes()` (autour ligne 2207)

**Code à insérer:**

```kotlin
// ========================================================================
// 🍱 MEAL MODE LEGACY CONDITIONS (Direct Send - No Safety)
// ========================================================================

private fun isMealModeCondition(): Boolean {
    val pbolusM: Double = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
    return mealruntime in 0..7 && lastBolusSMBUnit != pbolusM.toFloat() && mealTime
}

private fun isbfastModeCondition(): Boolean {
    val pbolusbfast: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
    return bfastruntime in 0..7 && lastBolusSMBUnit != pbolusbfast.toFloat() && bfastTime
}

private fun isbfast2ModeCondition(): Boolean {
    val pbolusbfast2: Double = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
    return bfastruntime in 15..30 && lastBolusSMBUnit != pbolusbfast2.toFloat() && bfastTime
}

private fun isLunchModeCondition(): Boolean {
    val pbolusLunch: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
    return lunchruntime in 0..7 && lastBolusSMBUnit != pbolusLunch.toFloat() && lunchTime
}

private fun isLunch2ModeCondition(): Boolean {
    val pbolusLunch2: Double = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
    return lunchruntime in 15..24 && lastBolusSMBUnit != pbolusLunch2.toFloat() && lunchTime
}

private fun isDinnerModeCondition(): Boolean {
    val pbolusDinner: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
    return dinnerruntime in 0..7 && lastBolusSMBUnit != pbolusDinner.toFloat() && dinnerTime
}

private fun isDinner2ModeCondition(): Boolean {
    val pbolusDinner2: Double = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
    return dinnerruntime in 15..24 && lastBolusSMBUnit != pbolusDinner2.toFloat() && dinnerTime
}

private fun isHighCarbModeCondition(): Boolean {
    val pbolusHC: Double = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
    return highCarbrunTime in 0..7 && lastBolusSMBUnit != pbolusHC.toFloat() && highCarbTime
}

private fun issnackModeCondition(): Boolean {
    val pbolussnack: Double = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
    return snackrunTime in 0..7 && lastBolusSMBUnit != pbolussnack.toFloat() && snackTime
}
```

---

### **ÉTAPE 2: Ajouter les checks DIRECT dans determine_basal**

**Localisation:** Dans `determine_basal`, **JUSTE AVANT** `val manualRes = tryManualModes(...)` (ligne 4095)

**Code à insérer:**

```kotlin
// ========================================================================
// 🍱 MEAL MODES LEGACY LOGIC (DIRECT SEND - NO SAFETY)
// Priority: Check BEFORE tryManualModes to guarantee prebolus delivery
// ========================================================================

if (isMealModeCondition()) {
    val pbolusM = preferences.get(DoubleKey.OApsAIMIMealPrebolus)
    rT.units = pbolusM
    rT.reason.append(context.getString(R.string.manual_meal_prebolus, pbolusM))
    consoleLog.add("🍱 LEGACY_MODE_MEAL P1=${"%.2f".format(pbolusM)}U (DIRECT SEND)")
    return rT
}

if (isbfastModeCondition()) {
    val pbolusbfast = preferences.get(DoubleKey.OApsAIMIBFPrebolus)
    rT.units = pbolusbfast
    rT.reason.append(context.getString(R.string.reason_prebolus_bfast1, pbolusbfast))
    consoleLog.add("🍱 LEGACY_MODE_BFAST P1=${"%.2f".format(pbolusbfast)}U (DIRECT SEND)")
    return rT
}

if (isbfast2ModeCondition()) {
    val pbolusbfast2 = preferences.get(DoubleKey.OApsAIMIBFPrebolus2)
    rT.units = pbolusbfast2
    rT.reason.append(context.getString(R.string.reason_prebolus_bfast2, pbolusbfast2))
    consoleLog.add("🍱 LEGACY_MODE_BFAST P2=${"%.2f".format(pbolusbfast2)}U (DIRECT SEND)")
    return rT
}

if (isLunchModeCondition()) {
    val pbolusLunch = preferences.get(DoubleKey.OApsAIMILunchPrebolus)
    rT.units = pbolusLunch
    rT.reason.append(context.getString(R.string.reason_prebolus_lunch1, pbolusLunch))
    consoleLog.add("🍱 LEGACY_MODE_LUNCH P1=${"%.2f".format(pbolusLunch)}U (DIRECT SEND)")
    return rT
}

if (isLunch2ModeCondition()) {
    val pbolusLunch2 = preferences.get(DoubleKey.OApsAIMILunchPrebolus2)
    rT.units = pbolusLunch2
    rT.reason.append(context.getString(R.string.reason_prebolus_lunch2, pbolusLunch2))
    consoleLog.add("🍱 LEGACY_MODE_LUNCH P2=${"%.2f".format(pbolusLunch2)}U (DIRECT SEND)")
    return rT
}

if (isDinnerModeCondition()) {
    val pbolusDinner = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus)
    rT.units = pbolusDinner
    rT.reason.append(context.getString(R.string.reason_prebolus_dinner1, pbolusDinner))
    consoleLog.add("🍱 LEGACY_MODE_DINNER P1=${"%.2f".format(pbolusDinner)}U (DIRECT SEND)")
    return rT
}

if (isDinner2ModeCondition()) {
    val pbolusDinner2 = preferences.get(DoubleKey.OApsAIMIDinnerPrebolus2)
    rT.units = pbolusDinner2
    rT.reason.append(context.getString(R.string.reason_prebolus_dinner2, pbolusDinner2))
    consoleLog.add("🍱 LEGACY_MODE_DINNER P2=${"%.2f".format(pbolusDinner2)}U (DIRECT SEND)")
    return rT
}

if (isHighCarbModeCondition()) {
    val pbolusHC = preferences.get(DoubleKey.OApsAIMIHighCarbPrebolus)
    rT.units = pbolusHC
    rT.reason.append(context.getString(R.string.reason_prebolus_highcarb, pbolusHC))
    consoleLog.add("🍱 LEGACY_MODE_HIGHCARB P1=${"%.2f".format(pbolusHC)}U (DIRECT SEND)")
    return rT
}

if (issnackModeCondition()) {
    val pbolussnack = preferences.get(DoubleKey.OApsAIMISnackPrebolus)
    rT.units = pbolussnack
    rT.reason.append(context.getString(R.string.reason_prebolus_snack, pbolussnack))
    consoleLog.add("🍱 LEGACY_MODE_SNACK P1=${"%.2f".format(pbolussnack)}U (DIRECT SEND)")
    return rT
}

// Si aucun mode legacy actif, continuer avec tryManualModes (nouveau système)
```

---

## 🎯 CE QUE ÇA FAIT

### **Flow Avant (Nouveau Système - Avec Safety):**
```
Mode Lunch activé
→ tryManualModes() calcule P1 = 6.0U
→ finalizeAndCapSMB(6.0)
  → Checks refractory ❌
  → Checks maxIOB ❌
  → capSmbDose → Réduit à 2.0U
→ rT.units = 2.0U
```

**Résultat:** Prebolus bloqué/réduit !

---

### **Flow Après (Legacy System - Direct):**
```
Mode Lunch activé (runtime=2)
→ isLunchModeCondition() = true
→ rT.units = 6.0U
→ return rT IMMÉDIATEMENT
```

**Résultat:** Prebolus envoyé à 6.0U **GARANTI** !

---

## ✅ GARANTIES

Avec ce code :

1. ✅ **P1 et P2 TOUJOURS envoyés** (runtime 0-7 et 15-24)
2. ✅ **Pas de check refractory**
3. ✅ **Pas de check maxIOB**
4. ✅ **Pas de check absorption**
5. ✅ **Pas de dégradation PKPD**
6. ✅ **Envoi DIRECT** via `rT.units = prebolus`

**Seul check:** `lastBolusSMBUnit != prebolus` (évite double envoi)

---

## 📊 LOGS ATTENDUS

### **Prebolus Legacy Envoyé:**
```
🍱 LEGACY_MODE_LUNCH P1=6.00U (DIRECT SEND)
Microbolusing 1/2 Lunch Mode 6.0U
```

### **Prebolus Legacy P2:**
```
🍱 LEGACY_MODE_LUNCH P2=2.00U (DIRECT SEND)
Microbolusing 2/2 Lunch Mode 2.0U
```

---

## ⚠️ IMPORTANT

**Ce code IGNORE toutes les safety !** C'est voulu car c'est comme ça que marchait l'ancien système.

**Mais** :
- BG pourrait être bas → Prebolus envoyé quand même
- IOB pourrait être élevé → Prebolus envoyé quand même
- Prédiction pourrait montrer hypo → Prebolus envoyé quand même

**C'est acceptable** car :
1. L'utilisateur ACTIVE MANUELLEMENT le mode
2. Il a pris/va prendre des glucides
3. Le prebolus est configuré par l'utilisateur

**C'est sa responsabilité.**

---

## 🚀 APRÈS AJOUT

1. **Compiler:** `./gradlew :plugins:aps:compileFullDebugKotlin`
2. **Tester:** Activer Lunch
3. **Chercher log:** `LEGACY_MODE_LUNCH P1=...U (DIRECT SEND)`
4. **Vérifier:** SMB envoyé à la valeur configurée

**Le prebolus sera GARANTI envoyé !** 🎉
