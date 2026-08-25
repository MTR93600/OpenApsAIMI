# Annexe 6 — Migration KMP du cœur physiologique, Harmonia, RBT, PKPD, sécurité et Hormonitor

**Statut :** audit d'architecture détaillé, prêt pour décision de migration  
**Référence inspectée :** `dev_OAPSAIMI` @ `06e7bc5021ca8fdd976505d1fefb03cc88681c19` (24 août 2026)  
**Périmètre :** `patient/`, `physio/`, `pkpd/`, `recursive/`, `safety/`, export et visionneuse Hormonitor, ainsi que leurs points d'intégration dans `DetermineBasalAIMI2.kt`  
**Hors périmètre de charge :** application Trio complète, drivers pompe/CGM, interface AIMI générale, modèle TFLite et trainers SMB/basal traités dans les autres annexes  

---

## 1. Décision d'architecture

Le cœur conceptuel d'AIMI est portable sur Kotlin Multiplatform et peut conserver sa fonction sur iOS :

- la construction de l'état patient et de l'arbre physiologique ;
- la décision Harmonia et son second passage `HarmoniaHarmonizer` ;
- le Recursive Belief Tree et ses autorités ;
- les protections hypo, empilement, post-hypo et plafonds SMB/TBR ;
- le runtime et l'apprentissage en ligne PKPD ;
- les classifiers physiologiques, mémoires de repas, phases, patterns, thermique et thyroïde ;
- le schéma scientifique, les exports JSONL et les agrégations Hormonitor.

Ce verdict vaut pour la **parité algorithmique**. Il ne signifie pas que les entrées Android et iOS sont identiques, ni que l'UX de fichiers Android peut être recopiée telle quelle.

| Niveau de parité | Verdict | Définition opérationnelle |
|---|---|---|
| Algorithmique | **Atteignable** | Même graphe causal, mêmes seuils, même ordre, mêmes autorités, mêmes commandes finales après quantification pompe |
| Données physiologiques | **Partielle par nature** | FC, pas, sommeil et RHR se correspondent ; HRV RMSSD Android et SDNN HealthKit ne sont pas interchangeables ; disponibilité et cadence varient |
| Persistance/état | **Atteignable avec refonte** | Il faut versionner et sérialiser les mémoires inter-ticks aujourd'hui dispersées dans des singletons et caches JVM |
| UX Hormonitor | **Fonction équivalente, UX différente** | Même dataset et analyses ; stockage sandboxé et export via Files/Document Picker sur iOS |
| Exécution de fond | **Best effort** | Les événements BLE et HealthKit peuvent réveiller l'app, mais aucun ordonnanceur iOS ne garantit les cadences WorkManager 15/30 min |

Conclusion : **GO conditionnel**. La migration doit porter le moteur comme une machine d'état déterministe et non copier `DetermineBasalAIMI2` dans `commonMain` avec ses singletons actuels.

---

## 2. Méthode et inventaire quantifié

L'audit repose sur le code de la branche, lu via `git show`, `git grep` et l'arbre Git. Les nombres ci-dessous couvrent les fichiers de production du périmètre, pas seulement les quatre classes les plus visibles.

| Domaine | Fichiers | Lignes | P0 : Kotlin quasi pur | P1 : logique portable après ports | P2 : implémentation plateforme requise |
|---|---:|---:|---:|---:|---:|
| `patient` | 18 | 4 358 | 2 | 16 | 0 |
| `physio` et sous-dossiers | 61 | 11 485 | 24 | 27 | 10 |
| `pkpd` | 27 | 3 492 | 12 | 14 | 1 |
| `recursive` | 22 | 3 967 | 13 | 9 | 0 |
| `safety` | 24 | 1 969 | 13 | 11 | 0 |
| `hormonitor/viewer` | 4 | 883 | 1 | 2 | 1 |
| **Total** | **156** | **26 154** | **65** | **79** | **12** |

Classification :

- **P0** : calculs, DTO et machines d'état sans dépendance Android/JVM structurante ; déplacement vers `commonMain` possible avec peu de changements.
- **P1** : calcul portable, mais couplé à `app.aaps.*`, `Preferences`, `org.json`, `java.time`, `java.io`, `Locale`, atomiques ou verrous JVM.
- **P2** : activité Android, Health Connect, WorkManager, Compose Android, `Environment`, ou accès réseau/fichier directement lié à la plateforme.

Les tests existants sont nombreux : **83 fichiers et 11 773 lignes** sur ce périmètre. Ils sont toutefois des tests JVM. Il n'existe pas encore de preuve que les 26 kLOC compilent et se comportent de façon identique sur Kotlin/Native.

---

## 3. Chaîne décisionnelle réelle

### 3.1 Ordre observé dans le tick

Le chemin standard de `runDetermineBasalTickInner` suit cet ordre. Cet ordre est une partie du comportement clinique et doit être figé dans le contrat de replay.

1. `runEarlyDetermineBasalStages` hydrate le contexte, les préférences et les horloges.
2. `bootstrapPhysiologyAfterEarlyTick` applique l'autopilote gestationnel, les multiplicateurs basaux appris et le module thyroïde.
3. `buildDecisionContextInitRtSosAndFlatShadow` remet à zéro les artefacts **du tick**, sans effacer les hystérésis inter-ticks.
4. Le profiler IOB et `RealTimeInsulinObserver` établissent l'état d'action de l'insuline.
5. `runT9PhysioEarlyPkpdAndTubeBootstrap` lit le snapshot physiologique, calcule les multiplicateurs, puis lance un premier runtime PKPD et les premières prédictions.
6. Le pipeline de prédiction construit `clinicalFloor` et `scenarioBest`.
7. `refreshPhysiologicalPhase` classifie puis fusionne les multiplicateurs.
8. `refreshMealAbsorptionPhase` établit la phase d'absorption et met à jour les mémoires repas.
9. `updatePhysioLatentState` construit les hypothèses UAM, le masque de stress et l'état latent.
10. `refreshPatientStateRuntime` construit le posterior causal, le mode patient, la mémoire d'événements, puis le **Physiological Tree**.
11. `MealCertaintyBuilder` évalue la certitude repas. Une certitude forte peut reconstruire l'intention de l'arbre afin que le repas confirmé surclasse un veto activité sans toucher à la protection hypo.
12. `HarmoniaDecisionEngine.evaluate` produit la simulation : action, TBR, SMB, blockers et base explicative.
13. `publishDoseTerminalAuthorityAndSnapshot` publie l'autorité de prédiction et les terminaux utilisés par les étages suivants.
14. `RecursiveBeliefEngine.build` collecte les feuilles aux échelles 15/60/180/480 min ; `RecursiveBeliefResolver.resolve` produit tensions, paradoxes et canaux de dose.
15. `RecursiveBeliefAuthorityGate` limite l'autorité à `NONE`, `SOFT` ou `HARD` selon prédictions, capteurs, chaos, post-hypo, patterns et intention de l'arbre.
16. Les branches Autodrive, SMB et basal appliquent les limites. `HarmoniaSmbArbiter` peut relever une proposition repas **uniquement dans l'enveloppe dure**.
17. Le choix de propriétaire basal est mutuellement exclusif : T3C native, Harmonia production, puis moteur basal normal.
18. `HarmoniaHarmonizer` effectue un second passage sur la proposition finale : `CONFIRM`, `SOFTEN` ou `BLOCK`.
19. Les protections terminales, les limites pompe et la quantification finale s'appliquent avant émission.
20. Le snapshot médical, le replay qualité et Hormonitor sont exportés. Une enveloppe de rattrapage garantit l'export des sorties anticipées.

### 3.2 Chemins spéciaux à préserver

