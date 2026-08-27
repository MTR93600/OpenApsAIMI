# AIMI KMP port ledger (Milos pattern)

Destination: `:plugins:aps` `commonMain` (`openAPSAIMI/`), types in `:core:interfaces` `commonMain`.
Same shape as OpenAPS SMB: algorithm in `commonMain`, Metro `@Inject`, no Hilt, no `android.util.Log`.

Freeze: `aimi-baseline-2026-08-26` (`dev_OAPSAIMI` `1ae418e106`). 441 main files.

Branch: `kmp-aimi-migration-study`  
HEAD (docs truth 2026-08-28): `45f91c9407`

- merge `903d725489` (`kmp` into this branch)
- AIMI API commit `9a9292513d`
- staging commit `45f91c9407`

AIMI does **not** run on iOS. `commonMain` compile is not a loop on a phone.

## Current truth after the kmp merge

| Item | State |
|---|---|
| OpenAPS SMB pattern | `OpenAPSSMBPlugin` + `DetermineBasalSMB` in `:plugins:aps` `commonMain`. Metro `@Inject` / `@SingleIn(AppScope)`. `TextResolver` + generated `ApsStrings`. `LTag` + `AAPSLogger`. |
| AIMI on this branch | **Present, not absent.** 119 kt in `commonMain`. Dump **not** in `androidMain`. |
| AIMI Android host | Parked. No `OpenAPSAIMIPlugin` in production. `ApsPluginRegistrations` is 210/220/230 only (no `@IntKey(225)`). |
| Tick | Parked (`DetermineBasalAIMI2.kt`). Do not move it this week. |
| Dexcom ONE+ / Libre 3 | **Landed on Android.** Host plugins: `DexcomOnePlusPlugin` `@IntKey(446)`, `Libre3NativePlugin` `@IntKey(447)`, Metro, not Hilt. Driver modules `:plugins:dexcom_oneplus` and `:plugins:libre3` stay Android `src/main` (GATT / NFC). Not an iOS port. |
| `:plugins:source` gradle | **Merge leftover.** Sources sit in `src/androidMain`, but `build.gradle.kts` is still `com.android.library` + Hilt (AIMI parent). kmp parent was already KMP. Do **not** copy this gradle shape. See report-merge-kmp.md. |
| NotificationId | Append only. AIMI ids are last: `HYPO_RISK_ALARM`, `AIMI_AUDITOR_INSIGHT`. One+ / Libre 3 dir-access ids sit just above. |
| EventPreferenceChange | String key only: `EventPreferenceChange(key)` / `isChanged(preferenceKey: String)`. |

Copy import / API rules from [`staging/lots/PIPELINE.md`](staging/lots/PIPELINE.md) (Milos merge 2026-08-28) and [`staging/lots/report-merge-kmp.md`](staging/lots/report-merge-kmp.md).

## Lot status

| Folder | commonMain / kmp APIs | Notes |
|---|---|---|
| `core:interfaces` AIMI DTOs | done | `GlucoseStatusAIMI`, `OapsProfileAimi`, adaptation status |
| `model/` `ports/` `extensions/` `carbs/` `decision/` `validation/` | done | T0 + early T1 |
| `keys/` | done | AIMI pref keys in `:core:keys` with `KeysStrings` (not `titleResId`) |
| T0 helpers | done | iOS fixpoint |
| JSON T1 peel | partial | some JSON files stayed in `commonMain` (`TpoModels`, …); others returned to the dump |
| Tick, plugin, Kalman, T2/T3 | parked | `_docs/kmp/staging/openAPSAIMI-android-wip/` (324 kt) |
| Freeze layouts | parked | `_docs/kmp/staging/res-layout-wip/` |

## Counts (2026-08-28)

- Freeze main: 441 kt
- `commonMain` AIMI: **119** kt — dump **not** in androidMain
- Staging android dump: **324** kt
- Dump files with no Android / `java.io` / `org.json` types (T1 candidates): ~180 (do **not** move in one lot)
- TIR freeze APIs restored: `belowPct` / `inRangePct` / `abovePct`, `calculateHour` / `calculateDaily`
- PersistenceLayer: carb helpers + `deleteLastEventMatchingKeyword` + `insertOrUpdateStepsCount`
- `APSResult.Algorithm.AIMI` **last** (after `AUTO_ISF`); RT AIMI fields; `LTag.AIMI`; `NotificationId.HYPO_RISK_ALARM` / `AIMI_AUDITOR_INSIGHT`; `EventPreferenceChange`
- English strings: `core/keys` strings for key titles; `plugins/aps` `aimi_strings.xml` + a few `ApsIntentKey` names in `strings.xml` (generator reads **every** xml in `values/`)

## Still parked (do not restore as one blob)

- `OpenAPSAIMIPlugin.kt`, `DetermineBasalAIMI2.kt`, orchestrator
- Compose / Advisor UI / Camera / SOS SMS
- Health Connect workers, StepService, SAF / `AimiStorageHelper`
- TFLite / ONNX / WorkManager trainers (`AimiModelHandler`, `aimiNeuralNetwork`, `ml/`, `learning/` workers)
- `keys/AimiStringKey.kt` (still `titleResId` + `R.string` — rewrite before any commonMain move)
- `EventAimiCloudBackup*`, `AfrezzaMaxBasal*` no-ops
- Dump still logs with `LTag.APS` and `android.util.Log` in places; dump still uses `ResourceHelper` on the plugin

## Agents this session (lots A–K)

- [Lot A host](01707091-16b9-4a39-ac16-3bcd2158c76c) parked with dump
- [Lot B JSON](3982f5e5-43dc-4037-b104-09b9b60c4a21)
- [Lot C algorithm](195a87ee-e8d4-4279-8303-a7ae3bd71d31)
- [Lot D keys](dfdc7444-5767-4597-91d7-49663080a98a)
- [Lot E persist carbs](517584c7-c0c2-4ee2-acb2-2c05fe17ca1c)
- [Lot F catalog](c16af15b-c421-4e29-840e-426235e66b2b)
- [Lot G APSResult/RT/DB](26e8e6f2-ea1b-480f-a7c1-9bc94323a580)
- [Lot H notif + pref event](64e45b5e-fadc-473f-8676-b5761a43d536)
- [Lot I deleteLast/steps](df7bcc8f-8738-4bff-badf-ff4f630aa78c)
- [Lot J leftover keys + intents](510635c3-07df-48c0-9909-23ee3ce95fa0)
- [Lot K strings](576e3075-77a7-450c-9026-b3acfdce8814): 198 strings

## Next

1. **Lot L done:** 14 T1 math files in `commonMain`. Review APPROVE_WITH_CONCERNS (`review-L.md`).
2. **Lot M:** peel dump files that Lot L skipped, after their dump-only types land (or peel those types first).
3. Host: repair `:plugins:source` gradle to the kmp KMP file (keep One+ / Libre 3). Separate from AIMI T1 lots.
4. Later: androidMain TFLite/HC/ONNX + `@IntKey(225)` after a **small** host slice compiles, not the whole dump at once.
5. Tick last. Do not say AIMI runs on iOS.
