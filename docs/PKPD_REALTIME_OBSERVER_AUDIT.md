# PKPD AUDIT & INNOVATION — REAL-TIME INSULIN OBSERVER

**Date:** 2025-12-18  
**Mission:** Piloter SMB vs TBR basé sur l'activité insulinique réelle (onset/peak/end)  
**Status:** 🔄 EN COURS

---

## 📋 PARTIE A — CARTOGRAPHIE PKPD

### Fichiers PKPD Identifiés

```
plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/
├── pkpd/
│   ├── AdaptivePkPdEstimator.kt      # Estimation DIA/Peak/Tail adaptative
│   ├── PkPdCore.kt                    # Modèles PKPD core (Weibull, etc.)
│   ├── PkPdIntegration.kt             # Intégration PKPD dans le loop
│   ├── InsulinActionProfiler.kt       # Calcul activité insulinique
│   ├── AdvancedPredictionEngine.kt    # Prédictions BG IOB/COB/UAM
│   ├── IsfFusion.kt                   # Fusion ISF (profile/TDD/autosens)
│   ├── SmbDamping.kt                  # Damping SMB selon PKPD
│   └── PkPdCsvLogger.kt               # Logging PKPD
├── smb/
│   └── SmbDampingUsecase.kt           # Use case damping SMB
└── advisor/
    └── PkpdAdvisor.kt                 # Advisor PKPD
```

### Pipeline PKPD (Flow)

```
┌─────────────────────────────────────────────────────────────┐
│ 1. ESTIMATION DIA/PEAK/TAIL                                 │
│    AdaptivePkPdEstimator.estimate()                         │
│    ├─ Input: Profile DIA, recent IOB history               │
│    ├─ Output: diaH, peakMin, tailFrac                       │
│    └─ Méthode: Analyse pente IOB + heuristiques            │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. CALCUL ACTIVITÉ INSULINIQUE                              │
│    InsulinActionProfiler.calculate()                        │
│    ├─ Input: IOB array, diaH, peakMin                       │
│    ├─ Output: iobActivityNow, iobActivityIn30Min            │
│    └─ Méthode: Weibull curve + somme weighted              │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. PRÉDICTION BG                                            │
│    AdvancedPredictionEngine.predict()                       │
│    ├─ Input: BG, IOB, COB, ISF, activity                    │
│    ├─ Output: predBGs[], eventualBG                         │
│    └─ Méthode: Simulation forward avec decay insuline      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. FUSION ISF                                               │
│    IsfFusion.compute()                                      │
│    ├─ Input: Profile ISF, TDD, autosens, PKPD              │
│    ├─ Output: fusedIsf                                      │
│    └─ Méthode: Weighted average avec clamps                │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. DAMPING SMB                                              │
│    SmbDamping.apply()                                       │
│    ├─ Input: Proposed SMB, PKPD runtime, activity          │
│    ├─ Output: Damped SMB                                    │
│    └─ Méthode: Tail damping si exercice/late fat meal      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 6. DÉCISION FINALE (DetermineBasalAIMI2)                    │
│    finalizeAndCapSMB() + basal decision                     │
│    ├─ Caps: maxSMB, maxIOB, absorptionGuard                 │
│    ├─ Output: rT.units (SMB), rT.rate (TBR)                 │
│    └─ PKPD influence: prédictions, damping, ISF             │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔍 ANALYSE DÉTAILLÉE DES FICHIERS

### 1. InsulinActionProfiler.kt

**Rôle:** Calcule l'activité insulinique instantanée basée sur l'IOB actif.

**Fonctions Clés:**
```kotlin
fun calculate(iobArray: Array<IobTotal>, profile: OapsProfileAimi): IobActionProfile {
    // Pour chaque entrée IOB:
    // 1. Calcule age (temps depuis bolus)
    // 2. Applique Weibull curve: getInsulinActivity(age, dia, peak)
    // 3. Somme weighted: iobActivityNow, iobActivityIn30Min
    // 4. Trouve peak time absolu
}

