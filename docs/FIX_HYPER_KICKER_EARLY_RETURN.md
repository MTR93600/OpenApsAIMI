# 🔧 FIX: HYPER KICKER EARLY RETURN BLOCKS SMB

## Date: 2025-12-29 19:25

---

## 1. ANALYSE PRÉ-MODIFICATION

### 1.1 Localisation du Bug

**Fichier** : `DetermineBasalAIMI2.kt`  
**Lignes** : 5383-5451

### 1.2 Code Problématique

```kotlin
val rate: Double? = when {
    snackTime && ... -> calculateRate(...)
    mealTime && ... -> calculateRate(...)
    // ... autres meal modes ...
    
    // Post-Meal Hyper Boost (ligne 5394-5416)
    (mealTime || lunchTime || ...) -> {
        val boostedRate = adjustBasalForMealHyper(...)
        if (boostedRate > profile * 1.05) calculateRate(...) else null
    }
    
    // Global Hyper Kicker (ligne 5421-5437) ← BUG ICI
    ((bg > target_bg + 40 || delta > 5.0f) && ...) -> {
        val boostedRate = adjustBasalForGeneralHyper(...)
        if (boostedRate > profile * 1.1) {
            calculateRate(..., "Global Hyper Kicker (Active)", ...)
        } else null
    }
    
    fastingTime -> calculateRate(...)
    else -> null
}

// Ligne 5445 - EARLY RETURN BLOQUANT
rate?.let {
    rT.rate = it.coerceAtLeast(0.0)
    rT.deliverAt = deliverAt
    rT.duration = 30
    logDecisionFinal("BASAL_RATE", rT, bg, delta)
    return rT  // ← SORT AVANT SMB !
}
```

---

### 1.3 Branches qui Rendent `rate` Non-Null

1. **snackTime** (ligne 5384)
2. **mealTime** (ligne 5385)
3. **bfastTime** (ligne 5386)
4. **lunchTime** (ligne 5387)
5. **dinnerTime** (ligne 5388)
6. **highCarbTime** (ligne 5389)
7. **Post-Meal Hyper Boost** (ligne 5394-5416) → si boostedRate > profile * 1.05
8. **Global Hyper Kicker** (ligne 5421-5437) → si boostedRate > profile * 1.1
9. **fastingTime** (ligne 5440)

**Total** : 9 branches peuvent causer early return.

---

### 1.4 Intention Originale vs Réalité

#### Intention (supposée)

**Meal modes (1-6)** : Durant les 30 premières minutes d'un meal mode actif, forcer un basal élevé pour compenser absorption rapide.  
→ **Devrait permettre SMB en parallèle** (MAIS actuellement return prématuré).

**Post-Meal Hyper Boost (7)** : Boost basal si BG monte post-meal.  
→ **Devrait compléter SMB** (MAIS return prématuré).

**Global Hyper Kicker (8)** : Catch-all pour montées hors meal windows (ex: BG 270).  
→ **Devrait compléter SMB** (MAIS return prématuré).

**fastingTime (9)** : Ajustement basal durant jeûne.  
→ **Peut-être légitime de bloquer SMB** (à confirmer).

#### Réalité

**TOUTES ces branches causent `return rT` AVANT** :
- Calcul SMB final (ligne 5807+)
- `finalizeAndCapSMB()` (ligne 5838+)
- Safety précautions finales

**Conséquence** : Si une de ces conditions est vraie, **SMB = 0 toujours** !

---

### 1.5 Flow Actuel (Bugué)

```
1. Calcul SMB upstream (ligne 5318) → smbToGive
2. When statement (ligne 5383) → rate
3. IF rate != null:
   → set rT.rate
   → RETURN rT (ligne 5450) ← BUG
   → SMB JAMAIS appliqué !
4. ELSE:
   → Continue vers finalizeAndCapSMB (ligne 5838)
   → SMB appliqué normalement
```

---

### 1.6 Point de Gating SMB

**Fonction** : `finalizeAndCapSMB()` (ligne 1462)

**Appels** :
- Ligne 4529 : Meal advisor
- Ligne 4553 : Auto meal intent
- Ligne 4606 : Drift terminator
- Ligne 5838 : **Normal SMB flow** ← Jamais atteint si `rate != null` !

**Conclusion** : Le gating est correct, mais **inaccessible** si early return.

---

## 2. DESIGN DU FIX

### 2.1 Principe : Overlay Pattern

**Au lieu de** :
```kotlin
rate?.let { return rT }  // Bloque SMB
```

**Appliquer** :
```kotlin
// Track basal boost
val basalBoostApplied = rate != null
val basalBoostSource = when {
    rate != null && "Hyper Kicker" in rT.reason -> "HyperKicker"
    rate != null && "Meal" in rT.reason -> "MealMode"
    rate != null && "fasting" in rT.reason -> "Fasting"
    else -> null
}

// Apply basal if boosted
if (basalBoostApplied && rate != null) {
    rT.rate = rate.coerceAtLeast(0.0)
    rT.deliverAt = deliverAt
    rT.duration = 30
    consoleLog.add("BOOST_BASAL_APPLIED source=$basalBoostSource rate=${\"%.2f\".format(rate)}")
    rT.reason.append("BasalBoost: $basalBoostSource ${\"%.2f\".format(rate)}U/h. ")
}
// DON'T RETURN - Continue to SMB calculation
```

