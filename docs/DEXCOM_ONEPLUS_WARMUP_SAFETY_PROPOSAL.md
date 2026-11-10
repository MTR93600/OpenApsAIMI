# Dexcom ONE+ warm-up — basal safety analysis & PROPOSED mechanism (A3)

> **STATUS: PROPOSAL — READ-ONLY ANALYSIS. NO CODE HAS BEEN CHANGED.**
> This document is a safety design proposal for the "no glucose during ONE+ warm-up" gap
> (requirements S1/S2/S3 of `docs/DEXCOM_ONEPLUS_WARMUP_DASHBOARD_PLAN.md`).
> It is **safety-critical** and touches insulin delivery indirectly (temp-basal cancellation).
> It **must** be reviewed by a project maintainer **and** a clinician, and validated on-device,
> before any implementation is written or merged. **This is not medical advice.** Nothing here
> constitutes individualized dosing guidance; it describes software behaviour only.

All line numbers below were read from the working tree on the `feature/dexcom-oneplus-native`
branch at analysis time. Re-verify before acting — surrounding code may shift.

---

## 1. Current behaviour with NO glucose during ONE+ warm-up — does a stale temp basal persist?

**Answer: YES, a residual temp basal persists — bounded by its own programmed duration — and the
loop does NOT force profile basal while glucose is absent.** Full trace with citations:

1. **Ingest is blocked during `WARMING`.**
   `DexcomOnePlusIngest.isWarmupBlockingIngest(phase)` returns true **only** for
   `OnePlusWarmupState.Phase.WARMING`
   (`plugins/source/src/main/kotlin/app/aaps/plugins/source/DexcomOnePlusIngest.kt:36-37`).
   In `DexcomOnePlusPlugin.onGlucose(...)` this early-returns and never inserts a CGM value
   (`plugins/source/src/main/kotlin/app/aaps/plugins/source/DexcomOnePlusPlugin.kt:164-170`).
   Net effect: during warm-up there is **no fresh glucose** in `PersistenceLayer` from this source.

2. **AIMI APS bails on missing glucose without producing a result.**
   `OpenAPSAIMIPlugin.invoke(initiator, tempBasalFallback)` first sets `lastAPSResult = null`
   (`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt:836`),
   then, if `getGlucoseStatusData(false) == null`, sends `EventResetOpenAPSGui(no_glucose_data)`
   and **returns immediately** (`OpenAPSAIMIPlugin.kt:837-842`).
   A second, deeper guard exists inside `determine_basal` — `glucoseStatusCalculatorAimi.compute(...)`
   returning a null `gs` also `return@withContext`s with the same `no_glucose_data`
   (`OpenAPSAIMIPlugin.kt:1237-1242`). `lastAPSResult` is only ever assigned a real result on the
   success path (`OpenAPSAIMIPlugin.kt:1431`), which is not reached. So with no glucose,
   **`lastAPSResult` stays null**.

3. **LoopPlugin produces no pump command.**
   `executeInvokeInternal(...)` calls `usedAPS.invoke(initiator, tempBasalFallback)` then reads
   `apsResult = usedAPS.lastAPSResult`
   (`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt:724-727`).
   Because that is null, it hits `if (apsResult == null) { … return }`
   (`LoopPlugin.kt:730-733`) and returns **before** any `applyTBRRequest` / `applySMBRequest`
   is reached. **No new TBR is enacted, and — critically — no command is issued to move the pump
   to profile basal.**

4. **Consequence.** Whatever temporary basal was last programmed on the pump keeps running on its
   own clock. The loop keeps invoking (~every 5 min) but keeps bailing at step 3, so it never
   refreshes or extends that temp. A standard AAPS/oref/AIMI temp carries a finite duration
   (commonly ~30 min); when that duration elapses the pump **auto-reverts to the profile scheduled
   basal**. Therefore:
   - The residual temp is **not permanent** — it self-expires — but it can run **for up to its
     remaining programmed duration with zero glucose data**.
   - If that residual temp was a **high** temp (e.g. set just before warm-up began), the pump can
     deliver elevated basal for up to ~one temp-duration window while blind. This is the exact
     safety gap S1 must close.
   - Nothing in the current code path **forces** profile basal at warm-up entry; reversion is
     passive (duration expiry), not active.

---

## 2. Semantics of `Loop.invoke(initiator, allowNotification, tempBasalFallback)`

Interface: `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/Loop.kt:76-82`
(doc: `tempBasalFallback` = "true if called from failed SMB").

**Does `tempBasalFallback=true` yield PROFILE basal when `apsResult==null`? NO.**

