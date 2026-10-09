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

Destination : `commonMain/.../math/AimiTickPolicyMath.kt`. Le tick android garde la signature et délègue. `isDriftTerminatorCondition` appelle maintenant `aimiFmt1` / `aimiFmt0` ; il reste dans le tick pour cette tranche (il n’a pas été déplacé).

### Tranche 3 — formatage `aimiFmt*` (faite sur `cursor/p63-tick-aimi-fmt-da40`)

465 appels à une seule valeur (`"%.Nf".format`, `String.format("%.Nf", …)`, y compris `Locale.US`) passent par `aimiFmt0/1/2/3`. `aimiFmt3` est nouveau. `Float` est promu en `Double` avant l’arrondi, comme le formateur Java. Un `Double?` nul imprime le mot `null` tronqué à N caractères (`%.2f` → `nu`), ce que `String.format` fait vraiment.

`DecimalFormat` half-up de la valeur binaire exacte ne reproduit pas `String.format` : `1.2345` à trois décimales donne `1.234` d’un côté et `1.235` de l’autre. `aimiFmt*` arrondit le décimal le plus court de `Double.toString`, demi loin de zéro, et garde le signe d’un zéro négatif (`%.0f` de `-0.25` est `-0`). Le test JVM compare les deux sur une grille qui inclut ces cas.

Restent dans le tick, faute d’équivalent exact : les `String.format` à plusieurs arguments (L7052, L7135), `%+.1f` / `%+.2f`, `%2f`, `DecimalFormat("0.##")`, et les `SimpleDateFormat` (tranche horloge).

### Tranche 3 — liste absorbée par la tranche 2 (historique)

Les fonctions listées plus bas étaient l’ancienne tranche 3. Elles sont dans `AimiTickPolicyMath` (tranche 2), sauf `round(): Int`.

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

### Tranche 4 — horloge (faite sur `cursor/p64-tick-kotlinx-clock-da40`)

`Calendar.getInstance()` (heure, minute, seconde, jour de semaine) et `LocalTime.now().hour` passent par `aimiCivilClock` / `aimiLocalHour`. Le bloc circadien capture un seul `aimiWallClockMs()`. `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)` devient `aimiCsvTimestamp`, et `"yyyy-MM-dd HH:mm"` devient `aimiCsvTimestampMinute`. La fenêtre 00:05–00:10 et le midi de la veille (`LocalDate` / `ZoneId`) passent par le même instant.

Deux sites d’horloge restent dans le tick, **identiques à la ref, et ne sont pas corrigés par le portage**. Ce sont des bugs de la ref. L’utilisateur tranchera plus tard.

1. Nom de sauvegarde CSV, `backupFileFor` : `SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())`. `Locale.US` serait grégorien. `Locale.getDefault()` suit le calendrier de la locale. Mesuré pour Bangkok `2026-10-04 12:34:56Z` : un `Calendar` thaï (`th-TH`) écrit l’année 2569, donc `25691004_193456`, alors que l’année grégorienne est 2026. Le portage garde `Locale.getDefault()`.
2. Colonne date des CSV, `logDataMLToCsv` et `logDataToCsv` : `dateUtil.dateAndTimeString(dateUtil.now()).format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"))`. `dateAndTimeString` renvoie déjà une `String` (date courte localisée + heure). `String.format` ignore l’argument `DateTimeFormatter` en trop, donc le motif `MM/dd/yyyy HH:mm` n’est pas appliqué. La colonne reste la date localisée, y compris en 12 h quand l’appareil n’est pas en 24 h. Remplacer par un motif US fixe changerait la colonne. Le portage garde l’appel tel quel.

`java.util.Date` des logs debug n’est pas touché.

Un test JVM compare l’heure, la minute, la seconde, le jour `Calendar` (y compris locale `th-TH`), les deux tampons et le midi de la veille à `java.time` sur UTC, Prague, New York, Bangkok et Auckland, y compris les transitions d’heure d’été.

### Tranche 5 — hypo, SMB finalize, legacy cap (faite sur `cursor/p65-tick-smb-cap-math-da40`)

Blocs entiers, entrées et sorties immuables, le tick android ne garde que les lectures (préférences, observateur d’insuline, thyroïde, trace de binding, export).

- `AimiHypoSmbSafety` : ajustement de sécurité, DIA, hystérésis hypo, intervalle SMB, sport, ajustements spécifiques, plancher SMB, agressivité repas, conditions critiques, délai, effet insuline, tendance, pic dynamique.
- `AimiSmbFinalizeMath` : chaîne de `finalizeAndCapSMB` (priorité repas, réfractaire, absorption, prédiction manquante, throttle, surveillance IOB, `capSmbDose`, tapis rouge, plancher HTR, plafond de contexte, effort, plafond de montée).
- `AimiLegacySmbCapMath` : le relief MaxIOB et le tapis rouge de `runPkpdGuardEndoDampenRedCarpetAndCapSmb`. Ce n’est pas la même formule que la finalize (seuil `<=`, MaxIOB de débridage). Les deux sont gardées telles quelles.
- `AimiPostHypoClassifier` : fenêtre 45 min / repas 30 min. `targetBg` est le membre du tick, passé en argument. La confiance UAM reste une lambda lue seulement dans la branche de récupération.

Quirk de la ref, conservé : `calculateInsulinEffect` passe les pas du membre `recentSteps180Minutes` au facteur de délai, et le paramètre `recentSteps180Min` seulement au facteur d’activité.

Le `%2f`.format du log de throttle PKPD reste un lambda fourni par le tick : `String.format` suit la locale de l’appareil. Les deux sites d’horloge de la tranche 4 ne sont pas touchés.

### Tranche 6 — caches async

`AtomicBoolean` / `AtomicReference`, `determineIoScope` sur `Dispatchers.IO`, les `refresh*Async`. Seam : `AapsLock` + `aapsIoDispatcher`. Ces fonctions lisent `PersistenceLayer`. Elles ne bougent pas avec le calcul.

### Tranche 7 — fichiers CSV

`appendCsvToFile`, `RandomAccessFile`, `storageHelper.getAimiFile` qui renvoie encore un `java.io.File`. `AimiStorage` couvre le journal JSONL, pas encore cette lecture. Pas de dose.

La frontière effet / lecture est dans [`p6-effects-boundary.md`](p6-effects-boundary.md). Le commun renvoie une décision ; la coquille Android exécute `setTempBasal`, les SMB, les préférences, les learners et l’export, dans l’ordre de la référence.

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

## 6. Exécution de la tranche 3

Aucun commit de `dev_OAPSAIMI` n’est rejoué : la ref écrit encore `String.format` / `"%.Nf".format` dans le tick. Le seam est `aimiFmt*`, déjà dans `commonMain`, complété par `aimiFmt3` et par l’arrondi du décimal le plus court.

Avant le code, `:plugins:aps:compileTestKotlinJvm` sur `AimiFmtHalfUpTest` : `GRADLE_EXIT=1`, `Unresolved reference 'aimiFmt3'`. Pas de XML. Journal `/tmp/p63-fmt-red-compile.log`.

Rouge, `aimiFmt3` bouchon qui renvoie `""` :

- XML `TEST-app.aaps.plugins.aps.openAPSAIMI.AimiFmtHalfUpTest.xml`, horodatage `2026-10-04T14:02:40.570Z`
- `tests="3" skipped="0" failures="1" errors="0"` (les deux cas déjà couverts par `aimiFmt0/1` passent ; les trois décimales échouent)

Un second rouge, avec `NumberFormat.withDecimalsHalfUp(3)`, échoue encore : `1.2345` s’imprime `1.234` au lieu de `1.235`. Journal `/tmp/p63-fmt-compile2.log`, 7 tests, 2 échecs.

Vert, après l’arrondi calqué sur `String.format` :

- `AimiFmtHalfUpTest` : `tests="6" skipped="0" failures="0" errors="0"`, horodatage `2026-10-04T14:14:03.204Z`
- `AimiFmtStringFormatParityTest` (JVM, oracle `String.format(Locale.US, …)`) : `tests="2" skipped="0" failures="0" errors="0"`, horodatage `2026-10-04T14:14:03.195Z`

`:plugins:aps:compileAndroidMain`, `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileTestKotlinIosSimulatorArm64` et `:app:assembleFullDebug` : `BUILD SUCCESSFUL in 1m 10s`, `GRADLE_EXIT=0`. Journal `/tmp/p63-gates.log`.

## 7. Exécution de la tranche 4

Aucun commit de `dev_OAPSAIMI` n’est rejoué. La ref lit encore `Calendar` et `java.time`. Le seam est `kotlinx.datetime`, et un test JVM compare les champs à ce JDK.

Avant le code, `:plugins:aps:compileTestKotlinJvm` et `compileTestKotlinIosSimulatorArm64` : `BUILD FAILED in 11s`, `GRADLE_EXIT=1`, références non résolues (`aimiCivilClock`, `aimiCsvTimestampMinute`, `aimiStrictlyInsideLocalWindow`, `aimiYesterdayMiddayEpochMs`) et trop d’arguments pour `aimiCsvTimestamp`. Pas de XML. Journal `/tmp/p64-red-compile.log`.

Rouge, bouchon qui renvoie 0 / `""` / hors fenêtre :

- `AimiCivilClockTest` : `tests="5" skipped="0" failures="5" errors="0"`, horodatage `2026-10-04T14:20:25.374Z`
- `AimiCivilClockJvmParityTest` : `tests="1" skipped="0" failures="1" errors="0"`, horodatage `2026-10-04T14:20:25.313Z`

Vert :

