# Libre 3 / Libre 3 Plus native CGM — Claude multi-agent playbook

**Status:** execution playbook for Claude. Not implemented yet. Not user-confirmed.  
**Date:** 2026-08-19  
**Scope lock:** **Libre 3 and Libre 3 Plus only.** No new Libre 2 / Libre 2 Plus code. Do not delete existing follower Libre 2 / Glimp / Tomato.  
**Template (shell only):** Dexcom ONE+ (`docs/DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md` + `:plugins:dexcom_oneplus`). Do **not** copy staging, GS1, `WarmupBasalGuard`, or `UseRealSkeleton default true`.  
**Protocol sources (MIT only):**

- https://github.com/airedev326/LibreCRKit (protocol owner — **parser Swift is wire truth**, not `protocol.md` offsets)
- https://github.com/LoopKit/LibreLoop (live handshake rules, CCCD set, persist)

Pinned: LibreLoop `main` `e4a4642`. LibreCRKit pin in Loop `Package.resolved` was `66920c6`. `protocol.md` first-pair fallback to `0x01` on an active sensor is **wrong** — follow LibreLoop live code.

**This is the only mission file.** Claude is C0. Follow §4.1 until §9 Claude boxes are true or a hard stop hits.

---

## 0. How Claude must run this file

### 0.1 Conductor (auto-prompt until done)

Claude is **C0**. After each lot:

1. Check that lot **DoD**.
2. Spawn **P** if the lot touched NFC, BLE, crypto, parse, persist, or GATT commands.
3. Spawn **R** if the lot touched Kotlin, XML, Gradle, strings, or tests.
4. If P or R report a **must-fix**, rework the **same** lot. No next lot until green.
5. Fill §11. Do **not** mark the feature working.
6. Start the next lot from **§4.1** (not numeric order, not the Gantt sketch).

**Stop only if:** a §2 ban is about to break; compile fails twice with the **same** error; an MIT vector/table/cert is missing (ask user; no Abbott `.so`, no Juggluco); the user says stop; or last lot is **A11** and §9 Claude boxes are true.

Do not stop because a lot is large. Split inside the lot, then continue.

### 0.2 Auto-prompt (send after every lot; do not wait for the user)

```text
You are C0 Conductor for docs/LIBRE3_NATIVE_AGENT_PLAN.md.
Last finished lot: [ID]
DoD last lot: [PASS / FAIL + one line]
P protocol review: [SKIP / PASS / FAIL + must-fix]
R AAPS rules review: [SKIP / PASS / FAIL + must-fix]
Open must-fix: [none / list]
Lot log §11: updated for last lot.
Next action:
- if must-fix then REWORK the same lot;
- else start the next lot from §4.1 (not numeric order).
- if last finished lot is A11 and §9 boxes that Claude can check are true, STOP and report to the user (do not mark working).
Hard bans in §2 still apply. Do not delete existing LIBRE_2 / LIBRE_2_NATIVE / Glimp / Tomato code. Libre 2 / 2+ is out of scope. Do not install the app. Do not run connectedAndroidTest. Do not commit unless the user asked. Do not claim working without user device confirm.
Read the lot section again before editing. Clone LibreCRKit + LibreLoop to /tmp if missing. Never cd && in bash. Do not invent identifiers; use §1 only.
```

### 0.3 Agent roster

| ID | Agent | May edit code? | When |
|----|-------|----------------|------|
| **C0** | Conductor | Yes, after lot prompts | Always |
| **P** | Protocol verifier (LibreCRKit + LibreLoop) | Review only | After A4, A5, A6, A7 (and A8 if NFC reader wired) |
| **R** | AAPS coding-rules reviewer | Review only | After every Kotlin / XML / Gradle / strings / tests lot |
| **A1** | Licence & NOTICE | Docs + NOTICE | First |
| **A2** | Gradle module skeleton | Yes | After A1 |
| **A3** | Enums, DI, Stub plugin, marker | Yes | After A2 |
| **A4** | NFC ISO 15693 | Yes | After A3 |
| **A5** | Crypto | Yes | After A4 |
| **A6** | BLE GATT + handshake + policy | Yes | After A5 |
| **A7** | Parse + ingest `GV` | Yes | After A6 |
| **A8** | Start / Status / Warmup UI | Yes | After A7 |
| **A9** | AIMI log line only | Yes, tiny | After A8 |
| **A10** | Unit tests + compile | Tests | After A9 |
| **A12** | Integration captain | Wire only | After A10 |
| **A11** | Docs freeze | Docs | After A12 (last) |

One lot = one owner. Do not invent names, IntKeys, certs, or command bytes.

### 0.4 Clone protocol repos (read-only)

```text
git clone --depth 1 https://github.com/airedev326/LibreCRKit /tmp/LibreCRKit
git clone --depth 1 https://github.com/LoopKit/LibreLoop /tmp/LibreLoop
```

If clones exist, use them. Do not push. Do not vendor the Swift tree. Port behaviour + MIT tables + MIT cert bytes, with NOTICE.

### 0.5 Bash and Gradle

- Never `cd &&` or `cd;`. Use absolute paths or `git -C`.
- No top-level `&&` / `||` / `;` between commands.
- Do not start commands with `awk`, `cut`, `tr`, `sort`, `uniq`, standalone `diff`, `which`.
- No gradle daemon: `./gradlew … --no-daemon`.
- Pass/fail: redirect to a log, then read the log. Do not pipe to `tail`.
- Never install the app. Never `connectedAndroidTest` unless the user explicitly allows it.
- Compile after A2, A3, A4, A5, A6, A7, A8, A9, A12. Skip compile for string-only edits inside A8/A11.
- On KSP error: compile again. Do not clean.
- Do not commit unless the user asks.

---

## 1. Locked identifiers (do not rename later)

