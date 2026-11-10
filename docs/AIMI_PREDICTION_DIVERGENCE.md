# AIMI — Divergence de prédiction, trajectoire et vision globale

**Statut :** analyse produit + correctif terrain gaté (clamp reconcile)  
**Date :** 2026-07-09 (maj 2026-07-18)  
**Portée :** signalements utilisateurs (« prédictions contradictoires »), impact dose, rôle de la trajectoire, arbre/Harmonia  
**Build de référence :** `dev_OAPSAIMI` (post F1 Red Carpet, carry-forward prébolus legacy)

**Documents liés :**
- [AIMI_SCENARIO_PROJECTION.md](AIMI_SCENARIO_PROJECTION.md) — dual curves `clinicalFloor` / `scenarioBest`
- [AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md](AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_ROADMAP_2026-06-10.md) — feuille de route causalité unique
- [AIMI_HYPER_TRAJECTORY_RELEASE.md](AIMI_HYPER_TRAJECTORY_RELEASE.md) — HTR vs TrajBridge
- [AIMI_RECURSIVE_BELIEF.md](AIMI_RECURSIVE_BELIEF.md) — arbitrage RBT (`SPIRAL_VS_RISE`, `TRAJ_TBR_VS_HTR_SMB`)
- [AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md](AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md) — arbre physiologique (Lot 1)

---

## 1. Résumé exécutif

Les signalements « prédictions AIMI fausses ou contradictoires » sont **réels au niveau affichage et consommateurs**, mais ce n'est en général **pas une hallucination** (invention de glycémies). C'est le symptôme d'une **architecture multi-modèle** où, dans un même tick :

1. **Plusieurs chemins** calculent des terminaux BG différents (PKPD early → scénario → PKPD main).
2. **Plusieurs courbes** coexistent dans `rT.predBGs` (IOB, COB, UAM, hybrid) avec des planchers numériques distincts.
3. **Plusieurs sous-systèmes de dose** ne lisent pas la même valeur (SafetyNet vs protection hypo vs stacking vs meal priority).
4. La **trajectoire** apporte une « vision » géométrique riche (type, énergie, convergence), mais elle n'alimente que **partiellement** les courbes PKPD et entre en **tension** avec HTR / meal rise sur certains profils.

**L'arbre physiologique et Harmonia ne corrompent pas les courbes de prédiction.** L'arbre exporte du contexte ; Harmonia simule/produit de la basale (Lot 1, `adds_smb_authority=false`). Le problème est en amont : **pas de snapshot autoritaire unique par tick**.

**Correctif terrain (2026-07-18) :** `ClampPkpdScenarioReconcile` — réconciliation **évidence-gatée** du faux plancher PKPD **avant** SafetyNet **et** stacking, **même si** Prediction Authority est ON. Bras classiques zone-2 + bras `DIGESTION_ACTIVE` / meal-active en zone-3. Veto chute / sport / post-hypo / pathMin scénario. Marker log : `CLAMP_RECONCILE`. La racine (NUMERIC_FLOOR / C1 snapshot unique) reste la direction long terme.

**Réversion endogène (EGP) — Wave4 H3 + garde-fous terrain (2026-07-22) :** `AdvancedPredictionEngine` applique une dérive lente (`ENDO_REVERSION_RATE`) vers une baseline quand l'insuline est épuisée (`|impact/pas| < ENDO_INSULIN_NEGLIGIBLE_MGDL`), sur IOB/COB/UAM/ZT **et** hybrid, afin de décoller les courbes du plancher absorbant 39. Ce terme alimente `minPredictedAcrossCurves` → protection hypo, donc deux garde-fous sécurité issus de l'analyse du support-package `1784724586473` (24 h) :

- **Guard A — ancre plafonnée par le BG courant.** L'ancre de réversion vaut `min(ENDO_REVERSION_BASELINE_MGDL, max(BG, NUMERIC_FLOOR))`. Sur un plateau bas (ex. BG=70 à plat) l'EGP ne prédit plus un rebond au-dessus de la valeur mesurée (avant : `soft`=80 alors que le BG réel restait 70) ; l'artefact plancher 39 reste corrigé dès que BG > 80.
- **Guard B — suspension sur chute franche.** Si `delta ≤ -3 mg/dL/5 min` (aligné sur `ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL`), l'EGP est totalement suspendue pour le tick : le path-min de sécurité reste pessimiste pendant une vraie descente (observé : -11).

