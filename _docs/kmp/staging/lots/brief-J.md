# Task J — Leftover AIMI keys + ApsIntentKey

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Freeze: `aimi-baseline-2026-08-26`

Lot D skipped these because names did not match the prefix filter. Dump still uses them.

## Do (1) core keys

From freeze `DoubleKey.kt` / `BooleanKey.kt`:

- `DoubleKey.autodriveMaxBasal("autodrive_max_basal", 1.0, 0.05, 25.0)`
- `DoubleKey.meal_modes_MaxBasal("meal_modes_max_basal", 1.0, 0.05, 25.0)`
- `BooleanKey.OApsxdriponeminute(key = "key_use_Aimi_xdripOM", defaultValue = false)`

kmp requires `title: KeysStrings...`. Add English `pref_title_*` to `core/keys/src/androidMain/res/values/strings.xml` only. Insert before each enum `;`.

## Do (2) ApsIntentKey

Dump plugin already uses (see `_docs/kmp/staging/openAPSAIMI-android-wip/OpenAPSAIMIPlugin.kt`):

- `ApsIntentKey.AimiControlCenter`
- `ApsIntentKey.AimiSosPermissions`
- `ApsIntentKey.AimiHypoRiskAlarmInfo`
- `ApsIntentKey.AimiPhysioPatternCatalogInfo`
- `ApsIntentKey.AimiHealthConnectPermissions`
- `ApsIntentKey.HormonitorViewer`
- `ApsIntentKey.PkpdSetup`

Add them to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/keys/ApsIntentKey.kt`.
Titles: `ApsStrings.*` — add English strings to `plugins/aps` generated string source (same pattern as `LinkToDocs`: look at how `ApsStrings` is generated, likely `plugins/aps/src/androidMain/res/values/` or `aimi_strings.xml`). English only.

`preferenceType`: follow existing `LinkToDocs` / freeze click intents. If dump uses `.withCompose(...)`, keep `PreferenceType.CLICK` unless LinkToDocs pattern says otherwise. Grep kmp for `withCompose` on IntentPreferenceKey.

Do **not** put these on empty `core/keys/IntentKey.kt`.

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:keys:compileKotlinIosSimulatorArm64 :core:keys:compileAndroidMain :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Dump stays parked. No commit.

## Report

`_docs/kmp/staging/lots/report-J.md`
Return DONE | DONE_WITH_CONCERNS | BLOCKED.
