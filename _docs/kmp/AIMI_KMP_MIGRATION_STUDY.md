# AIMI on Kotlin Multiplatform - study of Milos's work and cost of migrating the AIMI plugin

> **Statut historique — recommandations remplacées.** Ce rapport initial reste utile pour les
> mesures de branches, mais ses recommandations de suppression TFLite, de frontière JSON simple et
> son scénario à 51 semaines-personnes ont été invalidés par l'audit détaillé du code. La décision
> courante se trouve dans [`AIMI_KMP_MIGRATION_BLUEPRINT.md`](AIMI_KMP_MIGRATION_BLUEPRINT.md), avec
> le runtime ML dans l'annexe 5, le cœur dans l'annexe 6, iOS dans l'annexe 7, l'état/replay dans
> l'annexe 8, les surfaces produit dans l'annexe 9 et le séquencement dans
> `AIMI_KMP_IMPLEMENTATION_BACKLOG.md`. Ne pas engager une implémentation à partir des sections 7–8
> de ce fichier seules.

Branch: `kmp-aimi-migration-study`, cut from `kmp` at `4957c26eb8`.
Written 2026-08-25. All numbers were measured on this machine, on the branches named.
Reference document under review: `_docs/KMP_IOS_FEASIBILITY.md` (Milos, 2026-08-05, 2115 lines).

> **Mise a jour 2026-09-02, en complement de la banniere ci-dessus.** L'etat courant du portage est
> dans [AIMI_PORT_STATE.md](AIMI_PORT_STATE.md).
>
> Sur le fond, la banniere ci-dessus a raison et ce rapport avait tort sur un point qui compte :
> l'annexe 5 a releve la signature du modele (`modelUAM.tflite`, 4 504 octets, entree `[1,18]`
> Float32 vers `[1,1]`, une inference reelle active dans `DetermineBasalAIMI2`) et note que
> `AimiNeuralNetwork` a une **architecture differente**. Reexprimer le modele en JSON, comme la
> section 4.3 le proposait, n'aurait donc pas ete un echange a l'identique mais un changement de
> comportement sur un chemin d'estimation SMB. Garder le modele et l'executer via un adaptateur par
> plateforme est le bon choix. Cela augmente le cout, il ne le reduit pas.
>
> Trois faits ont par ailleurs change depuis la redaction, et invalident des affirmations du texte :
>
> 1. **Une app iOS existe.** La section 2.3 dit le contraire. Depuis le 2026-08-29 l'amont a livre un
>    vrai client suiveur : `ios/app/AAPSClient.xcodeproj`, 8 cibles, un `@main` Swift, et
>    `ios/shell` qui heberge le vrai `AapsAppRoot`. Mais `IosClientConfig` fixe `APS = false`,
>    `PUMPCONTROL = false`, `PUMPDRIVERS = false` : c'est un suiveur par conception. Le scenario SC-A
>    a donc ete livre par l'amont ; SC-C, le master sur iPhone, reste intact.
> 2. **Dagger a ete remplace par Metro**, natif KMP. La loi de planification la plus citee ici,
>    "le cout de conversion d'un module est a peu pres son nombre de Dagger", est retiree, et les
>    lignes de cout DI de la section 7 sont surevaluees.
> 3. **Room n'est plus intact** : `:database:impl` et `:database:persistence` sont tous deux dans
>    `migratedModules`.
>
> Ce qui tient : le cadre strategique, l'analyse par paliers de portabilite, le constat sur la
> distribution et le point de securite sur les Critical Alerts.

