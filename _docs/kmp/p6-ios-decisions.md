# Décisions iOS

Objectif final : parité stricte Android/iOS. Les valeurs neutres sont un échafaudage temporaire, derrière l'interrupteur éteint, et doivent toutes disparaître avant activation.

Approuvées par l’utilisateur le **2026-10-05** comme étape temporaire, pas comme état final. `IosClientConfig.APS` reste `false`. La boucle iOS n’est pas activée. Aucune écriture pompe.

`AimiCommonEngineSwitch` est **éteint par défaut**, et ce n’est pas `IosClientConfig.APS`. Éteint, `HoldAimiEngine` rend `Hold("ENGINE_NOT_EXTRACTED")`, même si un moteur commun lui a été passé. Allumé en test seulement, il délègue à ce moteur et les scènes ci-dessous donnent les nombres temporaires.

Règle d’activation : l’interrupteur iOS ne peut être allumé qu’une fois **toutes** les traces de parité vertes, octet pour octet, entre Android et iOS. Tant qu’une ligne diffère, il reste éteint.

## Échafaudage temporaire

Ces valeurs ne sont pas la parité. Elles disparaissent avant activation.

- COB virtuel : **0 g**.
- Effort : facteur **1,0**, pas d’assessment.
- Runtime patient : **non appelé**.
- Session TPO : **non appelée**.
- Snapshot wearable : **vide**.
- `resetEarlyScratch` : les mêmes 27 affectations que l’adaptateur Android. Le texte plus bas disait 29. Le test verrouille 27.
- Veto d’effort : **faux** sans assessment.
- Plancher PKPD : stocké et journalisé, **pas relu** dans le débit. Android non plus ne relit pas ce JSON pour doser. La parité est la même ligne de journal, au même endroit du tick.
- Hystérésis : par défaut, le même cycle de vie qu’Android. Les singletons de processus ne sont pas remis à zéro au début du tick. `iosNeutralResetHysteresisForTest` est une option de test. `evaluate` ne l’appelle pas.

## Écarts temporaires

- Prédiction basse : le TBR temporaire est **0,25 U/h** pendant 30 min, sans `TREE_DEPLOYED` ni `MEAL_CERTAINTY`.
- Nuit : le TBR temporaire est **1,00 U/h** pendant 30 min, sans les lignes d’apprenants, d’export, `UAM=0.00`, ni le SMB `final=0.35` non délivré.
- ISF **45** (FC 110 sur 10 min, moyenne 60 min 88, ISF 50 × 0,90) n’est pas produit tant que le snapshot est vide.
- Un maintien d’hystérésis laissé par le tick précédent reste en place, comme sur Android.

## Chemin vers la parité

Ordre : d’abord ce qui change un débit ou le tick suivant, ensuite le modèle et l’export. La taille est le sous-système à écrire, pas un calendrier. Chaque ligne se prouve par une trace déjà verrouillée sur Android, rejouée octet pour octet sur iOS.

1. **Hystérésis.** Déjà les mêmes `object` que `dev_OAPSAIMI` (`MealAbsorptionPhaseHysteresis`, `MealAbsorptionMemory`, `EndogenousPhaseHysteresis`, `PhysiologicalPatternHysteresis`, `InsulinSlopePreserveHysteresis`). Il ne reste pas de second cycle de vie. Le défaut iOS n’appelle pas `reset()`. Taille : un test de deux ticks, rien d’autre. Trace : `lowPredictionRequestsAQuarterBasal` sur instance propre, TBR **0,25 U/h** sans la ligne `meal absorption hysteresis hold` ; puis un tick `FIRST_WAVE` suivi d’un tick `NONE` qui garde `meal absorption hysteresis hold`, comme le singleton Android.

2. **Wearable, FC, et persistance des pas, de la FC et des bolus.** HealthKit remplit le même `HealthContextSnapshot` (pas 5/15/60 min, FC, FC de repos, fenêtres 10 et 60 min). Le contrat de lecture est commun. `MemoryAimiTherapyReads` ne survit pas à un redémarrage : la base qui le fera (Room KMP `2.8.4` déjà dans le dépôt, ou SQLDelight) est un choix laissé à l’utilisateur, détaillé dans « Plans de PR ». Les caches `stepsSnapshotRef`, `heartRatesSnapshotRef` et le cache bolus partent des mêmes listes. Trace : `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf`, FC 110 / 10 min, moyenne 60 min 88, ISF 50 × 0,90 = **45**, ligne `HR_TREND_ISF x0.90`.

