# AIMI — work state and agent playbook, 2026-09-14

Hand-over for the next session. Branch `dev_OAPSAIMI`. Written in simple English like the rest of
`docs/`, per `CLAUDE.md`.

`docs/AIMI_NEXT_SESSION.md` is the older hand-over (last real update 2026-08-19). It is still the
record for everything before September. **This file is the current state.** When they disagree, this
one wins.

---

## 1. Read this first

Three things were shipped on 2026-09-13/14. Two questions are still open, and one of them is the
user's main complaint.

| | Subject | State |
|---|---|---|
| A | Stress ISF floor — retuned | **shipped**, `517e55f795`, **confirmed on the body 2026-09-14** (section 9) |
| B | Advisor metrics were invented | **shipped**, `517e55f795` |
| C | Morning over-correction | **requalified 2026-09-14** — the morning is no longer the problem (section 9) |
| D | Kotlin/Native commas | **nothing to do on this branch** |
| E | Glucose sent to Nightscout was not whole | **shipped**, `9287157672` |
| F | Autodrive storm at the cap | **new, 2026-09-14** — the real source of the hypoglycaemia (section 9) |
| G | Rise ceiling guard | **shipped disarmed 2026-09-14** — first candidate to pass the test since the ISF floor (section 10) |
| H | MCER read the display path-min | **fixed 2026-09-16** (section 11) |
| I | TPO rewrites preferences from the loop | **export added 2026-09-16**, cause of the change still unknown (section 12) |
| J | Does the prediction layer stabilise? | **answered 2026-09-16** — the wiring is right, the slope is not (section 14) |
| K | Declared-meal anticipation | **built disarmed 2026-09-16** (section 16) |
| L | The tail latch was a regression | **fixed 2026-09-16** (section 17) |
| M | Heart rate during an undeclared meal rise | **fixed 2026-09-17** (section 18) |
| N | The rise ceiling guard's 18.55 was wrong | **correction 2026-09-17** — real value 1.11 / 0.45 (section 18.5) |

Working tree is clean except this file. Everything else below is committed.

---

## 2. What shipped, and what it is worth

### A — Stress ISF floor (`ISF/StressIsfFloor.kt`)

The gesture: when the heart rate sits at least 20 bpm over resting **and** the person is not
walking, hold the commanded sensitivity at the profile value instead of letting it drift down to
half. It can only make a dose smaller, never larger. Key `OApsAIMIStressIsfFloor`.

Two changes were measured on 6950 ticks over 8 days and applied:

- **Steps limit 100 → 250.** On morning ticks with the heart rate at least 20 bpm over resting the
  median step count is 58 but the 90th centile is 303, so 100 covered only **62 %** of them. 250
  covers **87 %** and stays under the 375 that `PhysiologicalTree` already calls sustained walking.
- **Getting out is no longer the same test as getting in.** Entering still needs 10 minutes of
  unbroken signature; leaving now needs 5 minutes out of signature (`EXIT_GRACE_MINUTES`). On
  2026-09-13 the step count crossed 100 one tick at a time — 91, 119, 171, 248 — while the heart
  rate stayed 24 to 44 bpm over resting, so the floor kept resetting and was active on **8 of the
  420 ticks** of that morning while blood glucose fell to 56.

Replayed, the same morning goes from **8 to 112 active ticks** and the separation on morning
episodes rises from 55.1 to **63.0**. `StressIsfFloorTest`: 19 tests, 0 failures.

**The finding the user has not acted on yet.** Split by when the episode starts, the gesture
separates **only in the morning**: episodes starting 04:00–11:00 score **55–63**, the rest of the day
scores **0.81** — below 1, meaning it withholds slightly more from episodes that ended well than from
episodes that ended badly. It is not dangerous (the gesture can only reduce insulin) but it is
useless outside the morning. The user asked for 24 h a day "pour le moment" and that is what runs.
A time window is a one-line change if he wants it.

**Still unverified:** nothing here has been confirmed on the body. The user planned to test it by
simulating a cortisol peak in the morning.

### B — The advisor was inventing patients

`AimiAdvisorService.calculateMetrics` started from hard-coded numbers (TIR 65 %, mean 160 mg/dL,
TDD 45 U) and only overwrote the ones a calculator could give. A caller could not tell a real patient
from that invented one.

Fixed by copying the convention of `OrefAnalysisReport`, its immediate neighbour, which already
solves the same problem: 11 fields of `AdvisorMetrics` became `Double?`, plus an
`AdvisorDataSufficiency` enum and coverage counters. Everything that proposes a settings change now
stays silent when the measure is missing — PKPD, the tuning plan, the family bridge. The screen says
"No data". The AI coach prompt writes `unknown` instead of a number.

Two things worth keeping in mind:

- The old code did `(x ?: 0.0) / 100` on percentages, so "no data" became **"0 % hypoglycaemia"** —
  the value that opens the guards instead of closing them. `getEmptyContext()` built the same
  all-zero patient.
- The real hole on **this** branch was `OpenAPSAIMIPlugin` line ~2024: the PKPD recommendation loader
  ran in production without a `TirCalculator`, so it tuned DIA, peak, ISF fusion and tail damping
  from the fictional patient. `TirCalculator` is now injected. The commit `83e245b87b` that the user
  mentioned is **not on this branch** — it lives on `kmp-aimi-migration-study`.

These numbers never reach a dose inside one loop cycle (`DetermineBasalAIMI2` never reads
`AdvisorMetrics`), but they reach preferences that govern dosing in two user clicks.

### E — Glucose sent out was not a whole number

Nightscout showed `83.17856343415423` while the phone showed `83`.

Both sensors store whole numbers (`Libre3GlucoseParser`: `mgdl = usableMgdl.toDouble()`,
`OnePlusGlucoseParser`: `mgdl = glucose.toDouble()`, both from an `Int`). The decimals came from
`GlucoseCorrectionImpl`: the corrected series is calibrated and smoothed, and because it sits on a
5-minute grid while both sensors speak every minute, it is also **interpolated**. That raw `Double`
went straight into `sgv` (`toNSSvgV3`) and into `mgdl` (`toXdripJson`), and `NSSgvV3.sgv` is a
`Double`, so nothing downstream caught it.

Rounded at the choke point, **after** the plausibility net — rounding first would turn a refused 38.6
into an accepted 39. One place covers all three consumers: Nightscout, the xDrip broadcast and the
local alarms. `GlucoseCorrectionImplTest`: 18 tests, 0 failures.

### D — Kotlin/Native commas: nothing to do here

`dev_OAPSAIMI` has **no KMP module**: no `multiplatform` in any Gradle file, no tracked `ios` file,
no Native source set. The 178 test names with commas found here are all plain JVM/Android tests and
renaming them would change nothing.

Proof the constraint follows the source set and not the module: on `dev`, the sources compiled for
Native hold 639 backticked identifiers and **zero** commas, while the non-Native sets keep 102 with a
green iOS CI.

Two things still worth doing, **on `dev`, not here**:

- `.github/workflows/ios-ci.yml` compiles for Native but its scope excludes `:ui` and `:plugins:aps`
  — exactly the two modules where 4 of the 5 breakages happened. Adding
  `compileTestKotlinIosSimulatorArm64` for them closes the hole and catches every Native breakage,
  not only commas. No new tool needed.
- `ProfileBoundariesTest` was fixed twice in one day by two different people on two branches. The
  symptom comes back at every port because `dev_OAPSAIMI` is the source branch the ports copy from,
  and 8 test names here differ from their `commonTest` twin **only by the comma**.

---

## 3. C — the open subject: the morning over-correction

This is the user's actual complaint: *"ce matin aussi il a trop corrigé"*.

### What is established

**The over-correction is basal, not SMB.** On 2026-09-13 between 04:00 and 11:00 the pump delivered
**0.90 U of SMB** — ten micro-boluses of 0.1 U — and **7.93 U of basal** against a 3.48 U profile,
so **+4.45 U above profile**. Insulin on board went from 1.24 U to 5.52 U.

The integration is cross-checked: on the full day of 2026-09-12 it gives 36.1 U of basal, and the
device's own measured TDD is 57.8 U/day. 36 U basal + ~20 U SMB = 56 U. It also says the loop runs
**+24.3 U/day above the profile basal**, three times the profile.

**The mechanism.** The commanded basal is a steep function of `eventual_bg`, and that prediction is
unusable at both ends. Over 6896 ticks it equals exactly **39 on 25.0 %** of them and passes 300 on
7.1 %. Median commanded basal by regime: **0.00 U/h** when the prediction is 39, **5.28 U/h** when it
is above 250, for a profile of 0.53.

The trace of the morning:

```
06:12  BG 133  Δ+4.7   prediction 206  ->  basal 5.00 U/h   IOB 1.77
06:24  BG 139  Δ+4.7   prediction 159  ->  basal 4.40 U/h   IOB 2.18
06:36  BG 143  Δ+0.7   prediction  39  ->  basal 0.00 U/h   IOB 3.32
09:00  BG 147  Δ+11.2  prediction 372  ->  basal 6.02 U/h   IOB 2.51
09:12  BG 157  Δ+4.6   prediction 179  ->  basal 5.00 U/h   IOB 4.55
09:24  BG 145  Δ-5.5   prediction  39  ->  basal 0.00 U/h   IOB 4.59
09:48  BG  80  Δ-25.6  prediction  39  ->  basal 0.00 U/h
```

The two mornings that went under 70 are the two with the biggest basal excess (09-12: +6.10 U, low
65; 09-13: +4.45 U, low 56) against +2.85 / +3.15 / +3.31 for the three that ended well.

**Observationally the signal is strong.** Basal delivered above profile in the previous 3 hours,
against the lowest blood glucose in the next 3 hours: **3.0 %** go under 70 when the excess is below
0.5 U, **25.9 %** when it is above 3 U, monotone across six bands.

### What was tried and rejected

Every candidate must pass the discrimination test (section 5). All three failed:

| Candidate | Ratio |
|---|---|
| Rolling cap on basal above profile (1 to 4 U per 2 h) | 0.54 – 1.14 |
| Block basal ≥ 3 U/h within 30 min of a 39 prediction | 1.32 |
| Cap at profile whenever `eventual < target` | 0.00 (<70) / 1.47 (<80) |

The third came from a real defect and is worth knowing about. `BasalTerminalInvariants` invariant 1
(`below_target`) requires **`bg < target` AND `eventual < target`**, while the oref rule its own KDoc
cites is on `eventual` alone. The gap is **398 ticks** where the prediction is under target but blood
glucose is above — median BG 126, target 120, prediction 80, rate 1.24 U/h for a 0.44 profile, and
16 % of those predictions are at 39. **The hole is real, but closing it touches no episode that went
under 70.** It costs 1.12 U/day for nothing.

### Why the interventions fail while the correlation is strong

Confounding. A large basal excess and the hypoglycaemia that follows share a cause — a rise that gets
over-treated — so a uniform cap hits the legitimate cases just as hard. Per-tick analysis also
suffers reverse causation: a commanded basal of **zero** is followed by a reading under 70 in 22.4 %
of cases, the same as a basal above 5 U/h, because zero is commanded once the fall is already under
way.

### Where to go next

Not another cap. Two directions that have not been tried:

1. **The prediction itself.** The basal is steered by `eventual_bg`, which is pinned at an artefact a
   quarter of the time and saturated high 7 % of the time. Fixing the controller's input is a
   different problem from bounding its output. See the memory note `pkpd-floor-39-contamination`,
   still open, and `floor39-guardB-falling-uncovered`.
2. **The ISF floor as the real lever.** A higher commanded sensitivity lowers the prediction, which
   lowers the basal. The stress floor measurements in section 2 only counted the **SMB** channel,
   which is the small one. Its effect through the basal has never been quantified, and it needs a
   replay of the basal path. **Section 2's separation numbers are therefore a lower bound.**

---

## 4. The multi-agent structure

Four roles, in order. One subject per chain. The main session is the orchestrator and is the only
one that talks to the user.

### Orchestrator (the main session)

- Cuts the work into subjects that do not share files, then runs one chain per subject.
- Writes every agent prompt in full: an agent starts cold and knows nothing about the conversation.
- **Never lets an agent re-measure something already measured.** Paste the numbers into the prompt
  and say "do not redo this, use it".
- Reports to the user, including what failed and what was not done.
- **Is the only one that commits, and only when the user asks.**

### 1. Analyst — measures, changes nothing

Reads support packages and code. Produces numbers, not opinions. Must not open an editor.

Output: the measurement, the method, and **what could not be determined**. An argued "there is
nothing to fix here" is a good result and must be accepted as one.

### 2. Designer — turns a finding into a contract, writes no code

Receives the analyst's numbers. Produces a specification precise enough that the coder has **no
decision left to make**: files to touch, exact signatures, constants with their value *and the
measurement that justifies it*, behaviour case by case, and the list of tests to write with real
numbers taken from the traces.

Must state the non-negotiables in its own spec: the gesture can never increase a dose; key disarmed
means bit-for-bit identical behaviour; missing data never activates anything.

### 3. Coder — implements the spec, decides nothing

Writes the code and the tests. Does not re-open a design question; if the spec is wrong or
incomplete, it **stops and says so** instead of inventing.

Does not run Gradle (the orchestrator does), does not commit.

### 4. Adversarial reviewer — tries to break it

Reads the diff against the spec. Looks for: a default value sneaking back in, a `!!` or a `?: 0.0`
that rebuilds the thing that was removed, a behaviour change outside the stated scope, a test that
passes for the wrong reason, a repo rule broken (imports, strings, KDoc links, module dependencies).

Must answer one question explicitly: **can this change increase a dose in any path?**

### Hard rules to paste into every agent prompt

```
BASH: never `cd X && cmd`, never `cd; cmd`. Absolute paths, or `git -C <repo> ...`.
Do NOT use awk / cut / tr / sort / uniq / diff / which / chmod / tar.
Use python3, grep, sed, head, tail, cat, git. Install nothing.
Run NO gradle build - the orchestrator does that.
Commit NOTHING. Leave the work in the working tree.
Scratchpad: use the session scratchpad directory, never /tmp.
```

Repo rules the coder and the reviewer both need:

```
Simple school English everywhere: code, comments, KDoc, UI strings.
Explicit imports always - never a fully qualified name at the use site.
Compose: stringResource(), never ResourceHelper. Never build user text by concatenation -
use a format-string resource with positional placeholders (%1$s, %2$s).
Resource strings: English version only, never the translations.
A KDoc [Xxx] link must resolve from that file, otherwise use backticks.
Never @Suppress("KDocUnresolvedReference").
No new inter-module dependency.
```

### Practical notes, learned the hard way

- **Agents die on the session rate limit.** Four were lost at once on 2026-09-13. Launch them in the
  background, and be ready to do the work in the main session. Do not let a whole subject depend on
  one agent surviving.
- **Two agents must never touch the same files.** The build will fail mid-flight and the failure will
  look like yours. When it does, attribute the compile errors by file before believing anything.
- **Gradle `UP-TO-DATE` serves stale test XML.** Always `--rerun-tasks`, and read the result XML, not
  the exit code. A pipe (`| tail`) makes the exit code the pipe's, so a failed build looks green.
- Ask the analyst for the **method**, not just the answer. Several wrong conclusions in this project
  came from an agent reporting a number without saying how it was built.

---

## 5. The rule that decides whether a gesture ships

**The discrimination test.** Replay the candidate over the whole corpus, then compare:

- what it **costs** on the episodes that ended well,
- against what it **gains** on the episodes that ended badly.

The ratio must be **clearly above 1**. Ten candidates have been dropped by this test — seven dosing
proposals before September, then the three basal candidates of section 3. **One has ever passed**, at
4.29, and that is the stress ISF floor.

Two traps that have caught us:

- **Different variants select different episode sets**, so their ratios are not strictly comparable.
  Say so when comparing.
- **A ratio computed on one channel is a lower bound**, not the effect. The stress floor numbers only
  count SMB, and SMB is the small channel.

---

## 6. Reading the data

### Packages

`/Users/mtr/Downloads/AIMI_Support_Package_<id>/` holds three files:

- `AIMI_Decisions_Last24h.jsonl` — one record per loop tick, ~1 per minute, very large lines (55 MB
  for 1435 records). **Read it line by line**, never load it whole.
- `Diagnostic_Report.txt`
- `oapsaimiML2_records.csv` — 3000 rows, one per minute, capped. **This is where `smbGiven` lives:
  the insulin actually delivered.** The JSONL carries decisions, not deliveries.

The five packages used so far, newest first: `1789299459844`, `1789205292535`, `1788956887632`,
`1788792543913`, `1788679552552`. Deduplicated they give **6950 ticks over 8 days**
(2026-09-05 → 2026-09-13).

### Field paths that matter

```
adjustments.smb_binding_trace.timestamp_ms        the tick clock - use this as the key
baseline_state.current_bg_mgdl / iob_u
baseline_state.command_isf_mgdl                   what the loop commanded
baseline_state.profile_isf_static_mgdl            "120" / "50" - the REAL profile, a string
baseline_state.profile_isf_mgdl                   TRAP: equals command_isf on 6910/6910 ticks
baseline_state.stress_isf_floor_active / _reason
adjustments.physiological_tree.physio_live.hr_now_bpm / rhr_resting_bpm / steps_last_15m
adjustments.smb_binding_trace.final_u / final_owner / origin_owner / autodrive_floor_u
adjustments.basal_terminal.rate_out_uph / rate_in_uph / profile_basal_uph
adjustments.basal_terminal.eventual_bg_mgdl / target_bg_mgdl / delta_mgdl_5m / bound_by
```

`baseline_state.profile_isf_mgdl` is **not** the profile sensitivity. It is the dynamic value passed
as `profile.sens`, and it equals `command_isf_mgdl` on every tick. Anything comparing the commanded
value to "the profile" must use `profile_isf_static_mgdl`. This trap cost a whole round of wrong
measurements.

### Method

- **A missing field is not a value.** `hr_now_bpm = 0` means missing, not calm.
- Deduplicate by timestamp when merging packages; never bridge a gap longer than 10 minutes.
- Always separate what the engine **decided** from what was **delivered**. Summing per-minute
  decisions overcounts insulin by roughly the tick rate.
- Basal insulin = integrate `rate_out_uph` between consecutive ticks, skipping any gap over 15 min.
  Cross-check the daily total against `tdd24HrsPerHour × 24` from the CSV before believing it.
- Replay first, then trust. The stress floor replay reproduces the device verdict on **97.5 %** of
  ticks and errs on the conservative side (it under-activates, because the device kept state from
  before the window).

### Patient facts

Profile sensitivity **120 mg/dL/U from 00:00 to 11:00, 50 from 11:00 to 00:00**. The 120 is a
deliberate safety choice for sleep and for the morning cortisol peak, **not a mistake** — this was
misread once. Resting heart rate around 50. Measured TDD about 57.8 U/day, of which roughly 36 U
basal against a profile basal of about 12 U/day.

---

## 7. Tests: what is red before you start

Do not chase these. They were red before this work and are unrelated to it.

- **`:plugins:sync` test source set — 15 compile errors**, `GarminPluginTest` and `LoopHubTest`
  (Garmin `sp` parameter). This blocks **every** test in that module. The main source set compiles.
- **`:implementation` — 15 failures**: `LastBgDataImplTest` 1 of 2, `KeepAliveWorkerTest` 14 of 19.
  Verified pre-existing by stashing the change and reproducing them identically. `LastBgDataImplTest`
  touches glucose display, so it looks related and is not — check before assuming.

Green as of 2026-09-14:

- `:plugins:aps` — 291 classes, **1771 tests, 0 failures, 0 errors**.
- `GlucoseCorrectionImplTest` — 18 tests, 0 failures.
- `StressIsfFloorTest` — 19 tests, 0 failures.

---

## 8. Open questions for the user

**Answered on 2026-09-14 by package `1789405703176`, see section 9:**

1. ~~Did the stress floor work this morning?~~ Yes — 161 active ticks against 8, `exit_grace` present,
   sensitivity held at 120 instead of 62.6. One morning only, and the basal excess did not fall.
2. ~~Should the floor be restricted to the morning?~~ It already does nothing after 11:00, and we now
   know why (section 9.1). Cosmetic, not urgent.

**Still open:**

3. **The Autodrive storm (section 9.2) — the direction to agree on before coding.** In order of
   benefit over risk:
   - **The `post_hypo` release point** (9.3). Do not let the guard go while glucose is climbing fast.
     Three dated occurrences in one package to write the tests from, plus the replay corpus
     `day_rebound_cycles.jsonl` that is already in `plugins/aps/src/test/resources/replay/`.
   - **The cadence, not the ceiling.** A 3-minute interval against a 20–40 minute action delay is the
     cause of shape. Lengthening the interval, or making the cap fall as insulin on board rises,
     attacks the instability instead of bounding its output — which is what the three rejected caps
     of section 3 could not do.
4. ~~The two switched-off Autodrive options (9.4).~~ Done on 2026-09-14: `descent_redose_guard`
   removed, `effort_smb` given a true shadow path. What is left is to **measure** `effort_smb` from
   the next package and decide whether to arm the key.
5. ~~The stale summary string (9.5).~~ Fixed.

**Working tree on 2026-09-14, nothing committed:** `docs/AIMI_SESSION_2026-09-14.md` (new),
`core/keys/.../strings.xml`, `core/keys/.../BooleanKey.kt`, `plugins/aps/.../DetermineBasalAIMI2.kt`,
`plugins/aps/.../OpenAPSAIMIPlugin.kt`, `plugins/aps/.../EffortActivityBeliefTest.kt`, and two
deleted files under `openAPSAIMI/smb/`. Verified: `:plugins:aps` 290 classes, **1743 tests, 0
failures, 0 errors**; `:core:keys` 8 tests, 0 failures. The red tests of section 7 were not touched.

---

## 9. Package `1789405703176` — 2026-09-13 19:08 → 2026-09-14 19:08

1359 ticks, one per minute. 76 minutes missing in total, of which one hole of 34 minutes at
09:51–10:25. The first package taken with the retuned stress floor running, and with the two
Autodrive options the user turned off on 2026-09-13.

### 9.1 A — the stress ISF floor works, and we now know why it only works in the morning

| | 2026-09-13 (before) | 2026-09-14 (after) |
|---|---|---|
| active ticks, 04:00–11:00 | **8 / 420** | **161 / 361 (44.6 %)** |
| commanded ISF while active | — | **120.0** (min = max) |
| commanded ISF while inactive | — | median **62.6** |
| blood glucose | low **56** | low **98**, no tick under 80 |

`exit_grace` appears in the reason codes with its `broken=…min` counter, so the asymmetric exit does
what it was written for. The entering/leaving flapping of 2026-09-13 does not come back.

**Do not over-read one morning.** The basal excess over the same window was **+5.01 U** (7.90 U
delivered against 2.89 U of profile), which is *more* than the +4.45 U of 2026-09-13 that ended at
56. The good morning cannot be attributed to the floor on this data. What is established is that the
gesture arms and bites.

**Why it separates 55–63 in the morning and 0.81 the rest of the day — mechanism, not statistics.**
The lever of the floor is the gap between the profile sensitivity and the dynamic one, and that gap
only exists before 11:00:

| window | ISF, floor active | ISF, floor inactive | real lever |
|---|---|---|---|
| 00:00–11:00 (profile 120) | 120.0 | 63.7 | **+88 % less insulin for the same error** |
| 11:00–24:00 (profile 50) | 50.0 | 53.8 | **−7 %, nothing** |

After 11:00 the floor clamps to 50 and the dynamic value already sits at 50. It is structurally
unable to act. Restricting the gesture to the morning would therefore cost nothing — it already does
nothing after 11:00. Not urgent.

Side measure: on 205 rising ticks after 11:00 the commanded ISF drops **below** the profile 50 on
21 % of them, down to **27.8**.

### 9.2 F — the real source of the hypoglycaemia: an Autodrive storm at the cap

The problem is not the morning any more. Over the 24 h: 77 % in range, but **19.0 % under 70** and
**1.0 % under 54**, low **48**. Five episodes under 70, none of them in the morning:

```
09-13 19:23 -> 19:46   24 min   low 64
09-13 22:51 -> 00:51  115 min   low 48
09-14 14:41 -> 15:40   60 min   low 57
09-14 15:58 -> 16:26   29 min   low 60
09-14 17:38 -> 18:05   28 min   low 66
```

One mechanism explains all of them.

| | storm 1 (09-13 20:32) | storm 2 (09-14 12:24) | storm 3 (09-14 18:30) |
|---|---|---|---|
| insulin on board | 1.83 → **19.41 U** in 56 min | 1.26 → **12.88 U** in 73 min | −1.05 → 4.98 U in 25 min |
| basal integrated | 5.63 U (profile 0.62) | 5.58 U (profile 0.69) | — |
| bolus share, by difference | **≥ 12 U** | ≥ 6 U | — |
| bolus pinned at the cap | 2.00 U on **20 ticks** | 1.25 U on **20 ticks** | 1.25 U on **19 of 25** |
| basal pinned at 7.00 U/h | — | **24 ticks** | — |
| `eventual_bg` saturated at 401 | 14 ticks | 6 ticks | — |
| what followed | low **48**, 115 min | low 57, then 60, then 66 | *still running at export* |

`origin_owner = AutodriveV3` on **100 %** of the dosing ticks of the three storms. Over the 24 h the
autodrive floor sets the final bolus value on **40 %** of dosing ticks — the note
`autodrive-floor-red-carpet-hypo-source`, measured again on fresh data.

**The dose is not computed, it is the ceiling.** `key_openapssmb_max_smb 2.0`,
`key_openapsaimi_high_bg_max_smb 1.25`, `autodrive_max_basal 7.0`, repeated every **3 minutes**
(`meal_interval`, `lunch_interval`, `highBG_interval`, `FCL_interval` all 3). 1.25 U every 3 minutes
is 25 U/h of bolus against an insulin that needs 20 to 40 minutes to show. The loop re-doses 7 to 13
times before the first dose is visible. This is dead-time instability, not an aggressive setting:
the controller sits at its maximum output.

### 9.3 The trigger: the `post_hypo` guard releases mid-rise

Two storms out of three start on the very tick the guard releases:

```
09-13 20:30  post_hypo    -> None   BG 124  d+5.2   basal 0.60 -> 3.96   (then 19.4 U on board, low 48)
09-14 07:01  below_target -> None   BG 115  d+4.5   basal      -> 4.81
09-14 18:30  post_hypo    -> None   BG 127  d+17.0  basal      -> 5.49
```

The guard holds basal at profile while glucose recovers, then lets go while glucose is **still
rising** on the rescue carbohydrate, and the loop reads that rebound as a meal. Hypoglycaemia number
one builds hypoglycaemia number two, deeper and five times longer. This is the oscillator of
`loop-oscillator-not-caused-by-basal-debt`, with the closing point now identified: the release of the
guard, not the descent.

At export time (19:08) the next cycle was already loaded: storm 3 finished, 4.65 U on board, glucose
144 falling at −2.8 per 5 min, basal already 0 and `eventual` pinned at 39.

### 9.4 The two Autodrive options the user switched off

**`descent_redose_guard` — "Block bolus re-dosing during a descent". Measurable, and measured.**
It writes a true shadow verdict when the key is off. Over the three packages that carry the field
(~4200 ticks) it says block on **13 ticks in total**, all inside one 13-minute window,
2026-09-11 15:49–16:02:

```
peak=191  age=76..91 min  drop=52 -> 32  trough=136  rebound=2.7 -> 23.4  bg 140 -> 160  iob 6.2 -> 7.0
```

Glucose was **climbing** through all 13 of them and went on to 182. The gesture fired against a new
rise — the one case its own summary says it does not block — because the 25 mg/dL rebound limit lets
a 23 mg/dL climb through. It would have withheld 4.53 U at the wrong moment.

On the one occasion in this package where its intent applies (peak 241, 19.4 U on board, hard fall to
48) it never armed: `peak_too_recent` until 21:42, then from 21:50 the reason goes null because no
bolus is on the table any more. **Its arming conditions and the loop's own dosing window barely
overlap** — by the time the guard is allowed to refuse a bolus, the loop has already stopped asking
for one. Reason codes over a full package: `peak_too_low` 293, `peak_too_recent` 29,
`drop_too_small` 23, `descent_redose` 13, `rebound_above_trough` 3, `iob_too_low` 1.

