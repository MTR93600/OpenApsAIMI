# AIMI KMP/iOS — blueprint d'architecture et de migration

> **Statut :** document directeur consolidé, prêt pour décisions G0.  
> **Référence auditée :** `dev_OAPSAIMI` à `06e7bc5021ca8fdd976505d1fefb03cc88681c19`.  
> **Fondation KMP :** `kmp` à `4957c26eb85a71103e649498e7e991cb473e3098`.  
> **Base commune :** `7fc8205e9a73259cec2982fc199f3d2055f84347`.  
> **Date :** 2026-08-25.  
> **Nature :** architecture cible, périmètre, gates et charges. Les annexes 5 à 9 portent les preuves détaillées.

## 0. Décision d'architecture

Le portage du **moteur AIMI** vers Kotlin Multiplatform et son intégration dans une application iOS
de type Trio/LoopKit sont techniquement possibles. L'objectif réaliste est une **parité du
comportement décisionnel AIMI**, et non une copie exacte de toutes les surfaces de l'application
Android AAPS.

La migration doit respecter les décisions suivantes :

1. **Conserver le modèle `modelUAM.tflite`.** Il devient un artefact versionné commun, exécuté par
   un adaptateur LiteRT/TFLite Android et un adaptateur TFLite iOS. Une réécriture ultérieure en
   Kotlin n'est acceptable qu'après extraction exacte du graphe et preuve de parité.
2. **Extraire un moteur stateful**, et non déplacer uniquement `DetermineBasalAIMI2.kt` ou exposer
   la méthode actuelle comme une boîte noire JSON.
3. **Préserver l'ordre du tick et ses autorités.** L'état causal alimente le Physiological Tree,
   qui produit l'intention insulinique ; MealCertainty peut la réviser ; Harmonia choisit l'action ;
   RBT construit les croyances et arbitre les canaux ; les branches de dose, le second passage
   Harmonia et les protections terminales bornent ensuite l'action.
4. **Séparer calcul et effets.** Le moteur retourne une décision, une transition d'état et des
   événements. Les lectures de base de données, écritures, notifications et commandes pompe sont
   exécutées par les shells Android/iOS.
5. **Faire d'Android le master de comparaison** jusqu'à ce que chaque gate de replay et de shadow
   soit passée. Android peut ensuite consommer le même moteur commun.
6. **Distinguer replay et réentraînement.** Un replay utilise exactement les mêmes artefacts,
   checksums, schémas et générations de modèles. Un réentraînement indépendant peut produire des
   poids différents si ses métriques, gates de publication et décisions finales restent équivalentes.
7. **Ne jamais rendre le dosage dépendant de la disponibilité d'un entraînement en arrière-plan.**
   Le dernier modèle validé et les politiques déterministes restent toujours utilisables.

## 1. Baseline mesurée

### 1.1 Taille du domaine

| Élément | Mesure sur `dev_OAPSAIMI` |
|---|---:|
| Sources Kotlin AIMI `main` | 441 fichiers / 102 354 LOC |
| Tests Kotlin AIMI | 243 fichiers / 31 962 LOC |
| Racine du package | 22 fichiers / 24 602 LOC |
| Packages de premier niveau | 41 |
| `DetermineBasalAIMI2.kt` | 18 886 LOC, soit 18,5 % du code principal |
| `OpenAPSAIMIPlugin.kt` | 2 282 LOC |
| Fichiers important `app.aaps.core.*` | 156 fichiers / 63 824 LOC |
| Fichiers utilisant `System.currentTimeMillis` | 80 |
| Fichiers utilisant `java.util.concurrent` | 43 |
| Fichiers utilisant `org.json` | 65 |
| Fichiers utilisant des fichiers JVM | 37 environ |
| Fichiers WorkManager | 9 |
| Fichiers Health Connect | 6 / 2 208 LOC |

La taille n'est pas le risque principal. Les risques dominants sont :

- l'état mutable caché entre les ticks ;
- l'ordre des effets et des retours anticipés ;
- les lectures AAPS faites au milieu du calcul ;
- la divergence possible des données physiologiques ;
- la publication concurrente des modèles entraînés ;
- le fait que les tests de replay actuels ne réexécutent pas le moteur complet.

### 1.2 Ce qui existe déjà et doit être réutilisé

Le code possède plusieurs embryons de la bonne architecture :

- `AimiTickContext` regroupe les paramètres explicites du tick ;
- `AimiDetermineBasalTickOrchestrator` définit un point de délégation unique ;
- `AimiLoopPhase` et `AimiLoopTelemetry` suivent le cycle ;
- `AimiLoopTickRecovery` retourne une attente sûre en cas d'erreur ;
- `AimiIntelligenceSnapshot` regroupe causalité, cinétique, ISF, prédictions et features ML ;
- `DoseTerminalSnapshot` fixe une vérité unique des terminaux de prédiction ;
- `AsyncDataState` distingue données fraîches, périmées et absentes ;
- les packages `ports`, `model`, `orchestration`, `patient`, `recursive`, `risk`, `scenario` et
  `safety` contiennent déjà une part importante des concepts nécessaires.

