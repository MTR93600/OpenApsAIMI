# P6 — porter le tick AIMI en commonMain, par tranches

Mesuré sur `kmp-aimi-migration-study` @ `7c13732990b338969c5c54f832492efb2bf8de57`.
Référence clinique : `dev_OAPSAIMI` @ `3dd0ca647722db854fc844eb344940bd133f8553`.
Merge-base : `283a184f60eb8b18dac42e228faebbe260c3aa22`.

Fichier : `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt` (20 109 lignes).
Le même fichier sur la ref est `plugins/aps/src/main/kotlin/.../DetermineBasalAIMI2.kt` (20 245 lignes).
Classe : `DetermineBasalaimiSMB2`. L’objet pur `BasalFirstPolicyMath` est en fin de fichier.

Ce lot ne déplace que la tranche 1. Il ne rejoue aucun commit de la ref. `:plugins:aimi-engine` reste `HoldAimiEngine` (`ENGINE_NOT_EXTRACTED`). `IosClientConfig.APS` reste `false`.

## 1. Dépendances Android et JVM, comptées dans le fichier study

Comptage par expression régulière sur le fichier study (une occurrence = un match). Un match dans un commentaire est compté et signalé.

| Dépendance | Occurrences | Équivalent déjà dans le dépôt | Tranche |
|---|---:|---|---|
| `android.annotation.SuppressLint` | 5 (`@SuppressLint`) | aucune. Annotation Android. Disparaît quand le `String.format` qu’elle couvre passe par `aimiFmt*` | formatage |
| `android.content.Context` | 0 | — | — |
| `android.content.SharedPreferences` | 1, commentaire L12062 seulement | `AimiKeyValueCache` (`commonMain`) documente le remplacement de `Context.getSharedPreferences` | pas d’appel |
| `androidx.collection.LongSparseArray` | 4 (import + 3 usages, L11132–L11141) | déjà en `commonMain` : `OpenAPSSMBPlugin` et `OpenAPSAutoISFPlugin` | pas un blocage |
| Room | 0 | `:database:impl` est déjà KMP ; le tick ne l’importe pas | — |
| Dagger / `javax.inject` | 0 | Metro a remplacé Dagger | — |
| Metro `@Inject` | 36 | Metro est KMP. Le constructeur du tick reste `androidMain` tant que les collaborateurs Android (TFLite, fichiers, notifications) n’ont pas de seam | orchestrateur |
| `java.io.File` | import + 5 `File(` dont 3 commentaires (L11154, L11155, L11182). Appels réels : `appExternalFallbackFile`, `appendCsvToFile`, backup | `AimiStorage` / `AimiPath` déjà utilisés L10213 (`storage.file(...)`) | fichiers |
| `java.io.RandomAccessFile` | import + lecture CSV L13930 | pas d’équivalent commun pour une lecture aléatoire. `AimiStorage` sait écrire, pas ce motif de lecture | fichiers |
| `java.text.DecimalFormat` | 2 (import + `withoutZeros` L13211) | `app.aaps.core.data.format.NumberFormat` via `AimiFmt.kt` | formatage |
| `java.text.SimpleDateFormat` | 4 sites (L9074, L12389, L13968, import) | `aimiCsvTimestamp` (`AimiTimestamp.kt`) pour `yyyy-MM-dd HH:mm:ss`. Les autres motifs (`yyyy-MM-dd HH:mm`, `yyyyMMdd_HHmmss`) n’ont pas encore de helper | formatage |
| `java.time` (`Instant`, `LocalDate`, `LocalTime`, `ZoneId`, `DateTimeFormatter`) | imports L293–297 ; `LocalTime.now()` L6370, L9048, L14045, L15965, L16072, L16296, L16392 ; `LocalDate.now()` L14054 ; `ZoneId.systemDefault()` L14057 ; `DateTimeFormatter` L12254, L13809, L13851 | `kotlinx.datetime` déjà importé dans le tick (`KxLocalDate`, `KxLocalTime`, `KotlinInstant`, 14 matches). `Clock.System` + `TimeZone.currentSystemDefault()` dans `AimiWallClock.kt` / `AimiTimestamp.kt` | horloge |
| `java.util.Calendar` | 18 matches du mot ; appels `Calendar.getInstance()` L2311, L2831, L9752, L13584, L15418 | même horloge `kotlinx.datetime`, à partir d’un `now` injecté. Le tick mélange encore l’horloge murale et `dateUtil.now()` | horloge |
| `java.util.Date` | 7 matches du mot | `kotlin.time.Instant` / `aimiWallClockMs()` (25 appels déjà) | horloge |
| `java.util.Locale` | 118, dont 116 `Locale.US` et 1 `Locale.getDefault()` | `aimiFmt0/1/2/4` (`AimiFmt.kt`) épingle le point décimal. **0 appel `aimiFmt` dans le tick aujourd’hui** | formatage |
| `String.format` | 55 | `aimiFmt*` | formatage |
| `".format("` | 496 | la plupart sont des templates de log `"%.2f".format`. `aimiFmt*` pour les nombres ; le texte utilisateur reste dans les ressources | formatage |
| `java.util.concurrent.atomic.AtomicBoolean` | 15 | `AapsLock` (`core/interfaces/.../concurrent/AapsLock.kt`, `expect`/`actual` JVM + iOS) | caches async |
| `AtomicReference` | 11 | idem | caches async |
| `TimeUnit` | 7 | `kotlin.time.Duration` | horloge |
| `kotlinx.coroutines.Dispatchers` | 2 ; `Dispatchers.IO` L1620 | `aapsIoDispatcher` (`core/interfaces/.../concurrent`, déjà utilisé par `:ui`) | caches async |
| `runBlocking` | 2 (import + L2862) | pas d’équivalent à conserver : un tick commun ne bloque pas le thread. Reste android tant que l’appelant est le thread de boucle Android | orchestrateur |
| `launch` | 14 | coroutines core, déjà en `commonMain` | avec le scope |
| `Math.round` | 4 (`round`, `roundBasal`) | **pas** `kotlin.math.round` : `Math.round` arrondit les demis vers +∞, `kotlin.math.round` les éloigne de zéro. Écart sur les négatifs. Un helper commun doit reproduire `floor(x + 0.5)` | arrondis |
| `BigDecimal` | 1, commentaire L13201 | — | — |
| `org.json` | 2, commentaires (L9949, L9973). Le code vivant est `AimiJson` (197 matches `AimiJson`/`JsonObj`/`JsonArr`) | déjà porté | fait |
| TFLite `Interpreter` / `tflite` | 2, commentaires de chemins de modèle L11154–L11155 | pas d’inférence dans ce fichier. `AimiSmbTrainer` est appelé plus bas et reste Android | ML, plus tard |
| ONNX | 0 dans ce fichier | — | — |
| `System.currentTimeMillis` / `SystemClock` | 0 | `aimiWallClockMs()` déjà là | — |
| `Thread` / `synchronized` / `@Synchronized` | 0 | — | — |
| `aapsLogger` | 54 | `AAPSLogger` est une interface. Le logger concret reste injecté | orchestrateur |
| `preferences.get` | 238 | `Preferences` est une interface commune. Les lectures restent dans le tick | orchestrateur |
| `persistenceLayer` / `PersistenceLayer` | 22 | interface. Les `refresh*Async` lisent la base hors du fil du tick | caches async |
| `fabricPrivacy` | 1 | injecté | orchestrateur |
| notification | 25 matches du mot, dont `notificationManager` injecté | `NotificationManager` est Android. Les notifications restent un seam | orchestrateur |
| `runCatching` | 27 déjà dans le fichier | **aucun nouveau `runCatching` dans ce lot.** `parseNgrTime` (L12281) avale déjà une erreur de parse. On ne le déplace pas ici | ne pas élargir |

