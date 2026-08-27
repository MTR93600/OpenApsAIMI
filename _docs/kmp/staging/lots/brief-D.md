# Task D — Port freeze AIMI preference keys to kmp `:core:keys`

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Branch: `kmp-aimi-migration-study`
Freeze: tag `aimi-baseline-2026-08-26` (`1ae418e106`)

## Why

`DetermineBasalAIMI2` and the android dump need `BooleanKey.OApsAIMI*`, `DoubleKey.OApsAIMI*`, etc.
kmp keys require `title: TextRef` (`KeysStrings.*`). Freeze used `titleResId: Int = 0`.

## Do

Port **every AIMI key** from freeze into the kmp enums. Include names that start with:

- `OApsAIMI`
- `Aimi`
- `ContextLLM` / `ContextMode` (AIMI context LLM)
- `OverviewShowHybridDashboardAimi` / other `*Aimi*` overview keys

Also: `BooleanNonKey.AimiAdaptiveBasalReenabledOnUpgrade`.

Freeze sources (git show):

- `core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt` (AIMI block ~286–737 plus hybrid dashboard ~45–60)
- `DoubleKey.kt` (~319–766)
- `IntKey.kt` (~464–530)
- `StringKey.kt` (~216–293)
- `BooleanNonKey.kt` (`AimiAdaptiveBasalReenabledOnUpgrade`)
- English strings: `core/keys/src/main/res/values/strings.xml` on the freeze tag

kmp destinations:

- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/{Boolean,Double,Int,String}Key.kt`
- `core/keys/src/commonMain/kotlin/app/aaps/core/keys/BooleanNonKey.kt`
- English strings only: `core/keys/src/androidMain/res/values/strings.xml` (ignore translations)

## Adapt freeze → kmp

- `titleResId = R.string.foo` → `title = KeysStrings.foo` (add `<string name="foo">` if missing)
- Freeze keys with **no** title: still required on kmp. Add `pref_title_<stable_name>` English string (school English, short) and use `KeysStrings.pref_title_<stable_name>`
- `summaryResId` → `summary = KeysStrings...` or omit
- Keep `key`, `defaultValue`, min/max, `dependency`, `defaultedBySM`, `preferenceType`, `isPassword`, `exportable` from freeze
- `PreferenceType.LIST` on StringKey: keep type; entries can stay empty if freeze had none (plugin fills via `withEntries`)
- Insert **before** the closing `;` of each enum
- Explicit imports. School English comments. Do not rename freeze enum constants (call sites use them)

## Do not

- Do not restore `_docs/kmp/staging/openAPSAIMI-android-wip/` into `androidMain`
- Do not edit `:plugins:aps` dump, `ApsPluginRegistrations.kt`, gradle TFLite/HC/ONNX
- Do not port non-AIMI freeze-only keys (example: `GeneralLowEndStabilityMode`)
- Do not commit or push
- Do not install the app
- Do not use `cd &&` in shell. Use `./gradlew` with `--no-daemon`
- `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` for iOS compile

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:keys:compileKotlinIosSimulatorArm64 :core:keys:compileAndroidMain
```

Redirect to a log; grep `BUILD SUCCESSFUL` / `BUILD FAILED` / `^e:`.

## Report

Write `_docs/kmp/staging/lots/report-D.md`: counts per enum, strings added, compile result, skipped keys.
Return: DONE | DONE_WITH_CONCERNS | BLOCKED + one-line summary.
