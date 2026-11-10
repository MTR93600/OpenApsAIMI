# AIMI Harmonia / Physiological Tree - Lot 1

## 1. Objectif du lot

Lot 1 introduit AIMI Harmonia comme arbre physiologique réel, construit pendant le flux AIMI et publié dans les canaux existants de contexte, presentation et export.

Le but n'est pas de commander l'insuline. Le but est de donner a AIMI une structure stable pour comprendre l'etat du corps avant toute evolution future des recommandations ou des reglages.

## 2. Pourquoi l'arbre est la premiere etape

AIMI possede deja beaucoup de signaux: phase physiologique, absorption repas, UAM, etat latent, causal posterior, memoire hyper/hypo, contexte utilisateur, temperature nocturne et RBT/T3C. Sans structure commune, ces signaux peuvent rester difficiles a expliquer et difficiles a exposer proprement aux modules produit.

L'arbre sert de couche de comprehension commune:

- racines: contexte de profil, TDD, basale, ISF, preferences, historique et ML asynchrone;
- tronc: etat physiologique global, confiance, coherence et risque;
- branches: digestion, repas, activite, post-effort, sommeil, stress, hormones, resistance, capteur, hypo et hyper;
- feuilles: explications, notes Auditor, hints Advisor, hints Meal Advisor, notes AIMI Context, garde-fous et raisons de non-action;
- fruits: signaux d'apprentissage et outcomes futurs;
- saisons: patterns circadiens, hormonaux, recuperation et resistance recurrente.

## 3. Architecture finale de ce lot

Le modele central est `PhysiologicalTreeSnapshot`, construit par `PhysiologicalTreeBuilder`.

Le builder lit uniquement des donnees deja disponibles:

- `PatientStateSnapshot`;
- `PatientModeOrchestrator.Decision`;
- `PhysioLiveDigest`;
- `ThermalBeliefDigest`.

Il produit un objet serialisable avec un resume court, par exemple:

```text
Tree: resistance probable | conf 86% | risk moderate | sensor ok
```

Le JSON exporte explicitement:

```json
{
  "insulin_authority": "none_lot1_context_only",
  "source": "harmonia_tree_v1"
}
```

Cette mention est volontaire: Harmonia Lot 1 est une comprehension physiologique active dans le produit, pas une autorite insulinique.

## 4. Flux avant/apres

Avant:

- `DetermineBasalAIMI2` calculait deja les etats physio/UAM/patient mode et les exportait separement.
- AIMI Context lisait le runtime patient mais n'avait pas de resume unifie de type arbre.
- Les notes utiles a Auditor/Advisor/Meal Advisor etaient dispersees dans plusieurs concepts.

Apres:

- `DetermineBasalAIMI2` construit l'arbre + Harmonia **toujours** sur le path dose (cascade native R1, 2026-07-18). `AimiPhysioAssistantEnable` ne gate plus que les multiplicateurs vitaux / extras assistant.
- `PatientRuntimeSnapshot` transporte `physiologicalTree`.
- `PatientStateRuntimeRepository` publie l'arbre avec le runtime patient.
- `PatientStatePresentationBuilder` consomme le resume compact dans AIMI Context.
- `AIMI_Decisions.jsonl` contient `adjustments.physiological_tree`.
- Les feuilles contiennent des notes structurees pour Auditor, Advisor et Meal Advisor.

## 5. Fichiers modifies

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`: construction, log court et export JSONL de l'arbre.
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRepository.kt`: ajout de `physiologicalTree` au snapshot runtime.
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresher.kt`: refresh de l'arbre avec les signaux live quand Harmonia est deja active.
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentation.kt`: affichage du resume `Tree:` dans AIMI Context.
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentationBuilderTest.kt`: verification de la presentation.
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresherTest.kt`: verification du refresh runtime.

## 6. Classes creees

