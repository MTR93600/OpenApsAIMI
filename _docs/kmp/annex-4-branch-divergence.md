# A4 — Divergence and integration cost: `dev_OAPSAIMI` onto `kmp`

Read-only analysis. No tracked file changed, no branch switched, no worktree created.

| item | value |
|---|---|
| repo | `/Users/mtr/StudioProjects/OpenApsAIMI` |
| merge base | `7fc8205e9a` (2026-08-06, "Fix scenes expiration") |
| `kmp` tip | `4957c26eb8` (2026-08-20) — 153 commits ahead, 1 author (Milos Kozak) |
| `dev_OAPSAIMI` tip | `06e7bc5021` (2026-08-24) — 2684 commits ahead, 6 authors (2204 by mtr93600) |
| git | 2.50.1 — `merge-tree --write-tree` available |

---

## 1. Conflict surface, measured

### 1a. `git merge-tree --write-tree kmp dev_OAPSAIMI`

Result tree `8da3ed1ebf`, exit 1 (conflicts).

| metric | value |
|---|---|
| conflicted paths reported by git | **139** |
| — `CONFLICT (content)` | 64 |
| — `CONFLICT (file location)` | 62 |
| — `CONFLICT (modify/delete)` | 13 |
| **total conflicted hunks** (`<<<<<<<` markers in the merged tree) | **130** |
| files git had to three-way merge at all (`Auto-merging`) | 145 |

The headline "139 conflicts" is misleadingly friendly. Break it down:

**62 `file location` conflicts are free.** These are all AIMI files added under `src/main/...`
inside a directory `kmp` renamed to `src/commonMain/...` or `src/androidMain/...`. Git puts them at
the old path and tells you where they belong. One scripted `git mv` pass. ~0.5 day.

**13 `modify/delete` are the expensive ones.** `kmp` deleted the file, AIMI edited it. There is no
merge to do — the AIMI change has to be re-implemented against whatever replaced it:

| dev lines changed | path | why kmp deleted it |
|---:|---|---|
| 1677 | `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt` | Overview is Compose now |
| 484 | `core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt` | moved to `commonMain`, `TextRef` titles |
| 31 | `core/ui/src/main/kotlin/app/aaps/core/ui/compose/preference/AdaptiveIntentPreference.kt` | preference API redesigned |
| 25 | `plugins/main/src/main/AndroidManifest.xml` | manifest entries moved to `:app` |
| 15 | `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/di/ApsModule.kt` | DI moved to `:app` |
| 10 | `core/data/src/test/.../SourceSensorExtensionsTest.kt` | test source set moved |
| 8 | `plugins/main/.../di/MainPluginsListModule.kt` | DI moved to `:app` |
| 7 | `plugins/aps/.../di/ApsPluginsListModule.kt` | DI moved to `:app` |
| 7 | `plugins/smoothing/.../di/SmoothingPluginsListModule.kt` | DI moved to `:app` |
| 5 | `core/interfaces/.../insulin/InsulinType.kt` | source set move |
| 4 | `core/interfaces/.../resources/ResourceHelper.kt` | split into `TextResolver` + `ResourceHelper` |
| 3 | `core/interfaces/.../stats/TIR.kt` | source set move |
| 2 | `core/ui/.../compose/FormatUtils.kt` | source set move |

The 1677-line `OverviewFragment.kt` is not a merge problem, it is a rewrite. AIMI's dashboard work
(34 files under `plugins/main/general/dashboard`, 18,690 lines module-wide) is built on a View-based
Overview that no longer exists on `kmp`.

### 1b. Conflicting files grouped by module, by severity (hunks)

