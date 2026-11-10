# ONE+ BLE port map — xDrip Direct → `:plugins:dexcom_oneplus`

**Status:** scaffold map for agent **A6** — **not** a production BLE claim  
**Branch:** `feature/dexcom-oneplus-native`  
**Date:** 2026-07-18  
**Q5:** xDrip Direct (G7 / ONE+ family)  
**Depends on:** **A1** licence / provenance pin (repo URL + commit hash + NOTICE)  
**Blocked for real GATT:** **A3** device spike GO  

Related:

- Product: [DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md](../DEXCOM_ONEPLUS_NATIVE_PLUGIN_PRODUCT.md) (Q5/Q6/Q11/Q12)
- Plan: [DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md](../DEXCOM_ONEPLUS_NATIVE_AGENT_PLAN.md) §A6
- Spike runbook: [ONEPLUS_BLE_SPIKE.md](ONEPLUS_BLE_SPIKE.md)
- Verified pin file list: [ONEPLUS_XDRIP_PIN_INVENTORY.md](ONEPLUS_XDRIP_PIN_INVENTORY.md)
- Default runtime: `OnePlusCgmDriverStub` (usable) — `OnePlusCgmDriverReal` is a **failing skeleton** until A3 + port

---

## 0. Licence / A1 gate (do not skip)

