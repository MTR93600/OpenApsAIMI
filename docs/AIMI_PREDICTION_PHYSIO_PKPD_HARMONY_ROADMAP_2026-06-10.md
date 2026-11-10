# AIMI — Roadmap d'harmonisation prédiction / physio / arbre logique / PKPD

**Statut :** analyse produit et feuille de route d'implémentation  
**Date :** 2026-06-10  
**Portée :** repas non déclarés avec Autodrive V3, compréhension physiologique, déploiement de l'arbre récursif, PKPD adaptatif, variation DIA/peakTime, impact ML

## 1. Résumé exécutif

Le système AIMI est déjà au-delà d'une logique APS classique.

Il possède désormais :

- une séparation sécurité / scénario avec `clinicalFloor` et `scenarioBest` ;
- une lecture physio amont via `PhysiologicalPhaseClassifier`, `PhysioLatentState`, `PhysiologicalPatternSnapshot` ;
- une lecture repas inter-ticks via `MealAbsorptionPhaseEngine` ;
- une lecture causale intermédiaire via `UamHypothesisState` ;
- une couche produit via `PatientStateSnapshot` et `PatientModeOrchestrator` ;
- une résolution multi-échelle via `RecursiveBeliefEngine` et `RecursiveBeliefResolver` ;
- une PKPD déjà reconnectée au poids, à l'état latent et à la trajectoire.

Le point faible restant n'est plus le manque de briques. Le point faible est la **dispersion de la causalité**.

Aujourd'hui, plusieurs couches comprennent chacune une partie de la situation du patient, mais il n'existe pas encore une **vérité causale unique** qui pilote :

- l'interprétation du type de montée ;
- la quantité d'insuline nécessaire ;
- la vitesse attendue de l'effet insulinique ;
- la durée résiduelle de l'effet ;
- la manière dont le ML doit apprendre ou s'abstenir d'apprendre.

La cible produit recommandée est donc :

- une **source causale unique par tick** ;
- un **PKPD à deux étages** : structurel appris lentement, contextuel non persisté ;
- une détection repas non déclarés gouvernée par une **compétition causale continue** et non seulement par des seuils ;
- un arbre récursif qui arbitre les **causes** et pas seulement l'autorité de release ;
- un ML qui reste un **raffineur borné**, entraîné sur des contextes propres.

## 2. Ancrages runtime déjà en place

Les briques actuelles les plus structurantes sont :

- `MealAbsorptionPhaseEngine.evaluate()` et `classifyRaw()` dans `physio/MealAbsorptionPhaseEngine.kt`
- `PhysiologicalPhaseClassifier.classify()` dans `physio/PhysiologicalPhaseClassifier.kt`
- `UamHypothesisStateBuilder.build()` dans `physio/UamHypothesisState.kt`
- `PhysioLatentStateBuilder.build()` dans `physio/PhysioLatentState.kt`
- `PatientStateEngine.build()` dans `patient/PatientStateSnapshot.kt`
- `PatientModeOrchestrator.evaluate()` dans `patient/PatientModeOrchestrator.kt`
- `DecisionPredictionAuthorityResolver.resolve()` dans `risk/DecisionPredictionAuthority.kt`
- `RecursiveBeliefEngine.build()` et `RecursiveBeliefResolver.resolve()` dans `recursive/`
- `PkPdIntegration.computeRuntime()` dans `pkpd/PkPdIntegration.kt`
- `InsulinLoadGovernor.evaluate()` dans `safety/InsulinLoadGovernor.kt`
- `SmbRefinementFeatureSchema.buildRuntimeFeatures()` et `AimiSmbTrainer.refine()` dans `ml/`

Les documents déjà alignés avec cette direction sont :

- `docs/AIMI_SCENARIO_PROJECTION.md`
- `docs/AIMI_RECURSIVE_BELIEF.md`
- `docs/AIMI_PHYSIOLOGICAL_PHASE.md`
- `docs/AIMI_PHYSIOLOGICAL_PATTERN_CATALOG.md`
- `docs/AIMI_MEAL_ABSORPTION_PHASE.md`
- `docs/AIMI_PHYSIO_RUNTIME_ACTIVATION_2026-06-06.md`
- `docs/LGS_PREDICTIVE_MEAL_BLIND_CASE_STUDY.md`
- `docs/AIMI_HORMONITOR_STUDY_NOTES_2026-06-06.md`