| module | hunks | worst files |
|---|---:|---|
| `core/ui` | 33 | `AdaptiveDoublePreference` 9, `AdaptiveIntPreference` 6, `AdaptiveSwitchPreference` 4, `AdaptiveStringPreference` 4, `AdaptiveListPreference` 4 |
| `plugins/main` | 11 | `IobCobCalculatorPlugin` 7, `AutosensDataStoreObject` 4 |
| `core/keys` | 9 | `StringKey` 4, `DoubleKey` 3, `IntentKey` 2, `IntKey` 2 |
| `plugins/aps` | 11 | `ApsIntentKey` 3, `LoopPlugin` 3, `build.gradle.kts` 3, `OpenAPSSMBPlugin` 2, `LoopPluginTest` 2 |
| `app` | 9 | `UiInteractionImpl` 5, `MainApp`, `ComposeMainActivity`, `AndroidManifest.xml`, `DummyService` |
| `implementation` | 7 | `NotificationManagerImpl` 3, `ResourceHelperImpl` 2, `PluginStore` 2 |
| `ui` | 4 | `PreferenceScreenView` 2, `StatusViewModel` 2, `MainDrawer`, `WidgetStateLoader` |
| `core/interfaces` | 4 | `RT.kt` 2, `ImportExportPrefs` 2 |
| `plugins/sync` | 2 | `WearPlugin` 2 |
| `plugins/smoothing` | 4 | 3 plugin classes + `build.gradle.kts` |
| `shared`, `workflow`, `pump/medtrum`, `buildSrc`, `core/graph` | 1–3 each | `DateUtilImpl`, `PrepareGraphDataWorker`, `MedtrumService`, `Versions.kt` |

The conflict pattern is diagnostic: `core/ui` preference composables, `core/keys` key enums,
`core/interfaces` resource/text plumbing. AIMI added ~1000 lines of preference keys and preference
UI on top of exactly the layer Milos rewrote for multiplatform.

### 1c. True integration surface — files changed on BOTH sides since the base

`git diff -M --name-only base kmp` ∩ `git diff -M --name-only base dev_OAPSAIMI`
(kmp keyed by its **pre-rename** path so the sets are comparable):

| | files | lines |
|---|---:|---|
| changed by `kmp` | 2453 | — |
| changed by `dev_OAPSAIMI` | 2298 | 349,793 |
| **intersection** | **216** | — |

Intersection by what `kmp` did to the file: **117 renamed** (source-set move, mostly free),
**86 modified in place**, **13 deleted**.

Of those 216, git flags 77 and **silently auto-merges 139**. 39 of the silent ones are `strings.xml`
files where `kmp` only renamed the file — genuinely safe. **100 are `.kt`/`.kts` files edited by both
sides that git merged without complaint.** Those are the review backlog, and where a safety-critical
loop hides its regressions:

| kmp+dev lines | path |
|---:|---|
| 793 | `ui/.../compose/main/MainScreen.kt` |
| 368 | `plugins/sync/.../garmin/GarminPlugin.kt` |
| 224 | `implementation/.../aps/DetermineBasalResult.kt` |
| 190 | `plugins/sync/.../wearintegration/DataHandlerMobile.kt` |
| 132 | `ui/.../overview/OverviewDataCacheImpl.kt` |
| 120 | `database/persistence/.../PersistenceLayerImpl.kt` |
| 116 | `core/interfaces/.../overview/OverviewData.kt` |
| 101 | `plugins/calibration/.../LinearCalibrationPlugin.kt` |
| 88 | `pump/medtrum/.../MedtrumPlugin.kt` |
| 87 | `pump/omnipod/dash/.../OmnipodDashPumpPlugin.kt` |
| 77 | `implementation/.../sharedPreferences/PreferencesImpl.kt` |
| … | 89 more, by module: `plugins/aps` 27, `core/interfaces` 19, `ui` 16, `plugins/sync` 11, `core/ui` 11, `core/data` 9, `implementation` 8 |

**Bottom line for §1: 216-file integration surface, 130 hunks git will stop on, 100 files git will
merge wrongly-and-quietly, 13 files that must be re-implemented rather than merged.**

---

## 2. The API-break map

