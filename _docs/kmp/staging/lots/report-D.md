# Task D report — Port freeze AIMI preference keys to kmp `:core:keys`

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: tag `aimi-baseline-2026-08-26` (`1ae418e106`)  
No commit, no push. Android dump not restored. `:plugins:aps` not edited.

## Counts per enum

| Enum | Ported | Untitled freeze (new `pref_title_*`) | Already had freeze title |
|---|---:|---:|---:|
| `BooleanKey` | 84 | 51 | 33 |
| `DoubleKey` | 117 | 89 | 28 |
| `IntKey` | 29 | 26 | 3 |
| `StringKey` | 28 | 26 | 2 |
| `BooleanNonKey` | 1 | n/a (no title on this enum) | n/a |
| **Total** | **259** | **192** | **66** |

Inserted before the closing `;` of each preference enum (`BooleanNonKey` has no `;`; inserted before `}`).

`titleResId = R.string.foo` → `title = KeysStrings.foo`. Same for summaries.  
Untitled freeze keys got `title = KeysStrings.pref_title_<stable_name>` (required on kmp).  
`key`, `defaultValue`, min/max, `dependency`, `defaultedBySM`, `preferenceType`, `isPassword`, `exportable` kept from freeze.  
`PreferenceType.LIST` string keys kept with empty entries (plugin fills via `withEntries`).  
Enum constant names not renamed.

## Strings added

English only: `core/keys/src/androidMain/res/values/strings.xml`

| Kind | Count |
|---|---:|
| Copied from freeze `core/keys/src/main/res/values/strings.xml` (titled keys) | 132 |
| New `pref_title_*` for untitled freeze keys | 192 |
| **Total new `<string>` rows** | **324** |

Translations not touched. `generateKeyStrings` ran during compile (19 empty locales warning is pre-existing).

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:keys:compileKotlinIosSimulatorArm64 :core:keys:compileAndroidMain
```

Log: `/tmp/aimi-task-D-keys.log`  
Result: **BUILD SUCCESSFUL in 28s**  
`^e:`: none. Retry not needed.

## Skipped keys

Not ported (brief name filters / destinations):

- `IntentKey.OApsAIMIProfileAdvisor`
- `IntentKey.OApsAIMIMealAdvisor`
- `IntentKey.OApsAIMIContext`  
  (`IntentKey` is not a brief destination.)
- `BooleanKey.OApsxdriponeminute` — freeze AIMI-adjacent key string, but the enum name does not match `OApsAIMI` / `Aimi` / `*Aimi*`.
- Non-AIMI freeze-only keys, including `GeneralLowEndStabilityMode`.
- Overview keys that mention AIMI only in comments: `OverviewUseDashboardLayout`, `OverviewDashboardExtendedMetrics` (names have no `Aimi`).

## Files touched

- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/BooleanKey.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/DoubleKey.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/IntKey.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/StringKey.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/BooleanNonKey.kt`
- `core/keys/src/androidMain/res/values/strings.xml`

Diff: 6 files, +1336 lines.

## Notes for the controller

- Mixed freeze constructors such as `(key = "...", 180.0, 140.0, 250.0)` were rewritten to named `defaultValue` / `min` / `max` so they compile on kmp.
- A few generated string **ids** are mechanical snake_case (`pref_title_oaps_aimi_m_ltraining`). Display English for those was cleaned (example: "AIMI ML training").
- Not runtime tested. Preference UI entries for LIST keys still come from the plugin.