| Item | Locked value |
|------|----------------|
| Gradle module | `:plugins:libre3` |
| Android namespace | `app.aaps.plugins.libre3` |
| Driver package | `app.aaps.plugins.libre3` |
| BgSource class | `app.aaps.plugins.source.Libre3NativePlugin` |
| DI `@IntKey` | **447** (after One+ 446, before Aidex 450). Do not use 400/440/445/446. |
| `SourceSensor` | `LIBRE_3_NATIVE("AAPS-Libre3")` inserted **after** existing `LIBRE_3`. Do **not** rename/remove `LIBRE_3`, `LIBRE_2`, `LIBRE_2_NATIVE`. |
| UE | `Sources.Libre3Native` |
| Marker file | `Documents/AAPS/extra/engineering_libre3` (name only, never read contents). Literal lives only in `Libre3Availability.kt`. |
| EN string names (trio) | `libre3_native`, `libre3_short`, `description_source_libre3_native` |
| EN string names (prefs/titles) | `libre3_status_title`, `libre3_status_summary`, `libre3_start_title`, `libre3_start_summary`, `libre3_warmup_title`, `libre3_warmup_summary`, `libre3_use_real_skeleton`, `libre3_use_real_skeleton_summary`, `libre3_plugin_summary`, `libre3_aaps_directory_access_lost`, `libre3_aaps_directory_select` |
| Log prefix | `LIBRE3_` |
| Boolean pref | `Libre3BooleanKey.UseRealSkeleton` — `key = "libre3_use_real_skeleton"`, **`defaultValue = false`**, `engineeringModeOnly = true`. Do **not** copy One+ `defaultValue = true`. |
| Intent pref keys | `libre3_status`, `libre3_start`, `libre3_warmup` |
| `NotificationId` | `LIBRE3_DIR_ACCESS_LOST` — **append last** in `NotificationId.kt`. Never insert mid-enum. Never reuse `DEXCOM_ONEPLUS_DIR_ACCESS_LOST`. |
| Permission request code | `44701` (BLE runtime only; NFC is install-time) |
| Plugin icon | reuse `IcPluginByoda` (same as One+). Do not add a new icon. |
| Phone cert | exact 162-byte family `03 03` blob from `/tmp/LibreCRKit` `PhoneCert.swift` / bundled `phone_cert_162b.bin`. Do not generate a cert. |
| Production `receiverID` | one `UInt32` from FNV over a **new** app UUID created once and stored in Keystore. Do **not** reuse the pinned test UUID `5abb0ad8-…` / `0x6F0D8378` (unit-test vector only). |
| Dedup gap | **240 s** and reject `lifeCount` ≤ last accepted. |
| Warmup | duration from NFC (default 60 min). Remaining = `warmupMinutes - lifeCount`, floored at 0. NFC does not send “remaining”. |
| Historic / clinical ingest | **out of scope for v1**. Still arm CCCDs 1–7. Do not parse those streams into `GV`. |
| Staging / pre-soak | **out of scope for v1**. |
| `patchControl` writes | **none in v1** (includes no shutdown `0x05` and no backfill). |
| Allowed extra `project()` dep | **pre-approved:** `plugins/source` → `implementation(project(":plugins:libre3"))`. Driver module → `:core:interfaces` + AndroidX only. Do not drop eversense / dexcom_oneplus / libkeks. |

Libre 3 vs Libre 3 Plus: **same driver**. NFC `generation` `0` = Libre 3 (wear often 20160 min / 14 days). `1` = Libre 3 Plus / Instinct (wear often 21600 min / 15 days).

`CgmSensorStatusProvider`: implement it so the dashboard compiles, but staging `StateFlow`s are always `ABSENT` / null and `promoteStagingToProduction` always `Rejected(STAGING_ABSENT)`. Do not clone `DexcomOnePlusStaging`, dual-slot Start UI, or `WarmupBasalGuard`.

Do not reuse follower tag `LIBRE_3` (`Libre3`) for native inserts.

---

## 2. Hard bans

Break any of these → stop the lot and report.

1. **No new Libre 2 / Libre 2 Plus** code, UUIDs (`FDE3` / `F001` / `F002`), or comments that claim 2+ works. Leave existing follower enums `LIBRE_2` / `LIBRE_2_NATIVE` / Glimp / Tomato **untouched**.
2. **No Juggluco source copy** (Java, C++, their tables, their cert loaders).
3. **No Abbott `.so`**.
4. **Never send first-pair `0x01`** on a sensor that already has `phase5RawKey`. Cached reconnect is **`0x11` only**. If `0x11` fails → NFC A8 re-scan. Do **not** fall back to `0x01` (LibreLoop live rule).
5. **Never send patchControl `0x05`** (or any `patchControl` opcode in v1). Ordinary disconnect, plugin stop, and process death must only drop GATT.
6. **Never first-pair with a random P-256 key.** Use LibreCRKit `SessionKey.makeFirstPairNativeEphemeral` and the same entropy for Phase 5.
7. **Phone cert must be the MIT 162-byte `03 03` blob.** Family `03 00` is rejected live. Do not synthesize.
8. **Persist NFC PIN and wait for that write** before any BLE connect. A8 replaces the PIN.
9. **Do not mix crypto planes:** handshake CCM uses LibAES white-box block; data-plane glucose uses **standard AES-CCM-128** with `kEnc` / `ivEnc`.
10. **Do not claim “working”** without user device confirm.
11. **Do not touch** BYODA Dexcom, Eversense, xDrip behaviour, Glimp, Tomato, AIMI dose engines except the A9 log + the two cloned tests.
12. **No extra `implementation(project(…))`** beyond the pre-approved `:plugins:source` → `:plugins:libre3` and `:plugins:libre3` → `:core:interfaces`.
13. **Usable glucose only** may be inserted: displayable + DQ good + condition OK + not warmup + not expired. Display map: 1–38 → 39; 39–501 valid; 502–999 → 501. `notActionable` is advisory — still usable if other gates pass.
14. **Stub is default.** `UseRealSkeleton` default false. Real driver must not connect until A4 persist-before-BLE and A5 vectors are green.
15. **No staging / pre-soak / second BLE session** in v1.
16. **Do not clone** `DexcomOnePlusWarmupBasalGuard`, `DexcomOnePlusStaging`, or GS1 applicator parser. Libre 3 PIN comes from NFC, not a typed pairing code.
17. **LibAES tables** come only from MIT LibreCRKit RuntimeTables. If missing after clone: hard stop.
18. **`NotificationId` only append last.**

---

## 3. Protocol pins (agent P checks these)

Swift parsers are truth. `protocol.md` is overview only.

### 3.1 NFC (ISO 15693, manufacturer `0x7A`)

Android: `NfcV.transceive`. Feature `android.hardware.nfc` **`required=false`**. BLE reconnect without NFC if store already has PIN.

| Cmd | Raw idea | Body |
|-----|----------|------|
| A1 patch info | `02 A1 7A` | empty |
| A0 activate | `02 A0 7A` + 10 B | when state `0x01` |
| A8 switch receiver | `02 A8 7A` + 10 B | otherwise |

A0/A8 body (10 B): `timeSeconds_LE4 || receiverID_LE4 || abbottCRC16_LE2`.

CRC: init `0xFFFF`, **bit-reverse each input byte**, poly `0x1021`, no final XOR, output LE.

Pinned **test** vector (must unit-test; do not use as production RID): time `1777216508` = `FC 2B EE 69`, RID `0x6F0D8378` = `78 83 0D 6F` → CRC `0x71B4` → body `FC2BEE6978830D6FB471`. Full A0: `02A07AFC2BEE6978830D6FB471`.

Receiver ID (wrapping mul):

```text
value = 0
for unit in uniqueID.utf16:
    value = (value &* 0x811C9DC5) XOR UInt32(unit)
```

Pinned **test** UUID `5abb0ad8-dc2e-4ede-9e2d-67472a3e630e` → `0x6F0D8378`. Production uses a **new** stored app UUID (see §1).

A0/A8 response 19 B: `00 A5 00 || bleAddress_LE6 || blePIN4 || activationTime4 || crc2`. Display MAC by **reversing** the 6 address bytes.

Patch-info (normalized `00 A5 …`):

| Off | Field |
|-----|--------|
| 7 u16 LE | `generation` 0 = Libre 3, 1 = Libre 3 Plus / Instinct |
| 9 u16 LE | `wearDurationMinutes` |
| 16 u8 | warmup units × 5 (capture `0x0C` → **60 min**) |
| 17 u8 | state `0x01` → A0 else A8 |
| 18–26 | ASCII serial 9 chars |

### 3.2 GATT base `0898xxxx-EF89-11E9-81B4-2A2AE2DBCCE4`