Measured against the AIMI package on `dev_OAPSAIMI`:
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI` — **442 files, 102,099 lines**
`plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI` — **243 files, 31,718 lines**
plus **145 files outside the AIMI package, in 16 modules, that reference AIMI symbols** (§2c).

### 2a. What actually changed on `kmp` (verified, not assumed)

| claim in brief | verified state on `kmp` |
|---|---|
| `PluginDescription` uses `TextRef` | ✅ `pluginName/shortName/description` are `TextRef?` (were `Int = -1`) |
| `PluginBase.pluginId` uses `KClass` | ⚠️ **partly wrong** — it is still `String`; the body changed from `javaClass.simpleName` to `this::class.simpleName!!`. Not a call-site break. |
| `HasAndroidInjector` dropped | ⚠️ **partly** — still present in 329 files on `kmp`; dropped module-by-module. Gone from every converted plugin. |
| DI modules moved to `:app` | ✅ `plugins/aps/**/di/` no longer exists; `app/src/main/kotlin/app/aaps/di/ApsPluginsModule.kt` |
| `PumpPluginBase` in `commonMain` | ✅ `core/interfaces/src/commonMain/.../pump/PumpPluginBase.kt` — **no AIMI call sites** |
| `:core:interfaces` files moved source sets | ✅ 250 `commonMain` / 56 `androidMain` / 13 `androidHostTest` / 3 `iosMain` |
| `DecimalFormat` → `NumberFormat` | ⚠️ in progress — 170 files still use `DecimalFormat` on `kmp` |
| `TimeUnit` → `Duration` | ⚠️ in progress |
| `org.json` → `kotlinx` | ⚠️ in progress — 118 files still `import org.json` on `kmp` (down from 318 on dev) |
| `ResourceHelper` gone | ❌ **no** — `interface ResourceHelper : TextResolver` still exists in `androidMain`, still has `gs(@StringRes Int)` |
| `R.string.*` must go | ❌ **no** — `TextRef.AndroidRes(id)` exists *specifically* so a module keeping AAPT resources still works |

The last two rows are the single most important finding in this report and they cut the S2 estimate
roughly in half — see §2b note.

### 2b. Break table

`main` = AIMI main source files affected, `test` = AIMI test files, `occ` = raw occurrences.

| breaking change | AIMI call sites | mechanical? |
|---|---|---|
| `PluginDescription.pluginName/shortName/description`: `Int` → `TextRef` | **1 file, 3 calls** | **y** — wrap in `TextRef.AndroidRes(...)`, or point at generated `ApsStrings` |
| `PluginBase.rh`: `ResourceHelper` → `TextResolver` | 6 main / 2 test | **y** — `ResourceHelper : TextResolver`, mostly a type annotation swap |
| `HasAndroidInjector` dropped from converted plugins | 1 main (`OpenAPSAIMIPlugin`) | **y** |
| `@Inject` constructor injection → hand-wired constructors in `:app` | **82 main files, 130 `@Inject`** | **y, but bulk** — kmp plugins take plain constructors; every provider written by hand in `:app` |
| Dagger `@Module` must move to `:app` | 7 modules in `plugins/aps` (2 AIMI-specific) | **y** |
| Android entry points (`Activity`) must move to `:app` | 13 AIMI Activity classes; 5 use `TranslatedDaggerAppCompatActivity` (**deleted on kmp**) | **n** — needs a replacement base class per activity |
| `R.string.*` → generated `ApsStrings` TextRef | 27 main files, **1099 occurrences** | **y — and optional.** `TextRef.AndroidRes` keeps `R.string` legal in an Android-only module. Only required if AIMI goes `commonMain`. |
| 1584 new AIMI strings added to `plugins/aps` `values/strings.xml` (100 → 1684) | 21 locale files | **y** — file moves to `src/androidMain/res`, `GenerateKeyStringsTask` emits TextRefs automatically |
| `org.json` → `kotlinx.serialization` | 67 main / 14 test, **976 occurrences** | **y, but the largest single bucket** — still legal on kmp today |
| `DecimalFormat` → `NumberFormat` | 4 main / 2 test, 12 occ | **y** — still legal on kmp today |
| `TimeUnit` → `kotlin.time.Duration` | 12 main / 2 test, 52 occ | **y** — still legal on kmp today |
| `java.text.*` | 13 main / 1 test, 15 occ | y |
| `java.util.Calendar/Date/Locale/TimeZone` | 47 main / 2 test, 95 occ | y |
| `System.currentTimeMillis()` → `dateUtil.now()` | 81 main / 19 test, 306 occ | y |
| `java.io.File` (JSONL logging, model store) | 33 main / 25 test, 66 occ | **n** for commonMain — needs an expect/actual or an injected file port |
| `android.*` imports | 72 main / 17 test, 224 occ, 177 distinct FQNs (`Context` in 51 files) | **n** for commonMain |
| `androidx.*` imports (Compose, WorkManager, Health Connect) | 41 main / 5 test, 284 occ | **n** for commonMain |
| `android.os.Handler`/`Looper` | 6 main | n |
| `SharedPreferences` direct use | 8 main, 14 occ | y — route through `Preferences` |
| relocated `core` symbols (import path changed, symbol intact) | ~20 FQNs: `convertedToAbsolute`, `plannedRemainingMinutes`, `target`, `put`, `plus`, `getPassedDurationToTimeInMinutes`, `decimalPlaces`, `IcPluginOpenAPS`, `advancedFilteringSupported`, `LocalPreferences`, `ProvidePreferenceTheme`, `Adaptive*PreferenceItem`, `PreferenceItem` … | **y** — import rewrite |
| genuinely deleted `core` symbols | `TranslatedDaggerAppCompatActivity` (5 files), `EventPreferenceChange` (1), `NSSettingsStatus` (1), `withCompose` (1), `withEntries` (1) | **n** — 9 files need redesign |
| `RxBus` / RxJava | 2 main | y |
| `PumpPluginBase`, Room/`@Entity` | **0** | — |

**Import-resolution check.** The AIMI package issues 1635 `import app.aaps.*` statements covering
574 distinct FQNs, of which **148 are outside the AIMI package**. Resolving those 148 against the
`kmp` tree: **104 resolve unchanged, 44 do not** — and of the 44, 16 are AIMI's own additions that
travel with the port, 4 are generated (`R`, `databinding`), ~19 are pure relocations, and **5 are
genuinely deleted APIs**. So the *external API break* on the AIMI package is ~24 real FQNs.

That is a small number. **The AIMI algorithm code is not what makes this hard.** What makes it hard
is §2c and §3.

### 2c. AIMI is not self-contained

**145 files outside `openAPSAIMI/` reference AIMI symbols**, spread over 16 modules:

| module | files | note |
|---|---:|---|
| `plugins/aps` (outside AIMI dir) | 36 | DI, `LoopPlugin` (+433 lines), `ApsIntentKey`, layouts |
| `plugins/main` | 34 | the whole `general/dashboard` package — **this is the module kmp gutted** |
| `core/keys` | 12 | ~1000 lines of AIMI preference keys spread across `BooleanKey`/`DoubleKey`/`IntKey`/`StringKey`/`IntentKey` |
| `core/interfaces` | 11 | `AimiAdaptationStatus`, `GlucoseStatusAIMI`, `OapsProfileAimi`, `RT.kt` (+99), `APSResult` (+7), `HardLimits`, `NotificationId`, 2 Rx events |
| `app` | 9 | `MainApp`, `ComposeMainActivity`, `ComparatorActivity`, `DashboardOverviewHost` |
| `ui` | 9 | `MainScreen`, maintenance sheet, graph decorations |
| `implementation` | 8 | `DetermineBasalResult`, `ImportExportPrefsImpl`, cloud backup |
| `plugins/sync` | 7 | `RemoteControlPlugin`, `WearPlugin`, NS ingest |
| `core/ui`, `database`, `plugins/source`, `plugins/dexcom_oneplus`, `core/data`, `buildSrc`, `plugins/configuration` | 15 | |

Every one of these is a hook that S2 must either replicate behind a new interface or drop.
The good news: AIMI's `core/interfaces` additions are already near-KMP-clean — `GlucoseStatusAIMI`
and `OapsProfileAimi` already use `kotlinx.serialization`; only one `android.annotation.SuppressLint`
appears across the three new files.

---

## 3. Non-AIMI divergence

`git diff --stat base dev_OAPSAIMI`: **2298 files, 349,793 lines**. Of those, **152,234 lines are
AIMI-named** (44%). The other 56% is a second fork's worth of work.

Modules `kmp` has already made multiplatform (has a `commonMain`): `core/data`, `core/graph`,
`core/interfaces`, `core/keys`, `core/nssdk`, `core/objects`, `core/ui`, `core/utils`,
`plugins/aps`, `plugins/calibration`, `plugins/main`, `plugins/sensitivity`, `plugins/smoothing`,
`pump/combov2/comboctl`, `pump/virtual`.

| module | files | lines | AIMI lines | files also touched by kmp | kmp made it MP? |
|---|---:|---:|---:|---:|:--:|
| `plugins/aps` | 737 | 158,668 | 133,965 | 34 | **YES** |
| `docs` | 245 | 69,731 | 16,306 | 0 | – |
| `plugins/libre3` | 146 | 20,586 | 0 | 0 | new module |
| `plugins/main` | 162 | 18,690 | 1,219 | 9 | **YES** |
| `plugins/source` | 81 | 11,202 | 0 | 2 | no |
| `plugins/eversense` | 161 | 8,866 | 0 | 0 | new module |
| `plugins/dexcom_oneplus` | 73 | 8,460 | 0 | 0 | new module |
| `core/graphview` | 26 | 6,114 | 0 | 0 | new module |
| `pump/apex` | 59 | 5,968 | 0 | 0 | new module |
| `core/keys` | 56 | 4,625 | 0 | 9 | **YES** |
| `ui` | 53 | 4,615 | 0 | 21 | no (still `src/main`) |
| `plugins/sync` | 49 | 4,076 | 0 | 12 | no |
| `core/ui` | 63 | 3,419 | 0 | 28 | **YES** |
| `core/data` | 63 | 2,937 | 0 | 10 | **YES** |
| `plugins/libkeks` | 28 | 2,238 | 0 | 0 | new module |
| `app` | 24 | 2,086 | 0 | 9 | no |
| `core/graph` | 25 | 1,784 | 0 | 1 | **YES** |
| `audit` | 10 | 1,757 | 0 | 0 | new dir |
| `core/interfaces` | 54 | 1,518 | 240 | 25 | **YES** |
| `plugins/smoothing` | 10 | 1,324 | 0 | 8 | **YES** |
| `implementation` | 29 | 1,252 | 0 | 15 | no |
| `pump/medtrum` | 7 | 764 | 0 | 2 | no |
| `pump/combov2` | 4 | 619 | 0 | 0 | partly |
| `database` | 27 | 473 | 0 | 2 | no |
| `plugins/calibration` | 4 | 385 | 0 | 4 | **YES** |
| `core/objects` | 14 | 350 | 0 | 3 | **YES** |
| `plugins/constraints` | 10 | 164 | 0 | 6 | no |
| `pump/equil` | 8 | 164 | 0 | 4 | no |
| others (`wear`, `workflow`, `plugins/automation`, `pump/omnipod`, `shared`, `core/utils`, `buildSrc`, gradle, loose `.md`) | ~90 | ~2,800 | 465 | ~12 | mixed |

### 3a. Cheap: five brand-new modules, zero base overlap

`plugins/libre3` (146 files), `plugins/eversense` (161), `plugins/dexcom_oneplus` (73),
`pump/apex` (59), `plugins/libkeks` (28), `core/graphview` (26). None exist on `base` **or** on
`kmp`. They cannot conflict. Cost is only: adapt each `build.gradle.kts` to `kmp`'s AGP-9 /
`settings.gradle` conventions and re-wire their DI to `:app`. **~493 files, essentially free to carry
under either strategy.** This is 46,000+ lines of the owner's work that is *not* at risk.

### 3b. Expensive: dev changes inside modules `kmp` has already multiplatformed

These are the individually costly ones. Listed with what `kmp` did to them:

1. **`plugins/aps`** — 34 overlapping files, 11 conflict hunks. `kmp` turned it into
   `kotlin("multiplatform")`, moved `openAPSSMB`/`openAPSAMA`/`openAPSAutoISF`/`DetermineBasalSMB`
   into `commonMain`, moved `res` to `src/androidMain/res`, added `GenerateKeyStringsTask`
   (`ApsStrings`), deleted `plugins/aps/**/di/`. AIMI's 688 files land here.
   *Note the sibling algorithms are already in `commonMain` — an `androidMain`-only AIMI is legal but
   permanently second-class in this module.*
2. **`plugins/main`** — 9 overlapping files, 11 hunks, and the `OverviewFragment.kt` modify/delete.
   AIMI's dashboard (34 files) has no host on `kmp`. **Highest-risk single item in the whole report.**
3. **`core/keys`** — 9 overlapping, 9 hunks. `BooleanKey` is a modify/delete (484 dev lines).
   All key enums moved to `commonMain` and their titles became `TextRef`. AIMI's ~1000 lines of
   preference keys must be re-expressed.
4. **`core/ui`** — 28 overlapping files, 33 hunks — the *most* conflicted module. AIMI edited the
   Adaptive*Preference composables; `kmp` moved all of them to `commonMain` and rewrote their
   signatures.
5. **`core/interfaces`** — 25 overlapping, 4 hunks, but 62 `file location` conflicts concentrate
   here. Mostly mechanical moves; `RT.kt` (dev +99 / kmp -99) is a genuine two-sided rewrite.
6. **`core/data`** — 10 overlapping, incl. a deleted test source set.
7. **`core/graph`** — 1 overlapping + `build.gradle.kts` conflict; dev also added a whole parallel
   `core/graphview` module (6114 lines) that duplicates territory `kmp` is multiplatforming.
8. **`plugins/smoothing`** — 8 overlapping, 4 hunks; `kmp` moved 3 plugin classes to `commonMain`,
   dev's `SmoothingPluginsListModule` is a modify/delete.
9. **`plugins/calibration`** — 4 overlapping, all `R###` renames (dev's Libre3 calibration fix from
   2026-08-24 lands on files `kmp` just moved).
