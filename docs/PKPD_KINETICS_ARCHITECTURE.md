# PKPD — Architecture cinétique insuline (DIA / peakTime)

**Statut :** analyse architecturale + validation terrain (package AIMI)  
**Date :** 2026-07-12  
**Portée :** modularité, propagation DIA/peak appris, intégration physio vs arbre causal, pertinence produit  
**Build de référence :** `dev_OAPSAIMI` (post-merge `milos/dev`)

> **Mise à jour 2026-07-13 — le refactor a atterri.** L'analyse ci-dessous décrivait en partie la *cible* ; depuis,
> plusieurs éléments « cible » sont **en production** (commits `9e8557c61b`, `305364951a`, `8b4acdf4e1`, + C2 working tree) :
> - **`InsulinKineticsAuthority`** existe : façade unique `resolve()` → `InsulinKineticsView.effective` — ce n'est plus une cible §10.1.
> - **`DiaGovernor`** existe et alimente **en prod** le DIA *effectif* des courbes de prédiction (plus seulement le peak TAP-G).
> - **`buildLearnedKineticsIobArray`** reconstruit le tableau de prédiction sur `kineticsView.effective` (DIA + peak **gouvernés**), pas les params bruts appris.
> - **C2** : `predictGlycemia` / cost-function T3C consomme `tickEffectiveDia/Peak` (fail-safe 300/75) — dernier item roadmap comblé.
>
> Les **§5, §8 (validation terrain) et les verdicts §1 d'origine** restent la **photo pré-refactor**. Les lignes
> factuelles corrigées ci-dessous sont annotées **« (MAJ 2026-07-13) »**.

**Documents liés :**
- [AIMI_INTELLIGENCE_SNAPSHOT_ROADMAP.md](AIMI_INTELLIGENCE_SNAPSHOT_ROADMAP.md) — implémentation snapshot unifié
- [AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md) — divergences multi-modèle par tick
- [AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md](AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md) — feuille de route causalité unique
- [AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md](AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md) — arbre Lot 1 (`insulin_authority: none`)
- [research/TAP_G_PEAK_GOVERNOR_RFC.md](research/TAP_G_PEAK_GOVERNOR_RFC.md) — gouverneur de peak
- [PKPD_REALTIME_IMPL_SPEC.md](PKPD_REALTIME_IMPL_SPEC.md) — spec runtime PKPD

---

## 1. Résumé exécutif

Le module PKPD AIMI **apprend en ligne** `diaHrs` et `peakMin` (kernel log-normal), **fusionne l'ISF**, **module l'agressivité SMB** (tail / stage), et **gouverne le peak effectif** via TAP-G. Le concept est **cliniquement pertinent**.

En l'état du code et des logs terrain analysés, la modulation DIA/peak est **partiellement efficace** :

| Dimension | Verdict |
|-----------|---------|
| Pertinence du design | ✅ Oui — variabilité inter-patient / site / lot |
| Apprentissage actif en prod | ⚠️ Souvent **bloqué** (gate causal + contexte patient) |
| Propagation DIA appris | ⚠️ Élargie (MAJ 2026-07-13) — `DiaGovernor` effectif sur prédiction + tube + T3C (C2) ; **pas** encore IOB comptable ni `profile.dia` |
| Propagation peak appris | ⚠️ Partielle — TAP-G → `peakTime`, mais profiler/IOB sur profil |
| Alignement arbre physiologique | ❌ Pont indirect (gates + échelles), pas déploiement homologue |
| Impact dose observable | ⚠️ ISF fusion + SMB damp + tube-line DIA ; pas la forme IOB principale |

**Dette structurelle :** ~~trois représentations de la cinétique insuline sans façade unique~~ — la façade
**`InsulinKineticsAuthority` existe désormais** (`resolve()` → `InsulinKineticsView.effective`, MAJ 2026-07-13) ;
reste que `PkPdIntegration` cumule apprentissage, persistance, fusion ISF, physio et damping.

---

