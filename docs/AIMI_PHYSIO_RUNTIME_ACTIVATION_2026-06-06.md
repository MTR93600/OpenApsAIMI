# AIMI — Implémentation runtime physio / UAM / PKPD / RBT

**Statut :** implémenté en code et validé par tests ciblés  
**Date :** 2026-06-06  
**Branche :** `dev_OAPSAIMI`  
**Docs liées :** [AIMI_PHYSIOLOGICAL_PHASE.md](AIMI_PHYSIOLOGICAL_PHASE.md), [AIMI_RECURSIVE_BELIEF.md](AIMI_RECURSIVE_BELIEF.md), [AIMI_MEAL_ABSORPTION_PHASE.md](AIMI_MEAL_ABSORPTION_PHASE.md), [PKPD_ABSORPTION_GUARD_AUDIT.md](PKPD_ABSORPTION_GUARD_AUDIT.md), [AIMI_PATIENT_MODE_REPLAY_CHECKLIST_2026-06-06.md](AIMI_PATIENT_MODE_REPLAY_CHECKLIST_2026-06-06.md)

---

## 1. Objet

Ce document résume **ce qui a été réellement codé** dans la passe qualité physio récente, **sans changer la grammaire de nommage existante du runtime**.

Il répond à trois questions :

1. Quelles briques ont été ajoutées ou raccordées ?
2. Quelles **préférences existantes** pilotent ces briques ?
3. Quelle stratégie d’activation progressive est recommandée ?

---

## 2. Point important

### 2.1 Aucune nouvelle préférence utilisateur n’a été ajoutée

La passe a été conçue pour **réutiliser les options déjà présentes** dans `OpenAPSAIMIPlugin.kt`, afin d’éviter :

- une deuxième couche de réglages redondants ;
- une divergence entre les noms documentés et les clés runtime ;
- un risque d’activation partielle mal comprise.

### 2.2 La doc décrit les noms runtime exacts

Les noms décrits ici sont volontairement alignés sur les structures déjà présentes dans le code :

- `PhysioLatentState`
- `UamHypothesisState`
- `PatientStateSnapshot`
- `PatientModeOrchestrator`
- `PhysiologicalStressMask`
- `RecursiveBeliefAuthorityGate`
- `ReplayQualityExport`
- `SmbRefinementFeatureSchema`

---

## 3. Ce qui a été codé

### 3.1 Lot 1 — Export qualité et replay

Une couche d’export qualité/replay a été consolidée pour rendre visibles les décisions runtime.

Fichier principal :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/quality/ReplayQualityExport.kt`

But :

- exposer les états utiles au replay ;
- rendre lisible l’application effective de l’autorité RBT ;
- tracer les raisons de blocage ou de limitation.

### 3.2 Lot 2 — Stress mask physio réellement branché

Le stress physiologique n’est plus laissé à un masque vide côté Autodrive.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/autodrive/learning/PhysiologicalStressMaskBuilder.kt`

Intégration principale :

- `DetermineBasalAIMI2.kt`

But :

- alimenter `MechanismAttentionGate` avec un masque réel ;
- mieux moduler les estimations en contexte de stress / récupération / contexte hormonal.

### 3.3 Lot 3 — État latent physio partagé

Un état latent commun a été introduit pour éviter que chaque moteur interprète le patient avec sa propre lecture isolée.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/PhysioLatentState.kt`

Champs métiers principaux :

- `mealProb`
- `endogenousGlucoseDrive`
- `circadianSiFactor`
- `transientResistanceProb`
- `sleepDebtScore`
- `sensorConfidence`

But :

- fournir une sémantique commune à UAM, PKPD, Autodrive, RBT et qualité/replay.

### 3.4 Lot 4 — Prior circadien individualisé repas

Le prior horaire repas a été amélioré en direction d’un profil plus individualisé.

Briques clés :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/CircadianMealProfileStore.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/MealAbsorptionPhaseEngine.kt`

But :

- réduire les faux repas dawn / cortisol ;
- conserver la détection des vrais repas matinaux ;
- sortir d’une logique trop dépendante d’horaires fixes.

### 3.5 Lot 5 — UAM multi-hypothèses

