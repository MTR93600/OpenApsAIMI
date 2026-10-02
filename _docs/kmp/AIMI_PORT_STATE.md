# AIMI port - state of play, and where to start next

> **2026-09-19 — superseded as the live snapshot (post-P3.8).**
> Read first: [`docs/kmp-migration/STATUS.md`](../../docs/kmp-migration/STATUS.md)
> (study tip `c9ff5e2f` + P0 freeze `c5db5a0333` + AIMI ref tip `c653fc4485` + lot ledger P0→P3.8).
> Remaining work: [`docs/kmp-migration/DELTA-remaining.md`](../../docs/kmp-migration/DELTA-remaining.md).
> Agent team / triple feu: [`AGENT_OPS.md`](AGENT_OPS.md).
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
nothing calls yet - and to port it together with whatever feature ends up needing it. **Updated
2026-09-28: that feature has been named.** The owner confirmed the Glass skin and the V2 dashboard are
in scope, so this file is deferred until they land, not held for want of a purpose. See 6ak.

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

## 6ai. 2026-09-21: the anchored lot series, and a dosing gate the branch had gone backwards on

Resuming after a merge brought in work this session had not seen. The important part is not the
merge itself but what it revealed about how this port is actually being run.

**There is an anchored lot process, and it is more rigorous than this session's file-count
arithmetic.** `_docs/kmp/P*-ANCHOR.md` runs P0.8 through P3.8, one clinical topic per lot (P3.5 MCER
latch, P3.6 TPO revert, P3.7 ML stale training, P3.8 calibration health), each with a PR, a
`GO_WITH_CAVEATS` verdict, and blob-level SHA comparison against named `dev_OAPSAIMI` commits with
out-of-scope items marked explicitly. Any future gap analysis should start there rather than
diffing file lists.

The merge moved AIMI from **532 to 674 files** here, and the production gap from ~25 real files to
**16** - nine of which are the `retention/` package. 129 tests still missing.

**Four duplicate test classes had to be removed**, and the direction matters: this session had ported
`UndeclaredCobEstimatorTest`, `UamInputSchemaValidatorTest`, `SmbRefinementFeatureSchemaTest` and
`TrainingCircuitBreakerTest` into `androidHostTest`, while the anchored series had already placed them
in `commonTest` with `kotlin.test`. The `commonTest` copies won: same assertions, but they also run on
Native, and their KDoc records the provenance and why the source set was chosen. Suite: **1377 tests,
0 failures**.

### The finding: the branch was running a dosing gate production had deliberately reverted

`UndeclaredCobEstimator`'s heart-rate gate stood down when glucose rose faster than
11 mg/dL per 5 min. Production's gate fires unconditionally, and its comment says why - quoting the
user's own instruction:

> the heart rate may protect, it may never be a reason to believe in a meal. The cost is known and
> accepted: a real undeclared meal that raises the heart rate is not caught here.

The history is the instructive part. `5220fc5e2f` **introduced** the rise-suspend four days ago; P3.1
ported that behaviour here, with four tests locking it in. Production then **reverted it** in
`57c6e0cc30` - a commit whose message is *"Enhance Dexcom One+ plugin to handle sensor change
timestamps and prevent duplicates"*. A revert of a dosing gate, carried in a commit named after
something else entirely.

**That is exactly the failure mode an anchor process cannot catch**: it compares against named
commits, and this change is invisible from the name. Only reading the file settles it.

The branch is now aligned: the gate is unconditional, with production's reasoning copied across, and
the one test that asserted the opposite is flipped rather than deleted - the constant stays as the
threshold that was considered and rejected, and the tests use it to say "even well above this, the
gate holds". The three neighbouring tests needed no change; they passed under either behaviour, which
is worth knowing, because it means a test suite being green said nothing about this divergence.

Gates: `testAndroidHostTest --rerun` **1377 tests, 0 failures**, `:app:assembleFullDebug` and
`compileKotlinIosArm64` both clean.

## 6aj. 2026-09-21: the `retention/` package, ported

The biggest missing piece of production code, and not housekeeping: it is production's fix for AIMI's
telemetry files that grow without limit. Production's own KDoc measures the decisions journal at
**2.10 GB**.

**Where the files went** (the user decided this):

| File | Source set | Why |
|---|---|---|
| `AimiRetentionPolicy` | commonMain | data only, 15 rules, same as production |
| `AimiTimestampKey` | commonMain | byte scanning; `Charsets.US_ASCII` swapped for `encodeToByteArray()`/`decodeToString()` (the keys `timestamp`/`wall_ms` are ASCII, so the bytes are the same) |
| `AimiLineScanner`, `AimiCutPlanner`, `AimiArchive`, `AimiFileLock`, `AimiRetentionManager`, `AimiAppendGuard` | androidMain | copied as-is. They rely on `RandomAccessFile.seek`, fsync, gzip, `ATOMIC_MOVE`, `fileKey()` and a non-blocking `tryLock()`. `AapsLock` blocks, so it cannot replace `tryWithFile` |
| `AimiRetentionWorker` | androidMain | Hilt → Metro, see below |

**The one new interface: `AimiAppendCap`** (commonMain, one method `beforeAppend(path, bytes)`). On this
branch `AuditorJsonlExport` is already shared code and only has an `AimiPath`, so it could not call the
`File`-based guard. Without this interface the decisions journal, the 2.10 GB file itself, would have
lost its cap without any sign. `AndroidAimiAppendCap` passes the call to `AimiAppendGuard`.
`DetermineBasalAIMI2` injects it and passes it to `AuditorJsonlExport.appendLine`. There is no iOS
implementation, and the KDoc says why: `AimiStorage` has none either, so on iOS these files are never
written. Production's own warning is carried across word for word: "one cap per file between janitor
runs, not an unlimited bound".

**The worker.** `@HiltWorker` became `@AssistedInject` plus an `@AssistedFactory` that extends
`MetroWorkerCreator`, registered with `@WorkerKey` in `AppWorkersGraph`. The `@Assisted` parameters
must be called `context`/`params` to match `MetroWorkerCreator`, or Metro fails with "Missing from
factory". The two pure functions stay in the same file, as production has them. The brief suggested
moving the body into a `RunnerWorker` runner, but that was not needed to port it. `now` is
`::aimiWallClockMs`. `AimiMlTrainingScheduler` schedules it every 24 h (`UPDATE` policy), and its
`cancel()` does not cancel it on purpose, with production's comment copied across.

**Tests: all 13 files, 107 tests, ported to `androidHostTest`. None were parked and no assertion was
changed.** One call site had to change: `AimiRetentionEndToEndTest` calls `ComparisonCsvParser.parse`,
which here takes an `AimiPath` through `AimiStorage`, not a `File`. The test now builds the parser on
an `AndroidAimiStorage` with a mocked helper. The path is absolute, so the helper is never asked
anything. The brief's plan to put the two commonMain files' tests in `commonTest` was not followed.
They run on the host with the rest; moving them to `kotlin.test` is a small follow-up.

Gates: `testAndroidHostTest --rerun` **1484 tests, 0 failures** (1377 + 107),
`compileKotlinIosArm64` and `:app:assembleFullDebug` both clean.

### Found while doing it: the basal ML trainer never runs on this branch

`BasalMlTrainerWorker` has a Metro `Factory`, and `AimiMlTrainingScheduler` enqueues it every hour
and once at start-up. But **no `@WorkerKey` in `AppWorkersGraph` registers it**. Without a key, WorkManager
falls back to reflection, which needs a `(Context, WorkerParameters)` constructor. This worker takes
five parameters, so every run fails. Every other AIMI worker takes only those two and works.

**Not fixed, on purpose.** Registering it is one `@Provides` block, but it turns on a learning loop
that affects basal dosing. That is a decision for the user, not part of a port.

## 6ak. 2026-09-28: the home screen decision — Glass and Dashboard V2 are IN SCOPE

The owner was asked directly, because three files kept being re-counted as "missing" at every audit
and two live telemetry functions have no caller on this branch. His answer, in his own words: the
Glass skin and the dashboard both matter, an AIMI-specific alternative home screen is worth having,
**he runs the Glass skin on his own phone today**, and other users run the dashboard.

**So they are not dropped. They are not ported yet. Nothing here may be deleted as dead on the
grounds that its consumer is absent — the consumer is coming.**

What is absent on this branch, measured 2026-09-28:

| On `origin/dev_OAPSAIMI` | Files | Here |
|---|---|---|
| `plugins/main/.../general/dashboard/` (`glass/` 23, `compose/` 19, `viewmodel/` 3, `views/` 4, plus the shell) | 67 | absent, no equivalent |
| `plugins/main/.../skins/` (`DashboardHomeVariant`, `DashboardHomeVariantResolver`, `SkinGlass`, `SkinClassic`, `SkinMinimal`, `SkinLowRes`, `SkinProvider`, …) | 12 | **absent entirely** |

The order matters and is not obvious from the file counts: the reference picks the home screen at
runtime through `DashboardHomeVariantResolver.resolve(...)` in `ComposeMainActivity` (ref `:676`,
`:681`, `:894`, `:1048`), with three outcomes — `OVERVIEW`, the V2 dashboard, or `GLASS`. This
branch's `ComposeMainActivity` goes straight to `OverviewScreen` with no resolver at all. **The
missing piece is the home-screen choice mechanism, and the screens come after it.**

### What this decision reverses

- `AIMI_PORT_STATE.md` §1403-1406 and §2236-2243 said to hold `orchestration/AimiLoopRuntimeGuard.kt`
  (the last staged file) until a feature needed it, and to ask the user before revisiting. Asked and
  answered: **the feature that needs it is in scope.** Keep the file staged, but it is now waiting for
  a named feature, not waiting for a reason to exist. `_docs/kmp/staging/` cannot be retired yet.
- The same applies to `pkpd/TrajectoryRuntimeRepository.kt`, which was correctly not ported on
  2026-09-27 because its only reader is `glass/GlassLoopDashboardViewModel`. That reader is coming,
  so the file is deferred, not refused.
- `AimiLoopTelemetry.isTickInProgress()` / `activeTickAgeMs()` have no caller here. That is the
  amputated consumer chain of the same feature. Do not "clean them up".

### A structural blocker found on 2026-09-27, which this decision makes urgent

On the reference, one consumer of the loop guard is `IobCobCalculatorPlugin`
(`scheduleDeferredAppInitializedCalculation`). On this branch that plugin now lives in
**`plugins/main/src/commonMain`**, so it cannot reference an `androidMain` type in `:plugins:aps` at
all. The deferral cannot come back in its reference shape; it needs a port in `core:interfaces`, the
way `PluginStatusBadgeSource` already solved the same problem for the auditor badge.

## 6al. 2026-09-28: the Glass plan, and the three things a first survey got wrong

A survey scoped the Glass + V2 dashboard port; an adversarial verification then broke two of its three
load-bearing claims. Both are recorded here because the wrong version is the intuitive one and will be
re-derived by anyone who looks at the file list rather than at the code.

**Wrong: "the home screen is a one-line swap in the `overview` slot."** There are two functions called
`OverviewScreen` on this branch. `ui/src/commonMain/.../main/OverviewScreen.kt:68` is the whole shell -
drawer, top bar, bottom navigation, sheets - and it is what sits in
`appNavGraph(overview = …)` (`appshell/src/commonMain/.../navigation/AppNavGraph.kt:188`, filled at
`app/src/main/kotlin/app/aaps/ComposeMainActivity.kt:480`). `ui/src/commonMain/.../overview/OverviewScreen.kt:50`
is the home content. Putting a Glass screen in that slot would delete the app chrome. The reference does
not do that either: it keeps its shell and passes the variant in, with `isGlassSkin` switching the bottom
bar (ref `ui/src/main/.../main/MainScreen.kt:160,163,250,452`). So the real work is: parameters on this
branch's shell, a `GlassNavigationBar` nobody counted, and 11 `AppRoute` entries in `:appshell`.