- `AimiCivilClockTest` : `tests="5" skipped="0" failures="0" errors="0"`, horodatage `2026-10-04T14:22:25.880Z`
- `AimiCivilClockJvmParityTest` : `tests="1" skipped="0" failures="0" errors="0"`, horodatage `2026-10-04T14:22:25.875Z`

`:plugins:aps:compileAndroidMain`, `compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `compileTestKotlinIosSimulatorArm64` et `:app:assembleFullDebug` : `BUILD SUCCESSFUL in 1m 12s`, `GRADLE_EXIT=0`. Journal `/tmp/p64-gates.log`.

## 8. Ce qui reste dans le tick après `executeT3cBrittleMode`

Mesuré sur `DetermineBasalAIMI2.kt` après le déplacement de la tête T3C : **17 518 lignes**, **325 fonctions**. Le corps de `executeT3cBrittleMode` est `decideT3cBrittleMode` en `commonMain`. La coquille fait 88 lignes : elle ne calcule plus, elle branche les ports.

Déjà décidées dans `commonMain`, coquille réduite : `applyLegacyMealModes` (47), `buildRbtExtendedSignals` (110), `runAutodriveV3MultiVariableBranch` (166), `executeT3cBrittleMode` (88), `finalizeAndCapSMB` (174), `runRecursiveBeliefResolve`, `resolveMealHyperBasalBoostOutcome`, `runTickClockMaxSmbTirCarbAndGlucoseCopy`, `runT9PhysioEarlyPkpdAndTubeBootstrap`, `runPkpdGuardEndoDampenRedCarpetAndCapSmb`, `buildDecisionContextInitRtSosAndFlatShadow`, `refineRbtMergeAfterDoseSnapshot`, `basalFirstAdaptiveMultiplier`, `runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf`, `runPostSafetyMealFirst30NgrHeadroomBasalSmbStage`, `runInsulinReqActivityRelaxAndMicrobolusStage`, `runCoreDecisionMaxIobExceededTempBasalGate`, `applySafetyPrecautions` (94), `runAdvancedPredictionsAndPredPipePrep` (98), `runPredPipelineSafetyHaltOrReturn` (84), `runT3cBrittleBypassOrReturn` (102), `planHarmoniaProductionBranch` (77), `buildGlobalAimiBasalScheduleBootstrap` (35), `runPostBasalBootstrapIobTickStepsAndHeartRate` (62).

Hors de cette série, par consigne, tailles au brace sur le fichier actuel : `setTempBasal` (353), `runDetermineBasalTickInner` (797), les learners (`applyBasalNeuralLearningAndTraining` 39, `logLearnersHealth` 53, `neuralnetwork5` 50, `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage` 618, les `refresh*Async`), l’export (`runAimiSnapshotMedicalJsonAndHormonitorExportStage` 503) et `toMedicalJson` (354).

Le tableau ci-dessous remplace celui d’après T3C. Les tailles sont le corps réel (accolade ouvrante jusqu’à l’accolade fermante, ou l’expression jusqu’à la ligne blanche), pas « jusqu’à la fonction suivante ». Cette ancienne mesure comptait les champs posés entre deux fonctions : `isAutodriveEngaged` y faisait 123 lignes et n’en fait plus qu’une ; `toMedicalJson` y faisait 754 lignes et son corps en fait 354. `tryMealAdvisor` n’existe plus : le conseiller est `runMealAdvisorDecisionOrReturn` (39 lignes, déjà en `commonMain`). `executeSmbInstruction` (96) ne choisit plus : il appelle `SmbInstructionExecutor.execute`.

Têtes qui choisissent encore un débit, un SMB ou un plafond, 40 lignes et plus. Le tableau d’après `applySafetyPrecautions` en comptait 31, sur un fichier de **16 342 lignes**. Trente et une d’entre elles sont en `commonMain`. `DetermineBasalAIMI2.kt` fait **15 555 lignes**. Le tableau est vide.

En dessous de 40 lignes : le reste des 325, des helpers d’horloge, de log et de lecture. Les compter une par PR gonflerait le chiffre sans avancer le moteur.

`HoldAimiEngine.evaluate` renvoie encore `Hold("ENGINE_NOT_EXTRACTED")`. Le remplacer demande que `runDetermineBasalTickInner` appelle ces têtes depuis `commonMain`, avec `setTempBasal`, la persistance, les notifications, les fichiers et TFLite derrière des ports. Au rythme d’une tête (ou d’une paire couverte par la même trace) par PR, le tableau d’après T3C faisait environ **15 PR**. L’inner tick et le branchement du moteur en font **4 à 6** de plus. Ce décompte a été tenu plus bas, tête par tête. Cette remesure en montrait encore 31 : le décompte « 4 restantes » ne les avait pas retirées. Le tableau courant, plus haut, en garde 7.

Le tableau est pris juste après T3C, avant le conseiller repas. `tryMealAdvisor` et `runMealAdvisorDecisionOrReturn` sont ensuite passés en `commonMain`. Le fichier android retombe à **17 342 lignes**. Ces deux lignes du tableau ne sont plus des corps de décision. Le compte de PR restantes baisse d’une.

`finalizeAndCapSMB` (377 dans le tableau) est ensuite passé en `commonMain`. La coquille fait 174 lignes. `DetermineBasalAIMI2.kt` retombe à **17 129 lignes**. Le compte baisse encore d’une : environ **13 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 18 PR** avant que `HoldAimiEngine` puisse déléguer.

`runRecursiveBeliefResolve` (254 dans le tableau) est ensuite passé en `commonMain` (`decideRecursiveBeliefResolve`). La coquille lit les champs au moment de l’appel. `DetermineBasalAIMI2.kt` retombe à **16 994 lignes**. Le `try/catch` de `contextManager.getSnapshot` garde le repli null et journalise l’échec (`readRbtOptional`, source `contextSnapshot`). Environ **12 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 17 PR** avant que `HoldAimiEngine` puisse déléguer.

`resolveMealHyperBasalBoostOutcome` (233 dans le tableau) est ensuite passé en `commonMain` (`decideMealHyperBasalBoost`). `setTempBasal` reste un port. `DetermineBasalAIMI2.kt` retombe à **16 847 lignes**. Environ **11 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 16 PR** avant que `HoldAimiEngine` puisse déléguer.

`runTickClockMaxSmbTirCarbAndGlucoseCopy` (204 dans le tableau) est ensuite passé en `commonMain` (`decideTickClockMaxSmb`). Le `try/catch` du prébolus legacy garde le repli faux et journalise l’échec. `DetermineBasalAIMI2.kt` retombe à **16 756 lignes**. Environ **10 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 15 PR** avant que `HoldAimiEngine` puisse déléguer.

`runT9PhysioEarlyPkpdAndTubeBootstrap` (198 dans le tableau) est ensuite passé en `commonMain` (`decideT9PhysioEarlyPkpd`). Le `try/catch` du nom de classe G6 garde le repli faux et journalise l’échec. `DetermineBasalAIMI2.kt` retombe à **16 690 lignes**. Environ **9 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 14 PR** avant que `HoldAimiEngine` puisse déléguer.

`runPkpdGuardEndoDampenRedCarpetAndCapSmb` (197 dans le tableau) est ensuite passé en `commonMain` (`decidePkpdGuardEndoDampenRedCarpetAndCapSmb`). Le garde d’absorption, le contexte de correction de repas et les prédictions dose restent des ports appelés à la ligne. Cette tête n’avale pas d’exception. SMB 0, PKPD absent, relief éteint, BG 110 : SMB **0**, intervalle inchangé. `DetermineBasalAIMI2.kt` retombe à **16 598 lignes**. Environ **8 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 13 PR** avant que `HoldAimiEngine` puisse déléguer.

`buildDecisionContextInitRtSosAndFlatShadow` (192 dans le tableau) est ensuite passé en `commonMain` (`decideDecisionContextInitRtSosAndFlatShadow`). Le plafond de nuit, le déclencheur de montée, l’effacement du cache auditeur et l’override capteur plat décident là. SOS, le bilan des learners et l’assemblage de `AimiDecisionContext` restent des ports : le type de contexte reste Android. Cette tête n’avale pas d’exception. BG 160, delta +6, capteur plat : déclencheur `BG_Rise_Fast`, drapeau plat effacé. `DetermineBasalAIMI2.kt` retombe à **16 563 lignes**. Environ **7 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 12 PR** avant que `HoldAimiEngine` puisse déléguer.

`refineRbtMergeAfterDoseSnapshot` (185 dans le tableau, corps réel ~42 lignes) est ensuite passé en `commonMain` (`decideRefineRbtMergeAfterDoseSnapshot`). `minPredictedBgForRbtWiring` et `mergeRbtHyperTrajectoryRelease` restent des ports. Cette tête n’avale pas d’exception. Garde IOB allumée, demande 1,50 U : le SMB passe de **1,20 U à 0,38 U**. La coquille de ports a la même taille que l’ancien corps, donc `DetermineBasalAIMI2.kt` reste à **16 563 lignes**. Environ **6 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 11 PR** avant que `HoldAimiEngine` puisse déléguer.

`basalFirstAdaptiveMultiplier` (185 dans le tableau, corps réel ~12 lignes) est ensuite passé en `commonMain` (`decideBasalFirstAdaptiveMultiplier`). La formule était déjà dans `BasalChannelSafetyGuards`. La préférence et les six drapeaux de repas sont lus à la ligne. Garde-fous allumés, multiplicateur 0,70, pas de repas : le tick conserve **0,70x**. La coquille est un peu plus longue que l’ancien corps : `DetermineBasalAIMI2.kt` passe à **16 566 lignes**. Environ **5 PR** pour les têtes restantes, plus **4 à 6** pour l’inner tick. **Environ 10 PR** avant que `HoldAimiEngine` puisse déléguer.

`runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf` (180 dans le tableau) est ensuite passé en `commonMain` (`decideBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf`). Le lissage, `aimiLocalHour` et `unifiedReactivityLearner.globalFactor` restent des ports. Cette tête n’avale pas d’exception. TDD 35 U, poids 70 kg, grossesse allumée, TIR ≥ 5, accélération, pic d’IOB à 60 min : basale **1,20 U/h**, ISF **30**. `DetermineBasalAIMI2.kt` retombe à **16 468 lignes**. Environ **4 PR** pour les têtes restantes du tableau, plus **4 à 6** pour l’inner tick. **Environ 9 PR** avant que `HoldAimiEngine` puisse déléguer. Les tailles du tableau restent celles d’après T3C.

`runPostSafetyMealFirst30NgrHeadroomBasalSmbStage` (146 dans le tableau) est ensuite passé en `commonMain` (`decideMealFirst30NgrHeadroomBasalSmb`). `setTempBasal`, `nightGrowthResistanceMode.evaluate`, les drapeaux et runtimes de repas, `adaptiveMult`, `maxSMB` et l’écriture de `maxIob` restent des ports appelés à la ligne. `ApsSmbMaxIob` est lu dans le commun, seulement si le headroom NGR s’applique. Cette tête n’avale pas d’exception. Repas manuel, runtime 10 min, basale forcée 2,00 U/h, temp courante 1,00 U/h : TBR **2,00 U/h** pendant 30 min, `overrideSafetyLimits` vrai. La coquille fait 95 lignes. `DetermineBasalAIMI2.kt` retombe à **16 435 lignes**.

`runInsulinReqActivityRelaxAndMicrobolusStage` (150 dans le tableau) est ensuite passé en `commonMain` (`decideInsulinReqActivityRelaxAndMicrobolus`). `activityProtectionMode`, `activityStateIntense`, `maxSMB` et le plancher HTR sont lus à la ligne. `calculateSMBInterval` et `finalizeAndCapSMB` restent des ports. Cette tête n’avale pas d’exception. Protection activité, maxSMB 1,00, demande 2,00 U, amortissement repas-IOB 0,50, microbolus interdit : insulinReq **0,25 U**. La coquille fait 60 lignes. `DetermineBasalAIMI2.kt` retombe à **16 392 lignes**.

`runCoreDecisionMaxIobExceededTempBasalGate` (101 dans le tableau) est ensuite passé en `commonMain` (`decideMaxIobExceededTempBasal`). `setTempBasal`, le comparateur SMB et `logDecisionFinal` restent des ports. L’exportateur d’étude, le contexte d’activité et `adaptiveMult` sont lus à la ligne. Cette tête n’avale pas d’exception. IOB 5 U au-dessus d’un plafond de 2 U, repas relax éteint, glycémie 160 en montée, basale demandée 2,00 : TBR **2,00 U/h** pendant 30 min. La coquille fait 66 lignes. `DetermineBasalAIMI2.kt` retombe à **16 370 lignes**.

`applySafetyPrecautions` (131 dans l’ancien tableau) est ensuite passé en `commonMain` (`decideSafetyPrecautions`). Les poids de repas, la condition critique, le drapeau sport, le cycle, les ajustements, la garde PKPD, le plancher et les plafonds restent des ports appelés à la ligne. `exerciseFlag` et `suspectedLateFatMeal` restent sur la signature Android : le corps ne les lit pas. Cette tête n’avale pas d’exception. Le zéro sport pose toujours `criticalSafetyZeroedThisTick`, pour que le tapis rouge ne rende pas ces unités. Sport et repas, SMB 2,00, dépassement de cible 80 mg/dL : l’échelle de garde 0,70 puis le boost repas 1,10 donnent **1,54 U**. La coquille fait 94 lignes. `DetermineBasalAIMI2.kt` retombe à **16 342 lignes**.

Les quatre têtes que le décompte courant appelait restantes (repas 0–30, insulinReq, gate MAX_IOB, garde sport) sont en `commonMain`. Le tableau remesuré juste après elles en gardait **31**. Ce ne sont pas les 4 à 6 PR de l’inner tick.

`runAdvancedPredictionsAndPredPipePrep` (170 dans le tableau) et `runPredPipelineSafetyHaltOrReturn` (101) sont ensuite passés en `commonMain` (`decideAdvancedPredictionsAndPredPipePrep`, corps 157 lignes ; `decidePredPipelineSafetyHalt`, corps 92). Une même trace les couvre. BG 100, activité IOB 0,20, sensibilité 50 : plancher 39, meilleur terminal environ 43,78, seuil LGS 70, TBR **0,25 U/h** pendant 30 min. L’échec de joignabilité pompe garde le repli faux et ajoute `PRED_PIPE pump reachability failed (<type>): <message> — value false`. La scène verrouillée ne lance pas ce chemin. La halte de sécurité n’avale pas d’exception. Les coquilles font 98 et 84 lignes. `DetermineBasalAIMI2.kt` retombe à **16 286 lignes**.

`runT3cBrittleBypassOrReturn` (166 dans le tableau) est ensuite passé en `commonMain` (`decideT3cBrittleBypass`, corps 154 lignes). Les lectures de champs qui peuvent changer après les prédictions restent des ports à la ligne. Le déploiement physio de l’arbre reste un `runCatching` qui attrape tout `Throwable` et continue sans arbre. L’échec est un `OptionalSignal.Failed` plus la ligne `T3C physioTree failed (<type>): <message> — deploy skipped`, et le port Android garde `aapsLogger.error` `T3C physio/tree deploy failed`. Un instantané qui lance laisse le débit. BG 180, basale profil 1,00, max 3,00 : l’arbre critique limite le pas, TBR **1,30 U/h** pendant 30 min, `units` null. La scène verrouillée ne lance pas ce chemin. La coquille fait 102 lignes. `DetermineBasalAIMI2.kt` retombe à **16 245 lignes**.

`planHarmoniaProductionBranch` (158 dans le tableau) est ensuite passé en `commonMain` (`decideHarmoniaProductionRamp`, corps 162 lignes). Les bloqueurs et l’enregistrement du mode restent des ports. Cette tête n’avale pas d’exception. Demande 2,00 U/h, débit précédent 1,00 : rampe **1,30 U/h**. La coquille fait 77 lignes. `DetermineBasalAIMI2.kt` retombe à **16 168 lignes**.

`buildGlobalAimiBasalScheduleBootstrap` (151 dans le tableau) et `runPostBasalBootstrapIobTickStepsAndHeartRate` (160) sont ensuite passés en `commonMain` (`decideBasalSchedule`, corps 137 lignes ; `decideHeartRateIsf`, corps 125). Une même trace les couvre. Autosens 0,5 sur une basale profil 1,00 : basale **2,00 U/h**. FC 110 sur 10 min, moyenne 60 min 88 : ISF 50 × 0,90 = **45**. Le `catch` de la fenêtre FC garde 80 bpm, `baselineReal` faux, la ligne `aapsLogger` déjà là, et ajoute `HR windows failed (<type>): <message> — averages 80, baseline not real`. La scène verrouillée ne lance pas ce chemin. L’horaire n’avale pas d’exception. Les coquilles font 35 et 62 lignes. `DetermineBasalAIMI2.kt` retombe à **15 979 lignes**.

Métrique, en plus de la longueur du fichier. Les coquilles restent dans `DetermineBasalAIMI2.kt` et masquent l’avancement : le run d’avant retire 363 lignes du fichier pour **827** lignes de décision posées en `commonMain`. Le compte est le corps de chaque `decide*`, accolade ouvrante jusqu’à accolade fermante, le même mètre que le tableau. Ce run-là : 157 + 92 + 154 + 162 + 137 + 125 = **827**. Cumul des 22 fonctions `decide*` à ce point : **2 914** lignes de corps. Têtes du tableau encore Android à ce point : **25**.

`runPkpdPredictionsBgiDeviationAndNoisyTargetsStage` (161) et `computePkpdPredictions` (85) sont ensuite passés en `commonMain` (`decidePkpdPredictionsAndNoisyTargets`, corps 147 lignes ; `decideComputePkpdPredictions`, corps 72). Une même trace les couvre. BG 180, cible profil 100, max 120 : cible de travail **80**, correction **2,00 U**, eventual 195. Les deux têtes n’avalent pas d’exception. `DetermineBasalAIMI2.kt` reste autour de **15 886 lignes** après ce déplacement : les coquilles compensent le corps retiré.

`runSignalPreparationPkpdRuntimePhase` (149) est ensuite passé en `commonMain` (`decideSignalPreparationPkpdRuntime`, corps 155 lignes). BG 100 en baisse, COB 0, DIA appris 5,00 h, pic 75, fusedISF 50 : plafond SMB **2,00 U → 0,00 U** (BASAL-FIRST fragile). Un `computeRuntime` qui lance garde le plafond et continue avec un runtime null, avec la ligne `PKPD runtime failed (<type>): <message> — value null`. La scène verrouillée ne lance pas ce chemin.

`applyTrajectoryAnalysis` (139) est ensuite passé en `commonMain` (`decideTrajectoryAnalysis`, corps 128 lignes). Garde allumée, pertinence 1,0, amortissement SMB 0,50 : plafond **2,00 U → 1,00 U**. Le `catch` vide de la notification critique devient `Trajectory notification failed (<type>): <message> — post skipped`. La scène verrouillée ne lance pas ce chemin. Le `catch` extérieur garde `🌀 Trajectory: ❌ Error`.

`runSmbDecisionLogAdvisorOneShotAndExecuteInstruction` (124) est ensuite passé en `commonMain` (`decideSmbAdvisorOneShot`, corps 94 lignes). Déclencheur repas, pas de verrou d’exercice, blender legacy sauté, insulinReq 1,50 : plafond SMB **30 U**, SMB livré **1,50 U**. `String.format` reste dans le port Android. Cette tête n’avale pas d’exception.

`resolveAndWireRbtLiveTick` (136) est ensuite passé en `commonMain` (`decideRbtLiveTick`, corps 126 lignes). HTR, le résolveur et `mergeRbtHyperTrajectoryRelease` restent des ports à la ligne. Les lectures qui peuvent changer (`bg`, `delta`, l’état latent, l’horloge `dateUtil.now()`) sont relues à la ligne. Cette tête n’avale pas d’exception. Ombre allumée, autorité éteinte, HTR allumé, BG 226 en forte hausse : SMB V3 **0,40 U → 2,00 U**. `DetermineBasalAIMI2.kt` fait **15 901 lignes**.

`applyPkpdAbsorptionGuardOncePerTick` (95) est ensuite passé en `commonMain` (`decidePkpdAbsorptionGuardOncePerTick`, corps 89 lignes). La glycémie, le delta, la prédiction et l’intervalle SMB sont lus à la ligne. Cette tête n’avale pas d’exception. Activité pré-onset, pas de repas, relief éteint : SMB **2,00 U → 1,00 U**, intervalle **+4 min**. `DetermineBasalAIMI2.kt` retombe à **15 842 lignes**.

Corps ajouté : **89**. Cumul des **29** fonctions `decide*` : **3 741** lignes de corps. Têtes du tableau encore Android : **18**.

`mergeRbtHyperTrajectoryRelease` (102) est ensuite passé en `commonMain` (`decideRbtMerge`, corps 85 lignes). Les plafonds physio, d’empilement et de motif, le brouillon de trace et l’indice de courbe sont lus à la ligne. Cette tête n’avale pas d’exception. Autorité dure, demande 2,00 U : SMB V3 **0,40 U → 2,00 U**. `DetermineBasalAIMI2.kt` retombe à **15 784 lignes**.

Corps ajouté : **85**. Cumul des **30** fonctions `decide*` : **3 826** lignes de corps. Têtes du tableau encore Android : **17**.

`planT3cBasalFirstProduction` (111) est ensuite passé en `commonMain` (`decideT3cBasalFirstProduction`, corps 118 lignes). Les gardes qui peuvent changer, le contexte repas, les terminaux hypo et la température courante sont lus à la ligne. Le blocage reste le port Android, avec le même journal. Cette tête n’avale pas d’exception. Canal T3C basal-first éligible, basale profil 1,00 U/h, demande 2,00 U/h : rampe **1,30 U/h** pendant 30 min. `DetermineBasalAIMI2.kt` retombe à **15 741 lignes**.

Corps ajouté : **118**. Cumul des **31** fonctions `decide*` : **3 944** lignes de corps. Têtes du tableau encore Android : **16**.

`runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage` (111) est ensuite passé en `commonMain` (`decideCarbsAdvisorEnableSmbBasalHistoryAndSafety`, corps 87 lignes). `enablesmb` reste un port Android appelé à la ligne. Cette tête n’avale pas d’exception. Glycémie 180, eventual 100, delta combiné faible, SMB toujours permis : facteur de bolus **0,50**, SMB allumé, risque hypo faux. `DetermineBasalAIMI2.kt` passe à **15 747 lignes**.

Corps ajouté : **87**. Cumul des **32** fonctions `decide*` : **4 031** lignes de corps. Têtes du tableau encore Android : **15**.

`runUamModelCalHypoGuardPostHypoAndSetPredictedSmb` (103) est ensuite passé en `commonMain` (`decideUamPostHypoSmb`, corps 92 lignes). Le modèle TFLite reste un port Android. L’hystérésis hypo est le pas déjà commun. Cette tête n’avale pas d’exception. Modèle froid, glycémie 180, rebond suspecté : SMB **0**, pont TBR **1,05 U/h** pendant 5 min. `DetermineBasalAIMI2.kt` retombe à **15 716 lignes**.

Corps ajouté : **92**. Cumul des **33** fonctions `decide*` : **4 123** lignes de corps. Têtes du tableau encore Android : **14**.

`runTherapyHydrateClocksAndExerciseLockoutGate` (102) est ensuite passé en `commonMain` (`decideTherapyExerciseLockout`, corps 56 lignes). L’hydratation thérapie, `setTempBasal` et le journal final restent des ports. Cette tête n’avale pas d’exception. Note sport, glycémie 100, brittle éteint : SMB coupé, TBR **0 U/h** pendant 30 min. `DetermineBasalAIMI2.kt` retombe à **15 712 lignes**.

Corps ajouté : **56**. Cumul des **34** fonctions `decide*` : **4 179** lignes de corps. Têtes du tableau encore Android : **13**.

`publishDoseTerminalAuthorityAndSnapshot` (94) est ensuite passé en `commonMain` (`decidePublishDoseTerminalAuthorityAndSnapshot`, corps 99 lignes). `applyTubeAdvisorFromDoseSnapshot` reste un port Android appelé à la dernière ligne. Cette tête n’avale pas d’exception. Autorité allumée, repas déclaré, eventual PKPD 140, meilleur scénario 180 : eventual publié **180**, minPred **160**. La coquille fait 63 lignes. `DetermineBasalAIMI2.kt` retombe à **15 630 lignes**.

Corps ajouté : **99**. Cumul des **35** fonctions `decide*` : **4 278** lignes de corps. Têtes du tableau encore Android : **12**.

`runBasalDecisionEngineDecideStage` (91) est ensuite passé en `commonMain` (`decideBasalDecisionEngine`, corps 92 lignes). `calculateRate` et `detectMealOnset` restent des ports. Le `catch (Exception)` du cache auditeur garde la confiance 0 et ajoute `Auditor verdict failed (<type>): <message> — confidence 0`. La scène verrouillée ne lance pas ce chemin. Sport, glycémie 180, delta +5, basale profil 1,00 : TBR **1,30 U/h** pendant 30 min. `DetermineBasalAIMI2.kt` retombe à **15 623 lignes**.

Corps ajouté : **92**. Cumul des **36** fonctions `decide*` : **4 370** lignes de corps. Têtes du tableau encore Android : **11**.

`runTrajectoryTightSpiralSafetyBridge` (91) est ensuite passé en `commonMain` (`decideTrajectoryTightSpiralSafetyBridge`, corps 75 lignes). La classification hyper et le plafond SMB restent des ports. L’alignement repas de la spirale est `mealPriorityAlignedForSpiralSmbCap` (12 lignes), aussi appelé par ce plafond. Cette tête n’avale pas d’exception. Spirale serrée, énergie 4,0 U, basale profil 1,00, pas de repas : basale en attente **0,25 U/h** pendant 30 min. `DetermineBasalAIMI2.kt` retombe à **15 584 lignes**.

Corps ajouté : **75**. Cumul des **37** fonctions `decide*` : **4 445** lignes de corps. Têtes du tableau encore Android : **10**.

`enablesmb` (86) est ensuite passé en `commonMain` (`decideEnableSmb`, corps 68 lignes). `convertBG` et le livre de phrases `rh.gs` restent des ports Android à la ligne. La coquille glucides continue d’appeler `enablesmb`. Cette tête n’avale pas d’exception. Glycémie 180, `enableSMB_always` : SMB **allumé**. `DetermineBasalAIMI2.kt` retombe à **15 542 lignes**.

Corps ajouté : **68**. Cumul des **38** fonctions `decide*` : **4 513** lignes de corps. Têtes du tableau encore Android : **9**.

`applyContextModule` (84) est ensuite passé en `commonMain` (`decideApplyContextModule`, corps 90 lignes). `String.format` et `aapsLogger` restent des ports Android. Le `catch (Exception)` garde `⚠️ Context error`, `rT.contextEnabled = false`, et ajoute `Context module failed (<type>): <message> — target null`. La scène verrouillée ne lance pas ce chemin. Activité haute, plafond SMB 0,50 U : SMB **0**, cible **150** mg/dL. `DetermineBasalAIMI2.kt` retombe à **15 512 lignes**.

Corps ajouté : **90**. Cumul des **39** fonctions `decide*` : **4 603** lignes de corps. Têtes du tableau encore Android : **8**.

`updatePhysioLatentState` (75) est ensuite passé en `commonMain` (`decideUpdatePhysioLatentState`, corps 76 lignes). La croyance d’effort, le runtime patient et la session TPO restent des ports Android. `dateUtil.now()` est relu à chaque appel. Cette tête n’avale pas d’exception. Préférences TPO changées, plafond SMB 0,50 U : SMB **1,25 U**, plafond haut **1,25 U**. `DetermineBasalAIMI2.kt` retombe à **15 495 lignes**.

Corps ajouté : **76**. Cumul des **40** fonctions `decide*` : **4 679** lignes de corps. Têtes du tableau encore Android : **7**.

`applyTubeAdvisorFromDoseSnapshot` (74) est ensuite passé en `commonMain` (`decideApplyTubeAdvisorFromDoseSnapshot`, corps 72 lignes). La note JSON du conseiller reste un port Android. Le `catch (Exception)` garde `📐 TUBE-LINE-D4` et ajoute `Tube advisor failed (<type>): <message> — apply skipped`. La scène verrouillée ne lance pas ce chemin. Plafond SMB 2,00 U, échelle 0,50 : SMB **1,00 U**, plafond haut **1,00 U**. La coquille dose terminale continue d’appeler la coquille Android. `DetermineBasalAIMI2.kt` retombe à **15 482 lignes**.

Corps ajouté : **72**. Cumul des **41** fonctions `decide*` : **4 751** lignes de corps. Têtes du tableau encore Android : **6**.

`applyAdvancedPredictions` (71) est ensuite passé en `commonMain` (`decideApplyAdvancedPredictions`, corps 69 lignes). Le COB virtuel non déclaré et la télémétrie de plancher restent des ports Android. Le `catch (Exception)` garde `🔮 PREDICT ERROR` et `printStackTrace`, et ajoute `Advanced prediction failed (<type>): <message> — curves kept`. La scène verrouillée ne lance pas ce chemin. COB déclaré 36 g, glycémie 180 : eventual publié **322**. `DetermineBasalAIMI2.kt` retombe à **15 459 lignes**.

Corps ajouté : **69**. Cumul des **42** fonctions `decide*` : **4 820** lignes de corps. Têtes du tableau encore Android : **5**.

`refreshMealAbsorptionPhase` (69) est ensuite passé en `commonMain` (`decideRefreshMealAbsorptionPhase`, corps 59 lignes). La montée tardive graisses et les lectures de champ restent des ports Android, chacun à sa ligne. Cette tête n’avale pas d’exception. Glycémie 180, delta +6, COB 20 g, intention de repas : phase **FIRST_WAVE**, priorité de livraison **vraie**. La coquille est plus longue que l’ancien corps : `DetermineBasalAIMI2.kt` passe à **15 470 lignes**.

Corps ajouté : **59**. Cumul des **43** fonctions `decide*` : **4 879** lignes de corps. Têtes du tableau encore Android : **4**.

`runPostHypoCompressionAndDriftTerminatorOrReturn` (69) est ensuite passé en `commonMain` (`decidePostHypoCompressionAndDriftTerminatorOrReturn`, corps 61 lignes). La compression, le prédicat de dérive, `finalizeAndCapSMB` et le journal final restent des ports Android. Cette tête n’avale pas d’exception. Glycémie 180 plate, Autodrive allumé, pas de bolus récent : SMB **0,12 U**, plafond SMB **0 → 0,40 U**. `DetermineBasalAIMI2.kt` passe à **15 488 lignes**.

Corps ajouté : **61**. Cumul des **44** fonctions `decide*` : **4 940** lignes de corps. Têtes du tableau encore Android : **3**.

`runEarlyDetermineBasalStages` (65) est ensuite passé en `commonMain` (`decideEarlyDetermineBasalStages`, corps 38 lignes). Le pouls d’étude, la remise à zéro des drapeaux, la copie de profil et l’hydratation repas restent des ports Android. Le `catch (Throwable)` du pouls garde le tick et ajoute `Loop pulse failed (<type>): <message> — pulse skipped`. La scène verrouillée ne lance pas ce chemin. TDD profil 0, préférence 35 U : TDD adopté **35 U**. La coquille est plus longue que l’ancien corps : `DetermineBasalAIMI2.kt` passe à **15 497 lignes**.

Corps ajouté : **38**. Cumul des **45** fonctions `decide*` : **4 978** lignes de corps. Têtes du tableau encore Android : **2**.

`runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep` (65) est ensuite passé en `commonMain` (`decideTrajectoryContextModuleTddIsfAndDynamicPbolusPrep`, corps 54 lignes). L’analyse de trajectoire, le pont spirale, le module contexte et la fusion ISF restent des coquilles Android, chacun à sa ligne. Les lectures de BG, delta et COB sont relues à chaque appel. Cette tête n’avale pas d’exception. Glycémie 120 plate, spirale serrée, ISF 50 : basale différée **0,25 U/h** pendant 30 min, microbolus **0,50 U** et **0,30 U**. La coquille est plus longue que l’ancien corps : `DetermineBasalAIMI2.kt` passe à **15 560 lignes**.

Corps ajouté : **54**. Cumul des **46** fonctions `decide*` : **5 032** lignes de corps. Têtes du tableau encore Android : **1**.

`buildRaObservationState` (40) est ensuite passé en `commonMain` (`decideBuildRaObservationState`, corps 37 lignes). Le masque d’effort, l’heure, les pas, la confiance UAM, la récupération post-hypo et la garde d’aube restent des ports Android, chacun à sa ligne. Le `runCatching` reste `Throwable` : l’échec garde le repli null et ajoute `RA observation failed (<type>): <message> — state null`. La scène verrouillée ne lance pas ce chemin. ISF 50, poids 70 kg : SI estimée **0,005**, amortissement hypo **faux**. `DetermineBasalAIMI2.kt` retombe à **15 555 lignes**.

Corps ajouté : **37**. Cumul des **47** fonctions `decide*` : **5 069** lignes de corps. Le tableau est vide.

`decideCalculateRate` (corps 5), `decideDriftTerminatorCondition` (corps 29) et `decideLateFatProteinRise` (corps 8) passent ensuite en `commonMain`. `profile.copy()` est la copie de la data class, appelée dans `decideEarlyDetermineBasalStages` (corps inchangé, 38). La coquille Android de la montée tardive garde `dateUtil.now()` comme défaut et transmet `nowMs`. `isCompressionProtectionCondition` était déjà `AimiTickPolicyMath`. Les quatre scènes rejouées sont identiques : montée tardive vraie, SMB de dérive **0,12 U**, TBR sport **1,30 U/h** pendant 30 min, TDD adopté **35 U**. `DetermineBasalAIMI2.kt` retombe à **15 536 lignes**.

Corps ajoutés : 5 + 29 + 8 = **42**. Cumul des **50** fonctions `decide*` : **5 111** lignes de corps. Le tableau des têtes reste vide.

Métrique de ce run. Corps ajoutés : 147 + 72 + 155 + 128 + 94 + 126 = **722**. Le corps déjà commun de `decideT3cBrittleBypass` passe de 154 à **170** pour la ligne d’échec de l’arbre. Cumul des **28** fonctions `decide*` : **3 652** lignes de corps. Têtes du tableau encore Android : **19**. L’orchestre de la section 9 commence après ces 19 têtes.

## 9. Plan de `runDetermineBasalTickInner` et de `HoldAimiEngine`

Ce plan est accepté, avec une correction d’ordre. Les têtes de dose du tableau passent en `commonMain` avant l’orchestre. Si l’orchestre bougeait pendant que ces têtes restent des ports Android, le moteur commun n’aurait aucune implémentation sur iOS, et `HoldAimiEngine` ne pourrait déléguer qu’en Android. Le tableau est vide : les **31** têtes sont en `commonMain`. `enablesmb` (`decideEnableSmb`) et `applyTubeAdvisorFromDoseSnapshot` (`decideApplyTubeAdvisorFromDoseSnapshot`) y sont aussi. Ce ne sont plus des ports seulement Android. La première tranche de l’orchestre, `decideDetermineBasalTickPrefix`, est en `commonMain`.

Ce plan est écrit avant tout déplacement de l’inner tick. Le tableau est vide. `HoldAimiEngine.evaluate` continue de renvoyer `Hold("ENGINE_NOT_EXTRACTED")`. `IosClientConfig.APS` reste `false`. La première PR de l’orchestre est le point 1 : le préfixe, de `runEarlyDetermineBasalStages` au retour de `runT3cBrittleBypassOrReturn`. Scènes : exercice et brittle. Le capteur plat est déjà décidé dans `buildDecisionContextInitRtSosAndFlatShadow` ; cette tranche ne fait que transmettre `flatBGsDetected`. Les sept scènes de bout en bout ne sont rejouées qu’à la PR 6. Les numéros de ligne du découpage sont ceux d’avant les déplacements suivants : la tranche se nomme par les fonctions.

`decideDetermineBasalTickPrefix` (corps 91) est cette première tranche. Il appelle les ports au moment de la référence. Le `runCatching` d’export du retour T3C reste dans la coquille Android. Scènes rejouées octet pour octet : exercice, TBR **0 U/h** (BG 100), et brittle, TBR **1,30 U/h** pendant 30 min (cible 2,06, pas 0,30 au-dessus de la basale 1,00). `HoldAimiEngine` et `IosClientConfig.APS` sont inchangés.

`decideDetermineBasalTickSignal` (corps 121) est la deuxième tranche, de `runSignalPreparationPkpdRuntimePhase` au retour de `runHardBrakeLyraOrReturn`. L’échec de `physioAdapter.getLatestSnapshot()` est `OptionalSignal.Failed` : le repli reste `HealthContextSnapshot()`, et la console reçoit `WEARABLE snapshot failed (<type>): <message> — snapshot empty`. Scènes rejouées octet pour octet : repas, SMB **3,30 U** et TBR **2,00 U/h** pendant 30 min, et hypo par le frein, TBR **0 U/h** pendant 30 min (BG 80, delta −2, short −1, long −3). L’horloge de production reste `Clock.System` via `aimiWallClockMs()`. Le tick lit l’heure civile par le port `epochMs()` (`AimiCivilClock`). En production ce port renvoie l’horloge murale. Les tests fixent `injectedTickEpochMs` : les quatre traces déjà verrouillées (exercice, brittle, repas, frein) sont à 05:00 locale, donc `nightbis` vrai, et ne dépendent plus de l’heure du run.

`decideDetermineBasalTickPostHypo` (corps 71) est la troisième tranche, de la classification post-hypo au retour de `runPostHypoCompressionAndDriftTerminatorOrReturn`. `resolveAndWireRbtLiveTick` et `runAutodriveV3MultiVariableBranch` restent des ports appelés à la ligne. `eventualBG`, `bg` et `targetBg` sont lus à la ligne d’usage. La préférence Autodrive est lue deux fois, aux deux conditions RBT. L’heure de `nightbis` vient du port `epochMs()` ; l’horloge de production reste `Clock.System`. Deux scènes du tick Android, qui appelle cette tranche commune, sont verrouillées à un instant fixe :

- 14:00 locale, `nightbis` faux. Le terminateur de dérive s’engage. Le micro-SMB proposé est 0,30 U. Les prédictions du tick complet sont présentes (`pred=Y`), donc la dégradation du test direct `driftTerminatorTapsAMicroSmb` (`pred=N`, **0,12 U**) ne s’applique pas. La finale autorise **0,18 U**. Ligne `TICK` : `smb=0.30->0.18->0.18 src=DriftTerminator`.
- 05:00 locale, `nightbis` vrai. Le terminateur ne retourne pas : `DRIFT_TERMINATOR` est absent, `RT.units` est null, il n’y a pas d’effet SMB. Le tick continue. L’effet pompe est une TBR **1,00 U/h** pendant 30 min. Le journal `SMB result: raw=0.00 -> final=0.35` n’est pas une dose délivrée.

Les traces déjà verrouillées ne sont pas réécrites. Les deux tests nouveaux s’appellent `zzPostHypo…` pour s’exécuter après les traces existantes, et remettent à zéro les hystérésis statiques avant et après. `DetermineBasalAIMI2.kt` fait **15 741 lignes**. Cumul des **53** fonctions `decide*` : **5 394** lignes de corps. `HoldAimiEngine` et `IosClientConfig.APS` sont inchangés.

`decideDetermineBasalTickSchedule` (corps 70) est la quatrième tranche, de `buildGlobalAimiBasalScheduleBootstrap` au retour de la garde PKPD déjà extraite. `sens` est enfilé : PAI voit la valeur d’entrée, les cibles PKPD et l’instruction SMB voient la valeur après le plancher ISF. `basalaimi`, `variableSensitivity` et `intervalsmb` sont lus dans le port, à l’appel. La scène UAM du tick de 05:00 (`nightbis` déjà vrai, sans nouvel appel à `aimiLocalHour()`) rejoue la trace de nuit octet pour octet : `UAM=0.00`, journal `SMB result: raw=0.00 -> final=0.35` non délivré, effet pompe TBR **1,00 U/h** pendant 30 min. Le test direct `uamPostHypoReboundBridgesAShortTempBasal` reste le verrou du pont : SMB prédit **0 U**, TBR **1,05 U/h** pendant 5 min. `DetermineBasalAIMI2.kt` fait **15 798 lignes**. Cumul des **54** fonctions `decide*` : **5 464** lignes de corps. `HoldAimiEngine` et `IosClientConfig.APS` sont inchangés.

`decideDetermineBasalTickMealNgr` (corps 82) est la cinquième tranche, de `runMealHyperBasalBoostTickStage` à `runInsulinReqActivityRelaxAndMicrobolusStage`. Elle peut retourner tôt (boost, arrêt basal hypo, repas 0–30, MAX_IOB). Elle ne lit pas `aimiLocalHour()` : `nightbis` est le drapeau déjà posé par l’horloge du tick. Scènes rejouées : repas 0–30, TBR **2,00 U/h** pendant 30 min, override vrai (`mealFirstThirtyMinutesForcesATempBasal`) ; gate MAX_IOB, TBR **2,00 U/h** (`maxIobWithoutMealRelaxSetsATempBasal`) ; tick de 05:00, `nightbis` déjà vrai, trace de nuit inchangée, effet TBR **1,00 U/h** pendant 30 min. `DetermineBasalAIMI2.kt` fait **15 896 lignes**. Cumul des **55** fonctions `decide*` : **5 546** lignes de corps. `HoldAimiEngine` et `IosClientConfig.APS` sont inchangés.

`decideDetermineBasalTickEngine` (corps 6) est la sixième tranche. `runBasalDecisionEngineDecideStage` entre dans l’orchestre. `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage` et `runAimiSnapshotMedicalJsonAndHormonitorExportStage` restent des ports appelés à la ligne. `nightMode` reçoit `nightbis`, déjà posé par l’horloge du tick. Le tick de 05:00, qui est le seul des verrous de bout en bout à atteindre le moteur, rejoue sa trace : TBR **1,00 U/h** pendant 30 min, pas d’effet SMB. Les scènes qui retournent avant (exercice TBR **0 U/h**, brittle TBR **1,30 U/h** / 30 min, repas SMB **3,30 U** et TBR **2,00 U/h**, frein TBR **0 U/h**, dérive 14:00 SMB **0,18 U**, test direct de dérive **0,12 U**, repas 0–30 TBR **2,00 U/h** override vrai, MAX_IOB TBR **2,00 U/h**, pont UAM SMB prédit **0 U** et TBR **1,05 U/h** / 5 min) restent identiques. `HoldAimiEngine` ne délègue pas : des helpers (a) sont encore « décision iOS requise ». `IosClientConfig.APS` reste `false`. `DetermineBasalAIMI2.kt` fait **15 902 lignes**. Cumul des **56** fonctions `decide*` : **5 552** lignes de corps.

`decideDetectMealOnset` (corps 14) et `decideRecordPkpdSoftFloor` (corps 8) passent en `commonMain`. Le veto `effortSuppressesUndeclaredMeal(): Boolean` et l’écriture `lastPkpdSoftFloorTelemetry` plus sa ligne de journal restent des ports Android, appelés à la ligne. Deux scènes déjà verrouillées sont rejouées : moteur sport, TBR **1,30 U/h** pendant 30 min (`basalDecisionEngineRaisesSportTemp`, le veto est faux sans assessment) ; prédiction basse, TBR **0,25 U/h** pendant 30 min, journal `PKPD_SOFT_FLOOR: raw=39 soft=39` (`lowPredictionRequestsAQuarterBasal`). La valeur iOS du veto et la relecture du plancher restent ouvertes, dans `_docs/kmp/p6-ios-decisions.md`. `DetermineBasalAIMI2.kt` fait **15 893 lignes**. Cumul des **58** fonctions `decide*` : **5 574** lignes de corps. `HoldAimiEngine` et `IosClientConfig.APS` sont inchangés.

`runDetermineBasalTickInner` fait 797 lignes (14849–15645). C’est l’orchestre. Il appelle déjà les têtes extraites. Le déplacer ne change pas une formule de dose. Chaque PR verrouille la trace sur le corps Android du parent, déplace une seule tranche, et rejoue. Si la trace diverge, on s’arrête. Les nombres de dose ne changent pas.

### Découpage en 6 PR

1. **Préfixe, exercice, brittle.** De `runEarlyDetermineBasalStages` au retour de `runT3cBrittleBypassOrReturn` (14849–14953). Scènes : **exercice** (`runTherapyHydrateClocksAndExerciseLockoutGate` retourne avant T3C) et **brittle** (T3C retourne un débit ; le `runCatching` d’export autour de `runAimiSnapshotMedicalJsonAndHormonitorExportStage` reste dans la coquille). Le **capteur plat** est déjà décidé dans `buildDecisionContextInitRtSosAndFlatShadow`. Cette tranche ne fait que transmettre `flatBGsDetected`. La scène plate est rejouée sur le tick complet à la PR 6.

2. **Signal, prédictions, halte, conseiller, frein.** De `runSignalPreparationPkpdRuntimePhase` à `runHardBrakeLyraOrReturn` (14955–15081). Scènes : **repas** par `runMealAdvisorDecisionOrReturn` (les effets déjà verrouillés sur la tête : SMB 3,30 U, TBR 2,00 U/h) et **hypo** si `runPredPipelineSafetyHaltOrReturn` ou le frein retourne.

3. **Post-hypo, terminaux, RBT, Autodrive, compression.** De la classification post-hypo à `runPostHypoCompressionAndDriftTerminatorOrReturn` (15083–15187). Scène : le retour hypo de cette compression, avec un SMB ou un TBR différent de l’entrée. `resolveAndWireRbtLiveTick` et `runAutodriveV3MultiVariableBranch` restent des ports appelés à la ligne.

4. **Horaire, PKPD, UAM, instruction SMB.** De `buildGlobalAimiBasalScheduleBootstrap` à la garde PKPD déjà extraite (15189–15422). Scène : **UAM**, `runUamModelCalHypoGuardPostHypoAndSetPredictedSmb` change le SMB prédit. La **nuit** ne se verrouille pas sur `aimiLocalHour()` : c’est l’horloge murale, et la remplacer par `dateUtil.now()` changerait des doses. La scène nuit affirme le débit quand `nightbis` est déjà vrai en entrée de la tranche NGR, pas l’heure.

5. **Boost repas, arrêt hypo, repas 0–30, MAX_IOB, insulinReq.** De `runMealHyperBasalBoostTickStage` à `runInsulinReqActivityRelaxAndMicrobolusStage` (15426–15572). Scènes : repas 0–30 (TBR **2,00 U/h**, 30 min, override vrai) et gate MAX_IOB (TBR **2,00 U/h**). L’arrêt basal hypo de `runCarbsAdvisorEnableSmbSafetyAndHardHypoBasalStopOrReturn` est l’autre scène hypo si la PR 2 n’a pas de retour.

6. **Moteur basal, coquille learners/export, puis `HoldAimiEngine`.** `runBasalDecisionEngineDecideStage` entre dans l’orchestre. `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage` et `runAimiSnapshotMedicalJsonAndHormonitorExportStage` restent des ports : ils ne bougent pas. Cette PR rejoue les sept scènes de bout en bout sur `runDetermineBasalTick`, pas seulement la tranche. `HoldAimiEngine` délègue seulement après cette PR, et seulement quand chaque helper (a) a une implémentation commune ou un port iOS explicitement décidé. Les 31 têtes du tableau sont déjà en `commonMain`.

### Ce qui reste dans la coquille Android

- `setTempBasal` (353 lignes). Le corps pompe ne bouge pas. L’appel est `AimiEffectSink.setTempBasal`, ou le port de TBR déjà posé sur la tête. En capture, la sonde écrit `EFFECT SetTbr` et retourne avant le corps.
- Les learners : `applyBasalNeuralLearningAndTraining`, `logLearnersHealth`, `neuralnetwork5`, les `refresh*Async`, et tout `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage` (618), qui appelle `basalLearner.process` et le learner de réactivité. L’orchestre l’appelle. Il ne le contient pas.
- L’export : `runAimiSnapshotMedicalJsonAndHormonitorExportStage` (503), y compris le `runCatching` du retour T3C.
- `toMedicalJson` (354).
- `logDecisionFinal` (learners, ligne `TICK` à l’horloge murale, `CACHE TDD24H`).
- `aimiLocalHour()` reste l’horloge murale.

`enablesmb` et `applyTubeAdvisorFromDoseSnapshot` ne figurent pas ici. Leurs décisions sont en `commonMain`. La coquille Android reste le point d’appel de l’orchestre.

### Classement des helpers encore Android

`HoldAimiEngine` ne délègue jamais tant qu’un élément (a) n’a pas d’implémentation commune ou un port iOS explicitement décidé. Les neutres déjà écrits plus bas comptent comme ces décisions de port. Ils ne sont pas une parité. Ils ne doivent pas être activés. Rien de ce mode n’est implémenté ici. `HoldAimiEngine` et `IosClientConfig.APS` restent inchangés.

La taille est le corps, de l’accolade ouvrante à l’accolade fermante. Une coquille qui ne fait que déléguer ne compte pas.

#### (a) Peuvent changer un nombre de dose ou une décision

Déjà en commun, purs :

| Élément | Taille | Dépendances Android | Passage |
|---|---|---|---|
| `decideCalculateRate` | 5 | aucune. `aimiFmt2` et `AimiTickPolicyMath.roundBasal` sont communs | pur. La coquille garde `overrideSafety` faux : le port du moteur basal ne le passe pas |
| `decideDriftTerminatorCondition` | 29 | aucune | pur. La coquille délègue |
| `decideLateFatProteinRise` | 8 | `MealFlags` et le défaut `dateUtil.now()` restent sur la coquille. `nowMs` est explicite | pur |
| `ctx.profile.copy()` | 1, dans `decideEarlyDetermineBasalStages` (38) | aucune. `OapsProfileAimi` est une data class commune | pur |
| `isCompressionProtectionCondition` | 8, dans `AimiTickPolicyMath` | aucune. La coquille est un délégué d’une ligne | déjà commun. Pas redéplacé |
| `aimiLocalHour` | 1, dans `AimiCivilClock` | aucune. La source est `Clock.System`, pas `dateUtil.now()` | déjà commun. Remplacer la source changerait des doses |

Encore Android. Il faut un port. Pas purs, donc pas déplacés dans ce lot :

| Élément | Taille | Dépendances Android | Passage |
|---|---|---|---|
| `detectMealOnset` | 14, en commun (`decideDetectMealOnset`) | le veto `effortSuppressesUndeclaredMeal(): Boolean` reste Android. Il lit `lastEffortAssessment`, les drapeaux de repas et le COB | port Android en place. iOS, approuvé le 2026-10-05 : veto faux sans assessment |
| `estimateUndeclaredVirtualCob` | 38 | préférences, `physioAdapter.getLatestSnapshot`, estimateur continu, journal | iOS, approuvé le 2026-10-05 : 0 g |
| `recordPkpdSoftFloor` | 8, en commun (`decideRecordPkpdSoftFloor`) | la préférence est lue à l’appel. L’écriture de `lastPkpdSoftFloorTelemetry` et la ligne de journal restent Android | iOS, approuvé le 2026-10-05 : stocker, ne pas relire dans le débit |
| `refreshEffortActivityBelief` | 34 | `physioAdapter`, préférences, `dateUtil`, `EffortActivityBelief` | iOS, approuvé le 2026-10-05 : facteur 1,0 |
| `refreshPatientStateRuntime` | 221 | instantané physio, contexte, moteurs patient, `dateUtil` | iOS, approuvé le 2026-10-05 : ne pas appeler |
| session TPO | appel `tpoOrchestrator.onTickStart(dateUtil.now())` | orchestrateur Android, horloge `dateUtil` | iOS, approuvé le 2026-10-05 : ne pas appeler |
| `physioAdapter.getLatestSnapshot` | lecture à la ligne | adaptateur wearable. `OptionalSignal.Failed` + journal, repli `HealthContextSnapshot()` | iOS, approuvé le 2026-10-05 : snapshot vide |
| `resetEarlyScratch` | 27 affectations dans l’adaptateur (le mémo disait 29) | écritures de champs du tick Android | iOS, approuvé le 2026-10-05 : les mêmes affectations |
| `setTempBasal` | 342 | corps pompe, sonde `EFFECT SetTbr` | port décidé : `AimiEffectSink` écrit `TempBasal` ou `Smb` et ne parle pas à une pompe |
| phase learners | 616, dont `applyBasalNeuralLearningAndTraining` 35, `logLearnersHealth` 53, `neuralnetwork5` 44 | fichiers d’apprentissage, `basalLearner.process` | port décidé : départ à froid multiplicateurs **1,0**, gouvernance `WARMUP`, pas `KEEP` |
| TFLite, `AimiUamHandler` | interpréteur Android | fichier `.tflite` | port décidé : modèle absent → `predictSmbUam` **0 U**, `refine` identité, pas d’entraînement |
| persistance pas, FC, bolus | `refreshStepsAsync` 13, `refreshHeartRatesAsync` 13 | `persistenceLayer` | port décidé : listes vides, le même repli qu’une lecture ratée. Ces listes peuvent changer l’ISF |
| `decisionContextForTrigger` | 43 | `AimiDecisionContext` est un type Android | port décidé, non-parité : ne pas instancier. Neutre documenté, pas activé |

Ces ports iOS sont tranchés dans `_docs/kmp/p6-ios-decisions.md`, approuvé le 2026-10-05, mis à jour le 2026-10-09. L’implémentation neutre est derrière `AimiCommonEngineSwitch`, allumé par défaut depuis le 2026-10-09 (décision projet, parité non encore prouvée). `IosClientConfig.APS` reste `false`. Le tick Android n’appelle pas ces ports.

- `detectMealOnset` : le corps est `decideDetectMealOnset`. Le veto Android lit l’assessment. iOS : faux sans assessment.
- `estimateUndeclaredVirtualCob` : iOS rend 0 g.
- `recordPkpdSoftFloor` : le corps est `decideRecordPkpdSoftFloor`. iOS stocke la télémétrie et ne la relit pas dans le débit.
- `refreshEffortActivityBelief` : iOS, facteur 1,0.
- `refreshPatientStateRuntime` : iOS ne l’appelle pas.
- session TPO : iOS ne l’appelle pas.
- `physioAdapter.getLatestSnapshot` : l’échec Android reste `OptionalSignal.Failed` et un snapshot vide. iOS : snapshot vide.
- `resetEarlyScratch` : iOS reprend les affectations de l’adaptateur Android (27 sur cette branche).

### Cycle de vie de `MealAbsorptionPhaseHysteresis`

La fuite d’un test à l’autre vient de la production, pas d’un singleton introduit par le portage. Dans `dev_OAPSAIMI` comme ici, `MealAbsorptionPhaseHysteresis` est un `object` Kotlin. `holdTicksRemaining` et `heldPhase` sont des champs `@Volatile` de ce singleton. `stabilize` les écrit. Un tick suivant, ou une autre instance de `DetermineBasalaimiSMB2` dans le même processus, relit le maintien. `reset()` n’existe dans la ref que dans `MealAbsorptionPhaseEngineTest`, et dans `stabilize` quand le maintien tombe. Le portage n’ajoute que `import kotlin.concurrent.Volatile`, pour compiler en `commonMain`. Le corps est le même. On le garde.

Le même `object` à champs mutables est déjà dans la ref pour `MealAbsorptionMemory`, `EndogenousPhaseHysteresis`, `PhysiologicalPatternHysteresis` et `InsulinSlopePreserveHysteresis`. Parité : le tick iOS n’appelle pas `reset()`. Le maintien traverse le tick, comme sur Android. `iosNeutralResetHysteresisForTest` existe seulement pour les tests. L’interrupteur ne s’allume en production qu’une fois les traces de parité identiques, octet pour octet. `IosClientConfig.APS` reste `false`.

Le test du basal au quart isole son entrée avec `reset()`, parce que la trace verrouillée est le maintien propre (TBR 0,25 U/h). Ce reset ne change pas la production.

#### (b) Présentation ou effets

Ces éléments ne changent pas un nombre de dose. Ils ne bloquent pas la règle ci-dessus par eux-mêmes. Les fichiers qui ne font que garder un journal ou un CSV sont ici. La persistance des pas, de la FC et des bolus est en (a).

| Élément | Taille | Pourquoi (b) |
|---|---|---|
| `convertBG` | 1 | `profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")`. Chaîne d’affichage seulement. Ce n’est pas un nombre de dose |
| `withoutZeros` | 1 | `DecimalFormat("0.##")`. Texte |
| `rh.gs` | livre de phrases | texte montré au raisonnement |
| `String.format` du module contexte | deux lignes d’intervalle | format, sans `Locale` en `commonMain` |
| `noteTubeAdvisorTrace` | 25 | note JSON |
| `aapsLogger` | journal d’échec de contexte | effet |
| `logDecisionFinal` | 5 | journal, ligne `TICK`, `CACHE TDD24H` |
| `toMedicalJson` | 354 | export |
| `runAimiSnapshotMedicalJsonAndHormonitorExportStage` | 497 | export, y compris le `runCatching` du retour T3C |
| pouls d’étude `recordLoopPulse` | appel | télémétrie. L’échec journalise et le tick continue |
| notifications | 3 appels `notificationManager.post` | effet. Le neutre déjà écrit est un no-op |
| fichiers de journal et CSV | stockage d’export | effet. `exists` faux et écritures sans effet sont le neutre déjà écrit |