Ces types ne forment cependant pas encore une frontière KMP : ils importent encore des types AAPS,
des préférences, des calculateurs, `org.json`, `UiInteraction` ou des collections Android.

## 2. Définition exacte de la parité

### 2.1 Quatre niveaux à ne pas confondre

| Niveau | Définition | Faisabilité |
|---|---|---|
| P0 — compilation | Le code compile pour Kotlin/Native | Nécessaire, très insuffisant |
| P1 — parité algorithmique | Même snapshot + même état donnent les mêmes valeurs internes dans les tolérances | Oui |
| P2 — parité décisionnelle | Même autorité finale, même SMB/TBR après caps et quantification | Oui, objectif principal |
| P3 — parité fonctionnelle AIMI | Learners, TFLite, physiologie, Hormonitor, recovery et historique restent actifs | Oui avec adaptateurs iOS |
| P4 — parité AAPS complète | Même UI, mêmes services Android, mêmes pompes et mêmes comportements OS | Non littéralement |

Le programme vise **P3 pour AIMI**. Les différences P4 doivent être explicites dans la matrice de
capabilities iOS et dans l'interface utilisateur.

### 2.2 Invariants de décision

Une migration n'est acceptée que si les invariants suivants sont vrais :

1. Les mêmes entrées invalides produisent un `safe hold`, jamais une dose improvisée.
2. Aucun learner, advisor, exporteur ou modèle optionnel ne peut contourner une autorité de sécurité.
3. Le canal basal-first et le canal SMB ne deviennent pas simultanément propriétaires en dehors
   des exceptions déjà documentées.
4. Aucun lift SMB Harmonia n'est silencieux : `LIFT_WITHIN_ENVELOPE` est nommé, tracé et reste borné
   par maxSMB, headroom IOB, caps HARD, post-hypo, stacking et limites pompe.
5. Le `DoseTerminalSnapshot` reste l'unique source dose-facing des terminaux de prédiction.
6. Les caps pompe, max IOB, post-hypo, activité, stacking et contraintes de profil restent appliqués
   dans le même ordre.
7. Une donnée absente reste `Missing`/`null` et n'est jamais transformée implicitement en zéro.
8. Une donnée périmée porte son âge et suit une politique explicite de conservation ou neutralisation.
9. Un tick ne publie ses états durables et ses modèles qu'après obtention d'une décision valide.
10. Une erreur d'export ou de télémétrie ne modifie jamais la dose.

## 3. Architecture cible

```text
                    Android AAPS shell                 iOS Trio / LoopKit shell
                    ------------------                 -------------------------
 histories/profile  AAPS repositories                  LoopKit stores
 pump/CGM events    AAPS plugins                       BLE managers
 physiology         Health Connect                     HealthKit + wearable adapters
                            |                                  |
                            +------------ adapters ------------+
                                             |
                                      AimiInputSnapshot
                                      AimiEngineState
                                             |
                  +--------------------------v--------------------------+
                  |                 :plugins:aimi-engine                |
                  |                                                   |
                  | validate -> causal/tree -> MealCertainty           |
                  | -> Harmonia -> terminals -> RBT/authority          |
                  | -> dose branches -> 2nd Harmonia -> terminal safety|
                  +--------------------------+--------------------------+
                                             |
                    AimiTickResult(decision, stateDelta, events, trace)
                                             |
                            +----------------+----------------+
                            |                                 |
                     Android effect runner              iOS effect runner
                     persistence/pump/UI                persistence/pump/UI
```

### 3.1 Modules recommandés

Le premier objectif n'est pas de transformer tout `:plugins:aps` en KMP. Il faut extraire une
unité verticale indépendante :

| Module/source set | Responsabilité |
|---|---|
| `:plugins:aimi-contracts` `commonMain` | DTO versionnés, unités, IDs, états `Fresh/Stale/Missing`, erreurs |
| `:plugins:aimi-engine` `commonMain` | arbre, Harmonia, RBT, prédictions, PKPD, safety, décision SMB/basal |
| `:plugins:aimi-learning` `commonMain` | réseaux Kotlin, trainers, validation/publication, learners déterministes |
| `:plugins:aimi-io` `commonMain` | interfaces de ports, codecs versionnés, journal d'événements |
| `:plugins:aimi-io` `androidMain` | adaptateurs AAPS, LiteRT Android, fichiers, scheduler |
| `:plugins:aimi-io` `iosMain` | adaptateurs TFLite iOS, fichiers, HealthKit bridge, scheduler |
| `:plugins:aimi-testkit` | capture, replay séquentiel, fixtures, comparateur et rapports |
| `:plugins:aps` Android | shell/plugin AAPS, contraintes, acquisition, enactment, UI Android |
| App iOS | shell LoopKit/Trio, BLE, cycle de vie, UI et enactment pompe |