La logique UAM ne reste plus limitée à une lecture quasi binaire repas / pas repas.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/UamHypothesisState.kt`

Hypothèses suivies :

- repas ;
- drive endogène / dawn ;
- stress ;
- rebond post-hypo ;
- late-fat.

But :

- mieux arbitrer les montées ambiguës ;
- éviter les faux positifs repas sur un contexte hormonal ou de contre-régulation.

### 3.6 Lot 6 — PKPD, poids et état physio

La PKPD a été reconnectée à des signaux plus physiologiques que de simples caps de sécurité.

Briques principales :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PkPdIntegration.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/autodrive/estimator/ContinuousStateEstimator.kt`

But :

- mieux représenter la dynamique d’absorption et de récupération ;
- tenir compte du poids et du contexte physio dans les ajustements.

### 3.7 Lot 7 — Raffinement SMB ML aligné sur la physiologie

Le pipeline `modelUAM.tflite -> refine()` a été mieux formalisé et sécurisé.

Briques principales :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ml/SmbRefinementFeatureSchema.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ml/AimiSmbTrainer.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/UamInputSchemaValidator.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/AimiModelHandler.kt`

But :

- aligner les features SMB sur `PhysioLatentState` ;
- conserver la compatibilité CSV historique ;
- bloquer proprement une exécution TFLite si le schéma d’entrée diverge.

### 3.8 Lot 8 — Autorité RBT progressive

L’autorité Recursive Belief n’est plus un simple booléen implicite.

Brique clé :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/recursive/RecursiveBeliefAuthorityGate.kt`

Modes effectifs :

- `NONE`
- `SOFT`
- `HARD`

But :

- empêcher une autorité trop précoce ;
- garder un fonctionnement shadow même si la préférence d’autorité est activée ;
- moduler le lift HTR / RBT via `liftBlend` selon la qualité du contexte.

### 3.9 Couche produit — état patient unifié

Une couche produit explicite a été ajoutée au-dessus des briques physio existantes.

Briques clés :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateSnapshot.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientModeOrchestrator.kt`
- `DetermineBasalAIMI2.kt`

Ce qui est désormais produit par tick :

- un `PatientStateSnapshot` qui agrège phase physio, phase d’absorption repas, signaux latents, hypothèse UAM dominante et intention utilisateur active ;
- une décision `PatientModeOrchestrator.Decision` qui choisit un mode haut niveau ;
- une stratégie runtime dérivée, exportée dans `AIMI_Decisions.jsonl`.

Modes actuellement câblés :

- `STABLE_BASELINE`
- `FAST_MEAL`
- `PROLONGED_MEAL`
- `DAWN_ENDOGENOUS`
- `POST_HYPO_RECOVERY`
- `STRESS_RESISTANCE`
- `EXERCISE_AFTERBURN`
- `POOR_SLEEP_DAY`
- `ABSORPTION_UNCERTAIN`

But :

- donner à tous les moteurs une même lecture produit du patient ;
- éviter qu’un repas, un dawn, un post-hypo ou une récupération d’effort soient traités comme des cas équivalents ;
- rendre l’explication runtime beaucoup plus lisible.

### 3.10 Alignement ML sur le mode patient

Le raffinement SMB apprend maintenant sur un langage plus cohérent avec le runtime.

Dans `SmbRefinementFeatureSchema`, trois features supplémentaires ont été ajoutées :

- `patientModeMealBias`
- `patientModeProtectionBias`
- `contextIntentConfidence`

Effet :

- le trainer CSV asynchrone peut apprendre un comportement plus aligné avec le mode produit ;
- les anciens CSV restent compatibles grâce aux valeurs neutres de fallback ;

### 3.11 Surface clinique runtime

L’écran `AIMI Context` expose maintenant la lecture AIMI du corps à partir des structures runtime existantes.

Point d’entrée UI :

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/context/ui/ContextActivity.kt`

Ce qui est affiché :

- le `patient_mode` courant ;
- la stratégie insulinique suggérée ;
- les signaux dominants ;
- les raisons principales ;
- la fraîcheur de la dernière lecture.

But :

- rendre l’état patient lisible sans ouvrir les logs ;
- donner une explication clinique compacte de la posture AIMI ;
- préparer la validation replay / go-no-go sur un support visible en UI.

### 3.12 Rafraîchissement live du mode patient (2026-06-06)

