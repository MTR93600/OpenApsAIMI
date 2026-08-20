# Merge constraint: Libre 3 native CGM

Purpose: keep the **native Libre 3 / Libre 3 Plus BLE** BG source alive when merging Nightscout
`dev` or rebasing this fork. Treat it as a first-class constraint next to AIMI, Eversense and
Dexcom ONE+.

Scope: **Libre 3 and Libre 3 Plus only.** The follower paths (xDrip, Glimp, Tomato) and the
`LIBRE_2`, `LIBRE_2_NATIVE`, `LIBRE_3` enum entries are **not** part of this work and must never be
removed to make this feature fit.

Plan: [LIBRE3_NATIVE_AGENT_PLAN.md](LIBRE3_NATIVE_AGENT_PLAN.md)
Licence: [LIBRE3_NATIVE_LICENCE_MEMO.md](LIBRE3_NATIVE_LICENCE_MEMO.md) + `plugins/libre3/NOTICE`
User guide: [LIBRE3_NATIVE_USER_GUIDE.md](LIBRE3_NATIVE_USER_GUIDE.md)

**Status of this file: frozen by lot A11 on 2026-08-20. The feature itself is NOT user-confirmed.**

## Locked identifiers (do not renumber casually)

| Item | Value |
|------|-------|
| Gradle module | `:plugins:libre3` |
| Kotlin package and Android namespace | `app.aaps.plugins.libre3` |
| BgSource plugin class | `app.aaps.plugins.source.Libre3NativePlugin` |
| DI `@IntKey` | **447** (after ONE+ 446, before Aidex 450; not 400 / 440 / 445 / 446) |
| `SourceSensor` | `LIBRE_3_NATIVE` → text `"AAPS-Libre3"`, inserted after the existing `LIBRE_3` |
| UE source | `Sources.Libre3Native` |
| Marker file | `Documents/AAPS/extra/engineering_libre3` (name only, contents never read) |
| Engineering switch | `Libre3BooleanKey.UseRealSkeleton`, key `libre3_use_real_skeleton`, **default false** |
| Intent preference keys | `libre3_status`, `libre3_start`, `libre3_warmup` |
| Notification id | `LIBRE3_DIR_ACCESS_LOST`, **appended last** in `NotificationId.kt` |
| Permission request code | `44701` (BLE runtime permissions only) |
| Plugin icon | `IcPluginByoda`, shared with ONE+. No new icon |
| Log prefix | `LIBRE3_` |
| De-duplication gap | 240 s, and a new sample must have a higher `lifeCount` |

## Must keep on every merge

| Area | Location | Notes |
|------|----------|-------|
| Gradle include | `settings.gradle` → `include ':plugins:libre3'` | Keep the ONE+, Eversense and libkeks includes too |
| Driver module | `plugins/libre3/**` | Namespace `app.aaps.plugins.libre3` |
| Module NOTICE | `plugins/libre3/NOTICE` | MIT texts of LibreCRKit and LibreLoop. Required by MIT |
| Runtime tables | `plugins/libre3/src/main/resources/libre3/**` | 34 MIT binary files, about 1.8 MB. The pairing cannot run without them, and a merge that drops them fails silently at pairing time, not at build time |
| Module Gradle | `plugins/libre3/build.gradle.kts` | `implementation(project(":core:interfaces"))` + `api(libs.androidx.core)` only |
| Manifest | `plugins/libre3/src/main/AndroidManifest.xml` | BLE permissions + `NFC` permission + `android.hardware.nfc` with `required=false` |
| BgSource plugin | `plugins/source/.../Libre3NativePlugin.kt` | Watcher to `PersistenceLayer` (lot A3) |
| DI | `SourcePluginsListModule` `@IntKey(447)` | After ONE+ 446, before Aidex 450 (lot A3) |
| Source dependency | `plugins/source/build.gradle.kts` → `implementation(project(":plugins:libre3"))` | The only extra module dependency that is allowed |
| Enum | `core/data/.../SourceSensor.kt` → `LIBRE_3_NATIVE("AAPS-Libre3")` | Do not touch `LIBRE_2`, `LIBRE_2_NATIVE`, `LIBRE_3` |
| Advanced filtering | `core/data/.../SourceSensorExtensions.kt` includes `LIBRE_3_NATIVE` | |
| UE | `core/data/.../Sources.kt` → `Libre3Native` | |
| DB entity | `database/impl/.../GlucoseValue.kt` → `SourceSensor.LIBRE_3_NATIVE` | |
| DB converters | `database/persistence/.../SourceSensorExtension.kt` **both ways** | |
| Strings EN | `plugins/source/.../values/strings.xml` | `libre3_native`, `libre3_short`, `description_source_libre3_native` and the preference titles |
| Engineering gate | `plugins/source/.../Libre3Availability.kt` + `Libre3NativePlugin.specialShowInListCondition()` | Marker file gate |
| Gate notification | `NotificationId.LIBRE3_DIR_ACCESS_LOST` (appended last, the id is the ordinal) + the branch in `ComposeMainActivity.handleNotificationAction` | Opens the AAPS directory picker |