Si la structure Gradle refuse plusieurs modules au début, ces responsabilités peuvent être des
packages/source sets d'un seul module KMP. Les frontières logiques ne doivent pas disparaître.

Dans Trio, le middleware existant est du JavaScript exécuté par JavaScriptCore ; il ne constitue pas
une SPI native suffisante pour AIMI. L'intégration ajoute un protocole Swift `DosingEngine` avec
implémentations `OrefJavaScriptEngine`, `AimiKmpEngine` et `ShadowDosingEngine`. Trio/Core Data reste
la source clinique canonique des glucoses, doses et glucides ; le store AIMI ne duplique que l'état,
les modèles, datasets et checkpoints propres à AIMI.

### 3.2 Contrat d'exécution

```kotlin
interface AimiEngine {
    fun evaluate(
        input: AimiInputSnapshot,
        state: AimiEngineState,
        models: AimiModelBundle
    ): AimiTickResult
}

data class AimiTickResult(
    val command: AimiTherapyCommand,
    val nextState: AimiEngineState,
    val trainingEvents: List<AimiTrainingEvent>,
    val persistenceEvents: List<AimiPersistenceEvent>,
    val telemetry: AimiDecisionTrace,
    val safety: AimiSafetyReport
)
```

Le moteur ne doit pas :

- commander directement une pompe ;
- ouvrir un fichier ;
- interroger Room/HealthKit ;
- afficher une notification ;
- lancer un worker ;
- appeler un service HTTP ;
- lire l'heure système en dehors du snapshot ;
- modifier une préférence globale.

### 3.3 `AimiInputSnapshot`

Le snapshot doit contenir les **valeurs déjà calculées**, pas des services capables de les calculer :

| Groupe | Contenu minimal |
|---|---|
| Métadonnées | `tickId`, wall clock, monotonic time, timezone, version de schéma |
| CGM | valeur, deltas, accélération, bruit, source, âge, qualité, durée stable |
| Pompe | basal actif, TBR actif, bolus step, basal step, limites, état de connexion |
| Profil | basal schedule, ISF statique/effectif, IC, DIA/peak, cibles, max IOB/basal |
| Insuline | IOB courant, activités, historique et tableaux de prédiction nécessaires |
| Repas | COB, carbs déclarés, timestamps, absorption et certitude |
| Historique | BG, bolus, TBR, TDD/TIR, changements de site et événements thérapeutiques |
| Physiologie | pas par fenêtres, HR, baseline HR, HRV typée, sommeil, température, disponibilité |
| Contexte | activité déclarée, repas, maladie, stress, cycle/endocrinien, grossesse |
| Configuration | snapshot immuable de toutes les préférences utilisées par ce tick |
| Modèles | IDs/checksums/versions et état de santé des modèles actifs |
| Capabilities | SMB autorisé, pompe compatible, données disponibles, droits plateforme |

Chaque valeur provenant d'une source asynchrone doit porter `value`, `capturedAt`, `age`, `quality`
et `availability`. Les horodatages et unités doivent être normalisés avant l'entrée du moteur.
La physiologie expose séparément `cgmConfidence`, `wearablePhysioConfidence` et
`causalConfidence` : l'absence d'une donnée wearable ne peut pas dégrader artificiellement la
confiance du CGM utilisée par les gates dose-facing.

### 3.4 État séparé du snapshot

| Catégorie | Exemples | Durée de vie | Règle de migration |
|---|---|---|---|
| Tick-local | variables de calcul, owners provisoires, courbes | un tick | jamais persisté |
| Cross-tick volatile | hystérésis, dernier owner, cooldown, dernières phases | minutes/heures | sérialisable et remis à zéro explicitement au lifecycle |
| Cache périmable | TDD/TIR, history slices, physiologie | secondes/minutes | âge et source obligatoires |
| Mémoire causale | événements patient, post-hypo, absorption, effort | heures/jours | schéma versionné et replayable |
| Learner state | EMA, facteurs, réseaux, optimiser, compteurs | jours/mois | publication atomique + rollback |
| Dataset | SMB/basal/autodrive/Hormonitor | long terme | append-only, schema/version/consentement |

Le code actuel mélange ces catégories dans des champs de singleton, des objets injectés, des
préférences et des fichiers. La migration doit les inventorier avant tout déplacement de source.

L'état explicite doit inclure au minimum les hystérésis absorption/endogène/pattern, la mémoire RBT
par horizon et ses épisodes, `RealTimeInsulinObserver`, l'état de fusion ISF, l'estimateur PKPD,
les fenêtres de damping/bolus, les agrégations et baselines physiologiques, ainsi que les profils
circadien et thermique. Chaque champ possède une autorité unique entre `EngineState`, checkpoint
learner et bundle modèle ; leurs générations sont capturées ensemble au début du tick.

