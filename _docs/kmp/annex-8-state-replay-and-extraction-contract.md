# A8 — Contrat d'état, replay exécutable et extraction de `DetermineBasalAIMI2`

> **Référence :** `dev_OAPSAIMI` à `06e7bc5021ca8fdd976505d1fefb03cc88681c19`.  
> **Objet :** définir la frontière qu'il faut réellement extraire pour obtenir un moteur AIMI KMP
> déterministe et vérifiable.  
> **Complément :** le blueprint directeur est `AIMI_KMP_MIGRATION_BLUEPRINT.md`.

## 1. Conclusion

La signature actuelle de `determine_basal` ne constitue pas le contrat complet du moteur.
`AimiTickContext` transporte 15 paramètres, mais `DetermineBasalAIMI2` continue à lire des services,
des préférences, des caches, des fichiers, des historiques et des objets stateful pendant le tick.

Une frontière JSON construite uniquement à partir des arguments de `determine_basal` ne peut donc
pas reproduire la décision. Elle perdrait une part des entrées et de l'état causal.

La frontière correcte est :

```text
ReadSet platform + EngineState(n) + ModelBundle(n)
                       |
                       v
               AimiInputSnapshot(n)
                       |
                       v
              AimiEngine.evaluate
                       |
                       v
 command(n) + EngineState(n+1) + ordered events + trace
```

## 2. Preuves dans le code actuel

### 2.1 État localisé dans la classe géante

Audit textuel de `DetermineBasalAIMI2.kt` :

| Surface | Mesure |
|---|---:|
| `private var` au niveau de la classe ou de ses objets | **239** occurrences |
| `private val` manifestement stateful (`Atomic*`, mutable, scope, lazy, holds) | **37** occurrences |
| références distinctes à des clés de préférence dans le fichier | **109** |
| accès textuels `preferences` | 259 |
| accès `persistenceLayer` | 18 |
| accès `tddCalculator` | 9 |
| accès `tirCalculator` | 18 |
| accès `physioAdapter` | 37 |
| accès cumulés aux quatre learners principaux | 41 |

Toutes les occurrences ne sont pas des états durables : une grande partie est du scratch de tick
stocké dans les champs de la classe. C'est précisément le problème. La durée de vie n'est pas
visible dans le type et dépend du fait que chaque chemin réinitialise correctement les champs.

### 2.2 Exemples de caches d'acquisition

Le moteur possède actuellement des références atomiques et des flags de refresh pour :

- âge de pompe et dernier SMB ;
- TIR warmup ;
- contexte glucidique ;
- TDD 2 et 30 jours ;
- insertion du capteur ;
- pas et fréquences cardiaques ;
- TBR et bolus ;
- profil effectif ;
- historique de trajectoire ;
- caches TDD/TIR par invocation.

Ces caches sont des responsabilités d'acquisition. Ils ne doivent pas être déplacés dans le moteur
commun. Le snapshot doit contenir leur dernière valeur, son âge et son statut de disponibilité.

### 2.3 Exemples de scratch de tick actuellement en champs

Les champs suivants doivent devenir des variables d'un `AimiTickWorkingState`, créé neuf à chaque
tick et impossible à relire au tick suivant :

- BG, delta, short/long average delta, accélération ;
- IOB, COB, TDD/TIR dérivés, target et max SMB ;
- `predictedSMB`, `eventualBG`, `predictedBg`, `variableSensitivity` ;
- moyennes de fréquence cardiaque et fenêtres de pas ;
- flags meal/breakfast/lunch/dinner/snack/fasting/sport/sleep ;
- `pkpdAbsorptionGuardAppliedThisTick`, `criticalSafetyZeroedThisTick` ;
- owners, safety source, prediction availability ;
- `last...Snapshot` qui désigne en réalité le résultat du tick courant ;
- compteurs de seal/cap du SMB courant ;
- données temporaires d'export et buffers console.

Le préfixe `last` ne suffit pas pour déterminer si la valeur est cross-tick. Chaque champ doit être
classé par comportement, pas par nom.

### 2.4 Exemples d'état réellement cross-tick