**Problème corrigé :** la carte *Current AIMI Understanding* ne se mettait à jour que sur les branches Autodrive V3 / RBT (souvent perçu comme « seulement après un SMB »). Entre deux ticks, steps / FC / contexte utilisateur ne poussaient pas l’UI.

**Architecture actuelle :**

| Composant | Rôle |
|-----------|------|
| `PatientStateRuntimeRepository` | Snapshot in-memory + `SharedFlow` pour push UI |
| `PatientStateLoopCache` | Derniers outputs loop (phase, repas, latent, contexte) |
| `PatientStateRuntimeRefresher` | Rebuild mode patient entre ticks |
| `PhysioLiveDigest` | Steps, FC, activité, dette sommeil pour l’UI et Hormonitor |

**Quand le mode patient est publié :**

1. **Chaque tick loop** — fin de `runAdvancedPredictionsAndPredPipePrep()` → `publishPatientStateAfterPhysiologyRefresh()` (plus de `clear()` au début du tick).
2. **Signaux corps entre ticks** — `HealthContextRepository` si steps / FC / activité changent.
3. **Contexte utilisateur** — `ContextManager` à l’ajout, suppression ou prolongation d’intent.

**UI Context (`ContextActivity`) :**

- section *Live body signals* (activité, steps/15 min, FC) ;
- jauges Meal / Endogenous / Resistance / **Thermal** / Sensor ;
- section **Thermal rhythm** (narrative + delta vs baseline) ;
- rafraîchissement immédiat via `PatientStateRuntimeRepository.updates` ;
- à l’**ouverture / reprise** de l’écran : `healthContextRepository.fetchSnapshot()` sur IO (commit `29e42fcec6`) ;
- suffixe *Updated … · live body signals* ou *· user context* selon la source.

**Export Hormonitor — historique `1.2.0` → `1.3.0`, schéma courant `1.4.0` :**

Bloc `patient_story` dans `AIMI_HORMONITOR_event_stream_v1.jsonl` :

- `patient_mode`, `patient_mode_confidence`, `patient_strategy_hint`
- `patient_narrative`, `patient_reason_codes`
- `physio_live` (steps, FC, activité, dette sommeil, champs thermiques)
- `thermal_belief` (**1.3.0** — voir § 3.13)

Fichiers :

- `plugins/aps/.../patient/PatientStateRuntimeRefresher.kt`
- `plugins/aps/.../patient/PhysioLiveDigest.kt`
- `plugins/aps/.../physio/AimiHormonitorStudyExporterMTR.kt` (`SCHEMA_VERSION = 1.4.0`; ajout causal-family, compatible avec les blocs `1.2.0`/`1.3.0`)
- le modèle interne sauvegardé sera naturellement recréé si la dimension d’entrée diverge.

### 3.13 Couche Thermal Belief — rythme thermique wearable (2026-06-06)

**Principe produit :** lire l’**évolution** de la température cutanée vs une baseline personnelle (nuits 2h–5h), pas une fièvre absolue. Sources : **Garmin** et **Oura** via Health Connect.

Briques :

| Fichier | Rôle |
|---------|------|
| `physio/thermal/ThermalBeliefEngine.kt` | Hypothèses, indices inflammation/récupération, narrative |
| `physio/thermal/ThermalBaselineStore.kt` | Baseline nocturne personnelle |
| `physio/AIMIPhysioDataRepositoryMTR.kt` | Lecture HC `SkinTemperatureRecord` + `BasalBodyTemperatureRecord` |
| `physio/AIMIHealthConnectPermissions.kt` | Permissions skin + BBT |
| `physio/HealthContextRepository.kt` | Fetch async + refresh UI si thermal change |

**Plancher de bruit (deadband) :**

| Constante | Valeur |
|-----------|--------|
| `NOISE_FLOOR_DELTA_C` | 0,03 °C — variations &lt; 0,03 ignorées pour indices/hypothèses |
| `NOISE_FLOOR_SLOPE_C_PER_H` | 0,01 °C/h — pentes sous ce seuil → 0 |
| Mise à jour baseline nocturne | &gt; 0,03 °C vs dernière médiane |

Les ticks 0,01–0,02 °C typiques des montres ne doivent **pas** déclencher de dérive inflammatoire.

