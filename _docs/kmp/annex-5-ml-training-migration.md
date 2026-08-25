# Annexe 5 — Migration KMP du ML et des apprentissages AIMI

> Audit technique autonome du périmètre ML/apprentissage de `dev_OAPSAIMI`, révision
> `06e7bc5021ca8fdd976505d1fefb03cc88681c19` du 24 août 2026.
>
> Périmètre : TFLite UAM, réseau neuronal Kotlin, raffinement SMB, apprentissage basal/T3C,
> BasalLearner, UnifiedReactivity, Autodrive Data Lake/backfill/Attention Gate/online learning,
> persistance des modèles, concurrence et ordonnancement. Les décisions cliniques finales de
> Harmonia, RBT et du moteur basal sont traitées dans les autres annexes ; leurs entrées/sorties ML
> sont néanmoins recensées ici.

## 1. Verdict

Le ML d'AIMI est **portable sur iOS avec conservation fonctionnelle**, mais il n'est ni « gratuit à
porter » ni constitué d'un modèle unique.

Les conclusions de cette annexe sont les suivantes :

1. **Le modèle initial `modelUAM.tflite` ne doit pas être supprimé.** LiteRT/TensorFlow Lite existe
   sur iOS et peut être placé derrière un port KMP. Le même artefact de 4 504 octets peut être utilisé
   par Android et iOS.
2. **`AimiNeuralNetwork` ne remplace pas mécaniquement `modelUAM.tflite`.** Le premier possède une
   couche cachée avec LeakyReLU ; le second contient une normalisation, quatre couches `dense_20` à
   `dense_23`, des ReLU et une PReLU de sortie. Une conversion sans extraction exacte du graphe et des
   poids changerait la fonction.
3. **Les entraînements SMB et basal/T3C peuvent s'exécuter sur Kotlin/Native.** Leur algèbre est
   portable. Les systèmes de fichiers, JSON, horloges, atomiques, publication de modèles et tâches de
   fond ne le sont pas tels quels.
4. **Les learners événementiels peuvent continuer sans cadence WorkManager garantie.** BasalLearner,
   UnifiedReactivity et OnlineLearner sont déjà déclenchés par les ticks. L'entraînement SMB est aussi
   déclenché depuis la boucle. Les travaux lourds doivent devenir reprenables et opportunistes.
5. **L'état est un enjeu plus important que les mathématiques.** Plusieurs fenêtres et compteurs ne
   sont actuellement qu'en mémoire. Les terminaisons plus fréquentes d'une application iOS créeraient
   un comportement différent d'Android si ces états n'étaient pas persistés ou reconstruits.
6. **La parité visée doit porter sur les décisions et les modèles acceptés**, avec tolérances
   numériques internes, et non sur une identité bit à bit des poids entraînés par deux runtimes
   différents.

Verdict de réalisation : **GO conditionnel**, avec un premier portail obligatoire qui exécute le
modèle TFLite original sur un iPhone réel, fige les schémas de features et rejoue l'état séquentiel
des learners.

## 2. Quatre systèmes ML distincts

Le terme « ML AIMI » recouvre quatre chaînes qui ne doivent pas être fusionnées dans la migration.

| Chaîne | Fonction | Entraînement actuel | Effet sur la décision |
|---|---|---|---|
| UAM TFLite | Estimation SMB initiale | Externe au téléphone ; artefact embarqué | Actif, une inférence réelle dans `DetermineBasalAIMI2` |
| Réseau Kotlin SMB | Raffinement borné de l'estimation UAM | Sur l'appareil, CSV des décisions | Actif si un modèle valide est publié ; correction très bornée |
| Réseaux Kotlin basal/T3C | Multiplicateurs basal et agressivité T3C | Sur l'appareil, résultats BG réalisés à +30 min | Actif si modèles sains, sinon heuristiques |
| Autodrive Attention | Risque d'hypo à +60 min selon masque physiologique | Régression logistique sur Data Lake backfillé | Actif et défensif seulement si poids valides ; Autodrive est encore décrit comme shadow dans son moteur |

Deux autres mécanismes apprennent sans réseau neuronal :

- `BasalLearner` combine trois EMA sur 30 min, 6 h et 24 h ;
- `UnifiedReactivityLearner` apprend des facteurs 2 h/24 h et par segment horaire.

`OnlineLearner` Autodrive calcule actuellement un facteur de sensibilité, mais ce facteur est
**observé et non appliqué**. Le commentaire du moteur explique que la cible est une extrapolation
linéaire provisoire, non une trajectoire MPC. La migration ne doit surtout pas activer ce facteur par
accident.

## 3. Graphe de données réel

### 3.1 UAM TFLite : estimation initiale

```text
Tick AIMI
  └─ 18 Float32 dans cet ordre exact
       0 hourOfDay
       1 weekend
       2 bg
       3 targetBg
       4 iob
       5 delta
       6 shortAvgDelta
       7 longAvgDelta
       8 tdd7DaysPerHour
       9 tdd2DaysPerHour
      10 tddPerHour
      11 tdd24HrsPerHour
      12 recentSteps5Minutes
      13 recentSteps10Minutes
      14 recentSteps15Minutes
      15 recentSteps30Minutes
      16 recentSteps60Minutes
      17 recentSteps180Minutes
          │
          ▼
  sanitize NaN/Inf -> 0
          │
          ├─ cache SHA-256(features LE Float32), TTL 30 min, 1 000 entrées
          ▼
  modelUAM.tflite [1,18] Float32 -> [1,1] Float32
          │
          ▼
  troncature à 4 décimales, plancher à 0 ; erreur/absence -> 0
```

Contrat binaire audité :

| Artefact | Taille | SHA-256 dans `dev_OAPSAIMI` | Usage |
|---|---:|---|---|
| `app/src/main/assets/modelUAM.tflite` | 4 504 octets | `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092` | **Actif** |
| `app/src/main/assets/model.tflite` | 4 232 octets | `6e4b83500f0a90a9a95e984bde67f39bc85ee544f5e585ab720e9c08d2208cee` | Copié au démarrage, mais aucun appel d'inférence actif trouvé |

Le fallback `0` ne signifie pas « aucune dose finale » : le moteur possède ensuite ses propres
fallbacks et barrières. La parité doit donc vérifier toute la chaîne appelante, pas uniquement la
sortie du runtime TFLite.

### 3.2 Raffinement SMB Kotlin

Le raffinement emploie 21 entrées :

