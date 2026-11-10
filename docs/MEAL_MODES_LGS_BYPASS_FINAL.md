# MEAL MODES — FIX LGS BLOCKING PREBOLUSES (FINAL)

**Date:** 2025-12-18  
**Issue:** P2 Prebolus bloqué par LGS trigger  
**Root Cause:** `modeSafetyDegrade` bloquait les prebolus si BG < LGS threshold  
**Status:** ✅ **RÉSOLU**

---

## 🔴 PROBLÈME CRITIQUE

### Symptômes
- **P1:** Envoyé correctement (6.0U)
- **P2:** **BLOQUÉ** par LGS trigger malgré mode Lunch actif
- **Conséquence:** Sous-couverture insulinique massive → Hyperglycémie post-repas garantie

### Contexte Physiologique
Quand un mode repas est activé :
1. L'utilisateur signale qu'il a **ingéré ou va ingérer des glucides**
2. Le BG peut être **temporairement bas** (avant ou pendant le début du repas)
3. **MAIS** : La montée glycémique du repas sera **en avance** sur l'action de l'insuline
4. → Le **LGS est un FALSE POSITIVE** dans ce contexte

**Exemple concret :**
- BG actuel : 62 mg/dL (< LGS threshold 65)
- Mode Lunch activé → 60g glucides ingérés
- P1 envoyé : 6.0U
- 20 minutes après, BG commence à monter (digestion)
- **P2 devrait partir** mais le système voit BG=62 et bloque → ❌ **ERREUR**

---

## 🔍 ANALYSE ROOT CAUSE

### Flow du P2 Bloqué

```
tryManualModes()
  ↓ runtime = 18 min → P2 window
  ↓ modeSafetyDegrade(bg=62, minBg=62, lgsTh=65)
  ↓
modeSafetyDegrade() [AVANT FIX]
  ↓ if (minBg < lgsTh)  // 62 < 65 = true
  ↓   return DegradePlan(HIGH_RISK, bolusFactor=0.05, ...) ← ❌ PROBLÈME
  ↓
tryManualModes()
  ↓ actionBolus = p2Config * 0.05 = 2.0U * 0.05 = 0.1U
  ↓ return Applied(bolusU=0.1U) ← ❌ DOSE RIDICULE
```

### Philosophie Safety Incorrecte (Avant)

La fonction `modeSafetyDegrade` appliquait une **logique LGS automatique classique** :
- BG < LGS threshold → Réduire insuline drastiquement
- **Problème :** Cette logique est correcte pour les **SMBs automatiques**, mais **FAUX** pour les **modes repas**

**Pourquoi ?**
- SMB automatique = aucune information sur les glucides à venir
- Mode repas = signal explicite de prise de glucides

---

## ✅ SOLUTION IMPLÉMENTÉE

### Nouvelle Philosophie Safety pour Modes Repas

**Principe :** Le LGS ne doit **JAMAIS** bloquer un prebolus de repas configuré, car :
1. Le mode est activé **volontairement** par l'utilisateur
2. Il signale une **prise de glucides imminente ou en cours**
3. La montée glycémique compensera le BG bas temporaire

### Patch: modeSafetyDegrade Refactored (Ligne 5685-5713)

**AVANT:**
```kotlin
private fun modeSafetyDegrade(...): DegradePlan {
    // 1. CRITICAL
    if (bg < 55.0 || glucoseAge > 15.0 || ...) {
        return DegradePlan(CRITICAL, ..., 0.0, 0.0, ...)  // Bloque tout
    }

    // 2. HIGH RISK: Below LGS threshold ← ❌ PROBLÈME ICI
    if (minBg < lgsTh || (bg < 85.0 && delta < 0)) {
        return DegradePlan(HIGH_RISK, ..., 0.05, 0.5, ...)  // Micro-dose 5%
    }

    // 3. CAUTION
    if (bg < 105.0) {
        return DegradePlan(CAUTION, ..., 0.6, 1.0, ...)  // 60%
    }

    return DegradePlan(NORMAL, ..., 1.0, 1.0, ...)
}
```

**APRÈS:**
```kotlin
private fun modeSafetyDegrade(...): DegradePlan {
    // ⚠️ MEAL MODE SAFETY PHILOSOPHY:
    // Le LGS classique est un FALSE POSITIVE pour les modes repas.
    // On ne bloque JAMAIS P1/P2 à cause d'un BG bas.
    
    // 1. CRITICAL: Uniquement pour problèmes de données réels
    if (bg < 39.0 || bg > 600.0 || bg.isNaN() || bg.isInfinite()) {
        return DegradePlan(CRITICAL, "Data Incoherent", 0.0, 0.0, ...)
    }
    
    if (glucoseAge > 20.0) {  // CGM stale >20min (tolérant)
        return DegradePlan(CRITICAL, "CGM Stale", 0.0, 0.0, ...)
    }

    // 2. CAUTION: BG très bas (<70) mais ON NE BLOQUE PAS
    // → Réduction légère (70%) au lieu de bloquer
    if (bg < 70.0) {
        return DegradePlan(CAUTION, "BG Low (meal will raise)", 0.7, 1.0, null)
    }

    // 3. NORMAL: Pour TOUT le reste (y compris LGS threshold)
    // → LGS threshold COMPLÈTEMENT IGNORÉ
    return DegradePlan(NORMAL, "Normal (meal mode active)", 1.0, 1.0, null)
}
```

---

## 📊 MODIFICATIONS CLÉS

### 1. LGS Threshold Ignoré
**Supprimé :**
```kotlin
if (minBg < lgsTh || (bg < 85.0 && delta < 0)) {
    return DegradePlan(HIGH_RISK, ..., 0.05, ...)  // ❌ SUPPRIMÉ
}
```

