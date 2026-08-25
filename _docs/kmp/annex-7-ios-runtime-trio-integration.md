# Annexe 7 — Runtime iOS, framework KMP et intégration Trio

> **Périmètre.** Cette annexe traite uniquement du côté Apple de la migration AIMI : production et
> exposition du framework Kotlin/Native, contrat avec Swift, intégration dans Trio, cycle de vie iOS,
> exécution en arrière-plan, persistance, HealthKit, notifications, drivers, distribution,
> observabilité et sécurité opérationnelle. Elle ne chiffre pas le portage des algorithmes AIMI vers
> `commonMain`, traité par les autres annexes.
>
> **Références auditées.** AIMI est observé sur `dev_OAPSAIMI` au commit
> `06e7bc5021ca8fdd976505d1fefb03cc88681c19`. L'état KMP local est observé sur la branche
> `kmp-aimi-migration-study`. Trio `main` est observé au commit
> [`29350e31`](https://github.com/nightscout/Trio/commit/29350e31a9f8b25dcea719f52fa1abb676e34af8),
> version 0.8.4, le 25 août 2026. Les détails Trio sont donc un **point de référence versionné**, pas
> une promesse sur une future version du projet.

## 1. Verdict exécutif

L'intégration d'AIMI dans Trio est techniquement réalisable avec les propriétés suivantes :

- AIMI est livré sous la forme d'un **unique XCFramework statique** appelé ici `AimiKit` ;
- Trio conserve la propriété du CGM, de la pompe, de la boucle iOS, de l'historique clinique et de
  l'exécution finale des commandes ;
- le moteur KMP conserve la propriété de l'arbre physiologique, de Harmonia, des croyances, des
  protections AIMI, des learners, de l'inférence et de l'état AIMI ;
- la frontière Swift/Kotlin est petite, versionnée et transactionnelle ;
- l'inférence utilise le même `modelUAM.tflite` sur Android et iOS ; il n'est ni supprimé ni converti
  en un autre réseau sans preuve de parité ;
- l'exécution d'une boucle fermée iOS est **événementielle**, principalement réveillée par BLE, et
  non une tâche périodique garantie toutes les cinq minutes ;
- les entraînements et les backfills sont repris par une file durable et opportuniste ; ils ne sont
  jamais une condition de production d'une dose ;
- la parité de la décision AIMI est atteignable. La parité de toutes les surfaces Android ne l'est
  pas : Bluetooth Classic sans MFi, SMS, système de fichiers partagé Android et alarmes critiques
  sans entitlement Apple restent différents.

Le coût du seul lot décrit dans cette annexe est estimé à **51–83 semaines-personnes**, hors portage
du moteur `commonMain`, avec un budget P80 de **70–105 semaines-personnes** incluant la qualification
matérielle d'une combinaison CGM/pompe. Ce montant explique pourquoi un total de 51 semaines-personnes
ne peut pas représenter à la fois le moteur AIMI complet et l'intégration iOS complète.

## 2. Constats vérifiés dans les dépôts

### 2.1 État de la fondation KMP locale

La fondation est réelle, mais elle ne constitue pas encore un produit Apple :

- plusieurs modules déclarent `iosArm64()` et `iosSimulatorArm64()` ;
- `plugins/aps` déclare ces cibles sur la branche de travail KMP ;
- aucun module ne déclare encore `binaries.framework`, un umbrella framework ou un assemblage
  XCFramework pour AIMI ;
- il n'existe pas d'application iOS locale consommant le code ;
- le workflow `.github/workflows/ios-ci.yml` compile et exécute principalement les tests Native de
  `core:data`, `core:nssdk` et `core:keys`. AIMI et la majorité des modules partagés ne sont pas
  exécutés sur simulateur dans ce workflow ;
- le module AIMI de `dev_OAPSAIMI` est encore un module Android et contient `Context`, WorkManager,
  Health Connect, `java.io.File`, `org.json`, les atomiques JVM, Dagger et les runtimes ML Android.

Conséquence : déclarer une cible `iosArm64` prouve au mieux qu'une partie de la graphe produit une
KLIB. Cela ne prouve ni le link final, ni la stabilité de l'API Swift, ni le comportement sur un
iPhone verrouillé, ni la capacité à terminer une boucle dans le budget de réveil.

### 2.2 Point d'intégration réel de Trio 0.8.4

Trio utilise encore JavaScriptCore dans `Trio/Sources/APS/OpenAPS` :

- `OpenAPS.determineBasal(...)` rassemble l'historique pompe, les glucoses, les glucides, le profil,
  l'autosens et le réservoir ;
- les calculs meal et IOB sont exécutés avant `determine-basal` ;
- `JavaScriptWorker` charge les scripts `prepare`, `bundle/determine-basal.js` et, si présent, le
  script `middleware/determine_basal.js` ;
- `APSManager.determineBasal()` contrôle la fraîcheur de la glycémie, lance le calcul et publie la
  détermination ;
- `APSManager.executeLoop()` appelle ensuite `enactDetermination()` en boucle fermée ;
- `APSManager` ouvre une assertion UIKit `beginBackgroundTask` pour laisser la boucle se terminer.

Le middleware est donc une extension **JavaScript évaluée dans le worker JavaScript**. Il ne fournit
ni une SPI native pour charger Kotlin/Native, ni l'état AIMI, ni le protocole de commit nécessaire aux
learners. La formulation « le middleware permet l'intégration sans fork de Trio » est trop optimiste.
Il faut au minimum une modification locale ou un PR Trio introduisant un protocole de moteur de dose.

L'autre raison est fonctionnelle : `DetermineBasalAIMI2` n'est pas une fonction autonome équivalente
au `determine-basal.js` de Trio. Il consomme davantage d'historique, des services de persistence, les
learners et un état conservé entre les ticks. Passer uniquement les treize arguments JSON de la
fonction Trio actuelle ne reconstitue pas le comportement AIMI.

### 2.3 Cycle BLE vérifié dans Trio

Trio contient un mécanisme explicite de heartbeat :

- `BluetoothTransmitter` crée un `CBCentralManager` avec
  `CBCentralManagerOptionRestoreIdentifierKey` ;
- une notification de caractéristique ou une déconnexion appelle le heartbeat ;
- le heartbeat déclenche la mise à jour de la pompe dans `DeviceDataManager` ;
- `recommendsLoop` déclenche ensuite `APSManager.loop()` ;
- `Info.plist` déclare notamment `bluetooth-central`, `processing`, `remote-notification` et le
  besoin d'accès Bluetooth.

Ce mécanisme démontre que la boucle événementielle est une voie crédible. Il ne faut cependant pas
le résumer à « iOS garantit la boucle cinq minutes ». Apple précise qu'un réveil BLE doit être traité
rapidement — historiquement de l'ordre de dix secondes — et qu'une application ne peut pas exécuter
du code indéfiniment en arrière-plan. La restauration ne se produit que si une opération BLE précise
est en attente et qu'un événement correspondant survient.

Autre constat à traiter avant de revendiquer la restauration complète : dans le snapshot Trio audité,
`BluetoothTransmitter.centralManager(_:willRestoreState:)` journalise l'appel mais n'exploite pas le
dictionnaire de périphériques restaurés. Apple demande de récupérer ces périphériques et de
réinstaller leurs delegates. Les drivers LoopKit peuvent avoir leurs propres chemins de restauration,
mais **chaque combinaison CGM/pompe doit être validée**, et le transmetteur générique doit être
renforcé si AIMI en dépend.

### 2.4 HealthKit de Trio n'est pas encore le provider physiologique AIMI

`BaseHealthKitManager` de Trio 0.8.4 gère l'écriture de glycémie, glucides, lipides, protéines et
insuline. Sa demande d'autorisation ne contient pas les types de lecture nécessaires à AIMI. Le
portage exige donc un nouveau `AimiPhysioHealthKitService` ou une extension clairement isolée ; la
phrase « Trio lit déjà toute la physiologie HealthKit » serait incorrecte.

### 2.5 Matrice consolidée des capacités iOS

La colonne « résultat cible » distingue une capacité portable d'une propriété que la plateforme ne
garantit pas. « Conditionnel » signifie qu'un gate matériel, un entitlement ou une politique de
dégradation doit être satisfait avant activation ; ce n'est pas un synonyme de « probablement
équivalent ».

| Capacité AIMI / hôte | Résultat cible iOS | Adaptation requise | Limite non supprimable par KMP |
|---|---|---|---|
| Framework Kotlin/Native consommé par Swift | **Oui** | Umbrella XCFramework statique, façade réduite, CI device/simulateur | Interop Objective-C par défaut ; toute évolution publique affecte l'ABI |
| Même `modelUAM.tflite` et même schéma 18 features | **Oui, sous gate de parité** | Runtime LiteRT/TFLite C iOS, tenseurs et pré/post-traitement identiques | Délégué Metal/CPU et versions de runtime peuvent produire de faibles écarts numériques |
| Arbre physiologique, Harmonia et protections | **Oui** | Port `commonMain`, horloge et persistence abstraites | La parité dépend de la qualité et de la sémantique des entrées iOS |
| Learners SMB, basale, T3C et Autodrive | **Oui, en cohérence éventuelle** | Jobs durables, checkpoints, reprise, exécution foreground/BG opportuniste | Aucun réveil périodique exact n'est garanti ; un learner ne doit jamais bloquer la dose |
| Décision et enact dans Trio | **Oui, avec modification Trio** | Protocole `DosingEngine`, snapshot enrichi, transaction PREPARED/ACK | Le middleware JavaScript actuel n'est pas un point d'extension natif suffisant |
| Historique pompe/CGM/glucides | **Oui** | Adaptateur Swift depuis Core Data vers le snapshot canonique | Core Data Trio reste la source de vérité ; pas de copie Room concurrente |
| Tick de boucle après événement BLE | **Conditionnel par driver/device** | Restauration CoreBluetooth, heartbeat, acteur sérialisé, test de chaque combinaison | iOS ne promet pas une exécution toutes les cinq minutes ni après force-quit |
| Maintenance et entraînement en arrière-plan | **Conditionnel et retardable** | `BGProcessingTask`, queue durable, expiration handler | Le système choisit l'heure d'exécution et peut interrompre la tâche |
| Sommeil, fréquence cardiaque, repos, pas | **Oui si disponible et autorisé** | Queries/anchors HealthKit, cache horodaté, provenance | Refus de lecture volontairement opaque ; certains devices ne produisent pas la donnée |
| HRV RMSSD Health Connect | **Pas identique nativement** | Dériver RMSSD d'une série de battements si disponible, sinon employer SDNN taggé séparément | SDNN HealthKit et RMSSD ne sont pas interchangeables ; baselines distinctes |
| Température cutanée/poignet | **Partielle** | Mapper le type réellement disponible avec provenance | Disponibilité dépend du matériel, du port de la montre et des autorisations |
| Hormonitor | **Oui avec adaptation UX** | Journal borné privé, export explicite via Document Picker, marqueurs de gaps | Pas d'écriture continue dans un répertoire arbitraire partagé |
| Notifications standard / Time Sensitive | **Oui, conditionnel aux réglages** | Demande progressive, watchdog replanifié à chaque tick | L'utilisateur peut refuser ou désactiver les interruptions Time Sensitive |
| Critical Alerts | **Conditionnel à Apple** | Demande d'entitlement et permission utilisateur | L'entitlement spécial n'est pas garanti ; prévoir un produit sûr sans lui |
| Drivers BLE CoreBluetooth/LoopKit | **Conditionnel par combinaison** | Conserver les drivers Swift, tester restore/reboot/lock/Low Power | La présence d'un driver ne prouve pas son comportement background sur chaque version iOS |
| Périphérique Bluetooth Classic générique | **Non dans le périmètre** | ExternalAccessory seulement avec coopération fabricant/MFi | Une app iOS générique n'obtient pas un accès série Classic arbitraire |
| Migration des états AIMI Android → iOS | **Oui pour un bundle AIMI versionné** | Export chiffré, checksum, migration transactionnelle, rollback | Pairing pompe, secrets, permissions OS et tâches planifiées ne sont pas portables |
| Distribution TestFlight | **Oui pour la bêta** | Signature, profils, App Store Connect, dSYM et procédure de renouvellement | Une build expire après 90 jours ; ce n'est pas une distribution autonome durable |

Le verdict de cette matrice est donc : **aucune fonction algorithmique centrale n'est condamnée par
iOS**, y compris le modèle TFLite et les learners. En revanche, leur environnement d'exécution doit
être rendu événementiel, durable et conscient des données absentes. Les garanties Android de
planification, de système de fichiers ou de transport ne doivent pas être copiées dans le contrat
fonctionnel iOS.

## 3. Architecture cible

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Trio / Swift                                                            │
│                                                                         │
│ CoreBluetooth / LoopKit ──► APSManager ──► AimiCoordinator (actor)      │
│          │                         │                 │                    │
│          │                         │                 ├─ snapshot Trio     │
│          │                         │                 ├─ physio HealthKit  │
│          │                         │                 └─ deadline / mode   │
│          │                         │                                      │
│          │                         └──────────────► DosingEngine          │
│          │                                           │                   │
│          │                         ┌─────────────────┴────────────────┐  │
│          │                         │ OrefJavaScript │ AimiKmpEngine   │  │
│          │                         └─────────────────┬────────────────┘  │
│          │                                           │                   │
│          └───────────────────────────────────────────┼─ enact + ACK      │
└──────────────────────────────────────────────────────┼───────────────────┘
                                                       │ NSData envelope v1
┌──────────────────────────────────────────────────────┼───────────────────┐
│ AimiKit.xcframework                                  ▼                   │
│                                                                         │
│ iosMain façade ──► commonMain AimiEngine                                 │
│                       │                                                  │
│                       ├─ arbre physiologique / Harmonia / RBT            │
│                       ├─ safety / décision / learners                    │
│                       ├─ état transactionnel / maintenance              │
│                       ├─ schémas et validation                          │
│                       └─ ports                                           │
│                           ├─ FileStore / Clock / Crypto                  │
│                           └─ UamInference ──► TensorFlowLiteC            │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Principe de propriété

Une seule couche doit être propriétaire de chaque fait clinique ou opérationnel :

| Responsabilité | Propriétaire | Pourquoi |
|---|---|---|
| Connexion CGM et pompe | Swift / LoopKit / Trio | Les drivers et le cycle BLE sont natifs et déjà éprouvés dans cet écosystème |
| Historique clinique canonique | Trio Core Data | Évite une seconde base divergente contenant bolus, basales et glucides |
| Capture cohérente du tick | `AimiContextAssembler` Swift | Il connaît les modèles Trio et fixe un `asOf` unique |
| Algorithme et états AIMI | `commonMain` | Identique sur Android et iOS |
| Inférence `modelUAM.tflite` | port AIMI, `actual` par plateforme | Même contrat de features et même modèle, runtime natif |
| État privé des learners | store AIMI | Versionné séparément de Core Data ; transactionnel |
| Décision de proposer une commande | AIMI | Arbre → Harmonia → croyances → safety |
| Validation des limites matérielles | Swift + LoopKit | La pompe reste l'autorité sur granularité, capacité et état |
| Envoi et confirmation pompe | Swift / LoopKit | Kotlin ne doit jamais parler directement au driver |
| Notification utilisateur et UI | Swift | API Apple, Focus, permissions, localisation |
| Physiologie HealthKit brute | Swift | Autorisations et queries Apple ; AIMI reçoit un modèle canonique |
| Hormonitor scientifique | AIMI pour le schéma, iOS pour l'export | Même sens des champs, stockage conforme au sandbox |

### 3.2 Le protocole de moteur à ajouter à Trio

Le point d'extension recommandé est un protocole Swift stable au niveau de l'orchestrateur, et non
le fichier middleware :

```swift
protocol DosingEngine {
    func determine(
        context: DosingContext,
        mode: DosingEngineMode,
        deadline: ContinuousClock.Instant
    ) async -> DosingEngineResult

    func acknowledge(_ outcome: PumpCommandOutcome) async
}
```

Implémentations :

- `OrefJavaScriptEngine` enveloppe le comportement Trio actuel sans le modifier ;
- `AimiKmpEngine` transforme `DosingContext` en enveloppe AIMI et appelle `AimiKit` ;
- `ShadowDosingEngine` exécute les deux, ne laisse agir que le moteur de référence et produit un
  rapport différentiel ;
- un choix versionné détermine l'autorité : `orefOnly`, `aimiShadow`, `aimiOpenLoop`,
  `aimiClosedLoop`.

Cette couture résiste mieux au remplacement futur de JavaScriptCore par une implémentation Swift.
Le projet [`trio-algorithm-validator`](https://github.com/nightscout/trio-algorithm-validator)
montre d'ailleurs qu'une migration de l'algorithme Trio et de vastes corpus de comparaison sont déjà
des sujets actifs. AIMI ne doit pas se coupler à l'implémentation momentanée du moteur OREF.

## 4. Contrat Swift/Kotlin et stabilité ABI

### 4.1 Ne pas exporter le graphe AAPS entier

Le framework public ne doit pas exporter `plugins/aps`, `PersistenceLayer`, les préférences AAPS,
Dagger, Compose ou des centaines de modèles internes. JetBrains recommande un umbrella module pour
assembler plusieurs modules dans un XCFramework, mais l'export transitif désactive une partie de
l'élimination de code mort et augmente l'API et le binaire. L'umbrella `:shared:aimi-apple-sdk` doit
donc dépendre de l'engine par `implementation` et exposer uniquement une façade iOS.

Artefact proposé :

```text
:shared:aimi-domain       commonMain, aucun API Apple
:shared:aimi-runtime      commonMain + ports + persistence AIMI
:shared:aimi-ios-runtime  iosMain actuals, TensorFlowLiteC
:shared:aimi-apple-sdk    umbrella, façade publique, XCFramework
```

Cibles minimales :

- `iosArm64` pour les appareils ;
- `iosSimulatorArm64` pour les Mac Apple Silicon et la CI ;
- `iosX64` seulement si les Mac Intel restent une cible explicitement supportée.

### 4.2 Façade publique minimale

L'API publique recommandée comporte moins de dix opérations :

```text
AimiIosRuntime.bootstrap(configurationData) -> BootstrapResult
AimiIosRuntime.evaluate(snapshotData) -> EvaluationData
AimiIosRuntime.acknowledge(outcomeData) -> AckResult
AimiIosRuntime.runMaintenance(requestData) -> MaintenanceResult
AimiIosRuntime.flush(deadlineEpochMs) -> FlushResult
AimiIosRuntime.health() -> HealthData
AimiIosRuntime.close()
AimiIosRuntime.buildInfo() -> BuildInfoData
```

La façade iOS accepte et retourne `NSData` plutôt que d'exposer tous les DTO Kotlin. C'est un choix
de stabilité, pas un abandon du typage :

- les deux côtés décodent vers des DTO stricts et validés ;
- chaque enveloppe porte `apiMajor`, `apiMinor`, `schemaVersion` et `stateSchemaVersion` ;
- Kotlin/Objective-C transforme et copie les grandes collections ;
- les génériques et enums Kotlin ne se projettent pas toujours idiomatiquement en Swift ;
- Swift Export est encore Alpha et l'export Objective-C/C est documenté Beta ;
- une façade binaire étroite évite que chaque refactoring interne devienne une rupture ABI.

Le format initial peut être un JSON UTF-8 produit par `kotlinx.serialization`. À cinq minutes de
cadence, son coût est négligeable face à l'accès à l'historique. Si les snapshots deviennent très
grands, un protobuf déterministe peut être ajouté dans une version de protocole ultérieure sans
changer la façade `NSData`.

### 4.3 JSON externe, types internes

Règle : **JSON au passage de langage, jamais comme modèle du domaine**.

Le contrat ne doit pas transmettre des `JSONObject`, `Any`, chaînes d'unités ambiguës ou dates
locales. Les champs suivants sont obligatoires :

- temps en epoch millisecondes UTC ;
- `timeZoneId` IANA séparé pour les règles circadiennes ;
- unités explicites (`mg/dL`, `mmol/L`, `U`, `U/h`, `g`, `bpm`, `ms`) ;
- source, timestamp, fraîcheur et qualité de chaque mesure ;
- valeur absente représentée par `null` et une disponibilité, jamais par zéro ;
- identifiant de schéma des features ML et hash du modèle ;
- identifiant unique de tick et révision de l'état hôte.

Types externes essentiels :

```text
AimiLoopSnapshot
  identity: loopId, capturedAt, monotonicAt, timeZoneId, hostVersion
  glucose: samples, raw/smoothed flag, source, trend, quality
  pump: status, currentTemp, reservoir, lastCommunication, capabilities
  therapy: profile schedules, insulin model, safety limits, targets
  history: insulin, carbs, temp basals, glucose, TDD and relevant events
  physiology: typed metrics with metricSemantic/source/age/availability
  preferences: revision + mapped AIMI settings
  engine: expectedStateRevision, mode, deadline

AimiEvaluation
  commandId, inputHash, previousStateRevision, proposedStateRevision
  action, predictions, authorityTrace, terminalGuards
  modelManifest, warnings, fallbackDisposition, telemetryBatch

PumpCommandOutcome
  commandId, requestedAction, enactedAction, pumpTimestamp
  success/failure/unknown, driverError, pumpHistoryRevision
```

### 4.4 Gestion des erreurs et concurrence

- aucune exception Kotlin ne traverse la frontière : le résultat contient un code d'erreur fermé,
  sa gravité, sa possibilité de retry et une disposition sûre ;
- les appels Swift entrent par un `actor AimiCoordinator`, garantissant un seul tick à la fois ;
- le moteur protège également ses états par `Mutex`/atomiques multiplateformes ;
- l'appel d'évaluation est synchrone au niveau de la façade Native, mais toujours exécuté hors
  `MainActor` par le wrapper Swift. Cela évite de dépendre de la projection expérimentale de
  `suspend` en `async` ou d'une bibliothèque d'ABI additionnelle ;
- le deadline est un champ explicite. Une annulation Swift ne doit jamais laisser Kotlin valider un
  état partiel ;
- les callbacks Swift conservés par Kotlin sont évités sur le chemin critique, limitant les cycles
  ARC/GC et les ambiguïtés de thread.

### 4.5 Packaging et versionnage

Développement local : intégration directe avec la tâche Gradle
`embedAndSignAppleFrameworkForXcode`.

Distribution reproductible dans le fork Trio :

1. assembler `AimiKitRelease.xcframework` pour appareil et simulateur ;
2. produire le zip, son checksum SwiftPM et un Software Bill of Materials ;
3. inclure licences, symboles/dSYM, UUID de binaire et manifeste de build ;
4. publier une release immuable ;
5. référencer une version et un checksum exacts dans `Package.swift` ;
6. ne jamais suivre `main`, une URL mutable ou une version flottante dans une build de dosage.

Le manifeste embarqué contient au minimum :

- commit AIMI et statut dirty ;
- versions Kotlin, coroutines, sérialisation et runtime LiteRT/TFLite ;
- versions de schéma input/output/état/features ;
- SHA-256 des modèles factory ;
- mode de build, cible, date reproductible et licences.

Swift Export ne doit pas être une dépendance de production tant qu'il reste Alpha. SKIE peut être
évalué pour le confort des développeurs, mais aucun protocole de sécurité ou de persistence ne doit
dépendre de sa projection des coroutines.

## 5. Conservation de TFLite sur iOS

Google prend officiellement en charge le runtime LiteRT/TFLite sur iOS, CPU et Metal. Le modèle
initial n'a donc aucune raison d'être perdu.

Architecture :

```text
commonMain UamInferencePort
  ├─ androidMain: org.tensorflow.lite.Interpreter
  └─ iosMain: TensorFlowLiteC via cinterop / petit shim Objective-C
```

Règles de qualification :

- conserver exactement les octets de `modelUAM.tflite` ;
- figer les 18 features, leur ordre, unité, normalisation et traitement des valeurs non finies ;
- commencer par CPU sur les deux plateformes pour réduire les variations de delegate ;
- pinner la version du runtime dans les deux builds ;
- comparer les sorties sur un corpus de vecteurs normaux, limites, manquants et non finis ;
- définir une tolérance interne, puis exiger une égalité de la décision après arrondi pompe et guards ;
- mesurer warm-up, mémoire, temps froid et temps chaud sur appareil ;
- stocker le modèle actif hors de `Documents` et vérifier son hash avant chargement.

Organisation iOS du modèle :

- le modèle factory est en lecture seule dans le bundle ;
- au premier démarrage, il est copié transactionnellement vers `Application Support/AIMI/models` ;
- un modèle importé ou entraîné est écrit sous un nouveau nom, vérifié, puis activé par changement
  atomique de manifeste ;
- le modèle précédent reste disponible pour rollback ;
- un fichier placé ou modifié manuellement dans Files ne devient jamais automatiquement le modèle
  de dosage actif.

Si l'inférence échoue, AIMI retourne une indisponibilité explicite et sa stratégie de fallback
validée. Il ne doit pas transformer silencieusement l'échec en prédiction `0` sans que Harmonia et la
télémétrie connaissent l'absence du signal.

## 6. Sémantique transactionnelle de la boucle

### 6.1 Séquence d'un tick

```text
1. Événement CGM/pompe/manuel
2. Déduplication et sérialisation par AimiCoordinator
3. Mise à jour pompe via LoopKit
4. Capture d'un snapshot Trio cohérent à t = asOf
5. Ajout du cache physiologique HealthKit disponible à asOf
6. AimiKit.evaluate(snapshot, deadline)
7. Validation hôte : fraîcheur, état pompe, limites, granularité
8. Écriture PREPARED(commandId, hash, stateRevision)
9. Commande LoopKit
10. Confirmation, échec ou résultat inconnu
11. AimiKit.acknowledge(outcome)
12. Commit du state delta et des événements d'apprentissage
13. Flush borné, métriques, armement du watchdog local
```

Les learners ne doivent pas apprendre qu'une dose a été administrée avant l'accusé de réception de
la pompe. AIMI contient aujourd'hui des effets de bord au milieu de `DetermineBasalAIMI2`; la
migration doit les convertir en `StateDelta` préparé puis committé après l'outcome.

### 6.2 Crash entre commande et ACK

C'est le cas de reprise le plus important : la pompe peut avoir reçu la commande alors que
l'application est tuée avant le commit local.

Au lancement suivant :

1. lire le journal `PREPARED` non terminé ;
2. interroger l'historique pompe via LoopKit ;
3. rapprocher identifiant, type, montant et fenêtre temporelle ;
4. committer comme `CONFIRMED`, `FAILED` ou `UNKNOWN` ;
5. en cas `UNKNOWN`, interdire une répétition automatique et faire passer le risque dans les guards ;
6. seulement ensuite exécuter le tick suivant.

La clé d'idempotence proposée est
`loopId + glucoseTimestamp + pumpHistoryRevision + commandKind`. Une même clé ne peut produire
qu'une commande matérielle.

### 6.3 Politique de fallback à décider

Trois politiques sont techniquement possibles :

| Politique | Avantage | Risque |
|---|---|---|
| Pas de nouvelle commande | Évite un basculement algorithmique non anticipé | La basale temporaire courante peut expirer ; disponibilité réduite |
| OREF Trio validé comme fallback | Maintient une recommandation | Transition de philosophie et d'état ; doit être validée comme telle |
| Dernière décision AIMI | Simple | **Interdit** si données ou état ont changé ; une dose périmée ne doit pas être rejouée |

La recommandation d'architecture est « pas de nouvelle commande », ou OREF seulement après une
campagne explicite validant le basculement. Le choix est un arbitrage de sécurité produit, pas une
décision technique implicite.

## 7. Cycle de vie iOS

### 7.1 Matrice des scénarios

| Scénario | Comportement attendu | Condition/test obligatoire |
|---|---|---|
| Premier lancement, téléphone déverrouillé | Permissions, bootstrap du store, validation des modèles ; aucune boucle fermée avant readiness | Test onboarding propre |
| Retour premier plan | Réconciliation pompe, jobs dus, HealthKit, diagnostics, puis boucle éventuelle | Pas de double tick |
| Écran verrouillé, app suspendue | Un événement BLE autorisé réveille Trio ; la boucle doit finir rapidement | Appareil réel, plusieurs nuits |
| App retirée de mémoire par iOS | CoreBluetooth peut relancer si la restauration est correctement configurée et qu'un événement attendu survient | Restaurer managers et delegates |
| App crashée | Même attente que la restauration système, puis journal de reprise | Crash injecté avant/après commande |
| Force quit par l'utilisateur | Pas de promesse de relance BLE générale ; iOS 26 ajoute des règles liées à AccessorySetupKit | Message utilisateur + ouverture manuelle ; qualification par driver |
| Redémarrage iPhone | Pas de relance avant le premier déverrouillage si un code est configuré ; ensuite restauration conditionnelle | Test reboot + premier unlock |
| Bluetooth coupé dans Réglages | La restauration n'est pas garantie | Notification déjà planifiée et diagnostic au prochain lancement |
| Mode avion | Dépend du maintien de Bluetooth et de l'opération en attente | Matrice de tests |
| Mode économie d'énergie | BLE peut fonctionner ; BGTasks et maintenance peuvent être retardés | Le dosage ne dépend pas de BGProcessing |
| Aucune liaison BLE directe | Les données cloud/App Group seules ne constituent pas automatiquement un heartbeat | Boucle fermée non supportée sans heartbeat qualifié |
| CGM BLE présent, pompe absente | Décision possible en open loop ; aucune exécution matérielle | État explicite |
| Pompe BLE présente, CGM via cloud | Le heartbeat pompe peut être exploitable selon le driver ; ne pas généraliser | Qualification driver par driver |
| Téléphone verrouillé avant premier unlock après reboot | Store et secrets restent inaccessibles | Fail closed, alerte locale déjà armée si possible |

Apple documente précisément les règles de relance dans
[`TN3115`](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules).
Pour iOS 26, AccessorySetupKit modifie certaines conditions de relance. La matrice de support doit
donc mentionner la version iOS et la manière dont chaque accessoire a été configuré.

### 7.2 Budget du réveil BLE

Un wake BLE sert d'abord la raison du wake. Il ne doit pas devenir une fenêtre pour lancer un
backfill ou un entraînement lourd.

Budgets cibles à mesurer, non garanties Apple :

| Étape | P95 cible initiale |
|---|---:|
| Mise à jour et snapshot pompe/CGM déjà connecté | ≤ 1 500 ms |
| Assemblage de l'historique | ≤ 1 000 ms |
| Lecture du cache physiologique | ≤ 100 ms |
| Inférence TFLite + moteur AIMI | ≤ 1 000 ms |
| Validation et préparation | ≤ 250 ms |
| Envoi de commande hors latence radio | ≤ 500 ms |
| Flush du journal minimal | ≤ 250 ms |

La latence radio est mesurée séparément. Un deadline monotone est passé au moteur. Si le temps
restant devient insuffisant, l'évaluation s'arrête sans commit et applique la politique de fallback.
Les appels HealthKit complets et les scans de fichiers ne sont pas admis sur ce chemin.

## 8. WorkManager vers iOS : modèle exact

`BGTaskScheduler` n'est pas un WorkManager périodique. Le système choisit le moment d'exécution et
peut expirer la tâche. La bonne abstraction n'est donc pas `expect fun scheduleEvery(hours)` mais une
**file durable de travaux dus**.

### 8.1 File durable

Chaque job persiste :

```text
jobId, type, dueAt, notBefore, priority, attempts,
checkpoint, inputRevision, leaseUntil, lastError, createdAt
```

Points d'exécution possibles :

- retour au premier plan ;
- après un tick, uniquement si le dosage est terminé et le budget le permet ;
- événement HealthKit, pour un petit rafraîchissement associé ;
- `BGProcessingTask` accordée par iOS ;
- action manuelle de l'utilisateur ;
- `BGContinuedProcessingTask` sur iOS 26+ pour un entraînement explicitement lancé par l'utilisateur,
  sans en faire une dépendance du fonctionnement autonome.

Toute tâche est idempotente, checkpointable et interrompt proprement son travail dans
`expirationHandler`. La planification de la prochaine requête se fait avant de terminer la tâche
courante. Une tâche expirée reste due.

### 8.2 Mapping des travaux AIMI

| Travail Android actuel | Stratégie iOS | Garantie fonctionnelle |
|---|---|---|
| Physio realtime, 15 min | Lire le cache à chaque tick + observer HealthKit | Pas de cadence 15 min garantie |
| Physio métabolique, 30 min | Recalcul lazy si stale, foreground et petite tâche opportuniste | Éventuel, pas périodique exact |
| Basal/T3C trainer, 1 h | `dueAt`, tentative foreground/après boucle/BGProcessing | Entraînement eventual ; ancienne version reste active |
| Autodrive backfill, 6 h | Curseur incrémental, petits lots reprenables | Aucun scan complet sur wake BLE |
| Autodrive trainer, 24 h | BGProcessing ou lancement utilisateur ; checkpoint | Peut être retardé plusieurs jours |
| Physio daily, 24 h | Détection de changement de jour + BGProcessing | Idempotent par date locale |
| Watchdog, 6 h | Health check à chaque tick/foreground + notification stale déjà planifiée | Ne dépend pas d'un wake watchdog |
| Hormonitor flush | Append borné après tick, flush sur transition background | Ne bloque jamais la décision |

Le commentaire Android de `AimiMlTrainingScheduler` explique que les contraintes charge + idle
empêchaient déjà certains entraînements de démarrer. Sur iOS, exiger systématiquement
`requiresExternalPower` reproduirait ce défaut. Ce paramètre doit être réservé aux travaux réellement
lourds, avec une voie foreground et une reprise incrémentale.

## 9. Stockage et persistence

### 9.1 Répartition dans le sandbox

| Donnée | Emplacement iOS | Backup | Protection |
|---|---|---|---|
| Modèle factory | Bundle de l'app | Inclus dans l'app | Lecture seule |
| Modèle actif et poids learners | `Library/Application Support/AIMI/models` | Oui | After First Unlock |
| État de l'engine et journal transactionnel | `Library/Application Support/AIMI/runtime` | Oui | After First Unlock |
| File de maintenance | `Library/Application Support/AIMI/jobs` ou SQLite | Oui | After First Unlock |
| Dataset d'apprentissage nécessaire | `Library/Application Support/AIMI/datasets` | Selon politique | After First Unlock |
| Cache reconstructible | `Library/Caches/AIMI` | Non | After First Unlock |
| Staging import/export | `tmp/AIMI` | Non | Protection appropriée, suppression après usage |
| Exports explicitement utilisateur | `Documents/AIMI/Exports` | Oui/visible | Jamais source de vérité active |
| Secrets, tokens, clés | Keychain | Selon attribut | `AfterFirstUnlockThisDeviceOnly` si requis en background |
| Résumé Watch/Live Activity | App Group | Selon container | Lecture seule, données minimales |

Apple recommande `Application Support` pour les fichiers nécessaires au fonctionnement mais non
gérés directement par l'utilisateur. `Documents` est sauvegardé et peut être exposé dans Files. Il
ne faut donc pas reproduire littéralement `Environment.getExternalStorageDirectory()/Documents/AAPS`
comme répertoire de travail actif.

`FileProtectionType.completeUntilFirstUserAuthentication` permet l'accès après le premier unlock,
même lorsque le téléphone est à nouveau verrouillé. Pour les secrets nécessaires à une boucle de
fond, Apple recommande la classe Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Les
secrets ainsi marqués ne migrent pas vers un nouvel appareil : un onboarding est attendu après
restauration.

### 9.2 Fichiers atomiques

Tout modèle ou état suit :

1. écrire dans un fichier temporaire du même volume ;
2. `fsync`/fermeture ;
3. valider schéma, dimensions, valeurs et checksum ;
4. renommer atomiquement ;
5. mettre à jour un petit manifeste actif ;
6. conserver au moins une génération précédente ;
7. ne supprimer les anciennes générations qu'après un démarrage confirmé.

Les CSV/JSONL append-only utilisent un journal avec séquence et CRC ou une base SQLite. Un fichier
tronqué doit être récupérable jusqu'au dernier record valide. Hormonitor écrit un marqueur de gap si
la télémétrie non critique est abandonnée sous pression ; il ne ralentit jamais le dosage.

### 9.3 Room KMP n'est pas obligatoire pour l'intégration Trio

Room KMP prend officiellement en charge iOS avec `BundledSQLiteDriver`, mais migrer les 46 DAO AAPS
dans Trio créerait une seconde base clinique concurrente de Core Data. Pour le scénario recommandé :

- Trio Core Data reste la source clinique ;
- Swift produit le snapshot AIMI ;
- le store KMP ne conserve que les états et datasets privés d'AIMI ;
- Room KMP est optionnel si ce sous-ensemble bénéficie réellement d'un schéma relationnel.

Si Room est retenu, il faut qualifier toutes les migrations, WAL/checkpoints, accès après verrouillage,
corruption et hausse de taille binaire. Le simple fait que `BundledSQLiteDriver` soit déjà utilisé sur
Android ne migre pas le module `database/impl` vers Native.

## 10. Migration Android vers iOS

### 10.1 Ne pas copier le répertoire AAPS brut

L'import doit utiliser un **AIMI Migration Bundle** versionné et chiffré :

```text
aimi-migration-v1.zip.enc
  manifest.json
  factory/modelUAM.tflite                 (si différent du factory iOS)
  models/basal_adaptive_weights.json
  models/t3c_brain_weights.json
  models/smb_refinement_weights.json
  state/physio_baseline.json
  state/learner_state/*
  state/patient_event_memory.json
  datasets/...                            (optionnel, consentement explicite)
  checksums.sha256
```

Le manifeste contient :

- version du bundle et version minimale du moteur ;
- plateforme, version et commit source ;
- schémas de features, modèles, états et datasets ;
- unités, zone horaire d'origine et timestamp ;
- hash de chaque entrée ;
- indicateur RMSSD/SDNN/derived pour toute baseline HRV ;
- identifiant pseudonyme de patient, sans credential de pompe.

### 10.2 Éléments volontairement non migrés

- pairing et état brut de la pompe ;
- identifiants CoreBluetooth et secrets de drivers ;
- permissions HealthKit/notifications/Bluetooth ;
- tâches planifiées ;
- caches expirables ;
- tokens réseau en clair ;
- commandes pompe en attente non réconciliées ;
- paramètres sans mapping Trio/AIMI approuvé.

Les profils et limites de thérapie sont configurés ou importés dans Trio par son propre parcours,
puis comparés au manifeste AIMI. Le bundle ne doit pas écraser silencieusement les réglages de la
pompe ou de Trio.

### 10.3 Import transactionnel

1. sélection par `UIDocumentPickerViewController` ;
2. copie dans un staging du sandbox ;
3. déchiffrement sans journaliser le secret ;
4. validation de tous les checksums et schémas ;
5. migration dans une nouvelle génération de store ;
6. replay à blanc de quelques snapshots ;
7. présentation d'un rapport de compatibilité ;
8. activation explicite ;
9. rollback automatique si le bootstrap suivant échoue.

Le Document Picker fournit des URLs security-scoped pour les ressources externes. L'accès doit être
ouvert, coordonné puis relâché. Une URL externe n'est pas conservée comme chemin de travail de
dosage.

## 11. HealthKit physiologique

### 11.1 Mapping de capacité

| Besoin AIMI / Health Connect | HealthKit | Parité | Traitement requis |
|---|---|---|---|
| Sommeil et phases | `sleepAnalysis` | Bonne avec variations de source | Fusionner les intervalles qui se chevauchent, conserver la provenance |
| Fréquence cardiaque | `heartRate` | Bonne | Unités bpm, qualité et source |
| Fréquence au repos | `restingHeartRate` | Bonne mais disponibilité variable | Ne pas remplacer par un minimum arbitraire sans tag |
| Pas | `stepCount` | Bonne | Agrégation par source et déduplication HealthKit |
| HRV RMSSD | Pas de quantité RMSSD standard | **Non directe** | SDNN séparé, ou RMSSD dérivé de heartbeat series si disponible |
| HRV SDNN | `heartRateVariabilitySDNN` | Native Apple | Baseline et seuils propres à SDNN |
| Intervalles RR | `HKHeartbeatSeriesSample` | Partiel | Peut permettre un RMSSD dérivé ; disponibilité non garantie |
| Température basale | `basalBodyTemperature` | Bonne sémantiquement, souvent manuelle | Garder la source |
| Température de peau | `appleSleepingWristTemperature` | Proche mais nocturne/poignet | Read-only, Apple Watch compatible, ne pas confondre avec BBT |

### 11.2 Règle HRV

RMSSD et SDNN ne sont pas interchangeables. Les identifiants de métrique doivent être distincts :

```text
hrv.rmssd.healthconnect.v1
hrv.rmssd.heartbeat-derived.v1
hrv.sdnn.healthkit.v1
```

Une baseline créée avec RMSSD n'est jamais continuée avec SDNN. À la migration :

- conserver la baseline RMSSD pour audit ;
- la désactiver pour les décisions iOS si aucun RMSSD comparable n'est disponible ;
- créer une nouvelle baseline SDNN, ou une baseline RMSSD dérivée après validation ;
- réauditer tous les seuils qui influencent stress, frein, multiplicateur SMB et autorité Harmonia.

### 11.3 Permissions et absence de données

HealthKit protège la confidentialité de lecture : l'application ne sait pas si l'utilisateur a
refusé un type ou si aucun échantillon n'existe. Une query vide ne peut donc pas devenir « permission
refusée » ni `0.0`.

Modèle canonique :

```text
availability = observed | noVisibleSample | unsupported | stale | queryFailed
authorizationVisibility = opaque
value = nullable
```

Le moteur reçoit également `sourceRevision`, device, timestamp et âge. Les valeurs des différents
producteurs ne sont fusionnées qu'avec une règle explicite de déduplication.

### 11.4 Architecture de lecture

- Swift demande les types de lecture AIMI séparément des types d'écriture Trio ;
- une `HKAnchoredObjectQuery` par type maintient un curseur persistant ;
- `HKObserverQuery` et background delivery rafraîchissent un cache ;
- les observer queries sont installées tôt au lancement ;
- le completion handler HealthKit est toujours appelé après traitement ;
- le tick lit le cache à `asOf` et n'attend pas une requête de sept jours ;
- une query foreground répare le cache et les anchors après migration ou corruption ;
- le simulateur ne valide pas la background delivery : tests sur iPhone obligatoires.

HealthKit est un heartbeat secondaire pour la physiologie, pas l'horloge primaire du closed loop.
Apple limite la fréquence maximale des deliveries et décide de leur horaire effectif.

## 12. Notifications et alarmes

### 12.1 Matrice

| Niveau | Capacité | Limite |
|---|---|---|
| Local standard/active | Écran + son si autorisé | Respecte Focus, mute et réglages utilisateur |
| Time Sensitive | Peut traverser Focus et Scheduled Summary | L'utilisateur peut le désactiver ; ne traverse pas nécessairement le mute |
| Critical Alert | Son même en mute/Do Not Disturb | Entitlement Apple approuvé + autorisation utilisateur |
| Notification déjà planifiée | Le système peut l'afficher app arrêtée | Livraison non absolument garantie |
| Live Activity | État visible | N'est ni un wake général ni une alarme critique |

Correction importante : l'entitlement Critical Alerts n'est pas « techniquement impossible » ou
« certainement non obtenable ». Apple documente un formulaire de demande et une attribution au cas
par cas. Pour un fork DIY, son obtention est **non garantie et constitue un risque élevé de
distribution**. Trio 0.8.4 ne déclare pas cet entitlement.

### 12.2 Watchdog sans timer applicatif

Après chaque loop réussie :

1. annuler la notification stale précédente ;
2. planifier une notification locale pour `lastLoop + seuil` ;
3. l'annuler/remplacer au tick suivant ;
4. utiliser Time Sensitive si configuré et autorisé ;
5. journaliser le niveau effectivement accordé par le système.

Cette alarme signale une boucle absente, elle ne réveille pas AIMI pour doser. Les garde-fous matériels
de la pompe et l'expiration des basales temporaires restent indépendants.

## 13. Drivers et transport

### 13.1 Politique d'intégration

AIMI ne doit pas porter les drivers Android dans KMP. Trio/LoopKit reste la couche device. Le moteur
ne voit qu'un `PumpCapabilitiesSnapshot` : pas de protocole radio, pas d'adresse, pas de callback BLE.

| Classe de device | Faisabilité iOS | Décision |
|---|---|---|
| CGM/pompe BLE avec driver LoopKit | Oui | Adapter et qualifier le driver existant |
| CGM BLE uniquement utilisé comme heartbeat App Group | Oui sous conditions | Trio doit maintenir sa propre souscription BLE ou le provider doit réveiller l'app |
| RileyLink BLE vers radio propriétaire | Oui | RileyLink est le périphérique CoreBluetooth ; qualification LoopKit |
| Bluetooth Classic RFCOMM/SPP | Pas d'accès générique | ExternalAccessory nécessite matériel MFi et autorisation fabricant |
| Donnée cloud / Nightscout seule | Lecture possible | Ne garantit aucun réveil cinq minutes |
| App vendor vers App Group sans BLE direct | Lecture possible | Pas un heartbeat autonome |

Apple précise que les accessoires Bluetooth Low Energy utilisent CoreBluetooth sans obligation MFi,
alors que ExternalAccessory communique avec des accessoires MFi et que Bluetooth Classic relève du
programme MFi. Il faut donc reformuler : DanaR/Rv2, Combo ou Insight ne sont pas « physiquement
impossibles sur iOS », mais leurs drivers AAPS Bluetooth Classic ne sont pas utilisables par une app
générique sans coopération MFi du fabricant. Pour ce projet, ils doivent être marqués **non supportés**
tant que cette coopération n'existe pas.

### 13.2 Matrice de qualification par combinaison

Pour chaque CGM × pompe × version iOS :

- transport et driver exacts, commit du submodule ;
- origine du heartbeat ;
- état restauration et restoration identifier ;
- comportement app suspendue, retirée de mémoire, crashée et après reboot ;
- comportement Bluetooth coupé/rétabli et Low Power Mode ;
- latence P50/P95/P99 de récupération, calcul et commande ;
- granularité bolus/basale et limites driver ;
- reprise d'une commande au résultat inconnu ;
- alarmes disponibles ;
- résultat : `qualified`, `open-loop-only`, `unsupported`.

Une affirmation globale « les pompes BLE fonctionnent » ne remplace pas cette matrice.

## 14. Observabilité et Hormonitor

### 14.1 Événement de loop minimal

Chaque tick produit un événement local contenant :

- `loopId`, cause du wake et timestamps wall/monotone ;
- état de l'application : foreground/background, temps restant si disponible ;
- version Trio, AimiKit, schémas et modèle actif ;
- hash canonique du snapshot, pas nécessairement le snapshot brut ;
- âge et provenance des entrées CGM, pompe et HealthKit ;
- révision AIMI avant/après ;
- trace des autorités arbre → Harmonia → croyances → protections ;
- prédiction ML et health du runtime TFLite ;
- décision proposée, action arrondie, action réellement confirmée ;
- temps de chaque phase, fallback et erreurs structurées ;
- état des jobs d'apprentissage et dernière activation de modèle.

### 14.2 Confidentialité

- aucun snapshot de santé brut dans un service de crash tiers par défaut ;
- logs OSLog privés pour les valeurs cliniques ;
- identifiant pseudonyme local, rotation et export avec consentement ;
- API keys et secrets systématiquement redacted ;
- export Hormonitor explicite, chiffrable et accompagné du manifeste des versions ;
- politique de rétention bornée et visible ;
- App Group limité au résumé nécessaire à la Watch/Live Activity.

### 14.3 Port de Hormonitor

La sémantique JSONL peut être identique, mais les primitives Android sont remplacées :

| Android | iOS/KMP |
|---|---|
| `SystemClock.uptimeMillis()` | horloge monotone injectée (`ContinuousClock`/mach time) |
| `Environment.../Documents/AAPS` | store interne + export explicite |
| `java.io.File`/`RandomAccessFile` | okio/Foundation ou SQLite |
| `Settings.Secure` | identifiant pseudonyme généré et stocké au Keychain |
| écran Compose Android | SwiftUI ou viewer multiplateforme séparé |

Le writer tourne sur une queue bornée. Si la queue de télémétrie est pleine, il ajoute un compteur de
perte et préserve la boucle ; il ne suspend pas le calcul d'insuline.

## 15. Sécurité opérationnelle

### 15.1 Invariants hôte

Même si AIMI possède ses propres protections, Swift/LoopKit vérifie avant enactment :

- résultat lié au `loopId` et au snapshot courant ;
- glucose et communication pompe encore frais ;
- commande non déjà exécutée ;
- profil et limites identiques à ceux du snapshot ;
- pompe disponible, non suspendue et réservoir plausible ;
- montant fini, positif, dans les limites et arrondi par le driver ;
- aucune commande antérieure au résultat inconnu non réconciliée ;
- mode `closedLoop` et autorité AIMI explicitement activés.

Un échec de cette validation ne déclenche jamais une correction automatique différente. Il produit un
outcome refusé que le moteur enregistre.

### 15.2 Défense du modèle et des états

- checksum du modèle et du schéma de features à chaque activation ;
- signature ou MAC des bundles importés si une clé de confiance est disponible ;
- limites de taille avant décompression/import ;
- parsing borné, refus de chemins relatifs et symlinks dans un zip ;
- rejet des NaN/Infinity et poids de dimensions inattendues ;
- circuit breaker d'entraînement ;
- rollback au dernier modèle sain ;
- aucune mise à jour de modèle distante silencieuse ;
- activation uniquement entre deux ticks, jamais au milieu d'une évaluation.

### 15.3 Mise à niveau et rollback applicatif

Chaque release Trio/AimiKit conserve :

- compatibilité de lecture de l'état `N-1` ;
- migration dans une nouvelle génération ;
- possibilité de démarrer en `orefOnly`/open loop si l'état AIMI est incompatible ;
- interdiction de downgrader vers une version incapable de comprendre le journal de commandes sans
  reset explicite ;
- liste noire possible d'une version AimiKit/model précise, séparée de la version Trio.

## 16. Plan de tests iOS

### 16.1 Build et ABI

- compilation `iosArm64` et `iosSimulatorArm64` de tout le graphe AIMI ;
- link Debug et Release de l'umbrella framework ;
- exécution `iosSimulatorArm64Test` de tous les `commonTest` AIMI ;
- compilation du harness Swift avec warnings traités ;
- snapshot du header Objective-C public et diff à chaque PR ;
- vérification SPM du checksum, licences, architectures, dSYM et symboles ;
- test d'une build Xcode propre sans cache Gradle ;
- budget de taille, cold start et mémoire K/N.

### 16.2 Contrat et moteur

- golden tests JSON/protobuf Swift ↔ Kotlin ;
- compatibilité unknown fields, version mineure et rejet d'un major incompatible ;
- unités mg/dL/mmol/L, DST, changement de zone et calendrier local ;
- données absentes, stale, dupliquées, réordonnées et futures ;
- replay multi-ticks avec état et ACK pompe, pas seulement comparaison de sorties isolées ;
- comparaison TFLite Android/iOS sur le schéma 18 features ;
- égalité des commandes finales après granularité pompe ;
- fuzz des enveloppes et bornes de taille.

### 16.3 Persistence et chaos

- kill après chaque étape PREPARED/ENACTED/ACK/COMMITTED ;
- disque plein, fichier tronqué, checksum incorrect, permissions et protection verrouillée ;
- upgrade `N-1 → N`, rollback et réinstallation ;
- import Android valide, partiel, ancien et hostile ;
- deux heartbeats simultanés et deux demandes manuelles ;
- expiration BGTask pendant chaque trainer ;
- interruption de l'entraînement et reprise au checkpoint ;
- rotation du modèle pendant une évaluation interdite.

### 16.4 Matériel et lifecycle

Le simulateur ne suffit pas. Sur au moins deux générations d'iPhone :

- 72 heures screen locked ;
- sept jours avec usage réel, réseau variable et Low Power Mode ;
- éviction mémoire provoquée ;
- crash injecté ;
- reboot puis premier unlock ;
- force quit documenté comme état non supporté jusqu'à réouverture ;
- Bluetooth Settings off/on, Control Center, mode avion ;
- éloignement et retour CGM/pompe ;
- changement d'heure et de fuseau ;
- perte HealthKit et révocation partielle ;
- notification standard, Time Sensitive et, si accordée, Critical.

### 16.5 Déploiement progressif

| Stade | Autorité | Durée minimale suggérée | Sortie attendue |
|---|---|---:|---|
| Harness offline | Aucune | Corpus complet | Contrat et replay stables |
| Appareil shadow | OREF/Android référence | 2–4 semaines | Aucun crash, divergences expliquées |
| Open loop | Utilisateur | 2–4 semaines | Recommandations et lifecycle fiables |
| Closed loop limité | AIMI, cohorte interne | 4+ semaines | Invariants et reprise validés |
| Élargissement | AIMI | Décision de release | Matrice device/version qualifiée |

Ces durées sont des minima d'ingénierie et ne constituent ni un protocole clinique ni une
certification.

## 17. Gates de livraison

### Gate I0 — contrat et artefact

- `AimiKit.xcframework` linke en Debug/Release ;
- API publique ≤ surface validée ;
- manifeste, checksum, licences et dSYM présents ;
- harness Swift exécute un snapshot sans dépendance Android.

### Gate I1 — exactitude du runtime ML

- même `modelUAM.tflite` et même schéma 18 features ;
- corpus Android/iOS dans les tolérances ;
- hash et rollback fonctionnent ;
- erreur ML visible par Harmonia, jamais transformée silencieusement en signal normal.

### Gate I2 — transaction et persistence

- state delta non committé avant ACK ;
- récupération des quatre crash points ;
- idempotence démontrée ;
- store accessible après premier unlock lorsque l'iPhone est verrouillé ;
- aucune commande répétée après outcome inconnu.

### Gate I3 — intégration Trio shadow

- protocole `DosingEngine` et feature flags ;
- snapshot cohérent et versionné ;
- moteur OREF inchangé en autorité ;
- rapport différentiel complet et budget du wake respecté.

### Gate I4 — lifecycle matériel

- combinaison CGM/pompe explicitement qualifiée ;
- restauration BLE réellement exercée ;
- reboot/lock/Low Power documentés ;
- watchdog local et diagnostics utilisateur ;
- aucune dépendance du dosage à BGProcessing ou HealthKit background delivery.

### Gate I5 — physiologie

- permissions et cache HealthKit ;
- métriques absentes non encodées à zéro ;
- HRV taggée et baseline séparée ;
- provenance et fraîcheur dans chaque snapshot ;
- guards réaudités pour la métrique iOS.

### Gate I6 — close loop

- période shadow et open loop terminée ;
- stratégie de fallback approuvée ;
- zéro divergence inexpliquée de commande finale ;
- observabilité et export de diagnostic opérationnels ;
- décision explicite sur les limites de notification et de distribution.

### 17.1 Registre des risques résiduels

| Risque | Probabilité avant mitigation | Impact | Détection/gate | Réponse obligatoire |
|---|---|---|---|---|
| Absence de heartbeat ou restauration BLE incomplète | Haute | Critique | I4, matrice device/iOS, compteur de gaps | Ne pas activer le closed loop pour la combinaison ; watchdog utilisateur ; corriger le chemin de restauration |
| Budget de réveil dépassé par snapshot ou AIMI | Moyenne | Critique | I3/I4, métriques monotoniques P95/P99 | Deadline hôte, cache pré-calculé, annulation ; aucune commande tardive |
| Commande envoyée mais résultat inconnu après crash | Moyenne | Critique | I2, chaos aux quatre points transactionnels | Réconcilier l'historique pompe avant toute nouvelle commande ; ne jamais rejouer aveuglément |
| Entraînement/backfill longtemps différé par iOS | Haute | Élevé | Âge de job, checkpoint et alerte de starvation | Continuer avec dernier état validé, exécuter au foreground, rendre l'âge visible ; jamais bloquer la boucle |
| Divergence de schéma snapshot/feature entre Android et iOS | Moyenne | Critique | I0/I1, version et hash de schéma | Rejeter l'envelope incompatible, tests golden cross-platform, pas de valeur par défaut silencieuse |
| Écart numérique LiteRT CPU/Metal | Faible à moyenne | Élevé | I1, corpus et commandes finales | Tolérances par tenseur, qualification CPU d'abord, désactiver un délégué non conforme |
| SDNN employé comme RMSSD ou baseline HealthKit contaminée | Moyenne | Élevé | I5, provenance/identifiant de métrique | Espaces de features et baselines distincts ; branche dégradée si métrique non comparable |
| Permission HealthKit refusée interprétée comme zéro physiologique | Moyenne | Élevé | I5, états `noVisibleSample`/`unsupported` | Modèle d'absence explicite, UI de diagnostic, aucun zéro synthétique |
| Corruption du store ou kill pendant activation d'un modèle | Moyenne | Élevé | I2, tests kill/fault et checksum | Écriture atomique, journal/manifest, dernier état validé et rollback |
| Entitlement Critical Alerts non accordé | Haute tant qu'Apple n'a pas répondu | Élevé | I6, configuration signée inspectée | Produit sûr avec notifications ordinaires/Time Sensitive et diagnostic in-app ; ne pas en dépendre |
| Dérive d'API interne Trio après mise à jour upstream | Moyenne | Élevé | CI sur commit Trio épinglé et test d'intégration | Adapter mince, PR upstream si possible, versionner le contrat, mettre à jour après requalification |
| Expiration TestFlight laissant un utilisateur sans build active | Moyenne | Élevé | Tableau des expirations et alerte équipe | Release cadence, procédure de renouvellement/rollback ; TestFlight n'est pas le plan de distribution final |
| Croissance du framework ou rupture ABI Kotlin/Native | Moyenne | Moyenne | I0, budget de taille et harness Swift | Une façade/umbrella unique, éviter les exports transitifs, semver du protocole, rebuild Swift et Kotlin ensemble |

Le risque iOS dominant n'est pas l'impossibilité de calculer AIMI ; c'est l'écart entre un calcul
correct en laboratoire et une boucle qui reste observable, idempotente et récupérable pendant les
transitions réelles de l'application et du matériel.

## 18. Charges

Charges en semaines-personnes, pour le lot iOS de cette annexe :

| Lot | P50 | Plage prudente |
|---|---:|---:|
| Umbrella XCFramework, façade, SPM, CI, symboles | 5 | 4–7 |
| Protocole `DosingEngine`, assembler Trio, feature flags | 9 | 7–12 |
| Transaction PREPARED/ACK/recovery et actor | 7 | 5–10 |
| TFLite C iOS, modèle, parity et benchmarks | 4 | 3–6 |
| Store AIMI, protection, queue de jobs, rollback | 8 | 6–12 |
| Migration Android → iOS et Document Picker | 5 | 4–8 |
| HealthKit physiologique, anchors, HRV, cache | 10 | 8–14 |
| BGTasks, expiration, maintenance et entraînement | 7 | 5–10 |
| Notifications, watchdog et diagnostics utilisateur | 4 | 3–6 |
| Hormonitor/observabilité/privacy | 5 | 4–8 |
| Qualification lifecycle d'un couple CGM/pompe | 12 | 8–18 |
| Stabilisation shadow/open loop et release | 12 | 10–20 |
| **Somme brute** | **88** | **67–131** |
| **Après recouvrement réaliste entre lots** | **67** | **51–83** |

Budget P80 recommandé : **70–105 semaines-personnes**, car les aléas drivers, Kotlin/Native,
HealthKit et lifecycle sont corrélés et ne se réduisent pas tous par parallélisation.

Cette charge exclut :

- le portage algorithmique de 102 KLOC AIMI vers `commonMain` ;
- la refonte complète des écrans AIMI ;
- plus d'une combinaison CGM/pompe qualifiée ;
- un nouveau driver de device ;
- watchOS, widgets, CarPlay et commandes distantes ;
- certification réglementaire, validation clinique et responsabilité médicale ;
- acceptation App Store ou entitlement Critical Alerts ;
- support utilisateur permanent et astreinte de release.

## 19. Distribution

Les affirmations à retenir sont limitées à ce qu'Apple garantit :

- l'Apple Developer Program coûte actuellement 99 USD/an, avec variations régionales possibles ;
- TestFlight rend une build testable pendant 90 jours ;
- une équipe peut désigner jusqu'à 100 testeurs internes qui sont des utilisateurs App Store Connect ;
- TestFlight externe accepte jusqu'à 10 000 personnes et le premier build d'une version ajouté à un
  groupe externe est soumis à Beta App Review ;
- un refus d'App Review n'est ni certain ni exclu. Il ne doit pas être présenté comme un fait avant
  soumission ;
- TestFlight n'est pas une distribution pérenne : le renouvellement des builds et certificats est
  une exigence opérationnelle ;
- le browser build de Trio est un précédent utile, mais chaque utilisateur/équipe reste responsable
  de ses identifiants, certificats, secrets et échéances.

Le pipeline AIMI doit ajouter :

- pin du commit Trio et de la version AimiKit ;
- vérification de checksum du XCFramework ;
- build automatique avant expiration ;
- test de démarrage et health du modèle avant upload ;
- canal de rollback et blacklist d'une release ;
- alerte d'expiration plusieurs jours avant les 90 jours.

## 20. Corrections à appliquer aux documents précédents

| Formulation actuelle | Formulation précise |
|---|---|
| « Supprimer TFLite » | Conserver `modelUAM.tflite`; utiliser le runtime iOS et prouver la parité |
| « Trio est un simple JSON-in/JSON-out » | Le calcul reçoit du JSON, mais l'intégration AIMI exige état, transaction, ACK et historique supplémentaire |
| « Le middleware évite un fork » | Le middleware est JavaScript ; ajouter une couture native `DosingEngine` nécessite une modification Trio |
| « CoreBluetooth garantit le tick 5 min » | Le loop est événementiel et conditionné par un heartbeat BLE et le lifecycle iOS |
| « La restauration survit à la terminaison » | Oui sous conditions ; les managers/delegates doivent être restaurés et le force quit reste un cas particulier |
| « BGProcessing remplace WorkManager » | BGProcessing est opportuniste ; une file durable et plusieurs points d'exécution sont requis |
| « Trio a déjà HealthKit » | Trio écrit actuellement certains types ; le provider physiologique AIMI reste à construire |
| « HealthKit donne la même HRV » | HealthKit donne SDNN ; RMSSD exige des heartbeat series disponibles et un calcul validé |
| « Critical Alerts est impossible » | Entitlement spécial, demandé au cas par cas, non garanti pour un fork DIY |
| « Bluetooth Classic est impossible » | Non accessible à une app générique sans MFi/autorisation fabricant ; donc non supporté dans le projet actuel |
| « Documents iOS remplace le dossier Android » | `Application Support` est le store actif ; `Documents` sert uniquement aux exports/imports explicites |
| « 8–9 semaines pour Trio » | Plausible pour un spike réduit, insuffisant pour lifecycle, persistence, HealthKit et qualification matérielle |

## 21. Arbitrages nécessaires

Les décisions suivantes doivent être prises avant Gate I0 :

1. **Autorité en cas d'échec AIMI** : aucune nouvelle commande ou fallback OREF explicitement validé.
2. **Couture Trio** : maintenir un petit fork ou proposer le protocole `DosingEngine` upstream.
3. **API binaire** : façade `NSData` versionnée recommandée, plutôt qu'export massif de DTO Kotlin.
4. **Runtime ML** : TensorFlowLiteC CPU piné en première version ; delegates seulement après parity.
5. **Store AIMI** : fichiers transactionnels ou petite base Room/SQLite, sans dupliquer Core Data.
6. **HRV iOS** : SDNN avec nouveaux seuils, RMSSD dérivé si disponible, ou signal HRV désactivé au
   début. Aucune conversion implicite.
7. **Combinaison device initiale** : sélectionner précisément un CGM heartbeat et une pompe LoopKit.
8. **Notifications** : accepter Time Sensitive sans garantie ou constituer un dossier Critical Alerts.
9. **Migration utilisateur** : importer les poids/états ou repartir avec des baselines iOS neuves.
10. **Distribution** : fork individuel/TestFlight interne, cohorte privée ou objectif App Store.

Les décisions 1, 6, 7 et 8 sont des décisions de sécurité produit. Elles ne doivent pas être prises
implicitement par une implémentation technique.

## 22. Sources primaires

### Apple

- [Core Bluetooth background processing et state restoration](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [TN3115 — règles de relance Bluetooth](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)
- [`CBCentralManagerOptionRestoreIdentifierKey`](https://developer.apple.com/documentation/corebluetooth/cbcentralmanageroptionrestoreidentifierkey)
- [Choisir une stratégie d'arrière-plan](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app)
- [Utiliser Background Tasks](https://developer.apple.com/documentation/UIKit/using-background-tasks-to-update-your-app)
- [Expiration d'une BGTask](https://developer.apple.com/documentation/backgroundtasks/bgtask/expirationhandler)
- [HealthKit observer queries et background delivery](https://developer.apple.com/documentation/healthkit/executing-observer-queries)
- [Autorisation HealthKit et opacité des droits de lecture](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data)
- [Types HealthKit](https://developer.apple.com/documentation/healthkit/data-types)
- [`HKHeartbeatSeriesSample`](https://developer.apple.com/documentation/healthkit/hkheartbeatseriessample)
- [Température de poignet nocturne](https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/applesleepingwristtemperature)
- [Critical Alerts entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.usernotifications.critical-alerts)
- [Notifications Time Sensitive](https://developer.apple.com/documentation/usernotifications/unnotificationinterruptionlevel/timesensitive)
- [ExternalAccessory et MFi](https://developer.apple.com/documentation/externalaccessory/)
- [Bluetooth et MFi](https://developer.apple.com/bluetooth/)
- [Répertoires du sandbox](https://developer.apple.com/documentation/foundation/using-the-file-system-effectively)
- [`completeUntilFirstUserAuthentication`](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication)
- [Keychain After First Unlock](https://developer.apple.com/documentation/security/ksecattraccessibleafterfirstunlockthisdeviceonly)
- [`UIDocumentPickerViewController`](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller)
- [TestFlight](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview)
- [Apple Developer Program — contenu et tarif](https://developer.apple.com/programs/whats-included/)

### Kotlin / Android / ML

- [Framework Kotlin/Native pour Apple](https://kotlinlang.org/docs/apple-framework.html)
- [Interopérabilité Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)
- [Intégration iOS KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)
- [Export XCFramework via SwiftPM](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html)
- [Production de binaires Native et export de dépendances](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Intégration ARC/GC Kotlin/Native](https://kotlinlang.org/docs/native-arc-integration.html)
- [Room KMP sur iOS](https://developer.android.com/kotlin/multiplatform/room)
- [LiteRT — plateformes prises en charge](https://github.com/google-ai-edge/LiteRT)
- [Exemples officiels LiteRT iOS](https://github.com/google-ai-edge/litert-samples)

### Trio

- [Trio, commit audité](https://github.com/nightscout/Trio/tree/29350e31a9f8b25dcea719f52fa1abb676e34af8)
- [`OpenAPS.swift`](https://github.com/nightscout/Trio/blob/29350e31a9f8b25dcea719f52fa1abb676e34af8/Trio/Sources/APS/OpenAPS/OpenAPS.swift)
- [`APSManager.swift`](https://github.com/nightscout/Trio/blob/29350e31a9f8b25dcea719f52fa1abb676e34af8/Trio/Sources/APS/APSManager.swift)
- [`BluetoothTransmitter.swift`](https://github.com/nightscout/Trio/blob/29350e31a9f8b25dcea719f52fa1abb676e34af8/Trio/Sources/APS/CGM/BluetoothTransmitter.swift)
- [`HealthKitManager.swift`](https://github.com/nightscout/Trio/blob/29350e31a9f8b25dcea719f52fa1abb676e34af8/Trio/Sources/Services/HealthKit/HealthKitManager.swift)
- [Browser build/TestFlight Trio](https://github.com/nightscout/Trio/blob/29350e31a9f8b25dcea719f52fa1abb676e34af8/fastlane/testflight.md)
