# Lot AQ — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e31a4159b9` (Lot AP was the user review doc, not a peel)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `:pump:medtrum` not moved to `iosMain`. Lot AP was not amended.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Patient runtime repository + loop cache are dest.** `PatientRefreshSource` extracted; dump refresher stays dump (UAM builder). Presentation stays dump (`Locale`). Tick / plugin stay parked. Dest repository is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `SharedFlow` / atomics match dump publish/clear. Not wired to tick this lot.

---

## Copied (2 + 1 extract) — dest did not exist

| rel | notes |
|---|---|
| dest `patient/PatientRefreshSource.kt` | dump enum from `PatientStateRuntimeRefresher.kt` |
| `patient/PatientStateLoopCache.kt` | dest physio / pattern / context types |
| `patient/PatientStateRuntimeRepository.kt` | `PatientRuntimeSnapshot` + repository. JVM `AtomicReference` → `kotlin.concurrent.atomics` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `PatientStateRuntimeRefresher` | dump `UamHypothesisStateBuilder` |
| `PatientStatePresentation` | `java.util.Locale` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Atomics only (`load` / `store`). Publish/clear behaviour unchanged.
- Explicit `BufferOverflow` import.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AQ.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: patient cache/repository only. Refresher / tick not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
