# Dexcom ONE+ — Phase A status

**Date:** 2026-07-19  
**Branch:** `feature/dexcom-oneplus-native`

## Today

| Path | Status |
|------|--------|
| Notification Reader (`com.dexcom.d1plus`, `com.dexcom.dexcomone`) | Tags BG as `AAPS-DexcomOnePlus` → `SourceSensor.DEXCOM_ONEPLUS_NATIVE` |
| xDrip Direct → AAPS xDrip source | Supported interim user path |
| Native BLE (`:plugins:dexcom_oneplus` / `DexcomOnePlusPlugin`) | **Stub default**; **Real** (GATT + KEKS + EGV + ingest) via eng pref `UseRealSkeleton` — **not device-confirmed** |
| BYODA `DexcomPlugin` | Unchanged (G6/G7 packs; not One+) |

## AIMI

- G6 lead / BYODA Δ compensation apply **only** when `sourceSensor == DEXCOM_G6_NATIVE`.
- One+ (`DEXCOM_ONEPLUS_NATIVE`) must **not** receive G6 lead; treated as a fast sensor (same class as G7).
- `GlucoseStatusCalculatorAimi` / plugin path propagate `sourceSensor` from the latest bucketed GV when known.

## See also

- [DEXCOM_ONEPLUS_DEV_ONBOARDING.md](DEXCOM_ONEPLUS_DEV_ONBOARDING.md)
- [DEXCOM_ONEPLUS_CHANGELOG.md](DEXCOM_ONEPLUS_CHANGELOG.md)
- [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)
