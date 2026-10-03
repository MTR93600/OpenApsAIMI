# Plan de finition Libre 3 et Dexcom ONE+

> **Ne pas merger.** Inventaire et plan de lots seulement. Aucun code produit.
>
> Study `kmp-aimi-migration-study` `16029c9587ad2bf76f0c047d9f98e892b3740a0a` (2026-10-02).
> Référence `dev_OAPSAIMI` `3dd0ca647722db854fc844eb344940bd133f8553` (2026-09-27).
> Décision propriétaire déjà prise : option A du 2026-09-27, commit `022510fb96` (ancêtre de study), ancre [`P4.8-Libre3-Staging-ANCHOR.md`](P4.8-Libre3-Staging-ANCHOR.md). La PR #135 et le commit `0a334a70a7` restent hors de study (`git merge-base --is-ancestor` : non).

## Comment c'est mesuré

- Fichiers : `git ls-tree` des deux tips, puis SHA-256 du blob. **202** fichiers `.kt` / `.java` partagés par nom de fichier sont identiques octet pour octet. Leur contenu est déjà sur study. Le `patch-id --stable` des **70** commits de la ref qui touchent ces arbres et qui ne sont pas ancêtres de study ne correspond à **aucun** des **47** commits study-only du même périmètre. Ne pas rejouer ces commits : le port KMP a recopié le résultat sous un autre patch.
- Lignes citées : lues dans le fichier, ou extraites par `git show` avec numéro de ligne. Pas de numéro inventé.
- iOS : mesuré sur cette VM (Gradle 9.7.1, SDK Android 37). `iosSimulatorArm64Test` n'a pas été lancé ici (hôte Linux). Le run GitHub [37053169679](https://github.com/MTR93600/OpenApsAIMI/actions/runs/37053169679) sur le SHA `16029c9587` est `success`. Les logs des runs rouges de septembre répondent HTTP 410 : le texte historique n'a pas été relu. Non vérifié : comportement sur capteur réel, et une compilation iOS complète relancée en local hors CI.