**Hypothèses :** `BASELINE_STABLE`, `INFLAMMATORY_DRIFT`, `RECOVERY_COOLING`, `CYCLE_BBT_RISE`, `HYPO_SYMPATHETIC_COOLING`, `FATIGUE_DYSREGULATION`, `DATA_PENDING`.

**Impact RBT et décisions (indirect, pas de dose directe) :**

1. **`PhysioLatentState`** — `inflammationIndex` / `recoveryBurden` thermiques fusionnés dans `transientResistanceProb` et recovery.
2. **`PatientModeOrchestrator`** — raisons `THERMAL_INFLAMMATORY_DRIFT`, `THERMAL_CYCLE_BBT`, `THERMAL_RECOVERY_COOLING` → modes `STRESS_RESISTANCE` ou `POOR_SLEEP_DAY` avec `CONSERVATIVE_OBSERVE`.
3. **`RecursiveBeliefAuthorityGate`** — modes protecteurs plafonnent l’autorité RBT à `SOFT` ; `protectionBias` abaisse le `readinessScore` et le `liftBlend`.
4. **Pas de multiplicateur SMB dédié à la température** — la dose suit l’arbitrage belief tree + ILG après lecture du corps.

**wcycle :** BBT + phase cycle (lutéale, ovulation, menstruation) pour distinguer physiologie hormonale et stress infectieux.

**Aucune nouvelle préférence** — activer les permissions Health Connect skin temperature + basal body temperature.

### 3.14 Fix steps/15m bloqués à zéro (2026-06-06)

**Symptôme signalé :** *Live body signals* affichait `0 steps/15m` alors que la FC était à jour (ex. `64 bpm`).

**Cause :** sources type **Garmin HTTP** n’écrivent que `steps5min` (delta) ; `steps15min` reste 0. `UnifiedActivityProviderMTR` traitait ce 0 comme un total valide au lieu de sommer les buckets 5 min.

**Correctif :**

| Fichier | Changement |
|---------|------------|
| `steps/UnifiedActivityProviderMTR.kt` | `stepsFromPrefilledWindowFields` : fenêtre > 5 min avec valeur 0 → fallback bucket ; logique extraite dans `resolveStepsTotalSince()` |
| `context/ui/ContextActivity.kt` | `refreshPhysioSnapshot()` au `onResume` |
| `UnifiedActivityProviderMTRTest.kt` | Garmin HTTP, HC prefilled, idle |

**Chaîne données :** DB `[SC]` → `HealthContextRepository.fetchSnapshot()` → `PhysioLiveDigest` → UI Context + Hormonitor `physio_live.steps_last_15m`.

---

## 4. Préférences existantes qui pilotent le runtime

### 4.1 Détection UAM / repas

Préférence existante :

- `ApsUseUam`

Fonction :

- active la logique UAM utilisée par la détection repas et ses hypothèses.

Référence d’exposition UI :

- `OpenAPSAIMIPlugin.kt` dans l’écran AIMI principal.

### 4.2 Autodrive / HTR / Recursive Belief

Préférences existantes :

- `OApsAIMIautoDrive`
- `OApsAIMIautoDriveActive`
- `OApsAIMIautoDriveAuthoritative`
- `OApsAIMIHyperTrajectoryRelease`
- `OApsAIMIHyperTrajectoryReleaseAggressive`
- `OApsAIMIRecursiveBeliefShadow`
- `OApsAIMIRecursiveBeliefAuthority`
- `OApsAIMIRecursiveBeliefWavelet`

Fonction :

- pilotent l’activation d’Autodrive ;
- autorisent HTR ;
- activent le shadow ou l’autorité RBT ;
- la nouvelle gate runtime peut malgré tout limiter l’autorité effective à `NONE` ou `SOFT`.

### 4.3 PKPD / dyn ISF / surveillance IOB

Préférences existantes :

- `OApsAIMIPkpdEnabled`
- `OApsAIMIPeakGovernorEnabled`
- `OApsAIMIDynIsfTrajectoryTuningEnabled`
- `OApsAIMIDynIsfTrajectoryShadowOnly`
- `OApsAIMIPkpdPragmaticReliefEnabled`
- `OApsAIMIIobSurveillanceGuard`

Fonction :

- activent la couche PKPD ;
- autorisent l’apprentissage / gouvernance du peak ;
- activent l’adaptation DynISF trajectoire ;
- laissent la surveillance IOB jouer comme garde-fou.

