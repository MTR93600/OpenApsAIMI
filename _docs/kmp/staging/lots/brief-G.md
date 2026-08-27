# Task G — AIMI APSResult / RT / DB algorithm

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Branch: `kmp-aimi-migration-study`
Freeze: `aimi-baseline-2026-08-26`

## Do

1. `core/interfaces/.../aps/APSResult.kt`
   - `var isHypoRisk: Boolean`
   - `var oapsProfileAimi: OapsProfileAimi?`
   - Add `AIMI` to `enum class Algorithm` **at the end** (after `AUTO_ISF`). Do **not** insert before `AUTO_ISF` (would shift Room ordinals). Freeze had AIMI before AUTO_ISF; kmp DBs have never stored AIMI.

2. `core/interfaces/.../aps/RT.kt` — add freeze AIMI fields with the same names/defaults (school English comments). Source: `git show aimi-baseline-2026-08-26:core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/RT.kt` fields `aimilog` through `aimiAdaptationStatus`. `AimiAdaptationStatus` already exists in this module. `aimilog` is `StringBuilder` (reuse existing `StringBuilderSerializer` if needed).

3. `implementation/.../aps/DetermineBasalResult.kt`
   - Implement `isHypoRisk` (default false) and `oapsProfileAimi` (default null)
   - In `with(result: RT)`, copy `isHypoRisk = result.isHypoRisk`

4. `database/impl/.../entities/APSResult.kt` — add `AIMI` **at end** of `enum class Algorithm`.

5. `database/persistence/.../converters/APSResultExtension.kt` — add AIMI branches like freeze (`git show aimi-baseline-2026-08-26:.../APSResultExtension.kt`): `GlucoseStatusAIMI` + `OapsProfileAimi`. Do not copy French comments. `GlucoseStatusAIMI` already exists.

Do **not** add `resultAsSpanned` / Android `Spanned` (kmp dropped those on purpose).

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :implementation:compileAndroidMain :database:persistence:compileAndroidMain :plugins:aps:compileKotlinIosSimulatorArm64
```

Dump stays parked. No commit.

## Report

`_docs/kmp/staging/lots/report-G.md`
Return DONE | DONE_WITH_CONCERNS | BLOCKED.
