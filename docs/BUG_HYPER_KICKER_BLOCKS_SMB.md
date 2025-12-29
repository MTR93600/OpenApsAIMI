# 🔥 BUG CONFIRMED - GLOBAL HYPER KICKER BLOCKS SMB

## Date: 2025-12-29 19:16

**ROOT CAUSE** : Ligne 5421-5450 dans `DetermineBasalAIMI2.kt`

---

## Code Problématique

```kotlin
// When statement ligne 5421
when {
    ((bg > target_bg + 40 || delta > 5.0f) && (delta >= 0.3 || shortAvgDelta >= 0.2)) -> {
        val boostedRate = adjustBasalForGeneralHyper(...)
        
        if (boostedRate > profile_current_basal * 1.1) {
            calculateRate(..., "Global Hyper Kicker (Active)", ...)
        } else null
    }
    else -> null
}

// Ligne 5445
rate?.let {
    rT.rate = it
    return rT  // ← BUG: SORT SANS CALCULER SMB !
}
```

---

## Situation Manu (BG 270)

1. **Condition** : `bg (270) > target + 40` → TRUE ✅
2. **Delta positif** → TRUE ✅
3. **Hyper Kicker** → Calcule basal boosté
4. **`rate` non-null** → **EARLY RETURN ligne 5450** ❌
5. **Résultat** : SMB JAMAIS calculé !

---

## FIX IMMÉDIAT

**Option A** : Commenter temporairement Hyper Kicker (ligne 5418-5437)

```kotlin
// TEMP FIX: Disable until proper refactor
/*
((bg > target_bg + 40 || delta > 5.0f) && ...) -> {
    ...
}
*/
```

**Option B** : Ne PAS return, mais laisser continuer vers SMB

```kotlin
// Ligne 5445 - REMPLACER
rate?.let {
    rT.rate = it
    // DON'T RETURN - Let SMB calculation proceed
}
// ENLEVER le return, continuer vers calcul SMB
```

---

## FIX PERMANENT

Refactor : Hyper Kicker doit **augmenter basal**  SANS bloquer SMB.

**Implementation** :
1. Calculer basal boosté
2. Le passer au calcul SMB
3. SMB + Basal boosté **ensemble**

Veux-tu que je l'implémente ?

---

**Status** : ✅ BUG IDENTIFIÉ  
**Priorité** : 🔴 CRITIQUE