`android.content.Context` n’est pas une dépendance du tick. Les 12 matches du mot `Context` sont des types du domaine (`ContextManager`, `ContextSnapshot`, `AimiTickContext`, …).

## 2. `git cherry` et `patch-id` — ne rien porter deux fois

Les deux corps qu’on pourrait croire « à reporter » sont **déjà** dans le fichier study, octet pour octet avec la ref.

| Bloc | Study | Ref | Identique |
|---|---|---|---|
| `BasalFirstPolicyMath` (KDoc inclus) | L19994–L20109 | L20130–L20245 | oui, SHA-256 `0da2479b2551cc53` (16 premiers hex) |
| constantes + `tightSpiralSmbCapEnergyThresholdU` + `tightSpiralSmbCapIobThresholdU` | 37 lignes depuis `RISE_FLOOR_REARM_MS` | 37 lignes | oui |

Commits de la ref qui ont touché ces symboles depuis le merge-base. `git merge-base --is-ancestor` vers `7c13732990` : code 1 (pas ancêtres). `git cherry -v 7c13732990 <commit> 283a184f60` : marque `+` (le patch n’est pas déjà appliqué tel quel). `git patch-id --stable` du diff texte (`*.kt`, `*.kts`, `*.xml`, `*.md`) :

| Commit | Sujet | patch-id | Marque cherry | Pourquoi on ne le rejoue pas |
|---|---|---|---|---|
| `f87d25e02419c33c6540ad5292c3bd21ad0d6b8a` | Add tests for physiological phase classification and basal-first policy logic | `900f60438eac56659a00f5f71667b0efbe0e0940` | `+` | 10 fichiers. Outre le math déjà présent, il modifie `EffortActivityBelief`, `StraightLineTubeAdvisor`, `PhysiologicalPhaseClassifier`, `EndogenousPhaseHysteresis` et ajoute trois autres tests. Le rejouer rejouerait ces autres fichiers. |
| `a5f53cdfae13a3b1f7bf09af2e7c557119f859e8` | feat(aimi): spiral SMB cap aligned with meal priority + eventual sanitize | `bbd5183e2114582e83bbe915c35be0bda9bccad6` | `+` | Touche aussi `InsulinStackingStance` et `SafetyNet`. Le seuil spiral est déjà identique. Tranche suivante, pas un cherry-pick. |

