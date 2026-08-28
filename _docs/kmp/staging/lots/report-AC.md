# Lot AC — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0d59b8a503` (Lot AB)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Lot AB dest files were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**TickContext is still blocked** on dump `AimiRiskEnvelope` + dump `SafetyPredictionTerminalsResolver` + dump `DecisionPredictionAuthority` (copied together later — this lot did **not** copy them). Dual-brain auditor still needs `AuditorVerdict`. UAM **builder** stays dump. `DoseTerminalSnapshotBuilder` stays dump until Authority lands. Tick / plugin stay parked. Dump `compose/` **screens** stay T2.

After this lot, Authority **imports** look dest (`MealCertainty`, tree `GlobalPhysiologicalState`, UAM DTO, latent, posterior, `PostHypoDeliveryAuthority`). That is **not** this Copy list. Next lot should verify the Authority **body** and then envelope + terminals.

---

## Copied (7) — dest did not exist

| rel | notes |
|---|---|
| `patient/PhysiologicalTree.kt` | dest `PatientModeOrchestrator` / meal / thermal / WCycle; this-lot `InsulinIntent`. `lowercase(Locale.US)` → `lowercase()` |
| `patient/HarmoniaDecision.kt` | this-lot tree + `HarmoniaAction`. Two `lowercase(Locale.US)` → `lowercase()` |
| `patient/HarmoniaSmbAuthorityDecision.kt` | dest `PatternCapKind`; this-lot `HarmoniaAction`. `putFiniteOrNull` receiver `JsonObjectBuilder`; split finite/`JsonNull`; nullable cap-kind name → `JsonNull` |
| `patient/MealCertainty.kt` | dest clamp / rise-exit; this-lot tree + Harmonia. Docs path in backticks |
| `recursive/RecursiveBeliefModels.kt` | this-lot `HarmoniaSmbAuthorityDecision` only |
| `MealCorrectionContextResolver.kt` | dest snapshot / UAM DTO / latent / `PatientMode` / post-hypo; this-lot `HarmoniaAction`. Lot L skip landed |
| `basal/T3cAutodriveBasalBridge.kt` | this-lot tree types. `"%.2f".format(Locale.US)` → `aimiFmt2`. Dropped `java.util.Locale`. Lot L skip landed |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `risk/DecisionPredictionAuthority.kt` | not this list (body check next lot) |
| `risk/AimiRiskEnvelope.kt` / `SafetyPredictionTerminalsResolver.kt` | need Authority. Not split |
| `recursive/RecursiveBeliefTickContext.kt` | dump envelope / terminals |
| recursive engine / adapters | TickContext |
| `UamHypothesisStateBuilder` | Compose |
| `advisor/auditor/AuditorDataStructures.kt` | dump `AuditorVerdict`. Not split |
| `pkpd/PkpdAbsorptionGuard.kt` / `smb/SmbDampingUsecase.kt` | remaining Lot L skips (`PkPdRuntime` in Compose `PkPdIntegration`) |
| tick / `OpenAPSAIMIPlugin` | parked |

Four Lot L skips become **two** after Copy. **Not copied.**

---

## Rewrite notes

- Locale / `aimiFmt2` as briefed. Therapy math unchanged.
- `putFiniteOrNull` on `JsonObjectBuilder` (kotlinx builder, not mutable `JsonObject`). Finite vs null JSON meaning unchanged.
- Meal-certainty docs path → backticks.
- No Metro. No `aimiFmt3`. No new `project()` deps. No `@IntKey(225)`.
- ⚠️ ASYNC IMPACT: dump KDoc on `HarmoniaSmbArbiter` kept (same-tick dose path). No new async.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect: `/tmp/aimi-lot-AC.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. `HoldAimiEngine` stays Hold. Tick last.

---

## Review

APPROVE.

- Spec: only the Copy list. Tree + Harmonia + Models + two Lot L skips. No Authority / TickContext / Compose.
- Quality: Locale / kotlinx builder rewrites are commonMain, not therapy edits.
- Next graph: Authority + envelope + terminals **if** the Authority body is T1-clean.

Return DONE.
