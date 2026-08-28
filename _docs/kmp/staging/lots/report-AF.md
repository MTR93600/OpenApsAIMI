# Lot AF — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `9b7b9a26fd` (Lot AE)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest DTS **data class / companion** were kept. Dest Lot AE recursive files were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**`fromAuthority` stays dump** (`PredictionAuthorityView` in `AimiIntelligenceSnapshot`, still needs dump `PkpdLearningDiagnostics`). **DTS builder is dest** (appended). Dual-brain auditor still needs dump `HarmoniaHarmonizer`. UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Tick / plugin stay parked. Dest recursive engine is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none new. `PredictionAuthorityApplier.apply` still writes `RT.eventualBG` on the same tick as dump.

---

## Copied (1 file + 1 append)

| rel | notes |
|---|---|
| `orchestration/PredictionAuthorityApplier.kt` | dest `RT` / Authority / scenario applicator. **`fromAuthority` omitted.** `ApplyResult` + `apply` + `formatShadowLogLine` copied |
| dest `orchestration/DoseTerminalSnapshot.kt` | builder + `shouldLiftPlateauFloorArtefact` appended. DTO KDoc no longer says builder is dump |

No dest DTO overwrite. Zero dest-exists skips for the new applier file.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `fromAuthority` / `PredictionAuthorityView` | dump `AimiIntelligenceSnapshot` (`PkpdLearningDiagnostics`) |
| `orchestration/AimiIntelligenceSnapshot.kt` | dump PKPD learning diagnostics |
| `patient/HarmoniaHarmonizer.kt` | not this list (auditor still blocked) |
| `AuditorDataStructures.kt` | dump Harmonizer |
| UAM builder / remaining Lot L / tick / plugin | parked |

---

## Rewrite notes

- Parked `fromAuthority` only. Therapy math of `apply` / DTS builder unchanged.
- No Metro. No `aimiFmt3`. No new `project()` deps. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect: `/tmp/aimi-lot-AF.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: applier without View mapper; DTS builder appended only. No tick.
- Next graph: `HarmoniaHarmonizer` + auditor structures **if** Harmonizer is T1-clean, or `PkpdLearningDiagnostics` for intelligence snapshot.

Return DONE.