Télémétrie d'étude (`AIMI_Decisions.jsonl` → `adjustments.pkpd_soft_floor`) : `raw_path_min_mgdl`, `soft_path_min_mgdl`, `delta_soft_minus_raw_mgdl`, `applied`, `suppressed_by_falling_trend`, `reason` (`egp_applied_on_insulin_curves` / `endo_suppressed_falling_trend` / `floor_but_insulin_still_active` / …). Marker log : `PKPD_SOFT_FLOOR`. **Validation terrain 24 h :** BG plancher réel 70 (aucune hypo), 116 SMB tous à BG ≥ 150, **0 SMB sous EGP+BG < 95** — la protection hypo n'a jamais été supprimée.

---

## 2. Ce que l'utilisateur voit vs ce que le code fait

Exemple typique dans `reason` / Nightscout :

```
minPredBG 39, minGuardBG 39, IOBpredBG 39, UAMpredBG 242, Eventual BG 226
```

| Valeur | Source réelle | Interprétation |
|--------|---------------|----------------|
| `minPredBG 39` | Minimum sur **toutes** les courbes PKPD (`PredictionCurveMath.minPredictedAcrossCurves`) | Courbe insuline-only collée au plancher `NUMERIC_FLOOR=39` |
| `UAMpredBG 242` | Terminal courbe UAM (momentum + deviation) | Projection repas/montée, plafonnée à 401 |
| `Eventual BG 226` | Terminal **hybrid** PKPD (dose SMB) ou scénario selon le chemin | Pas la même chose que minPred |
| `scenario_best` (JSONL) | `ScenarioProjectionEngine` | Fusion physio + repas + **trajectoire** + activité |

Ce n'est pas une incohérence d'un seul modèle : ce sont **des agrégats différents** du même tick, parfois affichés côte à côte.

---

## 3. Pipeline de prédiction par tick (ordre réel)

```
T9 physio + CosineTrajectoryGate (relevance)
    ↓
applyTrajectoryAnalysis (TrajectoryGuard) → caps SMB/interval/MaxIOB, rT.trajectory*
    ↓
runAdvancedPredictionsAndPredPipePrep
    → PKPD curves (AdvancedPredictionEngine)
    → ScenarioProjectionEngine (+ couche trajectoire sur scenarioBest)
    → safety early via scenario floor/best (PRED_PIPE)
    ↓
runPkpdPredictionsBgiDeviationAndNoisyTargetsStage
    → computePkpdPredictions (RE-ÉCRASE rT.predBGs, eventualBG, predictedBg)
    ↓
finalizeAndCapSMB / stacking / SafetyNet / HTR / Harmonia production
```

**Fichiers clés :** `DetermineBasalAIMI2.kt` (orchestration), `AdvancedPredictionEngine.kt`, `ScenarioProjectionEngine.kt`, `PredictionDivergenceAuditor.kt`.

### 3.1 Écart produit documenté vs implémenté

[AIMI_SCENARIO_PROJECTION.md](AIMI_SCENARIO_PROJECTION.md) prévoit que `ScenarioProjectionApplicator.applyToRt` mappe `IOB=floor`, `UAM=best` sur `rT.predBGs`.

**État actuel :** `ScenarioProjectionApplicator` est importé dans `DetermineBasalAIMI2.kt` mais **jamais appelé** en production. Les courbes UI / `predBGs` restent PKPD brutes ; la safety early lit le scénario enrichi. → **Contradiction visible garantie** tant que C1 n'est pas fait.

---

## 4. Mécanismes d'« erreur de prédiction » (par sévérité dose)

### 4.1 Plancher numérique PKPD (39 mg/dL) — structurel

**Où :** `AdvancedPredictionEngine.kt` — `NUMERIC_FLOOR = 39.0`, `coerceIn(39, 401)` sur courbes IOB/hybrid.

**Mécanisme :** À IOB élevé post-prandial, l'intégration insuline-only pousse la courbe vers 39 ; UAM/hybrid peuvent rester hauts. `minPredictedAcrossCurves` retourne 39.

