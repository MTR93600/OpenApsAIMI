# P6 — frontière entre le cœur commun et la coquille Android

La décision clinique reste celle de `dev_OAPSAIMI` @ `3dd0ca647722db854fc844eb344940bd133f8553`. Ce document fixe comment le tick peut bouger vers `commonMain` sans qu’un effet parte du code commun.

`IosClientConfig.APS` reste `false`. `:plugins:aimi-engine` reste `HoldAimiEngine`.

## 1. Principe

Un cœur fonctionnel commun, une coquille Android impérative. L’instantané unique pré-lu est abandonné : il avancerait les lectures conditionnelles et casserait les traces.

Le commun n’a pas d’instantané de préférences. Il appelle des ports injectés **au moment exact** où la référence lit ou appelle. Les implémentations restent en `androidMain` et délèguent au code actuel. Rien de ces implémentations ne passe en `commonMain`. Il n’y a pas d’implémentation iOS de l’effect sink : `IosClientConfig.APS` reste `false`.

Les ports sont minces et portent le nom de l’appel de la référence. Pas de bus générique. On n’introduit un port que lorsque la fonction portée fait réellement l’appel.

| port | ce que la référence appelle | qui l’implémente |
|---|---|---|
| `Preferences` | `get` / `put` | le stockage déjà injecté |
| `AimiEffectSink` | `setTempBasal`, `applySmbUnits` | la coquille, qui délègue aux fonctions actuelles |
| `AimiSmbActionType` | `setSmbActionType`, `finalLoopDecisionType`, `setFinalLoopDecisionType` | `physioAdapter` |
| `AimiLatestSmbCached` | `latestSmbCached` | le cache SMB de la coquille |
| `AimiRecentGlucose` | `getRecentGlucose` | le calculateur de glycémie |
| `AimiPostHypoClassification` | `classifyPostHypoState` | la coquille, qui met à jour `lastHypoBelow70At` |
| `AimiNightGrowthConfig` | `buildNightGrowthResistanceConfig` | la coquille, lectures nocturnes et learner compris |
| `AimiPhysioTick` | `getLastDecisionTrace`, `getEffectiveContext`, `getLatestSnapshot` | `physioAdapter` |
| `AimiRbtTickWrites` | `lastPostHypoOrdinal`, `lastNgrBasalMultiplier` | les champs du tick |
| `AimiAutodriveGater` | `shouldEngageV3` | `autodriveGater`, lu seulement à l'appel |
| `AimiEstimatedRa` | `getLastRa` | `continuousStateEstimator`, lu seulement à l'appel |
| `AimiAutodriveDebug` | `aapsLogger.debug` | le journal, seulement dans la branche engagée |
| `AimiUamConfidence` | `confidenceOrZero` | `AimiUamHandler` |
| `AimiFclDeclared` | `fclDeclaredThisTick` | la coquille |
| `AimiMinBgLookback` | `minBgInLastMinutes` | la coquille |
| `AimiPostHypoRecovery` | `postHypoRecoveryActive` | la coquille |
| `AimiTdd24h` | `resolveTdd24hForExport` | la coquille, l'export n'est pas déplacé |
| `AimiRaObservation` | `observeRaIfNotAlreadyRun` | la coquille |
| `AimiPhysiologicalPhase` | `refreshPhysiologicalPhase` | la coquille |
| `AimiMealSafety` | `buildMealSafetyContext` | la coquille |
| `AimiMealAbsorption` | `refreshMealAbsorptionPhase` | la coquille |
| `AimiPhysioLatentUpdate` | `updatePhysioLatentState` | la coquille |
| `AimiHyperSeverity` | `classifyHyperSeverityForTick` | la coquille |
| `AimiHtrTerminals` | `resolveHtrScenarioTerminals` | la coquille |
| `AimiBasalCap` | `capBasalRateForCorrectionAggression` | la coquille |
| `AimiAggressiveRiseFloor` | `aggressiveRiseSmbFloorU` | la coquille |
| `AimiRiseFloorNote` | `noteRiseFloorContribution` | la coquille |
| `AimiPatientStateRefresh` | `refreshPatientStateRuntime` | la coquille |
| `AimiDoseTerminal` | `publishDoseTerminalAuthorityAndSnapshot` | la coquille |
| `AimiRbtLive` | `resolveAndWireRbtLiveTick` | la coquille |
| `AimiV3SmbDelivery` | `deliverV3SmbFromRbt` | la coquille |
| `AimiHtrExport` | `markHtrRaFloorForExport` | la coquille, l'export n'est pas déplacé |
| `AimiDecisionLog` | `logDecisionFinal` | la coquille, les learners qu'il appelle restent |
| `AimiAutodriveTickWrites` | deltas Ra, note de porte, état engagé, plancher HTR, trace SMB, caps post-hypo | les champs du tick |

