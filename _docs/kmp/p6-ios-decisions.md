# Décisions iOS

Objectif final : parité stricte Android/iOS. Les valeurs neutres sont un échafaudage temporaire, derrière l'interrupteur éteint, et doivent toutes disparaître avant activation.

Approuvées par l’utilisateur le **2026-10-05** comme étape temporaire, pas comme état final. `IosClientConfig.APS` reste `false`. La boucle iOS n’est pas activée. Aucune écriture pompe.

`AimiCommonEngineSwitch` est **allumé par défaut depuis le 2026-10-09** (décision projet, option B). La règle d'origine (allumer uniquement après parité byte-for-byte) n'a pas été suivie. Allumé, il délègue au moteur commun si un delegate est fourni, sinon `HoldAimiEngine` rend `Hold("ENGINE_NOT_EXTRACTED")`. En pratique : aucun shell de production ne fournit encore de delegate, donc le switch n'a aucun effet runtime. Les scènes ci-dessous donnent les nombres temporaires.

⚠️ **Écart documenté** : l'activation a précédé les traces de parité, contrairement à la règle d'activation ci-dessous. Les traces restent dues.

## Échafaudage temporaire

Ces valeurs ne sont pas la parité. Elles disparaissent avant activation.

- COB virtuel : la fonction Android. Préférence coupée, ou glucides déjà déclarés : **0 g**, sans ligne, sans courbe. Préférence allumée : la ligne `VIRTUAL_COB`, puis la même courbe. **9,0 g** donne eventual **198**. La porte `hr_inflammation` donne **0 g** et eventual **170**.
- Effort : facteur **1,0**, pas d’assessment.
- Runtime patient : appelé sur la prédiction basse, après le plancher PKPD, et sur repas, sport et nuit. Repas et sport : `rise=OK`. Nuit : `rise=WEAK`, `terminals=UNKNOWN`. Plus de `patientRuntime=skipped`.
- Session TPO : appelée. Sans session, nuit **1,00 U/h** et sport **1,30 U/h**. Session active : plafond SMB **0,80 U**, requête d’activité **0,20 U**.
- Snapshot wearable : **vide**.
- `resetEarlyScratch` : les mêmes 27 affectations que l’adaptateur Android, dans le même ordre. Le compte 29 était faux. Le test verrouille 27. Rien à ajouter.
- Veto d’effort : **faux** sans assessment.
- Plancher PKPD : stocké et journalisé, **pas relu** dans le débit. Android non plus ne relit pas ce JSON pour doser. La parité est la même ligne de journal, au même endroit du tick.
- Hystérésis : par défaut, le même cycle de vie qu’Android. Les singletons de processus ne sont pas remis à zéro au début du tick. `iosNeutralResetHysteresisForTest` est une option de test. `evaluate` ne l’appelle pas.

## Écarts temporaires

- Prédiction basse : le TBR temporaire est **0,25 U/h** pendant 30 min, avec les lignes Android `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE` et `MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false`. Repas (160 mg/dL, delta +2) et sport (180, delta +5) : `rise=OK`, `terminals=UNKNOWN`. Nuit (180, delta 0) : `rise=WEAK`, `terminals=UNKNOWN`, la même famille que `PHYSIO_LATENT_SMB_CEILING_TRACE`.
- Nuit : le TBR temporaire est **1,00 U/h** pendant 30 min, avec les lignes d’apprenants d’un dépôt vide (`BasalLearner: multiplier=1.000`, `UnifiedReactivity: factor=1.000`, `BASAL_GOV[FINAL]` `action=WARMUP` `reason=Warmup`). Pas encore l’export, `UAM=0.00`, ni le SMB `final=0.35` non délivré.
- ISF **45** (FC 110 sur 10 min, moyenne 60 min 88, ISF 50 × 0,90) est produit quand le port de session rend les quatre échantillons. Le tick par défaut, interrupteurs éteints, garde le snapshot vide et ne produit pas cette ligne.
- Un maintien d’hystérésis laissé par le tick précédent reste en place, comme sur Android.

## Chemin vers la parité

Ordre : d’abord ce qui change un débit ou le tick suivant, ensuite le modèle et l’export. La taille est le sous-système à écrire, pas un calendrier. Chaque ligne se prouve par une trace déjà verrouillée sur Android, rejouée octet pour octet sur iOS.

## État de parité

`fait` : la trace verrouillée est la même des deux côtés. `partiel` : une partie du chemin l’est, le reste non. `bloqué` : une décision interdit d’avancer. Les PR sont des brouillons, sauf #213 et #217, déjà mergées dans l’étude.

