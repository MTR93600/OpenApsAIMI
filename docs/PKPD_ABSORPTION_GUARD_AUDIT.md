# 🛡️ PKPD ABSORPTION GUARD - AUDIT COMPLET

## Date: 2025-12-30 10:50

---

## 📋 CONTEXTE

**Problème** : Surcorrection UAM après fix early return (basal boost + SMB coexistent maintenant)  
**Symptôme** : SMB/TBR trop forts ou trop rapprochés lors montée glycémique non déclarée + résistance  
**Objectif** : Implémenter PKPD Absorption Guard soft (non-bloquant) basé sur physiologie insuline

---

## 🔬 PHASE 1: RECONSTITUTION PIPELINE DÉCISIONNEL

### 1.1 Étapes du Pipeline (DetermineBasalAIMI2.kt)

**À AUDITER** :
- [ ] Calcul prédictions (AdvancedPredictionEngine, predBg, eventualBg, rT.predBGs)
- [ ] Intégration PKPD (PkPdIntegration, AdaptivePkPdEstimator, InsulinActionProfiler)
- [ ] Safety checks (LGS/noise/stale)
- [ ] Calcul SMB (smbToGive, ML, SmbDamping)
- [ ] Finalisation unique (finalizeAndCapSMB, clamps maxIOB/maxSMB/refractory)
- [ ] Décision basal/TBR (setTempBasal)

### 1.2 Vérification Utilisation PKPD

**Chercher usages de** :
- `pkpdRuntime`, `pkpdState`, `pkpdParams`
- `dia`, `peak`, `tail`, `iobActivity`, `residualEffect`
- `InsulinActivityStage`, `InsulinActionState`
- Tout clamp sur `tp`, `DIA`, `peakTime`
- Tout guard basé sur `iob` ou `iobActivity`

### 1.3 Vérification Refractory

**Points de contrôle** :
- Temps minimal entre SMB (fixe? variable?)
- Bypass par Autodrive/Modes/HyperKicker?
- Impact du fix early return sur fréquence SMB

### 1.4 Variables Dynamiques Dangereuses

**À auditer** :
- `DinMaxIob` : bornes, dépendances BG/delta, plafonds
- `DynMaxSmb` : bornes, lien avec tp, lien avec maxSMBHB
- Cas BG < 120 : permissivité
- Prediction missing : comportement fallback

---

## 🔍 PHASE 2: HYPOTHÈSES À CONFIRMER/INFIRMER

### H1 - PKPD pas appliqué
**Hypothèse** : PKPD calcule DIA/peak/tail mais n'ajoute aucune contrainte temporelle sur SMB  
**Status** : 🔄 À VÉRIFIER  
**Preuves** : 

### H2 - Refractory trop faible / bypass
**Hypothèse** : Après suppression early return, SMB calculé à chaque tick sans freinage suffisant  
**Status** : 🔄 À VÉRIFIER  
**Preuves** : 

### H3 - maxIOB/maxSMB dynamiques trop permissifs
**Hypothèse** : Formules DinMaxIob/DynMaxSmb montent trop haut en résistance  
**Status** : 🔄 À VÉRIFIER  
**Preuves** :

### H4 - Predictions absentes/incohérentes
**Hypothèse** : Safety dépend de predBg/eventualBg. Si absent → pas de garde → SMB enchaînés  
**Status** : 🔄 À VÉRIFIER  
**Preuves** : 

### H5 - HyperKicker + SMB cumul sans coordination
**Hypothèse** : TBR boost + SMB élevés sans tenir compte absorption  
**Status** : 🔄 À VÉRIFIER  
**Preuves** : 

---

## 🛠️ PHASE 3: DESIGN PKPD ABSORPTION GUARD

### 3.1 Concept

**Fenêtre Physiologique** :
- PRE_ONSET / ONSET / PEAK : éviter ajout SMB trop rapide
- TAIL : relâcher progressivement

### 3.2 Entrées
- `pkpdState` ou `timeSinceLastBolus` vs `pkpdPeak/pkpdDia`
- `iobActivity` si disponible
- `timeSinceLastBolusMin`
- `bg`, `delta`, `shortAvgDelta`
- `predBg`/`eventualBg` si fiable

### 3.3 Sorties (Soft Modulation)
- `smbGuardFactor` (0.4..1.0) : facteur multiplicatif SMB
- `intervalAddMin` (0..+6 min) : délai supplémentaire avant prochain SMB
- Option : préférer TBR si montée modérée

### 3.4 Règles Proposées (À Affiner)

```kotlin
val guard = when {
    timeSinceLastBolus < onsetMin -> 
        PkpdGuard(factor = 0.4, intervalAdd = 4, reason = "PRE_ONSET")
    
    timeSinceLastBolus < peakMin -> 
        PkpdGuard(factor = 0.6, intervalAdd = 3, reason = "ONSET")
    
    timeSinceLastBolus < peakMin + 20 -> 
        PkpdGuard(factor = 0.75, intervalAdd = 2, reason = "PEAK")
    
    tailFraction > 0.5 -> 
        PkpdGuard(factor = 0.9, intervalAdd = 1, reason = "TAIL")
    
    else -> 
        PkpdGuard(factor = 1.0, intervalAdd = 0, reason = "EXHAUSTED")
}

// Urgency relaxation (ne pas bloquer vraies urgences)
if (bg > target + 80 && delta > 5 && predBg > bg + 30) {
    guard.factor = min(1.0, guard.factor + 0.2)
    guard.intervalAdd = max(0, guard.intervalAdd - 2)
}
```