**Wrong: "no new inter-module dependency is needed."** The conclusion survives, but only in a shape the
survey did not state: `GlassLoopDashboardState` in `core:interfaces`, the screen in `:ui`, the ViewModel
in `:plugins:aps`, and the nav graph handed a `StateFlow` rather than the ViewModel type. `:appshell` has
no `:plugins:aps` dependency and `:plugins:aps` has no `:ui`, so any other placement needs a new edge.

**Right, and it is the good news: Glass does not touch the shipping Overview graph.** No glass file
references `BgGraphCompose`; it renders its own Canvas chart and reads only data flows from
`GraphViewModel`. The reference's +9 dashboard-only parameters on the shared graph
(`BgGraphCompose` 8 params here vs 17 there) belong to the V2 dashboard alone, which keeps the highest
risk item out of the owner's slice.

### A dropped capability that blocks the Glass loop screen

`plugins/aps/src/androidMain/.../ml/AimiSmbTrainer.kt` is **422 lines here against 901 on the reference**.
`lastResult()`, `currentWaitingStatus()`, `lastAttemptAtMs()`, `lastTrainedAtMs()`, `isCircuitOpenNow()` and
the `TrainingOutcome`/`TrainingResult` types do not exist here at all, and neither do
`BasalMlTrainingCoordinator.lastTrainedAtMs()/isCircuitOpenNow()/basalWeightsFile()`. These are exactly what
`GlassLoopDashboardViewModel` reads. This is failure shape (e) - a capability dropped in the port, not
renamed - and it has to be restored before that screen can exist.

Other things nobody had counted: Hilt does not exist on this branch (5 glass files use `hiltViewModel`),
`core/ui/.../compose/glass/` (4 files) is absent, ~98 strings, 5 drawables to convert to `ImageVector`,
3 preference keys, and four glass files sit on LiveData that has to become a `StateFlow`.

### The app-init deferral: do NOT port it

`AppInitCalculationPolicy` on the reference is two things, and only one is cosmetic. `DEFER_MS = 5_000`
is UI smoothness, but `WARM_START_MAX_BUCKETS_TO_COMPUTE = 100` **truncates the autosens computation** on
cold start - consumed at `workflow/.../PrepareGraphDataWorker.kt:297` and `:528`, where it bounds both the
AutosensData loop and the oref loop to roughly the last 8 hours. And the guarded path drops the work
rather than rescheduling it: if an AIMI tick is running at T+5 s, the whole app-init recalculation never
happens. **This branch is currently the safer of the two** - it recomputes everything, immediately,
unguarded (`plugins/main/src/commonMain/.../IobCobCalculatorPlugin.kt:175-188`). Porting the reference
shape would be a dosing-input regression. If the cold-start ANR is real, it needs a design that
reschedules, and the bucket truncation needs its own clinical decision.

## 6am. 2026-09-28: Adaptive Smoothing is in scope, by the owner's decision

Asked whether `GlassSensorQualityScreen` should be dropped since adaptive smoothing does not exist here,
the owner answered that the plugin matters in its own right: it gives good smoothing **and** the sensor
quality view. So it is ported, and it is a clinical port - it changes the glucose series the loop doses on.

Four of the five smoothing plugins were already in `commonMain`; only the adaptive one and its 6 tests were
left behind, plus `SmoothingContext` and the three quality types in `core:interfaces`.

**The part to know before touching it:** porting it changes the shared `Smoothing` interface. `smooth`
becomes `suspend` and takes a `SmoothingContext`, and two defaulted members appear. That is not optional -
it is the reference contract, and on this branch `profileFunction.getProfile()` is itself `suspend`, which
forces the same answer independently. Production call sites are only two
(`workflow/.../PrepareGraphDataRunner.kt:158`, already suspend; `ios/shell/.../ShellInfo.kt:83`, not), plus
two in tests.

## 6an. 2026-09-28: the AIMI CSV is the training corpus, not a log — handle with care

Said by the owner, and it changes how the remaining file I/O in `DetermineBasalAIMI2.kt` must be
treated: **AIMI trains the SMB and the basal models on the phone, from these CSV files.** The writer
at `DetermineBasalAIMI2.kt:13906-13920` is therefore the corpus writer, and the reader is
`AimiSmbCorpus.buildTrainingCorpus`. A careless port of this path does not lose a log line, it changes
what the models learn. There is precedent: a header frozen at 13 columns once made the trainer learn
the wrong column as its label.

**So the `java.io.File` work in this file is NOT a mechanical lot.** 15 `File(...)` constructions
remain, and they are the real obstacle to `commonMain` now that the strings are gone (2026-09-28,
149 call sites moved to `TextResolver`). Whoever takes it must treat the corpus path as clinical code:
same header handling, same row order, same append semantics, byte for byte.

### Two defects found while reading it, neither fixed

**1. The rewrite drops the trailing newline, so the next row written is glued onto the last kept one.**
`removeLast200Lines` (`:13946`) ends with `csvFile.writeText(newLines.joinToString("\n"))`, which has
no trailing newline, while `appendCsvToFile` (`:13919`) appends `valuesRow + "\n"` with no guard. So
after every deletion the next tick's row merges with the previous last row: the two share one field and
the line ends up with about twice as many columns.

Severity is limited, and only because the reader is strict: `AimiSmbCorpus.kt:82` drops any row with
`cols.size > headers.size`, so the glued line is skipped rather than mis-parsed. **Nothing is learned
wrongly; two rows are silently lost, and the malformed line stays in the corpus for good.** The fix is
one line - append a newline first when the file does not end with one - and it belongs with whoever
ports this path, together with a test.

**2. The "bad day" cleanup deletes a fixed count, while the message promises a date.**
`automateDeletionIfBadDay` (`:13982`) fires between 00:05 and 00:10 when the 1-day TIR is under 85 %,
computes `yesterday`, and then calls `removeLast200Lines`, which drops the **newest** 200 rows - about
16 h at one row per 5 min - regardless of date. The user-facing reason then says the data for that date
was removed (`reason_data_removed`). The commented-out `createFilteredAndSortedCopy(csvfile, dateToRemove)`
on the line above shows the date-based version was the intent. The same blunt call is used by the
therapy-note path at `:3048`.
Whether the count is deliberate is a question for the owner, not a thing to change while porting.

The timestamped backups it leaves behind (`backup_yyyyMMdd_HHmmss.csv`) are at least managed:
`AimiRetentionPolicy.DROP_GLOBS` (`:172`) matches exactly that name.

## 6ao. 2026-09-28: `DetermineBasalAIMI2` no longer holds an Android type

The 20 000-line algorithm core went from "38 % of everything left in `androidMain`, held by Android"
to held only by the JVM. Three lots, in this order, each verified on its own:

1. **S1 - the two dead seams wired.** `AimiStorage.fallbackFile()` and `HormonitorStudyExporterProvider`
   both existed, implemented, with nothing calling them. The CSV fallback and the study exporter now go
   through them. The fallback path was proved unchanged: the Android implementation builds
   `<app scoped external dir>/AAPS/<name>`, which is the expression it replaced.
2. **S2 - the strings.** **149 call sites** moved from `context.getString(R.string.x, …)` to
   `rh.gs(ApsStrings.x, …)`. 138 distinct ids, of which 137 were already generated into a commonMain
   source dir by `GenerateKeyStringsTask`, so no XML work was needed; the 138th
   (`format_insulin_units`) already existed as `InterfacesStrings`.
3. **The last two `Context` holders.** `AimiModelHandler` (7 signatures, 19 ids - not the single
   function the brief predicted) and `smb/SmbInstructionExecutor` (7 ids) converted the same way, plus
   two lines in `OpenAPSAIMIPlugin`. `context` then left the constructor.

**Result:** `android.content.Context` appears in none of the three files. The only Android imports left
in the core are `android.annotation.SuppressLint` (an annotation) and `androidx.collection.LongSparseArray`,
which is itself a KMP library and therefore not a blocker.

### What actually guards this change

A wrong string id does not compile, because `ApsStrings` members are generated from the XML. **A wrong
argument count or order does compile**, and fails at runtime inside the dosing loop, in text that reaches
`rT.reason` and therefore Nightscout. Worse, `rh.gs` is not `context.getString`: the implementation
catches format errors and returns a fallback string, so what used to be a loud crash is now a silent
wrong line.

So the guard is `DetermineBasalAimiStringsTest` (`androidHostTest`), a table of **164 ids with their
argument counts** across the three files. It fills each template with arguments of the type each
placeholder asks for and asserts nothing is left unfilled. Two things learned while building it:

- **The table is hand-written, so it needs cross-checking against the real call sites, not just the XML.**
  Combining two lots surfaced one wrong entry (`bg_near_target` claimed 3 arguments; template and call
  site both have 2). Each lot was green alone; only the combination failed.
- A naive argument counter over Kotlin source is wrong in two ways that both appeared here: a **trailing
  comma** in a multi-line call reads as an empty argument, and a **nested** `rh.gs(...)` inside another
  call's argument list reads as its own site. Both produced false mismatches before being corrected.

### What still keeps the core out of `commonMain`

Not Android - the JVM. 13 `java.*` imports and, most importantly, **15 `File(...)` constructions**
including the CSV read-filter-rewrite at `:13906-13976`. That path is the on-device training corpus
(see `6an`) and must not be ported casually.

`SmbInstructionExecutor` is now the closest of all of them: **no `android.*` import at all**, and all
seven of its AIMI neighbours are already shared. What is left is one `Calendar`, one `TimeUnit` and about
ten `String.format` calls - each of which formats a number that reaches `rT.reason`, so swapping them is
behaviour-visible and deserves its own lot rather than a sweep.

## 6ap. 2026-09-28: the blocker is no longer imports, it is a handful of androidMain hub types

A compile probe tried to move the "easy" AIMI files to `commonMain` and moved **zero**, on purpose,
with the compiler naming the blocker each time. That negative result is the useful one, because it
kills the measure everyone reaches for first.

**The import test is the wrong filter.** Of the 109 AIMI files still in `androidMain`, 42 import no
`android.*` or `androidx.*` at all - and every one of them is still blocked. **78 of the 109 reference
another `androidMain` type**, which no scan of Android imports can see. Ordering candidates by file
size made it worse: the smallest files pointed at the *least* movable ones. `AuditorStatusBadgeSource`
says so in its own first KDoc line, and it was top of the "looks free" list.

### The real leverage, measured by how many files each hub gates

| files gated | hub type | what it needs |
|---|---|---|
| **13** | `utils/AimiStorageHelper` | nothing new - `AimiStorage`/`AimiPath` already exist in commonMain and `AndroidAimiStorage` already wraps this helper. These files simply still call it directly. |
| 7 | `llm/gemini/GeminiModelResolver` | an HTTP port |
| 7 | `advisor/AiCoachingService` | an HTTP port |
| 7 | `advisor/data/AdvisorHistoryRepository` | Gson → kotlinx.serialization, which changes the persisted format, so it needs a migration decision |
| 6 | `physio/HealthContextRepository`, `steps/UnifiedActivityProviderMTR`, `autodrive/learning/AutodriveDataBackfiller` | Health Connect / the CSV corpus |
| 5 | `AuditorUIState` | `@ColorRes`/`@DrawableRes` + `core.ui.R` - needs the colour-enum refactor the house rules already ask for |

Direct HTTP imports (`java.net`, `okhttp3`) appear in **9** files, all of them LLM or API clients:
the four vision providers, `AiCoachingService`, `AuditorAIService`, `GeminiModelResolver`,
`AIMILLMPhysioAnalyzerMTR`, `OuraApiThermalClient`. `org.json` appears in 13 files and usually travels
with them.