3. **Effort.** `decideRefreshEffortActivityBelief` est le corps Android, appelé avec le snapshot déjà lu. La protection coupée et T3C coupé laissent l’assessment null. Un snapshot invalide aussi. L’échec de lecture journalise la ligne wearable et ne réduit pas. Le facteur ne dépasse pas 1. Le tick iOS de repas passe le snapshot vide, protection coupée, et garde SMB **3,30 U** et TBR **2,00 U/h**. Trace : `signalMealReturnsTheAdvisorSmbAndTbr`.

4. **Veto.** La même règle qu’Android : EXERTION, ACTIVE ou RECENT_EFFORT, confiance ≥ 0,30, pas de repas déclaré, COB < 12 g. `decideDetectMealOnset` reçoit ce booléen. Taille : déplacer `effortSuppressesUndeclaredMeal` en commun, une fois l’assessment porté. Trace : `basalDecisionEngineRaisesSportTemp`, BG 180, delta +5, accélération 0, veto faux, onset faux, TBR **1,30 U/h**.

5. **COB virtuel.** La même préférence `OApsAIMIUndeclaredCobEnabled`, le même estimateur de Ra, le poids et le TDD. Glucides déjà déclarés : 0 g virtuel. Taille : les 38 lignes et l’estimateur, après HealthKit. Trace : `advancedPredictionPublishesEventualFromDeclaredCob`, COB déclaré **36 g**, glycémie 180, eventual **322**.

6. **Runtime patient.** Appeler les 221 lignes : état patient, arbre, Harmonia, avec le snapshot du tick. Taille : le plus gros port physio, après le snapshot. Il dépend du capteur et du cycle. Trace : `lowPredictionRequestsAQuarterBasal`, `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN`, `MEAL_CERTAINTY level=NONE`, TBR **0,25 U/h**, octet pour octet.

7. **Plancher PKPD.** Le débit Android vient des courbes, pas d’une relecture du JSON. La parité est la même ligne `PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled` dans le tick, et le même champ d’export. Le tick iOS de prédiction basse appelle `decideRecordPkpdSoftFloor` après le wearable et avant le TBR, puis il retourne. L’onset n’est pas atteint. Le champ écrit est `lastPkpdSoftFloorTelemetry`, celui que le scratch du début de tick avait vidé. Les scènes repas, sport et nuit ne reçoivent pas la ligne raw=39. Pas de seconde formule. Trace : `lowPredictionRequestsAQuarterBasal`.

8. **TPO.** `onTickStart` avec la même horloge, un stockage de session commun, et le même reversement de préférences. Taille : orchestrateur et stockage, après l’horloge déjà portée. Les scènes sans session sont verrouillées. Une scène avec session active n’a pas encore de nombre verrouillé : il faudra la verrouiller sur Android avant de l’exiger sur iOS. Traces sans session : `zzPostHypoAtFiveSkipsTheDriftMicroSmb`, TBR **1,00 U/h** ; `basalDecisionEngineRaisesSportTemp`, TBR **1,30 U/h**.

9. **Learners.** `BasalLearner`, `BasalNeuralLearner` et `UnifiedReactivityLearner` sont déjà en `commonMain`. Il manque l’état persisté identique (`aimi_basal_learner.json`, état du réseau, CSV), pas une autre politique. Départ à froid : multiplicateurs **1,0**, gouvernance `WARMUP`. `KEEP` seulement après les mêmes échantillons. Taille : le stockage de ces fichiers dans la persistance commune, puis les appels `process` au même endroit du tick (616 lignes côté Android, dont l’appel). Trace : `zzPostHypoAtFiveSkipsTheDriftMicroSmb`, TBR **1,00 U/h**, avec les lignes d’apprenants du tick de nuit.