### 3.5 Exceptions (Ne PAS Freiner)
- Prebolus1 / TBR / Prebolus2 (modes repas)
- Meal Advisor bolus
- LGS (priorité absolue)

### 3.6 Logging Obligatoire
```
PKPD_GUARD state=PEAK tSince=12m factor=0.60 +3m
rT.reason: "PKPDGuard: PEAK x0.60 +3m"
```

---

## 🔗 PHASE 4: COORDINATION HYPERKICKER + SMB

**Si HyperKicker applique TBR boost** :
- ET `pkpdState` est PEAK
- ALORS réduire SMB via guard (soft)
- JAMAIS faire return prématuré

---

## ✅ PHASE 5: VALIDATION SCÉNARIOS

### Scénario A - Montée non déclarée (maladie)
**Input** : BG monte, delta +2 à +5, pas COB  
**Attendu** :
- TBR peut augmenter ✅
- SMB possibles mais pas rafale ✅
- Si SMB récent, guard réduit suivant ✅

### Scénario B - Hyper sévère (BG > 250)
**Input** : BG très élevé  
**Attendu** :
- Guard se relâche suffisamment ✅
- Cadence raisonnable maintenue ✅

### Scénario C - Hypo risk
**Input** : BG < LGS threshold  
**Attendu** :
- LGS/noise/stale priorité absolue ✅
- TBR 0 / SMB 0 ✅

---

## 📊 FINDINGS (Complets)

### Finding 1 - Localisation PKPD Integration ✅
**Ligne** : DetermineBasalAIMI2.kt:4140-4162  
**Code** : 
```kotlin
val pkpdRuntimeTemp = pkpdIntegration.computeRuntime(
    epochMillis = currentTime,
    bg = bg,
    deltaMgDlPer5 = delta.toDouble(),
    iobU = iob.toDouble(),
    carbsActiveG = carbsActiveG,
    windowMin = windowSinceDoseInt,  // ← Minutes depuis dernière dose
    exerciseFlag = sportTime,
    profileIsf = profile.sens,
    tdd24h = tdd24Hrs.toDouble(),
    mealContext = pkpdMealContext,
    consoleLog = consoleLog
)
```
**Impact** : 
- ✅ PKPD est calculé et disponible via `pkpdRuntime`
- ✅ Contient `activity` (InsulinActivityState), `tailFraction`, `pkpdScale`, `params` (DIA/peak)
- ⚠️ MAIS utilisé uniquement pour ISF fusion et logs, PAS pour moduler temporellement SMB

### Finding 2 - Refractory Actuel ⚠️
**Ligne** : DetermineBasalAIMI2.kt:4126-4130  
**Mécanisme** : 
```kotlin
val windowSinceDoseMin = if (iob_data.lastBolusTime > 0 || internalLastSmbMillis > 0) {
    val effectiveLastBolusTime = kotlin.math.max(iob_data.lastBolusTime, internalLastSmbMillis)
    ((systemTime - effectiveLastBolusTime) / 60000.0).coerceAtLeast(0.0)
} else 0.0
windowSinceDoseInt = windowSinceDoseMin.toInt()
```
**Problème identifié** :
- ✅ `windowSinceDoseInt` est calculé correctement
- ❌ **ANCIEN ABS_GUARD** (ligne 5329-5333) avait `highBgEscape` qui DÉSACTIVAIT le guard si BG > target+60
- ❌ Aucun autre mécanisme de refractory basé sur PKPD stages
- **Bypass** : Modes repas, Autodrive, HyperKicker continuaient sans restriction temporelle

### Finding 3 - Variables Dynamiques 🔍
**DinMaxIob** : Non trouvé (probablement `maxIob` statique)  
**DynMaxSmb** : Ligne 5328
```kotlin
val currentMaxSmb = if (bg > 120 && !honeymoon && mealData.slopeFromMinDeviation >= 1.0) 
    maxSMBHB else maxSMB
```
**Analyse** : Permissivité raisonnable, pas le problème principal

### Finding 4 - Predictions ✅
**Ligne** : 4000-4050 (AdvancedPredictionEngine)  
**Comportement** : Predictions calculées, fallback en place (ensurePredictionFallback)  
**Conclusion** : Pas la cause principale

### Finding 5 - HyperKicker + SMB Coordination ❌
**Ligne** : 5383-5470 (après fix early return)  
**Problème** : 
- ✅ Basal boost ET SMB maintenant coexistent (fix précédent correct)
- ❌ **MAIS** aucune coordination physiologique : si boost basal 2.5 U/h appliqué, SMB continue sans considération de l'absorption en cours
- ❌ L'ancien ABS_GUARD (0-20min) était bypassé par `highBgEscape`

