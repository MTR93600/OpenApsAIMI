# Task I report — PersistenceLayer deleteLastEvent + insertOrUpdateStepsCount

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: `aimi-baseline-2026-08-26`  
No commit, no push. No dump restore. Carb helpers, APSResult, RT, NotificationId, and keys were not edited.

## Files

- `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/db/PersistenceLayer.kt`
  - After `getUserEntryFilteredDataFromTime`: `suspend fun deleteLastEventMatchingKeyword(noteKeyword: String)`
  - After `insertOrUpdateStepsCounts`: default `insertOrUpdateStepsCount(stepsCount: SC)` → `insertOrUpdateStepsCounts(listOf(stepsCount))` (freeze KDoc kept)
  - Three carb helpers left as they were
- `database/impl/src/commonMain/kotlin/app/aaps/database/daos/TherapyEventDao.kt` — freeze `@Query` DELETE + `deleteLastEventMatchingKeyword`
- `database/impl/src/commonMain/kotlin/app/aaps/database/AppRepository.kt` — dao pass-through after `getLastTherapyEventId` (same as freeze)
- `database/persistence/src/commonMain/kotlin/app/aaps/database/persistence/PersistenceLayerImpl.kt` — `override` calls `repository.deleteLastEventMatchingKeyword`. No `insertOrUpdateStepsCount` override (interface default)

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :database:impl:compileAndroidMain :database:persistence:compileAndroidMain
```

**BUILD SUCCESSFUL** in 8m 36s, exit 0.

Kotlin daemon first failed (`Could not delete .../compileAndroidMain/cacheable/caches-jvm`), then compiled without the daemon. `:core:interfaces:compileKotlinIosSimulatorArm64`, `:database:impl:compileAndroidMain`, and `:database:persistence:compileAndroidMain` all ran.

## Concerns / blockers for the controller

1. **No `aapsIoDispatcher` wrap** on `PersistenceLayerImpl.deleteLastEventMatchingKeyword`. Freeze is the same (direct repository call). Sibling KMP methods use `withContext(aapsIoDispatcher)`. Room suspend should still run the SQL; not runtime-tested.
2. **Hard DELETE**, not invalidate. Freeze query deletes the newest therapy event whose `note` contains the keyword. Callers (`therapy.kt` meal/sleep/sport tags) depend on that.
3. **Not runtime-tested.** Compile only. No check that LIKE + `ORDER BY timestamp DESC LIMIT 1` picks the same row as freeze.
4. **Kotlin daemon fallback** on this machine (cache delete). Not a source error.

## Counts for controller

- Status: `DONE_WITH_CONCERNS`
- Methods added: **2** (`deleteLastEventMatchingKeyword` interface+dao+repo+impl, `insertOrUpdateStepsCount` interface default only)
- Files edited: **4**
- Compile: **yes** (`compileKotlinIosSimulatorArm64` + `compileAndroidMain` on impl and persistence)
