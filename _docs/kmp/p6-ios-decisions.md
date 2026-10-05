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

2. **Wearable, FC, et persistance des pas, de la FC et des bolus.** HealthKit remplit le même `HealthContextSnapshot` (pas 5/15/60 min, FC, FC de repos, fenêtres 10 et 60 min). La persistance est commune, SQLDelight ou équivalent, avec les mêmes lectures que `persistenceLayer` (`getHeartRatesFromTimeToTime`, pas, bolus). Les caches `stepsSnapshotRef`, `heartRatesSnapshotRef` et le cache bolus partent des mêmes listes. Taille : un adaptateur HealthKit dans `iosMain`, un schéma et les requêtes, branchés à la place des listes vides. Ça débloque l’ISF, l’effort, le COB et le veto. Trace : `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf`, FC 110 / 10 min, moyenne 60 min 88, ISF 50 × 0,90 = **45**, ligne `HR_TREND_ISF x0.90`.

3. **Effort.** Une fois le snapshot réel, appeler `refreshEffortActivityBelief` tel quel (34 lignes). Facteur ≤ 1, assessment null si la protection est coupée. Taille : le corps Android derrière le snapshot, pas une seconde formule. Trace : `signalMealReturnsTheAdvisorSmbAndTbr`, SMB **3,30 U** et TBR **2,00 U/h**, sans exertion.

4. **Veto.** La même règle qu’Android : EXERTION, ACTIVE ou RECENT_EFFORT, confiance ≥ 0,30, pas de repas déclaré, COB < 12 g. `decideDetectMealOnset` reçoit ce booléen. Taille : déplacer `effortSuppressesUndeclaredMeal` en commun, une fois l’assessment porté. Trace : `basalDecisionEngineRaisesSportTemp`, BG 180, delta +5, accélération 0, veto faux, onset faux, TBR **1,30 U/h**.

5. **COB virtuel.** La même préférence `OApsAIMIUndeclaredCobEnabled`, le même estimateur de Ra, le poids et le TDD. Glucides déjà déclarés : 0 g virtuel. Taille : les 38 lignes et l’estimateur, après HealthKit. Trace : `advancedPredictionPublishesEventualFromDeclaredCob`, COB déclaré **36 g**, glycémie 180, eventual **322**.

6. **Runtime patient.** Appeler les 221 lignes : état patient, arbre, Harmonia, avec le snapshot du tick. Taille : le plus gros port physio, après le snapshot. Il dépend du capteur et du cycle. Trace : `lowPredictionRequestsAQuarterBasal`, `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN`, `MEAL_CERTAINTY level=NONE`, TBR **0,25 U/h**, octet pour octet.

7. **Plancher PKPD.** Le débit Android vient des courbes, pas d’une relecture du JSON. La parité est la même ligne `PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false` dans le tick, et le même champ d’export. Taille : placer `decideRecordPkpdSoftFloor` au même endroit que la coquille Android. Pas de seconde formule. Trace : `lowPredictionRequestsAQuarterBasal`.

8. **TPO.** `onTickStart` avec la même horloge, un stockage de session commun, et le même reversement de préférences. Taille : orchestrateur et stockage, après l’horloge déjà portée. Les scènes sans session sont verrouillées. Une scène avec session active n’a pas encore de nombre verrouillé : il faudra la verrouiller sur Android avant de l’exiger sur iOS. Traces sans session : `zzPostHypoAtFiveSkipsTheDriftMicroSmb`, TBR **1,00 U/h** ; `basalDecisionEngineRaisesSportTemp`, TBR **1,30 U/h**.

9. **Learners.** `BasalLearner`, `BasalNeuralLearner` et `UnifiedReactivityLearner` sont déjà en `commonMain`. Il manque l’état persisté identique (`aimi_basal_learner.json`, état du réseau, CSV), pas une autre politique. Départ à froid : multiplicateurs **1,0**, gouvernance `WARMUP`. `KEEP` seulement après les mêmes échantillons. Taille : le stockage de ces fichiers dans la persistance commune, puis les appels `process` au même endroit du tick (616 lignes côté Android, dont l’appel). Trace : `zzPostHypoAtFiveSkipsTheDriftMicroSmb`, TBR **1,00 U/h**, avec les lignes d’apprenants du tick de nuit.

10. **TFLite et UAM.** Le même fichier `modelUAM.tflite`. Interpréteur TFLite iOS, ou une inférence en Kotlin commun qui lit ce fichier. Modèle absent : `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement. Modèle présent : le même SMB que Android pour le même vecteur. Taille : l’interpréteur et le branchement de `AimiModelHandler`, sans réécrire `neuralnetwork5`. Traces : `uamPostHypoReboundBridgesAShortTempBasal`, SMB prédit **0 U**, TBR **1,05 U/h**, 5 min, sur le chemin modèle absent ; puis le même vecteur avec le fichier présent, SMB identique à Android.

11. **`AimiDecisionContext`.** Le type est encore dans `DetermineBasalAIMI2.kt` (fabrique 43 lignes). Le porter en commun, avec les champs que l’orchestre et l’export lisent. Taille : déplacer la data class et la fabrique, sans changer les champs. Trace : l’export du tick `lowPredictionRequestsAQuarterBasal`, champ `pkpd_soft_floor` compris, octet pour octet.

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

Recommandation : **ne pas appeler** tant que l’arbre iOS n’est pas décidé. Le TBR **0,25 U/h** reste celui du tick Android déjà verrouillé, pas un second calcul.

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