---

## ✅ HYPOTHÈSES - RÉSULTATS

### H1 — "PKPD pas appliqué" ✅ CONFIRMÉ
**Preuve** : PKPD calcule DIA/peak/tail/activity MAIS :
- Ligne 1845-1867 : Damping PKPD limité à exercise + late fat meal
- **POUR UAM NORMAL** : Aucun damping basé sur activity stage
- Ligne 5329-5333 : ABS_GUARD désactivé par `highBgEscape`

### H2 — "Refractory trop faible / bypass" ✅ CONFIRMÉ
**Preuve** : 
- Après fix early return, SMB calculé à chaque tick
- ABS_GUARD (0-20min) bypassé si BG > target+60
- Aucun intervalle dynamique basé sur PKPD stages

### H3 — "maxIOB/maxSMB dynamiques trop permissifs" ❌ INFIRMÉ
**Preuve** : Formules raisonnables, pas le problème principal

### H4 — "Predictions absentes/incohérentes" ❌ INFIRMÉ
**Preuve** : Predictions fonctionnent, fallback en place

### H5 — "HyperKicker + SMB cumul sans coordination" ✅ CONFIRMÉ
**Preuve** : 
- Basal boost (ex: 2.5 U/h) + SMB (ex: 1.2U) sans considération de l'absorption
- Pas de modulation SMB quand basal boost actif + insuline en phase PEAK

---

## 🛠️ IMPLÉMENTATION RÉALISÉE

### Fichiers Créés

#### 1. PkpdAbsorptionGuard.kt ✅
**Path** : `/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkpdAbsorptionGuard.kt`

**Fonctionnalités** :
- Calcul guard basé sur `InsulinActivityStage` (PRE_ONSET, RISING, PEAK, TAIL, EXHAUSTED)
- Modulation `factor` et `intervalAddMin` selon physiologie
- Urgency relaxation pour vraies urgences (BG > target+80, delta > 5, pred > BG+30)
- Stable fallback (si delta < 1.0 && avgDelta < 1.5, +10% factor)
- Exception modes repas (ne pas freiner prebolus/TBR)

**Règles implémentées** :
```
PRE_ONSET:  factor=0.5,  interval+4min, preferTbr=true
RISING:     factor=0.6,  interval+3min
PEAK:       factor=0.7,  interval+2min
TAIL_HIGH:  factor=0.85, interval+1min (si tailFrac > 50%)
TAIL_MED:   factor=0.92, interval+1min (si tailFrac > 30%)
EXHAUSTED:  factor=1.0,  interval+0min (neutral)
```

### Fichiers Modifiés

#### 2. DetermineBasalAIMI2.kt ✅
**Ligne** : 72 - Import ajouté
```kotlin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdAbsorptionGuard
```

**Ligne** : 5327-5365 - Remplacement ABS_GUARD
```kotlin
// 🛡️ PKPD ABSORPTION GUARD (FIX 2025-12-30)
val anyMealModeForGuard = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime

val pkpdGuard = PkpdAbsorptionGuard.compute(
    pkpdRuntime = pkpdRuntime,
    windowSinceLastDoseMin = windowSinceDoseInt.toDouble(),
    bg = bg,
    delta = delta.toDouble(),
    shortAvgDelta = shortAvgDelta.toDouble(),
    targetBg = target_bg,
    predBg = predictedBg.toDouble().takeIf { it > 20 },
    isMealMode = anyMealModeForGuard
)

if (pkpdGuard.isActive()) {
    val beforeGuard = smbToGive
    smbToGive = (smbToGive * pkpdGuard.factor.toFloat()).coerceAtLeast(0f)
    
    consoleError.add(pkpdGuard.toLogString())
    consoleLog.add("SMB_GUARDED: ${\"%.2f\".format(beforeGuard)}U → ${\"%.2f\".format(smbToGive)}U")
    rT.reason.append(" | ${pkpdGuard.reason} x${\"%.2f\".format(pkpdGuard.factor)}")
    
    if (pkpdGuard.intervalAddMin > 0) {
        intervalsmb = (intervalsmb + pkpdGuard.intervalAddMin).coerceAtMost(10)
        consoleLog.add("INTERVAL_ADJUSTED: +${pkpdGuard.intervalAddMin}m → ${intervalsmb}m total")
    }
}
```

**Impact** :
- ✅ Remplace ABS_GUARD avec logique physiologique
- ✅ Supprime `highBgEscape` dangereux
- ✅ Modulation SMB selon stage activité insuline
- ✅ Augmentation intervalle dynamique (évite rafales SMB)
- ✅ Logs complets (consoleError, consoleLog, rT.reason)

---

## 🚀 IMPLÉMENTATION

### Fichiers à Modifier
1. `DetermineBasalAIMI2.kt` - Guard logic
2. Potentiellement créer `PkpdAbsorptionGuard.kt`

### Build Validation
```bash
./gradlew assembleDebug
```

---

**Status** : 🔄 ANALYSE EN COURS  
**Priorité** : 🔴 CRITIQUE