Deux stores sont distincts :

- `DosingSnapshotStore`, immuable et corrélé au `tickId`, contient exactement l'état ayant dosé ;
- `PresentationStore`, éventuellement rafraîchi de façon asynchrone, alimente l'UI mais ne peut
  jamais revenir dans le chemin de dosage.

## 4. Préservation de la séquence du tick

La roadmap interne décrit 46 positions, de l'enveloppe de télémétrie au retour final. Elles doivent
être regroupées en stages testables, sans réordonnancement implicite.

| Stage KMP cible | Étapes actuelles | Contenu et contraintes |
|---|---:|---|
| S0 — acquisition/validation shell | avant #0 | profil, pompe, historique, physiologie, modèles ; aucun accès externe après construction du snapshot |
| S1 — bootstrap | #0–10 | lock de tick, caches, contexte, TDD, physio précoce, PKPD, glucose/IOB |
| S2 — modes et préparation | #11–16 | therapy, repas legacy, T3C brittle, trajectoire, contexte, ISF, prédictions |
| S3 — safety précoce et branches prioritaires | #17–23 | halt, Meal Advisor, Hard Brake, Autodrive, post-hypo, drift |
| S4 — causalité et décision | #24–34 | schedule, physio, UAM/TFLite, SMB, guards PKPD, états de commande |
| S5 — arbitrage terminal | #35–43 | boost basal, WCycle, CarbsAdvisor, safety, max IOB, SMB, BasalEngine, auditor |
| S6 — commit/export shell | #44–45 | snapshot médical, Hormonitor, publication d'état, retour ; les erreurs d'export sont isolées |

Ordres spécifiquement gelés :

- therapy → modes repas legacy → T3C ;
- safety initiale → Meal Advisor ;
- Hard Brake → Autodrive ;
- classification post-hypo une seule fois après Autodrive ;
- construction des terminaux de prédiction → consommateurs dose-facing ;
- calcul SMB/basal → safety terminale → commande ;
- commande validée → commit des états/événements → export.

Le futur orchestrateur ne doit pas seulement déléguer vers une méthode de 18 886 lignes. Chaque
stage doit avoir une entrée/sortie typée et un test de contrat.

À l'intérieur de ces stages, la séquence normative est : état causal → Tree → MealCertainty et
révision éventuelle de l'intention → HarmoniaDecision → publication des terminaux → construction et
résolution RBT → authority gate → branches Autodrive/SMB/basal → `HarmoniaSmbArbiter` éventuel →
propriétaire basal exclusif → `HarmoniaHarmonizer` (`CONFIRM/SOFTEN/BLOCK`) → protections
terminales → quantification. Autodrive peut provoquer un second refresh phase/absorption/latent ;
ce chemin doit rester explicite. L'annexe 6 §3 est le contrat détaillé de cet ordre.

Les transitions d'état effectuées avant une sortie anticipée nécessitent une politique par état :
`commit-on-safe-hold`, `rollback-on-abort` ou événement différé. Un commit global aveugle en fin de
tick ne reproduirait pas nécessairement Android, car certaines mémoires absorption/PKPD évoluent
avant un retour ultérieur.

## 5. Ports nécessaires

| Port commun | Remplace | Android | iOS |
|---|---|---|---|
| `AimiClock` | `System.currentTimeMillis`, `DateUtil`, `SystemClock` | horloges Android/JVM | wall + monotonic clocks Apple |
| `AimiHistorySource` | `PersistenceLayer`, TDD/TIR, IOB/COB reads | adaptateur AAPS/Room | adaptateur Trio/Core Data, source clinique canonique |
| `AimiPreferenceSnapshotSource` | `Preferences`, `SP` lus au milieu du tick | AAPS preferences | UserDefaults/stockage sécurisé |
| `AimiModelInference` | `AimiUamHandler`, ONNX scorer | LiteRT Android | TensorFlowLite C/Swift |
| `AimiModelStore` | fichiers JSON/TFLite, model stores | stockage privé app | Application Support/Documents |
| `AimiDatasetStore` | CSV/JSONL/data lake | app-private + export SAF | app-private + Document Picker |
| `AimiPhysiologySource` | Health Connect, step sensor, Oura | Health Connect/sensors | HealthKit/watch/wearable adapters |
| `AimiTrainingScheduler` | WorkManager | WorkManager + tick | tick + BGProcessingTask + foreground |
| `AimiLogger` | AAPSLogger/Log | AAPS logger | os_log/unified logging |
| `AimiNotifier` | notifications Android/UI | NotificationManager | UNUserNotificationCenter |
| `AimiLocalization` | `Context.getString`, `ResourceHelper` | Android resources | Localizable.strings/Swift facade |
| `AimiExportCoordinator` | SAF/partage | document provider | Files/share sheet |
| `AimiHttpClient` | HttpURLConnection/OkHttp | Ktor/adaptateur | Ktor/URLSession |
| `AimiPumpCapabilities` | ActivePlugin/pump description | pompe AAPS | driver LoopKit |