10. **TFLite et UAM.** Le même fichier `modelUAM.tflite` (4 504 octets). L’inférence Kotlin commune n’est pas retenue : le tenseur brut n’est pas le même bit d’un noyau TFLite à l’autre, et `Interpreter` 2.4.0 n’a pas été exécuté ici. Détail et arrêt dans « Plans de PR ». Modèle absent : `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement. Trace : `uamPostHypoReboundBridgesAShortTempBasal`, SMB prédit **0 U**, TBR **1,05 U/h**, 5 min.

11. **`AimiDecisionContext`.** Le type et `toMedicalJson()` sont en `commonMain`. Les champs ne changent pas. La fabrique reste dans la coquille : elle lit la télémétrie d’instance (`IsfSourceTelemetry`, estimateur, ratio) puis remplit le type commun. Trace : `lowPredictionRequestsAQuarterBasal`, TBR **0,25 U/h**, et l’export `pkpd_soft_floor` raw 39, soft 39, hybride 39, `applied` faux, raison `endo_reversion_disabled`.

## Plans de PR, gros chantiers

Les chantiers encore ouverts restent derrière l’interrupteur éteint jusqu’à leur trace verte. Rien n’est activé.

### HealthKit et snapshot wearable

- `iosMain` : `IosHealthKitWearable` interroge HealthKit (pas cumulés 5/15/60 min, échantillons de FC sur 60 min, FC de repos sur 24 h) et remplit `HealthContextSnapshot`. Les fenêtres 10 et 60 min reprennent le chevauchement `timestamp + duration` de `decideHeartRateIsf`. Les quatre échantillons 80, 80, 80, puis 110 bpm donnent hr10 **110** et hr60 **88**. `hrAvg15m` recopie la FC courante, comme le dépôt Android. FC de repos absente : **60**.
- La confiance suit Android : la FC seule vaut 0,3, et `isValid` exige plus que 0,3. Sans HRV ni sommeil le snapshot reste invalide, donc il ne réduit pas une dose. Le tick iOS lit le cache (rafraîchi hors du tick). L’échec journalise `WEARABLE snapshot failed … — snapshot empty` et le cache redevient vide. Un `Error` sort.
- JVM : le même appel reste le snapshot vide et la ligne `IOS_NEUTRAL wearable snapshot empty`. Android `DetermineBasalAIMI2` n’est pas modifié.
- Hors de cette PR : le COB, le runtime patient, les learners. L’ISF **45** est la lecture commune des mêmes échantillons, pas une seconde formule.

### Persistance des pas, de la FC et des bolus

`MemoryAimiTherapyReads` est le contrat des trois lectures de `persistenceLayer` : `getHeartRatesFromTimeToTime` (fenêtre 200 min, `timestamp` inclus), `getStepsCountFromTimeToTime` (fenêtre 210 min), `getBolusesFromTime` (valides, sans `referenceId`, `timestamp >= début`, ordre id descendant). C’est un magasin en mémoire. Il meurt avec le processus. Ce n’est pas la parité : sur iOS, les pas, la FC et les bolus doivent survivre à un redémarrage de l’application, comme le fichier Room sur Android. Cette note n’ajoute aucune base. Le choix est laissé à l’utilisateur.

Android `DetermineBasalAIMI2` continue d’appeler `persistenceLayer`. Le tick iOS lit le contrat. Un `Exception` est journalisé (`HR windows failed … — averages 80, baseline not real` pour la FC) et la liste est vide. Un `Error` sort. Une FC vide ne renforce pas l’ISF.

**Room KMP — version déjà résolue dans le dépôt.** Kotlin `2.4.10`, AGP `9.4.0` (`gradlePlugin`), Room `2.8.4`. `:database:impl` compile déjà cette combinaison : `room-runtime` en `commonMain`, `androidx.sqlite.bundled` en `iosMain` et `jvmMain`, processeur KSP sur `kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64` et `kspJvm`. `AppDatabase` version 35, `@ConstructedBy`, est en `commonMain`, avec `Converters` (150 lignes). `IosAppDatabaseBuilder` ouvre le fichier dans `NSApplicationSupportDirectory` avec `BundledSQLiteDriver`.

Entités du contrat : `HeartRate`, `StepsCount`, `Bolus`, et les DAO `HeartRateDao.getFromTimeToTime`, `StepsCountDao.getFromTimeToTime`, `BolusDao.getBolusesFromTime`. La base en a 21 au total (`APSResult`, bolus, calculateur de bolus, glucides, changements de profil effectif et de profil, bolus étendu, glycémie, basale temporaire, cible temporaire, événement de thérapie, dose journalière, changement de préférence, changement de version, entrée utilisateur, aliment, statut d’appareil, mode, FC, pas, calibration). Ouvrir `AppDatabase` crée les 21 tables. Le tick n’a besoin que des trois lectures.

Migration : 13 objets, `migration22to23` jusqu’à `migration34to35`, uniquement dans `AppDatabaseBuilder` androidMain. Schémas JSON sous `database/impl/src/androidDeviceTest/assets/app.aaps.database.AppDatabase/` : 13 fichiers de `22.json` à `35.json`, 1 515 009 octets ; `35.json` fait 109 013 octets. Le constructeur iOS ne passe pas ces migrations. `fallbackToDestructiveMigration(false)`. Le premier fichier iOS naît à la version 35, parce qu’il n’existe pas encore d’ancienne base iOS. Le commentaire du constructeur fixe la suite : dès qu’un fichier est chez un utilisateur, le prochain changement de schéma doit reprendre la même liste, déplacée en `commonMain`.

Risques. Les DAO sont `internal` et `suspend` ; le tick lit sans coroutine. Un adaptateur ne doit pas bloquer le tick. Le commentaire en tête de `database/impl/build.gradle.kts` dit encore que le processeur ne tourne que pour Android, alors que les lignes KSP iOS et JVM sont déclarées : le `AppDatabase_Impl` iOS doit être vérifié avant de s’en servir, pas activé ici. Aucun import d’un fichier Android. Un iPhone commence vide. `fallbackToDestructiveMigration(false)` refuse un vieux fichier au lieu de l’effacer, à condition que les migrations soient en commun avant le premier schéma livré après une base iOS réelle. Room `2.8.4` est la version déjà compilée avec Kotlin `2.4.10` et AGP `9.4.0`. En changer serait un autre choix.

Taille : pas une nouvelle dépendance. Reste à faire lire au tick iOS les trois DAO, et à déplacer les 13 migrations en commun avant toute livraison. Hors de cette PR.

**SQLDelight.** Absent de `libs.versions.toml`. L’ajouter est une nouvelle dépendance. Il faudrait un plugin et un runtime dont la version supporte Kotlin `2.4.10`, puis réécrire les tables et les trois requêtes. Les objets `Migration` de Room ne se transportent pas : le schéma SQL doit rester aligné sur la version 35 à la main. Risque : Room sur Android et SQLDelight sur iOS divergent au prochain changement de colonne. Taille : le plugin, le driver, les fichiers `.sq`, un second chemin de migration. Rien de cela n’est ajouté.

Trace inchangée : quatre échantillons 80, 80, 80, puis 110 bpm, pas vides, ISF **45**, `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`. Cache bolus vide : SMB repas **3,30 U**, TBR **2,00 U/h**.

### Runtime patient

- Les 221 lignes sont `decideRefreshPatientStateRuntime`, derrière le snapshot déjà lu. L’échec de lecture reste dans la coquille Android : snapshot vide, pas un `catch` nouveau. Le tick iOS de prédiction basse appelle la même fonction après le plancher PKPD.
- Entrées de cette scène : champ `bg` encore à 0 (la glycémie 100 n’est pas encore copiée), cible 100, delta 0, IOB 0, `maxIob` 0, chemin scénario 39 mg/dL. L’état latent part du snapshot vide, confiance capteur 0,105, tronc 0,90.
- Trace : `lowPredictionRequestsAQuarterBasal`, `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE`, `MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false`, TBR **0,25 U/h**.

### TPO

- `onTickStart` avec l’horloge déjà portée, stockage de session commun, même reversement de préférences.
- D’abord les scènes sans session : nuit TBR **1,00 U/h** (`zzPostHypoAtFiveSkipsTheDriftMicroSmb`) et sport TBR **1,30 U/h**. Une scène avec session active doit être verrouillée sur Android avant d’être exigée sur iOS.

### Learners

- Les classes sont déjà en `commonMain`. Cette PR ajoute le stockage identique (`aimi_basal_learner.json`, état du réseau, CSV) dans la persistance commune, puis les appels `process` au même endroit du tick.
- Départ à froid : multiplicateurs **1,0**, gouvernance `WARMUP`.
- Trace : tick de nuit, TBR **1,00 U/h**, avec les lignes d’apprenants.

### TFLite et UAM

Fichier inspecté : `app/src/main/assets/modelUAM.tflite` au commit `64e630c7fc` (absent de l’arbre de travail). 4 504 octets. SHA-256 `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092`. Identifiant `TFL3`, description `MLIR Converted.`, un sous-graphe. Entrée `[1, 18]` float32, sortie `[1, 1]` float32.

Sept opérations : `SUB` (entrée − moyenne `[1, 18]`), `MUL` (échelle `[1, 18]`), puis `FULLY_CONNECTED` 18→9, 9→5 et 5→2 avec ReLU fusionné, `FULLY_CONNECTED` 2→1 sans activation, `PRELU` à alpha partagé (un scalaire).

Le graphe est petit. Une boucle float32 en Kotlin commun peut l’exécuter. Elle ne prouve pas le SMB Android. `AimiUamHandler` appelle `org.tensorflow.lite.Interpreter` 2.4.0, tronque à 4 décimales (`(v * 10000f).toInt() / 10000f`) et plancher à 0. La bibliothèque JNI de cet artefact est un binaire Android : elle ne se charge pas sur cette machine. Un interpréteur LiteRT récent, en trois modes (référence, builtin sans délégué, XNNPACK), comparé à la boucle scalaire sur 67 vecteurs : le tenseur brut diffère sur 31 à 35 vecteurs selon le mode ; le SMB après la troncature Android coïncide sur les 67. Cette coïncidence n’est pas le bit d’`Interpreter` 2.4.0 sur téléphone. L’ADR D4 le dit : le réseau Kotlin n’est pas le graphe TFLite.

Décision : s’arrêter. Pas d’inférence Kotlin commune, pas de cinterop, pas de CocoaPods. Le chemin modèle absent reste celui d’Android : `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement. Trace : `uamPostHypoReboundBridgesAShortTempBasal`, SMB **0 U**, TBR **1,05 U/h**, 5 min.