| Point | Statut | PR | Encore manquant pour l’égalité octet pour octet |
|---|---|---|---|
| Hystérésis | fait | #215, #218 | Rien. Le défaut iOS n’appelle pas `reset()`. Le maintien traverse le tick, comme le singleton Android. |
| Wearable, FC, HealthKit | fait | #224, #236 | Rien sur la trace ISF **45**, `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`. La session demande pas 5/15/60, FC sur 60 min, FC de repos sur 24 h, derrière `HealthKitWindowPort`, et écrit le même `HealthContextSnapshot`. Tant que `IosClientConfig.APS` ou l’interrupteur moteur est éteint, aucune demande de droit et aucune requête HealthKit : le tick garde le snapshot vide et `IOS_NEUTRAL wearable snapshot empty`. Les entitlements sont documentés et non activés. |
| Lectures pas, FC, bolus | fait | #223, #227 | Rien sur la trace ISF **45**, `HR_TREND_ISF x0.90`. La session HealthKit fournit les mêmes quatre échantillons au même calcul. |
| Effort | fait | #222 | Rien sur le repas : facteur **1,0**, assessment null, SMB **3,30 U**, TBR **2,00 U/h**. Une exertion réelle qui baisserait le SMB n’a pas de nombre verrouillé. |
| Veto et `detectMealOnset` | fait | #214, #220, #231, #235 | Rien sur les deux débits. Accélération 2, pas d’assessment : onset vrai, TBR **2,00 U/h**, `phrase [AD_EARLY_TBR_TRIGGER rate=2.0]`. Le tick iOS appelle `decideBasalDecisionEngine` avec `forcedBasal` **2,0**, modes et autodrive. Même cinématique, posture EXERTION, confiance 0,30, pas de repas déclaré, COB 0 : onset faux, TBR **1,30 U/h**. Accélération 0 : **1,30 U/h**. |
| COB virtuel | fait | #230, #232 | Rien sur les deux retours Android (préférence coupée, ou **36 g** déclarés : **0 g**, eventual **322**, pas de ligne) ni sur la courbe. Préférence allumée, Ra 2,0, repas 0,8, snapshot vide : `g=9.0`, `PRED_SET` eventual **198**. Le même snapshot avec FC 110 et repos 60 : `reason=hr_inflammation`, **0 g**, eventual **170**. Le SMB repas reste **3,30 U**. Le tick iOS appelle la même fonction. |
| Runtime patient | fait | #226, #234 | Rien sur la prédiction basse : TBR **0,25 U/h** et `terminals=HYPO_CONFLICT`. Repas et sport : `rise=OK`, `terminals=UNKNOWN`, TBR inchangés (**2,00** et **1,30**). Nuit : `rise=WEAK`, `terminals=UNKNOWN`, TBR **1,00 U/h**. Plus de `patientRuntime=skipped`. |
| Plancher PKPD | fait | #221 | Rien sur la ligne `raw=39`. Elle n’est pas relue dans le débit. Les autres scènes ne l’ont pas. |
| Session TPO | fait | #228 | Rien. JSON, pas Room. Plafond **0,80 U**, requête **0,20 U**, nuit **1,00 U/h**, sport **1,30 U/h**. |
| Learners | partiel | #229 | Option A appliquée faute de réponse explicite. `process` reste hors du débit. Départ à froid **1,000** et `WARMUP`, nuit **1,00 U/h**, comme Android sans historique. Le fichier d’historique n’est pas amorcé. L’activation exige une acceptation écrite de A, ou B. Cette acceptation n’est pas écrite ici. |
| `resetEarlyScratch` | fait | #215 | Rien. **27** écritures, le même ordre que l’adaptateur Android. Le mémo disait 29 : ce compte était faux. Ajouter deux écritures changerait Android ou inventerait des champs qu’il n’a pas. |
| TFLite / UAM | bloqué | #225, ADR D4, #239, #240 | Le tick n’appelle pas le modèle : `predictSmbUam` reste **0 U**. La référence est `libtensorflowlite_jni.so` 2.4.0 **arm64-v8a**. Les binaires Kotlin appareil et simulateur lient uniquement `libtensorflow-lite.a` du tag v2.4.0, flags `-O3 -DNDEBUG -ffp-contract=on -DTFLITE_WITH_RUY -DTFLITE_WITHOUT_XNNPACK`, un thread, sans délégué. `check-uam24-linked.sh` exige `otool -L` sans TensorFlowLiteC, chaque symbole `TfLite*` défini une fois, et le SHA-256 de l’archive égal au sidecar du cache. `linkedRuntimeAgainstTheAndroidInterpreterBits` exige **67 / 67**. L’appareil est lié, pas exécuté. Aucune exemption. |
| Flocon de chiffrement | fait | #237 | Rien sur le test. `tamperSecureEnvelope` fait un XOR `0x01` sur l’octet du milieu du corps chiffré. `ff` devient `fe`. `isValidDataString` est faux. `decrypt` rend `""`. Le fail de #215 était ce flocon. |
| `AimiDecisionContext` | fait | #219, #233 | Rien sur les champs. `decideAimiDecisionContext` est commun. `htr_ra_floor_mgdl_per_min` reste null au bootstrap. L’estimateur de ratio reste lu par la coquille Android, puis passé en argument. |

## Conditions d'activation

`AimiCommonEngineSwitch` et `IosClientConfig.APS` restent éteints. Les allumer exige que chaque ligne suivante soit verte en même temps. Une seule ligne ouverte suffit à les laisser éteints.

1. **Toutes les traces de parité**, octet pour octet, entre le tick Android et la tranche iOS. Dans le tableau, chaque point est `fait` et sa colonne « encore manquant » ne contient plus un écart de trace. Aujourd’hui Learners reste `partiel` : l’activation exige une acceptation écrite de A, ou B. TFLite reste `bloqué`.
2. **TFLite**, ou une exemption écrite. La parité de production est Android arm64 contre iOS arm64, 1 thread, sans délégué. La référence mesurée est `libtensorflowlite_jni.so` 2.4.0 **arm64-v8a**. Elle diffère de la tranche x86_64 du même AAR : **18 / 67** identiques, ULP max **56**. Le run [37405106229](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37405106229) a imprimé **67 / 67**, ULP **0**, pour TensorFlow Lite C 2.4.0 compilé `iossimulator-arm64`. Les binaires Kotlin appareil et simulateur lient uniquement cette archive source, et la même source avec les mêmes flags pour `iphoneos-arm64`. `linkedRuntimeAgainstTheAndroidInterpreterBits` exige cette égalité et une version `2.4`. TensorFlow Lite C 2.10.0 n’est plus lié. `check-uam24-linked.sh` lit le binaire de `linkDebugTestIosArm64` : `otool -L` ne nomme pas TensorFlowLiteC, chaque symbole `TfLite*` est défini une fois, et le SHA-256 de `libtensorflow-lite.a` est égal à `archive.sha256` du cache. Le même contrôle s’applique au binaire de test simulateur. Le risque résiduel est l’appareil : la CI produit `linkDebugTestIosArm64` et ne lance pas les 67 vecteurs dessus. Le lever : exécuter ce test sur un iPhone physique, ou sur un appareil iOS cloud, et exiger **67 / 67** contre les mêmes fixtures. Ce mémo propose ce run. Il ne le lance pas. Le tick n’appelle toujours pas le modèle. L’exemption serait un texte qui accepte le chemin modèle absent (`predictSmbUam` **0 U**, `refine` identité) comme parité. ADR D4 constate l’arrêt. Ce mémo ne choisit pas cette exemption. TFLite reste bloqué pour l’allumage.
3. **Session HealthKit.** Le port rejoue la scène ISF **45** / `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`. Les entitlements ci-dessous sont écrits dans ce mémo. Les activer dans le binaire fait partie de l’allumage, pas d’une PR tant que les interrupteurs sont éteints.
4. **Learners.** L’option A est appliquée faute de réponse explicite : `process` hors du débit, départ à froid **1,000**, nuit **1,00 U/h**. L’activation exige une acceptation écrite de A, ou B. Cette phrase ne l’écrit pas. L’option B n’est pas choisie.

## Entitlements HealthKit

Documentés ici. Aucun fichier `.entitlements` n’est ajouté. `Info.plist` ne reçoit pas ces clés. Rien n’est activé.

- `com.apple.developer.healthkit` = vrai, pour ouvrir `HKHealthStore`.
- `NSHealthShareUsageDescription` : lecture des pas, de la fréquence cardiaque et de la fréquence cardiaque de repos, pour les mêmes fenêtres que la montre Android.
- Types lus : `HKQuantityTypeIdentifierStepCount`, `HKQuantityTypeIdentifierHeartRate`, `HKQuantityTypeIdentifierRestingHeartRate`.
- Pas de `NSHealthUpdateUsageDescription` : la session ne demande aucun type en écriture.
- Pas de `com.apple.developer.healthkit.background-delivery` : la session est une lecture, pas un réveil en arrière-plan.