**Impact dose :**
- **Conservateur** via stacking IOB (`InsulinStackingStance`, `trajectoryEnergy > 2`).
- **Clamp zone 2** SafetyNet si eventual PKPD < 120 → `maxSmbLow`.
- Atténué par `HyperTrajectoryHypoCredibility.sanitizeTerminalsForHypoGuard` en bande hyper.

**Risque correction non souhaitée :** faible (sous-correction). Fréquent sur profils COB=0 + IOB résiduel (cf. analyse terrain Thomas Willems, juillet 2026).

### 4.2 Momentum sans ré-ancrage — racine runaway

**Où :** `AdvancedPredictionEngine.predictCurves` — `deviation` semée une fois, `hybridMomentum` / `uamMomentum`, decay 0,72–0,96/step, **pas de recalage à la BG mesurée**.

**Impact dose :** indirect — alimente terminaux PKPD/scénario ; peut gonfler UAM terminal pendant que IOB floor reste à 39.

**Action :** **Option B shadow** (log nouvelle vs ancienne courbe, zéro impact dose) avant tout fix prod. Voir §7.

### 4.3 Consommateurs divergents — architectural

Documenté dans `PredictionDivergenceAuditor.kt` (audit **log-only**) :

| Consommateur | Lit | Effet |
|--------------|-----|-------|
| `SafetyNet.calculateSafeSmbLimit` | PKPD eventual (`decisionEventualBgForSmb`) | Plafond SMB |
| Protection hypo / early safety | Scénario + sanitize HTR | SMB=0, halt pipeline |
| `InsulinStackingStance` | eventual + **min**(`predBGs`) + `trajectoryEnergy` | Damp SMB, suppress Red Carpet |
| Meal priority / uplift | Scénario + UAM confidence | **Augmente** appétit correction |
| Red Carpet restore | gated vs proposed | Restaure SMB « mineur » (bloqué si vital zero — F1) |

**Risque correction non souhaitée :** **moyen à élevé à la hausse** quand scénario/UAM hyper-projeté + contexte repas, pendant que PKPD dit hypo (39). Ex. tick soir 21:44 (JSONL terrain) : protection hypo partielle, SMB 0,29 U malgré minPred=39.

### 4.4 Red Carpet — atténué (2026-07-08)

Restaurait un SMB=0 de protection hypo. **Fix F1 :** `criticalSafetyZeroedThisTick` interdit le restore sur zéro vital. Ne concerne pas les prébolus modes manuels (`applyLegacyMealModes`, return early).

---

## 5. Trajectoire — rôle de « vision » et limites actuelles

### 5.1 Rôle produit attendu

La trajectoire (`trajectory/`) modélise la **géométrie phase-space** (BG, Δ, IOB, stade PKPD sur ~90 min) :

| Composant | Rôle |
|-----------|------|
| `TrajectoryHistoryProvider` | Historique bucketé CGM + IOB/Weibull par sample |
| `TrajectoryMetricsCalculator` | κ, v_conv, ρ, **energyBalance (E)**, openness |
| `TrajectoryGuard` | Classification → `TrajectoryType` + modulation |
| `TrajectoryType` | `TIGHT_SPIRAL`, `OPEN_DIVERGING`, `STABLE_ORBIT`, `HOVERING`, … |

**Vision attendue :** anticiper si la dynamique actuelle mène à un empilement insuline (spirale) ou à une montée ouverte (repas/stress), et **orienter** basale/SMB/prédiction scénario en conséquence.

### 5.2 Ce que la trajectoire alimente réellement

| Sortie | Consommateur | Impact |
|--------|--------------|--------|
| `rT.trajectoryType`, `trajectoryEnergy`, métriques | JSONL, UI, stacking | Télémétrie + damp SMB si E>2 |
| Modulation directe (relevance > 0,4) | `maxSMB`, `intervalsmb`, `maxIob` | Caps tick |
| **TrajBridge** (`TIGHT_SPIRAL`) | TBR proactive 25–70 % basal, cap SMB standard | Dose basale différée |
| **Couche scénario** (`ScenarioProjectionEngine.applyTrajectoryLayer`) | `scenarioBest` uniquement | Biais rise/damp/convergence sur courbe **best** |
| **HTR** (`HyperTrajectoryReleaseEvaluator`) | Relâchement SMB hyper | Peut **augmenter** SMB |
| **RBT** | `SPIRAL_VS_RISE`, `TRAJ_TBR_VS_HTR_SMB` | Arbitrage conflits |
| **DecisionPredictionAuthority** | `OPEN_DIVERGING` / `SLOW_DRIFT` | Uplift scénario vs PKPD |