### Ports

Ceux qui existent restent appelés à la ligne : `AimiEffectSink`, chaque `decide*` déjà extrait, la fabrique de `AimiDecisionContext` (le type reste Android), `readRbtOptional` là où un `catch (Exception)` existe déjà.

Ports nouveaux de l’orchestre, chacun à l’appel actuel, pas hissés au début de la tranche :

- préfixe : `runEarlyDetermineBasalStages`, `bootstrapPhysiologyAfterEarlyTick`, `runRealtimePhysioIobProfilerAndInsulinObserver`, `ensureWCycleAndLoadGlucoseStatusOrAbort`, `runCombinedDeltaByodaAndDynamicPeak`, `buildPreTherapyAutodriveByodaBootstrap`, `refreshPostHypoDeliveryAuthorityForTick`, `decideAuditorIsfFactorForTick`, `runManualMealModesAfterTherapyGate`, `runT3cBrittleBypassOrReturn` (coquille ; la décision est `decideT3cBrittleBypass`) ;
- milieu : `runSignalPreparationPkpdRuntimePhase`, `runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep`, `physioAdapter.getLatestSnapshot` (`OptionalSignal.Failed`, journal du type et du message, repli `HealthContextSnapshot()`), `runAdvancedPredictionsAndPredPipePrep` (coquille ; `decideAdvancedPredictionsAndPredPipePrep`), `runPredPipelineSafetyHaltOrReturn` (coquille ; `decidePredPipelineSafetyHalt`), `runHardBrakeLyraOrReturn`, `runPostAutodrivePostHypoClassification`, `publishDoseTerminalAuthorityAndSnapshot`, `resolveTdd24hForExport`, `resolveAndWireRbtLiveTick`, `applyPendingTrajSpiralBasalIfNotSuppressed`, `runPostHypoCompressionAndDriftTerminatorOrReturn` ;
- SMB : `buildGlobalAimiBasalScheduleBootstrap` (coquille ; `decideBasalSchedule`), `runPostBasalBootstrapIobTickStepsAndHeartRate` (coquille ; `decideHeartRateIsf`), `applyEndoAndActivityAdjustments`, `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity`, `decideAuditorTargetFactorForTick`, le plafond spiral, `runPkpdPredictionsBgiDeviationAndNoisyTargetsStage`, `runUamModelCalHypoGuardPostHypoAndSetPredictedSmb`, `runSmbDecisionLogAdvisorOneShotAndExecuteInstruction`, `applySmbAdvisorExecutionToTickStateAndLog` ;
- fin de dose : `runMealHyperBasalBoostTickStage`, `applyMealHyperBasalBoostOverlayIfNeeded`, `runWCycleIcCsfClampCiAndCarbImpactLogs`, `runCarbsAdvisorEnableSmbSafetyAndHardHypoBasalStopOrReturn`, `runBasalDecisionEngineDecideStage` ;
- queue non déplacée : la phase learners et la phase export.

