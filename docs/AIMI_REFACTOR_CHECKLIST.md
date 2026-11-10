# AIMI refactor — check-list d’avancement

Dernière mise à jour : Phase 3 gouvernance adaptive basal **A0–A3 faits**, **A4 hors périmètre** (100 % téléphone, pas d’online / cloud). Suite du plan = **Phase 2** (P1 tags restants, P2 découpe, P3 `runBlocking`). Phase 2 **P1** quasi complet (tags `logDecisionFinal` sauf 3 lourds) ; **P2** amorcé (`CompressionReboundGuard`) ; P3/P4 backlog inchangé.

## Légende

- **Fait** — dans le dépôt, comportement métier inchangé dans le périmètre concerné  
- **Partiel**  
- **À faire**  
- **Décision** — nécessite une règle produit / clinique écrite  

---

## Sécurité & hypo

| Item | Statut |
|------|--------|
| `HypoThresholdMath` | Fait |
| `HypoGuard` + `HighBgOverride` aligné + tests | Fait |
| Logique LGS / bruit → `safety/LgsSafetyTriage.kt` + `LgsSafetyTriageTest` ; `trySafetyStart` = wrapper | Fait |

## Prédictions

| Item | Statut |
|------|--------|
| `PredictionSanity` + tests | Fait |
| `minPredictedAcrossCurves` → `PredictionCurveMath` + tests | Fait |

## Stacking & SMB

| Item | Statut |
|------|--------|
| `InsulinStackingSignals` + tests | Fait |
| `clampSmbToMaxSmbAndMaxIob` + tests | Fait |
| `capSmbDose` → `safety/CapSmbDose.kt` + tests | Fait |

## Circadien (SMB / sensibilité heure)

| Item | Statut |
|------|--------|
| Polynôme unifié `circadianSensitivityHourly` + tests vs formule legacy SMB | Fait |
| Variable `circadianSmb` (jamais lue) supprimée — aucun impact décisionnel | Fait |

## `DetermineBasalAIMI2` — structure & perf

| Item | Statut |
|------|--------|
| Inventaire `runBlocking` | Fait — KDoc sur `plugins/aps/.../DetermineBasalInvocationCaches.kt` + sites non couverts |
| Cache `calculateDaily(-24,0)` par invocation `determine_basal` | Fait |
| Autres `runBlocking` (DB, HC, …) : cache TTL ou async | Partiel — caches stats + **warmup** : COB futur / âge dernier carb / notes 4h regroupés en **un** `runBlocking` ; reste HC, boluses, site, etc. |
| Découpe continue du gros fichier | Partiel — caches d’invocation → `DetermineBasalInvocationCaches.kt` ; garde **compression rebound** → `safety/CompressionReboundGuard.kt` + tests ; suite par domaines (SMB finalize, autodrive, …) |
| `logDecisionFinal` → phases (résumé → ML/gov → TICK) | Fait |
| `updateLearning` aussi sur chemin nominal ? | **Décision A (produit)** — learning **uniquement** quand `logDecisionFinal` s’exécute (~sorties notables) ; **pas** sur le chemin nominal (plus safe, moins de bruit d’apprentissage). |
| Implémentation « learning sur nominal » | **N/A** (hors périmètre tant que décision A reste la règle). |

## Qualité tests

| Item | Statut |
|------|--------|
| `:plugins:aps:testFullDebugUnitTest` (régressions tests corrigées) | Fait |
| Tests scénario / golden moteur (branches clés) | **Partiel (cible P1)** — harnais partagé `DetermineBasalAimiScenarioTestHarness` ; `DetermineBasalAimiLogDecisionFinalScenarioTest` : `SAFETY`, `TRAJ_SAFETY`, `HARD_BRAKE`, `COMPRESSION`, `MEAL_ADVISOR`, `AUTODRIVE_V3`, `DRIFT_TERMINATOR`, `MAX_IOB` (+ learning 1×) ; + `DetermineBasalInvocationCachesTest`, `DetermineBasalAimiPerInvocationCacheTest` (nominal + `STALE_DATA`), exercice std/T3c, `TherapySportDetectionTest`, `CompressionReboundGuardTest`. **Reste** : tag `AUTODRIVE` (V2 seul sans V3) — instable dans le harnais léger sans refactor d’injectable horloge / prédiction. |

## Dual-brain / Sentinel

