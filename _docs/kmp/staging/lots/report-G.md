# Task G report — AIMI APSResult / RT / DB algorithm

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: `aimi-baseline-2026-08-26`  
Dump: parked (not restored). No commit, no push.

Did not touch `PersistenceLayer.kt`, `NotificationId.kt`, or `EventPreferenceChange.kt`.

## Do

1. `core/interfaces/.../aps/APSResult.kt`
   - `var isHypoRisk: Boolean`
   - `var oapsProfileAimi: OapsProfileAimi?`
   - `Algorithm.AIMI` added **at the end** (after `AUTO_ISF`). Freeze had AIMI before AUTO_ISF; kmp DBs have never stored AIMI.

2. `core/interfaces/.../aps/RT.kt`
   - Freeze AIMI fields `aimilog` through `aimiAdaptationStatus`, same names and defaults.
   - `aimilog` uses existing `StringBuilderSerializer`.
   - `aimiAdaptationStatus` is `@Transient` (`AimiAdaptationStatus` is not serializable).
   - School English comments. No French. No `ConsoleLogSerializer` (kmp `consoleLog` stays a plain list).

3. `implementation/.../aps/DetermineBasalResult.kt`
   - `isHypoRisk` default `false`, `oapsProfileAimi` default `null`.
   - `with(result)` copies `isHypoRisk = result.isHypoRisk`.
   - No `resultAsSpanned` / `Spanned`.

4. `database/impl/.../entities/APSResult.kt`
   - `Algorithm.AIMI` at the **end**.

5. `database/persistence/.../converters/APSResultExtension.kt`
   - AIMI `fromDb` / `toDb` branches with `GlucoseStatusAIMI` + `OapsProfileAimi`.
   - AIMI added to enum `fromDb` / `toDb` mappers.
   - No French comments. No freeze `sanitizeJson` helper (kmp AMA/SMB/AUTO_ISF branches do not sanitize).

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :implementation:compileAndroidMain :database:persistence:compileAndroidMain :plugins:aps:compileKotlinIosSimulatorArm64
```

**BUILD SUCCESSFUL** (9m 10s, exit 0). One mid-log `e: Daemon compilation failed` (Kotlin cache already registered / corrupted) from parallel lots; Gradle retried and finished green.

## Concerns for the controller

1. **`:app` androidTest stub:** `app/src/androidTest/.../APSResultObject.kt` still does not implement `isHypoRisk` / `oapsProfileAimi`. Not in the verify compile set. `:app` androidTest will not compile until that stub is updated.
2. **No freeze `sanitizeJson`:** freeze AIMI `fromDb` stripped unicode from `resultJson` before decode. kmp AIMI uses the same decode path as AUTO_ISF. Old AIMI rows with emoji in `consoleLog` may fail decode until a later pass (kmp DBs have never stored AIMI).
3. **No freeze `ConsoleLogSerializer`:** kmp still serializes `consoleLog` as a normal list.
4. **`APSResultExtensionTest`:** round-trip iterates all enums except UNKNOWN, so AIMI is covered. The explicit mapping test and its comment still list only AMA / SMB / AUTO_ISF.
5. **Room ordinal vs name:** `database/impl/.../Converters.kt` stores algorithm by **name**, not ordinal. AIMI at the end is still the safe choice.

## Not done (out of brief)

- `resultAsSpanned` / Android `Spanned`
- Freeze `allowedAlgorithms` check in `DetermineBasalResult.with`
- PersistenceLayer / NotificationId / EventPreferenceChange
- Dump restore / commit / push