| État | Sémantique | Cible |
|---|---|---|
| `lastHypoBlockAt`, `hypoClearCandidateSince` | hystérésis hypo | `HypoRecoveryMemory` durable |
| `lastBgRiseFastNightMs` | mémoire de montée nocturne | `NightRiseMemory` durable |
| `highBgOverrideUsed` | consommation d'une autorité | état de policy versionné |
| `hyperDwellAboveHighBgSinceMs` | dwell hyper | `HyperDwellMemory` durable |
| `lastRiseFloorContributionMs`, `riseFloorSpentU` | budget temporel SMB | ledger durable |
| `lastEffortMemory` | effort/post-activité | mémoire physiologique durable |
| `PatternCapHold` | maintien des caps de pattern | état de policy durable |
| `adaptiveMult` | adaptation | learner state ou valeur calculée, à trancher |
| `tddEma` dans le shell plugin | EMA ISF/TDD | état de learner durable |
| dyn-ISF cache | cache calculé et périmable | cache shell, pas état causal |
| timestamps de notifications | anti-spam UX | état plateforme, hors moteur |

Un restart ne doit pas modifier silencieusement la physiologie interprétée. Pour chaque mémoire,
la politique doit indiquer : persistée, reconstruite depuis l'historique, ou intentionnellement
réinitialisée avec un délai de prudence.

## 3. Taxonomie obligatoire des champs

Avant de déplacer une méthode, chaque champ lu ou écrit doit recevoir exactement une classe :

| Classe | Définition | Exemple | Destination |
|---|---|---|---|
| `INPUT` | vérité produite hors moteur | BG, IOB, profil, pas | `AimiInputSnapshot` |
| `CONFIG` | préférence figée pour le tick | enable flags, caps | `AimiConfigSnapshot` |
| `WORKING` | scratch sans mémoire | owners provisoires, courbes | `AimiTickWorkingState` |
| `ENGINE_STATE` | mémoire causale entre ticks | post-hypo, dwell, holds | `AimiEngineState` |
| `LEARNER_STATE` | paramètres et compteurs d'apprentissage | EMA, réseau, optimizer | `AimiModelBundle`/learner stores |
| `CACHE` | accélération reconstruisible | historique préchargé | shell `ReadSetCache` |
| `EFFECT` | action à effectuer après décision | write, notify, dataset | événement de résultat |
| `TELEMETRY` | observabilité non décisionnelle | JSONL, logs | `AimiDecisionTrace` |

Un champ ne peut pas être simultanément `WORKING` et `ENGINE_STATE`. Lorsqu'une valeur calculée au
tick courant doit être mémorisée, le résultat contient explicitement la transition à committer.

## 4. Contrats proposés

### 4.1 Enveloppe versionnée

```kotlin
@Serializable
data class AimiReplayEnvelope(
    val schemaVersion: Int,
    val engineBuildId: String,
    val platform: AimiPlatform,
    val input: AimiInputSnapshot,
    val stateBefore: AimiEngineState,
    val models: AimiResolvedModelBundle,
    val orderedExternalEventsSincePreviousTick: List<AimiExternalEvent>,
    val expected: AimiExpectedResult? = null
)
```

Les types communs utilisent des identifiants d'unité explicites. Les noms `bg`, `sens`, `rate` ou
`activity` sans unité sont interdits dans les nouveaux contrats.

### 4.2 Disponibilité des données

```kotlin
@Serializable
sealed interface TimedValue<out T> {
    data class Fresh<T>(val value: T, val capturedAtMs: Long, val ageMs: Long) : TimedValue<T>
    data class Stale<T>(val value: T, val capturedAtMs: Long, val ageMs: Long) : TimedValue<T>
    data class Missing(val reason: MissingReason) : TimedValue<Nothing>
    data class Denied(val capability: String) : TimedValue<Nothing>
    data class Unsupported(val capability: String) : TimedValue<Nothing>
}
```

`Denied`, `Unsupported`, `Missing` et une valeur numérique nulle ont des sens différents. Le moteur
peut alors appliquer une règle explicite au lieu d'interpréter une absence comme zéro.

### 4.3 Snapshot d'entrée

Le snapshot v1 est divisé pour éviter un DTO monolithique non versionnable :

```kotlin
data class AimiInputSnapshot(
    val meta: TickMeta,
    val glucose: GlucoseSnapshot,
    val pump: PumpSnapshot,
    val profile: ProfileSnapshot,
    val insulin: InsulinSnapshot,
    val meal: MealSnapshot,
    val history: HistorySnapshot,
    val physiology: PhysiologySnapshot,
    val context: PatientContextSnapshot,
    val config: AimiConfigSnapshot,
    val capabilities: CapabilitySnapshot
)
```

#### `TickMeta`