Les fichiers identiques (crypto d'appairage Libre 3, trames BLE, parseurs de glycémie, KEKS, `Libre3Ingest`, `DexcomOnePlusIngest`, `DexcomOnePlusStaging`, horloges de warm-up) ne sont pas un écart. Ne pas les reporter.

---

## Libre 3

Hôte study : `plugins/source/src/androidMain/.../Libre3NativePlugin.kt`.
Hôte ref : `plugins/source/src/main/.../Libre3NativePlugin.kt` (1223 lignes ; study 504).
Driver : `:plugins:libre3`, `com.android.library` des deux côtés (`plugins/libre3/build.gradle.kts` identique).

### 1. Écarts fonctionnels

| Axe | État | Study | Ref |
|---|---|---|---|
| Appairage (certificat, Phase 5, clés) | Présent | Fichiers `plugins/libre3/.../crypto/**` identiques | idem |
| Lecture BLE (GATT, trames, parseur, session) | Présent | `Libre3BleSession.kt`, `Libre3Gatt*`, `Libre3GlucoseParser.kt` identiques | idem |
| Lecture NFC (commandes, activation) | Partiel | `Libre3NfcSession.kt` n'a pas de veto d'autre slot | Veto L53, L94–100 : si le numéro de série est tenu par l'autre slot, `Libre3NfcFailure.SAME_SENSOR_OTHER_SLOT` (`Libre3NfcCommands.kt` L119). Le constructeur à un seul store reste, L56 |
| Warm-up | Présent, seam KMP | Horloge et mapper identiques. L'écran study mappe vers `CgmWarmupInfo` (`VendorWarmupMapping.kt`, ouvert). Compte à rebours : `commonMain/.../Libre3WarmupCountdown.kt` | L'écran ref passe l'état driver directement à `Libre3WarmupCountdown` |
| Staging | Absent | `Libre3CgmDrivers.kt` L15 : « There is no second driver ». Pas de `staging()`, pas de `Libre3Staging.kt`, pas de `beginStaging` | `Libre3Staging.kt` (154 lignes). `beginStaging` L788. `Libre3CgmDrivers.staging()` et `STAGING_NAMESPACE = "staging"` L24 |
| Promotion | Absent | `promoteStagingToProduction` L188–189 renvoie toujours `Rejected(STAGING_ABSENT)`. Le paramètre `allowEarly` n'est pas lu | Fonction L980–1078. `allowEarly` est accepté et ignoré (KDoc L976–978) : pas de porte de durée |
| Échange de capteur | Partiel | `onSensorChanged` L304 et `logSensorChangeOnce` L329 écrivent `SENSOR_CHANGE` daté de l'activation, sans borne de calibration. `Libre3SensorChange.kt` identique | Les mêmes sites n'écrivent pas `ignoreEntriesBefore` (ancre P4.8). La borne n'est écrite qu'au succès de la promotion, L1055 |
| Calibration | Absent côté Libre 3 | Aucun `ignoreEntriesBefore` dans `Libre3NativePlugin.kt`. La clé est lue par le plugin de calibration déjà porté (P4.5 / P4.8) | Succès seulement, L1055, après `logSensorChangeOnce(staged.activatedAtMs)` L1051 |
| Écrans | Partiel | `Libre3StartActivity`, `Libre3StatusActivity`, `Libre3WarmupActivity` existent. Status : `onCreate` L62, `Libre3StatusScreen` L86, `forgetSensor` L267. Aucun `Presoak`, aucun slot (fichier ouvert) | Start : choix de slot, `onSensorScanned` L148, `sensorHeldByOtherSlot` L182. Status : `PresoakCard` L545. Courbe `Libre3PresoakCurve.kt` absente de study |
| Notifications | Partiel | `Libre3WarmupNotification` : une seule id, ouvre l'écran de warm-up, icône `notif_icon` | Une id par slot (production `4471`, pré-soak `4472`), le pré-soak ouvre le status. Icône `ic_shield` aussi pour la production. `Libre3SessionService.kt` absent de study |
| Persistance | Partiel | `Libre3SensorStore` : un fichier `libre3_sensor_store` (L292), constructeur sans namespace (L85). Pas de `adopt`, pas de clés `slot_*` | Constructeur `(context, namespace)` L91–98. Pré-soak : `libre3_sensor_store_staging` (tests ref). `adopt` L375–394. Clés de slot L431–434 |

Clés study absentes : `Libre3BooleanKey.PresoakEnabled` et `KeepSessionAlive` (ref, défaut `false`, engineering, `exportable = false`). Les chaînes `libre3_presoak_*` et `libre3_keep_session_alive*` ne sont pas dans les `strings.xml` de study.

`Libre3MacArbiter.kt` et `Libre3ScanBudget.kt` : absents. `Libre3ReconnectPolicy.nextDelayMs` study ne prend pas de slot ; ref L44+ ajoute un délai plus lent pour `SensorSlot.STAGING`.

Marqueur `Libre3LogMarkers.PRESOAK` : absent (ref seulement).

### 2. État KMP

Ouverts, pas classés d'après le dossier :

- `commonMain`, sans `import android.*` : `CgmWarmup.kt` (`CgmWarmupPhase`, `CgmWarmupInfo`), `Libre3WarmupCountdown.kt`.
- `commonMain`, Compose Multiplatform (`androidx.compose`, pas le SDK Android) : `CgmWindow.kt` L3, `CgmStateChip.kt`, `CgmWarmupRing.kt`. Ils compilent déjà pour les cibles Apple du module `:plugins:source`.
- `androidMain`, `import android.*` : `Libre3NativePlugin.kt` L3 `android.content.Context`, les trois activités, `Libre3WarmupNotification.kt`, `Libre3BlePermissionHelper.kt`.
- `androidMain`, sans `import android.*` : `Libre3Ingest.kt`, `Libre3WarmupMapper.kt`, `Libre3SensorChange.kt`, `Libre3Availability.kt` (Metro `@Inject`, L10), les clés. Ils sont dans le source set Android parce que le module les y place, pas parce que le fichier importe Android.
- Driver `:plugins:libre3` : 7 fichiers importent Android (ouverts : `Libre3CgmDriverReal.kt` L3 `Context`, `Libre3GattClientAndroid.kt`, `Libre3BleScannerAndroid.kt`, `Libre3NfcReader.kt` L3 `android.app.Activity`, le store). 64 fichiers Kotlin du module n'importent pas Android (crypto, parseurs). Le module reste `com.android.library` : ces fichiers ne produisent pas de klib.

### 3. Étapes irréversibles et vérifications (ref)

La seule écriture Libre 3 de `ignoreEntriesBefore` est L1055. Elle est dans un `runCatching`, comme ONE+ ref L934. Le `runCatching` de la ref avale l'échec de la borne et la fonction renvoie quand même `Ok` L1078, **après** l'échange de capteur. Ce n'est pas un ajout de la PR #135. La PR #135 n'avait que cette écriture.

Avant L1055, dans l'ordre du corps L980–1054 :

Gardes qui retournent sans écrire la borne :

1. L981 `stagingPresent` sinon `STAGING_ABSENT`.
2. L983–984 `stagingStore.loadIdentity()` non nul (KDoc : série, MAC et PIN) sinon `STAGING_ABSENT`.
3. L985–986 `loadSessionKeys()` non nul sinon `STAGING_ABSENT`.
4. L987–991 clé Phase 5 absente : log seulement, pas un refus.
5. L1002 `sensorStore.adopt(staged, keys)` doit renvoyer `true`. KDoc L998–1001 : dernier pas réversible ; `false` signifie qu'aucun octet n'a été commis, retour `STAGING_ABSENT` L1007. `adopt` L375–394 enlève `KEY_LAST_LIFE_COUNT` et `KEY_SENSOR_CHANGE_SERIAL`, ne copie pas le receiver id, `commit()`, retourne le booléen du disque.

Ensuite, avant la borne, des pas déjà difficiles à défaire :

6. L1011 `preferences.put(UseRealSkeleton, true)` dans `runCatching`.
7. L1014 `cancelReconnectWatchdog()`.
8. L1016 l'instance pré-soak est capturée avant l'échange.
9. L1022 `newProductionEpoch()` (coupe le flux de l'ancien capteur, remet le garde de répétition).
10. L1025 `outgoing.disconnect()`.
11. L1029–1031 contexte, watcher production ajouté, watcher pré-soak retiré.
12. L1035 `clearStagingState()` avant l'effacement du fichier.
13. L1040 `stagingStore.clearAll()`.
14. L1044 `Libre3CgmDrivers.promoteStagingInstance()` (L153–161 : rebind store production, l'ancienne instance est rendue pour un `shutdown` hors verrou).
15. L1045–1047 `shutdown` de l'instance retirée, et du stub s'il était l'instance sortante.
16. L1051 `logSensorChangeOnce(staged.activatedAtMs)` — `SENSOR_CHANGE` antidaté à l'activation NFC.
17. L1055 la borne, horloge murale de l'échange, pas l'activation.

Après la borne : `select(useReal = true)`, refresh lifecycle, `refreshSessionService`, reconnect si le lien est down, puis `Ok`.

`beginStaging` L788 refuse si `PresoakEnabled` est faux (L789) ou si le capteur est déjà celui de la boucle (L793). Ce n'est pas la promotion.

Effacement de calibration : la borne ne supprime pas les lignes. Elle les cache pour le fit (`fitCutoff`). La ref ne les efface pas ici. `correctProductionSensorStart` n'existe pas pour Libre 3.

### 4. Lots Libre 3

Aucun lot ne doit appeler `ignoreEntriesBefore` tant que le même lot ne contient pas les gardes 1 à 5 et un test où chaque retour anticipé laisse `calibration_entries_valid_from` inchangée. C'est le motif du rejet de `0a334a70a7`.

Les trois modules drivers restent hors de `commonMain`. Mesuré : les y remettre casse `compileKotlinIosSimulatorArm64`.

---

## Dexcom ONE+

Hôte study : `DexcomOnePlusPlugin.kt` (907 lignes). Ref : 1140 lignes.
`DexcomOnePlusStaging.kt` est identique (SHA-256). Les constantes ci-dessous sont celles du fichier study, donc aussi celles de la ref : `STAGING_MIN_SETTLE_MS` L48 = 12 h, `STAGING_MIN_VALID_EGV` L51 = 6, `STAGING_MAX_EGV_AGE_MS` L58 = 20 min, `canPromoteEarly` L115–119.

### 1. Écarts fonctionnels

| Axe | État | Study | Ref |
|---|---|---|---|
| Appairage KEKS / GATT / code GS1 | Présent | `plugins/libkeks/**` identiques sauf `build.gradle.kts` (study ne force pas Java 17 ; ref `compileOptions` 17). Parseurs de session, auth, backfill identiques | idem |
| Lecture BLE | Présent, sauf calibration | `OnePlusBleSession.kt` : le diff non commentaire est l'ajout ref de `calibrationQueue` et `onCalibrationResult` (défauts). Le cycle EGV de lecture est le même fichier autrement | `OnePlusEgvSession` ref a `sendPendingCalibration` L237 et `awaitCalibrationReply` L291, absents de study |
| NFC | Sans objet | Pas de NFC ONE+ des deux côtés | idem |
| Warm-up | Présent | Horloge, mapper, garde basale : le diff de `DexcomOnePlusWarmupBasalGuard` est `Provider.get()` contre une lambda, et `@Singleton` côté ref. Écran : Metro contre `@AndroidEntryPoint`, et mapping `CgmWarmupInfo` | Même règles de compte à rebours, type driver direct |
| Staging | Présent, registre différent | `beginStaging` L594, `cancelStaging` L697, `resumeStagingSessionIfStored` L650, `OnePlusCgmDrivers.staging()` L54, namespace `"staging"` L29. Instances `by lazy` L17 et L26 | `staging()` aussi, mais `productionReal` / `stagingReal` sont mutables. `promoteStagingInstance` L88–96. KDoc L82–83 : ne pas revenir à `by lazy`, sinon la deuxième promotion ressort une instance dont l'exécuteur est déjà arrêté |
| Promotion | Partiel | Gardes L732–751, puis `ignoreEntriesBefore` L778 dans `runCatching`, puis `Ok` L793. Pas de `promoteStagingInstance`. L763 `driver.shutdown()` avant `adopt`. Bascule par `stagingPublishesToLoop = true` L780 | Gardes L857–875 via `rejectPromotion` L957 (une ligne de log par refus). Le lien vivant est gardé. Échange d'instance L924 **avant** `clearAll` L928. `onWarmup` L942, `refreshSessionService` L946, `resumeStoredSession` si le lien est down L949. Pas de `stagingPublishesToLoop` sur ce chemin |
| Échange de capteur / âge | Partiel | `onSensorSessionStarted` L440 écrit le départ tel quel (`startMs`). `logSensorChange` L459. `ingestToLoop` L421 `saveSessionStartIfAbsent(sample.timestampMs)` sans ancre | `DexcomOnePlusSensorChangeAnchor.resolve` L28 : un `SENSOR_CHANGE` manuel plus tôt, dans les 24 h, remplace l'heure automatique. Appelé au démarrage de session L462 et à l'ingestion L424. `healMissingSensorChange` L1048 réécrit l'événement manquant, une fois par départ |
| Calibration logicielle (borne) | Présent | L778, après les gardes et après `logSensorChange` | L934, même appel, même `runCatching`, après `logSensorChange` L931 |
| Calibration vers le capteur | Absent | Pas de `calibratesInSensor`, pas de `offerCalibration`, pas de `OnePlusCalibrateTx` / `Rx` / `OnePlusCalibrationQueue` | `calibratesInSensor` L234 lit `SendCalibrationToSensor` (défaut false, engineering). `calibrateSensor` L237 : si le switch est off, `NotSupported` ; sinon `driver.offerCalibration`. Le driver refuse un slot dont `storeNamespace != null` (L143), une session down (L149), puis la file L39 : mg/dL hors 40..400 (`OnePlusCalibrateTx` L28–29), âge > 1 h ou dans le futur, ou un envoi déjà en attente. KDoc de la file L16–18 : rien dans ce fichier n'envoie ; le chemin reste derrière le switch tant qu'un ONE+ n'a pas répondu `0x35` |
| Écrans | Partiel | Status a la carte de pré-soak et le bouton de promotion L114, L386, confirmation L276 | En plus : `SensorCalibrationCard` L441, `sensorCalibrationRequest` L503, `CorrectInsertionDateAction` L523 |
| Notifications | Présent, icône différente | Warm-up et service : `notif_icon` | `ic_shield`. Pas de second canal pré-soak distinct dans le diff de notification (le pré-soak ONE+ n'ajoute pas une id L4472) |
| Persistance | Partiel | Store de staging déjà namespacé (le driver study passe `storeNamespace`). `overwriteSessionStart` absent | `OnePlusSensorStore.overwriteSessionStart` : si `epochMs <= 0` return, sinon `putLong` + `apply` |
| Arbitre MAC | Différent | `OnePlusMacArbiter.claim(mac, slot)` clé sur le nom de slot | `claim(mac, owner)` clé sur un jeton d'instance. KDoc ref : le nom de slot survit à la promotion (l'instance promue se logue encore « staging ») et le pré-soak suivant effacerait la protection du capteur qui dose |

### 2. État KMP

Même découpage que Libre 3 pour le compte à rebours : `DexcomOnePlusWarmupCountdown.kt` est en `commonMain`, sans `import android.*` (fichier ouvert). Le mapping driver vers `CgmWarmupInfo` est `VendorWarmupMapping.kt` en `androidMain` (L3–4 importent les deux drivers Android).

`DexcomOnePlusPlugin.kt` L3 `android.content.Context`. `DexcomOnePlusStaging.kt` et `DexcomOnePlusIngest.kt` n'importent pas Android ; ils sont quand même en `androidMain`.

`:plugins:dexcom_oneplus` : 9 fichiers importent Android (`OnePlusCgmDriverReal.kt` L3, `OnePlusBleSession.kt` L3, `OnePlusSensorStore.kt` L3, GATT, scan). 41 fichiers Kotlin du module n'importent pas Android.

`:plugins:libkeks` : Java, BouncyCastle (`build.gradle.kts` L17–18), Lombok, `com.android.library`. Deux fichiers importent Android, sept importent BouncyCastle, quinze n'importent ni l'un ni l'autre. Pas une cible Kotlin/Native en l'état.

Sept activités de `:plugins:source` importent encore `javax.inject.Inject` : `DexcomOnePlusStatusActivity` L63, `DexcomOnePlusWarmupActivity` L65, `DexcomOnePlusStartActivity` L93, `Libre3StatusActivity` L47, `Libre3WarmupActivity` L56, `Libre3StartActivity` L51, `CgmDriverLogActivity` L57. `DexcomOnePlusWarmupBasalGuard.kt` importe `dev.zacsweers.metro.Inject`, pas javax. Le commentaire de `plugins/source/build.gradle.kts` L16–21 dit encore « 14 files » et cite cette garde : le commentaire est périmé. Non vérifié : retirer `includeDagger()` et recompiler `:app`.

### 3. Étapes irréversibles et vérifications (ref)

**A. Borne `ignoreEntriesBefore` à la promotion, L934.**

Gardes, toutes avant toute écriture de preference ou de store (L857–875) :

1. `stagingPresent` sinon `STAGING_ABSENT`.
2. `stagingValidEgvCount < 6` sinon `STAGING_NO_VALID_GLUCOSE`.
3. `stagingStore.loadSessionStart() <= 0` sinon `STAGING_NOT_SETTLED`.
4. Si `allowEarly` : `canPromoteEarly` (au moins 6 EGV, dernier horodaté, âge dans `0 .. 20 min`) sinon `STAGING_NO_RECENT_GLUCOSE`.
5. Sinon : soak `< 12 h` sinon `STAGING_NOT_SETTLED`, puis l'état calculé doit être `READY` sinon `STAGING_NOT_SETTLED`.

`rejectPromotion` L957 logue la raison et renvoie `Rejected`. Il n'écrit rien.

Puis, avant la borne :

6. L883 `UseRealSkeleton = true`.
7. L886 `cancelReconnectWatchdog`.
8. L889–890 capture des deux instances.
9. L892–893 retrait du watcher sortant et `disconnect` (pas `shutdown` ici).
10. L897–898 watcher de production ajouté sur l'instance promue, watcher de pré-soak retiré.
11. L900 `DexcomOnePlusIngest.reset()`.
12. L903 `sensorStore.adopt` de l'identité de staging et du `startMs` vérifié.
13. L907–917 drapeaux mémoire du slot remis à absent, **avant** l'effacement du fichier.
14. L924 `promoteStagingInstance` (rebind vers le fichier de production, L92).
15. L925–926 `shutdown` de l'ancienne instance.
16. L928 `stagingStore.clearAll()`.
17. L931 `logSensorChange(startMs)` — âge daté du début de pré-soak, pas de l'échange.
18. L934 la borne, `System.currentTimeMillis()`, dans `runCatching`.

Study a les gardes 1–5 (L732–751) et la borne (L778), mais pas 7, 8, 10, 14, 15 sous cette forme, et il `shutdown` le driver L763 avant `adopt`. L'effet sur le lien BLE n'a pas été exécuté : non vérifié sur appareil. Le code, lui, n'est pas le même.

**B. Calibration envoyée au capteur.** Irréversible sur le capteur (KDoc de la clé : une calibration acceptée ne s'édite pas et ne se supprime pas). Vérifications avant `offer` : switch engineering ; slot production seulement ; session up ; mg/dL 40..400 ; âge ≤ 1 h ; pas déjà dans le futur ; file vide. Study n'a aucune de ces fonctions. Ne pas les ajouter dans le lot de la borne logicielle.

**C. Correction de date d'insertion, ref L512.** N'efface pas les glycémies de calibration (KDoc L509–510). Elle invalide des `SENSOR_CHANGE`. Avant toute invalidation : `DexcomOnePlusSensorStartCorrection.validate` L36–45 — pas de session (`currentStartMs <= 0`) → `NoSession` ; date dans le futur → `InFuture` ; plus vieux que vie + grâce → `TooOld`. Seulement `Accepted` continue. Ensuite `cleanupFrom` = `min` des deux dates, `freeTimestamp` (pas de 1 s, 60 pas max) parce que la base refuse un doublon même invalidé, puis `overwriteSessionStart`, puis `writeSensorChange`. Absent de study.

**D. Ancre manuelle L28.** N'efface rien. Remplace l'heure auto seulement si un `SENSOR_CHANGE` existe, est strictement plus tôt, et a moins de 24 h. Absent de study. Conséquence : un capteur inséré des heures avant l'appairage est daté de l'appairage. Non vérifié à l'exécution.

### 4. Cause iOS mesurée

Graphe actuel, sans modifier le dépôt :

`:appshell:dependencies --configuration iosSimulatorArm64CompileKlibraries` → `BUILD SUCCESSFUL in 2m 18s`, `GRADLE_EXIT:0`. Les trois projets n'apparaissent pas (comptage 0). Ils sont dans `ANDROID_ONLY_PLUGINS` (`appshell/build.gradle.kts` L43–47) et seulement en `androidMain` (L120). `:plugins:source` les déclare en `androidMain` L102–103.

CI du tip : run `37053169679`, job `ios`, `success`. Le log ne contient ni `dexcom_oneplus`, ni `libre3`, ni `libkeks`.

Reproduction de l'échec, init script hors dépôt qui ajoute les trois projets à `iosSimulatorArm64MainImplementation`, puis `:appshell:compileKotlinIosSimulatorArm64` : `GRADLE_EXIT:1`.

> Could not determine the dependencies of task `:appshell:compileKotlinIosSimulatorArm64`.
> Could not resolve all dependencies for configuration `:appshell:iosSimulatorArm64CompileKlibraries`.
> The consumer was configured to find a library … `org.jetbrains.kotlin.native.target` = `ios_simulator_arm64`. However we cannot choose between the following variants of `:plugins:libre3` (puis dexcom_oneplus, puis libkeks).

Les variantes listées sont celles de `releaseApiElements` (`android-classes-jar`, `jar`, manifest, lint, …). Aucune ne déclare `ios_simulator_arm64`. `debugApiElements` n'apparaît pas dans ce log (comptage 0 ; `releaseApiElements` comptage 30). Le libellé des ancres P4 (« debug et release ») n'a pas été rejoué : logs GitHub 410. La cause mesurée aujourd'hui est l'absence de variante native, pas un défaut des sources Kotlin.

Pour que ces modules compilent pour iOS il faudrait une cible Kotlin qui publie un klib `ios_simulator_arm64` / `ios_arm64`, donc sortir de `com.android.library`. Les fichiers ouverts qui importent `android.bluetooth`, `android.nfc`, `Context` ou `Notification` ne peuvent pas passer tels quels en `commonMain`. `libkeks` (Java + BouncyCastle + Lombok) non plus. ADR G0 : le premier CGM iOS est ONE+ / G7 via `G7SensorKit` ; Libre 3 est la vague 2 (`LibreTransmitter` + CoreNFC). Ce n'est pas un déplacement de ces trois modules.

`iosSimulatorArm64Test` local : non exécuté (Linux). Les tests de module Android de ces plugins : non relancés dans cet inventaire.

---

## Lots ordonnés

Chaque lot est un PR sur `kmp-aimi-migration-study`, un seul, après GO. Ne pas rejouer un commit dont le blob est déjà identique. Ne pas remettre les trois drivers dans `commonMain`.

Gates communes, à lancer pour de vrai : `:app:assembleFullDebug`, `:plugins:source:compileKotlinJvm` (ou la tâche `compileKotlinJvm` du module touché), les tests du module touché. `iosSimulatorArm64Test` seulement si le lot touche un source set déjà Apple (`:plugins:source` common, `:plugins:calibration`). Il est `SKIPPED` hors macOS. Un lot qui ne touche que `:plugins:libre3` ou `:plugins:dexcom_oneplus` n'a pas de `iosSimulatorArm64Test` : ces modules n'ont pas la cible. Le gate iOS d'un tel lot est : le graphe `:appshell:compileKotlinIosSimulatorArm64` **sans** init script reste résoluble (les drivers restent en `androidMain`).

### P5.1 — Libre 3, second store et second driver, sans promotion

Périmètre : namespace de `Libre3SensorStore` (`adopt`, clés `slot_*`, `clearAll`), `Libre3CgmDrivers.staging` / `releaseStagingInstance` / `promoteStagingInstance` (la fonction existe, personne ne l'appelle encore), `Libre3MacArbiter`, `Libre3ScanBudget`, délai de retry par slot, veto NFC `SAME_SENSOR_OTHER_SLOT` et la branche de libellé. Pas de `beginStaging` plugin, pas de `ignoreEntriesBefore`, pas d'`ActivePlugin`.

Dépend de : rien, hors du code déjà identique.

Tests de parité : deux fichiers de préférences distincts ; `adopt` qui échoue au `commit` ne laisse pas un mélange ; le veto NFC n'envoie pas la commande d'activation ; l'arbitre refuse le second claim sur le même MAC.

Décision propriétaire : aucune si le pré-soak reste inerte (pas de bouton, pas d'appel).

### P5.2 — Libre 3, slot inerte : begin, cancel, reprise, collecte

Périmètre : `Libre3Staging.kt`, `beginStaging` / `cancelStaging` / `resumeStagingSessionIfStored`, clés `PresoakEnabled` et `KeepSessionAlive` défaut false, `Libre3SessionService`, notification d'id pré-soak, écran de démarrage à deux slots, carte et courbe de pré-soak. `promoteStagingToProduction` reste le refus `STAGING_ABSENT`. Les lectures de pré-soak ne passent pas par `Libre3Ingest` (la ref le dit : compteur de vie reparti à 0).

Dépend de : P5.1.

Tests : `computeStagingState` (absent / warmup / 5 lectures → ready, pas de porte de 12 h — ref L110–118) ; `beginStaging` refuse si le switch est off ou si c'est le capteur de production ; la collecte n'appelle pas `insertCgmSourceData`.

Décision propriétaire : le switch reste-t-il engineering et off par défaut, comme la ref ?

### P5.3 — Libre 3, promotion entière

Périmètre : le corps ref L980–1078, y compris `ActivePlugin` dans le constructeur **dans ce lot seulement**, `logSensorChangeOnce(activatedAtMs)` puis `ignoreEntriesBefore(System.currentTimeMillis())`. Pas sur le scan, pas sur la lecture.

Dépend de : P5.1 et P5.2.

Tests : chaque garde 1–3 et un `adopt` qui renvoie false laissent la clé de borne inchangée et ne changent pas le driver de production ; le succès écrit la borne **après** le `SENSOR_CHANGE` antidaté ; `allowEarly` ne change pas le résultat (la ref l'ignore).

Décision propriétaire : GO clinique. C'est le lot qui change la source de glycémie Libre 3 et qui pose une borne sans retour arrière automatique.

### P5.4 — ONE+, échange d'instance à la promotion

Périmètre : remplacer le `shutdown` anticipé et `stagingPublishesToLoop` comme bascule de boucle par l'ordre ref (gardes inchangées, lien gardé, `promoteStagingInstance`, plus de `by lazy` sur les deux instances, jeton d'arbitre par instance, `rejectPromotion`, `onWarmup`, `refreshSessionService`, reprise si lien down). La ligne `ignoreEntriesBefore` reste où elle est (après `logSensorChange`), toujours dans `runCatching` comme la ref. Ne pas « corriger » le `runCatching` dans ce lot.

Dépend de : rien côté Libre 3. Le staging ONE+ est déjà là.

Tests : une promotion réussie fait que `default()` renvoie l'instance qui était le pré-soak ; une deuxième promotion ne réutilise pas une instance arrêtée ; chaque refus n'avance pas la borne ; la borne n'est pas écrite si une garde échoue.

Décision propriétaire : GO. Le chemin de promotion ONE+ existe déjà en production sur study et ce lot change l'ordre. Non vérifié ici sur radio.

### P5.5 — ONE+, ancre d'âge et réparation

Périmètre : `DexcomOnePlusSensorChangeAnchor` et `healMissingSensorChange`. N'invalide pas d'événement. N'écrit la réparation qu'une fois par départ de session, et pas si `BgSourceCreateSensorChange` est off (ref L1050).

Dépend de : rien. Peut suivre P5.4 ou le précéder. Ne pas le mélanger à P5.6.

Tests : ancre — pas d'événement → heure auto ; événement plus tôt et < 24 h → cette heure ; événement plus tard ou trop vieux → heure auto. Réparation — second passage pour le même départ n'écrit pas.

Décision propriétaire : la réparation recrée un `SENSOR_CHANGE` absent. La ref ne le recrée pas si l'utilisateur l'a effacé après la première réparation. Confirmer que ce filet est voulu sur study.

### P5.6 — ONE+, correction de date d'insertion

Périmètre : `DexcomOnePlusSensorStartCorrection`, `overwriteSessionStart`, `correctProductionSensorStart`, l'action d'écran L523. Invalide les `SENSOR_CHANGE` de la fenêtre, n'efface pas les calibrations, envoie `EventRefreshOverview`.

Dépend de : P5.5 si l'on veut le drapeau `healedSensorChangeStartMs` cohérent (ref L556). Sinon le lot doit poser ce drapeau lui-même.

Tests : `InFuture`, `TooOld`, `NoSession` n'invalident rien ; `Accepted` invalide puis réécrit ; un horodatage déjà pris avance d'une seconde, pas plus de 60.

Décision propriétaire : GO. C'est un effacement d'événements de thérapie, pas de la calibration.

### P5.7 — ONE+, calibration vers le capteur, switch off

Périmètre : `OnePlusCalibrateTx` / `Rx`, file, `offerCalibration`, `calibratesInSensor`, clé `SendCalibrationToSensor` défaut false, carte de status. Tant que le switch est off, `calibrateSensor` renvoie `NotSupported` et aucune trame `0x34` ne part.

Dépend de : P5.4 recommandé, parce que le refus « pas sur le pré-soak » se fait sur `storeNamespace` vivant, qui change au `rebind`. On peut le poser avant si le test fige le namespace.

Tests : switch off → `NotSupported` ; slot staging → false ; hors 40..400 → false ; file occupée → false ; session down → false.

Décision propriétaire : porter le code éteint, ou attendre qu'un ONE+ ait répondu `0x35`. La ref elle-même dit que l'envoi n'est pas prouvé. Ne pas allumer le switch dans le lot.

### Hors lots de parité

- iOS de ces trois modules. Le graphe actuel compile sans eux. Les y mettre recrée l'échec mesuré. Un CGM iOS est un autre chantier (ADR G0), pas un flip de `android.library`.
- Passer les 7 activités de javax à Metro. Qualité, pas un écart avec la ref (la ref est en Hilt). Ne pas le glisser dans P5.3.
- Icône `notif_icon` contre `ic_shield`. Cosmétique. Décision séparée.
- `runCatching` autour de `ignoreEntriesBefore` : présent des deux côtés sur ONE+, et sur la ref pour Libre 3. Le changer est une décision, pas une fidélité.

---

## Décisions à soumettre au propriétaire avant le premier lot

1. P5.1 peut-il démarrer tout de suite ? Il n'appelle pas la promotion et ne pose pas la borne. Si non, rien ne part.
2. `PresoakEnabled` et `KeepSessionAlive` restent-ils engineering, défaut false, non exportés, comme la ref ?
3. P5.3 (promotion Libre 3 + borne) est-il accepté comme le seul lot autorisé à écrire `ignoreEntriesBefore` pour Libre 3, et seulement après les gardes 1–5 ?
4. P5.4 (changer l'ordre de promotion ONE+ déjà en service) a-t-il le GO, sachant que l'effet radio n'a pas été exécuté ici ?
5. P5.5 : le filet qui réécrit un `SENSOR_CHANGE` manquant est-il voulu ?
6. P5.6 : l'invalidation des `SENSOR_CHANGE` pour corriger la date, sans toucher aux calibrations, est-elle voulue ?
7. P5.7 : porte-t-on la calibration capteur éteinte, ou attend-on une réponse `0x35` réelle avant d'écrire ce code ?
8. Confirmer que ces lots ne cherchent pas à compiler `:plugins:libre3`, `:plugins:dexcom_oneplus` et `:plugins:libkeks` pour iOS. Le graphe iOS du tip est vert sans eux ; les y rattacher pour `ios_simulator_arm64` échoue, mesuré.
9. Le `runCatching` de la borne reste celui de la ref (échec avalé, `Ok` quand même une fois l'échange fait). Le rendre visible serait un écart avec la ref.