10. **`core/objects`** (3), **`core/utils`** (1) — small.

Also expensive despite not being multiplatform yet, because of raw overlap:
**`ui`** (21 overlapping files, incl. `MainScreen.kt` — 790 dev lines vs 3 kmp lines, silently
auto-merged) and **`implementation`** (15 overlapping, 7 hunks).

---

## 4. Ongoing cost — both branches are alive

| | `kmp` | `dev_OAPSAIMI` |
|---|---:|---:|
| commits since base | 153 | 2684 |
| date span since base | 2026-08-05 → 08-20 (15 d) | 2023-10-05 → 2026-08-24 |
| **commits/week (branch-specific)** | **≈ 71** | **≈ 97** (2495 in last 180 d) |
| commits/week last 30 d | (branch is 15 d old, so all of it) | ≈ 58 (247 in 30 d) |
| authors | 1 | 6 |
| last-180d commits touching kmp-multiplatform modules | — | **909** (≈ 35/week) |
| last-180d commits touching the AIMI dir | — | 443 (≈ 17/week) |
| last-180d commits touching the 6 new standalone modules | — | 64 (≈ 2.5/week) |
| kmp's own hot modules | `core/ui` 635 files, `core/interfaces` 349, `plugins/aps` 158, `ui` 122, `core/nssdk` 121 | — |

