# Non-Regression Checklist

Purpose: enforce repeatable quality gates to prevent freezes and functional regressions after merges, especially on high-risk paths (AIMI, adaptive smoothie, dashboard skins, ML permissions, physio, hormonitor structure).

Use this file for every merge from `dev` and every release candidate.

**Latest merge log:** [MERGE_DEV_2026-09-06.md](MERGE_DEV_2026-09-06.md) (`dev` @ `283a184f60` → `dev_OAPSAIMI`). Previous: [MERGE_DEV_2026-08-08.md](MERGE_DEV_2026-08-08.md) (`dev` @ `7fc8205e9a`), [MERGE_DEV_2026-08-03.md](MERGE_DEV_2026-08-03.md).

---

## 1) Mandatory Pre-Merge Checks

- [ ] Working tree clean before merge (`git status`).
- [ ] Merge target and source branches explicitly documented.
- [ ] Conflict files listed and reviewed one by one (no blind conflict resolution).
- [ ] For each conflict, decision recorded: keep ours / keep theirs / combine.
- [ ] If conflict touches async code, mark with `ASYNC IMPACT`.

- [ ] **Safety tag** created on the pre-merge commit (`git tag premerge-dev-<date> HEAD`) — enables the two
      baselines below and a clean rollback.
- [ ] **Invariant baseline captured before the merge and re-run after**, output diffed (fork markers: module
      includes, `SourceSensor`, `@IntKey` registrations, AIMI file count, `calibratedOrValue`,
      `dashboardOverview` / `SkinDescriptionProvider`, manifest ML/physio permissions, `patient_story`,
      `runVacuum = false`, `drain()`, notification-reader mappings). Any diff other than line-number shifts
      must be explained.
- [ ] **Failing tests attributed before being accepted.** A red test is only "pre-existing" once the *same*
      task has been run on the pre-merge tag in a separate worktree (`git worktree add --detach <dir>
      premerge-dev-<date>`, copy `local.properties`, `./gradlew -p <dir> <task>`) and shows the identical
      failure set. Otherwise treat it as a merge regression.

- [ ] **Duplicate-entry scan after any merge touching `strings.xml` or a manifest.** Git text-merges these
      files happily; gradle then rejects the result. Neither a Kotlin compile nor a unit test catches it —
      only `mergeResources` / `processMainManifest` do. Run:
      ```bash
      # duplicate resource names
      for f in $(find . -name strings.xml -not -path '*/build/*'); do
        d=$(grep -oE '<(string|plurals|string-array) name="[^"]+"' "$f" | sed 's/.*name="//;s/"//' | sort | uniq -d)
        [ -n "$d" ] && echo "$f: $d"
      done
      # duplicate manifest components
      for f in app/src/main/AndroidManifest.xml wear/src/main/AndroidManifest.xml; do
        grep -oE 'android:name="\.[A-Za-z0-9_.]+"' "$f" | sort | uniq -d
      done
      ```
- [ ] **Build what CI builds, not just `:app` debug.** Both `aaps-ci.yml` and `android-build.yml` run
      `:app:assemble<Variant>` **and `:wear:assemble<Variant>`**. `:app:compileFullDebugKotlin` alone never
      exercises `:wear` resource merging or manifest merging, so it can pass while CI fails. Minimum
      post-merge gate: `./gradlew :app:assembleFullDebug :wear:assembleFullDebug`.

