# Lot AI — deliberate graph: PkPdRuntime extracted from Compose integration

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `195670f0b6` (Lot AH)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AH landed DualBrain helpers. Remaining Lot L was blocked on dump `PkPdRuntime` inside `pkpd/PkPdIntegration.kt`, which also calls Compose `readAimiBehaviorRuntimeProfile`. This lot is **4 dump copies + 1 extract + 1 dest restore**. Cap ~15.

**The cut:** `PkPdRuntime` and `PkpdLearningTrace` are dest-type (dest `PkPdParams` / `SmbDamping` / `InsulinActivityState`). They live in the **same dump file** as Compose `PkPdIntegration`. **Extract them into dest `pkpd/PkPdRuntime.kt`.** Do **not** copy `class PkPdIntegration` or `readAimiBehaviorRuntimeProfile`. Do **not** copy `MealAggressionContext` / `PkpdBolusSample` (only used by the integration class). This is a documented park of the integration class, like Lot W omitting the DTS builder.

Once Runtime is dest: copy `PkpdAbsorptionGuard`, `SmbDampingUsecase`, `PkpdLearningDiagnostics`, `AimiIntelligenceSnapshot`. Restore dest `PredictionAuthorityApplier.fromAuthority` (`PredictionAuthorityView` lands in the snapshot file, same package).

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM builder / Compose screens stay dump. Auditor orchestrator still needs LiveData + integration runtime builder. Tick / plugin stay parked. Dest `PkPdRuntime` is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>` unless noted.

If dest already exists: **skip and report**. Do not overwrite.

None of these dest files exist except `PredictionAuthorityApplier.kt` (restore `fromAuthority` only).

| rel | why |
|---|---|
| dest `pkpd/PkPdRuntime.kt` (**extract**) | dump `PkpdLearningTrace` + `class PkPdRuntime` from `pkpd/PkPdIntegration.kt` only. Dest `SmbDamping`. KDoc `[PkPdIntegration.computeRuntime]` → backticks |
| `pkpd/PkpdLearningDiagnostics.kt` | dest `CausalStatePosterior` / `AdaptivePkPdEstimator`. `[AimiIntelligenceSnapshot]` → FQ dest link after snapshot lands, or backticks if copied first |
| `orchestration/AimiIntelligenceSnapshot.kt` | dest IOB / governors / params; this-lot trace + diagnostics; `PredictionAuthorityView` |
| `pkpd/PkpdAbsorptionGuard.kt` | dest `InsulinActivityStage`; this-lot `PkPdRuntime`. `String.format` → `aimiFmt2` |
| `smb/SmbDampingUsecase.kt` | this-lot `PkPdRuntime` + dest `SmbDampingAudit` |
| dest `orchestration/PredictionAuthorityApplier.kt` | **restore** dump `fromAuthority`. Drop the park KDoc line |

Copy order: Runtime extract → diagnostics → snapshot → absorption guard → damping usecase → restore `fromAuthority`.

---

## Skip — do not copy this lot

Do **not** copy `class PkPdIntegration`, `MealAggressionContext`, `PkpdBolusSample`, Compose `readAimiBehaviorRuntimeProfile`, `UamHypothesisStateBuilder`, auditor orchestrator, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

1. **Metro** — none. No `@IntKey(225)`.
2. **Format** — absorption guard `toLogString`: `aimiFmt2`. No `aimiFmt3`.
3. **KDoc** — parked `[PkPdIntegration.computeRuntime]` → backticks.
4. Keep therapy math. French dump comments stay.
5. Do not overwrite dest `SmbDamping.kt` / `PkPdCore.kt`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AI.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy the integration class or Compose profile reader.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AI.md`. State Runtime extract vs integration stays dump. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