The two branches are hot in the *same* places: `kmp`'s top-5 churn modules are `core/ui`,
`core/interfaces`, `plugins/aps`, `ui`, `core/keys` — and dev puts ~35 commits/week into that same
set. This is the number that decides the strategy.

**Monthly re-sync under S1 (one tree).** Every month you inherit ~300 new `kmp` commits × ~250 new
dev commits, landing on a shared 216-file surface that includes the 100 silent-auto-merge files.
Milos is still actively moving modules across source sets, so *new* `file location` and
`modify/delete` conflicts appear each cycle in modules that were fine last month. Realistic:
**3–5 person-days per month, indefinitely, and not decreasing** — plus the unquantifiable cost that
every silent auto-merge in `DetermineBasalResult`, `PersistenceLayerImpl` or `SafetyPlugin` is a
potential dosing regression in a closed-loop system.

**Monthly re-sync under S2 (separate module).** The AIMI module's 442 files never conflict — nothing
upstream touches them. The contested surface shrinks to the module's *consumed API*: ~148 external
FQNs plus the `:app` DI wiring. Milos's renames break compilation loudly rather than merging quietly.
Realistic: **0.5–1.5 person-days per month, decreasing** as `kmp` stabilises. Cost spikes only when
`kmp` converts a module AIMI depends on — a handful of times, and each spike is a compile error, not
a silent merge.

