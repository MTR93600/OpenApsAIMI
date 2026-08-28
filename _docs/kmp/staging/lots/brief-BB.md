# Lot BB — AIMI string preference keys (TextRef rewrite)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2cef8ced00` (Lot BA)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AimiLongKey`. `ApsIntentKey` uses `ApsStrings` + `TextRef`, not `titleResId`.

**The cut:** dump `keys/AimiStringKey.kt` is dest-type except `R.string` / `titleResId` and dump `UnifiedActivityProviderMTR` constants. **Rewrite titles to `ApsStrings`.** Inline the activity-source string literals. Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** `ContextManager` still needs dump UAM refresher. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `keys/AimiStringKey.kt` | dest `StringPreferenceKey` + `ApsStrings` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `UnifiedActivityProviderMTR`, `ContextManager`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `titleResId` / `summaryResId` → `title` / `summary` as `ApsStrings.*` (`TextRef`). Never `R.string`. Never hand-write `TextRef.Named`.
- `entries: Map<String, Int>` → `Map<String, TextRef>`.
- Hidden dump keys (`titleResId = 0`) → new english `aimi_pkpd_state_internal_title` (not shown; `showInApsMode = false`).
- Inline dump `UnifiedActivityProviderMTR` mode string literals. Do not import that dump class.
- Explicit `BooleanPreferenceKey` import. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BB.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BB.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
