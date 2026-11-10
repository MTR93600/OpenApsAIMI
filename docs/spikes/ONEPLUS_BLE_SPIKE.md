# Spike BLE Dexcom ONE+ — go / no-go (agent A3)

**Status:** package ready — **awaits physical device proof** (no BLE success claimed)  
**Branch:** `feature/dexcom-oneplus-native`  
**Date:** 2026-07-18  
**Owner:** A3 → verdict to **A0**  
**Depends on:** Q5 (protocol source) + A1 preliminary OK  
**Does not merge into production protocol:** A6 owns GATT / J-PAKE / session after GO  

Related:

- Product: [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](../DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md) (Q9, F2/F2b, Phase 0.4)
- Plan: [DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md](../DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md) §A3
- Stub today: `OnePlusCgmDriverStub` (emits `FAILED` — **not** a BLE stack)

---

## 0. What this spike proves (and what it does not)

| Proves | Does **not** prove |
|--------|---------------------|
| Pairing code → BLE session on **one** Q9 phone | Production reconnect / 24 h reliability |
| Warm-up remaining clock exposed (or estimable) | Full A8 UI polish |
| ≥ 1 valid BG notify parsed into AAPS-visible log | AIMI loop quality, backfill, OEM matrix |
| Licence / OEM / master-BLE risk notes for A0 | Merge-ready `:plugins:dexcom_oneplus` protocol |

**Hard rule:** do not mark BLE “working” in docs or commit messages until the user pastes logcat meeting §4 success criteria.

---

## 1. Prerequisites

### Hardware / apps

| Item | Requirement |
|------|-------------|
| Phone | **One** of Q9: Pixel 6/7/8 **or** Samsung S22–S24 |
| Sensor | Dexcom **ONE+** with unused or known pairing code |
| BLE ownership | Sensor must **not** stay connected as master to Dexcom app / xDrip Direct during the spike (single GATT client) |
| AAPS build | Debug APK of this branch (or disposable `spike/oneplus-ble`) — **operator installs**; agents do not auto-install |
| Reference (optional) | xDrip Direct on a **second** phone only for side-by-side sanity — never two masters on same sensor |

### Software / permissions (phone)

1. Android Bluetooth + Nearby / Location as required by OS for BLE scan.
2. Battery: unrestricted for the spike APK; disable adaptive battery for the test window.
3. Samsung: put spike app in “Never sleeping apps”; disable “Put unused apps to sleep” for the session.
4. Airplane mode **off**; Wi‑Fi optional; Bluetooth **on**.
5. Note wall-clock at session start (for warm-up ≈30 min check).

### Operator checklist before logcat

- [ ] Phone model + Android version written down  
- [ ] Pairing code available (do **not** paste full code into shared logs — redact to last 2 digits)  
- [ ] xDrip / Dexcom Follow / Dexcom app **force-stopped** or uninstalled for this phone  
- [ ] `adb` works: `adb devices` shows the phone  

---

## 2. Exact steps (Pixel / Samsung Q9)

### 2.1 Capture setup

```bash
adb logcat -c
adb logcat -v time '*:S' DEXCOM_ONEPLUS:V OnePlusSpike:V OnePlusCgm:V BluetoothGatt:I BluetoothLeScanner:I > /tmp/oneplus_ble_spike.log
```

Keep this running for the whole pair → warm-up → first BG window (expect **≥ 35 minutes** if warm-up is full).

In a second terminal (optional heartbeat):

```bash
adb shell date
```

### 2.2 Build / install (human only)

Agents must **not** install. Operator:

1. Build debug APK for the flavor used on that device (same as usual AAPS AIMI full/debug).
2. Install with data-preserving `-r` if already installed.
3. Grant BT / location / notifications as prompted.

### 2.3 Run spike path

**Preferred (until A6 ports protocol):** use a **throwaway spike entry** (see §3) that drives a minimal Direct session — **not** production `OnePlusCgmDriverStub.connect()` (that only emits stub `FAILED`).

If using a disposable spike APK / debug activity:

1. Open spike debug screen (or Config Builder → Dexcom ONE+ native → debug “Spike BLE” if wired — see §3).
2. Start scan → select ONE+ advertisement.
3. Enter pairing code → Connect / Start session.
4. Leave phone screen-on or with FGS notification visible; do not revoke BT permission.
5. Watch logcat for markers in §3.
6. Wait until warm-up remaining hits ~0 **or** phase `READY`, then confirm ≥1 BG line.
7. Stop logcat capture; archive `/tmp/oneplus_ble_spike.log` (redact pairing code).

**Stub-only smoke (negative control — expected fail):**

