# Lot BB — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2cef8ced00` (Lot BA)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `UnifiedActivityProviderMTR` not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**AIMI string preference keys are dest.** Titles use `ApsStrings` `TextRef`. Tick / plugin stay parked. Dest keys are not a live prefs screen. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `keys/AimiStringKey.kt` | `title` / `summary` / `entries` as `ApsStrings`. Activity mode literals inlined |
| `src/androidMain/res/values/aimi_strings.xml` | `aimi_pkpd_state_internal_title` for dump `titleResId = 0` keys |

No dest Kotlin file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `UnifiedActivityProviderMTR` | `Looper` / PersistenceLayer host |
| `ContextManager` | dump UAM refresher |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Teacher `TextRef` / `ApsStrings`, not `R.string`.
- Hidden PKPD keys keep dump `showIn*` false; title is the new internal string (UI does not show it).
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BB.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: string keys only. Activity provider and plugin not copied.
- Next graph: Compose / HC / File host. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
