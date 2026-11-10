# Dexcom ONE+ — developer onboarding

**Audience:** engineers joining the native ONE+ work  
**Status:** experimental Real BLE behind eng pref — **not** device-confirmed as production  
**Do not claim “working”** without user logcat confirmation (`DEXCOM_ONEPLUS_BG: insert complete`).

---

## 1. Architecture (Eversense mirror)

| Layer | Module | Contents |
|-------|--------|----------|
| Low-level BLE / session / parse | `:plugins:dexcom_oneplus` | GATT, scan, KEKS glue, EGV, backfill, reconnect, OEM profiles |
| KEKS crypto (GPL pin) | `:plugins:libkeks` | Vendored jamorham.keks + IPluginDA |
| AAPS BgSource + UI + ingest | `:plugins:source` | `DexcomOnePlusPlugin`, Start/Status/Warmup activities, `DexcomOnePlusIngest`, prefs keys |
| Domain / DB | `core/data`, `database/*` | `SourceSensor.DEXCOM_ONEPLUS_NATIVE` → `"AAPS-DexcomOnePlus"` |

DI: `SourcePluginsListModule` `@IntKey(446)` (≠ BYODA 440, ≠ Eversense 445).

Rule: driver must **not** depend on `:plugins:aps` / AIMI.

---

## 2. Enable Real path (spike)

1. Engineering mode ON in AAPS.
2. Config Builder → BG Source → **Dexcom ONE+**.
3. Plugin prefs → **Use Real BLE skeleton**.
4. Stop Dexcom app + xDrip.
5. Start screen → follow on-screen steps (apply → code now → scan → Connect / follow).

Default remains **Stub** (`OnePlusCgmDrivers.useRealSkeleton = false`).

---

## 3. Build / unit tests

```bash
./gradlew :plugins:dexcom_oneplus:testFullDebugUnitTest --no-daemon
./gradlew :plugins:source:testFullDebugUnitTest --tests "*DexcomOnePlus*" --no-daemon
./gradlew :plugins:source:compileFullDebugKotlin --no-daemon
```

Logcat filter: `DEXCOM_ONEPLUS`.

---

## 4. Key files

- Session policy (no auto SessionStop): `OnePlusSessionStartPolicy.kt`
- Control loop: `OnePlusEgvSession.kt`
- Driver select: `OnePlusCgmDrivers.select()`
- Ingest / dedup: `DexcomOnePlusIngest.kt`
- Merge must-keep: [MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md](MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md)
- Changelog: [DEXCOM_ONEPLUS_CHANGELOG.md](DEXCOM_ONEPLUS_CHANGELOG.md)
- User steps: [DEXCOM_ONEPLUS_USER_GUIDE.md](DEXCOM_ONEPLUS_USER_GUIDE.md)

---

## 5. Commit discipline

Prefer small commits per milestone (scaffold → libkeks → GATT/KEKS → scan UI → EGV → attach-safe → docs/tests).  
Never mark docs or commits as “fully working” without device confirmation.
