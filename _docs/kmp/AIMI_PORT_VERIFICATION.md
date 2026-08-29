# AIMI port - verification against the `kmp-module-flip` checklist

Run 2026-08-29 on `kmp-aimi-migration-study` at `c174fa6f69` (just after the `kmp` merge).
Checklist source: `.claude/skills/kmp-module-flip/SKILL.md`, which arrived with that merge.
Scope: `:plugins:aps` (which hosts the ported AIMI code) and the five `:plugins:aimi-*` modules.

Every line below is a command result, not a reading of the documentation.

---

## 1. Result at a glance

| # | checklist item | verdict |
|---|---|---|
| 1 | module type: `kotlin("multiplatform")` + `android.kmp.library`, never `com.android.library` | **PASS** |
| 2 | no convention plugin applied | **PASS** |
| 3 | iOS targets declared | **PASS** |
| 4 | compiles for `iosArm64`, klib really produced | **PASS** |
| 5 | no `R.string` / `@StringRes` in shared code; `XxxStrings` generated into commonMain | **PASS** |
| 6 | no `android.` / `javax.` / `dagger.` / `org.json.` / `java.io.` imports in commonMain | **PASS** |
| 7 | no hard-coded `src/test/` or `src/main/` in moved tests | **PASS** |
| 8 | source-set layout `androidMain` / `androidHostTest` | **PASS**, with a leftover (§3.3) |
| 9 | Metro construction trap cleared | **N/A yet** (§3.4) |
| 10 | plugin registers itself into the plugin map | **FAIL** (§3.1) |
| 11 | listed in `migratedModules` in `ios/shell/build.gradle.kts` | **FAIL** (§3.2) |
| 12 | module builds as part of the app | **FAIL** (§3.5, pre-existing) |

Seven of the mechanical criteria pass cleanly. The three failures are all about **integration**, not
about the quality of the ported code - which is the same conclusion the earlier review reached by a
different route.

---

## 2. What passes, with the evidence

**Module type and plugins.** `:plugins:aps` applies `kmp-test-defaults`, `kotlin("multiplatform")`,
`libs.plugins.android.kmp.library`, `compose.compiler`, `compose.multiplatform` and `metro`. The
three `com.android.library` strings in that file are inside comments explaining why it is *not* used
("AGP 9 refuses that plugin together with the multiplatform plugin"). Likewise the
`test-module-dependencies` / `compose-test-module-dependencies` mentions - both are comments above
hand-written dependency lists, exactly as the skill prescribes. The five `aimi-*` modules apply
`kotlin("multiplatform")` and nothing else.

**iOS compile is real.** `:plugins:aps:compileKotlinIosArm64` and
`:plugins:aimi-engine:compileKotlinIosArm64` → `EXIT=0`, `BUILD SUCCESSFUL`. No task reported
`SKIPPED` or `NO-SOURCE` (the only `NO-SOURCE` lines are `jvmTestProcessResources`, which processes
resources, not tests). The klib manifest exists at
`plugins/aps/build/classes/kotlin/iosArm64/main/klib/aps/default/manifest`.

**Strings.** `grep -rn "R\.string\|@StringRes"` over the ported `openAPSAIMI` commonMain returns
**0**. `GenerateKeyStringsTask` is registered as `generateApsStrings`, emits `ApsStrings`, and its
`commonOutputDir` is wired into the commonMain `kotlin.srcDir`. This is the skill's prescribed shape.

**Imports.** Using the skill's own anchored pattern `^import android\.` (it warns that the unanchored
form also matches `androidx` and hides every Compose file): **0** hits. Also **0** for
`^import javax\.`, `^import dagger\.`, `^import org\.json\.` and `^import java\.io\.`.

**Test paths.** **0** hard-coded `src/test/` or `src/main/` strings in the moved tests.

---

## 3. The three failures, and one caveat

### 3.1 AIMI does not register itself as a plugin - FAIL

The skill's target state says a plugin registers itself with
`@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())`.

In the ported AIMI tree: **0** files mention `PluginBase`, and **0** carry `@ContributesIntoMap`. For
comparison, `LoopPlugin.kt:114` and `AutotunePlugin.kt:77` both carry it, and the three oref plugins
are registered by `@IntKey(210/220/230)` in
`plugins/aps/src/androidMain/.../di/ApsPluginRegistrations.kt`, which has no AIMI entry.

`OpenAPSAIMIPlugin.kt` is still parked in `_docs/kmp/staging/`, outside every source set. So this is
not a defect in the port - it is the port being unfinished at exactly the point that would make it
visible to the app.