Les 29 commits de la study depuis le merge-base qui touchent `DetermineBasalAIMI2.kt` ont été passés au même `patch-id --stable`. **0** ne correspond à l’un de ces deux identifiants. Le corps est arrivé par un autre commit (`3ee2d6ec919dcf4dceae12b69df8b3adf399c572`, le passage du tick dans un vrai source set), pas par un patch équivalent. Le déplacer vers `commonMain` n’est pas un second port clinique.

Le test de la ref `plugins/aps/src/test/kotlin/.../BasalFirstPolicyMathTest.kt` (dans `f87d25e024`) **n’existe pas** sur la study (`git grep` vide). On reprend ses cas, pas le commit.

## 3. Tranches

Une fonction « pure » ici : entrées numériques ou booléennes, aucune lecture de membre, de préférence, de fichier, d’horloge murale, de log. Un survol automatique a marqué 145 fonctions membres « sans I/O » ; il compte trop large, parce que `bg`, `iob`, `mealTime` ne matchent pas le filtre. Seules les fonctions relues et fermées sur leurs paramètres sont des candidates.

### Tranche 1 — faite dans ce lot : `BasalFirstPolicyMath`

Objet déjà isolé, trois fonctions : `projectedBg`, `isAnticipatedRise`, `decide`. Aucune I/O. Le tick l’appelle déjà (`applyBasalFirstPolicy`, L17066) et n’écrit les plafonds SMB qu’autour. Destination : `plugins/aps/src/commonMain/.../basal/BasalFirstPolicyMath.kt`, package `app.aaps.plugins.aps.openAPSAIMI.basal`. L’objet android est supprimé. Un import remplace la copie. Même signatures, mêmes constantes, même corps.

Les gardes de la ref sont les branches du `decide` : BG sous 110, delta négatif (fragile), learner &lt; 0.75 sans résistance autosens, COB, repas lourd, montée persistante, one-shot advisor, montée confirmée, montée anticipée (delta, delta combiné, plancher 90, projection 30 min au-dessus de cible + 20). Elles sont toutes évaluées dans la fonction pure, avant toute écriture de plafond. L’écriture (`maxSMB = 0`) reste dans le wrapper android et ne s’exécute que si `decision.active`.

