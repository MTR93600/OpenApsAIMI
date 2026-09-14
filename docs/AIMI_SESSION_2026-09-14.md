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