**But an HTTP port frees only four of the nine, not the family.** Checked per file, 2026-09-28: the four
vision providers (`ClaudeVisionProvider`, `DeepSeekVisionProvider`, `GeminiVisionProvider`,
`OpenAIVisionProvider`) each carry two Android imports of their own - `android.graphics.Bitmap` and
`android.util.Base64` - so they need an image port as well and stay on Android until then.
`GeminiModelResolver` has one Android import left. The four with **no** Android import at all, and
therefore genuinely freed by an HTTP port plus `OrgJsonCompat` and `kotlinx.datetime`, are
`AiCoachingService`, `AuditorAIService`, `AIMILLMPhysioAnalyzerMTR` and `OuraApiThermalClient`.

The lesson repeats the one above: a hub type tells you what *gates* a file, not what would *free* it.
Only the per-file check answers that.

Gson is a smaller problem than it looked: exactly **one** file imports it.

### Two substitutions that are not general, and were written down as if they were

- **`ReentrantLock` → `AapsLock` does not always work.** `AapsLock` has `lock`/`unlock` and no timed
  `tryLock`. `AutodriveDatasetLock` exists precisely to attempt a zero-timeout, non-blocking lock so the
  APS decision thread never waits on the backfiller, and `AimiLoopGate` needs both `tryLock(timeout)`
  and `isHeldByCurrentThread`. Those files cannot move until `core:interfaces` grows that API - a design
  decision, not a swap.
- **`java.io.File` → `AimiStorage` does not cover retention.** The port is whole-file plus
  `readTailLines`/`forEachLine`. `AimiArchive`, `AimiLineScanner`, `JsonlTailReader` and
  `HormonitorReader` need `RandomAccessFile`, `FileChannel`, `GZIPOutputStream` or `StandardOpenOption`.
  The port has to be extended before they can move.

## 6aq. 2026-09-28: the parity rule, stated by the owner

In his words: we are **porting** AIMI, and everything that works on `dev_OAPSAIMI` today has to work on
the multiplatform version. Outside genuinely dead code - where the question may fairly be asked - the
job is to find the balance that carries the feature across, not to drop it.

This sets the default, and it is the opposite of the one an agent naturally reaches for. "Nothing calls
it here" is a reason to **look for the lost consumer**, not a reason to leave a capability behind. The
migration has already produced three cases of exactly that shape:

- `AimiSmbTrainer` lost its whole training-telemetry surface (901 lines there, 422 here) - restored
  2026-09-28.
- `TrajectoryRuntimeRepository` and `AimiLoopRuntimeGuard` look unused here only because the Glass and
  dashboard consumers were never ported; the owner has since confirmed both screens are in scope (6ak).
- `AimiLoopTelemetry.isTickInProgress()`/`activeTickAgeMs()` are live with no caller for the same reason.

So when a file cannot move because a dependency is missing, the answer is a port or a seam, not a
deletion and not a silent stub. An implementation that quietly does nothing is worse than an absent
feature, because the user believes it is working - which is why the badge source was made optional
rather than stubbed (6al).

The genuinely dead exceptions found so far, each confirmed dead on **both** branches before anything was
decided: `model/StateTransitionManager` (superseded by `AimiStateTransitionManager`), `AimiSmbSimulator`,
`AIMICompositeStepsProviderMTR`, `AIMIHealthConnectStepsProviderMTR`, and the empty Hilt scan module.
`AimiUamHandler.configureUamModel` is a candidate but has not been checked against `master` yet.

## 6ar. 2026-09-29: a measure that keeps being wrong — "gates" is not "frees"

I have now made the same estimating error three times in one campaign, in three different lots, so it
is worth writing down rather than re-learning:

| I said | Reality |
|---|---|
| "an HTTP port unblocks that family of 9" | it frees **4**; the other 5 carry `Bitmap`, `Base64` or their own Android import |
| "these 2 helpers are the last androidMain dependency of the LLM clients" | they were **two of several**; `SP`, `org.json` writing and JVM exception types remained |
| "`AdvisorHistoryRepository` gates 7 files, so porting it unblocks 7" | it frees **0**; all 7 lose one blocker each, none becomes movable |

**A hub count tells you how many files a type gates. It tells you nothing about how many would move if
that type were freed**, because the files typically carry two to five independent blockers. The only
honest estimate comes from checking each dependent's own remaining blockers, and the only proof is the
compiler.

The corollary matters for planning: porting a hub is still worth doing - it is a prerequisite that
retires debt for every dependent - but it should be scheduled and reported as "removes one blocker from
7 files", never as "unblocks 7 files". Two of those seven (`TpoSessionManager`, `TuningContextApplySupport`)
are now a single small blocker away (`java.util.UUID` and `java.util.Locale` respectively), which is the
useful thing to say.

## 6as. 2026-09-29: the advisor history store, and what Gson was really doing

`AdvisorHistoryRepository` is in `commonMain`. What the port had to preserve is history already on the
owner's phone, where a format break does not crash - it returns an empty list and the history silently
disappears.

Three facts found by running Gson rather than reasoning about it:

1. **The JSON is not byte-identical out of the box.** A plain `Gson()` writes in HTML-safe mode and
   escapes `<`, `>`, `&`, `=` and `'` as unicode escapes; kotlinx writes them as themselves. Both readers
   accept both forms, so this is not a read-compatibility problem - but advisor text is LLM-written and
   really does contain `=` and `>` (`ISF > 90`). The writer now reproduces Gson's escaping exactly. It is
   safe to apply to the finished document because none of those five characters is JSON syntax, so each
   can only sit inside a string value.
2. **An unknown enum value was a latent crash, not graceful degradation.** Gson kept the entry and wrote
   `null` into the non-null Kotlin field `type`, so the next caller reading `it.type` would throw. The new
   reader drops that one entry and keeps the rest - strictly better, and it decodes per entry precisely so
   that one bad entry cannot cost the whole file.
3. **A stored `""`, `"   "` or `"null"` made Gson return a null list**, which would have thrown at
   `loadHistory().toMutableList()`. Now it is an empty list.

**`kotlinx.serialization` is only half available in `:plugins:aps`:** the runtime arrives transitively
through `:core:utils`, but the **compiler plugin is not applied**, so `@Serializable` cannot be used here
without a build change. The store is read and written through the `JsonObject` element API with
`OrgJsonCompat` accessors, which is what `T3cRuntimeHistoryReader` and `HarmoniaRuntimeHistoryReader`
already do in the same package. Worth knowing before anyone plans a serialization lot in this module.

Also recorded, not fixed: `getRecentActions(days)` computes `days * 24 * 60 * 60` in `Int`, so anything
from 24856 days up silently overflows and the window becomes short or negative. No caller passes more
than a few weeks.

## 6at. 2026-10-02: `String.format` has no drop-in replacement, and three blocker categories, not two

### The number formatting, measured

The house replacement for `String.format` / `"%.Nf".format` is `NumberFormat`. Two differences were
being repeated in briefs, and **both are avoidable**: `NumberFormat.withDecimalsHalfUp(n)` gives
HALF_UP, and `format(v)` without a separator argument follows the device locale. The recipe
`withDecimals(n).format(v, SEPARATOR_DOT)` is the *locale-independent* overload, not the only one.

**The real obstacle is different, and it is not fixable by picking a rounding mode.**
`java.util.Formatter` rounds the double's **shortest decimal representation**; `DecimalFormat` rounds
the **exact binary value**. Measured over 180 297 comparisons (en-US / fr-FR / de-DE × 1, 2, 3
decimals, 20 000 random draws plus a tie list): **42 mismatches, all from exact ties, zero from the
random draws**:

| value | decimals | `String.format` | `NumberFormat` |
|---|---|---|---|
| 0.15 | 1 | `0.2` | `0.1` |
| 8.35 | 1 | `8.4` | `8.3` |
| 1.005 | 2 | `1.01` | `1.00` |
| 2.675 | 2 | `2.68` | `2.67` |
| Infinity | - | `Infinity` | `∞` |

So a swap is right almost always and then silently wrong on a value like 8.35 hours of sleep. **There
is no drop-in.** Swapping is fine for log text; for anything a reader or a model consumes it is a
decision, not a port. The harness that produced this is reproducible; flipping it to HALF_EVEN raises
the count 42 → 54, so the number is not a vacuous pass.

### Where those strings actually go, in `AuditorOrchestrator`

Traced, not guessed. Four of its six sites build a `DecisionResult.Applied.reason`, and that field is
**written into the training corpus** - `AuditorJsonlExport.kt:104` does `put("reason", result.reason)` -
and reaches the dose reason the user reads (`DetermineBasalAIMI2.kt:9635`,
`finalResult.reason.append("Auditor Rejected: ${result.reason}")`). The other two are log text and
would be swappable. **A `String.format` site in AIMI is not cosmetic until you have followed it.**

### Three blocker categories, not two

An import scan misses two things, and this run found a third:

1. a same-module `androidMain` type;
2. a `java.lang` API that needs no import (`String.format`, `Thread`, `Math`, `Throwable.stackTrace`);
3. **an androidMain-only interface in *another* module** - `TpoOrchestrator` is blocked by
   `app.aaps.core.interfaces.sharedPreferences.SP`, which exists only in `core/interfaces/androidMain`.

And a fourth trap, one level down from the import scan: **a name-based scan reports blockers that do
not exist.** Two types share the simple name `AuditorUIState` - one androidMain with `@ColorRes`, one
already in commonMain - and `AuditorOrchestrator` uses the common one.

### Kotlin/Native and stack traces

`Throwable.stackTrace` **does exist** on Native. It is `private` and typed `NativePtrArray`, so no
cast, reflection or `@Suppress` reaches it. The only shared API is `stackTraceToString()`, whose shape
is platform-specific and whose frames can be bare addresses in an optimised build - so class, method
and line are **absent**, not merely formatted differently. `AimiLoopTickRecovery` reads all three per
frame, so an honest iOS actual cannot be written; an empty list would silently drop the `at X.y:123`
from a safe-hold reason an engineer reads after an incident. This needs an owner decision, not a seam.

### Harness trap worth keeping

A trailing global `--rerun` does **not** apply to every task in the invocation: a run logged
`testAndroidHostTest UP-TO-DATE` and finished in 11 s. Attach the flag per task
(`:a --rerun :b --rerun`) and check the log says the task executed.

## 6au. 2026-10-02: `AimiLoopTickRecovery` is the tick-result totality guarantee, not a stack-trace reader

The owner pushed back on the framing, and he was right. This file had been written off as "it reads a
stack trace, Kotlin/Native has no `StackTraceElement`, so it stays on Android". That is a true premise
and the wrong conclusion.

**What it actually is.** Its contract: *every AIMI `determine_basal` tick returns exactly one
well-formed `RT` that commands no new insulin and names the loop phase it stopped in - including the
ticks that threw or were skipped - so the loop always has a result to publish, persist and upload, and
the failure is attributable.* Two entry points, both on the dosing path, both only on failure or skip,
called from `DetermineBasalAIMI2.kt:18949` and `:18951`.

**The no-change semantics are load-bearing and were written down nowhere.** `minimalRt` leaves `rate`,
`duration` and `units` null, so `DetermineBasalResult.with()` leaves `isTempBasalRequested` false and
`smb` 0.0, and `isChangeRequested()` is false. **A safe hold requests nothing and does not cancel a
running TBR either** - whatever the last good tick set runs to its natural expiry. That is a defensible
choice, but it is a choice.

**What is lost without it:** not a different safe decision - *no* decision and no trace.
`lastAPSResult` stays null, `LoopPlugin` has no try/catch around `usedAPS.invoke`, the exception climbs
to the worker, nothing is persisted or uploaded, and the `"Result: $it"` log line never runs, so **the
failure does not even reach the AAPS log file**. The pump is safe; the incident is invisible.