## 3. Lot 1 — Posterior causal unifié

**Objectif clinique :** créer une lecture causale unique par tick avant toute action insulinique.

**Variables existantes à réutiliser :**

- `UamHypothesisState.mealProb`
- `UamHypothesisState.dawnEndogenousProb`
- `UamHypothesisState.stressProb`
- `UamHypothesisState.postHypoProb`
- `UamHypothesisState.lateFatProb`
- `PhysioLatentState.mealProb`
- `PhysioLatentState.endogenousGlucoseDrive`
- `PhysioLatentState.transientResistanceProb`
- `PhysioLatentState.sleepDebtScore`
- `PhysioLatentState.postHypoReboundProb`
- `PhysioLatentState.sensorConfidence`
- `PatientStateSnapshot.falseMealSuppression`
- `PatientModeOrchestrator.Decision.mode`
- `PatientModeOrchestrator.Decision.confidence`

**Évolution recommandée :**

- introduire un `CausalStatePosterior` unique ;
- y exposer des probabilités normalisées pour `FAST_MEAL`, `PROLONGED_MEAL`, `DAWN_ENDOGENOUS`, `POST_HYPO_RECOVERY`, `STRESS_RESISTANCE`, `EXERCISE_AFTERBURN`, `INFLAMMATORY_DRIFT`, `ABSORPTION_UNCERTAIN` ;
- faire de ce posterior la source lue par `DecisionPredictionAuthorityResolver`, `RecursiveBeliefResolver`, `PkPdIntegration` et l'export replay.

**Observable avant :**

- une même montée peut être vue comme repas dans une couche, stress dans une autre, et dawn dans une troisième ;
- `falseMealSuppression` intervient bien, mais reste encore une conséquence distribuée ;
- les logs racontent bien plusieurs morceaux de l'histoire, sans une seule causalité consolidée.

**Observable après :**

- chaque tick possède une cause dominante et des causes concurrentes explicites ;
- un dawn lent, un rebond post-hypo et un vrai repas rapide cessent d'être traités comme des variations d'un même phénomène ;
- les exports replay et Hormonitor peuvent reconstruire un récit clinique unique.

**Validation attendue :**

- dawn matinal proche cible ;
- vrai petit-déjeuner sans COB ;
- rebond post-hypo matinal ;
- stress aigu avec FC haute ;
- late fat du soir.

## 4. Lot 2 — Séparer besoin en insuline et cinétique d'action

**Objectif clinique :** éviter qu'un seul paramètre compense plusieurs réalités physiologiques différentes.

**Variables existantes à réutiliser :**

- `PkPdRuntime.params.diaHrs`
- `PkPdRuntime.params.peakMin`
- `PkPdRuntime.tailFraction`
- `PkPdRuntime.physioAbsorptionFactor`
- `PkPdRuntime.physioSiFactor`
- `PkPdRuntime.weightKineticFactor`
- `PhysioLatentState.circadianSiFactor`
- `PhysioLatentState.transientResistanceProb`
- `PhysioLatentState.endogenousGlucoseDrive`
- `PhysioLatentState.postHypoReboundProb`
- `TrajectoryPeakBias.minutesNudge()`

**Évolution recommandée :**

- distinguer un PKPD **structurel** appris lentement ;
- distinguer un PKPD **contextuel** appliqué à la volée ;
- ne plus laisser `DIA` ou `peakTime` absorber implicitement du cortisol, de la dette de sommeil, une inflammation, ou un rebond post-hypo.

**Découpage cible :**

- `baseDiaHrs`, `basePeakMin`, `baseTailProfile` persistés ;
- `effectivePeakShiftMin`, `effectiveTailScale`, `effectiveAbsorptionFactor`, `effectiveSiFactor` non persistés ;
- modulation contextuelle gouvernée par la cause dominante et la confiance capteur.

**Observable avant :**

- le système comprend déjà une partie de la résistance et de l'absorption, mais les dimensions restent encore trop fusionnées ;
- certaines adaptations peuvent être interprétées comme un changement de PKPD alors qu'il s'agit surtout d'un changement d'état du patient.

