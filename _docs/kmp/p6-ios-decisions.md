# Décisions iOS

Approuvées par l’utilisateur le **2026-10-05**. Toutes les recommandations de ce mémo sont retenues.

`IosClientConfig.APS` reste `false`. La boucle iOS n’est pas activée. Aucune écriture pompe.

L’interrupteur nouveau `AimiCommonEngineSwitch` est **éteint par défaut**, et ce n’est pas `IosClientConfig.APS`. Éteint, `HoldAimiEngine` rend `Hold("ENGINE_NOT_EXTRACTED")`, même si un moteur commun lui a été passé. Allumé, en test, il délègue à ce moteur. Les scènes ci-dessous donnent alors les nombres de ce mémo.

Chaque nombre vient d’une trace déjà verrouillée. La recommandation retenue est celle qui n’ajoute pas d’insuline par rapport à cette trace.

## Décision

- COB virtuel : **0 g**.
- Effort : facteur **1,0**, pas d’assessment.
- Runtime patient : **non appelé**.
- Session TPO : **non appelée**.
- Snapshot wearable : **vide**.
- `resetEarlyScratch` : les mêmes affectations que l’adaptateur Android. Sur cette branche l’adaptateur en a **27**. Le texte plus bas disait 29. La décision est cette liste, pas le chiffre arrondi. Le test verrouille 27.
- Veto d’effort : **faux** sans assessment.
- Plancher PKPD : stocké et journalisé, **pas relu** dans le débit.
- Hystérésis : `reset()` au début de chaque tick **iOS**. Le tick Android n’est pas modifié. Il garde le singleton de la ref.

## Non-parité voulue

- Prédiction basse : le TBR reste **0,25 U/h** pendant 30 min. `TREE_DEPLOYED` et `MEAL_CERTAINTY` ne sont pas produits, parce que le runtime patient n’est pas appelé.
- Nuit : le TBR reste **1,00 U/h** pendant 30 min. Les lignes d’apprenants, d’export, `UAM=0.00` et le `SMB result: raw=0.00 -> final=0.35` non délivré ne sont pas produits. TPO n’est pas appelé.
- ISF **45** (FC 110 sur 10 min, moyenne 60 min 88, ISF 50 × 0,90) n’est pas produit. Le snapshot wearable est vide.
- Le `reset()` d’hystérésis est iOS. Un tick Android peut encore hériter du maintien de processus.

Le détail de chaque option reste ci-dessous.

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

Recommandation : **`reset()` au début de chaque tick** le jour où iOS calculera une dose. C’est le seul choix qui garde le TBR **0,25 U/h** même après un tick de repas. Tant que `APS` est faux, le singleton n’est pas écrit. Le code Android reste le singleton de la ref.

Côté iOS : aucun HealthKit. C’est le cycle de vie du processus.
