# PKPD REAL-TIME OBSERVER — RÉSUMÉ EXÉCUTIF

**Date:** 2025-12-18 16:30  
**Mission:** Audit PKPD + Innovation Real-Time Insulin Observer  
**Status:** 🎯 **SPÉCIFICATIONS COMPLÈTES**

---

## ✅ TRAVAIL ACCOMPLI

### PARTIE A — Cartographie PKPD ✅

**Fichiers analysés:**
- ✅ `InsulinActionProfiler.kt` → Calcul activité insulinique (Weibull)
- ✅ `AdaptivePkPdEstimator.kt` → DIA/Peak/Tail adaptatifs
- ✅ `AdvancedPredictionEngine.kt` → Prédictions BG
- ✅ `IsfFusion.kt` → Fusion ISF multi-sources
- ✅ `SmbDamping.kt` → Damping SMB exercice/late fat
- ✅ `PkPdCsvLogger.kt` → Logging (20 colonnes identifiées)

**Pipeline PKPD cartographié:**
```
Estimation (DIA/Peak/Tail)
  ↓
Activité Insulinique (iobActivityNow/In30)
  ↓
Prédiction BG (predBGs, eventualBG)
  ↓
Fusion ISF (profile/TDD/autosens/PKPD)
  ↓
Damping SMB (tail damping)
  ↓
Décision Finale (SMB/TBR)
```

**Angles Morts Identifiés:**
1. ❌ `iobActivityNow` calculé mais **NON utilisé** pour piloter SMB vs TBR
2. ❌ **Onset réel** jamais détecté (suppose onset immédiat)
3. ❌ **Time-to-Peak/End** calculés mais ignorés pour décision
4. ❌ **Residual Effect** (aire restante) pas exploité
5. ❌ Prédiction absente → Dégradation brutale 50% (pas de fallback intelligent)

---

### PARTIE B — Schema CSV ✅