Les membres du tick déjà calculés (glycémie, IOB, drapeaux de mode) sont passés à la fonction. Ce ne sont pas des lectures de préférences. Une préférence lue seulement sur une branche l’est encore seulement sur cette branche, à la même ligne.

Les lectures ne sont pas des effets. Elles sont dans la trace, parce qu’un déplacement qui les avance ou les saute change le comportement observable.

## 2. Types d’effets

Le commun n’empile pas une liste que la coquille rejouerait. Il appelle `AimiEffectSink` sur place. `AimiTickEffect` (`effects/AimiTickEffect.kt`) reste le format de la trace :

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

Lectures de tête, dans l’ordre de la référence :

1. `BooleanKey.OApsAIMIautoDriveActive`. Si faux : observation Ra (`autodrive_off`) et retour. Pas de TBR, pas de SMB.
2. `DoubleKey.OApsAIMILastEstimatedCarbs`, puis `DoubleKey.OApsAIMILastEstimatedCarbTime`.
3. Si la porte est engagée : le poids `DoubleKey.OApsAIMIweight` est lu avant le tick moteur. Après une commande sûre, le plancher agressif lit `BooleanKey.OApsAIMIautodriveAggressiveSmbFloor`, puis la trace de liaison lit `OApsAIMIautodrivesmallPrebolus` et `OApsAIMIautodrivePrebolus` même si le plancher vaut 0.
4. Si un SMB a été déposé : `BooleanKey.OApsAIMIautoDriveAuthoritative`.

La porte engagée lit aussi, au passage, les préférences HTR, le haut de glycémie, le contexte d’accord, et les cinq clés RBT. L’ordre complet est celui de `ShellDecisionTraceTest`, pas une liste raccourcie.

Écriture pompe : un `setTempBasal` de 30 min, limites de sécurité outrepassées, quand la commande V3 est sûre et demande une TBR. Le SMB part par `deliverV3SmbFromRbt`, et seulement si une libération HTR existe. RBT éteint et pas d’HTR préalable : la commande SMB du moteur n’est pas déposée. Les deux écritures restent dans la coquille.

La décision est dans `commonMain`. Chaque appel privé de la coquille est un port du même nom, invoqué à la même ligne. `shouldEngageV3`, `getLastRa` et `aapsLogger.debug` ne sont pas lus avant le test de `OApsAIMIautoDriveActive` : le chemin éteint ne touche pas ces `lateinit`. Le sink transmet `mealContext` à `setTempBasal` ; le corps de `setTempBasal` n’est pas modifié. `logDecisionFinal` reste dans la coquille, learners compris.

Porte fermée (préférence vraie, pas de mode repas, BG 110, delta 0,2, gater réel) : pas d’`EFFECT`. Lectures `OApsAIMIautoDriveActive`, les deux clés d’estimation de glucides, puis `OApsAIMIweight` dans `observeRaIfNotAlreadyRun` (`gate_disengaged`, `Ra=0.40`). Le compteur d’estimation du test vaut `-1`, comme `raEstimatorRunCountAtTickStart`, donc l’observation part.

Hypo plate (BG 54, delta 0, pas de mode repas) : la porte réelle reste fermée (`RISE_TOO_WEAK`). Le chemin désengagé ne journalise pas la glycémie, donc les octets sont ceux de la porte fermée. Aucune dose n’est inventée. Une hypo engagée demanderait un contexte repas (delta > 0,25) et une commande moteur choisie : ce n’est pas ce scénario.

Plafond d’activité : même montée de repas, `exerciseInsulinLockoutActive`, facteur `OApsAIMIActivityBasalCapFactor` 1,30 (le défaut de la clé), basal de profil 1,00 U/h, commande moteur 2,40 U/h. `capBasalRateForCorrectionAggression` coupe à 1,30. `EFFECT SetTbr rate=1.30`. Le journal `tbr=2.4` reste le débit demandé par le moteur, pas le débit plafonné : c’est le texte actuel, pas une correction.

Trace verrouillée (mode repas, BG 160, delta 3, commande moteur sûre 2,40 U/h et 0,80 U) : `EFFECT SetTbr rate=2.40 dur=30 override=true forceExact=false adaptive=1.00`, puis le journal `actual=0.0`. Pendant la capture, la sonde retourne avant le corps de `setTempBasal`, donc `DECISION_FINAL` voit encore `tbr=0.00`. C’est le contrat de la sonde, pas la dose demandée. La ligne `TICK` contient `aimiWallClockMs()` ; le test remplace `ts=<chiffres>` par `ts=<clock>`. Le reste de la ligne est octet pour octet.