**The stack trace is an optional enrichment the file already handles being without.** All three uses
are null-guarded (`:47`, `:54`, `:59`). Without a frame the reason degrades from
`AIMI safe hold [CORE_DECISION: … @ DetermineBasalAIMI2.coreDecisionBranch:4287]: NullPointerException`
to the same line without the ` @ …` clause - phase, hint and error class survive. So the obligation
that must be honourable everywhere (totality, no-change, phase attribution) is **fully portable**, and
only a precision band inside one string is not. The house rule about a feature being visibly absent
rather than present and dead is satisfied, because the clause's *presence* carries the claim.

The real dependency order is **phase holder → gate → recovery**; the stack trace is the third blocker,
not the first. The first two landed 2026-10-02.

### Two tests were lost in the migration, and a stale artifact hides it

`origin/dev_OAPSAIMI` has five orchestration tests. Three were carried to `androidHostTest`; **two were
not**: `AimiLoopTickRecoveryTest` and `AimiDetermineBasalTickOrchestratorTest`. Neither exists anywhere
on this branch.

**And `plugins/aps/build/test-results/.../AimiLoopTickRecoveryTest.xml` is still on disk from an old
run**, so a glance at the build output suggests the test still runs. It does not. When checking whether
a test exists, look in `src`, never in `build`.

The reference test pins the lock-skip and error paths, the frame rendering and that the exception
*message* stays out of `reason` (it goes to `consoleLog`/`consoleError` instead - a deliberate
separation documented nowhere else). **It does not pin the thing that actually protects the patient**:
that the returned `RT` requests no change. Neither branch has ever had that test.

---

---

## 6av. 2026-10-02: the Compose cluster measured, and a false alarm worth recording

With the algorithm core done, the dominant theme in what is left of AIMI `androidMain` is Compose:
**15 files, about 6 200 lines, 96 AIMI files left in `androidMain` against 425 in `commonMain`.**
Measured per file (lines | `stringResource(R.` | `R.string.` | `LocalContext` | Android graphics or
activity):

```
  53 |  0 |  1 | 0 | 0  AimiPreferenceInfoScreen.kt
  76 |  5 |  5 | 0 | 0  AiProviderDropdown.kt
 162 | 11 | 12 | 0 | 0  AimiSupportPackageScreen.kt
 181 |  4 |  6 | 2 | 0  AimiPkpdSettingsScreen.kt
 239 | 10 | 13 | 2 | 3  AimiSosPermissionScreen.kt
 267 | 13 | 14 | 2 | 2  AimiHealthConnectPermissionScreen.kt
 288 |  7 | 14 | 0 | 0  AimiCognitiveOrefCoachCards.kt
 299 | 17 | 24 | 2 | 0  AimiModeSettingsScreen.kt
 324 | 40 | 41 | 2 | 0  HormonitorViewerScreen.kt
 514 | 67 | 90 | 0 | 0  AimiRuntimeHistoryCards.kt
 590 | 42 | 57 | 2 | 0  AimiContextScreen.kt
 747 | 49 | 62 | 0 | 0  PkpdSettingsUi.kt
 762 | 18 | 36 | 3 | 7  AimiMealAdvisorScreen.kt
 923 | 62 | 81 | 2 | 0  AimiProfileAdvisorScreen.kt
1021 | 48 | 62 | 0 | 0  AimiControlCenterScreen.kt
```

**`R.string.` is not one job, it is four.** 518 sites, of which only 393 sit inside
`stringResource(`. The remaining 125 split into shapes that need different answers, and counting
only the first shape under-reads the lot by a quarter:

- `stringResource(app.aaps.core.ui.R.string.x)` / `CoreUiR.string.x` - cross-module, so the
  replacement is `CoreUiStrings.x`, not `ApsStrings.x`.
- `stringResource(android.R.string.ok)` / `.cancel` - the **Android framework** table, which has no
  multiplatform form at all. The same cluster already uses `app.aaps.core.ui.R.string.ok`/`cancel`
  a few lines away, so `CoreUiStrings.ok`/`cancel` is the parity-safe swap, but it is a decision,
  not a rename.
- `context.getString(...)` / `rh.gs(...)` in non-Composable helpers that happen to live in these
  files - notification titles, the SOS SMS body, an intent chooser. Same shape the algorithm core
  already solved.
- `titleResId = R.string.x` on preference definitions - lesson 6g (b) again: those properties were
  retyped to `TextRef`, so the value becomes `ApsStrings.x` and nothing else changes.

### The false alarm, recorded because the reasoning was reasonable and the conclusion was wrong

Reading `TextRefResource.android.kt` showed only three owners hardcoded (`keys`, `coreUi`,
`interfaces`) with everything else falling through to `TextRefIdRegistry`, and the only two
production `register()` calls in the tree are in `ResourceHelperImpl.start()`, for `coreUi` and
`implementation`. Sixteen modules declare an owner. That reads as twelve owners with no resolver -
and the resolver's own KDoc names the symptom, `format_carbs` drawn on the overview instead of
"12 g". It would have meant 2 044 production call sites, `aps` among them, drawing raw resource
names, and a blocking precondition for this whole lot.

**It is not true.** The per-platform registries are *generated* from
`buildSrc/src/main/kotlin/StringOwnerModules.kt`, which lists all sixteen owners including
`aps`, and which exists precisely because the four hand-written lists had drifted before (its KDoc
records the desktop one having five of sixteen). The two surviving hand-written `register()` calls
are the two special cases: `:core:ui` and `:implementation` cannot be seen from `:core:interfaces`,
so `ResourceHelperImpl` registers them itself.

The generalisable part: **a registration that is generated leaves no `register(` to grep for.** The
same habit that keeps catching people out here - a grep standing in for the build - caught this
too. The check that settled it was reading the generator, not searching for call sites.

So the Compose theme has **no hidden precondition**: `plugins/aps/build.gradle.kts:14` already
registers `GenerateKeyStringsTask` as `ApsStrings`/`ApsStringIds`, `aps` is a registered owner on
every platform, and 686 production sites in this module already use `ApsStrings.`. The lot is the
mechanical swap it looked like, plus the three non-string blockers (`LocalContext` in 7 files,
`android.graphics`/`androidx.activity` in 3).

Two pieces of stale documentation found on the way, left alone because the files are otherwise
untouched and this branch does not mass-fix:

- `MainApp.registerStringOwners()` is named by five KDoc comments (`TextRefResource.jvm.kt`,
  `TextRefValueRegistry.kt`, `GeneratedTextResolver.kt`, `ResourceHelperImpl.kt`, `BaseTestApp.kt`)
  and **does not exist** - it was replaced by the generated registries.
- `BaseTestApp.kt:130` is an **orphan KDoc**: a doc block with no declaration under it, left behind
  when the hand-written owner list it documented was deleted. Harmless, but it is what made the
  missing registration look real.

### First slice done, and the headline blocker was not one

Two of five moved: `AimiPreferenceInfoScreen` (53) and `AiProviderDropdown` (76). Gates unchanged at
1836 / 594 / 599, 0 failures, both compiles green. `AimiPreferenceInfoScreen` is worth noting because
the per-file table said **0 `stringResource(R.` sites** and it was still a strings job: it took
`@StringRes titleResId: Int` / `messageResId: Int`, which is lesson 6g (b) again. Counting one spelling
of a problem keeps under-reading these files.

The other three stayed, each for a reason no string swap would have touched:
`AimiSupportPackageScreen` needs an `androidMain` exporter and `java.io.File`; `AimiPkpdSettingsScreen`
needs four top-level declarations that live in `PkpdSettingsUi.kt` - **same package, so no import names
them**, the fifth time that trap has been paid for; `AimiSosPermissionScreen` is the Android
runtime-permission flow itself (104 errors, 77 platform) and has no honourable shared form.

**The probe over the other thirteen was worth more than the slice.** Moving four "clean by import scan"
files unconverted and classifying 432 compiler errors showed the scan wrong in both directions:
`AimiControlCenterScreen` is blocked by a same-package `Tpo` trio, and `AimiCognitiveOrefCoachCards` is
blocked by **Vico**, an Android-only charting library that read as clean only because the scan's regex
never covered `com.*`. Sixth instance of the same lesson.

**And the lot's reported headline finding was wrong, in a way worth recording.** The agent concluded
that `Dispatchers.IO` blocks six Compose files, that "there is no common IO-dispatcher abstraction
anywhere in the tree", and that one shared seam was therefore "probably the highest-leverage thing
left and it is a decision, not a port". It had looked in `plugins/aps/commonMain` and
`core/utils/commonMain`. The abstraction exists in neither of those: it is
`app.aaps.core.interfaces.concurrent.aapsIoDispatcher`, an `expect val` in
`core/interfaces/src/commonMain/` with iOS and `jvmShared` actuals, **already imported by about ten
AIMI `commonMain` files** - `therapy.kt`, `KalmanFilter.kt`, `AuditorAIService.kt`,
`UnifiedReactivityLearner.kt`, `OuraApiThermalClient.kt` among them. So the biggest reported blocker
is a one-line import swap, and six files are cheaper than the report says.

The pattern across 6ar, 6as, 6av and now this one is the same every time: **a negative claim from a
search is the least reliable kind of finding here, and it is the kind that gets reported as the
headline.** "Gates is not frees", "no `register(` call exists", "no IO dispatcher exists" - three
searches, three confident conclusions, three wrong. A negative result from a grep is a hypothesis.
Settle it by naming the thing it says is absent and looking for *that*, in the module where it would
actually live, before building a decision on top of it.

### The corrected map of the thirteen, after the `aapsIoDispatcher` correction

Checked against the files themselves, not against a scan. Three claims from the slice report were
spot-checked and all three held: Vico is really there (18 `com.patrykandpatrick` uses), the camera
pipeline is really there (19 camera2/`ImageReader`/`TextureView`/`HandlerThread` uses), and
`HormonitorViewerScreen` really does take `android.os.Environment`, `java.io.File` and
`SimpleDateFormat`.

| file | lines | what actually blocks it now |
|---|---|---|
| `PkpdSettingsUi` | 747 | strings only - **in flight** |
| `AimiPkpdSettingsScreen` | 181 | same-package companion above - **in flight** |
| `AimiRuntimeHistoryCards` | 514 | strings only - **in flight** |
| `AimiContextScreen` | 590 | `LocalContext` for **one** string with an argument; IO solved |
| `AimiControlCenterScreen` | 1021 | same-package `Tpo` trio |
| `AimiSupportPackageScreen` | 162 | `AimiSupportPackageExporter` (androidMain) + `java.io.File` |
| `AimiProfileAdvisorScreen` | 923 | `context.startActivity(Intent.createChooser(...))` at one line |
| `AimiModeSettingsScreen` | 299 | its own `getSharedPreferences` file + `String.format(Locale)` |
| `HormonitorViewerScreen` | 324 | `Environment` + `File` + `SimpleDateFormat` |
| `AimiSosPermissionScreen` | 239 | the Android runtime-permission flow itself |
| `AimiHealthConnectPermissionScreen` | 267 | same |
| `AimiMealAdvisorScreen` | 762 | a full `android.hardware.camera2` capture pipeline |
| `AimiCognitiveOrefCoachCards` | 288 | Vico, an Android-only charting library |

Two things this changes about what to do next.

**`AimiContextScreen` is a strings job after all.** Its `LocalContext` exists for exactly one call,
`context.getString(R.string.aimi_context_intent_added, ids.size)`. That is the first
**argument-carrying** string in this whole theme - the first slice happened to contain only
zero-argument ones, so the runtime-silent wrong-count trap is still unproven on this path and this
is where it will first be exercised. 590 lines unblocked by one hoist.