Les interfaces injectées au moteur ne doivent pas être des ports I/O synchrones. L'acquisition se
fait avant `evaluate`; les ports servent au shell et aux tâches asynchrones.

## 6. Carte de migration par domaine

### 6.1 Dosing critique — priorité absolue

| Domaine | Taille mesurée | Cible | Traitement |
|---|---:|---|---|
| racine AIMI | 22 / 24 602 LOC | partagé partiellement | découper `DetermineBasal`, conserver shell plugin Android |
| `patient` | 18 / 4 376 | `commonMain` | tree, causal posterior, Harmonia, mémoire patient |
| `recursive` | 22 / 3 989 | `commonMain` | RBT, authority, channel selection |
| `safety` | 24 / 1 993 | `commonMain` | guards purs ; acquisition hors moteur |
| `pkpd` | 27 / 3 519 | `commonMain` majoritaire | isoler préférences, logs et historiques |
| `basal` | 9 / 1 976 | `commonMain` | moteur/planificateur/terminal invariants |
| `orchestration` | 13 / 1 377 | `commonMain` après DTO | supprimer imports AAPS des snapshots |
| `risk`, `scenario`, `release` | 21 / 2 260 | `commonMain` | ports faibles, tests Native |
| `prediction`, `trajectory`, `ISF` | 15 / 2 533 | `commonMain` | normaliser temps/histoire/préférences |
| `smb` | 8 / 894 | logique commune + enactment shell | `SmbInstructionExecutor` ne commande pas la pompe |

### 6.2 Adaptation et apprentissage

| Domaine | Taille | Cible |
|---|---:|---|
| `ml` | 6 / 1 040 LOC | réseau/trainer commun, logger/store en ports |
| `learning` | 10 / 3 565 | learners communs, coordinateurs découplés du scheduler |
| `autodrive` | 18 / 3 793 | engine/estimator/safety communs, backfill et store en shell |
| `wcycle` | 10 / 869 | modèle commun, stockage/CSV en ports |
| TFLite racine | 1 / 297 | interface commune, actual Android/iOS, modèle conservé |

### 6.3 Données physiologiques

| Domaine | Taille | Cible |
|---|---:|---|
| `physio` | 61 / 11 549 LOC | modèles/algorithmes communs ; acquisition Health Connect/HealthKit séparée |
| `steps` | 11 / 1 731 | agrégations communes ; sources Android/iOS séparées |
| `activity` | 4 / 475 | croyance et policy communes |
| `inflammatory` | 1 / 52 | commun |

### 6.4 Fonctions produit non indispensables au premier dosage iOS

| Domaine | Taille | Décision initiale |
|---|---:|---|
| `advisor` | 63 / 15 207 LOC | contrats/calculs partagés ; Activities, LLM et providers dans le shell |
| `context` | 11 / 3 459 | modèle/influence partagés ; UI et réseau par plateforme |
| `tpo` | 12 / 1 937 | règles communes ; notifications/LLM par plateforme |
| `compose` | 10 / 3 915 | pas dans le framework Trio v1 ; UI iOS native |
| `comparison` | 8 / 2 553 | déplacer plus tard dans testkit/outillage |
| `sos` | 2 / 454 | feature plateforme, non requise par le moteur |
| `hormonitor` | 4 / 887 | modèles/reader communs ; viewer et export par plateforme |
| `quality` | 2 / 622 | testkit commun |
| `utils`, `di`, `keys` | 11 / 1 263 | remplacer par ports, composition root et config typée |

Le premier framework iOS ne doit pas exporter les Activities Android, Compose UI, Dagger, SMS,
SAF, WorkManager ou les clients LLM. Il exporte les capacités AIMI nécessaires au dosage et à
l'observabilité.

## 7. Stratégie TFLite et modèles

### 7.1 Artefacts distincts

Le code actuel contient deux familles à ne pas mélanger :

- `modelUAM.tflite` : estimation UAM/SMB initiale, chemin vivant, 18 features ;
- réseaux `AimiNeuralNetwork` JSON : raffinement SMB, basal/T3C et autres entraînements locaux.

Le réseau Kotlin actuel à une couche cachée ne constitue pas une représentation automatique du
graphe TFLite. La migration doit donc commencer par une interface commune :

```kotlin
interface UamInferenceEngine {
    val modelId: String
    val schemaId: String
    fun infer(features: UamFeatureVector): UamInferenceResult
}
```

### 7.2 Gate modèle

Pour chaque plateforme :

1. vérifier le checksum du modèle ;
2. valider le schéma et les 18 positions ;
3. rejeter NaN/infini et tailles incorrectes ;
4. comparer un corpus de vecteurs normaux, limites et corrompus ;
5. mesurer latence, mémoire et température sur appareil réel ;
6. enregistrer runtime, modèle et schema ID dans chaque trace ;
7. fallback neutre identique si le modèle est absent ou invalide.