- ID monotone du tick ;
- temps mural et monotone ;
- timezone/offset ;
- motif du déclenchement : CGM, pompe, manuel, recovery ;
- version de schéma et build ;
- âge maximal admis par famille de données.

#### `GlucoseSnapshot`

- glucose mg/dL ;
- delta, short/long delta et accélération en mg/dL/5 min ;
- timestamp de l'échantillon, âge et source CGM ;
- noise, confiance, flat flag et état warmup ;
- historique brut nécessaire aux calculs encore non matérialisés ;
- features AIMI telles que `duraISF`, `deltaPl/Pn`, parabole/corrélation.

#### `PumpSnapshot`

- basal programmé et basal réel ;
- temp basal rate/durée/âge ;
- dernier bolus/SMB et historique requis ;
- increments de basal/bolus ;
- max rates, max bolus/IOB et contraintes du driver ;
- état de communication et freshness ;
- capacités SMB/TBR/suspend.

#### `ProfileSnapshot`

- schedule basal/IC/ISF/cible ;
- profil statique et profil effectif ;
- DIA/peak structurels et effectifs ;
- temp target ;
- concentration ;
- limites validées par le shell.

#### `InsulinSnapshot`

- IOB et activité courante ;
- accounting IOB array ;
- prediction IOB array sur cinétique apprise ;
- TDD 24 h/1 j/2 j/4 j/7 j/30 j avec qualité ;
- TIR nécessaires avec qualité ;
- âge du site/canule et dernières doses.

#### `MealSnapshot`

- COB et carbs actifs ;
- dernier événement carbs et âge ;
- future COB ;
- événements repas déclarés ;
- mode repas manuel ;
- phase d'absorption/certitude si déjà disponible.

#### `HistorySnapshot`

- fenêtres BG ;
- bolus/TBR/therapy events ;
- notes/tags utilisés par les rules ;
- historique trajectoire ;
- dernières décisions si une policy les consomme.

#### `PhysiologySnapshot`

- pas 5/10/15/30/60/180 min ;
- HR courant et baselines ;
- HRV avec `metric = RMSSD | SDNN | DERIVED_RMSSD` ;
- sommeil, température, SpO2 et autres signaux réellement utilisés ;
- source, qualité, permission et âge par signal ;
- activité déclarée séparée de l'activité détectée.

#### `AimiConfigSnapshot`

`DetermineBasalAIMI2` référence 109 clés distinctes. Les nouvelles APIs ne passent pas un service
`Preferences`. Elles capturent une configuration typée et validée. Une empreinte de cette config
est inscrite dans le replay.

La première implémentation peut conserver des sous-structures par domaine :

- core/safety ;
- meal/T3C ;
- Autodrive ;
- PKPD/ISF ;
- physio/activity ;
- Harmonia/RBT ;
- training ;
- logging/shadow.

### 4.4 `AimiEngineState`

```kotlin
data class AimiEngineState(
    val schemaVersion: Int,
    val lastCommittedTickId: Long,
    val hypoRecovery: HypoRecoveryMemory,
    val mealAbsorption: MealAbsorptionMemory,
    val mealAbsorptionHysteresis: MealAbsorptionHysteresisState,
    val endogenousHysteresis: EndogenousHysteresisState,
    val physiologicalPatternHysteresis: PatternHysteresisState,
    val patientEvents: PatientEventMemoryState,
    val effort: EffortMemory,
    val patternCaps: PatternCapState,
    val recursiveBeliefByHorizon: RecursiveBeliefMemoryState,
    val rbtEpisodes: RbtEpisodeMemoryState,
    val hyperDwell: HyperDwellState,
    val authorityLedgers: AuthorityLedgerState,
    val hysteresis: HysteresisState,
    val insulinObserver: RealTimeInsulinObserverState,
    val isfFusion: IsfFusionState,
    val adaptivePkpd: AdaptivePkPdState,
    val pkpdDamping: PkpdDampingState,
    val physioAggregation: PhysioAggregationState,
    val physioBaselines: PhysioBaselineState,
    val circadianProfile: CircadianProfileState,
    val thermalProfile: ThermalProfileState,
    val lastDecision: LastDecisionState?
)
```

Les modèles/optimizers restent hors de `AimiEngineState` afin de pouvoir versionner, publier et
revenir indépendamment sur un modèle. Une seule autorité possède toutefois chaque champ appris.
Le chargement atomique du tick capture ensemble `engineStateGeneration`, `modelBundleGeneration` et
`datasetGeneration`, afin d'éviter un demi-checkpoint.