`requestAuthorizationToShareTypes` n’est appelé que si `iosHealthKitApsEnabled` (copie de `IosClientConfig.APS`, laissée à faux) et `AimiCommonEngineSwitch.enabled` sont vrais tous les deux. Les deux sont faux. Le tick ne construit pas de requête HealthKit.

1. **Hystérésis.** Déjà les mêmes `object` que `dev_OAPSAIMI` (`MealAbsorptionPhaseHysteresis`, `MealAbsorptionMemory`, `EndogenousPhaseHysteresis`, `PhysiologicalPatternHysteresis`, `InsulinSlopePreserveHysteresis`). Il ne reste pas de second cycle de vie. Le défaut iOS n’appelle pas `reset()`. Taille : un test de deux ticks, rien d’autre. Trace : `lowPredictionRequestsAQuarterBasal` sur instance propre, TBR **0,25 U/h** sans la ligne `meal absorption hysteresis hold` ; puis un tick `FIRST_WAVE` suivi d’un tick `NONE` qui garde `meal absorption hysteresis hold`, comme le singleton Android.

2. **Wearable, FC, et persistance des pas, de la FC et des bolus.** HealthKit remplit le même `HealthContextSnapshot` (pas 5/15/60 min, FC, FC de repos, fenêtres 10 et 60 min). Le contrat de lecture est commun. Le tick iOS lit `HeartRate`, `StepsCount` et `Bolus` dans Room KMP `2.8.4` (déjà dans le dépôt, Kotlin `2.4.10`, AGP `9.4.0`). Pas de SQLDelight. Les 13 migrations `22→23` … `34→35` sont en commun et passées au constructeur iOS avant tout schéma suivant. Un fichier neuf naît au schéma 35, vide. Android continue d’appeler `persistenceLayer`. Trace : `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf`, les mêmes lignes Room, ISF 50 × 0,90 = **45**, ligne `HR_TREND_ISF x0.90`.

3. **Effort.** `decideRefreshEffortActivityBelief` est le corps Android, appelé avec le snapshot déjà lu. La protection coupée et T3C coupé laissent l’assessment null. Un snapshot invalide aussi. L’échec de lecture journalise la ligne wearable et ne réduit pas. Le facteur ne dépasse pas 1. Le tick iOS de repas passe le snapshot vide, protection coupée, et garde SMB **3,30 U** et TBR **2,00 U/h**. Trace : `signalMealReturnsTheAdvisorSmbAndTbr`.

4. **Veto.** `decideMealOnsetBehindEffortVeto` calcule `decideEffortSuppressesUndeclaredMeal` puis `decideDetectMealOnset`. Android `detectMealOnset` délègue. Le tick iOS aussi, puis le sport appelle `decideBasalDecisionEngine` sur le bundle verrouillé (`forcedBasal` 2,0, modes, autodrive, historique basal vide). Le résolveur de texte rend `phrase`, comme la coquille. Accélération 2, pas d’assessment : TBR **2,00 U/h**, raison `phrase [AD_EARLY_TBR_TRIGGER rate=2.0]`. Trace déjà verrouillée : `basalDecisionEngineRaisesSportTemp`, BG 180, delta +5, accélération 0, onset faux, TBR **1,30 U/h**. Même scène avec EXERTION, confiance 0,30, COB 0 : onset faux, TBR **1,30 U/h**.

5. **COB virtuel.** `decideEstimateUndeclaredVirtualCob`, puis `decideApplyAdvancedPredictions` quand la préférence est allumée et qu’aucun glucide n’est déjà déclaré. Préférence coupée, ou glucides déjà déclarés : **0 g**, sans lire le snapshot, sans ligne, sans courbe. Trace déclarée : `advancedPredictionPublishesEventualFromDeclaredCob`, COB **36 g**, eventual **322**, pas de ligne. Préférence allumée, Ra 2,0, repas 0,8, snapshot vide : **9,0 g**, `PRED_SET size=49 eventual=198 min=150 uamT=163 source=AdvancedCurves`. FC 110 et repos 60 : **0 g**, `reason=hr_inflammation`, eventual **170**. Le tick iOS appelle la même fonction. Le SMB repas reste **3,30 U**.

6. **Runtime patient.** `decideRefreshPatientStateRuntime` sur les quatre scènes. Prédiction basse : chemin 39, `terminals=HYPO_CONFLICT`, TBR **0,25 U/h**. Repas, glycémie 160, delta +2, et sport, glycémie 180, delta +5 : `rise=OK`, `terminals=UNKNOWN`. Nuit, glycémie 180, delta 0 : `rise=WEAK`, `terminals=UNKNOWN`, les quatre lignes de `PHYSIO_LATENT_SMB_CEILING_TRACE`. Les débits ne changent pas. Plus de `patientRuntime=skipped`.

7. **Plancher PKPD.** Le débit Android vient des courbes, pas d’une relecture du JSON. La parité est la même ligne `PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled` dans le tick, et le même champ d’export. Le tick iOS de prédiction basse appelle `decideRecordPkpdSoftFloor` après le wearable et avant le TBR, puis il retourne. L’onset n’est pas atteint. Le champ écrit est `lastPkpdSoftFloorTelemetry`, celui que le scratch du début de tick avait vidé. Les scènes repas, sport et nuit ne reçoivent pas la ligne raw=39. Pas de seconde formule. Trace : `lowPredictionRequestsAQuarterBasal`.

8. **TPO.** `onTickStart` avec la même horloge, le fichier `tpo/tpo_session.json`, et le même reversement de préférences. Une session post-hypo d’un cran abaisse le plafond SMB de **1,00 U** à **0,80 U**. La scène d’activité (requête 2 U, protection, amortissement repas 0,50) livre alors **0,20 U**, ligne `SMB capped by Activity/Recovery (Limit: 0.40)`. Passé le délai de 45 min, le plafond revient à **1,00 U** et la requête à **0,25 U**. Traces sans session : `zzPostHypoAtFiveSkipsTheDriftMicroSmb`, TBR **1,00 U/h** ; `basalDecisionEngineRaisesSportTemp`, TBR **1,30 U/h**. Le tick de nuit iOS avec session active garde le TBR **1,00 U/h** et ajoute la requête **0,20 U**.

9. **Learners.** `BasalLearner`, `BasalNeuralLearner` et `UnifiedReactivityLearner` sont en `commonMain`. Le tick de nuit iOS les construit sur le même dossier que la session TPO et lit `aimi_basal_learner.json`, `aimi_unified_reactivity.json`, `basal_adaptive_weights.json`, `t3c_brain_weights.json`. Le CSV `basal_adaptive_records.csv` est celui qu’écrit `updateLearning`. Pas de table Room, pas de schéma 36. Départ à froid : multiplicateurs **1,0**, gouvernance `WARMUP` / `Warmup`. Un fichier illisible journalise `Load failed, using defaults (multiplier=1.0)` et reste à 1,0. Option A appliquée faute de réponse explicite : `process` reste hors du débit, comme Android sans historique. L’activation exige une acceptation écrite de A, ou B. Cette acceptation n’est pas écrite ici. L’option B n’est pas choisie. `KEEP` seulement après les mêmes échantillons réalisés. Trace : tick de nuit, TBR **1,00 U/h**, lignes `multiplier=1.000`, `factor=1.000`, `BASAL_GOV[FINAL]` `action=WARMUP` `reason=Warmup`. Le sport reste **1,30 U/h** sans ces lignes.

