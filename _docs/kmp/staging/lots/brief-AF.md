# Lot AF — deliberate graph: Authority applier + DoseTerminal builder

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `9b7b9a26fd` (Lot AE)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AE landed the recursive engine. Authority is dest. This lot is **1 dump copy + 1 dest append**. Cap ~15.

**The cut:** `DoseTerminalSnapshotBuilder` needs dest `DecisionPredictionAuthority` + this-lot `PredictionAuthorityApplyResult`. Dest already has the DTS **DTO** — **append the builder; do not overwrite the DTO.**

`PredictionAuthorityApplier.fromAuthority` returns dump `PredictionAuthorityView` in `AimiIntelligenceSnapshot.kt` (`PkpdLearningDiagnostics` still dump). **Omit `fromAuthority`.** Keep `apply` / `ApplyResult` / `formatShadowLogLine`. Do not copy `AimiIntelligenceSnapshot`. This is a documented park, not a leftover DTO hunt.

**Compose-graph wall after this lot:** auditor still needs dump `HarmoniaHarmonizer`. UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Tick / plugin stay parked. Dest engine is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (1 file + 1 append)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists for a **full-file** copy: **skip and report**. Do not overwrite.

| rel | why |
|---|---|
| `orchestration/PredictionAuthorityApplier.kt` | dest `RT` / Authority / `ScenarioProjectionApplicator`. **Omit `fromAuthority`** (dump `PredictionAuthorityView`). Drop unused imports after the omit. KDoc: `fromAuthority` stays dump until `AimiIntelligenceSnapshot` is T1-clean. |
| dest `orchestration/DoseTerminalSnapshot.kt` | **APPEND** dump `object DoseTerminalSnapshotBuilder` + `shouldLiftPlateauFloorArtefact`. Add imports `ClampPkpdScenarioReconcile`, `DecisionPredictionAuthority`, `kotlin.math`. Update DTO KDoc: builder is dest. Do **not** replace the dest data class / companion. |

Copy order: applier first (so `PredictionAuthorityApplyResult` exists), then append builder.

---

## Skip — do not copy this lot

Do **not** copy `fromAuthority` / `PredictionAuthorityView` / `AimiIntelligenceSnapshot`.

Do **not** copy: `HarmoniaHarmonizer`, `AuditorDataStructures`, UAM builder, remaining Lot L, Compose screens, tick, `OpenAPSAIMIPlugin`.

Do **not** overwrite dest DTS DTO fields or `formatLogLine`.

---

## Rewrite on copy (Milos / merge rules)

1. **Metro** — none. No `@IntKey(225)`.
2. **KDoc** — dest DTS already backticks `SafetyNet`. Applier `[DecisionPredictionAuthority]` dest — keep. `[fromAuthority]` parked → backticks.
3. **Explicit imports.**
4. Keep therapy math. No `aimiFmt3`.
5. `apply` mutates `RT.eventualBG` — same as dump. ⚠️ ASYNC IMPACT: none new (same-tick RT write as dump).

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AF.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Overwrite dest DTS DTO. Copy snapshot / Harmonizer / tick / plugin.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AF.md`. State `fromAuthority` parked. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