---

## 5. Recommendation

**Take S2 — extract AIMI into a standalone module on the `kmp` foundation. Do not rebase the fork.**

Reasoning, in order of weight:

1. **S1's cost is not the conflicts, it is the 100 silently auto-merged Kotlin files.** 130 hunks is
   a week's work. 100 files that git merged *without telling you* — `DetermineBasalResult`,
   `PersistenceLayerImpl`, `SafetyPlugin`, `MainScreen` (790 dev lines vs 3 kmp lines), `MedtrumPlugin`,
   `OmnipodDashPumpPlugin` — in a system that doses insulin, is a review burden you cannot shortcut
   and cannot fully test. And you pay it again every month.
2. **The `kmp` foundation already accommodates an Android-only module.** 35 modules on `kmp` are
   still plain `src/main` Android libraries. `ResourceHelper : TextResolver` still exists,
   `TextRef.AndroidRes(id)` exists explicitly so a module keeping AAPT resources still compiles, and
   `TextRef`'s own KDoc says modules migrate "one at a time rather than all at once". So the
   integration step does **not** require KMP-ifying AIMI — the 224 `android.*` + 284 `androidx.*`
   imports and 1099 `R.string.*` uses can stay exactly as they are. That is the difference between a
   6-week job and a 6-month job.