Voie à reprendre si elle est choisie : le même fichier, interpréteur TFLite/LiteRT C sur iOS, CPU d’abord, puis un corpus dont le SMB est celui d’`Interpreter` 2.4.0. Pas une réécriture du graphe.

Le détail des options temporaires reste ci-dessous.

## `estimateUndeclaredVirtualCob`

Android : la préférence `OApsAIMIUndeclaredCobEnabled` coupée rend **0 g**. Des glucides déjà déclarés rendent **0 g**. Sinon l’estimateur lit le snapshot wearable, la Ra, le poids, le TDD, et journalise `VIRTUAL_COB`. Ces grammes nourrissent la prédiction et le TBR. Ils ne deviennent pas un SMB.

Scène verrouillée : COB déclaré **36 g**, glycémie 180, eventual publié **322**. Le COB déclaré force le retour à 0 g virtuel.

| Option | Effet sur la scène |
|---|---|
| Toujours 0 g | L’eventual **322** ne reçoit pas de grammes virtuels en plus |
| Même préférence qu’Android, coupée | Même eventual **322** |
| HealthKit branché et préférence allumée | Peut ajouter des grammes. Aucune trace ne verrouille le TBR qui en sortirait |

Recommandation : **0 g**.

Côté iOS : HealthKit (pas, FC), préférence, estimateur de Ra. Sans eux, 0 g.