One nuance worth recording: the skill asks for registration **from commonMain**, and none of the
existing plugins do that either - `LoopPlugin` and `AutotunePlugin` register from `androidMain`. So
when AIMI is wired, matching the skill's ideal will be a decision, not a copy.

### 3.2 The iOS shell guard fails - FAIL

The merge brought `ios/shell`, a real iOS shell module with a `migratedModules` list of 23 entries
and `ShellInfo.LINKED_MODULES = 23`. The skill warns: *"Once a module builds for iOS, the ios-branch
guard fails until it is listed. Expect this on every flip."*

It does:

```
$ ./gradlew :ios:shell:checkMigratedModules            EXIT=1
> ios/shell no longer covers every module that builds for iOS.
    add to migratedModules:    :appshell, :plugins:aimi-contracts, :plugins:aimi-engine,
                               :plugins:aimi-io, :plugins:aimi-learning, :plugins:aimi-testkit
  Keep ShellInfo.LINKED_MODULES in step as well.
```

Six modules missing. Note that `:appshell` is Milos's own new module, so one of the six is not ours.
Fixing this is a two-line edit plus bumping the constant to 29.

### 3.3 A leftover source directory - minor

`plugins/aps/src/main` still exists alongside `commonMain` and `androidMain`. It holds **0** `.kt`
files - only a tree of macOS `.DS_Store` files. Harmless, but the skill's layout is
`main → androidMain`, and a stray `src/main` in a multiplatform module invites confusion. Delete it.

### 3.4 The Metro construction trap does not apply yet - watch item

The skill flags this as "the worst one, and it has happened four times": once a class gets
`@ContributesBinding`, Metro constructs it for real in tests, and I/O in a property initializer or
`init` block turns into an infinite loop, an OOM or an `ExceptionInInitializerError`.

Right now the ported AIMI code has **0** `@ContributesBinding`, so the trap is inert. But **6 files
in the ported tree have `init { }` blocks**, and AIMI is a subsystem that reads JSONL and CSV state
at startup. **Read those six before adding the first binding**, not after.

### 3.5 The module does not build as part of the app - FAIL, pre-existing

`:implementation:compileAndroidMain` → `EXIT=1`, `BUILD FAILED`, **12 Kotlin compiler errors**, all
tracing to the same cause reported before the merge:

```
Unresolved reference 'runBlocking'
  implementation/src/androidMain/.../stats/TirCalculatorImpl.kt line 119
  implementation/src/androidMain/.../stats/TirCalculatorImpl.kt line 140
```

The file has three `runBlocking` uses and no `import kotlinx.coroutines.runBlocking`. This predates
the merge and the merge did not touch it. **One line fixes it.**

---

## 4. What the AIMI port actually amounts to

Measured, not claimed:

| | |
|---|---|
| AIMI in `plugins/aps/src/commonMain` | **286 files / 38 486 LOC** |
| AIMI parked in `_docs/kmp/staging/`, outside any source set | **324 files** |
| the five `:plugins:aimi-*` modules | **16 files / 645 LOC** |
| source of truth on `dev_OAPSAIMI` | 441 files / 102 354 LOC (+243 test files) |
| tests in the `aimi-*` modules | **15** (4 + 7 + 1 + 1 + 2) |
| `expect` / `actual` pairs in the whole ported body | **0** |

Three of those five test result files are stale, dated 2026-08-26; only `aimi-engine` and
`aimi-contracts` re-ran in this verification.

**The zero `expect`/`actual` figure is the one that characterises the port.** The checklist's import
rules pass perfectly *because* the platform-touching half was never converted - it was moved into
`_docs/`. The skill's own step 4, "platform ports: replace `Context` and other Android types with
interfaces", is the step that has not been done. What has been done is steps 1, 3, 5 and 6 for the
subset of files that needed no ports at all.

That is real work and it is correctly done. It is also the cheaper half.

---

## 5. What to do next, in order

1. **Add `import kotlinx.coroutines.runBlocking` to `TirCalculatorImpl.kt`.** One line; it is what
   stands between here and a build that can be run and compared against.
2. **Register the six modules** in `ios/shell/build.gradle.kts` and set
   `ShellInfo.LINKED_MODULES = 29`. The guard already tells you exactly what to write.
3. **Delete `plugins/aps/src/main`** (`.DS_Store` only).
4. **Read the six `init { }` blocks** in the ported tree before the first `@ContributesBinding`.
5. **Then the real remaining work**: the platform ports. Every `expect`/`actual` that AIMI needs -
   file storage, the training scheduler, Health Connect, the step source - is still ahead, and the
   `aimi-domain-import-check` ban makes that the only way those files can ever land.