```text
10 bases
  bg, iob, cob, delta, shortAvgDelta, longAvgDelta,
  tdd7DaysPerHour, tdd2DaysPerHour, tddPerHour, tdd24HrsPerHour

4 états physiologiques latents
  mealProb, endogenousGlucoseDrive, circadianSiFactor, transientResistanceProb

3 états Patient Mode
  patientModeMealBias, patientModeProtectionBias, contextIntentConfidence

3 états causaux
  causalMealConfidence, causalProtectiveConfidence, causalLearningQuality

1 trendIndicator
```

Flux réel :

```text
modelUAM.tflite -> SMB initial
                       │
oapsaimiML2_records.csv│  cible = smbGiven
          │            │
          ▼            ▼
filtrage causal -> AimiNeuralNetwork(21, 8, 1)
                  -> sortie ML
                  -> delta borné à ±min(0,05 U, 25 % du SMB initial)
                  -> profil comportemental peut réduire encore ce clamp
                  -> mélange final avec predictedSMB historique
```

Gates de données existantes :

- `causalLearningQuality >= 0.52` ;
- `causalProtectiveConfidence < 0.86` ;
- rejet si `decisionConflictFlags` est non vide ;
- rejet si `correctionFragilityScore >= 0.72` ;
- rejet si épuisement post-hyper `>= 0.82` avec protection `>= 0.55` ;
- 200 nouvelles lignes avant une tentative, intervalle nominal de 6 h ;
- split chronologique 80/20, 300 époques ;
- sortie de la sonde dans `[0, 5] U` ;
- spread minimum 0,05 U sur les ancres BG ;
- MAE holdout au plus égale à 95 % du meilleur prédicteur constant.

**Train/serve skew actuel :** le runtime calcule `trendIndicator` avec davantage de contexte
(deltas, BG, IOB, sensibilité, COB, seuil BG, pas, fréquence cardiaque et profil). Le trainer ne
retrouve pas cette valeur dans le CSV et la réapproxime avec BG, IOB et deltas. Il faut ajouter au
CSV la valeur exacte servie au runtime, versionner le schéma et entraîner les nouvelles versions sur
cette colonne. Copier le code actuel sur iOS reproduirait le skew, mais ne constituerait pas une
parité ML démontrée.

### 3.3 Basal et T3C

Les deux têtes partagent 16 entrées :

```text
6 bases : bg, basal, accel, duraMin, duraAvg, iob
10 contextes : les 4 latents + 3 Patient Mode + 3 causaux du schéma SMB
```

La cible n'est plus `eventualBG`. Le parser joint chaque ligne à un BG réellement observé autour de
+30 min, dans une fenêtre de +20 à +45 min. Il rejette notamment :

- BG hors `[40, 400]` ou variation absolue supérieure à 150 mg/dL ;
- mouvement inférieur à 3 mg/dL hors deadband ;
- mouvement de signe incompatible avec la correction attendue ;
- bolus supérieur à 0,05 U ou COB supérieur à 5 g dans la fenêtre ;
- saut d'IOB supérieur au basal prévu + 0,25 U.

Les lignes historiques sans colonnes causales sont marquées `legacyUncensored`, pas assimilées à
des lignes prouvées propres.

| Tête | Minimum | Entraînement | Labels | Publication | Runtime |
|---|---:|---|---|---|---|
| Basal | 100 lignes utilisables | LR 0,0005, 200 époques, patience 20 | `[0,85 ; 1,35]` | sonde `[0,5 ; 2,0]`, spread 0,05, MAE/constant <= 0,95 | clamp bas 0,80, plafond préférence |
| T3C | 50 lignes utilisables | LR 0,001, 300 époques, patience 20 | `[0,5 ; 2,0]` | sonde `[0,3 ; 3,0]`, spread 0,05, MAE/constant <= 0,95 | clamp `[0,5 ; 2,0]` |

Le coordinateur exige 80 nouvelles lignes, limite les tentatives à une heure et persiste
`lastTrainMs` ainsi que `rowsAtLastTrain`. Il entraîne les deux têtes indépendamment de leur activation
dans les préférences, puis les recharge uniquement après publication.

### 3.4 Autodrive Attention

Le Data Lake écrit un schéma versionné de 20 colonnes. Les sorties futures sont laissées vides au
tick puis remplies depuis l'historique CGM :

```text
AutoDriveState + commandes raw/safe + engaged
        │
        ▼
autodrive_dataset.csv v2
        │ après 45–60 min
        ▼
Backfill : Future_BG_45m, Hypo_Occurred, Hyper_Occurred
        │ rétention 60 jours
        ▼
features = [mask HR, mask inflammation, mask hormonal, engaged]
target   = hypo dans l'heure
        │
        ▼
régression logistique équilibrée, 300 époques
        │
        ├─ holdout chronologique 20 %
        ├─ >= 500 lignes dans le trainer
        ├─ >= 20 positifs train et >= 5 holdout
        ├─ worker armé à >= 2 880 lignes labellisées
        └─ gain de log-loss >= 0,005 vs base et incumbent
        ▼
autodrive_attention_weights.json
        │
        ▼
Attention Gate : score > 0,5 -> SI multipliée de 1,0 à 1,5
                 score <= 0,5 -> 1,0, jamais de voie permissive
```

La correction du prior est importante : l'entraînement équilibre les classes autour d'un prior 50 %,
puis décale le biais sauvegardé vers le taux réel d'hypo. Le fichier conserve également le biais
équilibré pour comparer l'incumbent avec le candidat sur une base identique.

## 4. Inventaire fichier par fichier

Légende :

- **C0** : déplaçable en `commonMain` après nettoyage mineur ;
- **C1** : logique commune, dépendances plateforme à injecter ;
- **A** : implémentation `androidMain`/`iosMain` ;
- **R** : redesign d'état ou de concurrence requis avant parité ;
- **S** : shadow, diagnostic ou non décisionnel aujourd'hui.

### 4.1 Racine et runtime UAM