### `buildRbtExtendedSignals`

Lectures directes, dans cet ordre :

1. `DoubleKey.OApsAIMIT3cAnticipationStrength`
2. `BooleanKey.OApsAIMIT3cBrittleMode`
3. `DoubleKey.OApsAIMILastEstimatedCarbs`
4. `DoubleKey.OApsAIMILastEstimatedCarbTime`
5. via `buildNightGrowthResistanceConfig` : âge, `getIfExists` de `OApsAIMINightGrowthEnabled`, début, fin, IOB extra. `t3cModeEnabled()` ne relit pas BrittleMode quand l’âge est sous 18 et que le drapeau nocturne est absent.
6. `BooleanKey.AimiEndometriosisEnable` dans `endoAdjuster.calculateFactors`
7. `DoubleKey.OApsAIMIT3cActivationThreshold` — seulement si le mode brittle est vrai
8. `DoubleKey.OApsAIMISmbTailDamping`
9. `StringKey.AimiTuningContextSelection`

Écritures d’état du tick (pas des préférences) : `lastPostHypoOrdinal`, `lastNgrBasalMultiplier`, à la ligne de l’affectation. Pas de `setTempBasal`. La classification post-hypo appelle la confiance UAM seulement dans la fenêtre de récupération. La trace UAM (BG récents avec un point sous 70, confiance UAM 0,70, brittle éteint) donne `postHypoOrdinal=2` et ne lit pas le seuil d’activation T3c.

La décision est dans `commonMain`. Les appels qui restent dans la coquille passent par des ports du même nom : `getRecentGlucose`, `classifyPostHypoState`, `buildNightGrowthResistanceConfig`, `getLastDecisionTrace`, `getEffectiveContext`, `getLatestSnapshot`. Les learners, l’ajusteur endo et `AuditorVerdictCache` sont déjà communs : le commun les appelle, il ne les déplace pas. `pkpdIntegration.reconstructedIobUnits()` est appelé même quand le runtime PKPD est nul, puis filtré, comme la référence.

Cette fonction n’appelle pas la porte Autodrive. Porte ouverte et porte fermée se verrouillent avec `runAutodriveV3MultiVariableBranch`.

### Lectures optionnelles endométriose et cache auditeur

La référence (`dev_OAPSAIMI` @ `3dd0ca64772`, `DetermineBasalAIMI2.kt` L4321 et L4329) avale déjà l’erreur :

```kotlin
val endoFactors = try {
    endoAdjuster.calculateFactors(bg, delta.toDouble())
} catch (_: Exception) {
    null
}
val auditorVerdict = try {
    AuditorVerdictCache.get(300_000)?.verdict
} catch (_: Exception) {
    null
}
```

Le repli de valeur reste `null`. `endometriosisFactor`, `shadowAuditorConfidence` et `shadowSentinelVerdictLabel` sont donc les mêmes qu’avant sur le chemin qui réussit, et absents quand la lecture échoue. L’écart avec la référence : l’échec n’est plus silencieux. `readRbtOptional` renvoie `OptionalSignal.Failed` (source, type, message) et ajoute à `consoleLog` la ligne `RBT <source> failed (<type>): <message> — value null`. Un `Error` n’est pas attrapé, comme dans la référence. Un cache auditeur vide (`get` renvoie null) reste `Ready(null)`, pas un échec.

`RbtOptionalReadTest`, bouchon qui renvoyait encore `Ready` sans ligne de log : XML `tests="5" failures="2"`, horodatage `2026-10-04T18:57:45.129Z`. Après le résultat typé et le log : `tests="5" failures="0"`, horodatage `2026-10-04T18:58:17.274Z`. Les traces verrouillées ne lancent pas ces lectures en échec, donc leurs octets ne bougent pas.

Trace hypo brittle : BG 50, brittle vrai, seuil 140. Le seuil est lu, `t3cActive=true`, `t3cDemand=0.00`.

Trace plafond : BG 220, delta 8, moyenne courte 4, eventual 220, basal courante 1,00 U/h, max basal 1,20 U/h. `t3cDemand=1.20`. Le même scénario avec un max basal de 30 U/h donne 16,12 U/h : 1,20 est le clamp de `computeT3c`, pas une dose inventée. Un eventual à 0 faisait croire que le plafond était inatteignable, parce que le frein de trajectoire lit cet eventual comme une hypo (garde à 40 mg/dL) et coupe la demande avant le clamp.

