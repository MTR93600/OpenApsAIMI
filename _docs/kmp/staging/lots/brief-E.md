# Task E — Restore freeze PersistenceLayer carb helpers

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Branch: `kmp-aimi-migration-study`
Freeze: tag `aimi-baseline-2026-08-26`

## Why

Tick dump calls `persistence.getMostRecentCarbByDate()`, `getMostRecentCarbAmount()`, `getFutureCob()`.
They are **default methods** on freeze `PersistenceLayer`. Missing on kmp.

## Do

Add the same three default methods to:

`core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/db/PersistenceLayer.kt`

Insert **after** `collectNewEntriesSince(...)` and **before** `class TransactionResult`.

Freeze bodies (keep logic, school English KDoc, drop French comments):

```kotlin
    suspend fun getMostRecentCarbByDate(): Long? {
        val now = System.currentTimeMillis()
        return getCarbsFromTime(now, false)
            .maxByOrNull { it.timestamp }
            ?.timestamp
    }

    suspend fun getMostRecentCarbAmount(): Double? {
        val now = System.currentTimeMillis()
        return getCarbsFromTime(now, false)
            .maxByOrNull { it.timestamp }
            ?.amount
    }

    suspend fun getFutureCob(): Double {
        val now = System.currentTimeMillis()
        return getCarbsFromTime(now, true)
            .filter { it.timestamp > now }
            .sumOf { it.amount }
    }
```

`CA` already has `timestamp` and `amount` (`core/data/.../CA.kt`).
`System.currentTimeMillis()` is OK in commonMain here (same as freeze).

No impl class change: defaults on the interface are enough (freeze did it this way).

## Do not

- Do not edit key enums, `:plugins:aps`, staging dump, gradle
- Do not commit or push
- Do not use `cd &&`. Use `./gradlew --no-daemon`
- `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` for iOS

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :core:interfaces:compileAndroidMain
```

## Report

Write `_docs/kmp/staging/lots/report-E.md`.
Return: DONE | DONE_WITH_CONCERNS | BLOCKED + one-line summary.