| Fichier | LOC | Rôle | État KMP | Travail requis |
|---|---:|---|---|---|
| `AimiModelHandler.kt` | 296 | Cycle de vie TFLite, cache, sanitize, statut | **A/R** | Séparer contrat commun et runtimes ; cache commun ; modèle/version explicites ; ne pas traduire le verrou réentrant avec un `Mutex` non réentrant |
| `UamInputSchemaValidator.kt` | 14 | Validation de la dernière dimension du tensor | **C0** | Ajouter dtype, sortie `[1,1]` et version du schéma |
| `aimiNeuralNetwork.kt` | 613 | MLP, AdamW, dropout, normalisation, sérialisation | **C1** | Math vers common ; sortir `File` et `org.json` ; figer PRNG et format modèle |
| `TrainingConfig.kt` | 39 | Hyperparamètres et seed `20260822` | **C0** | Sérialiser les paramètres qui influencent l'inférence et l'entraînement |
| `MainApp.kt` lignes 485–527 | — | Copie des deux assets TFLite au démarrage | **A/R** | Déplacer vers un installateur d'artefacts versionné ; politique copy-if-absent ou upgrade atomique décidée explicitement |
| `DetermineBasalAIMI2.kt` lignes 14982–15075 | — | Construction des features UAM/SMB et déclenchement trainer | **C1/R** | DTOs typés, schéma versionné, enregistrer le vrai `trendIndicator`, ne pas passer de `File` |

### 4.2 Package `ml`

| Fichier | LOC | Rôle | État KMP | Travail requis |
|---|---:|---|---|---|
| `ml/SmbRefinementFeatureSchema.kt` | 196 | Schéma 21 features et filtres causaux | **C0** | Rendre public au module, ajouter `schemaId`, unités et bornes |
| `ml/NeuralModelTrainer.kt` | 412 | Split, normalisation, probes, holdout, publication | **C1** | Remplacer `File` par `ModelArtifactStore` ; tests Native |
| `ml/AimiSmbTrainer.kt` | 286 | Cycle SMB, modèle mémoire, entraînement asynchrone | **C1/R** | Injecter scope/clock/store/logger ; persister high-water mark ; séparer `SKIPPED` de `FAILED` |
| `ml/AimiNeuralModelStore.kt` | 70 | `.tmp`/`.bak`, load finite, suppression | **C1/R** | Store atomique multiplateforme, manifeste/checksum, protection contre publications concurrentes |
| `ml/AimiSmbModelStore.kt` | 22 | Nom du modèle SMB | **C1** | Manipuler un identifiant d'artefact, pas un `File` |
| `ml/TrainingCircuitBreaker.kt` | 48 | 3 échecs, cooldown 6 h | **C1** | Horloge injectée ; atomiques common ou acteur ; décider si l'état traverse un redémarrage |

### 4.3 Package `learning`

| Fichier | LOC | Rôle | État KMP | Travail requis |
|---|---:|---|---|---|
| `learning/BasalAdaptiveMultiplier.kt` | 23 | Petit calcul borné | **C0** | Déplacement direct + tests common |
| `learning/ReactivityDaypart.kt` | 109 | Segments horaires et combinaison | **C0** | Injecter zone/heure civile au lieu d'une API JVM chez l'appelant |
| `learning/BasalLearner.kt` | 473 | EMA 30 min/6 h/24 h et événements hypo/hyper | **C1/R** | Ports clock/store/logger ; persister ou reconstruire buffers, fasting et latch post-hypo |
| `learning/UnifiedReactivityLearner.kt` | 846 | TIR/CV/hypo, 2 h/24 h, segments, persistence | **C1/R** | `HistoryRepository`, clock/zone/store ; ordonnancement déterministe des snapshots DB ; acteur mono-écrivain |
| `learning/BasalNeuralLearner.kt` | 1 113 | Inférence basal/T3C, heuristiques, governance, CSV | **C1/R** | Retirer Context/Environment/File ; persister/reconstruire governance ; publication atomique du snapshot de modèles |
| `learning/BasalMlTrainingCoordinator.kt` | 801 | Parser causal, labels +30 min, deux entraînements | **C1/R** | Store/history/scope/clock injectés ; checkpoints d'annulation ; schéma de dataset |
| `learning/BasalMlModelStore.kt` | 18 | Façade modèle basal/T3C | **C1** | Identifiants d'artefacts communs |
| `learning/AimiMlTrainingScheduler.kt` | 125 | WorkManager 1 h/6 h/24 h + bootstrap | **A/R** | Android conserve WorkManager ; iOS soumet des travaux durables sans promesse de cadence |
| `learning/BasalMlTrainerWorker.kt` | 33 | Worker Android | **A** | Reste `androidMain` ; l'action commune devient `runTraining(reason)` |
| `learning/BasalMlWorkerDelegate.kt` | 14 | Mapping résultat -> WorkManager | **A** | Reste Android ; mapping iOS distinct |

### 4.4 Package `autodrive/learning`

| Fichier | LOC | Rôle | État KMP | Travail requis |
|---|---:|---|---|---|
| `AutodriveDatasetSchema.kt` | 97 | Schéma CSV v2 et migration logique | **C0** | Ajouter parseur CSV robuste et fixtures de versions |
| `PhysiologicalStressMaskBuilder.kt` | 247 | Construit les 3 features physiologiques | **C0** | Retirer `java.util.Locale` du debug |
| `AutodriveAuditor.kt` | 66 | Score de santé/diagnostic | **C0** | Remplacer DI JVM au bord du module |
| `AutodriveDataLake.kt` | 191 | Ligne par tick, queue non bloquante | **C1/R** | `AppendOnlyDatasetStore`, clock/formatter ; queue bornée persistable ; pas d'I/O sur le chemin dose |
| `AutodriveDatasetLock.kt` | 66 | Verrou réentrant et try-lock | **R** | Acteur de dataset ou `Mutex.tryLock` ; interdire les appels imbriqués implicites |
| `AutodriveDataBackfiller.kt` | 314 | Rejoint le CSV au CGM, migration/rétention | **C1/R** | `HistoryRepository` suspendu + transaction de dataset ; expiration/reprise par curseur |
| `AutodriveBackfillWorker.kt` | 42 | Worker 6 h | **A** | Android uniquement ; adaptateur vers job commun |
| `AutodriveNeuralTrainer.kt` | 386 | Régression logistique et holdout | **C1** | Retirer Context inutilisé, JSON/store abstraits, publication atomique |
| `AutodriveNeuralTrainerWorker.kt` | 50 | Gate 2 880 lignes, worker 24 h | **A/R** | Android uniquement ; résultat typé `INSTALLED/KEPT/RETRYABLE_FAILURE` |
| `MechanismAttentionGate.kt` | 145 | Inférence logistique et modulation défensive | **C1/R** | Snapshot immuable publié immédiatement ; store/clock/logger ; métadonnées modèle |
| `OnlineLearner.kt` | 143 | Feedback à +30 min | **C1/R/S** | Ne pas l'activer ; remplacer `removeIf`/Math, persister seulement après nouvelle cible validée |