---

### 2.2 Modulation SMB (Optionnelle)

Si basal boost élevé, damper SMB légèrement :

```kotlin
var smbDampingFactor = 1.0

if (basalBoostApplied && rate != null && rate > profile_current_basal * 1.3) {
    smbDampingFactor = 0.85  // -15% SMB si basal boosté > 130%
    consoleLog.add("SMB_DAMPING basalBoost=${\"%.2f\".format(rate)} factor=$smbDampingFactor")
}

// Apply damping AVANT finalizeAndCapSMB
val dampedSmb = microBolus * smbDampingFactor
```

**Important** : Cette modulation doit passer par le flow normal, PAS bloquer.

---

### 2.3 Exception : Hard Safety

**LGS / Noise / Stale CGM** peuvent toujours forcer early return :
```kotlin
if (hardSafetyCondition) {
    rT.rate = 0.0
    rT.units = 0.0
    return rT  // OK - Hard safety
}
```

---

## 3. FILES TO MODIFY

### 3.1 DetermineBasalAIMI2.kt

**Ligne 5445-5451** : SUPPRIMER early return

**Ajouter APRÈS ligne 5442** :
```kotlin
// Track basal boost for overlay
val basalBoostApplied = rate != null
val basalBoostSource: String? = when {
    rate != null && rT.reason.contains("Global Hyper Kicker") -> "HyperKicker"
    rate != null && rT.reason.contains("Post-Meal Boost") -> "PostMealBoost"
    rate != null && rT.reason.contains("Meal") -> "MealMode"
    rate != null && rT.reason.contains("fasting") -> "Fasting"
    else -> null
}

// Apply basal boost if applicable (OVERLAY - don't return)
if (basalBoostApplied && rate != null) {
    rT.rate = rate.coerceAtLeast(0.0)
    rT.deliverAt = deliverAt
    rT.duration = 30
    consoleLog.add("BOOST_BASAL_APPLIED source=${basalBoostSource ?: "Unknown"} rate=${\"%.2f\".format(rate)}")
    rT.reason.append("BasalBoost: ${basalBoostSource ?: "?"} ${\"%.2f\".format(rate)}U/h. ")
}
// REMOVED: return rT (continue to SMB logic)
```

---

### 3.2 SMB Damping (APRÈS ligne 5807)

**AVANT** `if (microBolus > 0)` :
```kotlin
// Optional: Dampen SMB if basal heavily boosted
var microBolus = insulinReq
if (basalBoostApplied && rate != null && rate > profile_current_basal * 1.3) {
    val dampFactor = 0.85
    microBolus = microBolus * dampFactor
    consoleLog.add("SMB_DAMPED_BASAL_BOOST ratio=${\"%.2f\".format(rate/profile_current_basal)} factor=$dampFactor")
}
```

---

### 3.3 Logs Ajoutés

**consoleLog** :
- `BOOST_BASAL_APPLIED source=... rate=...`
- `SMB_FLOW_CONTINUES afterBasalBoost=true`
- `SMB_DAMPED_BASAL_BOOST ...` (si damping)

**rT.reason** :
- `BasalBoost: HyperKicker 2.50U/h.`
- Puis normal SMB reason

---

## 4. VALIDATION PLAN

### Test Case 1 : BG 270, Delta +2, Hyper Kicker

**Avant** :
```
rate = adjustBasalForGeneralHyper(...) = 2.5 U/h
return rT → SMB = 0
```

**Après** :
```
basalBoostApplied = true
rT.rate = 2.5 U/h
Continue → SMB calculé → ex: 1.2U (après caps)
```

---

### Test Case 2 : LGS (Hard Safety)

**Avant & Après** : Inchangé
```
if (bg < LGS_threshold) {
    rT.rate = 0.0
    rT.units = 0.0
    return rT  // OK
}
```

---

### Test Case 3 : Normal Flow

**Avant & Après** : Inchangé
```
rate = null → skip basal boost → SMB normal
```

---

## 5. BUILD VALIDATION

```bash
./gradlew :plugins:aps:compileFullDebugKotlin
```

Expected: **BUILD SUCCESSFUL**

---

## 6. AUTRES EARLY RETURNS À VÉRIFIER

**Recherche dans le code** :
```kotlin
return rT$
```

**Identifier** :
- Hard safety (OK)
- Convenience returns (à refactor si bloquent SMB)

---

## STATUS

**Analysis** : ✅ COMPLETE  
**Design** : ✅ READY  
**Implementation** : 🔄 EN COURS

---

**Créé le** : 2025-12-29 19:25  
**Priorité** : 🔴 CRITIQUE