## `refreshEffortActivityBelief`

Android : la fonction remet l’assessment à null, puis sort si la protection d’effort est coupée et que T3C est coupé. Snapshot invalide : pas de réduction. `smbFactor` ne dépasse pas 1. Il ne fait que baisser le SMB à la finale.

Scène verrouillée : repas du conseiller, SMB **3,30 U** et TBR **2,00 U/h** pendant 30 min. Cette scène n’a pas de facteur d’effort sous 1.

| Option | Effet sur la scène |
|---|---|
| Pas d’assessment, facteur 1,0 | SMB **3,30 U** et TBR **2,00 U/h** inchangés |
| Facteur sous 1 sans wearable | Couperait le SMB **3,30 U**. Aucune trace ne verrouille le produit |
| HealthKit, même fonction qu’Android | Le repas sans effort reste **3,30 U**. Une exertion réelle baisserait le SMB, sans nombre verrouillé |

Recommandation : **facteur 1,0** tant qu’il n’y a pas de snapshot valide.

Côté iOS : HealthKit (pas 5/15/60 min, FC, FC de repos). Sans snapshot valide, pas de réduction.

## `refreshPatientStateRuntime`

Android : 221 lignes. Snapshot wearable ou snapshot vide si la lecture échoue. Construit l’état patient, l’arbre, Harmonia, et journalise `TREE_DEPLOYED` et `MEAL_CERTAINTY`.