| Item | Statut |
|------|--------|
| `LocalSentinel.computeAdvice` dans `AuditorOrchestrator.auditDecision` | **Fait** (voir `AuditorOrchestrator.kt` ~L189+) |
| Doc `DUAL_BRAIN_STATUS.md` | **Fait** — aligné sur `AuditorOrchestrator` + Sentinel ; ancien plan « à implémenter » remplacé par état factuel |

## Hygiène

| Item | Statut |
|------|--------|
| TODO `// TODO eliminate` dans `DetermineBasalAIMI2` (doublons / commentaires morts) | Fait (reste : TODO `tbrMaxMode` / `tbrMaxAutoDrive` si tracking un jour) |
| Fichiers `.bak` | **Fait** — `*.bak` dans `.gitignore` ; **3 sauvegardes `.bak` retirées du dépôt** (AIMI + Comboctl) |

## Merge `dev` — `preferencesId` (API plugin, pas « nettoyage »)

| Item | Statut |
|------|--------|
| Clarification produit | **Note** — Après merge avec `dev`, **`PluginDescription` ne définit plus `preferencesId` / `PREFERENCE_SCREEN`** (API retirée du cœur AAPS). Les appels du type `.preferencesId(PluginDescription.PREFERENCE_SCREEN)` **ne peuvent pas être conservés** : le projet ne compile pas tant qu’ils restent. |
| Remplacement fonctionnel | **Note** — L’existence d’un écran de réglages pour un plugin repose sur **`getPreferenceScreenContent()`** (non nul) et donc sur **`hasPreferences()`** (menu / navigation Compose). Ce n’est **pas** une suppression de fonctionnalité pour les utilisateurs AIMI si le plugin expose toujours ce contenu. |
| **OttaiPlugin** | **Note** — Hérite de **`AbstractBgSourcePlugin`**, qui fournit déjà **`getPreferenceScreenContent()`** (écran Compose BG source, ex. upload NS). Retirer `preferencesId` ici = alignement compile sur la nouvelle API, **sans retirer** l’écran prefs tant que cette méthode reste en place. |
| **ApexPumpPlugin** (`:pump:apex`) | **Note** — Même contrainte compile sur `preferencesId` ; le module n’est **pas** une dépendance de `:app` dans ce fork. Un usage Apex + AIMI exigera une **migration complète** des prefs (Compose / clés) et de l’interface `Pump`, pas seulement de réintroduire l’ancienne ligne supprimée du core. |

---

## Fin livraison « refactor v1 » (périmètre produit)

**Inclus dans cette phase** : sécurité extraite + tests, caches d’invocation stats + warmup DB groupé, `logDecisionFinal` découpé, décision A documentée et couverte par tests moteur légers, hygiène `.bak`/doc dual-brain, pas de changement ONNX.

**Hors v1 (backlog technique / qualité)** : découpe massive `DetermineBasalAIMI2` par domaines, golden sur toutes les branches `logDecisionFinal`, refonte async générale des autres `runBlocking`, TODO pump `tbrMax*`.

---

## Phase 2 — prochaines étapes (priorisées)

| Priorité | Livrable | But |
|----------|----------|-----|
| **P1** | Golden / scénarios moteur par tag `logDecisionFinal` (2–3 tags / itération) | **Quasi fait** — tags couverts : `STALE_DATA`, `EXERCISE_LOCKOUT`, `T3C_EXERCISE_LOCKOUT`, `SAFETY`, `TRAJ_SAFETY`, `HARD_BRAKE`, `COMPRESSION`, `MEAL_ADVISOR`, `AUTODRIVE_V3`, `DRIFT_TERMINATOR`, `MAX_IOB`. **Backlog** : `AUTODRIVE` (V2 seul, sans V3) — test harnais non stabilisé (horloge locale + `tryAutodrive` / PKPD). |
| **P2** | Découpe `DetermineBasalAIMI2` — premier domaine (ex. SMB + finalize, ou autodrive) | **Amorcé** — `CompressionReboundGuard` ; prochaines extractions : SMB finalize / meal high IOB, ou bloc autodrive. |
| **P3** | `runBlocking` restants (HC pas / pas, boluses site, …) | Perf / réactivité ; chaque site = revue fraîcheur des données. |
| **P4** | TODO pump `tbrMaxMode` / `tbrMaxAutoDrive` | Uniquement si besoin produit / matériel. |

**Prérequis exercice (tests)** : `TherapySportDetectionTest` — note « sport » vs « marche » pour valider la logique therapy avant scénarios EXERCISE complets.