**Observable après :**

- stress et mauvais sommeil ralentissent ou déforment l'action attendue sans polluer durablement le `baseDiaHrs` ;
- le système peut reconnaître une résistance transitoire sans conclure à une insuline structurellement plus lente ;
- la trajectoire et la thermique influencent l'effet du tick, pas la mémoire pharmacologique permanente.

**Validation attendue :**

- même repas en contexte neutre vs contexte stress ;
- même IOB en post-hypo vs hors post-hypo ;
- même montée avec inflammation thermique vs sans inflammation.

## 5. Lot 3 — Détection repas non déclarés gouvernée par compétition causale

**Objectif clinique :** rendre la détection repas très sensible aux vrais repas et très résistante aux faux positifs endocriniens.

**Variables existantes à réutiliser :**

- `MealAbsorptionPhaseEngine.Output.phase`
- `MealAbsorptionPhaseEngine.Output.belief`
- `MealAbsorptionPhaseEngine.Output.mealDeliveryPriority`
- `PhysiologicalPhaseClassifier.classify()`
- `BehavioralRiskPolicy.suppressMealLikeScenario`
- `UamHypothesisState.suppressMealInterpretation`
- `PhysioLatentState.falseMealSuppression`
- `DecisionPredictionAuthority.falseMealSuppression`
- `SafetyPredictionTerminalsResolver.isMealRiseConfirmed()`

**Évolution recommandée :**

- faire dépendre `mealDeliveryPriority` du posterior causal consolidé ;
- augmenter le poids des patterns `POST_HYPO_REBOUND`, `POOR_SLEEP_MORNING_RISE`, `HRV_DEPRESSED`, `INFLAMMATORY_DRIFT`, `ENDOGENOUS_COUNTER_REGULATORY` ;
- réduire les flips à seuils entre `meal_evidence`, `guarded_uplift` et `pkpd_retained` ;
- garder la possibilité d'un repas confirmé malgré un contexte hormonal, mais seulement si la signature de montée est réellement meal-compatible.

**Observable avant :**

- la détection repas est déjà plus mature qu'avant ;
- les faux repas dawn/stress sont bien mieux filtrés ;
- il existe encore des zones ambiguës où la montée est assez compatible repas pour une couche, mais pas assez pour les autres.

**Observable après :**

- un dawn lent proche cible ne libère plus une logique repas agressive ;
- un vrai repas rapide sans COB garde sa priorité même si HRV ou thermique sont perturbés ;
- un second wave ou un late fat restent traités comme repas prolongé, pas comme hyper isolée.

**Validation attendue :**

- petit-déjeuner réel sans entrée glucides ;
- déjeuner rapide avec montée franche ;
- dawn hormonal lent ;
- second wave du soir ;
- repas gras tardif.

## 6. Lot 4 — Faire du RBT un arbitre causal, pas seulement un modulateur d'autorité

**Objectif clinique :** faire résoudre par l'arbre la tension entre causes concurrentes avant d'autoriser l'agressivité insulinique.

**Variables existantes à réutiliser :**

- `RecursiveBeliefEngine.believe()`
- `RecursiveBeliefEngine.project()`
- `RecursiveBeliefEngine.deviate()`
- `RecursiveBeliefResolver.mealHypothesisProb`
- `RecursiveBeliefResolver.nonMealHypothesisProb`
- `RecursiveBeliefResolver.suppressMealInterpretation`
- `RecursiveBeliefAuthorityGate.readinessScore`
- `RecursiveBeliefAuthorityGate.liftBlend`
- `RecursiveBeliefTickContext.physiologicalPatterns`
- `RecursiveBeliefTickContext.extended`
- `InsulinLoadGovernor.Evaluation`

**Évolution recommandée :**

- injecter le posterior causal unique comme feuilles méta explicites ;
- réduire les transitions brutales entre `PKPD_ONLY`, `SCENARIO_GUARDED_UPLIFT`, `SCENARIO_MEAL_UPLIFT`, `SCENARIO_TRAJECTORY_UPLIFT` ;
- laisser l'arbre arbitrer un canal final parmi `SMB_PRIORITY`, `BASAL_BRIDGE`, `MEAL_SUPPORT`, `CONSERVATIVE_OBSERVE`, `PKPD_REASSESS`.

