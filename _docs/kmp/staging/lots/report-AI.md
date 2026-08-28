# Lot AI — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `195670f0b6` (Lot AH)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `SmbDamping.kt` / `PkPdCore.kt` / DoseTerminal DTO were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**PkPdRuntime + absorption guard + damping usecase + intelligence snapshot are dest.** `class PkPdIntegration` stays dump (Compose `readAimiBehaviorRuntimeProfile`). UAM builder stays dump. Auditor orchestrator still dump (LiveData + integration builder). Tick / plugin stay parked. Dest engine is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Runtime / guard / usecase / snapshot / `fromAuthority` are sync. No new coroutines.

---

## Copied (4 dump files + 1 extract + 1 dest restore)

| rel | notes |
|---|---|
| dest `pkpd/PkPdRuntime.kt` | **extract** dump `PkpdLearningTrace` + `class PkPdRuntime`. `[PkPdIntegration.computeRuntime]` → backticks. Integration class omitted |
| `pkpd/PkpdLearningDiagnostics.kt` | dest `CausalStatePosterior` / `AdaptivePkPdEstimator` |
| `orchestration/AimiIntelligenceSnapshot.kt` | dest IOB / governors / this-lot trace + diagnostics; includes `PredictionAuthorityView` |
| `pkpd/PkpdAbsorptionGuard.kt` | dest `InsulinActivityStage` / this-lot Runtime. `String.format` → `aimiFmt2`. French dump comments kept |
| `smb/SmbDampingUsecase.kt` | this-lot Runtime + dest `SmbDampingAudit` |
| dest `orchestration/PredictionAuthorityApplier.kt` | **restore** dump `fromAuthority`. Park KDoc line dropped |

No dest file was overwritten except the documented applier restore. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `class PkPdIntegration` / `MealAggressionContext` / `PkpdBolusSample` | Compose `readAimiBehaviorRuntimeProfile`; documented park |
| `UamHypothesisStateBuilder` | Compose `AimiBehaviorRuntimeProfile` |
| `AuditorOrchestrator.kt` | LiveData + dump integration builder |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Format / KDoc backticks only. Guard / damping / snapshot math unchanged.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`.

---

## Compile

Redirect: `/tmp/aimi-lot-AI.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Runtime extract + Lot L skips + snapshot + restore `fromAuthority`. Integration class / Compose / tick not copied.
- Dump-inherited unused `windowSinceLastDoseMin` / `params` in the absorption guard stay; not introduced by this peel.
- Next graph: auditor host still blocked on LiveData + `PkPdIntegration`. Compose wall. Tick last.

Return DONE.
