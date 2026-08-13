# Migration: Dexcom ONE+ / G7 native CGM driver onto upstream AndroidAPS `dev`

Target branch: `dev_OnePlusG7`, branched from the official AAPS `dev`.
Source branch: `dev_OAPSAIMI` (OpenApsAIMI fork).

This document is both the port plan and the running log of the port. It is written so that a
maintainer who never saw the fork can read one file and understand what was added, where it was
put, why it was put there, and what is still open.

---

## 1. Baseline facts (verified)

| Fact | Value | How it was checked |
|---|---|---|
| Official upstream remote | `milos` → `https://github.com/nightscout/AndroidAPS.git` | `git remote -v` |
| Local `dev` vs `milos/dev` | identical, 0 ahead / 0 behind | `git rev-list --left-right --count dev...milos/dev` |
| Upstream head at port start | `7fc8205e9a` "Fix scenes expiration" | `git rev-parse milos/dev` |
| Fork work to port | committed on `dev_OAPSAIMI`, tree clean | `git status --porcelain` empty |
| Last fork commits in scope | `3b6e2f56e7` (staging + early promotion), `a815ff0c6a` (session recovery fix) | `git log --oneline` |

## 2. The rules this port follows

Two normative documents, both taken from `milos/dev` (not from the fork):

**`CONTRIBUTING.md`**

- Branch from the most recent `dev`, one branch per feature, rebase on `dev` before any PR.
- Only English strings in `strings.xml`; every other language comes from Crowdin. Never hardcode
  user-facing text.
- Android Studio default indents (4 spaces), autoformat every changed file.
- "Start small" — smaller changes are easier to review.
- **AI-generated pull requests are not welcome.** The document is explicit: AndroidAPS is
  safety-critical software, and the accepted way to use AI is to open an Issue containing the
  analysis, leaving the code to the maintainers.

**`CLAUDE.md`** (root of `milos/dev`)

- Verified byte-identical to the fork's copy except for **one** added line (a fork-only merge
  procedure for the Eversense plugin). The development rules already followed in the fork are
  therefore the upstream rules.
- Load-bearing points here: explicit imports only; no user-facing text built by string
  concatenation (use format-string resources with positional placeholders); `stringResource()` in
  Compose rather than `ResourceHelper`; KDoc `[links]` must resolve or be backticked; avoid new
  inter-module `project()` dependencies without discussion; never claim a feature works before it
  has been tested on device.

### 2.1 Consequence for this work

The port is produced **in the fork**, on `dev_OnePlusG7`. Any later submission upstream is the
repository owner's decision and, under the rule above, would have to go through an Issue plus code
owned and rewritten by a human. This document is written to be useful in exactly that scenario: it
is the analysis a maintainer would need.

### 2.2 What is deliberately **not** ported

- **The Eversense native CGM plugin.** It exists only in the fork. Nothing from it is in this
  branch, and no ONE+ file refers to it.
- **Everything AIMI.** The goal is a driver that works for users who do not run AIMI, so the port
  depends on upstream interfaces only.

## 3. Licence and provenance

- AndroidAPS is **AGPL-3.0** (`LICENSE.txt`).
- Parts of the driver derive from **xDrip+** and from **Juggluco**, both **GPL-3.0**.
- GPL-3.0 code may be combined with AGPL-3.0 (GPLv3 §13), but the combination has to be explicit:
  per-file provenance headers kept, `NOTICE` carried over, provenance stated in any PR description.

Where the paperwork now lives on this branch:

| File | Role |
|---|---|
| `plugins/source/NOTICE` | Full attribution for the driver: xDrip section (with commit pin), Juggluco section, third-party libraries, and the list of files original to this work |
| `plugins/libkeks/NOTICE` + `plugins/libkeks/README.md` | Attribution and pin for the vendored KEKS code, kept verbatim from xDrip |
| Per-file KDoc headers | Each derived file names its upstream symbol and its pin |

Two provenance defects were found during the port and fixed here:

1. `session/OnePlusEgvSession.kt` credited "Juggluco `getdatacmd` + Ob1 `doGetData`" followed by a
   single commit hash — the **xDrip** hash. A reader could take it as a Juggluco pin. The header now
   lists the two upstreams separately and says in plain words that the xDrip pin does not cover the
   Juggluco part.
2. `NOTICE` and four source files still pointed at the old fork-only paths
   (`:plugins:dexcom_oneplus`, `docs/DEXCOM_ONEPLUS_ATTRIBUTION.md`,
   `docs/DEXCOM_ONEPLUS_LICENCE_MEMO.md`). They now point at `plugins/source/NOTICE` and at this
   document.

**Still open:** `NOTICE` §2 records no Juggluco commit pin. It is marked `TODO` upstream-style
rather than filled with a guess. It must be resolved before any wide distribution.

## 4. Target architecture (verified on `milos/dev`)

Two findings drove the layout decisions.

**BG sources are packages, not modules.** Upstream keeps every BG source inside `:plugins:source`
(`instara/`, `notificationreader/`, …). The fork had the driver in a module of its own,
`:plugins:dexcom_oneplus`. Keeping it would have added an inter-module dependency for no reason,
against the rule in `CLAUDE.md`.

**The Overview is Compose, in the `ui` module.**
`plugins/main/.../general/overview/OverviewFragment.kt` is an *empty file* upstream (blob
`e69de29`, the git hash of the empty blob) — a leftover. The live screen is:

- `ui/src/main/kotlin/app/aaps/ui/compose/overview/`
  - `OverviewScreenSplit`, `OverviewScreenStacked`, `OverviewScreenTablet` — three layouts, each
    building the same sections
  - `OverviewStatusSection.kt` — the card that holds the status rows
  - `statusLights/` — `StatusViewModel.kt`, `StatusUiState.kt`, `StatusItem.kt`,
    `StatusComponents.kt`; the subsystem that already renders age/level rows
- Conventions: a composable has a sibling `*Previews.kt`; a view model has a `*ViewModelTest.kt`.

## 5. What was ported

### 5.1 New module

| Module | Content |
|---|---|
| `:plugins:libkeks` | 24 Java files vendored verbatim from xDrip (`jamorham.keks`, `jamorham.libkeks`, `IPluginDA`), plus `NOTICE`, `README.md`, manifest, build file. Registered in `settings.gradle`. |

It stays a module of its own precisely so the vendored code is never edited: any change to it shows
up as a diff against the pin.

### 5.2 Driver, folded into `:plugins:source`

48 Kotlin files under
`plugins/source/src/main/kotlin/app/aaps/plugins/source/dexcomoneplus/`, with 21 test files
mirroring them:

| Package | Role |
|---|---|
| (root) | `OnePlusCgmDriver` + real/stub implementations, glucose sample model, warm-up state, log markers |
| `gatt/` | GATT client interface + Android implementation, ONE+ UUIDs, KEKS notify routing |
| `scan/` | BLE scanner interface + Android implementation, scan budget, scan result |
| `identity/` | GS1 applicator parsing, sensor identity, ADV candidate matching, persistent sensor store |
| `parse/` | Wire messages: EGV, session start/stop, transmitter time, backfill, CRC16, calibration state |
| `session/` | Session orchestration: auth (KEKS and short-auth), EGV loop, backfill, start policy, cycle policy |
| `oem/` | Per-OEM BLE profiles (Pixel / Samsung / generic fallback) |
| `reconnect/`, `warmup/` | Reconnect policy, warm-up clock |

### 5.3 BG source plugin and its UI

In `plugins/source`, next to the other sources:

- `DexcomOnePlusPlugin.kt` — the `PluginBase` / `BgSource`, and the implementation of
  `CgmSensorStatusProvider`
- `DexcomOnePlusIngest.kt` — turns driver samples into `insertCgmSourceData` calls
- `DexcomOnePlusStaging.kt` — the dual-sensor (pre-soak) rules, including the promotion gates
- `DexcomOnePlusWarmupMapper.kt`, `DexcomOnePlusWarmupNotification.kt`,
  `DexcomOnePlusWarmupBasalGuard.kt` — warm-up mapping, user notification, safety guard