### Tranche 2 — faite : arrondis `Math.round` et fonctions pures voisines

Ne pas cherry-pick `a5f53cdfae` (patch-id `bbd5183e2114582e83bbe915c35be0bda9bccad6`, cherry `+`) ni `f87d25e024` (patch-id `900f60438eac56659a00f5f71667b0efbe0e0940`, cherry `+`). Les corps déplacés sont identiques à `dev_OAPSAIMI` @ `3dd0ca64772`, sauf les commentaires de `adjustBasalForMealHyper` (le facteur 10 / 8 est le même) et `aimiMathRoundToLong`, qui reproduit l’algorithme OpenJDK de `Math.round(double)` pour compiler sur iOS. Un test JVM compare cet algorithme à `java.lang.Math.round` sur les demis, les extrêmes et une grille. `round(): Int` reste dans le tick : il écrit `consoleError`.

Destination : `commonMain/.../math/AimiTickPolicyMath.kt`. Le tick android garde la signature et délègue. `isDriftTerminatorCondition` reste android : son texte utilise `"%.1f".format`, qui n’est pas dans le stdlib commun.

### Tranche 3 — formatage `aimiFmt*` (lot suivant)

Les fonctions listées plus bas dans l’ancienne tranche 3 sont déjà dans `AimiTickPolicyMath` (tranche 2), sauf `round(): Int`. Le lot suivant remplace les motifs qui ont un équivalent commun exact (`aimiFmt0/1/2/4`, `aimiCsvTimestamp`). Un motif sans équivalent exact reste android.

Ancienne liste, absorbée par la tranche 2 :

- `round(value, digits)` L13202 et `roundBasal` L12551. Garder la sémantique `Math.round` (demi vers +∞). `round(): Int` L13212 écrit `consoleError` : pas cette tranche.
- `adjustDIAForIOB` L12722
- `adjustBasalForMealHyper` L13224
- `calculateDynamicMicroBolus` L15214 (le `StringBuilder` n’est pas lu)
- `computeDynamicBolusMultiplier` L16238
- `predictedDelta` L16275
- `sharpRiseEligibleForTrajectorySpiralSoftCap` L10314
- `mealModeRuntimeToNullableMinutes` L15479 et `runtimeToMinutes` L15486
- `predictGlycemia` L12476 (activité triangulaire locale)

Pas purs, malgré l’air de l’être : `costFunction` (lit la cinétique du tick), `detectMealOnset` (appelle `effortSuppressesUndeclaredMeal()`), `isMealPriorityAlignedForSpiralSmbCap` (appelle `AimiUamHandler.confidenceOrZero()`), `calculateBasalRate` (appelle `roundBasal` puis c’est bon), `finalizeSmbToGive` (lit `iob`, `bg`, `delta`, `lateFatRiseFlag`).

### Tranche 4 — formatage

`String.format` / `"%.Nf".format` / `DecimalFormat` / `SimpleDateFormat` / `Locale.US` vers `aimiFmt*` et `aimiCsvTimestamp`. Écart à couvrir par un test rouge si un arrondi half-even remplaçait half-up : `AimiFmt.kt` le documente déjà (`0.25` à une décimale).

### Tranche 5 — horloge

`LocalTime.now()`, `Calendar.getInstance()`, `ZoneId.systemDefault()`, `Date` vers `kotlinx.datetime` et un instant passé en argument. Ne pas lire l’horloge au milieu d’une fonction déplacée : le tick capture `now` une fois.

### Tranche 6 — caches async

`AtomicBoolean` / `AtomicReference`, `determineIoScope` sur `Dispatchers.IO`, les `refresh*Async`. Seam : `AapsLock` + `aapsIoDispatcher`. Ces fonctions lisent `PersistenceLayer`. Elles ne bougent pas avec le calcul.

### Tranche 7 — fichiers CSV

`appendCsvToFile`, `RandomAccessFile`, `storageHelper.getAimiFile` qui renvoie encore un `java.io.File`. `AimiStorage` couvre le journal JSONL, pas encore cette lecture. Pas de dose.