## 8. Replay et validation

### 8.1 Limite du corpus actuel

Les fixtures `day_in_range`, `day_rebound_cycles` et `day_hyper` sont des projections plates de
`AIMI_Decisions_Last24h.jsonl`. Elles permettent de mesurer et d'analyser des sorties historiques,
mais ne contiennent ni toutes les entrées ni tout l'état permettant de réexécuter le tick complet.

Elles restent utiles comme baseline comportementale, mais ne prouvent pas la parité KMP.

### 8.2 Nouveau format de capture

Chaque enregistrement de replay doit contenir :

- `schemaVersion`, build, plateforme et timestamp ;
- `AimiInputSnapshot` complet ;
- `AimiEngineState` avant tick ;
- manifest des modèles/checksums ;
- commande attendue avant/après quantification pompe ;
- `AimiEngineState` après tick ;
- events learners/persistence/export ;
- owners, autorités, gates, caps et raisons ;
- tolérances par champ.

Les données directement identifiantes restent hors dépôt. Le dépôt public ne conserve que des
fixtures synthétiques ou explicitement consenties et minimisées.

### 8.3 Pyramide de tests

| Niveau | Test | Gate |
|---|---|---|
| U0 | fonctions mathématiques et invariants | JVM + Native passent |
| U1 | stages avec snapshots synthétiques | mêmes branches/owners |
| M0 | modèles TFLite et Kotlin | sorties dans tolérances, fallbacks identiques |
| R0 | replay séquentiel Android ancien vs nouveau | commande finale identique |
| R1 | replay JVM vs iOS simulator/device | commande finale identique |
| S0 | shadow Android 72 h minimum | zéro divergence inexpliquée |
| S1 | shadow iOS sans enactment | aucune divergence critique sur plusieurs semaines |
| E0 | enactment fermé sur simulateur de pompe | caps, arrondis, idempotence |
| F0 | terrain contrôlé | critères sécurité et rollback satisfaits |

La comparaison porte d'abord sur : `halt`, `authority`, `originOwner`, `finalOwner`, SMB demandé,
SMB quantifié, TBR rate/durée, suspend/resume, caps, `eventual`, `minPred`, modèle actif et état
avant/après.

## 9. Plan de livraison et gates

### Gate G0 — figer la référence

- décider par ADR si les défauts connus sont reproduits ou corrigés sur Android ;
- appliquer les corrections retenues, puis taguer le **nouveau** commit clinique de référence ;
- figer hashes, modèles, préférences et schémas ;
- lister les features actives/dormantes ;
- documenter la pompe/CGM cible iOS ;
- interdire les modifications silencieuses de logique pendant l'extraction.

**Sortie :** baseline reproductible et matrice de capabilities validée.

### Gate G1 — capture exécutable

- construire `AimiInputSnapshot`/`AimiEngineState` v1 sur Android ;
- capturer toutes les lectures externes avant le tick ;
- enregistrer états avant/après et sorties TFLite ;
- rejouer séquentiellement sans pompe.

**Stop si :** un résultat Android ne peut pas être reproduit à partir de la capture.

### Gate G2 — vertical slice Native

- porter contrats, unités, clock et serialization ;
- porter état causal → Tree → MealCertainty → Harmonia → RBT → branches de dose → second passage
  Harmonia → sécurité terminale sur un scénario vertical ;
- exécuter les mêmes tests sur JVM, iOS Simulator et iPhone.

**Stop si :** l'autorité ou la commande diverge sans raison documentée.

### Gate G3 — moteur commun complet, Android master

- extraire les stages du tick ;
- remplacer accès service par snapshot/events ;
- faire consommer le moteur KMP par `OpenAPSAIMIPlugin` ;
- exécuter replay puis shadow Android.

**Stop si :** un retour anticipé, owner ou gate change involontairement.

### Gate G4 — ML et learners

- intégrer TFLite iOS sans changer le modèle ;
- porter network/trainers/stores ;
- qualifier BasalLearner, UnifiedReactivity, basal/T3C, SMB et Autodrive attention/backfill ;
- conserver OnlineLearner et OREF personnel explicitement en shadow ;
- tester interruption, rollback, corruption, concurrence et faible mémoire ;
- benchmarker entraînements sur appareils réels.

**Stop si :** un modèle incomplet peut être publié ou le dosage dépend du scheduler.

### Gate G5 — physiologie et Hormonitor

- implémenter HealthKit/availability/units ;
- distinguer HRV RMSSD et SDNN ;
- porter schémas Hormonitor et export ;
- ajouter `DatasetIdentity`, `event_id`, `tick_id`, séquence monotone, compteur d'overflow exact,
  schéma additif 1.5.x et affichage des trous ;
- tester permissions refusées, données absentes/périmées et changement de timezone.

