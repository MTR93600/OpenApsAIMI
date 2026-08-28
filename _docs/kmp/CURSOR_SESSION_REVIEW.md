# Review of the Cursor migration session

Written 2026-08-28, on branch `kmp-aimi-migration-study`.
Companion to [AIMI_KMP_MIGRATION_STUDY.md](AIMI_KMP_MIGRATION_STUDY.md), which is the baseline this
session was working from.

Method note: five review agents were dispatched; most were cut short by a spend limit. Every finding
below that survives is one I verified myself with direct commands, and the two most alarming things
the dying agents reported are addressed explicitly - **one of them was a false alarm.**

---

## 1. Verdict

**Real progress, one broken build, and no integration yet.**

- **38 486 lines of AIMI code compile for iOS.** Verified, not claimed: the Kotlin/Native tasks
  executed (none `SKIPPED` or `NO-SOURCE`) and klib manifests exist for `plugins/aps` in both
  `iosArm64` and `iosSimulatorArm64`. This is the first time AIMI logic has ever built for Apple.
- **The Android app does not build.** `:implementation:compileAndroidMain` fails. The cause is a
  single missing import introduced by this session - see §3. This is the one thing to fix first.
- **AIMI is not wired into anything.** `DetermineBasalAIMI2.kt` and `OpenAPSAIMIPlugin.kt` are not in
  any source set, and the plugin registry has no AIMI entry. Nothing calls the ported code.
- **The tests that cover it cannot run**, and there are only 21 of them for 40 026 lines.

So the honest summary: the session proved the hard technical premise of the study - AIMI's maths does
compile for iOS - and left the tree in a state where the Android app cannot be built or tested.

---

## 2. What actually happened

Not 24 hours of work: **40 commits authored `mtr93600` across 2026-08-24 to 08-28**. The other 294
commits in the range are merges of Milos's `kmp` and his new `ios` branch.

34 "lots" (A → AH), each peeling files into `commonMain`. **Two lots failed** - V and AA, both
recorded as "blocked after dest-type leftovers ran out", which is the peel order stalling on a type
that is still parked. Neither has a report; reviews exist for L-Z except V, and none from AA onward.

### AIMI source now lives in four places

| # | location | volume | role |
|---|---|---:|---|
| 1 | `dev_OAPSAIMI` @ `1ae418e106` | 441 files / 102 354 LOC | source of truth |
| 2 | `plugins/aps/src/commonMain/.../openAPSAIMI/` | **286 files / 38 486 LOC** | the peel target |
| 3 | `_docs/kmp/staging/openAPSAIMI-android-wip/` | **324 files, committed to git** | parked Android dump |
| 4 | `:plugins:aimi-contracts / -engine / -io / -learning / -testkit` | **16 files / 645 LOC** | clean-room engine |

Two strategies are running in parallel with a 60× size difference between them. That needs a
decision, not drift - see §7.

### The clean-room track is architecturally right

`:plugins:aimi-engine` declares `binaries.framework { baseName = "AimiEngine"; isStatic = true }` -
exactly the XCFramework path from §8 Phase 3 of the study. It is policed by
`plugins/aimi-domain-import-check.gradle.kts`, a custom Gradle task banning `android.`, `androidx.`,
`dagger.`, `javax.inject`, `app.aaps.database`, **`app.aaps.core.`**, `java.io.File`, `okhttp3.`,
`retrofit2.`, `org.json.` and even **`kotlinx.coroutines.`**. That is stricter than Milos's rule - a
pure domain with no dependency on `core` at all. Good mechanism. The open question is whether
645 lines can grow to 102 000 under that ban.

---

## 3. The Android build is broken - exact cause

```
> Task :implementation:compileAndroidMain FAILED
Unresolved reference 'runBlocking'.
  implementation/src/androidMain/kotlin/app/aaps/implementation/stats/TirCalculatorImpl.kt:119
```

Commit `9a9292513d` ("Port freeze AIMI keys, TIR helpers and APSResult AIMI fields onto kmp") added
`calculateHour` and `calculateDaily`, both using `runBlocking`, and added
`import app.aaps.core.data.time.T` - **but not `import kotlinx.coroutines.runBlocking`**. Milos's
prior version of the file contained no `runBlocking` at all, so the omission is this session's.

