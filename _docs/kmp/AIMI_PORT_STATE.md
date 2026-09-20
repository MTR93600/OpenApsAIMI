# AIMI port - state of play, and where to start next

> **2026-09-06 — superseded as the live snapshot.** Re-verified status and the remaining
> lots are in [`docs/kmp-migration/STATUS.md`](../../docs/kmp-migration/STATUS.md) and
> [`docs/kmp-migration/DELTA-remaining.md`](../../docs/kmp-migration/DELTA-remaining.md).
> This file is still the best diary of lots 0–6i (how the tick and plugin landed). Do not
> use its file counts, “2 files from commonMain”, or “330 tests” as today’s truth.

Updated 2026-09-02, on `kmp-aimi-migration-study` at `1f6ca62fe8` (the second `kmp` merge).
**Was** “read this first” until 2026-09-06. Keep it for the lot history only.

Tree is clean. `:app:assembleFullDebug` EXIT=0. `:plugins:aps:compileKotlinIosArm64` EXIT=0.
`:plugins:aps:testAndroidHostTest` **330 tests, 0 failures**. `:ios:shell:checkMigratedModules` EXIT=0.

---

## 1. The two-line summary

`DetermineBasalAIMI2` is **2 files away** from compiling in `commonMain`. Upstream has meanwhile
shipped a real, working **iOS follower app** - which changes the strategic picture more than it
changes our remaining work.

---

## 2. What is done

| lot | commit | what |
|---|---|---|
| 0 | `77099513a5` | made the branch build again after the first merge (4 defects) |
| 1 | `eaa3453fc3` | 3 files + `AdvisorModels` to commonMain |
| 2 | `b7354d4a85` | **the `AimiStorage` seam** + 6 files |
| 3 | `0f23dc3a5f` | **restored 389 LOC** an earlier port had silently dropped |
| 4A | `7852c8a923` | `aimiNeuralNetwork.kt` to commonMain |
| 4B | `fb7bcc9a57` | `BasalNeuralLearner` to commonMain |
| — | `1f6ca62fe8` | merged 246 upstream commits |

AIMI in `commonMain`: **333 files**. Tests went from **0 runnable** to **330 green**.

Verification standard used on every lot, and worth keeping: the numeric-literal multiset of each
moved file is diffed against **two** baselines - the `dev_OAPSAIMI` reference for logic, and the
immediately preceding state for the effect of the lot. Every lot so far: zero delta. That is the only
check that has actually caught things.

---

## 3. The last two blockers, and the pattern to use

`DetermineBasalAIMI2` imports 212 `plugins.aps` symbols. 195 resolve in `commonMain`. **Two files**
remain in `androidMain`:

- `openAPSAIMI/utils/AimiStorageHelper.kt`
- `openAPSAIMI/physio/AimiHormonitorStudyExporterMTR.kt` (`HormonitorDecisionEventMTR` is declared
  inside it at line 47 - not a third blocker)

**Do not invent a seam for these.** Upstream added exactly the pattern they need, in this same
module, in commit `f06619c50d`:

```
plugins/aps/src/commonMain/.../loop/LoopNotifier.kt      <- the interface
plugins/aps/src/androidMain/.../loop/AndroidLoopNotifier.kt
plugins/aps/src/iosMain/.../loop/IosLoopNotifier.kt
plugins/aps/src/iosTest/.../loop/IosLoopNotifierTest.kt  <- and it is tested
desktop/shell/.../platform/DesktopLoopNotifier.kt        <- four platforms now
```

Copy that shape. `AimiStorageHelper` is the Android implementation behind the `AimiStorage` seam we
already built in lot 2, so the work is to **rewire its consumers to `AimiStorage`**, not to move it.
`AimiStorageHelper` should stay in `androidMain` forever.

`AimiHormonitorStudyExporterMTR` needs six seams: `MessageDigest`, `SimpleDateFormat`, `Settings`,
`SystemClock`, `TimeZone`, `AtomicLong`. It deserves its own lot, and it is optional surface - a
study exporter, not a dosing path. **Consider deferring it and porting `DetermineBasalAIMI2` with
that one import stubbed**, rather than paying six seams first.

---

## 4. Three hazards, ranked

**H1 - `plugins/aps` lost its Metro Dagger interop.** Green today, because nothing in the module
carries a javax annotation any more. But the parked `OpenAPSAIMIPlugin.kt` has
`javax.inject.Provider` at line 104 and an `@Inject constructor` at line 151. The moment it lands,
Metro skips those declarations and `:app` fails with `UnprocessedUpstreamDeclaration`.
**Convert `OpenAPSAIMIPlugin` to `dev.zacsweers.metro` before porting it, not after.**

**H2 - our `plugins/source` interop block is the last one in the repo.** Upstream has removed
`includeDagger()` from every module; ours is fork-only, and it is holding up **7** files (the Dexcom
ONE+ and Libre 3 activities). Its own comment says 14, which was wrong - correct it when touched. It
sits in a file upstream edits often, so it is a standing conflict, and it dies outright if upstream
drops the interop capability. Converting those 7 files to Metro removes the dependency.

**H3 - the fork's CGM floor keeps rising under it.** `AbstractBgSourcePlugin` and
`AbstractBgSourceWithSensorInsertLogPlugin` moved to `commonMain`, and `DexcomOnePlusPlugin` /
`Libre3NativePlugin` sit on them. Meanwhile `:plugins:dexcom_oneplus` (76 files),
`:plugins:libre3` (147) and `:plugins:libkeks` (29) are still plain `android.library` applying
`android-module-dependencies` and `test-module-dependencies` - convention plugins that the
`kmp-module-flip` skill states **cannot** be used by a multiplatform module. These three will have to
flip eventually, and nobody has started.

**After every merge that touches DI, purge the generated output before believing the build.** This
has now bitten twice, identically. Upstream keeps deleting Dagger and Hilt code, but
`build/generated/ksp/**` still holds Java that imports `dagger.hilt.InstallIn` and `dagger.android`,
and it fails `compileFullDebugJavaWithJavac` in `:app` and in several `:pump:*` modules. The Kotlin
compile and the tests pass, so it looks like an unrelated breakage. The fix is one line:

```
find . -type d -name ksp -path "*/build/generated/*" -exec rm -rf {} +
```

A related trap, also hit twice: `./gradlew … > log 2>&1; echo "EXIT=$?"` in a **backgrounded** command
reports the exit code of the `echo`, not Gradle's, and the harness notification then says "exit code
0" over a failed build. Append the real code into the log (`echo "GRADLE_EXIT=$?" >> log`) and read it
from there.

Two smaller ones: `plugins/aps/androidMain/AndroidManifest.xml` was **deleted** upstream, so
`StepService.kt` needs `:app` now. And the iOS/desktop guards fail only the iOS/desktop build, so
**drift is invisible from an Android-only local build** - run `:ios:shell:checkMigratedModules`
before believing a green tree.

---

## 5. What upstream shipped, and why it matters strategically

246 commits, 2026-08-28 to 09-01: KMP-ification 64, **iOS client 36**, **desktop/JVM client 29**,
Dagger/Hilt → Metro 24, maintenance 19, tests 13, docs 10. 193 files newly in `commonMain`.
`:plugins:sync` (216 files) and `:shared:tests` flipped.

**The iOS app is real.** `ios/app/AAPSClient.xcodeproj` has 8 native targets and two products;
`ClientApp.swift` is a 50-line `@main` that hands off to `AapsAppHostKt.aapsAppViewController`;
`ios/shell` holds 28 `iosMain` files and 12 `iosTest`, links a static `AapsShared` framework, and
hosts the **real** `AapsAppRoot` from `appshell/commonMain` - not stubs. There is also a runnable
desktop client.

**But it is a follower, deliberately.** `ios/shell/.../config/IosClientConfig.kt`:

```
override val APS: Boolean = false
override val PUMPCONTROL: Boolean = false
override val PUMPDRIVERS: Boolean = false
override val AAPSCLIENT: Boolean = true
```

with the KDoc *"does not run the loop, which is exactly what `AAPSCLIENT` means… This is a real
implementation, not a stand-in."* The "two iOS clients" are the `aapsclient` / `aapsclient2`
flavours, **not** the `full` flavour AIMI runs on.

So: **scenario SC-A of the original study has essentially been delivered by upstream, and SC-C
(a master on iPhone) is untouched.** Nothing upstream has done makes the loop, the pump drivers or
the background execution problem any closer. The recommendation to ship the *engine* rather than
rebuild the *app* stands, and is now better supported: the shared spine below the UI exists, is
tested on Native, and someone else maintains it.

---

## 6. Corrections to the older documents in this folder

**There is a second, larger body of planning in this folder that post-dates the study:**
`AIMI_KMP_MIGRATION_BLUEPRINT.md` with annexes 5-9 and `AIMI_KMP_IMPLEMENTATION_BACKLOG.md`. Where it
disagrees with the study, it is generally right, because it read the code rather than the counts. The
clearest case is the ML runtime: annex 5 records that `modelUAM.tflite` is 4,504 bytes with an
`[1,18]` Float32 input and one live inference in `DetermineBasalAIMI2`, and that `AimiNeuralNetwork`
has a **different architecture**. So the study's idea of re-expressing the model as
`AimiNeuralNetwork` JSON was not a like-for-like swap - it would have changed behaviour on an SMB
estimation path. **Keep the model; run it through a per-platform adapter.** That raises the cost
rather than lowering it, and it means TFLite does not simply disappear from the iOS story.

`AIMI_KMP_MIGRATION_STUDY.md` - still the right strategic frame (SC-A/B/C/D, the tier analysis, the
distribution and Critical Alerts findings). **Three further claims in it are now false:**

1. *"There is no iOS app at all: no `iosApp/`, no `.xcodeproj`, no XCFramework."* Wrong since
   2026-08-29. See §5.
2. *"A module's conversion cost is roughly its Dagger count."* Retired. Upstream replaced Dagger with
   **Metro**, which is KMP-native; Dagger is down from 1,053 files to a remnant. The §7 line item for
   Dagger de-wiring and the SC-C repo-wide DI swap (12-20 pw) should both be re-costed downward.
3. *"Room is untouched."* `:database:impl` and `:database:persistence` are both in `migratedModules`
   now.

`CURSOR_SESSION_REVIEW.md` - the process findings hold (the four-copy problem, the honesty of the
ledger, the "clean imports because the hard half was parked" reading). Its **zero `expect`/`actual`**
observation is no longer true of the tree as a whole - upstream now has real ones, and
`LoopNotifier` shows the shape.

`AIMI_PORT_VERIFICATION.md` - the checklist results were true at `c174fa6f69`. Re-run the
`kmp-module-flip` checks after any lot; the two that mattered (the plugin is not registered, the port
is not wired into the app) are **still true**.

---

## 6b. What a fixpoint attempt on DB2 proved, 2026-09-03

I tried the spec's 5G - move `DetermineBasalAIMI2` from staging into `androidMain` so that every
later edit faces a compiler instead of accumulating unverified in a parked file. The attempt was
reverted, and the tree is back at `cbd77176e3`, green. It was worth doing: it corrects the plan.

**The fixpoint converges, and it is the right technique.** Eight rounds, each one the compiler naming
exactly what was missing, took DB2 and 23 supporting files out of the dump. No guessing, no regex
closure. Milos's method works in this direction too.

**But the closure is entangled, not layered.** Reverting one file broke the next, and the next. The 24
files are mutually dependent, so this is one indivisible move, not a sequence of small ones.

**The real blocker is not the file moves. It is JSON drift.** Once DB2 compiled, 30 errors remained
and all but two were the same thing: DB2 holds **116 `org.json` usages**, while the files that lots
2-4 ported now return kotlinx `JsonObject`. Changing DB2's four trace fields to `JsonObject?` did not
help - it moved the mismatch to the sites where DB2 builds those traces itself with `org.json`. The
two families are:

| family | sites | note |
|---|---|---|
| `org.json.JSONObject` vs kotlinx `JsonObject` | ~28 of the 30 | both directions, boundary is not clean |
| `java.time.Instant` vs `kotlin.time.Instant` | 2 | fixed with an aliased import, worked |

**So the order in the spec is inverted.** 5G assumed "land in androidMain, reduce the surface after".
That cannot work: the surface drift already exists, because DB2's collaborators moved to kotlinx while
DB2 sat parked. **The JSON conversion has to come first, in staging, and only then does the move
compile.** Upstream's `eb6f17f494` is the model - it deleted its own `org.json` port and rewrote the
call sites in `buildJsonObject` / `put`. Mind `putFiniteOrNull`: `org.json` throws on NaN and kotlinx
does not.

**Two things that did work and should be kept:**

1. **The `AimiStorage` rewire dissolves that blocker, as predicted.** Four call sites
   (`ensureLoaded`, `observeMealWindow`, `observeEstimatedMeal`, `observeDawnPhase`) plus one injected
   field, and it compiled. `AimiStorageHelper` stays in `androidMain` for the `File`-typed members;
   both can coexist during the transition.
2. **The TensorFlow Lite dependencies have to be restored.** Upstream's KMP rewrite of
   `plugins/aps/build.gradle.kts` dropped the fork's four `org.tensorflow:*` lines, so
   `AimiModelHandler` cannot compile. They belong in the `androidMain` dependency block, and they are
   Android-only by nature - iOS needs its own adapter, per annex 5.

The work-in-progress DB2, with the storage rewire and the `Instant` fix already applied, is worth
redoing rather than recovering: it is a handful of edits on top of a file that must be JSON-converted
first anyway.

---

## 6c. The JSON surface of DB2, measured - and it is one function

After the second failed attempt at moving DB2, I stopped patching and measured what the conversion
actually is. The answer changes the size of the job.

| | |
|---|---|
| real JSON `.put(` calls | **200** (210 minus 10 on `preferences`, which are not JSON) |
| `JSONObject()` constructions | 22 |
| `JSONArray` | 3 |
| `JSONObject.NULL` | **61** |
| **reads** - `optString`, `optDouble`, `getJSONObject`, `has`, `keys`, `length` | **0** |
| `.toString()` on a built object | 1 |

**All 200 of those `.put(` calls live inside one function: `toMedicalJson(): String`, lines 680-984 -
305 lines.** (An earlier version of this note said 680-1405 and 726 lines. That was wrong: the range
was measured on a comment-stripped copy and the line numbers were mapped back incorrectly.) The whole
`org.json` *construction* in an 18,886-line file is one self-contained, write-only function.

**But the conversion has a second half, and it is the one that defeated both attempts.** Outside that
function there are **47 `JSONObject` type references** - the field declarations on
`AimiDecisionContext`, `var x: org.json.JSONObject? = null`. They construct nothing; they *receive*.
Some receive from inside `toMedicalJson()`, some from collaborators that lots 2-4 already moved to
kotlinx. That is why patching at either end failed: the two halves have to change together.

**And the catch block decides the NaN question.** The function ends:

```kotlin
    json.toString()
} catch (_: Exception) { "{ \"error\": \"JSON Generation Failed\" }" }
```

`org.json` throws on a non-finite `Double`, so **today a single NaN anywhere in the export destroys
the whole medical JSON** and emits that error string instead. That is worth knowing on its own - it
is a latent all-or-nothing defect in the fork, and nobody appears to have noticed.

For the conversion it settles the choice: **replicate it exactly.** A shim whose `put(String, Double)`
throws on non-finite reproduces the current behaviour with no change at all, which is what "move the
logic, never improve it" requires here. Whether an export should really be all-or-nothing is a
separate decision, and a real one, but it is not this lot's to take.

That is a different job from "convert 116 scattered sites":

- **It is write-only.** No read-side semantics to preserve, which is the direction that usually costs.
- **It has a testable output.** Build the JSON both ways from the same input and compare the strings.
  That is the gate the parked file otherwise lacks, and it is worth more than any review.
- **It is one reviewable unit.** 726 lines, one idiom repeated: `receiver.put("key", value)`, with
  `?: org.json.JSONObject.NULL` for absent values 61 times.

### The one behavioural difference to design for

`org.json.put(String, Double)` **throws** on NaN and infinity; `kotlinx` does not. So a value that
today raises - and is caught somewhere up the stack - would silently serialise as a number after the
conversion. `putFiniteOrNull` already exists in commonMain for exactly this and is the right
replacement, but **every one of the 200 sites has to be classified**: which carry a `Double` that can
be non-finite, and which cannot. That classification is the real work of the lot; the substitution
itself is mechanical.

`JSONObject.NULL` maps to `JsonNull`, or to omitting the key. **These are not the same thing** for a
consumer that distinguishes "absent" from "null", so pick one deliberately and apply it to all 53
inside the function (61 across the file).

### The method that makes this small

Do **not** hand-convert 200 `put` lines into a `buildJsonObject { }` DSL. That is a restructuring, on
a file no compiler is checking. Write a ~40-line internal shim with `org.json`'s shape over kotlinx -
`put(key, value)` returning itself, a `JsonArr` for the two array uses, and a `NULL` sentinel - and
the 200 call lines do not change at all. The diff becomes the shim plus 16 constructor lines, the NaN
rule lives in one `toElement()` instead of 200 site decisions, and the shim can be deleted later once
the fields are kotlinx-native.

### Why this was not obvious

Both failed attempts assumed the boundary could be adapted - retype the fields, or convert at the
call sites. Neither works, because the mismatch runs in both directions through the same data paths.
Measuring first would have shown that the mismatch is not spread through the file at all: it is
concentrated in one function, and the rest of DB2 never touches JSON.

---

## 6d. What is actually left on DB2, measured by the compiler

The JSON half is done and committed (`110f0cc666`). A move was attempted after it and reverted, but
the compiler produced the remaining list before it did. **This is the list to work from - it is short,
and it is not what the earlier notes feared.**

I wrote earlier that "the families keep changing" each round. **That was wrong.** What shifted was the
*messages*, as each fix exposed the next one; the set is bounded. The "30 errors" was also a double
count - the log prints each error twice.

**Eleven distinct issues**, all in `DetermineBasalAIMI2` except one:

| # | issue | kind |
|---|---|---|
| 1-2 | `JsonObj` assigned to a `JsonObject?` field, 2 sites | add `.build()` |
| 3 | `Any` assigned to `JsonObject?` | residue of the rewritten mutation site |
| 4-5 | `put` unresolved, 2 sites | same |
| 6 | `IobSurveillanceExport?` vs `AimiDecisionContext.IobSurveillanceExport?` | type resolution |
| 7 | `AuditorJsonlExport.appendLine` | signature changed - see below |
| 8-9 | `java.time.LocalTime` where a collaborator wants `kotlinx.datetime.LocalTime` | 2 sites |
| 10 | `java.time.LocalDate` likewise | 1 site |
| 11 | `R.string.format_insulin_units` unresolved | **a pre-existing bug** - see below |

### Two of these are worth knowing about on their own

**`AuditorJsonlExport.appendLine` now takes three parameters**, not two:
`appendLine(storage: AimiStorage, decisionsFile: AimiPath, jsonLine: String)`. Lot 2 moved it onto the
storage seam. DB2 still calls `appendLine(aimiDecisionsJsonlFile(), jsonLine)` and builds its path
with `File(externalDir, "AIMI_Decisions.jsonl")`. Both sides of that call have to change together.

**`R.string.format_insulin_units` does not exist.** `core/ui` has `format_insulin_units1` and
`format_insulin_units_signed`, and nothing named `format_insulin_units`. So
`context.getString(app.aaps.core.ui.R.string.format_insulin_units, requestedU)` in DB2 has been a
broken reference for as long as the file has been parked - it cannot compile, and nobody could see it
because nothing compiles it. **This is the clearest single argument for getting the file into a source
set:** a resource reference rotted and no one knew.

### The eleventh is mechanical after all - the earlier note here was wrong

This section previously said `AiCoachingService` was blocked because
`AimiBehaviorCausalInsight` is declared inside `advisor/AimiProfileAdvisorActivity.kt`, a 2,316-line
Activity, and that lifting it was a real decision. **That is not true.** The type is declared in
`advisor/AimiBehaviorCausalAnalyzer.kt` - **163 lines, zero `android` / `java` / `javax` / `org.json`
imports**. The Activity merely *uses* it.

The mistake was grepping for files that **contain** the name rather than for the **declaration**, so a
consumer was read as the owner. That is the third time in this port that reasoning from filenames or
name-matches has produced a wrong answer - the others were the blocker count (2 vs 22, because inline
fully-qualified names are invisible to an import scan) and a "type already in commonMain" clear that
had matched a different class of the same name. **Grep for `class X` / `object X` / `fun X`, never for
`X`.**

So the eleventh issue is a one-file move like the rest. `AiCoachingService` was pulled in only because
`TpoOrchestrator` takes it as a constructor parameter.

---

## 6e. Do the remaining four collaborators cut the same way? Measured

After the `AimiAuditor` port worked, the obvious question was whether the same cut applies to the four
files that still block the move. It does, structurally. But the second half of the answer is the one
that matters, and it is not the encouraging one.

### The cut works, and it is dramatic

Members actually used, counted by resolving each consumer's property of that type and every call on it:

| consumer | target | target size | members used |
|---|---|---:|---:|
| `ContextManager` | `ContextLLMClient` | 605 LOC | **1** (`parseWithLLM`) |
| `ContextManager` | `PatientStateRuntimeRefresher` | 199 LOC | **1** (`refreshFromContextIntents`) |
| `AutoDriveGater` | `HealthContextRepository` | 282 LOC | **1** (`fetchSnapshotForAutodriveGater`) |
| `AIMIInsulinDecisionAdapterMTR` | `AIMIPhysioDataRepositoryMTR` | **985 LOC** | **2** (`fetchLastHeartRate`, `fetchStepsData`) |
| `AIMIInsulinDecisionAdapterMTR` | `HealthContextRepository` | 282 LOC | 2 (`fetchSnapshot`, `getLastSnapshot`) |

**Six methods stand between the decision path and 2,071 lines of collaborators.** Four narrow ports,
in the shape lot 5A established, would cut all of it out of the compile graph. That is worth doing and
it is the next lot.

### But it does not lower the iOS cost, and it would have been easy to claim it did

The tempting reading of that table is: the decision path only needs heart rate and steps from the
985-line Health Connect repository, and those are the two easiest HealthKit mappings - the ones §11.4
lists as direct with no caveat. So Health Connect stops being a T3 item.

**That reading is wrong, and the transitive path is why.** `HealthContextRepository` is itself on the
decision path, and it calls four more methods on the repository: `fetchHRVData`, `fetchMorningRHR`,
`fetchSleepData`, `fetchThermalWindow`. What it returns is `HealthContextSnapshot`, which carries
`hrvRmssd`, `sleepDebtMinutes`, `sleepEfficiency`, `hcSleepSessionActive`, `asleepLiveConfidence` and
`thermalBelief`.

And that snapshot reaches the dose. `AIMIInsulinDecisionAdapterMTR:227` reads
`hrvCurrent = snapshot.hrvRmssd`, which is the input §11.4 traced into the stress and brake
computation and from there into `smbMult`.

**So §11.4's finding stands, confirmed rather than overturned.** The HRV RMSSD-versus-SDNN mismatch is
on the dosing path, and sleep and skin temperature - the other two caveated mappings - are on it too.
Health Connect to HealthKit remains a real T3 item for the engine, not only for the physio feature.

One incidental correction to §11.4's table: `HealthContextSnapshot` also carries `bpSys` and `bpDia`.
They are **not** sourced from Health Connect - there is no `BloodPressureRecord` anywhere in the
repository - so they are not an iOS concern, but the table should not be read as the complete field
list of what the decision path consumes.

### What this means for sequencing

The four ports are a good next lot: they are small, they follow a pattern that has now worked once,
and they remove 2,071 lines from the move. They do **not** remove the HealthKit work. Those are
separate facts and it is worth not conflating them - the port makes the Android-side port tractable;
the HealthKit adapter is still the price of running the engine on iOS.

---

## 6f. Five ports later: what actually blocks the move now

All five lot 5A ports are wired. Each one behaved as designed - the consumer names the port, the
implementation declares it, and the concrete class and everything behind it stays parked:

| port | members | keeps parked |
|---|---:|---|
| `AimiAuditor` | 2 | `AuditorOrchestrator` + `AuditorAIService` + `AuditorStatusLiveData` + the advisor UI |
| `AimiTpo` | 3 | `TpoOrchestrator` + `AiCoachingService`, `TpoLlmValidator`, `TpoNotificationManager`, `TpoSessionManager`, `TpoEndReason` |
| `AimiContextLlm` | 1 | `ContextLLMClient`, 605 LOC |
| `AimiHealthContext` | 3 | `HealthContextRepository`, 282 LOC |
| `AimiPhysioSource` | 2 | `AIMIPhysioDataRepositoryMTR`, 985 LOC |

**Eleven methods now stand between the decision path and roughly 3,000 lines of collaborators.** The
pattern works and it is worth continuing.

### What blocks the move is no longer a collaborator - it is where two types are declared

`DetermineBasalAIMI2` calls `readAimiBehaviorRuntimeProfile`. That needs `AimiAutonomyMode`, declared
inside `compose/AimiControlCenterSupport.kt`, and `AimiBehaviorFamilyId`, declared inside
`compose/AimiControlCenterScreen.kt` - a **1,016-line Compose screen**.

So the engine cannot compile without dragging a screen in, and the screen drags `TpoOrchestrator`
back, which is what the `AimiTpo` port had just cut out. That circle is the current blocker.

**A port does not fix this one.** These are not behaviours to abstract, they are two plain
declarations - an enum and an id - sitting in the wrong file. The fix is to lift them into their own
files under `compose/` or `model/`, which is mechanical and small, and then nothing in the engine
names a UI file at all.

This is worth stating as a rule the port has now demonstrated twice: **a type that non-UI code needs
must not be declared inside a UI file.** `AimiBehaviorCausalInsight` was the first case - it turned
out to live in `AimiBehaviorCausalAnalyzer.kt` rather than the Activity, which is why that one was a
false alarm. `AimiAutonomyMode` and `AimiBehaviorFamilyId` are real cases.

---

## 6g. Milestone, 2026-09-03: DetermineBasalAIMI2 compiles in a real source set

Commit `3ee2d6ec91`. This is the point the whole 5A/5G effort was aimed at. The circle from 6f -
`AimiAutonomyMode` and `AimiBehaviorFamilyId` declared inside Compose files - is broken, and with it
the 20-file cluster around `DetermineBasalAIMI2` (18,886 lines) moved out of the staging dump into
`plugins/aps/src/androidMain/`, where a compiler checks every line of it.

**How the circle broke.** `AimiAutonomyMode` carried `@StringRes val labelResId: Int` as a constructor
parameter, which was the only thing making the type Android-only - the four cases themselves are
plain data. Split: the enum (four cases, no label) moved to commonMain; the label became a UI-only
extension function, `AimiAutonomyMode.labelResId()`, in the androidMain screen that displays it - same
four resource ids, unchanged. `AimiBehaviorRuntimeProfile`, the data class DB2 actually reads, is pure
arithmetic and moved whole. Only `readAimiBehaviorRuntimeProfile` - which walks a
preferences-to-draft-to-snapshot chain through two file-based history readers - stays on Android,
behind a sixth port: `AimiBehaviorProfileSource.read(preferences)`, same shape as the five from 6f.

**A fourth and fifth duplicate turned up**, extending the pattern from 6b/6e: `PkPdIntegration.kt`
had its own copies of `MealAggressionContext`, `PkpdBolusSample`, `PkpdLearningTrace` and
`PkPdRuntime`, all already extracted to commonMain by an earlier lot. Diffed identical before
deleting.

**Moving the file exposed drift that had been accumulating invisibly.** Three separate places in the
Compose support files still read a bare `titleResId: Int` from types whose `title` had already become
`TextRef` under Milos's wave 10 migration - `AimiStringKey.ActivitySourceMode`/`OuraPersonalAccessToken`,
the `DoublePreferenceKey`/`BooleanPreferenceKey.controlCenterTitleResId()` helpers, and
`ActivitySourceMode.entries` itself (`Map<String, TextRef>`, not `Map<String, Int>`). One of these
pointed at `R.string.autodrive_max_basal_title` / `meal_modes_max_basal_title` - **a second rotted
reference**, same shape as `format_insulin_units` in 6f: neither resource exists anywhere in the tree.
None of this was visible before, because nothing compiled this file.

**And an AGP-version problem, not an AIMI one.** Restoring the four `org.tensorflow:*` dependencies
(needed since 6d) hit AGP's namespace-uniqueness check: `tensorflow-lite` and `tensorflow-lite-gpu`
2.4.0 both declare the manifest namespace `org.tensorflow.lite`. Grepped - nothing in
`AimiModelHandler` ever constructs a `GpuDelegate` - so the GPU artifact was dropped rather than
worked around. `dev_OAPSAIMI`'s older AGP never enforced this check.

**Verified**, not assumed: numeric-literal multiset checked file by file against the pre-lot state,
and the split profile file's 56 literals checked as a set against the original with none lost or
added. `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64`,
`:ios:shell:checkMigratedModules` all EXIT=0. `:plugins:aps:testAndroidHostTest` EXIT=0, 330 tests,
0 failures.

**State after this commit:**

| | files |
|---|---:|
| AIMI in `commonMain` | 339 |
| AIMI in `androidMain` | 52 |
| AIMI still in staging | 295 |

**What this does NOT do.** AIMI is still not in `ApsPluginRegistrations` - nothing in the running app
calls any of this yet. That is the next real milestone, and it is `OpenAPSAIMIPlugin.kt` itself: 2,272
lines, still on `javax.inject` (the H1 hazard from section 4 - convert it before it lands, not after),
and with 14 of its 38 `openAPSAIMI` imports still unresolved (measured 2026-09-03):
`AimiAdvisorService`, `AimiControlCenterScreen`, `AimiPkpdSettingsScreen`, `PkpdTailPrudence`,
`AimiPreferenceInfoScreen`, `HormonitorViewerScreen`, `AimiMlTrainingScheduler`,
`PhysioMultipliersMTR`, `ActivityStage`, `InsulinActivityStage`, `IsfFusionBounds`, `TpoOrchestrator`,
`StableOrbit`, `AimiBackupManager`. Some of these are genuine UI screens (rightly Android-only);
others may be the same "type stuck in the wrong file" pattern seen twice already in this section -
worth checking by declaration, not by filename, before assuming either.

---

## 6h. Milestone, 2026-09-03: `OpenAPSAIMIPlugin` registers and the app builds

