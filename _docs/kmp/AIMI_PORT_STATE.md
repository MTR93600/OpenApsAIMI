# AIMI port - state of play, and where to start next

Updated 2026-09-02, on `kmp-aimi-migration-study` at `1f6ca62fe8` (the second `kmp` merge).
**Read this first.** It supersedes the stale parts of the older documents in this folder; each of
those is marked below with what to still trust it for.

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

## 7. Start here next session

The plugin is live: `:app:assembleFullDebug` builds with `OpenAPSAIMIPlugin` registered at
`@MetroIntKey(250)` and its whole reachable dependency closure compiling. All eight collaborator ports
now have exactly one implementation each. The AIMI Auditor now has a real Compose status chip on the
Overview screen, wired through a new `:core:interfaces` port (`PluginStatusBadgeSource`) rather than
its old View-based toolbar indicator. Staging is down to 17 files, all View-based Android Activities
(or their direct support classes) with no Compose equivalent yet - not 247, see 6i and its addenda.

1. **The 17 remaining staged files are all legacy View-based Android Activities or their support
   classes**, not AIMI's dosing logic - `AimiModeSettingsActivity`, `AimiProfileAdvisorActivity`,
   `ContextActivity`, `MealAdvisorActivity`/`MealAdvisorCameraActivity`, plus a handful of the smaller
   view models/adapters/permission Activities that go with them. The meal-photo vision pipeline and
   the Auditor's notification/status-badge cluster both moved this same day (see the two addenda right
   above); their Activities are the only pieces still parked. None of the 17 blocks what already runs.
   Porting an Activity at all is itself a design decision this codebase has been moving away from
   (Compose over View) - don't assume "port it as-is" is even the right call before asking, the same
   way `AuditorReportActivity` turned out not to need porting at all once its real dependency
   (`showOkDialog`) turned out to be gone rather than just unfound.
2. **Before moving any of those 17, or anything from a future upstream merge, check for the recurring
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
   and prefer the smallest correct fix over guessing.
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