- `PhysiologicalTreeSnapshot`
- `PhysiologicalRoots`
- `PhysiologicalTrunk`
- `PhysiologicalBranches`
- `PhysiologicalLeaves`
- `PhysiologicalFruits`
- `PhysiologicalSeasons`
- `PhysiologicalSignalState`
- `GlobalPhysiologicalState`
- `PhysiologicalRiskLevel`
- `DataCoherenceLevel`
- `PhysiologicalTreeBuilder`

Ces classes sont dans `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PhysiologicalTree.kt`.

## 7. Integration avec Auditor, Advisor, Meal Advisor et AIMI Context

AIMI Context consomme immediatement le resume compact via `PatientStatePresentationBuilder`.

Auditor, Advisor et Meal Advisor disposent maintenant d'une structure prete a consommer dans les feuilles de l'arbre:

- `auditorNotes`;
- `advisorHints`;
- `mealAdvisorHints`;
- `aimiContextNotes`;
- `safetyNotes`;
- `decisionExplanation`;
- `noActionReasons`.

Lot 1 n'injecte pas encore ces champs dans les prompts LLM. C'est volontaire: modifier un prompt peut changer une recommandation utilisateur meme sans changer la dose. Le prochain lot doit ajouter ce contexte en lecture seule avec tests golden prompts.

## 8. Logs utilisateur

Sur un tick de boucle, si Harmonia est active, le log recoit une ligne courte:

```text
Tree: digestion + meal probable | conf 78% | risk low | sensor ok
```

Les refreshs de contexte ou de signaux physio ne polluent pas le log console: le resume court est ajoute uniquement depuis `PatientRefreshSource.LOOP_TICK`.

## 9. Garde-fous (Lot 1 uniquement)

> **Note 2026-06 :** les lots 13+ (simulation + production basal-first + RBT-native) ont ajouté une autorité TBR **conditionnelle** via `HARMONIA_PRODUCTION_BASAL_FIRST`. La liste ci-dessous décrit le périmètre **strict du Lot 1 arbre seul** ; voir §13–§14 pour l'état actuel.

Lot 1 (arbre seul) ne modifiait pas:

- SMB;
- TBR/basale;
- ISF;
- cibles;
- AutoDrive;
- T3C basal-first;
- PKPD;
- Meal Advisor;
- preferences permanentes;
- schemas Hormonitor.

Le builder ne contient aucun champ de commande insulinique et l'export teste l'absence de champs directs comme `smb_u`, `tbr_uph` ou `bolus_u`.

La neutralisation passe par la preference existante `AimiPhysioAssistantEnable`; aucune nouvelle preference n'est ajoutee.

## 10. Tests

Tests ajoutes:

- creation d'un arbre stable avec donnees coherentes;
- absence d'arbre quand la couche est desactivee;
- degradation capteur incertain;
- branche digestion/repas active sur premiere vague;
- resistance/endogene dominante;
- hypo/post-hypo en contexte prioritaire;
- absence de crash si wearable ou ML absent;
- export JSON context-only sans champ de commande insulinique.

Tests existants adaptes:

- `PatientStatePresentationBuilderTest`;
- `PatientStateRuntimeRefresherTest`.

## 11. Build

Commandes executees:

```bash
git diff --check
```

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:testFullDebugUnitTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRefresherTest
```

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:compileFullDebugKotlin
```

Resultat:

- `git diff --check`: succes;
- tests cibles: succes;
- compilation Kotlin `:plugins:aps:compileFullDebugKotlin`: succes.

Note environnement: les commandes Gradle ont d'abord ete bloquees par le sandbox sur les sockets de file-lock Gradle, puis relancees hors sandbox avec succes.

## 12. Limites

Harmonia Lot 1 n'est pas encore:

- une UI arbre complete;
- une autorite de dose;
- une modification automatique des preferences;
- une commande LLM;
- un moteur ML synchrone;
- une refonte d'AutoDrive, Auditor ou Advisor.

Les fruits restent principalement des signaux d'apprentissage. Les outcomes reels devront etre relies a des replays et cohortes valides dans un lot ulterieur.

## 13. Lots suivants realises

Un premier lot introduit une branche Harmonia active en simulation:

- `HarmoniaSimulationEngine` produit une decision virtuelle bornee;
- `PatientRuntimeSnapshot` transporte `harmoniaSimulation`;
- AIMI Context affiche `Harmonia sim: ...`;
- `AIMI_Decisions.jsonl` exporte `adjustments.harmonia_simulation`;
- Auditor recoit `physiological_tree` et `harmonia_simulation`;
- Advisor porte `harmoniaSimulation` dans `AdvisorContext` et `AdvisorReport`;
- Meal Advisor recoit Harmonia comme contexte limite a `insulin_relevant_notes`.

La simulation reste explicitement separee de la pompe reelle avec `simulation_only=true` et `applies_to_pump=false`.

Un second lot branche Harmonia comme chemin production basal-first:

- `DetermineBasalAIMI2` selectionne `HARMONIA_PRODUCTION_BASAL_FIRST` au point final unique avant `setTempBasal`;
- T3C natif reste prioritaire et empeche la double decision;
- Harmonia perd si une autorite SMB ou une demande SMB existe deja;
- Harmonia perd face a sport/activity, post-hypo, meal conflict critique, maxIOB, surveillance IOB et LGS/predictive hypo final;
- `AIMI_Decisions.jsonl` exporte `adjustments.harmonia_production` avec `READY`, `APPLIED`, `BLOCKED` ou `SKIPPED`.

Cette branche n'ajoute aucune autorite SMB et n'ecrit aucune preference utilisateur.

Voir `docs/aimi-harmonia-simulation-branch.md`.

## 14. Vision produit vs etat code (double verification 2026-06)

### Intention produit (ce que Harmonia doit faire)

Harmonia est le moteur qui **harmonise** les signaux disperses (physio, repas latent, UAM, memoire hyper/hypo, wearables) en un **arbre physiologique** deploye a chaque tick, puis en une **decision virtuelle** qui sert de **seconde verification** avant d'appliquer une posture insulinique. Trois objectifs clinques :

1. **Repas non declare** : attraper une montee glycemique sans COB declare et la traiter avec la bonne posture (support repas vs basal-first vs observation), sans sur-reagir.
2. **Stabilisation** : eviter yoyo et hypo par sur-correction en freinant l'agressivite quand le tronc/branches signalent fragilite, post-hypo, activite ou epuisement.
3. **Harmonisation** : donner a Auditor, Advisor, RBT et TBR final un langage commun (`physiological_tree`, `harmonia_simulation`, `harmonia_production`).

### Ce qui est aligne aujourd'hui

| Objectif | Mecanisme code | Alignement |
|----------|----------------|------------|
| Arbre deploye au tick | `PhysiologicalTreeBuilder` lit `mealProb`, causal, phase, event memory → branches `meal`, `hypoRisk`, `digestion` | **Oui** |
| Repas latent dans l'arbre | `meal` branch : `max(mealProb, causal.mealConfidence)` ; feuille « declared vs undeclared » | **Partiel** — detection contextuelle |
| Simulation repas non declare | `MEAL_SUPPORT` si meal undeclared/declared rise **ou** H4 bridge (`DIGESTION_ACTIVE` + `meal_rise_confirmed` + BG>target+30) | **Partiel** — H4 bridge in `chooseAction` (2026-07-17); leaf→MealCorrection + production veto still open |
| Frein post-hypo / hypo | Blockers `hypo_or_recovery`, `low_or_falling_bg` ; production bloquee si `postHypoBlock` | **Partiel** — simulation + blocage, pas action yoyo dediee |
| Seconde verification | RBT canal `HARMONIA_PRODUCTION_BASAL_FIRST` ; production TBR si T3C/SMB inactifs | **Partiel** — basal-first residuel, pas confirmateur global |
| Stabilisation prefs 45 min | **TPO** (`AIMI_TRANSIENT_PREFERENCE_OVERLAY.md`) — orthogonal, partage `correctionFragilityScore` | **Oui** via TPO, pas via Harmonia seule |

### Ecarts critiques (correction trop / pas assez)

Les chemins **paralleles** a Harmonia restent souvent dominants sur le tick reel :