**The real highest-leverage seam is sharing, not IO.** Two screens are each blocked by one line that
hands a file or some text to the platform share sheet: `AimiProfileAdvisorScreen` calls
`startActivity(Intent.createChooser(...))` directly, and `AimiSupportPackageScreen` reaches it
through `AimiSupportPackageExporter` (143 lines, `Context` + `FileProvider` + `ZipOutputStream`).
That is 1 085 lines behind one interface, and it is the shape CLAUDE.md names: lift the platform
call out, keep the rule - the zip building is ordinary code, only the handoff is Android.

**It is also exactly the case CLAUDE.md says to ask about first.** An interface must be honourable on
every target it is given, and a share sheet that silently does nothing on iOS would be a feature the
user believes they have. So this is a decision, not a port, and this time that is said after
checking that the thing is really absent rather than inferring it from a search that found nothing.

### Second slice: the Pkpd chain, all three moved

`PkpdSettingsUi` (747), `AimiPkpdSettingsScreen` (181) and `AimiRuntimeHistoryCards` (514) all moved -
1 442 lines, nothing blocked. AIMI is now **91 `androidMain` / 430 `commonMain`** (was 96 / 425 at the
start of the Compose theme). Shapes converted: 153 `R.string` → `ApsStrings`, 3 `android.R.string` →
`CoreUiStrings`, 2 `@StringRes Int` parameters → `TextRef` (8 and 11 call sites), 2 `Dispatchers.IO` →
`aapsIoDispatcher`, 1 `LocalContext` removed by hoisting its single `getString` to a `val`.

Ordering by what unblocks what, rather than by line count, was right: `AimiPkpdSettingsScreen` could
only move after its 747-line same-package companion.

**The `aapsIoDispatcher` correction held**, checked first-hand: the Android actual *is* `Dispatchers.IO`,
so threading on the shipping target is unchanged. Behaviour-preserving, not a redesign.

**The argument-count trap was finally exercised, and survived - because it was checked by a parser, not
by eye.** 26 argument-carrying calls across the three files. The agent wrote a brace-matching parser
that splits each `stringResource(` argument list at top-level commas and compares the count against
`max(%N$)` over **every** XML in `res/values/` - which matters, because the AIMI strings live in
`aimi_strings.xml`, not `strings.xml`. 26 of 26 correct. One case the parser could not settle,
`stringResource(labelRes, count, percentOf(count, total))`, was resolved by hand against all 11
`HistoryCountRow` call sites. **This is now the standard for this theme**: a wrong count compiles and
fails at run time, so eyeballing it is not evidence.

**`--rerun` nearly let the slice through unverified.** The agent's first two runs reported
`testAndroidHostTest UP-TO-DATE`, `jvmTest UP-TO-DATE`, `compileKotlinIosArm64 UP-TO-DATE` - the flag
written once at the end of the task list is ignored for the tasks it was meant to cover. The skill
already documented this and the brief repeated it, and it was still got wrong twice. `BUILD SUCCESSFUL`
and `UP-TO-DATE` are indistinguishable in a grep for the result line, so the skill now carries an
explicit per-task grep as part of the gate rather than as advice.

### The unreachable expert tab - dead on this branch, and dead upstream too

`PkpdExpertSettingsContent` (~55 lines) has no caller. The agent rightly refused to delete it and
flagged it as a possible `TriggerBTDevice.devicesPaired()` case - a lost caller rather than obsolete
code. **Checked against `origin/dev_OAPSAIMI` (3dd0ca6477): it is dead there too, identically.** Both
branches route `ADVANCED` and `EXPERT` to `PkpdAdvancedSettingsContent`, the tab row offers only two
tabs, and nothing anywhere assigns `selectedLevel = PkpdSettingsLevel.EXPERT`. So the port is correct
to carry it across untouched, and this is **not** a migration regression.

It is, however, worth the owner's attention, because of what is stranded in it. The unreachable
function is the only editor for:

- `OApsAIMISmbTailDamping`, `OApsAIMISmbTailThreshold`, `OApsAIMISmbExerciseDamping`,
  `OApsAIMISmbLateFatDamping`
- `OApsAIMIRedCarpetRestoreThreshold`
- `OApsAIMIPriorityMaxIobFactor`, `OApsAIMIIobSurveillanceGuard`
- `OApsAIMIPeakGovernorEnabled` / `...LearnedWeight`, the three `OApsAIMIIsfFusion*` keys, and the
  three `OApsAIMIDynIsfTrajectory*` keys

Three of those names appear in open investigations in this project's notes - the shared-writer problem
on `OApsAIMISmbTailDamping`, RED_CARPET as a hypo contributor, and `PriorityMaxIobFactor` as one of the
keys TPO rewrites from inside a loop tick. The settings themselves are live and read by the algorithm;
only the screen that would show their stored value cannot be opened. Whether to wire the EXPERT tab
back is the owner's call, and it is a feature decision, not part of this port.

## 6aw. 2026-10-02: the Tpo chain - a seam built, and a lot that mostly did not move

AIMI is **90 `androidMain` / 432 `commonMain`**. Only `TpoUiSupport` (63 lines) moved, plus a new
31-line interface. The lot was briefed as ~1 866 lines and delivered 94, because the brief's
blocker list came from an import scan **for the seventh time** and was wrong in both directions
again. The compiler's verdict, from a probe that moved all three files unconverted and classified
92 errors, is the only number in this section worth trusting.

### The seam

`TpoNotifications` in `commonMain` with the two methods `TpoOrchestrator` actually calls, implemented
by the existing `TpoNotificationManager` in `androidMain` with `@ContributesBinding(AppScope::class)`.
Nothing about how a notification looks or behaves changed - same channels, same `NotificationCompat`
builder, same `PendingIntent`, same preference gate. `TpoEndReason` had to come with it; it was
declared at the bottom of `TpoNotificationManager.kt`, not in `TpoModels.kt`.

**There is no iOS implementation, and that is stated in the interface's KDoc rather than hidden.** On
iOS a TPO session would start and end without telling the user. The owner's rule is that a feature
should be visibly absent rather than silently dead, so this needs a decision before iOS ships.

**One wart to fix, found on review rather than reported.** Because `packTitleResId: Int` became a
`TextRef`, the notification manager now resolves that one label with `rh.gs(...)` while the other
**12** strings in the same class still use `context.getString(...)`. For a user with an AAPS language
override those two resolve differently, so one notification can mix two languages. The sibling
`AuditorNotificationManager` uses `context.getString` 7 times and `rh.gs` zero, so the house pattern
here is `context`. Three ways out: make the whole class use `rh` (consistent, but a wider behaviour
change than a port should make), duplicate the three-arm `packId -> R.string` `when` locally
(zero delta, but splits one mapping across two files), or leave it. **Owner's call.**

### What a rewrite onto the shared `NotificationManager` would really cost

Checked properly, because it is the obvious "do it right" move and it is not a like-for-like swap:

1. **`NotificationAction` cannot express TPO's intent.** It is `(buttonText: TextRef, action: () -> Unit)`
   - a button with an in-process lambda. TPO's is a *content* intent carrying
   `extra_navigate_route = "plugin_preferences/OpenAPSAIMIPlugin"`, which survives process death.
   `AndroidSystemNotificationPlatform` hardcodes `setContentIntent(notificationHolder().openAppIntent())`,
   and that builds a `TaskStackBuilder` to `MainActivity` **with no extras** - so a straight rewrite
   silently loses the deep link into the AIMI screen.
2. **No title field, and `bigText == text`.** TPO has a title, a one-line collapsed text and a
   different multi-line expanded text (up to 4 delta lines plus "N more changes").
3. **`NotificationId` is append-only** - the system notification id is the enum ordinal.
4. **Channels change audibly.** TPO owns `AIMI_TPO_PROTECTION` (DEFAULT, no vibration, no sound) and
   `AIMI_TPO_PROTECTION_ENDED` (HIGH). The shared platform posts everything on one HIGH channel, so
   the "started" notification gets louder and any per-channel settings the user made are orphaned.
5. Lost: `ic_shield`, `setOnlyAlertOnce(true)`, `setAutoCancel(true)`.
6. Gained: iOS works for free, plus swipe-dismissal tracking and expiry.

### Why the rest did not move

- **`AimiContextScreen`** (590): three `androidMain` collaborators totalling 1 481 lines -
  `ContextManager` (650, only `ConcurrentHashMap`, probably movable on its own),
  `HealthContextRepository` (403) and `AIMIPhysioContextStoreMTR` (428), the last two carrying
  `Context` + `Environment` + `File`, i.e. the same storage shape as `HormonitorViewerScreen`.
- **`TpoOrchestrator`** (192): the seam was necessary and nowhere near sufficient. `SP` is an
  androidMain interface in **another** module, `AiCoachingService` is 478 androidMain lines, and
  `TpoLlmValidator` is blocked by `org.json` - whose `JSONObject` is **interpolated straight into the
  LLM prompt** (`INPUT:\n$payload`), so swapping to kotlinx changes key order, spacing and number
  formatting in what the model reads. That is a prompt change, not a port.
- **`AimiControlCenterScreen`** (1 021): now blocked by **exactly one line**, `tpoOrchestrator:
  TpoOrchestrator` at line 63. The moment the orchestrator moves, this file is a pure `mv`.

Both screens had their string sweep done in place anyway (57 and 62 sites), which is behaviour
neutral, is covered by the gate, and leaves each one `mv` from done.

### Three lessons, all now in the skill

1. **`grep '^e: '` does not catch Kotlin errors on the Android path.** `:plugins:aps:compileAndroidMain`
   reports `Problem found: Kotlin compiler error` with a `Location:` line and prints no `e: ` at all.
   Two real logs in this session carry 4 and 2 such errors with `BUILD FAILED` and **zero** `e: `
   lines - the grep this skill prescribed would have called both clean. `BUILD FAILED` is the marker
   that never lies.
2. **A string whose argument is known only at callback time cannot be hoisted to a `val`.** That move
   works for zero-argument strings only. `context.getString(R.string.aimi_context_intent_added, ids.size)`
   has `ids.size` available only inside a nested non-Composable `onSend()`, and the surrounding
   `showSnackbar` **suspends** - the work after it is deliberately deferred, so restructuring into a
   `LaunchedEffect` would change observable behaviour. The answer is a `TextResolver` parameter and
   `textResolver.gs(ref, arg)`; `ResourceHelper` already extends it, so the call site passes `rh`.
3. **Order the string substitutions.** A blanket `s/R\.string\./ApsStrings./` turns
   `app.aaps.core.ui.R.string.back` into `app.aaps.core.ui.ApsStrings.back`. Do the fully-qualified
   and aliased forms first.

### Noticed, not touched

`AimiContextScreen` builds user-visible text by concatenation at lines 166 and 179
(`showSnackbar("$errorPrefix: ${e.message ?: ""}")`) - the CLAUDE.md anti-pattern. Pre-existing, needs
a new format-string resource, and is a feature decision rather than part of a port.

## 6ax. 2026-10-02: `SP` is not the blocker it looks like, and `org.json` splits in two

Two structural blockers were measured rather than assumed. One dissolves; the other is real but
smaller than it looked.

### `SP` blocks 11 AIMI files and none of them needs it

`SP` is the recurring "androidMain-only interface in another module" from 6at - nothing inside
`:plugins:aps` can move it. It is injected by **11 of the 90** remaining AIMI `androidMain` files,
and it is what stopped `TpoOrchestrator` (192 lines, which gates the 1 021-line
`AimiControlCenterScreen`) and `ContextManager` (650, which gates the 590-line `AimiContextScreen`).