Verdict: as written it fires only in the wrong direction and never in the right one. Its premise —
over-dosing on the descent — is not the failure mode in this data either; the damage is done on the
rise (9.2).

**Removed on 2026-09-14, on the user's call.** Gone: `smb/DescentRedoseGuard.kt`,
`DescentRedoseGuardTest.kt` (31 tests), the `evaluateDescentRedoseGuard()` helper and its call block
in `finalizeAndCapSMB`, the three `descent_redose_guard_*` fields and their JSON export,
`BooleanKey.OApsAIMIDescentRedoseGuard`, its two strings and its line in the Autodrive preference
screen. No reference is left in `plugins`, `core` or `app`. Users who had the key stored keep an
orphan entry in their preferences, which is harmless.

**`effort_smb` — "Effort/activity SMB protection". NOT measurable while off, and NOT inert.**
Unlike the guard above, its shadow does not compute when the key is off: it writes 1.00. Proof —
2026-09-14 09:32 to 09:46, 380 then 202 steps, heart rate 87, a bolus on the table, requested factor
**1.00**; the same conditions in the earlier packages requested 0.45.

With the key on, over five packages and 2110 dosing ticks, it asked for a reduction on **515 ticks
(24.4 %)** with a requested factor around 0.45–0.56 — it roughly halves the bolus on a quarter of
dosing ticks. The 1.59 % measured in this package is the key being off, not a measurement.

Do **not** delete it on this evidence.

**Shadow path added on 2026-09-14.** `refreshEffortActivityBelief()` now computes the belief on every
tick, armed or not. The disarmed branch runs on its own `lastEffortMemoryShadow`, so it can never
move the memory the armed path reads; `lastEffortAssessment` stays null when disarmed, so no dosing
path sees it and the tick is bit-identical. `effort_smb_factor_requested` is now read from the shadow
and carries what the belief really asked for; `effort_smb_factor_applied` still comes from the armed
path, so it is the truth about the dose. A new boolean `effort_smb_armed` tells the two apart, and
`effort_smb_floored_by_meal` is null when disarmed instead of being derived from a comparison that no
longer holds.

**How to check it on the next package:** with the key still off, `effort_smb_armed` must be `false`
while `effort_smb_factor_requested` goes below 1.0 on walking ticks. On the 2026-09-14 data those
ticks are 09:32–09:46 (380 then 202 steps, heart rate 87), where the old build wrote 1.00. Then
compare the reduction it asks for against the hypoglycaemia episodes, with the discrimination test of
section 5, before arming the key.

**Not covered by an automated test:** the wiring itself lives in a private method of
`DetermineBasalAIMI2` and the scenario harness does not drive the effort path. Two tests were added
to `EffortActivityBeliefTest` for the property the design rests on — two separate memory chains over
the same ticks agree, and advancing the shadow memory leaves the armed one untouched (18 tests, 0
failures). The wiring is verified from the export, as above.

Note also `effort-activity-single-system`: `EffortActivityBelief` is the only activity path, and this
is its bolus-side consumer.

### 9.5 The stress floor summary string is stale — user-facing

`pref_summary_aimi_stress_isf_floor` in `core/keys/src/main/res/values/strings.xml` said "fewer than
100 steps in the last 15 minutes" while `StressIsfFloor.MAX_STEPS_LAST_15M` is **250**, and it did
not mention `EXIT_GRACE_MINUTES` at all. The constants changed in `517e55f795`, the string did not.

**Fixed on 2026-09-14**: 100 → 250, plus one sentence for the 5-minute exit. English version only,
per `CLAUDE.md`.

### 9.6 Two data traps found in this package

- **Never sum `smbGiven` from the CSV per minute.** The value repeats across consecutive minutes, so
  the sum overcounts badly (it gave 20.8 U over one night hour, which is false). Every bolus figure
  in 9.2 comes from the change in insulin on board minus the integrated basal, which is a **lower
  bound** — the safe direction.
- The CSV of this package spans 09-09 → 09-14 but holds **no row at all for 09-13**
  (398 / 843 / 817 / 318 / 0 / 624 rows per day). It does not cover the window of the JSONL.
- `eventual_bg == 39` on **34.8 %** of ticks against 25.0 % on the reference corpus. The artefact is
  getting worse, not better.

---

## 10. The Autodrive storm: three candidates measured, one shipped disarmed

Corpus for all of it: every support package deduplicated by timestamp, restricted to **2026-09-02 →
2026-09-14** where `basal_terminal` and `bound_by` are complete. **12451 ticks over 12 days** — wider
than the 6950 the earlier work used. The August packages carry the fields too but sample about one
tick in five, so they were left out.

### 10.1 Two candidates refuted before a line of code

**The `post_hypo` release point — refuted.** Section 9.3 made it look like the obvious lever. It is
not. Over 32 releases with at least 30 minutes of follow-up, 8 were followed by a reading under 70.
The rise at the moment of release does not separate them:

```
went low : +3.2 +3.2 +15.3 +5.3 +3.1 +3.0 +3.4 +2.3
stayed ok: +3.2 +5.5 +15.2 -0.2 +0.6 -0.1 +3.0 -0.6 +4.1 -0.7 +0.4 +2.8 +3.3 +16.2
           +8.1 +7.8 +13.5 +8.6 +7.8 +3.2 +3.0 +2.8 +3.0 +16.2
```

The three fastest rises at release are all in the **good** group. "Do not release while glucose
climbs" would have hit the good episodes and let the bad ones through. Nothing at the release tick —
glucose, rise, insulin on board, the rate before or after — separates the two groups.

**A rolling insulin-stacking budget — refuted.** Share of ticks followed by a reading under 70,
by how much insulin on board rose over the previous 30 minutes: under 0.5 U → 22.9 %, 0.5–1 → 18.5 %,
1–2 → 17.3 %, 2–3 → 24.6 %, 3–4 → 33.1 %, over 4 → 23.3 %, against a base rate of 22.6 %. Not
monotone, and never far from the base rate.

### 10.2 The candidate that passed: the ceiling repeated during a fast rise

Counting ticks in a row where the decided bolus lands **exactly on a configured ceiling**
(2.00 / 1.25 / 0.80 U here):

| ticks in a row at the ceiling | ticks | followed by a reading under 70 within 3 h |
|---|---|---|
| 0 | 11619 | 22.3 % |
| 1–4 | 77 | 42.9 % |
| 5–9 | 49 | 49.0 % |
| 10–19 | 40 | 50.0 % |

**It is not "glucose is high".** Inside one glucose band and one rise band:

| glucose | rise | not at the ceiling | at the ceiling ≥ 3 ticks |
|---|---|---|---|
| 140–180 | +3 to +8 | 27.5 % (n=346) | 14.5 % (n=76) |
| 140–180 | over +8 | 16.9 % (n=278) | **90.6 % (n=32)** |
| 180–400 | over +8 | 30.5 % (n=164) | **100 % (n=18)** |

The signal is the **conjunction**: pinned at the ceiling *and* climbing fast. On its own, either one
says nothing.

**Discrimination test.** Method: replay over the corpus; one intervention = one contiguous run where
the candidate fires; cost = decided bolus withheld from bursts whose next 3 h stayed at or above 70;
gain = the same from bursts that went under 70.

| candidate | ratio | on low | on ok |
|---|---|---|---|
| ceiling ≥ 3 ticks **and** rise ≥ +8 | **18.55** | 7 | 1 |
| ceiling ≥ 1 tick and rise ≥ +8 | 9.68 | 8 | 5 |
| ceiling ≥ 5 ticks and rise ≥ +8 | 13.53 | 4 | 1 |
| ceiling ≥ 3 ticks, any rise | 1.24 | 8 | 9 |
| ceiling ≥ 3 ticks and rise ≥ +5 | 1.38 | 8 | 9 |

For scale, the stress ISF floor — the only gesture that had ever passed — scores 4.29.

### 10.3 Why it ships disarmed anyway

Three reservations, all of them real:

- **The sample is 6 distinct bad episodes.** 23 at-ceiling bursts in the corpus, 6 followed by a low,
  15 not. Consecutive minutes are not independent observations.
- **The ratio depends on where the line is drawn.** 18.55 at 70 mg/dL, but **1.99 at 60 mg/dL** — on
  the severe lows, the ones that matter most, the gesture is only about twice as targeted as chance.
- **The rise threshold looks fitted.** +6 gives 1.87, +8 gives 18.55. A cliff that sharp between two
  neighbouring values, on this few episodes, is what overfitting looks like. Leave-one-day-out is
  reassuring (9.88 to infinity, no single day carries it) but it cannot fix a threshold chosen after
  seeing the data.

So the same convention the descent guard used, and which worked: **compute and export on every tick,
change nothing until the user arms the key.** The next packages rebuild the same numbers on data
nobody has looked at, with the thresholds fixed in advance. That is the measurement this candidate
still needs.

One cost the test does **not** measure: it counts lows avoided, not highs caused. On this corpus the
single ok-episode it would have cut peaked at 157 mg/dL, but one example says little. Expect to run
higher after a meal once it is armed.

### 10.4 What was built

- `plugins/aps/.../smb/RiseCeilingGuard.kt` — pure object, no clock, no state, so a test can replay a
  real trace tick by tick. `MIN_REPEATS` 3, `MIN_RISE_MGDL_PER_5MIN` 8.0, `MAX_GAP_MS` 15 min.
- Call site in `finalizeAndCapSMB`, where the descent guard used to sit. **The count is taken on the
  bolus before the guard refuses anything** — counting the refused value would drop the run to zero
  on every second tick and the gesture would hold back one tick in three instead of the whole repeat
  that was measured.
- Four export fields: `rise_ceiling_guard_would_block`, `_reason`, `_repeats`, `_withheld_u`.
- `BooleanKey.OApsAIMIRiseCeilingGuard`, default **false**, on the Autodrive screen.
- `RiseCeilingGuardTest`: **24 tests, 0 failures**, including replays of the 09-13 20:50 and
  09-14 12:40 bursts and of the 09-12 23:10 burst that must be left alone.

One real bug was caught by its own test while writing it: the reason string formatted the rise with
the default locale, so it would have exported `delta=16,2` on a French phone and broken every reader
of that field. Fixed with `Locale.US`.

**How to read the next package.** With the key still off, look at `rise_ceiling_guard_would_block`:
count the distinct bursts it fires on, and the nadir in the 3 h after each. The candidate is
confirmed if the ratio stays clearly above 1 **with the line drawn at 60 mg/dL**, not only at 70.

---

## 11. MCER: the dose floor was pinned to current glucose

From a field report by Thomas Willems on HEAD `741665c704`, checked line by line here and then
measured on package `1789506919933` (2026-09-14 23:15 → 2026-09-15 23:15, 1308 ticks). All three of
its claims hold.

### 11.1 The bug

`DecisionPredictionAuthorityResolver` read `scenarioProjection?.scenarioBest?.pathMinMgdl` — the
**display** series. `ScenarioProjectionCurve` says in its own KDoc that dose-facing gates must use
`gatePathMinMgdl` (= `preLiftPathMinMgdl`), and `ScenarioProjectionEngine` computes that pre-lift
value explicitly, commented *"Gate path-min must reflect the curve BEFORE meal-absorption lift
(dose-facing safety)"*. `applyMealAbsorptionTerminalFloor` even says it avoids pinning the horizon
because that "poisoned dose-facing path-min gates". The trap was known and guarded; MCER was the one
consumer that walked into it. The other three (`DetermineBasalAIMI2` lines ~3861, ~10658, ~10865)
use the gate value.

