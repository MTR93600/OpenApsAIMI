# Task H report — NotificationId AIMI + EventPreferenceChange

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: `aimi-baseline-2026-08-26`  
No commit, no push. Did not edit PersistenceLayer, APSResult, RT, keys, or dump.

## Files

- `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/notifications/NotificationId.kt`
- `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/rx/events/EventPreferenceChange.kt` (new)

## NotificationId

Appended **at the end** of the enum (after `LIBRE3_DIR_ACCESS_LOST`, before `companion object`). Not inserted in the Loop block (would shift system ids).

| Entry | Args | KDoc |
|---|---|---|
| `HYPO_RISK_ALARM` | `URGENT, LOOP, allowMultiple = true` | Short school English (freeze had none) |
| `AIMI_AUDITOR_INSIGHT` | `INFO, LOOP` | Copied from freeze |

## EventPreferenceChange

Created in commonMain, matching freeze:

- `class EventPreferenceChange(val key: String) : Event()`
- `isChanged(preferenceKey: String): Boolean` only — freeze has no extra overloads
- Freeze KDoc kept (`PreferenceKey.key` fully qualified)

No publisher in `PreferencesImpl` (none existed on this branch). Dump plugin only needs the type to compile.

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64 :core:interfaces:compileAndroidMain
```

First run: **BUILD FAILED** (`compileAndroidMain` ICE). Concurrent compile: could not read `core/keys/.../classes.jar` (`ProfileComposedBooleanKeyKt.class`) + Kotlin daemon lookup-cache corruption. `compileKotlinIosSimulatorArm64` had already run (warnings only).

Retry once: Kotlin daemon failed again (`Storage ... is already registered`), then **fallback compile without daemon**. **BUILD SUCCESSFUL in 3m 23s**. No `^e:` on our files.

## Concerns / blockers for the controller

1. **No publisher:** `EventPreferenceChange` is not sent anywhere on this branch. `OpenAPSAIMIPlugin` can subscribe after dump restore, but runtime changes will not fire until a later lot wires `PreferencesImpl` (or equivalent). Brief forbade adding that publisher.
2. **Ordinals differ from freeze:** freeze put `HYPO_RISK_ALARM` in the Loop section and `AIMI_AUDITOR_INSIGHT` before `CARBS_STORE_FAILED`. Kmp appends both last on purpose so existing system-notification ids stay stable.
3. **First compile collision:** retry recovered. Not a source error.

## Counts for controller

- Status: `DONE_WITH_CONCERNS`
- NotificationId entries added: **2** (end of enum)
- New event type: **1** (`EventPreferenceChange`)
- Publisher wired: **no**
- Compile: retry **BUILD SUCCESSFUL**