Two follow-on errors cascade from it (the suspend call and an ambiguous `iterator()`); all six
reported compiler errors come from this one missing line. **The fix is one import.**

The process point matters more than the fix. `.cursor/rules/testing.mdc` says, in its own words:

> *"Never declare work complete because the code looks correct."* … *"compile the smallest affected
> module; run the relevant tests for that module."*

That step was skipped, or its failure was not acted on. The rule was right; it was not followed.

### A design concern in the same commit

The added KDoc is candid: *"⚠️ ASYNC IMPACT: freeze AIMI called these from a sync tick. Persistence
is suspend, so this blocks like the freeze `runBlocking` wrappers."* Blocking calls on a dosing tick
are carried-over behaviour rather than a new defect, but they are now in a module that is becoming
multiplatform, and `runBlocking` is exactly what will not survive the iOS BLE wake window described
in §11.3 of the study. Worth a ticket, not a revert.

---

## 4. The scariest reported finding was a false alarm

A review agent reported, just before being cut off:

> *"`AdaptivePkPdEstimator` changed 26 numeric literals during a peel."*

**That is wrong, and it matters that it is wrong** - a changed constant in PKPD code would be the
worst possible outcome. Verified directly: every numeric literal in the peeled file was extracted and
diffed against the `dev_OAPSAIMI` original. The only difference is **one extra occurrence of the
literal `1`**, which is `acceptedUpdateCount += 1` replacing `acceptedUpdateCount.incrementAndGet()`.
A counter, not a dose.

The rest of that file's diff is a legitimate concurrency rewrite: `AtomicReference` / `AtomicLong` →
`AapsLock` + plain fields, which is Milos's own seam. The agent appears to have counted diff lines
containing digits rather than changed constants.

**Nothing in this review found a changed dosing constant.** That is a real result, and it should be
stated as clearly as a defect would have been. It is not a full clearance either - only the files I
verified are cleared, and the systematic 286-file numeric audit did not finish. §7 keeps it on the list.

---

## 5. The staging dump has diverged - and the reason is benign

Comparing all 324 parked files against their `dev_OAPSAIMI` counterparts: **213 identical,
110 divergent, 1 absent upstream.**

The AIMI package on `dev_OAPSAIMI` has not been touched since 2026-08-22, and the dump was parked on
08-28, so the divergence is not upstream drift - the parked files were edited in place.

Sampling the most important one, `DetermineBasalAIMI2.kt` differs by **4 insertions and 3 deletions**,
and they are:

```
-import javax.inject.Inject / javax.inject.Singleton
+import dev.zacsweers.metro.Inject / SingleIn / AppScope
-@Singleton  ->  +@SingleIn(AppScope::class)
```

This is the Metro DI migration being applied to parked code. Sensible maintenance, not corruption.

**But the dump is still a hazard**, for a reason independent of its contents: it is a third copy of
insulin-dosing logic, committed to git, that looks like a frozen reference and is not one. Anyone who
diffs against it will get the wrong answer.

---

## 6. The biggest change did not come from this session

**Milos replaced Dagger with Metro across the whole repo on 2026-08-24.**

| | study baseline | now |
|---|---:|---:|
| files importing `dev.zacsweers.metro` | 0 | **642** |
| files importing `javax.inject` | — | 444 |
| files importing `dagger` | **1 053** | **77** |

Metro (`dev.zacsweers.metro`) is a KMP-native DI compiler plugin, and it is applied to multiplatform
modules including `core/ui`, `core/interfaces`, `plugins/calibration`, `smoothing`, `sensitivity`.

This **retires the study's most-quoted planning law** - *"a module's conversion cost is roughly its
Dagger count"* - and with it the reasoning that DI wiring must be lifted into `:app` one file per
module. The study's §7 line item for Dagger de-wiring (130 `@Inject` sites, ~65 h) and the SC-C
repo-wide DI swap (12-20 pw) both shrink substantially. I will not put a revised number on it here
without measuring; it is the single most valuable thing to re-cost.