1. Call production stub `OnePlusCgmDriverStub.connect(addr, code)`.
2. Expect markers: `ONEPLUS_DRIVER_STUB` / phase `FAILED` / message *BLE session not ported yet (agent A6)*.
3. This proves wiring only; **does not** count toward GO.

### 2.4 After first BG (still on device)

1. Note timestamp of first BG vs session start (warm-up duration).
2. Disconnect cleanly; confirm no crash.
3. Optional: one reconnect attempt — document only (not required for GO).

---

## 3. Debug entry point (coord with stub — no A6 port here)

### Production today

| Type | Role |
|------|------|
| `OnePlusCgmDriver` | Façade A7/A8 will use |
| `OnePlusCgmDriverStub` | Singleton no-op: `connect()` → `WarmupState.Phase.FAILED` + `onError(...)` |
| `OnePlusWarmupState` | Phases: `IDLE`, `PAIRING`, `WARMING`, `READY`, `FAILED` + optional `remainingMs` |
| `OnePlusGlucoseWatcher` | `onWarmup` / `onGlucose` / `onSession` / `onError` |

Stub is correct until A6. **Do not** replace stub with half-ported GATT in production paths.

### Spike-only entry (description — A3 / disposable branch)

Keep any spike code **out of** production DI:

- Suggested location if code is needed later: `plugins/dexcom_oneplus/spike/` (e.g. `OnePlusBleSpikeHelper`) — **not** referenced from `DexcomOnePlusPlugin`, Source DI, or `OnePlusCgmDriverStub`.
- Spike helper responsibilities (minimal):
  1. Scan filter for ONE+ ADV (per Q5 source).
  2. Connect + pair with code.
  3. Log warm-up remaining when protocol exposes it.
  4. Log first glucose; optional forward to a throwaway watcher that only logs.
- Production continues to bind `OnePlusCgmDriverStub.instance` until A6 swaps the real driver behind the same interface.

**This document does not claim a spike helper is wired or that BLE works.**

---

## 4. Logcat markers to capture

Use a single tag family. Recommended filter:

```text
DEXCOM_ONEPLUS|OnePlusSpike|ONEPLUS_DRIVER_STUB
```

| Marker (log line contains) | When | Required for GO? |
|----------------------------|------|------------------|
| `DEXCOM_ONEPLUS_SPIKE_START` | Operator starts spike | Yes |
| `DEXCOM_ONEPLUS_SCAN_START` | Scan begun | Yes |
| `DEXCOM_ONEPLUS_ADV` | ONE+ advertisement seen (addr redacted OK) | Yes |
| `DEXCOM_ONEPLUS_PAIR_SUBMIT` | Code submitted (**redact code**) | Yes |
| `DEXCOM_ONEPLUS_GATT_CONNECTED` | GATT connected | Yes |
| `DEXCOM_ONEPLUS_AUTH_OK` | Session auth / J-PAKE success | Yes |
| `DEXCOM_ONEPLUS_SESSION_UP` | Session considered up | Yes |
| `DEXCOM_ONEPLUS_WARMUP` + `remainingMs=` or phase `WARMING` | Warm-up clock | Yes |
| `DEXCOM_ONEPLUS_WARMUP_DONE` or phase `READY` | Warm-up finished | Yes |
| `DEXCOM_ONEPLUS_BG` + value + timestamp | First / each BG | Yes (≥1) |
| `DEXCOM_ONEPLUS_SPIKE_FAIL` + taxonomy code | Any hard fail | On fail |
| `ONEPLUS_DRIVER_STUB` | Production stub path | Negative control only |

Example grep after capture:

```bash
grep -E 'DEXCOM_ONEPLUS_|ONEPLUS_DRIVER_STUB|OnePlusSpike' /tmp/oneplus_ble_spike.log
```

---

## 5. Success criteria (GO input to A0)

All of the following on **one** Q9 phone (Pixel **or** Samsung from the Q9 list):

1. **Pair code path:** scan → select sensor → submit pairing code → `AUTH_OK` / `SESSION_UP` without Dexcom app / xDrip as master on that phone.  
2. **Warm-up remaining:** at least one `WARMUP` log with `remainingMs` **or** clear `WARMING` → `READY` / `WARMUP_DONE` transition consistent with ~30 min factory warm-up (or shorter if sensor already warming — document start offset).  
3. **≥ 1 BG:** one `DEXCOM_ONEPLUS_BG` (or equivalent parsed glucose log) in range 20–600 mg/dL with plausible timestamp.  
4. **Artifact:** logcat file + phone model / Android version + start/end wall times + redacted notes.  
5. **No production claim:** spike may live on disposable branch; A6 still owns clean port.

