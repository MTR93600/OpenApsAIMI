# Lot AX — CODE report

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `db488cdcbb` (Lot AW)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `class PkPdIntegration` not copied. `AIMIStepsManagerMTR` not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**PKPD meal/bolus DTOs and the steps provider interface are dest.** `class PkPdIntegration` stays dump (`readAimiBehaviorRuntimeProfile`). Steps manager / Health Connect stay dump. Tick / plugin stay parked. Dest interface is not a live steps source. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Interface + DTOs only. No coroutines.

Concern: first compile failed. Brief said `kotlinx.datetime.Clock.System`. This repo’s wall clock is `kotlin.time.Clock` (`AimiWallClock.kt`). Fixed to `kotlin.time.Clock` / `kotlin.time.Instant`. Second compile exit 0.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| dest `pkpd/PkPdIntegrationModels.kt` | extract dump `MealAggressionContext` + `PkpdBolusSample` only |
| `steps/AIMIStepsProviderMTR.kt` | interface + `AIMIStepsDataMTR`. `kotlin.time.Instant` defaults |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dump `class PkPdIntegration` | Compose `readAimiBehaviorRuntimeProfile` |
| `AIMIStepsManagerMTR` | Health Connect sync |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `java.time.Instant` → `kotlin.time.Instant` + `kotlin.time.Clock.System.now()` (not `kotlinx.datetime`).
- DTO copy as-is. No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AX.log`.

Attempt 1 **failed** (`Unresolved reference 'System'` on `kotlinx.datetime.Clock`).  
Attempt 2 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE after clock import fix.

- Spec: DTOs + steps interface only. Integration class and HC manager not copied.
- Next graph: Compose wall. UAM refresher / tick last.

Return DONE_WITH_CONCERNS.