10. **TFLite et UAM.** Le même fichier `modelUAM.tflite` (4 504 octets). Rien n’est touché. L’inférence Kotlin commune n’est pas retenue. La reprise future exige le même interpréteur LiteRT C sur iOS, CPU d’abord, et un corpus dont le SMB est celui d’`Interpreter` 2.4.0 Android. Détail dans « Plans de PR ». Modèle absent : `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement. Trace : `uamPostHypoReboundBridgesAShortTempBasal`, SMB prédit **0 U**, TBR **1,05 U/h**, 5 min.

11. **`AimiDecisionContext`.** Le type, `toMedicalJson()` et `decideAimiDecisionContext` sont en `commonMain`. Les champs ne changent pas. `htr_ra_floor_mgdl_per_min` reste null au bootstrap. La coquille Android lit encore l’estimateur de ratio et `getLastRa()`, puis passe les nombres. Elle garde `currentTickDecisionEventId`. Trace : `lowPredictionRequestsAQuarterBasal`, TBR **0,25 U/h**, et l’export `pkpd_soft_floor` raw 39, soft 39, hybride 39, `applied` faux, raison `endo_reversion_disabled`.

## Plans de PR, gros chantiers

Les chantiers encore ouverts restent derrière l’interrupteur éteint jusqu’à leur trace verte. Rien n’est activé.

### HealthKit et snapshot wearable

- `openHealthKitReadSession` demande au port les pas sur 5, 15 et 60 min, les échantillons de FC sur 60 min, et la FC de repos sur 24 h, puis écrit `HealthContextSnapshot`. Les fenêtres 10 et 60 min reprennent le chevauchement `timestamp + duration` de `decideHeartRateIsf`. Les quatre échantillons 80, 80, 80, puis 110 bpm donnent hr10 **110** et hr60 **88**, ISF 50 × 0,90 = **45**, ligne `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`. `hrAvg15m` recopie la FC courante, comme le dépôt Android. FC de repos absente : **60**.
- `iosMain` : `IosHealthKitWindowPort` est ce port sur `HKHealthStore`. Tant que `iosHealthKitApsEnabled` ou `AimiCommonEngineSwitch` est faux, `readIosHealthKitSession` ne l’appelle pas : pas de `requestAuthorizationToShareTypes`, pas de requête. Le tick garde `HealthContextSnapshot()` et `IOS_NEUTRAL wearable snapshot empty`. Les deux interrupteurs sont faux.
- La confiance suit Android : la FC seule vaut 0,3, et `isValid` exige plus que 0,3. Sans HRV ni sommeil le snapshot reste invalide, donc il ne réduit pas une dose. L’échec du port journalise `WEARABLE snapshot failed … — snapshot empty` et le résultat est vide. Un `Error` sort.
- JVM : sans port injecté, le même appel reste le snapshot vide et la ligne `IOS_NEUTRAL wearable snapshot empty`. Le test injecte un port qui rejoue HealthKit. Android `DetermineBasalAIMI2` n’est pas modifié.
- Hors de cette PR : le COB, le runtime patient, les learners. L’ISF **45** est la lecture commune des mêmes échantillons, pas une seconde formule. Les entitlements sont dans « Entitlements HealthKit ». Ils ne sont pas activés.

### Persistance des pas, de la FC et des bolus

Le tick iOS lit les trois DAO via `AppDatabase` (`RoomTherapyWindowReads`, pont `runBlocking` : les DAO restent `suspend`, `evaluate` reste une fonction normale). Fichier `aaps-ios.db`, `BundledSQLiteDriver`, `NSApplicationSupportDirectory`. Les 13 migrations sont `appDatabaseMigrations` en `commonMain`, passées par les constructeurs Android, iOS et JVM. Un fichier absent naît au schéma 35, vide. Android `DetermineBasalAIMI2` continue d’appeler `persistenceLayer`. `MemoryAimiTherapyReads` ne sert plus que de stand-in de test. Pas de SQLDelight. Pas de nouvelle dépendance.

Android `DetermineBasalAIMI2` continue d’appeler `persistenceLayer`. Le tick iOS lit le contrat. Un `Exception` est journalisé (`HR windows failed … — averages 80, baseline not real` pour la FC) et la liste est vide. Un `Error` sort. Une FC vide ne renforce pas l’ISF.

**Room KMP, retenu.** Kotlin `2.4.10`, AGP `9.4.0`, Room `2.8.4`. Pas de nouvelle dépendance. `AppDatabase` version 35 reste en `commonMain`. Les trois lectures sont `HeartRateDao.getFromTimeToTime`, `StepsCountDao.getFromTimeToTime`, `BolusDao.getBolusesFromTime`. La base a 21 tables ; un fichier neuf les crée toutes, vides. Les 13 migrations (`migration22to23` à `migration34to35`) sont `appDatabaseMigrations` en `commonMain` et sont passées par les trois constructeurs. `fallbackToDestructiveMigration(false)`. Les DAO restent `internal` et `suspend`. Le tick appelle `runBlocking` autour d’eux, comme `UnifiedActivityProviderMTR` le fait déjà pour les mêmes lectures. `evaluate` n’est pas devenu une fonction `suspend`.

**SQLDelight.** Non retenu. Absent du dépôt. L’ajouter aurait été une nouvelle dépendance.

Trace inchangée : quatre échantillons 80, 80, 80, puis 110 bpm, pas vides, ISF **45**, `HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)`. Cache bolus vide : SMB repas **3,30 U**, TBR **2,00 U/h**.

### Runtime patient

- Les 221 lignes sont `decideRefreshPatientStateRuntime`, derrière le snapshot déjà lu. L’échec de lecture reste dans la coquille Android : snapshot vide, pas un `catch` nouveau. Le tick iOS de prédiction basse appelle la même fonction après le plancher PKPD.
- Entrées de cette scène : champ `bg` encore à 0 (la glycémie 100 n’est pas encore copiée), cible 100, delta 0, IOB 0, `maxIob` 0, chemin scénario 39 mg/dL. L’état latent part du snapshot vide, confiance capteur 0,105, tronc 0,90.
- Trace : `lowPredictionRequestsAQuarterBasal`, `TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE`, `MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false`, TBR **0,25 U/h**.

### TPO

- `onTickStart` avec l’horloge déjà portée. Le fichier est `tpo/tpo_session.json`, celui d’Android. Les préférences iOS sont `tpo/aimi_preferences.json` dans le même dossier : Android garde son magasin de préférences, le reversement est `TpoSessionManager.expireIfNeeded`. Pas de table Room, pas de schéma 36.
- Session active, un cran post-hypo : plafond SMB **0,80 U**, requête d’activité **0,20 U**, `SMB capped by Activity/Recovery (Limit: 0.40)`. Session expirée : plafond **1,00 U**, requête **0,25 U**. Nuit sans session TBR **1,00 U/h**. Sport sans session TBR **1,30 U/h**. Le TBR de nuit reste **1,00 U/h** même avec la session : la requête **0,20 U** est celle de la scène d’activité, pas un second débit de nuit.

### Learners

- Les trois classes lisent et écrivent les fichiers Android dans le dossier AIMI déjà ouvert pour la session : `aimi_basal_learner.json`, `aimi_unified_reactivity.json`, `basal_adaptive_weights.json`, `t3c_brain_weights.json`, `basal_adaptive_records.csv`. Pas de SQLDelight, pas de table Room.
- Le tick de nuit appelle la même mise en forme que `logLearnersHealth` et `BASAL_GOV`. Dépôt vide : multiplicateur **1,000**, facteur **1,000**, `action=WARMUP`, `reason=Warmup`, TBR **1,00 U/h**. Un JSON basal illisible journalise `BasalLearner: Load failed, using defaults (multiplier=1.0)`.
- `process` et `updateLearning` ne sont pas dans le débit de nuit. Android les nourrit avec la glycémie du tick. Cette scène n’en a pas. Le CSV est prouvé par un appel direct d’`updateLearning`, qui laisse la gouvernance à `WARMUP` tant qu’aucun résultat réalisé n’est revenu. Le sport reste **1,30 U/h** et ne reçoit pas ces lignes.
- `process` reste hors du débit. C’est l’option A, appliquée tant que l’utilisateur n’a pas choisi B. Le départ à froid reste **1,000**. Le TBR de nuit reste **1,00 U/h**. Le fichier d’historique n’est pas amorcé.

## Learners, `process`

`BasalLearner.process` depuis les multiplicateurs 1,0 ne change pas un nombre de dose. Le terme court (alpha 0,25, ajustement 1,05) bouge d’environ 0,0125, puis le pas de convergence 0,02 le ramène à 1,0. Le terme moyen (alpha 0,15, ajustement 1,12) bouge d’environ 0,018 et revient aussi à 1,0. Le terme long peut laisser 1,001 : la combinaison change d’environ 0,00025, `aimiFmt3` reste `1.000`, et `roundBasal` d’une basale 1,00 reste **1,00 U/h**. `onHypoDetected` (×0,90, combinaison **0,96**) n’est pas `process`.

| Option | Effet |
|---|---|
| A. Laisser `process` hors du débit | Trois échantillons montants (BG 180, delta +8) restent au multiplicateur **1,000**. Le TBR de nuit reste **1,00 U/h**. C’est le départ à froid Android. |
| B. Amorcer `aimi_basal_learner.json` avec un historique qu’Android rechargerait, puis appeler `process` sur la même glycémie | Le multiplicateur part d’ailleurs que 1,0 et le TBR peut changer. Le fichier d’historique est le choix clinique. |

Option appliquée : **A**, faute de réponse explicite. `process` reste hors du débit. Départ à froid **1,000**, nuit **1,00 U/h**, comme Android sans historique. L’option B n’est pas choisie. L’activation exige une acceptation écrite de A, ou B. Cette acceptation n’est pas dans ce mémo. Ni le pas 0,02, ni la nuit **1,00 U/h**, ni `onHypoDetected` à la place de `process`.

### TFLite et UAM

Fichier inspecté : `app/src/main/assets/modelUAM.tflite` au commit `64e630c7fc` (absent de l’arbre de travail). 4 504 octets. SHA-256 `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092`. Identifiant `TFL3`, description `MLIR Converted.`, un sous-graphe. Entrée `[1, 18]` float32, sortie `[1, 1]` float32.

Sept opérations : `SUB` (entrée − moyenne `[1, 18]`), `MUL` (échelle `[1, 18]`), puis `FULLY_CONNECTED` 18→9, 9→5 et 5→2 avec ReLU fusionné, `FULLY_CONNECTED` 2→1 sans activation, `PRELU` à alpha partagé (un scalaire).

Le graphe est petit. Une boucle float32 en Kotlin commun peut l’exécuter. Elle ne prouve pas le SMB Android. `AimiUamHandler` appelle `org.tensorflow.lite.Interpreter` 2.4.0, tronque à 4 décimales (`(v * 10000f).toInt() / 10000f`) et plancher à 0. La bibliothèque JNI de cet artefact est un binaire Android : elle ne se charge pas sur cette machine. Un interpréteur LiteRT récent, en trois modes (référence, builtin sans délégué, XNNPACK), comparé à la boucle scalaire sur 67 vecteurs : le tenseur brut diffère sur 31 à 35 vecteurs selon le mode ; le SMB après la troncature Android coïncide sur les 67. Cette coïncidence n’est pas le bit d’`Interpreter` 2.4.0 sur téléphone. L’ADR D4 le dit : le réseau Kotlin n’est pas le graphe TFLite.

Décision d’alors : s’arrêter. Pas d’inférence Kotlin commune. Le chemin modèle absent reste celui du tick : `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement. Trace : `uamPostHypoReboundBridgesAShortTempBasal`, SMB **0 U**, TBR **1,05 U/h**, 5 min.