### 4.5 Bundle de modèles résolvable

Un manifest qui ne contient qu'un nom de fichier est insuffisant. Le replay doit résoudre :

```kotlin
data class AimiResolvedModelBundle(
    val generation: Long,
    val uamTflite: ResolvedModelArtifact,
    val smbRefinement21f: ResolvedModelArtifact?,
    val basal16f: ResolvedModelArtifact?,
    val t3c16f: ResolvedModelArtifact?,
    val autodriveAttention: ResolvedModelArtifact?,
    val basalLearner: LearnerCheckpoint,
    val basalNeuralGovernance: LearnerCheckpoint,
    val unifiedReactivity: LearnerCheckpoint,
    val onlineLearnerMode: ActivationMode,
    val orefPersonalMode: ActivationMode
)
```

Chaque artefact porte SHA-256, schema ID, génération, provenance, runtime/version, état
`ACTIVE/SHADOW/PREVIOUS/CANDIDATE` et paramètres de pré/post-traitement.

### 4.6 Résultat et événements

```kotlin
data class AimiTickResult(
    val command: AimiTherapyCommand,
    val stateAfter: AimiEngineState,
    val trainingRequests: List<TrainingRequest>,
    val datasetEvents: List<DatasetEvent>,
    val notificationEvents: List<NotificationEvent>,
    val trace: AimiDecisionTrace,
    val diagnostics: AimiDiagnostics
)
```

Le moteur pur peut demander un append ou un entraînement. Il ne publie aucun modèle pendant
`evaluate`. Les événements `CandidateValidated`, `ModelPublished` ou `RolledBack` appartiennent au
coordinateur asynchrone et deviennent visibles dans le snapshot du tick suivant.

Une `AimiTherapyCommand` doit être sémantique avant adaptation pompe :

- `Hold` ;
- `CancelTempBasal` ;
- `SetTempBasal(rateUph, durationMin)` ;
- `DeliverSmb(units)` ;
- éventuelle combinaison explicitement autorisée par le contrat.

Le shell applique ensuite increments/capabilities et renvoie un `EnactmentReceipt`. Le résultat de
quantification est aussi enregistré pour la comparaison Android/iOS.

## 5. Protocole transactionnel d'un tick

### 5.1 Acquisition

1. le shell reçoit un événement de boucle ;
2. il obtient un lock/actor par patient ;
3. il lit toutes les sources nécessaires ;
4. il attribue fraîcheur, qualité et unité ;
5. il capture la configuration ;
6. il charge atomiquement état et manifest de modèles ;
7. il produit un snapshot immuable.

Une lecture DB ou HealthKit ne doit plus apparaître après cette étape.

### 5.2 Évaluation

1. validation structurelle ;
2. construction du working state ;
3. exécution ordonnée des stages ;
4. production d'une commande et d'une transition ;
5. aucune écriture externe.

### 5.3 Commit

Ordre recommandé :

1. valider que `result.tickId` suit `state.lastCommittedTickId` ;
2. stocker l'état et les événements critiques dans une transaction locale ;
3. publier la commande vers l'effect runner ;
4. enregistrer le reçu d'enactment ;
5. traiter datasets, Hormonitor et notifications en best effort ;
6. accuser le tick.

Il faut décider à G1 si l'état doit être committé avant ou après enactment. La recommandation est
de conserver deux marqueurs (`decisionCommitted`, `enactmentReceipt`) pour permettre une reprise
idempotente après crash entre les deux opérations.

Cette règle n'implique pas que toutes les transitions attendent le même point de commit. Pour chaque
mémoire et chaque sortie anticipée, le registre indique l'une des sémantiques :

- `COMMIT_ON_VALID_DECISION` ;
- `COMMIT_ON_SAFE_HOLD` ;
- `COMMIT_AFTER_ENACTMENT_ACK` ;
- `ROLLBACK_ON_ABORT` ;
- `DEFER_TO_ASYNC_COORDINATOR`.

Les mémoires absorption/PKPD déjà mises à jour avant un retour anticipé, la consommation d'un
Meal Advisor intent et les checkpoints learners doivent être testés individuellement. Un commit
global en fin de tick pourrait introduire un changement de comportement.

