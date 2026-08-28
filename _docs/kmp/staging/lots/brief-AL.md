# Lot AL — deliberate graph: Advisor models + tuning engine

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f951f691ba` (Lot AK)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AK landed snapshot JSON and the PKPD prediction engine. This lot is **not** another leftover hunt. Lot V / AA parked `TuningContextEngine` on dump `AdvisorMetrics` in `AdvisorModels.kt` and forbade a split. Dest now has `HarmoniaDecision`, `OrefAnalysisReport`, and `model.AimiPriority` / `AimiDomain` / `AimiAction`. The **whole** `AdvisorModels.kt` file is dest-type (`titleResId` is `Int`, not `R.string`). Copy the file, then the engine. Cap ~15. This list is **2**.

**The cut:** copy `advisor/AdvisorModels.kt` and `advisor/tuning/TuningContextEngine.kt`. Do **not** split `AdvisorMetrics` out. Do **not** copy `PkpdAdvisor` (`ResourceHelper` / `R.string`) or `TuningContextApplySupport` (`android.content.Context`). Dest already has `TuningContextModels.kt` — do **not** overwrite it.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM builder / Compose screens stay dump. Auditor host still LiveData + integration builder. Tick / plugin stay parked. Dest engine is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

None of these dest files exist. Dest `advisor/tuning/TuningContextModels.kt` already exists — do **not** overwrite.

| rel | why |
|---|---|
| `advisor/AdvisorModels.kt` | dest Harmonia / OREF report / `AimiPriority`. Explicit imports (no FQ `model.` names). KDoc `[PersistenceLayer]` → backticks |
| `advisor/tuning/TuningContextEngine.kt` | dest `AdvisorMetrics` + dest `TuningContextModels` + dest `PkpdSmbTailDamping` |

Copy order: models first, then engine.

---

## Skip — do not copy this lot

Do **not** copy `PkpdAdvisor`, `TuningContextApplySupport`, `OrefFeatureBuilder` (`Calendar`), `PkPdIntegration`, auditor orchestrator, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

1. **Metro** — none. No `@IntKey(225)`.
2. **Imports** — explicit `AimiAction` / `AimiDomain` / `AimiPriority`.
3. Keep therapy / tuning math.
4. Do not overwrite dest `TuningContextModels.kt`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AL.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Split `AdvisorModels`.
- Copy Android apply/UI advisor.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AL.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