One caveat to carry: `gradle/libs.versions.toml` pins `metro = "1.5.0-SNAPSHOT"`, with a comment that
1.4.2 has a member-injection codegen bug. **A snapshot dependency in a build that computes insulin
doses is a risk** - snapshots are mutable. Pin a release as soon as one carries the fix.

---

## 7. What to do next

Ordered. The first item unblocks everything else.

1. **Add `import kotlinx.coroutines.runBlocking` to `TirCalculatorImpl.kt`** and get
   `:app:assembleFullDebug` green. Until the Android app builds there is no oracle to compare
   behaviour against, and every later claim about parity is unverifiable.
2. **Enforce the rule that already exists.** `testing.mdc` mandates compiling the smallest affected
   module. Make it mechanical rather than advisory - a pre-commit hook or a CI job on this branch, so
   a broken build cannot be committed again.
3. **Decide between the two tracks.** The 38 486-line peel into `:plugins:aps` and the 645-line
   clean-room `:plugins:aimi-engine` are different destinations. The clean-room module matches the
   study's recommended architecture and already emits a framework; the peel has the volume. Pick one
   as the destination and make the other explicitly a staging area, in writing.
4. **Finish the numeric audit.** §4 cleared the files I checked, not all 286. Extract every numeric
   literal from all peeled files and diff against `dev_OAPSAIMI` mechanically. This is a script, not a
   review - it should be a CI check that runs on every future lot.
5. **Delete the staging dump from git, or rename it so it cannot be mistaken for a reference.**
   `dev_OAPSAIMI` is the reference and it is one `git show` away.
6. **Answer the reachability question.** With the orchestrator parked, determine how much of the
   38 486 lines is reachable from any entry point. If the answer is "none", the work is real but its
   integration is entirely ahead, and the completion percentage should be reported that way.
7. **Re-cost the study's §7 with Metro in hand**, and record whether Lots V and AA mean the
   dependency-driven peel order converges or stalls before the orchestrator.

---

## 7b. Verified build results, and what they mean

Full build verification completed after §1-7 were drafted. It confirms the picture and sharpens it.

| check | result |
|---|---|
| `:app:assembleFullDebug` | **FAIL**, `EXIT=1`, 17m15s |
| `:plugins:aps` iOS compile (both targets) | **PASS**, 6/6 tasks ran, none `SKIPPED`, 6 klib manifests |
| `:plugins:aps:testAndroidHostTest` | **FAIL**, `EXIT=1` - blocked by the same `:implementation` breakage |
| `:plugins:aps:iosSimulatorArm64Test` | **SKIPPED** - the module has no `commonTest` source set at all |
| `:plugins:aimi-engine:jvmTest` | PASS, `tests="4" failures="0"` - against a 131-LOC stub |

**The iOS result is real and worth more than a green tick.** The klib for `:plugins:aps` contains
`linkdata/package_app.aaps.plugins.aps.openAPSAIMI.*` - the AIMI package itself is in the
Kotlin/Native output, not merely a module that happens to declare Apple targets.

**Methodology warning for anyone re-running this.** Gradle 9 reports Kotlin errors under a
"Problems report" banner, **not** as `e: ` lines. Grepping `^e: ` on this branch returns **zero on a
failed build**. Trust the exit code and `BUILD FAILED`, never an error-line count. The study's
existing "redirect, never pipe" rule is necessary but no longer sufficient.

### Zero `expect`/`actual` - and that is the finding, not a compliment

Banned-import scans come back **clean**: 0 hits for `android.*`, `java.*`, `javax.inject`, `org.json`,
Gson, RxJava or `kotlin.reflect` in the ported `openAPSAIMI` package or in any `aimi-*` module. The
only `androidx` hits in commonMain are legitimately multiplatform (`androidx.collection`,
`androidx.lifecycle.compose`, `androidx.annotation`).

But there are **zero `expect`/`actual` pairs** in the entire ported body. Every `actual` match is the
English word inside a KDoc comment.