**Stop si :** absence de donnée devient une preuve physiologique ou une valeur zéro.

### Gate G6 — intégration Trio shadow

- mapper les stores LoopKit vers le snapshot ;
- introduire le protocole natif `DosingEngine`; ne pas charger AIMI comme middleware JavaScript ;
- faire tourner AIMI à chaque heartbeat pertinent ;
- enregistrer mais ne pas commander ;
- comparer aux décisions Android sur corpus commun.

### Gate G7 — enactment contrôlé

- utiliser une seule pompe/CGM BLE explicitement supportée ;
- quantifier la commande dans le shell ;
- vérifier idempotence, reconnexion, relance, verrou et safe hold ;
- fournir kill switch, rollback moteur/modèle et export diagnostic.

### Gate G8 — extension fonctionnelle

- activer progressivement Autodrive training/backfill, advisors, UI, services distants et autres
  combinaisons matériel ;
- ne pas les confondre avec la preuve de sécurité du moteur de base.

## 10. Charges de référence consolidées

Les annexes spécialisées comptent volontairement leurs fondations et validations afin de donner le
coût autonome de chaque domaine. Leurs totaux ne doivent donc pas être additionnés. La WBS suivante
attribue chaque socle partagé à une seule ligne. Une semaine-personne représente cinq jours effectifs.

| Propriétaire de coût unique | P50 | Contenu non compté ailleurs |
|---|---:|---|
| socle contrats/capture/replay/CI/actor/state | 25–40 sp | snapshot, état, testkit, commit, stockage de base |
| moteur décisionnel incrémental | 30–45 sp | Tree, MealCertainty, Harmonia, RBT, PKPD, safety, terminal |
| ML et learners incrémentaux | 30–50 sp | TFLite, SMB/basal/T3C, learners, Autodrive learning |
| données physiologiques et Hormonitor | 18–30 sp | HealthKit, métriques, baselines, writer/intégrité/viewer minimal |
| fonctions dose-facing adjacentes | 10–18 sp | TPO, Meal Advisor, Local Sentinel, Auditor différé |
| runtime Apple/Trio/device/lifecycle | 40–65 sp | framework, DosingEngine, store, BLE, background, première paire |
| UI/diagnostics produit étendus | 8–15 sp | SwiftUI, providers, status/rollback/export |
| qualification intégrée et shadow | 15–25 sp | campagne transversale non parallélisable |

La somme de cette WBS dédupliquée est de **176–288 semaines-personnes**. Les travaux peuvent être
parallélisés pour réduire la durée calendaire, mais ce parallélisme ne réduit pas les
semaines-personnes. Le budget de programme par périmètre devient donc :

| Périmètre livré | P50 consolidé | P80 de programme | Commentaire |
|---|---:|---:|---|
| preuve go/no-go capture + TFLite + slice Native | 8–12 sp | 14–20 sp | pas une version opérationnelle |
| moteur AIMI KMP sur Android, core décisionnel | 60–90 sp | 95–125 sp | learners/physio étendus partiels |
| AIMI iOS shadow ciblé, un device, physio prudente | 125–200 sp | 175–270 sp | sans tous les learners et surfaces produit |
| **parité fonctionnelle AIMI étendue demandée** | **175–290 sp** | **245–380 sp** | WBS complète arrondie : learners, Autodrive ML, HealthKit, Hormonitor, produit actif |
| port complet de tout AAPS | **> 300 sp, à re-chiffrer** | non borné | certaines capabilities iOS n'ont pas d'équivalent strict |

Cette correction remplace l'ancien total 51 sp et le premier cadrage 85–130 sp. Elle est cohérente
avec les audits autonomes : ML 30–50 sp nets, cœur/physiologie 48–76 sp avec socles, et runtime iOS
51–83 sp hors moteur. Le chiffrage exclut certification réglementaire, étude clinique, support
multi-pompes complet et réécriture de toute l'UI AAPS.

### 10.1 Conversion en budget et calendrier

La conversion monétaire doit rester paramétrique :

`budget = semaines-personnes × 5 jours × taux journalier chargé`.

À titre de repère, avec un taux chargé moyen de **1 000 EUR/jour**, la parité AIMI étendue représente
environ **0,88–1,45 M EUR en P50**, ou **1,23–1,90 M EUR en enveloppe P80**. Ce n'est pas un devis :
matériel de test, comptes Apple, support terrain, cybersécurité formelle, qualité réglementaire et
validation clinique restent hors périmètre.

Avec une équipe réellement dédiée de cinq à sept personnes couvrant KMP, ML, iOS/LoopKit, data et
validation, les dépendances G0→G7 conduisent à un ordre de grandeur de **10 à 16 mois calendaires en
P50**. Ajouter des personnes au-delà de cette équipe ne compresse pas les captures longitudinales,
le shadow, les essais matériels ni les décisions de sécurité.

## 11. Gouvernance de la migration

### 11.1 Branches et flux