The lift ramps every point from `bg` upward, so after it the display trough **cannot** be below
current glucose. MCER therefore released the dose-governing floor to current glucose, which destroys
the self-limiting property its own preference KDoc promises ("stacked IOB pulls that trough back
down automatically"). With 11.6 U on board the released floor was still 269.9.

### 11.2 Measured, which the report could not do

Both values are exported (`best_path_min_mgdl`, `best_gate_path_min_mgdl`), so the report's
verification ask 2 is answered:

| | |
|---|---|
| `best_path_min` equal to current glucose (within 1 mg/dL) | **81.0 %** of 1308 ticks |
| gap display − gate | median 4.3 · **mean 25.0** · max **192.9** mg/dL |
| ticks where display is ≥ 20 mg/dL above gate | **43.0 %** |
| ticks where the gate value already sits at the 39 floor | 23.9 % |

On the 44 ticks carrying the MCER signature (`min_pred` == display path-min, display > gate + 5):
`min_pred` median **196** against a gate median of **114**. The floor that governs the dose was held
**36 mg/dL higher** than the gate curve said, up to **159**.

`key_aimi_meal_confirmed_early_release` is `true` on this device too, so this was live here, not only
on the reporter's phone.

### 11.3 The prediction itself is not usable — measured

The same package, `eventual_bg` against the glucose actually reached 60 minutes later, 710 ticks:

- absolute error **median 69 mg/dL**, mean 97
- **29.0 %** of ticks predict exactly **39**; glucose actually reached a median of **107**
- **6.8 %** predict ≥ 400; glucose actually reached a median of **96**
- even between 60 and 300, the median absolute error is **45 mg/dL**

Saturated against one rail or the other 36 % of the time, and wrong by 45 mg/dL in between — and this
is the signal that steers the commanded basal and the dose floor. This is the same open subject as
`pkpd-floor-39-contamination`; it is now quantified end to end.

### 11.4 What was fixed

1. **`DecisionPredictionAuthority.kt` — the gate value.** One identifier, `pathMinMgdl` →
   `gatePathMinMgdl`.
2. **The tail breaker is now latched.** New pure object `risk/MealConfirmedEarlyReleaseLatch.kt`; the
   resolver is an `object`, so the state is carried by `DetermineBasalAIMI2` (`mcerTailLatch`), the
   same shape as `RiseCeilingGuard`. It latches on the phase or falling breaker, and releases when
   glucose is back under `target + 20` (below which MCER could not arm anyway) or insulin on board is
   down to half of what it was at the trip. The IOB breaker is **not** latched — its headroom returns
   on its own, and latching it would keep MCER off all day. New reason code `tail_latched`.
   `IOB_RELEASE_FRACTION` 0.5 is a judgement, not a measurement; it is stated as such in the KDoc,
   and the latch can only keep an opt-in escalation off.
   Why it was needed: 2026-09-15, peak 13:52, then at 14:38 the absorption phase left
   `PEAK_CORRECTION` for exactly one tick while the sensor stepped 215.8 → 240.2; all three stateless
   breakers released together and the loop went back to the ceiling basal with 8.87 U on board.
3. **The smoothed delta is passed.** `combinedDeltaMgdl5m = tickCombinedDelta.toDouble()` instead of
   the raw `delta`. The parameter was always named for the combined signal.
   **Not one-directional:** `tickCombinedDelta` is amplified (`× 1.30`) in one branch, so it can be
   larger than the raw delta and MCER arming can move either way. What is gained is consistency with
   the rest of the gate, not a guaranteed reduction.

Left undone from the report: fix 4 (the IOB breaker anchored to `max_iob` with a 1.5 U margin, so
"stacked" means IOB ≥ 14.5 U on a `max_iob` of 16) and fix 5. Fix 4 needs a measurement first.

`MealConfirmedEarlyReleaseLatchTest`: 12 tests, 0 failures, including the 14:38 tick replayed.
`:plugins:aps` 292 classes, **1779 tests, 0 failures**; `:core:keys` 8 tests, 0 failures.

---

## 12. TPO rewrites dosing preferences from inside the loop

The user noticed `key_openapsaimi_high_bg_max_smb` change on its own. It did: **1.25 on 2026-09-14,
2.0 on 2026-09-15**, with `key_openapsaimi_max_smb` unchanged at 0.8. The runtime trace dates it
between 12:38 and 17:14 on the 15th (at both points the physio multiplier is 1.0, so
`max_smb_high_bg_u` is the stored value).

### 12.1 The mechanism

Unlike MCER, which only the preference screen writes, this key has automatic writers, and one of
them runs in the loop:

1. `DetermineBasalAIMI2:2389` calls `tpoOrchestrator.onTickStart(...)`, and `:3688`
   `onPatientStateReady(...)` — every tick.
2. `TpoOrchestrator.onPatientStateReady` builds a plan and, when `OApsAIMITpoLlmConfirmEnabled` is
   off, calls `sessionManager.startSession(plan, preferences, ...)`, which **writes the preferences
   directly**, with no user action, and sets `prefsChangedThisTick`.
3. The loop consumes that flag at `:3699` and re-reads the key on the next line.
4. `BooleanKey.OApsAIMITpoEnabled` defaults to **true**. The autonomy gate is met as soon as
   autodrive is active, which it is here.

### 12.2 It does revert — the design is sound

`startSession` stores `baseline` and `overlay`, TTL **45 min**. `onTickStart` → `expireIfNeeded`
every tick, and past the TTL `revertSession` restores every baseline key not in `userOwnedKeys`.
While the session is live, `trackUserOwnedKeys` marks any key whose current value no longer matches
the overlay, so a value changed by hand is not clobbered. `revertNow` and `supersedeActiveSession`
also revert.

### 12.3 Three holes

1. **`userOwnedKeys` cannot tell the user from another writer.** The test is only "current ≠
   overlay". The advisor tuning plan or the Control Center ladder writing the same key during a
   session marks it user-owned, and from then on the baseline is **never** restored: the overlay
   value becomes permanent. This is the one path by which a value stays silently changed.
2. **The revert lives only in the session file** (`tpo/tpo_session.json`). Lose it while an overlay is
   written — cleared data, reinstall, a write failure — and nothing ever restores the baseline.
3. **The support package carried no TPO trace at all** — no session, no baseline, no start/revert
   record, in neither the report nor the 45 MB JSONL. `AdvisorHistoryRepository` logs
   `TPO_SESSION_START` / `TPO_SESSION_REVERT` but that history is not exported.

### 12.4 Why the observed change is still unexplained

The TTL is 45 minutes, and `HighBGMaxSMB` read 1.25 continuously from 2026-09-14 23:25 to
2026-09-15 12:38 — over 13 hours, with the loop ticking every minute. **No live overlay can last
that long**, so the 1.25 was a stored value, not an active session. Either a session left it there
through hole 1 or 2, or something else wrote 2.0 later. `TpoDeltaBuilder` only ever steps this key
**down** (`stepDownLadder` on `[1.00, 1.25, 1.60, 2.20, 3.00]`), and two rungs below 2.0 is exactly
1.25 — consistent with 2.0 being the user's own value and 1.25 a leftover, but not proof.

### 12.5 What was added

A `[TPO SESSION]` section in `AimiDiagnosticsManager`. It prints whether TPO is enabled, the last
revert per pack, and — for every key a session touched — the user's value, the value the session
wrote, and the value live now, with `USER-OWNED, will never be put back` on any key hole 1 has
captured. A session past its expiry that is still written says so in words. No session is stated in
words too, because an empty section would read as "TPO did nothing".

`TpoPersistence(AimiStorageHelper(context, logger))` is built locally: same module, no new
dependency, no injection change, no change at the call site. `AimiDiagnosticsActiveProfileTest` still
3 tests, 0 failures.

**Second, separate effect, this one by design:** the *runtime* ceiling moves every few minutes
through `MaxSmbLadder` — branches `STANDARD`, `CONFIRMED_RISE_HIGH`, `CONFIRMED_RISE_HIGH_BY_DELTA`,
`PLATEAU_MODERATE_75`, `SENSITIVE_85`, values from 0 to 2.0 inside one hour on 2026-09-15. A second
reason the cap looks unstable, unrelated to the stored preference.

### 12.6 Still open

- **The decision, not a fix:** a subsystem on by default that rewrites `MaxSMB`, `HighBGMaxSMB` and
  `PriorityMaxIob` from inside the loop without confirmation. Either default `OApsAIMITpoEnabled` to
  false, or keep it and rely on the new export. Not changed — it is a safety default and the user's
  call.
- Hole 1 deserves a real fix: compare against the *baseline* the session wrote, or stamp who wrote a
  key, instead of inferring ownership from "the value moved".

---

## 13. The rise ceiling guard, first prospective look — not confirmed

Package `1789506919933` is the first with the shadow verdict running. It fired on three bursts, all
followed by a low:

```
09-15 12:45->13:17  33 blocking ticks  glucose 148 -> peak 241  low 58
09-15 17:27->17:34   8 blocking ticks  glucose 149 -> peak 158  low 63
09-15 20:44->21:18  31 blocking ticks  glucose 149 -> peak 214  low 59
```

**Three out of three proves nothing here.** On this package the base rate is **75 % under 70** and
**45.6 % under 60** over the ticks with 3 h of follow-up: almost any window is followed by a low. The
day ran 29 % of the time under 70. What is confirmed is that the gesture fires where it was predicted
to; the discrimination needs a day with a real mix of good and bad episodes. Keep collecting.

---

## 14. Does the prediction and trajectory layer stabilise the loop?

The user's question, and the most useful hour of the week. Corpus: every package deduplicated,
2026-09-02 → 2026-09-16, **14755 ticks**. Base rate over it: a reading under 70 follows within 3 h on
**27.7 %** of ticks, under 60 on 10.6 %.

### 14.1 Yes — the prediction layer brakes, and hard

| | |
|---|---|
| prediction **lowered** below the pkpd-only value | **50.7 %** of ticks, median **−75 mg/dL** |
| prediction raised | 10.8 %, median +3 mg/dL |
| on a fast rise (delta ≥ +8) | **118 of 118 lowered, 0 raised** |
| most frequent authority | `SCENARIO_SUPPRESSED_NON_MEAL`, 37.7 % |

It is not a one-way ratchet upward. The scenario layer is overwhelmingly a reducer.

### 14.2 Two numbers, and they are NOT a bug — correction of a wrong reading

`pred_terminal` and `eventual_bg` agree within 5 mg/dL on only 29.1 % of ticks, and on a fast rise
`eventual_bg` runs a median **+219 mg/dL** higher. That was first read here as "two predictions of the
same thing disagreeing, and the basal reads the unbraked one". **That reading is wrong.** In
`DecisionPredictionAuthorityResolver`:

- `predTerminal` starts as `rawScenarioFloor ?: pkpd` — the **clinical floor** curve, insulin only,
  the pessimistic estimate. It feeds `min_pred`, the tube advisor and the PKPD guards, and it is
  *supposed* to be low.
- `eventualTerminalMgdl` is `pkpd` or `max(pkpd, scenarioBest)` — the **expected** estimate. It feeds
  the correction and the basal, and during a meal it is *supposed* to be higher.

Worst case for the guards, expected case for the corrector. That is the right architecture. The
+219 mg/dL gap on a rise is the design working, not a defect — and it is why the basal sat at profile
(0.55–0.60 U/h) on the very ticks with the biggest gap.

**The replay that was planned off that misreading — make the basal read `pred_terminal` — was not
run, and must not be: it would make the corrector dose against the pessimistic floor and never
correct a meal.**

### 14.3 What IS wrong: the corrector's slope is far steeper than its input's accuracy

The basal as a function of `eventual_bg`, over the corpus:

| `eventual_bg` | ticks | commanded basal, median | profile |
|---|---|---|---|
| railed low (39) | 4478 | **0.00** U/h | 0.50 |
| 40–119 | 4599 | 0.12 | 0.50 |
| 120–249 | 4029 | 1.18 | 0.53 |
| 250–399 | 1305 | **5.28** | 0.60 |
| railed high (≥ 400) | 344 | 5.49 | 0.60 |

**A 4.5× jump in commanded basal across one band boundary**, while the profile stays at 0.60. And
that input is against a rail **32.7 %** of the time (exactly 39 on 30.3 %, ≥ 400 on 2.3 %; on rising
ticks ≥ 400 reaches 16.1 %), with a median absolute error of 69 mg/dL at a 60-minute horizon
(section 11.3).

A near-step corrector steered by a number that is wrong by 69 mg/dL and railed a third of the time is
the stabilisation failure. Not the absence of a predictive layer — the mismatch between the
controller's gain and its input's accuracy. **This is the one finding of section 14 that no candidate
has yet addressed, and it is testable by replay.**

### 14.4 The trajectory layer diagnoses correctly and has almost no authority

Its own verdict on 2026-09-16: `OPEN_DIVERGING` 29.7 %, `TIGHT_SPIRAL` 22.3 % — **52 % diverging or
spiralling** — against `CLOSING_CONVERGING` 20.6 % and `STABLE_ORBIT` 4.7 %. That matches the
oscillation measured independently. The diagnosis works. The authority does not:

| route | real power |
|---|---|
| prediction authority (`SCENARIO_TRAJECTORY_UPLIFT`) | **0 ticks of 1431** |
| ISF (`isf_trajectory_multiplier`) | moves on 49.8 % of ticks but bounded to **±10 %** (`aimi_dyn_isf_trajectory_max_fraction: 0.1`), median 0.968 |
| dosing via HTR | active 27 % of ticks — but HTR **releases** insulin, it does not brake |

A classifier that says "spiral" a fifth of the time and whose largest response is ±10 % on sensitivity
cannot stabilise anything.

### 14.5 The saturation candidate — strong marker, failed gesture, REJECTED

Matched inside one glucose band, rising ticks only, `eventual_bg ≥ 400` against 250–399:

| glucose | 250–399 | ≥ 400 |
|---|---|---|
| 90–130 | n=395, basal 1.05 → **24.8 %** under 70 | n=45, basal 0.59 → **57.8 %** |
| 130–170 | n=474, basal 5.61 → **23.8 %** | n=94, basal 6.16 → **51.1 %** |
| 170–400 | n=224, basal 5.49 → **31.2 %** | n=179, basal 6.00 → **51.4 %** |

The risk roughly **doubles** in all three bands, and the basal is nearly identical between the columns
— in the 90–130 band the railed ticks even get *less* basal and produce *more* lows. So the rail does
not drive the dose, it **marks** the state.

Six gestures built on it, all replayed with the discrimination test of section 5:

| candidate | channel | ratio at 70 | ratio at 60 |
|---|---|---|---|
| eventual ≥ 400 → cap basal at profile | basal | 1.31 | **0.41** |
| eventual ≥ 400 → cap basal at 2× profile | basal | 1.33 | — |
| eventual ≥ 400 and rising → cap at profile | basal | 1.31 | — |
| eventual ≥ 350 → cap basal at profile | basal | **0.90** | — |
| eventual ≥ 400 → refuse the bolus | SMB | 1.24 | **0.38** |
| eventual ≥ 350 → refuse the bolus | SMB | 0.91 | — |
| eventual ≥ 300 → refuse the bolus | SMB | 0.74 | **0.33** |

**All rejected.** None is clearly above 1, and every one gets *worse* at the 60 mg/dL line — it would
withhold more from episodes that did not go severely low. The reason is the confounding the doc warns
about: railed ticks concentrate on big meals, which legitimately need insulin, so a uniform gate hits
the good episodes harder. A strong marginal association is not a gesture.

Two further lessons worth keeping: on the basal channel the candidate withholds only **26.3 U over
15 days** (≈ 1.8 U/day) because the railed windows are minutes long — the marker is right, the lever
is tiny. And moving the same signal to the SMB channel, where the damage is, does not help either.

### 14.6 The 39 rail: an artefact, but not a demonstrated dosing harm — correction

`eventual_bg` is exactly 39 on 30.3 % of ticks, and the basal is then a median 0.00 U/h against a
0.50 profile. That has been carried in this project (and in the memory note
`pkpd-floor-39-contamination`) as a dosing defect. **On this corpus it is not one.** Of 4064 ticks
with `eventual == 39` and basal held at or below half profile:

- glucose 60 min later **rose** by more than 10 mg/dL on 42.5 % — but from a median of **85** to 122,
  and switching basal off at 85 is what one would want;
- it **fell** by more than 10 on 31.8 %, median 118 → 84 — the zeroing was right;
- flat on 25.7 %;
- and of the 64 railed ticks where glucose was already at or above 150, it **fell on every one**
  (median 159 → 106) — so the zeroing never held basal off through a real high.

The value 39 is still an artefact and it still poisons the basal ML labels and the SMB gate, which is
the part of the memory note that stands. What does not stand is the claim that it drives hypoglycaemia
through the basal channel.

### 14.7 Where this leaves the search

Rejected today: the `pred_terminal` rewiring (wrong by construction), six saturation gestures, and
the 39-rail dosing-harm hypothesis. That brings the running tally to **13 candidates dropped by the
discrimination test, 2 passed** — the stress ISF floor at 4.29, and the rise ceiling guard at 18.55,
the second still unconfirmed prospectively (section 13).

The direction named here as "the one live, untested one" — flattening the corrector's gain — was
replayed the next hour and **also failed**. See section 15.

---

## 15. The corrector's gain: replayed and rejected. And two real bugs in the TPO revert.

### 15.1 The gain candidate — REJECTED, six spans

The gesture: pull the commanded basal back toward profile in proportion to how close `eventual_bg`
sits to the high rail (401). Reduction only, never raises, never touches a rate at or below profile.
`span` is the width of the ramp in mg/dL — at `span` 150 the pull-back starts at eventual 251 and is
complete at 401. Replayed over the same 14755 ticks.

| span | interventions | on LOW | on ok | withheld LOW | withheld ok | ratio @70 | ratio @60 |
|---|---|---|---|---|---|---|---|
| 40 | 53 | 21 | 32 | 18.16 U | 16.32 U | 1.11 | **0.37** |
| 60 | 54 | 20 | 34 | 19.69 | 18.55 | 1.06 | **0.38** |
| 100 | 73 | 27 | 46 | 22.07 | 24.81 | 0.89 | **0.34** |
| 150 | 111 | 35 | 76 | 25.42 | 34.79 | 0.73 | **0.30** |
| 200 | 147 | 38 | 109 | 30.10 | 48.32 | 0.62 | **0.26** |
| 300 | 242 | 45 | 197 | 42.34 | 76.89 | 0.55 | **0.21** |

Monotone: the wider the ramp, the worse. At the 60 mg/dL line every variant sits between **0.21 and
0.38**, i.e. it withholds about three times more from episodes that never went severely low.

**The generalisation, now robust.** `eventual_bg` cannot be the trigger of any withholding gesture.
Thirteen formulations have been replayed — a hard basal cap at profile and at 2× profile, the same
with a rising condition, a lower threshold, three SMB vetoes, and six graded gains — and **all
thirteen fail**. The reason is always the same: a high `eventual_bg` marks a big meal, and a big meal
legitimately needs insulin, so any gate keyed on it hits the good episodes hardest.

The one gesture that did pass (18.55) keys on something different in kind: **the dose being pinned at
its own ceiling, repeatedly, during a fast rise** — a property of the controller's *behaviour*, not a
value of the prediction. That distinction is the lesson to carry: gate on what the controller is
doing, not on what the prediction says.

Running tally: **19 candidates dropped by the discrimination test, 2 passed.**

### 15.2 Two real bugs in the TPO revert — fixed, TDD

Not a dosing candidate: a correctness defect, found by reading section 12.3 hole 1 properly and
proven by a failing test before any code was written.

`revertSession` decided key by key with `if (key in session.userOwnedKeys) return@forEach`.
`userOwnedKeys` was filled by `trackUserOwnedKeys` on every tick where the live value differed from
the overlay, and that set **only ever grew**. Two failure modes, both reproduced as failing tests:

1. **The session's own value became permanent.** One tick of divergence marked the key for the rest
   of the session, so the revert skipped it for good: the overlay stayed in the preferences and the
   user's baseline was silently lost. A transient was enough — another writer, a value the loop
   scaled for a moment, or the user changing a setting and changing it straight back. This is the
   mechanism that can leave `key_openapsaimi_high_bg_max_smb` stuck at a ladder rung (section 12.4).
2. **Somebody else's change was overwritten.** For any key *not* in the set the baseline was restored
   unconditionally, so a change the tracking had not happened to observe — `revertNow` called
   directly, or a divergence between the last tick and the expiry — was clobbered. This one was not
   in section 12.3; the test found it.

**The fix.** New pure object `tpo/TpoRevertPolicy.kt`. At revert time, restore the baseline **iff the
value in force is still the one the session wrote**. If it is, nothing else has touched the key; if it
is not, somebody else owns it now. `observedDivergence` is still passed from `userOwnedKeys` so the
call site shows it explicitly, and it deliberately does **not** block a restore — that stickiness is
the bug. `valuesDiffer` in `TpoSessionManager` now delegates to `TpoRevertPolicy.sameValue`, so the
whole revert path has one definition of value equality.

The failure this cannot avoid is milder and rarer, and is stated in the KDoc: if somebody sets a key
to the very value the session wrote, the revert puts the baseline back against their wish. Nothing can
tell that apart from the session's own write, and losing a baseline for good is the worse of the two.

`TpoRevertPolicyTest` 10 tests, `TpoSessionManagerRevertTest` 4 tests, both 0 failures — the two
bug-reproducing tests were watched failing first (`put` not called; `put` called when it must not be).
Whole run: `:plugins:aps` 294 classes, **1793 tests, 0 failures, 0 errors**; `:core:keys` 8 tests, 0
failures.

### 15.3 Two things found and deliberately not changed

- **`key_oaps_aimi_dynisf_factor_0_1` … `23_24`** — the 24 hourly dynISF factors stored on the device
  (200 at night, 500 from 12:00 to 16:00) are read by **nothing**: zero references in the repo outside
  build directories. They are dead preferences from an earlier version. The lever a user would reach
  for, `IntKey.ApsDynIsfAdjustmentFactor` ("DynISFAdjust", default 100, max 300), is read only by
  `OpenAPSSMBPlugin`, which is disabled on this device. **There is no dynISF knob in the AIMI path.**
  Removing or wiring them is a decision, not a fix.
- **`SCENARIO_TRAJECTORY_UPLIFT`** fired on **0 of 1431** ticks. Its guard needs four conditions at
  once (`!falseMealSuppression && strongRiseProjection && trajectorySupportsUplift && scenarioLead >=
  20.0`). It is an *uplift*, so a dead branch here withholds nothing; worth diagnosing, not urgent.

---

## 16. The declared-meal button: what already existed, and what was built

The user asked for a button meaning "I am eating", so the loop stops waiting for the rise to confirm
a meal. Three rounds of design, and most of the answer was already in the code.

### 16.1 What already existed

`DetermineBasalAIMI2.kt:7240`, label **"Meal Boost 30min (Force MaxBasal)"**:

```kotlin
(mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime) && (max runtime) in 0..30 -> {
    val safeMax = if (mealModesMaxBasal > 0.1) mealModesMaxBasal else profileCurrentBasal * 5.0
    ... setTempBasal(boostedRate, 30, ..., overrideSafetyLimits = true, ...)
}
```

A declared meal forces the basal to `meal_modes_max_basal` — **10.0 U/h on this device** — for 30
minutes, with `overrideSafetyLimits = true` so it passes the 7 U/h `openapsma_max_basal`. That is
5 U, exactly what the user proposed. `capBasalRateForCorrectionAggression` only caps it under a
rebound guard or an exercise lockout, so at rest nothing holds it back.

The window and the cancel were already primitives too. `therapy.kt` matches a NOTE event whose text
holds a keyword **while `now <= event.timestamp + event.duration`** — the window lives in the note, so
it survives ticks and restarts — and `deleteLastEventMatchingKeyword(...)` at line 126 is the cancel.

**And it has never been used**: `meal_mode_active = true` on **0 of 6973 ticks** over the five most
recent packages.

### 16.2 Why it is not what he wanted

The meal modes fire a prebolus: `setLegacyPrebolusUnits(rbf(DoubleKey.OApsAIMIMealPrebolus), "MEAL_P1", mealruntime)`.

| key | default | **minimum** |
|---|---|---|
| `OApsAIMIMealPrebolus` | 2.0 U | **0.1** |
| `OApsAIMILunchPrebolus` | 2.5 U | **0.1** |

Neither is set on the device, so both are at their defaults, and **the minimum is 0.1 — it cannot be
switched off**. Pressing Lunch would give 2.5 U of prebolus *plus* 5 U of floor: 7.5 U in 30 minutes.
His objection was right and the separate mode is the correct answer.

### 16.3 Why the temp-target half was dropped

He proposed carrying the state on a temp target at 80 and gating the forced basal on "note AND temp
target" — a dead-man's switch, which is sound thinking. Two measured reasons not to:

**The loop already targets lower than 80 at lunch.** Every branch of the target `when` is guarded by
`!profile.temptargetSet`, and the rise branch computes
`max(baseTarget, profile.target_bg - (bg - target)/3)` with `baseTarget` 70 outside the hours
0–11/15–19/≥22. On 14755 ticks, the target the loop chose **by itself** on a rising tick
(delta ≥ +3, BG ≥ 120):

| hour | n | median | min | already under 80 |
|---|---|---|---|---|
| 00–11 | 764 | 90 | 90 | 0 % |
| **11–15** | 573 | **76** | **70** | **61 %** |
| 15–19 | 299 | 90 | 90 | 0 % |
| 19–24 | 587 | 90 | 70 | 29 % |

A flat 80 would be **less** aggressive on 61 % of rising lunch ticks — exactly where his worst
episodes are (nadirs 58, 57, 64) — and it loses the tracking, since the computed target descends as
glucose climbs.

**And it disables the protective branch.** `!profile.temptargetSet && combinedDelta <= 0 && predictedBg < 120 -> targetBg = min(hypoTarget, 166)`. Pinned at 80, the loop could no longer raise its
target to 110–166 during the 30 minutes right after 5 U went in. The target cannot be used as a flag
because `temptargetSet` is itself a control input.

The note's own duration gives the window, and deleting the note gives the cancel, with none of that.

### 16.4 What was built — TDD, both effects disarmed

The user chose: two separate keys, and a fixed unit budget.

**Keyword `anticip`.** Deliberately not a word containing "meal": `findActiveMealEvents` matches any
note holding "meal", so `premeal` would switch the meal mode on **and fire its 2.0 U prebolus** — the
one thing this mode exists to avoid. Checked against every keyword the parser matches (bfast,
breakfast, delete, dinner, fasting, high carb, highcarb, lowcarb, lunch, marche, meal, sleep, snack,
sport, stop, walk): `anticip` collides with none, `premeal` collides with `meal`. Two tests pin it.

- **`basal/AnticipationBasalFloor.kt`** — pure, no clock, no state. The budget is spread **evenly**
  over `WINDOW_MINUTES` 30, so the rate does not change as the window runs down and the whole window
  delivers exactly the budget above profile: nothing accumulates, nothing is left to spend, and it is
  a front-loaded anticipation rather than a chase. Returns a **floor**; the caller takes the larger of
  its own rate and this one. Stands down under `MIN_GLUCOSE_MGDL` 80, at a fall of
  `MAX_FALL_MGDL_PER_5MIN` −3 or faster, past the window, on a clock that moved back, on a zero
  budget, and on any input that is not a usable number. Both interlocks are judgement, not
  measurement, and say so in the KDoc — this is the **only gesture in the project that raises a
  dose**, so a conservative interlock costs little.
- **`therapy.kt`** — `anticipTime` flag, `findActiveAnticipEvents`, snapshot field, reset, and an
  entry in `clearActiveEventsOnStop`. Eight edits, mirroring `mealTime` exactly.
- **Call site** applied **after** the slew limiter, because a floor the limiter can clamp away is not
  a floor. Ceiling is `max(profile.max_basal, meal_modes_MaxBasal)`, so a declaration can never reach
  higher than a meal mode already can.
- **`DecisionPredictionAuthorityResolver.resolve(declaredMeal = ...)`** joins `treeMealEvidence`.
  With the key off it is `false`, and `false || X == X`, so the tick is bit-identical.

Keys, both **default false**: `OApsAIMIAnticipBasalFloor`, `OApsAIMIAnticipMealEvidence`, plus
`OApsAIMIAnticipBudgetU` (default 2.0, **min 0.0** unlike the prebolus keys, so a zero budget disarms
the gesture even with its key on).

**Two keys on purpose.** Tree meal evidence is one of the disjuncts of `strongMealConfirmed`, which
gates MCER — the gesture section 11 was spent fixing. Switching it on arms that release on demand, a
far wider effect than the basal floor, so the two must be measurable apart.

### 16.5 The sizing, which is still the weak point

A fixed budget is what the user chose, and it is the honest limitation to record: 5 U is **−250 mg/dL**
of potential after 11:00 (ISF 50) and **−600 mg/dL** before it (ISF 120), and with IC 8 it covers 40 g.
One number cannot serve both windows or every meal size. The budget preference is his to set, and the
carb-derived version remains the better answer if the fixed one proves awkward.

Tests: `AnticipationBasalFloorTest` 14, `TherapyAnticipationDetectionTest` 6, both 0 failures.
Whole run: `:plugins:aps` 296 classes, **1813 tests, 0 failures, 0 errors**; `:core:keys` 8 tests, 0
failures.

---

## 17. The tail latch was a regression, and it is mine

Two field reports from Thomas Willems on HEAD `f3de6740ee`, both verified line by line here. One of
them is about the latch section 11.4 introduced the same day.

### 17.1 What the report said, and it is right

`MealConfirmedEarlyReleaseLatch` tripped on **any** falling tick while MCER was merely enabled:

```kotlin
tailTripped = tailByPhase || tailByFall      // DecisionPredictionAuthority.kt:201
```

No check that MCER had armed, and none that there had been a peak. On 2026-09-16 the reporter's
glucose drifted down from a morning high — a shallow insulin-driven descent at low insulin on board
— which is exactly `tailByFall`, so the latch set with `iobAtTripU` at the bottom of that descent,
1.71 U. Then lunch started from 126 and neither way out was reachable: glucose never came back under
`target + 20` = 120, and insulin on board only grew. The latch held **MCER off for the whole meal**,
`OFF(tail_latched)` on all 17 rise ticks.

Their measured consequence: the bolus channel graded down to 0.10–0.50 U caps against MPC requests of
up to 1.81 U — about 92 % closed at the moment the loop wanted it most — and the correction moved
into the basal channel, 6.3 U above profile through a 5.0 U/h block. Half the insulin of the day
before, a lower peak, and a **deeper** low: 44.5.

Every claim checked: the unconditional trip (confirmed, line 201), both release conditions
unreachable during a rise (confirmed in `releases()`), and **no path resets the latch** — it appears
only at lines 11081, 11086 and 11511, so it persists across meals for the life of the object.

### 17.2 Replayed on this device's own corpus

The latch is pure by design, so it replays. Over 14755 ticks, 09-02 → 09-16, with `tailTripped`
approximated by `tailByFall` alone (the absorption phase is not in this export, so trips are
**under**-counted):

| | old latch |
|---|---|
| ticks latched | **5321 / 14755 (36.1 %)** |
| episodes | 3193 — it flickered on every falling tick |
| longest episode | **165 min** (09-12 15:10 → 17:55) |
| episodes ≥ 45 min | 15 |
| ticks blocking an otherwise-armable MCER | **215 (1.5 %)** |

And the reporter's exact trap appears twice here: **09-14 09:39 → 11:07, 88 min, `iobAtTrip` 1.79** —
a release needing insulin on board ≤ 0.90 U — and 09-15 09:50 → 10:42, 52 min, 1.88 → 0.94 U.

Two corrections to the record. **The report's mechanism detail is slightly off and its conclusion is
right:** the code latches on the **first** falling tick, not the last, but over a long descent it
latches, releases, and re-latches, ending up latched at the **lowest** insulin of the descent — which
produces exactly the pathology described. And **the first measurement made here was circular**: a
"meal start" was detected as a crossing of `target + 20` from below, which *is* the release condition,
so it trivially found the latch clear on 9 of 9. The honest metric is the 215 blocked ticks.

### 17.3 The fix

The first version replaced a stateless breaker with **state that had no episode**. The latch is now
bound to one:

- `State` gains `armedSeen`. Nothing can latch before MCER has armed — a fall with no earlier arm is
  not a post-peak tail, because there was no peak.
- `next()` gains `armedThisTick`, fed from a new `DecisionPredictionAuthority.mcerArmed`. The two can
  never both be true: MCER's `armed` requires `!tailBreaker`, and the breaker holds both tail tests.
- A release ends the episode and forgets both facts, so the next excursion starts clean.
- The remembered stack is floored at `MIN_TRIP_IOB_U` **3.0**, so the insulin way out stays reachable:
  a trip at 1.7 U used to set the threshold at 0.86 U, below what any meal correction immediately
  creates.

Replayed against the old one on the same corpus:

| | old | fixed |
|---|---|---|
| ticks latched | 36.1 % | **13.6 %** |
| episodes | 3193 | **96** |
| episodes > 60 min | 10 | 8 |
| longest | 165 min | 165 min |
| blocking an armable MCER | 215 | **183** |

**What the fix does not do, stated plainly.** The long episodes remain, because a 165-minute hold
inside an episode where MCER really armed, with glucose still above `target + 20` and insulin still
above half the trip stack, is the gesture working as designed. The reporter's fix 2 — also release on
`rising && aboveTarget && iob <= 3 U` — would shorten them. It was **not** implemented: the
`MIN_TRIP_IOB_U` floor already makes the insulin release reachable at ≥ 1.5 U, so a new meal starting
at a low stack releases on its own, and fix 2 adds a threshold that has not been measured. Their fix 5
(export `latched` and `iobAtTripU` in `smb_binding_trace`) is also still open and is the cheap one:
this hour was spent reading `reason` strings.

Four existing tests changed their expectations, each because the old expectation encoded the bug: a
trip now needs an arm first, and an unreadable or negative stack falls back to the floored value
instead of 0 (which used to close the insulin way out entirely). `MealConfirmedEarlyReleaseLatchTest`
**17 tests, 0 failures**; whole run `:plugins:aps` 296 classes, **1818 tests, 0 failures, 0 errors**.

### 17.4 The second report — verified, not mine, and not touched

`PR_BasalEngine_LevelOnlyHyperFactor_OverridesZero_PastPeak.md`. Every code claim confirmed at the
character level:

- `interpolateBasal(bg, combinedDelta)` declares `combinedDelta` and **never reads it**; above 180 the
  factor is ×5 whatever the direction.
- `BasalDecisionEngine.kt:517` — `input.bg > 150 && input.delta in -5.0..1.0`, and the reason string is
  `bg_over_180_stable_basal_factor`: the string says stable, the window is the falling side.
- `:393` — `combinedDelta in -2.0..15.0 && bgAcceleration > 0.0`.
- `CorrectionAggressionBasalCap.mergeEngineAndRtRates` returns `maxOf(engine, rt)` when
  `allowRocketBasalScale`, so a zero written earlier on a low prediction is **discarded**; and
  `Tier.FULL` sets `allowRocketBasalScale = true` **unconditionally** with `maxBasalScaleCap = 10.0`.
  MODERATE makes it conditional, REBOUND_GUARD forbids it. This is the most serious finding of the two
  reports on the merits.
- `PEAK_CORRECTION` is in the meal-absorption boost's eligible phases; `adjustBasalForMealHyper` uses
  `risingOrFlat = delta >= 0.3 || shortAvgDelta >= 0.2` with a factor of 8 or 10 and
  `minutesSinceMealStart in 0..120` as its only time bound.

One doubt raised here **resolved against the doubter**: `adjustBasalForMealHyper` takes
`isMealModeActive`, and the report says COB = 0 with no note — but the parameter is passed **`true`
hardcoded** at both call sites (7262 and 7316), so the guard reduces to the time bound and the branch
runs on the detected absorption phase alone, with no meal note. The report is strengthened.

**Scale on this device is much smaller than on the reporter's**, and worth recording so nobody
over-reads it:

| situation | n | basal median | × profile | ≥ 4× profile |
|---|---|---|---|---|
| BG > 150, rising (Δ > +1) | 1216 | 5.28 | **8.8×** | 99 % |
| BG > 150, falling in −5..1 | 754 | 0.53 | 1.0× | 27 % |
| BG > 180, falling in −5..1 | 201 | 1.45 | 2.7× | 37 % |
| falling faster than −5 | 346 | 0.00 | 0 | 0 % |

Insulin above profile while above 150 **and falling** in −5..1: **11.7 U over 15 days, 0.78 U/day**.
Ticks at ≥ 4× profile in that state are followed by a reading under 70 on **34.0 %** against a 27.7 %
base rate, under 60 on 13.9 % against 10.6 % — real but weak. The reporter sees far more because
their three caps are all 5.0 against a 0.66–0.75 profile, so the same ×8 saturates at 7.6× profile
instead of being absorbed.

Nothing in that report was changed: it is pre-existing code, it did not get worse, and the `max()`
merge deserves its own measured pass.

---

## 18. Heart rate and the undeclared meal rise — 2026-09-17

The user got about 8 U at 01:00 on a rise he did not eat for. Three analyst agents ran in parallel on
the three questions the main session was least sure of. Their reports changed two conclusions written
earlier in this document.

### 18.1 What actually happened, minute by minute

Package `1789632360901`, 2026-09-16 10:06 → 2026-09-17 10:06.

```
00:25-00:41  ISF floor ACTIVE   commanded ISF 120 (= profile)   heart rate 88 then 62
00:36        heart rate 88 -> 62 in one exported minute
00:42        ISF floor OFF      commanded ISF 120 -> 67.4       MCER ARMED   bolus 1.80 (its ceiling)
00:42-00:59  bolus at the 1.80 ceiling ELEVEN times in 18 min   IOB 0.47 -> 8.52
00:54        heart rate -> 100, but re-entering needs 10 unbroken minutes
01:05        ISF floor ACTIVE again, commanded ISF back to 120 — the insulin is already in
01:03-01:31  glucose 186 -> 56, bottom 54.4 at 01:29
```

Excursion **+112 mg/dL**. At the profile ISF 120 that needs **0.93 U**; at the commanded 67.4,
**1.66 U**. Delivered: **8.05 U** — 8.6× and 4.8×. The analyst pinned the delivery independently:
**5 boluses, +7.11 U, on a 6-minute pump cadence**, and confirmed that summing `final_u` overcounts
**4.4×** (1.80 logged on 11 ticks, 4-5 boluses actually landed). "8 U" is right to about 10 %.

**This was not a cortisol shape**, by the project's own measured criteria: the excursion is 4.3× the
26 mg/dL cortisol envelope and the rise reached 2.9× the 11.0 escape threshold. And the heart rate was
**62 — resting + 12, below what `isStressCortisol` requires** — through the whole dosing window. The
heart rate did not lead the rise: 88 before, 62 during, 100 after.

### 18.2 The heart rate is not a per-minute measurement

Measured over **11,644 logged minutes, 12 days** (agent 1):

- It is a **staircase**, refreshed about every 9 minutes (median 9, 45.5 % exactly 9, 18.7 % at 5).
  Over 2 hours of the incident: 118 ticks, **15 distinct values**, in blocks of 9 identical minutes.
- **`hr_now_bpm` equals `hr_avg_15m_bpm` on 11,618 of 11,618 valid ticks.** The "15-minute average"
  is the same held number. Confirmed independently by agent 2 from the code:
  `HealthContextRepository.kt:186` is literally `hrAvg15m = currentHR`.
- So a "one-minute change" is never a one-minute physiological change. Rated per sample interval,
  88 → 62 is −2.9 bpm/min.

**And the 62 was the trustworthy reading; the 88 was the outlier.** 41.8 % of this person's nocturnal
minutes are ≤ 62 and 44.3 % fall in 60-66, while only **2.0 % reach 88**. Steps corroborate the fall
(182 → 95 → 36 → 8 → 0). The unsupported readings are the **100 and 109** of 00:54-01:03: above the
nocturnal 95th centile of 83, with zero steps either side, and the +36 at 00:54 is the largest rise in
the whole dataset.

### 18.3 The exit grace was structurally unusable — measured

Of 42 floor releases, 22 came from a non-grace state and **20 were grace-driven. All 20 had exactly
one distinct heart-rate value across the entire grace window; none had two.** The reason is
arithmetic: the grace is 5 minutes, the refresh cadence is 9, and **66.7 % of hold blocks last ≥ 6
minutes**. The grace always expires on the very reading that broke the signature, having never seen a
second one. Rate ≈ 3.6 grace-driven releases a day; consequential on about 15 % of them, which needs a
rising glucose at the same time.

`stress_isf_floor_active` is null before 2026-09-12, so this covers ~5.5 days.

### 18.4 The three fixes, TDD

**A — the heart rate may not remove the floor during a fast rise.** New `REASON_RISE_HOLD` in
`ISF/StressIsfFloor.kt`: an active floor whose signature breaks while the rise is at or above
`RISE_HOLD_MGDL_PER_5MIN` **11.0** keeps its state. The threshold is not invented here — it is the
value `PhysiologicalPhaseClassifier` already measured as "too steep to be cortisol alone". Same
reasoning in both places: a rise that fast is not hormonal, so a heart rate says nothing about its
cause. Three guards, each tested: the freeze **holds** a state and never **creates** one (a rise can
never switch an inactive floor on, which would withhold insulin from a real meal on no evidence); a
data gap still drops the floor; a missing heart rate still drops it.

Replayed on the real night with the true per-tick heart rate, steps and delta: floor active
**17/40 → 40/40** ticks over 00:30-01:10, holding by `rise_hold` from 00:40 to 00:53 and returning to
`active` at 00:54. Over the whole 24 h, 67.3 % → **68.9 %** — **+23 ticks of 1436**. The gesture does
not become permanent.

**B — the release waits for a fresh sample.** `EXIT_GRACE_MAX_MINUTES` **20.0** (covering the 90th
centile of the observed refresh interval) and a new `Verdict.breakHrBpm`: the floor may not be
released while the heart rate still reads the same value that broke the signature. Restricted to
breaks **caused by the heart rate** — if the step count broke it, steps refresh on their own clock and
the heart rate's freshness is irrelevant, so the plain grace governs there. That restriction was found
by a pre-existing test going red, which is what tests are for.

**C — the one heart-rate path that raises a dose now has a gate.** It had none:

```kotlin
val heartRateTrend = averageBeatsPerMinute10 / averageBeatsPerMinute60
if (recentSteps10Minutes < 100 && heartRateTrend > 1.1 && bg > 110) {
    this.variableSensitivity *= 0.9f      // lower ISF number = stronger insulin
}
```

No carbs test, no delta test, no meal phase. Extracted to `ISF/HeartRateTrendIsf.kt` and it now stands
down above `RISE_SUSPEND_MGDL_PER_5MIN` 11.0 — during such a rise the heart-rate elevation is a
**consequence** of the rise, so adding insulin for it counts the same event twice — and on a
**baseline that was not measured**: when the one-hour window is empty the engine substitutes 80 bpm,
and for this person a real ten-minute average over 88 would trip the 1.1 ratio on that substitute
alone. A new `heartRateBaselineIsReal` flag carries the difference; the shared 80 fallback stays
because `ActivityManager`'s `avgHrResting` depends on a non-zero number.

On the episode the gate removes the strengthening at 00:54 (Δ +11.9) but not at 01:00 or 01:03, where
the rise had slowed under 11. It is narrow. (The `hr60` values in that check are estimates — the field
is not exported — so it is illustrative, not measured.)

`HeartRateTrendIsfTest` 11 tests, `StressIsfFloorTest` 19 → **30** tests. Whole run: `:plugins:aps`
297 classes, **1840 tests, 0 failures, 0 errors**; `:core:keys` 8 tests, 0 failures.

### 18.5 CORRECTION — the 18.55 that justified the rise ceiling guard was wrong

Section 10.2 recorded a discrimination ratio of **18.55** for the rise ceiling guard. **That number is
not reproducible.** An analyst replayed the **actual shipped code** — found in the repo, called at
`DetermineBasalAIMI2.kt:14187`, and cross-checked against production's own exported verdict on
**594 of 596 ticks (99.7 %)** — over essentially the same window (12,511 ticks against the 12,451
claimed) and got **0.68 at line 70 and 0.28 at line 60, with a cost of 233.80 U, not 3.8 U. The cost
is 60× larger.**

The error is mine and it is identifiable: the replay behind 18.55 had the ceilings **hardcoded** as
`{2.0, 1.25, 0.8}`, while the code reads `max_smb_u` and `max_smb_high_bg_u` **per tick**. The real
ceilings on this device run 1.80, 1.40, 1.70, 0.05, 0… so the hardcoded set flagged mostly the storm
ticks and inflated the gain side.

On the full corpus (15,957 ticks, 2026-09-01 → 2026-09-17) the shipped rule scores **1.11 @70 and
0.45 @60**, against a random-placement expectation of 0.40 and 0.13. The signal is real — about 2.8×
chance — but it does **not** clear the bar:

- removing **2026-09-15 alone** takes it to **0.74 @70** and **0.23 @60**; that one day carries 33.8 %
  of all gain, the top two days 50.8 %;
- requiring the low to be **sustained** (≥ 5 follow-up readings under the line, so a single dip cannot
  manufacture gain) takes it to **0.96**, and every variant to 0.86-0.91.

**The key was ARMED on the user's device** (`key_aimi_rise_ceiling_guard: true`). Recommendation on
record: disarm it until it is re-measured. Not done here — it is the user's key.

**And the proposed fix to it is rejected.** Replacing exact equality with "at or above a fraction of
the ceiling" scores at or below the incumbent at **every** fraction and both lines; the marginal return
is 0.2-0.56 U of bad-episode per U newly withheld; and the fraction low enough to make the 09-17 burst
contiguous is **0.80** (1.50/1.80 = 0.833), which is the **weakest** variant at line 70 (0.96). The
discrimination lives in the **rise threshold**, not in the ceiling test: the incumbent goes 0.88 →
1.11 → 1.27 as delta goes 6 → 8 → 10, identically for every variant.

Methodological note worth keeping: "a fraction of the ceiling" is **ambiguous**, and the two readings
are not nested. Generalising `isAtCeiling` fires *more* than the shipped rule (a superset); taking the
ratio to the **highest** ceiling in force fires *less*, because it loses the 215 ticks sitting exactly
on the **lower** of two positive ceilings.

### 18.6 CORRECTION — heart rate IS used as evidence for a meal

Section 16 and the 2026-09-17 reply both stated that heart rate is nowhere a meal signal. **Wrong, in
two places:**

- `patient/MealCertainty.kt:252-262` — `softCorroborationFromPhysio`: `idle && stepsLow && hr >= rhr + 12`.
  **Inert for dosing**: never referenced in `resolveLevel`; its only uses are the reason string, the
  stored field and the JSON export.
- `physio/MealAbsorptionPhaseEngine.kt:175-182` — **live**: `hrDelta in 5..18 && delta >= 1.5` adds
  **+0.35** to `physioScore`, carried at weight 0.10 into the phase belief, which drives
  `usesMealHtrPolicy`, `bypassesIobSurveillance` and `mealDeliveryPriority`. Contribution about
  ±0.035, decisive only at a threshold boundary. The same function *subtracts* 0.20 when
  `hrDelta > 18 && delta >= 4.0`.

### 18.7 Two heart-rate gates left alone on purpose

Both exist **only** on the undeclared-meal path — exactly what the user asked to remove — but both
**reduce** insulin, so removing the heart rate from them would make the loop more aggressive on
undeclared meals, against everything measured in sections 9 and 10:

- `autodrive/controller/MpcController.kt:128-134` — `hour in 4..10 && steps < 200 && hr > rhr + 5 && cob < 0.1`
  halves the MPC bolus cap and sets `activeRInsulin = 100.0`. It sits in an `else if` chain **above**
  the branch whose own comment reads "Unannounced Meal Crushing", so **a heart rate 5 bpm over resting
  in the morning makes that branch unreachable**. Declaring carbs removes the guard entirely.
- `UndeclaredCobEstimator.kt:95` — `if (input.hrInflammationElevated) return Result.gated("hr_inflammation")`
  at `hrElevation >= 15`, and the enclosing function only runs when carbs are zero. **A postprandial
  heart-rate rise of 15 bpm zeroes the virtual COB** — the estimator written to find undeclared meals
  is disabled by the rise the meal itself causes. Self-defeating, and still protective in direction.

Same shape at `physio/PhysiologicalPhaseClassifier.kt:343-348`: the looser morning prior
(`hr > rhr + 8`, delta ≥ 2.5, hours 5-10) runs at `:117-124`, **before** `isMealLikeRise` at
`:125-136`, and pre-empts `MEAL_UNDECLARED`. The policy difference is large — `STRESS_CORTISOL` gives
`smbFloorCapU 0.75`, `maxHtrTier EMERGING`, `mpcInsulinCostMultiplier 2.5`; `MEAL_UNDECLARED` gives
`+INFINITY`, `DEEP`, `1.0` — and protective. Only `isTooSteepForCortisolAlone` escapes it.

### 18.8 The heart-rate pipeline is unreliable by construction — open

All from agent 2, each a file:line it read:

- **Stale carry-forward with a refreshed timestamp.** `HealthContextRepository.kt:201-218`: when the
  new snapshot is invalid and the previous one was valid, it returns `lastSnapshot.copy(... timestamp = snapshot.timestamp)`.
  **An arbitrarily old heart rate is re-dated as current. There is no maximum age.** This is the worst
  of the list: any heart-rate decision can rest on hours-old data presented as fresh.
- `unifiedProvider.getLatestHeartRate(15 * 60 * 1000)` — a reading up to **15 minutes** old is
  presented as "now".
- **`rhrResting` falls back to a fabricated 60** (`:116-120`, `else -> 60` over the 7-day morning
  minimum). Every consumer guarding on `rhrResting > 0` passes, **including `StressIsfFloor`'s own
  missing-data check** (`:187-196`), which is the one place that handles absence correctly.
- `averageBeatsPerMinute60` and `180` fall back to a fabricated **80.0**, and all four do on any
  exception. Fixed for the one consumer that strengthens a dose (18.4 C); the others still read it.
- `isValid = confidence > 0.3` while `currentHR > 0` contributes exactly **0.3** — so the heart rate
  alone can never make a snapshot valid; it needs HRV or sleep data.
- Under `MODE_PREFER_WEAR` the provider takes the newest *wear* record even when a newer Health
  Connect record exists, so `hrNow` can be older than the best available sample.
- No field anywhere carries the HR **acquisition time**; `snapshot_age_ms` has a median of 18 ms and
  refers to the snapshot object, not the sample. So staleness cannot be measured from the export at
  all — only inferred from the staircase.

### 18.9 The stale carry-forward — fixed, TDD

The worst item of 18.8. `HealthContextRepository` carried the previous heart rate into a new snapshot
and stamped it `timestamp = snapshot.timestamp`, then stored that snapshot as the new previous one.
**The re-dating compounded**: the same reading was presented as current on every tick indefinitely,
with nothing anywhere recording when it had actually been measured — and the export carried no
acquisition time either, so the only visible clue was the staircase shape of the series.

New pure object `physio/HeartRateCarryForward.kt`: a reading keeps **its own** age. A fresh sample
resets it, a carried one does not, and past `MAX_AGE_MS` the reading is dropped to **0** rather than
carried — the value every consumer already treats as missing, including `StressIsfFloor`'s
`hrNowBpm <= 0` check, the one place in the engine that handles absence correctly.

`MAX_AGE_MS` is **15 minutes**, and it is not invented: it is the provider's own lookback
(`getLatestHeartRate(15 * 60 * 1000)`), so carrying a reading past it adds nothing the provider would
not have returned by itself. A test pins that equality so the two cannot drift apart.

`HealthContextSnapshot` gains `hrMeasuredAtMs`, kept **apart from** `timestamp` on purpose, and
`PhysioLiveDigest` exports `hr_sample_age_ms` — distinct from `snapshot_age_ms`, which has a median of
18 ms and refers to the snapshot object. Staleness is now measurable from a support package.

`HeartRateCarryForwardTest`: 10 tests, including the compounding case (carry the same reading a minute
at a time for 30 minutes and it must still expire on its real age), a clock that moved back, and the
exact-limit boundary.

### 18.10 A flaky test, and the production defect behind it

Adding the new test class made `KalmanFilterTest.the floor is relative to the profile when the profile is known`
fail once with **223.29 mg/dL/U where 15.0 was expected** — a sensitivity out by more than a factor of
ten. It did **not** reproduce: two further full-suite runs were clean, and a run with every production
change in place but the new test class moved aside was clean too (1840 tests, 0 failures). So the
failure was not caused by the change.

The mechanism it exposed is real, though. `KalmanISFCalculator.refreshTddAsync()` launches a coroutine
on `Dispatchers.IO` and writes `averageTDD(...)?.data?.totalAmount` straight into its cache. That
returns **0.0** when there is no history — and a relaxed test double returns 0.0 too. The effective
dose then becomes `0.2*0 + 0.4*0 + 0.4*0 = 0`, and it scales the whole sensitivity estimate.

Fixed with `isUsableTdd` / `MIN_USABLE_TDD_U` 1.0 in the companion object: only a measurement is
cached, and a refused value leaves the previous cache or the preference fallback in place. Nobody on
insulin therapy has a real daily total under 1 U, so below that it is missing data, not a small dose.
4 tests.

Whole run after all of section 18: `:plugins:aps` 298 classes, **1854 tests, 0 failures, 0 errors**;
`:core:keys` 8 tests, 0 failures.

## 19. The FCL mode — 2026-09-17

### 19.1 Why the first attempt did nothing

On 2026-09-17 the person ate at 13:00 with an "FCL" scenario running. Only the temp target 80
reached the loop. The reason is one line long: **"fcl" was not a keyword.** The parser in
[therapy.kt](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/therapy.kt) recognised
`anticip bfast breakfast delete dinner fasting high carb highcarb lowcarb lunch marche meal sleep
snack sport stop walk` and nothing else, so no mode flag was set. No flag means:

- no prebolus — `setLegacyPrebolusUnits(...)` is keyed on `mealruntime` / `lunchruntime` / …;
- no max-basal boost — that branch needs `mealTime || lunchTime || dinnerTime || highCarbTime || bfastTime`;
- not even the declared-meal anticipation floor, which needs a note holding "anticip".

The temp target worked because it never touches the note parser: `OpenAPSAIMIPlugin` reads
`persistenceLayer.getTemporaryTargetActiveAt(now)` straight from the database.

### 19.2 What FCL now is

`FclMealBasal` — an "fcl" note **plus** a low temp target ask for the meal basal ceiling, and nothing
else. No prebolus, because the prebolus path is keyed on the meal-mode keywords and "fcl" is not one
of them.

**The temp target is the leash.** It is what ends the mode, which is deliberate: a note window needs
a duration, and a scenario that writes a note with no duration would arm nothing — exactly the
2026-09-17 failure. Binding the mode to the temp target means the mode lasts as long as the target
the person can see in the app, and cancelling the target cancels the mode at once. It also makes the
gate two independent manual acts, so neither a stale note nor a temp target set for another reason
can force the ceiling alone.

The note therefore only has to be recent: `Therapy.FCL_MIN_WINDOW_MS` is one hour, the same lookback
`getTimeElapsedSinceLastEvent` already uses, and a note carrying a longer duration of its own keeps
it.

Stand-downs, all judgement and not measurement, all in the direction of less insulin:

| test | value | why |
|---|---|---|
| temp target set | required | `profile.temptargetSet` |
| temp target value | ≤ 85 mg/dL | over that it is an eat-soon or exercise target, which must never get the meal ceiling. 85 lets 80 mg/dL and its mmol/L neighbours (4.4 = 79, 4.5 = 81, 4.6 = 83) through, but not a plain 90 |
| glucose | ≥ 80 mg/dL | same rule and same reason as `AnticipationBasalFloor` |
| fall | slower than −3 mg/dL/5 min | the meal is not arriving as declared |
| sport note | must be absent | two manual notes that disagree are not a tie; the one that withholds insulin wins |

The raw `profile.target_bg` is read, **not** the working target the boost function receives: the raw
one carries the temp target the person set, the working one has already been reshaped by the engine's
own targeting.

### 19.3 Where it is applied, and why not as a mode branch

The first attempt put FCL in `resolveMealHyperBasalBoostOutcome`, the same branch the declared meal
modes use. That branch returns `CompleteWithTempBasal`, and that return (`:18201`) **ends the tick**
while the bolus stage is at `:18317` — so the meal modes run on basal alone for their window, after
sending their prebolus early in the tick (`applyLegacyMealModes` at `:3108`). SMB must stay active
during FCL, so that shape was wrong.

FCL is therefore applied as a **floor at the terminal basal apply-point**, beside
`AnticipationBasalFloor`, inside `runPostBasalEngineLearnersRtInstrumentationAndAuditorStage`
(called at `:18381`). Two properties come from that position:

- it is the **last** point where the rate can still be raised, so the slew limiter, the Harmonia
  harmonizer and the effort damp cannot clamp it away afterwards;
- it runs **after** the bolus stage, so the bolus channel is untouched.

The price is that the two channels do not talk to each other inside one tick: the bolus is decided
before the floor is applied, so it cannot know the basal is about to be raised. The coupling is real
but late — the insulin the floor delivers becomes insulin on board on the next tick, which the bolus
gate does read. **With a low temp target, SMB live and the basal at its ceiling, this is the most
insulin the engine can be asked for, and nothing subtracts one channel from the other.**

### 19.3.1 What actually reaches the pump

Everything downstream of the floor still applies, in this order inside `setTempBasal`:

1. the LGS block can zero it;
2. `DynamicBasalController` multiplies by 0…10, and hard-brakes to 0 when glucose is under target and
   falling faster than −1, or at or under 90 and falling faster than −2;
3. the clamp: `maxSafe` normally, `profile.max_basal` when the safety bypass is on.

For this person's settings:

| setting | value |
|---|---|
| `meal_modes_max_basal` | 10.0 U/h — what FCL asks for |
| `openapsma_max_basal` | 7.0 U/h — the hard cap, bypass included |
| basal profile | 0.50 U/h then 0.60 U/h from 11:00 |
| `max_daily_safety_multiplier` | 10.0 |
| `current_basal_safety_multiplier` | 10.0 |
| ⇒ `maxSafe` at lunch | min(7.0, 10 × 0.60, 10 × 0.60) = **6.0 U/h** |

So the floor sets `finalOverrideSafetyLimits = true`, but **only on the ticks where it actually raises
the rate**. That lifts one clamp — the daily-safety one, 6.0 — up to `max_basal`, 7.0, which is the
same bypass the declared meal modes already use. Without it FCL would deliver 6.0 while a `lunch`
note delivers 7.0, and FCL is meant to be *lunch minus the prebolus*, not a weaker version of it.

**So on a rising meal the FCL floor puts 7.0 U/h on the pump, not the 10 it asks for.**

⚠️ **Correction to an earlier claim in this document.** "10 has never been reachable for any meal
mode" was wrong. The declared meal modes do not go through the floor at all: `applyLegacyMealModes`
→ `manualMealModeTbr` → `setTempBasal(..., forceExact = true)`, and the `forceExact` path *posts the
requested rate exactly* — no `DynamicBasalController`, no `maxSafe`, no `max_basal` cap, no terminal
invariants. A `lunch` note therefore delivers exactly `meal_modes_MaxBasal` = **10 U/h** for its first
30 minutes. Only the FCL floor is capped at 7, because it deliberately keeps the PD brake that cuts
the rate when glucose is under target and falling. Raising `max_basal` is the honest lever if 10 is
wanted on the floor path too.

Two things deliberately left alone:

- **`BasalTerminalInvariants` still applies to FCL** — it exempts `isMealMode`, which FCL is not part
  of. Its three rules (glucose *and* prediction under target, post-hypo authority live, negative IOB
  with no rise) are all cases where the meal ceiling should not fire either.

  ⚠️ **It is ON, not off.** `BooleanKey.OApsAIMIBasalTerminalInvariants` defaults to `true`
  (`BooleanKey.kt:678`), and the KDoc of `BasalTerminalInvariants` claiming "défaut `false` →
  comportement historique" is **wrong** — an uncorrected doc defect. The 2026-09-16/17 export confirms
  it live: `enabled` is true on all 1436 ticks. Counting only the ticks whose block was actually
  computed (see 20.2 — the field was stale on 310 of them), it lowered the rate on **115 of 1126
  ticks (10.2%)**, mean 2.48 → 0.51 U/h, **3.77 U withheld over 24 h**; `post_hypo` binds 7.9% and
  `below_target` 2.3%. So FCL will be cut to the profile basal on roughly one tick in ten. That is
  wanted, but it was described here as inert and it is not.
- **`isMealMode` inside `setTempBasal` was not widened** to include FCL: it also feeds
  `MealSafetyContext`, which loosens an LGS guard. FCL must not buy a weaker hypo interlock.

### 19.4 Tests

| file | tests |
|---|---|
| `FclMealBasalTest` | 15 |
| `TherapyFclDetectionTest` | 8 |

Watched RED first on both (`Unresolved reference 'FclMealBasal'`, then `Unresolved reference
'fclTime'`). Full module suite after the change: 300 classes, 1881 tests, 0 failures, 0 errors. The
six existing `TherapyAnticipationDetectionTest` cases still pass, so the keyword parser did not move.

### 19.5 Still open

- `profile.max_basal` is 7 while `meal_modes_MaxBasal` is 10. Raising the first is the only way to
  reach 10 U/h, and it raises the ceiling for **every** other path too.
- SMB and the forced basal are **additive within a tick**, and with a temp target at 80 that is the
  most aggressive combination available. It was asked for explicitly. The stand-downs in 19.2 are the
  only things holding it, and they are judgement, not measurement — the first real window should be
  watched, not trusted.
- Nothing has been measured on real data yet: no replay, no discrimination test. FCL is a manual
  gesture behind two manual acts, so the corpus has no episodes to replay it over.


## 20. What the basal channel actually does, and a telemetry lie — 2026-09-17

### 20.1 The most-used basal value is zero

Measured on `AIMI_Decisions_Last24h.jsonl`, field `outcome.target_basal_rate_uph`, 1436 ticks from
09-16 08:06 to 09-17 08:06. Median gap 1.00 min and the gaps sum to 24.0 h exactly, so the per-tick
histogram and the time-weighted one are the same thing.

| band | time | share |
|---|---|---|
| **0 (suspended)** | **770 min** | **53.5 %** |
| under profile | 208 min | 14.4 % |
| **at profile** | **76 min** | **5.3 %** |
| 1–2× profile | 44 min | 3.1 % |
| 2–4× profile | 42 min | 2.9 % |
| **over 4× profile** | **300 min** | **20.8 %** |

Single most-used value: **0.00 U/h, 53.5 %**. The most-used non-zero value is 0.50 U/h at 2.9 %,
across 203 distinct values — so outside zero there is no usual rate at all.

Mean commanded 1.232 U/h against a profile mean of 0.551 → **2.23×**: 29.56 U of basal commanded over
the day where the profile alone would have given 13.23 U.

**The channel is bimodal.** It is either off or far above profile, and it sits at the profile 5.3 % of
the time. 41 zero episodes, median 4 min but a long tail: 150, 81, 80, 74, 58, 47 min. The longest
(09-17 00:01 → 02:31, glucose 113 → 80 with a low of 58) is the aftermath of the 01:00 episode in
section 18. While suspended the median glucose is 95 and 28.7 % of ticks are under 80, so those zeros
are mostly the protection working — but it works that hard partly because the other end of the
distribution spends 20.8 % of the day over 4× profile.

### 20.2 `adjustments.basal_terminal` was republishing the previous tick — fixed

**Symptom.** On 122 ticks the export showed `basal_terminal.rate_out_uph` > 0 while
`outcome.target_basal_rate_uph` was 0, decision `Basal_Modulation`.

**Root cause, from the code.** `lastBasalTerminalTelemetry` is a `private var … = null` member written
at exactly **one** place — the very end of `setTempBasal`, after every multiplier — and read into the
decision context. `setTempBasal` has **four early returns before it**, two of which set the rate to 0
(the `forceExact` hypo floor and the LGS block). The member was never cleared between ticks, so on
every tick that returned early the export republished the last tick that did reach the end, as if it
described this one.

The per-tick reset cluster right above the `AimiDecisionContext` constructor already does exactly this
for its neighbours (`aimiDecisionExportedThisTick = false`, `pendingDecisionCtxForExport = null`, with
a comment saying the export must happen on every exit path of the tick). This member had escaped it.

**Confirmation in the data.** 310 of 1443 ticks (21.5 %) carried a `basal_terminal` block
byte-identical to the previous tick's, and on **310 of 310** the rate that reached the pump was 0 —
a 100 % correlation with the early-return signature. The 122 were only the subset whose stale value
happened to be above zero.

**Fix.** One line, `lastBasalTerminalTelemetry = null`, in the per-tick reset cluster. Not at the top
of `setTempBasal`: the export runs on every exit path of the tick, including paths that never call
`setTempBasal` at all, so only a tick-level reset covers it. `adjustments.basal_terminal` is read with
`?.let`, so a null simply leaves the key out — which is the honest answer for a tick where the
terminal invariants never ran.

**Not a dosing defect.** The 0 U/h was correct; what lied was the record of why.

**Scope checked, and it is only this one.** Three other export members looked like the same shape at
first (`lastPredDivergenceExport`, `lastTubeAdvisorTrace`, `lastAdaptiveBasalTrace`) and all three are
false positives: the first two are reset to null (lines 2082 and 2094), the third is rebuilt
unconditionally every tick (line 2169). `adaptive_basal` does show 822 duplicate blocks (57.2 %), but
they split 55.6 / 44.4 between zero and non-zero rate against a 54.2 % base rate — no correlation, so
that block is simply slow-moving, not stale.

**Also fixed: a wrong default in a KDoc.** `BasalTerminalInvariants` documented
`OApsAIMIBasalTerminalInvariants` as "défaut `false` → comportement historique". The key defaults to
**`true`** (`BooleanKey.kt:678`). That wrong line is what made this session report the invariants as
inert; they are live on every tick.

**Verification.** Full module suite after both fixes: 300 classes, 1881 tests, 0 failures, 0 errors.
There is **no unit test** asserting the absent key: the only seam is a private `JSONObject` inside a
20 000-line injected class, and the scenario harness cannot observe the export without widening
`pendingDecisionCtxForExport` to `internal`. Adding that seam was not done unasked.


## 21. FCL, second pass: the basal was pinned to the profile and no SMB was sent — 2026-09-17

Field report: during a meal with FCL active and the temp target at 80, the basal stayed at the
profile rate, and no Autodrive prebolus or SMB was sent. Three independent causes, all three found in
the code, one of them measured in the export.

### 21.1 The basal: the terminal invariants were pulling it back

`BasalTerminalInvariants` runs **inside `setTempBasal`, therefore after** the FCL floor, and it
received `mealModeActive = isMealMode` — snack / highCarb / meal / lunch / dinner / bfast, **FCL
absent**. All three of its invariants return *exactly* `profile.current_basal`. So the floor raised the
rate to the meal ceiling and the invariant pulled it straight back a few lines later: the reported
symptom, word for word.

This contradicted the module's own contract: *"Modes repas exclus. Quand un mode manuel est déclaré,
l'utilisateur a demandé que la basale du mode s'applique sur toute sa durée ; les invariants
s'effacent."* FCL **is** a manually declared mode. Leaving it out was the error.

Which rule bound? In the 24 h export only two of the three ever did: `post_hypo` 7.9 % and
`below_target` 2.3 %; the negative-IOB rule never bound once. With this person's frequency of lows,
`post_hypo` can cover a whole meal window.

**Fix.** `mealModeActive = isMealMode || fclDeclaredThisTick(profile)`. `isMealMode` itself is still
not widened — it also builds `MealSafetyContext`, which loosens an LGS guard, and FCL must not buy a
weaker hypo interlock.

### 21.2 The Autodrive gate never opened its meal channel

`AutoDriveGater.shouldEngageV3` was called with
`explicitMealMode = mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime || snackTime` —
FCL absent again. At COB 0 with no recent estimate, `implicitMealContext` is false, so
`isMealRising` (`implicitMealContext && combinedDelta > 0.25`) is dead and the gate falls back to the
glycaemic thresholds. Under 120 mg/dL those need **`combinedDelta > 2.0` AND
`minBgLookback75m >= 75`**. Eating at ~100 with the target at 80, neither holds — and a low in the
previous 75 minutes keeps the second false long afterwards.

**Measured: `GateKind.MEAL_AWARE_RISE` fired 0 times in 1443 ticks.** The gate was RISE_TOO_WEAK
47.6 %, STRONG_RISE 19.1 %, HIGH_PLATEAU 12.2 %. The meal channel has never opened on this install.

**Fix.** FCL added to `explicitMealMode` at that call site only. The same expression appears at three
other places (T3C, `classifyPostHypoState`, one estimate path); they were left alone, they were not
part of what was analysed.

### 21.3 The Autodrive prebolus is locked under 120 mg/dL

`aggressiveRiseSmbFloorU`: `if (bgMgdl < 120.0) return 0.0`, then Large needs
`riseSignal >= 5 && shortAvgDelta >= 3`, Small needs `riseSignal >= 2`. The key is on
(`key_aimi_autodrive_aggressive_smb_floor: true`) and the amounts are at their defaults, **1.0 U and
0.1 U**. At the moment a prebolus is wanted, neither the glucose nor the rise condition holds. That
mechanism can only fire once the rise is well under way, which is a late correction, not a prebolus.

**Fix — and why not simply opening that floor.** It has **no per-episode budget by design**: the
budget was removed on 2026-08-10 because with it the peak went 225 → 268.7. The documented
consequence of letting it run free is the bolus storm — pinned at the max-SMB cap every 3 minutes on
the rise, 19.4 U of IOB in 56 minutes, low at 48. Gating it on FCL would fire it on **every tick** of
the temp-target window.

So FCL gets a **one-shot prebolus** instead, reusing the proven legacy machinery rather than a new
mechanism:

| mechanism | role for FCL |
|---|---|
| `fclruntime in 0..7` | the same P1 window every other mode uses |
| `legacyPrebolusFiredAtMem` + `legacyPrebolusLatchBlocks` | one fire per activation, window `runtime + 90 s` |
| the latch checked **in the branch condition** | without it the branch's own `return` would end the tick on all eight minutes and kill SMB |
| `pendingLegacyPrebolusUnit` + `LEGACY_PREBOLUS_DELIVERY_TTL_MS` | delivery guarantee: FCL added to the carry-forward `when` |
| `CARRY_RETRY_COOLDOWN_MS` | no double send while the first reaches the database |
| **absence** from `legacyMealMaint` | that block ends the tick for 30 min on a bare TBR; FCL must leave the bolus channel alive |

The amount is `DoubleKey.OApsAIMIautodrivePrebolus` — the Autodrive prebolus the person already set,
so there is nothing new to configure.

**On the carry-forward and SMB.** While a requested prebolus is unconfirmed the tick returns early,
so no SMB that tick. That is not a regression, it is the anti-redundancy requirement: no insulin has
landed yet, so the same amount is re-proposed rather than an SMB being stacked on an unknown outcome.
Worst case if a bolus is never enacted: 30 minutes (`LEGACY_PREBOLUS_DELIVERY_TTL_MS`).

### 21.4 What FCL now does, end to end

1. note `fcl` + temp target ≤ 85 mg/dL, no `sport` note → declared (`FclMealBasal.declared`, the one
   gate all four callers share);
2. minutes 0–7, once: prebolus = Autodrive prebolus amount, and that tick's TBR is posted **exactly**
   at `meal_modes_MaxBasal` (the `forceExact` path) = 10 U/h;
3. every tick after that: the FCL basal floor (capped at `max_basal` = 7 U/h, PD brake kept), **SMB
   alive**, and the Autodrive meal channel open on delta > 0.25;
4. stand-downs throughout: glucose < 80, fall ≤ −3 mg/dL/5 min, a live `sport` note;
5. the temp target expiring ends all of it.

### 21.5 Tests and what is still unverified

`FclMealBasalTest` 15 → **23** (8 new for `declared`, RED watched on `Unresolved reference
'declared'`). `rateUph` now routes through `declared`, and the 15 original cases still pass, which is
the regression guard on that refactor. Full module suite: 300 classes, **1889 tests, 0 failures, 0
errors**. No compiler warning on any new line.

**Not verified by data.** No export covers the FCL meal: the newest ends 09-17 08:06 and the FCL code
landed at 16:01. Cause 21.1 is therefore the best-supported hypothesis, not a measurement. What to
look for in the next export: the console line `🍽️ FCL_MEAL_BASAL` (the floor fired) and
`basal_terminal.bound_by` on those ticks (which rule cut it). Causes 21.2 and 21.3 are read directly
from the code and 21.2 is confirmed by the 0/1443 MEAL_AWARE_RISE count.

The three wirings are call-site changes in a 20 000-line injected class and have **no unit test of
their own**; only the pure `declared` gate is tested. That is the project's established pure-object
pattern, and it is a real limit, not a claim of coverage.
