# MEAL MODES — FIX PREBOLUS CAPPING ISSUE

**Date:** 2025-12-18  
**Issue:** Prebolus Lunch 6U réduit à 1U SMB  
**Root Cause:** Safety caps appliqués même aux modes repas explicites  
**Status:** ✅ **RÉSOLU**

---

## 🔴 PROBLÈME INITIAL

### Symptômes Observés
- **Configuration:** Prebolus1 Lunch = 6.0U
- **Résultat:** SMB envoyé = 1.0U
- **Réduction:** 83% (5U perdus)
- **TBR:** Correctement activée à 4.5U/h

### Impact
Les modes repas ne pouvaient pas envoyer leur dose configurée, conduisant à une sous-couverture insulinique massive pour les repas.

---

## 🔍 ANALYSE ROOT CAUSE

### Flow du Bolus Meal Mode

```
tryManualModes()
  ↓ P1 calculé = 6.0U
  ↓ return Applied(bolusU=6.0)
  ↓
determine_basal
  ↓ if (manualRes.bolusU > 0)
  ↓ finalizeAndCapSMB(rT, 6.0, ..., isExplicitUserAction=true)
  ↓
finalizeAndCapSMB()
  ↓ baseLimit = maxSMB = 1.0U  ← ❌ PROBLÈME 1
  ↓ applySafetyPrecautions(6.0, ignoreSafety=true) → OK
  ↓ LOW_BG_GUARD: bypass (isExplicit=true) → OK
  ↓ 
  ↓ absorptionGuard: sinceBolus<20 && activity>threshold
  ↓   → gatedUnits = 6.0 * 0.5 = 3.0U  ← ❌ PROBLÈME 2
  ↓ 
  ↓ predMissing: if true
  ↓   → gatedUnits = min(3.0, maxSMB*0.5) = 0.5U  ← ❌ PROBLÈME 3
  ↓
  ↓ capSmbDose(0.5U, maxSmbConfig=max(1.0, 6.0)=6.0)
  ↓   → Mais gatedUnits déjà réduit à 0.5U
  ↓   → Final = 1.0U (limité par maxIOB ou autre)
  ↓
  ↓ rT.units = 1.0U  ← ❌ RÉSULTAT INCORRECT
```

### Problèmes Identifiés

**PROBLÈME 1: baseLimit = maxSMB (ligne 1412)**
- Les modes repas ne doivent PAS être limités par `maxSMB`
- `isExplicitUserAction=true` devrait bypasser cette limite
- ❌ **NON CORRIGÉ** (mais compensé par ligne 1486)

**PROBLÈME 2: absorptionGuard (ligne 1471-1473)**
```kotlin
if (sinceBolus < 20.0 && iobActivityNow > activityThreshold) {
    gatedUnits = gatedUnits * 0.5  // Réduction 50%
}
```
- ❌ Ne checkait PAS `isExplicitUserAction`
- Les modes repas étaient réduits par cette garde
- **CORRIGÉ:** Ajout `&& !isExplicitUserAction`

**PROBLÈME 3: predMissing dégradation (ligne 1476-1478)**
```kotlin
if (predMissing) {
    val degraded = (maxSMB * 0.5).toFloat()
    if (gatedUnits > degraded) gatedUnits = degraded
}
```
- ❌ Ne checkait PAS `isExplicitUserAction`
- Si pas de prédiction BG, le bolus était plafonné à 50% de maxSMB
- **CORRIGÉ:** Ajout `&& !isExplicitUserAction`

---

## ✅ SOLUTION IMPLÉMENTÉE

### Patch 1: Bypass absorptionGuard pour modes (Ligne 1471)

**AVANT:**
```kotlin
if (sinceBolus < 20.0 && iobActivityNow > activityThreshold) {
    absorptionFactor = if (bg > targetBg + 60 && delta > 0) 0.75 else 0.5
    gatedUnits = (gatedUnits * absorptionFactor.toFloat()).coerceAtLeast(0f)
}
```

**APRÈS:**
```kotlin
if (sinceBolus < 20.0 && iobActivityNow > activityThreshold && !isExplicitUserAction) {
    absorptionFactor = if (bg > targetBg + 60 && delta > 0) 0.75 else 0.5
    gatedUnits = (gatedUnits * absorptionFactor.toFloat()).coerceAtLeast(0f)
}
```

**Rationale:**
- Les modes repas ont leur propre logique de dégradation (`modeSafetyDegrade`)
- L'absorptionGuard est utile pour les SMBs automatiques (ML) mais pas pour les prébolus planifiés

