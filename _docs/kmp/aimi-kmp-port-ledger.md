# AIMI KMP port ledger (Milos pattern)

Destination: `:plugins:aps` `commonMain` (`openAPSAIMI/`), types in `:core:interfaces` `commonMain`.
Same shape as OpenAPS SMB: algorithm in commonMain, Metro `@Inject`, no Hilt, no `android.util.Log`.

Freeze: `aimi-baseline-2026-08-26` (`dev_OAPSAIMI` `1ae418e106`). 441 main files.

## Lot status

| Folder | commonMain / kmp APIs | Notes |
|---|---|---|
| `core:interfaces` AIMI DTOs | done | `GlucoseStatusAIMI`, `OapsProfileAimi`, adaptation status |
| `model/` `ports/` `extensions/` `carbs/` `decision/` `validation/` | done | |
| `keys/` | done | `AimiLongKey` + 262 AIMI pref keys in `:core:keys` |
| T0 helpers | done | iOS fixpoint |
| JSON T1 peel | 8 files stayed | rest in android dump |
| Tick, plugin, Kalman, T2/T3 | not compiling | dump parked: `_docs/kmp/staging/openAPSAIMI-android-wip/` (324 kt) |
| Freeze layouts | parked | `_docs/kmp/staging/res-layout-wip/` (AAPT was missing styles/strings) |

## Counts (2026-08-27 night)

- Freeze main: 441 kt
- `commonMain` AIMI: **119** kt — dump **not** in androidMain
- Staging android dump: **324** kt
- TIR freeze APIs restored: `belowPct` / `inRangePct` / `abovePct`, `calculateHour` / `calculateDaily`
- PersistenceLayer: carb helpers + `deleteLastEventMatchingKeyword` + `insertOrUpdateStepsCount`
- `APSResult.Algorithm.AIMI` **last** (after `AUTO_ISF`); RT AIMI fields; `LTag.AIMI`; `NotificationId.HYPO_RISK_ALARM` / `AIMI_AUDITOR_INSIGHT`; `EventPreferenceChange`
- 198 freeze English strings added to `aimi_strings.xml`

## Agents this session

- [Lot A host](01707091-16b9-4a39-ac16-3bcd2158c76c) parked with dump
- [Lot B JSON](3982f5e5-43dc-4037-b104-09b9b60c4a21)
- [Lot C algorithm](195a87ee-e8d4-4279-8303-a7ae3bd71d31)
- [Lot D keys](dfdc7444-5767-4597-91d7-49663080a98a): 259 keys
- [Lot E persist carbs](517584c7-c0c2-4ee2-acb2-2c05fe17ca1c)
- [Lot F catalog](c16af15b-c421-4e29-840e-426235e66b2b)
- [Lot G APSResult/RT/DB](26e8e6f2-ea1b-480f-a7c1-9bc94323a580)
- [Lot H notif + pref event](64e45b5e-fadc-473f-8676-b5761a43d536)
- [Lot I deleteLast/steps](df7bcc8f-8738-4bff-badf-ff4f630aa78c)
- [Lot J leftover keys + intents](510635c3-07df-48c0-9909-23ee3ce95fa0)
- [Lot K strings](576e3075-77a7-450c-9026-b3acfdce8814): 198 strings

## Next

1. Controller compile `:plugins:aps` with dump **still parked**.
2. Then restore dump + androidMain TFLite/HC/ONNX + `@IntKey(225)` and compile androidMain.
3. Remaining dump blockers: `AfrezzaMaxBasal*` (keep no-op), `EventAimiCloudBackup*`, `android.util.Log` in dump, `AimiStringKey`, Context→TextResolver on tick.
4. Tick last. Do not say AIMI runs on iOS.
5. Do not commit the 324-file dump until `compileAndroidMain` is green.