- In `LoopPlugin`, `tempBasalFallback` is merely **forwarded** to the APS plugin:
  `usedAPS.invoke(initiator, tempBasalFallback)` (`LoopPlugin.kt:725`). It is not consulted by the
  loop's own control flow.
- Inside `OpenAPSAIMIPlugin.invoke`, when glucose is missing the function returns at
  `OpenAPSAIMIPlugin.kt:841` **before `tempBasalFallback` is used at all**. So with no glucose the
  flag has **zero effect**: `lastAPSResult` remains null and the loop still returns at
  `LoopPlugin.kt:730-733`.
- `tempBasalFallback` is a "SMB failed → recompute a TBR-only result" retry hint (see the
  self-re-invoke `handler?.postDelayed({ … invoke("tempBasalFallback", allowNotification, true) })`
  at `LoopPlugin.kt:896`). It is meaningful **only when glucose exists** and an APS result can be
  computed. It is **not** a "go to profile basal" primitive.

**Correct minimal action to force profile basal.** Since re-invoking the loop is a no-op without
glucose, the safety mechanism must issue an **explicit temp-basal cancellation**, which makes the
pump revert to its profile scheduled basal:

```
commandQueue.cancelTempBasal(enforceNew = false, autoForced = true)
```

API: `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/queue/CommandQueue.kt:46`
(`suspend fun cancelTempBasal(enforceNew: Boolean, autoForced: Boolean = false): PumpEnactResult`).

Why cancel (not `tempBasalAbsolute(profileBasal, …)`): cancelling clears the temp so the pump uses
its **own** profile schedule; it cannot deliver above profile basal and does not depend on us
computing the correct absolute rate for the current time block. (`tempBasalAbsolute` exists at
`CommandQueue.kt:43` / used by the loop at `LoopPlugin.kt:1092`, but pinning an absolute rate
duplicates the profile schedule and is more error-prone.)

**Important safety nuance for the reviewer (clinician):** cancelling a temp reverts to *profile*
basal. If the residual temp was a **low/zero** temp (pump was reducing insulin, e.g. after a
predicted low), cancelling it *raises* delivery back to profile while we are blind. "Profile basal"
is the project's chosen safe fallback for lost data (it is what a plain temp does on expiry), but
whether unconditional cancellation is desirable, or whether it should be **suppressed when the
running temp is below profile** (i.e. only cancel *high* temps), is a clinical decision that must be
settled in review. See §5 option (b).

---

## 3. Can `:plugins:source` access `Loop` / `CommandQueue` via Hilt?

**Yes — with no new inter-module dependency.**

- `:plugins:source` already depends on `:core:interfaces`
  (`plugins/source/build.gradle.kts:26` → `implementation(project(":core:interfaces"))`).
- Both `Loop` (`core/interfaces/.../aps/Loop.kt`) and `CommandQueue`
  (`core/interfaces/.../queue/CommandQueue.kt`) are **interfaces in `:core:interfaces`**, so their
  Hilt bindings are already on the app graph. A `@Inject constructor` in
  `DexcomOnePlusPlugin` can take them directly. **No `implementation(project(...))` needs to be
  added**, satisfying constraint C1 / the plan's archi guardrail.
- Precedent that `Loop` is safely injected from many plugin modules:
  `plugins/constraints/.../dstHelper/DstHelperPlugin.kt`,
  `plugins/automation/.../AutomationRuntime.kt`,
  `plugins/sync/.../smsCommunicator/actions/LoopResumeAction.kt`,
  `plugins/aps/.../loop/compose/LoopViewModel.kt`, etc.
- **Injection-shape caution (verify in review):** `DexcomOnePlusPlugin` is a `BGSOURCE` plugin
  constructed early in DI. To avoid any construction-order cycle (source → Loop(LoopPlugin, in
  `:plugins:aps`) → …), prefer injecting **`javax.inject.Provider<Loop>`** / **`dagger.Lazy<Loop>`**
  and resolving lazily at first use, rather than an eager `Loop` reference. `CommandQueue` is a
  lower-level singleton and can likely be injected directly, but `Provider`/`Lazy` is the
  conservative choice for both. A `core:interfaces` hook is **not** required.

---

## 4. Interaction with SUSPEND / OPEN-LOOP / max-basal constraints

The mechanism **must not administer anything when the loop is paused, and must respect the loop
mode**. Relevant facts:

- Running modes: `core/data/src/main/kotlin/app/aaps/core/data/model/RM.kt:43-57`. Helpers:
  `isClosedLoopOrLgs()` (`RM.kt:66`), `isLoopRunning()` (`RM.kt:67`),
  `pausesLoopExecution()` (`RM.kt:75`, true for `DISCONNECTED_PUMP`, `SUSPENDED_BY_PUMP`,
  `SUSPENDED_BY_USER`, `SUSPENDED_BY_DST`, `SUPER_BOLUS`).
- Mode is queried via `Loop.runningMode(): RM.Mode` — a **suspend** function
  (`Loop.kt:50`); call it from a coroutine.
- **Suspend / disconnect / super-bolus = DO NOT TOUCH.** In these modes the `RunningModeReconciler`
  actively maintains a **zero temp** on the pump (see the comment on `LoopPlugin.goToZeroTemp`,
  `LoopPlugin.kt:1150-1168`: "the RunningModeReconciler observes the change and issues zero-TBR").
  If our warm-up mechanism cancelled the temp here, it would **revert a suspend-zero-temp to profile
  basal — i.e. administer insulin during a suspend.** This is the single most dangerous failure mode
  and must be hard-guarded: **if `runningMode().pausesLoopExecution()` is true → do nothing.**
- **`DISABLED_LOOP`** (`RM.kt:50`): loop off entirely (the real loop also early-returns on this,
  `LoopPlugin.kt:699-704`). The warm-up mechanism should **do nothing** — the user has opted out of
  automation.
- **`OPEN_LOOP`** (`RM.kt:44`): the loop only *suggests*; it does not enact
  (`LoopPlugin.kt:916-934`). Auto-cancelling a temp in open loop would enact a change the user did
  not accept, violating the open-loop contract. Recommended default: **in open loop, notify only —
  do not auto-cancel** (make this a reviewed decision).
- **`CLOSED_LOOP` / `CLOSED_LOOP_LGS`** (`RM.kt:45-46`): the mode where forcing profile basal is
  appropriate.
- **Constraints / maxBasal.** Cancelling a temp reverts to the *profile* scheduled basal, which is
  by definition within the user's own basal programming and cannot exceed `maxBasal` (profile basal
  ≤ max basal by construction). So an explicit **cancel** inherently respects basal constraints
  without invoking `constraintChecker`. (If option (a2) `tempBasalAbsolute` were ever chosen
  instead, it would have to run through `constraintChecker.applyBasalConstraints`, as the loop does
  at `LoopPlugin.kt:749-753` — another reason to prefer cancel.)

---

## 5. PROPOSED mechanism (requires human + clinician + device sign-off)

**Design goals:** trigger the instant warm-up begins (works with the phone in standby, no Activity),
re-affirm on a cadence, and hand back cleanly to the normal loop when glucose returns — without ever
administering during suspend/disabled/open-loop, and without fighting the loop once BG is live.