- le commit audité reste la preuve historique ; l'ADR G0 choisit les corrections Android, puis le
  commit clinique résultant devient la référence gelée pour la capture ;
- Les changements d'architecture sont livrés par petits lots verticaux sur la fondation KMP.
- Chaque lot porte un identifiant de gate et un rapport de replay.
- Un changement intentionnel de comportement exige un ADR avec avant/après mesuré.
- Les refactors mécaniques et les changements de politique thérapeutique ne partagent pas le même PR.

### 11.2 Propriétaires techniques

| Domaine | Propriétaire de revue |
|---|---|
| modèles/features/training | ML + sécurité modèle |
| Tree/Harmonia/RBT/PKPD | moteur décisionnel |
| snapshot/state/replay | architecture KMP |
| HealthKit/background/Trio | iOS/LoopKit |
| enactment pompe/caps | intégration device |
| Hormonitor/datasets/privacy | data/observabilité |

Une modification touchant deux autorités exige les deux propriétaires. Une modification du schéma
ML ou Hormonitor exige version et migration explicites.

## 12. Décisions ouvertes avant implémentation

| ID | Décision | Recommandation actuelle |
|---|---|---|
| D1 | Application hôte iOS | Trio/LoopKit plutôt qu'un port de toute l'application AAPS |
| D2 | Première pompe/CGM | paire BLE déjà supportée par LoopKit, à nommer à G0 |
| D3 | Format frontière Swift | API typée pour le runtime, JSON versionné pour capture/support |
| D4 | Runtime TFLite | même modèle, versions de runtime épinglées par plateforme |
| D5 | Base locale iOS | réutiliser store LoopKit + store AIMI privé versionné ; éviter double vérité |
| D6 | UI iOS AIMI | SwiftUI native pour v1 ; ne pas bloquer le moteur sur Compose MP |
| D7 | HRV | métrique typée ; aucune substitution silencieuse RMSSD/SDNN |
| D8 | Training lourd | opportuniste, reprenable, jamais requis pour le tick courant |
| D9 | Critère numérique | commandes exactes après quantification, tolérances internes par champ |
| D10 | Données de replay | corpus privé chiffré + petit corpus public synthétique/minimisé |
| D11 | Timeout/état pompe inconnu | définir `Hold`, conservation TBR sûre ou fallback oref par scénario avant I0 |
| D12 | Migration Android→iOS | bundle AIMI versionné uniquement ; jamais pairings, secrets, permissions ou commandes en attente |

## 13. Relation avec les études existantes

Les annexes 1 à 4 restent les preuves d'inventaire et de divergence. Le présent blueprint remplace
leurs recommandations lorsqu'elles sont contradictoires, notamment :

- ne pas supprimer TFLite sur la seule base de la taille du modèle ;
- ne pas considérer `AimiNeuralNetwork` comme identique au modèle TFLite ;
- ne pas qualifier les fichiers sans import Android de « déplaçables inchangés » sans compilation
  Kotlin/Native et audit de leurs types transitifs ;
- ne pas traiter `DetermineBasal` comme une frontière JSON complète ;
- ne pas utiliser les projections de sortie existantes comme preuve de replay du moteur ;
- ne pas annoncer une périodicité iOS garantie ;
- ne pas déclarer Critical Alerts techniquement impossible : l'entitlement est spécial et non garanti ;
- ne pas présenter 51 semaines-personnes comme le coût d'une parité AIMI complète.

## 14. Definition of Done

La migration AIMI iOS ciblée est terminée seulement lorsque :

1. le même moteur KMP tourne sur Android et iOS ;
2. le modèle TFLite initial est conservé ou remplacé par une implémentation prouvée équivalente ;
3. les learners SMB/basal et leurs fallbacks fonctionnent après interruption/restart ;
4. Tree, Harmonia, RBT, PKPD et safety ont des tests Native et des replays séquentiels ;
5. les commandes finales sont identiques après quantification sur le corpus de référence ;
6. les données physiologiques ont unités, source, qualité et âge explicites ;
7. Hormonitor produit un schéma versionné comparable sur les deux plateformes ;
8. le loop survit à l'absence de modèle, de HealthKit, de réseau et de tâche de fond ;
9. le shell iOS supporte relance, reconnexion BLE, lock, safe hold, rollback et diagnostic ;
10. les limites de capabilities et le matériel supporté sont documentés sans ambiguïté ;
11. plusieurs semaines de shadow iOS ne montrent aucune divergence critique inexpliquée ;
12. un kill switch permet de revenir au moteur ou modèle précédent sans migration destructive ;
13. BasalLearner, UnifiedReactivity, SMB, basal/T3C et Autodrive learning ont chacun une preuve de
    continuité après restart ; OnlineLearner et OREF personnel restent signalés `SHADOW`.
14. l'exécution iOS est validée comme événementielle : aucune sécurité ni continuité ne suppose un
    timer garanti toutes les cinq minutes.