Scène verrouillée : prédiction basse, `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN`, `MEAL_CERTAINTY level=NONE`, TBR **0,25 U/h** pendant 30 min.

| Option | Effet sur la scène |
|---|---|
| Ne pas appeler | La scène ne produit plus ces deux lignes. Le TBR **0,25 U/h** n’est pas rejoué |
| Appeler avec un snapshot vide | Même famille que l’échec wearable : arbre sans vitaux. Le TBR verrouillé de cette scène est **0,25 U/h** quand l’arbre part incertain |
| HealthKit complet | Peut changer le tronc. Aucune trace ne verrouille un autre débit que **0,25 U/h** pour cet appel |

Recommandation retenue : **appeler la fonction commune** avec le snapshot déjà lu. Le TBR **0,25 U/h** reste celui du tick Android. Les deux lignes sont les siennes.

Côté iOS : HealthKit, cycle, capteur, état patient. Gros port.

## Session TPO

Android : si `tpoOrchestrator` est initialisé, `onTickStart(dateUtil.now())` expire une session active et peut reverser des préférences. `onPatientStateReady` peut ouvrir une session. Aucune trace de dose ne montre ce reversement.

Scènes verrouillées sans session : nuit, TBR **1,00 U/h** pendant 30 min ; sport, TBR **1,30 U/h** pendant 30 min.

| Option | Effet sur la scène |
|---|---|
| Ne pas appeler | Nuit **1,00 U/h**, sport **1,30 U/h** |
| Expirer une session et reverser les préférences | Ces deux débits ne sont plus garantis. Aucune trace ne verrouille le débit d’après reversement |
| HealthKit plus stockage de session, comme Android | Même limite : pas de nombre verrouillé après un pack TPO |

Recommandation : **ne pas appeler**.

Côté iOS : horloge, stockage de session, notifications. Rien de tout cela n’est branché.

## Contenu wearable de `getLatestSnapshot`

Android : l’échec est déjà porté. `OptionalSignal.Failed`, ligne `WEARABLE snapshot failed (<type>): <message> — snapshot empty`, snapshot vide. Le contenu réussi (pas, FC) reste Android.

Scènes verrouillées : repas, SMB **3,30 U** et TBR **2,00 U/h**. ISF après FC : 110 bpm sur 10 min, moyenne 60 min 88, ISF 50 × 0,90 = **45**.

| Option | Effet sur la scène |
|---|---|
| Snapshot vide | Le repas conseiller reste SMB **3,30 U** et TBR **2,00 U/h**. L’ISF **45** ne se produit pas : cette scène a besoin des fenêtres FC |
| HealthKit pas et FC | Peut atteindre l’ISF **45** si les mêmes fenêtres sont fournies |
| Pas seulement, FC absente | Le repas **3,30 U** tient. L’ISF **45** ne tient pas |

Recommandation : **snapshot vide**. L’ISF **45** attend des fenêtres FC réelles.

Côté iOS : HealthKit (pas, FC, FC de repos). Le port d’échec existe déjà.

## `resetEarlyScratch`

Android : les affectations au début du tick. Le mémo les avait comptées 29. L’adaptateur de cette branche en a 27. Elles remettent à faux ou à null les drapeaux de dose du tick, dont `lastPkpdSoftFloorTelemetry`, `mealAdvisorOneShotThisTick`, `criticalSafetyZeroedThisTick`. Elles ne touchent pas les singletons d’hystérésis.

Scène verrouillée : prédiction basse sur une instance fraîche, plancher `raw=39 soft=39`, TBR **0,25 U/h** pendant 30 min.

| Option | Effet sur la scène |
|---|---|
| Mêmes 29 écritures | Chaque tick repart comme l’instance fraîche : TBR **0,25 U/h**, plancher 39 |
| Ne pas remettre à zéro | Un tick suivant peut garder le one-shot ou le plancher précédent. Aucune trace de second tick ne verrouille un autre débit |
| Remettre seulement les drapeaux qui coupent l’insuline | Le TBR **0,25 U/h** de l’instance fraîche n’est plus le contrat complet |

Recommandation : **les 29 écritures**, au début de chaque tick.