| Item | Rule |
|------|------|
| Upstream | [NightscoutFoundation/xDrip](https://github.com/NightscoutFoundation/xDrip) — **GPL-3.0** |
| Contagion | Ported protocol code into AAPS AIMI fork **inherits GPL obligations** for that code (and likely the shipping APK if linked). **A1 owns** the legal memo + NOTICE draft. |
| Pin | **A1 landed 2026-07-18** — see [DEXCOM_ONEPLUS_LICENCE_MEMO.md](../DEXCOM_ONEPLUS_LICENCE_MEMO.md): NightscoutFoundation/xDrip tag `2026.07.15` / commit `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f`; NOTICE at `plugins/dexcom_oneplus/NOTICE`. Do not float on `master`. |
| Secrets | **Never** embed proprietary Dexcom app secrets, private keys, or obfuscated blobs from non-GPL trees. J-PAKE / session crypto must come from the **A1-approved GPL Direct path** (or clean-room rewrite approved by A0/A1). |
| This map | Class names below are the **expected** Direct G7/ONE+ surface in xDrip trees used by the community for Direct collection. **Verify against the A1-pinned commit** before copy-paste; paths drift across releases. |

**A6 may scaffold interfaces now; must not paste GPL sources until A1 pin + NOTICE path exist.**

---

## 1. Expected upstream packages / files (xDrip Direct G7 / ONE+)

xDrip treats G7 / ONE+ / Stelo as one Direct family (pairing code as transmitter ID). Typical Java packages under `app/src/main/java/` (confirm on pinned hash):

| Upstream area (expected) | Role in Direct path | Port? |
|--------------------------|---------------------|-------|
| `com.eveningoutpost.dexdrip.utilitymodels.DexCollectionType` | Collection type enum (`DexcomG7` / One+ routing) | Reference only — map to AAPS driver enable |
| `com.eveningoutpost.dexdrip.services.Ob1G5CollectionService` (+ Ob1 state machine siblings) | Long-lived BLE collector service / state machine used by modern Dex Direct | **Port logic**, not Android Service shell — AAPS uses plugin lifecycle + optional FGS via OEM profile |
| `com.eveningoutpost.dexdrip.g5model.*` (auth / challenge / glucose / control messages) | Packet models + parse for Dex family (G7 branches often live here or adjacent) | Port parsers + message types needed for ONE+ |
| Auth / J-PAKE helpers colocated with Ob1 / g5model (names vary by commit: look for `Auth`, `Challenge`, `Pair`, `JPake`, `KeyExchange`) | Pairing-code → session keys | Port behind `OnePlusSessionAuth` — **no hard-coded secrets** |
| BLE scan / advertise filter helpers used when collection type is G7/One+ (often next to Ob1 or `bluetooth` util packages) | ADV name / UUID / manufacturer filter (`DXCM**` style devices) | Port → `OnePlusBleScanner` |
| Glucose notify / backfill handlers in Ob1 / g5model path | CCCD subscribe, EGV parse, short history | Port → `OnePlusGlucoseParser` + `OnePlusBackfill` |
| Warm-up / sensor age / “not ready” status surfaces (Dex status page data sources) | Remaining warm-up clock (~30 min factory) | Port → `OnePlusWarmupClock` / `OnePlusWarmupState` |
| Reconnect / bond / backoff around Ob1 collector | Drop recovery | Port policy only → `OnePlusReconnectPolicy` (OEM knobs from A9) |

**Out of scope for A6 port (do not drag in):**

- xDrip UI, Nightscout upload, treatments, Companion-app collectors  
- Share / Follow cloud paths  
- G5/G6-only UI strings and calib flows unrelated to G7/ONE+ Direct  

**A1 deliverable cross-check:** replace this section’s “expected” table with an exact file list + hash once A1 lands.

---

## 2. Target Kotlin map (`app.aaps.plugins.dexcomoneplus`)

| A6 step | Target class / file | Upstream role |
|---------|---------------------|---------------|
| Façade | `OnePlusCgmDriver` | Stable API for A7/A8 (unchanged contract) |
| Default impl | `OnePlusCgmDriverStub` | Usable stub — **default** until Real passes A3 |
| Real skeleton | `OnePlusCgmDriverReal` | Wires session stack; **fails closed** until protocol filled |
| Scan | `scan/OnePlusBleScanner.kt` | ADV filter + scan start/stop |
| GATT | `gatt/OnePlusGattClient.kt` | Connect, discovery, notify routing |
| Session | `session/OnePlusBleSession.kt` | Session up/down, start with pairing code |
| Auth | `session/OnePlusSessionAuth.kt` | J-PAKE / key exchange **interface** (no secrets in repo) |
| Start | `session/OnePlusSessionStart.kt` | Pairing-code validation + start orchestration |
| Warm-up | `warmup/OnePlusWarmupClock.kt` + `OnePlusWarmupState` | `remainingMs` / `endsAtEpochMs` for ~30 min UI |
| Glucose | `parse/OnePlusGlucoseParser.kt` + `OnePlusGlucoseSample` | Notify parse + 20–600 bounds |
| Backfill | `session/OnePlusBackfill.kt` | Short history pull stub |
| Reconnect | `reconnect/OnePlusReconnectPolicy.kt` | Backoff using `oem/OemDeviceProfile` |
| Markers | `OnePlusLogMarkers.kt` | `DEXCOM_ONEPLUS_SESSION` / `WARMUP` / `BG` / `ERROR` |
| OEM | `oem/DeviceProfileRegistry.kt` | A9 — consumed by reconnect / connect timeouts |

Package layout:

```text
plugins/dexcom_oneplus/src/main/kotlin/app/aaps/plugins/dexcomoneplus/
  OnePlusCgmDriver.kt
  OnePlusCgmDriverStub.kt
  OnePlusCgmDriverReal.kt
  OnePlusCgmDrivers.kt
  OnePlusGlucoseWatcher.kt
  OnePlusGlucoseSample.kt
  OnePlusWarmupState.kt
  OnePlusLogMarkers.kt
  scan/OnePlusBleScanner.kt
  gatt/OnePlusGattClient.kt
  session/OnePlusBleSession.kt
  session/OnePlusSessionAuth.kt
  session/OnePlusSessionStart.kt
  session/OnePlusBackfill.kt
  parse/OnePlusGlucoseParser.kt
  warmup/OnePlusWarmupClock.kt
  reconnect/OnePlusReconnectPolicy.kt
  oem/...
```

---

## 3. Public API compatibility (A7 / A8)

Do **not** break:

| Type | Contract |
|------|----------|
| `OnePlusCgmDriver` | `setContext`, `add/removeWatcher`, `start/stopScan`, `connect(address, pairingCode)`, `disconnect`, `shutdown`, `warmupState()`, `isSessionUp()` |
| `OnePlusWarmupState` | `phase` ∈ `IDLE\|PAIRING\|WARMING\|READY\|FAILED`, `remainingMs`, `endsAtEpochMs`, `message` |
| `OnePlusGlucoseSample` | `mgdl`, `timestampMs`, `trendArrowRaw`, `sequence` |
| `OnePlusGlucoseWatcher` | `onWarmup`, `onGlucose`, `onSession`, `onError` |
| Default binding | `OnePlusCgmDriverStub.instance` remains what A7/A8 call today |

New session types are **internal to** `:plugins:dexcom_oneplus` until A3 GO and Real driver is selected behind the same façade.

---

## 4. ⚠️ ASYNC IMPACT — BLE callback thread model

Mirror the Eversense pattern (`bleExecutor` single thread):

```text
Android BLE binder thread
        │  BluetoothGattCallback / ScanCallback
        ▼
  OnePlusGattClient / OnePlusBleScanner
        │  hop immediately → bleExecutor (single thread)
        ▼
  OnePlusBleSession / SessionAuth / GlucoseParser
        │  may call OnePlusGlucoseWatcher.* from bleExecutor
        ▼
  A7 DexcomOnePlusPlugin (must not assume main thread)
        │  already uses ioScope for PersistenceLayer insert
        ▼
  DB / UI observers (main via AndroidX / Compose)
```

| Rule | Detail |
|------|--------|
| **Single bleExecutor** | All GATT sequential work (discover → auth → subscribe → parse) on one thread — avoids GATT race / 133 storms |
| **No AIMI dose work on BLE thread** | Watchers must not call APS / SMB / engine APIs synchronously |
| **Watcher thread** | `onWarmup` / `onGlucose` / `onSession` / `onError` may arrive **off the main thread** — A7/A8 must treat as async (A8 already polls `warmupState()` on UI; ingest uses background scope) |
| **Stub** | May invoke watchers on the calling thread (UI) — Real must document bleExecutor hop |
| **Shutdown** | `shutdown()` cancels executor work, closes GATT, clears watchers — called from plugin `onStop` |

---

## 5. Log markers (A6.11)

| Marker constant | When |
|-----------------|------|
| `DEXCOM_ONEPLUS_SESSION` | session up/down / connect orchestration |
| `DEXCOM_ONEPLUS_WARMUP` | phase + `remainingMs` |
| `DEXCOM_ONEPLUS_BG` | parsed glucose (rate-limit in Real later) |
| `DEXCOM_ONEPLUS_ERROR` | non-fatal / fatal driver errors |
| `DEXCOM_ONEPLUS_SCAN` | scan start/stop / ADV (when scanner live) |
| `DEXCOM_ONEPLUS_RECONNECT` | retry / backoff |

Stub also logs `ONEPLUS_DRIVER_STUB` on connect (negative control for A3).

---

## 6. What still needs A3 device GO

Before claiming production BLE:

1. Physical ONE+ on a Q9 phone per [ONEPLUS_BLE_SPIKE.md](ONEPLUS_BLE_SPIKE.md)  
2. A1 pin + NOTICE for any copied Direct sources  
3. Fill `OnePlusSessionAuth` / GATT / scanner **without** shipping stub `FAILED` on happy path  
4. Swap default from Stub → Real only after A0 records A3 GO  

**This document does not claim BLE works.**