## 2. Modèle conceptuel — trois courbes parallèles

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    AUTORITÉS DE CINÉTIQUE (aujourd'hui)                  │
├──────────────────────┬──────────────────────┬───────────────────────────┤
│ A. Profil insuline   │ B. PKPD appris       │ C. Weibull profiler       │
│ (ICfg exponentiel)   │ (LogNormalKernel)    │ (InsulinActionProfiler)   │
├──────────────────────┼──────────────────────┼───────────────────────────┤
│ iobArray SMB         │ estimator learn      │ trajectoire / observateur │
│ IOB accounting loop  │ tailFraction, stage  │ activity loops Plugin     │
│ RealTimeInsulinObs.  │ pkpdIobDataArray (*) │ InsulinWeibullCurve       │
│ profile.dia (loop)   │ tube-line DIA        │ peak = profile.peakTime   │
└──────────────────────┴──────────────────────┴───────────────────────────┘
  (*) optionnel : pref OApsAIMIPkpdPredictionKinetics (défaut ON)
      → rebuild exponentiel AAPS sur DIA/peak EFFECTIFS (kineticsView.effective : DiaGovernor + TAP-G),
        pas les params bruts, pas LogNormal (MAJ 2026-07-13)
```

Ces trois courbes sont **intentionnellement distinctes** historiquement, mais **non documentées** comme telles pour l'utilisateur ni pour les consommateurs internes — d'où l'impression « PKPD actif mais graphe inchangé ».

### Fichiers pivots

| Rôle | Fichier |
|------|---------|
| Apprentissage | `pkpd/AdaptivePkPdEstimator.kt` |
| Orchestration | `pkpd/PkPdIntegration.kt` |
| Kernel | `pkpd/PkPdCore.kt` (`LogNormalKernel`) |
| Peak gouverné | `pkpd/TapPeakGovernor.kt` |
| Profil loop | `OpenAPSAIMIPlugin.kt` (invoke → TAP-G, `buildLearnedKineticsIobArray`) |
| Tick dose | `DetermineBasalAIMI2.kt` (`computeRuntime`, predictions, tube-line) |
| IOB comptable | `ICfg.kt` / `iobCobCalculator.calculateIobArrayInDia` |
| Profiler | `pkpd/InsulinActionProfiler.kt` |
| Logs CSV | `pkpd/PkPdCsvLogger.kt` → `oapsaimi_pkpd_records.csv` |

---

## 3. Pipeline d'apprentissage DIA / peak

### 3.1 Algorithme (`AdaptivePkPdEstimator.update`)

Entrées : BG, delta 5 min, IOB, COB, fenêtre post-bolus (20–180 min), flag exercice.

**Filtres d'exclusion (skip learn) :**

| Condition | Seuil | Raison |
|-----------|-------|--------|
| IOB | &lt; 0,3 U | Signal insuline trop faible |
| COB | &gt; 15 g | Absorption domine |
| Exercice | true | Cinétique non insulinique |
| Delta montée | &gt; 5 mg/dL/5min | Montée non attribuable à l'insuline seule |
| BG hypo | &lt; 75 mg/dL | Récupération hypo |
| BG + chute | &lt; 90 et delta &lt; -1 | Zone fragile |
| Chute rapide | delta ≤ -3 | Bruit / autre cause |

**Mise à jour :** erreur entre chute observée et chute attendue (`kernel.actionAt × IOB × ISF TDD`), pas gradient exact — pas `lr=0.02`, régularisation vers ancres DIA 4 h / peak 75 min, rate-limit journalier (prefs bounds).

### 3.2 Gate causal (arbre Lot 1)

```kotlin
// CausalStatePosterior.kt
fun learningContextClean(minQuality: Double = 0.58): Boolean =
    learningQuality >= minQuality &&
    !(dominant == POST_HYPO_RECOVERY && dominantConfidence >= 0.68) &&
    !(dominant == ABSORPTION_UNCERTAIN && dominantConfidence >= 0.55)
```

L'arbre **ne modifie pas** `diaHrs`/`peakMin` directement ; il **autorise ou interdit** l'apprentissage. C'est un pont **qualité**, pas un pont **paramétrique** homologue au scénario ou à Harmonia.

### 3.3 Modulation physio (échelle, pas forme)

`PkPdIntegration.computeRuntime` applique en parallèle :

- `physioAbsorptionFactor` / `physioSiFactor` → `pkpdScale`, `aggressionMultiplier`
- `weightKineticFactor`, `familyMealFactor`, tail / activity blend
- **Pas** de branche DIA ou peak dans le kernel

Le peak physiologique passe par **`PhysioMultipliersMTR.peakShiftMinutes` → TAP-G**, pas par l'estimateur.

---

## 4. Gouverneur de peak (TAP-G)

```
anchor = prior_peak + physio_shift + site_shift
effective = blend(anchor, learned_peak, w) + trajectory_nudge
         → OapsProfileAimi.peakTime
         → boucles activité (cosine futureActivity)
```

| Branche | Source | Persisté |
|---------|--------|----------|
| prior | `iCfg.peak` (profil insuline) | profil |
| physio | `peakShiftMinutes` | prefs echo |
| site | `TapSitePeakShift` | prefs echo |
| learned | `estimator.peakMin` | `OApsAIMIPkpdStatePeakMin` |
| trajectory | `TrajectoryPeakBias` | prefs echo |

**Asymétrie produit (MAJ 2026-07-13) :** il existe un gouverneur pour le peak (TAP-G) **et désormais un `DiaGovernor`**
(blend profil/appris, en prod via `InsulinKineticsAuthority.resolve` → `kineticsView.effective.diaHours`). Le DIA **effectif**
alimente l'apprentissage, le tube-line (`effectiveDiaH`), **les courbes de prédiction** (`buildLearnedKineticsIobArray`) et la
cost-function T3C (C2) — mais **pas** encore le profil loop `profile.dia` / IOB comptable, qui restent sur le profil. L'asymétrie
résiduelle porte donc sur la **chaîne comptable IOB**, plus sur l'absence de gouverneur DIA.

---

## 5. Matrice de propagation

| Consommateur | DIA appris | peak appris | peak TAP-G | Profil `iCfg` | Notes |
|--------------|:----------:|:-----------:|:----------:|:-------------:|-------|
| `iobArray` (IOB SMB) | ❌ | ❌ | ❌ | ✅ | Chaîne dose principale |
| `OapsProfileAimi.dia` | ❌ | — | — | ✅ | Jamais learned |
| `OapsProfileAimi.peakTime` | — | blend | ✅ | prior | |
| Courbes PKPD (`AdvancedPredictionEngine`) | ⚠️ | ⚠️ | indirect | ✅ défaut | Si `PkpdPredictionKinetics` ON |
| `buildLearnedKineticsIobArray` | ✅ | ✅ | ✅ (peak effectif) | fallback | **(MAJ 2026-07-13)** Sur `kineticsView.effective` (DIA gouverné + peak TAP-G) ; kernel exponentiel AAPS, pas LogNormal |
| Tube-line advisor | ✅ | — | — | fallback | `effectiveDiaH` |
| SMB `adjustedDia` (executor) | ✅ base | via peak | ✅ | fallback | |
| SMB damping (tail/stage) | via kernel | — | — | — | Magnitude pas forme IOB |
| `InsulinActionProfiler` | ❌ | Weibull | lit `peakTime` gouverné | IOB profil | Décalage entrées |
| `RealTimeInsulinObserver` | ❌ | — | — | `profile.dia` | |
| `predictGlycemia` / T3C | ✅ | ✅ | ✅ | fail-safe 300/75 | **(MAJ 2026-07-13)** C2 fait : `tickEffectiveDia/Peak` (repli 300/75 si indispo) |
| Gate maxIOB production (T3c + Harmonia) | ✅ | ✅ | ✅ | fallback | **(MAJ 2026-07-13)** release effectif gouverné hypo — voir §10.5 ; `iobForGate` via `EffectiveIobReleaseAuthority` |
| DynISF Plugin | ❌ | ❌ | ❌ | — | `lastPkpdScale` tick N-1 |
| `PredictionPhysioModulation` | — | — | — | — | `fusedIsf` + facteurs |
| JSONL / UI PKPD | ✅ | ✅ | ✅ | — | Télémétrie |
| PkPd CSV logger | ✅ | ✅ | — | — | Audit long terme |

**Légende :** ✅ utilisé · ⚠️ conditionnel · ❌ ignoré

---

## 6. Flux par tick — couplage

```
OpenAPSAIMIPlugin.invoke()
  ├─ iobArray ← profil iCfg                    [courbe A]
  ├─ computeRuntime #1 (SANS causalPosterior)  [⚠️ peut apprendre "sale"]
  ├─ pkpdIobDataArray ← learned (opt.)         [courbe A + params appris]
  ├─ TapPeakGovernor → peakTime gouverné
  └─ OapsProfile(dia=profil, peak=governed)

DetermineBasalAIMI2
  ├─ computeRuntime #2 (AVEC causal + physio)
  ├─ computeRuntime #3 (prep signaux)
  ├─ computePkpdPredictions(pkpdIob ?: iob)
  ├─ InsulinActionProfiler(iob profil)         [courbe C]
  └─ RealTimeInsulinObserver(dia profil)
```

**Violations SRP :**

1. `PkPdIntegration` — learn + persist + ISF + physio + damp + meal scaling (≈ 600 lignes).
2. Multiples `computeRuntime` par tick avec flags différents.
3. Plugin call #1 sans `causalStatePosterior` → apprentissage possible hors gate Lot 1.

---

## 7. Arbre physiologique vs PKPD — même logique ?

| | Arbre (Lot 1) | PKPD DIA/peak |
|---|---------------|---------------|
| Nature | Contexte causal exporté | Estimateur numérique online |
| Autorité insuline | `none_lot1_context_only` | Partielle (peak, ISF, damp) |
| Effet sur courbes | Aucun direct | Peak via TAP-G ; DIA non gouverné |
| Posterior causal | Patterns → JSONL / guards | Gate `learningContextClean` + facteurs d'échelle |
| Harmonie scénario | Lit phase, trajectoire, repas | N'utilise pas les mêmes branches pour dia/peak |

**Conclusion :** le PKPD n'est **pas** déployé comme l'arbre (pas de nœuds DIA/peak qui se déploient). L'intégration physio est **asymétrique** : peak oui (shift → TAP-G), DIA non (pas de gouverneur ni shift structurant).

---

## 8. Validation terrain — `~/Downloads/AIMI files` et Support Package

Sources analysées :

| Source | Contenu | Période / volume |
|--------|---------|------------------|
| `AIMI_Support_Package_1783712879531/` | Benicio, diag + 24 h JSONL | Build `050726`, 227 ticks |
| `AIMI files/AIMI_Decisions (3).jsonl` | Historique décisions | 37 966 ticks |
| `AIMI files/oapsaimi_pkpd_records (1).csv` | Log PKPD CSV | 56 014 lignes |

### 8.1 État statique Benicio (Diagnostic 2026-07-10)

| Paramètre | Valeur | Commentaire |
|-----------|--------|-------------|
| PKPD enabled | true | Module actif |
| DIA appris (état) | **4,09 h** | Ancre initiale 6 h, bornes min **4 h** → convergé au plancher |
| Peak appris | **74,6 min** | Dans bornes [45, 120] |
| Prior peak (profil) | 90 min | Profil insuline Nightscout |
| TAP-G effective peak | **81,7 min** | `w=0,55`, site +0,4 min, branche LEARNED |
| Peak physio shift | 0 | Pas de modulation physio peak ce jour |
| Profil ISF | 90 → 55 mg/dL/U (circadien) | Fusion PKPD par tick |

**Écart structurel visible :** DIA appris (4,1 h) ≠ DIA comptable profil (typ. 6 h) — l'IOB loop et le graphe OpenAPS utilisent le profil, pas l'état appris.

### 8.2 JSONL Benicio — 24 h (227 ticks)

| Métrique | Valeur | Interprétation |
|----------|--------|----------------|
| COB = 0 | **98,2 %** | Peu de fenêtres « propres » côté COB |
| `causal_learning_quality` médiane | **0,037** | **100 % &lt; 0,58** → apprentissage **bloqué** toute la fenêtre |
| `sensor_confidence` | **0,105 fixe** | Qualité capteur dégradée → gate causal fermé |
| `PkPd_Fusion` factor | 0,21 – 3,14 (med 1,08) | **ISF fusion active** malgré learn bloqué |
| `pkpd_triggers_low_clamp` | 31 / 223 (14 %) | Prédictions PKPD au plancher 39 |
| \|eventual − composite_min\| | med **47,8** mg/dL | Divergence PKPD / safety early |
| `pkpd_eventual_mgdl` médiane | 191 | Courbes PKPD tournent ; contexte meal hypothesis ~92 % |

**Verdict Benicio :** la modulation **DIA/peak en ligne est gelée** sur cette fenêtre ; seuls persistent l'**état appris historique** (DIA 4,1 h, peak 74,6), le **blend TAP-G** (peak 81,7), et la **fusion ISF**. Le code est pertinent, mais **le contexte patient empêche l'ajustement continu**.

### 8.3 Historique long — `AIMI_Decisions (3).jsonl` (37 966 ticks)

| Métrique | Valeur |
|----------|--------|
| Ticks avec `causal_learning_quality` | 9 144 |
| Quality ≥ 0,58 (learn autorisé) | **41 (0,45 %)** |
| Quality &lt; 0,58 (bloqué) | 9 103 (99,5 %) |
| `pkpd_triggers_low_clamp` true | 1 738 / 9 264 (19 %) |
| `PkPd_Fusion` actif | 35 526 ticks |
| \|eventual − composite_min\| (sous-ensemble safety) | med 25 mg/dL |

L'apprentissage DIA/peak **0,45 % du temps** sur l'historique complet — le module vit surtout en mode **état persisté + fusion ISF + damping**, pas en adaptation continue.

### 8.4 CSV PKPD — `oapsaimi_pkpd_records` (56 014 lignes)

Colonnes : `diaH`, `peakMin`, `fusedIsf`, `profileIsf`, `tailFrac`, `smbProposed/Final`.

| Métrique | Valeur | Interprétation |
|----------|--------|----------------|
| DIA médiane globale | 6,0 h (min 4, max 24) | 89 % dans [3, 12] h |
| Peak médiane globale | 76 min | Stable, 223 valeurs uniques (pas 0,5 min) |
| Évolution temporelle (Q1→Q4) | DIA 12 → 6 h ; peak 142 → **54 min** | Convergence lente vers cinétique plus rapide |
| Changements DIA step/tick | **7,6 %** | Learn parfois actif |
| Changements peak step/tick | **0,01 %** | Peak quasi figé une fois appris |
| `fusedIsf / profileIsf` médiane | **0,94** | Fusion ISF significative |
| `tailFrac` médiane | 0,97 (Q4 : 0,43) | Queue insuline → SMB damp fréquent |
| SMB proposed ≠ final | **26,3 %** | Damping / quantification actifs |

**Verdict historique :** sur la durée, le PKPD **a modulé DIA et ISF** de façon crédible ; le **peak** bouge très peu après convergence (rate-limit + gate). La modulation **tail → SMB** est le levier dose le plus actif au quotidien.

---

## 9. La modulation actuelle est-elle suffisante et pertinente ?

### Pertinent (design + code)

1. **Estimation en ligne** sur fenêtre post-bolus — signal clinique réel.
2. **TAP-G** — bon compromis prior profil / learned / physio / site / trajectoire.
3. **Gate causal** — évite d'apprendre en post-hypo ou absorption incertaine.
4. **Fusion ISF** — impact dose mesurable dans les JSONL (`PkPd_Fusion` 0,2–3,1×).
5. **SMB tail damping** — 26 % des ticks modifient la SMB proposée.
   **Semantics (2026-07-18):** stored `aimi_smb_tail_damping` is a multiplicative **floor** —
   lower = stronger damping. Control Center Stability writes the PKPD band `0.70…0.92`
   (Smoother→More reactive). Values ≤0.55 are legacy and remapped to neutral `0.85` at runtime
   (`PkpdSmbTailDamping.effectiveStoredValue`); do not write outside the band from family UI.
6. **`PkpdPredictionKinetics`** — chemin explicite pour courbes sur DIA/peak appris (fail-safe peak &lt; DIA/2).

### Insuffisant (propagation + conditions terrain)

1. **Apprentissage quasi arrêté** quand `learning_quality &lt; 0,58` (Benicio 100 %, historique 99,5 %) — l'état persisté peut **diverger** du profil réel sans correction.
2. **DIA appris non répercuté** sur IOB comptable → incohérence prédictions / sécurité IOB (Benicio : 4,1 h appris vs ~6 h profil).
3. **Pas de gouverneur DIA** symétrique à TAP-G — le DIA peut converger au **plancher de bornes** (4 h) sans contre-poids profil.
4. **Trois courbes** sans façade — profiler et observateur ignorent l'état appris.
5. **`computeRuntime` multiple** — risque d'apprentissage hors gate sur Plugin call #1.
6. **Peak CSV quasi stable** — si le patient change de lot/site, adaptation peak **&lt; 10 min/jour** peut être trop lente.

### Synthèse produit

| Question | Réponse |
|----------|---------|
| Le code actuel est-il pertinent ? | **Oui** pour ISF, peak blend, damp, prédiction optionnelle |
| Est-il suffisant en prod ? | **Non** tant que l'apprentissage est bloqué et le DIA comptable ignore l'état appris |
| Cas Benicio | PKPD « actif » en UI mais **frozen learn** ; modulation réelle = ISF + peak gouverné + damp, pas DIA live |
| Cas historique AIMI files | Modulation **a fonctionné** sur DIA/ISF ; peak une fois calé, peu mobile |

---

## 10. Cible architecture propre (sans changement comportement initial)

Refactor **structurel** : mêmes entrées/sorties aux frontières publiques.

### 10.1 Ports (hexagonal)

```kotlin
interface InsulinKineticsAuthority {
    fun accountingIobArray(): Array<IobTotal>      // toujours profil — documenté
    fun learnedParams(): PkPdParams?
    fun effectivePeak(): TapPeakGovernorResult
    fun predictionIobArray(): Array<IobTotal>?     // pkpdIob ?: accounting
    fun effectiveDiaHours(): Double                // learned ?: profile.dia
}

interface PkpdLearningPort {
    fun observe(ctx: PkpdTickObservation): PkPdRuntime  // un seul update/tick
}

interface PhysioKineticsModulation {
    fun learningGate(posterior: CausalStatePosterior): Boolean
    fun scaleFactors(state: PatientStateSnapshot): PkpdScaleFactors
}
```

### 10.2 Règles de propagation (cible)

| Frontière | Règle unique |
|-----------|--------------|
| Prédictions | `predictionIobArray()` centralise `PkpdPredictionKinetics` |
| Peak décisions | Toujours `effectivePeak()` post-TAP-G |
| DIA advisory | `effectiveDiaHours()` pour tube, NGR, logs — **pas** `profile.dia` seul |
| Apprentissage | **Un** `computeRuntime` / tick via `AimiTickContext`, toujours avec posterior |
| ISF Dyn | Une `IsfFusion` = `PkPdRuntime.fusedIsf` courant |

### 10.3 Découpage `PkPdIntegration`

| Module extrait | Responsabilité |
|----------------|----------------|
| `PkpdLearner` | `AdaptivePkPdEstimator` + persist + gates |
| `PkpdRuntimeComposer` | scale, activity, tail, stage |
| `IsfFusionService` | fusion TDD / profil (existe partiellement) |
| `PkpdSmbPolicy` | `SmbDamping` (existe) |
| `PeakGovernorService` | `TapPeakGovernor` (déjà isolé) |
| `DiaGovernorService` | **nouveau** (futur) — symétrie TAP-G pour DIA |

### 10.4 Plan PRs (incrémental, comportement préservé)

| PR | Contenu | Risque | Statut (2026-07-13) |
|----|---------|--------|---------------------|
| PR-1 | Doc + `InsulinKineticsAuthority` read-only wrapper | Nul | ✅ Fait — façade `resolve()` → `InsulinKineticsView` |
| PR-2 | Unifier `computeRuntime` → `AimiTickContext` | ⚠️ async tick | ⚠️ Partiel — `AimiTickContext` en place, unification `computeRuntime` non confirmée |
| PR-3 | Centraliser `predictionIobArray()` / `effectiveDiaHours()` | Faible | ✅ Fait — `buildLearnedKineticsIobArray` + `tickEffectiveDia/Peak` |
| PR-4 | Scinder `PkPdIntegration` en 4 classes | Moyen | ⬜ À faire |
| PR-5 | ~~Shadow~~ `DiaGovernor` (blend profil/learned) | Faible | ✅ En **prod** — prédiction/tube/T3C (pas encore `profile.dia`) |
| PR-6 | Aligner `InsulinActionProfiler` entrées sur `predictionIobArray()` | ⚠️ courbes | ⬜ À faire |

Roadmap alignée : **C1** snapshot autoritaire ([AIMI_PREDICTION_DIVERGENCE.md](AIMI_PREDICTION_DIVERGENCE.md)) **✅ en prod**, **C2** `predictGlycemia` branché sur kinetics authority **✅ fait (2026-07-13)**.

### 10.5 Pont IOB comptable → gate maxIOB : release effectif gouverné par les hypos (réalisé 2026-07-13)

**Limite levée.** Le gate maxIOB de **production** compare l'IOB **comptable** (profil DIA) à `maxIOB`. Pour une
insuline rapide, le DIA effectif appris est plus court → le comptable **surestime** l'insuline restante → le gate
**bloque des corrections d'hyper** que les courbes effectives (C1) veulent déjà. Les deux moitiés de la décision
divergent → hypers sous-traités. C'est le **seul** consommateur qui restait sur le comptable (les courbes + T3C sont
déjà sur l'effectif).

**Mécanisme (AIMI-local, zéro cœur/pompe/NS).** `EffectiveIobReleaseAuthority` (objet pur, `safety/`) :

```
iobForGate = ledger − θ · (ledger − effectif)          θ ∈ [0, THETA_MAX = 0.5]
gate : if (resolveIobForGate() > maxIob) → block        (T3c planT3cBasalFirstProduction + Harmonia production)
```

- **Release-only** : effectif ≥ ledger (insuline lente) → no-op, jamais moins prudent. `maxIOB` **jamais modifié**.
- **Gouvernance hypo (arbre)** — θ replié à **0** si : `PostHypoDeliveryAuthority.active`, ou `minBgInLastMinutes < 75`.
  Rampe 0→θmax sur plancher 75→105 mg/dL ; ×0,3 en ReboundSuspected/MealConfirmed. `postHypoProb` (UAM) : réservé v2.
- **Boucle fermée / auto-limitant** : une hypo EST le signal de repli (θ→0) → au pire une excursion bornée puis
  retour au comptable + cooldown via le plancher glycémique. Terrain : apprentissage bloqué ~99,5 % → effectif ≈
  ledger → θ≈0 → **no-op la quasi-totalité du temps**.

**Cohérence produit.** Aucun chemin de décision parallèle : l'arbre/Harmonia décident **quoi** doser ; le release
n'harmonise que le check `max_iob` de la **même séquence de gardes** qui lit déjà `PostHypoDeliveryAuthority`,
`riskLevel == CRITICAL`, `meal_conflict`, `stacking_cap`. L'IOB effectif vient de la **même** `InsulinKineticsAuthority.effective`
(source cinétique unique) que C1/C2. La sortie Harmonia reste soumise à tout l'aval (SafetyNet, caps, block CRITICAL).

**Gouvernance & observabilité.** Pref `OApsAIMIEffectiveIobReleaseEnabled` (défaut ON). JSONL : bloc `iob_release`
(`theta, iob_ledger_u, iob_effective_u, iob_for_gate_u, released_u, gate_flips_block_to_allow, reason`) dans
`AIMI_Decisions.jsonl` — **`gate_flips_block_to_allow: true`** = l'événement sécurité à auditer. Tests :
`EffectiveIobReleaseAuthorityTest` (9). **v2** : brancher `postHypoProb` (prudence anticipative), étendre au gate
prébolus T3c (L2434).

---

## 11. Recommandations opérationnelles (terrain)

Pour un profil type Benicio (learn bloqué, COB=0, sensor_low) :

1. **Ne pas sur-interpréter** le DIA appris 4,1 h — état **historique**, pas mis à jour en 24 h.
2. **Aligner profil insuline** peak ~75–82 min avec TAP-G effective (81,7) plutôt que 90 min seul.
3. **Vérifier** `learning_quality` dans JSONL avant de tuner bounds PKPD.
4. **Déclarer COB** / corriger prefs SMB pour fenêtres d'apprentissage plus propres.
5. **MAJ APK** `100726+` pour correctifs récents, mais **ne remplace pas** l'alignement kinetics.

---

## 12. Checklist revue code (mainteneur)

- [ ] Un seul `computeRuntime` par tick avec `causalStatePosterior`
- [ ] `InsulinKineticsAuthority` documente les 3 courbes
- [ ] `effectiveDiaHours()` utilisé partout où le diag cite « PKPD DIA »
- [ ] `predictionIobArray()` seul point pour `pkpdIobDataArray`
- [ ] JSONL expose `learned_dia_h`, `effective_peak_min`, `learning_gate_pass` (aujourd'hui partiel)
- [ ] Tests : TAP-G (existe), + test propagation `effectiveDiaHours` tube vs profile
- [ ] Shadow DiaGovernor avant prod

---

*Document généré à partir de l'analyse statique du code `dev_OAPSAIMI` et des exports `~/Downloads/AIMI files` + `AIMI_Support_Package_1783712879531`.*