That combination explains itself: the code is clean because **the platform-touching half was never
solved - it was moved into `_docs/`**. The study's §4.1 predicted that T0 and T1 would go quickly and
that T2 (80 files needing `expect`/`actual`) plus T3 (8 files) would be the real work. Exactly those
were parked. So the correct reading of "0 banned imports" is not "the hard part is done"; it is
**"the hard part has not started"**.

### The port is fully orphaned - confirmed at the registration site

`plugins/aps/src/androidMain/.../di/ApsPluginRegistrations.kt` registers exactly three plugins:
`OpenAPSAMAPlugin` `@IntKey(210)`, `OpenAPSSMBPlugin` `@IntKey(220)`, `OpenAPSAutoISFPlugin`
`@IntKey(230)`. **There is no AIMI entry** (`master` binds `OpenAPSAIMIPlugin` at
`PluginsListModule.kt:265`; the replacement dropped it).

Corroborating: **0** `PluginBase` implementations under the ported `openAPSAIMI` package, **0**
inbound references from outside that package, **0** mentions of AIMI anywhere in `app/src`, and **0**
producers of `APSResult.Algorithm.AIMI`.

**Install this branch, once it builds, and AIMI would not appear in the plugin list at all.**

### Test coverage is the weakest point

**21 `@Test` methods across 9 files, for 40 026 lines** - one test per ~1 900 lines of insulin-dosing
code. For comparison, `dev_OAPSAIMI` carries 243 AIMI test files / 31 962 lines, so essentially none
of the existing suite came across. `:plugins:aps` has **no `commonTest` source set**, so nothing is
verified on Kotlin/Native despite that being the entire point.

The four passing `aimi-engine` tests exercise `HoldAimiEngine`, whose reason code is literally
`REASON_NOT_EXTRACTED`.

### On `sdd-progress.md`

Its claim - *"W8: JVM tests BUILD SUCCESSFUL; iOS simulator compile BUILD SUCCESSFUL"* - is
**true but severely misleading**. Both statements describe the `aimi-engine` **stub**, not the
40 000-line port, and neither noticed that the app does not build. The stale test XMLs make the
pattern visible: `build/test-results/testAndroidHostTest/` holds exactly 9 files, all AIMI, all
timestamped 2026-08-27 23:52 - *before* the breaking commit - and zero non-AIMI. A filtered subset
was run once and never re-run.

### Smaller build-hygiene items

- **CI cannot cover this work, and a manual trigger would not help.** `ios-ci.yml` does not merely
  filter paths to `core/data`, `core/nssdk`, `core/keys` - its compile step and both assertion steps
  **hardcode those three modules**. The green iOS result above has never been reproduced by CI and
  cannot be without editing the workflow.
- The new modules **dropped `mingwX64`**, which `core/data` documents as the only Native target whose
  tests can run off a Mac.
- `api(kotlin("reflect"))` sits in `plugins/aps` **commonMain** dependencies. There is no Native
  artifact for it; this is harmless only because nothing uses it. It belongs in `androidMain`.
- Correction to one agent claim: the four `aimi-*` framework `baseName`s were reported as identical.
  They are not - `AimiContracts`, `AimiIo`, `AimiLearning`, `AimiEngine` are distinct. Not a defect.
- The staging dump is larger than §2 states: **324 files / 94 589 LOC**, git-tracked.

---

## 8. Fair assessment

The discipline in this session was better than its outcome. The ledger ends with *"Tick last. **Do
not say AIMI runs on iOS.**"* and keeps an explicit "Still parked (do not restore as one blob)" list.
Lot reviews record `APPROVE_WITH_CONCERNS` rather than rubber-stamping. Concurrency was migrated to
Milos's `AapsLock` seam rather than dropped. No dosing constant was found altered. The import-ban
Gradle task is a genuinely good mechanism that did not exist before.

Against that: the Android build was left broken by a one-line omission that the session's own rules
would have caught, two lots stalled without reports, and 104 markdown files were produced under a
standing instruction that says *"Do not produce long progress reports."* The documentation volume is
itself a finding - it is roughly one markdown file per three files ported.