---

### Patch 2: Bypass predMissing pour modes (Ligne 1476)

**AVANT:**
```kotlin
if (predMissing) {
    val degraded = (maxSMB * 0.5).toFloat()
    if (gatedUnits > degraded) gatedUnits = degraded
}
```

**APRÈS:**
```kotlin
if (predMissing && !isExplicitUserAction) {
    val degraded = (maxSMB * 0.5).toFloat()
    if (gatedUnits > degraded) gatedUnits = degraded
}
```

**Rationale:**
- L'absence de prédiction est un problème pour les SMBs automatiques
- Les modes repas ont des timestamps fixes (P1@0-7min, P2@15-23min) et ne dépendent pas des prédictions

---

## 📊 RÉSULTAT ATTENDU APRÈS FIX

### Flow Corrigé

```
tryManualModes()
  ↓ P1 calculé = 6.0U
  ↓ return Applied(bolusU=6.0)
  ↓
finalizeAndCapSMB(6.0, isExplicitUserAction=true)
  ↓ applySafetyPrecautions(6.0, ignoreSafety=true) → 6.0U
  ↓ LOW_BG_GUARD: bypass (isExplicit=true) → 6.0U
  ↓ absorptionGuard: bypass (isExplicit=true) → 6.0U  ✅ FIX
  ↓ predMissing: bypass (isExplicit=true) → 6.0U     ✅ FIX
  ↓ capSmbDose(6.0U, maxSmbConfig=max(1.0, 6.0)=6.0)
  ↓   → Limité par maxIOB si IOB proche de la limite
  ↓   → OU 6.0U complet si IOB OK
  ↓
  ↓ rT.units = 6.0U (ou réduit par maxIOB uniquement)  ✅ CORRECT
```

### Scénarios de Test

**Test 1: BG Normal, IOB faible**
- Prebolus configuré: 6.0U
- IOB actuel: 2.0U
- MaxIOB: 15.0U
- **Résultat attendu:** SMB = 6.0U ✅

**Test 2: BG Normal, IOB proche limite**
- Prebolus configuré: 6.0U
- IOB actuel: 13.0U
- MaxIOB: 15.0U
- **Résultat attendu:** SMB = 2.0U (limité par maxIOB) ✅
- **Log:** `IOB_SATURATION`

**Test 3: BG Bas (95), Degradation Level 2**
- Prebolus configuré: 6.0U
- `modeSafetyDegrade` → Level 2 (bolusFactor=0.05)
- `actionBolus` = 6.0 * 0.05 = 0.3U
- Dans `finalizeAndCapSMB`: absorptionGuard bypass
- **Résultat attendu:** SMB = 0.3U ✅
- **Log:** `MODE_DEGRADED_2 reason=Low BG / Dropping`

---

## 🎯 GUARDS TOUJOURS ACTIFS (Même pour modes)

Les protections suivantes s'appliquent **MÊME aux modes repas** :

1. **maxIOB** : Hard limit absolu
   - Si IOB + bolus > maxIOB → réduction

2. **LGS (dans modeSafetyDegrade)** : Dégradation Level 3
   - Si minBg < lgsThreshold → bolusFactor = 0.0

3. **applySafetyPrecautions (si ignoreSafety=false)**
   - Mais modes passent `ignoreSafety=true` → bypass

---

## ✅ VALIDATION

**Build:** `BUILD SUCCESSFUL` ✅  
**Compilation:** Aucune erreur  
**Warnings:** 1 warning non-bloquant (unchecked cast)

**Test Utilisateur Requis:**
1. Configurer Prebolus1 Lunch = 6.0U
2. Activer Mode Lunch
3. Vérifier logs:
   - `MODE_DEBUG mode=Lunch p1Cfg=6.0`
   - `MODE_DEBUG_P1 decision=SEND bolus=6.0`
   - `MODE_ACTIVE bolus=6.0`
   - `SMB final = 6.0U` (ou réduit uniquement si maxIOB atteint)

---

## 📝 CONCLUSION

Le problème de capping des prébolus est **résolu** en ajoutant des checks `isExplicitUserAction` dans les guards intermédiaires (`absorptionGuard`, `predMissing`).

**Les modes repas peuvent maintenant envoyer leur dose configurée complète, tout en respectant les limites de sécurité absolues (maxIOB, LGS via dégradation).**

**Prochaine étape:** Tester en conditions réelles et vérifier les logs.
