# Task E report — Restore freeze PersistenceLayer carb helpers

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
No commit, no push. Did not edit LTag.kt, core/keys, or the android dump.

## Files

- `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/db/PersistenceLayer.kt` only

Inserted after `collectNewEntriesSince(...)` and before `class TransactionResult`:

- `getMostRecentCarbByDate(): Long?`
- `getMostRecentCarbAmount(): Double?`
- `getFutureCob(): Double`

Default methods on the interface (same as freeze). No impl class change. School English KDoc. Freeze logic kept (`getCarbsFromTime` + `maxByOrNull` / `filter` + `sumOf`).

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :core:interfaces:compileAndroidMain
```

First run failed on iOS: `Unresolved reference 'System'` at the three new methods. Brief freeze bodies used `System.currentTimeMillis()`, which is JVM-only.

Fixed to `Clock.System.now().toEpochMilliseconds()` (already imported and used in this same file, e.g. `insertPumpTherapyEventIfNewByTimestamp`). Retry: **BUILD SUCCESSFUL** in 41s. LTag.kt was not involved.

## Concerns / blockers for the controller

1. **Time source:** freeze used `System.currentTimeMillis()`. KMP iOS commonMain cannot. Same epoch-ms meaning via `kotlin.time.Clock`, already the pattern in `PersistenceLayer`.
2. **Not runtime-tested:** compile only. No check that callers get the same carb rows as freeze.

## Counts for controller

- Status: `DONE_WITH_CONCERNS`
- Methods added: **3**
- Files edited: **1**
- Compile: **yes** (`compileKotlinIosSimulatorArm64` + `compileAndroidMain`)
