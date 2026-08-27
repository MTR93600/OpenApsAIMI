# Task I — PersistenceLayer deleteLastEvent + insertOrUpdateStepsCount

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Freeze: `aimi-baseline-2026-08-26`

## Do

1. `core/interfaces/.../db/PersistenceLayer.kt`
   - After `getUserEntryFilteredDataFromTime`: `suspend fun deleteLastEventMatchingKeyword(noteKeyword: String)`
   - After `insertOrUpdateStepsCounts`: default
     `suspend fun insertOrUpdateStepsCount(stepsCount: SC): TransactionResult<SC> = insertOrUpdateStepsCounts(listOf(stepsCount))`
   Do **not** change the three carb helpers already there.

2. `database/impl/.../daos/TherapyEventDao.kt` — freeze query:
   `@Query("DELETE FROM $TABLE_THERAPY_EVENTS WHERE id IN (SELECT id FROM $TABLE_THERAPY_EVENTS WHERE note LIKE '%' || :noteKeyword || '%' ORDER BY timestamp DESC LIMIT 1)")`
   `suspend fun deleteLastEventMatchingKeyword(noteKeyword: String)`

3. `database/impl/.../AppRepository.kt` — `suspend fun deleteLastEventMatchingKeyword(noteKeyword: String)` that calls the dao (same as freeze).

4. `database/persistence/.../PersistenceLayerImpl.kt` — `override suspend fun deleteLastEventMatchingKeyword` → `repository.deleteLastEventMatchingKeyword`. `insertOrUpdateStepsCount` needs **no** override if the interface default is enough.

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :database:impl:compileAndroidMain :database:persistence:compileAndroidMain
```

No commit. Do not restore dump.

## Report

`_docs/kmp/staging/lots/report-I.md`
Return DONE | DONE_WITH_CONCERNS | BLOCKED.