- Les sorties précoces sécurité, repas manuel, T3C, stale data, Meal Advisor et hard brake ne traversent pas toutes les mêmes étages. Le mécanisme d'export final rattrape ces chemins.
- Lorsque Autodrive est engagé, phase, absorption et état latent peuvent être rafraîchis une seconde fois avant la décision Autodrive.
- `HealthContextRepository` et `ContextManager` peuvent republier un `PatientRuntimeSnapshot` hors du tick pour l'interface. Cet état de présentation ne doit jamais modifier silencieusement l'état de dosage en cours.
- Le refresh asynchrone reconstruit actuellement un `HealthContextSnapshot` depuis `PhysioLiveDigest` en perdant notamment `stepsLast5m`, HRV et plusieurs champs sommeil/thermique laissés à leur valeur par défaut. C'est une vue de présentation lossy, pas une entrée de dosage équivalente.
- L'état Tree→Harmonia est toujours construit sur le chemin dose ; `AimiPhysioAssistantEnable` ne désactive que les extras et multiplicateurs de l'assistant.

### 3.3 Point critique pour la migration

Le moteur n'est pas une fonction sans état. La frontière correcte n'est donc pas :

```text
JSON courant -> DetermineBasal -> JSON résultat
```

mais :

```text
(TickInput immuable + EngineState versionné) -> TickOutput + EngineState suivant + événements
```

Sans l'état précédent, un replay peut reproduire les nombres d'un tick isolé tout en perdant les hystérésis, le belief echo, les épisodes post-hypo, le lissage ISF et l'apprentissage DIA/peak.

---

## 4. États persistants et cycle de vie

### 4.1 État limité au tick

Les champs `lastPhysiologicalPhaseOutput`, `lastPhysiologicalPatternSnapshot`, `lastMealAbsorptionOutput`, `lastPhysioLatentState`, `lastUamHypothesisState`, `lastPatientState`, `lastPatientModeDecision`, `lastPhysiologicalTreeSnapshot`, `lastHarmoniaDecision`, `lastRecursiveBeliefSnapshot`, `lastRecursiveAuthorityGateDecision` et les traces d'autorité sont remis à zéro au début du tick. Ils doivent devenir un `TickWorkingSet` local, jamais un ensemble de propriétés globales partagées.

### 4.2 État inter-ticks en mémoire

| Producteur | État | Durée/sémantique | Migration exigée |
|---|---|---|---|
| `MealAbsorptionMemory` | dernière phase, dernier tick actif, vagues, delta, gap et terminal précédents | Mémoire repas entre cycles | Inclure dans `EngineState`; horloge injectée |
| `MealAbsorptionPhaseHysteresis` | phase tenue et nombre de ticks restant | Stabilise les bascules | Sérialiser si reprise exacte après kill requise |
| `EndogenousPhaseHysteresis` | phase tenue et compteur | Stabilise dawn/hormonal | Même traitement |
| `PhysiologicalPatternHysteresis` | pattern dominant, instant et lecture retenue | Anti-flapping | Remplacer singleton par état explicite |
| `PatternCapHold` | cap HARD retenu et ticks restant | Empêche la disparition instantanée d'une protection | État détenu par session moteur |
| `RecursiveBeliefMemory` | anneau de 12 entrées par horizon | Belief echo MR-7 | Persister ou définir explicitement un warm-up fail-closed après redémarrage |
| `RbtEpisodeMemory` | épisode post-hypo/chaotique, timestamps, pic, profondeur | TTL 30/45 min post-hypo, 45 min chaos | Persistance obligatoire pour parité après relance |
| `RealTimeInsulinObserver` | heure onset, quatre pentes, trois corrélations | Détection onset/action | État versionné et testé sur interruption |
| `IsfFusion` | dernier ISF fusionné | Limite la variation par tick | Persistance ou warm-up documenté |
| `AdaptivePkPdEstimator` | DIA, peak, dernier update, compteur, dernier statut | Apprentissage en ligne | DIA/peak déjà persistés ; compléter statut et séquence si replay exact |
| `PkPdIntegration` | config, estimateur, fusion, damping, dernières valeurs persistées, bolus récents | Cache et continuité PKPD | Transformer en composant stateful non singleton |
| `PhysioAggregator` | fenêtres de pas et FC | 15/60 min | Reconstruire depuis historique ou persister ring buffer |
| `AIMIPhysioBaselineModelMTR` | historiques 7 jours et baseline | Apprentissage physiologique non-ML | Store versionné commun |
| `CircadianMealProfileStore` | profil circadien observé | Longue durée | Store commun versionné |
| `ThermalBaselineStore` | médianes nocturnes | Baseline thermique | Store commun, fuseau explicite |
| `ThermalDataCache` | dernière fenêtre thermique | Cache | Atomic/actor KMP |
| `PatientStateRuntimeRepository` | dernier runtime, cache loop, flux UI | Présentation asynchrone | Séparer `DosingSnapshotStore` et `PresentationStore` |

### 4.3 État durable existant

- `AIMIPhysioContextStoreMTR` persiste contexte et baseline dans `physio_context.json`, avec validité de 20 h.
- `AIMIPhysioBaselineModelMTR` restaure ses historiques depuis la baseline sérialisée.
- `PkPdIntegration` persiste DIA et peak dans `Preferences` lorsque les deltas dépassent 0,005 h ou 0,05 min.
- Hormonitor persiste les compteurs quotidiens et QA dans `AIMI_HORMONITOR_daily_state_v1.json` toutes les 30 s au plus.

Le format KMP doit contenir `schemaVersion`, `engineVersion`, unité, fuseau, provenance et stratégie de migration. Une désérialisation invalide doit produire un état neutre/protecteur et une télémétrie, jamais un demi-état.

---

## 5. Inventaire fichier par fichier

Légende : **A** = port direct vers `commonMain`; **B** = logique commune après découplage ; **C** = `expect/actual` ou adaptateur plateforme ; **D** = UX spécifique à réimplémenter. « Données » signale une sémantique à normaliser, même si le calcul est portable.

### 5.1 `patient/` — arbre, état patient et Harmonia

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `AimiCascadeArbitrationArtifacts.kt` | Publication atomique des artefacts patterns/Harmonia pour les consommateurs | B | Remplacer atomiques JVM par état de sortie du tick |
| `BodyKineticsDigest.kt` | Résumé DIA/peak/état d'action utilisé par l'arbre et Harmonia | B | Retirer JSON/AAPS, garder DTO commun |
| `CausalStatePosterior.kt` | Scores causaux, dominant, qualité d'apprentissage et `PatientStateEngine` | B | Extraire DTO AAPS et sérialisation |
| `HarmoniaDecision.kt` | Environnement, simulation, actions, blockers et calcul principal Harmonia | B | Math commune ; remplacer `org.json`/`Locale`; préserver arrondi pompe |
| `HarmoniaHarmonizer.kt` | Second passage `CONFIRM/SOFTEN/BLOCK` sur TBR/SMB proposés | A | Port direct et tests Native |
| `HarmoniaSensorTelemetry.kt` | Normalise âge et bruit CGM pour les blockers | A | Port direct ; contrat d'unité explicite |
| `HarmoniaSmbAuthorityDecision.kt` | Arbitre SMB : accepter, réduire ou relever dans l'enveloppe | B | JSON hors du domaine ; tests de plafond stricts |
| `MealCertainty.kt` | Fusion arbre, géométrie, activité et terminaux repas | B | Extraire dépendances scénario/physio vers DTO |
| `PatientEventMemory.kt` | DTO des charges hyper/hypo, fragilité et épuisement | B | Sérialisation KMP versionnée |
| `PatientEventMemoryCalculator.kt` | Calcule les charges et leur décroissance temporelle | B | Horloge/historique injectés |
| `PatientModeOrchestrator.kt` | Mode patient dominant et stratégie thérapeutique | B | Retirer JSON/Locale et types AAPS |
| `PatientStateLoopCache.kt` | Cache utilisé par les rafraîchissements asynchrones | B | Remplacer par snapshot immuable versionné |
| `PatientStatePresentation.kt` | Jauges et récit humain | B | Garder modèle commun, formater côté UI/localisation |
| `PatientStateRuntimeRefresher.kt` | Recalcule l'état de présentation sur événement physio/contexte | B | Acteur sérialisé ; interdire mutation du tick dose |
| `PatientStateRuntimeRepository.kt` | Dernier état et `SharedFlow` de présentation | B | `StateFlow` commun ou port ; atomique JVM supprimé |
| `PatientStateSnapshot.kt` | Snapshot patient et sérialisation | B | DTO commun ; JSON dans couche telemetry |
| `PhysioLiveDigest.kt` | Vue compacte des capteurs vivants | B | DTO commun, provenance et disponibilité enrichies |
| `PhysiologicalTree.kt` | Racines, tronc, branches, feuilles, fruits, saisons, intention insulinique | B | Algorithme commun ; JSON/Locale séparés ; golden tests |