### 4.5 ML OREF adjacent

`advisor/oref/OrefPersonalMlTrainer.kt` (148 LOC) réutilise `AimiNeuralNetwork` sur 35 features et
deux têtes hypo/hyper à 4 h. Il est portable après abstraction du stockage, mais il reste
**non décisionnel** : l'objectif entraîne une sortie linéaire 0/1 puis la lecture applique une
sigmoïde, donnant un pseudo-pourcentage approximativement compris entre 50 et 73 %. Les poids sont
sauvegardés mais jamais rechargés. `OrefPersonalSignalGate` bloque son usage décisionnel. Le porter ne
doit pas être interprété comme une validation de ce signal.

## 5. Contradictions et corrections des documents existants

### 5.1 « Supprimer TFLite et réexprimer le modèle en JSON »

Cette recommandation apparaît dans le rapport principal, notamment aux phases 2.8, ainsi que dans
l'annexe 2. Elle contredit l'annexe 3, qui propose justement LiteRT par cinterop.

Elle est techniquement injustifiée :

- un runtime iOS officiel existe ;
- le graphe TFLite n'est pas le même que `AimiNeuralNetwork` ;
- le modèle actif fait 4 504 octets, et non 4 232 octets ; 4 232 est la taille de `model.tflite`, qui
  n'a pas d'appel actif ;
- une taille de fichier réduite ne prouve pas qu'un réseau a une architecture compatible.

**Décision proposée : conserver `modelUAM.tflite` comme artefact de référence.** Une réécriture pure
Kotlin ne serait recevable que si un extracteur reconstruit toutes les couches et tous les poids et
si un corpus de parité démontre l'équivalence avant retrait du runtime.

### 5.2 « L'inférence et l'entraînement sont 100 % pure Kotlin / passent tels quels »

Les boucles mathématiques sont Kotlin. Les composants complets ne le sont pas : `java.io.File`,
`org.json`, `Context`, `Environment`, Logcat, `java.util.concurrent`, `ReentrantLock`, WorkManager,
`PersistenceLayer`, `Calendar` et `SimpleDateFormat` sont présents dans les chemins actifs.

La formulation correcte est : **algorithmes portables, lifecycle et données à adapter**.

### 5.3 « Le modèle est fourni par l'utilisateur à l'exécution »

Le code observé ne garantit pas ce contrat. `MainApp.copyModelToInternalStorage()` copie
inconditionnellement les deux assets à chaque démarrage, puis configure le handler sur la copie. Une
modification utilisateur du fichier peut donc être écrasée au prochain lancement.

Avant la migration, il faut arbitrer entre :

- artefact embarqué immuable ;
- artefact utilisateur importable ;
- artefact embarqué initial, puis upgrade/import versionné.

Dans les trois cas, le SHA-256, le schéma d'entrée et la provenance doivent être visibles dans le
statut de santé.

### 5.4 « Le problème iOS est seulement le moment où entraîner »

Le scheduling est un problème réel, mais trois autres problèmes sont de même niveau :

1. état mémoire perdu plus fréquemment sur iOS ;
2. publication atomique et concurrence entre tick, trainer et backfill ;
3. décalages de schéma et de features entre collecte, entraînement et service.

### 5.5 « 5 à 9 semaines-personnes pour le ML »

Cette charge est plausible pour un POC : runtime TFLite iOS, déplacement des boucles numériques et
quelques tests. Elle ne couvre pas la parité de production de tous les learners, la migration des
états, la concurrence, les interruptions iOS, les datasets et la validation terrain. Le budget de la
section 13 distingue ces deux objets.

## 6. Stratégie de conservation du modèle TFLite

### 6.1 Contrat commun

Le code commun ne doit connaître ni `Interpreter` Android ni l'API Swift :

```kotlin
interface UamInferencePort : AutoCloseable {
    val metadata: UamModelMetadata
    suspend fun load(artifact: ModelArtifact): UamLoadResult
    fun infer(features: FloatArray): UamInferenceResult
}

data class UamModelMetadata(
    val sha256: String,
    val featureSchemaId: String,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val inputType: TensorDataType,
    val outputType: TensorDataType,
    val runtimeVersion: String,
)
```

`commonMain` conserve : ordre des 18 features, sanitation, politique de fallback, troncature à quatre
décimales, cache et health status. Les `actual` ne font que charger et exécuter le tensor.

### 6.2 Android

- conserver d'abord le CPU TFLite sans delegate GPU ;
- isoler la dépendance actuellement ancienne `tensorflow-lite:2.4.0` ;
- mmap ou buffer direct selon le store ;
- vérifier entrée `[1,18]`, sortie `[1,1]` et Float32 avant activation ;
- ne jamais remplacer un interpréteur actif tant que le nouveau n'a pas passé ses probes.

### 6.3 iOS

Deux intégrations sont acceptables :

1. `TensorFlowLiteSwift` dans la couche Swift, avec un petit pont vers le framework KMP ;
2. `TensorFlowLiteC.framework` lié à `iosMain` via cinterop.

Le premier choix limite l'exposition des bindings expérimentaux dans le code commun ; le second
centralise davantage le lifecycle dans Kotlin. La décision doit être prise par un spike de deux jours
mesurant packaging XCFramework/SPM, taille et simplicité de CI.

LiteRT prend officiellement en charge iOS et expose un interpréteur Swift. Kotlin/Native sait appeler
des frameworks C/Objective-C, même si les bindings Objective-C importés restent marqués Beta :