| Chemin | Role | Rapport a Harmonia |
|--------|------|-------------------|
| `SafetyPredictionTerminalsResolver.meal_rise_confirmed` | Uplift terminal / suppression hypo predictive ; souvent `true` avec COB=0 | **Bypass** Harmonia |
| `MealCorrectionContextResolver` | Priorite SMB/TBR repas non declare | **Bypass** — autorite SMB en amont |
| `BasalLearner` + `AdaptiveBasal` + Autodrive V3 | TBR elevee en BG « stable » 100–130 | **Aval** — Harmonia ne veto pas |
| `PostHypoProjectionCap` | Plafond rebound ; crash si BG > plafond | **Bug** — tick avorte au lieu de stabiliser |
| RBT chaos / `PostHypoDeliveryAuthority` | Dampen SMB, meal suppress | **Parallele** — stabilisation reelle |

**Verdict :** l'arbre **comprend** bien le repas non declare et la fragilite ; Harmonia **simule** `MEAL_SUPPORT` / `PROTECTIVE_REDUCTION` ; mais la **correction clinique** du repas non declare et du yoyo passe encore surtout par safety terminals, meal correction, RBT chaos et TPO — pas par une harmonisation unique au sommet.

### Lots recommandes (plan mis a jour)

| Lot | Objectif | Changement attendu | Statut |
|-----|----------|-------------------|--------|
| **H4 — Meal-rise bridge** | Fermer la boucle repas non declare | `chooseAction` : `MEAL_SUPPORT` bat `PROTECTIVE_REDUCTION` si `DIGESTION_ACTIVE` + `meal_rise_confirmed` + BG>target+30 + **Δ≥0.8** (`h4_meal_rise_bridge`) ; env `target_bg_mgdl` ; reste : veto production si `mealDeliveryPriority` incoherent ; feuilles → `MealCorrectionContextResolver` | **Partiel 2026-07-17** (bridge + Δ guard) |
| **H4b — Post-hypo rise exit (RBT)** | Liberer autorite sur montee agressive post-hypo | `PostHypoAggressiveRiseExit` + `PREDICTIVE_HYPO_AGGRESSIVE_RISE` → RBT SOFT (pas NONE) | **Done 2026-07-17** (device validation ouverte) |
| **H4c — Soft-meal basal channel** | Fermer `rbt_no_harmonia_channel` pendant montee | SOFT + `MEAL_SUPPORT` + `DIGESTION_ACTIVE` → canal `HARMONIA_PRODUCTION_BASAL_FIRST` ; bypass `smb_authority_active` ; clear Harmonia `POST_HYPO` block on aggressive-rise exit | **Done 2026-07-17** (device validation ouverte) |
| **H5 — Stabilisation yoyo** | Action `STABILIZE` / renforcer `PROTECTIVE_REDUCTION` | Brancher `correctionFragilityScore`, `postHyperExhaustion`, episodes RBT CHAOTIC ; rampe max hausse basale si fragilite | Ouvert |
| **H6 — Harmoniseur** | Vraie 2e verification dans la cascade | MealCertainty CONFIRM sync ; Auditor payload + prompts ; SafetyNet soft-landing veto — [AIMI_DECISION_CASCADE_ROADMAP.md](AIMI_DECISION_CASCADE_ROADMAP.md) D2 | **Done 2026-07-18** (device validation ouverte) |
| **H7 — Capteur tick** | Blockers sensor reels | Passer `sensorAgeMin` / `sensorNoise` reels dans l'environnement tick (aujourd'hui souvent 0 en loop) | Partiel (telemetry wired; validate field) |
| **H0 — Bug P0** | Robustesse post-hypo | `PostHypoProjectionCap` : si `ceiling < bg+5`, skip cap (eviter crash tick) | Ouvert |

Documents a maintenir synchronises : `aimi-harmonia-simulation-branch.md` (matrice bypass), `AIMI_TRANSIENT_PREFERENCE_OVERLAY.md` (§12 TPO ↔ Harmonia).