| Role | UUID |
|------|------|
| Data / scan | `089810CC-EF89-11E9-81B4-2A2AE2DBCCE4` |
| Security | `0898203A-EF89-11E9-81B4-2A2AE2DBCCE4` |
| glucoseData | `0898177A-EF89-11E9-81B4-2A2AE2DBCCE4` |
| patchStatus | `08981482-EF89-11E9-81B4-2A2AE2DBCCE4` |
| patchControl | `08981338-EF89-11E9-81B4-2A2AE2DBCCE4` |
| historicData | `0898195A-EF89-11E9-81B4-2A2AE2DBCCE4` |
| eventLog | `08981BEE-EF89-11E9-81B4-2A2AE2DBCCE4` |
| clinicalData | `08981AB8-EF89-11E9-81B4-2A2AE2DBCCE4` |
| factoryData | `08981D24-EF89-11E9-81B4-2A2AE2DBCCE4` |
| secCertData | `089823FA-EF89-11E9-81B4-2A2AE2DBCCE4` |
| secChallengeData | `089822CE-EF89-11E9-81B4-2A2AE2DBCCE4` |
| secCommandResponse | `08982198-EF89-11E9-81B4-2A2AE2DBCCE4` |

Framing: writes `offset_LE2 || chunk` (18 B payload). Notifies `seq1 || chunk` (19 B payload). `glucoseData` data-plane also splits **15 B + 20 B**; concat before CCM (`DataPlaneDecoder.swift`).

### 3.3 Handshake command clock (`PairingFlow.swift`)

Clock: 1-byte writes on `secCommandResponse`. The two `0x08` uses are **different**.

**First pair (no `phase5RawKey`) — REQUIRED `0x01`:**

1. `0x01` StartAuthentication  
2. `0x02` LoadCertificate  
3. write phone cert 162 B (`03 03`) to `secCertData` (9×18 B fragments)  
4. `0x03` SendCertificateLoadDone → wait prefix `0x04`  
5. `0x09` GetCertificate → wait prefix `0x0A`  
6. sensor cert 140 B  
7. `0x0D` ValidateCertificate  
8. write native eph pubkey 65 B **padded to 72 B**  
9. `0x0E` SendEphemeralDone → wait prefix `0x0F`  
10. sensor eph 65 B  
11. `0x11` StartAuthorization → wait prefix `0x08` (ChallengeLoadDone)  
12. **23 B** on `secChallengeData`: `R1_16 || nonce7`  
13. write Phase 5 **54 B**  
14. phone sends `0x08` SendChallengeLoadDone → wait prefix `0x08`  
15. Phase 6 **67 B**

**Cached reconnect (has `phase5RawKey`) — `0x01` FORBIDDEN:**

1. `0x11` only (skip cert/eph) → prefix `0x08` then 23 B R1  
2. Phase 5 with saved `phase5RawKey` + current PIN as `tail4`  
3. `0x08` → prefix `0x08` then 67 B Phase 6  

Yields **new** `kEnc`/`ivEnc`. Reuses `phase5RawKey`. If this fails: back off, NFC A8, **never** `0x01`.

Phase 5 plaintext 36 B: `R1_16 || R2_16 || PIN4`. Wire 54 B: `ct36 || tag4 || 14×00`. CCM M=4, nonce = 7 B challenge, empty AAD, **LibAES** block.

Phase 6 plaintext 56 B: `R2_16 || R1_16 || kEnc16 || ivEnc8`. Verify R2/R1 echoes.

### 3.4 CCCD after Phase 6 (LibreLoop seven, not LibreCRKit five)

Force **off→on** in this order:

1. patchControl  
2. eventLog  
3. factoryData  
4. glucoseData  
5. patchStatus  
6. historicData  
7. clinicalData  

v1 still arms 6–7 so later backfill is possible. v1 does **not** write `patchControl`.

### 3.5 Glucose 29 B plaintext (`RealtimeGlucoseReading.swift`)

| Off | Field |
|-----|--------|
| 0 | lifeCount minutes |
| 2 | packed current |
| 4 | ROC hundredths mg/dL/min (`Int16.min` = missing) |
| 14 | trend bits 0–2; bit 3 actionable |
| 15 | uncapped current mg/dL |

Packed: bits 0–12 glucose; 13–14 condition 0 OK / 1 invalid / 2 ESA; bit 15 non-displayable.

Warmup default **60 min**. Wear from NFC. Sample time = `activatedAt + lifeCount * 60s`.

Insert gap: **240 s** (and new `lifeCount`).

### 3.6 Persist

Must survive process death: serial, BLE MAC (display reversed), PIN 4 B, receiverID 4 B, `phase5RawKey` 16 B, `kEnc` 16 B, `ivEnc` 8 B (refresh kEnc/ivEnc every handshake), last accepted `lifeCount`, wear minutes, warmup minutes, `generation`, `activatedAt`.

LibreLoop keychain v3 (document; encoding may adapt): `0x03 || kEnc16 || ivEnc8 || phase5RawKey16 || receiverID_LE4`.

Persist PIN **immediately after NFC**, before BLE.

### 3.7 Files P must open

LibreCRKit: `LibreSensorGATT.swift`, `NFCActivationCommand.swift`, `NFCActivationModels.swift`, `Libre3ReceiverID.swift`, `PairingFlow.swift`, `PhoneCert.swift`, `SessionKey.swift`, `ChallengeMessage.swift`, `LibAES.swift`, `AESCCM.swift`, `DataPlaneCrypto.swift`, `DataPlaneDecoder.swift`, `Phase5KeySchedule.swift`, `FirstPairSourceSlice.swift` (or the clone’s native-eph owner), RuntimeTables, `RealtimeGlucoseReading.swift`, `PatchStatus.swift`, `PatchControlCommand.swift`, `SensorLifecycle.swift`, `Tests/LibreCRKitTests/NFCActivationCommandTests.swift`.

LibreLoop: `LibreLoopPairingService.swift` (`0x01` ban), `LibreLoopSensorMonitor.swift` (CCCD set), `LibreLoopKeychain.swift`, `LibreLoopCGMManagerState.swift` (gen 0/1). Ignore stale reconnect **docstrings** that mention full-handshake fallback; follow the live `if phase5RawKey` branch.

---

## 4. Gantt (sketch only)

```text
A1 → A2 → A3 → A4 → A5 → A6 → A7 → A8 → A9 → A10 → A12 → A11 (stop)
```

### 4.1 Locked C0 sequence (no parallel, no numeric skip)

**A1 → A2 → A3 → A4 → A5 → A6 → A7 → A8 → A9 → A10 → A12 → A11 (stop).**

- Do not run A4 and A5 in parallel.
- A6.policy tests are inside lot A6, not a separate lot.
- A10 is a real lot here. Also run the A10 compile/test commands at the end of A2, A3, A5, A6, A7, A8, A12 as those lots say.
- A11 is last. Do not start A11 before A12 DoD is green.

---

## 5. AAPS coding rules (agent R)

From `CLAUDE.md` and `.claude/agents/code-reviewer.md`. Fail the lot if broken.