### 5.2 `physio/` — acquisition, features et politiques

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `AIMIDecisionOrchestratorShadowMTR.kt` | Compare la modulation physiologique en shadow | A | Port direct, événement telemetry typé |
| `AIMIHealthConnectPermissionActivityMTR.kt` | Écran Android de permissions Health Connect | D | Remplacer par flow d'autorisation HealthKit Swift |
| `AIMIHealthConnectPermissions.kt` | Ensemble des permissions et diagnostics HC | C | `HealthPermissionPort`; mapping iOS distinct |
| `AIMIHealthConnectPermissionsHandlerMTR.kt` | Lance l'activité et observe les autorisations | C | Implémentation Android conservée, `actual` iOS HealthKit |
| `AIMIInsulinDecisionAdapterMTR.kt` | Convertit contexte physio en multiplicateurs et trace | B | Retirer Looper, DI AAPS et atomiques ; calcul commun |
| `AIMILLMPhysioAnalyzerMTR.kt` | Analyse narrative HTTP optionnelle | C | Port réseau optionnel ; jamais dans le chemin dose ni requis pour parité clinique |
| `AIMIPhysioBaselineModelMTR.kt` | Historiques 7 jours, percentiles, z-scores | B | Store commun et mutex/actor ; horloge injectée |
| `AIMIPhysioContextEngineMTR.kt` | Classifier déterministe récupération/stress/infection | B | Calcul commun ; logger en port |
| `AIMIPhysioContextStoreMTR.kt` | Cache et fichier JSON du contexte/baseline | C | `PhysioStateStore`; app support/Application Support iOS |
| `AIMIPhysioDataModelsMTR.kt` | DTO bruts, features, baseline, contexte et trace | B | `kotlinx.serialization`; supprimer `java.time` et valeurs sentinelles ambiguës |
| `AIMIPhysioDataRepositoryMTR.kt` | Requêtes Health Connect sommeil/HRV/FC/RHR/pas/température | C | Implémentation Android ; nouvel adaptateur HealthKit Swift |
| `AIMIPhysioFeatureExtractorMTR.kt` | Agrège les données brutes en features normalisées | B | Calcul commun ; intégrer unités et types HRV |
| `AIMIPhysioManagerMTR.kt` | Orchestre collecte, baseline, analyse et WorkManager | C | Scinder `PhysioPipeline` commun et ordonnanceurs plateforme |
| `AIMIPhysioOutcomes.kt` | Résultats fetch/probe/pipeline | A | Étendre aux états permission/absence/stale |
| `AIMIPhysioPipelineWatchdogMTR.kt` | Diagnostique et relance pipeline/synchronisation | B | Politique commune, déclenchement plateforme |
| `AIMIPhysioWorkersMTR.kt` | Workers 15 min, 30 min, 24 h et watchdog 6 h | C | Android conservé ; iOS opportuniste via loop/BLE/HealthKit/BGTask |
| `AIMIVectorModels.kt` | DTO noyaux/trajectoires vectorielles | A | Port direct |
| `AimiHormonitorStudyExporterMTR.kt` | Schémas JSONL, file d'écriture, QA, état journalier et watchdog loop | C | Voir section Hormonitor ; décomposer domaine/writer/identity/clock |
| `BehavioralRiskPolicy.kt` | Politique de caps selon phase et risque | B | Extraire types AAPS, port direct ensuite |
| `CircadianMealProfileStore.kt` | Apprend/stocke un profil repas circadien | B | Store/clock/timezone communs ; supprimer singleton |
| `EndogenousBasalBridgePolicy.kt` | Pont basal pour production endogène | A | Port direct |
| `EndogenousCounterRegulatoryDetector.kt` | Détecte contre-régulation/rebond | B | DTO d'entrée commun |
| `EndogenousPhaseHysteresis.kt` | Maintien inter-ticks des phases endogènes | A | État explicite, non singleton |
| `HealthContextRepository.kt` | Fusion HC + DB Wear/phone en snapshot utilisé par le dosage | C | Source iOS, actor, provenance et nullabilité strictes |
| `HealthContextSnapshot.kt` | Source de vérité physio instantanée et score SNS | B/Données | Remplacer 0 sentinelle par `MeasurementAvailability` |
| `HormonalScenarioTerminalCap.kt` | Plafond des terminaux selon contexte hormonal | A | Port direct |
| `MealAbsorptionMemory.kt` | Mémoire phase/vagues/deltas/gaps entre ticks | A | Déplacer dans `EngineState` |
| `MealAbsorptionPhase.kt` | Enum des phases repas | A | Port direct |
| `MealAbsorptionPhaseEngine.kt` | Classification repas et priorité de délivrance | B | Découpler types scénario ; tests séquentiels Native |
| `MealAbsorptionPhaseHysteresis.kt` | Anti-flapping de phase repas | A | État explicite et sérialisable |
| `PhysioAggregator.kt` | Fenêtres pas/FC 15/60 min | B | `ArrayDeque` commun, mutex/actor et horloge injectée |
| `PhysioLatentState.kt` | Probabilités latentes, confiance capteur, attention mask | B/Données | DTO commun ; auditer la définition de `sensorConfidence` |
| `PhysioPhaseFusion.kt` | Fusion phase et multiplicateurs physio | A | Port direct |
| `PhysiologicalPhase.kt` | Enum phase physiologique | A | Port direct |
| `PhysiologicalPhaseClassifier.kt` | Classifie dawn, activité, hormonal, risque | B | Types d'entrée communs ; tests aux limites |
| `SleepLiveDetector.kt` | Déduit sommeil vivant depuis HC, mouvement, HR et heure | A/Données | Port direct, mais signal source/cadence différent |
| `UamHypothesisState.kt` | Hypothèses meal/endogenous/stress/post-hypo et suppression meal | B | JSON séparé ; DTO commun |
| `gate/CosineTrajectoryGate.kt` | Gate trajectoire paramétré par préférences | B | `PreferenceSnapshot` typé ; retirer DI AAPS |
| `pattern/PatternCapHold.kt` | Retient uniquement les caps HARD | A | État explicite, propriété du moteur |
| `pattern/PhysiologicalPatternCatalog.kt` | Catalogue et caps proposés par pattern | A | Port direct et version de catalogue figée |
| `pattern/PhysiologicalPatternDetector.kt` | Détecte patterns depuis les entrées du tick | B | DTO commun |
| `pattern/PhysiologicalPatternExport.kt` | Construction/export JSON des patterns | B | Séparer input builder et sérialiseur |
| `pattern/PhysiologicalPatternHysteresis.kt` | Stabilise le pattern dominant | A | État explicite |
| `pattern/PhysiologicalPatternId.kt` | Identifiants et catégories | A | Port direct |
| `pattern/PhysiologicalPatternModels.kt` | Lectures, snapshot et propositions de caps | B | Retirer types externes ; port direct ensuite |
| `pattern/PhysiologicalPatternPolicy.kt` | Politique de combinaison des patterns | A | Port direct |
| `thermal/HcRecoveryProxyThermalSource.kt` | Proxy thermique dérivé HRV/RHR/sommeil | B/Données | Ne pas nourrir avec SDNN sous étiquette RMSSD |
| `thermal/OuraApiThermalClient.kt` | Client réseau Oura et cache | C | Port réseau/source ; optionnel si HealthKit fournit température poignet |
| `thermal/ThermalBaselineStore.kt` | Médianes nocturnes de température | B | Store commun ; fuseau explicite ; remplacer CopyOnWriteArrayList |
| `thermal/ThermalBeliefDigest.kt` | Hypothèse thermique structurée et JSON | B | DTO commun, sérialisation séparée |
| `thermal/ThermalBeliefEngine.kt` | Indices inflammation/récupération et hypothèse | B | Retirer Locale/AAPS ; golden tests |
| `thermal/ThermalDataCache.kt` | Dernière fenêtre thermique atomique | B | StateFlow/actor ou état d'entrée |
| `thermal/ThermalDataMTR.kt` | Samples et fenêtres thermiques | A | Port direct avec unité/source explicites |
| `thermal/ThermalDataOrigins.kt` | Identifiants d'origine | A | Port direct |
| `thermal/ThermalSourceTier.kt` | Niveau mesuré/proxy/absent | A | Port direct |
| `thyroid/ThyroidDiagnosticsLogger.kt` | Format de diagnostics thyroïde | B | Formatter commun ou UI ; supprimer Locale |
| `thyroid/ThyroidEffectModel.kt` | Multiplicateurs selon statut/traitement | A | Port direct |
| `thyroid/ThyroidModels.kt` | DTO statut, traitement et effets | A | Port direct |
| `thyroid/ThyroidPreferences.kt` | Lecture des clés AAPS | B | Mapper depuis `PreferenceSnapshot` |
| `thyroid/ThyroidSafetyGates.kt` | Limites thyroïde/hypo/IOB | A | Port direct et tests d'invariants |
| `thyroid/ThyroidStateEstimator.kt` | Estimation d'état thyroïde | A | Port direct |

