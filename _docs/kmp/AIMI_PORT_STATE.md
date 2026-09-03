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

**All 200 of those `.put(` calls live inside one function: `toMedicalJson(): String`, lines 680-1405.
Zero are outside it.** The whole `org.json` surface of an 18,886-line file is a self-contained,
write-only, 726-line subsystem that builds one string.

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
consumer that distinguishes "absent" from "null", so pick one deliberately and apply it to all 61.

### Why this was not obvious

Both failed attempts assumed the boundary could be adapted - retype the fields, or convert at the
call sites. Neither works, because the mismatch runs in both directions through the same data paths.
Measuring first would have shown that the mismatch is not spread through the file at all: it is
concentrated in one function, and the rest of DB2 never touches JSON.

---

## 7. Start here tomorrow

1. **Convert `OpenAPSAIMIPlugin.kt` (parked) off javax to Metro.** H1. Do this before any port
   touches it.
2. **Lot 5: rewire `AimiStorageHelper`'s consumers to `AimiStorage`.** The seam exists; this removes
   blocker one without moving the file.
3. **Decide on `AimiHormonitorStudyExporterMTR`**: six seams, or stub the import and press on to
   `DetermineBasalAIMI2`. Recommend the stub - it is a study exporter, not a dosing path.
4. **Then `DetermineBasalAIMI2` itself.** 18,886 lines, of which only **244 touch Android**: 133
   `context.getString`, 116 `JSONObject`, 107 `Locale.US` with 33 `String.format`, 26 atomics. The
   `aimiFmt1` seam (lot 1), the `AimiStorage` seam (lot 2) and `AapsLock` already cover part of that
   list. Note upstream **dropped its own `org.json` port** in `eb6f17f494` - check what it did
   instead before writing 116 conversions by hand.
5. **Keep the two-baseline numeric check on every lot.** It is cheap and it is the only gate that has
   caught anything.

One process note for whoever runs the next session: the pipeline of five agents (definer, designer,
coder, controller, committer) earned its cost. In every lot, a later stage caught a real error an
earlier stage had missed - a spec that claimed no `String.format` where there were four, a dependency
closure naming the wrong companions, a "mechanical" swap that would have loaded a forever-growing CSV
into memory on every loop tick. A single pass would have shipped each of those.