La reprise est la section suivante. Le tick n’est pas branché.

## Plan TFLite iOS

Le cinterop et le corpus sont en place. La compilation source `iossimulator-arm64` du tag v2.4.0 est verte bit à bit contre la référence Android arm64. Le tick n’appelle pas le modèle. ADR D4 constate l’arrêt précédent. Ce texte n’est pas l’exemption qui autoriserait l’allumage.

**Interpréteur.** Android reste `org.tensorflow:tensorflow-lite:2.4.0`. Un thread, sans délégué.

TensorFlow Lite C **2.10.0** n’est plus téléchargé et n’est plus lié. Contre la référence arm64-v8a il différait de **43** mots, ULP max **88** (vecteur 1, Android `3c21f4d4`, iOS `3c21f52c`). Ce runtime est rejeté. `linkedRuntimeAgainstTheAndroidInterpreterBits` exige maintenant **67 / 67** et une version qui commence par `2.4`.

TensorFlow Lite C **2.4.0**, tranche simulateur x86_64 du framework publié (`lipo -thin x86_64`, binaire `MH_OBJECT`, iOS 9), a tourné sous `simctl`. Le run [37394005430](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37394005430) imprime `version 2.4.0` et **67 / 67** identiques à la tranche Android **x86_64**. ULP max **0**. Cette égalité est réelle. Elle compare deux hôtes x86_64. Les téléphones sont arm64.

