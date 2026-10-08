# AIMI → KMP: handover, 2026-10-08

Written for the next agent picking this up. The goal is to migrate AIMI **entirely** to Kotlin
Multiplatform on `kmp-aimi-migration-study`, with the clinical reference staying `origin/dev_OAPSAIMI`.

Read this, then `AIMI_PORT_STATE.md` (the long ledger — sections `6bf` to `6bj` are this session) and
the `kmp-module-flip` skill. Those two carry the detail; this file carries the shape.

---

## 1. Where the branch stands

Tip `c30b21b0c4a`, plus **24 uncommitted paths in the working tree** (the owner commits, not us).

| source set | files | lines |
|---|---:|---:|
| `commonMain` | 533 | 87 905 |
| `androidMain` | **87** | **39 624** |
| `commonTest` | 107 | 16 638 |
| `androidHostTest` | 173 | 28 686 |
| `appleJvmMain` | 11 | 1 823 |
| `iosMain` | 10 | 729 |
| `jvmMain` | 8 | 190 |

Gate, measured on the tree as left: **2 082 / 770 / 765, 0 failures**, `BUILD SUCCESSFUL`.

**Two thirds of what is left sits in two files.** `DetermineBasalAIMI2.kt` is 14 678 lines (37 % of the
remainder) and `OpenAPSAIMIPlugin.kt` is 2 602. The other 85 files share about 22 000 lines.

---

## 2. Done this session

- **Tranche 7 — CSV helpers.** New `commonMain/.../ml/AimiTrainingCsvWriter.kt`. The one real gap was
  `AimiStorage.lastChar(path)`, implemented for real on Android, JVM and iOS
  (`NSFileHandle.seekToFileOffset`, not a whole-file read). **The tick now holds no `java.io.File`,
  no `RandomAccessFile` and no `AimiStorageHelper`** — the only matches left in it are comments
  saying so.
- **Tranche 6 — async caches.** 13 scattered `AtomicBoolean` + `AtomicReference` pairs folded into
  `AimiSingleFlightCache`. **Carries a known regression — see §4.1.**
- **Auditor chain.** `AuditorUIState` and `AuditorReportFormatter` moved to `commonMain` once two
  unread `@ColorRes` fields went (which `CLAUDE.md` forbids in a domain model anyway). The colour
  distinction now rides on `StateType` → `PluginStatusLevel`, with a new test pinning all five.
- **Parity harness landed and frozen** (committed in `c30b21b0c4a`): `ParityDoseTrace`,
  `ParityAndroidFixture`, `ParityDivergenceTest`, and a nine-scenario Android capture in
  `ShellDecisionTraceTest`.
- **Test harness repaired** after the two tranches renamed the private fields it reflects into.
- **Two build prerequisites documented** — see §5.2. Both cost an hour each to diagnose from the
  symptom.

---

## 3. The hard parts of this project

Read this section before planning anything. Every item below has already cost real time.

### 3.1 A grep that finds nothing is a hypothesis

This is the single most expensive mistake in the campaign, made **ten times** in different costumes:

- `^import android` also matches `androidx`, hiding every Compose file.
- A type in the **same package** needs no import.
- `java.lang` needs no import (`String.format`, `Math`, `Thread`, `Throwable.stackTrace`).
- `@Volatile` is often written with **no import at all** and resolves to `kotlin.jvm.Volatile`.
- `synchronized` does not match `@Synchronized`.
- A **generated** declaration leaves no call site to find (`ApsStrings` lives under `build/`; the
  per-platform string registries are generated from `buildSrc/.../StringOwnerModules.kt`).
- A file was called "Android" because it sat in a folder beside Android ones.
- A `Context` that turned out to be a dead import.

**Move the file, compile for iOS, read the errors.** One file at a time. The compiler is the authority.

### 3.2 Behaviour that changes without failing anything

- **Rounding is not one behaviour.** `String.format("%.Nf")` rounds the *shortest round-trip decimal*
  half-away-from-zero. `NumberFormat.withDecimalsHalfUp` rounds the *exact binary value*. They
  disagree on ordinary numbers: `%.1f` of `8.35` is `8.4` one way and `8.3` the other. `aimiFmt*` now
  routes through `formatFixedHalfAway`, which matches `String.format`; a parity test pins it.