Une lecture de champ qui peut changer au milieu de la tranche (`cachedPkpdRuntime`, drapeaux de repas, `basal`, `smbToGive`) est un port à la ligne.

### Scènes de bout en bout

Verrouillées sur le parent Android, rejouées après le déplacement, octet pour octet. Les jetons `ts=` deviennent `ts=<clock>`. Aucune scène nuit ne dépend de `aimiLocalHour()`.

| Scène | Nombre qui doit changer | Où |
|---|---|---|
| hypo | le frein ou l’arrêt basal hypo pose un TBR | PR 2 ou la porte glucides de la PR 5 |
| repas | SMB 3,30 U et TBR 2,00 U/h du conseiller ; TBR forcé 2,00 U/h du repas 0–30 | PR 2 et PR 5 |
| UAM | le SMB prédit par `runUamModelCalHypoGuardPostHypoAndSetPredictedSmb` | PR 4 |
| nuit | un débit avec `nightbis` vrai, sans affirmer l’heure murale | PR 5, drapeau déjà fixé |
| exercice | le verrou exercice retourne avant T3C | PR 1 |
| brittle | le retour du bypass T3C, TBR **1,30 U/h** pendant 30 min. L'arbre critique limite le pas à 0,30 au-dessus de la basale 1,00. `executeT3cBrittleMode` reste la tête déjà verrouillée à 2,00 U/h | PR 1 |
| capteur plat | `flatBGsDetected` change la suite par rapport à une montée | tick complet, PR 6 |