- `DexcomOnePlusAvailability.kt`, `OnePlusBlePermissionHelper.kt`
- `activities/` — start, status and warm-up screens
- `compose/` — the warm-up countdown composable and its labels
- `keys/DexcomOnePlusBooleanKey.kt`, `keys/DexcomOnePlusIntentKey.kt` — preference keys, in the
  same place as the other source keys

### 5.4 Upstream files touched

Kept as small as possible — 28 files, +619 / −4 lines outside the driver itself:

| File | Change |
|---|---|
| `settings.gradle` | `include ':plugins:libkeks'` |
| `plugins/source/build.gradle.kts` | `implementation(project(":plugins:libkeks"))`, with a comment saying why the module exists |
| `plugins/source/src/main/AndroidManifest.xml` | BLE permissions (`BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) and the three activities |
| `di/SourcePluginsListModule.kt` | `@Binds @AllConfigs @IntoMap @IntKey(446)` — the free slot after `DexcomPlugin` (440) |
| `di/SourceModule.kt` | three `@ContributesAndroidInjector` entries for the activities |
| `core/data/.../SourceSensor.kt` | `DEXCOM_ONEPLUS_NATIVE("AAPS-DexcomOnePlus")` |
| `core/data/.../SourceSensorExtensions.kt` | added to `ADVANCED_FILTERING_SENSORS` (the driver delivers filtered Dexcom values) |
| `core/data/.../ue/Sources.kt` | `DexcomOnePlus` |
| `database/impl/.../GlucoseValue.kt`, `.../UserEntry.kt` | matching Room enum entries |
| `database/persistence/.../SourceSensorExtension.kt`, `.../SourcesExtension.kt` | four `when` arms (two per file, both directions) |
| `implementation/.../UserEntryPresentationHelperImpl.kt` | icon and colour for the new source |
| `core/interfaces/.../notifications/NotificationId.kt` | `DEXCOM_ONEPLUS_DIR_ACCESS_LOST` |
| `core/ui/src/main/res/drawable/ic_shield.xml` | icon used by the status screen |
| `plugins/source/src/main/res/values/strings.xml` | 123 English strings, all under one comment block |
| `ui/` (7 files) | the Overview work, see §7 |

### 5.5 New public API in `core/interfaces`

Two files, both source-agnostic on purpose so no consumer has to know about Dexcom:

- `source/CgmWarmupStatus.kt` — `CgmWarmupStatus` (active, phase, remaining, ends-at, total) and
  `CgmWarmupProvider`
- `source/CgmSensorLifecycle.kt` — `SensorSlot`, `StagingState`, `CgmSensorLifecycle`,
  `CgmStagingEvidence`, `PromotionRejectReason`, `PromotionResult`, and `CgmSensorStatusProvider`

Consumers cast the active source: `(activePlugin.activeBgSource as? CgmWarmupProvider)`. A source
that does not implement them changes nothing on screen.

### 5.6 The engineering gate, and why it is gone

In the fork the plugin only appeared in the plugin list when a marker file named
`engineering_oneplus` was present in `Documents/AAPS/extra/`. That was right while the driver was
being written: it kept an unfinished BLE driver out of everyone else's reach.

It is wrong for this port, whose whole point is that people who do not run the fork can use the
sensor. A gate that asks a user to create an extension-less file by hand, in a folder reached
through the storage-access framework, is not a feature — it is a barrier. So the gate is not
carried over: ONE+ appears in Config Builder, the Setup Wizard, search and Quick Launch like every
other BG source, and the user turns it on if they want it.

Removing it also removed everything that existed only to serve it: the availability provider and
its two tests, the "AAPS folder access lost" notification with its two strings, and the
`NotificationId` entry. Nothing else in the driver reads the AAPS directory, so none of it had a
second purpose.

One engineering flag remains, and it is harmless: `UseRealSkeleton` selects the Real driver over
the test Stub. It is `engineeringModeOnly`, but its default is `true` and a preference outside
engineering mode always reads its default, so an ordinary user gets the Real driver and never sees
the switch.

## 6. Adaptation map (fork → upstream)

| Fork element | Upstream status | Decision |
|---|---|---|
| Module `:plugins:dexcom_oneplus` | ABSENT — sources are packages | Dissolved into `:plugins:source`; package renamed `app.aaps.plugins.dexcomoneplus` → `app.aaps.plugins.source.dexcomoneplus` |
| Module `:plugins:libkeks` | ABSENT | Kept as a module (vendored code must stay untouched) |
| `PersistenceLayer.insertCgmSourceData` | DIFFERENT — `suspend` upstream | No change needed: the fork already called it from `ioScope.launch` |
| `SourceSensor` / `Sources` / Room enums | EXIST, without the new value | One entry each; the four `when` sites in the converters are exhaustive and had to be extended |
| `NotificationId` | EXISTS | One entry |
| Preference keys | EXIST, same package layout | Copied as is |
| Overview XML status lights | ABSENT — Compose now | Rebuilt as two `StatusItem` rows in `statusLights/` (§7) |
| Eversense plugin references | ABSENT upstream, fork-only | Removed; three comments that mentioned it reworded |
| AIMI-specific hooks | ABSENT upstream | None ported; the driver talks to upstream interfaces only |

## 7. Overview: warm-up and second sensor

### 7.1 What the user sees

Inside the existing Status card, right under the sensor row:

- **Warm-up** — appears only while a source reports a warm-up. Shows the minutes left and a bar
  that fills as the warm-up progresses. Disappears by itself when the warm-up ends.
- **New sensor** — appears only while a second sensor is staged next to the one in use. Shows
  "warming up", then "ready in *n* h" with a progress bar while it settles, then "Ready to switch".
  The level column shows how many valid readings the staged sensor has produced, which is the only
  proof that a collect-only sensor is really alive. Once it is ready the row carries a **Switch**
  button: it asks for confirmation, then hands the loop over to the new sensor and reports the
  outcome — including the reason when the source refuses.

Both rows are `null` for every other source, so the card is unchanged for everyone else.

The Switch button appears **only** in the READY state. Forcing an early switch (when the sensor in
use dies before the new one has settled) stays in the plugin's own status screen, which is where the
warning about less reliable early readings is written. The button is also hidden when
`commandsAllowed` is false, like every other mutating action in this card.

### 7.2 How it is built

| File | Change |
|---|---|
| `statusLights/StatusUiState.kt` | slots `warmUpStatus`, `secondSensorStatus`, `canPromoteSecondSensor`, `promotionMessage` |
| `statusLights/StatusViewModel.kt` | `buildWarmUpStatus()`, `buildSecondSensorStatus()`, `canPromoteSecondSensor()`, `promoteSecondSensor()`, `promotionMessage()`, `clearPromotionMessage()`, plus a 10 s ticker that only refreshes when `hasTransientCgmStatus()` is true |
| `statusLights/StatusComponents.kt` | two extra rows in `StatusSectionContent`, the Switch action on the second-sensor row, divider logic updated |
| `statusLights/StatusComponentsPreviews.kt` | `StatusSectionContentCgmTransientsPreview` |
| `overview/OverviewStatusSection.kt` | new parameters, the confirmation and result dialogs, and the `listOfNotNull` that decides whether the card shows at all |
| `OverviewScreenStacked/Split/Tablet.kt` | pass the new values and the two callbacks |
| `ui/src/main/res/values/strings.xml` | 15 English strings, with `comment=` translator notes where they carry placeholders |

### 7.3 Two design points worth keeping

**The 12 h settle rule stays in the plugin.** The first draft was going to compute the countdown in
the view model from a copied constant. Instead `CgmSensorStatusProvider` gained
`stagingSettleRemainingMs`, published by the source. A UI copy of that threshold would drift from
the rule that actually gates promotion, and the countdown would then lie to the user.

**The warm-up countdown prefers the wall-clock end time.** `CgmWarmupStatus` carries both
`remainingMs` and `endsAtEpochMs`. A stored countdown goes stale between two radio windows, so the
end time wins whenever the protocol gave one. A unit test pins this behaviour.

## 8. Verification

Run on 2026-08-12, on this branch, with `--no-daemon`:

| Check | Command | Result |
|---|---|---|
| App assembles | `:app:assembleFullDebug` | BUILD SUCCESSFUL |
| Driver + plugin tests | `:plugins:source:testFullDebugUnitTest` | 382 tests, 0 failures |
| UI tests (incl. the two new Overview rows) | `:ui:testFullDebugUnitTest` | 402 tests, 0 failures |
| Domain enums | `:core:data:test` | 24 tests, 0 failures |
| Room converters | `:database:persistence:testFullDebugUnitTest` | 56 tests, 0 failures |
| User-entry presentation | `:implementation:testFullDebugUnitTest` | 707 tests, 0 failures |

Of those, the tests written for this port's Overview work are `StatusViewModelTest` (10 cases,
covering the countdown source of truth, the row disappearing after warm-up, the hour ceiling on the
settle countdown, the READY state, the switch being offered only when ready, and one distinct
wording per refusal reason) and `StatusSectionContentTest` (3 cases, rendering both new rows and
clicking the Switch button).

**Not verified, and it matters:** nothing here has been run against a real ONE+ / G7 sensor on this
branch. A green build is not a working CGM driver. Before anyone calls this done, a full sensor
session has to be observed on device: pairing, warm-up, first readings, reconnects, backfill, and a
staged second sensor promoted to production.

## 9. Action log

| Date | Action | Result |
|---|---|---|
| 2026-08-12 | Verified `dev` == `milos/dev`, fork tree clean, work committed | ok |
| 2026-08-12 | Read upstream `CONTRIBUTING.md` and `CLAUDE.md`; diffed rules against the fork | 1 line of difference |
| 2026-08-12 | Located the real upstream Overview (Compose, `ui` module) | `OverviewFragment.kt` upstream is empty |
| 2026-08-12 | Checked licence and provenance surface | AGPL-3.0 target, GPL-3.0 provenance |
| 2026-08-12 | Created `dev_OnePlusG7` from `dev` | at `7fc8205e9a` |
| 2026-08-12 | Stage 1 — `:plugins:libkeks` added; driver folded into `:plugins:source`, package renamed | compiles, driver tests green |
| 2026-08-12 | Stage 2 — `SourceSensor`, `Sources`, Room entities, converters | four exhaustive `when` sites had to be extended |
| 2026-08-12 | Stage 3 — plugin, activities, DI, keys, manifest, 123 strings | app module compiles |
| 2026-08-12 | Removed Eversense files dragged in by a wildcard checkout | 4 activities + 2 key files removed, 3 comments reworded |
| 2026-08-12 | Stages 4 and 5 — Overview warm-up row and second-sensor row, previews, tests | `:ui` compiles, 9 tests green |
| 2026-08-12 | Provenance fixes — Juggluco pin ambiguity, stale module paths | `NOTICE` and 5 files updated |
| 2026-08-12 | Full build and wide test run | APK built; 1567 tests green across the 5 touched modules |
| 2026-08-12 | Compared the port against the fork branch, point by point | driver and plugin functionally identical; three gaps found, listed below |
| 2026-08-12 | Gap 1 — the ONE+ "directory access lost" notification did nothing when tapped | dropped instead: the notification only existed to serve the engineering gate, removed below |
| 2026-08-12 | Gap 2 — no way to switch sensors from the home screen (the fork had it on its own dashboard) | Switch button added to the second-sensor row, with confirmation and result dialogs |
| 2026-08-12 | Gap 3 — the fork's assertion on `DEXCOM_ONEPLUS_NATIVE.advancedFilteringSupported()` was not carried over | restored in `SourceSensorExtensionsTest` |
| 2026-08-12 | Rebuild after the three fixes | APK built; 1571 tests green |
| 2026-08-13 | Removed the `engineering_oneplus` marker-file gate (see §5.6) | plugin now offered like any other BG source; 3 files, 2 strings, 1 notification id deleted |
