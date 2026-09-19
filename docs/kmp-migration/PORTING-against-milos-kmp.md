# Porter AIMI dans le monde KMP de Milos

**Vérifié :** 2026-09-06 (relecture du code, pas des docs antérieurs)  
**Branche étude :** `kmp-aimi-migration-study` @ `6f66e63565546a243a569a0b6d205fce39ee334f`  
*(merge Milos `kmp` `196179309b` + fallout `9e2b5bd40e` + P0.1 #71)*  
**Référence clinique :** `origin/dev_OAPSAIMI` @ `c5db5a033379390bceb7851ff92004b72ef055bf`  
**Merge-base :** `283a184f60` (2026-08-25)  
**Ahead / behind référence :** étude **984** commits en avance, **2746** en retard.

Ce fichier est le **mode d’emploi** pour chaque lot P0. L’inventaire des lots reste [DELTA-remaining.md](DELTA-remaining.md). L’état figé est [STATUS.md](STATUS.md).

---

## EN — summary (short)

Do **not** cherry-pick AIMI files from `dev_OAPSAIMI` into this tree as-is. Clinical **formulas and control flow** come from the reference. **Wiring** (DI, source sets, prefs, cloud, time/locks/IO, plugin key) comes from Milos’s KMP study tip.

The Milos merge (`196179309b`) did **not** change AIMI clinical files under `plugins/aps/**/openAPSAIMI/**` (empty diff). It *did* change the world AIMI must compile in: `StringKey` no longer has an `exportable` constructor param (`9e2b5bd40e`), cloud upload for iOS/desktop lives on `LocalImportExportPrefs.uploadFileToCloud`, and plugin maps are Metro (`@MetroIntKey`), not Hilt (`@IntKey(225)`).

P0.1 (`PkPdLearnedState` @ `6f66e635`) is the worked example: same learned-state semantics as reference `0761e9c00a`, Metro/`AapsLock`/`kotlin.concurrent.Volatile` instead of javax/`@Synchronized`, plus the study-only `AimiBehaviorProfileSource` constructor argument.

**Rule for every later lot:** clinical behaviour from `origin/dev_OAPSAIMI`; wiring from this study tip. Blind copy fails on Hilt→Metro, `javax.inject`, `titleResId`/`exportable` on `StringKey`, `src/main` vs `commonMain`/`androidMain`, `org.json`, File/System time in `commonMain`, and plugin key **225** (study AIMI is **250**).

---

## 1. Règle d’or

| Ce qui vient de `dev_OAPSAIMI` | Ce qui vient de l’étude / Milos |
|---|---|
| Formules, seuils, ordre des lectures, tags de raison, tests numériques | Metro, source sets, ports, clés, cloud Ktor, horloge, locks, IO |
| Le *quoi* clinique | Le *comment* ça se branche |

**Ne pas** remplacer `DetermineBasalAIMI2.kt` d’un bloc. L’étude a déjà des coutures KMP (ports, `AimiJson`, `PkPdLearnedState`, `AimiStorage`). On greffe les hunks cliniques.

**Ne pas** inventer une deuxième copie d’une classe « parce que la référence en a deux fichiers ». Compter les *instances* et les *constructeurs* sur **cette** pointe.

---

## 2. Ce que le merge Milos a vraiment changé

Vérifié par `git diff 196179309b^1 196179309b -- 'plugins/aps/src/**/openAPSAIMI/**'` : **diff vide**.

Le merge (`196179309b`, 340 fichiers hors AIMI) + le fallout (`9e2b5bd40e`) touchent l’infra :

| Changement | Fichier | Conséquence pour un port AIMI |
|---|---|---|
| `StringKey.exportable` **retiré** du constructeur de l’enum | `core/keys/.../StringKey.kt` | `exportable = false` sur une entrée `StringKey` **ne compile plus**. `OApsAIMIContextStorage` n’a plus ce paramètre (l.227). |
| `uploadFileToCloud` ajouté à l’impl partagée | `implementation/.../LocalImportExportPrefs.kt` l.205–223 | iOS/desktop ont enfin la même API que l’Android `ImportExportPrefsImpl`. AIMI appelle déjà l’interface. |
| Drive via Ktor (plus de `GoogleDriveManager` Android-only) | `implementation/.../cloud/GoogleDriveApi.kt` | Ne jamais rappeler un SDK Drive depuis le plugin. |
| Prefs I/E iOS/desktop bound dans `ClientGraphBindings` | `shared/clientbindings/.../ClientGraphBindings.kt` l.124–170 | **Ne pas** mettre `@ContributesBinding` sur `LocalImportExportPrefs` : Android a déjà `ImportExportPrefsImpl` → collision de graphe. |

P0.1 (`6f66e635`) est le premier lot clinique **après** ce monde. Il compile parce qu’il a été *adapté*, pas copié.

---

## 3. Le monde Milos (à relire avant chaque lot)

### 3.1 Metro, pas Hilt

Référence (`ApsPluginsListModule`) :

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class ApsPluginsListModule {
    @Binds @AllConfigs @IntoMap @IntKey(225)
    abstract fun bindOpenAPSAIMIPlugin(plugin: OpenAPSAIMIPlugin): PluginBase
}
```

Fichier : `origin/dev_OAPSAIMI:plugins/aps/src/main/kotlin/app/aaps/plugins/aps/di/ApsPluginsListModule.kt` l.51–52.

Étude — AIMI **n’est pas** dans `ApsPluginRegistrations` (seulement AMA 210 / SMB 220 / AutoISF 230). Le plugin s’auto-enregistre :

```163:166:plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(250)
@SingleIn(AppScope::class)
open class OpenAPSAIMIPlugin  @Inject constructor(
```

Imports Metro (pas `javax.inject`, pas `dagger.*`) :

```110:115:plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.IntKey as MetroIntKey
import dev.zacsweers.metro.binding
```

| Clé | Plugin | Comment c’est enregistré | Source set |
|---|---|---|---|
| 200 | Loop | `@MetroIntKey` sur la classe | `commonMain` |
| 210 / 220 / 230 | AMA / SMB / AutoISF | `ApsPluginRegistrations` `@IntKey` | `commonMain` |
| 240 | Autotune | `@MetroIntKey` sur la classe | `androidMain` |
| **250** | **AIMI** | `@MetroIntKey` sur la classe | **`androidMain`** |

`225` n’existe **nulle part** dans les `.kt` de l’étude. C’est la clé Hilt de la référence. Les briefs `_docs/kmp/staging/lots/*` qui disent « ne pas ajouter `@IntKey(225)` » parlent de l’ancien plan, pas de la clé live.

**Deux patterns Metro, ne pas les mélanger pour AIMI :**

1. Auto-enregistrement (`@ContributesIntoMap` + `@MetroIntKey`) — Loop, Autotune, AIMI.
2. Objet central `@BindingContainer` — AMA/SMB/AutoISF seulement.

Mettre AIMI dans `ApsPluginRegistrations` **et** garder l’annotation de classe = double entrée / collision.

Qualifier : le commentaire de `ApsPluginRegistrations.kt` l.21–31 est obligatoire. Le seau **non qualifié** est fusionné inconditionnellement par `MetroGraphs.allPlugins`. Un `@APS` « parce que le fichier s’appelle APS » compile, passe les tests, et **retire** les plugins des builds follower.

Bindings de ports AIMI déjà en place (`@ContributesBinding(AppScope::class)` + `@SingleIn`) :

| Port (commonMain) | Impl (androidMain) |
|---|---|
| `AimiStorage` | `utils/AndroidAimiStorage.kt` |
| `AimiBehaviorProfileSource` | `compose/AndroidAimiBehaviorProfileSource.kt` |
| `AimiEmergencySos` | `sos/AndroidAimiEmergencySos.kt` |
| `AimiAuditor` | `advisor/auditor/AuditorOrchestrator.kt` |
| `AimiSmbComparison` | `comparison/AimiSmbComparator.kt` |
| `AimiTpo` | `tpo/TpoOrchestrator.kt` |
| physio / health | `AIMIPhysioDataRepositoryMTR`, `HealthContextRepository` |
| LLM contexte | `context/ContextLLMClient.kt` |

Module manuel (quand le constructeur n’est pas `@Inject` tout seul) : `di/WCycleModule.kt` — `@ContributesTo(AppScope::class)` + `@BindingContainer` + `@Provides`, **pas** un `@Module` Hilt.

**Gate Metro :** `:plugins:aps:compileAndroidMain` peut réussir sans `@ContributesBinding`. Le graphe se voit à `:app:assembleFullDebug`.

Grep AIMI étude : **zéro** `javax.inject` / `dagger.` / `hilt.` sous `openAPSAIMI/`.

### 3.2 Source sets (`plugins/aps`)

`plugins/aps/build.gradle.kts` : `android { }`, `iosArm64()` / `iosSimulatorArm64()`, **`jvm()`** (desktop Compose). **Pas** de dossier `desktopMain` dans ce module. Le `jvm()` est le desktop.

| Source set | Rôle AIMI aujourd’hui | `.kt` AIMI (2026-09-06) |
|---|---|---:|
| `commonMain` | Maths, modèles, PK/PD cœur, physio types, ports | **340** |
| `androidMain` | Plugin, tick, HC, TFLite/ONNX, trainers, Compose, SOS | **109** |
| `iosMain` | **0 AIMI** (seulement `loop/IosLoopNotifier.kt`) | **0** |
| `commonTest` | Tests purs (pas Mockito) | **0 AIMI** |
| `androidHostTest` | JUnit5 / Robolectric / Mockito OK | **13** |

`commonMain` AIMI : **aucun** import `android.*` / `java.io.File` / `javax.inject` / `org.json` (grep 2026-09-06).

Dans `:plugins:aps`, le split plateforme n’est **pas** `expect`/`actual`. Pattern Milos pour AIMI :

- interface + types dans `commonMain`
- impl Android + `@ContributesBinding` dans `androidMain`
- **pas** de stub iOS qui écrit nulle part (`AimiStorage.kt` l.24–27 le dit : sans binding, le graphe iOS casse **fort**)

`expect`/`actual` est réservé à l’infra `:core:*` (ex. `AapsLock`).

Dépendances Android-only (TFLite 2.4.0, ONNX, Health Connect, WorkManager) : bloc `androidMain` de `plugins/aps/build.gradle.kts` l.73–104. Ne pas les monter en `commonMain`.

### 3.3 Prefs / clés

`exportable` vit sur `NonPreferenceKey` (défaut `true`) :

```13:20:core/keys/src/commonMain/kotlin/app/aaps/core/keys/interfaces/NonPreferenceKey.kt
    /**
     * If true, this preference is exported. Set false to keep it out of an export.
     * ...
     */
    val exportable: Boolean
        get() = true
```

`StringKey` étude : **plus** de paramètre `exportable`, **plus** de `titleResId: Int`. Titres = `TextRef` / `KeysStrings.*`.

Référence encore :

```kotlin
enum class StringKey(
    ...
    override val titleResId: Int = 0,
    ...
    override val exportable: Boolean = true,
)
    OApsAIMIContextStorage("aimi_context_storage", "", exportable = false),
```

Étude :

```227:227:core/keys/src/commonMain/kotlin/app/aaps/core/keys/StringKey.kt
    OApsAIMIContextStorage("aimi_context_storage", "", title = KeysStrings.pref_title_oaps_aimi_context_storage),
```

Secrets / non-export : le dire sur l’enum qui **a encore** le champ — `AimiStringKey`, `AimiLongKey`, `LongNonKey` — pas sur `StringKey`.

Exemple secret AIMI :

```41:48:plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/keys/AimiStringKey.kt
    OuraPersonalAccessToken(
        ...
        isPassword = true,
        exportable = false,
    ),
```

P0.1 a ajouté `LongNonKey.OApsAIMIPkpdLearnedStateGeneration` dans `core/keys/.../LongNonKey.kt` l.31. Une clé lue par le tick **et** par un écran Compose doit vivre dans `:core:keys` si les deux modules la voient ; sinon dans `plugins/aps/.../keys/`.

### 3.4 Cloud / Drive

Chaîne unique :

```
AimiBackupManager (androidMain)
  → ImportExportPrefs.uploadFileToCloud(...)
    → CloudStorageManager.getActiveProvider()
      → GoogleDriveProvider + GoogleDriveApi (Ktor)
```

Interface : `core/interfaces/.../ImportExportPrefs.kt` l.116–117.

Impl partagée (iOS/desktop), ajoutée au fallout Milos :

```205:223:implementation/src/commonMain/kotlin/app/aaps/implementation/maintenance/LocalImportExportPrefs.kt
    override suspend fun uploadFileToCloud(...): Boolean {
        val provider = cloudStorageManager.getActiveProvider()
        ...
    }
```

Android : `ImportExportPrefsImpl` (même signature). AIMI référence **appelle déjà** `uploadFileToCloud` — le port cloud n’est pas « réécrire Drive », c’est **rester sur l’interface**.

Chemin : `CloudBackupConstants.CLOUD_PATH_AIMI` = `"/AAPS/export/aimi"`.

`AimiBackupManager` reste `androidMain` (fichiers, cap 16 Mo). L’API cloud est partagée ; le manager de backup ne l’est pas encore.

### 3.5 Temps, locks, IO

**Horloge commonMain** — pas `expect`/`actual`, pas `System.currentTimeMillis()` :

```1:6:plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/AimiWallClock.kt
fun aimiWallClockMs(): Long = Clock.System.now().toEpochMilliseconds()
```

**Lock** — `expect` dans `:core:interfaces` :

```30:37:core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/concurrent/AapsLock.kt
expect class AapsLock() {
    fun lock()
    fun unlock()
}
```

`@Synchronized` / `kotlin.synchronized` = JVM only. `Mutex` coroutines ≠ drop-in (pas réentrant, suspend). P0.1 : `clearLearned()` référence = `@Synchronized` ; étude = `AapsLock.withLock { }`.

**IO AIMI** — port, pas `expect`/`actual` dans `:plugins:aps` :

- contrat : `utils/AimiStorage.kt` + `AimiPath`
- Android : `AndroidAimiStorage` → `AimiStorageHelper` (Documents/AAPS → external app-scoped → internal)
- écritures : `Boolean`, jamais throw (le tick dose ne meurt pas pour un log)
- header CSV : `readFirstLine()`, pas `readLines()` sur un fichier qui grandit toutes les 5 minutes

### 3.6 Chemin iOS (pas « AIMI tourne sur iPhone »)

Aujourd’hui : `iosMain` AIMI = 0. `IosClientConfig.APS` reste `false`. `:plugins:aimi-engine.evaluate()` est un Hold. Un `commonMain` qui compile pour `iosArm64` **n’est pas** un tick iOS.

Vers iOS : même interface, binding iOS **quand** il y a une politique réelle (documents, HealthKit, runtime ML). Pas de no-op qui fait croire que l’apprentissage persiste.

---

## 4. AIMI sur l’étude aujourd’hui

| Zone | commonMain | androidMain | Manque vs référence |
|---|---|---|---|
| Tick `DetermineBasalaimiSMB2` | callees | **18 888 lignes** (`Context`, `File`, `java.time`, `Atomic*`) | 19 312 sur ref (−424) ; instruments P0.3–P0.7 absents |
| Plugin | clés / prefs | `OpenAPSAIMIPlugin` 2 339 lignes, `@MetroIntKey(250)` | cache DynISF encore `LongSparseArray` (P0.2) |
| PK/PD | formules + **`PkPdLearnedState` (P0.1)** | `PkPdIntegration` (3 args) | ctor ref = 2 args ; l’étude a `AimiBehaviorProfileSource` en plus |
| DynISF | `DynIsfTrajectoryTuning`, `DynamicSensitivityPolicy` | cache inline composite time+glucose | **`DynIsfCache`, `CommandedIsf`, `ObservedSensitivityMeter`** |
| ML | `aimiNeuralNetwork` pur | TFLite `AimiModelHandler`, ONNX, trainers WM | `SmbTrainingRowBuffer` |
| Physio / sleep / steps | ~45 fichiers modèles | HC repos, `UnifiedActivityProviderMTR` | Activities permission ; chaîne composite ref ≠ unified study |
| Cloud CSV | — | `AimiBackupManager` → `uploadFileToCloud` | OK côté API |
| UI / prefs | clés, quelques types | Compose Control Center / PKPD | Activities View en staging, pas sur un source set |
| DI | ports | plugin + `@ContributesBinding` + `WCycleModule` | modules Hilt vides de la ref (`AIMIPhysioModuleMTR`, …) à **ne pas** recopier |

`_docs/kmp/staging/openAPSAIMI-android-wip/` : 17 fichiers **hors** source set. `AimiMemberInjectors.kt` est du Hilt. Ne pas les câbler.

En-tête périmé : `ports/AimiCollaboratorPorts.kt` l.36–37 dit encore « no implementation yet ». Les 8 impls Android existent. Ne pas les réécrire.

---

## 5. Matrice de friction (réf → Milos → adaptation → risque)

### 5.1 Tick / `DetermineBasalAIMI2`

| | |
|---|---|
| **Référence** | `src/main/.../DetermineBasalAIMI2.kt`, `@Singleton` + `javax.inject`, types concrets (`AuditorOrchestrator`, `AimiSmbComparator`, `TpoOrchestrator`), `org.json`, `readAimiBehaviorRuntimeProfile()` direct |
| **Étude** | `androidMain`, `@SingleIn(AppScope)`, ports (`AimiAuditor`, `AimiSmbComparison`, `AimiTpo`, `AimiEmergencySos`, `AimiBehaviorProfileSource`), `AimiJson` / kotlinx.serialization, `PkPdLearnedState` injecté |
| **Adaptation** | Greffer les hunks cliniques (P0.3–P0.8) **dans** le fichier étude. Mapper chaque nouveau collaborateur sur un port existant ou en créer un + `@ContributesBinding`. Garder `pkPdLearnedState` dans le ctor. |
| **Risque si copie naïve** | Le fichier ref (19 312) écrase les coutures KMP. `javax.inject` / `org.json` / types concrets ne compilent pas. Perte de P0.1. |

Ctor étude (ne pas le ramener au ctor Hilt) :

```1252:1269:plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt
@SingleIn(AppScope::class)
class DetermineBasalaimiSMB2 @Inject constructor(
    ...
    private val pkPdLearnedState: PkPdLearnedState,
    ...
    private val auditorOrchestrator: AimiAuditor,
    private val behaviorProfileSource: AimiBehaviorProfileSource,
    ...
    private val comparator: AimiSmbComparison,
    ...
    private val tpoOrchestrator: AimiTpo,
```

Champs `@Inject lateinit` encore présents (héritage ref). Metro les remplit. Ne pas les convertir « pour faire joli » dans un lot clinique.

### 5.2 PK/PD (P0.1 **fait**)

| | |
|---|---|
| **Référence** | `PkPdLearnedState` `@Singleton` + `javax.inject` + `@Volatile` + `@Synchronized clearLearned()` ; `PkPdIntegration(preferences, learnedState)` |
| **Étude** | `commonMain` Metro `@SingleIn` + `kotlin.concurrent.Volatile` + `AapsLock` ; `PkPdIntegration(preferences, learnedState, behaviorProfileSource)` |
| **Adaptation (déjà)** | Voir §7.1. Les formules n’ont pas bougé. |
| **Risque si on « resynchronise » depuis la ref** | Perte du 3ᵉ argument → plus de profil comportemental. `@Synchronized` en `commonMain` → ne compile pas sur Native. |

### 5.3 DynISF / `DynIsfCache` (P0.2)

| | |
|---|---|
| **Référence** | `ISF/DynIsfCache.kt` ; plugin : `private val dynIsfCache = DynIsfCache()` ; `put(atMs, isfMgdl, glucoseMgdl)` ; clé **temps** ; pas de `clear()` global à 1000 |
| **Étude** | `LongSparseArray<Double>()` + `Any()` lock ; clé `timestamp - timestamp % 30min + glucose.toLong()` ; `if (size > 1000) clear()` |
| **Adaptation** | Porter `DynIsfCache` en `commonMain` (maths pures + `AapsLock` si le ref synchronise). Remplacer les 4–5 call sites du plugin. **Ne pas** garder la clé composite glucose. Tests ref → `commonTest` si purs, sinon `androidHostTest`. |
| **Risque si copie du plugin ref entier** | Hilt, chemins `src/main`, télémetrie `IsfSourceTelemetry` peut-être absente, perte des coutures Metro du plugin. Si on copie seulement la classe sans changer les call sites : deux caches. |

Preuve étude (clé composite, le bug que P0.2 corrige) :

```930:934:plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        synchronized(dynIsfCacheLock) {
            if (dynIsfCache.size > 1000) dynIsfCache.clear()
            dynIsfCache.put(key, blended)
```

`android.util.LongSparseArray` est Android-only : le **cache type** doit quitter `LongSparseArray` pour pouvoir vivre en `commonMain`. `synchronized` du plugin peut rester tant que le plugin est `androidMain` ; le type partagé doit utiliser `AapsLock`.

### 5.4 ML / TFLite / NN + CSV

| | |
|---|---|
| **Référence** | TFLite + `Environment.getExternalStorageDirectory()`, `SmbTrainingRowBuffer`, trainers dans `src/main` |
| **Étude** | NN pur en `commonMain` ; `AimiModelHandler` + ONNX + WM en `androidMain` ; CSV via `AimiStorage` |
| **Adaptation** | Buffer maths → `commonMain` ; store fichiers → `androidMain` + `AimiStorage`. TFLite **reste** Android (commentaire `build.gradle.kts` : réexprimer le modèle changerait le SMB). |
| **Risque** | `java.io.File` / storage externe en `commonMain`. Monter TFLite en `commonMain`. Stub iOS qui « score » 0. |

### 5.5 Physio / activité / sommeil

| | |
|---|---|
| **Référence** | `AIMICompositeStepsProviderMTR` + `AIMIHealthConnectStepsProviderMTR` + modules Hilt vides |
| **Étude** | `UnifiedActivityProviderMTR` (androidMain) ; modèles physio en `commonMain` ; repos HC bound Metro |
| **Adaptation** | Ne pas recoller la chaîne composite par-dessus `UnifiedActivityProviderMTR`. Porter le *comportement* manquant dans le provider unifié. Activities permission = P2, pas P0. |
| **Risque** | Deux providers injectés, steps à 0, ou module Hilt ressuscité. |

### 5.6 Cloud backup CSV

| | |
|---|---|
| **Référence** | `AimiBackupManager` `@Singleton`, RxJava3, déjà `uploadFileToCloud` |
| **Étude** | Metro `@SingleIn`, `rxBus.toFlow()`, même `uploadFileToCloud` + `CLOUD_PATH_AIMI` |
| **Adaptation** | Presque rien côté API. Si la ref a changé les *fichiers* uploadés, prendre cette liste. Garder le cap taille. |
| **Risque** | Rappeler Drive/Ktor depuis le plugin. Binder `LocalImportExportPrefs` sur Android. |

### 5.7 UI / prefs

| | |
|---|---|
| **Référence** | `titleResId: Int`, `exportable` sur `StringKey`, Activities View |
| **Étude** | `TextRef` / `KeysStrings` / `ApsStrings` ; Compose pour Control Center / PKPD ; Activities en staging |
| **Adaptation** | Toute nouvelle clé : `TextRef`, pas `R.string` dans `:core:keys`. Secrets → `exportable = false` sur l’enum qui le permet. UI View → demander avant de porter (P2). |
| **Risque** | `titleResId` casse `:core:keys`. Secret exporté dans le backup. |

### 5.8 Entrées DI

| | |
|---|---|
| **Référence** | Hilt `@IntKey(225)` dans `ApsPluginsListModule` ; `@Singleton` ; member inject |
| **Étude** | `@MetroIntKey(250)` sur la classe `androidMain` ; `@ContributesBinding` ; `WCycleModule` |
| **Adaptation** | Voir §3.1. Staging `AimiMemberInjectors` = interdit. |
| **Risque** | Plugin invisible (mauvais seau / mauvaise clé) ou doublon 210–250. |

---

## 6. Catalogue « cherry-pick à l’aveugle »

| # | Ce que la ref apporte | Pourquoi ça casse ici |
|---|---|---|
| 1 | `javax.inject.Inject` / `@Singleton` / `@Module` Hilt | Metro : `dev.zacsweers.metro.Inject`, `@SingleIn(AppScope)` |
| 2 | `@IntKey(225)` dans `ApsPluginsListModule` | AIMI = `@MetroIntKey(250)` déjà ; 225 libre / docs morts |
| 3 | `org.json.JSONObject` | `AimiJson` / `JsonObj` / kotlinx.serialization |
| 4 | `titleResId` / `exportable =` sur `StringKey` | Plus dans le ctor (`9e2b5bd40e`) |
| 5 | `PkPdIntegration(prefs, learnedState)` | Étude : 3ᵉ arg `AimiBehaviorProfileSource` |
| 6 | `readAimiBehaviorRuntimeProfile()` depuis Compose | Port `AimiBehaviorProfileSource` |
| 7 | Types concrets dans le tick (Auditor, Comparator, TPO, SOS) | Ports `Aimi*` |
| 8 | `java.io.File` / `Environment` / `System.currentTimeMillis()` en code partagé | `AimiStorage`, `aimiWallClockMs()` |
| 9 | `@Synchronized` sur un type `commonMain` | `AapsLock` (P0.1 l’a déjà fait) |
| 10 | `src/main/kotlin/...` | `commonMain` vs `androidMain` — choisir, ne pas créer `src/main` AIMI |
| 11 | `src/test/...` | `androidHostTest` (Mockito) ou `commonTest` (fakes) |
| 12 | `AIMICompositeStepsProviderMTR` | `UnifiedActivityProviderMTR` |
| 13 | RxJava3 dans backup | Flow déjà en place |
| 14 | `SmbTrainingRowBuffer` / `CommandedIsf` / `DynIsfCache` / … | Fichiers absents : les **créer** adaptés, ne pas importer le tick ref en entier |
| 15 | Staging `AimiMemberInjectors` | Hilt member inject |
| 16 | Stub iOS `AimiStorage` no-op | Interdit par le contrat du port |
| 17 | `exportable = false` oublié sur un token | Backup settings (Oura = le contre-exemple juste) |

---

## 7. Règles concrètes pour les lots P0

**Toujours :** comportement clinique = référence @ `c5db5a0333` (ou le SHA du fichier sur cette branche). Câblage = étude @ `6f66e635` et suivants.

**Toujours compiler :** `:plugins:aps:compileAndroidMain` **et** `:app:assembleFullDebug`. Si le fichier est censé être partagé : `:plugins:aps:compileKotlinIosArm64` aussi.

**Jamais :** changer une formule « en nettoyant ». Extraire ≠ réécrire.

### 7.1 P0.1 — `PkPdLearnedState` (fait, modèle à calquer)

SHA étude : `6f66e635`. Source clinique : `origin/dev_OAPSAIMI` @ `0761e9c00a`.

| Référence | Adaptation étude | Pourquoi |
|---|---|---|
| `@Singleton` + `javax.inject` | `@SingleIn(AppScope::class)` + Metro `@Inject` | Graphes Metro |
| `@Volatile` (javax/JVM) | `kotlin.concurrent.Volatile` | commonMain |
| `@Synchronized fun clearLearned()` | `AapsLock.withLock { }` | `@Synchronized` JVM-only |
| `PkPdIntegration(prefs, learnedState)` | + `behaviorProfileSource: AimiBehaviorProfileSource` | L’étude avait déjà ce port ; la ref lit Compose directement |
| deux *copies* de classe sur la ref | une classe, **deux instances** (plugin + tick) | Le commentaire étude l.13–16 le dit. Ne pas inventer un second fichier. |
| tests `src/test` | `androidHostTest` : `PkPdLearnedStateIntegrationTest` + preset generation | Mockito OK ici |

Fichiers touchés (ne pas les « remettre à la ref ») : `PkPdLearnedState.kt`, `PkPdIntegration.kt`, ctors plugin + tick, `LongNonKey`, `PkpdPresetProfiles.kt`.

### 7.2 P0.2 — `DynIsfCache` (en cours / suivant)

1. Lire `origin/dev_OAPSAIMI:plugins/aps/src/main/kotlin/.../ISF/DynIsfCache.kt` + `DynIsfCacheTest`.
2. Créer `plugins/aps/src/commonMain/.../ISF/DynIsfCache.kt` : mêmes signatures cliniques (`put(atMs, isfMgdl, glucoseMgdl)`, `newest()`, `averageSince`, `isEmpty`, `size`). Remplacer sync JVM par `AapsLock` si le type est commonMain.
3. Dans `OpenAPSAIMIPlugin` **seulement** les call sites cache (l.327+, 582+, 631+, 686–687, 930–934). Ne pas coller le plugin ref.
4. Supprimer `LongSparseArray` + clé `+ glucose.toLong()` + `clear()` à 1000.
5. Tests : corpus ref, source set selon imports.
6. **Ne pas** tirer `CommandedIsf` / `ObservedSensitivityMeter` dans ce lot (P0.3 / P0.4).

### 7.3 P0.3 — `ObservedSensitivityMeter`

Fichier + test depuis la ref. commonMain si zéro Android. Brancher **uniquement** les call sites que la ref a dans le tick/plugin. Le tick étude n’a pas encore ces noms : hunks cibles = ceux de `eb84e078f5`, pas un replace du fichier tick.

### 7.4 P0.4 — `CommandedIsf`

Idem. Ordre « lire l’instrument avant le frein » = comportement clinique. Types + call sites. Pas de rewrite ISF.

### 7.5 P0.5 — `MaxSmbLadder`

L’étude a déjà l’échelle **inline** dans le tick (constantes l.1034–1042, branches l.2600–2664, champ `lastMaxSmbLadderBranch` l.10992). **Comparer** avant d’extraire. Si les nombres matchent : extraire seulement. Si `f03fa321a6` a changé la règle de rise : prendre **cette** règle, ne pas mixer inline + objet.

### 7.6 P0.6 — `HarmoniaCounterfactual` + `InsulinOriginMeter`

Paire `da9bc789ce`. Ports si l’impl tire de l’Android. L’étude a `IobSurveillanceExport` à la place de `InsulinOriginMeter` — **ne pas** supprimer l’existant sans voir si la ref l’a remplacé ou ajouté à côté.

### 7.7 P0.7 — `SmbTrainingRowBuffer`

Maths buffer → `commonMain` si pures. Store fichier → `androidMain` + `AimiStorage`. Pas de `File` en commonMain.

### 7.8 P0.8 — sync tick / plugin

**Le lot dur.** Après P0.2–P0.7 (les types existent).

- Diff hunk-par-hunk `DetermineBasalAIMI2` étude vs ref. Ne pas remplacer le fichier.
- Commits ref à rejouer sur le tick étude (après `f237f2d3d0`, toujours vrais vs `c5db5a0333`) : `eb84e078f5`, `81e370bfbf`, `f03fa321a6`, `da9bc789ce`, `7f8aa0109c`, `db21308e6c`, `02c90656b1`.
- `c5db5a0333` (re-grid glucose) est un changement **loop**, pas un lot AIMI sauf décision explicite.
- Chaque hunk : garder ports, `AimiJson`, `pkPdLearnedState`, `Context` encore dans androidMain.
- Gate : revue des reason-tags + compile app.

---

## 8. Do / Don’t (à copier dans chaque brief de lot)

**Do**

- Lire ce guide + le fichier étude **avant** le fichier ref.
- Grep `@MetroIntKey` / `@IntKey` avant d’attribuer une clé.
- `aimiWallClockMs()`, `AapsLock`, `AimiStorage`, `TextRef`, Metro.
- Tests du sujet dans le **même** lot.
- `:app:assembleFullDebug` dès qu’il y a une nouvelle classe `@Inject` / un port.

**Don’t**

- Cherry-pick `dev_OAPSAIMI` sans adapter.
- `@IntKey(225)`, Hilt, `javax.inject`, `titleResId`, `exportable` sur `StringKey`.
- Remplacer `DetermineBasalAIMI2` ou `OpenAPSAIMIPlugin` entiers.
- Stub iOS silencieux pour le stockage / le ML.
- `expect`/`actual` dans `:plugins:aps` pour un port AIMI (interface + binding).
- Mockito dans `commonTest`.
- `IosClientConfig.APS = true`.
- Recopier `_docs/kmp/staging/openAPSAIMI-android-wip/` dans un source set.
- Rebaser `dev_OAPSAIMI` sur cette branche.

---

## 9. Checklist lot (orchestrateur)

1. SHA étude + SHA du fichier sur `origin/dev_OAPSAIMI` écrits dans le PR.
2. Liste des fichiers étude qui existent déjà (ne pas écraser une couture).
3. Tableau : ligne clinique inchangée / ligne câblage adaptée (comme §7.1).
4. Source set justifié (Android API ? → `androidMain`).
5. Si `@Inject` / port : `@SingleIn` + `@ContributesBinding` ou entrée `*Module` Metro.
6. Clés : pas de `exportable`/`titleResId` sur `StringKey`.
7. Compile `aps` + `assembleFullDebug` (+ iOS klib si commonMain).
8. Aucune formule « améliorée ».

---

## 10. Chemins critiques

| Sujet | Chemin étude |
|---|---|
| Plugin + clé 250 | `plugins/aps/src/androidMain/.../OpenAPSAIMIPlugin.kt` |
| Tick | `plugins/aps/src/androidMain/.../DetermineBasalAIMI2.kt` |
| Enregistrement AMA/SMB/AutoISF | `plugins/aps/src/commonMain/.../di/ApsPluginRegistrations.kt` |
| Ports | `plugins/aps/src/commonMain/.../ports/AimiCollaboratorPorts.kt` |
| Storage | `.../utils/AimiStorage.kt` + `androidMain/.../AndroidAimiStorage.kt` |
| Horloge | `plugins/aps/src/commonMain/.../AimiWallClock.kt` |
| Lock | `core/interfaces/src/commonMain/.../AapsLock.kt` |
| Cloud interface | `core/interfaces/.../ImportExportPrefs.kt` |
| Cloud iOS/desktop | `implementation/src/commonMain/.../LocalImportExportPrefs.kt` |
| Drive Ktor | `implementation/src/commonMain/.../cloud/GoogleDriveApi.kt` |
| Backup AIMI | `plugins/aps/src/androidMain/.../utils/AimiBackupManager.kt` |
| Clés core | `core/keys/src/commonMain/.../StringKey.kt`, `LongNonKey.kt` |
| Clés module | `plugins/aps/src/commonMain/.../keys/AimiStringKey.kt` |
| Gradle source sets | `plugins/aps/build.gradle.kts` |
| P0.1 modèle | `plugins/aps/src/commonMain/.../pkpd/PkPdLearnedState.kt` |
| Clé plugin Hilt (ref) | `origin/dev_OAPSAIMI:.../di/ApsPluginsListModule.kt` |

---

## 11. Incertitudes (ne pas inventer)

1. Les gates assemble / iOS klib / host tests **n’ont pas** été relancées dans la session qui écrit ce guide. Dernière mention écrite verte : 2026-09-03 dans `_docs/kmp/AIMI_PORT_STATE.md` (pré-merge Milos, pré-P0.1).
2. Fidélité numérique des ~421 fichiers homonymes vs `c5db5a0333` : non re-diffée fichier par fichier ici.
3. `MaxSmbLadder` : extract vs changement de règle — comparer avant P0.5.
4. `HarmoniaDecisionEngine` : ce nom n’existe sur aucune pointe ; les tests ref l’utilisent, l’impl est `HarmoniaHarmonizer` / logique dans le tick.
