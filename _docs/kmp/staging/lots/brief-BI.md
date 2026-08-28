# Lot BI — Android host WCycle adjuster + facade

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c367b09d2d` (Lot BH)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `WCycleEstimator` / prefs / types in commonMain, and androidMain `WCycleLearner` + `WCycleCsvLogger`.

**The cut:** dump `WCycleAdjuster` and `WCycleFacade` have no Android APIs themselves, but they call dest androidMain `WCycleLearner` / `WCycleCsvLogger` (`File`). Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** Health Connect stays dump. Tick / plugin stay parked. Dest facade is not a live tick host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `wcycle/WCycleAdjuster.kt` | androidMain same rel |
| `wcycle/WCycleFacade.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy Health Connect, tick, `OpenAPSAIMIPlugin`, Compose screens.

---

## Rewrite on copy

- `String.format("%.2f", x)` → `aimiFmt2(x)`.
- Keep same-package dest types (`WCycleInfo`, `WCycleDefaults`, `CyclePhase`).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BI.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BI.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