3. **S1 forces you to reconcile 56% of dev's diff that has nothing to do with AIMI** — Libre3,
   Eversense, ONE+, Apex, libkeks, graphview, the Compose dashboard, cloud backup — against a
   foundation that is being rewritten weekly by someone else. S2 lets you move those independently:
   the five new modules (~493 files, 46k lines) port for free under either strategy, and can go first
   as a low-risk warm-up.
4. **S1's real blocker is `OverviewFragment.kt`.** 1677 dev lines, deleted on `kmp`. AIMI's dashboard
   is 34 files built on it. A merge cannot produce a working result here; only a rewrite can. That
   rewrite is required under S2 too, but under S2 it is a *scheduled* item, not something blocking a
   2684-commit merge from compiling.

### Person-week estimate — integration step only

Excludes KMP-ification of AIMI (another agent's number). Assumes one experienced engineer who knows
both trees.

**S2 (recommended)**

| work item | person-weeks |
|---|---:|
| Create `:plugins:aimi` (Android-only lib on `kmp`), move 442 main + 243 test files, package rename, `build.gradle.kts` | 0.4 |
| Re-apply AIMI's `core/*` additions onto `kmp` shapes: ~1000 lines of keys → `TextRef` titles + `commonMain`; `RT.kt` +99; `APSResult`; `HardLimits`; `NotificationId`; 2 Rx events; 3 AIMI interface types | 1.5 |
| Rewire DI: 130 `@Inject` sites in 82 files → plain constructors + hand-written providers in `:app`; 7 `@Module` files relocated | 1.2 |
| Move 13 Activities + 4 Workers to `:app`; replace `TranslatedDaggerAppCompatActivity` (5 files) | 0.8 |
| `PluginDescription` → `TextRef`; strings.xml (1584 entries) into `src/androidMain/res`; ~20 relocated imports; 5 deleted APIs | 0.5 |
| Re-hook the ~60 non-dashboard wiring points across `plugins/sync`, `implementation`, `ui`, `app`, `database` behind interfaces | 1.5 |
| Port the 6 standalone modules (libre3, eversense, dexcom_oneplus, apex, libkeks, graphview) to `kmp` conventions | 0.8 |
| Get 243 AIMI tests green on `kmp`'s `androidHostTest` layout | 0.8 |
| Buffer / first working end-to-end loop run | 1.0 |
| **total** | **≈ 8.5 person-weeks** |

Range: **7–10 person-weeks.** The dashboard rewrite (34 `plugins/main` files onto `kmp`'s Compose
Overview) is **deliberately excluded** — estimate that separately at 3–5 further weeks; AIMI can ship
headless first.

**S1 (not recommended)**

| work item | person-weeks |
|---|---:|
| 130 conflict hunks across 64 files | 0.8 |
| 62 `file location` moves | 0.1 |
| 13 modify/delete re-implementations (excl. `OverviewFragment`) | 1.0 |
| Review 100 silently auto-merged `.kt` files | 1.2 |
| Make the merged tree compile: 2298 dev files against kmp APIs, six half-migrated API families | 3.5 |
| Reconcile dev's non-AIMI work in 10 already-multiplatform modules | 2.0 |
| `OverviewFragment` / dashboard rewrite — **mandatory here, it blocks the build** | 4.0 |
| Regression-test a merged closed loop | 2.0 |
| **total to a first building tree** | **≈ 14.5 person-weeks** |

Range: **12–18 person-weeks**, plus **3–5 person-days every month forever**, plus dosing-regression
risk that cannot be bounded by review.

**S1 ≈ 14.5 pw + 4 d/month. S2 ≈ 8.5 pw + 1 d/month.** S2 wins on the one-time cost and wins
decisively on the treadmill.

### Suggested sequencing under S2

1. Port the 6 zero-conflict standalone modules to `kmp` first (0.8 pw) — proves the `kmp` build
   conventions with no merge risk and banks 46k lines of the owner's work.
2. Land AIMI's `core/*` additions onto `kmp` as a normal upstream-shaped PR (1.5 pw) — this is the
   only part that genuinely has to merge into shared files.
3. Create `:plugins:aimi` Android-only and move the package (0.4 pw).
4. DI + Activities + strings (2.5 pw) — the module now builds.
5. Wiring hooks + tests (2.3 pw) — the loop now runs.
6. Dashboard rewrite, scheduled separately.

Explicitly **do not** try to make `:plugins:aimi` `commonMain` during integration. 51 files use
`android.content.Context`, 6 use `Handler`/`Looper`, 33 use `java.io.File`, 4 use `CoroutineWorker`,
6 use Health Connect. That is a separate project and `kmp` is designed to let you defer it.

---

## Verification

Main working tree checked at the end of the analysis: branch `kmp-aimi-migration-study`
(= `4957c26eb8`, same tip as `kmp`), `git status --short` empty. No worktree was created, no branch
switched, no tracked file modified. All analysis used read-only plumbing
(`merge-tree --write-tree`, `diff`, `log`, `ls-tree`, `show`, `grep <ref>`); the merged tree
`8da3ed1ebf` exists only as a dangling object and was never checked out.