« Best effort » ne signifie pas perte invisible : chaque événement Hormonitor porte `DatasetIdentity`,
`event_id`, `tick_id` et une séquence monotone ; overflow, rotation et trous sont mesurés. Les flux
JSONL restent append-only, tandis que `daily_state` est explicitement un checkpoint remplaçable et
atomique, pas un journal exhaustif.

### 5.4 Concurrence

Le `ReentrantLock` JVM actuel masque le fait que le moteur n'est pas réentrant. La cible KMP doit
être un **single-writer** : actor/serial executor par patient. Le moteur pur n'a pas besoin de lock
interne puisqu'il reçoit un état immuable et produit l'état suivant.

Les entraînements travaillent sur une copie/version du dataset et publient par compare-and-swap :

```text
active model v12
  -> train candidate v13
  -> validate candidate
  -> fsync/temp/rename or transactional write
  -> atomic manifest v13
  -> next tick observes v13
```

En cas d'interruption avant publication du manifest, v12 reste actif.

Un job reprenable choisit explicitement l'une des deux politiques :

- checkpoint complet de l'époque, optimiser, PRNG, dataset generation et candidat ;
- abandon atomique du candidat incomplet, puis reprise depuis le modèle incumbent.

Une reprise implicite avec seulement une partie de l'optimizer n'est pas admise.

### 5.5 État de dosage et état de présentation

`DosingSnapshotStore` et `PresentationStore` sont séparés. Le premier contient le snapshot exact,
le state hash et la décision corrélés au `tickId`. Le second peut être rafraîchi pour l'UI à partir
de données partielles, mais ne peut jamais être relu par le moteur. Cette règle évite que le refresh
asynchrone actuel, qui perd certaines fenêtres de pas/HRV/sommeil, devienne une entrée de dosage.

## 6. Format du replay exécutable

### 6.1 Pourquoi les fixtures actuelles ne suffisent pas

`ReplayTick` lit une projection des **sorties** du journal : BG, IOB, COB, owner, décision, SMB,
basal, phase, eventual/minPred et quelques gates. Le test peut résumer un jour et vérifier la
présence de sections JSONL, mais il ne peut pas reconstruire :

- toutes les préférences ;
- les tableaux IOB ;
- l'historique complet ;
- les caches et mémoires avant tick ;
- les modèles/poids actifs ;
- les données physiologiques avec freshness ;
- les événements intervenus entre deux ticks.

Le terme « replay » désigne donc actuellement un **replay analytique de décisions enregistrées**,
pas une réexécution du moteur.

### 6.2 Trois artefacts distincts

| Artefact | Contenu | Usage |
|---|---|---|
| `decision-projection-v1` | format actuel compact | statistiques et régressions de journaux |
| `engine-replay-v1` | snapshot + état + modèles + attendu | parité KMP du moteur |
| `lifecycle-scenario-v1` | séquence d'événements OS/BLE/crash/restart | validation du shell iOS |

Ils ne doivent pas partager le même nom de classe ou le même identifiant de schéma.

### 6.3 Manifest et fichiers

```text
replay-case/
  manifest.json
  ticks/000001.cbor
  ticks/000002.cbor
  models/modelUAM.tflite
  models/modelUAM.sha256
  models/basal-v12.json
  expected/commands.jsonl
  expected/state-hashes.jsonl
  expected/training-events.jsonl
  expected/inference-outputs.jsonl
```

JSON reste acceptable pour debug. CBOR ou ProtoBuf peut réduire la taille du corpus complet, sous
réserve d'un schéma lisible et versionné. La précision `Double` ne doit pas être perdue.

### 6.4 Scénarios minimaux

Le corpus de qualification doit couvrir :

1. journée en range stable ;
2. hyper prolongée et plateau sur floor numérique ;
3. repas déclaré ;
4. repas non déclaré/UAM ;
5. chute/hypo puis recovery ;
6. cycles rebound hyper→hypo ;
7. activité déclarée ;
8. activité détectée avec absence d'intention ;
9. sommeil/dawn/cortisol ;
10. cycle/endocrinien/grossesse selon activation ;
11. capteur warmup/bruyant/périmé ;
12. pompe/CGM déconnecté ;
13. modèle absent/corrompu ;
14. dataset/model publication interrompue ;
15. changement de timezone/heure d'été ;
16. restart entre décision et enactment ;
17. deux événements de tick concurrents ;
18. absence/refus HealthKit ;
19. HRV SDNN présente sans RMSSD ;
20. valeurs NaN/infini/hors limites.
21. MealCertainty surclasse une activité ambiguë mais jamais une hypo réelle ;
22. `LIFT_WITHIN_ENVELOPE` Harmonia reste sous caps HARD/headroom/post-hypo/stacking ;
23. hold de pattern HARD et épisode RBT post-hypo/chaos sur plusieurs ticks ;
24. propriétaires basaux T3C/Harmonia/normal mutuellement exclusifs ;
25. second passage Harmonia `CONFIRM/SOFTEN/BLOCK` ;
26. second refresh phase/absorption/latent déclenché par Autodrive ;
27. restart pendant belief echo, observer insuline ou apprentissage PKPD ;
28. saturation de la file Hormonitor au-delà de 512 événements avec trous détectés.

