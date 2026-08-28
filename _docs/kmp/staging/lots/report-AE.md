# Lot AE — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ac3c91a770` (Lot AD)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest TickContext / Models / Preferences / `RbtEpisodeMemory` were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Recursive engine is dest.** That is still not live tick: `DetermineBasalaimiSMB2` / `OpenAPSAIMIPlugin` stay parked. Dual-brain auditor still needs `AuditorVerdict`. UAM **builder** stays dump. `DoseTerminalSnapshotBuilder` stays dump (do not overwrite dest DTO). Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Dump `compose/` **screens** stay T2. `HoldAimiEngine` stays Hold.

---

## Copied (12) — dest did not exist

| rel | notes |
|---|---|
| `recursive/BeliefLeafAdapter.kt` | dest TickContext / leaf id |
| `recursive/BeliefLeafAdapterRegistry.kt` | dest wavelet / stacking / HTR. `fmt1`/`fmt2` + band strings → `aimiFmt1` / `aimiFmt2` |
| `recursive/BeliefLeafRegistry.kt` | this-lot registry |
| `recursive/RecursiveBeliefEngine.kt` | dest TickContext / wavelet; this-lot collect |
| `recursive/RecursiveBeliefParadox.kt` | dest TickContext / meal / stacking |
| `recursive/CredibilityCascade.kt` | dest Models scale nodes |
| `recursive/RecursiveBeliefResolver.kt` | dest Harmonia arbiter / load governor. `"%.2f".format` → `aimiFmt2` |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dest HTR evaluator |
| `recursive/RecursiveBeliefAuthorityGate.kt` | dest UAM DTO / `PatientMode` / latent |
| `recursive/RbtChaosEvaluator.kt` | dest snapshot. `"%.2f".format` → `aimiFmt2` |
| `recursive/UnfoldExporter.kt` | dest export DTOs; this-lot gate |
| `recursive/RbtResolutionBridge.kt` | dest resolution / episode memory. `"%.2f".format` → `aimiFmt2`. `[SafetyNet]` → backticks |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dest TickContext / Models / Preferences / `RbtEpisodeMemory` | already dest — not recopy |
| `DoseTerminalSnapshotBuilder` | dest DTO exists; needs `PredictionAuthorityApplier` |
| `orchestration/PredictionAuthorityApplier.kt` | not this list |
| `UamHypothesisStateBuilder` | Compose |
| `AuditorDataStructures.kt` | dump `AuditorVerdict` |
| `pkpd/PkpdAbsorptionGuard.kt` / `smb/SmbDampingUsecase.kt` | remaining Lot L |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Log format strings → `aimiFmt1` / `aimiFmt2`. Numbers unchanged.
- Bridge KDoc: `SafetyNet` in backticks (not on this module classpath as a link).
- No Metro. No `aimiFmt3`. No new `project()` deps. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect: `/tmp/aimi-lot-AE.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: engine graph only. No tick. No dest overwrite.
- Quality: format rewrites are commonMain, not therapy edits.
- Next graph: `PredictionAuthorityApplier` + DTS builder (append, do not overwrite DTO), or auditor verdict.

Return DONE.