Le libellé de blocage Harmonia passe par `uppercase()` sans locale. Les identifiants (`sensor_uncertain`, `critical_risk`, …) sont ASCII : le résultat est le même qu’avec `Locale.US`, qui n’existe pas en `commonMain`.

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

Tout throwable, y compris `Error`, devenait un boost 0.0 sans log. Le calcul des bandes est `cfrdHrInflammationBoostOf` (pur, testé). `decideT3cBrittleMode` lit l’instantané seulement si le mode CFRD est vrai, via `readRbtOptional`. L’échec est `OptionalSignal.Failed` et la ligne déjà en place `🫁 T3c CFRD: hr snapshot failed (<type>) — boost 0.00`. Le boost reste 0.0. Un `Error` n’est pas attrapé, comme dans la référence.

Bandes, inchangées : hausse corrigée du repos ≥ 25 bpm → 0.35, 15..24 → 0.20, 8..14 → 0.10, sinon 0. FC absente (l’une des deux ≤ 0) → 0.

## 6. Ce qui ne bouge pas dans ce lot

`setTempBasal`, `runDetermineBasalTickInner`, les learners, l’export, `toMedicalJson`. Les deux sites d’horloge de la tranche 4 non plus. Le corps de `setTempBasal` n’est pas modifié : le sink l’appelle.

`applyLegacyMealModes` décide dans `commonMain` et appelle les ports du repas. Cette fonction ne consulte pas la porte Autodrive. Les traces hypo (récupération et hypo sévère) et le plafond MaxIOB sont déjà verrouillées.

`buildRbtExtendedSignals` décide dans `commonMain`. Hypo brittle (`t3cDemand=0.00`) et plafond (`t3cDemand=1.20` sous un max basal de 1,20 U/h, 16,12 U/h sans ce plafond) sont verrouillées.

`runAutodriveV3MultiVariableBranch` décide dans `commonMain`. Porte ouverte (TBR 2,40), porte fermée, hypo plate (mêmes octets que la porte fermée, pas de dose), hypo engagée avec repas et commande moteur, et plafond d’activité (TBR 1,30 sous un facteur 1,30) sont verrouillées. Le SMB moteur 0,80 U n’est toujours pas déposé tant que RBT est éteint.

Hypo engagée : BG 54, `mealTime`, delta 0,4, commande moteur sûre 2,40 U/h et 0,80 U. La porte s’ouvre (`Meal-aware rise`, delta > 0,25). `EFFECT SetTbr rate=2.40`. `appliedAction` reste faux : la sonde retourne avant le corps de `setTempBasal`, donc `rT.rate` n’est pas écrit. Le `Trend=` de la ligne d’engagement imprime le `Float` 0,4 tel que Kotlin le convertit (`0.4000000059604645`). C’est le texte actuel. La dose demandée n’a pas été changée.

`executeT3cBrittleMode` décide dans `commonMain` (`decideT3cBrittleMode`). La coquille passe l’arbre, le facteur d’effort, le multiplicateur NGR, et appelle au même moment le facteur adaptatif (learner + `currentBasalPhysioFeatures`), l’instantané FC, `postHypoRecoveryActive`, `minBgInLastMinutes`, puis l’apprentissage basal et `markFinalLoopDecisionFromRT`. Trace brittle actif : BG 180, seuil 140, basal profil 1,00, max 3,00. Le stub du learner renvoie 0, donc l’agressivité est le plancher 0,3. Débit 2,00 U/h, durée 30, `units` inchangé (null). Pas de SMB.

`tryMealAdvisor` et `runMealAdvisorDecisionOrReturn` décident dans `commonMain`. La coquille appelle `setTempBasal` (`forceExact` faux, comme l’argument omis d’origine), `applySmbUnits`, puis l’écriture `internalLastSmbMillis` (qui pose `LastPrebolusTime`), `logDecisionFinal` et `markFinalLoopDecisionFromRT`. Trace : 40 g, IC 10, IOB 1,00, max basal repas 2,00. SMB 3,30 U, TBR 2,00 U/h sur 30 min. L’heure d’estimation des glucides est l’horloge murale ; le test remplace cette valeur et `ts=` par `<clock>`. Le `ts=` de `units=3.30` n’est pas touché.