### 6.5 Timeline d'entraînement entre ticks

Le corpus enregistre aussi, dans l'ordre :

- `DatasetGenerationAdvanced` ;
- `TrainingAttemptStarted` avec clé d'idempotence ;
- `TrainingCheckpointed` ou `TrainingAbandoned` ;
- `CandidateValidated` / `CandidateRejected` ;
- `ModelPublished` / `ModelRolledBack` ;
- génération active observée par le tick suivant.

Les traces incluent les vecteurs exacts et schema IDs : UAM 18f, SMB 21f avec le vrai
`trendIndicator`, basal/T3C 16f, ainsi que les sorties UAM/SMB/basal/T3C/attention attendues.

## 7. Comparateur de parité

### 7.1 Ordre de sévérité

| Niveau | Divergence | Résultat |
|---|---|---|
| C0 | halt/suspend vs dose | blocage immédiat |
| C1 | owner/authority/gate différents | blocage immédiat |
| C2 | SMB/TBR quantifiés différents | blocage immédiat sauf ADR intentionnel |
| C3 | terminaux/caps changent au-delà tolérance | investigation obligatoire |
| C4 | trace/rationale différente mais décision identique | accepté si contrat de log versionné |
| C5 | valeurs ML internes/poids différents mais sorties équivalentes | accepté dans tolérances |

### 7.2 Politique numérique initiale

Les valeurs définitives suivent la grille de la pompe et doivent être identiques :

- SMB après `bolusStep` : égalité exacte ;
- TBR après `basalStep` et durée : égalité exacte ;
- `Hold/Cancel/Suspend` : égalité exacte ;
- owner/authority/gate : égalité exacte.

Pour les intermédiaires, définir une tolérance absolue et relative par famille ; ne jamais utiliser
une tolérance globale qui permettrait à une valeur proche d'un seuil de changer de branche.

Le comparateur doit signaler la **distance au seuil**. Deux valeurs numériquement proches mais
placées de part et d'autre d'un gate constituent une divergence C1.

La ligne C5 concerne uniquement une campagne de **réentraînement séparée**. Dans un engine replay,
les artefacts, SHA, schema IDs, générations et checkpoints sont identiques ; seules de faibles
différences d'inférence liées au runtime peuvent être tolérées. Le réentraînement compare ensuite
métriques, décisions de publication et résultats dose-facing, sans exiger des poids byte-identiques.

## 8. Méthode d'extraction de `DetermineBasalAIMI2`

### Lot E0 — registre des champs

- produire automatiquement la liste des champs et de leurs read/write sites ;
- attribuer la taxonomie `INPUT/CONFIG/WORKING/ENGINE_STATE/LEARNER_STATE/CACHE/EFFECT/TELEMETRY` ;
- marquer chaque reset et chaque lecture avant écriture ;
- identifier les champs que les early returns laissent derrière eux.

**Livrable :** CSV/Markdown reviewé ; aucune modification médicale.

### Lot E1 — capture sans changement de comportement

- construire `AimiConfigSnapshot` à partir des 109 clés ;
- capturer toutes les lectures historiques du tick ;
- dual-write `engine-replay-v1` ;
- conserver les appels actuels comme source d'autorité.

**Gate :** la capture est complète sur tous les chemins de retour.

### Lot E2 — working state

- déplacer les champs strictement tick-local dans `AimiTickWorkingState` ;
- remplacer progressivement `this.field` par `work.field` ;
- initialiser le working state une seule fois ;
- prouver que deux ticks séquentiels ne partagent aucun scratch.

**Gate :** le replay Android reste identique et un test « poisoned previous tick » passe.

### Lot E3 — état causal explicite

