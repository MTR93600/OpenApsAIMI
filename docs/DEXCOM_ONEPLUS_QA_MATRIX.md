# Dexcom ONE+ — QA matrix (Agent A11)

**Branch:** `feature/dexcom-oneplus-native`  
**Status:** lab checklist — do **not** mark “fully working” without user device confirmation  
**Related:** [ONEPLUS_BLE_SPIKE.md](spikes/ONEPLUS_BLE_SPIKE.md), [USER_GUIDE](DEXCOM_ONEPLUS_USER_GUIDE.md), [OEM checklist](DEXCOM_ONEPLUS_OEM_CHECKLIST.md)

---

## 1. Unit / CI (no device)

| Check | Command / location | Pass? |
|-------|-------------------|-------|
| Module compile | `./gradlew :plugins:dexcom_oneplus:compileFullDebugKotlin --no-daemon` | ⬜ |
| Module unit tests | `./gradlew :plugins:dexcom_oneplus:testFullDebugUnitTest --no-daemon` | ⬜ |
| Source compile (plugin + UX) | `./gradlew :plugins:source:compileFullDebugKotlin --no-daemon` | ⬜ |
| Ingest mapping | `DexcomOnePlusIngestTest` | ⬜ |
| Notif d1plus / dexcomone | `PackageConfigTest` + assets JSON | ⬜ |
| AIMI no G6 lead on One+ | `ContinuousStateEstimatorG6LeadTest` | ⬜ |
| Advanced filtering One+ | `SourceSensorExtensionsTest` | ⬜ |

---

## 2. Config Builder smoke (install by user)

| Check | Expected |
|-------|----------|
| Plugin listed | **Dexcom ONE+** short name ONE+ |
| `@IntKey(446)` | Enable/disable without crash |
| BYODA `@440` | Still selectable / functional |
| Eversense `@445` | Still selectable / functional |
| Prefs | Status / Start sensor / Warm-up entries open |

---

## 3. Phase A follower (no native BLE)

| Check | Expected |
|-------|----------|
| Official One+ app notifs | BG tagged `AAPS-DexcomOnePlus` / `DEXCOM_ONEPLUS_NATIVE` |
| AIMI log | `SENSOR_AWARE` One+ path; **no** G6 lead (+30% / +25%) |
| xDrip Direct interim | Documented in USER_GUIDE — exclusive of native when native is used |

---

## 4. Native BLE (blocked until A3 GO)

Phone matrix Q9 (fill after spike):

| Device | Pair code → warm-up remaining | ≥1 BG | 24 h uptime % | Airplane reconnect | Notes |
|--------|-------------------------------|-------|---------------|--------------------|-------|
| Pixel ___ | ⬜ | ⬜ | | ⬜ | |
| Samsung ___ | ⬜ | ⬜ | | ⬜ | |

Additional cases:

| Case | Pass? |
|------|-------|
| Disable plugin mid-session → GATT down | ⬜ |
| Start new sensor flow | ⬜ |
| Warm-up countdown ~30 min then first BG | ⬜ |
| OEM profile log `DEXCOM_ONEPLUS_OEM_PROFILE` | ⬜ |

---

## 5. Sign-off

| Role | Date | Result |
|------|------|--------|
| Dev scaffold | 2026-07-18 | Unit/compile path exercised on branch — **not** device-validated |
| User device | | ⬜ Pending — required before any “working” claim |