fun getInsulinActivity(timeMinutes: Double, dia: Double, peak: Double): Double {
    // Weibull model: activity = f(time, dia, peak)
    // Forme: montée → pic → décroissance
}
```

**Variables PKPD Produites:**
- `iobTotal`: IOB cumulé (U)
- `peakMinutes`: Temps jusqu'au pic absolu (min)
- `iobActivityNow`: Activité actuelle (somme pondérée)
- `iobActivityIn30Min`: Activité prédite à +30min

**Utilisation Actuelle:**
- ✅ Calculé dans `determine_basal` (ligne ~3487)
- ✅ Utilisé pour `absorptionGuard` (ligne 1471)
- ❌ **NON utilisé** pour piloter SMB vs TBR directement
- ❌ **NON utilisé** pour détecter onset réel

---

### 2. AdaptivePkPdEstimator.kt

**Rôle:** Estime DIA/Peak/Tail adaptatifs basés sur l'historique IOB.

**Méthode:**
```kotlin
fun estimate(profileDia: Double, iobHistory: List<IobTotal>): PkpdEstimate {
    // 1. Analyse slope IOB (montée/plateau/descente)
    // 2. Ajuste DIA selon absorption observée
    // 3. Ajuste Peak selon réactivité
    // 4. Calcule Tail fraction (queue longue vs courte)
}
```

**Variables Produites:**
- `diaH`: DIA ajusté (heures)
- `peakMin`: Peak time ajusté (minutes)
- `tailFrac`: Fraction tail (0-1)

**Utilisation:**
- ✅ Calculé périodiquement
- ⚠️ **Sous-utilisé:** Sert principalement pour logging, pas décision temps réel

---

### 3. AdvancedPredictionEngine.kt

**Rôle:** Prédit BG futur basé sur IOB/COB/UAM.

**Méthode:**
```kotlin
fun predict(bg: Double, iob: Double, cob: Double, isf: Double, ...): PredictionResult {
    // Simulation forward:
    // BG[t+1] = BG[t] - IOB_decay * ISF + COB_absorption - UAM
}
```

**Variables Produites:**
- `predBGs`: Array de BG prédits (5-min intervals)
- `eventualBG`: BG final stabilisé
- `predIOB`, `predCOB`: Trajectoires IOB/COB

**Utilisation:**
- ✅ Prédictions utilisées pour decisions (LGS, targets)
- ⚠️ **Angle mort:** Si prédiction absente, degradation mode 50% (ligne 1476)

---

### 4. IsfFusion.kt

**Rôle:** Fusionne plusieurs sources ISF en une valeur consensuelle.

**Sources:**
- `profileIsf`: ISF du profil utilisateur
- `tddIsf`: ISF basé sur TDD (1800/TDD ou 1500/TDD)
- `autosensIsf`: ISF ajusté par autosens
- `pkpdIsf`: ISF influencé par PKPD (si disponible)

**Méthode:**
```kotlin
fun compute(...): IsfFusionResult {
    // Weighted average avec clamps
    // fusedIsf = w1*profile + w2*tdd + w3*autosens + w4*pkpd
}
```

**Utilisation:**
- ✅ ISF fusionné utilisé pour calculs SMB/TBR
- ✅ Logged pour analyse

---

### 5. SmbDamping.kt

**Rôle:** Applique un damping (réduction) du SMB basé sur PKPD.

**Logique:**
```kotlin
fun apply(smbProposed: Double, pkpdRuntime: PkpdRuntime, exercise: Boolean, lateFat: Boolean): Double {
    // Si exercice + tail élevée: damping pour éviter hypo
    // Si late fat meal + IOB élevée: damping pour éviter stack
    
    val tailDampingFactor = when {
        exercise && pkpdRuntime.pkpdScale < 0.9 -> 0.7
        lateFatMeal && iob > maxSMB -> 0.6
        else -> 1.0
    }
    
    return smbProposed * tailDampingFactor
}
```

**Utilisation:**
- ✅ Appliqué dans `applySafetyPrecautions` (ligne 1624+)
- ⚠️ **Limité:** Uniquement pour exercice/late fat, pas pour onset/peak général

---

### 6. PkPdCsvLogger.kt

**Rôle:** Log toutes les variables PKPD pour analyse offline.

**Colonnes CSV (ordre déduit):**
```
0:  dateStr            (timestamp)
1-3:  bg, delta, iob   (glycémie, delta, iob actuel)
4-6:  diaH, peakMin, tailFrac (PKPD adaptatif)
7-9:  iobActivityNow, iobActivityIn30, peakMinutesAbs (activité)
10-12: profileIsf, tddIsf, fusedIsf (ISF sources)
13-15: predBg, eventualBg, minPredBg (prédictions)
16-18: smbProposedU, smbFinalU, tbrUph (décisions)
19:   reason          (raison décision)
```

**Fichier:** `oapsaimi_pkpd_records.csv` (pas d'en-tête)

---

## ❌ ANGLES MORTS IDENTIFIÉS

### 1. **Activité Insulinique NON Utilisée pour SMB/TBR**
- `iobActivityNow` est calculé mais **jamais** utilisé pour:
  - Réduire SMB quand activité élevée (near peak)
  - Préférer TBR quand activité montante
  - Augmenter SMB quand activité résiduelle faible

### 2. **Onset Réel Non Détecté**
- Le système suppose onset immédiat (Weibull commence à t=0)
- **Réalité:** Onset peut prendre 10-30 minutes
- Pas de corrélation BG slope vs expected insulin drive

### 3. **Prédiction Manquante = Dégradation Brutale**
- Si `predBGs` absent → SMB réduit à 50% (ligne 1476)
- Pas de fallback intelligent basé sur activité insulinique réelle

### 4. **Time-to-Peak/End Non Exploités**
- `peakMinutes` calculé mais pas utilisé pour décision
- Pas de "residual effect" (combien d'action reste)

### 5. **MaxIOB/MaxSMB Paradoxaux**
- MaxIOB peut bloquer SMB même si activité résiduelle faible
- MaxSMB peut autoriser SMB même si activité near peak

---

## ✅ PROCHAINES ÉTAPES

1. **Analyser CSV** → Valider cohérence PKPD terrain
2. **Créer RealTimeInsulinObserver** → Détecter onset/peak/end réels
3. **Intégrer SMB/TBR Throttle** → Piloter décision intelligemment
4. **Compiler & Tester** → Build success + validation

**État:** Cartographie PKPD complète ✅  
**Prochaine section:** Analyse CSV (PARTIE B)