### 5.3 `pkpd/` — cinétique, apprentissage en ligne et prédictions

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `AdaptivePkPdEstimator.kt` | Apprend DIA/peak en ligne avec bornes et raisons de rejet | B | Remplacer atomiques JVM ; état explicite ; conserver ordre flottant |
| `AdvancedPredictionCurves.kt` | DTO des courbes de prédiction | A | Port direct |
| `AdvancedPredictionEngine.kt` | Produit courbes IOB/COB/UAM/ZT/hybrides | B | Adapter `IobTotal`, profil et interfaces AAPS |
| `CausalKineticsModulator.kt` | Modulation et gate d'apprentissage depuis posterior causal | B | DTO commun |
| `CleanPostBolusWindow.kt` | Définit fenêtre propre après bolus | A | Port direct |
| `DiaGovernor.kt` | Arbitre DIA profil/contextuel/appris | B | Supprimer Locale ; port direct ensuite |
| `InsulinActionProfiler.kt` | Profil d'action depuis IOB et âge du bolus | B | Adapter données AAPS |
| `InsulinActionState.kt` | État PRE_ONSET/RISING/PEAK/TAIL/EXHAUSTED | A | Port direct |
| `InsulinKineticsAuthority.kt` | Autorité DIA/peak selon préférences et données | B | `PreferenceSnapshot`, DTO commun |
| `InsulinWeibullCurve.kt` | Courbe d'action Weibull | A | Port direct |
| `IsfFusion.kt` | Médiane profil/TDD/PKPD et limite de variation | A | État `lastIsf` explicite et sérialisable |
| `PkPdCore.kt` | Paramètres, bornes, kernels et sanitation | A | Port direct ; comparer maths JVM/Native |
| `PkPdCsvLogger.kt` | CSV diagnostic dans Documents/AAPS | C | `TelemetrySink`; jamais requis pour calcul |
| `PkPdIntegration.kt` | Orchestration config, estimation, persistance, fusion, damping | B | `PreferenceStore`, mutex/actor, horloge commune |
| `PkpdAbsorptionGuard.kt` | Réduit/retarde SMB selon phase d'action | A | Port direct |
| `PkpdLearningBounds.kt` | Normalise bornes de variation quotidienne | A | Port direct |
| `PkpdLearningDiagnostics.kt` | Explique qualité/gate de l'apprentissage | B | DTO causal commun |
| `PkpdSmbTailDamping.kt` | Migration préférence et échelle damping tail | B | Séparer migration prefs du calcul pur |
| `PkpdSoftFloorPathMin.kt` | Corrige le plancher numérique des courbes | B | Retirer JSON/types scénario ; golden tests |
| `PredictionPhysioModulation.kt` | Modifie impacts insulin/carb/UAM selon état causal | B | DTO commun ; supprimer Locale |
| `RealTimeInsulinObserver.kt` | Détecte onset et stage avec petites fenêtres | A | État explicite ; horloge injectée |
| `SmbDamping.kt` | Damping tail/exercice/repas gras | A | Port direct |
| `SmbTbrThrottleLogic.kt` | Arbitre espacement SMB/TBR | A | Port direct |
| `TapPeakGovernor.kt` | Fusion peak profil/site/appris/trajectoire | B | Supprimer Locale ; port direct ensuite |
| `TapSitePeakShift.kt` | Décalage peak par âge du site | A | Port direct |
| `TrajectoryPeakBias.kt` | Nudge peak depuis géométrie trajectoire | B | Adapter modèle trajectoire |
| `TrajectoryPeakMismatchScorer.kt` | Estime le peak minimisant l'erreur historique | B | DTO historique commun |