`build-uam24-device-arm64.sh` compile le même tag pour `iphoneos-arm64`, avec les flags de `uam24-common.sh`. `linkDebugTestIosArm64` reçoit uniquement cette `libtensorflow-lite.a`. Le binaire appareil ne reçoit pas le framework publié, ni à la place de cette archive, ni à côté. Le binaire de test simulateur reçoit uniquement l’archive `iossimulator-arm64` du même tag. `check-uam24-linked.sh` échoue si `otool -L` nomme TensorFlowLiteC, si un symbole `TfLite*` est défini plus d’une fois ou reste indéfini, ou si le SHA-256 de l’archive diffère de `archive.sha256`. La carte `-map` doit contenir le chemin de cette archive. L’appareil n’est pas exécuté. Le tag v2.4.0 n’a pas de config Bazel `ios_sim_arm64`. `build-uam24-sim-arm64.sh` compile le même tag pour le simulateur arm64 et lance les 67 vecteurs. `check-uam24-flags.sh` refuse un écart de flags ou de version de clang entre les deux bibliothèques. Les caches sont séparés et portent le sidecar `archive.sha256`. Un écart de mot fait échouer le job. Le run [37416729231](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37416729231) a construit les deux bibliothèques, puis le contrôle a échoué : les `-I` portent `tflite-24-src-sim` ou `tflite-24-src-device`. Les flags de parité étaient déjà les mêmes. Le contrôle ignore les chemins d'inclusion.

Essai 1, run [37402326168](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37402326168), cache froid, environ 63 s : le téléchargement des dépendances réussit, GNU Make 3.81 lance la compilation, et Xcode 26 refuse `std::abs<float>` dans `elementwise.cc:155`. L’AAR Android 2.4.0 identifie NDK clang 7.0.2, qui accepte cette forme. Aucun vecteur n’a tourné.

Essai 2, run [37403588402](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37403588402), cache froid, 3 min 16 s. `-O3 -ffp-contract=on`, RUY, et les cinq lambdas. La bibliothèque `iossimulator-arm64` est produite. L’édition de liens du lanceur échoue : `absl::cctz::local_time_zone` demande CoreFoundation, et `-force_load` embarque `test_delegate_providers.cc` (le makefile ne l’exclut pas, le nom ne finit pas par `test.cc`). Aucun vecteur n’a tourné. Le runtime Android n’est pas changé. Les opérations du graphe ne changent pas.

Essai 3, run [37405106229](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37405106229), tête `66aa157ada`. Le cache `tflite-2.4.0-iossim-arm64-v1-…` répond « Cache not found » : les essais 1 et 2 ont échoué avant l’écriture. L’étape dure **4 min 11 s** (02:42:03–02:46:14 UTC). GNU Make 3.81. Apple clang **21.0.0** (`clang-2100.1.1.101`), SDK `iPhoneSimulator26.5`, `-arch arm64`, `-O3 -DNDEBUG -ffp-contract=on -DTFLITE_WITH_RUY -DTFLITE_WITHOUT_XNNPACK`. Le fichier de test est retiré avant `make`. Le lanceur lie `-framework CoreFoundation`. `spawn status 0`. Le journal imprime `version 2.4.0`, puis les 67 paires de mots identiques, puis `identical 67 / 67` et `67 / 67 identical`. Aucune ligne `ulp=`. L’écriture du cache a lieu après le job, clé identique. L’AAR Android reste celui de NDK clang 7.0.2 ; ce runtime n’est pas modifié. Les flags `-O3` et `-ffp-contract=on` sont ceux de cet essai, déjà en place depuis l’essai 2. Aucun essai supplémentaire n’est nécessaire : les 67 mots coïncident.

Pas de délégué GPU. XNNPACK n’est pas ajouté. `SetNumThreads(1)`. Pas de réécriture du graphe en Kotlin. Les binaires ne sont pas dans git. `run-uam24-x64.sh` télécharge le framework publié 2.4.0, en extrait l’objet x86_64 (`MH_OBJECT` après `lipo -thin`) et le lie dans un lanceur séparé. Ce lanceur verrouille l’écart x86 déjà mesuré. Les binaires Kotlin `iosArm64` et `iosSimulatorArm64` reçoivent `libtensorflow-lite.a` compilée depuis le tag, `-lc++` et CoreFoundation. `tflite.def` ne nomme pas TensorFlowLiteC. Kotlin 2.4 ignore `-linker-option` du cinterop ; `linkerOpts` du binaire nomme l’archive source et écrit `debugTest-link.map`. Kotlin 2.4 ne lance pas ce cinterop sur Linux : `compileKotlinIosSimulatorArm64` de `:plugins:aps` y est SKIPPED. La seule preuve de compilation iOS de ce module est la CI macOS.

**Modèle.** `plugins/aps/src/tfliteParity/modelUAM.tflite`, le fichier du commit `64e630c7fc`. 4 504 octets. SHA-256 `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092`. Identifiant `TFL3`. Entrée `[1, 18]` float32, sortie `[1, 1]` float32. Les mêmes octets sont dans `UamTfliteCorpus`. Le tick ne les ouvre pas.

**Référence Android.** `libtensorflowlite_jni.so` 2.4.0 **arm64-v8a**, la bibliothèque qu’un téléphone charge. Elle a été exécutée telle quelle, sous `qemu-aarch64` avec le bionic de l’image Android 30 arm64 (`TfLiteInterpreterCreate`, `SetNumThreads(-1)` et `SetNumThreads(1)`, XNNPACK non appliqué). Les deux réglages donnent les mêmes 67 mots. Le constructeur de production `Interpreter(ByteBuffer)` (numThreads -1, `useXNNPACK` non fixé) coïncide donc avec 1 thread sur ce graphe, sur arm64. La tranche x86_64 du même AAR, mesurée avant, en partage **18 / 67**. ULP max **56** (vecteur 1, x86_64 `3c21f49c`, arm64 `3c21f4d4`). Les fixtures de parité sont les mots arm64. Les mots sont les bits bruts, en hex, pas le SMB tronqué. Les deux colonnes sont dans `android-arm64.txt` et `android-x86_64.txt`.

**Corpus.** 67 vecteurs. 0 : zéros. 1 : uns. 2..19 : vecteurs unité. 20..66 : 47 tirages d’un LCG (état `0x00C0FFEE`, ×1664525 + 1013904223, 24 bits, `u / 16777216f * 4f - 2f` en float32). Ce n’est pas la coïncidence ancienne « SMB tronqué identique, tenseur brut différent ».