**Observable avant :**

- le RBT unifie très bien les signaux et protège bien contre les incohérences grossières ;
- il agit encore davantage comme un méta-résolveur d'autorité que comme une ontologie causale unique.

**Observable après :**

- l'arbre ne dit plus seulement "release ou non release" ;
- il dit aussi "pourquoi" et "par quel canal" ;
- un contexte `DAWN_ENDOGENOUS` ou `POST_HYPO_RECOVERY` devient naturellement un bridge ou une observation protectrice, sans heuristique dispersée.

**Validation attendue :**

- replay avec trajectoire montante non-repas ;
- replay meal rapide avec governor actif ;
- replay stress résistance avec cap progressif ;
- comparaison shadow vs authority.

## 7. Lot 5 — Gouvernance ML et enrichissement du schéma de features

**Objectif clinique :** empêcher le ML d'apprendre à partir de situations causalement mélangées.

**Variables existantes à réutiliser :**

- `SmbRefinementFeatureSchema.latentFeatureNames`
- `SmbRefinementFeatureSchema.modeFeatureNames`
- `SmbRefinementFeatureSchema.buildRuntimeFeatures()`
- `AimiSmbTrainer.maybeTrainAsync()`
- `AimiSmbTrainer.refine()`
- `ReplayQualityExport.uamHypothesisDominant`
- `ReplayQualityExport.patientMode`
- `ReplayQualityExport.qualityTags`
- `AimiUamHandler.confidenceOrZero()`

**Évolution recommandée :**

- enrichir les features avec la causalité dominante, la confiance capteur, le post-hypo, la thermique, et les raisons de protection ;
- exclure de l'entraînement asynchrone les ticks à causalité ambiguë ou protectrice ;
- conserver `refine()` comme correcteur local borné, jamais comme décideur causal.

**Observable avant :**

- le ML affine déjà, mais il reste exposé à des lignes où repas, dawn, stress et post-hypo peuvent être mélangés dans la même logique d'entraînement ;
- il apprend donc potentiellement un comportement moyen sur des situations qui devraient être séparées.

**Observable après :**

- les raffinements deviennent plus cohérents par contexte ;
- moins de surcorrections "intelligentes" mais mal situées ;
- moins d'effets de mémoire indésirables après une série de journées atypiques.

**Validation attendue :**

- compatibilité CSV historique ;
- skip training sur `POST_HYPO_RECOVERY` ;
- skip training sur `DAWN_ENDOGENOUS` ;
- stabilité de `refine()` avec le nouveau schéma.

## 8. Lot 6 — Validation produit et observabilité clinique

**Objectif clinique :** mesurer l'harmonie du produit par scénarios, pas seulement par moyenne globale.

**Variables existantes à réutiliser :**

- `ReplayQualityExport`
- `RecursiveBeliefSnapshot.loadGovernor`
- `PatientStateSnapshot`
- `PatientModeOrchestrator.Decision`
- `AIMI_Decisions.jsonl`
- `patient_story`
- `thermal_belief`

**Évolution recommandée :**

- créer une matrice de validation par causalité dominante ;
- ajouter des tags de désaccord causaux ;
- suivre explicitement les cas où le PKPD, le scénario enrichi et la safety ne racontent pas la même histoire ;
- produire un score d'harmonie produit par scénario.

**Scénarios minimaux à suivre :**

- dawn lent sans repas ;
- vrai petit-déjeuner sans COB ;
- déjeuner rapide sans déclaration ;
- repas gras tardif ;
- rebond post-hypo ;
- stress aigu avec HRV basse ;
- inflammation thermique nocturne ;
- exercice afterburn.

**Observable avant :**

- le système est déjà très bien exporté ;
- l'évaluation reste encore trop orientée composant par composant.

**Observable après :**

- on pourra dire précisément où le produit reste disharmonieux ;
- les arbitrages meals vs endogène vs stress deviendront auditables dans un langage clinique stable ;
- le replay patient deviendra le principal juge de cohérence produit.

## 9. Impact ML — détail demandé

Le ML doit être **touché**, mais pas remplacé, et surtout pas sur-responsabilisé.

### 9.1 Ce qui ne change pas

