# Lot BI — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c367b09d2d` (Lot BH)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**WCycle adjuster and CSV facade are dest (androidMain).** Tick / plugin stay parked. Dest facade is not a live tick host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync disk via dest learner/logger on the caller thread, same as dump.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `wcycle/WCycleAdjuster.kt` | dest learner + dest estimator/prefs. `aimiFmt2` |
| androidMain `wcycle/WCycleFacade.kt` | dest adjuster + dest CSV logger |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `String.format("%.2f", x)` → `aimiFmt2`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BI.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Adjuster + Facade only. Health Connect not added.
- Next graph: File readers (`T3c`/`Harmonia` history, physio store, HormonitorReader). Tick last.

Return DONE.
