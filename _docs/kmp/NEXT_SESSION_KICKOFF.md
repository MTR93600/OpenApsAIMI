# Start here — AIMI Compose port, next session

Branch: `kmp-aimi-migration-study`. Last lot finished: sub-lot 3/5 (the T3c / Harmonia / RBT
runtime-history cards), written up as entry `6r`. Sub-lot 2 is committed as `9184588bf5`; sub-lot 3
is still in the working tree.

This file is a short, self-contained kickoff for a fresh session with no memory of prior work. For
full history, reasoning, and every past finding, read `_docs/kmp/AIMI_PORT_STATE.md` in full,
especially section 7 ("Start here next session") and the most recent lettered entries (6p, 6q, 6r) -
this file only summarizes what those already say in detail.

## How to work: multi-agent pipeline, every lot

The user has asked for this porting work to continue in a multi-agent structure, not done inline by
the main session. For each remaining sub-lot, run this pipeline (matches how every lot since 6l has
been done):

1. **Definer** - spawn an `Explore` agent (read-only) to survey the exact staged code and its current
   live backend before any code is written. Past lots repeatedly found the staged code's assumptions
   had gone stale (renamed keys, grown/shrunk enums, changed return types) or that the whole feature
   had zero live entry point anywhere - never trust the staged file's assumptions, always re-verify.
   If the survey turns up a real architectural fork (a design choice with more than one reasonable
   answer, not a mechanical detail), stop and ask the user before writing code - do not guess. Recent
   real example: Meal Advisor's camera needed a Camera2-vs-CameraX decision (6n).
2. **Coder** - spawn a `general-purpose` agent (with Write/Edit/Bash) with a complete, self-contained
   brief: exact file paths, exact current API signatures from the definer's findings, the established
   Compose conventions to copy (name the 2-3 most recently ported screens as style templates), the
   exact `ApsIntentKey`/`OpenAPSAIMIPlugin.kt` wiring pattern to follow, and the required build
   verification (`:app:assembleFullDebug`, purge `app/build/generated/ksp` once if it fails with
   stale Dagger/Hilt-referencing errors unrelated to the change). Tell it explicitly: do not commit,
   leave changes in the working tree.
   - If the agent's turn budget or a rate limit cuts it off mid-task, **resume the same agent** via
     `SendMessage` to its `agentId` rather than restarting from scratch - it keeps all the context
     it already built. This has happened twice in this series and both times finished cleanly on
     resume.
3. **Reviewer** - spawn a `code-reviewer` agent with a priority-ordered brief: name the specific
   dosing-relevant correctness properties to verify first (exact preference keys, exact text strings
   any downstream matcher depends on, any known-tricky math like a rotation formula), then a normal
   pass. If it hits its own turn limit before calling `ReportFindings`, resume it the same way and
   tell it to converge and report rather than open new investigation.
4. **Verify independently** (main session) - re-run the build/iOS-compile/test gate yourself rather
   than only trusting an agent's self-report; agents have occasionally reported success right before
   being cut off, so re-confirm.
5. **Fix directly** anything the reviewer finds, if it's small and precisely understood (this has
   been faster and more reliable than sending it back to another agent round-trip for every finding
   this series so far) - only spin up another agent for something that needs real re-investigation.
6. **Update `AIMI_PORT_STATE.md`** with a new lettered entry (next is `6s`) documenting what was done,
   what was found, and why - then refresh section 7's file count/next-steps. Commit the lot with a
   descriptive message (see recent commits for the tone/format), only after independent verification
   passes. Never commit without having actually rebuilt yourself.

Two agent runs each hit a turn/rate limit mid-task in this series and were resumed successfully rather
than restarted - resuming is cheap (no lost context) and has worked every time so far.

## What's already done (for full detail, see AIMI_PORT_STATE.md 6k-6r)

- Staging cleanup: 6 dead/superseded files deleted (6k).
- Health Connect + Emergency SOS permission screens ported (6l).
- The "Context" screen (free-text/preset intent logging + patient-state panel) ported, including a
  brand-new preference-tree entry since none existed (6m).
- Meal Advisor + its camera capture screen ported, including a Camera2-in-`AndroidView` design
  decision and 3 real bugs fixed (rotation, permission-denial feedback, camera-not-reopening-after-
  background) (6n).
- Mode Settings ported - a **live dosing control surface** (writes therapy-event notes that
  `therapy.kt` matches by substring to drive real prebolus/`smbMult` decisions), verified byte-for-
  byte unchanged (6o).