- Explicit `import` lines. No inline FQNs.
- Simple school English in names, KDoc, comments, UI strings.
- Compose UI: `stringResource()`, not `ResourceHelper`. **Carve-out:** `PluginDescription.composeContent { BgSourceComposeContent(title = rh.gs(R.string.libre3_native)) }` is **not** `@Composable` — follow One+ and use `rh.gs` there. Do **not** call `stringResource()` in the plugin constructor.
- Theme spacing/colors, no hardcoded dp/colors, no Android attrs in Compose.
- `clearFocusOnTap` if a text field exists.
- No user-facing string concat. Format resources with `%1$s`. `comment=` only when placeholders need it.
- English strings only (`values/strings.xml`). Ignore translations.
- KDoc: resolvable `[links]` or backticks. Never `@Suppress("KDocUnresolvedReference")`.
- Plugin: `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. In `onStop`, **cancel jobs** like One+ (do not require cancelling the whole scope).
- `onGlucose` must not block. `insertCgmSourceData` only inside `ioScope.launch`.
- No side effects inside `StateFlow.update { }`.
- Prefer `val`. Prefer specific types over `Any?`.
- Card Compose: `CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)`.
- Previews: `MaterialTheme`, not `AapsTheme`.
- Screen composables `internal`, content `private`, `modifier` first optional param.
- No new ViewModel / `@ViewModelKey` unless a lot lists it. One+ Start does **not** use a ViewModel — do not add one.
- Domain: no `@ColorInt`.
- Tests: if constructors change, update **all** test source sets.
- Do not add LocalSnackbarHostState consumers.
- ⚠️ **ASYNC IMPACT:** BLE binder → dedicated `bleExecutor`. NFC `transceive` on IO, not main, not BLE executor. Never share GATT with another CGM plugin.

---

## 6. Lots

### A1 — Licence

**Entry:** this file §2 legal bans.  
**Files:** `plugins/libre3/NOTICE` (create dir), `docs/LIBRE3_NATIVE_LICENCE_MEMO.md`.  
**Work:** confirm both repos MIT; NOTICE = MIT lines + “protocol port, no Abbott binary, no Juggluco copy”; one-page memo.  
**DoD:** NOTICE + memo exist.  
**P:** skip. **R:** skip.

---

### A2 — Gradle module skeleton

**Entry:** A1 done. Clone `plugins/dexcom_oneplus/build.gradle.kts` shape (no libkeks).  
**Files:**

- `settings.gradle` — **append** `include ':plugins:libre3'` next to dexcom_oneplus. Do not remove eversense / dexcom_oneplus / libkeks.
- `plugins/libre3/build.gradle.kts` — namespace `app.aaps.plugins.libre3`, `implementation(project(":core:interfaces"))`, `api(libs.androidx.core)`.
- `plugins/libre3/src/main/AndroidManifest.xml` — BLE perms like One+ **plus** `NFC`. NFC feature `required=false`.
- Façade Stub only: `Libre3CgmDriver.kt`, `Libre3CgmDriverStub.kt`, `Libre3CgmDrivers.kt`, `Libre3GlucoseSample.kt`, `Libre3GlucoseWatcher.kt`, `Libre3WarmupState.kt`, `Libre3LogMarkers.kt`.
- Seed `docs/MERGE_CONSTRAINT_LIBRE3.md` from `docs/MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md` locked table (§1).

**Compile:** `./gradlew :plugins:libre3:compileFullDebugKotlin --no-daemon > /tmp/libre3-a2.log 2>&1`  
**DoD:** log shows BUILD SUCCESSFUL. Stub is the only driver.  
**P:** skip. **R:** required.

---

### A3 — Enums, DI, Stub plugin, engineering gate

**Entry:** A2 module compiles. Clone One+ **DI + marker + plugin shell**, not staging / WarmupBasalGuard.

**Files:**

- `core/data/src/main/kotlin/app/aaps/core/data/model/SourceSensor.kt`
- `core/data/src/main/kotlin/app/aaps/core/data/model/SourceSensorExtensions.kt`
- `core/data/src/test/kotlin/app/aaps/core/data/model/SourceSensorExtensionsTest.kt` (add `LIBRE_3_NATIVE` next to Libre 3; do not remove `LIBRE_2`)
- `core/data/src/main/kotlin/app/aaps/core/data/ue/Sources.kt`
- `database/impl/src/main/kotlin/app/aaps/database/entities/GlucoseValue.kt`
- `database/persistence/src/main/kotlin/app/aaps/database/persistence/converters/SourceSensorExtension.kt` (both directions)
- `database/persistence/src/test/.../SourceSensorExtensionTest.kt` (entries loop — compile is the gate)
- `plugins/source/build.gradle.kts` — **pre-approved** `implementation(project(":plugins:libre3"))`. Keep eversense and dexcom_oneplus.
- `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3NativePlugin.kt` — `AbstractBgSourcePlugin` + `PluginType.BGSOURCE` + `BgSource` + `Libre3GlucoseWatcher` + `CgmSensorStatusProvider`. Stub driver. `ioScope`. `specialShowInListCondition` = marker. **Do not** gate `specialEnableCondition`. Staging flows ABSENT. `getPreferenceScreenContent` + `IntentKey.withActivity` for Status / Start / Warmup (clone One+ prefs shape).
- `plugins/source/src/main/kotlin/app/aaps/plugins/source/Libre3Availability.kt` — clone `DexcomOnePlusAvailability.kt` including provider + SAF-lost notification. Literal `engineering_libre3` only here. Use **new** `NotificationId.LIBRE3_DIR_ACCESS_LOST`.
- `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/notifications/NotificationId.kt` — **append last** `LIBRE3_DIR_ACCESS_LOST`.
- `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt` — `handleNotificationAction`: same picker branch as One+ for the new id.
- `Libre3Ingest.kt` — empty gates + `mapToGv` stub (filled in A7).
- `keys/Libre3IntentKey.kt`, `keys/Libre3BooleanKey.kt` (`UseRealSkeleton` default **false**).
- `di/SourcePluginsListModule.kt` — `@IntKey(447)` after 446.
- `di/SourceModule.kt` — three `@ContributesAndroidInjector`.
- `plugins/source/src/main/AndroidManifest.xml` — three `exported=false` activities (empty `AppCompatActivity` stubs so A3 compiles; A8 fills them).
- `plugins/source/src/main/res/values/strings.xml` — EN only, names from §1.
- `plugins/source/src/test/.../Libre3NativePluginVisibilityTest.kt` — clone One+ visibility test **without** WarmupBasalGuard.
- `plugins/source/src/test/.../Libre3AvailabilityProviderTest.kt` — clone One+ availability test.

**Compile:** `:plugins:source:compileFullDebugKotlin` and `:database:persistence:testFullDebugUnitTest` redirected.  
**DoD:** plugin hidden without marker; listed with marker (unit test). IntKey 447 unique. 400/445/446 untouched. `NotificationId` appended last.  
**P:** skip. **R:** required.  
**⚠️ ASYNC:** `ioScope` only; no BLE yet.

---

### A4 — NFC

**Entry:** §3.1. One+ has no NFC. **Do clone** store structure from `plugins/dexcom_oneplus/.../identity/OnePlusSensorStore.kt` → `Libre3SensorStore.kt`. Do not clone GS1 parser.

**Files:** `nfc/Libre3NfcUids.kt`, `Libre3NfcCommands.kt`, `Libre3NfcSession.kt`, `Libre3NfcReader.kt`, `identity/Libre3SensorStore.kt`, `Libre3SensorIdentity.kt`.

**Work:** reject manufacturer != `0x7A`; A1 parse; A0 vs A8; CRC + RID unit tests with **test** vectors; persist PIN+MAC+serial+receiverID **and wait**; then “ready for BLE”.  
⚠️ **ASYNC IMPACT:** `transceive` on IO. Reader only while Start is resumed. `disableReaderMode` on pause.

**DoD:** unit tests for CRC vector, RID vector, 0x7A reject, A0 vs A8. Fake transceive. No `NfcAdapter` in unit tests.  
**P:** required. **R:** required.

---

### A5 — Crypto

**Entry:** A4 store exists. LibreCRKit crypto files in §3.7.

**Work:**

- Port LibAES tables only from MIT RuntimeTables. If missing: hard stop.
- Embed phone cert bytes from `PhoneCert.swift` / `phone_cert_162b.bin` (162 B, prefix `03 03`). Do not generate.
- Data-plane AES-CCM-128 (standard).
- Handshake CCM with LibAES block.
- ECDH: Android JCE `KeyPairGenerator.getInstance("EC")` first. If that fails, **ask** before adding BouncyCastle.
- First-pair: port `makeFirstPairNativeEphemeral`. Random JCE P-256 must **not** be used on the first-pair path (negative test).

**DoD:** CCM data-plane round-trip; Phase 5/6 sizes; `03 03` accept / `03 00` reject; first-pair path does not use random P-256.  
**P:** required. **R:** required.

---

### A6 — BLE session

**Entry:** A4 persist-before-BLE + A5 vectors green. Clone One+ `gatt/` `scan/` `session/` `reconnect/` **structure**, not opcodes.

**Files:** `scan/Libre3BleScanner.kt`, `Libre3BleScannerAndroid.kt`, `Libre3ScanResult.kt`, `gatt/Libre3BluetoothUuids.kt`, `Libre3GattClient.kt`, `Libre3GattClientAndroid.kt`, `session/Libre3BleSession.kt`, `Libre3SessionAuth.kt`, `Libre3SessionStartPolicy.kt`, `Libre3DisconnectPolicy.kt`, `Libre3EgvSession.kt`, `reconnect/Libre3ReconnectPolicy.kt`, `Libre3CgmDriverReal.kt`.  
`Libre3CgmDrivers.default()` reads `UseRealSkeleton` (false → Stub). No OEM registry. No `staging()` driver.

**Work:**

- One `bleExecutor`. One outstanding GATT write.
- Pure policy tests:
  - `phase5RawKey` present → `0x11` path, **not** `0x01`
  - disconnect / `shutdown()` → **no** `patchControl` write
- First pair only when store says unpaired (full §3.3 clock).
- After Phase 6: CCCD seven chars off→on.
- Connect only if store has PIN and the persist write finished.
- Real driver compiled, **not** default.

⚠️ **ASYNC IMPACT:** binder → executor. Watchers may run on executor. Plugin `onGlucose` still must not block.

**DoD:** policy tests green without Bluetooth. Stub remains default. Grep: no `0x05` sender.  
**P:** required. **R:** required.

---

### A7 — Parse + ingest

**Entry:** `RealtimeGlucoseReading.swift`, `PatchStatus.swift`, One+ `DexcomOnePlusIngest`.

**Files:** `parse/Libre3GlucoseParser.kt`, `Libre3PatchStatusParser.kt`, `plugins/source/Libre3Ingest.kt`, `Libre3WarmupMapper.kt`, `warmup/Libre3WarmupClock.kt`.  
Do **not** ingest historic/clinical into `GV` in v1.

**Work:** 29 B → sample; usable gate §2.13; `WARMING` blocks ingest; dedup 240 s + lifeCount high-water; `mapToGv` → `LIBRE_3_NATIVE` / `Sources.Libre3Native`; insert on `ioScope` only; warmup remaining = `warmupMinutes - lifeCount`; `hasSensorError()` on insertionFailure / expired / terminated / unknown `errorData`.

**DoD:** parser tests; ingest tests: WARMING blocks; PAIRING/IDLE do not; native tag; no insert of non-displayable.  
**P:** required. **R:** required.

---

### A8 — UI

**Entry:** A7 ingest exists. Clone One+ **activity shape** (Hilt `AppCompatActivity` + `setContent` + `MaterialTheme` preview). Do not copy GS1, 4-digit pairing code, `SensorSlot`, staging buttons.

**Files:**

- `activities/Libre3StartActivity.kt`, `Libre3StatusActivity.kt`, `Libre3WarmupActivity.kt`
- `compose/Libre3UiLabels.kt`, `Libre3WarmupCountdown.kt`
- `Libre3WarmupNotification.kt` (clone One+ warmup notif)
- `Libre3BlePermissionHelper.kt` (clone One+ helper; `REQUEST_CODE 44701`; BLE only)
- `Libre3WarmupMapper.kt` if not already from A7
- EN strings in `values/strings.xml`
- tests: `Libre3WarmupMapperTest.kt`, `compose/Libre3WarmupCountdownTest.kt`

**Work:** Start = NFC reader while resumed (A4) + BLE connect **after** persist. No typed PIN. Status = serial, generation 3 vs 3 Plus, MAC, last BG, reconnect, warmup. Warmup countdown. Conflict copy: stop Abbott / Juggluco (one receiver).  
**P:** required if this lot wires `NfcAdapter`; else skip. **R:** required.  
**DoD:** activities open on Stub (compile). No protocol changes.

---

### A9 — AIMI (tiny)

**Entry:** grep `plugins/aps` for `DEXCOM_ONEPLUS_NATIVE` and `SENSOR_AWARE`. Do not trust a line number.

**Files:**

- `DetermineBasalAIMI2.kt` — one `else if` log next to the One+ `SENSOR_AWARE` branch: Libre 3 native, fast sensor, **no G6 lead**. `SourceSensor` is already imported — use `SourceSensor.LIBRE_3_NATIVE` (no FQN).
- `ContinuousStateEstimatorG6LeadTest.kt` — add a `LIBRE_3_NATIVE` case cloned from the One+ test (must **not** engage G6 lead).
- `GlucoseStatusCalculatorAimiTest.kt` — add a native Libre 3 propagate test cloned from the One+ test.

If a grep hit is not that log / those tests, do not touch. Do not change UKF / T3C / Harmonia.

**DoD:** aps unit tests still compile; production change is the log `else if` only.  
**P:** skip. **R:** required.  
⚠️ **ASYNC:** none if only a sync `sourceSensor` compare.

---

### A10 — Tests and compile gates

**Entry:** A9 done. No new files.  
**Commands:**

```text
./gradlew :plugins:libre3:testFullDebugUnitTest --no-daemon > /tmp/libre3-unit.log 2>&1
./gradlew :plugins:source:testFullDebugUnitTest --no-daemon > /tmp/libre3-source-unit.log 2>&1
./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon > /tmp/libre3-aps-unit.log 2>&1
```

**DoD:** each log has BUILD SUCCESSFUL; no `^e: `; no `connectedAndroidTest`.  
**P:** skip. **R:** required on test style.

---

### A12 — Integration captain

**Entry:** A10 green. **Before A11.** Wire only.

**Work:** confirm `settings.gradle` include; source project dep; `@IntKey(447)`; `UseRealSkeleton` default false; NOTICE present; grep `plugins/libre3` for no `FDE3`/`F001`/`F002`; grep shutdown/disconnect for no `0x05` / no `patchControl` write.

**DoD:** `:plugins:libre3` + `:plugins:source` unit tests green. Do not install.  
**P:** skip unless handshake/shutdown files changed. **R:** required.

---

### A11 — Docs freeze (last)

**Entry:** A12 DoD green. Locked table §1.

**Files:**

- `docs/MERGE_CONSTRAINT_LIBRE3.md` — freeze (clone One+ sections: locked ids, must keep, do not regress, conflict rules, post-merge smoke). Status: not user-confirmed.
- `docs/NON_REGRESSION_CHECKLIST.md` — subsection after Dexcom ONE+ named “Fork merge constraint: Libre 3 native CGM”; add this merge doc to the grep list that already has Eversense and One+; add a PR-gate checkbox after the Dexcom ONE+ line.
- `docs/LIBRE3_NATIVE_USER_GUIDE.md` — NFC once, no Abbott in parallel, warmup 60 min, 3 vs 3 Plus, Juggluco is still the production path until the user confirms native. **Do not** say native is working.
- this playbook §11 lot log (not “working”).

**DoD:** merge constraint matches §1; checklist pointer exists; user guide does not say native is confirmed.  
**P:** skip. **R:** skip (docs). Then STOP and report to the user.

---

## 7. File tree

Driver (A2–A7): `plugins/libre3/` as in lots (NOTICE, gradle, manifest, façade, nfc, crypto, scan, gatt, session, parse, identity, reconnect, warmup, tests).

Source / core / app (A3–A8): plugin, availability, ingest, keys, di, strings.xml, NotificationId, ComposeMainActivity branch, three activities (stubs in A3, filled in A8), compose labels, warmup notif, BLE permission helper, visibility + availability tests.

Do not create a `libre2` package.

---

## 8. Prompts

### P

```text
You are agent P for docs/LIBRE3_NATIVE_AGENT_PLAN.md.
Review ONLY files changed in lot [ID] against /tmp/LibreCRKit and /tmp/LibreLoop.
Libre 2 / 2+ is out of scope. protocol.md offsets are not truth if they disagree with Swift parsers.
Check: UUIDs, NFC A1/A0/A8, CRC vector, wrapping RID, first-pair clock in §3.3 (including 0x02–0x0E waits and 23 B R1), 0x01 forbidden on cached reconnect, no patchControl in v1, CCCD seven-char LibreLoop set, crypto plane split, persist PIN before BLE, usable glucose rules, MIT cert 03 03, native ephemeral.
Return: PASS or FAIL with file:line must-fix. Do not edit AIMI unless C0 said this is a rework lot.
```

### R

```text
You are agent R for docs/LIBRE3_NATIVE_AGENT_PLAN.md.
Review ONLY recently changed AIMI files against CLAUDE.md and .claude/agents/code-reviewer.md.
Fail on: inline FQNs, ResourceHelper in @Composable (PluginDescription rh.gs is allowed), hardcoded dp/colors, string concat UI, translation edits, new project() deps beyond the locked :plugins:libre3 wiring, blocking onGlucose, insertCgmSourceData off IO, 0x01/0x05 policy missing tests, KDoc unresolved links, AapsTheme in Preview, NotificationId inserted mid-enum, UseRealSkeleton default true, staging/WarmupBasalGuard copies, deleting LIBRE_2 enums.
Return Critical / Important / Suggestions. Do not claim working.
```

### A1 start

```text
Lot A1 from docs/LIBRE3_NATIVE_AGENT_PLAN.md. MIT NOTICE + licence memo only. No Kotlin. Then auto-prompt §0.2.
```

### A6 start

```text
Lot A6 from docs/LIBRE3_NATIVE_AGENT_PLAN.md §A6.
Clone One+ gatt/session/reconnect structure. Implement the full §3.3 first-pair clock and cached 0x11 path.
Libre3SessionStartPolicy + Libre3DisconnectPolicy as pure functions first.
Never 0x01 if phase5RawKey exists. No patchControl writes in v1. Stub stays default.
No UI. No AIMI. No install. School English. Explicit imports.
```

---

## 9. Global Definition of Done (code complete, not “working”)

Claude can check:

- [ ] A1–A12 DoD (sequence §4.1; A11 last)
- [ ] P PASS on A4, A5, A6, A7
- [ ] R must-fix empty on every code lot
- [ ] Grep `plugins/libre3`: no `FDE3`, no `F001`, no `0x05` write
- [ ] Existing `LIBRE_2` / `LIBRE_2_NATIVE` / `LIBRE_3` still present
- [ ] `@IntKey(447)` unique; One+ 446 and xDrip 400 untouched
- [ ] Marker hides plugin; Stub default false
- [ ] Unit tests green (redirected logs) for libre3, source, aps
- [ ] `MERGE_CONSTRAINT_LIBRE3.md` matches §1
- [ ] User guide does not say native is confirmed

**Still required from the user (not Claude):** real Libre 3 or 3 Plus, NFC pair, BLE glucose, reconnect without `0x01`/`0x05`. Until that confirm, status stays **not user-confirmed**.

---

## 10. Current production path (do not break)

Until native is user-confirmed, Libre 3 in AIMI stays **Juggluco / xDrip → `XdripSourcePlugin` @400 → `SourceSensor.LIBRE_3`**. Native plugin is extra and hidden without `engineering_libre3`.

---

## 10.0 START HERE for the next session

**Where this stands on 2026-08-20.** Lots A1 to A4 and A6 to A12 are done and committed. Lot A5 is
two thirds done. One piece is missing, and it is the only thing between this driver and a real
sensor test.

### The one job left

Port `FirstPairSourceSlice` from LibreCRKit, the part a **first pairing** needs.

- Source: `/tmp/LibreCRKit/Sources/LibreCRKit/Crypto/FirstPairSourceSlice.swift` (15011 lines).
- **Only 79 members, about 2774 lines, are reachable** from the first pairing. The rest of the file
  is other branches. Reachable from these five entry points:
  - `builder633fa8NullScalarWindowFromEntropySource`
  - `builderProcess2P5PublicKey65FromEntropy`
  - `builder633fa8NullScalarWindowFromEntropy`
  - `builder633fa8StaticScalarWindowFromEntrySource`
  - `builder6388f0FirstPairStreamSeedsFromScalarsAndSensorPoints`
- Also needed: `Pairing/SessionKey.swift` (`makeFirstPairNativeEphemeral`, `deriveFirstPairPhase5Material`)
  and `Pairing/EphemeralExchange.swift`.
- **All 23 tables it needs are already committed** under `plugins/libre3/src/main/resources/libre3/`.
- **Vectors: 64 test functions** in `/tmp/LibreCRKit/Tests/LibreCRKitTests/FirstPairSourceSliceTests.swift`,
  one set per builder. This is what makes the port safe: do one builder, port its vectors, run them,
  move on. Do not write a large block and hope.

### The method that worked twice

`Libre3LibAes` and `Libre3Phase5KeySchedule` were both right the first time, by doing this:

1. Read the Swift for one unit only.
2. Write the Kotlin with `UInt` where the Swift uses `UInt32`, so the wrapping matches.
3. Port that unit's published vectors as a test **before** moving on.
4. Run. If it is green, the unit is done; nothing further can silently break it.

### When the port lands, wire these three things

1. `Libre3FirstPairEphemeral.make()` must return the real key pair, and `isAvailable` become true.
2. `Libre3BleSession.openOrFail`, the `FIRST_PAIR` branch: run `Libre3SessionAuth.runFirstPair`,
   then derive the source, then `Libre3Phase5KeySchedule.deriveRawKey`, and then **store the key
   with `Libre3SensorStore.savePhase5RawKeyAndWait`**. Without that last step a reconnect can never
   happen, even after a first pairing that worked.
3. `Libre3PhoneCert.bundled()` already returns the real certificate, and
   `Libre3PairingBlocks.factory()` already returns the real block maker. Nothing to do there.

### What is already proven, so do not re-do it

- The pairing block maker reproduces the live capture of 2026-05-06 exactly.
- The Phase 5 key schedule matches all six published vectors and produces the key that capture used.
- The NFC step matches every published byte vector.
- The command clock, the framing, the channel rules and the safety policies are pinned by tests.

### Traps already found and fixed. Do not reintroduce them

- A command must be written **raw**; framing it turns `0x11` into `00 00 11`.
- A command answer must be read **raw**; its first byte is the answer, not a counter.
- The data plane packet number comes from the **message**, never from a local counter.
- The sample time must use the **sensor's own** activation moment, never the scan moment, and no
  sample at all may be built while that moment is unknown.
- The repeat guard must be reset when a different sensor is scanned.
- Teardown must not be queued on the executor that the read loop owns.

### How to check your work at the end

Run the three suites, then re-run the two review agents from section 8 and the whole system
verification agent. The last full run was: `:plugins:libre3` 175 tests green, `:plugins:source`
Libre 3 tests green, `:plugins:aps` green, `:app` compiles. Note that `:plugins:source` also has 18
failures in `GlunovoPluginTest` and `IntelligoPluginTest` which are **pre-existing and unrelated**:
those plugins build an Android `Uri` in their constructor, which is null in a plain unit test.

Status stays **not user-confirmed** until the user runs it on a real sensor.

## 10.1 Final verification (agent, 2026-08-20)

A whole-system agent compared the driver with both reference projects end to end. Its verdict, kept
here in full because it is the honest state of this work:

**The driver cannot be tested on a real sensor yet.** Three pieces of the pairing are missing (the
LibAES block maker, the Phase 5 key schedule and the first pairing key pair), their table files are
not in the tree, and until they land no sensor can be paired and therefore no reconnect can happen
either.

Everything it found has been fixed except those three ports:

| What it found | What the user would have seen | State |
|---|---|---|
| The real driver was never reachable: `default()` always returned the stub and nothing called `connect` | The plugin appears, the NFC scan works, then nothing ever happens | Fixed. `default()` now returns the real driver when the switch is on **and** the files ship, the status screen has a connect control, and the reason is shown when it is blocked |
| A sample could be built on an unknown start time | Readings dated 1970, so the loop sees no recent glucose and quietly does nothing | Fixed. No sample is made at all while the start time is unknown |
| A wrong wear time from NFC ended the stream for good | Glucose stops dead mid-wear, in silence | Fixed. A sensor still sending good readings past its stored end is given more time, like the reference |
| A dropped link during channel opening was reported as success | The session says it is up on a dead link, then waits 90 s | Fixed |
| The link was leaked on every failed attempt | After a few retries nothing can connect until AAPS is force stopped | Fixed |
| The record kind was chosen by plain text length, not by channel | Another channel's record could be published as a glucose value | Fixed |
| The restart mark was written before the reading reached the database | A dropped reading could never be offered again | Fixed |
| The screen said ready before the reading passed its checks | A refused reading still showed a working sensor | Fixed |
| The owed-answer count could grow without bound | Every later step fails for good after two timeouts | Fixed, and the step timeout raised to the 15 s the reference settled on |
| The user guide claimed glucose stops on a sensor fault | A false safety claim | Fixed. The guide now says what really happens |

Left open on purpose: `notActionable` readings are inserted as ordinary readings, because §2.13 of
this plan says so. The reference marks them display only. Worth revisiting with the user.

Still to do before any real sensor test: the three ports above, the table files, and then storing
the pairing key on a successful first pairing.

## 11. Lot log (C0 fills)

| Lot | Date | DoD | P | R | Notes |
|-----|------|-----|---|---|-------|
| A1 | 2026-08-19 | PASS | skip | skip | Both upstreams confirmed MIT by reading LICENSE. `plugins/libre3/NOTICE` + `docs/LIBRE3_NATIVE_LICENCE_MEMO.md` written. Pins: LibreCRKit `a86b92f` (delta to `66920c6` is additive), LibreLoop `e4a4642`. LibAES tables + 162 B `03 03` cert present in the clone. |
| A2 | 2026-08-19 | PASS | skip | PASS | `:plugins:libre3` created: gradle + manifest (NFC `required=false`) + 7 stub façade files. `settings.gradle` include added next to dexcom_oneplus, nothing dropped. `MERGE_CONSTRAINT_LIBRE3.md` seeded. `compileFullDebugKotlin` BUILD SUCCESSFUL (`/tmp/libre3-a2.log`). R: no must-fix. |
| A3 | 2026-08-19 | PASS | skip | PASS | Enums (`LIBRE_3_NATIVE`, `Sources.Libre3Native`), DB entity + both converter directions, `NotificationId.LIBRE3_DIR_ACCESS_LOST` appended last, `ComposeMainActivity` picker branch, `@IntKey(447)`, stub plugin + availability gate + ingest frame + keys + 3 activity stubs + EN strings + 2 tests (21 green). Also needed: `UserEntry.Sources` + `SourcesExtension` + `UserEntryPresentationHelperImpl` rows (exhaustive `when`). `:plugins:source`, `:app` compile; persistence + core data tests green. R: no must-fix. |
| A4 | 2026-08-20 | PASS (after one rework) | PASS | PASS | NFC ISO 15693: `Libre3NfcUids`, `Libre3NfcCommands` (CRC, receiver id, frames, parsers), `Libre3NfcSession` (persist before BLE), `Libre3NfcReader` (own thread, reader only while resumed), `Libre3SensorStore` + identity. 30 tests green on the MIT vectors. **P first pass FAILED**: `activatedAt` was stamped `now` even on A8, which would put every sample of a sensor taken over mid-life into the future; the A8 answer already carries the sensor's own activation epoch and it was being thrown away. Fixed, plus wire time `now-1` read once, `check(written)` on the receiver id, `commit` on the life counter. R items also applied: `Libre3NfcReader.shutdown()`, typed `Libre3NfcFailure` instead of raw English to the screen, narrowed and logged catches. **Carry-forward:** A6 must gate its first connect on `readyForBle`; A7 must treat `activatedAtMs == 0` as unknown and derive it from the first `lifeCount`, and keep the LibreLoop self-heal when the anchor disagrees by more than 30 min. **Open with the user:** the app UUID sits in the app private settings file, not the Keystore (§1 row), and the PIN / `phase5RawKey` are Base64 in that same file. |
| A5 | 2026-08-20 | PART DONE, two of three crypto ports finished and vector proven | pending | pending | Green: `Libre3AesCcm` (RFC 3610), `Libre3DataPlaneCrypto`, `Libre3PairingMessages`, `Libre3PhoneCert` (real 162 B `03 03` blob now ships and parses), `Libre3EphemeralKeys`, `Libre3RuntimeTables`. **`Libre3LibAes`**: the pairing block maker, ported and right first time. The three published block vectors pass, and the live capture of 2026-05-06 is reproduced exactly, key, nonce, plain text and all 40 bytes. A test also proves it is not ordinary AES. **`Libre3Phase5KeySchedule`**: all six published vectors pass, and a joining test shows the two ports meet, the schedule making the very key that reproduces the live capture. 34 table files now ship, about 1.8 MB. **Still missing:** the first pairing ephemeral, 79 members and about 2774 lines of the 15011 line slicer, with 23 tables (all copied) and 64 sets of vectors that allow it to be ported and checked one builder at a time. Until it lands, a first pairing cannot run, so no key is stored and a reconnect has nothing to reuse. |
| A6 | 2026-08-20 | PASS for everything reachable | pending | PASS (after one rework) | `Libre3BluetoothUuids` (all §3.2 identifiers, the three pairing channels that must listen first, and the seven data channels in LibreLoop's order), the three pure policies, `Libre3SessionAuth` (the whole §3.3 command clock, played against a scripted sensor), `Libre3BleFraming`, `Libre3GattClientAndroid` (one operation at a time, indicate or notify chosen from what the channel offers, every waiter woken on a dropped link), `Libre3BleScannerAndroid`, `Libre3BleSession` (policy, then pairing channels, then handshake, then the seven data channels off and on) and `Libre3CgmDriverReal` (own executor, gated `toSampleOrNull` is the only path to a sample). 36 tests, no Bluetooth needed. Proven: a stored key can never lead to `0x01`; a refused reconnect stops; no reason lets a command reach the sensor's control channel. The first pairing branch returns a clear refusal until A5 is finished. **R FAILED first, three real defects:** the read loop held the driver's only executor for the whole session, so a `disconnect` or `shutdown` queued on that same executor could never run while a sensor was connected; a single latch let a late answer from a timed out step finish the next step with the old result; and a wait that started after the link had already gone sat for its whole timeout. All three fixed: teardown now runs on the caller's thread and drops the link to wake the loop, an owed-answer count swallows late answers, and a `linkDown` flag makes a new wait give up at once. |
| A7 | 2026-08-20 | PASS (after one rework) | PASS | PASS | Parsers and ingest: `Libre3GlucoseParser` (29 B), `Libre3PatchStatusParser` (12 B), `Libre3GlucoseFrameAssembler` (15+20 join), `Libre3WarmupClock`, `Libre3Ingest`, `Libre3WarmupMapper`. 151 tests green in the driver module, 40 in source. **R FAILED first**: `Libre3Ingest.seed`/`reset` had no production caller, so a sensor swap without an app restart would have refused every reading of the new sensor for its whole life, silently. **P FAILED first**: `isUsable` had no caller and no bridge, and `anchorLooksWrong` took a parameter named `sampleTimeMs` that, if fed that function's own output, made the repair a permanent no-op. Fixed: gated `toSampleOrNull` is now the only way a reading becomes a sample, `wearMinutes` has no default, `receivedAtMs` renamed with the trap pinned in a test, ingest seeded and reset and its high-water persisted, and a test now pins the value to byte 15 rather than the packed word. **Carry-forward:** A8 must call `Libre3NativePlugin.onSensorChanged()` on the NFC path. |
| A8 | 2026-08-20 | PASS | pending | PASS (after one rework) | Three screens: Start (NFC only, no typing, reader on only while the screen is up, calls `onSensorChanged`), Status (sensor, family, address, phase, session) and Warm-up (countdown from the sensor only, a dash when unknown). Plus `Libre3BlePermissionHelper` (code 44701, Bluetooth only), `Libre3UiLabels` (every driver value turned into a string resource, so no English from the driver can reach a screen), `Libre3WarmupCountdown` with 8 tests. EN strings only. **R FAILED first:** the NFC reader was turned on once at first drawing and off on pause, so the screen went silently dead after the user switched apps and came back. It now follows the screen lifecycle. Also fixed: `modifier` moved after the required parameters on all three screens, and the warm-up notification listed in the plan was missing and has been added. |
| A9 | 2026-08-20 | PASS | skip | PASS | One `else if` log line in `DetermineBasalAIMI2` next to the ONE+ branch: Libre 3 native is a fast sensor and takes **no** G6 lead. Two cloned tests: the G6 lead compensator does not engage for `LIBRE_3_NATIVE`, and the native tag survives to the dose engine. `:plugins:aps` tests green. No UKF, T3C or Harmonia change. |
| A10 | 2026-08-20 | PASS with one note | skip | pending | `:plugins:libre3` 151 tests green. `:plugins:aps` green. `:plugins:source` 339 tests, 18 failures, **all pre-existing and unrelated**: `GlunovoPluginTest` and `IntelligoPluginTest` fail in their own `setup()` because those plugins build an Android `Uri` in their constructor, which is null in a plain JVM test. Both files are untouched by this work (`git diff` empty). Every Libre 3 test is green. |
| A12 | 2026-08-20 | PASS | PASS | pending | Checked: `settings.gradle` include present, `:plugins:source` dependency present, `@IntKey(447)` unique, `UseRealSkeleton` default false, NOTICE present, no `FDE3`/`F001`/`F002` anywhere in the module, no patch control write outside the identifier list, no `0x05`, `LIBRE_2` / `LIBRE_2_NATIVE` / `LIBRE_3` all still present. `:app:compileFullDebugKotlin` green. |
| A11 | 2026-08-20 | PASS | skip | skip | `MERGE_CONSTRAINT_LIBRE3.md` frozen, `NON_REGRESSION_CHECKLIST.md` wired (new subsection, grep list, PR gate line, release sentence), `LIBRE3_NATIVE_USER_GUIDE.md` written and it says plainly that the driver is not confirmed. |

---

## 12. First message Claude should run

```text
Read docs/LIBRE3_NATIVE_AGENT_PLAN.md in full. It is the only mission file.
You are C0. Scope is Libre 3 and Libre 3 Plus only. Existing LIBRE_2 / LIBRE_3 follower code stays.
Locked IDs are §1. Do not invent names, IntKeys, NotificationId ordinals, certs, or receiverIDs.
Clone LibreCRKit and LibreLoop to /tmp if missing.
Clone One+ files only when a lot lists them. Do not clone staging, GS1, WarmupBasalGuard, or libkeks.
Follow §4.1 sequence: A1 → A2 → A3 → A4 → A5 → A6 → A7 → A8 → A9 → A10 → A12 → A11.
After each lot: run P/R as §0.1, fill §11, then send yourself §0.2.
Stop on hard ban, same compile error twice, missing MIT vector, or when A11 and §9 (Claude boxes) are done.
Do not install. Do not connectedAndroidTest. Do not commit unless I asked.
Do not mark working. School English. Explicit imports.
Start lot A1 now.
```