## Do not regress

- Follower Libre paths: `LIBRE_2`, `LIBRE_2_NATIVE`, `LIBRE_3`, Glimp, Tomato. Libre 3 through
  Juggluco or xDrip stays the production path until the user confirms the native driver.
- **BYODA** `DexcomPlugin` (`@IntKey(440)`) and xDrip (`@IntKey(400)`).
- **Eversense** (`@IntKey(445)`), see [MERGE_CONSTRAINT_EVERSENSE.md](MERGE_CONSTRAINT_EVERSENSE.md).
- **Dexcom ONE+** (`@IntKey(446)`), see
  [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md). Libre 3 must not take
  its notification id, its icon slot or its `libkeks` dependency.
- The AIMI G6 lead path stays for `DEXCOM_G6_NATIVE` only. Libre 3 is a fast sensor and must **not**
  get the G6 lead.

## Rules that protect the protocol

These are safety rules, not style. A merge that loses them is a broken merge.

1. Never send the first pair opcode `0x01` to a sensor that already has a stored `phase5RawKey`.
   A cached reconnect is `0x11` only. If it fails, scan NFC again.
2. Never write a patch control command in v1, and never the shutdown opcode `0x05`. Disconnect,
   plugin stop and process death must only drop the GATT link.
3. The phone certificate is the 162-byte blob with family prefix `03 03`. Never generate one and
   never use the `03 00` file.
4. The NFC PIN must be written to storage, and that write must be finished, before any BLE connect.
5. The handshake and the glucose data plane use different crypto. Do not merge them.
6. `UseRealSkeleton` stays **false** by default.

## Conflict resolution rules

1. Prefer **combine**: keep upstream BG source changes **and** the Libre 3 registration and module.
2. Never resolve `settings.gradle` or `SourcePluginsListModule` by dropping `:plugins:libre3` or
   `@IntKey(447)`.
3. If upstream renumbers IntKeys, pick a new free key (not 400 / 440 / 445 / 446) and update this
   file and the user guide.
4. If upstream adds entries to `NotificationId`, keep `LIBRE3_DIR_ACCESS_LOST` **last**. Never move
   it into the middle of the enum, because the id is the ordinal.
5. The NOTICE must stay as long as any ported code is in the tree.

## Post-merge smoke (do not mark "working" without user confirm)

- [ ] With `Documents/AAPS/extra/engineering_libre3` present, Config Builder lists **Libre 3**
      (`Libre3NativePlugin`, IntKey 447).
- [ ] Without that file, Libre 3 native is absent from Config Builder and no notification is shown.
- [ ] xDrip (400), BYODA Dexcom (440), Eversense (445) and Dexcom ONE+ (446) are still listed.
- [ ] `SourceSensor` still has `LIBRE_2`, `LIBRE_2_NATIVE` and `LIBRE_3`.
- [ ] `UseRealSkeleton` is still off by default.
- [ ] Grep `plugins/libre3` finds no `FDE3`, no `F001`, no `F002`, and no patch control write.
- [ ] Native NFC pairing, warm-up and BG — only after **user device confirmation**.

## Status

| Date | Note |
|------|------|
| 2026-08-19 | Lot A1: licence memo + `plugins/libre3/NOTICE` (LibreCRKit `a86b92f`, LibreLoop `e4a4642`, both MIT). |
| 2026-08-19 | Lot A2: module `:plugins:libre3` created with the stub façade only. No protocol code yet. |
| 2026-08-19 | Lot A3: enums, DB converters both ways, DI `@IntKey(447)`, marker gate, stub plugin, three activity placeholders. |
| 2026-08-20 | Lot A4: NFC ISO 15693 complete, with the MIT byte vectors as tests. PIN is written and awaited before any Bluetooth. |
| 2026-08-20 | Lot A5 part: AES-CCM data plane, Phase 5 and Phase 6 message shapes, certificate rules, key agreement. The table files are not in the tree yet, so the pairing block maker and the first pairing are not ported. |
| 2026-08-20 | Lot A6: identifiers, the three pure policies, the whole pairing command clock, framing, GATT client, scanner, session and the real driver. |
| 2026-08-20 | Lot A7: parsers, sensor life, the usable glucose gate, ingest with its repeat guard. |
| 2026-08-20 | Lot A9: one AIMI log line. Libre 3 native is a fast sensor and takes no G6 lead. |
| 2026-08-20 | Lot A11: this file frozen, checklist wired, user guide written. Not user-confirmed. |
| 2026-08-20 | Committed in ten structural commits on `dev_OAPSAIMI_Libre3`. Two of the three crypto ports are done and vector proven; the first pairing ephemeral is the only piece left. See section 10.0 of the plan. |