**Format `oapsaimi_pkpd_records.csv` (20 colonnes, pas d'en-tête):**

```
0:  dateStr           # Timestamp
1:  bg                # BG actuel (mg/dL)
2:  delta             # Delta BG (mg/dL/5min)
3:  iob               # IOB actuel (U)
4:  diaH              # DIA adaptatif (h)
5:  peakMin           # Peak time adaptatif (min)
6:  tailFrac          # Tail fraction (0-1)
7:  iobActivityNow    # Activité insulinique actuelle
8:  iobActivityIn30   # Activité prédite +30min
9:  peakMinutesAbs    # Temps jusqu'au pic (min)
10: profileIsf        # ISF profil
11: tddIsf            # ISF TDD
12: fusedIsf          # ISF fusionné
13: predBg            # BG prédit principal
14: eventualBg        # BG eventual
15: minPredBg         # BG prédit minimum
16: smbProposedU      # SMB proposé (U)
17: smbFinalU         # SMB final (U)
18: tbrUph            # TBR (U/h)
19: reason            # Raison décision
```

**Validation Recommandée:**
- Vérifier distributions: `diaH` (3-7h), `peakMin` (45-90min), `tailFrac` (0.2-0.5)
- Détecter outliers: `tailFrac >1.0`, `smbFinal quand activity >0.7`

---

### PARTIE C — Architecture RealTimeInsulinObserver ✅

**Nouveau module créé (spécifié):**

```kotlin
class RealTimeInsulinObserver {
    fun update(...): InsulinActionState {
        // 1. BG slope lissé (EMA)
        // 2. Corrélation slope vs expected insulin drive
        // 3. Détection onset (corrélation stable >0.5 pendant 15min)
        // 4. Stage detection (RISING/PEAK/FALLING/TAIL)
        // 5. Time-to-peak/end
        // 6. Residual effect (aire restante)
    }
}

data class InsulinActionState(
    val onsetConfirmed: Boolean,
    val activityStage: ActivityStage,  // RISING/PEAK/FALLING/TAIL
    val activityNow: Double,
    val timeToPeakMin: Int,
    val timeToEndMin: Int,
    val residualEffect: Double,
    val reason: String
)
```

**Algorithmes Clés:**
1. **Onset Detection:** Corrélation BG slope vs expected insulin drive (neutralisation COB)
2. **Stage Detection:** Basé sur `timeToPeak`, `activityNow`
3. **Residual Calculation:** Approximation aire restante Weibull

---

### PARTIE D — Throttle SMB/TBR ✅

**Logique de Décision:**

| Situation | SMB Factor | Interval Add | Prefer TBR | Rationale |
|-----------|------------|--------------|------------|-----------|
| **Onset non confirmé + BG↑** | 0.6 | +3 min | ✅ | Attendre onset, TBR maintient pression |
| **Near Peak (activity>0.7)** | 0.3 | +5 min | ✅ | Risque stacking élevé |
| **Tail + résiduel<0.3 + BG↑** | 1.0 | 0 min | ❌ | Fin d'action, SMB safe |
| **Falling (post-peak)** | 0.7 | +2 min | ❌ | Décroissance normale |

**Intégration dans `finalizeAndCapSMB`:**
```kotlin
// Après ligne 1479
if (insulinObserver != null && !isExplicitUserAction) {
    val throttle = computeThrottle(insulinObserver.getState(), delta, bgRising)
    gatedUnits = (gatedUnits * throttle.smbFactor).toFloat()
    
    consoleLog.add("PKPD_THROTTLE smbFactor=${throttle.smbFactor} ...")
    consoleLog.add("PKPD_OBS ${actionState.reason}")
}
```

**Garantie:** Jamais de blocage total (smbFactor min = 0.2, jamais 0.0)

---

## 📊 LOGS ATTENDUS

### Logs Normaux
```
PKPD_OBS onset=✓ stage=FALLING corr=0.78 resid=0.45
PKPD_THROTTLE smbFactor=0.7 intervalAdd=2 preferTbr=false reason=Falling stage
SMB_CAP: Proposed=2.5 Allowed=1.75
```

### Logs Near Peak (throttle actif)
```
PKPD_OBS onset=✓ stage=PEAK corr=0.85 resid=0.70
PKPD_THROTTLE smbFactor=0.3 intervalAdd=5 preferTbr=true reason=Near peak / High activity
💡 TBR recommended (Near peak / High activity → SMB throttled)
SMB_CAP: Proposed=2.5 Allowed=0.75
```

---

## 🎯 PROCHAINES ÉTAPES (Implémentation Requise)

### Phase 1: Core Observer (3-4 heures)
- [ ] Créer `/pkpd/RealTimeInsulinObserver.kt`
- [ ] Créer `/pkpd/InsulinActionState.kt`
- [ ] Implémenter méthodes:
  - `update()`
  - `computeSmoothedSlope()`
  - `detectOnset()`
  - `detectActivityStage()`
  - `computeResidualEffect()`

### Phase 2: Throttle Logic (1-2 heures)
- [ ] Créer `/pkpd/SmbTbrThrottle.kt`
- [ ] Implémenter `computeThrottle()`
- [ ] Tests scénarios (onset, peak, tail)

### Phase 3: Intégration (2 heures)
- [ ] Instancier observer dans `DetermineBasalAIMI2` (membre classe)
- [ ] Appeler `observer.update()` dans `determine_basal` (après ligne 3500)
- [ ] Appliquer throttle dans `finalizeAndCapSMB` (après ligne 1479)
- [ ] Ajouter logs `PKPD_OBS` et `PKPD_THROTTLE`

### Phase 4: Build & Validation (1 heure)
- [ ] `./gradlew :plugins:aps:compileFullDebugKotlin`
- [ ] Corriger erreurs compilation
- [ ] Tester logs en conditions réelles
- [ ] Ajuster thresholds si nécessaire

---

## 📝 FICHIERS LIVRÉS

### Documentation
1. ✅ `PKPD_REALTIME_OBSERVER_AUDIT.md` → Cartographie PKPD complète
2. ✅ `PKPD_REALTIME_IMPL_SPEC.md` → Architecture & spécification détaillée
3. ✅ `PKPD_REALTIME_EXEC_SUMMARY.md` → Ce résumé exécutif

### Code (À Implémenter)
- ⏳ `pkpd/RealTimeInsulinObserver.kt` (classe principale)
- ⏳ `pkpd/InsulinActionState.kt` (data classes)
- ⏳ `pkpd/SmbTbrThrottle.kt` (logique throttle)
- ⏳ Modifications `DetermineBasalAIMI2.kt` (intégration)

---

## ⚠️ POINTS CRITIQUES

### Sécurité
✅ **Aucun blocage brutal** : Le throttle est un multiplicateur (0.2-1.0)  
✅ **Modes repas bypassent** : `isExplicitUserAction = true` → pas de throttle  
✅ **LGS/Safety hard préservés** : Throttle appliqué APRÈS safety, pas avant  

### Performance
✅ **Calcul léger** : EMA, corrélation simple, pas de ML lourde  
✅ **Pas de nouvelle API** : Utilise données PKPD existantes  
✅ **Logging maîtrisé** : 2 lignes max par tick  

### Validation
⏳ **Tests unitaires à créer** : Onset detection, stage detection  
⏳ **Validation terrain** : Analyser CSV post-implémentation  
⏳ **Ajustement thresholds** : Basé sur feedback utilisateur  

---

## 🎯 CONCLUSION

**État Actuel:** Spécifications complètes ✅  
**Temps Estimation:** 6-9 heures implémentation complète  
**Complexité:** MOYENNE-ÉLEVÉE  
**Risque:** FAIBLE (soft throttle, pas de blocage)  

**Recommandation:** Implémenter Phase 1 (Core Observer) en priorité, tester isolément, puis intégrer progressivement.

**Prochain commit:** Créer `RealTimeInsulinObserver.kt` avec onset detection de base et logs de validation.

---

**Contact:** Spécifications disponibles dans `/docs/PKPD_REALTIME_*.md`  
**Support:** Code skeleton fourni, implémentation requise pour build success