- [Google AI Edge — référence TensorFlowLiteSwift](https://ai.google.dev/edge/api/tflite/swift/Enums/InterpreterError)
- [Kotlin/Native — interopérabilité C](https://kotlinlang.org/docs/native-c-interop.html)
- [Kotlin/Native — framework Apple](https://kotlinlang.org/docs/apple-framework.html)

### 6.4 Politique d'artefact

Recommandation :

1. embarquer `modelUAM.tflite` dans les deux applications ;
2. au premier lancement, installer de façon atomique la version embarquée ;
3. ne pas écraser automatiquement un modèle importé ou plus récent ;
4. conserver `current`, `previous` et `candidate` ;
5. valider SHA, taille, schema, tensor types, probes et sorties finies ;
6. activer le candidat par échange atomique d'un snapshot immuable ;
7. rollback immédiat si l'inférence échoue au-delà d'un seuil ;
8. exposer `modelId`, SHA, version runtime et source dans Hormonitor.

## 7. Architecture KMP proposée

### 7.1 Principe

Les algorithmes vivent dans `commonMain`. Les plateformes fournissent des ports. `expect/actual` est
réservé aux petites fabriques dont la nature est véritablement plateforme ; les services métier sont
des interfaces injectées, afin de pouvoir les simuler dans les replays.

```text
                        commonMain
┌──────────────────────────────────────────────────────────────┐
│ Feature schemas + typed samples                              │
│ AimiNeuralNetwork + trainers + publish gates                 │
│ BasalLearner + UnifiedReactivity + Autodrive ML              │
│ ModelRegistry (snapshots immuables)                           │
│ TrainingCoordinator (jobs idempotents et reprenables)        │
│                                                              │
│ Ports : Clock, History, ArtifactStore, DatasetStore,          │
│         UamInference, Logger, TrainingWakeup, AppLifecycle    │
└───────────────────────┬───────────────────────┬──────────────┘
                        │                       │
                  androidMain                iosMain
              TFLite / WorkManager      LiteRT / BGTaskScheduler
              AAPS PersistenceLayer     Trio/LoopKit repository
              fichiers Android          sandbox/Data Protection
```

### 7.2 Ports requis

| Port | Responsabilité | Pourquoi il est nécessaire |
|---|---|---|
| `AimiClock` | heure murale, monotone, zone locale | TTL, cooldowns, fenêtres, DST, tests déterministes |
| `AimiArtifactStore` | read, write-temp, atomicMove, backup, checksum | modèles et états crash-safe |
| `AimiDatasetStore` | append non bloquant, snapshot, transaction rewrite | CSV SMB/basal/Autodrive sans bloquer la dose |
| `AimiHistoryRepository` | BG et événements sur une fenêtre | UnifiedReactivity et backfill Autodrive |
| `UamInferencePort` | charger/exécuter/fermer TFLite | runtime natif par plateforme |
| `ModelRegistry` | publier/lire un modèle immuable par génération | absence de course inférence/entraînement |
| `TrainingWakeupPort` | demander une opportunité, annuler, reporter | WorkManager vs BGTaskScheduler/foreground/BLE tick |
| `AimiLogger` | événements structurés et liveness | retirer Logcat/AAPSLogger du cœur |
| `TrainingBudget` | deadline, annulation, thermique/énergie | iOS peut interrompre un job |

### 7.3 Modèle d'état

Chaque learner expose un réducteur explicite :

```text
LearnerState(t-1) + TickEvent + HistorySnapshot
                    -> LearnerState(t) + LearnerOutputs + PersistEvents
```

Le moteur de dosage ne doit jamais lire un objet mutable pendant qu'un worker le modifie. La boucle
lit un `ModelSnapshot` immuable. Le trainer construit un candidat privé, le valide, l'écrit, puis
publie une nouvelle génération en une opération.

Pour Native, préférer un **acteur mono-écrivain** pour les états de learners et le dataset. Utiliser
des atomiques seulement pour des snapshots immuables ou compteurs simples. Une traduction mécanique
de `synchronized` vers `Mutex` est dangereuse : le verrou JVM de `AimiUamHandler` est réentrant et
`configureUamModel()` appelle `close()` sous ce même verrou ; `kotlinx.coroutines.sync.Mutex` ne l'est
pas.

## 8. Persistance et continuité entre redémarrages

### 8.1 État actuel

| Composant | Persisté | Seulement mémoire | Risque iOS |
|---|---|---|---|
| UAM | modèle sur disque | interpreter, cache, confiance runtime | faible ; le cache peut être perdu |
| SMB trainer | poids JSON | `lastTrainMs`, `rowsAtLastTrain`, breaker | relecture/réentraînement répété après restart |
| Basal trainer | poids + lastTrain/rows | breaker | acceptable si le breaker est redéfini |
| BasalLearner | multiplicateurs + timestamps | fenêtres 2 h/24 h, fasting, latch post-hypo | **élevé**, comportement après relance différent |
| BasalNeuralLearner | poids | governance 24 h, facteurs heuristiques, outcomes en attente | **élevé** si l'app est terminée |
| UnifiedReactivity | facteurs, timestamps, charge hypo déjà comptée | caches BG, montée confirmée en attente | moyen |
| Autodrive Data Lake | CSV | jusqu'à 48 lignes différées | perte possible lors d'une terminaison |
| Attention Gate | poids | cache et heure de chargement | faible |
| OnlineLearner | rien | facteur, prédictions et feedback | total, mais chemin shadow aujourd'hui |

### 8.2 Cible

- Persister un `LearnerCheckpoint` versionné après chaque transition décisionnelle importante.
- Pour les fenêtres BG volumineuses, stocker les événements dans le repository et reconstruire la
  fenêtre au démarrage plutôt que dupliquer toutes les mesures en JSON.
- Persister `nextEligibleAt`, high-water row/event ID et résultat de la dernière tentative de chaque
  trainer.
- Différencier `NO_DATA`, `NOT_DUE`, `CANDIDATE_REJECTED`, `INSTALLED`, `CANCELLED`,
  `RETRYABLE_FAILURE` et `PERMANENT_FAILURE`.
- Donner à chaque job une clé d'idempotence `{trainerId, datasetGeneration, schemaId}`.
- Chiffrer/protéger les fichiers de santé avec les protections de données de la plateforme et éviter
  les fichiers accessibles hors sandbox comme source active de dosage.

### 8.3 Manifeste de modèle

Le JSON de poids v2 ne sérialise pas tous les paramètres d'interprétation, notamment la configuration
du réseau. Le nouveau format doit envelopper le payload existant :

```json
{
  "artifactSchema": 3,
  "modelId": "basal",
  "featureSchema": "basal-v2-16f",
  "inputSize": 16,
  "hiddenSize": 8,
  "outputSize": 1,
  "activation": "leaky-relu",
  "leakyReluAlpha": 0.01,
  "layerNorm": false,
  "trainingSeedAlgorithm": "aimi-xoroshiro-v1",
  "datasetGeneration": 1234,
  "createdAt": 0,
  "metrics": {},
  "payloadSha256": "...",
  "payload": {}
}
```

Les fichiers v2 existants restent importables par un migrateur, mais ne sont activés qu'après les
probes de la tête concernée.

## 9. Ordonnancement iOS

Apple prévoit `BGProcessingTask` pour des travaux lourds différables, mais le système choisit leur
moment de lancement et peut interrompre un travail. Il ne faut donc pas traduire « toutes les 6 h »
par une promesse de réveil toutes les 6 h. Référence :
[Apple — Choosing Background Strategies](https://developer.apple.com/documentation/BackgroundTasks/choosing-background-strategies-for-your-app).

### 9.1 Déclenchement cible

| Travail | Déclencheur primaire | Déclencheurs de rattrapage | Bloque la dose ? |
|---|---|---|---|
| UAM + inférences Kotlin | chaque tick | aucun | calcul synchrone borné, fallback immédiat |
| BasalLearner | chaque tick, horloge interne | reconstruction au lancement | non |
| UnifiedReactivity | tick si fenêtre échue | activation app/actualisation historique | non ; utilise snapshot précédent si query en cours |
| SMB training | événement `>=200 nouvelles lignes` et `>=6 h` | foreground, BLE tick, BGProcessing | jamais |
| Basal/T3C training | `>=80 nouvelles lignes` et `>=1 h` | bootstrap, foreground, BGProcessing | jamais |
| Autodrive backfill | outcome arrivé à +60 min | foreground, BLE tick, BGProcessing | jamais |
| Attention training | dataset prêt, au plus une fois/jour | foreground/charge/BGProcessing | jamais |

### 9.2 Travail reprenable

Les trainers actuels effectuent jusqu'à 200 ou 300 époques d'un bloc. La cible iOS doit :

- vérifier `TrainingBudget.isExpired` entre les époques ou lots ;
- sauvegarder dataset generation, epoch, meilleur snapshot et état Adam si une reprise exacte est
  exigée ;
- ou abandonner proprement le candidat privé et recommencer plus tard, sans toucher à l'incumbent ;
- appeler le callback de fin de tâche sur tous les chemins ;
- ne publier qu'après la totalité des gates ;
- reprogrammer le prochain travail avant de rendre la main.

Le modèle de production n'est jamais dépendant de la réussite d'un réveil iOS : le dernier incumbent
valide ou le fallback heuristique reste disponible.

## 10. Concurrence et chemin critique

### 10.1 Invariants obligatoires

1. Aucun accès disque, query historique, parsing CSV ou attente de verrou non bornée devant une
   commande de dose.
2. Une inférence lit un modèle immuable qui reste vivant jusqu'à la fin de l'appel.
3. Un candidat ne devient visible qu'après écriture, checksum, relecture et probes.
4. Deux trainers sur la même génération ne peuvent pas publier dans un ordre inversé ; un token de
   génération rend le second obsolète.
5. Le backfill et la réécriture du dataset forment une transaction ; l'append du tick est soit ajouté,
   soit mis dans une queue bornée, jamais attendu.
6. Une expiration iOS ne modifie ni incumbent ni high-water mark de succès.
7. Toute erreur se termine sur un comportement neutre/heuristique explicite et observable.

### 10.2 Défauts ou ambiguïtés observés à traiter

- `AimiSmbTrainer` ne persiste ni l'heure ni le nombre de lignes de la dernière tentative. Tant que
  200 lignes ne sont pas atteintes, un redémarrage ou chaque tick éligible peut relancer une lecture
  complète du CSV. Le coordinateur basal a déjà corrigé ce pattern en enregistrant aussi une tentative
  qui ne publie rien ; SMB doit adopter le même protocole.
- `AutodriveNeuralTrainerWorker` transforme tout `false` du trainer en `Result.retry()`. Or `false`
  signifie aussi « candidat sain mais pas meilleur », résultat normal qui ne doit pas être réessayé
  comme une panne.
- `MechanismAttentionGate` ne recharge les poids qu'au plus une fois par heure. Un modèle correctement
  installé peut donc rester invisible jusqu'à une heure ; un `ModelRegistry` doit notifier la
  publication immédiatement.
- `AimiStorageHelper.saveFileSafe()` est best-effort mais non atomique. Il ne doit pas servir tel quel
  à publier un modèle Autodrive.
- `BasalLearner` et la governance de `BasalNeuralLearner` perdent leurs fenêtres en mémoire au restart.
  La différence de cycle de vie Android/iOS rend ce point fonctionnel, pas seulement technique.
- `UnifiedReactivityLearner` lance une query asynchrone puis analyse immédiatement le cache précédent.
  Ce décalage d'un ou plusieurs ticks doit être rendu explicite par un `HistorySnapshot(version)` ; une
  implémentation iOS qui attendrait la query ne serait pas strictement équivalente.
- `OnlineLearner` est non persisté et volontairement non appliqué. La migration conserve ce statut
  shadow jusqu'à définition d'une vraie cible MPC et validation séparée.

## 11. Stratégie de tests et parité

Les tests JVM existants sont riches sur le réseau Kotlin, les publish gates, le parser basal et le
dataset Autodrive. Ils ne contiennent toutefois aucun test qui exécute réellement le `.tflite` sur
Android et iOS, ni aucun test Native. `UamInputSchemaValidatorTest` ne vérifie que la dimension
attendue.

### 11.1 Corpus UAM golden

Créer un corpus versionné avec, pour chaque cas : 18 features brutes, features sanitizées, SHA de
cache, sortie TFLite brute, sortie tronquée et résultat final de la branche appelante.

Le corpus doit couvrir :

- valeurs nominales repas/UAM, jeûne, nuit, activité, résistance et post-hypo ;
- limites BG, IOB, TDD et compteurs de pas ;
- NaN, `+Inf`, `-Inf` ;
- modèle absent, corrompu, mauvais input, mauvais output ;
- appels répétés validant cache hit/expiration/invalidation ;
- valeurs proches d'une quatrième décimale et des seuils de quantification de dose.

Critères proposés :

- SHA de l'artefact exactement identique ;
- schéma et dtype exactement identiques ;
- sortie Float32 CPU Android/iOS à `absError <= 1e-5` et `relError <= 1e-5` ;
- résultat tronqué identique, sauf cas explicitement marqué à moins de la tolérance d'une frontière ;
- commande finale et raison d'autorité exactement identiques après quantification.

Ne pas activer Metal/GPU dans la phase de parité. Une optimisation ultérieure aura son propre corpus.

### 11.2 Réseau Kotlin

Tests `commonTest`, exécutés JVM et simulateur iOS :

- forward pass sur poids fixes ;
- normalisation, LeakyReLU, layer norm on/off ;
- gradient sur un petit réseau par différences finies ;
- AdamW, biais, early stopping et restauration du meilleur epoch ;
- serialization v2 -> v3, roundtrip et rejet des dimensions incorrectes ;
- modèle corrompu, NaN/Inf, backup valide ;
- publish gates sur signal réel, labels constants et bruit ;
- publication concurrente et candidat obsolète ;
- expiration à chaque epoch et absence de modèle partiellement publié.

Le seed fixe actuel ne suffit pas à garantir les mêmes poids dans le temps : la documentation Kotlin
garantit une séquence répétable seulement dans une même version du runtime et prévient que
l'algorithme peut changer. Il faut posséder un PRNG AIMI stable si la reproductibilité
cross-version est une exigence : [Kotlin Random](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.random/-random.html).

Critère de production : mêmes gates acceptées/rejetées et prédictions dans une tolérance documentée.
L'identité binaire des poids n'est pas requise si les décisions cliniques restent identiques.

### 11.3 SMB

- test de l'ordre et des unités des 21 features ;
- test que la valeur `trendIndicator` enregistrée est celle de l'inférence ;
- fixtures anciennes sans contexte physiologique, avec neutral backfill ;
- filtres learning quality/protection/conflits/fragilité/épuisement ;
- high-water mark persistant à travers un restart ;
- fallback exact en absence de modèle, circuit ouvert et exception ;
- clamp `±min(0,05 U, 25 %)` sous tous les profils comportementaux ;
- vérification de la dose finale, pas uniquement de la sortie réseau.

### 11.4 Basal/T3C

- parser identique sur CSV v1/v2, tri non ordonné et trous CGM ;
- jointure future à +20/+30/+45 min ;
- censure bolus/COB/IOB et compteur `legacyUncensored` ;
- label au-dessus et au-dessous de la cible, deadbands et bornes ;
- split chronologique ;
- publication/rejet/reload atomique des deux têtes ;
- conservation du gap volontaire label basal 0,85 / runtime 0,80 ;
- reconstruction de la governance après terminaison/relaunch ;
- replay séquentiel de 24 h avec les mêmes snapshots à chaque tick.

### 11.5 UnifiedReactivity et BasalLearner

- heure locale, changement de jour, DST avant/arrière ;
- mêmes fenêtres avec redémarrage toutes les 30 minutes, toutes les 6 heures et sans redémarrage ;
- montée confirmée latchée puis consommée ;
- charge hypo non recomptée ;
- post-hypo rebound exclu ;
- cache d'historique ancien versus nouveau snapshot explicitement versionné ;
- persistance défaillante : le dosage continue avec le dernier état mémoire sûr.

### 11.6 Autodrive

- exactement une ligne par tick et préférence de la ligne `engaged` ;
- append pendant backfill sans blocage ; queue 48 et perte comptabilisée ;
- migrations v0/v1/v2 et header canonique ;
- labels CGM à +45/+60 min et absence de couverture ;
- rétention 60 jours ;
- dataset très déséquilibré, holdout chronologique, prior correction ;
- résultat `KEPT` non traité comme erreur ;
- chargement immédiat après publication ;
- preuve que l'Attention Gate reste défensive uniquement ;
- preuve que `OnlineLearner.learnedSensitivityFactor` demeure non appliqué.

### 11.7 Fault injection

Simuler sur les deux plateformes :

- terminaison après écriture `.tmp`, après rotation `.bak`, pendant backfill et pendant entraînement ;
- disque plein, fichier verrouillé, Data Protection indisponible appareil verrouillé ;
- changement de modèle pendant 100 inférences concurrentes ;
- expiration `BGProcessingTask` ;
- horloge murale reculant/avançant ;
- dataset tronqué, ligne partielle et caractères CSV échappés.

## 12. Gates de migration

### Gate ML-0 — Freeze des contrats

- figer SHA des deux TFLite et déclarer `modelUAM` actif ;
- documenter provenance, licence, unités et plage des 18 features ;
- capturer au moins 1 000 inférences représentatives Android avec sorties brutes ;
- versionner les schémas SMB 21f, basal 16f et Autodrive v2 ;
- ajouter le vrai `trendIndicator` au dataset SMB ;
- décider la politique modèle embarqué/importé.

Sortie : aucun portage tant que ces éléments ne sont pas reproductibles.

### Gate ML-1 — Common core

- porter `AimiNeuralNetwork`, `TrainingConfig`, schemas, parsers et gates ;
- remplacer JSON/JVM par sérialisation KMP ;
- introduire PRNG stable et `AimiClock` ;
- exécuter la même suite sur JVM et `iosSimulatorArm64`.

Sortie : mêmes décisions de gates et mêmes prédictions dans les tolérances.

### Gate ML-2 — TFLite réel sur iPhone

- intégrer LiteRT CPU ;
- charger exactement le SHA de référence ;
- passer le corpus golden sur simulateur lorsque possible et appareil réel ;
- mesurer p50/p95/p99, mémoire, température et temps de chargement ;
- valider missing/corrupt/rollback.

Sortie : aucune perte du modèle initial et aucune différence de décision finale.

### Gate ML-3 — Persistance et modèle d'acteurs

- `ArtifactStore`, `DatasetStore`, `HistoryRepository`, `ModelRegistry` ;
- migration des fichiers Android ;
- reprise/expiration et fault injection ;
- zéro attente de lock ou I/O non bornée sur le chemin dose.

Sortie : tests de crash et concurrence verts sur JVM et iOS.

### Gate ML-4 — Learners

- SMB refinement ;
- basal/T3C et governance ;
- BasalLearner et UnifiedReactivity ;
- Autodrive backfill/Attention ;
- OnlineLearner explicitement shadow.

Sortie : replays séquentiels identiques avec redémarrages artificiels.

### Gate ML-5 — Shadow terrain

- Android et iOS consomment les mêmes snapshots ;
- au minimum 14 jours/4 000 ticks continus avant examen, prolongé à 30 jours pour événements rares ;
- aucune divergence d'autorité, de clamp ou de commande finale ;
- toute divergence numérique est classée avant activation ;
- modèles, datasets et raisons de fallback visibles dans Hormonitor.

Cette gate est une validation d'ingénierie, pas une certification clinique ou réglementaire.

## 13. Charge ML/apprentissage

Estimation en semaines-personnes, hors portage du moteur AIMI global, interface Trio, drivers,
HealthKit complet et validation réglementaire.

| Lot | P50 | P80 | Contenu |
|---|---:|---:|---|
| Freeze, corpus et schémas | 3 | 5 | Gate ML-0, modèle card, capture Android |
| Réseau Kotlin + formats + PRNG | 4 | 6 | commonMain, v3, tests Native |
| Runtime TFLite Android/iOS | 3 | 5 | packaging, bridge, lifecycle, golden tests |
| SMB refinement | 3 | 5 | dataset, skew trend, scheduler, migration |
| Basal/T3C | 5 | 8 | parser, deux têtes, governance, reprise |
| BasalLearner + UnifiedReactivity | 4 | 7 | historique, état durable, heure locale |
| Autodrive ML/backfill | 6 | 10 | Data Lake, transaction, attention, workers |
| Concurrence et scheduling commun | 4 | 7 | acteurs, jobs durables, expiration iOS |
| CI, appareils réels, performance/faults | 5 | 8 | simulateur + iPhone, campagne shadow outillée |
| **Total de planification** | **37 sp** | **61 sp** | chevauchements possibles avec le socle KMP global |

Avec un socle stockage/horloge/scheduler déjà construit par un autre lot AIMI, environ 6 à 10 sp
peuvent être mutualisées. Le périmètre ML net reste donc **30 à 50 semaines-personnes** pour une
parité de production, contre **5 à 9** pour un simple POC runtime.

Principales dépendances de charge :

- disponibilité d'un iPhone et d'une CI macOS ;
- accès à des captures complètes et pseudonymisées ;
- décision sur modèle utilisateur versus modèle embarqué ;
- conservation des CSV ou passage à une base transactionnelle ;
- degré de correction des défauts actuels avant gel de parité.

## 14. Risques et décisions

| ID | Risque | Probabilité | Impact | Réduction |
|---|---|---|---|---|
| ML-R1 | Réécriture du TFLite non équivalente | élevée si recommandation actuelle suivie | critique | conserver l'artefact, LiteRT iOS, corpus golden |
| ML-R2 | Ordre/unité d'une feature différent | moyenne | critique | DTO et schemaId, tests index par index |
| ML-R3 | État learner perdu lors des relaunch iOS | élevée | élevé | checkpoint/reconstruction, replay avec restarts |
| ML-R4 | Deux trainers publient en course | moyenne | élevé | acteur, génération et ModelRegistry |
| ML-R5 | Tâche iOS interrompue | élevée | moyen | candidat privé, checkpoints/abandon sûr |
| ML-R6 | PRNG ou flottants divergent | moyenne | moyen | PRNG AIMI, tolérances, décision finale exacte |
| ML-R7 | Train/serve skew SMB | certain aujourd'hui | élevé | logger le trend réel, versionner et migrer |
| ML-R8 | Historique HealthKit/Trio incomplet | moyenne | élevé | `Missing/Denied/Stale` distincts, pas de zéro implicite |
| ML-R9 | CSV endommagé ou réécriture concurrente | moyenne | élevé | DatasetStore transactionnel, fault injection |
| ML-R10 | Un chemin shadow devient actif pendant le port | faible à moyenne | critique | feature gate explicite et tests OnlineLearner/OREF |
| ML-R11 | Upgrade runtime modifie les sorties | moyenne | élevé | versions Android/iOS coordonnées et golden gate |
| ML-R12 | Modèle sans provenance ni plages documentées | certain | élevé | model card et manifeste avant Gate ML-1 |

## 15. Arbitrages nécessaires

### A. Modèle UAM

**Recommandation :** même modèle embarqué sur Android et iOS, import optionnel seulement après
manifest/checksum/probes. Ne pas le convertir en `AimiNeuralNetwork` dans la première migration.

Décision produit requise : l'utilisateur peut-il remplacer ce modèle, et si oui quelle politique de
signature, compatibilité et rollback ?

### B. Compatibilité comportementale ou correction préalable

Certains comportements sont des défauts connus : trend SMB approximatif, état volatile, résultat
Autodrive `KEPT` traité en retry. Deux stratégies sont possibles :

1. émuler d'abord Android puis corriger sur les deux plateformes ;
2. corriger Android, geler une nouvelle référence, puis porter cette référence.

**Recommandation : stratégie 2.** Émuler une anomalie de lifecycle Android sur iOS rendrait la parité
fragile et plus coûteuse.

### C. CSV ou store transactionnel

**Recommandation :** conserver import/export CSV pour Hormonitor et compatibilité, mais utiliser un
store transactionnel commun comme source active des nouveaux événements. Générer les CSV comme vues
d'export. Cela supprime les réécritures complètes et simplifie les high-water marks.

### D. Déterminisme

**Recommandation :** exiger l'identité des décisions finales et des gates ; tolérer de très petits
écarts internes ; posséder le PRNG pour les nouveaux entraînements. Ne pas imposer des poids JSON
byte-identiques entre libm/runtimes.

### E. OnlineLearner et OREF personnel

**Recommandation :** les porter uniquement pour observabilité et continuité de format, en conservant
leurs gates shadow. Leur activation clinique constitue un projet séparé avec cible, calibration,
holdout et validation propres.

## 16. Réponse synthétique aux questions de faisabilité

- **Perdre le modèle initial TFLite ?** Non. Il doit être conservé et exécuté par LiteRT iOS.
- **Entraîner SMB sur iOS ?** Oui, après abstraction du store/scheduler et correction du skew de
  feature. Le dosage reste opérationnel sans nouveau modèle.
- **Entraîner basal et T3C ?** Oui. Les labels, gates et MLP sont portables. La governance et les
  fenêtres doivent devenir durables.
- **Faire fonctionner les learners ?** Oui pour BasalLearner, UnifiedReactivity, SMB et basal/T3C.
  OnlineLearner reste volontairement shadow jusqu'à une vraie cible MPC.
- **Faire fonctionner Autodrive ML ?** Oui, avec backfill CGM, dataset transactionnel et tâche
  opportuniste. Sa cadence 6 h/24 h n'est pas garantie par iOS, mais sa correction n'est pas requise
  pour chaque tick.
- **Préserver l'intégration des données physiologiques ?** Oui, si les schémas 21f/16f et le masque
  Autodrive sont versionnés et si l'absence de données est explicite. Les features réellement
  observées sont les états latents, biais Patient Mode, confiances causales et masque de stress ;
  il ne faut pas promettre que chaque sortie brute du Tree/Harmonia entre directement dans le ML.

Le bloc ML ne contient donc aucun obstacle matériel à Kotlin/Native. Son prix vient de la preuve de
parité, de l'état durable et du lifecycle, pas d'une réécriture des mathématiques.