**Critère.** L’égalité est `plugins/aps/src/tfliteParity/build-uam24-sim-arm64.sh` : TensorFlow Lite C 2.4.0 compilé pour `iossimulator-arm64`, contre les mots Android arm64-v8a. Un écart imprime l’index, les deux hex et la distance ULP, et fait échouer le job. Le run [37405106229](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37405106229) imprime **67 / 67**, ULP **0**. `run-uam24-x64.sh` verrouille l’écart déjà mesuré entre cette référence et la tranche x86_64 (18 / 67, ULP max 56) ; ce même run l’a reproduit. `UamTfliteIosParityTest` exige zéro écart et une version `2.4`. Le processus de test lie uniquement l’archive source simulateur, et `linkDebugTestIosArm64` lie uniquement l’archive source appareil. `check-uam24-linked.sh` imprime les deux SHA-256 et échoue s’ils diffèrent, si `otool -L` nomme TensorFlowLiteC, ou si un symbole `TfLite*` est défini plus d’une fois. L’appareil n’est pas exécuté. Le tick n’appelle pas le modèle : `predictSmbUam` reste **0 U**, `refine` identité, pas d’entraînement. Trace inchangée : `uamPostHypoReboundBridgesAShortTempBasal`, SMB **0 U**, TBR **1,05 U/h**, 5 min. Les interrupteurs restent faux. Aucune exemption n’est choisie. Kotlin 2.4 ne lance pas le cinterop de `:plugins:aps` sur Linux : `compileKotlinIosSimulatorArm64` y est SKIPPED. La seule preuve de compilation iOS de ce module est la CI macOS. `linkDebugTestIosArm64` a bien tourné sur ce run.

## CI iOS de #215

Le run [37290294934](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37290294934) (`push`, SHA `07393a357203b459a03ae80578225cdd2041931a`) a échoué à l’étape « Run the shared tests on the iOS simulator » : `304 tests completed, 1 failed`. Le seul échec est `IosSecureEncryptTest.a tampered body no longer validates`, `kotlin.AssertionError` sans message. Le run [37290303572](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37290303572) est le même SHA, événement `pull_request`, et son étape de tests finit par `BUILD SUCCESSFUL`. Les deux jobs sont allés au bout. Aucun n’a été annulé.

Le test remplace les deux derniers caractères de l’enveloppe par `ff`. L’enveloppe est `<sha256>:<alias>:<iv hex>:<chiffré hex>`. Le dernier octet du chiffré GCM est l’étiquette, donc ces deux caractères hex sont uniformes. S’ils sont déjà `ff`, la chaîne ne change pas, `isValidDataString` reste vrai, et `assertFalse` lève une `AssertionError` sans message. Une chaîne vraiment modifiée rate le hash d’en-tête, et `decrypt` rend `""`. Le produit tient. Le test rate quand le suffixe aléatoire est déjà `ff`, une fois sur 256. Le run vert est le cas où le suffixe était autre. Le fichier de test sur ce SHA est l’octet `057b352e0de0e5b355976acbeafad9d77106135b`. Les CI iOS vertes plus haut dans la pile ont exécuté ce même test.

Ce n’est pas une tête obsolète annulée par la concurrence. #217 ajoute `concurrency.group: ios-ci-${{ github.ref }}` et `cancel-in-progress: true`, et a été mergée le 2026-10-05 à 12:32 UTC. Les deux runs de #215 ont fini à 10:39 UTC, avant ce merge. Le workflow de ce SHA ne contenait pas ce groupe.

La correction de la pile est #237 : `tamperSecureEnvelopeMiddleByte` fait un XOR `0x01` sur l’octet du milieu du corps chiffré, y compris `ff` → `fe`. `dropLast(2) + "ff"` n’est plus utilisé. Le test jumeau JVM parcourt les 256 valeurs du milieu. L’étude a déjà #153 : `tamperSecureEnvelopeLastByte` XOR le dernier octet, `ff` → `fe`, et parcourt les 256 valeurs de ce dernier octet. Les deux fonctions restent. Le test iOS appelle les deux, et le corps qui finit déjà par `ff` reste celui de #153.

## Prêt à intégrer

Inventaire seulement. L’intégration n’est pas demandée. Aucune de ces PR n’est mergée ici. L’activation reste bloquée par l’acceptation écrite de A, ou B, et par TFLite.

La tête de code du correctif est #237, `cursor/p145-secure-encrypt-tamper-da40`, SHA `8f6ca8b08b872add7f26a5cf906b52179ea75a7b`. Le commit d’inventaire est juste au-dessus. La branche contient les commits de #206 à #237. `kmp-aimi-migration-study` (`8be81495f1ca`) n’en est pas l’ancêtre : 10 commits de l’étude manquent sur la tête, dont les merges #213 et #217, le workflow de concurrence iOS, et #153. Une branche d’intégration rejoue la gate ci-dessous après les avoir réunis. Pas avant.

Ordre d’empilement. Le SHA est la tête de la branche.

| PR | Branche | SHA | CI `ios` |
|---|---|---|---|
| #206 | `cursor/p116-pure-dose-helpers-da40` | `3220e7665378` | 2 pass |
| #207 | `cursor/p117-tick-prefix-da40` | `d7321513cb4a` | 2 pass |
| #208 | `cursor/p118-tick-signal-da40` | `9e6da6cba46a` | 2 pass |
| #209 | `cursor/p119-tick-post-hypo-da40` | `1ee2f9b4c0a9` | 2 pass |
| #210 | `cursor/p120-tick-schedule-uam-da40` | `dca5bbf6700a` | 2 pass |
| #211 | `cursor/p121-tick-meal-ngr-da40` | `3f87da1ac68a` | 2 pass |
| #212 | `cursor/p122-tick-basal-engine-da40` | `e69902f0722d` | 1 pass |
| #214 | `cursor/p123-effort-veto-pkpd-floor-da40` | `6660bfd5dbf5` | 2 pass |
| #215 | `cursor/p124-ios-neutral-hold-da40` | `07393a357203` | 1 fail, 1 pass, même SHA, flocon corrigé par #237 |
| #216 | `cursor/p125-trace-singleton-isolation-da40` | `6fa156e4c648` | 2 pass |
| #218 | `cursor/p126-hysteresis-two-tick-da40` | `553bd808b2bc` | 2 pass |
| #219 | `cursor/p127-decision-context-common-da40` | `52c88632ee61` | 2 pass |
| #220 | `cursor/p128-effort-veto-common-da40` | `42765dfbb803` | 2 pass |
| #221 | `cursor/p129-pkpd-floor-tick-da40` | `e1397f73f649` | 2 pass |
| #222 | `cursor/p130-effort-belief-common-da40` | `1647196f3497` | 2 pass |
| #223 | `cursor/p131-therapy-reads-da40` | `e9aea5eeb65d` | 2 pass |
| #224 | `cursor/p132-healthkit-snapshot-da40` | `fb003019f39e` | 2 pass |
| #225 | `cursor/p133-store-uam-notes-da40` | `70d4cfb263a8` | aucun `ios` (mémo seul) |
| #226 | `cursor/p134-patient-runtime-common-da40` | `67fd1ab7527c` | 1 pass |
| #227 | `cursor/p135-room-therapy-ios-da40` | `1dd759db17b6` | 2 pass |
| #228 | `cursor/p136-tpo-session-json-da40` | `0e61920b4c21` | 2 pass |
| #229 | `cursor/p137-learners-files-da40` | `9886145df8d1` | 2 pass |
| #230 | `cursor/p138-virtual-cob-da40` | `a55153bdcff9` | 2 pass |
| #231 | `cursor/p139-meal-onset-veto-da40` | `799d5e9f3ef1` | 1 pass |
| #232 | `cursor/p140-virtual-cob-curve-da40` | `21a2ef6151be` | en cours |
| #235 | `cursor/p141-sport-forced-basal-da40` | `df68c9a5ab60` | en cours |
| #234 | `cursor/p142-scene-patient-runtime-da40` | `d9a7f13ee3ff` | en file |
| #233 | `cursor/p143-decision-context-factory-da40` | `8ccbccd8f5ae` | en cours |
| #236 | `cursor/p144-healthkit-read-session-da40` | `61876c6b3faf` | en file |
| #237 | `cursor/p145-secure-encrypt-tamper-da40` | `8f6ca8b08b87` (correctif) | en file, push et pull_request |