### 5.4 `recursive/` — Recursive Belief Tree

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `BeliefLeafAdapter.kt` | Contrat d'une feuille RBT | A | Port direct |
| `BeliefLeafAdapterRegistry.kt` | Lit et normalise toutes les feuilles par horizon | B | Découpler les nombreux DTO AIMI ; tests de couverture |
| `BeliefLeafId.kt` | Registre des feuilles et groupes micro/meso/macro/meta/shadow | A | Port direct ; ordre/version figés |
| `BeliefLeafRegistry.kt` | Façade de collecte | A | Port direct |
| `BeliefParadoxId.kt` | Identifiants de paradoxes | A | Port direct |
| `ChannelInterferenceOptimizer.kt` | Optimise SMB/TBR selon coût et tension | A | Port direct ; vérifier discrétisation |
| `CredibilityCascade.kt` | Propage la crédibilité parent/enfant | A | Port direct |
| `RbtChaosEvaluator.kt` | Score tensions, paradoxes, incertitude et flapping | A | Port direct |
| `RbtEpisodeMemory.kt` | Mémoire post-hypo/chaos avec TTL | A | État explicite ; persistance et horloge monotone |
| `RbtExtendedSignals.kt` | DTO de tous les signaux étendus | A | Port direct ; versionner le schéma |
| `RbtResolutionBridge.kt` | Pont des résolutions vers canaux de dose | A | Port direct |
| `RecursiveBeliefAuthorityGate.kt` | Limite l'autorité selon sécurité/physio/tree | B | Retirer JSON ; tests combinatoires et fail-closed |
| `RecursiveBeliefEngine.kt` | MR-7 : collecte, croyance, projection, mémoire | A | Injecter `RecursiveBeliefMemoryState` |
| `RecursiveBeliefMemory.kt` | Anneaux de belief echo | A | Supprimer singleton global ; état immuable/actor |
| `RecursiveBeliefModels.kt` | Nœuds, tensions, paradoxes, résolutions, snapshot | B | Découpler types externes ; port direct ensuite |
| `RecursiveBeliefParadox.kt` | Détecte paradoxes inter-échelles | B | DTO commun |
| `RecursiveBeliefPreferences.kt` | Active shadow/authority/wavelet depuis prefs | B | Mapper depuis snapshot typé |
| `RecursiveBeliefReleaseCalculator.kt` | Plancher de release HTR/SMB | B | Adapter modèles HTR communs |
| `RecursiveBeliefResolver.kt` | Résout autorités, canaux, basal-first et Harmonia SMB | B | Principal lot de golden tests Native |
| `RecursiveBeliefTickContext.kt` | Entrée exhaustive d'un tick RBT | B | En faire un DTO commun stable, sans objets AAPS |
| `UnfoldExporter.kt` | Export complet RBT JSON et ligne de log | B | Garder modèle commun, sérialisation telemetry |
| `WaveletBelief.kt` | Bandes wavelet et signal multi-échelle | A | Port direct ; tolérances numériques |

### 5.5 `safety/` — protections et enveloppes terminales

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `CapSmbDose.kt` | Cap élémentaire de dose SMB | A | Port direct |
| `CompressionReboundGuard.kt` | Détecte rebond après compression CGM | A | Port direct |
| `CorrectionAggressionBasalCap.kt` | Limite/merge les hausses basales | B | Supprimer Locale ; golden tests |
| `CorrectionAggressionGate.kt` | Évalue niveau d'agression selon historique | B | Adapter historique AAPS |
| `EffectiveIobReleaseAuthority.kt` | Autorité de release selon IOB effectif | B | Supprimer Locale ; port direct ensuite |
| `HighBgOverride.kt` | Exception hyper bornée | B | Adapter modèles de décision |
| `HyperInstalledDroppingExemption.kt` | Ouvre ensemble autorité et safety wall sur descente hyper sûre | A | Port direct ; invariant partagé obligatoire |
| `HypoGuard.kt` | Façade de protection hypo | A | Port direct |
| `HypoLgsBlockReason.kt` | Explique précisément le blocage LGS | B | Adapter enums/scénario |
| `HypoThresholdMath.kt` | Calcule seuil hypo/LGS | B | Adapter `Profile`; fonction pure ensuite |
| `HypoTools.kt` | Helpers hypo | A | Port direct |
| `InsulinLoadGovernor.kt` | Gouverne charge insulinique et échappement hausse | B | Adapter signaux AIMI |
| `InsulinStackingSignals.kt` | DTO de signaux d'empilement | A | Port direct |
| `InsulinStackingStance.kt` | Classifie empilement et produit caps | B | Adapter terminaux/scénario |
| `LgsSafetyTriage.kt` | Résolution de départ sécurité et tiers LGS | B | DTO commun |
| `MealSafetyContext.kt` | Contexte repas utilisé par les protections | A | Port direct |
| `PostHypoAggressiveRiseExit.kt` | Sortie bornée du mode post-hypo | A | Port direct |
| `PostHypoDeliveryAuthority.kt` | Autorité et cap après hypo | B | JSON séparé ; port direct ensuite |
| `PostHypoProjectionCap.kt` | Cap de projection après hypo | A | Port direct |
| `PredictiveHypoEvaluator.kt` | Vérité commune hypo prédictive et règles TIER1/2/3 | A | Port direct ; tests prioritaires Native |
| `SafetyDecision.kt` | DTO décision sécurité | A | Port direct |
| `SafetyNet.kt` | Plafonds SMB par zones relatives à la cible | A | Port direct ; mêmes arrondis |
| `SafetyRiskExportSnapshot.kt` | Snapshot d'audit sécurité | B | DTO commun, sérialisation séparée |
| `SmbMaxLimits.kt` | Limites SMB communes | A | Port direct |

### 5.6 Hormonitor : writer, reader et UI

| Fichier | Rôle réel | Classe | Travail de migration |
|---|---|---:|---|
| `physio/AimiHormonitorStudyExporterMTR.kt` | Écrit cinq flux, agrège quotidien/QA, maintient watchdog et état | C | Décomposer en cœur commun et writer/identity/clock plateforme |
| `hormonitor/viewer/HormonitorLabels.kt` | Libellés d'affichage | B | Localisation/format commun ou Swift |
| `hormonitor/viewer/HormonitorReader.kt` | Lecture incrémentale JSONL, regroupement et détail journalier | B | `FileSystemPort`, `kotlinx.serialization`, streaming commun |
| `hormonitor/viewer/HormonitorViewerModels.kt` | Modèles d'agrégation UI | A | Port direct |
| `hormonitor/viewer/HormonitorViewerScreen.kt` | Écran Compose Android et sélection du répertoire | D | SwiftUI natif ou Compose Multiplatform après preuve UX |

---

## 6. Architecture KMP cible

### 6.1 Modules proposés

```text
aimi-domain-common
  ├─ patient : causal posterior, PatientState, Tree, MealCertainty, Harmonia
  ├─ physio  : phases, absorption, latent state, patterns, thermal, thyroid
  ├─ pkpd    : kernels, estimator, fusion, predictions, damping
  ├─ rbt     : leaves, MR-7, memory, resolver, authority
  └─ safety  : hypo/LGS, stacking, caps, terminal guards

aimi-runtime-common
  ├─ AimiPhysioDecisionEngine
  ├─ EngineState + migrations
  ├─ PreferenceSnapshot
  ├─ replay/serialization
  └─ telemetry events

aimi-platform-android
  ├─ Health Connect + UnifiedActivityProvider
  ├─ WorkManager
  ├─ AAPS persistence/preferences
  └─ files/logging

aimi-platform-ios
  ├─ HealthKit/Watch data adapter
  ├─ Trio/LoopKit history adapter
  ├─ BLE/background hooks + BGProcessingTask
  └─ Application Support / Documents export
```

### 6.2 API déterministe

```kotlin
data class PhysioEngineInput(
    val tickId: Long,
    val now: Instant,
    val zoneId: TimeZoneId,
    val glucose: GlucoseSnapshot,
    val insulin: InsulinSnapshot,
    val meal: MealSnapshot,
    val physiology: PhysiologySnapshot,
    val predictions: PredictionSnapshot,
    val preferences: AimiPreferenceSnapshot,
    val pumpConstraints: PumpConstraintSnapshot,
)

data class PhysioEngineOutput(
    val patientState: PatientStateSnapshot,
    val tree: PhysiologicalTreeSnapshot,
    val harmonia: HarmoniaDecision?,
    val recursiveBelief: RecursiveBeliefSnapshot?,
    val safety: SafetyEnvelope,
    val therapyIntent: TherapyIntent,
    val nextState: PhysioEngineState,
    val telemetry: List<AimiTelemetryEvent>,
)
```

Les side effects sont exécutés **après** le calcul : persistance, JSONL, notification, UI et commande pompe. Un échec Hormonitor ne peut donc jamais faire échouer le tick.

### 6.3 Ports nécessaires