- **A green suite does not prove a concurrency port faithful.** See §4.1.
- **Reading a value earlier than the reference did** is a real change that no test may cover.
  `p6-effects-boundary.md` rejects a pre-read snapshot for exactly this reason: the common core must
  call its ports *at the point* the reference reads.
- **Who reads the bytes decides whether a format change matters.** An HTTP request body is re-parsed
  by a JSON parser and does not care about key order; JSON pasted into an **LLM prompt** is read
  character by character and does. Same for a decimal separator in a log versus on screen.
- **`OrgJsonCompat` is for readers that used `opt*`.** A reader that used the *throwing* getters
  (`getString`, `getJSONArray`) needs `getValue(...).jsonPrimitive` instead — the throw is often
  load-bearing, because it is what produces a user-visible error.

### 3.3 The CSV files are the training corpus

The owner's standing warning: AIMI trains the SMB and basal models **on the phone** from these files.
A changed column, order, separator or timestamp corrupts data that cannot be recovered. Two incidents
are already on record: a frozen 13-column header made the trainer learn the wrong field, and a cleanup
that left no trailing newline glued the next row onto the last one so **the reader dropped both**.

The hazard shape to watch for: `AimiStorage.appendText` **returns `false`**, where `File.appendText`
**threw**. A port that keeps `runCatching` leaves a fallback that can never fire, and every training
row is silently dropped when shared storage is denied. That was caught in tranche 7; expect the same
shape elsewhere.

### 3.4 Test results that lie

- `build/test-results/` keeps the **previous** run's green counts when a build dies before the test
  task. This produced two confident wrong readings in one evening, the second with plausible numbers.
  **Always compare the newest XML's mtime against the clock.**
- `--rerun` written once at the end of a task list is **ignored** for the tasks it was meant to cover;
  the run returns `UP-TO-DATE` and looks like a pass. Attach it **per task**.
- `^e: ` **misses Kotlin errors on the Android path**, which print `Problem found: Kotlin compiler
  error` with a `Location:` line instead. And `Problem found:` also fires for *warnings*, including an
  incremental-compilation fallback.
- **`--quiet` suppresses `BUILD SUCCESSFUL` entirely.** Verified. So under `--quiet` the absence of a
  marker is not evidence of failure and a 95-byte log is not evidence of a crash — only the exit code
  is left. `CLAUDE.md` recommends `--quiet` for token economy; for a gate, the result wins.

### 3.5 Agent and machine limits

- Fresh worktrees have been handed `2fd4d5f218` (a pre-KMP commit, with `plugins/aps/src/main/`)
  **ten times running**. Always check the base and reset onto the branch tip.
- **Do not run more than one Gradle build at a time on this machine.** Three concurrent builds killed
  two agents and a gate in one night.
- Agents stall on a 600 s watchdog, and one waited forever on a background run that had been killed.
  **Run the gate in the foreground; never finish with a build still running.**

---

## 4. Open defects and decisions

### 4.1 The concurrency regression (not yet fixed — needs the owner's word)

`AimiSingleFlightCache.refresh` ships as a **non-atomic** check-then-set:

```kotlin
if (inFlight.load()) return
inFlight.store(true)
```

The pre-port tick used `compareAndSet(false, true)` in **14 places**. So the port weakened an atomic
guard across 13 caches, on code the dosing tick calls. Three things make this instructive:

1. The class's **own KDoc** (line 17) quotes `compareAndSet(false, true)` as the shape it is porting.
2. Its test comment claims a plain check-then-set "fails this one". The shipped code **is** that shape
   and the test passes.
3. **Only mutation exposed it.** Restoring a real `compareAndSet` leaves the suite 6/6 green, so it
   cannot tell CAS from non-CAS. Removing the guard **entirely** still leaves
   `theReadIsNeverRunningTwiceOverUnderRealThreads` green, because its 64 `refresh` calls are issued
   sequentially from one coroutine — only the loads run concurrently, so the guard is never raced.