Notes:
- No opportunistic refactor during merge conflict resolution.
- Keep method signatures and contracts unchanged unless explicitly required.
- Verify gradle results by **exit code on a redirected log**, never through a pipe (`| tail` reports the
  pipe's status, so a FAILED run looks green).

### Fork merge constraint: Eversense (native CGM patches)

When this fork includes (or will include) the CAPTCG Eversense BLE plugin series, every merge from upstream `dev` must **preserve** module registration, DI, `SourceSensor` / DB mappings, and Config-related changes. Do not resolve conflicts “theirs only” on those paths without explicit review.

- [ ] **Eversense preservation reviewed** — follow [docs/MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md) (patch order, high-risk files, post-merge checks).

### Fork merge constraint: Dexcom ONE+ (native CGM)

When this fork includes the Dexcom ONE+ native plugin (`:plugins:dexcom_oneplus`, `DexcomOnePlusPlugin` `@IntKey(446)`), every merge from upstream `dev` must **preserve** module registration, DI, `SourceSensor.DEXCOM_ONEPLUS_NATIVE` / DB converters, and notification-reader remaps (`com.dexcom.d1plus` / `com.dexcom.dexcomone` → `AAPS-DexcomOnePlus`). Do not resolve conflicts “theirs only” on those paths without explicit review.

- [ ] **Dexcom ONE+ preservation reviewed** — follow [docs/MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md).
- [ ] **Smoke (scaffold / post-merge):** Config Builder lists **Dexcom ONE+**; **BYODA** (`@IntKey(440)`) still works; **Eversense** (`@IntKey(445)`) still works.

### Fork merge constraint: Libre 3 native CGM

When this fork includes the native Libre 3 plugin (`:plugins:libre3`, `Libre3NativePlugin` `@IntKey(447)`), every merge from upstream `dev` must **preserve** the module registration, the DI binding, `SourceSensor.LIBRE_3_NATIVE` and its DB converters both ways, `Sources.Libre3Native`, and `NotificationId.LIBRE3_DIR_ACCESS_LOST` **as the last entry** of that enum. Do not resolve conflicts "theirs only" on those paths without review. The follower Libre paths (`LIBRE_2`, `LIBRE_2_NATIVE`, `LIBRE_3`, Glimp, Tomato) must stay untouched: Libre 3 through Juggluco or xDrip is still the production path.

- [ ] **Libre 3 preservation reviewed** — follow [docs/MERGE_CONSTRAINT_LIBRE3.md](MERGE_CONSTRAINT_LIBRE3.md).
- [ ] **Smoke (scaffold / post-merge):** with `Documents/AAPS/extra/engineering_libre3` present, Config Builder lists **Libre 3**; without it the plugin is absent; xDrip (`@IntKey(400)`), BYODA (`@IntKey(440)`), Eversense (`@IntKey(445)`) and Dexcom ONE+ (`@IntKey(446)`) all still listed; `Libre3BooleanKey.UseRealSkeleton` still defaults to off.
- [ ] Prefs open Status / Start / Warm-up without crash (Stub default; Real via eng pref).
- [ ] Native pair / warm-up / BG — only after **user device confirmation** (do not mark “working” without that). User guide: [docs/DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md). Dev onboarding: [docs/DEXCOM_ONEPLUS_DEV_ONBOARDING.md](DEXCOM_ONEPLUS_DEV_ONBOARDING.md). Integration: [docs/DEXCOM_ONEPLUS_INTEGRATION_NOTES.md](DEXCOM_ONEPLUS_INTEGRATION_NOTES.md).

---

## 2) Critical Domain Regression Gates

### AIMI plugin
- [ ] Loop behavior unchanged (no accidental SMB disabling).
- [ ] AIMI configuration import/export keys preserved.
- [ ] No regression on JSON/CSV writes used by AIMI workflows.

### Adaptive Smoothie plugin
- [ ] Plugin activation/state transitions unchanged.
- [ ] No regression on data flow feeding smoothing decisions.
- [ ] No new blocking call on main thread.
- [ ] With **Linear calibration** active, adaptive smoothing uses `calibratedOrValue` (parity with UKF / exponential smoothers).

### Dashboard + Skin switching
- [ ] Embedded dashboard renders correctly.
- [ ] User can switch back to original overview skin.
- [ ] No freeze/jank when opening dashboard repeatedly.
- [ ] No broken FAB/top bar/bottom bar visibility transitions.

### ML permissions and storage
- [ ] JSON/CSV write paths still allowed and reachable.
- [ ] No new permission requirement missing at runtime.
- [ ] Storage fallback/error path validated (no silent failure loops).
- [ ] **DataInbox** consumers call `drain()` before early return (Dexcom, xDrip, SMS, APS result export worker).

### Physio part
- [ ] Inputs/outputs used by physio layer are unchanged or intentionally migrated.
- [ ] No data type/unit mismatch introduced.
- [ ] No async race introduced between producer/consumer.

### Hormonitor study structure
- [ ] Required structural flows/interfaces remain intact.
- [ ] No rename/removal of expected keys or records.
- [ ] Historical compatibility preserved for study data consumption.
- [ ] Schema `1.2.0` additive block `patient_story` present when patient runtime is active (no breaking rename of `1.1.0` keys).

### AIMI documentation consistency (fork-specific `.md`)

Our AIMI docs are part of the contract: a merge that renames or deletes a symbol they reference is a
documentation regression even when the build is green.

- [ ] Every `` `*.kt` `` reference in [AIMI_ARCHITECTURE_MAP.md](AIMI_ARCHITECTURE_MAP.md),
      [AIMI_ROADMAP.md](AIMI_ROADMAP.md) and the merge-constraint docs still resolves to an existing file:
      ```bash
      grep -ohE '`[A-Za-z0-9_/.:-]+\.kt`' docs/AIMI_ARCHITECTURE_MAP.md docs/AIMI_ROADMAP.md \
        docs/NON_REGRESSION_CHECKLIST.md docs/MERGE_CONSTRAINT_EVERSENSE.md \
        docs/MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md docs/MERGE_CONSTRAINT_LIBRE3.md | tr -d '`' | sort -u | while read -r p; do
          find . -name "$(basename "$p")" -not -path '*/build/*' -not -path './.git/*' | grep -q . || echo "MISSING: $p"
        done
      ```
- [ ] Behaviour claims still true for paths the merge touched (AIMI decision cascade, physio, hormonitor export).
- [ ] New merge log added under `docs/MERGE_DEV_<date>.md` and the **Latest merge log** pointer above updated.

---

## 3) Stability and Freeze Prevention Gates

### Event orchestration
- [ ] No direct "refresh all" on every event burst.
- [ ] Event streams are debounced/coalesced where needed.
- [ ] Refresh work is cancellable (`collectLatest` or equivalent).

### Coroutine safety
- [ ] No unbounded parallel job spawning from frequent events.
- [ ] Expensive jobs are single-flight (cancel previous before new launch).
- [ ] Heavy work (`DB`, `TDD`, parsing, file I/O) off main thread.

### Compose/UI performance
- [ ] No avoidable recomposition storms introduced.
- [ ] Layout measurement loops avoided on hot screens.
- [ ] UI state updates scoped to minimal changed fields.

### Blocking call checks
- [ ] No new `runBlocking` in UI/ViewModel hot paths.
- [ ] No `Thread.sleep` on any UI-related execution path.
- [ ] No synchronous I/O in frequently triggered callbacks.

### Database maintenance (May 2026 regression — `SQLITE_NOMEM` / freeze)

Upstream once ran **inline `VACUUM`** inside `cleanupDatabase()`; `KeepAliveWorker` triggered it daily while the loop was active → crashes / OOM. Re-verify after every merge touching DB or workers.

- [ ] **`KeepAliveWorker.databaseCleanup`** calls `cleanupDatabase(..., runVacuum = false)` only (daily retention trim, no VACUUM). File: `implementation/src/main/kotlin/app/aaps/implementation/receivers/KeepAliveWorker.kt`.
- [ ] **`AppRepository.cleanupDatabase`** does **not** run `VACUUM` unless `runVacuum == true` (comment documents SQLITE_NOMEM risk). Default path: `PRAGMA optimize` + deletes + `wal_checkpoint(TRUNCATE)` only. File: `database/impl/src/main/kotlin/app/aaps/database/AppRepository.kt`.
- [ ] **Startup DB maintenance** uses `MainApp.maintainDatabaseIfDue()` → `maintainDatabaseAtStartup()` (**no automatic VACUUM** at launch; full VACUUM only via manual `runVacuum=true`). Key `LongNonKey.LastVacuumRun`, timeout 2 min, `catch (Throwable)`.
- [ ] **Manual maintenance only:** `runVacuum = true` only from explicit UI (e.g. `MaintenanceViewModel`, NS client cleanup dialog), with `DatabaseMaintenanceCoordinator` around compaction in `AppRepository`.
- [ ] **Merge conflicts:** resolving `AppRepository.kt` / `KeepAliveWorker.kt` / `PersistenceLayer.kt` did not re-inline `VACUUM` into the automatic cleanup path. See [MERGE_DEV_2026-05-20.md](MERGE_DEV_2026-05-20.md) (AppRepository combine note).

Reference commits: upstream `16598541af` (regression), fork `11f409c58c` (optional `runVacuum` + KeepAlive fix), upstream `18b2dee6ef` (VACUUM at startup).

---

## 4) Smoke Test Matrix (Required)

Run after merge and before release build:

### Core runtime
- [ ] App starts cleanly after cold launch.
- [ ] No ANR/freeze in first 5 minutes idle.

### Dashboard / Overview
- [ ] Open dashboard, interact for 3 minutes, no freeze.
- [ ] Switch skin dashboard <-> original overview, no regressions.
- [ ] Graph interactions remain responsive.

### AIMI / Smoothie
- [ ] AIMI plugin enabled path validated.
- [ ] Adaptive smoothie enabled path validated.
- [ ] SMB-related indicators/settings remain coherent with behavior.

### AAPSClient / NS flow (if applicable)
- [ ] With incoming status bursts, UI remains responsive for 5-10 minutes.
- [ ] Status lights/chips update without stutter or lockups.
- [ ] Validate with `Low-end device stability mode` ON and OFF (no functional regression in both modes).

### Permissions / storage
- [ ] JSON and CSV writes succeed on device.
- [ ] Missing-permission scenario handled without infinite retries/freezes.
- [ ] xDrip / Dexcom high-frequency receive: no WorkManager inbox stall (post-merge `Inbox.kt` gate).

### Database (optional on device, recommended after DB-related merge)
- [ ] Cold start: no crash during “optimizing database” (monthly checkpoint path, not full VACUUM).
- [ ] After 24h+ uptime: no `SQLITE_NOMEM` / DB freeze in logcat tied to `cleanupDatabase` or `KeepAliveWorker`.

Pass criteria:
- No freeze, no ANR, no blocking UI behavior, no critical feature regression.

---

## 5) PR Gate (Copy/Paste in PR description)

```
## Non-Regression Gate
- [ ] AIMI verified
- [ ] Adaptive Smoothie verified
- [ ] Dashboard + skin switching verified
- [ ] ML JSON/CSV permissions and writes verified
- [ ] Physio path verified
- [ ] Hormonitor structure verified
- [ ] AIMI `.md` documentation consistency verified (references resolve, merge log added)
- [ ] AIMI/SMB/AutoISF parity reviewed (upstream APS changes ported or explicitly declined)
- [ ] Eversense merge constraint reviewed (if native plugin present on branch)
- [ ] Dexcom ONE+ merge constraint reviewed (if native plugin present on branch)
- [ ] Libre 3 native merge constraint reviewed (if native plugin present on branch)
- [ ] Database maintenance regression gate reviewed (KeepAlive `runVacuum=false`, no auto VACUUM in `cleanupDatabase`)
- [ ] Async/freeze checklist reviewed
- [ ] Smoke tests passed
```

---

## 6) Freeze Incident Log (Append-only)

When a freeze is reported, append an entry:

```
Date:
Branch/Build:
Area:
Symptom:
Repro steps:
Suspected commit(s):
Root cause:
Fix commit:
Validation done:
Status: OPEN / MONITORING / CLOSED
```

Rule:
- Never close an incident without reproducible validation notes.

---

## 7) Release Go/No-Go

Release is `NO-GO` if any of the following is true:

- Any checklist item above is unchecked.
- Any OPEN freeze incident on same area/build.
- Any known regression in AIMI, adaptive smoothie, dashboard skin switching, ML permissions, physio, hormonitor structure, Eversense native CGM, Dexcom ONE+ native CGM, or Libre 3 native CGM integration when that integration is part of the release branch.

Release is `GO` only when all gates are green and documented.
