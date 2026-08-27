# Task H — NotificationId AIMI + EventPreferenceChange

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Freeze: `aimi-baseline-2026-08-26`

## Do

1. `core/interfaces/.../notifications/NotificationId.kt`
   Add **at the end** of the enum (before `companion object`), never in the middle (ordinal = system id):
   - `HYPO_RISK_ALARM(URGENT, LOOP, allowMultiple = true)`
   - `AIMI_AUDITOR_INSIGHT(INFO, LOOP)`
   Short school English KDoc like freeze.

2. Create `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/rx/events/EventPreferenceChange.kt` matching freeze:

```kotlin
package app.aaps.core.interfaces.rx.events

class EventPreferenceChange(val key: String) : Event() {
    fun isChanged(preferenceKey: String): Boolean = preferenceKey == key
}
```

If freeze has extra `isChanged` overloads, port those too (`git show aimi-baseline-2026-08-26:core/interfaces/src/main/kotlin/app/aaps/core/interfaces/rx/events/EventPreferenceChange.kt`).

Do **not** wire a new publisher in PreferencesImpl unless one already exists on this branch. Dump plugin only needs the type to compile.

## Do not

Edit PersistenceLayer, APSResult, keys, dump. No commit.

## Verify

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :core:interfaces:compileAndroidMain
```

If lot G is compiling interfaces at the same time and you collide, retry once.

## Report

`_docs/kmp/staging/lots/report-H.md`
Return DONE | DONE_WITH_CONCERNS | BLOCKED.
