# Task J report — Leftover AIMI keys + ApsIntentKey

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: tag `aimi-baseline-2026-08-26`  
No commit, no push. Android dump not restored. `aimi_strings.xml` not edited (lot K).

## Do (1) core keys

Inserted before each enum `;`. Freeze `key` / defaults / min / max kept. kmp `title` required.

| Enum | Constant | Shared pref key | Title |
|---|---|---|---|
| `DoubleKey` | `autodriveMaxBasal` | `autodrive_max_basal` (1.0, 0.05, 25.0) | `KeysStrings.pref_title_autodrive_max_basal` |
| `DoubleKey` | `meal_modes_MaxBasal` | `meal_modes_max_basal` (1.0, 0.05, 25.0) | `KeysStrings.pref_title_meal_modes_max_basal` |
| `BooleanKey` | `OApsxdriponeminute` | `key_use_Aimi_xdripOM` (false) | `KeysStrings.pref_title_oapsxdriponeminute` |

English only in `core/keys/src/androidMain/res/values/strings.xml`:

- `pref_title_autodrive_max_basal` — Fixed basal 30 min if meal detected (U/h)
- `pref_title_meal_modes_max_basal` — Fixed basal 30 min at meal start (U/h)
- `pref_title_oapsxdriponeminute` — Libre3 1-minute readings

Display English taken from freeze `plugins/aps` (`autodrive_max_basal_title`, `meal_modes_max_basal_title`, `Enable_xdripOM_title`). Freeze `core/keys` had no titles for these.

`core/keys/IntentKey.kt` left empty.

## Do (2) ApsIntentKey

Added to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/keys/ApsIntentKey.kt`. Freeze `key` values kept.

| Constant | `preferenceType` | Why |
|---|---|---|
| `AimiControlCenter` | `CLICK` | dump uses `.withCompose(...)` |
| `AimiHypoRiskAlarmInfo` | `CLICK` | dump `.withCompose` + freeze `CLICK` |
| `AimiPhysioPatternCatalogInfo` | `CLICK` | dump `.withCompose` + freeze `CLICK` |
| `HormonitorViewer` | `CLICK` | dump uses `.withCompose(...)` |
| `PkpdSetup` | `CLICK` | dump uses `.withCompose(...)` |
| `AimiSosPermissions` | `ACTIVITY` | freeze click intent (no `withCompose`) |
| `AimiHealthConnectPermissions` | `ACTIVITY` | freeze click intent (no `withCompose`) |

kmp `IntentPreferenceKey` has no `activityClass`. Freeze SOS / Health Connect activity classes were not copied. Dump can attach click later.

### Strings for `ApsStrings`

`generateApsStrings` reads every xml under `values/`. Lot K already puts freeze names (`aimi_control_center_entry_title`, …) in `aimi_strings.xml`. Copying those names into `strings.xml` would duplicate and fail AAPT.

New English rows in `plugins/aps/src/androidMain/res/values/strings.xml` (14 strings: 7 titles + 7 summaries), unique `pref_title_*` / `pref_summary_*` ids. English follows freeze, school English where freeze used `&` or an em dash.

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:keys:compileKotlinIosSimulatorArm64 :core:keys:compileAndroidMain
```

Log: `/tmp/aimi-task-J-keys.log`  
Result: **BUILD SUCCESSFUL in 2m 46s**  
`^e:`: none.

`:plugins:aps` compile **skipped**: another gradle was already compiling `:plugins:aps`, and lot K is writing `aimi_strings.xml`. Controller should compile aps later. `generateApsStrings` must see the new `pref_title_*` rows in `plugins/aps/.../values/strings.xml`.

## Files touched

- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/DoubleKey.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/BooleanKey.kt`
- `core/keys/src/androidMain/res/values/strings.xml`
- `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/keys/ApsIntentKey.kt`
- `plugins/aps/src/androidMain/res/values/strings.xml`

## Concerns for the controller

1. **`:plugins:aps` not compiled here.** Intent key compile is not proven in this lot.
2. **ApsIntentKey titles do not reuse freeze string ids.** Dump screens still use freeze `R.string.aimi_*` names (lot K). Preference titles use `ApsStrings.pref_title_*` so J and K do not clash.
3. **No `activityClass` on kmp.** `AimiSosPermissions` / `AimiHealthConnectPermissions` need a plugin `withClick` (or compose) when dump is restored.
4. Not runtime tested.