Côté iOS : ce sont des champs du tick. Pas de HealthKit.

## Valeur iOS du veto d’effort

Android : `effortSuppressesUndeclaredMeal()` est faux sans assessment. Il devient vrai seulement en posture EXERTION, état ACTIVE ou RECENT_EFFORT, confiance ≥ 0,30, sans repas déclaré, COB < 12 g. `decideDetectMealOnset` reçoit ce booléen à l’appel. Vrai : la fonction rend faux, le TBR de repas forcé ne part pas.

Scène verrouillée : moteur sport, BG 180, delta +5, accélération 0, TBR **1,30 U/h** pendant 30 min. Le veto est faux. L’onset est faux aussi (accélération 0). Le débit n’est pas le `forcedBasal` du bundle.

| Option | Effet sur la scène |
|---|---|
| Faux sans assessment | TBR sport **1,30 U/h** |
| Vrai en permanence | TBR sport **1,30 U/h** aussi. L’escalade de repas non déclaré ne part jamais. Aucune trace ne verrouille ce débit forcé |
| Même règle qu’Android, avec HealthKit | TBR sport **1,30 U/h** sans exertion. Une exertion réelle couperait l’onset, sans nombre verrouillé |

Recommandation : **faux** sans assessment. Le TBR **1,30 U/h** reste celui de la trace. Vrai en permanence baisserait un chemin qui n’a pas de nombre verrouillé.

Côté iOS : le booléen seul. HealthKit seulement si la règle Android est reprise.

## Relecture du plancher PKPD

Android : `decideRecordPkpdSoftFloor` calcule la télémétrie. Le port Android écrit `lastPkpdSoftFloorTelemetry` et la ligne de journal. Plus tard, l’export copie ce champ dans `decisionCtx.adjustments.pkpd_soft_floor`. Le débit du tick vient des courbes, pas d’une relecture de ce JSON.

Scène verrouillée : `PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false`, TBR **0,25 U/h** pendant 30 min, meilleur terminal environ 43,78, seuil 70.

| Option | Effet sur la scène |
|---|---|
| Ne pas relire le JSON dans le débit | TBR **0,25 U/h**, plancher 39 |
| Relire le JSON au tick suivant pour relever les courbes | Aucune trace de tick suivant ne verrouille un autre débit que **0,25 U/h** |
| Garder le champ pour l’export seulement, comme Android | TBR **0,25 U/h** |

Recommandation : **ne pas relire le JSON dans le débit**. L’export peut garder le champ.

Côté iOS : rien d’autre que la mémoire du tick. Pas de HealthKit.

## Cycle de vie des singletons d’hystérésis

Android et `dev_OAPSAIMI` : `MealAbsorptionPhaseHysteresis` est un `object`. `holdTicksRemaining` et `heldPhase` survivent au tick et à l’instance dans le même processus. `reset()` n’est appelé que dans les tests et quand `stabilize` lâche le maintien. Même modèle pour `MealAbsorptionMemory`, `EndogenousPhaseHysteresis`, `PhysiologicalPatternHysteresis`, `InsulinSlopePreserveHysteresis`.

Scène verrouillée : prédiction basse, instance propre, TBR **0,25 U/h** pendant 30 min. Un maintien laissé par un test précédent insère `meal absorption hysteresis hold` dans la trace. Le nombre verrouillé de la scène propre est **0,25 U/h**.

| Option | Effet sur la scène |
|---|---|
| Garder le singleton de processus, comme la ref | La scène propre reste TBR **0,25 U/h**. Le tick suivant peut hériter du maintien |
| `reset()` au début de chaque tick | Chaque tick a la scène propre : TBR **0,25 U/h**. Le maintien de repas ne traverse plus le tick. Ce n’est plus la ref |
| Un état par instance, pas par processus | Deux moteurs ne partagent plus le maintien. Un second tick de la même instance peut encore hériter. La scène propre reste **0,25 U/h** |

Recommandation d’alors : `reset()` au début de chaque tick, pour garder le TBR **0,25 U/h** même après un repas. La parité l’emporte : le défaut est le singleton Android, et `reset()` ne reste qu’en test (`iosNeutralResetHysteresisForTest`). Le tick de production iOS n’appelle pas `reset()`. Le code Android reste le singleton de la ref.

Côté iOS : aucun HealthKit. C’est le cycle de vie du processus.
