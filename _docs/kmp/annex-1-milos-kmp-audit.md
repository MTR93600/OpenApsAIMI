# Forensic audit: what Milos actually built on the `kmp` branch

Read-only audit, no files modified. Working tree = `kmp-aimi-migration-study`, byte-identical to
`kmp` (both at `4957c26eb8`, 2026-08-20).

| Fact | Value |
|---|---|
| Commits `dev..kmp` | **153** (151 non-merge + 2 merges of `dev`) |
| Calendar span | **2026-08-05 → 2026-08-20 = 16 consecutive days**, no gaps |
| Author | Milos Kozak, 100% of commits |
| Total diff | 2,458 files, +30,454 / −16,996 |
| `dev` behind `kmp` | `dev` is 6 commits ahead (dependabot + one wear fix) — merge is trivial |
| Reference doc | `_docs/KMP_IOS_FEASIBILITY.md`, 2,115 lines, waves 1–18 |
| Doc currency | Wave narrative stops at wave 18 (2026-08-12). **11 of the 14 KMP modules were converted after the doc stopped being maintained.** |

---

## 1. Module-by-module state table

### 1.1 Modules with a `commonMain` source set

`.kt` file counts, current working tree, `build/` excluded.

| Module | total .kt | commonMain | androidMain | iosMain | jvmMain | other native | test src set | targets declared | `expect` decls |
|---|---:|---:|---:|---:|---:|---:|---|---|---:|
| `:core:data` | 82 | **67** | 0 | 2 | 3 | mingwX64Main, nativeMain | commonTest (4) + jvmTest (3) | jvm, iosArm64, iosSimulatorArm64, mingwX64 | 3 |
| `:core:graph` | 12 | **11** | 0 | 0 | 0 | – | androidHostTest (1) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:core:interfaces` | 289 | **250** | 23 | 3 | 0 | – | androidHostTest (13) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 3 |
| `:core:keys` | 48 | **47** | 0 | 0 | 0 | – | androidHostTest (1) | android(kmp-lib), jvm, iosArm64, iosSimulatorArm64 | 0 |
| `:core:nssdk` | 101 | **73** | 0 | 2 | 4 | mingwX64Main | jvmTest (20) | jvm, iosArm64, iosSimulatorArm64, mingwX64 | 2 |
| `:core:objects` | 59 | **25** | 7 | 0 | 0 | – | androidHostTest (27) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:core:ui` | 480 | **435** | 12 | 3 | 0 | – | androidHostTest (30) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 3 |
| `:core:utils` | 27 | **5** | 17 | 0 | 0 | – | androidHostTest (5) | android(kmp-lib), jvm, iosArm64, iosSimulatorArm64 | 0 |
| `:plugins:aps` | 77 | **23** | 22 | 0 | 0 | – | androidHostTest (32) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:plugins:calibration` | 15 | **9** | 1 | 1 | 0 | – | androidHostTest (4) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 1 |
| `:plugins:main` | 6 | **2** | 0 | 0 | 0 | – | androidHostTest (4) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:plugins:sensitivity` | 11 | **6** | 0 | 0 | 0 | – | androidHostTest (5) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:plugins:smoothing` | 8 | **7** | 0 | 0 | 0 | – | androidHostTest (1) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 0 |
| `:pump:virtual` | 10 | **6** | 1 | 1 | 0 | – | androidHostTest (2) | android(kmp-lib), iosArm64, iosSimulatorArm64 | 1 |
| **TOTAL (14)** | **1,225** | **966** | **83** | **12** | **7** | | | | **14** |

`:pump:combov2:comboctl` also has a `commonMain/` folder (36 .kt) but **is not a KMP module** — it is
a plain `com.android.library` whose folder names are upstream leftovers. The doc says this explicitly
in §1. It is the only place a `java.*` import survives inside a folder called `commonMain`
(`java.util.concurrent.Executors` in `base/Dispatchers.kt`). Do not count it as migrated.

### 1.2 Target declaration mechanics

- Two shapes. `:core:data` and `:core:nssdk` are **pure** `kotlin("multiplatform")` with
  `jvm() + iosArm64() + iosSimulatorArm64() + mingwX64()` and **no Android target at all** — Android
  consumers resolve the `jvm` variant. `mingwX64` is kept deliberately: it is the only Kotlin/Native
  target whose tests can *run* on the Windows dev machine.
- The other 12 use `alias(libs.plugins.android.kmp.library)` = `com.android.kotlin.multiplatform.library`,
  with the `android { }` block **inside** `kotlin { }`. Every one of those build files carries the same
  comment: *"NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin."*
- Every UI-bearing module also applies `compose-compiler` + `compose-multiplatform` (`org.jetbrains.compose`),
  because the Compose compiler plugin is applied **per project, not per target**, so every target needs a
  Compose runtime on its classpath.
- **No module declares `iosX64`, `macos*`, `linux*`, or a `binaries { framework { } }` block.** Nothing
  produces an XCFramework.

### 1.3 `expect` / `actual` — the entire platform surface

14 `expect` declarations. This is the whole thing under 84.9 KLOC of shared code.

| `expect` | Module | iOS `actual` |
|---|---|---|
| `NumberFormatPlatform` (object, 4 members) | `:core:data` | `NSNumberFormatter` |
| `systemUtcOffsetAt(Long): Long` | `:core:data` | `NSTimeZone` (`* 1000`) |
| `devAssert(Boolean)` | `:core:data` | native, own opt-in |
| `nsHttpClient(...)` | `:core:nssdk` | Ktor Darwin engine |
| `nsIoDispatcher` | `:core:nssdk` | – |
| `AapsLock()` (class, 2 members) | `:core:interfaces` | reentrant lock |
| `aapsIoDispatcher` | `:core:interfaces` | – |
| `bluetoothPermissionGroup(): PermissionGroup?` | `:core:interfaces` | `null` (Info.plist) |
| `stringResource(TextRef): String` | `:core:ui` | **PLACEHOLDER — see §5.2** |
| `SystemBarAppearance(Boolean)` | `:core:ui` | no-op |
| `smallestScreenWidthDp(): Int` | `:core:ui` | `LocalWindowInfo` |
| `noFontPaddingPlatformStyle()` | `:core:ui` | – |
| `CalibrationScatterChart(...)` | `:plugins:calibration` | **STUB — see §5.2** |
| `virtualPumpSerialNumber(): String` | `:pump:virtual` | `identifierForVendor` |

Actual-declaration counts by source set: `core/data` iosMain 6 / jvmMain 7 / mingwX64Main 6 / nativeMain 1;
`core/interfaces` androidMain 5 / iosMain 5; `core/nssdk` iosMain 2 / jvmMain 2 / mingwX64Main 2;
`core/ui` androidMain 4 / iosMain 4; `plugins/calibration` 1+1; `pump/virtual` 1+1.

### 1.4 Modules with ZERO commonMain (37 of 51)

`:app`, `:benchmark`, `:database:impl` (166 prod .kt), `:database:persistence` (33), `:implementation` (115),
`:plugins:automation` (100), `:plugins:configuration` (23), `:plugins:constraints` (39), `:plugins:source` (34),
`:plugins:sync` (205), `:ui` (282), `:wear` (114), `:workflow` (6), `:shared:impl` (15), `:shared:tests`,
`:pump:combov2` (19), `:pump:common` (25), `:pump:dana` (24), `:pump:danar` (88), `:pump:danars` (60),
`:pump:diaconn` (136), `:pump:eopatch` (210), `:pump:equil` (97), `:pump:insight` (261), `:pump:medtronic` (64),
`:pump:medtrum` (83), `:pump:omnipod:common` (165), `:pump:omnipod:dash` (23), `:pump:omnipod:eros` (24),
`:pump:rileylink` (68), + the 3 emulator modules and `:wear:watchfacepush`.

---

## 2. The migration playbook Milos actually used

This is the reusable output. It is reconstructed from the commit sequence + build files + KDoc, not
from the doc alone (the doc stops before most of it).

### 2.0 The governing method: **fixpoint iteration, not file-by-file porting**

The single most important technique, stated verbatim in `949a587cd5`:

> *"Found by moving all of androidMain to commonMain, compiling for iosArm64, moving the failures back,
> and repeating until the build is green. That took six rounds: 32 files fail on their own Android
> imports, then 46 more because they referenced those 32, then 19, then 10, then 1. **One round is not
> enough and gives a much larger answer**, because a file can compile only while a dependency that
> later has to move back is still next to it."*

And in `19d23293cf`: *"the fixpoint jumps from 156 files in commonMain to 204, leaving 56. Six rounds
again: 31 files fail on their own imports, then 11, 10, 3 and 1."*

So the loop is:

1. `git mv src/androidMain/** src/commonMain/**` (everything).
2. `./gradlew :module:compileKotlinIosArm64`.
3. Move only the failures back to `androidMain`.
4. Repeat until green — typically **6 rounds**.
5. Each remaining `androidMain` file is then a *named* piece of debt with a reason.

The insight he records: *"The point is not the 22 files, it is that the iosArm64 target now fails the
build if an Android import appears in any of them."* The target is a **ratchet**, not a deliverable.

### 2.1 Ordered transformation sequence (the "before you can flip" list)

Applied in this order, each as its own commit, each buildable:

| # | Transformation | Evidence | Scale |
|---|---|---|---|
| 1 | **Dead code out first**, defaults in, characterization tests written against the OLD stack | `e24476237b`, `d23b9be3f1`, `74644c7667` | — |
| 2 | `java.text.DecimalFormat` → `NumberFormat` (own type) + `NumberFormatPlatform` seam | wave 1, on `dev` | 74 files |
| 3 | `java.util.concurrent.TimeUnit` → `kotlin.time.Duration`; `TimeDiff` replaces the TimeUnit map | wave 2, `d143b22f32` | 93 sites |
| 4 | `org.json` → `kotlinx.serialization` `JsonObject`, module by module | `f6a4e85e8a`, `ec05bdffe6`, `7d26bebbe9`, `a929ae1425`, `9eb22e76a1`, `aff51ea099` | 227 files repo-wide |
| 5 | Gson → kotlinx.serialization (via a Retrofit `converter-kotlinx-serialization` intermediate step) | `350f486be4` | 46 files |
| 6 | joda-time / `java.time` → `kotlinx-datetime` | `cb7b8fa924`, `ba3c465093` | 5 files |
| 7 | Retrofit + OkHttp → **Ktor** (contract test suite written first, against Retrofit) | `e92ec082d3` then `ed9c87599f` | `:core:nssdk` |
| 8 | `@StringRes Int` → **`TextRef`** everywhere in module APIs | `307b1ed615`, `95da0fa7ca`, `29dd6ff33e`, `f400188f37`, `7ba30dcdc6` | 342+ call sites |
| 9 | `ResourceHelper` → **`TextResolver`** in shared signatures | `a9c537a265`, `af715a6617`, `5ab8f8e9ff` | — |
| 10 | **RxJava `Observable` → `Flow`** across the whole app (the bus, then every consumer) | 20 commits on 2026-08-15 | **551 files, +3,395 / −2,340** |
| 11 | `RxBus.toFlow(Class<T>)` → `toFlow(KClass<T>)`; `Event` becomes a `data class` in commonMain | `978f0ef234`, `19d23293cf` | 158 files, 208 call sites |
| 12 | `Spanned`/HTML → `AnnotatedString` (+ own 60-line `htmlToAnnotatedString`) | `37a9cedf5d`, `feddd57589` | — |
| 13 | `System.currentTimeMillis()` → injected `Clock`; `@Synchronized` → `AapsLock` | `b092230284`, `73699d1095` | scoped, not repo-wide |
| 14 | `java.math.BigDecimal` out of `Round` | `9ba7d1a7e5` | — |
| 15 | Resource-id **sentinels** (`0`, `-1`) deleted — made unrepresentable by the type change | `307b1ed615` | 26 enums |
| 16 | Raw resource ids as data → **enums** (`AlarmSounds`), `@DrawableRes Int` → `ImageVector` | `d8b74a5eb2`, `PluginDescription.icon` | — |
| 17 | `Parcelable` dropped where vestigial; `kotlin-parcelize` removed from build | `8bca337112`, `84ecd73795` | — |
| 18 | `Dagger Provider` → **plain factory lambda** in shared code | `fff05a5df1` | `Command`, `QuickWizard` |
| 19 | **Drop `HasAndroidInjector`**; field `@Inject` → constructor params | `a8ea386028` | 352 → 304 files |
| 20 | **Move Android entry points to `:app`** (Services, Receivers, Workers, Fragments) | `4957c26eb8`, `73699d1095`, `109632e72d` | 9 files + 6 tests |
| 21 | **Lift the whole Dagger module to `app/src/main/kotlin/app/aaps/di/<Module>.kt`** | 9 new files | see §2.3 |
| 22 | `PluginBase` / `PluginBaseWithPreferences` / `PumpPluginBase` to commonMain | `9d1eb070bb`, `0fcca75ce1` | the spine |
| 23 | **Flip the module** (build.gradle.kts rewrite, §2.4) | `6b64ba9400`, `981e21a6b0`, … | ~110 lines of Gradle |
| 24 | Fixpoint sweep (§2.0) until `commonMain` is maximal | `949a587cd5`, `e63983a3ff` | — |
| 25 | Write `expect`/`actual` for the irreducible remainder | 14 total | — |

### 2.2 The seam abstractions he invented

**`TextRef` — `core/keys/.../interfaces/TextRef.kt`.** The keystone. A sealed interface with three cases:

```kotlin
sealed interface TextRef {
    data class AndroidRes(val id: Int, val args: List<Any> = emptyList()) : TextRef
    data class Named(val owner: String, val name: String, val args: List<Any> = emptyList()) : TextRef
    data class Literal(val text: String) : TextRef
    companion object { fun TextRef.withArgs(vararg args: Any): TextRef = ... }
}
```

Note the doc (wave 10) still documents the old shape `TextRef.Res(id, args)`. The code has since
renamed it to `AndroidRes` and added `Named`. **Doc/code drift — trust the code.**

- `AndroidRes` = the migration escape hatch. 172 files still use it. Costs nothing; modules migrate
  one at a time.
- `Named(owner, name, args)` = the platform-neutral handle. `owner` exists because a string name is
  only unique *within one module* — `ns_wifi_ssids` genuinely exists in both `:core:keys` and
  `:core:ui` with different Bulgarian translations, and a resolver guessing by lookup order would
  silently pick the wrong one.
- `Literal` = replaces the `titleResId = 0` / `= -1` sentinels. After the change "no title" is not
  representable, so the trap cannot come back.
- **"Do not persist it"** is in the KDoc: `AndroidRes.id` must never reach preferences, the DB,
  Nightscout or wear, because the same number means different things in different builds.

**`GenerateKeyStringsTask` — `buildSrc/src/main/kotlin/`, 248 lines.** The mechanism that makes
`Named` cheap. One pass over a module's `res/values/strings.xml` emits two files:

| Generated | Source set | Content |
|---|---|---|
| `<X>Strings` | `commonMain` | `val foo: TextRef = TextRef.Named("owner", "foo")` — no Android types |
| `<X>StringIds` | `androidMain` | `"foo" to R.string.foo`, plus `idOf(name)` |

Same pass, so they cannot drift: delete a string from the XML and both sides vanish and every call
site stops compiling. **This kills the three objections to name-based lookup**: no reflection (R8 sees
literal `R.string.x`), nothing kept alive, a typo is a compile error. The task also prints per-locale
translation completeness — the only translation detector in the build, since `MissingTranslation`
lint is disabled repo-wide.

Wired into **9 modules**: `:core:keys` (KeysStrings), `:core:ui` (UiStrings), `:core:interfaces`
(InterfacesStrings), `:plugins:aps`, `:plugins:main`, `:plugins:smoothing`, `:plugins:sensitivity`,
`:plugins:calibration`, `:pump:virtual`. Current adoption: **1,133 references across 139 files**
(KeysStrings 342, UiStrings 502, InterfacesStrings 136, ApsStrings 94, rest 59).

**The critical consequence: NO resource file ever moves.** `crowdin.yml` is untouched, AAPT still
resolves every locale, `gsNotLocalised` still works. This is the decision that made the whole
string problem tractable, and it was reached only after compose-resources and moko-resources were
both evaluated and rejected with published-source evidence (wave 14).

**`TextResolver` — `core/interfaces/.../resources/TextResolver.kt`.** The 4-method subset of
`ResourceHelper` that every platform can implement: `gs(TextRef)`, `gs(TextRef, vararg)`,
`gsNotLocalised(TextRef)`, `shortTextMode()`. `ResourceHelper` (androidMain) extends it and adds the
`Int` overloads. The KDoc names the real motive: *"most files in this module never call a resolver —
they only name the type in a signature… Naming `TextResolver` instead lets those files move to
commonMain **without any change to their callers**."* A pure widening; zero call-site churn.

**`TextRefIdRegistry` — `core/interfaces/src/androidMain/`.** Added after a real regression the
emulator caught: `ResourceHelperImpl` lives in `:core:interfaces` and cannot see `UiStringIds`
(`:core:ui` depends on it, not the reverse), so a `ui`-owned name resolved outside a Composable
printed its own name — the overview showed `format_carbs` instead of `58 g`. Fix: a registry consulted
for any owner `keysIdOf` doesn't know, with `ResourceHelperImpl` registering `"ui"` in its `init`.
**Anyone repeating this recipe across module boundaries will hit exactly this bug.**

**`NumberFormatPlatform` — `core/data/.../format/`.** `expect object` with `SEPARATOR_DOT`,
`localeSeparator`, `format(NumberFormat, Double)`, `format(NumberFormat, Double, Char)`. The KDoc
states the principle: locale data (separators, minus sign, digit shapes) is CLDR and belongs to the
platform; reimplementing it in common Kotlin means carrying megabytes and then disagreeing with the OS.
Later gained a `NumberRounding` enum after a near-miss (§5.3).

**`AapsLock` — `core/interfaces/.../concurrent/`.** `expect class` replacing `@Synchronized`, which is
JVM-only. KDoc pins the contract: **reentrant** and **blocking**, and explicitly warns
`kotlinx.coroutines.Mutex` is NOT a drop-in (not reentrant, needs a coroutine). Android `actual`
delegates straight to `kotlin.synchronized`, so generated code and runtime behaviour on Android are
unchanged. `withLock` is `inline` so `return` from inside the block still compiles.

**`PluginBase` in commonMain.** `abstract class PluginBase(pluginDescription, aapsLogger, rh: TextResolver)`.
`pluginId` uses `this::class.simpleName` not `javaClass.simpleName`. Carries its own
`pluginScope = CoroutineScope(Dispatchers.Default + Job())`.

**`PumpPluginBase` in commonMain.** `PluginBaseWithPreferences` + `CommandQueue`. `onStart()` launches
the initial `readStatus` on `pluginScope` after `delay(6000)` — replacing an Android `Handler`
`postDelayed`. `requiredPermissions()` = `listOfNotNull(bluetoothPermissionGroup())`, which is the
`expect` that returns `null` on iOS.

**`PluginDescription`.** `pluginName`, `shortName`, `description` are all `TextRef?`; `icon` is an
`ImageVector?`. The doc lists the `description: Int` `-1` sentinel as follow-up #3 "still open" —
**it is done in the code.** Doc is behind.

**`OrgJsonCompat` — `core/data/src/commonMain/.../json/`, 146 lines.** A shared shim so code that
speaks `org.json` idiom can run on kotlinx JSON, with `OrgJsonCompatParityTest` (253 lines, `jvmTest`)
using the real Android `org.json` as the **oracle**. Note the rule in the build file: *"the only place
org.json may appear"*.

**`TimeDiff`, `ReadableDuration`, `devAssert`, `aapsIoDispatcher`** — smaller seams following the same shape.

### 2.3 The DI decision (recorded as final, in the doc's "Decisions taken")

**Rule: a module can be multiplatform only if it carries NO Dagger annotation anywhere — not in
`commonMain`, and not in `androidMain` either.**

The reason is written down because the failure is silent. Probed on `:plugins:smoothing` with
`add("kspAndroid", dagger.compiler)`:

- `kspAndroidMain` runs and writes `ProbeThing_Factory.java`
- the only `JavaCompile` task in the whole build is `:buildSrc:compileJava NO-SOURCE`
- `build/classes/` gets the Kotlin and **no** `*_Factory.class`
- **`BUILD SUCCESSFUL`**

So Dagger emits Java into a target with no javac and nothing reports it. The module *looks* converted
and its factories do not exist.

**Consequence: DI wiring is lifted into one file per module under `app/src/main/kotlin/app/aaps/di/`.**
Nine such files now exist: `CoreObjectsModule`, `VirtualPumpModule`, `SmoothingPluginsModule`,
`CalibrationPluginsModule`, `SensitivityPluginsModule`, `MainPluginsModule`, `ApsPluginsModule`,
`AutomationAndroidModule`, `PersistentNotificationModule`.

**Hidden architectural cost, not flagged in the doc.** The deleted per-module DI files were
*self-registering*: their KDoc said *"Including `:plugins:smoothing` in settings.gradle is enough — no
central list edit needed."* The lifted versions live in `:app`, so **every converted plugin now requires
a central edit in `:app`**, and `@Binds` became `@Provides` with hand-written construction. Modularity
regressed to buy portability. For AIMI this matters: it means the AIMI plugin's DI graph must be
re-expressed by hand in `:app`.

**The exit is documented**: Dagger PR #5234 (merged 2026-07-30) teaches the Hilt Gradle plugin about
`com.android.kotlin.multiplatform.library`. The project is on Dagger 2.60.1, which predates it. The
instruction is precise: when a release lands, **re-run the probe and check for `*_Factory.class`, not
for a green build.**

**No alternative DI framework was adopted.** Koin / kotlin-inject / Metro remain listed as options in
§3 of the doc and were never chosen. There is no DI on the iOS side at all.

### 2.4 The module flip recipe (`build.gradle.kts`)

Canonical example, `:plugins:smoothing` (~110 lines). Every converted module is a near copy.

```kotlin
plugins {
    kotlin("multiplatform")
    alias(libs.plugins.android.kmp.library)   // NOT com.android.library — AGP 9 refuses
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val generateSmoothingStrings = tasks.register<GenerateKeyStringsTask>("generateSmoothingStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.plugins.smoothing"); owner.set("smoothing")
    objectName.set("SmoothingStrings"); idsObjectName.set("SmoothingStringIds")
    // Set explicitly: addGeneratedSourceDirectory derives its convention from the task name, so both
    // properties would land on one directory and the second file written would delete the first.
    commonOutputDir.set(...); androidOutputDir.set(...)
}

kotlin {
    android {
        namespace = "..."; compileSdk = Versions.compileSdk; minSdk = Versions.minSdk
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true; isReturnDefaultValues = true }  // Robolectric
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }
        lint { checkReleaseBuilds = false; disable += "MissingTranslation"; disable += "ExtraTranslation" }
    }
    iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain { kotlin.srcDir(generateSmoothingStrings.flatMap { it.commonOutputDir }); dependencies { ... } }
        androidMain { kotlin.srcDir(generateSmoothingStrings.flatMap { it.androidOutputDir }) }
        getByName("androidHostTest") { dependencies { /* hand written — see below */ } }
    }
}
// :shared:tests is a flavoured Android library, so the host test classpath must pin a flavour.
listOf("androidHostTestCompileClasspath", "androidHostTestRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes {
        attribute(com.android.build.api.attributes.ProductFlavorAttr.of("standard"), objects.named("full")) } }
}
tasks.withType<Test> { useJUnitPlatform() }
```

**Five gotchas the recipe encodes, each of which costs a day if rediscovered:**

1. `com.android.library` + `kotlin("multiplatform")` is refused outright by AGP 9. Must use
   `com.android.kotlin.multiplatform.library`, with `android { }` *inside* `kotlin { }`.
2. `platform()` does not exist in a KMP source-set dependency block — use `project.dependencies.platform(...)`.
3. Dropping the `android-module-dependencies` convention plugin **silently switches `MissingTranslation`
   lint on** and restores `checkReleaseBuilds = true`. With 19 empty locales that fails a release build.
   The `lint { }` block must be restated per module.
4. The convention plugins (`android-module-dependencies`, `test-module-dependencies`) apply
   `com.android.library` and therefore **cannot be used** — the `androidHostTest` dependency block must
   be hand-written in every converted module (~15 lines duplicated 12×).
5. `:shared:tests` is a flavoured library ⇒ the host-test classpath must pin `ProductFlavorAttr "full"`
   or resolution is ambiguous.

Plus: `runtests.sh` / `.bat` must run **`testFullDebugUnitTest allTests`**. Without `allTests`, a
multiplatform module has no `testFullDebugUnitTest` task and **silently runs zero tests** — which is
what had been happening to `:core:data` and `:core:nssdk` for waves 5–9 unnoticed.

### 2.5 Behaviour-preservation discipline (worth copying wholesale)

- **Characterization tests written first, against the old stack.** `e92ec082d3` wrote the Nightscout
  contract suite against Retrofit *before* the Ktor port. `d23b9be3f1` / `74644c7667` characterized
  profile JSON with real Nightscout shapes before touching it.
- **An explicit oracle.** `OrgJsonCompatParityTest` uses the real Android `org.json` as truth.
  `NumberFormatTest` uses the real `DecimalFormat` as truth: *"`DecimalFormat` is the reference,
  because it is what every existing AAPS number has been rendered with."*
- **Two-stage serialization change.** Gson → Retrofit-`converter-kotlinx-serialization` → Ktor, so the
  quiet-wrong-data risk and the transport risk were never in the same commit. Recorded reasoning in
  open decision 8.
- **Adversarial audits.** *"Five parallel agents against the migrated code, each trying to find an
  input where old and new differ, with a second pass trying to refute what they found."* Results:
  DecimalFormat→NumberFormat identical across 17 patterns × ~90 values × ~800 locales;
  TimeUnit→kotlin.time compared by reflection over 42 million values (diverges only past ~146 million years).
- **Mixed-version device verification.** Waves 5–9 each verified against a live Nightscout, waves 7–9
  on an emulator running a new master against a **pre-KMP client**, so every wire-format step was
  checked in a mixed-version pair.
- **Failure-mode-aware verification.** *"The failure mode of these waves is a blank label, not a crash,
  so they need eyes rather than logcat."*
- **The commit messages carry the reasoning.** ~1,335 lines of commit body over 153 commits. Several
  (`19d23293cf`, `e63983a3ff`, `feddd57589`, `949a587cd5`) are better documentation than the doc.

---

## 3. Effort / velocity baseline

### 3.1 Raw per-module data

`commits` = commits in `dev..kmp` touching that path (double-counts cross-cutting commits).
`subj` = commits whose *subject line* names the module (the isolable "flip cost").
`files` / `+ / −` from `git diff --shortstat dev..kmp -- <path>`.

| Module | commonMain KLOC | commits (path) | subj | files touched | + | − | span | days |
|---|---:|---:|---:|---:|---:|---:|---|---:|
| `:core:data` | 3.36 | 16 | 4 | 84 | 1,280 | 158 | 08-05→08-19 | 15 |
| `:core:graph` | 1.94 | 5 | 1 | 14 | 113 | 58 | 08-10→08-18 | 9 |
| `:core:interfaces` | 13.52 | 69 | 12 | 349 | 3,070 | 996 | 08-07→08-20 | 14 |
| `:core:keys` | 3.56 | 10 | 4 | 82 | 842 | 1,185 | 08-07→08-20 | 14 |
| `:core:nssdk` | 4.55 | 11 | 9 | 122 | 4,596 | 1,496 | 08-06→08-17 | 12 |
| `:core:objects` | 3.32 | 29 | 5 | 72 | 2,897 | 1,133 | 08-05→08-20 | 16 |
| `:core:ui` | 42.50 | 37 | 12 | 635 | 3,142 | 2,312 | 08-07→08-20 | 14 |
| `:core:utils` | 0.26 | 6 | 1 | 32 | 344 | 255 | 08-11→08-19 | 9 |
| `:plugins:aps` | 6.62 | 19 | 1 | 161 | 969 | 1,432 | 08-07→08-20 | 14 |
| `:plugins:calibration` | 1.12 | 8 | 2 | 20 | 312 | 157 | 08-07→08-19 | 13 |
| `:plugins:main` | 1.09 | 13 | 1 | 49 | 402 | 818 | 08-10→08-20 | 11 |
| `:plugins:sensitivity` | 0.69 | 3 | 1 | 45 | 124 | 97 | 08-16→08-19 | 4 |
| `:plugins:smoothing` | 1.58 | 4 | 1 | 42 | 178 | 139 | 08-07→08-19 | 13 |
| `:pump:virtual` | 0.76 | 10 | 3 | 45 | 252 | 191 | 08-07→08-19 | 13 |
| *(support)* `:plugins:automation` | 0 | 16 | 3 | 120 | 2,281 | 2,118 | 08-08→08-20 | 13 |
| *(support)* `:app` | 0 | 27 | – | 55 | 3,103 | 315 | 08-10→08-20 | 11 |
| **TOTAL** | **84.87** | **153 actual** | **59** | **2,458** | **30,454** | **16,996** | **08-05→08-20** | **16** |

Path-attributed commits sum to 245 against 153 real commits — the overlap **is the finding**.

### 3.2 The number that actually matters: horizontal vs vertical

| Class | Commits | Share |
|---|---:|---:|
| Cross-cutting seam work (subject names no module) | **94** | **61%** |
| Module-named commits | 59 | 39% |

**61% of the effort was building horizontal seams that every module then reuses.** The per-module flip
is cheap *only because* that was paid first.

Proof, from the flip commits themselves:

| Flip commit | files | + | − | .kt |
|---|---:|---:|---:|---:|
| `6b64ba9400` `:core:ui` becomes multiplatform | 625 | **225** | **114** | 479 |
| `5b41d264c9` `:core:objects` KMP | 235 | 407 | 300 | 230 |
| `109632e72d` `:plugins:aps` is multiplatform | 174 | 1,278 | 618 | 100 |
| `869da2b3cc` `:plugins:main` is multiplatform | 59 | 385 | 209 | 26 |
| `43d7ad3d0f` `:plugins:sensitivity` is multiplatform | 53 | 284 | 123 | 19 |
| `981e21a6b0` `:plugins:smoothing` fully multiplatform | 43 | 260 | 137 | 10 |
| `e8bb09584e` `:pump:virtual` becomes multiplatform | 44 | 280 | 151 | 11 |
| `b6a026080f` `:core:graph` 11 of 11 in commonMain | 22 | 133 | 71 | 20 |
| `74fabe33e9` `:plugins:calibration` multiplatform | 7 | 67 | 10 | 6 |

`:core:ui` is the headline: **42.5 KLOC flipped in a 339-line diff**, because 479 of the 625 changed
paths are pure `git mv` renames.

### 3.3 Segmented ratios

| Segment | Modules | commonMain KLOC | subj commits | commits/KLOC | flip diff lines/KLOC |
|---|---|---:|---:|---:|---:|
| **(a) Clean data** | data, nssdk, keys, graph | 13.41 | 18 | **1.34** | ~15 |
| **(b) UI / contract** | ui, objects, interfaces, utils | 59.60 | 30 | **0.50** | ~13 |
| **(c) Plugins** | aps, main, sensitivity, smoothing, calibration, virtual | 11.86 | 9 | **0.76** | ~230 |

These ratios are **not** a cost model on their own — segment (b) looks cheapest per KLOC only because
`:core:ui` was already 424/434 files Compose-only, i.e. the Compose migration had already paid the bill.

### 3.4 Working-day estimate

- Commits on **all 16 calendar days**, no gaps. Per-day: 4, 8, 2, 3, 16, 16, 8, 8, 6, 12, 23, 10, 12, 8, 12, 5.
- Commit-hour histogram spans **07:00 → 23:00**, mode at 22:00 (23 commits). Evening-weighted, and the
  spread is not consistent with part-time work.
- Estimate: **16 working days at 10–14 h/day ≈ 160–220 engineer-hours**, i.e. roughly **4–5.5 normal
  engineer-weeks compressed into 16 days**.

### 3.5 Confidence and caveats — read before extrapolating

1. **This is a one-person, deep-context baseline.** Milos is the project's lead maintainer. He knows
   which code is dead (`NSSettingsStatus` "never received any data") without measuring. A second
   engineer on AIMI does not get this multiplier.
2. **It is agent-augmented.** The doc records *"five parallel agents"* for the parity audit and a
   *"seven-agent pass over the pump layer"*. The repo carries `CLAUDE.md` and
   `.github/workflows/claude-code-review.yml`. No commit carries a `Co-Authored-By` trailer, so the
   *share* is unmeasurable — but **do not model this as unaided hand-typing throughput.**
3. **The 84.9 KLOC is not 84.9 KLOC of *rewriting*.** Most of it was `git mv`. The genuine edit volume
   is 30,454 insertions / 16,996 deletions across the whole branch, and a large slice of that
   (551 files, +3,395/−2,340) is the single mechanical Rx→Flow sweep.
4. **`:core:ui` was pre-paid.** AAPS had already completed an XML→Compose migration and an earlier
   RxJava removal. `:core:ui` had **0 XML layouts**, **0 Dagger imports**, and only **16** `android.*`
   imports out of 434 files before any KMP work. **An unmigrated codebase does not get this rate.**
5. **The doc's own cost rule is the one to use: "a module's conversion cost is roughly its Dagger count."**
   Not its KLOC. `:plugins:sensitivity` (7 files, no Dagger, no `android.*`) was 3 commits over 4 days.
   `:plugins:configuration` (22 of 23 files Dagger-bound) was never converted.
6. **Nothing here is merged or released.** `kmp` has never been merged to `dev`; open decision 11 has
   been "still open" since wave 9. No integration or regression cost has been paid yet.

### 3.6 Applying this to AIMI — the measured gap

| | upstream `:plugins:aps` (`dev`) | AIMI `:plugins:aps` (`dev_OAPSAIMI`) |
|---|---:|---:|
| prod `.kt` files | 52 | **495** |
| prod LOC | 12,383 | **114,793** |
| of which `openAPSAIMI/` | – | 441 files / **101,913 LOC** |
| test files / LOC | – | 277 / 38,214 |

**AIMI's aps plugin is ~9.3× the upstream one Milos converted, and he only got 55% of upstream's into
commonMain** (23 files / 6.6 KLOC common vs 22 files / 5.5 KLOC android — `LoopPlugin` and the entire
Autotune subsystem stayed Android).

Naive KLOC scaling from segment (c) (0.76 commits/KLOC) gives ~87 commits for 114.8 KLOC. **Treat that
as a floor, not an estimate**, because:
- the 61% horizontal seam cost was already paid by Milos and AIMI inherits it *only if AIMI rebases on `kmp`*;
- AIMI has 60+ subpackages (`physio` 37 files, `pkpd` 27, `safety` 24, `recursive` 22, `patient` 18 …),
  each of which needs its own Dagger lift;
- AIMI code is numeric/algorithmic, i.e. closer to segment (a)'s 1.34 commits/KLOC than segment (b)'s 0.50;
- there is no Compose-migration head start inside `openAPSAIMI/` comparable to `:core:ui`'s.

A defensible range using segment (a)'s ratio on AIMI's 101.9 KLOC of `openAPSAIMI/`:
**~135 commits, ~25–40 working days for one deep-context engineer**, *after* rebasing onto `kmp`.
Without the rebase, add the 94 horizontal seam commits back.

---

## 4. What is NOT done

### 4.1 There is no iOS app. At all.
- **No `iosApp/`, no `.xcodeproj`, no `.xcworkspace`** anywhere in the repo.
- **No `binaries { framework { } }` block** in any module — nothing produces a framework or XCFramework.
- No SKIE, no CocoaPods, no SPM publication.
- The doc is honest about this (§5 describes it as future work), but any reader who sees "14
  multiplatform modules" and infers an iOS build exists would be wrong.

### 4.2 iOS CI covers 3 of the 14 modules
`.github/workflows/ios-ci.yml` is well built — it asserts tasks **executed** rather than merely
"BUILD SUCCESSFUL" (a disabled Kotlin/Native task reports SKIPPED and exits 0), checks each klib
manifest names its target, and pins `TZ=Europe/Prague` so the timezone actual is actually exercised.

**But its path filters and task list name only `core/data`, `core/nssdk`, `core/keys`.** The 11
modules converted after wave 16 — `:core:ui` (42.5 KLOC), `:core:interfaces` (13.5 KLOC),
`:core:objects`, `:core:graph`, `:plugins:aps/main/sensitivity/smoothing/calibration`, `:pump:virtual` —
**are never compiled for iOS in CI**. Milos's own warning in the workflow header
(*"Cross-compilability is also transitive: a single cinterop anywhere in the project dependency graph
turns these into no-ops"*) applies directly and is unguarded for exactly the modules where a
Compose-Multiplatform dependency makes it most likely to break. **I could not verify locally that
`:core:ui:compileKotlinIosArm64` succeeds at HEAD.**

### 4.3 Room is NOT multiplatform — and the doc overclaims it
Section 7 of the doc states *"Steps 1, 2 and 5 are done"*, where step 5 is *"Ktor Nightscout client,
Room KMP"*. Ktor is done. **Room is not started.** `database/impl/build.gradle.kts` at HEAD is still:

```
alias(libs.plugins.android.library)      // not KMP
api(libs.androidx.room.rxjava3)          // Rx
api(libs.com.google.code.gson)           // Gson
api(libs.com.google.dagger.hilt.android) // Dagger
api(libs.kotlin.reflect)                 // reflection — cannot exist on Native
```

166 prod `.kt` files, zero `commonMain`. **This is the single largest unstarted blocker**, and the
`kotlin.reflect` dependency is a hard Kotlin/Native wall.

### 4.4 DI framework decision: deferred, not made
Dagger/Hilt is still the only DI in the app. `javax.inject`/`dagger` appears in **1,053 files** on
`kmp` (down from 1,125 on `dev` — a 6% reduction). `HasAndroidInjector`: 304 files (from 352).
No Koin / kotlin-inject / Metro evaluation was performed. The strategy is "keep Dagger in `:app`, wait
for Dagger PR #5234 to ship" — a bet on an external release.

### 4.5 RxJava: mostly gone from shared code, still 158 files
210 files on `dev` → **158 on `kmp`**. `commonMain` is **completely clean** (0 `io.reactivex` imports).
The remainder is concentrated in pump drivers that the doc says will never be KMP:

`pump/eopatch` 113, `pump/omnipod` 15, `wear` 6, `plugins/sync` 5, `pump/diaconn` 4, `pump/dana` 4,
`plugins/source` 3, `app` 2, `database/impl` 1, and 1 each in `shared/impl`, `shared/tests`,
`pump/danars`, `plugins/aps`, `core/interfaces`.

### 4.6 `commonMain` purity: clean (verified)

| Check across all `src/commonMain` | Count |
|---|---:|
| `import android.*` | **0** |
| `import java.*` / `import javax.*` | **0** (1 in `comboctl`, which is not a KMP module) |
| Dagger / `@Inject` annotations | **0** (6 matches are all KDoc explaining their absence) |
| `io.reactivex` | **0** |
| `R.string.` | **0** (2 matches are KDoc text) |
| non-Compose `androidx` | 30, all KMP-published: `collection` (13), `lifecycle` (12), `navigationevent` (4), `annotation` (1) |

This is genuinely clean. The `iosArm64` target is doing its ratchet job for the 3 CI-covered modules.

### 4.7 Strings: seam done, migration ~12% done
`R.string.` still appears in **824 files** on `kmp` (932 on `dev`). Generated-object adoption is 1,133
references across 139 files. The biggest holders are `:ui` (175 files), `:plugins:automation` (86),
`:implementation` (83), `:wear` (68), `:plugins:sync` (66) — none converted.

### 4.8 compose-resources: rejected, permanently, with evidence
Not "not yet done" — **decided against**, and the reasoning is the most valuable defensive finding in
the whole doc. From the *published* `components-resources:1.11.1` sources jar:

1. `ResourceEnvironment` has an `internal` constructor; the only public producer reads
   `Locale.getDefault()`. **No locale override** ⇒ the always-English search index (`gsNotLocalised`,
   19 production call sites) could not be built at all.
2. `filterByLocale` has exactly three steps: exact language+region, language **with no region
   qualifier**, then no qualifiers. **No sibling-region fallback.** `LocaleHelper.currentLocale()`
   builds a region-less `Locale` for 22 of 25 in-app languages, and every `:core:keys` folder is
   region-pinned (`values-cs-rCZ`, no bare `values-cs`). Result: **8 of 11 translated locales would
   have silently fallen back to English** — bg, cs, es, fr, it, nb, ro, sk — plus de_AT, fr_CA, es_MX,
   nl_BE, zh_HK. *"Build green, no crash, no test catches it."*

moko-resources solves both but its 0.26.4 release predates Kotlin 2.4.0 with no commits since,
175 open issues, catalog pinned to Kotlin 2.1.0. Rejected as an unattended dependency in a medical app.

### 4.9 Other named-and-open items
- `wear/SmallestDoubleString.kt` — last non-seam `DecimalFormat`; builds patterns from a runtime string.
- Client-control crypto sits in `jvmMain`; golden vectors ready, no platform needs it yet.
- `MissingTranslation` lint still disabled for all 32 other string-owning modules.
- The live `:core:keys` translation bug (§9a): 19 of 30 languages are empty shells — **pre-existing on
  `dev`, not caused by this work**, but still live.
- `FoodManagement` comma defect: found, not fixed.
- WorkManager (44 files) has no iOS answer at all. The doc is blunt: *"iOS has no equivalent…
  A follower that expects to run every 5 minutes cannot work that way. The real answer is push (APNs)
  from a server, which changes the design, not only the code."*

---

## 5. Quality verdict

### 5.1 Verdict: **sound, reusable production refactoring — not a spike.** With three real caveats.

Evidence for "not a spike":

- **Every commit is buildable and behaviour-scoped.** No "WIP", no giant bang commit. The
  Gson→Ktor path was deliberately split into two so the risks were never bundled.
- **Tests grew, and nothing was silently dropped.** Repo-wide test `.kt` files: **1,212 on `dev` →
  1,256 on `kmp` (+44)**. Per module: `:core:nssdk` 5→20, `:core:objects` 18→27, `:core:interfaces`
  8→13, `:core:data` 3→7, `:core:ui` 26→30, `:app` 47→53. The two apparent decreases
  (`:plugins:main` 6→4, `:plugins:aps` 34→32) are **moves**, verified: `e6414b8bbc` relocated the
  extension tests to `:core:objects`, and every `:plugins:aps` test shows as `R084`–`R100` rename into
  `androidHostTest`.
- **The CMP spike was deleted once it had answered its question** (`f184269d6d`), with its result and
  build recipe recorded in the doc instead of left rotting in the tree.
- **Nothing was deleted without a recorded reason.** Only **22 production `.kt` files** are genuinely
  gone (all others are renames git's default detector missed). All 22 are: 9 lifted DI modules,
  7 Retrofit/OkHttp/Rx-client files replaced by Ktor, `NSSettingsStatus`+Impl (*"never received any
  data"*), `HtmlHelper` (replaced by `AnnotatedString`), `TotalDailyDoseExtension`, `RemoteProfileStore`.
  `OverviewFragment.kt` was a **0-byte file**. No feature was quietly dropped.
- **`CustomAction`/`CustomActionType` were kept against seven agents' recommendation to delete**,
  with the retention reasoning written into both types' KDoc *"because any future dead-code sweep will
  find them again and the finding will look new."* That is maintainer-grade judgement.

### 5.2 Every place functionality was STUBBED rather than ported (complete list)

There are exactly **two**, both `iosMain`-only, **neither affects Android**:

1. **`core/ui/src/iosMain/.../TextRefResource.ios.kt`** — the string resolver.
   ```kotlin
   actual fun stringResource(ref: TextRef): String = when (ref) {
       is TextRef.Literal    -> ref.text
       is TextRef.Named      -> ref.name          // returns "format_carbs", not "58 g"
       is TextRef.AndroidRes -> "?"
   }
   ```
   Self-labelled `PLACEHOLDER`. **This is the largest single piece of hidden iOS debt**: an iOS UI built
   on the current shared code would render raw string *names* and `?` for every one of the 172 files
   still on `AndroidRes`. The KDoc names the exit precisely (a `.strings` lookup keyed on
   `Named(owner, name)`), and the migration order is stated: `AndroidRes` files must move to `Named` first.
2. **`plugins/calibration/src/iosMain/.../CalibrationScatterChart.ios.kt`** — renders the text
   *"Calibration chart is not implemented on this platform yet"*. **The Android chart is unchanged and
   fully functional** (215 lines using `android.graphics.Paint` + `nativeCanvas.drawText`). The stated
   reason is credible: porting to `TextMeasurer` + `DrawScope.drawText` changes label *positioning*,
   *"which needs the chart in front of you to check rather than a compile."* The placeholder text is
   deliberately untranslated so it never reaches a translator.

**No Android functionality was stubbed out anywhere.** The "Calibration chart placeholder says it is
missing" commit is an iOS-side honesty fix (say it is missing rather than draw an empty box that looks
like a render failure), not an Android regression. **That specific worry is unfounded.**

### 5.3 Near-misses the process caught (why the discipline is worth copying)

- **The rounding trap.** `AdaptiveUnitDoublePreference` used `BigDecimal.setScale(n, HALF_UP)`. The
  multiplatform `NumberFormat` is half-even. At **0 decimals (mg/dL)** a tie `k + 0.5` **is** exactly
  representable, so half-even would have silently changed a **displayed glucose value** (4.5 → 4).
  Fix: a `NumberRounding` enum wired through all three actuals, defaulting to HALF_EVEN so nothing
  else moves. Logged as *"Not verified by hand: the rounding change… but it is a glucose display."*
- **The `format_carbs` regression** (§2.2, `TextRefIdRegistry`) — caught on an emulator, not by a test.
- **The `titleResId = 0` default** — grep said the guards were dead; 26 enums *defaulted* to 0 and one
  constant took the default. Found by reasoning, not by grep.
- **`SearchableItem.Wiki`'s `titleResId = 0` sentinel** — correct only by the accident that nothing
  read it. *"An `Int` field will hold a lie indefinitely."*
- **Three real locale bugs fixed in passing**: Danish `dk` matched no folder; `formatUS` used the
  wrong symbols under Arabic; `qs()` grouping was accidental and broken.

### 5.4 The three caveats

**(a) Verification stopped on 2026-08-18.** 13 commits carry an explicit *verified / emulator / on a
device* note in the body — the last is `5b41d264c9` (`:core:objects`, 08-18). The final six module
conversions — `:pump:virtual` fully, `:plugins:smoothing`, `:plugins:calibration`,
`:plugins:sensitivity`, `:plugins:main`, `:plugins:aps`, `:plugins:automation` — carry **no
verification note and no doc entry**. `:plugins:aps` in particular touched 174 files including
`LoopPlugin`'s module boundary. **I cannot confirm the Android app builds or runs at HEAD**, and
neither can the branch's CI: `aaps-ci.yml`, `pr-ci.yml` and `branch-ci.yml` were **not modified** on
`kmp` and only run `:app:assemble` / `:wear:assemble` — **they run no unit tests at all**. The only
test gate is the local `runtests.sh`, which Milos did correctly update with `allTests`.

**(b) The doc is 8 days and 11 modules stale, and overclaims in two places.**
- Section 7 claims step 5 (*"Ktor Nightscout client, Room KMP"*) is **done**. **Room KMP is not started.**
- Section 7 lists **three** modules as multiplatform. There are **fourteen**.
- Wave 10 documents `TextRef.Res`; the code says `TextRef.AndroidRes` and has gained `Named`.
- Follow-up #3 (`PluginDescription.description: Int` sentinel) is listed **open**; the code has it as `TextRef?`.
- The `commonTest` claim is worth reading carefully: **only `:core:data` has a `commonTest` source
  set** — 4 files, 344 lines, 29 tests. All other converted modules put their tests in
  `androidHostTest`, i.e. **JVM/Robolectric only**. So 966 shared files have shared-target coverage for
  exactly 4 of them. `iosSimulatorArm64Test` for `:core:nssdk` and `:core:keys` is NO-SOURCE and the
  workflow says so honestly, but the practical effect is that **~81.5 of the 84.9 shared KLOC has never
  been executed on Kotlin/Native.**

**(c) Modularity regressed to buy portability.** Self-registering per-module DI is gone; `:app` now
holds 9 hand-written wiring files and every future converted module adds a tenth. Combined with the
hand-copied `androidHostTest` dependency block in each of the 12 flipped modules (the convention
plugins can't be used), each conversion adds ~30 lines of duplicated build/wiring boilerplate that
`:app` and the module must keep in sync.

### 5.5 Claims the doc makes that the code does NOT confirm

| Doc claim | Reality at HEAD |
|---|---|
| §7 "Steps 1, 2 and **5** are done" (step 5 = Ktor **+ Room KMP**) | Room untouched: `database/impl` is `com.android.library`, 166 files, 0 commonMain, still on `room-rxjava3`, Gson, Hilt **and `kotlin.reflect`** |
| §7 "Three modules now build for Kotlin/Native" | 14 declare Apple targets; only **3** are compiled for iOS by CI |
| Wave 10 `TextRef.Res(id, args)` | renamed `TextRef.AndroidRes`; `Named(owner, name, args)` added in wave 14 |
| Follow-up #3 `PluginDescription.description: Int` "still open" | done — it is `TextRef?` |
| Wave 16 "29 tests execute on the iOS simulator" | still true, and still **all of them** — `commonTest` has grown by zero files since |
| Doc structure implies waves ≤18 | 11 of 14 modules were converted in undocumented waves 19+; the commit bodies are the only record |

---

## 6. The five things to carry into an AIMI migration

1. **Rebase AIMI onto `kmp` before doing anything else.** 61% of Milos's commits are horizontal seam
   work (`TextRef`, `TextResolver`, `GenerateKeyStringsTask`, `AapsLock`, `NumberFormatPlatform`,
   `PluginBase`/`PumpPluginBase` in commonMain, Rx→Flow, `RxBus` on `KClass`). Doing AIMI on `dev`
   means paying all of it again.
2. **Use the fixpoint loop, not file-by-file porting.** Move everything, compile for `iosArm64`, move
   failures back, repeat ~6 rounds. Budget for six, not one.
3. **Cost AIMI by its Dagger count, not its KLOC.** That is the doc's own rule and it matches the data:
   `:plugins:sensitivity` (no Dagger) took 3 commits; `:plugins:configuration` (22/23 files Dagger-bound)
   was never attempted. Count `@Inject`/`@Module`/`@Provides` sites in `openAPSAIMI/` first — that
   number is the estimate.
4. **Fix the two audit gaps before extending the pattern.** Add the 11 uncovered modules to
   `ios-ci.yml`'s path filter and task list, and put at least the numeric AIMI kernels
   (`pkpd`, `physio`, `basal`) in `commonTest` so they actually execute on Native. Right now 81.5 of
   84.9 shared KLOC has never run outside a JVM.
5. **Expect the `TextRefIdRegistry` bug.** Any module whose generated string names are read from
   outside Compose, by a resolver that lives *upstream* of it, will silently print the name instead of
   the text. It is a blank-label failure — invisible to the compiler, to lint, and to every test.