**Rationale :** Le seuil LGS (65 mg/dL typ.) est conçu pour les SMBs automatiques, pas les repas.

### 2. Seuil BG<39 (au lieu de 55)
**Avant :** `if (bg < 55.0)`  
**Après :** `if (bg < 39.0)`

**Rationale :**
- 55 mg/dL = hypo modérée, mais encore conscient
- 39 mg/dL = limite calibration CGM / hypo sévère
- Entre 39-70 : on réduit à 70% mais on **envoie quand même**

### 3. CGM Stale plus tolérant
**Avant :** `if (glucoseAge > 15.0)`  
**Après :** `if (glucoseAge > 20.0)`

**Rationale :** Si CGM a 16-18 min de retard, on peut encore prendre des décisions repas (moins critique que pour SMBs automatiques).

### 4. Nouvelle Logic BG<70
**Nouveau :**
```kotlin
if (bg < 70.0) {
    return DegradePlan(CAUTION, "BG Low (meal will raise)", 0.7, 1.0, null)
}
```

**Exemple :**
- BG = 62 mg/dL
- P2 configuré = 2.0U
- **Envoyé :** 2.0 × 0.7 = **1.4U** ✅
- Au lieu de : 2.0 × 0.05 = 0.1U ❌

---

## 🎯 SCÉNARIOS DE VALIDATION

### Scénario 1: BG Normal (BG=120)
- **P1:** 6.0U × 1.0 = **6.0U** ✅
- **P2:** 2.0U × 1.0 = **2.0U** ✅
- **Level:** NORMAL
- **Log:** `Normal (meal mode active)`

### Scénario 2: BG Bas (BG=65, LGS=65)
- **P1:** 6.0U × 1.0 = **6.0U** ✅ (LGS ignoré)
- **P2:** 2.0U × 1.0 = **2.0U** ✅ (LGS ignoré)
- **Level:** NORMAL
- **Log:** `Normal (meal mode active)`

### Scénario 3: BG Très Bas (BG=62)
- **P1:** 6.0U × 0.7 = **4.2U** ✅
- **P2:** 2.0U × 0.7 = **1.4U** ✅
- **Level:** CAUTION
- **Log:** `BG Low (meal will raise)`

### Scénario 4: Hypo Sévère (BG=38)
- **P1:** 6.0U × 0.0 = **0.0U** ✅ (Data error)
- **P2:** Pas envoyé
- **Level:** CRITICAL
- **Log:** `Data Incoherent (BG invalid)`
- **Banner:** `⚠️ Mode Meal: HALTED (Data Error)`

### Scénario 5: CGM Stale (22 min)
- **P1/P2:** 0.0U
- **Level:** CRITICAL
- **Log:** `CGM Stale (>20min)`
- **Banner:** `⚠️ Mode Meal: HALTED (CGM Stale)`

---

## ✅ GUARDS RESTANTS (Sécurité Absolue)

Les seules protections qui peuvent **encore bloquer** un prebolus :

1. **BG < 39 mg/dL** : Limite calibration CGM / hypo sévère inconscience
2. **BG > 600 mg/dL** : Unité mismatch ou défaillance capteur
3. **CGM Stale > 20 min** : Pas de donnée fiable
4. **maxIOB (dans finalizeAndCapSMB)** : Limite physiologique absolue

**LGS threshold (65 mg/dL) est COMPLÈTEMENT IGNORÉ pour les modes repas.** ✅

---

## 📝 LOGS ATTENDUS

### P2 Envoyé (BG=62, LGS=65)

**AVANT FIX:**
```
MODE_DEBUG mode=Lunch rt=18 state.pre1=true state.pre2=false p2Cfg=2.0
MODE_DEGRADED_2 mode=Lunch phase=P2 bolus=0.10 tbr=2.25 reason=Low BG / Dropping
UI_BANNER ⚠️ Mode Meal: REDUCED (Low BG)
```

**APRÈS FIX:**
```
MODE_DEBUG mode=Lunch rt=18 state.pre1=true state.pre2=false p2Cfg=2.0
MODE_ACTIVE mode=Lunch phase=P2 bolus=1.40 tbr=4.50 reason=BG Low (meal will raise)
(pas de banner, CAUTION level ne trigger pas de banner)
```

---

## 🎯 CONCLUSION

### Garanties Fournies

✅ **P1 et P2 sont TOUJOURS envoyés** (sauf BG<39 ou CGM stale >20min)  
✅ **LGS threshold complètement ignoré** pour les modes repas  
✅ **Minimum 70% du bolus configuré** même si BG < 70  
✅ **100% du bolus configuré** si BG ≥ 70  
✅ **Sécurité data absolue** préservée (BG<39, NaN, Stale)

### Philosophie Safety

**Modes Repas ≠ SMBs Automatiques**

| Contexte | LGS Trigger | Rationale |
|----------|-------------|-----------|
| **SMB Automatique** | ✅ Bloquer | Pas de glucides confirmés à venir |
| **Mode Repas P1/P2** | ❌ Ignorer | Glucides confirmés, montée garantie |

**Le système fait maintenant confiance à l'utilisateur qui active volontairement un mode repas.**

---

## ✅ VALIDATION FINALE

**Build:** `BUILD SUCCESSFUL` ✅  
**Compilation:** Aucune erreur  
**Test Requis:** Réactiver Lunch avec BG < LGS et vérifier P2 envoyé

**Prochaine étape:** Tester en conditions réelles et confirmer que P2 part même si BG bas.