### `HoldAimiEngine` sans activer `APS` sur iOS

Aujourd’hui `evaluate` renvoie `AimiTherapyCommand.Hold("ENGINE_NOT_EXTRACTED")`. `AimiEngineTest` verrouille cette raison. Ce run n’y touche pas.

Après la PR 6, `evaluate` appelle l’orchestre commun. Il ne parle pas à une pompe. L’orchestre ne demande une dose que par `AimiEffectSink`.

Android branche le sink sur `setTempBasal` et `applySmbUnits`.

iOS lie déjà `:plugins:aps` et `:plugins:aimi-engine` (`ios/shell/build.gradle.kts`). Le lien n’est pas une boucle. `IosClientConfig.APS` reste `false`. `AAPSCLIENT` reste vrai. Aucun type iOS n’implémente `AimiEffectSink`. Le binding iOS de `AimiEngine` passe un sink qui écrit `AimiTherapyCommand.TempBasal` ou `Smb` sur `AimiTickResult` et retourne. Il n’appelle pas de pompe. Le shell iOS ne démarre pas la boucle.

`AimiTherapyCommand` garde les trois variantes déjà dans `aimi-contracts` : `Hold`, `Smb`, `TempBasal`. La traduction de `RT` vers cette commande se fait dans la coquille, après le retour de l’orchestre, pas dans le calcul de dose.

