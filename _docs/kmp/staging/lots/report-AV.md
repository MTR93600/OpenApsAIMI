# Lot AV — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `01f299a065` (Lot AU)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Compose screens not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Patient state presentation is dest.** Refresher stays dump (UAM builder). Compose wall. Tick / plugin stay parked. Dest builder is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Presentation builder is sync.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `patient/PatientStatePresentation.kt` | dest runtime snapshot / thermal / mode. Dropped `Locale`. `%+.1f` → `aimiFmtSigned1`. Dump English strings kept |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `PatientStateRuntimeRefresher` | dump `UamHypothesisStateBuilder` |
| `AimiStateTransitionManager` | `@Synchronized` / `ConcurrentLinkedQueue` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Locale / signed 1-decimal format only. Narrative strings unchanged.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AV.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: presentation text only. Refresher / tick not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