If any of 1–3 fail → fill §6 taxonomy → A0 **NO-GO** or conditional retry (not silent “almost”).

---

## 6. Failure taxonomy

| Code | Class | Typical symptoms | Likely owner / next |
|------|-------|------------------|---------------------|
| `F_PERM` | Permissions / OS | No scan results; `SecurityException` | Fix perms; Samsung location for BLE |
| `F_OEM_SCAN` | OEM scan filter | ADV never seen on Samsung/Pixel only | A9 profile; retry other Q9 phone |
| `F_MASTER` | Master conflict | Connect then immediate drop; status 133/22 | Kill Dexcom/xDrip; power-cycle sensor window |
| `F_ADV` | Wrong filter / sensor family | Scan finds nothing or wrong device | Q5 ADV UUID/name; A6 map |
| `F_GATT` | Link layer | Connect timeout, 133 loops | Phone BT stack; distance; retry |
| `F_AUTH` | Pair / J-PAKE | Connected but auth fail | Wrong code; protocol mismatch Q5 |
| `F_LICENSE` | Cannot ship port | Spike works in private tree but A1 blocks | A0 + A1 — architectural NO-GO |
| `F_WARMUP` | No clock | Session up but no remaining / no READY | Protocol gap — A6 research before UI |
| `F_BG` | No glucose | Warm-up done / long wait, zero BG | Notify CCCD / parser — A6 |
| `F_PARSE` | Bad values | BG out of range / garbage | Parser endian / units |
| `F_STUB` | Wrong entry point | Only `ONEPLUS_DRIVER_STUB` | Operator used production stub — not a protocol fail |
| `F_CRASH` | Process death | Tombstone during spike | Capture bugreport; stop; file note |
| `F_UNKNOWN` | Other | — | Attach full log; A0 triage |

---

## 7. OEM / master-BLE / licence notes (for A0)

| Risk | Spike observation to record | Impact if bad |
|------|----------------------------|---------------|
| Samsung scan / battery | Did ADV appear within 60 s? Any kill after screen off? | May need A9 SamsungDefault before lab 24 h |
| Pixel BT | Any 133 storm? | Document; still may GO if BG achieved |
| Master BLE | Second app connected? | False NO-GO — retest alone |
| Licence (A1) | Which tree supplied minimal session? Commit hash? | GO technical ≠ GO legal |

---

## 8. GO / NO-GO template (paste to A0 / fiche §15)

Copy, fill, send to A0. Do **not** invent checkmarks without logcat.

```markdown
### A3 Spike BLE — verdict for A0

- Date:
- Operator:
- Branch / APK build id:
- Phone (Q9): Pixel __ / Samsung __  — model: __  — Android: __
- Sensor: ONE+ (lot/expiry if known): __
- Pairing code: [REDACTED — last 2 digits: __]
- xDrip/Dexcom master on same phone: yes / no (must be no)

#### Evidence
- Logcat path / attachment:
- `SPIKE_START` time:
- `ADV` seen: yes / no
- `AUTH_OK` / `SESSION_UP`: yes / no
- Warm-up: remainingMs samples (or WARMING→READY times):
- First `BG` time + value (mg/dL):
- Warm-up duration observed (min):

#### Taxonomy (if not full success)
- Primary code: F_*
- Secondary codes:
- Notes:

#### Recommendation
- [ ] **GO** — criteria §5 met; A6 may start production port on `:plugins:dexcom_oneplus`
- [ ] **NO-GO** — blocking code: F_* — reason in one sentence:
- [ ] **RETRY** — fixable operator/OEM issue; retest plan:

#### A1 / licence gate
- Source used for spike session code: __
- A1 preliminary OK: yes / no / unknown

#### Sign-off
- A3: __
- A0 decision + date: __
```

---

## 9. Hand-off rules

| After verdict | Action |
|---------------|--------|
| **GO** | A0 records in product journal; A6 ports cleanly; **do not** merge raw spike into `dev` |
| **NO-GO** | A0 documents; A2 Phase A continues; A4–A11 paused per plan §8 |
| **RETRY** | Same doc; new logcat; do not flip GO until §5 complete |

Stub remains `OnePlusCgmDriverStub` until A6 replaces the driver implementation behind `OnePlusCgmDriver`.

---

## 10. A3 session status (this agent)

| Item | State |
|------|--------|
| Spike doc package | **Delivered** (this file) |
| Physical ONE+ on Q9 | **Not run in agent session** |
| BLE working claim | **None** |
| A0 GO/NO-GO | **Blocked on user device proof** |