#213 et #217 sont déjà mergées dans l’étude. Elles ne sont pas dans cette liste. `claude-review` n’est pas la CI iOS. Les comptes XML de #232 à #237 ne sont pas encore là.

Gate locale à rejouer sur la branche d’intégration, après la réunion avec l’étude. Linux ne lance pas `iosSimulatorArm64Test`.

```
./gradlew :plugins:aps:testAndroidHost --offline
./gradlew :plugins:aps:jvmTest --offline
./gradlew :plugins:aps:compileTestKotlinIosSimulatorArm64 --offline
./gradlew :implementation:jvmTest --offline --tests app.aaps.implementation.protection.SecureEnvelopeTamperTest
./gradlew :implementation:testAndroidHostTest --tests app.aaps.implementation.protection.SecureEncryptImplTest
./gradlew :implementation:compileTestKotlinIosSimulatorArm64 --offline
```

`testAndroidHost` se rejoue une seconde fois avec `@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)` sur `ShellDecisionTraceTest` seulement. L’annotation est retirée avant tout commit. `rg FixMethodOrder` doit être vide. Les deux skips connus sont `NightGrowthResistanceMonitorTest` et `OrefReasonParserTest`. Dernière mesure de cette suite, sur le parent de #237, `plugins/aps` inchangé par #237 : 298 XML, 2046 tests, 0 échec, 0 erreur, 2 skips, les deux ordres. La réunion avec l’étude peut changer ce compte : on le recompte.

Sur macOS, la gate iOS est celle du workflow : `iosSimulatorArm64Test` de `:core:data`, `:core:nssdk`, `:core:keys`, `:core:interfaces`, `:core:objects`, `:implementation`, `:ios:shell`, `:plugins:aps`, puis la somme des XML `tests` / `failures` / `errors`.

Le détail des options temporaires reste ci-dessous.

## `estimateUndeclaredVirtualCob`

Android : la préférence `OApsAIMIUndeclaredCobEnabled` coupée rend **0 g**. Des glucides déjà déclarés rendent **0 g**. Sinon l’estimateur lit le snapshot wearable, la Ra, le poids, le TDD, et journalise `VIRTUAL_COB`. Ces grammes nourrissent la prédiction et le TBR. Ils ne deviennent pas un SMB.

Scène verrouillée : COB déclaré **36 g**, glycémie 180, eventual publié **322**. Le COB déclaré force le retour à 0 g virtuel.

| Option | Effet sur la scène |
|---|---|
| Toujours 0 g | L’eventual **322** ne reçoit pas de grammes virtuels en plus |
| Même préférence qu’Android, coupée | Même eventual **322** |
| HealthKit branché et préférence allumée | Peut ajouter des grammes. Aucune trace ne verrouille le TBR qui en sortirait |

Recommandation retenue : la fonction Android. Préférence coupée : **0 g**, eventual **322**. Préférence allumée : la ligne de l’estimateur, pas un second calcul.

Côté iOS : le tick appelle la même fonction avec le snapshot de la session et `tpo/aimi_preferences.json`. Interrupteurs éteints : snapshot vide, pas de requête HealthKit.

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

Côté iOS : les quatre scènes appellent la fonction commune. La prédiction basse garde le chemin 39. Repas, sport et nuit n’ont pas ce chemin : `terminals=UNKNOWN`.

## Session TPO

Android : si `tpoOrchestrator` est initialisé, `onTickStart(dateUtil.now())` expire une session active et peut reverser des préférences, puis le tick relit le plafond SMB. `onPatientStateReady` peut ouvrir une session. Le fichier de session est `tpo/tpo_session.json`.

Scènes verrouillées sans session : nuit, TBR **1,00 U/h** pendant 30 min ; sport, TBR **1,30 U/h** pendant 30 min.

Scène verrouillée avec session active : un cran post-hypo, plafond SMB **0,80 U**, requête d’activité **0,20 U**, ligne `SMB capped by Activity/Recovery (Limit: 0.40)`. Après 45 min le plafond revient à **1,00 U** et la requête à **0,25 U**.

| Option | Effet sur la scène |
|---|---|
| Ne pas appeler | Nuit **1,00 U/h**, sport **1,30 U/h**. Pas de requête **0,20 U** |
| Session active, même fichier, même reversement | Nuit **1,00 U/h** et sport **1,30 U/h** sans session. Avec session, requête **0,20 U**. Expirée, requête **0,25 U** |
| Nouvelle table Room | Second historique à côté du JSON Android |

Retenu : le JSON déjà utilisé. Le tick iOS appelle `decideTpoSessionAtTickStart` avec `aimiWallClockMs()`. Android continue d’appeler `tpoOrchestrator.onTickStart(dateUtil.now())` puis le même plafond.

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
| Mêmes 27 écritures, même ordre | Chaque tick repart comme l’instance fraîche : TBR **0,25 U/h**, plancher 39 |
| Ne pas remettre à zéro | Un tick suivant peut garder le one-shot ou le plancher précédent. Aucune trace de second tick ne verrouille un autre débit |
| Ajouter deux écritures pour arriver à 29 | Android n’a pas ces deux champs. Le tick Android changerait, ou iOS écrirait ce qu’Android n’écrit pas |

Recommandation : **les 27 écritures**, au début de chaque tick, dans l’ordre de l’adaptateur. Le 29 était un mauvais compte.

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