Section 6g's next milestone is done: `OpenAPSAIMIPlugin.kt` (2,272 lines) moved into `androidMain`,
converted off `javax.inject.Provider` to a plain `() -> APSResult`, self-registered with
`@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>()) @MetroIntKey(250)` (the next
free slot after Autotune's 240 - grepped every `@IntKey`/`@MetroIntKey` in the tree first), and its
whole transitive dependency closure moved out of staging behind it. `:app:assembleFullDebug` now
succeeds - **this is the first time in the whole migration that AIMI is reachable from a running
build**, not just compiling in isolation.

**Scale.** ~45 files moved from `_docs/kmp/staging/openAPSAIMI-android-wip/` into `androidMain` this
lot: the oref pipeline (`OrefLocalPipeline`, `OrefOnnxScorer`, `OrefPersonalMlTrainer` - ONNX inference
was never wired at all), the whole auditor UI/model cluster, the TPO cluster
(`AiCoachingService`/`TpoSessionManager`/`TpoLlmValidator`/`TpoNotificationManager`), the physio/Health
Connect cluster (`AIMIPhysioDataRepositoryMTR`, `HealthContextRepository`, permission handlers, sync
service + worker), the wcycle DI module, and the Compose screens (`AimiControlCenterScreen`,
`AimiPkpdSettingsScreen`/`PkpdSettingsUi`, `HormonitorViewerScreen`, `AimiPreferenceInfoScreen`). Every
move followed the same fixpoint loop: `git mv`, compile, fix the exact reported error, repeat - never
predicting the closure in advance. Five duplicate top-level declarations were found and deleted along
the way (`TuningPreferenceLabels` was the fifth, byte-identical between a staging leftover and the
already-extracted commonMain copy).

**Recurring drift patterns, same shapes as 6g but in new files:**

- **`rh: ResourceHelper` narrowed to `TextResolver`.** `PluginBase.rh` is `open val rh: TextResolver`
  (KMP-common), so a plain (non-`override`) `rh: ResourceHelper` constructor parameter is invisible
  outside the primary constructor - every `rh.gs(R.string.x)` call in a member function actually
  resolved against the narrower inherited property and failed with "Int, but TextRef expected". Fixed
  by declaring `override val rh: ResourceHelper` on the plugin's own constructor param, matching the
  established pattern (`AutotunePlugin`, `VersionCheckerPlugin`, `BgQualityCheckPlugin` all do this).
  This one line fixed a dozen call sites at once; converting each call site to a named `TextRef`
  first (which I did before finding the root cause) was not wasted work, just not the minimal fix.
- **`AimiRecommendation.titleResId`/`descriptionResId` (Int) → `.title`/`.description` (TextRef).**
  Same shape as `AimiAutonomyMode`'s old `labelResId` - hit in `AimiAdvisorService`,
  `AiCoachingService`, `PkpdSettingsUi` independently. One `descriptionResId = 0` sentinel became
  `TextRef.Literal("")`, per the type's own doc comment: `Literal` is the direct replacement for the
  `0`/`-1` "no resource" sentinels.
- **`UnitType.valueResId()`/`.unitLabelResId()` → `.valueFormat()`/`.unitLabel()` in `:core:ui`,
  returning `TextRef?` not `Int?`.** `UnitType.kt`'s own comment says the mapping "lives in `:core:ui`
  (`UnitTypeText.kt`), not here" - the old names never existed there either, they'd just moved.
- **Two Android APIs that changed shape under the plain-Kotlin refactor:**
  `ExportPasswordDataStore.getPasswordFromDataStore()` and
  `ImportExportPrefs.exportSharedPreferencesNonInteractive(password)` both dropped a `Context`
  parameter they no longer need internally.

**Two capabilities were dropped, not renamed, and had to be restored rather than chased:**

- **`ResourceHelper.gsa()` (string-array reading) does not exist in the KMP interface at all** - only
  `gs`/`gq`/`gsNotLocalised`. Every other array-based preference in the tree had already been migrated
  away from Android `<string-array>` resources (`SafetyPlugin`'s `hardLimits.ageEntries()` is the
  precedent). Asked the user rather than guessing: **converted to Kotlin-native entries** - a small
  `aimiComposeEntries(vararg Pair<String,String>): Map<String, TextRef>` helper wrapping each label in
  `TextRef.Literal`, at the 8 call sites (Women's Cycle tracking/contraceptive, inflammatory disease,
  thyroid module). The backing `wcycle_strings.xml` string-arrays were never carried into the KMP
  tree; their content (recovered from `dev_OAPSAIMI`) is now inline at the call site instead.
- **The AIMI cloud-backup bridge (`CloudBackupConstants`, `EventAimiCloudBackupResult`,
  `EventAimiCloudBackupTrigger`, `ImportExportPrefs.uploadFileToCloud`) did not exist anywhere in the
  KMP tree**, though the `CloudStorageManager`/`CloudStorageProvider` abstraction it bridges to was
  already fully ported and working. Restored the 3 small `core:interfaces` files verbatim from
  `dev_OAPSAIMI`, and added `uploadFileToCloud` to `ImportExportPrefs` plus its three implementations
  (`ImportExportPrefsImpl` gets the real bridge to `CloudStorageManager`; `IosImportExportPrefs` and
  `DesktopImportExportPrefs` get a `failNotOnIosYet`/`failNotOnDesktopYet` stub, matching those files'
  own stated convention of throwing rather than faking a result).
- Also restored as plain missing resources (dropped, not renamed, confirmed by diffing against
  `dev_OAPSAIMI`): the `aimi_tube_advanced_title` string in `:core:keys`, and four color resources
  (`deviationGrey`, `examinedProfile`, `high`, `warning` - light + night) in `:core:ui`, both consumed
  by classic View-based UI (`AuditorUIState`'s status badge), not Compose, so restoring them doesn't
  fight the "no Android colors in Compose" rule.
- Two Gradle dependencies restored the same way as `tensorflow-lite`/`onnxruntime` in 6g:
  `androidx.health.connect:connect-client:1.1.0` (Health Connect - `AIMIPhysioDataRepositoryMTR`'s
  HRV/sleep/temperature/steps reads) and (already had) ONNX runtime.

**Four Metro bindings were missing entirely** - classes moved into `androidMain` implementing a port
interface (`AimiAuditor`, `AimiHealthContext`, `AimiPhysioSource`, `AimiContextLlm`) but never
annotated `@ContributesBinding(AppScope::class)`, so `:plugins:aps:compileAndroidMain` passed (nothing
there checks the graph) while `:app:compileFullDebugKotlin` failed with `Metro/MissingBinding` -
`AuditorOrchestrator`, `TpoOrchestrator`, `HealthContextRepository`, `AIMIPhysioDataRepositoryMTR`,
`ContextLLMClient`. This is the reason `:app:assembleFullDebug`, not just
`:plugins:aps:compileAndroidMain`, has to be the gate for "this plugin is actually live" - the module
compile alone cannot see a missing binding.

**One dead-code deletion, checked both ways before removing.** `OpenAPSAIMIPlugin.invoke()` had an
"FCL 11.0: Force Copy Predictions via JSON" block that built an `org.json.JSONObject` and mutated the
`JsonObject?` returned by `determineBasalResult.json()`. Checked `DetermineBasalResult.json()`'s
implementation on **both** sides: in the current KMP tree it is `Json.encodeToJsonElement(...)`, a
fresh value every call; in `dev_OAPSAIMI` it was `JSONObject(result.serialize())`, also fresh every
call. The mutation was already a no-op before this migration touched it, not something the migration
broke - confirmed dead on both timelines before deleting it, per the "check whether dead code is
hiding a bug" rule.

**Verified:** `:app:assembleFullDebug` EXIT=0, `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:plugins:aps:testAndroidHostTest` EXIT=0 (330 tests, 0 failures). Numeric-fidelity check was scoped
to files with actual logic edits (not pure `git mv`s, which cannot alter content) - none of those
edits touched a dosing-relevant constant; they were TextRef/API-signature/DI-annotation fixes.

**State after this lot:**

| | files |
|---|---:|
| AIMI in `commonMain` | 356 |
| AIMI in `androidMain` | 100 |
| AIMI still in staging | 247 |

**What this does NOT do.** The other 247 staged files (Compose screens beyond the ones just moved,
`AimiHormonitorStudyExporterMTR`, SOS SMS, and whatever else the app doesn't currently reach) are still
parked - none of them block the plugin from running with the feature set that compiled.

**Addendum, same day: the last two collaborator ports closed.** `AimiSmbComparison` and
`AimiEmergencySos` turned out not to be missing implementations at all - both had a fully-working,
already-injected concrete class sitting right next to them (`AimiSmbComparator`, field-injected by its
own concrete type; `EmergencySosManager`, called directly as a plain object) that the port was
designed to narrow down to, and neither had ever been wired up:

- `AimiSmbComparator.compare(...)`'s eleven parameters matched the port's signature exactly, field for
  field - added `: AimiSmbComparison` + `@ContributesBinding(AppScope::class)`, then narrowed
  `DetermineBasalAIMI2`'s `@Inject lateinit var comparator` from the concrete type to the port (its
  only two call sites use nothing but `.compare(...)`, so nothing else could break).
- `EmergencySosManager.evaluateSosCondition(...)` takes one parameter the port's own doc comment says
  on purpose does not belong in the signature - `context: Context`, "it belongs to the Android half."
  Added a two-line wrapper, `AndroidAimiEmergencySos`, holding the `Context` and delegating straight
  through with no behaviour change; `DetermineBasalAIMI2` now field-injects the port and calls
  `.evaluate(...)` instead of the object directly.

All eight collaborator ports designed for this migration now have exactly one implementation each.
Verified: `:plugins:aps:compileAndroidMain`, `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64`
all EXIT=0; `:plugins:aps:testAndroidHostTest` 330 tests, 0 failures. No numeric literal touched - both
changes are type-narrowing plus one call-site rename.

---

## 6i. Discovery, same day: 221 of the 247 "still staged" files were already ported

The 247 count in 6h's table was never wrong about the staging directory's contents, but it implied 247
files' worth of work still to do. It was not: 221 of them had already been ported to `commonMain` or
`androidMain` under the same filename, and the staging copy was a forgotten pre-refactor snapshot -
never deleted after whatever session or upstream merge actually did the port. Nothing in any
`build.gradle.kts` or `settings.gradle.kts` references `_docs/kmp/staging/` at all, so none of this was
ever compiled, tested, or reachable - ordinary `find`-by-basename against the two real source sets is
what surfaced it, not a build failure.

**Checked before deleting anything, not assumed:** 55 of the 221 were byte-identical to their real
counterpart - zero risk. The other 166 differed, so before deleting those the numeric-literal multiset
of each pair was compared (same technique as every fidelity check in this document) - 141 matched
exactly, and the 25 that didn't were individually diffed by hand, including the highest-stakes ones on
purpose: `pkpd/AdvancedPredictionEngine.kt`, `pkpd/AdaptivePkPdEstimator.kt`,
`autodrive/controller/MpcController.kt`, `advisor/gestation/GestationalAutopilot.kt`. Every single one,
with no exception found, turned out to be the same story: a systematic JVM-to-multiplatform primitive
substitution, already completed on the real file, that the number-diff or line-diff surfaces as noise
but changes no threshold, no control flow, no dosing constant:

- `AtomicReference`/`AtomicLong` + manual `synchronized` → `AapsLock`/`withLock` (or a plain `var`
  behind one lock)
- `System.currentTimeMillis()` → `aimiWallClockMs()`
- `String.format("%.Nf", x)` / a local `.format(digits)` extension → `aimiFmt1`/`aimiFmt2`
- `java.time.LocalDate`/`ChronoUnit`/`Math.round` → `kotlinx.datetime.LocalDate`/`daysUntil`/
  `kotlin.math.round`
- `javaClass.simpleName` → `.name` (enums) or `::class.simpleName` (sealed types)
- `@JvmStatic`/`@JvmOverloads` dropped (meaningless outside a JVM-only target)
- one frozen shared constant (`Constants.PREDICTION_GRAPH_MIN_MINUTES` = 120) inlined with a comment,
  because the KMP rewrite of `:core:data`'s `Constants` object dropped the AIMI-specific keys - same
  "capability genuinely removed, not renamed" shape as 6g/6h's dropped resources, just already handled
  by whoever ported the real file, before this session ever looked at it

Deleted all 221 (`git rm`, no content edit to any live file). Staging is down to 26 files, and every one
of them was independently confirmed to have **no** filename match anywhere in `commonMain` or
`androidMain` - legacy View-based Android Activities (`AimiProfileAdvisorActivity`,
`AuditorReportActivity`, `ContextActivity`, `MealAdvisorActivity`, permission Activities) and the
meal-photo vision providers (`ClaudeVisionProvider`, `GeminiVisionProvider`, `OpenAIVisionProvider`,
`FoodRecognitionService`). These are the only files in the whole original inventory that are actually
still unported.

Verified after deleting: `:plugins:aps:compileAndroidMain` EXIT=0 (expected - staging was never in any
source set, so this could only ever be a no-op check).

**Same day, addendum: the meal-photo vision pipeline moved.** Asked which of the 26 remaining files to
tackle first, since the View-Activity-versus-Compose question didn't need answering to make progress
on the rest: the answer was the 6 vision-provider files, independent of any Activity. Moving
`AIVisionProvider.kt` wholesale hit the same "duplicate by type, not by filename" shape as 6i's cleanup
but the other direction - the file's `AIVisionProvider` interface was genuinely new, but the same file
also carried `EstimationResult`/`VisibleFoodItem`/`MacroRange`/`FoodAnalysisPrompt`, already extracted
into two other already-ported commonMain files (`MealEstimateModels.kt`, `FoodAnalysisPrompt.kt`) under
different names than the monolithic staging original - so the filename-match check in 6i's cleanup
script never flagged it. Same underlying pattern as everything else this week: the real
`FoodAnalysisPrompt.kt` parses with `kotlinx.serialization` instead of `org.json`, same public API
(`cleanJsonResponse`/`parseJsonToResult`/`emptyErrorResult`/`SYSTEM_PROMPT`). Fixed by stripping the
four duplicated declarations out of the moved file, leaving only the interface (same package, so the
already-ported models resolve with no new import). `ClaudeVisionProvider`/`DeepSeekVisionProvider`/
`GeminiVisionProvider`/`OpenAIVisionProvider`/`FoodRecognitionService` moved with no further changes -
all their dependencies (`LlmHttpRetry`, `PatientStateRuntimeRepository`, `MealVisionUserPrompt`) were
already ported. `FoodRecognitionService` takes a plain constructor, not `@Inject` - nothing in the live
graph constructs it yet, since its only caller is the still-parked `MealAdvisorActivity`/
`MealAdvisorCameraActivity`. Verified: `:plugins:aps:compileAndroidMain`, `:app:assembleFullDebug`,
`:plugins:aps:compileKotlinIosArm64` all EXIT=0; `:plugins:aps:testAndroidHostTest` 330 tests, 0
failures. Staging down to 20 files - the View-based Activities only.

**Same day, addendum 2: `AuditorReportActivity` and the first cross-module plugin-status port.**
Asked which of the 20 to start with; picked as the smallest (24 lines) - a transparent trampoline
that showed a system-notification tap as a dialog, then finished itself. It turned out to need no
Compose rewrite of its own, but unwound three layers deep before landing:

1. **The trampoline's only real work, `uiInteraction.showOkDialog(...)`, doesn't exist anywhere in
   the current tree.** Not renamed - the whole "dedicated Activity shows one dialog" idiom was
   retired in favour of a global `rxBus.send(EventShowDialog.Ok(title, message, onOk))` consumed by
   a `GlobalDialogHost` composable mounted once at the app root
   (`appshell/.../AapsAppRoot.kt`). Fix: delete `AuditorReportActivity` outright (confirmed dead
   architecture, not aporting gap), rewrite `AuditorNotificationManager.openReport()` to send that
   event instead, and repoint its two `PendingIntent`s at `uiInteraction.mainActivity.java` - the
   same pattern `TpoNotificationManager` already uses for its own notifications.
2. **With the Activity gone, nothing calls `openReport()` on notification tap any more - by design**,
   per the user's choice: open the app, let the status badge carry the detail, don't force a popup.
   That pushed the real work onto the second piece: `AuditorStatusIndicator`, the old toolbar badge
   (a hand-built `FrameLayout` View, animations included, meant for a `DashboardShellController` that
   doesn't exist anywhere in the KMP tree - confirmed by search, not assumed). Its actual home in the
   current app is the Overview screen's chips row, next to the BG circle
   (`OverviewScreenStacked.kt`'s `BgInfoSection` + `OverviewChipsColumn`, confirmed by reading the
   layout rather than guessing).
3. **`OverviewChipsColumn` lives in `:ui` (plugin-neutral); the Auditor's live state
   (`AuditorStatusLiveData`) lives in `:plugins:aps`.** No existing extension point let one plugin
   contribute a status chip without `:ui` depending on that plugin - checked `StatusSectionContent`
   (hardcoded to exactly 4 device-status items) and the top app bar (`MainTopBar`, one hardcoded
   Settings icon, explicitly capped by its own doc comment at "2-3 icons") before concluding neither
   was reusable. The mechanism that *does* already cross this exact boundary is `Loop` - a neutral
   `core:interfaces` type, bound to whichever plugin implements it, injected straight into `:ui`'s
   `ChipsViewModel`. Built the same shape for this, scoped to exactly what AIMI needs:
   - `PluginStatusBadge`/`PluginStatusLevel`/`PluginStatusBadgeSource` -
     `core/interfaces/.../overview/PluginStatusBadge.kt` (new, commonMain, no Android in it - deliberately
     not named after "Auditor", the same way `Loop` isn't named after any specific APS algorithm).
   - `AuditorStatusBadgeSource` - `plugins/aps/.../advisor/auditor/ui/` (new, androidMain,
     `@ContributesBinding`), bridging `AuditorStatusLiveData`'s `LiveData` to a plain `StateFlow` via
     `observeForever` (safe: this is a process-scoped singleton, not a `View`/`Activity`), and
     `onBadgeClick()` calling the just-rewritten `AuditorNotificationManager.openReport()`.
   - `PluginStatusChip` - `ui/.../overview/chips/` (new, commonMain), styled from
     `AapsTheme.snackbarColors` (error/warning/info/success - already existing, semantic, no
     `ElementType`/`ElementColors` touched) rather than the old View's raw `@ColorRes` ints. Hides
     itself entirely at `IDLE` with nothing to count, so a quiet plugin adds no chrome.
   - Wired through `ChipsViewModel` (new `pluginStatusBadgeSource` constructor param + `pluginBadge`
     `StateFlow` + `onPluginBadgeClick()`) → `OverviewChipsColumn` (new `pluginBadge`/
     `onPluginBadgeClick` params) → all three `OverviewScreenStacked`/`Split`/`Tablet` variants,
     mirroring `sensitivityUiState`'s existing wiring line for line.
   - Two pre-existing tests (`ChipsViewModelTest`, `OverviewViewModelFixture`) constructed
     `ChipsViewModel` positionally and broke on the new parameter - added a mocked
     `PluginStatusBadgeSource` stubbed to `PluginStatusBadge(PluginStatusLevel.IDLE)` to both.

Verified: `:core:interfaces:compileAndroidMain`, `:plugins:aps:compileAndroidMain`,
`:ui:compileAndroidMain`, `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64` all EXIT=0;
`:plugins:aps:testAndroidHostTest` 330 tests and `:ui:testAndroidHostTest` 499 tests, 0 failures on
both. `AuditorStatusIndicator.kt` (the old View) deleted from staging rather than ported - fully
superseded by `PluginStatusChip`. Staging down to 17 files, all still View-based Activities or their
direct support classes.

**Why this took three rounds of research before any code:** each "obvious" next layer turned out to
be either gone (`showOkDialog`), never built in this tree at all (`DashboardShellController`), or
present but not extensible the way it looked (`StatusSectionContent`, `MainTopBar`). Every one of
those was confirmed by reading the actual current code, not inferred from the old (`dev_OAPSAIMI`)
design or from what a class's name implied - the same discipline as every other lot in this document,
just applied one layer further out than usual (into `:ui`/`:core:interfaces`, not just `:plugins:aps`).

---

## 6j. 2026-09-06 to 2026-09-08: two `kmp` merges, and a parallel AIMI porting effort (P0.1-P0.7)

Two upstream merges landed since 6i, and between them - **not from this session** - seven more AIMI
pieces were ported directly onto `kmp-aimi-migration-study` via GitHub PRs (#71-#79), done with a
Cursor agent and reviewed/merged by the user. This section is the analysis the user asked for after
the second merge: what that parallel work is, and that it does not conflict with anything in 6a-6i.

**The two merges themselves:**

- **2026-09-06, ~106 commits from `milos/kmp`.** The big one: `ImportExportPrefs` gained a real
  cross-platform implementation (`LocalImportExportPrefs` in `:implementation` commonMain, replacing
  the old iOS/desktop "not ported yet" stubs), and `CloudStorageManager`/`CloudConstants` moved from
  androidMain to commonMain in the same module. Three conflicts, all in the settings-export area this
  session had also touched (6h's `uploadFileToCloud` addition): resolved by keeping both sides'
  additions (upstream's `prepareImportRestart` → `prepareImportedSettings` rename, and this branch's
  `uploadFileToCloud`), and by adding `uploadFileToCloud` to the new `LocalImportExportPrefs` so
  iOS/desktop get the same AIMI cloud-backup path Android already had. One more break found only by
  compiling: `StringKey.OApsAIMIContextStorage` used `exportable = false`, a parameter that used to
  exist on `StringKey`'s own constructor and no longer does - `exportable` now lives only on
  `NonPreferenceKey`. Dropped the argument; nothing depended on that key being excluded from export
  (the only code that ever executed `isExportableKey` against `StringKey` entries reads a `prefsList`
  typed `Set<NonPreferenceKey>`, so `StringKey.exportable` had never actually been wired in - the
  argument compiled but did nothing, on both sides of the merge).
- **2026-09-08, 9 commits, no conflicts.** CI/TestFlight signing checks, iOS/desktop preference-screen
  navigation, a slow-basal-rebuild race fix, and a Metro version bump (snapshot → 1.4.3 release). None
  of it touches `plugins:aps`.

**Discovered while verifying the second merge, not caused by it:** now that `plugins:aps` (this
week's P0.x work) and `:ui` (today's merge) both have real `commonTest` content for the first time,
running `:iosSimulatorArm64Test` - the CLAUDE.md note that only `:core:data` has `commonTest` is
stale - surfaced a Kotlin/Native-only restriction neither Windows nor the Android host test catches:
**a backtick-quoted test name may not contain a comma** ("Name contains illegal characters"). The JVM
accepts anything in a backtick identifier; Kotlin/Native has to turn the name into a valid symbol and
refuses. Two occurrences, both from this week, both harmless prose renamed with no assertion changes:
`CommandedIsfOrderTest` (P0.4) and `ProfileBoundariesTest` (today's merge, from upstream). Grepped the
whole repo's `commonTest` trees precisely (`fun` \`...,...\`() a comma inside a backtick pair) for
more - none. Worth remembering as its own item in the recurring-shapes list (7.2) now that AIMI code
lives in `commonTest`: a comma in a backtick test name is a new failure shape, not one of the five
already listed.

**Also discovered, environmental and unresolved:** `:iosSimulatorArm64Test` cannot finish on this Mac
right now - `xcode-select -p` points at `/Library/Developer/CommandLineTools`, not
`/Applications/Xcode.app/Contents/Developer`, so `xcrun xcodebuild -version` fails and the simulator
test link step never runs. This is a machine setting (`sudo xcode-select -s ...`), not a code issue,
and out of scope for this session to change unasked. It only blocks the simulator *run* - both fixed
test files were confirmed to *compile* clean for `iosSimulatorArm64` (`compileTestKotlinIosSimulatorArm64`
succeeded before the link step hit the Xcode path problem), and `:plugins:aps:compileKotlinIosArm64`
(main sources, unaffected by this) passed EXIT=0 on both merges.

**The parallel P0.1-P0.7 AIMI work itself**, all seven PRs following one documented pattern - port
the reference implementation from `origin/dev_OAPSAIMI` at a cited commit, land it in commonMain,
adapt only the KMP-mechanical parts (`AapsLock` for `@Synchronized`, `aimiFmtN` for `String.format`,
`ArrayDeque`/`MutableMap` for JVM collection types), keep the clinical formula and call-site order
identical to the reference, put tests in `commonTest` when the type carries no Android dependency:

| PR | What | Where |
|---|---|---|
| P0.1 (#71) | `PkPdLearnedState` - one shared learned DIA/peak holder for both `PkPdIntegration` instances (plugin + `DetermineBasalaimiSMB2`), replacing two separate copies | `pkpd/PkPdLearnedState.kt` |
| P0.2 (#72) | `DynIsfCache` - time-keyed ISF store; fixes a real bug in the store it replaces (keyed on `bucketStart + glucose`, so "newest key" during a falling BG returned the value from the bucket's peak, not the latest sample) | `ISF/DynIsfCache.kt` |
| P0.3 (#75) | `ObservedSensitivityMeter` - passive outcome ISF instrument, observation only | `ISF/ObservedSensitivityMeter.kt` |
| P0.4 (#76) | `CommandedIsf` - shadow witness reading the ISF instrument before `floorAgainstProfile` runs, so the pre-floor value is on record | `ISF/CommandedIsf.kt` |
| P0.5 (#77) | `MaxSmbLadder` - extracted the maxSMB ceiling out of `DetermineBasalAIMI2` (it was inline) and added the `shortAvgDelta >= 8.0` confirmed-rise branch alongside the existing slope-only rule | `smb/MaxSmbLadder.kt` |
| P0.6 (#78) | `HarmoniaCounterfactual` + `InsulinOriginMeter` - an observation pair, distinct from the existing one-tick `IobSurveillanceExport` snapshot, which stays alongside it | `patient/HarmoniaCounterfactual.kt`, `quality/InsulinOriginMeter.kt` |
| P0.7 (#79) | `SmbTrainingRowBuffer` - in-memory delayed origin/outcome CSV row queue for the ML training corpus; drain still goes through the existing androidMain `oapsaimiML2_records.csv` writer | `ml/SmbTrainingRowBuffer.kt` |

All observation/instrumentation, explicitly "no dose change" per every commit message; confirmed live
(44 references to these eight types inside the current `DetermineBasalAIMI2.kt`, not parked). None of
it touches `_docs/kmp/staging/` - it is ported straight from `dev_OAPSAIMI`, a completely separate
source from the staging snapshot 6i cleaned up, so the "17 files, all View-based Activities" count
from 6i is unaffected and still current.

**State after both merges and the P0.x series:**

| | files |
|---|---:|
| AIMI in `commonMain` | 364 (+8 from P0.1-P0.7) |
| AIMI in `androidMain` | 109 |
| AIMI still in staging | 17 (unchanged - separate source) |

Verified after the second merge: `:app:assembleFullDebug` EXIT=0; `:plugins:aps:compileKotlinIosArm64`
EXIT=0; `:plugins:aps:testAndroidHostTest` 419 tests (was 330 - the ~89 new P0.x tests) and
`:ui:testAndroidHostTest` 524 tests, 0 failures on both.

---

## 6k. 2026-09-12: staging cleanup lot - 6 of the 17 files were dead, not pending

Before starting the Compose port of the last 17 staged files, a survey pass checked each one for live
callers and live successors, instead of assuming all 17 still needed porting (the same question that
made `AuditorReportActivity` turn out to need no port at all, back in 6i). Six did not:

- `AimiDiagnosticsManager.kt` - a live file of the same name already exists in `androidMain`, and is a
  strict superset (English text, plus the 2026-09-06 active-profile fix from 6-something's support
  report work). Diffed line by line to confirm before deleting.
- `StateTransitionManager.kt` (`advisor/auditor/model/`) - superseded by the live
  `AimiStateTransitionManager` (`advisor/auditor/`), which `AuditorOrchestrator` actually constructs.
  Same job (Auditor state machine), different name, so a grep for the old name found nothing live.
- `AimiSmbSimulator.kt` (class `DualEngineSimulator`) - its whole supporting cast
  (`VirtualGlucoseEngine`, `VirtualInsulinReservoir`, `VirtualIobCalculator`, `PerformanceScorer`) is
  already live and already consumed by `AimiSmbComparator`, a different top-level class doing the same
  comparison job. Zero references to `DualEngineSimulator` anywhere.
- `AIMIHealthConnectStepsProviderMTR.kt` and `AIMICompositeStepsProviderMTR.kt` - the steps
  architecture moved to a sync-to-database model (`AIMIHealthConnectSyncServiceMTR` /
  `AIMIDatabaseStepsProviderMTR` / `AIMIStepsManagerMTR`) after these two were written; neither has a
  caller left.
- `AimiMemberInjectors.kt` - a DI wiring template for a `MembersInjector`-per-Activity pattern the
  project has moved away from (see `PluginStatusBadgeSource`, a plain interface + `@ContributesBinding`
  instead). It was already stale against its own staging tree - it imports `AuditorReportActivity`,
  removed back in 6i.

Verified each with a grep across the whole repo excluding `_docs/kmp/staging/` before deleting, same
discipline as 6i. `_docs/kmp/staging/` is not part of any Gradle source set (confirmed: no
`build.gradle*` references it), so this cleanup needed no build re-verification.

**11 files are left** (was 17), all confirmed genuine UI still to port, backend already live in every
case - see 7.1 for the lot split. One of the 11, `AimiLoopRuntimeGuard.kt`, wraps a live telemetry
method that nothing calls yet (no Overview wiring exists for it) - held rather than ported, per the
"don't ship a registration nothing consumes yet" rule; port it together with whatever feature ends up
needing it, not before.

---

## 6l. 2026-09-12: lot 2 - the two permission screens, ported to Compose

`AIMIHealthConnectPermissionActivityMTR` and `AIMIEmergencySosPermissionActivityMTR` are gone from
staging, replaced by `AimiHealthConnectPermissionScreen.kt` (`openAPSAIMI/physio/`) and
`AimiSosPermissionScreen.kt` (`openAPSAIMI/sos/`) - self-contained `@Composable` functions in the same
androidMain packages as the backend they wrap, not new Activities. **9 files left** (was 11).

Neither screen is an Activity any more. Both are wired the same way `AimiSupportPackageScreen` and
`AimiControlCenterScreen` already are: `ApsIntentKey.AimiHealthConnectPermissions` /
`.AimiSosPermissions` now carry `.withCompose { onBack -> ... }` at their `add(...)` call site in
`OpenAPSAIMIPlugin.kt`, and their `preferenceType` moved from the leftover `PreferenceType.ACTIVITY`
to `PreferenceType.CLICK`, matching every other Compose-backed entry in that same enum (the type had
no actual effect on rendering - `IntentPreferenceKey` picks compose-vs-click-vs-url by which field is
set, not by this enum - but every sibling entry uses `CLICK`, so the two leftover `ACTIVITY` values
were corrected for consistency, not because anything depended on them).

One reusable decision made here, worth remembering for the rest of this lot split: **there is already
an app-wide permission sheet** (`app.aaps.ui.compose.permissionsSheet.PermissionsSheet`, backed by
`PermissionGroup`/`PluginPermissionsImpl`), and it was deliberately **not** used for either screen.
Two independent reasons: (a) `:plugins:aps` does not depend on `:ui` today, and adding that edge
without discussion is against this project's own inter-module rule; (b) that sheet's "is it granted"
check is synchronous (`ContextCompat.checkSelfPermission`-shaped), while Health Connect's is a suspend
call through its own `PermissionController` - a different model the sheet's existing wiring does not
handle. Both screens instead check permissions themselves, the same way the Activities they replace
did, and only borrow that sheet's *visual* language (a `ListItem` row with a
`CheckCircle`/`Warning` leading icon) by hand, once per screen - a small, accepted duplication rather
than a new cross-module dependency for two screens.

The SOS screen keeps the two-stage request Android itself requires: foreground (SMS + fine/coarse
location) first, then, only after those are granted, a *second*, separate request for background
location - Android will not grant background location in the same dialog as foreground permissions
(confirmed against the one other place in this repo that already does the same split,
`AndroidLocationPermissions.kt` in `:plugins:automation`).

Dropped from the original Activities, deliberately: the old HC Activity's `onNewIntent` handler for
Health Connect's system `ACTION_SHOW_PERMISSIONS_RATIONALE` intent (this module has no
`AndroidManifest.xml` entry that could ever receive it - confirmed before dropping, not assumed), and
its post-grant "test read 5 minutes of steps" diagnostic probe (developer-facing debugging output, not
something a real user needs to see on a settings screen).

Verified: `:app:assembleFullDebug` EXIT=0 (after the usual stale-KSP purge - a Dagger/Hilt-referencing
generated file broke the first attempt, unrelated to this change, see 6h/6i for why that keeps
happening after any DI-graph-touching change), `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:plugins:aps:testAndroidHostTest` 514 tests, 0 failures.

---

## 6m. 2026-09-12: lot 3 - the Context cluster, ported to Compose

`ContextActivity.kt`, `ContextIntentAdapter.kt`, `PatientSignalGaugeBinder.kt` and their 3 layout XMLs
are gone from staging, replaced by one file: `AimiContextScreen.kt` (`openAPSAIMI/context/ui/`). **5
files left** (was 9): Meal Advisor + camera, Mode Settings, Profile Advisor, and the held
`AimiLoopRuntimeGuard`.

`ContextViewModel.kt` was **not** ported - it was dead weight even in staging. Its own package
(`context.ui`) had two competing implementations sitting side by side: the Activity called
`ContextManager` directly and said so in its own doc comment ("Simplified version without ViewModel
for quick implementation"), while `ContextViewModel` wrapped the same calls in `LiveData` and was
never once referenced by the Activity or anything else. Grepped to confirm zero live callers before
dropping it - same check as every other deletion in this port, see 6k.

This lot needed a real backend survey before writing any UI, not just a port: `ContextManager` -
already a plugin constructor field, used elsewhere - had four call shapes the staged Activity had
subtly wrong (`addPreset` returns one `String` id, not a list; `getAllIntents()` returns a `Map`, not
a `List<Pair<...>>`, though `.toList()` on either produces the right shape so this one didn't matter;
`removeIntent`/`extendDuration` return `Boolean`, ignored same as before). `ContextPreset.ALL_PRESETS`
has grown to 12 entries since the Activity was parked (it assumed 10, hardcoded by index) - the new
screen iterates the list and reads each preset's own `displayName`/`icon` instead of hardcoding a
chip per index, so it will not go stale again the next time a preset is added. Two `ContextIntent`
subtypes (`SlowCarbMeal`, `HypoRecovery`) existed in the model but were never handled by the staged
Activity's display code at all - both are handled now.

This feature had **no live entry point anywhere** before this lot - not a leftover Activity reference,
an actually-missing one: no `ApsIntentKey` entry, no preference-tree `add(...)`, most of its
`context_*` string resources were never created. Added `ApsIntentKey.AimiContext` (top-level, same
shape as `AimiControlCenter`/`AimiSupportPackage`) and wired it with `.withCompose` right next to
those two. `HealthContextRepository` (needed for the same on-resume snapshot refresh the old Activity
did) was Metro-injectable but not yet a plugin constructor field either - added as one, the same way
`preferences`/`tpoOrchestrator` already were, since Metro resolves it automatically at construction;
this is a same-module Metro dependency addition, not the kind of new inter-module Gradle edge the
project's dependency rule is about.

Verified: `:app:assembleFullDebug` EXIT=0 on the first attempt (no stale-KSP issue this time),
`:plugins:aps:compileKotlinIosArm64` EXIT=0, `:plugins:aps:testAndroidHostTest` 514 tests, 0 failures
(unchanged - a UI-only lot, same as 6l, adds no new tests).

---

## 6n. 2026-09-12: lot 4 - Meal Advisor + its camera screen, ported to Compose (multi-agent lot)

`MealAdvisorActivity.kt` and `MealAdvisorCameraActivity.kt` are gone from staging, replaced by one
file: `AimiMealAdvisorScreen.kt` (`openAPSAIMI/advisor/meal/ui/`). **4 files left** (was 5):
`AimiModeSettingsActivity`, `AimiProfileAdvisorActivity`, and the held `AimiLoopRuntimeGuard`.

This lot ran as definer -> coder -> reviewer, each a separate agent, rather than one pass done
directly - the first lot in this port done that way on request. Worth recording what that bought and
what it cost, since more lots may use it:

- **The definer (a research-only agent) surfaced one real architectural fork before any code was
  written**: `MealAdvisorCameraActivity` uses raw Camera2 (not CameraX - this repo had zero CameraX
  usage anywhere), so porting it is a materially different decision from a layout port - Camera2
  wrapped in a Compose `AndroidView`, or adopt CameraX as this repo's first precedent. Put to the user
  rather than guessed (per the standing rule in section 7.1): **Camera2-in-`AndroidView`, no new
  dependency**. The same survey also found two real bugs in the original - rotation hardcoded to a
  fixed 90° regardless of device orientation, and a silent no-op on camera-permission denial (the
  Activity called `requestPermissions` but never implemented `onRequestPermissionsResult` at all) -
  also put to the user: **fix both**, rather than port them as-is.
- **The two staged Activities became one Compose screen**, not two, same choice as 6m's Context
  screen and for the same reason: there is no cross-screen "launch and get a result back" contract in
  this app's preference-Compose-screen mechanism (`ComposeScreenContent { onBack -> ... }`, one screen
  per entry). A local `showCamera` boolean toggles between the input/result view and a full-screen
  Camera2 capture view inside one composable, instead of inventing a new navigation contract for two.
- **The coder agent's first run was cut off mid-task by a platform rate limit**, after writing the
  main screen file and the manifest permission but before the string resources, the `ApsIntentKey`
  wiring, or a build check. Resumed via the same agent (not restarted, so the ~200K tokens of context
  it had already built were not thrown away) with a message pointing at exactly what survived and
  what was still missing - it finished the rest in one more pass and reported `BUILD SUCCESSFUL`.
- **The reviewer agent hit its own turn limit before reporting**, mid-check of one detail
  (`ExposedDropdownMenu` used with no matching top-level import - not a bug, it resolves as a
  `ExposedDropdownMenuBoxScope` member, same as every other dropdown in this codebase). Resumed with
  an explicit instruction to converge and call `ReportFindings` rather than open new lines of
  investigation. Found one real, verified issue the coder's own build-passing self-check could not
  have caught: **the camera was never reopened after `ON_PAUSE`** - only `stop()` was wired to
  `ON_PAUSE`, with no `ON_RESUME` counterpart, so backgrounding the app while the capture screen was
  open and returning left a frozen preview and a capture button that always failed. The reviewer
  cross-checked the dosing-relevant confirm-flow contract (`persistenceLayer.insertOrUpdateCarbs`,
  then `BooleanKey.OApsAIMIMealAdvisorTrigger`/`DoubleKey.OApsAIMILastEstimatedCarbs`/
  `DoubleKey.OApsAIMILastEstimatedCarbTime`) against every live reader in `DetermineBasalAIMI2.kt` and
  confirmed it unchanged - the one thing in this lot that would have been a real dosing-safety defect,
  not a UX one, had it drifted.
- **Fixed directly rather than sent back to an agent**: the ON_RESUME gap was a small, precisely
  understood fix once named - added a `wasStoppedForPause` flag so the restart only fires after a
  genuine pause, not on Lifecycle's synchronous ON_RESUME replay to an observer added while already
  resumed (which would otherwise restart the camera a second time right at screen entry, on top of
  the `AndroidView` factory's own first `start()`).

Verified after the fix: `:app:assembleFullDebug` EXIT=0, `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:plugins:aps:testAndroidHostTest` 514 tests, 0 failures (unchanged - UI-only, no new tests, same as
6l/6m).

---

## 6o. 2026-09-13: lot 5 - Mode Settings, ported to Compose (multi-agent lot, clean this time)

`AimiModeSettingsActivity.kt` is gone from staging, replaced by `AimiModeSettingsScreen.kt`
(`openAPSAIMI/advisor/modesettings/ui/`). **3 files left** (was 4): `AimiProfileAdvisorActivity`
(2321 lines, next), and the held `AimiLoopRuntimeGuard`.

Same definer -> coder -> reviewer split as 6n, but no agent needed resuming this time - both finished
their turn budget cleanly in one pass each.

**Unlike every advisor screen ported before it (Context, Meal Advisor), this one is not advisory - it
is a live control surface for the dosing engine.** The "Activate <mode>" button writes a therapy-event
NOTE whose exact text ("Lunch" / "Dinner" / "Breakfast" / "High Carb") is matched by substring in
`therapy.kt` (`findActiveLunchEvents` and its three siblings) to drive real prebolus/`smbMult`/meal-mode
decisions in `DetermineBasalAIMI2.kt`. That raised the review bar: the definer's survey confirmed no
backend drift at all (a first for this port series - every prior lot found at least one stale
assumption), but flagged the one thing that mattered most - the note text has to survive translation
untouched. The coder kept it as a separate, deliberately non-localized `noteText` field on the mode
enum, apart from the translatable tab-label string shown in the UI, so a future translator can never
touch the substring the dosing matcher depends on. The reviewer verified this by checking which of the
two strings actually gets written to `TE.note` (the plain `noteText`, never the localized display
label) - the trap a careless port could fall into without ever failing a build or a test, since nothing
in this repo tests `therapy.kt`'s note-matching against a live Compose screen's output.

One design call, made explicit rather than defaulted: the screen's 4 "Duration (min)" values live in a
private `SharedPreferences` file (`"aimi_mode_activity"`) entirely outside the `Preferences`/`IntKey`
system, and nothing else in the app reads them. Asked whether to keep that as-is or promote them to
real `IntKey` entries for consistency with the other 10 mode settings (which are already
`DoubleKey`/`IntKey`) - kept as-is, since nothing depends on the inconsistency and promoting it would
be scope beyond what this lot needed.

Also dropped, confirmed dead by the definer before any code was written: unused `automation`/`rh`
injected fields, a never-wired "AI Settings" input trio (`inputOpenAiKey`/`inputGeminiKey`/
`switchProvider` - declared, never initialized, never added to any layout, never read), and an unused
`getInputBackground()` helper.

Not fixed, flagged for later as a shared follow-up across all three advisor screens rather than
patched here alone: none of `AimiContextScreen`/`AimiMealAdvisorScreen`/`AimiModeSettingsScreen` set
`CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)` on their `Card`s
or have a `@Preview`, and all three expose their top-level composable as `public` rather than
`internal` - cosmetic/consistency items, not correctness bugs, worth doing as one pass over all three
rather than three separate touch-ups.

Verified: `:app:assembleFullDebug` EXIT=0, `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:plugins:aps:testAndroidHostTest` 514 tests, 0 failures (unchanged - UI-only, no new tests).

---

## 6p. 2026-09-13: `AimiProfileAdvisorActivity` split into 5 sub-lots; sub-lot 1/5 (Tuning Context) done

The last screen-shaped staged file, `AimiProfileAdvisorActivity.kt` (2321 lines, ~5x any screen
ported so far), was surveyed and split before any code was written, same discipline as every prior
lot but at a larger scale. Ten distinct sections were found (bootstrap, dashboard header, a
support-ZIP flow, model selector, metrics/recommendations, brain/Oref/CGM-range chart, AI Coach,
Tuning Context, Behavior Causal Map/Family Bridge, T3c/Harmonia runtime history, footer). Two were
resolved before any porting: the support-ZIP flow (lines ~450-601) is fully superseded by the
already-live `AimiSupportPackageScreen` from an earlier lot - confirmed the live version does
strictly more (adds `[ACTIVE PROFILE]` and an ML-training-CSV tail the staged code never had) - so
that section is simply dropped, not ported. The Behavior Causal Map / Family Bridge section (~390
lines) was put to the user: it has zero live callers anywhere and zero tests, was built only for this
Activity and never wired anywhere else - decided **not to port it**, same treatment as the six files
already dropped in 6k, rather than resurrecting ~390 lines of code nothing exercises.

The remaining 5 sections became the sub-lot plan, smallest/safest first: (1) Tuning Context - done
this entry; (2) Metrics + Recommendations + Apply flow; (3) T3c/Harmonia/RBT runtime history cards;
(4) Brain + Oref + AI Coach cards; (5) Header + quick actions (dashboard, basal-profile proposal,
model selector). Confirmed via grep that this file shares no classes or preference keys with
`AimiModeSettingsActivity` (6o) - the two needed no coordination.

**Sub-lot 1 (Tuning Context, ~230 lines) is done.** New screen:
`AimiProfileAdvisorScreen.kt` (`openAPSAIMI/advisor/compose/`), wired via a new top-level
`ApsIntentKey.AimiProfileAdvisor` entry - added now, not deferred, because every future sub-lot's
cards read from the same `AdvisorReport` this sub-lot's screen already loads; building the
loading/error state once now means sub-lots 2-5 just add cards to the existing `Column`, not
redesign the state model. The screen will look sparse (one card) until more sub-lots land - an
accepted, explicit trade for having something real and testable now instead of an unwired composable
sitting in the tree for 4 more lots.

A design/wiring decision surfaced and made without needing to go back to the user: constructing
`AimiAdvisorService` for the new screen exposed that its `calculateMetrics` silently falls back to
hardcoded fake TIR numbers (`tir70_180=0.65`, `timeBelow70=0.05`, `timeAbove180=0.30` - not derived
from the patient's real data at all) whenever no `TirCalculator` is supplied, and `TuningContextEngine`
gates its whole tiering decision on those numbers. `TirCalculator` is already Metro-bound and already
injected elsewhere in this same plugin (`DetermineBasalAIMI2.kt`) - added as a new `OpenAPSAIMIPlugin`
constructor field (same-module Metro addition, not a new Gradle dependency, same pattern as
`HealthContextRepository` in 6m) and threaded through. Review confirmed the fix genuinely takes effect
for the new screen, and caught the same bug still live at a **second**, pre-existing
`AimiAdvisorService` construction site (`aimiComposePkpdSetupItem`'s PKPD-recommendations loader,
`OpenAPSAIMIPlugin.kt`) that this lot's change didn't originally touch - fixed too, same one-line
addition, since the plugin now has `tirCalculator` as a field either way. **Any card in a future
sub-lot, or any other code path, that constructs `AimiAdvisorService` without `tirCalculator` will
silently compute against fake TIR data - always pass it now that it exists as a plugin field.**

Review also found the async report-load had no error handling at all (unlike the original, which
caught `Throwable`, special-cased `OutOfMemoryError`, and showed a worded message) - an uncaught
exception would have meant either a crash or an infinite spinner with no way for the user to tell
what happened. Fixed: same try/catch shape as the original, an `aimi_adv_error_prefix`/`_error_oom`
message shown in place of the spinner. Also found and fixed while touching that code path: the new
screen called `generateReport()` with no arguments, silently dropping the `history` argument
(defaults to `emptyList()`) that a *later* sub-lot's recommendation cards need for their 48h-cooldown
filter (`isRecommendationVisible`/`wasPreferenceKeyAppliedInLast48h` - without real history, a
recommendation could resurface immediately after being applied, instead of staying hidden for 48h)
and the `assetContext` the OREF pipeline uses to load its bundled ML asset. Both are invisible today
(sub-lot 1 renders nothing that depends on either) but would have silently degraded sub-lot 2 and
sub-lot 4 once built on top of the same `report` this sub-lot loads - fixed now while the call site was
already open, rather than left for a future sub-lot to rediscover. One unrelated, pre-existing base
resource bug fixed in passing: `aimi_adv_error_prefix` (the string this fix now actually renders) was
`"Erreur: "` in the base (non-French) `values/aimi_strings.xml` - corrected to `"Error: "`.

Verified: `:app:assembleFullDebug` EXIT=0, `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:plugins:aps:testAndroidHostTest` 514 tests, 0 failures (unchanged - UI-only, no new tests). The
staged `AimiProfileAdvisorActivity.kt` is NOT deleted yet - 4 sub-lots still read from it.

## 6q. 2026-09-14: `AimiProfileAdvisorActivity` sub-lot 2/5 - metrics, recommendations and the apply flow

Sub-lot 2 of 5 (staged lines ~635-921 plus their call site ~235-259) is done: the metrics grid, the
observation/PKPD recommendation cards and the apply flow now render as Compose cards appended to the
`AimiProfileAdvisorScreen.kt` that 6p created. No second entry point, same `AdvisorReport`.

The definer survey paid for itself again, and harder than usual. `AimiRecommendation` no longer
carries `titleResId`/`descriptionResId` - it carries `TextRef` - so the staged card's whole
description logic (a `when` over four specific string ids, injecting metric numbers) was dead code
against a model that no longer exists. Worse, those four recommendations - hypos, poor control,
hypers, basal dominance - **were not emitted by any engine anywhere**, not on this branch and not on
`dev_OAPSAIMI` either. Commit `8c7a6c63a9` (2026-03-13, "Migrate recommendation generation to a
plugin-based system") had deleted all four rules and replaced them with `SafetyAggressionPlugin` /
`StableControlPlugin`, which implement a different, real-time-BG rule and which **nothing ever
registers** - there is not one call to `AimiPluginManager.register(...)` in the tree, so
`collectActions` has always returned an empty list. The Advisor had been shipping its metric section
with no metric rules behind it for six months.

Put to the user, who chose to restore the four rules rather than drop them. That made this lot
engine work as well as a screen port, so the rules were restored from the deleted code with their
original thresholds rather than invented: hypos `timeBelow70 > 0.04` (Critical/Safety, proposes
`OApsAIMIMaxSMB` x0.8 only when Max SMB > 1.5), poor control `tir70_180 < 0.70 && timeBelow70 <= 0.03`
(High/Basal, proposes `OApsAIMILunchFactor` +0.1 only while it is below 1.2), hypers
`timeAbove180 > 0.20 && timeBelow70 <= 0.03` (Medium/Isf, informational), basal dominance
`basalPercent > 0.55` (Medium/Basal, informational). Restoring them surfaced a real bug in the
original: the lunch-factor line read `(prefs.lunchFactor + 0.1 * 10.0).roundToInt() / 10.0`, which by
operator precedence is `lunchFactor + 1.0` and then `/ 10`, so a lunch factor of 1.0 would have
proposed **0.2**, not 1.1 - a large unannounced cut to meal aggressiveness, one tap away. Restored
with the brackets fixed and a test that pins `1.0 -> 1.1`.

The rules went to **commonMain** as a pure `metricRecommendations(metrics, prefs, rh)`
(`advisor/AdvisorMetricRules.kt`), not into the Android-only service, because everything they need
(`AdvisorMetrics`, `AimiPrefsSnapshot`, `AimiRecommendation`, `ApsStrings`, `DoubleKey`) is already
multiplatform. They are therefore unit-testable without a pump, a database or Android, which is how
17 threshold tests exist at all. The shared recommendation card went to commonMain for the same
reason and compiles for iOS.

Three things were deduplicated or fixed while the code was open, each of which was live before this
lot:

- **One recommendation card, not two.** `PkpdAdvisorSuggestionCard` already existed and was live on
  the PKPD Setup screen; rather than write a second card, it was lifted to a shared
  `AimiRecommendationCard` that both screens call. Its new parameters (`showPriority`, `applyLabel`)
  default to the PKPD screen's old behaviour, so that live screen renders exactly as before - checked
  against the deleted card body, not assumed.
- **`applyPkpdPreferenceUpdate` silently ignored two key types.** It handled Double/Int/Boolean/String
  and returned `false` for anything else. `LongPreferenceKey` was simply missing, and
  `UnitDoublePreferenceKey` does **not** extend `DoubleNonPreferenceKey`, so an `is DoublePreferenceKey`
  test misses it even though its value is a `Double` - an apply on such a key would have looked like a
  no-op with no error. Both branches added. Latent today (no current recommendation proposes those
  types), which is exactly why a build would never have caught it.
- **The advisor history logged the literal string `"OLD"`** as the previous value. Not cosmetic:
  `AiCoachingService.kt:261` feeds the history into the LLM prompt as `"<old> -> <new>"`, so the AI
  Coach was being told `"OLD -> 0.75"`. A new `readPreferenceValueAsString` reads the real value
  before the write.

The review caught one more thing worth recording: the two action `reason` strings were written as
hardcoded English literals, while the sibling `PkpdAdvisor` in the same folder resolves its reasons
through `TextResolver`. That text is user-visible (the confirm dialog) **and** is what gets stored in
the history the AI coach reads, so it is now resolved through the same `TextResolver`, with the
nullable-resolver English fallback this file already uses for its score labels. Six dead
`aimi_adv_rec_*_action_*` strings were deleted in passing - they belonged to an older model where one
recommendation listed three textual suggestions, and that model is gone (checked against the staged
Activity too, since sub-lots 3-5 still read it). Eleven more base-English strings that were actually
French were rewritten, the same bug class 6p fixed for `aimi_adv_error_prefix`.

Kept deliberately: the confirm dialog. Apply writes preference keys the dosing engine reads on the
next loop tick (`OApsAIMIMaxSMB`, `OApsAIMILunchFactor`, the PKPD/relief/MaxIOB keys), so it is a real
therapy-parameter change and never becomes a one-tap button. Nothing here writes a therapy-event note,
so there is no `therapy.kt` substring-matching risk.

Verified independently, not only from the agents' self-reports: `:app:assembleFullDebug` EXIT=0 with
0 Kotlin errors, `:plugins:aps:compileKotlinIosArm64` EXIT=0, `:plugins:aps:testAndroidHostTest`
**542 tests, 0 failures** (514 before this lot: +17 threshold tests, +8 apply/read tests, +3 for the
resolved reasons). The test task was re-run with `--rerun` rather than trusted as UP-TO-DATE. The
staged `AimiProfileAdvisorActivity.kt` is still NOT deleted - 3 sub-lots still read it.

---

## 6r. 2026-09-15: sub-lot 3/5 - the T3c / Harmonia / RBT runtime-history cards

The three read-only diagnostic cards are ported: the recursive-belief unfold card, the T3c 24h
runtime history and the Harmonia 24h runtime history, appended to the same
`AimiProfileAdvisorScreen.kt` between the Tuning Context card and the metrics grid, in the order the
original inserted them.

This is the first lot in the series where the survey's verdict was **"the data is alive"** rather
than "the backend is dead". Worth recording, because the check is only useful if it can come back
either way: the decisions JSONL is written on every loop tick, and the preferences that gate it
(`OApsAIMIRecursiveBeliefShadow`, `...Authority`, both depending on `OApsAIMIautoDriveActive`) all
default to true, so these cards render real data on a default install. What had no consumer was the
*aggregation* - `summarizeLast24Hours` on both readers had zero callers, because its only consumer
was the parked Activity. The live Control Center screen shows the same two subsystems but only for
the latest tick; this is the 24h aggregate, so it was ported rather than dropped as a duplicate.

Four decisions were put to the user before any code was written, all resolved the recommended way:
port all three cards; keep the RBT detail as a raw pretty-printed JSON dialog rather than building
structured UI (the typed `RecursiveBeliefExport` model exists in commonMain but is **write-only** -
there is a `toJsonObject` and no decoder, and none of the classes are `@Serializable`, so structured
UI would have meant changing a model the live dosing path writes, for a developer diagnostic); give
the three loads their own deferred load; and add tests for the summarisers, which had none.

The staged code's `org.json.JSONObject` was stale in the way this series keeps finding: the writer
already produces a `kotlinx.serialization.json.JsonObject` and both live readers already parse with
`Json.parseToJsonElement` plus the `OrgJsonCompat` helper, so the loader was rewritten to kotlinx
rather than ported as-is. `org.json` is Android-only, and this branch is going multiplatform.

Three new strings were needed even though the survey said none would be, and the reason is worth
knowing for the remaining sub-lots: the staged code built three pieces of user-visible text **by
joining strings in Kotlin** (`"$avg U/h ($min-$max)"`, `"$from -> $to"`, and a bare English
`"unknown runtime blocker"` fallback), so there was no resource to find. A survey that greps for
`R.string.` cannot see text that was never a resource - when the staged source concatenates, expect
to add a template.

The review found the lot correct on every dosing-relevant property it was asked to check first
(format-argument order and type on every row, the four RBT field defaults against the real writer,
card placement and order, the null-vs-empty distinction, and the read-only guarantee), and found
three things worth fixing, all fixed directly:

- **The one genuinely new file had no tests.** The 18 tests the lot shipped all landed on the two
  pre-existing readers and on a pure percentage helper; `RecursiveBeliefExportReader` - the actual
  new code, with its own tail scan, its text pre-filter and its four defaults - had none. Seven tests
  added, covering the three different ways it can legitimately return null and, specifically, the two
  decoys its cheap `contains("recursive_belief")` filter lets through: a line that names the block in
  a note without carrying it, and a line that carries the block outside `adjustments`.
- **`runCatching` swallowed `CancellationException`.** Harmless here (the three wrapped calls are
  synchronous), but it is a pattern that gets copied. Replaced by a `loadOrNull` helper that rethrows
  cancellation and turns only real failures into "no data".
- **A test file named after the cards tested only a percentage helper**, which would have told the
  next reader the cards were covered. Renamed to say what it actually covers.

One cleanup in passing: six string ids named `aimi_t3c_history_*` are rendered by both cards, so a
reader editing one "T3c" string would silently change the Harmonia card too. Renamed to
`aimi_history_*`; each was referenced from exactly one Kotlin file, so the rename is contained.

Verified independently, not from the agents' reports: `:app:assembleFullDebug` EXIT=0 with 0 Kotlin
errors, `:plugins:aps:compileKotlinIosArm64` EXIT=0, `:plugins:aps:testAndroidHostTest` **567 tests,
0 failures, 0 errors** (542 before this lot: +18 from the lot, +7 from the review fix), re-run with
`--rerun` rather than trusted as UP-TO-DATE.

Two things this lot deliberately did not fix, recorded so they are not rediscovered as new:
the writer (`AimiStorageHelper.kt:73`) falls back to app-scoped storage when `Documents/AAPS` is not
writable while the reader (`T3cRuntimeHistoryReader.kt:132-134`) has no such fallback, so
"unavailable" can mean "cannot read the file", not "the loop is not exporting" - the card copy is
worded accordingly and carries a comment saying so. And the T3c reader treats `ownershipReason` as a
blocker name for non-blocked ticks, which reads oddly in the UI; that is pre-existing reader logic,
not something a port should change.

---

## 6s. 2026-09-15: sub-lots 4/5 and 5/5, and the end of the staged file

The last two sub-lots were run through the `superpowers:subagent-driven-development` skill at the
user's request, on top of the kickoff's own definer/coder/reviewer pipeline. What the skill added
that the previous lots did not have was a written ledger
(`.superpowers/sdd/NEXT_SESSION_KICKOFF/progress.md`): a pre-flight conflict scan of the remaining
tasks, every ruling with what it costs if wrong, and a completion line per task. Three of the skill's
own rules were overridden because project instructions outrank a skill, and each override is recorded
there rather than done silently: implementers do not commit (`CLAUDE.md`), genuine architectural
forks still go to the user instead of being ruled on (the kickoff says so explicitly, and it keeps
earning its keep), and a read-only definer runs before each implementer.

**Sub-lot 4 - brain, OREF and AI coach.** The user chose Vico for the CGM range chart over a
hand-drawn Compose alternative. That turned out to cost nothing in build terms: `:core:graph` already
exposes Vico as `api(...)` and `:plugins:aps` already depends on `:core:graph`, so no dependency was
added and no build file was touched. `AiCoachingService` needed real wiring - the staged bare
`AiCoachingService()` has not compiled since it gained `@Inject constructor(rh: ResourceHelper)` - so
it became an `OpenAPSAIMIPlugin` constructor field, the same pattern `tirCalculator` follows.
`OrefUserInsightFormatter.buildParagraph` had also changed from taking an Android `Context` to taking
a `TextResolver`, which is the KMP direction this whole branch is moving in.

Nineteen more base-English strings turned out to be French, and six of them are the ones most users
actually see: with no API key configured - the default for all four providers - the coach card never
calls the network at all, it falls back to `generatePlainTextAnalysis`, and that fallback was
entirely French. That is the third lot in a row to find this bug class.

**Sub-lot 5 - header, quick actions, footer, and a real bug.** The survey of the basal-proposal
dialog found a genuine defect in its producer, not in the staged UI:
`AimiAdvisorService.generateBasalProfileProposal` computed each hourly rate with
`profile.getBasal((hour * 3600).toLong())`. `Profile.getBasal(timestamp)` routes through
`MidnightUtils.secondsFromMidnight(timestamp)`, which expects **epoch milliseconds**, so every one of
the 24 hours landed inside the first 83 seconds of 1 January 1970 and returned the same midnight
block. A "basal proposal" would have shown 24 identical rows. The correct API was sitting next to it
the whole time: `Profile.getBasalTimeFromMidnight(timeAsSeconds: Int)`.

The same mistake was **already shipping** at two other sites in that file: `totalBasalCalc` summed 24
copies of the midnight rate into `AimiProfileSnapshot.totalBasal`, and `nightBasal = getBasal(0L)`
reads the wrong block in any timezone that is not UTC. Both feed the AI coach's prompt, so an LLM has
been advising the user from a wrong total basal. The user chose to fix all three sites in this lot
rather than defer, with a regression test that uses a genuine two-rate profile - under the bug the
total is 24.0, fixed it is 42.0, so the test cannot pass either way.

The model selector was the third place in the app to choose the same AI provider, after the settings
tree and the Meal Advisor screen. Rather than add a third copy of the widget, the Meal Advisor's
private `ProviderDropdown` was lifted into a shared composable both screens call, reusing the
existing localized provider labels instead of the staged hardcoded marketing names, which had already
gone stale. The staged `recreate()` - an Activity reload used to make the coach re-run with the new
provider - became a `selectedProvider` state value added to the coach effect's keys.

Two sections were dropped rather than ported, as decided earlier: the support-ZIP flow, superseded by
the live `AimiSupportPackageScreen`, and the behavior causal map. The header's support button went
with it, because this codebase has no mechanism for one registered Compose preference screen to
navigate into another - `ComposeScreenContent` only ever receives `onBack` - and inventing one for a
dropped feature would have been the wrong trade.

**Review found four things across the two lots**, all fixed: a KDoc that described the opposite of
what its code did; the basal-proposal failure message losing the exception detail, which was this
session's own brief mandating the `loadOrNull` helper that discards the throwable; prose left
hardcoded in the text the proposal shares out to a human, which was split line by line so the
`key=value` and CSV lines stay literal while the one real sentence became a resource; and a latent
trap now carrying a comment - the new `catch (Throwable)` is safe only while
`generateBasalProfileProposal` and `calculateMetrics` stay non-suspend, since each roots its own
`runBlocking` job.

One project rule was broken and is recorded rather than hidden: the sub-lot 4 implementer read Vico's
own library sources to find the `ColumnCartesianLayer` API. `CLAUDE.md` says not to read library
sources locally without asking first, and names Vico. The code is correct and the gates are green,
but the rule was not followed.

Verified independently at every step, never from an agent's self-report: `:app:assembleFullDebug`
EXIT=0 with 0 Kotlin errors (the gate that matters, since a missing Metro binding shows up only at
app-graph resolution), `:plugins:aps:compileKotlinIosArm64` EXIT=0, and
`:plugins:aps:testAndroidHostTest --rerun` **569 tests, 0 failures** (567 before these lots, +2 for
the basal regression test). Zero `build.gradle` files touched.

## 6t. 2026-09-15: the staged directory is empty of screens

`AimiProfileAdvisorActivity.kt` is deleted. Before deleting it, the definer took a full inventory of
all 2321 lines against what is live, function by function, and every one of them is either ported or
inside one of the two deliberately dropped sections. Two functions were dead even inside the staged
file and went with it: `getScoreColor`, defined and never called - evidence that colouring the score
by severity was intended and never wired up, which the Compose port now actually does - and
`Int.dpToPx()`, meaningless in Compose.

Four KDoc comments in live files referred to the Activity as "parked"; they now say "the former", so
a reader who greps for it is not left looking for a file that no longer exists.

**One staged file remains**: `orchestration/AimiLoopRuntimeGuard.kt` (16 lines). The standing
decision is to hold it rather than port it speculatively - it wraps a live telemetry method that
nothing calls yet - and to port it together with whatever feature ends up needing it. That decision
is unchanged and should be put to the user before it is revisited.

---

## 6u. 2026-09-17: the first real KMP lot, and why it moved one file instead of sixteen

With the Profile Advisor port finished, the next work is the KMP migration proper: 122 of the 478
AIMI files were still in `androidMain`. A grep for files importing none of `android.`, `androidx.`,
`java.io`, `java.util`, `java.text` or `org.json` gave 16 candidates that looked ready to move.

**Fourteen of the sixteen failed the iOS compile**, exactly as `kmp-module-flip` warns ("counting
files with no android/androidx/java import over-estimates badly... compile for iOS to find out").
The grep cannot see the two things that actually block this code: `app.aaps.plugins.aps.R` (no
android/java substring anywhere in that import) and a dependency on a *type* that is itself still in
`androidMain`. A fifteenth, `AndroidAimiBehaviorProfileSource`, compiled on its own and then failed
once its one dependency was reverted - coupling the grep also cannot see.

So exactly one file moved: `di/WCycleModule.kt`. Gates green (`:app:assembleFullDebug` 0 errors,
`compileKotlinIosArm64` EXIT=0, 569 tests / 0 failures).

**The useful output of this lot is the measurement, not the move.** The 351 compile errors rank the
real blockers, and they say the remaining `androidMain` AIMI code is not a list of independent files
but one connected cluster that has to move in dependency order:

| blocker | error count | what it is |
|---|---|---|
| `AimiBehaviorFamilyId` | 48 | a type still in androidMain, referenced across the behaviour cluster |
| `R` (`R.string`) | 22 | needs the `ApsStrings`/`TextRef` swap the skill describes |
| `AimiControlCenterDraft` | 12 | Control Center state, still androidMain |
| `StepService` | 11 | a genuine Android service - a platform port, not a move |
| `AuditorUIState` / `AuditorAIService` | 14 | auditor types, still androidMain |

The next lot should therefore be defined by *what everything else references*, not by what looks
unblocked: move `AimiBehaviorFamilyId` and the behaviour-family types first, then their readers, and
only then the analysers that use them. `StepService` is the opposite case - it is Android by nature
and wants an interface in commonMain with the service behind it, per the "lift the platform call out,
keep the rule" rule.

One fact worth recording for planning: `dev` and `kmp` contain **zero** `openAPSAIMI` files - the
whole 529-file AIMI tree exists only on this branch. So AIMI KMP work cannot conflict with the `dev`
catch-up, and the two can proceed independently.

---

## 6v. 2026-09-17: the AimiBehaviorFamilyId cluster, moved

Entry 6u measured the cluster; this one moves it. AIMI code in `androidMain` went from **121 files to
114**, and `commonMain` from 356 to 365 - the counts do not simply swap because one 787-line file was
split in two.

Run as two tranches with a checkpoint between them, because the seven steps are one dependency chain
with exactly one clean internal seam and every other boundary leaves unresolved references.

**Tranche 1 - the foundation.** `AimiBehaviorFamilyRegistry.kt` moved unchanged;
`AimiControlCenterSnapshot.kt` split along the IO-purity line (the pure model, the five `build*Family`
functions and the whole scoring tail to commonMain; the two `loadLatest*RuntimeSnapshot()` functions
and their five formatters stay in a new androidMain `AimiControlCenterRuntimeLoaders.kt`, because they
read files through `android.os.Environment`); `AimiControlCenterSupport.kt` moved with
`AimiAutonomyMode.labelResId()` replaced by a commonMain `controlCenterLabel(): TextRef`. About 24
label fields changed type from `@StringRes Int` to `TextRef`, and `AimiControlCenterScreen.kt` (1015
lines, stays androidMain) was updated in the same change to resolve `TextRef`, or the module would
have stopped compiling for Android too.

**Tranche 2 - the four files the whole lot was aiming at.** `AimiControlCenterAdvisor.kt`,
`AimiBehaviorCausalAnalyzer.kt`, `AimiBehaviorFamilyBridge.kt` and `AimiBehaviorRuntimeProfileReader.kt`,
plus `AndroidAimiBehaviorProfileSource.kt`, which failed 6u's attempt only because its one dependency
was still on the Android side and now compiles for iOS untouched. Its name is a misnomer now - it
holds nothing Android and its Metro binding contributes from commonMain, which is the target state -
but renaming was left out of a move-only lot.

Six dead fields were deleted rather than converted: `titleResId`, `bodyResId` and `bodyArgs` on both
`AimiBehaviorCausalInsight` and `AimiFamilyBridgeSuggestion`. They were written at ten call sites and
read nowhere - the one live consumer, `AiCoachingService.formatAimiBehaviorCausalInsightsForCoach`,
reads only `id`, `primaryFamily`, `secondaryFamilies`, `confidence` and `evidence`. Deleting them
removed 20 `R.string` references that would otherwise have been converted for nothing.

**What only the compiler found, again.** Three things the survey called pure moves were not:
`Map.putIfAbsent` is `java.util` and does not exist on Kotlin/Native (replaced by `getOrPut`, same
first-wins semantics); `String.format(Locale.US, ...)` in the snapshot's own formatter, which would
have compiled for Android and failed for iOS (replaced by the existing `aimiFmt1`/`aimiFmt2` helpers,
whose own KDoc says not to use `String.format` in commonMain); and two `private` helpers that had to
widen to `internal` once their callers moved to a separate file, since Kotlin's `private` does not
cross files even inside one module. That is now three lots in a row where `compileKotlinIosArm64` -
not a grep, not a survey - was the thing that told the truth.

**One correction made during verification.** The hoisted constant that keeps
`UnifiedActivityProviderMTR.MODE_DISABLED` (androidMain) and the commonMain comparison in step were
reported as leaving "exactly one literal", and there were two: the enum's own `entries` map, 40 lines
above, carried the same `"disabled"` string. Fixing it surfaced a Kotlin rule worth recording: a
constant an enum's **own entries** need cannot live in that enum's companion object
("Companion object of enum class is uninitialized here"), even as a `const val`. It went to a
top-level `const val` in the same file instead. There is now one definition, and the only other
`"disabled"` literals in the tree are unrelated telemetry reason strings.

Review found **zero defects**. The check that mattered - that no threshold, coefficient or comparison
changed in the moved scoring functions, which feed `UamHypothesisTuning` and the dosing algorithm's
heuristics - was done twice: read function by function against `git show HEAD:<old path>`, then by
extracting every numeric literal from both versions and diffing the sorted lists. Zero literal added,
zero lost.

Gates, verified independently at each tranche: `:plugins:aps:compileKotlinIosArm64` EXIT=0,
`:app:assembleFullDebug` 0 Kotlin errors, `:plugins:aps:testAndroidHostTest --rerun` **569 tests,
0 failures** - the same 569 as before the lot, which is the point: this lot moved code and changed no
behaviour.

**Next, by the same logic 6u established** - define the lot by what everything else references, not by
what looks unblocked. The remaining named clusters are the Auditor types
(`AuditorUIState`/`AuditorAIService`/`AuditorOrchestrator`, verified to have zero coupling with this
one), `StepService`, and the two JSONL runtime-history readers whose tick-record types keep the
Control Center loaders on the Android side.

**A safety note carried forward from the survey, for whoever ports `StepService` to iOS.** It is an
Android `SensorEventListener` step counter, and its output gates a live dosing branch
(`DetermineBasalAIMI2.kt:5909`, where `recentSteps30Minutes >= 500 || recentSteps180Minutes > 1500`
changes SMB behaviour). The honest iOS analogue is `CMPedometer`/HealthKit, which is asynchronous,
batched, and can lag by minutes. An iOS implementation that silently returns 0 when data is not fresh
would change the algorithm's behaviour rather than failing loudly. That decision belongs with whoever
owns dosing safety review, not with a KMP-move implementer.

---

## 6w. 2026-09-17: the whole-tree probe - a complete blocker map, and what it kills

6u measured one cluster by moving 16 files. This entry measures **all of them at once**, which turns
out to be the better technique and is worth reusing: `git mv` every remaining androidMain AIMI file
into commonMain, run `compileKotlinIosArm64` once, read the error ranking, then `git mv` everything
back. One compile, complete map, and the revert is exact because nothing but paths changed.

All 114 files moved; 6425 errors across 108 of them. Six had no *intrinsic* blocker - but note
carefully what that means: they were error-free **in a world where their dependencies had also moved**,
not error-free on their own. The probe measures intrinsic platform coupling, not movability.

**The finding that killed the obvious plan.** The tempting next step was a horizontal sweep - fix one
whole class of blocker across the tree and watch files fall out. Measured against the probe, that
plan yields nothing:

| sweep | files it would unblock on its own |
|---|---|
| `String.format`/`Locale` | 0 |
| resource strings (`R`/`getString`/`stringResource`) | 0 |
| both together | 0 |
| both plus `org.json` | 0 |

Every one of the 108 files has at least one blocker outside any single theme. The remaining AIMI code
is not one knot with a few threads; it is genuinely platform-coupled, file by file.

**The map, by how many files each blocker touches** (not by error count - error count over-weights a
single file that formats numbers in a loop):

| blocker | files | nature |
|---|---|---|
| `java` / `android` | 68 / 66 | the bulk, mostly the specific things below |
| `Context` | 46 | platform port, or an unused parameter - check before assuming |
| `System.currentTimeMillis` | 33 | mechanical, **done in this entry** |
| `File` + `exists`/`readText`/`writeText` | ~28 | wants one file-access port; the biggest structural item left |
| `R` / `getString` / `stringResource` / `res` | ~27 | the `ApsStrings`/`TextRef` swap |
| `@Volatile` / `TimeUnit` / `ReentrantLock` | ~18 | JVM concurrency, `AapsLock` is the house replacement |
| `JSONObject` / `json` | ~14 | `kotlinx.serialization`, as done for the RBT reader in 6r |

Distribution is long-tailed: 8 files have only 2 distinct blockers, and one has 65.

**Done in this entry:** `System.currentTimeMillis()` swept to `aimiWallClockMs()` across 31 files and
147 call sites, including one KDoc example. The helper already existed in commonMain
(`openAPSAIMI/AimiWallClock.kt`) and was already used by 47 files, so this is convergence on the house
pattern rather than a new one. Identical semantics - both return epoch milliseconds. Gates:
`:app:assembleFullDebug` 0 Kotlin errors, `testAndroidHostTest --rerun` 569 tests / 0 failures.

It unblocks no file on its own, and that is expected - it is on the path for 33 of them.

**What the next decision actually is.** Not "which files move next" but "what shape should the file
access port take". Around 28 files read or write files, and they are the largest coherent group left.
The project rule is that a port expresses intent rather than steps, and that a target which cannot
honour the contract should make the feature visibly absent rather than silently dead - which for
files-on-disk is a real question on iOS, not a formality. That is a design conversation to have before
any more code moves.

---

## 6x. 2026-09-17: four decisions about the storage port, and a cluster nobody had named

6w said the next question was the shape of a file access port. Measuring it first changed the
question twice, which is the point of this entry.

**The port already exists.** `AimiStorage` is in commonMain (`openAPSAIMI/utils/AimiStorage.kt`), 19
members, with `AndroidAimiStorage` as its Android half, and its KDoc shows the thinking was already
done: one storage policy at runtime, one health report, and every write wrapped so a failed AIMI log
line can never take down a dosing tick. Fourteen files use it. Thirty still call `java.io.File`
directly. So the work was never "design a port" - it was "finish adopting the one we have".

**Most of the measured gaps were grep artefacts.** The first pass said the contract was missing
`length()` in 14 files, streams in 15 and `bufferedReader` in 10. Checked against the code: half the
`length()` hits are `JSONArray.length()`, and **every single** stream and `bufferedReader` hit is an
`HttpURLConnection`, not a file. The real gaps are much smaller: `delete` (4 files), a tail read (2,
today via `RandomAccessFile`), a size for diagnostics or an is-it-empty test (~5), a directory listing
(2), a safe replace (3), a backup copy (1).

**A cluster nobody had named.** Those stream hits are 8 files doing HTTP: the four vision providers,
the AI coach, the auditor AI service, the Gemini model resolver and the physio analyzer, all on
`HttpURLConnection`. That is a separate port with a separate answer (Ktor, for a KMP target) and it
must not be absorbed into the storage work by accident. Also separate: `AimiModelHandler`'s
`FileChannel.map` of the TFLite model is not storage but "load a model", and TFLite is Android-only
regardless.

### The four decisions

1. **iOS must eventually run AIMI dosing.** This is the one that commands the others. It means every
   port needs a real iOS half, and a missing one is a safety matter rather than a todo: the decisions
   JSONL and the ML model stores feed the algorithm, so an iOS build that silently read nothing would
   dose differently instead of failing loudly. It also makes the current state a known gap -
   `AimiStorage` has no iOS binding at all, while 14 files already depend on it.
2. **Extend the contract by intent, not by mechanism.** `readTailLines(path, maxLines)` rather than
   random access; bytes rather than streams; no JVM stream types in the shared contract. More work
   than retyping call sites, and the reason is decision 1: a mechanism-shaped contract is one an iOS
   half can only imitate badly.
3. **One dedicated sweep of the 30 stragglers, before any more file moves.** The measurement in 6w
   says this unblocks no file on its own - no remaining file is blocked by storage alone - but it
   removes a whole blocker class from the map in one reviewable change instead of scattering it.
4. **A safe replace is one intent method, not three steps.** Three files today write a `.tmp`, delete
   a `.bak` and rename, including `AimiNeuralModelStore`, which holds a model the dosing algorithm
   loads on the next tick. The port offers replace-or-keep-the-old as a single call, with the dance
   inside each platform half, so no caller owns a three-step protocol it can get wrong and iOS can use
   its own atomic primitive rather than imitating Android's steps.

---

## 6y. 2026-09-17: the AimiStorage contract, extended by intent

Six members added, one designed and then removed. The contract is at 24 members and the Android half
implements all of them; there is still deliberately no iOS half (see below). Gates:
`compileKotlinIosArm64` EXIT=0 - which is the meaningful one here, since the interface lives in
commonMain and that compile is what proves no JVM type leaked into it - `:app:assembleFullDebug` 0
Kotlin errors, `testAndroidHostTest --rerun` **583 tests, 0 failures** (569 baseline + 14 new).

What was added, each named for what the caller means rather than what the platform does:
`delete`, `replaceText`, `readTailLines(path, maxLines)`, `sizeBytes`, `copy`, `lastModifiedMs`.

**`replaceText` is the one with teeth.** Three callers today are supposed to replace a stored file
safely, and one of them (`ml/AimiNeuralModelStore`) holds a model the dosing algorithm loads on the
next tick, so a half-written file is a real hazard. The `.tmp`-then-rename now lives inside the
Android implementation and the contract states the guarantee: on `false`, the previous content is
still readable. The test proves it by making the parent directory non-writable so the `.tmp` can never
be created, then asserting the target is untouched - a guarantee with no test behind it is a claim.

**A correction to this session's own brief, found by reading the callers.** The brief asserted that
three callers do a `.tmp`/`.bak`/rename dance today. Only `AimiNeuralModelStore` does.
`AutodriveDataBackfiller` does tmp-then-rename with a copy fallback and no `.bak`, and
**`TpoPersistence` writes directly with no temporary file and no atomicity at all**. So the sweep that
moves these onto `replaceText` will not merely preserve behaviour for that third one - it will give it
crash safety it has never had. Worth knowing before the sweep, because "no behaviour change" is the
usual promise of a sweep and here it would be false in a good way.

**`list` was built, then removed.** The brief asked for it and named two callers. The implementer
built it, could not find a caller it actually fitted, and said so. Checking that: the only candidate is
`AimiStorageHelper.listBackupCandidates`, which is recursive and filters by extension and size, so a
non-recursive files-only listing cannot serve it - and that helper is the Android storage policy
itself, which has no reason to move to commonMain at all. So the member had no caller today and no
foreseeable one, which is exactly what `CLAUDE.md` forbids shipping. Removed from the interface, the
implementation and its two tests.

**`copy` survived the same test, the other way.** The implementer also reported it had no caller,
because the brief named `AimiBackupManager`, which reads bytes and uploads them rather than copying to
a second path. Searching wider found two real ones the brief had missed:
`DetermineBasalAIMI2.kt:13399` backing up the training CSV, and `AutodriveDataBackfiller.kt:227`'s copy
fallback. The member stays; the brief was wrong about where, not about whether.

**`JsonlTailReader` was kept, not absorbed.** `readTailLines` delegates to it rather than inlining its
tuned reverse scan (8 KB chunks, a 16 MB cap that exists for Advisor launch on 256 MB heaps), because
three readers still call it directly and converting them belongs to the sweep. Absorbing it now would
have meant either duplicating that logic or breaking those readers.

**Why there is still no iOS half, despite the decision that iOS must eventually dose.** The
interface's own KDoc already says the absence is deliberate, and the reasoning holds: a stub that
quietly wrote nowhere would leave the learning loops looking alive while they persisted nothing, and
without a binding the feature is visibly absent and any future iOS graph fails loudly at wiring time.
Writing the half now would overturn that with code, when what it actually needs is a **storage policy**
answer: where AIMI may write on iOS, whether the user can retrieve those files, and whether the
support ZIP still works there. That is the next decision to put to the user, and it is a product
question, not an API mapping.

---

## 6z. 2026-09-17: storage sweep, batch A - the readers, and a real bug fixed on the way

Five of the six targeted readers now reach the disk through `AimiStorage` instead of `java.io.File`:
`T3cRuntimeHistoryReader`, `HarmoniaRuntimeHistoryReader`, `RecursiveBeliefExportReader`,
`AimiControlCenterRuntimeLoaders` and `ComparisonCsvParser`, plus the call sites that had to learn to
pass the port - `AimiControlCenterScreen`, `AimiProfileAdvisorScreen`, `AimiSupportPackageExporter`
and three construction sites in `OpenAPSAIMIPlugin`. Twelve files in all, then three more for the fix
described below. Gates: `compileKotlinIosArm64` EXIT=0, `:app:assembleFullDebug` 0 Kotlin errors,
`testAndroidHostTest --rerun` **585 tests, 0 failures**.

**The readers stayed `object`s.** Converting them to injected classes would have rippled into every
call site including two Compose screens, which is a refactor wearing a sweep's clothes. Instead the
port is a parameter, replacing the `file: File = aimiDecisionsJsonlFile()` they already carried:
`summarizeLast24Hours(storage, nowMs)`. Their tests changed only in how the fixture reaches them, not
in what they assert.

**A production bug is fixed, deliberately.** `aimiDecisionsJsonlFile()` resolved the decisions journal
with `Environment.getExternalStoragePublicDirectory` and no fallback, while the writer
(`DetermineBasalAIMI2`, through `AimiStorageHelper`) falls back to app-scoped storage when
`Documents/AAPS` is not writable. On any device where that fallback had happened, **the readers were
blind to what the loop was writing** - the Control Center, the Advisor's history cards and the support
package all silently saw nothing while the journal filled up elsewhere. Both sides now resolve through
`storage.file("AIMI_Decisions.jsonl")`, which is the same call the writer already made. This was known
as a hypothesis since 6r ("do not treat unavailable as proof the feature is dead"); it is now closed.

**A regression the sweep introduced, caught in review.** Converting
`AimiSupportPackageExporter.addDecisionLogLast24h` replaced a line-by-line `BufferedReader` walk with
`storage.readLines(path)`, which holds the whole file. That journal gains a line every loop tick and
is never truncated, and the T3c reader carries a 16 MB scan cap whose comment says it exists for
256 MB heaps - so the support export would have risked running the heap out on exactly the device
whose problem it exists to capture. The port gained `forEachLine(path) { }`: walk the lines without
ever holding them, answering `false` when the walk stopped early, so a caller can tell a truncated
file from a complete one. The exporter uses it, with a comment saying why. Batch B will want it too -
the training CSVs have the same shape.

**`AimiNeuralNetworkFiles` was deferred, correctly.** Its only caller is `ml/AimiNeuralModelStore`, an
`object` with no DI whose own callers are two more `ml/*` objects. Giving it the port means either an
object-to-class conversion one hop further out, or a late-init singleton bolted on for one call. It is
a writer, so it belongs to batch B anyway, where that knot can be untied deliberately rather than as a
side effect.

**One process note.** This lot cost a round trip because the brief said "do not touch" about files it
only meant "do not convert in this batch". The implementer read it absolutely, correctly refused to
edit a call site it believed was frozen, and delivered one file with a clear explanation rather than
guessing - which is the behaviour you want. The wording was the defect, not the reading.

---

## 6aa. 2026-09-17: storage sweep, batch B1 - and `forEachLine` pays for itself a second time

Five writers and stores converted from `AimiStorageHelper`/`java.io.File` to the `AimiStorage` port:
`tpo/TpoPersistence` (plus its one call site in `TpoOrchestrator`),
`learning/BasalMlTrainingCoordinator`, `learning/BasalLearner`, `learning/UnifiedReactivityLearner`
and `autodrive/learning/AutodriveDataLake`. Gates: `compileKotlinIosArm64` EXIT=0,
`:app:assembleFullDebug` 0 Kotlin errors, `testAndroidHostTest --rerun` **585 tests, 0 failures** -
the same 585, which is what a sweep should produce.

Each converted class came out holding the port and **not** the helper, which is the direction
`DetermineBasalAIMI2.kt:1458` already documented: `AimiStorage` is the seam, `AimiStorageHelper` is
transitional.

**`Context` was dead weight in both learners.** The brief asked whether the `Context` those two take
was only there to resolve a file path, in which case the conversion would remove it. It was not used
*at all* - zero references in either class, apparently left from before `AimiStorageHelper` existed.
Both parameters are gone. That is two files off the `Context` blocker list (46 files at the last
count) for no work, and a reminder that this migration's biggest blocker is partly an illusion: some
of those 46 may not use the thing they hold either. Worth checking before designing a port for them.

**A second unbounded read, this one pre-existing.** `BasalMlDatasetParser.parse()` inside
`BasalMlTrainingCoordinator` read `basal_adaptive_records.csv` with `readLines()` - a training CSV
that gains a row every loop tick and is never truncated. Unlike batch A's, this one was **not
introduced by a sweep**; it has been there. Rewritten to `readFirstLine` for the header plus
`forEachLine` for the rows, with a line count reproducing the old "fewer than two lines gives null"
short circuit exactly. So the member added in 6z has now caught two out-of-memory risks in two lots,
which is a good sign the intent-shaped read was the right call rather than an over-design.

**`TpoPersistence` is atomic now.** All three save paths (`saveSession`, `saveLedger`,
`saveLastRevertAtMsByPack`) go through `storage.replaceText`, so a crash or a full disk mid-write
leaves the previous session, ledger or meta readable instead of truncated. It had none of that
before - a plain `writeText`. This is the deliberate behaviour change of the lot.

**One bridge, documented, and temporary.** `BasalMlTrainingCoordinator` hands its two weight files to
`NeuralModelTrainer.trainAndPublish(weightsFile: File, ...)`, which lives in the `ml/*` chain that
batch B2 has not untangled yet. Rather than change an out-of-scope signature, the file keeps
`AimiPath` as its own source of truth and converts to a `File` on that one call line, with a comment
saying why. B2 removes it.

`AutodriveDataLake`'s carried-forward rows used to be several `FileWriter.append()` calls inside one
open handle and are now one `appendText` of the concatenated string - same bytes, same order. Noted
only because it sits next to crash-safety code: `appendText` is not atomic and was not made so.

---

## 6ab. 2026-09-18: storage sweep, batch B2 - the ml chain, and the sweep is done

The `ml/*` chain is on the port. With batches A, B1 and B2 together, **AIMI files calling
`java.io.File` directly went from 31 to 14**, and every one of the 14 that remains is there on
purpose: the storage layer itself (`AndroidAimiStorage`, `AimiStorageHelper`, `JsonlTailReader`), TFLite
model loading, two Compose screens, the SAF backup manager, and the deeply-Android exporters and
physio store. Gates: `compileKotlinIosArm64` EXIT=0, `:app:assembleFullDebug` 0 Kotlin errors,
`testAndroidHostTest --rerun` **588 tests, 0 failures** (585 + 3 for the new member).

**`replaceKeepingBackup` was added, reversing an earlier decision on new information.** When the
contract was designed, a backup-keeping replace was declined as an escape hatch that would become the
default path - a good rule, decided while the alternative was hypothetical. Reading
`AimiNeuralModelStore` made it concrete: its `.bak` is not a convenience, it is the **rollback**. The
load path tries the target and then the `.bak`, and `delete` deliberately removes both so a model
judged dead cannot be resurrected by the next load. Converting that file with plain `replaceText`
would have deleted a safety mechanism on a model the dosing algorithm runs. The user was asked again,
with the protocol in front of them, and chose to add the member. The Android implementation mirrors
the old hand-rolled sequence line for line, including what happens when the final rename fails, and
three tests pin it.

**The hazard this batch exposed, worth carrying to any future port work.** `AimiNeuralNetworkFiles.saveToFile`
returned `Unit` and signalled failure by throwing. `AimiStorage` writes never throw - they answer
`false`, by deliberate design, so an AIMI log line can never take down a dosing tick. Converting the
function without noticing would have left `OrefPersonalMlTrainer`'s "write failure → `TRAIN_FAILED`"
branch **silently unreachable**: training would have reported success while persisting nothing. The
signature became `Boolean` and the caller now checks it. The general shape: **when converting code
that detected failure by catching an exception, the port's non-throwing contract silently deletes that
detection unless the return value is checked.** Nothing about that fails a build.

A third unbounded read was converted on the way - `AimiSmbTrainer.trainNow` was reading
`oapsaimiML2_records.csv` whole, a file that gains a row every loop tick. That is three
out-of-memory risks this sweep has found, one introduced by itself and two pre-existing, all now on
`forEachLine`.

The bridge B1 left in `BasalMlTrainingCoordinator` is gone, as planned. Threading the port went deeper
than the eight-file list suggested - the OREF advisor path needed a nullable `storage` parameter on
`AimiAdvisorService` and `OrefLocalPipeline` - but nothing was converted to an injected class, so the
house pattern held.

One diagnostics-only change: `NeuralModelTrainer`'s log lines now print `storage.displayPath(...)`
rather than a bare filename, because an `AimiPath` may not be taken apart from outside the Android
half. Log text only, no protocol change.

**Left for a later lot, with a real caller.** `BasalMlModelStore` in commonMain is load-only and its
KDoc explains why: the write side needs directory creation, rename and delete, *"and the AIMI storage
seam offers none of the three"*. It now offers all three. No `save` was added, because nothing would
call it yet and this project does not ship an API with no consumer - but the stated blocker is gone
and that KDoc is stale. A lot that brings a real writer should add the save and fix the comment in the
same change.

---

## 6ac. 2026-09-18: eleven files cross into commonMain - what the three sweeps bought

The storage, clock and behaviour-family work had moved almost nothing on its own, by design: 6w
measured that no remaining file was blocked by a single class of problem, so each sweep was a
payment towards a move rather than a move. This entry collects the change.

Re-running 6w's whole-tree probe - `git mv` every remaining androidMain AIMI file into commonMain,
compile for iOS once, read the ranking, revert - shows **files with no intrinsic platform blocker went
from 6 to 16**. That number is the sweeps' receipt.

Then the second question, which the probe cannot answer on its own: of those 16, which compile when
only *they* move, rather than when everything moves? Reverting the failures and iterating took one
round. **Eleven files now live in commonMain**: `AimiNeuralNetworkFiles`, the three runtime-history
readers (`T3cRuntimeHistoryReader`, `HarmoniaRuntimeHistoryReader`, `RecursiveBeliefExportReader`),
`AimiControlCenterRuntimeLoaders`, `ComparisonCsvParser`, `TpoPersistence`, and the four `ml/*`
persistence files (`AimiNeuralModelStore`, `AimiSmbModelStore`, `NeuralModelTrainer`,
`TrainingCsvHeaderFile`).

AIMI is now **103 files in androidMain against 376 in commonMain**, from 121/356 when this series of
lots began.

Five of the 16 did not survive alone - `AimiClinicalReportEngine`, `AimiAdaptationStatusBuilder`,
`AimiDetermineBasalTickOrchestrator` and the two step providers - each waiting on a collaborator that
has not moved (`AIMIPhysioManagerMTR`, `BasalLearner`, `UnifiedReactivityLearner`). They are queued
behind a name, not behind a platform, which is a much better place to be.

Gates: `compileKotlinIosArm64` EXIT=0, `:app:assembleFullDebug` 0 Kotlin errors,
`testAndroidHostTest --rerun` **588 tests, 0 failures** - unchanged, as a move should leave them.

**One comment corrected.** `AimiControlCenterRuntimeLoaders.kt` was created in 6v to hold the part of
the Control Center snapshot that had to stay on Android, and its header said exactly that. The file
has now moved to commonMain itself, so the sentence had become false. The split no longer separates
platform from model and is kept only because the grouping reads well - which is what the header says
now. A comment that survives the reason it describes is worse than none.

**A note on method, since it has now been decided twice by measurement rather than by argument.** The
crude approach - grep for files that import nothing Android and move those - has produced a wrong
answer every time it has been tried: 14 of 16 failed in 6u, and an attempt this session to find
unused `Context` parameters by regex flagged files that plainly use theirs on the next line. The
probe works because it asks the compiler, and it costs one compile. Prefer it.

---

## 6ad. 2026-09-18: the two learners, and a Buddhist-calendar bug fixed on the way

`BasalLearner` and `UnifiedReactivityLearner` were the last two files standing in front of a queue,
and their only remaining blockers were JVM concurrency primitives and `java.util.Calendar`. Both are
now converted, and **three more files crossed into commonMain**: the two learners and
`AimiAdaptationStatusBuilder`, which was waiting on both. AIMI is at **100 androidMain / 379
commonMain**. Gates: `compileKotlinIosArm64` EXIT=0, `:app:assembleFullDebug` 0 Kotlin errors,
`testAndroidHostTest --rerun` **589 tests, 0 failures** (588 + 1 new).

**The conversion was chosen per field, not applied as a pattern**, because the wrong choice here is
silent. Several fields were `AtomicReference` snapshots read on the dosing path; replacing those with
a lock would let a dosing tick block behind a background refresh. The house answer was already in the
module - `advisor/auditor/AuditorVerdictCache.kt` publishes with `@Volatile` from `kotlin.concurrent`
and reserves `AapsLock` for what genuinely needs mutual exclusion - so:

- the `AtomicReference<List<...>>` snapshots became `@Volatile`, keeping reads lock-free;
- the `AtomicBoolean` "refresh in flight" guards became `AapsLock` plus a `@Volatile` flag, tested and
  set inside one `withLock`, copied from the same shape already in `KalmanFilter.kt`;
- the `AtomicLong` counters became `@Volatile` with a plain increment, on evidence rather than
  assumption: `LoopPlugin.invoke()` holds `invokeMutex` around the whole tick with the comment
  "serialize loop runs so they cannot overlap", and the only writers are inside that call chain.

The single-writer claim is the one that would have been easy to assert and wrong. It was established
by reading the Loop's own serialization, which is the right kind of evidence for it.

**The timezone hazard held.** Both `Calendar.getInstance().get(HOUR_OF_DAY)` calls read the *device's*
zone implicitly, and this learner buckets its factors by hour, so `TimeZone.UTC` in the replacement
would have shifted every bucket - a dosing change disguised as a date-library swap. Both use
`TimeZone.currentSystemDefault()`.

**And a latent bug went with it.** Moving the file surfaced a `SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
Locale.getDefault())` writing the CSV timestamp. The module already has `aimiCsvTimestamp()`, whose
KDoc explains precisely why it exists: a locale-defaulted pattern writes *that locale's calendar*, and
"a Thai phone wrote Buddhist years into wire timestamps". The old call used `Locale.getDefault()`, so
this learner's CSV had the same defect. Swapped to the helper, with a comment recording what it fixed.

**What the queue is actually waiting on now** - all three are structural, not incidental:

| file | blocked by | nature |
|---|---|---|
| `AimiDetermineBasalTickOrchestrator` | `DetermineBasalaimiSMB2` | the algorithm core; the endgame, not a lot |
| `AimiClinicalReportEngine` | `AIMIPhysioManagerMTR` | `WorkManager` - a scheduling port, not a move |
| the two step providers | `StepService` | an Android `SensorEventListener`; the port was deferred in 6v with a dosing-safety flag still open |

A note on method, again: these two files' `SimpleDateFormat` did **not** appear in the whole-tree
probe's blocker list. It was masked - the file failed earlier on its atomics, and the compiler never
got far enough to complain about the date formatter. **A probe ranks the blockers it can see, and
removing one can reveal another underneath.** Expect the remaining counts to grow slightly as layers
come off, rather than falling monotonically.

---

## 6ae. 2026-09-20: the branch is measured against live AIMI, and the gap is mostly tests

Three days of work landed on `dev_OAPSAIMI` while this branch moved files between source sets, so the
first job was measuring how far apart they are. The headline number is misleading and the real one is
more useful.

**532 AIMI files here against 762 on `dev_OAPSAIMI`** - 299 present there and missing here. But
**260 of those 299 are tests**. Only **39 are production files**, and roughly fourteen of those are
deliberately absent: the Activities replaced by Compose, `ContextViewModel` dropped as dead in 6m,
`AimiLoopRuntimeGuard` held on purpose. So the production gap is about **25 real files** - the
`retention/` package (9 files, new), the new ISF work (`HeartRateTrendIsf`, `StressIsfFloor`),
`FclMealBasal`, `AnticipationBasalFloor`, `TpoRevertPolicy`, `RiseCeilingGuard` and a handful more.

**The test gap is the serious one: 52 AIMI test files here against 294 there.** This migration has
been moving and rewriting dosing code with under a fifth of the coverage the live fork has.

Of the 260 missing tests, **154 have their subject already present on this branch** - they can be
ported now, without porting any feature first. 119 of those 154 use no MockK; this module is wired for
Mockito, so the other 35 need a dependency decision before they can land.

### The pilot, and what it says about the migration

Thirteen non-MockK `pkpd` tests were copied from `dev_OAPSAIMI:plugins/aps/src/test/kotlin` into
`androidHostTest` (the KMP equivalent per the `kmp-module-flip` skill).

**Twelve compiled unchanged and all passed** - 589 tests became 643, zero failures. That is the useful
result: those twelve engine files have **not** drifted. Everything those tests assert about the
migrated code still holds, which is the first real evidence that the port preserved behaviour rather
than merely preserving compilation.

**One failed to compile, and it is a finding rather than a nuisance.** `IsfFusionTest` calls
`IsfFusion.fused(..., nowMs = ..., authoritative = ...)`. This branch's `IsfFusion` has neither
parameter: production gained a time argument and an authority flag that this branch's copy does not
have. **This branch is running an older ISF fusion engine than production.**

So porting these tests is not only coverage. It is an **audit of the drift**: a test that compiles and
passes says its engine file is faithful, and a test that refuses to compile names a file that is
behind and says exactly how. That makes the remaining 141 a measuring instrument as much as a safety
net, and it is the cheapest way to find out which of this branch's engine files are stale.

`IsfFusionTest` is parked at `.superpowers/sdd/.../IsfFusionTest.kt.deferred` until `IsfFusion` is
brought up to date, so the finding is not lost.

---

## 6af. 2026-09-20: 104 tests ported, and the audit found two real ISF regressions

The pilot in 6ae said porting `dev_OAPSAIMI`'s tests would be both coverage and an audit of the drift.
Scaled to all 119 non-MockK portable tests, it was both, and the audit part paid first.

**The suite went from 589 to 1151 tests, 0 failures.** 104 of the 107 copied compiled unchanged, which
is itself the headline result: the migrated engine agrees with production everywhere those tests look.

### Two regressions this branch had introduced, found by running production's own tests

`IsfBlender` and `IsfAdjustmentEngine` both returned **50.0** where production returns 75.0 and 71.0 -
an ISF a third lower, which is a third more aggressive on every correction that uses it.

Same cause in both, and it is a porting artefact rather than a missing feature. Production keeps one
nullable object:

```kotlin
private data class Anchor(val isf: Double, val tsMs: Long)
/** No anchor yet (first call of the process): the target is returned as is. */
private fun rateLimit(target: Double, nowMs: Long): Double {
    val a = anchor ?: return target
```

This branch had split that into two nullable fields, `lastIsf` and `lastTsMs`, and the early return
went with it. On the first call `elapsedMs` came out as zero, so the hourly budget was zero, so the
limiter clamped the result to the fallback value - discarding the Kalman contribution entirely. The
failing tests are named `first blend is not rate limited` and `first adjustment is not rate limited`,
so the intent was written down; only the code had lost it.

Both are restored to the anchor form, with a comment saying why one nullable object and not two. **The
design lesson is worth more than the fix: splitting a two-field invariant into two independent
nullables removes the compiler's ability to make you handle the "neither is set yet" case.** It reads
like a harmless refactor and it is not.

### What the 11 parked tests mean

Each one names something, and they are kept in `_docs/kmp/deferred-tests/` rather than deleted:

- **Migration artefacts** - the test uses a JVM type this branch deliberately replaced:
  `NightGrowthResistanceMonitorTest` (`java.time.LocalTime`/`ZoneId` vs `kotlinx.datetime`),
  `aimiNeuralNetworkTest` and `ComparisonCsvParserTest` (`File` vs `AimiStorage`/`AimiPath`). These
  need their types swapped and then they should pass.
- **Real engine drift** - the test names something this branch does not have:
  `UndeclaredCobEstimatorTest` wants `HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN`, from Grok's recent
  heart-rate gating work. `IsfFusionTest` wants `fused(..., nowMs, authoritative)`: production's ISF
  fusion gained a time argument and an authority flag that this branch's copy does not have.
- The remaining six (`DoseTerminalSnapshotTest`, `InsulinStackingStanceTest`,
  `PostHypoDeliveryAuthorityTest`, `PredictionDivergenceAuditorTest`, `ReplayCorpusTest`,
  `SmbBindingTraceTest`) were parked by the same iterate-and-compile loop and have not been
  categorised yet - that is the next job, and each is either a type swap or a named missing feature.

### Still outstanding

35 portable tests use MockK and this module is wired for Mockito - a dependency decision, not work.
106 more tests need their feature ported first. And roughly 25 production files are genuinely missing,
the `retention/` package being the largest.

Gates: `:app:assembleFullDebug` 0 Kotlin errors (after the documented stale-KSP purge),
`compileKotlinIosArm64` EXIT=0, `testAndroidHostTest --rerun` **1151 tests, 0 failures**.

---

## 6ag. 2026-09-20: the seven artefacts adapted, and three of the four drifts closed

Continuing 6af. The eleven tests that would not compile split cleanly once the compiler's cascading
was accounted for - removing one broken file made others look broken, so the first categorisation
over-counted the drift.

**Seven were migration artefacts**, i.e. the test spoke a JVM API this branch had deliberately
replaced: `java.time` (1), `java.io.File` (2) and `org.json` (4). All seven now land, adapted only in
how they reach the code, with **no assertion touched**. None of them revealed drift: every production
class they name still had exactly the fields, types and constants they assumed. Suite 1151 -> 1191.

**Four were genuine engine drift.** Three are now closed, taking the suite to **1235 tests, 0
failures**:

- **`UndeclaredCobEstimator`** turned out to need only the missing constant
  (`HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN = 11.0`) and its documentation. Production's KDoc records a
  past-tense design change - the heart-rate gate used to stand down above that rise rate and now
  always fires - and this branch's gate was *already* unconditional. So the behaviour matched; only
  the name the test reaches for was absent.
- **`IsfFusion`** was the real one. Production turned the fixed one-tick slew limiter into a
  clock-driven budget: `fused(..., nowMs, authoritative)`, elapsed time clamped to two ticks scaling
  the allowed movement, downside slew 1.375x the upside, backward clock jumps freezing the value
  rather than ratcheting, and a re-stamp each call so a bad jump self-heals in one tick. Note this sits
  directly on top of the anchor regression fixed earlier the same day (6af) - same file, same
  structure, and production's newer version already carries the anchor form.
- **`InsulinStackingStance`** brought two behaviour changes: the IOB floor moves from a hard-coded
  `max(3.2, maxIob*0.26)` to `max(1.0, maxIob*0.26)` - production's KDoc cites a field report where a
  stress episode with 2 U on board got no stacking protection at all - and a new 70-130 mg/dL caution
  band that engages surveillance below the usual gate, but only when nothing says meal. That needed
  the new `mealModeActive` parameter, passed at all four call sites with the same expression
  production's own callers use.

### The fourth is a decision, not a task

`ReplaySummary` is ported and appears correct, but `ReplayCorpusTest` cannot be un-parked. The test
needs three bundled day fixtures that **this branch deliberately does not carry**: `ReplayCorpus`'s
KDoc says the day fixtures stay on `dev_OAPSAIMI`, and that boundary is not just a comment - an
already-committed test, `BarrierReplayTest.dayFixturesAreNotBundledOnTheStudyTree`, asserts that
loading them throws.

The implementer tried restoring them, saw all five `ReplayCorpusTest` methods pass with production's
exact figures, then noticed the full suite had gone red on that boundary test and **reverted the
whole attempt** rather than quietly reversing another engineer's tested decision. That is the right
instinct and worth recording as the behaviour to expect.

The numbers that decided it: the three fixtures are **149 + 152 + 172 KB**, against the single fixture
already bundled at **43 KB** - eleven times the embedded test data, as Kotlin string constants the
compiler must parse. Put to the user with those numbers, and **reversed deliberately**: see 6ah.

---

## 6ah. 2026-09-21: the replay day fixtures, restored on purpose

The boundary 6ag stopped at is now reversed, by the person whose call it was rather than by an agent
mid-task. All three day fixtures are bundled, `ReplayCorpusTest` is un-parked, and the suite is at
**1240 tests, 0 failures** (`compileKotlinIosArm64` and `compileTestKotlinIosSimulatorArm64` both
green - the second matters here, because the fixtures land in `commonTest` and large embedded string
constants are exactly the sort of thing Kotlin/Native can object to).

The fixtures produced production's figures bit for bit: 284 / 285 / 409 ticks, 27.46 U total SMB with
95.4% time in range on the in-range day, 56.76 U total with 10.96 U at `REBOUND_GUARD` on the rebound
day. That is a stronger statement than "the tests pass" - it says this branch's replay harness and
`ReplaySummary` reproduce the live fork's numbers exactly on three full days of real ticks.

**The boundary test was inverted, not deleted.** `dayFixturesAreNotBundledOnTheStudyTree` asserted
that loading a day fixture throws; it is now
`dayFixturesAreBundledAndCarryTheirExpectedTickCounts`, asserting 284/285/409, with a comment
recording that it used to check the opposite and why. Deleting it would have removed the only thing
watching whether the fixtures are present and parseable - the check still exists, it just checks the
new truth. `ReplayCorpus`'s KDoc was rewritten for the same reason, and now carries the sizes so that
whoever considers a fourth day fixture sees what the first three cost.

`ReplaySummary` therefore ships with a real consumer rather than none, which is what 6ag said was the
condition for shipping it at all.

**Worth keeping as a pattern.** The agent that hit this boundary had already made the change work -
five tests passing, production figures matching - and then found the full suite red on a committed
test asserting the opposite. It reverted its own working change and asked, rather than deleting the
test in its way. The cost was one round trip; the alternative was silently reversing a documented,
tested decision belonging to someone else. Expect and reward that.

---

---

## 7. Start here next session

The plugin is live: `:app:assembleFullDebug` builds with `OpenAPSAIMIPlugin` registered at
`@MetroIntKey(250)` and its whole reachable dependency closure compiling. All eight collaborator ports
now have exactly one implementation each. The AIMI Auditor now has a real Compose status chip on the
Overview screen, wired through a new `:core:interfaces` port (`PluginStatusBadgeSource`) rather than
its old View-based toolbar indicator. Staging is down to 2 files (was 17: six deleted in 6k as dead or
superseded, two permission screens ported in 6l, the Context cluster ported in 6m, Meal Advisor + its
camera screen ported in 6n, Mode Settings ported in 6o), `AimiProfileAdvisorActivity` is gone: all five of its sub-lots
are ported (6p, 6q, 6r, 6s) and the file is deleted (6t). Exactly one staged file remains,
`orchestration/AimiLoopRuntimeGuard.kt`, deliberately held.
Two `kmp` merges and a parallel P0.1-P0.7 porting series (done outside this session, with a Cursor
agent) have landed since 6i; see 6j for what they were and why neither touches these staged files.

1. **The Profile Advisor port is finished; the next decision is `AimiLoopRuntimeGuard`.** It is the
   last staged file (16 lines), and the standing decision is to hold it rather than port it
   speculatively: it wraps `AimiLoopTelemetry.isTickInProgress()`/`activeTickAgeMs()`, which nothing
   calls yet, and no Overview wiring exists for it. Port it together with whatever feature needs it.
   **Ask the user before changing that decision.** Once it is resolved one way or the other,
   `_docs/kmp/staging/` can be retired entirely - worth a final pass to confirm nothing else
   references it.
   Habits worth carrying into whatever comes next, each of which caught something real in this
   series: check that a section's backend still runs at all before porting its UI (6q found four
   rules no engine had emitted for six months); when the staged source **concatenates** user-visible
   text there is no `R.string.` to grep for, so expect to add a template (6r, 6s); check that a lot's
   new tests landed on its new code rather than on the pre-existing code around it (6r shipped 18
   tests, none on the one new reader); and when a survey turns up a defect in a *producer* rather
   than in the UI being ported, check whether that same defect is already shipping elsewhere (6s
   found the basal timestamp bug at three sites, two of them live and feeding the AI coach).
2. **Before moving `AimiLoopRuntimeGuard`, or anything from a future upstream merge, check for the recurring
   failure shapes from 6g through 6i, in order:** (a) a class implementing a port interface but missing
   `@ContributesBinding(AppScope::class)` - compiles fine alone, fails only at `:app:compileFullDebugKotlin`,
   so that has to be the gate, not `:plugins:aps:compileAndroidMain`; (b) `.titleResId`/`.descriptionResId`/
   `.summaryResId`/`.valueResId`/`.unitLabelResId`-shaped names on anything that used to carry a bare
   `Int` - almost always renamed to a `TextRef`-typed property, not gone; (c) a JVM-only primitive with a
   multiplatform replacement already in use everywhere else - `AtomicReference`/`synchronized` →
   `AapsLock`, `System.currentTimeMillis()` → `aimiWallClockMs()`, `String.format` → `aimiFmtN`,
   `java.time.*` → `kotlinx.datetime.*`, `javaClass.simpleName` → `.name`/`::class.simpleName`; (d) a
   duplicate top-level declaration between a staging leftover and an already-extracted `commonMain`
   file - diff before deleting, they have all matched (byte-for-byte, or differing only by (c)) every
   time so far. **This duplication can be by type, not by filename** - a monolithic staging file can
   carry several top-level declarations that were later split into differently-named files during the
   real port (`AIVisionProvider.kt`'s models ended up in `MealEstimateModels.kt`/`FoodAnalysisPrompt.kt`);
   a filename-only duplicate check misses this, only the compiler's "Redeclaration" error catches it,
   so move-and-compile still beats predicting the closure by filename; (e) a capability or resource
   genuinely dropped (not renamed) during the KMP rewrite - confirm on `dev_OAPSAIMI` before restoring,
   and prefer the smallest correct fix over guessing; (f) a backtick-quoted `commonTest` function name
   containing a comma - compiles fine on the JVM/Android host, fails only Kotlin/Native's symbol
   mangling, so `iosSimulatorArm64Test` (not `testAndroidHostTest`) is the gate that catches it. Found
   in 6j, in AIMI content on both sides (P0.4's own test and one from this branch's own `kmp` merge).
3. **`:app:assembleFullDebug` is now a required gate, not `:plugins:aps:compileAndroidMain` alone.**
   The module compile cannot see a missing Metro binding; only the app graph resolution catches it.
   Keep both in the loop, but if only one can run, run the app assemble.
4. **Keep the two-baseline numeric check on every lot that touches logic, not just moves files - and
   remember it also answers "is this staging file safe to delete", not only "is this edit safe".** A
   matching numeric-literal multiset was the signal that let 6i clear 221 files in one pass instead of
   hand-diffing each one; a pure `git mv` or `git rm` of an already-superseded file needs no re-diffing
   at all, since it cannot alter content anyone still depends on.

One process note, still holding from 6g: the pipeline of five agents (definer, designer, coder,
controller, committer) is for lots with a real architectural decision to make. This lot had exactly one
such decision - the array-based preferences with no backing API left in the tree - and it was put to
the user rather than guessed. Everything else (closing ~45 files' worth of dependency graph, five
Metro bindings, three dropped-capability restores) was mechanical move-compile-fix, done directly.