- `modelUAM.tflite` garde son rôle de première estimation quantitative ;
- `AimiUamHandler.predictSmbUam()` reste un prédicteur borné et séparé ;
- `AimiSmbTrainer.refine()` reste un **raffinement léger** avec clamp de correction ;
- aucune logique ne doit faire du ML la source principale de compréhension causale.

### 9.2 Ce qui change

- le schéma de features s'enrichit ;
- le CSV d'entraînement ne doit plus être interprété comme un flux homogène ;
- l'entraînement asynchrone doit apprendre sur des contextes plus propres ;
- certains ticks doivent devenir **non apprenants**.

### 9.3 Impact comportemental attendu

**Avant :**

- le ML peut apprendre qu'une journée stressée ou un dawn ressemble à un petit repas si les sorties observées ont été proches ;
- il peut donc raffiner dans un sens correct statistiquement mais faux physiologiquement.

**Après :**

- le ML apprend des familles de comportements mieux séparées ;
- il affine mieux les cas réellement meal-compatibles ;
- il dérive moins après une séquence de journées atypiques ;
- il devient plus stable et plus lisible dans les replays.

### 9.4 Risques ML

- trop filtrer les lignes peut ralentir l'apprentissage ;
- enrichir le schéma sans gouvernance peut casser la comparabilité historique ;
- laisser entrer des labels causalement sales peut produire un raffinement "intelligent mais incohérent".

### 9.5 Règle produit recommandée

Le ML ne doit jamais décider :

- de la cause dominante ;
- de l'activation d'une fausse suppression repas ;
- d'un changement structurel de PKPD ;
- d'une sortie de sécurité protectrice.

Le ML doit seulement :

- raffiner une quantité proposée ;
- dans un contexte causal déjà compris ;
- et sous des bornes explicites.

## 10. Synthèse avant / après observable

| Cas | Avant harmonisation | Après harmonisation |
|-----|---------------------|---------------------|
| Dawn lent proche cible | Risque de lecture partiellement meal-compatible selon les couches | Lecture homogène `DAWN_ENDOGENOUS`, pas de faux repas agressif |
| Petit-déjeuner réel sans COB | Bonne détection possible, mais parfois concurrencée par prudence hormonale | Priorité repas plus nette si signature réellement meal-compatible |
| Rebond post-hypo | Protection déjà meilleure, mais mélange possible avec hyper/rise | Lecture protectrice unique, pas d'apprentissage PKPD/ML sur ce cas |
| Stress + HRV basse + FC haute | Peut freiner la correction, mais avec cinétique encore partiellement mélangée | Résistance transitoire reconnue sans polluer le PKPD structurel |
| Repas gras tardif | Déjà mieux traité par phases repas | Distinction plus nette entre `PROLONGED_MEAL` et hyper isolée |
| Inflammation thermique nocturne | Signal utile mais encore trop indirect pour toute la chaîne | Influence claire sur causalité, protection, PKPD contextuel et replay |
| ML refinement | Raffine utilement, mais peut apprendre des mélanges de contextes | Raffine par contexte propre, plus stable, moins de corrections contre-intuitives |

## 11. Ordre d'exécution recommandé

1. `Lot 1` — posterior causal unifié  
2. `Lot 2` — séparation besoin insulinique / cinétique  
3. `Lot 3` — repas non déclarés gouvernés par causalité  
4. `Lot 4` — RBT arbitre causal  
5. `Lot 5` — gouvernance ML  
6. `Lot 6` — validation et score d'harmonie

Cet ordre est important.

Si le ML ou le RBT sont modifiés avant que la causalité et le PKPD contextuel soient clarifiés, le système risque d'apprendre ou d'optimiser une logique encore hétérogène.

## 12. Conclusion produit

Le produit AIMI ne manque plus de sophistication. Il manque surtout d'une dernière **convergence sémantique**.

La prochaine étape n'est pas d'ajouter un nouveau moteur parallèle.

La prochaine étape est de faire en sorte que :

- la montée observée ;
- la cause probable ;
- l'effet attendu de l'insuline ;
- et l'action délivrée

soient gouvernés par une **même histoire physiologique**, relue à chaque tick, exportée proprement, et assez stable pour que le ML n'apprenne pas les mauvaises leçons.