Sans sink, `evaluate` renvoie encore `Hold`. Il ne construit pas un `DetermineBasalaimiSMB2`.

### Dépendances encore sans implémentation iOS

Le mode commande seule donnera des doses différentes d’Android pour la même scène. Ce n’est pas une parité. Il ne doit pas être activé tant que l’utilisateur ne l’a pas décidé. Rien de ce mode n’est implémenté ici.

Une fois les têtes de dose en `commonMain`, ces dépendances restent des ports. Le moteur commun, en mode commande seule, a besoin pour chacune d’une valeur neutre documentée ou d’une implémentation commune. `IosClientConfig.APS` reste `false`. Rien ici ne branche une pompe.

**TFLite et `neuralnetwork5`.** `AimiModelHandler.ensureInterpreter` retourne null et journalise `model_missing` quand `Documents/AAPS/ml/modelUAM.tflite` est absent. `predictSmbUam` rend alors **0 U** (`uam_unavailable`). `AimiSmbTrainer.refine` rend le SMB candidat inchangé quand aucun modèle n’est en mémoire (`No pre-trained model found`). `maybeTrainAsync` ne doit rien écrire. Mode commande seule : ne pas ouvrir de `.tflite`, ne pas entraîner. Le neutre est le chemin déjà codé pour un fichier manquant : UAM à 0 U, `refine` identité, pas de CSV. `neuralnetwork5` garde ensuite son mélange `0,7 * raffiné + 0,3 * predictedSMB`.