| Port | Responsabilité |
|---|---|
| `AimiClock` | heure murale, temps monotone, fuseau et calcul d'âge |
| `PhysiologyDataPort` | lectures typées, provenance, unité, cadence, disponibilité et autorisation |
| `TherapyHistoryPort` | BG, bolus, basal, IOB/COB, TDD, actions précédentes |
| `PreferenceStore` | snapshot immuable au début du tick et écriture contrôlée des états appris |
| `EngineStateStore` | état versionné et transactionnel des mémoires inter-ticks |
| `AimiMutex/EngineActor` | un seul tick dose à la fois ; sérialise mutations d'apprentissage |
| `BackgroundWorkPort` | opportunités de refresh/backfill sans promesse de cadence |
| `TelemetrySink` | événements non bloquants, métrique de pertes, flush explicite |
| `FileSystemPort` | append atomique, rotation, lecture streaming et export utilisateur |
| `DatasetIdentityPort` | identifiant pseudonyme durable sans Android ID |
| `CryptoPort` | SHA-256 multiplateforme |
| `Logger` | diagnostics structurés, sans dépendance AAPS |

### 6.4 Concurrence

Kotlin/Native ne doit pas recevoir une traduction littérale de `AtomicReference`, `@Volatile`, `@Synchronized`, `ReentrantReadWriteLock` et singletons mutables. La règle cible est :

- un **actor moteur** séquentiel détient `EngineState` ;
- les nouvelles données capteur sont stockées dans un snapshot immuable ;
- le tick capture une version de ce snapshot au démarrage ;
- les événements UI peuvent continuer, mais ne remplacent jamais l'état du tick courant ;
- le writer Hormonitor a sa propre file et n'accède qu'aux événements immuables finalisés.

---

## 7. Health Connect vers HealthKit

### 7.1 Matrice des données

| Besoin AIMI | Android actuel | iOS cible | Parité et règle |
|---|---|---|---|
| FC instantanée/15 min | `HeartRateRecord` + DB Wear | `heartRate` samples | Bonne si timestamps et source sont conservés ; cadence Apple Watch non garantie |
| FC repos | `RestingHeartRateRecord`, sinon minimum matinal | `restingHeartRate` | Bonne fonctionnellement ; ne pas mélanger mesure native et proxy sans tag |
| Pas 5/15/60 min | DB Wear/phone puis Health Connect | `stepCount`, requête cumulative par intervalle | Bonne ; dédupliquer par source et provenance |
| Sommeil et stades | `SleepSessionRecord` | `sleepAnalysis` | Partielle : catégories et règles d'overlap diffèrent ; calculer efficacité dans le domaine |
| HRV | `HeartRateVariabilityRmssdRecord` | `heartRateVariabilitySDNN` | **Non équivalente** ; métrique explicitement typée et baseline séparée |
| HRV dérivée | RMSSD fourni | RMSSD calculable seulement si séries RR exploitables | Ne dériver que si données heartbeat suffisantes et algorithme validé |
| Température peau/poignet | `SkinTemperatureRecord` | wrist temperature pendant sommeil sur appareils compatibles | Partielle ; source, baseline, emplacement et cadence obligatoires |
| Température basale | `BasalBodyTemperatureRecord` | `basalBodyTemperature` si fournie | Bonne sur le papier, disponibilité utilisateur variable |
| Activité/exercice | provider AAPS + étapes | workout/activity samples | Mapping à définir ; les pas seuls restent le signal commun minimum |

Health Connect définit explicitement `HeartRateVariabilityRmssdRecord` comme RMSSD. HealthKit définit `heartRateVariabilitySDNN` comme l'écart-type des intervalles RR. Ces métriques ne doivent pas partager une même baseline ni les mêmes seuils :

