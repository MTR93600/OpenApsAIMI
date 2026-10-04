# P6 — frontière entre le cœur commun et la coquille Android

La décision clinique reste celle de `dev_OAPSAIMI` @ `3dd0ca647722db854fc844eb344940bd133f8553`. Ce document fixe comment le tick peut bouger vers `commonMain` sans qu’un effet parte du code commun.

`IosClientConfig.APS` reste `false`. `:plugins:aimi-engine` reste `HoldAimiEngine`.

## 1. Principe

Un cœur fonctionnel commun, une coquille Android impérative.

- La coquille lit les préférences, l’horloge, les membres du tick et les collaborateurs (moteur, learners, pompe) **dans l’ordre de la référence**, et construit un instantané immuable.
- Le commun reçoit cet instantané. Il renvoie une décision et une liste ordonnée d’effets. Il n’appelle ni `Preferences`, ni `setTempBasal`, ni un learner, ni l’export.
- La coquille exécute les effets dans l’ordre reçu, avec les mêmes fonctions qu’aujourd’hui (`setTempBasal`, `applySmbUnits`, `preferences.put`, mise à jour de learner).
- Aucun effet ne part du commun. Une lecture conditionnelle non plus : si la référence ne lit une clé que sur une branche, la coquille la lit au même moment. Le commun peut demander la prochaine lecture (`Read`) ; la coquille l’effectue, puis rappelle le commun avec la valeur. L’ordre des lignes `READ` reste celui de la référence.

Les lectures ne sont pas des effets. Elles sont dans la trace, parce qu’un déplacement qui les avance ou les saute change le comportement observable.

## 2. Types d’effets

`AimiTickEffect` (`effects/AimiTickEffect.kt`) :

| effet | ce que la coquille fait |
|---|---|
| `SetTbr` | `setTempBasal(rate, duration, …)` |
| `Smb` | `applySmbUnits(rT, units, owner)` |
| `WritePref` | `preferences.put` |
| `LearnerUpdate` | l’appel de learner déjà présent dans la coquille (observation Ra, marquage) |

La trace golden, une ligne par événement, encodée sans locale (`aimiFmt2` pour les doses) :

```
READ key=<Enum>.<nom> value=<valeur>
WRITE key=<Enum>.<nom> value=<valeur>
EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00
EFFECT Smb units=1.50 owner=LegacyMealModes
EFFECT WritePref key=… value=…
EFFECT LearnerUpdate name=… detail=…
LOG <ligne console existante>
```

`AimiEffectProbe` n’est armé que par les tests. En production la liste est vide : `setTempBasal` exécute son corps, `applySmbUnits` ne fait qu’une vérification nulle. Pendant une capture, le test enregistre la demande de TBR et **n’entre pas** dans le corps de `setTempBasal` (la fonction reste dans la coquille, elle n’est pas déplacée). La ligne `SetTbr` est donc la dose demandée par la décision.

## 3. Ordre du tick dans la référence

`runDetermineBasalTick` délègue à `runDetermineBasalTickInner`. Ordre des étapes dans la référence (fichier `plugins/aps/src/main/kotlin/.../DetermineBasalAIMI2.kt` au commit cité) :