**Persistance.** `refreshStepsAsync` et `refreshHeartRatesAsync` posent une liste vide dans le `catch (Exception)`. Les références de cache (`stepsSnapshotRef`, `heartRatesSnapshotRef`, et les caches bolus du même modèle) partent déjà vides. Mode commande seule : ne pas ouvrir de base. Le neutre est cette liste vide, le même repli qu’une lecture ratée.

**Notifications.** Trois appels à `notificationManager.post` dans le fichier Android. Mode commande seule : le port ne poste rien et ne lance pas. Le neutre est un no-op.

**Fichiers.** `AimiStorage`, le CSV d’entraînement SMB, `externalDir`, le chemin du modèle. Mode commande seule : `exists` est faux, les lectures sont nulles ou vides, les écritures n’ont pas d’effet. On ne touche pas au système de fichiers.

**`AimiDecisionContext`.** `internal data class` encore dans `DetermineBasalAIMI2.kt`. Le moteur commun ne peut pas la construire sur iOS. Il lui faut un instantané commun, avec les champs que l’orchestre lit, ou une fabrique qui le rend. Mode commande seule : identifiant vide, horodatage du tick, déclencheur vide, baseline à zéro, ajustements par défaut, `outcome` null. Tant que cet instantané n’est pas commun, le type reste Android et iOS ne l’instancie pas.

**Learners.** `BasalLearner`, `BasalNeuralLearner` et `UnifiedReactivityLearner` sont déjà en `commonMain`. Leur départ à froid dépend d’un fichier (`aimi_basal_learner.json`, état du réseau, CSV). Mode commande seule : ne pas appeler `process`, `updateLearning`, `processIfNeeded` ni l’entraînement, et ne pas charger ces fichiers. Le neutre est l’état du constructeur sans fichier : multiplicateurs basal **1,0** (`getMultiplier()` 1,0), facteurs neuraux **1,0**, gouvernance **`WARMUP`**, `sampleCount` 0, confiance 0, raison `Warmup`, `globalFactor` **1,0**. `KEEP` arrive seulement après assez d’échantillons. Il n’est pas le départ à froid.