### 4.4 Raffinement ML

Préférence existante :

- `OApsAIMIMLtraining`

Fonction :

- permet au trainer CSV asynchrone de compléter le résultat du modèle UAM de base ;
- ne remplace pas `modelUAM.tflite`, il l’affine ;
- exploite désormais aussi le mode patient et la confiance d’intention comme contexte de raffinement.

---

## 5. Préférences existantes non ajoutées mais importantes à connaître

Certaines briques codées restent dépendantes de l’environnement fonctionnel déjà présent :

- `AimiPhysioAssistantEnable`
- `AimiPhysioSleepDataEnable`

## 6. Explication runtime disponible

Le runtime exporte maintenant, sans nouvelle préférence :

- `adjustments.patient_state`
- `adjustments.patient_mode`
- `adjustments.replay_quality.patient_mode`
- `adjustments.replay_quality.patient_strategy_hint`
- `adjustments.replay_quality.context_intent_*`

En pratique, cela permet de voir dans le JSON si AIMI a interprété le tick comme :

- un repas rapide ;
- un repas prolongé ;
- un drive endogène de type dawn ;
- une récupération post-hypo ;
- une phase d’effort / afterburn ;
- une absorption incertaine.
- `AimiPhysioHRVDataEnable`
- `AimiPhysioLLMAnalysisEnable`
- `AimiPhysioDebugLogs`

Elles ne font pas partie d’une nouvelle passe de préférences, mais elles influencent la richesse du contexte physio disponible.

---

## 6. Recommandation d’activation

### 6.1 Profil recommandé pour première mise en route

- `ApsUseUam = ON`
- `OApsAIMIautoDrive = ON`
- `OApsAIMIautoDriveActive = ON`
- `OApsAIMIHyperTrajectoryRelease = ON`
- `OApsAIMIRecursiveBeliefShadow = ON`
- `OApsAIMIRecursiveBeliefAuthority = OFF` au départ
- `OApsAIMIPkpdEnabled = ON`
- `OApsAIMIPeakGovernorEnabled = ON`
- `OApsAIMIIobSurveillanceGuard = ON`
- `OApsAIMIMLtraining = ON` seulement si l’on veut le raffinement CSV actif

### 6.2 Deuxième étape après observation terrain

Si les premiers cycles sont propres :

- laisser `RecursiveBeliefShadow = ON`
- activer `OApsAIMIRecursiveBeliefAuthority = ON`

Important :

- même avec la préférence d’autorité activée, `RecursiveBeliefAuthorityGate` peut rester en `NONE` ou `SOFT` selon le contexte ;
- l’autorité effective n’est donc pas strictement équivalente à la préférence utilisateur.

---

## 7. Ce qu’il faut surveiller après activation

### 7.1 Contexte repas / dawn

Vérifier :

- absence de faux repas au réveil ;
- maintien d’une bonne détection sur vrai petit-déjeuner ;
- absence de sur-réaction sur cortisol / drive endogène.

### 7.2 Contexte correction / stacking

Vérifier :

- pas de rafale SMB injustifiée après une première correction ;
- comportement cohérent avec `IOB surveillance` ;
- récupération propre après hypo ou rebond post-hypo.

### 7.3 Contexte RBT / HTR

Vérifier :

- que `authority_effective` passe bien par `NONE -> SOFT -> HARD` seulement quand le contexte est crédible ;
- que les raisons de blocage ou de limitation remontent bien dans l’export qualité.

### 7.4 Raffinement SMB ML (`AimiSmbTrainer`) — distinct de `modelUAM.tflite`

AIMI a **deux** pipelines ML indépendants :

| Pipeline | Fichier / pref | Rôle | Impact de cette passe |
|----------|----------------|------|------------------------|
| **UAM TFLite** | `modelUAM.tflite` | Prédiction repas / SMB UAM | **Features inchangées** — ajout d’un garde-fou `UamInputSchemaValidator` (mismatch → SMB UAM = 0 + log) |
| **Raffinement CSV** | `AimiSmbTrainer`, pref `OApsAIMIMLtraining` | Affine le SMB **après** le calcul AIMI (± min(0.05 U, 25 %)) | **Vecteur étendu** 11 → **15** entrées |

#### Vecteur `AimiSmbTrainer` (runtime et entraînement)