### Tranche 8 — orchestrateur

`determine_basal` et les étapes `run*`. Reste `androidMain` : constructeur Metro, notifications, TFLite/SMB trainer, 238 lectures de préférences. `OpenAPSAIMIPlugin.kt` (2 602 lignes) est un lot à part. Le moteur `:plugins:aimi-engine` reste `Hold` tant qu’un `evaluate()` de replay n’existe pas.

## 4. Exécution de la tranche 1

Avant le déplacement, `BasalFirstPolicyMath` n’est pas dans `commonMain`.

- JVM, compilation de `:plugins:aps:compileTestKotlinJvm` : `BUILD FAILED in 3m 29s`, `GRADLE_EXIT=1`, 11 `Unresolved reference 'BasalFirstPolicyMath'`. Pas de XML : la compilation s’arrête avant les tests. Journal `/tmp/p61-red-compile.log`.
- iOS, compilation de `:plugins:aps:compileTestKotlinIosSimulatorArm64` : `BUILD FAILED in 1m 10s`, `GRADLE_EXIT=1`, les mêmes 11 références non résolues. Pas de XML. Journal `/tmp/p61-ios-red-compile.log`. `iosSimulatorArm64Test` lui-même ne s’exécute pas sur ce Linux.

Rouge, bouchon qui compile et renvoie toujours inactif / projection 0, puis `:plugins:aps:jvmTest --tests app.aaps.plugins.aps.openAPSAIMI.basal.BasalFirstPolicyMathTest` :

- `BUILD FAILED in 1m 9s`, `GRADLE_EXIT=1`
- XML `plugins/aps/build/test-results/jvmTest/TEST-app.aaps.plugins.aps.openAPSAIMI.basal.BasalFirstPolicyMathTest.xml`, horodatage `2026-10-04T13:10:42.362Z`
- `tests="23" skipped="0" failures="14" errors="0"`

Le bouchon est ensuite remplacé par le corps copié (116 lignes identiques au bloc android d’origine, plus la ligne `package`). Le tick android importe `app.aaps.plugins.aps.openAPSAIMI.basal.BasalFirstPolicyMath` et n’a plus d’objet local.

Vert, même filtre, après le déplacement :

- `BUILD SUCCESSFUL in 59s`, `GRADLE_EXIT=0`
- même fichier XML, horodatage `2026-10-04T13:13:41.586Z`
- `tests="23" skipped="0" failures="0" errors="0"`

Compilations après le déplacement, tas Gradle plafonné à 3 Go (le premier `:app:assembleFullDebug` a tué le démon à 8 Go, sans erreur de compilation) :

- `:plugins:aps:compileAndroidMain`, `:plugins:aps:compileKotlinIosSimulatorArm64`, `:plugins:aps:compileTestKotlinIosSimulatorArm64`, `:plugins:aps:compileKotlinIosArm64` : `BUILD SUCCESSFUL in 1m 40s`, `GRADLE_EXIT=0`. Le klib `iosArm64` (`native_targets=ios_arm64`) et le klib `iosSimulatorArm64` contiennent `BasalFirstPolicyMath`. Le klib de test simulateur aussi.
- `:app:assembleFullDebug` : `BUILD SUCCESSFUL in 2m 48s`, `GRADLE_EXIT=0`.

`iosSimulatorArm64Test` ne s’exécute pas sur ce Linux. Le décompte d’exécution iOS vient du workflow `iOS CI` une fois la PR poussée.

## 5. Ce que la tranche 1 ne change pas

- Aucune formule. Le corps copié est celui déjà identique à la ref.
- Aucune écriture pompe, fichier, préférence.
- `APS`, `PUMPCONTROL`, `PUMPDRIVERS` restent `false` dans `IosClientConfig`.
- `HoldAimiEngine.evaluate` renvoie toujours `Hold("ENGINE_NOT_EXTRACTED")`.
- Les 18 échecs Glunovo/Intelligo ne sont pas touchés.
- Les 27 `runCatching` existants ne sont ni étendus ni « réparés ».