1. `runEarlyDetermineBasalStages`
2. `buildDecisionContextInitRtSosAndFlatShadow`
3. `runRealtimePhysioIobProfilerAndInsulinObserver`
4. `runT9PhysioEarlyPkpdAndTubeBootstrap`
5. `runCombinedDeltaByodaAndDynamicPeak`
6. `buildPreTherapyAutodriveByodaBootstrap`
7. `runTickClockMaxSmbTirCarbAndGlucoseCopy`
8. `runTherapyHydrateClocksAndExerciseLockoutGate`
9. `refreshPostHypoDeliveryAuthorityForTick`
10. `runManualMealModesAfterTherapyGate`
11. `runT3cBrittleBypassOrReturn` — peut terminer le tick
12. `runAimiSnapshotMedicalJsonAndHormonitorExportStage` (premier export)
13. `runSignalPreparationPkpdRuntimePhase`
14. `runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep`
15. `runAdvancedPredictionsAndPredPipePrep`
16. `runPredPipelineSafetyHaltOrReturn`
17. `runMealAdvisorDecisionOrReturn`
18. `runHardBrakeLyraOrReturn`
19. `runPostAutodrivePostHypoClassification`
20. `refreshPostHypoDeliveryAuthorityForTick` (seconde fois)
21. `publishDoseTerminalAuthorityAndSnapshot`
22. lecture `BooleanKey.OApsAIMIautoDriveActive` (journal, pas la branche)
23. `runAutodriveV3MultiVariableBranch`
24. lecture `OApsAIMIautoDriveActive` à nouveau, puis résolution RBT si le drapeau est vrai et que RBT n’a pas encore tourné
25. `applyPendingTrajSpiralBasalIfNotSuppressed`
26. `runPostHypoCompressionAndDriftTerminatorOrReturn`
27. `buildGlobalAimiBasalScheduleBootstrap`
28. `runPostBasalBootstrapIobTickStepsAndHeartRate`
29. `runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf`
30. `applyEndoAndActivityAdjustments`
31. `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity`
32. `applyTrajectoryTightSpiralStandardSmbCapIfNeeded`
33. `runPkpdPredictionsBgiDeviationAndNoisyTargetsStage`
34. `runUamModelCalHypoGuardPostHypoAndSetPredictedSmb`
35. `runSmbDecisionLogAdvisorOneShotAndExecuteInstruction`
36. `applySmbAdvisorExecutionToTickStateAndLog`
37. `runPkpdGuardEndoDampenRedCarpetAndCapSmb`
38. `runMealHyperBasalBoostTickStage` puis `applyMealHyperBasalBoostOverlayIfNeeded`
39. `runWCycleIcCsfClampCiAndCarbImpactLogs`
40. `runCarbsAdvisorEnableSmbSafetyAndHardHypoBasalStopOrReturn`
41. `runPostSafetyMealFirst30NgrHeadroomBasalSmbStage`
42. `runCoreDecisionMaxIobExceededTempBasalGate`
43. `runInsulinReqActivityRelaxAndMicrobolusStage`
44. `runBasalDecisionEngineDecideStage`
45. `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage`
46. `runAimiSnapshotMedicalJsonAndHormonitorExportStage` (second export)

`setTempBasal`, `runDetermineBasalTickInner`, les learners, l’export et `toMedicalJson` restent dans la coquille. Les trois fonctions ci-dessous ne déplacent que la décision.

## 4. Lectures et écritures des trois fonctions

Comparé au même commit de la référence, le corps actuel ne diffère que par des coutures déjà posées : `aimiWallClockMs` à la place de `System.currentTimeMillis`, `aimiFmt*` à la place de `String.format`, `rh.gs` à la place de `context.getString`, `KotlinInstant` à la place de `java.time.Instant`, et un drapeau de diagnostic `profileIsfIsDynamic = true` qui ne change pas l’ancre (`profile.sens`).

`git cherry -v kmp-aimi-migration-study 3dd0ca64772 283a184f60` marque l’historique Autodrive et repas legacy en `+` : ce n’est pas un patch déjà équivalent. `git patch-id --stable` sur le diff de corps `runAutodriveV3MultiVariableBranch` vaut `328a685b0dfe439dd87687cc6fa7e48ef9165624`. Ce lot est une couture, pas un rejeu.

### `runAutodriveV3MultiVariableBranch`

Lectures, dans l’ordre de la référence :

1. `BooleanKey.OApsAIMIautoDriveActive`. Si faux : observation Ra (`autodrive_off`) et retour. Pas de TBR, pas de SMB.
2. `DoubleKey.OApsAIMILastEstimatedCarbs`, puis `DoubleKey.OApsAIMILastEstimatedCarbTime`.
3. Plus loin, seulement si la porte est engagée et qu’une commande sûre a produit un plancher : `DoubleKey.OApsAIMIweight` (poids patient, avant le tick moteur), puis `DoubleKey.OApsAIMIautodrivesmallPrebolus` et `DoubleKey.OApsAIMIautodrivePrebolus`.
4. Si un SMB a été déposé : `BooleanKey.OApsAIMIautoDriveAuthoritative`.