- Android : [HeartRateVariabilityRmssdRecord](https://developer.android.com/reference/androidx/health/connect/client/records/HeartRateVariabilityRmssdRecord)
- Apple : [heartRateVariabilitySDNN](https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/heartratevariabilitysdnn)

### 7.2 Correction préalable du modèle de données

Le modèle actuel utilise souvent `0`, `60 bpm` ou une collection vide pour plusieurs situations différentes. Exemples observés :

- HRV absente devient `0.0` ;
- RHR absente peut devenir `60` ;
- pas indisponibles deviennent `0` ;
- `HealthContextSnapshot` donne par défaut une efficacité de sommeil de `1.0` ;
- une erreur, une permission refusée et une vraie absence de données finissent souvent dans le même chemin vide.

Ce défaut existe déjà côté Android. Le reproduire sur iOS donnerait une égalité technique trompeuse. Le contrat commun doit utiliser :

```kotlin
sealed interface MeasurementAvailability<out T> {
    data class Available<T>(val value: T, val observedAt: Instant, val source: Source) : MeasurementAvailability<T>
    data class Stale<T>(val lastValue: T, val observedAt: Instant, val source: Source) : MeasurementAvailability<T>
    data object NoSample : MeasurementAvailability<Nothing>
    data object PermissionDenied : MeasurementAvailability<Nothing>
    data object Unsupported : MeasurementAvailability<Nothing>
    data class Failed(val category: String) : MeasurementAvailability<Nothing>
}
```

Les décisions consomment ensuite `valueOrNull`, âge, provenance et confiance. Elles ne consomment jamais une valeur sentinelle fabriquée.

### 7.3 HRV et sécurité

Options sûres :

1. **Mode métrique native séparée** : `HRV_RMSSD` et `HRV_SDNN` ont chacune leur baseline et leurs seuils appris. C'est l'option recommandée au lancement.
2. **RMSSD dérivé** : calcul depuis des intervalles RR/heartbeat suffisamment complets. Il faut tracer le filtrage des battements, la fenêtre et la qualité. Cette disponibilité ne doit pas être supposée.
3. **HRV indisponible** : la contribution HRV devient neutre et réduit la confiance ; elle n'est jamais égale à une HRV faible.

Avant ouverture de l'autorité sur iOS, auditer tous les usages de `hrvRmssd`, `hrvDeviationZ`, `sensorConfidence`, stress et thermal proxy. En particulier, `sensorConfidence` agrège aujourd'hui des signaux wearable et peut fermer RBT avec `SENSOR_LOW`, alors que la qualité CGM est correcte. Il faut séparer :

- `cgmConfidence` pour la sécurité et l'autorité dose ;
- `wearablePhysioConfidence` pour la force des hypothèses physiologiques ;
- `causalConfidence` pour l'arbitrage meal/non-meal.

### 7.4 Actualisation en arrière-plan

HealthKit permet des `HKObserverQuery` et la background delivery, avec entitlement. Les notifications signalent un changement ; il faut ensuite requêter les nouvelles données et appeler le completion handler. Apple limite les notifications à la fréquence enregistrée et ne garantit pas un réveil toutes les 15 minutes :

- [Executing Observer Queries](https://developer.apple.com/documentation/healthkit/executing-observer-queries)
- [HealthKit background-delivery entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.healthkit.background-delivery)

Stratégie iOS :

- rafraîchir le snapshot au tick BLE/pompe et lorsque l'app est active ;
- utiliser les observer queries comme accélérateur, pas comme horloge thérapeutique ;
- lancer baseline et thermique quand une opportunité de fond est accordée ;
- conserver un snapshot durable avec âge explicite ;
- en cas de données anciennes, neutraliser progressivement les modulations et conserver les protections glucose/IOB.

---

## 8. Hormonitor sur iOS

### 8.1 Fonction actuelle

Le writer produit :

1. `AIMI_HORMONITOR_event_stream_v1.jsonl` ;
2. `AIMI_HORMONITOR_daily_outcomes_v1.jsonl` ;
3. `AIMI_HORMONITOR_dataset_qa_v1.jsonl` ;
4. `AIMI_HORMONITOR_shadow_contributions_v1.jsonl` ;
5. `AIMI_HORMONITOR_loop_blackbox_v1.jsonl` ;
6. l'état de reprise `AIMI_HORMONITOR_daily_state_v1.json`.

Le schéma courant est `1.4.0`. La file contient 512 écritures avec `DROP_OLDEST`. Le compteur `droppedWrites` n'est incrémenté que lorsque `trySend` échoue ; or une insertion avec `DROP_OLDEST` peut réussir tout en évincant l'élément le plus ancien. Le code actuel ne mesure donc pas fidèlement toutes les pertes par saturation. Le watchdog émet un événement si aucun pulse loop n'arrive pendant 10 minutes et un événement intra-tick selon une préférence de 60 à 600 s.

### 8.2 Découpage cible

| Composant | Commun | Plateforme |
|---|---|---|
| `HormonitorEventBuilder` | Construit l'événement final typé | — |
| `HormonitorSchemaCodec` | Encode/décode JSONL 1.4.x | — |
| `HormonitorDailyAggregator` | Compteurs, TIR/TDD, QA | — |
| `HormonitorWatchdogPolicy` | Détermine les événements stall | — |
| `HormonitorWriter` | File, backpressure, métriques de perte | Coroutine/actor commun possible | Ou writer natif si contraintes iOS |
| `FileSystemPort` | Contrat append/overwrite/rotate/read | Android `Documents/AAPS`; iOS Application Support/Documents |
| `DatasetIdentityPort` | Contrat pseudonyme | Android ID actuel ; Keychain UUID iOS |
| `MonotonicClock` | Contrat uptime | `SystemClock.elapsedRealtime`; `ProcessInfo.systemUptime`/continuous clock |
| Viewer models/reader | Agrégation commune | SwiftUI/Compose pour l'écran |

### 8.3 Règles iOS

- Écriture automatique dans `Application Support/AIMI/Hormonitor`, exclue des sauvegardes si la politique produit l'exige.
- Export volontaire vers Files avec `UIDocumentPicker`; aucun accès libre à un équivalent de `/Documents/AAPS` partagé.
- Append transactionnel : segment temporaire/journal ou handle sérialisé, `fsync` proportionné au risque, rotation par taille/jour.
- Le dataset ID provient d'un UUID aléatoire conservé dans le Keychain et haché avec un namespace de schéma. Ne pas utiliser `identifierForVendor` comme identité scientifique durable.
- Les événements contiennent `platform`, `app_version`, `engine_version`, `schema_version`, `metric_kind`, `source`, `observed_at`, `ingested_at` et `availability`.
- Passer au minimum à un schéma additif `1.5.x` avec `hrv_metric_kind` et `hrv_value_ms`. Sur iOS, un échantillon SDNN ne doit jamais être écrit dans l'ancien champ `hrv_rmssd_ms`; celui-ci reste `null` sauf RMSSD réellement mesuré ou dérivé et qualifié.
- Une écriture perdue ou un fichier corrompu ne change jamais la dose. Le compteur de pertes et le dernier numéro de séquence sont exportés.
- Remplacer `DROP_OLDEST` silencieux par une politique observable : numéro de séquence, callback d'overflow et compteur exact, ou spool durable borné.
- Préserver les `event_id` et `tick_id` pour joindre stream, shadow, QA et blackbox.

### 8.4 Parité UX

La parité UX exacte n'est pas un objectif utile : Android expose directement les fichiers partagés, iOS utilise son sandbox. La parité fonctionnelle requise est :

- consulter les jours, statistiques, modes Tree/Harmonia et indicateurs d'intégrité ;
- filtrer et lire un jour sans charger tout le fichier en mémoire ;
- exporter un bundle signé/haché ;
- afficher clairement les données absentes, stale et les écritures perdues.

---

## 9. Invariants de sécurité non négociables

Ces règles doivent devenir des propriétés testées du moteur commun, pas des conventions dispersées.

1. **Un seul propriétaire final par canal.** Un tick ne peut pas laisser T3C, Harmonia basal-first et le moteur basal normal augmenter simultanément la même commande.
2. **Réduction monotone des protections.** Un module safety peut réduire/annuler ; il ne peut augmenter une dose que dans une exception explicitement nommée et bornée.
3. **Harmonia ne dépasse jamais l'enveloppe dure.** Toute élévation SMB reste sous maxSMB effectif, maxSMBHB, headroom IOB, caps HARD, stacking, post-hypo et limites pompe.
4. **Un cap HARD ne devient jamais SOFT par sérialisation ou hystérésis.** `PatternCapHold` ne retient que les caps HARD.
5. **MealCertainty ne neutralise jamais l'hypo réelle.** Elle peut surclasser le veto activité, pas `TIER1_BG_REAL`, max IOB ou un terminal hypo crédible.
6. **TIER1 réel arrête le pipeline.** Les tiers prédictifs peuvent être réconciliés, pas une glycémie réellement sous seuil.
7. **L'exception de descente hyper est partagée.** Autorité RBT et safety wall consomment le même `HyperInstalledDroppingExemption`; jamais deux copies divergentes.
8. **Fail-closed sur entrée invalide.** NaN, timestamp futur, unité inconnue, prediction absente ou état non migrable ne doivent jamais augmenter insulin.
9. **Données physiologiques absentes = contribution neutre, confiance réduite.** Jamais un stress réel fabriqué à partir de zéro sentinelle.
10. **L'état de présentation ne dose pas.** Un refresh HealthKit/Context asynchrone publie pour l'UI et le prochain tick seulement.
11. **L'apprentissage PKPD est hygiénique.** Pas de mise à jour en hypo, chute rapide, exercice, carbs excessifs ou contexte causal non propre ; une erreur de persistance garde les derniers paramètres validés.
12. **L'export est observe-only.** JSON, fichier, réseau, UI ou watchdog ne peuvent retarder ou invalider la commande thérapeutique.
13. **Horloge monotone pour les durées.** Les changements de fuseau et d'heure civile ne prolongent pas une hystérésis ou un épisode.
14. **Quantification finale identique.** Les pas basal/SMB et les caps pompe sont appliqués au même stade sur Android et iOS.
15. **Traçabilité de l'autorité.** Chaque augmentation possède source, demande avant/après, enveloppe, blockers, raison et état de sécurité terminal.

---

## 10. Corpus et stratégie de replay

### 10.1 Limite des tests actuels

Les tests unitaires couvrent bien de nombreux composants. `RecursiveBeliefJsonlReplayTest`, malgré son nom, construit des lignes synthétiques et appelle directement RBT ; il ne recharge pas un tick AIMI complet avec son état précédent. `HormonitorReaderTest` vérifie la lecture/agrégation d'un petit dataset synthétique.

Il manque donc un replay **entrée + état -> sortie + état** de toute la cascade.

### 10.2 Format minimal à capturer

Chaque tick de référence Android doit contenir :

- identifiant, instant, fuseau, version code/config ;
- BG et tous les deltas, bruit, source et âge CGM ;
- IOB détaillé, activité, bolus récents, basal actif, profil et contraintes pompe ;
- COB, repas, modes manuels et historique utile ;
- toutes les courbes/terminaux ou les entrées suffisantes pour les recalculer ;
- snapshot physiologique typé avec origine, métrique, âge, unité et autorisation ;
- préférences effectivement lues pendant le tick ;
- `EngineState` avant tick ;
- Tree, MealCertainty, Harmonia, RBT, authority gate, safety envelope ;
- commande SMB/TBR finale et `EngineState` après tick ;
- événements Hormonitor et compteur d'écritures perdues.

### 10.3 Scénarios obligatoires

Au minimum :

- hypo réelle et chaque tier LGS ;
- faux terminal à 39 mg/dL en hyper ;
- descente hyper juste de part et d'autre des bornes −15, BG 180 et projection hypo+40 ;
- repas annoncé/non annoncé, première et seconde vague, repas gras/protéiné ;
- effort actif puis adrénaline post-effort ;
- dawn/endogène, stress, inflammation, phase cycle et thyroïde ;
- post-hypo léger/profond avec expiration 30/45 min et aggressive-rise exit ;
- données physiologiques fraîches, stale, absentes, permission refusée et source changeante ;
- redémarrage entre deux ticks pendant une hystérésis, un épisode RBT et un apprentissage PKPD ;
- pression d'écriture Hormonitor supérieure à 512 événements ;
- changement d'heure/fuseau et timestamp futur ;
- T3C, Harmonia production, Autodrive actif/inactif et sorties précoces.

### 10.4 Critères de réussite

| Sortie | Critère |
|---|---|
| Commande pompe SMB/TBR | Identique après quantification et caps |
| Autorités/blockers/reason codes | Identiques |
| État discret Tree/Harmonia/RBT/phase/pattern | Identique |
| Doubles intermédiaires | Tolérance documentée, par exemple absolue `1e-9` pour calcul pur et tolérance métier avant seuil |
| État suivant | Identique champ par champ hors métadonnées plateforme |
| JSONL | Schéma et valeurs métier identiques ; ordre des clés non pertinent |
| Entraînement PKPD long | Même nombre d'updates acceptées et paramètres dans une tolérance serrée |

Tout seuil clinique doit avoir des tests `epsilon` des deux côtés. L'objectif n'est pas le bit-à-bit arbitraire de `libm`, mais aucune divergence de branche, d'autorité ou de dose.

---

## 11. Phases et charge de ce lot

Les charges couvrent uniquement le cœur physiologique/Harmonia/RBT/PKPD/safety/Hormonitor. Elles n'incluent ni le moteur AIMI entier, ni Trio, ni TFLite/SMB/basal learners hors PKPD.

| Phase | Livrable | Charge P50 |
|---|---|---:|
| P0 — Freeze et capture | Contrats d'entrée/état, corpus réel, schémas et hash de référence | 6–9 semaines-personnes |
| P1 — Domaine pur | Tree, MealCertainty, Harmonia, phases, patterns, safety de base en `commonMain` | 8–12 sp |
| P2 — RBT et états | RBT complet, mémoires versionnées, actor moteur, autorités et golden tests | 7–11 sp |
| P3 — PKPD | Kernels, prédictions, learning state, persistence et tests longitudinaux Native | 6–9 sp |
| P4 — Données physio | Nouveau contrat availability, Android adapter corrigé, HealthKit adapter et baselines HRV | 8–13 sp |
| P5 — Hormonitor | Codec/agrégateur/writer commun, filesystem iOS, viewer/export minimal | 5–8 sp |
| P6 — Shadow et validation | Replay Android/Native/iOS, tests appareil, sorties précoces, soak et correction | 8–14 sp |
| **Total** | | **48–76 semaines-personnes** |

Budget P80 recommandé : **60–90 semaines-personnes**. Avec un tarif de 700 à 1 000 €/jour, cela représente environ **210–380 k€ en P50**, **hors validation réglementaire/clinique**.

Le travail se parallélise partiellement, mais P0 et le contrat d'état précèdent tous les lots. P6 ne peut pas être comprimé proportionnellement au nombre de développeurs.

---

## 12. Gates de décision

### Gate A — contrat

- Tous les champs lus par Tree/Harmonia/RBT/PKPD/safety sont dans des DTO versionnés.
- Les mémoires inter-ticks ont un propriétaire et une politique de restauration.
- HRV et disponibilité sont sémantiquement typées.

### Gate B — compilation Native

- Les modules domaine compilent pour `iosArm64` et `iosSimulatorArm64`.
- Les 83 suites pertinentes ont un équivalent `commonTest` ou un golden test Native.
- Aucun `android.*`, `java.io`, `java.util.concurrent`, `org.json` ou type AAPS n'entre dans le domaine.

### Gate C — parité algorithmique

- Le corpus séquentiel Android/KMP ne diverge sur aucune branche clinique.
- Les invariants de la section 9 sont vérifiés par tests de propriété.
- Redémarrage et état stale ont un comportement documenté et protecteur.

### Gate D — parité de données iOS

- HealthKit fournit les sources attendues sur appareils réels.
- Les baselines SDNN ne réutilisent aucune baseline RMSSD.
- Permissions, absence, stale et erreurs sont visibles dans Hormonitor.

### Gate E — production shadow

- Plusieurs semaines de shadow sans commande iOS montrent mêmes autorités et doses quantifiées.
- Aucune perte silencieuse Hormonitor ; taux de perte et QA acceptables.
- Le rythme de données de fond est suffisant, ou la dégradation vers neutralité est validée.

---

## 13. Risques prioritaires et arbitrages

| Priorité | Risque | Impact | Arbitrage recommandé |
|---:|---|---|---|
| 1 | Singletons/mémoires invisibles non capturés | Replay faux, divergence après quelques ticks | `EngineState` explicite avant tout portage |
| 2 | RMSSD remplacé par SDNN | Faux stress/inflammation, changement des gates | Métriques/baselines séparées, HRV neutre au lancement si nécessaire |
| 3 | `sensorConfidence` mélange CGM et wearable | Autorité RBT fermée pour absence de wearable | Trois confiances distinctes ; CGM seul décide de la qualité capteur dose |
| 4 | Rafraîchissement async concurrent du patient runtime | Tree affiché différent du Tree ayant dosé | Actor et snapshot capturé au début du tick |
| 5 | Copie littérale atomiques/verrous JVM | Races Native ou architecture figée | État immuable + actor, pas `expect/actual Atomic*` partout |
| 6 | Cadence WorkManager supposée sur iOS | Baseline/stale non prévisible | Event-driven, opportuniste, âge explicite, neutralité progressive |
| 7 | Séparation JSON tardive | `org.json` contamine `commonMain` et le domaine | Sérialisation aux frontières dès P1 |
| 8 | Hormonitor écrit sur le chemin dose | Latence ou panne disque affecte le tick | Événement immuable après décision, writer indépendant |
| 9 | `DROP_OLDEST` évince sans métrique fiable | Dataset scientifique incomplet mais QA apparemment saine | Séquence monotone, compteur exact et spool observable |
| 10 | Tests composant sans séquence d'état | Parité proclamée trop tôt | Replay longitudinal, restart tests et property tests |
| 11 | Chiffre basé sur nombre de lignes seulement | Sous-budget intégration/validation | Budgeter les contracts, états, données et soak séparément |

### Arbitrages critiques retenus

1. **Préserver l'ordre causal avant d'optimiser le code.** Tree, MealCertainty, Harmonia, RBT et safety forment un protocole de décision.
2. **État explicite plutôt que singletons portés.** C'est la condition de testabilité, de reprise et de parité iOS.
3. **Même intelligence, données honnêtes.** La parité iOS ne doit pas maquiller SDNN en RMSSD ni permission refusée en zéro.
4. **Harmonia reste orchestrateur, jamais contour de safety.** Ses lifts restent sous l'enveloppe dure et ses décisions sont traçables.
5. **Hormonitor conserve le schéma scientifique, pas les chemins Android.** La valeur est dans les événements et leurs jointures, non dans `/Documents/AAPS`.
6. **Le lancement iOS peut fonctionner sans HRV.** Il vaut mieux une contribution neutre et une confiance moindre qu'une pseudo-parité physiologique dangereuse.
7. **Aucune activation de dosage iOS avant replay séquentiel puis shadow.** La compilation Native seule n'est pas une preuve clinique.