But `SP` is **29 lines and all of it is the `@StringRes Int` overloads**. The string-keyed half
already lives in `KeyValueStore`, in `core/interfaces/commonMain`, and the interface says so itself:
*"The string keyed half moved to [KeyValueStore] so the preference layer could become common. These
overloads stayed behind because a resource id only exists on Android."*

Checked every `sp.` call in all 11 files, by reading them, not by counting: **zero resource-id
calls.** Every one passes a `String` - either `SomeKey.X.key` or a `private const val PREF_KEY_… =
"…"`. `KeyValueStore` is already injected directly from `commonMain` by `PreferencesImpl`,
`LocalImportExportPrefs` and `GoogleDriveProvider`, so it is bound and reachable.

So the fix is a type and an import, with no behaviour change at all. **A blocker that had stopped two
chains across two separate lots was a one-word substitution the whole time.** The lesson is the one
6av already states and this is its sharpest instance: the blocker was named from the *import* (`SP`),
and nobody read what the interface actually contained.

### `org.json` is two problems, and the house shim says which is which

`OrgJsonCompat` in `core/data/commonMain` replaces `org.json` accessors and is already used by 22
files. It does not solve everything, and its own KDoc draws the line precisely:

> Reading is safe to shim; writing changes the bytes on the wire.

That settles the `TpoLlmValidator` question from 6aw. Its `JSONObject` is **built** and interpolated
into the prompt (`INPUT:\n$payload`), so it is the writing case: key order, spacing and number
formatting would change what the language model reads. Not a port. The same question has to be asked
of `AiCoachingService` (478 lines) call by call, because it is the last thing between
`TpoOrchestrator` and a 1 021-line screen.

### Where the two remaining big chains actually end

- `AimiControlCenterScreen` (1 021) ← `TpoOrchestrator` (192) ← `AiCoachingService` (478) +
  `TpoLlmValidator` (182) ← **the org.json-in-a-prompt decision**.
- `AimiContextScreen` (590) ← `ContextManager` (650, freed by the `SP` swap) +
  `HealthContextRepository` (403) + `AIMIPhysioContextStoreMTR` (428) ← **the storage seam decision**
  (the same one `HormonitorViewerScreen` and `AimiSupportPackageScreen` need).

Both terminate at decisions already put to the owner. Everything mechanical in front of them is
being done; nothing further should be guessed past those two points.

## 6ay. 2026-10-02: the `SP` swap done, and every remaining chain traced to its end

### The swap

Ten AIMI files changed `private val sp: SP` to `private val sp: KeyValueStore`, with the matching
import, plus one KDoc `[SP]` link that would have stopped resolving once the import went. The
eleventh file only names `SP` in a fully qualified KDoc link, so it needed nothing. Gates after:
**1836 / 594 / 599, 0 failures, BUILD SUCCESSFUL**, all four tasks confirmed executed rather than
`UP-TO-DATE`. No behaviour change: same keys, same defaults, same order.

### `org.json` is not "read versus write" - it is "who reads the result"

6ax took `OrgJsonCompat`'s KDoc at its word: *"Reading is safe to shim; writing changes the bytes on
the wire."* Classifying `AiCoachingService` (478 lines) call by call shows the rule needs one more
turn. That file uses JSON for exactly two things: building the HTTP request body for OpenAI, Gemini,
DeepSeek and Claude, and parsing their responses. Its prompt is a `StringBuilder` of plain text and
**no JSON is interpolated into it**.

A request body is re-read by a JSON parser at the far end, which does not care about key order or
whitespace. A JSON string pasted into prompt text is read by the *model*, character by character. So:

- **`AiCoachingService` is portable with no decision** - both its write side and its read side are
  safe, because nothing it produces is read as prose.
- **`TpoLlmValidator` (182) is a real decision**, because there the JSON *is* the prompt.

That leaves `TpoOrchestrator` - and behind it the 1 021-line `AimiControlCenterScreen` - blocked by
one 182-line file and one question.

### A probe of the seven remaining "clean-looking" files

Moved all seven at once and let the compiler classify the 68 errors. None moved, and the shape is
almost entirely **dependency chains inside androidMain**, not platform APIs:
`AimiClinicalReportEngine` needs `AIMIPhysioManagerMTR`; `AIMIStepsManagerMTR` and
`AIMIPhysioPipelineWatchdogMTR` need the two steps sync services, `UnifiedActivityProviderMTR`,
`AIMIHealthConnectPermissionsHandlerMTR` and `HealthContextRepository`. The hubs are the Health
Connect and steps services, which are genuinely Android.

> **Correction, same day (6az):** naming `UnifiedActivityProviderMTR` as one of those hubs was wrong.
> It is 303 lines of source-priority rule code with a single Android call made twice, and it moved.
> The error was mine and it is the usual one in a new costume: the file was classified from the
> company it kept - it sat in `steps/` beside the sync services - rather than from what it contained.

**`AIMILLMPhysioAnalyzerMTR` (512 lines) produced exactly one error**, and a previous session had
already found it, written the reason into the file's own KDoc, and left it deliberately:

> `private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)`
> … The text it makes goes straight into the prompt the model reads, and it follows the phone's
> locale, so a shared replacement would change what is sent - a French phone writes `7,5` today and
> a shared version would write `7.5`.

So 512 lines sit behind one line, and that line is the same decision as `TpoLlmValidator`.

### The decision list, as it now stands

Three of the four open questions are **one question wearing three hats: what reaches a language
model must not change silently.**

1. **Prompt text formatting.** `AIMILLMPhysioAnalyzerMTR` (512 lines, locale decimal separator) and
   `TpoLlmValidator` (182 lines, JSON key order and spacing, which gates 1 021 more). Both change
   what the model reads.
2. **User-visible `String.format`/`Locale`** in three Compose screens - the `8.35` → `8.4` vs `8.3`
   rounding difference. Display only, no clinical path.
3. **A storage seam** for `HealthContextRepository`, `AIMIPhysioContextStoreMTR`,
   `HormonitorViewerScreen` and `AimiSupportPackageScreen` (`Environment` + `File` + directory
   discovery).
4. **A share seam** for `AimiProfileAdvisorScreen` and `AimiSupportPackageScreen` - verified absent
   by looking for the thing rather than inferring from a grep miss; `ImportExportPrefs` writes export
   files and is not a share sheet.
5. **`ContextManager`'s `ConcurrentHashMap`** (22 use sites, three internal coroutines plus the loop
   tick and a screen). **See the correction below: the "not atomic today" part of this was wrong.**
6. **The TPO notification resolver wart** from 6aw, and whether TPO should move to the shared
   notification stack at the cost of its two channels and its deep link.

Everything mechanical in front of these has now been done. Nothing further should be guessed past
them.

## 6az. 2026-10-02: the physio and steps hub map, and a hub that was not one

Three agents ran in parallel on disjoint file sets. This section is the physio/steps one.

### What moved

`UnifiedActivityProviderMTR` (303 lines), androidMain → commonMain. The diff is a rename plus three
lines: `import android.os.Looper` dropped, `aimiIsMainThread()` imported, and
`Looper.myLooper() == Looper.getMainLooper()` replaced at its two guard sites.

Also one line of dead code removed: `import android.content.Context` in `HealthContextRepository`,
which the file never used.

### The seam, and why it is a substitution rather than a decision

`expect fun aimiIsMainThread(): Boolean` in the module's own `commonMain`, with three actuals:
Android on `Looper` (the original check, unchanged), iOS on `NSThread.isMainThread()` (its direct
counterpart), and plain JVM returning `false` because a headless JVM draws no interface, so the
caller proceeds with the blocking read it wants.

This clears the bar the owner set - an interface must be honourable on every target that gets one -
because **every target answers with a real platform fact**, not with a stub that quietly disables a
feature. It is also the module's established pattern: `aimiWaitMs`, `aimiDeviceLanguage` and
`AimiExclusiveLock` are existing in-module `expect`/`actual` pairs, so no new module dependency and
no change to `core:interfaces`.

### The hub map, measured

| hub | genuinely Android | evidence |
|---|---|---|
| `UnifiedActivityProviderMTR` | **no** | one `Looper` call, twice - **moved** |
| `AIMIPhysioManagerMTR` | yes | it *is* a WorkManager scheduler - `PeriodicWorkRequestBuilder`, `Constraints`, `ExistingPeriodicWorkPolicy`, `BackoffPolicy`, `NetworkType`, plus `Context`, across about half the class |
| `AIMIHealthConnectSyncServiceMTR` | yes | `androidx.health.connect.client.*` + WorkManager + `java.util.Timer` |
| `AIMIHealthConnectPermissionsHandlerMTR` | yes | `HealthConnectClient`, `PermissionController`, `Intent` |
| `AIMIPhoneStepsSyncServiceMTR` | yes | `StepService` (hardware sensor) + `Timer` |
| `AIMIPhoneStepsProviderMTR` | no imports, yes in effect | zero `android.*`; all 12 errors are `StepService`. **The architecture is already right** - its interface `AIMIStepsProviderMTR` is in commonMain and this is the Android implementation. Nothing to move. |
| `StepService` | yes - the true hub | `object StepService : SensorEventListener` |
| `HealthContextRepository` | **no** on `Context` (dead import), yes on its graph | blocked by `AIMIPhysioDataRepositoryMTR` (12 Health Connect imports). Its own shapes all have house answers. |
| `AIMIPhysioContextStoreMTR` | yes | `Environment` + `File` + `ReentrantReadWriteLock` - this is the open storage seam |

### Two of my own claims were wrong, and both in the same way

1. **`UnifiedActivityProviderMTR` is not an Android hub.** I wrote that in 6ay and it is corrected
   in place above. The file was classified by the company it kept - it sits in `steps/` next to the
   sync services - rather than by what it contains.
2. **`HealthContextRepository` does not carry `android.content.Context`.** I repeated that from an
   earlier report without opening the file. It carries the *import* and nothing else. The "real
   question" I built on top of it did not exist.

Both are the campaign's recurring failure in a new costume. The rule that keeps being relearned is
narrow and worth stating exactly: **a file's blocker is a property of its body, not of its imports,
its folder, or what someone said about it last.**

A fourth trap, new this time and the mirror image of the known ones: `@Volatile` in
`AIMIPhysioPipelineWatchdogMTR` is written with **no import at all**, resolving implicitly to
`kotlin.jvm.Volatile`. An import scan cannot see a missing import. Only the iOS compiler finds it.

### Two seams proposed and not built, one of them recommended against

- **A physio-status port** would free `AimiClinicalReportEngine` (151 lines of pure maths - LBGI,
  HBGI, CV, GMI) for the cost of one method, `getStatus()`. The file reads exactly one key from it,
  `status["isEnabled"]`. On iOS, with no Health Connect, "physio is off" is a true answer, not a
  hollow one. Worth asking. *Separately: that file carries a comment admitting it works around
  `PhysioManager` not exposing `getLastContext()`, and hardcodes `cyclePhase` to `"UNKNOWN"`.*
- **A steps-sync coordinator port** would free `AIMIStepsManagerMTR` (122 lines). **Recommended
  against**: `start`/`stop`/`getSyncStatus`/`triggerManualSync` over Health Connect and a hardware
  pedometer is exactly the contract iOS cannot honour, and a user who believes step data is syncing
  when it is not is the safety case the owner's rule names. iOS has `CMPedometer`, but wiring that is
  a feature, not a port.

### One improvement to the gate, adopted

The fast pre-gate in the skill was `compileAndroidMain` + `compileKotlinIosArm64`. That misses a
broken or missing `jvmMain` actual, and this module has a `jvmMain` source set. Add
`compileKotlinJvm`.

## 6ba. 2026-10-02: three parallel lots, and a rounding defect under all of AIMI

The other two of the three parallel lots. AIMI is now **88 `androidMain` / 436 `commonMain`**.

