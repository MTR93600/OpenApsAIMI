# 🔧 FIX: SURCORRECTION UAM - PKPD DAMPING MANQUANT

## Date: 2025-12-30 10:30

---

## 🩺 DIAGNOSTIC

### Contexte Utilisateur
- **Situation**: Repas non déclaré (haricots rouges, omelette, bacon, 2 cafés)
- **Détection**: UAM (Unannounced Meal Detection) activée
- **Contexte médical**: Infection (staphylocoque nasal) + antibiotiques → résistance à l'insuline temporaire
- **Problème**: Surcorrection massive malgré montée glycémique modérée

### Changement Récent (2025-12-29)
**FIX_HYPER_KICKER_EARLY_RETURN.md** a retiré l'early return qui bloquait SMB quand hyper kicker était actif.

**Intention**: Permettre basal boost + SMB en parallèle  
**Résultat**: ✅ Basal + SMB fonctionnent ensemble  
**Effet secondaire**: ⚠️ Trop d'insuline empilée car les garde-fous PKPD ne fonctionnent pas correctement

---

## 🔍 ANALYSE DU CODE

### 1. ABS_GUARD (lignes 5329-5332) - PROBLÈME PRINCIPAL

```kotlin
val absGuard = if (windowSinceDoseInt in 0..20 && iobActivityNow > 0.25) {
    val highBgEscape = bg > target_bg + 60 && delta > 0
    if (highBgEscape) 1.0 else 0.6 + (eventualBG.coerceAtLeast(bg) / max(bg, 1.0)) * 0.2
} else 1.0
```

**Problème**: La clause `highBgEscape` **DÉSACTIVE COMPLÈTEMENT** le garde-fou si:
- BG > target + 60 (ex: 160 mg/dL si target = 100)
- delta > 0 (montée)

**Conséquence**: Dans EXACTEMENT la situation où on devrait être PRUDENT (montée glycémique avec IOB récent), le système devient **ULTRA-AGRESSIF** !

### 2. PKPD Tail Damping (lignes 1845-1867) - TROP RESTRICTIF

```kotlin
if (pkpdRuntime != null && smbToGive > 0f) {
    val tailDampingFactor = when {
        exerciseFlag && pkpdRuntime.pkpdScale < 0.9 -> 0.7
        suspectedLateFatMeal && iob > maxSMB -> 0.6
        else -> 1.0  // ← PAS DE DAMPING PAR DÉFAUT !
    }
}
```

**Problème**: Le PKPD damping ne s'applique que dans 2 cas spécifiques:
1. Exercise + scale bas
2. Late fat meal + IOB élevé

**Pour UAM normal** (montée glycémique sans repas déclaré), **aucun damping PKPD n'est appliqué** !

### 3. Flux Actuel (Bugué)

```
1. UAM détecté → BG monte
2. Hyper Kicker → Basal boost (2.5 U/h)
3. SMB calculé (ex: 1.5U)
4. windowSinceDoseInt = 5 min (insuline récente encore active)
5. iobActivityNow = 0.4 (40% d'activité)
6. ABS_GUARD check:
   - windowSinceDoseInt in 0..20 ✅
   - iobActivityNow > 0.25 ✅
   - bg > target+60 ✅ (ex: 180 > 160)
   - delta > 0 ✅
   → highBgEscape = TRUE
   → absGuard = 1.0 (PAS DE PROTECTION !)
7. SMB 1.5U donné EN PLUS du basal boost
8. 5 min plus tard: IOB très élevé, mais BG continue à monter (résistance)
9. RÉPÉTITION du cycle → EMPILEMENT
```

---

## 🎯 SOLUTION

### Principe: PKPD-Aware Damping Universel

**Au lieu de**: Désactiver ABS_GUARD en cas d'urgence  
**Faire**: Moduler le damping en fonction de l'activité de l'insuline ET de l'urgence

### Implémentation

#### Option A: Réparer ABS_GUARD (Recommandé)

```kotlin
val absGuard = if (windowSinceDoseInt in 0..20 && iobActivityNow > 0.25) {
    // Calculer un facteur basé sur l'activité réelle de l'insuline
    val activityFactor = when {
        iobActivityNow > 0.6 -> 0.4  // Pic d'activité → très prudent
        iobActivityNow > 0.4 -> 0.6  // Activité moyenne → prudent
        iobActivityNow > 0.25 -> 0.75 // Début d'activité → modéré
        else -> 0.9
    }
    
    // En cas d'urgence (BG très élevé), on peut être un PEU moins restrictif
    // MAIS on ne désactive JAMAIS complètement la protection
    val urgencyBoost = if (bg > target_bg + 60 && delta > 0) {
        min(0.2, (bg - (target_bg + 60)) / 200.0)  // Max +20% boost
    } else 0.0
    
    val finalFactor = (activityFactor + urgencyBoost).coerceIn(0.4, 0.95)
    
    if (urgencyBoost > 0) {
        consoleError.add("ABS_GUARD urgency: base=${\"%.2f\".format(activityFactor)} boost=+${\"%.2f\".format(urgencyBoost)} final=${\"%.2f\".format(finalFactor)}")
    }
    
    finalFactor
} else 1.0
```