`finalizeAndCapSMB` décide dans `commonMain` (`decideFinalizeAndCapSmb`). `applySafetyPrecautions`, `calculateSMBInterval`, `resolveTdd24hForLoop`, l’observateur d’insuline et les lectures d’autorité de dose restent des ports appelés à la ligne d’origine. Le `try/catch` du cache auditeur est le même écart que #159 : confiance null si la lecture échoue, plus `OptionalSignal.Failed` et une ligne de log. La trace verrouillée ne lance pas ce chemin. Proposition 3,00 U, maxSMB 0,50, pas de courbes : le filet coupe à 0,50, l’absence de prédiction coupe à `maxSMB * 0,5` = 0,25, le throttle PKPD 0,60 laisse **0,15 U**. `WRITE LastPrebolusTime` reste avant les lignes `GATE_` et `SMB_CAP`.

`runRecursiveBeliefResolve` décide dans `commonMain` (`decideRecursiveBeliefResolve`). Les lectures de champs (`lastScenarioProjection`, `lastMealAbsorptionOutput`, `maxSMB`, le mode patient après `updatePhysioLatentState`) sont des ports appelés à la ligne. `buildRbtExtendedSignals`, `ensureWCycleInfo`, `dwellAboveHighBgMinutes` et `updatePhysioLatentState` restent des appels Android. Le `try/catch` de `contextManager.getSnapshot` garde le repli null. L’échec est `OptionalSignal.Failed` et la ligne `RBT contextSnapshot failed (<type>): <message> — value null`. La préférence contexte est fausse dans la trace verrouillée, donc cet appel n’est pas fait. Résolution plate : SMB 0,00, fraction TBR 1,00, attente 0,15, autorité `NONE`, garde hypo `FULL`, raisons `P2_SOFT`, `HARMONIA_SMB_ACCEPT`, `OFF_ASLEEP_LIVE`.

`resolveMealHyperBasalBoostOutcome` décide dans `commonMain` (`decideMealHyperBasalBoost`). Les branches qui posent un TBR de 30 min appellent `setTempBasal` par un port, arguments inchangés (`overrideSafetyLimits` vrai, multiplicateur 1,0). `calculateRate` écrit la même raison sur `rT`. Jeûne, delta +2, basal profil 1,00 U/h : débit **2,00 U/h**, raison `0m@1.00 AI Force basal because fastingTime`, une lecture `meal_modes_MaxBasal`.

`runTickClockMaxSmbTirCarbAndGlucoseCopy` décide dans `commonMain` (`decideTickClockMaxSmb`). L’échelle MaxSMB, le copiage des deltas et les lectures TIR/glucides restent dans l’ordre. Le `try/catch` qui confirme un prébolus legacy garde le repli faux. L’échec est `OptionalSignal.Failed` et la ligne `RBT legacyPrebolusDelivered failed (<type>): <message> — value null`. La scène verrouillée n’a pas de prébolus en attente. BG 110 plat, maxSMB 0,50, plafond haut 1,20 : branche `MAXSMB_STANDARD`, plafond **0,50 U**.

`runT9PhysioEarlyPkpdAndTubeBootstrap` décide dans `commonMain` (`decideT9PhysioEarlyPkpd`). Le calcul PKPD, les prédictions et l’ajusteur d’inflammation restent des ports. Le `try/catch` du nom de classe G6 garde le repli faux. L’échec est `RBT g6Sensor failed (<type>): <message> — value null`. L’erreur de l’adaptateur physio et l’échec du runtime PKPD gardent leurs lignes déjà écrites (`aapsLogger`, `consoleError`). BG 110, delta 0, assistant physio éteint, PKPD éteint : prédiction **110 mg/dL**, multiplicateurs neutres.

`runPkpdGuardEndoDampenRedCarpetAndCapSmb` décide dans `commonMain` (`decidePkpdGuardEndoDampenRedCarpetAndCapSmb`). `applyPkpdAbsorptionGuardOncePerTick`, `resolveMealCorrectionContext`, `authoritativeEventualBg`, `authoritativeMinPredBg` et `minPredictedBgForRbtWiring` restent des ports appelés à la ligne. Le relief MaxIOB et le tapis rouge restent `AimiLegacySmbCapMath`. Cette tête n’a pas de `try/catch` avalé. SMB 0, runtime PKPD null, relief éteint, BG 110 : SMB **0**, intervalle 4, raison vide, étapes de trace `PKPD_GUARD` puis `LEGACY_RED_CARPET_MAX_SMB_IOB`.

Les traces golden restent, octet pour octet : mode repas (TBR puis prébolus), récupération d’hypo, hypo sévère avec autorité post-hypo, plafond MaxIOB, Autodrive éteint, montée de repas engagée (`ShellDecisionTraceTest`, TBR 2,40 U/h demandée, SMB moteur non déposé tant que RBT est éteint), et le chemin UAM de `buildRbtExtendedSignals` (ordinal post-hypo 2, confiance 0,70). Si une trace diverge, on s’arrête.