**Fix:** one line, `if (!inFlight.compareAndSet(false, true)) return`. The form already exists in
shared code at `AIMIDatabaseStepsProviderMTR.kt:133`. The test also needs either real concurrency or
an honest name.

### 4.2 Decisions waiting on the owner

| | what it blocks |
|---|---|
| **Decimal separator in user-visible text** — `rT.learnersInfo` and `rT.reason`, including inside localized resource templates. Numerically identical, systematic. | nothing technically; it is already shipped behaviour |
| **What reaches an LLM prompt** — `AIMILLMPhysioAnalyzerMTR` (512 lines behind one locale-formatted line) and `TpoLlmValidator` (JSON key order and `.0` rendering, which also gates `TpoOrchestrator` → `AimiControlCenterScreen`, 1 021 lines) | ~1 715 lines |
| **The five parity scenarios with no iOS counterpart** — `fasting`, `uam`, `hypo-rebound`, `sensor-gap`, both `healthkit`. iOS has four scenes. | the whole "every trace matches byte for byte" activation rule |
| **TPO notifications on iOS** — recommendation: write the `UNUserNotificationCenter` actual rather than migrate onto the shared stack, which would lose the deep link and merge two channels into one louder one | iOS feature parity for TPO |
| **`sportSafety:249`** — a safety guard with no test; deleting it leaves all 14 green. Closing test is one fixture (`recentSteps5 = 0`, `recentSteps30 = 1500`). | nothing; it is a coverage hole |
| **`declared-meal` asymmetry** — the SMB effect reaches `rT`, the temp-basal effect does not, though the trace shows it issued. Not traced to a branch. | a maintainer question |

### 4.3 Known-stale documentation

- **`AimiStorage`'s KDoc says it has no iOS implementation. It does** — `DirectoryAimiStorage` in
  `appleJvmMain`, over an `AimiLocalFiles` expect/actual with both iOS and JVM actuals. A new
  `AimiStorage` method is therefore a **three-implementation** change plus the test fake. This stale
  line has already misled one brief.
- `p6-tick-plan.md` line 27 calls the `RandomAccessFile` use *"lecture aléatoire"*. It is a **one-byte
  tail read**. That framing is what made tranche 7 look expensive.
- Five KDoc comments name `MainApp.registerStringOwners()`, which no longer exists.
- `NumberRounding`'s KDoc used to argue one-decimal ties were unreachable. They are (`0.25`, `0.75`,
  `1.25`). Corrected, but the same reasoning may appear elsewhere.

---

## 5. Methods in place

### 5.1 The five gates

```
:plugins:aps:testAndroidHostTest --rerun :plugins:aps:jvmTest --rerun \
:plugins:aps:iosSimulatorArm64Test --rerun :plugins:aps:compileKotlinIosArm64 --rerun \
:plugins:aps:compileKotlinJvm --rerun :app:assembleFullDebug
```