### `AuditorOrchestrator` (724 lines) moved, and its blocker was imaginary

The whole diff is 15 insertions, 11 deletions: `Dispatchers.IO` → `aapsIoDispatcher`, `@Volatile` →
`kotlin.concurrent.Volatile`, six `format` calls, and one constructor parameter.

`AIMIInsulinDecisionAdapterMTR` (653 androidMain lines, `Looper` + atomics + `runBlocking`, hanging
off `AIMIPhysioContextStoreMTR`) looked like a hard dependency. It was not. The only thing the
orchestrator wanted from it was:

```kotlin
fun getLatestSnapshot(): HealthContextSnapshot = repo.getLastSnapshot()
```

`repo` is `AimiHealthContext` - **already a commonMain port**, already declaring `getLastSnapshot()`,
already returning the commonMain `HealthContextSnapshot`. The orchestrator was reaching an interface
it could inject directly, through 653 lines of Android. Swapping the parameter is zero delta:
`AimiHealthContext` has exactly one implementation, `HealthContextRepository`, bound
`@SingleIn(AppScope::class)`, which is the same singleton the adapter held.

**Eighth time a named blocker dissolved on measurement, and the first that needed no probe move -
reading the one method was enough.**

### `AiCoachingService` (478 lines) moved, and the brief's instruction was wrong

The JSON split held: four request bodies written, three responses parsed, no JSON anywhere near the
prompt. But **the instruction to read with `OrgJsonCompat` was wrong and was correctly refused.**
`OrgJsonCompat` only has `opt*` accessors, which return `""` for a missing key. The old code used the
**throwing** `getJSONArray`/`getString` family, and the throw is load-bearing: it is what lands in the
`catch` that produces `aimi_coach_svc_read_error_openai` and its siblings. With the shim a malformed
reply would have come back as an empty coaching answer instead of an error the user can see.

The right house pattern was already in this module: `AuditorAIService.extractContentText` uses
`getValue(...).jsonArray[0].jsonObject...jsonPrimitive.content`, which raises at every step exactly as
the `org.json` getters did, and its KDoc says so. **`OrgJsonCompat` is for readers that used `opt*`;
a reader that used the throwing getters needs `getValue`/`jsonPrimitive`.** That distinction belongs
beside the "reading is safe to shim" line, which is true but not the whole rule.

Two more things worth keeping:

- **`aimiDeviceLanguage()` is a trap for prompt text.** It returns the ISO code (`fr`); the prompt
  line `"Respond in '$deviceLang'."` needs the display name (`French`). Reusing it blindly would have
  changed what the model reads. A second `expect`, `aimiDeviceLanguageName()`, was added.
- **`:plugins:aps` has its own `jvmMain` with duplicated actuals, so a new `expect` needs four halves,
  not two.** The first full gate failed on exactly that, which the Android+iOS pre-gate could not see.
  This is why `compileKotlinJvm` is now part of the pre-gate.

### The rounding defect, which is bigger than any of the three lots

`AimiFmt.kt` defines `aimiFmt0/1/2/4` on `NumberFormat.INTEGER`, `DECIMAL_1`, `DECIMAL_2` and
`withDecimals(4)`. All of those take `NumberFormat`'s default rounding, which is **HALF_EVEN**
(`NumberFormat.kt:31`). `String.format("%.Nf", x)` - what they replace, and what `dev_OAPSAIMI` runs -
is **HALF_UP**. There are **355** `aimiFmt*` call sites in `:plugins:aps`.

The repo already contains the right answer, two plugins away: `AdaptiveSmoothingPlugin` and
`UnscentedKalmanFilterPlugin` both use `NumberFormat.withDecimalsHalfUp(decimals)`, with a KDoc that
says in as many words *"`withDecimalsHalfUp` matches what `%.Nf` did: exactly N decimals, rounded
half up."* AIMI's own helpers did not get that memo.

**And the shared module's own documentation argues the problem away, incorrectly.**
`NumberRounding.kt:6-9` claims a tie is "only reachable when the halfway point is exactly
representable as a `Double`", that whole numbers have reachable ties, but that *"rounding to one
decimal does not: a tie there would have to be `(2k+1)/20`, and the factor of 5 in the denominator
means no `Double` ever lands on it."*

That arithmetic is wrong. `(2k+1)/20` reduces whenever `2k+1` is a multiple of 5: `5/20 = 1/4 = 0.25`
exactly. So **0.25, 0.75, 1.25, 1.75, 2.25 are all exactly representable one-decimal ties**, and at
two decimals `0.125`, `0.375`, `0.625` likewise. Checked against real IEEE doubles, not reasoned
about. An agent in this session read that KDoc and concluded the choice was "moot here"; it is not.

What it costs, on values AIMI actually prints:

| | `%.Nf` (today, and on `dev_OAPSAIMI`) | `aimiFmt*` (this branch) |
|---|---|---|
| `aimiFmt0(2.5)` | `3` | `2` |
| `aimiFmt0(120.5)` | `121` | `120` |
| `aimiFmt1(0.25)` | `0.3` | `0.2` |
| `aimiFmt1(1.25)` | `1.3` | `1.2` |
| `aimiFmt2(0.125)` | `0.13` | `0.12` |

Insulin doses land on `.25` and `.5` constantly. And this is **not confined to logs** despite the
helper's KDoc saying "for AIMI logs": `AuditorDataCollector:257-258` uses `aimiFmt2` for text that
feeds the auditor's LLM prompt, and `OrefAnalysisReport` does the same.

**This is a parity break the migration introduced**, against the owner's stated rule, at 355 sites,
with a known-correct one-line-per-helper fix. It is the owner's call because it changes rendered
numbers across the plugin, but there is no argument for keeping HALF_EVEN: nothing chose it, it is a
default that was inherited by not being named.

## 6bb. 2026-10-02: the rounding correction applied, and the decision list with what iOS can really do

### Applied

`AimiFmt.kt` now builds every helper on `NumberFormat.withDecimalsHalfUp(n)` instead of the
`INTEGER`/`DECIMAL_1`/`DECIMAL_2`/`withDecimals(4)` constants. Those constants differ from
`withDecimalsHalfUp(n)` in exactly one field - `rounding` - so digit counts are unchanged and only
the tie behaviour moves, back to what `String.format("%.Nf", x)` does on the shipping build.

`NumberRounding`'s KDoc is corrected: it claimed one decimal ties were unreachable because a tie
would be `(2k+1)/20` and "the factor of 5 in the denominator means no `Double` ever lands on it".
The factor cancels when `2k+1` is a multiple of 5, so `5/20 = 1/4` and `0.25`, `0.75`, `1.25`,
`1.75` are all exact one decimal ties. `NumberFormat`'s own KDoc now says half-even is the right
default only for code replacing a `DecimalFormat`, and points `%.Nf` callers at
`withDecimalsHalfUp`.

**Trio reached the same rule independently.** Its JS→Swift migration guide, on the same class of
port, says: *"When an algorithm rounds, match the JS convention exactly (often `floor(x + 0.5)` in
JS code)."* The discipline is not an AIMI preference; it is what a sibling AID project found it had
to do.

### What cannot be ported, named plainly

These are not blocked on a decision. They are Android capabilities with no shared form. On iOS each
would be a **new implementation against a different API**, which is a feature, not a port, and the
owner's rule says the feature should be visibly absent until someone builds it.

| what | Android | the iOS counterpart, if someone builds it |
|---|---|---|
| Health Connect sync + permissions | `androidx.health.connect.client.*` | HealthKit - a different API and permission model. Trio has a HealthKit service, so it is proven possible, but it is separate code. |
| `StepService` | `SensorEventListener`, hardware pedometer | `CMPedometer` |
| `AIMIPhysioManagerMTR` scheduling | WorkManager, about half the class | `BGTaskScheduler`. **Not equivalent**: iOS background execution is far more restricted than a `PeriodicWorkRequest`, so the schedule itself would have to be redesigned, not translated. |
| `AimiMealAdvisorScreen` capture | `android.hardware.camera2`, 762 lines | AVFoundation |
| `AimiCognitiveOrefCoachCards` | Vico charting | any Compose Multiplatform chart - but that is a rewrite of the cards |
| SOS and Health Connect permission screens | Android runtime permissions | a different consent model entirely |
| external storage discovery | `Environment.getExternalStorageDirectory()` | **iOS has no shared external storage.** See the storage decision below. |

### The decisions, each with what it unblocks and what iOS can honour

**1. What reaches a language model.** Unblocks `AIMILLMPhysioAnalyzerMTR` (512 lines, one line of
locale formatting) and `TpoLlmValidator` (182, an `org.json` object interpolated into the prompt),
and behind the second one `TpoOrchestrator` and `AimiControlCenterScreen` (1 021). About 1 715 lines
behind one question.

The branch is **already inconsistent**: `AuditorDataCollector` and `OrefAnalysisReport` use
`aimiFmt2` in text that feeds a prompt, so part of the system already sends the model a dot while
`AIMILLMPhysioAnalyzerMTR` sends a French phone a comma. A prompt that varies with the device locale
is a reproducibility problem rather than a feature. Recommended: accept the change, and capture one
real before/after prompt pair to put in the record.

**2. The storage seam.** Blocks `HealthContextRepository` (403), `AIMIPhysioContextStoreMTR` (428),
`HormonitorViewerScreen` (324) and `AimiSupportPackageScreen` (162).

This question splits in two, and only one half is a real problem:

- **The app's own data directory is portable.** Trio writes its algorithm artefacts - `monitor/*.json`,
  `settings/profile.json` - into the app's documents directory on iOS, which is exactly the shape
  `AimiStorage` already has. A sibling AID app keeps the same kind of data the same way.
- **Scanning several shared locations is not.** `HormonitorViewerScreen` looks in
  `Environment.getExternalStorageDirectory()/Documents/AAPS` and in `getExternalFilesDir(null)/AAPS`.
  iOS has no shared external storage; an app sees its own container, which it may expose to the Files
  app. There is nothing to point the second path at.

Recommended: define the seam as the app's own directory only, keep multi-location discovery as an
Android-only extra, and let the viewer show the app directory on iOS rather than pretending to search.

**3. The share seam.** Blocks `AimiProfileAdvisorScreen` (923) and `AimiSupportPackageScreen` (162).
iOS has `UIActivityViewController`, a direct counterpart of the Android chooser. Honourable on both.
Recommended: build it with a real iOS implementation.

**4. `ContextManager`'s `ConcurrentHashMap`** (650 lines, unblocking `AimiContextScreen`, 590).
22 use sites, three internal coroutines plus the loop tick and a screen. Recommended: do it as its
own change, with tests, not folded into a move.

> **Correction, same day.** This entry, and item 5 of the list in 6ax, both said the change would
> "make atomic two compound operations that are not atomic today (`filter` then `remove`)".
> **That was wrong, and it was the stated justification for touching live dosing-adjacent code.**
> Both methods carried `@Synchronized`, as did five others in the class - seven in all. The claim
> came from a grep for `synchronized`, lower case, which cannot match `@Synchronized`.
>
> It was settled by measurement, not argument: the new tests were run against the **original**
> file and passed 4/4, then against a deliberately re-split version and failed 2. So they are
> regression tests that pin an invariant, not proof of a defect repaired.
>
> What the lock does genuinely close is smaller and real: `addIntent` is a `suspend fun` and
> therefore **could not** carry `@Synchronized`, so its `nextId++` read-then-write raced with the
> synchronized `addPreset`. Two adds in the same millisecond could take the same id and the second
> would silently replace the first - the user adds two contexts and sees one. The agent could not
> make that fail in 600 rounds and said so rather than claiming it had.