### 5.1 Single source of truth = the existing warm-up StateFlow
`DexcomOnePlusPlugin` already exposes `val warmup: StateFlow<OnePlusWarmupState>`
(`DexcomOnePlusPlugin.kt:88-91`, updated in `onWarmup`, `DexcomOnePlusPlugin.kt:147-155`) and the
generic `override val warmupStatus: StateFlow<CgmWarmupStatus?>`
(`DexcomOnePlusPlugin.kt:97-100`, from A1's `CgmWarmupProvider`). The safety layer must consume the
**same** flow — no parallel warm-up state. `active != null` ⇒ warm-up/(re)connect in progress with
no valid glucose.

Because the flow lives on the singleton driver/plugin (kept alive foreground by `DummyService`, per
plan §2.2), a collector on the plugin's existing `ioScope`
(`DexcomOnePlusPlugin.kt:80`) runs in standby without any Activity — satisfying S2.

### 5.2 Trigger, re-affirm, and hand-off (behavioural spec, not code)

Collect `warmupStatus` (or `warmup`) on `ioScope`. Maintain a small guarded routine
`ensureProfileBasalDuringWarmup()`:

1. **Guard — abort if we must not act.** In order, all via `Provider<Loop>`/injected deps:
   - `warmupStatus.value == null` (warm-up not active, or `READY`/`IDLE`/`FAILED`) → **stop /
     hand off** (see step 4).
   - this ONE+ plugin is the **active BG source** (`activePlugin.activeBgSource === this`); if not,
     do nothing (don't act on another source's behalf).
   - `loop.isEnabled()` is true, and `loop.runningMode()` is **`CLOSED_LOOP` or
     `CLOSED_LOOP_LGS`** (`RM.kt:66`). If `pausesLoopExecution()` (`RM.kt:75`),
     `DISABLED_LOOP`, or `OPEN_LOOP` → **do NOT cancel** (open loop: notify only).
   - pump is initialized and command queue is idle enough to accept a cancel (mirror the loop's own
     `isEmptyQueue()` / `pump.isInitialized()` posture, cf. `LoopPlugin.kt:715-722`).
2. **Act (option a — recommended):** fire-and-forget an explicit
   `commandQueue.cancelTempBasal(enforceNew = false, autoForced = true)`
   (`CommandQueue.kt:46`) so the pump reverts to profile basal. Use a scope that outlives the call
   and **do not `await` from a BLE/queue-execution context** (the `CommandQueue` KDoc deadlock
   warning, `CommandQueue.kt:10-25`). `autoForced = true` marks it as system-initiated (not a user
   action) for the UEL/audit log.
   - **Option (b) — clinician-gated refinement:** only cancel when the running temp is **at/above**
     profile (a *high* temp); leave a *low/zero* temp untouched so we never *raise* delivery while
     blind. Requires reading the active temp vs. profile basal before cancelling. Recommended for
     clinical review as the safer default; costs one extra read.
3. **Re-affirm (S2):** because a fresh temp could be (re)issued elsewhere, re-run step 1–2 on a
   cadence while warm-up stays active and glucose absent — align to the driver's KeepAlive / warm-up
   tick rather than a new timer, and make it **idempotent** (cancelling an already-cancelled temp is
   a no-op, so repeated calls are safe). Also react immediately to any new emission of the flow.
4. **Hand-off (S3):** when `warmupStatus` transitions to `null` (phase → `READY`, or the first valid
   EGV is ingested and ingest is no longer blocked, `DexcomOnePlusIngest.kt:36-37`), **stop** issuing
   cancellations and let the normal loop resume. Do **not** issue a final command on hand-off — the
   next natural `loop.invoke` (now that glucose exists) computes and enacts the correct temp. This
   avoids a double-command / fight: our side only ever *cancels* (idempotent) and goes quiet the
   moment BG is live; the loop owns all positive dosing.

### 5.3 Where it lives
Inside `:plugins:source` (`DexcomOnePlusPlugin`, which already owns the warm-up state and `ioScope`),
injecting `Provider<Loop>` and `CommandQueue` from `:core:interfaces`. **Do not** modify the APS
algorithm, `LoopPlugin`, or any basal/SMB computation. No new inter-module dependency.

### 5.4 Explicitly out of scope / open questions for reviewers
- Clinical: option (a) unconditional-cancel vs option (b) high-temp-only cancel (§5.2).
- Clinical: behaviour in `OPEN_LOOP` (notify-only vs. nothing).
- Whether a user-facing notification should accompany the forced profile basal, and its wording
  (EN string only; must state it is not medical advice per project conventions).
- Device validation per plan §5 A5: confirm TBR shows **profile basal within < 1 cycle** at warm-up
  entry, holds in standby/lock-screen, and that the normal loop resumes on first EGV with no
  double-command.

---

## Executive summary (5 lines)
1. **Stale temp?** Yes — with no glucose the loop returns at `LoopPlugin.kt:730-733` and issues no
   command, so the last temp basal runs out its own duration (up to ~one temp window, e.g. ~30 min)
   before the pump passively reverts to profile; nothing actively forces profile basal.
2. **`tempBasalFallback`?** It does **not** yield profile basal when `apsResult==null` — it is only
   forwarded to the APS (`LoopPlugin.kt:725`), which has already returned on missing glucose
   (`OpenAPSAIMIPlugin.kt:836-842`); the flag has zero effect without glucose.
3. **Recommended action:** an explicit, idempotent `commandQueue.cancelTempBasal(enforceNew=false,
   autoForced=true)` (`CommandQueue.kt:46`) that reverts the pump to profile basal, hard-guarded by
   loop mode (`RM.kt:66/75`): act only in CLOSED_LOOP/LGS; never when suspended/disconnected/disabled;
   open-loop = notify only.
4. **Injectable in `:plugins:source`?** Yes — both `Loop` and `CommandQueue` are `:core:interfaces`
   types already on that module's classpath (`build.gradle.kts:26`); inject via
   `Provider<Loop>`/`CommandQueue` with **no new inter-module dependency** (Loop is already injected
   across many plugin modules).
5. **Trigger/hand-off:** drive it from the existing `warmup`/`warmupStatus` StateFlow on the
   plugin's `ioScope` (works in standby, no Activity), re-affirm on the KeepAlive cadence while
   warm-up is active, and stop the moment `warmupStatus` → null (READY / first EGV) so the normal
   loop cleanly reclaims dosing. **PROPOSAL only — requires maintainer + clinician review and
   on-device validation before implementation; not medical advice.**