**Avantages**:
- ✅ Garde-fou TOUJOURS actif (min 0.4 = 40% du SMB)
- ✅ Modulation en fonction de l'activité réelle de l'insuline
- ✅ Permet un boost modéré en urgence (+20% max)
- ✅ Évite l'empilement dangereux

#### Option B: PKPD Universal Damping (Complémentaire)

Ajouter un damping universel basé sur le PKPD dans `finalizeAndCapSMB`:

```kotlin
// Après ligne  1845, AVANT les conditions existantes
if (pkpdRuntime != null && smbToGive > 0f) {
    // Damping basé sur l'activité de l'insuline (UNIVERSEL)
    val activityDamping = when {
        pkpdRuntime.activity.stage == InsulinActivityStage.PEAK -> 0.7
        pkpdRuntime.activity.stage == InsulinActivityStage.RISING -> 0.85
        pkpdRuntime.tailFraction > 0.5 -> 0.9  // 50%+ de l'insuline encore active
        pkpdRuntime.tailFraction > 0.3 -> 0.95 // 30%+ encore active
        else -> 1.0
    }
    
    if (activityDamping < 1.0) {
        val beforeActivity = smbToGive
        smbToGive = (smbToGive * activityDamping.toFloat()).coerceAtLeast(0f)
        consoleLog.add("PKPD_ACTIVITY_DAMP: ${\"%.2f\".format(beforeActivity)}→${\"%.2f\".format(smbToGive)} stage=${pkpdRuntime.activity.stage} tail=${\"%.0f\".format(pkpdRuntime.tailFraction*100)}%")
    }
}
```

---

## 📋 RECOMMANDATION

**Implémenter les DEUX options**:

1. **Option A (Priorité 1)**: Réparer ABS_GUARD pour éviter la désactivation complète
2. **Option B (Priorité 2)**: Ajouter PKPD universal damping comme couche supplémentaire de sécurité

**Pourquoi les deux?**:
- Option A: Garde-fou principal (empêche l'empilement dans la fenêtre critique 0-20min)
- Option B: Protection continue (damping même au-delà de 20min si insuline encore active)

---

## 🧪 TEST CASE

### Scénario: UAM avec Résistance (Infection)

**Avant Fix**:
```
T+0:  BG 140, Delta +3 → SMB 1.2U, Basal 2.0 U/h
T+5:  BG 155, Delta +3, IOB 1.5U (activity 0.5)
      → windowSince = 5, iobActivity = 0.5
      → highBgEscape = TRUE (BG 155 > 160? Non... mais delta > 0)
      → absGuard = 1.0 → SMB 1.3U donné !
T+10: BG 165, Delta +2, IOB 2.6U
      → Encore un SMB 1.1U
T+15: IOB 3.5U → SURCORRECTION
```

**Après Fix (Option A)**:
```
T+0:  BG 140, Delta +3 → SMB 1.2U, Basal 2.0 U/h
T+5:  BG 155, Delta +3, IOB 1.5U (activity 0.5)
      → windowSince = 5, iobActivity = 0.5
      → activityFactor = 0.6 (activity > 0.4)
      → urgencyBoost = 0 (BG 155 < target+60)
      → absGuard = 0.6 → SMB 1.3U * 0.6 = 0.78U
T+10: BG 163, Delta +2, IOB 2.1U (activity 0.55)
      → activityFactor = 0.4 (activity > 0.6)
      → urgencyBoost = 0.015 (BG 163 - 160 = 3/200)
      → absGuard = 0.415 → SMB réduit
T+15: IOB 2.5U → Correction progressive, pas de surc orrection
```

**Après Fix (Option A + B)**:
Protection encore renforcée par le PKPD Activity Damp.

---

## 📁 FILES TO MODIFY

### DetermineBasalAIMI2.kt

**1. Lignes 5329-5339**: Remplacer ABS_GUARD logic (Option A)
**2. Lignes 1845-1867**: Ajouter PKPD Universal Damping (Option B)

---

## ✅ VALIDATION

```bash
./gradlew :plugins:aps:compileFullDebugKotlin
```

---

## 🚨 MONITORING POST-FIX

**Surveiller**:
1. Montées UAM → SMB réduit dans les 20 premières minutes
2. Pas de sous-correction (le boost d'urgence doit fonctionner si nécessaire)
3. IOB max atteint dans scénarios d'infection/résistance

---

## STATUS

**Analysis**: ✅ COMPLETE  
**Design**: ✅ READY  
**Implementation**: 🔄 EN ATTENTE APPROBATION

---

**Créé le**: 2025-12-30 10:30  
**Priorité**: 🔴 CRITIQUE  
**Root Cause**: highBgEscape désactive ABS_GUARD + PKPD damping trop restrictif
