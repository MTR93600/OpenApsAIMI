# Lot AD — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a7c1eee7ca` (Lot AC)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest DTS **DTO** was **not** overwritten. Lot AC dest files were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**TickContext is dest.** Recursive **engine** / adapters stay dump until a later graph. Dual-brain auditor still needs `AuditorVerdict`. UAM **builder** stays dump. `DoseTerminalSnapshotBuilder` stays dump (do not overwrite dest DTO). Tick / plugin stay parked. Dump `compose/` **screens** stay T2.

---

## Copied (4) — dest did not exist

| rel | notes |
|---|---|
| `risk/DecisionPredictionAuthority.kt` | dest meal-certainty / tree / UAM DTO / latent / posterior / post-hypo. `"%.1f"/`%.2f`.format` → `aimiFmt1` / `aimiFmt2` |
| `risk/SafetyPredictionTerminalsResolver.kt` | dest Harmonia / meal-certainty / scenario; this-lot Authority |
| `risk/AimiRiskEnvelope.kt` | dest `AimiRiskPhase` / IOB source / hypo math; this-lot Authority + terminals. `"%.0f"/`%.2f`.format` → `aimiFmt0` / `aimiFmt2` |
| `recursive/RecursiveBeliefTickContext.kt` | dest physio / curves / HTR / stacking; this-lot envelope + terminals. `[DetermineBasalaimiSMB2]` → backticks |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `DoseTerminalSnapshotBuilder` (dump `orchestration/DoseTerminalSnapshot.kt`) | dest DTO already exists — do **not** overwrite. Builder needs `PredictionAuthorityApplier` |
| recursive engine / adapters / paradox / cascade | TickContext dest is not enough; engine still dump |
| `UamHypothesisStateBuilder` | Compose |
| `AuditorDataStructures.kt` | dump `AuditorVerdict` |
| `pkpd/PkpdAbsorptionGuard.kt` / `smb/SmbDampingUsecase.kt` | remaining Lot L (`PkPdRuntime` in Compose `PkPdIntegration`) |
| tick / `OpenAPSAIMIPlugin` | parked |

Two Lot L skips stay two.

---

## Rewrite notes

- Log format strings: `String.format` is JVM-only. Replaced with `aimiFmt0` / `aimiFmt1` / `aimiFmt2`. Reason / log **numbers** unchanged.
- TickContext KDoc: parked tick class in backticks.
- No Metro. No `aimiFmt3`. No new `project()` deps. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect: `/tmp/aimi-lot-AD.log`.

Attempt 1 **FAILED**: unresolved `String.format` on Native (`AimiRiskEnvelope`, `DecisionPredictionAuthority`).

Attempt 2 **BUILD SUCCESSFUL** after `aimiFmt*` rewrites.

A `commonMain` compile is **not** “AIMI runs on iOS”. `HoldAimiEngine` stays Hold. Tick last.

---

## Review

APPROVE.

- Spec: Authority + envelope + terminals + TickContext only. No tick. No dest DTS overwrite.
- Quality: format rewrites are commonMain, not therapy edits.
- Next graph: recursive engine (if dest-type after TickContext) or `PredictionAuthorityApplier` + DTS builder park, or auditor verdict.

Return DONE.