Écriture pompe : un `setTempBasal` de 30 min, limites de sécurité outrepassées, quand la commande V3 est sûre et demande une TBR. Le SMB part par `deliverV3SmbFromRbt`, pas par `applySmbUnits` direct. Les deux restent dans la coquille.

### `buildRbtExtendedSignals`

Lectures, toutes avant l’assemblage, dans cet ordre :

1. `DoubleKey.OApsAIMIT3cAnticipationStrength`
2. `BooleanKey.OApsAIMIT3cBrittleMode`
3. `DoubleKey.OApsAIMILastEstimatedCarbs`
4. `DoubleKey.OApsAIMILastEstimatedCarbTime`
5. `DoubleKey.OApsAIMIT3cActivationThreshold`
6. `DoubleKey.OApsAIMISmbTailDamping`
7. `StringKey.AimiTuningContextSelection`

Écritures d’état du tick (pas des préférences) : `lastPostHypoOrdinal`, `lastNgrBasalMultiplier`. Pas de `setTempBasal`. La classification post-hypo (UAM compris) est appelée ici ; la confiance UAM reste une lambda lue seulement dans la branche de récupération, comme en tranche 5.

### `applyLegacyMealModes`

1. Lecture paresseuse du pending : `AimiLongKey.PendingLegacyPrebolusUnitMilli`, et l’échéance seulement si un pending est en vol.
2. Sur la branche prise, `setTempBasal` (30 min, `forceExact`) **puis** la lecture du prébolus de cette phase (`OApsAIMIMealPrebolus`, `BF`, `Lunch`, `Dinner`, `HighCarb`, `Snack`, ou `OApsAIMIautodrivePrebolus` pour FCL).
3. SMB via `applySmbUnits`, ou mise à zéro sans SMB si le verrou, l’IOB (`iob > maxIob`), l’hypo sévère avec autorité post-hypo, ou `hasHypoRecovery` bloque.
4. Si des unités partent : écritures `LastPrebolusTime`, `PendingLegacyPrebolusUnitMilli`, `PendingLegacyPrebolusExpiry`, `LastLegacyPrebolusTime`.

L’ordre TBR puis lecture du prébolus est celui de la référence. Le commun ne doit pas lire le prébolus avant d’avoir émis le `SetTbr`.

## 5. `runCatching` sur l’instantané FC

Dans `executeT3cBrittleMode`, la référence et le tick actuel faisaient :

```kotlin
runCatching { physioAdapter.getLatestSnapshot(); /* bandes FC */ }.getOrDefault(0.0)
```

Tout throwable, y compris `Error`, devenait un boost 0.0 sans log. La fonction n’est pas déplacée. Le calcul des bandes est `cfrdHrInflammationBoostOf` (pur, testé). La coquille lit l’instantané dans un `try/catch (Exception)`, journalise `T3c CFRD: hr snapshot failed`, et utilise 0.0. La dose sur échec reste 0.0. Un `Error` n’est plus avalé : c’est l’écart documenté avec la référence.

Bandes, inchangées : hausse corrigée du repos ≥ 25 bpm → 0.35, 15..24 → 0.20, 8..14 → 0.10, sinon 0. FC absente (l’une des deux ≤ 0) → 0.

## 6. Ce qui ne bouge pas dans ce lot

`setTempBasal`, `runDetermineBasalTickInner`, les learners, l’export, `toMedicalJson`. Les deux sites d’horloge de la tranche 4 non plus.

Les traces golden de ce lot couvrent, sur le code Android actuel : mode repas (TBR puis prébolus), récupération d’hypo, hypo sévère avec autorité post-hypo, plafond MaxIOB, et Autodrive éteint (une lecture, aucune dose). La montée de repas engagée et le chemin UAM de `buildRbtExtendedSignals` dépendent du moteur, du learner et de la persistance. Leurs traces sont prises sur le corps actuel au moment où ces fonctions sont ouvertes, avant de remplacer la décision, avec le même encodeur. Si une trace diverge, on s’arrête.
