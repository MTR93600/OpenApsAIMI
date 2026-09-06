# Merge constraint: Dexcom ONE+ native CGM

Purpose: preserve the **native Dexcom ONE+ BLE** BG source when merging Nightscout `dev` or rebasing this fork. Treat as a first-class constraint alongside AIMI and Eversense.

Product: [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md)  
Agents: [DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md](DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md)  
User guide: [DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md)

## Locked identifiers (do not renumber casually)

| Item | Value |
|------|-------|
| Gradle module | `:plugins:dexcom_oneplus` (+ `:plugins:libkeks`) |
| Kotlin package | `app.aaps.plugins.dexcomoneplus` |
| BgSource plugin class | `DexcomOnePlusPlugin` |
| DI `@IntKey` | **446** (≠ 440 BYODA `DexcomPlugin`, ≠ 445 Eversense) |
| `SourceSensor` | `DEXCOM_ONEPLUS_NATIVE` → text `"AAPS-DexcomOnePlus"` |
| UE source | `Sources.DexcomOnePlus` |

## Must keep on every merge

| Area | Location | Notes |
|------|----------|-------|
| Gradle include | `settings.gradle` → `include ':plugins:dexcom_oneplus'` **and** `':plugins:libkeks'` | Do not drop |
| Driver module | `plugins/dexcom_oneplus/**` | Namespace `app.aaps.plugins.dexcomoneplus`; BLE session (A6) |
| KEKS / J-PAKE | `plugins/libkeks/**` + `NOTICE` | GPL-3 vendored xDrip pin; dep of dexcom_oneplus |
| Module Gradle | `plugins/dexcom_oneplus/build.gradle.kts` | Keep `implementation(project(":plugins:libkeks"))` |
| BgSource plugin | `plugins/source/.../DexcomOnePlusPlugin.kt` | Watcher → PersistenceLayer |
| DI | `SourcePluginsListModule` `@IntKey(446)` → `bindDexcomOnePlusPlugin` | After Eversense 445, before Aidex 450 |
| Source dep | `plugins/source/build.gradle.kts` → `implementation(project(":plugins:dexcom_oneplus"))` | |
| Enum | `core/data/.../SourceSensor.kt` → `DEXCOM_ONEPLUS_NATIVE("AAPS-DexcomOnePlus")` | |
| Advanced filtering | `core/data/.../SourceSensorExtensions.kt` includes `DEXCOM_ONEPLUS_NATIVE` | |
| UE | `core/data/.../Sources.kt` → `DexcomOnePlus` | |
| DB entity | `database/impl/.../GlucoseValue.kt` → `SourceSensor.DEXCOM_ONEPLUS_NATIVE` | |
| DB converters | `database/persistence/.../SourceSensorExtension.kt` **both ways** | |
| Notif follower | `plugins/source/src/main/assets/notification_reader_packages.json` | Phase A remap |
| Strings EN | `plugins/source/.../values/strings.xml` | `dexcom_oneplus_native`, `dexcom_oneplus_short`, `description_source_dexcom_oneplus_native` |
| Engineering gate | `plugins/source/.../DexcomOnePlusAvailability.kt` + `DexcomOnePlusPlugin.specialShowInListCondition()` | Marker-file gate, see below |
| Gate notification | `NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST` (append last — id is the ordinal) + `ComposeMainActivity.handleNotificationAction` | Opens the AAPS directory picker |

### Engineering availability gate

ONE+ is offered **only** when the marker file `Documents/AAPS/extra/engineering_oneplus` exists
(exact name, no extension, contents never read). This reuses the project's `extra`-directory marker
convention but has its own evaluator, because `Config.isEnabled(ExternalOptions)` caches for the
process lifetime and cannot tell "file absent" from "AAPS directory unreachable".

- Single source of truth: `DexcomOnePlusAvailabilityProvider` (`:plugins:source`). It also owns the
  only copy of the `engineering_oneplus` literal (`ONE_PLUS_ACCESS_FILE_NAME`).
- Single filter point: `PluginBase.showInList` → `ActivePlugin.getSpecificPluginsVisibleInList`,
  which Config Builder, Setup Wizard, search and Quick Launch all read.