**5. The notification resolver, now found twice** - `TpoNotificationManager` and
`AuditorReportFormatter`. One label resolves through `rh`, its neighbours through `context`, so one
notification can mix two languages for a user with a language override. The house pattern in AIMI
notification managers is `context` (`AuditorNotificationManager`: 7 uses, zero `rh`). Recommended:
decide once for both, and the zero-delta choice is `context`.

**6. TPO onto the shared notification stack.** Deferred, with the costs now known: `NotificationAction`
carries a lambda, not a `PendingIntent`, and `AndroidSystemNotificationPlatform` hardcodes an intent
to `MainActivity` **with no extras**, so the deep link into the AIMI screen is lost; TPO's two
channels collapse into one at `IMPORTANCE_HIGH`, so the quiet "started" notification gets louder.
Gained: iOS works for free. Recommended: not until `AapsNotification` can carry a route.

## 6bc. 2026-10-02: the owner's answers, and the storage seam built under them

### What the owner decided

- **Port the corrections.** Done in 6bb.
- **Storage: do not change the Android directory - earlier versions of the app depend on that
  folder. Give iOS its own dedicated directory.** This is a hard constraint, not a preference.
- **Sharing: if a specific iOS implementation is needed, write it.**
- **The concurrent map: go ahead.**
- **The notification resolver: use what is planned and works for KMP, meaning Android and iOS.**
  That reverses the recommendation in 6bb item 5. The owner is right and the reasoning is the
  campaign's own: `context.getString` is Android-only, so choosing it would have been choosing
  against the port. The shared path is `TextResolver`, which `ResourceHelper` already extends.
  It costs one real behaviour change, stated plainly: for a user with an AAPS language override the
  notification text now follows the AAPS language rather than the system one, as the rest of the app
  does.

### The storage seam

A new interface, `AimiStudyLocations` in the module's `commonMain`, with one method,
`studyDirectories(): List<AimiPath>`, and an Android and an iOS binding. **Not** a method on
`AimiStorage`: that interface deliberately has no iOS implementation, because a stub that wrote
nowhere would leave the learning loops looking alive. "Which folders may hold this file" is read
only and has an honest iOS answer, so it lives apart.

**An interface needs no `jvmMain` half**, unlike an `expect`. The four-halves rule from 6ba applies
to `expect`/`actual` only. The residual risk moves from compilation to DI: a future JVM graph
materialising the physio store would need a JVM binding, and there is no JVM graph in this module.

Android returns today's two paths, in today's order, with both `runCatching` wrappers kept, so a
device with no external volume still drops the entry and carries on. Verified against the committed
file line by line. iOS returns `<app container>/Documents/AAPS`, created on demand - a **subfolder**,
not `Documents` itself, because `AppDatabaseBuilder.ios.kt` puts the app database directly in
`Documents` and the support package is built by walking a whole directory.

**The trap worth recording**: the obvious simplification, `listOf(storage.directory())`, would have
broken the owner's constraint **with no compiler error at all**. `AimiStorage.directory()` is
`AimiStorageHelper`'s three-tier *write* policy - it requires `canWrite` and silently falls back to
app-scoped or internal storage. The viewer's first candidate is `Documents/AAPS` unconditionally.
The two agree on a healthy device and diverge on a broken one, which is exactly when a user needs
their files found.

`AIMIPhysioContextStoreMTR` (428 lines) moved. `AimiStorage` gained `canWrite`, purely so a
diagnostic log line could stay byte-identical. Gates: 1836 / 594 / 599, 0 failures.

### `HormonitorViewerScreen` stayed, and the refusal was right

`DateUtil` has no equivalent for either format the viewer uses, and both substitutes would be
**visible on screen**:

- `"EEE d MMM"`: the nearest build is `dayNameString + dayString + monthString`, but
  `DateUtilImpl.dayString` is `"dd"` - zero padded - so the 7th would render `07`. It also means
  joining three pieces of user-facing text in code, which the house rules forbid.
- `"HH:mm"`: `DateUtilImpl.timeString` is `if (is24Hour()) "HH:mm" else "hh:mm a"`, so every user on
  a 12-hour device would start seeing `2:30 PM` where they see `14:30` today.

There is a loophole - `dayNameString(mills, pattern)` forwards its pattern straight through, so
`dayNameString(mills, "EEE d MMM")` would literally work - and it was correctly not taken. The clean
fix is a general `format(mills, pattern)` on the `DateUtil` interface, which is a `core:interfaces`
decision. The screen is also blocked by `HormonitorReader` (372 lines, `RandomAccessFile`).

### iOS app configuration that no Kotlin change can supply

The iOS folder is only visible to the user in the Files app if the Xcode target's `Info.plist`
carries `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace`. Reads and writes work
without them; the user simply never sees the files. To be set when an iOS target exists.

### The sharing seam, with a real iOS implementation

`AimiSharing` in `commonMain`, interface plus Metro bindings on four source sets. **Not**
`expect`/`actual`, because the Android side needs an injected `Context` and an `expect fun` signature
cannot name an Android type - the same reason `TpoNotifications` took this shape.

**The sketch in the brief was wrong, and following it would have deleted user-visible content.** It
had `shareText(text, chooserTitle)`. Both real call sites also set `Intent.EXTRA_SUBJECT`, and the ZIP
path sets `EXTRA_TEXT` *next to* the attachment, a covering message. The shipped shape is
`shareText(text, subject, chooserTitle)` and
`shareFile(path, mimeType, subject, text, chooserTitle)`. The lesson is the one the campaign keeps
paying for, in its interface-design costume: **shape a seam from the call sites, never from a sketch.**

**One genuine Android behaviour change, and it is not cosmetic.** Both call sites previously started
the chooser from the Compose `LocalContext`, which is the Activity. The binding receives the
**application** context, and `startActivity` from outside an Activity throws without
`FLAG_ACTIVITY_NEW_TASK`. The flag is now on both paths; it was already on the ZIP path, which ships
and works. So the text share of the Profile Advisor would have crashed on first tap without it.
**This is the one thing in the lot that wants a tap on a real device before anyone calls it done.**

The iOS half is `UIActivityViewController`, and three details in it are load-bearing:

- The root-view-controller walk is this repo's existing production pattern (`IosAuthBrowser` uses the
  same ten lines for `SFSafariViewController`). If it cannot find a host, neither can the Google
  sign-in that already ships.
- **The iPad popover anchor is set.** Without it this is a hard crash on iPad, not a layout glitch.
- The subject goes through `UIActivityItemSource.subjectForActivityType`, not the widespread
  `setValue(subject, forKey: "subject")` KVC trick, which raises `NSUnknownKeyException` if the key
  is ever withdrawn.

**Kotlin/Native trap worth keeping**: implementing `UIActivityItemSourceProtocol` fails with
`Conflicting overloads`, because `itemForActivityType:` and `subjectForActivityType:` are two
Objective-C selectors that project onto one Kotlin signature - Kotlin ignores parameter names. The
fix is `@ObjCSignatureOverride` on both. It is easy to read that error as "iOS cannot do this" and
reach for the KVC hack, which would be a latent crash.

**Nobody has seen the iOS sheet appear.** It compiles and links for `iosArm64`, and no iOS code calls
it yet, because both callers are still androidMain. Reasoned, not observed.

Moved: `AimiSupportPackageScreen` (162 lines). Its nested `Result` type became a top-level
`AimiSupportPackageResult` carrying an `AimiPath` instead of a `java.io.File`, which is what freed it.

**What still blocks the other two, named by the compiler rather than guessed:**

- `AimiSupportPackageExporter` (143): a ZIP seam (`java.util.zip`), a scratch-file location
  (`context.cacheDir` - deliberately *outside* the AIMI directory, so not the storage seam's
  question), `AimiDiagnosticsManager`, and `java.util.Date`.
- `AimiProfileAdvisorScreen` (923): 227 errors of which four are structural - `AimiAdvisorService`
  (androidMain, the screen's whole data source), `ResourceHelper` → `TextResolver`, and
  **`assetContext`**, which loads a bundled ML asset through a real Android `Context`. That last one
  is an asset-loading seam, not a rename, and it is the interesting one.

Gates after storage + sharing: **1836 / 594 / 599, 0 failures, BUILD SUCCESSFUL**, all five tasks
confirmed executed.

### The lock and the resolver, and a claim of mine that measurement overturned

**`ContextManager` did not move.** Its map is now a plain `LinkedHashMap` behind one `AapsLock`,
`removeByType` takes a `KClass` instead of a `java.lang.Class`, seven `@Synchronized` annotations are
gone (the lock replaces the monitor and `@Synchronized` is JVM only), and `Dispatchers.IO` is
`aapsIoDispatcher`. The one remaining blocker is `org.json` in `saveToStorage`/`loadFromStorage`, and
it was **correctly left alone**: that is a live persistence format, and the read path's throwing
`getString`/`getLong` is what makes the per-intent `catch` *skip* a corrupt entry. `opt*Compat`
returns a fallback instead, so a naive swap would silently restore corrupt intents with default
values rather than dropping them. Same distinction as `AiCoachingService` in 6ba, in a place where
the cost is stored user data rather than an error message.

The locking discipline used throughout: **take the lock, mutate or copy, release, then do the slow
work.** No lock is held across a suspension, across a call into `KeyValueStore`, or across a
`notifyPatientStateChanged`.

**The resolver lot went wider than briefed, correctly.** Three files, not two: `TpoNotificationManager`
(12 calls), `AuditorReportFormatter` (19 sites / 17 ids - the brief said 14) and
`AuditorNotificationManager` (7). The third was not in the brief and had to be: it builds the
**title** of the same notification whose **body** the formatter builds, so converting only the
formatter would have left that one notification still mixing two languages - the exact defect being
fixed. 38 calls verified by script before and after. `CoreUiStrings` turned out not to be needed at
all; neither file references a core-ui string.

`AuditorReportFormatter` still cannot move: `Context` is gone, but `AuditorUIState` is in
`androidMain`.

**Behaviour change for the release notes**: for a user with an AAPS language override, these
notifications now follow the AAPS language rather than the system one. Confirmed through
`AppAndroidBindings.kt:54`, where `TextResolver` is bound to `ResourceHelperImpl`, which resolves via
`localizedContext`. It is what the rest of the app does and the only way title, body and pack label
can agree - but it is not a no-op.

Gates: **1840 / 594 / 599**, 0 failures. The four new tests are
`ContextManagerAtomicityTest`.

### The correction, and why it is the one to remember

The brief for this lot asserted that two compound operations in `ContextManager` were not atomic, and
that assertion was **the stated justification for touching code one step from the pump**. It was
wrong: both methods carried `@Synchronized`, as did five others. The claim came from a grep for
`synchronized`, lower case, which cannot match `@Synchronized`.

It was settled by measurement rather than by argument - the new tests run against the **original**
file pass 4/4, and against a deliberately re-split version fail 2 - which is the only reason the
error did not survive into the record as a fixed defect.

What the lock genuinely closes is smaller and real: `addIntent` is a `suspend fun` and so **could not**
carry `@Synchronized`, leaving its `nextId++` racing the synchronized `addPreset`. Two adds in the
same millisecond could take the same id, and the second would silently replace the first - the user
adds two contexts and sees one. 600 rounds did not reproduce it, and the agent said so instead of
claiming a fix it had not demonstrated.

**The pattern, stated once for the whole campaign:** every wrong call in this migration has been a
search that matched one spelling of a thing and was read as proof of absence - `^import android`
missing `androidx`, a bare name hiding a same-package type, `java.lang` needing no import, `@Volatile`
needing none either, a generated `register(` that exists only after a build, `aimiDeviceLanguage`
answering a different question than its name suggests, and now `synchronized` not matching
`@Synchronized`. **A grep that finds nothing is a hypothesis.** The compiler, a test, or reading the
file is what settles it.

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