- regrouper hystérésis, holds, dwell et memories ;
- charger `AimiEngineState` avant le tick ;
- produire `stateAfter` ;
- comparer l'état après chaque tick, pas uniquement la commande.

### Lot E4 — external reads removal

- remplacer les accès `PersistenceLayer/TDD/TIR/IOB/physio/preferences` par le snapshot ;
- remplacer notifications/files/logs par des événements ;
- vérifier par règle statique qu'aucun package plateforme n'est importé par l'engine.

### Lot E5 — stage extraction

- convertir la carte des 46 positions en stages typés ;
- conserver les early returns comme résultats sealed explicites ;
- tester chaque stage et l'orchestrateur complet ;
- supprimer les bundles qui transportent des références mutables AAPS.

### Lot E6 — commonMain

- déplacer les stages et domaines purs ;
- remplacer `org.json`, `java.io`, `java.text`, `java.util.concurrent` et types AAPS résiduels ;
- compiler et tester sur JVM et Native à chaque sous-lot.

## 9. Règles statiques proposées

Le source set du moteur commun doit échouer au build s'il contient :

- `android.*`, `androidx.*`, `java.io.*`, `java.text.*` ;
- `System.currentTimeMillis` ou une lecture directe de timezone ;
- `org.json.*` ;
- `app.aaps.core.interfaces.db.PersistenceLayer` ;
- `Preferences`, `SP`, `UiInteraction`, `NotificationManager` ;
- TFLite/ONNX/HealthKit/Health Connect concrets ;
- commandes pompe ou fichier ;
- lancement de coroutine non rattaché à un scope possédé par le shell.

Les tests peuvent utiliser des adaptateurs JVM, mais les contrats communs restent indépendants.

## 10. Gates spécifiques à l'état

| Gate | Preuve |
|---|---|
| ST0 | toutes les lectures avant écriture sont détectées |
| ST1 | tout champ mutable a une taxonomie et un propriétaire |
| ST2 | état avant/après sérialisable et hashable |
| ST3 | replay séquentiel reproduit Android |
| ST4 | tick précédent empoisonné ne contamine pas le suivant |
| ST5 | restart explicite reproduit la politique documentée |
| ST6 | deux ticks concurrents sont sérialisés sans double enactment |
| ST7 | crash avant/après commit est repris idempotemment |
| ST8 | publication modèle interrompue conserve le dernier modèle sain |
| ST9 | erreurs export/Hormonitor n'altèrent ni état causal ni commande |
| ST10 | `event_id`, `tick_id` et séquences Hormonitor permettent de détecter chaque trou |
| ST11 | state/model/dataset generations sont capturées atomiquement |

## 11. Estimation de cette seule extraction

| Travail | P50 | P80 |
|---|---:|---:|
| registre de champs et read-set | 2–3 sp | 4–5 sp |
| snapshot/config/capture v1 | 3–5 sp | 6–8 sp |
| working state et isolation tick | 3–5 sp | 6–8 sp |
| état causal et commit | 3–5 sp | 6–8 sp |
| suppression des accès externes | 4–7 sp | 8–11 sp |
| stages et early returns typés | 5–8 sp | 9–13 sp |
| replay/comparator/CI Native | 4–6 sp | 7–10 sp |
| **Total avec recouvrements** | **18–28 sp** | **30–42 sp** |

Cette charge explique pourquoi « remplacer `Context.getString` puis déplacer le fichier » ne suffit
pas. Les lignes Android explicites sont peu nombreuses ; la dépendance architecturale à l'état et
aux données AAPS est profonde.

## 12. Décisions à prendre à G0/G1

1. Quels états sont persistés lors d'un restart et lesquels sont reconstruits ?
2. Quelle est la frontière transactionnelle entre commit du state et enactment pompe ?
3. Quels historiques doivent être matérialisés dans le snapshot plutôt que recalculés ?
4. Quel format du corpus privé permet la précision complète sans exposer de données personnelles ?
5. Quel nombre de jours/utilisateurs/scénarios est requis avant passage de shadow à enactment ?
6. Quelle version des préférences constitue le premier `AimiConfigSnapshot` stable ?
7. Comment quantifier exactement les commandes pour le premier driver iOS ?
8. Quelle politique de reset prudent appliquer si l'état versionné est illisible ?

La réponse à ces décisions précède l'écriture d'un framework iOS. Sans elle, le framework pourrait
compiler tout en exécutant un AIMI physiologiquement différent.