- Marker absent → hidden, silent. Directory unreachable / SAF grant lost → hidden **and** a
  restore-access notification, posted once per loss.
- `specialEnableCondition` is deliberately **not** gated: an already-selected ONE+ keeps running.
  BGSOURCE has no `fallbackIfNotVisible` (only SENSITIVITY does) — do not add one here.

### Notification reader packages (Phase A)

Keep these mappings (sensor text must match enum `"AAPS-DexcomOnePlus"`):

| Package | Sensor |
|---------|--------|
| `com.dexcom.d1plus` | `AAPS-DexcomOnePlus` |
| `com.dexcom.dexcomone` | `AAPS-DexcomOnePlus` |

## Do not regress

- **BYODA** `DexcomPlugin` (`@IntKey(440)`) — untouched except shared enums/converters.
- **Eversense** (`@IntKey(445)`) — see [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md).
- AIMI G6 BYODA lead (`DEXCOM_G6_NATIVE` only) — One+ must **not** get G6 lead.

## Conflict resolution rules

1. Prefer **combine**: keep upstream BG-source changes **and** One+ registration/module.
2. Never resolve `settings.gradle` / `SourcePluginsListModule` by dropping `:plugins:dexcom_oneplus` or `@IntKey(446)`.
3. If upstream renumbers IntKeys, re-pick a free key (not 440/445) and update this doc + user guide.
4. NOTICE / THIRD_PARTY for xDrip Direct port (GPL) must stay once A1/A6 land.

## Post-merge smoke (do not mark “working” without user confirm)

- [ ] With `Documents/AAPS/extra/engineering_oneplus` present, Config Builder lists **Dexcom ONE+** (`DexcomOnePlusPlugin` / IntKey 446).
- [ ] Without that file, Dexcom ONE+ is absent from Config Builder and no permission notification is shown.
- [ ] BYODA `DexcomPlugin` still listed and selectable (`@IntKey(440)`).
- [ ] Eversense still listed and selectable (`@IntKey(445)`).
- [ ] `notification_reader_packages.json` still maps `d1plus` / `dexcomone` → `AAPS-DexcomOnePlus`.
- [ ] Native BLE pair / warm-up / BG — only after **user device confirmation** (eng Real path).

## Status

| Date | Note |
|------|------|
| 2026-07-18 | Branch `feature/dexcom-oneplus-native`: module + plugin DI `@IntKey(446)` + enums + notif remap. |
| 2026-07-18 | A12: merge constraint + user guide + non-regression pointer. |
| 2026-07-18 | `:plugins:libkeks` + Android GATT + KEKS + EGV/SessionStart/Backfill; Stub default; Real via eng pref. |
| 2026-07-19 | Attach-safe (no auto SessionStop); docs onboarding/changelog; UI i18n. Device A3 still required. |
| 2026-08-03 | Merge `dev` @ `fa2d2c78a5`: no upstream change on ONE+ paths (`settings.gradle`, `SourceSensor`, `SourcePluginsListModule` `@IntKey(446)`, notification-reader remaps all untouched). Constraint satisfied without re-application — verified by invariant-baseline diff. Log: [MERGE_DEV_2026-08-03.md](MERGE_DEV_2026-08-03.md). |
| 2026-08-08 | Merge `dev` @ `7fc8205e9a` → `dev_OAPSAIMI`: no upstream change on ONE+ paths; invariant-baseline diff byte-identical. One test-only adaptation in `:plugins:source`: the new upstream `NotificationCollectorServiceTest` was adapted to the fork's value-aware `GlucoseDeduplicator.process(packageName, now, glucoseMgdl)` — the fork's 3-argument dedup (which protects against CLOSED_LOOP_LGS on Dexcom transition glitches) was **kept**. Log: [MERGE_DEV_2026-08-08.md](MERGE_DEV_2026-08-08.md). |
| 2026-09-06 | Merge `dev` @ `283a184f60`: dependency-bump-only upstream diff (6 files), no ONE+ path touched. `@IntKey(446)`, `SourceSensor.DEXCOM_ONEPLUS_NATIVE` and notification-reader remaps verified by invariant baseline. Log: [MERGE_DEV_2026-09-06.md](MERGE_DEV_2026-09-06.md). |