macOS, `./gradlew`, `--no-daemon`, **never piped** (a pipe makes the exit code the pipe's), **never
`--quiet`**. Redirect to a log and grep
`-E '^e: |Kotlin compiler error|BUILD FAILED|BUILD SUCCESSFUL'`. Take counts from the `<testsuite>`
attributes in `plugins/aps/build/test-results/<task>/*.xml`, check their mtime against the clock, and
confirm each gate task appears as an executing `> Task` line with no `UP-TO-DATE`.

Fast pre-gate while iterating, about one minute against seven:
`compileAndroidMain` + `compileKotlinIosArm64` + `compileKotlinJvm`. Include the JVM one — this module
has a `jvmMain` source set, so a new `expect` needs **four** halves, and an Android+iOS pre-gate will
not notice a missing one.

### 5.2 Prerequisites Gradle does not provide

1. **iOS simulator runtime** — `xcodebuild -downloadPlatform iOS`. Without it `iosSimulatorArm64Test`
   says `Xcode does not support simulator tests for ios_simulator_arm64`.
2. **The TFLite static library** — `bash plugins/aps/src/tfliteParity/build-uam24-sim-arm64.sh`,
   about four minutes, **once per checkout and once per agent worktree** because the artefact lives
   under `build/`. Without it the iOS link dies with
   `ld: library '…/tflite-24-sim-arm64/libtensorflow-lite.a' not found`, and the Kotlin/Native
   compiler cache is a red herring. The script ends by printing `identical 67 / 67` — its own proof
   that the simulator build matches the Android arm64-v8a reference **bit for bit**, which is why a
   parity divergence found later is almost certainly in the tick's logic, not in TFLite.
3. **Stale KSP** — if `:app:compileFullDebugJavaWithJavac` fails on `dagger.hilt` imports, that is
   pre-Metro generated output. Delete only `app/build/generated/ksp/<variant>`. **Never a clean
   build.**

### 5.3 The parity harness

`ParityDoseTrace` serialises one scenario with a fixed field order, doubles as raw IEEE-754 hex, and
`absent` for a missing value. `firstParityDivergence(reference, actual)` names the first differing
field. `ParityAndroidFixture.BLOCK` holds the frozen Android output for nine scenarios.

Rules, learned the hard way:

- **Never type a fixture value by hand, and never change the tick to make the fixture match.** The
  fixture records what Android does; a surprising number is a finding.
- When it fails, ask whether the **numbers** moved or only the plumbing. This session the fixture
  failed, the plumbing was repaired, and **it matched again without being re-frozen** — which is what
  demonstrated that the two tranches changed no dose.

### 5.4 How a lot is run

One agent per lot, in its own worktree, with a brief that carries: the worktree base check, the two
prerequisites, the §3 traps, the gate, the house rules from `CLAUDE.md`, and an explicit instruction
to **stop and report rather than invent a seam** when the lot hits a decision. Agents are asked to
name every function they touch, which is what let two lots edit `DetermineBasalAIMI2.kt` in parallel
and still merge with **zero conflicts** (`git merge-file --diff3`).

The most valuable section of every report has consistently been *"what in this brief turned out to be
wrong"*. Ask for it, and act on it — briefs in this campaign have been wrong about blocker lists,
about which files exist, about whether a claim was already true, and about the gate itself.

---

## 6. Next steps, in the order I would take them

1. **Fix §4.1** (one line) and settle the test. It is a parity regression in dosing-adjacent code and
   it is already written down; leaving it is the worst option.
2. **Get the five iOS scenario decisions.** Nothing downstream of the activation rule can proceed
   without them, and no code can be written to anticipate them.
3. **Tranche 8 of the tick** — `p6-tick-plan.md` says it stays `androidMain` (Metro constructor,
   notifications, TFLite, 238 preference reads), so the honest next move is to **re-measure** what is
   actually left in `DetermineBasalAIMI2.kt` now that tranches 1–7 are done, and re-cut the remaining
   work. The plan's line numbers are already stale.
4. **`OpenAPSAIMIPlugin.kt`** (2 602 lines) is its own lot, as the plan says.
5. **The Compose screens still blocked on seams**: `AimiProfileAdvisorScreen` (908) needs an
   **asset-loading** seam (it reaches a bundled ML asset through a real `Context`);
   `HormonitorViewerScreen` needs `format(mills, pattern)` on `DateUtil`, which is a `core:interfaces`
   decision.
6. **Accept as permanently `androidMain`**, and stop re-probing them: Health Connect sync and
   permissions, `StepService`, the `camera2` capture pipeline in `AimiMealAdvisorScreen`, the Vico
   charts in `AimiCognitiveOrefCoachCards`, and the WorkManager scheduling in `AIMIPhysioManagerMTR`
   (iOS `BGTaskScheduler` is not equivalent — it would be a redesign, not a port).

**One standing rule worth repeating.** The iOS side carries deliberate scaffolding — "temporary
neutral values" in `p6-ios-decisions.md` — and it is genuinely inert today: `IosClientConfig.APS` is
`false`, `HoldAimiEngine` is constructed only in `appleJvmMain`, and nothing outside tests sets
`AimiCommonEngineSwitch.enabled`. **Verify those three before trusting any claim about iOS being
off**, and do not turn any of them on outside a test.