Supporting detail, same folder:
[annex 1 - audit of Milos's branch](annex-1-milos-kmp-audit.md) ·
[annex 2 - AIMI portability inventory](annex-2-aimi-portability-inventory.md) ·
[annex 3 - iOS platform blockers](annex-3-ios-platform-blockers.md) ·
[annex 4 - branch divergence](annex-4-branch-divergence.md)

---

## 1. Short answer

**Milos's work is sound, and it is much further along than his own document says.** Fourteen modules
are multiplatform, not the three the document claims. **1 002 files / 75 440 lines of code sit in
`commonMain`**, and the entire platform-specific surface under them is **14 `expect` declarations**.
The work was done in **16 calendar days**, alone, and it is behaviour-preserving: tests went from
1 212 to 1 256, and only 22 production files were genuinely deleted, each with a stated reason.

**The single most important fact for us is already in the tree.** On `kmp`,
`plugins/aps/src/commonMain/` contains `DetermineBasalSMB.kt`, `DetermineBasalAMA.kt` and
`DetermineBasalAutoISF.kt`, and the module declares `iosArm64()`. **The AAPS dosing algorithm
compiles for iOS today.** `DetermineBasalAIMI2.kt` is the same shape - one big pure-math file - so
AIMI's core has a proven path, not a hypothetical one.

**AIMI is large but not hostile.** 441 files / 102 354 lines in `src/main`. Of those, 49 % are pure
Kotlin already, and the Android coupling is shallow rather than deep: across the 80 files that do
touch Android, only **2 781 lines (6 %)** actually name an Android symbol. `DetermineBasalAIMI2.kt`
is 18 886 lines with **244 Android-touching lines (1.3 %)**, and its `Context` exists almost entirely
to serve 133 `context.getString(R.string.…)` calls. Replace that with a string provider and the
biggest file in the codebase becomes a mechanical rewrite.

**The machine-learning story is not the blocker, contrary to first impression.** AIMI's inference and
its on-device training are **pure Kotlin** (`aimiNeuralNetwork.kt`, 613 lines, Adam optimiser). The
TensorFlow Lite dependency is **one file, 297 lines, one inference call site**, against a model of
**4 232 bytes**. The three shipped ONNX models are **~440-byte placeholder stubs** next to a
`PLACE_ONNX_MODELS_HERE.txt`. The real native dependency is **Health Connect** (6 files / 2 208
lines), which needs a HealthKit rewrite.

**What is genuinely not done, and what the reference document gets wrong.** Room is untouched -
`:database:impl` is still a plain Android library on `room-rxjava3` + Gson + Hilt + `kotlin.reflect`,
which is a hard Kotlin/Native wall; the document's claim that "step 5 is done" is not supported by the
code. There is **no iOS app at all**: no `iosApp/`, no `.xcodeproj`, no XCFramework. And **about 81 of
the 85 shared KLOC have never executed on Kotlin/Native** - only `:core:data` has a `commonTest`.

**The document's central strategic warning is also wrong, and this matters.** It says iOS cannot
support a 5-minute loop, so "the real answer is push (APNs), which changes the design". It is not.
Loop, Trio and iAPS have closed a 5-minute loop on iPhone since 2018 using the `bluetooth-central`
background mode with CoreBluetooth **state restoration**: the CGM acts as a heartbeat and wakes the
app on every BLE notify. Trio's own documentation states the constraint plainly - *"if your CGM does
not supply a heartbeat, the app will stop automatically running when it is not open"*. The blocker is
therefore not "iOS forbids background loops"; it is "the loop needs a directly connected BLE CGM".

**Recommendation: do not rebuild AAPS on iOS. Ship the AIMI engine as a Kotlin/Native framework.**
Rebuilding the whole master app on iPhone is a 3-to-5 person-year project that ends up where Trio
already is, minus five features, carrying a second and less-tested implementation of the two riskiest
subsystems - pump drivers and background execution. The far better trade is to lift AIMI into
`commonMain`, publish it as an XCFramework, and plug it into Trio at the seam where Trio already
calls its oref JavaScript bundle as a JSON-in / JSON-out black box. You inherit eight years of
LoopKit drivers, background behaviour and a working distribution pipeline, and the *same*
`commonMain` keeps serving Android AAPS - one algorithm, two platforms, no fork.

**And the intermediate step pays for itself even if iOS never happens.** Getting AIMI into
`commonMain` means it becomes testable off-device, on the JVM and on Native, with the JSON contract
pinned. For 102 KLOC of safety-critical dosing logic that today can only be exercised inside an
Android app, that is worth doing on its own merits.

---

## 2. What Milos actually built, measured

### 2.1 State of the tree

| metric | value |
|---|---|
| Commits on `kmp` ahead of `dev` | **153**, 2026-08-05 → 2026-08-20, **16 calendar days**, one author |
| Files touched | 2 458, +30 454 / −16 996 |
| Modules with `kotlin("multiplatform")` | **14** of 50 (the document says 3) |
| Files in `commonMain` | **1 002** |
| Lines in `commonMain` | 103 734 raw / **75 440 code** |
| Files in `iosMain` | **12** |
| `expect` declarations, whole repo | **14** |
| Tests | 1 212 → **1 256** (+44) |
| Production files genuinely deleted | 22, each justified |

`commonMain` is clean in the strict sense: zero `android.*`, zero `java.*`, zero Dagger, zero
RxJava, zero `R.string`.

### 2.2 Per-module state

| module | files | LOC | multiplatform | files in commonMain |
|---|---:|---:|:---:|---:|
| `core/ui` | 480 | 46 177 | yes | **435** |
| `core/interfaces` | 289 | 15 479 | yes | **250** |
| `core/nssdk` | 101 | 7 815 | yes | 73 |
| `core/data` | 82 | 4 422 | yes | 67 |
| `core/keys` | 48 | 3 680 | yes | 47 |
| `core/objects` | 59 | 6 965 | yes | 25 |
| `plugins/aps` | 77 | 18 332 | yes | 23 |
| `core/graph` | 12 | 2 014 | yes | 11 |
| `plugins/calibration` | 15 | 2 260 | yes | 9 |
| `plugins/smoothing` | 8 | 1 672 | yes | 7 |
| `plugins/sensitivity` | 11 | 1 408 | yes | 6 |
| `core/utils` | 27 | 1 946 | yes | 5 |
| `plugins/main` | 6 | 2 888 | yes | 2 |
| `ui` | 380 | 64 470 | **no** | 0 |
| `plugins/sync` | 316 | 45 570 | **no** | 0 |
| `implementation` | 189 | 31 057 | **no** | 0 |
| `wear` | 165 | 21 037 | **no** | 0 |
| `database/impl` | 251 | 18 565 | **no** | 0 |
| `app` | 94 | 17 427 | **no** | 0 |
| `plugins/automation` | 168 | 14 375 | **no** | 0 |
| `plugins/source` | 69 | 8 097 | **no** | 0 |
| `plugins/constraints` | 52 | 6 648 | **no** | 0 |
| `database/persistence` | 49 | 5 984 | **no** | 0 |
| `shared/impl` | 21 | 3 838 | **no** | 0 |
| `plugins/configuration` | 28 | 2 758 | **no** | 0 |
| `workflow` | 9 | 1 636 | **no** | 0 |

37 of 51 modules still have zero `commonMain`.

### 2.3 Quality verdict

Sound and reusable, not a spike. Test count rose; no module silently lost coverage. Exactly **two
stubs** exist, both iOS-only, neither touching Android:

- `TextRefResource.ios.kt` - self-labelled PLACEHOLDER, returns raw string *names* and `"?"`. This is
  the largest single piece of hidden iOS debt in the tree.
- `CalibrationScatterChart.ios.kt` - the Android chart is fully intact at 215 lines. The commit
  message "chart stubbed off Android" reads like an Android regression and is not one.

Three real caveats:

- **Verification stopped on 2026-08-18.** The last six conversions, including `:plugins:aps`
  (174 files), carry no verification note, and this fork's CI only assembles - it never runs tests.
- **Only `:core:data` has a `commonTest`** (4 files). About **81.5 of the 84.9 shared KLOC has never
  executed on Kotlin/Native.** The klibs compile; almost nothing has run.
- Self-registering DI is gone. `:app` now carries 9 hand-written wiring files, and one more appears
  with every module converted.

### 2.4 Where the document and the code disagree

| document says | code says |
|---|---|
| "Three modules are multiplatform" | 14 declare multiplatform targets |
| §7 "step 5 (Room KMP) is done" | Room is untouched; `database/impl` is a plain Android library |
| "Three modules build for Native" | 14 declare targets; **3** are covered by iOS CI |
| wave 10 `TextRef.Res(id, args)` | renamed to `AndroidRes`, and `Named` was added |
| `PluginBase.pluginId` "uses KClass" | the type is still `String` (`this::class.simpleName!!`) |
| open follow-up #3 | already done |

The wave narrative stops at wave 18. **Eleven of the fourteen modules were converted in undocumented
waves**; the commit bodies are the only record, and several of them (`19d23293cf`, `e63983a3ff`,
`949a587cd5`) are better documentation than the document itself.

---

## 3. The playbook - the most reusable thing Milos produced

### 3.1 The governing method: fixpoint iteration, not file-by-file porting

Stated verbatim in `949a587cd5`:

> *"Found by moving all of androidMain to commonMain, compiling for iosArm64, moving the failures
> back, and repeating until the build is green. That took six rounds: 32 files fail on their own
> Android imports, then 46 more because they referenced those 32, then 19, then 10, then 1. One round
> is not enough and gives a much larger answer, because a file can compile only while a dependency
> that later has to move back is still next to it."*

The loop:

1. `git mv src/androidMain/** src/commonMain/**` - everything, indiscriminately.
2. `./gradlew :module:compileKotlinIosArm64`.
3. Move only the failures back to `androidMain`.
4. Repeat, typically **six rounds**.
5. Every file left in `androidMain` is now *named* debt with a reason attached.

His own note on why this is the point: *"the point is not the 22 files, it is that the iosArm64
target now fails the build if an Android import appears in any of them."* **The iOS target is a
ratchet, not a deliverable.** That is the idea to steal.

### 3.2 The ordered transformation list, applied before a module can flip

Each as its own buildable commit: dead code out first and characterization tests written against the
*old* stack; `DecimalFormat` → own `NumberFormat` + `NumberFormatPlatform` seam; `TimeUnit` →
`kotlin.time.Duration`; `org.json` → `kotlinx.serialization`; Gson → kotlinx; joda → kotlinx-datetime;
Retrofit/OkHttp → Ktor (contract suite written first); `@StringRes Int` → `TextRef`; `ResourceHelper`
→ `TextResolver`; **RxJava → Flow across 551 files in one day**; `Spanned`/HTML → `AnnotatedString`;
`System.currentTimeMillis()` → injected `Clock`; `@Synchronized` → `AapsLock`; resource-id sentinels
(`0`, `-1`) deleted by making them unrepresentable; ids-as-data → enums and `ImageVector`;
`Parcelable` dropped; Dagger `Provider` → plain factory lambda; **drop `HasAndroidInjector`**; move
Android entry points (Services, Receivers, Workers, Fragments) to `:app`; lift the module's Dagger
module to `app/src/main/kotlin/app/aaps/di/`; `PluginBase`/`PumpPluginBase` to `commonMain`; flip the
build file; fixpoint sweep; write the `expect`/`actual` remainder.

### 3.3 The seams he invented

**`TextRef`** (`core/keys/.../interfaces/TextRef.kt`) - a sealed interface with `AndroidRes(id)`,
`Named(owner, name)` and `Literal(text)`. `owner` is not decoration: `ns_wifi_ssids` genuinely exists
in both `:core:keys` and `:core:ui` with different Bulgarian translations, so a resolver guessing by
lookup order would silently pick the wrong string. `AndroidRes` is kept **by design** so modules can
migrate one at a time - which is why integrating AIMI does not require making it multiplatform first.

**`GenerateKeyStringsTask`** (buildSrc, 248 lines) - the mechanism that makes `Named` cheap. One pass
over a module's `strings.xml` emits `<X>Strings` into `commonMain` (pure `TextRef.Named` values) and
`<X>StringIds` into `androidMain` (the `name → R.string.id` map). Same pass, so they cannot drift.
**No resource file ever moves**, `crowdin.yml` is untouched, AAPT keeps resolving every locale, and a
typo is a compile error rather than a silent `0`. Wired into 9 modules, 1 133 references.

**`TextResolver`** - the four-method subset of `ResourceHelper` that any platform can implement.
Most files never call a resolver; they only *name the type in a signature*. Widening the signature
lets them move to `commonMain` with zero call-site churn.

Plus `TextRefIdRegistry`, `NumberFormatPlatform`, `AapsLock`, `PumpPluginBase`.

### 3.4 The planning law, from his own "Decisions taken"

> **"A module's conversion cost is roughly its Dagger count."**

And the reason it is not negotiable: Dagger cannot live in `androidMain` either. Probed on
`:plugins:smoothing`, Dagger's KSP backend emits **Java** into a target that has no `javac`. The
factories are never compiled, nothing reports it, and **the build passes**. A module can look
converted while its DI does not exist. Hence: no Dagger annotation anywhere in a multiplatform
module, and the wiring moves to one file per module under `app/.../di/`.

### 3.5 Why the flips look cheap, and why that is misleading

**61 % of the commits (94 of 153) are horizontal seam work that names no module.** The per-module
flip is cheap only because that was paid first. `:core:ui` moved **42.5 KLOC in a 339-line diff**
(625 files, 479 of them pure renames). Segmented rates: clean data modules ≈ 1.34 commits/KLOC,
UI and contract modules ≈ 0.50, plugins ≈ 0.76. Estimated **160-220 engineer-hours** overall -
commits on all 16 days, 07:00 to 23:00, mode at 22:00.

Three things make this a *ceiling*, not a baseline anyone should expect to match: he is the lead
maintainer with deep context, the work was **agent-augmented** (the document itself cites "five
parallel agents" and a "seven-agent pass"), and `:core:ui` had been pre-paid by an earlier Compose
migration - 0 XML layouts, 0 Dagger, 16 `android.*` imports out of 434 files.

---

## 4. AIMI - portability inventory

Measured on `dev_OAPSAIMI` at `06e7bc5021`.

| metric | value |
|---|---|
| Files, `src/main`, AIMI package | **441** |
| Lines, `src/main` | **102 354** |
| Files / lines, `src/test` | 243 / 31 962 |
| Top-level packages under `openAPSAIMI/` | 41 |
| Largest file | `DetermineBasalAIMI2.kt` - **18 886 lines**, 357 functions, 12 nested classes |

For scale: the whole vanilla `:plugins:aps` on `kmp` is 77 files / 18 332 lines. **AIMI is 9.3× the
upstream plugin**, and on its own it is larger than everything Milos has moved to `commonMain`.

### 4.1 Portability tiers

Import-anchored classification, comments and string literals stripped first (a naive grep for
`Context` scores about 15 false positives from KDoc prose).

| tier | files | % files | LOC | % LOC | meaning |
|---|---:|---:|---:|---:|---|
| **T0** pure Kotlin | 216 | 49.0 % | 23 325 | 22.8 % | moves to `commonMain` free |
| **T1** easy seam | 137 | 31.1 % | 29 799 | 29.1 % | mechanical - exactly the rewrites Milos already solved |
| **T2** needs `expect`/`actual` | 80 | 18.1 % | 46 534 | 45.5 % | Context, prefs, files, sensors, WorkManager |
| **T3** hard | 8 | 1.8 % | **2 696** | **2.6 %** | TFLite, ONNX, Health Connect |

**T2's 45.5 % is an artifact of file size, not of coupling depth.** Counting only the lines that
actually name an Android symbol:

| file | LOC | Android-touching lines | share |
|---|---:|---:|---:|
| `DetermineBasalAIMI2.kt` | 18 886 | **244** | 1.3 % |
| `OpenAPSAIMIPlugin.kt` | 2 282 | 98 | 4.3 % |
| all 80 T2 files | 46 534 | **2 781** | **6.0 %** |
| all 8 T3 files | 2 696 | 231 | 8.6 % |

`DetermineBasalAIMI2.kt` has **four** Android imports: `SuppressLint` and `Environment` (zero call
sites), `LongSparseArray` (4 uses), and `Context` - which exists to serve **133
`context.getString(R.string.…)`** calls. Inject a string provider and the biggest file in the
project drops from T2 to T1.

### 4.2 Packages that can move today, unchanged

100 % T0: `risk` (843), `scenario` (748), `release` (669), `keys`, `decision`, `inflammatory`,
`ports`, `extensions`, `carbs`. Near-pure: `safety` 21 of 24, `recursive` 20 of 22, `pkpd` 18 of 27.

### 4.3 The ML story - not the cost driver

- **Inference and training are pure Kotlin.** `aimiNeuralNetwork.kt` (613 lines): one hidden layer,
  z-score → LeakyReLU → layernorm → dropout, **Adam optimiser, on-device training**, seeded RNG,
  JSON persistence. Its only imports are `kotlin.math`, `kotlin.random`, `java.io.File`, `org.json`.
  No Android, no native code. It runs on Kotlin/Native essentially as-is.
- Pure-Kotlin NN plus trainers = **4 279 lines**. Full ML surface = 42 files / 8 741 lines, of which
  **native-bound is 488 lines (5.6 %)**. **94.4 % is portable unchanged.**
- **TensorFlow Lite = 1 file, 297 lines, 1 real inference call site** (`calculateSMBFromModel`).
  Model sizes verified with `git cat-file -s`: `model.tflite` **4 232 bytes**, `modelUAM.tflite`
  **4 504 bytes**. A 4 KB flatbuffer is a few dense layers and a few hundred float32 weights. The
  cheapest fix is to re-express it as an `AimiNeuralNetwork` JSON and delete four Gradle
  dependencies.
- **ONNX = 1 file, 191 lines.** The three shipped `*_lgbm.onnx` files are **440-442 bytes**, sitting
  next to `PLACE_ONNX_MODELS_HERE.txt`. The feature is dormant; an `actual` stub on iOS costs nothing
  behaviourally.
- **Health Connect is 4.5× the native ML code: 6 files / 2 208 lines**, 984 of them in
  `AIMIPhysioDataRepositoryMTR.kt`. A HealthKit rewrite, no shortcut. **This is the real T3.**

Gradle dependencies that leave on the KMP path: `tensorflow-lite`, `-gpu`, `-support`, `-metadata`
(all 2.4.0) and `onnxruntime-android:1.20.0`.

### 4.4 Dependency census

Files / lines-of-those-files: `javax.inject`+Dagger 83 / 48 491 (75 `@Inject constructor`, only
2 `@Module`) · `System.currentTimeMillis` 80 / 49 029 · `android.*` 72 / 43 881 · `org.json`
65 / 41 164 · **`kotlinx.coroutines` 53 / 42 250 (already multiplatform)** · `java.util.concurrent`
43 / 34 231 · `androidx.*` 41 / 33 873 · `R.string` 27 / 32 655 · `java.text` 10 / 22 471 ·
**Gson 1 · RxJava 2 · Thread/Executors 2 · joda 0**.

The Rx and Gson counts are the good news: AIMI was written after those battles and is already on
coroutines.

### 4.5 Blockers that live outside AIMI

`app.aaps.core.*` is reached from **156 of 441 files / 63 824 lines (62 %)**. The heaviest single
external dependency is **`PersistenceLayer` (Room), touched from 21 files / 31 396 lines** - and Room
is the one thing Milos has not started. `Preferences`/`SP` reach 73 files, but only **4 files** touch
`SharedPreferences` directly, so one store abstraction covers the whole surface.

Localisation: **860 distinct `R.string` keys, 472 call sites, 15 files**, plus the 1 099 occurrences
counted repo-wide across 27 files.

### 4.6 I/O and UI surface

37 files write to `Environment.getExternalStorage()/Documents/AAPS`: 6 Hormonitor JSONL streams,
`AIMI_Decisions.jsonl`, 6 CSV training logs, 11 JSON model and state stores. WorkManager in 9 files /
1 246 lines. Sensors 2 / 340. SAF backup 1 / 266. SOS over SMS 2 / 454. HTTP through
`HttpURLConnection` in 8 files - a straight Ktor swap.

UI is only **6.5 % of AIMI**: Compose 5 files / 2 357, View-based Activities 8 files / 4 254,
**zero Fragments**, 5 XML layouts in total. The worst single file is
`AimiProfileAdvisorActivity.kt` - 2 316 lines building its UI programmatically, 26.8 % of them
Android.

---

## 5. Bringing AIMI onto Milos's foundation - the integration cost

Measured with `git merge-tree --write-tree kmp dev_OAPSAIMI`. Common ancestor `7fc8205e9a`;
`dev_OAPSAIMI` is **2 684 commits** ahead of it, `kmp` is **153**.

### 5.1 The conflict surface is not what git reports

- Git flags **139 conflicted paths / 130 hunks** (64 content, 62 file-location from source-set moves,
  13 modify/delete).
- The **true integration surface is 216 files** - those changed by *both* sides since the ancestor.
- **Git silently auto-merges 139 of those 216, and 100 of them are `.kt`/`.kts`.** That is the real
  risk: `MainScreen.kt` (790 lines changed on the fork against 3 on `kmp`),
  `DetermineBasalResult`, `PersistenceLayerImpl`, `SafetyPlugin`, `GarminPlugin`, `MedtrumPlugin`.
  A clean merge here is not evidence of a correct merge, and the code being merged decides insulin
  doses.
- Worst modules by hunk count: `core/ui` 33, `plugins/aps` 11, `plugins/main` 11, `core/keys` 9,
  `app` 9, `implementation` 7.
- Worst single case: `OverviewFragment.kt` - 1 677 lines changed on the fork, **deleted on `kmp`**.
  AIMI's 34-file dashboard has no host to attach to.

### 5.2 API breaks, and two that turned out not to exist

| breaking change | AIMI call sites | mechanical? |
|---|---:|:---:|
| `PluginDescription` `Int` → `TextRef` | 1 file, 3 calls | yes |
| `rh: ResourceHelper` → `TextResolver` | 6 main / 2 test | yes |
| `HasAndroidInjector` dropped | 1 | yes |
| field `@Inject` → hand-wired constructors in `:app` | **82 files, 130 sites** | yes, bulk |
| Dagger `@Module` lifted to `:app` | 7 modules | yes |
| Activities → `:app`; `TranslatedDaggerAppCompatActivity` deleted | 13 activities, 5 affected | **no** |
| `R.string.*` → `ApsStrings` | 27 files, 1 099 occurrences | yes - **and optional** |
| `org.json` → kotlinx | 67 / 14 files, 976 occurrences | yes - still legal on `kmp` |
| `DecimalFormat` / `TimeUnit` / `java.text` / `java.util.Date` | 4 / 12 / 13 / 47 files | yes - all still legal |
| `System.currentTimeMillis` | 81 files, 306 occurrences | yes |
| `android.*` / `androidx.*` imports | 72 / 41 files | no - **but only for `commonMain`** |
| relocated core symbols (~20 FQNs) | import rewrite | yes |
| genuinely deleted APIs | 5 FQNs, 9 files | no |
| `PumpPluginBase`, Room | **0** | — |

**Two premises I started from were wrong, and correcting them roughly halves the estimate.**
`pluginId` is still a `String`, and `ResourceHelper` plus `TextRef.AndroidRes` still exist by
design - 35 modules on `kmp` are still plain `src/main` Android libraries. **Integration does not
require KMP-ifying AIMI.** Those are two separable projects, and conflating them is the main way this
work could be mis-scoped.

### 5.3 Non-AIMI divergence

56 % of the fork's 349 793 changed lines is not AIMI. About **493 files in 6 brand-new modules**
(`libre3`, `eversense`, `dexcom_oneplus`, `apex`, `libkeks`, `graphview`) exist on neither the
ancestor nor `kmp` - **zero conflict, free to carry across**.

The expensive divergence is the fork's edits *inside* modules Milos has already made multiplatform:
`plugins/aps` 34 overlapping files, `core/ui` 28, `core/interfaces` 25, `core/data` 10, `core/keys` 9,
`plugins/main` 9, `plugins/smoothing` 8, `plugins/calibration` 4, `core/objects` 3, `core/graph` 1 -
plus `ui` 21 and `implementation` 15 by raw overlap.

### 5.4 Both branches are moving

`kmp` ran at ≈ 71 commits/week (153 in 15 days, one author). `dev_OAPSAIMI` runs at ≈ 97
commits/week (2 495 over 180 days, 6 authors), and puts **909 commits per 180 days - about 35 per
week - into the very modules Milos has made multiplatform.** Any plan that assumes a stationary
target is wrong.

### 5.5 Two strategies, and the recommendation

**S1 - rebase the fork onto `kmp`, keep one tree.** ≈ **14.5 person-weeks** (range 12-18) to a first
building tree, then **3-5 days every month, indefinitely**, with unbounded dosing-regression risk
from the 100 silent auto-merges. The dashboard rewrite (≈ 4 pw) is *mandatory* here because it blocks
the build.

**S2 - extract the plugin.** Leave `dev_OAPSAIMI` alone; lift AIMI onto the `kmp` foundation as its
own module, re-wired to the new interfaces. ≈ **8.5 person-weeks** (range 7-10), then **0.5-1.5
days/month and falling**. Breakages surface as compile errors rather than silent merges. The
dashboard rewrite is excluded and deferrable - AIMI ships headless first.

**Take S2.** Sequence: port the 6 zero-conflict modules first → land AIMI's `core/*` additions as an
ordinary PR → create an **Android-only** `:plugins:aimi` → DI, Activities, strings → wiring and
tests. Explicitly **do not attempt `commonMain` during integration**. Getting onto the foundation and
becoming multiplatform are two projects, and doing them at once is how this fails.

---

## 6. The iOS platform, honestly

### 6.1 Background execution - the reference document's warning is wrong

`_docs/KMP_IOS_FEASIBILITY.md` §3 says iOS has no equivalent of WorkManager, that `BGTaskScheduler`
gives delayed wake-ups, and therefore *"the real answer is push (APNs) from a server, which changes
the design"*. That conclusion does not match what the Swift loop ecosystem has been doing since 2018.

Loop, Trio and iAPS keep a 5-minute closed loop alive with the **`bluetooth-central` background
mode** plus CoreBluetooth **state restoration**
(`CBCentralManagerOptionRestoreIdentifierKey`). The CGM is used as a **heartbeat**: every BLE notify
wakes the app, which then has roughly 10-30 seconds to run a cycle and issue a pump command. Loop's
`Info.plist` uses `BGTaskScheduler` for exactly one thing - log export.

The real constraint is different, and Trio's own documentation states it plainly: *"if your CGM does
not supply a heartbeat, the app will stop automatically running when it is not open."* So the
blocker is **"the loop needs a directly connected BLE CGM"**, not "iOS forbids background loops".
Nightscout-sourced, xDrip-sourced and Dexcom-Share-sourced setups cannot loop unattended on iPhone.

This correction matters because the APNs conclusion implies a server component and a redesign; the
BLE-heartbeat reality implies neither.

### 6.2 Verdict table

| area | verdict | effort |
|---|---|---|
| Background execution | POSSIBLE WITH CONSTRAINTS - BLE wake, not BGTask/APNs; no BLE CGM means no loop | 8-14 pw |
| BLE devices | POSSIBLE WITH CONSTRAINTS - see losses below | 14-30 pw for 1 CGM + 1 pump; 150+ pw for parity |
| **ML runtime** | **POSSIBLE - the smallest section of all**; training is pure Kotlin and runs on Native free | 5-9 pw |
| Storage | POSSIBLE WITH CONSTRAINTS - Room KMP is fine (46 DAOs, 0 RxJava, already on `BundledSQLiteDriver`); SAF has no analogue | 25-41 pw |
| Distribution | POSSIBLE WITH CONSTRAINTS - **$99/year per user, forever** | 6-10 pw + permanent support load |
| Other Android surfaces | 85 WorkManager files, no foreground service, no Critical Alerts | 10-16 pw |

Core ML was evaluated and rejected: it cannot train on-device in the way AIMI needs, and it cannot
load a user's own `.tflite`.

### 6.3 Pumps and CGMs

Mature Swift drivers already exist for DASH, Eros, Dana-i, Medtrum, Medtronic, G6, G7 and Libre
(OmnipodKit, DanaKit, MedtrumKit, MinimedKit, CGMBLEKit, G7SensorKit, LibreTransmitter). The Eros
RileyLink is a **BLE** bridge, so it is not an iOS blocker.

Verified against this repo by grepping the pump modules:

- **Bluetooth Classic / RFCOMM - impossible on iOS without MFi**: `pump/combov2/comboctl`,
  `pump/danar`, `pump/insight`. iOS offers no SPP to third-party apps. Trio's supported-device list
  matches this line exactly.
- **BLE GATT - portable via CoreBluetooth**: `pump/danars`, `pump/diaconn`, `pump/equil`,
  `pump/medtrum`, `pump/omnipod/common`, `pump/rileylink`.

### 6.4 Feature-loss list for any iOS AAPS

1. DanaR / DanaRv2, Accu-Chek Combo, Accu-Chek Insight - permanently.
2. SMS and phone-call commands - no iOS API, permanent.
3. **Alarms that override Do Not Disturb.** The Critical Alerts entitlement is not obtainable for a
   DIY fork; Trio's entitlements file does not contain it. **This is a safety regression, not a
   convenience one.**
4. Wear OS entirely.
5. Glance widgets - a WidgetKit rewrite, and it needs App Groups, so it is unavailable on the free
   provisioning profile.
6. Looping without a directly connected BLE CGM.
7. Auto-start after reboot - the loop stays down until the first unlock.
8. Scheduled SAF export to a user-chosen folder.
9. Tasker / intent automation and wifi-SSID triggers.
10. Garmin integration.
11. Free one-off sideloading.

### 6.5 Distribution - the gate that decides whether anyone but you can use it

The only viable route is the **Loop browser build**: the user forks the repo, buys **their own $99/yr
Apple Developer account**, stores four fastlane secrets, and GitHub Actions builds and uploads to
**their own** TestFlight, where they are their own *internal* tester - up to 100 team members and
**no Beta App Review**. No Mac is needed by the end user. Builds expire after 90 days with a weekly
automatic rebuild and a manual re-trigger around day 60. First setup runs 2-4 hours.

The alternatives do not work: a free 7-day profile covers the author only and loses App Groups;
external TestFlight would require App Review and would be refused; AltStore PAL under the DMA is
EU-only, Apple-notarised, and would mean publicly distributing an unregulated medical device under
EU MDR; MDM does not apply.

**Plan for this as real work, and for a permanent support load.** Every user pays Apple $99 a year.

---

## 7. Cost

### 7.1 Method and assumptions

Estimates are bottom-up from measured quantities, calibrated against Milos's **delivered** throughput
and then de-rated. Stated openly so the numbers can be argued with:

- **1 person-week = 40 hours.**
- **Calibration.** Milos delivered 75 440 lines into `commonMain` in 160-220 engineer-hours across
  16 days. Bulk mechanical work ran at roughly 1.5 minutes per file (551 files of RxJava → Flow in
  one day); the hours went into seam *design*, not into edits.
- **De-rating factor 2.5× for P50, 4× for P80.** Milos is the lead maintainer with full context, the
  work was agent-augmented (his own document cites "five parallel agents" and a "seven-agent pass"),
  and upstream code had been pre-cleaned by the Compose migration and the RxJava removal. This fork
  is a single-maintainer, safety-critical codebase where 62 % of AIMI files reach into `core`, and it
  has no Kotlin/Native test infrastructure at all.
- **The seam work is already paid.** AIMI lands on a foundation where `TextRef`,
  `GenerateKeyStringsTask`, `TextResolver`, `NumberFormatPlatform` and `AapsLock` already exist. This
  is the single biggest reason AIMI is cheaper than its line count suggests.
- **Room is NOT on AIMI's critical path.** Verified: `PersistenceLayer.kt` and `Preferences.kt` are
  already in `commonMain`. AIMI compiles to `commonMain` against those interfaces while the Room
  implementation stays Android-only. Room becomes required only for an iOS app that actually *runs* -
  not for a shared engine. This removes ~13 pw from the intermediate scenario.

### 7.2 Work package WP1 - integration onto the `kmp` foundation

Strategy S2, Android-only, no `commonMain`. From the measured conflict surface: **8.5 pw P50,
11 pw P80.**

### 7.3 Work package WP2 - AIMI into `commonMain`

| item | quantity | rate | hours |
|---|---:|---|---:|
| T0 files moved | 216 files | 4 min | 14 |
| T1 mechanical rewrites (`org.json` 976 occ, `java.text`, `TimeUnit`, `Date`, `currentTimeMillis` 306 occ) | 137 files | 0.75 h/file | 103 |
| T2 seams | 80 files / 2 781 Android lines | 1.5 h/file | 120 |
| External-storage abstraction | 37 files | 1 h/file | 37 |
| WorkManager → `expect` scheduler | 9 files | — | 40 |
| Health Connect → `expect` (Android `actual` unchanged) | 6 files | — | 40 |
| TFLite removal, 4 KB model re-expressed as `AimiNeuralNetwork` JSON, parity test | 1 file + model | — | 60 |
| ONNX `actual` stub | 1 file | — | 8 |
| Fixpoint sweeps, 6 rounds over 441 files | — | — | 60 |
| Gradle flip + iOS CI wiring | — | — | 24 |
| **`commonTest` parity harness, JVM + Native, for dosing output** | — | — | **160** |
| **total** | | | **666 h ≈ 17 pw** |

**17 pw P50, 26 pw P80.** The 160-hour test harness is the line not to cut. Today 102 KLOC of dosing
logic can only be exercised inside an Android app; the harness is what makes the migration
*verifiable* rather than hopeful, and it is the item that pays back regardless of iOS.

### 7.4 Scenarios

| scenario | what you get | P50 | P80 |
|---|---|---:|---:|
| **SC-A** iOS follower | Milos's original scope: read-only viewer, no AIMI dosing | **53 pw** | 72 pw |
| **SC-B** AIMI engine shared, Android master only | AIMI in `commonMain`, testable off-device, still ships only on Android | **26 pw** | 37 pw |
| **SC-C** AIMI master on iOS | the full ambition: loop closing on iPhone | **171 pw** | 250 pw |
| **SC-D** AIMI as XCFramework inside Trio | AIMI dosing on iPhone, on Trio's drivers and distribution | **51 pw** | 70 pw |

Calendar, with a scaling penalty applied (0.75 efficiency at 2 FTE, 0.6 at 4 FTE - this is a
safety-critical single-maintainer codebase, so extra people do not help linearly):

| scenario | 1 FTE | 2 FTE | 4 FTE |
|---|---:|---:|---:|
| SC-A | 53 wk | 35 wk | 22 wk |
| SC-B | **26 wk** | 17 wk | 11 wk |
| SC-C | 171 wk (3.3 yr) | 114 wk (2.2 yr) | 71 wk (1.4 yr) |
| SC-D | 51 wk | 34 wk | 21 wk |

**Reality check on FTE.** This project is realistically 0.5-1 FTE. At that rate SC-C is a **3 to 7
year** commitment; SC-B is **6 to 12 months**; SC-D is **1 to 2 years**.

### 7.5 The alternatives, costed

- **Do nothing (stay Android-only).** Zero cost, and you keep every feature. What you lose is the
  ability to test 102 KLOC of dosing logic outside an Android device - which is a real cost already
  being paid today, in debugging time.
- **Rewrite AIMI in Swift for Trio/Loop.** 102 354 lines rewritten by hand, behaviour-identical.
  Even at a sustained 100 lines/day that is ≈ **205 pw**, and it leaves **two implementations of
  safety-critical dosing logic to keep in step forever**. Worse than every other option.
- Note that "just contribute AIMI to Trio" is not the cheap option it sounds like: **Trio's algorithm
  is oref0 JavaScript running in JavaScriptCore** (`Trio/Resources/javascript/bundle/determine-basal.js`).
  Contributing to it means rewriting Kotlin into JavaScript.

---

## 8. Recommendation and detailed plan

### 8.1 The recommendation

**Take SC-D, reached through SC-B. Do not attempt SC-C.**

SC-C - rebuilding the whole AAPS master app on iOS - costs 171 to 250 person-weeks and lands you
where Trio already is, **minus five features** (DanaR/Combo/Insight, SMS commands, DND-overriding
alarms, Wear OS, Garmin), while carrying a **second, less-tested implementation of the two riskiest
subsystems in the product**: pump drivers and background execution. For an insulin-dosing app that is
not a good trade at any price.

SC-D costs 51 pw and reuses eight years of LoopKit drivers, a proven background model and a working
distribution pipeline. It is credible because **the seam already exists on both sides**:

- On the Kotlin side, `plugins/aps/src/commonMain/` already holds `DetermineBasalSMB.kt`,
  `DetermineBasalAMA.kt` and `DetermineBasalAutoISF.kt`, and the module declares `iosArm64()`. The
  AAPS dosing algorithm compiles for iOS today. `DetermineBasalAIMI2.kt` is the same shape.
- On the Swift side, Trio already treats determine-basal as a **JSON-in / JSON-out black box** - it
  has to, because it calls JavaScriptCore - and it ships a `middleware/` hook at exactly that seam.

So the integration point is a JSON contract, not an API rewrite. And the decisive property: **the
same `commonMain` keeps feeding Android AAPS.** One algorithm, two platforms, no second
implementation to keep in step.

**SC-B is the mandatory first half of SC-D and is worth doing on its own.** If iOS never happens, you
still end up with 102 KLOC of dosing logic that runs on the JVM, in CI, without a device.

### 8.2 The plan

Phases are gated. Each gate is a real go/no-go with a stated exit test - the point is to be able to
stop cheaply, not to commit up front.

---

#### Phase 0 - Spike: prove the seam before committing anything (4-6 weeks)

The whole recommendation rests on one claim: AIMI's decision core can compile to Kotlin/Native and
produce byte-identical decisions. Test that first, on the smallest possible slice.

1. On this branch, take the ~9 packages that are already 100 % T0 (`risk` 843 lines, `scenario` 748,
   `release` 669, `decision`, `ports`, `carbs`, `extensions`, `inflammatory`, `keys`) plus
   `recursive` (20 of 22 pure).
2. Put them in a throwaway multiplatform module targeting `jvm` + `iosArm64` + `iosSimulatorArm64`.
3. Capture a corpus of **real production inputs and outputs** from the existing JSONL decision logs
   (`AIMI_Decisions.jsonl`) - de-identified, no names, no Nightscout URLs, no tokens.
4. Build a `commonTest` that replays the corpus and asserts the outputs match the Android run.
5. Link an XCFramework and run the same corpus through a 200-line Swift harness.

**Exit test:** the Swift harness reproduces the Android decisions on the captured corpus, bit for
bit on the numeric fields. **If it does not, stop here** - the cost is 4-6 weeks, not 51.

This phase also answers the question nobody has answered yet: **does any of this shared code actually
run on Native?** Today about 81 of the 85 shared KLOC has never executed there.

---

#### Phase 1 - Land AIMI on the `kmp` foundation, Android-only (8.5 pw)

Strategy S2. **Do not touch `commonMain` in this phase.** Getting onto the foundation and becoming
multiplatform are two projects; doing them together is the main way this fails.

1. Port the **6 zero-conflict modules** first (`libre3`, `eversense`, `dexcom_oneplus`, `apex`,
   `libkeks`, `graphview`) - 493 files that exist on neither side's ancestor, so they carry across
   free and prove the pipeline.
2. Land AIMI's additions to `core/*` as an ordinary reviewed PR (the `GlucoseStatusAIMI`,
   `OapsProfileAimi`, `AimiAdaptationStatus` contracts).
3. Create `:plugins:aimi` as a **plain Android library**, and move the 441 files into it.
4. Mechanical API fixes: 130 `@Inject` sites across 82 files, 7 Dagger modules lifted to
   `app/.../di/`, 13 Activities relocated, ~20 relocated core FQNs, 5 genuinely deleted APIs.
5. Re-wire and get the existing **243 test files / 31 962 lines** green.

**Gate 1:** the app builds, installs, and runs a full loop cycle on a test device with AIMI selected,
with the fork's own test suite green.

**Known unsolved item:** `OverviewFragment.kt` is deleted on `kmp`, and AIMI's 34-file dashboard has
no host. Deliberately deferred - AIMI ships headless first. Budget 3-5 pw when it can no longer be
deferred, and do it as a Compose rewrite rather than a resurrection.

---

#### Phase 2 - AIMI into `commonMain` (17 pw)

Apply Milos's playbook, in his order, with his seams. Use **fixpoint iteration**, not file-by-file
porting: move everything, compile `iosArm64`, move the failures back, repeat six rounds.

| step | work | measured quantity |
|---|---|---|
| 2.1 | T0 packages straight across | 216 files / 23 325 lines |
| 2.2 | `org.json` → `kotlinx.serialization` | 976 occurrences |
| 2.3 | `java.text`, `TimeUnit`, `Date`, `System.currentTimeMillis` → `Clock` | 306 occurrences, 81 files |
| 2.4 | `R.string` → `AimiStrings` via `GenerateKeyStringsTask` | 1 099 occurrences, 27 files. **Do `DetermineBasalAIMI2.kt`'s 133 `getString` calls first** - it alone moves the largest file from T2 to T1 |
| 2.5 | External storage → `okio` + `expect` path provider | 37 files |
| 2.6 | WorkManager → `expect` scheduler (Android `actual` keeps WorkManager) | 9 files |
| 2.7 | Health Connect → `expect` physiological source | 6 files / 2 208 lines |
| 2.8 | Delete TFLite: re-express the 4 232-byte model as `AimiNeuralNetwork` JSON, with a parity test against the TFLite output before deleting the dependency | 1 file, 297 lines |
| 2.9 | ONNX → `actual` stub on iOS (feature is dormant, models are 440-byte placeholders) | 1 file, 191 lines |
| 2.10 | Fixpoint sweeps until `commonMain` is maximal | 6 rounds |
| 2.11 | Flip the build file; add `:plugins:aimi` to the iOS CI **path filter** | ~110 lines of Gradle |
| 2.12 | **`commonTest` decision-parity suite, running on JVM and Native** | the 160-hour item |

**Gate 2:** `:plugins:aimi` compiles for `iosArm64`, the parity suite passes on both JVM and Native,
and the Android app's behaviour is unchanged on a real device over a 72-hour run.

---

#### Phase 3 - XCFramework and Swift harness (8 pw)

1. Package `:plugins:aimi` + its `core` dependencies as an **XCFramework**, with **SKIE** so
   `suspend` and `Flow` bridge into Swift properly.
2. Define the JSON contract at the determine-basal boundary - the same shape Trio already passes to
   its JavaScript bundle.
3. Swift harness replaying the captured corpus.
4. Publish via SPM from this repo; the iOS side stays out of this repo, as Milos's document argues.

**Gate 3:** the XCFramework runs the corpus in Swift with matching output, and its size and cold-start
cost are acceptable on a real iPhone.

---

#### Phase 4 - Trio integration (9 pw)

1. Replace the JavaScriptCore `determine-basal` call with the XCFramework call, behind a flag.
2. Use Trio's existing `middleware/` hook as the initial integration point - it needs no fork of Trio
   to prototype.
3. Shadow mode first: run both, log both, dose from oref0. Only after a shadow period does AIMI take
   the dose.
4. Distribution: the Loop browser-build model - the user forks, supplies their own $99/yr Apple
   credentials as GitHub secrets, Actions uploads to their own TestFlight.
5. Talk to Trio upstream early. A vendored XCFramework in a third-party fork is a maintenance dead
   end; an accepted seam is not.

**Gate 4:** shadow-mode agreement between the Kotlin engine and the Android reference over a
multi-week real run, before any dose is taken from it.

---

### 8.3 What to do first, this week

1. **Merge `kmp` into `dev`.** It is only 6 commits behind and the merge has never been done. It is
   free and it stops the drift.
2. **Extend the iOS CI path filter.** It names only `core/data`, `core/nssdk` and `core/keys`, so
   `:core:ui`'s iOS compile is unverified at HEAD - 11 of 14 multiplatform modules are outside CI.
   One-line change, catches real breakage.
3. **Fix `TextRefResource.ios.kt`.** It is a self-labelled placeholder returning raw string *names*
   and `"?"`. It is the largest piece of hidden iOS debt in the tree, and every later phase builds on
   top of it.
4. **Start Phase 0.** It is the cheapest decisive experiment available.

---

## 9. Risks

| # | risk | likelihood | impact | mitigation |
|---|---|---|---|---|
| 1 | **A silent auto-merge changes dosing behaviour.** Git cleanly merges 139 of the 216 shared files, 100 of them Kotlin. | high | severe | This is why the plan is S2 (extract) and not S1 (rebase). Breakage becomes a compile error. Plus the Phase 0 replay corpus. |
| 2 | **The shared code has never run on Native.** ~81 of 85 shared KLOC compiles but has never executed. | certain (it is a fact) | high | Phase 0 exists specifically to find out. Extend the iOS CI path filter now. |
| 3 | **Both branches keep moving.** The fork puts ~35 commits/week into modules that are already multiplatform. | certain | high | S2's cost is 0.5-1.5 days/month and falling, versus 3-5 days/month forever under S1. Re-sync monthly, not quarterly. |
| 4 | Behavioural drift in the numeric core - rounding, `Double` formatting, time zones between JVM and Native. | medium | severe | The parity corpus, and Milos's own precedent: his `NumberFormatParityTest` and `SystemTimeZoneTest` exist for exactly this, and his CI pins `TZ=Europe/Prague` so a zero-offset runner cannot hide a bug. |
| 5 | Trio upstream declines the seam. | medium | high | Prototype through the existing `middleware/` hook, which needs no upstream change. Talk to them in Phase 3, not Phase 4. |
| 6 | Dagger's KMP story does not land. Dagger is still in 1 053 files repo-wide; the exit depends on a PR merged 30 July 2026 that this project's 2.60.1 predates. | medium | medium | AIMI has only **2** `@Module` and 75 `@Inject constructor` - constructor injection ports. Re-run Milos's probe and check for `*_Factory.class`, **never for a green build**. |
| 7 | The maintainer is one person, and this is a multi-quarter commitment. | high | high | Every phase is gated and independently valuable. SC-B pays off even if iOS is abandoned. |
| 8 | An iOS AAPS loses DND-overriding alarms - the Critical Alerts entitlement is unobtainable for a DIY fork. | certain | severe | Not mitigable. It is a **safety** regression and must be disclosed to any user, prominently. |
| 9 | Distribution asks every user for $99/yr and a GitHub fork. | certain | medium | It is the model the Loop community already runs. Budget for a permanent support load. |
| 10 | Scope creep from SC-B into SC-C because "the app almost builds". | high | severe | The gates. SC-C is 171-250 pw and is explicitly out of scope. |

---

## 10. Provenance

Everything above was measured on this machine on 2026-08-25, on `kmp` at `4957c26eb8` and
`dev_OAPSAIMI` at `06e7bc5021`, common ancestor `7fc8205e9a`. The working tree was never switched;
other branches were read with `git show` / `git ls-tree` / `git grep`, and the merge analysis used
`git merge-tree --write-tree` (result tree `8da3ed1ebf`), which writes no files.

Produced by five parallel analyses - an audit of Milos's branch, an AIMI portability inventory, an
iOS platform study, a branch-divergence measurement, and this synthesis. Working notes are not
committed.

**Claims verified directly rather than taken from a report:** the model file sizes
(`git cat-file -s`); `PluginBase.pluginId` still being `String`; `ResourceHelper` and
`TextRef.AndroidRes` still existing; `PersistenceLayer.kt` and `Preferences.kt` already being in
`commonMain`; `plugins/aps/src/commonMain` containing the three determine-basal files while declaring
`iosArm64()`; and the Bluetooth Classic versus BLE GATT split across the pump modules.

**Two figures a careful reader will find inconsistent with the sub-reports:** `commonMain` is
1 002 files by `find` over `*/src/commonMain/*` and 103 734 raw lines, of which 75 440 are code
(blank and comment lines stripped). A lower file count in a sub-report reflects a narrower path
filter. `expect` declarations are **14** exactly.

**This is a draft for engineering review.** The cost figures are estimates with stated assumptions
and a stated de-rating factor, not quotes. Nothing here is a clinical or regulatory judgement: any
change that reaches dosing must go through this project's own safety review before it reaches a user,
and the iOS Critical Alerts loss in §6.4 needs an explicit safety decision, not just an engineering
one.
---

## 11. What is actually shared today - and the three OS-bound parts of AIMI

Added after review. Two questions came up that the sections above answer only implicitly.

### 11.1 Is it only the follower, or the master with the SMB plugin?

**Milos's stated scope was a follower. What he built goes past it.** These are in `commonMain` today:

- `DetermineBasalSMB.kt`, `DetermineBasalAMA.kt`, `DetermineBasalAutoISF.kt` - the dosing
  computations themselves
- `OpenAPSSMBPlugin`, `OpenAPSAMAPlugin`, `OpenAPSAutoISFPlugin`
- `PluginBase`, `PluginBaseWithPreferences`, `PumpPluginBase`, `ActivePlugin`, the command queue -
  the plugin spine
- `:plugins:sensitivity`, `:plugins:smoothing`, `:plugins:calibration`, `:pump:virtual`
- `RunningModeReconciler` and the running-mode logic
- `:core:ui`, 435 of 480 files

That is master-app territory, not follower territory. A follower needs none of it.

**But "multiplatform" here means "compiles to a klib for `iosArm64`", not "runs on iPhone".** What
stayed Android-only in `:plugins:aps` is exactly 22 files:

- **`LoopPlugin.kt`** - and the reason matters. Its Android imports are `NotificationChannel`,
  `NotificationCompat`, `PendingIntent`, `TaskStackBuilder`, `Context`, `Intent`, `Handler`,
  `HandlerThread`. **Not one of them is dosing logic.** It is the notification and threading shell
  around the decision, which is precisely the kind of thing `expect`/`actual` is for.
- **All of Autotune** - 14 files, including `AutotuneFS` (file system) and the Compose screens.

So the honest statement: **the brain is portable, the arm is not yet.** The computation that decides a
dose is shared. The component that acts on it, talks to the pump, and tells the user is not. Add to
that: Room and `:database:*` untouched, every pump driver Android-only, `:plugins:sync` (45 570
lines), `:ui` (64 470), `:implementation` (31 057) and `:app` all Android-only, and **no iOS app
exists**.

This is why §8 recommends shipping the *engine* rather than the app. The engine is the part that is
already almost there.

### 11.2 The machine learning

Not the problem it looks like. Restating §4.3 as steps:

1. **Nothing to port for the maths.** `aimiNeuralNetwork.kt` (613 lines, one hidden layer, Adam,
   seeded RNG) plus the trainers = 4 279 lines of pure Kotlin. It runs on Kotlin/Native as-is. Only
   `java.io.File` and `org.json` need swapping - both already-solved patterns.
2. **Delete TensorFlow Lite.** One file, 297 lines, one real inference site, against a
   **4 232-byte** model. Re-express it as an `AimiNeuralNetwork` JSON, assert parity against the
   TFLite output on the replay corpus, then delete four Gradle dependencies.
3. **Stub ONNX.** One file, 191 lines. The three shipped models are 440-byte placeholders next to
   `PLACE_ONNX_MODELS_HERE.txt`. An iOS `actual` that returns "unavailable" changes no behaviour.

**The real ML problem on iOS is not the maths, it is when the training is allowed to run.** See
§11.3.

### 11.3 On-device training and the CSV/JSONL files

Two separate problems that are easy to conflate.

**Problem A - where the files live.** About 30 named artefacts are written to
`Environment.getExternalStorage()/Documents/AAPS`, from 37 files: `AIMI_Decisions.jsonl`, six
`AIMI_HORMONITOR_*` streams, `oapsaimi2_records.csv`, `oapsaimiML2_records.csv`,
`oapsaimi_pkpd_records.csv`, `basal_adaptive_records.csv`, `autodrive_dataset.csv`,
`aimi_reactivity_analysis.csv`, `oapsaimi_wcycle.csv`, plus model and state stores
(`aimi_smb_model.json`, `basal_ml_training_state.json`, `personal_hyper_mlp.json`, ...).

Steps: one `expect` path provider plus `okio` for the I/O, with the Android `actual` keeping today's
path and the iOS `actual` returning the app sandbox's `Documents` directory. Mechanically simple.
What changes is the *user-visible* behaviour, and it should be stated up front:

- No SAF, so **no scheduled export to a folder the user picks**.
- The files are visible in the Files app only if the app declares `UIFileSharingEnabled` and
  `LSSupportsOpeningDocumentsInPlace`.
- **No other app can read them.** On Android these files sit in shared storage; on iOS they are
  sandboxed.

That last point is a privacy improvement, not a regression - this is per-patient health data, and a
sandbox is the better default. Whatever is done, the training corpus must stay on the device unless
the user explicitly exports it.

**Problem B - when training runs, and this one needs a redesign, not a port.** AIMI schedules
**six periodic WorkManager jobs**:

| worker | period |
|---|---|
| `PhysioRealtimeWorker` | 15 min |
| `PhysioMetabolicWorker` | 30 min |
| `BasalMlTrainerWorker` | 1 h |
| `AutodriveBackfillWorker` | 6 h |
| `AutodriveNeuralTrainerWorker` | 24 h |
| `PhysioDailyWorker` | 24 h |

iOS has no equivalent of any of them. The mapping:

1. **15 min and 30 min → fold into the loop tick.** The BLE CGM already wakes the app every ~5
   minutes (§6.1), so the physio refresh becomes free: run it on every tick, and the metabolic pass
   every sixth. This is *better* than the Android version, not worse.
2. **1 h basal trainer → measure first.** The wake window is roughly 10-30 seconds. One hidden layer
   over a bounded CSV window may well fit; if it does, train there. If it does not, it moves to
   category 3, and the loop must never wait on it.
3. **6 h and 24 h trainers → `BGProcessingTask`**, with `requiresExternalPower`. iOS runs these
   opportunistically, typically overnight on the charger. That is genuinely the right fit for model
   training.

There is an irony worth recording, because it is a trap. `AimiMlTrainingScheduler.kt` carries this
comment: *"setRequiresCharging + setRequiresDeviceIdle combo almost never coincided on real phones, so
the worker never [ran]"* - and those constraints were removed. **`BGProcessingTask` reimposes exactly
that model.** So the iOS training scheduler will hit the failure mode the Android code already
diagnosed and abandoned. Plan for it: the scheduler must be a real per-platform `actual` with its own
design, and the model must stay usable when training has not run for days.

**Hard requirement that falls out of this: training must never be on the dosing path.** Inference
runs in the wake window; training is opportunistic and may not happen. Any code that assumes a fresh
model is a defect on iOS.

### 11.4 Physiological data - Health Connect to HealthKit

Verified record types actually read by AIMI, and their HealthKit counterparts:

| Health Connect | HealthKit | status |
|---|---|---|
| `StepsRecord` | `HKQuantityTypeIdentifier.stepCount` | direct |
| `HeartRateRecord` | `.heartRate` | direct |
| `RestingHeartRateRecord` | `.restingHeartRate` | direct |
| `SleepSessionRecord` | `HKCategoryTypeIdentifier.sleepAnalysis` | maps, but the sleep-stage vocabulary differs |
| `BasalBodyTemperatureRecord` | `.basalBodyTemperature` | direct (manual entry in practice on both) |
| `SkinTemperatureRecord` | `.appleSleepingWristTemperature` | **partial** - sleep only, Apple Watch Series 8+ |
| `HeartRateVariabilityRmssdRecord` | `.heartRateVariabilitySDNN` | **different metric - see below** |

**The HRV mismatch is safety-relevant and must not be glossed over.** Health Connect gives **RMSSD**;
HealthKit publishes **SDNN**. Both derive from beat-to-beat intervals, and they are **not numerically
interchangeable**. In AIMI this is not a cosmetic field: `hrvRmssd` flows through
`AIMIInsulinDecisionAdapterMTR` into the stress and brake computation and from there into
`smbMult` - an insulin multiplier. The log line in that file spells the chain out:
`"PHYSIO ctx: steps15=, hr=, hrv=, conf= -> brake=, stress= -> smbMult="`.

What makes it survivable is that `AIMIPhysioBaselineModelMTR` learns a **personal percentile
baseline** (it keeps an RMSSD history and uses P50), so a metric swap partially self-corrects as the
baseline re-learns. That is a mitigation, not a free pass. Required steps:

1. Name the metric in the type, not just the field: an `HrvMetric.RMSSD` / `HrvMetric.SDNN` tag
   travelling with the value, so no consumer can silently assume one.
2. **Reset the personal baseline** when the source metric changes - never carry an RMSSD-learned
   baseline into SDNN.
3. **Audit every absolute HRV threshold.** Percentile comparisons self-correct; hardcoded millisecond
   thresholds do not, and would be permanently wrong.
4. Consider deriving RMSSD on iOS instead. HealthKit can expose beat-to-beat interval samples
   (`HKHeartbeatSeriesSample`), from which RMSSD can be computed directly - more work, but it removes
   the mismatch rather than managing it.

**Two structural differences beyond the mapping:**

- **HealthKit is push, Health Connect is poll.** `HKObserverQuery` plus
  `enableBackgroundDelivery(for:frequency:)` wakes the app when new samples land. This replaces the
  15-minute poll with something strictly better, and it can act as a **secondary heartbeat**
  alongside the CGM.
- **A denied read is indistinguishable from no data.** HealthKit deliberately does not reveal that
  read authorisation was refused - the query simply returns empty. AIMI's `PhysioLiveDigest` defaults
  `hrvRmssd` to `0.0`, and at least one consumer guards with `if (snapshot.hrvRmssd > 0)`. **Every
  physio consumer must be audited for that guard before iOS**, because "sensor absent", "permission
  denied" and "value is genuinely zero" must not collapse into the same number on a path that scales
  insulin. Represent absence as absence - a null or a sealed `Unavailable` - not as `0.0`.

Also required, and cheap: `NSHealthShareUsageDescription` in `Info.plist`, the HealthKit capability
enabled, and per-type authorisation requested explicitly.

### 11.5 Where these three fit in the plan

| area | phase | note |
|---|---|---|
| Pure-Kotlin NN + trainers to `commonMain` | Phase 0 / 2.1 | free, no seam needed |
| Delete TFLite, re-express the 4 KB model | Phase 2.8 | parity test before deleting |
| Stub ONNX | Phase 2.9 | dormant feature |
| File paths → `expect` provider + `okio` | Phase 2.5 | 37 files |
| Training scheduler → per-platform `actual` | Phase 2.6, **redesigned** | not a port; `BGProcessingTask` |
| Health Connect → `expect` physio source | Phase 2.7 | Android `actual` unchanged |
| HealthKit `actual`, HRV metric tag, baseline reset, absence audit | Phase 3 | the safety-relevant one |