```
[10 base] + [4 latent PhysioLatentState] + [1 trendIndicator] = 15 floats
```

| Groupe | Colonnes | Source |
|--------|----------|--------|
| Base (obligatoires) | `bg`, `iob`, `cob`, `delta`, `shortAvgDelta`, `longAvgDelta`, `tdd7DaysPerHour`, `tdd2DaysPerHour`, `tddPerHour`, `tdd24HrsPerHour` | tick loop (inchangé) |
| Latent (nouvelles) | `mealProb`, `endogenousGlucoseDrive`, `circadianSiFactor`, `transientResistanceProb` | `PhysioLatentState` (même état que PKPD / RBT) |
| Meta | `trendIndicator` | calculé à l’inférence ; approximé offline à l’entraînement |

Schéma centralisé : `SmbRefinementFeatureSchema.kt`. CSV enrichi : `logDataMLToCsv` → fichier `oapsaimiML2_records.csv` (ou fallback du même nom).

#### Compatibilité et comportement sans action utilisateur

- **Ancien modèle sauvegardé (11 entrées)** : `refine()` détecte `features.size != 15` → retourne le SMB prédit **sans** correction ML. Pas de crash.
- **Anciennes lignes CSV sans colonnes latent** : à l’entraînement, valeurs **neutres** injectées (`mealProb=0`, `endogenous=0`, `circadianSi=1.0`, `resistance=0`).
- **Header CSV** : les nouvelles colonnes latent apparaissent dès le prochain enregistrement ; l’entraînement async exige toujours les 10 colonnes base + `smbGiven`.
- **Loop principal** : non modifié — le raffinement ML ne s’applique que si `mlRefined > predictedSMB && bg > 150 && delta > 5`.

#### Checklist si `OApsAIMIMLtraining = ON`

1. Laisser tourner plusieurs jours — le CSV se remplit avec les 4 colonnes latent.
2. Attendre **≥ 200 nouvelles lignes** et un cycle d’entraînement (rate limit **6 h** entre runs).
3. Log attendu : `AimiSmbTrainer: Model trained and saved successfully (15 inputs)` (ou équivalent avec `INPUT_SIZE=15`).
4. Jusqu’au retrain : raffinement ML **inactif** (fallback transparent sur `calculateSMBFromModel()`).
5. Ne pas confondre avec **UAM TFLite** — celui-ci n’a pas été retrainé ; seul le petit réseau CSV est dimensionné sur la physio partagée.

#### Signaux d’alerte

| Symptôme | Interprétation |
|----------|----------------|
| Log `UAM input schema mismatch` | TFLite vs vecteur UAM — SMB UAM forcé à 0 |
| Pas de log « Model trained » après semaines | CSV insuffisant, colonnes manquantes, ou circuit breaker ML (6 h après 3 échecs) |
| Raffinement jamais visible | Normal tant que modèle 11 entrées non régénéré ; ou conditions `bg/delta` non remplies |

---

## 8. Logs et exports à regarder

Les points d’observation importants sont :

- `ReplayQualityExport`
- `RecursiveBeliefExport`
- `authority_gate`
- `physiological_phase`
- `recursive_authority_gate`
- `rbtMode`
- `authorityRequested`
- `authorityEffective`
- `authorityReadinessScore`
- `authorityGateReasons`

Ces champs sont les meilleurs indicateurs pour comprendre pourquoi une autorité RBT a été appliquée, limitée ou bloquée.

---

## 9. Résumé opérationnel

### 9.1 Ce qui est vrai

- les briques physio/UAM/PKPD/RBT/ML ont été intégrées ;
- elles réutilisent les préférences existantes ;
- aucune nouvelle option cachée n’est requise pour les activer.

### 9.2 Ce qui n’est pas vrai

- il n’y a pas de “nouveau mode magique” à activer ;
- `RecursiveBeliefAuthority = ON` ne garantit pas une autorité hard permanente ;
- le trainer CSV ne remplace pas le modèle UAM TFLite.

### 9.3 Verdict pratique

Pour utiliser la passe récente :

1. activer les préférences existantes utiles ;
2. démarrer avec `RecursiveBeliefShadow` ;
3. n’ouvrir `RecursiveBeliefAuthority` qu’après observation ;
4. suivre les exports qualité sur les premiers cas dawn / repas / correction.