---

## Sorties précoces → `logDecisionFinal` (tags)

| Tag | Contexte (résumé) |
|-----|-------------------|
| `T3C_EXERCISE_LOCKOUT` | Verrou exercice T3c |
| `EXERCISE_LOCKOUT` | Verrou exercice |
| `STALE_DATA` | Données CGM > 12 min |
| `TRAJ_SAFETY` | Sécurité trajectoire |
| `SAFETY` | Pile safety / early |
| `MEAL_ADVISOR` | Meal advisor |
| `HARD_BRAKE` | Frein dur |
| `AUTODRIVE_V3` | Autodrive V3 |
| `AUTODRIVE` | Autodrive |
| `COMPRESSION` | Compression |
| `DRIFT_TERMINATOR` | Drift terminator |
| `MAX_IOB` | Plafond IOB |

Le chemin nominal « succès » complet **ne** passe **pas** par `logDecisionFinal` (comportement inchangé tant que la décision produit sur l’apprentissage basal n’est pas modifiée).

---

## ONNX

Aucun refactor listé ici ne modifie les chemins d’inférence ONNX ; garder cette discipline pour les prochains changements.

---

## Phase 3 — Gouvernance adaptive basal (on-device, hors cloud)

Feuille de route **100 % on-phone** : état patient riche, politique graduée, apprentissage personnel borné, garde-fous explicites.

| Étape | Livrable | Statut |
|-------|----------|--------|
| **A0** | Hystérésis entrée/sortie `HOLD_CONSERVATIVE` (seuil hypo 20 % → sortie &lt; 12 %) + palier **severe** (bg &lt; 70) vs **taux seul** (planchers / decay distincts) | **Fait** — `BasalNeuralLearner` + logs `BASAL_GOV` / APS ; tests `BasalNeuralLearnerGovernanceTest` |
| **A1** | Préférences bornées (seuils, planchers, decay) + doc clinique | **Fait** — `DoubleKey` gouvernance (16 clés dont anticipation A3, `dependency` = adaptive basal ON) ; `BasalNeuralLearner.effectiveGovernanceParams()` + contraintes croisées ; écran AIMI Adaptive Basal (Compose + XML) ; chaînes **EN** dans `core/keys` ; tests mock `Preferences.get(DoublePreferenceKey)` → `defaultValue`. **Doc** : résumés string = aide contextuelle ; avis clinique formel hors repo. |
| **A2** | Vecteur d’état enrichi (pente, IOB, confiance capteur) dans la fenêtre ou scoring parallèle | **Fait** — chaque échantillon gouvernance stocke `deltaMgDl`, `iobUnits`, `sensorNoise` ; taux hypo **pondéré** (`hypoRateGovernance`) + `meanGovernanceWeight` pour HOLD/hystérésis ; severe ne compte que si poids ≥ seuil ; `updateLearning(..., loopDeltaMgDl5m, sensorNoise)` branché depuis `DetermineBasalAIMI2` (decision final + T3c + `lastLoopCgmNoise`) ; test bruit vs fraction brute. |
| **A3** | Prédiction courte → modulation **anticipative** de l’intensité HOLD / decay | **Fait** — `shortMinPredBg` dans `LearningSample` / `updateLearning` ; relief sur fenêtre récente (`anticipationRelief`, `hypoGovernanceAdjusted`) pour entrée/sortie HOLD ; decay basal/agg adouci si relief ; `DetermineBasalAIMI2` passe `minPredictedAcrossCurves(rT.predBGs)` ; logs `BASAL_GOV` + APS ; `BasalNeuralLearnerGovernanceTest` ; helper tests fenêtre `addFirst` cohérent prod. **Préférences** (EN, `DoubleKey` + écran Adaptive Basal) : lookback échantillons, marge mg/dL au-dessus du seuil hypo, damp hypo, plafond adoucissement decay HOLD. |
| **A4** | Mise à jour **online** des paramètres personnels (pas de modèle lourd cloud), rollback + audit log | **N/A** — périmètre **uniquement on-device** sur téléphone : pas de couche online / sync cloud pour ce volet. Les mécanismes A0–A3 (fenêtre, prefs bornées, garde-fous HOLD, anticipation locale) couvrent la gouvernance hors cloud. |

**Note** : A2–A3 ont fixé les contrats `updateLearning` / fenêtre ; toute extension (ex. persistance ou audit **local** dédié) = nouvelle décision produit, pas A4 « online ».