### 5.3 Ce que la trajectoire n'alimente PAS (gap vision)

1. **`rT.predBGs` PKPD brutes** — `computePkpdPredictions` n'a **aucune** entrée trajectoire. La vision ne recalcule pas les courbes IOB/UAM/hybrid.
2. **`ScenarioProjectionApplicator`** — non branché → la vision scénario n'unifie pas l'affichage graphique.
3. **Seuil relevance 0,25 vs 0,4** — la couche scénario peut bouger sans modulation SMB correspondante.
4. **Historique async** — `trajectoryHistoryCached` peut être d'un tick précédent (premiers ~20 min ou charge).

### 5.4 Tensions trajectoire ↔ autres couches

| Conflit | Description |
|---------|-------------|
| **TrajBridge vs HTR** | Spirale → frein basale ; HTR hyper → relâche SMB. `suppressTrajBasalShift` + canal RBT `TRAJ_TBR_VS_HTR_SMB`. |
| **TIGHT_SPIRAL vs repas** | Spirale + Δ>2 (first wave) → `SPIRAL_VS_RISE` (RBT). TrajBridge a des relax meal/hyper mais paradoxe peut fire. |
| **OPEN_DIVERGING vs stacking** | Guard boost SMB 1,25–1,4× + scénario rise bias ; plus tard stacking sur IOB+trajE → damp. |
| **Même E, sens opposé** | E>2 → surveillance (moins SMB) ; E>3 + spiral → classification spirale ; HTR EMERGING peut relax TrajBridge. |

**Conclusion trajectoire :** la vision **existe et est riche**, mais elle est **fragmentée** — caps directs, biais scénario, TrajBridge, HTR, stacking lisent des facettes différentes sans recalcul PKPD unifié. D'où l'impression utilisateur de prédictions qui « disent une chose » (graph minPred 39) pendant que la trajectoire / le scénario « disent autre chose » (montée, FIRST_WAVE, OPEN_DIVERGING).

---

## 6. Arbre physiologique et Harmonia — périmètre

| Couche | Modifie les courbes BG ? | Autorité dose |
|--------|--------------------------|---------------|
| `physiological_tree` (7 racines, patterns FAST_MEAL, POST_HYPO_RECOVERY, …) | **Non** — export JSON + contexte Harmonia | Aucune directe |
| `harmonia_simulation` | **Non** — `simulation_only=true` | Virtual |
| `harmonia_production` | **Non** — basal-first, `adds_smb_authority=false` | Basale si `APPLIED` (Lot 1) |
| `PhysioPhaseFusion` / `PredictionPhysioModulation` | **Oui (indirect)** — scale momentum/carb dans PKPD | Via courbes PKPD |

Harmonia **aboutit** (décision par tick) mais peut être `BLOCKED` (HYPO_RISK, post_hypo) — c'est le comportement attendu Lot 1, pas un échec de déploiement arbre.

---

## 7. Garde-fous déjà en place

| Garde | Fichier / mécanisme | Effet |
|-------|---------------------|-------|
| F1 Red Carpet | `criticalSafetyZeroedThisTick` | Pas de restore sur zéro vital hypo |
| HTR credibility | `HyperTrajectoryHypoCredibility` | Ignore minPred=39 incohérent en hyper |
| Safety terminals | `SafetyPredictionTerminalsResolver` | Uplift artefact plancher 39 |
| Decision envelope | `DecisionPredictionAuthorityResolver` | Fusion PKPD/scénario pour risk |
| Divergence audit | `PredictionDivergenceAuditor` | Log `low_clamp_disagreement` |
| SafetyNet sanitize | `sanitizeEventualMgdlForSmbZones` | Cap eventual pathologique >300 |
| Naive guard | `NaiveEventualBgSignGuard` | Effondre eventual incohérent |

