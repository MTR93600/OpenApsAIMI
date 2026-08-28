# Lot BH — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `4f202a3691` (Lot BG)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. `WCycleAdjuster` / `WCycleFacade` not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**WCycle disk learner is dest (androidMain).** Tick / plugin stay parked. Dest learner is not a live WCycle host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file I/O on the caller thread, same as dump. `@Volatile initialized` kept.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `wcycle/WCycleLearner.kt` | dump `Context` / `File` / `Environment` / `EnumMap`. Dest `CyclePhase` + dest `AimiStorageHelper`. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `WCycleAdjuster` | Compose-graph wall; next host lot |
| `WCycleFacade` | needs Adjuster |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Disk JSON: kotlinx parse/build + `OrgJsonCompat`. `org.json` not used.
- `CyclePhase.values()` → `CyclePhase.entries`.
- Dump unused `ctx` field kept (faithful).
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BH.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File learner only. Adjuster/Facade not copied.
- Next graph: `WCycleAdjuster` / `WCycleFacade` File+prefs, or Compose. Tick last.

Return DONE.
