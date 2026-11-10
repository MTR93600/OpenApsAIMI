# Dexcom ONE+ — Integration notes (Agent A13)

**Branch:** `feature/dexcom-oneplus-native`  
**Date:** 2026-07-18  
**Claim level:** integrated end-to-end — **not** device-validated as production BLE

---

## 1. What is integrated on the branch

| Layer | Status |
|-------|--------|
| Gradle `:plugins:dexcom_oneplus` + `:plugins:libkeks` | Included in `settings.gradle` |
| DI `@IntKey(446)` | Between BYODA `440` and Aidex `450`; Eversense stays `445` |
| Enums / DB / UE | `DEXCOM_ONEPLUS_NATIVE` / `AAPS-DexcomOnePlus` / `Sources.DexcomOnePlus` |
| Notif Phase A | `d1plus` + `dexcomone` → One+ tag |
| AIMI | `sourceSensor` wired; G6 lead **only** `DEXCOM_G6_NATIVE` |
| Ingest | `DexcomOnePlusIngest` → PersistenceLayer; skip `WARMING`; 4 min dedup |
| UX | Status / Start / Warm-up Compose + prefs + start steps |
| OEM | `DeviceProfileRegistry` used by Real GATT reconnect/MTU |
| Licence | Memo + NOTICE files — port OK conditional (xDrip pin) |
| BLE | **Stub default**; **Real** = GATT + KEKS + EGV/backfill via eng `UseRealSkeleton` |

---

## 2. IntKeys (must stay unique)

| Key | Plugin |
|-----|--------|
| 440 | BYODA `DexcomPlugin` |
| 445 | `EversensePlugin` |
| **446** | **`DexcomOnePlusPlugin`** |

---

## 3. Driver selection

```text
OnePlusCgmDrivers.select(false) → Stub (default)
OnePlusCgmDrivers.select(true)  → Real (eng pref UseRealSkeleton)
```

Plugin `syncDriverFromPrefs()` migrates watchers. Do not flip default to Real until device A3 GO.

---

## 4. ASYNC contract (A6 → A7)

- Real skeleton may call watchers on `bleExecutor` (not main).
- Ingest uses `Dispatchers.IO` (Eversense pattern).
- Do not run AIMI dose logic on watcher callbacks.

---

## 5. Next gates (human)

1. **A3** device spike — [ONEPLUS_BLE_SPIKE.md](spikes/ONEPLUS_BLE_SPIKE.md) → GO/NO-GO  
2. Port Direct subset from A1 pin into Real session (GATT / J-PAKE / EGV)  
3. Fill [DEXCOM_ONEPLUS_QA_MATRIX.md](DEXCOM_ONEPLUS_QA_MATRIX.md) Q9 24 h rows  
4. User confirms runtime before any “working” language in PLAN/PRODUCT  

---

## 6. Merge / docs

- [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)  
- [NON_REGRESSION_CHECKLIST.md](NON_REGRESSION_CHECKLIST.md) § Dexcom ONE+  
- [DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md)  

**Install:** user-driven only (no auto-install from agents).