**Manque structurel :** une **vérité autoritaire par tick** (objectif roadmap 2026-06-10).

---

## 8. Feuille de route (alignée décision produit 2026-07-08)

| Phase | Action | Impact dose | Priorité |
|-------|--------|-------------|----------|
| **A′ terrain** | `ClampPkpdScenarioReconcile` (gaté ; authority ON inclus ; stacking+SafetyNet) | Relâche faux planchers meal/digestion | **Livré 2026-07-18** (à valider device) |
| **—** | ~~Option A reconcile clamp non gaté~~ | Relâche SMB hypo-dominant | **Non** (rejeté) |
| **B shadow** | Log momentum corrigé vs actuel (`hybrid.last`, `path_min`) à côté `PRED_DIVERGENCE` | **Zéro** | **Maintenant** |
| **B prod** | Ré-ancrage momentum (Kalman Ra / decay) après validation shadow 2–3 sem | Corrige runaway à la source | Après B validé |
| **C1** | Un seul `computePredictions` + cache ; brancher `ScenarioProjectionApplicator` ou équivalent | Unifie consommateurs | **Avec B prod** |
| **C2** | `predictGlycemia` Lyumjev-aware (DIA/peak prefs) | Basale costFunction seulement | Plus tard |
| **Trajectoire** | Après C1 : injecter `TrajectoryAnalysis` dans le snapshot unique (pas seulement couche scénario) | Vision → même courbe que dose | Post C1 |

### Critères validation B shadow

- Floor-rate PKPD ≤45 **baisse** sur JSONL
- MAE vs CGM **s'améliore** (+60/+90 min)
- Détection vraies hypos (50 % floors légitimes) **ne se dégrade pas**
- `%<54` TIR **ne monte pas**

---

## 9. FAQ support / terrain

**« L'arbre ne se déploie pas »**  
Vérifier `physiological_tree.roots` non vide dans JSONL. Ticks `bg=null` (trous CGM) = arbre absent (7/262 normal).

**« Harmonia n'aboutit pas »**  
Vérifier `harmonia_production.mode` : `BLOCKED` + `runtime_blocker=post_hypo|hypo_or_recovery` = diagnostic correct, pas bug. Lot 1 ne pousse pas de SMB.

**« Nos modifs d'hier ont causé l'hypo »**  
F1 et carry-forward prébolus : F1 **réduit** SMB ; carry-forward **uniquement** modes repas actifs. Hypo nocturne avec IOB ~12 U + SMB=0 partout → bolus externe + queue insuline, pas prédiction restore.

**« Prédictions contradictoires »**  
Attendu tant que C1 absent. Pointer vers `pred_divergence` et `scenario_projection.contributors` dans JSONL pour expliquer PKPD vs scénario vs trajectoire.

---

## 10. Fichiers code — index rapide

| Sujet | Fichier |
|-------|---------|
| Orchestration tick | `DetermineBasalAIMI2.kt` |
| Courbes PKPD + momentum | `pkpd/AdvancedPredictionEngine.kt` |
| Scénario + couche trajectoire | `scenario/ScenarioProjectionEngine.kt` |
| Applicator non branché | `scenario/ScenarioProjectionApplicator.kt` |
| Audit divergence | `prediction/PredictionDivergenceAuditor.kt` |
| Clamp SMB zones | `safety/SafetyNet.kt` |
| Trajectoire | `trajectory/TrajectoryGuard.kt`, `TrajectoryMetricsCalculator.kt` |
| TrajBridge | `DetermineBasalAIMI2.kt` (`runTrajectoryTightSpiralSafetyBridge`) |
| HTR | `release/HyperTrajectoryReleaseEvaluator.kt` |
| Crédibilité hypo hyper | `release/HyperTrajectoryHypoCredibility.kt` |
| Stacking | `safety/InsulinStackingStance.kt` |
| Red Carpet + F1 | `DetermineBasalAIMI2.kt` (`finalizeAndCapSMB`, `runPkpdGuardEndoDampenRedCarpetAndCapSmb`) |

---

*Document rédigé à partir de l'analyse code HEAD `dev_OAPSAIMI` et des packages support terrain (juillet 2026). Aucun changement de comportement dose n'est implicite dans ce document.*