- `AimiProfileAdvisorActivity` (2321 lines, ~5x any prior screen) surveyed and split into 5 sub-lots.
  Two sections resolved without porting: the support-ZIP flow is already superseded by the live
  `AimiSupportPackageScreen`; the Behavior Causal Map/Family Bridge section was explicitly decided
  **not to be ported** (dead backend, zero live callers, zero tests). **Sub-lot 1/5 (Tuning Context)
  is done** - new screen `AimiProfileAdvisorScreen.kt`, wired via a new top-level
  `ApsIntentKey.AimiProfileAdvisor` entry. Building it surfaced and fixed a real bug: `AimiAdvisorService`
  silently computes against hardcoded fake TIR numbers whenever no `TirCalculator` is supplied - fixed
  at the new call site AND at one other pre-existing call site with the same gap. **Any future
  `AimiAdvisorService(...)` construction must pass `tirCalculator`, or it will silently use fake data.**
  (6p)

## What's left

**2 more `AimiProfileAdvisorActivity` sub-lots**, in this order (smallest/safest first) - each adds
cards to the same `AimiProfileAdvisorScreen.kt`/`ApsIntentKey.AimiProfileAdvisor` entry 6p created,
reading from the same `AdvisorReport` that screen already loads. Do not create a second entry point.
Sub-lots 1 (Tuning Context, 6p), 2 (Metrics + Recommendations + Apply flow, 6q) and 3 (the three
runtime-history cards, 6r) are done. Sub-lot 2 turned out to be engine work as well as a port - four
of its rules had no live engine behind them at all - so do not assume the rest are UI only. There is
now ONE shared `AimiRecommendationCard` in commonMain, used by both this screen and the PKPD Setup
screen - do not write a second one. Two habits from 6r: when the staged source concatenates
user-visible text there is no `R.string.` to find, so expect to add a template; and check that a
lot's new tests actually land on its new code.

1. **Brain + Oref + AI Coach cards** (~300 lines, roughly 922-1227) - note `AiCoachingService` now
   requires DI-construction (`@Inject constructor(rh: ResourceHelper)`), not the staged code's bare
   `AiCoachingService()` - this alone will not compile as staged, confirm the current constructor
   before writing the card.
2. **Header + quick actions** (~205 lines, roughly 305-448 + 603-632) - dashboard header, basal-
   profile-proposal dialog/share, model selector. Explicitly excludes lines ~450-601 (the support-ZIP
   dialogs), which are dropped entirely, already superseded.

**Only after both are done**: delete `_docs/kmp/staging/openAPSAIMI-android-wip/advisor/AimiProfileAdvisorActivity.kt`
- not before, since later sub-lots still read it as reference.

**Then, the last staged file**: `_docs/kmp/staging/openAPSAIMI-android-wip/orchestration/AimiLoopRuntimeGuard.kt`
(16 lines) - wraps a live telemetry method (`AimiLoopTelemetry.isTickInProgress()`/`activeTickAgeMs()`)
that nothing calls yet (no Overview wiring exists for it). Decided to hold this one rather than port
it speculatively - port it together with whatever future feature ends up needing it, not before. Ask
the user before changing this decision.

**Once both of those are done, staging is empty** - the whole `_docs/kmp/staging/` directory tree
(and this migration's staging-cleanup discipline) can be retired, worth a final pass to confirm
nothing else references it.

## Standing gotchas worth re-reading before starting (all detailed further in AIMI_PORT_STATE.md)

- Kotlin/Native rejects a comma inside a backtick-quoted test function name - compiles fine on
  JVM/Android, fails only `iosSimulatorArm64Test`. Relevant if any sub-lot adds `commonTest` content.
- After any DI-graph-touching change, a stale `app/build/generated/ksp/**` cache can produce false
  Dagger/Hilt-referencing compile errors - `rm -rf app/build/generated/ksp` and rebuild once before
  trusting a failure.
- This machine's `xcode-select` may point at Command Line Tools instead of the full Xcode install,
  blocking `iosSimulatorArm64Test`'s link step (not the plain compile step) - a machine setting, not
  a code issue, out of scope to fix without being asked.
- Always check whether a preference key, sealed-class variant, or backend method a staged file
  references still exists with the same shape - nearly every lot in this series found at least one
  thing that had quietly changed since the file was parked.
